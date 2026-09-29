package com.wotagei.cam.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

private const val TAG_FROST = "WotaGlFrost"

/** 连续这么多帧 GL 报错就整条链停用（预览不受影响，只是没有毛玻璃） */
private const val FROST_MAX_ERROR_STREAK = 8

/**
 * 模糊趟的取样单元。
 *
 * 本工程的分配是：0 = 相机帧（两路输出 pass 都用）、1 = 斑马纹瓦片、2 = 曲线 LUT
 * （见 `GlRenderEngine.drawPass`），所以离屏链走 3，不与它们抢同一个单元——
 * 虽然每次绘制前大家都会各自重绑、混用也不会错，但"错开"让状态泄漏一眼可见。
 * ES2 保证至少 8 个单元（`MAX_TEXTURE_IMAGE_UNITS ≥ 8`），3 号一定在。
 */
private const val FROST_SRC_UNIT = 3

/**
 * 毛玻璃离屏链（docs/plan/14 §二 · #84 步骤 1）：OES → 2D 拷贝 + 两趟可分离高斯。
 *
 * 只在 GL 线程上活（[GlRenderEngine.postGl] 那条），因为它要用当前上下文，而全工程只有这一条上下文
 * （`eglCreateContext` 的 share 实参是 `EGL_NO_CONTEXT`，没有共享上下文这条路）。
 *
 * 三趟的资源拓扑（两张纹理 + 两个 FBO 乒乓，结果恒在 [resultTex]）：
 * ```
 * ① OES ──(拷贝, 1/N, 上屏那套纹理坐标)──▶ resultTex @ resultFbo
 * ② resultTex ──(横向高斯)──▶ scratchTex @ scratchFbo
 * ③ scratchTex ──(纵向高斯)──▶ resultTex @ resultFbo      ← 上层采样的就是这一张
 * ```
 * ③ 写 resultTex 时读的是 scratchTex，不是"同一张纹理又读又写"，不构成 feedback loop。
 *
 * 纪律三条，都在代码里落成了判据：
 * - **尺寸没变不重建**（[frostRtNeedsRebuild]），A/B 翻转不碰资源 ⇒ 没有尺寸抖动也没有闪烁；
 * - **状态自恢复**：`glViewport` / `glScissor` / `glUseProgram` / 顶点属性数组的 enable 位，进出各存一次；
 *   链跑在编码 pass 之后、上屏 pass 之前，带脏状态出去就是伤到出货行为；
 * - **失败就退化成"没有模糊"**：program 编不出来、FBO 不完整、连续报错超阈值 ⇒ [broken] 置位并删干净，
 *   预览照常、录像是同一份像素。
 */
