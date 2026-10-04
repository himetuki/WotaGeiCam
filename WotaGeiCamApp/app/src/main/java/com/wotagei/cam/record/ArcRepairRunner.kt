package com.wotagei.cam.record

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 光弧修复的日志锚点（与录制 `WotaRecord` 分开，方便真机过滤） */
private const val TAG_ARC = "WotaArcRepair"

/** 解码器输入/出、编码器出的轮询粒度 */
private const val DEQUEUE_TIMEOUT_US = 10_000L

/** 编码器 EOS 后允许吐完缓存的上限（防 stop 卡死） */
private const val ENC_DRAIN_LIMIT_MS = 3_000L

/** 估算总耗时的安全系数 + 固定余量：超时即失败收尾，绝不无限等 */
private const val DEADLINE_FACTOR = 8L
private const val DEADLINE_EXTRA_MS = 120_000L

/** 统计源帧数时的上限（防畸形容器把计数循环拖死） */
private const val MAX_SRC_FRAMES = 2_000_000

/** 源容器读不到帧率时的回退值（注释即口径：绝大多数手机录像 30fps 或更高，取 30 不会放大成慢动作） */
private const val FALLBACK_SRC_FPS = 30

/**
 * CPU 路线等一枚空闲编码器输入缓冲的上限。
 * `dequeueInputBuffer(DEQUEUE_TIMEOUT_US)` 自身会阻塞到超时，所以循环不是热自旋；这个上限只是
 * 兜住"编码器长时间不给空位"（输出没抽干/编码器卡死）的极端，超了就判 [ArcRepairError.CPU_FEED]。
 */
private const val CPU_FEED_LIMIT_MS = 3_000L

object ArcRepairError {
    /** 源打不开 / 没有视频轨 / 帧数为 0 */
    const val SOURCE = "ARC_SOURCE"

    /** 输出 pending 建不出来或打不开 */
    const val NO_OUTPUT = "ARC_NO_OUTPUT"

    /** 解码器建不出来或创建输出面失败 */
    const val DECODER = "ARC_DECODER"

    /** 编码器建不出来 */
    const val ENCODER = "ARC_ENCODER"

    /** GPU 路线失败（EGL/渲染/等帧超时） */
    const val GPU = "ARC_GPU"

    /** CPU 路线拿不到解码输出的 Image（该解码器只支持 Surface 输出） */
    const val CPU_YUV = "ARC_CPU_YUV"

    /** CPU 路线没法把帧写进编码器输入缓冲（等不到空闲 input buffer / 取不到 Image 或 Buffer） */
    const val CPU_FEED = "ARC_CPU_FEED"

    /** 没等到视频轨 */
    const val NO_VIDEO = "ARC_NO_VIDEO"

    /** 用户取消 */
    const val CANCELLED = "ARC_CANCELLED"

    /** 源实测帧率不高于目标帧率：没有帧可抽，修复无意义（UI 给专门提示，不算故障） */
    const val NO_NEED = "ARC_NO_NEED"

    /** 超时保护触发 */
    const val TIMEOUT = "ARC_TIMEOUT"

    /** 其它运行期异常，附 `:异常类名` */
    const val ENGINE = "ARC_ENGINE"
}

enum class ArcRepairStatus { IDLE, RUNNING, DONE, FAILED }

/**
 * 修复结果。[uri]/[path]/[bytes] 指向新入库的成片；[error] 非空即失败（此时产物已回收）。
 * [note] 是给用户/日志看的补充口径（例如 CPU 路线的性能与时间戳代价），不是错误。
 */
data class ArcRepairResult(
    val ok: Boolean,
    val uri: Uri?,
    val path: String?,
    val bytes: Long,
    val error: String?,
    val note: String? = null
)

/**
 * 光弧修复的**后台执行器**（用户需求第 7 项）：把一段视频处理成"插帧后"的新文件并入库。
 *
 * 形态克隆自 `ui/CameraScreen.kt` 的 `RecordRunner`：自己的 `HandlerThread` + 若干
 * `MutableStateFlow`（状态/进度/结果），`shutdown()` 用 `CountDownLatch` 同步收尾。
 *
 * **不是播放时的实时处理**：整个管线（解 → 插帧 → 编码 → 封装）跑在这条后台线程上，
 * 与预览/播放互不相干。用户点按钮才启动（v1 只提供入口，不含自动触发）。
 */
class ArcRepairRunner(private val ctx: Context) {

    private val thread = HandlerThread("WotaArcRepair").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var cancelRequested = false

    /** 同一时刻只允许一个任务（管线本身占满这条线程） */
    @Volatile
    private var busy = false

    val status = MutableStateFlow(ArcRepairStatus.IDLE)
    val progress = MutableStateFlow(0f)
    val result = MutableStateFlow<ArcRepairResult?>(null)

    /**
     * 启动一次修复。
     * @param srcUri 源视频（媒体库里的 content uri）
     * @param useGpu true = GPU 路线（EGL 到编码器面）；false = CPU 路线（逐平面 YUV 取大后直写编码器输入缓冲）
     * @param dstFps 抽帧目标帧率（产品口径 = 强制档 24/25）；源实测帧率不高于它时任务以 NO_NEED 收场
     */
    fun start(srcUri: Uri, useGpu: Boolean, dstFps: Int) {
        handler.post {
            if (busy) {
                Log.i(TAG_ARC, "已有任务在跑，忽略重复 start")
                return@post
            }
            busy = true
            cancelRequested = false
            status.value = ArcRepairStatus.RUNNING
            progress.value = 0f
            result.value = null
            val out = try {
                ArcRepairSession(
                    ctx = ctx,
                    src = srcUri,
                    useGpu = useGpu,
                    dstFps = dstFps,
                    onProgress = { progress.value = it },
                    cancelled = { cancelRequested }
                ).run()
            } catch (e: Throwable) {
                // 会话内部已把异常转成结果；这里再兜一层，保证"绝不崩"
                Log.e(TAG_ARC, "会话未按预期返回：${e.javaClass.simpleName} ${e.message}")
                ArcRepairResult(false, null, null, 0L, "${ArcRepairError.ENGINE}:${e.javaClass.simpleName}")
            }
            busy = false
            result.value = out
            status.value = if (out.ok) ArcRepairStatus.DONE else ArcRepairStatus.FAILED
            progress.value = if (out.ok) 1f else progress.value
            Log.i(TAG_ARC, "任务结束 ok=${out.ok} err=${out.error} uri=${out.uri} note=${out.note}")
        }
    }

