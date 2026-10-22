package com.wotagei.cam.record

import android.media.AudioDeviceInfo
import kotlin.math.abs

/**
 * 蓝牙内录预校准的纯策略（零状态，JVM 可测）。
 *
 * 偏移语义与 TrackSync/TrackRule 同一套：`offsetMs = 内录内容时刻 − 环境内容时刻`。
 * 预校准只处理「内录早于环境」（Bluetooth A2DP 让现场声晚到）这一侧，所以平移量
 * `−offsetMs` 只在 ≥[MIN_PRE_SHIFT_MS] 时生效；负 offset（内录已晚）留给录制后的自动校准。
 *
 * 20..600ms 的两端不是机型参数，而是**预校准的能力边界**：
 * - <20ms 听感上不可辨，不折腾 PTS，也避免把测量抖动写成偏移；
 * - >600ms 已超出 A2DP 常见缓冲范围，硬钳到 600ms 保留"能纠多少纠多少"，后校准仍会补残差。
 */
object AudioPreflightPolicy {

    /** 预校准最小可辨延迟（ms）：低于它视为 0 */
    const val MIN_PRE_SHIFT_MS = 20

    /** 预校准最大允许平移（ms） */
    const val MAX_PRE_SHIFT_MS = 600

    /** 后校准允许写入的偏移范围（±ms）；超出的只记日志不落盘 */
    const val MAX_AUTO_OFFSET_MS = 1000L

    /** 预校准/后校准共用的置信度门槛（比对比页手动的 1.5 更严：无人值守自动写盘） */
    const val MIN_CONFIDENCE = 2.0

    /**
     * 预校准平移量（µs）：正 = 内录轨整体延后。
     * 低置信度 → 0；`−offsetMs < 20`（内录不早）→ 0；>600 → 钳到 600。
     */
    fun capPreShiftUs(
        offsetMs: Int,
        confidence: Double,
        minConfidence: Double = MIN_CONFIDENCE
    ): Long {
        if (confidence < minConfidence) return 0L
        val delayMs = -offsetMs
        if (delayMs < MIN_PRE_SHIFT_MS) return 0L
        return minOf(delayMs, MAX_PRE_SHIFT_MS).toLong() * 1000L
    }

    /** 只有「环境+内录双轨且输出当前是 A2DP」才值得在起录前多花 800ms */
    fun shouldPreflight(envOn: Boolean, capOn: Boolean, a2dp: Boolean): Boolean =
        envOn && capOn && a2dp

    /** 输出设备类型里是否有蓝牙 A2DP（纯函数桥：AndroidAudioRouteProbe 与测试同吃） */
    fun a2dpFromOutputTypes(types: IntArray): Boolean =
        types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

    /** 后校准可落盘判据：置信度过门槛且偏移在 ±1000ms 内 */
    fun autoOffsetWithinRange(
        offsetMs: Long,
        confidence: Double,
        minConfidence: Double = MIN_CONFIDENCE
    ): Boolean = confidence >= minConfidence && abs(offsetMs) <= MAX_AUTO_OFFSET_MS
}
