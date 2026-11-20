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
 * # 两种结论（R06/R07 的 pending 语义）
 * - **临时快照**（`RecordResult.terminal == false`）：停录 join 超时时泵线程还在封段/发布。
 *   它可以带已封段与丢失账，但**不是失败**：没有真实引擎错误时 `error == null`；UI 只做
 *   "已保存 N 段 / 收尾中"的中间呈现，绝不清练习桥、绝不交付练习。
 * - **权威结论**（`terminal && pendingPublications == 0`）：引擎已终态、发布路已静默。
 *   只有此时才 [TakeSettlement.settleView]；迟到发布把 pending 减到 0 时再补一次。
 *
 * # 恰好一次
 * - 结算交给 [TakeSettlement] 的进程级所有者：键 = `seriesId + startedAtMs + 已发布段集合 +
 *   成功结论`。同一集合重复调用拿回同一枚任务（[TakeSettlementOwner] 的 `inFlight`）。
 * - 本对象额外挡两种情况：① 停录线程与页面 collect 各登记一次（同会话只建一个槽）；
 *   ② 段集合/结论/发布 pending 没变化的事件不触发再结算。
 * - 会话身份 `(seriesId, startedAtMs, attempt)` 与事件同源；`SessionDelta.version` 单调递增，
 *   页面只认同一身份的最高 version，迟到旧版本不许覆盖新结论。
 *
 * # 数据红线（不许假成功）
 * - 临时快照的 `error` 是真实首错；迟到成功段**不洗白**它（[mergeSessionResult] 只并段）。
 * - 临时快照没有真实错误时 `error` 必须是 null：发布仍 pending 不是 TOO_SHORT/ROTATE_STALL。
 * - `practiceArmed` 用**停录那一刻的快照**（首次登记锁定）：桥态后来变化不许让同一场录制
 *   再交付一次；迟到对账也不再读 `ComparePractice.armed`。
 */
