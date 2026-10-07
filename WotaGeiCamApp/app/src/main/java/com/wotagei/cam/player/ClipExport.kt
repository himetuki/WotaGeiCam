package com.wotagei.cam.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import android.util.Log
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.record.ArcDropLog
import com.wotagei.cam.record.ORIENTATION_MUXER_CCW
import com.wotagei.cam.record.exportOrientationHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * 掐头去尾无损剪辑导出（用户 2026-10-04 定版方向 3）。
 *
 * **无损** = 不重编码：`MediaExtractor` 逐样本读源 MP4，`MediaMuxer` 原样写出，只改 PTS。
 * 精度是**视频关键帧**：入点自动向前对齐到最近的 I 帧（解码必须从关键帧起），出点按 PTS 截止。
 * 音画同步靠"两轨同减一个公共偏移"保住——偏移取视频首样本（关键帧）的 PTS，早于它的音频样本
 * 直接丢弃（那段画面本来就多出来了，丢掉对应音频才不会整体错位）。
 *
 * B 帧容错：按 PTS 判"越界"在有 B 帧的流里会提前停（解码序靠后的 B 帧 PTS 反而小），
 * 所以每轨都按"连续越界样本数"封轨（视频 32 / 音频 8，典型 B 帧深度 ≤ 4），界内迟到样本照写。
 *
 * sidecar：源片带 `.drops.json`（本 App 抽帧成片）时，剪辑区间做平移重映射后写进新片同名
 * sidecar。平移基准（关键帧的输出帧序号）不靠从头数样本——抽帧成片的 PTS 恒为
 * "帧序号 × 1e6/dstFps"（ArcKeepRule 的均匀帧序号时间戳定版），所以
 * `shift = (baseUs × dstFps + 5×10⁵) / 10⁶` 闭式反推即可；`(k, n)` 里的 n 是增量
 * （"该帧之前丢了几个源帧"），与全局序号无关，平移后原样保留（见 [ClipRule.remapDrops]）。
 * 外来视频没有 sidecar，这条链路自然不走。
 */
object ClipRule {

    /** 保留段下限：比它短视为误操作（300ms 在 24fps 下连 8 帧都不到，没有导出价值） */
    const val MIN_KEEP_MS = 300L

    /** 剪辑成片子目录（媒体库按 WotaGeiCam 关键字收录，Clip 子目录自然在库内） */
    const val RELATIVE_PATH = "Movies/WotaGeiCam/Clip"

    /**
     * 入出点规范化：起点非负、终点不超片长、终点必须晚于起点、保留段不短于 [MIN_KEEP_MS]。
     * @return (startMs, endMs)；不合法返回 null（调用方给「保留段太短」提示）
     */
    fun normalize(startMs: Long, endMs: Long, durationMs: Long): Pair<Long, Long>? {
        val s = startMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val e = endMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        if (e - s < MIN_KEEP_MS) return null
        return s to e
    }

    /**
     * sidecar 平移重映射：剪辑保留输出帧序号 `shift` 及其之后的部分，位次整体前移 [shift]。
     * n 是增量（"该帧之前丢了几个源帧"），与全局序号无关，平移后原样保留；
     * shift 之前的账目随被剪掉的画面一起丢弃。
     */
    fun remapDrops(log: ArcDropLog, shift: Int): ArcDropLog =
        ArcDropLog(
            mode = log.mode,
            dstFps = log.dstFps,
            drops = log.drops.mapNotNull { (k, n) -> if (k >= shift) (k - shift) to n else null }
        )

    /** 关键帧的输出帧序号：抽帧成片 PTS = 帧序号 × 1e6/dstFps 的闭式反推（四舍五入） */
    fun keyFrameIndexOf(baseUs: Long, dstFps: Int): Int =
        ((baseUs * dstFps + 500_000L) / 1_000_000L).toInt().coerceAtLeast(0)

    /** 剪辑成片命名：`edit_<源名去扩展名>.mp4`（用户 2026-10-04 定版：处理过的片名上前缀可辨；
     *  MediaStore 同名冲突自行追加序号） */
    fun clipNameOf(srcName: String): String {
        val dot = srcName.lastIndexOf('.')
        val stem = if (dot > 0) srcName.substring(0, dot) else srcName
        return "edit_$stem.mp4"
    }
}

/** 导出结果：[Done] 带 MediaStore 媒体 id（pending uri 自带，commit 前已定） */
sealed interface ClipResult {
    data class Done(val mediaId: Long, val name: String) : ClipResult
    data class Fail(val reason: ClipError) : ClipResult
}

