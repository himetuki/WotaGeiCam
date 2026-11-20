package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复执行器的生命周期/异常路径守卫。
 *
 * 1. Y23：任务所有者迁出播放页 `remember`（执行器不再持有状态源，宿主负责收尾）；
 *    执行器入队失败必须归还所有权（旧实现先写 RUNNING 再 post，post 失败留下永久 RUNNING 幽灵任务）。
 * 2. `openAudioLanes` 只捕 IOException：非 IOException 时当前 extractor 与已建 lane 都只在
 *    局部变量里，finish() 的 audioLanes 还看不到它们 ⇒ 原生 extractor 泄漏。现在异常路径
 *    先全部 release 再把异常抛给会话收尾。
 */
class ArcRepairLifecycleGuardTest {

    private val src by lazy { codeOnly(mainSourceText("record/ArcRepairRunner.kt")) }

    @Test
    fun `任务收尾必须先归还所有权再交付终态_线程按需自退`() {
        val start = flatten(bodyOf(src, "start"))
        assertTrue("start 的排队取消/正常两条收尾都必须走 finishTask", start.contains("finishTask("))
        val finish = flatten(bodyOf(src, "finishTask"))
        val end = finish.indexOf("gate.end(token)")
        val deliver = finish.indexOf("onResult(out)")
        assertTrue("收尾必须先归还所有权", end >= 0)
        assertTrue("终态必须经 onResult 交付宿主", deliver >= 0)
        assertTrue("先归还所有权再交付（回调里起跑新一轮不会被自己挡回）", end < deliver)
        assertTrue(
            "任务收尾必须读 quitAfterTask 并 quitSafely（否则宿主销毁后线程永久驻留）",
            finish.contains("if (quitAfterTask) thread.quitSafely()")
        )
        val shutdown = flatten(bodyOf(src, "shutdownAfterTask"))
        assertTrue("必须先登记退线程意图", shutdown.contains("quitAfterTask = true"))
        assertTrue(
            "空闲时必须立即退（判据走所有权门，不许再维护第二枚 busy）",
            shutdown.contains("if (!gate.hasTask()) thread.quitSafely()")
        )
        // 不能把取消语义混进来：shutdownAfterTask 不许动任务取消门
        assertFalse("shutdownAfterTask 不许取消用户已发起的修复", shutdown.contains("gate.cancel("))
    }

    @Test
    fun `播放页不再持有任务_状态与终态归Activity宿主`() {
        val player = codeOnly(mainSourceText("player/PlayerScreen.kt"))
        assertFalse(
            "页面不得再自己构造执行器（remember + ArcRepairRunner = 退页即丢状态、重进另起一条并行）",
            player.contains("remember(app) { ArcRepairRunner(")
        )
        assertTrue("页面必须经 Activity 作用域宿主取任务", player.contains("ArcRepairHost.of("))
        val host = flatten(codeOnly(mainSourceText("player/ArcRepairHost.kt")))
        assertTrue("宿主收尾必须走不取消任务的路径（用户已发起的修复要写完）", host.contains("shutdownAfterTask()"))
        assertFalse("宿主收尾不得无差别取消（onCleared 不是用户取消）", host.contains("runner.shutdown()"))
    }

    @Test
    fun `执行器入队失败不得留下幽灵RUNNING`() {
        val start = flatten(bodyOf(src, "start"))
        assertTrue("start 必须拿到 handler.post 的受理结果", start.contains("val posted = handler.post"))
        assertTrue("线程已退时必须归还所有权", start.contains("gate.end(token)"))
        val guard = start.substringAfter("if (!posted)").substringBefore("return token")
        assertTrue("拒绝起跑必须返回 null（调用方据此回滚槽位并按忙提示）", guard.contains("return null"))
        assertFalse("执行器不得再持有 MutableStateFlow（状态已迁到宿主账本）", src.contains("MutableStateFlow"))
        assertFalse(
            "执行器不得再有第二枚 activeToken/busy 记账",
            src.contains("private var activeToken") || src.contains("private var busy")
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
