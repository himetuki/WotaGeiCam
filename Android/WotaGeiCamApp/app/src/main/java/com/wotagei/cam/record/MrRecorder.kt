package com.wotagei.cam.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.SystemClock
import android.system.Os
import com.wotagei.cam.diag.Diag as Log
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
 * stop → 销毁旧实例（release + 置空，下一段由 [ensureRecorder] 新建）→ sleep 500ms →
 * 新 pending uri → 新实例 re-prepare（GPU 路重挂**同一个** persistent 输入面）→ start。
 * 分段是在 MediaRecorder 回调线程里同步做的，会占住该线程约 1s（含 500ms 间隔），
 * 因此 [prepare] 必须在带 Looper 的工作线程调用（不要放主线程）。
 *
 * 【为什么换段必须换实例（2026-10-08 真机根因）】原实现让同一实例反复
 * reset→configure→prepare→start。真机（WIKO GAR-AN60 / Android 11、32MiB 测试阈值 + 50Mbps）
 * 实测：第一段正常、**第二次换段之后彻底停摆**——第 2 次换段在 createPending 之后失败
 * （createPending / open / applyConfig / start 四者之一），旧段已封段 commit、新段连 pending
 * 都没留下，UI 计时冻结且无提示。出货应用 StaCam 的同款分段流程是 stop → release（字段置 null）
 * → 500ms → 新实例 → 重挂输入面 → prepare → start（smali `x5/b.e()`），本类照此口径换代。
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

    /**
     * 当前段的**对外编码面**（[surface] 的取值快照）：首段在 [prepare] 成功后记下，换段在
     * `applyConfig` 成功后与新实例的面比对（见 [rotateSurfaceHandoffNeeded]）。只在控制线程读写。
     */
    private var exposedSurface: Any? = null

    @Volatile private var state = EngineState.IDLE
    @Volatile private var engineError: String? = null
    @Volatile private var rotating = false

    /** 显式弃段意图（[abandonCurrentSegment] 置位，新会话/新段起始复位）：见 [segmentKeepDecision] */
    @Volatile private var abandonSegment = false

    /**
     * 当前段是否收到 801（`MAX_FILESIZE_REACHED`）：平台已自行停录并封口，再 `stop()` 会抛
     * `IllegalStateException`。它是 MR 路**独立的"容器已确认收尾"信号**（见
     * [mrReachedMaxFilesize]），封段时喂给 [segmentKeepDecision]，避免把完整成片按废片删掉。
     * 新会话/新段起始/收尾后复位。
     */
    @Volatile private var reachedMaxFilesize = false

    /**
     * 会话级错误残留：换段成功清 [engineError] 前，把旧段的失败码挪到这里，最终 [RecordResult]
     * 用它兜底（清错只让**新段**恢复判活，绝不把"旧段确实失败过"从结果里抹掉）。
     */
    @Volatile private var sessionError: String? = null

    private val clock = ElapsedClock()
    private val parts = ArrayList<VideoSegment>()
    private var partIndex = 0
    private var partStartMs = 0L

    /**
     * 跨段丢帧累加账（与 Codec 路共用同一个 [RotateLossLedger]，折算口径一致，不另造一套）。
     */
    private val rotateLoss = RotateLossLedger()

    /**
     * 逐段换段丢失账（索引=partIndex，与 Codec 路 `segLostMs` 同款：0 值不落键）。
     * 记的是"这一段开始之前那次换段腿里丢掉的画面"，进 [RecordResult.segLostMs]（sidecar 段清单
     * 由 UI 侧按 partIndex 取，与 Codec 路同一份数据形状）。
     */
    private val segLostMs = ArrayList<Long>()

    /**
     * GPU 模式的持久输入面（API 29 起可用）。**跨分段复用、换段销毁实例也不重建**：
     * 面的 producer 归本类所有，接线方（GPU 的 GL / DIRECT 的相机会话）持有的是同一个对象；
     * 只有会话级 [release] 会释放它。换段时新实例靠 `applyConfig` → [applyInputSurface]
     * 重新把它挂成编码器的输入（挂不上的机型退回报废实例自建面，见 [rotateSurfaceHandoffNeeded]）。
     */
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

    /**
     * 实例的**唯一创建点**（全类只有这里 `MediaRecorder()`）。
     *
     * 线程契约：MediaRecorder 的事件回调走"创建它的那条 Looper"（无参构造取 `Looper.myLooper()`），
     * 所以本函数只能在控制线程（与 prepare/start/stop 同一条 HandlerThread）被调用——换段销毁
     * 实例后 [rotateSegment] 就在该线程上重建，802/801 回调因此仍回到控制线程，单线程口径不变。
     */
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
        reachedMaxFilesize = false
        exposedSurface = null
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        resetRotateBooks()
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
        // 首段的对外编码面：换段时与新一代实例的面比对，判要不要让接线方重挂（见 rotateSurfaceHandoffNeeded）
        exposedSurface = surface
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
     * GPU 模式：GL 渲染结果直接画进编码器面（零拷贝；成片只含曲线/Glow/降噪，
     * 斑马纹/峰值对焦只上屏、不入成片）。
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
        reachedMaxFilesize = false // 新会话起始：801 收尾信号不复用
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
        val err = stopErrorForKeep()
        if (prev == EngineState.START) {
            // 弃段意图（自检判废）压过"看着正常"：见 segmentKeepDecision。
            // 801 自停是 MR 路真实的"容器已确认收尾"信号（平台已封口，再 stop 必抛），
            // 必须传进判据；传恒 false 会把完整段按 STOP_FAILED 删掉。
            sealCurrent(
                dur,
                segmentKeepDecision(
                    abandoned = abandonSegment, ok = true, errorCode = err,
                    durationMs = dur, minKeepMs = RecordProfile.MIN_KEEP_MS,
                    containerFinalized = reachedMaxFilesize
                )
            )
        } else {
            discardCurrent(err ?: RecordError.BAD_STATE)
        }
        releaseEngine()
        val total = clock.elapsedMs()
        // 段级错误优先；没有时用会话级残留兜底（换段清错过旧段失败码，见 sessionError，
        // 保证"旧段确实失败过"不会从最终 RecordResult 里消失）
        val effective = err ?: sessionError
        // 无产物也要把换段丢帧账带上（与 CodecRecorder.finish 同口径：换段受阻时用户唯一能看到的
        // 解释就是"整段丢了 N 秒"，不能因为没成片就把账抹掉）
        val out = if (parts.isEmpty()) RecordResult.fail(effective ?: RecordError.TOO_SHORT, total, rotateLoss.totalMs)
        else buildResult(effective)
        Log.i(TAG, "mr stop total=${total}ms parts=${parts.size} code=${out.error}")
        state = EngineState.IDLE
        engineError = null
        sessionError = null
        abandonSegment = false
        reachedMaxFilesize = false
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        resetRotateBooks()
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
                // 弃段意图同 stop()：release 也是"停录"，弃段一律不 commit。
                // 801 自停是 MR 路真实的"容器已确认收尾"信号，与 stop() 同口径传入。
                sealCurrent(
                    dur,
                    segmentKeepDecision(
                        abandoned = abandonSegment, ok = true,
                        errorCode = stopErrorForKeep(),
                        durationMs = dur, minKeepMs = RecordProfile.MIN_KEEP_MS,
                        containerFinalized = reachedMaxFilesize
                    )
                )
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
        exposedSurface = null
        state = EngineState.IDLE
        engineError = null
        sessionError = null
        abandonSegment = false
        reachedMaxFilesize = false
        clock.reset()
        parts.clear()
        partIndex = 0
        resetRotateBooks()
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
     * 【线程口径】当前 [health] **只在控制线程**调用，有两处调用方：`RecordRunner.healthCheckLoop`
     * （起录自检拍）与 `RecordRunner.engineWatchdogTask`（录制中途引擎判废看门狗），两者的拍子都由
     * `RecordRunner.handler` 投递，与 [prepare]/[start]/[stop] 同在一条 HandlerThread 的消息队列上
     * ——这也是"看门狗看不见换段途中 engineError 瞬时值"的依据。这是**事实（今天就是同线程），
     * 不是约定**，所以 [target] 的 `@Volatile` 并非必需；
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
        if (mrRotateRequested(what, extra)) {
            // 801 = 平台已自行停录并封口：先记下这个收尾事实，封段判据据此放行完整段
            if (mrReachedMaxFilesize(what, extra)) reachedMaxFilesize = true
            rotateSegment(extra)
        }
    }

    override fun onError(rec: MediaRecorder?, what: Int, extra: Int) {
        Log.e(TAG, "mr error what=$what extra=$extra")
        engineError = "${RecordError.ENGINE_ERROR}:$what:$extra"
        state = EngineState.ERROR
    }

    /**
     * restartRecord：stop → **销毁旧实例** → 500ms → 新 pending → 新实例 re-prepare
     * （GPU 路重挂同一个 persistent 输入面）→ start，全程在本回调线程。
     *
     * 换代是 2026-10-08 真机缺陷（第二段之后停摆）的根因修复：同一实例反复
     * reset→configure→prepare→start 在真机上第二轮不可靠，换段一律从零起算；[inputSurface]
     * 是**跨实例**复用的面（producer 归本类），[applyConfig] 会把它重挂到新实例上。
     */
    private fun rotateSegment(extra: Int) {
        if (rotating) {
            // 重入时本腿不处理；801 信号只服务本次封段，不许留到之后的 stop/release
            reachedMaxFilesize = false
            return
        }
        rotating = true
        // 换段腿起钟（`elapsedRealtime`，与 CodecRecorder.beginRotateLeg 同一时基域）：
        // **旧实例被拆的那一刻**就是丢帧窗口的起点（此后进来的帧写不进任何容器），腿里另有
        // 固定的 [SEGMENT_GAP_MS] 间隔——那段时间同样没有编码器，账上如实算进去。0 = 还没起钟。
        var legStart = 0L
        var legRotated = false
        try {
            if (state != EngineState.START) return
            Log.i(TAG, "file size event extra=$extra, restartRecord")
            legStart = SystemClock.elapsedRealtime()
            stopEngine()
            // 销毁旧实例（release + 置空）：下一段由 ensureRecorder 新建。release 异常吞在
            // releaseEngine 里——清理失败不该把换段打断，下一段的成败由 prepare/start 自己报。
            // 顺序不许反：必须在本段 stop 落定之后（文件已完整）、在 applyConfig 之前。
            releaseEngine()
            val dur = clock.closeSegment()
            // 弃段意图同 stop()：轮转前若被要求弃段，旧段也不 commit。
            // 801 自停是 MR 路真实的"容器已确认收尾"信号：它由平台封口后发出，此时 stop()
            // 必抛 STOP_FAILED；不把它传进判据就会把刚写完的整段有效文件按废片删掉。
            timedLegStep("seal") {
                sealCurrent(
                    dur,
                    segmentKeepDecision(
                        abandoned = abandonSegment, ok = true,
                        errorCode = stopErrorForKeep(),
                        durationMs = dur, minKeepMs = RecordProfile.MIN_KEEP_MS,
                        containerFinalized = reachedMaxFilesize
                    )
                )
            }
            Thread.sleep(SEGMENT_GAP_MS)
            val next = timedLegStep("insert") { store.createPending(partIndex + 1) }
                ?: return failNow("${RecordError.ENGINE_ERROR}:ROTATE_NO_OUTPUT", dropOutput = false)
            val sink = OutputSink.Pending(next)
            val t = timedLegStep("open") { OutputTarget.open(ctx, sink) }
            val p = profile
            if (t == null || p == null) {
                store.discard(sink) // 新 pending 没被写过，必须立刻回收
                return failNow(RecordError.NO_OUTPUT, dropOutput = false)
            }
            val audio = p.audioUsable && AudioProbe.hasRecordPermission(ctx) &&
                AudioProbe.isSampleRateSupported(p.sampleRate, p.channels)
            if (!timedLegStep("prepare") { applyConfig(ensureRecorder(), p, t, audio, encoderOf(p), sink) }) {
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
            // 【换段面交接】新实例的对外编码面若与上一段不是同一个对象，接线方必须重新挂好，
            // 否则帧进不去、下一段静默零帧：
            // - DIRECT：面来自实例（getSurface()，producer 属实例），换代后相机会话还绑在旧实例上；
            // - GPU 的 setInputSurface 退回路（个别 ROM 拒绝 persistent 面）：面同样随实例换代，
            //   GL 还画在旧面上。
            // 默认档（GPU + persistent 面被接受）面跨实例不变 ⇒ 恒不回调 ⇒ GL/相机侧零改动。
            // 必须早于 mr?.start()（与首段"先挂牌再 start"同口径），回调异常就地吞掉不折断换段。
            val exposed = surface
            if (rotateSurfaceHandoffNeeded(exposedSurface, exposed) && exposed != null) {
                Log.i(TAG, "segment surface changed, handoff to caller")
                runCatching { onInputSurfaceRecreated?.invoke(exposed) }
                    .onFailure { Log.w(TAG, "segment surface handoff threw: ${it.message}") }
            }
            exposedSurface = exposed
            mr?.start()
            state = EngineState.START
            clock.startSegment()
            legRotated = true
            // P2：新段成功 start 之后清段级错误，否则换段时 stop 抛过一次（如 801 后 MediaRecorder
            // 已自停、再 stop 抛 IllegalState）会把 STOP_FAILED 一直挂着 ⇒ 此后每一拍 health() 都回
            // 非空 errorCode ⇒ MR 路被误判 UNHEALTHY 而把仍在正常录的新段整场重启，且该残留错误还会
            // 让 sealCurrent 把后续已写好的成品段按废片删。清错只影响**新段**的判活：旧段此刻已按
            // keep 语义封好（该弃的已弃）。801 自停产生的 STOP_FAILED 是正常收尾，不算旧段失败，
            // 其余旧段失败码仍先挪进 sessionError（会话级账），最终 RecordResult 用它兜底。
            val oldError = stopErrorForKeep()
            if (oldError != null) sessionError = oldError
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
            // 无论本腿成功还是中途失败都结算：失败腿丢掉的时间同样是"用户没录到的画面"，
            // 不记账就等于把现场抹掉（S2-M4-01 的取证缺口正是这里）。
            settleRotateLeg(legStart, legRotated)
            // 801 信号只服务本腿封段：无论成败都在腿末复位，不许泄漏到下一条腿/下一次 stop
            reachedMaxFilesize = false
            rotating = false
        }
    }

    /**
     * 封段判据使用的错误码：801 自停后的 `STOP_FAILED` 是平台正常收尾（不是坏容器），
     * 对判据按"无错误"处理；其余错误码原样透传。
     */
    private fun stopErrorForKeep(): String? =
        if (reachedMaxFilesize && engineError == RecordError.STOP_FAILED) null else engineError

    /**
     * 换段腿里一步**无上限**调用的耗时留痕（`elapsedRealtime`）。
     *
     * 这四步（封段入库 / 建 pending / 开输出 fd / prepare）都会走 MediaProvider、FUSE 或 native，
     * 而 MR 路**没有** [BoundedStore] 那层有界化（本轮只留痕、不改执行，见 S2-M4-01）：
     * 卡住时原先连一行耗时证据都没有，而本机的换段慢是 30~45s 量级（`RotateGuard` 类注现场），
     * 没有逐腿耗时就无法分辨"卡在哪一步"。
     *
     * 统一行首 `mr rotate leg step=` ⇒ 真机日志可一条 grep 出整腿的四步耗时。
     * 超 [STORE_CALL_BUDGET_MS]（引擎侧有界预算的同一把尺）另打一条 warn，与 Codec 路留痕同口径。
     */
    private inline fun <T> timedLegStep(step: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val costMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "mr rotate leg step=$step cost=${costMs}ms")
            if (costMs > STORE_CALL_BUDGET_MS) {
                Log.w(TAG, "mr rotate leg step=$step cost=${costMs}ms 超过 ${STORE_CALL_BUDGET_MS}ms" +
                    "（MediaProvider/FUSE 同步调用无上限；本轮只留痕不改执行，见 S2-M4-01）")
            }
        }
    }

    /**
     * 换段腿结算（只记账，**不判失败**）。
     *
     * 与 [CodecRecorder] 的 `settleRotateLeg` 同口径：腿耗时扣掉良性开销 [ROTATE_BENIGN_MS] 之后
     * 就是"这段时间的画面确实没录到"，进累计账与逐段账。**刻意不接** [rotateStallDecision] 那个
     * 致命闸——MR 腿无界化属行为变更、留待另案裁决（M4-03 联动），本轮慢腿只进账不停录、不置
     * [RecordError.ROTATE_STALL]，以免把"取证"变成"行为变更"。
     *
     * @param legRotated 本腿是否真的换出了新段：只有换成了新段才有"下一段"可挂这条账（逐段账索引
     *   必须与 partIndex 严格对齐）；中途失败的腿只进累计账。
     */
    private fun settleRotateLeg(legStart: Long, legRotated: Boolean) {
        if (legStart <= 0L) return
        val legMs = SystemClock.elapsedRealtime() - legStart
        val lost = rotateLostMs(legMs)
        rotateLoss.add(lost)
        if (legRotated) {
            // 逐段账的索引必须与 partIndex 对齐（与 CodecRecorder 的 segLostMs 同一口径）：首段之前
            // 没有换段腿 ⇒ 该位补 0；此后每换出一段，把本腿的丢失追加到**新段**那一位上。
            while (segLostMs.size < partIndex) segLostMs.add(0L)
            segLostMs.add(lost)
        }
        if (lost > 0L) {
            Log.w(
                TAG,
                "mr rotate leg total=${legMs}ms（良性开销 ${ROTATE_BENIGN_MS}ms），丢帧约 ${lost}ms；" +
                    "累计 ${rotateLoss.totalMs}ms/${rotateLoss.count} 次（只记账不判失败）"
            )
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

    /**
     * 结果口径与 [CodecRecorder.buildResult] **同字段**（2026-10-10 S2-M4-01）：换段丢帧账
     * （逐段 [segLostMs]、累计 [RecordResult.lostMs]、次数 [RecordResult.rotateStalls]）原先
     * MR 路一个都不填 ⇒ 换段慢时结果里零证据、UI 与 `rotate-fail.log` 也无从落账，与 Codec 路
     * 逐字同源（同一个 [RotateLossLedger]、同一套折算）。
     *
     * 其余字段的差异是**引擎实情**、不是缺口：MR 路不拆 muxer（`latePending`/`segmentRestarts`/
     * `muxDiag` 恒空/0/""），也不数视频样本位次（`segVideoSamples` 恒空表，转换会话恒走 Codec）。
     */
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
            uncommitted = parts.count { !it.committed },
            segLostMs = segLostMs.toList(),
            lostMs = rotateLoss.totalMs,
            rotateStalls = rotateLoss.count
        )
    }

    /** 会话级换段账复位（新会话与收尾共用）：账不许漏进下一次录制，与 parts 的清账点同处 */
    private fun resetRotateBooks() {
        segLostMs.clear()
        rotateLoss.clear()
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

    /**
     * 只 stop+reset，不 release：stop 之后该文件即完整可播（分段与停止共用）。
     * **实例生死由调用方紧跟处理**：停止与换段都在本函数之后调 [releaseEngine]——
     * 换段必须换代（同一实例第二轮 reset→configure→prepare 在真机上不可靠，见类注）。
     */
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
