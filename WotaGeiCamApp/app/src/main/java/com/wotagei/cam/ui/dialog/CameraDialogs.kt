package com.wotagei.cam.ui.dialog

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wotagei.cam.R
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.PeakingColor
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.aspectOf
import com.wotagei.cam.core.forcedShutterOutOfRange
import com.wotagei.cam.core.shutterCeilingNs
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.WotaSettings
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.theme.AcrylicScrim
import com.wotagei.cam.ui.theme.TextScaleLayer
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.widget.TierItem
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 录制页的覆盖式面板（06 文档 §5）：镜头 / 参考线 / 监看。
 *
 * 单值参数（分辨率、帧率、快门、ISO、EV、白平衡、变焦、对焦、码率）不再走这里的整页抽屉，
 * 改成了贴着控件弹出的就近胶囊，见 `ui/CameraPills.kt` 与 `ui/design/PillPopup.kt`。
 *
 * 全部走「同层覆盖」而不是独立窗口：系统弹窗的遮罩会盖住取景器，而参考线/斑马纹/峰值要求边改边看
 * （03 文档 §4 参考线只在 View 层绘制、不进录像）。所以每个面板从调用方拿到与预览同尺寸的
 * [modifier]（CameraScreen 传 `matchParentSize()`），面板自身只做半透明底 + 滚动内容。
 *
 * 面板**不用** `Modifier.blur`，也不是因为旧文档那句"预览层之上禁模糊"（那条红线已被 docs/plan/14 §五
 * 作废）：`Modifier.blur` / `RenderEffect` 是 API 31+，minSdk 29 / 主测机 API 30 上它根本不渲染，
 * 而面板要的"近实底保证长列表可读"本来就比玻璃更实。取景 HUD 那层真·透光毛玻璃走的是另一条路
 * ——GL 自绘（`camera/FrostBlurChain` + `FrostPlatePass`），跟这些面板无关。
 */

// ------------------------------------------------------------------ 枚举 → 文案

@StringRes
internal fun lensLabelRes(type: LensType): Int = when (type) {
    LensType.SUPER_WIDE -> R.string.cam_lens_super_wide
    LensType.WIDE -> R.string.cam_lens_wide
    LensType.TELEPHOTO -> R.string.cam_lens_telephoto
    LensType.SUPER_TELEPHOTO -> R.string.cam_lens_super_telephoto
    LensType.AUTO -> R.string.cam_lens_auto
    LensType.FRONT -> R.string.cam_lens_front
}

@StringRes
internal fun aeLabelRes(mode: AeMode): Int = when (mode) {
    AeMode.AUTO -> R.string.cam_ae_auto
    AeMode.MANUAL -> R.string.cam_ae_manual
    AeMode.LOCK -> R.string.cam_ae_lock
}

@StringRes
internal fun afLabelRes(mode: AfMode): Int = when (mode) {
    AfMode.CONTINUOUS_VIDEO -> R.string.cam_af_continuous_video
    AfMode.CONTINUOUS_PICTURE -> R.string.cam_af_continuous_picture
    AfMode.MACRO -> R.string.cam_af_macro
    AfMode.MANUAL -> R.string.cam_af_manual
}

@StringRes
internal fun wbLabelRes(preset: WbPreset): Int = when (preset) {
    WbPreset.AUTO -> R.string.cam_wb_auto
    WbPreset.INCANDESCENT -> R.string.cam_wb_incandescent
    WbPreset.FLUORESCENT -> R.string.cam_wb_fluorescent
    WbPreset.WARM_FLUORESCENT -> R.string.cam_wb_warm_fluorescent
    WbPreset.DAYLIGHT -> R.string.cam_wb_daylight
    WbPreset.CLOUDY_DAYLIGHT -> R.string.cam_wb_cloudy
    WbPreset.TWILIGHT -> R.string.cam_wb_twilight
    WbPreset.SHADE -> R.string.cam_wb_shade
    WbPreset.MANUAL -> R.string.cam_wb_manual
}

