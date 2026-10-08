package com.wotagei.cam.record

import java.util.concurrent.atomic.AtomicLong

/**
 * 换段（分段轮转）这一腿的**有界化 + 记账**（2026-10-08 真机缺陷修复）。
 *
 * # 缺陷现场（WIKO GAR-AN60 / Android 11 / EMUI，间歇但每轮必中）
 * 24※ 强制档 ⇒ [CodecRecorder] + GPU + MEND；把 `MAX_FILE_BYTES` 临时调成 32MiB 后每段≈7.4s。
 * 真机每轮的段数在 1~6 之间浮动、之后**必然**停摆几十秒（DIRECT 路同样中招）：段名时间戳
 * （= `createPending` 调用时刻）相差 30~45s，期间没有任何新文件、没有任何错误码、UI 无提示，
 * 停摆窗口里的画面被静默丢弃；停止时才又封出一段。
 *
 * # 根因（代码级结论，现场分解见 `docs` 与提交信息）
 * 阈值触顶之后，泵线程要**串行**走完「拆旧编码器/muxer → 关输出 fd → 查 SIZE/DATA → 清 IS_PENDING
 * （且失败还重试一次）→ 建新 pending → 打开新 fd → 重建编码器 → 起新段」这一整腿，其中每一步都是
 * **无上限**的同步 FUSE / MediaProvider 调用，全部压在**唯一的产出线程**上。这台机的 MediaProvider
 * 对 33MB 级 pending 文件的收尾（close/scan/rename，见 `PendingName` 那条同源的异步改名实证）
 * 会长时间不返回，于是这一段腿会卡几十秒：
 * - 段名时间戳落在 `createPending` 入口 ⇒ 卡点必在「阈值触顶 → 建下一段 pending」之间，
 *   即上表的拆解/入库腿（`closeSegmentEngine` → `sealCurrent`），**不是**编码器重建、也不是 GL 重挂；
 *   且封出的段时长恰好等于阈值触顶时刻（7.40s/31.5MB）⇒ 泵不是"写不满"，是"到点后不换段"；
 * - 全程无错误码 ⇒ 卡点是一条**不抛异常的慢路径**（真失败的 DRAIN/NO_VIDEO_TRACK/MUX 都会置
 *   `engineError`，录制中途看门狗会在 200ms 内把录制收掉并亮错——现场没有，故排除那些支路）；
 * - `ElapsedClock` 在 `closeSegment()` 之后、下一段 `startSegment()` 之前不跑 ⇒ 停摆期间计时冻结；
 * - DIRECT 路（无 GPU/无 GL 重挂）同样停摆 ⇒ 排除 GL 背压与重挂环。
 *
 * # 本文件的职责
 * 把这一腿的**等待**变成有界的、把**丢掉的内容**变成账目：
 * - [ROTATE_LEG_BUDGET_MS]：换段腿的墙钟上限（一旦编码器被拆，这一腿里所有等待都受它约束）；
 * - [STORE_CALL_BUDGET_MS]：不在换段腿里的单次入库调用上限（停止时的封段）；
 * - [BoundedStore]：把会走 MediaProvider / FUSE 的调用挪到一枚守护线程上并按上限等，
 *   超时**不当成功**，由调用方按 [RotateLossLedger] 记账并（在被判停摆时）走明确失败通路；
 * - [rotateLostMs] / [RotateLossLedger]：把"这一腿花了多久"折算成"这段丢了多久"（良性开销
 *   [ROTATE_BENIGN_MS] 不计），进 `RecordResult.segLostMs` 与 sidecar 段清单，用户事后可查；
 * - [OneShotHandoff]：判停摆时**晚到**的那枚 pending 不许变幽灵（见类注）。
 *
 * 纯 Kotlin（无 Android 依赖），判定与记账全表 JVM 单测；线程件的协议另有确定性用例。
 */

