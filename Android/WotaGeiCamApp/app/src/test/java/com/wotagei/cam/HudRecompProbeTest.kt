package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.HudProbeVerdict
import com.wotagei.cam.ui.HudRecompProbeCounts
import com.wotagei.cam.ui.hudProbeAvgText
import com.wotagei.cam.ui.hudProbeAvgUs
import com.wotagei.cam.ui.hudProbeMaxText
import com.wotagei.cam.ui.hudProbeMsText
import com.wotagei.cam.ui.hudProbeRatePerSec
import com.wotagei.cam.ui.hudProbeSecText
import com.wotagei.cam.ui.hudProbeSharePercent
import com.wotagei.cam.ui.hudProbeVerdict
import com.wotagei.cam.ui.hudRecompProbeEnabledOf
import com.wotagei.cam.ui.hudRecompProbeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B12 测量入口（`ui/HudRecompProbe.kt`）的纯函数层与接线守卫。
 *
 * **这一批不冒充"已测量"**：本文件证明的是"尺子本身不错"——
 * 均值/频率/占比/判读四件纯算术两头都对、读数文本把"窗口增量"与"全程最大"分开写、
 * 三处计数点与 adb 入口都接在既有路径上、正式变体的闸门只可能得到"关"。
 * 真正要的那组数（录制中每秒组合几次、一次多少 ms）只有真机 + `debugHook`/`probe` 包
 * + `adb shell am start ... --ez wota_hud_recomp_probe true` 才能拿到，JVM 里一条都没有。
 *
 * **测不到的**（照实写，不拿结构断言充数）：
 * `BuildConfig.HUD_RECOMP_PROBE` 在四个变体各是什么值（构建侧证据，用生成的 BuildConfig 核，
 * 本文件只用 [hudProbeFieldMismatch] 钉住 build.gradle.kts 里四枚声明与 FROST_PROBE 逐位同形）；
 * 计时点是否真的落在组合体起点/终点（源码结构能钉位置，钉不到运行时语义）；
 * 叠层挂载后读数是否为真（这正是探针存在的目的）。
 */
class HudRecompProbeTest {

    // ------------------------------------------------------------ 闸门

    @Test
    fun `闸门在正式变体恒关`() {
        // 变体闸门关着时，adb 喂 true 必须整个作废——这就是"release 进不到探针"的本体
        assertFalse("闸门关着就不许开探针", hudRecompProbeEnabledOf(enabled = false, raw = true))
        assertFalse("没带 extra（桌面图标重进）= 关闭态", hudRecompProbeEnabledOf(enabled = true, raw = false))
        // 同一个 extra 在开着的变体里必须真的生效（否则上一条的"红"可能只是函数写死了 false）
        assertTrue(hudRecompProbeEnabledOf(enabled = true, raw = true))
    }

    // ------------------------------------------------------------ 纯算术

    @Test
    fun `均值没样本时是0而不是编一个数`() {
        assertEquals(0L, hudProbeAvgUs(totalNs = 0L, samples = 0L))
        assertEquals(0L, hudProbeAvgUs(totalNs = 9_999_999L, samples = 0L))
        // 3 次共 3000ns ⇒ 每次 1000ns = 1µs
        assertEquals(1L, hudProbeAvgUs(totalNs = 3_000L, samples = 3L))
        // 8ms / 4 次 = 2ms = 2000µs
        assertEquals(2_000L, hudProbeAvgUs(totalNs = 8_000_000L, samples = 4L))
    }

    @Test
    fun `频率除零不算数`() {
        assertEquals(0, hudProbeRatePerSec(delta = 5L, windowMs = 0L))
        assertEquals(0, hudProbeRatePerSec(delta = 5L, windowMs = -1L))
        // 500ms 内 5 次 = 10/s
        assertEquals(10, hudProbeRatePerSec(delta = 5L, windowMs = 500L))
        // 2s 内 1 次 = 0.5/s ⇒ 取整为 0（不虚报成 1）
        assertEquals(0, hudProbeRatePerSec(delta = 1L, windowMs = 2_000L))
    }

    @Test
    fun `毫秒与秒文本定宽两位`() {
        assertEquals("0.00ms", hudProbeMsText(0L))
        assertEquals("0.41ms", hudProbeMsText(410L))
        assertEquals("2.00ms", hudProbeMsText(2_000L))
        assertEquals("12.34ms", hudProbeMsText(12_345L))
        assertEquals("0.00ms", hudProbeMsText(-5L))
        assertEquals("0.5s", hudProbeSecText(500L))
        assertEquals("2.0s", hudProbeSecText(2_013L))
        assertEquals("12.9s", hudProbeSecText(12_999L))
    }

