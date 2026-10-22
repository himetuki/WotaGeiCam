package com.wotagei.cam.record

import android.content.Context
import android.os.SystemClock
import com.wotagei.cam.core.AudioAlign
import com.wotagei.cam.diag.Diag as Log

/** 预校准结果（shiftUs = 内录通道喂编码器时的 PTS 平移，正 = 延后） */
data class PreflightResult(
    val ok: Boolean,
    val offsetMs: Int = 0,
    val confidence: Double = 0.0,
    val shiftUs: Long = 0L
)

/**
 * 录制开始前的蓝牙内录预校准（用户需求：A2DP 播放时内录比环境音早，成片里现场声被蓝牙延迟）。
 *
 * 做法：两条**临时** [AudioFeeder]（环境路 = 麦克风，内录路 = AudioPlaybackCapture）
 * 同时采 800ms，按**两路共同的 t0** 落位（前导补静音）后转单声道 8k，交给
 * [AudioAlign.estimateOffsetMs]。不用"各自首包归零"：两路 AudioRecord 启动/读缓冲差可达
 * 几十毫秒，各自归零会把这层差异从测量里抹掉。
 * 留下的正是 A2DP 缓冲造成的听感延迟。
 *
 * 边界（预校准失败绝不影响录制）：
 * - 权限/参数/采集器建不起来、超时、静音、低置信 → `ok=false`；
 * - 800ms 为采集目标（到时即停）、400ms 为数据下限；600ms 时两路仍全零提前放弃；
 * - [shouldCancel] 每轮询检查一次（20ms 粒度），用户点停不必等满 800ms；
 * - 两条临时 feeder 在 finally 里 **stop()**（同时释放 AudioRecord）；两次 stop 各带
 *   有界 join（各 ≤300ms），因此起录链最坏多等约 1.4s，仍是有限值。
 */
class AudioPreflightCapture(context: Context) {

    private val appContext = context.applicationContext

    fun capture(
        sampleRate: Int,
        channels: Int,
        source: Int,
        shouldCancel: () -> Boolean = { false }
    ): PreflightResult {
        if (shouldCancel()) return fail("用户已点停，未启动预校准采集")
        if (sampleRate <= 0 || channels <= 0) return fail("参数非法 sr=$sampleRate ch=$channels")
        val minBuf = AudioProbe.minBufferSize(sampleRate, channels)
        if (minBuf <= 0) return fail("采样参数本机不支持 sr=$sampleRate ch=$channels")
        if (!AudioProbe.hasRecordPermission(appContext)) return fail("无 RECORD_AUDIO 权限")

        val env = AudioFeeder(appContext, sampleRate, channels, source, minBuf)
        val cap = AudioFeeder(PlaybackCaptureController.get(appContext), sampleRate, channels, minBuf)
        // 两路共用的时间原点：取启动前一刻的 nanoTime。各源首包 PTS 是 read 返回时刻，
        // 两路启动/读缓冲差可达几十毫秒——各自首包归零会把这层差异从测量里抹掉。
        val t0Us = System.nanoTime() / 1000L
        val envPcm = PreflightMonoPcm(sampleRate, channels, t0Us)
        val capPcm = PreflightMonoPcm(sampleRate, channels, t0Us)
        try {
            if (!env.start()) return fail("环境采集器启动失败")
            if (!cap.start()) return fail("内录采集器启动失败（会话未激活/参数不支持）")
            val collected = collect(env, cap, envPcm, capPcm, shouldCancel)
            if (collected != null) return fail(collected)
        } finally {
            runCatching { env.stop() }
            runCatching { cap.stop() }
        }

        val envMs = envPcm.durationMs()
        val capMs = capPcm.durationMs()
        if (envMs < MIN_DATA_MS || capMs < MIN_DATA_MS) {
            return fail("数据不足 env=${envMs}ms cap=${capMs}ms（下限 ${MIN_DATA_MS}ms）")
        }
        val env8k = AudioAlign.resample(envPcm.toArray(), sampleRate, TARGET_SR, MAX_SECONDS)
        val cap8k = AudioAlign.resample(capPcm.toArray(), sampleRate, TARGET_SR, MAX_SECONDS)
        val estimate = AudioAlign.estimateOffsetMs(
            envPcm = env8k,
            capPcm = cap8k,
            sampleRate = TARGET_SR,
            searchMs = SEARCH_MS,
            minConfidence = AudioPreflightPolicy.MIN_CONFIDENCE
        ) ?: return fail("低置信/静音，无法估计偏移")
        val shiftUs = AudioPreflightPolicy.capPreShiftUs(estimate.offsetMs, estimate.confidence)
        Log.i(
            TAG,
            "预校准采集完成 env=${envMs}ms cap=${capMs}ms offset=${estimate.offsetMs}ms " +
                "conf=${estimate.confidence} shift=${shiftUs}us"
        )
        return PreflightResult(
            ok = true,
            offsetMs = estimate.offsetMs,
            confidence = estimate.confidence,
            shiftUs = shiftUs
        )
    }

    /** 采集阶段；返回非 null = 失败原因（null 表示拿到可用数据） */
    private fun collect(
        env: AudioFeeder,
        cap: AudioFeeder,
        envPcm: PreflightMonoPcm,
        capPcm: PreflightMonoPcm,
        shouldCancel: () -> Boolean
    ): String? {
        val startMs = SystemClock.elapsedRealtime()
        var envEos = false
        var capEos = false
        while (true) {
            if (shouldCancel()) return "用户取消"
            val elapsed = SystemClock.elapsedRealtime() - startMs
            if (elapsed >= TARGET_MS || elapsed >= HARD_TIMEOUT_MS) break
            if (!envEos) {
                val p = env.poll(POLL_MS)
                if (p != null) {
                    if (p.ptsUs < 0) envEos = true else envPcm.append(p.data, p.ptsUs)
                }
            }
            if (!capEos) {
                val p = cap.poll(POLL_MS)
                if (p != null) {
                    if (p.ptsUs < 0) capEos = true else capPcm.append(p.data, p.ptsUs)
                }
            }
            if (elapsed >= SILENCE_CHECK_MS && envPcm.isSilent() && capPcm.isSilent()) {
                return "两路在 ${SILENCE_CHECK_MS}ms 内全为零（无媒体在播/内录静音）"
            }
            if (envEos && capEos) break
        }
        if (envPcm.durationMs() < MIN_DATA_MS || capPcm.durationMs() < MIN_DATA_MS) {
            return "数据不足 env=${envPcm.durationMs()}ms cap=${capPcm.durationMs()}ms"
        }
        return null
    }

    private fun fail(reason: String): PreflightResult {
        Log.i(TAG, "蓝牙预校准跳过：$reason")
        return PreflightResult(false)
    }

    private companion object {
        const val TAG = "WotaPreflight"
        const val TARGET_SR = AudioAlign.TARGET_SR
        const val MAX_SECONDS = 8
        const val TARGET_MS = 800L
        const val MIN_DATA_MS = 400L
        const val HARD_TIMEOUT_MS = 1_500L
        const val SILENCE_CHECK_MS = 600L
        const val POLL_MS = 20L
        /** 预校准只处理 A2DP 可辨延迟：搜索窗收窄到 ±600ms，与 clamp 上限同口径 */
        const val SEARCH_MS = 600
    }
}
