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
        // measuredSrcFps 现有 Int/Long 两个重载（M6-05）；整数**字面量**列表对两者都可行 ⇒ 必须点名
        // 元素类型（`33_333L` → Long 核；`List<Int>(…)` → Int 外壳；`emptyList<Int>()` 同理）
        assertEquals(rate(24, 1), ArcRateProbe.measuredSrcFps(List(7) { 33_333L }, 24))
        assertEquals(rate(24, 1), ArcRateProbe.measuredSrcFps(List<Int>(7) { 33_333 }, 24))
        assertEquals(rate(30, 1), ArcRateProbe.measuredSrcFps(emptyList<Int>(), 30))
        // fallback 同为有理数形态（NTSC 容器标称 30000/1001 原样带出，不再先整成 30）
        assertEquals(rate(30_000, 1_001), ArcRateProbe.measuredSrcFps(emptyList<Int>(), 30_000, 1_001))
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

    // region M6-05 帧距 Long 保精度（>2³¹µs 的断层不得降位混进滤噪窗）

    /**
     * M6-05 守卫：**`> 2³¹µs` 的轨道断层在滤噪前不得降位**——一旦先 `.toInt()`，回卷值可能恰好
     * 落进滤噪窗（`1_001..1_000_000`）而被当中位样本收下，毒化源帧率、放大 M6-01 的整数误差。
     *
     * 靶子：`(1L shl 32) + 16_667L`（约 71.6 分钟量级的 Long 帧距）降 Int 后回卷成 `16_667`，
     * 而 `16_667` 恰在窗内、且吸附到 60fps——旧口径会把它算进去。真值：Long 核按 Long 比窗，
     * 断层原样挡在窗外。第二条断言是**对照**（旧口径的降位路径必真毒化），防本守卫变成恒等式。
     */
    @Test
    fun `超2的31次方帧距不进滤噪窗也不毒化中位数`() {
        val overflow = (1L shl 32) + 16_667L
        assertEquals("前提：降位回卷值恰在滤噪窗内（这正是本守卫的靶子）", 16_667, overflow.toInt())
        // 9 枚断层 + 8 枚真样本（41_708 → NTSC 23.976）。中位数位上若混进回卷值，结果会被翻成 60。
        val raw = List(9) { overflow } + List(8) { 41_708L }
        assertEquals(
            "Long 核必须把断层挡在窗外、中位数仍只取真样本",
            rate(24_000, 1_001),
            ArcRateProbe.measuredSrcFps(raw, 30)
        )
        // 对照：同一物理输入若先降 Int 再喂（旧口径的降位路径），中位数必被回卷值翻成 60
        // ——证明"不保 Long 就会真的毒化"，即本守卫不是恒等式。
        assertEquals(
            "降位路径（旧口径）必须真的毒化中位数，否则本守卫什么都没测到",
            rate(60, 1),
            ArcRateProbe.measuredSrcFps(raw.map { it.toInt() }, 30)
        )
    }

    /**
     * M6-05 守卫：整数源**逐点零变化**——旧 `List<Int>` 外壳必须与显式 `List<Long>` 核在同一
     * 物理输入上逐点同值（Int 值恒在 Long 域内，滤噪窗与中位数逐点等价，故旧调用点行为不变）。
     */
    @Test
    fun `整数源逐点零变化_Int外壳与Long核同值`() {
        // 三段混合：重复样本（0）+ 有效 NTSC 样本 + 断层（2_000_000）——同时走滤噪与中位数两条路
        val intRaw = buildList {
            repeat(10) { add(0) }
            repeat(45) { add(41_708) }
            add(2_000_000)
            repeat(45) { add(41_708) }
        }
        assertEquals(
            "Int 外壳必须与显式 Long 核逐点同值（含中位数位次）",
            ArcRateProbe.measuredSrcFps(intRaw.map { it.toLong() }, 24),
            ArcRateProbe.measuredSrcFps(intRaw, 24)
        )
        // 回退路（有效样本不足）也必须同值
        assertEquals(
            ArcRateProbe.measuredSrcFps(List(7) { 33_333L }, 24),
            ArcRateProbe.measuredSrcFps(List<Int>(7) { 33_333 }, 24)
        )
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
        val rate = ArcRateProbe.measuredSrcFps(List(90) { 39_370L }, 24)  // 10⁶/39370 ≈ 25.4（约分 100000/3937）
        assertEquals(rate(100_000, 3_937), rate)
        assertEquals(25, rate.roundedInt())                              // 整数视图整成 25——分叉的来源
        assertFalse("25.4 ≤ 25 不成立（整数口径会把 25.4 打成 25 误判 NO_NEED）", rate.atMostInt(25))
        assertTrue(rate.atMostInt(26))
        assertTrue(rate.atMostInt(255))
    }

    // endregion
}
