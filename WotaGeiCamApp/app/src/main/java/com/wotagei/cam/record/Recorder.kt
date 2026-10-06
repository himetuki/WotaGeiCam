package com.wotagei.cam.record

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.wotagei.cam.core.WotaTiers

/** 全模块统一日志锚点（07 文件：`WotaRecord` 覆盖 prepare/start/stop/分段/失败码） */
const val TAG = "WotaRecord"

/**
 * 录制输出的失败/终止原因码。面向用户的中文文案一律在 `res/values/strings.xml`，
 * 这里只给机器可读码，UI 侧按码映射文案（00 文件 §7：代码里不写中文字面量）。
 */
object RecordError {
    /** 存储余量不足，附 `:可用MB` */
    const val LOW_STORAGE = "LOW_STORAGE"

    /** 输出目标打不开（pending uri 插入失败 / FD 为空） */
    const val NO_OUTPUT = "NO_OUTPUT"

    const val BAD_STATE = "BAD_STATE"
    const val PREPARE_FAILED = "PREPARE_FAILED"
    const val START_FAILED = "START_FAILED"

    /** 引擎运行期报错，附 `:what:extra` */
    const val ENGINE_ERROR = "ENGINE_ERROR"

    /** 自研管线没等到视频轨（轨对齐失败） */
    const val NO_VIDEO_TRACK = "NO_VIDEO_TRACK"

    /** 录制时长 < [RecordProfile.MIN_KEEP_MS]，按废片删除 */
    const val TOO_SHORT = "TOO_SHORT"

    const val STOP_FAILED = "STOP_FAILED"

    /** 未 start 就 release：pending 记录直接回收 */
    const val RELEASED = "RELEASED"

    /** 起录自检窗口内三格未立齐/引擎已报错，重录次数触顶：明确失败（文案见 strings.xml） */
    const val SELF_CHECK_FAILED = "SELF_CHECK_FAILED"
}

/**
 * 引擎的起录健康读数（[Recorder.health]）。**两个独立通道**，各自服务一路引擎：
 * - 三格（[videoTrackAdded]／[muxStarted]／[firstVideoSample]）+ [milestonesObservable]：
 *   逐缓冲观测，只有 [CodecRecorder] 具备（见 [healthVerdict]）；
 * - [outputBytes]：输出文件产出活性，[MrRecorder] 用（见 [streamHealthVerdict]）。
 *
 * [milestonesObservable] 描述的是**三格里程碑是否可见**（Codec 路 true / MR 路 false），
 * 与 [outputBytes] 不是一回事：它 false 只说明"别拿恒 false 的三格去套超窗"，不代表 [outputBytes]
 * 也无意义——两通道互不蕴含。UI 侧按它分流：true 走 [healthVerdict]，false 走 [streamHealthVerdict]。
 */
data class RecorderHealth(
    val videoTrackAdded: Boolean = false,
    val muxStarted: Boolean = false,
    val firstVideoSample: Boolean = false,
    val errorCode: String? = null,
    /** start() 至今的墙钟经过 ms（当前段）；未 start 或引擎不报时为 0 */
    val elapsedMs: Long = 0L,
    /**
     * 当前段输出文件已落盘字节数（[MrRecorder] 路经 `Os.fstat(fd).st_size`）。
     * **-1L = 不可观测**（拿不到 fd/真实路径、或 fstat/读路径抛错）——判定侧必须把它当"没信号"
     * 而非"0 字节"（见 [streamHealthVerdict]，误把量不到当 0 会误杀正常录制）。默认 -1 = 未覆写。
     */
    val outputBytes: Long = -1L,
    /** 三格里程碑是否可见（Codec 路 true / MR 路 false）；与 [outputBytes] 是两个独立通道 */
    val milestonesObservable: Boolean = false
)

/**
 * 输出目标（``）：优先 MediaStore pending content uri；私有目录/调试用真实文件。
 * 引擎不关心入库细节，只按 sink 分流 FileDescriptor 与 path。
 *
 * 注意嵌套类名 `File` 会遮蔽 `java.io.File`，属性类型必须写全限定名。
 */
sealed interface OutputSink {
    data class Pending(val uri: Uri) : OutputSink
    data class File(val f: java.io.File) : OutputSink
}

