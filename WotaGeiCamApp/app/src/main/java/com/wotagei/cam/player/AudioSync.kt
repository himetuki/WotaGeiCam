package com.wotagei.cam.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.wotagei.cam.R
import com.wotagei.cam.core.AudioAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 基于音频的自动同步（05 文档第 7 节定版：能量包络互相关 + FFT 精修，纯 Kotlin，无第三方库）。
 *
 * 1. MediaExtractor + MediaCodec 解两条音轨 → 单声道 8kHz PCM（只取前 30s，够对齐）；
 *    每轨以**自身首样本的 presentationTimeUs** 归零，其后按 PTS 落位（空洞补静音），
 *    所以 PCM 下标即「该轨自身时间轴毫秒」，两条轨各自的起始时间戳不会污染互相关结果。
 * 2. 10ms 窗 RMS → 归一化包络（100 点/秒）；
 * 3. ±10s 搜索窗内互相关求粗偏移；
 * 4. 以粗偏移为中心，1kHz 带内 FFT 相关在 ±50ms 内以 1ms 步进精修；
 * 5. 置信度 = 峰 / 次峰（次峰取主峰 ±150ms 之外的最大值），< 1.5 提示手动校正。
 *
 * 偏移符号约定（与对比页一致）：**rightPos = leftPos + offsetMs**，即左视频第 0 毫秒对应右视频的 offsetMs。
 */
object AudioSync {

    data class Result(
        val ok: Boolean,
        val offsetMs: Long = 0L,
        val confidence: Float = 0f,
        val lowConfidence: Boolean = false,
        val reasonRes: Int? = null
    )

    /** 目标采样率：TrackSync 判"至少 1 秒 PCM"同口径复用，故 internal 单一真源 */
    internal const val TARGET_SR = AudioAlign.TARGET_SR
    private const val MAX_SECONDS = 30
    /** 低置信度门槛（主峰/次峰比）：TrackSync 同口径复用，故 internal 单一真源 */
    internal const val MIN_CONFIDENCE = AudioAlign.MIN_CONFIDENCE

    suspend fun align(context: Context, left: Uri, right: Uri): Result = withContext(Dispatchers.Default) {
        val pcmL = decodeMono(context, left) ?: return@withContext Result(false, reasonRes = R.string.player_sync_no_audio)
        val pcmR = decodeMono(context, right) ?: return@withContext Result(false, reasonRes = R.string.player_sync_no_audio)
        if (pcmL.size < TARGET_SR || pcmR.size < TARGET_SR) {
            return@withContext Result(false, reasonRes = R.string.player_sync_too_short)
        }

        val envL = envelope(pcmL)
        val envR = envelope(pcmR)
        val coarseMs = coarseAlign(envL, envR)
        val (fineMs, confidence) = refine(pcmL, pcmR, coarseMs)
        Result(
            ok = true,
            offsetMs = fineMs,
            confidence = confidence,
            lowConfidence = confidence < MIN_CONFIDENCE
        )
    }

    // region 1. 解码 → 单声道 8kHz PCM

    /**
     * 解出单声道 Float PCM（重采样到 [TARGET_SR]，只取前 [MAX_SECONDS] 秒）。
     * 无音轨 / 解码失败返回 null。委托轨序版第 0 轨（对比页双文件对齐行为零变化）。
     */
    internal fun decodeMono(context: Context, uri: Uri): FloatArray? {
        return decodeMono(context, uri, 0)
    }

    /**
     * 同上，但按**容器轨序**取第 [audioIndex] 条音轨（0 起）：内录双轨片 0=环境、1=内录
     * （识别口径见 [TrackSync] 头注）。旧单参签名原样保留（委托本函数、默认 0），
     * 对比页双文件对齐走的仍是"每文件第一条音轨"，行为零变化。
     */
    internal fun decodeMono(context: Context, uri: Uri, audioIndex: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var mime: String? = null
            var nth = 0
            for (i in 0 until extractor.trackCount) {
                val t = extractor.getTrackFormat(i)
                val m = t.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    if (nth == audioIndex) {
                        trackIndex = i
                        mime = m
                        break
                    }
                    nth++
                }
            }
            if (trackIndex < 0 || mime == null) return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            val dec = codec ?: return null
            dec.configure(format, null, null, 0)
            dec.start()

            val raw = GrowableFloats()
            val srcRate = IntArray(1) { format.integerOr(MediaFormat.KEY_SAMPLE_RATE, TARGET_SR) }
            var channels = format.integerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var encoding = format.integerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0
            // 首样本 PTS 作该轨时间基准：两轨各自归零后再互相关，起始时间戳/edit list 偏移才不会变成常量误差
            var baseUs = -1L

