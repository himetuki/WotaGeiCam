package com.wotagei.cam.ui.design

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 半模态尺寸纪律（Top8 #8；来源 bindsheet.md 直板机/平板通用尺寸规格节，design-spec §8.2）。
 *
 * 原先这组常量私有在 `ui/dialog/CameraDialogs.kt`（录制页包），播放侧的 CompareHistorySheet
 * 想同规格接不上，只好各用一套 M3 默认值——纪律就漏了一个口。提到 design 层后，
 * 录制页面板、曲线画布、对比页历史面板共用同一份档位，禁止逐面板手调。
 * 含窗口/inset 的量一律运行时取，禁止按方向写死（本机横屏挖孔在左右两侧，
 * 同一枚写死方向的常量在两个横屏方向必错一次）。
 *
 *   宽 480dp 上限        ——「手机横屏时保持宽度 480vp 最大宽度」/ 平板「宽度默认为 480vp」
 *   最小高 320dp         ——「半模态最小高度为 320vp」
 *   最大高=短边 90%      ——「半模态最大高度为屏幕短边的 90% 高度」
 *   距信号栏 8dp         ——Size-Regular「高度距离信号栏保持 8vp 间距」（inset 运行时读）
 *   >600dp 短边转居中    ——「大于 600vp 设备断点以上时…屏幕居中显示」（断点输入是**短边**：
 *                           平板/折叠屏展开态才居中，手机横竖屏一律底贴屏；2026-10-02 用户裁决，
 *                           原「宽度>600」会让手机横屏常驻居中；短边运行时取，不按方向写死）
 */
internal val SheetMaxWidth = 480.dp
internal val SheetMinHeight = 320.dp
internal const val SheetMaxShortRatio = 0.9f
internal val SheetSignalGap = 8.dp

/** 弹窗内间距 12dp（⑤；design-spec §5.1 `ohos_id_default_padding_start/end`=12vp） */
internal val SheetInnerPadding = 12.dp

internal const val SheetCenterBreakpoint = 600

/**
 * 面板顶部要给页面顶栏留出的高度——**兜底近似，不是实测值**：
 * 顶栏设计高 44（录制页 `ui/HudLayer` 的 `TopBarSpace`：38 圆钮 + 上下 4+2）+ 4 的一次固定加成；
 * 播放/对比页顶栏（返回钮一行）同一量级，共用这份兜底。
 *
 * 已知债：顶栏实测更高时本预留不足会压面板顶——能力/权限告警条约 22dp 出现、或字体缩放 120%
 * 把顶栏撑高，都是常见场景。正解是由宿主页把 `topBarH` 实测（`CameraScreen` 里
 * `HudTopChrome.onSizeChanged` 回报、现只喂 `HudLayout` 的 `topAvoidDp` 那份）经 CompositionLocal
 * 传入面板，待跨文件接线（provide 点在 `CameraScreen`，10-01 清查记录）。
 * 上账的「顶 inset」那一笔已由 [sheetMaxHeight] 运行时读掉（⑧ 距信号栏 8dp），这里只剩 topBarH 一笔。
 */
private val PanelTopKeep = 48.dp

/**
 * 半模态面板的最大高（dp）：短边 90% 与「窗口高 − 顶部避让」取小。
 * 顶部避让 = max(信号栏 inset + [SheetSignalGap]，[PanelTopKeep] 兜底)——inset 运行时读；
 * 本机横屏挖孔在左右两侧时 top inset 自然为 0，48dp 的顶栏兜底接手（PanelTopKeep 的已知债仍有效）。
 * 面板本体（录制页 `BottomPanel` 这类自绘半模态）共用同一份口径，不许各算各的。
 * （曲线已换装左侧小弹窗 `CurvePopup`，自带 200dp 设计档，不再吃这份半模态预算。）
 *
 * 真机实测（横屏 1600x720 density 2）：本函数返回 ≈278dp——窗口/配置把挖孔列扣掉后
 * `screenHeightDp` 只报 ≈326，短边 90%（293）不绑定，`screenHeightDp − 48` 这条先到。
 * 278 < 最小高 320：横屏装不下 320dp 时面板按可用高给满，属**有意的退化**（见 BottomPanel）。
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
