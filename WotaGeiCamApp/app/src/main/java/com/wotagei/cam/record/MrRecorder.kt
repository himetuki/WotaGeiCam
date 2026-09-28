package com.wotagei.cam.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import com.wotagei.cam.core.WotaTiers
import java.io.IOException

/**
 * MediaRecorder 引擎（04 文件 §3：fps ≤ 60 默认路径，DIRECT 与 GPU 共用）。
 *
 * prepare 顺序固定（`` 教学节 / 04 文件 §3），跳步会以 IOException 或 IllegalState 收场：
 * reset → setAudioSource → setVideoSource(SURFACE) → setOutputFormat(MPEG_4) → 输出 → setMaxFileSize →
 * setVideoFrameRate → setVideoEncodingBitRate → setVideoSize → 音频参数 → setVideoEncoder →
 * setCaptureRate(延时) → setOrientationHint → setInputSurface(GPU) → prepare。
 *
 * 分段：`setMaxFileSize(WotaTiers.MAX_FILE_BYTES)` + 802 回调 →
 * stop → sleep 500ms → 新 pending uri → re-prepare → start。
 * 分段是在 MediaRecorder 回调线程里同步做的，会占住该线程约 500ms，
 * 因此 [prepare] 必须在带 Looper 的工作线程调用（不要放主线程）。
 */
