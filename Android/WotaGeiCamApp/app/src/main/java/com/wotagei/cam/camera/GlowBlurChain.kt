package com.wotagei.cam.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.wotagei.cam.core.fillGlowBlurOffset
import com.wotagei.cam.core.glowRawLongestEdge
import com.wotagei.cam.core.glowRtSizeInto
import com.wotagei.cam.diag.Diag as Log
import java.nio.FloatBuffer

internal const val TAG_GLOW = "WotaGlGlow"

/** 连续这么多帧 GL 报错就整条链停用（预览与成片其余部分照常，只是没有光晕） */
private const val GLOW_MAX_ERROR_STREAK = 8

/**
 * Glow 图的取样单元：**主 pass 的 `uGlowSrc` 也用这一号**（`GlowBlurChain` 产出、主 pass 消费）。
 *
 * 与既有分配错开：0 = 相机帧、1 = 斑马纹瓦片、2 = 曲线 LUT、3/4 = 霜链与霜画板。
 * ES2 保证 `MAX_TEXTURE_IMAGE_UNITS ≥ 8`，5 号一定在。写死一个号而不是每次现问，
 * 是为了让"链写在 5、主 pass 读 5"这条对齐关系在源码里可读（守卫钉着）。
 */
internal const val GLOW_SRC_UNIT = 5

/**
 * 拷贝趟的**恒等** 4×2 纹理坐标（左下 (0,0) → 右上 (1,1)）。
 *
 * 不能用 `BuildTexCoords` 产的那两份：它们把转正/镜像烘进去了，而拷贝趟要的是"RT 的第 q 个纹素
 * = 相机帧在 M·q 处的内容"（24 §1.2 的推导）。主 pass 那边 `vUvGlow = aTexCoord` 与这套坐标同源，
 * 光晕才会与画面逐像素对齐。构造期分配一次、每帧只读（与 `FrostBlurChain.quadTexCoords` 同款）。
 */
internal fun glowIdentityTexCoords(): FloatBuffer {
    return nativeFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
}

/**
 * Glow 离屏链（docs/plan/24 R3 §1.2 / §1.4，25 §11.4）：OES → 2D 拷贝 + 两趟可分离高斯。
 *
 * 三趟、三张纹理、三个 FBO（**结果恒在 [resultTex]**）：
 * ```
 * ① OES ──(亮部提取 + 降采样到 1/scale, 恒等 UV)──▶ downTex  @ downFbo
 * ② downTex ──(横向高斯)──▶ scratchTex @ scratchFbo
 * ③ scratchTex ──(纵向高斯)──▶ glowTex @ glowFbo        ← 主 pass 采这张（单元 [GLOW_SRC_UNIT]）
 * ```
 * ③ 写 glowTex 时读的是 scratchTex，不构成 feedback loop。
 *
 * # 与霜链的关系：同构、独立
 * 拓扑与状态纪律照抄 [FrostBlurChain]（那是本项目唯一跑通的真机先例），但**实例、program、纹理单元
 * 全部分开**：两者语义不同（霜是 UI chrome、Glow 是 scene layer），独立之后任一条坏了都不牵连另一条。
 * 拷贝趟用 [Shaders.GLOW_COPY_FS]（**亮部提取**：`smoothstep(threshold±soft)` 后再乘回原色，
 * 这是"只有高光发光"与"整帧糊一层"的分界；霜那枚纯拷贝 [Shaders.FROST_COPY_FS] 不适用），
 * 模糊趟复用 [Shaders.frostGaussianFragment]（只吃 `sampler2D`，与 `samplerExternalOES` 混进同一
 * program 会直接链接失败），所以没有第二份高斯实现。
 *
 * # 三条纪律（每条都在代码里落成判据）
 * - **每帧只在 `drawFrame` 里跑一次**，不放进 `drawPass`（那份绘制一帧被调 2~4 次）；
 * - **尺寸没变不重建**：`scale` / 帧尺寸变了才重建 RT（`glowRtSizeInto` 说尺寸变了才走 `ensureRenderTarget`）；
 * - **状态自恢复**：`glViewport` / `glScissor(+TEST)` / `glUseProgram` / 自己开过的顶点属性 / **`GL_FRAMEBUFFER_BINDING`**
 *   进出各存一次。FBO 这一条比霜更严：Glow 链排在编码 pass 之前，而 MEND 支路对"当前绑定面"有契约
 *   （`ArcMendPass.drawPending` 画进当前面），带脏 FBO 出去就是把待发帧画错地方。
 *
 * # 失败语义：退化成"没有光晕"
 * program 编不出来、FBO 不完整、连续 [GLOW_MAX_ERROR_STREAK] 帧报错 ⇒ [broken] 置位并删干净；
 * [resultTex] 归 0，主 pass 绑 1×1 黑纹理（贡献为 0），预览与成片其余部分零影响。
 * 关掉开关只停跑、**不删资源**（再打开不重建、不闪），资源随 `release()` 统一收口。
 *
 * 只在 GL 线程上活（`GlRenderEngine.postGl` 那条）：它要用当前上下文，而全工程只有这一条上下文。
 *
 * @param linkProgram 与霜链共用引擎那枚 `link()`（同一份日志/退避口径）
 * @param drainGlError 同上；返回本次排空的错误条数（判"这帧画坏了"）
 * @param positionBuffer 全屏四边形（`-1..1`）的顶点缓冲，构造期建好、只读
 */
