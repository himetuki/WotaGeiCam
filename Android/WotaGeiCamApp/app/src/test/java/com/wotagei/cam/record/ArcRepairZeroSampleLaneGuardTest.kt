package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复链「内录 lane 平移后零样本」防线守卫。
 *
 * 背景：`capOffsetMs` 为正（内录晚响，向后校准）时 lane 1 整体前移；若平移量接近/超过该轨全长，
 * 所有样本 PTS 都 <0 被丢弃，而 `addTrack` 已建轨 → `muxer.stop()` 对零样本轨抛错 → 整份修复
 * 产物按失败回收（视频明明完好）。修复 = 建轨前按 shift 预扫至少一枚可写样本，没有就不 addTrack。
 *
 * 2026-11-19（Y25）：预扫从 `openAudioLanes` 挪到 `startMuxerIfReady`。建 lane 时首帧显示 PTS
 * （decodePtsBase）还没定，旧实现用视频首样本 PTS 预扫，可能与拷贝时基不一致（预扫宽松、
 * 实际写入 0 枚）。现在只允许在 muxer.start 前、用与拷贝同一条 [arcAudioOutPtsUs] 判据与同一个
 * base 验收；不通过就释放 lane、绝不建零样本轨。
 */
class ArcRepairZeroSampleLaneGuardTest {

    private fun violations(raw: String): List<String> {
        val src = codeOnly(raw)
        val lanes = flatten(bodyOf(src, "openAudioLanes"))
        val add = flatten(bodyOf(src, "addAudioTracksIfReady"))
        val bad = ArrayList<String>()
        if (lanes.contains("hasWritableSample(")) {
            bad.add("建 lane 时不得预扫（时基未定，预扫会与拷贝口径分叉）")
        }
        if (lanes.contains("addTrack(")) {
            bad.add("建 lane 时不得提前 addTrack（必须在 start 前按可写性验收）")
        }
        if (!add.contains("if (!lane.hasWritableSample(base))")) {
            bad.add("muxer.start 前必须按与拷贝同一 base 的可写判据验收")
        }
        if (!add.contains("lane.release()")) {
            bad.add("预扫判无样本时必须释放 lane extractor")
        }
        if (!add.contains("m.addTrack(")) {
            bad.add("验收通过后才允许建轨")
        }
        if (add.indexOf("hasWritableSample(") !in 0 until add.indexOf("m.addTrack(")) {
            bad.add("可写性验收必须发生在 addTrack 之前")
        }
        val startMux = flatten(bodyOf(src, "startMuxerIfReady"))
        if (!startMux.contains("addAudioTracksIfReady(")) {
            bad.add("startMuxerIfReady 必须在 muxer.start 前调用音频轨验收")
        }
        return bad
    }

    @Test
    fun `零样本内录lane不得建轨`() {
        val raw = mainSourceText("record/ArcRepairRunner.kt")
        assertEquals(
            "零样本 lane 防线断点：\n${violations(raw).joinToString("\n")}",
            emptyList<String>(),
            violations(raw)
        )
    }

    @Test
    fun `突变_删预扫必红`() {
        val raw = mainSourceText("record/ArcRepairRunner.kt")
        assertEquals("良品必须先真的绿", 0, violations(raw).size)
        // 突变：验收恒通过（零样本 lane 重新建轨 = 缺陷回潮）
        val mutant = raw.replace(
            "if (!lane.hasWritableSample(base))",
            "if (false)"
        )
        assertTrue("删预扫的突变体必须报红", violations(mutant).isNotEmpty())
    }
}
