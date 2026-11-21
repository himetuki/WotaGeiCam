import Foundation
import CoreVideo
import CoreGraphics
import Dispatch
#if canImport(WotaCore)
import WotaCore
#endif

// MARK: - CPU 兜底后端
//
// 当 Metal / OpenGL ES 都不可用时（例如受限环境）交付**真实处理后的帧**，绝不返回永远黑/空占位。
// 允许降分辨率（在内部工作分辨率上做反馈累积，再上采样合成）与降倍率，但**保留全部核心参数入口**：
// 模式、倍数、衰减、阈值、强度、拖尾色模式/色/容差、补弧死区都照常生效，只是拖尾细节粒度受
// 工作分辨率限制（拖尾本就是柔和的，观感可接受）。算法语义与 GPU 后端逐项同源。

/// CPU 逐像素 / 帧缓冲降级后端。
public final class CPUFrameBackend: FrameRenderBackend {

    public let kind: RenderBackend = .cpu
    public let renderQueue = DispatchQueue(label: "com.wotagei.render.cpu")

    /// CPU 降级的内部工作分辨率上限（最长边）。减少内存与每帧开销；输入更大时按其等比下调。
    /// 注意：这只影响**回波累积面**的粒度，输出恒为输入同尺寸；上层参数入口不受影响。
    public let maxWorkingEdge: Int

    public init(maxWorkingEdge: Int = 1280) {
        self.maxWorkingEdge = max(64, maxWorkingEdge)
    }

    public var isOperational: Bool { return true }

    public var accumulationSize: CGSize {
        return CGSize(width: workingWidth, height: workingHeight)
    }

    // MARK: 内部状态（全部在 renderQueue 上访问）

    private var workingWidth = 0
    private var workingHeight = 0

    /// 回波累积面（乒乓两张，交错 RGB，0..1）。`accRead` 是上一帧历史，`accWrite` 是本帧产出。
    private var accReadR = [Float]()
    private var accReadG = [Float]()
    private var accReadB = [Float]()
    private var accWriteR = [Float]()
    private var accWriteG = [Float]()
    private var accWriteB = [Float]()

    /// 工作分辨率的本帧（降采样输入，供累积读取）。
    private var workR = [Float]()
    private var workG = [Float]()
    private var workB = [Float]()

    /// 是否已有一帧历史（首帧 prev 视为黑 ⇒ 恒等）。
    private var hasHistory = false

    // MARK: 生命周期

    public func resetEchoHistory() {
        if workingWidth > 0 && workingHeight > 0 {
            clearAccumulation()
        }
        hasHistory = false
    }

    public func release() {
        workingWidth = 0
        workingHeight = 0
        accReadR = []; accReadG = []; accReadB = []
        accWriteR = []; accWriteG = []; accWriteB = []
        workR = []; workG = []; workB = []
        hasHistory = false
    }

    // MARK: 回波处理

    public func processEcho(frame: CVPixelBuffer, parameters: RenderEffectParameters) throws -> RenderFrameOutput {
        let (width, height) = try RenderFrameGeometry.validateBGRA(frame)
        let output = try RenderFrameGeometry.makeBGRAOutput(width: width, height: height)
        let params = parameters.sanitized()

        ensureWorkingSize(width: width, height: height)

        try lockAndAccess(frame, output) { source, destination, sRow, dRow in
            // 1) 降采样输入 → 工作分辨率本帧
            downscale(source: source, srcRow: sRow, width: width, height: height)
            // 2) 累积：acc_write = f(cur, k·acc_read)
            if params.isEnabled {
                accumulateEcho(params: params)
            } else {
                // 关闭：新面 = 本帧（恒等），输出原样拷贝。
                copyWorkToAccWrite()
            }
            // 3) 全分辨率合成：out = applyEcho(cur, acc_write)
            compositeEcho(destination: destination, dstRow: dRow,
                          width: width, height: height,
                          source: source, srcRow: sRow, params: params)
            // 4) 乒乓交换：本帧产出成为下一帧的历史
            swapAccumulation()
            hasHistory = true
        }
        return RenderFrameOutput(pixelBuffer: output, width: width, height: height,
                                 pixelFormat: kCVPixelFormatType_32BGRA)
    }

