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
 */
class ArcRepairZeroSampleLaneGuardTest {

    private fun violations(raw: String): List<String> {
        val src = codeOnly(raw)
        val open = flatten(bodyOf(src, "open"))
        val lanes = flatten(bodyOf(src, "openAudioLanes"))
        val bad = ArrayList<String>()
        if (!open.contains("val videoBasePtsUs = videoEx.sampleTime")) {
            bad.add("open 必须用视频首样本 PTS 作为 lane 预扫的保守基准")
        }
        if (!lanes.contains("hasWritableSample(videoBasePtsUs)")) {
            bad.add("建 lane 前必须按平移量预扫可写样本")
        }
        if (!lanes.contains("lane.release()")) {
            bad.add("预扫判无样本时必须释放 lane extractor")
        }
        if (!lanes.contains("lanes.add(lane)")) {
            bad.add("预扫通过后才允许把 lane 加进音频轨集合")
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
        val good = violations(raw)
        assertEquals("良品必须先真的绿", 0, good.size)
        // 突变：预扫恒通过（零样本 lane 重新建轨 = 缺陷回潮）
        val mutant = raw.replace(
            "if (!lane.hasWritableSample(videoBasePtsUs))",
            "if (false)"
        )
        assertTrue("删预扫的突变体必须报红", violations(mutant).isNotEmpty())
    }
}
