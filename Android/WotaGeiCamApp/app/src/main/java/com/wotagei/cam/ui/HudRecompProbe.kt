package com.wotagei.cam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wotagei.cam.BuildConfig
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * B12 测量入口：录制页 **Compose 重组计数 / 组合耗时** 的上屏取证叠层。
 *
 * ## 这一件是干什么的
 * 审查 B12（`docs/reviews/android-20261119/fix/B12-HudCtx重组成本未测量.md`）的原文结论只有
 * "HudCtx 随录制页重组的重复组装成本未测量"——动作是"Compose 重组计数/帧耗时测量；超标再物化"。
 * 也就是说：**在拿到次数与耗时之前，任何"该 remember 键化 / 该 derivedStateOf"的判断都是猜的**。
 * 本文件就是那把尺子，不预先假定超标，也不预先假定无害。
 *
 * ## 量什么（三处，都是既有路径上的读数，不新增任何业务分支）
 * - `组合`：`CameraScreen` 组合体进入次数（每次重组 +1）与其**体耗时**（body 起点到终点，
 *   含子 composable 的组合执行）。这就是"一帧里这份 UI 自己花了多少"；
 * - `HudCtx`：`HudCtx(...)` 这一整段汇集（63 个字段 / 21 个函数型，含 stringResource 与
 *   String.format 的标签计算）的单独耗时，用于回答"组合耗时里 HudCtx 占几成"；
 * - `ZoneBox`：`HudZoneBox` 进入次数。父层每重组一次子层是否跟着整棵重入，
 *   看这个比值就够了（≈1 意味着子层与父层同拍，即数据类相等/跳过没生效）。
 *
 * ## 判读线（写死成 [hudProbeVerdict]，只用来给结论贴标签，不参与任何 UI 判断）
 * 60fps 的帧预算是 16.7ms。**组合耗时本身**吃半帧（≥8ms/次）就已在该帧的关键路径上；
 * 若还每帧重组（≥60/s）则连"偶发"都不成立 ⇒ 判 `MATERIALIZE`（该物化）。
 * 2ms/次或 60/s 任一越过 ⇒ `WATCH`（继续盯，未到物化线）。其余 ⇒ `NEGLIGIBLE`（B12 可判无需物化）。
 * **窗口内没有组合样本（或计时缺失）⇒ `UNMEASURED`（未测）**：没有读数就不下结论，
 * 绝不把"没量到"折算成"可关"。
 *
 * ## 三条纪律（与 [FrostProbe] / [CompareProbe] / [MergeDebugHook] 同一条）
 * - **不污染正常 UI**：入口只有 adb 的 intent extra（[EXTRA_HUD_RECOMP_PROBE]），设置页没有一行调试项；
 * - **不持久化**：状态只活在进程内；extra 缺失（从桌面图标重进）就是关闭态；
 * - **变体闸门**：[BuildConfig.HUD_RECOMP_PROBE] 只在 `debugHook` / `probe` 为 true，
 *   `release` / `debug` 恒 false ⇒ 正式包里 [hudRecompProbeEnabledOf] 只能得到"关"。
 *
 * ## 开销
 * 关闭态：调用点先过 [BuildConfig.HUD_RECOMP_PROBE] 编译期常量（`debug` / `release` 恒 false），
 * Kotlin 折叠后计时、入组计数与叠层挂载整句消失，连 `@Volatile` 读与 `System.nanoTime()` 都不剩；
 * 只有 `debugHook` / `probe` 关闭时才多一次 `@Volatile` 布尔读（[enabled]），叠层一个节点都不组合。
 * 打开态：每次组合 2 次 `System.nanoTime()` + 3 次 AtomicLong 写；读数 1Hz 重算一段短文本。
 * 这台机没有 `screenrecord`、`screencap` 一张 464–541ms，所以读数必须由应用自己写在屏幕上。
 */

/** adb intent 里那枚 extra 的键；除 adb 之外没有第二个写入方 */
const val EXTRA_HUD_RECOMP_PROBE = "wota_hud_recomp_probe"

/**
 * 叠层让开霜探针读数的纵向偏移（dp）。霜探针那一段是 7 行 11sp 等宽文本，
 * 这里给足一行余量；两枚探针同时开着的机会只有"一次取证两个问题"，
 * 偏移是取证工具自己的常量，不参与任何布局账，也不定义任何机型参数。
 */
internal const val HUD_RECOMP_PROBE_TOP_DP = 96

