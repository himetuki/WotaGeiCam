package com.wotagei.cam.camera

/**
 * 相机存活快照（Y07）：**代数 + 单调帧计数必须同代读出**。
 *
 * 帧计数（[Camera2Engine.frameResultCount]）在引擎内单调，但**跨引擎没有可比性**：
 * 替代引擎从 0 起步，"`-1 → 0`（首次采样）"与"`N → 0`（换引擎）"都是计数变化。
 * 看门狗以前只比计数（`cam != camLast`），于是 0 帧的新引擎也算"涨了"，误判活着、
 * 把自愈基准往后推。这里把引擎代一起带出，跨代一律先建基线。
 *
 * [epoch] 由 [CameraController] 发号：每次 `bind` 新建引擎 +1。同一引擎内的会话重建
 * 不换代——`camResultTotal` 本来就不清零，跨会话仍然可比。
 */
data class CameraHealth(
    val epoch: Long,
    /** 这一代引擎收到 capture result 的单调累计 */
    val frameCount: Long,
)

/** 看门狗对一帧健康快照的裁决（见 [CameraWatchClock.observe]）。 */
enum class CameraPulse {
    /** 第一拍或换代：建立新基线。帧数 0 也不代表活着，只是从这一刻起重新计死。 */
    REBASED,

    /** 同代首次见帧：真的出图了，推进基准并允许上屏判据启用。 */
    FIRST_FRAMES,

    /** 同代计数继续上涨：推进基准时刻。 */
    ADVANCED,

    /** 同代计数未动（或引擎不在）：没有进展。 */
    STILL,
}

/**
 * 相机心跳时钟（Y07）：把「引擎代 + 帧计数 + 基准时刻」作为一个整体推进。
 *
 * 看门狗的两种用法：
 * - 相机心跳：`observe(cameraHealth(), now)`，只有 [CameraPulse.ADVANCED]/[CameraPulse.FIRST_FRAMES]
 *   才推进 [grewAtMs]；换代只重设基准、**不算进展**；
 * - 上屏判据：只在 [everSawFrames] 为真时启用（0 帧的新引擎不许触发重挂面）；
 * - 暂停/录制/重建后由调用方 [arm] 一次，把基准推到当下，同样不计进展。
 */
class CameraWatchClock(initialNowMs: Long) {
    private var epoch = -1L
    private var frameCount = -1L

    /** 最近一次算「进展」（换代/首帧/涨帧/显式 [arm]）的时刻 */
    var grewAtMs = initialNowMs
        private set

    /** 本代是否真的见过帧（上屏判据的启用闸） */
    var everSawFrames = false
        private set

    fun observe(health: CameraHealth?, nowMs: Long): CameraPulse {
        if (health == null) {
            // 引擎不在：保留基准时刻让"无引擎"继续计时（到点由调用方重绑），但世间不许再
            // 拿旧代的帧计数去喂上屏判据。
            epoch = -1L
            frameCount = -1L
            everSawFrames = false
            return CameraPulse.STILL
        }
        if (health.epoch != epoch) {
            epoch = health.epoch
            frameCount = health.frameCount
            grewAtMs = nowMs
            everSawFrames = false
            return CameraPulse.REBASED
        }
        if (health.frameCount == frameCount) return CameraPulse.STILL
        frameCount = health.frameCount
        grewAtMs = nowMs
        return if (everSawFrames) {
            CameraPulse.ADVANCED
        } else {
            everSawFrames = true
            CameraPulse.FIRST_FRAMES
        }
    }

    /** 暂停/录制/自愈重建后把基准推到 nowMs（不计进展，不换代）。 */
    fun arm(nowMs: Long) {
        grewAtMs = nowMs
        everSawFrames = false
    }
}