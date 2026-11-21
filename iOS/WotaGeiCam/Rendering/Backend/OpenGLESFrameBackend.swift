#if canImport(OpenGLES)
import Foundation
import CoreVideo
import CoreGraphics
import Dispatch
import OpenGLES
#if canImport(WotaCore)
import WotaCore
#endif

// MARK: - OpenGL ES 2 帧渲染后端（iOS 侧新写）
//
// 目标：iOS 10 / iPhone 5（A6，armv7，无 Metal）。运行前检查 EAGLContext 可建性（ES2）。
// 拓扑与 Android 的 ArcMendPass / EchoChain 同构（**代码重写，不翻译 GL**）：
//   - 两张乒乓累积面（RGBA8 纹理 + FBO），写读不同体，无 feedback loop；
//   - 首帧 prev 视为黑 ⇒ 恒等；尺寸变化即重建并清黑（R2 §五.2/§五.6）；
//   - 失败退化为「没有拖影/补弧」，绝不返回黑帧（参数关闭时走直通拷贝）。
//
// 输入/输出都是 `CVPixelBuffer`（BGRA），经 `CVOpenGLESTextureCache` 映射为 2D 纹理，
// 不引入 OES 外部纹理与相机会话依赖。

public final class OpenGLESFrameBackend: FrameRenderBackend {

    public let kind: RenderBackend = .openGLES
    public let renderQueue = DispatchQueue(label: "com.wotagei.render.opengles")

    private let context: EAGLContext?
    private var textureCache: CVOpenGLESTextureCache?

    private var accumProgram: GLuint = 0
    private var compositeProgram: GLuint = 0
    private var copyProgram: GLuint = 0
    private var arcMergeProgram: GLuint = 0

    // 属性/一致变量位置
    private struct Attrib { var position: GLint = -1; var texCoord: GLint = -1 }
    private var accumAttrib = Attrib()
    private var compositeAttrib = Attrib()
    private var copyAttrib = Attrib()
    private var arcAttrib = Attrib()

    private var accumUCur: GLint = -1
    private var accumUPrev: GLint = -1
    private var accumUDecay: GLint = -1
    private var accumUMode: GLint = -1
    private var accumUThreshold: GLint = -1
    private var accumUTint: GLint = -1
    private var accumUMask: GLint = -1
    private var accumUTol: GLint = -1

    private var compositeUBase: GLint = -1
    private var compositeUEcho: GLint = -1
    private var compositeUMode: GLint = -1
    private var compositeUGain: GLint = -1
    private var compositeUTint: GLint = -1
    private var compositeUTintMode: GLint = -1

    private var copyUFrame: GLint = -1
    private var arcUA: GLint = -1
    private var arcUB: GLint = -1
    private var arcUDeadband: GLint = -1

    // 乒乓累积面
    private var accTexture = [GLuint](repeating: 0, count: 2)
    private var accFramebuffer = [GLuint](repeating: 0, count: 2)
    private var accIndex = 0
    private var accumWidth = 0
    private var accumHeight = 0
    private var hasHistory = false

    private var outputFramebuffer: GLuint = 0
    private var ready = false

    /// 渲染队列专属标记：用于让 `resetEchoHistory()` / `release()` 从任意队列安全落回
    /// ``renderQueue``（GL 对象与 `EAGLContext` 的 `setCurrent` 都是线程局部且互斥的）。
    private let renderQueueKey = DispatchSpecificKey<UInt8>()

    /// 需在下一次真实渲染前清黑累积面（`resetEchoHistory()` 时无法安全进入 GL，或上下文临时不可用）。
    private var pendingAccumulationClear = false

    // 全屏四边形（TRIANGLE_STRIP）
    private let positions: [GLfloat] = [-1, -1, 1, -1, -1, 1, 1, 1]
    private let texCoords: [GLfloat] = [0, 0, 1, 0, 0, 1, 1, 1]

    public init() {
        self.context = EAGLContext(api: .openGLES2)
        renderQueue.setSpecific(key: renderQueueKey, value: 1)
        // 建上下文时保存并恢复进入前的当前上下文，避免覆盖同线程上其他 EAGLContext 的 current 状态。
        let previous = EAGLContext.current()
        if let context = self.context, EAGLContext.setCurrent(context) {
            _ = prepareResources()
            if EAGLContext.current() === context { EAGLContext.setCurrent(previous) }
        }
    }

