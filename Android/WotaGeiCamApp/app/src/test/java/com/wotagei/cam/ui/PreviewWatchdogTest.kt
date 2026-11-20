package com.wotagei.cam.ui

import com.wotagei.cam.camera.CameraHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R04 桥测：Activity 级预览看门狗在录制窗口内**绝不**返回重建/重绑动作；
 * 窗口结束后必须重新计满 4s/10s 门槛；引擎缺失与上屏停滞各有自己的恢复动作。
 *
 * 状态机是 [MainActivity.onRouteForPreviewWatch] 抽出的同一条逻辑（PreviewWatchdog），
 * 这里直接驱动它，不在 JVM 里伪造整个 Activity。
 */
class PreviewWatchdogTest {

    private fun tick(
        w: PreviewWatchdog,
        nowMs: Long,
        recording: Boolean,
        epoch: Long = 1L,
        frames: Long? = 0L,
        display: Long? = 0L
    ): PreviewWatchTick = w.tick(
        nowMs,
        recording,
        { frames?.let { CameraHealth(epoch, it) } },
        { display }
    )

    @Test
    fun `录制窗口内二十秒无帧也不许重建引擎`() {
        val w = PreviewWatchdog(0L)
        assertEquals("首拍录制中", PreviewWatchTick.None, tick(w, 0L, recording = true))
        for (t in 1_000L..20_000L step 1_000L) {
            assertEquals(
                "录制窗口内 t=$t 不得返回任何自愈动作（编码器已拿面）",
                PreviewWatchTick.None,
                tick(w, t, recording = true)
            )
        }
    }

    @Test
    fun `录制窗口结束后必须重新计满门槛`() {
        val w = PreviewWatchdog(0L)
        for (t in 0L..9_000L step 1_000L) {
            assertEquals(PreviewWatchTick.None, tick(w, t, recording = true))
        }
        // 停录当拍只建基线，不立刻动手
        assertEquals(PreviewWatchTick.None, tick(w, 10_000L, recording = false))
        assertEquals("窗口期间的死亡时间不得结转", PreviewWatchTick.None, tick(w, 13_000L, recording = false))
        assertEquals(
            "停录后重新计满 4s 才允许重启预览",
            PreviewWatchTick.RestartPreview(4_000L),
            tick(w, 14_000L, recording = false)
        )
        assertEquals("已重启过，10s 门槛未到", PreviewWatchTick.None, tick(w, 18_000L, recording = false))
        assertEquals(
            "重启后仍无帧，10s 到点才整条重建",
            PreviewWatchTick.RebindStuckEngine,
            tick(w, 24_000L, recording = false)
        )
    }

    @Test
    fun `引擎缺失四秒后重新绑定`() {
        val w = PreviewWatchdog(0L)
        assertEquals(PreviewWatchTick.None, tick(w, 0L, recording = false, frames = null))
        assertEquals(PreviewWatchTick.None, tick(w, 3_000L, recording = false, frames = null))
        assertEquals(
            "引擎不在满 4s 必须重绑",
            PreviewWatchTick.RebindMissingEngine,
            tick(w, 4_000L, recording = false, frames = null)
        )
    }

    @Test
    fun `相机出帧但上屏停滞三次重挂后重建引擎`() {
        val w = PreviewWatchdog(0L)
        assertEquals(PreviewWatchTick.None, tick(w, 0L, recording = false, frames = 1L, display = 0L))
        assertEquals(PreviewWatchTick.None, tick(w, 1_000L, recording = false, frames = 2L, display = 0L))
        // 首帧到达时上屏基准从 1s 起算：3s 只差 2s，未到 2.5s
        assertEquals(PreviewWatchTick.None, tick(w, 3_000L, recording = false, frames = 4L, display = 0L))
        assertEquals(
            "上屏 2.5s 不换帧先重挂",
            PreviewWatchTick.ReattachDisplay(1, 3_000L),
            tick(w, 4_000L, recording = false, frames = 6L, display = 0L)
        )
        assertEquals(
            "第二次重挂",
            PreviewWatchTick.ReattachDisplay(2, 3_000L),
            tick(w, 7_000L, recording = false, frames = 9L, display = 0L)
        )
        assertEquals(
            "第三次仍无效才整条重建",
            PreviewWatchTick.RebindAfterReattach,
            tick(w, 10_000L, recording = false, frames = 12L, display = 0L)
        )
    }
}
