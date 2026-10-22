package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出链音轨选择的接线守卫（2026-10-18 用户定版）：
 *
 * - 剪辑导出**只导用户选中的那一条**（默认环境音），既不"只认第一条"（双轨素材的内录被静默丢），
 *   也不"多轨全导"；
 * - 建轨前必须过 `audioTrackWritable` 预扫：零样本轨会让 `muxer.stop()` 抛异常，而导出链的
 *   异常路径会删掉**整份**输出（视频明明完好）——双音轨素材的某一轨、或平移后全为负的内录轨
 *   都会命中这条。
 */
class ExportTrackSelectionGuardTest {

    private val clip = codeOnly(mainSourceText("player/ClipExport.kt"))
    private val track = codeOnly(mainSourceText("player/TrackExport.kt"))
    private val player = codeOnly(mainSourceText("player/PlayerScreen.kt"))

    @Test
    fun `剪辑导出必须暴露音轨选择且按选中轨取轨`() {
        val export = flatten(clip.substringAfter("suspend fun export(").substringBefore("val range ="))
        assertTrue("export 必须收用户选中的音轨参数", export.contains("track: SelectedTrack"))
        val copyBody = flatten(clip.substringAfter("private fun copy(").substringBefore("val info = MediaCodec.BufferInfo()"))
        assertTrue("必须枚举容器里全部 audio/ 轨", copyBody.contains("audioTracks.add(i)"))
        assertTrue(
            "必须按选中档位取轨（ENV=0 / CAP=1），而不是固定第一条",
            copyBody.contains("val audioOrdinal = if (track == SelectedTrack.ENV) 0 else 1") &&
                copyBody.contains("audioTracks.getOrNull(audioOrdinal)")
        )
        assertFalse(
            "不得回潮成『只认第一条音轨』（audioTrack < 0 && 前缀），那会让内录音轨被静默丢掉",
            copyBody.contains("audioTrack < 0 &&")
        )
        assertTrue(
            "建轨前必须预扫零样本轨（否则 muxer.stop 抛错 → 整份输出被删；平移量必须与写出同源）",
            copyBody.contains("audioTrackWritable(ex, it, baseUs, shiftUs = shiftUs, endRelUs = endRel")
        )
    }

    @Test
    fun `选轨导出建轨前也必须预扫零样本轨`() {
        val copyBody = flatten(track.substringAfter("private fun copy("))
        assertTrue(
            "TrackExporter.copy 必须先跑 audioTrackWritable",
            copyBody.contains("audioTrackWritable(ex, audioTrack, baseUs, shiftUs, endRelUs = null)")
        )
        assertTrue(
            "不可写时不得建音频轨（aIdx 必须按 audioWritable 分岔）",
            copyBody.contains("val aIdx = if (audioWritable) muxer.addTrack")
        )
        assertTrue("写出前必须再判 aIdx（防御未建轨时写入）", copyBody.contains("} else if (aIdx >= 0)"))
        assertTrue(
            "未建轨（纯视频）时不得写音频样本",
            copyBody.contains("muxer.writeSampleData(aIdx") && copyBody.contains("if (audioWritable)")
        )
    }

    @Test
    fun `播放页剪辑弹层必须带音轨选择并把它传给导出`() {
        val popup = flatten(player.substringAfter("private fun ClipExportPopup(").substringBefore("private fun TrackPickPopup("))
        assertTrue("弹层必须收选中音轨", popup.contains("track: SelectedTrack"))
        assertTrue("弹层必须能改选", popup.contains("onPickTrack"))
        assertTrue("内录项必须按双音轨门置灰", popup.contains("enabled = dualReady"))
        assertTrue(
            "导出调用必须把用户选中的音轨与缓存偏移传进去（漏传 = 选择/同步无效）",
            flatten(player).contains("clipExporter.export(clip, ab.aMs, ab.bMs, clipTrack, offset)")
        )
        assertTrue("选轨必须是按片重置的会话态", player.contains("var clipTrack by remember(clip.uri)"))
    }
}
