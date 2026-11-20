package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 生产 open/session/repeating 出口取证，专门覆盖原 inline IO 夹具漏掉的异步启动边界。 */
class CameraStartupIntentBridgeTest {
    @Test
    fun `存活预览rebind枚举挂起后pause完成不得被迟到启动覆盖`() = platform { f ->
        val p = f.camera
        f.startLive()
        val oldEngine = p.engine()
        p.rebind()
        val replacement = p.engine()
        assertNotSame(oldEngine, replacement)
        assertEquals(1, f.ioPending())
        p.platform.call(p.controller, "pause")
        p.drain()
        assertEquals("STOP", p.state("Preview").toString())

        f.releaseEnumeration()
        p.drain()
        p.drain(oldEngine)

        assertEquals("最后 pause 已执行，不得再次向平台 openCamera", 1, p.opened())
        assertEquals("不得创建第二条会话", 1, p.sessions())
        assertFalse(p.platform.call(replacement, "isPreviewLive") as Boolean)
        assertEquals("STOP", p.state("Preview").toString())
        assertEquals(0, f.ioPending())
    }

    @Test
    fun `挂起启动pause再resume必须由最新请求启动一次并真正repeating`() = platform { f ->
        val p = f.camera
        p.bind()
        p.start()
        p.platform.call(p.controller, "pause")
        p.drain()
        p.platform.call(p.controller, "resume")
        p.drain()

        f.finishEnumerations()
        p.drain()

        assertEquals("过期 start 与最新 resume 不能各开一次设备", 1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
        assertEquals(1, p.sessions())
        assertEquals(0, f.ioPending())
    }

    @Test
    fun `挂起启动pause resume pause最终仍不得open`() = platform { f ->
        val p = f.camera
        p.bind()
        p.start()
        p.platform.call(p.controller, "pause")
        p.platform.call(p.controller, "resume")
        p.platform.call(p.controller, "pause")
        p.drain()
        f.finishEnumerations()
        p.drain()

        assertEquals("reloadWanted 不得在最终 pause 后补开", 0, p.opened())
        assertEquals(0, p.sessions())
        assertEquals("STOP", p.state("Preview").toString())
    }

    @Test
    fun `busyLoading已欠新rebind时pause必须同时拦住接力`() = platform { f ->
        val p = f.camera
        p.bind()
        p.start()
        val retired = p.engine()
        p.rebind()
        p.platform.call(p.controller, "pause")
        p.drain()
        p.drain(retired)
        f.finishEnumerations()
        p.drain()

        assertEquals("旧枚举 finally 不得在暂停期间补做 replacement 启动", 0, p.opened())
        assertEquals(0, p.sessions())
        assertNull("过期结果不得发布 currentSlot", p.state("CurrentSlot"))
        assertEquals(0, f.ioPending())
    }

    @Test
    fun `枚举已返回IO但stop先到Main时不许回填镜头及能力参数`() = platform { f ->
        val p = f.camera
        p.bind()
        p.start()
        f.runIo()
        assertEquals(1, f.mainPending())
        val originalSize = f.param("Size")
        val retired = p.engine()
        p.platform.call(p.controller, "stop")
        p.drain(retired)
        f.runMain()
        p.drain(retired)

        assertEquals(0, p.opened())
        assertNull("已 stop 的枚举不得发布镜头", p.state("CurrentSlot"))
        assertEquals("过期能力不得改写参数总线", originalSize, f.param("Size"))
        assertEquals(0, f.ioPending())
    }

    @Test
    fun `空枚举等待Main时unbind后不得发布迟到失败`() = platform { f ->
        val p = f.camera
        p.available(false)
        p.bind()
        p.start()
        f.runIo()
        assertEquals(1, f.mainPending())
        val retired = p.engine()
        p.platform.call(p.controller, "unbind", p.sink)
        p.drain(retired)
        val afterUnbind = p.state("Device")
        f.runMain()

        assertEquals("旧启动不能把已解绑状态写成 OPEN_FAILED", afterUnbind, p.state("Device"))
        assertNull(p.state("CurrentSlot"))
        assertEquals(0, p.opened())
        assertEquals(0, f.ioPending())
    }

    @Test
    fun `替换引擎但没有新start时旧continuation不得污染新引擎状态`() = platform { f ->
        val p = f.camera
        p.bind()
        p.start()
        f.runIo()
        assertEquals(1, f.mainPending())
        val retired = p.engine()
        val replacementSink = p.platform.sink("new-preview")
        p.platform.call(p.controller, "bind", replacementSink)
        val replacement = p.engine()
        assertNotSame(retired, replacement)
        val originalSize = f.param("Size")
        f.runMain()
        p.drain()
        p.drain(retired)

        assertEquals(0, p.opened())
        assertNull("即使生命周期意图未变，也必须检查 captured engine identity", p.state("CurrentSlot"))
        assertEquals(originalSize, f.param("Size"))
        assertSame(replacement, p.engine())
    }

    @Test
    fun `过期枚举不能提前回填新引擎而busy接力仍须启动最新rebind`() = platform { f ->
        val p = f.camera
        p.bind()
        p.start()
        f.runIo()
        assertEquals(1, f.mainPending())
        val retired = p.engine()
        val originalSize = f.param("Size")
        p.rebind()
        val replacement = p.engine()
        f.runMain()
        p.drain()
        p.drain(retired)

        assertNull("新枚举尚未完成，不得先发布旧枚举的槽位", p.state("CurrentSlot"))
        assertEquals(originalSize, f.param("Size"))
        assertEquals(0, p.opened())
        assertEquals("必须将 busyLoading 的合法新请求接力给当前引擎", 1, f.ioPending())
        f.releaseEnumeration()
        p.drain()
        assertEquals(1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
        assertSame(replacement, p.engine())
    }

    @Test
    fun `stop bind resume在旧IO挂起时仍必须履行新引擎启动`() = platform { f ->
        val p = f.camera
        p.bind()
        p.start()
        val retired = p.engine()
        p.platform.call(p.controller, "stop")
        p.platform.call(p.controller, "resume")
        p.bind()
        val replacement = p.engine()
        assertNotSame(retired, replacement)
        f.finishEnumerations()
        p.drain()
        p.drain(retired)

        assertEquals(1, p.opened())
        p.completeOpen(0)
        p.completeSession(0)
        p.assertLive()
        assertSame(replacement, p.engine())
    }

    private fun platform(block: (CameraStartupIntentFixture) -> Unit) =
        CameraStartupIntentFixture(requireNotNull(javaClass.classLoader!!)).use(block)
}
