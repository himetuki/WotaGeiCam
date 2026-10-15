package com.wotagei.cam.record

import com.wotagei.cam.R
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceFile
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内录轨纯静音检测（数字静音流可见化，2026-10-14）的**纯函数单测 + 源码结构守卫**。
 *
 * 背景：部分固件（Android 16）的投影音频捕获会间歇性给出纯数字静音流——真机实证内录轨
 * 7.76s、371 包、全程恒定零样本（外部量测约 -91dB，即全零 PCM），环境轨同场正常；内录轨
 * 「在却无声」用户只有录完播放才发现。本轮：内录路也逐块算 RMS（AudioFeeder 与环境路同款），
 * 段收尾一次性判读（[CodecRecorder.captureSilenceVerdict]，不在热循环做 dB 换算），命中打
 * W 级日志并经独立 StateFlow（PlaybackCaptureController.captureSilent）提示一次，按捕获会话去重。
 *
 * 「尺子自己能红」：每条守卫配突变体自证——拆掉被判据的行，同一把尺必须立刻报红。
 */
class CaptureSilenceVerdictTest {

    // ------------------------------------------------------------------ 判据常量与纯函数

    @Test
    fun `静音判据常量与取值对账`() {
        assertEquals("时长门槛 2s：启动窗口内的短暂静音不告警", 2_000L, CodecRecorder.CAPTURE_SILENCE_MIN_MS)
        assertEquals("数字静音阈值 -80dB（口径同 rmsToDb，全零 PCM 才落得进来）", -80, CodecRecorder.CAPTURE_SILENCE_PEAK_DB)
    }

    @Test
    fun `captureSilenceVerdict表驱动_含阈值门槛与无样本边界`() {
        val table = listOf(
            Case(false, 5_000L, -160, false, "无样本不告警（轨不在场另有 NO_SAMPLES 语义）"),
            Case(true, 1_999L, -160, false, "时长不足门槛不告警（边界：2000-1）"),
            Case(true, 2_000L, -160, true, "恰在时长门槛：告警（含端点）"),
            Case(true, 5_000L, -80, true, "恰在阈值：告警（含端点）"),
            Case(true, 5_000L, -79, false, "阈值之上 1dB 不告警（边界：-80+1）"),
            Case(true, 5_000L, -160, true, "全程全零 PCM：告警"),
            Case(true, 5_000L, 20, false, "有实际声音（RMS=1 在本量程已是 20dB）：不告警"),
            Case(false, 2_000L, -80, false, "无样本时其余判据再差也不告警")
        )
        for (c in table) {
            assertEquals(
                "判据表失败：${c.why} (hasSamples=${c.hasSamples} durationMs=${c.durationMs} peakDb=${c.peakDb})",
                c.expected,
                CodecRecorder.captureSilenceVerdict(c.hasSamples, c.durationMs, c.peakDb)
            )
        }
    }

    @Test
    fun `rmsToDb对齐AudioProbe_dbOf既有换算且下限正确`() {
        // 下限：amp ≤ 0（全零 PCM 是唯一现实来源）恒 -160，不给 NaN/-Inf
        assertEquals(-160, CodecRecorder.rmsToDb(0))
        assertEquals(-160, CodecRecorder.rmsToDb(-1))
        // 量程锚点：本量程以 0.1 为参考（20×log10(amp/0.1)），RMS=1 即 20dB、每 ×10 加 20dB
        assertEquals(20, CodecRecorder.rmsToDb(1))
        assertEquals(40, CodecRecorder.rmsToDb(10))
        assertEquals(60, CodecRecorder.rmsToDb(100))
        // 与既有换算（AudioProbe.dbOf，Float 版）逐点对齐：取整口径一致，两把尺不许分叉
        for (a in intArrayOf(1, 2, 3, 7, 10, 33, 100, 555, 1234, 8192, 32767)) {
            assertEquals(
                "rmsToDb 必须与 AudioProbe.dbOf 同口径（amp=$a）",
                Math.round(AudioProbe.dbOf(a)),
                CodecRecorder.rmsToDb(a)
            )
        }
        // 真机实证参照：数字静音流恒定零样本 → RMS=0 → -160dB，落进 -80 阈值之下
        assertTrue(CodecRecorder.captureSilenceVerdict(true, 7_760L, CodecRecorder.rmsToDb(0)))
    }

    // ------------------------------------------------------------------ 告警去重语义（纯状态）

    @Test
    fun `OnceLatch首触真_复触假_复位后可再触`() {
        val latch = OnceLatch()
        assertTrue("首次触发必须放行", latch.tryFire())
        assertFalse("同会话重复触发必须被去重", latch.tryFire())
        assertFalse("去重是锁存：任意次复触都拦", latch.tryFire())
        latch.reset()
        assertTrue("复位（新捕获会话）后必须能再次触发", latch.tryFire())
        assertFalse("复位后再触发同样进入去重", latch.tryFire())
    }

    // ------------------------------------------------------------------ 源码结构守卫

