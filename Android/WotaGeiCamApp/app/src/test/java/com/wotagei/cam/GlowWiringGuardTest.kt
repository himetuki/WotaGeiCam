package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glow 接到引擎上的**接线守卫**（docs/plan/24 R3 §1.2/§1.3，25 §11.3/§11.4，B6b）。
 *
 * 素材层（模型/着色器/链）由 `GlowScaleTest` / `GlowShaderTest` / `GlowChainGuardTest` 把守；
 * 这里钉的是"**素材真的被接上了、而且只接在该接的地方**"——后者尤其重要：
 * - 链若被塞进 `drawPass`（一帧调 2~4 次）⇒ 每帧跑 2~4 趟模糊，零代价当场破产（§11.4 点名）；
 * - 链若排在编码 pass 之后 ⇒ MEND 的 cur 渲染采到上一帧的光晕（错帧）；
 * - `setGlowParams` 若调 `requestRender` ⇒ 录制中拖一条滑杆就往成片塞一帧重复帧（§11.7）；
 * - 参数只写 volatile 而不进 program 键 ⇒ 开关永远不生效（"能点、无效果"）。
 *
 * 全部是源码级判据（GL 行为只有真机能证，T1/T11/T17）。
 */
class GlowWiringGuardTest {

    private val engineSrc by lazy { KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt") }
    private val engine by lazy { KotlinSourceScan.codeOnly(engineSrc) }
    private val direct by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/DirectSink.kt")) }
    private val provider by lazy { KotlinSourceScan.mainSourceText("camera/GlowProvider.kt") }

    private fun body(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, name))

    private fun directBody(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(direct, name))

    @Test
    fun `两个sink都实现GlowProvider`() {
        assertTrue(
            "GlRenderEngine 必须实现 GlowProvider（B7 的 UI 只认这个接口）",
            Regex("class GlRenderEngine\\s*:[^{]*\\bGlowProvider\\b").containsMatchIn(engine)
        )
        assertTrue(
            "DirectSink 必须实现 GlowProvider（DIRECT 下在场但禁用）",
            Regex("class DirectSink\\s*:[^{]*\\bGlowProvider\\b").containsMatchIn(direct)
        )
        // 接口本身的三件事（模型注释里写的是"能不能/开没开/读数"三分）
        for (fn in listOf("fun setGlowParams(params: GlowParams)", "fun isGlowAvailable(): Boolean", "fun glowStatus(): GlowStatus")) {
            assertTrue("GlowProvider 缺 $fn", provider.contains(fn))
        }
    }

    @Test
    fun `setGlowParams绝不触发重画`() {
        val body = body("setGlowParams")
        assertTrue("锚点丢失：setGlowParams 里没截到 enabled 判定", body.contains("next.enabled"))
        assertFalse(
            "setGlowParams 不许调 requestRender（录制中会多一帧重复帧）",
            body.contains("requestRender")
        )
        assertFalse("setGlowParams 不许置 dirty", body.contains("dirty = true"))
        assertFalse("setGlowParams 不许直接排帧", body.contains("scheduleDraw"))
        // 首次开启才做编译/建链（关掉只停跑）
        assertTrue("enabled 翻真时必须预热 glow 变体", body.contains("prepareGlowPrograms()"))
        assertTrue("enabled 翻真时给链一次重试机会", body.contains("glowChain?.prepare()"))
    }

    @Test
    fun `链只在drawFrame里跑且排在编码pass之前`() {
        val frame = body("drawFrame")
        assertEquals("drawGlowPass 在帧循环里必须恰好一处", 1, KotlinSourceScan.occurrences(frame, "drawGlowPass(hasFrame)").size)
        val glowAt = frame.indexOf("drawGlowPass(hasFrame)")
        val encoderAt = frame.indexOf("drawEncoderPass()")
        assertTrue("锚点丢失：drawFrame 里没截到 drawEncoderPass()", encoderAt >= 0)
        assertTrue("Glow 链必须排在编码 pass 之前（MEND 的 cur 渲染也要采这张图）", glowAt in 0 until encoderAt)
        // 也不许跑到成片槽同步之前（那条要先定 program，链与 program 无关但顺序口径固定）
        val syncAt = frame.indexOf("syncMovieSlot()")
        assertTrue("Glow 链必须排在 syncMovieSlot() 之后（§11.7 定版顺序）", syncAt in 0 until glowAt)
        // drawPass 只采样、不跑链
        assertFalse(
            "drawPass 不许出现 glowChain —— 它一帧被调 2~4 次，链在里面就是 2~4 趟模糊",
            body("drawPass").contains("glowChain")
        )
    }

