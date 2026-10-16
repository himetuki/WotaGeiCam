package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceFile
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内录轨纯静音检测（数字静音流可见化，2026-10-14；**灵敏化，2026-10-16**）的
 * **纯函数单测 + 桥测试 + 源码结构守卫**。
 *
 * 背景：部分固件（Android 16）的投影音频捕获会间歇性给出纯数字静音流——真机实证内录轨
 * 7.76s、371 包、全程恒定零样本（全零 PCM），环境轨同场正常；内录轨「在却无声」用户只有
 * 录完播放才发现。灵敏化三条硬约束：录制中就要提示；视频保持正常录制（不停不录不碰文件账目）；
 * 走上端小弹窗（既有 tip 通道）。
 *
 * 判据口径（本轮）：内录采集线程只维持一枚"本场是否见过非零样本"的字节级锁存
 * （`AudioFeeder.sawNonZero`，命中即早停；r30 关掉的逐样本 Double RMS **不得回退**）；
 * 引擎泵循环约 1s 低频巡检（本段已喂 ≥5s 且仍无任何非零样本 → 提示），段收尾再兜底判读一次；
 * 提示频次由按场节流锁存收敛（每场首触恒放行、同场 ≥60s 才再弹、每次录制开始复位）。
 *
 * 「尺子自己能红」：每条守卫配突变体自证——拆掉被判据的行，同一把尺必须立刻报红。
 */
class CaptureSilenceVerdictTest {

    // ------------------------------------------------------------------ 判据常量与纯函数

    @Test
    fun `静音判据常量与取值对账`() {
        assertEquals("收尾兜底门槛 2s：启动窗口内的短暂静音不告警", 2_000L, CodecRecorder.CAPTURE_SILENCE_MIN_MS)
        assertEquals(
            "录制中巡检门槛 5s：长于内录样本等待窗 1.5s 与起录自检长窗 3s，起录期不叠报",
            5_000L,
            CodecRecorder.CAPTURE_SILENCE_LIVE_MIN_MS
        )
        assertEquals("巡检节拍约 1s", 1_000L, CodecRecorder.SILENCE_PROBE_INTERVAL_MS)
        assertEquals("同场重弹节流窗 60s（防漏看、防刷屏）", 60_000L, SILENCE_REPROMPT_MS)
    }

    @Test
    fun `captureSilenceVerdict全表单测_在场x时长x非零`() {
        // 全表：hasSamples × 门槛端点 ±1 × sawNonZero；期望 = 在场 && 过门槛 && 未见非零
        for (hasSamples in booleanArrayOf(true, false)) {
            for (durationMs in longArrayOf(1_999L, 2_000L, 2_001L)) {
                for (sawNonZero in booleanArrayOf(true, false)) {
                    assertEquals(
                        "全表失败（hasSamples=$hasSamples durationMs=$durationMs sawNonZero=$sawNonZero）",
                        hasSamples && durationMs >= 2_000L && !sawNonZero,
                        CodecRecorder.captureSilenceVerdict(hasSamples, durationMs, sawNonZero)
                    )
                }
            }
        }
        // 录制中门槛（显式 minMs=5s）：4999 不判、5000 起判（含端点）、无边界的样本仍 false
        val live = CodecRecorder.CAPTURE_SILENCE_LIVE_MIN_MS
        assertFalse(
            "时长不足巡检门槛不判（边界：5000-1）",
            CodecRecorder.captureSilenceVerdict(true, 4_999L, false, live)
        )
        assertTrue(
            "恰在巡检门槛：判读（含端点）",
            CodecRecorder.captureSilenceVerdict(true, 5_000L, false, live)
        )
        assertTrue(
            "真机实证参照：7.76s 全程全零样本 → 判读",
            CodecRecorder.captureSilenceVerdict(true, 7_760L, false, live)
        )
        assertFalse(
            "见过非零：时长再长也不判（本场永久不再提示的判据侧落地）",
            CodecRecorder.captureSilenceVerdict(true, 7_760L, true, live)
        )
        assertFalse(
            "无样本不判（轨不在场另有 NO_SAMPLES 语义）",
            CodecRecorder.captureSilenceVerdict(false, 600_000L, false, live)
        )
    }

