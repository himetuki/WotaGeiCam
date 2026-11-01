package com.wotagei.cam.core

/**
 * 等号档曝光纠偏（2026-11-01 用户定版；方案 docs/plan/31 三稿）。
 *
 * 地基事实：Camera2 合同只保证 FRAME_DURATION ≥ EXPOSURE_TIME，「曝光 == 帧周期」（1/24@24、
 * 1/25@25 这两对满窗档）落在合同边界域——曝光与读出重叠时 HAL 拉不拉长帧周期是自由裁量，
 * 拉了帧率就掉档。传感器的读出（"搬运"）时间不在任何公开 Camera2 键里，跨品牌读取接口建表
 * 违反「能力协商而非能力假设」⇒ **不问相机要参数，测相机对请求的反应**。
 *
 * 三条铁律（谁改这块谁先读）：
 * 1. **发送侧永不发等号值**：纠偏只动 `SENSOR_EXPOSURE_TIME` 这一个键——AE 状态、
 *    `SENSOR_FRAME_DURATION`（节奏真值）、总线档位全部不动；
 * 2. **总线保真**：纠偏是发送侧行为，UI 档位名、滑杆、读数不动（`clampShutterNs` 本体不改，
 *    `Camera2Engine.clampShutterToFrame` 的总线回写依赖精确档纳秒做 `isRequiredShutterNs` 判据）；
 * 3. **探测只在预览态**：探测发等号请求，录制目标在场必须立刻退出探测态（等号不进录制）。
 */
object ExposureCorrection {

    /** 手动微调滑杆域：-8ms ~ +8ms，0.5ms 步进（正值 = 曝光更短更稳，负值 = 趋向满窗） */
    const val MANUAL_MIN_NS = -8_000_000L
    const val MANUAL_MAX_NS = 8_000_000L
    const val MANUAL_STEP_NS = 500_000L

    /** 自动余量下限 = 帧周期 × 5%（守约机的保守余量；Δ 缺失/为 0 时的取值） */
    const val AUTO_FLOOR_PERCENT = 5L

    /** 自动余量上限 = 帧周期 × 20%（实测 Δ 顶到上限 ⇒ 该机该帧率物理上装不下满窗） */
    const val AUTO_CAP_PERCENT = 20L

    /** 实测 Δ 之外再加的安全系数（1ms）：中位数会低估尾部，留一刀余量 */
    const val AUTO_SAFETY_NS = 1_000_000L

    /** 探测样本数（帧间隔数）：24fps × 32 ≈ 1.3s 窗口 */
    const val PROBE_SAMPLES = 32

    /** 探测超时（帧流不足时放弃本轮）与冷却（防死循环重试） */
    const val PROBE_TIMEOUT_MS = 5_000L
    const val PROBE_COOLDOWN_MS = 5_000L

    /** 手动滑杆值夹进 [-8ms, +8ms]（步进对齐在 UI 层做） */
    fun clampManualNs(v: Long): Long = v.coerceIn(MANUAL_MIN_NS, MANUAL_MAX_NS)

    /** 帧周期：1e9 / 档位帧率（与 [RequestApplier.frameDurationNs] 同一条算式，core 侧副本） */
    fun framePeriodNs(fps: Int): Long = WotaTiers.NS_PER_SECOND / fps.coerceAtLeast(1)

    /**
     * 等号档判定：**强制快门档**（1/24、1/25）且快门 ≥ 帧周期。
     * 25fps 选 1/24（41.67ms 被钳到 40ms == 帧周期）同样命中；1/25@24（40 < 41.67）不命中——
     * 那对组合本来就不满窗，读出有余量，不需要纠偏。非强制档（如 1/30@30）不命中：UI 开关
     * 只在强制档露出，行为与入口同域。
     */
    fun applicable(shutterNs: Long, fps: Int): Boolean =
        WotaTiers.isRequiredShutterNs(shutterNs) && shutterNs >= framePeriodNs(fps)

    /**
     * 自动余量：实测 Δ + 1ms 安全系数，夹进 [5%, 20%] 帧周期。
     * Δ 缺失（null = 没测到/键不匹配）或为 0（守约机）⇒ 落在下限 5%。
     * Δ 是**绝对量**（读出 ≈ 扫描耗时，不随帧率等比变）：换帧率后按新帧周期重新夹界，
     * 不做百分比外推。
     */
    fun autoMarginNs(deltaNs: Long?, perFrameNs: Long): Long {
        val floor = perFrameNs * AUTO_FLOOR_PERCENT / 100
        val cap = perFrameNs * AUTO_CAP_PERCENT / 100
        return ((deltaNs ?: 0L).coerceAtLeast(0L) + AUTO_SAFETY_NS).coerceIn(floor, cap)
    }

    /**
     * 手动滑杆值即纠偏值：正值 = 从满窗再缩短（更稳）；负值 = 趋向满窗（更亮）。
     * 负值在等号档会被物理上限（帧周期）截断——曝光不能超过帧周期，这是合同不是设计。
     */
    fun manualMarginNs(sliderNs: Long): Long = clampManualNs(sliderNs)

    /**
     * 发送曝光 = 钳制后曝光 − 纠偏值，夹进 [1ns, 帧周期]。
     * 上限封死「曝光越窗」（负值纠偏饱和在满窗 = 满窗直发）；下限防 0/负值。
     */
    fun correctedExposureNs(clampedNs: Long, marginNs: Long, perFrameNs: Long): Long =
        (clampedNs - marginNs).coerceIn(1L, perFrameNs)

    /**
     * 探测折算：帧间隔**中位数** − 请求周期 = 拉长量 Δ。
     * 中位数抗突发抖动（编码器启停那一两拍的长间隔不该污染结论）；偶数个取中间两数平均；
     * Δ 为负（本机跑得比请求还快）归 0——「没拉长」不是「负拉长」。
     */
    fun probeDeltaNs(intervalsNs: List<Long>, periodNs: Long): Long {
        if (intervalsNs.isEmpty()) return 0L
        val sorted = intervalsNs.sorted()
        val mid = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
        return (mid - periodNs).coerceAtLeast(0L)
    }
}
