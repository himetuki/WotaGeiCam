package com.wotagei.cam.record

import android.net.Uri

/**
 * 补弧的**结果契约**（机器码 / 结果 / 三条终态判据）。与执行器分文件：Y23 把任务所有者
 * 迁到 Activity 宿主后，"终态怎么裁决"由宿主侧的账本（[ArcRepairTasks]）直接消费，
 * 不应再要求它连整条 MediaCodec 管线一起编译。纯函数部分可 JVM 全表单测。
 */
object ArcRepairError {

    /** 源打不开 / 没有视频轨 / 帧数为 0 */
    const val SOURCE = "ARC_SOURCE"

    /** 输出 pending 建不出来或打不开 */
    const val NO_OUTPUT = "ARC_NO_OUTPUT"

    /** 解码器建不出来或创建输出面失败 */
    const val DECODER = "ARC_DECODER"

    /** 编码器建不出来 */
    const val ENCODER = "ARC_ENCODER"

    /** GPU 路线失败（EGL/渲染/等帧超时） */
    const val GPU = "ARC_GPU"

    /** CPU 路线拿不到解码输出的 Image（该解码器只支持 Surface 输出） */
    const val CPU_YUV = "ARC_CPU_YUV"

    /** CPU 路线没法把帧写进编码器输入缓冲（等不到空闲 input buffer / 取不到 Image 或 Buffer） */
    const val CPU_FEED = "ARC_CPU_FEED"

    /** 没等到视频轨 */
    const val NO_VIDEO = "ARC_NO_VIDEO"

    /** 用户取消 */
    const val CANCELLED = "ARC_CANCELLED"

    /** 源实测帧率不高于目标帧率：没有帧可抽，修复无意义（UI 给专门提示，不算故障） */
    const val NO_NEED = "ARC_NO_NEED"

    /** 超时保护触发 */
    const val TIMEOUT = "ARC_TIMEOUT"

    /**
     * 已封口但 IS_PENDING 两次清除都失败：成片字节在盘上、容器也完整，但 MediaStore 行仍处于
     * pending，本应用列表（IS_PENDING=0 门）与系统相册都看不到。不是纯成功，也不是"失败已回收"；
     * UI 必须提示「已保存待发布」，冷启救援会在门槛后把它收进相册。
     */
    const val PUBLISH_PENDING = "ARC_PUBLISH_PENDING"

    /** 其它运行期异常，附 `:异常类名` */
    const val ENGINE = "ARC_ENGINE"
}

/**
 * 修复结果。[uri]/[path]/[bytes] 指向新入库的成片；[error] 非空即失败（此时产物已回收）。
 * [note] 是给用户/日志看的补充口径（例如 CPU 路线的性能与时间戳代价），不是错误。
 */
data class ArcRepairResult(
    val ok: Boolean,
    val uri: Uri?,
    val path: String?,
    val bytes: Long,
    val error: String?,
    val note: String? = null,
    /**
     * 已封口待发布：文件/路径/字节都已就绪（[uri]/[path]/[bytes] 非空），但 `IS_PENDING=0`
     * 两次都没清成功，媒体索引里暂时不可见。此时 [ok] 必须为 false（不是纯成功），
     * UI 按 [error] == [ArcRepairError.PUBLISH_PENDING] 给「已保存待发布」专门文案。
     */
    val pendingPublish: Boolean = false
)

/**
 * 修复成功判据（纯函数，JVM 全表单测；`ArcRepairSession.run` 收尾唯一入口）：
 * muxer 已 start + 视频轨已 addTrack + **至少写出一个视频样本**。
 *
 * 【为什么必须数样本】存储写满时 `writeSampleData` 会逐样本抛错（修复管线把异常吞掉只记日志），
 * 而 moov 在 muxer stop 之前没写出去 ⇒ 成片只剩 ftyp 头；只看前两格会把这份废片判成成功并
 * commit 进相册。零样本整废是必须拦的形态。
 * 【为什么只拦零样本】部分样本失败（可播但缺帧）不判失败：把整段白修的产物改判失败代价不对称；
 * 写失败已有日志与 `ArcRepairSession.writeFailCode`（ENGINE:MUX）留痕，这里只把"一个样本都没
 * 写出去"从成功里剔掉。
 */
fun arcRepairOk(muxStarted: Boolean, videoTrackAdded: Boolean, writtenVideoSamples: Int): Boolean =
    muxStarted && videoTrackAdded && writtenVideoSamples > 0

/**
 * 修复产物"可保留"的最终判据：写样本成功（[ok]）**且** `MediaMuxer.stop()` 正常返回。
 *
 * 为什么 stop 必须单列一格：`stop()` 是写 moov 的步骤；它抛异常时文件通常只有 ftyp 头。
 * 旧 `finish` 把 stop 异常吞成一行日志，随后仍 `seal(keep = ok)` 把坏容器 commit 进相册并报成功
 * ——与 CodecRecorder 把 stop 失败当 `MUX_CLOSE` 独立信号的做法相反。零样本音轨、盘满等都会
 * 让 stop 抛（见 [arcRepairOk] 的零样本说明），这条判据把"容器没封口"从成功里剔出去。
 */
internal fun arcRepairKeep(ok: Boolean, muxStopOk: Boolean): Boolean = ok && muxStopOk

/**
 * 修复产物从「封口」走到「公开/索引」的最终三态（纯函数，JVM 全表）：
 *
 * - [PUBLISHED]：muxer 封口 + `seal` 清 IS_PENDING 命中行 ⇒ 文件、发布、索引三格全成立；
 * - [PENDING_PUBLISH]：muxer 封口但两次 commit 都失败。文件仍在盘上（VideoStore 不删），
 *   只是 pending 行对媒体库不可见。**绝不能报纯成功**，否则用户以为进了相册；
 * - [DISCARDED]：keep=false（容器没封口/零样本），产物已按故意丢弃处理。
 *
 * 旧实现只看 `keep` 就返回 `ArcRepairResult(true, ...)`，把 PENDING_PUBLISH 当成功。
 */
internal enum class ArcPublishOutcome { PUBLISHED, PENDING_PUBLISH, DISCARDED }

internal fun arcPublishOutcome(keep: Boolean, committed: Boolean): ArcPublishOutcome = when {
    !keep -> ArcPublishOutcome.DISCARDED
    committed -> ArcPublishOutcome.PUBLISHED
    else -> ArcPublishOutcome.PENDING_PUBLISH
}

/**
 * 终态归类（纯函数，JVM 全表）：UI 文案只管"这一类给哪句"，判定顺序不再散落在页面里。
 *
 * 顺序即语义：成功 → 用户取消 → 无需抽帧（不是故障）→ 已封口待发布（文件在盘上，只是索引不可见）
 * → 其它失败。旧实现把这段判定直接写在播放页的 `LaunchedEffect(status)` 里，还读执行器上
 * "最后一次状态"与"最后一次结果"两个字段——退页后两份状态都可能不是同一轮的。
 */
internal enum class ArcResultKind { DONE, CANCELLED, NO_NEED, PENDING_PUBLISH, FAILED }

internal fun arcResultKind(result: ArcRepairResult): ArcResultKind = when {
    result.ok -> ArcResultKind.DONE
    result.error == ArcRepairError.CANCELLED -> ArcResultKind.CANCELLED
    result.error == ArcRepairError.NO_NEED -> ArcResultKind.NO_NEED
    result.pendingPublish -> ArcResultKind.PENDING_PUBLISH
    else -> ArcResultKind.FAILED
}