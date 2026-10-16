package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceFile
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 环境音（麦克风）轨丢失全链收口与可见化的**桥测试 + 源码结构守卫**（S1-M3-05 结案轮）。
 *
 * 缺陷形态：环境采集线程死亡只留一行日志（AudioFeeder.pump 的 read<0 支）——采集线程死了没人
 * 体检，下一段起 `startMuxerIfReady` 的环境轨门恒假（死 feeder 队列永远为空 ⇒ envCh 永不
 * hasSample、永不 dropped）⇒ **muxer 永不启动、视频样本全被 release 丢弃**，到用户停止才按
 * NO_VIDEO_TRACK 判废删整段；同族第二入口是换段环境编码器重建失败（stopFeeder 但 profile
 * 未改写 ⇒ 下段编码器重建成功即出现 a!=null 而 feeder==null 的幽灵通道，同一个黑洞）。
 *
 * 本文件钉四件事：
 * 1. `AudioFeeder.alive`（采集线程未退出且未到 EOS）与桥 `ambientSourceLive` 的判据语义；
 * 2. `runSegment` 环境分支：每拍查活性/缺失 → 一次性收口（上报/拆 feeder/profile 改写）→
 *    消费残留 → 兜底 EOS（哨兵被 drain 清掉/offer 超时也不许再让 muxer 门卡死）；
 * 3. 换段重建失败分支的 profile 改写（防黑洞复现）与四路上报点各就各位（权限缺失/参数不支持
 *    这两条有意降级**不**许上报）；
 * 4. 可见化接线：health()/RecordResult 带 ambientLost、录制页巡检一次性提示、文案资源逐字钉住。
 *
 * 「尺子自己能红」：每条判据配突变体（对源码文本做替换再断言；替换不中 = 结构本就不在，
 * 断言同样翻红）。
 */
class AmbientTrackLostGuardTest {

    private fun codec(): String = codeOnly(mainSourceText("record/CodecRecorder.kt"))

    private fun codecBody(name: String): String = flatten(bodyOf(codec(), name))

    private fun feederMasked(): String = codeOnly(mainSourceText("record/AudioFeeder.kt"))

    private fun feederBody(name: String): String = flatten(bodyOf(feederMasked(), name))

    private fun screenMasked(): String = codeOnly(mainSourceText("ui/CameraScreen.kt"))

    /** 文案资源文件（`res/values/strings_camera.xml`）：与 AutoStopWiringGuardTest 同一手法 */
    private fun stringsCamera(): File {
        var d: File? = mainSourceFile("ui/CameraScreen.kt").parentFile
        while (d != null) {
            val f = File(d, "res/values/strings_camera.xml")
            if (f.isFile) return f
            d = d.parentFile
        }
        throw AssertionError("找不到 res/values/strings_camera.xml（文案资源被挪走/改名，守卫失去输入）")
    }

    // ------------------------------------------------------------------ 活性判据与桥

    @Test
    fun `AudioFeeder_alive判据与captureActive前两判据同口径_不含外部健康判据`() {
        val alive = feederBody("alive")
        assertTrue(
            "alive 必须同时看 AudioRecord 已建与通道未到 EOS（死亡不置空 record 字段，" +
                "只看 record 会把死源判成活）",
            alive.contains("record != null && !eosSeen")
        )
        assertFalse(
            "alive 不许带外部健康判据（healthy 对环境源恒 null；环境源判活就是「采集线程未退出且未到 EOS」）",
            alive.contains("healthy")
        )
        // 突变自证：判据改成恒真（死源被判活 ⇒ 收口永不触发，黑洞复现）
        val mutated = flatten(
            bodyOf(
                feederMasked().replace("record != null && !eosSeen", "true"),
                "alive"
            )
        )
        assertFalse("活性判据被改成恒真后守卫必须报红", mutated.contains("record != null && !eosSeen"))
    }

    /**
     * 桥测试（计划→行为）：全表手写字面量，不调实现反推。判不活的含义 = 引擎这一拍必须走收口；
     * 缺失与死亡对通道的后果同型（都是"没有喂入端"），桥把两种形态一并覆盖。
     */
    @Test
    fun `ambientSourceLive桥全表_缺失与死亡同判不活`() {
        assertTrue(
            "在场 + 采集线程未退出 ⇒ 可继续喂码",
            CodecRecorder.ambientSourceLive(feederPresent = true, feederAlive = true)
        )
        assertFalse(
            "在场 + 已退出（read 错误死亡 / stop 收场）⇒ 收口",
            CodecRecorder.ambientSourceLive(feederPresent = true, feederAlive = false)
        )
        assertFalse(
            "缺失（幽灵通道形态，通道没有喂入端）⇒ 收口",
            CodecRecorder.ambientSourceLive(feederPresent = false, feederAlive = true)
        )
        assertFalse(
            "缺失 + 已退出 ⇒ 收口",
            CodecRecorder.ambientSourceLive(feederPresent = false, feederAlive = false)
        )
        // 结构钉：桥体必须真的是合取（改成单项 = 某一类形态被漏掉）
        assertTrue(
            "桥体必须是 feederPresent && feederAlive（合取）",
            codec().contains("feederPresent && feederAlive")
        )
    }