/** 分段后的单个成品文件（04 文件 §5.3：同一段录用 partIndex 关联，Room 侧记 series_id） */
data class VideoSegment(
    val uri: Uri?,
    val path: String?,
    val partIndex: Int,
    val bytes: Long,
    val startMs: Long,
    val durationMs: Long,
    /** false = 文件已写完但 `IS_PENDING` 没清掉：字节在盘上，相册却可能看不到 */
    val committed: Boolean = true
)

/**
 * 一次停止的结果。[uri]/[path]/[bytes]/[durationMs] 描述首段（UI「回看缩略图」直接用它），
 * 全部分段见 [parts]，[seriesId] 供媒体库把分段归到同一录制片段。
 */
data class RecordResult(
    val uri: Uri?,
    val path: String?,
    val bytes: Long,
    val durationMs: Long,
    val error: String?,
    val seriesId: String = "",
    val parts: List<VideoSegment> = emptyList(),
    /** 有几段没能在 MediaStore 登记成可见；>0 时 UI 要在「已保存」里额外提示 */
    val uncommitted: Int = 0,
    /**
     * 逐段已写**视频**样本数（索引=段序号，与 [parts] 的 partIndex 对应）。纯内存计数的排查
     * 增量信息：转换录制 sidecar 的段清单（[ArcDropLog] 的 segments）凭它把每段对上跨段全局
     * 输出位次——位次只数视频帧，音频样本不入账。MediaRecorder 路线不参与位次账
     * （转换会话恒走 Codec 引擎），恒空表。
     */
    val segVideoSamples: List<Int> = emptyList()
) {
    /** 有可用产物且无错误 */
    val ok: Boolean get() = error == null && (uri != null || path != null)

    companion object {
        fun fail(code: String, durationMs: Long = 0L) =
            RecordResult(null, null, 0L, durationMs, code)
    }
}

/**
 * 引擎内部状态（04 文件 §6）。对外状态机由 `camera/Camera2Engine` 的 `RecordStatus` 承载，
 * 本包只用它守住 prepare/start/stop 的调用顺序。
 */
enum class EngineState { IDLE, PREPARE, START, STOPPING, ERROR }

/**
 * 录制引擎接口（04 文件 §2 契约）。两个实现：
 * [MrRecorder]（MediaRecorder，fps ≤ 60 默认）与 [CodecRecorder]（MediaCodec+MediaMuxer+AudioRecord 自采，高速帧率）。
 *
 * 线程约定：[prepare]/[start]/[stop] 在同一台带有 Looper 的工作线程调用；
 * [MrRecorder] 的分段在 MediaRecorder 信息回调里同步执行，会占用该线程约 500ms。
 */
interface Recorder {

    /**
     * 编码器输入面（`android.view.Surface`）；prepare 成功后有效，未 prepare 时为 null。
     * - DIRECT 模式：Camera2 把录像面直接挂进 session；
     * - GPU 模式：`GlRenderEngine.outputSurface` 接同一个面（零拷贝，录出=所见）。
     */
    val surface: Any?

    /**
     * GPU 路换段重挂钩（默认无操作）：分段轮转若换了 per-codec 输入面（[CodecRecorder] GPU 路
     * 专用——per-codec 面不能跨编码器实例复用），泵线程在新面建好、`codec.start()` 之前
     * **同步**回调；接线方（UI 层）做「下发 GL 重挂 + 等挂好」。面跨段不变的引擎
     * （[MrRecorder]、DIRECT 路的 persistent 面）永不回调，接线对它们是无害的空挂。
     */
    var onInputSurfaceRecreated: ((Any) -> Unit)?
        get() = null
        set(_) {}

    /** @return false = 失败（内部已回 IDLE 并记日志），调用方不进 START */
    fun prepare(p: RecordProfile, sink: OutputSink): Boolean

    fun start()

    /** 停引擎 + 收尾入库（成功清 IS_PENDING，失败/废片删记录），任何状态都可调、幂等 */
    fun stop(): RecordResult

