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

/** 取样单元：0/1 = 取大合并的两路输入；与 `Shaders.ARC_MERGE_FS` 的注释同源。 */
private const val ARC_UNIT_A = 0
private const val ARC_UNIT_B = 1

/** EGL14 未暴露该常量，取 Khronos 原值 `EGL_OPENGL_ES3_BIT_KHR`（与 GlRenderEngine 同一写法）。 */
private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

/** 单条 GL 命令（建面/绘制）的等待上限；超时按失败处理，绝不无限等 */
private const val OP_WAIT_MS = 4_000L

/** 等解码器把一帧推上 SurfaceTexture 的上限 */
private const val FRAME_WAIT_MS = 2_000L

/**
 * 光弧修复的 **GPU 路线独立小引擎**（用户需求第 7 项，v2 抽帧+补弧口径）。
 *
 * 为什么不复用 [com.wotagei.cam.camera.GlRenderEngine]：那个引擎的 OES 纹理**死绑相机
 * `SurfaceTexture` 与预览 pass**；本引擎的输入是**解码器**、输出只有编码器一面。所以这里
 * 另起一套 EGL，但**逐段搬**了 GlRenderEngine 里已被真机验证的五段：`initGl` 的 EGLConfig
 * （WINDOW|PBUFFER）/ ES3→ES2 回退 / `eglCreateWindowSurface` 建编码器面 /
 * `EGLExt.eglPresentationTimeANDROID` 写 PTS / `eglSwapBuffers`。
 *
 * # v2 的纹理拓扑（替换 v1 的"prev 备份 + cross-fade"）
 * - `cur`：解码帧的 2D 拷贝（OES → 2D 拷贝趟，拓扑与 `camera/FrostBlurChain` 一致）；
 * - `pending` A/B 乒乓：**待发保留帧**——决策权在 [ArcRepairFlow]（runner 侧），本引擎只认
 *   指令原语：留（hold/merge）、并（fold/accumulate）、发（emit）；
 * - `acc` A/B 乒乓：**补弧累积面**——连续多枚被抽帧先在 acc 里逐枚取大，再一并并进前后帧。
 * - 全部合并走 [Shaders.ARC_MERGE_FS]（`max(a,b)`）；2D→2D 趟用恒等纹理矩阵，只有 OES 拷贝趟
 *   带 SurfaceTexture 的 `stMatrix`。
 * - 直通画笔两条：OES 版（PASS_THROUGH_FS）只采解码帧，2D 版（PASS_THROUGH_2D_FS）采
 *   cur/pend/acc——采样器类型必须与纹理目标一致，混用一条 program 在真机上恒黑
 *   （2026-10-05 全程黑屏缺陷，守卫 ArcRepairGlSamplerGuardTest 钉死）。
 *
 * # 线程
 * EGL/SurfaceTexture 全在一条 `HandlerThread("WotaArcRepairGl")` 上活（EGL 上下文绑定线程）；
 * 管线线程通过各指令方法投递并**阻塞等完成**。[awaitFrame] 等的是"解码器把帧推上来"的信号，
 * 通知方是**本 GL 线程**的 `onFrameAvailable`，等待方是管线线程——两个线程不同，不会自己等自己。
 */
