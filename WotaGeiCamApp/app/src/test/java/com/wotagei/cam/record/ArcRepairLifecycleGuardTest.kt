package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复执行器的生命周期/异常路径守卫（两条真实泄漏）。
 *
 * 1. 播放页在 `status == RUNNING` 时 onDispose 跳过 shutdown()：任务结束后没有任何 quitSafely
 *    调用 ⇒ 每次"运行中离开播放页"永久多一条 WotaArcRepair 线程。现在 onDispose 一律调
 *    `shutdownAfterTask()`（不取消任务，任务末尾自行退线程）。
 * 2. `openAudioLanes` 只捕 IOException：非 IOException 时当前 extractor 与已建 lane 都只在
 *    局部变量里，finish() 的 audioLanes 还看不到它们 ⇒ 原生 extractor 泄漏。现在异常路径
 *    先全部 release 再把异常抛给会话收尾。
 */
class ArcRepairLifecycleGuardTest {

    private val src by lazy { codeOnly(mainSourceText("record/ArcRepairRunner.kt")) }

    @Test
    fun `运行中离开页面_任务结束后线程必须自行退出`() {
        val start = flatten(bodyOf(src, "start"))
        assertTrue(
            "start 任务末尾必须读 quitAfterTask 并 quitSafely（否则运行中离开页面后线程永久驻留）",
            start.contains("if (quitAfterTask) thread.quitSafely()")
        )
        val shutdown = flatten(bodyOf(src, "shutdownAfterTask"))
        assertTrue("必须先登记退线程意图", shutdown.contains("quitAfterTask = true"))
        assertTrue("空闲时必须立即退", shutdown.contains("if (!busy) thread.quitSafely()"))
        // 不能把取消语义混进来：shutdownAfterTask 不许置 cancelRequested
        assertFalse("shutdownAfterTask 不许取消用户已发起的修复", shutdown.contains("cancelRequested = true"))
    }

    @Test
    fun `播放页离开时必须走不取消任务的收尾`() {
        val player = codeOnly(mainSourceText("player/PlayerScreen.kt"))
        val dispose = player.substringAfter("DisposableEffect(arcRunner)").substringBefore("val pos by")
        assertTrue("onDispose 必须调 shutdownAfterTask", dispose.contains("shutdownAfterTask()"))
        assertFalse(
            "不得再按 RUNNING 跳过收尾（跳过 = 线程永久驻留）",
            dispose.contains("status.value != ArcRepairStatus.RUNNING")
        )
    }

    @Test
    fun `音频lane非IO异常必须释放当前与已建extractor`() {
        val lanes = flatten(bodyOf(src, "openAudioLanes"))
        assertTrue("必须有 Throwable 兜底（IOException 之外的异常同样会离开函数）", lanes.contains("catch (e: Throwable)"))
        assertTrue("当前 extractor 必须 release", lanes.contains("runCatching { ex.release() }"))
        assertTrue(
            "已建 lane 必须全部 release（否则局部集合随异常丢失，finish 遍历不到）",
            lanes.contains("lanes.forEach { it.release() }")
        )
    }
}
