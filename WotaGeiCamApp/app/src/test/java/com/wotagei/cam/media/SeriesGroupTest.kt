package com.wotagei.cam.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段系列归组的真值表（#5 Step 4c）。
 *
 * # 为什么测 [SeriesGroup.groupSpans] 而不是 [SeriesGroup.galleryEntries]
 * `VideoClip` 里躺着 `android.net.Uri`，本仓 JVM 单测没有 Robolectric/mockk：
 * android.jar 桩里 `Uri.EMPTY` 是 **null**、任何方法直接抛 `Stub!` ⇒ 在 JVM 里
 * **造不出一个 VideoClip**（`Uri.parse` 会抛，绕过去又会被 data class 的
 * `checkNotNullParameter` 拦下）。所以归组规则落在只吃 [SeriesKey]、只吐索引的
 * [groupSpans] 上，本文件测它；[galleryEntries] 只是它的薄适配层，由
 * [SeriesGroupGuardTest] 钉接线。
 *
 * # 覆盖
 * 1. `seriesId == null` → 单条（不参与归组）；
 * 2. 2/3 段乱序输入 → 成组、`indices` 升序对应 `partIndex`；
 * 3. 组内可见成员只剩 1 个 → 退化成单条（页签只过滤成员，归组对剩下的成员做）；
 * 4. 两组互不串、顺序按首见位稳定；
 * 5. `partIndex` 为 null 的脏数据排末尾、不散组；
 * 6. `collapse = false` → 逐字等于输入的扁平列表（今天的基线 + 回退开关）。
 *
 * 突变自证见末尾：三个退化各证一条断言，且都是照抄生产逻辑只动一处的**真偏离**。
 */
class SeriesGroupTest {

    private fun k(id: Long, seriesId: Long? = null, partIndex: Int? = null) =
        SeriesKey(id, seriesId, partIndex)

    // ------------------------------------------------------------------ 基本归组

    @Test
    fun `空表返回空`() {
        assertTrue(SeriesGroup.groupSpans(emptyList()).isEmpty())
    }

