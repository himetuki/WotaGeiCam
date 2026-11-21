package com.wotagei.cam.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import com.wotagei.cam.diag.Diag as Log
import com.wotagei.cam.record.monotonicPts
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer

/**
 * 后渲染导出的**音频车道**（Y30）：与视频按平移后 PTS **交错**写出，取代"先把整段视频写到 EOS、
 * 最后再整段透传音频"的单向流水。
 *
 * 为什么要交错 + 上界（量化见审查卡 Y30）：先视频后音频时，muxer/原生侧要为两轨的时间轴对齐
 * 保留在途样本；60 秒 40–60Mb/s 的压缩视频可达 300–450MB。0.5 秒超前窗口的参考上界只是
 * 2.5–3.75MB（4K）/0.5–1MB（1080p），所以**超前时长**是真正需要设上界的那一维：
 * - [LEAD_US]：音频最多超前视频 0.5 秒；窗口外的样本留在 extractor 里**不读进内存**
 *   （等待视频追上，而不是先读进来排队 → 本类"等待而非积压"的语义）；
 * - [MAX_SAMPLE_BYTES]：单枚样本字节上限，覆盖 VBR 峰值与单个大「关键帧式」AAC 包；
 *   超过说明流异常，显式失败（随后 pending 由 owner 回收），绝不按样本大小无限扩容。
 *
 * 对齐口径与 [ClipExporter] 逐条同源（这里只搬实现，不另立规则）：
 * - 判据吃**平移后**的时刻 `outRel = 源PTS − basePts + shift`；负值丢弃、不 clip 到 0；
 * - 出点用「连续越界满 [AUDIO_TAIL_TOLERANCE] 枚才封口」，与预扫 [audioTrackWritable] 同判据；
 * - 写出前过逐轨单调桥 [monotonicPts]（同轨倒退会让 MPEG4Writer 抛异常）。
 */
