package com.wotagei.cam.record

import com.wotagei.cam.core.WotaTiers
import kotlin.math.abs
import kotlin.math.ln

/**
 * 三段式码率策略：**用户自设 > 自动表 > 公式兜底**，HEVC 再整体 ×0.9。
 *
 * 数值来源是产品档位而非机型能力，所以这里的分辨率分界、系数都是常量；
 * 需求给的 5/10/20/35/50 Mbps 六档在 `WotaTiers.BITRATES`，UI 只从那里取候选值。
 */
object BitratePolicy {

    /** 音频码率固定 128 kbps（`` 参数表） */
    const val AUDIO_BITRATE = 128_000

    /** 自动表（``：720P→8M、1080P→16M、4K→32M） */
    private const val AUTO_720P = 8_000_000
    private const val AUTO_1080P = 16_000_000
    private const val AUTO_4K = 32_000_000

    /** 自动表的分界像素：720P/1080P 之间取两者像素数的**算术**中间值（不是几何），高于 4K 档上界即无表值 → 交给兜底公式 */
    private const val PX_720P = 1280L * 720L
    private const val PX_1080P = 1920L * 1080L
    private const val PX_4K = 3840L * 2160L
    private const val BAND_1080_UPPER = 5_000_000L
    private const val BAND_4K_UPPER = 9_000_000L

    /** fps ≥ 60 时全档翻倍（``） */
    private const val DOUBLE_FPS = 60

    /** 兜底公式 `w × h × 30 × 0.2`（`` 第 3 段） */
    private const val FALLBACK_FPS = 30
    private const val FALLBACK_BPP = 0.2

    /** HEVC / HDR 同画质省 10%（`` 微调项） */
    private const val HEVC_FACTOR = 0.9f

    /** 兜底下限：低于 1 Mbps 编码器画质会退化到不可用，用于钳住极小分辨率 */
    private const val MIN_BITRATE = 1_000_000

    /**
     * 自动表命中值；返回 0 表示分辨率超出表的上界（例如 8K、超宽画幅），调用方改用 [fallback]。
     * 注意 1440P（约 3.69M 像素）是**落在表内**的，按 1080P 档给 16M。
     */
    fun autoTable(width: Int, height: Int, fps: Int): Int {
        val px = width.toLong() * height.toLong()
        val base = when {
            px <= (PX_720P + PX_1080P) / 2 -> AUTO_720P
            px <= BAND_1080_UPPER -> AUTO_1080P
            px <= BAND_4K_UPPER -> AUTO_4K
            else -> 0
        }
        return if (base == 0) 0 else if (fps >= DOUBLE_FPS) base * 2 else base
    }

    /** 兜底公式：`w × h × 30 × 0.2`（Long 计算防 8K 溢出，再钳回 Int） */
    fun fallback(width: Int, height: Int): Int {
        val v = width.toLong() * height.toLong() * FALLBACK_FPS * (FALLBACK_BPP * 100).toLong() / 100L
        return v.coerceIn(MIN_BITRATE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 三段式取值：[userBitrate] > 0 直接用；否则自动表；自动表无档位再用兜底公式。
     * HEVC 时整体 ×0.9（`` 微调项，对三段来源统一生效）。
     *
     * 自动/兜底两段被产品最高档（`WotaTiers.BITRATES` 末项）钳住：8K 的公式值是 199 Mbps，
     * 超出手机编码器实际能力，prepare 会被拒；用户自设档一律尊重不钳。
     */
    fun resolve(userBitrate: Int, width: Int, height: Int, fps: Int, hevc: Boolean): Int {
        val fromUser = userBitrate > 0
        val auto = autoTable(width, height, fps)
        val raw = when {
            fromUser -> userBitrate
            auto > 0 -> auto
            else -> fallback(width, height)
        }
        val capped = if (fromUser) raw else raw.coerceAtMost(maxTier())
        val scaled = if (hevc) (capped * HEVC_FACTOR).toInt() else capped
        return scaled.coerceAtLeast(MIN_BITRATE)
    }

    /** 产品码率档位上限（档位表为空时退化为不钳制） */
    fun maxTier(): Int = WotaTiers.BITRATES.maxOrNull() ?: Int.MAX_VALUE

    /** 同一个 profile 的便捷入口：[RecordProfile.bitrate] ≤ 0 即「未自设」 */
    fun resolve(p: RecordProfile): Int = resolve(p.bitrate, p.width, p.height, p.fps, p.hevc)

    /**
     * 把任意码率吸附到最近的 `WotaTiers.BITRATES` 档位（5/10/20/35/50 Mbps）。
     * 用对数距离而非算术距离：码率档位是等比分布的，4K/60fps 的 64M 也不会错配到 5M。
     */
    fun nearestTier(bitrate: Int): Int {
        if (WotaTiers.BITRATES.isEmpty()) return bitrate
        return WotaTiers.BITRATES.minByOrNull { abs(ln(it.toDouble()) - ln(bitrate.toDouble())) } ?: bitrate
    }

    /**
     * UI 默认推荐档：按分辨率/帧率给出应高亮的产品档位（用户可再改）。
     * 用户已自设且合法时，直接回落到用户档位的最近合法档。
     */
    fun recommendTier(userBitrate: Int, width: Int, height: Int, fps: Int, hevc: Boolean): Int =
        nearestTier(resolve(userBitrate, width, height, fps, hevc))
}

/**
 * 「还能拍多久」的估算（顶栏容量胶囊用）。
 *
 * 只按视频码率折算，不含音频（约 128kbps，对分钟数影响 <1%）；
 * 结果是**上界**：分段开销与 Moov 原子没算进来，所以文案一律带「约」。
 */
object StorageEstimate {

    /** 可用容量（MB）与视频码率（bps）→ 可拍分钟数，向下取整；码率非法时返回 0 */
    fun minutesOf(freeMb: Long, bitrateBps: Int): Long {
        if (freeMb <= 0L || bitrateBps <= 0) return 0L
        return freeMb * 1024L * 1024L * 8L / bitrateBps / 60L
    }

    /** 分钟数 → 读数：不足一小时给「N 分钟」，过了一小时给「h.h 小时」，超过 100 小时只报「>100 小时」 */
    fun humanize(minutes: Long): String = when {
        minutes <= 0L -> "0"
        minutes >= 6_000L -> "100+"
        minutes >= 60L -> String.format(java.util.Locale.US, "%.1f", minutes / 60f)
        else -> minutes.toString()
    }

    /** [humanize] 用的是小时还是分钟：决定文案单位 */
    fun unitOf(minutes: Long): Boolean = minutes >= 60L
}
