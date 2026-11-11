package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N12：forceRebind 的旧引擎异步收尾不得关闭新引擎刚重放的共享分析 reader。
 *
 * 测试只通过真实 CameraController.forceRebind、真实 Camera2Engine.setAnalysisTarget/close
 * 和可控平台 Handler 调度时序取证；不复制生产关闭或重放逻辑。
 */
class N12CameraControllerRebindBridgeTest {

    @Test
    fun `N12 forceRebind旧引擎迟到close不得关闭新引擎重放的共享analysis reader`() = platform { p ->
        val app = p.application()
        val controller = p.controller(app)
        val sink = p.sink("preview")
        val analysisSurface = p.surface("analysis")
        val reader = p.reader("analysis-reader")

        p.call(controller, "bind", sink)
        assertTrue(
            "真实 Controller.setAnalysisTarget 必须先登记共享 reader",
            p.call(controller, "setAnalysisTarget", analysisSurface, reader) as Boolean
        )

        val oldEngine = p.engineOf(controller)
        // 先执行旧引擎的挂槽任务，建立旧引擎对共享 reader 的真实 ownership。
        p.drain(oldEngine)
        p.call(controller, "forceRebind", sink)
        val newEngine = p.engineOf(controller)
        assertFalse("forceRebind 必须替换引擎实例", oldEngine === newEngine)

        // 先让真实新引擎执行 bind 中的 analysisTarget 重放，再放行旧引擎的异步 close。
        p.drain(newEngine)
        assertSame(
            "新引擎必须持有 Controller 重放的同一 reader",
            reader,
            p.field(newEngine, "analysisReader")
        )
        p.drain(oldEngine)

        assertEquals(
            "旧引擎迟到 close 不得回收新引擎已接管的共享 reader",
            0,
            p.readerCloseCount(reader)
        )
        assertSame(reader, p.field(newEngine, "analysisReader"))
    }

    private fun platform(block: (N12CameraControllerFixture) -> Unit) =
        N12CameraControllerFixture(javaClass.classLoader).use(block)
}
