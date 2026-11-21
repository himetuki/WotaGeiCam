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
 *
 * B14：模型输出是**已转正分析帧**的归一化坐标，而画布是**预览内容区**。两者之间隔着
 * 分析流尺寸/裁剪、共享 `SCALER_CROP_REGION`、预览流尺寸/转正/镜像（[StanceOverlayGeometry]）。
 * 没有几何就宁可不画——不再假设「归一化坐标天然对齐预览」。
 */
@Composable
fun StanceOverlay(
    output: EngineOutput?,
    geometry: StanceOverlayGeometry?,
    modifier: Modifier = Modifier,
) {
    if (output == null || output.slots.isEmpty() || geometry == null) return
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        // 预览内容在画布内的等比居中区（信箱黑边）；通常就是整块画布。
        val content = geometry.previewContentRect(size.width, size.height)
        fun px(u: Float): Float = (content.left + u * content.width) * size.width
        fun py(v: Float): Float = (content.top + v * content.height) * size.height
        for (slot in output.slots) {
            val person = slot.person
            if (person != null) {
                // 模型坐标 → 预览内容区坐标（同源裁剪/转正/镜像），再落到画布像素。
                val mapped = geometry.mapAnalysisRect(
                    NormRect(
                        left = person.cx - person.w / 2f,
                        top = person.by - person.h,
                        right = person.cx + person.w / 2f,
                        bottom = person.by,
                    )
                )
                val x1 = px(mapped.left)
                val y1 = py(mapped.top)
                val w = mapped.width * content.width * size.width
                val h = mapped.height * content.height * size.height
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
                // 缺位：淡色透明人形外廓。锚点是**表现层**选择（不是检测坐标），
                // 只保证落在预览内容区内，避免内容有黑边时画到画面外。
                val anchorX = px(0.5f)
                val anchorY = py(0.62f)
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
