package com.wotagei.cam

import com.wotagei.cam.camera.Shaders
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glow 离屏链（`camera/GlowBlurChain.kt`）的**结构与纪律守卫**（docs/plan/24 R3 §1.1–§1.4，
 * 25 §11.4）。全是 GLES 调用、JVM 跑不了行为，所以按工程惯例（[FrostEncoderGuardTest] /
 * [FrostChainProgramGuardTest]）落源码级判据。
 *
 * 本文件只管**链本体**（资源拓扑/三趟/状态/失败语义）；"链接到引擎哪里、每帧跑几次、参数怎么进键"
 * 由 `GlowWiringGuardTest` 把守（B6b）——链只许在 `drawFrame` 里跑一次，`drawPass` 只采样结果。
 *
 * # 为什么判据都写成 `xxxIssues(masked): List<String>`
 * 每条判据都要能被**突变体**再喂一遍（`xxxIssues(mutated).isNotEmpty()`），证明这把尺子真的能量出
 * 缺陷。只对突变体做 `contains` 等于没测尺子——那是本项目最常见的假绿形态。
 */
class GlowChainGuardTest {

    private val src by lazy { KotlinSourceScan.mainSourceText("camera/GlowBlurChain.kt") }

    private val masked by lazy { KotlinSourceScan.codeOnly(src) }

