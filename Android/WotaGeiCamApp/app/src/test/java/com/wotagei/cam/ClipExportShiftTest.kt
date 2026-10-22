package com.wotagei.cam

import com.wotagei.cam.player.ClipRule
import com.wotagei.cam.player.SelectedTrack
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪辑导出的同步平移（纯函数 + 接线守卫）：
 * - 只有 CAP 吃 `−offsetMs`，ENV 恒 0；
 * - 平移后负 PTS 丢弃（不 clip 到 0）；
 * - 预扫与写出必须吃同一枚 audioShiftUs（不同源 = 建了轨却写不出/写出负 PTS 崩 muxer）。
 */
class ClipExportShiftTest {

    @Test
    fun `内录轨按负偏移平移_环境轨恒零`() {
        assertEquals(-120_000L, ClipRule.audioShiftUs(SelectedTrack.CAP, offsetMs = 120))
        assertEquals(120_000L, ClipRule.audioShiftUs(SelectedTrack.CAP, offsetMs = -120))
        assertEquals(0L, ClipRule.audioShiftUs(SelectedTrack.CAP, offsetMs = 0))
        assertEquals(0L, ClipRule.audioShiftUs(SelectedTrack.ENV, offsetMs = 120))
        assertEquals(0L, ClipRule.audioShiftUs(SelectedTrack.ENV, offsetMs = -999))
    }

    @Test
    fun `平移后负PTS不可写_不clip到零`() {
        // 源样本早于入点 100ms：环境轨（shift=0）仍为负 → 丢弃
        assertFalse(ClipRule.remuxAudioSampleWritable(relUs = -100_000, shiftUs = 0, endRelUs = null))
        // 内录晚响 120ms → shift=+120ms，同一枚样本被拉回正区间 → 可写
        assertTrue(ClipRule.remuxAudioSampleWritable(relUs = -100_000, shiftUs = 120_000, endRelUs = null))
        // 负向平移（内录早响）会把 50ms 样本推到负 → 丢弃，且不允许钳到 0
        assertFalse(ClipRule.remuxAudioSampleWritable(relUs = 50_000, shiftUs = -120_000, endRelUs = null))
        // 出点也必须吃平移后的时刻：raw 在区间内、延后后越界 → 不写（预扫/写出同口径）
        assertFalse(
            ClipRule.remuxAudioSampleWritable(relUs = 990_000, shiftUs = 120_000, endRelUs = 1_000_000)
        )
        assertTrue(
            ClipRule.remuxAudioSampleWritable(relUs = 880_000, shiftUs = 120_000, endRelUs = 1_000_000)
        )
        assertEquals("边界 0 是可写的（keepSample 口径 rel>=0）", true, ClipRule.keepSample(0))
        assertFalse(ClipRule.keepSample(-1))
    }

    @Test
    fun `导出接线_预扫与写出同吃audioShiftUs且视频不平移`() {
        val src = codeOnly(mainSourceText("player/ClipExport.kt"))
        val exportSig = flatten(src.substringAfter("suspend fun export(").substringBefore("val range ="))
        assertTrue("export 必须收缓存偏移", exportSig.contains("offsetMs: Long"))
        val copy = flatten(KotlinSourceScan.bodyOf(src, "copy"))
        assertTrue(
            "预扫必须用与写出同源的 shiftUs（写死 0 会让平移后的内录轨整条被判不可写）",
            copy.contains("audioTrackWritable(ex, it, baseUs, shiftUs = shiftUs")
        )
        assertTrue(
            "音频判据必须吃平移后的 audioRel",
            copy.contains("val audioRel = rel + shiftUs") && copy.contains("ClipRule.keepSample(audioRel)")
        )
        assertFalse(
            "视频轨不得被音轨偏移污染（视频 PTS 只减公共基准）",
            copy.contains("val rel = ex.sampleTime - baseUs + shiftUs")
        )
    }

    @Test
    fun `预扫写死0与正确平移的行为差异`() {
        // 内录早 120ms → shift=+120ms：一枚早于入点 100ms 的样本在正确平移下可写
        assertTrue(ClipRule.remuxAudioSampleWritable(relUs = -100_000, shiftUs = 120_000, endRelUs = null))
        // 同一个预扫若写死 shift=0（缺陷形态）会判不可写——这正是"建了轨却写不出样本"的来源
        assertFalse(ClipRule.remuxAudioSampleWritable(relUs = -100_000, shiftUs = 0L, endRelUs = null))
        // 出点同口径：raw 990ms 在 1000ms 出点内，但延后 120ms 后越界 → 正确实现不写
        assertFalse(ClipRule.remuxAudioSampleWritable(relUs = 990_000, shiftUs = 120_000, endRelUs = 1_000_000))
        assertTrue(ClipRule.remuxAudioSampleWritable(relUs = 990_000, shiftUs = 0L, endRelUs = 1_000_000))
    }
}
