package com.wotagei.cam.record

/**
 * 源帧率**实测**（帧距中位数，纯函数 JVM 可测）：强制 24/25fps 档的容器 `KEY_FRAME_RATE`
 * 会标成目标值而传感器实际跑更高帧率，按容器值造抽帧计划会得出"无需抽帧"的假结论。
 * 中位数对重复/空样本免疫（帧距≈0 被过滤），这是"45 个有效包被数成 124 个样本"那次
 * 遍历教训的正确解法：不数个数，量间隔。
 */
object ArcRateProbe {

    /** 有效帧距样本少于这个数（极短视频）时中位数不可信，退回容器帧率 */
    const val MIN_DELTAS = 8

    /** 实测帧率上限（防畸变帧距把值算飞；产品最高档 240，留一倍余量） */
    const val MAX_MEASURED_FPS = 480

    /**
     * 由**原始**相邻样本帧距（微秒）测源帧率：先滤噪（<1ms 的重复样本、>1s 的轨道断层），
     * 再取中位数折算 fps（四舍五入）；有效样本不足退回 [fallbackFps]。
     */
    fun measuredFps(rawDeltasUs: List<Int>, fallbackFps: Int): Int {
        val clean = rawDeltasUs.filter { it in 1_001..1_000_000 }
        if (clean.size < MIN_DELTAS) return fallbackFps
        val median = clean.sorted()[clean.size / 2].toLong()
        return (((1_000_000L + median / 2L) / median).toInt()).coerceIn(1, MAX_MEASURED_FPS)
    }
}

/**
 * 录制在线路的**抽帧保留规则**（用户 2026-10-03 定版，2026-10-03 夜实现）。
 *
 * 录制侧没有"先知帧率"：传感器在无精确档范围里实际跑多快由 HAL 决定，所以这里不用
 * `⌊j·S/D⌋` 那套离线计划，改用**应到时刻**判定——输出第 k 帧的应到时刻 = 锚点 + k·帧间隔，
 * 相机帧到达时刻（SurfaceTexture 时间戳，单调）不早于它才保留为第 k 帧，否则丢弃。
 * 规则自追平（停滞后来帧不从落后账里连发）、自纠偏（传感器快慢漂移直接反映到丢弃分布），
 * 长期输出节奏收敛到恰为 D fps。
 *
 * 输出 PTS 不走本类：GL 编码支路的均匀帧序号时间戳（帧序号 × 1e9/fps）只在保留帧上推进，
 * 丢弃帧跳过 swap 即可，两套口径天然一致。源帧率实测见 [ArcRateProbe]。
 */
class ArcKeepRule(private val slotNs: Long) {
    /** 下一输出帧的应到时刻；[NOT_STARTED] = 还没见到首帧 */
    private var nextDueNs = NOT_STARTED

    /** 自上一保留帧以来累计丢弃数（供被抽帧位次 sidecar 记账） */
    private var pendingDrops = 0

    /**
     * 相机帧到达（[tsNs] = SurfaceTexture 时间戳）。
     * @return true = 保留为本输出帧（调用方推进输出帧序号）
     */
    fun onFrame(tsNs: Long): Boolean {
        if (slotNs <= 0L) return true
        if (nextDueNs == NOT_STARTED) {
            // 首帧保留并锚定节奏：下一帧的应到时刻 = 首帧时刻 + 帧间隔
            nextDueNs = tsNs + slotNs
            return true
        }
        return if (tsNs >= nextDueNs) {
            // 注意：保留帧**不**清 pendingDrops——"自上一保留帧以来丢了几个"要在发出那一帧时
            // 才被记账方 takeDrops() 取走，这里清了会把区间账抹成 0
            nextDueNs += slotNs
            if (nextDueNs <= tsNs) nextDueNs = tsNs + slotNs   // 停滞追平，不连发
            true
        } else {
            pendingDrops++
            false
        }
    }

    /** 取走"最近一次保留之前丢弃了几帧"（Emit 记账用；取走即清零） */
    fun takeDrops(): Int {
        val n = pendingDrops
        pendingDrops = 0
        return n
    }

    /** 重置（每次录制开始/编码面重挂时调用） */
    fun reset() {
        nextDueNs = NOT_STARTED
        pendingDrops = 0
    }

    private companion object {
        val NOT_STARTED = Long.MIN_VALUE
    }
}
