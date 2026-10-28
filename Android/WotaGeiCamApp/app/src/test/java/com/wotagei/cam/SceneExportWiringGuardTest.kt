package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「内置播放器内后渲染导出」的**接线与红线守卫**（2026-10-28 用户需求）。
 *
 * 用户口径拆成三条硬约束，逐条钉在源码上：
 * 1. **只做 Glow + 画面降噪**：后渲染链读的是 `KEY_GLOW` 与 `videoDenoise`，没有第三种效果来源；
 * 2. **绝不做 RGB 曲线**：用户原话"不需要在内置播放器内做 RGB 曲线后渲染"——曲线是录制期
 *    scene layer，`GlRenderEngine.setCurve` 已把它烘进成片像素，离线再套一次就是双倍。
 *    所以整条离线链**不许出现任何曲线符号**（含 `KEY_CURVE_STACK` / `uCurve` / `setCurve`）；
 * 3. **无效果时不给用户"点了没反应"**：后渲染 chip 的 enabled 必须来自 `effects.active`。
 *
 * 另钉两条与 remux 路（[com.wotagei.cam.player.ClipExporter]）的**口径同源**，这两处一旦
 * 各写一份就会出"选了内录轨但没按偏移对齐"或"某类失败被谎报成重启可恢复"：
 * - 音轨偏移/丢弃/单调桥必须复用 `TrackRule.shiftUsFor`、`ClipRule.keepSample`、`monotonicPts`；
 * - `Reason.COMMIT`（成片在盘、只是没收录）必须与 `Reason.MUXER`（没写成、pending 已删）分开。
 */
class SceneExportWiringGuardTest {

    private val playerSrc by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt")) }
    private val runnerSrc by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/SceneExportRunner.kt")) }
    private val glSrc by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/SceneExportGl.kt")) }
    private val planSrc by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/SceneExportPlan.kt")) }
    private val shadersRaw by lazy { KotlinSourceScan.mainSourceText("camera/Shaders.kt") }

    /** 取 `val NAME = """..."""` 的着色器源（着色器在字符串字面量里，不能吃 codeOnly） */
    private fun shader(name: String): String {
        val at = shadersRaw.indexOf("val $name =")
        assertTrue("着色器 $name 丢失（守卫失去输入，不许算通过）", at >= 0)
        val open = shadersRaw.indexOf("\"\"\"", at)
        assertTrue("$name 不是原始字符串字面量", open > at)
        val close = shadersRaw.indexOf("\"\"\"", open + 3)
        assertTrue("$name 的原始字符串没有收尾", close > open)
        return shadersRaw.substring(open + 3, close)
    }

    private fun stringsXml(): File {
        var dir: File? = KotlinSourceScan.mainSourceFile("player/PlayerScreen.kt").parentFile
        while (dir != null && dir.name != "java") dir = dir.parentFile
        assertTrue("定位不到 src/main/java 目录（守卫失去输入）", dir != null)
        return File(File(dir!!.parentFile, "res"), "values/strings_player.xml")
    }

    // ---------------------------------------------------------------- 红线：不做曲线

    /** 曲线符号尺子：三份离线链源码共用（突变自证也用同一把尺，避免"两把尺各说各话"） */
    private fun curveHits(src: String): List<String> =
        listOf("CURVE", "Curve", "curve", "uCurve", "LUT", "lut").filter { src.contains(it) }

    @Test
    fun `后渲染链里绝不许出现曲线`() {
        for ((name, src) in listOf(
            "SceneExportPlan.kt" to planSrc,
            "SceneExportRunner.kt" to runnerSrc,
            "SceneExportGl.kt" to glSrc
        )) {
            // codeOnly 已剥掉注释：命中就是真代码，不是解释性文字
            val hits = curveHits(src)
            assertTrue(
                "$name 出现 $hits：曲线是录制期 scene layer（GlRenderEngine.setCurve 已烘进成片），" +
                    "用户明确要求后渲染不做曲线——再套一次是双倍",
                hits.isEmpty()
            )
        }
        // 反证：曲线能力本身确实存在（上面的"不含"不是"根本没收这个功能"的空转）
        assertTrue("Shaders.CURVE_FS 应仍在（曲线走的是录制链，不是离线链）", shadersRaw.contains("val CURVE_FS"))
    }

