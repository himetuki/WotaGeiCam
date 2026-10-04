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
 * 上屏画板的取样单元（[#84 步骤 2][FrostPlatePass]）：与离屏链的 3 号再错开一格。
 * 霜结果纹理在画板 pass 里**只读**，但它同时是下一帧离屏链的写入目标，
 * 所以画板出去前必须把它从取样单元上摘下来（与 [FrostBlurChain.restoreState] 那条保险同一条理由）。
 */
private const val FROST_PLATE_UNIT = 4

/**
 * 毛玻璃离屏链（docs/plan/14 §二 · #84 步骤 1）：OES → 2D 拷贝 + 两趟可分离高斯。
 *
 * 本文件现在装着霜的两个 GL 侧角色：**[FrostBlurChain] 糊（离屏，产一枚结果纹理）**、
 * **[FrostPlatePass] 贴（上屏，按矩形表逐枚画底板）**。两者共用引擎的 `link()`/`drainGlError()`/
 * 全屏四边形缓冲，但 program、纹理单元、状态进出各自独立——分家是为了让"编码 pass 绝不碰霜"这条
 * 红线在结构上一眼可见（离屏链夹在两个 pass 之间跑，画板只挂在上屏 pass 里）。
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

    /** 霜探针（#84）：只读；true = 连续 GL 错误后已停用，等显式 resetAfterBreak */
    internal fun isBrokenForDiagnostics(): Boolean = broken

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
        // 幂等重建：上一轮 blur 链接失败会留下 copyProgram != 0 && blurProgram == 0 的半初始化态，
        // 此时 prepare 的「两个都非 0 才短路」拦不住再次进入这里——先删旧件再编，
        // 否则下面的无条件重链把旧 program 对象直接覆盖丢弃（GL program 泄漏，翻转一次漏一枚）
        deletePrograms()
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

/**
 * 毛玻璃**上屏画板**（#84 步骤 2 · A2 混合路线）：按 [FrostCardTable] 那张矩形表，逐枚把
 * 「模糊纹理 + 圆角裁切 + 底板色」画进窗口面，位置就在预览 blit 之后、`eglSwapBuffers` 之前。
 *
 * A2 的分工在这里落地成一句话：**本类只负责圆角以内的那块板**，1dp 描边、选中高亮、图标、文字
 * 仍旧由 Compose 叠在 TextureView 之上画。所以 `ui/` 与 `camera/` 之间只有一张矩形表这一个接口，
 * 没有第二处视觉共享（也不需要共享——那枚纹理根本出不了这条 EGL 上下文）。
 *
 * 圆角用片元里的 rounded-rect SDF 判掉（[Shaders.frostPlateFragment]），**不引 stencil、不加 pass、
 * 不引第三方**；每枚板一次 `glDrawArrays`，uniform 只改 3 个数，顶点缓冲恒是那枚构造期建好的
 * 全屏四边形 ⇒ 每帧零分配（scratch 数组全是构造期的）。
 *
 * 纪律与 [FrostBlurChain] 同族，一条都不少：
 * - **进出各存各恢复**：`glViewport` / `glScissor` / `GL_SCISSOR_TEST` / `glUseProgram` /
 *   `GL_BLEND`（开关按进来时的值复原；混合因子设的就是 GL 默认那一组，所以不必额外还原）/
 *   自己开过的顶点属性下标 / 取样单元上的纹理绑定；
 * - **绝不碰编码器**：本类只被 [GlRenderEngine.drawWindowPass] 调用，`drawEncoderPass` 里一个引用都不许出现
 *   （有源码级用例守着，见 `FrostEncoderGuardTest`）；
 * - **失败就退化成"没有板"**：program 编不出来 ⇒ [broken] 置位并删干净，此后返回 0 块板，
 *   上层因此报"UI 侧继续画旧的纯色 fill"，观感与接霜前逐字相同。
 *
 * @param linkProgram 与 [FrostBlurChain] 共用引擎那枚 `link()`（同一份退避与日志口径）
 * @param drainGlError 同上；报错计数在这里只用于记日志，画板是可逐帧丢弃的 pass，不做停用阈值
 * @param positionBuffer 构造期那枚全屏四边形（`-1..1`），画板靠 uniform 摆位，不改它一个字节
 */
internal class FrostPlatePass(
    private val linkProgram: (vertexSrc: String, fragmentSrc: String) -> Int,
    private val drainGlError: (where: String) -> Int,
    private val positionBuffer: FloatBuffer
) {

    @Volatile
    private var broken = false

    /** 霜探针（#84）：只读；true = program 链接失败已停用（prepare 的 Log.w 有现场） */
    internal fun isBrokenForDiagnostics(): Boolean = broken

    private var program = 0
    private var aPositionLoc = -1
    private var uRectLoc = -1
    private var uUvRectLoc = -1
    private var uHalfPxLoc = -1
    private var uRadiusPxLoc = -1
    private var uTintLoc = -1
    private var uAlphaLoc = -1
    private var uSrcLoc = -1

    // --- 构造期一次性分配的件（每帧零分配）
    private val viewportBackup = IntArray(4)
    private val scissorBackup = IntArray(4)
    private val scissorState = IntArray(1)
    private val programBackup = IntArray(1)
    private val blendState = IntArray(1)
    private val originScratch = FloatArray(2)
    private val rectScratch = FloatArray(6)
    private val uvScratch = FloatArray(4)

    /**
     * 画一帧的板。
     *
     * @param texId 霜结果纹理（[FrostSnapshot.textureId]），0 = 这帧没有可用的霜 ⇒ 一块都不画
     * @param texWidthPx texHeightPx 结果纹理尺寸（px），[frostUvInto] 的整纹素对齐分母
     * @param contentLeftPx 等比画面在**承载视图**里的矩形左缘（px）；[contentTopPx] / [contentRightPx] /
     *   [contentBottomPx] 同义四条边。与表里矩形同一个坐标系是这套换算成立的前提。
     * @param viewWidthPx viewHeightPx 承载视图尺寸（= 那条 EGL 窗口面尺寸，NDC 的分母）
     * @param table [FrostCardTable.tryReadInto] 读进调用方副本的那一份（表头 + 压实后的卡片块）
     * @param cardCount 那一份里压实后的卡片数（`<=0` 直接返回 0）
     * @return 真画出去的板数（0 = 一帧都没画；调用方据此向 [FrostCardTable.reportPlatesDrawn] 回报，
     *         UI 侧再决定底板的纯色 fill 让不让位）
     */
    fun draw(
        texId: Int,
        texWidthPx: Int,
        texHeightPx: Int,
        contentLeftPx: Float,
        contentTopPx: Float,
        contentRightPx: Float,
        contentBottomPx: Float,
        viewWidthPx: Int,
        viewHeightPx: Int,
        table: FloatArray,
        cardCount: Int
    ): Int {
        if (broken || texId == 0 || cardCount <= 0) return 0
        if (viewWidthPx <= 0 || viewHeightPx <= 0) return 0
        prepare()
        if (program == 0) {
            broken = true
            return 0
        }
        // 表头：根在窗口里的矩形 + 底板色。视图原点由这两个数与 GL 自己知道的视图尺寸现算
        // （推断量与前提见 frostViewOriginInto 的注释），前提不成立就一块都不画。
        // 偏移一律走 FrostCardTable.HEADER_*：卡片那侧一直是用 CARD_* 常量取的，表头这两套写法并存
        // 的时候，表头加一个字段就会让下面这七行整体错一位（读到"底色"其实是别人），且不报错。
        if (!frostViewOriginInto(
                originScratch,
                rootLeftPx = table[FrostCardTable.HEADER_ROOT_LEFT],
                rootTopPx = table[FrostCardTable.HEADER_ROOT_TOP],
                rootWidthPx = table[FrostCardTable.HEADER_ROOT_WIDTH],
                rootHeightPx = table[FrostCardTable.HEADER_ROOT_HEIGHT],
                viewWidthPx = viewWidthPx,
                viewHeightPx = viewHeightPx
            )
        ) {
            return 0
        }
        val tintRed = table[FrostCardTable.HEADER_TINT_RED]
        val tintGreen = table[FrostCardTable.HEADER_TINT_GREEN]
        val tintBlue = table[FrostCardTable.HEADER_TINT_BLUE]

        val scissorWasEnabled = saveState()
        var drew = 0
        GLES20.glViewport(0, 0, viewWidthPx, viewHeightPx)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + FROST_PLATE_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        if (uSrcLoc >= 0) GLES20.glUniform1i(uSrcLoc, FROST_PLATE_UNIT)
        if (uTintLoc >= 0) GLES20.glUniform3f(uTintLoc, tintRed, tintGreen, tintBlue)
        bindPositionAttribute()

        for (i in 0 until cardCount) {
            val base = FrostCardTable.HEADER_FLOATS + i * FrostCardTable.SLOT_FLOATS
            if (base + FrostCardTable.SLOT_FLOATS > table.size) break
            val left = table[base + FrostCardTable.CARD_LEFT]
            val top = table[base + FrostCardTable.CARD_TOP]
            val right = table[base + FrostCardTable.CARD_RIGHT]
            val bottom = table[base + FrostCardTable.CARD_BOTTOM]
            // 先出 NDC 与像素半轴（退化矩形返回 false ⇒ 这块板这帧不画，与表里的在场位同一条口径）
            if (!frostPlateRectInto(
                    rectScratch, left, top, right, bottom,
                    originScratch[0], originScratch[1], viewWidthPx, viewHeightPx
                )
            ) continue
            val widthPx = rectScratch[4] * 2f
            val heightPx = rectScratch[5] * 2f
            val radiusPx = frostResolveRadiusPx(widthPx, heightPx, table[base + FrostCardTable.CARD_RADIUS])
            // 采样窗口：与等比画面求交，落在信箱黑边上的那截不参与（frostUvInto 里已裁）
            if (!frostUvInto(
                    uvScratch,
                    cardLeftPx = left, cardTopPx = top, cardRightPx = right, cardBottomPx = bottom,
                    contentLeftPx = contentLeftPx, contentTopPx = contentTopPx,
                    contentRightPx = contentRightPx, contentBottomPx = contentBottomPx,
                    viewOriginXInWindowPx = originScratch[0], viewOriginYInWindowPx = originScratch[1],
                    texWidthPx = texWidthPx, texHeightPx = texHeightPx
                )
            ) continue
            if (uRectLoc >= 0) {
                GLES20.glUniform4f(uRectLoc, rectScratch[0], rectScratch[1], rectScratch[2], rectScratch[3])
            }
            if (uUvRectLoc >= 0) {
                GLES20.glUniform4f(uUvRectLoc, uvScratch[0], uvScratch[1], uvScratch[2], uvScratch[3])
            }
            if (uHalfPxLoc >= 0) GLES20.glUniform2f(uHalfPxLoc, rectScratch[4], rectScratch[5])
            if (uRadiusPxLoc >= 0) GLES20.glUniform1f(uRadiusPxLoc, radiusPx)
            if (uAlphaLoc >= 0) GLES20.glUniform1f(uAlphaLoc, table[base + FrostCardTable.CARD_ALPHA])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            drew++
        }
        val errors = drainGlError("frostPlatePass")
        restoreState(scissorWasEnabled)
        if (errors > 0) return 0
        return drew
    }

    /**
     * program 只编一次（先 highp、失败退 mediump，与 [FrostBlurChain.buildPrograms] 同款退避）。
     * 幂等：编好就直接返回，所以每帧调用零成本；开关翻转时引擎会显式叫它一次，
     * 免得编译那几毫秒落在第一帧画板上（与 [FrostBlurChain.prepare] 同一条理由）。
     */
    fun prepare() {
        if (program != 0) return
        var id = linkProgram(Shaders.FROST_PLATE_VS, Shaders.frostPlateFragment(highp = true))
        if (id == 0) {
            Log.w(TAG_FROST, "highp 画板 program 链接失败，退回 mediump 再编一次")
            id = linkProgram(Shaders.FROST_PLATE_VS, Shaders.frostPlateFragment(highp = false))
        }
        if (id == 0) {
            Log.w(TAG_FROST, "画板 program 链接失败，停用画板（预览照常，只是底板仍是旧材质）")
            broken = true
            return
        }
        program = id
        aPositionLoc = GLES20.glGetAttribLocation(id, "aPosition")
        uRectLoc = GLES20.glGetUniformLocation(id, "uRect")
        uUvRectLoc = GLES20.glGetUniformLocation(id, "uUvRect")
        uHalfPxLoc = GLES20.glGetUniformLocation(id, "uHalfPx")
        uRadiusPxLoc = GLES20.glGetUniformLocation(id, "uRadiusPx")
        uTintLoc = GLES20.glGetUniformLocation(id, "uTint")
        uAlphaLoc = GLES20.glGetUniformLocation(id, "uAlpha")
        uSrcLoc = GLES20.glGetUniformLocation(id, "uSrc")
        GLES20.glUseProgram(id)
        if (uSrcLoc >= 0) GLES20.glUniform1i(uSrcLoc, FROST_PLATE_UNIT)
        checkGlErrorNoise()
    }

    /** 本类只有一个顶点属性（局部坐标由 uniform 摆出来），所以只开一个 enable 位 */
    private fun bindPositionAttribute() {
        if (aPositionLoc < 0) return
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
    }

    private fun saveState(): Boolean {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_SCISSOR_TEST, scissorState, 0)
        if (scissorState[0] != 0) GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, scissorBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, programBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_BLEND, blendState, 0)
        return scissorState[0] != 0
    }

    private fun restoreState(scissorWasEnabled: Boolean) {
        GLES20.glViewport(viewportBackup[0], viewportBackup[1], viewportBackup[2], viewportBackup[3])
        if (scissorWasEnabled) {
            GLES20.glScissor(scissorBackup[0], scissorBackup[1], scissorBackup[2], scissorBackup[3])
        } else {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }
        if (aPositionLoc >= 0) GLES20.glDisableVertexAttribArray(aPositionLoc)
        // 把霜纹理从取样单元上摘下来：下一帧离屏链要往同一张纹理里画，某些驱动只查"这张还挂在某个
        // 激活单元上"就当 feedback loop 报警（与 FrostBlurChain.restoreState 那条保险同一条理由）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + FROST_PLATE_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (blendState[0] == 0) GLES20.glDisable(GLES20.GL_BLEND)
        // 全工程只有本类开混合，所以"进来时没开 ⇒ 出去时关掉"就等价于恢复原状；混合因子那组
        // 设的就是 GL 默认值（SRC_ALPHA / ONE_MINUS_SRC_ALPHA），不需要额外还回去
        if (programBackup[0] != 0) GLES20.glUseProgram(programBackup[0]) else GLES20.glUseProgram(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun checkGlErrorNoise() {
        drainGlError("frostPlateBuildProgram")
    }

    /** 由 `releaseGl()` 调用（必须已在 GL 线程且上下文 current） */
    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        aPositionLoc = -1
        uRectLoc = -1
        uUvRectLoc = -1
        uHalfPxLoc = -1
        uRadiusPxLoc = -1
        uTintLoc = -1
        uAlphaLoc = -1
        uSrcLoc = -1
        broken = false
    }

    /** 停用后的重试入口（与 [FrostBlurChain.resetAfterBreak] 同一条语义：只在显式人为动作时给机会） */
    fun resetAfterBreak() {
        broken = false
    }
}
