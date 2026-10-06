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
 * 自研管线：`MediaCodec + MediaMuxer + AudioRecord`（04 文件 §3：fps > 60 的高速路径；
 * 内录体系批 3 起也是双轨路径——arcConvert / captureAudio / 高帧率三者任一命中都走本引擎）。
 *
 * 关键点：
 * - 输入面：DIRECT 路（Camera2 当 producer）用 `MediaCodec.createPersistentInputSurface()`
 *   （API 29 起）跨分段复用不重建；GPU 路（EGL 当 producer）必须 per-codec
 *   `codec.createInputSurface()`（persistent 面挂 EGL 真机 EGL_BAD_ALLOC，见 [prepareGpuVideoEncoder]），
 *   换段重挂经 [onInputSurfaceRecreated] 同步通知；
 * - 视频参数：COLOR_FormatSurface + BITRATE_MODE_VBR + KEY_I_FRAME_INTERVAL=1 + KEY_FRAME_RATE=fps；
 * - 音频参数：`audio/mp4a-latm` + AAC-LC + 128k + CHANNEL_COUNT + KEY_MAX_INPUT_SIZE=minBufferSize，
 *   PTS 用 `System.nanoTime()/1000`，EOS 走 PCM 同一通道的 4 字节哨兵（``）；
 * - **双轨编排（P2：单 MP4 双 AAC 音轨）**：环境/内录各一套 feeder + 编码器 + 逐通道状态
 *   （[AudioChannel]，heldPkt/lastPts/EOS 标志互不串扰），addTrack 顺序 = 环境轨先、内录轨后
 *   （轨序即 tkhd track_ID 顺序，批 4 播放器按此识别）；Muxer 只在「视频就绪 且 环境轨就绪
 *   且（未启用捕获 或 捕获轨就绪）」时 start()（[startMuxerIfReady]）；
 * - **录制中捕获源失效**（投影撤销 → [AudioFeeder.captureActive] 变 false）：内录轨就地 EOS 收尾、
 *   环境+视频继续录——成片仍是合法 MP4（内录轨比另两轨短，播放器按轨长对齐），不中断录制；
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

    /** 单条音频通道的逐段状态（批 3：heldPkt / 末包 PTS / EOS 标志环境、内录各一套）。 */
    private class AudioChannel {
        /** 编码器没空位时暂存的包：留到下一轮，绝不丢帧 */
        var heldPkt: AudioFeeder.Packet? = null
        /** 本通道最后喂进编码器的真实 PCM 包 PTS（EOS 收尾时间戳基准） */
        var lastPtsUs = 0L
        /** muxer 轨号（addTrack 成功后 ≥0） */
        var track = -1
        /** 输出格式已到（INFO_OUTPUT_FORMAT_CHANGED 见过；内录轨 addTrack 待轨序门控放行） */
        var fmtReady = false
        /** EOS 已入队（喂到哨兵 / 停止兜底 / 捕获源失效三路共用，恒只入队一次） */
        var eosQueued = false
        /** 编码器已吐 EOS（本通道收尾完成） */
        var done = false

        // ---- 以下为零样本防线（修复轮2 P3 起环境、内录两通道共用同一套口径）----

        /** 编码器已吐出首个真实（非 EOS、非空）输出缓冲：addTrack 的前置判据 */
        var hasSample = false
        /** 通道废弃（收尾仍无真实样本）：startMuxerIfReady 按「未启用」处理 */
        var dropped = false
        /** 首个真实样本的输出缓冲暂存（等 addTrack+muxer start 后补写；-1=无）及其 BufferInfo 快照 */
        var heldOutIdx = -1
        var heldPts = 0L
        var heldSize = 0
        var heldFlags = 0
    }

    private var inputSurface: Surface? = null
    private var vCodec: MediaCodec? = null
    private var aCodec: MediaCodec? = null
    /** 内录（AudioPlaybackCapture）第二音轨的编码器；null = 未启用捕获或本段已降级 */
    private var capCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var target: OutputTarget? = null
    private var currentSink: OutputSink? = null
    private var feeder: AudioFeeder? = null
    /** 内录捕获源 feeder（环境源 [feeder] 并存——双轨并存不是换源） */
    private var capFeeder: AudioFeeder? = null
    private var profile: RecordProfile? = null

    /** 本段两轨共用的 PTS 基准（μs，与 nanoTime 同域），避免分段后时间戳倒退 */
    private var segBaseUs = 0L
    private var aacMinBuf = 0
    private var audioSource = 0
    /** 双 feeder 的 start 时刻（μs，nanoTime 域）：启动记账（批 4 校正的数据基础） */
    private var envFeederStartUs = 0L
    private var capFeederStartUs = 0L

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

    /** 当前段已写视频样本数（纯内存计数，不碰任何 I/O 时序）；段闭时落进 [segSampleCounts] 并归零 */
    private var segVideoWritten = 0

    // ---- 起录自检三格（泵线程单写者，UI 线程经 health() 读，故一律 @Volatile）----
    // 三格的定义与判定见 RecordHealth.kt：建轨 / muxer 启动 / 首个视频样本落地。
    /** INFO_OUTPUT_FORMAT_CHANGED 后 addTrack 成功（videoTrack ≥ 0） */
    @Volatile private var healthVideoTrackAdded = false
    /** startMuxerIfReady 内 mx.start() 成功 */
    @Volatile private var healthMuxStarted = false
    /** 首个视频样本 writeSampleData 成功（writeSample 返回 >0） */
    @Volatile private var healthFirstVideoSample = false
    /** start() 时刻（自检窗口计时基准；未 start 为 0） */
    @Volatile private var healthStartMs = 0L

    /**
     * 逐段视频样本数账（索引=partIndex）：位次账（arcDrops）里的 k 是跨段全局视频帧序号，
     * 段与位次靠这份账对上。只在泵线程读写，stop 收尾时随 [RecordResult.segVideoSamples] 交给
     * sidecar 段清单（CameraScreen → ArcDropLog.segments）。
     */
    private val segSampleCounts = ArrayList<Int>()

    /** prepare 成功后即稳定可用：Camera2 录像面 / GL outputSurface 的写入目标 */
    override val surface: Any? get() = inputSurface

    /**
     * GPU 路换段重挂钩（[Recorder] 契约）：GPU 路输入面 per-codec（见 [prepareGpuVideoEncoder]），
     * 分段轮转换编码器必须换面，泵线程在建好新面后同步回调、等 GL 挂好才继续。
     * DIRECT 路面是 persistent surface 跨段复用，本钩子不置位（永不回调）。
     */
    @Volatile override var onInputSurfaceRecreated: ((Any) -> Unit)? = null

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
        healthVideoTrackAdded = false
        healthMuxStarted = false
        healthFirstVideoSample = false
        healthStartMs = 0L
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        segVideoWritten = 0
        segSampleCounts.clear()
        envFeederStartUs = 0L
        capFeederStartUs = 0L
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
        val mime = resolveVideoMime(p)
        // GPU 路（EGL 当 producer）输入面必须 per-codec（[prepareGpuVideoEncoder]，真机实测
        // persistent surface 挂 EGL 会 EGL_BAD_ALLOC）；DIRECT 路（Camera2 当 producer）保持
        // persistent surface 跨会话复用的既有口径，行为不变。
        val v: MediaCodec? = if (p.useGpu) {
            prepareGpuVideoEncoder(mime, p)
        } else {
            val inSurf = inputSurface ?: MediaCodec.createPersistentInputSurface().also { inputSurface = it }
            createVideoEncoder(mime, p, inSurf)
        }
        if (v == null) {
            // t 已打开（fd 在手）但 target 字段还没轮到赋值，reject 的 target?.close() 够不着它：
            // 必须在这里先关，否则 openAssetFileDescriptor 的 fd 一直悬到进程结束
            t.close()
            closeSegmentEngine()
            reject(sink, RecordError.PREPARE_FAILED)
            return false
        }
        vCodec = v
        // 静音 / 无权限 / 采样率不支持 → 完全不初始化音频通路（环境、内录两轨一起关）
        val minBuf = if (p.audioUsable && AudioProbe.hasRecordPermission(ctx))
            AudioProbe.minBufferSize(p.sampleRate, p.channels) else 0
        aacMinBuf = if (minBuf > 0) minBuf else 0
        audioSource = AudioProbe.pickSource(ctx)
        val a = if (minBuf > 0) createAudioEncoder(p, minBuf) else null
        if (a == null && p.audioUsable)
            Log.i(TAG, "audio dropped on codec path: sr=${p.sampleRate} ch=${p.channels} minBuf=$minBuf")
        aCodec = a
        // 内录第二音轨（P2：单 MP4 双 AAC）：参数与钟域同环境轨；前置条件=环境编码器已建
        //（buildProfile 已裁决「内录×静音=纯视频」——audioEnabled 优先，这里再兜一道）
        val c = if (minBuf > 0 && p.captureAudio && a != null) createAudioEncoder(p, minBuf) else null
        if (c == null && p.captureAudio)
            Log.i(TAG, "capture track dropped on codec path: sr=${p.sampleRate} ch=${p.channels} minBuf=$minBuf")
        capCodec = c
        if (a == null) aacMinBuf = 0 // 分段重启时不再尝试音频，避免空转
        profile = when {
            a == null && (p.audioEnabled || p.captureAudio) -> p.copy(audioEnabled = false, captureAudio = false)
            c == null && p.captureAudio -> p.copy(captureAudio = false)
            else -> p
        }
        target = t
        currentSink = sink
        if (!openMuxer(t, p.orientationHint)) {
            closeSegmentEngine()
            reject(sink, RecordError.NO_OUTPUT)
            return false
        }
        Log.i(TAG, "codec prepared part=$partIndex ${p.width}x${p.height}@${p.fps} mime=$mime " +
            "bps=${p.effectiveBitrate()} audio=${if (a == null) "off" else "${p.sampleRate}Hz/${p.channels}ch"} " +
            "capture=${if (c == null) "off" else "on"} " +
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
     * GPU 路的视频编码器 + 输入面建立：configure 传 null 只建不绑，随后用
     * [MediaCodec.createInputSurface] 取 **per-codec** 输入面（合法窗口 = configure 之后、
     * start 之前）。**不用** [MediaCodec.createPersistentInputSurface]：那是「MediaRecorder 与
     * MediaCodec 共享同一输入面」的专用件，拿它当 EGL 渲染目标在真机 `eglCreateWindowSurface`
     * 直接失败（EGL_BAD_ALLOC 0x3003，实测三处：ArcRepairRunner 两轮 + 24※ 录制期转换首轮——
     * 强制档恒走本引擎，这是它在本机 GPU 路的第一次真机运行，普通 30fps 走 MrRecorder 不进这里）。
     * per-codec 面随编码器实例换代，分段轮转的重挂经 [onInputSurfaceRecreated] 同步通知。
     * @return null = 编码器或输入面建立失败（内部已清理，调用方按 PREPARE_FAILED 收场）
     */
    private fun prepareGpuVideoEncoder(mime: String, p: RecordProfile): MediaCodec? {
        val v = createVideoEncoder(mime, p, null) ?: return null
        return try {
            inputSurface = v.createInputSurface()
            v
        } catch (e: Exception) {
            Log.e(TAG, "createInputSurface failed: ${e.javaClass.simpleName} ${e.message}")
            inputSurface = null
            releaseQuietly(v)
            null
        }
    }

    /**
     * 建一枚 Surface 输入的编码器（能力探测走 [findSurfaceEncoder]，参数表与录制逐字相同）。
     * `internal` 供 ArcRepair 复用：光弧修复的插帧编码器与录制必须同一套能力探测与参数口径，
     * 复制一份迟早与录制分叉（AGENTS.md：不为同一件事写第二份）。
     *
     * @param surface 输入面；**传 null = 只 configure 不绑面**，调用方随后自行
     *   `codec.createInputSurface()` 取它自己的输入面。GPU 录制路（[prepareGpuVideoEncoder]）与
     *   ArcRepair 走这条：MediaCodec 的 `createPersistentInputSurface()` 是给 MediaRecorder 共享
     *   输入面的专用件，拿它当 EGL 渲染目标在真机上 `eglCreateWindowSurface` 直接失败
     *   （EGL_BAD_ALLOC 0x3003，实测）。DIRECT 录制路（Camera2 当 producer）照旧传 persistent
     *   输入面，行为逐字不变。
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
        // MediaMuxer 侧的旋转标记（旋转节）；GL 已按方向画，所以这里只做容器元数据。
        // 写出值统一经 exportOrientationHint，与 ArcRepairRunner 同一约定（见 ORIENTATION_MUXER_CCW），
        // 避免"修了修复路、录制路还错"
        try {
            m.setOrientationHint(exportOrientationHint(hint, ORIENTATION_MUXER_CCW))
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
        // 音频采集与编码器同刻启动，避免 PCM 积压落到下一个 PTS 基准上（环境、内录两个 feeder）
        if (aCodec != null && feeder == null) startFeederOrDropAudio()
        if (capCodec != null && capFeeder == null) startCapFeederOrDropAudio()
        try {
            vCodec?.start()
            aCodec?.start()
            capCodec?.start()
        } catch (e: Exception) {
            Log.e(TAG, "codec start failed: ${e.message}")
            engineError = RecordError.START_FAILED
            state = EngineState.ERROR
            closeSegmentEngine()
            stopFeeder()
            stopCapFeeder()
            discardCurrent(RecordError.START_FAILED)
            state = EngineState.IDLE
            return
        }
        segBaseUs = System.nanoTime() / 1000L
        state = EngineState.START
        healthStartMs = SystemClock.elapsedRealtime() // 自检窗口从这里起算
        partStartMs = clock.elapsedMs()
        clock.startSegment()
        stopping = false
        rotatePending = false
        loop = Thread({ pumpLoop() }, "WotaCodecMux").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        // 启动记账（批 4 双轨对齐校正的数据基础）：双 feeder 的 start 时刻与间隔都在 nanoTime 钟域，
        // 各源首包 PTS 由 feeder 自报（「首包记账」行）——环境/内录两轨的启动偏移一行可查
        if (envFeederStartUs > 0 || capFeederStartUs > 0) {
            Log.i(
                TAG,
                "启动记账 envStart=${envFeederStartUs}us capStart=${capFeederStartUs}us " +
                    "feeder间隔=${if (envFeederStartUs > 0 && capFeederStartUs > 0) capFeederStartUs - envFeederStartUs else -1}us"
            )
        }
        Log.i(
            TAG,
            "codec start part=$partIndex from=${partStartMs}ms " +
                "audio=${if (feeder == null) "off" else "on"} capture=${if (capFeeder == null) "off" else "on"}"
        )
    }

    private fun startFeederOrDropAudio() {
        val p = profile ?: return
        val f = AudioFeeder(ctx, p.sampleRate, p.channels, audioSource, aacMinBuf)
        if (f.start()) {
            feeder = f
            envFeederStartUs = System.nanoTime() / 1000L
            return
        }
        // 起不来就整条音频通路撤掉：视频继续（的静默降级）。profile 一并改写（P3，与内录
        // 首启失败同族口径）：轮转不再重建环境编码器——只置 aCodec=null 的话轮转会复活编码器
        // 而采集源已死，后续段出零样本幽灵轨、muxer 启动不变式恒卡死
        f.stop()
        releaseQuietly(aCodec)
        aCodec = null
        profile = p.copy(audioEnabled = false)
        Log.i(TAG, "audio feeder unavailable, continue video only")
    }

    /**
     * 内录捕获源 feeder：AudioRecord 由 [PlaybackCaptureController.createCaptureAudioRecord]
     * 在 Active 态建。起不来（会话已失效/参数不支持）就整条内录通路撤掉：环境音+视频继续——
     * 「会话活着但起不来」与「录制中失效」都降级，成片至少是视频+环境音。
     */
    private fun startCapFeederOrDropAudio() {
        val p = profile ?: return
        val f = AudioFeeder(
            capture = PlaybackCaptureController.get(ctx),
            sampleRate = p.sampleRate,
            channels = p.channels,
            minBufferSize = aacMinBuf
        )
        if (f.start()) {
            capFeeder = f
            capFeederStartUs = System.nanoTime() / 1000L
            return
        }
        f.stop()
        releaseQuietly(capCodec)
        capCodec = null
        // P0-b：profile 一并改写——只释放 capCodec 的话轮转会复活编码器而 feeder 恒 null，
        // 后续段出零样本幽灵轨（同族降级口径：采集失败 → profile 改写 → 轮转不空试）
        profile = p.copy(captureAudio = false)
        Log.i(TAG, "capture feeder unavailable, continue without capture track")
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
            stopCapFeeder()
            discardCurrent(engineError ?: RecordError.BAD_STATE)
            return finish(engineError ?: RecordError.BAD_STATE)
        }
        state = EngineState.STOPPING
        stopping = true
        joinLoop() // 循环内完成 EOS → 抽干 → 封段 → 提交
        closeSegmentEngine()
        // AudioRecord 的 stop/release 固定排在 codec、muxer 之后（环境、内录两个 feeder）
        stopFeeder()
        stopCapFeeder()
        return finish(engineError)
    }

    override fun release() {
        if (state == EngineState.START || state == EngineState.STOPPING) stop()
        stopping = true
        joinLoop()
        closeSegmentEngine()
        stopFeeder()
        stopCapFeeder()
        val s = inputSurface
        inputSurface = null
        s?.release()
        state = EngineState.IDLE
        engineError = null
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        segVideoWritten = 0
        segSampleCounts.clear()
        Log.i(TAG, "codec released")
    }

    override val elapsedMs: Long get() = clock.elapsedMs()

    /**
     * 起录自检读数：三格在泵循环里已有对应内部状态（建轨 / muxer 启动 / 首样本），这里只提升成
     * 可读快照。errorCode 与 [engineError] **同源**（不另立一份会漂移的镜像）——写入点分布在
     * 泵循环/收尾多处，任何一处漏镜像都会让 UI 读到过期的"健康"。
     */
    override fun health(): RecorderHealth = RecorderHealth(
        videoTrackAdded = healthVideoTrackAdded,
        muxStarted = healthMuxStarted,
        firstVideoSample = healthFirstVideoSample,
        errorCode = engineError,
        elapsedMs = if (healthStartMs > 0L) SystemClock.elapsedRealtime() - healthStartMs else 0L,
        milestonesObservable = true
    )

    /** PCM 均方根；量程与 MediaRecorder.getMaxAmplitude 一致，UI 音量表两引擎通用。
     *  只读**环境**源（P6 裁决：内录模式下音量条仍显环境音 RMS，不加第二根，内录源不入表） */
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
            // 段闭落账（泵线程单写者，无竞争）：无论本段成废都要落，索引才与 partIndex 一一对齐；
            // 落完归零，下一段从 0 重新计
            segSampleCounts.add(segVideoWritten)
            segVideoWritten = 0
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
        val t = OutputTarget.open(ctx, sink) ?: return false
        // GPU 路：per-codec 输入面随编码器实例换代——新面建好后**同步**让 GL 重挂（[Recorder]
        // 的 onInputSurfaceRecreated 契约，等挂好才 start），旧面在 GL 解绑后再释放；
        // 不重挂的话新段帧全落旧面（旧 codec 已停），GL 侧还持有已 release 的 native 面。
        // DIRECT 路（Camera2 当 producer）：persistent surface 跨段复用，行为不变。
        val v: MediaCodec?
        if (p.useGpu) {
            v = createVideoEncoder(resolveVideoMime(p), p, null)
            if (v == null) {
                // 编码器重建失败：target 字段此刻还是上一段的 null（sealCurrent 已清），
                // 调用方 nextSegment 的 target?.close() 关不到这枚 t，必须就地关
                t.close()
                return false
            }
            val newSurf = try {
                v.createInputSurface()
            } catch (e: Exception) {
                Log.e(TAG, "segment createInputSurface failed: ${e.javaClass.simpleName} ${e.message}")
                releaseQuietly(v)
                t.close()
                return false
            }
            val old = inputSurface
            inputSurface = newSurf
            // 回调跑在泵线程 try/catch 之外（pumpLoop 只兜 runSegment）：接线方抛异常会烧掉
            // 这枚 t 的 fd + 留幽灵 pending。公开契约不许假设实现不抛——就地折断换段
            //（返回 false 走 nextSegment 的 discard 收口），engineError 让 pumpLoop 退出
            // 而不是带着没挂好的面续录
            val rebindOk = runCatching { onInputSurfaceRecreated?.invoke(newSurf) }
                .onFailure { Log.e(TAG, "input surface rebind threw: ${it.message}") }
                .isSuccess
            if (!rebindOk) {
                engineError = engineError ?: "${RecordError.ENGINE_ERROR}:REBIND"
                t.close()
                return false
            }
            runCatching { old?.release() }
                .onFailure { Log.w(TAG, "old input surface release failed: ${it.message}") }
        } else {
            val inSurf = inputSurface
            if (inSurf == null) {
                t.close()
                return false
            }
            v = createVideoEncoder(resolveVideoMime(p), p, inSurf)
            if (v == null) {
                t.close()
                return false
            }
        }
        vCodec = v
        // 重建闸按 profile 改写判定（P0-b/P3：feeder 首启失败/捕获失效都已改写 profile，
        // 这里不再空试复活编码器）。环境、内录两块**独立**：环境路降级不该连坐健康
        // 的内录路（反之亦然），成片按各自在场的轨组合出
        if (aacMinBuf > 0 && p.audioEnabled) {
            val a = createAudioEncoder(p, aacMinBuf)
            aCodec = a
            if (a == null) stopFeeder() // 环境编码器重建失败：同步停采集，避免 PCM 队列空转
        }
        if (aacMinBuf > 0 && p.captureAudio) {
            // 内录编码器重建（captureAudio 恒走本引擎，profile 已带标志）。重建失败=内录轨从
            // 本段起降级退出（环境+视频继续录），profile 一并改写——后续段不再空试，与 prepare 的
            // 降级语义一致；成片仍是合法 MP4（前段双轨、后段单环境轨）
            val c2 = createAudioEncoder(p, aacMinBuf)
            capCodec = c2
            if (c2 == null) {
                stopCapFeeder()
                profile = p.copy(captureAudio = false)
                Log.i(TAG, "capture encoder rebuild failed, drop capture track for rest of series")
            }
        }
        if (!openMuxer(t, p.orientationHint)) {
            t.close()
            return false
        }
        target = t
        currentSink = sink
        feeder?.drain() // 丢弃上一段积压，避免旧 PCM 落进新文件造成 PTS 倒退
        capFeeder?.drain() // 双轨同口径：内录轨积压一并丢弃
        return try {
            v.start()
            aCodec?.start()
            capCodec?.start()
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
     * 单段循环：喂 PCM（环境/内录双通道）→ 抽轨写 muxer → 处理停止/分段。
     * Muxer 启动条件严格守住「视频就绪 且 环境轨就绪 且（未启用捕获 或 捕获轨就绪）」。
     * @return false = 本段没等到视频轨，或中途出错
     */
    private fun runSegment(): Boolean {
        val v = vCodec ?: return false
        val a = aCodec
        val c = capCodec
        val ef = feeder
        val cf = capFeeder
        val mx = muxer ?: return false
        // 段起始清自检三格：换段后新编码器/muxer 从零起算，上一段的真值不能冒充本段健康
        healthVideoTrackAdded = false
        healthMuxStarted = false
        healthFirstVideoSample = false
        val bi = MediaCodec.BufferInfo()
        // 逐通道状态各一套（批 3：heldPkt/lastPts/EOS 标志环境、内录分开）。状态生命周期本就
        // 只在单段内（pumpLoop 每段重建编码器并清 held 包），段内局部比实例字段更不容易串轨
        val envCh = if (a != null) AudioChannel() else null
        val capCh = if (c != null) AudioChannel() else null
        // P1：捕获源失效的一次性贯穿收尾标志（teardown 幂等，恒只做一次）
        var capTornDown = false
        var videoTrack = -1
        var muxStarted = false
        var videoDone = false
        var videoEosQueued = false
        var writtenBytes = 0L
        var deadline = 0L
        // 各轨最后样本的媒体时长（已按段基准归一），用来在一行日志里看出轨间与墙钟的漂移
        var vPtsLastUs = 0L
        var aPtsLastUs = 0L
        var cPtsLastUs = 0L
        val segStartNs = System.nanoTime()

        while (true) {
            // 喂 PCM：环境/内录各自独立通道（feedAudioInput 单份共用，EOS 判定逐字沿用）
            if (a != null && ef != null && envCh != null && !envCh.done && !envCh.eosQueued)
                feedAudioInput(a, ef, envCh)
            if (c != null && cf != null && capCh != null && !capCh.done) {
                if (cf.captureActive()) {
                    feedAudioInput(c, cf, capCh)
                } else {
                    // 录制中捕获源失效（投影撤销/会话终止/重授权）。
                    // 贯穿收尾与 eosQueued 解耦（修复轮2 P1）：泵线程健康失败退出**必然**投
                    // EOS 哨兵（FIFO 排在残留真实包后），主循环消费到哨兵即置位 eosQueued——
                    // 收尾若锁在「!eosQueued && 队列空」门内，唯一触发窗口只剩几十毫秒竞态，
                    // 之后永久跳过（僵尸 feeder 残留 + profile 残 true → 轮转空试复活编码器）。
                    // captureActive()==false 本身就是充分条件：一次性幂等 teardown；
                    // 队列里的残留包与哨兵照常由 feedAudioInput 消费收尾（成片=有样本的
                    // 短轨；无样本走 dropped，见内录输出支路），环境+视频不受影响继续录。
                    if (!capTornDown) {
                        capTornDown = true
                        stopCapFeeder()
                        profile = profile?.copy(captureAudio = false)
                        Log.i(TAG, "capture source inactive, tear down capture track part=$partIndex")
                    }
                    feedAudioInput(c, cf, capCh)
                    if (!capCh.eosQueued && cf.pending() == 0 && capCh.heldPkt == null) {
                        // 队列已排空仍未收尾：哨兵没进队（offer 超时等罕见竞态）→ 兜底直发
                        queueAudioEos(c, capCh)
                    }
                }
            }
            if (!videoDone) {
                val idx = v.dequeueOutputBuffer(bi, DEQUEUE_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = mx.addTrack(v.getOutputFormat())
                        healthVideoTrackAdded = true // 自检第一格：编码器产出格式、muxer 建轨成功
                        if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                        Log.i(TAG, "video track=$videoTrack fmt=${v.getOutputFormat()}")
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    idx >= 0 -> {
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (muxStarted && !eos && bi.size > 0) {
                            // 位次账只认视频帧：写成功才计数（writeSample 失败返回 0），音频轨不入账
                            val wrote = writeSample(mx, videoTrack, v.getOutputBuffer(idx), bi)
                            writtenBytes += wrote
                            if (wrote > 0) segVideoWritten++
                            // 自检第三格：首个视频样本真写进 muxer（writeSample 失败返回 0，不计）
                            if (wrote > 0) healthFirstVideoSample = true
                            vPtsLastUs = bi.presentationTimeUs
                        }
                        v.releaseOutputBuffer(idx, false)
                        if (eos) videoDone = true
                    }
                }
            }
            if (a != null && envCh != null && !envCh.done) {
                val idx = a.dequeueOutputBuffer(bi, DEQUEUE_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> envCh.fmtReady = true
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    idx >= 0 -> {
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        when {
                            eos -> {
                                a.releaseOutputBuffer(idx, false)
                                envCh.done = true
                                // P3：与内录通道对称的零样本防线——收尾仍无真实样本（feeder
                                // 产出首个 PCM 前死亡）→ 通道废弃，轨不进 muxer，启动闸按
                                // 「未启用音频」重评，段照常成片而非整段作废
                                if (!envCh.hasSample) {
                                    envCh.dropped = true
                                    if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                                }
                            }
                            envCh.dropped -> a.releaseOutputBuffer(idx, false)
                            envCh.track >= 0 && muxStarted -> {
                                // 正常路：muxer 已接纳本轨，照写
                                if (bi.size > 0) {
                                    writtenBytes += writeSample(mx, envCh.track, a.getOutputBuffer(idx), bi)
                                    aPtsLastUs = bi.presentationTimeUs
                                }
                                a.releaseOutputBuffer(idx, false)
                            }
                            envCh.heldOutIdx < 0 && bi.size > 0 -> {
                                // muxer 尚未接纳本轨：首个真实样本暂存不释放，门开后补写
                                //（与内录通道同构，「已产出样本 ⇒ 轨必有 ≥1 写入样本」）
                                envCh.heldOutIdx = idx
                                envCh.heldPts = bi.presentationTimeUs
                                envCh.heldSize = bi.size
                                envCh.heldFlags = bi.flags
                                envCh.hasSample = true
                            }
                            else -> a.releaseOutputBuffer(idx, false) // 暂存已占/空缓冲：启动前样本照既有口径丢弃
                        }
                    }
                }
            }
            if (c != null && capCh != null && !capCh.done) {
                val idx = c.dequeueOutputBuffer(bi, DEQUEUE_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> capCh.fmtReady = true
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    idx >= 0 -> {
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        when {
                            eos -> {
                                c.releaseOutputBuffer(idx, false)
                                capCh.done = true
                                // P0-a：收尾仍无任何真实样本 → 通道废弃，轨不进 muxer
                                //（零样本轨会让 muxer.stop 抛异常、缺 moov 的坏片被提交）。
                                // P1：废弃=未启用——可能正是内录轨挡着 muxer start，
                                // 就地重评启动闸（dropped 通道此后恒按未启用处理，无重入问题）
                                if (!capCh.hasSample) {
                                    capCh.dropped = true
                                    if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                                }
                            }
                            capCh.dropped -> c.releaseOutputBuffer(idx, false)
                            capCh.track >= 0 && muxStarted -> {
                                // 正常路：muxer 已接纳本轨，照写
                                if (bi.size > 0) {
                                    writtenBytes += writeSample(mx, capCh.track, c.getOutputBuffer(idx), bi)
                                    cPtsLastUs = bi.presentationTimeUs
                                }
                                c.releaseOutputBuffer(idx, false)
                            }
                            capCh.heldOutIdx < 0 && bi.size > 0 -> {
                                // muxer 尚未接纳本轨（未 addTrack 或未 start）：首个真实样本暂存
                                // 不释放，门开后补写——addTrack 前置判据 hasSample 由此而来，
                                // 且「已产出样本 ⇒ 轨必有 ≥1 写入样本」结构成立（P0-a）
                                capCh.heldOutIdx = idx
                                capCh.heldPts = bi.presentationTimeUs
                                capCh.heldSize = bi.size
                                capCh.heldFlags = bi.flags
                                capCh.hasSample = true
                            }
                            else -> c.releaseOutputBuffer(idx, false) // 暂存已占/空缓冲：启动前样本照既有口径丢弃
                        }
                    }
                }
            }
            // 环境轨 addTrack 门（P3 对称）：格式到 + 已见首个真实样本才准入；零样本（dropped）
            // 按「未启用音频」处理。**必须排在内录门之前**——轨序红线：环境轨先 addTrack
            //（轨序即 tkhd track_ID 顺序，批 4 播放器按 video 后第一/二条音轨识别环境/内录）
            if (a != null && envCh != null && !envCh.dropped && envCh.fmtReady && envCh.hasSample &&
                envCh.track < 0
            ) {
                envCh.track = mx.addTrack(a.getOutputFormat())
                if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                Log.i(TAG, "env audio track=${envCh.track}")
            }
            // 门开（addTrack + muxer start）后补写环境轨暂存的首个样本
            if (a != null && envCh != null && envCh.heldOutIdx >= 0 && envCh.track >= 0 && muxStarted) {
                bi.set(0, envCh.heldSize, envCh.heldPts, envCh.heldFlags)
                writtenBytes += writeSample(mx, envCh.track, a.getOutputBuffer(envCh.heldOutIdx), bi)
                a.releaseOutputBuffer(envCh.heldOutIdx, false)
                envCh.heldOutIdx = -1
            }
            // 内录轨 addTrack 门（每轮幂等尝试）+ 零样本防线（P0-a）：必须晚于环境轨
            //（环境轨本就不存在=编码器缺席，或已废弃 dropped——两者都不再有「先于谁」可言，
            // 才允许内录轨自由 add；env 轨废弃仍挡门的话会复现 P1 同款 muxStarted 恒 false），
            // 且必须**已见首个真实样本**（hasSample）——从未产出样本的通道已标 dropped，
            // startMuxerIfReady 按「未启用捕获」处理，零样本轨从结构上不可能出现。fmtReady 只
            // 证明格式到了，hasSample 才证明有内容可写；FORMAT_CHANGED 事件错过不丢格式：
            // getOutputFormat() 随时可取当前格式，等门开再 add 即可
            if (c != null && capCh != null && !capCh.dropped && capCh.fmtReady && capCh.hasSample &&
                capCh.track < 0 && (envCh == null || envCh.dropped || envCh.track >= 0)
            ) {
                capCh.track = mx.addTrack(c.getOutputFormat())
                if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                Log.i(TAG, "capture audio track=${capCh.track}")
            }
            // 门开（addTrack + muxer start）后补写内录轨暂存的首个样本
            if (c != null && capCh != null && capCh.heldOutIdx >= 0 && capCh.track >= 0 && muxStarted) {
                bi.set(0, capCh.heldSize, capCh.heldPts, capCh.heldFlags)
                writtenBytes += writeSample(mx, capCh.track, c.getOutputBuffer(capCh.heldOutIdx), bi)
                c.releaseOutputBuffer(capCh.heldOutIdx, false)
                capCh.heldOutIdx = -1
            }
            if (stopping || rotatePending) {
                if (deadline == 0L) {
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
                }
                // P2：双通道 EOS 兜底**每轮重试**（置位=真正入队，那一拍 dequeueInputBuffer(0)
                // 返回 -1 就下一轮再来）——锁进 deadline==0L 单发门的话，满负荷拍点 EOS 丢失、
                // done 恒不置位 → 1200ms DRAIN 超时误废整段（单段录制即整条丢失）。
                // 重试上界仍是 deadline（DRAIN_LIMIT_MS）
                if (a != null && envCh != null && !envCh.eosQueued) queueAudioEos(a, envCh)
                if (c != null && capCh != null && !capCh.eosQueued) queueAudioEos(c, capCh)
            }
            if (videoDone && (envCh?.done ?: true) && (capCh?.done ?: true)) break
            if (deadline > 0L && SystemClock.elapsedRealtime() > deadline) {
                Log.e(TAG, "drain timeout video=$videoDone env=${envCh?.done ?: "off"} cap=${capCh?.done ?: "off"}")
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
        // 三轨时长并排给出一行即可判定漂移：视频/环境/内录互为轨间偏移，与墙钟差即整体滑移
        // （本机无 >60fps 能力，这条路径的音画同步只能靠日志取证，见 docs/plan/10 §19 与任务 #33）
        Log.i(
            TAG,
            "segment $partIndex drained video=$videoDone env=${envCh?.done ?: "off"} cap=${capCh?.done ?: "off"} " +
                "bytes=$writtenBytes 视频=${vPtsLastUs / 1000f}ms 环境=${aPtsLastUs / 1000f}ms 内录=${cPtsLastUs / 1000f}ms " +
                "墙钟=${(System.nanoTime() - segStartNs) / 1_000_000f}ms"
        )
        return true
    }

    /**
     * Muxer 启动不变式（批 3 双轨版）：视频就绪 且 环境轨就绪 且（未启用捕获 或 捕获轨就绪）。
     * 「轨就绪」= 编码器本就不存在（静音/无权限降级，通道为 null）或已 addTrack。
     * addTrack 顺序即 tkhd track_ID 顺序（环境先、内录后），本函数只做启动闸、不改轨序。
     */
    private fun startMuxerIfReady(mx: MediaMuxer, videoTrack: Int, envCh: AudioChannel?, capCh: AudioChannel?): Boolean {
        if (videoTrack < 0) return false
        // P3：未启用音频（envCh=null）或环境轨已废弃（dropped：从未产出真实样本）→ 无需等环境轨
        if (envCh != null && !envCh.dropped && envCh.track < 0) return false
        // P0-a：未启用捕获（capCh=null）或捕获轨已废弃 → 无需等内录轨
        if (capCh != null && !capCh.dropped && capCh.track < 0) return false
        return try {
            mx.start()
            healthMuxStarted = true // 自检第二格：muxer 真启动（此后样本落盘）
            Log.i(TAG, "muxer start video=$videoTrack env=${envCh?.track ?: "none"} cap=${capCh?.track ?: "none"}")
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

    /**
     * 消费一条 PCM 通道（环境/内录共用**这一份**，EOS 判定逐字沿用）：取包 → 编码器输入；
     * EOS 哨兵以 pts<0 识别（``）。喂到哨兵即置位 [AudioChannel.eosQueued]——停止/捕获源
     * 失效路径的兜底 EOS（[queueAudioEos]）据此不再重复入队。
     */
    private fun feedAudioInput(codec: MediaCodec, src: AudioFeeder, ch: AudioChannel) {
        val pkt = ch.heldPkt ?: src.poll(0) ?: return
        val idx = codec.dequeueInputBuffer(0)
        if (idx < 0) {
            ch.heldPkt = pkt // 编码器没空位：本包留到下一轮，绝不丢帧
            return
        }
        ch.heldPkt = null
        // EOS 判定只看 pts<0（真实包 pts 恒为 nanoTime µs、恒正，哨兵恒 -1）。长度不能并列成
        // 第二判据：哨兵 data 恰 4 字节与「双声道 2 帧的合法短读」同形，size==4 支路（01028b9
        // 加 pts<0 时留在 OR 里的旧判定）会把真 PCM 误判成 EOS 提前掐断音轨
        if (pkt.ptsUs < 0) {
            queueEosOn(codec, idx, ch)
            return
        }
        val buf = codec.getInputBuffer(idx) ?: return
        val len = minOf(pkt.data.size, buf.capacity())
        buf.clear()
        buf.put(pkt.data, 0, len)
        try {
            codec.queueInputBuffer(idx, 0, len, pkt.ptsUs, 0)
            ch.lastPtsUs = pkt.ptsUs
        } catch (e: IllegalStateException) {
            Log.e(TAG, "audio queueInput failed: ${e.message}")
        }
    }

    /** 停止/轮转/捕获源失效三路的 EOS 兜底：入队成功才置位（P2）——idx<0 不置位，下一轮重试 */
    private fun queueAudioEos(codec: MediaCodec, ch: AudioChannel) {
        val idx = codec.dequeueInputBuffer(0)
        if (idx < 0) return
        queueEosOn(codec, idx, ch)
    }

    private fun queueEosOn(codec: MediaCodec, idx: Int, ch: AudioChannel) {
        try {
            codec.queueInputBuffer(idx, 0, 0, eosPts(ch), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            // 置位=真正入队（P2）：旧单发语义先置位再入队，idx<0/入队抛异常时 EOS 实际丢了
            // 而标志已立，喂入门与兜底都不再试 → done 恒不置位 → DRAIN 超时把当段误废。
            // 失败不置位，三条触发路（喂到哨兵后的补发、停止兜底、失效兜底）下一轮重试
            ch.eosQueued = true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "audio eos queue failed: ${e.message}")
        }
    }

    private fun eosPts(ch: AudioChannel): Long =
        if (ch.lastPtsUs > 0L) ch.lastPtsUs else System.nanoTime() / 1000L

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

    private fun stopCapFeeder() {
        capFeeder?.stop()
        capFeeder = null
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
        val c = capCodec
        if (c != null) {
            try {
                c.stop()
            } catch (e: IllegalStateException) {
                Log.i(TAG, "capture codec stop ignored")
            } catch (e: RuntimeException) {
                Log.e(TAG, "capture codec stop threw: ${e.message}")
            }
            releaseQuietly(c)
            capCodec = null
        }
        val m = muxer
        if (m != null) {
            try {
                m.stop()
            } catch (e: IllegalStateException) {
                // P0-c：不仅「没 start 过」会抛——start 过但某轨零样本（幽灵轨）一样抛。
                // 这两种状态下容器必然缺 moov/轨不完整，本段按废片处理：置 engineError 让
                // sealCurrent 走 keep=false，绝不把损坏 MP4 静默提交进相册。
                // 真「没 start 过」的路（NO_VIDEO_TRACK 等）engineError 已先置，?: 保留首个原因码
                Log.i(TAG, "muxer stop failed (not started or zero-sample track): ${e.message}")
                engineError = engineError ?: "${RecordError.ENGINE_ERROR}:MUX_CLOSE"
            }
            releaseQuietly(m)
            muxer = null
        }
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
        // prepare 中途失败（openMuxer 失败走的就是这条）target 里还攥着打开的 fd，置空前必须关
        target?.close()
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
            uncommitted = parts.count { !it.committed },
            segVideoSamples = segSampleCounts.toList()
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
        segVideoWritten = 0
        segSampleCounts.clear()
        return out
    }

    // endregion
}
