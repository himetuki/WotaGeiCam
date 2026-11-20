package com.wotagei.cam.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.wotagei.cam.diag.Diag as Log
import com.wotagei.cam.record.mediaIdOf
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 已到达的**公开终态**（Done / COMMIT 保留）。
 *
 * 为什么需要这枚簿记：提交类 Binder 调用（清 IS_PENDING）放行前 caller 可能已经取消；
 * `withContext(Dispatchers.IO)` 在恢复 caller 时若发现协程已取消，会把块的返回值换成取消异常
 * ⇒ "成片已经写进相册"这一事实被吞掉，用户只看到取消。终态一旦发生就不能撤回，所以由
 * owner 侧先记账，[RemuxWait] / [SceneExportRunner] 在 caller 已离开时按任务身份回填。
 */
internal class ExportTerminal<T : Any> {
    private val value = AtomicReference<T?>(null)

    /** 只记第一次：终态是单调的，后来的值不许改写已发生的结局。 */
    fun reached(result: T) {
        value.compareAndSet(null, result)
    }

    fun peek(): T? = value.get()
}

/**
 * 无损剪辑/选轨导出的**资源 owner**：取消方只标记放弃并取消 worker Job，绝不触碰 owner 持有的
 * URI/FD；同步 Binder/native 返回后仍由这个 owner 登记资源、验票并收口。
 *
 * 与 [SceneExportRunner] 的 ExportOwner 同一套纪律（U05 定版）：
 * - 单次 Provider 阻塞**不可中断**（Binder 同步调用不响应 Thread.interrupt）；取消的语义是
 *   "等待方立刻返回，worker 在该调用返回后的下一个检查点退出并回收迟到资源"，不是"打断 Binder"；
 * - 每次公开提交（清 IS_PENDING）前重新领许可：放弃已赢时不得再发起新的 Binder 调用；
 * - 封轨（muxer.stop 已过）之后不许删行：删 MediaStore 行会连带删掉成片文件。
 */
internal class RemuxOwner(private val tag: String) {
    val workerJob = SupervisorJob()
    private val abandoned = AtomicBoolean(false)
    private val lock = Any()
    private var pendingUri: Uri? = null
    private var pendingClaimed = false
    private var sealed = false

    fun abandon() {
        abandoned.set(true)
        workerJob.cancel()
    }

    fun isAbandoned(): Boolean = abandoned.get()

    /** 每次公开提交前都必须重新领取许可；放弃已赢时不得再发起新的 Binder 调用。 */
    fun commitPermitted(): Boolean = synchronized(lock) { !abandoned.get() }

    /** 登记必须与放弃在同一把锁下裁决；返回 false 表示调用已放弃，调用方仍须回收。 */
    fun registerPending(uri: Uri): Boolean = synchronized(lock) {
        pendingUri = uri
        !abandoned.get()
    }

    fun markSealed() = synchronized(lock) {
        sealed = true
    }

    fun isSealed(): Boolean = synchronized(lock) { sealed }

    /** 同一 owner 至多领取一次 pending 删除权；外部 Binder 调用不在锁内执行。 */
    fun deletePending(ctx: Context) {
        val uri = synchronized(lock) {
            if (sealed || pendingClaimed) null
            else pendingUri?.also { pendingClaimed = true }
        } ?: return
        deletePendingRowVerified(ctx, uri, tag)
    }
}

/**
 * 失败回收：删 pending 行并**核对受影响行数**。
 *
 * `delete` 不抛异常不代表删掉了（URI 已失效/Provider 局部失败都可能 0 行）。旧实现只
 * `runCatching { delete }` 不看返回值，删失败会留一条半成品 pending 行而日志里毫无痕迹。
 * 这里 0 行/抛异常重试一次（Provider 瞬时忙多半秒级恢复），两次都没生效就留痕——行还在盘上，
 * 冷启 StalePendingSweep.rescuePendingRows 按封轨完成标记处置。
 * 标识只写 MediaStore 数字 id（B06：日志不落片名/完整 URI）。
 */
internal fun deletePendingRowVerified(ctx: Context, uri: Uri, tag: String): Boolean {
    val id = mediaIdOf(uri)
    repeat(2) { attempt ->
        val rows = runCatching { ctx.contentResolver.delete(uri, null, null) }.getOrElse { e ->
            // Provider 抛异常与"删除返回 0 行"是两种失败语义：异常这条已留痕，
            // 同一 attempt 不再重复报"未生效"（否则一次失败刷两条日志）。
            Log.w(tag, "删 pending 抛异常 id=$id attempt=${attempt + 1}: ${e.message}")
            null
        }
        if (rows == null) return@repeat
        if (rows > 0) return true
        Log.w(tag, "删 pending 未生效 id=$id rows=$rows attempt=${attempt + 1}")
    }
    Log.w(tag, "删 pending 两次未生效 id=$id：行保留待冷启救援（按封轨标记处置）")
    return false
}

