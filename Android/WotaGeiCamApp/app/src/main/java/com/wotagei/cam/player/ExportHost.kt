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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 剪辑（无损/后渲染）与选轨导出的 Activity 宿主。导出跨播放页进退及 Activity 重建存活，
 * 两类任务各持一个槽位。进度、可取消对象和待展示结果共用 [ExportTasks] 的任务身份。
 * 导出器独占编解码器、GL 和 Provider 资源，宿主只负责启动、撤销和交付结果。
 */
class ExportHost(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "WotaExport"

        fun of(owner: ViewModelStoreOwner): ExportHost =
            ViewModelProvider(owner)[ExportHost::class.java]
    }

    private val ctx: Context = app.applicationContext
    private val repo: MediaRepo = MediaRepo.get(ctx)
    private val bus = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = ExportTasks(bus)
    internal val state = tasks.state

    // 只在真正起跑时构造导出器；播放器浏览不占用 GL/编码资源。
    private val clipExporter by lazy { ClipExporter(ctx) }
    private val sceneRunner by lazy { SceneExportRunner(ctx) }
    private val trackExporter by lazy { TrackExporter(ctx) }

    /** 区间、选轨、偏移和效果由调用方在起跑时定版。false 表示本类导出已经在跑。 */
    fun startClip(
        clip: VideoClip,
        aMs: Long,
        bMs: Long,
        track: SelectedTrack,
        offsetMs: Long,
        postRender: Boolean,
        effects: SceneExportEffects
    ): Boolean = runExport(ExportDetails.Clip(aMs, bMs, clip.durationMs), clip) { onProgress, onLate ->
        if (postRender) {
            sceneOutcome(
                sceneRunner.export(clip, aMs, bMs, track, offsetMs, effects,
                    onLateOutcome = { onLate(sceneOutcome(it)) }) { onProgress(it) }
            )
        } else {
            clipExporter.export(clip, aMs, bMs, track, offsetMs,
                onLateOutcome = { onLate(it) }) { onProgress(it) }
        }
    }

    fun startTrack(
        clip: VideoClip,
        track: SelectedTrack,
        offsetMs: Long?,
        offsetSource: TrackSync.Source?
    ): Boolean {
        val shift = offsetMs ?: 0L
        return runExport(ExportDetails.Track(track, offsetMs, offsetSource), clip) { onProgress, onLate ->
            trackExporter.export(clip, track, shift, onLateOutcome = { onLate(it) }) { onProgress(it) }
        }
    }

    private fun runExport(
        details: ExportDetails,
        clip: VideoClip,
        run: suspend ((Float) -> Unit, (ClipResult) -> Unit) -> ClipResult
    ): Boolean = tasks.start(
        details,
        clip.uri.toString(),
        clip.name,
        // 迟到终态（预算到点/取消后才真正提交完）也要刷索引：相册里确实多了/保留了一条
        onLateOutcome = { late -> if (late is ClipResult.Done) repo.invalidate() }
    ) { onProgress, onLate ->
        try {
            run(onProgress, onLate).also { if (it is ClipResult.Done) repo.invalidate() }
        } catch (e: CancellationException) {
            Log.i(TAG, "导出已取消 type=${details.kind}")
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "导出异常 type=${details.kind}", t)
            ClipResult.Fail(ClipError.MUX)
        }
    }

    internal fun cancel(request: ExportRequest) = tasks.cancel(request)

    internal fun acknowledge(taskId: Long) = tasks.acknowledge(taskId)

    override fun onCleared() {
        bus.cancel()
    }

    /** 后渲染三态 → 剪辑链公开结果（正常返回与迟到回填共用同一映射，避免两套口径）。 */
    private fun sceneOutcome(r: SceneExportRunner.Result): ClipResult = when (r) {
        is SceneExportRunner.Done -> ClipResult.Done(r.id, r.name)
        is SceneExportRunner.Failed -> ClipResult.Fail(
            when (r.reason) {
                SceneExportRunner.Reason.RANGE -> ClipError.RANGE
                SceneExportRunner.Reason.COMMIT -> ClipError.COMMIT
                SceneExportRunner.Reason.BUSY -> ClipError.BUSY
                else -> ClipError.MUX
            }
        )
    }
}