    /** 请求取消（非阻塞）：管线在每个循环里检查，收尾时弃掉半成品 */
    fun stopAsync() {
        cancelRequested = true
    }

    /** 退出页面：同步等收尾完成，保证 pending 记录不会留成幽灵条目 */
    fun shutdown() {
        cancelRequested = true
        val latch = CountDownLatch(1)
        handler.post {
            latch.countDown()
            thread.quitSafely()
        }
        val ok = runCatching { latch.await(3, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!ok) Log.w(TAG_ARC, "收尾超时，交给线程自行结束")
    }

    fun clearResult() {
        result.value = null
        status.value = ArcRepairStatus.IDLE
        progress.value = 0f
    }
}

/** 运行期失败信号（带机器可读码，UI 按码映射文案） */
private class ArcFail(val code: String) : Exception(code)

/**
 * 一次修复会话：资源全在这里建、在这里收，[run] 无论成败都返回结果（不抛给外部）。
 *
 * 管线（v2 抽帧+补弧口径）：源 `MediaExtractor`（视频轨）→ 解码 → 按 [ArcRepairFlow] 指令
 * 抽帧/取大补弧 → 只把保留帧送编码器 → 另一条 `MediaExtractor`（音频轨）直拷 → `MediaMuxer`
 * 封装 → `VideoStore` 入库（`IS_PENDING` 两段式）。
 */
private class ArcRepairSession(
    private val ctx: Context,
    private val src: Uri,
    private val useGpu: Boolean,
    private val dstFps: Int,
    private val onProgress: (Float) -> Unit,
    private val cancelled: () -> Boolean
) {

    private var store: VideoStore? = null
    private var sink: OutputSink? = null
    private var target: OutputTarget? = null
    private var muxer: MediaMuxer? = null
    private var vDec: MediaCodec? = null
    private var vEnc: MediaCodec? = null
    private var vEx: MediaExtractor? = null
    private var aEx: MediaExtractor? = null
    private var arcGl: ArcRepairGl? = null
    private var encSurface: Surface? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxStarted = false
    private var videoDone = false
    private var audioDone = true
    private var encEosSent = false
    private var srcIndex = 0

    /** 首帧解码 PTS：之后所有时间戳都相对它归一（去重判距用，输出 PTS 不再走源时间轴） */
    private var decodePtsBase = -1L

    /** 上一解码帧的归一化 PTS：去重判距用 */
    private var prevPtsUs = 0L
    private var outputsDone = 0
    private var dstTotal = 1
    private var width = 0
    private var height = 0
    private var srcFps = FALLBACK_SRC_FPS
    private var durationMs = 0L
    private var videoPtsBase = -1L
    private var audioPtsBase = -1L
    private var audioPending = false
    private var audioPendingPts = 0L
    private var audioPendingSize = 0
    private var note: String? = null

    /** 抽帧计划与它的流式桥（open() 里建，主循环逐帧问指令） */
    private lateinit var flow: ArcRepairFlow

    // --- 复用件
    private val vBufInfo = MediaCodec.BufferInfo()
    private val aBufInfo = MediaCodec.BufferInfo()
    private val encBufInfo = MediaCodec.BufferInfo()
    private val audioBuf: ByteBuffer = ByteBuffer.allocateDirect(1 shl 20)

    // --- CPU 路线的平面缓冲（open() 里一次分配，逐帧复用，全为零分配）
    // cur = 当前解码帧、pend = 滞留待发的保留帧、acc = 被抽帧的取大累积面。
    // 一律"紧打包"（Y 占 w*h，U/V 各占 (w/2)*(h/2)），与编码器输入 Image 的 rowStride 无耦合，
    // 读入/写出时各自尊重对方的 stride——这样 Buffer 的布局差异不会污染合并算式的输入。
    private var curY: ByteArray = ByteArray(0)
    private var curU: ByteArray = ByteArray(0)
    private var curV: ByteArray = ByteArray(0)
    private var pendY: ByteArray = ByteArray(0)
    private var pendU: ByteArray = ByteArray(0)
    private var pendV: ByteArray = ByteArray(0)
    private var accY: ByteArray = ByteArray(0)
    private var accU: ByteArray = ByteArray(0)
    private var accV: ByteArray = ByteArray(0)

    fun run(): ArcRepairResult {
        var code: String? = null
        var ok = false
        try {
            open()
            mainLoop()
            ok = muxStarted && videoTrack >= 0
            if (!ok) code = code ?: ArcRepairError.NO_VIDEO
        } catch (e: ArcFail) {
            code = e.code
            Log.i(TAG_ARC, "修复中止：${e.code}")
        } catch (e: Exception) {
            code = "${ArcRepairError.ENGINE}:${e.javaClass.simpleName}"
            Log.e(TAG_ARC, "修复异常：${e.javaClass.simpleName} ${e.message}")
        } catch (e: Error) {
            code = "${ArcRepairError.ENGINE}:${e.javaClass.simpleName}"
            Log.e(TAG_ARC, "修复错误：${e.javaClass.simpleName} ${e.message}")
        }
        return finish(ok, code)
    }

    // region 打开与建轨

    private fun open() {
        val videoEx = MediaExtractor()
        vEx = videoEx
        try {
            videoEx.setDataSource(ctx, src, null)
        } catch (e: IOException) {
            throw ArcFail(ArcRepairError.SOURCE)
        }
        val vTrack = findTrack(videoEx, "video/")
        if (vTrack < 0) throw ArcFail(ArcRepairError.SOURCE)
        videoEx.selectTrack(vTrack)
        val vf = videoEx.getTrackFormat(vTrack)
        width = vf.intOr(MediaFormat.KEY_WIDTH, 0)
        height = vf.intOr(MediaFormat.KEY_HEIGHT, 0)
        val containerFps = vf.intOr(MediaFormat.KEY_FRAME_RATE, FALLBACK_SRC_FPS)
        durationMs = vf.longOr(MediaFormat.KEY_DURATION, 0L) / 1000L
        val mime = vf.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
        val hint = vf.intOr(MediaFormat.KEY_ROTATION, 0)
        if (width <= 0 || height <= 0) throw ArcFail(ArcRepairError.SOURCE)

        // 源帧率**实测**（ArcRateProbe 帧距中位数）：强制 24/25fps 档容器会标假 fps（标 24 实跑 30），
        // 按容器值造抽帧计划会漏抽；中位数对重复/空样本免疫，是遍历计数教训的正确解法
        var srcFrames = 0
        val rawDeltas = ArrayList<Int>(512)
        var prevSampleUs = -1L
        var scanned = 0
        while (scanned < MAX_SRC_FRAMES && videoEx.sampleTime >= 0L) {
            val t = videoEx.sampleTime
            if (prevSampleUs >= 0L) rawDeltas.add((t - prevSampleUs).toInt())
            prevSampleUs = t
            scanned++
            if (!videoEx.advance()) break
        }
        srcFps = ArcRateProbe.measuredFps(rawDeltas, containerFps)
        videoEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        // 源帧数按时长 × 实测帧率估算，只喂进度分母与计划日志；输出由解码顺序驱动
        // （真机实测遍历计数不可靠，见 v1 注释；时长缺失时才退回数样本）
        srcFrames = if (durationMs > 0 && srcFps > 0) {
            ((durationMs * srcFps + 500L) / 1000L).toInt().coerceAtLeast(1)
        } else {
            countSamples(videoEx)
        }
        videoEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        if (dstFps <= 0) throw ArcFail(ArcRepairError.SOURCE)
        if (srcFps <= dstFps) throw ArcFail(ArcRepairError.NO_NEED)
        val plan = ArcRepairPlan.of(srcFrames, srcFps, dstFps)
        if (plan.dstFrames <= 0) throw ArcFail(ArcRepairError.SOURCE)
        dstTotal = plan.dstFrames
        flow = ArcRepairFlow(plan)
        note = "源实测≈${srcFps}fps（容器标 $containerFps）→ 目标 ${dstFps}fps，" +
            "被抽帧画面已取大并入前后帧；不改原片"
        Log.i(
            TAG_ARC,
            "源 ${width}x$height 容器fps=$containerFps 实测fps=$srcFps srcFrames=$srcFrames " +
                "→ dstFps=$dstFps dstFrames=$dstTotal 抽≈${srcFrames - dstTotal} route=${if (useGpu) "GPU" else "CPU"} hint=$hint"
        )

        // 音频轨（另开一条 extractor：同一 extractor 只能选一条轨）
        val audioEx = MediaExtractor()
        aEx = audioEx
        var aFormat: MediaFormat? = null
        try {
            audioEx.setDataSource(ctx, src, null)
            val aTrack = findTrack(audioEx, "audio/")
            if (aTrack >= 0) {
                audioEx.selectTrack(aTrack)
                aFormat = audioEx.getTrackFormat(aTrack)
                audioDone = false
            }
        } catch (e: IOException) {
            Log.i(TAG_ARC, "音频轨打开失败，按无音频继续：${e.message}")
            audioEx.release()
            aEx = null
            audioDone = true
        }

        // 输出与封装。成片名带抽帧前缀（"30fto24f_"，源=实测帧率）：名字上可辨本片被抽过帧
        val st = VideoStore(ctx)
        store = st
        val pending = st.createPending(0, namePrefix = ArcRateProbe.convertNamePrefix(srcFps, dstFps))
            ?: throw ArcFail(RecordError.NO_OUTPUT)
        val sk = OutputSink.Pending(pending)
        sink = sk
        val t = OutputTarget.open(ctx, sk) ?: throw ArcFail(RecordError.NO_OUTPUT)
        target = t
        muxer = openMuxer(t, hint)
        if (aFormat != null) {
            // 源音频不是封装器认识的格式（或 csd 缺失）时，宁可没有音轨也不整单失败
            audioTrack = try {
                muxer!!.addTrack(aFormat)
            } catch (e: Exception) {
                Log.i(TAG_ARC, "音频轨加入失败，改出无音轨成片：${e.message}")
                -1
            }
            if (audioTrack >= 0) {
                Log.i(TAG_ARC, "音频轨已加（直拷）track=$audioTrack")
            } else {
                audioDone = true
                runCatching { aEx?.release() }
                aEx = null
            }
        }

        // 编码器（复用 CodecRecorder 的能力探测与参数口径）。
        // ⚠（仅 GPU 路线）输入面用 codec 自己的 createInputSurface()，**不用**
        // MediaCodec.createPersistentInputSurface()：后者是"MediaRecorder 与 MediaCodec 共享同一输入面"
        // 的专用件（Recorder 那条路才需要），拿它当 EGL 渲染目标在真机上 eglCreateWindowSurface 直接
        // 失败（EGL_BAD_ALLOC 0x3003，实测两轮）。createInputSurface 的合法窗口是 configure 之后、
        // start 之前——这里正好。CPU 路线不建面。
        // v2 口径：容器按**目标帧率**建档（v1 是源×2 的插帧容器，已废弃）。
        val profile = RecordProfile(
            width = width,
            height = height,
            fps = dstFps,
            captureRate = 0f,
            bitrate = 0,
            codec = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) RecordProfile.CODEC_HEVC else RecordProfile.CODEC_H264,
            sampleRate = 0,
            channels = 0,
            audioEnabled = false,
            orientationHint = hint,
            mirrored = false,
            useGpu = useGpu
        )
        // 只为调用那个无实例状态的 internal 方法而建一枚壳：VideoStore 已在手，构造零副作用
        val helper = CodecRecorder(ctx, st)
        // 两条路线的编码器输入方式不同：
        // - GPU：Surface 输入。surface 传 null = 只 configure、不绑面，随后用 codec.createInputSurface()
        //   拿它自己的输入面（此路线的 EGL 时序已真机验收）；
        // - CPU：ByteBuffer 输入，直写 YUV 平面，PTS 由 queueInputBuffer 显式给出——这正是修掉
        //   "Canvas 写输入面 → 时间戳由系统墙钟生成 → 21 倍慢放"那个真机缺陷的关键。
        val enc = if (useGpu) {
            helper.createVideoEncoder(mime, profile, null) ?: throw ArcFail(ArcRepairError.ENCODER)
        } else {
            helper.createYuvEncoder(mime, profile) ?: throw ArcFail(ArcRepairError.ENCODER)
        }
        vEnc = enc
        // encSurface 只在 GPU 路线存在；CPU 路线没有输入面（写的是输入缓冲），保持 null
        if (useGpu) {
            encSurface = try {
                enc.createInputSurface()
            } catch (e: Exception) {
                throw ArcFail(ArcRepairError.ENCODER)
            }
        }

        // 解码器
        val dec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw ArcFail(ArcRepairError.DECODER)
        }
        vDec = dec
        if (useGpu) {
            val gl = ArcRepairGl(width, height)
            arcGl = gl
            if (!gl.start()) throw ArcFail(ArcRepairError.GPU)
            val decSurface = gl.decoderSurface ?: throw ArcFail(ArcRepairError.GPU)
            dec.configure(vf, decSurface, null, 0)
        } else {
            // CPU：ByteBuffer 模式拿 YUV（无输出面但 getOutputImage 可读），逐平面取大后直写编码器输入缓冲
            dec.configure(vf, null, null, 0)
            allocPlanes()
            note = "CPU 路线：逐平面（YUV420）取大合并后直写编码器输入缓冲；单帧成本随分辨率上升，" +
                "长视频明显慢于 GPU。帧时间戳由 queueInputBuffer 显式给出，与 GPU 路线同一口径。$note"
        }

