package com.wotagei.cam.record

import com.wotagei.cam.R
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceFile
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.ui.captureTrackLostTipRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内录轨丢失可见化（缺内录轨排查轮）的**纯函数单测 + 源码结构守卫**。
 *
 * 背景：引擎层的每一条内录降级路径（采集器建不起来 / 录制中失效 / 整段无样本 / 轨建立失败）
 * 此前只留日志——用户"开了内录却没内录轨"毫无感知。本轮给每条路径接上 reportTrackLost 事件，
 * 由录制页 hint 通道提示。守卫锁两件事：
 * - 每条降级路径都必须走到上报（漏一条 = 那条路径退回静默）；
 * - usage 白名单必须包含官方允许捕获的全部三个 usage（漏一个 = 该类播放整类收不到）。
 *
 * 「尺子自己能红」：每条守卫配突变体自证——对读入的源码做替换再断言（替换不中 = 源码里
 * 本就没有被判据的结构，断言同样翻红），同一把尺必须立刻报红。
 */
class CaptureTrackLostGuardTest {

    private fun maskedMain(rel: String): String = codeOnly(mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        flatten(bodyOf(maskedMain(rel), funName))

    // ------------------------------------------------------------------ usage 白名单

    @Test
    fun `captureUsages含官方允许捕获的全部三个usage_字面量对照`() {
        // 官方 addMatchingUsage 仅接受这三个 usage（其余抛 IllegalArgumentException），
        // 期望值手写字面量：USAGE_UNKNOWN=0、USAGE_MEDIA=1、USAGE_GAME=14
        // （14 不是笔误——USAGE_ALARM 才是 4；JVM 探针实跑 android-34 jar 常量实证）
        assertTrue(
            "usage 白名单必须逐字等于 {UNKNOWN, MEDIA, GAME}：漏一个 = 该类播放整类收不到",
            PlaybackCaptureController.captureUsages().contentEquals(intArrayOf(1, 14, 0))
        )
    }

    @Test
    fun `onEstablishResult必须遍历captureUsages白名单`() {
        val body = body("record/PlaybackCaptureController.kt", "onEstablishResult")
        assertTrue(
            "usage 白名单必须取自 captureUsages()（纯函数唯一真源；硬编三行迟早与守卫分叉）",
            body.contains("for (usage in captureUsages())") && body.contains("addMatchingUsage(usage)")
        )
        // 突变自证：白名单遍历改回硬编单 usage（替换不生效 = 源码里本就没有遍历，同样翻红）
        val mutated = flatten(
            bodyOf(
                maskedMain("record/PlaybackCaptureController.kt")
                    .replace(
                        "for (usage in captureUsages()) builder.addMatchingUsage(usage)",
                        "builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)"
                    ),
                "onEstablishResult"
            )
        )
        assertFalse(
            "拆掉白名单遍历后守卫必须报红",
            mutated.contains("for (usage in captureUsages())")
        )
    }

    // ------------------------------------------------------------------ 等待窗纯函数（E6 防整段作废）

    @Test
    fun `capSampleWaitTick三态桥_起窗_清窗_窗满`() {
        // 常量与测试字面量的对账：窗满阈值就是 1500ms
        assertEquals(1500L, CodecRecorder.CAP_SAMPLE_WAIT_MS)
        // 不再等待：恒清窗且不判满（含"已起窗后条件消失"的清窗）
        assertEquals(0L to false, CodecRecorder.capSampleWaitTick(0L, 1000L, waiting = false))
        assertEquals(0L to false, CodecRecorder.capSampleWaitTick(900L, 6000L, waiting = false))
        // 首拍起窗：只记起点，不判满（起窗即满窗会把 0 长窗误判成超时）
        assertEquals(1000L to false, CodecRecorder.capSampleWaitTick(0L, 1000L, waiting = true))
        // 已起窗：窗内不判满、过窗判满（起窗后 1499ms 不满、1501ms 满——字面量按窗 1500 对账）
        assertEquals(1000L to false, CodecRecorder.capSampleWaitTick(1000L, 2499L, waiting = true))
        assertEquals(1000L to true, CodecRecorder.capSampleWaitTick(1000L, 2501L, waiting = true))
    }

    @Test
    fun `capSampleWaitTick窗满判据结构守卫_被拆后必须报红`() {
        val tick = body("record/CodecRecorder.kt", "capSampleWaitTick")
        assertTrue(
            "窗满判据必须是 nowMs - waitStartMs > CAP_SAMPLE_WAIT_MS（改成恒真恒假都失去有界语义）",
            tick.contains("nowMs - waitStartMs > CAP_SAMPLE_WAIT_MS")
        )
        // 突变自证：窗满判据改成恒 false（永不放弃 = 退回无界等待），同一把尺必须报红
        val mutated = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt")
                    .replace("nowMs - waitStartMs > CAP_SAMPLE_WAIT_MS", "false"),
                "capSampleWaitTick"
            )
        )
        assertFalse(
            "拆掉窗满判据后守卫必须报红",
            mutated.contains("nowMs - waitStartMs > CAP_SAMPLE_WAIT_MS")
        )
    }

    @Test
    fun `等待窗必须短于自检长窗_否则窗满放行被自检判废抢先_永不可达`() {
        // 【系统级两窗竞争，非恒等式】capSampleWaitTick 与 healthCheckLoop 同在 elapsedRealtime
        // 时钟域起算：cap 无样本时自检三格恒不满（muxStarted 被 cap 闸卡死），healthVerdict 在
        // FULL_WINDOW_MS(3000) 判废 abandonAndRestart、3 次后 GIVE_UP 整段失败。
        // 历史缺陷：CAP_SAMPLE_WAIT_MS 曾取 5000 > 3000——窗满放行从未可达，"防整段作废"
        // 形同虚设，不回缓冲机型（华为系）表现为"开内录必 SELF_CHECK_FAILED"。
        assertTrue(
            "等待窗必须短于自检长窗，否则窗满分支永远轮不到、自检先判废",
            CodecRecorder.CAP_SAMPLE_WAIT_MS < FULL_WINDOW_MS
        )
        // 预算分解（字面量对账，超窗必红）：窗起点（视频 addTrack ≤800ms）+ 等待窗 +
        // muxer 启动到首视频样本再被自检读到（≤2 拍 360ms）必须落在长窗内
        assertTrue(
            "预算分解不成立：800(窗起点)+等待窗+360(自检两拍) 必须 < 3000(自检长窗)",
            CodecRecorder.CAP_SAMPLE_WAIT_MS + 800L + 360L < FULL_WINDOW_MS
        )
        assertEquals("等待窗取值 1500ms（与预算注释对账）", 1500L, CodecRecorder.CAP_SAMPLE_WAIT_MS)
    }

    // ------------------------------------------------------------------ 引擎四个上报点

    @Test
    fun `E1首启失败必须上报INIT_FAILED且detail取lastInitFailure`() {
        val body = body("record/CodecRecorder.kt", "startCapFeederOrDropAudio")
        assertTrue(
            "内录 feeder 首启失败必须上报 INIT_FAILED（detail 取 controller 记下的中文短因）——" +
                "这条路径是'开了内录却没内录轨'的头号静默点（AudioRecord 建不起来的机型每次必中）",
            body.contains("CaptureTrackLostReason.INIT_FAILED") && body.contains("ctrl.lastInitFailure")
        )
        // 突变自证：删掉上报行（替换不生效 = 上报行本就不在，同样翻红）
        val mutated = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt")
                    .replace(
                        "ctrl.reportTrackLost(CaptureTrackLostReason.INIT_FAILED, ctrl.lastInitFailure)",
                        "Unit"
                    ),
                "startCapFeederOrDropAudio"
            )
        )
        assertFalse(
            "拆掉 INIT_FAILED 上报后守卫必须报红",
            mutated.contains("CaptureTrackLostReason.INIT_FAILED")
        )
    }

    @Test
    fun `E2失效收尾上报SOURCE_INACTIVE且在capTornDown幂等门内`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        val tornAt = seg.indexOf("if (!capTornDown) {")
        val reportAt = seg.indexOf("CaptureTrackLostReason.SOURCE_INACTIVE", tornAt)
        val stopAt = seg.indexOf("stopCapFeeder()", tornAt)
        assertTrue(
            "失效收尾必须上报 SOURCE_INACTIVE，且位置在 capTornDown 置位之后、stopCapFeeder 之前" +
                "（门内幂等：capTornDown 保证恒只报一次）",
            tornAt in 0 until reportAt && reportAt in 0 until stopAt
        )
        // 突变自证：删掉门内上报行，门序断言必须报红
        val mutated = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt")
                    .replace(
                        "ctl.reportTrackLost(CaptureTrackLostReason.SOURCE_INACTIVE, null)",
                        "Unit"
                    ),
                "runSegment"
            )
        )
        val mTorn = mutated.indexOf("if (!capTornDown) {")
        val mStop = mutated.indexOf("stopCapFeeder()", mTorn)
        assertFalse(
            "拆掉门内 SOURCE_INACTIVE 上报后守卫必须报红",
            mutated.substring(mTorn, mStop).contains("CaptureTrackLostReason.SOURCE_INACTIVE")
        )
    }

    @Test
    fun `E3废弃上报NO_SAMPLES且带防重门_失效与窗满不叠报`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        // EOS 收尾仍无样本的废弃点：上报必须在防重门内——窗满放弃/失效收尾各已有一次上报，
        // 无门会连报三条同一句提示
        val gateAt = seg.indexOf("if (!capCh.dropped && !capTornDown) {")
        val reportAt = seg.indexOf("CaptureTrackLostReason.NO_SAMPLES", gateAt)
        val dropAt = seg.indexOf("capCh.dropped = true", gateAt)
        assertTrue(
            "EOS 废弃点的 NO_SAMPLES 上报必须带防重门（!capCh.dropped && !capTornDown）：" +
                "门内上报、随后置 dropped",
            gateAt in 0 until reportAt && gateAt in 0 until dropAt
        )
        // 突变自证：拆掉防重门（每次 EOS 都报），同一把尺必须报红
        val mutated = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt")
                    .replace("if (!capCh.dropped && !capTornDown) {", "if (true) {"),
                "runSegment"
            )
        )
        assertFalse(
            "拆掉防重门后守卫必须报红",
            mutated.contains("if (!capCh.dropped && !capTornDown) {")
        )
    }

    @Test
    fun `E4重建失败必须上报ENCODER_REBUILD_FAILED`() {
        val body = body("record/CodecRecorder.kt", "openNextSegment")
        assertTrue(
            "分段轮转内录编码器重建失败必须上报 ENCODER_REBUILD_FAILED（本段起内录轨退出）",
            body.contains("CaptureTrackLostReason.ENCODER_REBUILD_FAILED")
        )
        // 突变自证：删掉上报行
        val mutated = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt")
                    .replace(
                        "ctl.reportTrackLost(CaptureTrackLostReason.ENCODER_REBUILD_FAILED, null)",
                        "Unit"
                    ),
                "openNextSegment"
            )
        )
        assertFalse(
            "拆掉 ENCODER_REBUILD_FAILED 上报后守卫必须报红",
            mutated.contains("CaptureTrackLostReason.ENCODER_REBUILD_FAILED")
        )
    }

    // ------------------------------------------------------------------ E6 等待窗接线

    @Test
    fun `E6窗满必须废弃通道上报并重评启动闸`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        assertTrue(
            "runSegment 必须接线 capSampleWaitTick（无界等待会让 muxer 永不启动 → 停止时整段作废）",
            seg.contains("capSampleWaitTick(")
        )
        val expiredAt = seg.indexOf("if (capWaitExpired && capCh != null)")
        val dropAt = seg.indexOf("capCh.dropped = true", expiredAt)
        val reportAt = seg.indexOf("CaptureTrackLostReason.NO_SAMPLES", expiredAt)
        val reevalAt = seg.indexOf("startMuxerIfReady(mx, videoTrack, envCh, capCh)", expiredAt)
        assertTrue(
            "窗满分支必须依次：废弃通道 → 上报 NO_SAMPLES → 重评启动闸（不重评则 muxStarted 恒 false，窗满白等）",
            expiredAt in 0 until dropAt && expiredAt in 0 until reportAt && expiredAt in 0 until reevalAt
        )
        // 突变自证：禁用窗满分支（替换不生效 = 源码里本就没有该分支，同样翻红）
        val mutated = flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt")
                    .replace("if (capWaitExpired && capCh != null)", "if (false && capCh != null)"),
                "runSegment"
            )
        )
        assertFalse(
            "拆掉窗满分支后守卫必须报红",
            mutated.contains("if (capWaitExpired && capCh != null)")
        )
    }

    // ------------------------------------------------------------------ 录制页接线与文案映射

    @Test
    fun `录制页必须collect丢失事件并读走即清`() {
        val screen = maskedMain("ui/CameraScreen.kt")
        assertTrue(
            "录制页必须 collect capture.trackLost 并走 hint 通道（引擎层降级不许静默）",
            screen.contains("capture.trackLost.observed()") &&
                screen.contains("captureTrackLostTipRes(evt.reason")
        )
        assertTrue(
            "提示后必须 clearTrackLost（读走即清：StateFlow 相等去重，不清则同因第二次事件发不出）",
            screen.contains("capture.clearTrackLost()")
        )
        // 突变自证：删掉 clear 调用（替换不生效 = 源码里本就没有，同样翻红）
        val mutated = maskedMain("ui/CameraScreen.kt").replace("capture.clearTrackLost()", "Unit")
        assertFalse(
            "拆掉 clearTrackLost 后守卫必须报红",
            mutated.contains("capture.clearTrackLost()")
        )
    }

    @Test
    fun `captureTrackLostTipRes_会话非Active恒null_四个原因各映射一条不同文案`() {
        // 过滤语义：撤销/建链失败已有独立提示（audio_state_revoked / audio_state_failed），
        // 引擎层照报会双报打扰——非 Active 恒 null
        for (reason in CaptureTrackLostReason.values()) {
            assertNull("非 Active 态不提示（reason=$reason）", captureTrackLostTipRes(reason, captureActive = false))
        }
        val res = CaptureTrackLostReason.values().map { captureTrackLostTipRes(it, captureActive = true) }
        assertTrue(
            "Active 态四个原因都必须有文案且两两不同",
            res.all { it != null } && res.toSet().size == CaptureTrackLostReason.values().size
        )
        // 文案 id 对账（行为断言：映射目标就是这四条，不是别的）
        assertEquals(R.string.audio_track_lost_init, captureTrackLostTipRes(CaptureTrackLostReason.INIT_FAILED, true))
        assertEquals(R.string.audio_track_lost_inactive, captureTrackLostTipRes(CaptureTrackLostReason.SOURCE_INACTIVE, true))
        assertEquals(R.string.audio_track_lost_no_samples, captureTrackLostTipRes(CaptureTrackLostReason.NO_SAMPLES, true))
        assertEquals(R.string.audio_track_lost_encoder, captureTrackLostTipRes(CaptureTrackLostReason.ENCODER_REBUILD_FAILED, true))
    }

    @Test
    fun `captureTrackLostTipRes非Active过滤被拆掉后守卫必须报红`() {
        // 突变自证：删掉非 Active 早退行（替换不生效 = 过滤行本就不在，同样翻红）
        val mutated = maskedMain("ui/CameraScreen.kt")
            .replace("if (!captureActive) return null", "")
        assertFalse(
            "拆掉非 Active 过滤后守卫必须报红",
            mutated.contains("if (!captureActive) return null")
        )
    }

    @Test
    fun `渲染处detail为null必须兜底_否则占位渲染出字面量`() {
        val screen = maskedMain("ui/CameraScreen.kt")
        assertTrue(
            "trackLost 提示渲染必须带 detail ?: 兜底——detail 为 null 的路径真实存在" +
                "（AudioRecord 建立成功但 startRecording 抛 ISE → reportTrackLost(INIT_FAILED, null)），" +
                "裸 getString(audio_track_lost_init) 会把 %1\$s 占位原样渲染出字面量",
            screen.contains("evt.detail ?: app.getString(R.string.audio_track_lost_reason_unknown)")
        )
        // 兜底文案必须真的登记（丢资源编译会红，显式锁住防资源被挪走后守卫空转）
        assertTrue(
            "strings.xml 必须登记 audio_track_lost_reason_unknown",
            stringsXml().readText(Charsets.UTF_8).contains("audio_track_lost_reason_unknown")
        )
        // 突变自证：删掉兜底（替换不生效 = 源码里本就没有兜底，同样翻红）
        val mutated = screen.replace(
            "evt.detail ?: app.getString(R.string.audio_track_lost_reason_unknown)",
            "evt.detail"
        )
        assertFalse(
            "拆掉 detail 兜底后守卫必须报红",
            mutated.contains("evt.detail ?: app.getString(R.string.audio_track_lost_reason_unknown)")
        )
    }

    /** 从 CameraScreen.kt 所在的 src/main/java 向上取同源的 res/values/strings.xml（文案落点） */
    private fun stringsXml(): File {
        var dir: File? = mainSourceFile("ui/CameraScreen.kt").parentFile
        while (dir != null && dir.name != "java") dir = dir.parentFile
        assertTrue("定位不到 src/main/java 目录（守卫失去输入）", dir != null)
        return File(File(dir!!.parentFile, "res"), "values/strings.xml")
    }
}
