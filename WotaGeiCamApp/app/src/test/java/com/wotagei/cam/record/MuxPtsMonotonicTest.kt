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

    // ---- ④ 分账层：同格等值（设计内）与真倒退（异常）不许混记（S1-M3-04）----
    //
    // 为什么钉结构而不新增纯函数行：等值 ⇔ `backUs == 1`、真倒退 ⇔ `backUs >= 2` 已由上面 ①② 的
    // 表测逐字覆盖（`等值必须推进一微秒` 记 1、`倒退一微秒记 2` 记 2），再加一行只是把定义抄一遍
    // （AGENTS：由定义直接推出的等式测不出任何实现退化）。真正会退的是**接线**：把等值当异常
    // 计数、或把两枚账又合回去，纯函数全绿也照样漏。突变 M1/M2 就打在这两处。

    @Test
    fun `同格等值与真倒退必须分两枚计数`() {
        val w = bodyOf(codec, "writeSample")
        val eq = w.indexOf("if (info.presentationTimeUs == lastUs)")
        assertTrue("锚点丢失：writeSample 里没有「候选 == 上一戳 ⇒ 等值」的分叉判定", eq >= 0)
        val elseAt = w.indexOf("} else {", eq)
        assertTrue("锚点丢失：等值分支没有配 else（两枚账必须真的分开，不许共用一支）", elseAt > eq)

        // 等值分支：只许计数 + d 级留痕
        val eqBranch = w.substring(eq, elseAt)
        assertTrue("等值必须单独计数", eqBranch.contains("muxPtsEqualFixes++"))
        assertFalse(
            "等值分支不许自增真倒退计数（混记＝把设计内同格多帧当异常，快照这把尺子就废了）",
            eqBranch.contains("muxPtsFixes++")
        )
        assertFalse("等值分支不许动最大倒退量", eqBranch.contains("muxPtsMaxBackUs"))
        assertFalse("等值分支不许打 w（逐帧刷屏正是本条要治的）", eqBranch.contains("Log.w("))
        assertTrue(
            "等值必须降到 d 级留痕（D 档默认关，见 Diag.diagLevelEnabled；否则仍按帧级刷屏）",
            eqBranch.contains("Log.d(")
        )

        // 真倒退分支：保留 w + 两枚读数
        val backBranch = w.substring(elseAt)
        assertTrue("真倒退必须自增 ptsfix 计数", backBranch.contains("muxPtsFixes++"))
        assertTrue("真倒退必须更新最大倒退量", backBranch.contains("muxPtsMaxBackUs"))
        assertTrue("真倒退必须保留 w 级日志", backBranch.contains("Log.w("))
    }

    @Test
    fun `两枚计数都要进段尾汇总行并逐段复位`() {
        val seg = bodyOf(codec, "runSegment")
        assertTrue("段尾汇总行必须带真倒退计数（不逐帧刷就得有汇总出口）", seg.contains("muxPtsFixes"))
        assertTrue("段尾汇总行必须带等值计数", seg.contains("muxPtsEqualFixes"))
        val reset = bodyOf(codec, "resetMuxPtsBooks")
        assertTrue("复位必须清真倒退计数", reset.contains("muxPtsFixes = 0"))
        assertTrue("复位必须清等值计数（否则跨段串味，汇总行读不出本段）", reset.contains("muxPtsEqualFixes = 0"))
    }

    // ---- ⑤ 换段重挂失败分支的资源释放（S1-M3-02）----

    @Test
    fun `重挂失败分支必须就地释放新建的编码器实例`() {
        val open = bodyOf(codec, "openNextSegment")
        val br = open.indexOf("if (!rebindOk)")
        assertTrue("锚点丢失：openNextSegment 里没有 !rebindOk 分支", br >= 0)
        val end = open.indexOf("return false", br)
        assertTrue("锚点丢失：!rebindOk 分支没有 return false", end > br)
        val branch = open.substring(br, end)
        assertTrue(
            "接线回调抛异常时这枚 MediaCodec 还没进 vCodec（vCodec = v 排在本分支之后），" +
                "closeSegmentEngine 够不着它 ⇒ 必须就地 releaseQuietly(v)" +
                "（漏放不是内存问题：并发 codec 实例有限，后续录制可能直接建不出编码器）",
            branch.contains("releaseQuietly(v)")
        )
        assertTrue("输出句柄仍要照原样关（补释放不许顺手漏掉 fd）", branch.contains("closeBounded(t)"))
    }
}