enum class ClipError { RANGE, OPEN, MUX, CANCELLED }

/**
 * 执行器（录制管线之外的第二条"读源→写库"链路，入库走与 VideoStore 同一套
 * IS_PENDING 两段式——写完清 0 相册立即可见，失败/取消删记录不留 0 字节幽灵条目）。
 * sidecar 刻意放在 commit 成功**之后**写：成片已落定，sidecar 写失败只少一份补弧档案，
 * 不牵连导出成败；失败路径的 fail() 也就不需要清理 sidecar。
 */
class ClipExporter(private val ctx: Context) {

    companion object {
        private const val TAG = "ClipExport"

        /** 每轨"连续越界即封轨"的容忍样本数：视频要盖过 B 帧重排深度，音频 AAC 帧间无重排 */
        private const val VIDEO_TAIL_TOLERANCE = 32
        private const val AUDIO_TAIL_TOLERANCE = 8

        /** 进度回调节流：样本粒度太频，按已读字节比例变化超 1% 才回一次 */
        private const val PROGRESS_STEP = 0.01f

        /** 样本缓冲初始容量（4K 码率的视频帧可以远超 1MB，不够时按需翻倍） */
        private const val INITIAL_BUF = 1 shl 20
    }

    /**
     * 把 [src] 的 `[startMs, endMs]` 段无损导出成新入库条目。
     * [onProgress] 0..1（IO 线程回调，写 Compose State 是线程安全的）。
     * 取消 = 调用方 cancel 协程；本函数在取消路径上删 pending 记录后原样重抛。
     */
    suspend fun export(
        src: VideoClip,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit
    ): ClipResult = withContext(Dispatchers.IO) {
        val range = ClipRule.normalize(startMs, endMs, src.durationMs)
            ?: return@withContext ClipResult.Fail(ClipError.RANGE)
        val (sMs, eMs) = range
        val pendingUri = insertPending(ClipRule.clipNameOf(src.name))
            ?: return@withContext ClipResult.Fail(ClipError.OPEN)
        // 循环在非 suspend 的 copy() 里跑，取消检查靠把 Job 递进去（coroutineContext 是 suspend 属性，
        // 在 copy() 里直接摸不到）
        val job = coroutineContext[Job]
        try {
            val afd = ctx.contentResolver.openAssetFileDescriptor(pendingUri, "rw")
                ?: return@withContext fail(pendingUri, ClipError.OPEN)
            afd.use {
                val muxer = MediaMuxer(afd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    // 纯 remux 不动像素：源带容器旋转（自录横屏片常带 90/180/270）时方向只能靠
                    // metadata 透传，不写 hint 成片方向错；源 hint=0 时写出 0、行为不变。
                    // 读数走双源（readSourceRotationHint），写出必须经 exportOrientationHint 统一口径
                    val hint = readSourceRotationHint(ctx, src.uri)
                    muxer.setOrientationHint(exportOrientationHint(hint, ORIENTATION_MUXER_CCW))
                    val baseUs = copy(src, sMs * 1000L, eMs * 1000L, job, muxer, onProgress)
                    muxer.stop()
                    commit(pendingUri)
                    // sidecar 平移：成片 PTS = 帧序号 × 1e6/dstFps，闭式反推关键帧位次
                    val srcLog = src.dataPath?.let { ArcDropLog.readFrom(it) }
                    val outPath = queryData(pendingUri)
                    if (srcLog != null && outPath != null) {
                        val shift = ClipRule.keyFrameIndexOf(baseUs, srcLog.dstFps)
                        val ok = ArcDropLog.writeTo(outPath, ClipRule.remapDrops(srcLog, shift))
                        Log.i(TAG, "sidecar remap shift=$shift ok=$ok")
                    }
                    val id = ContentUris.parseId(pendingUri)
                    Log.i(TAG, "done id=$id $sMs..$eMs ms baseUs=$baseUs")
                    ClipResult.Done(id, ClipRule.clipNameOf(src.name))
                } finally {
                    runCatching { muxer.release() }
                }
            }
        } catch (e: CancellationException) {
            fail(pendingUri, ClipError.CANCELLED)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "export failed: ${e.message}")
            fail(pendingUri, ClipError.MUX)
        }
    }

    /**
     * 拷贝主体：从入点关键帧读到出点，样本原样写进 [muxer]，PTS 平移到 0 起。
     * @return 公共偏移基准（入点关键帧的源 PTS，微秒）——sidecar 平移反推要用
     */
    private fun copy(
        src: VideoClip,
        startUs: Long,
        endUs: Long,
        job: Job?,
        muxer: MediaMuxer,
        onProgress: (Float) -> Unit
    ): Long {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, src.uri, null)
            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    videoTrack < 0 && mime.startsWith("video/") -> videoTrack = i
                    audioTrack < 0 && mime.startsWith("audio/") -> audioTrack = i
                }
            }
            if (videoTrack < 0) throw IllegalStateException("no video track")
            ex.selectTrack(videoTrack)

            // 第一步：入点向前对齐关键帧（此阶段只选了视频轨，扫到的都是视频样本）
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var buf = ByteBuffer.allocate(INITIAL_BUF)
            var baseUs = -1L
            while (baseUs < 0) {
                val size = ex.sampleSize // 注意：getSampleSize 返回 long（android-34 javap 实证）
                if (size < 0) throw IllegalStateException("no sync frame before EOF")
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)
                ex.readSampleData(buf, 0)
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) baseUs = ex.sampleTime
                else ex.advance()
            }

            // 第二步：音频此刻才选中并 seek，两轨同位（baseUs）起读，相对关系不破坏
            if (audioTrack >= 0) ex.selectTrack(audioTrack)
            ex.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val vIdx = muxer.addTrack(ex.getTrackFormat(videoTrack))
            val aIdx = if (audioTrack >= 0) muxer.addTrack(ex.getTrackFormat(audioTrack)) else -1
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val srcBytes = src.sizeBytes.coerceAtLeast(1L)
            var readTotal = 0L
            var lastReported = 0f
            var videoWritten = 0
            var vTail = 0
            var aTail = 0
            var videoClosed = false
            var audioClosed = audioTrack < 0
            val endRel = endUs - baseUs
            while (!videoClosed || !audioClosed) {
                // 取消检查挂在每个样本上：这一步很轻，是循环里最密的检查点
                job?.ensureActive()
                val size = ex.sampleSize // getSampleSize 返回 long；写入路径用 int 版
                if (size < 0) break // EOF（尾轨先到头是常态，另一轨继续）
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)
                ex.readSampleData(buf, 0)
                val isVideo = ex.sampleTrackIndex == videoTrack
                val rel = ex.sampleTime - baseUs
                if (isVideo) {
                    if (rel > endRel) {
                        // 连续越界满容忍数才封轨：B 帧重排会让 PTS 在界内界外来回跳
                        if (++vTail >= VIDEO_TAIL_TOLERANCE) videoClosed = true
                    } else {
                        vTail = 0
                        // flags 直传 extractor 的 sampleFlags：SAMPLE_FLAG_SYNC(1) 与
                        // BUFFER_FLAG_SYNC_FRAME(1) 同值同义，Extractor→Muxer 转写的通行做法
                        @Suppress("WrongConstant")
                        info.set(0, size.toInt(), rel, ex.sampleFlags)
                        muxer.writeSampleData(vIdx, buf, info)
                        videoWritten++
                    }
                } else if (!audioClosed) {
                    if (rel > endRel) {
                        if (++aTail >= AUDIO_TAIL_TOLERANCE) audioClosed = true
                    } else if (rel >= 0L) {
                        // 早于公共基准的音频样本（AAC 帧界早于视频关键帧）丢弃：负 PTS 会崩 Muxer
                        @Suppress("WrongConstant") // 同上：sampleFlags 与 BUFFER_FLAG_* 同值体系
                        info.set(0, size.toInt(), rel, ex.sampleFlags)
                        muxer.writeSampleData(aIdx, buf, info)
                    }
                }
                ex.advance()
                readTotal += size
                val frac = (readTotal.toFloat() / srcBytes).coerceIn(0f, 1f)
                if (frac - lastReported >= PROGRESS_STEP) {
                    lastReported = frac
                    onProgress(frac)
                }
            }
            onProgress(1f)
            if (videoWritten == 0) throw IllegalStateException("empty range: no video sample")
            return baseUs
        } finally {
            runCatching { ex.release() }
        }
    }

    // region MediaStore pending 两段式（与 VideoStore 同一模式，目录换成 Clip 子目录）

    private fun insertPending(name: String): android.net.Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, ClipRule.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }
        ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    private fun commit(uri: android.net.Uri) {
        runCatching {
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
        }
    }

    /** pending 记录的真实路径（DATA 列）；sidecar 与它同目录同名 */
    private fun queryData(uri: android.net.Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    /** 失败/取消收尾：删 pending 记录（sidecar 此时尚未写，无需清理） */
    private fun fail(uri: android.net.Uri, reason: ClipError): ClipResult {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
        Log.i(TAG, "failed reason=$reason")
        return ClipResult.Fail(reason)
    }

    // endregion
}
