package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
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
 * 重录链**可中断化 + 计时不跳回零**的源码级接线守卫（2026-10-13 真机缺陷）。
 *
 * 缺陷本体（Android 16 设备，内录授权后相机反复 onConfigureFailed）：起录自检判废 →
 * abandonAndRestart → beginSession → 下一轮自检是一条**不可抢占的单 handler 消息链**，链上
 * 阻塞步依次是 createPending(≤3s) → prepare → 挂面等待(≤1.5s×2) → 自检窗口(≤3s) → 再重录。
 * 用户点「停止」只是排队一条 stopWithGrace，最长 ~10s 无响应，且重开计数在 onOpened 归零使
 * 循环永不出头。修复三件：
 * - `stopRequested` 即时取消标志：stopAsync 在 UI 线程先置位再排队，链上检查点见它即让出
 *   （abandonAndRestart 早退 / beginSession 检查点①②③ / await 轮询条件提前打断）；
 * - 预览重开触顶归 Camera2Engine（另见 camera/PreviewReopenCapGuardTest）；
 * - 计时口径：显示直接取引擎真实值，重录跳回 00:00 是有意为之（与成片同口径，2026-10-14
 *   用户裁决；曾试过"基线累加防跳零"，会显示比成片长的数字，已回滚并钉成红线）。
 *
 * 「尺子自己能红」：每条判据配突变自证（对遮蔽后文本做替换再断言；替换不中 = 结构本就不在）。
 */
class StopRequestedInterruptibleGuardTest {

    private val masked by lazy { codeOnly(mainSourceText("ui/CameraScreen.kt")) }

    private fun body(funName: String): String = flatten(bodyOf(masked, funName))

    // ------------------------------------------------------------------ 即时取消标志

    @Test
    fun `stopAsync必须先置stopRequested再排队宽限停止`() {
        val stop = body("stopAsync")
        // 正向锚点：宽限通道必须原样保留（AutoStopWiringGuardTest 也钉这行，双保险）
        assertTrue("锚点丢失：stopAsync 没截到宽限排队", stop.contains("handler.post { stopWithGrace() }"))
        val set = stop.indexOf("stopRequested = true")
        val post = stop.indexOf("handler.post")
        assertTrue(
            "stopAsync 必须置 stopRequested = true——不置位的话重录链检查点永远等不到让出信号，" +
                "点停止仍要等整条链走完（真机 ~10s 无响应回潮）",
            set >= 0
        )
        assertTrue(
            "置位必须先于排队（set=$set post=$post）：信号要赶在链上检查点读到之前生效",
            set in 0 until post
        )
        // 突变自证：摘掉置位后尺子必须红
        val mutated = flatten(bodyOf(masked.replace("stopRequested = true", "Unit"), "stopAsync"))
        assertFalse("摘掉即时置位后尺子必须红", mutated.contains("stopRequested = true"))
    }

    @Test
    fun `abandonAndRestart体内必须有stopRequested早退分支且走stopInternal`() {
        val restart = body("abandonAndRestart")
        assertTrue("锚点丢失：没截到判废弃段收尾", restart.contains("teardownAbandonedSession(rec)"))
        val checkpoint = restart.indexOf("if (stopRequested)")
        val stopCall = restart.indexOf("stopInternal()")
        val begin = restart.indexOf("beginSession()")
        assertTrue(
            "abandonAndRestart 体内必须有 stopRequested 早退分支——用户点停后判废重录不许再起一轮",
            checkpoint >= 0
        )
        assertTrue(
            "早退分支必须走 stopInternal 收尾（teardownAbandonedSession 不改 status，" +
                "需 stopInternal 把 START 收回 IDLE）",
            stopCall > checkpoint
        )
        assertTrue(
            "早退分支必须在 beginSession 重放之前（checkpoint=$checkpoint begin=$begin）——" +
                "让出点越靠前，停止生效越快",
            checkpoint < begin
        )
        // 突变自证：把检查点判据替换掉（回退无条件重录）后尺子必须红
        val mutated = restart.replace("if (stopRequested)", "if (false)")
        assertFalse("摘掉早退分支后尺子必须红", mutated.contains("stopRequested"))
    }

