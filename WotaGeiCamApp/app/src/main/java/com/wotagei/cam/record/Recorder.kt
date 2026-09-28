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
}

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
    val uncommitted: Int = 0
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

    /** @return false = 失败（内部已回 IDLE 并记日志），调用方不进 START */
    fun prepare(p: RecordProfile, sink: OutputSink): Boolean

    fun start()

    /** 停引擎 + 收尾入库（成功清 IS_PENDING，失败/废片删记录），任何状态都可调、幂等 */
    fun stop(): RecordResult

    fun release()

    /** 累计录制时长（跨分段），UI 以 200ms 轮询读取（04 文件 §6） */
    val elapsedMs: Long

    /** 0..32767 量程振幅：MR 走 `getMaxAmplitude()`，自采走 PCM RMS；dB 换算用 [AudioProbe.dbOf] */
    fun amplitude(): Int
}

/**
 * 引擎选型入口（04 文件 §3，按「预览类型/帧率档」分流而非按录制类型）：
 * fps ≤ 60 → [MrRecorder]（DIRECT 与 GPU 都一样，GPU 时 GL 画进编码器面）；
 * fps > 60（120/240 高速会话）→ [CodecRecorder]。
 */
object Recorders {

    /** MediaRecorder 路径的最高帧率档，超过即必须自研管线 */
    const val MR_MAX_FPS = 60

    fun useCodecEngine(p: RecordProfile): Boolean = p.fps > MR_MAX_FPS

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