@StringRes
internal fun flashLabelRes(flash: Flash): Int = when (flash) {
    Flash.OFF -> R.string.cam_flash_off
    Flash.ON -> R.string.cam_flash_on
    Flash.AUTO -> R.string.cam_flash_auto
    Flash.TORCH -> R.string.cam_flash_torch
}

@StringRes
internal fun stabLabelRes(mode: Stabilize): Int = when (mode) {
    Stabilize.OFF -> R.string.cam_stab_off
    Stabilize.OIS -> R.string.cam_stab_ois
    Stabilize.EIS -> R.string.cam_stab_eis
    Stabilize.OIS_EIS -> R.string.cam_stab_ois_eis
}

@StringRes
internal fun renderLabelRes(mode: RenderMode): Int = when (mode) {
    RenderMode.GPU -> R.string.cam_render_gpu
    RenderMode.DIRECT -> R.string.cam_render_direct
}

@StringRes
internal fun effectLabelRes(effect: FrameEffect): Int = when (effect) {
    FrameEffect.NONE -> R.string.cam_effect_none
    FrameEffect.ZEBRA -> R.string.cam_effect_zebra
    FrameEffect.PEAKING -> R.string.cam_effect_peaking
}

@StringRes
internal fun peakingColorLabelRes(color: PeakingColor): Int = when (color) {
    PeakingColor.WHITE -> R.string.cam_pk_white
    PeakingColor.GREEN -> R.string.cam_pk_green
    PeakingColor.BLUE -> R.string.cam_pk_blue
    PeakingColor.ORANGE -> R.string.cam_pk_orange
    PeakingColor.RED -> R.string.cam_pk_red
}

// ------------------------------------------------------------------ 数值格式化
// 数字与国际单位缩写不算界面中文文案，集中在此供录制页/设置页共用，避免各处重复换算

/** 快门：纳秒 → `1/25`；≥0.1s（[WotaTiers.SHUTTER_CAP_NS] 防手抖上限）走秒制 */
internal fun shutterText(ns: Long): String {
    if (ns <= 0L) return "-"
    return if (ns >= WotaTiers.SHUTTER_CAP_NS) {
        String.format(Locale.US, "%.1fs", ns.toDouble() / WotaTiers.NS_PER_SECOND)
    } else {
        "1/${(WotaTiers.NS_PER_SECOND.toFloat() / ns).roundToInt()}"
    }
}

internal fun bitrateText(bps: Int): String =
    String.format(Locale.US, "%dM", (bps / 1_000_000f).roundToInt().coerceAtLeast(1))

internal fun sampleRateText(sr: Int): String = when (sr) {
    44_100 -> "44.1k"
    32_000 -> "32k"
    48_000 -> "48k"
    else -> String.format(Locale.US, "%.1fk", sr / 1000f)
}

internal fun evText(steps: Int, evStep: Float): String =
    String.format(Locale.US, "%+.1fEV", steps * evStep)

internal fun sizeText(size: Size): String =
    "${size.width}x${size.height} ${aspectOf(size.width, size.height)}"

internal fun freeSpaceText(mb: Long): String = com.wotagei.cam.core.freeSpaceShort(mb)

/** 斑马纹 UI 域 80–100 ↔ shader 域 0.6–1.0（、03 文档 §3.5） */
internal fun zebraUiOfThreshold(threshold: Float): Int =
    (80 + (threshold - 0.6f) / 0.4f * 20f).roundToInt().coerceIn(80, 100)

internal fun zebraThresholdOfUi(ui: Int): Float =
    (0.6f + (ui - 80) / 20f * 0.4f).coerceIn(0.6f, 1.0f)


/** 页面内统一的参数流读取入口（lifecycle 感知，后台不采集） */
@Composable
internal fun <T> StateFlow<T>.observed(): State<T> = collectAsStateWithLifecycle()

// ------------------------------------------------------------------ 共用小件

