package com.wotagei.cam.ui

import com.wotagei.cam.camera.CameraHealth
import com.wotagei.cam.camera.CameraPulse
import com.wotagei.cam.camera.CameraWatchClock

/**
 * Activity 级预览看门狗的状态机（R04 抽出的可测边界）。
 *
 * 原实现整段内联在 [MainActivity.onRouteForPreviewWatch] 的协程里，录制窗口门控只能靠
 * 源码字符串守卫；这里把「何时推进基准、何时重启/重绑/重挂」收成纯状态机：
 * - 录制窗口（含 PREPARE/换段）期间**只推平计时**，绝不返回任何自愈动作——编码器已拿面，
 *   底下拆会话会把在写的段变成零帧/坏段；
 * - 窗口结束后必须**重新计满** 4s/10s 门槛，不能拿窗口期间累积的死亡时间立刻动手；
 * - 相机健康读数只在需要判活时取（传入 lambda），录制期间不读，保持原实现的低成本路径。
 *
 * 不在这里打日志：JVM 单测要能直接驱动状态机，Android Log 由调用方按返回动作补。
 */
internal sealed interface PreviewWatchTick {
    data object None : PreviewWatchTick
    data object RebindMissingEngine : PreviewWatchTick
    data class RestartPreview(val deadMs: Long) : PreviewWatchTick
    data object RebindStuckEngine : PreviewWatchTick
    data class ReattachDisplay(val tries: Int, val blindMs: Long) : PreviewWatchTick
    data object RebindAfterReattach : PreviewWatchTick
}

internal class PreviewWatchdog(nowMs: Long) {
    private val clock = CameraWatchClock(nowMs)
    private var dispLast = -1L
    private var dispGrowAtMs = nowMs
    private var restarted = false
    private var reattachTries = 0

    /** 后台/录制/重建后把全部基准推到当下（不计进展，不换代） */
    fun hold(nowMs: Long) {
        clock.arm(nowMs)
        dispGrowAtMs = nowMs
        restarted = false
        reattachTries = 0
    }

    fun tick(
        nowMs: Long,
        recording: Boolean,
        health: () -> CameraHealth?,
        displayFrames: () -> Long?
    ): PreviewWatchTick {
        // R04：录制窗口内不读健康、不动会话，只把计时推平。
        if (recording) {
            hold(nowMs)
            return PreviewWatchTick.None
        }
        val snapshot = health()
        val cam = snapshot?.frameCount
        when (clock.observe(snapshot, nowMs)) {
            CameraPulse.REBASED, CameraPulse.FIRST_FRAMES -> {
                dispGrowAtMs = nowMs
                restarted = false
            }
            CameraPulse.ADVANCED -> restarted = false
            CameraPulse.STILL -> Unit
        }
        val disp = displayFrames()
        if (disp != null && disp != dispLast) {
            dispLast = disp
            dispGrowAtMs = nowMs
            reattachTries = 0
        }
        val camDeadMs = nowMs - clock.grewAtMs
        if (camDeadMs >= 4_000L) {
            return when {
                cam == null -> {
                    clock.arm(nowMs)
                    dispGrowAtMs = nowMs
                    PreviewWatchTick.RebindMissingEngine
                }
                !restarted -> {
                    restarted = true
                    dispGrowAtMs = nowMs
                    PreviewWatchTick.RestartPreview(camDeadMs)
                }
                camDeadMs >= 10_000L -> {
                    clock.arm(nowMs)
                    dispGrowAtMs = nowMs
                    restarted = false
                    PreviewWatchTick.RebindStuckEngine
                }
                else -> PreviewWatchTick.None
            }
        }
        // 上屏侧：相机在出帧而屏上不换帧——重启相机没用，只能重挂面
        if (disp != null && clock.everSawFrames && nowMs - dispGrowAtMs >= 2_500L) {
            reattachTries++
            val blindMs = nowMs - dispGrowAtMs
            dispGrowAtMs = nowMs
            return if (reattachTries >= 3) {
                clock.arm(nowMs)
                reattachTries = 0
                PreviewWatchTick.RebindAfterReattach
            } else {
                PreviewWatchTick.ReattachDisplay(reattachTries, blindMs)
            }
        }
        return PreviewWatchTick.None
    }
}
