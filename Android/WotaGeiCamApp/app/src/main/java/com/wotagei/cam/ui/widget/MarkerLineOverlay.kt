package com.wotagei.cam.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.wotagei.cam.core.MarkerLineColor
import com.wotagei.cam.core.MarkerLines

/**
 * 站位指示线覆盖层（2026-10-27 用户需求）：WOTA 艺多人排练时标出各站位用的等距竖线。
 *
 * 与 [RefLineOverlay] 同一通道——**纯 View 层绘制，不进录像画面**：录制采集的是相机/GL 面，
 * View 叠加结构上进不了编码器，无需开关防呆。只画线不收手势（控制 在录制页控件簇）。
 *
 * 线的 x 用 [MarkerLines.lineXsFraction] 的比例（居中对称、整组占 spread 比例宽），
 * 映射进与 [RefLineOverlay] 同款的**画面内接矩形**（黑边区域不画线）；线全长（顶到底），
 * 2dp、55% 透明度——"透明"是产品口径（参考线族的颜色都压暗，站位线要显眼但不抢画面）。
 */
@Composable
fun MarkerLineOverlay(
    count: Int,
    spread: Float,
    color: MarkerLineColor,
    aspect: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val xs = MarkerLines.lineXsFraction(count, spread)
        if (xs.isEmpty()) return@Canvas
        // 内接矩形：复用 RefLineOverlay 的同一条算式（私有 → internal，两覆盖层共用一份口径，
        // 避免"黑边区域画不画线"在两族参考图形上分叉）
        val box = inscribedBox(aspect)
        if (box == Rect.Zero) return@Canvas
        val lineColor = Color(color.argb.toInt()).copy(alpha = 0.55f)
        for (x in xs) {
            val px = box.left + box.width * x
            drawLine(
                lineColor,
                Offset(px, box.top),
                Offset(px, box.bottom),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}