/**
 * 换段腿的墙钟上限：从**编码器被拆的那一刻**（丢帧窗口开启）到新段 `codec.start()` 返回。
 * 健康轮这一腿在同一台机上实测 <1s（段名间隔 7~8s vs 段内容 7.4s），3s 留了数倍余量；
 * 一旦超过它就是"这段在丢内容"，必须收手走明确失败而不是陪它静默丢几十秒。
 */
internal const val ROTATE_LEG_BUDGET_MS = 3_000L

/**
 * 不在换段腿里的单次入库调用上限（停止路径的封段：关 fd、查 SIZE/DATA、清 IS_PENDING）。
 * 取与腿同一量级：健康轮这些调用在 <1s 内返回；超时只记账 + 留日志，**不置失败码**——
 * 停止时的封段慢不等于录制出错（那份文件照样会被晚到的 commit 落地）。
 */
internal const val STORE_CALL_BUDGET_MS = 3_000L

/**
 * 换段腿里算"正常开销"的那一段：拆 muxer（写 moov）→ 入库 close/commit → 建新 pending →
 * 重建编码器 → 起新段。这几步本身也要耗时间，其间的画面本来就会丢（见 `GlRenderSuppression`
 * 的既定取舍："换段点附近丢几帧"），所以记账时先扣掉这一档，只把**超出它的**部分算成异常丢失，
 * 免得健康轮每段都报"丢了半秒"。
 */
internal const val ROTATE_BENIGN_MS = 1_000L

/**
 * 换段腿是否已经超过上限。边界口径与 `healthVerdict` 的窗口一致（恰好到窗算超窗，不无限等）。
 */
internal fun rotateLegOverBudget(legMs: Long, budgetMs: Long = ROTATE_LEG_BUDGET_MS): Boolean =
    legMs >= budgetMs

/**
 * 清账类调用（关输出 fd）的最低预算：腿预算已尽时也要留一点给"收摊"——
 * 关不及时就是 fd 泄漏（`openAssetFileDescriptor` 的句柄悬到进程结束），比多等半秒贵得多。
 */
internal const val CLEANUP_MIN_BUDGET_MS = 500L

/** 清账类调用的预算：腿剩余预算与下限取大（纯函数，全表单测） */
internal fun cleanupBudgetMs(leftMs: Long, floorMs: Long = CLEANUP_MIN_BUDGET_MS): Long =
    maxOf(leftMs, floorMs)

/**
 * 停止路径的换段受阻判定（纯函数）：用户按停止时泵线程还卡在腿里，用它兜底。
 *
 * 判据**比泵侧收严**，因为停止时的"最后一段封段"本身就可能慢（大文件 commit 要扫+改名）：
 * 把它误报成"丢了 N 秒"比漏报更糟（用户以为内容丢了而其实没丢）。只有两种情形才算：
 * - [storeTimedOut]：本腿已有入库调用撞上限（有界化真生效、真被顶到墙上的直接证据）；
 * - [legMs] 超过"有界腿的最坏耗时"（腿预算 + 一次清账上限）：那说明腿里有一步根本没受约束。
 *
 * @return true = 按换段受阻判明确失败（丢失时长照记，见 [RotateLossLedger]）
 */
internal fun rotateStopStallDecision(storeTimedOut: Boolean, legMs: Long): Boolean =
    storeTimedOut || legMs >= ROTATE_LEG_BUDGET_MS + STORE_CALL_BUDGET_MS

/**
 * 结果里"丢了多少秒"提示的起报门槛（ms）：低于它只进账、不打扰用户——
 * 健康轮每段的良性开销会带来几百毫秒量级的小差额，逐次弹提示是噪音。
 */
internal const val LOSS_NOTICE_MIN_MS = 1_000L

/**
 * 本腿实际丢掉的画面时长（腿耗时 - 良性开销，负数归零）。
 * 语义：这一腿里编码器已被拆、新段还没起来，进来的帧写不进任何容器 ⇒ 腿耗时就是丢帧窗口。
 */
