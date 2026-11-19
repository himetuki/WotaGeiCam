package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y15：同一段里重复的 `INFO_OUTPUT_FORMAT_CHANGED` 不许再建视频轨。
 *
 * 缺陷形态（旧代码 `runSegment` 的输出 dequeue 分支）：
 * ```
 * idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
 *     videoTrack = mx.addTrack(v.getOutputFormat())   // 无条件建轨
 *     ...
 * }
 * ```
 * muxer 已 `start()` 后再 `addTrack` 是非法状态（抛 `IllegalStateException` 炸掉泵线程，整场以
 * LOOP 结束）；启动前重复通知则多建一条永远没有样本的空轨。编码器何时重复通知不可控（重配 /
 * ROM 行为），所以判定必须在段内幂等：每段只建一次，真换格式走段级错误码。
 *
 * 本测试钉三件事：
 * 1. 纯判定的全真值表（首轨 / 同指纹重复 / 真换格式 / 指纹缺失）；
 * 2. 结构接线：段内只有一处视频 `addTrack`，且必须落在 `ADD_TRACK` 臂里；指纹与自检格只在
 *    建轨臂更新；`FAIL_CHANGED` 臂置 `ENGINE_ERROR:FMT` 并退出循环；
 * 3. 突变自证：把建轨复制进 `IGNORE_SAME` 臂（= 旧缺陷），"只建一轨"的尺子必须报红。
 */
class VideoFmtTrackGuardTest {

    private val raw = mainSourceText("record/CodecRecorder.kt")
    private val codec = codeOnly(raw)
    private val seg = flatten(bodyOf(codec, "runSegment"))

    // ------------------------------------------------------------------ 纯判定真值表

    @Test
    fun `首轨必建_已建轨同指纹重复忽略_指纹不同按段级错误`() {
        assertEquals(
            "本段尚未建轨 ⇒ 必须建轨（正常首轨路径）",
            VideoFmtAction.ADD_TRACK,
            videoFmtAction(trackBuilt = false, previousKey = null, nextKey = "mime/1920/1080")
        )
        assertEquals(
            "未建轨时旧键不参与判定（段内局部变量不可能有值，真出现也只认首轨）",
            VideoFmtAction.ADD_TRACK,
            videoFmtAction(trackBuilt = false, previousKey = "x", nextKey = "y")
        )
        assertEquals(
            "已建轨且指纹相同 ⇒ 只是重复通知，不许再 addTrack",
            VideoFmtAction.IGNORE_SAME,
            videoFmtAction(trackBuilt = true, previousKey = "k", nextKey = "k")
        )
        assertEquals(
            "已建轨但指纹缺失（无法证明同一格式）⇒ 宁可段级报错也不混写",
            VideoFmtAction.FAIL_CHANGED,
            videoFmtAction(trackBuilt = true, previousKey = null, nextKey = "k")
        )
        assertEquals(
            "已建轨且指纹真的变了（重配/切分辨率）⇒ 旧轨 csd/轨头已写死，按段级错误收段",
            VideoFmtAction.FAIL_CHANGED,
            videoFmtAction(trackBuilt = true, previousKey = "k1", nextKey = "k2")
        )
    }

    // ------------------------------------------------------------------ 段内只建一轨（结构）

    @Test
    fun `同段只建一次视频轨_重复FORMAT_CHANGED不再次addTrack`() {
        assertEquals(
            "视频轨 addTrack 在段内只许一处（第二处 = 重复建轨，muxer 已启动时是崩溃路径）",
            1,
            occurrences(seg, "mx.addTrack(v.getOutputFormat())").size
        )
        assertTrue(
            "建轨必须经纯判定（videoTrack >= 0 即幂等守卫，同指纹只记日志）",
            seg.contains("when (videoFmtAction(videoTrack >= 0, videoFmtKey, nextKey))")
        )
        val addAt = seg.indexOf("VideoFmtAction.ADD_TRACK ->")
        val trackAt = seg.indexOf("videoTrack = mx.addTrack(v.getOutputFormat())")
        val ignoreAt = seg.indexOf("VideoFmtAction.IGNORE_SAME ->")
        val failAt = seg.indexOf("VideoFmtAction.FAIL_CHANGED ->")
        assertTrue(
            "三臂必须齐全且有序（ADD_TRACK → IGNORE_SAME → FAIL_CHANGED），偏移 $addAt/$ignoreAt/$failAt",
            addAt in 0 until ignoreAt && ignoreAt < failAt
        )
        assertTrue(
            "建轨只许落在 ADD_TRACK 臂内（偏移 $addAt/$trackAt/$ignoreAt）",
            trackAt in (addAt + 1) until ignoreAt
        )
        assertTrue(
            "格式指纹只在建轨成功那一臂写入（重复通知不许覆盖它）",
            seg.indexOf("videoFmtKey = nextKey", addAt) in (addAt + 1) until ignoreAt
        )
        assertTrue(
            "自检第一格（建轨成功）只在真正建轨的那一臂置位",
            seg.indexOf("healthVideoTrackAdded = true", addAt) in (addAt + 1) until ignoreAt
        )
        // 旧缺陷形态不许复现：FORMAT_CHANGED 分支不得以"无条件建轨"开头
        assertFalse(
            "旧形态（FORMAT_CHANGED -> { videoTrack = mx.addTrack }）不许留在分支里",
            seg.contains("INFO_OUTPUT_FORMAT_CHANGED -> { videoTrack = mx.addTrack")
        )
        // 真换格式：段级错误码 + 退出循环（与抽干超时同形，后段照常记账收尾）
        val stepAt = seg.indexOf("engineError = engineError ?:", failAt)
        assertTrue("FAIL_CHANGED 臂必须置引擎错误码", stepAt > failAt)
        assertTrue("FAIL_CHANGED 臂必须退出泵循环（否则继续用旧轨写新格式样本）", seg.indexOf("break", failAt) > stepAt)
        assertTrue(
            "错误码字面量必须是 ENGINE_ERROR:FMT（codeOnly 把字面量遮蔽了，读原文）",
            raw.contains("engineError = engineError ?: \"\${RecordError.ENGINE_ERROR}:FMT\"")
        )
    }

    @Test
    fun `突变自证_建轨复制进忽略臂后只建一轨的尺子必须报红`() {
        val dup = seg.replace(
            "VideoFmtAction.IGNORE_SAME ->",
            "VideoFmtAction.IGNORE_SAME -> { videoTrack = mx.addTrack(v.getOutputFormat()) }"
        )
        assertEquals(
            "突变体必须真的多出一处建轨（否则尺子不是在测它自称在测的东西）",
            2,
            occurrences(dup, "mx.addTrack(v.getOutputFormat())").size
        )
        assertFalse(
            "守卫必须红：1 处 = 通过，2 处 = 重复建轨",
            occurrences(dup, "mx.addTrack(v.getOutputFormat())").size == 1
        )
    }
}