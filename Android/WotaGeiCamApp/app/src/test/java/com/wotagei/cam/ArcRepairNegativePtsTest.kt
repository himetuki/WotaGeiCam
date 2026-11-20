package com.wotagei.cam

import com.wotagei.cam.record.arcAudioHasWritable
import com.wotagei.cam.record.arcAudioOutPtsUs
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y25 音频准入与拷贝的**单一基准**（负首帧反例 + 接线钉扎 + 突变）。
 *
 * 缺陷形态：`decodePtsBase` 用 `-1L` 兼作"未定"哨兵，而负 PTS 前导是源容器里的**合法**样本。
 * 于是一旦首帧 PTS 为负：① 每一帧都被当成首帧，基准被反复改写；② muxer.start 前的预扫把
 * 负基准误当"未知"退回 0；③ 拷贝在基准为负时退回首枚音频样本。预扫与拷贝各锚一个时基，
 * "可写"的判定与实际写出的样本对不上，整条音轨错位。
 *
 * 修复后：基准是否已确定用显式布尔记账，基准值可为负；准入与拷贝读同一枚字段、同一算式。
 * 旧代码在"负基准 + 合法样本"这组反例上会判"不可写"（丢轨）而拷贝本可写，故本测有判别力。
 */
class ArcRepairNegativePtsTest {

    /** 源码接线违规项（空表 = 接线正确）；抽出来也供突变体复用 */
    private fun violations(raw: String): List<String> {
        val src = codeOnly(raw)
        val bad = ArrayList<String>()
        if (src.contains("decodePtsBase < 0L")) bad.add("不得再用符号表示未定（合法负 PTS 会被误判）")
        if (src.contains("decodePtsBase >= 0L")) bad.add("不得再以 >= 0L 判基准是否可用")

        val frame = bodyOf(src, "handleDecodedFrame")
        if (!frame.contains("if (!decodePtsBaseKnown)")) bad.add("首帧基准必须只写一次（显式已知位）")
        if (!frame.contains("decodePtsBaseKnown = true")) bad.add("写基准时同时置已知位")

        val start = bodyOf(src, "startMuxerIfReady")
        val guardAt = start.indexOf("if (!decodePtsBaseKnown) return")
        val addAt = start.indexOf("addAudioTracksIfReady(")
        if (guardAt < 0 || addAt <= guardAt) bad.add("基准未定时不得启动 muxer（推迟到基准已知）")
        if (!bodyOf(src, "drainEncoder").contains("startMuxerIfReady(")) {
            bad.add("推迟后必须能再次尝试启动（drainEncoder 顶部重试）")
        }

        val add = bodyOf(src, "addAudioTracksIfReady")
        if (!add.contains("val base = decodePtsBase")) bad.add("建轨验收直接用 decodePtsBase")
        if (!add.contains("if (!lane.hasWritableSample(base))")) bad.add("同一基准必须原样传给预扫")
        if (add.contains("else 0L")) bad.add("不得再把负基准退回 0")
        if (add.contains(">= 0L")) bad.add("不得按符号判基准可用")

        val prescan = bodyOf(src, "hasWritableSample")
        if (!prescan.contains("arcAudioOutPtsUs(ex.sampleTime, basePtsUs, shiftUs)")) {
            bad.add("预扫必须走与拷贝同一算式")
        }

        val copy = bodyOf(src, "drainUpTo")
        if (!copy.contains("val ptsBase = decodePtsBase")) bad.add("拷贝时基必须在准入时定版")
        if (!copy.contains("arcAudioOutPtsUs(pendingPts, ptsBase, shiftUs)")) bad.add("拷贝必须与预扫同一算式")
        if (copy.contains("else pendingPts")) bad.add("拷贝不得再自行退回首枚音频样本作基准")
        if (copy.contains("if (ptsBase < 0L)")) bad.add("拷贝不得再按符号重挑基准")
        if (src.contains("var ptsBase = -1L")) bad.add("lane 不再自带一枚 -1 时基哨兵")
        if (bodyOf(src, "openAudioLanes").contains("hasWritableSample(")) bad.add("建 lane 时点不得预扫")
        return bad
    }

    @Test
    fun `负首帧基准下预扫与拷贝同口径_合法样本不被当未知`() {
        // 负 PTS 前导素材：首帧显示 PTS = -300ms，音频从 -280ms 起，全部是真样本
        val base = -300_000L
        val samples = listOf(-280_000L, -250_000L, -10_000L)
        assertEquals(
            "首枚音频样本相对负基准归一后为 +20ms（拷贝真正写出的 PTS）",
            20_000L,
            arcAudioOutPtsUs(samples[0], base, 0L)
        )
        assertTrue("同一基准下序列有可写样本，必须建轨", arcAudioHasWritable(samples, base, 0L))
        // 旧预扫把负基准当"未知"退回 0：这些负时间轴样本全被判掉 ⇒ 整条音轨被丢。
        // 而拷贝在同一文件的旧代码里用真基准，本可写出 3 枚——这就是"单一准入"被破坏的证据。
        assertFalse(
            "退回 0 的旧口径会把这些合法负时间轴样本全判掉（Y25 的分歧点）",
            arcAudioHasWritable(samples, 0L, 0L)
        )
    }

    @Test
    fun `负基准加平移的边界与正基准同一算式`() {
        assertEquals(
            "样本本身早于基准 ⇒ 直接不可写（平移救不回来，判据先看 norm）",
            -1L,
            arcAudioOutPtsUs(-320_000L, -300_000L, 100_000L)
        )
        assertEquals(
            "基准之后 +20ms，平移 −100ms 后为负 ⇒ 不可写",
            -1L,
            arcAudioOutPtsUs(-280_000L, -300_000L, -100_000L)
        )
        assertEquals(
            "基准之后 +20ms，平移 +100ms ⇒ 可写 120ms",
            120_000L,
            arcAudioOutPtsUs(-280_000L, -300_000L, 100_000L)
        )
        assertEquals(
            "负基准不会把正常样本推负（−300ms 基准下 1s 样本 → 1.3s）",
            1_300_000L,
            arcAudioOutPtsUs(1_000_000L, -300_000L, 0L)
        )
    }

    @Test
    fun `源码钉扎_基准用显式已知位且准入与拷贝读同一枚字段`() {
        val raw = mainSourceText("record/ArcRepairRunner.kt")
        assertEquals("音频时基接线断点：\n${violations(raw).joinToString("\n")}", emptyList<String>(), violations(raw))
    }

    @Test
    fun `突变_基准退回符号哨兵与0口径必红`() {
        val raw = mainSourceText("record/ArcRepairRunner.kt")
        assertEquals("良品必须先真的绿", 0, violations(raw).size)
        // 突变：把"显式已知位"改回"符号哨兵"，并把建轨验收改回 0 兜底
        val mutant = raw
            .replace("if (!decodePtsBaseKnown)", "if (decodePtsBase < 0L)")
            .replace("val base = decodePtsBase", "val base = if (decodePtsBase >= 0L) decodePtsBase else 0L")
        assertTrue("退回符号哨兵/0 兜底的突变体必须报红", violations(mutant).isNotEmpty())
    }
}