package com.wotagei.cam

import com.wotagei.cam.core.ArcConvertMode
import com.wotagei.cam.record.ArcDropLog
import com.wotagei.cam.record.ArcKeepRule
import com.wotagei.cam.record.ArcOp
import com.wotagei.cam.record.ArcRateProbe
import com.wotagei.cam.record.ArcRepairPlan
import com.wotagei.cam.record.ArcRepairFlow
import com.wotagei.cam.record.RecordProfile
import com.wotagei.cam.record.Recorders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 录制期转换（强制 24/25fps 无原生精确档）三件纯逻辑的 JVM 全覆盖：
 * 在线保留规则（无先知帧率）、被抽帧位次 sidecar、引擎强制选型。
 * GL/MediaCodec 的行为只能在真机验（同 ArcRepairPlanTest 的分工口径）。
 */
class ArcRecordConvertTest {

    // region 在线保留规则（ArcKeepRule）

    private val slot24 = 1_000_000_000L / 24          // 41.67ms
    private val frame30 = 1_000_000_000L / 30         // 33.33ms

    @Test
    fun `首帧必保留且锚定节奏`() {
        val rule = ArcKeepRule(slot24)
        assertTrue(rule.onFrame(123L))
        assertEquals(0, rule.takeDrops())
    }

    @Test
    fun `30fps到达按24保留_约每5丢1_丢弃账随保留帧取走`() {
        val rule = ArcKeepRule(slot24)
        var ts = 0L
        val kept = mutableListOf<Int>()
        val dropsPerKeep = mutableListOf<Int>()
        for (n in 0 until 90) {
            if (rule.onFrame(ts)) {
                kept += n
                dropsPerKeep += rule.takeDrops()
            }
            ts += frame30
        }
        // 90 帧 @30fps = 3s ⇒ 24fps 下应有 ≈72 帧输出（±1 的取整容差）
        assertEquals(72.0, kept.size.toDouble(), 1.0)
        // 账目守恒：所有保留帧取走的丢弃数之和 = 90 − 保留数
        assertEquals(90 - kept.size, dropsPerKeep.sum())
        // 30→24 的丢弃分布不扎堆：单次保留间隔最多丢 1
        assertTrue(dropsPerKeep.all { it <= 1 })
        // 抽帧点分布均匀：前 12 帧里恰好丢 2~3 次
        assertTrue(dropsPerKeep.take(12).sum() in 2..3)
    }

    @Test
    fun `长停滞追平_恢复时不连发`() {
        val rule = ArcKeepRule(slot24)
        assertTrue(rule.onFrame(0L))
        // 停 1 秒再来的帧：保留一次，应到时刻直接跳到"该帧时刻+间隔"，绝不把欠的帧连发补齐
        assertTrue(rule.onFrame(1_000_000_000L))
        assertFalse(rule.onFrame(1_000_000_000L + 1))
        assertTrue(rule.onFrame(1_000_000_000L + slot24))
    }

    @Test
    fun `reset清节奏与丢弃账`() {
        val rule = ArcKeepRule(slot24)
        rule.onFrame(0L)
        assertFalse(rule.onFrame(frame30))
        rule.reset()
        assertTrue("reset 后首帧重新锚定", rule.onFrame(0L))
        assertEquals(0, rule.takeDrops())
    }

    @Test
    fun `零间隔档退化为全保留_不炸`() {
        val rule = ArcKeepRule(0L)
        for (n in 0 until 10) assertTrue(rule.onFrame(n.toLong()))
    }

    // endregion

    // region 源帧率实测（ArcRateProbe）

    @Test
    fun `中位数折算_奇偶样本数都对`() {
        val e = List(90) { 33_333 }              // 全 30fps 帧距
        assertEquals(30, ArcRateProbe.measuredFps(e, 24))
        val odd = List(89) { 33_333 }
        assertEquals(30, ArcRateProbe.measuredFps(odd, 24))
    }

