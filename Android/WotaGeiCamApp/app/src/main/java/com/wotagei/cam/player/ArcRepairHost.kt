package com.wotagei.cam.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.record.ArcRepairRequest
import com.wotagei.cam.record.ArcRepairRunner
import com.wotagei.cam.record.ArcRepairTasks

/**
 * 光弧修复的 **Activity 宿主**（Y23）：任务所有者从播放页的 `remember` 迁到这里。
 *
 * 旧实现 `remember(app) { ArcRepairRunner(app) }` 把执行器、状态与结果一起钉在组合实例上：
 * 退页（NavHost 拆组合）或配置重建后，在跑的任务虽然还在写盘，但状态与终态无从展示，重进页面
 * 还会另起一条执行器与它并行。宿主跨退页/重建存活，页面只按 `(来源 URI, 代数)` 展示/取消/确认；
 * 起跑、取消、确认都经 [ArcRepairTasks] 的身份匹配，迟到的取消碰不到下一轮。
 *
 * 与 [ExportHost] 同一种 ViewModel 作用域，但任务形态不同（单槽、无协程、走独占 HandlerThread），
 * 所以单独成类；两者互不占用对方的槽位，也不互相取消。
 */
class ArcRepairHost(app: Application) : AndroidViewModel(app) {

    companion object {
        fun of(owner: ViewModelStoreOwner): ArcRepairHost =
            ViewModelProvider(owner)[ArcRepairHost::class.java]
    }

    // 修复管线独占一条 HandlerThread + 编解码器/GL；宿主只在真正起跑时才用得到它，
    // 但 runner 本身不占 GPU 资源（线程空闲时只是等消息），随宿主一起存活即可。
    private val runner = ArcRepairRunner(app.applicationContext)
    private val tasks = ArcRepairTasks(onCancel = runner::cancel)
    internal val state = tasks.state

    /**
     * 起跑一次修复。设置（目标帧率、并入门槛、同步偏移）由调用方在起跑时定版。
     * @return false = 已有任务在跑（本片或别片）或执行器拒收，调用方按忙提示
     */
    fun start(clip: VideoClip, useGpu: Boolean, dstFps: Int, capOffsetMs: Int = 0): Boolean =
        tasks.start(clip.uri.toString(), clip.name, useGpu, dstFps) { request ->
            runner.start(
                srcUri = clip.uri,
                useGpu = useGpu,
                dstFps = dstFps,
                // 并入门槛是参数总线发布的只读快照：在**起跑这一刻**读，改滑杆不影响在途任务
                deadband = ArcMergeDeadband.deadband,
                capOffsetMs = capOffsetMs,
                onProgress = { tasks.progress(request, it) },
                onResult = { tasks.finish(request, it) }
            )
        }

    /** 取消本片这一轮（按代数匹配；执行器只在准备期/主循环/封口前的检查点响应） */
    internal fun cancel(request: ArcRepairRequest) = tasks.cancel(request)

    /** 终态已展示：按代数（taskId）确认，别的代数的结果原样保留 */
    internal fun acknowledge(taskId: Long) = tasks.acknowledge(taskId)

    override fun onCleared() {
        // Activity 真正销毁：已发起的修复让它写完（结果照常落库/刷新索引），线程在任务收尾后自退；
        // 空闲则立即退。这里**不做取消**：onCleared 不是用户取消，取消只经页面那枚按钮。
        runner.shutdownAfterTask()
    }
}