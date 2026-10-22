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
 * 收尾抽干窗口的**进度感知**桥测试与源码结构守卫（`CodecRecorder.drainWaitTick`）。
 *
 * 缺陷形态：收尾抽干原来只有一条绝对 deadline（起钟即定 1200ms），"编码器只是慢"与"编码器已死"
 * 同判——一条 drain timeout 会把已经写完 moov、可播的整段判废删掉。修法是**有界顺延**：
 * 窗口内观测到推进（任一轨 done 翻转 / dequeue 返回过 idx>=0 或 FORMAT_CHANGED / 有样本写进
 * muxer）就把超时时钟挪一个静默预算，推进一停仍按同一预算判超时；总时长另有硬上限
 * （推进也不救），**无任何推进时与原实现逐字一致**。
 *
 * 期望值一律**手写字面量**（不调被测实现反推）；三态各自有真实语义：起窗本拍不判超时
 * （起窗即判会把 0 长窗误判成超时）、顺延绝不越过硬上限、无推进严格大于判超时（与旧判同口径）。
 */
class DrainWaitGuardTest {

    private fun codec(): String = codeOnly(mainSourceText("record/CodecRecorder.kt"))

    private fun body(name: String): String = flatten(bodyOf(codec(), name))

    private fun tick(
        openedAtMs: Long,
        deadlineMs: Long,
        nowMs: Long,
        progressed: Boolean
    ): DrainTick = CodecRecorder.drainWaitTick(openedAtMs, deadlineMs, nowMs, progressed)

    // ------------------------------------------------------------------ 常量与可达性预算

    @Test
    fun `抽干两档预算取值与join可达性_字面量对账`() {
        assertEquals("静默预算保持 1200ms（无推进 ⇒ 与原行为一致）", 1_200L, CodecRecorder.DRAIN_LIMIT_MS)
        assertEquals("抽干硬上限 1900ms（与预算注释里的分解对账）", 1_900L, CodecRecorder.DRAIN_HARD_LIMIT_MS)
        assertEquals("join 预算 2500ms（stop 的 joinLoop 等待上限）", 2_500L, CodecRecorder.LOOP_JOIN_MS)
        assertTrue(
            "静默预算必须小于硬上限（否则首枚 deadline 就翻过上限，顺延语义自相矛盾）",
            CodecRecorder.DRAIN_LIMIT_MS < CodecRecorder.DRAIN_HARD_LIMIT_MS
        )
        // 【系统级可达性，非恒等式】stop() 的 joinLoop 只等 LOOP_JOIN_MS(2500)，超了走
        // finishWithoutTeardown（只记账不拆资源，用户看到的是失败）。抽干之后泵线程还要跑完
        // closeSegmentEngine（codec/muxer stop + moov 落盘）+ sealCurrent（关 fd + commit），
        // 预留 500ms（本机典型数十~数百 ms）。硬上限若吃到 join 线，"保下已录内容"就变成
        // "连收尾都交不出去"——顺延只在仍赶得上 join 的前提下才有意义。
        assertTrue(
            "预算分解不成立：硬上限 1900 + 预留封段收尾 500 必须 < join 预算 2500",
            CodecRecorder.DRAIN_HARD_LIMIT_MS + 500L < CodecRecorder.LOOP_JOIN_MS
        )
    }

    // ------------------------------------------------------------------ 三态桥

    @Test
    fun `起窗本拍只记起点不判超时`() {
        // 起窗即判会把 0 长窗误判成超时（首拍就 break 掉整段抽干）
        assertEquals(
            DrainTick(openedAtMs = 1_000L, deadlineMs = 2_200L, timedOut = false),
            tick(openedAtMs = 0L, deadlineMs = 0L, nowMs = 1_000L, progressed = false)
        )
        // 起窗那拍就算有推进也一样：窗口必须真的开出来（起点 + 首枚 deadline）
        assertEquals(
            DrainTick(openedAtMs = 1_000L, deadlineMs = 2_200L, timedOut = false),
            tick(openedAtMs = 0L, deadlineMs = 0L, nowMs = 1_000L, progressed = true)
        )
    }