// ---- 半模态尺寸纪律（Top8 #8；来源 bindsheet.md 直板机/平板通用尺寸规格节，design-spec §8.2）----
// 档位收成常量供本包（CurveSheet 等）共用，禁止逐面板手调；含窗口/inset 的量一律运行时取，
// 禁止按方向写死（本机横屏挖孔在左右两侧，同一枚写死方向的常量在两个横屏方向必错一次）。
//   宽 480dp 上限        ——「手机横屏时保持宽度 480vp 最大宽度」/ 平板「宽度默认为 480vp」
//   最小高 320dp         ——「半模态最小高度为 320vp」
//   最大高=短边 90%      ——「半模态最大高度为屏幕短边的 90% 高度」
//   距信号栏 8dp         ——Size-Regular「高度距离信号栏保持 8vp 间距」（inset 运行时读）
//   >600dp 短边转居中    ——「大于 600vp 设备断点以上时…屏幕居中显示」（断点输入是**短边**：
//                          平板/折叠屏展开态才居中，手机横竖屏一律底贴屏；2026-10-02 用户裁决，
//                          原「宽度>600」会让手机横屏常驻居中；短边运行时取，不按方向写死）
internal val SheetMaxWidth = 480.dp
internal val SheetMinHeight = 320.dp
internal const val SheetMaxShortRatio = 0.9f
internal val SheetSignalGap = 8.dp
/** 弹窗内间距 12dp（⑤；design-spec §5.1 `ohos_id_default_padding_start/end`=12vp） */
internal val SheetInnerPadding = 12.dp
internal const val SheetCenterBreakpoint = 600

/**
 * 半模态面板的最大高（dp）：短边 90% 与「窗口高 − 顶部避让」取小。
 * 顶部避让 = max(信号栏 inset + 8dp, [PanelTopKeep] 兜底)——inset 运行时读；本机横屏
 * 挖孔在左右两侧时 top inset 自然为 0，48dp 的顶栏兜底接手（PanelTopKeep 的已知债仍有效）。
 * 面板本体（[BottomPanel]）与曲线画布（`CurveSheet.canvasSide`）共用同一份口径，不许各算各的。
 */
@Composable
internal fun sheetMaxHeight(): Dp {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val shortSide = minOf(config.screenWidthDp, config.screenHeightDp).dp
    val topInset = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
    val topKeep = maxOf(topInset + SheetSignalGap, PanelTopKeep)
    return (minOf(shortSide * SheetMaxShortRatio, config.screenHeightDp.dp - topKeep))
        .coerceAtLeast(0.dp)
}

@Composable
internal fun BottomPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val motion = LocalMotion.current
    // ⑧ 第 5 条：断点输入是短边（min(宽,高)，随旋转变正是所需语义）——平板/折叠屏展开态短边
    // >600dp 才转居中，手机横竖屏短边一律 <600dp 底贴屏（2026-10-02 用户裁决，原「宽度>600」
    // 会让手机横屏 800dp 命中常驻居中）
    val config = LocalConfiguration.current
    val centered = minOf(config.screenWidthDp, config.screenHeightDp) > SheetCenterBreakpoint
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.float),
            exit = fadeOut(motion.float),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(Modifier.fillMaxSize().background(AcrylicScrim).clickable(onClick = onDismiss))
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(motion.offset) { it },
            exit = slideOutVertically(motion.offset) { it },
            modifier = Modifier.align(if (centered) Alignment.Center else Alignment.BottomCenter)
        ) {
            val ctx = LocalContext.current
            val dialogScale = WotaSettings.textScale(
                remember(ctx) { WotaSettings.of(ctx) },
                WotaSettings.KEY_TEXT_SCALE_DIALOG
            )
            TextScaleLayer(dialogScale) {
                // 整块限高到「顶栏以下」并只让参数区滚：以前是整块 verticalScroll，
                // 参数一多就撑满全高、标题被顶到屏幕最上沿压住录制页顶栏文字（真机截图核对）
                val sheetMaxH = sheetMaxHeight()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = SheetMaxWidth)
                        .heightIn(min = minOf(SheetMinHeight, sheetMaxH), max = sheetMaxH)
                        // WotaShape.dialog 是 Dp 件位值（24dp）不是 Shape，clip 要 Shape 就地包。
                        // 口径备注：官方底贴屏面板只圆**上缘** 24dp，这里仍是全形状四角皆圆（本轮改动最小）；
                        // 待令牌层出"仅上缘"半径档后一行切换
                        .clip(RoundedCornerShape(WotaShape.dialog))
                        .background(AcrylicScrim)
                        .safeDrawingPadding()
                        .padding(SheetInnerPadding)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = WotaText)
                            subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    // 正文压 AcrylicScrim(0xB3)：textLo worst ≈2.8、textMid worst ≈4.26 都不够 4.5，
                                    // 面板正文档只有 textHi（worst 5.71）
                                    color = WotaText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = WotaTextDim
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    DividerLine()
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * 面板顶部要给录制页顶栏留出的高度——**兜底近似，不是实测值**：
 * 顶栏设计高 44（`ui/HudLayer` 的 `TopBarSpace`：38 圆钮 + 上下 4+2）+ 4 的一次固定加成。
 *
 * 已知债：顶栏实测更高时本预留不足会压面板顶——能力/权限告警条约 22dp 出现、或字体缩放 120%
 * 把顶栏撑高，都是常见场景。正解是由录制页把 `topBarH` 实测（`CameraScreen` 里
 * `HudTopChrome.onSizeChanged` 回报、现只喂 `HudLayout` 的 `topAvoidDp` 那份）经 CompositionLocal
 * 传入本面板，待跨文件接线（provide 点在 `CameraScreen`，不属本轮可改文件；10-01 清查记录）。
 * 上账的「顶 inset」那一笔已由 [sheetMaxHeight] 运行时读掉（⑧ 距信号栏 8dp），这里只剩 topBarH 一笔。
 */