/**
 * 「变体允不允许 + adb 给的原始位 → 探针开不开」——release 不可达这道闸门的本体（JVM 可测）。
 * 形参而不是直读 [BuildConfig.HUD_RECOMP_PROBE]，与 [frostProbeEnabledOf] 同一条理由：
 * 两头都要能在 JVM 上测到。
 */
fun hudRecompProbeEnabledOf(enabled: Boolean, raw: Boolean): Boolean = enabled && raw

/**
 * 探针状态：**进程内单例，不写 prefs**。写入方只有 MainActivity 的 intent 一条路
 * （onCreate / onNewIntent），杀进程或从桌面图标重进（无 extra）就回关闭态。
 */
object HudRecompProbe {

    /**
     * 热路径读这个：普通 `@Volatile`，**刻意不用 Compose 状态**——
     * 计数点在 `CameraScreen` / `HudZoneBox` 的组合体里，读一个 Compose 状态会顺带把
     * 这两个组合点订阅到探针开关上（开关变一次就白重组一次，量的是被测对象，不能给它加变量）。
     */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 叠层挂载读这个：Compose 状态，只在 adb 切换那一刻触发一次挂载 */
    private val mountState = mutableStateOf(false)
    val mountEnabled: Boolean get() = mountState.value

    /** 写入方只有 MainActivity 的 intent 一条路 */
    fun apply(raw: Boolean) {
        val on = hudRecompProbeEnabledOf(BuildConfig.HUD_RECOMP_PROBE, raw)
        enabled = on
        mountState.value = on
    }

    // 计数器：Atomic 而非 Compose 状态——写点在组合热路径上，写状态会自己触发重组
    private val bodySamples = AtomicLong()
    private val bodyTotalNs = AtomicLong()
    private val bodyMaxNs = AtomicLong()
    private val ctxSamples = AtomicLong()
    private val ctxTotalNs = AtomicLong()
    private val ctxMaxNs = AtomicLong()
    private val zoneBoxEntries = AtomicLong()

    /** `CameraScreen` 组合体走完一次（body 起点/终点两枚时间戳的差） */
    fun onBodyComposed(ns: Long) {
        bodySamples.incrementAndGet()
        bodyTotalNs.addAndGet(ns)
        bumpMax(bodyMaxNs, ns)
    }

    /** `HudCtx(...)` 这一段汇集走完一次 */
    fun onCtxAssembled(ns: Long) {
        ctxSamples.incrementAndGet()
        ctxTotalNs.addAndGet(ns)
        bumpMax(ctxMaxNs, ns)
    }

    /** `HudZoneBox` 进入组合一次（录制页五枚容器 + 若中途进过编辑页，也计入） */
    fun onZoneBox() {
        zoneBoxEntries.incrementAndGet()
    }

    /**
     * 当前累计读数（只读快照；叠层自己减基准得到窗口内的增量）。
     *
     * `internal`：返回类型 [HudRecompProbeCounts] 是模块内取证类型，不做公开 API；
     * public 函数暴露 internal 返回类型在 Kotlin 里是编译错误（B12 审查点名的第一处）。
     */
    internal fun counts(): HudRecompProbeCounts = HudRecompProbeCounts(
        bodySamples = bodySamples.get(),
        bodyTotalNs = bodyTotalNs.get(),
        bodyMaxNs = bodyMaxNs.get(),
        ctxSamples = ctxSamples.get(),
        ctxTotalNs = ctxTotalNs.get(),
        ctxMaxNs = ctxMaxNs.get(),
        zoneBoxEntries = zoneBoxEntries.get()
    )

    private fun bumpMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current || target.compareAndSet(current, value)) return
        }
    }
}

/**
 * 累计读数。`MaxNs` 是**全程**最大值（进程内单调），不参与窗口差值——
 * 减法对最大值不成立，混进去只会给出假的"窗口最大"。
 */
internal data class HudRecompProbeCounts(
    val bodySamples: Long,
    val bodyTotalNs: Long,
    val bodyMaxNs: Long,
    val ctxSamples: Long,
    val ctxTotalNs: Long,
    val ctxMaxNs: Long,
    val zoneBoxEntries: Long
)

/**
 * B12 的判读三档 + "未测"（见文件头"判读线"）。
 * [UNMEASURED] 不是一档性能结论，而是"窗口内还没有读数"——它必须与 [NEGLIGIBLE] 分开。
 */
internal enum class HudProbeVerdict { NEGLIGIBLE, WATCH, MATERIALIZE, UNMEASURED }

/** 每次组合的平均耗时（µs）；无样本时 0（不是"很快"，是"还没量到"） */
internal fun hudProbeAvgUs(totalNs: Long, samples: Long): Long =
    if (samples <= 0L) 0L else (totalNs / samples) / 1000L

