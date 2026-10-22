package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库写操作协程兜底（[com.wotagei.cam.media.MediaOps.launchOp]）的结构守卫。
 *
 * 背景（缺陷 4）：`launchOp` 曾是裸 `scope.launch { dispatch(block()) }`——scope 是
 * `SupervisorJob() + Dispatchers.Main.immediate`（Supervisor 不改未捕获即崩），而操作体内
 * Room `insert`、`contentResolver.update/delete` 只在个别点捕 SecurityException：盘满时点
 * 收藏/加 tag/改名 ⇒ `SQLiteFullException` 等从协程冒出无人接 ⇒ 进程崩溃。
 * 钉住：必须 `runCatching` 兜底 + 失败派发 `MediaOp.Message`（横幅）+ 记日志；
 * `CancellationException` 必须原样抛回（离开屏幕的正常取消不是失败，不许弹"操作失败"）。
 * 突变：把 launchOp 改回裸 `dispatch(block())` → 本用例必红。
 */
class MediaOpLaunchGuardTest {

    @Test
    fun `launchOp 必须 runCatching 兜底并派发失败文案`() {
        val src = codeOnly(mainSourceText("media/MediaActions.kt"))
        val body = bodyOf(src, "launchOp")
        assertTrue(
            "必须 runCatching 兜底（盘满时 SQLiteFullException 从协程冒出就是进程崩溃）",
            body.contains("runCatching")
        )
        assertTrue(
            "失败必须派发 MediaOp.Message（横幅文案出口）",
            body.contains("MediaOp.Message(")
        )
        assertTrue(
            "取消不是失败：CancellationException 必须原样抛回（正常退出屏幕不许弹失败横幅）",
            body.contains("CancellationException")
        )
        assertTrue("失败必须记日志（Log.w）", body.contains("Log.w("))
        assertTrue(
            "失败文案必须是 media_op_failed",
            mainSourceText("media/MediaActions.kt").contains("R.string.media_op_failed")
        )
    }

    /** 既有出口语义不被破坏：dispatch 仍把 Message 转给 onMessage（幂等，Done 静默） */
    @Test
    fun `dispatch 出口语义仍在`() {
        val src = codeOnly(mainSourceText("media/MediaActions.kt"))
        val body = bodyOf(src, "dispatch")
        assertTrue("Message 必须仍走 onMessage", body.contains("onMessage"))
        assertFalse("消息出口不许被兜底改动挪走", body.contains("media_op_failed"))
    }
}