internal fun rotateLostMs(legMs: Long, benignMs: Long = ROTATE_BENIGN_MS): Long =
    (legMs - benignMs).coerceAtLeast(0L)

/**
 * 换段受阻是否要判明确失败（"计划→行为"桥）。两个入参是两条互相独立的路：
 * [storeTimedOut] = 本腿里有入库调用撞了上限（[BoundedStore] 超时，最直接的证据）；
 * [legMs] = 本腿墙钟（入库没超时但编码器/muxer 这类 native 调用拖长了，也要抓）。
 * **必须走这座桥**：直接把判定写成 `legMs > ROTATE_LEG_BUDGET_MS` 会让"入库超时 + 腿没到点"
 * 这一态漏掉（入库是撞了上限就返回的，腿耗时可能刚好没越过预算）。
 */
internal fun rotateStallDecision(
    storeTimedOut: Boolean,
    legMs: Long,
    budgetMs: Long = ROTATE_LEG_BUDGET_MS
): Boolean = storeTimedOut || rotateLegOverBudget(legMs, budgetMs)

/**
 * 换段受阻的失败码（带丢帧账 + 环节游标）：`ROTATE_STALL:<总计丢失ms>[:<环节>]`。
 * 环节游标（[RotateStage]）只给排查用：这台 ROM 吞掉应用日志，账目（UI 提示/段清单/
 * `rotate-fail.log`）是唯一能分辨"卡在哪一步"的通道。
 */
internal fun rotateStallError(totalLostMs: Long, stage: String = ""): String =
    if (stage.isEmpty()) "${RecordError.ROTATE_STALL}:$totalLostMs"
    else "${RecordError.ROTATE_STALL}:$totalLostMs:$stage"

/** 换段腿里的环节游标（失败账用；空串 = 未定位到具体一步） */
internal object RotateStage {
    const val SEAL = "SEAL"
    const val INSERT = "INSERT"
    const val OPEN = "OPEN"
    const val ENCODE = "ENCODE"
    const val MUX = "MUX"
    const val START = "START"
    const val ROTATE = "ROTATE"
}

/**
 * 取回失败码里的环节游标（UI/账目用）。非本码或无游标返回空串——
 * 与 [rotateStallLostMs] 同一套"码带载荷"口径。
 */
internal fun rotateStallDetail(code: String?): String {
    if (code == null || !code.startsWith(RecordError.ROTATE_STALL + ":")) return ""
    // 形如 ROTATE_STALL:1200:INSERT → 第三段；没有第三段即空
    return code.split(':').getOrNull(2)?.takeIf { it.isNotBlank() } ?: ""
}

/**
 * 一句可直接落盘的失败账（纯函数，JVM 全表单测）：这台 ROM 没有 logcat、`dumpsys media.codec`
 * 也不可用，失败账必须是**文件**才能跨轮次分辨"哪一环卡住、丢了多久"。
 * 形如 `t=2026-10-08 14:43:40 parts=2 dur=88s lost=1200ms code=ROTATE_STALL:1200:INSERT`；
 * 有过"超时后迟到落地的 insert"时追加 ` late=N`（那些是只能靠回查确认的幽灵候选，见 [OneShotHandoff]）。
 */
internal fun rotateFailLedgerLine(
    stamp: String,
    parts: Int,
    durationMs: Long,
    lostMs: Long,
    code: String,
    latePending: Long = 0L,
    restarts: Int = 0
): String = buildString {
    append("t=").append(stamp)
    append(" parts=").append(parts)
    append(" dur=").append(durationMs / 1000).append('s')
    append(" lost=").append(lostMs).append("ms")
    append(" code=").append(code)
    if (latePending > 0L) append(" late=").append(latePending)
    if (restarts > 0) append(" restarts=").append(restarts)
}

