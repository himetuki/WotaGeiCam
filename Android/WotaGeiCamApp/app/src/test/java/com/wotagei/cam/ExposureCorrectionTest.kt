package com.wotagei.cam

import com.wotagei.cam.core.ExposureCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 等号档曝光纠偏纯逻辑守卫（2026-11-01 用户定版，docs/plan/31）。
 * 地基口径：发送侧永不发等号值；AE 状态/帧周期/总线档位一律不动；探测只在预览态。
 */
class ExposureCorrectionTest {

    private val t24 = 41_666_666L
    private val t25 = 40_000_000L

    // ---------------------------------------------------------- 适用判定（与 UI 开关露出域同一条线）

    @Test
    fun applicablePairsMatchProductDefinition() {
        assertTrue("1/24@24 满窗", ExposureCorrection.applicable(t24, 24))
        assertTrue("1/25@25 满窗", ExposureCorrection.applicable(t25, 25))
        assertTrue(
            "25fps+1/24 被钳到 40ms == 帧周期，同样是等号",
            ExposureCorrection.applicable(t24, 25)
        )
        assertFalse(
            "1/25@24 不满窗（40 < 41.67），读出本来就有余量",
            ExposureCorrection.applicable(t25, 24)
        )
        assertFalse(
            "非强制档不命中（UI 开关只在 1/24、1/25 露出，行为与入口同域）",
            ExposureCorrection.applicable(33_333_333L, 30)
        )
        assertFalse("短快门（远小于帧周期）不命中", ExposureCorrection.applicable(20_000_000L, 25))
    }

    // ---------------------------------------------------------- 自动余量：下限 / 上限 / 安全系数

    @Test
    fun autoMarginFloorsCapsAndAddsSafety() {
        assertEquals("守约机（Δ 缺失）走 5% 下限", 2_000_000L, ExposureCorrection.autoMarginNs(null, t25))
        assertEquals("Δ=0（实测没拉长）同样走下限", 2_000_000L, ExposureCorrection.autoMarginNs(0L, t25))
        assertEquals("Δ+1ms 安全系数", 7_000_000L, ExposureCorrection.autoMarginNs(6_000_000L, t25))
        assertEquals("Δ 顶到 20% 上限封顶", 8_000_000L, ExposureCorrection.autoMarginNs(30_000_000L, t25))
        assertEquals("负 Δ 归 0 后走下限（没拉长不是负拉长）", 2_000_000L, ExposureCorrection.autoMarginNs(-3_000_000L, t25))
        // 24fps 帧周期 41_666_666：floor = 2_083_333、cap = 8_333_333
        assertEquals(2_083_333L, ExposureCorrection.autoMarginNs(null, t24))
        assertEquals(8_333_333L, ExposureCorrection.autoMarginNs(99_000_000L, t24))
    }

    // ---------------------------------------------------------- 纠偏合成：永不越窗、永不归零

    @Test
    fun correctedExposureNeverExceedsFramePeriod() {
        assertEquals(38_000_000L, ExposureCorrection.correctedExposureNs(t25, 2_000_000L, t25))
        assertEquals(
            "手动 0 = 满窗直发（等号），与改版前行为一致",
            t25,
            ExposureCorrection.correctedExposureNs(t25, 0L, t25)
        )
        assertEquals(
            "1/24@24：满窗 41_666_666 − 5% 下限 2_083_333 = 39_583_333",
            39_583_333L,
            ExposureCorrection.correctedExposureNs(t24, 2_083_333L, t24)
        )
        assertEquals(
            "巨额纠偏不许把曝光压到 0/负",
            1L,
            ExposureCorrection.correctedExposureNs(t25, 999_999_999L, t25)
        )
    }

    @Test
    fun manualSliderDomainIsClamped() {
        assertEquals("负值区已去掉：手改 prefs 的负值钳回 0", 0L, ExposureCorrection.clampManualNs(-99_000_000L))
        assertEquals(8_000_000L, ExposureCorrection.clampManualNs(99_000_000L))
        assertEquals(500_000L, ExposureCorrection.clampManualNs(500_000L))
        assertEquals(0L, ExposureCorrection.clampManualNs(0L))
    }

    // ---------------------------------------------------------- 探测折算：中位数抗抖动、负 Δ 归 0

    @Test
    fun probeDeltaUsesMedianAgainstOutliers() {
        val period = t25
        val ms: (Long) -> Long = { it * 1_000_000L }
        assertEquals(
            "偶数个取中间两数平均：42、44 → 43ms ⇒ Δ=3ms",
            ms(3),
            ExposureCorrection.probeDeltaNs(listOf(ms(42), ms(44)), period)
        )
        assertEquals(
            "奇数个取中位：编码器启停那一拍 200ms 长尾不污染结论",
            ms(2),
            ExposureCorrection.probeDeltaNs(listOf(ms(42), ms(42), ms(200), ms(42), ms(42)), period)
        )
        assertEquals("本机跑得比请求还快 ⇒ Δ 归 0", 0L, ExposureCorrection.probeDeltaNs(listOf(ms(39), ms(39)), period))
        assertEquals("空样本 = 没测到 ⇒ 0（走下限）", 0L, ExposureCorrection.probeDeltaNs(emptyList(), period))
    }
}
