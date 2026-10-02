package com.wotagei.cam.record

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.wotagei.cam.camera.Shaders
import com.wotagei.cam.camera.nativeFloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG_ARC_GL = "WotaArcRepairGl"

/** 取样单元：0 = 当前解码帧（OES），1 = 上一帧的 2D 备份；与 `Shaders.ARC_REPAIR_FS` 的注释同源。 */
private const val ARC_CUR_UNIT = 0
private const val ARC_PREV_UNIT = 1

/** EGL14 未暴露该常量，取 Khronos 原值 `EGL_OPENGL_ES3_BIT_KHR`（与 GlRenderEngine 同一写法）。 */
private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

/** 单条 GL 命令（建面/绘制）的等待上限；超时按失败处理，绝不无限等 */
private const val OP_WAIT_MS = 4_000L

/** 等解码器把一帧推上 SurfaceTexture 的上限 */
private const val FRAME_WAIT_MS = 2_000L

/**
 * 光弧修复的 **GPU 路线独立小引擎**（用户需求第 7 项）。
 *
 * 为什么不复用 [com.wotagei.cam.camera.GlRenderEngine]：那个引擎的 OES 纹理**死绑相机 `SurfaceTexture`
 * 与预览 pass**，输入源、上屏目标、效果链全是为相机构造的；本引擎的输入是**解码器**、输出只有
 * 编码器一面、且要按"原帧/混合帧"交替 swap。硬塞进去只会在相机预览与离线修复之间挖一条互相污染的暗道。
 * 所以这里另起一套 EGL，但**逐段搬**了 GlRenderEngine 里已被真机验证的五段：
 * `initGl` 的 EGLConfig（WINDOW|PBUFFER）/ ES3→ES2 回退 / `eglCreateWindowSurface` 建编码器面 /
 * `EGLExt.eglPresentationTimeANDROID` 写**均匀 PTS** / `eglSwapBuffers`。
 *
 * # 双帧获取方案：**单解码 Surface + 上一帧 2D 备份纹理**（另一条是 A/B 双 SurfaceTexture）
 * 选它的理由：A/B 双 SurfaceTexture 要做"这一帧落在 A 还是 B"的状态机，且两枚 `updateTexImage` 的
 * 消费时序一错就静默丢帧（表现为光弧在个别接缝处闪一下，极难归因）；单 Surface + 每帧把当前解码帧
 * 拷进一张普通 2D 纹理（FBO 拷贝趟，拓扑与 `camera/FrostBlurChain` 的 OES→2D 拷贝趟一致）
 * 时序是**确定**的：拷贝发生在原帧输出之后、下一帧到来之前，prev 纹理永远是"上一帧"。
 * 代价：每帧多一趟全分辨率离屏拷贝（1920×1080 一次 fill，GPU 上是皮秒级），换来彻底去掉丢帧状态机。
 *
 * # 输出节奏（2N−1 帧）
 * 第 k 帧（k≥1）到达时：先出 `Blend(f(k−1), f(k))`（权重来自 [ArcRepairPlan.blendWeight]），
 * 再出 `Original(f(k))`，然后把当前帧拷进 prev 纹理。k=0 只出 `Original`。
 * 于是输出 = 1 + 2(N−1) = 2N−1，与 [ArcRepairPlan] 的计划严格同构。
 *
 * # 线程
 * EGL/SurfaceTexture 全在一条 `HandlerThread("WotaArcRepairGl")` 上活（EGL 上下文绑定线程）；
 * 管线线程通过 [start]/[attachEncoderSurface]/[renderFrame]/[release] 投递并**阻塞等完成**。
 * [awaitFrame] 等的是"解码器把帧推上来"的信号，通知方是**本 GL 线程**的 `onFrameAvailable`，
 * 等待方是管线线程——两个线程不同，所以不会自己等自己。
 */
