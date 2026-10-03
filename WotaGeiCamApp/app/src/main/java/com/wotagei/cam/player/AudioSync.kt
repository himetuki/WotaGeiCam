package com.wotagei.cam.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.wotagei.cam.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    private const val TARGET_SR = 8000
    private const val MAX_SECONDS = 30
    private const val ENV_WIN_MS = 10
    private const val SEARCH_MS = 10_000L
    private const val REFINE_MS = 50L
    private const val PEAK_GUARD_MS = 150L
    private const val MIN_CONFIDENCE = 1.5f

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
     * 无音轨 / 解码失败返回 null。
     */
    internal fun decodeMono(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val t = extractor.getTrackFormat(i)
                val m = t.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    trackIndex = i
                    mime = m
                    break
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

    /** 线性插值重采样 + 截断到 maxSeconds。
     *  输出帧数 = 源帧数×dst/src（整段重采样的目标帧数）再被 maxSeconds 封顶——
     *  旧实现拿 src.size（源帧数）与 dstRate·maxSeconds（目标帧数）直接取 min 是单位混用，
     *  44.1k→8k 时会输出 44100 帧（=5.5s@8k，比真实时长多出 10% 的零填充尾巴）。 */
    internal fun resample(src: FloatArray, srcRate: Int, dstRate: Int, maxSeconds: Int): FloatArray {
        val rate = if (srcRate <= 0) dstRate else srcRate
        val limitFrames = minOf(
            src.size.toLong() * dstRate / rate,
            dstRate.toLong() * maxSeconds
        ).toInt().coerceAtLeast(0)
        if (rate == dstRate) return src.copyOfRange(0, limitFrames)
        val ratio = rate.toFloat() / dstRate.toFloat()
        val out = FloatArray(limitFrames)
        for (i in 0 until limitFrames) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = pos - idx
            val a = src.getOrElse(idx) { 0f }
            val b = src.getOrElse(idx + 1) { a }
            out[i] = a + (b - a) * frac
        }
        return out
    }

    // endregion

    // region 2~3. 包络 + 粗对齐

    /** 10ms 窗 RMS，去均值后归一化到 0..1，得到 100 点/秒的包络 */
    internal fun envelope(pcm: FloatArray, sr: Int = TARGET_SR): FloatArray {
        val win = max(1, sr * ENV_WIN_MS / 1000)
        val n = pcm.size / win
        if (n <= 0) return FloatArray(1) { 0f }
        val rms = FloatArray(n)
        for (k in 0 until n) {
            var sum = 0.0
            val base = k * win
            for (j in 0 until win) {
                val v = pcm[base + j].toDouble()
                sum += v * v
            }
            rms[k] = sqrt(sum / win).toFloat()
        }
        var mean = 0f
        rms.forEach { mean += it }
        mean /= n
        var maxV = 0f
        for (k in 0 until n) {
            val d = rms[k] - mean
            rms[k] = if (d > 0f) d else 0f
            if (rms[k] > maxV) maxV = rms[k]
        }
        if (maxV > 0f) for (k in 0 until n) rms[k] = rms[k] / maxV
        return rms
    }

    /**
     * 粗偏移（ms）：在 ±[SEARCH_MS] 内求归一化互相关最大。
     * 正偏移含义见类注释：left(t) ≈ right(t + offset)。
     */
    internal fun coarseAlign(envL: FloatArray, envR: FloatArray): Long {
        val stepMs = ENV_WIN_MS
        val maxLag = (SEARCH_MS / stepMs).toInt()
        val pl = prefixEnergy(envL)
        val pr = prefixEnergy(envR)
        var bestLag = 0
        var bestScore = -Float.MAX_VALUE
        for (lag in -maxLag..maxLag) {
            val (from, until) = overlapRange(envL.size, envR.size, lag) ?: continue
            val r = correlate(envL, envR, from, until, lag)
            val eL = rangeEnergy(pl, from, until)
            val eR = rangeEnergy(pr, from + lag, until + lag)
            val denom = sqrt(eL * eR)
            val score = if (denom > 1e-6f) r / denom else 0f
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return bestLag.toLong() * stepMs
    }

    private fun correlate(a: FloatArray, b: FloatArray, from: Int, until: Int, lag: Int): Float {
        var sum = 0f
        var i = from
        while (i < until) {
            sum += a[i] * b[i + lag]
            i++
        }
        return sum
    }

    // endregion

    // region 4~5. FFT 精修与置信度

    /**
     * 精修：1kHz 带内（带通由低通近似）做 FFT 互相关，在粗偏移 ±[REFINE_MS] 内按 1ms 步进取峰。
     * 置信度 = 主峰 / 次峰（次峰取主峰 ±[PEAK_GUARD_MS] 之外、搜索窗内的最大值）。
     */
    internal fun refine(pcmL: FloatArray, pcmR: FloatArray, coarseMs: Long): Pair<Long, Float> {
        val low = lowPass1k(pcmL)
        val high = lowPass1k(pcmR)
        val n = nextPowerOfTwo(low.size + high.size)
        val reL = FloatArray(n)
        val imL = FloatArray(n)
        val reR = FloatArray(n)
        val imR = FloatArray(n)
        System.arraycopy(low, 0, reL, 0, low.size)
        System.arraycopy(high, 0, reR, 0, high.size)
        meanRemove(reL, low.size)
        meanRemove(reR, high.size)

        fft(reL, imL)
        fft(reR, imR)
        // 互相关：conj(FFT(L)) * FFT(R)
        for (i in 0 until n) {
            val ar = reL[i]; val ai = imL[i]
            val br = reR[i]; val bi = imR[i]
            // (ar - j·ai)(br + j·bi) = (ar·br + ai·bi) + j(ar·bi - ai·br)
            reL[i] = ar * br + ai * bi
            imL[i] = ar * bi - ai * br
        }
        inverseFft(reL, imL)

        val corr = FloatArray(n) { reL[it] / n }
        val pl = prefixEnergy(low)
        val pr = prefixEnergy(high)
        val stepMs = 1L
        val lo = (coarseMs - REFINE_MS).coerceAtLeast(-SEARCH_MS)
        val hi = (coarseMs + REFINE_MS).coerceAtMost(SEARCH_MS)
        val maxLagSamples = (SEARCH_MS * TARGET_SR / 1000L).toInt()

        // 候选：1ms 步进的 lag（样本数 = lag * SR/1000）
        val candidates = ArrayList<Pair<Int, Float>>()
        var ms = lo
        while (ms <= hi) {
            val lag = (ms * TARGET_SR / 1000L).toInt()
            if (kotlin.math.abs(lag) <= maxLagSamples) {
                score(corr, pl, pr, lag, low.size, high.size, n)?.let { candidates += lag.toInt() to it }
            }
            ms += stepMs
        }
        if (candidates.isEmpty()) return coarseMs to 0f

        val best = candidates.maxByOrNull { it.second } ?: return coarseMs to 0f
        // 次峰：搜索窗内排除主峰 ±150ms
        val guard = (PEAK_GUARD_MS * TARGET_SR / 1000L).toInt()
        var second = -Float.MAX_VALUE
        var scan = -SEARCH_MS
        while (scan <= SEARCH_MS) {
            val lag = (scan * TARGET_SR / 1000L).toInt()
            if (abs(lag - best.first) > guard && abs(lag) <= maxLagSamples) {
                score(corr, pl, pr, lag, low.size, high.size, n)?.let { if (it > second) second = it }
            }
            scan += 4L // 4ms 粒度扫次峰，够精且省时间
        }
        val confidence = when {
            second <= 0.02f || second <= 0f -> 99f
            else -> (best.second / second).coerceIn(0f, 99f)
        }
        return (best.first.toLong() * 1000L / TARGET_SR) to confidence
    }

    /** 归一化互相关：直接取 FFT 相关在 lag 处的值，再按重叠窗能量归一 */
    private fun score(corr: FloatArray, pl: FloatArray, pr: FloatArray, lag: Int, lenL: Int, lenR: Int, n: Int): Float? {
        val (from, until) = overlapRange(lenL, lenR, lag) ?: return null
        if (until - from <= 1) return null
        val index = if (lag >= 0) lag else n + lag
        if (index < 0 || index >= n) return null
        val raw = corr[index]
        val eL = rangeEnergy(pl, from, until)
        val eR = rangeEnergy(pr, from + lag, until + lag)
        val denom = sqrt(eL * eR)
        return if (denom > 1e-6f) raw / denom else null
    }

    private fun overlapRange(lenL: Int, lenR: Int, lag: Int): Pair<Int, Int>? {
        val from = max(0, -lag)
        val until = min(lenL, lenR - lag)
        return if (until - from <= 1) null else from to until
    }

    private fun prefixEnergy(x: FloatArray): FloatArray {
        val p = FloatArray(x.size + 1)
        var sum = 0f
        for (i in x.indices) {
            sum += x[i] * x[i]
            p[i + 1] = sum
        }
        return p
    }

    private fun rangeEnergy(prefix: FloatArray, from: Int, until: Int): Float {
        val a = from.coerceIn(0, prefix.size - 1)
        val b = until.coerceIn(0, prefix.size - 1)
        return if (b <= a) 0f else prefix[b] - prefix[a]
    }

    /** ~1kHz 低通（8 点滑动平均，8kHz 下第一零点约 1kHz），抑制高频让对齐更稳 */
    private fun lowPass1k(x: FloatArray): FloatArray {
        val win = 8
        val out = FloatArray(x.size)
        var acc = 0f
        for (i in x.indices) {
            acc += x[i]
            if (i >= win) acc -= x[i - win]
            out[i] = acc / min(win, i + 1)
        }
        return out
    }

    private fun meanRemove(re: FloatArray, len: Int) {
        if (len <= 0) return
        var sum = 0f
        for (i in 0 until len) sum += re[i]
        val m = sum / len
        for (i in 0 until len) re[i] -= m
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var n = 1
        while (n < v) n = n shl 1
        return n
    }

    /** 迭代 radix-2 FFT（in-place） */
    internal fun fft(re: FloatArray, im: FloatArray) = transform(re, im, false)

    internal fun inverseFft(re: FloatArray, im: FloatArray) = transform(re, im, true)

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        if (n <= 1) return
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * Math.PI / len
            val wRe = kotlin.math.cos(ang).toFloat()
            val wIm = kotlin.math.sin(ang).toFloat()
            var start = 0
            while (start < n) {
                var curRe = 1f
                var curIm = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val xRe = re[b] * curRe - im[b] * curIm
                    val xIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - xRe
                    im[b] = im[a] - xIm
                    re[a] += xRe
                    im[a] += xIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
    }

    // endregion

    private fun MediaFormat.integerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
