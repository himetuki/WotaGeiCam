package com.wotagei.cam.player

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.wotagei.cam.diag.Diag as Log
import com.wotagei.cam.media.MediaRepo
import com.wotagei.cam.media.VideoClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 导出任务的类别：剪辑两档（无损/后渲染）共用一个闸门，选轨导出独立一个 */
enum class ExportKind { CLIP, TRACK }

/**
 * 导出界面的可观察状态。**进度与"在跑"住在宿主里**（页面只是展示）：
 * 页面被销毁重建时，正在跑的导出与它的进度都不随组合消失，回来还能看到进度条。
 */
data class ExportUiState(
    val clipRunning: Boolean = false,
    val clipProgress: Float = 0f,
    val trackRunning: Boolean = false,
    val trackProgress: Float = 0f,
    /** 最近一次结束的结果（页面映射成横幅文案后调 [ExportHost.clearResult]）；null = 无待展示 */
    val result: ClipResult? = null,
    val resultKind: ExportKind = ExportKind.CLIP
)

/**
 * 剪辑导出（无损重封 / 后渲染重编码）与选轨导出的**任务宿主**（2026-10-30 全面审查 🟡-1）。
 *
 * # 为什么不能继续挂在页面组合作用域上
 * 这两条导出此前用 `rememberCoroutineScope()` 起跑：导出是用户主动发起的**长任务**
 * （后渲染要整段重编码，分钟级），而组合作用域随播放页离开立即取消——快速返回、
 * 或导航竞态把组合拆掉时，导出被**静默丢弃**，连"已取消"都不提示（取消本身是安全的：
 * [SceneExportRunner] 内部 `ensureActive()` + 未封轨删 pending 行，不会留脏数据；
 * 丢的是用户的时间与预期）。这与相机门面上收 Activity 作用域（r41）是同一条原则：
 * **任务寿命不随页面组合**。
 *
 * # 归属与闸门
 * - Activity 作用域（ViewModel）：页面进出、recreate 都拿回同一个宿主与同一条跑着的任务；
 * - 剪辑档一个闸门、选轨档一个闸门（与旧实现的两枚独立 Job 一致，互不阻塞）；
 * - 结果留在 [ExportUiState.result] 里直到页面消费——不在场时完成的导出，回到播放页
 *   照样弹一次横幅（成片同时已进相册，[MediaRepo.invalidate] 由宿主自己发）。
 *
 * # 线程
 * 起跑/落账在 `Dispatchers.Main.immediate`（与页面同一线程语义），重活由导出器内部
 * `withContext(Dispatchers.IO)` 承担；进度回调来自 IO 线程，一律走 [MutableStateFlow.update]
 * 的原子读改写，不会丢更新。
 */
class ExportHost(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "WotaExport"

        /**
         * 取宿主：**传 Activity 的 ViewModelStoreOwner**（页面所在导航条目的 owner 属于
         * 错误归属——那正是本类要离开的坑）。
         */
        fun of(owner: ViewModelStoreOwner): ExportHost =
            ViewModelProvider(owner)[ExportHost::class.java]
    }

    private val ctx: Context = app.applicationContext
    private val repo: MediaRepo = MediaRepo.get(ctx)
    private val bus = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 导出器本身无状态、只在真正起跑时才构造（后渲染那套 GL/编码资源不预览时零占用）
    private val clipExporter by lazy { ClipExporter(ctx) }
    private val sceneRunner by lazy { SceneExportRunner(ctx) }
    private val trackExporter by lazy { TrackExporter(ctx) }

    private var clipJob: Job? = null
    private var trackJob: Job? = null

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state

    /**
     * 起跑剪辑导出。入参（区间/选轨/偏移/效果）由调用方在**这一刻**定版快照，
     * 导出期间用户再动 A/B、换轨、改效果都不影响这一轮（既有口径，原样保留）。
     *
     * @return false = 已有一轮剪辑导出在跑（重入防护；页面按钮态之外的第二道闸）
     */
    fun startClip(
        clip: VideoClip,
        aMs: Long,
        bMs: Long,
        track: SelectedTrack,
        offsetMs: Long,
        postRender: Boolean,
        effects: SceneExportEffects
    ): Boolean {
        if (clipJob != null) return false
        _state.update { it.copy(clipRunning = true, clipProgress = 0f, result = null) }
        clipJob = bus.launch {
            var out: ClipResult? = null
            try {
                out = if (postRender) {
                    // 后渲染：重编码 + Glow/降噪（曲线刻意不做，录制已烘进成片）
                    when (val r = sceneRunner.export(clip, aMs, bMs, track, offsetMs, effects) { p ->
                        _state.update { it.copy(clipProgress = p) }
                    }) {
                        is SceneExportRunner.Done -> ClipResult.Done(r.id, r.name)
                        is SceneExportRunner.Failed -> ClipResult.Fail(
                            when (r.reason) {
                                SceneExportRunner.Reason.RANGE -> ClipError.RANGE
                                // 只有"已封轨、只是没清 IS_PENDING"才算 commit 保留口径
                                SceneExportRunner.Reason.COMMIT -> ClipError.COMMIT
                                else -> ClipError.MUX
                            }
                        )
                    }
                } else {
                    clipExporter.export(clip, aMs, bMs, track, offsetMs) { p ->
                        _state.update { it.copy(clipProgress = p) }
                    }
                }
                if (out is ClipResult.Done) repo.invalidate()
            } catch (e: CancellationException) {
                // 用户主动取消（或宿主销毁）：不出横幅，未封轨的 pending 行由导出器自己删
                Log.i(TAG, "剪辑导出已取消（$postRender）")
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "剪辑导出异常", t)
                out = ClipResult.Fail(ClipError.MUX)
            } finally {
                _state.update {
                    it.copy(
                        clipRunning = false,
                        clipProgress = 0f,
                        // 取消时 out 为 null：保留未读的上一条结果，别把用户还没看到的横幅冲掉
                        result = out ?: it.result,
                        resultKind = ExportKind.CLIP
                    )
                }
                clipJob = null
            }
        }
        return true
    }

    /** 起跑选轨导出；入参同样在起跑那一刻定版。@return false = 已有一轮在跑 */
    fun startTrack(clip: VideoClip, track: SelectedTrack, offsetMs: Long): Boolean {
        if (trackJob != null) return false
        _state.update { it.copy(trackRunning = true, trackProgress = 0f, result = null) }
        trackJob = bus.launch {
            var out: ClipResult? = null
            try {
                out = trackExporter.export(clip, track, offsetMs) { p ->
                    _state.update { it.copy(trackProgress = p) }
                }
                if (out is ClipResult.Done) repo.invalidate()
            } catch (e: CancellationException) {
                Log.i(TAG, "选轨导出已取消")
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "选轨导出异常", t)
                out = ClipResult.Fail(ClipError.MUX)
            } finally {
                _state.update {
                    it.copy(
                        trackRunning = false,
                        trackProgress = 0f,
                        result = out ?: it.result,
                        resultKind = ExportKind.TRACK
                    )
                }
                trackJob = null
            }
        }
        return true
    }

    fun cancelClip() {
        clipJob?.cancel()
    }

    fun cancelTrack() {
        trackJob?.cancel()
    }

    /** 页面把结果映射成横幅后调用（StateFlow 相等去重，不清就收不到下一次同值结果） */
    fun clearResult() {
        _state.update { it.copy(result = null) }
    }

    override fun onCleared() {
        bus.cancel()
    }
}