    public var isOperational: Bool { return ready }

    public var accumulationSize: CGSize {
        return CGSize(width: accumWidth, height: accumHeight)
    }

    public func resetEchoHistory() {
        // GL 状态必须与 process* 串行，且只能落在持有 EAGLContext 的线程上：统一落回 renderQueue。
        runOnRenderQueue { self.resetEchoHistoryOnQueue() }
    }

    private func resetEchoHistoryOnQueue() {
        hasHistory = false
        pendingAccumulationClear = false
        guard ready, accFramebuffer[0] != 0, accFramebuffer[1] != 0 else { return }
        do {
            try withContext { clearAccumulation() }
        } catch {
            // 上下文暂时不可用时，交给下一次 process*（已保证在 renderQueue + 上下文上）清黑。
            pendingAccumulationClear = true
        }
    }

    public func release() {
        // 删除 GL 对象与改 current 上下文必须与渲染串行；从任意队列调用都会同步落回 renderQueue。
        runOnRenderQueue { self.releaseOnQueue() }
    }

    private func releaseOnQueue() {
        hasHistory = false
        pendingAccumulationClear = false
        accumWidth = 0
        accumHeight = 0
        guard let context = context else { ready = false; return }
        let previous = EAGLContext.current()
        guard EAGLContext.setCurrent(context) else { ready = false; return }
        releaseAccumulation()
        if accumProgram != 0 { glDeleteProgram(accumProgram); accumProgram = 0 }
        if compositeProgram != 0 { glDeleteProgram(compositeProgram); compositeProgram = 0 }
        if copyProgram != 0 { glDeleteProgram(copyProgram); copyProgram = 0 }
        if arcMergeProgram != 0 { glDeleteProgram(arcMergeProgram); arcMergeProgram = 0 }
        if outputFramebuffer != 0 { glDeleteFramebuffers(1, &outputFramebuffer); outputFramebuffer = 0 }
        if let cache = textureCache { CVOpenGLESTextureCacheFlush(cache, 0) }
        textureCache = nil
        ready = false
        if EAGLContext.current() === context { EAGLContext.setCurrent(previous) }
    }

    // MARK: 处理入口

    public func processEcho(frame: CVPixelBuffer, parameters: RenderEffectParameters) throws -> RenderFrameOutput {
        let (width, height) = try RenderFrameGeometry.validateBGRA(frame)
        let params = parameters.sanitized()
        let output = try RenderFrameGeometry.makeBGRAOutput(width: width, height: height)

        try withContext {
            guard prepareResources(), let cache = textureCache else {
                throw RenderBackendError.backendNotOperational(.openGLES)
            }
            guard let inputTexture = makeTexture(from: frame, width: width, height: height, cache: cache) else {
                throw RenderBackendError.renderTargetCreationFailed(reason: "输入纹理建立失败")
            }
            let inputName = CVOpenGLESTextureGetName(inputTexture)

            if params.isEnabled {
                guard ensureAccumulation(width: width, height: height) else {
                    throw RenderBackendError.renderTargetCreationFailed(reason: "乒乓累积面建立失败")
                }
                let newIndex = 1 - accIndex
                drawEchoAccumulation(input: inputName, writeIndex: newIndex, params: params, width: width, height: height)
            }

            glBindFramebuffer(GLenum(GL_FRAMEBUFFER), outputFramebuffer)
            guard let outputTexture = makeTexture(from: output, width: width, height: height, cache: cache) else {
                throw RenderBackendError.renderTargetCreationFailed(reason: "输出纹理建立失败")
            }
            let outputName = CVOpenGLESTextureGetName(outputTexture)
            glFramebufferTexture2D(
                GLenum(GL_FRAMEBUFFER), GLenum(GL_COLOR_ATTACHMENT0),
                GLenum(GL_TEXTURE_2D), outputName, 0
            )
            if glCheckFramebufferStatus(GLenum(GL_FRAMEBUFFER)) != GLenum(GL_FRAMEBUFFER_COMPLETE) {
                throw RenderBackendError.renderTargetCreationFailed(reason: "输出 FBO 不完整")
            }
            glViewport(0, 0, GLsizei(width), GLsizei(height))

            // 只检查本帧绘制产生的错误：先清掉建纹理/FBO 阶段的陈旧错误。
            clearGLErrors()
            if params.isEnabled {
                let echoIndex = 1 - accIndex
                drawComposite(base: inputName, echo: accTexture[echoIndex], params: params, attrib: compositeAttrib)
                accIndex = echoIndex
                hasHistory = true
            } else {
                drawCopy(source: inputName, attrib: copyAttrib)
            }

            glBindFramebuffer(GLenum(GL_FRAMEBUFFER), 0)
            // 同步帧契约：CPU/编码器会在 process* 返回后读取输出，必须等 GPU 真正完成，
            // 而非仅提交命令流。Metal 后端用 waitUntilCompleted()，这里用 glFinish() 对应。
            glFinish()
            let readbackError = glGetError()
            CVOpenGLESTextureCacheFlush(cache, 0)
            _ = outputTexture
            _ = inputTexture
            if readbackError != GLenum(GL_NO_ERROR) {
                throw RenderBackendError.readbackFailed(reason: "OpenGL ES 读回失败（glGetError=\(readbackError)）")
            }
        }
        return RenderFrameOutput(pixelBuffer: output, width: width, height: height,
                                 pixelFormat: kCVPixelFormatType_32BGRA)
    }