internal class ArcRepairGl(
    private val widthPx: Int,
    private val heightPx: Int,
    private val basePtsNs: Long = 0L
) {

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
    private var curTex = 0
    private var curFbo = 0
    private val pendTex = IntArray(2)
    private val pendFbo = IntArray(2)
    private var pendIdx = 0
    private val accTex = IntArray(2)
    private val accFbo = IntArray(2)
    private var accIdx = 0

    /** OES 直通画笔（samplerExternalOES）：只许采解码帧那枚 EXTERNAL_OES 纹理（copyOesToCur） */
    private var passProgram = 0

    /**
     * 2D 直通画笔（sampler2D，[Shaders.PASS_THROUGH_2D_FS]）：cur→pending/acc 拷贝与 emit 上
     * 编码面采的都是普通 2D FBO 纹理，必须用它——拿 OES 画笔采 2D 是采样器类型错配（规范
     * 结果未定义；常见驱动读 EXTERNAL 槽位上的默认纹理，本机实测恒黑）。2026-10-05 真机
     * 「GPU 路全程黑屏」缺陷本体。
     */
    private var pass2dProgram = 0
    private var mergeProgram = 0

    private var passAPosition = -1
    private var passATexCoord = -1
    private var passUTexMatrix = -1
    private var passUFrame = -1
    private var pass2dAPosition = -1
    private var pass2dATexCoord = -1
    private var pass2dUTexMatrix = -1
    private var pass2dUFrame = -1
    private var mergeAPosition = -1
    private var mergeATexCoord = -1
    private var mergeUTexMatrix = -1
    private var mergeUA = -1
    private var mergeUB = -1

    private var texture: SurfaceTexture? = null

    // --- 构造期一次性分配（每帧零分配）
    private val stMatrix = FloatArray(16)
    private val identityMatrix = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f
    )
    private val positionBuffer = nativeFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoordBuffer = nativeFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    // --- 帧到达信号（GL 线程通知、管线线程等待）
    private val frameLock = Object()
    private var framesQueued = 0L
    private var framesConsumed = 0L

    fun start(): Boolean = postAndWait(OP_WAIT_MS) { initGl() }

    /** 把编码器输入面挂上 EGL（必须在 enc.start() 之后调，时序理由见 runner 侧注释） */
    fun attachEncoderSurface(surface: Surface): Boolean =
        postAndWait(OP_WAIT_MS) { createEncoderSurface(surface) }

    /** 等解码器把一帧推上 SurfaceTexture；超时 false */
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

    // region 指令原语（与 ArcRepairFlow 的 ArcOp 一一同构）

    /**
     * 消费当前解码帧：`updateTexImage` → OES 带纹理矩阵拷进 cur 2D 纹理。
     * 拷贝趟发生在原帧输出之后、下一帧到来之前，cur 永远是"这一帧"（v1 同一条时序论证）。
     */
    fun acquire(): Boolean = postAndWait(OP_WAIT_MS) {
        if (!glReady) return@postAndWait false
        val st = texture ?: return@postAndWait false
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        val ok = copyOesToCur()
        synchronized(frameLock) { framesConsumed++ }
        ok
    }

    /**
     * 处理被抽帧：[fresh] = true 时 acc 整帧换成本帧（清掉上一轮残留的复用内容），
     * false 时 acc = max(acc, cur) 继续累积。乒乓读写不同体。
     */
    fun accumulate(fresh: Boolean): Boolean = postAndWait(OP_WAIT_MS) {
        if (fresh) {
            copyIntoAcc(curTex, identityMatrix)
        } else {
            mergeOnGl(curTex, accTex[accIdx], accFbo[1 - accIdx])
                .also { if (it) accIdx = 1 - accIdx }
        }
    }

    /** pending = max(pending, acc)：累积补弧并进**前**保留帧 */
    fun foldAccIntoPending(): Boolean = postAndWait(OP_WAIT_MS) {
        mergeOnGl(pendTex[pendIdx], accTex[accIdx], pendFbo[1 - pendIdx])
            .also { if (it) pendIdx = 1 - pendIdx }
    }

    /** pending = cur：首枚保留帧滞留待发 */
    fun holdCurAsPending(): Boolean = postAndWait(OP_WAIT_MS) {
        copyToPending(curTex, identityMatrix)
    }

    /** pending = max(cur, acc)（[useAcc] = false 时即 pending = cur）：后保留帧带弧升级待发 */
    fun mergeCurAsPending(useAcc: Boolean): Boolean = postAndWait(OP_WAIT_MS) {
        if (!useAcc) {
            copyToPending(curTex, identityMatrix)
        } else {
            mergeOnGl(curTex, accTex[accIdx], pendFbo[1 - pendIdx])
                .also { if (it) pendIdx = 1 - pendIdx }
        }
    }

    /** 把待发帧画进编码器面（[ptsUs] = 均匀目标帧率步长，微秒） */
    fun emitPending(ptsUs: Long): Boolean = postAndWait(OP_WAIT_MS) {
        emitOnGl(ptsUs)
    }

    // endregion

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
            return false
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

        var ok = createRenderTarget().let { (t, f) -> curTex = t; curFbo = f; true } && curFbo != 0
        for (i in 0..1) {
            ok = ok && createRenderTarget().let { (t, f) -> pendTex[i] = t; pendFbo[i] = f; true } && pendFbo[i] != 0
        }
        for (i in 0..1) {
            ok = ok && createRenderTarget().let { (t, f) -> accTex[i] = t; accFbo[i] = f; true } && accFbo[i] != 0
        }
        if (!ok) {
            releaseGl()
            return false
        }
        if (!buildPrograms()) {
            releaseGl()
            return false
        }
        drainGlError("arcInit")
        glReady = true
        Log.i(TAG_ARC_GL, "就绪 ${widthPx}x$heightPx")
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

    /** 建一枚全分辨率 RGBA 纹理 + 绑定它的 FBO，返回 (tex, fbo)；cur / 乒乓 pending / 乒乓 acc 共用 */
    private fun createRenderTarget(): Pair<Int, Int> {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val tex = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
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
        val fbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG_ARC_GL, "离屏 FBO 不完整 status=0x${Integer.toHexString(status)}")
            return 0 to 0
        }
        return tex to fbo
    }

    private fun buildPrograms(): Boolean {
        passProgram = link(Shaders.TEXTURE_VS, Shaders.PASS_THROUGH_FS)
        if (passProgram == 0) return false
        passAPosition = GLES20.glGetAttribLocation(passProgram, "aPosition")
        passATexCoord = GLES20.glGetAttribLocation(passProgram, "aTexCoord")
        passUTexMatrix = GLES20.glGetUniformLocation(passProgram, "uTexMatrix")
        passUFrame = GLES20.glGetUniformLocation(passProgram, "uFrame")

        // 2D 直通画笔与 OES 直通画笔必须各一条：采样器类型不同不能共 program
        //（samplerExternalOES 采 2D 绑定 = 类型错配恒黑，见 Shaders.PASS_THROUGH_2D_FS 的 KDoc）
        pass2dProgram = link(Shaders.TEXTURE_VS, Shaders.PASS_THROUGH_2D_FS)
        if (pass2dProgram == 0) return false
        pass2dAPosition = GLES20.glGetAttribLocation(pass2dProgram, "aPosition")
        pass2dATexCoord = GLES20.glGetAttribLocation(pass2dProgram, "aTexCoord")
        pass2dUTexMatrix = GLES20.glGetUniformLocation(pass2dProgram, "uTexMatrix")
        pass2dUFrame = GLES20.glGetUniformLocation(pass2dProgram, "uFrame")

        mergeProgram = link(Shaders.TEXTURE_VS, Shaders.ARC_MERGE_FS)
        if (mergeProgram == 0) return false
        mergeAPosition = GLES20.glGetAttribLocation(mergeProgram, "aPosition")
        mergeATexCoord = GLES20.glGetAttribLocation(mergeProgram, "aTexCoord")
        mergeUTexMatrix = GLES20.glGetUniformLocation(mergeProgram, "uTexMatrix")
        mergeUA = GLES20.glGetUniformLocation(mergeProgram, "uA")
        mergeUB = GLES20.glGetUniformLocation(mergeProgram, "uB")
        drainGlError("arcBuildPrograms")
        return true
    }

    // endregion

    // region 绘制原语

    /** OES（带 SurfaceTexture 矩阵）→ cur 2D：解码帧落进可重复采样的普通纹理 */
    private fun copyOesToCur(): Boolean {
        if (passProgram == 0 || curFbo == 0) return false
        makeCurrent(pbufferSurface)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, curFbo)
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glUseProgram(passProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_UNIT_A)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (passUFrame >= 0) GLES20.glUniform1i(passUFrame, ARC_UNIT_A)
        if (passUTexMatrix >= 0) GLES20.glUniformMatrix4fv(passUTexMatrix, 1, false, stMatrix, 0)
        drawQuad(passAPosition, passATexCoord)
        unbindUnits()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return drainGlError("arcCopyCur") == 0
    }

    /**
     * 2D 纹理 → pending 写入面（恒等矩阵）：hold/merge 的"普通拷贝"支。
     * 只吃 2D 画笔——本引擎只有 copyOesToCur 采 OES 纹理，其余趟的源全是 2D FBO 纹理。
     */
    private fun copyToPending(src2D: Int, matrix: FloatArray): Boolean {
        if (pass2dProgram == 0) return false
        makeCurrent(pbufferSurface)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, pendFbo[1 - pendIdx])
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glUseProgram(pass2dProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_UNIT_A)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src2D)
        if (pass2dUFrame >= 0) GLES20.glUniform1i(pass2dUFrame, ARC_UNIT_A)
        if (pass2dUTexMatrix >= 0) GLES20.glUniformMatrix4fv(pass2dUTexMatrix, 1, false, matrix, 0)
        drawQuad(pass2dAPosition, pass2dATexCoord)
        unbindUnits()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        pendIdx = 1 - pendIdx
        return drainGlError("arcHoldPending") == 0
    }

    /** 2D 纹理 → acc 写入面（恒等矩阵，整帧替换）：fresh 累积支（2D 画笔，理由同 copyToPending） */
    private fun copyIntoAcc(src2D: Int, matrix: FloatArray): Boolean {
        if (pass2dProgram == 0) return false
        makeCurrent(pbufferSurface)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, accFbo[1 - accIdx])
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glUseProgram(pass2dProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_UNIT_A)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src2D)
        if (pass2dUFrame >= 0) GLES20.glUniform1i(pass2dUFrame, ARC_UNIT_A)
        if (pass2dUTexMatrix >= 0) GLES20.glUniformMatrix4fv(pass2dUTexMatrix, 1, false, matrix, 0)
        drawQuad(pass2dAPosition, pass2dATexCoord)
        unbindUnits()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        accIdx = 1 - accIdx
        return drainGlError("arcFreshAcc") == 0
    }

    /**
     * 取大合并：`写入面 = max(texA, texB)`。写入目标是乒乓的另一面，读写不同体；
     * 画完立即摘掉两个采样单元的绑定——某些驱动只查"纹理还挂在激活单元上"就当
     * feedback loop 报警（与 FrostBlurChain.restoreState 那条保险同一条理由）。
     */
    private fun mergeOnGl(texA: Int, texB: Int, dstFbo: Int): Boolean {
        if (mergeProgram == 0) return false
        makeCurrent(pbufferSurface)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glUseProgram(mergeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_UNIT_A)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_UNIT_B)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB)
        if (mergeUA >= 0) GLES20.glUniform1i(mergeUA, ARC_UNIT_A)
        if (mergeUB >= 0) GLES20.glUniform1i(mergeUB, ARC_UNIT_B)
        if (mergeUTexMatrix >= 0) GLES20.glUniformMatrix4fv(mergeUTexMatrix, 1, false, identityMatrix, 0)
        drawQuad(mergeAPosition, mergeATexCoord)
        unbindUnits()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return drainGlError("arcMerge") == 0
    }

    /**
     * 待发帧 → 编码器面：先 makeCurrent(编码器面) 再画（不切面 swap 会报 EGL_BAD_SURFACE，真机实测）。
     * 画笔必须用 2D 版（pending 是普通 2D FBO 纹理）：这趟是编码器看到的**最后一手**，
     * 用 OES 画笔时整条成片黑屏而 swap 照常成功——2026-10-05 真机缺陷的成片落点。
     */
    private fun emitOnGl(ptsUs: Long): Boolean {
        if (pass2dProgram == 0 || encoderEglSurface === EGL14.EGL_NO_SURFACE) return false
        if (!makeCurrent(encoderEglSurface)) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glUseProgram(pass2dProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_UNIT_A)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pendTex[pendIdx])
        if (pass2dUFrame >= 0) GLES20.glUniform1i(pass2dUFrame, ARC_UNIT_A)
        if (pass2dUTexMatrix >= 0) GLES20.glUniformMatrix4fv(pass2dUTexMatrix, 1, false, identityMatrix, 0)
        drawQuad(pass2dAPosition, pass2dATexCoord)
        unbindUnits()
        stampPresentation(ptsUs)
        val swapped = EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
        if (!swapped) Log.w(TAG_ARC_GL, "eglSwapBuffers 失败 err=0x${eglErrorHex()}")
        drainGlError("arcEmit")
        return swapped
    }

    /** 呈现时间戳：调用方给的均匀目标帧率 PTS（微秒→纳秒），叠上会话基址 */
    private fun stampPresentation(ptsUs: Long) {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY || encoderEglSurface === EGL14.EGL_NO_SURFACE) return
        val ptsNs = basePtsNs + ptsUs * 1000L
        runCatching { EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, ptsNs) }
            .onFailure { Log.w(TAG_ARC_GL, "写呈现时间戳失败：${it.message}") }
    }

    /** 摘掉本引擎用到的两个采样单元（防 feedback loop 误报） */
    private fun unbindUnits() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_UNIT_B)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ARC_UNIT_A)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
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
            Log.w(TAG_ARC_GL, "eglDestroySurface 失败：${eglErrorHex()}")
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
        if (pass2dProgram != 0) {
            GLES20.glDeleteProgram(pass2dProgram)
            pass2dProgram = 0
        }
        if (mergeProgram != 0) {
            GLES20.glDeleteProgram(mergeProgram)
            mergeProgram = 0
        }
        for (i in 0..1) {
            if (pendFbo[i] != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(pendFbo[i]), 0)
            if (pendTex[i] != 0) GLES20.glDeleteTextures(1, intArrayOf(pendTex[i]), 0)
            pendFbo[i] = 0
            pendTex[i] = 0
            if (accFbo[i] != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(accFbo[i]), 0)
            if (accTex[i] != 0) GLES20.glDeleteTextures(1, intArrayOf(accTex[i]), 0)
            accFbo[i] = 0
            accTex[i] = 0
        }
        if (curFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(curFbo), 0)
        if (curTex != 0) GLES20.glDeleteTextures(1, intArrayOf(curTex), 0)
        curFbo = 0
        curTex = 0
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
                Log.w(TAG_ARC_GL, "eglDestroyContext 失败：${eglErrorHex()}")
            }
            if (!EGL14.eglTerminate(eglDisplay)) {
                Log.w(TAG_ARC_GL, "eglTerminate 失败")
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