    @Test
    fun `占比算不出时是负数而不是0`() {
        // 分母无效 ⇒ -1（叠层据此显示 n/a，见 [hudRecompProbeText]）；0% 是"量到了但占比为零"，两者不能混
        assertEquals(-1, hudProbeSharePercent(partNs = 1L, wholeNs = 0L))
        assertEquals(25, hudProbeSharePercent(partNs = 100L, wholeNs = 400L))
        assertEquals(0, hudProbeSharePercent(partNs = 0L, wholeNs = 400L))
        assertEquals(100, hudProbeSharePercent(partNs = 400L, wholeNs = 400L))
    }

    // ------------------------------------------------------------ 判读线

    @Test
    fun `判读三档的边界真的在边界上`() {
        // 半帧（8ms/次）以上：不管频率多少一律"该物化"
        assertEquals(
            HudProbeVerdict.MATERIALIZE,
            hudProbeVerdict(bodySamples = 1L, bodyTotalNs = 8_000_000L, ratePerSec = 0)
        )
        // 差 1µs 就掉一档——证明阈值不是摆设
        assertNotEquals(
            HudProbeVerdict.MATERIALIZE,
            hudProbeVerdict(bodySamples = 1L, bodyTotalNs = 7_999_000L, ratePerSec = 0)
        )
        // 4ms/次且每帧重组：组合在每一帧的关键路径上 ⇒ 该物化
        assertEquals(
            HudProbeVerdict.MATERIALIZE,
            hudProbeVerdict(bodySamples = 2L, bodyTotalNs = 8_000_000L, ratePerSec = 60)
        )
        // 同样的 4ms 但不到每帧 ⇒ 只到观察线
        assertEquals(
            HudProbeVerdict.WATCH,
            hudProbeVerdict(bodySamples = 2L, bodyTotalNs = 8_000_000L, ratePerSec = 59)
        )
        // 2ms 或 60/s 任一越过 ⇒ 观察
        assertEquals(
            HudProbeVerdict.WATCH,
            hudProbeVerdict(bodySamples = 1L, bodyTotalNs = 2_000_000L, ratePerSec = 0)
        )
        assertEquals(
            HudProbeVerdict.WATCH,
            hudProbeVerdict(bodySamples = 1L, bodyTotalNs = 1_000_000L, ratePerSec = 60)
        )
        // 两条都在线下 ⇒ 可关（B12 判"无需物化"的唯一一格）
        assertEquals(
            HudProbeVerdict.NEGLIGIBLE,
            hudProbeVerdict(bodySamples = 1L, bodyTotalNs = 1_999_000L, ratePerSec = 59)
        )
    }

    @Test
    fun `空窗口与缺计时判未测而不是可关`() {
        // 空窗口：一次组合都没采到
        assertEquals(
            HudProbeVerdict.UNMEASURED,
            hudProbeVerdict(bodySamples = 0L, bodyTotalNs = 0L, ratePerSec = 0)
        )
        assertNotEquals(
            HudProbeVerdict.NEGLIGIBLE,
            hudProbeVerdict(bodySamples = 0L, bodyTotalNs = 0L, ratePerSec = 0)
        )
        // 有计数但计时为 0（时钟分辨率下没量到差）：不是"0ms 很快"
        assertEquals(
            HudProbeVerdict.UNMEASURED,
            hudProbeVerdict(bodySamples = 3L, bodyTotalNs = 0L, ratePerSec = 0)
        )
        // 计数为 0 却带着总耗时（窗口差值削到 0）：同样未测
        assertEquals(
            HudProbeVerdict.UNMEASURED,
            hudProbeVerdict(bodySamples = 0L, bodyTotalNs = 5_000_000L, ratePerSec = 0)
        )
        // 异常负数输入也不许落到可关
        assertEquals(
            HudProbeVerdict.UNMEASURED,
            hudProbeVerdict(bodySamples = -1L, bodyTotalNs = -1L, ratePerSec = 0)
        )
    }

    // ------------------------------------------------------------ 读数文本

