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
 * 3. **无效果时不给用户"点了没反应"**：后渲染 chip **恒可选**（冷态也要能进面板内的效果配置，
 *    否则永远开不了效果）；无效果时由**导出钮**按 `clipExportReady` 置灰拦住重编码，且置灰
 *    必须带 `valueColor`（`enabled` 本身不新造视觉，见 Widgets.kt 的注）。
 *
 * 另钉两条与 remux 路（[com.wotagei.cam.player.ClipExporter]）的**口径同源**，这两处一旦
 * 各写一份就会出"选了内录轨但没按偏移对齐"或"某类失败被谎报成重启可恢复"：
 * - 音轨偏移/丢弃/单调桥必须复用 `TrackRule.shiftUsFor`、`ClipRule.keepSample`、`monotonicPts`；
 * - `Reason.COMMIT`（成片在盘、只是没收录）必须与 `Reason.MUXER`（没写成、pending 已删）分开。
 */
class SceneExportWiringGuardTest {

    private val playerSrc by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt")) }
    // 2026-10-30 全面审查 🟡-1：导出任务上收到 ExportHost（Activity 作用域）。守卫判据语义不变
    // （两条路径都要真接上、COMMIT 与 MUXER 必须分开报），只是派发/映射的锚点跟着代码搬家。
    private val hostSrc by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/ExportHost.kt")) }
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
    fun `效果参数来自光晕残影落盘键与降噪档`() {
        assertTrue(
            "后渲染的效果必须现读 KEY_GLOW 落盘串（用户赎回设置里刚改的光晕）",
            playerSrc.contains("prefs.getString(WotaSettings.KEY_GLOW, null)")
        )
        assertTrue(
            "残影必须现读 KEY_ECHO 落盘串（R2 第三路，与光晕同口径）",
            playerSrc.contains("prefs.getString(WotaSettings.KEY_ECHO, null)")
        )
        assertTrue(
            "降噪档必须走 WotaSettings.videoDenoise（与设置页同一枚读取器）",
            playerSrc.contains("WotaSettings.videoDenoise(prefs)")
        )
        // 2026-10-29：导出弹层内可直接配置（光晕/降噪/残影），起跑那一刻取面板当前配置的快照
        //（导出期间再改不影响这一轮）；2026-11-05 残影加入快照（R2 第三路）
        assertTrue(
            "导出起跑时必须取面板当前配置的快照（sceneExportEffectsOf 含残影）",
            playerSrc.contains("effects = sceneExportEffectsOf(clipGlow, clipDenoise, clipEcho)")
        )
        assertTrue(
            "决策层必须把 enabled=false 的效果折成 null（关着就不该付 GPU 代价）",
            planSrc.contains("if (it.enabled) it else null")
        )
    }

