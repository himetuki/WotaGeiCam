package com.wotagei.cam.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.wotagei.cam.ui.design.WotaColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.wotagei.cam.core.RefLineType
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// 与 res/values/colors.xml 的 wota_refline / wota_refline_red 同值；
// Canvas 每帧取资源色会有查表与解码开销，这里直接用令牌常量（03 文档 §4 颜色规范）
private val RefLineColor = WotaColor.refLine
private val RefLineRedColor = WotaColor.refLineRed

/**
 * 取景参考线覆盖层（03 文档第 4 节的 11 档，纯 View 层绘制，不进录像画面）。
 *
 * 与其他 UI 的分工：本组件只画线，不接收手势；`CameraSurface` 在其下方，参数面板在其上方。
 *
 * @param mask `RefLineType.bit` 的位或组合，多档可同时叠加
 * @param aspect 取景画面的宽高比（宽/高）；线画在该画面的内接矩形里，<=0 表示铺满整个组件
 * @param rollDegrees 设备横滚角（`LevelSensor.roll`），只作用于水平线
 * @param showLevelLine 水平线总开关：为 false 时即使 mask 含 HORIZON_LINE 也不画（相机页外的页面可复用）
 * @param modifier 布局修饰；一般传 `Modifier.matchParentSize()` 与预览同尺寸
 */
@Composable
fun RefLineOverlay(
    mask: Int,
    aspect: Float,
    rollDegrees: Float,
    showLevelLine: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val horizonOn = showLevelLine && mask and RefLineType.HORIZON_LINE.bit != 0
        if (mask == 0 && !horizonOn) return@Canvas
        val box = inscribedBox(aspect)

        if (mask and RefLineType.GRID_3X3.bit != 0) drawThirdsGrid(box)
        if (mask and RefLineType.DIAGONAL.bit != 0) drawDiagonals(box)
        if (mask and RefLineType.CROSSHAIR.bit != 0) drawCrosshair(box)
        if (mask and RefLineType.BROADCAST_SAFE.bit != 0) drawBroadcastSafe(box)
        if (mask and RefLineType.SAFE_AREA.bit != 0) drawSafeAreas(box)
        if (mask and RefLineType.GRID.bit != 0) drawSquareGrid(box, GRID_LINES)
        if (mask and RefLineType.CENTER_MARK.bit != 0) drawCenterMark(box)
        if (horizonOn) drawHorizon(box, rollDegrees)

        // 红线最后画：WOTA 走位判定要压在其他线之上
        if (mask and RefLineType.RED_TOP.bit != 0) drawFullWidthLine(box, 0.10f)
        if (mask and RefLineType.RED_MID.bit != 0) drawFullWidthLine(box, 0.50f)
        if (mask and RefLineType.RED_BOTTOM.bit != 0) drawFullWidthLine(box, 0.90f)
    }
}

private const val GRID_LINES = 6

/** 细线 1dp、加粗 2dp（红线规范要求 2dp） */
private fun DrawScope.thin(): Float = 1.dp.toPx()

private fun DrawScope.thick(): Float = 2.dp.toPx()

/** 把参考线约束在画面内容的内接矩形内，避免黑边区域也画线 */
private fun DrawScope.inscribedBox(aspect: Float): Rect {
    val canvasW = size.width
    val canvasH = size.height
    if (canvasW <= 0f || canvasH <= 0f) return Rect(Offset.Zero, Size.Zero)
    if (aspect.isNaN() || aspect <= 0f) return Rect(Offset.Zero, size)
    val ratio = canvasW / canvasH
    val boxW: Float
    val boxH: Float
    if (ratio > aspect) {
        boxH = canvasH
        boxW = boxH * aspect
    } else {
        boxW = canvasW
        boxH = boxW / aspect
    }
    return Rect(
        Offset((canvasW - boxW) / 2f, (canvasH - boxH) / 2f),
        Size(boxW, boxH)
    )
}

/** 三分法：1/3、2/3 处横竖各两条 */
private fun DrawScope.drawThirdsGrid(box: Rect) {
    val w = box.width
    val h = box.height
    for (i in 1..2) {
        val x = box.left + w * i / 3f
        val y = box.top + h * i / 3f
        drawLine(RefLineColor, Offset(x, box.top), Offset(x, box.bottom), strokeWidth = thin())
        drawLine(RefLineColor, Offset(box.left, y), Offset(box.right, y), strokeWidth = thin())
    }
}

/** 两条对角线 + 交点（中心）标圆 */
private fun DrawScope.drawDiagonals(box: Rect) {
    drawLine(RefLineColor, Offset(box.left, box.top), Offset(box.right, box.bottom), strokeWidth = thin())
    drawLine(RefLineColor, Offset(box.right, box.top), Offset(box.left, box.bottom), strokeWidth = thin())
    val center = Offset(box.left + box.width / 2f, box.top + box.height / 2f)
    drawCircle(RefLineColor, radius = 4.dp.toPx(), center = center, style = Stroke(width = thin()))
}