    public func processArcMerge(frame: CVPixelBuffer, parameters: ArcMergeParameters) throws -> RenderFrameOutput {
        let (width, height) = try RenderFrameGeometry.validateBGRA(frame)
        let deadbandLuma = parameters.sanitized().deadbandLuma
        let output = try RenderFrameGeometry.makeBGRAOutput(width: width, height: height)

        try withContext {
            guard prepareResources(), let cache = textureCache else {
                throw RenderBackendError.backendNotOperational(.openGLES)
            }
            guard ensureAccumulation(width: width, height: height) else {
                throw RenderBackendError.renderTargetCreationFailed(reason: "乒乓累积面建立失败")
            }
            guard let inputTexture = makeTexture(from: frame, width: width, height: height, cache: cache) else {
                throw RenderBackendError.renderTargetCreationFailed(reason: "输入纹理建立失败")
            }
            let inputName = CVOpenGLESTextureGetName(inputTexture)

            let writeIndex = 1 - accIndex
            glBindFramebuffer(GLenum(GL_FRAMEBUFFER), accFramebuffer[writeIndex])
            glViewport(0, 0, GLsizei(width), GLsizei(height))
            clearGLErrors()
            if hasHistory {
                drawArcMerge(kept: accTexture[accIndex], dropped: inputName,
                             deadbandLuma: deadbandLuma, attrib: arcAttrib)
            } else {
                drawCopy(source: inputName, attrib: copyAttrib)
            }
            accIndex = writeIndex
            hasHistory = true

            // 输出累积面（拷贝到输出纹理）。
            glBindFramebuffer(GLenum(GL_FRAMEBUFFER), outputFramebuffer)
            guard let outputTexture = makeTexture(from: output, width: width, height: height, cache: cache) else {
                throw RenderBackendError.renderTargetCreationFailed(reason: "输出纹理建立失败")
            }
            glFramebufferTexture2D(
                GLenum(GL_FRAMEBUFFER), GLenum(GL_COLOR_ATTACHMENT0),
                GLenum(GL_TEXTURE_2D), CVOpenGLESTextureGetName(outputTexture), 0
            )
            glViewport(0, 0, GLsizei(width), GLsizei(height))
            drawCopy(source: accTexture[accIndex], attrib: copyAttrib)
            glBindFramebuffer(GLenum(GL_FRAMEBUFFER), 0)
            // 同步帧契约：CPU/编码器会在 process* 返回后读取输出，必须等 GPU 真正完成，
            // 而非仅提交命令流。Metal 后端用 waitUntilCompleted()，这里用 glFinish() 对应。
            glFinish()
            let readbackError = glGetError()
            CVOpenGLESTextureCacheFlush(cache, 0)
            _ = outputTexture
            _ = inputTexture
            if readbackError != GLenum(GL_NO_ERROR) {
                throw RenderBackendError.readbackFailed(reason: "OpenGL ES 读回失败（glGetError=\(readbackError)）")
            }
        }
        return RenderFrameOutput(pixelBuffer: output, width: width, height: height,
                                 pixelFormat: kCVPixelFormatType_32BGRA)
    }

