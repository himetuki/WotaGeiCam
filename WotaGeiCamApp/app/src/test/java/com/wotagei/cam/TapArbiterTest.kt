package com.wotagei.cam

import com.wotagei.cam.player.TapArbiter
import com.wotagei.cam.player.TapArbiter.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页双击仲裁器的契约钉子（单击切显隐 / 双击启停共用一个 windowMs 窗口）：
 * - 首击挂起（DeferredToggle），窗内第二击改判启停（PlayPause）并清挂起；
 * - 挂起定时器**到点或过点**才真切显隐；提前问一律 false 且不清挂起；
 * - 判完启停后状态归零，下一击开启新窗口（循环复用）。
 * 全部用显式时间戳驱动，不睡真实时钟。
 */
class TapArbiterTest {

    private val window = 280L

    @Test
    fun `首击挂起_窗内第二击改判启停`() {
        val arb = TapArbiter(window)
        assertEquals(Decision.DeferredToggle, arb.onPointerUp(1000L))
        assertEquals(Decision.PlayPause, arb.onPointerUp(1200L))
    }

    @Test
    fun `恰好到点的第二击也算双击_边界含等号`() {
        val arb = TapArbiter(window)
        arb.onPointerUp(1000L)
        assertEquals(Decision.PlayPause, arb.onPointerUp(1000L + window))
    }

    @Test
    fun `超过窗口的第二击是新单击_重新挂起`() {
        val arb = TapArbiter(window)
        arb.onPointerUp(1000L)
        val second = arb.onPointerUp(1000L + window + 1L)
        assertEquals(Decision.DeferredToggle, second)
        // 新窗口生效：再隔一窗内的第二击照样判启停
        assertEquals(Decision.PlayPause, arb.onPointerUp(1000L + window + 1L + window))
    }

    @Test
    fun `定时器到点才真切_提前问返回false且不清挂起`() {
        val arb = TapArbiter(window)
        arb.onPointerUp(1000L)
        assertTrue(!arb.onToggleDue(1000L + window - 1L))
        // 提前问不清挂起：到点再问仍然为 true
        assertTrue(arb.onToggleDue(1000L + window))
        // 清零后重复问到点：false
        assertTrue(!arb.onToggleDue(1000L + window + 1L))
    }

    @Test
    fun `cancelPending后定时器作废_下一击重新挂起`() {
        val arb = TapArbiter(window)
        arb.onPointerUp(1000L)
        arb.cancelPending()
        assertTrue(!arb.onToggleDue(1000L + window))
        assertEquals(Decision.DeferredToggle, arb.onPointerUp(5000L))
    }

    @Test
    fun `启停判完即归零_第三次击开启新循环`() {
        val arb = TapArbiter(window)
        arb.onPointerUp(1000L)
        assertEquals(Decision.PlayPause, arb.onPointerUp(1100L))
        // 双击判完的瞬间再点：不是"三击"，是新一轮的首击
        assertEquals(Decision.DeferredToggle, arb.onPointerUp(1150L))
        assertEquals(Decision.PlayPause, arb.onPointerUp(1250L))
    }

    @Test
    fun `长时间空闲后首击照常挂起`() {
        val arb = TapArbiter(window)
        assertTrue(arb.onToggleDue(999_999L) == false)
        assertEquals(Decision.DeferredToggle, arb.onPointerUp(1_000_000L))
    }
}
