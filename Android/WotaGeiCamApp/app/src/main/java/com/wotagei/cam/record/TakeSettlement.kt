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
 * **恰好一次**：同一场结算（`seriesId + startedAt + 已发布段集合 + 成功结论`）只启动一个任务，
 * 重复调用拿回同一枚 [Deferred]。键里刻意**不含** `armed`——武装状态是发起那一刻的快照，
 * 后面的桥态变化不许让同一场录制再交付一次。
 *
 * **迟到对账**：R06 的迟到 commit 会改变已发布段集合 ⇒ 键变化 ⇒ 以新集合再结算一次
 * （`SeriesStore` 的 upsert 是 REPLACE，成员行收敛到最终集合，不会留下旧段的孤儿行）。
 * R07 的迟到终态可能只改正**结论**不改段集合（join 超时快照判失败、泵线程收尾成功）：
 * 结论进键 ⇒ "先失败后成功"补交付练习、"先成功后失败"清桥态——只按段集合去重会把后到
 * 的相反结论吞掉（练习被漏交付，或失败成片被当成练习交付）。
 */
internal class TakeSettlementOwner(
    private val scope: CoroutineScope,
    /** 结算去重表保留上限：超出后按插入顺序淘汰**已完成**的最老项（见 [evictCompletedLocked]） */
    private val maxRetainedKeys: Int = 64,
    /** 会话落库顺序链保留上限：同上，只淘汰已完成的会话链（未完成链仍被新任务 join 着） */
    private val maxRetainedSessions: Int = 8
) {
    // 放在类体，不进构造签名：SettlementKey 是私有嵌套类型，出现在 internal 构造参数里
    // 算不出合法签名（父树同修）
    private val inFlight = LinkedHashMap<SettlementKey, Deferred<TakeSettlementOutcome>>()

    /**
     * 恰好一次的键：系列归属 + 会话起始时刻 + **已发布段的段序/uri 清单** + 成功结论。
     * 前三项相同而结论相反（R07 迟到终态改正 join 超时快照）是**两笔不同结算**：只按段集合
     * 去重会把后到的相反结论吞掉（该交付的不交付、该清桥态的不清）。不含 `armed` 快照。
     */
    private data class SettlementKey(
        val seriesId: Long,
        val startedAtMs: Long,
        val parts: List<Pair<Int, String?>>,
        val resultOk: Boolean
    )

    private val lock = Any()

    /**
     * 同一会话的**落库顺序**（发起顺序）：R06 的迟到段会让后发起的结算带更大集合，两笔并发落库
     * 时"先发起但完成更晚"的旧集合会把系列成员写回旧集合（REPLACE 缩水）。链上等待前一笔完成
     * 再写，后发起的集合必定最后落库（调用顺序即收敛方向）；异常/取消由 runSettlement 自己收住，
     * `join` 不抛。键相同的重复调用仍走 [inFlight] 去重，不会多跑。
     */
    private data class SessionId(val seriesId: Long, val startedAtMs: Long)
    // 进程级所有者与 App 同寿：两本表都必须有界（见 evictCompletedLocked）
    private val tail = LinkedHashMap<SessionId, Deferred<TakeSettlementOutcome>>()

    fun settle(
        seriesId: Long,
        startedAtMs: Long,
        parts: List<SettledPart>,
        resultOk: Boolean,
        practiceArmed: Boolean,
        backend: TakeSettlementBackend
    ): Deferred<TakeSettlementOutcome> {
        val committed = committedSettledParts(parts)
        val key = settlementKey(seriesId, startedAtMs, committed, resultOk)
        val job = synchronized(lock) {
            inFlight[key] ?: run {
                val session = SessionId(seriesId, startedAtMs)
                val prev = tail[session]
                val created = scope.async(start = CoroutineStart.LAZY) {
                    // 前一笔（更小的集合 / 相反结论）先落库，再落这一笔——顺序由发起顺序决定
                    prev?.join()
                    runSettlement(seriesId, startedAtMs, committed, resultOk, practiceArmed, backend)
                }
                inFlight[key] = created
                tail[session] = created
                evictCompletedLocked(keepKey = key, keepSession = session)
                created
            }
        }
        job.start()
        return job
    }

    /**
     * 两本表的**容量上界**（调用方持锁）：进程级所有者与 App 同寿，不设界就是每录一场泄漏
     * 一条键/一条会话链（键里握着段序 + uri 字符串）。
     *
     * 只淘汰**已完成**且不是本次新键/新会话的最老项：
     * - 未完成的任务仍被调用方 `await` 着，删表不删对象，但重复调用会再造一笔（去重失效）；
     * - 已完成项被淘汰后再收到同键重复调用，会重新跑一次结算——写库是 REPLACE upsert、
     *   练习交付在桥态已清后返回 false，效果仍幂等，不会双交付。
     * - 会话链只可能淘汰"全部落库都已结束"的旧链；新一轮结算取不到 prev 时，旧链的写早已完成，
     *   落库顺序依旧成立。
     */
    private fun evictCompletedLocked(keepKey: SettlementKey, keepSession: SessionId) {
        if (inFlight.size > maxRetainedKeys) {
            val it = inFlight.entries.iterator()
            while (it.hasNext() && inFlight.size > maxRetainedKeys) {
                val e = it.next()
                if (e.key != keepKey && e.value.isCompleted) it.remove()
            }
        }
        if (tail.size > maxRetainedSessions) {
            val it = tail.entries.iterator()
            while (it.hasNext() && tail.size > maxRetainedSessions) {
                val e = it.next()
                if (e.key != keepSession && e.value.isCompleted) it.remove()
            }
        }
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
        committed: List<SettledPart>,
        resultOk: Boolean
    ): SettlementKey = SettlementKey(
        seriesId = seriesId,
        startedAtMs = startedAtMs,
        parts = committed.map { it.partIndex to it.uriStr },
        resultOk = resultOk
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

    /**
     * 迟到对账的**段视图**入口（R06/R07）：调用方（[RecordSession]）已持有 [SettledPart] 视图
     * （迟到事件的结算视图在交付时就定型），避免"VideoSegment → SettledPart"二次反推。
     * 调用方自带后端（引擎侧会话交付没有页面/仓库句柄），但仍走**同一枚所有者**——
     * 与 UI 的正常结算按同一个键去重，不会交付两次。
     */
    fun settleView(
        backend: TakeSettlementBackend,
        seriesId: Long,
        startedAtMs: Long,
        parts: List<SettledPart>,
        resultOk: Boolean,
        practiceArmed: Boolean
    ): Deferred<TakeSettlementOutcome> = owner.settle(
        seriesId = seriesId,
        startedAtMs = startedAtMs,
        parts = parts,
        resultOk = resultOk,
        practiceArmed = practiceArmed,
        backend = backend
    )
}
