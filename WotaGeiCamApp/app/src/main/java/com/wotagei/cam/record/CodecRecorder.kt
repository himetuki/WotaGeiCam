package com.wotagei.cam.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.wotagei.cam.core.WotaTiers
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 自研管线：`MediaCodec + MediaMuxer + AudioRecord`（04 文件 §3：fps > 60 的高速路径）。
 *
 * 关键点：
 * - 输入面用 `MediaCodec.createPersistentInputSurface()`（API 29 起），跨分段复用不重建，GL 只认同一面；
 *   编码器用 `configure(format, surface, …)` 绑定（`setInputSurface` 是 API 30，minSdk 29 不依赖它）；
 * - 视频参数：COLOR_FormatSurface + BITRATE_MODE_VBR + KEY_I_FRAME_INTERVAL=1 + KEY_FRAME_RATE=fps；
 * - 音频参数：`audio/mp4a-latm` + AAC-LC + 128k + CHANNEL_COUNT + KEY_MAX_INPUT_SIZE=minBufferSize，
 *   PTS 用 `System.nanoTime()/1000`，EOS 走 PCM 同一通道的 4 字节哨兵（``）；
 * - **Muxer 只在「视频轨就绪 且（静音或音频轨就绪）」时 start()**；
 * - 停止顺序：codec.stop/release → muxer.stop/release → AudioRecord.stop/release。
 */
