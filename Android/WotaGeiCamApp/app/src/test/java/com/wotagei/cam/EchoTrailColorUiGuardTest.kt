package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 残影拖尾颜色补丁的 **UI 接线守卫**（plan/35，2026-11-09）——
 * 引擎/着色器段归 EchoWiringGuardTest / EchoShaderTest / SceneExportWiringGuardTest，
 * 这里钉的是"色盘三控件真的接进了两处面板、双击回归默认的锚在位、文案词条双语（有则）齐全"：
 *
 * - 色盘组件须已泛化为 WotaColorWheel（internal + 可选双击通道），Glow 与 Echo 各一枚调用；
 * - 手势须读取两条最新回调、只以 editable 为 pointerInput 键；双击后消费按压但不再取色；
 * - EchoPill 须含模式五档写链（trailColorMode）、双击回归默认（ECHO_TRAIL_COLOR_DEFAULT）、
 *   容差滑杆接域常量（ECHO_TRAIL_TOL_MIN）与刻度梯（percentLadder(5, 80, 5)）；
 * - 播放器导出面板（ExportEffectConfig）须有同款三控件锚（作用于本地副本，不反写落盘）；
 * - strings 词条（plan/35 §3.4 列表）必须在 strings_camera.xml 在位。
 *
 * 全部是源码级判据（GL 行为只有真机能证）。
 */
class EchoTrailColorUiGuardTest {