/**
 * muxer 写失败的**现场快照**（纯函数）：这台 ROM 吞掉应用日志，"写超停"还是"写先于 start"、
 * 哪条轨、第几段、写的是本段第几笔，只能靠这一行随失败码（`ENGINE_ERROR:MUX|<快照>`）带出来。
 *
 * 关键读法（真机 2026-10-08 第二次复现全是 `ENGINE_ERROR:MUX`，需要这一行定性）：
 * - `field=gone` ⇒ 写的那一刻 `muxer` 字段已被 `closeSegmentEngine` 置空/释放（写超停，
 *   换段期收尾竞态）；`field=same` ⇒ 字段还指着这枚 muxer（不是"我们把它关了"，
 *   而是这枚 muxer 自己不收样本 ⇒ 换行后的 fd/轨状态或 native 写失败）。
 * - `started`：**由闸门推导**（写路径只在 `muxStarted` 为真时进入，见 runSegment 的两处写点），
 *   所以恒 1；它留在这里是为了"哪天真冒出一条不接闸门的新写路径"，那一刻它会变 0。
 * - `wb/vs` = 本段已写字节/已写视频样本数：都为 0 ⇒ 新段刚起来的**首笔**就写不动（重建判据）。
 * - `st/rp` = 失败当刻 stopping/rotatePending 的值（区分"停止期余帧"与"换段期"）。
 */
internal fun muxFailDiagnostic(
    kind: String,
    started: Boolean,
    fieldSame: Boolean,
    partIndex: Int,
    track: Int,
    size: Int,
    ptsUs: Long,
    flags: Int,
    segBaseUs: Long,
    writtenBytes: Long,
    videoSamples: Int,
    restarts: Int,
    stopping: Boolean,
    rotatePending: Boolean
): String = listOf(
    "trk=$kind",
    "started=${if (started) 1 else 0}",
    "field=${if (fieldSame) "same" else "gone"}",
    "part=$partIndex",
    "n=$track",
    "sz=$size",
    "pts=$ptsUs",
    "fl=$flags",
    "base=$segBaseUs",
    "wb=$writtenBytes",
    "vs=$videoSamples",
    "rs=$restarts",
    "st=${if (stopping) 1 else 0}",
    "rp=${if (rotatePending) 1 else 0}"
).joinToString(";")

/**
 * 已被有界重建接住的 muxer 写失败（**不再判废整场**）：它不进 `RecordResult.error`（进去看门狗会
 * 把录制停掉），但必须让账目看得见"这一场踩过 muxer 写失败、现场长什么样"——所以它有自己的标记。
 */
internal const val MUX_RECOVERED_PREFIX = "MUX_RECOVERED"

/** 已重建的 muxer 写失败 → 账目里用的标记码（带现场快照） */
internal fun muxRecoveredCode(diag: String): String = "$MUX_RECOVERED_PREFIX|$diag"

/** 失败码前缀：`ENGINE_ERROR:MUX|<快照>`（保持 `ENGINE_ERROR` 前缀不变，UI 按前缀映射不动） */
internal fun muxFailError(diag: String): String = "${RecordError.ENGINE_ERROR}:MUX|$diag"

/** 从失败码/账目里取回 muxer 现场快照（非本码返回空串） */
internal fun muxFailDetailOf(code: String?): String {
    val d = code?.substringAfter(":MUX|", "") ?: ""
    return if (d == code) "" else d
}

/**
 * 本段是否"一笔都没写成"（新段刚起来的形态）：重建本段的判据之一。
 * 已写过再失败就不是"起来就写不动"，那是段中途坏，重建的代价与收益都不同。
 */
internal fun freshSegmentMuxFailure(videoSamples: Int, writtenBytes: Long): Boolean =
    videoSamples <= 0 && writtenBytes <= 0L

