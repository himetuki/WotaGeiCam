package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内录状态/事件短名映射守卫（R3 观测修复配套）。
 *
 * 锁两件事：
 * 1. 5 个状态、6 个事件**各有一条非空映射**——漏一条就退化成 R8 混淆名，logcat 当场不可读；
 * 2. 映射值互异（两个不同状态打成同一串 = 现场排查会把两条不同的迁移看成同一条）。
 *
 * 刻意用 `when` 无 else 之外的显式枚举列表锁覆盖面：新增 sealed 分支而忘了补映射时，
 * 既由编译器在 [captureStateLabel] / [captureEventLabel] 处报错，本测试也会因实例数变化而红。
 */
class CaptureTraceLabelTest {

    private val states = listOf(
        CaptureState.Idle,
        CaptureState.Authorizing,
        CaptureState.Active,
        CaptureState.Failed("buildCaptureConfig"),
        CaptureState.Revoked
    )

    private val events = listOf(
        CaptureEvent.ConsentRequested,
        CaptureEvent.ConsentCancelled,
        CaptureEvent.Established,
        CaptureEvent.EstablishFailed("buildCaptureConfig"),
        CaptureEvent.ProjectionStopped,
        CaptureEvent.Shutdown
    )

    @Test
    fun `状态短名非空且互异`() {
        val labels = states.map { captureStateLabel(it) }
        assertEquals("状态总数与短名数应一一对应", states.size, labels.size)
        labels.forEach { assertTrue("状态短名不许为空", it.isNotBlank()) }
        assertEquals("状态短名必须互异", labels.size, labels.distinct().size)
    }

    @Test
    fun `事件短名非空且互异`() {
        val labels = events.map { captureEventLabel(it) }
        assertEquals("事件总数与短名数应一一对应", events.size, labels.size)
        labels.forEach { assertTrue("事件短名不许为空", it.isNotBlank()) }
        assertEquals("事件短名必须互异", labels.size, labels.distinct().size)
    }
}