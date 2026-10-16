package com.wotagei.cam.record

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
 * 「容器已确认收尾」两枚独立信号与降级呈现的**源码结构守卫**（本轮 keep 判据拆分）。
 *
 * 判据核（[segmentKeepDecision] 全组合表）在 `RecordHealthTest` 钉着；这里钉的是它**吃到的信号**
 * 从哪来、接线有没有断——纯函数判定再对，信号取错（从错误码反推 / 读被清零的计数 / 实参写成
 * 恒 true）都会让"保下已录内容"这条修复整条落空。三件必钉：
 * 1. `muxFinalized` **只能**由 `muxer.stop()` 正常返回置位（放在 catch 置错之后、或别处再置一次，
 *    都会把坏容器标成好的——closeSegmentEngine 的置错是"首错优先"，MUX_CLOSE 会被 DRAIN 吞掉）；
 * 2. `segHadVideoSample` **只能**由视频样本写成功置位（pumpLoop 顶部已把 segVideoWritten 清零，
 *    拿它进判据会把所有段判成零样本）；
 * 3. `containerFinalized` 实参必须由这两枚标志**合成**（防将来被写成恒 true/false）。
 *
 * 外加降级呈现的接线：keep 与错误码并存 ⇒ 记会话降级码；`finish` 按成功呈现（error 置空、
 * 带诊断原文）；UI 走"已保存 + 末尾可能不完整"文案；诊断码进落盘账目（这台 ROM 吞日志）。
 * 「尺子自己能红」：每条判据配突变体（对源码文本做替换再断言；替换不中 = 结构本就不在，同样翻红）。
 */
class DegradedKeepGuardTest {

    private fun codecMasked(): String = codeOnly(mainSourceText("record/CodecRecorder.kt"))

    private fun codecBody(name: String): String = flatten(bodyOf(codecMasked(), name))

    private fun mrBody(name: String): String =
        flatten(bodyOf(codeOnly(mainSourceText("record/MrRecorder.kt")), name))

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

    // ------------------------------------------------------------------ 两枚独立信号

    @Test
    fun `muxFinalized 只能由 muxer_stop 正常返回置位`() {
        val close = codecBody("closeSegmentEngine")
        val stopAt = close.indexOf("m.stop()")
        val trueAt = close.indexOf("muxFinalized = true")
        val errAt = close.indexOf("engineError = engineError ?:")
        assertTrue("锚点丢失：closeSegmentEngine 没截到 m.stop()", stopAt >= 0)
        assertTrue("锚点丢失：closeSegmentEngine 没截到置错行", errAt >= 0)
        assertTrue(
            "muxFinalized 必须由 muxer.stop() 正常返回置位（排在 m.stop() 之后）",
            trueAt > stopAt
        )
        assertTrue(
            "置位必须落在 try 内（排在 catch 的置错之前）——放错位置就把 stop 抛出的坏容器标成好的",
            trueAt in 0 until errAt
        )
        assertEquals(
            "置真点必须唯一（别处再置一次 = 从别的路径反推容器可用）",
            1,
            occurrences(codecMasked(), "muxFinalized = true").size
        )
        // 复位：逐段信号不许跨段/跨会话残留
        for (fn in listOf("prepare", "release", "runSegment", "finish")) {
            assertTrue("$fn 必须复位 muxFinalized（逐段信号跨段残留会放行坏容器）", codecBody(fn).contains("muxFinalized = false"))
        }
        // 突变自证：把置位挪到 m 判空那一行（stop 还没调就置真），位置断言必须报红
        val mutated = flatten(
            bodyOf(codecMasked().replace("if (m != null) {", "if (m != null) { muxFinalized = true"), "closeSegmentEngine")
        )
        assertFalse(
            "置位挪到 m.stop() 之前后位置守卫必须报红",
            mutated.indexOf("muxFinalized = true") > mutated.indexOf("m.stop()")
        )
    }

    @Test
    fun `segHadVideoSample 只能由视频样本写成功置位`() {
        val seg = codecBody("runSegment")
        assertTrue(
            "segHadVideoSample 只能由视频样本写成功（wrote > 0）置位——无条件置位会把零样本段放行",
            seg.contains("if (wrote > 0) segHadVideoSample = true")
        )
        assertEquals(
            "置真点必须唯一",
            1,
            occurrences(codecMasked(), "segHadVideoSample = true").size
        )
        assertTrue("段起始必须复位（逐段信号）", seg.contains("segHadVideoSample = false"))
        for (fn in listOf("prepare", "release", "finish")) {
            assertTrue("$fn 必须复位 segHadVideoSample", codecBody(fn).contains("segHadVideoSample = false"))
        }
        // 突变自证：丢掉写成功前置（无条件置位），判据即报红
        val mutated = flatten(
            bodyOf(codecMasked().replace("if (wrote > 0) segHadVideoSample = true", "segHadVideoSample = true"), "runSegment")
        )
        assertFalse(
            "无条件置位后守卫必须报红",
            mutated.contains("if (wrote > 0) segHadVideoSample = true")
        )
    }

