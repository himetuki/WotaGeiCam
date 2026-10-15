package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复链音频直拷**双轨化**的源文守卫（内录体系之后源带双 AAC 音轨：track1=环境麦克、
 * track2=内录 AudioPlaybackCapture，见 `record/CodecRecorder.kt` 双轨体系）。
 *
 * 缺陷本体：离线修复链（帧率标准化）的音频直拷是**单轨**——`findTrack(ex, "audio/")` 命中
 * 即返回只取第一条音轨，内录轨被整体丢掉；25fps GPU 路录的双轨片修成 24fps 后产物只剩环境音。
 *
 * 修复形态：每条 audio/ 轨建一条 `AudioCopyLane`（extractor/format/done/pending/PTS 基/单调
 * 账本/缓冲全在 lane 自带，各配一枚 extractor——同一 extractor 只能 select 一条轨），drain 与
 * done 走聚合。行为测试够不着 MediaCodec/MediaMuxer（JVM 无真身），按本仓守卫口径锁源文结构。
 *
 * 「尺子自己能红」：单轨回潮的四种真实退化（只拷第一条 / 多 lane 共享一枚 extractor / done 只看
 * 第一条 / 枚举前缀筛错轨类）各喂一个突变体，同一把尺必须报红；注释级改动不许引发红。
 */
class ArcRepairAudioLaneGuardTest {

    /** 接线体检：返回违规清单，空 = 双轨直拷接线完整（真身与突变体同吃，本仓既有范式） */
    private fun laneViolations(rawRunner: String): List<String> {
        val bad = ArrayList<String>()
        val runner = KotlinSourceScan.codeOnly(rawRunner)

        // ① open 必须经枚举函数建 lane（findTrack 命中即返回 = 内录轨丢失的缺陷形态）
        val open = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(runner, "open"))
        if (!open.contains("audioTrackIndices(")) bad.add("open 未枚举全部 audio/ 轨建 lane")

        // ② 枚举函数必须按容器轨数全量筛 audio/ 前缀（字面量在原文层复核：遮蔽文本看不到它）
        val enumBody = rawBodyFrom(rawRunner, "audioTrackIndices")
        if (!(enumBody.contains("trackCount") && enumBody.contains("\"audio/\""))) {
            bad.add("枚举未按 audio/ 前缀全量筛轨")
        }

        // ③ 每条 lane 的 extractor 必须在逐轨循环体内新配（挪到循环外 = 多 lane 共享一枚
        //    extractor，而同一 extractor 只能 select 一条轨 = 单轨回潮）
        val lanes = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(runner, "openAudioLanes"))
        val loopAt = lanes.indexOf("forEachIndexed")
        val exAt = lanes.indexOf("MediaExtractor()")
        val laneAt = lanes.indexOf("AudioCopyLane(")
        if (laneAt < 0) bad.add("openAudioLanes 未逐轨建 lane")
        if (!(loopAt in 0 until exAt && exAt < laneAt)) {
            bad.add("extractor 未在逐轨循环内新配（共享 = 单轨回潮）")
        }

        // ④ lane 自带 extractor（状态随 lane 走，不由会话层共享）
        if (!(runner.contains("inner class AudioCopyLane") && runner.contains("val ex: MediaExtractor"))) {
            bad.add("AudioCopyLane 未自带 extractor（每 lane 一枚的约束缺失）")
        }

        // ⑤ drain 必须聚合全部 lane（只推第一条 = 缺陷本体）
        val agg = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(runner, "drainAudioUpTo"))
        if (!agg.contains("for (lane in audioLanes) lane.drainUpTo(limitUs)")) {
            bad.add("drainAudioUpTo 未逐 lane 全量推进")
        }

        // ⑥ done 聚合 = 全 lane 完成（first 单轨判定 = 缺陷回潮）
        val done = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(runner, "audioAllDone"))
        if (!done.contains("audioLanes.all { it.done }")) bad.add("done 未按全 lane 聚合")

        // ⑦ 主循环收尾必须对全部 lane 各 drain（收尾点缺失 = 音频尾巴截断）
        val main = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(runner, "mainLoop"))
        if (!main.contains("drainAudioUpTo(Long.MAX_VALUE)")) bad.add("主循环收尾缺音频聚合 drain")

        return bad
    }

    /** 函数体原文（含字符串字面量）：遮蔽文本与原文逐位等长，区间直接回原文取 */
    private fun rawBodyFrom(raw: String, funName: String): String {
        val r = KotlinSourceScan.regionOf(KotlinSourceScan.codeOnly(raw), funName)
        return raw.substring(r.start + 1, r.end - 1)
    }

    @Test
    fun `双轨直拷接线完整`() {
        val raw = KotlinSourceScan.mainSourceText("record/ArcRepairRunner.kt")
        val bad = laneViolations(raw)
        assertEquals("双轨直拷接线发现断点：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    /**
     * 突变自证：单轨回潮的四种真实退化必报红（文本级突变不编译，只验尺子会红；每个突变体都
     * 改变可观测行为——产物里哪条音轨被写出），对照组只改注释不许引发红。
     */
    @Test
    fun `尺子自己能红_单轨回潮突变体必报红`() {
        val raw = KotlinSourceScan.mainSourceText("record/ArcRepairRunner.kt")
        assertEquals("良品必须先真的绿", 0, laneViolations(raw).size)

        val drainLine = "        for (lane in audioLanes) lane.drainUpTo(limitUs)"
        val loopHead = "        idxs.forEachIndexed { li, ti ->\n            val ex = MediaExtractor()"
        assertTrue("突变锚点丢失：没截到聚合 drain 行", raw.contains(drainLine))
        assertTrue("突变锚点丢失：没截到逐轨循环头", raw.contains(loopHead))

        val mutants = listOf(
            Triple(
                "drain 退回只推第一条 lane（缺陷本体：内录轨永不写出）",
                raw.replace(drainLine, "        audioLanes.firstOrNull()?.drainUpTo(limitUs)"),
                true
            ),
            Triple(
                "extractor 挪到循环外（多 lane 共享一枚，select 互相覆盖 = 单轨回潮）",
                raw.replace(loopHead, "        val ex = MediaExtractor()\n        idxs.forEachIndexed { li, ti ->"),
                true
            ),
            Triple(
                "done 聚合退回只看第一条 lane",
                raw.replace("audioLanes.all { it.done }", "audioLanes.first().done"),
                true
            ),
            Triple(
                "枚举前缀筛成 video/（一条音频 lane 都建不出来 = 音频整体丢失）",
                raw.replace("\"audio/\"", "\"video/\""),
                true
            ),
            // 对照组：只改注释不许引发红
            Triple(
                "对照组：只改注释",
                raw.replace("private fun drainAudioUpTo(limitUs: Long) {", "private fun drainAudioUpTo(limitUs: Long) { // 注释"),
                false
            )
        )
        mutants.forEach { (name, mutated, shouldBeRed) ->
            val bad = laneViolations(mutated)
            if (shouldBeRed) {
                assertTrue("突变体「$name」必须报红（实际违规：${bad.joinToString("；")}）", bad.isNotEmpty())
            } else {
                assertEquals("对照组「$name」不该报红（实际违规：${bad.joinToString("；")}）", 0, bad.size)
            }
        }
    }
}
