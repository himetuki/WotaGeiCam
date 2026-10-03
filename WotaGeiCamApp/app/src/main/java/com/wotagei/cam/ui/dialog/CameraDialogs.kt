package com.wotagei.cam.ui.dialog

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import com.wotagei.cam.ui.design.SheetCenterBreakpoint
import com.wotagei.cam.ui.design.SheetInnerPadding
import com.wotagei.cam.ui.design.SheetMaxWidth
import com.wotagei.cam.ui.design.SheetMinHeight
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.sheetMaxHeight
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

// 半模态尺寸纪律的五枚常量与 sheetMaxHeight() 已上提到 `ui/design/SheetSpec.kt`：
// 播放侧 CompareHistorySheet 换装同一套纪律时不能反过来 import 录制页包，design 层才是公共家。

@Composable
internal fun BottomPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    // 钉底动作行槽位（不参与滚动）：只有 CurveSheet 一个调用方传。不给默认值的话
    // 将来出现无动作行的面板会被迫传空块，这里允许 null 即可——漏挂动作行的后果是
    // 功能缺失而不是布局炸裂，编译期拦截不了也不必拦
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val motion = LocalMotion.current
    // ⑧ 第 5 条：断点输入是短边（min(宽,高)，随旋转变正是所需语义）——平板/折叠屏展开态短边
    // >600dp 才转居中，手机横竖屏短边一律 <600dp 底贴屏（2026-10-02 用户裁决，原「宽度>600」
    // 会让手机横屏 800dp 命中常驻居中）
    val config = LocalConfiguration.current
    val centered = minOf(config.screenWidthDp, config.screenHeightDp) > SheetCenterBreakpoint
    // BACK 收面板：本组件换掉的那枚 M3 ModalBottomSheet 自带这个语义，别丢——
    // 对比页历史面板开着按 BACK 原先会连整个对比页一起退掉（对比会话两片全丢）。
    // 放在 AnimatedVisibility **外**恒组合：enabled 跟着 visible 走，退出动画一开始就该放行，
    // 否则那一小段窗口里 BACK 会再打一次 onDismiss（重复收面板），而用户想退的是页面。
    // 录制页那几枚面板（CurveSheet 等）顺带获得同一语义；那一页没有任何既有的 BACK 处理器
    // （全仓只有 GalleryScreen 的多选态挂过 BackHandler），所以不冲突、只是补上缺失的那一条。
    BackHandler(enabled = visible) { onDismiss() }
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
                // 整块限高到「顶栏以下」、只让参数区滚、动作行钉底：以前是整块 verticalScroll，
                // 参数一多就撑满全高、标题被顶到屏幕最上沿压住录制页顶栏文字（真机截图核对）；
                // 横屏真机再实测出动作行整行沉到 y≈700-719 零亮像素（t8_07），根因就是动作行
                // 也躺在滚动区里被画布挤出屏——现在把「标题/滚动区/动作行」拆成三段，动作行
                // 排在滚动区之后，结构上永远贴着面板底缘
                val sheetMaxH = sheetMaxHeight()
                Column(
                    Modifier
                        // 宽度两件套的顺序不可换：foundation 1.5.4 的 SizeModifier 是把目标档
                        // **收进传入约束**（target.coerceInto(incoming)），`fillMaxWidth().widthIn(max)`
                        // 会先把 min 顶成窗口宽、widthIn 的 480 被 coerce 回窗口宽成了摆设
                        // （真机横屏实测内容 742dp，t8_07）。先钳后铺：横屏窗口 766dp 被钳到 480
                        // 并由 BottomCenter 水平居中；竖屏 360dp 可用宽用不满 480，fillMaxWidth
                        // 铺满可用宽——「内容宽 = min(可用宽, 480)」就是规格允许的既定退化
                        .widthIn(max = SheetMaxWidth)
                        .fillMaxWidth()
                        // 高度口径：屏幕给得出 320dp 就给足最小高（竖屏 800dp 高 → max 短边 90%
                        // =324 封顶、min=320 生效）；装不下（横屏 360dp 高窗口扣顶栏兜底后
                        // sheetMaxH ≈278dp）时 min 让位于「不超出屏幕」，按可用高给满——
                        // 这是**有意的退化**不是缺陷，此时面板高 = max = 278dp，如实记录
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
                    Column(
                        Modifier
                            // fill=false：内容矮时滚动区收着内容走（面板由 min 高兜底），
                            // 内容高时把剩余空间全让给滚动区——bottomBar 永远排在其后不参与滚动。
                            // （weight 必须挂在 verticalScroll 之前：先占位再滚，反了 weight 拿不到列约束）
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        content()
                    }
                    bottomBar?.invoke()
                }
            }
        }
    }
}

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
