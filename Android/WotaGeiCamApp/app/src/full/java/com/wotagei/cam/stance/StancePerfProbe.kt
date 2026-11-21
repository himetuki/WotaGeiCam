package com.wotagei.cam.stance

import android.os.Debug
import android.os.SystemClock
import com.wotagei.cam.BuildConfig
import com.wotagei.cam.diag.Diag as Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * B15 真机取证探针（仅 `PERF_PROBE` 变体，release/debug 恒关）。
 *
 * 心跳式读数：第一次写阶段标记或第一帧进入转换/推理后起一条守护线程，每 2 秒打一条读数，
 * 连续 [IDLE_STOP_MS] 无帧也无标记才退出。
 * - 只统计真正进入转换/推理的帧：节流与停听早退不计入；
 * - 之所以不用"每 10 秒一条汇总"：真机 logcat 被相机 HAL 以每秒上千行灌满，稀疏日志
 *   1~4 秒就被冲出环形缓冲，`adb logcat -t N` 抓不到；2 秒心跳保证任何时刻读都能命中；
 * - 心跳**不依赖帧**：挂槽被门控挡下时也要能读到"为什么没帧"（`note=` 是最近三条阶段标记）；
 * - 读数含 `avgMs/maxMs`（单帧处理耗时）、ART GC 次数/耗时、native heap —— B15 的判据是
 *   「Image 立即关闭 + 复用 scratch 之后，GC 与 native heap 不随帧数单调上涨」。
 *
 * 不改变分析路径；每次心跳只拼一条字符串。
 */
internal object StancePerfProbe {
    const val TAG = "StancePerf"

    @Volatile
    var enabled: Boolean = BuildConfig.PERF_PROBE

    private const val HEARTBEAT_MS = 2_000L
    private const val IDLE_STOP_MS = 10 * 60_000L

    private val frames = AtomicLong()
    private val totalNs = AtomicLong()
    private val maxNs = AtomicLong()
    private val lastActivityMs = AtomicLong(0L)
    private val heartbeatOn = AtomicBoolean(false)

    /** 最近三条阶段标记（挂/摘槽为什么没发生），只做取证，不参与任何判定 */
    private val notes = ArrayDeque<String>()

    val notesText: String
        @Synchronized get() = notes.joinToString(" | ")

    /**
     * 阶段标记：写 logcat（真机 logcat 只有 ~4 秒窗口，靠心跳把它带出来），
     * 同时留给读数。只在 `PERF_PROBE` 变体生效。
     */
    fun note(msg: String) {
        if (!enabled) return
        synchronized(notes) {
            notes.addLast(msg)
            while (notes.size > 3) notes.removeFirst()
        }
        lastActivityMs.set(SystemClock.elapsedRealtime())
        Log.i(TAG, "note $msg")
        ensureHeartbeat()
    }

    fun onFrame(ns: Long) {
        if (!enabled) return
        frames.incrementAndGet()
        totalNs.addAndGet(ns)
        bumpMax(ns)
        lastActivityMs.set(SystemClock.elapsedRealtime())
        ensureHeartbeat()
    }

    private fun ensureHeartbeat() {
        if (!heartbeatOn.compareAndSet(false, true)) return
        val t = Thread({
            try {
                while (enabled) {
                    Thread.sleep(HEARTBEAT_MS)
                    val last = lastActivityMs.get()
                    val now = SystemClock.elapsedRealtime()
                    if (last != 0L && now - last > IDLE_STOP_MS) break
                    val n = frames.get()
                    val total = totalNs.get()
                    Log.i(
                        TAG,
                        "frames=$n avgMs=${ms(total, n)} maxMs=${ms(maxNs.get(), 1L)} " +
                            "gcCount=${stat("art.gc.gc-count")} gcTimeMs=${stat("art.gc.gc-time")} " +
                            "nativeHeap=${Debug.getNativeHeapAllocatedSize()} note=[$notesText]"
                    )
                }
            } catch (_: InterruptedException) {
                // 退出探针不算异常
            } finally {
                heartbeatOn.set(false)
            }
        }, "StancePerfProbe")
        t.isDaemon = true
        t.start()
    }

    private fun bumpMax(ns: Long) {
        while (true) {
            val cur = maxNs.get()
            if (ns <= cur || maxNs.compareAndSet(cur, ns)) return
        }
    }

    private fun ms(ns: Long, n: Long): String =
        if (n <= 0L) "n/a" else String.format(Locale.US, "%.2f", ns.toDouble() / n / 1_000_000.0)

    private fun stat(key: String): String =
        runCatching { Debug.getRuntimeStat(key) }.getOrNull() ?: "n/a"
}
