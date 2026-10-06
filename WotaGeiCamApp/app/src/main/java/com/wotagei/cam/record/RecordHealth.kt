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
 * 【两路判据（各用各的信号，不许混套）】[CodecRecorder] 路（`fps > MR 上限`／光弧修复／内录）
 * 有逐缓冲回调，吃**完整三格**（[healthVerdict]）；[MrRecorder] 路（MediaRecorder，30/60fps
 * 常规档）没有逐缓冲回调（三格恒 false），但它攥着真实输出 fd，可读"文件是否在长大"——吃
 * **产出活性**判据（[streamHealthVerdict]：`Os.fstat(fd).st_size` 是否长到 [MIN_OUTPUT_BYTES]）。
 * 两路各自翻成同一个 [HealthVerdict]，再汇进同一个 [retryActionOf]。
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
 * 常规档（[MrRecorder] 路）的产出活性门槛：一帧都没写时容器里最多只有 ftyp/moov 头（<1KB 量级）；
 * 顶档码率下 64KiB 在数十毫秒内就会写满 ⇒ 到窗口末仍"不足这个量"就是真没在写。
 * **取 64KiB 而非 `>0`**：`>0` 会被"只写了容器头"的假活喂绿——那份文件同样不能播。
 */
const val MIN_OUTPUT_BYTES = 64L * 1024L

/**
 * 产出字节数的**双源交叉核对**（纯函数）：把 fd 直读与 provider 权威读数合成一个"可用信号"。
 *
 * 【为什么要两源】[MrRecorder.health] 走 `Os.fstat(fd).st_size`——便宜、无额外 IPC，但 fd 是
 * MediaStore pending content uri 经 `openAssetFileDescriptor("rw")` 拿到的，Android 11+ 落
 * FUSE/MediaProvider：某些 ROM 的 `st_size` 滞后、或该 provider 给管道式 fd 恒 0，**唯一判据失效
 * 就会把正常录制误杀**。所以只在中招可疑（`fstat` 读数 < [MIN_OUTPUT_BYTES]）时，再花一次便宜 IPC
 * 查 MediaStore `SIZE` 列做交叉核对：一条是 fd 直读、便宜但依赖 FUSE 行为；一条是 provider 权威、
 * 稍贵。**两条都量不到（都 < 0）才算不可观测**（返回 -1L）。
 *
 * @param fstatBytes `Os.fstat` 读数；**-1L = 量不到**（拿不到 fd/抛错），不是 0 字节
 * @param providerBytes MediaStore `SIZE` 读数；**-1L = 量不到**，不是 0 字节
 * @return 可用源里的最大值；两源都 -1 → -1L（不可观测，判定侧退回只认错误码，绝不当 0 判废）
 */
fun resolveOutputBytes(fstatBytes: Long, providerBytes: Long): Long =
    if (fstatBytes < 0L && providerBytes < 0L) -1L else maxOf(fstatBytes, providerBytes)

/**
 * 产出活性判定（[MrRecorder] 路；`bytesWritten` = 当前段输出文件已落盘字节数）。判定序即优先级：
 * 1. [errorCode] 非空 ⇒ 立刻判废（即便字节已足量——引擎已判废的段不能因为"当时看着好"放行）；
 * 2. `bytesWritten < 0` ⇒ **不可观测**（[MrRecorder] 拿不到 fd/真实路径、或 `Os.fstat` 抛错时传
 *    -1L），退回"只认错误码"的既有口径：无错即等（[HealthVerdict.WAIT]）——**绝不许当 0 字节判废**，
 *    那会把"量不到"误杀成"没在写"；
 * 3. 字节已长到 [MIN_OUTPUT_BYTES] ⇒ 健康（文件真在长大，产出成立）；
 * 4. 到/超过长窗 [RecordHealthWindow.fullWindowMs] 仍不足量 ⇒ 判废（整个长窗都没写出足量数据，
 *    不是慢启动而是没在写）；恰好到窗算超窗，不无限等；
 * 5. 否则继续等。
 *
 * 【为何读文件字节而非三格】MediaRecorder 不暴露逐缓冲回调（没有 MediaCodec 那样的
 * dequeueOutputBuffer），三格对它恒 false；但引擎握着的 fd 是真实 OS fd，`Os.fstat(fd).st_size`
 * 能直接量到产出是否落地——不需要新权限、不产生额外 IPC，比查 MediaStore 的 SIZE 便宜得多。
 */
fun streamHealthVerdict(
    bytesWritten: Long,
    errorCode: String?,
    elapsedMs: Long,
    window: RecordHealthWindow
): HealthVerdict {
    if (errorCode != null) return HealthVerdict.UNHEALTHY
    if (bytesWritten < 0L) return HealthVerdict.WAIT
    if (bytesWritten >= MIN_OUTPUT_BYTES) return HealthVerdict.HEALTHY
    if (elapsedMs >= window.fullWindowMs) return HealthVerdict.UNHEALTHY
    return HealthVerdict.WAIT
}

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

/**
 * 停录时"这一段要不要留"的判定（[MrRecorder]/[CodecRecorder] 的 keep 计算共用，纯函数）。
 *
 * 【弃段意图必须压过"看着正常"】起录自检判废后，UI 调 [Recorder.abandonCurrentSegment] 再停录——
 * 此时段形态恰恰是"**一直在录但没写出数据、[RecorderHealth.errorCode] 仍为 null**"（这正是静默零帧
 * 的形态），旧的 `errorCode == null && dur >= minKeep` 判定会把它当正常段 commit 进相册：
 * 近空/不可播的坏片混进成片，且之后 `shouldDiscardAbandonedSink` 因它已进 parts 而不再兜底删。
 * 所以弃段是**显式意图**，不靠停录的 keep 语义去反推。
 *
 * 四个入参的语义（判定即优先级）：
 * - [abandoned] true ⇒ 一律 false（**弃段意图是最高优先**，段本就没有可用内容）；
 * - [errorCode] 非空 ⇒ false（引擎已判废的段不能因"当时看着好"放行）；
 * - [durationMs] >= [minKeepMs] 且无错且未弃 ⇒ true；否则 false（过短按废片删）。
 */
fun segmentKeepDecision(
    abandoned: Boolean,
    errorCode: String?,
    durationMs: Long,
    minKeepMs: Long
): Boolean = !abandoned && errorCode == null && durationMs >= minKeepMs
