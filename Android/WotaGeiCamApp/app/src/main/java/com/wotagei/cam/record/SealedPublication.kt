package com.wotagei.cam.record

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 已封口段（fd 已关、容器已收尾）的**独立发布路**（R06）。
 *
 * # 缺陷形态
 * 旧实现把「关 fd → muxer 封口 → 查 SIZE/DATA → 清 IS_PENDING」整条串在换段腿预算
 * （[ROTATE_LEG_BUDGET_MS]）里。预算一旦被 muxer 封口/fd 关闭吃光，`sealBounded` 连发布任务
 * 都不会启动（[BoundedStore] 对 `timeoutMs <= 0` 直接回 TimedOut）⇒ 整段永久 `IS_PENDING=1`，
 * 相册不可见；另一形态是发布任务已发出、调用方等超时返 null，Provider 后来成功公开文件，
 * 段却永远进不了 `parts`（相册可见但录制结果/系列成员对不上）。
 *
 * # 本文件的职责
 * - [PUBLISH_WAIT_MS]：发布**自己的**等待窗，与换段预算完全无关——**即使预算为 0 也照样启动**
 *   （R06 路径一：不许"没预算就不发布"）；
 * - [SealedPublisher]：把 `store.seal` 放到一枚守护线程上跑，预算内完成就直接交回真实结果；
 *   超时则**不取消**那枚调用（binder 无取消接口），完成时经 [SealedPublisher] 的迟到回调补发；
 * - [PublishedPart] / [PublishClass]：**发布者实际返回什么就记什么**，不许按"看着成功"反推。
 *   特别注意 `store.seal(keep=false)` 的返回值是 `committed=true`（它删了记录）——旧口径会把
 *   废片误当"已发布"，这里用 [PublishClass.DISCARDED] 独立分类，废片绝不进 `parts`/系列结算。
 */

/**
 * 封口段发布等待窗（ms）：与换段腿预算同量级，但**独立成账**。
 *
 * 为什么不是 [STORE_CALL_BUDGET_MS] 的别名：两者语义不同。前者是"换段腿里单次入库调用的上限"
 * （腿预算的一部分），这是"已封口段还有多久必须给我结论"（发布路的）。将来调其中一个不连带另一个。
 */
internal const val PUBLISH_WAIT_MS = 3_000L

/** 封口后待公开的一段：fd 已关、容器已收尾，只剩 MediaStore 发布这一步 */
internal data class SealedDraft(
    val partIndex: Int,
    val startMs: Long,
    val durationMs: Long,
    /** false = 废片：发布动作是删记录，结论恒 [PublishClass.DISCARDED] */
    val keep: Boolean
)

/**
 * 一段的发布结论（**实际结果分类**）。
 * - [PUBLISHED]：`IS_PENDING` 已清，相册可见（唯一可进系列/练习结算的类别）；
 * - [UNPUBLISHED]：文件在盘上但清 `IS_PENDING` 失败——段仍进结果（UI 报"可能看不到"），
 *   但不进系列结算（与 Y21「只收 committed 段」同口径）；
 * - [DISCARDED]：`keep=false` 的封口，记录已删；
 * - [FAILED]：发布调用自身抛错/没有拿到任何结论（不许伪造一个 VideoSegment 冒充已保存）。
 *
 * 独立成枚举（而不是用 `keep`/`committed` 反推）是因为分类就是发布者**实际返回的结论**；
 * 会话交付事件 [RecorderDelivery.LatePart] 把它原样带出，消费方据此分辨
 * "文件在盘上但相册不可见"与"根本没保住"。
 */
internal enum class PublishClass { PUBLISHED, UNPUBLISHED, DISCARDED, FAILED }

/** 发布路的实际结果（[segment] 为 null 仅在 [PublishClass.FAILED]） */
internal data class PublishedPart(
    val draft: SealedDraft,
    val segment: VideoSegment?,
    val classification: PublishClass
) {
    /** true = 这段在盘上（保留段），可以进 `RecordResult.parts`；废片/失败不进 */
    val keep: Boolean
        get() = classification == PublishClass.PUBLISHED || classification == PublishClass.UNPUBLISHED

    /** 进结果清单的段视图（废片/失败回 null） */
    fun visibleSegment(): VideoSegment? = if (keep) segment else null
}

/** 发布者返回值 → 分类（纯函数；`keep=false` 压过 `committed`，因为删记录也报 committed=true） */
internal fun classifyPublished(
    draft: SealedDraft,
    segment: VideoSegment?,
    failure: Throwable?
): PublishedPart = when {
    failure != null || segment == null -> PublishedPart(draft, null, PublishClass.FAILED)
    !draft.keep -> PublishedPart(draft, segment, PublishClass.DISCARDED)
    segment.committed -> PublishedPart(draft, segment, PublishClass.PUBLISHED)
    else -> PublishedPart(draft, segment, PublishClass.UNPUBLISHED)
}

