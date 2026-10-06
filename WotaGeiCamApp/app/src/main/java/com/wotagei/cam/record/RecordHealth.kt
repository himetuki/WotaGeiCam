package com.wotagei.cam.record

/**
 * 起录自检（纯 Kotlin，无 Android 依赖，JVM 可直接测）。
 *
 * 存在的理由（可观测性缺口，非某个 bug）：引擎历来只在**停止/收尾**时判产出是否成立——
 * [CodecRecorder] 在 `runSegment` 结尾判 `videoTrack < 0`、`stop` 时判 `!muxStarted`，
 * [MrRecorder] 只靠 `MEDIA_ERROR` 回调。于是「GPU 路输入面 EGL_BAD_ALLOC → 编码器零帧
 * → 无视频轨 → 整段废掉」这类事故里，用户做完 WOTA 艺动作、**录完才知道**失败。
 * 本模块把「起录后是否真的在产出」这件事抽成可判定的三格：
 * **编码器建轨了吗（videoTrackAdded）／muxer 启动了吗（muxStarted）／首个视频样本写进去了吗
 * （firstVideoSample）**——三格全立才算健康，超窗未立齐即判废，由 UI 侧自动重录。
 *
 * 【窗口口径：只在"确实没起来"时弃段，不对慢启动做惩罚】视频路与音轨路的期限**分开算**
 * （[RecordHealthWindow.videoWindowMs] / [fullWindowMs]）：`CodecRecorder.startMuxerIfReady`
 * 要等环境轨与内录轨都 addTrack、muxer 没 start 前视频样本一律丢弃，因此 `firstVideoSample`
 * 结构上不可能先于 `muxStarted`——三格全真实际等于"视频编码器首输出"与"环境/内录音频首样本"
 * 里**最慢那一路**都到了。若只用一个短窗，4K/高码率冷启动首输出 >1.2s、或部分机型 playback
 * capture 在没有音频在播时不回缓冲，都会把正常启动误判为废、把用户整段表演作废。
 * 所以：**视频路**（`videoTrackAdded` 有没有）吃短窗 [videoWindowMs]，1.2s 内毫无音讯即判废
 * （历史上那次 EGL_BAD_ALLOC 的真实形态）；**含音轨就绪的完整三格**吃长窗 [fullWindowMs]。
 *
 * 【覆盖边界（不许含糊）】本自检的**完整三格判据只覆盖 [CodecRecorder] 路**（`fps > MR 上限`
 * ／光弧修复／内录 三类走它的会话）；[MrRecorder] 路（MediaRecorder，30/60fps 常规档）只做
 * **错误码弱判**（[weakHealthVerdict]），不做产出健全性自检——MediaRecorder 没有逐缓冲回调，
 * 三格恒 false，拿它们套超窗只会把正常录制反复重录。
 */

/**
 * 自检窗口两档预算：视频路的期限（[videoWindowMs]）与含音轨就绪的完整期限（[fullWindowMs]），
 * 以及最多自动重录几次（含首个共 [maxAttempts]+1 次机会）。
 */
data class RecordHealthWindow(
    val videoWindowMs: Long,
    val fullWindowMs: Long,
    val maxAttempts: Int
)

/** 自检判定：等 / 健康 / 判废 */
enum class HealthVerdict { WAIT, HEALTHY, UNHEALTHY }

/** 判废后该做什么：不动作 / 自动重录 / 认输（明确失败） */
enum class RetryAction { NONE, RESTART, GIVE_UP }

/**
 * 视频路期限：编码器建轨正常 <300ms，取 4 倍余量。这段只要求"视频路活着"（有输出格式），
 * **不等 muxer/首样本**——它们要等音轨 addTrack。不做可配置项（用户 2026-10-06 裁决）。
 */
const val VIDEO_WINDOW_MS = 1_200L

/**
 * 完整期限：含音轨就绪（muxer start + 首视频样本）在内的长窗。给音轨多留 1.8s：
 * 部分机型的 playback capture 在没有音频在播时首包来得晚，短窗会误杀整段。
 */
const val FULL_WINDOW_MS = 3_000L

/** 自动重录次数上限：含首个共 3 次机会，全废才明确失败 */
const val MAX_RESTART_ATTEMPTS = 2

