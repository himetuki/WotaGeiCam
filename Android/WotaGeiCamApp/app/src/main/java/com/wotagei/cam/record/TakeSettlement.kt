package com.wotagei.cam.record

import android.content.ContentUris
import android.net.Uri
import com.wotagei.cam.media.SeriesPartInput
import com.wotagei.cam.media.SeriesStore
import com.wotagei.cam.media.WotaDb
import com.wotagei.cam.player.ComparePractice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 录制收尾的**会话级结算所有者**（Y21 / R07 同族收口）。
 *
 * # 为什么不能继续住在录制页的 `LaunchedEffect`
 * 页面组合作用域在用户按 Home / 返回 / 切页时会被取消。旧实现把「系列归组落库」和
 * 「练习交付」都内联在 `runner.result.collect` 里：取消恰好落在写库途中，`NonCancellable`
 * 只救得了已经在跑的那一段，救不了**还没开始**的第二段；练习交付更是完全没有保护——
 * 退页收尾的成片会漏归组、漏交付（Y21 的两种形态）。
 *
 * 本所有者持有一条**进程级**作用域（与页面生死无关），页面只在自己的协程里 `await` 结论：
 * - 页面还活着 ⇒ 拿到 `practiceDelivered` 决定是否弹栈回对比页；
 * - 页面已经走了 ⇒ 等待被取消，但落库与交付在所有者作用域里照常完成；
 * - 对比页回程读的是 [ComparePractice.pendingRightId]，不依赖录制页还活着。
 *
 * # 数据红线
 * 只有 `committed == true` 且带 `content://` uri 的段才参与归组与练习交付：
 * 发布没成功的段在媒体库里不可见，收进 `series_part` 就是永久孤儿行；把它当练习成片交付
 * 则会让对比页打开一个相册里看不到的文件（Y21「练习回程核对发布状态」）。`committed=false`
 * 的迟到发布由 R06 的对账路径在真正 commit 之后以**新的结算键**再结算一次。
 */
internal data class SettledPart(
    val partIndex: Int,
    val uriStr: String?,
    val durationMs: Long,
    val committed: Boolean
)

/** 本次结算的段视图：只留已发布、有 uri、段序唯一的段（迟到未发布段不参与） */
internal fun committedSettledParts(parts: List<SettledPart>): List<SettledPart> =
    parts.asSequence()
        .filter { it.committed && !it.uriStr.isNullOrBlank() }
        .distinctBy { it.partIndex }
        .sortedBy { it.partIndex }
        .toList()

/** 练习交付判定（纯函数）：未武装不碰桥；武装但本次不是干净成功 / 无已发布段 ⇒ 清桥态 */
internal sealed interface PracticeDecision {
    data class Deliver(val part: SettledPart) : PracticeDecision
    object Clear : PracticeDecision
    object Leave : PracticeDecision
}

internal fun practiceDecision(
    armed: Boolean,
    resultOk: Boolean,
    committed: List<SettledPart>
): PracticeDecision = when {
    !armed -> PracticeDecision.Leave
    !resultOk -> PracticeDecision.Clear
    committed.isEmpty() -> PracticeDecision.Clear
    // 交付录制顺序上的首段：不依赖调用方是否已排序，按 partIndex 取最小
    else -> PracticeDecision.Deliver(committed.minBy { it.partIndex })
}

/** 一次结算的结果（页面只需 practiceDelivered；其余进日志/诊断） */
internal data class TakeSettlementOutcome(
    val committedParts: Int,
    val seriesRows: Int,
    val seriesSettled: Boolean,
    val practiceDelivered: Boolean,
    val practiceCleared: Boolean
)

/** 结算后端（生产 = Room + ComparePractice；测试注入假实现，不碰 Android 类型） */
internal interface TakeSettlementBackend {
    /** @return 实际写入的成员行数；0 = 未达门槛/无归属。抛异常由所有者兜住并如实上报 */
    suspend fun writeSeries(seriesId: Long, startedAtMs: Long, parts: List<SettledPart>): Int

    /** @return true = 本次交付被对比桥认账（armed 仍然为真） */
    fun deliverPractice(part: SettledPart): Boolean

    /** 清掉练习桥态（非干净成功/无已发布段） */
    fun clearPractice()
}

/**
 * 结算所有者本体。
 *
 * **恰好一次**：同一场结算（`seriesId + startedAt + 已发布段集合`）只启动一个任务，
 * 重复调用拿回同一枚 [Deferred]。键里刻意**不含** `armed`——武装状态是发起那一刻的快照，
 * 后面的桥态变化不许让同一场录制再交付一次。
 *
 * **迟到对账**：R06 的迟到 commit 会改变已发布段集合 ⇒ 键变化 ⇒ 以新集合再结算一次
 * （`SeriesStore` 的 upsert 是 REPLACE，成员行收敛到最终集合，不会留下旧段的孤儿行）。
 */
