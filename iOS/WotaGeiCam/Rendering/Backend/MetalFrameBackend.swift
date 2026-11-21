#if arch(arm64) && canImport(Metal)
import Foundation
import CoreVideo
import CoreGraphics
import Metal
#if canImport(WotaCore)
import WotaCore
#endif

// MARK: - Metal 帧渲染后端（iOS 侧新写）
//
// 覆盖 A7+ / arm64（iOS 10 起）。整段用 `#if arch(arm64) && canImport(Metal)` 编译期隔离：
// armv7（iPhone 5，A6）根本不编译本文件、不 import Metal、不引用任何 Metal 符号。
// 运行期仍以 `MTLCreateSystemDefaultDevice()` 判可用（见 RenderRuntimeCapabilities）。
//
// 拓扑（**代码重写，不翻译 Android GLSL**）：两张乒乓 BGRA8 累积纹理 + 4 条渲染管线
// （累积 / 合成 / 拷贝 / 补弧），逐帧一条 command buffer 顺序编码，天然满足「写读不同体」，
// 无 feedback loop；尺寸变化即重建并清黑；失败退化为直通拷贝（绝不返回黑帧）。
// 输入/输出都是 `CVPixelBuffer`（BGRA）经 `CVMetalTextureCache` 映射的 `MTLTexture`。

public final class MetalFrameBackend: FrameRenderBackend {

    public let kind: RenderBackend = .metal
    public let renderQueue = DispatchQueue(label: "com.wotagei.render.metal")

    private let device: MTLDevice?
    private let commandQueue: MTLCommandQueue?
    private var textureCache: CVMetalTextureCache?

    private var accumPipeline: MTLRenderPipelineState?
    private var compositePipeline: MTLRenderPipelineState?
    private var copyPipeline: MTLRenderPipelineState?
    private var arcPipeline: MTLRenderPipelineState?

    private var accTexture: [MTLTexture?] = [nil, nil]
    private var accIndex = 0
    private var accumWidth = 0
    private var accumHeight = 0
    private var hasHistory = false
    /// 累积面重建或 `resetEchoHistory()` 之后，需在下一次真实渲染前清黑两张乒乓面。
    /// 否则 `encodeAccumulation` 会把「未初始化 / 陈旧」的 prev 面当作首帧历史叠加。
    private var accumNeedsClear = false
    private var ready = false

