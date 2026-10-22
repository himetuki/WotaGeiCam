package com.wotagei.cam.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制收尾落库的**纯逻辑真值表**（#5 Step 4b）。
 *
 * Room 那层 JVM 测不了（要 Context），所以规则全抽进 [SeriesStore.seriesPartsToRecord]：
 * `parseId` 做成入参，把 `ContentUris`/`Uri` 两个 Android 类型挡在测试之外。
 *
 * 覆盖的规则：
 * 1. `uriStr` 为 null 的段**不收**（没登记进 MediaStore 的段在媒体库里根本不存在）；
 * 2. `parseId` 解析不出的段**跳过**（`mediaId` 是主键的一半，塞 0 会让整组挂到不存在的媒体上）；
 * 3. 字段逐字对得上（seriesId / partIndex / mediaId / uri / durationMs 一路传下去）；
 * 4. 乱序输入保持引擎给的段序，读侧才排得出序；
 * 5. 门槛：有效段 < 2 ⇒ 返回空（单段不成系列）。
 *
 * 突变自证见末尾：把 ① 门槛改成 `< 1`、② 去掉 uri 空段过滤，两个突变体都必须与真规则**行数不同**。
 */
class SeriesStoreTest {

    /** 测试用的假 parseId：`content://…/<id>` 取末段，解析不出或负数一律视为失败 */
    private val parse = { s: String -> s.substringAfterLast('/').toLongOrNull()?.takeIf { it >= 0 } }

    private fun p(index: Int, id: Long?, dur: Long = 1_000L) =
        SeriesPartInput(index, id?.let { "content://media/external/video/media/$it" }, dur)

    @Test
    fun `三段全部收下且字段逐字对得上`() {
        val rows = SeriesStore.seriesPartsToRecord(7L, listOf(p(0, 100L), p(1, 101L), p(2, 102L, 2_500L)), parse)
        assertEquals(3, rows.size)
        assertEquals(7L, rows[0].seriesId)
        assertEquals(0, rows[0].partIndex)
        assertEquals(100L, rows[0].mediaId)
        assertEquals("content://media/external/video/media/100", rows[0].uri)
        assertEquals(1_000L, rows[0].durationMs)
        assertEquals(2_500L, rows[2].durationMs)
    }

    @Test
    fun `uri为空的段不收`() {
        val rows = SeriesStore.seriesPartsToRecord(
            7L,
            listOf(p(0, 100L), SeriesPartInput(1, null, 2_000L), p(2, 102L)),
            parse
        )
        assertEquals("uri 为空的段必须跳过", 2, rows.size)
        assertEquals(listOf(0, 2), rows.map { it.partIndex })
    }

    @Test
    fun `解析不出mediaId的段跳过`() {
        val rows = SeriesStore.seriesPartsToRecord(7L, listOf(p(0, 100L), p(1, null), p(2, 102L)), parse)
        assertEquals("解析不出 id 的段必须跳过", 2, rows.size)
        assertEquals(listOf(0, 2), rows.map { it.partIndex })
    }

    @Test
    fun `乱序输入保持引擎给的段序不重排`() {
        val rows = SeriesStore.seriesPartsToRecord(7L, listOf(p(2, 102L), p(0, 100L), p(1, 101L)), parse)
        assertEquals("落库顺序必须原样保留 partIndex", listOf(2, 0, 1), rows.map { it.partIndex })
    }

    /** 门槛：单段不成系列（放纯函数里才测得到，见 SeriesStore 里的取舍说明） */
    @Test
    fun `单段不成系列`() {
        assertEquals("单段不成系列（门槛 2）", 2, MIN_SERIES_PARTS)
        assertTrue(
            "只有一段时必须返回空（record 见到空列表直接返回，不写库）",
            SeriesStore.seriesPartsToRecord(7L, listOf(p(0, 100L)), parse).isEmpty()
        )
        assertEquals(
            "两段才成系列",
            2,
            SeriesStore.seriesPartsToRecord(7L, listOf(p(0, 100L), p(1, 101L)), parse).size
        )
    }

    /** 全是废段（uri 全 null）同样不成系列 */
    @Test
    fun `全废段不成系列`() {
        val rows = SeriesStore.seriesPartsToRecord(
            7L,
            listOf(SeriesPartInput(0, null, 1_000L), SeriesPartInput(1, null, 1_000L)),
            parse
        )
        assertTrue("全是废段时必须返回空", rows.isEmpty())
    }

    // ------------------------------------------------------------------ 突变自证

    /**
     * 突变体一：把门槛 `< 2` 改成 `< 1`（照抄生产逻辑，只动这一处）。
     *
     * 真偏离不是语义空操作：单段输入下真规则返回 **0 行**、突变体返回 **1 行**，
     * 真值表上确实分叉 —— 「单段不成系列」这条判据只可能由真规则满足。
     */
    private fun mutantGateOne(
        seriesId: Long,
        parts: List<SeriesPartInput>,
        parseId: (String) -> Long?
    ): List<SeriesPart> {
        val rows = parts.mapNotNull { p ->
            val uriStr = p.uriStr ?: return@mapNotNull null
            val mediaId = parseId(uriStr) ?: return@mapNotNull null
            SeriesPart(seriesId, p.partIndex, mediaId, uriStr, p.durationMs)
        }
        return if (rows.size < 1) emptyList() else rows // ← 唯一的差别
    }

    /**
     * 突变体二：去掉 `uriStr ?: return@mapNotNull null` 这一句（空段也收）。
     *
     * 真偏离：3 段（其中 1 段 uri 为空）输入下，真规则出 2 行、突变体出 3 行。
     */
    private fun mutantKeepNullUri(
        seriesId: Long,
        parts: List<SeriesPartInput>,
        parseId: (String) -> Long?
    ): List<SeriesPart> {
        val rows = parts.map { p ->
            val uriStr = p.uriStr.orEmpty()
            SeriesPart(seriesId, p.partIndex, parseId(uriStr) ?: -1L, uriStr, p.durationMs)
        }
        return if (rows.size < MIN_SERIES_PARTS) emptyList() else rows // ← 唯一的差别
    }

    @Test
    fun `突变自证一：门槛改成1则单段被收下真规则收不下`() {
        val one = listOf(p(0, 100L))
        val real = SeriesStore.seriesPartsToRecord(7L, one, parse)
        val mutant = mutantGateOne(7L, one, parse)
        assertTrue("对照组：真规则下单段返回空", real.isEmpty())
        assertEquals("突变体一必须真的偏离（门槛改 1 ⇒ 单段也收）", 1, mutant.size)
    }

    @Test
    fun `突变自证二：uri空段也收则真值表分叉`() {
        val mixed = listOf(p(0, 100L), SeriesPartInput(1, null, 2_000L), p(2, 102L))
        val real = SeriesStore.seriesPartsToRecord(7L, mixed, parse)
        val mutant = mutantKeepNullUri(7L, mixed, parse)
        assertEquals("对照组：真规则下 3 段进 2 段出", 2, real.size)
        assertEquals("突变体二必须真的偏离（空段也收 ⇒ 3 段进 3 段出）", 3, mutant.size)
        assertTrue("两个突变体必须与真规则分叉，否则这条自证什么也证明不了", real.size != mutant.size)
    }
}