    /**
     * 显式声明「放弃当前段」：**下一次停录必须丢弃当前段**（keep=false、不 commit、不进 parts、
     * 记录连同 pending 一并删除），无论错误码是否为空、时长是否够。
     *
     * 只给自检判废这类"这段本就没有可用内容"的场景调用（[Recorder] 契约里弃段意图不许靠停录的
     * keep 语义反推，见 `segmentKeepDecision`）。默认无操作——引擎不实现该通路时行为与旧版一致。
     * 新会话/新段起始必须复位该意图，否则一次判废会把之后每一段都拖成废段。
     */
    fun abandonCurrentSegment() {}

    fun release()

    /** 累计录制时长（跨分段），UI 以 200ms 轮询读取（04 文件 §6） */
    val elapsedMs: Long

    /** 0..32767 量程振幅：MR 走 `getMaxAmplitude()`，自采走 PCM RMS；dB 换算用 [AudioProbe.dbOf] */
    fun amplitude(): Int

    /**
     * 起录自检读数（录制中轮询）。默认实现返回「未就绪」（`milestonesObservable=false` →
     * UI 侧走 [streamHealthVerdict]，但默认 [RecorderHealth.outputBytes] 也是 -1 = 不可观测，
     * 即无信号时不误判）。两个引擎各自覆写：能逐格观测的 [CodecRecorder] 填三格 + 写入字节，
     * [MrRecorder] 填 `Os.fstat` 读到的 [RecorderHealth.outputBytes]。
     */
    fun health(): RecorderHealth = RecorderHealth()
}

/**
 * 引擎选型入口（04 文件 §3，按「预览类型/帧率档」分流而非按录制类型）：
 * fps ≤ 60 → [MrRecorder]（DIRECT 与 GPU 都一样，GPU 时 GL 画进编码器面）；
 * fps > 60（120/240 高速会话）→ [CodecRecorder]。
 * **例外**：24/25fps 录制期转换（强制档无原生精确档的抽帧/补弧）恒走 [CodecRecorder]——
 * MediaRecorder 的面输入来帧即编、没有拒帧能力，抽帧只在 GL 编码支路做得了（用户 2026-10-03 定版）；
 * 设备内录第二音轨（captureAudio）同理恒走 [CodecRecorder]——MediaRecorder 无多音轨能力
 * （内录体系批 3，与 arcConvert 同款强制先例）。
 */
object Recorders {

    /** MediaRecorder 路径的最高帧率档，超过即必须自研管线 */
    const val MR_MAX_FPS = 60

    fun useCodecEngine(p: RecordProfile): Boolean {
        return p.fps > MR_MAX_FPS || p.arcConvert != null || p.captureAudio
    }

    /** 供 UI 灰显高速档时用：本机高速码率建议值 */
    fun recommendedTier(p: RecordProfile): Int =
        BitratePolicy.recommendTier(p.bitrate, p.width, p.height, p.fps, p.hevc)

    fun create(ctx: Context, p: RecordProfile, store: VideoStore = VideoStore(ctx)): Recorder {
        if (p.fps !in WotaTiers.FPS) Log.i(TAG, "fps=${p.fps} not in product tiers (device-driven value), continue")
        return if (useCodecEngine(p)) CodecRecorder(ctx, store) else MrRecorder(ctx, store)
    }
}

/**
 * 跨分段累计的录制时钟：`elapsedMs = 已封段累计 + 当前段经过时间`。
 * 用 `elapsedRealtime()` 而非 `System.currentTimeMillis()`，避免用户改系统时间导致计时跳变。
 */
internal class ElapsedClock {
    private var accumulatedMs = 0L
    private var segmentStartMs = 0L
    private var segmentRunning = false

    fun startSegment() {
        segmentStartMs = SystemClock.elapsedRealtime()
        segmentRunning = true
    }

    /** 封当前段，返回该段时长 */
    fun closeSegment(): Long {
        if (!segmentRunning) return 0L
        val d = SystemClock.elapsedRealtime() - segmentStartMs
        accumulatedMs += d
        segmentRunning = false
        return d
    }

    fun segmentElapsedMs(): Long =
        if (segmentRunning) SystemClock.elapsedRealtime() - segmentStartMs else 0L

    fun elapsedMs(): Long = accumulatedMs + segmentElapsedMs()

    fun reset() {
        accumulatedMs = 0L
        segmentStartMs = 0L
        segmentRunning = false
    }
}