    @Test
    fun `突变自证_把曲线塞回离线链必报红`() {
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", curveHits(planSrc).isEmpty())
        val mutant = planSrc + "\nprivate fun hack(tex: Int) = Shaders.CURVE_FS + tex\n"
        assertTrue("塞回曲线符号后同一把尺必须红", curveHits(mutant).isNotEmpty())
    }

    // ---------------------------------------------------------------- 参数来源

    @Test
    fun `效果参数只来自光晕落盘键与降噪档`() {
        assertTrue(
            "后渲染的效果必须现读 KEY_GLOW 落盘串（用户赎回设置里刚改的光晕）",
            playerSrc.contains("prefs.getString(WotaSettings.KEY_GLOW, null)")
        )
        assertTrue(
            "降噪档必须走 WotaSettings.videoDenoise（与设置页同一枚读取器）",
            playerSrc.contains("WotaSettings.videoDenoise(prefs)")
        )
        // 起跑那一刻现读一份（导出期间改设置不影响本轮）
        assertTrue("导出起跑时必须现读一份效果快照", playerSrc.contains("val effects = sceneExportEffects("))
        assertTrue(
            "决策层必须把 enabled=false 的光晕折成 null（关着就不该付 GPU 代价）",
            planSrc.contains("if (it.enabled) it else null")
        )
    }

    // ---------------------------------------------------------------- 派发与 UI

    @Test
    fun `lossless与scene两条导出路径都被接上`() {
        assertTrue("必须真的实例化 SceneExportRunner", playerSrc.contains("SceneExportRunner(app)"))
        assertTrue("后渲染分支必须真的调用 runner", playerSrc.contains("sceneRunner.export("))
        assertTrue("无损分支必须保留原路径", playerSrc.contains("clipExporter.export("))
        assertTrue("两种模式必须由同一个 mode 变量分流", playerSrc.contains("if (mode == 1)"))
    }