        dec.start()
        enc.start()
        // 编码器 EGL 面必须在 **enc.start() 之后**挂：真机实测 start 之前调 eglCreateWindowSurface
        // 报 EGL_BAD_ALLOC(0x3003)——MediaCodec 的输入面在 start 之前 buffer consumer 尚未就绪。
        // 这与 GlRenderEngine 的成功时序一致（它的编码面也是在录制 codec 起来之后挂的）。
        if (useGpu) {
            val gl = arcGl ?: throw ArcFail(ArcRepairError.GPU)
            val surf = encSurface ?: throw ArcFail(ArcRepairError.ENCODER)
            if (!gl.attachEncoderSurface(surf)) throw ArcFail(ArcRepairError.GPU)
        }
    }

    /** CPU 路线的平面缓冲：open() 里一次分配，之后逐帧复用（尺寸口径见字段注释） */
    private fun allocPlanes() {
        val yLen = width * height
        val cLen = (width / 2) * (height / 2)
        curY = ByteArray(yLen)
        curU = ByteArray(cLen)
        curV = ByteArray(cLen)
        pendY = ByteArray(yLen)
        pendU = ByteArray(cLen)
        pendV = ByteArray(cLen)
        accY = ByteArray(yLen)
        accU = ByteArray(cLen)
        accV = ByteArray(cLen)
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith(prefix)) return i
        }
        return -1
    }

    /** 数视频轨样本数（索引式 advance，不解码）；数完必须 seek 回起点 */
    private fun countSamples(ex: MediaExtractor): Int {
        var n = 0
        while (n < MAX_SRC_FRAMES) {
            if (ex.sampleTime < 0L) break
            n++
            if (!ex.advance()) break
        }
        return n
    }

    private fun openMuxer(t: OutputTarget, hint: Int): MediaMuxer {
        val m = try {
            val fd = t.fileDescriptor
            if (fd != null) MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else MediaMuxer(t.path ?: throw ArcFail(ArcRepairError.NO_OUTPUT), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            throw ArcFail(ArcRepairError.NO_OUTPUT)
        }
        try {
            m.setOrientationHint(hint)
        } catch (e: IllegalStateException) {
            Log.i(TAG_ARC, "orientation hint 忽略：${e.message}")
        }
        return m
    }

    // endregion

    // region 主循环

    private fun mainLoop() {
        val dec = vDec ?: throw ArcFail(ArcRepairError.DECODER)
        val decInfo = MediaCodec.BufferInfo()
        var inputEos = false
        var decOutDone = false
        val deadline = SystemClock.elapsedRealtime() +
            (if (durationMs > 0) durationMs * DEADLINE_FACTOR else DEADLINE_EXTRA_MS) + DEADLINE_EXTRA_MS

        while (!videoDone || !audioDone) {
            if (cancelled()) throw ArcFail(ArcRepairError.CANCELLED)
            if (SystemClock.elapsedRealtime() > deadline) throw ArcFail(ArcRepairError.TIMEOUT)

            if (!inputEos && feedDecoderInput(dec)) inputEos = true

            if (!decOutDone) {
                val idx = dec.dequeueOutputBuffer(decInfo, DEQUEUE_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                idx >= 0 -> {
                    val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos) {
                        // 解码 EOS：先把尾巴上的滞留帧/补弧按计划发出（末帧也必须是保留帧），
                        // 再交棒给编码器 EOS
                        if (useGpu) execGpu(flow.onSourceEos()) else execCpu(flow.onSourceEos())
                        runCatching { dec.releaseOutputBuffer(idx, false) }
                        decOutDone = true
                    } else if (decInfo.size > 0) {
                        // 该解码帧的源时间轴 PTS（归一化到首帧），**只用于去重判距**——
                        // v2 输出 PTS 取均匀目标帧率步长（第 j 帧 = j/dstFps），
                        // 输出时长 = 保留帧数/dstFps ≈ 源时长，与源 PTS 抖动解耦
                        if (decodePtsBase < 0L) decodePtsBase = decInfo.presentationTimeUs
                        val ptsUs = (decInfo.presentationTimeUs - decodePtsBase).coerceAtLeast(0L)
                        // 去重：源容器里常有空/重复样本（真机实测：1.875s 的片子只有 45 个有效包，
                        // 却有 124 个样本），解码器会把它们全吐出来。按"距上一个已采用帧不足半个源帧长"
                        // 判重丢弃——被灌水的帧会搅乱抽帧节奏（把该保留的位次占掉）
                        val minGapUs = if (srcFps > 0) 500_000L / srcFps else 0L
                        if (srcIndex > 0 && ptsUs - prevPtsUs < minGapUs) {
                            runCatching { dec.releaseOutputBuffer(idx, false) }
                        } else {
                            prevPtsUs = ptsUs
                            processSourceFrame(dec, idx)
                        }
                    } else {
                        runCatching { dec.releaseOutputBuffer(idx, false) }
                    }
                }
                }
            } else if (!encEosSent) {
                // 解码走完：给编码器发 EOS，之后只剩排空
                sendEncoderEos()
                encEosSent = true
            }

            drainEncoder(false)

            if (decOutDone && !muxStarted && vEnc != null) {
                // 编码器一直没吐视频轨：给一次排空机会，仍无则判失败
                drainEncoder(true)
                if (!muxStarted) throw ArcFail(ArcRepairError.NO_VIDEO)
            }
            if (videoDone && !audioDone) drainAudioUpTo(Long.MAX_VALUE)
        }

        // 收尾：把音频尾巴也写进去
        drainAudioUpTo(Long.MAX_VALUE)
    }

    /** @return true = 已排空输入（EOS 已入队） */
    private fun feedDecoderInput(dec: MediaCodec): Boolean {
        val ex = vEx ?: return true
        val idx = dec.dequeueInputBuffer(0)
        if (idx < 0) return false
        val buf = dec.getInputBuffer(idx) ?: return false
        val size = ex.readSampleData(buf, 0)
        return if (size < 0) {
            dec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } else {
            dec.queueInputBuffer(idx, 0, size, ex.sampleTime, 0)
            ex.advance()
            false
        }
    }

    // endregion

    // region 抽帧/补弧的指令执行（CPU 平面 / GPU 纹理两条路共用同一台 ArcRepairFlow）

    /**
     * 每个被采用的非重复解码帧：读帧 → 问 [ArcRepairFlow] 要指令 → 按路线执行。
     * 保留/抽掉的决策全在计划层（可 JVM 单测）；这里只忠实执行指令。
     */
    private fun processSourceFrame(dec: MediaCodec, idx: Int) {
        if (useGpu) {
            dec.releaseOutputBuffer(idx, true)
            val gl = arcGl ?: throw ArcFail(ArcRepairError.GPU)
            if (!gl.awaitFrame()) throw ArcFail(ArcRepairError.GPU)
            if (!gl.acquire()) throw ArcFail(ArcRepairError.GPU)
            execGpu(flow.onSourceFrame(srcIndex))
        } else {
            val image = dec.getOutputImage(idx)
            if (image == null) {
                // 该解码器不吐 Image（只支持 Surface 输出）：如实失败，不假装做了补弧
                runCatching { dec.releaseOutputBuffer(idx, false) }
                throw ArcFail(ArcRepairError.CPU_YUV)
            }
            try {
                if (image.width != width || image.height != height) {
                    Log.e(TAG_ARC, "解码输出尺寸 ${image.width}x${image.height} 与轨道 $width x $height 不符")
                    throw ArcFail(ArcRepairError.CPU_YUV)
                }
                readPlanes(image, curY, curU, curV)
                execCpu(flow.onSourceFrame(srcIndex))
            } finally {
                image.close()
                runCatching { dec.releaseOutputBuffer(idx, false) }
            }
        }
        srcIndex++
    }

    private fun execGpu(ops: List<ArcOp>) {
        val gl = arcGl ?: throw ArcFail(ArcRepairError.GPU)
        for (op in ops) {
            when (op) {
                is ArcOp.AccumulateCur -> if (!gl.accumulate(op.fresh)) throw ArcFail(ArcRepairError.GPU)
                is ArcOp.FoldAccIntoPending -> if (!gl.foldAccIntoPending()) throw ArcFail(ArcRepairError.GPU)
                is ArcOp.HoldCurAsPending -> if (!gl.holdCurAsPending()) throw ArcFail(ArcRepairError.GPU)
                is ArcOp.MergeCurAsPending -> if (!gl.mergeCurAsPending(op.useAcc)) throw ArcFail(ArcRepairError.GPU)
                is ArcOp.EmitPending -> {
                    if (!gl.emitPending(op.ptsUs)) throw ArcFail(ArcRepairError.GPU)
                    countOutput(1)
                    // 编码器输入面缓冲有限，每出一帧抽一次：不抽干可能让下一枚等不到空位
                    drainEncoder(false)
                }
            }
        }
    }

    private fun execCpu(ops: List<ArcOp>) {
        for (op in ops) {
            when (op) {
                is ArcOp.AccumulateCur -> {
                    if (op.fresh) copyCurToAcc() else {
                        ArcRepairMerge.mergeMax(accY, curY, curY.size)
                        ArcRepairMerge.mergeMax(accU, curU, curU.size)
                        ArcRepairMerge.mergeMax(accV, curV, curV.size)
                    }
                }
                is ArcOp.FoldAccIntoPending -> {
                    // P' = max(P, acc)：被抽帧的弧段并进**前**保留帧
                    ArcRepairMerge.mergeMax(pendY, accY, pendY.size)
                    ArcRepairMerge.mergeMax(pendU, accU, pendU.size)
                    ArcRepairMerge.mergeMax(pendV, accV, pendV.size)
                }
                is ArcOp.HoldCurAsPending -> copyCurToPending()
                is ArcOp.MergeCurAsPending -> {
                    if (op.useAcc) {
                        // B' = max(B, acc)：被抽帧的弧段并进**后**保留帧，带弧升格待发
                        ArcRepairMerge.mergeMax(curY, accY, curY.size)
                        ArcRepairMerge.mergeMax(curU, accU, curU.size)
                        ArcRepairMerge.mergeMax(curV, accV, curV.size)
                    }
                    copyCurToPending()
                }
                is ArcOp.EmitPending -> {
                    feedEncoder(pendY, pendU, pendV, op.ptsUs)
                    countOutput(1)
                    // 每出一帧抽一次编码器：硬件编码器输入缓冲有限，不抽干可能让下一帧等不到空位
                    drainEncoder(false)
                }
            }
        }
    }

    /** cur 平面整段拷进 acc（fresh 累积：清掉上一轮残留，整帧替换） */
    private fun copyCurToAcc() {
        System.arraycopy(curY, 0, accY, 0, curY.size)
        System.arraycopy(curU, 0, accU, 0, curU.size)
        System.arraycopy(curV, 0, accV, 0, curV.size)
    }

    /** cur 平面整段拷进 pending（滞留待发；零分配） */
    private fun copyCurToPending() {
        System.arraycopy(curY, 0, pendY, 0, curY.size)
        System.arraycopy(curU, 0, pendU, 0, curU.size)
        System.arraycopy(curV, 0, pendV, 0, curV.size)
    }

    // endregion

    /**
     * 把指定平面（[y]/[u]/[v]）写进编码器输入缓冲，并显式附上 [ptsUs]。
     *
     * **为什么必须逐帧显式给 PTS（本缺陷的修复点）**：旧实现用 `Surface.lockCanvas()` 往编码输入面
     * 画帧，时间戳由输入面生产者按系统墙钟生成、代码无法干预；真机 1.875s 素材 CPU 处理了 41s，
     * 容器里就被写成 21 倍慢放（帧步长 0.93s）。改成 `queueInputBuffer(…, ptsUs, 0)` 后时间戳完全
     * 由调用方掌控，与 GPU 路线同一口径。v2 里 PTS 一律是均匀目标帧率步长（第 j 帧 = j/dstFps）。
     */
    private fun feedEncoder(y: ByteArray, u: ByteArray, v: ByteArray, ptsUs: Long) {
        val enc = vEnc ?: throw ArcFail(ArcRepairError.ENCODER)
        val idx = awaitEncoderInput(enc)
        if (idx < 0) {
            Log.e(TAG_ARC, "等不到空闲编码器输入缓冲，pts=$ptsUs")
            throw ArcFail(ArcRepairError.CPU_FEED)
        }
        val buf = try {
            enc.getInputBuffer(idx)
        } catch (e: IllegalStateException) {
            null
        }
        // 容量在**取 Image 之前**就抄进局部量：官方文档写明取得 input Image 后，同一 index 先前
        // 返回的 ByteBuffer「不许再使用」——虽然只读 capacity() 实际无副作用，但这属于契约外的用法，
        // 抄出来最省心（编码器读多少由它自己的 format 布局决定，容量恒为合法值）
        val bufCapacity = buf?.capacity() ?: (width * height * 3 / 2)
        val img = try {
            enc.getInputImage(idx)
        } catch (e: IllegalStateException) {
            null
        }
        if (img != null) {
            // 首选：Image 自带合法的 rowStride/pixelStride，按它的布局逐行写，不猜内存
            writePlanes(img, y, u, v)
            // 不 close()：输入 Image 的底层缓冲由 queueInputBuffer 归还给编码器，
            // 提前 close 反而有在部分机型上让缓冲失效的风险（与 output Image 语义不同）
        } else {
            // 退路：拿不到 Image（该编码器不保证 Flexible 布局）时按 I420 紧密排布写整块缓冲。
            // 取舍：I420 假设 Y 后先全 U 再全 V；若编码器实际要 NV12（SemiPlanar）这类交错布局，
            // 颜色会偏——但 findYuvEncoder 已把 Flexible 排在首位，走到这里已是最差情况。
            if (buf == null) throw ArcFail(ArcRepairError.CPU_FEED)
            buf.clear()
            buf.put(y)
            buf.put(u)
            buf.put(v)
        }
        // size 取缓冲容量而非实写字节数：容量恒为合法值，编码器按自己的 format 布局去读；
        // 直接给 w*h*3/2 在带 padding 的输入面上会短于实际面，编码器可能读到未初始化尾部。
        enc.queueInputBuffer(idx, 0, bufCapacity, ptsUs, 0)
    }

    /** 等一枚空闲编码器输入缓冲；超时返回 -1。dequeue 自身带超时阻塞，故循环不热自旋 */
    private fun awaitEncoderInput(enc: MediaCodec): Int {
        val deadline = SystemClock.elapsedRealtime() + CPU_FEED_LIMIT_MS
        while (true) {
            val idx = try {
                enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                Log.e(TAG_ARC, "编码器输入出队异常：${e.message}")
                return -1
            }
            if (idx >= 0) return idx
            if (SystemClock.elapsedRealtime() > deadline) return -1
        }
    }

    /** 把紧打包的 Y/U/V 平面写进编码器输入 Image（尊重目标面的 rowStride/pixelStride） */
    private fun writePlanes(dst: Image, y: ByteArray, u: ByteArray, v: ByteArray) {
        writePlane(dst.planes[0], y, width, height)
        val cw = width / 2
        val ch = height / 2
        writePlane(dst.planes[1], u, cw, ch)
        writePlane(dst.planes[2], v, cw, ch)
    }

    /**
     * 单平面写出。[pw]×[ph] 是平面样本数（Y=w×h，UV=w/2×h/2）。
     * pixelStride==1（Y 与部分 Planar 实现）走整行批量 put，省掉逐字节 JNI；
     * 否则（NV12/NV21 交错 UV，pixelStride=2）按步进逐样本绝对 put。
     */
    private fun writePlane(plane: Image.Plane, src: ByteArray, pw: Int, ph: Int) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        if (pixStride == 1) {
            for (j in 0 until ph) {
                buf.position(j * rowStride)
                buf.put(src, j * pw, pw)
            }
        } else {
            var s = 0
            for (j in 0 until ph) {
                val base = j * rowStride
                for (i in 0 until pw) {
                    buf.put(base + i * pixStride, src[s + i])
                }
                s += pw
            }
        }
    }

    /**
     * 解码得到的 `YUV_420_888` → 三块紧打包平面（Y 每行前 w 字节，U/V 每行前 w/2 个样本）。
     * 逐行按源平面 stride 取样本，避免把 padding 带进混合算式（padding 参与平均会污染边界亮度）。
     */
    private fun readPlanes(image: Image, y: ByteArray, u: ByteArray, v: ByteArray) {
        copyPlane(image.planes[0], y, width, height)
        copyPlane(image.planes[1], u, width / 2, height / 2)
        copyPlane(image.planes[2], v, width / 2, height / 2)
    }

    /** 单平面读入：[pw]×[ph] 为样本数；Y 面 pixelStride 通常为 1，UV 面 1（Planar）或 2（SemiPlanar） */
    private fun copyPlane(plane: Image.Plane, dst: ByteArray, pw: Int, ph: Int) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        var o = 0
        if (pixStride == 1) {
            for (j in 0 until ph) {
                buf.position(j * rowStride)
                buf.get(dst, o, pw)
                o += pw
            }
        } else {
            for (j in 0 until ph) {
                val base = j * rowStride
                for (i in 0 until pw) {
                    dst[o + i] = buf.get(base + i * pixStride)
                }
                o += pw
            }
        }
    }

    /**
     * 给编码器发 EOS。
     * - GPU：输入面路线的专用哨兵 [MediaCodec.signalEndOfInputStream]（行为逐字不变）；
     * - CPU：**没有输入面**，那个 API 不适用；改为占用一枚 input buffer 并打上 END_OF_STREAM 标志。
     * 两路都吞异常（EOS 发不出去时交给后续排空/超时兜底，不因此丢掉已经编好的成片）。
     */
    private fun sendEncoderEos() {
        val enc = vEnc ?: return
        if (useGpu) {
            runCatching { enc.signalEndOfInputStream() }
            return
        }
        val idx = awaitEncoderInput(enc)
        if (idx < 0) {
            Log.e(TAG_ARC, "CPU 路线发不出 EOS（等不到输入缓冲）")
            return
        }
        try {
            enc.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (e: IllegalStateException) {
            Log.e(TAG_ARC, "CPU 路线 EOS 入队失败：${e.message}")
        }
    }

    /** 每发出一枚保留帧就 +1 并刷新进度（总输出帧数 = 保留帧数，见 [ArcRepairPlan]） */
    private fun countOutput(n: Int) {
        outputsDone += n
        onProgress(ArcRepairPlan.progressOf(outputsDone, dstTotal))
    }

    // endregion

    // region 编码器排空与封装

    private fun drainEncoder(finalDrain: Boolean) {
        val enc = vEnc ?: return
        val m = muxer ?: return
        val bi = encBufInfo
        // EOS 之后还按 0 超时轮询会变成热自旋，所以只要发了 EOS 就用正常超时等待
        val timeoutUs = if (finalDrain || encEosSent) DEQUEUE_TIMEOUT_US else 0L
        var deadline = 0L
        while (true) {
            val idx = try {
                enc.dequeueOutputBuffer(bi, timeoutUs)
            } catch (e: IllegalStateException) {
                Log.e(TAG_ARC, "编码器出队异常：${e.message}")
                videoDone = true
                return
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (videoTrack < 0) {
                        videoTrack = m.addTrack(enc.outputFormat)
                        Log.i(TAG_ARC, "视频轨 track=$videoTrack fmt=${enc.outputFormat}")
                        startMuxerIfReady(m)
                    }
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (videoDone) return
                    if (!finalDrain) return
                    if (deadline == 0L) deadline = SystemClock.elapsedRealtime() + ENC_DRAIN_LIMIT_MS
                    if (SystemClock.elapsedRealtime() > deadline) {
                        Log.e(TAG_ARC, "编码器排空超时")
                        videoDone = true
                        return
                    }
                }
                idx >= 0 -> {
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val cfg = bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxStarted && !eos && !cfg && bi.size > 0) writeVideo(enc, idx, bi)
                    runCatching { enc.releaseOutputBuffer(idx, false) }
                    if (eos) {
                        videoDone = true
                        return
                    }
                }
            }
        }
    }

    private fun startMuxerIfReady(m: MediaMuxer) {
        if (videoTrack < 0 || muxStarted) return
        try {
            m.start()
            muxStarted = true
            Log.i(TAG_ARC, "muxer 已启动 video=$videoTrack audio=$audioTrack")
        } catch (e: IllegalStateException) {
            Log.e(TAG_ARC, "muxer start 失败：${e.message}")
        }
    }

    /** 视频样本统一到本片基（首样本归零），并按时间轴把音频样本穿插写出去 */
    private fun writeVideo(enc: MediaCodec, idx: Int, bi: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        val buf = enc.getOutputBuffer(idx) ?: return
        if (videoPtsBase < 0L) videoPtsBase = bi.presentationTimeUs
        val norm = (bi.presentationTimeUs - videoPtsBase).coerceAtLeast(0L)
        // ⚠ flags 必须跟着样本走：MediaMuxer 靠 BufferInfo.flags 里的 BUFFER_FLAG_KEY_FRAME 标同步帧。
        // 真机实测传 0 会让容器里一个关键帧都没有，产物连流参数都解析不出（ffprobe: width=0/height=0/
        // codec_name=unknown）。CODEC_CONFIG 帧在上游已跳过，这里的 flags 只可能是关键帧位或 0。
        vBufInfo.set(bi.offset, bi.size, norm, bi.flags)
        try {
            m.writeSampleData(videoTrack, buf, vBufInfo)
        } catch (e: Exception) {
            Log.e(TAG_ARC, "写视频样本失败：${e.message}")
        }
        drainAudioUpTo(norm)
    }

    /** 音频直拷：源已是 AAC，格式（含 csd）直接 addTrack，样本原样写出去 */
    private fun drainAudioUpTo(limitUs: Long) {
        val ex = aEx ?: return
        val m = muxer ?: return
        val tr = audioTrack
        if (tr < 0 || audioDone || !muxStarted) return
        while (true) {
            if (!audioPending) {
                audioBuf.clear()
                val size = try {
                    ex.readSampleData(audioBuf, 0)
                } catch (e: Exception) {
                    -1
                }
                if (size < 0) {
                    audioDone = true
                    return
                }
                audioPending = true
                audioPendingPts = ex.sampleTime
                audioPendingSize = size
            }
            if (audioPtsBase < 0L) audioPtsBase = audioPendingPts
            val norm = (audioPendingPts - audioPtsBase).coerceAtLeast(0L)
            if (norm > limitUs) return
            audioBuf.position(0)
            audioBuf.limit(audioPendingSize)
            aBufInfo.set(0, audioPendingSize, norm, 0)
            try {
                m.writeSampleData(tr, audioBuf, aBufInfo)
            } catch (e: Exception) {
                Log.e(TAG_ARC, "写音频样本失败：${e.message}")
            }
            audioPending = false
            if (!ex.advance()) {
                audioDone = true
                return
            }
        }
    }

    // endregion

    // region 收尾

    private fun finish(ok: Boolean, code: String?): ArcRepairResult {
        // 顺序：codec → muxer（写 moov）→ 输出句柄 → EGL → 输入面 → extractor
        releaseCodec(vEnc)
        vEnc = null
        releaseCodec(vDec)
        vDec = null

        val m = muxer
        muxer = null
        if (m != null) {
            runCatching { m.stop() }.onFailure { Log.i(TAG_ARC, "muxer stop 忽略：${it.message}") }
            runCatching { m.release() }
        }
        arcGl?.release()
        arcGl = null
        val es = encSurface
        encSurface = null
        runCatching { es?.release() }

        val t = target
        target = null
        t?.close()
        runCatching { vEx?.release() }
        vEx = null
        runCatching { aEx?.release() }
        aEx = null

        val st = store
        val sk = sink
        sink = null
        if (st == null || sk == null) {
            return ArcRepairResult(false, null, null, 0L, code ?: ArcRepairError.NO_OUTPUT, note)
        }
        val seg = st.seal(sk, 0, 0L, if (ok) durationMs else 0L, keep = ok)
        return if (ok) {
            ArcRepairResult(true, seg.uri, seg.path, seg.bytes, null, note)
        } else {
            ArcRepairResult(false, null, null, 0L, code ?: ArcRepairError.ENGINE, note)
        }
    }

    private fun releaseCodec(c: MediaCodec?) {
        if (c == null) return
        runCatching { c.stop() }.onFailure { Log.i(TAG_ARC, "codec stop 忽略：${it.message}") }
        runCatching { c.release() }.onFailure { Log.e(TAG_ARC, "codec release 异常：${it.message}") }
    }

    // endregion

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.longOr(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback
}
