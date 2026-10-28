package com.wotagei.cam.ui.widget

import com.wotagei.cam.core.ArcMergeDeadband
import kotlin.math.abs

/**
 * 刻度滑杆的梯子构造（2026-10-17 用户需求：所有带拖动条的参数改成有刻度、拖动吸附）：
 *
 * - 输入范围一律来自运行时能力（`ParamState.range` / `CameraAbility`），本文件只负责
 *   「从范围内挑出产品档位」，不出现任何机型硬编码；
 * - 输出升序、已按 `lo..hi` 过滤，因此 [nearestLadderIndex] 与 Material3 的索引空间
 *   （`0f..(size-1)`）能直接对应；
 * - 纯函数，全部可 JVM 单测（见 `SliderLadderTest`）。
 */

/** 就近刻度索引：空表返回 0；恰好落在两档中点时取低档（与滑杆"向下吸附"的观感一致） */
internal fun nearestLadderIndex(value: Float, ladder: List<Float>): Int {
    if (ladder.isEmpty()) return 0
    var best = 0
    var bestDist = abs(ladder[0] - value)
    for (i in 1 until ladder.size) {
        val d = abs(ladder[i] - value)
        if (d < bestDist) {
            best = i
            bestDist = d
        }
    }
    return best
}

/** 1/3 档标准 ISO 序列（50…12800），按设备范围过滤 */
internal fun isoLadder(lo: Int, hi: Int): List<Float> =
    listOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
    ).filter { it in lo..hi }.map { it.toFloat() }

/** 曝光补偿：设备每个整数补偿步一档 */
internal fun evLadder(lo: Int, hi: Int): List<Float> =
    if (lo > hi) emptyList() else (lo..hi).map { it.toFloat() }

/** 白平衡色温：全局 500K 网格（2000..10000 恰 17 档），按范围过滤后仍按 500 对齐 */
internal fun kelvinLadder(lo: Int, hi: Int): List<Float> = steppedLadder(lo, hi, 500)

/** 白平衡色调：每 5 一档（-50..50 → 21 档） */
internal fun tintLadder(lo: Int, hi: Int): List<Float> = steppedLadder(lo, hi, 5)

/** 变焦快捷倍率梯：沿用产品档位（含 1.5/2.5 便于构图），按运行时变焦范围过滤 */
internal fun zoomLadder(lo: Float, hi: Float): List<Float> =
    listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f)
        .filter { it >= lo && it <= hi }

/**
 * 手动对焦屈光度：`lo..hi` 等分 10 段共 11 点（`hi<=lo` 表示定焦镜头，没有可调对焦距离）。
 * 这是本轮唯一从连续变分档的对焦量。下限吃运行时 `range.start`——少数镜头的最小屈光度不为 0，
 * 梯若从 0 起会造出滑杆可达、参数域外的刻度。
 */
internal fun diopterLadder(lo: Float, hi: Float): List<Float> =
    if (hi <= lo) emptyList() else (0..10).map { lo + (hi - lo) * it / 10f }

/** 百分比梯（斑马阈值 80..100 步 1、峰值强度 5..95 步 5） */
internal fun percentLadder(lo: Int, hi: Int, step: Int): List<Float> = steppedLadder(lo, hi, step)

/**
 * 色调电平梯（29 §4.4 F4，2026-10-28）：黑点/白点 0..[max] 按 0.01 步长切成刻度。
 *
 * **为什么必须有真梯**：`PillSlider` 把「非 null 但不足 2 点」的梯判成禁用（防假连续，
 * P3 口径），传 `emptyList()` 会让整根滑杆灰掉——色调首版就是这样在真机上三根全灰的
 * （WIKO 12:05 取证，SeekBar enabled=false）。梯值即 `step=0.01` 的网格，与 snap 后的
 * 取值域完全一致，不改变拖动手感。
 */
internal fun toneLevelLadder(max: Float): List<Float> {
    val n = kotlin.math.round(max / 0.01f).toInt()
    return (0..n).map { it * 0.01f }
}

/**
 * 补弧「并入门槛」（差分死区 D）：整数逐档一格，共 [ArcMergeDeadband.MAX]-[ArcMergeDeadband.MIN]+1 档。
 * 域来自 [ArcMergeDeadband]（参数域常量，非机型数值），所以这里不接任何运行时入参。
 */
internal fun deadbandLadder(): List<Float> =
    steppedLadder(ArcMergeDeadband.MIN, ArcMergeDeadband.MAX, 1)

/**
 * 快门梯：调用方传入 `shutterItems(...).filter { it.supported }.map { it.value.toFloat() }`，
 * 这里只做范围过滤、去重、升序（不支持的档直接不进梯，而不是灰显 chip）。
 */
internal fun shutterLadder(tiers: List<Float>, lo: Float, hi: Float): List<Float> =
    tiers.filter { it >= lo && it <= hi }.distinct().sorted()

/** 以 [step] 为全局网格生成 `lo..hi` 内的档位（负数范围同样按网格向上对齐） */
private fun steppedLadder(lo: Int, hi: Int, step: Int): List<Float> {
    if (step <= 0 || lo > hi) return emptyList()
    val start = alignUp(lo, step)
    if (start > hi) return emptyList()
    val out = ArrayList<Float>((hi - start) / step + 1)
    var v = start
    while (v <= hi) {
        out.add(v.toFloat())
        v += step
    }
    return out
}

/** 向上对齐到 [step] 的网格（Kotlin 的 `/` 对负数向零截断，这里用 floorDiv 保负区间正确） */
private fun alignUp(v: Int, step: Int): Int = Math.floorDiv(v + step - 1, step) * step