/**
 * 新段首笔写不进 muxer 后的**有界重建**判定（纯函数桥，全表单测）。
 *
 * 为什么是"重建本段"而不是"吞掉异常"或"判废整场"：真机现场是"1~2 段即死、后半段全没了"
 * （用户能看到的提示是引擎报错），而失败的段本身**一笔都没写成**（`wb=0/vs=0`）——留着它没有意义，
 * 但为它判废整场代价过大。重建=重新 insert/open/建 muxer/起编码器（走既有 `nextSegment`），
 * 拿一套全新的 fd+编码器+muxer 再试一次：换行后的 fd/轨状态类问题能被这一下绕开。
 * **不是静默**：每次重建都记 [muxFailDiagnostic] 进失败码/账目、丢失照记；次数触顶仍失败 ⇒
 * 明确失败（保持 r18 那条可见失败通道不变）。
 */
internal fun muxRecoverDecision(
    fresh: Boolean,
    restarts: Int,
    maxRestarts: Int = ROTATE_STORE_ATTEMPTS
): Boolean = fresh && restarts < maxRestarts

/**
 * 换段腿里入库调用失败/超时后的**有界重试**判定（纯函数，全表单测）。
 *
 * 为什么值得重试：真机上这条腿失败的现场形状是"封旧段成功 → 建新段失败 → 整场录制当场结束
 * （报 ENGINE_ERROR:ROTATE）"，而死点浮动在 1~6 次换段之间——像是"上一段 33MB pending 的收尾
 * 还在 MediaProvider 里排队，insert/open 正好撞上"这种**瞬时忙**。`VideoStore.seal` 的 commit
 * 早就有"失败多半是 Provider 瞬时忙，立即重试一次"的同一先例，这里对齐它：一次失败就放弃
 * 等于把整场录制丢掉，代价远大于多等一拍。
 *
 * @param attempt 已经失败的次数（1 = 刚失败一次）
 * @param leftMs 本腿剩余预算
 * @return true = 还允许再试一次（次数未到上限，且剩余预算够再做一轮）
 */
internal fun rotateRetryDecision(
    attempt: Int,
    leftMs: Long,
    maxAttempts: Int = ROTATE_STORE_ATTEMPTS,
    floorMs: Long = ROTATE_RETRY_FLOOR_MS
): Boolean = attempt < maxAttempts && leftMs >= floorMs

/** 换段腿里入库调用的尝试次数上限（含首次）：与 `VideoStore.seal` 的 commit 双尝试同口径 */
internal const val ROTATE_STORE_ATTEMPTS = 2

/** 再试一次的剩余预算门槛（ms）：预算不够一轮有意义的重试时就不再试 */
internal const val ROTATE_RETRY_FLOOR_MS = 500L

/** 两次尝试之间的退让（ms）：给 MediaProvider 把上一段的收尾做完的时间窗（有界、计入腿预算） */
internal const val ROTATE_RETRY_BACKOFF_MS = 120L

/**
 * 从失败码里取回丢失毫秒（UI 文案要用 N 秒）。非本码/畸形返回 null——
 * 与 `LOW_STORAGE:<可用MB>` 同一套「码带载荷」口径（`recordResultText` 的既有先例）。
 */
internal fun rotateStallLostMs(code: String?): Long? {
    if (code == null || !code.startsWith(RecordError.ROTATE_STALL + ":")) return null
    return code.substringAfter(':').substringBefore(':').toLongOrNull()
}

/**
 * 丢失时长的秒级读数（用户提示用）：四舍五入到秒，**至少 1 秒**——
 * 报出"丢失约 0 秒"比不报更糟（用户会以为提示是假的）。
 */
internal fun rotateStallLostSeconds(lostMs: Long): Long =
    ((lostMs + 500L) / 1000L).coerceAtLeast(1L)

/**
 * 换段丢帧账（跨段累加）。泵线程与停止路径（控制线程）都可能记账 ⇒ 读写一律加锁
 * （记录点是几次换段量级，锁开销可忽略）。
 */