internal class SceneAudioLane(
    private val ex: MediaExtractor,
    private val audioIdx: Int,
    private val muxer: MediaMuxer,
    private val basePtsUs: Long,
    private val shiftUs: Long,
    private val endUs: Long,
    private val job: Job?
) {
    companion object {
        /** 音频超前视频的最长时长（Y30 的 0.5s 参考窗口） */
        const val LEAD_US = 500_000L

        /** 单枚样本字节上限：4MiB 能盖住 4K VBR 峰值与任意单个 AAC 包，再大属流异常 */
        const val MAX_SAMPLE_BYTES = 4L * 1024 * 1024

        /** 音轨"连续越界即封口"的容忍样本数（AAC 帧间无重排，与 ClipExport 同一枚值） */
        const val AUDIO_TAIL_TOLERANCE = 8

        private const val INITIAL_BUF = 256 * 1024

        /** 窗口外 = 下一枚样本还没到写出时刻（等待视频追上，不读入内存） */
        private const val WAITING = 0

        /** 已处理一枚（已写或按判据丢弃），游标已推进 */
        private const val ADVANCED = 1

        /** 音轨已封口（EOF 或连续越界满容忍数） */
        private const val CLOSED = 2
    }

    private val endRelUs = endUs - basePtsUs
    private val info = MediaCodec.BufferInfo()
    private var buf: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_BUF)
    private var lastPts = -1L
    private var tail = 0
    private var outTrack = -1

    var written = 0
        private set
    var ptsFixes = 0
        private set
    var maxBackUs = 0L
        private set

    /**
     * 占用音轨：**必须先全部取消选择**。MediaExtractor 的选择是累加的，主提取器在编码循环里
     * 还选着视频轨；不撤就会读到音视频交错的样本流——音轨里混进整帧视频（真机实测：成片音轨
     * 码率 5.2 Mbps、AAC 帧里夹着 20KB 巨块）。这里用独立提取器 + 先撤全部选择，双保险。
     *
     * Y30 顺序：`prepare` 只做撤选/选轨/seek，**成功后才允许 `addTrack`**（见 [arm]）。
     * 若 seek/select 抛异常，调用方不得先建音轨——否则 muxer 会留下零样本轨，`stop()` 抛异常
     * 并把本来完好的视频一起删掉。
     */
    fun prepare(seekUs: Long) {
        for (i in 0 until ex.trackCount) runCatching { ex.unselectTrack(i) }
        ex.selectTrack(audioIdx)
        ex.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** prepare 成功后占用 muxer 音轨号；调用前不得先 `addTrack`。 */
    fun arm(track: Int) {
        outTrack = track
    }

    /** 兼容既有直调测试：先 prepare 再 arm（生产路径拆成两步以钉住顺序）。 */
    fun arm(seekUs: Long, track: Int) {
        prepare(seekUs)
        arm(track)
    }

    /**
     * 只写平移后 PTS ≤ [limitUs] 的样本：视频每提交一帧就调一次（`limit = 该帧 PTS + LEAD_US`）。
     * 返回 false 表示音轨已封口（无需再调）；返回 true 表示还有窗口外样本等视频追上。
     */
    fun drainUpTo(limitUs: Long): Boolean = drain(limitUs) != CLOSED

    /**
     * 尾部排空：视频已 EOS，剩余在出点内的音频一次写完（窗口不再有意义）。
     * 收口时落一条段尾汇总（B06：逐样本明细在 d 档，汇总只此一条 i 级）。
     */
    fun drainAll() {
        drain(Long.MAX_VALUE)
        if (written > 0 || ptsFixes > 0) {
            Log.i(TAG, "音轨写入=$written pts修正=$ptsFixes(back=${maxBackUs}us)")
        }
    }

    private fun drain(limitUs: Long): Int {
        while (true) {
            // 取消检查挂在每枚样本上：与主循环同一枚 owner Job，取消后下一枚即退出
            job?.ensureActive()
            val size: Long = ex.sampleSize
            if (size < 0L) return CLOSED
            val outRel = ex.sampleTime - basePtsUs + shiftUs
            val next = when {
                outRel > endRelUs -> {
                    // 出点外：连续满容忍数才封口（与预扫同判据），否则丢弃继续
                    if (++tail >= AUDIO_TAIL_TOLERANCE) return CLOSED
                    ADVANCED
                }
                else -> {
                    tail = 0
                    // 窗口外的样本不读入内存：等视频 PTS 追上来再写（Y30 的核心上界）
                    if (outRel > limitUs) return WAITING
                    if (ClipRule.keepSample(outRel)) writeSample(size, outRel)
                    ADVANCED
                }
            }
            if (next == ADVANCED && !ex.advance()) return CLOSED
        }
    }

    private fun writeSample(size: Long, outRel: Long) {
        if (size > MAX_SAMPLE_BYTES) {
            throw IllegalStateException("音频样本 ${size}B 超过单样本上限 ${MAX_SAMPLE_BYTES}B")
        }
        if (buf.capacity() < size) buf = ByteBuffer.allocateDirect(size.toInt())
        buf.clear()
        val n: Int = ex.readSampleData(buf, 0)
        if (n < 0) throw IllegalStateException("音频 readSampleData 返回负值")
        val fix = monotonicPts(lastPts, outRel)
        if (fix.advanced) {
            ptsFixes++
            if (fix.backUs > maxBackUs) maxBackUs = fix.backUs
            // B06：逐样本明细只留首条（d 档），次数与最大倒退量由 drainAll 的段尾汇总给出
            if (ptsFixes == 1) Log.d(TAG, "音轨 pts 修正 cur=$outRel → ${fix.ptsUs}（back=${fix.backUs}us；后续只计数）")
        }
        lastPts = fix.ptsUs
        @Suppress("WrongConstant") // sampleFlags 与 BUFFER_FLAG_* 同值体系（同 ClipExport）
        info.set(0, n, fix.ptsUs, ex.sampleFlags)
        val t0 = if (ExportPerfProbe.enabled) System.nanoTime() else 0L
        try {
            muxer.writeSampleData(outTrack, buf, info)
        } finally {
            if (t0 != 0L) ExportPerfProbe.onWrite(System.nanoTime() - t0)
        }
        written++
    }
}

private const val TAG = "WotaSceneExport"