    @Test
    fun `后渲染chip无效果时置灰而不是偷偷降级`() {
        val popup = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(playerSrc, "ClipExportPopup")
        )
        assertTrue("弹层必须有导出方式的两个 chip", popup.contains("player_clip_mode_lossless"))
        assertTrue(popup.contains("player_clip_mode_scene"))
        assertTrue(
            "后渲染 chip 的 enabled 必须来自 effects.active（无效果时点它=点了没反应）",
            popup.contains("enabled = sceneEffects.active")
        )
        assertTrue("弹层必须能选模式", popup.contains("onPickMode("))
        // 文案必须真的登记（丢了编译会红，但显式锁住防资源被挪走后守卫空转）
        val xml = stringsXml().readText(Charsets.UTF_8)
        for (name in listOf(
            "player_clip_mode",
            "player_clip_mode_lossless",
            "player_clip_mode_scene",
            "player_clip_scene_lossless_note",
            "player_clip_scene_none",
            "player_clip_scene_curve_note"
        )) {
            assertTrue("strings_player.xml 必须登记 $name", xml.contains("name=\"$name\""))
        }
    }

    // ---------------------------------------------------------------- 与 remux 路同源

    @Test
    fun `音轨偏移与丢弃判据必须复用同源纯函数`() {
        assertTrue(
            "内录轨偏移必须走 TrackRule.shiftUsFor（与无损剪辑/选轨导出同一枚值）",
            runnerSrc.contains("TrackRule.shiftUsFor(track, offsetMs)")
        )
        assertTrue(
            "负 PTS 丢弃判据必须走 ClipRule.keepSample（导出侧单一真源）",
            runnerSrc.contains("ClipRule.keepSample(")
        )
        assertTrue(
            "写出前必须过逐轨单调桥 monotonicPts（同轨倒退会让 MPEG4Writer 抛异常）",
            runnerSrc.contains("monotonicPts(")
        )
        assertTrue(
            "音轨必须先预扫是否可写（零样本轨会让 muxer.stop 抛异常并删掉整份输出）",
            runnerSrc.contains("audioTrackWritable(")
        )
    }

    @Test
    fun `音轨拷贝必须先取消全部轨选择`() {
        // 真机抓到的缺陷（r10）：MediaExtractor 的选择是**累加**的，编码循环里视频轨还选着，
        // copyAudio 只 select 音轨 ⇒ 读到音视频交错样本 ⇒ 整帧视频被当 AAC 样本写进音轨
        //（成片音轨码率 5.2 Mbps、AAC 帧间夹着 20KB 巨块，播放器报 channel element 未分配）。
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(runnerSrc, "copyAudio"))
        val unselect = body.indexOf("unselectTrack(i)")
        val select = body.indexOf("selectTrack(audioIdx)")
        assertTrue("copyAudio 必须先 unselectTrack 全部轨（否则音轨混进视频样本）", unselect >= 0)
        assertTrue("copyAudio 必须选中音轨", select >= 0)
        assertTrue("取消选择必须早于 selectTrack(audioIdx)", unselect < select)
    }

    @Test
    fun `COMMIT与MUXER必须分开报`() {
        assertTrue("Reason 必须有 COMMIT（成片在盘但没收录）", runnerSrc.contains("COMMIT"))
        // export() 是表达式体（`= withContext {}`），bodyOf 定位不到；下面几条判据在全文里本就唯一
        assertTrue(
            "commit 两连败必须报 COMMIT（UI 才能说\"成片已保留，重启后自动恢复\"）",
            runnerSrc.contains("Failed(Reason.COMMIT)")
        )
        assertTrue(
            "muxer 没写成时必须报 MUXER（pending 行已删，不许谎报可恢复）",
            runnerSrc.contains("fail(pendingUri, Reason.MUXER)")
        )
        // PlayerScreen 的映射：只有 COMMIT 才落到 ClipError.COMMIT
        assertTrue(
            "PlayerScreen 必须把 Reason.COMMIT 映射成 ClipError.COMMIT",
            playerSrc.contains("SceneExportRunner.Reason.COMMIT -> ClipError.COMMIT")
        )
        assertFalse(
            "不许把 MUXER 映射成 COMMIT（pending 已删，谎报会让用户白等重启）",
            playerSrc.contains("SceneExportRunner.Reason.MUXER -> ClipError.COMMIT")
        )
    }

    // ---------------------------------------------------------------- GL 链与着色器

    @Test
    fun `渲染顺序是降噪后光晕`() {
        val render = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(glSrc, "render"))
        val den = render.indexOf("bilatProgram")
        val bright = render.indexOf("brightProgram")
        val blur = render.indexOf("blurProgram")
        val comp = render.indexOf("compProgram")
        assertTrue("render() 必须四趟齐全（降噪/亮部/模糊/合成）", den >= 0 && bright >= 0 && blur >= 0 && comp >= 0)
        assertTrue("降噪必须先于 Glow（scene 顺序）", den < bright)
        assertTrue("亮部提取先于模糊", bright < blur)
        assertTrue("合成必须是最后一趟（它写编码器面）", comp > blur)
        // 编码器面：合成前必须切到编码器 EGL 面，并逐帧打戳（坏戳不可自愈）
        assertTrue("必须切到编码器面", render.contains("makeCurrent(encoderSurface)"))
        assertTrue("必须逐帧打呈现时间戳", render.contains("eglPresentationTimeANDROID"))
        assertTrue("必须 swap 才真正交付这一帧", render.contains("eglSwapBuffers"))
    }

    @Test
    fun `四条后渲染着色器与实时同源`() {
        val bilat = shader("SCENE_BILATERAL_2D_FS")
        assertTrue("双边必须 8 邻域", bilat.contains("o[7]"))
        assertTrue("双边必须用亮度差权重", bilat.contains("inv2s2"))

        val bright = shader("SCENE_BRIGHT_FS")
        assertTrue("亮部提取必须把掩码乘回**原色**（只让 tint 负责换色）", bright.contains("c * w"))
        assertTrue("亮度取 Rec601 三通道加权（与 GLOW_COPY_FS 同口径）", bright.contains("0.299, 0.587, 0.114"))

        val blur = shader("SCENE_BLUR_FS")
        // 9 tap 权重与实时链 GlowBlurChain 的 gaussianSource 逐字同源
        for (w in listOf("0.227027", "0.1945946", "0.1216216", "0.0540540", "0.0162160")) {
            assertTrue("模糊权重 $w 必须与实时链同源", blur.contains(w))
        }

        val comp = shader("SCENE_COMPOSITE_FS")
        assertTrue(
            "合成公式必须与 applyGlow 逐项同源：c + glow * tint * (gain + add) * (blend * n)",
            comp.contains("c + g * uTint * (uGain + uAdd) * (uBlend * n)")
        )
        assertTrue("合成必须有频率噪声（freq=0 时退化成乘 1）", comp.contains("step(0.0001, uFreq)"))
        assertTrue("合成必须取 tint uniform", comp.contains("uTint"))

        // 实时链的 applyGlow 也要真的带 tint（2026-10-28 同色光晕口径）
        val decl = shader("GLOW_DECL")
        assertTrue(
            "applyGlow 必须是 c + glow * uGlowTint * (gain + add) * (blend * n)",
            decl.contains("c + glow * uGlowTint * (uGlowGain + uGlowAdd) * (uGlowBlend * n)")
        )
        assertFalse("旧的 A/B 色带入口必须彻底消失", decl.contains("uGlowColorA") || decl.contains("uGlowColorB"))
    }

    @Test
    fun `扩散半径口径与实时链同源`() {
        // uGlowWidth 以 RT **短边** 为单位 ⇒ offset = width * vec2(min(1,h/w), min(1,w/h))
        assertTrue(
            "离线链的 dx/dy 必须按短边各向同性折算（与 fillGlowBlurOffset 同口径）",
            glSrc.contains("glow.width * minOf(1f, heightPx.toFloat() / widthPx)") &&
                glSrc.contains("glow.width * minOf(1f, widthPx.toFloat() / heightPx)")
        )
        assertEquals(
            "四趟 program + 两条直通（OES/2D）都要在 buildPrograms 里建",
            6,
            listOf("oesProgram", "pass2dProgram", "bilatProgram", "brightProgram", "blurProgram", "compProgram")
                .count { glSrc.contains("$it = link(") }
        )
    }

    @Test
    fun `编码器面用recordable配置`() {
        // 绑 MediaCodec 输入面必须声明 EGL_RECORDABLE_ANDROID（缺它部分驱动 eglCreateWindowSurface 返回 0x3003）
        assertTrue("EGL 配置必须带 recordable 属性 0x3142", glSrc.contains("0x3142"))
        assertTrue("必须优先建 ES3 上下文并回退 ES2", glSrc.contains("EGL_CONTEXT_CLIENT_VERSION, 3"))
    }

    @Test
    fun `pending两段式与冷启救援口径不变`() {
        assertTrue("必须写 IS_PENDING=1 再清 0（相册里不该看到半成品）", runnerSrc.contains("IS_PENDING"))
        assertTrue("封轨后（sealed）失败不许删行（删行=删文件=丢成片）", runnerSrc.contains("if (!sealed)"))
    }
}
