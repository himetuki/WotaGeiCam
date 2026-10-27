package com.wotagei.cam

import com.wotagei.cam.core.MarkerLineColor
import com.wotagei.cam.core.MarkerLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站位指示线几何（2026-10-27）：等距、居中对称、任意 (count, spread) 组合不出画。
 * 用户语义：+/- 调线数、滑杆调间距——这里钉的是**渲染拿到的最终几何**，控件怎么算出来的
 * 中间值不管。
 *
 * 二轮定版（2026-10-27）：线的开/关只由**线数**驱动，0 = 关（没有独立开关）。
 * 所以 0 必须是合法值（能被 +/− 调到）且渲染为空表，出厂默认也必须是 0（默认不画）。
 */
class MarkerLinesTest {

    @Test
    fun `等距与对称性_任意参数下成立`() {
        for (count in 1..MarkerLines.MAX_COUNT) {
            for (spread in listOf(MarkerLines.MIN_SPREAD, 0.5f, MarkerLines.MAX_SPREAD)) {
                val xs = MarkerLines.lineXsFraction(count, spread)
                assertEquals("线数正确", MarkerLines.clampCount(count), xs.size)
                // 升序、不出画
                assertEquals("首线不出左界", true, xs.first() >= 0f)
                assertEquals("末线不出右界", true, xs.last() <= 1f)
                assertEquals("升序", xs, xs.sorted())
                // 等距
                if (xs.size >= 2) {
                    val gap = xs[1] - xs[0]
                    for (i in 1 until xs.size) {
                        assertEquals("等距 ($count,$spread)", gap, xs[i] - xs[i - 1], 1e-5f)
                    }
                }
                // 居中对称：首末到中点等距
                if (xs.size >= 2) {
                    assertEquals("对称", 1f, xs.first() + xs.last(), 1e-5f)
                }
            }
        }
    }

    @Test
    fun `线数为零即关闭_空表`() {
        // 二轮定版的开关语义全压在这一条上：0 = 没有线 = 关
        assertEquals("0 必须是合法线数（否则 +/− 关不掉）", 0, MarkerLines.MIN_COUNT)
        assertEquals(emptyList<Float>(), MarkerLines.lineXsFraction(0, MarkerLines.DEFAULT_SPREAD))
        assertEquals(emptyList<Float>(), MarkerLines.lineXsFraction(0, MarkerLines.MAX_SPREAD))
        // 负数脏值钳到 0（= 关），不是钳到 1
        assertEquals(0, MarkerLines.clampCount(-3))
    }

    @Test
    fun `出厂默认不画线`() {
        assertEquals("默认线数 = 0（没有独立开关，默认关只能靠 0 表达）", 0, MarkerLines.DEFAULT_COUNT)
        assertTrue(MarkerLines.lineXsFraction(MarkerLines.DEFAULT_COUNT, MarkerLines.DEFAULT_SPREAD).isEmpty())
        assertTrue("默认组宽在滑杆域内", MarkerLines.DEFAULT_SPREAD in MarkerLines.MIN_SPREAD..MarkerLines.MAX_SPREAD)
    }

    @Test
    fun `单线恒居中`() {
        assertEquals(listOf(0.5f), MarkerLines.lineXsFraction(1, MarkerLines.MAX_SPREAD))
        assertEquals(listOf(0.5f), MarkerLines.lineXsFraction(1, MarkerLines.MIN_SPREAD))
    }

    @Test
    fun `间距随spread单调增_随count单调减`() {
        // spread 拉大 ⇒ 间距拉大（滑杆方向的几何依据）
        val s3 = MarkerLines.lineXsFraction(4, 0.30f)
        val s6 = MarkerLines.lineXsFraction(4, 0.60f)
        val gap3 = s3[1] - s3[0]
        val gap6 = s6[1] - s6[0]
        assertTrue("spread 0.3→0.6 间距应变大", gap6 > gap3)
        // 线数变多（spread 不变）⇒ 间距变小（整组宽度被更多线瓜分）
        val c8 = MarkerLines.lineXsFraction(8, 0.60f)
        val gap8 = c8[1] - c8[0]
        assertTrue("线数 4→8 间距变小", gap8 < gap6)
    }

    @Test
    fun `脏值钳回合法域`() {
        assertEquals(0, MarkerLines.clampCount(0))
        assertEquals(MarkerLines.MAX_COUNT, MarkerLines.clampCount(99))
        assertEquals(MarkerLines.MIN_SPREAD, MarkerLines.clampSpread(-1f))
        assertEquals(MarkerLines.MAX_SPREAD, MarkerLines.clampSpread(9f))
        // 钳回后渲染仍不出画（滑杆外的脏值不炸）
        val xs = MarkerLines.lineXsFraction(99, 9f)
        assertTrue(xs.first() >= 0f && xs.last() <= 1f)
    }

    @Test
    fun `色板_序号取值回退黄_六色齐全`() {
        assertEquals(6, MarkerLineColor.ALL.size)
        assertEquals(MarkerLineColor.YELLOW, MarkerLineColor.fromOrdinal(-1))
        assertEquals(MarkerLineColor.YELLOW, MarkerLineColor.fromOrdinal(99))
        for (i in MarkerLineColor.ALL.indices) {
            assertEquals(i, MarkerLineColor.fromOrdinal(i).ordinal)
        }
    }
}
