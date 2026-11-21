package com.wotagei.cam.player

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class ExportKind { CLIP, TRACK }

/** 只显示本次提交的区间和音轨，回页后新片的设置不会被误认为在途任务的设置。 */
internal sealed interface ExportDetails {
    val kind: ExportKind

    data class Clip(val aMs: Long, val bMs: Long, val durationMs: Long) : ExportDetails {
        override val kind get() = ExportKind.CLIP
    }

    data class Track(
        val selected: SelectedTrack,
        val offsetMs: Long?,
        val offsetSource: TrackSync.Source?
    ) : ExportDetails {
        override val kind get() = ExportKind.TRACK
    }
}

internal data class ExportRequest(
    val taskId: Long,
    val sourceUri: String,
    val sourceName: String,
    val details: ExportDetails
) {
    val kind get() = details.kind
}

internal data class ExportTask(
    val request: ExportRequest,
    internal val job: Job,
    val progress: Float = 0f,
    val cancelling: Boolean = false
)

internal data class ExportResultItem(val request: ExportRequest, val payload: ClipResult)

internal data class ExportUiState(
    val clip: ExportTask? = null,
    val track: ExportTask? = null,
    val results: List<ExportResultItem> = emptyList()
) {
    operator fun get(kind: ExportKind): ExportTask? = when (kind) {
        ExportKind.CLIP -> clip
        ExportKind.TRACK -> track
    }

    fun withTask(kind: ExportKind, task: ExportTask?): ExportUiState = when (kind) {
        ExportKind.CLIP -> copy(clip = task)
        ExportKind.TRACK -> copy(track = task)
    }

    /** 同一页回程只自动打开一个进度窗，并且只指向正在观看的源片。 */
    fun latestFor(sourceUri: String): ExportRequest? {
        val clipRequest = clip?.request?.takeIf { it.sourceUri == sourceUri }
        val trackRequest = track?.request?.takeIf { it.sourceUri == sourceUri }
        return when {
            clipRequest == null -> trackRequest
            trackRequest == null || clipRequest.taskId > trackRequest.taskId -> clipRequest
            else -> trackRequest
        }
    }
}

/**
 * 两类导出可并行。起跑、归还闸门、结果入队和跨线程进度校验共用一份原子快照。
 * 结果留在窗口宿主中，直到播放页按 taskId 确认。离页、取消或起跑新任务都不会清掉未读结果。
 *
 * Y32 迟到终态回填：owner 侧的提交（清 IS_PENDING）在 caller 已取消/预算到点离开后仍可能落地
 * （成功 → 相册里多了成片；COMMIT 全败 → pending 行保留待救援）。这些真话经 [start] 的
 * [onLateOutcome] 按**任务身份**回填：同一请求先到的结果不改写（终态单调），迟到值 upsert 进
 * results（同请求已有条目 = 等待方先报了 BUSY/取消，迟到真话顶掉假话）。
 */
internal class ExportTasks(private val scope: CoroutineScope) {
    private val nextId = AtomicLong()
    private val mutableState = MutableStateFlow(ExportUiState())
    val state = mutableState.asStateFlow()

    fun start(
        details: ExportDetails,
        sourceUri: String,
        sourceName: String,
        onLateOutcome: (ClipResult) -> Unit = {},
        run: suspend (report: (Float) -> Unit, late: (ClipResult) -> Unit) -> ClipResult
    ): Boolean {
        val kind = details.kind
        if (state.value[kind] != null) return false
        val request = ExportRequest(nextId.incrementAndGet(), sourceUri, sourceName, details)
        var result: ClipResult? = null
        // 迟到回填去重簿记：owner 侧回填先于 job 完成时，completion 不再重复入队。
        val lateSettled = AtomicReference<ClipResult?>()
        // Main.immediate 上 BUSY 等结果可能同步返回。必须先完成登记再执行任务。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            result = run({ publishProgress(request, it) }) { late ->
                // 首次回填才生效：终态单调，owner 侧重复通知不改写已入账的真话。
                if (lateSettled.compareAndSet(null, late)) {
                    upsertResult(request, late)
                    onLateOutcome(late)
                }
            }
        }
        val task = ExportTask(request, job)
        while (true) {
            val previous = mutableState.value
            if (previous[kind] != null) {
                job.cancel()
                return false
            }
            if (mutableState.compareAndSet(previous, previous.withTask(kind, task))) break
        }
        // 也覆盖「登记后宿主已销毁，协程体没进过」的情形。
        job.invokeOnCompletion {
            mutableState.update { previous ->
                if (previous[kind]?.request != request) previous
                else {
                    val released = previous.withTask(kind, null)
                    // 迟到真话已按任务身份入账的，不再把等待方先报的假话（BUSY/取消）追加一遍。
                    val value = if (lateSettled.get() != null) null else result
                    value?.let { released.copy(results = previous.results + ExportResultItem(request, it)) }
                        ?: released
                }
            }
        }
        return job.start()
    }

    private fun publishProgress(request: ExportRequest, progress: Float) {
        if (!progress.isFinite()) return
        val bounded = progress.coerceIn(0f, 1f)
        mutableState.update { previous ->
            val task = previous[request.kind]
            if (task == null || task.request != request || task.cancelling || bounded <= task.progress) previous
            else previous.withTask(request.kind, task.copy(progress = bounded))
        }
    }

    /**
     * 迟到真话按任务身份入账：同请求已有条目（等待方先报了 BUSY/取消）就顶掉它，没有就 append。
     * 主线程之外也可能调（owner worker 线程），StateFlow.update 的 CAS 循环保证并发安全。
     */
    private fun upsertResult(request: ExportRequest, payload: ClipResult) {
        mutableState.update { previous ->
            val existing = previous.results.firstOrNull { it.request == request }
            if (existing == null) previous.copy(results = previous.results + ExportResultItem(request, payload))
            else previous.copy(results = previous.results.map {
                if (it.request == request) it.copy(payload = payload) else it
            })
        }
    }

    fun cancel(request: ExportRequest) {
        while (true) {
            val previous = mutableState.value
            val task = previous[request.kind] ?: return
            if (task.request != request || task.cancelling) return
            if (mutableState.compareAndSet(previous, previous.withTask(request.kind, task.copy(cancelling = true)))) {
                // 已领取的 Provider 提交可完成。取消后不再显示此任务迟到的进度，也不撤销已发布结果。
                task.job.cancel()
                return
            }
        }
    }

    fun acknowledge(taskId: Long) {
        mutableState.update { previous ->
            previous.copy(results = previous.results.filterNot { it.request.taskId == taskId })
        }
    }
}
