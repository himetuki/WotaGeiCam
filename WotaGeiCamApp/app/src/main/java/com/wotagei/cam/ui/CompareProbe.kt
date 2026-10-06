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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wotagei.cam.BuildConfig
import kotlinx.coroutines.delay

/**
 * 对比播放**黑屏取证探针**（仿 [FrostProbe] 的整套套路）。
 *
 * ## 为什么必须有
 * 「对比播放左右侧全黑」已被修过两轮（会话播放意图起播、双窗黑层判定），用户仍报全程黑。
 * 「代码上通」不等于「真机上通」——断点可能在链上任何一环：ExoPlayer 没建、视图没绑上、
 * TextureView 面没 isAvailable、视图被量成 0 尺寸、黑层位被写脏、引擎没在播、素材域判错。
 * 这台机 `screencap` 慢，逐环取证只能让应用把各环的数**写在屏幕上**读。
 *
 * ## 读数判读（每格对应一个断点）
 * - `playerL/R`：引擎是否已建出 ExoPlayer。0 ⇒ attach 没跑到/searchPath 错；
 * - `boundL/R`：引擎登记的视图数。player=1 而 bound=0 ⇒ 面从没 bind 上（绑定契约断）；
 * - `surfL/R`：首个登记视图的 TextureView 是否 isAvailable。bound=1 而 surf=0 ⇒ 面未就绪；
 * - `sizeL/R`：视图当帧尺寸。0x0 ⇒ 布局没量出来/被裁；
 * - `domBlackL/R`：会话黑层位（驱动两窗黑层 Box 的 alpha）。1 ⇒ 该窗被黑层盖住；
 * - `blackL/R`：编排**本拍下发**的黑层决定。与 domBlack 不一致 ⇒ 编排写脏/没写回；
 * - `playingL/R`：两引擎 isPlaying；
 * - `tL/tR/off/T/Tmin/Tmax`：两引擎实时位置、偏移、时间线时刻与上下限；
 * - `stateL/R`：PlaybackState（2=BUFFERING、3=READY、4=ENDED、1=IDLE）。3 而画面黑 ⇒
 *   渲染面问题（surf/size 那三格）；1/2 ⇒ 还没就绪。
 *
 * ## 三条纪律（与 MERGE_HOOK / FROST_PROBE 相同）
 * - 入口只有 adb 的 intent extra（[EXTRA_COMPARE_PROBE]），设置页零调试项；
 * - 不持久化：状态只活在进程内，extra 缺失（从桌面重进）即关闭态；
 * - 只读不改：读的全是既有状态，不给链路加分支、不碰布局账；release 变体恒不可达。
 */

/** adb intent 里那枚 extra 的键；除 adb 之外没有第二个写入方 */
const val EXTRA_COMPARE_PROBE = "wota_compare_probe"

/**
 * 「变体允不允许 + adb 给的原始位 → 探针开不开」——**release 不可达这道闸门的本体**（JVM 可测）。
 * [enabled] 当形参而不是直读 [BuildConfig.COMPARE_PROBE]，让用例两头都能测到（同 [frostProbeEnabledOf]）。
 */
fun compareProbeEnabledOf(enabled: Boolean, raw: Boolean): Boolean = enabled && raw

/** 探针状态：进程内单例，不写 prefs。写入方只有 MainActivity 的 intent 一条路 */
object CompareProbe {

    /** 当前是否开着；正式 release 恒 false（闸门见 [compareProbeEnabledOf]） */
    var enabled by mutableStateOf(false)
        private set

    fun apply(raw: Boolean) {
        enabled = compareProbeEnabledOf(BuildConfig.COMPARE_PROBE, raw)
    }
}

/**
 * 一格快照：全部来自既有状态，探针只读。`domBlack*`=会话黑层位、`cmdBlack*`=编排本拍决定。
 */
internal data class CompareProbeState(
    val playerL: Boolean,
    val playerR: Boolean,
    val boundViewsL: Int,
    val boundViewsR: Int,
    val surfaceAvailL: Boolean,
    val surfaceAvailR: Boolean,
    val viewSizeL: Pair<Int, Int>,
    val viewSizeR: Pair<Int, Int>,
    val domBlackL: Boolean,
    val domBlackR: Boolean,
    val cmdBlackL: Boolean,
    val cmdBlackR: Boolean,
    val playingL: Boolean,
    val playingR: Boolean,
    val tL: Long,
    val tR: Long,
    val off: Long,
    val t: Long,
    val tMin: Long,
    val tMax: Long,
    val stateL: Int,
    val stateR: Int
)

/** 探针轮询周期：与 FrostProbe 同一档频率（5Hz 读十来个只读值，成本可忽略） */
private const val COMPARE_PROBE_POLL_MS = 200L

/** 快照 → 上屏等宽文本（**纯函数**，JVM 直测；不是 Composable，只读快照） */
internal fun compareProbeText(s: CompareProbeState): String = buildString(320) {
    append("CMPPROBE")
    append("\nplayerL=").append(b01(s.playerL)).append(" playerR=").append(b01(s.playerR))
    append("\nboundL=").append(s.boundViewsL).append(" boundR=").append(s.boundViewsR)
    append("\nsurfL=").append(b01(s.surfaceAvailL)).append(" surfR=").append(b01(s.surfaceAvailR))
    append("\nsizeL=").append(s.viewSizeL.first).append('x').append(s.viewSizeL.second)
        .append(" sizeR=").append(s.viewSizeR.first).append('x').append(s.viewSizeR.second)
    append("\ndomBlackL=").append(b01(s.domBlackL)).append(" domBlackR=").append(b01(s.domBlackR))
    append("\nblackL=").append(b01(s.cmdBlackL)).append(" blackR=").append(b01(s.cmdBlackR))
    append("\nplayingL=").append(b01(s.playingL)).append(" playingR=").append(b01(s.playingR))
    append("\ntL=").append(s.tL).append(" tR=").append(s.tR).append(" off=").append(s.off)
    append("\nT=").append(s.t).append(" Tmin=").append(s.tMin).append(" Tmax=").append(s.tMax)
    append("\nstateL=").append(s.stateL).append(" stateR=").append(s.stateR)
}

private fun b01(v: Boolean): Int = if (v) 1 else 0

/**
 * 探针叠层：探针开着才组合（关闭态一个节点都不存在），每 [COMPARE_PROBE_POLL_MS] 刷新读数。
 * 挂在对比页根 Box 的 TopStart，只画不收指（无 clickable）。
 */
@Composable
internal fun CompareProbeOverlay(snapshot: () -> CompareProbeState, modifier: Modifier = Modifier) {
    if (!CompareProbe.enabled) return
    // 探针每拍重算文本：stamp 只负责触发重组，不进任何布局账
    var stamp by remember { mutableIntStateOf(0) }
    val snap by rememberUpdatedState(snapshot)
    LaunchedEffect(Unit) {
        while (true) {
            delay(COMPARE_PROBE_POLL_MS)
            stamp++
        }
    }
    val text = remember(stamp) { compareProbeText(snap()) }
    Box(
        modifier
            .background(CompareProbeScrim, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(text, style = CompareProbeStyle, color = CompareProbeInk)
    }
}

/** 探针专用样式：等宽小字（读数对齐），与令牌体系无关——它是取证工具，不进观感账 */
private val CompareProbeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 9.sp,
    lineHeight = 11.sp
)

/** 半透黑衬底 + 高亮绿字：亮暗两种画面底上都读得清 */
private val CompareProbeScrim = Color(0xCC000000)
private val CompareProbeInk = Color(0xFF8FE38F)
