package com.wotagei.cam.record

/**
 * 补弧任务的**所有权/取消门**（纯 Kotlin，JVM 全表）。
 *
 * 旧实现把 `busy` 检查放在 `handler.post { … }` **之后**：两次 `start()` 会先后入队，
 * 第一条跑完后第二条才看见 `busy=false`，于是同一批「连点两下」真的跑两轮；而 `stopAsync()`
 * 只置一枚进程级 `cancelRequested`，排队中的 `start` 在消息内又把它复位，取消会被下一次启动
 * 吞掉。这里把「领任务」前移到调用线程：**投递之前**就要拿到 token，拿不到直接忽略；
 * 取消按 token 记账，只影响发起它的那一轮，不会串到下一轮。
 *
 * 线程安全靠 `@Synchronized`（调用来自主线程 / 后台线程，临界区只有几行）。
 */
internal class ArcRepairTaskGate {

    private var nextToken = 0L
    private var currentToken = 0L

    /**
     * 取消记在**当前轮**上（0 = 未取消）。旧实现用 `HashSet<Long>` 记账：
     * 任务结束时如果取消比收尾更晚到，条目会永久留在集合里（长时间使用会攒下无界集合），
     * 而"取消一个已结束的 token"本来就不该有任何效力——取消只对**持有所有权的那一轮**有效。
     */
    private var cancelToken = 0L

    /** 领取本轮所有权；已有任务在跑返回 null（调用方必须原样忽略，不排队） */
    @Synchronized
    fun begin(): Long? {
        if (currentToken != 0L) return null
        nextToken += 1L
        currentToken = nextToken
        cancelToken = 0L
        return currentToken
    }

    /** 请求取消某一轮（非阻塞）：只接受当前轮的 token，迟到的取消碰不到新一轮 */
    @Synchronized
    fun cancel(token: Long) {
        if (token != 0L && token == currentToken) cancelToken = token
    }

    @Synchronized
    fun isCancelled(token: Long): Boolean = token != 0L && token == cancelToken

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == currentToken

    /** 是否仍有任务持有所有权（收尾时判"空闲即可退线程"） */
    @Synchronized
    fun hasTask(): Boolean = currentToken != 0L

    /** 本轮收尾：只归还自己持有的所有权（迟到的旧 token 不得清掉新一轮） */
    @Synchronized
    fun end(token: Long) {
        if (currentToken == token) {
            cancelToken = 0L
            currentToken = 0L
        }
    }
}

/**
 * 补弧的阶段（进度按阶段呈现，封口前绝不显示 100%）。
 *
 * 旧 `countOutput` 直接写 `已出帧数/总帧数`，编码器刚交完最后一帧就落到 1.0，而 muxer 还要
 * stop（写 moov）、清 IS_PENDING、刷新索引——用户在那段时间看到 100% 会以为已经完成。
 */
internal enum class ArcProgressStage { PREPARE, PROCESS, FINALIZE, PUBLISHED }

/**
 * 阶段化进度（纯函数，JVM 全表）：[PROCESS] 最多 0.95，[FINALIZE] 固定 0.98，只有
 * [PUBLISHED] 才是 1.0。任何「封口/发布还没完成」的路径都到不了 1。
 */
internal fun arcRepairProgress(stage: ArcProgressStage, doneFrames: Int, totalFrames: Int): Float =
    when (stage) {
        ArcProgressStage.PREPARE -> 0.02f
        ArcProgressStage.PROCESS -> 0.02f + 0.93f * ArcRepairPlan.progressOf(doneFrames, totalFrames)
        ArcProgressStage.FINALIZE -> 0.98f
        ArcProgressStage.PUBLISHED -> 1f
    }

/**
 * 取消/截止检查的唯一出口（纯函数，JVM 全表）：取消优先于超时（用户意图先于兜底）。
 * @return null = 可继续；否则是 [ArcRepairError] 里的机器码
 */
internal fun arcAbortReason(cancelled: Boolean, nowMs: Long, deadlineMs: Long): String? = when {
    cancelled -> ArcRepairError.CANCELLED
    nowMs > deadlineMs -> ArcRepairError.TIMEOUT
    else -> null
}

/**
 * 音频 lane 的**可写判据**：拷贝与预扫共用这一个函数、同一个时基。
 *
 * 旧实现的预扫用 `basePtsUs - shiftUs`（base = 视频首样本 PTS），而实际拷贝用
 * `normalize(sample, decodePtsBase) + shiftUs`；`decodePtsBase`（首帧显示 PTS）可能**大于**
 * 首样本 PTS ⇒ 预扫比拷贝宽松，判定"有可写样本"并建轨，实际写入 0 枚，muxer.stop 因零样本
 * 轨失败、整份修复产物被回收。现在两侧都只认本函数，且预扫必须用与拷贝相同的 base。
 *
 * @return ≥ 0 = 可写且输出 PTS；< 0 = 不可写（视频起点之前，或平移后仍为负）
 */
internal fun arcAudioOutPtsUs(samplePtsUs: Long, ptsBaseUs: Long, shiftUs: Long): Long {
    val norm = samplePtsUs - ptsBaseUs
    if (norm < 0L) return -1L
    val out = norm + shiftUs
    return if (out < 0L) -1L else out
}

/** 序列上「至少一枚可写样本」的纯判据（测试用桩序列直接驱动，与 [arcAudioOutPtsUs] 同口径） */
internal fun arcAudioHasWritable(samplePtsUs: List<Long>, ptsBaseUs: Long, shiftUs: Long): Boolean =
    samplePtsUs.any { arcAudioOutPtsUs(it, ptsBaseUs, shiftUs) >= 0L }

/**
 * 编码器输出缓冲「该不该当样本写进 muxer」的纯判据（Y24 真值表，JVM 可全测）。
 *
 * 只看**有无负载**与**是否配置帧**，判断里**不得出现 EOS**：平台会把末帧与
 * `BUFFER_FLAG_END_OF_STREAM` 合在同一枚输出缓冲上，拿 `!eos` 当写条件会把这一枚末帧
 * 直接 release——成片比抽帧计划少一帧、尾部补弧被削，容器时长与音频尾巴对不上。
 * 配置帧（csd）已随 `outputFormat` 进 addTrack，不能再当样本写；零长度 EOS 没有负载。
 *
 * @return true = 先写这枚样本，再交接 EOS
 */
internal fun arcEncSampleWritable(sizeBytes: Int, codecConfig: Boolean): Boolean =
    sizeBytes > 0 && !codecConfig

/**
 * 运行收尾的**成功/失败裁决**（纯函数，JVM 全表）。
 *
 * [ok] 是既有三格判据（muxer 启动 + 视频轨 + 至少一枚样本）；[encodeFail] 是编码器排空期
 * 异常/末次超时留下的失败码（Y24：旧实现置 `videoDone=true` 后照样返回成功，末帧被吞也报完成）。
 * 任一派空失败 ⇒ 结果不纯成功，失败码优先归因编码器，其次写样本失败，最后才落到兜底码。
 */
internal data class ArcRunVerdict(val ok: Boolean, val code: String?)

internal fun arcRunVerdict(ok: Boolean, encodeFail: String?, writeFail: String?, fallback: String): ArcRunVerdict =
    if (ok && encodeFail == null) {
        ArcRunVerdict(true, null)
    } else {
        ArcRunVerdict(false, encodeFail ?: writeFail ?: fallback)
    }