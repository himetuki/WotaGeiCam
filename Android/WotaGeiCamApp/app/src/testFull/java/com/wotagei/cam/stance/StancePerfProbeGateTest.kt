package com.wotagei.cam.stance

import com.wotagei.cam.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * B15 探针（`StancePerfProbe`）的关态守卫。
 *
 * 正式变体里它必须是纯 no-op：不写 notes、不统计帧、不起心跳线程。
 * 单测跑 debug 变体（`PERF_PROBE=false`），一旦有人在关态提前记账，
 * 这里会看到非空 notes；关态若调用 Debug/SystemClock 则直接抛 "not mocked"。
 */
class StancePerfProbeGateTest {

    @Test
    fun `JVM 变体默认关且关时不留痕`() {
        assertFalse("单测跑的是 debug 变体，PERF_PROBE 必须为 false", BuildConfig.PERF_PROBE)
        assertFalse(StancePerfProbe.enabled)
        StancePerfProbe.note("关态不该记录的阶段标记")
        StancePerfProbe.onFrame(1_000_000L)
        assertEquals("关态 notes 必须为空", "", StancePerfProbe.notesText)
    }
}