/**
 * 独立发布路本体：每段一枚守护线程（与 [BoundedStore] 同款"不让一个卡住的 Provider 调用堵住
 * 后续步骤"的取舍），等待窗只决定**是否同步交回结论**，不决定是否发布。
 *
 * 线程协议（确定性，见 `SealedPublicationTest`）：
 * 1. 调用线程 `publish` 启动工作线程后，按 [waitMs] 等 [CountDownLatch]；
 * 2. 工作线程先写结果、再 `countDown`；迟到回调只在调用方**确实已放弃等待**时发一次
 *    （`givenUp` 集合由调用线程在超时后置入；两端都在锁内改，见 [publish]）；
 * 3. 结果台账（[publishedSegments]）保留每一段的真实结论，供收尾时取"已落地但迟到"的段；
 * 4. 迟到回调抛异常只吞掉留痕——不许把发布线程的栈炸掉（结果已经落账）。
 */
internal class SealedPublisher(
    private val waitMs: Long = PUBLISH_WAIT_MS,
    private val onLate: (PublishedPart) -> Unit = {}
) {

    private val lock = Any()

    /** partIndex → 真实结论（同步完成与迟到完成都留档） */
    private val results = HashMap<Int, PublishedPart>()

    /** 调用线程已放弃等待的段序：工作线程完成时据此决定是否走迟到回调 */
    private val givenUp = HashSet<Int>()

    /**
     * 提交一段发布。
     *
     * @param block 真正会走 MediaProvider 的发布动作（生产 = `VideoStore.seal`）
     * @return 预算内完成 = 真实结论；null = 仍在发布路（**不是失败**，结论稍后经 [onLate] 补发）
     */
    fun publish(draft: SealedDraft, block: () -> VideoSegment): PublishedPart? {
        // 预算为 0 也照样启动：R06 路径一修的就是"没预算就不发布"。此时直接按"已放弃等待"记。
        if (waitMs <= 0L) synchronized(lock) { givenUp.add(draft.partIndex) }
        val done = CountDownLatch(1)
        var value: VideoSegment? = null
        var failure: Throwable? = null
        val worker = Thread({
            try {
                value = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                val part = classifyPublished(draft, value, failure)
                var notify = false
                synchronized(lock) {
                    results[draft.partIndex] = part
                    if (givenUp.remove(draft.partIndex)) notify = true
                }
                done.countDown()
                // 回调自己抛错只吞掉：结果已落账，不许把发布线程的栈炸掉（纯 Kotlin 文件，不引 android.util.Log）
                if (notify) runCatching { onLate(part) }
            }
        }, "WotaSealPub-${draft.partIndex}")
        worker.isDaemon = true
        worker.start()
        if (waitMs <= 0L) return null
        val finished = try {
            done.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            synchronized(lock) {
                // 与工作线程的收尾赛跑：它可能刚写完结果、还没被调用方看见——先查台账，
                // 拿到就按同步结果交回（不许同一段既交回又走迟到回调）。
                results[draft.partIndex]?.let { return it }
                givenUp.add(draft.partIndex)
            }
            return null
        }
        return synchronized(lock) { results[draft.partIndex] }
    }

    /** 已落地的保留段（同步 + 迟到），按段序排好；废片/失败不在列 */
    fun publishedSegments(): List<VideoSegment> = synchronized(lock) {
        results.values.mapNotNull { it.visibleSegment() }.sortedBy { it.partIndex }
    }

    /** 已落地的全部结论（诊断/对账用，含废片与失败） */
    fun outcomes(): List<PublishedPart> = synchronized(lock) {
        results.values.sortedBy { it.draft.partIndex }
    }
}

/**
 * 段清单合并（纯函数）：以 `partIndex` 为唯一键，`existing` 优先（泵线程已写进 `parts` 的那枚
 * 与发布台账里的同一段是**同一结果的两份引用**，不重复、不覆盖），补齐迟到段后按段序排序。
 */
internal fun mergeSegments(
    existing: List<VideoSegment>,
    extra: List<VideoSegment>
): List<VideoSegment> {
    if (extra.isEmpty()) return existing
    val seen = existing.mapTo(HashSet()) { it.partIndex }
    val out = ArrayList<VideoSegment>(existing.size + extra.size)
    out.addAll(existing)
    for (s in extra) if (seen.add(s.partIndex)) out.add(s)
    out.sortBy { it.partIndex }
    return out
}

/**
 * 会话结果视图合并（纯函数）：把迟到发布/迟到终态带来的段并进已有结果。
 *
 * **不许改的东西**：
 * - `error`/`degradedCode`：快照里的失败诊断是本场事实，补一段不洗白失败（防"假成功"）；
 * - `seriesId`/`durationMs`/丢失账：会话级量，段数变化不重算。
 * 该重算的只有段派生量（首段回看字段、总字节、未登记数）。
 */
internal fun mergeSessionResult(result: RecordResult, extra: List<VideoSegment>): RecordResult {
    val merged = mergeSegments(result.parts, extra)
    if (merged.size == result.parts.size) return result
    val first = merged.firstOrNull()
    return result.copy(
        uri = result.uri ?: first?.uri,
        path = result.path ?: first?.path,
        bytes = merged.sumOf { it.bytes },
        parts = merged,
        uncommitted = merged.count { !it.committed }
    )
}