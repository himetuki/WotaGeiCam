package com.wotagei.cam.record

import kotlinx.coroutines.Deferred

/**
 * 录制会话的**迟到对账所有者**（R06/R07 的收尾侧）。
 *
 * # 缺陷形态
 * - R06：封口段的发布（查 SIZE/DATA、清 IS_PENDING）被压在换段腿预算里，预算被吃光后
 *   `store.seal` 根本不会启动（[BoundedStore] 对 `timeoutMs <= 0` 直接判超时）⇒ 段永久
 *   `IS_PENDING=1`；或发布已发出、调用方等超时返回 null，Provider 后来公开了文件，
 *   段却永远进不了结果/系列（相册可见，归组与练习回程对不上）。
 * - R07：控制线程 join 超时"只记账不拆资源"返回后，泵线程晚些才真正封段/收尾——那份终态
 *   没有任何出口，结算用的是收工那一刻的早期快照。
 *
 * 引擎侧修法见 [SealedPublisher] 与 `CodecRecorder.pumpTrail`：发布独立成路、泵线程退出交付终态。
 * 本文件是**消费端**：把早期快照与迟到事件合成一条**恰好一次**的结算流。
 *
 * # 恰好一次
 * - 结算交给 [TakeSettlement] 的进程级所有者：键 = `seriesId + startedAtMs + 已发布段集合`，
 *   同一集合重复调用拿回同一枚任务（[TakeSettlementOwner] 的 `inFlight`）。页面 collect 的
 *   既有调用走同一个键，自动去重。
 * - 本对象额外挡两种情况：① 停录线程与页面 collect 各登记一次（同会话只建一个槽，结果视图
 *   只并段、不许用旧快照降级）；② 段集合没变化的事件不触发再结算。段集合**变化**（迟到 commit
 *   成功）⇒ 新键 ⇒ `SeriesStore` 的 REPLACE upsert 把成员行收敛到最终集合，不残留旧段的孤儿行。
 *
 * # 数据红线（不许假成功）
 * - 合并只改段派生量（首段回看字段、总字节、未登记数）；`error`/`degradedCode` **不洗白**：
 *   快照里的失败诊断是本场事实，补一段迟到文件不等于这次录制成功（见 [mergeSessionResult]）。
 * - `resultOk` 每次结算都从**合并后的结果**取（不是过期快照）。R07 的终态只在 join 超时路径
 *   交付，而那条路径的快照/终态带明确失败码 ⇒ 不会把失败结算成练习交付。
 * - `practiceArmed` 用**停录那一刻的快照**（首次登记时锁定）：桥态后来变化不许让同一场录制
 *   再交付一次。
 */
internal data class SessionDelta(
    /** 合并迟到事件后的最新结果视图（UI 只用来刷新相册/回看字段，不重复提示） */
    val result: RecordResult
)

/**
 * 结算视图合并（纯函数）：按段序去重，[priority]（已有/终态）优先——同一段在迟到事件里
 * 再出现时不许覆盖已经结算过的那份（它们是同一结果的两份引用，字面量也一样）。
 * 结果按段序排序，保证结算键稳定（顺序变化不该触发再结算）。
 */
internal fun mergeSettled(priority: List<SettledPart>, extra: List<SettledPart>): List<SettledPart> {
    if (extra.isEmpty()) return priority
    val seen = priority.mapTo(HashSet(), SettledPart::partIndex)
    val out = ArrayList<SettledPart>(priority.size + extra.size)
    out.addAll(priority)
    for (p in extra) if (seen.add(p.partIndex)) out.add(p)
    out.sortBy { it.partIndex }
    return out
}

internal object RecordSession {

    private data class SessionKey(
        val seriesId: Long,
        val startedAtMs: Long,
        /**
         * 尝试序号（0 = 首录，自检重录逐次 +1）。自检重录沿用同一 `seriesId + startedAtMs`，
         * 没有它就分不开"被判废那次尝试的迟到终态"与"重录成功那次"——前者会把后者洗成失败。
         */
        val attempt: Int
    )