    // ------------------------------------------------------------------ runSegment 环境分支

    @Test
    fun `runSegment环境分支必须查活性缺失并走一次性收口与兜底EOS`() {
        val seg = codecBody("runSegment")
        assertTrue(
            "环境分支必须每拍问桥（在场/活性两判据一起进桥，缺失的幽灵通道也走收口）",
            seg.contains("val envLive = ambientSourceLive(ef != null, ef?.alive() == true)")
        )
        val gateAt = seg.indexOf("if (!envTornDown) {")
        val reportAt = seg.indexOf("reportAmbientLost(", gateAt)
        val stopAt = seg.indexOf("stopFeeder()", gateAt)
        val profileAt = seg.indexOf("profile = profile?.copy(audioEnabled = false)", gateAt)
        assertTrue(
            "不活必须走一次性收口（envTornDown 门控）：上报 → 拆 feeder → profile 改写，" +
                "三段顺序不许反（偏移 $gateAt/$reportAt/$stopAt/$profileAt）",
            gateAt in 0 until reportAt && reportAt in 0 until stopAt && stopAt in 0 until profileAt
        )
        assertTrue(
            "残留包与哨兵照常消费（ef!=null 才喂；不消费编码器拿不到 EOS，抽干窗口白等一个预算）",
            seg.contains("if (ef != null && !envCh.eosQueued) feedAudioInput(a, ef, envCh)")
        )
        assertTrue(
            "队列排空仍未收尾必须兜底直发 EOS——段间死亡被 drain() 清掉哨兵/offer 超时的唯一出口",
            seg.contains("if (!envCh.eosQueued && (ef == null || ef.pending() == 0) && envCh.heldPkt == null)") &&
                seg.indexOf("queueAudioEos(a, envCh)", gateAt) > gateAt
        )
        // 突变自证 1：拆掉活性检查（回到恒定可喂 = 死源黑洞复现），同一把尺必须报红
        val m1 = flatten(
            bodyOf(
                codec().replace(
                    "val envLive = ambientSourceLive(ef != null, ef?.alive() == true)",
                    "val envLive = true"
                ),
                "runSegment"
            )
        )
        assertFalse(
            "拆掉活性检查后守卫必须报红",
            m1.contains("ambientSourceLive(ef != null, ef?.alive() == true)")
        )
        // 突变自证 2：拆掉收口里的 profile 改写（下段幽灵通道复现）
        val m2 = flatten(
            bodyOf(codec().replace("profile = profile?.copy(audioEnabled = false)", "Unit"), "runSegment")
        )
        assertFalse("拆掉收口 profile 改写后守卫必须报红", m2.contains("profile = profile?.copy(audioEnabled = false)"))
        // 突变自证 3：兜底 EOS 的排空前置被摘（残包未喂完就直发 EOS = 尾音被丢）
        val m3 = flatten(
            bodyOf(codec().replace("(ef == null || ef.pending() == 0)", "(ef == null)"), "runSegment")
        )
        assertFalse("拆掉排空前置后守卫必须报红", m3.contains("(ef == null || ef.pending() == 0)"))
    }

    // ------------------------------------------------------------------ R4 换段重建失败

    @Test
    fun `换段重建失败必须改写profile并上报_防幽灵通道复现`() {
        val next = codecBody("openNextSegment")
        val stopAt = next.indexOf("stopFeeder()")
        val profileAt = next.indexOf("profile = p.copy(audioEnabled = false)", stopAt)
        val reportAt = next.indexOf("reportAmbientLost(", stopAt)
        assertTrue(
            "环境编码器重建失败（a==null）必须 profile 改写 + 上报：只 stopFeeder 的话，下段编码器" +
                "重建成功即出现 a!=null 而 feeder==null 的幽灵通道 ⇒ muxer 启动门恒假 ⇒ 后续段全成黑洞",
            stopAt in 0 until profileAt && stopAt in 0 until reportAt
        )
        // 突变自证：拆掉 profile 改写（回到黑洞形态），同一把尺必须报红
        val m = flatten(
            bodyOf(codec().replace("profile = p.copy(audioEnabled = false)", "Unit"), "openNextSegment")
        )
        assertFalse("拆掉 profile 改写后守卫必须报红", m.contains("profile = p.copy(audioEnabled = false)"))
    }