    public func previewImage(for output: RenderFrameOutput) -> CGImage? {
        return RenderPixelBufferPreview.makeCGImage(from: output.pixelBuffer)
    }

    // MARK: 绘制

    /// 清空当前上下文累积的 GL 错误（`glGetError` 每次只取出一个）。
    private func clearGLErrors() {
        while glGetError() != GLenum(GL_NO_ERROR) {}
    }

    private func drawEchoAccumulation(input: GLuint, writeIndex: Int, params: RenderEffectParameters, width: Int, height: Int) {
        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), accFramebuffer[writeIndex])
        glViewport(0, 0, GLsizei(width), GLsizei(height))
        glUseProgram(accumProgram)
        glActiveTexture(GLenum(GL_TEXTURE0))
        glBindTexture(GLenum(GL_TEXTURE_2D), input)
        glActiveTexture(GLenum(GL_TEXTURE1))
        glBindTexture(GLenum(GL_TEXTURE_2D), accTexture[1 - writeIndex])
        setUniform1i(accumUCur, 0)
        setUniform1i(accumUPrev, 1)
        setUniform1f(accumUDecay, params.feedbackDecay)
        setUniform1f(accumUMode, params.mode == .trail ? 1 : 0)
        setUniform1f(accumUThreshold, params.highlightThreshold)
        let tint = renderTrailTintRGB(params)
        setUniform3f(accumUTint, tint.r, tint.g, tint.b)
        setUniform1f(accumUMask, renderTrailMaskUniform(params))
        setUniform1f(accumUTol, renderTrailTolerance(params))
        drawQuad(accumAttrib)
        glActiveTexture(GLenum(GL_TEXTURE1))
        glBindTexture(GLenum(GL_TEXTURE_2D), 0)
        glActiveTexture(GLenum(GL_TEXTURE0))
        glBindTexture(GLenum(GL_TEXTURE_2D), 0)
    }

    private func drawComposite(base: GLuint, echo: GLuint, params: RenderEffectParameters, attrib: Attrib) {
        glUseProgram(compositeProgram)
        glActiveTexture(GLenum(GL_TEXTURE0))
        glBindTexture(GLenum(GL_TEXTURE_2D), base)
        glActiveTexture(GLenum(GL_TEXTURE1))
        glBindTexture(GLenum(GL_TEXTURE_2D), echo)
        setUniform1i(compositeUBase, 0)
        setUniform1i(compositeUEcho, 1)
        setUniform1f(compositeUMode, params.mode == .trail ? 1 : 0)
        setUniform1f(compositeUGain, params.trailGain)
        let tint = renderTrailTintRGB(params)
        setUniform3f(compositeUTint, tint.r, tint.g, tint.b)
        setUniform1f(compositeUTintMode, renderTrailTintModeUniform(params))
        drawQuad(attrib)
        glActiveTexture(GLenum(GL_TEXTURE1))
        glBindTexture(GLenum(GL_TEXTURE_2D), 0)
        glActiveTexture(GLenum(GL_TEXTURE0))
        glBindTexture(GLenum(GL_TEXTURE_2D), 0)
    }

    private func drawCopy(source: GLuint, attrib: Attrib) {
        glUseProgram(copyProgram)
        glActiveTexture(GLenum(GL_TEXTURE0))
        glBindTexture(GLenum(GL_TEXTURE_2D), source)
        setUniform1i(copyUFrame, 0)
        drawQuad(attrib)
        glBindTexture(GLenum(GL_TEXTURE_2D), 0)
    }

    private func drawArcMerge(kept: GLuint, dropped: GLuint, deadbandLuma: Float, attrib: Attrib) {
        glUseProgram(arcMergeProgram)
        glActiveTexture(GLenum(GL_TEXTURE0))
        glBindTexture(GLenum(GL_TEXTURE_2D), kept)
        glActiveTexture(GLenum(GL_TEXTURE1))
        glBindTexture(GLenum(GL_TEXTURE_2D), dropped)
        setUniform1i(arcUA, 0)
        setUniform1i(arcUB, 1)
        setUniform1f(arcUDeadband, deadbandLuma)
        drawQuad(attrib)
        glActiveTexture(GLenum(GL_TEXTURE1))
        glBindTexture(GLenum(GL_TEXTURE_2D), 0)
        glActiveTexture(GLenum(GL_TEXTURE0))
        glBindTexture(GLenum(GL_TEXTURE_2D), 0)
    }

    /// 全屏四边形。客户端顶点数组的指针只在 `withUnsafeBufferPointer` 闭包内有效，
    /// 因此 enable → pointer → draw → disable 必须全部落在同一对嵌套作用域内；
    /// `glVertexAttribPointer` 保存的是裸指针，draw 时才解引用，闭包外继续使用是未定义行为。
    private func drawQuad(_ attrib: Attrib) {
        positions.withUnsafeBufferPointer { positions in
            texCoords.withUnsafeBufferPointer { texCoords in
                if attrib.position >= 0, let positionPointer = positions.baseAddress {
                    glEnableVertexAttribArray(GLuint(attrib.position))
                    glVertexAttribPointer(
                        GLuint(attrib.position), 2, GLenum(GL_FLOAT),
                        GLboolean(GL_FALSE), 0, UnsafeRawPointer(positionPointer)
                    )
                }
                if attrib.texCoord >= 0, let texCoordPointer = texCoords.baseAddress {
                    glEnableVertexAttribArray(GLuint(attrib.texCoord))
                    glVertexAttribPointer(
                        GLuint(attrib.texCoord), 2, GLenum(GL_FLOAT),
                        GLboolean(GL_FALSE), 0, UnsafeRawPointer(texCoordPointer)
                    )
                }

                glDrawArrays(GLenum(GL_TRIANGLE_STRIP), 0, 4)

                if attrib.position >= 0 { glDisableVertexAttribArray(GLuint(attrib.position)) }
                if attrib.texCoord >= 0 { glDisableVertexAttribArray(GLuint(attrib.texCoord)) }
            }
        }
    }

    // MARK: 累积面

    private func ensureAccumulation(width: Int, height: Int) -> Bool {
        if accumWidth == width && accumHeight == height
            && accTexture[0] != 0 && accTexture[1] != 0
            && accFramebuffer[0] != 0 && accFramebuffer[1] != 0 {
            if pendingAccumulationClear {
                clearAccumulation()
                pendingAccumulationClear = false
            }
            return true
        }
        releaseAccumulation()
        pendingAccumulationClear = false
        accumWidth = width
        accumHeight = height
        glGenTextures(2, &accTexture)
        for i in 0..<2 {
            glBindTexture(GLenum(GL_TEXTURE_2D), accTexture[i])
            glTexParameteri(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_MIN_FILTER), GLint(GL_LINEAR))
            glTexParameteri(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_MAG_FILTER), GLint(GL_LINEAR))
            glTexParameteri(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_WRAP_S), GLint(GL_CLAMP_TO_EDGE))
            glTexParameteri(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_WRAP_T), GLint(GL_CLAMP_TO_EDGE))
            glTexImage2D(
                GLenum(GL_TEXTURE_2D), 0, GLint(GL_RGBA),
                GLsizei(width), GLsizei(height), 0,
                GLenum(GL_RGBA), GLenum(GL_UNSIGNED_BYTE), nil
            )
        }
        glBindTexture(GLenum(GL_TEXTURE_2D), 0)
        glGenFramebuffers(2, &accFramebuffer)
        for i in 0..<2 {
            glBindFramebuffer(GLenum(GL_FRAMEBUFFER), accFramebuffer[i])
            glFramebufferTexture2D(
                GLenum(GL_FRAMEBUFFER), GLenum(GL_COLOR_ATTACHMENT0),
                GLenum(GL_TEXTURE_2D), accTexture[i], 0
            )
            if glCheckFramebufferStatus(GLenum(GL_FRAMEBUFFER)) != GLenum(GL_FRAMEBUFFER_COMPLETE) {
                releaseAccumulation()
                return false
            }
        }
        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), 0)
        accIndex = 0
        clearAccumulation()
        hasHistory = false
        return true
    }

    private func clearAccumulation() {
        glDisable(GLenum(GL_SCISSOR_TEST))
        glColorMask(GLboolean(GL_TRUE), GLboolean(GL_TRUE), GLboolean(GL_TRUE), GLboolean(GL_TRUE))
        glClearColor(0, 0, 0, 1)
        for i in 0..<2 where accFramebuffer[i] != 0 {
            glBindFramebuffer(GLenum(GL_FRAMEBUFFER), accFramebuffer[i])
            glClear(GLbitfield(GL_COLOR_BUFFER_BIT))
        }
        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), 0)
    }

    private func releaseAccumulation() {
        if accTexture[0] != 0 || accTexture[1] != 0 { glDeleteTextures(2, &accTexture) }
        accTexture = [0, 0]
        if accFramebuffer[0] != 0 || accFramebuffer[1] != 0 { glDeleteFramebuffers(2, &accFramebuffer) }
        accFramebuffer = [0, 0]
        accumWidth = 0
        accumHeight = 0
        accIndex = 0
        hasHistory = false
    }

    // MARK: 程序与纹理

    private func buildPrograms() {
        accumProgram = linkProgram(vertex: EchoGLESShaders.fullscreenVertex, fragment: EchoGLESShaders.echoAccumFragment)
        compositeProgram = linkProgram(vertex: EchoGLESShaders.fullscreenVertex, fragment: EchoGLESShaders.echoCompositeFragment)
        copyProgram = linkProgram(vertex: EchoGLESShaders.fullscreenVertex, fragment: EchoGLESShaders.copyFragment)
        arcMergeProgram = linkProgram(vertex: EchoGLESShaders.fullscreenVertex, fragment: EchoGLESShaders.arcMergeFragment)

        accumAttrib = attribs(accumProgram)
        compositeAttrib = attribs(compositeProgram)
        copyAttrib = attribs(copyProgram)
        arcAttrib = attribs(arcMergeProgram)

        accumUCur = uniform(accumProgram, "uCur")
        accumUPrev = uniform(accumProgram, "uPrev")
        accumUDecay = uniform(accumProgram, "uDecay")
        accumUMode = uniform(accumProgram, "uMode")
        accumUThreshold = uniform(accumProgram, "uThreshold")
        accumUTint = uniform(accumProgram, "uTint")
        accumUMask = uniform(accumProgram, "uMask")
        accumUTol = uniform(accumProgram, "uTol")

        compositeUBase = uniform(compositeProgram, "uBase")
        compositeUEcho = uniform(compositeProgram, "uEcho")
        compositeUMode = uniform(compositeProgram, "uMode")
        compositeUGain = uniform(compositeProgram, "uGain")
        compositeUTint = uniform(compositeProgram, "uTint")
        compositeUTintMode = uniform(compositeProgram, "uTintMode")

        copyUFrame = uniform(copyProgram, "uFrame")
        arcUA = uniform(arcMergeProgram, "uA")
        arcUB = uniform(arcMergeProgram, "uB")
        arcUDeadband = uniform(arcMergeProgram, "uDeadbandLuma")
    }

    private func attribs(_ program: GLuint) -> Attrib {
        return Attrib(position: attribute(program, "aPosition"), texCoord: attribute(program, "aTexCoord"))
    }

    private func attribute(_ program: GLuint, _ name: String) -> GLint {
        return name.withCString { glGetAttribLocation(program, $0) }
    }

    private func uniform(_ program: GLuint, _ name: String) -> GLint {
        return name.withCString { glGetUniformLocation(program, $0) }
    }

    private func makeTexture(from pixelBuffer: CVPixelBuffer, width: Int, height: Int, cache: CVOpenGLESTextureCache) -> CVOpenGLESTexture? {
        var cvTexture: CVOpenGLESTexture?
        let status = CVOpenGLESTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, cache, pixelBuffer, nil,
            GLenum(GL_TEXTURE_2D), GLint(GL_RGBA),
            GLsizei(width), GLsizei(height),
            GLenum(GL_BGRA), GLenum(GL_UNSIGNED_BYTE), 0, &cvTexture
        )
        guard status == kCVReturnSuccess else { return nil }
        return cvTexture
    }

    private func linkProgram(vertex: String, fragment: String) -> GLuint {
        let vs = compile(type: GLenum(GL_VERTEX_SHADER), source: vertex)
        let fs = compile(type: GLenum(GL_FRAGMENT_SHADER), source: fragment)
        guard vs != 0, fs != 0 else {
            if vs != 0 { glDeleteShader(vs) }
            if fs != 0 { glDeleteShader(fs) }
            return 0
        }
        let program = glCreateProgram()
        glAttachShader(program, vs)
        glAttachShader(program, fs)
        glLinkProgram(program)
        glDeleteShader(vs)
        glDeleteShader(fs)
        var status: GLint = 0
        glGetProgramiv(program, GLenum(GL_LINK_STATUS), &status)
        if status == GL_FALSE {
            glDeleteProgram(program)
            return 0
        }
        return program
    }

    private func compile(type: GLenum, source: String) -> GLuint {
        let shader = glCreateShader(type)
        guard shader != 0 else { return 0 }
        source.withCString { cString in
            var pointer: UnsafePointer<GLchar>? = cString
            glShaderSource(shader, 1, &pointer, nil)
        }
        glCompileShader(shader)
        var status: GLint = 0
        glGetShaderiv(shader, GLenum(GL_COMPILE_STATUS), &status)
        if status == GL_FALSE {
            glDeleteShader(shader)
            return 0
        }
        return shader
    }

    // MARK: 上下文与 uniform 工具

    /// 把调用串行落回 ``renderQueue``。GL 对象、`EAGLContext.setCurrent` 都是线程局部的，
    /// `resetEchoHistory()` / `release()` 可能从主队列调用，必须在这里同步到渲染队列，避免与
    /// 正在渲染的 `process*` 并发触碰同一批 GL 资源。已在 renderQueue 上时直接执行，不自嵌套。
    private func runOnRenderQueue(_ body: () -> Void) {
        if DispatchQueue.getSpecific(key: renderQueueKey) != nil {
            body()
        } else {
            renderQueue.sync(execute: body)
        }
    }

    /// 惰性准备 GL 资源（纹理缓存 / 着色器程序 / 输出 FBO）。`release()` 后再次 `process*`
    /// 会在这里重建，而不是直接抛错。
    private func prepareResources() -> Bool {
        if ready { return true }
        guard let context = context else { ready = false; return false }
        if textureCache == nil {
            var cache: CVOpenGLESTextureCache?
            let status = CVOpenGLESTextureCacheCreate(kCFAllocatorDefault, nil, context, nil, &cache)
            textureCache = (status == kCVReturnSuccess) ? cache : nil
        }
        if accumProgram == 0 || compositeProgram == 0 || copyProgram == 0 || arcMergeProgram == 0 {
            buildPrograms()
        }
        if outputFramebuffer == 0 {
            glGenFramebuffers(1, &outputFramebuffer)
        }
        ready = (textureCache != nil) && (accumProgram != 0) && (compositeProgram != 0)
            && (copyProgram != 0) && (arcMergeProgram != 0) && (outputFramebuffer != 0)
        return ready
    }

    /// 进入渲染作用域：设置并恢复当前 `EAGLContext`。只要求上下文可建，资源是否就绪交给
    /// ``prepareResources``，这样 `release()` 之后能在同一个实例上惰性重建。
    private func withContext(_ body: () throws -> Void) throws {
        guard let context = context else {
            throw RenderBackendError.backendNotOperational(.openGLES)
        }
        let previous = EAGLContext.current()
        guard EAGLContext.setCurrent(context) else {
            throw RenderBackendError.backendNotOperational(.openGLES)
        }
        defer {
            if EAGLContext.current() === context { EAGLContext.setCurrent(previous) }
        }
        try body()
    }

    private func setUniform1i(_ location: GLint, _ value: GLint) {
        if location >= 0 { glUniform1i(location, value) }
    }

    private func setUniform1f(_ location: GLint, _ value: Float) {
        if location >= 0 { glUniform1f(location, value) }
    }

    private func setUniform3f(_ location: GLint, _ r: Float, _ g: Float, _ b: Float) {
        if location >= 0 { glUniform3f(location, r, g, b) }
    }
}
#endif
