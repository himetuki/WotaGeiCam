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
 * 选轨：容器音轨序（识别口径见 [TrackSync] 头注——双音轨段第一条=环境、第二条=内录）。
 */
enum class SelectedTrack { ENV, CAP }

/**
 * 选轨导出的**纯函数**（JVM 单测钉口径，见 TrackExportTest）。
 *
 * 【负 PTS 语义裁决：丢弃前导，不 clip 到 0】MediaMuxer 的 MP4 封轨要求每轨 PTS 单调不减
 * 且 ≥0——把负 PTS 样本钳到 0 会造成同刻多样本，真机 muxer 直接抛异常；丢弃与 ClipExporter
 * "早于公共基准的音频样本丢弃"是同一既有口径，前导丢失时长 = 偏移量，属对齐的物理代价。
 */
object TrackRule {

    /** 选轨成片子目录（媒体库按 WotaGeiCam 关键字收录，Inau 子目录自然在库内） */
    const val RELATIVE_PATH = "Movies/WotaGeiCam/Inau"

    /**
     * 平移量（µs）：选环境轨=0（环境轨本就是视频时间轴的参照）；选内录轨=−offset
     * （[TrackSync.Result.offsetMs] 正=内录晚响，导出把它提前 offset 毫秒对齐环境轨）。
     */
    fun shiftUsFor(track: SelectedTrack, offsetMs: Long): Long =
        if (track == SelectedTrack.CAP) -offsetMs * 1000L else 0L

    /**
     * 平移后的输出 PTS（µs）；早于 0（负 PTS，MP4 不收）返回 null = 丢弃该前导样本。
     * [baseUs] 是视频首样本 PTS（输出 0 点），与 ClipExporter 的公共偏移基准同义。
     */
    fun shiftedPts(srcPtsUs: Long, baseUs: Long, shiftUs: Long): Long? {
        val v = srcPtsUs - baseUs + shiftUs
        return if (v < 0L) null else v
    }

    /** 选轨成片命名：`inau_<源名去扩展名>.mp4`（用户裁决前缀，Internal Audio 可辨） */
    fun nameOf(srcName: String): String {
        val dot = srcName.lastIndexOf('.')
        val stem = if (dot > 0) srcName.substring(0, dot) else srcName
        return "inau_$stem.mp4"
    }
}

/**
 * 选轨导出执行器（批 4）：**全片**导出 = 视频 + 选中的**单**音轨（另一条不写入），不重编码。
 *
 * 与 ClipExporter 同源技法照抄口径：PTS 平移到 0 起、B 帧容忍（全片导出无出点截断，
 * 封轨只看 EOF，容忍数实际用不上但循环结构保持一致）、IS_PENDING 两段式入库、
 * 取消删 pending。ClipExporter 本体零改动（剪辑导出的回归红线）。
 *
 * 复用 [ClipResult]：Done 带 mediaId/name；选轨缺失（如 cap-only 段选内录）归 MUX 失败——
 * 三控件在单音轨片上根本不显示，这条路径只是防御性兜底。
 */
class TrackExporter(private val ctx: Context) {

    companion object {
        private const val TAG = "TrackExport"

        /** 样本缓冲初始容量（同 ClipExporter：4K 帧可远超 1MB，不够按需翻倍） */
        private const val INITIAL_BUF = 1 shl 20

        /** 进度回调节流：按已读字节比例变化超 1% 才回一次 */
        private const val PROGRESS_STEP = 0.01f
    }

