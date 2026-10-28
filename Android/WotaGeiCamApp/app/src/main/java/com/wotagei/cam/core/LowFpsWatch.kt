package com.wotagei.cam.core

/**
 * 帧率坍塌提示的负载（引擎写进参数总线、HUD 警告行读）：目标档位 + 实测整数值。
 * [achievedFps] 只做展示粒度（四舍五入到整数），判定永远用浮点原值。
 */
data class LowFpsHint(val targetFps: Int, val achievedFps: Int)

/**
 * 运行时帧率坍塌守卫（2026-10-28 用户6/7 反馈；纯逻辑，JVM 全测）。
 *
 * ## 为什么必须有
 * 帧率档位决议只保证**下发值合法**（机器声明过才发），保证不了**实际到帧率**：
 * - 变量档设备（WIKO：24fps 决议到 `[5,30]`）暗场下 AE 可以合法掉到 5fps；
 * - 固定档设备（华为 ALN-AL00：`[24,24]`）HAL 也会在曝光吃紧时把**结果侧**范围放宽成
 *   `[15,24]`（2026-10-28 用户6 日志 3A 遥测实证）；
 * - vivo r03 时代更是整段录在 4.98fps 上（成片容器 25fps）。
 * 三种塌法用户看到的都是同一件事——「录出来很卡」——而 App 此前**全程零提示**，
 * 只有停录后的帧账日志才见分晓。按「一切自动降级必提示」的既定口径，这里给出
 * 运行时提示的**判定逻辑**；采样与发布在引擎侧（[com.wotagei.cam.camera.Camera2Engine]）。
 *
 * ## 判定口径
 * - **宽限**：首次采样后 [GRACE_MS] 内不判低——起录预热/暗场切换的暂态不算账；
 * - **持续**：连续 [SUSTAIN_SAMPLES] 拍（≈1s 一拍）实测 < 目标×[LOW_RATIO] 才触发，
 *   单拍抖动（对焦搜索、瞬间遮挡）不触发；
 * - **滞回**：触发后要实测 ≥ 目标×[RECOVER_RATIO] 才解除——恢复带内不翻转，提示不闪烁；
 * - **换档即重置**：目标档位变了，旧账（宽限起点/连续计数/触发态）全部作废。
 *
 * 线程约定：所有方法只允许在**同一个**串行执行器上调用（引擎侧即相机回调线程），
 * 内部字段不加锁——跨线程发布由调用方经参数总线（StateFlow）完成。
 */
class LowFpsWatch(targetFps: Int) {

    /** 当前判据档位；换档必须走 [reset]，别直接改 */
    var target: Int = targetFps
        private set

    private var firstSampleAtMs = NEVER
    private var lowRun = 0
    private var active = false

    /**
     * 喂一拍实测帧率，返回**本拍之后**提示应当处于开还是关。
     * 只在状态翻转到开时返回 true 的去抖由调用方做（StateFlow 同值不去重才需要）；
     * 这里返回的是**当前应有的状态**，不是边沿。
     */
    fun onSample(nowMs: Long, measuredFps: Float): Boolean {
        if (target <= 0 || measuredFps < 0f) return active
        if (firstSampleAtMs == NEVER) firstSampleAtMs = nowMs
        if (active) {
            // 滞回：恢复阈值高于触发阈值，恢复带内维持原状（提示不闪烁）
            if (measuredFps >= target * RECOVER_RATIO) {
                active = false
                lowRun = 0
            }
        } else {
            if (nowMs - firstSampleAtMs < GRACE_MS) return false
            lowRun = if (measuredFps < target * LOW_RATIO) lowRun + 1 else 0
            if (lowRun >= SUSTAIN_SAMPLES) active = true
        }
        return active
    }

    /** 换档/会话重建：目标、宽限起点、连续计数、触发态全部作废 */
    fun reset(newTarget: Int) {
        target = newTarget
        firstSampleAtMs = NEVER
        lowRun = 0
        active = false
    }

    /** 引擎侧判断手里这枚守卫是否还配当前档位，不配就换新的 */
    fun targets(fps: Int): Boolean = target == fps

    companion object {
        /** 首拍之后的判低宽限：起录预热与转场暂态不算账 */
        const val GRACE_MS = 4_000L

        /** 判低线 = 目标×0.7：24fps 档实测跌破 ~17 才算塌（23.75 这种正常抖动不告警） */
        const val LOW_RATIO = 0.7f

        /** 解除线 = 目标×0.85：高于触发线，滞回防提示在阈值附近闪烁 */
        const val RECOVER_RATIO = 0.85f

        /** 持续拍数：1s 一拍 × 4 ≈ 连续 4 秒低帧才提示 */
        const val SUSTAIN_SAMPLES = 4

        private const val NEVER = Long.MIN_VALUE
    }
}