    private class Slot(
        val seriesId: Long,
        val startedAtMs: Long,
        /** 停录那一刻的练习桥态快照（首次登记锁定；见类注） */
        val practiceArmed: Boolean,
        val backend: TakeSettlementBackend,
        /** 结果视图（UI 用；段派生量随迟到事件合并，失败诊断不洗白） */
        var result: RecordResult,
        /** 结算视图（只含已发布段；迟到 commit 成功后变大 ⇒ 再结算一次） */
        var settled: List<SettledPart>,
        /** 已发出结算的段集合（`partIndex to uri`，与所有者的键同构） */
        var settledKey: List<Pair<Int, String?>>? = null,
        /** 已发出结算时的成功结论（R07：终态可能只改正结论不改段集合，结论变了必须补结算） */
        var settledOk: Boolean? = null,
        var settledJob: Deferred<TakeSettlementOutcome>? = null
    )

    /** 每个会话最多存几枚"先于登记到达"的交付（实际 1~2 枚：迟到发布 + 终态） */
    private const val MAX_BUFFERED_PER_SESSION = 16

    /** 会话槽与缓冲的容量上限：槽是几十字节，缓冲只为"登记前到达"的极短窗口存在 */
    private const val MAX_BUFFERED_SESSIONS = 8

    /**
     * 会话槽保留上限：槽里握着结算后端（生产 = 持有 `WotaDb` 的
     * [AndroidTakeSettlementBackend]），**不能按场次无限累积**。一场录制正常只有 1~2 条交付
     * （迟到发布 + 泵线程终态），它们在停录后的秒级内就到齐；保留最近若干场足够覆盖
     * "用户连续录几条、上一条的泵线程还在收尾"的窗口。
     */
    private const val MAX_SESSIONS = 8

    private val lock = Any()
    private val slots = HashMap<SessionKey, Slot>()

    /** 停录线程登记前的交付（泵线程先退出时会走这里） */
    private val buffered = HashMap<SessionKey, MutableList<RecorderDelivery>>()

    /**
     * 登记一场会话的收尾结果并**立即发起首次结算**（停录线程调用；页面 collect 的同类调用
     * 会在同键去重后拿回同一枚任务）。
     *
     * @param settled 结算视图（生产默认由 [RecordResult.parts] 映射；测试注入）
     * @param attempt 本会话内的尝试序号（0 = 首录，自检重录逐次 +1；见 [SessionKey.attempt]）
     * @return 首次结算的任务（页面 await `practiceDelivered` 用）；`startedAtMs <= 0`（无会话键）返回 null
     */
    fun record(
        backend: TakeSettlementBackend,
        seriesId: Long,
        startedAtMs: Long,
        result: RecordResult,
        practiceArmed: Boolean,
        settled: List<SettledPart> = result.parts.map { it.toSettledPart() },
        attempt: Int = 0
    ): Deferred<TakeSettlementOutcome>? {
        if (startedAtMs <= 0L) return null
        val key = SessionKey(seriesId, startedAtMs, attempt)
        return synchronized(lock) {
            val existing = slots[key]
            if (existing == null) {
                // 先按场次上限淘汰最老的一场（连同它没来得及应用的缓冲）：槽持有结算后端，
                // 不清就是每录一条泄漏一枚 WotaDb 引用。淘汰只可能丢掉"早已过窗"的迟到结论，
                // 不改变任何已发出的结算。
                while (slots.size >= MAX_SESSIONS) {
                    val oldest = slots.keys.minByOrNull { it.startedAtMs } ?: break
                    slots.remove(oldest)
                    buffered.remove(oldest)
                }
                val created = Slot(
                    seriesId = seriesId,
                    startedAtMs = startedAtMs,
                    practiceArmed = practiceArmed,
                    backend = backend,
                    result = result,
                    settled = committedSettledParts(settled)
                )
                slots[key] = created
                // 先按**停录快照**发起首次结算：页面 collect 紧接着拿同一份快照再调一次
                // TakeSettlement.settle，键相同（段集合 + 结论都相同）⇒ 拿回同一枚任务。
                // 顺序不许反：若先并入缓冲的迟到事件再结算，页面那笔“较小集合”的结算就会成为
                // 另一个键，异步写库先后不定 ⇒ 旧集合可能把收敛后的系列成员写回去（缩水）。
                settleLocked(created)
                // 再应用先于登记到达的交付（终态/迟到发布）：段集合变大或结论改正 ⇒ 新键再结算
                buffered.remove(key)?.forEach { applyLocked(created, it) }
                // 返回最终任务（没有缓冲事件时就是上面那枚，键未变会复用）
                settleLocked(created)
            } else {
                // 二次登记（停录线程与页面 collect）：只并段，不许用页面手里的旧快照覆盖
                // 已经并入的迟到结果（error/degraded 也不动，见 mergeSessionResult）
                existing.result = mergeSessionResult(existing.result, result.parts)
                existing.settled = mergeSettled(existing.settled, committedSettledParts(settled))
                settleLocked(existing)
            }
        }
    }