    public func processArcMerge(frame: CVPixelBuffer, parameters: ArcMergeParameters) throws -> RenderFrameOutput {
        let (width, height) = try RenderFrameGeometry.validateBGRA(frame)
        let output = try RenderFrameGeometry.makeBGRAOutput(width: width, height: height)
        let deadbandLuma = parameters.sanitized().deadbandLuma

        ensureWorkingSize(width: width, height: height)

        try lockAndAccess(frame, output) { source, destination, sRow, dRow in
            downscale(source: source, srcRow: sRow, width: width, height: height)
            if hasHistory {
                arcMerge(droppedIs: workR, droppedG: workG, droppedB: workB, deadbandLuma: deadbandLuma)
            } else {
                // 首帧无历史：累积面 = 本帧。
                copyWorkToAccWrite()
            }
            // 输出累积面（上采样回全分辨率）。
            upscaleAccToOutput(destination: destination, dstRow: dRow, width: width, height: height)
            swapAccumulation()
            hasHistory = true
        }
        return RenderFrameOutput(pixelBuffer: output, width: width, height: height,
                                 pixelFormat: kCVPixelFormatType_32BGRA)
    }

    public func previewImage(for output: RenderFrameOutput) -> CGImage? {
        return RenderPixelBufferPreview.makeCGImage(from: output.pixelBuffer)
    }

    // MARK: 工作区

    private func ensureWorkingSize(width: Int, height: Int) {
        let longest = max(width, height)
        let scale: Double = longest > maxWorkingEdge ? Double(maxWorkingEdge) / Double(longest) : 1.0
        let w = max(1, Int((Double(width) * scale).rounded()))
        let h = max(1, Int((Double(height) * scale).rounded()))
        if w == workingWidth && h == workingHeight { return }
        workingWidth = w
        workingHeight = h
        let count = w * h
        workR = [Float](repeating: 0, count: count)
        workG = [Float](repeating: 0, count: count)
        workB = [Float](repeating: 0, count: count)
        accReadR = [Float](repeating: 0, count: count)
        accReadG = [Float](repeating: 0, count: count)
        accReadB = [Float](repeating: 0, count: count)
        accWriteR = [Float](repeating: 0, count: count)
        accWriteG = [Float](repeating: 0, count: count)
        accWriteB = [Float](repeating: 0, count: count)
        hasHistory = false
        // 尺寸变化即清黑（R2 §五.2/§五.6）。
    }

    private func clearAccumulation() {
        for i in accReadR.indices {
            accReadR[i] = 0; accReadG[i] = 0; accReadB[i] = 0
            accWriteR[i] = 0; accWriteG[i] = 0; accWriteB[i] = 0
        }
        hasHistory = false
    }

    private func swapAccumulation() {
        swap(&accReadR, &accWriteR)
        swap(&accReadG, &accWriteG)
        swap(&accReadB, &accWriteB)
    }

    private func copyWorkToAccWrite() {
        for i in workR.indices {
            accWriteR[i] = workR[i]
            accWriteG[i] = workG[i]
            accWriteB[i] = workB[i]
        }
    }

    // MARK: 逐像素核

