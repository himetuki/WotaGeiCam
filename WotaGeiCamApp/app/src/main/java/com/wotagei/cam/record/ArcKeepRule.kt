package com.wotagei.cam.record

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
 * 丢弃帧跳过 swap 即可，两套口径天然一致。
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
