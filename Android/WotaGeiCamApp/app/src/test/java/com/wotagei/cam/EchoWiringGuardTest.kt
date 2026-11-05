package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 残影接到引擎上的**接线守卫**（R2 高光拖影，2026-11-05；docs/research/R2 §四/§五）——
 * 与 [GlowWiringGuardTest] 同一把尺（素材层由 EchoParamsTest/EchoShaderTest 把守），
 * 这里钉的是"素材真的被接上了、而且只接在该接的地方"：
 *
 * - 链若被塞进 `drawPass`（一帧调 2~4 次）⇒ 同一张帧被累积 2~4 次，拖影凭空翻倍；
 * - 链若排在编码 pass 之后 ⇒ MEND 的 cur 渲染采到上一帧的拖影（错帧）；
 * - `setEchoParams` 若调 `requestRender` ⇒ 录制中拖滑杆就往成片塞重复帧（且重复累积）；
 * - state-only 重画若也累积 ⇒ 同一张 OES 帧多叠一层（R2 §五.1 护栏）；
 * - 方向矩阵变/模式切换不清累积面 ⇒ 拖着上一颗 sensor 的残影跑（R2 §五.2 护栏）；
 * - 参数只写 volatile 不进 program 键 ⇒ 开关永远不生效（"能点、无效果"）。
 *
 * 全部是源码级判据（GL 行为只有真机能证）。
 */
class EchoWiringGuardTest {

    private val engineSrc by lazy { KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt") }
    private val engine by lazy { KotlinSourceScan.codeOnly(engineSrc) }
    private val direct by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/DirectSink.kt")) }
    private val provider by lazy { KotlinSourceScan.mainSourceText("camera/EchoProvider.kt") }
    private val chain by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/EchoChain.kt")) }

    private fun body(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, name))

    private fun directBody(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(direct, name))

    private fun chainBody(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(chain, name))

    @Test
    fun `两个sink都实现EchoProvider`() {
        assertTrue(
            "GlRenderEngine 必须实现 EchoProvider（UI 只认这个接口）",
            Regex("class GlRenderEngine\\s*:[^{]*\\bEchoProvider\\b").containsMatchIn(engine)
        )
        assertTrue(
            "DirectSink 必须实现 EchoProvider（DIRECT 下在场但禁用）",
            Regex("class DirectSink\\s*:[^{]*\\bEchoProvider\\b").containsMatchIn(direct)
        )
        for (fn in listOf(
            "fun setEchoParams(params: EchoParams)",
            "fun isEchoAvailable(): Boolean",
            "fun echoStatus(): EchoStatus"
        )) {
            assertTrue("EchoProvider 缺 $fn", provider.contains(fn))
        }
    }

    @Test
    fun `setEchoParams绝不触发重画`() {
        val b = body("setEchoParams")
        assertTrue("锚点丢失：setEchoParams 里没截到 enabled 判定", b.contains("next.enabled"))
        assertFalse("setEchoParams 不许调 requestRender（录制中会多一帧重复帧且重复累积）", b.contains("requestRender"))
        assertFalse("setEchoParams 不许置 dirty", b.contains("dirty = true"))
        assertFalse("setEchoParams 不许直接排帧", b.contains("scheduleDraw"))
        assertTrue("enabled 翻真时必须预热 echo 变体", b.contains("prepareEchoPrograms()"))
        assertTrue("enabled 翻真时给链一次重试机会", b.contains("echoChain?.resetAfterBreak()"))
        assertTrue("enabled 翻真时预编链的 program（毫秒级编译不落帧路径）", b.contains("echoChain?.prepare()"))
        assertTrue("enabled 翻真时清零累积面（陈旧拖影不带进新开的那一段）", b.contains("echoChain?.reset()"))
    }

    @Test
    fun `链只在drawFrame里跑且排在编码pass之前`() {
        val frame = body("drawFrame")
        assertEquals("drawEchoPass 在帧循环里必须恰好一处", 1, KotlinSourceScan.occurrences(frame, "drawEchoPass(hasFrame)").size)
        val echoAt = frame.indexOf("drawEchoPass(hasFrame)")
        val encoderAt = frame.indexOf("drawEncoderPass()")
        assertTrue("锚点丢失：drawFrame 里没截到 drawEncoderPass()", encoderAt >= 0)
        assertTrue("残影链必须排在编码 pass 之前（MEND 的 cur 渲染也要采这张累积面）", echoAt in 0 until encoderAt)
        val syncAt = frame.indexOf("syncMovieSlot()")
        assertTrue("残影链必须排在 syncMovieSlot() 之后（与 Glow 同一条定版顺序）", syncAt in 0 until echoAt)
        assertFalse(
            "drawPass 不许出现 echoChain —— 它一帧被调 2~4 次，链在里面就是 2~4 次累积",
            body("drawPass").contains("echoChain")
        )
    }

