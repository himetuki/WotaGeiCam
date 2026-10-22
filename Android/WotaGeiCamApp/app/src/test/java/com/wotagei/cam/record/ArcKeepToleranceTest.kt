package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽帧相位容差（2026-10-17 第 3b 笔）。
 *
 * 尺子三层：
 * 1. 容差纯函数（槽宽比例，非毫秒硬编码）；
 * 2. **5 万帧**、±3ms 伪随机抖动、30/25/60→24 三源 × 100 个种子（0..99，seed=7 只是普通样本）：
 *    输出帧数必须落在 `⌈N·D/S⌉ ±1`，且输出时间戳单调——不再依赖单个恰好命中理想值的种子。
 *    多 seed 用 5 万帧窗：10 万帧时 25→24 的末槽恰落在观测边界，个别种子会因末尾相位少 2 帧
 *    （边界效应，非抽帧率漂移）；10 万帧仍用于零容差对照与 50% 槽突变自证；
 * 3. 突变自证：把容差改成 50% 槽后不变量必须变红（60→24 多一帧）。
 *
 * 抖动源用固定种子 LCG（不依赖 kotlin.random 的跨版本实现），同一判据在 JVM 上可复现。
 */
class ArcKeepToleranceTest {

    private val n = 100_000

    /** 多种子不变量用的观测窗（10 万帧末槽的相位边界会造成 ±2，见类注） */
    private val multiSeedN = 50_000

    /** 固定种子 LCG：输出 [-3_000_000, +3_000_000] ns（±3ms） */
    private fun jitter(state: Long): Pair<Long, Long> {
        val next = state * 6364136223846793005L + 1442695040888963407L
        val j = (next ushr 33) % 6_000_001L - 3_000_000L
        return next to j
    }

    private fun expectedCount(srcFps: Int, dstFps: Int = 24, frames: Int = n): Long =
        (frames.toLong() * dstFps + srcFps - 1) / srcFps

    private data class Sim(val count: Long, val monotonic: Boolean)

    private fun simulate(
        srcFps: Int,
        slotNs: Long,
        toleranceNs: Long,
        seed: Long = 7L,
        frames: Int = n
    ): Sim {
        val rule = ArcKeepRule(slotNs = slotNs, toleranceNs = toleranceNs)
        var state = seed
        var count = 0L
        var last = Long.MIN_VALUE
        var monotonic = true
        for (i in 0 until frames) {
            val (next, j) = jitter(state)
            state = next
            val ts = i.toLong() * 1_000_000_000L / srcFps + j
            if (rule.onFrame(ts)) {
                count++
                if (ts < last) monotonic = false
                last = ts
            }
        }
        return Sim(count, monotonic)
    }

    // ---- ① 容差纯函数 ----

    @Test
    fun `容差按时长比例计算_关闭转换时为 0`() {
        val slot24 = 1_000_000_000L / 24
        assertEquals(slot24 * 10L / 100L, arcKeepToleranceNs(slot24))
        assertEquals(0L, arcKeepToleranceNs(0L))
        assertEquals(0L, arcKeepToleranceNs(slot24, 0L))
        assertEquals("比例钳在 100%（超过一整个槽不是相位容差）", slot24, arcKeepToleranceNs(slot24, 200L))
    }

    @Test
    fun `容差内早到帧保留_超出容差仍抽`() {
        val slot = 100L
        val tol = arcKeepToleranceNs(slot, 10L)
        val inside = ArcKeepRule(slotNs = slot, toleranceNs = tol)
        assertTrue(inside.onFrame(1_000L))
        assertTrue("恰在 nextDue - tol 上的帧必须保留", inside.onFrame(1_090L))

        val outside = ArcKeepRule(slotNs = slot, toleranceNs = tol)
        assertTrue(outside.onFrame(1_000L))
        assertFalse("比 nextDue - tol 还早 1ns 的帧仍应抽掉", outside.onFrame(1_089L))
        assertEquals("被抽掉的帧计入 pendingDrops", 1, outside.takeDrops())
    }

    // ---- ② 长期帧数不变量（10 万帧、±3ms 抖动） ----

    @Test
    fun `30_25_60 转 24 的输出帧数等于理想值加减一且时间戳单调_100种子`() {
        val slot = 1_000_000_000L / 24
        val tol = arcKeepToleranceNs(slot)
        for (src in listOf(30, 25, 60)) {
            val ideal = expectedCount(src, frames = multiSeedN)
            for (seed in 0L..99L) {
                val sim = simulate(src, slot, tol, seed, multiSeedN)
                assertTrue(
                    "${src}→24 seed=$seed 的 ${multiSeedN} 帧输出数必须落在理想值 ±1（理想 $ideal，实际 ${sim.count}）",
                    kotlin.math.abs(sim.count - ideal) <= 1L
                )
                assertTrue("${src}→24 seed=$seed 的输出时间戳必须单调", sim.monotonic)
            }
        }
    }

    @Test
    fun `零容差在 25 转 24 会系统性多抽_容差修的是误抽而不是改变帧数`() {
        val slot = 1_000_000_000L / 24
        val zero = simulate(25, slot, 0L)
        val ideal = expectedCount(25)
        assertTrue("零容差应低于理想输出（实测 ${zero.count} < $ideal）", zero.count < ideal)
        assertEquals("10% 容差把 25→24 拉回理想输出", ideal, simulate(25, slot, arcKeepToleranceNs(slot)).count)
    }

    // ---- ③ 突变自证：容差 50% 槽必须破坏不变量 ----

    @Test
    fun `容差改到 50 槽后帧数不变量必红`() {
        val slot = 1_000_000_000L / 24
        val mutant = arcKeepToleranceNs(slot, 50L)
        assertTrue("50% 槽必须与 10% 槽真的不同", mutant > arcKeepToleranceNs(slot))
        val broken = listOf(30, 25, 60).map { src ->
            src to (simulate(src, slot, mutant).count != expectedCount(src))
        }
        assertTrue("50% 容差必须至少破坏一个源的不变量（实测 60→24 多一帧），否则尺子是空的", broken.any { it.second })
    }
}
