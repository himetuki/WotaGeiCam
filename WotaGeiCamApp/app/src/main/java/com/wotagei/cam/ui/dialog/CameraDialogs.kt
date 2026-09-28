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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wotagei.cam.R
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.PeakingColor
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.aspectOf
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.WotaSettings
import com.wotagei.cam.ui.theme.AcrylicScrim
import com.wotagei.cam.ui.theme.TextScaleLayer
import com.wotagei.cam.ui.theme.WotaAccent
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
 * [modifier]（CameraScreen 传 `matchParentSize()`），面板自身只做半透明底 + 滚动内容，
 * 且不使用 `Modifier.blur`（预览层之上禁用模糊，见 06 文档 §2）。
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
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val ctx = LocalContext.current
            val dialogScale = WotaSettings.textScale(
                remember(ctx) { WotaSettings.of(ctx) },
                WotaSettings.KEY_TEXT_SCALE_DIALOG
            )
            TextScaleLayer(dialogScale) {
                BoxWithConstraints {
                    // 整块限高到「顶栏以下」并只让参数区滚：以前是整块 verticalScroll，
                    // 参数一多就撑满全高、标题被顶到屏幕最上沿压住录制页顶栏文字（真机截图核对）
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                            .heightIn(max = maxHeight - PanelTopKeep)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(AcrylicScrim)
                            .safeDrawingPadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium, color = WotaText)
                                subtitle?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = WotaTextDim,
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
}

/** 面板顶部要给录制页顶栏留出的高度 */
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
        color = WotaAccent,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** 快门药丸：产品档 ∩ 本机范围，交集外灰显（02 文档 §7 档位求交） */
internal fun shutterItems(shutter: ParamState<Long>): List<TierItem> {
    val lo = shutter.range?.start
    val hi = shutter.range?.endInclusive
    return WotaTiers.SHUTTER_DENOM.map { denom ->
        val ns = WotaTiers.NS_PER_SECOND / denom
        TierItem(ns.toInt(), "1/$denom", lo != null && hi != null && ns in lo..hi)
    }
}
