package com.wotagei.cam.record

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.wotagei.cam.diag.Diag as Log
import android.view.Surface
import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.media.MediaRepo
import java.io.IOException
import java.nio.ByteBuffer

/** 光弧修复的日志锚点（与录制 `WotaRecord` 分开，方便真机过滤） */
private const val TAG_ARC = "WotaArcRepair"

/** 解码器输入/出、编码器出的轮询粒度 */
private const val DEQUEUE_TIMEOUT_US = 10_000L

/** 编码器 EOS 后允许吐完缓存的上限（防 stop 卡死） */
private const val ENC_DRAIN_LIMIT_MS = 3_000L

/** 估算总耗时的安全系数 + 固定余量：超时即失败收尾，绝不无限等 */
private const val DEADLINE_FACTOR = 8L
private const val DEADLINE_EXTRA_MS = 120_000L

/**
 * 准备期（打开源/量帧率/预扫音轨/建编解码器）的独立截止。
 * 旧实现只在主循环建 deadline：源在 setDataSource/帧率遍历上卡住时任务可以无限期挂着，
 * 取消也要等 open() 自己返回才有检查点。60s 覆盖冷启动大文件的一次完整预扫。
 */
private const val PREPARE_DEADLINE_MS = 60_000L

/** 准备期循环里每扫这么多条样本查一次取消/截止（大文件也不至于几百毫秒才响应一次） */
private const val ABORT_CHECK_STRIDE = 64

/** 音频可写性预扫的样本上限（防病态容器把建轨前检查拖死；超过上限按不可写处理，只影响该 lane） */
private const val MAX_AUDIO_PRESCAN_SAMPLES = 100_000

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

/**
 * 光弧修复的**后台执行器**（用户需求第 7 项）：把一段视频处理成"插帧后"的新文件并入库。
 *
 * 形态克隆自 `ui/CameraScreen.kt` 的 `RecordRunner`：自己的 `HandlerThread`；**状态与结果不在本类**——Y23 把任务所有者迁到播放页的 Activity
 * 宿主（[com.wotagei.cam.record.ArcRepairTasks] / `ArcRepairHost`），执行器只经回调交付
 * 进度与终态。旧实现把 `MutableStateFlow` 挂在执行器上、执行器又挂在页面 `remember` 里：
 * 退页或配置重建后状态整份消失，重进页面还会另起一条执行器与在跑的任务并行。
 *
 * **不是播放时的实时处理**：整个管线（解 → 插帧 → 编码 → 封装）跑在这条后台线程上，
 * 与预览/播放互不相干。用户点按钮才启动（v1 只提供入口，不含自动触发）。
 */
class ArcRepairRunner(private val ctx: Context) {

    private val thread = HandlerThread("WotaArcRepair").apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * 任务所有权/取消门（Y23）：**投递之前**在调用线程领 token，拿不到说明已有任务在跑。
     * 旧实现先 post 再查 `busy`，两次 start 依次入队后会真的跑两轮；`stopAsync` 的进程级
     * 标志又会被排队中的 start 在消息内复位，取消被下一轮吞掉。现在取消按 token 记账，
     * 且只对当前轮生效（迟到的取消不会污染下一轮）。
     */
    private val gate = ArcRepairTaskGate()

    /**
     * 宿主已收尾（Activity 被销毁）、任务跑完后自行退线程。**不取消**正在跑的任务（用户已
     * 发起的修复要让它写完），只把"线程何时退"从"必须有人再次调 shutdown()"改成"任务收尾时自己退"。
     */
    @Volatile
    private var quitAfterTask = false

