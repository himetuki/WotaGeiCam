package com.wotagei.cam.player

import android.content.Context
import android.net.Uri
import com.wotagei.cam.diag.Diag as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 录制**完全停止后**的内录音轨自动校准（用户需求：蓝牙延迟要在录前+录后各纠一次）。
 *
 * 只在停止收尾末尾调度，**不等待**：解码两轨前 30s PCM + 互相关在 IO 线程跑，慢一点也不影响
 * 「已保存」提示与下一次起录。全部异常只记日志；调用方（[com.wotagei.cam.ui.CameraScreen] 的
 * stopInternal）连调度本身都包在 runCatching 里——纠偏失败绝不能丢成片或抛回停止路径。
 *
 * 双音轨判定：调用方传入的 Boolean 只是"本场请求了内录"；这里**逐段**用
 * [TrackSync.countAudioTracks] 复核实际轨数，单音轨段既不测也不写 `sync_done=true`。
 *
 * 分段策略：≤[MAX_EXACT_SEGMENTS] 段时逐段各跑一次 autoRun（每段有独立容器起点差，复制会带误差）；
 * 超过时退回"测首段 + 复制"，并在日志里明确标注近似。
 */
object PostStopSync {

    private const val TAG = "WotaPostSync"

    /** 逐段精确校准的段数上限：再多人均 30s 解码会让后台占用过久，退回首段近似 */
    internal const val MAX_EXACT_SEGMENTS = 4

    /** 独立于任何页面/Activity 的生命周期：停止收尾时页面可能正在退出 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化：两场录制接连停止时不并发解码同一批文件（也避免 prefs 复合读写交错） */
    private val mutex = Mutex()

    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** 正在自动校准的分段 uri 字符串集合；播放页据此在完成后重读偏移/来源 */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    /**
     * 调度一次停止后校准。立即返回；[segments] 的 Boolean = 该段"录制时请求了双音轨"，
     * 实际是否双轨由后台逐段复核。非双音轨请求/空列表直接忽略。
     */
    fun schedule(app: Context, segments: List<Pair<Uri, Boolean>>) {
        val requested = segments.filter { it.second }.map { it.first }
        if (requested.isEmpty()) return
        val appContext = app.applicationContext
        val keys = requested.map { it.toString() }.toSet()
        val launched = runCatching {
            scope.launch {
                _running.update { it + keys }
                try {
                    mutex.withLock {
                        // 实际双轨复核：调用方的 captureAudio 只是请求，单轨段（内录启动失败/
                        // 中途降级/仅环境音）不许被标 sync_done
                        val actualDual = requested.filter { uri ->
                            runCatching { TrackSync.countAudioTracks(appContext, uri) >= 2 }
                                .getOrDefault(false)
                        }
                        if (actualDual.isEmpty()) {
                            Log.i(TAG, "后校准跳过：请求分段里没有实际双音轨 first=${requested.first()}")
                            return@withLock
                        }
                        if (actualDual.size <= MAX_EXACT_SEGMENTS) {
                            var done = 0
                            for (uri in actualDual) {
                                // 逐段兜底：某段解码/读容器抛异常只跳过该段，不连坐后面的段
                                val r = try {
                                    TrackSync.autoRun(appContext, uri)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    Log.w(
                                        TAG,
                                        "后校准单段异常（继续下一段）uri=$uri " +
                                            "${e.javaClass.simpleName} ${e.message}"
                                    )
                                    continue
                                }
                                if (r.ok) done++ else {
                                    Log.i(TAG, "后校准未写盘 uri=$uri reason=${r.reasonRes ?: "auto-decision"}")
                                }
                            }
                            Log.i(TAG, "后校准逐段完成 成功=$done/${actualDual.size}")
                        } else {
                            // 段数过多：每段各解 30s 代价过高，退回首段测量+复制并明确标注近似
                            val first = actualDual.first()
                            val r = TrackSync.autoRun(appContext, first)
                            if (r.ok) {
                                for (uri in actualDual.drop(1)) {
                                    TrackSync.persistAuto(appContext, uri, r.offsetMs, r.confidence)
                                }
                                Log.i(
                                    TAG,
                                    "后校准首段测量+复制（近似：段数=${actualDual.size}>$MAX_EXACT_SEGMENTS）" +
                                        " offset=${r.offsetMs}ms conf=${r.confidence}"
                                )
                            } else {
                                Log.i(TAG, "后校准未写盘 first=$first reason=${r.reasonRes ?: "auto-decision"}")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 绝不抛回调用方：只留一条日志，成片与下次录制都不受影响
                    Log.e(TAG, "后校准异常（已忽略）：${e.javaClass.simpleName} ${e.message}")
                } finally {
                    _running.update { it - keys }
                }
            }
        }
        launched.onFailure {
            Log.e(TAG, "后校准调度失败（已忽略）：${it.javaClass.simpleName} ${it.message}")
        }
    }
}