    @Test
    fun `drawGlowPass的三条早退与每帧一次`() {
        val body = body("drawGlowPass")
        assertTrue("关着必须一次 GL 调用都不发", body.contains("!glowParams.enabled"))
        assertTrue("blend=0 不跑链（零代价早退）", body.contains("glowParams.blend <= 0f"))
        assertTrue("state-only 帧不跑链", body.contains("if (!hasFrame) return"))
        assertTrue(
            "没有编码面也没有窗口面时不跑链（没有消费者）",
            body.contains("encoderEglSurface == null && windowEglSurface == null")
        )
        assertTrue(
            "空闲预览被上屏节流的帧不跑链（编码面在位时不许省：编码器每帧都要这张图）",
            body.contains("encoderEglSurface == null && windowThrottled()")
        )
        assertTrue("必须按相机帧尺寸 + 帧率分档", body.contains("glowScaleFor("))
        assertTrue("必须把恒等 UV 交给链", body.contains("identityUv = uv"))
        assertTrue("必须把 threshold 交给链（否则阈值滑杆无效）", body.contains("thresholdParam = glowParams.threshold"))
        assertTrue("必须把 soft 交给链（否则软边滑杆无效）", body.contains("softParam = glowParams.soft"))
        assertTrue("分档变化必须留日志（自动变化必须可见）", body.contains("TAG_GLOW") && body.contains("glowLoggedScale"))
        // 不调 requestRender（链只是产出纹理，不该引发重画）
        assertFalse("drawGlowPass 不许调 requestRender", body.contains("requestRender"))
    }

