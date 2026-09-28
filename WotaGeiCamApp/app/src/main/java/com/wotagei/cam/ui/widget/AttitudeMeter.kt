package com.wotagei.cam.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.camera.LevelSensor
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.theme.WotaWarn

/** 横滚/俯仰画到地平仪上时的满量程（超出即钳边，不让天地线转出圆外） */
private const val ROLL_FULL_SCALE_DEG = 30f
private const val PITCH_FULL_SCALE_DEG = 45f

private const val HORIZON_SIZE = 46

/**
 * 圆形人工地平仪（用户 2026-09-28 鸿蒙化第 3 条）：天地线随横滚旋转、随俯仰平移，
 * 中心那枚机标固定不动 —— 读的是"机体相对水平面偏了多少"，不是数字本身。
 *
 * 只画不判：容差带、到水平震动等判定仍在 `LevelSensor` 与上层开关里。
 * 旋转/平移走动画档，所以从 -17.8° 到 -16.3° 是滑过去的，不是跳过去的。
 */
@Composable
fun ArtificialHorizon(roll: Float, pitch: Float, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val smoothRoll by animateFloatAsState(roll.coerceIn(-ROLL_FULL_SCALE_DEG, ROLL_FULL_SCALE_DEG), motion.float)
    val smoothPitch by animateFloatAsState(pitch.coerceIn(-PITCH_FULL_SCALE_DEG, PITCH_FULL_SCALE_DEG), motion.float)
    Canvas(modifier.size(HORIZON_SIZE.dp)) {
        val radius = size.minDimension / 2f
        val center = Offset(radius, radius)
        val inner = radius - 1.6.dp.toPx()
        // 环底：1.x 版 DrawScope 的描边要 Stroke，这里直接铺一枚实心圆、内容只画到内半径，
        // 露出来的那一圈就是边框，省掉一个 import 也少一处版本差异
        drawCircle(WotaDivider, radius = radius - 0.8.dp.toPx(), center = center)
        clipPath(Path().apply { addOval(Rect(center.x - inner, center.y - inner, center.x + inner, center.y + inner)) }) {
            rotate(-smoothRoll, pivot = center) {
                val shift = smoothPitch * (inner / PITCH_FULL_SCALE_DEG)
                val y = center.y + shift
                drawRect(
                    WotaAccent.copy(alpha = 0.16f),
                    Offset(center.x - inner * 2f, y - inner * 2f),
                    Size(inner * 4f, inner * 2f)
                )
                drawRect(
                    WotaWarn.copy(alpha = 0.16f),
                    Offset(center.x - inner * 2f, y),
                    Size(inner * 4f, inner * 2f)
                )
                drawLine(WotaText, Offset(center.x - inner, y), Offset(center.x + inner, y), 1.4.dp.toPx())
                listOf(-20f, -10f, 10f, 20f).forEach { deg ->
                    val tick = y + deg * (inner / PITCH_FULL_SCALE_DEG)
                    val half = if (deg % 20f == 0f) inner * 0.28f else inner * 0.16f
                    drawLine(WotaTextDim, Offset(center.x - half, tick), Offset(center.x + half, tick), 1.dp.toPx())
                }
            }
        }
        // 固定机标：左右两翼 + 中心竖线，代表"我"，永远不随地平线转
        drawLine(WotaAccent, Offset(center.x - inner * 0.44f, center.y), Offset(center.x - inner * 0.14f, center.y), 2.2.dp.toPx())
        drawLine(WotaAccent, Offset(center.x + inner * 0.14f, center.y), Offset(center.x + inner * 0.44f, center.y), 2.2.dp.toPx())
        drawLine(WotaAccent, Offset(center.x, center.y - inner * 0.14f), Offset(center.x, center.y + inner * 0.10f), 2.2.dp.toPx())
    }
}

/**
 * 姿态仪卡片：地平仪 + 俯仰/横滚双读数（右侧裸数值的老形态读不出趋势，见鸿蒙化第 3 条）。
 * [enabled] 为 false 时整块不组合，与 #54 的 `CamPill.LEVEL` 一起决定显隐。
 */
@Composable
fun AttitudeCard(roll: Float, pitch: Float, enabled: Boolean) {
    if (!enabled) return
    val level = kotlin.math.abs(roll) <= LevelSensor.LEVEL_TOLERANCE_DEG &&
        kotlin.math.abs(pitch) <= LevelSensor.LEVEL_TOLERANCE_DEG
    Column(
        Modifier
            .wotaCard(WotaShape.medium)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ArtificialHorizon(roll, pitch)
        Text(
            text = stringResource(if (level) R.string.level_state_level else R.string.level_state_tilt),
            style = MaterialTheme.typography.labelSmall,
            color = if (level) WotaAccent else WotaWarn,
            maxLines = 1
        )
        Text(
            text = stringResource(R.string.level_tag_roll) + ' ' +
                stringResource(R.string.level_angle_value, roll),
            style = MaterialTheme.typography.labelSmall,
            color = WotaTextDim,
            maxLines = 1
        )
        Text(
            text = stringResource(R.string.level_tag_pitch) + ' ' +
                stringResource(R.string.level_angle_value, pitch),
            style = MaterialTheme.typography.labelSmall,
            color = WotaTextDim,
            maxLines = 1
        )
    }
}
