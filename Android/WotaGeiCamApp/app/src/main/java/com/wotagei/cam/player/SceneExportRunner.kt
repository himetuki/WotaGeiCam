package com.wotagei.cam.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import com.wotagei.cam.diag.Diag as Log
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.record.ORIENTATION_MUXER_CCW
import com.wotagei.cam.record.monotonicPts
import com.wotagei.cam.record.decoderFormatOf
import com.wotagei.cam.record.exportOrientationHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * 「内置播放器内后渲染导出」的管线（2026-10-28 用户需求）：
 * `MediaExtractor → MediaCodec 解码 → 可选 2D 双边降噪 → 可选 Glow（提取+模糊+合成） → MediaCodec 重编码 → MediaMuxer`。
 *
 * 与无损剪辑（[ClipExport]）的关系：那条是 `Extractor → Muxer` 的原样重封（不重编码）；
 * 这条是**重编码 + 场景层效果**，只应用用户点名的两项：**Glow 与画面降噪**。
 * **RGB 曲线刻意不做**（用户口径：曲线在录制时已烘进成片像素，再套一次是双倍）。
 *
 * 音轨：与 [ClipExport] 同一条选轨口径（ENV=容器第一条音频、CAP=第二条），样本按
 * 「源 PTS − 首个提交帧 PTS」平移后透传（不重编码音频）。
 *
 * 失败/取消与无损导出同一纪律：muxer.stop() 之前的失败删 pending 行；
 * 之后的失败保留行（字节已在盘上，冷启救援能收），不静默。
 */
class SceneExportRunner(private val ctx: Context) {

    sealed interface Result
    data class Done(val id: Long, val name: String) : Result
    data class Failed(val reason: Reason) : Result

    /**
     * [COMMIT] 与 [MUXER] **必须分开**：COMMIT = 成片已封轨、只是没清掉 IS_PENDING（字节在盘上，
     * 保留 pending 行待冷启救援，UI 要报"导出完成但未收录"）；MUXER = 没写成，pending 行已删
     * （UI 报普通失败）。混成一个会让后者对用户撒"重启后自动恢复"的谎。
     */
    // 没有 CANCELLED：取消在 export() 的 catch (CancellationException) 里清场后原样重抛，
    // 永远不会变成 Failed（映射成失败值反而会吞掉协程取消）；别把它加回来
    enum class Reason { RANGE, NO_EFFECT, OPEN, DECODER, ENCODER, GL, MUXER, COMMIT, TIMEOUT }