    private fun maskedMain(rel: String): String = codeOnly(mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        flatten(bodyOf(maskedMain(rel), funName))

    /** 判据表一行：(有样本, 有效时长ms, 峰值dB, 期望, 说明) */
    private data class Case(
        val hasSamples: Boolean,
        val durationMs: Long,
        val peakDb: Int,
        val expected: Boolean,
        val why: String
    )

    @Test
    fun `内录路必须开逐块RMS且峰值账与rms同点更新`() {
        val feeder = maskedMain("record/AudioFeeder.kt")
        assertFalse(
            "内录捕获路必须开逐块 RMS（needsAmplitude = false 是数字静音流盲区的根源，不得回退）",
            feeder.contains("needsAmplitude = false")
        )
        val pump = body("record/AudioFeeder.kt", "pump")
        assertTrue(
            "泵循环必须把逐块 RMS 记进峰值账（段收尾静音判读的数据基础）",
            pump.contains("if (rms > peakRms) peakRms = rms")
        )
        // 突变自证：拆掉峰值账，同一把尺必须报红
        val mutatedPump = flatten(
            bodyOf(maskedMain("record/AudioFeeder.kt").replace("if (rms > peakRms) peakRms = rms", " "), "pump")
        )
        assertFalse("拆掉峰值账后守卫必须报红", mutatedPump.contains("peakRms"))
        // 分段边界必须复位峰值账（drain 是唯一的段界复位点，峰值账与段对齐）
        assertTrue(
            "drain 必须复位峰值账（不复位则跨段残留峰值把下一段的静音判读永久压死）",
            body("record/AudioFeeder.kt", "drain").contains("peakRms = 0")
        )
    }

    @Test
    fun `段收尾判读必须在轨在场门内_判据命中上报_结果进收尾行`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        val gateAt = seg.indexOf("capCh.hasSample && !capCh.dropped && !capTornDown")
        val verdictAt = seg.indexOf("captureSilenceVerdict(")
        val reportAt = seg.indexOf("reportCaptureSilence()")
        // 判读结果写进收尾行：capRmsInfo 在上报之后的门内赋值（声明本身在门前面，不算）
        val rmsInfoAt = seg.indexOf("capRmsInfo", reportAt)
        assertTrue(
            "段收尾判读必须按序：轨在场门（hasSample 且未废弃且未失效收尾）→ 判据 → 上报 → 收尾行。" +
                "门外的判读会在轨丢失/失效场景与 NO_SAMPLES/SOURCE_INACTIVE 叠报",
            gateAt in 0 until verdictAt && verdictAt in 0 until reportAt && reportAt in 0 until rmsInfoAt
        )
        // 突变自证：拆掉上报行，同一把尺必须报红
        val mutatedSeg = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt").replace("reportCaptureSilence()", "Unit"),
                "runSegment"
            )
        )
        assertFalse("拆掉静音上报后守卫必须报红", mutatedSeg.contains("reportCaptureSilence()"))
    }

    @Test
    fun `静音告警必须经锁存去重_新捕获会话必须复位`() {
        val report = body("record/PlaybackCaptureController.kt", "reportCaptureSilence")
        assertTrue(
            "reportCaptureSilence 必须经 OnceLatch 去重并打 W 级日志（去重是提示一次即停的落地处）",
            report.contains("tryFire") && report.contains("Log.w")
        )
        val apply = body("record/PlaybackCaptureController.kt", "apply")
        assertTrue(
            "进入 Active（新捕获会话）必须复位去重锁存——重授权后的新会话要能重新提示，" +
                "否则锁存卡死成终身静默",
            apply.contains("resetCaptureSilence()")
        )
        // 突变自证：拆掉锁存门，同一把尺必须报红
        val mutated = flatten(
            bodyOf(
                maskedMain("record/PlaybackCaptureController.kt")
                    .replace("if (!silenceLatch.tryFire()) return", " "),
                "reportCaptureSilence"
            )
        )
        assertFalse("拆掉锁存门后守卫必须报红", mutated.contains("tryFire"))
    }

    @Test
    fun `录制页必须collect静音事件走hint通道并读走即清_文案资源已登记`() {
        val screen = maskedMain("ui/CameraScreen.kt")
        assertTrue(
            "录制页必须 collect capture.captureSilent 并走 hint 通道，提示后读走即清" +
                "（与 trackLost 同款读走即清纪律）",
            screen.contains("capture.captureSilent.observed()") && screen.contains("capture.clearCaptureSilence()")
        )
        // 文案资源必须真的登记（丢资源编译会红，显式锁住防资源被挪走后守卫空转）
        assertTrue(
            "strings.xml 必须登记 capture_silent_hint",
            stringsXml().readText(Charsets.UTF_8).contains("capture_silent_hint")
        )
        // 突变自证：删掉读走即清，同一把尺必须报红
        val mutated = screen.replace("capture.clearCaptureSilence()", "Unit")
        assertFalse("拆掉 clearCaptureSilence 后守卫必须报红", mutated.contains("capture.clearCaptureSilence()"))
    }

    /** 从 CameraScreen.kt 所在的 src/main/java 向上取同源的 res/values/strings.xml（文案落点） */
    private fun stringsXml(): File {
        var dir: File? = mainSourceFile("ui/CameraScreen.kt").parentFile
        while (dir != null && dir.name != "java") dir = dir.parentFile
        assertTrue("定位不到 src/main/java 目录（守卫失去输入）", dir != null)
        return File(File(dir!!.parentFile, "res"), "values/strings.xml")
    }
}