internal class FrostBlurChain(
    private val linkProgram: (vertexSrc: String, fragmentSrc: String) -> Int,
    private val drainGlError: (where: String) -> Int,
    private val positionBuffer: FloatBuffer
) {

    /** 当前可用的结果纹理；主线程只读它（不可用 = null，是正常状态不是错误） */
    @Volatile
    var target: FrostRenderTarget? = null
        private set

    /** 停用原因已记录在日志；true 之后除 [release] 与 [resetAfterBreak] 外不再做事 */
    @Volatile
    private var broken = false

    // --- program（2 枚：拷贝 + 模糊共用一枚横向/纵向）
    private var copyProgram = 0
    private var blurProgram = 0

    // --- 离屏资源（2 纹理 + 2 FBO）
    private var resultTex = 0
    private var scratchTex = 0
    private var resultFbo = 0
    private var scratchFbo = 0
    private var rtWidth = 0
    private var rtHeight = 0

    // --- uniform/attribute 位置缓存（与 :1044-1054 同一范式：建 program 时取一次）
    private var copyAPosition = -1
    private var copyATexCoord = -1
    private var copyUFrame = -1
    private var copyUTexMatrix = -1
    private var blurAPosition = -1
    private var blurATexCoord = -1
    private var blurUSrc = -1
    private var blurUOffset = -1
    private var blurUWeight = -1

    // --- 构造期一次性分配的件（每帧零分配）
    /** 恒等映射的纹理坐标：RT 是普通 2D 图，要的就是 (0,0) 左下 … (1,1) 右上 */
    private val quadTexCoords: FloatBuffer = nativeFloatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )
    private val offsetScratch = FloatArray(2)
    private val sizeScratch = IntArray(2)
    private val viewportBackup = IntArray(4)
    private val scissorBackup = IntArray(4)
    private val scissorState = IntArray(1)
    private val programBackup = IntArray(1)
    private var errorStreak = 0

    /**
     * 提前把 program 编好（开关从 OFF 打到 ON 时调一次）。
     *
     * 为什么不 lazily 塞在 [draw] 里等第一帧才编：着色器编译是毫秒级且跑在渲染线程上，
     * 落在 `drawFrame` 里就是明晃晃掉一帧；调用它的是显式翻转，那一帧本来就什么都没画。
     * 幂等：已编好就直接返回，重复翻转不会重编译。
     */
    fun prepare() {
        if (broken) return
        if (copyProgram != 0 && blurProgram != 0) return
        buildPrograms()
    }

    /**
     * 跑完整三趟，产出/更新 [target]。
     *
     * @param oesTextureId 相机帧那枚 `GL_TEXTURE_EXTERNAL_OES`
     * @param texMatrix `SurfaceTexture.getTransformMatrix` 的矩阵（与上屏 pass 同一份）
     * @param displayTexCoords 上屏 pass 那套已烘好转正/镜像的纹理坐标缓冲（RT 内容与屏幕因此同朝向）
     * @param contentWidthPx 等比画面宽（px），RT 尺寸由它算，**不**用窗口宽高也不用编码器宽高
     * @param contentHeightPx 等比画面高（px）
     */
    fun draw(
        oesTextureId: Int,
        texMatrix: FloatArray,
        displayTexCoords: FloatBuffer,
        contentWidthPx: Int,
        contentHeightPx: Int
    ) {
        if (broken || oesTextureId == 0) return
        prepare()
        if (copyProgram == 0 || blurProgram == 0) {
            failChain("program 仍未就绪")
            return
        }

        if (!frostRtSizeInto(sizeScratch, contentWidthPx, contentHeightPx)) {
            // 预览尺寸回报为 0 帧（还没布局 / 会话没建好）：这帧没有模糊可用，但资源留着别拆
            if (target != null) target = null
            return
        }
        val w = sizeScratch[0]
        val h = sizeScratch[1]
        if (frostRtNeedsRebuild(rtWidth, rtHeight, w, h) && !ensureRenderTarget(w, h)) {
            failChain("RT/FBO 建立失败 ${w}x$h")
            return
        }

        val scissorWasEnabled = saveState()
        var errors = 0

        GLES20.glViewport(0, 0, w, h)

        // ① OES → 2D（降采样到 1/N 就靠这张小视口，一次绘制同时完成"拷进 2D"和"缩"）
        GLES20.glUseProgram(copyProgram)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, resultFbo)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (copyUFrame >= 0) GLES20.glUniform1i(copyUFrame, 0)
        if (copyUTexMatrix >= 0) GLES20.glUniformMatrix4fv(copyUTexMatrix, 1, false, texMatrix, 0)
        drawQuad(copyAPosition, copyATexCoord, displayTexCoords)
        errors += drainGlError("frostCopyPass")

        // ②③ 可分离高斯两趟：横向进 scratch，纵向回 result（结果恒在 target 指的那张）
        // sampler 单元与权重表都在 buildPrograms 里传过一次（uniform 属于 program，跨帧保留），
        // 这里只剩每趟变化的 uOffset
        GLES20.glUseProgram(blurProgram)

        if (fillFrostBlurOffset(offsetScratch, true, w, h)) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scratchFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + FROST_SRC_UNIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resultTex)
            if (blurUOffset >= 0) GLES20.glUniform2f(blurUOffset, offsetScratch[0], offsetScratch[1])
            drawQuad(blurAPosition, blurATexCoord, quadTexCoords)
            errors += drainGlError("frostBlurHorizontal")
        }

        if (fillFrostBlurOffset(offsetScratch, false, w, h)) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, resultFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + FROST_SRC_UNIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scratchTex)
            if (blurUOffset >= 0) GLES20.glUniform2f(blurUOffset, offsetScratch[0], offsetScratch[1])
            drawQuad(blurAPosition, blurATexCoord, quadTexCoords)
            errors += drainGlError("frostBlurVertical")
        }

        restoreState(scissorWasEnabled)

        if (errors > 0) {
            // 报错帧的 RT 内容不可信（可能是半张图或上一帧残留），当场撤回快照：
            // 上层这帧退普通 scrim，比"糊错内容但看不出来"诚实
            errorStreak++
            target = null
            if (errorStreak >= FROST_MAX_ERROR_STREAK) failChain("连续 $errorStreak 帧 GL 报错")
            return
        }
        errorStreak = 0
        publishTarget(w, h)
    }

    /** 只在真正变化时换一枚新快照对象（每帧零分配 + 主线程读到的一定是同帧几何） */
    private fun publishTarget(w: Int, h: Int) {
        val cur = target
        if (cur != null && cur.textureId == resultTex && cur.widthPx == w && cur.heightPx == h) return
        target = FrostRenderTarget(resultTex, w, h)
    }

    private fun drawQuad(aPosition: Int, aTexCoord: Int, texCoords: FloatBuffer) {
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

    /**
     * 进出各一次：视口、剪裁框（含 `GL_SCISSOR_TEST` 开关本身）、当前 program、自己开过的属性数组。
     *
     * 顶点属性数组的 enable 位是**全局**的（ES2 没有 VAO），而各 program 的 `aPosition/aTexCoord`
     * 下标由驱动分配、不保证与主 program 相同；若链里开了下标 2 却留着不关，主 program 用下标 0/1
     * 绘制时下标 2 仍指向已失效的缓冲 —— 所以出去前把自己开过的两个关掉（主 pass 每次都自己 enable）。
     */
    private fun saveState(): Boolean {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_SCISSOR_TEST, scissorState, 0)
        if (scissorState[0] != 0) GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, scissorBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, programBackup, 0)
        return scissorState[0] != 0
    }

    private fun restoreState(scissorWasEnabled: Boolean) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(viewportBackup[0], viewportBackup[1], viewportBackup[2], viewportBackup[3])
        if (scissorWasEnabled) {
            GLES20.glScissor(scissorBackup[0], scissorBackup[1], scissorBackup[2], scissorBackup[3])
        } else {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
        if (copyAPosition >= 0) GLES20.glDisableVertexAttribArray(copyAPosition)
        if (copyATexCoord >= 0) GLES20.glDisableVertexAttribArray(copyATexCoord)
        if (blurAPosition >= 0) GLES20.glDisableVertexAttribArray(blurAPosition)
        if (blurATexCoord >= 0) GLES20.glDisableVertexAttribArray(blurATexCoord)
        // 把源纹理从取样单元上摘下来：下一帧第一趟要往 resultTex 里画，而某些驱动只查"这张纹理还挂在
        // 某个激活单元上"就当 feedback loop 报警（严格规格是"被当前 program 的 sampler 引用"才算）。
        // 这是免费的保险，省下一次真机上极难归因的花屏
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + FROST_SRC_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (programBackup[0] != 0) GLES20.glUseProgram(programBackup[0]) else GLES20.glUseProgram(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /**
     * 建/重建离屏资源：只在 [frostRtNeedsRebuild] 说尺寸变了之后走这一条。
     *
     * 内部格式 RGBA8 = ES2 保证可渲染的组合（`glCheckFramebufferStatus` 是唯一可信的验收，见末尾）。
     * 采样参数照 :532 那四参写 GL_LINEAR + CLAMP_TO_EDGE：
     * 模糊要的是相邻纹素插值，CLAMP 保证卡片贴画面边缘时不绕回另一侧取样。
     */
    private fun ensureRenderTarget(w: Int, h: Int): Boolean {
        deleteRenderTarget()
        val texIds = IntArray(2)
        GLES20.glGenTextures(2, texIds, 0)
        resultTex = texIds[0]
        scratchTex = texIds[1]
        for (id in texIds) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            // 只占位不填数据：内容全靠三趟画出来
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val fboIds = IntArray(2)
        GLES20.glGenFramebuffers(2, fboIds, 0)
        resultFbo = fboIds[0]
        scratchFbo = fboIds[1]
        var complete = true
        complete = complete and attach(resultFbo, resultTex)
        complete = complete and attach(scratchFbo, scratchTex)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (drainGlError("frostRenderTarget") > 0) complete = false
        if (!complete) {
            Log.w(TAG_FROST, "FBO 不完整，停用毛玻璃链（预览不受影响）")
            deleteRenderTarget()
            return false
        }
        rtWidth = w
        rtHeight = h
        Log.i(
            TAG_FROST,
            "离屏 RT ${w}x$h（降采样 1/$FROST_DOWNSCALE，屏幕空间半径 ±${frostScreenRadiusPx()}px）"
        )
        return true
    }

    private fun attach(fbo: Int, texture: Int): Boolean {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        return status == GLES20.GL_FRAMEBUFFER_COMPLETE
    }

    /**
     * 编两枚 program。模糊那枚先试 highp，失败退 mediump 再编一次（与 `buildProgram` 同款退避）；
     * 拷贝那枚本来就是 mediump，编不出来就没有退路，直接停用。
     */
    private fun buildPrograms() {
        copyProgram = linkProgram(Shaders.TEXTURE_VS, Shaders.FROST_COPY_FS)
        if (copyProgram == 0) return
        copyAPosition = GLES20.glGetAttribLocation(copyProgram, "aPosition")
        copyATexCoord = GLES20.glGetAttribLocation(copyProgram, "aTexCoord")
        copyUFrame = GLES20.glGetUniformLocation(copyProgram, "uFrame")
        copyUTexMatrix = GLES20.glGetUniformLocation(copyProgram, "uTexMatrix")

        blurProgram = linkProgram(Shaders.FROST_QUAD_VS, gaussianSource(highp = true))
        if (blurProgram == 0) {
            Log.w(TAG_FROST, "highp 模糊 program 链接失败，退回 mediump 再编一次")
            blurProgram = linkProgram(Shaders.FROST_QUAD_VS, gaussianSource(highp = false))
        }
        if (blurProgram == 0) return
        blurAPosition = GLES20.glGetAttribLocation(blurProgram, "aPosition")
        blurATexCoord = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
        blurUSrc = GLES20.glGetUniformLocation(blurProgram, "uSrc")
        blurUOffset = GLES20.glGetUniformLocation(blurProgram, "uOffset")
        blurUWeight = GLES20.glGetUniformLocation(blurProgram, "uWeight")
        // 表长不变的常量（sampler 单元 + 权重）在这里传一次就够：uniform 属于 program，跨帧保留
        GLES20.glUseProgram(blurProgram)
        if (blurUSrc >= 0) GLES20.glUniform1i(blurUSrc, FROST_SRC_UNIT)
        if (blurUWeight >= 0) {
            GLES20.glUniform1fv(blurUWeight, FROST_BLUR_WEIGHTS.size, FROST_BLUR_WEIGHTS, 0)
        }
        checkGlErrorNoise()
    }

    /** 抽头数只从权重表来，两处同源（表改了着色器跟着变，反之亦然） */
    private fun gaussianSource(highp: Boolean): String =
        Shaders.frostGaussianFragment(FROST_BLUR_WEIGHTS.size, highp)

    private fun checkGlErrorNoise() {
        drainGlError("frostBuildPrograms")
    }

    private fun failChain(reason: String) {
        Log.w(TAG_FROST, "毛玻璃链停用：$reason")
        broken = true
        target = null
        deleteRenderTarget()
        deletePrograms()
        rtWidth = 0
        rtHeight = 0
    }

    /**
     * 停用后的重试入口：A/B 从 OFF 打到 ON 时给一次机会（显式人为动作，不是每帧重试，
     * 所以不会像 `blacklistedEffect` 那样把坏 program 每帧烧一遍编译）。
     */
    fun resetAfterBreak() {
        broken = false
        errorStreak = 0
    }

    private fun deleteRenderTarget() {
        if (resultTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(resultTex), 0)
            resultTex = 0
        }
        if (scratchTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(scratchTex), 0)
            scratchTex = 0
        }
        if (resultFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(resultFbo), 0)
            resultFbo = 0
        }
        if (scratchFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(scratchFbo), 0)
            scratchFbo = 0
        }
        rtWidth = 0
        rtHeight = 0
    }

    private fun deletePrograms() {
        if (copyProgram != 0) {
            GLES20.glDeleteProgram(copyProgram)
            copyProgram = 0
        }
        if (blurProgram != 0) {
            GLES20.glDeleteProgram(blurProgram)
            blurProgram = 0
        }
        copyAPosition = -1
        copyATexCoord = -1
        copyUFrame = -1
        copyUTexMatrix = -1
        blurAPosition = -1
        blurATexCoord = -1
        blurUSrc = -1
        blurUOffset = -1
        blurUWeight = -1
    }

    /** 由 `releaseGl()` 调用（必须已在 GL 线程且上下文 current） */
    fun release() {
        target = null
        deleteRenderTarget()
        deletePrograms()
        broken = false
        errorStreak = 0
    }
}