    @Test
    fun `containerFinalized 实参必须由两枚标志合成`() {
        val pump = codecBody("pumpLoop")
        assertTrue("pumpLoop 的封段 keep 必须走 segmentKeepDecision", pump.contains("segmentKeepDecision("))
        assertTrue(
            "containerFinalized 实参必须由 muxFinalized && segHadVideoSample 合成（写成恒 true = 坏容器也放行）",
            pump.contains("containerFinalized = muxFinalized && segHadVideoSample")
        )
        // 突变自证：改成恒 true（任何段都按"容器已收尾"放行），判据必须报红
        val mutated = flatten(
            bodyOf(
                codecMasked().replace("containerFinalized = muxFinalized && segHadVideoSample", "containerFinalized = true"),
                "pumpLoop"
            )
        )
        assertFalse(
            "恒 true 后守卫必须报红",
            mutated.contains("containerFinalized = muxFinalized && segHadVideoSample")
        )
    }

    /**
     * MR 路（[MrRecorder]）行为逐字不变的**结构钉**：它没有 Codec 路的"段循环提前退出"信号
     * （ok）也没有 muxer 收尾信号（containerFinalized），三个封段点必须显式传 `ok = true` +
     * `containerFinalized = false`——等于旧四参判据。谁把这两枚改成别的值（哪怕只是"看起来更严格"），
     * MR 路的保留/删除行为当场变，这条守卫必须红。
     */
    @Test
    fun `MrRecorder 三个封段点必须显式传 ok_true 与 containerFinalized_false`() {
        for (fn in listOf("stop", "release", "rotateSegment")) {
            val b = mrBody(fn)
            assertTrue("$fn 必须走 segmentKeepDecision", b.contains("segmentKeepDecision("))
            assertTrue("$fn 的 ok 必须恒 true（MR 路无段循环失败信号，与旧四参判据等价）", b.contains("ok = true"))
            assertTrue("$fn 的 containerFinalized 必须恒 false（MR 路无 muxer 收尾信号）", b.contains("containerFinalized = false"))
        }
    }

    // ------------------------------------------------------------------ 降级呈现接线

    @Test
    fun `keep 与错误码并存必须记降级码并按成功呈现`() {
        val pump = codecBody("pumpLoop")
        assertTrue(
            "段被保留却带错误码时必须记会话降级码（否则用户看到的仍是「失败」文案）",
            pump.contains("if (keep && engineError != null)") && pump.contains("degradedCode = engineError")
        )
        val finish = codecBody("finish")
        assertTrue(
            "有产物 + 降级码 ⇒ 按成功呈现（error 置空 + degradedCode 带诊断原文）",
            finish.contains("buildResult(null).copy(degradedCode = degraded)")
        )
        assertTrue(
            "降级呈现只认「错误就是那条降级码」（差一字宁可回落失败，绝不盖住更硬的失败）",
            finish.contains("degraded != null && degraded == error")
        )
        // 无产物仍走失败：parts.isEmpty() 分支必须排在降级分支之前
        val emptyAt = finish.indexOf("parts.isEmpty()")
        val degradedAt = finish.indexOf("degraded != null && degraded == error")
        assertTrue("锚点丢失：finish 没截到降级分支", degradedAt >= 0)
        assertTrue("无产物必须仍走失败呈现（parts.isEmpty 分支排在降级分支之前）", emptyAt in 0 until degradedAt)
        // RecordResult 轻量字段：默认 null，其它路径行为不变
        assertTrue(
            "RecordResult 必须有 degradedCode 字段且默认 null（旧构造点行为逐字不变）",
            mainSourceText("record/Recorder.kt").contains("val degradedCode: String? = null")
        )
        // 突变自证：拆掉降级分支（结果退回带错误码 = 失败呈现）
        val mutated = flatten(
            bodyOf(codecMasked().replace("buildResult(null).copy(degradedCode = degraded)", "buildResult(error)"), "finish")
        )
        assertFalse(
            "拆掉降级呈现后守卫必须报红",
            mutated.contains("buildResult(null).copy(degradedCode = degraded)")
        )
    }

    @Test
    fun `降级会话文案与落盘账目必须接线`() {
        // 文案分派自 2026-10-16 起住在呈现计划纯函数里（ui/RecordResultNotice.kt 的 recordTextPlan；
        // CameraScreen 只做渲染）——同一条断言换真源文件，不是弱化
        val plan = mainSourceText("ui/RecordResultNotice.kt")
        assertTrue("结果分派必须接降级文案资源", plan.contains("R.string.cam_record_saved_degraded"))
        assertTrue("降级文案分派必须以 degradedCode 非空为条件", plan.contains("result.degradedCode != null"))
        val screen = mainSourceText("ui/CameraScreen.kt")
        assertTrue(
            "降级会话必须落换段受阻账（这台 ROM 吞日志，诊断码只有账目这条路留得住）",
            screen.contains("out.degradedCode != null")
        )
        val xml = stringsCamera().readText(Charsets.UTF_8)
        assertTrue("资源 cam_record_saved_degraded 必须存在", xml.contains("name=\"cam_record_saved_degraded\""))
        assertTrue(
            "资源文案逐字钉住（P3-1 起是附注**后缀**：时长由原因句带一次，本条不许再带 %1\$s 占位符；" +
                "措辞不许出现「失败」二字）",
            xml.contains(
                "name=\"cam_record_saved_degraded\">；本次收尾未完整结束，成片末尾可能不完整</string>"
            )
        )
        // 突变自证：文案分派条件拆掉（降级段退回普通「已保存」文案，提示丢失）
        val mutated = plan.replace("result.degradedCode != null", "false")
        assertFalse("拆掉降级文案分派条件后守卫必须报红", mutated.contains("result.degradedCode != null"))
    }
}