    @Test
    fun `seriesId为null的片各自成一条`() {
        val spans = SeriesGroup.groupSpans(listOf(k(1), k(2), k(3)))
        assertEquals("三张单段片必须出三条", 3, spans.size)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), spans.map { it.indices })
        assertTrue("全都不是组", spans.all { it.seriesId == null })
    }

    @Test
    fun `两段乱序输入成组且按partIndex升序`() {
        val spans = SeriesGroup.groupSpans(listOf(k(11, 7L, 1), k(10, 7L, 0)))
        assertEquals("两段必须折成一条", 1, spans.size)
        assertEquals(7L, spans[0].seriesId)
        assertEquals("必须按 partIndex 升序（输入是乱的：下标1 在前）", listOf(1, 0), spans[0].indices)
    }

    @Test
    fun `三段乱序输入仍升序`() {
        val spans = SeriesGroup.groupSpans(listOf(k(13, 7L, 2), k(11, 7L, 0), k(12, 7L, 1)))
        assertEquals(1, spans.size)
        assertEquals("必须按 partIndex 0,1,2 排", listOf(1, 2, 0), spans[0].indices)
    }

    /** 组内可见成员只剩 1 个（其余被别的页签过滤掉 / 被删了）⇒ 退化成单条 */
    @Test
    fun `组内只剩一个可见成员时退化成单条`() {
        val spans = SeriesGroup.groupSpans(listOf(k(10, 7L, 0)))
        assertEquals(1, spans.size)
        assertNull("只剩一段时不得再成组（否则卡上的组级动作会扇出到看不见的成员）", spans[0].seriesId)
        assertEquals(listOf(0), spans[0].indices)
    }

    @Test
    fun `输出顺序按输入顺序且组站在首见位`() {
        val spans = SeriesGroup.groupSpans(listOf(k(1), k(20, 7L, 0), k(21, 7L, 1), k(2)))
        assertEquals("四条 entry：单片 / 组 / 单片", 3, spans.size)
        assertEquals(listOf(0), spans[0].indices)
        assertEquals("组站在它首见的位置（下标 1），不是排在最后", listOf(1, 2), spans[1].indices)
        assertEquals(listOf(3), spans[2].indices)
    }

    @Test
    fun `两个组互不串`() {
        val spans = SeriesGroup.groupSpans(
            listOf(k(10, 1L, 0), k(11, 1L, 1), k(20, 2L, 0), k(21, 2L, 1))
        )
        assertEquals(2, spans.size)
        assertEquals(listOf(1L, 2L), spans.map { it.seriesId })
        assertEquals(listOf(0, 1), spans[0].indices)
        assertEquals(listOf(2, 3), spans[1].indices)
    }

    /** 今日扁平列表基线 / 回退开关：collapse=false 逐字等于输入 */
    @Test
    fun `collapse为false时逐字等于输入`() {
        val keys = listOf(k(1), k(10, 7L, 0), k(11, 7L, 1), k(20, 8L, 0), k(21, 8L, 1), k(2))
        val spans = SeriesGroup.groupSpans(keys, collapse = false)
        assertEquals("折叠关掉必须一条不折", keys.size, spans.size)
        assertEquals("必须逐字等于输入（顺序也不许变）", keys.indices.map { listOf(it) }, spans.map { it.indices })
        assertTrue("全部必须是单条", spans.all { it.seriesId == null })
    }

    /** 脏数据 partIndex 为 null：不许因此把整组打散，null 排末尾 */
    @Test
    fun `partIndex为null的脏数据排末尾不散组`() {
        val spans = SeriesGroup.groupSpans(listOf(k(10, 7L, null), k(11, 7L, 1), k(12, 7L, 0)))
        assertEquals("不得散组", 1, spans.size)
        assertEquals("null 排末尾，0/1 按序", listOf(2, 1, 0), spans[0].indices)
    }

    /** 同一 seriesId 但只有 1 段（库里残留的头）⇒ 单条，不成组 */
    @Test
    fun `成组门槛是2不是1`() {
        assertEquals("门槛必须是 2", 2, SeriesGroup.MIN_GROUP_SIZE)
        assertNull(SeriesGroup.groupSpans(listOf(k(10, 7L, 0))).single().seriesId)
        assertEquals(7L, SeriesGroup.groupSpans(listOf(k(10, 7L, 0), k(11, 7L, 1))).single().seriesId)
    }

    // ------------------------------------------------------------------ 突变自证

    /**
     * 三个突变体各自照抄 [SeriesGroup.groupSpans] 的判定逻辑、**只动一处**：
     * ① `collapse` 判据恒 false；② 不排序（代表段随输入首见）；③ 门槛放宽到 1。
     *
     * 这么写而不是"直接断言期望值"：断言期望值只能证明输出对，证明不了**判据能红**——
     * 一条恒真的断言和一个正确的实现在测试里长得一模一样。突变体让三处分别翻脸。
     */
    private fun mutantCollapseAlwaysFalse(keys: List<SeriesKey>): List<GroupSpan> =
        keys.indices.map { GroupSpan(null, listOf(it)) }

    private fun mutantNoSort(keys: List<SeriesKey>): List<GroupSpan> {
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        keys.forEachIndexed { idx, k ->
            k.seriesId?.let { groups.getOrPut(it) { ArrayList() } += idx }
        }
        val out = ArrayList<GroupSpan>(keys.size)
        val emitted = HashSet<Long>()
        keys.forEachIndexed { idx, k ->
            val sid = k.seriesId
            if (sid == null) { out += GroupSpan(null, listOf(idx)); return@forEachIndexed }
            if (!emitted.add(sid)) return@forEachIndexed
            val members = groups[sid].orEmpty()
            if (members.size < SeriesGroup.MIN_GROUP_SIZE) { members.forEach { out += GroupSpan(null, listOf(it)) }; return@forEachIndexed }
            out += GroupSpan(sid, members) // ← 唯一的差别：不排序
        }
        return out
    }

    private fun mutantGateOne(keys: List<SeriesKey>): List<GroupSpan> {
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        keys.forEachIndexed { idx, k ->
            k.seriesId?.let { groups.getOrPut(it) { ArrayList() } += idx }
        }
        val out = ArrayList<GroupSpan>(keys.size)
        val emitted = HashSet<Long>()
        keys.forEachIndexed { idx, k ->
            val sid = k.seriesId
            if (sid == null) { out += GroupSpan(null, listOf(idx)); return@forEachIndexed }
            if (!emitted.add(sid)) return@forEachIndexed
            val members = groups[sid].orEmpty()
            if (members.size < 1) { out += GroupSpan(null, members); return@forEachIndexed } // ← 门槛改 1
            out += GroupSpan(sid, members.sortedBy { keys[it].partIndex ?: Int.MAX_VALUE })
        }
        return out
    }

    @Test
    fun `突变自证一：collapse恒false则折组判据必红`() {
        val keys = listOf(k(10, 7L, 0), k(11, 7L, 1))
        assertEquals("对照组：真实现两段折成一条", 1, SeriesGroup.groupSpans(keys).size)
        assertEquals("突变体一（恒不折叠）必须出两条", 2, mutantCollapseAlwaysFalse(keys).size)
    }

    @Test
    fun `突变自证二：不排序则升序判据必红`() {
        val keys = listOf(k(13, 7L, 2), k(11, 7L, 0), k(12, 7L, 1))
        assertEquals("对照组：必须按 partIndex 升序", listOf(1, 2, 0), SeriesGroup.groupSpans(keys).single().indices)
        assertEquals("突变体二（不排序）顺序必须不同", listOf(0, 1, 2), mutantNoSort(keys).single().indices)
    }

    @Test
    fun `突变自证三：门槛改成1则单成员组判据必红`() {
        val keys = listOf(k(10, 7L, 0))
        assertNull("对照组：真实现下单成员不成组", SeriesGroup.groupSpans(keys).single().seriesId)
        assertEquals("突变体三（门槛改 1）必须成组", 7L, mutantGateOne(keys).single().seriesId)
    }
}
