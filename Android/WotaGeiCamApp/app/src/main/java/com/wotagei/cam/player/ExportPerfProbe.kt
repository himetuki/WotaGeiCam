package com.wotagei.cam.player

import android.os.Debug
import android.os.SystemClock
import com.wotagei.cam.BuildConfig
import com.wotagei.cam.diag.Diag as Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Y30 真机取证探针（仅 `PERF_PROBE` 变体，release/debug 恒关）。
 *
 * 心跳式读数：第一次写出样本后起一条守护线程，每 2 秒打一条**累计**读数，连续
 * [IDLE_STOP_MS] 无新样本就退出。之所以用心跳而不是"每窗口一条汇总"：
 * - 真机 logcat 被相机 HAL 以每秒上千行灌满，单条稀疏日志 1~4 秒就被冲出环形缓冲，
 *   `adb logcat -t N` 抓不到；2 秒心跳保证任何时刻读都能命中；
 * - 导出结束后心跳继续 [IDLE_STOP_MS]，`muxer.stop()` 的等待值与停后 native heap 仍可读。
 *
 * 读数含义（判据见审查卡 Y30）：
 * - `samples/avgWriteMs/maxWriteMs`：`muxer.writeSampleData` 的阻塞量（车道等 muxer 的直接证据）；
 * - `stopWaitMs`：`muxer.stop()` 从进入到返回的墙钟等待（收尾把在途样本刷完的代价）；
 * - `nativeHeap`：`Debug.getNativeHeapAllocatedSize`（MCP 无 `dumpsys meminfo` 通道，
 *   这是应用内能拿到的 native 曲线；判据看单调上升不回落，而不是绝对 PSS）。
 *
 * 只加计时与日志，不改写出顺序、窗口或错误路径。
 */
internal object ExportPerfProbe {
    const val TAG = "ExportPerf"

    @Volatile
    var enabled: Boolean = BuildConfig.PERF_PROBE

    private const val HEARTBEAT_MS = 2_000L

    /** 最后一次样本之后还打多久心跳（够一次 `logcat -t 5000` 的抓取窗口，约 4 秒） */
    private const val IDLE_STOP_MS = 60_000L

    private val samples = AtomicLong()
    private val totalWriteNs = AtomicLong()
    private val maxWriteNs = AtomicLong()
    private val lastSampleMs = AtomicLong(0L)
    private val stopStartMs = AtomicLong(0L)
    private val stopWaitMs = AtomicLong(-1L)
    private val heartbeatOn = AtomicBoolean(false)

    fun onWrite(ns: Long) {
        if (!enabled) return
        samples.incrementAndGet()
        totalWriteNs.addAndGet(ns)
        bumpMax(maxWriteNs, ns)
        lastSampleMs.set(SystemClock.elapsedRealtime())
        ensureHeartbeat()
    }

    fun onStopStart() {
        if (enabled) stopStartMs.set(SystemClock.elapsedRealtime())
    }

    fun onStopEnd() {
        if (!enabled) return
        val t0 = stopStartMs.get()
        // 没有起点就写 -1，明确"没测到"，不冒充 0
        stopWaitMs.set(if (t0 > 0L) SystemClock.elapsedRealtime() - t0 else -1L)
        lastSampleMs.set(SystemClock.elapsedRealtime())
        ensureHeartbeat()
    }

    private fun ensureHeartbeat() {
        if (!heartbeatOn.compareAndSet(false, true)) return
        val t = Thread({
            try {
                while (enabled) {
                    Thread.sleep(HEARTBEAT_MS)
                    val last = lastSampleMs.get()
                    if (last != 0L && SystemClock.elapsedRealtime() - last > IDLE_STOP_MS) break
                    val n = samples.get()
                    val total = totalWriteNs.get()
                    val max = maxWriteNs.get()
                    val rt = Runtime.getRuntime()
                    Log.i(
                        TAG,
                        "samples=$n avgWriteMs=${ms(total, n)} maxWriteMs=${ms(max, 1L)} " +
                            "stopWaitMs=${stopWaitMs.get()} " +
                            "nativeHeap=${Debug.getNativeHeapAllocatedSize()} " +
                            "javaHeap=${rt.totalMemory() - rt.freeMemory()}"
                    )
                }
            } catch (_: InterruptedException) {
                // 退出探针不算异常
            } finally {
                heartbeatOn.set(false)
            }
        }, "ExportPerfProbe")
        t.isDaemon = true
        t.start()
    }

    private fun bumpMax(target: AtomicLong, ns: Long) {
        while (true) {
            val cur = target.get()
            if (ns <= cur || target.compareAndSet(cur, ns)) return
        }
    }

    private fun ms(ns: Long, n: Long): String =
        if (n <= 0L) "n/a" else String.format(Locale.US, "%.2f", ns.toDouble() / n / 1_000_000.0)
}