    @Test
    fun `fedMsOf换算口径_实喂字节到毫秒`() {
        // 48kHz 立体声帧 4 字节：5000ms = 48000×4×5 = 960000 字节
        assertEquals(5_000L, CodecRecorder.fedMsOf(960_000L, 48_000, 2))
        assertEquals(0L, CodecRecorder.fedMsOf(0L, 48_000, 2))
        // 单声道（帧 2 字节）：44100Hz × 2B × 3s = 264600
        assertEquals(3_000L, CodecRecorder.fedMsOf(264_600L, 44_100, 1))
        // 不足 1ms 向下取整（口径同旧式的整数除法）
        assertEquals(0L, CodecRecorder.fedMsOf(47L, 48_000, 2))
        assertEquals(1L, CodecRecorder.fedMsOf(192L, 48_000, 2))
    }

    // ------------------------------------------------------------------ 按场节流锁存（纯状态）

    @Test
    fun `按场节流锁存语义_首触放行_60s节流_按场复位`() {
        val gate = SilencePromptGate()
        assertTrue("本场首触必须放行", gate.tryFire(10_000L))
        assertFalse("距上次不足 60s 必须拦下（防刷屏）", gate.tryFire(10_000L + 59_999L))
        assertTrue("距上次恰 60s 必须再放行（防漏看，含端点）", gate.tryFire(10_000L + 60_000L))
        assertFalse("再放行后重新进入节流", gate.tryFire(10_000L + 60_000L + 1L))
        gate.resetTake()
        assertTrue("复位（新一场录制）后必须重新按本场首触放行——连录两场都要能提示", gate.tryFire(900_000L))
        gate.resetTake()
        assertTrue("时刻读数 0 也不得把首触误判成节流（fired 与时刻解耦）", gate.tryFire(0L))
    }

    // ------------------------------------------------------------------ 桥测试（判定→该弹/不该弹）

    @Test
    fun `桥_每场一次_同场不叠条_非零后永久停`() {
        // 组合口径 = 真实调用序：判据（在场×时长×非零）× 锁存（节流）→ 弹/不弹
        fun promptNow(verdict: Boolean, gate: SilencePromptGate, nowMs: Long): Boolean =
            verdict && gate.tryFire(nowMs)

        val gate = SilencePromptGate()
        // 第一场：录制中巡检首发（起步 5s 仍全零）→ 段收尾兜底同场不叠条（共用同一"本场已提醒"状态）
        assertTrue("第一场巡检首发必须弹", promptNow(true, gate, 5_000L))
        assertFalse("同场收尾兜底不叠条（节流窗内，同场只提示一次）", promptNow(true, gate, 30_000L))
        // 第二场（引擎 start 已复位）：仍静音要能再提示（旧的会话级 OnceLatch 在此恒漏）
        gate.resetTake()
        assertTrue("连录两场、第二场仍静音必须能再提示", promptNow(true, gate, 1_000_000L))
        // 见过非零：判据侧永停（两个触发点都拿不到 true），这才是"本场永久不再提示"的落地
        assertFalse(
            "见过非零后录制中巡检判据恒 false",
            CodecRecorder.captureSilenceVerdict(
                true, 600_000L, sawNonZero = true, minMs = CodecRecorder.CAPTURE_SILENCE_LIVE_MIN_MS
            )
        )
        assertFalse(
            "见过非零后收尾兜底判据恒 false",
            CodecRecorder.captureSilenceVerdict(true, 600_000L, sawNonZero = true)
        )
        // 这条断言说明"永久停"为什么只能由判据侧保证：节流窗对长场次本来就会再放行
        assertTrue(
            "节流锁存本身不拦长场次（>60s 会再放行）——非零永停必须靠判据 sawNonZero",
            promptNow(true, gate, 1_000_000L + SILENCE_REPROMPT_MS)
        )
    }

    // ------------------------------------------------------------------ 源码结构守卫

