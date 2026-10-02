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
import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.camera.GlRenderEngine
import com.wotagei.cam.camera.frostViewOriginInto
import kotlinx.coroutines.delay

/**
 * #84 霜探针：**上屏取证叠层**（唯一用途是回答"霜为什么没上屏"）。
 *
 * ## 为什么必须有
 * 2026-09-30 的 A/B 真机硬负结果（背板区开/关逐像素差 0.0%）取自**启动死锁修复前**的包，
 * 修复后（`3767084`：intent 管喂表 / live 管 fill）链条在代码上是通的——底栏壳
 * `wotaDockShell` 只看 intent 喂表，GL 画了板 live 才翻真，其余底板才跟着注册。
 * 但"代码上通"不等于"真机上通"：断点可能在链上任何一环（快照没发布、表是空的、
 * 画板 pass 内部早退、视图原点推断不成立）。这台机 `screencap` 一张 464–541ms、
 * 没有 `screenrecord`，逐环取证只能让应用自己把各环的数**写在屏幕上**读。
 *
 * ## 为什么不是 BuildConfig.DEBUG
 * 与 [com.wotagei.cam.ui.anim.MergeDebugHook] 同一条纪律（构建侧见 `app/build.gradle.kts`）：
 * 本机不能装 debug 包（与 release 两张证书，换装必须 uninstall ⇒ 清空用户存档），
 * 钩子挂在**与 release 同签名、只是 debuggable=true** 的 `debugHook` 变体上，
 * 开关是变体专属的 [BuildConfig.FROST_PROBE]：`release`/`debug` 恒 false，
 * `frostProbeEnabledOf` 在正式变体里只能得到"关"。
 *
 * ## 三条纪律（与 MERGE_HOOK 相同）
 * - **不污染正常 UI**：入口只有 adb 的 intent extra（[EXTRA_FROST_PROBE]），设置页没有一行调试项；
 * - **不持久化**：状态只活在进程内；extra 缺失（从桌面图标重进）就是关闭态；
 * - **只读不改**：探针读的全是各环节已有的状态（GL 侧三个诊断位是本文件配套新增的**镜像**，
 *   不参与任何绘制判断），不给链路加任何分支、不碰布局账。
 *
 * ## 读数怎么用（七行各自的判读）
 * - `intent`：UI 侧开关意图（[HudFrost.intent]）。0 ⇒ 设置键没到 UI，先查 `hud_frost_blur`；
 * - `hdr`：表头的 UI 开关位（[FrostCardTable.hasUiEnabled]）。intent=1 而 hdr=0 ⇒ [HudFrost.refreshHeader]
 *   没跑（根尺寸 0 / 表被停用，看下一行）；
 * - `slots`：租出去的槽位数（[FrostCardTable.usedSlotCount]）。0 ⇒ 没有任何注册器挂上
 *   （底栏壳不在组合 / `wotaHudCard` 的闸没开）；
 * - `cards`：GL 最近一个窗口帧从表里读到的卡片数。slots>0 而 cards=0 ⇒ 写卡失败
 *   （表被停用 / 写方线程漂移，看 `tbl` 行）；
 * - `want/avail`：GL 的开关位与快照可用位（[GlRenderEngine.isFrostBlurEnabled] /
 *   [GlRenderEngine.isFrostBlurAvailable]）。want=1 而 avail=0 ⇒ 离屏链没产出（DIRECT 模式没有
 *   glEngine、链 broken、上屏节流都会这样）；
 * - `drew`：最近一个窗口帧实画的板数（[GlRenderEngine.frostDiagDrewPlates]）。
 *   cards>0 且 avail=1 而 drew=0 ⇒ 断点在画板 pass 内部：`chainBrk/plateBrk` 看着色器，
 *   `geo` 行看视图原点推断（`frostViewOriginInto` 不成立时一块都不画）；
 * - `rep/live`：GL 回报位与 UI 让位位。drew>0 而 live=0 ⇒ 200ms 轮询没跑（[HudFrost] 的 bug）。
 *
 * ⚠ `cards/drew` 是"最近一个窗口帧"的值：上屏被 `windowThrottled()` 节流的帧不刷新（与
 * [FrostCardTable.isPlatesDrawn] 同口径），读数停住不等于链死了，再等一拍。
 */

/** adb intent 里那枚 extra 的键；除 adb 之外没有第二个写入方 */
const val EXTRA_FROST_PROBE = "wota_frost_probe"

/**
 * 「变体允不允许 + adb 给的原始位 → 探针开不开」——**release 不可达这道闸门的本体**（JVM 可测）。
 * [enabled] 当形参而不是直接读 [BuildConfig.FROST_PROBE]，就是为了能让用例两头都测到，
 * 与 `mergeHookArgsOf` 同一条理由。
 */
fun frostProbeEnabledOf(enabled: Boolean, raw: Boolean): Boolean = enabled && raw

/**
 * 探针状态：**进程内单例，不写 prefs**。写入方只有 MainActivity 的 intent 一条路
 * （onCreate / onNewIntent），杀进程或从桌面图标重进（无 extra）就回关闭态。
 */
object FrostProbe {

    /** 当前是否开着；读侧也过 [frostProbeEnabledOf] 同一道闸门，正式 release 恒为 false */
    var enabled by mutableStateOf(false)
        private set

    /** 写入方只有 MainActivity 的 intent 一条路 */
    fun apply(raw: Boolean) {
        enabled = frostProbeEnabledOf(BuildConfig.FROST_PROBE, raw)
    }
}

/** 探针轮询周期：与 [HudFrost] 的 tick 同一档频率，5Hz 读十来个 volatile 的成本可忽略 */
private const val FROST_PROBE_POLL_MS = 200L