/** 窗口内的每秒次数；`windowMs <= 0` 时 0（不除零，也不编数） */
internal fun hudProbeRatePerSec(delta: Long, windowMs: Long): Int =
    if (windowMs <= 0L) 0 else ((delta * 1000L) / windowMs).toInt()

/** µs → "0.41ms" 的定宽两位小数（整数运算，避免 Double 格式化的位数漂移） */
internal fun hudProbeMsText(us: Long): String {
    val u = us.coerceAtLeast(0L)
    return "${u / 1000}.${((u % 1000) / 10).toString().padStart(2, '0')}ms"
}

/** ms → "2.0s" 的一位小数（窗口头用；整数运算同 [hudProbeMsText]） */
internal fun hudProbeSecText(ms: Long): String {
    val m = ms.coerceAtLeast(0L)
    return "${m / 1000}.${(m % 1000) / 100}s"
}

/**
 * 窗口平均耗时文本：没有样本（或样本在、计时缺失）时写「未测」。
 * 不能复用 [hudProbeMsText] 的 "0.00ms"——那会被读成"很快"，而事实是"没量到"。
 */
internal fun hudProbeAvgText(avgUs: Long, samples: Long, totalNs: Long): String =
    if (samples <= 0L || totalNs <= 0L) "未测" else hudProbeMsText(avgUs)

/**
 * 全程最大耗时文本：窗口内没有样本或计时缺失时不展示历史最大
 * （那会被当成"本窗口测到的"）。有有效样本时仍走
 * [HudRecompProbeCounts.bodyMaxNs] / [HudRecompProbeCounts.ctxMaxNs] 的全程值，
 * 与文件头"最大不参与窗口差值"同一条口径。
 */
internal fun hudProbeMaxText(maxNs: Long, samples: Long, totalNs: Long): String =
    if (samples <= 0L || totalNs <= 0L) "未测" else hudProbeMsText(maxNs / 1000L)

/** 占比（%）：分母无效时 -1 表示"算不出"（叠层据此不显示该行，而不是显示 0%） */
internal fun hudProbeSharePercent(partNs: Long, wholeNs: Long): Int =
    if (wholeNs <= 0L) -1 else ((partNs * 100L) / wholeNs).toInt()

/**
 * 判读：窗口内**组合样本数 + 组合总耗时**与**每秒次数**三输入，
 * 阈值就是文件头写死的那三条（60fps 半帧 = 8.0ms）。
 *
 * 空窗口（`bodySamples == 0`）或计时缺失（`bodyTotalNs == 0`，例如时钟分辨率下没量到差）
 * 一律返回 [HudProbeVerdict.UNMEASURED]——"没量到"不是"可关"。
 * 纯函数，JVM 两侧都可测（正反各一条用例）。
 */
internal fun hudProbeVerdict(
    bodySamples: Long,
    bodyTotalNs: Long,
    ratePerSec: Int
): HudProbeVerdict {
    if (bodySamples <= 0L || bodyTotalNs <= 0L) return HudProbeVerdict.UNMEASURED
    val bodyAvgUs = hudProbeAvgUs(bodyTotalNs, bodySamples)
    return when {
        bodyAvgUs >= HUD_PROBE_HALF_FRAME_US -> HudProbeVerdict.MATERIALIZE
        bodyAvgUs >= HUD_PROBE_PER_FRAME_US && ratePerSec >= HUD_PROBE_EVERY_FRAME_PER_SEC ->
            HudProbeVerdict.MATERIALIZE
        bodyAvgUs >= HUD_PROBE_WATCH_US || ratePerSec >= HUD_PROBE_EVERY_FRAME_PER_SEC ->
            HudProbeVerdict.WATCH
        else -> HudProbeVerdict.NEGLIGIBLE
    }
}

/** 半帧（16.7ms 的一半）——组合耗时吃到这里，该帧的余量已经不够一次绘制 */
private const val HUD_PROBE_HALF_FRAME_US = 8_000L

/** 一帧的 1/4：单次组合成本已不可忽略 */
private const val HUD_PROBE_PER_FRAME_US = 4_000L

/** 继续盯的门槛：帧预算的 12% */
private const val HUD_PROBE_WATCH_US = 2_000L

/** 每帧重组一次（60fps ⇒ 组合在每一帧的关键路径上） */
private const val HUD_PROBE_EVERY_FRAME_PER_SEC = 60

/**
 * 读数文本：一行窗口头 + 组合 / HudCtx / ZoneBox 三段 + 一行判读。
 * 纯函数（[now] 减 [base] 得到窗口增量），叠层只负责按 1Hz 拿新快照。
 */
