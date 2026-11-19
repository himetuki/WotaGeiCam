package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R01/R03/R04 行为测试：真实 Controller/Engine 字节码 + 平台边界桩。
 * 不用源码扫描代替「旧就绪快照是否被世代失效」这类行为。
 */
class CameraReadinessBridgeTest {

    @Test
    fun `会话配置成功但repeating未接纳前不得ready`() = platform { p ->
        p.bind()
        p.start()
        p.drain()
        p.completeOpen(0)
        // 会话已发起但还没配置回调：绝不能 ready
        assertFalse(p.ready(p.readiness()))
        assertFalse(p.platform.call(p.engine(), "isPreviewLive") as Boolean)

        p.completeSession(0)
        val ready = p.readiness()
        assertTrue(p.ready(ready))
        assertTrue(p.platform.call(ready, "getConfigured") as Boolean)
        assertTrue(p.platform.call(ready, "getRepeatingSubmitted") as Boolean)
        p.assertLive()
    }

    @Test
    fun `录制面交接后旧ready快照必须被新会话世代取代`() = platform { p ->
        // GPU 的编码面归 GL，只有 DIRECT 会把这枚 Surface 交给 Camera2 的 repeating。
        startLive(p, direct = true)
        val baseline = p.readiness()
        assertTrue(p.ready(baseline))

        val encoder = p.platform.surface("encoder")
        val handoff = p.platform.call(p.controller, "setRecordingTarget", encoder, null) as Int
        assertNotEquals(0, handoff)
        p.drain()

        val rebuilding = p.readiness()
        assertEquals("已登记的面尚未被 repeating 接纳", 0, p.recordingTargetEpoch(rebuilding))
        assertFalse("换面后新会话尚未配置，不能沿用旧 ready", p.ready(rebuilding))
        assertNotEquals(p.attempt(baseline), p.attempt(rebuilding))
        assertEquals(
            "录制面必须进入本世代快照",
            true,
            p.platform.call(rebuilding, "getRecordingTargetPresent") as Boolean
        )

        p.completeSession(1)
        val handedOff = p.readiness()
        assertTrue(p.ready(handedOff))
        assertEquals("repeating 只确认本次交接", handoff, p.recordingTargetEpoch(handedOff))
        assertNotEquals("旧快照不能匹配交接后的会话", p.attempt(baseline), p.attempt(handedOff))
        assertNotEquals(
            "旧 fast-fail 快照也不能当作新会话",
            p.attempt(rebuilding),
            p.attempt(handedOff)
        )
    }

    @Test
    fun `同一录制面重新交接只接受最新会话`() = platform { p ->
        startLive(p, direct = true)
        p.platform.call(p.controller, "setRecordingActive", true)
        val encoder = p.platform.surface("encoder")

        val first = p.platform.call(p.controller, "setRecordingTarget", encoder, null) as Int
        p.drain()
        p.completeSession(1)
        assertEquals(first, p.recordingTargetEpoch(p.readiness()))

        val replaced = p.platform.call(p.controller, "setRecordingTarget", encoder, null) as Int
        assertNotEquals(first, replaced)
        assertEquals("相机线程尚未处理交接时仍只能读到旧编号", first, p.recordingTargetEpoch(p.readiness()))
        p.drain()
        val latest = p.platform.call(p.controller, "setRecordingTarget", encoder, null) as Int
        assertNotEquals(replaced, latest)
        p.drain()
        assertEquals(4, p.sessions())

        p.completeSession(2)
        assertEquals(0, p.recordingTargetEpoch(p.readiness()))
        assertFalse(p.ready(p.readiness()))
        assertTrue(p.sessionCloseCount(2) >= 1)
        p.completeSession(3)
        assertTrue(p.ready(p.readiness()))
        assertEquals(latest, p.recordingTargetEpoch(p.readiness()))
    }

    @Test
    fun `停止后迟到的会话配置回调不能把ready写回`() = platform { p ->
        startLive(p)
        val encoder = p.platform.surface("encoder")
        p.platform.call(p.controller, "setRecordingTarget", encoder, null)
        p.drain()
        val pending = p.readiness()
        assertFalse(p.ready(pending))

        p.platform.call(p.controller, "pause")
        p.drain()
        p.completeSession(1)
        p.drain()

        val after = p.readiness()
        assertFalse("过期 onConfigured 不得复活就绪态", p.ready(after))
        assertEquals("STOP", p.state("Preview").toString())
        assertTrue("过期会话必须当场关闭", p.sessionCloseCount(1) >= 1)
    }

    @Test
    fun `录制窗口True跨引擎重放且录制面在新引擎重放`() = platform { p ->
        startLive(p, direct = true)
        val encoder = p.platform.surface("encoder")
        val handoff = p.platform.call(p.controller, "setRecordingTarget", encoder, null) as Int
        p.drain()
        p.completeSession(1)
        assertEquals(handoff, p.recordingTargetEpoch(p.readiness()))
        p.platform.call(p.controller, "setRecordingActive", true)

        p.platform.call(p.controller, "unbind", p.sink)
        p.platform.call(p.controller, "bind", p.sink)
        p.drain()
        p.platform.call(p.controller, "start")
        p.drain()
        assertEquals(2, p.opened())
        p.completeOpen(1)
        assertTrue(
            "新引擎会话必须同时包含预览面与重放的录制面",
            p.outputNames(2).containsAll(listOf("recovery-preview", "encoder"))
        )
        assertTrue("录制窗口必须跨引擎重放", p.recordingWindow(p.readiness()))
        assertEquals("替代引擎还未接纳目标", 0, p.recordingTargetEpoch(p.readiness()))
        p.completeSession(2)
        assertEquals("重放保留 Controller 分配的原交接号", handoff, p.recordingTargetEpoch(p.readiness()))
        p.assertLive()
    }

    @Test
    fun `自动重开在stopPreview后不得复活预览`() = platform { p ->
        startLive(p)
        val openedBefore = p.opened()
        p.platform.call(p.engine(), "scheduleReopen")
        assertTrue("自动重开必须排入 delayed 回调", p.delayedCount() >= 1)

        p.platform.call(p.controller, "pause")
        p.drain()
        p.runDelayed()

        assertEquals("预览意图已撤销，迟到重开必须作废", openedBefore, p.opened())
    }

    @Test
    fun `自动重开在设备世代已变后不得再开旧镜头`() = platform { p ->
        startLive(p)
        val openedBefore = p.opened()
        val engine = p.engine()
        val slot = p.platform.field(engine, "lens")!!
        p.platform.call(engine, "scheduleReopen")

        // 新意图（换/重开同一颗）推进设备世代；旧 delayed 回调不得再开一次
        p.platform.call(engine, "open", slot)
        p.drain()
        assertEquals(openedBefore + 1, p.opened())

        p.runDelayed()
        assertEquals("世代已变，旧重开回调必须跳过", openedBefore + 1, p.opened())
    }

    private fun startLive(p: CameraRecoveryStartFixture, direct: Boolean = false) {
        if (direct) {
            p.renderMode("DIRECT")
        }
        p.bind()
        p.start()
        p.drain()
        assertEquals(1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
    }

    private fun platform(block: (CameraRecoveryStartFixture) -> Unit) {
        CameraRecoveryStartFixture(javaClass.classLoader).use(block)
    }
}
