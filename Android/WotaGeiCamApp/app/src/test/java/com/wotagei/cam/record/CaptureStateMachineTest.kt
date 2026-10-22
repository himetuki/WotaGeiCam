package com.wotagei.cam.record

import com.wotagei.cam.record.CaptureState.Active
import com.wotagei.cam.record.CaptureState.Authorizing
import com.wotagei.cam.record.CaptureState.Failed
import com.wotagei.cam.record.CaptureState.Idle
import com.wotagei.cam.record.CaptureState.Revoked
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内录授权状态机的**全转移表**（纯 Kotlin，JVM 直打）。
 *
 * 红/绿突变自证：把 [transition] 任一分支改写（如 ConsentCancelled 改回 Authorizing、
 * ProjectionStopped 改去 Failed、ConsentRequested 改为 Authorizing 态也可重复进），
 * 对应用例即红。要测的是"计划→行为"的桥本身：转移表就是运行时行为。
 */
class CaptureStateMachineTest {

    private fun run(vararg events: CaptureEvent): CaptureState =
        events.fold(Idle as CaptureState, ::transition)

    // ------------------------------------------------------------------ 正常链路

    @Test
    fun `正常链路_Idle拉起授权建链到Active`() {
        assertEquals(Active, run(CaptureEvent.ConsentRequested, CaptureEvent.Established))
    }

    @Test
    fun `建立失败_Authorizing转Failed并带原因`() {
        assertEquals(Failed("boom"), run(CaptureEvent.ConsentRequested, CaptureEvent.EstablishFailed("boom")))
    }

    // ------------------------------------------------------------------ 取消回滚

    @Test
    fun `授权取消回滚_Authorizing取消回Idle`() {
        assertEquals(Idle, run(CaptureEvent.ConsentRequested, CaptureEvent.ConsentCancelled))
    }

    @Test
    fun `取消在非Authorizing态不生效_原态保持`() {
        assertEquals(Idle, transition(Idle, CaptureEvent.ConsentCancelled))
        assertEquals(Active, transition(Active, CaptureEvent.ConsentCancelled))
        assertEquals(Revoked, transition(Revoked, CaptureEvent.ConsentCancelled))
    }

    // ------------------------------------------------------------------ 撤销（回环境音语义）

    @Test
    fun `Active被撤销_转Revoked_批3据此回环境音`() {
        assertEquals(Revoked, run(CaptureEvent.ConsentRequested, CaptureEvent.Established, CaptureEvent.ProjectionStopped))
    }

    @Test
    fun `建链中被撤销_也进Revoked_回环境音`() {
        // 授权同意后会话已存在才可能被撤：建立中途被撤同样按"撤销过"记
        assertEquals(Revoked, run(CaptureEvent.ConsentRequested, CaptureEvent.ProjectionStopped))
    }

    @Test
    fun `Failed态被撤销_进Revoked`() {
        assertEquals(Revoked, run(CaptureEvent.ConsentRequested, CaptureEvent.EstablishFailed("x"), CaptureEvent.ProjectionStopped))
    }

    @Test
    fun `Idle或Revoked态收到ProjectionStopped_原态保持`() {
        assertEquals(Idle, transition(Idle, CaptureEvent.ProjectionStopped))
        assertEquals(Revoked, transition(Revoked, CaptureEvent.ProjectionStopped))
    }

    // ------------------------------------------------------------------ 重复授权与恢复

    @Test
    fun `Active下重复授权_回Authorizing并可重建到Active`() {
        val s = run(
            CaptureEvent.ConsentRequested, CaptureEvent.Established,
            CaptureEvent.ConsentRequested, CaptureEvent.Established
        )
        assertEquals(Active, s)
    }

    @Test
    fun `Revoked重新授权_可再走完整链路到Active`() {
        val s = run(
            CaptureEvent.ConsentRequested, CaptureEvent.Established, CaptureEvent.ProjectionStopped,
            CaptureEvent.ConsentRequested, CaptureEvent.Established
        )
        assertEquals(Active, s)
    }

    @Test
    fun `Failed重新授权_可恢复到Active`() {
        val s = run(
            CaptureEvent.ConsentRequested, CaptureEvent.EstablishFailed("x"),
            CaptureEvent.ConsentRequested, CaptureEvent.Established
        )
        assertEquals(Active, s)
    }

    @Test
    fun `Authorizing中重复拉起授权框_保持Authorizing`() {
        assertEquals(Authorizing, transition(Authorizing, CaptureEvent.ConsentRequested))
    }

    @Test
    fun `乱序回执_Established与EstablishFailed只在Authorizing生效`() {
        // 非授权中态收到建链回执不许凭空改态（回执迟到/服务重启的脏回执）：原态保持
        assertEquals(Active, transition(Active, CaptureEvent.Established))
        assertEquals(Active, transition(Active, CaptureEvent.EstablishFailed("late")))
        assertEquals(Idle, transition(Idle, CaptureEvent.Established))
        assertEquals(Revoked, transition(Revoked, CaptureEvent.EstablishFailed("late")))
    }

    // ------------------------------------------------------------------ 关停

    @Test
    fun `关停归Idle_任意态幂等`() {
        listOf<CaptureState>(Idle, Authorizing, Active, Failed("x"), Revoked).forEach { st ->
            assertEquals("从 $st 关停必须归 Idle", Idle, transition(st, CaptureEvent.Shutdown))
            assertEquals("Idle 再关停仍 Idle", Idle, transition(Idle, CaptureEvent.Shutdown))
        }
    }
}