private val PanelTopKeep = 48.dp

@Composable
internal fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(WotaDivider))
}

@Composable
internal fun SmallTextButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        // 动作小字不压 AcrylicScrim 用 accent：亮画面 worst 只有 ≈2.2；改 textHi（scrim 上
        // 最稳的一档，worst 5.71）+ 下划线保留「可点」语义，accent 强调交给需要时的图形件
        color = WotaText,
        textDecoration = TextDecoration.Underline,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/**
 * 快门药丸：产品档 ∩ 本机范围，交集外灰显（02 文档 §7 档位求交）。
 *
 * 可选性的上限由 **[shutterCeilingNs]** 按 [fpsHi] 现算，**不再**读按用户档位算出来的
 * `ParamState.range`——两者会在「用户选 24fps、设备只给 [15,30]（非精确）」时分裂：
 * 旧口径把 1/24 判成可选并打 `※`，而下发/回写（`RequestApplier` 与 `Camera2Engine.clampShutterToFrame`）
 * 都按**实际生效帧周期** 30fps 把请求夹成 1/30，露出一个点不住的死档位。现在判据与回写同源：
 * 列表里可选 = 下发不会被帧周期改掉。
 *
 * [deviceNs] 是本机曝光范围（`ability.exposureNs`）；[fpsHi] 是**实际生效的帧率上界**
 * （`pickFpsRange(...)` 的结果 `.hi`，与 `applyExposure` / 引擎 `normal.hi` 同一个量）。
 * [approxMark] 是 `※`：**强制档**（1/24、1/25）超出设备范围时在档位标签上打上它。
 * 注意 `※` 与「可选」绑定——`forced` 只在 `supported` 为真时参与 label，灰显档一律不带标
 * （帧率侧的 `※` 也是同样的「只有近似才打、灰显绝不打」规则）。
 *
 * 形参 [fpsHi]、[approxMark] **不给默认值**：漏传就编译不过
 * （与 §69「控件锚点无默认值必传」同一条纪律）。
 */
internal fun shutterItems(
    deviceNs: RangeI?,
    fpsHi: Int,
    approxMark: String
): List<TierItem> {
    val exp = deviceNs?.takeIf { it.ok() }
    return WotaTiers.SHUTTER_DENOM.map { denom ->
        val ns = WotaTiers.NS_PER_SECOND / denom
        val hi = exp?.let { shutterCeilingNs(it.hi.toLong(), fpsHi) }
        val supported = exp != null && hi != null && ns in exp.lo.toLong()..hi
        val forced = exp != null && supported && forcedShutterOutOfRange(ns, exp)
        TierItem(
            ns.toInt(),
            "1/$denom" + if (forced) approxMark else "",
            supported
        )
    }
}
