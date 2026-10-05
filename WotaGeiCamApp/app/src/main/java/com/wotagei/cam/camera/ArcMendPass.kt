package com.wotagei.cam.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.wotagei.cam.camera.nativeFloatBuffer

private const val TAG_MEND = "ArcMendPass"

/** 采样单元：0/1 = 取大合并的两路输入（与 Shaders.ARC_MERGE_FS 同源） */
private const val MEND_UNIT_A = 0
private const val MEND_UNIT_B = 1

/**
 * 录制期"自动抽帧补弧"的 **GL 离屏通道**（跑在 [GlRenderEngine] 的 GL 线程、同一 EGL 上下文内，
 * 无自己的线程/面——它只是给引擎多备了几张画布和两支画笔）。
 *
 * 拓扑与离线版 `record/ArcRepairGl`（v2）同构：
 * - `cur`：本帧特效链的渲染目标（引擎把 OES 帧按编码口径画进来）；
 * - `pending` A/B 乒乓：滞留待发的保留帧；
 * - `acc` A/B 乒乓：被抽帧的取大累积面（fresh 语义 = 整帧替换，防复用残留污染）。
 * 决策在 `record.ArcRepairFlow`（onClassification 在线路），本类只忠实执行绘制指令。
 */
internal class ArcMendPass {

    private var width = 0
    private var height = 0

    private var curTex = 0
    private var curFbo = 0
    private val pendTex = IntArray(2)
    private val pendFbo = IntArray(2)
    private var pendIdx = 0
    private val accTex = IntArray(2)
    private val accFbo = IntArray(2)
    private var accIdx = 0

