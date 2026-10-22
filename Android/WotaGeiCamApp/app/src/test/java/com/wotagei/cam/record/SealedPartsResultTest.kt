package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已封段快照 → RecordResult」桥函数（[sealedPartsResult]）的纯 JVM 单测。
 *
 * 背景（2026-10-16 "录制结果如实化"）：joinLoop 超时时泵线程仍活着，收尾只能"只记账不拆资源"，
 * 但那份结果必须**如实**带出"此刻已封段"——旧行为返回无产物的失败结果，用户被告知全丢、
 * 相册里却躺着 N 段。桥函数是"快照 → 结果字段"的唯一落点，这里逐字段钉住：
 * 1. 空快照 = 旧失败形态**逐字不变**（[RecordResult.fail]：不带 seriesId/rotateStalls/
 *    latePending/ambientLost——旧构造点行为不许漂）；
 * 2. 非空快照如实映射 uri/path/bytes/分段清单/登记未成功数/能安全读的账，且**不清 error**
 *    （诊断码必须可查：UI 呈现计划把它翻成软附注，落的仍是同一条码）；
 * 3. 逐段样本/丢失账（泵线程单写者的普通容器，快照那一刻可能正在被写）**一律不带**（宁缺勿假）。
 *
 * 期望值一律**手写字面量**，不调被测实现反推（避免"用实现证明实现"）。
 */
class SealedPartsResultTest {

    private fun seg(i: Int, bytes: Long, committed: Boolean = true): VideoSegment = VideoSegment(
        uri = null,
        path = "/tmp/part$i.mp4",
        partIndex = i,
        bytes = bytes,
        startMs = i * 1_000L,
        durationMs = 1_000L,
        committed = committed
    )

    @Test
    fun `空快照退回旧失败形态_字段逐字不变`() {
        val out = sealedPartsResult(
            sealed = emptyList(),
            err = "${RecordError.ROTATE_STALL}:1234:SEAL",
            durationMs = 5_000L,
            seriesId = 7L,
            lostMs = 1_234L,
            rotateStalls = 2,
            muxDiag = "diag",
            ambientLost = true,
            latePending = 3L
        )
        assertNull("无已封段时不许带 uri", out.uri)
        assertNull("无已封段时不许带 path", out.path)
        assertEquals("无已封段字节恒 0", 0L, out.bytes)
        assertEquals("失败码原样保留", "${RecordError.ROTATE_STALL}:1234:SEAL", out.error)
        assertTrue("无产物必须仍判失败（ok=false）", !out.ok && out.parts.isEmpty())
        assertEquals("时长照旧", 5_000L, out.durationMs)
        assertEquals("丢失账照旧带上（用户唯一能看到的解释）", 1_234L, out.lostMs)
        assertEquals("现场快照照旧带上（排查通道）", "diag", out.muxDiag)
        // 旧 RecordResult.fail 形态：这四枚不在场（旧构造点行为逐字不变，谁顺手"补全"谁改行为）
        assertEquals("空快照不带 seriesId（旧 fail 形态）", 0L, out.seriesId)
        assertEquals("空快照不带 rotateStalls（旧 fail 形态）", 0, out.rotateStalls)
        assertEquals("空快照不带 latePending（旧 fail 形态）", 0L, out.latePending)
        assertFalse("空快照不带 ambientLost（旧 fail 形态）", out.ambientLost)
    }

    @Test
    fun `多段快照如实映射_段序与段标识原样保留`() {
        val sealed = listOf(seg(0, 100L), seg(1, 200L), seg(2, 300L, committed = false))
        val out = sealedPartsResult(
            sealed = sealed,
            err = "${RecordError.ENGINE_ERROR}:MUX_CLOSE",
            durationMs = 65_000L,
            seriesId = 42L,
            lostMs = 0L,
            rotateStalls = 0,
            muxDiag = "",
            ambientLost = false,
            latePending = 0L
        )
        assertNull("uri 取自首段（File sink 路为 null）", out.uri)
        assertEquals("path 取自首段", "/tmp/part0.mp4", out.path)
        assertEquals("bytes = 各段之和", 600L, out.bytes)
        assertEquals("分段清单原样进结果", sealed, out.parts)
        assertEquals("段标识（partIndex）逐一保留", listOf(0, 1, 2), out.parts.map { it.partIndex })
        assertEquals("登记未成功数按 committed=false 计数", 1, out.uncommitted)
        assertEquals("诊断码保留（可查：日志/落盘账目/结果字段）", "${RecordError.ENGINE_ERROR}:MUX_CLOSE", out.error)
        assertTrue("有产物但带错误码 = 非干净成功（ok=false，UI 呈现计划按「已保存 N 段」）", !out.ok)
        assertEquals("时长照旧（引擎结算值）", 65_000L, out.durationMs)
        // 宁缺勿假：逐段样本/丢失账是泵线程单写者的普通容器，快照那一刻可能正在被写 ⇒ 一律不带
        assertEquals("逐段样本账不许从泵线程单写者容器里读", emptyList<Int>(), out.segVideoSamples)
        assertEquals("逐段丢失账不许从泵线程单写者容器里读", emptyList<Long>(), out.segLostMs)
        // 能安全读的账照常带出（内部加锁账本 / @Volatile / 构造期常量）
        assertEquals("seriesId 带出（归组用）", 42L, out.seriesId)
    }

    @Test
    fun `单段快照也要带出_不是只认多段`() {
        val out = sealedPartsResult(
            sealed = listOf(seg(0, 128L)),
            err = RecordError.STOP_FAILED,
            durationMs = 2_000L,
            seriesId = 9L,
            lostMs = 3_000L,
            rotateStalls = 1,
            muxDiag = "m",
            ambientLost = true,
            latePending = 1L
        )
        assertEquals("单段同样如实带出（段数不是门槛）", 1, out.parts.size)
        assertEquals(128L, out.bytes)
        assertEquals(9L, out.seriesId)
        assertEquals(3_000L, out.lostMs)
        assertEquals(1, out.rotateStalls)
        assertEquals("m", out.muxDiag)
        assertTrue("环境音丢失标记带出（附注用）", out.ambientLost)
        assertEquals(1L, out.latePending)
        assertEquals(2_000L, out.durationMs)
        assertFalse(out.ok)
    }
}
