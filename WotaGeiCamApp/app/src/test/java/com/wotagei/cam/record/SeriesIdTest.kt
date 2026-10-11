package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * 分段系列 id（#5 Step 4a）的真值表。
 *
 * 治的是 `docs/scan/S2` M4-07 登记的碰撞项：老写法 `"S" + nowMillis + "_" + Random.nextInt(1000)`
 * 在同一毫秒内只有 1000 个可能值，批量起录 / 自动重录挤在一毫秒时会撞。撞了的代价不是"重试"——
 * 两个系列的段落互挂，组卡里会出现别人的片段。
 *
 * 类型红线：**必须是 Long**（Room 侧 `MediaSeries.seriesId` 是 Long 主键）。
 * 把实体改成 String 主键 = 改列 affinity = 改 schema = 撞 `fallbackToDestructiveMigration()`
 * ⇒ 升级即清空用户的收藏 / 标签 / 回收站 / 系列。所以生成端归一，实体一字不动。
 *
 * 突变自证见文件末尾两个用例：去掉 `max(it+1, …)`、返回常量两种退化都必须让「互异」变红。
 */
class SeriesIdTest {

    @Before
    fun reset() = SeriesIds.resetForTest()

    @Test
    fun `固定时刻下序号递增则id互异且严格递增`() {
        val now = 1_700_000_000_000L
        val ids = (0L..2L).map { SeriesIds.packSeriesId(now, it) }
        assertEquals("同一时刻不同序号必须给出不同的 id", 3, ids.toSet().size)
        assertTrue("序号递增时 id 必须严格递增：$ids", ids[0] < ids[1] && ids[1] < ids[2])
    }

    @Test
    fun `时刻递增时id严格大于旧值`() {
        // 低位序号取模回绕（999 → 下一毫秒的 0）也不许让 id 倒退
        val a = SeriesIds.packSeriesId(1_700_000_000_000L, 999)
        val b = SeriesIds.packSeriesId(1_700_000_000_001L, 0)
        assertTrue("跨毫秒必须严格递增（低位序号回绕也不许倒退）", b > a)
    }

    @Test
    fun `打包结果不溢出Long`() {
        // 上界约 1.7e15，远小于 Long 上限 9.2e18；这条钉的是"别哪天把乘数调大就静默溢出成负数"
        val worst = SeriesIds.packSeriesId(Long.MAX_VALUE / 1000, 999)
        assertTrue("极端时刻不得溢出成负数：$worst", worst > 0)
    }

    @Test
    fun `生成器连续十万次全部互异且严格递增`() {
        val ids = (0 until 100_000).map { SeriesIds.nextSeriesId() }
        assertEquals("十万次生成出现重复 id", 100_000, ids.toSet().size)
        // 不只是"互异"——不递增的实现可能只是随机撞运气凑够 1000 个值
        assertTrue("生成结果必须严格递增", ids.zipWithNext().all { (a, b) -> a < b })
    }

    /** 真实场景：批量起录 / 自动重录挤在同一毫秒内（此时只有 `it + 1` 兜得住） */
    @Test
    fun `同一毫秒内连续生成也互异`() {
        val ids = (0 until 10_000).map { SeriesIds.nextSeriesId(1_700_000_000_000L) }
        assertEquals("同一毫秒内连生成一万次也必须互异", 10_000, ids.toSet().size)
    }

    @Test
    fun `时钟回拨时不倒退`() {
        val t = 1_700_000_000_000L
        val first = SeriesIds.nextSeriesId(t)
        // 模拟系统时间被往回调 10 秒：只靠时间基的实现会给出更小的值，
        // 那会让旧系列的段落被新系列顶掉（upsert 是 REPLACE）
        val afterRewind = SeriesIds.nextSeriesId(t - 10_000)
        assertTrue("时钟回拨后 id 不得倒退", afterRewind > first)
    }

    // ------------------------------------------------------------------ 突变自证

    /**
     * 突变体一：与真实现唯一的差别是**去掉 `max(it + 1, …)` 里的 `it + 1`**（只留时间基）。
     *
     * 真偏离、不是语义空操作：同一毫秒内连调两次，真实现给出相差 1 的两个值，
     * 它给出**同一个值**——「互异」在真值表上由真变假。
     */
    private class NoMonotonicGen {
        private val last = AtomicLong(0L)
        fun next(nowMs: Long): Long = last.updateAndGet { SeriesIds.packSeriesId(nowMs, 0) }
    }

    /** 突变体二：返回常量（最粗的退化） */
    private object ConstantGen {
        fun next(nowMs: Long): Long = 42L
    }

    @Test
    fun `突变自证：同毫秒两次取值真实现互异而两个突变体都不互异`() {
        val now = 1_700_000_000_000L
        // 真值表对照组：真实现
        assertTrue(
            "真实现同毫秒连调两次必须互异（这正是 max(it+1, …) 存在的理由）",
            SeriesIds.nextSeriesId(now) != SeriesIds.nextSeriesId(now)
        )
        // 突变体一：同毫秒两次 ⇒ 相等（真偏离）
        val m1 = NoMonotonicGen()
        assertEquals("突变体一去掉 it+1 后同毫秒两次取值必须相等（证明它真偏离）", m1.next(now), m1.next(now))
        // 突变体二：任何两次 ⇒ 相等
        assertEquals("突变体二返回常量，任何两次取值必须相等", ConstantGen.next(now), ConstantGen.next(now + 1))
    }

    /**
     * 更硬的一条：把十万次互异判据**直接跑在两个突变体上**。
     *
     * 上一条只证明"同毫秒两次"这一格；这条证明规模一上去判据仍然会红——
     * 免得判据只在两三次的小样本上灵。
     */
    @Test
    fun `突变自证：十万次互异判据在突变体上必红`() {
        val realIds = (0 until 100_000).map { SeriesIds.nextSeriesId() }
        assertEquals("真实现十万次必须互异", 100_000, realIds.toSet().size)

        val m1 = NoMonotonicGen()
        val m1Ids = (0 until 100_000).map { m1.next(1_700_000_000_000L) }
        assertTrue("突变体一（去掉 it+1）十万次必然重复", m1Ids.toSet().size < m1Ids.size)

        val m2 = (0 until 100_000).map { ConstantGen.next(it.toLong()) }
        assertTrue("突变体二（返回常量）必然重复", m2.toSet().size < m2.size)
    }
}
