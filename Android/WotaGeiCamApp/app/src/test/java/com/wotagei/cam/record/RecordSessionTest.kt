package com.wotagei.cam.record

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话迟到对账（R06/R07 消费端）的行为测试。
 *
 * 钉住四条生产判据：
 * 1. **登记即结算**：停录线程交出结果那一刻就发起首次结算（不依赖页面 collect 还活着）；
 * 2. **迟到 grow ⇒ 再结算一次**：迟到 commit 成功改变已发布段集合，用最终集合收敛（REPLACE）；
 * 3. **不假成功**：迟到段不洗白失败码；泵线程终态（失败）压过早期快照（成功）后，
 *    练习桥被清态而不是交付；
 * 4. **恰好一次**：同键重复登记/重复交付不重复写库。
 *
 * 结算视图（[SettledPart]）在本测试里显式注入：JVM 单测造不出 `android.net.Uri`，
 * 而生产里的视图由 `VideoSegment.toSettledPart()` 给出（`uri.toString()`）。注入点与生产
 * 同一条路径（`RecordSession.record` 的 settled 参数就是生产默认值）。
 */
class RecordSessionTest {

    /** 每个用例一个独立会话键（对象是进程级单例，槽跨用例保留） */
    private fun session(): Long = System.nanoTime()

    private fun part(i: Int, committed: Boolean = true): SettledPart = SettledPart(
        partIndex = i,
        uriStr = "content://media/external/video/media/${100 + i}",
        durationMs = 1_000L,
        committed = committed
    )

    /** 发布路实际交出的段视图（JVM 单测没有 MediaStore uri，用 path 判"文件在盘上"） */
    private fun sealedSeg(i: Int, committed: Boolean = true): VideoSegment = VideoSegment(
        uri = null,
        path = "/tmp/part$i.mp4",
        partIndex = i,
        bytes = 1_000L,
        startMs = i * 1_000L,
        durationMs = 1_000L,
        committed = committed
    )

    private fun result(
        error: String? = null,
        path: String? = "/tmp/part0.mp4",
        seriesId: Long = 7L
    ) = RecordResult(
        uri = null,
        path = path,
        bytes = 1_000L,
        durationMs = 9_000L,
        error = error,
        seriesId = seriesId,
        parts = emptyList()
    )

    private class FakeBackend : TakeSettlementBackend {
        val writes = CopyOnWriteArrayList<List<SettledPart>>()
        val deliveries = CopyOnWriteArrayList<SettledPart>()
        val writeCalls = AtomicInteger()
        val clearCalls = AtomicInteger()

        override suspend fun writeSeries(
            seriesId: Long,
            startedAtMs: Long,
            parts: List<SettledPart>
        ): Int {
            writeCalls.incrementAndGet()
            writes += parts
            return parts.size
        }

        override fun deliverPractice(part: SettledPart): Boolean {
            deliveries += part
            return true
        }

        override fun clearPractice() {
            clearCalls.incrementAndGet()
        }
    }

    private fun awaitWrites(backend: FakeBackend, n: Int) {
        // 等**台账落地**（writeSeries 先自增计数再追加，只等计数会与追加赛跑：断言读 last() 时
        // 第二笔可能还没写进列表）
        awaitUntil("结算落库") { backend.writes.size >= n }
    }

    private fun awaitClears(backend: FakeBackend, n: Int) {
        awaitUntil("练习桥清态") { backend.clearCalls.get() >= n }
    }

    private fun awaitDeliveries(backend: FakeBackend, n: Int) {
        awaitUntil("练习交付") { backend.deliveries.size >= n }
    }

    private fun awaitUntil(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("等待${what}超时")
    }

    // ------------------------------------------------------------------ 主路径

    @Test
    fun `登记即结算_迟到发布落地后再结算收敛到最终集合`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val job = RecordSession.record(
            backend = backend,
            seriesId = 7L,
            startedAtMs = s,
            result = result(),
            practiceArmed = false,
            settled = listOf(part(0))
        )!!
        withTimeout(5_000) { job.await() }
        assertEquals("登记即发起首次结算", 1, backend.writeCalls.get())
        assertEquals("首次结算只含当时已发布段", listOf(0), backend.writes.last().map { it.partIndex })