    @Test
    fun `无推进按原静默预算超时_严格大于与旧判同口径`() {
        // 起窗于 5000 ⇒ deadline=6200：6200 恰好到线不算超时（旧判是 `now > deadline`）
        var t = tick(openedAtMs = 0L, deadlineMs = 0L, nowMs = 5_000L, progressed = false)
        assertEquals(DrainTick(5_000L, 6_200L, false), t)
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 6_200L, progressed = false)
        assertEquals("恰好到线不判超时", DrainTick(5_000L, 6_200L, false), t)
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 6_201L, progressed = false)
        assertEquals("过线 1ms 即超时（无推进路径与原实现逐字一致）", DrainTick(5_000L, 6_200L, true), t)
    }

    @Test
    fun `每拍推进顺延一个静默预算`() {
        // 起窗 1000（deadline=2200、硬上限=2900）：1500 那拍还在吐东西 ⇒ deadline 挪到 1500+1200
        var t = tick(openedAtMs = 0L, deadlineMs = 0L, nowMs = 1_000L, progressed = false)
        assertEquals(DrainTick(1_000L, 2_200L, false), t)
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 1_500L, progressed = true)
        assertEquals(DrainTick(1_000L, 2_700L, false), t)
        // 再推一拍：2200 那拍吐了东西 ⇒ deadline 挪到 2200+1200=3400，但被硬上限 2900 夹住
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 2_200L, progressed = true)
        assertEquals("顺延绝不越过硬上限（夹在 opened+1900）", DrainTick(1_000L, 2_900L, false), t)
    }

    @Test
    fun `推进停止后按顺延到的deadline超时`() {
        // 起窗 1000 ⇒ deadline 2200；1400 有推进 ⇒ deadline 顺延到 2600（未触 2900 硬上限）
        var t = tick(openedAtMs = 0L, deadlineMs = 0L, nowMs = 1_000L, progressed = false)
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 1_400L, progressed = true)
        assertEquals(DrainTick(1_000L, 2_600L, false), t)
        // 此后不再推进：2600 不超时、2601 超时（"推进停止 ⇒ idle 预算后仍超时"）
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 2_600L, progressed = false)
        assertEquals(DrainTick(1_000L, 2_600L, false), t)
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 2_601L, progressed = false)
        assertEquals(DrainTick(1_000L, 2_600L, true), t)
    }

    @Test
    fun `硬上限强制超时_推进也不救`() {
        // 起窗 10000 ⇒ deadline 11200、硬上限 11900；每拍都推进也只许顺延到 11900 那刻
        var t = tick(openedAtMs = 0L, deadlineMs = 0L, nowMs = 10_000L, progressed = true)
        assertEquals(DrainTick(10_000L, 11_200L, false), t)
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 11_100L, progressed = true)
        assertEquals(DrainTick(10_000L, 11_900L, false), t)
        // 到线前 1ms 仍在顺延（夹在硬上限上，不超时）
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 11_899L, progressed = true)
        assertEquals(DrainTick(10_000L, 11_900L, false), t)
        // 恰好到硬上限即强制超时——**带着推进也判**（不许把抽干拖过 join 预算）
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 11_900L, progressed = true)
        assertEquals(DrainTick(10_000L, 11_900L, true), t)
        t = tick(t.openedAtMs, t.deadlineMs, nowMs = 12_500L, progressed = false)
        assertEquals("过线后仍是超时态", DrainTick(10_000L, 11_900L, true), t)
    }

    // ------------------------------------------------------------------ 结构守卫（尺子自己能红）

    @Test
    fun `drainWaitTick 三态判据结构守卫_被拆后必须报红`() {
        val t = body("drainWaitTick")
        assertTrue(
            "硬上限判据必须是 nowMs >= hardAt（改成恒假 = 顺延无界，抽干可拖过 join 预算）",
            t.contains("nowMs >= hardAt")
        )
        assertTrue(
            "推进顺延必须被硬上限夹住（nowMs + DRAIN_LIMIT_MS, hardAt）",
            t.contains("nowMs + DRAIN_LIMIT_MS, hardAt")
        )
        assertTrue(
            "无推进判超时必须是 nowMs > deadlineMs（严格大于，与旧判 now > deadline 同口径）",
            t.contains("nowMs > deadlineMs")
        )
        // 突变自证 1：硬上限判据改成恒假（顺延无界 = 回到"能拖多久拖多久"）
        val m1 = flatten(bodyOf(codec().replace("nowMs >= hardAt", "false"), "drainWaitTick"))
        assertFalse("拆掉硬上限判据后守卫必须报红", m1.contains("nowMs >= hardAt"))
        // 突变自证 2：顺延不夹上限（deadline 可越过硬上限线）
        val m2 = flatten(
            bodyOf(codec().replace("minOf(nowMs + DRAIN_LIMIT_MS, hardAt)", "nowMs + DRAIN_LIMIT_MS"), "drainWaitTick")
        )
        assertFalse("拆掉顺延夹上限后守卫必须报红", m2.contains("nowMs + DRAIN_LIMIT_MS, hardAt"))
    }

    @Test
    fun `runSegment 必须接线抽干窗口推进与推进观测`() {
        val seg = body("runSegment")
        assertTrue(
            "抽干窗口必须接线 drainWaitTick（起窗/顺延/超时三态的唯一真源）",
            seg.contains("drainWaitTick(")
        )
        assertTrue(
            "三路 dequeue 必须做推进观测（idx>=0 或 FORMAT_CHANGED 即算这一拍有推进）",
            seg.contains("if (idx >= 0 || idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) drainProgress = true")
        )
        assertTrue("推进观测必须逐拍清零（否则一次推进会把后续所有超时都顺延掉）", seg.contains("drainProgress = false"))
        assertTrue(
            "超时判定必须读桥的判定结果（drainTimedOut），不许页内另算 deadline 比较",
            seg.contains("if (drainTimedOut)")
        )
        // 判定的发生点不变：起窗仍在 stopping||rotatePending 门内、muxer 未 start 仍按 NO_VIDEO_TRACK 放弃
        val gateAt = seg.indexOf("if (stopping || rotatePending)")
        val tickAt = seg.indexOf("drainWaitTick(", gateAt)
        assertTrue("drainWaitTick 必须在 stopping||rotatePending 门内（判定的发生点不许前移/后移）", gateAt >= 0 && tickAt > gateAt)
        // 突变自证：拆掉接线（窗口退回固定 deadline）
        val m = bodyOf(codec().replace("drainWaitTick(", "fixedDeadline("), "runSegment")
        assertFalse("拆掉 drainWaitTick 接线后守卫必须报红", m.contains("drainWaitTick("))
    }

    @Test
    fun `抽干超时后的错误码与汇总日志不许漂移`() {
        val seg = body("runSegment")
        // 超时后的错误码仍是 ENGINE_ERROR:DRAIN：错误码字面量在 codeOnly 下被遮蔽，
        // 只锁"超时分支里置错"这个结构位置（DRAIN 的字面量由既有 UI 映射与真机日志承担）
        val timeoutAt = seg.indexOf("if (drainTimedOut)")
        val errAt = seg.indexOf("engineError = engineError ?:", timeoutAt)
        assertTrue("超时分支必须置引擎错误码（ENGINE_ERROR:DRAIN 的位置不许挪）", errAt > timeoutAt)
        // 汇总日志：一条（顺延次数 + 总等待），且只在真起过窗时打。
        // 文案在字符串字面量里 ⇒ codeOnly 下被遮蔽，这一条读原文（同"双触名单 KDoc"的先例）
        assertTrue("汇总日志必须以起过窗为门（没起窗不许打）", seg.contains("if (drainOpenedAtMs > 0L) {"))
        val raw = mainSourceText("record/CodecRecorder.kt")
        assertTrue("抽干必须留一条汇总日志（顺延次数 + 总等待）", raw.contains("顺延 \$drainExtensions 次"))
    }
}