    public init() {
        let device = MTLCreateSystemDefaultDevice()
        self.device = device
        self.commandQueue = device?.makeCommandQueue()
        if let device = device, let queue = commandQueue {
            var cache: CVMetalTextureCache?
            let status = CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &cache)
            self.textureCache = (status == kCVReturnSuccess) ? cache : nil
            buildPipelines(device: device)
            ready = (textureCache != nil)
                && (accumPipeline != nil) && (compositePipeline != nil)
                && (copyPipeline != nil) && (arcPipeline != nil)
            _ = queue
        }
    }

    public var isOperational: Bool { return ready }

    public var accumulationSize: CGSize {
        return CGSize(width: accumWidth, height: accumHeight)
    }

    public func resetEchoHistory() {
        hasHistory = false
        accumNeedsClear = true
    }

    public func release() {
        accTexture = [nil, nil]
        accumWidth = 0
        accumHeight = 0
        accIndex = 0
        hasHistory = false
        accumNeedsClear = true
        if let cache = textureCache { CVMetalTextureCacheFlush(cache, 0) }
    }

    // MARK: 处理入口

    public func processEcho(frame: CVPixelBuffer, parameters: RenderEffectParameters) throws -> RenderFrameOutput {
        let (width, height) = try RenderFrameGeometry.validateBGRA(frame)
        let params = parameters.sanitized()
        let output = try RenderFrameGeometry.makeBGRAOutput(width: width, height: height)

        try withBackend { device, queue, cache in
            guard let inputTexture = makeTexture(from: frame, width: width, height: height, cache: cache, renderTarget: false),
                  let outputTexture = makeTexture(from: output, width: width, height: height, cache: cache, renderTarget: true) else {
                throw RenderBackendError.renderTargetCreationFailed(reason: "Metal 纹理映射失败")
            }
            guard let commandBuffer = queue.makeCommandBuffer() else {
                throw RenderBackendError.backendNotOperational(.metal)
            }

            var echoTexture: MTLTexture?
            if params.isEnabled {
                guard ensureAccumulation(device: device, width: width, height: height) else {
                    throw RenderBackendError.renderTargetCreationFailed(reason: "乒乓累积纹理建立失败")
                }
                if accumNeedsClear {
                    clearAccumulation(commandBuffer: commandBuffer)
                    accumNeedsClear = false
                }
                let writeIndex = 1 - accIndex
                encodeAccumulation(commandBuffer: commandBuffer,
                                   input: inputTexture,
                                   writeIndex: writeIndex,
                                   params: params)
                echoTexture = accTexture[writeIndex]
                accIndex = writeIndex
                hasHistory = true
            }

            if let echo = echoTexture {
                encodeComposite(commandBuffer: commandBuffer,
                                target: outputTexture,
                                base: inputTexture,
                                echo: echo,
                                params: params)
            } else {
                encodeCopy(commandBuffer: commandBuffer, target: outputTexture, source: inputTexture)
            }

            commandBuffer.commit()
            commandBuffer.waitUntilCompleted()
            // 保持 CVMetalTexture 存活至 GPU 完成。
            _ = inputTexture
            _ = outputTexture
            CVMetalTextureCacheFlush(cache, 0)
            if let error = commandBuffer.error {
                throw RenderBackendError.readbackFailed(reason: "Metal 命令缓冲失败：\(error.localizedDescription)")
            }
        }
        return RenderFrameOutput(pixelBuffer: output, width: width, height: height,
                                 pixelFormat: kCVPixelFormatType_32BGRA)
    }

    public func processArcMerge(frame: CVPixelBuffer, parameters: ArcMergeParameters) throws -> RenderFrameOutput {
        let (width, height) = try RenderFrameGeometry.validateBGRA(frame)
        let deadbandLuma = parameters.sanitized().deadbandLuma
        let output = try RenderFrameGeometry.makeBGRAOutput(width: width, height: height)

        try withBackend { device, queue, cache in
            guard let inputTexture = makeTexture(from: frame, width: width, height: height, cache: cache, renderTarget: false),
                  let outputTexture = makeTexture(from: output, width: width, height: height, cache: cache, renderTarget: true) else {
                throw RenderBackendError.renderTargetCreationFailed(reason: "Metal 纹理映射失败")
            }
            guard ensureAccumulation(device: device, width: width, height: height),
                  let commandBuffer = queue.makeCommandBuffer() else {
                throw RenderBackendError.renderTargetCreationFailed(reason: "乒乓累积纹理建立失败")
            }

            if accumNeedsClear {
                clearAccumulation(commandBuffer: commandBuffer)
                accumNeedsClear = false
            }

            let writeIndex = 1 - accIndex
            if hasHistory, let keptTexture = accTexture[accIndex] {
                encodeArcMerge(commandBuffer: commandBuffer,
                               target: accTexture[writeIndex]!,
                               kept: keptTexture,
                               dropped: inputTexture,
                               deadbandLuma: deadbandLuma)
            } else {
                encodeCopy(commandBuffer: commandBuffer, target: accTexture[writeIndex]!, source: inputTexture)
            }
            accIndex = writeIndex
            hasHistory = true

            encodeCopy(commandBuffer: commandBuffer, target: outputTexture, source: accTexture[accIndex]!)

            commandBuffer.commit()
            commandBuffer.waitUntilCompleted()
            _ = inputTexture
            _ = outputTexture
            CVMetalTextureCacheFlush(cache, 0)
            if let error = commandBuffer.error {
                throw RenderBackendError.readbackFailed(reason: "Metal 命令缓冲失败：\(error.localizedDescription)")
            }
        }
        return RenderFrameOutput(pixelBuffer: output, width: width, height: height,
                                 pixelFormat: kCVPixelFormatType_32BGRA)
    }

    public func previewImage(for output: RenderFrameOutput) -> CGImage? {
        return RenderPixelBufferPreview.makeCGImage(from: output.pixelBuffer)
    }

    // MARK: 编码

    private struct EchoAccumUniforms {
        var decay: Float
        var mode: Float
        var threshold: Float
        var tintR: Float
        var tintG: Float
        var tintB: Float
        var mask: Float
        var tol: Float
    }

    private struct EchoCompositeUniforms {
        var mode: Float
        var gain: Float
        var tintR: Float
        var tintG: Float
        var tintB: Float
        var tintMode: Float
    }

    private struct ArcMergeUniforms {
        var deadband: Float
    }

    private func encodeAccumulation(commandBuffer: MTLCommandBuffer, input: MTLTexture, writeIndex: Int, params: RenderEffectParameters) {
        guard let pipeline = accumPipeline, let target = accTexture[writeIndex], let prev = accTexture[1 - writeIndex] else { return }
        guard let encoder = beginPass(commandBuffer: commandBuffer, target: target, clear: true) else { return }
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(input, index: 0)
        encoder.setFragmentTexture(prev, index: 1)
        let tint = renderTrailTintRGB(params)
        var uniforms = EchoAccumUniforms(
            decay: params.feedbackDecay,
            mode: params.mode == .trail ? 1 : 0,
            threshold: params.highlightThreshold,
            tintR: tint.r, tintG: tint.g, tintB: tint.b,
            mask: renderTrailMaskUniform(params),
            tol: renderTrailTolerance(params)
        )
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<EchoAccumUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
    }

    private func encodeComposite(commandBuffer: MTLCommandBuffer, target: MTLTexture, base: MTLTexture, echo: MTLTexture, params: RenderEffectParameters) {
        guard let pipeline = compositePipeline else { return }
        guard let encoder = beginPass(commandBuffer: commandBuffer, target: target, clear: false) else { return }
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(base, index: 0)
        encoder.setFragmentTexture(echo, index: 1)
        let tint = renderTrailTintRGB(params)
        var uniforms = EchoCompositeUniforms(
            mode: params.mode == .trail ? 1 : 0,
            gain: params.trailGain,
            tintR: tint.r, tintG: tint.g, tintB: tint.b,
            tintMode: renderTrailTintModeUniform(params)
        )
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<EchoCompositeUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
    }

    private func encodeCopy(commandBuffer: MTLCommandBuffer, target: MTLTexture, source: MTLTexture) {
        guard let pipeline = copyPipeline else { return }
        guard let encoder = beginPass(commandBuffer: commandBuffer, target: target, clear: false) else { return }
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(source, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
    }

    private func encodeArcMerge(commandBuffer: MTLCommandBuffer, target: MTLTexture, kept: MTLTexture, dropped: MTLTexture, deadbandLuma: Float) {
        guard let pipeline = arcPipeline else { return }
        guard let encoder = beginPass(commandBuffer: commandBuffer, target: target, clear: false) else { return }
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(kept, index: 0)
        encoder.setFragmentTexture(dropped, index: 1)
        var uniforms = ArcMergeUniforms(deadband: deadbandLuma)
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<ArcMergeUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
    }

    private func beginPass(commandBuffer: MTLCommandBuffer, target: MTLTexture, clear: Bool) -> MTLRenderCommandEncoder? {
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].texture = target
        descriptor.colorAttachments[0].loadAction = clear ? .clear : .dontCare
        descriptor.colorAttachments[0].storeAction = .store
        descriptor.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)
        return commandBuffer.makeRenderCommandEncoder(descriptor: descriptor)
    }

    // MARK: 资源

    private func ensureAccumulation(device: MTLDevice, width: Int, height: Int) -> Bool {
        if accumWidth == width && accumHeight == height && accTexture[0] != nil && accTexture[1] != nil {
            return true
        }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: width, height: height, mipmapped: false
        )
        descriptor.usage = [.renderTarget, .shaderRead]
        guard let first = device.makeTexture(descriptor: descriptor),
              let second = device.makeTexture(descriptor: descriptor) else {
            return false
        }
        accTexture = [first, second]
        accumWidth = width
        accumHeight = height
        accIndex = 0
        hasHistory = false
        accumNeedsClear = true
        return true
    }

    /// 清黑两张累积面。`encodeAccumulation` 的 render pass 只清写面，prev 面必须由本方法先清。
    private func clearAccumulation(commandBuffer: MTLCommandBuffer) {
        for texture in accTexture {
            guard let texture = texture,
                  let encoder = beginPass(commandBuffer: commandBuffer, target: texture, clear: true) else {
                continue
            }
            encoder.endEncoding()
        }
    }

    private func makeTexture(from pixelBuffer: CVPixelBuffer, width: Int, height: Int, cache: CVMetalTextureCache, renderTarget: Bool) -> MTLTexture? {
        var cvTexture: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, cache, pixelBuffer, nil,
            .bgra8Unorm, width, height, 0, &cvTexture
        )
        guard status == kCVReturnSuccess, let cv = cvTexture else { return nil }
        return CVMetalTextureGetTexture(cv)
    }

    private func buildPipelines(device: MTLDevice) {
        let source = EchoMetalShaders.source()
        guard let library = try? device.makeLibrary(source: source, options: nil) else { return }
        accumPipeline = makePipeline(device: device, library: library, fragment: "echoAccumFragment")
        compositePipeline = makePipeline(device: device, library: library, fragment: "echoCompositeFragment")
        copyPipeline = makePipeline(device: device, library: library, fragment: "echoCopyFragment")
        arcPipeline = makePipeline(device: device, library: library, fragment: "arcMergeFragment")
    }

    private func makePipeline(device: MTLDevice, library: MTLLibrary, fragment: String) -> MTLRenderPipelineState? {
        guard let vertexFunction = library.makeFunction(name: "echoVertex"),
              let fragmentFunction = library.makeFunction(name: fragment) else {
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm
        return try? device.makeRenderPipelineState(descriptor: descriptor)
    }

    private func withBackend(_ body: (MTLDevice, MTLCommandQueue, CVMetalTextureCache) throws -> Void) throws {
        guard ready, let device = device, let queue = commandQueue, let cache = textureCache else {
            throw RenderBackendError.backendNotOperational(.metal)
        }
        try body(device, queue, cache)
    }
}
#endif
