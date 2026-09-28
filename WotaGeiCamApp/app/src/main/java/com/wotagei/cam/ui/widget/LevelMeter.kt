package com.wotagei.cam.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wotagei.cam.R
import com.wotagei.cam.camera.LevelSensor
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.theme.WotaWarn

/**
 * 水平仪 + 俯仰仪双表（需求 24、25 行；03 文档 §5）。
 *
 * - 横条：[roll]，过心线 + 可动指针 + 等宽数字，水平容差带用主色半透明标出；
 * - 竖条：[pitch]，指针向上代表仰角（与数字同号）。
 * - 配色：左右水平（`|roll| < LevelSensor.LEVEL_TOLERANCE_DEG`）时指针用 [WotaAccent]，偏斜用 [WotaWarn]。
 *
 * 指针量程是 UI 手感值（±30° / ±45°），只做「贴边」截断，数字仍报真实角度；
 * 传感器原始范围 ±90° 直接画满会让小角度的抖动几乎看不见。
 *
 * @param compact 紧凑模式（录制页右侧仪表列，可用宽度约 40dp）：表与数字竖排、隐去文字标签；
 *    false 为宽版横排，带「横滚/俯仰」标签与水平状态文字。
 */
@Composable
fun LevelMeter(
    roll: Float,
    pitch: Float,
    modifier: Modifier = Modifier,
    compact: Boolean = true
) {
    val level = LevelSensor.isLevelOf(roll)
    val barColor = if (level) WotaAccent else WotaWarn
    val rollText = stringResource(R.string.level_angle_value, roll)
    val pitchText = stringResource(R.string.level_angle_value, pitch)
    // 数字一律等宽，避免角度跳动
    val numberStyle = MonoStyle.copy(
        fontSize = if (compact) 10.sp else MaterialTheme.typography.labelMedium.fontSize
    )

    if (compact) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Canvas(
                Modifier
                    .width(COMPACT_ROLL_WIDTH.dp)
                    .height(COMPACT_BAR_THICK.dp)
            ) { drawRollBar(roll, barColor, level) }
            NumberText(rollText, numberStyle, barColor)
            Canvas(
                Modifier
                    .width(COMPACT_BAR_THICK.dp)
                    .height(COMPACT_PITCH_HEIGHT.dp)
            ) { drawPitchBar(pitch, barColor) }
            NumberText(pitchText, numberStyle, barColor)
        }
        return
    }

    val labelStyle = MaterialTheme.typography.labelSmall
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.level_tag_roll), style = labelStyle, color = WotaTextDim)
            Canvas(
                Modifier
                    .width(FULL_ROLL_WIDTH.dp)
                    .height(FULL_BAR_THICK.dp)
            ) { drawRollBar(roll, barColor, level) }
            NumberText(rollText, numberStyle, barColor)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.level_tag_pitch), style = labelStyle, color = WotaTextDim)
            Canvas(
                Modifier
                    .width(FULL_BAR_THICK.dp)
                    .height(FULL_PITCH_HEIGHT.dp)
            ) { drawPitchBar(pitch, barColor) }
            NumberText(pitchText, numberStyle, barColor)
        }
        Text(
            stringResource(if (level) R.string.level_state_level else R.string.level_state_tilt),
            style = labelStyle,
            color = barColor
        )
    }
}

@Composable
private fun NumberText(text: String, style: TextStyle, color: Color) {
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 录制页右上角小徽标（紧凑模式的 [LevelMeter] 加半透明底）。
 *
 * 只负责自身外观，摆放位置由调用方决定（相机页放在右侧仪表列顶部）。
 * [enabled] 为 false 时整块不渲染 —— 把「水平仪开关 + 本机有无加速度计」一起交给调用方判定。
 */
@Composable
fun LevelBadge(roll: Float, pitch: Float, enabled: Boolean) {
    if (!enabled) return
    Box(
        Modifier
            .wotaCard(WotaShape.medium)
            .padding(horizontal = 4.dp, vertical = 5.dp)
    ) {
        LevelMeter(roll = roll, pitch = pitch, compact = true)
    }
}

/** 横条：轨道 + 容差带 + 过心线 + 指针 */
private fun DrawScope.drawRollBar(roll: Float, barColor: Color, level: Boolean) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val cx = w / 2f
    val travel = cx - POINTER_STROKE.dp.toPx() / 2f
    drawRect(
        color = WotaDivider.copy(alpha = TRACK_ALPHA),
        topLeft = Offset(0f, (h - TRACK_THICK.dp.toPx()) / 2f),
        size = Size(w, TRACK_THICK.dp.toPx())
    )
    val band = travel * (LevelSensor.LEVEL_TOLERANCE_DEG / ROLL_FULL_SCALE_DEG)
    drawRect(
        color = WotaAccent.copy(alpha = if (level) BAND_ALPHA_ON else BAND_ALPHA_OFF),
        topLeft = Offset(cx - band, 0f),
        size = Size(band * 2f, h)
    )
    drawLine(WotaTextDim, Offset(cx, 0f), Offset(cx, h), strokeWidth = CENTER_STROKE.dp.toPx())
    val t = (roll / ROLL_FULL_SCALE_DEG).coerceIn(-1f, 1f)
    val x = cx + travel * t
    drawLine(barColor, Offset(x, 0f), Offset(x, h), strokeWidth = POINTER_STROKE.dp.toPx())
}

/** 竖条：轨道 + 容差带 + 过心线 + 指针（仰角指针向上） */
private fun DrawScope.drawPitchBar(pitch: Float, barColor: Color) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val cy = h / 2f
    val travel = cy - POINTER_STROKE.dp.toPx() / 2f
    drawRect(
        color = WotaDivider.copy(alpha = TRACK_ALPHA),
        topLeft = Offset((w - TRACK_THICK.dp.toPx()) / 2f, 0f),
        size = Size(TRACK_THICK.dp.toPx(), h)
    )
    val band = travel * (LevelSensor.LEVEL_TOLERANCE_DEG / ROLL_FULL_SCALE_DEG)
    drawRect(
        color = WotaAccent.copy(alpha = BAND_ALPHA_OFF),
        topLeft = Offset(0f, cy - band),
        size = Size(w, band * 2f)
    )
    drawLine(WotaTextDim, Offset(0f, cy), Offset(w, cy), strokeWidth = CENTER_STROKE.dp.toPx())
    val t = (pitch / ROLL_FULL_SCALE_DEG).coerceIn(-1f, 1f)
    val y = cy - travel * t
    drawLine(barColor, Offset(0f, y), Offset(w, y), strokeWidth = POINTER_STROKE.dp.toPx())
}

// 以下全是界面手感值，不是机型能力，故不来自 CameraCharacteristics
private const val ROLL_FULL_SCALE_DEG = 30f
private const val PITCH_FULL_SCALE_DEG = 45f
private const val TRACK_THICK = 1f
private const val CENTER_STROKE = 1f
private const val POINTER_STROKE = 2.5f
private const val TRACK_ALPHA = 0.55f
private const val BAND_ALPHA_ON = 0.28f
private const val BAND_ALPHA_OFF = 0.16f

/** 紧凑版给右侧 54dp 仪表列用：表 34dp 宽 + 徽标左右各 4dp 内边距，合计不超出可用宽度 */
private const val COMPACT_ROLL_WIDTH = 34
private const val COMPACT_BAR_THICK = 10
private const val COMPACT_PITCH_HEIGHT = 26
private const val FULL_ROLL_WIDTH = 176
private const val FULL_BAR_THICK = 22
private const val FULL_PITCH_HEIGHT = 86
