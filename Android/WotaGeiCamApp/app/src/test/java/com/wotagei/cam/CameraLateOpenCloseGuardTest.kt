package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Camera2「迟到 onOpened 无人 close」的修复守卫（2026-10-18）。
 *
 * 缺陷：`openCamera` 的回调原本投在引擎自己的 workHandler 上；`close()` 收尾会 `quitSafely()`，
 * 此后迟到的 `onOpened` 投递即丢——已打开的 CameraDevice 没有任何人 close，相机句柄泄漏到
 * 进程结束，再次进相机页可能直接 CAMERA_IN_USE。
 *
 * 修复：设备回调改投**进程级** callbackHandler（永不 quit）；回调本体判代后投回引擎线程，
 * `Handler.post` 在 looper 已退时返回 false ⇒ 当场 close。直接改引擎状态仍只发生在 work 线程。
 */
class CameraLateOpenCloseGuardTest {

    private val src = codeOnly(mainSourceText("camera/Camera2Engine.kt"))

    @Test
    fun `openCamera必须用进程级回调Looper`() {
        assertTrue(
            "openCamera 必须用 callbackHandler（投到 workHandler = close 后迟到回调被丢）",
            src.contains("manager.openCamera(slot.logicId, deviceCallback(gen), callbackHandler)")
        )
        assertTrue(
            "回调 Looper 必须是进程级单例（by lazy）",
            src.contains("private val callbackThread: HandlerThread by lazy") &&
                src.contains("private val callbackHandler: Handler by lazy")
        )
        assertTrue(
            "回调线程名可辨识（真机排查用）",
            mainSourceText("camera/Camera2Engine.kt").contains("\"WotaCamCallback\"")
        )
    }

    @Test
    fun `回调投递失败必须就地关闭设备`() {
        val raw = mainSourceText("camera/Camera2Engine.kt")
        // 表达式体函数（deviceCallback = object : ...）bodyOf 解不出，按段落切片
        val callbacks = raw.substringAfter("private fun deviceCallback(")
        assertTrue(
            "onOpened 必须在投递失败时 close（Handler.post 返回 false = looper 已退）",
            callbacks.contains("if (!queued) runCatching { opened.close() }")
        )
        assertTrue(
            "onDisconnected 同样要在投递失败时 close",
            callbacks.contains("if (!queued) runCatching { disconnected.close() }")
        )
        assertTrue(
            "onOpened 在引擎线程内还要再判一次代（投递成功但期间已换代）",
            callbacks.contains("if (gen != generation || !threadRunning)")
        )
        assertTrue(
            "并发重开时过期句柄必须关掉，不得覆盖 device 字段",
            callbacks.contains("if (cur != null && cur !== opened)")
        )
        val errorAt = callbacks.indexOf("override fun onError")
        val closeAt = callbacks.indexOf("runCatching { errored.close() }", errorAt)
        val failAt = callbacks.indexOf("failDevice(status", errorAt)
        assertTrue(
            "onError 必须在投递前关闭设备（不依赖投递是否成功）",
            errorAt >= 0 && closeAt > errorAt && failAt > closeAt
        )
    }

    @Test
    fun `设备回调不得在回调线程直接改引擎状态`() {
        val callbacks = codeOnly(mainSourceText("camera/Camera2Engine.kt")).substringAfter("private fun deviceCallback(")
        val beforeFirstPost = callbacks.substringBefore("workHandler.post")
        assertFalse(
            "回调入口（投递之前）不得直接写 device 字段",
            beforeFirstPost.contains("device = opened")
        )
        assertFalse(
            "回调入口不得直接发布状态",
            beforeFirstPost.contains("publishDevice(")
        )
    }
}