    /**
     * 消费引擎交付（泵线程/发布线程调用）。槽未登记时先缓冲（[record] 登记时一并应用）。
     *
     * @return 结果视图确实多出段时返回最新视图（UI 刷新相册/回看字段）；其余情况 null
     */
    fun onDelivery(delivery: RecorderDelivery): SessionDelta? {
        val seriesId: Long
        val startedAtMs: Long
        val attempt: Int
        when (delivery) {
            is RecorderDelivery.LatePart -> {
                seriesId = delivery.seriesId
                startedAtMs = delivery.startedAtMs
                attempt = delivery.attempt
            }
            is RecorderDelivery.FinalResult -> {
                seriesId = delivery.seriesId
                startedAtMs = delivery.startedAtMs
                attempt = delivery.attempt
            }
        }
        if (startedAtMs <= 0L) return null
        val key = SessionKey(seriesId, startedAtMs, attempt)
        return synchronized(lock) {
            val slot = slots[key]
            if (slot == null) {
                val list = buffered.getOrPut(key) { ArrayList() }
                if (list.size < MAX_BUFFERED_PER_SESSION) list.add(delivery)
                while (buffered.size > MAX_BUFFERED_SESSIONS) buffered.remove(buffered.keys.first())
                null
            } else {
                val before = slot.result.parts.size
                applyLocked(slot, delivery)
                val after = slot.result.parts.size
                if (after != before) SessionDelta(slot.result) else null
            }
        }
    }

    /** 事件 → 结果视图/结算视图合并 + 必要的再结算（调用方持锁） */
    private fun applyLocked(slot: Slot, delivery: RecorderDelivery) {
        when (delivery) {
            is RecorderDelivery.LatePart -> {
                // 废弃/发布失败段交付时 segment/settled 均为 null（不许把它算成产物）
                delivery.segment?.let { slot.result = mergeSessionResult(slot.result, listOf(it)) }
                delivery.settled?.let { slot.settled = mergeSettled(slot.settled, committedSettledParts(listOf(it))) }
            }
            is RecorderDelivery.FinalResult -> {
                // 泵线程终态是权威（控制线程只拿到 join 超时那刻的早期快照）：以它作基底，
                // 把早期快照里已有、它却没有的段并回去；同段以终态为准（合并函数的优先级）
                slot.result = mergeSessionResult(delivery.result, slot.result.parts)
                slot.settled = mergeSettled(committedSettledParts(delivery.settled), slot.settled)
            }
        }
        settleLocked(slot)
    }

    /**
     * 结算（调用方持锁）：已发布段集合**与成功结论**都与上次相同就复用任务；任一变化即再结算。
     * 任务本身在 [TakeSettlement] 的进程级作用域里跑，页面生死不影响落库/交付。
     */
    private fun settleLocked(slot: Slot): Deferred<TakeSettlementOutcome>? {
        val key = slot.settled.map { it.partIndex to it.uriStr }
        // 成功态取合并后的结果：迟到段不许把失败洗成成功；早期成功遇终态失败也不会再交付练习。
        // 它和段集合同样进去重判据：R07 的迟到终态可能只改正结论（段集合不变），漏掉就会
        // "该交付的练习不交付 / 该清的桥态不清"。
        val ok = slot.result.ok
        val existing = slot.settledJob
        if (existing != null && key == slot.settledKey && ok == slot.settledOk) return existing
        slot.settledKey = key
        slot.settledOk = ok
        val job = TakeSettlement.settleView(
            backend = slot.backend,
            seriesId = slot.seriesId,
            startedAtMs = slot.startedAtMs,
            parts = slot.settled,
            resultOk = ok,
            practiceArmed = slot.practiceArmed
        )
        slot.settledJob = job
        return job
    }
}