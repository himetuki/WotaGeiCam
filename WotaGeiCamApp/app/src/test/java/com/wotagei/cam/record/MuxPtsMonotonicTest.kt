package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换段期 muxer 写失败（真机账 `ENGINE_ERROR:MUX|trk=A;started=1;field=same;st=0`）的两层修复守卫。
 *
 * 现场事实（2026-10-08，装机版 + 零主机干扰）：同一轨 pts 倒退会让 `MediaMuxer.writeSampleData`
 * 抛 `IllegalStateException` ⇒ 泵循环退出 ⇒ **整场录制当场结束**；失败样本恒为音频轨、且发生在
 * 新段起来后 30~130ms。
 *
 * 修复分两层，本文件各钉一层：
 * 1. **保序**（结构层）：换段期"暂存首样本补写"必须排在"新样本直写"之前 —— 否则补写那枚**较早**
 *    的样本会晚于**较新**的样本写出，同轨 pts 倒退。这条用**偏移序**断言（`runSegment` 体内
 *    `heldOutIdx` 的写出点必须在 `idx` 的直写点之前）。
 * 2. **保不死**（行为层）：[monotonicPts] 把等值/倒退就地推进到 `last+1`，把致命错误降级成
 *    微秒级修正；计次与最大倒退量必须进失败快照（否则下次复现只能继续猜机制）。
 */
class MuxPtsMonotonicTest {

    private val codec = codeOnly(mainSourceText("record/CodecRecorder.kt"))

    // ---- ① 纯函数表：monotonicPts（全手写字面量）----

    @Test
    fun `本段首枚不做判定（没有历史就原样放行）`() {
        val r = monotonicPts(lastUs = -1L, candidateUs = 12_345L)
        assertEquals(12_345L, r.ptsUs)
        assertFalse(r.advanced)
        assertEquals(0L, r.backUs)
    }

    @Test
    fun `严格递增原样放行`() {
        val r = monotonicPts(lastUs = 1_000L, candidateUs = 20_000L)
        assertEquals(20_000L, r.ptsUs)
        assertFalse(r.advanced)
        assertEquals(0L, r.backUs)
    }

    @Test
    fun `等值必须推进一微秒（等值也会被 MPEG4Writer 拒）`() {
        val r = monotonicPts(lastUs = 5_000L, candidateUs = 5_000L)
        assertEquals(5_001L, r.ptsUs)
        assertTrue(r.advanced)
        assertEquals(1L, r.backUs)
    }

    @Test
    fun `倒退推进到 last+1 并把倒退量记账`() {
        val r = monotonicPts(lastUs = 100_000L, candidateUs = 40_000L)
        assertEquals(100_001L, r.ptsUs)
        assertTrue(r.advanced)
        assertEquals(60_001L, r.backUs)
    }

    @Test
    fun `倒退一微秒记 2（last-cur+1 口径）`() {
        val r = monotonicPts(lastUs = 1_000L, candidateUs = 999L)
        assertEquals(1_001L, r.ptsUs)
        assertTrue(r.advanced)
        assertEquals(2L, r.backUs)
    }

    @Test
    fun `负值夹紧后的同零相遇同样推进（换段期最常见的形态）`() {
        val r = monotonicPts(lastUs = 0L, candidateUs = 0L)
        assertEquals(1L, r.ptsUs)
        assertTrue(r.advanced)
        assertEquals(1L, r.backUs)
    }

    @Test
    fun `真实数量级（nanoTime 微秒域）不溢出且推进正确`() {
        val base = 1_026_194_809_575L // 现场 base= 量级
        val r = monotonicPts(lastUs = base, candidateUs = base - 75L)
        assertEquals(base + 1L, r.ptsUs)
        assertTrue(r.advanced)
        assertEquals(76L, r.backUs)
    }

    @Test
    fun `advance 后仍单调：把结果喂回同一桥必须原样放行`() {
        val fixed = monotonicPts(lastUs = 700L, candidateUs = 100L).ptsUs
        val again = monotonicPts(lastUs = fixed, candidateUs = fixed)
        assertTrue("推进后的值再喂回去必须只推进 1µs，不得再判倒退", again.advanced)
        assertEquals(fixed + 1L, again.ptsUs)
    }

    // ---- ② 行为层接线：writeSample 必须走桥、快照必须带读数 ----

    @Test
    fun `writeSample 的 pts 单调判定必须走桥`() {
        val w = bodyOf(codec, "writeSample")
        assertTrue("writeSample 必须调 monotonicPts 桥（不许内联判定）", w.contains("monotonicPts("))
        assertTrue("必须用桥给出的 pts 覆盖 info.presentationTimeUs", w.contains("info.presentationTimeUs = fix.ptsUs"))
        assertTrue("必须把本轨已写出的 pts 记回账本", w.contains("muxLastPtsUs[ti] = fix.ptsUs"))
    }

    @Test
    fun `失败快照必须带上 ptsfix 与 back 读数`() {
        val w = bodyOf(codec, "writeSample")
        // 注意：KotlinSourceScan 会把字符串字面量的内容遮蔽掉（只保留 `${...}` 里的真代码），
        // 所以这里断言的必须是**插值里的标识符**，不能断言 "ptsfix=" 这种字面量。
        // 从 muxFailDiagnostic( 之后切一段：增量语句在它之前，切掉就不会被"自己涨了自己"假绿。
        val afterDiag = w.substring(w.indexOf("muxFailDiagnostic("))
        assertTrue("快照要能证明是不是这条机制：必须带 ptsfix 计数", afterDiag.contains("muxPtsFixes"))
        assertTrue("快照要能给出最大倒退量：必须带 back 计数", afterDiag.contains("muxPtsMaxBackUs"))
    }

    @Test
    fun `逐段必须复位 pts 账（新段新 muxer，没有历史）`() {
        // 1 处定义 + 2 处调用（start 与换段各自设 segBaseUs 的地方）
        val n = codec.split("resetMuxPtsBooks()").size - 1
        assertTrue("resetMuxPtsBooks 至少要有定义 + 两处调用（start / 换段），实际 $n 处", n >= 3)
    }

    // ---- ③ 保序层：暂存补写必须排在新样本直写之前（偏移序）----

    @Test
    fun `环境轨暂存补写必须早于直写`() {
        val body = bodyOf(codec, "runSegment")
        val held = body.indexOf("a.getOutputBuffer(envCh.heldOutIdx)")
        val direct = body.indexOf("a.getOutputBuffer(idx)")
        assertTrue("两处写出点都必须存在（held=$held direct=$direct）", held >= 0 && direct >= 0)
        assertTrue(
            "环境轨暂存补写必须排在直写之前（反过来 ⇒ 同轨 pts 倒退 ⇒ muxer 抛异常整场录制结束）",
            held < direct
        )
    }

    @Test
    fun `内录轨暂存补写必须早于直写`() {
        val body = bodyOf(codec, "runSegment")
        val held = body.indexOf("c.getOutputBuffer(capCh.heldOutIdx)")
        val direct = body.indexOf("c.getOutputBuffer(idx)")
        assertTrue("两处写出点都必须存在（held=$held direct=$direct）", held >= 0 && direct >= 0)
        assertTrue(
            "内录轨暂存补写必须排在直写之前（同上，轨序红线之外还有时序红线）",
            held < direct
        )
    }
}