    @Test
    fun `读数把窗口增量与全程最大分开写`() {
        val now = HudRecompProbeCounts(
            bodySamples = 10L, bodyTotalNs = 20_000_000L, bodyMaxNs = 4_500_000L,
            ctxSamples = 10L, ctxTotalNs = 4_000_000L, ctxMaxNs = 1_200_000L,
            zoneBoxEntries = 50L
        )
        val base = HudRecompProbeCounts(
            bodySamples = 6L, bodyTotalNs = 12_000_000L, bodyMaxNs = 3_000_000L,
            ctxSamples = 6L, ctxTotalNs = 2_400_000L, ctxMaxNs = 900_000L,
            zoneBoxEntries = 30L
        )
        val text = hudRecompProbeText(now = now, base = base, windowMs = 500L)
        // 窗口：4 次组合（10−6）、8/s（4 次 / 0.5s）、均 2.00ms（8ms / 4 次）
        assertTrue(text, text.contains("窗口 0.5s"))
        assertTrue(text, text.contains("组合 4 次 8/s 均 2.00ms max 4.50ms"))
        // max 走全程值（now 的 4.5ms），不参与窗口差值
        assertTrue(text, text.contains("HudCtx 均 0.40ms max 1.20ms 占组合 20%"))
        assertTrue(text, text.contains("ZoneBox 入组 20 次 40/s"))
        assertTrue(text, text.contains("判读 观察"))
    }

    @Test
    fun `均值与最大文本缺样本时写未测`() {
        assertEquals("未测", hudProbeAvgText(avgUs = 0L, samples = 0L, totalNs = 0L))
        assertEquals("未测", hudProbeAvgText(avgUs = 0L, samples = 3L, totalNs = 0L))
        assertEquals("0.40ms", hudProbeAvgText(avgUs = 400L, samples = 3L, totalNs = 1_200_000L))
        // 历史最大只在窗口有样本且计时有效时才展示；空窗口不许把全局 max 当成"本窗口测到的"
        assertEquals("未测", hudProbeMaxText(maxNs = 4_500_000L, samples = 0L, totalNs = 0L))
        assertEquals("未测", hudProbeMaxText(maxNs = 4_500_000L, samples = 1L, totalNs = 0L))
        assertEquals("4.50ms", hudProbeMaxText(maxNs = 4_500_000L, samples = 1L, totalNs = 1_200_000L))
    }