    private fun maskedMain(rel: String): String = codeOnly(mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        flatten(bodyOf(maskedMain(rel), funName))

    /**
     * 内录捕获构造块（从 AudioRecord 构造锚点到构造调用收尾，约 400 字符窗）：
     * 守卫的作用域是这一段，不拿整文件判字面量（整文件判法正是上一轮被要求废弃的形态）。
     */
    private fun captureCtorChunk(masked: String): String {
        val at = masked.indexOf("capture.createCaptureAudioRecord(sampleRate, channels)")
        assertTrue("找不到内录捕获构造锚点（守卫失去输入）", at >= 0)
        return masked.substring(at, minOf(masked.length, at + 400))
    }

    /**
     * 录制中巡检窗（结构切片）：起点 = 巡检块自己的时钟读数 `val probeNowMs`，终点 = 停录/换段
     * 分支 `if (stopping || rotatePending)`（= 巡检窗的自然上界，runSegment 里首处即它）。
     *
     * 起点**不许**取 `SILENCE_PROBE_INTERVAL_MS`：它住在节流条件行内，会把时钟读数那一行以及
     * 任何人塞在其后的调用划在窗外（2026-10-16 复核）；收尾兜底里的同形在场表达式落在本窗口
     * 之外——这就是判据必须按窗口切片、不许对全文 contains 的原因。
     */
    private fun probeWindow(seg: String): String {
        val from = seg.indexOf("val probeNowMs")
        assertTrue("找不到巡检窗起点锚点 val probeNowMs（守卫失去输入，不许算通过）", from >= 0)
        val to = seg.indexOf("if (stopping || rotatePending)")
        assertTrue("找不到巡检窗终点锚点（停录/换段分支），或落在了起点之前", to > from)
        return seg.substring(from, to)
    }

    /**
     * 词边界赋值判定：`stopping = …` 与 `stopping=…`（无空格）都必须命中；`stopping == …` 这种
     * 只读比较不算写入（带空格字面量 "stopping =" 的判法对无空格写法有盲区，2026-10-16 复核）。
     */
    private fun assigns(window: String, name: String): Boolean =
        Regex("\\b$name\\b\\s*=(?!=)").containsMatchIn(window)

    /** 巡检窗（纯提示）里不许出现的字面：停录/置错/文件与账目路径 */
    private val probeForbidden = listOf(
        "engineError", "RecordError", "abandonSegment", "closeSegment", "discardCurrent",
        "stopFeeder", "stopCapFeeder", "releaseQuietly",
        "writeSample", "signalEndOfInputStream", "queueAudioEos"
    )

    /** 「巡检窗只许提示」的判定本体（良品与突变体共用同一把尺） */
    private fun probePurityHolds(segText: String): Boolean {
        val w = probeWindow(segText)
        if (!w.contains("reportCaptureSilence()")) return false // 反向对照：不许是空窗口空转
        if (probeForbidden.any { w.contains(it) }) return false
        // 在场状态是巡检的只读输入：赋值一律红（词边界判定，`stopping=true` 同样命中）
        return !assigns(w, "stopping") && !assigns(w, "rotatePending")
    }

    @Test
    fun `内录路不得路由到逐样本RMS_锁存扫描必须早停`() {
        val feeder = maskedMain("record/AudioFeeder.kt")
        // 语义级：逐样本 Double RMS（rmsOf）只允许挂在 needsAmplitude 开关下（pump 是唯一调用点）。
        // 捕获路一旦被路由到 rmsOf，r30（05f0742）已修过的采集线程掉帧风险立刻回归。
        val pump = body("record/AudioFeeder.kt", "pump")
        val guardAt = pump.indexOf("if (needsAmplitude)")
        val rmsAt = pump.indexOf("rmsOf(")
        assertTrue(
            "rmsOf 必须且只能出现在 needsAmplitude 开关内（捕获路不得背逐样本 Double RMS）",
            guardAt in 0 until rmsAt
        )
        // 捕获构造必须显式关掉逐样本 RMS——这就是"内录路不路由到 rmsOf"的接线处
        assertTrue(
            "内录捕获构造必须 needsAmplitude = false（静音判读改由 sawNonZero 锁存供数）",
            captureCtorChunk(feeder).contains("needsAmplitude = false")
        )
        // 突变自证 1：把捕获构造改回开逐样本 RMS，同一把尺必须报红
        val mutatedCtor = maskedMain("record/AudioFeeder.kt")
            .replace("needsAmplitude = false", "needsAmplitude = true")
        assertFalse(
            "把捕获路改回逐样本 RMS 后守卫必须报红",
            captureCtorChunk(mutatedCtor).contains("needsAmplitude = false")
        )
        // 突变自证 2：拆掉 rmsOf 的 needsAmplitude 开关（改无条件调用），路由守卫必须报红
        val mutatedPump = flatten(
            bodyOf(
                maskedMain("record/AudioFeeder.kt")
                    .replace("if (needsAmplitude) rms = rmsOf(copy)", "rms = rmsOf(copy)"),
                "pump"
            )
        )
        val mutatedGuardAt = mutatedPump.indexOf("if (needsAmplitude)")
        val mutatedRmsAt = mutatedPump.indexOf("rmsOf(")
        assertFalse(
            "rmsOf 脱离开关后守卫必须报红",
            mutatedGuardAt in 0 until mutatedRmsAt
        )
    }

    @Test
    fun `见过非零锁存_泵内早停扫描_start复位_drain不复位_峰值RMS退场`() {
        val masked = maskedMain("record/AudioFeeder.kt")
        val pump = body("record/AudioFeeder.kt", "pump")
        assertTrue(
            "泵循环必须把任一非零样本写进锁存（静音判读的数据基础；见到即永久置位）",
            pump.contains("if (!sawNonZero && hasNonZeroByte(copy)) sawNonZero = true")
        )
        val scan = body("record/AudioFeeder.kt", "hasNonZeroByte")
        assertTrue("扫描必须早停：见到第一个非零字节即返回", scan.contains("!= 0) return true"))
        // 锁存是整场会话级：start 复位（feeder 随每场录制新建 ⇒ "每场一次"的落地处），
        // drain（段界）不得复位——跨段巡检与收尾判读共用的就是这份整场账面
        assertTrue(
            "feeder start 必须复位锁存",
            body("record/AudioFeeder.kt", "start").contains("sawNonZero = false")
        )
        assertFalse(
            "drain（段界）不得复位锁存（复位的话跨段静音会从零重记，段尾判读读出假账）",
            body("record/AudioFeeder.kt", "drain").contains("sawNonZero")
        )
        // 突变自证：给 drain 塞进锁存复位，同一把尺必须报红
        val mutatedDrain = flatten(
            bodyOf(
                maskedMain("record/AudioFeeder.kt").replace("fun drain() {", "fun drain() { sawNonZero = false;"),
                "drain"
            )
        )
        assertTrue("给 drain 塞进锁存复位后守卫必须报红", mutatedDrain.contains("sawNonZero"))
        // 峰值 RMS 退场：字段与读数（peakRms/peakAmplitude）都不许再回来（无读者）
        assertFalse(
            "peakRms / peakAmplitude 必须退场（内录路不做逐样本 RMS 后没有读者）",
            masked.contains("peakRms") || masked.contains("peakAmplitude")
        )
    }

    @Test
    fun `录制中巡检接线_泵循环读判据走上报_收尾兜底仍在`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        assertTrue("泵循环必须做低频巡检（节拍常量在场）", seg.contains("SILENCE_PROBE_INTERVAL_MS"))
        assertTrue("巡检必须用录制中门槛（5s）", seg.contains("CAPTURE_SILENCE_LIVE_MIN_MS"))
        assertTrue("巡检必须按实物字节换算时长（与收尾兜底同一换算）", seg.contains("fedMsOf(capCh.fedBytes"))
        assertTrue("巡检必须读本场「见过非零」锁存", seg.contains("cf.sawNonZero"))
        // 巡检的 reportCaptureSilence 必须落在 stop/rotate 分支之前（= 录制中，不是收尾才提示）
        val stopBranchAt = seg.indexOf("if (stopping || rotatePending)")
        val firstReportAt = seg.indexOf("reportCaptureSilence()")
        assertTrue(
            "录制中提示必须排在 stop/rotate 分支之前——排到段尾就等于回到「录完才发现」",
            stopBranchAt >= 0 && firstReportAt in 0 until stopBranchAt
        )
        // 收尾兜底判读仍在（短录音/漏检场景）：恰两处出口，第二处在 stop 分支之后（段收尾区）
        assertEquals("提示出口必须恰好两处（巡检 + 收尾兜底）", 2, Regex("reportCaptureSilence\\(\\)").findAll(seg).count())
        assertTrue("收尾兜底提示必须保留", seg.lastIndexOf("reportCaptureSilence()") > stopBranchAt)
        // 诊断不缩水：收尾行打印 见过非零 + 有效时长 + 判定 三字段（替代旧的 峰值dB/有效/静音）。
        // 字段住在**字符串字面量内部**、body() 用的遮蔽文本会把字面量内容遮掉，所以这里用同一份
        // 遮蔽文本定位 runSegment 花括号区间（遮蔽等长、偏移通用）再回原文切片
        val raw = mainSourceText("record/CodecRecorder.kt")
        val region = regionOf(maskedMain("record/CodecRecorder.kt"), "runSegment")
        val rawSeg = raw.substring(region.start, region.end)
        assertTrue(
            "收尾行必须打印 见过非零=/有效=/静音= 三字段",
            rawSeg.contains("见过非零=") && rawSeg.contains("有效=") && rawSeg.contains("静音=")
        )
        // 突变自证：拆掉巡检上报（第一处 replaceFirst 置 Unit），同一条"在 stop 分支前"判据必须报红
        val mutated = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt").replaceFirst("reportCaptureSilence()", "Unit"),
                "runSegment"
            )
        )
        val mutatedStopAt = mutated.indexOf("if (stopping || rotatePending)")
        val mutatedFirstAt = mutated.indexOf("reportCaptureSilence()")
        assertFalse(
            "拆掉巡检上报后守卫必须报红（首处上报不再落在 stop 分支之前）",
            mutatedStopAt >= 0 && mutatedFirstAt in 0 until mutatedStopAt
        )
    }

    @Test
    fun `巡检窗内不得引入任何停录或置错调用`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        assertTrue("良品：巡检窗必须过（只提示、不写在场状态、不碰文件与账目）", probePurityHolds(seg))
        // 突变自证 1（起点锚点）：把一枚停录调用塞在 `val probeNowMs` 之后、节流条件之前——
        // 这一段正是旧锚点（首个 SILENCE_PROBE_INTERVAL_MS，住在节流条件行内）划在窗外的部分
        val anchorMut = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt").replace(
                    "val probeNowMs = SystemClock.elapsedRealtime()",
                    "val probeNowMs = SystemClock.elapsedRealtime(); stopCapFeeder()"
                ),
                "runSegment"
            )
        )
        assertFalse("锚点上方塞入停录调用后同一把尺必须报红", probePurityHolds(anchorMut))
        val oldAnchorWindow = anchorMut.substring(
            anchorMut.indexOf("SILENCE_PROBE_INTERVAL_MS"),
            anchorMut.indexOf("if (stopping || rotatePending)")
        )
        assertFalse(
            "（对照）旧锚点窗看不见塞进去的停录调用——这正是换锚点的理由，不是等价改写",
            oldAnchorWindow.contains("stopCapFeeder()")
        )
        // 突变自证 2（词边界）：在巡检体内写 `stopping=true`（无空格）——旧字面量判法会放它过去
        val assignMut = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt").replace(
                    "if (probeNowMs - lastSilenceProbeMs >= SILENCE_PROBE_INTERVAL_MS) {",
                    "if (probeNowMs - lastSilenceProbeMs >= SILENCE_PROBE_INTERVAL_MS) { stopping=true"
                ),
                "runSegment"
            )
        )
        assertFalse("无空格写法 stopping=true 后同一把尺必须报红", probePurityHolds(assignMut))
        assertFalse(
            "（对照）带空格字面量判法对 stopping=true 有盲区，所以必须改词边界",
            probeWindow(assignMut).contains("stopping =")
        )
    }

    @Test
    fun `录制中巡检窗必须在轨在场门`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        val window = probeWindow(seg)
        // 反向对照：窗口确实包着巡检上报（不是空窗口空转）
        assertTrue("巡检窗必须真的包含上报调用", window.contains("reportCaptureSilence()"))
        // 在轨在场门（已见真实样本、未废弃、未走失效收尾）必须住在巡检窗内。收尾兜底里有一份
        // **同形表达式**顶着，所以判据只能按窗口切片定位——对全文 contains 的话，删掉巡检里这份
        // 兜底那份照旧命中，尺子恒绿（2026-10-16 复核揪出的正是这条守卫空缺）
        assertTrue(
            "巡检必须在场门内才判读：capCh.hasSample && !capCh.dropped && !capTornDown 必须在巡检窗内",
            window.contains("capCh.hasSample && !capCh.dropped && !capTornDown")
        )
        // 突变自证：把在场门从巡检条件里删掉（条件只剩非空判断）——只动首处（巡检），兜底那份留下
        val mutated = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt").replaceFirst(
                    "if (cf != null && capCh != null && capCh.hasSample && !capCh.dropped && !capTornDown) {",
                    "if (cf != null && capCh != null) {"
                ),
                "runSegment"
            )
        )
        assertFalse(
            "删掉巡检里的在场门后守卫必须报红（兜底那份同形表达式不许替它顶）",
            probeWindow(mutated).contains("capCh.hasSample && !capCh.dropped && !capTornDown")
        )
        assertTrue(
            "（对照）全文 contains 判法对同一突变体充耳不闻——证明按窗口切片是这条守卫的承重设计",
            mutated.contains("capCh.hasSample && !capCh.dropped && !capTornDown")
        )
    }

    @Test
    fun `静音提示经按场节流_每场录制开始复位`() {
        val report = body("record/PlaybackCaptureController.kt", "reportCaptureSilence")
        assertTrue(
            "reportCaptureSilence 必须经按场节流锁存并打 W 级日志",
            report.contains("tryFire") && report.contains("Log.w")
        )
        assertTrue(
            "每次录制开始（CodecRecorder.start）必须复位节流——连录两场都要能提示",
            body("record/CodecRecorder.kt", "start").contains("resetCaptureSilenceTake()")
        )
        // 突变自证 1：拆掉节流门，守卫必须报红
        val mutatedReport = flatten(
            bodyOf(
                maskedMain("record/PlaybackCaptureController.kt")
                    .replace("if (!silenceGate.tryFire(SystemClock.elapsedRealtime())) return", " "),
                "reportCaptureSilence"
            )
        )
        assertFalse("拆掉节流门后守卫必须报红", mutatedReport.contains("tryFire"))
        // 突变自证 2：拆掉按场复位，守卫必须报红
        val mutatedStart = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt").replace("resetCaptureSilenceTake()", "Unit"),
                "start"
            )
        )
        assertFalse("拆掉按场复位后守卫必须报红", mutatedStart.contains("resetCaptureSilenceTake()"))
    }

    @Test
    fun `录制页必须collect静音事件走hint通道_文案可行动且不归因`() {
        val screen = maskedMain("ui/CameraScreen.kt")
        assertTrue(
            "录制页必须 collect capture.captureSilent 并走 hint 通道，提示后读走即清" +
                "（与 trackLost 同款读走即清纪律；本轮 UI 通道原样复用、未变）",
            screen.contains("capture.captureSilent.observed()") && screen.contains("capture.clearCaptureSilence()")
        )
        // 文案资源必须真的登记（丢资源编译会红，显式锁住防资源被挪走后守卫空转）
        val strings = stringsXml().readText(Charsets.UTF_8)
        assertTrue("strings.xml 必须登记 capture_silent_hint", strings.contains("capture_silent_hint"))
        assertFalse(
            "文案不许把成因写死成「系统未放行」（M1-12：没在播/蓝牙路由/音量 0 同证不可分）",
            strings.contains("系统未放行")
        )
        assertTrue("文案必须给可行动指引（确认音乐正在播放等）", strings.contains("音乐正在播放"))
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
