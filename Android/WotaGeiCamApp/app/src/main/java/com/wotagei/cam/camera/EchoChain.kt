package com.wotagei.cam.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.wotagei.cam.core.glowRawLongestEdge
import com.wotagei.cam.core.glowRtSizeInto
import com.wotagei.cam.diag.Diag as Log
import java.nio.FloatBuffer

internal const val TAG_ECHO = "WotaGlEcho"

/** 连续这么多帧 GL 报错就整条链停用（预览与成片其余部分照常，只是没有拖影） */
private const val ECHO_MAX_ERROR_STREAK = 8

/** 累积面上一枚（prev）的取样单元；主 pass 的 `uEchoSrc` 用 [ECHO_SRC_UNIT]（各自一枚，互不干扰） */
private const val ECHO_PREV_UNIT = 6

/**
 * 残影累积面的取样单元：**主 pass 的 `uEchoSrc` 用这一号**（[EchoChain] 产出、主 pass 消费）。
 *
 * 与既有分配错开：0 = 相机帧、1 = 斑马纹瓦片、2 = 曲线 LUT、3/4 = 霜链与霜画板、5 = Glow。
 * ES2 保证 `MAX_TEXTURE_IMAGE_UNITS ≥ 8`，7 号一定在（主 pass 同时最多挂 0/1/2/5/7 五枚，仍在上限内）。
 */
internal const val ECHO_SRC_UNIT = 7

/**
 * 残影离屏链（R2 方案 B：**单张反馈累积面**，docs/research/R2 §四.1）：
 * `acc_t = f(cur_t, k·acc_{t−1})`，展开 = 当前帧 + 按几何衰减的历史帧——与滑窗 N+1 帧
 * （B 站光弧倍增器的字面实现）观感接近，只要两张乒乓 RT（4K 档 ≈ 2×8MB@半分辨率，远低于
 * 环形缓冲 8×33MB 的方案 A）。
 *
 * 结构与纪律照抄 [GlowBlurChain]（本项目跑通的真机先例），但实例/program/纹理单元全部分开：
 * - **每帧只在 `drawFrame` 里跑一次**，且**只在新帧上累积**（`hasFrame=false` 的状态刷新重画
 *   由引擎在调用侧拦住——同一张 OES 帧画两次会把拖影多叠一层，R2 §五.1 护栏）；
 * - 写读不同体（乒乓）：本帧画进 `acc[1-idx]`、采样 `acc[idx]`，不构成 feedback loop；
 * - 状态自恢复（viewport/scissor/program/属性/FBO），FBO 还原那条比 Glow 同款更严
 *   （MEND 支路对"当前绑定面"有契约）；
 * - 失败语义 = 退化成"没有拖影"：连续 [ECHO_MAX_ERROR_STREAK] 帧报错 ⇒ [broken] 置位删干净，
 *   [resultTex] 归 0，主 pass 绑 1×1 黑纹理（**max(c,黑)=c 且 c+0·gain=c：两种模式下黑纹理
 *   都是恒等**，比 Glow 的"贡献为 0"还干净），预览与成片其余部分零影响。
 *
 * 分档复用 `glowScaleFor`/`glowRtSizeInto`（同为"每帧逐像素"的 pass，负载口径相同，不造第二套）。
 * 只在 GL 线程上活（`GlRenderEngine.postGl` 那条）。
 */