internal fun hudRecompProbeText(
    now: HudRecompProbeCounts,
    base: HudRecompProbeCounts,
    windowMs: Long
): String {
    val bodyDelta = (now.bodySamples - base.bodySamples).coerceAtLeast(0L)
    val bodyNs = (now.bodyTotalNs - base.bodyTotalNs).coerceAtLeast(0L)
    val ctxDelta = (now.ctxSamples - base.ctxSamples).coerceAtLeast(0L)
    val ctxNs = (now.ctxTotalNs - base.ctxTotalNs).coerceAtLeast(0L)
    val zoneDelta = (now.zoneBoxEntries - base.zoneBoxEntries).coerceAtLeast(0L)
    val bodyAvgUs = hudProbeAvgUs(bodyNs, bodyDelta)
    val bodyRate = hudProbeRatePerSec(bodyDelta, windowMs)
    val ctxAvgUs = hudProbeAvgUs(ctxNs, ctxDelta)
    val share = hudProbeSharePercent(ctxNs, bodyNs)
    val sb = StringBuilder(220)
    sb.append("HUD重组 窗口 ").append(hudProbeSecText(windowMs))
    sb.append("\n组合 ").append(bodyDelta).append(" 次 ").append(bodyRate).append("/s 均 ")
        .append(hudProbeAvgText(bodyAvgUs, bodyDelta, bodyNs))
        .append(" max ").append(hudProbeMaxText(now.bodyMaxNs, bodyDelta, bodyNs))
    sb.append("\nHudCtx 均 ").append(hudProbeAvgText(ctxAvgUs, ctxDelta, ctxNs))
        .append(" max ").append(hudProbeMaxText(now.ctxMaxNs, ctxDelta, ctxNs))
        .append(" 占组合 ").append(if (share < 0) "n/a" else "$share%")
    sb.append("\nZoneBox 入组 ").append(zoneDelta).append(" 次 ")
        .append(hudProbeRatePerSec(zoneDelta, windowMs)).append("/s")
    sb.append("\n判读 ")
    sb.append(
        when (hudProbeVerdict(bodyDelta, bodyNs, bodyRate)) {
            HudProbeVerdict.UNMEASURED -> "未测（窗口内无组合样本，不下结论）"
            HudProbeVerdict.NEGLIGIBLE -> "可关（组合 < 2.00ms/次 且 < 60/s）"
            HudProbeVerdict.WATCH -> "观察（过 2.00ms 或 60/s，未到物化线）"
            HudProbeVerdict.MATERIALIZE -> "超标→物化（remember 键化 / derivedStateOf）"
        }
    )
    return sb.toString()
}

/** 叠层刷新周期：1Hz。1Hz 下读数已经够看清，再快也只是多烧一点组合 */
private const val HUD_PROBE_POLL_MS = 1000L

/**
 * 探针叠层：探针开着才组合（关闭态一个节点都不存在），每 [HUD_PROBE_POLL_MS] 刷新一次读数。
 *
 * 挂载点在 `CameraScreen` 的 HUD 根 **TopStart**、霜探针读数下方（同角叠放会互相遮挡，
 * 偏移是探针专用常量，不进布局账）：只画不收指（无 clickable），不吃任何手势。
 * 窗口 = 本叠层挂载时刻起：从打开探针那一刻开始算，避免把上一次打开时的累计混进来。
 */
@Composable
fun HudRecompProbeOverlay(modifier: Modifier = Modifier) {
    if (!HudRecompProbe.mountEnabled) return
    val base = remember { HudRecompProbe.counts() }
    val startNs = remember { System.nanoTime() }
    // 重组节拍：stamp 只负责让本叠层每秒重算一次文本，不进任何布局账
    var stamp by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(HUD_PROBE_POLL_MS)
            stamp++
        }
    }
    val text = remember(stamp) {
        hudRecompProbeText(
            now = HudRecompProbe.counts(),
            base = base,
            windowMs = (System.nanoTime() - startNs) / 1_000_000L
        )
    }
    Box(
        modifier
            .background(HudProbeScrim, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(text, style = HudProbeStyle, color = HudProbeInk)
    }
}

/** 探针专用样式：等宽小字（读数对齐），与令牌体系无关——它是取证工具，不进观感账 */
private val HudProbeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 9.sp,
    lineHeight = 11.sp
)

/** 半透黑衬底 + 高亮黄字：与霜探针（绿字）一眼区分，亮暗两种预览底上都读得清 */
private val HudProbeScrim = Color(0xCC000000)
private val HudProbeInk = Color(0xFFFFE08A)