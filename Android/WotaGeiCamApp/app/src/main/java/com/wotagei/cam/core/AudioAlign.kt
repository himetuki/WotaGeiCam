package com.wotagei.cam.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 音频自动对齐的**纯 DSP 核心**（05 文档第 7 节定版：能量包络互相关 + FFT 精修，纯 Kotlin、零第三方库）。
 *
 * 本文件不许 import `android.*` / `player.*`：player 的 [com.wotagei.cam.player.AudioSync] 与
 * 录制前的 `record/AudioPreflight` 共用同一份实现，JVM 单测直接跑它。解码/持久化等 Android 依赖
 * 留在各自的调用方。
 *
 * 偏移符号约定（与对比页、TrackSync 全链一致）：**left(t) ≈ right(t + offsetMs)**。
 * 本类里 left=环境参考、right=内录（或对比页右片），所以正 offset = 内录内容晚响。
 *
 * 处理链：
 * 1. 10ms 窗 RMS → 去均值 → 归一化包络（100 点/秒）；
 * 2. ±searchMs 搜索窗内互相关求粗偏移；
 * 3. 以粗偏移为中心，1kHz 带内 FFT 相关在 ±refineMs 内以 1ms 步进精修；
 * 4. 置信度 = 峰 / 次峰（次峰取主峰 ±150ms 之外的最大值）。
 */
object AudioAlign {

    /** 对齐链的目标采样率（AudioSync 的「至少 1 秒 PCM」口径也复用它） */
    const val TARGET_SR = 8000

    /** 低置信度门槛（主峰/次峰比）；官方口径由 AudioSync 与 TrackSync 共同引用 */
    const val MIN_CONFIDENCE = 1.5f

    const val ENV_WIN_MS = 10
    const val DEFAULT_SEARCH_MS = 10_000
    const val DEFAULT_REFINE_MS = 50
    const val PEAK_GUARD_MS = 150

    data class OffsetEstimate(val offsetMs: Int, val confidence: Double)

    /**
     * 估计 `capPcm` 相对 `envPcm` 的内容偏移。
     *
     * 两段 PCM 必须**已同一采样率、单声道**（调用方负责重采样；录制前预校准转 8k，对比页也转 8k）。
     * 输入过短 / 全静音 / 置信度低于 [minConfidence] 一律返回 null——调用方据此走「不纠偏」，
     * 不许把低置信结果当成真偏移写盘。
     *
     * @param searchMs 粗对齐搜索半径，单位 ms
     * @param refineMs 精修搜索半径（围绕粗偏移），单位 ms
     */
    fun estimateOffsetMs(
        envPcm: FloatArray,
        capPcm: FloatArray,
        sampleRate: Int,
        searchMs: Int = DEFAULT_SEARCH_MS,
        refineMs: Int = DEFAULT_REFINE_MS,
        minConfidence: Double = MIN_CONFIDENCE.toDouble()
    ): OffsetEstimate? {
        if (sampleRate <= 0 || searchMs <= 0 || refineMs <= 0) return null
        // 200ms 下限：再短连一个包络窗口都凑不满，互相关没有统计意义
        val minSamples = max(1, sampleRate / 5)
        if (envPcm.size < minSamples || capPcm.size < minSamples) return null
        if (isSilent(envPcm) || isSilent(capPcm)) return null
        val env = envelope(envPcm, sampleRate)
        val cap = envelope(capPcm, sampleRate)
        val coarseMs = coarseAlign(env, cap, searchMs)
        val (fineMs, confidence) = refine(envPcm, capPcm, coarseMs, sampleRate, searchMs, refineMs)
        if (confidence < minConfidence) return null
        return OffsetEstimate(fineMs.toInt(), confidence.toDouble())
    }

    private fun isSilent(pcm: FloatArray): Boolean {
        var peak = 0f
        for (v in pcm) {
            val a = abs(v)
            if (a > peak) peak = a
            if (peak > 1e-6f) return false
        }
        return true
    }

    // region 重采样与包络