internal class GlowBlurChain(
    private val linkProgram: (vertexSrc: String, fragmentSrc: String) -> Int,
    private val drainGlError: (where: String) -> Int,
    private val positionBuffer: FloatBuffer
) {

    /**
     * 可采样的 glow 纹理；**0 = 未就绪**（首帧 / 刚开启 / 已停用）——正常状态，不是错误。
     * GL 线程写、UI 线程经 [com.wotagei.cam.core.GlowStatus] 读 ⇒ @Volatile（整体值交换，无需锁）。
     */
    @Volatile
    var resultTex = 0
        private set

    /** 当前 RT 尺寸（0 = 还没建）；`GlowStatus` 在 UI 线程读 ⇒ @Volatile */
    @Volatile
    var rtWidthPx = 0
        private set

    @Volatile
    var rtHeightPx = 0
        private set

    /** 最长边是否被 `GL_MAX_TEXTURE_SIZE` 夹取过（真机排障要看得见，胶囊据此给提示） */
    @Volatile
    var lastClamped = false
        private set

    /** 停用原因已记录在日志；true 之后除 [release] 与 [resetAfterBreak] 外不再做事 */
    @Volatile
    private var broken = false

    /** Glow 探针（B6b）：只读；true = 链接失败/连续 GL 错误后已停用，等显式 resetAfterBreak */
    internal fun isBrokenForDiagnostics(): Boolean = broken

    // --- program（2 枚：拷贝 + 模糊横向/纵向共用）
    private var copyProgram = 0
    private var blurProgram = 0

    // --- 离屏资源（3 纹理 + 3 FBO）
    private var downTex = 0
    private var scratchTex = 0
    private var glowTex = 0
    private var downFbo = 0
    private var scratchFbo = 0
    private var glowFbo = 0
    private var rtW = 0
    private var rtH = 0
    private var maxTextureEdge = 0

    // --- uniform/attribute 位置缓存（建 program 时取一次，与霜链同一范式）
    private var copyAPosition = -1
    private var copyATexCoord = -1
    private var copyUFrame = -1
    private var copyUTexMatrix = -1
    private var copyUThreshold = -1
    private var copyUSoft = -1
    private var copyUTint = -1
    private var copyUColorOnly = -1
    private var blurAPosition = -1
    private var blurATexCoord = -1
    private var blurUSrc = -1
    private var blurUOffset = -1
    private var blurUWeight = -1

    // --- 构造期一次性分配的件（每帧零分配）
    private val offsetScratch = FloatArray(2)
    private val sizeScratch = IntArray(2)
    private val viewportBackup = IntArray(4)
    private val scissorBackup = IntArray(4)
    private val scissorState = IntArray(1)
    private val programBackup = IntArray(1)
    private val fboBackup = IntArray(1)
    private var errorStreak = 0

    /**
     * 提前把 program 编好（开关从 OFF 打到 ON 时调一次，B6b 接线）。
     *
     * 不 lazily 塞在 [draw] 里等第一帧才编：着色器编译是毫秒级且跑在渲染线程上，
     * 落在 `drawFrame` 里就是明晃晃掉一帧。幂等：已编好直接返回，反复翻转不重编译。
     */
    fun prepare() {
        if (broken) return
        if (copyProgram != 0 && blurProgram != 0) return
        buildPrograms()
    }

    /**
     * 跑完整三趟，产出/更新 [resultTex]。
     *
     * @param oesTextureId 相机帧那枚 `GL_TEXTURE_EXTERNAL_OES`
     * @param texMatrix `SurfaceTexture.getTransformMatrix` 的矩阵（**只给拷贝趟**：
     *   RT(q) = B(M·q)，主 pass 的 `vUvGlow` 因此可以不乘矩阵地对齐）
     * @param identityUv [glowIdentityTexCoords] 那枚恒等缓冲（拷贝与模糊两趟共用）
     * @param frameWidthPx 相机帧宽（= 录制档；Glow 分档按它算，不按上屏档）
     * @param frameHeightPx 相机帧高
     * @param scale [com.wotagei.cam.core.glowScaleFor] 的分档（1/2/4）
     * @param widthParam `GlowParams.width`（0..0.2，RT 短边为单位）
     * @param thresholdParam `GlowParams.threshold`（**亮部判定**：拷贝趟的 smoothstep 下界中心）
     * @param softParam `GlowParams.soft`（过渡带半宽；0 = 硬切；阈值接近 1 时在拷贝趟里向 1.0 收敛）
     * @param tintParam 光晕颜色（`GlowParams.color` 的 RGB；`colorOnly=false` 时消费方需传白=不改变）
     * @param colorOnlyParam true = 亮部直接取 tint 色（仅色盘颜色），false = 乘回原色
     * @return true = 本帧产出了可采样的光晕图
     */
    fun draw(
        oesTextureId: Int,
        texMatrix: FloatArray,
        identityUv: FloatBuffer,
        frameWidthPx: Int,
        frameHeightPx: Int,
        scale: Int,
        widthParam: Float,
        thresholdParam: Float,
        softParam: Float,
        tintParam: FloatArray,
        colorOnlyParam: Boolean
    ): Boolean {
        if (broken || oesTextureId == 0) return false
        prepare()
        if (copyProgram == 0 || blurProgram == 0) {
            failChain("program 仍未就绪")
            return false
        }

        val maxEdge = maxTextureSize()
        if (!glowRtSizeInto(sizeScratch, frameWidthPx, frameHeightPx, scale, maxEdge)) {
            // 尺寸/档非法（还没出帧 / 参数没就绪）：这帧没有光晕，但资源留着别拆
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
            // "到底有没有被夹"必须用**夹取前**的原始最长边判：夹取后最长边恒等于上限，
            // 拿它反推会把 4096×2160 @ 4096 这种"本来就正好"误报成夹取（P2 审查项）
            lastClamped = glowRawLongestEdge(frameWidthPx, frameHeightPx, scale) > maxEdge
            if (lastClamped) {
                // "自动变化必须可见"（24 §〇.1-4）：夹取会让光晕整体变小，不写日志就查不出来
                Log.w(TAG_GLOW, "Glow RT 最长边被夹到 ${maxEdge}px（GL_MAX_TEXTURE_SIZE，按最长边等比）")
            }
        }

        val scissorWasEnabled = saveState()
        var errors = 0

        GLES20.glViewport(0, 0, w, h)

        // ① OES → 2D：恒等 UV 坐标 + uTexMatrix，视口=RT 尺寸 ⇒ 一次绘制同时完成"拷进 2D"和"缩"
        GLES20.glUseProgram(copyProgram)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downFbo)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (copyUFrame >= 0) GLES20.glUniform1i(copyUFrame, 0)
        if (copyUTexMatrix >= 0) GLES20.glUniformMatrix4fv(copyUTexMatrix, 1, false, texMatrix, 0)
        // 亮部提取：阈值/软边是**每帧都可能变**的形状参数（只换 uniform，不重建 program/RT）
        if (copyUThreshold >= 0) GLES20.glUniform1f(copyUThreshold, thresholdParam)
        if (copyUSoft >= 0) GLES20.glUniform1f(copyUSoft, softParam)
        // 颜色模式（2026-10-29 用户口径）：每帧随参数走，只换 uniform 不重建 program
        if (copyUTint >= 0) GLES20.glUniform3f(copyUTint, tintParam[0], tintParam[1], tintParam[2])
        if (copyUColorOnly >= 0) GLES20.glUniform1f(copyUColorOnly, if (colorOnlyParam) 1f else 0f)
        drawQuad(copyAPosition, copyATexCoord, identityUv)
        errors += drainGlError("glowCopyPass")

        // ②③ 可分离高斯：横 → scratch，纵 → glow。sampler 单元与权重表在 buildPrograms 里传过，
        // 这里只剩每趟变化的 uOffset（与霜链同一套省法：uniform 属于 program，跨帧保留）
        GLES20.glUseProgram(blurProgram)
        if (fillGlowBlurOffset(offsetScratch, w, h, widthParam, horizontal = true)) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scratchFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GLOW_SRC_UNIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTex)
            if (blurUOffset >= 0) GLES20.glUniform2f(blurUOffset, offsetScratch[0], offsetScratch[1])
            drawQuad(blurAPosition, blurATexCoord, identityUv)
            errors += drainGlError("glowBlurHorizontal")
        }
        if (fillGlowBlurOffset(offsetScratch, w, h, widthParam, horizontal = false)) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GLOW_SRC_UNIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scratchTex)
            if (blurUOffset >= 0) GLES20.glUniform2f(blurUOffset, offsetScratch[0], offsetScratch[1])
            drawQuad(blurAPosition, blurATexCoord, identityUv)
            errors += drainGlError("glowBlurVertical")
        }

        restoreState(scissorWasEnabled)

        if (errors > 0) {
            // 报错帧的 RT 内容不可信（半张图/上帧残留）：撤掉结果，上层这帧退"无光晕"，
            // 比"糊错内容但看不出来"诚实（与霜链同一条口径）
            errorStreak++
            resultTex = 0
            if (errorStreak >= GLOW_MAX_ERROR_STREAK) failChain("连续 $errorStreak 帧 GL 报错")
            return false
        }
        errorStreak = 0
        resultTex = glowTex
        return true
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

    /** `GL_MAX_TEXTURE_SIZE` 只查一次（构造后首次 [draw] 时）。异常值兜 2048（低端机常见上限） */
    private fun maxTextureSize(): Int {
        if (maxTextureEdge > 0) return maxTextureEdge
        val out = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, out, 0)
        maxTextureEdge = if (out[0] > 0) out[0] else 2048
        return maxTextureEdge
    }

    /**
     * 进出各一次：视口、剪裁框（含 `GL_SCISSOR_TEST` 开关）、当前 program、**当前 FBO**、
     * 自己开过的属性数组（ES2 没有 VAO，属性 enable 位是全局的）。
     */
    private fun saveState(): Boolean {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_SCISSOR_TEST, scissorState, 0)
        if (scissorState[0] != 0) GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, scissorBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, programBackup, 0)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fboBackup, 0)
        return scissorState[0] != 0
    }

    private fun restoreState(scissorWasEnabled: Boolean) {
        // FBO 必须还原成"进来时那一枚"，不能无脑回 0：MEND 支路的 drawPending 画进**当前**面
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBackup[0])
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
        // 把源纹理从取样单元上摘下来：下一帧要往这几张里画，某些驱动只查"这张还挂在某个激活单元上"
        // 就当 feedback loop 报警（与霜链 restoreState 那条保险同一条理由）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GLOW_SRC_UNIT)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (programBackup[0] != 0) GLES20.glUseProgram(programBackup[0]) else GLES20.glUseProgram(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /**
     * 建/重建三张纹理与三枚 FBO：只在尺寸变了之后走这一条。
     *
     * 内部格式 RGBA8 = ES2 保证可渲染的组合；采样 LINEAR + CLAMP_TO_EDGE（模糊要相邻纹素插值，
     * CLAMP 保证画面边缘不绕回另一侧取到对边的灯）。内部会临时绑 FBO，退出时还原进来时那一枚。
     */
    private fun ensureRenderTarget(w: Int, h: Int): Boolean {
        deleteRenderTarget()
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fboBackup, 0)

        val texIds = IntArray(3)
        GLES20.glGenTextures(3, texIds, 0)
        downTex = texIds[0]
        scratchTex = texIds[1]
        glowTex = texIds[2]
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

        val fboIds = IntArray(3)
        GLES20.glGenFramebuffers(3, fboIds, 0)
        downFbo = fboIds[0]
        scratchFbo = fboIds[1]
        glowFbo = fboIds[2]
        var complete = attach(downFbo, downTex)
        complete = complete and attach(scratchFbo, scratchTex)
        complete = complete and attach(glowFbo, glowTex)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBackup[0])
        if (drainGlError("glowRenderTarget") > 0) complete = false
        if (!complete) {
            Log.w(TAG_GLOW, "FBO 不完整，停用 Glow 链（预览与成片其余部分照常）")
            deleteRenderTarget()
            return false
        }
        rtW = w
        rtH = h
        rtWidthPx = w
        rtHeightPx = h
        Log.i(TAG_GLOW, "Glow 离屏 RT ${w}x$h（三张 2D 纹理，主 pass 采同一张）")
        return true
    }

    private fun attach(fbo: Int, texture: Int): Boolean {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0
        )
        return GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
    }

    /**
     * 编两枚 program。模糊那枚先试 highp，失败退 mediump 再编一次（与 `buildProgram` 同款退避）；
     * 拷贝那枚本来就是 mediump，编不出来就没有退路，直接停用。
     */
    private fun buildPrograms() {
        // 幂等重建：上一轮 blur 链接失败会留下 copyProgram != 0 && blurProgram == 0 的半初始化态，
        // prepare 的「两个都非 0 才短路」拦不住再次进入这里——先删旧件再编，否则旧 program 泄漏
        deletePrograms()
        // 拷贝趟 = **亮部提取**（GLOW_COPY_FS，不是霜那枚纯拷贝）：整帧模糊不是 Glow
        copyProgram = linkProgram(Shaders.TEXTURE_VS, Shaders.GLOW_COPY_FS)
        if (copyProgram == 0) return
        copyAPosition = GLES20.glGetAttribLocation(copyProgram, "aPosition")
        copyATexCoord = GLES20.glGetAttribLocation(copyProgram, "aTexCoord")
        copyUFrame = GLES20.glGetUniformLocation(copyProgram, "uFrame")
        copyUTexMatrix = GLES20.glGetUniformLocation(copyProgram, "uTexMatrix")
        copyUThreshold = GLES20.glGetUniformLocation(copyProgram, "uGlowThreshold")
        copyUSoft = GLES20.glGetUniformLocation(copyProgram, "uGlowSoft")
        copyUTint = GLES20.glGetUniformLocation(copyProgram, "uGlowTint")
        copyUColorOnly = GLES20.glGetUniformLocation(copyProgram, "uGlowColorOnly")

        blurProgram = linkProgram(Shaders.FROST_QUAD_VS, gaussianSource(highp = true))
        if (blurProgram == 0) {
            Log.w(TAG_GLOW, "highp 光晕模糊 program 链接失败，退回 mediump 再编一次")
            blurProgram = linkProgram(Shaders.FROST_QUAD_VS, gaussianSource(highp = false))
        }
        if (blurProgram == 0) return
        blurAPosition = GLES20.glGetAttribLocation(blurProgram, "aPosition")
        blurATexCoord = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
        blurUSrc = GLES20.glGetUniformLocation(blurProgram, "uSrc")
        blurUOffset = GLES20.glGetUniformLocation(blurProgram, "uOffset")
        blurUWeight = GLES20.glGetUniformLocation(blurProgram, "uWeight")
        // 表长不变的常量（sampler 单元 + 权重）在这里传一次：uniform 属于 program，跨帧保留
        GLES20.glUseProgram(blurProgram)
        if (blurUSrc >= 0) GLES20.glUniform1i(blurUSrc, GLOW_SRC_UNIT)
        if (blurUWeight >= 0) {
            GLES20.glUniform1fv(blurUWeight, FROST_BLUR_WEIGHTS.size, FROST_BLUR_WEIGHTS, 0)
        }
        drainGlError("glowBuildPrograms")
    }

    /** 抽头数只从权重表来（与霜链同一份表、同一条"表与源同源"纪律） */
    private fun gaussianSource(highp: Boolean): String {
        return Shaders.frostGaussianFragment(FROST_BLUR_WEIGHTS.size, highp)
    }

    private fun failChain(reason: String) {
        Log.w(TAG_GLOW, "Glow 链停用：$reason")
        broken = true
        resultTex = 0
        deleteRenderTarget()
        deletePrograms()
        rtW = 0
        rtH = 0
    }

    /**
     * 停用后的重试入口：开关从 OFF 打到 ON 时给一次机会（显式人为动作，不是每帧重试）。
     * 与 [FrostBlurChain.resetAfterBreak] 同一条语义。
     */
    fun resetAfterBreak() {
        broken = false
        errorStreak = 0
    }

    private fun deleteRenderTarget() {
        if (downTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(downTex), 0)
            downTex = 0
        }
        if (scratchTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(scratchTex), 0)
            scratchTex = 0
        }
        if (glowTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(glowTex), 0)
            glowTex = 0
        }
        if (downFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(downFbo), 0)
            downFbo = 0
        }
        if (scratchFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(scratchFbo), 0)
            scratchFbo = 0
        }
        if (glowFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(glowFbo), 0)
            glowFbo = 0
        }
        rtW = 0
        rtH = 0
        rtWidthPx = 0
        rtHeightPx = 0
        // 夹取位跟着 RT 一起归零：新 RT 还没建，谈"这一档有没有被夹"没有意义
        lastClamped = false
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
        copyUThreshold = -1
        copyUSoft = -1
        copyUTint = -1
        copyUColorOnly = -1
        blurAPosition = -1
        blurATexCoord = -1
        blurUSrc = -1
        blurUOffset = -1
        blurUWeight = -1
    }

    /** 由 `releaseGl()` 调用（必须已在 GL 线程且上下文 current）；只在这里删光资源 */
    fun release() {
        resultTex = 0
        lastClamped = false
        deleteRenderTarget()
        deletePrograms()
        broken = false
        errorStreak = 0
    }
}
