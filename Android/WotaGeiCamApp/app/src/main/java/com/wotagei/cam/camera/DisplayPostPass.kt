package com.wotagei.cam.camera

import android.graphics.Rect
import android.opengl.GLES20
import com.wotagei.cam.core.displayRtNeedsRebuild
import com.wotagei.cam.core.resolveDisplayRtInto
import com.wotagei.cam.diag.Diag as Log
import java.nio.FloatBuffer

private const val TAG_DISPLAY_POST = "WotaGlDisplay"

/** FBO 不完整/连续报错到这么多帧就停用上屏档（引擎当场回原片并让 UI 收回档位） */
private const val DISPLAY_POST_MAX_ERROR_STREAK = 4

/**
 * **上屏降档**的离屏 RT + 放大 blit（docs/plan/24 R3 §2.2，25 §11.5）。
 *
 * ```
 * ① 等比区 / divisor 的 RGBA8 RT：glBindFramebuffer(displayFbo)，viewport=(0,0,rtW,rtH)，drawPass(false)
 * ② 回默认面 → 清黑 → viewport=等比区 → 用 PASS_THROUGH_2D_FS 放大 blit → 霜板 → swap
 * ```
 *
 * # 为什么只动上屏这一路
 * 上屏 RT 是**窗口 pass 自己的**渲染目标：相机流（`setDefaultBufferSize`）、编码面、Mended 的
 * cur/pending、Glow 链、霜链的尺寸一个都不碰 ⇒ 成片逐像素与预览档无关（24 §〇.1 不变量 2）。
 *
 * # 三条纪律
 * - **`ORIG` 不占资源**：只在 `>ORIG` 时 [begin]；切回原片由引擎调 [releaseTarget]（只删两枚空纹理 + FBO，
 *   blit program 留在会话缓存里——编译贵、重建便宜）。
 * - **第 ② 步的 `glBindFramebuffer(GL_FRAMEBUFFER, 0)` 是载重步骤**：`drawPass` 只 draw 不绑面，
 *   霜板与 `swap` 都假定当前是默认面（与 `drawEncoderMended` 的"cur 渲染后立刻恢复 FB=0"同一条假设）。
 * - **失败就回原片**：[begin] 返回 false（program 编不出、尺寸不足 128、FBO 不完整、连续 GL 错误）
 *   ⇒ 引擎同一帧走原片路径，并让 UI 把档位改回原片（绝不"读数显示 4、实际按原片跑"）。
 *
 * blit 复用现有着色器（`FROST_QUAD_VS` + `PASS_THROUGH_2D_FS`），**不新增 shader 源码**——
 * 与 `ArcMendPass.copyInto` 是同一件事的两次实现。
 *
 * 只在 GL 线程上活；构造期分配全部缓冲（每帧零分配）。
 */