/**
 * 两类重封导出（[ClipExporter] / [TrackExporter]）的**独立等待侧**。
 *
 * 缺陷（Y31）：Provider 调用（插入/开 FD/清 IS_PENDING/删行）在 `withContext(IO)` 里原生阻塞；
 * 一旦某次调用长期不返回，该类别闸门就一直停在运行态——取消只改了协程标记，等待方照样挂死。
 *
 * 修法与 Scene 导出同构：caller 的等待由本对象单独计时（预算 = max(时长, 尾点)×4 下限 60s，再 +60s
 * 收尾余量），预算到点或取消都让 caller **立刻返回**（BUSY/取消），worker 线程继续负责迟到资源
 * 的登记与回收；闸门是单 owner 零等待队列的执行器，忙时立即拒绝（新任务得 BUSY，不排队堆积）。
 * 已领许可进入的提交若在取消后返回成功，终态经 [ExportTerminal] 回填——**取消不吞结局**。
 *
 * 边界（可恢复的等待 vs 不可回收的挂死）：本对象只保证"等待方不被扣住"。同步 Provider/native 调用
 * 本身不可打断，等待方离开后 owner 线程仍会停在那次调用上直到它返回；返回后的迟到资源由 owner
 * 登记并回收一次、不再公开提交。若某次调用**永不返回**，那个单 owner 闸门就永久 BUSY（不排队、
 * 不积压），这是平台/Provider 的责任边界：导出层不伪造低质量兜底，也不提前释放仍被平台持有的行。
 */
internal object RemuxWait {
    private const val WATCHDOG_POLL_MS = 25L
    private const val WATCHDOG_GRACE_MS = 1_000L

    /** 独立等待预算：正常导出（按源字节率读盘）远用不到；只兜"Provider/native 长期不返回"。 */
    fun budgetMs(durationMs: Long, endMs: Long = 0L): Long =
        (durationMs.coerceAtLeast(endMs) * 4L).coerceAtLeast(60_000L) + 60_000L

    /**
     * 在 [executor] 上以单 owner 方式跑 [body]；null 返回 = 闸门忙或等待预算到点（调用方报 BUSY）。
     * [body] 自己负责清理。caller 已离开（取消/预算到点）时，[terminal] 里记下的公开终态仍会经
     * [onLateOutcome] 回填一次；owner 收口却没有任何公开终态时，仍在等待的 caller 立即结算为 null。
     */
    suspend fun <T : Any> bounded(
        executor: ThreadPoolExecutor,
        budgetMs: Long,
        threadName: String,
        tag: String,
        terminal: ExportTerminal<T>,
        onLateOutcome: (T) -> Unit,
        body: suspend (RemuxOwner) -> T?
    ): T? = suspendCancellableCoroutine { continuation ->
        val owner = RemuxOwner(tag)
        val settled = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val finish: (T?) -> Boolean = { result ->
            if (settled.compareAndSet(false, true) && !cancelled.get() && continuation.isActive) {
                runCatching { continuation.resume(result) }
                true
            } else {
                false
            }
        }
        val deadlineAt = SystemClock.elapsedRealtime() + budgetMs
        val watchdog = Thread({
            // 先让 owner 真正起跑，再开始计时。
            try {
                Thread.sleep(WATCHDOG_GRACE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            while (!owner.isAbandoned()) {
                if (SystemClock.elapsedRealtime() > deadlineAt) {
                    owner.abandon()
                    // 预算到点：等待方立刻拿 BUSY；worker 收口后若已有终态，由 onLateOutcome 回填。
                    if (settled.compareAndSet(false, true) && continuation.isActive) {
                        runCatching { continuation.resume(null) }
                    }
                    return@Thread
                }
                try {
                    Thread.sleep(WATCHDOG_POLL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "${threadName}Watchdog").apply { isDaemon = true }
        watchdog.start()
        continuation.invokeOnCancellation {
            // 不 close/delete/join；worker 负责同步调用返回后的登记、验票和一次回收。
            cancelled.set(true)
            owner.abandon()
            watchdog.interrupt()
        }
        try {
            executor.execute {
                val result = try {
                    runBlocking(owner.workerJob) { body(owner) }
                } catch (_: CancellationException) {
                    // 封轨/提交成功后取消：块的返回值被取消边界吞掉，取已记账的终态。
                    terminal.peek()
                } catch (failure: Throwable) {
                    Log.w(tag, "重封导出 owner 异常：${failure.message}")
                    terminal.peek()
                } finally {
                    owner.workerJob.cancel()
                    watchdog.interrupt()
                }
                if (result != null) {
                    // finish 失败 = 等待方没有收到这个值（已取消或预算到点）。公开终态是"已经发生"
                    // 的事实（成片已提交入库 / 行已保留待救援），取消只表示等待方离开，不许把结局
                    // 一起吞掉：一律按任务身份回填一次（Y32），由 [ExportTasks.upsertResult] 去重。
                    if (!finish(result)) runCatching { onLateOutcome(result) }
                } else {
                    // owner 收口却没有任何公开终态（异常终止/块返回 null）：仍在等待的调用方必须
                    // 当场结算（BUSY 口径），不许吊到等待预算到点；已取消的等待方由 finish 判定不恢复。
                    finish(null)
                }
            }
        } catch (_: RejectedExecutionException) {
            // 闸门为单 owner 零等待队列：上一 owner 未收口时新任务直接 BUSY，绝不排队。
            owner.abandon()
            watchdog.interrupt()
            finish(null)
        }
    }
}