    @Test
    fun `重复样本与轨道断层被滤掉_不拖歪中位数`() {
        // 90 个有效 33.3ms 帧距 + 50 个 0（重复样本）+ 2 个 2s（断层）：中位数只认有效档
        val raw = buildList {
            repeat(50) { add(0) }
            repeat(45) { add(33_333) }
            add(2_000_000)
            repeat(45) { add(33_333) }
            add(2_000_000)
        }
        assertEquals(30, ArcRateProbe.measuredFps(raw, 24))
    }

    @Test
    fun `有效样本不足退回容器帧率`() {
        assertEquals(24, ArcRateProbe.measuredFps(List(7) { 33_333 }, 24))
        assertEquals(30, ArcRateProbe.measuredFps(emptyList(), 30))
    }

    @Test
    fun `混档节奏取中位档_60fps实测回60`() {
        assertEquals(60, ArcRateProbe.measuredFps(List(100) { 16_667 }, 24))
    }

    // endregion

    @Test
    fun `时间戳回退的陈旧帧按丢弃处理_等下次到点_不炸不死锁`() {
        // 传感器时间戳理论上单调，防御性钉住：回退帧一律视为"未到点"丢弃并计入丢弃账，
        // 且不影响后续正常帧到点照常保留
        val rule = ArcKeepRule(slot24)
        assertTrue(rule.onFrame(0L))
        assertFalse(rule.onFrame(-1_000L))          // 回退帧：丢弃
        assertEquals(1, rule.takeDrops())            // 计入丢弃账
        assertFalse(rule.onFrame(frame30 / 2))       // 仍未到应到时刻：丢弃
        assertTrue(rule.onFrame(slot24))             // 到点：照常保留
    }

    // endregion

    // region 被抽帧位次 sidecar（ArcDropLog）

    @Test
    fun `encode到decode往返无损`() {
        val log = ArcDropLog("mend", 24, listOf(0 to 1, 5 to 1, 11 to 2))
        val back = ArcDropLog.decode(log.encode())
        assertEquals(log, back)
    }

    @Test
    fun `空drops与零丢弃都合法`() {
        val log = ArcDropLog("drop", 25, emptyList())
        assertEquals(log, ArcDropLog.decode(log.encode()))
        assertEquals(ArcDropLog("drop", 25, listOf(0 to 0)), ArcDropLog.decode(ArcDropLog("drop", 25, listOf(0 to 0)).encode()))
    }

    @Test
    fun `坏串与缺字段返回null_不抛`() {
        assertNull(ArcDropLog.decode("不是json"))
        assertNull(ArcDropLog.decode("{\"mode\":\"mend\"}"))
        assertNull(ArcDropLog.decode("{\"mode\":\"mend\",\"dstFps\":24,\"drops\":[[x,1]]}"))
    }

    @Test
    fun `sidecar路径同名换扩展`() {
        assertEquals("/dcim/VID_a.drops.json", ArcDropLog.sidecarFor("/dcim/VID_a.mp4"))
        // 无扩展名的裸名：直接追加（本格式唯一真源是 encode 自己，不会出现这种输入）
        assertEquals("VID.drops.json", ArcDropLog.sidecarFor("VID"))
    }

    @Test
    fun `写读文件往返_读不存在的返回null`() {
        val f = File.createTempFile("arcside", ".mp4")
        try {
            assertTrue(ArcDropLog.writeTo(f.absolutePath, ArcDropLog("drop", 24, listOf(2 to 1))))
            assertEquals(ArcDropLog("drop", 24, listOf(2 to 1)), ArcDropLog.readFrom(f.absolutePath))
            val ghost = File.createTempFile("arcghost", ".mp4").apply { delete() }
            assertNull(ArcDropLog.readFrom(ghost.absolutePath))
        } finally {
            f.delete()
            ArcDropLog.deleteFor(f.absolutePath)
        }
    }

    @Test
    fun `deleteFor连带删除sidecar`() {
        val f = File.createTempFile("arcside2", ".mp4")
        ArcDropLog.writeTo(f.absolutePath, ArcDropLog("mend", 24, emptyList()))
        val sidecar = File(ArcDropLog.sidecarFor(f.absolutePath))
        assertTrue(sidecar.isFile)
        ArcDropLog.deleteFor(f.absolutePath)
        assertFalse(sidecar.exists())
        f.delete()
    }