    private var blitProgram = 0
    private var mergeProgram = 0
    private var blitAPosition = -1
    private var blitATexCoord = -1
    private var blitUTexMatrix = -1
    private var blitUFrame = -1
    private var mergeAPosition = -1
    private var mergeATexCoord = -1
    private var mergeUTexMatrix = -1
    private var mergeUA = -1
    private var mergeUB = -1

    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f
    )
    private val positionBuffer = nativeFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoordBuffer = nativeFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    val ready: Boolean
        get() = blitProgram != 0 && mergeProgram != 0 && curFbo != 0

    /** 惰性建资源；尺寸变化即重建（编码面尺寸在会话里恒定，这里只是防呆） */
    fun ensure(w: Int, h: Int): Boolean {
        if (ready && width == w && height == h) return true
        release()
        width = w
        height = h
        createRenderTarget().let { (t, f) -> curTex = t; curFbo = f }
        for (i in 0..1) createRenderTarget().let { (t, f) -> pendTex[i] = t; pendFbo[i] = f }
        for (i in 0..1) createRenderTarget().let { (t, f) -> accTex[i] = t; accFbo[i] = f }
        if (!readyTargets()) {
            release()
            Log.w(TAG_MEND, "离屏目标建不全")
            return false
        }
        // blit 画笔必须是 sampler2D 版：copyInto/drawPending 采的 cur/pend/acc 全是普通 2D FBO
        // 纹理（OES 采样只发生在引擎 drawPass 里：相机帧→cur，不经这条画笔）。曾链 OES 直通
        // 画笔（samplerExternalOES）采 2D = 采样器类型错配 → 补弧帧恒黑，与离线 ArcRepairGl
        // 同一处缺陷（2026-10-05 一起修）；批 F 极性修复后该支路已无直通兜底，黑帧会直接进成片。
        blitProgram = link(Shaders.TEXTURE_VS, Shaders.PASS_THROUGH_2D_FS)
        mergeProgram = link(Shaders.TEXTURE_VS, Shaders.ARC_MERGE_FS)
        if (blitProgram == 0 || mergeProgram == 0) {
            release()
            return false
        }
        blitAPosition = GLES20.glGetAttribLocation(blitProgram, "aPosition")
        blitATexCoord = GLES20.glGetAttribLocation(blitProgram, "aTexCoord")
        blitUTexMatrix = GLES20.glGetUniformLocation(blitProgram, "uTexMatrix")
        blitUFrame = GLES20.glGetUniformLocation(blitProgram, "uFrame")
        mergeAPosition = GLES20.glGetAttribLocation(mergeProgram, "aPosition")
        mergeATexCoord = GLES20.glGetAttribLocation(mergeProgram, "aTexCoord")
        mergeUTexMatrix = GLES20.glGetUniformLocation(mergeProgram, "uTexMatrix")
        mergeUA = GLES20.glGetUniformLocation(mergeProgram, "uA")
        mergeUB = GLES20.glGetUniformLocation(mergeProgram, "uB")
        drainGlError("mendEnsure")
        return true
    }

    fun release() {
        if (curFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(curFbo), 0)
        if (curTex != 0) GLES20.glDeleteTextures(1, intArrayOf(curTex), 0)
        curFbo = 0
        curTex = 0
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
        if (blitProgram != 0) GLES20.glDeleteProgram(blitProgram)
        if (mergeProgram != 0) GLES20.glDeleteProgram(mergeProgram)
        blitProgram = 0
        mergeProgram = 0
    }

    // region 指令执行（调用方保证在引擎 GL 线程、上下文已就绪）

    /** 本帧特效链的渲染目标 FBO（引擎 bind 后自己 viewport + drawPass(encoderPass=true)） */
    fun curFbo(): Int = curFbo

    fun accumulate(fresh: Boolean): Boolean =
        if (fresh) copyInto(accFbo[1 - accIdx], curTex)
            .also { if (it) accIdx = 1 - accIdx }
        else mergeInto(curTex, accTex[accIdx], accFbo[1 - accIdx])
            .also { if (it) accIdx = 1 - accIdx }

    fun foldAccIntoPending(): Boolean =
        mergeInto(pendTex[pendIdx], accTex[accIdx], pendFbo[1 - pendIdx])
            .also { if (it) pendIdx = 1 - pendIdx }

    fun holdCurAsPending(): Boolean =
        copyInto(pendFbo[1 - pendIdx], curTex)
            .also { if (it) pendIdx = 1 - pendIdx }

    fun mergeCurAsPending(useAcc: Boolean): Boolean =
        if (useAcc) mergeInto(curTex, accTex[accIdx], pendFbo[1 - pendIdx])
            .also { if (it) pendIdx = 1 - pendIdx }
        else copyInto(pendFbo[1 - pendIdx], curTex)
            .also { if (it) pendIdx = 1 - pendIdx }

    /** 待发帧纹理（emit 路的读取源） */
    fun pendingTex(): Int = pendTex[pendIdx]

    /** 把待发帧画进**当前已绑定**的目标面（调用方负责 makeCurrent(编码面) + viewport + stamp + swap） */
    fun drawPending(): Boolean {
        if (blitProgram == 0) return false
        GLES20.glUseProgram(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + MEND_UNIT_A)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pendTex[pendIdx])
        if (blitUFrame >= 0) GLES20.glUniform1i(blitUFrame, MEND_UNIT_A)
        if (blitUTexMatrix >= 0) GLES20.glUniformMatrix4fv(blitUTexMatrix, 1, false, identity, 0)
        drawQuad(blitAPosition, blitATexCoord)
        unbindUnits()
        return drainGlError("mendBlit") == 0
    }

    // endregion

    // region 绘制内部

    private fun copyInto(dstFbo: Int, src2D: Int): Boolean {
        if (blitProgram == 0) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + MEND_UNIT_A)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src2D)
        if (blitUFrame >= 0) GLES20.glUniform1i(blitUFrame, MEND_UNIT_A)
        if (blitUTexMatrix >= 0) GLES20.glUniformMatrix4fv(blitUTexMatrix, 1, false, identity, 0)
        drawQuad(blitAPosition, blitATexCoord)
        unbindUnits()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return drainGlError("mendCopy") == 0
    }

    /** `写入面 = max(a, b)`；写读不同体（乒乓），画完摘掉采样单元防 feedback 误报 */
    private fun mergeInto(a: Int, b: Int, dstFbo: Int): Boolean {
        if (mergeProgram == 0) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(mergeProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + MEND_UNIT_A)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + MEND_UNIT_B)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b)
        if (mergeUA >= 0) GLES20.glUniform1i(mergeUA, MEND_UNIT_A)
        if (mergeUB >= 0) GLES20.glUniform1i(mergeUB, MEND_UNIT_B)
        if (mergeUTexMatrix >= 0) GLES20.glUniformMatrix4fv(mergeUTexMatrix, 1, false, identity, 0)
        drawQuad(mergeAPosition, mergeATexCoord)
        unbindUnits()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return drainGlError("mendMerge") == 0
    }

    private fun unbindUnits() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + MEND_UNIT_B)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + MEND_UNIT_A)
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
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
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
            Log.w(TAG_MEND, "离屏 FBO 不完整 status=0x${Integer.toHexString(status)}")
            return 0 to 0
        }
        return tex to fbo
    }

    private fun readyTargets(): Boolean =
        curTex != 0 && curFbo != 0 &&
            pendTex.all { it != 0 } && pendFbo.all { it != 0 } &&
            accTex.all { it != 0 } && accFbo.all { it != 0 }

    private fun link(vertexSrc: String, fragmentSrc: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) return 0
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.w(TAG_MEND, "shader 编译失败: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG_MEND, "program 链接失败: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun drainGlError(where: String): Int {
        var count = 0
        var error = GLES20.glGetError()
        while (error != GLES20.GL_NO_ERROR) {
            count++
            Log.w(TAG_MEND, "$where: glError 0x${Integer.toHexString(error)}")
            error = GLES20.glGetError()
        }
        return count
    }

    // endregion
}