        // R06：第 2 段迟到 commit 成功 ⇒ 已发布段集合变大 ⇒ 新键再结算一次
        RecordSession.onDelivery(
            RecorderDelivery.LatePart(
                seriesId = 7L,
                startedAtMs = s,
                partIndex = 1,
                segment = null,
                classification = PublishClass.PUBLISHED,
                settled = part(1)
            )
        )
        awaitWrites(backend, 2)
        assertEquals("最终集合必须收敛（REPLACE upsert 语义）", listOf(0, 1), backend.writes.last().map { it.partIndex })
    }

    @Test
    fun `迟到事件先于登记到达时缓存_登记后按快照首结算再收敛到最终集合`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        assertNull(
            "槽未登记：先缓存，不凭空结算",
            RecordSession.onDelivery(
                RecorderDelivery.LatePart(7L, s, 1, null, PublishClass.PUBLISHED, part(1))
            )
        )
        val job = RecordSession.record(
            backend = backend,
            seriesId = 7L,
            startedAtMs = s,
            result = result(),
            practiceArmed = false,
            settled = listOf(part(0))
        )!!
        // 返回的是**最终**任务（链上等早期那笔落库），await 它 = 收敛写已完成
        withTimeout(5_000) { job.await() }
        assertEquals("缓存事件必须并进结算并用最终集合收敛", listOf(0, 1), backend.writes.last().map { it.partIndex })
        assertEquals("首笔用停录快照（与页面 collect 同键去重）+ 迟到收敛一笔", 2, backend.writeCalls.get())
    }

    @Test
    fun `同键重复登记拿回同一枚任务且只写一次`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val a = RecordSession.record(backend, 7L, s, result(), practiceArmed = false, settled = listOf(part(0)))!!
        val b = RecordSession.record(backend, 7L, s, result(), practiceArmed = true, settled = listOf(part(0)))!!
        assertSame("同会话同段集合必须复用同一枚任务", a, b)
        withTimeout(5_000) { a.await() }
        assertEquals(1, backend.writeCalls.get())
    }

    @Test
    fun `重复交付同一段不重复结算`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val job = RecordSession.record(backend, 7L, s, result(), false, listOf(part(0)))!!
        withTimeout(5_000) { job.await() }
        awaitWrites(backend, 1)

        val dup = RecorderDelivery.LatePart(7L, s, 0, null, PublishClass.PUBLISHED, part(0))
        assertNull(RecordSession.onDelivery(dup))
        assertNull(RecordSession.onDelivery(dup))
        // 没有新任务被创建；给调度一拍，确认没有第二笔写库
        Thread.sleep(100)
        assertEquals("段集合没变 ⇒ 不许再结算", 1, backend.writeCalls.get())
    }

    // ------------------------------------------------------------------ 红线

    @Test
    fun `迟到段不许把失败洗白`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val job = RecordSession.record(
            backend = backend,
            seriesId = 7L,
            startedAtMs = s,
            result = result(error = "${RecordError.ROTATE_STALL}:1200"),
            practiceArmed = true,
            settled = listOf(part(0))
        )!!
        withTimeout(5_000) { job.await() }
        assertEquals("失败录制不许交付练习", 0, backend.deliveries.size)
        assertEquals("非干净成功必须清桥态", 1, backend.clearCalls.get())

        RecordSession.onDelivery(
            RecorderDelivery.LatePart(7L, s, 1, null, PublishClass.PUBLISHED, part(1))
        )
        awaitWrites(backend, 2)
        assertEquals("补一段迟到文件不改变失败结论", 0, backend.deliveries.size)
        awaitClears(backend, 2)
        assertEquals("再结算仍必须清桥态（不许在补结算时交付）", 2, backend.clearCalls.get())
    }

    @Test
    fun `泵线程终态压过早期成功快照_练习被清态而不是交付`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val early = RecordSession.record(
            backend = backend,
            seriesId = 7L,
            startedAtMs = s,
            result = result(path = "/tmp/part0.mp4"),
            practiceArmed = true,
            settled = listOf(part(0))
        )!!
        withTimeout(5_000) { early.await() }
        assertEquals("早期快照是干净成功 ⇒ 正常交付", listOf(0), backend.deliveries.map { it.partIndex })

        // R07：泵线程延迟收尾的终态是失败（join 超时路径的真实结论）
        RecordSession.onDelivery(
            RecorderDelivery.FinalResult(
                seriesId = 7L,
                startedAtMs = s,
                result = result(error = "${RecordError.ROTATE_STALL}:8000", path = "/tmp/part1.mp4"),
                settled = listOf(part(0), part(1))
            )
        )
        awaitWrites(backend, 2)
        assertEquals("终态的段集合必须收敛", listOf(0, 1), backend.writes.last().map { it.partIndex })
        assertEquals("终态失败不许再交付练习", 1, backend.deliveries.size)
        awaitClears(backend, 1)
        assertEquals("补结算必须清桥态（终态失败压过快照成功）", 1, backend.clearCalls.get())
    }

    @Test
    fun `同段集合的迟到终态由失败改成成功必须补交付练习`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        // join 超时那刻只拿到失败快照（R07），段集合 [0] 本身是已发布的
        val job = RecordSession.record(
            backend = backend,
            seriesId = 7L,
            startedAtMs = s,
            result = result(error = "${RecordError.ROTATE_STALL}:1200"),
            practiceArmed = true,
            settled = listOf(part(0))
        )!!
        withTimeout(5_000) { job.await() }
        assertEquals("失败快照不许交付练习", 0, backend.deliveries.size)
        assertEquals(1, backend.clearCalls.get())

        // 泵线程后续收尾成功：段集合没变、只把结论改正为成功 ⇒ 必须补交付（不许被段集合去重吞掉）
        RecordSession.onDelivery(
            RecorderDelivery.FinalResult(
                seriesId = 7L,
                startedAtMs = s,
                result = result(path = "/tmp/part0.mp4"),
                settled = listOf(part(0))
            )
        )
        awaitDeliveries(backend, 1)
        assertEquals("同段集合的结论改正必须补交付首段", listOf(0), backend.deliveries.map { it.partIndex })
        awaitWrites(backend, 2)
    }

    @Test
    fun `同段集合的迟到终态由成功改成失败必须清桥态`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val job = RecordSession.record(
            backend = backend,
            seriesId = 7L,
            startedAtMs = s,
            result = result(),
            practiceArmed = true,
            settled = listOf(part(0))
        )!!
        withTimeout(5_000) { job.await() }
        awaitDeliveries(backend, 1)

        // 终态失败、段集合相同：不许因为"段集合没变"就保留已交付的练习
        RecordSession.onDelivery(
            RecorderDelivery.FinalResult(
                seriesId = 7L,
                startedAtMs = s,
                result = result(error = "${RecordError.ROTATE_STALL}:8000"),
                settled = listOf(part(0))
            )
        )
        awaitClears(backend, 1)
        assertEquals("已交付的练习必须被终态失败清掉（不许把失败片当练习成片）", 1, backend.deliveries.size)
        awaitWrites(backend, 2)
    }

    @Test
    fun `未发布段进结果视图但不进结算`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val job = RecordSession.record(backend, 7L, s, result(), false, listOf(part(0)))!!
        withTimeout(5_000) { job.await() }
        awaitWrites(backend, 1)

        val unpublished = VideoSegment(
            uri = null,
            path = "/tmp/part1.mp4",
            partIndex = 1,
            bytes = 1_000L,
            startMs = 1_000L,
            durationMs = 1_000L,
            committed = false
        )
        val delta = RecordSession.onDelivery(
            RecorderDelivery.LatePart(7L, s, 1, unpublished, PublishClass.UNPUBLISHED)
        )
        assertTrue("文件在盘上 ⇒ 结果视图必须变大（UI 报可能看不到）", delta != null)
        Thread.sleep(100)
        assertEquals("未发布段不许进系列结算", 1, backend.writeCalls.get())
    }

    @Test
    fun `临时快照不结算_泵线程终态与迟到发布到齐后才权威交付`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        // 停录 join 超时：泵线程还在跑，真实错误为 null，只有 1 段未落地发布
        val provisional = result(path = null).copy(terminal = false, pendingPublications = 1)
        assertNull(
            "临时快照（terminal=false）不是结论，绝不许结算/清桥/交付",
            RecordSession.record(backend, 7L, s, provisional, practiceArmed = true, settled = emptyList())
        )
        Thread.sleep(100)
        assertEquals("临时快照不写系列", 0, backend.writeCalls.get())
        assertEquals("临时快照不交付练习", 0, backend.deliveries.size)
        assertEquals("临时快照不清练习桥", 0, backend.clearCalls.get())

        // 迟到发布先落地（段已公开、pending 归零），但泵线程还没交终态 ⇒ 仍不许结算
        val mid = RecordSession.onDelivery(
            RecorderDelivery.LatePart(
                seriesId = 7L,
                startedAtMs = s,
                partIndex = 0,
                segment = sealedSeg(0),
                classification = PublishClass.PUBLISHED,
                settled = part(0),
                pendingPublications = 0
            )
        )
        assertNotNull("段视图必须随迟到发布更新", mid)
        val midDelta = mid!!
        assertFalse("引擎未终态 ⇒ 不许当成权威结论", midDelta.terminal)
        assertNull("引擎未终态 ⇒ 不许交付结算", midDelta.settlement)
        Thread.sleep(100)
        assertEquals("发布落地但引擎未终态不许结算", 0, backend.writeCalls.get())

        // 泵线程延迟收尾的真实终态（同一身份、带同样的段）
        val terminal = RecordSession.onDelivery(
            RecorderDelivery.FinalResult(
                seriesId = 7L,
                startedAtMs = s,
                result = result(path = "/tmp/part0.mp4"),
                settled = listOf(part(0))
            )
        )
        assertNotNull(terminal)
        val finalDelta = terminal!!
        assertTrue("引擎终态 + 发布静默 = 权威结论", finalDelta.terminal)
        assertTrue("有保留段、无真实错误 ⇒ 权威成功", finalDelta.result.ok)
        assertNotNull("权威结论必须给出结算任务", finalDelta.settlement)
        awaitDeliveries(backend, 1)
        assertEquals("迟到发布与终态到齐后恰好结算一次", 1, backend.writeCalls.get())
        assertEquals("成功且武装 ⇒ 交付首段", listOf(0), backend.deliveries.map { it.partIndex })
        assertEquals(0, backend.clearCalls.get())
    }

    @Test
    fun `终态但发布pending不算失败_迟到发布落地后重算权威成功`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        // 正常停录：引擎已终态，但封口段还在发布路（pending=1），零产物视图的 error 必须为 null
        val pending = result(path = null).copy(terminal = true, pendingPublications = 1)
        assertNull(
            "发布路未静默不许结算",
            RecordSession.record(backend, 7L, s, pending, practiceArmed = true, settled = emptyList())
        )
        assertEquals("pending 不是失败", null, pending.error)
        Thread.sleep(100)
        assertEquals(0, backend.writeCalls.get())
        assertEquals(0, backend.clearCalls.get())

        val delta = RecordSession.onDelivery(
            RecorderDelivery.LatePart(
                seriesId = 7L,
                startedAtMs = s,
                partIndex = 0,
                segment = sealedSeg(0),
                classification = PublishClass.PUBLISHED,
                settled = part(0),
                pendingPublications = 0
            )
        )
        assertNotNull(delta)
        val grown = delta!!
        assertTrue("发布落地后必须重算为权威", grown.terminal)
        assertTrue("迟到成功必须能定稿为成功（不许停在 pending）", grown.result.ok)
        assertNull(grown.result.error)
        assertNotNull("权威成功必须补结算", grown.settlement)
        awaitDeliveries(backend, 1)
        awaitWrites(backend, 1)
        assertEquals("pending 从来不是失败 ⇒ 不许清练习桥", 0, backend.clearCalls.get())
    }

    @Test
    fun `零产物发布失败落地后给可查失败码且不伪装成功`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val pending = result(path = null).copy(terminal = true, pendingPublications = 1)
        assertNull(
            RecordSession.record(backend, 7L, s, pending, practiceArmed = true, settled = emptyList())
        )
        val delta = RecordSession.onDelivery(
            RecorderDelivery.LatePart(
                seriesId = 7L,
                startedAtMs = s,
                partIndex = 0,
                segment = null,
                classification = PublishClass.FAILED,
                settled = null,
                pendingPublications = 0
            )
        )
        assertNotNull(delta)
        val failed = delta!!
        assertTrue(failed.terminal)
        assertFalse("发布失败不许伪装成功", failed.result.ok)
        assertEquals(
            "零产物 + 发布真实失败 ⇒ 可查的发布失败码（不是 TOO_SHORT）",
            "${RecordError.ENGINE_ERROR}:PUBLISH",
            failed.result.error
        )
        assertNotNull(failed.settlement)
        awaitClears(backend, 1)
        assertEquals("失败不许交付练习", 0, backend.deliveries.size)
    }

    @Test
    fun `错误尝试或错误系列的迟到发布不进入当前会话`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val job = RecordSession.record(
            backend, 7L, s, result(), practiceArmed = true, settled = listOf(part(0)), attempt = 1
        )!!
        withTimeout(5_000) { job.await() }
        awaitWrites(backend, 1)
        awaitDeliveries(backend, 1)

        assertNull(
            "被判废尝试（attempt 0）的迟到发布只许进缓冲，不许作用到 attempt 1",
            RecordSession.onDelivery(
                RecorderDelivery.LatePart(7L, s, 9, sealedSeg(9), PublishClass.PUBLISHED, part(9), attempt = 0)
            )
        )
        assertNull(
            "别的系列同起始时间的迟到事件同样必须隔离",
            RecordSession.onDelivery(
                RecorderDelivery.LatePart(8L, s, 9, sealedSeg(9), PublishClass.PUBLISHED, part(9), attempt = 1)
            )
        )
        Thread.sleep(100)
        assertEquals("错误身份的迟到事件不许补结算", 1, backend.writeCalls.get())
        assertEquals(1, backend.deliveries.size)
        assertEquals(0, backend.clearCalls.get())
    }

    @Test
    fun `练习武装是停录快照_后续登记不改变`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        val a = RecordSession.record(backend, 7L, s, result(), practiceArmed = false, settled = listOf(part(0)))!!
        withTimeout(5_000) { a.await() }
        RecordSession.record(backend, 7L, s, result(), practiceArmed = true, settled = listOf(part(0)))
        Thread.sleep(100)
        assertEquals("首次登记（停录那一刻）未武装 ⇒ 后来变真也不交付", 0, backend.deliveries.size)
        assertEquals(0, backend.clearCalls.get())
    }

    @Test
    fun `同系列不同尝试的迟到终态不许串场`() = runBlocking {
        val backend = FakeBackend()
        val s = session()
        // 自检判废那次尝试（attempt 0）泵线程延迟收尾：它在 stopInternal 登记之前就交付终态，
        // 按生产时序只会进缓冲；紧接着重录（attempt 1，同一 seriesId/startedAtMs）成功。
        assertNull(
            RecordSession.onDelivery(
                RecorderDelivery.FinalResult(
                    seriesId = 7L,
                    startedAtMs = s,
                    result = result(error = RecordError.SELF_CHECK_FAILED, path = null),
                    settled = listOf(part(9)),
                    attempt = 0
                )
            )
        )
        val retry = RecordSession.record(
            backend = backend,
            seriesId = 7L,
            startedAtMs = s,
            result = result(),
            practiceArmed = true,
            settled = listOf(part(0)),
            attempt = 1
        )!!
        withTimeout(5_000) { retry.await() }
        awaitWrites(backend, 1)
        assertEquals(
            "被判废那次尝试的段集合不许并进重录成功那次",
            listOf(0),
            backend.writes.last().map { it.partIndex }
        )
        assertEquals("重录成功必须照常交付练习（不被 attempt 0 的失败洗掉）", listOf(0), backend.deliveries.map { it.partIndex })
        // attempt 0 的迟到终态在重录登记之后再来一次：仍只进缓冲，不许给 attempt 1 补结算
        assertNull(
            RecordSession.onDelivery(
                RecorderDelivery.FinalResult(
                    seriesId = 7L,
                    startedAtMs = s,
                    result = result(error = "${RecordError.ROTATE_STALL}:900", path = null),
                    settled = listOf(part(9)),
                    attempt = 0
                )
            )
        )
        Thread.sleep(100)
        assertEquals("尝试序号必须把两场结算隔开", 1, backend.writeCalls.get())
    }

    @Test
    fun `没有会话键不登记`() {
        val backend = FakeBackend()
        assertNull(RecordSession.record(backend, 7L, 0L, result(), false, listOf(part(0))))
        assertNull(
            RecordSession.onDelivery(
                RecorderDelivery.LatePart(7L, 0L, 1, null, PublishClass.PUBLISHED, part(1))
            )
        )
        assertFalse("没有会话键 ⇒ 一笔都不许写", backend.writeCalls.get() > 0)
    }

    @Test
    fun `会话槽按场次上限淘汰最老一场_不许无限持有结算后端`() {
        val backend = FakeBackend()
        val old = session()
        RecordSession.record(backend, 7L, old, result(), false, listOf(part(0)))!!
        // 后面再来 8 场（达到上限）：最老一场连槽带缓冲被淘汰，槽里的 WotaDb 引用随之释放
        repeat(8) {
            RecordSession.record(backend, 7L, session(), result(), false, listOf(part(0)))
        }
        assertNull(
            "已淘汰的会话迟到事件只能进缓冲，不许凭空再结算",
            RecordSession.onDelivery(
                RecorderDelivery.LatePart(7L, old, 9, null, PublishClass.PUBLISHED, part(9))
            )
        )
    }

    @Test
    fun `R06 迟到发布先到而终态快照晚到不得把段写回待发布`() {
        val backend = FakeBackend()
        val s = session()
        val interim = result().copy(
            terminal = false,
            pendingPublications = 1,
            pendingPartIds = setOf(1)
        )
        RecordSession.record(backend, 7L, s, interim, practiceArmed = false, settled = listOf(part(0)))
        assertNotNull(
            "迟到段必须产生增量",
            RecordSession.onDelivery(
                RecorderDelivery.LatePart(7L, s, 1, sealedSeg(1), PublishClass.PUBLISHED, part(1), pendingPublications = 0)
            )
        )
        // 停录那一刻生成的终态快照：发布路当时还有 1 段未落地。它比迟到结论晚到。
        val stale = result().copy(
            terminal = true,
            pendingPublications = 1,
            pendingPartIds = setOf(1),
            parts = listOf(sealedSeg(0))
        )
        val final = RecordSession.onDelivery(RecorderDelivery.FinalResult(7L, s, stale, listOf(part(0))))
        assertNotNull(final)
        assertTrue("已收到结论的段不得被终态快照复活成 pending", final!!.result.authoritative)
        assertEquals(0, final.result.pendingPublications)
        awaitWrites(backend, 1)
        assertEquals(listOf(0, 1), backend.writes.last().map { it.partIndex })
    }

    @Test
    fun `R06 终态先到而迟到发布后到也必须收敛到权威结论`() {
        val backend = FakeBackend()
        val s = session()
        val interim = result().copy(
            terminal = false,
            pendingPublications = 1,
            pendingPartIds = setOf(1)
        )
        RecordSession.record(backend, 7L, s, interim, practiceArmed = false, settled = listOf(part(0)))
        val stale = result().copy(
            terminal = true,
            pendingPublications = 1,
            pendingPartIds = setOf(1),
            parts = listOf(sealedSeg(0))
        )
        val final = RecordSession.onDelivery(RecorderDelivery.FinalResult(7L, s, stale, listOf(part(0))))
        assertNotNull(final)
        assertFalse("发布路仍有一段未落地前不是权威结论", final!!.result.authoritative)
        val late = RecordSession.onDelivery(
            RecorderDelivery.LatePart(7L, s, 1, sealedSeg(1), PublishClass.PUBLISHED, part(1), pendingPublications = 0)
        )
        assertNotNull(late)
        assertTrue("最后一段落地后必须立刻权威", late!!.result.authoritative)
        awaitWrites(backend, 1)
        assertEquals(listOf(0, 1), backend.writes.last().map { it.partIndex })
    }

    @Test
    fun `R07 迟到终态只修正时长也必须再结算一次`() {
        val backend = FakeBackend()
        val s = session()
        RecordSession.record(backend, 7L, s, result(), practiceArmed = false, settled = listOf(part(0)))
        awaitWrites(backend, 1)
        assertEquals(1_000L, backend.writes.last().single().durationMs)
        val corrected = result().copy(terminal = true, pendingPartIds = emptySet())
        val delta = RecordSession.onDelivery(
            RecorderDelivery.FinalResult(7L, s, corrected, listOf(part(0).copy(durationMs = 2_500L)))
        )
        assertNotNull("时长修正属于不同结算键，必须再落库一次", delta)
        awaitWrites(backend, 2)
        assertEquals(2_500L, backend.writes.last().single().durationMs)
    }

    // ------------------------------------------------------------------ 纯函数

    @Test
    fun `结算视图合并按段序去重且已有优先`() {
        val existing = listOf(part(0), part(2))
        val extra = listOf(
            part(2).copy(uriStr = "content://media/external/video/media/999"),
            part(1)
        )
        val merged = mergeSettled(existing, extra)
        assertEquals(listOf(0, 1, 2), merged.map { it.partIndex })
        assertEquals("同段不许被迟到事件覆盖", existing[1].uriStr, merged[2].uriStr)
        assertSame("没有新增段时返回原对象", existing, mergeSettled(existing, emptyList()))
    }
}