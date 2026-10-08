package com.wotagei.cam.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
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
        /** muxer 写失败的现场快照里"这笔来自哪条轨"（见 [muxFailDiagnostic]） */
        private const val TRACK_VIDEO = "V"
        private const val TRACK_ENV = "A"
        private const val TRACK_CAP = "C"
        /**
         * 内录样本等待窗：muxer 启动被"启用但无样本"的内录通道卡住时的有界放弃时限。
         * 部分机型 playback capture 无媒体在播时不回缓冲——无界等待会让 muxer 永不启动，
         * 停止时按 NO_VIDEO_TRACK 整段作废（用户连视频都拿不到）。窗满按废弃放行：
         * 环境+视频照常出片，内录轨丢失经 reportTrackLost 可见化。
         *
         * 【必须短于起录自检长窗 RecordHealth.FULL_WINDOW_MS(3000)——这不是取值口味，是可达性】
         * cap 无样本时自检三格恒不满（muxStarted 被 cap 闸卡死），healthVerdict 会在 3000ms
         * 判废 abandonAndRestart、3 次后 GIVE_UP 整段失败。两窗同在 elapsedRealtime 域起算，
         * 等待窗若 ≥ 长窗（历史取 5000ms 即如此），窗满放行永远轮不到、自检先动手——"防整段
         * 作废"形同虚设，不回缓冲机型（华为系）表现为"开内录必 SELF_CHECK_FAILED"。
         * 预算：窗起点（视频 addTrack ≤800ms）+ 本窗 1500 + muxer 启动到首视频样本再被自检
         * 读到（≤2 拍 360ms）< 3000。正常双轨机型有媒体播放时内录首样本（PCM 20~40ms/包 +
         * AAC 首输出）远快于 1500ms，不触发（成功路径零改变）。
         */
        internal const val CAP_SAMPLE_WAIT_MS = 1_500L

        /**
         * 内录样本等待窗的一拍推进（纯函数，"计划→行为"桥：起窗/清窗/窗满三态各有真实语义）。
         * @param waitStartMs 窗起点（0 = 未起窗），elapsedRealtime 域
         * @param nowMs       本拍时刻，elapsedRealtime 域
         * @param waiting     本拍是否仍需等内录样本（视频+环境都就绪、内录启用且未废弃、
         *                    仍无首个真实样本、且未走失效收尾）
         * @return (新窗起点, 是否窗满放弃)：不再等待恒清窗；首拍起窗不判超时（起窗即满窗
         *         会把 0 长窗误判成超时）
         */
        internal fun capSampleWaitTick(waitStartMs: Long, nowMs: Long, waiting: Boolean): Pair<Long, Boolean> {
            // 花括号体不是风格偏好：结构守卫用 bodyOf 抓函数体做突变自证，表达式体让守卫失去输入
            return when {
                !waiting -> 0L to false
                waitStartMs == 0L -> nowMs to false
                else -> waitStartMs to (nowMs - waitStartMs > CAP_SAMPLE_WAIT_MS)
            }
        }
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

    /**
     * 逐 muxer 轨"已写出的 pts"（μs，已减去本段基准）：-1 = 本段该轨还没写过。
     *
     * 【为什么需要这本账（2026-10-08 真机根因收敛）】同一轨的 pts **倒退**会让 MPEG4Writer
     * 走失败路径——`writeSampleData` 抛 `IllegalStateException`，泵循环退出 ⇒ **整场录制当场结束**
     * （现场 `rotate-fail.log` 的 `trk=A;started=1;field=same;st=0` 那族，6/6 都是它）。
     * 换段期"暂存首样本补写"（[AudioChannel.heldOutIdx]）天然存在结构性顺序风险：那枚**较早**的样本
     * 可能在**较新**的样本之后才写出（补写点排在音频 dequeue 之后）。上游时序假设不能当保证，
     * 所以这里就地兜住：等值/倒退一律推进到 `last+1`，把致命错误降级成一枚微秒级修正，
     * 并把**次数与最大倒退量**记进失败快照（下次复现时能直接看出是不是这条机制）。
     */
    private val muxLastPtsUs = LongArray(8) { -1L }
    private var muxPtsFixes = 0
    private var muxPtsMaxBackUs = 0L

    /** 逐轨 pts 账复位（新段起算：换段后是新 muxer，没有历史） */
    private fun resetMuxPtsBooks() {
        java.util.Arrays.fill(muxLastPtsUs, -1L)
        muxPtsFixes = 0
        muxPtsMaxBackUs = 0L
    }
    private var aacMinBuf = 0
    private var audioSource = 0
    /** 双 feeder 的 start 时刻（μs，nanoTime 域）：启动记账（批 4 校正的数据基础） */
    private var envFeederStartUs = 0L
    private var capFeederStartUs = 0L

    @Volatile private var state = EngineState.IDLE
    @Volatile private var engineError: String? = null

    /** 显式弃段意图（[abandonCurrentSegment] 置位，新会话/新段起始复位）：见 [segmentKeepDecision] */
    @Volatile private var abandonSegment = false

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

    /**
     * 当前段已写 muxer 字节数（段内累加，泵线程单写者；[health] 今天只在控制线程读它，故 @Volatile
     * 是防御未来把自检挪出该线程的零成本保险）。
     * 复用原 `runSegment` 内的局部账本（**不新开计数**），段起始归零，供 [RecorderHealth.outputBytes]。
     */
    @Volatile private var segWrittenBytes = 0L

    // ---- 起录自检三格（泵线程单写者，[health] 今天只在控制线程读，故一律 @Volatile 是防御未来把
    // 自检挪出该线程的零成本保险）----
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

    // ---- 换段腿的有界化与丢帧账（2026-10-08 真机缺陷修复，判定/记账见 RotateGuard.kt）----

    /**
     * 换段腿起点（`elapsedRealtime`；0 = 当前不在换段腿里）。**编码器被拆的那一刻**起钟——
     * 此后进来的帧写不进任何容器 = 丢帧窗口开启。泵线程写、停止路径（控制线程）读 ⇒ @Volatile。
     */
    @Volatile
    private var rotateLegStartMs = 0L

    /** 本腿里是否有入库调用撞了有界上限（泵线程与停止路径都可能置位 ⇒ @Volatile） */
    @Volatile
    private var rotateStoreTimedOut = false

    /** 上一次换段腿的丢失（ms）：[settleRotateLeg] 写入、下一轮循环顶部随段账落地（见 [segLostMs]） */
    private var pendingLegLostMs = 0L

    /**
     * 本段是否请求"重开本段"（新段的 muxer 首笔就写不进 ⇒ 有界重建，见 [muxRecoverDecision]）。
     * 泵线程写、泵线程读，@Volatile 只是防御未来挪出该线程。
     */
    @Volatile
    private var segmentRestartRequested = false

    /** 本会话已重建本段的次数（上界见 [muxRecoverDecision]） */
    private var segmentRestarts = 0

    /** 最近一次 muxer 写失败的现场快照（随失败码进结果/账目，见 [muxFailDiagnostic]） */
    @Volatile
    private var muxFailDetail = ""

    /**
     * 本腿**第一个**失败的环节游标（见 [RotateStage]；空串 = 还没失败）。只记第一次：
     * 后续环节的失败多半是前面卡住后的连锁反应，第一个才是根。随失败码进结果/账目——
     * 这台 ROM 吞掉应用日志，环节游标是唯一能在真机上分辨"卡在哪一步"的通道。
     */
    private var rotateFailStage = ""

    /**
     * 逐段换段丢失账（索引=partIndex，与 [segSampleCounts] **同处、同口径**追加）：
     * 记的是"这一段开始之前那次换段腿里丢掉的画面时长"。泵线程单写者，随
     * [RecordResult.segLostMs] 进 sidecar 段清单，用户事后可查"这段丢了 N 秒"。
     */
    private val segLostMs = ArrayList<Long>()

    /** 跨段丢帧累加账（泵线程 + 停止路径都会记 ⇒ 账本内部加锁） */
    private val rotateLoss = RotateLossLedger()

    /** 有界入库执行器：把走 MediaProvider/FUSE 的同步调用挪出泵线程并按预算等（见 [BoundedStore]） */
    private val storeIo = BoundedStore()

    /**
     * 判超时后迟到落地的 pending 计数（见 [OneShotHandoff]）：回收动作本身要能在账里看见
     * （"幽灵候选"有多少条），泵线程 + 入库线程都可能自增 ⇒ @Volatile。
     */
    @Volatile
    private var latePendingReaped = 0L

    /** 新 pending 的交接闸：判停摆时稳住**晚到**的那一枚（见 [OneShotHandoff]，防幽灵条目） */
    private val pendingHandoff = OneShotHandoff<Uri> { late ->
        latePendingReaped++
        Log.e(TAG, "迟到落地的 pending 就地回收（第 $latePendingReaped 枚）")
        discardBounded(late)
    }

    /** 新输出目标的交接闸：晚到的那枚自己关掉（fd 不许悬到进程结束） */
    private val targetHandoff = OneShotHandoff<OutputTarget> { t ->
        runCatching { t.close() }.onFailure { Log.w(TAG, "晚到的 output target 关闭失败：${it.message}") }
    }

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
        abandonSegment = false
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
        segWrittenBytes = 0L
        segSampleCounts.clear()
        resetRotateBooks()
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
        // 静音 / 无权限 / 采样率不支持 → 完全不初始化音频通路（环境、内录两轨一起关）；
        // 音源双开定版（2026-10-07）：内录轨不再挂在环境编码器之下，「仅内录」（环境音关）
        // 也吃同一套音频通路参数（AudioRecord minBufferSize 两源同值），minBuf 按「任一轨要建」起算
        val minBuf = if ((p.audioUsable || p.captureAudio) && AudioProbe.hasRecordPermission(ctx))
            AudioProbe.minBufferSize(p.sampleRate, p.channels) else 0
        aacMinBuf = if (minBuf > 0) minBuf else 0
        audioSource = AudioProbe.pickSource(ctx)
        val a = if (minBuf > 0 && p.audioEnabled) createAudioEncoder(p, minBuf) else null
        if (a == null && p.audioUsable)
            Log.i(TAG, "audio dropped on codec path: sr=${p.sampleRate} ch=${p.channels} minBuf=$minBuf")
        aCodec = a
        // 内录第二音轨（P2：单 MP4 双 AAC）：参数与钟域同环境轨；两轨编码器**独立**创建
        //（2026-10-07 双开定版：环境音关不连坐内录，仅内录组合合法）
        val c = if (minBuf > 0 && p.captureAudio) createAudioEncoder(p, minBuf) else null
        if (c == null && p.captureAudio)
            Log.i(TAG, "capture track dropped on codec path: sr=${p.sampleRate} ch=${p.channels} minBuf=$minBuf")
        capCodec = c
        // 两轨全没建起来才清：仅内录（a=null 合法）时 aacMinBuf 还要留给分段轮转重建 cap 编码器
        if (a == null && c == null) aacMinBuf = 0
        // 两轨**独立**降级（双开定版 2026-10-07）：环境编码器建失败只关环境（内录不连坐，
        // 反之亦然），与 833 行重建闸「环境路降级不该连坐健康的内录路」同一口径。
        // 旧「a 失败两条全关」只在 cap 前置=env 已建的时代必要，现在 c 自己判自己
        profile = when {
            a == null && p.audioEnabled -> p.copy(audioEnabled = false)
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
        resetMuxPtsBooks()
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
        // 失败原因可见化（此前只留日志）：采集器建不起来的机型上用户只会看到"没内录轨"，
        // detail 取 controller 在 createCaptureAudioRecord 各失败分支记下的中文短因
        val ctrl = PlaybackCaptureController.get(ctx)
        ctrl.reportTrackLost(CaptureTrackLostReason.INIT_FAILED, ctrl.lastInitFailure)
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
        // 循环内完成 EOS → 抽干 → 封段 → 提交。泵线程若还在换段腿里（这一步现在有界，见
        // ROTATE_LEG_BUDGET_MS），停止等待就可能超时——**不许当"没什么事"放行**：
        // 那正是真机上"用户按了停止、内容已经丢了几十秒、结果却报已保存"的形态，
        // 所以超时即按换段受阻结算（明确失败 + 丢帧账进结果）
        if (!joinLoop()) settleRotateLegAtStop()
        closeSegmentEngine()
        // AudioRecord 的 stop/release 固定排在 codec、muxer 之后（环境、内录两个 feeder）
        stopFeeder()
        stopCapFeeder()
        return finish(engineError)
    }

    /**
     * 显式弃段（[Recorder.abandonCurrentSegment] 契约）：置位后本段封段一律 keep=false。
     *
     * 【为何本路也要接】审查时核对过 runSegment 的 stopping 分支与 pumpLoop 的 seal 路径：
     * 「muxer 没 start」会先置 [RecordError.NO_VIDEO_TRACK]、「muxer.stop 抛」会在 closeSegmentEngine
     * 里置 `ENGINE_ERROR:MUX_CLOSE`——这两态本来就会 discard。但存在一条**无错误码、三格不全**的态：
     * `healthVideoTrackAdded=true`（轨已建）且 `muxStarted=true`（muxer 已启）却**没有首个视频样本写入**
     * （`healthFirstVideoSample=false`，如 auto-stop 的前 3s 长窗内视频编码器迟迟不吐样本），此时
     * `ok=true、engineError=null、dur>=MIN_KEEP` 会 keep=true 把只有容器的空片 commit 进相册——正是
     * MR 路静默零帧的同族形态。所以本路不允许赌，同样接上 [abandonSegment]。
     */
    override fun abandonCurrentSegment() {
        abandonSegment = true
        Log.i(TAG, "codec abandon current segment requested")
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
        abandonSegment = false
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        segVideoWritten = 0
        segWrittenBytes = 0L
        segSampleCounts.clear()
        resetRotateBooks()
        Log.i(TAG, "codec released")
    }

    /** 换段腿/丢帧账复位（新会话、新段序列与释放三处共用同一份口径，不许各写一份） */
    private fun resetRotateBooks() {
        rotateLegStartMs = 0L
        rotateStoreTimedOut = false
        pendingLegLostMs = 0L
        rotateFailStage = ""
        latePendingReaped = 0L
        segmentRestartRequested = false
        segmentRestarts = 0
        muxFailDetail = ""
        segLostMs.clear()
        rotateLoss.clear()
    }

    override val elapsedMs: Long get() = clock.elapsedMs()

    /**
     * 起录自检读数：三格在泵循环里已有对应内部状态（建轨 / muxer 启动 / 首样本），这里只提升成
     * 可读快照。errorCode 与 [engineError] **同源**（不另立一份会漂移的镜像）——写入点分布在
     * 泵循环/收尾多处，任何一处漏镜像都会让 UI 读到过期的"健康"。
     * [RecorderHealth.outputBytes] 填本段已写 muxer 字节账（[segWrittenBytes]，复用写入点已有的账，
     * 不新开计数）——本路 UI 走三格 [healthVerdict]、不读它，但两个通道都如实填，语义不打折。
     */
    override fun health(): RecorderHealth {
        return RecorderHealth(
            videoTrackAdded = healthVideoTrackAdded,
            muxStarted = healthMuxStarted,
            firstVideoSample = healthFirstVideoSample,
            errorCode = engineError,
            elapsedMs = if (healthStartMs > 0L) SystemClock.elapsedRealtime() - healthStartMs else 0L,
            outputBytes = segWrittenBytes,
            milestonesObservable = true
        )
    }

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
            // 本段要不要"重开"（muxer 首笔写不进的有限重建，见 writeSample）：与换段共用同一条腿
            val restart = segmentRestartRequested
            segmentRestartRequested = false
            // 段闭落账（泵线程单写者，无竞争）：无论本段成废都要落，索引才与 partIndex 一一对齐；
            // 落完归零，下一段从 0 重新计。换段丢帧账与段账同处落地（首段前没有换段腿 ⇒ 恒 0）
            segSampleCounts.add(segVideoWritten)
            segVideoWritten = 0
            segLostMs.add(pendingLegLostMs)
            pendingLegLostMs = 0L
            val dur = clock.closeSegment()
            // 这一轮到底要不要换段（判据必须在拆编码器**之前**取好：换段腿的起钟与结算都以它为准，
            // 否则停止时的最后一段封段会被算进"换段腿"——那份慢不该被说成"丢了内容"）
            val willRotate = (ok && engineError == null && rotatePending) || (restart && engineError == null)
            // 换段腿起钟：**编码器即将被拆**那一刻（此后进来的帧写不进任何容器 = 丢帧窗口开启）
            if (willRotate) beginRotateLeg()
            // 先拆 codec/muxer（moov 写完）再封段提交，顺序反了相册会拿到半截文件
            closeSegmentEngine()
            // 弃段意图（自检判废）压过"看着正常"：见 segmentKeepDecision
            sealCurrent(dur, ok && segmentKeepDecision(abandonSegment, engineError, dur, RecordProfile.MIN_KEEP_MS))
            // 停录途中不再换段：`stopping` 置位后 rotatePending 往往恰好也为真（阈值刚触顶就被停），
            // 照旧换段会白建一枚 pending 再按 NO_VIDEO_TRACK 丢掉（附带一个假错误码落进结果）
            val wantRotate = willRotate && !stopping
            var rotated = false
            if (wantRotate) {
                // 重建本段时换段意图可能没置过：这里补清（下一次阈值触顶照常换段）
                rotatePending = false
                rotated = nextSegment()
                // 换段腿失败：一律走带丢帧账+环节游标的明确失败码（可见、可事后分辨环节）。
                // 真机现场就是这条把整场录制当场结束掉（r18 后可见，此前只有一行日志）
                if (!rotated) engineError = engineError ?: rotateStallError(rotateLoss.totalMs, rotateFailStage)
                if (rotated && restart) {
                    segmentRestarts++
                    noteSegmentRestartLoss(dur)
                    Log.e(TAG, "本段已重建（第 $segmentRestarts 次）：丢失 $dur ms + 换段腿")
                }
            }
            // 本腿结算：入库撞上限或腿超预算 ⇒ ROTATE_STALL（明确失败，看门狗/收尾把原因亮给用户）；
            // 没越界但有损失也记账（[segLostMs] → sidecar，用户事后可查"这段丢了 N 秒"）
            settleRotateLeg(judgeStall = wantRotate)
            keepGoing = wantRotate && rotated && engineError == null
        }
        if (engineError != null) Log.i(TAG, "codec loop end with ${engineError}")
    }

    /**
     * 换段腿起钟（泵线程）。腿的定义：从编码器被拆到新段 `codec.start()` 返回（见 [ROTATE_LEG_BUDGET_MS]）。
     */
    private fun beginRotateLeg() {
        rotateLegStartMs = SystemClock.elapsedRealtime()
        rotateStoreTimedOut = false
        rotateFailStage = ""
    }

    /** 记下本腿第一个失败的环节（后续失败不覆盖：第一个才是根因，见 [rotateFailStage]） */
    private fun noteRotateStage(stage: String) {
        if (rotateFailStage.isEmpty()) rotateFailStage = stage
    }

    /**
     * 换段腿的剩余预算（ms）：腿内按 [ROTATE_LEG_BUDGET_MS] 递减，腿外（停止封段等）用
     * [STORE_CALL_BUDGET_MS]。<=0 = 预算已尽 ⇒ 下一次有界调用直接判超时（不许再发无预算的调用）。
     */
    private fun rotateBudgetLeftMs(): Long {
        val start = rotateLegStartMs
        if (start == 0L) return STORE_CALL_BUDGET_MS
        return ROTATE_LEG_BUDGET_MS - (SystemClock.elapsedRealtime() - start)
    }

    /** 入库调用撞上限（或无预算可发）时的记账：本腿结算会据此判明确失败 */
    private fun noteStoreTimeout(what: String) {
        rotateStoreTimedOut = true
        Log.e(TAG, "换段整备受阻：$what 超过有界上限（本腿剩余预算已算尽），按有界结果继续")
    }

    /**
     * 重建本段的丢失记账：失败的本段一笔都没写成，但它占掉的那段墙钟确实没内容
     * （[durMs] = 该段从 startSegment 到失败那一刻），必须与换段腿的丢失一起进账。
     */
    private fun noteSegmentRestartLoss(durMs: Long) {
        if (durMs <= 0L) return
        pendingLegLostMs += durMs
        rotateLoss.add(durMs)
    }

    /**
     * 换段腿结算（泵线程）：把腿耗时折成丢帧账，越界即判明确失败 [RecordError.ROTATE_STALL]。
     * 记账只落 [pendingLegLostMs]（由下一轮循环顶部随段账落地，索引才对得上 partIndex）；
     * 停止路径的腿由 [settleRotateLegAtStop] 单独结算（那条路的泵线程还卡在腿里没出来）。
     *
     * @param judgeStall 本腿是否真的走到了轮转（false = 停止途中取消了轮转，腿里只有最后一段的
     *   封段：那份耗时该记进丢失账、但**不许**判失败——封段慢不等于录制受阻）
     */
    private fun settleRotateLeg(judgeStall: Boolean) {
        val start = rotateLegStartMs
        if (start == 0L) return
        rotateLegStartMs = 0L
        val legMs = SystemClock.elapsedRealtime() - start
        val lost = rotateLostMs(legMs)
        pendingLegLostMs = lost
        rotateLoss.add(lost)
        if (lost > 0L) {
            Log.e(
                TAG,
                "换段受阻：本腿 ${legMs}ms（良性开销 ${ROTATE_BENIGN_MS}ms），丢帧约 ${lost}ms；" +
                    "累计 ${rotateLoss.totalMs}ms/${rotateLoss.count} 次"
            )
        }
        if (!judgeStall) return
        if (rotateStallDecision(rotateStoreTimedOut, legMs)) {
            Log.e(TAG, "换段受阻超过上限 ${ROTATE_LEG_BUDGET_MS}ms（腿 ${legMs}ms），明确失败并保留已录分段")
            engineError = engineError ?: rotateStallError(rotateLoss.totalMs, rotateFailStage)
        }
    }

    /**
     * 停止路径的换段腿结算（控制线程；泵线程此刻仍卡在腿里）。判据收严（[rotateStopStallDecision]）：
     * 停止时最后一段封段本身就可能慢，误报"丢了 N 秒"比漏报更糟。只写此时已经安全的量：
     * 账本内部加锁、错误码 @Volatile——**不碰**泵线程单写者的段账表（那边可能同时在校验/写）。
     */
    private fun settleRotateLegAtStop() {
        val start = rotateLegStartMs
        if (start == 0L) return
        val legMs = SystemClock.elapsedRealtime() - start
        val lost = rotateLostMs(legMs)
        rotateLoss.add(lost)
        val stall = rotateStopStallDecision(rotateStoreTimedOut, legMs)
        Log.e(
            TAG,
            "停止时换段腿仍未返回（已 ${legMs}ms，丢帧约 ${lost}ms）：" +
                if (stall) "按换段受阻明确失败，已录分段照常保留" else "只记账（未达兜底判据，不误报失败）"
        )
        if (stall) engineError = engineError ?: rotateStallError(rotateLoss.totalMs, rotateFailStage)
    }

    // ---- 有界入库调用（2026-10-08 真机缺陷修复）：会走 MediaProvider/FUSE 的同步调用一律经这里 ----
    // 为什么必须挪出泵线程：泵线程是唯一的产出线程，这一腿里任何一步无界等待都等于"整机静默丢内容"
    // （真机 30~45s 空洞、无错误码、计时冻结）。上限见 ROTATE_LEG_BUDGET_MS / STORE_CALL_BUDGET_MS。

    /** 关当前段的输出句柄（有界）：晚到的 close 幂等，超时只记账 */
    private fun closeTargetBounded() {
        val t = target
        target = null
        closeBounded(t)
    }

    /** 关一枚输出目标（有界）：失败路径也不许在泵线程上无界等；收摊类调用保底 [CLEANUP_MIN_BUDGET_MS] */
    private fun closeBounded(t: OutputTarget?) {
        if (t == null) return
        val r = storeIo.run(cleanupBudgetMs(rotateBudgetLeftMs())) { t.close() }
        if (r is Bounded.TimedOut) noteStoreTimeout("target.close")
    }

    /** 建一枚新 pending（有界）：超时/失败回 null；晚到的那枚经 [pendingHandoff] 就地回收 */
    private fun createPendingBounded(partIndex: Int): Uri? {
        pendingHandoff.reset()
        val r = storeIo.run(rotateBudgetLeftMs()) {
            pendingHandoff.publish(store.createPending(partIndex))
        }
        return when (r) {
            is Bounded.Done -> if (r.value) {
                pendingHandoff.claim()
            } else {
                // insert 当场返回空（真机那条"第 1~2 次换段就死"的死法）：也是 INSERT 环节
                noteRotateStage(RotateStage.INSERT)
                null
            }
            Bounded.TimedOut -> {
                noteStoreTimeout("createPending")
                noteRotateStage(RotateStage.INSERT)
                discardBounded(pendingHandoff.abandon())
                null
            }
        }
    }

    /** 回收一枚 pending（有界、幂等）：泵线程不许在回收上再等一次无界调用 */
    private fun discardBounded(uri: Uri?) {
        if (uri == null) return
        val r = storeIo.run(STORE_CALL_BUDGET_MS) { store.discard(OutputSink.Pending(uri)) }
        if (r is Bounded.TimedOut) Log.e(TAG, "pending 回收超时（记录只能留给系统兜底）：$uri")
    }

    /** 打开输出目标（有界）：超时回 null；晚到的那枚自己关掉（fd 不许悬到进程结束） */
    private fun openTargetBounded(sink: OutputSink): OutputTarget? {
        targetHandoff.reset()
        val r = storeIo.run(rotateBudgetLeftMs()) { targetHandoff.publish(OutputTarget.open(ctx, sink)) }
        return when (r) {
            is Bounded.Done -> if (r.value) targetHandoff.claim() else null
            Bounded.TimedOut -> {
                noteStoreTimeout("OutputTarget.open")
                noteRotateStage(RotateStage.OPEN)
                // 交接闸里已落地的那枚由我们自己关（晚到的由 discard 回调关）
                targetHandoff.abandon()?.let { closeBounded(it) }
                null
            }
        }
    }

    /** 封一段并入库（有界）：超时回 null（晚到的 commit 仍会把文件落地，内容不丢） */
    private fun sealBounded(sink: OutputSink, durationMs: Long, keep: Boolean): VideoSegment? {
        val r = storeIo.run(rotateBudgetLeftMs()) {
            store.seal(sink, partIndex, partStartMs, durationMs, keep)
        }
        return when (r) {
            is Bounded.Done -> r.value
            Bounded.TimedOut -> {
                noteStoreTimeout("store.seal")
                noteRotateStage(RotateStage.SEAL)
                null
            }
        }
    }

    /**
     * 分段：换 pending uri、重建 codec+muxer（输入面与采集线程沿用），并在本线程内重启。
     *
     * **有界重试**（2026-10-08 真机缺陷）：这台 ROM 在上一段 33MB pending 文件的异步收尾期间，
     * 建新 pending / 开新输出的入库调用会久等甚至直接失败，一次失败就返回 false = 整场录制当场
     * 结束（真机现场正是"第 1~2 次换段后整轮无段"）。重试仍在换段腿预算内（[rotateRetryDecision]），
     * 不许把有界做成无界；失败的 pending 每轮就地回收，不留幽灵。
     */
    private fun nextSegment(): Boolean {
        var attempt = 0
        while (true) {
            attempt++
            val uri = createPendingBounded(partIndex + 1)
            if (uri != null) {
                val sink = OutputSink.Pending(uri)
                if (openNextSegment(sink)) return true
                discardBounded(uri) // 没写进去的 pending 立刻回收，避免幽灵条目
                closeTargetBounded()
                // 行已回收：当前段句柄一并清空（否则后续收尾会去封一条已被删掉的记录）
                currentSink = null
            }
            if (!rotateRetryDecision(attempt, rotateBudgetLeftMs())) {
                rotateFailStage = if (rotateFailStage.isEmpty()) RotateStage.ROTATE else rotateFailStage
                Log.e(TAG, "换段失败（第 $attempt 次尝试后放弃，剩预算 ${rotateBudgetLeftMs()}ms）")
                return false
            }
            Log.i(TAG, "换段受阻：第 $attempt 次尝试失败，退让 ${ROTATE_RETRY_BACKOFF_MS}ms 后重试")
            SystemClock.sleep(ROTATE_RETRY_BACKOFF_MS)
        }
    }

    private fun openNextSegment(sink: OutputSink.Pending): Boolean {
        val p = profile ?: return false
        // 腿预算已被前面的入库调用吃光就不再往下走：后面全是编码器/muxer 的 native 重建（无上限可给），
        // 预算既尽就该交回 pumpLoop 按 ROTATE_STALL 明确失败，而不是带着没预算的腿继续赌
        if (rotateLegStartMs != 0L && rotateBudgetLeftMs() <= 0L) {
            noteStoreTimeout("rotate budget spent")
            return false
        }
        val t = openTargetBounded(sink) ?: run {
            noteRotateStage(RotateStage.OPEN)
            return false
        }
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
                noteRotateStage(RotateStage.ENCODE)
                closeBounded(t)
                return false
            }
            val newSurf = try {
                v.createInputSurface()
            } catch (e: Exception) {
                Log.e(TAG, "segment createInputSurface failed: ${e.javaClass.simpleName} ${e.message}")
                releaseQuietly(v)
                closeBounded(t)
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
                closeBounded(t)
                return false
            }
            runCatching { old?.release() }
                .onFailure { Log.w(TAG, "old input surface release failed: ${it.message}") }
        } else {
            val inSurf = inputSurface
            if (inSurf == null) {
                noteRotateStage(RotateStage.ENCODE)
                closeBounded(t)
                return false
            }
            v = createVideoEncoder(resolveVideoMime(p), p, inSurf)
            if (v == null) {
                noteRotateStage(RotateStage.ENCODE)
                closeBounded(t)
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
                // 重建失败可见化：本段起内录轨退出，环境+视频继续录
                val ctl = PlaybackCaptureController.get(ctx)
                ctl.reportTrackLost(CaptureTrackLostReason.ENCODER_REBUILD_FAILED, null)
                Log.i(TAG, "capture encoder rebuild failed, drop capture track for rest of series")
            }
        }
        if (!openMuxer(t, p.orientationHint)) {
            noteRotateStage(RotateStage.MUX)
            closeBounded(t)
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
            resetMuxPtsBooks()
            partIndex++
            partStartMs = clock.elapsedMs()
            clock.startSegment()
            Log.i(TAG, "codec segment $partIndex started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "segment restart failed: ${e.message}")
            noteRotateStage(RotateStage.START)
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
        // 段起始一并复位弃段意图：一次判废只作用于被弃的那一段，不许拖累之后每一段
        abandonSegment = false
        val bi = MediaCodec.BufferInfo()
        // 逐通道状态各一套（批 3：heldPkt/lastPts/EOS 标志环境、内录分开）。状态生命周期本就
        // 只在单段内（pumpLoop 每段重建编码器并清 held 包），段内局部比实例字段更不容易串轨
        val envCh = if (a != null) AudioChannel() else null
        val capCh = if (c != null) AudioChannel() else null
        // P1：捕获源失效的一次性贯穿收尾标志（teardown 幂等，恒只做一次）
        var capTornDown = false
        // 内录样本等待窗起点（0 = 未起窗；[capSampleWaitTick] 单写者推进）
        var capWaitStartMs = 0L
        var videoTrack = -1
        var muxStarted = false
        var videoDone = false
        var videoEosQueued = false
        // 段起始归零本段写入字节账（复用实例字段，供 health() 读产出活性；见 segWrittenBytes）
        segWrittenBytes = 0L
        var deadline = 0L
        // 各轨最后样本的媒体时长（已按段基准归一），用来在一行日志里看出轨间与墙钟的漂移
        var vPtsLastUs = 0L
        var aPtsLastUs = 0L
        var cPtsLastUs = 0L
        val segStartNs = System.nanoTime()

        while (true) {
            // 本段请求重建（新段首笔就写不进 muxer，见 writeSample）：立刻收口，**不置错误码**，
            // 交 pumpLoop 按"重开一段"处理（engineError 一置，录制中途看门狗就会抢先把整场停掉）
            if (segmentRestartRequested) {
                Log.e(TAG, "本段按重建收口（muxer 首笔写不进），part=$partIndex")
                return false
            }
            // 【暂存首样本补写必须排在"新样本直写"之前】换段后头几拍里，先到的音频输出缓冲
            // 会因"轨未 add / muxer 未 start"被暂存（heldOutIdx），等门开了再写回。原实现把补写
            // 放在音频 dequeue **之后**：若 muxer 恰在内录轨那道门才启动（`startMuxerIfReady`
            // 要求内录轨也就绪），下一拍就会先直写一枚**较新**的样本、再把**较早**的暂存样本补写
            // 上去 ⇒ 同一轨 pts 倒退 ⇒ MPEG4Writer 抛 IllegalStateException ⇒ **整场录制当场结束**
            // （2026-10-08 真机现场 `trk=A;started=1;field=same;st=0` 那族）。放到循环开头即结构性消除。
            // writeSample 里另有逐轨单调兜底（[muxLastPtsUs]），两层都在：一层保序、一层保不死。
            if (a != null && envCh != null && envCh.heldOutIdx >= 0 && envCh.track >= 0 && muxStarted) {
                bi.set(0, envCh.heldSize, envCh.heldPts, envCh.heldFlags)
                segWrittenBytes += writeSample(mx, TRACK_ENV, envCh.track, a.getOutputBuffer(envCh.heldOutIdx), bi)
                a.releaseOutputBuffer(envCh.heldOutIdx, false)
                envCh.heldOutIdx = -1
            }
            if (c != null && capCh != null && capCh.heldOutIdx >= 0 && capCh.track >= 0 && muxStarted) {
                bi.set(0, capCh.heldSize, capCh.heldPts, capCh.heldFlags)
                segWrittenBytes += writeSample(mx, TRACK_CAP, capCh.track, c.getOutputBuffer(capCh.heldOutIdx), bi)
                c.releaseOutputBuffer(capCh.heldOutIdx, false)
                capCh.heldOutIdx = -1
            }
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
                        // 失效可见化（此前只留日志）：read 错误/静默死亡导致的失效用户毫无感知；
                        // 投影撤销场景 UI 层会按 captureState 过滤（Revoked 已有独立提示）
                        val ctl = PlaybackCaptureController.get(ctx)
                        ctl.reportTrackLost(CaptureTrackLostReason.SOURCE_INACTIVE, null)
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
                            val wrote = writeSample(mx, TRACK_VIDEO, videoTrack, v.getOutputBuffer(idx), bi)
                            segWrittenBytes += wrote
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
                                    segWrittenBytes += writeSample(mx, TRACK_ENV, envCh.track, a.getOutputBuffer(idx), bi)
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
                                    // 防重上报：窗满放弃已置 dropped 并报过 NO_SAMPLES，EOS 收尾不再重复；
                                    // 失效收尾（capTornDown）已报 SOURCE_INACTIVE，废弃原因以更准的那条为准
                                    if (!capCh.dropped && !capTornDown) {
                                        val ctl = PlaybackCaptureController.get(ctx)
                                        ctl.reportTrackLost(CaptureTrackLostReason.NO_SAMPLES, null)
                                    }
                                    capCh.dropped = true
                                    if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                                }
                            }
                            capCh.dropped -> c.releaseOutputBuffer(idx, false)
                            capCh.track >= 0 && muxStarted -> {
                                // 正常路：muxer 已接纳本轨，照写
                                if (bi.size > 0) {
                                    segWrittenBytes += writeSample(mx, TRACK_CAP, capCh.track, c.getOutputBuffer(idx), bi)
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
            // 暂存补写已上移到本拍开头（保序；见那里注释），此处不再补
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
            // 暂存补写已上移到本拍开头（保序；见那里注释），此处不再补
            // 内录样本等待窗（E6 防整段作废）：muxer 启动被"启用但无样本"的内录通道卡住时，
            // 从其余轨就绪那拍起等 [CAP_SAMPLE_WAIT_MS]；窗满按废弃放行（dropped + 重评启动闸 +
            // NO_SAMPLES 可见化），环境+视频照常出片。部分机型 playback capture 无媒体在播时
            // 不回缓冲，无界等待会让 muxer 永不启动 → 停止时整段作废、用户连视频都拿不到。
            // 正常双轨机型有媒体播放时内录首样本远快于窗，此分支不触发（成功路径零改变）。
            // 窗条件含 !capTornDown：失效收尾的原因码以 SOURCE_INACTIVE 为准，不叠报 NO_SAMPLES。
            val capWaiting = !muxStarted && videoTrack >= 0 &&
                (envCh == null || envCh.dropped || envCh.track >= 0) &&
                capCh != null && !capCh.dropped && !capCh.hasSample && !capTornDown
            val (capWaitStart, capWaitExpired) = capSampleWaitTick(
                capWaitStartMs, SystemClock.elapsedRealtime(), capWaiting
            )
            capWaitStartMs = capWaitStart
            if (capWaitExpired && capCh != null) {
                capCh.dropped = true
                val ctl = PlaybackCaptureController.get(ctx)
                ctl.reportTrackLost(CaptureTrackLostReason.NO_SAMPLES, null)
                if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                Log.i(TAG, "capture sample wait window expired, drop capture track part=$partIndex")
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
            if (!rotatePending && segWrittenBytes >= (WotaTiers.MAX_FILE_BYTES * ROTATE_SAFETY).toLong()) {
                Log.i(TAG, "written=$segWrittenBytes reached MAX_FILE_BYTES limit, rotate")
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
                "bytes=$segWrittenBytes 视频=${vPtsLastUs / 1000f}ms 环境=${aPtsLastUs / 1000f}ms 内录=${cPtsLastUs / 1000f}ms " +
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
    private fun writeSample(mx: MediaMuxer, kind: String, track: Int, buf: ByteBuffer?, info: MediaCodec.BufferInfo): Int {
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
        // 逐轨单调保证（判定走 [monotonicPts] 桥，见 [muxLastPtsUs] 注释）：等值/倒退就地推进到
        // last+1，绝不把倒退交给 MPEG4Writer（它会抛 IllegalStateException 让整场录制结束）。
        // 计次与最大倒退量进失败快照——用途是**证明**下一次现场是不是这条机制，没有读数就只能继续猜。
        val ti = if (track in muxLastPtsUs.indices) track else -1
        if (ti >= 0) {
            val fix = monotonicPts(muxLastPtsUs[ti], info.presentationTimeUs)
            if (fix.advanced) {
                muxPtsFixes++
                if (fix.backUs > muxPtsMaxBackUs) muxPtsMaxBackUs = fix.backUs
                Log.w(TAG, "轨 $track pts 未单调（cur=${info.presentationTimeUs}）⇒ 推进到 ${fix.ptsUs}（倒退 ${fix.backUs}us）")
            }
            info.presentationTimeUs = fix.ptsUs
            muxLastPtsUs[ti] = fix.ptsUs
        }
        return try {
            mx.writeSampleData(track, buf, info)
            info.size
        } catch (e: IllegalStateException) {
            // 现场快照（这台 ROM 无日志：这是唯一能分辨"写超停"与"写先于 start"的通道）
            val diag = muxFailDiagnostic(
                kind = kind, started = true, fieldSame = muxer === mx,
                partIndex = partIndex, track = track, size = info.size,
                ptsUs = info.presentationTimeUs, flags = info.flags, segBaseUs = segBaseUs,
                writtenBytes = segWrittenBytes, videoSamples = segVideoWritten,
                restarts = segmentRestarts, stopping = stopping, rotatePending = rotatePending
            ) + ";ptsfix=${muxPtsFixes};back=${muxPtsMaxBackUs}us"
            muxFailDetail = diag
            Log.e(TAG, "writeSampleData failed: ${e.message} [$diag]")
            if (muxRecoverDecision(freshSegmentMuxFailure(segVideoWritten, segWrittenBytes), segmentRestarts)) {
                // 本段一笔都没写成 ⇒ 重建本段（换一套 fd/编码器/muxer 再试），不判废整场。
                // **不置 engineError**：置了录制中途看门狗会抢先 stopInternal（r18 那条可见失败通道
                // 留给"重建也不成"的终局），丢失与现场快照照记
                segmentRestartRequested = true
                Log.e(TAG, "新段首笔样本就写不进 muxer，重建本段（第 ${segmentRestarts + 1} 次）")
            } else {
                engineError = engineError ?: muxFailError(diag)
            }
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

    /**
     * 等泵循环收尾。
     * @return true = 循环已退出（收尾完整）；false = 超时仍未退出（泵线程还卡在换段腿/收尾里，
     *   调用方必须按"这一段可能丢内容"处理，见 [stop]）
     */
    private fun joinLoop(): Boolean {
        val t = loop
        loop = null
        if (t == null) return true
        try {
            t.join(LOOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (t.isAlive) {
            Log.e(TAG, "mux loop alive after ${LOOP_JOIN_MS}ms")
            return false
        }
        return true
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
        // 输出句柄先关，再 commit/discard（写完才清 IS_PENDING）。**两步都有界**：关 fd / 查 SIZE、DATA /
        // 清 IS_PENDING 全走 MediaProvider，这台机在 33MB 级 pending 文件收尾上会长时间不返回（真机
        // 现场：段已写满 31.5MB、计时冻结、无新文件、无残留——正是卡在这两步之间的形态）
        closeTargetBounded()
        currentSink = null
        val seg = sealBounded(sink, durationMs, keep) ?: return
        // 废片不能进 parts：seal 已经把这条 MediaStore 记录删掉了，列进去就会让
        // buildResult 拿到一个不存在的 uri，out.ok 成立 → UI 报「已保存」而文件其实没了
        // （超时的情况下 seg 为 null：晚到的 commit 仍会把文件落地，只是不进 parts 清单）
        if (keep) parts.add(seg)
    }

    private fun discardCurrent(code: String) {
        val sink = currentSink ?: return
        Log.i(TAG, "discard unusable output: $code")
        closeTargetBounded()
        currentSink = null
        val r = storeIo.run(rotateBudgetLeftMs()) {
            store.seal(sink, partIndex, partStartMs, 0L, keep = false)
        }
        if (r is Bounded.TimedOut) noteStoreTimeout("discardCurrent")
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
            segVideoSamples = segSampleCounts.toList(),
            segLostMs = segLostMs.toList(),
            lostMs = rotateLoss.totalMs,
            rotateStalls = rotateLoss.count,
            latePending = latePendingReaped,
            segmentRestarts = segmentRestarts,
            muxDiag = muxFailDetail
        )
    }

    /** ERROR 只是中间态：清完废片必回 IDLE（04 §6） */
    private fun finish(error: String?): RecordResult {
        // 无产物也要把换段丢帧账带上（"整段丢了 N 秒"是用户唯一能看到的解释，
        // 见 RecordError.ROTATE_STALL 与 recordResultText 的文案映射）
        val lost = rotateLoss.totalMs
        val out = if (parts.isEmpty())
            RecordResult.fail(error ?: RecordError.TOO_SHORT, clock.elapsedMs(), lost, muxFailDetail)
        else buildResult(error)
        state = EngineState.IDLE
        engineError = null
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        segVideoWritten = 0
        segSampleCounts.clear()
        resetRotateBooks()
        return out
    }

    // endregion
}
