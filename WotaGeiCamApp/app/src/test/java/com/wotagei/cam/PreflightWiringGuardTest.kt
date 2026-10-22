package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预校准接线守卫（重行为在真机，JVM 锁源文结构）：
 * - beginSession 顺序：prepare → 检查点② → preflight → 复查 `stopRequested || pendingStop` → setRecordingTarget；
 * - feedAudioInput 真的把 ptsShiftUs 加进喂编码器的 PTS；
 * - 每段新建的 cap AudioChannel 继承会话级 preCalShiftUs（环境恒 0）。
 *
 * 每条判据都配突变体，证明删掉接线后同一把尺会红。
 */
class PreflightWiringGuardTest {

    private fun masked(rel: String) = codeOnly(mainSourceText(rel))

    private fun beginSessionOrderViolations(body: String): List<String> {
        val b = flatten(body)
        val prepare = b.indexOf("rec.prepare(profile, sink)")
        val cancelDef = b.indexOf("val preflightCancelled = { stopRequested || pendingStop }")
        val preflight = b.indexOf("rec.preflightAudioSync(preflightCancelled)")
        val recheck = b.indexOf("if (preflightCancelled())", if (preflight >= 0) preflight else 0)
        val target = b.indexOf("ctrl.setRecordingTarget(surface)")
        val bad = ArrayList<String>()
        if (prepare < 0) bad.add("找不到 rec.prepare")
        if (cancelDef < 0) bad.add("预校准取消信号必须同时看 stopRequested 与 pendingStop")
        if (preflight < 0) bad.add("beginSession 未调用 rec.preflightAudioSync")
        if (target < 0) bad.add("找不到 ctrl.setRecordingTarget(surface)")
        if (prepare >= 0 && preflight >= 0 && prepare >= preflight) {
            bad.add("预校准必须在 prepare 成功之后")
        }
        if (preflight >= 0 && target >= 0 && preflight >= target) {
            bad.add("预校准必须在编码面交给相机之前（否则正式采集器已起，临时 AudioRecord 抢路）")
        }
        if (preflight < 0 || recheck < 0 || (target >= 0 && recheck >= target)) {
            bad.add("预校准返回后必须再查一次取消信号（PREPARE 点停走 pendingStop，不许白等后还 start）")
        }
        return bad
    }

    @Test
    fun `beginSession预校准顺序`() {
        val body = bodyOf(masked("ui/CameraScreen.kt"), "beginSession")
        assertEquals(
            "预校准接线顺序错误：\n${beginSessionOrderViolations(body).joinToString("\n")}",
            emptyList<String>(),
            beginSessionOrderViolations(body)
        )
        // 突变 1：取消信号退回只看 stopRequested（PREPARE 点停打不断 800ms 采集）
        val mutantLambda = body.replace(
            "val preflightCancelled = { stopRequested || pendingStop }",
            "val preflightCancelled = { stopRequested }"
        )
        assertTrue("取消信号漏 pendingStop 的突变体必须报红", beginSessionOrderViolations(mutantLambda).isNotEmpty())
        // 突变 2：事后不复查（白等后仍 start）
        val mutantRecheck = body.replace("if (preflightCancelled())", "if (false)")
        assertTrue("事后不复查的突变体必须报红", beginSessionOrderViolations(mutantRecheck).isNotEmpty())
    }

    private fun missingShiftWiring(feedBody: String, runBody: String, preflightBody: String): List<String> = listOf(
        feedBody.contains("pkt.ptsUs + ch.ptsShiftUs") to "feedAudioInput 必须把 ptsShiftUs 加进喂编码器的 PTS",
        feedBody.contains("ch.lastPtsUs = ptsUs") to "末包记账必须记平移后的 PTS（EOS 戳不许比样本早）",
        runBody.contains("capCh?.ptsShiftUs = preCalShiftUs") to "每段新建的内录通道必须继承会话级预校准平移",
        preflightBody.contains("AudioPreflightCapture(ctx)") to "预校准必须走 AudioPreflightCapture",
        preflightBody.contains("routeProbe.isA2dpOutput()") to "预校准必须先判 A2DP 路由",
        preflightBody.contains("preCalShiftUs = r.shiftUs") to "预校准结果必须落到 preCalShiftUs"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    @Test
    fun `喂编码器PTS加内录平移`() {
        val codec = masked("record/CodecRecorder.kt")
        val feed = flatten(bodyOf(codec, "feedAudioInput"))
        val run = flatten(bodyOf(codec, "runSegment"))
        val pre = flatten(bodyOf(codec, "preflightAudioSync"))
        assertEquals(
            "内录平移接线断点：\n${missingShiftWiring(feed, run, pre).joinToString("\n")}",
            emptyList<String>(),
            missingShiftWiring(feed, run, pre)
        )
        // 突变：删掉 + ch.ptsShiftUs（预校准测了却不用，成片照旧错位）
        val mutantFeed = feed.replace("pkt.ptsUs + ch.ptsShiftUs", "pkt.ptsUs")
        assertTrue(
            "删平移加法的突变体必须报红",
            missingShiftWiring(mutantFeed, run, pre).isNotEmpty()
        )
        // 突变：段起始不继承 shift（首段后全部退回 0）
        val mutantRun = run.replace("capCh?.ptsShiftUs = preCalShiftUs", "")
        assertTrue(
            "不继承 shift 的突变体必须报红",
            missingShiftWiring(feed, mutantRun, pre).isNotEmpty()
        )
        assertTrue(
            "AudioChannel 必须有 ptsShiftUs 字段（默认 0）",
            codec.contains("var ptsShiftUs = 0L")
        )
        assertFalse("环境通道不得被写非零平移", codec.contains("envCh?.ptsShiftUs = preCalShiftUs"))

        // 临时 feeder 必须在 finally 里 stop（stop 内含 AudioRecord.release），不许留给正式录制
        val capture = flatten(bodyOf(masked("record/AudioPreflight.kt"), "capture"))
        assertTrue("预校准没有 finally 收尾", capture.contains("finally"))
        assertTrue("预校准必须 stop 环境临时 feeder", capture.contains("env.stop()"))
        assertTrue("预校准必须 stop 内录临时 feeder", capture.contains("cap.stop()"))

        // shutdown 清 stopRequested 但**不得**顺手清 pendingStop：PREPARE 点停的意图要在预校准
        // 取消判据里继续可见，否则退页/关停路径会把"用户已点停"抹掉。
        val runner = masked("ui/CameraScreen.kt")
        val shutdown = flatten(bodyOf(runner, "shutdown"))
        assertTrue("shutdown 必须清 stopRequested", shutdown.contains("stopRequested = false"))
        assertFalse("shutdown 不得清 pendingStop", shutdown.contains("pendingStop = false"))
    }
}