/**
 * 把链上各环的数收成一段等宽文本。**不是** Composable（不重组、只读快照），返回值直接上屏。
 *
 * 表格副本按 [FrostCardTable.TABLE_FLOATS] 临时分配一枚：探针每 200ms 一次、8 字节 × 64 的短命对象，
 * 不在"每帧零分配"的绘制路径上，不为它破坏读法的直白。
 */
internal fun frostProbeText(glEngine: GlRenderEngine?): String {
    val copy = FloatArray(FrostCardTable.TABLE_FLOATS)
    val origin = FloatArray(2)
    val sb = StringBuilder(256)
    sb.append("FROSTPROBE mode=")
    if (glEngine == null) {
        // DIRECT 模式整条 GL 链不存在：霜物理上不可得（docs/plan/14 §二），读数到此为止
        sb.append("DIRECT（无 GL，霜不可得）")
        return sb.toString()
    }
    sb.append("GPU")
    val hdrUi = FrostCardTable.hasUiEnabled()
    val cards = FrostCardTable.tryReadInto(copy)
    sb.append("\nui intent=").append(if (HudFrost.intent) 1 else 0)
        .append(" live=").append(if (HudFrost.live) 1 else 0)
        .append(" hdr=").append(if (hdrUi) 1 else 0)
        .append(" rep=").append(if (FrostCardTable.isPlatesDrawn()) 1 else 0)
        .append(" slots=").append(FrostCardTable.usedSlotCount())
    sb.append("\nrd cards=").append(glEngine.frostDiagTableCards)
        .append(" drew=").append(glEngine.frostDiagDrewPlates)
        .append(" snap=").append(if (glEngine.frostDiagSnapshotPublished) 1 else 0)
        .append(" want=").append(if (glEngine.isFrostBlurEnabled()) 1 else 0)
        .append(" avail=").append(if (glEngine.isFrostBlurAvailable()) 1 else 0)
    sb.append("\nbrk chain=").append(if (glEngine.frostChainBrokenForDiagnostics()) 1 else 0)
        .append(" plate=").append(if (glEngine.frostPlateBrokenForDiagnostics()) 1 else 0)
    // 几何自查：拿探针自己读到的表头 + GL 的视图尺寸，把画板 pass 那次原点推断在 UI 侧重算一遍。
    // 两边吃同一份表头与同一对视图尺寸 ⇒ 结果应与 GL 帧内一致；ok=0 就是"一块都不画"的直接成因
    val view = glEngine.windowSize()
    val geoOk = if (cards >= 0 && view.first > 0 && view.second > 0) {
        frostViewOriginInto(
            origin,
            rootLeftPx = copy[FrostCardTable.HEADER_ROOT_LEFT],
            rootTopPx = copy[FrostCardTable.HEADER_ROOT_TOP],
            rootWidthPx = copy[FrostCardTable.HEADER_ROOT_WIDTH],
            rootHeightPx = copy[FrostCardTable.HEADER_ROOT_HEIGHT],
            viewWidthPx = view.first,
            viewHeightPx = view.second
        )
    } else {
        null
    }
    sb.append("\ngeo view=").append(view.first).append('x').append(view.second)
        .append(" origin=").append(
            when {
                geoOk == null -> "n/a"
                geoOk -> "(${origin[0].toInt()},${origin[1].toInt()})"
                else -> "fail"
            }
        )
        .append(" root=").append(copy[FrostCardTable.HEADER_ROOT_WIDTH].toInt())
        .append('x').append(copy[FrostCardTable.HEADER_ROOT_HEIGHT].toInt())
    sb.append("\ntbl ")
    if (FrostCardTable.isDisabledForDiagnostics()) {
        sb.append("DISABLED ").append(FrostCardTable.disabledReasonForDiagnostics())
    } else {
        sb.append("ok")
    }
    sb.append(" wThr=").append(
        if (FrostCardTable.writerThreadIdForDiagnostics() == Thread.currentThread().id) 1 else 0
    )
    return sb.toString()
}

/**
 * 探针叠层：探针开着才组合（关闭态一个节点都不存在），每 [FROST_PROBE_POLL_MS] 刷新一次读数。
 *
 * 挂载点在 `CameraScreen` 的 HUD 根（带 `safeDrawingPadding()` 的那层 Box）**TopStart**：
 * 默认验收姿态是横屏，左上角既不在顶栏（居中）也不在左 Dock（中段）的矩形里；
 * 竖屏下可能与顶栏胶囊相压——探针是取证工具，只在带 extra 的进程里存在，
 * 常规截图不会带上它。它只画不收指（无 clickable），不吃任何手势。
 */
@Composable
fun FrostProbeOverlay(glEngine: GlRenderEngine?, modifier: Modifier = Modifier) {
    if (!FrostProbe.enabled) return
    // 重组节拍：stamp 只负责让本叠层每 200ms 重算一次文本，不进任何布局账
    var stamp by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(FROST_PROBE_POLL_MS)
            stamp++
        }
    }
    val text = remember(stamp) { frostProbeText(glEngine) }
    Box(
        modifier
            .background(ProbeScrim, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(text, style = ProbeStyle, color = ProbeInk)
    }
}

/** 探针专用样式：等宽小字（读数对齐），与令牌体系无关——它是取证工具，不进观感账 */
private val ProbeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 9.sp,
    lineHeight = 11.sp
)

/** 半透黑衬底 + 高亮绿字：亮暗两种预览底上都读得清（取证可读性优先） */
private val ProbeScrim = Color(0xCC000000)
private val ProbeInk = Color(0xFF8FE38F)