    private func accumulateEcho(params: RenderEffectParameters) {
        let decay = params.feedbackDecay
        let trailMode = params.mode == .trail
        let threshold = params.highlightThreshold
        let mask = renderTrailMaskUniform(params)   // 0/1/2
        let tint = renderTrailTintRGB(params)
        let tolerance = renderTrailTolerance(params)
        let lo = threshold - 0.05
        let hi = threshold + 0.05

        var index = 0
        while index < workR.count {
            let cr = workR[index], cg = workG[index], cb = workB[index]
            let pr = accReadR[index] * decay
            let pg = accReadG[index] * decay
            let pb = accReadB[index] * decay

            if !trailMode {
                // 保持亮度：acc = max(cur, k·prev)
                accWriteR[index] = max(cr, pr)
                accWriteG[index] = max(cg, pg)
                accWriteB[index] = max(cb, pb)
            } else {
                // 提亮拖尾：t = smoothstep(thr∓0.05, luma)（择/除改用 RGB 峰值并按色度门控）
                var t = smoothstep(lo, hi, renderLuma(cr, cg, cb))
                if mask > 0.5 {
                    let peak = max(cr, max(cg, cb))
                    let tintPeak = max(tint.r, max(tint.g, tint.b))
                    let sat = (peak - min(cr, min(cg, cb))) / max(peak, 0.0001)
                    let tintSat = (tintPeak - min(tint.r, min(tint.g, tint.b))) / max(tintPeak, 0.0001)
                    let m: Float
                    if sat < 0.05 || tintSat < 0.05 {
                        m = (sat < 0.05 && tintSat < 0.05) ? 1.0 : 0.0
                    } else {
                        let chromaR = cr / max(peak, 0.0001)
                        let chromaG = cg / max(peak, 0.0001)
                        let chromaB = cb / max(peak, 0.0001)
                        let tintChromaR = tint.r / max(tintPeak, 0.0001)
                        let tintChromaG = tint.g / max(tintPeak, 0.0001)
                        let tintChromaB = tint.b / max(tintPeak, 0.0001)
                        let dr = chromaR - tintChromaR
                        let dg = chromaG - tintChromaG
                        let db = chromaB - tintChromaB
                        let chromaGap = (dr * dr + dg * dg + db * db).squareRoot()
                        m = 1.0 - smoothstep(tolerance, tolerance + 0.1, chromaGap)
                    }
                    t = smoothstep(lo, hi, peak)
                    t *= (mask < 1.5) ? m : (1.0 - m)
                }
                accWriteR[index] = cr * t + pr
                accWriteG[index] = cg * t + pg
                accWriteB[index] = cb * t + pb
            }
            index += 1
        }
    }

    /// 全分辨率合成：读取输入原帧，双线性上采样累积面，套 `applyEcho`。
    private func compositeEcho(
        destination: UnsafeMutableRawPointer, dstRow: Int,
        width: Int, height: Int,
        source: UnsafeMutableRawPointer, srcRow: Int,
        params: RenderEffectParameters
    ) {
        let decayDisabled = !params.isEnabled
        let trailMode = params.mode == .trail
        let gain = params.trailGain
        let tintMode = renderTrailTintModeUniform(params)   // 0/1/2
        let tint = renderTrailTintRGB(params)

        let scaleX = workingWidth > 1 ? Float(workingWidth - 1) / Float(max(width - 1, 1)) : 0
        let scaleY = workingHeight > 1 ? Float(workingHeight - 1) / Float(max(height - 1, 1)) : 0

        for y in 0..<height {
            let dst = destination.advanced(by: y * dstRow)
            let src = source.advanced(by: y * srcRow)
            let fy = Float(y) * scaleY
            for x in 0..<width {
                let srcPixel = src.advanced(by: x * 4)
                let b = Float(srcPixel.load(fromByteOffset: 0, as: UInt8.self)) / 255.0
                let g = Float(srcPixel.load(fromByteOffset: 1, as: UInt8.self)) / 255.0
                let r = Float(srcPixel.load(fromByteOffset: 2, as: UInt8.self)) / 255.0

                var outR = r, outG = g, outB = b
                if !decayDisabled {
                    let fx = Float(x) * scaleX
                    let e = sampleAccBilinear(fx: fx, fy: fy)   // (r, g, b) 已是累积面（含 k·prev）
                    if !trailMode {
                        outR = max(r, e.r); outG = max(g, e.g); outB = max(b, e.b)
                    } else {
                        var er = e.r, eg = e.g, eb = e.b
                        if tintMode > 0.5 {
                            let factorR = tintMode < 1.5 ? tint.r : (1.0 - tint.r)
                            let factorG = tintMode < 1.5 ? tint.g : (1.0 - tint.g)
                            let factorB = tintMode < 1.5 ? tint.b : (1.0 - tint.b)
                            er *= factorR; eg *= factorG; eb *= factorB
                        }
                        outR = min(r + er * gain, 1.0)
                        outG = min(g + eg * gain, 1.0)
                        outB = min(b + eb * gain, 1.0)
                    }
                }
                let dstPixel = dst.advanced(by: x * 4)
                dstPixel.storeBytes(of: UInt8((outB * 255.0).rounded().clampedByte), toByteOffset: 0, as: UInt8.self)
                dstPixel.storeBytes(of: UInt8((outG * 255.0).rounded().clampedByte), toByteOffset: 1, as: UInt8.self)
                dstPixel.storeBytes(of: UInt8((outR * 255.0).rounded().clampedByte), toByteOffset: 2, as: UInt8.self)
                dstPixel.storeBytes(of: 255, toByteOffset: 3, as: UInt8.self)
            }
        }
    }