    @Test
    fun `beginSession必须有至少两处stopRequested检查点且返回Boolean`() {
        // 返回值契约：false = 被停止（调用方 start/abandonAndRestart 据此补 stopInternal 收尾）
        assertTrue(
            "beginSession 必须声明返回 Boolean（调用方要靠它区分『真正起录』与『被停止』）",
            masked.contains("private fun beginSession(): Boolean")
        )
        val begin = body("beginSession")
        // 正向锚点：证明切到的是真身（PREPARE 期 pendingStop 语义保留不动）
        assertTrue("锚点丢失：beginSession 没截到 pendingStop 消费点", begin.contains("if (pendingStop)"))
        assertTrue("被停止出口必须 return false（调用方据此收尾）", begin.contains("return false"))
        val checkpoints = occurrences(begin, "if (stopRequested)").size
        assertTrue(
            "beginSession 体内 stopRequested 检查点必须 ≥2 处（createPending 成功后 / prepare 成功后 / " +
                "await 返回后至少占两处），实际 $checkpoints 处——阻塞步之间没有检查点 = 停止要等这一步走完",
            checkpoints >= 2
        )
        // 突变自证：检查点判据全部替换成恒假后计数必须归零（尺子能红）
        val mutated = begin.replace("if (stopRequested)", "if (pendingStop)")
        assertEquals("突变体（检查点被拆）必须让计数归零", 0, occurrences(mutated, "if (stopRequested)").size)
    }

    @Test
    fun `awaitPreview与awaitEncoderSurface轮询条件必须含stopRequested`() {
        val preview = body("awaitPreview")
        assertTrue("锚点丢失：awaitPreview 没截到等待窗", preview.contains("WAIT_MS"))
        assertTrue(
            "awaitPreview 轮询条件必须含 !stopRequested——否则挂面等待窗(≤1.5s)内点停止要干等满窗",
            preview.contains("!stopRequested")
        )
        val encoder = body("awaitEncoderSurface")
        assertTrue("锚点丢失：awaitEncoderSurface 没截到等待窗", encoder.contains("WAIT_MS"))
        assertTrue(
            "awaitEncoderSurface 轮询条件必须含 !stopRequested（同 awaitPreview 口径）",
            encoder.contains("!stopRequested")
        )
        // 突变自证：轮询条件里的取消判据被拆后尺子必须红
        val mutatedPreview = preview.replace("!stopRequested", "")
        val mutatedEncoder = encoder.replace("!stopRequested", "")
        assertFalse("awaitPreview 拆掉取消判据后尺子必须红", mutatedPreview.contains("stopRequested"))
        assertFalse("awaitEncoderSurface 拆掉取消判据后尺子必须红", mutatedEncoder.contains("stopRequested"))
    }

    @Test
    fun `stopRequested三个清零点必须在位`() {
        assertTrue(
            "start() 起新会话必须清 stopRequested（上一会话的取消信号不带进新会话）",
            body("start").contains("stopRequested = false")
        )
        assertTrue(
            "shutdown() 必须清 stopRequested（退页收尾不走检查点，账目要平）",
            body("shutdown").contains("stopRequested = false")
        )
        val stop = body("stopInternal")
        assertTrue(
            "stopInternal 收尾完成必须清 stopRequested（不清的话残留信号会打断下一场起录）",
            stop.contains("stopRequested = false")
        )
        // 突变自证：收尾清账被拆后尺子必须红
        val mutated = stop.replace("stopRequested = false", "Unit")
        assertFalse("stopInternal 清账被拆后尺子必须红", mutated.contains("stopRequested = false"))
    }

