package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Y18 的两本账：**换段腿截止时刻**（[RotateLegLedger]）与**交接代号/锁外回收**
 * （[OneShotHandoff]）。
 *
 * 缺陷形态（旧实现）：一枚 `AtomicLong rotateLegStartMs` 既当预算起钟又当丢失结算锁，
 * `getAndSet(0L)` 在「领结算」的同时把腿也清了 ⇒ stop 超时与在途换段交错时，预算读数
 * 退回腿外的 3s（控制线程收尾凭空多预算、在途泵判不出停摆）；交接闸没有代号，复位之后
 * 上一次尝试的死线程晚到的 insert 会被当成**本代**的上交，而回收又在锁内跑（把泵线程堵在一次
 * MediaProvider delete 上）。
 *
 * 这里钉住的真值表：
 * 1. 预算只由**不可变截止时刻**派生：领取丢失结算**不动**预算；只有腿终点（泵线程 `endLeg`）清它。
 * 2. 领取权恰好一次（CAS）：先到者独得，后到者拿 0；`begin` 重开、`reset` 关死。
 * 3. 交接代号：旧代的 publish/claim/abandon 一律拒收（有值即回收），不许冒充本代。
 * 4. 回收在锁外：回收正卡在（模拟的）入库调用上时，下一代必须能立刻开闸——同一把尺放到
 *    「锁内回收」的突变体上必须被堵住（尺子有牙）。
 */
class RotateLegLedgerTest {

    // ------------------------------------------------------------------ 截止时刻/预算/领取权

    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun ledger(clock: FakeClock) = RotateLegLedger(
        legBudgetMs = 3_000L,
        idleBudgetMs = 1_500L,
        now = clock
    )

    @Test
    fun `腿内预算按不可变截止时刻递减_腿外给单次入库预算`() {
        val clock = FakeClock()
        val l = ledger(clock)
        assertEquals("没有腿时必须给腿外预算", 1_500L, l.budgetLeftMs())
        assertFalse("没有腿", l.active())

        l.begin()
        assertTrue("起钟后必须算在腿里", l.active())
        assertEquals("腿起那刻的剩余 = 腿预算", 3_000L, l.budgetLeftMs())
        clock.now += 1_200L
        assertEquals("腿内按截止时刻递减", 1_800L, l.budgetLeftMs())
        clock.now += 2_000L
        assertTrue("越过截止时刻必须是负数（调用方据此判停摆，不许夹成 0 以外的好看数）", l.budgetLeftMs() < 0L)

        l.endLeg()
        assertFalse("腿终点之后不再是腿", l.active())
        assertEquals("腿外回到单次入库预算", 1_500L, l.budgetLeftMs())
    }

    @Test
    fun `停止路径领取丢失结算不许把预算重新发出来`() {
        // Y18 的核心形态：stop join 超时、泵线程还卡在腿里，控制线程领走丢失结算。
        // 旧实现这一领同时清了起钟 ⇒ 预算又变成腿外的 3s（两本账串台）。
        val clock = FakeClock()
        val l = ledger(clock)
        val start = l.begin()
        clock.now += 4_000L

        assertEquals("控制线程必须能领到本腿结算", start, l.claim())
        assertEquals("领取权不许动预算：预算仍是腿内的负数", -1_000L, l.budgetLeftMs())
        assertTrue("截止时刻不许被领取清掉（腿还没走完）", l.active())
    }

    @Test
    fun `泵线程领结算并在腿终点清截止_之后预算才回腿外`() {
        val clock = FakeClock()
        val l = ledger(clock)
        val start = l.begin()
        clock.now += 4_000L

        assertEquals("泵线程是腿走完的一方，独得结算", start, l.claim())
        assertTrue("结算本身不清预算（清它的是 endLeg，两件事分开）", l.budgetLeftMs() < 0L)
        l.endLeg()
        assertEquals("腿终点之后预算回腿外", 1_500L, l.budgetLeftMs())
    }

    @Test
    fun `stop超时与在途换段交错_预算与结算不串`() {
        // 验收形态（Y18）：泵线程卡在换段腿里 → 控制线程 join 超时 → 两边围着同一条腿各做各的。
        val clock = FakeClock()
        val l = ledger(clock)
        l.begin()
        clock.now += 3_500L // 泵线程还在腿里，腿预算已尽

        // 控制线程：领走丢失结算（记一笔），按"只记账不拆资源"返回
        val stopStart = l.claim()
        assertTrue("停止路径必须领到本腿起点", stopStart > 0L)
        // 在途泵线程此刻再发有界调用：必须按腿内预算判停摆，不许拿到腿外 3s
        assertTrue("stop 领取后预算必须仍是腿内的负数（两本账不串）", l.budgetLeftMs() <= 0L)
        assertTrue("腿还没走完，截止时刻必须还在", l.active())

        // 泵线程终于回到腿终点：领不到结算（已被 stop 领走），但必须清截止
        assertEquals("泵线程后到必得 0（缺失结算不许双记）", 0L, l.claim())
        l.endLeg()
        assertEquals("腿终点之后预算才回腿外", 1_500L, l.budgetLeftMs())
    }

    @Test
    fun `领取权恰好一次_两侧先到者独得`() {
        val clock = FakeClock()
        // 停录先到 → 泵线程后到必得 0
        val a = ledger(clock)
        val sa = a.begin()
        assertEquals(sa, a.claim())
        assertEquals("后到者必得 0（不许双记一笔丢失）", 0L, a.claim())

        // 泵线程先到 → 停录后到必得 0
        val b = ledger(clock)
        val sb = b.begin()
        assertEquals(sb, b.claim())
        assertEquals("后到者必得 0", 0L, b.claim())
    }

