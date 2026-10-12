package com.wotagei.cam

import com.wotagei.cam.record.ArcRateProbe
import com.wotagei.cam.record.ArcRateProbe.MeasuredSrcFps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源帧率探测核**有理数化**（M6-01：离线光弧修复链非整数源分叉修复）的全表行为测试。
 *
 * 分叉根因：整数 fps 视图把 29.97（30000/1001）压成 30——保留位次与真值漂一位（NTSC 案：
 * 抽帧点 {4,9,…} → {5,10,…}）；25.4（254/10）压成 25——"源不快于目标"的门槛误判 NO_NEED。
 * 本表钉住：吸附只认 ±2µs 纯整数交叉相乘判据、未吸附走原始约分（den≠1）、整数视图只是
 * 四舍五入+钳制的派生品（旧签名委托对旧调用点行为零变化）。
 */
class ArcRateProbeRationalTest {

    private fun rate(num: Long, den: Long) = MeasuredSrcFps(num, den)

    // region 快照逐点（snapToKnownRate）

    @Test
    fun `已知档位吸附逐点_整数档`() {
        assertEquals(rate(30, 1), ArcRateProbe.snapToKnownRate(33_333))
        assertEquals(rate(30, 1), ArcRateProbe.snapToKnownRate(33_334))
        assertEquals(rate(25, 1), ArcRateProbe.snapToKnownRate(40_000))
        assertEquals(rate(24, 1), ArcRateProbe.snapToKnownRate(41_666))
        assertEquals(rate(24, 1), ArcRateProbe.snapToKnownRate(41_667))
        assertEquals(rate(60, 1), ArcRateProbe.snapToKnownRate(16_666))
        assertEquals(rate(60, 1), ArcRateProbe.snapToKnownRate(16_667))
        // 16_668 在 60 档的 ±2µs 窗口内（周期 16_666.67，窗到 16_668.67）
        assertEquals(rate(60, 1), ArcRateProbe.snapToKnownRate(16_668))
    }

    @Test
    fun `已知档位吸附逐点_NTSC档`() {
        assertEquals(rate(30_000, 1_001), ArcRateProbe.snapToKnownRate(33_366))
        assertEquals(rate(30_000, 1_001), ArcRateProbe.snapToKnownRate(33_367))
        assertEquals(rate(24_000, 1_001), ArcRateProbe.snapToKnownRate(41_708))
        assertEquals(rate(60_000, 1_001), ArcRateProbe.snapToKnownRate(16_683))
        assertEquals(rate(60_000, 1_001), ArcRateProbe.snapToKnownRate(16_685))
    }

    @Test
    fun `不吸附带走原始约分_den不为1`() {
        // 两档窗口之间（16669..16681）：不在 60 档的 [16664.67,16668.67]，也不在 NTSC60 的
        // [16681.33,16685.33]——退原始有理数，den≠1（旧口径在这里会整成 60 掩掉真值）
        for (d in 16_669L..16_681L) {
            val r = ArcRateProbe.snapToKnownRate(d)
            assertEquals("d=$d 值须等于 10⁶/d", r.den * 1_000_000L, r.num * d)
            assertTrue("d=$d 未吸附却整成整数档（$r）", r.den != 1L)
        }
        // 约分形态逐点抽查
        assertEquals(rate(1_000_000, 16_669), ArcRateProbe.snapToKnownRate(16_669))
        assertEquals(rate(100_000, 1_667), ArcRateProbe.snapToKnownRate(16_670))
    }

    // endregion

    // region 回退与钳制

    @Test
    fun `有效样本不足退回fallback有理数`() {
        assertEquals(rate(24, 1), ArcRateProbe.measuredSrcFps(List(7) { 33_333 }, 24))
        assertEquals(rate(30, 1), ArcRateProbe.measuredSrcFps(emptyList(), 30))
        // fallback 同为有理数形态（NTSC 容器标称 30000/1001 原样带出，不再先整成 30）
        assertEquals(rate(30_000, 1_001), ArcRateProbe.measuredSrcFps(emptyList(), 30_000, 1_001))
    }

    @Test
    fun `重复样本与断层被滤掉_中位数照常吸附`() {
        val raw = buildList {
            repeat(10) { add(0) }
            repeat(45) { add(41_708) }
            add(2_000_000)
            repeat(45) { add(41_708) }
            add(2_000_000)
        }
        assertEquals(rate(24_000, 1_001), ArcRateProbe.measuredSrcFps(raw, 24))
    }

    @Test
    fun `roundedInt钳到1到480_防畸变帧距算飞`() {
        // d=1000 不在滤噪带内但 snapToKnownRate 直喂可及：10⁶/1000 = 1000 → 钳 480
        assertEquals(rate(1_000, 1), ArcRateProbe.snapToKnownRate(1_000))
        assertEquals(480, ArcRateProbe.snapToKnownRate(1_000).roundedInt())
        // 下缘：3fps 的超慢畸变也只到 1
        assertEquals(1, rate(1, 3).roundedInt())
    }

    // endregion

    // region 金标等价（整数视图 == v1 手算 round(10⁶/d)）

    /** v1 手算口径（逐字抄自旧 measuredFps——两套独立公式对拍，对拍同源委托才是恒等式假证明） */
    private fun goldRound(d: Long): Int = ((1_000_000L + d / 2) / d).toInt().coerceIn(1, 480)

    @Test
    fun `整数视图与手算金标逐点相等`() {
        val samples = listOf(
            1_001L, 1_250L, 2_000L, 2_002L, 3_125L, 4_000L, 4_166L, 4_167L, 4_170L, 5_000L,
            6_666L, 6_667L, 7_500L, 7_508L, 8_333L, 8_334L, 10_000L, 10_010L, 12_500L, 13_333L,
            15_015L, 16_666L, 16_667L, 16_670L, 16_683L, 20_000L, 20_020L, 24_024L, 25_000L,
            30_003L, 33_333L, 33_334L, 33_366L, 33_367L, 40_000L, 41_666L, 41_667L, 41_708L,
            50_000L, 60_006L, 83_333L, 100_000L, 125_000L, 250_000L, 500_000L, 1_000_000L,
        )
        for (d in samples) {
            assertEquals("d=$d", goldRound(d), MeasuredSrcFps.of(1_000_000L, d).roundedInt())
        }
    }

    @Test
    fun `旧整数签名委托行为零变化`() {
        // 有样本：走有理数核再取整，与 v1 手算同值
        assertEquals(30, ArcRateProbe.measuredFps(List(90) { 33_333 }, 24))
        assertEquals(60, ArcRateProbe.measuredFps(List(100) { 16_667 }, 24))
        // 样本不足：fallback 原样返回、**不钳制**（v1 逐字行为——传 0 得 0，不许被钳成 1）
        assertEquals(24, ArcRateProbe.measuredFps(List(7) { 33_333 }, 24))
        assertEquals(0, ArcRateProbe.measuredFps(emptyList(), 0))
    }

    @Test
    fun `25点4fps探出真值有理数_整数视图只作量级`() {
        val rate = ArcRateProbe.measuredSrcFps(List(90) { 39_370 }, 24)  // 10⁶/39370 ≈ 25.4（约分 100000/3937）
        assertEquals(rate(100_000, 3_937), rate)
        assertEquals(25, rate.roundedInt())                              // 整数视图整成 25——分叉的来源
        assertFalse("25.4 ≤ 25 不成立（整数口径会把 25.4 打成 25 误判 NO_NEED）", rate.atMostInt(25))
        assertTrue(rate.atMostInt(26))
        assertTrue(rate.atMostInt(255))
    }

    // endregion
}
