package com.wotagei.cam

import com.wotagei.cam.player.SelectedTrack
import com.wotagei.cam.player.TrackRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 选轨导出纯逻辑（TrackRule）单测：平移量、负 PTS 丢弃语义（MP4 封轨要求每轨 PTS 单调
 * 非负，clip 到 0 会同刻多样本，裁决=丢弃前导，见 TrackRule 头注）、命名。
 * 拷贝本体（MediaExtractor/MediaMuxer）依赖 Android framework，真机验收归真机验证清单。
 */
class TrackExportTest {

    private val baseUs = 1_000_000L // 视频首样本 PTS（输出 0 点）

    // region shiftUsFor：选轨平移量

    @Test
    fun `选环境轨平移为0`() {
        assertEquals(0L, TrackRule.shiftUsFor(SelectedTrack.ENV, 120L))
        assertEquals(0L, TrackRule.shiftUsFor(SelectedTrack.ENV, -45L))
    }

    @Test
    fun `选内录轨平移为负offset微秒`() {
        // offset 正=内录晚响 120ms：导出把每个样本提前 120_000us
        assertEquals(-120_000L, TrackRule.shiftUsFor(SelectedTrack.CAP, 120L))
        assertEquals(0L, TrackRule.shiftUsFor(SelectedTrack.CAP, 0L))
    }

    @Test
    fun `选内录轨负offset变正平移`() {
        // 内录早响 45ms（offset=-45）：平移 +45_000us，无样本被丢
        assertEquals(45_000L, TrackRule.shiftUsFor(SelectedTrack.CAP, -45L))
    }

    // endregion

    // region shiftedPts：负 PTS 前导丢弃（不 clip 到 0）

    @Test
    fun `环境轨样本原样平移到0点系`() {
        assertEquals(0L, TrackRule.shiftedPts(1_000_000L, baseUs, 0L))
        assertEquals(2_500_000L, TrackRule.shiftedPts(3_500_000L, baseUs, 0L))
    }

    @Test
    fun `早于0点的样本丢弃`() {
        // 视频基点之前的音频样本（AAC 帧界早于视频首样本）负 PTS → null，与 ClipExporter 同口径
        assertNull(TrackRule.shiftedPts(999_999L, baseUs, 0L))
    }

    @Test
    fun `内录晚响_偏移窗内样本全丢`() {
        // offset=+120ms：前 120ms 的内录样本平移后为负 → 丢弃（对齐的物理代价）
        assertNull(TrackRule.shiftedPts(baseUs + 50_000L, baseUs, -120_000L))
        assertNull(TrackRule.shiftedPts(baseUs + 119_999L, baseUs, -120_000L))
    }

    @Test
    fun `内录晚响_恰在偏移点上平移为0保留`() {
        // 恰好 120ms：0 是合法 PTS（非负），保留——单调性不受单点影响
        assertEquals(0L, TrackRule.shiftedPts(baseUs + 120_000L, baseUs, -120_000L))
    }

    @Test
    fun `内录晚响_偏移窗后样本提前对齐`() {
        assertEquals(80_000L, TrackRule.shiftedPts(baseUs + 200_000L, baseUs, -120_000L))
    }

    @Test
    fun `内录早响_正平移不产生丢弃`() {
        // offset=-45ms：+45ms 平移，最前样本 0+45_000，恒非负
        assertEquals(45_000L, TrackRule.shiftedPts(baseUs, baseUs, 45_000L))
    }

    @Test
    fun `丢弃判据保序_留下的样本平移后严格单调`() {
        // 判据只砍负前导、不改相对次序：留下的段必须严格递增（MP4 封轨前提）
        var prev = -1L
        listOf(120_000L, 121_920L, 150_000L, 900_000L).forEach { srcUs ->
            val out = TrackRule.shiftedPts(baseUs + srcUs, baseUs, -120_000L)
                ?: error("偏移点后的样本不许被丢")
            assert(prev < out) { "平移后 PTS 退化：$prev -> $out" }
            prev = out
        }
    }

    // endregion

    // region nameOf：inau_ 前缀命名

    @Test
    fun `name 常规mp4加inau前缀`() {
        assertEquals("inau_VID_20261005_120000_001.mp4", TrackRule.nameOf("VID_20261005_120000_001.mp4"))
    }

    @Test
    fun `name 无扩展名直接加前缀`() {
        assertEquals("inau_take3.mp4", TrackRule.nameOf("take3"))
    }

    @Test
    fun `name 隐藏文件按无扩展名处理`() {
        assertEquals("inau_.hidden.mp4", TrackRule.nameOf(".hidden"))
    }

    // endregion
}
