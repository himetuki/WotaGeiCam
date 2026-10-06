package com.wotagei.cam.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaRecorder
import android.system.Os
import android.util.Log
import android.view.Surface
import com.wotagei.cam.core.WotaTiers
import java.io.File
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

    /**
     * 当前段输出目标。只在**控制线程**（与 [prepare]/[start]/[stop] 同线程）读写——[health] 也只在
     * 该线程被调用（线程口径见 [health]），**今天没有跨线程读者**。标 `@Volatile` 因此非必需，纯属
     * 零成本保险：防御将来有人把自检挪出该线程后，这里才会出现"读到的 target 已换代"这一隐性前提。
     */
    @Volatile private var target: OutputTarget? = null
    private var currentSink: OutputSink? = null

    @Volatile private var state = EngineState.IDLE
    @Volatile private var engineError: String? = null
    @Volatile private var rotating = false

    /** 显式弃段意图（[abandonCurrentSegment] 置位，新会话/新段起始复位）：见 [segmentKeepDecision] */
    @Volatile private var abandonSegment = false

    /**
     * 会话级错误残留：换段成功清 [engineError] 前，把旧段的失败码挪到这里，最终 [RecordResult]
     * 用它兜底（清错只让**新段**恢复判活，绝不把"旧段确实失败过"从结果里抹掉）。
     */
    @Volatile private var sessionError: String? = null

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
        sessionError = null
        abandonSegment = false
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
        abandonSegment = false // 新会话起始：弃段意图不复用（一次判废不许拖累之后每一段）
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
            // 弃段意图（自检判废）压过"看着正常"：见 segmentKeepDecision
            sealCurrent(dur, segmentKeepDecision(abandonSegment, err, dur, RecordProfile.MIN_KEEP_MS))
        } else {
            discardCurrent(err ?: RecordError.BAD_STATE)
        }
        releaseEngine()
        val total = clock.elapsedMs()
        // 段级错误优先；没有时用会话级残留兜底（换段清错过旧段失败码，见 sessionError，
        // 保证"旧段确实失败过"不会从最终 RecordResult 里消失）
        val effective = err ?: sessionError
        val out = if (parts.isEmpty()) RecordResult.fail(effective ?: RecordError.TOO_SHORT, total)
        else buildResult(effective)
        Log.i(TAG, "mr stop total=${total}ms parts=${parts.size} code=${out.error}")
        state = EngineState.IDLE
        engineError = null
        sessionError = null
        abandonSegment = false
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        return out
    }

    /**
     * 显式弃段（[Recorder.abandonCurrentSegment] 契约）：置位后下一次停录（[stop]/[release]）
     * 一律按废片丢弃当前段，不看错误码与时长——静默零帧的段正是"无错误码但没写出数据"的形态。
     */
    override fun abandonCurrentSegment() {
        abandonSegment = true
        Log.i(TAG, "mr abandon current segment requested")
    }

    override fun release() {
        when (state) {
            EngineState.START -> {
                stopEngine()
                val dur = clock.closeSegment()
                // 弃段意图同 stop()：release 也是"停录"，弃段一律不 commit
                sealCurrent(dur, segmentKeepDecision(abandonSegment, engineError, dur, RecordProfile.MIN_KEEP_MS))
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
        sessionError = null
        abandonSegment = false
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

    /**
     * 产出活性健康读数（[streamHealthVerdict] 的输入源）：MediaRecorder 不暴露逐缓冲回调，
     * 三格对它恒 false、[RecorderHealth.milestonesObservable] 恒 false；但它攥着**真实输出 fd**
     * （`applyConfig` 里 `rec.setOutputFile(t.fileDescriptor)`），于是拿"输出文件已落盘字节数"作
     * 产出活性信号——`Os.fstat(fd).st_size` 不需要新权限、不产生额外 IPC，比查 MediaStore 的 SIZE 便宜。
     *
     * 【fd 生命周期】当前段的 `target` 在 prepare/换段时赋值，sealCurrent/discardCurrent/reject
     * 里关 fd 并置空；这里只读它的 fd/路径，不持有、不关闭。
     *
     * 读不到（未挂 target／无 fd 且无真实路径／`Os.fstat` 抛错）一律回 **-1L = 不可观测**，
     * 判定侧据此退回只认错误码（[streamHealthVerdict]），**绝不许当 0 字节判废**——那是误杀。
     *
     * 【线程口径】当前 [health] **只在控制线程**调用：唯一调用方 `RecordRunner.healthCheckLoop` 的
     * 拍子由 `RecordRunner.handler` 投递，与 [prepare]/[start]/[stop] 同在一条 HandlerThread 的
     * 消息队列上。这是**事实（今天就是同线程），不是约定**，所以 [target] 的 `@Volatile` 并非必需；
     * 保留它只是零成本保险——防御将来有人把自检（或别的读者）挪出该线程后，"读到换代 target"
     * 这个隐性前提才会重新出现。
     */
    override fun health(): RecorderHealth {
        val t = target
        val fd = t?.fileDescriptor
        val path = t?.path
        val bytes: Long = when {
            fd != null -> runCatching { Os.fstat(fd).st_size }.getOrDefault(-1L)
            path != null -> runCatching { File(path).length() }.getOrDefault(-1L)
            else -> -1L
        }
        return RecorderHealth(
            errorCode = engineError,
            elapsedMs = if (state == EngineState.START) clock.segmentElapsedMs() else 0L,
            outputBytes = bytes
        )
    }

    // endregion

    // region 分段
    override fun onInfo(rec: MediaRecorder?, what: Int, extra: Int) {
        // 先打日志再判定：日志里保留 what/extra 原值是修复后的自证手段——真机上看到
        // what=802/801（或 what=1 且 extra 带码）后紧跟 "file size event … restartRecord"，
        // 才能证明换段链路活着。历史缺陷：这里曾要求 what==UNKNOWN 才往下走、却在 extra 里找
        // 801/802，判定序反转 ⇒ 只打日志不换段。判定本体在 mrRotateRequested（纯函数，可 JVM 测）。
        Log.i(TAG, "mr info what=$what extra=$extra")
        if (mrRotateRequested(what, extra)) rotateSegment(extra)
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
            // 弃段意图同 stop()：轮转前若被要求弃段，旧段也不 commit
            sealCurrent(dur, segmentKeepDecision(abandonSegment, engineError, dur, RecordProfile.MIN_KEEP_MS))
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
            // 换段成功：新 target 接管本段 fd 的生命周期（本段 stop/discard 时由 sealCurrent/
            // discardCurrent 关闭）。原先这里漏赋值 → 轮转后 target 恒 null、新段 fd 无人关闭（泄漏）；
            // 旧 target 已在上面 sealCurrent 里关闭并置空，这里直接覆写不双关。
            target = t
            partIndex++
            partStartMs = clock.elapsedMs()
            mr?.start()
            state = EngineState.START
            clock.startSegment()
            // P2：新段成功 start 之后清段级错误，否则换段时 stop 抛过一次（如 801 后 MediaRecorder
            // 已自停、再 stop 抛 IllegalState）会把 STOP_FAILED 一直挂着 ⇒ 此后每一拍 health() 都回
            // 非空 errorCode ⇒ MR 路被误判 UNHEALTHY 而把仍在正常录的新段整场重启，且该残留错误还会
            // 让 sealCurrent 把后续已写好的成品段按废片删。清错只影响**新段**的判活：旧段此刻已按
            // keep 语义封好（该弃的已弃）。旧段失败码先挪进 sessionError（会话级账），最终
            // RecordResult 用它兜底——"旧段确实失败过"不会因清错而从结果里消失。
            if (engineError != null) sessionError = engineError
            engineError = null
            abandonSegment = false // 新段起始复位弃段意图（一次判废不许拖累之后每一段）
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
        // setupRecorder 失败走的就是这条：target 里还攥着打开的 fd，置空前必须关
        target?.close()
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
