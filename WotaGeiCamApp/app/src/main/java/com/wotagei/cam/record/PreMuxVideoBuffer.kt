package com.wotagei.cam.record

/**
 * muxer 启动前的**已编码视频样本缓冲**（2026-10-17 起录补写）。
 *
 * 现状缺陷：muxer 要等视频轨 + 环境轨（双轨时还要内录轨）都就绪才 `start()`；内录样本等待窗
 * 最长 1.5s。这段时间视频编码器已经在出样本，而容器还不能写，旧实现直接
 * `releaseOutputBuffer(idx, false)` 丢掉——丢的是**已经编码好**的帧，纯亏。
 *
 * 本类只做纯 Kotlin 的字节预算与排序，不碰 MediaCodec/MediaMuxer，JVM 可全量测：
 * - 按**到达顺序**保存；超 [maxBytes] 时淘汰**最旧**的样本并累计 [dropped]（内存有界）；
 * - 单枚样本本身就超上限时直接丢弃（它进来也装不下，留着只会把整条缓冲清空）；
 * - [drainPtsOrdered] 按 PTS 升序交出（稳定排序）并清空：视频编码器正常按 PTS 出样本，
 *   但"乱序仍按单调写出"这条必须由本类保证，而不是靠上游时序假设。
 *
 * 容量基线的量级依据（不是机型数值）：起录窗口受 `CAP_SAMPLE_WAIT_MS=1500ms` 约束；
 * 本工程自动码率最高档 4K/60 为 64Mbps ⇒ 1.5s ≈ 12MB，关键帧峰值再留一倍余量 ⇒ 32MiB
 * 覆盖正常窗口且仍是一个固定、可解释的上界。上限固定而不是按堆比例，是为了让"最多占多少内存"
 * 在代码里可一眼读出；设备堆再小也不会因本缓冲超过 32MiB。
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

    /**
     * 收一枚样本，返回本次新淘汰的枚数（0 = 未溢出）。
     * 淘汰发生在**到达顺序**的最旧端；不做 PTS 排序（排序只在补写时做，保证接收路径 O(1) 摊销）。
     */
    fun offer(sample: Sample): Long {
        if (maxBytes <= 0L || sample.bytes.size.toLong() > maxBytes) {
            dropped++
            return 1L
        }
        samples.addLast(sample)
        bufferedBytes += sample.bytes.size
        var evicted = 0L
        while (bufferedBytes > maxBytes && samples.isNotEmpty()) {
            val old = samples.removeFirst()
            bufferedBytes -= old.bytes.size
            evicted++
        }
        dropped += evicted
        return evicted
    }

    /** 按 PTS 升序交出全部样本并清空（同 PTS 保持到达顺序，稳定排序） */
    fun drainPtsOrdered(): List<Sample> {
        if (samples.isEmpty()) return emptyList()
        val out = samples.toMutableList()
        samples.clear()
        bufferedBytes = 0L
        out.sortBy { it.ptsUs }
        return out
    }

    /** 丢弃全部样本（返回丢弃枚数；异常路径/段结束用，计入调用方的丢失账） */
    fun clear(): Int {
        val n = samples.size
        samples.clear()
        bufferedBytes = 0L
        return n
    }
}
