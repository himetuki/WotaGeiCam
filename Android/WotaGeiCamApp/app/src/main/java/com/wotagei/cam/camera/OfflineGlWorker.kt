package com.wotagei.cam.camera

import android.os.Handler
import android.os.HandlerThread
import com.wotagei.cam.diag.Diag as Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 离线 GL 命令与资源共用一条线程。等待超时只结束等待方，不能提前销毁在途命令的输入面。 */
internal class OfflineGlWorker(private val tag: String) {
    private val cleanup = CompletableFuture<FutureTask<Unit>>()
    @Volatile private var dispatchStopped = false
    val thread: Thread
        get() = owner
    private val owner = object : HandlerThread(tag) {
        override fun run() {
            try {
                super.run()
            } catch (e: RuntimeException) {
                Log.w(tag, "GL 消息分发异常：${e.javaClass.simpleName}")
            } finally {
                dispatchStopped = true
                // 即使 Looper 提前停止，也保留原线程的 EGL 归属，直到管线交回两侧 codec。
                cleanup.join().run()
            }
        }
    }.apply { start() }
    val handler = Handler(owner.looper)

    fun call(timeoutMs: Long, block: () -> Boolean): Boolean {
        if (dispatchStopped || cleanup.isDone) return false
        if (Thread.currentThread() === owner) return block()
        val task = FutureTask { !cleanup.isDone && block() }
        if (!handler.post(task)) return false
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(false)
            handler.removeCallbacks(task)
            Log.w(tag, "GL 操作超时 ${timeoutMs}ms")
            false
        } catch (e: InterruptedException) {
            task.cancel(false)
            handler.removeCallbacks(task)
            Thread.currentThread().interrupt()
            false
        } catch (e: ExecutionException) {
            Log.w(tag, "GL 操作失败：${e.cause?.javaClass?.simpleName}")
            false
        }
    }

    /** 三项释放从调用方移交给同一个 owner。join 到期后仍由该 owner 依序完成。 */
    fun close(waitMs: Long, stopDecoder: () -> Unit, releaseGl: () -> Unit, releaseEncoder: () -> Unit) {
        val finish = FutureTask {
            cleanupStep("decoder", stopDecoder)
            cleanupStep("EGL", releaseGl)
            cleanupStep("encoder", releaseEncoder)
        }
        if (cleanup.complete(finish)) owner.quitSafely()
        if (Thread.currentThread() === owner || waitMs <= 0L) return
        try {
            owner.join(waitMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun cleanupStep(stage: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(tag, "释放 $stage 失败：${it.javaClass.simpleName}") }
    }
}
