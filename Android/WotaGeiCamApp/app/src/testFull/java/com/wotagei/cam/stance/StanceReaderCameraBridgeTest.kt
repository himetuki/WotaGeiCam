package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 真实 Controller/Engine/站位 hooks 的行为 RED；不以源码文本、running 标记或重复 close 幂等代替资源契约。 */
class StanceReaderCameraBridgeTest {
    @get:Rule
    val temporary = TemporaryFolder(File("build/tmp/stance-reader-camera-test").apply { mkdirs() })

    @Test
    fun `同页换sink无需重新进入页面仍发布新分析帧`() = platform { p ->
        val oldSink = p.platform.sink("direct-preview")
        val newSink = p.platform.sink("gpu-preview")
        p.start(oldSink)
        assertEquals("正向对照必须由真实相机会话回执接纳分析流", "INCLUDED", p.cameraDecision().toString())
        assertEquals(1, p.emitCurrentSession())
        val before = p.output()
        assertNotNull("切换前必须先让真实分析回调发布一帧，不能只看 running", before)
        val oldEngine = p.engine()

        // 对齐 CameraScreen 的 activeSink effect：旧 sink dispose 后绑定新 sink，站位 effect 未重进。
        p.call(p.camera, "unbind", oldSink)
        p.relayDecision()
        p.renderMode("GPU")
        p.call(p.camera, "bind", newSink)
        p.call(p.camera, "start")
        p.pumpCameraAndMain()
        p.relayDecision()
        p.analysisIdle()
        assertFalse("必须实际换到新引擎", oldEngine === p.engine())

        // 不调 onPageEnter，也不重新 setEnabled/attach；帧只能经新引擎的真实 repeating 目标进入。
        val reader = p.reader()
        val surface = p.call(reader, "getSurface")!!
        val configured = p.configuredTargets()
        val repeating = p.repeatingTargets()
        val frames = p.emitCurrentSession()
        val after = p.output()
        assertTrue(
            "同页换 sink 后，新会话和 repeating 都必须包含有效分析目标，不能永久停在 ABSENT",
            p.surfaceLive(reader) && configured.contains(surface) && repeating.contains(surface),
        )
        assertEquals("新引擎必须真实接纳分析流", "INCLUDED", p.cameraDecision().toString())
        assertEquals("不能绕过新会话直接给旧 reader 灌帧", 1, frames)
        assertNotNull("新目标必须实际发布新结果，而不是只恢复 running", after)
        assertNotSame("旧叠加残留不能冒充切换后的新帧", before, after)
    }

    @Test
    fun `真实释放链等待在途Image归还后由分析owner关闭reader一次`() = platform { p ->
        p.start(p.platform.sink("preview"))
        assertEquals(1, p.emitCurrentSession())
        assertNotNull("在途竞态前先证明实际分析和结果发布可用", p.output())
        val analysis = p.analysis()
        val owner = p.field(analysis, "thread") as Thread
        val reader = p.reader()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        p.blockNextPlanes(reader, entered, proceed)
        var closesBeforeReturn = -1
        var returnedWhileBlocked = -1
        try {
            p.emitWithoutWaiting()
            assertTrue("必须卡在真实 listener 已 acquire 且 processImage 正读取 planes 的窗口", entered.await(5, TimeUnit.SECONDS))
            assertEquals("正向帧已归还；被卡住的第二帧尚未归还", 1, p.imageReturned(reader))

            // 真实链：StanceController.release -> detachHook -> CameraController.setAnalysisTarget(null)。
            // Main 可以封口/摘槽，但不得与正在读 planes 的 Analysis owner 并发关闭 reader。
            p.call(p.stance, "release")
            closesBeforeReturn = p.closeCount(reader)
            returnedWhileBlocked = p.imageReturned(reader)
        } finally {
            // 断言失败也必须放行；没有 sleep、无限 join 或留在后台的挂起回调。
            proceed.countDown()
        }
        p.awaitStopped(analysis)
        p.pumpCameraAndMain()

        assertEquals("暂停或摘槽不等于在途 Image 已归还；归还之前 reader 不得 close", 0, closesBeforeReturn)
        assertEquals("主线程释放链返回时第二帧仍在读取，不能伪造已 drain", 1, returnedWhileBlocked)
        assertEquals("两个 acquired Image 均须由真实 processImage finally 归还", 2, p.imageReturned(reader))
        assertEquals("不能把失效 Image 的访问异常吞掉后声称生命周期安全", 0, p.invalidAccesses(reader))
        assertEquals("最终 reader 只归约定 owner 释放一次，不以 double close 幂等替代所有权", 1, p.closeCount(reader))
        assertEquals("reader 与在途帧必须由同一长期 Analysis owner 串行收尾", listOf(owner), p.closeOwners(reader))
        val events = p.events(reader)
        assertTrue("最终 reader.close 必须晚于最后一帧归还：$events", events.lastIndexOf("image.return") < events.indexOf("reader.close"))
        assertEquals("release 后的旧帧不得再发布叠加", null, p.output())
        assertFalse("最终关闭应让旧分析面失效", p.surfaceLive(reader))
    }

    @Test
    fun `Controller销毁时reader关闭仍排在分析owner且在途Image归还后`() = platform { p ->
        p.start(p.platform.sink("preview"))
        assertEquals(1, p.emitCurrentSession())
        assertNotNull("正向分析必须先可用", p.output())
        val analysis = p.analysis()
        val owner = p.field(analysis, "thread") as Thread
        val reader = p.reader()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        p.blockNextPlanes(reader, entered, proceed)
        var closesBeforeReturn = -1
        var returnedWhileBlocked = -1
        try {
            p.emitWithoutWaiting()
            assertTrue("必须卡在真实 planes 读取窗口", entered.await(5, TimeUnit.SECONDS))
            // 非配置销毁路径：Controller.onCleared 不得在 Main 直接 close 分析 reader。
            p.call(p.camera, "onCleared")
            closesBeforeReturn = p.closeCount(reader)
            returnedWhileBlocked = p.imageReturned(reader)
        } finally {
            proceed.countDown()
        }
        p.analysisIdle()
        p.pumpCameraAndMain()

        assertEquals("在途 Image 归还前 reader 不得 close", 0, closesBeforeReturn)
        assertEquals("销毁返回时第二帧仍在读取", 1, returnedWhileBlocked)
        assertEquals(2, p.imageReturned(reader))
        assertEquals("不得让失效 Image 访问被吞后冒充安全", 0, p.invalidAccesses(reader))
        assertEquals("最终 reader 只由约定 owner 关闭一次", 1, p.closeCount(reader))
        assertEquals(listOf(owner), p.closeOwners(reader))
        val events = p.events(reader)
        assertTrue("close 必须晚于最后一帧归还：$events", events.lastIndexOf("image.return") < events.indexOf("reader.close"))
    }

    private fun platform(body: (StanceReaderCameraBridgeFixture) -> Unit) {
        StanceReaderCameraBridgeFixture(temporary.newFolder(), javaClass.classLoader).use(body)
    }
}