    // ------------------------------------------------------------------ 计时口径（用户裁决：与成片同口径）

    @Test
    fun `计时显示直接取引擎真实值_不掺跨attempt基线`() {
        // pollTask 显示值必须是裸 rec.elapsedMs（窗口截法同 AutoStopWiringGuardTest）。
        // 2026-10-14 用户裁决：重录跳回 00:00 是**有意为之**——被弃段不进成片，显示必须与
        // 最终成片时长同口径；曾经的"基线累加防跳零"方案会显示比成片长的数字，用户对不上账
        // 必然再报障，已回滚。这条判据把「显示=成片同口径」钉成红线。
        val pollWindow = masked.substringAfter("val pollTask", missingDelimiterValue = "")
            .substringBefore("private fun maybeAutoStop", missingDelimiterValue = "")
        assertTrue(
            "pollTask 显示必须为 rec.elapsedMs（引擎真实值，与成片同口径）",
            pollWindow.contains("elapsedMs.value = rec.elapsedMs")
        )
        assertFalse(
            "计时显示不许掺任何跨 attempt 基线（elapsedBaseMs 回潮即红——显示会比成片长）",
            pollWindow.contains("elapsedBaseMs")
        )
        // 全文件层面也不许再有基线字段（含弃段处累加、收尾归零的死代码残留）
        assertFalse("elapsedBaseMs 不许回潮", masked.contains("elapsedBaseMs"))
        // 突变自证：有人把显示改成掺基线 → 尺子必须红
        val mutated = masked.replace(
            "elapsedMs.value = rec.elapsedMs",
            "elapsedMs.value = 123L + rec.elapsedMs"
        )
        val mutatedWindow = mutated.substringAfter("val pollTask", "").substringBefore("private fun maybeAutoStop", "")
        assertTrue("显示掺了基线后尺子必须红", !mutatedWindow.contains("elapsedMs.value = rec.elapsedMs"))
    }

    // ------------------------------------------------------------------ 语义红线

    @Test
    fun `failNow失败路径不得被误标为被停止`() {
        // beginSession 的各失败出口（余量不足 / 无输出 / prepare 失败）必须 return true：
        // 它们已自收尾，调用方再补 stopInternal 会砸掉 ERROR 提示窗（80ms 自复位窗口）
        val begin = body("beginSession")
        val failFree = begin.indexOf("failNow(code)")
        val retAfterFree = begin.indexOf("return true", failFree)
        assertTrue("锚点丢失：没截到余量失败出口", failFree >= 0 && retAfterFree > failFree)
        val failOutput = begin.indexOf("failNow(RecordError.NO_OUTPUT)")
        val retAfterOutput = begin.indexOf("return true", failOutput)
        assertTrue("无输出失败出口必须 return true（不许让调用方补 stopInternal）", retAfterOutput > failOutput)
        val failPrepare = begin.indexOf("RecordError.PREPARE_FAILED")
        val retAfterPrepare = begin.indexOf("return true", failPrepare)
        assertTrue("prepare 失败出口必须 return true（同上）", retAfterPrepare > failPrepare)
    }

    @Test
    fun `调用方必须消费beginSession的Boolean返回值`() {
        val start = body("start")
        assertTrue(
            "start 必须按 beginSession 返回值补 stopInternal（false = 被停止，状态要收回 IDLE）",
            start.contains("if (!beginSession()) stopInternal()")
        )
        val restart = body("abandonAndRestart")
        assertTrue(
            "abandonAndRestart 必须按 beginSession 返回值补 stopInternal（同上）",
            restart.contains("if (!beginSession()) stopInternal()")
        )
        // 突变自证：返回值被无视（改回裸调）后尺子必须红
        val mutatedStart = start.replace("if (!beginSession()) stopInternal()", "beginSession()")
        assertFalse("start 无视返回值后尺子必须红", mutatedStart.contains("stopInternal()"))
    }
}
