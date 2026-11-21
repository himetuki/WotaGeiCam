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
        val publisher = SealedPublisher(
            waitMs = 0L,
            onLate = { _: PublishedPart -> throw IllegalStateException("callback bug") }
        )
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

    // ------------------------------------------------------------------ 关闭所有权（先关后发布）

    /** close 与 publish 必须在同一枚线程上按序完成：close 未返回前绝不允许发布块开跑 */
    @Test
    fun `发布前必须先在发布线程上关闭fd`() {
        val publisher = SealedPublisher(waitMs = 2_000L)
        val order = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val threads = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val taken = publisher.publish(
            draft(8),
            close = {
                threads["close"] = Thread.currentThread().id
                order.add("close")
            }
        ) {
            threads["publish"] = Thread.currentThread().id
            order.add("publish")
            seg(8)
        }
        assertNotNull(taken)
        assertEquals(
            "close 没有返回，发布块绝不能先跑（未关 fd 就清 IS_PENDING = 与 close 赛跑）",
            listOf("close", "publish"),
            order.toList()
        )
        assertEquals(
            "关闭与发布必须同一枚线程（跨线程就是两路并发碰同一枚 fd）",
            threads["close"], threads["publish"]
        )
    }

    /** close 超过等待窗：调用线程按 null 返回（不伪造结论），发布照常完成、迟到补发恰一次 */
    @Test
    fun `关闭超过等待窗不取消发布_迟到结论仍补发`() {
        val lateCalls = AtomicInteger(0)
        val published = CountDownLatch(1)
        val publisher = SealedPublisher(waitMs = 60L) { _ ->
            lateCalls.incrementAndGet()
            published.countDown()
        }
        val closeCalls = AtomicInteger(0)
        val taken = publisher.publish(draft(9), close = {
            closeCalls.incrementAndGet()
            Thread.sleep(250)
        }) { seg(9, bytes = 8_888L) }
        assertNull("窗内没完成 ⇒ 只交回 null（不许伪造结论）", taken)
        assertTrue("关闭超时不取消发布（fd 最终必须有人关）", published.await(2, TimeUnit.SECONDS))
        assertEquals("close 恰好一次", 1, closeCalls.get())
        assertEquals("迟到结论只许补发一次", 1, lateCalls.get())
        assertEquals(8_888L, publisher.publishedSegments().single { it.partIndex == 9 }.bytes)
    }

    /** close 抛错 = 整段按失败分类，发布块根本不跑：不许在未关闭的句柄上清 IS_PENDING */
    @Test
    fun `关闭抛错则整段判失败且发布块不执行`() {
        val publisher = SealedPublisher(waitMs = 2_000L)
        val publishRan = AtomicInteger(0)
        val taken = publisher.publish(draft(10), close = { throw IllegalStateException("fd gone") }) {
            publishRan.incrementAndGet()
            seg(10)
        }
        assertNotNull(taken)
        assertEquals(PublishClass.FAILED, taken!!.classification)
        assertNull("失败段没有段视图", taken.visibleSegment())
        assertEquals("close 失败绝不能继续发布", 0, publishRan.get())
        assertTrue("失败结论同样留档", publisher.outcomes().any { it.draft.partIndex == 10 })
        assertTrue(publisher.publishedSegments().isEmpty())
    }

    /**
     * 严格关闭被 Provider 闩住时：发布块**一步都不许跑**，直到 close 返回。
     * 这是旧缺陷的直接回归（close 超时返回后发布线程已去查 SIZE/清 IS_PENDING）。
     */
    @Test
    fun `关闭被闩住时发布块绝不先跑_释放后才开始`() {
        val publisher = SealedPublisher(waitMs = 0L)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val publishRan = AtomicInteger(0)
        val publishDone = CountDownLatch(1)
        try {
            val taken = publisher.publish(draft(12), waitMs = 0L, close = {
                closeEntered.countDown()
                // 模拟卡住的 fd 关闭：闩住直到测试放行（永不释放就会挂住发布线程，用例自带 finally 兜底）
                releaseClose.await(3, TimeUnit.SECONDS)
            }) {
                publishRan.incrementAndGet()
                publishDone.countDown()
                seg(12)
            }
            assertNull("零等待只提交、不冒充结论", taken)
            assertTrue("close 必须真的在发布线程上开跑", closeEntered.await(2, TimeUnit.SECONDS))
            assertEquals("close 未返回 ⇒ 发布块绝不许先跑", 0, publishRan.get())
            assertEquals("close 未返回 ⇒ 结论不许落账", 0, publisher.outcomes().size)
        } finally {
            releaseClose.countDown()
        }
        assertTrue("释放后发布必须继续（close 超时/挂起不取消发布）", publishDone.await(2, TimeUnit.SECONDS))
        awaitUntil("释放后结论落账") { publisher.outcomes().any { it.draft.partIndex == 12 } }
        assertEquals("close 恰好一次、发布恰好一次", 1, publishRan.get())
    }

    /**
     * 发布 Provider 被闩住时，换段路径的零等待提交必须**立即返回**：换段腿不许把下一段的
     * insert/open 预算耗在等待发布上（旧实现内联等 3s）。
     */
    @Test
    fun `发布被闩住时零等待提交立即返回_不拖住换段腿`() {
        val publisher = SealedPublisher(waitMs = 0L)
        val blockEntered = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val publishDone = CountDownLatch(1)
        try {
            val startedNs = System.nanoTime()
            val taken = publisher.publish(draft(13)) {
                blockEntered.countDown()
                releaseBlock.await(3, TimeUnit.SECONDS)
                publishDone.countDown()
                seg(13)
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
            assertNull("零等待提交不交回结论", taken)
            assertTrue(
                "零等待提交必须立即返回（实测 ${elapsedMs}ms，发布被闩住也不许等）",
                elapsedMs < 500L
            )
            assertTrue("发布线程仍必须真的开跑", blockEntered.await(2, TimeUnit.SECONDS))
            assertEquals("发布未落地前 pendingCount 必须如实报 1", 1, publisher.pendingCount())
        } finally {
            releaseBlock.countDown()
        }
        assertTrue("释放后发布完成", publishDone.await(2, TimeUnit.SECONDS))
        awaitUntil("释放后 pending 归零") { publisher.pendingCount() == 0 }
    }

    @Test
    fun `关闭被闩住时迟到的剩余pending数按提交台账计算`() {
        val late = ArrayList<Pair<Int, Int>>()
        // 双参构造：第二参是"本次结论落账后还剩几段"（会话所有者据此判断发布路是否静默）
        val withRemaining = SealedPublisher(waitMs = 0L) { part, remaining ->
            synchronized(late) { late.add(part.draft.partIndex to remaining) }
        }
        val closeGate = CountDownLatch(1)
        withRemaining.publish(draft(14), waitMs = 0L, close = { closeGate.await(2, TimeUnit.SECONDS) }) { seg(14) }
        withRemaining.publish(draft(15), waitMs = 0L) { seg(15) }
        awaitUntil("第二段先落账并报 remaining=1") {
            synchronized(late) { late.contains(15 to 1) }
        }
        closeGate.countDown()
        awaitUntil("第一段随后落账并报 remaining=0") {
            synchronized(late) { late.contains(14 to 0) }
        }
        awaitUntil("发布路静默后 pendingCount 必须为 0") { withRemaining.pendingCount() == 0 }
    }

    /** 同一段重复提交幂等早退：不起第二枚发布线程、close/发布各至多一次 */
    @Test
    fun `同一段重复提交幂等早退不重复发布`() {
        val publisher = SealedPublisher(waitMs = 2_000L)
        val publishRuns = AtomicInteger(0)
        val closeRuns = AtomicInteger(0)
        val first = publisher.publish(draft(11), close = { closeRuns.incrementAndGet() }) {
            publishRuns.incrementAndGet()
            seg(11)
        }
        assertNotNull(first)
        val dup = publisher.publish(draft(11), close = { closeRuns.incrementAndGet() }) {
            publishRuns.incrementAndGet()
            seg(11, bytes = 999_999L)
        }
        assertNull("重复提交回 null（发布权已被首次提交占住）", dup)
        assertEquals("close 只跑一次", 1, closeRuns.get())
        assertEquals("发布块只跑一次", 1, publishRuns.get())
        assertEquals("台账里同一段只有一份结论", 1, publisher.outcomes().count { it.draft.partIndex == 11 })
        assertEquals(
            "结论取首次发布的真实结果，不被重入洗掉",
            1_000L, publisher.publishedSegments().single { it.partIndex == 11 }.bytes
        )
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