    /**
     * 全片选轨导出。[offsetMs] 用 [TrackSync.cachedOffsetMs] 的持久化值（未同步传 0）。
     * [onProgress] 0..1（IO 线程回调，写 Compose State 是线程安全的）。
     */
    suspend fun export(
        src: VideoClip,
        track: SelectedTrack,
        offsetMs: Long,
        onProgress: (Float) -> Unit
    ): ClipResult = withContext(Dispatchers.IO) {
        val pendingUri = insertPending(TrackRule.nameOf(src.name))
            ?: return@withContext ClipResult.Fail(ClipError.OPEN)
        val job = coroutineContext[Job]
        try {
            val afd = ctx.contentResolver.openAssetFileDescriptor(pendingUri, "rw")
                ?: return@withContext fail(pendingUri, ClipError.OPEN)
            afd.use {
                val muxer = MediaMuxer(afd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    // 纯 remux 不动像素：源带容器旋转时方向只能靠 metadata 透传，不写 hint 成片方向错；
                    // 源 hint=0 时写出 0、行为不变。读数走双源（readSourceRotationHint），
                    // 写出必须经 exportOrientationHint 统一口径（与 ClipExporter 同一套）
                    val hint = readSourceRotationHint(ctx, src.uri)
                    muxer.setOrientationHint(exportOrientationHint(hint, ORIENTATION_MUXER_CCW))
                    copy(src, track, TrackRule.shiftUsFor(track, offsetMs), job, muxer, onProgress)
                    muxer.stop()
                    commit(pendingUri)
                    val id = ContentUris.parseId(pendingUri)
                    Log.i(TAG, "done id=$id track=$track offsetMs=$offsetMs")
                    ClipResult.Done(id, TrackRule.nameOf(src.name))
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

    /** 全片拷贝：视频 + 选中单音轨，读到底为止（无出点截断，B 帧容忍不需判界） */
    private fun copy(
        src: VideoClip,
        track: SelectedTrack,
        shiftUs: Long,
        job: Job?,
        muxer: MediaMuxer,
        onProgress: (Float) -> Unit
    ) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, src.uri, null)
            var videoTrack = -1
            val audioTracks = ArrayList<Int>()
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    videoTrack < 0 && mime.startsWith("video/") -> videoTrack = i
                    mime.startsWith("audio/") -> audioTracks.add(i)
                }
            }
            if (videoTrack < 0) throw IllegalStateException("no video track")
            // 音轨序即识别口径：ENV=第一条、CAP=第二条；选轨缺失=源与预期不符（防御性兜底）
            val audioOrdinal = if (track == SelectedTrack.ENV) 0 else 1
            val audioTrack = audioTracks.getOrNull(audioOrdinal)
                ?: throw IllegalStateException("audio track #$audioOrdinal missing (have ${audioTracks.size})")
            ex.selectTrack(videoTrack)

            // 输出 0 点 = 视频首样本（关键帧）：全片导出从 0 读起，首个样本即同步帧
            ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var buf = ByteBuffer.allocate(INITIAL_BUF)
            var baseUs = -1L
            while (baseUs < 0) {
                val size = ex.sampleSize
                if (size < 0) throw IllegalStateException("no sync frame at start")
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)
                ex.readSampleData(buf, 0)
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) baseUs = ex.sampleTime
                else ex.advance()
            }

            if (audioTrack >= 0) ex.selectTrack(audioTrack)
            val vIdx = muxer.addTrack(ex.getTrackFormat(videoTrack))
            val aIdx = muxer.addTrack(ex.getTrackFormat(audioTrack))
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val srcBytes = src.sizeBytes.coerceAtLeast(1L)
            var readTotal = 0L
            var lastReported = 0f
            var videoWritten = 0
            var audioWritten = 0
            while (true) {
                // 取消检查挂在每个样本上（与 ClipExporter 同一检查密度）
                job?.ensureActive()
                val size = ex.sampleSize
                if (size < 0) break // 两轨都到底
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)
                ex.readSampleData(buf, 0)
                if (ex.sampleTrackIndex == videoTrack) {
                    val rel = ex.sampleTime - baseUs // baseUs 是首样本：rel 恒 ≥ 0
                    @Suppress("WrongConstant") // sampleFlags 与 BUFFER_FLAG_* 同值体系（同 ClipExporter）
                    info.set(0, size.toInt(), rel, ex.sampleFlags)
                    muxer.writeSampleData(vIdx, buf, info)
                    videoWritten++
                } else {
                    // 选内录轨：PTS 平移 −offset 对齐环境轨；负 PTS 前导丢弃（TrackRule 裁决）
                    val outPts = TrackRule.shiftedPts(ex.sampleTime, baseUs, shiftUs)
                    if (outPts != null) {
                        @Suppress("WrongConstant")
                        info.set(0, size.toInt(), outPts, ex.sampleFlags)
                        muxer.writeSampleData(aIdx, buf, info)
                        audioWritten++
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
            if (videoWritten == 0) throw IllegalStateException("empty source: no video sample")
            Log.i(TAG, "video=$videoWritten audio=$audioWritten shiftUs=$shiftUs")
        } finally {
            runCatching { ex.release() }
        }
    }

    // region MediaStore pending 两段式（与 ClipExporter 同一模式，目录换成 Inau 子目录）

    private fun insertPending(name: String): android.net.Uri? {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, TrackRule.RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            }
            ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
    }

    private fun commit(uri: android.net.Uri) {
        runCatching {
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
        }
    }

    /** 失败/取消收尾：删 pending 记录，不留 0 字节幽灵条目 */
    private fun fail(uri: android.net.Uri, reason: ClipError): ClipResult {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
        Log.i(TAG, "failed reason=$reason")
        return ClipResult.Fail(reason)
    }

    // endregion
}
