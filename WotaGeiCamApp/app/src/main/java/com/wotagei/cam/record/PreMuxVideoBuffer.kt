package com.wotagei.cam.record

/**
 * muxer 启动前的**已编码视频样本缓冲**（2026-10-17 起录补写）。
 *
 * 现状缺陷：muxer 要等视频轨 + 环境轨（双轨时还要内录轨）都就绪才 `start()`；内录样本等待窗
 * 最长 1.5s。这段时间视频编码器已经在出样本，而容器还不能写，旧实现直接
 * `releaseOutputBuffer(idx, false)` 丢掉——丢的是**已经编码好**的帧，纯亏。
 *
 * 本类只做纯 Kotlin 的字节预算与到达顺序维护，不碰 MediaCodec/MediaMuxer，JVM 可全量测：
 * - 按**到达顺序**保存；超 [maxBytes] 时淘汰**最旧**的样本并累计 [dropped]（内存有界）；
 * - 溢出淘汰**对齐关键帧**：淘汰到下一个关键帧为止（该关键帧保留），保证补写段不以 P 帧
 *   开头；若淘汰后一枚关键帧都不剩，则整体清空并计数（P 帧打头的保留段解不出来）；
 * - 单枚样本本身就超上限时直接丢弃（它进来也装不下）；调用方可用 [canAccept] **先判断再复制**，
 *   避免为注定拒收的超大样本做一次瞬时大分配；
 * - [drainArrivalOrder] 按**到达顺序**交出（编码器输出的是访问单元顺序，含 B 帧或 PTS 非单调时
 *   排序会变成解码顺序错乱）并清空；PTS 单调兜底由调用方 `writeSample` 的既有 monotonicPts
 *   负责（与直写路、离线修复路同一条兜底）。
 *
 * 容量基线的量级依据（不是机型数值）：起录窗口受 `CAP_SAMPLE_WAIT_MS=1500ms` 约束；
 * 本工程自动码率最高档 = 产品档位上限 50Mbps ⇒ 1.5s ≈ 9.4MB，关键帧峰值再留约 3 倍余量
 * ⇒ 32MiB 覆盖正常窗口且仍是一个固定、可解释的上界。上限固定而不是按堆比例，是为了让
 * "最多占多少内存"在代码里可一眼读出；设备堆再小也不会因本缓冲超过 32MiB。
 */
internal class PreMuxVideoBuffer(private val maxBytes: Long) {

    /** 一枚已编码视频样本（[bytes] 是复制件，MediaCodec 的输出缓冲在 release 后不可再用） */
    internal class Sample(val ptsUs: Long, val flags: Int, val bytes: ByteArray)

    private val samples = ArrayDeque<Sample>()
    private var bufferedBytes = 0L

    /** 已被淘汰/拒收的样本总数（只增；调用方段尾落账） */
    var dropped = 0L
        private set

    val size: Int get() = samples.size
    val bytes: Long get() = bufferedBytes

    /** 这枚样本进来后能否留在缓冲里（false ⇒ 调用方不必复制字节，直接计入丢失即可） */
    fun canAccept(size: Int): Boolean =
        maxBytes > 0L && size >= 0 && size.toLong() <= maxBytes

    /**
     * 收一枚样本，返回本次新淘汰的枚数（0 = 未溢出）。
     * 淘汰发生在**到达顺序**的最旧端，并对齐关键帧（见类注）；不做 PTS 排序（补写按到达顺序）。
     */
    fun offer(sample: Sample): Long {
        if (!canAccept(sample.bytes.size)) {
            dropped++
            return 1L
        }
        samples.addLast(sample)
        bufferedBytes += sample.bytes.size
        if (bufferedBytes <= maxBytes) return 0L
        var evicted = 0L
        while (bufferedBytes > maxBytes && samples.isNotEmpty()) {
            val old = samples.removeFirst()
            bufferedBytes -= old.bytes.size
            evicted++
        }
        // 保留段不许以 P 帧开头：丢到下一个关键帧为止（关键帧本身保留）。
        // 极端情况下缓冲里一枚关键帧都不剩时，循环会把它们全部丢光——宁可整段清空也不交出
        // 解不出来的 P 帧前缀。
        while (samples.isNotEmpty() && (samples.first().flags and FLAG_KEY_FRAME) == 0) {
            val old = samples.removeFirst()
            bufferedBytes -= old.bytes.size
            evicted++
        }
        dropped += evicted
        return evicted
    }

    /** 按**到达顺序**交出全部样本并清空（不排序；PTS 单调兜底在 writeSample） */
    fun drainArrivalOrder(): List<Sample> {
        if (samples.isEmpty()) return emptyList()
        val out = samples.toList()
        samples.clear()
        bufferedBytes = 0L
        return out
    }

    /** 丢弃全部样本（返回丢弃枚数；异常路径/段结束用，计入调用方的丢失账） */
    fun clear(): Int {
        val n = samples.size
        samples.clear()
        bufferedBytes = 0L
        return n
    }

    private companion object {
        /** `MediaCodec.BUFFER_FLAG_KEY_FRAME` 的字面值（纯 Kotlin 类不引 android.*，JVM 直接可测） */
        const val FLAG_KEY_FRAME = 1
    }
}