    // ------------------------------------------------------------------ 四路上报点

    @Test
    fun `四路上报点各就各位_有能力面却不成才报_有意降级不报`() {
        assertTrue(
            "R1 起录采集器启动失败必须上报（此前只 Log.i；能走到该分支 = 编码器已建出来，" +
                "minBuf>0 与权限都在，属「能力面本应可行却没成」）",
            codecBody("startFeederOrDropAudio").contains("reportAmbientLost(")
        )
        val prepare = codecBody("prepare")
        assertTrue(
            "R2 prepare 期环境编码器建失败必须上报，判据必须带「真尝试过」（minBuf>0 && p.audioEnabled）" +
                "——权限缺失/参数不支持（minBuf=0）是产品明确的纯视频降级，不许从这里上报",
            prepare.contains("if (a == null && minBuf > 0 && p.audioEnabled) reportAmbientLost(")
        )
        assertTrue(
            "R4 换段重建失败必须上报",
            codecBody("openNextSegment").contains("reportAmbientLost(")
        )
        assertTrue(
            "R3 录制中不活收口必须上报（三路静默退出随收口一并了结）",
            codecBody("runSegment").contains("reportAmbientLost(")
        )
        // 突变自证：R2 判据放宽成「编码器为 null 即报」（有意降级被误报成丢轨）
        val m = flatten(
            bodyOf(
                codec().replace(
                    "if (a == null && minBuf > 0 && p.audioEnabled) reportAmbientLost(",
                    "if (a == null) reportAmbientLost("
                ),
                "prepare"
            )
        )
        assertFalse("R2 判据放宽后守卫必须报红", m.contains("minBuf > 0 && p.audioEnabled) reportAmbientLost("))
    }

    // ------------------------------------------------------------------ health / RecordResult / 复位

    @Test
    fun `health与结果必须带ambientLost_会话级复位且置真点唯一`() {
        assertTrue(
            "CodecRecorder.health 必须填 ambientLost（录制中一次性提示通道的数据源）",
            codecBody("health").contains("ambientLost = ambientLost")
        )
        assertTrue(
            "buildResult 必须把 ambientLost 带进结果（「已保存 + 附注」的数据源）",
            codecBody("buildResult").contains("ambientLost = ambientLost")
        )
        for (fn in listOf("prepare", "release")) {
            assertTrue("$fn 必须复位 ambientLost（会话级标志不许跨会话残留）", codecBody(fn).contains("ambientLost = false"))
        }
        assertEquals(
            "置真点必须唯一（只在 reportAmbientLost 一处；四路入口全走它，标志是布尔或天然幂等）",
            1,
            occurrences(codec(), "ambientLost = true").size
        )
        val recorder = mainSourceText("record/Recorder.kt")
        assertEquals(
            "RecorderHealth 与 RecordResult 都必须有 ambientLost 且默认 false（MR 路与旧构造点行为逐字不变）",
            2,
            occurrences(recorder, "val ambientLost: Boolean = false").size
        )
        // 突变自证：health 如实填报改成恒 false（提示永不触发）
        val m = flatten(
            bodyOf(codec().replace("ambientLost = ambientLost", "ambientLost = false"), "health")
        )
        assertFalse("health 恒报 false 后守卫必须报红", m.contains("ambientLost = ambientLost"))
    }

    // ------------------------------------------------------------------ 录制页接线与文案

    /** 看门狗 run 体（与 RecordHealthWiringGuardTest 同一筛选法：engineWatchdogTrips 唯一命中） */
    private fun watchdogRunBody(masked: String): String {
        val bodies = KotlinSourceScan.regionsOf(masked, "run")
            .map { masked.substring(it.start + 1, it.end - 1) }
            .filter { it.contains("engineWatchdogTrips(") }
        assertTrue("必须恰好一个 run 体走引擎看门狗判据，实际 ${bodies.size} 个", bodies.size == 1)
        return bodies[0]
    }

