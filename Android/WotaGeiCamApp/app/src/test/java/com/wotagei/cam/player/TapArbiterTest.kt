package com.wotagei.cam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面单击 / 双击仲裁的判定表（JVM，不需设备）。
 * 覆盖验收点：单击=显隐、双击=启停、两者共存不互触。
 */
class TapArbiterTest {

    private val window = 280L

    @Test
    fun `单击要等满双击窗口才落地显隐`() {
        val a = TapArbiter(window)
        assertEquals(TapArbiter.Decision.DeferredToggle, a.onPointerUp(0))
        // 窗口未到就检查：不能切
        assertFalse(a.onToggleDue(100))
        // 等满窗口：切一次
        assertTrue(a.onToggleDue(window))
        // 已经落地过，重复到点检查不再切第二次
        assertFalse(a.onToggleDue(window + 50))
    }

    @Test
    fun `双击只判启停且取消挂起的显隐`() {
        val a = TapArbiter(window)
        assertEquals(TapArbiter.Decision.DeferredToggle, a.onPointerUp(0))
        assertEquals(TapArbiter.Decision.PlayPause, a.onPointerUp(120))
        // 第二击之后挂起已被取消，到点也不该切显隐
        assertFalse(a.onToggleDue(window + 100))
    }

    @Test
    fun `相隔超过窗口的两次单击各切一次显隐`() {
        val a = TapArbiter(window)
        assertEquals(TapArbiter.Decision.DeferredToggle, a.onPointerUp(0))
        assertTrue(a.onToggleDue(window))
        assertEquals(TapArbiter.Decision.DeferredToggle, a.onPointerUp(window + 120))
        assertFalse(a.onToggleDue(window + 200))
        assertTrue(a.onToggleDue(window * 2 + 120))
    }

    @Test
    fun `正好压在窗口边界仍算双击`() {
        val a = TapArbiter(window)
        a.onPointerUp(0)
        assertEquals(TapArbiter.Decision.PlayPause, a.onPointerUp(window))
    }

    @Test
    fun `切走页面取消挂起后不再补发显隐`() {
        val a = TapArbiter(window)
        assertEquals(TapArbiter.Decision.DeferredToggle, a.onPointerUp(0))
        a.cancelPending()
        assertFalse(a.onToggleDue(window + 500))
    }
}