    @Test
    fun `drawEchoPass的早退与每帧一次`() {
        val b = body("drawEchoPass")
        assertTrue("关着必须一次 GL 调用都不发（零代价口径）", b.contains("!echoParams.enabled"))
        assertTrue(
            "state-only 帧绝不累积（R2 §五.1：同一张 OES 帧画两次 = 拖影多叠一层）",
            b.contains("if (!hasFrame) return")
        )
        assertTrue(
            "没有编码面也没有窗口面时不跑链（没有消费者）",
            b.contains("encoderEglSurface == null && windowEglSurface == null")
        )
        assertTrue(
            "空闲预览被上屏节流的帧不跑链（编码面在位时不许省：编码器每帧都要这张累积面）",
            b.contains("encoderEglSurface == null && windowThrottled()")
        )
        assertTrue("衰减必须由倍数换算（否则倍数档位是死的）", b.contains("echoDecayOf(echoParams.multiplier)"))
        assertTrue("必须把模式交给链（否则模式切换无效）", b.contains("trailMode = trailMode"))
        assertTrue("必须把阈值交给链（否则提亮档阈值滑杆无效）", b.contains("threshold = echoParams.threshold"))
        assertFalse("链只是产出累积面，不该引发重画", b.contains("requestRender"))
    }

    @Test
    fun `方向变化与模式切换必须清累积面`() {
        // R2 §五.2：方向矩阵变过（consumeFrame 置位）或模式切换 ⇒ reset() 清累积面
        assertTrue(
            "consumeFrame 的 stChanged 分支必须置位残影作废标记",
            body("consumeFrame").contains("echoStaleSinceOrientationChange = true")
        )
        val b = body("drawEchoPass")
        assertTrue(
            "drawEchoPass 必须读作废标记并调 reset()",
            b.contains("echoStaleSinceOrientationChange") && b.contains("chain.reset()")
        )
        assertTrue(
            "模式切换也必须清（旧模式的残影语义不许带进新模式）",
            b.contains("echoAppliedTrailMode != null && echoAppliedTrailMode != trailMode")
        )
    }

    @Test
    fun `echo维度进program键且着色器按开关分版本`() {
        assertTrue("echoOn() 必须取 echoParams.enabled", engine.contains("fun echoOn(): Boolean = echoParams.enabled"))
        val link = body("linkProgram")
        assertTrue("glow+echo 双开配 TEXTURE_VS_GLOW_ECHO", link.contains("Shaders.TEXTURE_VS_GLOW_ECHO"))
        assertTrue("echo 单开配 TEXTURE_VS_ECHO", link.contains("Shaders.TEXTURE_VS_ECHO"))
        val fragment = body("fragmentFor")
        assertTrue("片元必须经 echoFragment 注入", fragment.contains("echoFragment("))
        // 2026-11-05 真机缺陷回归：echo 注入必须独立于 glow（glowOn=key.glow）。旧写法
        // `if (!key.glow) return base` 会在 glow 默认关时把 echo 一起挡掉 ⇒ 预览/成片恒无残影。
        assertTrue("echo 注入 glowOn 必须传 key.glow（glow 关也能注 echo）", fragment.contains("glowOn = key.glow"))
        assertFalse("不许回潮 glow 短路挡掉 echo", fragment.contains("if (!key.glow) return base"))
        assertTrue("两个槽的键都带 echo", body("syncMovieSlot").contains("echoOn()") && body("syncDisplaySlot").contains("echoOn()"))
    }

    @Test
    fun `drawPass绑7号单元并画完解绑`() {
        val b = body("drawPass")
        assertTrue("必须绑 ECHO_SRC_UNIT", b.contains("GL_TEXTURE0 + ECHO_SRC_UNIT"))
        assertTrue("未就绪兜底 1×1 黑纹理（两种模式下都是恒等）", b.contains("echoFrameTex.takeIf { it != 0 } ?: glowBlackTex"))
        assertTrue("必须传 uEchoSrc", b.contains("p.uEchoSrc"))
        assertTrue("必须传 uEchoMode", b.contains("p.uEchoMode"))
        assertTrue("必须传 uEchoGain", b.contains("p.uEchoGain"))
        val drawAt = b.indexOf("glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)")
        // 解绑在 drawArrays **之后**的那次出现（第一次出现是绑定，别找错位置）
        val unbindAt = b.indexOf("GL_TEXTURE0 + ECHO_SRC_UNIT", drawAt)
        assertTrue("drawPass 锚点丢失：drawArrays", drawAt >= 0)
        assertTrue("解绑必须在 drawArrays 之后", unbindAt > drawAt)
    }

    @Test
    fun `预热与释放都登记了残影资源`() {
        val prewarm = body("prepareEchoPrograms")
        assertTrue("成片槽的 echo=1 变体", prewarm.contains("movieProgramKey(curveOn, denoise, glowOn(), echo = true)"))
        assertTrue("上屏槽的 echo=1 变体", prewarm.contains("displayProgramKey(wantEffect, curveOn, denoise, glowOn(), echo = true)"))
        val release = body("releaseGl")
        assertTrue("releaseGl 必须收链", release.contains("echoChain?.release()"))
        assertTrue("releaseGl 必须清帧纹理/作废标记/模式缓存", release.contains("echoFrameTex = 0") &&
            release.contains("echoStaleSinceOrientationChange = false") && release.contains("echoAppliedTrailMode = null"))
        val init = body("initGl")
        assertTrue("initGl 必须建链", init.contains("EchoChain(::link, ::drainGlError, positionBuffer)"))
    }

