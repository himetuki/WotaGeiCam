package com.wotagei.cam.player

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
import android.view.Surface
import com.wotagei.cam.camera.Shaders
import com.wotagei.cam.camera.nativeFloatBuffer
import com.wotagei.cam.core.GlowParams
import com.wotagei.cam.diag.Diag as Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「内置播放器内后渲染导出」的 GL 引擎（2026-10-28 用户需求）：
 * 解码帧 → **2D 拷出 → 双边降噪（可选）→ 亮部提取 + 可分离高斯（可选）→ 合成** → 编码器输入面。
 *
 * 与实时链的关系（[com.wotagei.cam.camera.GlRenderEngine]）：
 * - **只应用 scene layer 里用户点名的两项**：Glow 与画面降噪；**不做 RGB 曲线**（用户口径：
 *   曲线录制时已烘进成片，后渲染再套一次就是双倍）。也不做任何 monitor 层（斑马/峰值）。
 * - 合成公式与 `GLOW_DECL` 的 applyGlow 逐项同源（同色光晕 + 单色 tint）：
 *   `c + glow × tint × (gain + add) × blend × 噪声`。
 *
 * 为什么另起一套 EGL：与 [com.wotagei.cam.record.ArcRepairGl] 同源理由——实时引擎的 OES 纹理
 * 死绑相机 `SurfaceTexture` 与预览面，而这套的输入是**解码器**、输出只有编码器一面。
 * EGL 配置（含 `EGL_RECORDABLE_ANDROID`）、ES3→ES2 回退、编码器面绑定、`eglPresentationTimeANDROID`
 * 打戳与 `eglSwapBuffers` 全部**逐段照搬**那条已在真机验证的链（2026-10-05 全黑缺陷的坑别再踩）。
 *
 * 线程：EGL 上下文绑线程 ⇒ 全部 GL 调用跑在一条 `HandlerThread` 上；管线线程投递并阻塞等完成。
 * [awaitFrame] 等的是解码器把帧推上 `SurfaceTexture` 的信号（GL 线程 `onFrameAvailable` 通知）。
 */
