package com.wotagei.cam.core

/**
 * 浮层落点（2026-10-27 三轮·站位线）：贴着锚点控件的**右下方**放，右缘与锚点右缘对齐，
 * 但整块内容必须留在右侧保留区（右 Dock 左缘）以左、窗口以内。
 *
 * # 为什么要抽成纯整数函数
 * 站位线的浮层被用户点名改过两次落点（先是漂在画面中间，后又压到右 Dock），
 * 而"贴控件 + 不压 Dock + 不越窗口"是可以纯算术钉住的三条约束——
 * 放进 JVM 单测，改坐标时不必靠真机截图猜。
 *
 * @param rightLimitPx 右缘上限（px，窗口坐标）：调用方传「锚点右缘」与「右侧保留区左缘」的较小值
 * @param anchorBottomPx 锚点下缘（px，窗口坐标）
 * @param popupWidthPx 浮层实测宽
 * @param popupHeightPx 浮层实测高
 * @param windowWidthPx 窗口宽
 * @param windowHeightPx 窗口高
 * @param gapPx 浮层与锚点之间的竖直间隙
 * @param marginPx 距窗口边缘的最小留白
 * @return (x, y)；空间不足时先保左/上留白（已知边界：内容比窗口还宽时右缘必然越界——
 *   固定宽内容请先用 [popupWidthCapPx] 收缩到可用宽，Y55）
 */
fun popupBelowRight(
    rightLimitPx: Int,
    anchorBottomPx: Int,
    popupWidthPx: Int,
    popupHeightPx: Int,
    windowWidthPx: Int,
    windowHeightPx: Int,
    gapPx: Int,
    marginPx: Int
): Pair<Int, Int> {
    val margin = marginPx.coerceAtLeast(0)
    val maxX = (windowWidthPx - popupWidthPx - margin).coerceAtLeast(margin)
    val maxY = (windowHeightPx - popupHeightPx - margin).coerceAtLeast(margin)
    val x = (rightLimitPx - popupWidthPx).coerceIn(margin, maxX)
    val y = (anchorBottomPx + gapPx).coerceIn(margin, maxY)
    return x to y
}

/**
 * 浮层内容的**可用宽上限**（Y55）：给定右缘目标后，浮层最宽只能到 `rightLimit - margin`。
 *
 * 为什么需要独立一条：左缘钳制（[popupBelowRight] 的 `coerceIn(margin, …)`）与"右缘不越目标"
 * 在窄窗/分屏下**不可兼得**——宽度超过上限时保了左边距就必然越过右 Dock 槽左缘。固定宽内容
 * （220dp 的站位间距滑杆）必须在组合期按此上限收缩，浮层才同时满足两条约束。
 *
 * @param rightLimitPx 右缘上限（px）：调用方传「锚点右缘」与「右侧保留区左缘」的较小值，
 *   与 [popupBelowRight] 同源
 * @param marginPx 距窗口边缘的最小留白
 * @return 可用宽（px，≥0）；为 0 表示右缘目标已不剩空间，调用方应换侧或不弹
 */
fun popupWidthCapPx(rightLimitPx: Int, marginPx: Int): Int =
    (rightLimitPx - marginPx.coerceAtLeast(0)).coerceAtLeast(0)
