package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 封口段独立发布路（R06）的行为测试：钉**生产判据**，不是钉实现细节。
 *
 * 覆盖三件事：
 * 1. **分类**：发布者实际返回什么就记什么——尤其 `store.seal(keep=false)` 返回
 *    `committed=true`（它删了记录），绝不能按 `committed` 把废片当已发布；
 * 2. **预算为 0 也发布**：等待窗只决定"能否同步拿回结论"，不决定"要不要发布"（R06 路径一）；
 * 3. **迟到不丢结论**：同步窗口超时返回 null ≠ 失败，结论经迟到回调补发一次（R06 路径二，
 *    相册可见但结果/系列对不上）；迟到回调抛错不许炸发布线程。
 *
 * 时间参数一律用短窗（≤300ms）并配 latch/轮询等待，不使用固定 sleep 赌时序。
 */
class SealedPublicationTest {

    private fun seg(i: Int, bytes: Long = 1_000L, committed: Boolean = true): VideoSegment = VideoSegment(
        uri = null,
        path = "/tmp/part$i.mp4",
        partIndex = i,
        bytes = bytes,
        startMs = i * 1_000L,
        durationMs = 1_000L,
        committed = committed
    )

    private fun draft(i: Int, keep: Boolean = true) = SealedDraft(i, i * 1_000L, 1_000L, keep)

    /** 轮询等条件成立（最多 2s）：不改用固定 sleep 赌线程调度 */
    private fun awaitUntil(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("等待超时：$what")
    }

    // ------------------------------------------------------------------ 分类真值表

    @Test
    fun `保留且已清IS_PENDING的段判已发布`() {
        val p = classifyPublished(draft(0), seg(0), null)
        assertEquals(PublishClass.PUBLISHED, p.classification)
        assertTrue("已发布段必须可进结果/结算", p.keep)
        assertNotNull("已发布段必须有段视图", p.visibleSegment())
    }

    @Test
    fun `保留但没清IS_PENDING的段判未发布_仍进结果但不进结算`() {
        val p = classifyPublished(draft(0), seg(0, committed = false), null)
        assertEquals(PublishClass.UNPUBLISHED, p.classification)
        assertTrue("文件在盘上，必须进结果（UI 报可能看不到）", p.keep)
        assertFalse("未发布段不许进系列/练习结算", p.visibleSegment()!!.committed)
    }

    /** 关键分类陷阱：`store.seal(keep=false)` 删记录后返回 `committed=true` */
    @Test
    fun `废片即使committed为true也只判废弃`() {
        val p = classifyPublished(draft(0, keep = false), seg(0, committed = true), null)
        assertEquals(PublishClass.DISCARDED, p.classification)
        assertFalse("废弃段绝不能进结果清单（记录已删，uri 指向不存在的行）", p.keep)
        assertNull("废弃段没有可交出的段视图", p.visibleSegment())
    }

    @Test
    fun `发布抛错或没有结论一律判失败_不许伪造产物`() {
        val thrown = classifyPublished(draft(0), null, IllegalStateException("binder dead"))
        assertEquals(PublishClass.FAILED, thrown.classification)
        assertNull("失败段不许有段视图", thrown.visibleSegment())
        assertFalse("失败段不许进结果", thrown.keep)
        val nulled = classifyPublished(draft(0), null, null)
        assertEquals("没有结论同样不许当成功", PublishClass.FAILED, nulled.classification)
    }

    // ------------------------------------------------------------------ 发布器：同步窗口

    @Test
    fun `等待窗内完成直接交回真实结论`() {
        val publisher = SealedPublisher(waitMs = 2_000L)
        val taken = publisher.publish(draft(1)) { seg(1, bytes = 4_242L) }
        assertNotNull("窗内完成必须同步交回结论（不许让调用方白等/猜成功）", taken)
        assertEquals(4_242L, taken!!.segment!!.bytes)
        assertEquals(listOf(1), publisher.publishedSegments().map { it.partIndex })
        assertEquals("同步完成不许再走迟到回调", 1, publisher.outcomes().size)
    }

    @Test
    fun `发布器抛错也同步交回失败分类`() {
        val publisher = SealedPublisher(waitMs = 2_000L)
        val taken = publisher.publish(draft(2)) { throw IllegalStateException("boom") }
        assertNotNull(taken)
        assertEquals(PublishClass.FAILED, taken!!.classification)
        assertTrue("失败结论也必须留档（诊断/对账可见）", publisher.outcomes().any { it.draft.partIndex == 2 })
        assertTrue("失败段不进已发布清单", publisher.publishedSegments().isEmpty())
    }

    @Test
    fun `废片同步完成走废弃分类且不进已发布清单`() {
        val publisher = SealedPublisher(waitMs = 2_000L)
        val taken = publisher.publish(draft(3, keep = false)) { seg(3, committed = true) }
        assertEquals(PublishClass.DISCARDED, taken!!.classification)
        assertTrue(publisher.publishedSegments().isEmpty())
        assertEquals(1, publisher.outcomes().size)
    }

    // ------------------------------------------------------------------ 预算为 0 / 迟到