internal class SceneExportGl(
    private val widthPx: Int,
    private val heightPx: Int,
    private val effects: SceneExportEffects
) {

    companion object {
        private const val TAG = "WotaSceneGl"
        private const val UNIT_A = 0
        private const val UNIT_B = 1
        private const val OP_WAIT_MS = 15_000L
        private const val FRAME_WAIT_MS = 3_000L

        /** 双边降噪的亮度差 σ（与实时链 GlRenderEngine 同一量级：0.12 偏保边，夜景暗部够用） */
        private const val BILATERAL_SIGMA_R = 0.12f
    }

    private val thread = HandlerThread("WotaSceneExportGl").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface = EGL14.EGL_NO_SURFACE

    private var oesTex = 0
    private var curTex = 0
    private var curFbo = 0
    private var denTex = 0
    private var denFbo = 0
    private var glowATex = 0
    private var glowAFbo = 0
    private var glowBTex = 0
    private var glowBFbo = 0

    private var texture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null

    // program + 位置缓存
    private var oesProgram = 0
    private var oesAPos = -1
    private var oesATex = -1
    private var oesUMatrix = -1
    private var oesUFrame = -1

    private var pass2dProgram = 0
    private var pass2dAPos = -1
    private var pass2dATex = -1
    private var pass2dUMatrix = -1
    private var pass2dUFrame = -1

    private var bilatProgram = 0
    private var bilatAPos = -1
    private var bilatATex = -1
    private var bilatUMatrix = -1
    private var bilatUFrame = -1
    private var bilatUTexel = -1
    private var bilatUSigma = -1

    private var brightProgram = 0
    private var brightAPos = -1
    private var brightATex = -1
    private var brightUMatrix = -1
    private var brightUSrc = -1
    private var brightUThreshold = -1
    private var brightUSoft = -1

    private var blurProgram = 0
    private var blurAPos = -1
    private var blurATex = -1
    private var blurUMatrix = -1
    private var blurUSrc = -1
    private var blurUOffset = -1

    private var compProgram = 0
    private var compAPos = -1
    private var compATex = -1
    private var compUMatrix = -1
    private var compUBase = -1
    private var compUGlow = -1
    private var compUTint = -1
    private var compUGain = -1
    private var compUAdd = -1
    private var compUBlend = -1
    private var compUFreq = -1

    private var glReady = false

    private val stMatrix = FloatArray(16)
    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f
    )
    private val positionBuffer = nativeFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoordBuffer = nativeFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    private val frameLock = Object()
    private var framesQueued = 0L
    private var framesConsumed = 0L

    /** 解码器要写的面（管线线程拿它去 `decoder.configure`） */
    fun decoderOutputSurface(): Surface? = decoderSurface

    fun start(): Boolean = onGl(false) { initGl() }

    fun attachEncoderSurface(surface: Surface): Boolean = onGl(false) { createEncoderSurface(surface) }

    /** 等解码器推来一帧（[acquire] 消费它） */
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

    /** 只消费这一帧（区间外的帧要解码出来才能继续，但不进编码器） */
    fun acquireOnly(): Boolean = onGl(false) { acquire() }

    /**
     * 消费一帧并渲染进编码器：[ptsUs] 相对导出起点的呈现时间（微秒）。
     * 打戳失败按"这一帧没发出去"处理（与 ArcRepairGl 同判据：坏戳不可自愈）。
     */
    fun acquireAndRender(ptsUs: Long): Boolean = onGl(false) {
        if (!acquire()) return@onGl false
        render(ptsUs)
    }

    fun release() {
        onGl(false) { releaseGl(); true }
        thread.quitSafely()
        runCatching { thread.join(1500) }
    }

    // ---------------------------------------------------------------- 初始化

    private fun initGl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false
        val config = chooseConfig(eglDisplay) ?: return false
        eglConfig = config
        eglContext = createContext(eglDisplay, config) ?: return false
        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (pbufferSurface === EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            teardownEgl()
            return false
        }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(widthPx, heightPx)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        texture = st
        decoderSurface = Surface(st)

        val cur = createTarget()
        curTex = cur.first
        curFbo = cur.second
        var ok = curFbo != 0
        if (effects.denoise) {
            val den = createTarget()
            denTex = den.first
            denFbo = den.second
            ok = ok && denFbo != 0
        }
        if (effects.glow != null) {
            val a = createTarget()
            glowATex = a.first
            glowAFbo = a.second
            val b = createTarget()
            glowBTex = b.first
            glowBFbo = b.second
            ok = ok && glowAFbo != 0 && glowBFbo != 0
        }
        if (!ok) {
            releaseGl()
            return false
        }
        if (!buildPrograms()) {
            releaseGl()
            return false
        }
        drainGlError("sceneInit")
        glReady = true
        Log.i(TAG, "就绪 ${widthPx}x$heightPx glow=${effects.glow != null} denoise=${effects.denoise}")
        return true
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val recordable = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            // EGL_RECORDABLE_ANDROID：绑 MediaCodec 输入面必须声明（NDK 里同值）
            0x3142, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (EGL14.eglChooseConfig(display, recordable, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            return configs[0]
        }
        Log.w(TAG, "带 recordable 的 eglChooseConfig 无配置，退回通用属性")
        val fallback = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        return if (EGL14.eglChooseConfig(display, fallback, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            configs[0]
        } else null
    }

    private fun createContext(display: EGLDisplay, config: EGLConfig): EGLContext? {
        val es3 = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        if (es3 !== EGL14.EGL_NO_CONTEXT) return es3
        val es2 = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        return if (es2 !== EGL14.EGL_NO_CONTEXT) es2 else null
    }

    private fun createEncoderSurface(surface: Surface): Boolean {
        val config = eglConfig ?: return false
        if (!surface.isValid) return false
        if (encoderSurface !== EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbufferSurface)
            EGL14.eglDestroySurface(eglDisplay, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
        val es = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (es === EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "编码器 EGLSurface 创建失败")
            return false
        }
        encoderSurface = es
        return true
    }

    private fun createTarget(): Pair<Int, Int> {
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
        return if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) tex to fbo else 0 to 0
    }

    private fun buildPrograms(): Boolean {
        oesProgram = link(Shaders.TEXTURE_VS, Shaders.PASS_THROUGH_FS)
        if (oesProgram == 0) return false
        oesAPos = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        oesATex = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
        oesUMatrix = GLES20.glGetUniformLocation(oesProgram, "uTexMatrix")
        oesUFrame = GLES20.glGetUniformLocation(oesProgram, "uFrame")

        pass2dProgram = link(Shaders.TEXTURE_VS, Shaders.PASS_THROUGH_2D_FS)
        if (pass2dProgram == 0) return false
        pass2dAPos = GLES20.glGetAttribLocation(pass2dProgram, "aPosition")
        pass2dATex = GLES20.glGetAttribLocation(pass2dProgram, "aTexCoord")
        pass2dUMatrix = GLES20.glGetUniformLocation(pass2dProgram, "uTexMatrix")
        pass2dUFrame = GLES20.glGetUniformLocation(pass2dProgram, "uFrame")

        if (effects.denoise) {
            bilatProgram = link(Shaders.TEXTURE_VS, Shaders.SCENE_BILATERAL_2D_FS)
            if (bilatProgram == 0) return false
            bilatAPos = GLES20.glGetAttribLocation(bilatProgram, "aPosition")
            bilatATex = GLES20.glGetAttribLocation(bilatProgram, "aTexCoord")
            bilatUMatrix = GLES20.glGetUniformLocation(bilatProgram, "uTexMatrix")
            bilatUFrame = GLES20.glGetUniformLocation(bilatProgram, "uFrame")
            bilatUTexel = GLES20.glGetUniformLocation(bilatProgram, "uTexel")
            bilatUSigma = GLES20.glGetUniformLocation(bilatProgram, "uSigmaR")
        }
        if (effects.glow != null) {
            brightProgram = link(Shaders.TEXTURE_VS, Shaders.SCENE_BRIGHT_FS)
            blurProgram = link(Shaders.TEXTURE_VS, Shaders.SCENE_BLUR_FS)
            compProgram = link(Shaders.TEXTURE_VS, Shaders.SCENE_COMPOSITE_FS)
            if (brightProgram == 0 || blurProgram == 0 || compProgram == 0) return false
            brightAPos = GLES20.glGetAttribLocation(brightProgram, "aPosition")
            brightATex = GLES20.glGetAttribLocation(brightProgram, "aTexCoord")
            brightUMatrix = GLES20.glGetUniformLocation(brightProgram, "uTexMatrix")
            brightUSrc = GLES20.glGetUniformLocation(brightProgram, "uSrc")
            brightUThreshold = GLES20.glGetUniformLocation(brightProgram, "uThreshold")
            brightUSoft = GLES20.glGetUniformLocation(brightProgram, "uSoft")
            blurAPos = GLES20.glGetAttribLocation(blurProgram, "aPosition")
            blurATex = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
            blurUMatrix = GLES20.glGetUniformLocation(blurProgram, "uTexMatrix")
            blurUSrc = GLES20.glGetUniformLocation(blurProgram, "uSrc")
            blurUOffset = GLES20.glGetUniformLocation(blurProgram, "uOffset")
            compAPos = GLES20.glGetAttribLocation(compProgram, "aPosition")
            compATex = GLES20.glGetAttribLocation(compProgram, "aTexCoord")
            compUMatrix = GLES20.glGetUniformLocation(compProgram, "uTexMatrix")
            compUBase = GLES20.glGetUniformLocation(compProgram, "uBase")
            compUGlow = GLES20.glGetUniformLocation(compProgram, "uGlowSrc")
            compUTint = GLES20.glGetUniformLocation(compProgram, "uTint")
            compUGain = GLES20.glGetUniformLocation(compProgram, "uGain")
            compUAdd = GLES20.glGetUniformLocation(compProgram, "uAdd")
            compUBlend = GLES20.glGetUniformLocation(compProgram, "uBlend")
            compUFreq = GLES20.glGetUniformLocation(compProgram, "uFreq")
        }
        drainGlError("sceneBuildPrograms")
        return true
    }

    // ---------------------------------------------------------------- 每帧

    /** updateTexImage + OES→cur（带 SurfaceTexture 矩阵） */
    private fun acquire(): Boolean {
        if (!glReady) return false
        val st = texture ?: return false
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        val ok = drawTo(curFbo, oesProgram, oesAPos, oesATex, oesUMatrix, identity) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_A)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            if (oesUFrame >= 0) GLES20.glUniform1i(oesUFrame, UNIT_A)
            if (oesUMatrix >= 0) GLES20.glUniformMatrix4fv(oesUMatrix, 1, false, stMatrix, 0)
        }
        synchronized(frameLock) { framesConsumed++ }
        return ok
    }

    private fun render(ptsUs: Long): Boolean {
        val glow = effects.glow
        // 1) 降噪：cur → den（scene 顺序：降噪在 Glow 之前）
        var base = curTex
        if (effects.denoise) {
            val ok = drawTo(denFbo, bilatProgram, bilatAPos, bilatATex, bilatUMatrix, identity) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_A)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curTex)
                if (bilatUFrame >= 0) GLES20.glUniform1i(bilatUFrame, UNIT_A)
                if (bilatUMatrix >= 0) GLES20.glUniformMatrix4fv(bilatUMatrix, 1, false, identity, 0)
                if (bilatUTexel >= 0) GLES20.glUniform2f(bilatUTexel, 1f / widthPx, 1f / heightPx)
                if (bilatUSigma >= 0) GLES20.glUniform1f(bilatUSigma, BILATERAL_SIGMA_R)
            }
            if (!ok) return false
            base = denTex
        }
        // 2) Glow：亮部提取 → 横/纵模糊；3) 合成到编码器面
        if (glow != null) {
            val brightOk = drawTo(glowAFbo, brightProgram, brightAPos, brightATex, brightUMatrix, identity) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_A)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, base)
                if (brightUSrc >= 0) GLES20.glUniform1i(brightUSrc, UNIT_A)
                if (brightUMatrix >= 0) GLES20.glUniformMatrix4fv(brightUMatrix, 1, false, identity, 0)
                if (brightUThreshold >= 0) GLES20.glUniform1f(brightUThreshold, glow.threshold)
                if (brightUSoft >= 0) GLES20.glUniform1f(brightUSoft, glow.soft)
            }
            if (!brightOk) return false
            val dx = glow.width * minOf(1f, heightPx.toFloat() / widthPx)
            val dy = glow.width * minOf(1f, widthPx.toFloat() / heightPx)
            val hOk = drawTo(glowBFbo, blurProgram, blurAPos, blurATex, blurUMatrix, identity) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_A)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowATex)
                if (blurUSrc >= 0) GLES20.glUniform1i(blurUSrc, UNIT_A)
                if (blurUMatrix >= 0) GLES20.glUniformMatrix4fv(blurUMatrix, 1, false, identity, 0)
                if (blurUOffset >= 0) GLES20.glUniform2f(blurUOffset, dx, 0f)
            }
            if (!hOk) return false
            val vOk = drawTo(glowAFbo, blurProgram, blurAPos, blurATex, blurUMatrix, identity) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_A)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowBTex)
                if (blurUSrc >= 0) GLES20.glUniform1i(blurUSrc, UNIT_A)
                if (blurUMatrix >= 0) GLES20.glUniformMatrix4fv(blurUMatrix, 1, false, identity, 0)
                if (blurUOffset >= 0) GLES20.glUniform2f(blurUOffset, 0f, dy)
            }
            if (!vOk) return false
        }

        if (!makeCurrent(encoderSurface)) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, widthPx, heightPx)
        if (glow != null) {
            GLES20.glUseProgram(compProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_A)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, base)
            if (compUBase >= 0) GLES20.glUniform1i(compUBase, UNIT_A)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_B)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowATex)
            if (compUGlow >= 0) GLES20.glUniform1i(compUGlow, UNIT_B)
            if (compUMatrix >= 0) GLES20.glUniformMatrix4fv(compUMatrix, 1, false, identity, 0)
            val tint = effects.glowTint
            if (compUTint >= 0) GLES20.glUniform3f(compUTint, tint[0], tint[1], tint[2])
            if (compUGain >= 0) GLES20.glUniform1f(compUGain, glow.gain)
            if (compUAdd >= 0) GLES20.glUniform1f(compUAdd, glow.add)
            if (compUBlend >= 0) GLES20.glUniform1f(compUBlend, glow.blend)
            if (compUFreq >= 0) GLES20.glUniform1f(compUFreq, glow.freq)
            drawQuad(compAPos, compATex)
        } else {
            GLES20.glUseProgram(pass2dProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_A)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, base)
            if (pass2dUFrame >= 0) GLES20.glUniform1i(pass2dUFrame, UNIT_A)
            if (pass2dUMatrix >= 0) GLES20.glUniformMatrix4fv(pass2dUMatrix, 1, false, identity, 0)
            drawQuad(pass2dAPos, pass2dATex)
        }
        unbindUnits()
        val stamped = runCatching {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderSurface, ptsUs * 1000L)
        }.isSuccess
        if (!stamped) {
            Log.w(TAG, "打戳失败 ⇒ 本帧判未发出（坏戳不可自愈）")
            return false
        }
        val swapped = EGL14.eglSwapBuffers(eglDisplay, encoderSurface)
        if (!swapped) Log.w(TAG, "eglSwapBuffers 失败")
        drainGlError("sceneRender")
        return swapped
    }

    /** 通用一趟：绑 FBO/视口/程序，调用方配 uniform，然后画四边形 */
    private inline fun drawTo(
        fbo: Int,
        program: Int,
        aPos: Int,
        aTex: Int,
        uMatrix: Int,
        matrix: FloatArray,
        bindUniforms: () -> Unit
    ): Boolean {
        if (!glReady || program == 0 || fbo == 0) return false
        makeCurrent(pbufferSurface)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glUseProgram(program)
        bindUniforms()
        drawQuad(aPos, aTex)
        unbindUnits()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return drainGlError("scenePass") == 0
    }

    // ---------------------------------------------------------------- 工具

    private fun unbindUnits() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_B)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_A)
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

    private fun makeCurrent(surface: EGLSurface): Boolean {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY || eglContext === EGL14.EGL_NO_CONTEXT) return false
        return EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
    }

    private fun releaseGl() {
        glReady = false
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) makeCurrent(pbufferSurface)
        for (p in intArrayOf(oesProgram, pass2dProgram, bilatProgram, brightProgram, blurProgram, compProgram)) {
            if (p != 0) GLES20.glDeleteProgram(p)
        }
        oesProgram = 0; pass2dProgram = 0; bilatProgram = 0
        brightProgram = 0; blurProgram = 0; compProgram = 0
        for (i in intArrayOf(curFbo, denFbo, glowAFbo, glowBFbo)) if (i != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(i), 0)
        for (t in intArrayOf(curTex, denTex, glowATex, glowBTex, oesTex)) if (t != 0) GLES20.glDeleteTextures(1, intArrayOf(t), 0)
        curTex = 0; curFbo = 0; denTex = 0; denFbo = 0
        glowATex = 0; glowAFbo = 0; glowBTex = 0; glowBFbo = 0; oesTex = 0
        val s = decoderSurface
        decoderSurface = null
        s?.release()
        val st = texture
        texture = null
        st?.setOnFrameAvailableListener(null)
        st?.release()
        if (encoderSurface !== EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbufferSurface)
            EGL14.eglDestroySurface(eglDisplay, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
        if (pbufferSurface !== EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        teardownEgl()
    }

    private fun teardownEgl() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
    }

    private fun link(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc) ?: return 0
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc) ?: run {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "program 链接失败：${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, src: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "shader 编译失败：${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun drainGlError(where: String): Int {
        var err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "$where glError=0x${Integer.toHexString(err)}")
            while (true) {
                val e = GLES20.glGetError()
                if (e == GLES20.GL_NO_ERROR) break
                err = e
            }
        }
        return err
    }

    /** 把一次调用投递到 GL 线程并阻塞等结果（EGL 上下文绑线程：任何 GL 调用都不许留在调用方线程） */
    private fun <T> onGl(default: T, timeoutMs: Long = OP_WAIT_MS, block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        var result = default
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                Log.w(TAG, "GL 线程异常：${t.message}", t)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return default
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result else {
            Log.w(TAG, "GL 操作超时 ${timeoutMs}ms")
            default
        }
    }
}