    suspend fun export(
        src: VideoClip,
        startMs: Long,
        endMs: Long,
        track: SelectedTrack,
        offsetMs: Long,
        effects: SceneExportEffects,
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val range = ClipRule.normalize(startMs, endMs, src.durationMs)
            ?: return@withContext Failed(Reason.RANGE)
        val (sMs, eMs) = range
        val startUs = sMs * 1000L
        val endUs = eMs * 1000L
        // UI 已把无效果时的后渲染 chip 置灰；走到这里说明面板开着期间设置被改了。
        // 单独一枚 reason：报 RANGE 会让用户看到"保留段太短"这种假话
        if (!effects.active) {
            Log.w(TAG, "后渲染被调用但没有可应用的效果（光晕关闭且降噪 OFF），不建空任务")
            return@withContext Failed(Reason.NO_EFFECT)
        }

        val pendingUri = insertPending(ClipRule.clipNameOf(src.name))
            ?: return@withContext Failed(Reason.OPEN)
        val job = coroutineContext[Job]
        var sealed = false
        var submitted = 0L
        var gl: SceneExportGl? = null
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var videoTrack = -1
        var muxerStarted = false
        try {
            val afd = ctx.contentResolver.openAssetFileDescriptor(pendingUri, "rw")
                ?: return@withContext fail(pendingUri, Reason.OPEN)
            afd.use { fd ->
                muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                // 容器方向必须恰好生效一次：解码器喂的是剥过 KEY_ROTATION 的 format（见下
                // dec.configure），像素保持 as-buffered 原样 ⇒ 方向只能靠这里的 hint 补。
                // 读数走双源（retriever 权威 + extractor 兜"部分机型给 0"），与无损/选轨导出
                // 同一枚 readSourceRotationHint；写出经 exportOrientationHint 与录制/修复路同口径
                runCatching {
                    muxer?.setOrientationHint(
                        exportOrientationHint(readSourceRotationHint(ctx, src.uri), ORIENTATION_MUXER_CCW)
                    )
                }

                val ex = MediaExtractor()
                extractor = ex
                ex.setDataSource(ctx, src.uri, null)
                val vIdx = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return@withContext fail(pendingUri, Reason.DECODER)
                val vFormat = ex.getTrackFormat(vIdx)
                val audioIndices = (0 until ex.trackCount).filter {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                // 选轨口径与 ClipExport 同源：ENV = 第一条、CAP = 第二条（不存在则退回 ENV）
                val audioIdx = when {
                    track == SelectedTrack.CAP && audioIndices.size >= 2 -> audioIndices[1]
                    audioIndices.isNotEmpty() -> audioIndices[0]
                    else -> -1
                }
                val audioFormat = if (audioIdx >= 0) ex.getTrackFormat(audioIdx) else null
                val width = vFormat.getInteger(MediaFormat.KEY_WIDTH)
                val height = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
                val srcFps = vFormat.intOr(MediaFormat.KEY_FRAME_RATE, 0)
                val srcBitrate = vFormat.intOr(MediaFormat.KEY_BIT_RATE, 0)

                // ---- 时间基准：入点之后第一个**视频样本**的源 PTS。
                // 必须先扫出来（只读样本头、不解码），因为音轨预扫和「建不建音轨」都发生在
                // muxer.start() 之前，而 start() 一过就再也不能 addTrack
                ex.selectTrack(vIdx)
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (ex.sampleTime in 0 until startUs) {
                    if (!ex.advance()) break
                }
                val basePtsUs = ex.sampleTime
                if (basePtsUs < 0L || basePtsUs >= endUs) return@withContext fail(pendingUri, Reason.RANGE)
                // 内录轨按同步偏移平移（与 ClipExport 同一枚纯函数，别在别处再写一份）
                val shiftUs = TrackRule.shiftUsFor(track, offsetMs)

                // ---- 编码器（surface 输入）
                val encFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, sceneExportBitrate(srcBitrate))
                    setInteger(MediaFormat.KEY_FRAME_RATE, sceneExportFps(srcFps))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder = enc
                enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = enc.createInputSurface()
                enc.start()

                // ---- GL（先建，解码器要它的 Surface）
                val engine = SceneExportGl(width, height, effects)
                gl = engine
                if (!engine.start()) return@withContext fail(pendingUri, Reason.GL)
                if (!engine.attachEncoderSurface(inputSurface)) return@withContext fail(pendingUri, Reason.GL)
                val decoderSurface = engine.decoderOutputSurface()
                    ?: return@withContext fail(pendingUri, Reason.GL)

                val dec = MediaCodec.createDecoderByType(
                    vFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
                )
                decoder = dec
                // 喂解码器的 format 必须剥掉 KEY_ROTATION（与 ArcRepairRunner 同款）：平台把
                // "带旋转的 format + 有输出面"解释成把旋转烘进解码像素，而 muxer 又写了同一个
                // hint ⇒ 双重旋转（竖录/前摄横录导出方向错误）。剥离后像素 as-buffered，
                // 与上面写出的 hint 恰好各生效一次；width/height 原样，90° 源不会被压扁。
                dec.configure(decoderFormatOf(vFormat), decoderSurface, null, 0)
                dec.start()

                // ---- muxer：音轨格式先入，视频轨等编码器回报
                // 选中轨在区间内没有可写样本就不建轨（零样本轨会让 muxer.stop() 抛异常，
                // 而异常路径会删掉整份输出——视频明明完好）。预扫与拷贝同口径见 [audioTrackWritable]
                val audioTrack = if (audioFormat != null && audioIdx >= 0 &&
                    audioTrackWritable(
                        ex, audioIdx, basePtsUs, shiftUs, endUs - basePtsUs,
                        AUDIO_TAIL_TOLERANCE
                    )
                ) {
                    muxer?.addTrack(audioFormat) ?: -1
                } else {
                    if (audioFormat != null) {
                        Log.w(TAG, "选中轨在区间内没有可写样本，本片只导出视频")
                    }
                    -1
                }

                ex.selectTrack(vIdx)
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()
                var inputEos = false
                var decEos = false
                val deadline = SystemClock.elapsedRealtime() +
                    (src.durationMs.coerceAtLeast(eMs) * 4L).coerceAtLeast(60_000L) + 60_000L

                while (!decEos) {
                    if (job?.isActive == false) throw CancellationException()
                    coroutineContext.ensureActive()
                    if (SystemClock.elapsedRealtime() > deadline) return@withContext fail(pendingUri, Reason.TIMEOUT)

                    // 喂解码器
                    if (!inputEos) {
                        val inIdx = dec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val buf = dec.getInputBuffer(inIdx)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    // 取解码帧
                    when (val outIdx = dec.dequeueOutputBuffer(decInfo, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIdx >= 0) {
                            val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val ptsUs = decInfo.presentationTimeUs
                            val submit = !eos && sceneExportShouldSubmit(ptsUs, startUs, endUs)
                            dec.releaseOutputBuffer(outIdx, true)
                            if (!eos) {
                                // 解码器每吐一帧就换一次纹理：区间外的帧也要消费掉，否则 SurfaceTexture
                                // 只会停在最新一帧，后续提交帧的时机会错位
                                if (!engine.awaitFrame()) return@withContext fail(pendingUri, Reason.GL)
                                if (submit) {
                                    if (!engine.acquireAndRender(sceneExportPtsUs(ptsUs, basePtsUs))) {
                                        return@withContext fail(pendingUri, Reason.GL)
                                    }
                                    submitted++
                                    val total = (endUs - basePtsUs).coerceAtLeast(1L)
                                    onProgress(((ptsUs - basePtsUs).toFloat() / total).coerceIn(0f, 0.99f))
                                } else {
                                    engine.acquireOnly()
                                }
                            }
                            if (eos) decEos = true
                        }
                    }
                    // 抽编码器输出
                    while (true) {
                        when (val idx = enc.dequeueOutputBuffer(encInfo, 0L)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> break
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    videoTrack = muxer!!.addTrack(enc.outputFormat)
                                    muxer!!.start()
                                    muxerStarted = true
                                }
                            }
                            else -> {
                                if (idx >= 0) {
                                    val eosEnc = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    val codecConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                    if (!codecConfig && encInfo.size > 0 && muxerStarted) {
                                        val out = enc.getOutputBuffer(idx)!!
                                        out.position(encInfo.offset)
                                        out.limit(encInfo.offset + encInfo.size)
                                        muxer!!.writeSampleData(videoTrack, out, encInfo)
                                    }
                                    enc.releaseOutputBuffer(idx, false)
                                    if (eosEnc) break
                                }
                            }
                        }
                    }
                }

                if (submitted <= 0L || basePtsUs < 0L) return@withContext fail(pendingUri, Reason.RANGE)

                // 视频 EOS：信号 + 抽干
                enc.signalEndOfInputStream()
                var encDone = false
                val drainDeadline = SystemClock.elapsedRealtime() + 30_000L
                while (!encDone && SystemClock.elapsedRealtime() < drainDeadline) {
                    when (val idx = enc.dequeueOutputBuffer(encInfo, 10_000L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoTrack = muxer!!.addTrack(enc.outputFormat)
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }
                        else -> if (idx >= 0) {
                            val eosEnc = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val codecConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!codecConfig && encInfo.size > 0 && muxerStarted) {
                                val out = enc.getOutputBuffer(idx)!!
                                out.position(encInfo.offset)
                                out.limit(encInfo.offset + encInfo.size)
                                muxer!!.writeSampleData(videoTrack, out, encInfo)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (eosEnc) encDone = true
                        }
                    }
                }
                if (!encDone) return@withContext fail(pendingUri, Reason.ENCODER)
                if (!muxerStarted) return@withContext fail(pendingUri, Reason.MUXER)

                // ---- 音轨透传：源 PTS − basePts + 内录偏移（与视频同一时间基准）
                if (audioTrack >= 0) {
                    copyAudio(ex, audioIdx, audioTrack, muxer!!, basePtsUs, shiftUs, endUs, job)
                }
                muxer!!.stop()
                sealed = true
            }

            // ---- 提交（与 ClipExport 同一条 IS_PENDING 口径；commit 全败保留行待冷启救援）
            var committed = runCatching { commit(pendingUri) }.getOrDefault(false)
            if (!committed) committed = runCatching { commit(pendingUri) }.getOrDefault(false)
            if (!committed) {
                Log.w(TAG, "commit 两连败，保留 pending 行待冷启救援 uri=$pendingUri")
                return@withContext Failed(Reason.COMMIT)
            }
            val id = ContentUris.parseId(pendingUri)
            Log.i(TAG, "后渲染导出完成 id=$id ${sMs}..$eMs glow=${effects.glow != null} denoise=${effects.denoise} frames=$submitted")
            Done(id, ClipRule.clipNameOf(src.name))
        } catch (e: CancellationException) {
            if (!sealed) runCatching { ctx.contentResolver.delete(pendingUri, null, null) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "后渲染导出失败：${e.message}", e)
            if (!sealed) runCatching { ctx.contentResolver.delete(pendingUri, null, null) }
            Failed(mapError(e))
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            gl?.release()
            runCatching { extractor?.release() }
        }
    }

    // ---------------------------------------------------------------- 音轨

    /**
     * 音轨样本透传：`输出 PTS = 源 PTS − basePts + shift`（负值丢弃、不 clip 到 0）。
     *
     * 与 [ClipExporter] 的 copy() 音频支**逐条同口径**：
     * - 判据（封轨/丢弃）吃**平移后**的时刻，写出前再过逐轨单调桥 [monotonicPts]
     *   （B 帧源的样本序会在界内外来回跳，同轨倒退会让 MPEG4Writer 抛异常）；
     * - 出点用「连续越界满容忍数才封轨」，与 [audioTrackWritable] 预扫同判据；
     * - 首样本早于 base 的（开放 GOP 前导）丢弃，容忍 [AUDIO_HEAD_TOLERANCE] 枚后判轨结束。
     */
    private fun copyAudio(
        ex: MediaExtractor,
        audioIdx: Int,
        outTrack: Int,
        muxer: MediaMuxer,
        basePtsUs: Long,
        shiftUs: Long,
        endUs: Long,
        job: Job?
    ) {
        // **必须先全部取消选择**：MediaExtractor 的选择是累加的，视频轨在编码循环里还选着，
        // 不撤就会读到音视频交错的样本流——音轨里混进整帧视频（真机实测：成片音轨码率
        // 5.2 Mbps、AAC 帧里夹着 20KB 的巨块）
        for (i in 0 until ex.trackCount) runCatching { ex.unselectTrack(i) }
        ex.selectTrack(audioIdx)
        ex.seekTo(basePtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val endRelUs = endUs - basePtsUs
        val info = MediaCodec.BufferInfo()
        var buf: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_AUDIO_BUF)
        var headDrop = 0
        var tail = 0
        var lastPts = -1L
        while (true) {
            if (job?.isActive == false) break
            val size: Long = ex.sampleSize
            if (size < 0L) break
            val rel = ex.sampleTime - basePtsUs
            val outRel = rel + shiftUs
            if (outRel > endRelUs) {
                if (++tail >= AUDIO_TAIL_TOLERANCE) break
            } else if (ClipRule.keepSample(outRel)) {
                tail = 0
                if (buf.capacity() < size) {
                    buf = ByteBuffer.allocateDirect((size * 2).toInt().coerceAtLeast(INITIAL_AUDIO_BUF))
                }
                buf.clear()
                val n: Int = ex.readSampleData(buf, 0)
                if (n < 0) break
                val fix = monotonicPts(lastPts, outRel)
                lastPts = fix.ptsUs
                @Suppress("WrongConstant") // sampleFlags 与 BUFFER_FLAG_* 同值体系（同 ClipExport）
                info.set(0, n, fix.ptsUs, ex.sampleFlags)
                muxer.writeSampleData(outTrack, buf, info)
            } else {
                tail = 0
                if (++headDrop > AUDIO_HEAD_TOLERANCE) break
            }
            if (!ex.advance()) break
        }
    }

    // ---------------------------------------------------------------- 输出

    private fun insertPending(name: String): android.net.Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ClipRule.RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    private fun commit(uri: android.net.Uri): Boolean = runCatching {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, values, null, null) > 0
    }.getOrDefault(false)

    private fun fail(uri: android.net.Uri, reason: Reason): Failed {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
        return Failed(reason)
    }

    private fun mapError(e: Exception): Reason = when (e) {
        is IllegalStateException -> Reason.MUXER
        else -> Reason.ENCODER
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    companion object {
        private const val TAG = "WotaSceneExport"

        /** 音轨在入点前允许的连续越界样本数（B/GOP 交错时源序会早于 basePts，与 ClipExport 同口径） */
        private const val AUDIO_HEAD_TOLERANCE = 32

        /** 音轨"连续越界即封轨"的容忍样本数（AAC 帧间无重排，与 ClipExport 同一枚值） */
        private const val AUDIO_TAIL_TOLERANCE = 8

        /** 音轨样本缓冲初始容量；单枚 AAC 帧远小于此，不再按需翻倍之外额外开销 */
        private const val INITIAL_AUDIO_BUF = 256 * 1024
    }
}