    /**
     * 线性插值重采样 + 截断到 maxSeconds。
     * 输出帧数 = 源帧数×dst/src（整段重采样的目标帧数）再被 maxSeconds 封顶——
     * 旧实现拿 src.size（源帧数）与 dstRate·maxSeconds（目标帧数）直接取 min 是单位混用。
     */
    fun resample(src: FloatArray, srcRate: Int, dstRate: Int, maxSeconds: Int): FloatArray {
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

    /** 10ms 窗 RMS，去均值后归一化到 0..1，得到 100 点/秒的包络 */
    fun envelope(pcm: FloatArray, sr: Int = TARGET_SR): FloatArray {
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

    // endregion

    // region 粗对齐

    /**
     * 粗偏移（ms）：在 ±[searchMs] 内求归一化互相关最大。
     * 正偏移含义见类注释：left(t) ≈ right(t + offset)。
     */
    fun coarseAlign(
        envL: FloatArray,
        envR: FloatArray,
        searchMs: Int = DEFAULT_SEARCH_MS
    ): Long {
        val stepMs = ENV_WIN_MS
        val maxLag = max(0, searchMs / stepMs)
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

    // region FFT 精修与置信度

    /**
     * 精修：1kHz 带内（带通由低通近似）做 FFT 互相关，在粗偏移 ±[refineMs] 内按 1ms 步进取峰。
     * 置信度 = 主峰 / 次峰（次峰取主峰 ±[PEAK_GUARD_MS] 之外、搜索窗内的最大值）。
     */
    fun refine(
        pcmL: FloatArray,
        pcmR: FloatArray,
        coarseMs: Long,
        sampleRate: Int = TARGET_SR,
        searchMs: Int = DEFAULT_SEARCH_MS,
        refineMs: Int = DEFAULT_REFINE_MS
    ): Pair<Long, Float> {
        val low = lowPass1k(pcmL, sampleRate)
        val high = lowPass1k(pcmR, sampleRate)
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
        val lo = (coarseMs - refineMs).coerceAtLeast(-searchMs.toLong())
        val hi = (coarseMs + refineMs).coerceAtMost(searchMs.toLong())
        val maxLagSamples = (searchMs.toLong() * sampleRate / 1000L).toInt()

        // 候选：1ms 步进的 lag（样本数 = lag * SR/1000）
        val candidates = ArrayList<Pair<Int, Float>>()
        var ms = lo
        while (ms <= hi) {
            val lag = (ms * sampleRate / 1000L).toInt()
            if (abs(lag) <= maxLagSamples) {
                score(corr, pl, pr, lag, low.size, high.size, n)?.let { candidates += lag to it }
            }
            ms += stepMs
        }
        if (candidates.isEmpty()) return coarseMs to 0f

        val best = candidates.maxByOrNull { it.second } ?: return coarseMs to 0f
        // 次峰：搜索窗内排除主峰 ±150ms
        val guard = (PEAK_GUARD_MS.toLong() * sampleRate / 1000L).toInt()
        var second = -Float.MAX_VALUE
        var scan = -searchMs.toLong()
        while (scan <= searchMs) {
            val lag = (scan * sampleRate / 1000L).toInt()
            if (abs(lag - best.first) > guard && abs(lag) <= maxLagSamples) {
                score(corr, pl, pr, lag, low.size, high.size, n)?.let { if (it > second) second = it }
            }
            scan += 4L // 4ms 粒度扫次峰，够精且省时间
        }
        val confidence = when {
            second <= 0.02f -> 99f
            else -> (best.second / second).coerceIn(0f, 99f)
        }
        return (best.first.toLong() * 1000L / sampleRate) to confidence
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

    /** ~1kHz 低通（滑动平均，窗宽 = 采样率/1000；8kHz 下为 8 点，第一零点约 1kHz） */
    private fun lowPass1k(x: FloatArray, sampleRate: Int): FloatArray {
        val win = max(1, sampleRate / 1000)
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
    fun fft(re: FloatArray, im: FloatArray) = transform(re, im, false)

    fun inverseFft(re: FloatArray, im: FloatArray) = transform(re, im, true)

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
}