internal class RotateLossLedger {

    private val lock = Any()
    private var total = 0L
    private var worst = 0L
    private var events = 0

    /** 全部换段腿累计丢掉的时长（ms） */
    val totalMs: Long get() = synchronized(lock) { total }

    /** 单次最长的换段丢失（ms；0 = 从未丢过） */
    val worstMs: Long get() = synchronized(lock) { worst }

    /** 发生过丢失的换段次数（只数 >0 的，良性开销不计） */
    val count: Int get() = synchronized(lock) { events }

    /** 记账：<=0 视为良性开销（健康轮恒走这条），不计数、不累计 */
    fun add(lostMs: Long) {
        if (lostMs <= 0L) return
        synchronized(lock) {
            total += lostMs
            events++
            if (lostMs > worst) worst = lostMs
        }
    }

    fun clear() {
        synchronized(lock) {
            total = 0L
            worst = 0L
            events = 0
        }
    }
}

/** 有界调用的两种结果（超时**不是**失败值，调用方必须分开处理） */
internal sealed interface Bounded<out T> {
    data class Done<T>(val value: T) : Bounded<T>
    object TimedOut : Bounded<Nothing>
}

/**
 * 有界入库调用执行器：把一步会走 MediaProvider / FUSE 的同步调用放到一枚守护线程上，最多等
 * [timeoutMs]。超时即返回 [Bounded.TimedOut]，**不取消**那枚调用（binder 调用没有取消接口），
 * 由调用方决定怎么记账/怎么收口；晚到的那枚若是"建新 pending"，必须走 [OneShotHandoff] 回收。
 *
 * 为什么每调用一枚线程而不是一条常驻队列：常驻队列会在第一次卡住之后把后续每一步都排在同一枚
 * 卡住的线程后面（那正是"停摆后本会话再不复原"的形态）；每调用一枚线程则让后续步骤照常拿到机会，
 * 卡住的那枚留在后台自生自灭——它做的事要么是幂等的（close/commit/query），要么被交接闸管住。
 * 代价是每段最多漏一枚守护线程（阻塞在 binder 上，占一个线程栈），量级可接受。
 */
internal class BoundedStore(private val tag: String = "WotaStoreIo") {

    private val seq = AtomicLong()

    fun <T> run(timeoutMs: Long, block: () -> T): Bounded<T> {
        // 预算已被吃光：不许再发一次无预算的调用（那是把有界化做回无界）
        if (timeoutMs <= 0L) return Bounded.TimedOut
        var finished = false
        var value: T? = null
        var failure: Throwable? = null
        val t = Thread({
            try {
                value = block()
            } catch (e: Throwable) {
                failure = e
            } finally {
                finished = true
            }
        }, "$tag-${seq.incrementAndGet()}")
        t.isDaemon = true
        t.start()
        t.join(timeoutMs)
        // join 返回即 happens-before：线程真跑完时下面三枚读都是安全的；超时则一律按 TimedOut
        if (!finished) return Bounded.TimedOut
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return Bounded.Done(value as T)
    }
}

/**
 * 「一枚新 pending」的交接闸（泵线程 ↔ 入库线程）。
 *
 * 为什么需要：`Resolver.insert` **不可取消**——泵线程判超时之后，那枚入库线程还在跑，它可能
 * 在"判超时 → 回收 → 重试"之后才真正插进一行。建新 pending 又是这一腿里唯一**不可幂等**的调用，
 * 没人管就是幽灵条目（`IS_PENDING=1` 的 `.pending-<id>-…`）。所以交接必须做成**无竞态窗口**的
 * 四步，全部在同一把锁里：
 * 1. 入库线程把 pending 交给闸（[publish]）：此时若已判停摆、或闸里已有一枚（上一次尝试的死线程
 *    留下的），**就地回收**——本腿的闸只允许一枚在场；
 * 2. 泵线程判停摆时撤走交接权（[abandon]），并回收**已经交上来**的那一枚；
 * 3. 泵线程成功时取走 pending（[claim]）；
 * 4. 每次尝试开始前复位（[reset]）：上一轮留在闸里的那一枚没人要了，就地回收。
 * 无论两个线程谁先谁后、也无论重试几轮，每枚 pending 只可能被上交或被回收，二者必居其一
 * ⇒ 结构上无幽灵。
 *
 * @param discard 回收动作（由调用方包成"有界 + 不阻塞泵线程"的形态）；调用方应顺带记账
 *   （迟到落地的 insert 是要能看见的：`RotateFailLog` 的 `late=` 字段）
 */
