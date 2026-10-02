package com.wotagei.cam.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.camera.LevelSensor
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.MonoStyle
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
 * 姿态仪卡片，两种显示模式（用户 2026-10-01 指令，点击整卡切换）：
 * - 气泡模式（默认）：圆形人工地平仪 + 「已回正/偏斜中」状态行，即 2026-09-28 六项第 2 条的画法，逐字未动；
 * - 数值模式：不画地平仪圆，改两行等宽数字「俯仰 +12.3°」「倾角 -1.6°」（标签 + 带符号一位小数），
 *   状态行保留在数字下方——用户砍的是数字不是状态行，它仍是"传感器在不在工作"的唯一信号。
 *
 * 数值是会话态（[numeric]）：点击切换、不持久化，退出页面重置回气泡模式。
 * roll/pitch 本身就是度数，定义域由 `LevelMath` 保证：roll 经 foldToHalfCircle 折回 -90..90，
 * pitch 是 atan2(-sz, planar≥0) 天然落在 -90..90，所以原样显示、不夹取不截断。
 *
 * 宽度教训（git 1920335 删掉的旧 AttitudeReadout）：当年在右栏 54dp 固定宽里被裁
 * （「横滚 -1.6°」超宽）。本实现不再踩：Dock 是包内容宽，数值行不写死宽度、行文案最宽
 * 也就「俯仰 +90.0°」约 68dp（labelSmall 等宽，见 [AngleRow]）；两模式宽度不同引发的
 * 底板横移交给 Dock 的 animateContentSize 平滑（10-01 布局批）。
 *
 * [enabled] 为 false 时整块不组合，与 #54 的 `CamPill.LEVEL` 一起决定显隐。
 *
 * [card] 决定是否自绘那层底板（S3-4）：单独摆在画面上要有一层底才可读，默认 true 保持旧行为；
 * 放进左右竖 Dock 时由 Dock 的底板统一承托，传 false，否则两层 hudScrim 叠成"卡中卡"。
 */
@Composable
fun AttitudeCard(roll: Float, pitch: Float, enabled: Boolean, card: Boolean = true) {
    if (!enabled) return
    val level = kotlin.math.abs(roll) <= LevelSensor.LEVEL_TOLERANCE_DEG &&
        kotlin.math.abs(pitch) <= LevelSensor.LEVEL_TOLERANCE_DEG
    // 会话态：点击整卡在气泡/数值两种模式间切换，不持久化，退出页面即重置
    var numeric by remember { mutableStateOf(false) }
    Column(
        Modifier
            .then(if (card) Modifier.wotaCard(WotaShape.medium) else Modifier)
            // 宽度一律包内容：气泡 54dp ↔ 数值约 76dp，切换时 Dock 底板的横移由
            // HudDockZone 的 animateContentSize（10-01 布局批）平滑化。这里不设 min 守卫——
            // 两种模式的自然宽度都大于 46dp，写了也是永不生效的假断言（审查 P3）
            .clickable(
                onClickLabel = stringResource(R.string.level_mode_toggle),
                role = Role.Button
            ) { numeric = !numeric }
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (numeric) {
            AngleRow(R.string.level_tag_pitch, pitch)
            AngleRow(R.string.level_tag_roll, roll)
        } else {
            ArtificialHorizon(roll, pitch)
        }
        Text(
            text = stringResource(if (level) R.string.level_state_level else R.string.level_state_tilt),
            style = MaterialTheme.typography.labelSmall,
            color = if (level) WotaAccent else WotaWarn,
            maxLines = 1
        )
    }
}

/** 数值模式的一行：`俯仰 +12.3°`，等宽字体 + textHi 色，行宽包内容不写死（1920335 裁宽教训） */
@Composable
private fun AngleRow(labelRes: Int, deg: Float) {
    Text(
        text = stringResource(labelRes) + " " + stringResource(R.string.level_angle_value, deg),
        style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
        color = WotaText,
        maxLines = 1
    )
}
