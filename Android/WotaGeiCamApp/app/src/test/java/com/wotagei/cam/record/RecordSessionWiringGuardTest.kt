package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R06/R07 的**页面接线守卫**：引擎/所有者各自的行为测试（[SealedPublicationTest]、
 * [RecordSessionTest]）证明"机制对"，这里钉"电真的接通了"——机制再好，接线被删/被挪回
 * `result.collect` 就退回旧缺陷形态。
 *
 * 三条判据：
 * 1. 停录线程在交出结果那一刻**登记会话并发起首次结算**（不依赖页面 `collect`；退页/取消会
 *    让那条协程整段消失 ⇒ R07「退页后再收尾的成片仍进入系列归组」）；
 * 2. 起录时给引擎**绑定会话身份 + 交付通道**，并把迟到交付喂给 [RecordSession]；
 * 3. 迟到增量只刷新相册/回看字段，**不重复弹结果提示**（提示在停录那刻已经给过）。
 */
class RecordSessionWiringGuardTest {

    private val screen = codeOnly(mainSourceText("ui/CameraScreen.kt"))

    @Test
    fun `停录收尾必须登记会话并发起首次结算`() {
        val stop = bodyOf(screen, "stopInternal")
        assertTrue("停录收尾必须调 RecordSession.record（登记即结算，不靠页面 collect）", stop.contains("RecordSession.record("))
        assertTrue(
            "登记必须带生产结算后端（页面/仓库句柄在引擎侧不可得）",
            stop.contains("AndroidTakeSettlementBackend(dbProvider())")
        )
        assertTrue("登记必须带会话起始时刻（恰好一次的键之一）", stop.contains("startedAtMs = sessionStartedAtMs"))
        assertTrue(
            "登记必须带尝试序号（自检重录沿用同一会话键，不带就分不开被判废那次与重录成功那次）",
            stop.contains("attempt = attempt")
        )
        assertTrue("登记必须带本次结果", stop.contains("result = out"))
        val reg = stop.indexOf("RecordSession.record(")
        val stopped = stop.indexOf("recorder = null")
        assertTrue("锚点丢失：stopInternal 里没有 recorder = null", stopped > 0 && reg > 0)
        assertTrue("必须在引擎已交出结果之后登记（此时 out 才是最终快照）", reg > stopped)
    }

    @Test
    fun `引擎会话身份必须在起录时绑定且交付回到页面`() {
        val begin = bodyOf(screen, "beginSession")
        assertTrue(
            "只有带超时收尾形态的引擎实现 SessionDeliverySink（MR 路不实现，行为不变）",
            begin.contains("(rec as? SessionDeliverySink)")
        )
        assertTrue(
            "交付键必须与结算键同源（store.seriesId + 会话起始时刻 + 尝试序号）",
            begin.contains("bindSession(store.seriesId, sessionStartedAtMs, attempt)")
        )
        assertTrue("必须接上交付通道", begin.contains("onSessionDelivery = {"))
        assertTrue(
            "交付必须喂给会话所有者（合并去重 + 补结算），不许页面自己记账",
            begin.contains("RecordSession.onDelivery(delivery)")
        )
        assertTrue(
            "迟到增量必须进页面可观察的流（StateFlow 读走即清，同 retryNotice 口径）",
            begin.contains("lateResult.value = it")
        )
    }

    @Test
    fun `迟到增量只刷新相册且不重复弹提示`() {
        val at = screen.indexOf("runner.lateResult.collect")
        assertTrue("锚点丢失：页面没有迟到对账收集器", at > 0)
        val block = screen.substring(at, screen.indexOf("runner.clearLateResult()", at))
        assertTrue("迟到落地必须刷新相册", block.contains("repo.invalidate()"))
        assertTrue("回看首段字段要跟着迟到段走（首段可能迟到）", block.contains("delta.result.uri"))
        assertFalse(
            "迟到事件不许再弹一次结果提示（同一场录制已在停录那刻给过）",
            block.contains("showTip(")
        )
    }
}