internal class TakeSettlementOwner(
    private val scope: CoroutineScope,
    private val inFlight: MutableMap<SettlementKey, Deferred<TakeSettlementOutcome>> = HashMap()
) {

    /** 恰好一次的键：系列归属 + 会话起始时刻 + **已发布段的段序/uri 清单**（不含 armed 快照） */
    private data class SettlementKey(
        val seriesId: Long,
        val startedAtMs: Long,
        val parts: List<Pair<Int, String?>>
    )

    private val lock = Any()

    fun settle(
        seriesId: Long,
        startedAtMs: Long,
        parts: List<SettledPart>,
        resultOk: Boolean,
        practiceArmed: Boolean,
        backend: TakeSettlementBackend
    ): Deferred<TakeSettlementOutcome> {
        val committed = committedSettledParts(parts)
        val key = settlementKey(seriesId, startedAtMs, committed)
        val job = synchronized(lock) {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                runSettlement(seriesId, startedAtMs, committed, resultOk, practiceArmed, backend)
            }.also { inFlight[key] = it }
        }
        job.start()
        return job
    }

    private suspend fun runSettlement(
        seriesId: Long,
        startedAtMs: Long,
        committed: List<SettledPart>,
        resultOk: Boolean,
        practiceArmed: Boolean,
        backend: TakeSettlementBackend
    ): TakeSettlementOutcome {
        // 归组写库失败不许拖累练习交付：媒体已经发布是独立事实。异常在这里收住，
        // 交给调用方一枚可读的 seriesSettled=false（页面据此只影响日志，不影响导航）。
        val write = runCatching { backend.writeSeries(seriesId, startedAtMs, committed) }
        val rows = write.getOrDefault(0)
        var delivered = false
        var cleared = false
        when (val d = practiceDecision(practiceArmed, resultOk, committed)) {
            is PracticeDecision.Deliver ->
                delivered = runCatching { backend.deliverPractice(d.part) }.getOrDefault(false)
            PracticeDecision.Clear -> {
                runCatching { backend.clearPractice() }
                cleared = true
            }
            PracticeDecision.Leave -> Unit
        }
        return TakeSettlementOutcome(
            committedParts = committed.size,
            seriesRows = rows,
            seriesSettled = write.isSuccess,
            practiceDelivered = delivered,
            practiceCleared = cleared
        )
    }

    private fun settlementKey(
        seriesId: Long,
        startedAtMs: Long,
        committed: List<SettledPart>
    ): SettlementKey = SettlementKey(
        seriesId = seriesId,
        startedAtMs = startedAtMs,
        parts = committed.map { it.partIndex to it.uriStr }
    )
}

/** `VideoSegment` → 纯数据视图（生产侧唯一适配点；JVM 测试直接用 [SettledPart]） */
internal fun VideoSegment.toSettledPart(): SettledPart = SettledPart(
    partIndex = partIndex,
    uriStr = uri?.toString(),
    durationMs = durationMs,
    committed = committed
)

/** 生产后端：Room 归组 + 对比练习桥 */
internal class AndroidTakeSettlementBackend(private val db: WotaDb) : TakeSettlementBackend {

    override suspend fun writeSeries(
        seriesId: Long,
        startedAtMs: Long,
        parts: List<SettledPart>
    ): Int = SeriesStore.record(
        db,
        seriesId,
        startedAtMs,
        parts.map { SeriesPartInput(it.partIndex, it.uriStr, it.durationMs, committed = true) }
    )

    override fun deliverPractice(part: SettledPart): Boolean {
        val mediaId = part.uriStr
            ?.let { runCatching { ContentUris.parseId(Uri.parse(it)) }.getOrNull() }
            ?: return false
        return ComparePractice.deliver(mediaId)
    }

    override fun clearPractice() {
        ComparePractice.armed = false
    }
}

/** 进程级入口：录制页只调这里，不再自己写库/交付 */
internal object TakeSettlement {

    private val owner = TakeSettlementOwner(
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    fun settle(
        db: WotaDb,
        seriesId: Long,
        startedAtMs: Long,
        parts: List<VideoSegment>,
        resultOk: Boolean,
        practiceArmed: Boolean
    ): Deferred<TakeSettlementOutcome> = owner.settle(
        seriesId = seriesId,
        startedAtMs = startedAtMs,
        parts = parts.map { it.toSettledPart() },
        resultOk = resultOk,
        practiceArmed = practiceArmed,
        backend = AndroidTakeSettlementBackend(db)
    )
}