    /// 差分死区整像素合并：acc = select(acc, cur)。
    private func arcMerge(droppedIs: [Float], droppedG: [Float], droppedB: [Float], deadbandLuma: Float) {
        var index = 0
        while index < accReadR.count {
            let kept = (accReadR[index], accReadG[index], accReadB[index])
            let dropped = (droppedIs[index], droppedG[index], droppedB[index])
            let lumaKept = arcMergeLumaOfRGB(kept.0, kept.1, kept.2)
            let lumaDropped = arcMergeLumaOfRGB(dropped.0, dropped.1, dropped.2)
            let take = (lumaDropped - lumaKept) > deadbandLuma
            accWriteR[index] = take ? dropped.0 : kept.0
            accWriteG[index] = take ? dropped.1 : kept.1
            accWriteB[index] = take ? dropped.2 : kept.2
            index += 1
        }
    }

    // MARK: 采样/缩放

    private func downscale(source: UnsafeMutableRawPointer, srcRow: Int, width: Int, height: Int) {
        let scaleX = width > 1 ? Float(width - 1) / Float(max(workingWidth - 1, 1)) : 0
        let scaleY = height > 1 ? Float(height - 1) / Float(max(workingHeight - 1, 1)) : 0
        for wy in 0..<workingHeight {
            let sy = Float(wy) * scaleY
            for wx in 0..<workingWidth {
                let sx = Float(wx) * scaleX
                let sample = sampleSourceBilinear(source: source, srcRow: srcRow,
                                                  width: width, height: height, fx: sx, fy: sy)
                let index = wy * workingWidth + wx
                workR[index] = sample.r
                workG[index] = sample.g
                workB[index] = sample.b
            }
        }
    }

    private func sampleSourceBilinear(
        source: UnsafeMutableRawPointer, srcRow: Int,
        width: Int, height: Int, fx: Float, fy: Float
    ) -> (r: Float, g: Float, b: Float) {
        let x0 = min(max(Int(fx), 0), width - 1)
        let y0 = min(max(Int(fy), 0), height - 1)
        let x1 = min(x0 + 1, width - 1)
        let y1 = min(y0 + 1, height - 1)
        let tx = fx - Float(x0)
        let ty = fy - Float(y0)
        let p00 = readSource(source: source, srcRow: srcRow, x: x0, y: y0)
        let p10 = readSource(source: source, srcRow: srcRow, x: x1, y: y0)
        let p01 = readSource(source: source, srcRow: srcRow, x: x0, y: y1)
        let p11 = readSource(source: source, srcRow: srcRow, x: x1, y: y1)
        let r = (p00.r * (1 - tx) + p10.r * tx) * (1 - ty) + (p01.r * (1 - tx) + p11.r * tx) * ty
        let g = (p00.g * (1 - tx) + p10.g * tx) * (1 - ty) + (p01.g * (1 - tx) + p11.g * tx) * ty
        let b = (p00.b * (1 - tx) + p10.b * tx) * (1 - ty) + (p01.b * (1 - tx) + p11.b * tx) * ty
        return (r, g, b)
    }

    private func readSource(
        source: UnsafeMutableRawPointer, srcRow: Int, x: Int, y: Int
    ) -> (r: Float, g: Float, b: Float) {
        let pixel = source.advanced(by: y * srcRow + x * 4)
        let b = Float(pixel.load(fromByteOffset: 0, as: UInt8.self)) / 255.0
        let g = Float(pixel.load(fromByteOffset: 1, as: UInt8.self)) / 255.0
        let r = Float(pixel.load(fromByteOffset: 2, as: UInt8.self)) / 255.0
        return (r, g, b)
    }

