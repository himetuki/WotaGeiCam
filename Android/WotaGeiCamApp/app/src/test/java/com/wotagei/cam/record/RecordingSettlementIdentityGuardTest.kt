package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R06/R07/Y21 收尾身份与所有权守卫（2026-11-20 补）。
 *
 * 集成提交已把「独立发布路 / 泵线程终态对账 / 会话级结算」接通，但仍有四条**身份/所有权**
 * 缺口会让对账退化成"有机制、没认领"：
 *
 * 1. **空首段（无产物失败）丢系列身份**：`RecordResult.fail` 的 `seriesId` 恒 0，而引擎
 *    绑定与迟到交付用的是 `store.seriesId`。停录线程用 0 登记、发布线程用真实系列号交付，
 *    两边永远不是同一个会话键 ⇒ 迟到 commit 的段进不了结果/系列（R06 路径二仍复现）。
 * 2. **健康检查回退 attempt**：自检重录到 attempt 1 后 HEALTHY 把 `attempt = 0`，停录登记
 *    与**已被判废那次**的迟到终态撞键；缓冲里的失败终态会把重录成功的会话洗成失败
 *    （练习漏交付）。attempt 是引擎身份，只能单调递增；重试预算要另用一枚计数。
 * 3. **fd 关闭与发布赛跑**：`sealCurrent` 先 `closeTargetBounded()`，预算超时就返回，而
 *    BoundedStore 线程仍在关 fd；泵线程已把同一段交给发布线程查 SIZE/清 IS_PENDING ⇒
 *    可能对未关闭的文件发布。关闭必须与发布在同一枚发布线程上按序完成。
 * 4. **停录登记使用结果里的系列号**：系列身份应来自会话（`sessionStore`），不取决于这次
 *    结果里有没有段；否则空首段再次退化成 0。
 */
class RecordingSettlementIdentityGuardTest {

    private val screen = codeOnly(mainSourceText("ui/CameraScreen.kt"))
    private val codec = codeOnly(mainSourceText("record/CodecRecorder.kt"))
    private val recorder = codeOnly(mainSourceText("record/Recorder.kt"))

    /** 生产桥函数：无段快照也必须保留会话归属（空首段不是"没有会话"） */
    @Test
    fun `空产物快照仍带系列归属`() {
        val out = sealedPartsResult(
            sealed = emptyList(),
            err = RecordError.TOO_SHORT,
            durationMs = 900L,
            seriesId = 42L,
            lostMs = 0L,
            rotateStalls = 0,
            muxDiag = "",
            ambientLost = false,
            latePending = 0L
        )
        assertEquals("空首段的失败结果仍必须告诉结算这是哪个系列", 42L, out.seriesId)
        assertTrue("无段仍是失败结果（不许伪造成功）", out.parts.isEmpty())
        assertFalse(out.ok)
    }

    /** 空段失败出口（正常收尾与停录超时快照）都不许把系列号丢成 0 */
    @Test
    fun `空段失败出口保留系列身份且停录登记用会话身份`() {
        assertTrue(
            "sealedPartsResult 的空段出口必须补回 seriesId",
            flatten(bodyOf(recorder, "sealedPartsResult")).contains(".copy( seriesId = seriesId,")
        )
        assertTrue(
            "CodecRecorder.finish 的空段出口必须补回 store.seriesId",
            flatten(bodyOf(codec, "finish")).contains(".copy( seriesId = store.seriesId,")
        )
        assertTrue(
            "停录登记必须以会话系列号为准（不依赖本次结果有没有段）",
            bodyOf(screen, "stopInternal").contains("seriesId = sessionSeriesId")
        )
    }

    /** 自检通过只清"重试预算"，不许回退引擎身份（迟到的旧尝试结论会串场） */
    @Test
    fun `健康检查通过不得回退尝试序号`() {
        val health = bodyOf(screen, "healthCheckLoop")
        assertFalse(
            "HEALTHY 分支把 attempt 归零 ⇒ 重录 attempt 1 的停录登记会与 attempt 0 的迟到终态撞键",
            health.contains("attempt = 0")
        )
        assertTrue(
            "自检重试预算必须用独立计数（attempt 保持单调）",
            health.contains("retryActionOf(healthRestartCount")
        )
        val abandon = bodyOf(screen, "abandonAndRestart")
        assertTrue("重录必须推进引擎身份", abandon.contains("attempt++"))
        assertTrue("重录必须消耗自检重试预算", abandon.contains("healthRestartCount++"))
        val start = bodyOf(screen, "start")
        assertTrue("新会话两枚计数都从 0 开始", start.contains("attempt = 0"))
        assertTrue(start.contains("healthRestartCount = 0"))
    }

    /** fd 关闭所有权交给发布线程：关闭完成之后才允许清 IS_PENDING */
    @Test
    fun `封口发布必须由发布线程在发布前关闭fd`() {
        val seal = bodyOf(codec, "sealCurrent")
        assertFalse(
            "sealCurrent 不许再用有界 close（超时返回后发布线程会与仍开着的 fd 赛跑）",
            seal.contains("closeTargetBounded()")
        )
        assertTrue("输出目标必须先从字段摘走，由发布线程独占", seal.contains("target = null"))
        assertTrue(
            "发布调用必须把 close 动作交给 SealedPublisher（同线程、先关后发布）",
            seal.contains("sealedPublisher.publish(") && seal.contains("closeStrict()")
        )
        assertFalse(
            "关闭不许再套 BoundedStore（超时后发布线程会与仍在关闭的 fd 赛跑）",
            seal.contains("storeIo.run(")
        )
    }
}