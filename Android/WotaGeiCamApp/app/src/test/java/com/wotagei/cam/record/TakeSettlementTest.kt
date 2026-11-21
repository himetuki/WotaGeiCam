package com.wotagei.cam.record

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话级结算所有者（Y21 / R07 同族）的行为真值表。
 *
 * 这里不碰 Room / Uri / ComparePractice：后端是注入的假实现，所有断言都打在**可观察的副作用**
 * （写了几次、写的段集合、交付了哪一段、页面取消后是否仍在跑）上。三条主线：
 * 1. 只有 `committed` 段参与归组与练习交付（未发布段不许进系列、不许当练习成片）；
 * 2. 同一场结算恰好启动一次任务（重复调用拿回同一枚 deferred）；
 * 3. 页面作用域被取消不打断所有者；后端异常被如实上报（`seriesSettled=false`）而不外抛。
 */
class TakeSettlementTest {

    private fun part(i: Int, committed: Boolean = true): SettledPart = SettledPart(
        partIndex = i,
        uriStr = "content://media/external/video/media/${100 + i}",
        durationMs = 1_000L,
        committed = committed
    )

    private class FakeBackend(
        private val gate: CompletableDeferred<Unit>? = null,
        private val failWrite: Boolean = false,
        private val deliverResult: Boolean = true
    ) : TakeSettlementBackend {

        val writes = CopyOnWriteArrayList<List<SettledPart>>()
        val deliveries = CopyOnWriteArrayList<SettledPart>()
        val writeCalls = AtomicInteger()
        var clearCalls = 0

        override suspend fun writeSeries(
            seriesId: Long,
            startedAtMs: Long,
            parts: List<SettledPart>
        ): Int {
            writeCalls.incrementAndGet()
            gate?.await()
            if (failWrite) throw IllegalStateException("inject write failure")
            writes += parts
            return parts.size
        }

        override fun deliverPractice(part: SettledPart): Boolean {
            deliveries += part
            return deliverResult
        }

        override fun clearPractice() {
            clearCalls++
        }
    }

    private fun owner(scope: CoroutineScope) = TakeSettlementOwner(scope)

    // ------------------------------------------------------------------ 纯判定

    @Test
    fun `只有已发布段进入结算视图_并按段序去重`() {
        val parts = listOf(
            part(2),
            part(0),
            part(1, committed = false),
            SettledPart(3, null, 1_000L, committed = true),
            part(2).copy(uriStr = "content://media/external/video/media/999") // 同段序只留首条
        )
        val kept = committedSettledParts(parts)
        assertEquals("未发布/无 uri/重复段都必须剔除", listOf(0, 2), kept.map { it.partIndex })
        assertEquals(
            "同段序重复条目保留源列表首条（迟到的重复 uri 不许覆盖）",
            "content://media/external/video/media/102",
            kept.last().uriStr
        )
    }

    @Test
    fun `未武装时练习桥完全不动`() {
        assertEquals(
            PracticeDecision.Leave,
            practiceDecision(armed = false, resultOk = true, committed = listOf(part(0)))
        )
    }

    @Test
    fun `武装但非干净成功必须清桥态`() {
        assertEquals(
            PracticeDecision.Clear,
            practiceDecision(armed = true, resultOk = false, committed = listOf(part(0)))
        )
    }

    @Test
    fun `武装且干净成功但没有已发布段也必须清桥态`() {
        assertEquals(
            PracticeDecision.Clear,
            practiceDecision(armed = true, resultOk = true, committed = emptyList())
        )
    }

    @Test
    fun `武装且干净成功交付段序最前的已发布段`() {
        val d = practiceDecision(armed = true, resultOk = true, committed = listOf(part(1), part(0)))
        assertEquals("交付首段（源列表顺序不参与判定，取 partIndex 最小）", 0, (d as PracticeDecision.Deliver).part.partIndex)
    }

    // ------------------------------------------------------------------ 所有者生命周期

    @Test
    fun `同一场结算只启动一次任务_重复调用拿回同一枚deferred`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend(gate = gate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val o = owner(scope)
        val args = listOf(part(0), part(1))

        val a = o.settle(7L, 1_000L, args, resultOk = true, practiceArmed = false, backend = backend)
        val b = o.settle(7L, 1_000L, args, resultOk = true, practiceArmed = false, backend = backend)
        assertSame("同一结算键必须复用同一枚任务（恰好一次）", a, b)

        gate.complete(Unit)
        withTimeout(5_000) { a.await() }
        assertEquals("写库只发生一次", 1, backend.writeCalls.get())
    }