/** 过心十字，中心留 4dp 缺口（留缺口是为了不遮住被摄主体） */
private fun DrawScope.drawCrosshair(box: Rect) {
    val cx = box.left + box.width / 2f
    val cy = box.top + box.height / 2f
    val gap = 4.dp.toPx()
    drawLine(RefLineColor, Offset(cx, box.top), Offset(cx, cy - gap), strokeWidth = thin())
    drawLine(RefLineColor, Offset(cx, cy + gap), Offset(cx, box.bottom), strokeWidth = thin())
    drawLine(RefLineColor, Offset(box.left, cy), Offset(cx - gap, cy), strokeWidth = thin())
    drawLine(RefLineColor, Offset(cx + gap, cy), Offset(box.right, cy), strokeWidth = thin())
}

/** 广播安全框：70% 尺寸细框 + 四角加粗短边 */
private fun DrawScope.drawBroadcastSafe(box: Rect) {
    drawRatioFrame(box, 0.70f, thin())
    val arm = min(box.width, box.height) * 0.06f
    val inner = shrink(box, 0.70f)
    drawCornerTicks(inner, arm, thick())
}

/** 安全区域：90% 与 95% 双层细框 */
private fun DrawScope.drawSafeAreas(box: Rect) {
    drawRatioFrame(box, 0.95f, thin())
    drawRatioFrame(box, 0.90f, thin())
}

/** N×N 细网格（只画内部线，外框由安全区域负责） */
private fun DrawScope.drawSquareGrid(box: Rect, divisions: Int) {
    if (divisions < 2) return
    for (i in 1 until divisions) {
        val x = box.left + box.width * i / divisions
        val y = box.top + box.height * i / divisions
        drawLine(RefLineColor, Offset(x, box.top), Offset(x, box.bottom), strokeWidth = thin())
        drawLine(RefLineColor, Offset(box.left, y), Offset(box.right, y), strokeWidth = thin())
    }
}

/** 中心小方框 + 外侧四角括号 */
private fun DrawScope.drawCenterMark(box: Rect) {
    val side = min(box.width, box.height) * 0.08f
    val cx = box.left + box.width / 2f
    val cy = box.top + box.height / 2f
    drawRect(
        color = RefLineColor,
        topLeft = Offset(cx - side / 2f, cy - side / 2f),
        size = Size(side, side),
        style = Stroke(width = thin())
    )
    val bracket = min(box.width, box.height) * 0.30f
    val arm = min(box.width, box.height) * 0.06f
    drawCornerTicks(
        Rect(Offset(cx - bracket / 2f, cy - bracket / 2f), Size(bracket, bracket)),
        arm,
        thick()
    )
}

/**
 * 水平线：随设备横滚角旋转的过心线。
 * `LevelSensor.roll` 规定「正 = 屏幕右侧偏低」，右侧下沉时真实地平在画面里向右上方抬起；
 * 画布 Y 轴向下，故右端取 `cy - dy`。线长取对角线长度，保证任意角度都横跨画面。
 */
private fun DrawScope.drawHorizon(box: Rect, rollDegrees: Float) {
    val rad = Math.toRadians(rollDegrees.toDouble())
    val cx = box.left + box.width / 2f
    val cy = box.top + box.height / 2f
    val half = hypot(box.width, box.height) / 2f
    val dx = (cos(rad).toFloat() * half)
    val dy = (sin(rad).toFloat() * half)
    drawLine(RefLineColor, Offset(cx - dx, cy + dy), Offset(cx + dx, cy - dy), strokeWidth = thin())
}

/** 满宽横线，用于上/中/下红线（fraction 为距顶比例） */
private fun DrawScope.drawFullWidthLine(box: Rect, fraction: Float) {
    val y = box.top + box.height * fraction
    drawLine(RefLineRedColor, Offset(box.left, y), Offset(box.right, y), strokeWidth = thick())
}

private fun DrawScope.drawRatioFrame(box: Rect, ratio: Float, stroke: Float) {
    val frame = shrink(box, ratio)
    drawRect(
        color = RefLineColor,
        topLeft = frame.topLeft,
        size = frame.size,
        style = Stroke(width = stroke)
    )
}

private fun shrink(box: Rect, ratio: Float): Rect {
    val w = box.width * ratio
    val h = box.height * ratio
    return Rect(Offset(box.left + (box.width - w) / 2f, box.top + (box.height - h) / 2f), Size(w, h))
}

/** 四角各画一横一竖加粗短边 */
private fun DrawScope.drawCornerTicks(box: Rect, arm: Float, stroke: Float) {
    val corners = listOf(
        Offset(box.left, box.top) to Offset(1f, 1f),
        Offset(box.right, box.top) to Offset(-1f, 1f),
        Offset(box.left, box.bottom) to Offset(1f, -1f),
        Offset(box.right, box.bottom) to Offset(-1f, -1f)
    )
    corners.forEach { (point, direction) ->
        drawLine(
            RefLineColor, point,
            Offset(point.x + direction.x * arm, point.y),
            strokeWidth = stroke
        )
        drawLine(
            RefLineColor, point,
            Offset(point.x, point.y + direction.y * arm),
            strokeWidth = stroke
        )
    }
}