internal data class SessionDelta(
    /** 合并迟到事件后的最新结果视图（UI 只用来刷新相册/回看字段，不重复提示） */
    val result: RecordResult,
    /** 稳定会话身份：迟到/终态/UI 修订必须三者全同（自检重录复用 seriesId+startedAtMs） */
    val seriesId: Long = result.seriesId,
    val startedAtMs: Long = 0L,
    val attempt: Int = 0,
    /** 本槽单增版本：页面只接受同一身份的最高 version（防迟到旧结论覆盖新结论） */
    val version: Long = 0L,
    /** 是否已到权威结论（= result.authoritative） */
    val terminal: Boolean = result.authoritative,
    /** 停录登记时锁定的练习桥态快照（后续迟到事件不重读 ComparePractice） */
    val practiceArmed: Boolean = false,
    /** 权威结论对应的结算任务（临时快照恒 null；页面 await practiceDelivered） */
    val settlement: Deferred<TakeSettlementOutcome>? = null
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

/**
 * 权威结果重算（纯函数）：终态结果 + 已知段集合 ⇒ 全量结果。
 *
 * - `base` 决定会话级量（duration/lost/identity/terminal/pending）；
 * - `extra` 只补段（迟到发布/早期快照里已有而终态没带的段）；
 * - 零产物且发布路静默时才落真实失败码：发布失败优先 PUBLISH，其余 TOO_SHORT；
 * - 零产物但 pending>0 保持 `error=null`（pending 不是失败）。
 */
internal fun authoritativeRecordResult(
    base: RecordResult,
    extra: List<VideoSegment>
): RecordResult {
    val merged = mergeSegments(base.parts, extra)
    val first = merged.firstOrNull()
    val pending = base.pendingPartIds?.size ?: base.pendingPublications
    val pendingEmpty = base.pendingPartIds?.isEmpty() ?: (pending == 0)
    val failures = base.publicationFailures
    // 段集合为空时保留 base 的回看字段（旧调用点/注入的结算视图可能只有 uri/path 而没有 parts）
    val uri = first?.uri ?: base.uri
    val path = first?.path ?: base.path
    val bytes = if (merged.isEmpty()) base.bytes else merged.sumOf { it.bytes }
    val hasProduct = merged.isNotEmpty() || uri != null || path != null
    val error = when {
        base.error != null -> base.error
        !hasProduct && base.terminal && pendingEmpty && failures > 0 ->
            "${RecordError.ENGINE_ERROR}:PUBLISH"
        !hasProduct && base.terminal && pendingEmpty -> RecordError.TOO_SHORT
        else -> null
    }
    return base.copy(
        uri = uri,
        path = path,
        bytes = bytes,
        parts = merged,
        uncommitted = merged.count { !it.committed },
        error = error
    )
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
        val attempt: Int,
        /** 停录那一刻的练习桥态快照（首次登记锁定；见类注） */
        val practiceArmed: Boolean,
        val backend: TakeSettlementBackend,
        /** 结果视图（UI 用；段派生量随迟到事件合并，真实错误不洗白） */
        var result: RecordResult,
        /** 结算视图（只含已发布段；迟到 commit 成功后变大 ⇒ 再结算一次） */
        var settled: List<SettledPart>,
        /** 已发出结算的段集合（段序/uri/时长三元组，与所有者的键同构；时长修正也必须补结算） */
        var settledKey: List<Triple<Int, String?, Long>>? = null,
        /** 已发出结算时的成功结论（R07：终态可能只改正结论不改段集合，结论变了必须补结算） */
        var settledOk: Boolean? = null,
        var settledJob: Deferred<TakeSettlementOutcome>? = null,
        /** 单增版本：每次有效状态变化 +1（UI 据此丢弃迟到旧版本） */
        var version: Long = 0L,
        /** 本槽已确认发布失败/废弃的段序（按段去重，见 LatePart.classification） */
        val failedParts: MutableSet<Int> = HashSet(),
        /**
         * 已经收到最终结论的段序（任何分类）。待发布集合按**确切段序**消账：
         * 终态快照可能比迟到结论更早成型，若按计数覆盖，已发布段会被重新算成待发布，
         * 权威结论永远到不了（R06/R07）。
         */
        val receivedParts: MutableMap<Int, PublishClass> = HashMap()
    ) {
        fun delta(): SessionDelta = SessionDelta(
            result = result,
            seriesId = seriesId,
            startedAtMs = startedAtMs,
            attempt = attempt,
            version = version,
            terminal = result.authoritative,
            practiceArmed = practiceArmed,
            settlement = settledJob
        )
    }

    /** 每个会话最多存几枚"先于登记到达"的交付（实际 1~2 枚：迟到发布 + 终态） */
    private const val MAX_BUFFERED_PER_SESSION = 16

    /** 缓冲会话上限：只为"登记前到达"的极短窗口存在；淘汰时优先丢已静默的 */
    private const val MAX_BUFFERED_SESSIONS = 32

    /**
     * 已终结会话槽保留上限：槽里握着结算后端（生产 = 持有 `WotaDb` 的
     * [AndroidTakeSettlementBackend]），**不能按场次无限累积**。
     */
    private const val MAX_SESSIONS = 8

    /**
     * 尚未权威的会话槽硬上限（引擎终态未知或发布仍 pending）。淘汰时**先淘汰已权威的**；
     * 只有所有槽都 pending 且超过本上限才丢最老的一枚（进程级内存兜底，正常路径不会命中）。
     */
    private const val MAX_PENDING_SESSIONS = 64

    private val lock = Any()
    private val slots = HashMap<SessionKey, Slot>()

    /** 停录线程登记前的交付（泵线程先退出时会走这里） */
    private val buffered = HashMap<SessionKey, MutableList<RecorderDelivery>>()

    /**
     * 待发布账与已收到结论的段集对账：**只减不增**。
     * 生产结果带确切段序时按集合差计算；旧构造点没有段序（null）时退回计数下限 0 的保守算法，
     * 但绝不把计数写回成比已知更大——迟到的终态快照不得复活已发布的段。
     */
    internal fun reconcilePending(result: RecordResult, received: Collection<Int>): RecordResult {
        val ids = result.pendingPartIds ?: return result.copy(
            pendingPublications = (result.pendingPublications - received.size).coerceAtLeast(0)
        )
        val remaining = ids - received.toSet()
        return result.copy(pendingPublications = remaining.size, pendingPartIds = remaining)
    }

    /**
     * 登记一场会话的收尾结果并（若已权威）发起结算（停录线程调用；页面 collect 的同类调用
     * 会在同键去重后拿回同一枚任务）。
     *
     * 临时快照（`result.terminal == false`）**不结算**：不清练习桥、不交付练习；等泵线程终态
     * 或迟到发布把 pending 减到 0 后由 [onDelivery] 触发。
     *
     * @param settled 结算视图（生产默认由 [RecordResult.parts] 映射；测试注入）
     * @param attempt 本会话内的尝试序号（0 = 首录，自检重录逐次 +1；见 [SessionKey.attempt]）
     * @param onDelta 可选：登记后的最新 delta（页面在停录时即可拿到稳定身份；可在任意线程回调）
     * @return 已权威时的结算任务（页面 await `practiceDelivered`）；临时快照/无会话键返回 null
     */
    fun record(
        backend: TakeSettlementBackend,
        seriesId: Long,
        startedAtMs: Long,
        result: RecordResult,
        practiceArmed: Boolean,
        settled: List<SettledPart> = result.parts.map { it.toSettledPart() },
        attempt: Int = 0,
        onDelta: ((SessionDelta) -> Unit)? = null
    ): Deferred<TakeSettlementOutcome>? {
        if (startedAtMs <= 0L) return null
        val key = SessionKey(seriesId, startedAtMs, attempt)
        var delta: SessionDelta? = null
        val job = synchronized(lock) {
            val existing = slots[key]
            if (existing == null) {
                evictLocked(keep = key)
                val created = Slot(
                    seriesId = seriesId,
                    startedAtMs = startedAtMs,
                    attempt = attempt,
                    practiceArmed = practiceArmed,
                    backend = backend,
                    result = result,
                    settled = committedSettledParts(settled)
                )
                slots[key] = created
                // 先按**停录快照**登记并结算（临时快照不结算）；再应用先于登记到达的交付。
                // 顺序：若先并入迟到事件再结算，页面那笔"较小集合"的结算会成为另一个键，
                // 异步写库先后不定 ⇒ 旧集合可能把收敛后的系列成员写回去（缩水）。
                settleLocked(created)
                buffered.remove(key)?.forEach { applyLocked(created, it) }
                settleLocked(created)
                created.version++
                delta = created.delta()
                created.settledJob
            } else {
                // 二次登记（停录线程与页面 collect）：只并段；终态登记覆盖临时快照的结论，
                // 临时快照不许把已到的终态降级。
                val before = existing.result
                val beforeSettled = existing.settled
                val beforeJob = existing.settledJob
                if (result.terminal) {
                    // 引擎终态（发布仍 pending 也算终态）：以终态作基底，保留早期快照里已有段
                    existing.result = authoritativeRecordResult(result, existing.result.parts)
                } else if (!existing.result.terminal) {
                    existing.result = authoritativeRecordResult(
                        existing.result.copy(
                            parts = mergeSegments(existing.result.parts, result.parts),
                            pendingPublications = result.pendingPublications,
                            publicationFailures = maxOf(
                                existing.result.publicationFailures,
                                result.publicationFailures
                            )
                        ),
                        emptyList()
                    )
                } else {
                    existing.result = mergeSessionResult(existing.result, result.parts)
                }
                // 已收到结论的段从待发布集合里消掉：终态/二次登记不得把它们的计数写回来。
                existing.result = reconcilePending(existing.result, existing.receivedParts.keys)
                existing.settled = mergeSettled(existing.settled, committedSettledParts(settled))
                settleLocked(existing)
                if (existing.result != before || existing.settled != beforeSettled ||
                    existing.settledJob !== beforeJob
                ) {
                    existing.version++
                    delta = existing.delta()
                }
                existing.settledJob
            }
        }
        delta?.let { d -> runCatching { onDelta?.invoke(d) } }
        return job
    }

    /**
     * 消费引擎交付（泵线程/发布线程调用）。槽未登记时先缓冲（[record] 登记时一并应用）。
     *
     * @return 状态（段/结论/发布 pending）确实变化时返回最新 delta；重复事件返回 null
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
                rememberBufferedLocked(key, delivery)
                null
            } else {
                val before = slot.result
                val beforeSettled = slot.settled
                val beforeJob = slot.settledJob
                applyLocked(slot, delivery)
                if (slot.result != before || slot.settled != beforeSettled || slot.settledJob !== beforeJob) {
                    slot.version++
                    slot.delta()
                } else null
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
                if (delivery.classification == PublishClass.FAILED) slot.failedParts.add(delivery.partIndex)
                // 这一段已有最终结论：按确切段序从待发布集合消账（任何分类都算落地）。
                slot.receivedParts[delivery.partIndex] = delivery.classification
                val resultWithPending = reconcilePending(
                    slot.result.copy(
                        pendingPublications = slot.result.pendingPartIds?.let { it.size }
                            ?: delivery.pendingPublications.coerceAtLeast(0),
                        publicationFailures = maxOf(slot.result.publicationFailures, slot.failedParts.size)
                    ),
                    slot.receivedParts.keys
                )
                slot.result = authoritativeRecordResult(resultWithPending, emptyList())
            }
            is RecorderDelivery.FinalResult -> {
                // 泵线程终态是权威（控制线程只拿到 join 超时那刻的早期快照）：以它作基底，
                // 把早期快照里已有、它却没有的段并回去；同段以终态为准。
                // `delivery.result.terminal` 已由引擎置 true；pending 字段保留，交给 late 事件收敛。
                // 终态结果不知道已收到的 LatePart FAILED 时，用本槽台账补上（零产物失败要可查）。
                val terminal = delivery.result.copy(
                    publicationFailures = maxOf(
                        delivery.result.publicationFailures,
                        slot.failedParts.size
                    )
                )
                slot.result = reconcilePending(
                    authoritativeRecordResult(terminal, slot.result.parts),
                    slot.receivedParts.keys
                )
                slot.settled = mergeSettled(committedSettledParts(delivery.settled), slot.settled)
            }
        }
        settleLocked(slot)
    }

    /**
     * 结算（调用方持锁）：**只认权威结论**（terminal && pendingPublications==0）。
     * 临时快照/发布仍 pending 时绝不调用 [TakeSettlement]——否则 `practiceDecision` 会把
     * 一次 join 超时快照当成"非干净成功"去清练习桥（page 死在收尾途中就会漏交付）。
     * 已发布段集合**与成功结论**都与上次相同就复用任务；任一变化即再结算。
     */
    private fun settleLocked(slot: Slot): Deferred<TakeSettlementOutcome>? {
        if (!slot.result.authoritative) return slot.settledJob
        val key = slot.settled.map { Triple(it.partIndex, it.uriStr, it.durationMs) }
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

    private fun rememberBufferedLocked(key: SessionKey, delivery: RecorderDelivery) {
        val list = buffered.getOrPut(key) { ArrayList() }
        if (list.size < MAX_BUFFERED_PER_SESSION) list.add(delivery)
        while (buffered.size > MAX_BUFFERED_SESSIONS) {
            // 先丢"所有事件都已静默"的缓冲；全都 pending 才丢最老（硬内存上限，正常不命中）
            val complete = buffered.keys.firstOrNull { k ->
                buffered[k]?.all { it.pendingCount() == 0 } == true
            }
            buffered.remove(complete ?: buffered.keys.first())
        }
    }

    /**
     * 槽淘汰（调用方持锁）：先淘汰**已权威**的最老场次；pending/临时槽在正常上限内绝不淘汰
     * （R06/R07 的迟到结论必须还有人认领）。只有全是 pending 且超过硬上限时才丢最老一枚。
     */
    private fun evictLocked(keep: SessionKey) {
        while (slots.size >= MAX_SESSIONS) {
            val victim = slots.entries
                .filter { it.key != keep && it.value.result.authoritative }
                .minByOrNull { it.key.startedAtMs } ?: break
            slots.remove(victim.key)
            buffered.remove(victim.key)
        }
        while (slots.size > MAX_PENDING_SESSIONS) {
            val victim = slots.entries
                .filter { it.key != keep }
                .minByOrNull { it.key.startedAtMs } ?: break
            slots.remove(victim.key)
            buffered.remove(victim.key)
        }
    }

    /** 交付事件自带的"发布路还剩几段"（FinalResult 从结果字段取） */
    private fun RecorderDelivery.pendingCount(): Int = when (this) {
        is RecorderDelivery.LatePart -> pendingPublications
        is RecorderDelivery.FinalResult -> result.pendingPublications
    }
}