internal class ArcRepairGl(
    private val widthPx: Int,
    private val heightPx: Int,
    srcFps: Int,
    private val basePtsNs: Long = 0L
) {

    /** 输出帧率 = 源帧率 × 2（插帧后），用于 [EGLExt.eglPresentationTimeANDROID] 的均匀步长 */
    private val outputFps: Int = if (srcFps > 0) srcFps * 2 else 60

    private val thread = HandlerThread("WotaArcRepairGl").apply { start() }
    private val handler = Handler(thread.looper)

    /** 解码器输出面；[start] 成功后有效 */
    @Volatile
    var decoderSurface: Surface? = null
        private set

    @Volatile
    private var released = false
    private var glReady = false

    // --- EGL
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface = EGL14.EGL_NO_SURFACE

    // --- 纹理 / program
    private var oesTextureId = 0
    private var prevTex = 0
    private var prevFbo = 0
    private var passProgram = 0
    private var blendProgram = 0

    private var passAPosition = -1
    private var passATexCoord = -1
    private var passUTexMatrix = -1
    private var passUFrame = -1
    private var blendAPosition = -1
    private var blendATexCoord = -1
    private var blendUTexMatrix = -1
    private var blendUCur = -1
    private var blendUPrev = -1
    private var blendUPrevWeight = -1

    private var texture: SurfaceTexture? = null

    // --- 构造期一次性分配（每帧零分配）
    private val stMatrix = FloatArray(16)
    private val positionBuffer = nativeFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoordBuffer = nativeFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    // --- 帧到达信号（GL 线程通知、管线线程等待）
    private val frameLock = Object()
    private var framesQueued = 0
    private var framesConsumed = 0

    // region 对外（管线线程调用）

    /** 在 GL 线程建 EGL 与离屏资源；成功后 [decoderSurface] 可用 */
    fun start(): Boolean = postAndWait(OP_WAIT_MS) { initGl() }

    /** 把编码器的输入面挂成 EGL 窗口面（`eglCreateWindowSurface`） */
    fun attachEncoderSurface(surface: Surface): Boolean =
        postAndWait(OP_WAIT_MS) { createEncoderSurface(surface) }

    /**
     * 等解码器把一帧推上 SurfaceTexture（`onFrameAvailable` 由本 GL 线程触发）。
     * 超时返回 false —— 管线据此报错收尾，绝不无限等。
     */
    fun awaitFrame(timeoutMs: Long = FRAME_WAIT_MS): Boolean = synchronized(frameLock) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (framesQueued <= framesConsumed) {
            val remain = deadline - SystemClock.elapsedRealtime()
            if (remain <= 0L) return false
            try {
                frameLock.wait(remain)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        true
    }

    /**
     * 把当前解码帧的产物画进编码器面：先（[blendPtsUs] 非空时）混合帧、再原帧，最后把当前帧拷进 prev 纹理。
     *
     * **PTS 沿源时间轴**（10-01 真机修正）：原帧用它的解码 PTS、混合帧用前后两帧 PTS 的中点——
     * 于是输出时长严格等于源时长，且不依赖"预先数出源帧数"（那个统计在真机上被 extractor 的
     * 遍历行为坑过：45 个包的片子数出 124）。旧的"输出帧号 × 1/outFps"口径会把时长拉长。
     */
    fun renderFrame(origPtsUs: Long, blendPtsUs: Long?): Boolean =
        postAndWait(OP_WAIT_MS) { renderOnGl(origPtsUs, blendPtsUs) }

    /** 释放全部 GL/EGL 资源并退出 GL 线程；幂等 */
    fun release() {
        if (released) return
        postAndWait(OP_WAIT_MS) {
            releaseGl()
            true
        }
        released = true
        thread.quitSafely()
    }

    // endregion

    // region GL 线程内部

    private fun postAndWait(timeoutMs: Long, block: () -> Boolean): Boolean {
        if (released) return false
        val latch = CountDownLatch(1)
        val out = booleanArrayOf(false)
        handler.post {
            out[0] = try {
                block()
            } catch (e: Exception) {
                Log.e(TAG_ARC_GL, "GL 操作抛异常：${e.javaClass.simpleName} ${e.message}")
                false
            }
            latch.countDown()
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && out[0]
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun initGl(): Boolean {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG_ARC_GL, "eglGetDisplay 失败 err=0x${eglErrorHex()}")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.w(TAG_ARC_GL, "eglInitialize 失败 err=0x${eglErrorHex()}")
            return false
        }
        eglDisplay = display

        val config = chooseConfig(display)
        if (config == null) {
            eglTerminate()
            return false
        }
        eglConfig = config

        val context = createContext(display, config)
        if (context == null) {
            eglTerminate()
            return false
        }
        eglContext = context

        val pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (pbuffer === EGL14.EGL_NO_SURFACE) {
            Log.w(TAG_ARC_GL, "pbuffer 创建失败 err=0x${eglErrorHex()}")
            teardownEgl()
            return false
        }
        pbufferSurface = pbuffer
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)) {
            Log.w(TAG_ARC_GL, "eglMakeCurrent 失败 err=0x${eglErrorHex()}")
            teardownEgl()
            return false
        }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // SurfaceTexture 必须在本线程、makeCurrent 之后建（构造期会把纹理 attach 到当前上下文）
        val st = SurfaceTexture(oesTextureId)
        st.setDefaultBufferSize(widthPx, heightPx)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        texture = st
        decoderSurface = Surface(st)

        if (!createPrevTarget()) {
            releaseGl()
            return false
        }
        if (!buildPrograms()) {
            releaseGl()
            return false
        }
        drainGlError("arcInit")
        glReady = true
        Log.i(TAG_ARC_GL, "就绪 ${widthPx}x$heightPx outFps=$outputFps")
        return true
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            // Android 特有属性（`EGL_RECORDABLE_ANDROID`，NDK 里是 `EGL_RECORDABLE_ANDROID_KHR` 同值）：
            // 「EGL 输出要绑到编解码器输入面」时官方要求声明 recordable；缺它时部分驱动
            // `eglCreateWindowSurface` 直接返回 EGL_BAD_ALLOC（真机实测 0x3003）。
            // GlRenderEngine 没带也能跑，但那是绑 MediaRecorder 的面；转码这条路绑的是 MediaCodec
            // 自己的输入面，实测必须带。EGL14 没有这个常量（属 EGL_KHR_recordable 扩展），故写字面量。
            0x3142, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            // 带 recordable 选不出配置就退回不带的那组（老驱动可能不认扩展），宁可降级也别整块失败
            Log.w(TAG_ARC_GL, "带 recordable 的 eglChooseConfig 无配置，退回通用属性重试")
            val fallback = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(display, fallback, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                Log.w(TAG_ARC_GL, "eglChooseConfig 无可用配置 err=0x${eglErrorHex()}")
                return null
            }
        }
        return configs[0]
    }

    private fun createContext(display: EGLDisplay, config: EGLConfig): EGLContext? {
        val es3 = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        if (es3 !== EGL14.EGL_NO_CONTEXT) return es3
        Log.w(TAG_ARC_GL, "ES3 上下文创建失败 err=0x${eglErrorHex()}，退回 ES2")
        val es2 = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (es2 === EGL14.EGL_NO_CONTEXT) {
            Log.w(TAG_ARC_GL, "ES2 上下文创建失败 err=0x${eglErrorHex()}")
            return null
        }
        return es2
    }

    private fun createEncoderSurface(surface: Surface): Boolean {
        val config = eglConfig
        if (config == null) {
            Log.w(TAG_ARC_GL, "EGL 未就绪，无法绑定编码器面")
            return false
        }
        if (!surface.isValid) {
            Log.w(TAG_ARC_GL, "编码器 Surface 已失效")
            return false
        }
        if (encoderEglSurface !== EGL14.EGL_NO_SURFACE) {
            // 同一面重复挂：先解除再重建（本引擎一个会话只挂一次，这里是防呆）
            makeCurrent(pbufferSurface)
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
        val es = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (es === EGL14.EGL_NO_SURFACE) {
            Log.w(TAG_ARC_GL, "编码器 EGLSurface 创建失败 err=0x${eglErrorHex()}")
            return false
        }
        encoderEglSurface = es
        return true
    }

    private fun createPrevTarget(): Boolean {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        prevTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, widthPx, heightPx, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        prevFbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, prevTex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG_ARC_GL, "prev FBO 不完整 status=0x${Integer.toHexString(status)}")
            return false
        }
        return true
    }

    private fun buildPrograms(): Boolean {
        passProgram = link(Shaders.TEXTURE_VS, Shaders.PASS_THROUGH_FS)
        if (passProgram == 0) return false
        passAPosition = GLES20.glGetAttribLocation(passProgram, "aPosition")
        passATexCoord = GLES20.glGetAttribLocation(passProgram, "aTexCoord")
        passUTexMatrix = GLES20.glGetUniformLocation(passProgram, "uTexMatrix")
        passUFrame = GLES20.glGetUniformLocation(passProgram, "uFrame")

        blendProgram = link(Shaders.TEXTURE_VS, Shaders.ARC_REPAIR_FS)
        if (blendProgram == 0) return false
        blendAPosition = GLES20.glGetAttribLocation(blendProgram, "aPosition")
        blendATexCoord = GLES20.glGetAttribLocation(blendProgram, "aTexCoord")
        blendUTexMatrix = GLES20.glGetUniformLocation(blendProgram, "uTexMatrix")
        blendUCur = GLES20.glGetUniformLocation(blendProgram, "uCur")
        blendUPrev = GLES20.glGetUniformLocation(blendProgram, "uPrev")
        blendUPrevWeight = GLES20.glGetUniformLocation(blendProgram, "uPrevWeight")
        drainGlError("arcBuildPrograms")
        return true
    }

    private fun renderOnGl(origPtsUs: Long, blendPtsUs: Long?): Boolean {
        if (!glReady) return false
        val st = texture ?: return false
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        var ok = true
        if (blendPtsUs != null) {
            ok = drawToEncoder(blendProgram, true, blendPtsUs) && ok
        }
        ok = drawToEncoder(passProgram, false, origPtsUs) && ok

        // 摘掉 prev 纹理的绑定：拷贝趟的写入目标就是它，某些驱动只查"这张纹理还挂在某个激活单元上"
        // 就当 feedback loop 报警（与 FrostBlurChain.restoreState 那条保险同一条理由）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_PREV_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        ok = copyToPrev() && ok

        synchronized(frameLock) { framesConsumed++ }
        return ok
    }

    /** 一次上屏：切到编码器面 → 绑定 program → 画满屏四边形 → 写均匀 PTS → swap */
    private fun drawToEncoder(program: Int, blend: Boolean, ptsUs: Long): Boolean {
        if (program == 0 || encoderEglSurface === EGL14.EGL_NO_SURFACE) return false
        // ⚠ 必须先 makeCurrent(编码器面)：初始化后上下文一直挂在 pbuffer 上，不切面就画/换帧
        // 会 eglSwapBuffers 报 EGL_BAD_SURFACE(0x300d)（真机实测）。GlRenderEngine 的
        // drawEncoderPass 同样是先 makeCurrent(encoder) 再画
        if (!makeCurrent(encoderEglSurface)) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_CUR_UNIT)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (blend) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_PREV_UNIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex)
            if (blendUCur >= 0) GLES20.glUniform1i(blendUCur, ARC_CUR_UNIT)
            if (blendUPrev >= 0) GLES20.glUniform1i(blendUPrev, ARC_PREV_UNIT)
            if (blendUPrevWeight >= 0) GLES20.glUniform1f(blendUPrevWeight, ArcRepairPlan.blendWeight())
            if (blendUTexMatrix >= 0) GLES20.glUniformMatrix4fv(blendUTexMatrix, 1, false, stMatrix, 0)
            drawQuad(blendAPosition, blendATexCoord)
        } else {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_CUR_UNIT)
            if (passUFrame >= 0) GLES20.glUniform1i(passUFrame, ARC_CUR_UNIT)
            if (passUTexMatrix >= 0) GLES20.glUniformMatrix4fv(passUTexMatrix, 1, false, stMatrix, 0)
            drawQuad(passAPosition, passATexCoord)
        }
        stampPresentation(ptsUs)
        val swapped = EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
        if (!swapped) Log.w(TAG_ARC_GL, "eglSwapBuffers 失败 err=0x${eglErrorHex()}")
        drainGlError("arcDrawEncoder")
        return swapped
    }

    /** 呈现时间戳：直接用调用方给的**源时间轴 PTS**（微秒→纳秒），不再自算步长 */
    private fun stampPresentation(ptsUs: Long) {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY || encoderEglSurface === EGL14.EGL_NO_SURFACE) return
        val ptsNs = basePtsNs + ptsUs * 1000L
        runCatching { EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, ptsNs) }
            .onFailure { Log.w(TAG_ARC_GL, "写呈现时间戳失败：${it.message}") }
    }

    /** OES → prev 纹理（普通 2D）：不 swap、不写 PTS，只是把"上一帧"存下来给下一次混合用 */
    private fun copyToPrev(): Boolean {
        if (passProgram == 0 || prevFbo == 0) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo)
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glUseProgram(passProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_CUR_UNIT)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (passUFrame >= 0) GLES20.glUniform1i(passUFrame, ARC_CUR_UNIT)
        if (passUTexMatrix >= 0) GLES20.glUniformMatrix4fv(passUTexMatrix, 1, false, stMatrix, 0)
        drawQuad(passAPosition, passATexCoord)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return drainGlError("arcCopyPrev") == 0
    }

    private fun drawQuad(aPosition: Int, aTexCoord: Int) {
        if (aPosition >= 0) {
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        }
        if (aTexCoord >= 0) {
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun onFrameAvailable() {
        synchronized(frameLock) {
            framesQueued++
            frameLock.notifyAll()
        }
    }

    // endregion

    // region 释放

    private fun makeCurrent(surface: EGLSurface): Boolean {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY || eglContext === EGL14.EGL_NO_CONTEXT) return false
        return EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
    }

    private fun destroyEglSurface(surface: EGLSurface) {
        if (surface === EGL14.EGL_NO_SURFACE) return
        makeCurrent(pbufferSurface)
        if (!EGL14.eglDestroySurface(eglDisplay, surface)) {
            Log.w(TAG_ARC_GL, "eglDestroySurface 失败 err=0x${eglErrorHex()}")
        }
    }

    private fun releaseGl() {
        if (!glReady && eglDisplay === EGL14.EGL_NO_DISPLAY) {
            glReady = false
            return
        }
        glReady = false
        makeCurrent(pbufferSurface)

        if (passProgram != 0) {
            GLES20.glDeleteProgram(passProgram)
            passProgram = 0
        }
        if (blendProgram != 0) {
            GLES20.glDeleteProgram(blendProgram)
            blendProgram = 0
        }
        if (prevFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(prevFbo), 0)
            prevFbo = 0
        }
        if (prevTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(prevTex), 0)
            prevTex = 0
        }
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }

        val s = decoderSurface
        decoderSurface = null
        s?.release()
        val st = texture
        texture = null
        st?.setOnFrameAvailableListener(null)
        st?.release()

        destroyEglSurface(encoderEglSurface)
        encoderEglSurface = EGL14.EGL_NO_SURFACE
        destroyEglSurface(pbufferSurface)
        pbufferSurface = EGL14.EGL_NO_SURFACE
        teardownEgl()
        Log.i(TAG_ARC_GL, "GL 资源已释放")
    }

    private fun teardownEgl() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglContext !== EGL14.EGL_NO_CONTEXT && !EGL14.eglDestroyContext(eglDisplay, eglContext)) {
                Log.w(TAG_ARC_GL, "eglDestroyContext 失败 err=0x${eglErrorHex()}")
            }
            if (!EGL14.eglTerminate(eglDisplay)) {
                Log.w(TAG_ARC_GL, "eglTerminate 失败 err=0x${eglErrorHex()}")
            }
        }
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
    }

    /** 兜底 terminate（init 早期失败时 display 已建、context 未建） */
    private fun eglTerminate() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
    }

    // endregion

    // region shader 工具

    private fun link(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return 0
        }
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG_ARC_GL, "program 链接失败: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG_ARC_GL, "shader 编译失败: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun drainGlError(where: String): Int {
        var count = 0
        var error = GLES20.glGetError()
        while (error != GLES20.GL_NO_ERROR) {
            count++
            Log.w(TAG_ARC_GL, "$where: glError 0x${Integer.toHexString(error)}")
            error = GLES20.glGetError()
        }
        return count
    }

    private fun eglErrorHex(): String = Integer.toHexString(EGL14.eglGetError())

    // endregion
}