    /**
     * 启动一次修复。
     * @param srcUri 源视频（媒体库里的 content uri）
     * @param useGpu true = GPU 路线（EGL 到编码器面）；false = CPU 路线（4:2:0 2×2 块整像素选择后直写编码器输入缓冲）
     * @param dstFps 抽帧目标帧率（产品口径 = 强制档 24/25）；源实测帧率不高于它时任务以 NO_NEED 收场
     * @param deadband 补弧合并差分死区 D（0..32）；缺省取参数总线发布的只读快照（默认 12）
     * @param capOffsetMs 播放页缓存的音轨同步偏移（内录 − 环境，正=内录晚）；仅双音轨第 2 条 lane 应用
     * @param onProgress 进度回调（阶段化，封口前封顶 0.98）；在本执行器线程上调用
     * @param onResult 终态回调（**恰好一次**，含排队期取消）；在本执行器线程上调用
     * @return 本轮的 token；**null = 没有领到所有权**（已有任务在跑 / 线程已退），调用方必须按忙处理，
     *         不许假装已起跑（旧实现先写 RUNNING 再 post，post 失败会留下永久 RUNNING 的幽灵任务）。
     */
    fun start(
        srcUri: Uri,
        useGpu: Boolean,
        dstFps: Int,
        deadband: Int = ArcMergeDeadband.deadband,
        capOffsetMs: Int = 0,
        onProgress: (Float) -> Unit = {},
        onResult: (ArcRepairResult) -> Unit = {}
    ): Long? {
        // 所有权在**调用线程**领取（Y23）：拿到 token 才代表这一轮真的存在，未拿到直接忽略。
        // 这样连续 start 两次只有第一轮会执行，也不会出现"排队后再复位取消"。
        val token = gate.begin() ?: run {
            Log.i(TAG_ARC, "已有任务在跑，拒绝本次 start")
            return null
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        val posted = handler.post {
            // token 可能在 post 之后、执行之前被取消（排队/准备期取消立即生效）
            if (!gate.isCurrent(token) || gate.isCancelled(token)) {
                Log.i(TAG_ARC, "任务在排队期被取消")
                finishTask(token, ArcRepairResult(false, null, null, 0L, ArcRepairError.CANCELLED), onResult)
                return@post
            }
            val out = try {
                ArcRepairSession(
                    ctx = ctx,
                    src = srcUri,
                    useGpu = useGpu,
                    dstFps = dstFps,
                    deadband = ArcMergeDeadband.clamp(deadband),
                    capOffsetMs = capOffsetMs,
                    taskStartedAtMs = startedAtMs,
                    // 进度也过 token 门：取消/换代之后迟到的进度不得再改写宿主显示
                    onProgress = { p -> if (gate.isCurrent(token) && !gate.isCancelled(token)) onProgress(p) },
                    cancelled = { gate.isCancelled(token) }
                ).run()
            } catch (e: Throwable) {
                // 会话内部已把异常转成结果；这里再兜一层，保证"绝不崩"
                Log.e(TAG_ARC, "会话未按预期返回：${e.javaClass.simpleName} ${e.message}")
                ArcRepairResult(false, null, null, 0L, "${ArcRepairError.ENGINE}:${e.javaClass.simpleName}")
            }
            finishTask(token, out, onResult)
        }
        if (!posted) {
            // 线程已退（宿主收尾后又被起跑）：归还所有权并如实报告没起跑，不留幽灵任务
            gate.end(token)
            Log.w(TAG_ARC, "修复线程已退出，拒绝本次 start")
            return null
        }
        return token
    }

    /** 一轮任务收尾：先归还所有权，再把终态交给宿主；最后按需退线程 */
    private fun finishTask(token: Long, out: ArcRepairResult, onResult: (ArcRepairResult) -> Unit) {
        gate.end(token)
        Log.i(TAG_ARC, "任务结束 ok=${out.ok} err=${out.error} id=${mediaIdOf(out.uri)} note=${out.note}")
        // 终态由任务宿主发布（Y23）：先归还所有权，宿主即可接纳下一轮任务。
        onResult(out)
        // 从 handler 线程调用 quitSafely() 是合法的（当前消息处理完后终止 Looper）。
        if (quitAfterTask) thread.quitSafely()
    }

    /**
     * 请求取消（非阻塞）：只取消 [token] 那一轮；准备期各检查点会立即抛 CANCELLED。
     * 已结束/未知 token 一律无效——晚取消不会串到之后起跑的新一轮。
     */
    fun cancel(token: Long) {
        gate.cancel(token)
    }

    /**
     * 退出宿主（**不取消**正在跑的任务）：空闲则立即退线程，忙碌则等任务收尾后自行退。
     * 这是 Activity 结束时的唯一收尾口：既不丢用户已发起的修复，也不让 HandlerThread 永久驻留。
     * （"必须立刻停"的语义由 [cancel] + 会话内各检查点承担，不再提供阻塞式 join：收尾由任务自己的
     * finish 会话保证，宿主的回调是终态的唯一出口。）
     */
    fun shutdownAfterTask() {
        quitAfterTask = true
        handler.post {
            // 若还有任务持有所有权，这里先退让；任务收尾时会读 quitAfterTask 并自行 quitSafely。
            // 空闲时本消息就是队尾，直接退线程。
            if (!gate.hasTask()) thread.quitSafely()
        }
    }
}

/** 运行期失败信号（带机器可读码，UI 按码映射文案） */
private class ArcFail(val code: String) : Exception(code)

/**
 * 一次修复会话：资源全在这里建、在这里收，[run] 无论成败都返回结果（不抛给外部）。
 *
 * 管线（v2 抽帧+补弧口径）：源 `MediaExtractor`（视频轨）→ 解码 → 按 [ArcRepairFlow] 指令
 * 抽帧/整像素选择补弧 → 只把保留帧送编码器 → **每条音频轨各自的** `MediaExtractor`（源里全部
 * audio/ 轨逐轨直拷，录制端双 AAC：环境麦克 + 内录 AudioPlaybackCapture）→ `MediaMuxer`
 * 封装 → `VideoStore` 入库（`IS_PENDING` 两段式）。
 */
private class ArcRepairSession(
    private val ctx: Context,
    private val src: Uri,
    private val useGpu: Boolean,
    private val dstFps: Int,
    private val deadband: Int,
    /** 播放页缓存的同步偏移（ms，正=内录晚）；只作用于双音轨第 2 条，单音轨恒 0 */
    private val capOffsetMs: Int,
    /** 本轮任务**投递前**的时刻（Y23）：准备期也要有截止，不能等主循环才起算 */
    private val taskStartedAtMs: Long,
    private val onProgress: (Float) -> Unit,
    private val cancelled: () -> Boolean
) {

    /**
     * 当前阶段的截止时刻。准备期（open）用 [PREPARE_DEADLINE_MS]；读到源时长后扩成
     * `时长×DEADLINE_FACTOR + 余量`，主循环与收尾共用同一枚绝对时刻。
     */
    private var deadlineMs = taskStartedAtMs + PREPARE_DEADLINE_MS

    private var store: VideoStore? = null
    private var sink: OutputSink? = null
    private var target: OutputTarget? = null
    private var muxer: MediaMuxer? = null
    private var vDec: MediaCodec? = null
    private var vEnc: MediaCodec? = null
    private var vEx: MediaExtractor? = null
    private var arcGl: ArcRepairGl? = null
    private var encSurface: Surface? = null

    private var videoTrack = -1
    private var muxStarted = false

    /**
     * 音频轨是否已加入 muxer（Y25）：延迟到第一帧解码后、muxer.start 之前才 addTrack。
     * 只有那时 [decodePtsBase] 才是**拷贝真正使用的时基**，预扫与拷贝才能共用同一判据；
     * open() 里提前建轨正是"预扫说可写、实际零样本"的来源。
     */
    private var audioTracksAdded = false

    /** 本会话已**成功**写出的视频样本数（写失败不计数）：成功判据第三格，见 [arcRepairOk] */
    private var writtenVideoSamples = 0
    private var videoDone = false
    private var encEosSent = false
    private var srcIndex = 0

    /**
     * 音频直拷 lane：源里**每条** audio/ 轨一条（录制端双 AAC 体系 = track1 环境麦克 +
     * track2 内录 AudioPlaybackCapture，见 CodecRecorder）。单轨时代只拷第一条、内录轨被整体
     * 丢掉（本缺陷点）；现在逐轨各建一条、互不串扰。空表 = 源无音频轨（聚合口径恒 done）。
     */
    private var audioLanes: List<AudioCopyLane> = emptyList()

    /**
     * 首帧解码 PTS：之后所有时间戳都相对它归一（去重判距、音频准入与拷贝共用同一枚基准）。
     *
     * **不能拿符号当"未定"哨兵**（Y25）：负 PTS 前导是源容器里的合法样本，首帧基准本身就可能
     * 为负。旧写法 `if (decodePtsBase < 0L)` 会把之后每一帧都当成首帧反复改写基准，音频预扫
     * 又把负基准误当"未知"退回 0 ⇒ 准入与拷贝各锚一个时基，整条音轨错位。基准是否已确定
     * 由 [decodePtsBaseKnown] 单独记账。
     */
    private var decodePtsBase = 0L
    private var decodePtsBaseKnown = false

    /** 上一解码帧的归一化 PTS：去重判距用 */
    private var prevPtsUs = 0L
    private var outputsDone = 0
    private var dstTotal = 1
    private var width = 0
    private var height = 0
    private var srcFps = FALLBACK_SRC_FPS

    /**
     * 实测源帧率**有理数真值**（M6-01）：抽帧计划、源帧数估算、NO_NEED 门槛、去重阈值都吃它；
     * [srcFps] 只是它的整数视图（roundedInt），仅配给日志/进度/成片名前缀这类只看量级的点。
     */
    private var srcRate = ArcRateProbe.MeasuredSrcFps(FALLBACK_SRC_FPS.toLong(), 1L)
    private var durationMs = 0L
    private var videoPtsBase = -1L
    private var note: String? = null

    /** muxer 写样本失败的失败码：run() 收尾时优先于 NO_VIDEO（与 CodecRecorder.writeSample 的 :MUX 映射同语义） */
    private var writeFailCode: String? = null

    /**
     * 编码器排空失败的失败码（Y24）：dequeue 异常或末次排空超时。
     * 旧实现把 `videoDone=true` 后照样返回成功，末帧被吞也判完成；现在由 [arcRunVerdict]
     * 把它带进结果码（非纯成功）。
     */
    private var encodeFailCode: String? = null

    /**
     * #8 单调兜底账本（桥见 [monotonicPts]，与录制链 CodecRecorder.writeSample 同口径）：
     * 视频一枚 + 每条音频 lane 各一枚（lastPts/修正计数/最大倒退量随 lane 走，见 [AudioCopyLane]）。
     * 修复链虽走重编码，但 B 帧源按解码序喂入时编码器输出 PTS 仍可能非严格递增（部分 ROM
     * 编码器行为），同轨倒退照样抛 Muxer 异常。
     */
    private var vMuxLastPtsUs = -1L
    private var vPtsFixes = 0
    private var vPtsMaxBackUs = 0L

    /**
     * 写样本失败计数（视频 / 音频 lane 合计）：逐样本失败是既有口径（不中断修复），
     * 但 B06 要求首条留异常明细、后续只计数，收尾给一行汇总——按帧刷 e 级会淹掉日志。
     * 失败码仍记 [writeFailCode]，与计数互不影响。
     */
    private var videoWriteFails = 0
    private var audioWriteFails = 0

    /** 抽帧计划与它的流式桥（open() 里建，主循环逐帧问指令） */
    private lateinit var flow: ArcRepairFlow

    // --- 复用件（音频 lane 的 bufInfo/缓冲在 lane 自带，多轨不共用）
    private val vBufInfo = MediaCodec.BufferInfo()
    private val encBufInfo = MediaCodec.BufferInfo()

    // --- CPU 路线的平面缓冲（open() 里一次分配，逐帧复用，全为零分配）
    // cur = 当前解码帧、pend = 滞留待发的保留帧、acc = 被抽帧的整像素选择累积面。
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
            // 成功判据走纯函数 arcRepairOk（JVM 全表单测）：muxer 启动 + 视频轨已建 + 视频样本
            // 真的写出去过。只拦"零样本整废"（盘满时逐样本写失败 ⇒ 成片只剩 ftyp 头也曾被报成功），
            // "部分样本失败"仍算成功（可播但缺帧的产物改判失败代价不对称，取舍见 arcRepairOk 注释）。
            // Y24：编码器排空异常/末次超时留下的 encodeFailCode 必须把结果从"纯成功"里剔出去。
            val verdict = arcRunVerdict(
                ok = arcRepairOk(muxStarted, videoTrack >= 0, writtenVideoSamples),
                encodeFail = encodeFailCode,
                writeFail = writeFailCode,
                fallback = ArcRepairError.NO_VIDEO
            )
            ok = verdict.ok
            if (!ok && code == null) code = verdict.code
            // 修正汇总（#8，B 帧源预期行为）：逐样本明细在 d 档（默认关），终止只落一条 i 级。
            // 插值用 ${} 花括号形式（与 CodecRecorder 段尾汇总同款）：源码守卫只认花括号里的代码
            val aPtsFixes = audioLanes.sumOf { it.ptsFixes }
            val aPtsMaxBackUs = audioLanes.maxOfOrNull { it.ptsMaxBackUs } ?: 0L
            if (vPtsFixes > 0 || aPtsFixes > 0) {
                Log.i(TAG_ARC, "pts修正=v${vPtsFixes}(back=${vPtsMaxBackUs}) a${aPtsFixes}(back=${aPtsMaxBackUs})")
            }
        } catch (e: ArcFail) {
            code = e.code
            Log.i(TAG_ARC, "修复中止：${e.code}")
        } catch (e: Exception) {
            code = "${ArcRepairError.ENGINE}:${e.javaClass.simpleName}"
            Log.e(TAG_ARC, "修复异常：${e.javaClass.simpleName} ${e.message}")
        } catch (e: Error) {
            code = "${ArcRepairError.ENGINE}:${e.javaClass.simpleName}"
            Log.e(TAG_ARC, "修复错误：${e.javaClass.simpleName} ${e.message}")
        } finally {
            // 写样本失败汇总（B06）：逐样本 e 级只留首条，收尾必须把总数说清楚（否则"部分失败"
            // 与"一直失败"在日志里无法区分）。放 finally：中途 ArcFail 退出也要给出计数。
            if (videoWriteFails > 0 || audioWriteFails > 0) {
                Log.i(TAG_ARC, "写样本失败 视频=$videoWriteFails 音频=$audioWriteFails（首条异常见上文）")
            }
        }
        return finish(ok, code)
    }

    // region 打开与建轨

    private fun open() {
        // 排队期/准备期的取消必须在这里就生效（Y23）：旧实现只有主循环每轮才看 cancel，
        // setDataSource/帧率预扫/建编解码器这段几十秒里点取消没有任何反应。
        checkAbort()
        val videoEx = MediaExtractor()
        vEx = videoEx
        try {
            videoEx.setDataSource(ctx, src, null)
        } catch (e: IOException) {
            throw ArcFail(ArcRepairError.SOURCE)
        }
        checkAbort()
        val vTrack = findTrack(videoEx, "video/")
        if (vTrack < 0) throw ArcFail(ArcRepairError.SOURCE)
        videoEx.selectTrack(vTrack)
        val vf = videoEx.getTrackFormat(vTrack)
        width = vf.intOr(MediaFormat.KEY_WIDTH, 0)
        height = vf.intOr(MediaFormat.KEY_HEIGHT, 0)
        val containerFps = vf.intOr(MediaFormat.KEY_FRAME_RATE, FALLBACK_SRC_FPS)
        durationMs = vf.longOr(MediaFormat.KEY_DURATION, 0L) / 1000L
        val mime = vf.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
        // 容器旋转角双源：retriever 的 METADATA_KEY_VIDEO_ROTATION（= 显示所需旋转角）才是权威口径，
        // 以它为准；extractor 的 KEY_ROTATION 兜住"部分机型/容器给 0"。⚠ 双源只决定"读出多少度"：
        // 2026-10-06 成片差 180° 的读数**本就正确**，真因是像素被平台多转一次（见 decoderFormatOf）。
        val hintExtractor = vf.intOr(MediaFormat.KEY_ROTATION, 0)
        val hintRetriever = readRetrieverRotation(ctx, src)
        val hint = matchDecision(hintExtractor, hintRetriever, hintRetriever >= 0)
        val hintWritten = exportOrientationHint(hint, ORIENTATION_MUXER_CCW)
        if (width <= 0 || height <= 0) throw ArcFail(ArcRepairError.SOURCE)

        // 源帧率**实测**（ArcRateProbe 帧距中位数，有理数真值链）：强制 24/25fps 档容器会标假 fps
        // （标 24 实跑 30），按容器值造抽帧计划会漏抽；中位数对重复/空样本免疫，是遍历计数教训的
        // 正确解法。M6-01：计划/门槛/估算/去重阈值全吃有理数真值（29.97 整成 30 会让保留位次漂
        // 一位、25.4 整成 25 会误判无需抽帧），整数视图只喂日志/进度/成片名前缀
        // M6-05：帧距保 **Long** 精度——>2³¹µs 的轨道断层若先降 Int 再进滤噪窗，回卷值可能恰好
        // 落进合法窗、毒化中位数；Long 判据把它原样挡在窗外（整数源逐点行为不变，见 ArcRateProbe）
        val rawDeltas = ArrayList<Long>(512)
        var prevSampleUs = -1L
        var scanned = 0
        // Y25：EOF 以**样本存在性**（sampleSize）判定，不看 sampleTime 的符号——B04 已确认
        // 负 PTS 前导是合法样本，旧条件 `sampleTime >= 0` 会在首枚负前导处提前收工、退回容器帧率。
        while (scanned < MAX_SRC_FRAMES && videoEx.sampleSize >= 0L) {
            val t = videoEx.sampleTime
            if (prevSampleUs >= 0L) rawDeltas.add(t - prevSampleUs)
            prevSampleUs = t
            scanned++
            if (scanned % ABORT_CHECK_STRIDE == 0) checkAbort()
            if (!videoEx.advance()) break
        }
        checkAbort()
        srcRate = ArcRateProbe.measuredSrcFps(rawDeltas, containerFps.toLong(), 1L)
        srcFps = srcRate.roundedInt()
        videoEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        // 源帧数按时长 × 实测帧率估算，只喂进度分母与计划日志；输出由解码顺序驱动
        // （真机实测遍历计数不可靠，见 v1 注释；时长缺失时才退回数样本）
        val srcFrames = if (durationMs > 0 && srcRate.num > 0) {
            ((durationMs * srcRate.num + 500L * srcRate.den) / (1000L * srcRate.den)).toInt().coerceAtLeast(1)
        } else {
            countSamples(videoEx)
        }
        videoEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        // 音频可写判据**不再**在这里预扫（Y25）：那会让预扫与拷贝各用一个时基
        // （旧预扫吃视频首样本 PTS，拷贝吃首帧显示 PTS）。统一在 muxer.start 之前、
        // decodePtsBase 已确定时跑一次，见 startMuxerIfReady。
        if (dstFps <= 0) throw ArcFail(ArcRepairError.SOURCE)
        if (srcRate.atMostInt(dstFps)) throw ArcFail(ArcRepairError.NO_NEED)
        // 源时长已知：准备期截止扩成正式任务截止（主循环与收尾共用这一枚绝对时刻）
        deadlineMs = taskStartedAtMs +
            (if (durationMs > 0) durationMs * DEADLINE_FACTOR else DEADLINE_EXTRA_MS) + DEADLINE_EXTRA_MS
        checkAbort()
        val plan = ArcRepairPlan.of(srcFrames, srcRate.num, srcRate.den, dstFps)
        if (plan.dstFrames <= 0) throw ArcFail(ArcRepairError.SOURCE)
        dstTotal = plan.dstFrames
        flow = ArcRepairFlow(plan)
        note = "源实测≈${srcFps}fps（真值 ${srcRate.num}/${srcRate.den}，容器标 $containerFps）→ " +
            "目标 ${dstFps}fps，被抽帧画面已按整像素选择（差分死区 D=$deadband）并入前后帧；不改原片"
        Log.i(
            TAG_ARC,
            "源 ${width}x$height 容器fps=$containerFps 实测fps=$srcFps 实测=${srcRate.num}/${srcRate.den} " +
                "srcFrames=$srcFrames → dstFps=$dstFps dstFrames=$dstTotal 抽≈${srcFrames - dstTotal} " +
                "route=${if (useGpu) "GPU" else "CPU"} deadband=$deadband " +
                "hintExtractor=$hintExtractor hintRetriever=$hintRetriever hintWritten=$hintWritten"
        )

        // 音频直拷**双轨化**：源里全部 audio/ 轨各建一条 lane（录制端双 AAC = track1 环境麦克、
        // track2 内录 AudioPlaybackCapture）。单轨时代 findTrack 命中即返回，内录轨被整体丢掉
        // ——本缺陷点。枚举复用视频 extractor（数轨不动样本游标）；建 lane 时每轨**各配一枚新
        // extractor**：同一 extractor 只能 select 一条轨（约束不变，×N）。
        audioLanes = openAudioLanes(audioTrackIndices(videoEx))
        checkAbort()

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
        checkAbort()
        // 音频轨**不在这里 addTrack**（Y25）：等首帧解码后由 startMuxerIfReady 用拷贝真正
        // 使用的时基（decodePtsBase）逐 lane 验收，零可写样本的 lane 直接不建轨。提前建轨
        // 会在"只有视频起点前短样本"的分布上留下零样本轨，muxer.stop 抛错、整份产物被回收。
        for (lane in audioLanes) {
            Log.i(TAG_ARC, "音频 lane 待建轨（start 前验收可写性）label=${lane.label} srcTrack=${lane.trackIndex}")
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
        // 喂解码器的 format **必须剥掉容器旋转角**（KEY_ROTATION）：平台把"带旋转的 format + 有输出面"
        // 解释成"把旋转烘进输出缓冲"（Surface 的缓冲变换 → SurfaceTexture 变换矩阵 → ArcRepairGl 的
        // OES 拷贝趟照单全收），落进编码器的像素于是已转过一次，而 muxer 又写了同一个 hint
        // ⇒ 双重旋转 —— 2026-10-06 真机成片相对源片恰差 180° 的根因（CPU 路无输出面，故只有 GPU 路出事）。
        // 剥离后像素保持原始朝向、与写出的 hint 一致；width/height 原样带过去，尺寸不变（90° 源不会被压扁）。
        // 两路都过同一个 decFmt（同一纯函数、同一实例）：CPU 路无面时剥离无副作用，但保持一致，
        // 避免将来有人只改一路。三条 hint* 日志照旧，另打一条剥离自证（见下）。
        val decFmt = decoderFormatOf(vf)
        Log.i(
            TAG_ARC,
            "解码器 format 旋转剥离：route=${if (useGpu) "GPU" else "CPU"} surfaceOutput=$useGpu " +
                "源KEY_ROTATION=$hintExtractor 需剥=${bakedRotationNeedsStrip(hintExtractor, useGpu)} " +
                "decFmt仍含KEY_ROTATION=${decFmt.containsKey(MediaFormat.KEY_ROTATION)} " +
                "decFmt=${decFmt.intOr(MediaFormat.KEY_WIDTH, 0)}x${decFmt.intOr(MediaFormat.KEY_HEIGHT, 0)} " +
                "mime=${decFmt.getString(MediaFormat.KEY_MIME)} csd0=${decFmt.containsKey("csd-0")} " +
                "入参vf仍含KEY_ROTATION=${vf.containsKey(MediaFormat.KEY_ROTATION)}（须 true=未污染入参）"
        )
        if (useGpu) {
            val gl = ArcRepairGl(width, height, deadband = deadband)
            arcGl = gl
            if (!gl.start()) throw ArcFail(ArcRepairError.GPU)
            val decSurface = gl.decoderSurface ?: throw ArcFail(ArcRepairError.GPU)
            dec.configure(decFmt, decSurface, null, 0)
        } else {
            // CPU：ByteBuffer 模式拿 YUV（无输出面但 getOutputImage 可读），按 2×2 块整像素选择后
            // 直写编码器输入缓冲；4:2:0 要求偶数宽高，allocPlanes 里显式报 ARC_CPU_YUV。
            dec.configure(decFmt, null, null, 0)
            allocPlanes()
            note = "CPU 路线：4:2:0 2×2 块整像素选择（差分死区 D=$deadband）后直写编码器输入缓冲；" +
                "单帧成本随分辨率上升，长视频明显慢于 GPU。帧时间戳由 queueInputBuffer 显式给出，" +
                "与 GPU 路线同一口径。$note"
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
        // 4:2:0 的 U/V 是每 2×2 块一个样本：奇数宽高若静默 width/2 会截掉最后一列/行，
        // 整像素选择就会漏掉边缘块。CPU 路线无面输出，先验尺寸并如实失败（不猜裁切口径）。
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            Log.e(TAG_ARC, "CPU 路线 4:2:0 要求正偶数宽高，实际 ${width}x$height")
            throw ArcFail(ArcRepairError.CPU_YUV)
        }
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

    /** 源容器里**全部** audio/ 轨号（升序 = 容器轨序；录制端写入序为环境先、内录后，lane 顺序沿用） */
    private fun audioTrackIndices(ex: MediaExtractor): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) out.add(i)
        }
        return out
    }

    /** 每条 audio/ 轨建一条 lane、各配一枚新 extractor；单轨打开失败只弃该轨，按无音频继续 */
    private fun openAudioLanes(idxs: List<Int>): List<AudioCopyLane> {
        val lanes = ArrayList<AudioCopyLane>(idxs.size)
        idxs.forEachIndexed { li, ti ->
            checkAbort()
            val ex = MediaExtractor()
            try {
                ex.setDataSource(ctx, src, null)
                ex.selectTrack(ti)
                // 轨序即识别口径：0=环境、1=内录。只有「确有第二轨」且选中 lane 1 才应用后校准；
                // 单音轨/无法识别内录时 shift 恒 0，绝不猜。
                val shiftUs = if (idxs.size >= 2 && li == 1 && capOffsetMs != 0) {
                    -capOffsetMs.toLong() * 1000L
                } else {
                    0L
                }
                // 零样本 lane 防线（Y25）：可写性不再在这里预扫，建 lane 只负责打开提取器；
                // 真正的"至少一枚可写样本"验收在 startMuxerIfReady（与拷贝同一时基、同一判据）。
                lanes.add(AudioCopyLane("audio$li", ex, ex.getTrackFormat(ti), ti, shiftUs))
            } catch (e: IOException) {
                Log.i(TAG_ARC, "音频轨 ti=$ti 建 lane 失败，该轨按无音频继续：${e.message}")
                runCatching { ex.release() }
            } catch (e: Throwable) {
                // 非 IOException（SecurityException/IllegalState/参数异常）会让整个 open() 抛出：
                // 此时 lanes 只在局部变量里，finish() 的 audioLanes 还看不到它们——当前 extractor
                // 与已建 lane 都会漏。先在异常路径里全部归还，再把异常原样抛给会话收尾。
                runCatching { ex.release() }
                lanes.forEach { it.release() }
                throw e
            }
        }
        return lanes
    }

    /** 数视频轨样本数（索引式 advance，不解码）；数完必须 seek 回起点 */
    private fun countSamples(ex: MediaExtractor): Int {
        var n = 0
        while (n < MAX_SRC_FRAMES) {
            // 同帧率预扫：EOF 认样本存在性，负 PTS 前导是合法样本
            if (ex.sampleSize < 0L) break
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
            // 写出值统一经 exportOrientationHint（与 CodecRecorder 同一约定，见 ORIENTATION_MUXER_CCW）：
            // 不直喂裸 hint，避免修复路/录制路旋转约定各自为政
            m.setOrientationHint(exportOrientationHint(hint, ORIENTATION_MUXER_CCW))
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
        // 截止从**任务投递前**起算（Y23 由 open 建立，见 deadlineMs），不再只从主循环起算

        while (!videoDone || !audioAllDone()) {
            checkAbort()

            if (!inputEos && feedDecoderInput(dec)) inputEos = true

            if (!decOutDone) {
                val idx = dec.dequeueOutputBuffer(decInfo, DEQUEUE_TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx >= 0 -> {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        // Y24：平台会把最后一帧与 EOS 合在同一枚输出缓冲。旧实现的
                        // `if (eos) … else if (size > 0)` 先走 EOS 分支，带 payload 的末帧被直接
                        // release，最后一帧永久丢失。顺序必须是「先处理有效载荷，再交接 EOS」；
                        // 零长度 EOS 才是没有 payload 的那种。
                        if (decInfo.size > 0) {
                            handleDecodedFrame(dec, idx, decInfo)
                        } else {
                            runCatching { dec.releaseOutputBuffer(idx, false) }
                        }
                        if (eos) {
                            // 解码 EOS：先把尾巴上的滞留帧/补弧按计划发出（末帧也必须是保留帧），
                            // 再交棒给编码器 EOS
                            if (useGpu) execGpu(flow.onSourceEos()) else execCpu(flow.onSourceEos())
                            decOutDone = true
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
            if (videoDone && !audioAllDone()) drainAudioUpTo(Long.MAX_VALUE)
        }

        // 收尾：把音频尾巴也写进去
        drainAudioUpTo(Long.MAX_VALUE)
    }

    /**
     * 一枚**带 payload** 的解码输出（含与 EOS 合帧的那一枚，见 mainLoop 的顺序）。
     * 去重判据只看源时间轴 PTS（输出 PTS 是均匀目标步长，与源 PTS 抖动解耦）。
     */
    private fun handleDecodedFrame(dec: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
        // 该解码帧的源时间轴 PTS（归一化到首帧），**只用于去重判距**——
        // v2 输出 PTS 取均匀目标帧率步长（第 j 帧 = j/dstFps），
        // 输出时长 = 保留帧数/dstFps ≈ 源时长，与源 PTS 抖动解耦
        if (!decodePtsBaseKnown) {
            decodePtsBase = info.presentationTimeUs
            decodePtsBaseKnown = true
        }
        val ptsUs = (info.presentationTimeUs - decodePtsBase).coerceAtLeast(0L)
        // 去重：源容器里常有空/重复样本（真机实测：1.875s 的片子只有 45 个有效包，
        // 却有 124 个样本），解码器会把它们全吐出来。按"距上一个已采用帧不足半个源帧长"
        // 判重丢弃——被灌水的帧会搅乱抽帧节奏（把该保留的位次占掉）。
        // M6-01：阈值跟有理数真值（它决定"哪些源帧被采用"；整数视图会带偏）
        val minGapUs = if (srcRate.num > 0) 500_000L * srcRate.den / srcRate.num else 0L
        if (srcIndex > 0 && ptsUs - prevPtsUs < minGapUs) {
            runCatching { dec.releaseOutputBuffer(idx, false) }
        } else {
            prevPtsUs = ptsUs
            processSourceFrame(dec, idx)
        }
    }

    /** 取消/截止检查唯一入口：取消立即生效（准备期与主循环同一口径） */
    private fun checkAbort() {
        val reason = arcAbortReason(cancelled(), SystemClock.elapsedRealtime(), deadlineMs)
        if (reason != null) throw ArcFail(reason)
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
                    if (op.fresh) copyCurToAcc() else selectInto(accY, accU, accV, curY, curU, curV)
                }
                is ArcOp.FoldAccIntoPending -> {
                    // P' = select(P, acc)：被抽帧的弧段并进**前**保留帧（a=P、b=acc）
                    selectInto(pendY, pendU, pendV, accY, accU, accV)
                }
                is ArcOp.HoldCurAsPending -> copyCurToPending()
                is ArcOp.MergeCurAsPending -> {
                    if (op.useAcc) {
                        // B' = select(B, acc)：被抽帧的弧段并进**后**保留帧，带弧升格待发
                        // （a=cur、b=acc；就地改 cur 后再整帧拷进 pending）
                        selectInto(curY, curU, curV, accY, accU, accV)
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

    /** `a = select(a, b)`：复用缓冲就地更新，不新建数组（2×2 块整像素选择，差分死区 D=[deadband]） */
    private fun selectInto(
        aY: ByteArray, aU: ByteArray, aV: ByteArray,
        bY: ByteArray, bU: ByteArray, bV: ByteArray
    ) {
        ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, width, height, deadband)
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

    /** 每发出一枚保留帧就 +1 并刷新进度（封口前封顶 0.95，见 [arcRepairProgress]） */
    private fun countOutput(n: Int) {
        outputsDone += n
        onProgress(arcRepairProgress(ArcProgressStage.PROCESS, outputsDone, dstTotal))
    }

    // endregion

    // region 编码器排空与封装

    private fun drainEncoder(finalDrain: Boolean) {
        val enc = vEnc ?: return
        val m = muxer ?: return
        // Y25：视频轨已建但首帧基准未定时，start 推迟到基准确定后的下一次排空（见
        // [startMuxerIfReady]）。数据缓冲出现前一定先解出首帧，故推迟不会放过任何视频样本。
        if (videoTrack >= 0 && !muxStarted) startMuxerIfReady(m)
        val bi = encBufInfo
        // EOS 之后还按 0 超时轮询会变成热自旋，所以只要发了 EOS 就用正常超时等待
        val timeoutUs = if (finalDrain || encEosSent) DEQUEUE_TIMEOUT_US else 0L
        var deadline = 0L
        while (true) {
            val idx = try {
                enc.dequeueOutputBuffer(bi, timeoutUs)
            } catch (e: IllegalStateException) {
                // Y24：排空异常不再只是"置 videoDone 后照常成功"——失败码进结果，物不判纯成功
                Log.e(TAG_ARC, "编码器出队异常：${e.message}")
                encodeFailCode = encodeFailCode ?: "${ArcRepairError.ENGINE}:ENC"
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
                        // 末次排空超时 = 最后一帧可能没吐出来，不能算纯成功（Y24）
                        encodeFailCode = encodeFailCode ?: ArcRepairError.TIMEOUT
                        videoDone = true
                        return
                    }
                }
                idx >= 0 -> {
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val cfg = bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    // Y24：写样本只认「有负载且非配置帧」，**不得把 !eos 写进条件**——平台会把
                    // 末帧与 EOS 合在同一枚输出缓冲，旧条件会把这一枚末帧直接 release：成片比
                    // 抽帧计划少一帧、尾部补弧被削，容器时长与已推到末尾的音轨对不上。
                    // 顺序与解码器侧 mainLoop 同一口径：先写有效载荷，再交接 EOS。
                    if (muxStarted && arcEncSampleWritable(bi.size, cfg)) writeVideo(enc, idx, bi)
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
        // Y25：音频准入与拷贝必须锚同一枚时基（[decodePtsBase]）。首帧基准未定时**不启动**：
        // 宁可晚一轮排空，也不能让预扫退回 0 / 首枚音频样本，而拷贝稍后改用真基准。基准确定后
        // 由 [drainEncoder] 再次调用本函数；数据缓冲出现前一定先解出首帧，因此不会丢视频样本。
        if (!decodePtsBaseKnown) return
        addAudioTracksIfReady(m)
        try {
            m.start()
            muxStarted = true
            Log.i(
                TAG_ARC,
                "muxer 已启动 video=$videoTrack " +
                    audioLanes.joinToString(" ") { "audio(${it.label})=src${it.trackIndex}/mux${it.muxTrack}" }
            )
        } catch (e: IllegalStateException) {
            Log.e(TAG_ARC, "muxer start 失败：${e.message}")
        }
    }

    /**
     * 音频轨**延迟建轨**（Y25）：调用点已保证首帧基准已确定（[decodePtsBaseKnown]，见
     * [startMuxerIfReady]），此处 [decodePtsBase] 就是拷贝真正使用的时基。用与
     * [AudioCopyLane.drainUpTo] 相同的 [arcAudioOutPtsUs] 判据逐 lane 验收，零可写样本的
     * lane 直接不 addTrack（防止零样本轨让 muxer.stop 失败、整份产物被回收）。
     *
     * 基准**可以是负值**（负 PTS 前导素材）：那不是"未知"，不得退回 0——退回 0 会让预扫与
     * 拷贝各锚一个时基（本卡旧缺陷）。
     */
    private fun addAudioTracksIfReady(m: MediaMuxer) {
        if (audioTracksAdded) return
        audioTracksAdded = true
        val base = decodePtsBase
        for (lane in audioLanes) {
            if (lane.muxTrack >= 0) continue
            if (!lane.hasWritableSample(base)) {
                Log.i(
                    TAG_ARC,
                    "音频轨 ${lane.label} 在该时基下无可写样本（shift=${lane.shiftUs}us base=$base），" +
                        "不建轨（防零样本轨崩 muxer）"
                )
                lane.done = true
                lane.release()
                continue
            }
            lane.muxTrack = try {
                m.addTrack(lane.format)
            } catch (e: Exception) {
                // 源音频不是封装器认识的格式（或 csd 缺失）：宁可没有该音轨也不整单失败
                Log.i(TAG_ARC, "音频轨 ${lane.label} 加入失败，该轨改无音轨继续：${e.message}")
                -1
            }
            if (lane.muxTrack >= 0) {
                Log.i(TAG_ARC, "音频轨已加（直拷）label=${lane.label} srcTrack=${lane.trackIndex} muxTrack=${lane.muxTrack}")
            } else {
                lane.done = true
                lane.release()
            }
        }
    }

    /** 视频样本统一到本片基（首样本归零），并按时间轴把音频样本穿插写出去 */
    private fun writeVideo(enc: MediaCodec, idx: Int, bi: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        val buf = enc.getOutputBuffer(idx) ?: return
        if (videoPtsBase < 0L) videoPtsBase = bi.presentationTimeUs
        val norm = (bi.presentationTimeUs - videoPtsBase).coerceAtLeast(0L)
        // 写出前过单调桥（#8）：修正属 B 帧源预期行为，逐样本只记 d 级（默认关），不重蹈逐帧刷屏
        val fix = monotonicPts(vMuxLastPtsUs, norm)
        if (fix.advanced) {
            vPtsFixes++
            if (fix.backUs > vPtsMaxBackUs) vPtsMaxBackUs = fix.backUs
            // B06：首条留明细，其余只计数（终止汇总给计数与最大倒退量）
            if (vPtsFixes == 1) Log.d(TAG_ARC, "视频轨 pts 修正 cur=$norm → ${fix.ptsUs}（back=${fix.backUs}us；后续只计数）")
        }
        vMuxLastPtsUs = fix.ptsUs
        // ⚠ flags 必须跟着样本走：MediaMuxer 靠 BufferInfo.flags 里的 BUFFER_FLAG_KEY_FRAME 标同步帧。
        // 真机实测传 0 会让容器里一个关键帧都没有，产物连流参数都解析不出（ffprobe: width=0/height=0/
        // codec_name=unknown）。CODEC_CONFIG 帧在上游已跳过，这里的 flags 只可能是关键帧位或 0。
        // Y24：与 EOS 合帧的末帧同样要写出，但 EOS 位不是样本属性（muxer 只认同步帧/配置位），
        // 入户前剥掉，避免把"流结束"带进容器。
        vBufInfo.set(
            bi.offset,
            bi.size,
            fix.ptsUs,
            bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
        )
        try {
            m.writeSampleData(videoTrack, buf, vBufInfo)
            // 只认写成功：盘满等失败不计数（成功判据 arcRepairOk 的第三格）
            writtenVideoSamples++
        } catch (e: Exception) {
            // 吞异常是既有口径（逐样本失败不中断修复），但必须留痕：零样本时 run() 靠这个码
            // 把失败归因到 MUX 而不是误报 NO_VIDEO（与 CodecRecorder.writeSample 同语义）
            // B06：首条带异常明细，后续只计数（按帧刷 e 级会淹掉日志），收尾给汇总行
            videoWriteFails++
            if (videoWriteFails == 1) Log.e(TAG_ARC, "写视频样本失败：${e.message}（同类后续只计数，收尾汇总）")
            writeFailCode = writeFailCode ?: "${ArcRepairError.ENGINE}:MUX"
        }
        drainAudioUpTo(norm)
    }

    /** 音频直拷聚合口：全部 lane 各自推进到 limitUs（视频穿插点与收尾统一走这里） */
    private fun drainAudioUpTo(limitUs: Long) {
        for (lane in audioLanes) lane.drainUpTo(limitUs)
    }

    /** 聚合口径：全部音频 lane 都拷完（空集恒 true = 无音频，与单轨时代 audioDone 同语义） */
    private fun audioAllDone(): Boolean {
        return audioLanes.all { it.done }
    }

    /**
     * 单条音频轨的直拷状态机（每条 audio/ 轨一枚：extractor/format/账本/缓冲全在 lane 自带，
     * 多轨互不串扰）。[drainUpTo] 逻辑与单轨时代逐字同口径：PTS 基锚视频首帧、负 norm 丢弃、
     * monotonicPts 独立账本、写失败归因 MUX——只是从 ×1 变 ×N。
     */
    private inner class AudioCopyLane(
        val label: String,
        val ex: MediaExtractor,
        val format: MediaFormat,
        val trackIndex: Int,
        /** 本 lane 的 PTS 平移（µs）：仅内录 lane 可能非 0（播放页缓存的同步偏移） */
        val shiftUs: Long
    ) {
        /** muxer 侧轨号（addTrack 失败 = -1：该 lane 降级 done 不连坐） */
        var muxTrack = -1
        var done = false
        var pending = false
        var pendingPts = 0L
        var pendingSize = 0

        /** 本 lane 的单调兜底账本（#8，与视频轨/其他 lane 各记各的） */
        var muxLastPtsUs = -1L
        var ptsFixes = 0
        var ptsMaxBackUs = 0L

        private val bufInfo = MediaCodec.BufferInfo()
        private val buf: ByteBuffer = ByteBuffer.allocateDirect(1 shl 20)

        /**
         * 平移后是否至少有一枚样本能落进 muxer 时间轴（PTS ≥ 0）。
         *
         * **与 [drainUpTo] 共用 [arcAudioOutPtsUs] 和同一个 [decodePtsBase]**（Y25）：本函数由
         * [addAudioTracksIfReady] 在 muxer.start 前调用，入参就是 `decodePtsBase` 本身（**可为负**——
         * 负 PTS 前导是合法样本，不是"未知"），两侧读的是同一枚基准，不存在"预扫宽、拷贝严"的
         * 余地。旧实现用视频首样本 PTS 做保守基准，但 `decodePtsBase`（首帧显示 PTS）可能更大 ⇒
         * 预扫判"可写"、实际写入 0 枚。EOF 以样本存在性（`sampleSize < 0`）识别，
         * 负 PTS 前导仍是合法样本。查完必须把 extractor 游标还原到轨道起点，drain 仍从 0 读。
         */
        fun hasWritableSample(basePtsUs: Long): Boolean {
            try {
                var guard = 0
                while (guard++ < MAX_AUDIO_PRESCAN_SAMPLES) {
                    if (ex.sampleSize < 0L) return false
                    if (arcAudioOutPtsUs(ex.sampleTime, basePtsUs, shiftUs) >= 0L) return true
                    if (!ex.advance()) return false
                }
                return false
            } finally {
                runCatching { ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
            }
        }

        /** 音频直拷：源已是 AAC，格式（含 csd）已直接 addTrack，样本原样写出去 */
        fun drainUpTo(limitUs: Long) {
            val m = muxer ?: return
            if (muxTrack < 0 || done || !muxStarted) return
            // 时基只认**视频首帧**（decodePtsBase）：两轨 sampleTime 同一条容器时间轴，音轨晚启动
            // 的源按自身首样本归一会把整条音轨往前挪（修复成片音画错位）。这枚基准在 muxer.start 前
            // 已由同一判据验收（见 addAudioTracksIfReady），拷贝不得再自行挑一个（Y25：旧实现
            // 在基准为负时退回首枚音频样本 PTS，准入与拷贝从此错轨）。
            val ptsBase = decodePtsBase
            while (true) {
                if (!pending) {
                    buf.clear()
                    val size = try {
                        ex.readSampleData(buf, 0)
                    } catch (e: Exception) {
                        -1
                    }
                    if (size < 0) {
                        done = true
                        return
                    }
                    pending = true
                    pendingPts = ex.sampleTime
                    pendingSize = size
                }
                // 与建轨预扫同一判据、同一时基（Y25）：视频起点之前或平移后仍为负的样本丢弃，
                // 不挤在 0 点造成非单调/重播；穿插上限按平移后的时刻算。
                val outPts = arcAudioOutPtsUs(pendingPts, ptsBase, shiftUs)
                if (outPts < 0L) {
                    pending = false
                    if (!ex.advance()) {
                        done = true
                        return
                    }
                    continue
                }
                if (outPts > limitUs) return
                buf.position(0)
                buf.limit(pendingSize)
                // 单调桥与视频轨同口径（各轨独立账本）：穿插/封轨判据不动，只改写出的戳
                val fix = monotonicPts(muxLastPtsUs, outPts)
                if (fix.advanced) {
                    ptsFixes++
                    if (fix.backUs > ptsMaxBackUs) ptsMaxBackUs = fix.backUs
                    // 与视频轨同口径（B06）：首条留明细，其余只计数
                    if (ptsFixes == 1) {
                        Log.d(TAG_ARC, "音频轨${label} pts 修正 cur=$outPts → ${fix.ptsUs}（back=${fix.backUs}us；后续只计数）")
                    }
                }
                muxLastPtsUs = fix.ptsUs
                bufInfo.set(0, pendingSize, fix.ptsUs, 0)
                try {
                    m.writeSampleData(muxTrack, buf, bufInfo)
                } catch (e: Exception) {
                    // 与视频写失败同口径：吞异常不中断，但留 MUX 失败码（音轨整废时归因不误报）
                    // B06：首条带异常明细，后续只计数（lane 多、样本密，按帧刷 e 级会淹掉日志）
                    audioWriteFails++
                    if (audioWriteFails == 1) {
                        Log.e(TAG_ARC, "写音频样本失败（${label}）：${e.message}（同类后续只计数，收尾汇总）")
                    }
                    writeFailCode = writeFailCode ?: "${ArcRepairError.ENGINE}:MUX"
                }
                pending = false
                if (!ex.advance()) {
                    done = true
                    return
                }
            }
        }

        /** finish/异常路径释放（runCatching 兜底，可重复调） */
        fun release() {
            runCatching { ex.release() }
        }
    }

    // endregion

    // region 收尾

    private fun finish(ok: Boolean, code: String?): ArcRepairResult {
        val gl = arcGl.also { arcGl = null }
        val decoder = vDec.also { vDec = null }
        val encoder = vEnc.also { vEnc = null }
        val input = encSurface.also { encSurface = null }
        val stopDecoder = { releaseCodec(decoder) }
        val releaseEncoder = {
            releaseCodec(encoder)
            runCatching { input?.release() }
            Unit
        }
        if (gl != null) {
            // GL 管线可能仍在 native 命令里。把 codec 与输入面一并交给它，超时也由它收完。
            gl.release(stopDecoder, releaseEncoder)
        } else {
            stopDecoder()
            releaseEncoder()
        }

        val m = muxer
        muxer = null
        // stop 是写 moov 的收尾：正常返回才代表容器封口成功。旧实现把异常吞成日志后仍按
        // ok 提交，坏容器（尤其零样本音轨导致 stop 抛）会被当成功产物写进相册。
        val muxStopOk = if (m != null && muxStarted) {
            runCatching { m.stop() }
                .onFailure { Log.e(TAG_ARC, "muxer stop 失败（容器未封口，产物将回收）：${it.message}") }
                .isSuccess
        } else {
            // 从未 start 的 muxer 没有 moov 可写，stop() 必抛——直接 release，不把它当收尾失败刷日志
            false
        }
        if (m != null) runCatching { m.release() }

        val t = target
        target = null
        t?.close()
        runCatching { vEx?.release() }
        vEx = null
        for (lane in audioLanes) lane.release()
        audioLanes = emptyList()

        val st = store
        val sk = sink
        sink = null
        if (st == null || sk == null) {
            return ArcRepairResult(false, null, null, 0L, code ?: ArcRepairError.NO_OUTPUT, note)
        }
        // 保留判据必须同时含「样本写成功」与「muxer 封口成功」：只过前者会把无 moov 的坏容器
        // commit 进相册（零样本音轨/盘满时 stop 会抛）。失败时 keep=false 由 seal 回收 pending，
        // 源片不在这条路上，不受影响。
        // Y23：封口/发布前最后一次验取消——准备期/音频尾拷/两次发布之间都要能停。取消发生在
        // stop 之后也按取消处理（不把用户已撤销的产物 commit 进相册）。
        val cancelledNow = cancelled()
        val keep = arcRepairKeep(ok, muxStopOk) && !cancelledNow
        val failCode = when {
            keep -> null
            cancelledNow -> ArcRepairError.CANCELLED
            ok && !muxStopOk -> "${ArcRepairError.ENGINE}:MUX_CLOSE"
            else -> code ?: ArcRepairError.ENGINE
        }
        // 封口前进度封顶 0.98（不到 100%）：stop/清 IS_PENDING 还没做完
        onProgress(arcRepairProgress(ArcProgressStage.FINALIZE, outputsDone, dstTotal))
        val seg = st.seal(sk, 0, 0L, if (keep) durationMs else 0L, keep = keep)
        return when (arcPublishOutcome(keep, seg.committed)) {
            ArcPublishOutcome.PUBLISHED -> {
                // 发布成功后才由任务所有者刷新索引：媒体库 IS_PENDING=0 门现在能看见这一行。
                // 不在 pending 分支刷新（那一行仍被 pending 门挡住，刷了也只会多做一次全量查询）。
                MediaRepo.get(ctx).invalidate()
                ArcRepairResult(true, seg.uri, seg.path, seg.bytes, null, note)
            }
            ArcPublishOutcome.PENDING_PUBLISH -> {
                // 成片已封口且字节在盘上，只是两次清 IS_PENDING 都失败。必须报"待发布"而非成功：
                // 媒体库/系统相册此刻都看不到它，用户继续以为修复完成就是骗他。
                Log.e(
                    TAG_ARC,
                    "修复产物已封口但两轮发布均失败，保留 pending 行待冷启救援：" +
                        "uri=${seg.uri} bytes=${seg.bytes} path=${seg.path}"
                )
                ArcRepairResult(
                    ok = false,
                    uri = seg.uri,
                    path = seg.path,
                    bytes = seg.bytes,
                    error = ArcRepairError.PUBLISH_PENDING,
                    note = note,
                    pendingPublish = true
                )
            }
            ArcPublishOutcome.DISCARDED -> {
                ArcRepairResult(false, null, null, 0L, failCode ?: ArcRepairError.ENGINE, note)
            }
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

/**
 * 读外部视频的"显示所需旋转角"（`METADATA_KEY_VIDEO_ROTATION`，相册/播放器都按它转的权威口径）；
 * 读不到返回 **-1**（调用方据此降级单源）。必须在后台线程调用——本函数只在 ArcRepairSession.open()
 * 里被调，而 open() 跑在 ArcRepairRunner 自己的 HandlerThread 上，故不在主线程做这枚阻塞 IO。
 */
private fun readRetrieverRotation(ctx: Context, uri: Uri): Int {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: -1
    } catch (e: Exception) {
        Log.i(TAG_ARC, "读 retriever 旋转角失败，降级单源：${e.message}")
        -1
    } finally {
        runCatching { r.release() }
    }
}