    // endregion

    // region 引擎强制选型（Recorders.useCodecEngine）

    @Test
    fun `随机分类序列_不变量恒成立_种子固定可复现`() {
        // 种子固定 = 失败可复现（非加密用途）；录制现实的保留序列是任意的（丢帧/VFR 断层），
        // floor 计划序列只是其中一种——这里对任意 0/1 序列钉不变量
        val rnd = java.util.Random(42)
        repeat(200) { round ->
            val len = rnd.nextInt(60)
            val flow = ArcRepairFlow(dstFps = 24)
            var kept = 0
            var emitted = 0
            var lastPts = -1L
            for (s in 0 until len) {
                val keep = rnd.nextBoolean()
                for (op in flow.onClassification(keep)) {
                    when (op) {
                        is ArcOp.EmitPending -> {
                            assertTrue("PTS 严格递增", op.ptsUs > lastPts)
                            lastPts = op.ptsUs
                            emitted++
                        }
                        else -> Unit
                    }
                }
                if (keep) kept++
            }
            for (op in flow.onSourceEos()) {
                if (op is ArcOp.EmitPending) {
                    assertTrue("EOS 尾帧 PTS 仍递增", op.ptsUs > lastPts)
                    lastPts = op.ptsUs
                    emitted++
                }
            }
            assertEquals("发出数 = 保留数（round=$round）", kept, emitted)
        }
    }

    // endregion

    /** 最小可用 profile：只动 fps 与 arcConvert，其余给合法默认 */
    private fun profile(fps: Int, convert: ArcConvertMode?): RecordProfile = RecordProfile(
        width = 1920,
        height = 1080,
        fps = fps,
        captureRate = fps.toFloat(),
        bitrate = 0,
        codec = RecordProfile.CODEC_H264,
        sampleRate = 48_000,
        audioEnabled = false,
        orientationHint = 0,
        mirrored = false,
        useGpu = true,
        arcConvert = convert
    )

    @Test
    fun `转换激活强制走Codec引擎_即使24fps低于MediaRecorder上限`() {
        assertTrue(Recorders.useCodecEngine(profile(24, ArcConvertMode.MEND)))
        assertTrue(Recorders.useCodecEngine(profile(25, ArcConvertMode.DROP)))
    }

    @Test
    fun `不转换时维持既有口径_高速档恒Codec`() {
        assertFalse(Recorders.useCodecEngine(profile(24, null)))
        assertFalse(Recorders.useCodecEngine(profile(30, null)))
        assertTrue(Recorders.useCodecEngine(profile(120, null)))
    }

    // endregion

    // region 录制在线路与离线计划同构（桥的对称性）

    @Test
    fun `同一台状态机_在线分类入口按真实节奏产出目标帧率`() {
        // 30fps 均匀到达、目标 24：在线规则给出保留决策，喂给分类入口。
        // 300 帧 @30fps = 10s ⇒ 24fps 输出应 ≈240 帧（滞留帧随 EOS 发出，容差 ±1）。
        val rule = ArcKeepRule(slot24)
        val online = ArcRepairFlow(24)
        var ts = 0L
        var keptSeen = 0
        var emits = 0
        for (n in 0 until 300) {
            val keep = rule.onFrame(ts)
            ts += frame30
            emits += online.onClassification(keep).count { it is ArcOp.EmitPending }
            if (keep) keptSeen++
        }
        emits += online.onSourceEos().count { it is ArcOp.EmitPending }
        assertEquals(240.0, emits.toDouble(), 1.0)
        assertEquals(emits.toDouble(), keptSeen.toDouble(), 1.0)
    }

    // endregion

    /** 插帧计划类仍被引用防误删（v2 离线修复的保留集真源） */
    @Test
    fun `离线计划真源仍在`() {
        assertEquals(36, ArcRepairPlan.dstFrameCount(45, 30, 24))
        assertNotNull(ArcRepairFlow(ArcRepairPlan.of(45, 30, 24)))
    }
}