    @Test
    fun `链写读不同体且失败退化成无拖影`() {
        // 乒乓：本帧画 acc[1-idx]、采 acc[idx]（同一张纹理自读自写 = feedback loop）
        assertTrue("写读不同体（乒乓）", chainBody("draw").contains("accFbo[1 - accIdx]") && chainBody("draw").contains("accTex[accIdx]"))
        assertTrue("画完交换", chainBody("draw").contains("accIdx = 1 - accIdx"))
        assertTrue("报错帧撤掉结果（比糊错内容诚实）", chainBody("draw").contains("resultTex = 0"))
        assertTrue("连续报错停用整条链", chain.contains("ECHO_MAX_ERROR_STREAK"))
        // reset 清的是黑（两模式下黑 = 恒等），且还原进来时的 FBO（MEND 契约）
        val reset = chainBody("reset")
        assertTrue("reset 必须清成黑", reset.contains("glClearColor(0f, 0f, 0f, 1f)"))
        assertTrue("reset 必须还原 FBO", reset.contains("glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboBackup[0])"))
    }

    @Test
    fun `echoStatus的字段来源`() {
        val b = body("echoStatus")
        assertTrue("available 看链的结果纹理", b.contains("chain?.resultTex"))
        assertTrue("enabled 看用户参数", b.contains("echoParams.enabled"))
        assertTrue("broken 看链停用位", b.contains("isBrokenForDiagnostics"))
    }

    @Test
    fun `DirectSink在场但不可用`() {
        assertTrue(
            "DIRECT 必须回答不可用（表达式体，按整文件精确串钉）",
            direct.contains("override fun isEchoAvailable(): Boolean = false")
        )
        assertTrue("DIRECT 仍然收下参数（UI 可无条件下发）", direct.contains("override fun setEchoParams"))
        val status = directBody("echoStatus")
        assertTrue("DIRECT 的 status.available 必须恒 false", status.contains("available = false"))
        assertTrue("DIRECT 的 status.broken 必须恒 false", status.contains("broken = false"))
    }

    @Test
    fun `UI接线_胶囊位显隐与就近面板`() {
        val pills = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt"))
        val screen = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
        val hud = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt"))
        val layout = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayout.kt"))
        assertTrue("PillKey 必须有 ECHO", pills.contains("enum class PillKey") && Regex("enum class PillKey[^}]*\\bECHO\\b").containsMatchIn(pills))
        assertTrue("残影进录制中可调白名单（scene layer，同 Glow/TONE 先例）", Regex("RECORDING_EDITABLE_PILLS[^)]*\\bPillKey.ECHO\\b").containsMatchIn(pills))
        assertTrue("锚点登记表必须有 ECHO 行", pills.contains("PillKey.ECHO to"))
        assertTrue("PillHost 必须分派 EchoPill", pills.contains("PillKey.ECHO -> EchoPill("))
        assertTrue("录制页点按开就近面板", screen.contains("pop = PillKey.ECHO"))
        assertTrue("HudEntryItem 必须有 ECHO 分支", Regex("CamPill.ECHO -> TierChip\\(").containsMatchIn(hud))
        assertTrue("HudEntry.pillKey 必须有 ECHO 映射", Regex("CamPill.ECHO -> PillKey.ECHO").containsMatchIn(hud))
        assertTrue("默认落底栏（homeZone）", Regex("CamPill.ECHO -> HudZone.BOTTOM").containsMatchIn(layout))
        assertTrue("默认顺序表里 ECHO 排降噪左侧", Regex("HudZone.BOTTOM -> listOf\\(\\s*HudEntry.of\\(CamPill.ECHO\\), HudEntry.of\\(CamPill.DENOISE\\)").containsMatchIn(layout))
        assertTrue("参数下发必须走 EchoProvider（独立 effect，DIRECT 也收）", screen.contains("as? com.wotagei.cam.camera.EchoProvider)?.setEchoParams(echoParams)"))
    }

    // ------------------------------------------------------------------ 突变自证

    @Test
    fun `尺子自己能红_去掉stateOnly护栏或写死开关必红`() {
        // 突变 1：state-only 帧也累积（R2 §五.1 护栏被删）
        val noGuard = engine.replace("    private fun drawEchoPass(hasFrame: Boolean) {\n        if (!echoParams.enabled) return\n        if (!hasFrame) return", "    private fun drawEchoPass(hasFrame: Boolean) {\n        if (!echoParams.enabled) return")
        assertTrue(
            "删掉 hasFrame 护栏的突变必须被这条判据看见",
            !KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(noGuard, "drawEchoPass")).contains("if (!hasFrame) return")
        )
        // 突变 2：echoOn 写死 false（开关不进 program 键 = 能点无效果）
        val stuckOff = engine.replace("fun echoOn(): Boolean = echoParams.enabled", "fun echoOn(): Boolean = false")
        assertFalse("写死 echoOn 的突变必须被抓", stuckOff.contains("fun echoOn(): Boolean = echoParams.enabled"))
    }
}