/**
 * 三格健康判定。判定顺序即优先级：
 * 1. [errorCode] 非空 ⇒ 立刻判废——**即便三格已全立**（引擎已判废的段不能因为"当时看着好"放行，
 *    例如 muxer 写样本时抛 IllegalStateException 后三格仍可能停在真值上）；
 * 2. 三格全真 ⇒ 健康；
 * 3. **视频路短窗内毫无音讯**（`!videoTrackAdded` 且已过 [RecordHealthWindow.videoWindowMs]）
 *    ⇒ 判废：视频编码器都没建起来 = 历史上那次 EGL_BAD_ALLOC 的真实形态，短窗即抓、不需要等长窗；
 * 4. 已到/超过长窗 [RecordHealthWindow.fullWindowMs] ⇒ 判废（恰好到窗算超窗，不无限等）；
 * 5. 否则继续等。第 3 与第 4 的先后保证"音轨慢"落到第 4 才判废，不因视频窗到点被误杀。
 */
fun healthVerdict(
    firstVideoSample: Boolean,
    muxStarted: Boolean,
    videoTrackAdded: Boolean,
    errorCode: String?,
    elapsedInWindowMs: Long,
    window: RecordHealthWindow
): HealthVerdict {
    if (errorCode != null) return HealthVerdict.UNHEALTHY
    if (videoTrackAdded && muxStarted && firstVideoSample) return HealthVerdict.HEALTHY
    if (!videoTrackAdded && elapsedInWindowMs >= window.videoWindowMs) return HealthVerdict.UNHEALTHY
    if (elapsedInWindowMs >= window.fullWindowMs) return HealthVerdict.UNHEALTHY
    return HealthVerdict.WAIT
}

/**
 * 判废后的动作（自检的"计划→行为"桥：把 [HealthVerdict] 与已用重录次数翻成下一步）：
 * 只有 [HealthVerdict.UNHEALTHY] 才动作，未触顶 [RecordHealthWindow.maxAttempts] 就重录，
 * 触顶即认输。**必须走这段桥**：直接把判定写成 `attempts <= max` 这类边界，会让第 2 次重录
 * （attempts=2）被误放行成无限重试。
 */
fun retryActionOf(attempts: Int, window: RecordHealthWindow, verdict: HealthVerdict): RetryAction = when {
    verdict != HealthVerdict.UNHEALTHY -> RetryAction.NONE
    attempts < window.maxAttempts -> RetryAction.RESTART
    else -> RetryAction.GIVE_UP
}

/**
 * 弱口径判定：只认硬错误码，三格无信号。供 [RecorderHealth.milestonesObservable] = false 的引擎
 * （[MrRecorder]，MediaRecorder 没有逐缓冲回调）使用——**不许拿三格去套**，否则正常录制会在窗口
 * 到点时被误判超窗、反复重录。
 *
 * 【边界必须显式声明】这条弱判等价于「起录瞬间没有硬错误码」，**不是**产出健全性自检：首拍即
 * [HealthVerdict.HEALTHY] 且不再重投，之后 MediaRecorder 若静默零帧也抓不到。完整三格判据只覆盖
 * [CodecRecorder] 路（见文件头 KDoc）；MR 路（30/60fps 常规档）刻意不做（无真机数据，加"文件字节
 * 增长"这类未验证信号误判起来比现状更糟——会让录制反复重来）。这一位不是遗漏，是既定边界。
 */
fun weakHealthVerdict(errorCode: String?): HealthVerdict =
    if (errorCode != null) HealthVerdict.UNHEALTHY else HealthVerdict.HEALTHY

/**
 * 被弃段的**兜底删除闸门**（纯函数）：[abandonSinkKey] 是本次 attempt 首段 sink 的标识
 * （content uri 或真实路径），[producedKeys] 是本会话引擎 `stop()` 已封段并产出的段标识集。
 *
 * 【为什么只能删"未进产物"的那一枚】UI 侧的 `sessionSink` 只在 `beginSession` 赋值一次；
 * 引擎一旦发生**分段轮转**（文件大小触顶换段），首段会被封段 commit（进 `parts`、相册可见），
 * 而 `sessionSink` 仍指向那枚**已 commit 的首段**——此时再无条件 discard 就是把用户已完成的成片
 * 删掉。轮转后当前段的收尾由引擎 `stop()`/`discardCurrent` 自己承担，不需要这条兜底。
 * 自检跨度最长约 9s（单次 attempt 期限 ≤ 长窗 [FULL_WINDOW_MS]＝3s，含首个共 [MAX_RESTART_ATTEMPTS]+1
 * ＝3 次 attempt）；轮转阈值是 `MAX_FILE_BYTES`(3.5GiB)×0.98，顶档码率下需数百秒才触顶——今天
 * 够不到轮转。这里把"自检跨度远小于轮转耗时"这个隐式前提写成显式闸门，避免以后调窗时静默踩雷。
 *
 * @return true = 该 sink 不在已产出段里（未成形/被引擎判废），可以兜底 discard
 */
fun shouldDiscardAbandonedSink(abandonSinkKey: String?, producedKeys: List<String>): Boolean =
    abandonSinkKey != null && abandonSinkKey !in producedKeys