internal class OneShotHandoff<T : Any>(private val discard: (T) -> Unit) {

    private val lock = Any()
    private var abandoned = false
    private var landed: T? = null

    /** 入库线程侧：@return true = 已上交（结果由 [claim] 取）；false = 已判停摆/已有在场/本就为空 */
    fun publish(v: T?): Boolean = synchronized(lock) {
        when {
            v == null -> false
            abandoned -> {
                discard(v)
                false
            }
            // 闸里已有一枚 = 上一次尝试的死线程仍在跑（重试场景）：本腿只认最新那一枚，
            // 旧的那枚就地回收（不回收就是幽灵，回收了它仍是一条合法的新 pending 行）
            landed != null -> {
                discard(v)
                false
            }
            else -> {
                landed = v
                true
            }
        }
    }

    /** 泵线程侧：撤走交接权（判停摆），返回已交上来、需要调用方回收的那一枚 */
    fun abandon(): T? = synchronized(lock) {
        abandoned = true
        val l = landed
        landed = null
        l
    }

    /** 泵线程侧：本腿成功，取走 pending */
    fun claim(): T? = synchronized(lock) {
        val l = landed
        landed = null
        l
    }

    /** 每次尝试/每次换段前复位：留在闸里的那一枚没人要了，**必须回收**（不许只是丢掉引用） */
    fun reset() = synchronized(lock) {
        abandoned = false
        landed?.let(discard)
        landed = null
    }
}

/**
 * 逐轨 PTS 单调化的"计划→行为"桥（2026-10-08 真机根因收敛）。
 *
 * 为什么必须走桥：`MediaMuxer.writeSampleData` 对**同一轨 pts 倒退**会走失败路径并抛
 * `IllegalStateException`——现场表现是**整场录制当场结束**（`rotate-fail.log` 里
 * `trk=A;started=1;field=same;st=0` 那一族都是它）。而换段期"暂存首样本补写"天生可能让
 * **较早**的那枚样本晚于**较新**的样本写出（结构性顺序风险，已用"补写上移到本拍开头"治本）。
 * 这层是兜底：等值/倒退一律推进到 `last+1`，把致命错误降级成微秒级修正。
 *
 * @param lastUs 本段该轨**已写出的**最后一枚 pts（<0 = 本段该轨还没写过 ⇒ 不作判定）
 * @param candidateUs 本枚候选 pts（已减去本段基准、已做过负值夹紧）
 */
internal data class PtsFix(
    /** 真正应写进 muxer 的 pts */
    val ptsUs: Long,
    /** 是否发生了推进（true ⇒ 候选值不单调，被挪到了 last+1） */
    val advanced: Boolean,
    /** 被抹平的倒退量（μs；`advanced=false` 时恒 0）——只用于账目/快照，不参与判定 */
    val backUs: Long
)

internal fun monotonicPts(lastUs: Long, candidateUs: Long): PtsFix =
    if (lastUs >= 0L && candidateUs <= lastUs) {
        PtsFix(lastUs + 1L, advanced = true, backUs = lastUs - candidateUs + 1L)
    } else {
        PtsFix(candidateUs, advanced = false, backUs = 0L)
    }