    @Test
    fun `页面作用域取消不打断所有者_退页后仍完成落库与交付`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeBackend(gate = gate)
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val o = owner(ownerScope)
        val args = listOf(part(0), part(1))

        // 页面侧：拿到 deferred 后 await；模拟退页取消
        val pageJob = pageScope.async {
            o.settle(7L, 1_000L, args, resultOk = true, practiceArmed = true, backend = backend).await()
        }
        delay(50) // 让 owner 任务进入 writeSeries 的 gate
        pageJob.cancelAndJoin()

        gate.complete(Unit)
        // 页面已经走了：所有者任务仍必须完成（用同一键取回那枚 deferred）
        val outcome = withTimeout(5_000) {
            o.settle(7L, 1_000L, args, resultOk = true, practiceArmed = true, backend = backend).await()
        }
        assertTrue("退页取消不得取消落库", outcome.seriesSettled)
        assertEquals(2, outcome.committedParts)
        assertEquals(2, backend.writes.single().size)
        assertTrue("练习交付同样必须在所有者作用域里完成", outcome.practiceDelivered)
        assertEquals("交付首段", 0, backend.deliveries.single().partIndex)
    }

    @Test
    fun `未发布段不进写库也不进练习交付`() = runBlocking {
        val backend = FakeBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outcome = owner(scope).settle(
            7L, 1_000L,
            listOf(part(0), part(1, committed = false)),
            resultOk = true, practiceArmed = true, backend = backend
        ).await()

        assertEquals("只有已发布段参与结算", 1, outcome.committedParts)
        assertEquals(listOf(0), backend.writes.single().map { it.partIndex })
        assertEquals("练习交付只认已发布段", listOf(0), backend.deliveries.map { it.partIndex })
    }

    @Test
    fun `迟到发布的对账以新段集合再结算一次`() = runBlocking {
        val backend = FakeBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val o = owner(scope)

        o.settle(7L, 1_000L, listOf(part(0), part(1)), resultOk = true, practiceArmed = false, backend = backend)
            .await()
        // R06：第 3 段迟到 commit，已发布集合变化 ⇒ 新结算键
        o.settle(7L, 1_000L, listOf(part(0), part(1), part(2)), resultOk = true, practiceArmed = false, backend = backend)
            .await()

        assertEquals("两次结算都必须落库", 2, backend.writeCalls.get())
        assertEquals("第二次必须是最终集合（REPLACE 收敛）", listOf(0, 1, 2), backend.writes.last().map { it.partIndex })
    }

    @Test
    fun `同段集合的相反结论是两笔结算_失败改正必须补交付`() = runBlocking {
        val backend = FakeBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val o = owner(scope)
        val parts = listOf(part(0))

        // R07：join 超时快照判失败（段已发布、段集合不变）
        o.settle(7L, 1_000L, parts, resultOk = false, practiceArmed = true, backend = backend).await()
        assertEquals("失败快照只清桥态", 1, backend.clearCalls)
        assertTrue(backend.deliveries.isEmpty())

        // 泵线程迟到终态把结论改正为成功：同一段集合也必须再结算一次并补交付
        o.settle(7L, 1_000L, parts, resultOk = true, practiceArmed = true, backend = backend).await()
        assertEquals("结论改正必须落第二笔库（REPLACE 收敛）", 2, backend.writeCalls.get())
        assertEquals("成功结论必须补交付首段", listOf(0), backend.deliveries.map { it.partIndex })
    }

    /** 第一笔写很慢、第二笔很快：不串行时第二笔会先落库，最后留下的反而是旧集合 */
    private class SlowFirstBackend : TakeSettlementBackend {
        val order = CopyOnWriteArrayList<List<Int>>()
        val calls = AtomicInteger()

        override suspend fun writeSeries(
            seriesId: Long,
            startedAtMs: Long,
            parts: List<SettledPart>
        ): Int {
            if (calls.incrementAndGet() == 1) Thread.sleep(300)
            order += parts.map { it.partIndex }
            return parts.size
        }

        override fun deliverPractice(part: SettledPart): Boolean = true

        override fun clearPractice() = Unit
    }

    @Test
    fun `同一会话的迟到结算必须按发起顺序落库_旧集合不许最后写回`() = runBlocking {
        val backend = SlowFirstBackend()
        val o = owner(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        // 发起顺序 = 收敛方向：先 [0]，迟到段到齐后 [0,1]
        val early = o.settle(7L, 1_000L, listOf(part(0)), resultOk = true, practiceArmed = false, backend = backend)
        val grown = o.settle(7L, 1_000L, listOf(part(0), part(1)), resultOk = true, practiceArmed = false, backend = backend)
        early.await()
        grown.await()

        assertEquals(
            "后发起的更大集合必须最后落库（REPLACE 收敛，不许缩水）",
            listOf(listOf(0), listOf(0, 1)),
            backend.order
        )
    }

    @Test
    fun `写库异常被收住_如实上报且不拖累练习交付`() = runBlocking {
        val backend = FakeBackend(failWrite = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outcome = owner(scope).settle(
            7L, 1_000L, listOf(part(0), part(1)),
            resultOk = true, practiceArmed = true, backend = backend
        ).await()

        assertFalse("写库失败必须如实上报（不许假装登记成功）", outcome.seriesSettled)
        assertEquals("失败时行数为 0", 0, outcome.seriesRows)
        assertTrue("媒体已发布是独立事实，练习交付不受写库失败影响", outcome.practiceDelivered)
    }

    @Test
    fun `非干净成功清桥态而不是交付`() = runBlocking {
        val backend = FakeBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outcome = owner(scope).settle(
            7L, 1_000L, listOf(part(0), part(1)),
            resultOk = false, practiceArmed = true, backend = backend
        ).await()

        assertTrue("非干净成功必须清桥态", outcome.practiceCleared)
        assertTrue("不许交付半场成片", backend.deliveries.isEmpty())
        assertEquals(1, backend.clearCalls)
    }

    // ------------------------------------------------------------------ 容量上界（进程级所有者不许无限累积）

    @Test
    fun `结算去重表有容量上界_已完成的最老键被淘汰`() = runBlocking {
        val backend = FakeBackend()
        val o = TakeSettlementOwner(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxRetainedKeys = 2,
            maxRetainedSessions = 8
        )
        // 同一会话、五个不同段集合 = 五枚不同键（迟到对账的正常形态）
        val first = o.settle(7L, 1_000L, listOf(part(0)), true, false, backend)
        first.await()
        val second = o.settle(7L, 1_000L, listOf(part(0), part(1)), true, false, backend)
        second.await()
        val third = o.settle(7L, 1_000L, listOf(part(0), part(1), part(2)), true, false, backend)
        third.await()
        val fourth = o.settle(7L, 1_000L, listOf(part(0), part(1), part(2), part(3)), true, false, backend)
        fourth.await()

        assertNotSame(
            "超出上界后最老的已完成键必须被淘汰（重跑一次效果幂等，但不许无限持有 uri 清单）",
            first,
            o.settle(7L, 1_000L, listOf(part(0)), true, false, backend)
        )
        assertSame(
            "最近完成的键仍必须复用（恰好一次不被容量上界破坏）",
            fourth,
            o.settle(7L, 1_000L, listOf(part(0), part(1), part(2), part(3)), true, false, backend)
        )
    }

    @Test
    fun `容量淘汰只动已完成项_在途结算的重复调用仍拿到同一枚`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val slow = FakeBackend(gate = gate)
        val fast = FakeBackend()
        val o = TakeSettlementOwner(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxRetainedKeys = 1,
            maxRetainedSessions = 8
        )
        val inFlight = o.settle(7L, 1_000L, listOf(part(0)), true, false, slow)
        // 塞满并溢出：在途那枚不能被淘汰（淘汰它等于同一场结算并发跑两笔）
        repeat(4) { i ->
            o.settle(8L + i, 1_000L + i, listOf(part(0)), true, false, fast).await()
        }
        assertSame(
            "未完成的结算必须仍在去重表里（重复调用拿回同一枚 deferred）",
            inFlight,
            o.settle(7L, 1_000L, listOf(part(0)), true, false, slow)
        )
        gate.complete(Unit)
        withTimeout(5_000) { inFlight.await() }
    }

    @Test
    fun `会话链有容量上界_连续多场退页结算不累积`() = runBlocking {
        val backend = FakeBackend()
        val o = TakeSettlementOwner(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxRetainedKeys = 64,
            maxRetainedSessions = 2
        )
        // 20 场连续录制（每场两笔：早期快照 + 迟到终态）：只验证不崩且每场都落库
        repeat(20) { i ->
            o.settle(100L + i, 1_000L + i, listOf(part(0)), true, false, backend).await()
            o.settle(100L + i, 1_000L + i, listOf(part(0), part(1)), true, false, backend).await()
        }
        assertEquals("每场两笔都要落库（会话链淘汰不动已完成的写）", 40, backend.writeCalls.get())
        assertEquals("最后一场的段集合完整", listOf(0, 1), backend.writes.last().map { it.partIndex })
    }
}