            while (!outputDone && guard < 200_000) {
                guard++
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)
                        val size = buf?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (size < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = dec.outputFormat
                        srcRate[0] = if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) out.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_SR
                        channels = if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) out.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                        encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    outIndex >= 0 -> {
                        val outBuf = dec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val pts = bufferInfo.presentationTimeUs
                            if (pts >= 0L) {
                                if (baseUs < 0L) baseUs = pts
                                val ch = max(1, channels)
                                val rate = max(1, srcRate[0])
                                val limit = max(1, MAX_SECONDS * rate * ch)
                                // GrowableFloats 的下标是交织声道样本数，所以帧位要乘声道数
                                val sampleIndex = ((pts - baseUs) * rate * ch / 1_000_000.0).roundToInt()
                                raw.alignTo(sampleIndex.coerceIn(0, limit), max(1, rate * ch / 1000))
                            }
                            val bytes = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(bytes, 0, bufferInfo.size)
                            raw.appendPcm(bytes, channels, encoding)
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        // 已取到目标时长就收工，30s 足够对齐
                        val frames = raw.sampleCount / max(1, channels)
                        if (frames / max(1, srcRate[0]) >= MAX_SECONDS) outputDone = true
                    }
                    else -> Unit // INFO_TRY_AGAIN_LATER
                }
            }
            val mixed = raw.toFloats(channels)
            return resample(mixed, srcRate[0], TARGET_SR, MAX_SECONDS)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 攒解码输出字节，按声道交织存 float（mixdown 留到 [toFloats]） */
    private class GrowableFloats {
        private var data = FloatArray(1 shl 16)
        var sampleCount = 0
            private set

        /**
         * 按 PTS 落位：流内空洞补静音，让数组下标表示「相对首样本的时间」而不是解码顺序。
         * 亚毫秒的取整抖动跳过即可 —— 每帧都按绝对 PTS 重新标定，抖动不会累积。
         */
        fun alignTo(sampleIndex: Int, minPadSamples: Int) {
            val gap = sampleIndex - sampleCount
            if (gap <= minPadSamples) return
            ensure(gap)
            repeat(gap) { if (sampleCount < data.size) data[sampleCount++] = 0f }
        }

        private fun ensure(n: Int) {
            if (sampleCount + n <= data.size) return
            var size = data.size
            while (size < sampleCount + n) size *= 2
            data = data.copyOf(size)
        }

        fun appendPcm(bytes: ByteArray, channels: Int, encoding: Int) {
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val n = bytes.size / 4
                    ensure(n)
                    var i = 0
                    while (i + 3 < bytes.size && sampleCount < data.size) {
                        val bits = (bytes[i].toInt() and 0xFF) or
                            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[i + 3].toInt() and 0xFF) shl 24)
                        data[sampleCount++] = Float.fromBits(bits)
                        i += 4
                    }
                }
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val n = bytes.size / 3
                    ensure(n)
                    var i = 0
                    while (i + 2 < bytes.size && sampleCount < data.size) {
                        var v = (bytes[i].toInt() and 0xFF) or
                            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[i + 2].toInt() and 0xFF) shl 16)
                        if (v and 0x800000 != 0) v = v or 0xFF000000.toInt()
                        data[sampleCount++] = v / 8388608f
                        i += 3
                    }
                }
                else -> {
                    // 默认 16bit little-endian（含 ENCODING_PCM_16BIT 与未知编码的兜底）
                    val n = bytes.size / 2
                    ensure(n)
                    var i = 0
                    while (i + 1 < bytes.size && sampleCount < data.size) {
                        var v = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                        if (v and 0x8000 != 0) v = v - 0x10000
                        data[sampleCount++] = v / 32768f
                        i += 2
                    }
                }
            }
        }

        /** 交织多声道 → 单声道（均值） */
        fun toFloats(channels: Int): FloatArray {
            val ch = max(1, channels)
            if (ch == 1) return data.copyOfRange(0, sampleCount)
            val frames = sampleCount / ch
            val out = FloatArray(frames)
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) sum += data[f * ch + c]
                out[f] = sum / ch
            }
            return out
        }
    }

    /** 重采样委托 core（纯 DSP 单一真源，见 [AudioAlign]） */
    internal fun resample(src: FloatArray, srcRate: Int, dstRate: Int, maxSeconds: Int): FloatArray =
        AudioAlign.resample(src, srcRate, dstRate, maxSeconds)

    // endregion

    // region 2~5. 包络 / 粗对齐 / FFT 精修（全部委托 core.AudioAlign）

    internal fun envelope(pcm: FloatArray, sr: Int = TARGET_SR): FloatArray = AudioAlign.envelope(pcm, sr)

    internal fun coarseAlign(envL: FloatArray, envR: FloatArray): Long = AudioAlign.coarseAlign(envL, envR)

    internal fun refine(pcmL: FloatArray, pcmR: FloatArray, coarseMs: Long): Pair<Long, Float> =
        AudioAlign.refine(pcmL, pcmR, coarseMs)

    internal fun fft(re: FloatArray, im: FloatArray) = AudioAlign.fft(re, im)

    internal fun inverseFft(re: FloatArray, im: FloatArray) = AudioAlign.inverseFft(re, im)

    // endregion

    private fun MediaFormat.integerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