    @Test
    fun `没有腿时领取必得0_begin重开领取权_reset关死`() {
        val clock = FakeClock()
        val l = ledger(clock)
        assertEquals("从没起过腿：没有腿账可领", 0L, l.claim())

        val first = l.begin()
        assertEquals(first, l.claim())
        clock.now += 500L
        val second = l.begin()
        assertEquals("新腿必须重开领取权（上一腿领过不影响这一腿）", second, l.claim())

        l.reset()
        assertEquals("复位之后没有腿可领", 0L, l.claim())
        assertFalse("复位之后没有腿", l.active())
        assertEquals("复位之后是腿外预算", 1_500L, l.budgetLeftMs())
    }

    @Test
    fun `领取权在真实并发下恰好一次`() {
        repeat(200) { round ->
            val clock = FakeClock()
            val l = ledger(clock)
            l.begin()
            val wins = AtomicInteger()
            val gate = CountDownLatch(1)
            val parties = listOf("pump", "stop").map { name ->
                Thread {
                    gate.await()
                    if (l.claim() != 0L) wins.incrementAndGet()
                }.also {
                    it.name = "$name-$round"
                    it.isDaemon = true
                    it.start()
                }
            }
            gate.countDown()
            parties.forEach { it.join(2_000L) }
            assertEquals("第 $round 轮：同一条腿的丢失结算只能被领一次", 1, wins.get())
        }
    }

    // ------------------------------------------------------------------ 交接代号

    @Test
    fun `交接闸_上一代晚到的插入必须被拒收并回收_不许冒充本代`() {
        val dropped = ArrayList<String>()
        val h = OneShotHandoff<String> { dropped.add(it) }
        val g1 = h.reset()
        val g2 = h.reset()

        assertFalse("上一代的迟到插入不许被本代收下", h.publish(g1, "late"))
        assertEquals("被拒的那枚必须就地回收（否则相册留 IS_PENDING 幽灵）", listOf("late"), dropped)
        assertNull("本代槽里不许出现被冒充的那枚", h.claim(g2))
    }

    @Test
    fun `交接闸_旧代判停摆不许撤掉本代交接权`() {
        val dropped = ArrayList<String>()
        val h = OneShotHandoff<String> { dropped.add(it) }
        val g1 = h.reset()
        assertTrue(h.publish(g1, "a"))
        val g2 = h.reset()
        assertEquals("换代时上一代留在槽里的必须回收", listOf("a"), dropped)

        assertNull("旧代判停摆拿不到任何东西", h.abandon(g1))
        assertTrue("旧代判停摆也不许把本代置成已撤权", h.publish(g2, "b"))
        assertEquals("本代结果照常可取", "b", h.claim(g2))
    }

    /**
     * 缺陷形态的对照闸（Y18）：判拒时**在锁内**回收。只用来证明下面那把尺子有牙——
     * 真身（[OneShotHandoff]）把回收放在锁外。
     */
    private class LockedReapGate(private val discard: () -> Unit) {
        private val lock = Any()
        fun rejectInLock() = synchronized(lock) { discard() }
        fun openNext() = synchronized(lock) { }
    }

    @Test
    fun `交接闸_回收必须在锁外_回收卡住时下一代必须立刻能开闸`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dropped = ArrayList<String>()
        val h = OneShotHandoff<String> { late ->
            // 模拟生产回收：一次有界入库调用（MediaProvider delete），最多 STORE_CALL_BUDGET_MS
            inside.countDown()
            release.await(2, TimeUnit.SECONDS)
            dropped.add(late)
        }
        val gen = h.reset()
        h.abandon(gen)
        val late = Thread { h.publish(gen, "late") }
        late.isDaemon = true
        late.start()
        assertTrue("迟到那枚必须真的进入回收", inside.await(2, TimeUnit.SECONDS))

        val done = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val opener = Thread {
            val g2 = h.reset()
            accepted.set(h.publish(g2, "fresh"))
            done.countDown()
        }
        opener.isDaemon = true
        opener.start()
        assertTrue(
            "锁外回收：回收还卡着时下一代必须能立刻开闸（false = 回收占着交接闸的锁，把泵线程堵在一次入库调用上）",
            done.await(1_000L, TimeUnit.MILLISECONDS)
        )
        assertTrue("新一代的上交必须被接受", accepted.get())
        release.countDown()
        late.join(2_000L)
        opener.join(2_000L)
        assertEquals("卡住的回收最终也要落账", listOf("late"), dropped)
    }

    @Test
    fun `尺子自己能红_锁内回收必然把开闸线程堵住`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = LockedReapGate {
            inside.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        val reaper = Thread { gate.rejectInLock() }
        reaper.isDaemon = true
        reaper.start()
        assertTrue("回收必须先卡住", inside.await(2, TimeUnit.SECONDS))

        val opened = CountDownLatch(1)
        val opener = Thread {
            gate.openNext()
            opened.countDown()
        }
        opener.isDaemon = true
        opener.start()
        assertFalse(
            "锁内回收必须把开闸线程堵住——堵不住说明这把尺子测不到缺陷形态",
            opened.await(300L, TimeUnit.MILLISECONDS)
        )
        release.countDown()
        assertTrue("回收结束后必须能开闸", opened.await(2, TimeUnit.SECONDS))
        reaper.join(2_000L)
        opener.join(2_000L)
    }
}