    @Test
    fun `等待窗为0也必须启动发布_结论只经迟到回调补发`() {
        val published = CountDownLatch(1)
        val lateParts = ArrayList<PublishedPart>()
        val publisher = SealedPublisher(waitMs = 0L) { part ->
            synchronized(lateParts) { lateParts.add(part) }
            published.countDown()
        }
        val started = AtomicInteger(0)
        val taken = publisher.publish(draft(4)) {
            started.incrementAndGet()
            seg(4)
        }
        assertNull("等待窗为 0 只能交回「未拿到结论」，不是失败", taken)
        assertTrue("发布必须真的启动（R06 路径一：没预算也要清 IS_PENDING）", published.await(2, TimeUnit.SECONDS))
        assertEquals(1, started.get())
        val parts = synchronized(lateParts) { lateParts.toList() }
        assertEquals(1, parts.size)
        assertEquals(PublishClass.PUBLISHED, parts[0].classification)
        assertEquals(listOf(4), publisher.publishedSegments().map { it.partIndex })
    }

    @Test
    fun `等待窗超时不取消发布_迟到结论补发一次且可被收尾取到`() {
        val published = CountDownLatch(1)
        val lateCalls = AtomicInteger(0)
        val publisher = SealedPublisher(waitMs = 40L) { part ->
            assertEquals(PublishClass.PUBLISHED, part.classification)
            lateCalls.incrementAndGet()
            published.countDown()
        }
        val taken = publisher.publish(draft(5)) {
            Thread.sleep(200)
            seg(5, bytes = 7_777L)
        }
        assertNull("窗内没完成 ⇒ 调用方只拿到 null（不许伪造结论）", taken)
        assertTrue("迟到发布必须补发结论", published.await(2, TimeUnit.SECONDS))
        awaitUntil("迟到段进发布台账") { publisher.publishedSegments().any { it.partIndex == 5 } }
        assertEquals("迟到结论只许补发一次", 1, lateCalls.get())
        assertEquals(7_777L, publisher.publishedSegments().single { it.partIndex == 5 }.bytes)
    }

    @Test
    fun `迟到回调抛错不许炸发布线程`() {
        val publisher = SealedPublisher(waitMs = 0L) { throw IllegalStateException("callback bug") }
        val taken = publisher.publish(draft(6)) { seg(6) }
        assertNull(taken)
        awaitUntil("迟到的结果仍然落账") { publisher.publishedSegments().any { it.partIndex == 6 } }
    }

    @Test
    fun `同一段迟到与同步结论只有一份台账`() {
        val publisher = SealedPublisher(waitMs = 1_500L)
        publisher.publish(draft(7)) { seg(7) }
        assertEquals(1, publisher.outcomes().count { it.draft.partIndex == 7 })
        assertTrue(publisher.publishedSegments().count { it.partIndex == 7 } == 1)
    }

    // ------------------------------------------------------------------ 段清单合并（纯函数）

    @Test
    fun `段清单按段序去重且已有段优先`() {
        val existing = listOf(seg(0, bytes = 10L), seg(2, bytes = 30L))
        val extra = listOf(seg(2, bytes = 300L), seg(1, bytes = 20L))
        val merged = mergeSegments(existing, extra)
        assertEquals(listOf(0, 1, 2), merged.map { it.partIndex })
        assertEquals("同段不许被迟到台账覆盖（它们是同一结果的两份引用）", 30L, merged[2].bytes)
    }

    @Test
    fun `没有新增段时合并返回原对象`() {
        val existing = listOf(seg(0))
        assertSame(existing, mergeSegments(existing, emptyList()))
        val same = mergeSegments(existing, listOf(seg(0, bytes = 999L)))
        assertSame("集合没变大 ⇒ 不白造新对象（结算键也不动）", existing, same)
    }

    // ------------------------------------------------------------------ 结果视图合并

    @Test
    fun `迟到段并进结果且失败诊断不被洗白`() {
        val base = RecordResult(
            uri = null,
            path = "/tmp/part0.mp4",
            bytes = 1_000L,
            durationMs = 9_000L,
            error = "${RecordError.ROTATE_STALL}:1200",
            seriesId = 7L,
            parts = listOf(seg(0)),
            uncommitted = 0,
            lostMs = 1_200L,
            degradedCode = null
        )
        val merged = mergeSessionResult(base, listOf(seg(1, bytes = 2_000L)))
        assertEquals("补一段不洗白失败码（防假成功）", base.error, merged.error)
        assertEquals("会话级归属不变", 7L, merged.seriesId)
        assertEquals("时长/丢失账是会话级量，不重算", 9_000L, merged.durationMs)
        assertEquals(1_200L, merged.lostMs)
        assertEquals(listOf(0, 1), merged.parts.map { it.partIndex })
        assertEquals("总字节要重算", 3_000L, merged.bytes)
        assertEquals("未登记数要重算", 0, merged.uncommitted)
        assertFalse("有失败码就仍是失败结果", merged.ok)
    }

    @Test
    fun `迟到段给空结果补上回看字段但没新增段时原样返回`() {
        val empty = RecordResult(null, null, 0L, 3_000L, RecordError.TOO_SHORT)
        val filled = mergeSessionResult(empty, listOf(seg(0, bytes = 5L)))
        assertEquals("/tmp/part0.mp4", filled.path)
        assertEquals(5L, filled.bytes)
        assertEquals("失败码仍不清空", RecordError.TOO_SHORT, filled.error)
        val again = mergeSessionResult(filled, listOf(seg(0, bytes = 5L)))
        assertSame(filled, again)
    }
}