    /**
     * 键/着色器版本的判据（真身与突变体同吃一把尺）。`glowOn` 是表达式体，按整文件精确串钉。
     */
    private fun fragmentIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        if (!maskedSrc.contains("fun glowOn(): Boolean = glowParams.enabled")) {
            bad.add("glowOn() 必须取 glowParams.enabled（写死 false = 能点但无效果）")
        }
        fun slice(name: String): String? =
            runCatching { KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedSrc, name)) }.getOrNull()
        val link = slice("linkProgram") ?: return bad + "linkProgram 定位失败"
        val fragment = slice("fragmentFor") ?: return bad + "fragmentFor 定位失败"
        if (!link.contains("Shaders.TEXTURE_VS_GLOW")) bad.add("glow=true 时必须配 TEXTURE_VS_GLOW")
        if (!link.contains("else Shaders.TEXTURE_VS")) bad.add("glow=false 时必须配 TEXTURE_VS（源码逐字不变）")
        if (!fragment.contains("glowFragment(")) bad.add("片元必须经 glowFragment 注入")
        if (!fragment.contains("if (!key.glow) return base")) {
            bad.add("fragmentFor 必须先短路 glow=false（关着时一字节都不注入）")
        }
        if (!link.contains("fragmentFor(key) ?: return null")) {
            bad.add("注入失败必须整条链接失败（让回退阶梯降掉 glow），不许静默用无 Glow 源")
        }
        return bad
    }

    @Test
    fun `glow维度进program键且着色器按开关分版本`() {
        val issues = fragmentIssues(engine)
        assertEquals("Glow 键/着色器版本缺口：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `尺子能红_删掉glow短路或键维度必红`() {
        assertEquals("真身必须绿", emptyList<String>(), fragmentIssues(engine))
        // 突变 1：删掉 `if (!key.glow) return base`（= 关着也走注入，零代价破产）
        val noShortCircuit = engine.replace("        if (!key.glow) return base", "")
        assertTrue(
            "删掉 glow 短路的突变必须报红",
            fragmentIssues(noShortCircuit).any { it.contains("短路") }
        )
        // 突变 2：键维度写死 false（= 开关不进 program 键）
        val stuckOff = engine.replace("fun glowOn(): Boolean = glowParams.enabled", "fun glowOn(): Boolean = false")
        assertTrue("写死 glowOn 的突变必须报红", fragmentIssues(stuckOff).isNotEmpty())
        // 突变 3：注入失败静默回原串（= "开了没效果"且查不出来）
        val silent = engine.replace("fragmentFor(key) ?: return null", "fragmentFor(key) ?: \"\"")
        assertTrue("静默回退的突变必须报红", fragmentIssues(silent).isNotEmpty())
    }

    @Test
    fun `drawPass绑5号单元并画完解绑`() {
        val body = body("drawPass")
        assertTrue("必须绑 GLOW_SRC_UNIT", body.contains("GL_TEXTURE0 + GLOW_SRC_UNIT"))
        assertTrue("未就绪兜底 1×1 黑纹理", body.contains("glowBlackTex"))
        assertTrue("必须传 uGlowSrc", body.contains("p.uGlowSrc"))
        for (u in listOf("uGlowGain", "uGlowAdd", "uGlowFreq", "uGlowBlend", "uGlowTint")) {
            assertTrue("drawPass 缺 $u 的写入", body.contains("p.$u"))
        }
        // 画完必须解绑（下一帧链要往同一张里画，挂着会被当 feedback loop）
        val drawAt = body.indexOf("glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)")
        val unbindAt = body.indexOf("glBindTexture(GLES20.GL_TEXTURE_2D, 0)")
        assertTrue("drawPass 锚点丢失：drawArrays", drawAt >= 0)
        assertTrue("解绑必须在 drawArrays 之后", unbindAt > drawAt)
    }

    @Test
    fun `预热与释放都登记了Glow资源`() {
        assertTrue("replaceEncoderSurface 必须预热 glow 变体", body("replaceEncoderSurface").contains("prepareGlowPrograms()"))
        assertTrue(
            "预热必须同时把链的 program 编好（链自己也有两枚 program：亮部提取 + 高斯）",
            body("replaceEncoderSurface").contains("glowChain?.prepare()")
        )
        assertTrue("预热必须只在 enabled 时做", body("replaceEncoderSurface").contains("glowParams.enabled"))
        val prewarm = body("prepareGlowPrograms")
        assertTrue("成片槽的 glow=1 变体", prewarm.contains("movieProgramKey(curveOn, denoise, glow = true)"))
        assertTrue("上屏槽的 glow=1 变体", prewarm.contains("displayProgramKey(wantEffect, curveOn, denoise, glow = true)"))
        val release = body("releaseGl")
        assertTrue("releaseGl 必须收链", release.contains("glowChain?.release()"))
        assertTrue("releaseGl 必须删 1×1 黑纹理", release.contains("glowBlackTex"))
        assertTrue("releaseGl 必须清恒等 UV", release.contains("glowIdentityUv = null"))
        // 建资源点：只在 initGl（链 + 黑纹理 + 恒等 UV 一次建好）
        val init = body("initGl")
        assertTrue("initGl 必须建链", init.contains("GlowBlurChain(::link, ::drainGlError, positionBuffer)"))
        assertTrue("initGl 必须建恒等 UV", init.contains("glowIdentityTexCoords()"))
        assertTrue("initGl 必须建 1×1 黑纹理", init.contains("createGlowBlackTexture()"))
    }

    @Test
    fun `glowStatus的字段来源`() {
        val body = body("glowStatus")
        assertTrue("available 看链的结果纹理", body.contains("chain?.resultTex"))
        assertTrue("enabled 看用户参数", body.contains("glowParams.enabled"))
        assertTrue("broken 看链停用位", body.contains("isBrokenForDiagnostics"))
        assertTrue("scale 按相机帧 × 帧率", body.contains("glowScaleFor(wantFrameWidth, wantFrameHeight, fps)"))
        assertTrue("没绑编码面时帧率按 30 估", body.contains("if (encoderFps > 0) encoderFps else 30"))
        assertTrue("RT 尺寸从链读", body.contains("chain?.rtWidthPx") && body.contains("chain?.rtHeightPx"))
        assertTrue("夹取位从链读", body.contains("chain?.lastClamped"))
    }

    @Test
    fun `DirectSink在场但不可用`() {
        assertTrue(
            "DIRECT 必须回答不可用（表达式体，按整文件精确串钉）",
            direct.contains("override fun isGlowAvailable(): Boolean = false")
        )
        assertTrue("DIRECT 仍然收下参数（UI 可无条件下发）", direct.contains("override fun setGlowParams"))
        val status = directBody("glowStatus")
        assertTrue("DIRECT 的 status.available 必须恒 false", status.contains("available = false"))
        assertTrue("DIRECT 的 status.broken 必须恒 false", status.contains("broken = false"))
        assertTrue("DIRECT 没有分档", status.contains("scale = 1"))
    }

    // ------------------------------------------------------------------ 突变自证

    @Test
    fun `尺子自己能红_把链挪进drawPass或去掉预热必须报红`() {
        // 真身必须绿
        assertFalse(body("drawPass").contains("glowChain"))
        assertTrue(body("replaceEncoderSurface").contains("prepareGlowPrograms()"))

        // 突变 1：drawPass 里顺手跑一次链（= 每帧 2~4 趟模糊）
        val leaked = engine.replace(
            "private fun drawPass(encoderPass: Boolean) {",
            "private fun drawPass(encoderPass: Boolean) { glowChain?.prepare()"
        )
        assertTrue(
            "drawPass 引用 glowChain 的突变必须被这条判据看见",
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(leaked, "drawPass")).contains("glowChain")
        )

        // 突变 2：去掉编码面预热
        val noPrewarm = engine.replace("if (glowParams.enabled) prepareGlowPrograms()", "")
        assertFalse("去掉预热的突变必须报红", noPrewarm.contains("if (glowParams.enabled) prepareGlowPrograms()"))

        // 突变 3：drawGlowPass 挪到编码 pass 之后（MEND 会采到上一帧的光晕）
        // ⚠ 搜索串里不带换行符：全仓 EOL 不统一（有人存 CRLF 时带 \n 的搜索永远不命中 = 突变空转）
        val frame = KotlinSourceScan.bodyOf(engine, "drawFrame")
        val movedFrame = frame.replace("drawGlowPass(hasFrame)", "").replace(
            "        syncDisplaySlot()",
            "        syncDisplaySlot()\n        drawGlowPass(hasFrame)"
        )
        val moved = engine.replace(frame, movedFrame)
        val movedBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(moved, "drawFrame"))
        assertTrue(
            "挪到编码 pass 之后的突变必须被这条顺序判据抓到",
            movedBody.indexOf("drawGlowPass(hasFrame)") > movedBody.indexOf("drawEncoderPass()")
        )
    }

    // ------------------------------------------------------------------ B7 · UI 登记（胶囊/位掩码/持久化）

    private val pills by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")) }
    private val hudLayer by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")) }
    private val hudLayout by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayout.kt")) }
    private val persist by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/ParamsPersist.kt")) }

    /**
     * UI 登记的**源码侧**判据（真身与突变体同吃一把尺）。
     *
     * 数值/枚举那几条不在这里：它们直接断言运行时值（`CamPill.GLOW.bit` / `MASK_SCHEMA` /
     * `WotaSettings.KEY_GLOW` / `ParamsPersist.KEYS`），本身就是真值，不必再抄一份文本。
     */
    private fun coreGlowSrc(): String = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("core/Glow.kt"))

    private fun uiWiringIssues(pillsSrc: String): List<String> {
        val bad = ArrayList<String>()
        // 29 §4.4（2026-10-28）起 PillKey 末尾是 TONE：GLOW 仍在 TONE 之前
        if (!pillsSrc.contains("DENOISE, GLOW, TONE }")) bad.add("PillKey 末尾必须有 GLOW, TONE")
        if (!pillsSrc.contains("PillKey.MONITOR, PillKey.GLOW")) bad.add("GLOW 必须进录制可调白名单")
        if (!pillsSrc.contains("PillKey.GLOW to")) bad.add("pillAnchorWriters 缺 GLOW 一行（浮层会弹到屏幕原点）")
        if (!pillsSrc.contains("PillKey.GLOW -> GlowPill(")) bad.add("PillHost 缺 GLOW → GlowPill 分支")
        if (!pillsSrc.contains("glowStatusProvider()")) bad.add("GlowPill 没读状态（分档/停用/夹取三条都成摆设）")
        // 状态行的**选句**住在纯函数 glowStatusNote（core/Glow.kt，六态 JVM 全测）：
        // pills 侧只需"真的用了它"，五条文案的引用在 core 侧逐个点名
        if (!pillsSrc.contains("glowStatusNote(")) bad.add("GlowPill 状态行没走纯函数 glowStatusNote")
        val coreGlow = coreGlowSrc()
        for (res in listOf(
            "cam_glow_status_auto", "cam_glow_status_res_label",
            "cam_glow_broken", "cam_glow_clamped", "cam_glow_direct"
        )) {
            // 按词边界判：`contains` 会把 "…_clampedX" 当成"还在"（经典假绿）
            if (KotlinSourceScan.occurrences(coreGlow, res).isEmpty()) {
                bad.add("glowStatusNote 缺 $res（状态行少一种状态）")
            }
        }
        if (!hudLayer.contains("CamPill.GLOW -> PillKey.GLOW")) bad.add("HudEntry.pillKey 缺 GLOW")
        if (!hudLayer.contains("ctx.glowUsable") || !hudLayer.contains("ctx.onGlowClick")) {
            bad.add("HudEntryItem 的 GLOW 分支没接 ctx 字段")
        }
        if (!hudLayout.contains("CamPill.GLOW -> HudZone.LEFT")) bad.add("GLOW 的默认容器必须是左竖 Dock")
        if (!hudLayout.contains("HudEntry.of(CamPill.GLOW)")) bad.add("左竖 Dock 默认序必须登记 GLOW")
        if (!persist.contains("decodeGlow(")) bad.add("ParamsPersist.restore 没走 decodeGlow")
        if (!persist.contains("encodeGlow(")) bad.add("ParamsPersist.flush 没走 encodeGlow")
        return bad
    }

    @Test
    fun `UI登记_枚举位掩码与持久化键的真值`() {
        // 位掩码：GLOW 必须是新加的一位（版本 2 补位）；2026-10-28 起 schema=3（+TONE，不自动补位）
        assertEquals(16384, com.wotagei.cam.core.CamPill.GLOW.bit)
        assertEquals(3, com.wotagei.cam.core.CamPill.MASK_SCHEMA)
        assertEquals(
            com.wotagei.cam.core.CamPill.GLOW.bit,
            com.wotagei.cam.core.CamPill.ADDED_IN_SCHEMA[2]
        )
        // 胶囊可调性（录制中整组可调：Glow 是 scene layer，改了会进成片但不产生重复帧）
        assertTrue(com.wotagei.cam.ui.pillEditableDuringRecording(com.wotagei.cam.ui.PillKey.GLOW))
        // 持久化：整份参数一个复合串键，且在白名单里
        assertEquals("glow_params", com.wotagei.cam.ui.WotaSettings.KEY_GLOW)
        assertTrue(
            "glow_params 必须在 ParamsPersist.KEYS 白名单里（否则重启回默认）",
            com.wotagei.cam.ui.ParamsPersist.KEYS.contains(com.wotagei.cam.ui.WotaSettings.KEY_GLOW)
        )
        // 默认落位：左竖 Dock 第 5 组（2026-10-28 起末位是 TONE，GLOW 在其前一位）
        val leftOrder = com.wotagei.cam.ui.HudLayoutTable.defaultOrderOf(com.wotagei.cam.ui.HudZone.LEFT)
        assertEquals(
            com.wotagei.cam.ui.HudEntry.of(com.wotagei.cam.core.CamPill.GLOW),
            leftOrder[leftOrder.lastIndex - 1]
        )
        assertEquals(com.wotagei.cam.ui.HudZone.LEFT, com.wotagei.cam.ui.HudEntry.of(com.wotagei.cam.core.CamPill.GLOW).homeZone)
    }

    @Test
    fun `UI登记_源码接线判据`() {
        val issues = uiWiringIssues(pills)
        assertEquals("Glow 的 UI 接线缺口：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    // ------------------------------------------------------------------ B7 · 下发通道与状态行接线

    private val screen by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")) }

    /**
     * Glow 的**下发通道**判据（真身与突变体同吃一把尺）：
     * - 参数下发必须是**独立 effect**（键 = activeSink + glowParams），否则长键里一挂 Glow，
     *   拖滑杆每 tick 就把 effect/zebra/peaking/curve/降噪整条链重跑一遍；
     * - 独立 effect 必须在长 effect **之前**且**不经过** `glEngine ?: return`（DIRECT 下 glEngine 恒 null，
     *   走那里就到不了 DirectSink）；
     * - 长键里不许出现 glowParams、长体内不许出现 setGlowParams；
     * - 胶囊的状态源必须真的接到 sink（`glowStatusProvider` + `glowStatus()`），
     *   并且 `PillHost` 的 GlowPill 实参里带上了它（漏传 = 状态行永远空白，编译期就该挡，这里再加一道）。
     */
    private fun glowChannelIssues(screenSrc: String, pillsSrc: String): List<String> {
        val bad = ArrayList<String>()
        val flatScreen = KotlinSourceScan.flatten(screenSrc)
        val flatPills = KotlinSourceScan.flatten(pillsSrc)
        if (!flatScreen.contains("LaunchedEffect(activeSink, glowParams)")) {
            bad.add("CameraScreen 缺独立 effect LaunchedEffect(activeSink, glowParams)")
        }
        if (!flatScreen.contains(
                "LaunchedEffect(activeSink, glowParams) { (activeSink as? com.wotagei.cam.camera.GlowProvider)?.setGlowParams(glowParams) }"
            )
        ) {
            bad.add("独立 effect 体内必须是 setGlowParams（且不许先过 glEngine ?: return，否则 DIRECT 收不到）")
        }
        // ⚠ 这条针以 '(' 结尾：occurrences 的"后一个字符不能是标识符"会把调用点整批判掉，
        // 所以用普通子串计数（1 处 = 下发点唯一）
        if (screenSrc.split("setGlowParams(").size - 1 != 1) {
            bad.add("CameraScreen 只许有一处 setGlowParams（下发点必须唯一）")
        }
        if (flatScreen.contains("videoDenoise, glowParams )")) {
            bad.add("长键里不许有 glowParams（拖滑杆会每 tick 重跑整条 effect 链）")
        }
        // 长键尾部：videoDenoise（降噪）→ displayScale（上屏档，B8）→ toneParams（29 §4.3 F4，
        // 色调烘进同一条 LUT，与曲线同一条下发链；同为离散完成点不是每 tick 参数）
        if (!flatScreen.contains("arcMergeDeadband, videoDenoise, displayScale, toneParams )")) {
            bad.add("长键锚点丢失：键表尾部应是 videoDenoise, displayScale, toneParams")
        }
        if (!flatScreen.contains("glowStatusProvider = {")) {
            bad.add("CameraScreen 没给 PillHost 传 glowStatusProvider")
        }
        if (!flatScreen.contains("(activeSink as? com.wotagei.cam.camera.GlowProvider)?.glowStatus()")) {
            bad.add("glowStatusProvider 必须现读 sink 的 glowStatus（DIRECT 也实现 GlowProvider）")
        }
        if (!flatPills.contains(
                "PillKey.GLOW -> GlowPill( anchor, params, editable, gpuMode, glowStatusProvider, onClose, onPersist )"
            )
        ) {
            bad.add("PillHost 的 GlowPill 实参缺 glowStatusProvider（状态行永远空白）")
        }
        return bad
    }

    @Test
    fun `Glow下发走独立effect且胶囊状态源接到sink`() {
        val issues = glowChannelIssues(screen, pills)
        assertEquals(
            "Glow 下发/状态源接线缺口：\n" + issues.joinToString("\n"),
            emptyList<String>(),
            issues
        )
    }

    @Test
    fun `尺子能红_把Glow塞回长键或摘掉状态源必红`() {
        assertEquals("真身必须绿", emptyList<String>(), glowChannelIssues(screen, pills))
        // 突变 1：把 Glow 塞回长键（= 每 tick 重跑整条 effect 链）
        val intoLongKey = screen.replace(
            "arcMergeDeadband, videoDenoise",
            "arcMergeDeadband, videoDenoise, glowParams"
        )
        assertTrue(
            "塞回长键的突变必须报红",
            glowChannelIssues(intoLongKey, pills).any { it.contains("长键") }
        )
        // 突变 2：独立 effect 摘掉（DIRECT / 换引擎都收不到参数）
        val noEffect = screen.replace("LaunchedEffect(activeSink, glowParams) {", "LaunchedEffect(activeSink) {")
        assertTrue("摘掉独立 effect 的突变必须报红", glowChannelIssues(noEffect, pills).isNotEmpty())
        // 突变 3：状态源不传（状态行静默消失）
        val noStatus = pills.replace("gpuMode, glowStatusProvider, onClose", "gpuMode, onClose")
        assertTrue(
            "摘掉 glowStatusProvider 实参的突变必须报红",
            glowChannelIssues(screen, noStatus).any { it.contains("glowStatusProvider") }
        )
    }

    /**
     * 2026-10-28 用户口径：预设 A/B（品红→青 / 橙→绿）与两端色**整组删除**——光晕要跟随
     * 光棒自身颜色，不做变色。这条把「删干净」钉成可执行判据：残留任何一枚预设/色点符号即红，
     * 同时要求面板保留一句同色口径说明（否则用户会继续找"换色"入口）。
     */
    private fun presetRemovedIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        for (token in listOf(
            "cam_glow_preset_a", "cam_glow_preset_b",
            "cam_glow_color_a", "cam_glow_color_b",
            "GlowColorRow(", "GlowParams.PRESET_", "GlowColor.of("
        )) {
            if (src.contains(token)) bad.add("预设/色带残留：$token")
        }
        if (!src.contains("cam_glow_same_color")) bad.add("缺同色口径说明 cam_glow_same_color")
        // 第二步（2026-10-28）：删预设之后必须有**色盘自选单色**入口
        if (!src.contains("GlowColorWheel(")) bad.add("缺色盘入口 GlowColorWheel")
        if (!src.contains("cam_glow_color")) bad.add("色盘缺标签 cam_glow_color")
        return bad
    }

    @Test
    fun `预设与色带已删除_面板保留同色口径说明`() {
        val flatPills = KotlinSourceScan.flatten(pills)
        assertEquals(
            "预设/色带删除不干净：${presetRemovedIssues(flatPills)}",
            emptyList<String>(), presetRemovedIssues(flatPills)
        )
        // 尺要能红：塞回一枚预设控件符号后必须报红
        val reintroduced = flatPills.replace(
            "Note(R.string.cam_glow_same_color)",
            "Note(R.string.cam_glow_preset_a)"
        )
        assertTrue(
            "塞回预设后判据必须翻红",
            presetRemovedIssues(reintroduced).any { it.contains("cam_glow_preset_a") }
        )
    }

    @Test
    fun `尺子能红_摘掉白名单_Host分支或状态行必红`() {
        assertEquals("真身必须绿", emptyList<String>(), uiWiringIssues(pills))
        // 突变 1：白名单摘掉 GLOW（录制中变只读）
        val noWhitelist = pills.replace("PillKey.MONITOR, PillKey.GLOW", "PillKey.MONITOR")
        assertTrue("白名单突变必须报红", uiWiringIssues(noWhitelist).any { it.contains("白名单") })
        // 突变 2：PillHost 分支摘掉（点开了没内容）
        val noHost = pills.replace("PillKey.GLOW -> GlowPill(", "PillKey.GLOW -> Note(")
        assertTrue("PillHost 分支突变必须报红", uiWiringIssues(noHost).any { it.contains("PillHost") })
        // 突变 3：状态行不再走纯函数（分档/停用/夹取三条全成摆设）
        val noNote = pills.replace("glowStatusNote(", "glowStatusNoteX(")
        assertTrue(
            "摘掉 glowStatusNote 的突变必须报红",
            uiWiringIssues(noNote).any { it.contains("glowStatusNote") }
        )
        // 突变 4：锚点登记摘掉（浮层回屏幕原点）
        val noAnchor = pills.replace("PillKey.GLOW to", "PillKey.GLOWX to")
        assertTrue("锚点登记突变必须报红", uiWiringIssues(noAnchor).any { it.contains("锚点") || it.contains("pillAnchorWriters") })
    }
}