    private func sampleAccBilinear(fx: Float, fy: Float) -> (r: Float, g: Float, b: Float) {
        let x0 = min(max(Int(fx), 0), workingWidth - 1)
        let y0 = min(max(Int(fy), 0), workingHeight - 1)
        let x1 = min(x0 + 1, workingWidth - 1)
        let y1 = min(y0 + 1, workingHeight - 1)
        let tx = fx - Float(x0)
        let ty = fy - Float(y0)
        func at(_ index: Int) -> (Float, Float, Float) {
            return (accWriteR[index], accWriteG[index], accWriteB[index])
        }
        let i00 = y0 * workingWidth + x0
        let i10 = y0 * workingWidth + x1
        let i01 = y1 * workingWidth + x0
        let i11 = y1 * workingWidth + x1
        let p00 = at(i00), p10 = at(i10), p01 = at(i01), p11 = at(i11)
        let r = (p00.0 * (1 - tx) + p10.0 * tx) * (1 - ty) + (p01.0 * (1 - tx) + p11.0 * tx) * ty
        let g = (p00.1 * (1 - tx) + p10.1 * tx) * (1 - ty) + (p01.1 * (1 - tx) + p11.1 * tx) * ty
        let b = (p00.2 * (1 - tx) + p10.2 * tx) * (1 - ty) + (p01.2 * (1 - tx) + p11.2 * tx) * ty
        return (r, g, b)
    }

    private func upscaleAccToOutput(
        destination: UnsafeMutableRawPointer, dstRow: Int, width: Int, height: Int
    ) {
        let scaleX = width > 1 ? Float(width - 1) / Float(max(workingWidth - 1, 1)) : 0
        let scaleY = height > 1 ? Float(height - 1) / Float(max(workingHeight - 1, 1)) : 0
        for y in 0..<height {
            let dst = destination.advanced(by: y * dstRow)
            let fy = Float(y) * scaleY
            for x in 0..<width {
                let fx = Float(x) * scaleX
                let e = sampleAccBilinear(fx: fx, fy: fy)
                let dstPixel = dst.advanced(by: x * 4)
                dstPixel.storeBytes(of: UInt8((e.b * 255.0).rounded().clampedByte), toByteOffset: 0, as: UInt8.self)
                dstPixel.storeBytes(of: UInt8((e.g * 255.0).rounded().clampedByte), toByteOffset: 1, as: UInt8.self)
                dstPixel.storeBytes(of: UInt8((e.r * 255.0).rounded().clampedByte), toByteOffset: 2, as: UInt8.self)
                dstPixel.storeBytes(of: 255, toByteOffset: 3, as: UInt8.self)
            }
        }
    }

    // MARK: 加锁/解锁

    private func lockAndAccess(
        _ input: CVPixelBuffer, _ output: CVPixelBuffer,
        _ body: (UnsafeMutableRawPointer, UnsafeMutableRawPointer, Int, Int) -> Void
    ) throws {
        CVPixelBufferLockBaseAddress(input, CVPixelBufferLockFlags(rawValue: 0))
        CVPixelBufferLockBaseAddress(output, CVPixelBufferLockFlags(rawValue: 0))
        defer {
            CVPixelBufferUnlockBaseAddress(output, CVPixelBufferLockFlags(rawValue: 0))
            CVPixelBufferUnlockBaseAddress(input, CVPixelBufferLockFlags(rawValue: 0))
        }
        // 锁失败绝不静默返回：否则调用方会交出未写入的输出面（黑帧/垃圾帧）。
        guard let source = CVPixelBufferGetBaseAddress(input),
              let destination = CVPixelBufferGetBaseAddress(output) else {
            throw RenderBackendError.renderTargetCreationFailed(reason: "像素缓冲锁定失败（基址不可用）")
        }
        body(source, destination, CVPixelBufferGetBytesPerRow(input), CVPixelBufferGetBytesPerRow(output))
    }
}

// MARK: - 数学工具

private func renderLuma(_ r: Float, _ g: Float, _ b: Float) -> Float {
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/// GLSL `smoothstep`。
private func smoothstep(_ edge0: Float, _ edge1: Float, _ x: Float) -> Float {
    if edge1 <= edge0 { return x < edge0 ? 0 : 1 }
    let t = min(max((x - edge0) / (edge1 - edge0), 0), 1)
    return t * t * (3 - 2 * t)
}

private extension Float {
    var clampedByte: Float {
        if !isFinite { return 0 }
        if self < 0 { return 0 }
        if self > 255 { return 255 }
        return self
    }
}
