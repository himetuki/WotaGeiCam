package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 停录收尾移交闸 [TrailHandoff] 的真值表（R07 收口）。
 *
 * 缺陷形态（旧布尔 `teardownDeferred`，2026-11-20 前的实现）：
 * ```
 * // 控制线程                          // 泵线程（pumpTrail）
 * joinLoop() 超时，t.isAlive == true
 *   ...（GC/调度窗口）...               if (!teardownDeferred) return   // 读到 false，跳过收尾
 * teardownDeferred = true              // 线程退出
 * return finishWithoutTeardown()       // 早期快照
 * ```
 * 双方都不做延迟收尾：终态没人 finish、输入面没人释放、迟到修正永远不交付。
 *
 * 现在由两枚互斥 CAS（OPEN→SKIPPED / OPEN→DEFERRED）原子二选一，保证任何交错下
 * **恰好一方**拿到延迟收尾权，且不需要第二次有界 join、不依赖"退出应该很快"的时序假设。
 */
class TrailHandoffTest {

    @Test
    fun `正常停录_泵越过检查点后无人接管`() {
        val h = TrailHandoff()
        assertEquals("初始状态", "open", h.stateName())
        assertFalse("无人请求移交 ⇒ 泵不做延迟收尾（控制线程 join 成功后自己 finish）", h.pumpCheckpoint())
        assertEquals("泵已宣告不接管", "skipped", h.stateName())
        assertFalse("同一泵线程再问一次也不许接管", h.pumpCheckpoint())
    }

    @Test
    fun `请求先到_泵线程接管延迟收尾`() {
        val h = TrailHandoff()
        assertFalse("控制线程请求移交（不是自己补做）", h.controlDefer())
        assertEquals("请求已生效", "deferred", h.stateName())
        assertTrue("泵越过检查点必须接管", h.pumpCheckpoint())
        assertEquals("泵在收尾", "pump", h.stateName())
        assertFalse("控制线程已交权，不许再补做", h.controlDefer())
    }

    @Test
    fun `泵先越过检查点_控制线程必须补做收尾`() {
        // 旧布尔的缺陷窗口：泵先读到 false 并退出，控制随后才置位 ⇒ 双方都不做
        val h = TrailHandoff()
        assertFalse("泵先越过：无人请求，宣告不接管", h.pumpCheckpoint())
        assertTrue("控制线程超时后必须发现泵已宣告不接管，并自己补做", h.controlDefer())
        assertEquals("控制线程在收尾", "control", h.stateName())
        assertFalse("补做之后再问也不许重复接管", h.controlDefer())
        assertFalse("泵已宣告过，不会再接管", h.pumpCheckpoint())
    }

    @Test
    fun `旧布尔闸在同一时序下双方都不做_交接闸必须有一方`() {
        // 旧实现的最小模型：唯一的布尔由控制线程置位
        var legacyDeferred = false
        val pumpWouldTail = legacyDeferred // 泵在 pumpTrail 里读到 false
        legacyDeferred = true              // 控制线程随后置位（泵已经退出）
        assertTrue("控制线程已置位，但泵不会再回来消费", legacyDeferred)
        assertFalse("缺陷形态复现：旧布尔闸在「判活后、置位前」的窗口里双方都不做", pumpWouldTail)

        // 同一时序的交接闸：泵先越过检查点（false），控制线程必须补做（true）
        val h = TrailHandoff()
        val pumpTails = h.pumpCheckpoint()
        val controlTails = h.controlDefer()
        assertFalse("泵不接管", pumpTails)
        assertTrue("控制线程补做——不存在双方都不做的出口", controlTails)
    }

    @Test
    fun `复位后协议重新开始`() {
        val h = TrailHandoff()
        h.pumpCheckpoint()
        h.controlDefer()
        h.reset()
        assertEquals("复位回初始态", "open", h.stateName())
        assertFalse("复位后无人请求 ⇒ 泵不接管", h.pumpCheckpoint())
        assertTrue("复位后同一时序下控制线程仍能补做", h.controlDefer())
    }

    @Test
    fun `真实并发下延迟收尾权恰好一份`() {
        repeat(300) { round ->
            val h = TrailHandoff()
            val gate = CountDownLatch(1)
            val tails = AtomicInteger()
            val pump = Thread {
                gate.await()
                if (h.pumpCheckpoint()) tails.incrementAndGet()
            }
            val control = Thread {
                gate.await()
                if (h.controlDefer()) tails.incrementAndGet()
            }
            listOf(pump, control).forEach {
                it.isDaemon = true
                it.name = "trail-${it.name}-$round"
                it.start()
            }
            gate.countDown()
            pump.join(2_000L)
            control.join(2_000L)
            assertEquals(
                "第 $round 轮：join 超时 + 泵线程退出必须恰好一方收尾（0 = 双方都不做 ⇒ 终态丢失；" +
                    "2 = 双方都做 ⇒ 双重 finish/双放输入面）",
                1,
                tails.get()
            )
        }
    }
}