internal class DisplayPostPass(
    private val linkProgram: (vertexSrc: String, fragmentSrc: String) -> Int,
    private val positionBuffer: FloatBuffer
) {

    /** 停用位：program 编不出 / FBO 不完整 / 连续报错。true 之后 [begin] 恒 false，直到 [prepare] 复位 */
    private var broken = false

    private var program = 0
    private var aPosition = -1
    private var aTexCoord = -1
    private var uSrc = -1

    private var tex = 0
    private var fbo = 0
    private var rtWidth = 0
    private var rtHeight = 0

    /** blit 的恒等 4×2 UV（RT 与窗口面是同一套 0..1 坐标，不乘任何矩阵） */
    private val identityUv: FloatBuffer = nativeFloatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )
    private val sizeScratch = IntArray(2)
    private var errorStreak = 0

    // 进出备份（每帧只碰这几个标量，零分配）：blit 是"窗口尾巴"上的最后一段绘制，
    // 仍按霜链/Glow 链的同一条纪律把 program / 属性 enable / 激活单元还回去。
    private val programBackup = IntArray(1)
    private val activeTextureBackup = IntArray(1)
    private val attribState = IntArray(1)

    /** 幂等编 blit program（切到 `>ORIG` 时由引擎在 postGl 里调一次，不在帧路径上编译） */
    fun prepare() {
        // 显式切档 = 给停用过的 blit 一次重试机会（与霜/Glow 的 resetAfterBreak 同一条语义）
        broken = false
        errorStreak = 0
        if (program != 0) return
        val id = linkProgram(Shaders.FROST_QUAD_VS, Shaders.PASS_THROUGH_2D_FS)
        if (id == 0) {
            Log.w(TAG_DISPLAY_POST, "上屏 blit program 链接失败，上屏降档不可用（回原片）")
            broken = true
            return
        }
        program = id
        aPosition = GLES20.glGetAttribLocation(id, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
        uSrc = GLES20.glGetUniformLocation(id, "uFrame")
    }

    /**
     * 开始一帧的降档绘制：算 RT 尺寸 → 尺寸变才重建 → 绑 FBO 并把视口设成 RT 尺寸。
     *
     * @param contentWidthPx contentHeightPx 等比区尺寸（**不是**窗口尺寸、也不是编码尺寸）
     * @param divisor 档位除数（2/4/8/16）
     * @param maxTexture 运行时 `GL_MAX_TEXTURE_SIZE`（0 = 不检查）
     * @return false = 本档此刻不可用（调用方必须**同一帧回原片**，不许两路都画）；true 时当前 FBO/视口
     *   已经指向 RT，调用方直接 `drawPass(encoderPass = false)` 即可
     */
    fun begin(contentWidthPx: Int, contentHeightPx: Int, divisor: Int, maxTexture: Int): Boolean {
        if (broken) return false
        if (program == 0) prepare()
        if (broken || program == 0) return false
        if (!resolveDisplayRtInto(sizeScratch, contentWidthPx, contentHeightPx, divisor)) return false
        val w = sizeScratch[0]
        val h = sizeScratch[1]
        if (maxTexture > 0 && (w > maxTexture || h > maxTexture)) {
            // 极端窗口（分屏/副屏）下等比区/2 也可能超过纹理上限：当场回原片，不做逐维裁剪
            Log.w(TAG_DISPLAY_POST, "上屏 RT ${w}x$h 超 GL_MAX_TEXTURE_SIZE=$maxTexture，回原片")
            return false
        }
        if (displayRtNeedsRebuild(rtWidth, rtHeight, w, h) && !ensureTarget(w, h)) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        return true
    }

    /**
     * 把 RT 放大 blit 回**默认面**的等比区，并清掉黑边。
     *
     * 调用前提：这一帧已经用 [begin] 成功画完 RT（当前 FBO 指向 RT）。
     * 结束时当前面 = 默认面（0）、视口 = 等比区、纹理已解绑 —— 正是霜板与 `swap` 期望的状态。
     */
    fun blitToWindow(windowWidthPx: Int, windowHeightPx: Int, contentRect: Rect) {
        if (broken || program == 0 || tex == 0) return
        // 进出各存各恢复（与霜链/Glow 链同一条纪律）：program / 激活单元 / 自己开过的两个属性下标。
        // ⚠ **FBO 不恢复**是刻意的：这一步的契约就是把当前面交回默认面（霜板与 swap 都在默认面上画）。
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, programBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, activeTextureBackup, 0)
        val posWasEnabled = attribEnabled(aPosition)
        val texWasEnabled = attribEnabled(aTexCoord)
        // 载重步骤：回默认面（drawPass 只 draw 不绑面，霜板与 swap 都假定当前是默认面）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, windowWidthPx, windowHeightPx)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val width = contentRect.width().coerceAtLeast(1)
        val height = contentRect.height().coerceAtLeast(1)
        // 窗口坐标 Y 向下 → GL 视口 Y 向上
        GLES20.glViewport(contentRect.left, windowHeightPx - contentRect.top - height, width, height)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (uSrc >= 0) GLES20.glUniform1i(uSrc, 0)
        if (aPosition >= 0) {
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        }
        if (aTexCoord >= 0) {
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, identityUv)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // 立刻解绑：下一帧要往同一张纹理里画，某些驱动只查"这张还挂在激活单元上"就当 feedback loop
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        // 恢复：属性 enable 位（ES2 没有 VAO，是全局状态）→ 激活单元 → program
        if (aPosition >= 0) {
            if (posWasEnabled) GLES20.glEnableVertexAttribArray(aPosition)
            else GLES20.glDisableVertexAttribArray(aPosition)
        }
        if (aTexCoord >= 0) {
            if (texWasEnabled) GLES20.glEnableVertexAttribArray(aTexCoord)
            else GLES20.glDisableVertexAttribArray(aTexCoord)
        }
        GLES20.glActiveTexture(activeTextureBackup[0])
        GLES20.glUseProgram(programBackup[0])
        // 报错记账（这是本档路径的最后一处 GL 调用）：连续几帧都坏 ⇒ 停用，让引擎回原片并收回档位。
        // 这枚 drain 会"吃掉"这些错误，引擎末尾的 checkGlError 因此看不到它们——我们在这里带自己的
        // tag 逐条登记（排障看 `WotaGlDisplay`），比让它们混进 drawFrame 的泛化行更有用。
        if (drainErrors() > 0) {
            errorStreak++
            if (errorStreak >= DISPLAY_POST_MAX_ERROR_STREAK) {
                Log.w(TAG_DISPLAY_POST, "上屏降档连续 $errorStreak 帧 GL 报错，停用并回原片")
                broken = true
                deleteTarget()
            }
        } else {
            errorStreak = 0
        }
    }

    /** 某个顶点属性当前是否 enabled（下标 <0 时按 false 处理：本类不持有那个属性） */
    private fun attribEnabled(index: Int): Boolean {
        if (index < 0) return false
        GLES20.glGetVertexAttribiv(index, GLES20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, attribState, 0)
        return attribState[0] != 0
    }

    /** 排空 GL 错误队列并返回条数（只在本类路径内调用，tag 独立） */
    private fun drainErrors(): Int {
        var count = 0
        var error = GLES20.glGetError()
        while (error != GLES20.GL_NO_ERROR) {
            count++
            Log.w(TAG_DISPLAY_POST, "上屏 blit glError 0x${Integer.toHexString(error)}")
            error = GLES20.glGetError()
        }
        return count
    }

    /**
     * 建/重建 RT：RGBA8（ES2 保证可渲染）、`GL_LINEAR` + `CLAMP_TO_EDGE`
     * （NEAREST 会把上屏档放大成马赛克；REPEAT 会在边缘绕回画面另一头）。
     */
    private fun ensureTarget(w: Int, h: Int): Boolean {
        deleteTarget()
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        tex = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
        )
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!complete) {
            Log.w(TAG_DISPLAY_POST, "上屏 RT FBO 不完整，回原片（预览其余部分照常）")
            deleteTarget()
            broken = true
            return false
        }
        rtWidth = w
        rtHeight = h
        Log.i(TAG_DISPLAY_POST, "上屏 RT ${w}x$h（等比区 ÷ divisor；放大 blit 回窗口）")
        return true
    }

    /** 只删纹理/FBO，保留 blit program（编译贵；`ORIG` 档的"不占资源"指的就是这两枚） */
    fun releaseTarget() {
        deleteTarget()
        errorStreak = 0
    }

    /** 由 `releaseGl()` 调用（必须已在 GL 线程且上下文 current） */
    fun release() {
        deleteTarget()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        aPosition = -1
        aTexCoord = -1
        uSrc = -1
        broken = false
        errorStreak = 0
    }

    private fun deleteTarget() {
        if (tex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            tex = 0
        }
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        rtWidth = 0
        rtHeight = 0
    }
}