    @Test
    fun `残影是第三路效果且进active判据`() {
        // 决策层：echo 维存在、active 含 echo、工厂函数读 echo
        assertTrue("SceneExportEffects 必须有 echo 维", planSrc.contains("val echo: EchoParams?"))
        assertTrue("active 必须含 echo（只开残影也能后渲染）", planSrc.contains("glow != null || denoise || echo != null"))
        assertTrue("sceneExportEffectsOf 必须收 echo 形参", planSrc.contains("denoiseOn: Boolean, echo: EchoParams"))
        // GL：echo 开时建累积面/合成趟，读原始帧累积，合成包在 glow 外层
        assertTrue("GL 必须按 effects.echo 建乒乓累积面", glSrc.contains("if (effects.echo != null)"))
        assertTrue("累积趟必须读原始 2D 帧 curTex（与实时链读原始 OES 同口径）", glSrc.contains("GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curTex)"))
        assertTrue("衰减必须走 echoDecayOf（与实时链同一换算）", glSrc.contains("echoDecayOf(echo.multiplier)"))
        // 拖尾颜色补丁（docs/plan/35）：离线两趟的 uniform 取位置与设值必须真的接上，
        // 推导与实时链同一组 core 纯函数（不许离线自造一套口径）。锚点用变量名与调用名——
        // codeOnly 会遮蔽字符串字面量内容，`"uEchoTint"` 这种字面量锚在 glSrc 上永远落空。
        for (loc in listOf(
            "echoAccUTint = GLES20.glGetUniformLocation",
            "echoAccUMask = GLES20.glGetUniformLocation",
            "echoAccUTol = GLES20.glGetUniformLocation",
            "echoCompUEchoTintMode = GLES20.glGetUniformLocation",
            "echoCompUEchoTint = GLES20.glGetUniformLocation"
        )) {
            assertTrue("离线两趟必须取补丁 uniform 位置（$loc）", glSrc.contains(loc))
        }
        assertTrue("累积趟必须设 uEchoMask（择/除门）", glSrc.contains("glUniform1f(echoAccUMask"))
        assertTrue("累积趟必须设 uEchoTint（补丁色）", glSrc.contains("glUniform3f(echoAccUTint"))
        assertTrue("累积趟必须设 uEchoTol（容差）", glSrc.contains("glUniform1f(echoAccUTol"))
        assertTrue("累积趟门模式必须走 echoTrailMaskOf", glSrc.contains("echoTrailMaskOf(echo)"))
        assertTrue("累积趟容差必须走 echoTrailTolOf", glSrc.contains("echoTrailTolOf(echo)"))
        assertTrue("合成趟必须设 uEchoTintMode（加/减）", glSrc.contains("glUniform1f(echoCompUEchoTintMode"))
        assertTrue("合成趟必须设 uEchoTint（补丁色）", glSrc.contains("glUniform3f(echoCompUEchoTint"))
        assertTrue("合成趟染色模式必须走 echoTrailTintOf", glSrc.contains("echoTrailTintOf(echo)"))
        assertTrue("合成必须用残影合成趟 echoCompProgram", glSrc.contains("GLES20.glUseProgram(echoCompProgram)"))
        // 渲染顺序：残影累积在 Glow 模糊之后，残影合成是写编码器面的最后一趟
        val render = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(glSrc, "render"))
        val blur = render.indexOf("blurProgram")
        val acc = render.indexOf("echoAccProgram")
        val echoComp = render.indexOf("GLES20.glUseProgram(echoCompProgram)")
        assertTrue("render 必须含残影累积趟与合成趟", acc >= 0 && echoComp >= 0)
        assertTrue("残影累积必须在 Glow 模糊之后", blur < acc)
        assertTrue("残影合成必须在累积之后（它写编码器面）", acc < echoComp)
        // glow 缺席时 uGlowSrc 绑黑纹理（光晕贡献为 0），不许漏兜底
        assertTrue("echo 开但 glow 关时必须绑黑纹理兜底", glSrc.contains("if (glow != null) glowATex else blackTex"))
        // 释放登记：乒乓面 + 黑纹理 + 两枚 program 都要删
        assertTrue("releaseGl 必须删残影 program", glSrc.contains("echoAccProgram = 0; echoCompProgram = 0"))
        assertTrue("releaseGl 必须删黑纹理", glSrc.contains("blackTex = 0"))
    }

    @Test
    fun `残影着色器与实时链同源`() {
        val acc = shader("SCENE_ECHO_ACCUM_FS")
        // 2D 输入（离线帧已拷成 2D），双模式与实时 ECHO_ACCUM_FS 逐项同源
        assertTrue("累积趟必须用 sampler2D（离线帧是 2D，不是 OES）", acc.contains("uniform sampler2D uSrc;"))
        assertTrue("prev 必须先乘衰减", acc.contains("texture2D(uEchoPrev, vUv).rgb * uEchoDecay"))
        assertTrue("保持亮度档必须是 max(c, prev)", acc.contains("max(c, prev)"))
        assertTrue("提亮档必须做高光阈值切分", acc.contains("smoothstep("))
        assertTrue("亮度按 Rec601 加权", acc.contains("0.299, 0.587, 0.114"))
        // r06 筛色回归：离线不能再保留会消灭彩色的旧色门；与实时链逐句一致。
        for (u in listOf("uniform vec3 uEchoTint;", "uniform float uEchoMask;", "uniform float uEchoTol;")) {
            assertTrue("累积趟缺补丁 uniform $u", acc.contains(u))
        }
        assertEquals(
            "离线筛色与实时同源（实际像素由 GLES 脚本验证）",
            shader("ECHO_ACCUM_FS").substringAfter("float luma =").substringBefore("gl_FragColor"),
            acc.substringAfter("float luma =").substringBefore("gl_FragColor")
        )
        assertTrue("择除必须按峰值判亮", acc.contains("t = smoothstep(uEchoThreshold - 0.05, uEchoThreshold + 0.05, peak);"))
        assertTrue("白灰与彩色必须独立分类", acc.contains("if (sat < 0.05 || tintSat < 0.05)"))
        assertTrue("累积趟缺择/除分派", acc.contains("t *= (uEchoMask < 1.5) ? m : 1.0 - m;"))

        val comp = shader("SCENE_COMPOSITE_ECHO_FS")
        // 合成 = applyEcho(base + glow, echo)：残影包在 Glow 外层，与实时 echoFragment 同序
        assertTrue("必须先合成 Glow 再叠残影", comp.contains("vec3 withGlow = c + g * uTint * (uGain + uAdd) * (uBlend * n)"))
        assertTrue("残影必须作用在 withGlow 上", comp.contains("applyEcho(withGlow, e)"))
        assertTrue("保持亮度档 max", comp.contains("return max(c, e);"))
        assertTrue("提亮档加法叠回钳 1", comp.contains("return min(c + e * uEchoGain, vec3(1.0));"))
        // 拖尾颜色补丁·加/减（docs/plan/35 U2/U3/E3）：算子与位置同实时 ECHO_DECL（KB 早退后、TRAIL 终写前）
        assertTrue("合成趟缺染色模式 uniform", comp.contains("uniform float uEchoTintMode;"))
        assertTrue("合成趟缺补丁色 uniform", comp.contains("uniform vec3 uEchoTint;"))
        assertTrue("合成趟缺加/减算子", comp.contains("e *= (uEchoTintMode < 1.5) ? uEchoTint : (vec3(1.0) - uEchoTint);"))
        assertTrue(
            "合成趟的染色必须位于 KB 分支之后、TRAIL 终写之前",
            comp.indexOf("return max(c, e);") < comp.indexOf("e *= (uEchoTintMode < 1.5)") &&
                comp.indexOf("e *= (uEchoTintMode < 1.5)") < comp.indexOf("return min(c + e * uEchoGain, vec3(1.0));")
        )
        // 实时链的 applyEcho 也要真的同源（反证：不是离线自造一套）
        val decl = shader("ECHO_DECL")
        assertTrue("实时 applyEcho 保持亮度档同源", decl.contains("return max(c, e);"))
        assertTrue("实时 applyEcho 提亮档同源", decl.contains("return min(c + e * uEchoGain, vec3(1.0));"))
        assertTrue("实时 applyEcho 加/减算子同源", decl.contains("e *= (uEchoTintMode < 1.5) ? uEchoTint : (vec3(1.0) - uEchoTint);"))
    }

    // ---------------------------------------------------------------- 派发与 UI

    @Test
    fun `lossless与scene两条导出路径都被接上`() {
        assertTrue("必须在宿主里实例化 SceneExportRunner", hostSrc.contains("SceneExportRunner(ctx)"))
        assertTrue("后渲染分支必须真的调用 runner", hostSrc.contains("sceneRunner.export("))
        assertTrue("无损分支必须保留原路径", hostSrc.contains("clipExporter.export("))
        // 两种模式由同一个 mode 快照分流：页面算 clipMode==1，宿主按 postRender 分岔
        assertTrue(
            "两种模式必须由同一个 mode 变量分流（页面快照 → 宿主分岔）",
            playerSrc.contains("postRender = clipMode == 1") && hostSrc.contains("if (postRender)")
        )
    }

    @Test
    fun `无效果时后渲染仍可进配置_导出钮拦住且看得见`() {
        val popup = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(playerSrc, "ClipExportPopup")
        )
        assertTrue("弹层必须有导出方式的两个 chip", popup.contains("player_clip_mode_lossless"))
        assertTrue(popup.contains("player_clip_mode_scene"))
        // 2026-11-07 真机（内测 r05）「后渲染点了没反应」的两个成因，逐条钉死不许回退：
        // ① WotaChip 的 enabled 不新造视觉（Widgets.kt 的注）——按 effects.active 置灰后
        //    chip 看起来与可点态一样；
        // ② 效果配置区只在 mode==1 出现——冷态（光晕/降噪/残影全关）进不了 mode 1，
        //    也就永远打不开配置区里的效果开关（死锁）。
        assertFalse(
            "后渲染 chip 不许再按 effects.active 置灰（冷态就进不了面板内的效果配置）",
            popup.contains("enabled = sceneEffects.active")
        )
        assertTrue("弹层必须能选模式（后渲染恒可选）", popup.contains("onPickMode("))
        // 拦住重编码：无效果时导出钮不可点，且必须能看出来（enabled 只关点击、不新造视觉）
        assertTrue(
            "导出钮必须按 clipExportReady(mode==1, effects) 置灰",
            popup.contains("val exportReady = clipExportReady(mode == 1, sceneEffects)")
        )
        assertTrue(popup.contains("enabled = exportReady"))
        assertTrue(
            "置灰必须带 valueColor（enabled 本身不新造视觉）",
            popup.contains("valueColor = if (exportReady) null else WotaTextDim")
        )
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
        // 导出宿主的映射：只有 COMMIT 才落到 ClipError.COMMIT
        assertTrue(
            "宿主必须把 Reason.COMMIT 映射成 ClipError.COMMIT",
            hostSrc.contains("SceneExportRunner.Reason.COMMIT -> ClipError.COMMIT")
        )
        assertFalse(
            "不许把 MUXER 映射成 COMMIT（pending 已删，谎报会让用户白等重启）",
            hostSrc.contains("SceneExportRunner.Reason.MUXER -> ClipError.COMMIT")
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
        // 2026-10-29：颜色两模式——mix(c, uTint, uColorOnly)：0 = 乘回原色，1 = 仅色盘色
        assertTrue(
            "亮部颜色必须按模式二选一（像素自身颜色 / 仅色盘颜色，与实时链同口径）",
            bright.contains("mix(c, uTint, uColorOnly)")
        )
        assertTrue("亮部保留、暗部压零：颜色 × w", bright.contains("vec4(pick * w, 1.0)"))
        assertTrue(
            "软边必须朝 1.0 收敛（阈值 100% = 只给最亮像素加光晕，与 GLOW_COPY_FS 同口径）",
            bright.contains("min(uSoft, max(1.0 - uThreshold, 0.0) + 0.0001)")
        )
        assertTrue("亮度取 Rec601 三通道加权（与 GLOW_COPY_FS 同口径）", bright.contains("0.299, 0.587, 0.114"))
        // 🟡-6 角点防御（2026-10-29，docs/reviews/1029/29-shader-bright-corner.md）：hi 的
        // 1.0 截断曾在唯一角点 (threshold=1.0, soft=0) 吃掉 +0.0001 余量 ⇒ lo==hi ⇒
        // smoothstep 未定义（驱动相关 NaN 可污染导出帧）。修复式勿叠加 1.0 上限——叠加在
        // 同一角点仍截回 lo==hi，等于没修（29 专档「修复方向」二次校核结论）。
        assertTrue(
            "hi 必须是 max(uThreshold + s, lo + 0.0001)（s = 压缩后的软边；edge0<edge1 恒成立，与 GLOW_COPY_FS 同款防御）",
            bright.contains("max(uThreshold + s, lo + 0.0001)")
        )
        assertFalse(
            "不许再出现 1.0 截断（min(uThreshold + uSoft 形态 = 角点退化回潮）",
            bright.contains("min(uThreshold + uSoft")
        )

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

    // ---------------------------------------------------------------- 旋转恰好生效一次

    @Test
    fun `解码format必须剥除容器旋转角`() {
        // 🔴 2026-10-29（docs/reviews/1029/47-scene-export-rotation.md）：平台把
        // "带 KEY_ROTATION 的 format + 有输出面的解码器"解释成把旋转烘进解码像素
        //（OrientationExport.bakedRotationNeedsStrip 的仓内真机证据），muxer 又写同一个
        // hint ⇒ 双重旋转——竖录/前摄横录源片后渲染导出方向错误。
        // 与 ArcRepairRunner 同款：配置解码器必须过 decoderFormatOf（保真拷贝+剥 KEY_ROTATION）。
        assertTrue(
            "解码器 format 必须过 decoderFormatOf（剥 KEY_ROTATION，与 ArcRepairRunner 同款）",
            runnerSrc.contains("dec.configure(decoderFormatOf(vFormat), decoderSurface, null, 0)")
        )
        assertFalse(
            "不许再把带旋原始 format 直接喂给带输出面的解码器",
            runnerSrc.contains("dec.configure(vFormat,")
        )
    }

    @Test
    fun `muxer旋转写且仅写源旋转一次`() {
        // 剥旋后解码像素是 as-buffered 原样 ⇒ 容器方向只能靠 hint 恰好补一次：
        // 读数必须双源（readSourceRotationHint：retriever 权威 + extractor 兜"部分机型给 0"，
        // 单源 retriever 在那些机型读 0 会导出横躺片），写出经 exportOrientationHint
        // 与录制/修复/无损导出同一条写出口径。
        assertTrue(
            "旋转读数必须走双源 readSourceRotationHint",
            runnerSrc.contains("readSourceRotationHint(ctx, src.uri)")
        )
        assertFalse(
            "单源读数 readRotationDegrees 必须移除（它已被双源口径取代）",
            runnerSrc.contains("readRotationDegrees")
        )
        assertTrue(
            "hint 必须来自 exportOrientationHint(readSourceRotationHint(...))（写出与读数同一处）",
            runnerSrc.contains("exportOrientationHint(readSourceRotationHint(ctx, src.uri), ORIENTATION_MUXER_CCW)")
        )
        // 反证：双源裁决纯函数必须真实存在（上面的"引用"不是指向空想）
        assertTrue(
            "readSourceRotationHint 必须在 RemuxOrientation 定义（无损/选轨导出同源）",
            KotlinSourceScan.mainSourceText("player/RemuxOrientation.kt").contains("fun readSourceRotationHint")
        )
    }
}
