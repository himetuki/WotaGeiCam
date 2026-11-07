package com.wotagei.cam.stance

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * 号位叠加（M2c）：判定输出的可视化——画在 previewStage 的 Compose View 层
 * （与站位线同通道，结构性不进成片；严禁接进 GL effect）。
 * 每号位：外框（状态色）+「1号 往左」标签；完成=绿 ✓；缺人=淡色透明人形外廓。
 */
@Composable
fun StanceOverlay(
    output: EngineOutput?,
    modifier: Modifier = Modifier,
) {
    if (output == null || output.slots.isEmpty()) return
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        for (slot in output.slots) {
            val person = slot.person
            if (person != null) {
                // 归一化 → 画布占比（同 PC 试验台：左上角 + 宽高）
                val x1 = (person.cx - person.w / 2) * size.width
                val y1 = (person.by - person.h) * size.height
                val w = person.w * size.width
                val h = person.h * size.height
                val color = when {
                    slot.done -> Color(0xFF22CC66)
                    slot.verdict == Verdict.LEFT || slot.verdict == Verdict.RIGHT -> Color(0xFFFF5555)
                    else -> Color(0xFF55AAFF)
                }
                drawRoundRect(color, topLeft = Offset(x1, y1), size = Size(w, h),
                    cornerRadius = CornerRadius(12f, 12f), style = Stroke(width = 3f))
                val label = if (slot.done) "${slot.slot}号 ✓" else "${slot.slot}号 ${slot.verdict.speak}"
                val text = measurer.measure(
                    label, TextStyle(fontSize = 14.sp, color = color, fontWeight = FontWeight.Bold)
                )
                drawText(text, topLeft = Offset(x1 + 6, y1 + 4))
            } else if (slot.verdict == Verdict.MISSING) {
                // 缺位：淡色透明人形外廓（圆头 + 肩梯形轮廓），画在号位锚点附近
                val anchorX = size.width * 0.5f
                val anchorY = size.height * 0.62f
                val ghost = Color(0x66CCCCCC)
                drawCircle(ghost, radius = 26f, center = Offset(anchorX, anchorY - 60f))
                drawRoundRect(
                    ghost,
                    topLeft = Offset(anchorX - 44f, anchorY - 30f),
                    size = Size(88f, 120f),
                    cornerRadius = CornerRadius(28f, 28f),
                    style = Stroke(width = 3f),
                )
                val text = measurer.measure("${slot.slot}号缺", TextStyle(fontSize = 13.sp, color = ghost))
                drawText(text, topLeft = Offset(anchorX - 30f, anchorY + 96f))
            }
        }
    }
}
