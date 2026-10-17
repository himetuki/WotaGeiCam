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
     * M6-05 守卫：整数源**逐点零变化**——旧 `List<Int>` 外壳的每一步决策（滤噪窗界、中位数位次、
     * 样本门槛、退档）都必须与**旧 Int 算法**逐点同值，故旧调用点行为不变。
     *
     * 为什么不能拿 Long 核当参照：Int 外壳的实现**就是** `measuredSrcFps(ints.map { it.toLong() }, …)`
     * ——拿它当"旧算法"是**同源委托恒等式**（本文件 [goldRound] 那条老教训：对拍同源委托才是恒等式
     * 假证明），删掉 Long 核里的判断分支它也照绿。故此处的参照是**独立参考实现** [LegacyIntRef]：
     * 滤噪窗、中位数位次、档位快照表、±2µs 吸附窗、约分全在测试侧另写，**不调用 `ArcRateProbe`
     * 的任何函数**（连 `snapToKnownRate`、`MeasuredSrcFps.of` 都不用）。
     */
    @Test
    fun `整数源逐点零变化_Int外壳与旧Int参考实现同值`() {
        val bad = parityViolations(LegacyIntRef())
        assertEquals(
            "Int 外壳与旧 Int 算法的独立参考对拍出现分歧：\n${bad.joinToString("\n")}",
            0,
            bad.size
        )
    }

    /**
     * 尺子能红：把参考实现的**窗界 / 中位数位次 / 吸附窗**任一处改错，对拍必须报红——否则上面
     * 那条"逐点同值"只是 fixture 巧合或恒等式。四枚突变体各改一处判据；另配一枚"无害放宽"
     * （吸附窗 2µs → 3µs，fixture 未触及该边界）的对照，证明尺子不过敏。
     */
    @Test
    fun `尺子自己能红_参考实现判据改错后对拍必失败`() {
        assertEquals("良品必须先真的绿", 0, parityViolations(LegacyIntRef()).size)
        val mutants = listOf(
            "中位数位次取低半（size/2 → (size-1)/2）" to LegacyIntRef(medianRank = { (it - 1) / 2 }),
            "滤噪窗下缘放宽到 1000" to LegacyIntRef(cleanLo = 1_000),
            "滤噪窗上缘放宽到 1_000_001" to LegacyIntRef(cleanHi = 1_000_001),
            "吸附窗收到 0µs（±2µs 判据失效）" to LegacyIntRef(snapWindowUs = 0L),
        )
        mutants.forEach { (name, ref) ->
            val red = parityViolations(ref)
            assertTrue("突变体「$name」必须让对拍报红，实际 0 分歧 ⇒ 上面的尺子测不到该判据", red.isNotEmpty())
        }
        // 对照组：只把吸附窗从 2µs 无害放宽到 3µs（fixture 不在该边界上），不许报红
        assertEquals("对照组（无害放宽吸附窗）不该报红", 0, parityViolations(LegacyIntRef(snapWindowUs = 3L)).size)
    }

    /**
     * 对拍 fixture：三条分别覆盖滤噪（窗内外）、中位数位次（偶数样本双峰）、样本门槛
     * （恰 7 枚 < [ArcRateProbe.MIN_DELTAS]）。第 2/3 条是**为突变体量身**的——它们让
     * "窗界改错"与"位次改错"必然翻转结果，否则突变体可能悄悄与良品同值。
     */
    private val parityFixtures: List<Triple<String, List<Int>, Long>> = listOf(
        Triple(
            "混合：10 枚重复(0) + 90 枚有效 NTSC(41708) + 1 枚断层(2000000)",
            buildList<Int> {
                repeat(10) { add(0) }
                repeat(45) { add(41_708) }
                add(2_000_000)
                repeat(45) { add(41_708) }
            },
            24L
        ),
        Triple(
            "偶数样本双峰（4×33333 + 4×41708）+ 窗下沿外样本 1000",
            listOf(1_000) + List(4) { 33_333 } + List(4) { 41_708 },
            24L
        ),
        Triple(
            "有效样本恰 7 枚（<MIN_DELTAS）+ 窗上沿外样本 1_000_001",
            List(7) { 41_708 } + listOf(1_000_001),
            30L
        ),
    )

    /** Int 外壳（被测）与旧 Int 算法独立参考的**逐点对拍**，返回分歧清单（空 = 一致） */
    private fun parityViolations(ref: LegacyIntRef): List<String> {
        val bad = ArrayList<String>()
        for ((tag, raw, fallbackFps) in parityFixtures) {
            val shell = ArcRateProbe.measuredSrcFps(raw, fallbackFps)   // 被测：Int 外壳
            val legacy = ref.measure(raw, fallbackFps)                  // 参照：独立参考实现
            if (shell != legacy) bad.add("$tag：Int 外壳=$shell 旧 Int 参考=$legacy")
        }
        return bad
    }

    /**
     * 旧 Int 算法（父提交 `cca21be^` 的 `measuredSrcFps(List<Int>, …)`）的**独立参考实现**。
     *
     * 判据全部由构造参数给出，默认值 = v1 冻结口径；[medianRank] / [cleanLo] / [cleanHi] /
     * [snapWindowUs] 是留给"尺子能红"突变体的旋钮（改一处 → 对拍必红），**不是**生产配置。
     *
     * 只借用 [MeasuredSrcFps] 这枚值类型当返回值（它未被 M6-05 改动，`data class` 的 equals
     * 正合逐点比较）。档位表是 v1 的**冻结抄本**：生产 `KNOWN_RATES` 若扩充会一并红——那是有意的，
     * 扩充档位会改变旧 Int 调用点的输出，属需人裁决的行为变更。
     */
    private class LegacyIntRef(
        private val cleanLo: Int = 1_001,
        private val cleanHi: Int = 1_000_000,
        private val minDeltas: Int = 8,
        private val medianRank: (Int) -> Int = { it / 2 },
        private val snapWindowUs: Long = 2L,
    ) {
        fun measure(raw: List<Int>, fallbackNum: Long, fallbackDen: Long = 1L): MeasuredSrcFps {
            val clean = raw.filter { it in cleanLo..cleanHi }
            if (clean.size < minDeltas) return reduce(fallbackNum, fallbackDen)
            return snap(clean.sorted()[medianRank(clean.size)].toLong())
        }

        /** ±[snapWindowUs]µs 的纯整数交叉相乘吸附；未命中退原始有理数 10⁶/d */
        private fun snap(dUs: Long): MeasuredSrcFps {
            for ((num, den) in RATES) {
                val diff = dUs * num - 1_000_000L * den
                if (diff <= snapWindowUs * num && diff >= -snapWindowUs * num) {
                    return MeasuredSrcFps(num, den)
                }
            }
            return reduce(1_000_000L, dUs)
        }

        /** 约分（非正数按 (0,1) 哨兵）——与生产同语义，但本函数自写、不复用生产的 gcd */
        private fun reduce(num: Long, den: Long): MeasuredSrcFps {
            if (num <= 0L || den <= 0L) return MeasuredSrcFps(0L, 1L)
            val g = gcd(num, den)
            return MeasuredSrcFps(num / g, den / g)
        }

        private fun gcd(a: Long, b: Long): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }

        private companion object {
            /** v1 档位表冻结抄本（与生产同序，但**不引用**它） */
            val RATES: List<Pair<Long, Long>> = listOf(
                24L to 1L, 25L to 1L, 30L to 1L, 50L to 1L, 60L to 1L, 120L to 1L, 240L to 1L,
                24_000L to 1_001L, 30_000L to 1_001L, 60_000L to 1_001L,
            )
        }
    }

    // endregion

    // region P3-1 滤噪窗单一事实源（Int 视图由 Long 版派生）

    /**
     * P3-1：`CLEAN_RANGE`（Int）与 `CLEAN_RANGE_LONG` 曾是**两份字面量**，任一处被单独改动都会让
     * 两条路径的窗界背离。现由 Long 版派生 ⇒ 按定义相等。末尾那条相等断言本身是**构造不变量（弱）**，
     * 只防"有人把派生改回两份不同字面量"，不构成行为证明——真正钉窗界的是中间四条**行为**断言
     * （窗内外各取一对样本：改窗界必翻转结果）。
     */
    @Test
    fun `滤噪窗单一事实源_两视图同界且窗沿行为相符`() {
        // 行为①：窗下沿 1001 入窗、1000 出窗。若 1000 被收进窗，中位数位次从 41708 落到 33333，
        // 结果会从 24/1.001 翻成 30 —— 窗界被改必红。
        val belowLoInt = listOf(1_000) + List(4) { 33_333 } + List(4) { 41_708 }
        assertEquals(
            "窗下沿 1000 必须出窗（否则中位数被翻转成 30）",
            rate(24_000, 1_001),
            ArcRateProbe.measuredSrcFps(belowLoInt, 24)
        )
        // 行为②：窗上沿 1_000_000 入窗、1_000_001 出窗。出窗则 7 枚有效样本不足 8，仍退 fallback。
        val aboveHiInt = List(7) { 41_708 } + listOf(1_000_001)
        assertEquals(
            "窗上沿 1_000_001 必须出窗（否则样本门槛被凑满、fallback 被绕过）",
            rate(30, 1),
            ArcRateProbe.measuredSrcFps(aboveHiInt, 30)
        )
        // 行为③：窗沿**内**两枚必须真被收下（防窗被整体收窄）：1001 → 10⁶/1001、1_000_000 → 1fps
        // （`List(8) { 1_001 }` 的元素类型对 Int/Long 两个重载都可行 ⇒ 必须点名 `List<Int>`，
        //   否则重载解析歧义——本文件开头同类坑的老教训）
        assertEquals(rate(1_000_000, 1_001), ArcRateProbe.measuredSrcFps(List<Int>(8) { 1_001 }, 24))
        assertEquals(rate(1, 1), ArcRateProbe.measuredSrcFps(List<Int>(8) { 1_000_000 }, 24))
        // 行为④：Long 核跑同一批样本必须同值（Int 值恒在 Int 域内，两视图窗界只能同界）
        assertEquals(
            ArcRateProbe.measuredSrcFps(belowLoInt.map { it.toLong() }, 24),
            ArcRateProbe.measuredSrcFps(belowLoInt, 24)
        )
        assertEquals(
            ArcRateProbe.measuredSrcFps(aboveHiInt.map { it.toLong() }, 30),
            ArcRateProbe.measuredSrcFps(aboveHiInt, 30)
        )
        // 弱不变量（构造）：派生后两者按定义同界，此处只作"别改回两份字面量"的哨兵
        assertEquals(
            "P3-1：Int 滤噪窗必须由 Long 版派生（同界）",
            ArcRateProbe.CLEAN_RANGE_LONG.first.toInt()..ArcRateProbe.CLEAN_RANGE_LONG.last.toInt(),
            ArcRateProbe.CLEAN_RANGE
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