class CodecRecorder(
    private val ctx: Context,
    private val store: VideoStore
) : Recorder {

    companion object {
        /** 输出侧轮询粒度 */
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        /** EOS 之后允许编码器吐完缓存的上限，超了就强拆（防 stop 卡死） */
        private const val DRAIN_LIMIT_MS = 1_200L
        private const val LOOP_JOIN_MS = 2_500L
        /** 分段阈值余量：估算写量不含容器结构开销，提前 2% 换段 */
        private const val ROTATE_SAFETY = 0.98
        /** MediaFormat KEY_I_FRAME_INTERVAL 单位是秒 */
        private const val I_FRAME_INTERVAL_SEC = 1
        /** 延时摄影专用键（04 文件 §3） */
        private const val KEY_CAPTURE_RATE = "capture-rate"
        /** BufferQueue 模式下的历史返回值，仍需吞掉（API 29 起标废弃，故不引用常量） */
        private const val INFO_OUTPUT_BUFFERS_CHANGED = -3
    }

    private var inputSurface: Surface? = null
    private var vCodec: MediaCodec? = null
    private var aCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var target: OutputTarget? = null
    private var currentSink: OutputSink? = null
    private var feeder: AudioFeeder? = null
    private var profile: RecordProfile? = null

    /** 本段两轨共用的 PTS 基准（μs，与 nanoTime 同域），避免分段后时间戳倒退 */
    private var segBaseUs = 0L
    private var aacMinBuf = 0
    private var audioSource = 0
    private var heldPkt: AudioFeeder.Packet? = null
    private var lastAudioPtsUs = 0L

    @Volatile private var state = EngineState.IDLE
    @Volatile private var engineError: String? = null

    /** stop() 置位：循环给两轨发 EOS 后收尾 */
    @Volatile private var stopping = false

    /** 循环内写量估算触顶后置位：封当前段并接新 pending */
    @Volatile private var rotatePending = false

    private var loop: Thread? = null
    private val clock = ElapsedClock()
    private val parts = ArrayList<VideoSegment>()
    private var partIndex = 0
    private var partStartMs = 0L

    /** prepare 成功后即稳定可用：Camera2 录像面 / GL outputSurface 的写入目标 */
    override val surface: Any? get() = inputSurface

    // region 准备

    override fun prepare(p: RecordProfile, sink: OutputSink): Boolean {
        if (state != EngineState.IDLE) {
            Log.e(TAG, "codec prepare ignored, state=$state")
            return false
        }
        state = EngineState.PREPARE
        engineError = null
        stopping = false
        rotatePending = false
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        heldPkt = null
        clock.reset()
        store.checkFreeSpace()?.let {
            reject(sink, it)
            return false
        }
        val t = OutputTarget.open(ctx, sink)
        if (t == null || !t.usable) {
            reject(sink, RecordError.NO_OUTPUT)
            return false
        }
        val inSurf = inputSurface ?: MediaCodec.createPersistentInputSurface().also { inputSurface = it }
        val mime = resolveVideoMime(p)
        val v = createVideoEncoder(mime, p, inSurf)
        if (v == null) {
            closeSegmentEngine()
            reject(sink, RecordError.PREPARE_FAILED)
            return false
        }
        vCodec = v
        // 静音 / 无权限 / 采样率不支持 → 完全不初始化音频通路
        val minBuf = if (p.audioUsable && AudioProbe.hasRecordPermission(ctx))
            AudioProbe.minBufferSize(p.sampleRate, p.channels) else 0
        aacMinBuf = if (minBuf > 0) minBuf else 0
        audioSource = AudioProbe.pickSource(ctx)
        val a = if (minBuf > 0) createAudioEncoder(p, minBuf) else null
        if (a == null && p.audioUsable)
            Log.i(TAG, "audio dropped on codec path: sr=${p.sampleRate} ch=${p.channels} minBuf=$minBuf")
        aCodec = a
        if (a == null) aacMinBuf = 0 // 分段重启时不再尝试音频，避免空转
        profile = if (a == null && p.audioEnabled) p.copy(audioEnabled = false) else p
        target = t
        currentSink = sink
        if (!openMuxer(t, p.orientationHint)) {
            closeSegmentEngine()
            reject(sink, RecordError.NO_OUTPUT)
            return false
        }
        Log.i(TAG, "codec prepared part=$partIndex ${p.width}x${p.height}@${p.fps} mime=$mime " +
            "bps=${p.effectiveBitrate()} audio=${if (a == null) "off" else "${p.sampleRate}Hz/${p.channels}ch"} " +
            "hint=${p.orientationHint} gpu=${p.useGpu} maxFile=${WotaTiers.MAX_FILE_BYTES}")
        return true
    }

    /** HEVC 只在「存在支持 Surface 输入且吃下该分辨率的编码器」时用，否则回退 H264 */
    private fun resolveVideoMime(p: RecordProfile): String {
        if (!p.hevc) return MediaFormat.MIMETYPE_VIDEO_AVC
        val name = findSurfaceEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, p.width, p.height)
        if (name == null) {
            Log.i(TAG, "no surface-capable hevc encoder for ${p.width}x${p.height}, fallback avc")
            return MediaFormat.MIMETYPE_VIDEO_AVC
        }
        return MediaFormat.MIMETYPE_VIDEO_HEVC
    }

    /** 能力一律运行时读（AGENTS.md：禁止硬编码机型数值） */
    private fun findSurfaceEncoder(mime: String, w: Int, h: Int): String? {
        val infos = try {
            @Suppress("DEPRECATION")
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: Exception) {
            Log.e(TAG, "codec list unavailable: ${e.message}")
            return null
        }
        for (info in infos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val ok = try {
                caps.isFormatSupported(MediaFormat.createVideoFormat(mime, w, h))
            } catch (e: Exception) {
                false
            }
            if (ok) return info.name
        }
        return null
    }

    /**
     * 找一枚"能吃 YUV420 ByteBuffer 输入"的编码器，顺带给出它接受的颜色格式。
     *
     * 与 [findSurfaceEncoder] 分开命名（不改旧名以免动既有调用点）是因为两者探测的条件不同：
     * Surface 路线要 `COLOR_FormatSurface`，YUV 路线要 `COLOR_FormatYUV420*`，一类机型的两个能力
     * 不一定出现在同一枚编码器上（真机实测就有只吐 Flexible、不给 Surface 的实现）。
     *
     * 颜色格式按「Flexible → SemiPlanar → Planar」优先级挑：**Flexible 优先**是因为只有它保证
     * `getInputImage()` 能拿到带合法 rowStride/pixelStride 的 Image，从而不必猜内存布局；
     * 后两者只能在 `getInputBuffer()` 上手写字节序，是最差情况下的退路。
     */
    private fun findYuvEncoder(mime: String, w: Int, h: Int): Pair<String, Int>? {
        val infos = try {
            @Suppress("DEPRECATION")
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: Exception) {
            Log.e(TAG, "codec list unavailable: ${e.message}")
            return null
        }
        val priority = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        for (info in infos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (e: IllegalArgumentException) {
                continue
            }
            // 先过分辨率闸：吃不下的编码器即便自称支持 mime 也不能用
            val ok = try {
                caps.isFormatSupported(MediaFormat.createVideoFormat(mime, w, h))
            } catch (e: Exception) {
                false
            }
            if (!ok) continue
            val cf = priority.firstOrNull { caps.colorFormats.contains(it) } ?: continue
            return info.name to cf
        }
        return null
    }

    /**
     * 建一枚 YUV420 ByteBuffer 输入的编码器（能力探测走 [findYuvEncoder]）。
     *
     * **为什么单独开一个方法而不是复用 [createVideoEncoder]**：录制与 GPU 路线走 Surface 输入，
     * 帧的时间戳由输入面的生产者按系统墙钟生成，代码给不了 PTS；光弧修复的 **CPU 路线**要把逐平面
     * 混合结果直接写进编码器输入缓冲并要求**自己指定 PTS**，只能走 ByteBuffer 模式（surface 传 null）。
     * 真机教训：曾用 `Surface.lockCanvas()` 往输入面写帧，1.875s 的素材 CPU 处理了 41s，容器里的
     * 帧步长就被写成 ≈0.46s（21 倍慢放）——这不是"处理快一点"能绕过的，必须换成显式 PTS。
     *
     * 除 `KEY_COLOR_FORMAT` 外，其余参数与 [createVideoEncoder] **逐字相同**：两条路线的产物码率/画质
     * 必须同口径，否则同一素材走 GPU 与 CPU 会得到两种画质（AGENTS.md：不为同一件事写第二份口径）。
     */
    internal fun createYuvEncoder(mime: String, p: RecordProfile): MediaCodec? {
        val found = findYuvEncoder(mime, p.width, p.height)
        if (found == null) {
            Log.e(TAG, "no yuv420 encoder for $mime ${p.width}x${p.height}")
            return null
        }
        val (name, colorFormat) = found
        val codec = try {
            MediaCodec.createByCodecName(name)
        } catch (e: IOException) {
            Log.e(TAG, "yuv encoder create failed ($name): ${e.message}")
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "bad yuv encoder name ($name): ${e.message}")
            return null
        }
        val fmt = MediaFormat.createVideoFormat(mime, p.width, p.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            setInteger(MediaFormat.KEY_BIT_RATE, p.effectiveBitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            if (p.timeLapse) setFloat(KEY_CAPTURE_RATE, p.captureRate)
        }
        return try {
            // surface = null：这就是与 Surface 路线的唯一差别，PTS 交回调用方掌控
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            Log.e(TAG, "yuv video configure failed: ${e.javaClass.simpleName} ${e.message}")
            releaseQuietly(codec)
            null
        }
    }

    /**
     * 建一枚 Surface 输入的编码器（能力探测走 [findSurfaceEncoder]，参数表与录制逐字相同）。
     * `internal` 供 ArcRepair 复用：光弧修复的插帧编码器与录制必须同一套能力探测与参数口径，
     * 复制一份迟早与录制分叉（AGENTS.md：不为同一件事写第二份）。
     *
     * @param surface 输入面；**传 null = 只 configure 不绑面**，调用方随后自行
     *   `codec.createInputSurface()` 取它自己的输入面。ArcRepair 走这条：MediaCodec 的
     *   `createPersistentInputSurface()` 是给 MediaRecorder 共享输入面的专用件，拿它当 EGL 渲染
     *   目标在真机上 `eglCreateWindowSurface` 直接失败（EGL_BAD_ALLOC 0x3003，实测）。录制路照旧
     *   传自己的输入面，行为逐字不变。
     */
    internal fun createVideoEncoder(mime: String, p: RecordProfile, surface: Surface?): MediaCodec? {
        val name = findSurfaceEncoder(mime, p.width, p.height)
        val codec = try {
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(mime)
        } catch (e: IOException) {
            Log.e(TAG, "no video encoder for $mime: ${e.message}")
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "bad encoder name: ${e.message}")
            return null
        }
        val fmt = MediaFormat.createVideoFormat(mime, p.width, p.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            setInteger(MediaFormat.KEY_BIT_RATE, p.effectiveBitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            if (p.timeLapse) setFloat(KEY_CAPTURE_RATE, p.captureRate)
        }
        return try {
            codec.configure(fmt, surface, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            Log.e(TAG, "video configure failed: ${e.javaClass.simpleName} ${e.message}")
            releaseQuietly(codec)
            null
        }
    }

    private fun createAudioEncoder(p: RecordProfile, minBuf: Int): MediaCodec? {
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            Log.e(TAG, "no aac encoder: ${e.message}")
            return null
        }
        val fmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, p.sampleRate, p.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, p.audioBitrate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, p.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf) // = AudioRecord minBufferSize
        }
        return try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            Log.e(TAG, "audio configure failed: ${e.message}")
            releaseQuietly(codec)
            null
        }
    }

    private fun openMuxer(t: OutputTarget, hint: Int): Boolean {
        val m = try {
            val fd = t.fileDescriptor
            if (fd != null) MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else {
                val path = t.path ?: return false
                MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
        } catch (e: Exception) {
            Log.e(TAG, "muxer open failed: ${e.message}")
            null
        } ?: return false
        // MediaMuxer 侧的旋转标记（旋转节）；GL 已按方向画，所以这里只做容器元数据
        try {
            m.setOrientationHint(hint)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "muxer orientation hint ignored: ${e.message}")
        }
        muxer = m
        return true
    }

    // endregion

    // region 启停

    override fun start() {
        if (state != EngineState.PREPARE) {
            Log.e(TAG, "codec start ignored, state=$state")
            return
        }
        // 音频采集与编码器同刻启动，避免 PCM 积压落到下一个 PTS 基准上
        if (aCodec != null && feeder == null) startFeederOrDropAudio()
        try {
            vCodec?.start()
            aCodec?.start()
        } catch (e: Exception) {
            Log.e(TAG, "codec start failed: ${e.message}")
            engineError = RecordError.START_FAILED
            state = EngineState.ERROR
            closeSegmentEngine()
            stopFeeder()
            discardCurrent(RecordError.START_FAILED)
            state = EngineState.IDLE
            return
        }
        segBaseUs = System.nanoTime() / 1000L
        state = EngineState.START
        partStartMs = clock.elapsedMs()
        clock.startSegment()
        stopping = false
        rotatePending = false
        loop = Thread({ pumpLoop() }, "WotaCodecMux").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        Log.i(TAG, "codec start part=$partIndex from=${partStartMs}ms audio=${if (feeder == null) "off" else "on"}")
    }

    private fun startFeederOrDropAudio() {
        val p = profile ?: return
        val f = AudioFeeder(ctx, p.sampleRate, p.channels, audioSource, aacMinBuf)
        if (f.start()) {
            feeder = f
            return
        }
        // 起不来就整条音频通路撤掉：视频继续（的静默降级）
        f.stop()
        releaseQuietly(aCodec)
        aCodec = null
        Log.i(TAG, "audio feeder unavailable, continue video only")
    }

    override fun stop(): RecordResult {
        val prev = state
        if (prev == EngineState.IDLE) return finish(engineError)
        if (prev != EngineState.START) {
            // PREPARE / ERROR：一帧都没写，直接回收 pending
            stopping = true
            joinLoop()
            closeSegmentEngine()
            stopFeeder()
            discardCurrent(engineError ?: RecordError.BAD_STATE)
            return finish(engineError ?: RecordError.BAD_STATE)
        }
        state = EngineState.STOPPING
        stopping = true
        joinLoop() // 循环内完成 EOS → 抽干 → 封段 → 提交
        closeSegmentEngine()
        stopFeeder() // AudioRecord 的 stop/release 固定排在 codec、muxer 之后
        return finish(engineError)
    }

    override fun release() {
        if (state == EngineState.START || state == EngineState.STOPPING) stop()
        stopping = true
        joinLoop()
        closeSegmentEngine()
        stopFeeder()
        val s = inputSurface
        inputSurface = null
        s?.release()
        state = EngineState.IDLE
        engineError = null
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        Log.i(TAG, "codec released")
    }

    override val elapsedMs: Long get() = clock.elapsedMs()

    /** PCM 均方根；量程与 MediaRecorder.getMaxAmplitude 一致，UI 音量表两引擎通用 */
    override fun amplitude(): Int = feeder?.amplitude() ?: 0

    // endregion

    // region 编码器循环

    private fun pumpLoop() {
        var keepGoing = true
        while (keepGoing) {
            val ok = try {
                runSegment()
            } catch (e: Exception) {
                Log.e(TAG, "mux loop threw: ${e.javaClass.simpleName} ${e.message}")
                engineError = engineError ?: "${RecordError.ENGINE_ERROR}:LOOP"
                false
            }
            val dur = clock.closeSegment()
            // 先拆 codec/muxer（moov 写完）再封段提交，顺序反了相册会拿到半截文件
            closeSegmentEngine()
            sealCurrent(dur, ok && engineError == null && dur >= RecordProfile.MIN_KEEP_MS)
            keepGoing = when {
                !ok || engineError != null -> false
                !rotatePending -> false
                else -> {
                    rotatePending = false
                    val ready = nextSegment()
                    if (!ready) engineError = engineError ?: "${RecordError.ENGINE_ERROR}:ROTATE"
                    ready
                }
            }
        }
        if (engineError != null) Log.i(TAG, "codec loop end with ${engineError}")
    }

    /** 分段：换 pending uri、重建 codec+muxer（输入面与采集线程沿用），并在本线程内重启 */
    private fun nextSegment(): Boolean {
        val uri = store.createPending(partIndex + 1) ?: return false
        val sink = OutputSink.Pending(uri)
        val ready = openNextSegment(sink)
        if (!ready) {
            store.discard(sink) // 没写进去的 pending 立刻回收，避免幽灵条目
            target?.close()
            target = null
        }
        return ready
    }

    private fun openNextSegment(sink: OutputSink.Pending): Boolean {
        val p = profile ?: return false
        val inSurf = inputSurface ?: return false
        val t = OutputTarget.open(ctx, sink) ?: return false
        val v = createVideoEncoder(resolveVideoMime(p), p, inSurf) ?: return false
        vCodec = v
        if (aacMinBuf > 0) {
            val a = createAudioEncoder(p, aacMinBuf)
            aCodec = a
            if (a == null) stopFeeder() // 音频编码器重建失败：同步停采集，避免 PCM 队列空转
        }
        if (!openMuxer(t, p.orientationHint)) {
            t.close()
            return false
        }
        target = t
        currentSink = sink
        heldPkt = null
        feeder?.drain() // 丢弃上一段积压，避免旧 PCM 落进新文件造成 PTS 倒退
        return try {
            v.start()
            aCodec?.start()
            segBaseUs = System.nanoTime() / 1000L
            partIndex++
            partStartMs = clock.elapsedMs()
            clock.startSegment()
            Log.i(TAG, "codec segment $partIndex started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "segment restart failed: ${e.message}")
            false
        }
    }

    /**
     * 单段循环：喂 PCM → 抽两轨写 muxer → 处理停止/分段。
     * Muxer 启动条件严格守住「视频轨就绪 且（静音或音频轨就绪）」。
     * @return false = 本段没等到视频轨，或中途出错
     */
    private fun runSegment(): Boolean {
        val v = vCodec ?: return false
        val a = aCodec
        val mx = muxer ?: return false
        val muted = a == null
        val bi = MediaCodec.BufferInfo()
        var videoTrack = -1
        var audioTrack = -1
        var muxStarted = false
        var videoDone = false
        var audioDone = muted
        var videoEosQueued = false
        var audioEosQueued = false
        var writtenBytes = 0L
        var deadline = 0L
        // 两轨各自最后一段的媒体时长（已按段基准归一），用来在一行日志里看出轨间与墙钟的漂移
        var vPtsLastUs = 0L
        var aPtsLastUs = 0L
        val segStartNs = System.nanoTime()

        while (true) {
            if (a != null && !audioDone && !audioEosQueued) feedAudioInput(a)
            if (!videoDone) {
                val idx = v.dequeueOutputBuffer(bi, DEQUEUE_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = mx.addTrack(v.getOutputFormat())
                        if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, audioTrack, muted)
                        Log.i(TAG, "video track=$videoTrack fmt=${v.getOutputFormat()}")
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    idx >= 0 -> {
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (muxStarted && !eos && bi.size > 0) {
                            writtenBytes += writeSample(mx, videoTrack, v.getOutputBuffer(idx), bi)
                            vPtsLastUs = bi.presentationTimeUs
                        }
                        v.releaseOutputBuffer(idx, false)
                        if (eos) videoDone = true
                    }
                }
            }
            if (a != null && !audioDone) {
                val idx = a.dequeueOutputBuffer(bi, DEQUEUE_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        audioTrack = mx.addTrack(a.getOutputFormat())
                        if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, audioTrack, muted)
                        Log.i(TAG, "audio track=$audioTrack")
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    idx >= 0 -> {
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (muxStarted && !eos && bi.size > 0) {
                            writtenBytes += writeSample(mx, audioTrack, a.getOutputBuffer(idx), bi)
                            aPtsLastUs = bi.presentationTimeUs
                        }
                        a.releaseOutputBuffer(idx, false)
                        if (eos) audioDone = true
                    }
                }
            }
            if ((stopping || rotatePending) && deadline == 0L) {
                if (!muxStarted) {
                    Log.e(TAG, "stop before muxer started, give up segment")
                    engineError = engineError ?: RecordError.NO_VIDEO_TRACK
                    break
                }
                deadline = SystemClock.elapsedRealtime() + DRAIN_LIMIT_MS
                if (!videoEosQueued) {
                    v.signalEndOfInputStream() // 4 字节哨兵只在音频通道，视频走 input surface 的 EOS
                    videoEosQueued = true
                }
                if (a != null && !audioEosQueued) {
                    queueAudioEos(a)
                    audioEosQueued = true
                }
            }
            if (videoDone && audioDone) break
            if (deadline > 0L && SystemClock.elapsedRealtime() > deadline) {
                Log.e(TAG, "drain timeout video=$videoDone audio=$audioDone")
                engineError = engineError ?: "${RecordError.ENGINE_ERROR}:DRAIN"
                break
            }
            if (!rotatePending && writtenBytes >= (WotaTiers.MAX_FILE_BYTES * ROTATE_SAFETY).toLong()) {
                Log.i(TAG, "written=$writtenBytes reached MAX_FILE_BYTES limit, rotate")
                rotatePending = true
            }
        }
        if (videoTrack < 0) {
            Log.e(TAG, "no video track produced in segment $partIndex")
            engineError = engineError ?: RecordError.NO_VIDEO_TRACK
            return false
        }
        // 三数并排给出一行即可判定漂移：视频/音频轨时长互为轨间偏移，与墙钟差即整体滑移
        // （本机无 >60fps 能力，这条路径的音画同步只能靠日志取证，见 docs/plan/10 §19 与任务 #33）
        Log.i(
            TAG,
            "segment $partIndex drained video=$videoDone audio=$audioDone bytes=$writtenBytes " +
                "视频=${vPtsLastUs / 1000f}ms 音频=${aPtsLastUs / 1000f}ms " +
                "墙钟=${(System.nanoTime() - segStartNs) / 1_000_000f}ms"
        )
        return true
    }

    /** 两轨都 addTrack 完才 start：这是自研管线最容易做错的一步 */
    private fun startMuxerIfReady(mx: MediaMuxer, videoTrack: Int, audioTrack: Int, muted: Boolean): Boolean {
        if (videoTrack < 0) return false
        if (!muted && audioTrack < 0) return false
        return try {
            mx.start()
            Log.i(TAG, "muxer start video=$videoTrack audio=${if (muted) "none" else "$audioTrack"}")
            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "muxer start failed: ${e.message}")
            false
        }
    }

    /** PTS 统一到本段基准（两轨同一时钟，分段后不出现倒退） */
    private fun writeSample(mx: MediaMuxer, track: Int, buf: ByteBuffer?, info: MediaCodec.BufferInfo): Int {
        if (track < 0 || buf == null) return 0
        val shifted = info.presentationTimeUs - segBaseUs
        if (shifted < 0L) {
            // 基准取的是换段瞬间的 nanoTime，音频包可能在那之前就已入队：夹紧会把这几帧往前挪，
            // 量级必须能在日志里看到，否则分段处的音画偏移无从判断（本机无高帧率能力，改语义前先取证）
            Log.w(TAG, "段 $partIndex pts 早于基准 ${-shifted}us，已夹紧 track=$track")
            info.presentationTimeUs = 0L
        } else {
            info.presentationTimeUs = shifted
        }
        return try {
            mx.writeSampleData(track, buf, info)
            info.size
        } catch (e: IllegalStateException) {
            Log.e(TAG, "writeSampleData failed: ${e.message}")
            engineError = engineError ?: "${RecordError.ENGINE_ERROR}:MUX"
            0
        }
    }

    /** 消费 PCM 通道：取包 → 编码器输入；4 字节包即 EOS 哨兵（``） */
    private fun feedAudioInput(codec: MediaCodec) {
        val f = feeder ?: return
        val pkt = heldPkt ?: f.poll(0) ?: return
        val idx = codec.dequeueInputBuffer(0)
        if (idx < 0) {
            heldPkt = pkt // 编码器没空位：本包留到下一轮，绝不丢帧
            return
        }
        heldPkt = null
        // EOS 判定看 pts<0（真实包的时间戳恒为 nanoTime µs、恒正）：哨兵 data 恰 4 字节
        // 与「双声道 2 帧的合法短读」同形，只看长度会把真 PCM 误判成 EOS 提前掐断音轨
        if (pkt.ptsUs < 0 || pkt.data.size == AudioFeeder.EOS_SIZE) {
            queueEosOn(codec, idx, eosPts())
            return
        }
        val buf = codec.getInputBuffer(idx) ?: return
        val len = minOf(pkt.data.size, buf.capacity())
        buf.clear()
        buf.put(pkt.data, 0, len)
        try {
            codec.queueInputBuffer(idx, 0, len, pkt.ptsUs, 0)
            lastAudioPtsUs = pkt.ptsUs
        } catch (e: IllegalStateException) {
            Log.e(TAG, "audio queueInput failed: ${e.message}")
        }
    }

    private fun queueAudioEos(codec: MediaCodec) {
        val idx = codec.dequeueInputBuffer(0)
        if (idx < 0) return
        queueEosOn(codec, idx, eosPts())
    }

    private fun queueEosOn(codec: MediaCodec, idx: Int, ptsUs: Long) {
        try {
            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "audio eos queue failed: ${e.message}")
        }
    }

    private fun eosPts(): Long =
        if (lastAudioPtsUs > 0L) lastAudioPtsUs else System.nanoTime() / 1000L

    // endregion

    // region 收尾

    private fun joinLoop() {
        val t = loop
        loop = null
        if (t == null) return
        try {
            t.join(LOOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (t.isAlive) Log.e(TAG, "mux loop alive after ${LOOP_JOIN_MS}ms")
    }

    private fun stopFeeder() {
        feeder?.stop()
        feeder = null
    }

    /** 固定停止顺序（04 文件 §3）：codec.stop/release → muxer.stop/release */
    private fun closeSegmentEngine() {
        val v = vCodec
        if (v != null) {
            try {
                v.stop()
            } catch (e: IllegalStateException) {
                Log.i(TAG, "video codec stop ignored")
            } catch (e: RuntimeException) {
                Log.e(TAG, "video codec stop threw: ${e.message}")
            }
            releaseQuietly(v)
            vCodec = null
        }
        val a = aCodec
        if (a != null) {
            try {
                a.stop()
            } catch (e: IllegalStateException) {
                Log.i(TAG, "audio codec stop ignored")
            } catch (e: RuntimeException) {
                Log.e(TAG, "audio codec stop threw: ${e.message}")
            }
            releaseQuietly(a)
            aCodec = null
        }
        val m = muxer
        if (m != null) {
            try {
                m.stop()
            } catch (e: IllegalStateException) {
                // 轨没对齐/没 start 过（start 前失败）时 stop 必抛：本段按废片处理
                Log.i(TAG, "muxer stop ignored (not started or incomplete)")
            }
            releaseQuietly(m)
            muxer = null
        }
        heldPkt = null
    }

    private fun releaseQuietly(c: MediaCodec?) {
        try {
            c?.release()
        } catch (e: RuntimeException) {
            Log.e(TAG, "codec release threw: ${e.message}")
        }
    }

    private fun releaseQuietly(m: MediaMuxer?) {
        try {
            m?.release()
        } catch (e: RuntimeException) {
            Log.e(TAG, "muxer release threw: ${e.message}")
        }
    }

    private fun reject(sink: OutputSink, code: String) {
        Log.e(TAG, "codec prepare rejected: $code")
        engineError = code
        target = null
        currentSink = null
        store.discard(sink)
        state = EngineState.IDLE
    }

    private fun sealCurrent(durationMs: Long, keep: Boolean) {
        val sink = currentSink ?: return
        // 输出句柄先关，再 commit/discard（写完才清 IS_PENDING）
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

    /** ERROR 只是中间态：清完废片必回 IDLE（04 §6） */
    private fun finish(error: String?): RecordResult {
        val out = if (parts.isEmpty())
            RecordResult.fail(error ?: RecordError.TOO_SHORT, clock.elapsedMs()) else buildResult(error)
        state = EngineState.IDLE
        engineError = null
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        return out
    }

    // endregion
}