    private val pillsCode by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt"))
    }
    private val screenCode by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt"))
    }

    private fun pillsBody(name: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(pillsCode, name))

    private fun exportBody(): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(screenCode, "ExportEffectConfig"))

    @Test
    fun `色盘已泛化为WotaColorWheel且两处面板各有一枚`() {
        // 定义本体 + Glow 调用 + Echo 调用 ⇒ CameraPills 里至少三处；调用分别钉进两个面板函数体。
        // 词不带"("：occurrences 的词边界规则要求 needle 后邻字符非标识符，而 GlowPill 是
        // `WotaColorWheel(current = …)` 行内实参风格（"(" 后紧跟 c 会被判成标识符一部分而漏计）
        assertTrue(
            "CameraPills 里 WotaColorWheel 须至少三处（定义 + Glow + Echo）",
            KotlinSourceScan.occurrences(pillsCode, "WotaColorWheel").size >= 3
        )
        assertTrue("GlowPill 须保留一枚色盘调用", pillsBody("GlowPill").contains("WotaColorWheel("))
        assertTrue("EchoPill 须有一枚色盘调用", pillsBody("EchoPill").contains("WotaColorWheel("))
        assertFalse("旧名 GlowColorWheel( 不许残留", pillsCode.contains("GlowColorWheel("))
        assertTrue("导出面板须有一枚色盘调用", exportBody().contains("WotaColorWheel("))
    }

    private fun wheelBody(): String = pillsBody("WotaColorWheel")

    private fun wheelCallbacksAreFresh(body: String): Boolean {
        // 状态委托须建在手势协程之外；事件内只读取委托，不能缓存首次组合的回调。
        val setup = body.substringBefore(".pointerInput")
        val events = body.substringAfter("awaitEachGesture {", "")
        val pick = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(body, "pick"))
        return setup.contains("val latestOnPick by rememberUpdatedState(onPick)") &&
            setup.contains("val latestOnDoubleTap by rememberUpdatedState(onDoubleTap)") &&
            pick.contains("latestOnPick( com.wotagei.cam.core.glowArgbFromWheel(") &&
            events.contains(
                "if (isDoubleTap) { latestOnDoubleTap?.invoke() ?: " +
                    "latestOnPick(com.wotagei.cam.core.GLOW_COLOR_DEFAULT)"
            ) &&
            !Regex("""\b(onPick|onDoubleTap)\s*(\(|\?\.\s*invoke\s*\()""").containsMatchIn(events)
    }

    private fun wheelHasStablePointerKey(body: String): Boolean =
        Regex("""\.pointerInput\s*\(([^)]*)\)\s*\{""").findAll(body)
            .map { it.groupValues[1].trim() }.toList() == listOf("editable")

    private fun wheelSuppressesDoubleTapPick(body: String): Boolean =
        body.contains("while (true) { val change = awaitPointerEvent()") &&
            body.contains(
                "if (change.pressed) { if (!isDoubleTap) pick(change.position) " +
                    "change.consume() } else { break }"
            )

    @Test
    fun `色盘拾色与双击事件须调用最新回调`() {
        assertTrue(
            "两条回调须各经 rememberUpdatedState；pick、双击通道及默认白色分支须实际调用最新引用",
            wheelCallbacksAreFresh(wheelBody())
        )
    }

    @Test
    fun `色盘pointerInput只能以editable为键避免重组打断拖动或双击`() {
        assertTrue("颜色与回调不能进入 pointerInput 的键", wheelHasStablePointerKey(wheelBody()))
    }

    @Test
    fun `色盘双击后持续消费到抬起但不再拾色覆盖复位`() {
        assertTrue(
            "pressed 分支取色须受 !isDoubleTap 保护，消费不能跟着该条件被跳过",
            wheelSuppressesDoubleTapPick(wheelBody())
        )
    }

    @Test
    fun `尺子自己能红_旧回调重启键及双击续取色突变必红`() {
        val wheel = wheelBody()
        val callbackMutations = listOf(
            "rememberUpdatedState(onPick)" to "remember { mutableStateOf(onPick) }",
            "rememberUpdatedState(onDoubleTap)" to "remember { mutableStateOf(onDoubleTap) }",
            "latestOnPick( com.wotagei.cam.core.glowArgbFromWheel(" to
                "onPick( com.wotagei.cam.core.glowArgbFromWheel(",
            "latestOnDoubleTap?.invoke()" to "onDoubleTap?.invoke()",
            "?: latestOnPick(com.wotagei.cam.core.GLOW_COLOR_DEFAULT)" to
                "?: onPick(com.wotagei.cam.core.GLOW_COLOR_DEFAULT)"
        )
        for ((before, after) in callbackMutations) {
            assertTrue("回调突变目标须存在：$before", wheel.contains(before))
            assertFalse("旧回调突变须被捕获：$before", wheelCallbacksAreFresh(wheel.replace(before, after)))
        }
        for (key in listOf("current", "onPick", "onDoubleTap", "latestOnPick", "latestOnDoubleTap")) {
            assertTrue("键突变目标须存在", wheel.contains(".pointerInput(editable)"))
            assertFalse(
                "增加 $key 键会重启手势，须被捕获",
                wheelHasStablePointerKey(wheel.replace(".pointerInput(editable)", ".pointerInput(editable, $key)"))
            )
        }
        val guardedPress = "if (!isDoubleTap) pick(change.position) change.consume()"
        assertTrue("双击取色防线的突变目标须存在", wheel.contains(guardedPress))
        for (replacement in listOf(
            "pick(change.position) change.consume()",
            "if (isDoubleTap) pick(change.position) change.consume()",
            "if (!isDoubleTap) { pick(change.position) change.consume() }"
        )) {
            assertFalse(
                "删除或反转双击防线、把消费放进防线均须被捕获：$replacement",
                wheelSuppressesDoubleTapPick(wheel.replace(guardedPress, replacement))
            )
        }
    }

    @Test
    fun `EchoPill钉拖尾颜色三控件`() {
        val b = pillsBody("EchoPill")
        assertTrue("模式选择写链缺失（trailColorMode）", b.contains("trailColorMode"))
        for (res in listOf(
            "cam_echo_tcm_off", "cam_echo_tcm_add", "cam_echo_tcm_sub",
            "cam_echo_tcm_only", "cam_echo_tcm_except"
        )) {
            assertTrue("模式五档缺文案 $res", b.contains(res))
        }
        assertTrue("双击回归默认锚缺失（ECHO_TRAIL_COLOR_DEFAULT，E2/U6）", b.contains("ECHO_TRAIL_COLOR_DEFAULT"))
        assertTrue("容差滑杆没接域下限常量（ECHO_TRAIL_TOL_MIN）", b.contains("ECHO_TRAIL_TOL_MIN"))
        assertTrue("容差刻度梯缺失（percentLadder(5, 80, 5)）", b.contains("percentLadder(5, 80, 5)"))
        assertTrue("容差写链缺失（trailTol）", b.contains("trailTol"))
    }

    @Test
    fun `导出面板钉同款三控件`() {
        val b = exportBody()
        for (res in listOf(
            "cam_echo_tcm_off", "cam_echo_tcm_add", "cam_echo_tcm_sub",
            "cam_echo_tcm_only", "cam_echo_tcm_except"
        )) {
            assertTrue("导出面板模式五档缺文案 $res", b.contains(res))
        }
        assertTrue("导出面板缺色盘调用（WotaColorWheel）", b.contains("WotaColorWheel("))
        assertTrue("导出面板双击也必须回默认（ECHO_TRAIL_COLOR_DEFAULT）", b.contains("ECHO_TRAIL_COLOR_DEFAULT"))
        assertTrue("导出面板容差刻度梯缺失（percentLadder(5, 80, 5)）", b.contains("percentLadder(5, 80, 5)"))
        assertTrue("导出面板容差写链缺失（trailTol）", b.contains("trailTol"))
    }

    @Test
    fun `尺子自己能红_摘掉双击回归默认锚必红`() {
        // 突变：把 EchoPill 的双击回归默认锚换成别的常量 ⇒ 判据必须翻红
        val noReset = pillsCode.replace(
            "com.wotagei.cam.core.ECHO_TRAIL_COLOR_DEFAULT",
            "com.wotagei.cam.core.GLOW_COLOR_DEFAULT"
        )
        assertFalse(
            "摘掉双击回归默认锚的突变必须被这条判据看见",
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(noReset, "EchoPill"))
                .contains("ECHO_TRAIL_COLOR_DEFAULT")
        )
    }

    @Test
    fun `拖尾颜色词条在位`() {
        val zh = resSourceText("values/strings_camera.xml")
        for (name in listOf(
            "cam_echo_trail_color",
            "cam_echo_tcm_off", "cam_echo_tcm_add", "cam_echo_tcm_sub",
            "cam_echo_tcm_only", "cam_echo_tcm_except",
            "cam_echo_tcm_note", "cam_echo_trail_tol"
        )) {
            assertTrue("strings_camera.xml 缺词条 $name", zh.contains("name=\"$name\""))
        }
        // 英文源集（values-en）2026-11-09 仓库实况不存在——只中文一份；哪天补了英文，
        // 这里自动开始钉同名单（新词条不许漏翻）。
        val en = resSourceTextOrNull("values-en/strings_camera.xml")
        if (en != null) {
            for (name in listOf(
                "cam_echo_trail_color", "cam_echo_tcm_add", "cam_echo_tcm_note", "cam_echo_trail_tol"
            )) {
                assertTrue("values-en/strings_camera.xml 缺词条 $name", en.contains("name=\"$name\""))
            }
        }
    }

    // ------------------------------------------------------------------ res 文件定位

    /** 仿 [KotlinSourceScan.mainSourceFile] 的上溯探测，读 `src/main/res` 下的文本文件（守卫输入不许缺） */
    private fun resSourceText(rel: String): String =
        resSourceTextOrNull(rel)
            ?: throw AssertionError("找不到 res 文件 $rel——守卫不能在没有输入的情况下算通过")

    private fun resSourceTextOrNull(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var hops = 0
        while (dir != null && hops <= 5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val f = File(base, "src/main/res/$rel")
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
            hops++
        }
        return null
    }
}