    private fun body(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, name))

    /**
     * 子串出现次数。**刻意不用 [KotlinSourceScan.occurrences]**：它按词边界判，
     * 而这里要数的针以 `(` 结尾（`attach(`、`drawQuad(`），其"后一个字符"必然是标识符字符、
     * 会被它整批判掉——那正是"尺子恒为 0"的假判据。
     */
    private fun countOf(text: String, needle: String): Int = text.split(needle).size - 1

    private fun bodyOfSrc(maskedSrc: String, name: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedSrc, name))

    // ------------------------------------------------------------------ 判据函数（真身与突变体同吃一把尺）

    /** ① 拷贝趟 = **亮部提取**（threshold/soft 真的进了着色器与 uniform），且用恒等 UV + 相机矩阵 */
    private fun copyPassIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val build = runCatching { bodyOfSrc(maskedSrc, "buildPrograms") }.getOrNull()
            ?: return listOf("buildPrograms 定位失败（守卫失去输入）")
        val draw = runCatching { bodyOfSrc(maskedSrc, "draw") }.getOrNull()
            ?: return listOf("draw 定位失败（守卫失去输入）")
        if (!build.contains("linkProgram(Shaders.TEXTURE_VS, Shaders.GLOW_COPY_FS)")) {
            bad.add("拷贝 program 必须是 TEXTURE_VS + GLOW_COPY_FS（纯拷贝 FROST_COPY_FS 会把整帧糊一层，不是高光发光）")
        }
        val shader = Shaders.GLOW_COPY_FS
        if (!shader.contains("smoothstep(")) bad.add("GLOW_COPY_FS 缺 smoothstep 亮部提取")
        // ⚠ 遮蔽文本把字符串内容替换成空白：uniform 名不能按字面量查，
        // 按"标识符 = glGetUniformLocation(copyProgram, …)"这一行判（标识符是代码）
        if (!build.contains("copyUThreshold = GLES20.glGetUniformLocation(copyProgram,")) {
            bad.add("拷贝趟缺 uGlowThreshold 位置（阈值滑杆会变成死参数）")
        }
        if (!build.contains("copyUSoft = GLES20.glGetUniformLocation(copyProgram,")) {
            bad.add("拷贝趟缺 uGlowSoft 位置（软边滑杆会变成死参数）")
        }
        if (!draw.contains("glUniform1f(copyUThreshold, thresholdParam)")) {
            bad.add("拷贝趟必须每帧传 threshold（否则阈值滑杆是死参数）")
        }
        if (!draw.contains("glUniform1f(copyUSoft, softParam)")) {
            bad.add("拷贝趟必须每帧传 soft（否则软边滑杆是死参数）")
        }
        // 恒等 UV：拷贝趟不能用上屏坐标（用了 = 光晕相对画面差一次旋转/镜像）
        if (!draw.contains("drawQuad(copyAPosition, copyATexCoord, identityUv)")) {
            bad.add("拷贝趟必须用恒等 UV 缓冲（drawQuad(copyAPosition, copyATexCoord, identityUv)）")
        }
        if (!draw.contains("glUniformMatrix4fv(copyUTexMatrix, 1, false, texMatrix, 0)")) {
            bad.add("拷贝趟必须把相机矩阵传给 uTexMatrix（RT(q)=B(M·q) 的推导前提）")
        }
        if (maskedSrc.contains("displayTexCoord")) {
            bad.add("整条链不许认识上屏纹理坐标")
        }
        // 恒等 UV 缓冲的内容（构造函数是唯一真源）
        val identity = runCatching { bodyOfSrc(maskedSrc, "glowIdentityTexCoords") }.getOrNull()
        if (identity == null || !identity.contains("floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)")) {
            bad.add("恒等 UV 必须是 (0,0)(1,0)(0,1)(1,1)")
        }
        if (countOf(draw, "drawQuad(") != 3) {
            bad.add("拷贝与两趟模糊都用同一枚 identityUv（drawQuad 调用点应为 3，实际 ${countOf(draw, "drawQuad(")}）")
        }
        return bad
    }

    /** ② 模糊趟复用霜的高斯 + highp→mediump 回退 + 权重表同源 */
    private fun blurPassIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val build = runCatching { bodyOfSrc(maskedSrc, "buildPrograms") }.getOrNull()
            ?: return listOf("buildPrograms 定位失败")
        val draw = runCatching { bodyOfSrc(maskedSrc, "draw") }.getOrNull()
            ?: return listOf("draw 定位失败")
        if (!build.contains("Shaders.FROST_QUAD_VS")) bad.add("模糊 program 必须复用 FROST_QUAD_VS")
        if (!build.contains("gaussianSource(highp = true)")) bad.add("必须先试 highp")
        if (!build.contains("gaussianSource(highp = false)")) bad.add("highp 失败必须退 mediump 再编一次")
        val gaussian = runCatching { bodyOfSrc(maskedSrc, "gaussianSource") }.getOrNull()
        if (gaussian == null || !gaussian.contains("Shaders.frostGaussianFragment")) {
            bad.add("gaussianSource 必须调 Shaders.frostGaussianFragment（不另写一份高斯）")
        }
        if (gaussian == null || !gaussian.contains("FROST_BLUR_WEIGHTS.size")) {
            bad.add("抽头数必须来自 FROST_BLUR_WEIGHTS.size")
        }
        if (!build.contains("glUniform1fv(blurUWeight")) bad.add("uWeight 必须在建 program 时上传一次")
        if (draw.contains("glUniform1fv")) bad.add("draw 不许每帧重传权重")
        return bad
    }

    /** ③ 资源拓扑：3 纹理 / 3 FBO / 取样单元 5 */
    private fun resourceIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val ensure = runCatching { bodyOfSrc(maskedSrc, "ensureRenderTarget") }.getOrNull()
            ?: return listOf("ensureRenderTarget 定位失败")
        if (!ensure.contains("glGenTextures(3")) bad.add("必须建三张纹理")
        if (!ensure.contains("glGenFramebuffers(3")) bad.add("必须建三个 FBO")
        if (countOf(ensure, "attach(") != 3) bad.add("三个 FBO 都要 attach 并验完整（实际 ${countOf(ensure, "attach(")}）")
        if (!ensure.contains("for (id in texIds)")) bad.add("三张纹理必须同一段循环分配")
        if (!ensure.contains("glTexImage2D(")) bad.add("纹理必须真的分配存储")
        if (!maskedSrc.contains("internal const val GLOW_SRC_UNIT = 5")) bad.add("GLOW_SRC_UNIT 必须是 5")
        val build = runCatching { bodyOfSrc(maskedSrc, "buildPrograms") }.getOrNull().orEmpty()
        if (!build.contains("glUniform1i(blurUSrc, GLOW_SRC_UNIT)")) {
            bad.add("模糊趟的 uSrc 必须绑 GLOW_SRC_UNIT")
        }
        val draw = runCatching { bodyOfSrc(maskedSrc, "draw") }.getOrNull().orEmpty()
        if (countOf(draw, "GL_TEXTURE0 + GLOW_SRC_UNIT") != 2) {
            bad.add("两趟模糊都要在 GLOW_SRC_UNIT 上取样")
        }
        return bad
    }

    /** ④ 状态自恢复：视口 / 剪裁 / program / 属性 / **FBO 绑定** */
    private fun stateIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val save = runCatching { bodyOfSrc(maskedSrc, "saveState") }.getOrNull()
            ?: return listOf("saveState 定位失败")
        val restore = runCatching { bodyOfSrc(maskedSrc, "restoreState") }.getOrNull()
            ?: return listOf("restoreState 定位失败")
        for (token in listOf("GL_VIEWPORT", "GL_SCISSOR_TEST", "GL_SCISSOR_BOX", "GL_CURRENT_PROGRAM", "GL_FRAMEBUFFER_BINDING")) {
            if (!save.contains(token)) bad.add("saveState 必须保存 $token")
        }
        if (!restore.contains("glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBackup[0])")) {
            bad.add("restoreState 必须把 FBO 还原成进来时那一枚（不是无脑回 0：MEND 的 drawPending 画进当前面）")
        }
        if (!restore.contains("glViewport(viewportBackup[0]")) bad.add("restoreState 必须还原视口")
        if (!restore.contains("glScissor(scissorBackup[0]")) bad.add("restoreState 必须还原 scissor 开关")
        if (!restore.contains("glUseProgram(programBackup[0])")) bad.add("restoreState 必须还原 program")
        if (KotlinSourceScan.occurrences(restore, "glDisableVertexAttribArray").size != 4) {
            bad.add("自己开过的四个属性下标都要关掉（ES2 没有 VAO）")
        }
        if (!restore.contains("GL_TEXTURE0 + GLOW_SRC_UNIT")) bad.add("restoreState 必须摘掉 5 号单元上的纹理")
        if (!restore.contains("glActiveTexture(GLES20.GL_TEXTURE0)")) bad.add("restoreState 必须把激活单元复位")
        return bad
    }

    /** ⑤ 失败语义：streak=8 停链 + 可重试 + release 收口 */
    private fun failureIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        if (!maskedSrc.contains("GLOW_MAX_ERROR_STREAK = 8")) bad.add("错误 streak 阈值必须是 8")
        val draw = runCatching { bodyOfSrc(maskedSrc, "draw") }.getOrNull()
            ?: return bad + "draw 定位失败"
        if (!draw.contains("errorStreak >= GLOW_MAX_ERROR_STREAK")) bad.add("连续报错到阈值必须停链")
        if (!draw.contains("resultTex = 0")) bad.add("报错帧必须撤掉结果（半张图比没有更坏）")
        val fail = runCatching { bodyOfSrc(maskedSrc, "failChain") }.getOrNull().orEmpty()
        if (!fail.contains("broken = true")) bad.add("failChain 必须置 broken")
        if (!fail.contains("deleteRenderTarget()")) bad.add("failChain 必须删资源")
        if (!fail.contains("deletePrograms()")) bad.add("failChain 必须删 program")
        val reset = runCatching { bodyOfSrc(maskedSrc, "resetAfterBreak") }.getOrNull().orEmpty()
        if (!reset.contains("broken = false")) bad.add("resetAfterBreak 必须清 broken")
        if (!reset.contains("errorStreak = 0")) bad.add("resetAfterBreak 必须清 streak")
        val release = runCatching { bodyOfSrc(maskedSrc, "release") }.getOrNull().orEmpty()
        if (!release.contains("resultTex = 0")) bad.add("release 必须清结果纹理 id")
        if (!release.contains("deleteRenderTarget()")) bad.add("release 必须删资源")
        if (!release.contains("deletePrograms()")) bad.add("release 必须删 program")
        return bad
    }

    /** ⑥ 幂等重建 + 不触发重画 */
    private fun rebuildIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val build = runCatching { bodyOfSrc(maskedSrc, "buildPrograms") }.getOrNull()
            ?: return listOf("buildPrograms 定位失败")
        val del = build.indexOf("deletePrograms()")
        val link = build.indexOf("linkProgram(")
        if (del < 0 || link < 0 || link < del) bad.add("buildPrograms 必须先 deletePrograms 再 link（半初始化态下不漏 program）")
        val prepare = runCatching { bodyOfSrc(maskedSrc, "prepare") }.getOrNull().orEmpty()
        if (!prepare.contains("copyProgram != 0 && blurProgram != 0")) bad.add("prepare 必须幂等（两枚都在就短路）")
        if (maskedSrc.contains("requestRender")) bad.add("链不许碰 requestRender（会触发 state-only 重画 → 成片重复帧）")
        return bad
    }

    /** ⑦ 尺寸变化才重建 + 夹取可见 + **夹取判据用夹取前尺寸** */
    private fun clampIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val draw = runCatching { bodyOfSrc(maskedSrc, "draw") }.getOrNull()
            ?: return listOf("draw 定位失败")
        if (!draw.contains("glowRtSizeInto(")) bad.add("必须按 glowRtSizeInto 算 RT 尺寸")
        if (!draw.contains("if (w != rtW || h != rtH)")) bad.add("必须先比尺寸再重建")
        if (!draw.contains("maxTextureSize()")) bad.add("必须按运行时 GL_MAX_TEXTURE_SIZE 夹取")
        if (!draw.contains("lastClamped = glowRawLongestEdge(")) {
            bad.add("夹取判据必须用**夹取前**的原始最长边（拿夹取后尺寸反推会把 4096×2160 误报成夹取）")
        }
        val max = runCatching { bodyOfSrc(maskedSrc, "maxTextureSize") }.getOrNull().orEmpty()
        if (!max.contains("GL_MAX_TEXTURE_SIZE")) bad.add("必须真的查 GL_MAX_TEXTURE_SIZE")
        val delete = runCatching { bodyOfSrc(maskedSrc, "deleteRenderTarget") }.getOrNull().orEmpty()
        if (!delete.contains("lastClamped = false")) bad.add("deleteRenderTarget 必须复位 lastClamped")
        if (!draw.contains("Log.w(TAG_GLOW") && !draw.contains("Log.i(TAG_GLOW")) {
            bad.add("分档/夹取必须留日志（自动变化必须可见，不许静默变柔）")
        }
        return bad
    }

    // ------------------------------------------------------------------ 真身

    @Test
    fun `拷贝趟做亮部提取且用恒等UV与相机矩阵`() {
        assertEquals("拷贝趟缺口：\n${copyPassIssues(masked).joinToString("\n")}", emptyList<String>(), copyPassIssues(masked))
    }

    @Test
    fun `模糊趟复用霜的高斯且highp失败退mediump`() {
        assertEquals("模糊趟缺口：\n${blurPassIssues(masked).joinToString("\n")}", emptyList<String>(), blurPassIssues(masked))
    }

    @Test
    fun `三张纹理三个FBO与取样单元5`() {
        assertEquals("资源拓扑缺口：\n${resourceIssues(masked).joinToString("\n")}", emptyList<String>(), resourceIssues(masked))
    }

    @Test
    fun `进出各存各恢复且含FBO绑定`() {
        assertEquals("状态恢复缺口：\n${stateIssues(masked).joinToString("\n")}", emptyList<String>(), stateIssues(masked))
    }

    @Test
    fun `失败语义是停用整条链且可重试`() {
        assertEquals("失败语义缺口：\n${failureIssues(masked).joinToString("\n")}", emptyList<String>(), failureIssues(masked))
    }

    @Test
    fun `幂等重建且不触发重画`() {
        assertEquals("重建/重画缺口：\n${rebuildIssues(masked).joinToString("\n")}", emptyList<String>(), rebuildIssues(masked))
    }

    @Test
    fun `尺寸变化才重建且夹取判据用夹取前尺寸`() {
        assertEquals("尺寸/夹取缺口：\n${clampIssues(masked).joinToString("\n")}", emptyList<String>(), clampIssues(masked))
    }

    // ------------------------------------------------------------------ 突变自证（每把尺子都要能量出缺陷）

    @Test
    fun `尺子能红_拷贝趟退回纯拷贝或换掉UV`() {
        assertEquals("真身必须绿", emptyList<String>(), copyPassIssues(masked))
        // 纯拷贝（= 整帧模糊叠加，threshold/soft 变死参数）
        val pureCopy = masked.replace("Shaders.GLOW_COPY_FS", "Shaders.FROST_COPY_FS")
        assertTrue("退回纯拷贝的突变必须报红", copyPassIssues(pureCopy).isNotEmpty())
        // 上屏坐标（= 光晕转两遍）
        val displayUv = masked.replace(
            "drawQuad(copyAPosition, copyATexCoord, identityUv)",
            "drawQuad(copyAPosition, copyATexCoord, displayTexCoords)"
        )
        assertTrue("换掉拷贝趟 UV 的突变必须报红", copyPassIssues(displayUv).isNotEmpty())
        // 阈值不传（= 滑杆无效）
        val noThreshold = masked.replace(
            "if (copyUThreshold >= 0) GLES20.glUniform1f(copyUThreshold, thresholdParam)",
            ""
        )
        assertTrue("丢掉 threshold uniform 的突变必须报红", copyPassIssues(noThreshold).isNotEmpty())
        val noSoft = masked.replace(
            "if (copyUSoft >= 0) GLES20.glUniform1f(copyUSoft, softParam)",
            ""
        )
        assertTrue("丢掉 soft uniform 的突变必须报红", copyPassIssues(noSoft).isNotEmpty())
        // 相机矩阵不传（= RT 与画面差一次矩阵变换）
        val noMatrix = masked.replace(
            "if (copyUTexMatrix >= 0) GLES20.glUniformMatrix4fv(copyUTexMatrix, 1, false, texMatrix, 0)",
            ""
        )
        assertTrue("丢掉 uTexMatrix 的突变必须报红", copyPassIssues(noMatrix).isNotEmpty())
    }

    @Test
    fun `尺子能红_单元号与FBO恢复与夹取判据`() {
        assertEquals("真身必须绿", emptyList<String>(), resourceIssues(masked))
        assertEquals("真身必须绿", emptyList<String>(), stateIssues(masked))
        assertEquals("真身必须绿", emptyList<String>(), clampIssues(masked))

        // 单元号改成 4（与霜画板撞车）
        val unitSwapped = src.replace("GLOW_SRC_UNIT = 5", "GLOW_SRC_UNIT = 4")
        assertTrue("单元号突变必须报红", resourceIssues(KotlinSourceScan.codeOnly(unitSwapped)).isNotEmpty())

        // 抽掉 FBO 还原（= MEND 待发帧画错面）
        val noRestore = masked.replace("glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBackup[0])", "")
        assertTrue("抽掉 FBO 还原的突变必须报红", stateIssues(noRestore).isNotEmpty())

        // 夹取判据退回"夹取后尺寸"（= 4096×2160 被误报成夹取）
        val wrongClamp = masked.replace(
            "lastClamped = glowRawLongestEdge(frameWidthPx, frameHeightPx, scale) > maxEdge",
            "lastClamped = kotlin.math.max(w, h) >= maxEdge"
        )
        assertTrue("夹取判据退化的突变必须报红", clampIssues(wrongClamp).isNotEmpty())
    }

    @Test
    fun `尺子能红_失败streak与requestRender`() {
        assertEquals("真身必须绿", emptyList<String>(), failureIssues(masked))
        assertEquals("真身必须绿", emptyList<String>(), rebuildIssues(masked))

        val noStreak = masked.replace("if (errorStreak >= GLOW_MAX_ERROR_STREAK)", "if (false)")
        assertTrue("摘掉 streak 判据的突变必须报红", failureIssues(noStreak).isNotEmpty())

        val withRedraw = masked.replace("fun prepare() {", "fun prepare() {\n        requestRender()")
        assertTrue("链里调 requestRender 的突变必须报红", rebuildIssues(withRedraw).isNotEmpty())
    }
}