internal class EchoChain(
    private val linkProgram: (vertexSrc: String, fragmentSrc: String) -> Int,
    private val drainGlError: (where: String) -> Int,
    private val positionBuffer: FloatBuffer
) {

    /** 可采样的累积面；**0 = 未就绪**（首帧/刚开启/已停用）——正常状态，不是错误 */
    @Volatile
    var resultTex = 0
        private set

    /** 停用位；true 之后除 [release] 与 [resetAfterBreak] 外不再做事 */
    @Volatile
    private var broken = false

    internal fun isBrokenForDiagnostics(): Boolean = broken

    private var accumProgram = 0
    private val accTex = IntArray(2)
    private val accFbo = IntArray(2)
    private var accIdx = 0
    private var rtW = 0
    private var rtH = 0
    private var maxTextureEdge = 0

    // uniform/attrib 位置（建 program 时取一次）
    private var aPosition = -1
    private var aTexCoord = -1
    private var uFrame = -1
    private var uTexMatrix = -1
    private var uPrev = -1
    private var uDecay = -1
    private var uMode = -1
    private var uThreshold = -1
    private var uTint = -1
    private var uMask = -1
    private var uTol = -1

    // 构造期一次性分配（每帧零分配）
    private val sizeScratch = IntArray(2)
    private val viewportBackup = IntArray(4)
    private val scissorBackup = IntArray(4)
    private val scissorState = IntArray(1)
    private val programBackup = IntArray(1)
    private val fboBackup = IntArray(1)
    private val clearColorBackup = FloatArray(4)
    private val colorMaskBackup = BooleanArray(4)
    private var errorStreak = 0

    /** 开关从 OFF 打到 ON 时预编 program（毫秒级编译不落在帧路径上）；幂等 */
    fun prepare() {
        if (broken) return
        if (accumProgram != 0) return
        // 必须用 TEXTURE_VS_ECHO：它输出矩阵变换的 vUv（采 OES 相机帧）+ 恒等的 vUvEcho（采上一枚
        // 累积面）。prev 已在 RT 空间，只能按恒等坐标读；若同 vUv 再乘一次矩阵，反馈每帧给历史多叠
        // 一次旋转/镜像 ⇒ 拖影相对真画面转置/对称（2026-11-05 r44 真机缺陷）。
        accumProgram = linkProgram(Shaders.TEXTURE_VS_ECHO, Shaders.ECHO_ACCUM_FS)
        if (accumProgram == 0) return
        aPosition = GLES20.glGetAttribLocation(accumProgram, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(accumProgram, "aTexCoord")
        uFrame = GLES20.glGetUniformLocation(accumProgram, "uFrame")
        uTexMatrix = GLES20.glGetUniformLocation(accumProgram, "uTexMatrix")
        uPrev = GLES20.glGetUniformLocation(accumProgram, "uEchoPrev")
        uDecay = GLES20.glGetUniformLocation(accumProgram, "uEchoDecay")
        uMode = GLES20.glGetUniformLocation(accumProgram, "uEchoMode")
        uThreshold = GLES20.glGetUniformLocation(accumProgram, "uEchoThreshold")
        uTint = GLES20.glGetUniformLocation(accumProgram, "uEchoTint")
        uMask = GLES20.glGetUniformLocation(accumProgram, "uEchoMask")
        uTol = GLES20.glGetUniformLocation(accumProgram, "uEchoTol")
        drainGlError("echoBuildPrograms")
    }

    /**
     * 清空累积面（R2 §五.2/§五.6 护栏）：**方向矩阵变了（转屏/换前后摄）、模式变了、
     * 显式关闭再开启**都必须调——旧朝向/旧模式的残影留在面里，下一帧起会被当成本帧历史
     * 继续叠进成片（拖着上一颗 sensor 的残影跑）。倍数变化**不**调：k 只影响往后的衰减，
     * 已有拖影按新系数继续衰减出去，1~2 帧内自然过渡。
     */
    fun reset() {
        if (broken || accFbo[0] == 0) return
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fboBackup, 0)
        GLES20.glGetFloatv(GLES20.GL_COLOR_CLEAR_VALUE, clearColorBackup, 0)
        GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK, colorMaskBackup, 0)
        val scissorWasEnabled = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
        try {
            // clear 不受 viewport 限制，但会受 scissor 和写掩码限制，必须临时解除。
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glColorMask(true, true, true, true)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            for (i in 0..1) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, accFbo[i])
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
        } catch (failure: Throwable) {
            failChain("累积面清黑异常")
            throw failure
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBackup[0])
            GLES20.glClearColor(clearColorBackup[0], clearColorBackup[1], clearColorBackup[2], clearColorBackup[3])
            GLES20.glColorMask(colorMaskBackup[0], colorMaskBackup[1], colorMaskBackup[2], colorMaskBackup[3])
            if (scissorWasEnabled) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            else GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
        if (drainGlError("echoReset") > 0) failChain("累积面清黑失败")
    }

    /**
     * 累积一趟：`acc[1-idx] ← f(OES 帧, k·acc[idx])`，随后乒乓交换。
     *
     * @param decay `echoDecayOf(倍数)`（0..1）；@param trailMode false = 保持亮度（max）
     * @param trailMask 择/除门模式（`echoTrailMaskOf`：0=不筛 1=择 2=除）
     * @param trailTintR 补丁色 R（0..1）；@param trailTintG 补丁色 G；@param trailTintB 补丁色 B
     * @param trailTol 择/除色度容差（`echoTrailTolOf`，docs/plan/35 E6）
     * @return true = 本帧产出了可采样的累积面
     */
    fun draw(
        oesTextureId: Int,
        texMatrix: FloatArray,
        identityUv: FloatBuffer,
        frameWidthPx: Int,
        frameHeightPx: Int,
        scale: Int,
        decay: Float,
        trailMode: Boolean,
        threshold: Float,
        trailMask: Int,
        trailTintR: Float,
        trailTintG: Float,
        trailTintB: Float,
        trailTol: Float
    ): Boolean {
        if (broken || oesTextureId == 0) return false
        prepare()
        if (accumProgram == 0) {
            failChain("program 仍未就绪")
            return false
        }
        val maxEdge = maxTextureSize()
        if (!glowRtSizeInto(sizeScratch, frameWidthPx, frameHeightPx, scale, maxEdge)) {
            if (resultTex != 0) resultTex = 0
            return false
        }
        val w = sizeScratch[0]
        val h = sizeScratch[1]
        if (w != rtW || h != rtH) {
            if (!ensureRenderTarget(w, h)) {
                failChain("RT/FBO 建立失败 ${w}x$h")
                return false
            }
            if (glowRawLongestEdge(frameWidthPx, frameHeightPx, scale) > maxEdge) {
                Log.w(TAG_ECHO, "残影 RT 最长边被夹到 ${maxEdge}px（GL_MAX_TEXTURE_SIZE，按最长边等比）")
            }
        }

        val scissorWasEnabled = saveState()
        var errors = 0
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(accumProgram)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, accFbo[1 - accIdx])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ECHO_PREV_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, accTex[accIdx])
        if (uFrame >= 0) GLES20.glUniform1i(uFrame, 0)
        if (uPrev >= 0) GLES20.glUniform1i(uPrev, ECHO_PREV_UNIT)
        if (uTexMatrix >= 0) GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        // 形状参数每帧随参数走（只换 uniform，不重建 program/RT）
        if (uDecay >= 0) GLES20.glUniform1f(uDecay, decay)
        if (uMode >= 0) GLES20.glUniform1f(uMode, if (trailMode) 1f else 0f)
        if (uThreshold >= 0) GLES20.glUniform1f(uThreshold, threshold)
        // 拖尾颜色补丁·择/除（docs/plan/35）：mask/tol 只在 TRAIL 支路参与（KB 分支不执行，E7 双保险之一）
        if (uMask >= 0) GLES20.glUniform1f(uMask, trailMask.toFloat())
        if (uTint >= 0) GLES20.glUniform3f(uTint, trailTintR, trailTintG, trailTintB)
        if (uTol >= 0) GLES20.glUniform1f(uTol, trailTol)
        drawQuad(identityUv)
        errors += drainGlError("echoAccumPass")
        restoreState(scissorWasEnabled)

        if (errors > 0) {
            errorStreak++
            resultTex = 0
            if (errorStreak >= ECHO_MAX_ERROR_STREAK) failChain("连续 $errorStreak 帧 GL 报错")
            return false
        }
        errorStreak = 0
        accIdx = 1 - accIdx
        resultTex = accTex[accIdx]
        return true
    }

    private fun drawQuad(texCoords: FloatBuffer) {
        if (aPosition >= 0) {
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        }
        if (aTexCoord >= 0) {
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun maxTextureSize(): Int {
        if (maxTextureEdge > 0) return maxTextureEdge
        val out = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, out, 0)
        maxTextureEdge = if (out[0] > 0) out[0] else 2048
        return maxTextureEdge
    }

    private fun saveState(): Boolean {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_SCISSOR_TEST, scissorState, 0)
        if (scissorState[0] != 0) GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, scissorBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, programBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fboBackup, 0)
        return scissorState[0] != 0
    }

    private fun restoreState(scissorWasEnabled: Boolean) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBackup[0])
        GLES20.glViewport(viewportBackup[0], viewportBackup[1], viewportBackup[2], viewportBackup[3])
        if (scissorWasEnabled) {
            GLES20.glScissor(scissorBackup[0], scissorBackup[1], scissorBackup[2], scissorBackup[3])
        } else {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
        if (aPosition >= 0) GLES20.glDisableVertexAttribArray(aPosition)
        if (aTexCoord >= 0) GLES20.glDisableVertexAttribArray(aTexCoord)
        // 采样面从单元上摘下来：下一帧要往这两张里画，某些驱动见"还挂着"就报 feedback loop
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + ECHO_PREV_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (programBackup[0] != 0) GLES20.glUseProgram(programBackup[0]) else GLES20.glUseProgram(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** 建两张乒乓 RT；null 只分配存储，必须在首次采样前显式清黑（换尺寸也同路清历史）。 */
    private fun ensureRenderTarget(w: Int, h: Int): Boolean {
        deleteRenderTarget()
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fboBackup, 0)
        val previousFramebuffer = fboBackup[0]
        var complete = true
        try {
            GLES20.glGenTextures(2, accTex, 0)
            for (id in accTex) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                )
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glGenFramebuffers(2, accFbo, 0)
            for (i in 0..1) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, accFbo[i])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, accTex[i], 0
                )
                complete = complete &&
                    (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            }
        } catch (failure: Throwable) {
            deleteRenderTarget()
            throw failure
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer)
        }
        if (drainGlError("echoRenderTarget") > 0) complete = false
        if (!complete) {
            Log.w(TAG_ECHO, "FBO 不完整，停用残影链（预览与成片其余部分照常）")
            deleteRenderTarget()
            return false
        }
        // 早于建 RT 的 reset 无面可清；在两张 FBO 完整后补清，再允许 draw 采历史。
        reset()
        if (broken) return false
        rtW = w
        rtH = h
        accIdx = 0
        Log.i(TAG_ECHO, "残影乒乓 RT ${w}x$h（两张 2D 纹理，主 pass 采其中一张）")
        return true
    }

    private fun failChain(reason: String) {
        Log.w(TAG_ECHO, "残影链停用：$reason")
        broken = true
        resultTex = 0
        deleteRenderTarget()
        if (accumProgram != 0) {
            GLES20.glDeleteProgram(accumProgram)
            accumProgram = 0
        }
    }

    /** 停用后的重试入口：开关从 OFF 打到 ON 时给一次机会（显式人为动作，不是每帧重试） */
    fun resetAfterBreak() {
        broken = false
        errorStreak = 0
    }

    private fun deleteRenderTarget() {
        if (accTex[0] != 0 || accTex[1] != 0) GLES20.glDeleteTextures(2, accTex, 0)
        accTex[0] = 0
        accTex[1] = 0
        if (accFbo[0] != 0 || accFbo[1] != 0) GLES20.glDeleteFramebuffers(2, accFbo, 0)
        accFbo[0] = 0
        accFbo[1] = 0
        rtW = 0
        rtH = 0
        resultTex = 0
    }

    /** 由 `releaseGl()` 调用（GL 线程且上下文 current）；只在这里删光资源 */
    fun release() {
        deleteRenderTarget()
        if (accumProgram != 0) {
            GLES20.glDeleteProgram(accumProgram)
            accumProgram = 0
        }
        broken = false
        errorStreak = 0
    }
}