class MrRecorder(
    private val ctx: Context,
    private val store: VideoStore
) : Recorder, MediaRecorder.OnInfoListener, MediaRecorder.OnErrorListener {

    companion object {
        /** 段间隔：stop 与 re-prepare 之间留时间让文件系统落盘（restartRecord） */
        private const val SEGMENT_GAP_MS = 500L
    }

    private var mr: MediaRecorder? = null
    private var profile: RecordProfile? = null
    private var target: OutputTarget? = null
    private var currentSink: OutputSink? = null

    @Volatile private var state = EngineState.IDLE
    @Volatile private var engineError: String? = null
    @Volatile private var rotating = false

    private val clock = ElapsedClock()
    private val parts = ArrayList<VideoSegment>()
    private var partIndex = 0
    private var partStartMs = 0L

    /** GPU 模式的持久输入面（API 29 起可用，跨分段复用不重建） */
    private var inputSurface: Surface? = null
    private var inputSurfaceAccepted = false

    /** prepare 成功后有效：DIRECT 返回 `getSurface()`，GPU 返回持久输入面（`setInputSurface` 被接受时） */
    override val surface: Any?
        get() = if (inputSurfaceAccepted) inputSurface else mrSurfaceSafely()

    private fun mrSurfaceSafely(): Surface? = try {
        mr?.surface
    } catch (e: IllegalStateException) {
        null
    }

    @Suppress("DEPRECATION") // MediaRecorder(Context) 是 API 31，minSdk 29 只能用无参构造
    private fun ensureRecorder(): MediaRecorder =
        mr ?: MediaRecorder().also { mr = it }

    // region 生命周期

    override fun prepare(p: RecordProfile, sink: OutputSink): Boolean {
        if (state != EngineState.IDLE) {
            Log.e(TAG, "mr prepare ignored, state=$state")
            return false
        }
        state = EngineState.PREPARE
        engineError = null
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        clock.reset()
        // 录像前余量门槛（04 §5.1）：不够直接拒绝，且不留下 pending 幽灵条目
        store.checkFreeSpace()?.let {
            reject(sink, it)
            return false
        }
        val t = OutputTarget.open(ctx, sink)
        if (t == null || !t.usable) {
            reject(sink, RecordError.NO_OUTPUT)
            return false
        }
        target = t
        currentSink = sink
        val effective = setupRecorder(p, sink, t)
        if (effective == null) {
            reject(sink, RecordError.PREPARE_FAILED)
            return false
        }
        profile = effective
        Log.i(TAG, "mr prepared part=$partIndex ${effective.width}x${effective.height}@${effective.fps} " +
            "bps=${effective.effectiveBitrate()} codec=${if (effective.hevc) "hevc" else "avc"} " +
            "audio=${audioDesc(effective)} hint=${effective.orientationHint} gpu=${effective.useGpu} maxFile=${WotaTiers.MAX_FILE_BYTES}")
        return true
    }

    /** 参数下发；HEVC 不被支持时原地降级 H264 重试一次，返回真正生效的 profile */
    private fun setupRecorder(p: RecordProfile, sink: OutputSink, t: OutputTarget): RecordProfile? {
        val rec = ensureRecorder()
        val audio = p.audioUsable && AudioProbe.hasRecordPermission(ctx) &&
            AudioProbe.isSampleRateSupported(p.sampleRate, p.channels)
        if (applyConfig(rec, p, t, audio, encoderOf(p), sink)) return p
        if (!p.hevc) return null
        Log.i(TAG, "hevc rejected by MediaRecorder, retry h264")
        val plain = p.copy(codec = RecordProfile.CODEC_H264)
        return if (applyConfig(rec, plain, t, audio, MediaRecorder.VideoEncoder.H264, sink)) plain else null
    }

    private fun encoderOf(p: RecordProfile): Int =
        if (p.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264

    /** 唯一的参数下发实现（首备与分段重启共用），失败只返回 false 不改状态 */
    private fun applyConfig(
        rec: MediaRecorder, p: RecordProfile, t: OutputTarget, audio: Boolean, encoder: Int, sink: OutputSink
    ): Boolean {
        val fd = t.fileDescriptor
        val path = t.path
        if (fd == null && path == null) {
            Log.e(TAG, "applyConfig: output target has neither fd nor path")
            return false
        }
        return try {
            rec.reset()
            if (audio) rec.setAudioSource(AudioProbe.pickSource(ctx))
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            // 输出：content:// 用 FileDescriptor，其余用 path
            when {
                fd != null -> rec.setOutputFile(fd)
                path != null -> rec.setOutputFile(path)
            }
            rec.setMaxFileSize(WotaTiers.MAX_FILE_BYTES) // 分段由文件大小驱动
            rec.setVideoFrameRate(p.fps)
            rec.setVideoEncodingBitRate(p.effectiveBitrate())
            rec.setVideoSize(p.width, p.height)
            if (audio) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(p.sampleRate)
                rec.setAudioChannels(p.channels)
                rec.setAudioEncodingBitRate(p.audioBitrate)
            }
            rec.setVideoEncoder(encoder)
            if (p.timeLapse) rec.setCaptureRate(p.captureRate.toDouble())
            rec.setOrientationHint(p.orientationHint)
            if (p.useGpu) applyInputSurface(rec)
            rec.setOnInfoListener(this)
            rec.setOnErrorListener(this)
            rec.prepare()
            profile = p
            currentSink = sink
            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "applyConfig rejected: ${e.message}")
            false
        } catch (e: IOException) {
            Log.e(TAG, "applyConfig io failed: ${e.message}")
            false
        }
    }

    /**
     * GPU 模式：GL 渲染结果直接画进编码器面（零拷贝、录出=所见）。
     * `setInputSurface` 在部分低版本/定制 ROM 上不可用，失败即退回 `getSurface()`——
     * 两者都是 SURFACE 输入源，语义等价，只是前者可跨分段复用同一个面。
     */
    private fun applyInputSurface(rec: MediaRecorder) {
        val s = inputSurface ?: MediaCodec.createPersistentInputSurface().also { inputSurface = it }
        inputSurfaceAccepted = try {
            rec.setInputSurface(s)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "setInputSurface unavailable (${t.javaClass.simpleName}), fallback to recorder surface")
            false
        }
    }

    override fun start() {
        if (state != EngineState.PREPARE) {
            Log.e(TAG, "mr start ignored, state=$state")
            return
        }
        partStartMs = clock.elapsedMs()
        try {
            mr?.start()
            state = EngineState.START
            clock.startSegment()
            Log.i(TAG, "mr start part=$partIndex from=${partStartMs}ms")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "mr start failed: ${e.message}")
            failNow(RecordError.START_FAILED, dropOutput = true)
        }
    }

    override fun stop(): RecordResult {
        val prev = state
        if (prev == EngineState.IDLE) {
            if (parts.isNotEmpty()) return buildResult(engineError)
            return RecordResult.fail(engineError ?: RecordError.BAD_STATE, clock.elapsedMs())
        }
        state = EngineState.STOPPING
        // err 一律在 stopEngine() 之后再取：一帧没写时 stop 会抛异常，本段只能当废片
        val dur = if (prev == EngineState.START) {
            stopEngine()
            clock.closeSegment()
        } else {
            clock.closeSegment()
        }
        val err = engineError
        if (prev == EngineState.START) {
            sealCurrent(dur, err == null && dur >= RecordProfile.MIN_KEEP_MS)
        } else {
            discardCurrent(err ?: RecordError.BAD_STATE)
        }
        releaseEngine()
        val total = clock.elapsedMs()
        val out = if (parts.isEmpty()) RecordResult.fail(err ?: RecordError.TOO_SHORT, total)
        else buildResult(err)
        Log.i(TAG, "mr stop total=${total}ms parts=${parts.size} code=${out.error}")
        state = EngineState.IDLE
        engineError = null
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        return out
    }

    override fun release() {
        when (state) {
            EngineState.START -> {
                stopEngine()
                val dur = clock.closeSegment()
                sealCurrent(dur, engineError == null && dur >= RecordProfile.MIN_KEEP_MS)
                releaseEngine()
            }
            EngineState.PREPARE -> {
                discardCurrent(RecordError.RELEASED)
                releaseEngine()
            }
            else -> releaseEngine()
        }
        inputSurface?.release()
        inputSurface = null
        inputSurfaceAccepted = false
        state = EngineState.IDLE
        engineError = null
        clock.reset()
        parts.clear()
        partIndex = 0
        Log.i(TAG, "mr released")
    }

    override val elapsedMs: Long get() = clock.elapsedMs()

    /** 振幅来自 `getMaxAmplitude()`（音量表），量程 0..32767，非 START 状态返回 0 */
    override fun amplitude(): Int = try {
        if (state == EngineState.START) mr?.maxAmplitude ?: 0 else 0
    } catch (e: IllegalStateException) {
        0
    } catch (e: RuntimeException) {
        0
    }

    // endregion

    // region 分段
    override fun onInfo(rec: MediaRecorder?, what: Int, extra: Int) {
        if (what != MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) {
            Log.i(TAG, "mr info what=$what extra=$extra")
            return
        }
        when (extra) {
            // 802 逼近上限即换段（801 兜底，部分 ROM 只报到达）
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING,
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> rotateSegment(extra)
            else -> Log.i(TAG, "mr info extra=$extra")
        }
    }

    override fun onError(rec: MediaRecorder?, what: Int, extra: Int) {
        Log.e(TAG, "mr error what=$what extra=$extra")
        engineError = "${RecordError.ENGINE_ERROR}:$what:$extra"
        state = EngineState.ERROR
    }

    /**  restartRecord：stop → 500ms → 新 pending → re-prepare → start，全程在本回调线程 */
    private fun rotateSegment(extra: Int) {
        if (rotating) return
        rotating = true
        try {
            if (state != EngineState.START) return
            Log.i(TAG, "file size event extra=$extra, restartRecord")
            stopEngine()
            val dur = clock.closeSegment()
            sealCurrent(dur, engineError == null && dur >= RecordProfile.MIN_KEEP_MS)
            Thread.sleep(SEGMENT_GAP_MS)
            val next = store.createPending(partIndex + 1)
                ?: return failNow("${RecordError.ENGINE_ERROR}:ROTATE_NO_OUTPUT", dropOutput = false)
            val sink = OutputSink.Pending(next)
            val t = OutputTarget.open(ctx, sink)
            val p = profile
            if (t == null || p == null) {
                store.discard(sink) // 新 pending 没被写过，必须立刻回收
                return failNow(RecordError.NO_OUTPUT, dropOutput = false)
            }
            val audio = p.audioUsable && AudioProbe.hasRecordPermission(ctx) &&
                AudioProbe.isSampleRateSupported(p.sampleRate, p.channels)
            if (!applyConfig(ensureRecorder(), p, t, audio, encoderOf(p), sink)) {
                t.close()
                store.discard(sink)
                return failNow("${RecordError.ENGINE_ERROR}:ROTATE_PREPARE", dropOutput = false)
            }
            partIndex++
            partStartMs = clock.elapsedMs()
            mr?.start()
            state = EngineState.START
            clock.startSegment()
            Log.i(TAG, "segment $partIndex started at ${partStartMs}ms")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failNow("${RecordError.ENGINE_ERROR}:ROTATE_INTERRUPT", dropOutput = true)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "segment start failed: ${e.message}")
            failNow("${RecordError.ENGINE_ERROR}:ROTATE_START", dropOutput = true)
        } finally {
            rotating = false
        }
    }
    // endregion

    // region 内部收尾

    private fun audioDesc(p: RecordProfile): String = when {
        !p.audioUsable -> "off"
        !AudioProbe.hasRecordPermission(ctx) -> "no-permission"
        !AudioProbe.isSampleRateSupported(p.sampleRate, p.channels) -> "sr-${p.sampleRate}-unsupported"
        else -> "${p.sampleRate}Hz/${p.channels}ch"
    }

    private fun buildResult(err: String?): RecordResult {
        val first = parts.firstOrNull()
        return RecordResult(
            uri = first?.uri,
            path = first?.path,
            bytes = parts.sumOf { it.bytes },
            durationMs = clock.elapsedMs(),
            error = err,
            seriesId = store.seriesId,
            parts = parts.toList(),
            uncommitted = parts.count { !it.committed }
        )
    }

    /** prepare 阶段的失败：清输出 + 删 pending，回 IDLE（04 §6 失败即 ERROR 再回 IDLE） */
    private fun reject(sink: OutputSink, code: String) {
        Log.e(TAG, "mr prepare rejected: $code")
        engineError = code
        state = EngineState.ERROR
        releaseEngine()
        store.discard(sink)
        target = null
        currentSink = null
        state = EngineState.IDLE
    }

    private fun failNow(code: String, dropOutput: Boolean) {
        Log.e(TAG, "mr aborted: $code")
        engineError = code
        state = EngineState.ERROR
        clock.closeSegment()
        if (dropOutput) discardCurrent(code)
        releaseEngine()
    }

    /** 只 stop+reset，不 release：stop 之后该文件即完整可播（分段与停止共用） */
    private fun stopEngine() {
        val rec = mr ?: return
        try {
            rec.stop()
        } catch (e: IllegalStateException) {
            // 「一帧都没进」的常见现象：本段按废片处理
            Log.e(TAG, "mr stop threw: ${e.message}")
            if (engineError == null) engineError = RecordError.STOP_FAILED
        } catch (e: RuntimeException) {
            Log.e(TAG, "mr stop threw: ${e.message}")
            if (engineError == null) engineError = RecordError.STOP_FAILED
        }
        try {
            rec.reset()
        } catch (e: RuntimeException) {
            Log.i(TAG, "mr reset after stop ignored")
        }
    }

    private fun sealCurrent(durationMs: Long, keep: Boolean) {
        val sink = currentSink ?: return
        // 先关输出句柄再 commit/discard：文件写完、大小统计到位之后才能清 IS_PENDING
        target?.close()
        target = null
        currentSink = null
        val seg = store.seal(sink, partIndex, partStartMs, durationMs, keep)
        // 废片不能进 parts：seal 已经把这条 MediaStore 记录删掉了，列进去就会让
        // buildResult 拿到一个不存在的 uri，out.ok 成立 → UI 报「已保存」而文件其实没了
        if (keep) parts.add(seg)
    }

    private fun discardCurrent(code: String) {
        val sink = currentSink ?: return
        Log.i(TAG, "discard unusable output: $code")
        target?.close()
        target = null
        currentSink = null
        store.seal(sink, partIndex, partStartMs, 0L, keep = false)
    }

    private fun releaseEngine() {
        val rec = mr ?: return
        try {
            rec.release()
        } catch (e: RuntimeException) {
            Log.i(TAG, "mr release threw: ${e.message}")
        }
        mr = null
    }

    // endregion
}
