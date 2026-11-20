package com.wotagei.cam.record

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一次补弧任务的**身份**：`(来源 URI, 代数)`。
 *
 * 代数在每次真正起跑时递增，且只属于这一个宿主（进程内单槽）。页面拿到的每份状态都带这份身份：
 * 显示按来源 URI 过滤、取消与确认都按代数匹配——迟到的进度、上一轮的取消、另一片的结果
 * 都不会落到当前窗口上。旧实现把 runner 挂在页面 `remember` 上，身份其实是"哪一个组合实例"，
 * 退页即失效，重进页面又会起一条新 runner 与在跑的任务并行。
 */
internal data class ArcRepairRequest(
    val taskId: Long,
    val sourceUri: String,
    val sourceName: String,
    val useGpu: Boolean,
    val dstFps: Int
)

/**
 * 在跑的一轮。[token] 是执行器所有权门发的 token（取消只认它，晚取消碰不到下一轮）；
 * [progress] 按阶段呈现（封口前封顶 0.98，见 [arcRepairProgress]），[cancelling] 期间不再前进。
 */
internal data class ArcRepairRun(
    val request: ArcRepairRequest,
    val token: Long = 0L,
    val progress: Float = 0f,
    val cancelling: Boolean = false
)

/** 一条未确认的终态。[request] 带来源 URI 与代数，页面据此决定文案与"是不是本片"。 */
internal data class ArcRepairResultItem(
    val request: ArcRepairRequest,
    val payload: ArcRepairResult
)

internal data class ArcRepairUiState(
    val running: ArcRepairRun? = null,
    /** 未确认终态（先到先显示）。结果不会因为起跑下一轮或退页而消失，只由 taskId 确认移除。 */
    val results: List<ArcRepairResultItem> = emptyList()
) {
    /** 本片在跑的那一轮（别的片在跑时返回 null：入口按忙处理，但不把进度画到本片上） */
    fun runningFor(sourceUri: String): ArcRepairRun? = running?.takeIf { it.request.sourceUri == sourceUri }

    fun latestResult(): ArcRepairResultItem? = results.firstOrNull()
}

/**
 * 补弧任务的**宿主侧账本**（纯 Kotlin，JVM 全表）：单一执行槽 + 结果队列 + 按代数的取消/确认。
 *
 * 分工：执行器（[ArcRepairRunner]）只负责跑与交付终态；本类负责"谁有权起跑、结果属于谁、
 * 什么时候清"。线程模型：`start/cancel/acknowledge` 在主线程，`progress/finish` 在执行器线程，
 * 全部经 [MutableStateFlow] 的 CAS 更新，不做跨线程字段直写。
 *
 * 【为什么起跑前先登记】执行器的排队期取消路径可能在 [start] 返回 token 之前就交付终态
 * （消息已被取消并在同一线程上收尾）。先登记再起跑保证"终态到达时槽位已经认得出它"，
 * 结果照常入队；token 回填发现槽位已清就跳过，不会把旧轮写成新轮。
 */
internal class ArcRepairTasks(
    /** 取消落到执行器上的唯一出口（token 由起跑时回填；0 = 尚未回填，主线程上不会发生） */
    private val onCancel: (Long) -> Unit = {}
) {
    private val nextId = AtomicLong()
    private val mutableState = MutableStateFlow(ArcRepairUiState())
    val state: StateFlow<ArcRepairUiState> = mutableState.asStateFlow()

    /**
     * 领取槽位并起跑。
     * @param launch 真正起跑；返回执行器 token，null = 没领到执行器所有权（回滚槽位并按忙返回 false）
     * @return true = 本轮已起跑；false = 已有任务/执行器拒收（调用方按忙提示，不入队、不排队）
     */
    fun start(
        sourceUri: String,
        sourceName: String,
        useGpu: Boolean,
        dstFps: Int,
        launch: (ArcRepairRequest) -> Long?
    ): Boolean {
        val request = ArcRepairRequest(nextId.incrementAndGet(), sourceUri, sourceName, useGpu, dstFps)
        while (true) {
            val previous = mutableState.value
            if (previous.running != null) return false
            val registered = previous.copy(
                running = ArcRepairRun(
                    request = request,
                    progress = arcRepairProgress(ArcProgressStage.PREPARE, 0, 0)
                )
            )
            if (mutableState.compareAndSet(previous, registered)) break
        }
        val token = launch(request)
        if (token == null) {
            // 执行器拒收（它的所有权门被占/线程已退）：槽位回滚，页面看到的是"忙"而不是幽灵 RUNNING
            mutableState.update { previous ->
                if (previous.running?.request == request) previous.copy(running = null) else previous
            }
            return false
        }
        mutableState.update { previous ->
            val run = previous.running
            if (run?.request == request) previous.copy(running = run.copy(token = token)) else previous
        }
        return true
    }

    /** 进度只认当前轮，且在取消之后不再前进（取消中的迟到进度不得把百分比推回去） */
    fun progress(request: ArcRepairRequest, value: Float) {
        if (!value.isFinite()) return
        val bounded = value.coerceIn(0f, 1f)
        mutableState.update { previous ->
            val run = previous.running ?: return@update previous
            if (run.request != request || run.cancelling || bounded <= run.progress) previous
            else previous.copy(running = run.copy(progress = bounded))
        }
    }

    /**
     * 终态到达（执行器保证每轮恰好一次）。无论起跑是否已被取消都**照实入队**：
     * 取消与完成赛跑时用户拿到的仍是真结果（"已取消"不能盖掉一份已经发布成功的成片）。
     */
    fun finish(request: ArcRepairRequest, payload: ArcRepairResult) {
        mutableState.update { previous ->
            if (previous.results.any { it.request.taskId == request.taskId }) return@update previous
            val cleared = if (previous.running?.request == request) previous.copy(running = null) else previous
            cleared.copy(results = cleared.results + ArcRepairResultItem(request, payload))
        }
    }

    /** 按 (来源 URI + 代数) 取消：只有槽位里正是这一轮才允许，旧代数的迟到取消一律无效 */
    fun cancel(request: ArcRepairRequest) {
        while (true) {
            val previous = mutableState.value
            val run = previous.running ?: return
            if (run.request != request || run.cancelling) return
            if (mutableState.compareAndSet(previous, previous.copy(running = run.copy(cancelling = true)))) {
                if (run.token != 0L) onCancel(run.token)
                return
            }
        }
    }

    /** 用户已看到这条终态：按 taskId 确认移除，别的代数的结果原样保留 */
    fun acknowledge(taskId: Long) {
        mutableState.update { previous ->
            previous.copy(results = previous.results.filterNot { it.request.taskId == taskId })
        }
    }
}