    @Test
    fun `零样本窗口写未测而不是可关或0 percent`() {
        val now = HudRecompProbeCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L)
        val text = hudRecompProbeText(now = now, base = now, windowMs = 1_000L)
        assertTrue(text, text.contains("占组合 n/a"))
        assertFalse("没有样本时不许给出 0% 的假占比", text.contains("占组合 0%"))
        assertTrue("零样本必须落到未测档，而不是可关/观察/物化", text.contains("判读 未测"))
        assertFalse("零样本绝不能被判成可关（B12 的假阴性）", text.contains("判读 可关"))
        assertTrue("零样本时均值不许写 0.00ms", text.contains("均 未测"))
        assertFalse(text, text.contains("均 0.00ms"))
        assertTrue("零样本时最大也不许写 0.00ms", text.contains("max 未测"))
    }

    // ------------------------------------------------------------ 接线守卫

    private fun code(rel: String): String = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun flat(rel: String): String = KotlinSourceScan.flatten(code(rel))

    @Test
    fun `录制页的三处计时点都在组合体上`() {
        val screen = flat("ui/CameraScreen.kt")
        val startAt = screen.indexOf("val hudProbeStartNs = if (BuildConfig.HUD_RECOMP_PROBE && HudRecompProbe.enabled) System.nanoTime() else 0L")
        val ctxAt = screen.indexOf("val hudCtxStartNs = if (BuildConfig.HUD_RECOMP_PROBE && HudRecompProbe.enabled) System.nanoTime() else 0L")
        val ctxEnd = screen.indexOf("if (BuildConfig.HUD_RECOMP_PROBE && hudCtxStartNs != 0L) { HudRecompProbe.onCtxAssembled(System.nanoTime() - hudCtxStartNs) }")
        val bodyEnd = screen.indexOf("if (BuildConfig.HUD_RECOMP_PROBE && hudProbeStartNs != 0L) { HudRecompProbe.onBodyComposed(System.nanoTime() - hudProbeStartNs) }")
        assertTrue("组合体起点计时必须在（B12 的分子）", startAt >= 0)
        assertTrue("HudCtx 起始计时必须在（回答'占组合几成'）", ctxAt >= 0)
        assertTrue("HudCtx 终点必须在段落后记一笔", ctxEnd >= 0)
        assertTrue("组合体终点必须在函数末尾记一笔", bodyEnd >= 0)
        // 顺序：起点 → HudCtx 段（起→终）→ body 终点。位置颠倒量出来的就是负数/别人的耗时
        assertTrue("HudCtx 段必须在组合体起点之后", ctxAt > startAt)
        assertTrue("HudCtx 终点必须在 HudCtx 起点之后", ctxEnd > ctxAt)
        assertTrue("组合体终点必须最后记", bodyEnd > ctxEnd)
    }

    @Test
    fun `HudZoneBox 有子层入组计数`() {
        assertTrue(
            "HudZoneBox 必须记一次入组（B12：对照父层组合率判子层是否整棵重入）",
            code("ui/HudLayer.kt").contains(
                "if (BuildConfig.HUD_RECOMP_PROBE && HudRecompProbe.enabled) HudRecompProbe.onZoneBox()"
            )
        )
    }

    @Test
    fun `关闭态热路径先过编译期常量再读探针开关`() {
        val screen = flat("ui/CameraScreen.kt")
        val hud = flat("ui/HudLayer.kt")
        // 正常 debug/release：HUD_RECOMP_PROBE=false 是 javac 常量，Kotlin 折叠后整句消失，
        // 连 @Volatile 读都不剩；只有 debugHook/probe 才会继续读开关。
        assertTrue(
            "组合体起点必须先过编译期常量",
            screen.contains("val hudProbeStartNs = if (BuildConfig.HUD_RECOMP_PROBE && HudRecompProbe.enabled)")
        )
        assertTrue(
            "HudCtx 起点必须先过编译期常量",
            screen.contains("val hudCtxStartNs = if (BuildConfig.HUD_RECOMP_PROBE && HudRecompProbe.enabled)")
        )
        assertTrue(
            "子层入组计数必须先过编译期常量",
            hud.contains("if (BuildConfig.HUD_RECOMP_PROBE && HudRecompProbe.enabled) HudRecompProbe.onZoneBox()")
        )
        assertTrue(
            "叠层挂载点必须整段包在编译期常量里",
            screen.contains("if (BuildConfig.HUD_RECOMP_PROBE) { HudRecompProbeOverlay(")
        )
        assertTrue(
            "HudCtx 终点也必须先过编译期常量（正常变体整句消失）",
            screen.contains("if (BuildConfig.HUD_RECOMP_PROBE && hudCtxStartNs != 0L)")
        )
        assertTrue(
            "组合体终点也必须先过编译期常量（正常变体整句消失）",
            screen.contains("if (BuildConfig.HUD_RECOMP_PROBE && hudProbeStartNs != 0L)")
        )
        // 反例守卫：不许再出现绕过编译期常量的热路径读（否则正常变体每次组合仍读 @Volatile）
        assertFalse(
            "热路径不许只剩运行期开关读",
            screen.contains("= if (HudRecompProbe.enabled)")
        )
        assertFalse(
            "终点不许只剩运行期开关读",
            screen.contains("if (hudCtxStartNs != 0L)") || screen.contains("if (hudProbeStartNs != 0L)")
        )
        assertFalse(
            "子层不许只剩运行期开关读",
            hud.contains("if (HudRecompProbe.enabled) HudRecompProbe.onZoneBox()")
        )
    }

    @Test
    fun `adb 入口在 onCreate 与 onNewIntent 两条路都接`() {
        val main = KotlinSourceScan.flatten(code("ui/MainActivity.kt"))
        // 两处调用点（onCreate / onNewIntent）+ 一处定义（形参带类型，不匹配这个字面量）
        assertEquals(
            "applyHudRecompProbe(intent) 必须在 onCreate 与 onNewIntent 各调一次",
            2,
            main.split("applyHudRecompProbe(intent)").size - 1
        )
        assertTrue(main, main.contains("private fun applyHudRecompProbe(intent: Intent?)"))
        assertTrue(main, main.contains("HudRecompProbe.apply(intent?.getBooleanExtra(EXTRA_HUD_RECOMP_PROBE, false) == true)"))
    }

    @Test
    fun `探针不持久化也不往热路径挂 Compose 订阅`() {
        val probe = code("ui/HudRecompProbe.kt")
        assertFalse("探针状态不许写 prefs（进程内单例）", probe.contains("SharedPreferences"))
        assertFalse("探针状态不许写 prefs（进程内单例）", probe.contains("getSharedPreferences"))
        // 热路径读 @Volatile 布尔：改成 `var enabled by mutableStateOf(...)` 会让录制页与
        // HudZoneBox 订阅开关本身（量的是被测对象，不能给它加变量）
        assertFalse(
            "热路径开关不许是 Compose 状态（会让被测组合点订阅开关）",
            probe.contains("var enabled by mutableStateOf")
        )
        assertTrue("热路径开关必须是 @Volatile 普通字段", probe.contains("@Volatile"))
        // 叠层只有一处挂载点，且挂在对齐修饰符的 BoxScope 里（与霜探针同角错位）
        val screen = flat("ui/CameraScreen.kt")
        assertEquals("叠层必须只有一个挂载点", 1, screen.split("HudRecompProbeOverlay(").size - 1)
        assertTrue(screen, screen.contains(".align(Alignment.TopStart)"))
        assertTrue(
            "偏移必须走探针专用常量（不与霜探针读数同角叠放）",
            screen.contains(".padding(top = HUD_RECOMP_PROBE_TOP_DP.dp)")
        )
    }

    @Test
    fun `关闭态早退且计数值不公开暴露内部类型`() {
        val probe = code("ui/HudRecompProbe.kt")
        // counts() 的返回类型是 internal 取证类型：函数也必须是 internal，
        // 否则 Kotlin 直接报 `'public' function exposes its 'internal' return type`
        assertTrue("counts() 必须是 internal", probe.contains("internal fun counts()"))
        assertFalse("counts() 不许是 public（编译期会撞 internal 返回类型）", probe.contains("\n    fun counts()"))
        // 关闭态在叠层根节点之前早退 ⇒ 一个 Compose 节点都不组合
        val guard = probe.indexOf("if (!HudRecompProbe.mountEnabled) return")
        assertTrue("叠层必须在关闭态早退（零节点开销）", guard >= 0)
        assertTrue("早退必须发生在叠层根 Box 之前", probe.indexOf("Box(", guard) > guard)
    }

    // ------------------------------------------------------------ 构建侧

    /** 工作目录不定（Gradle 在 app/，IDE 可能在工程根/仓库根），从 user.dir 往上找；找不到必须抛 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var hops = 0
        while (dir != null && hops <= 5) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
            hops++
        }
        throw AssertionError("找不到 $rel——守卫不能在没有输入的情况下算通过")
    }

    private val gradleKts: String by lazy {
        repoFile("WotaGeiCamApp/app/build.gradle.kts").readText(Charsets.UTF_8)
    }

    private fun buildFieldValues(text: String, field: String): List<String> =
        Regex("buildConfigField\\(\"boolean\", \"$field\", \"(true|false)\"\\)")
            .findAll(text).map { it.groupValues[1] }.toList()

    /** 判据本体：HUD_RECOMP_PROBE 的四枚声明必须与 FROST_PROBE 逐位同形（真身/突变体共用这把尺） */
    private fun hudProbeFieldMismatch(text: String): Boolean {
        val probe = buildFieldValues(text, "HUD_RECOMP_PROBE")
        val frost = buildFieldValues(text, "FROST_PROBE")
        return probe.size != 4 || frost.size != 4 || probe != frost
    }

    @Test
    fun `四个变体各一枚且与霜探针逐位同形`() {
        assertFalse("HUD_RECOMP_PROBE 必须四变体各一枚，且开/关与 FROST_PROBE 一致", hudProbeFieldMismatch(gradleKts))
        assertEquals(
            "字段顺序必须与同文件其它探针同序（debug/debugHook/probe/release）",
            buildFieldValues(gradleKts, "FROST_PROBE"),
            listOf("false", "true", "true", "false")
        )
    }

    @Test
    fun `这条判据自己能红`() {
        // 少一枚（probe 变体漏声明 ⇒ 该变体编译期没有 BuildConfig.HUD_RECOMP_PROBE）
        // 用 replaceFirst：Kotlin 的 String.replace 没有"只换第 1 处"的重载
        val missing = gradleKts.replaceFirst(
            "            buildConfigField(\"boolean\", \"HUD_RECOMP_PROBE\", \"true\")\n",
            ""
        )
        assertTrue("删掉一枚必须判红", hudProbeFieldMismatch(missing))
        // 多点亮一枚（release 里 never 的闸门被打开）
        val openedInRelease = gradleKts.replace(
            "buildConfigField(\"boolean\", \"HUD_RECOMP_PROBE\", \"false\")\n        }\n    }",
            "buildConfigField(\"boolean\", \"HUD_RECOMP_PROBE\", \"true\")\n        }\n    }"
        )
        assertTrue("release 里点亮必须判红", hudProbeFieldMismatch(openedInRelease))
    }
}