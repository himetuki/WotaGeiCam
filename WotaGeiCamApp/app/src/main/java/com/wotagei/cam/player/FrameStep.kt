package com.wotagei.cam.player

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 帧步进的纯帧号算术（从 `PlayerEngine.stepFrame` 拆出，便于 JVM 单测）。
 *
 * 要锁的性质：**连点必须单调前进，来回点不漂**。两个坑：
 * 1. 浮点帧长（29.97fps → 33.3667ms）下，帧首四舍五入到整毫秒会变成 33ms，再按 `floor` 反查帧号
 *    会退回第 0 帧，于是每次前进都算出同一个 33ms、永久卡住。整数帧率 25/50 恰好无余数，
 *    本机自测发现不了，所以这里先按「整毫秒截断」容差把位置吸到最近的帧首再定位。
 * 2. seek 尚未落地期间 `currentPosition` 仍是旧值，连点会互相吞掉，所以由调用方把上一次已发出的
 *    目标当 anchor 传进来。
 */
object FrameStep {

    /**
     * 帧首定位容差（ms）。整毫秒截断本身 <1ms，再留一点呈现抖动余量。
     * 代价：真的停在某一帧末尾 1.5ms 以内时，前进会跳过紧随的那一帧——暂停态下播放器总是
     * 停在帧首，这条在实际路径上碰不到。
     */
    const val SNAP_TOL_MS = 1.5

    /**
     * @param pendingMs 上一次已发出但尚未落地的步进目标；无则传 null
     * @param positionMs 播放器当前位置（仅 [pendingMs] 为 null 时使用）
     * @param frameMs 单帧时长（ms，可为小数）
     * @param durationMs 片长；<=0 表示未知，不做上限钳制
     * @return 新的步进目标（ms，>=0）
     */
    fun next(
        pendingMs: Long?,
        positionMs: Long,
        frameMs: Double,
        fwd: Boolean,
        durationMs: Long
    ): Long {
        val anchor = (pendingMs ?: positionMs.coerceAtLeast(0L)).toDouble()
        if (frameMs <= 0.0) return anchor.toLong().coerceAtLeast(0L)
        val raw = if (fwd) (anchor + SNAP_TOL_MS) / frameMs else (anchor - SNAP_TOL_MS) / frameMs
        val frame = (if (fwd) floor(raw) else ceil(raw)).toLong()
        val targetFrame = (if (fwd) frame + 1L else frame - 1L).coerceAtLeast(0L)
        val targetMs = (targetFrame * frameMs).roundToLong()
        val headroomMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return targetMs.coerceIn(0L, headroomMs)
    }
}
