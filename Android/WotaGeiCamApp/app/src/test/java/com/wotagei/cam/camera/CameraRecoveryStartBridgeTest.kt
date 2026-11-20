package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自愈升级与失败页重试均走真实 Controller/Engine，不以源码字符串代替开镜头行为。 */
class CameraRecoveryStartBridgeTest {
    @Test
    fun `已启动预览forceRebind必须沿同一sink重新开镜头并重放分析槽`() = platform { p ->
        val analysis = p.platform.surface("recovery-analysis")
        val reader = p.platform.reader("recovery-reader")
        p.bind()
        p.platform.call(p.controller, "setAnalysisTarget", analysis, reader)
        startLive(p)
        val oldEngine = p.engine()
        assertEquals("已满足的 start 不再欠 pendingStart", false, p.platform.field(p.controller, "pendingStart"))

        p.rebind()
        val replacement = p.engine()
        assertNotSame(oldEngine, replacement)
        p.drain()

        assertEquals("forceRebind 不能只 attach 空引擎，必须重新进入 openCamera", 2, p.opened())
        assertSame(p.sink, p.platform.call(p.controller, "currentSink"))
        assertSame(p.sink, p.platform.field(replacement, "sink"))
        assertSame(analysis, p.platform.field(replacement, "analysisSurface"))
        assertSame(reader, p.platform.field(replacement, "analysisReader"))
        p.completeOpen(1)
        assertEquals(listOf("recovery-preview", "recovery-analysis"), p.outputNames(1))
        p.completeSession(1)
        p.assertLive()
        p.drain(oldEngine)
        assertEquals("旧引擎迟到 close 不能关闭重放的分析 reader", 0, p.platform.readerCloseCount(reader))
        assertEquals(1, p.deviceCloseCount(0))
        assertTrue(p.platform.field(replacement, "desiredPreview") as Boolean)
    }

    @Test
    fun `forceRebind后旧设备迟到onOpened必须关闭且不能覆盖新预览`() = platform { p ->
        p.bind()
        p.start()
        val oldEngine = p.engine()
        p.drain()
        assertEquals(1, p.opened())

        p.rebind()
        p.drain()
        assertEquals("替代引擎必须自行启动，不等旧引擎回调", 2, p.opened())
        p.completeOpen(1)
        p.completeSession(0)
        p.assertLive()
        val currentDevice = p.platform.field(p.engine(), "device")

        p.completeOpen(0, oldEngine)
        assertEquals("旧引擎的 generation/thread guard 必须收掉过期设备", 1, p.deviceCloseCount(0))
        assertSame(currentDevice, p.platform.field(p.engine(), "device"))
        p.assertLive()
    }

    @Test
    fun `初次枚举失败且engine没有device和lens时重试必须重新枚举打开`() = platform { p ->
        p.available(false)
        p.bind()
        p.start()
        p.drain()
        assertEquals("OPEN_FAILED_INNER_ERROR", p.state("Device").toString())
        assertNull(p.platform.field(p.engine(), "device"))
        assertNull(p.platform.field(p.engine(), "lens"))
        assertEquals(0, p.opened())

        p.available(true)
        p.retry()
        p.drain()

        assertEquals("失败页重试不能在空 lens 的 reopenLastLens 中静默返回", 1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
        assertEquals(false, p.state("NeedsCameraPermission"))
    }

    @Test
    fun `手动重试时engine缺失必须保留启动请求直到绑定sink`() = platform { p ->
        p.retry()
        assertEquals("没有引擎时重试也必须登记 start 请求", true, p.platform.field(p.controller, "pendingStart"))
        p.bind()
        p.drain()
        assertEquals(1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
    }

    @Test
    fun `forceRebind期间缺少相机权限必须等授权后才重新开镜头`() = platform { p ->
        p.cameraPermission(false)
        p.bind()
        p.start()
        p.drain()
        assertEquals(true, p.state("NeedsCameraPermission"))
        val oldEngine = p.engine()

        p.rebind()
        p.drain()
        p.drain(oldEngine)
        assertEquals("缺权限时不能绕过原权限流程", 0, p.opened())
        assertEquals(true, p.state("NeedsCameraPermission"))

        p.cameraPermission(true)
        p.platform.call(p.controller, "onPermissionsResult")
        p.drain()
        assertEquals(1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
        assertEquals(false, p.state("NeedsCameraPermission"))
    }

    @Test
    fun `空lens重试仍要检查相机权限且缺音频只降级视频`() = platform { p ->
        p.available(false)
        p.bind()
        p.start()
        p.drain()
        p.available(true)
        p.cameraPermission(false)
        p.retry()
        p.drain()
        assertEquals("重试完整启动链必须保留相机权限检查", true, p.state("NeedsCameraPermission"))
        assertEquals(0, p.opened())

        p.cameraPermission(true)
        p.audioPermission(false)
        p.platform.call(p.controller, "onPermissionsResult")
        p.drain()
        assertEquals(1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
        assertEquals(true, p.state("AudioDegraded"))
        val params = p.platform.call(p.controller, "getParams")!!
        val audio = p.platform.call(params, "getAudioEnabled")!!
        assertEquals(false, p.platform.call(audio, "getValue"))
    }

    @Test
    fun `设备健康的重试继续只重建会话不得额外关闭重开镜头`() = platform { p ->
        p.bind()
        startLive(p)
        val engine = p.engine()
        p.retry()
        p.drain()
        assertEquals("健康设备仍保留低成本会话恢复", 1, p.opened())
        assertEquals(2, p.sessions())
        assertEquals(0, p.deviceCloseCount(0))
        assertEquals(1, p.sessionCloseCount(0))
        assertSame(engine, p.engine())
        p.completeSession(1)
        p.assertLive()
    }

    @Test
    fun `open失败后手动重试仍能重新打开并启动预览`() = platform { p ->
        p.rejectOpen(true)
        p.bind()
        p.start()
        p.drain()
        assertEquals("OPEN_FAILED_INNER_ERROR", p.state("Device").toString())
        assertFalse(p.platform.call(p.engine(), "isPreviewLive") as Boolean)

        p.rejectOpen(false)
        p.retry()
        p.drain()
        assertEquals(2, p.opened())
        p.completeOpen(1)
        p.completeSession(0)
        p.assertLive()
    }

    private fun startLive(p: CameraRecoveryStartFixture) {
        p.start()
        p.drain()
        assertEquals("回归前置态必须由真实 start 打开镜头", 1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
    }

    private fun platform(block: (CameraRecoveryStartFixture) -> Unit) =
        CameraRecoveryStartFixture(requireNotNull(javaClass.classLoader!!)).use(block)
}