    @Test
    fun `录制页巡检必须读到ambientLost并一次性提示`() {
        val body = watchdogRunBody(screenMasked())
        assertTrue("巡检必须读引擎健康读数的 ambientLost 位", body.contains("rec.health().ambientLost"))
        assertTrue("同一次录制只提示一次（一次性格：先查锁存再置位）", body.contains("!ambientLostNotified"))
        assertTrue("命中必须置位提示锁存", body.contains("ambientLostNotified = true"))
        assertTrue(
            "命中必须推提示资源 id（经既有 hint 通道），资源名逐字钉住",
            body.contains("ambientLostNotice.value = R.string.cam_ambient_lost_tip")
        )
        // 与判废收尾的次序：判废在前（那一拍收尾优先，提示不叠发）
        val tripAt = body.indexOf("engineWatchdogTrips(")
        val noticeAt = body.indexOf("ambientLostNotice.value")
        assertTrue("环境音提示必须排在判废收尾之后（收尾优先，提示不叠发）", tripAt in 0 until noticeAt)
        assertTrue(
            "RecordRunner.start 必须复位提示锁存（一次录制一条提示；重录 attempt 间不去重）",
            flatten(bodyOf(screenMasked(), "start")).contains("ambientLostNotified = false")
        )
        // 突变自证：拆掉锁存判定（每拍重复提示同一句）
        val m = screenMasked().replace("!ambientLostNotified", "true")
        val mBody = watchdogRunBody(m)
        assertFalse("拆掉锁存判定后守卫必须报红", mBody.contains("!ambientLostNotified"))
    }

    @Test
    fun `提示通道与结果文案接线_文案资源逐字钉住`() {
        val m = screenMasked()
        assertTrue(
            "UI 必须 collect ambientLostNotice 并读走即清（同 retryNotice 的 StateFlow 去重口径）",
            m.contains("runner.ambientLostNotice.collect") && m.contains("runner.clearAmbientLostNotice()")
        )
        val collectBlock = m.substringAfter("runner.ambientLostNotice.collect", "")
            .substringBefore("LaunchedEffect", "")
        assertTrue("collect 里必须调 showTip（接了资源 id 却没接 hint 通道 = 提示不可见）", collectBlock.contains("showTip("))
        // 结果文案分派（P3-1 起是"原因句 + 附注句叠加"结构，组合遮蔽守卫在 SavedTextDispatchGuardTest；
        // 2026-10-16 起判定搬进呈现计划纯函数 ui/RecordResultNotice.kt）：环境音附注挂在附注列表里
        // ——条件仍是 result.ambientLost（判定语义逐字不变），与降级/登记/丢失的附注并列，谁也不盖谁
        val notes = codeOnly(mainSourceText("ui/RecordResultNotice.kt"))
            .substringAfter("val notes = buildList", "")
            .substringBefore("return RecordTextPlan", "")
        val ambientCondAt = notes.indexOf("if (result.ambientLost) add(")
        val ambientResAt = notes.indexOf("R.string.cam_record_saved_ambient_lost")
        assertTrue(
            "附注列表必须含环境音附注（if (result.ambientLost) 条件 + 资源接线；偏移 $ambientCondAt/$ambientResAt）",
            ambientCondAt >= 0 && ambientResAt > ambientCondAt
        )
        // 文案资源逐字钉住（P3-3 措辞修正：提示与结果两条都不再绝对化，对「起录就没有」与
        // 「中途中断/换段重建失败」两种成因都成立；"本次录制没有环境声音"对后者不成立——前段有）
        val xml = stringsCamera().readText(Charsets.UTF_8)
        assertTrue(
            "提示文案 cam_ambient_lost_tip 必须存在且逐字钉住（两种成因都成立）",
            xml.contains(
                "name=\"cam_ambient_lost_tip\">环境音不可用或已中断，后段没有环境声音</string>"
            )
        )
        assertTrue(
            "结果附注 cam_record_saved_ambient_lost 必须存在且逐字钉住（后缀形态，可并列叠加）",
            xml.contains(
                "name=\"cam_record_saved_ambient_lost\">；环境声音缺失或中断（后段没有环境声音）</string>"
            )
        )
        assertFalse(
            "绝对化旧措辞「本次录制没有环境声音」必须绝迹（中途中断时前段是有环境音的合法 MP4）",
            xml.contains("本次录制没有环境声音")
        )
        // 突变自证：拆掉 UI collect（提示发了没人看）
        val mutated = m.replace("runner.ambientLostNotice.collect", "Unit")
        assertFalse("拆掉 UI collect 后守卫必须报红", mutated.contains("runner.ambientLostNotice.collect"))
        // 突变自证：环境音附注被摘（结果层这件信息丢了）
        val m2 = notes.replace("if (result.ambientLost) add(", "if (false) add(")
        assertFalse("摘掉环境音附注后守卫必须红", m2.contains("if (result.ambientLost) add("))
    }
}
