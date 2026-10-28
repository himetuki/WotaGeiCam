package com.wotagei.cam.core

/**
 * 曲线面板边长的组合期钳制（29 §1.3 F1，纯函数 JVM 全测）。
 *
 * prefs 读回的 sideDp 已经过读取侧钳（负数/NaN 回 200），这里带**组合期才有**的 maxSide
 * （弹窗可用区）做第二次钳：持久值比当前窗口大（换小屏/分屏）时缩回可用区，
 * 比最小值小（手改 prefs）时抬回最小——两边都不许破面板可用性。
 */
object CurvePanelSize {

    /** 设计档边长（与 CurvePopup 的 CurveSide 同源：200dp），重置写回它 */
    const val DEFAULT_SIDE_DP = 200f

    fun clamp(sideDp: Float, maxSideDp: Float, minSideDp: Float): Float {
        val base = if (sideDp.isNaN() || sideDp < 0f) DEFAULT_SIDE_DP else sideDp
        val hi = maxOf(maxSideDp, minSideDp)          // maxSide 坏域（< 最小值）时以最小值为准
        return base.coerceIn(minSideDp, hi)
    }
}
