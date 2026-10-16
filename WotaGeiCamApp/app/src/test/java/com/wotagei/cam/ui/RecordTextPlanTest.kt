package com.wotagei.cam.ui

import com.wotagei.cam.R
import com.wotagei.cam.record.AutoStopRules
import com.wotagei.cam.record.RecordError
import com.wotagei.cam.record.RecordResult
import com.wotagei.cam.record.VideoSegment
import com.wotagei.cam.record.rotateStallLostSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「录制结果 → 提示计划」（[recordTextPlan]）与「失败码 → 软附注」（[partialSavedNote]）的
 * **全表单测**（2026-10-16 "录制结果如实化"）。
 *
 * 缺陷形态（主代理逐行核实）：`finish(error)` 在 parts 非空时走 `buildResult(error)`（error 非空
 * ⇒ ok=false）⇒ UI 只认 ok 分支，于是「前几段干净 + 末段因 MUX_CLOSE/零样本被删」也报
 * 「录制失败」，已保存的部分只字不提；join 超时快照路更糟（结果结构性无产物）。
 *
 * 规则（用户 2026-10-16 拍板）：**任何「有已保存分段但非干净成功」**的原因句走「已保存 N 段」
 * （单段沿用现有「已保存 %1$s」措辞——"已保存 1 段"是口语倒退，不许出现）；失败码语义转成
 * 不吓人的附注；登记失败/降级/环境音/丢失附注照旧且可并列（次序即契约：登记失败永远最前）。
 *
 * 期望值一律**手写字面量**，不调被测实现反推（时长走既有 formatDuration 口径：42s = "00:42"）。
 */
class RecordTextPlanTest {

    private fun seg(i: Int, committed: Boolean = true): VideoSegment = VideoSegment(
        uri = null,
        path = "/tmp/p$i.mp4",
        partIndex = i,
        bytes = 100L,
        startMs = i * 1_000L,
        durationMs = 1_000L,
        committed = committed
    )

    /**
     * @param okWithoutParts 防御分支：ok 成立但 parts 为空（两路引擎都不产出这个态；本参数只为
     *   钉住"计划非 null ⇔ 含产物"的契约，与相册刷新条件同源）
     */
    private fun result(
        parts: Int = 0,
        error: String? = null,
        durationMs: Long = 42_000L,
        uncommitted: Int = 0,
        degradedCode: String? = null,
        ambientLost: Boolean = false,
        lostMs: Long = 0L,
        autoStopReason: String? = null,
        okWithoutParts: Boolean = false
    ): RecordResult = RecordResult(
        uri = null,
        path = if (parts > 0 || okWithoutParts) "/tmp/p0.mp4" else null,
        bytes = parts * 100L,
        durationMs = durationMs,
        error = error,
        seriesId = 1L,
        parts = List(parts) { seg(it, committed = it < parts - uncommitted) },
        uncommitted = uncommitted,
        lostMs = lostMs,
        autoStopReason = autoStopReason,
        degradedCode = degradedCode,
        ambientLost = ambientLost
    )

    /** 计划必非 null 的用例取法（null 分支单列：空产物失败走既有失败文案通道） */
    private fun planOf(result: RecordResult): RecordTextPlan =
        recordTextPlan(result) ?: throw AssertionError("有产物结果必须给出提示计划（null = 无产物失败通道）")

    private fun noteRes(plan: RecordTextPlan): List<Int> = plan.notes.map { it.res }

    // ------------------------------------------------------------------ 有已保存分段但非干净成功

    @Test
    fun `多段加错误_原因句已保存N段_失败码转软附注`() {
        val plan = planOf(result(parts = 3, error = "${RecordError.ENGINE_ERROR}:MUX_CLOSE"))
        assertEquals(plan.toString(), R.string.cam_record_saved_parts, plan.reason.res)
        assertEquals("段数与时长（时长由原因句带一次）", listOf<Any>(3, "00:42"), plan.reason.args)
        assertEquals(
            "不吓人的附注：收尾未完成（诊断原文在结果 error/日志/落盘账目里）",
            listOf(R.string.cam_record_saved_note_tail),
            noteRes(plan)
        )
    }

    @Test
    fun `单段加错误_沿用现有已保存措辞_不许出现已保存1段`() {
        val plan = planOf(result(parts = 1, error = "${RecordError.ENGINE_ERROR}:MUX_CLOSE"))
        assertEquals("单段沿用现有「已保存 %1\$s」措辞（不走计数句）", R.string.cam_record_saved, plan.reason.res)
        assertEquals(listOf<Any>("00:42"), plan.reason.args)
        assertEquals(listOf(R.string.cam_record_saved_note_tail), noteRes(plan))
    }

    @Test
    fun `join超时快照_多段加换段受阻_原因句计数_附注说后续画面`() {
        val plan = planOf(
            result(parts = 2, error = "${RecordError.ROTATE_STALL}:8123:SEAL", lostMs = 8_123L)
        )
        assertEquals(R.string.cam_record_saved_parts, plan.reason.res)
        assertEquals(listOf<Any>(2, "00:42"), plan.reason.args)
        assertEquals(
            "换段受阻单列附注 + 丢失秒数照旧（8.123s 四舍五入 8s）",
            listOf(R.string.cam_record_saved_note_rotate, R.string.cam_record_saved_loss),
            noteRes(plan)
        )
        assertEquals(listOf<Any>(rotateStallLostSeconds(8_123L)), plan.notes[1].args)
    }

    @Test
    fun `附注族全命中_次序即契约_登记失败永远最前`() {
        val plan = planOf(
            result(
                parts = 2,
                error = "${RecordError.ENGINE_ERROR}:MUX_CLOSE",
                uncommitted = 1,
                degradedCode = "ENGINE_ERROR:DRAIN",
                ambientLost = true,
                lostMs = 2_000L
            )
        )
        assertEquals(
            "次序：登记失败 → 非干净附注 → 降级 → 环境音 → 丢失（谁也不盖谁）",
            listOf(
                R.string.cam_record_saved_uncommitted,
                R.string.cam_record_saved_note_tail,
                R.string.cam_record_saved_degraded,
                R.string.cam_record_saved_ambient_lost,
                R.string.cam_record_saved_loss
            ),
            noteRes(plan)
        )
    }

    @Test
    fun `多段混合加自动停录_原因句只讲段数_拍板口径`() {
        // 拍板原文："任何「有已保存分段但非干净成功」：原因句走「已保存 N 段」"——多段时
        // 计数句压过自动停录原因句（自动停录原因只在单段/干净成功时占原因句；本角无组合资源，
        // 未获授权不加）。钉在这里防"顺手"改成另一种优先序。
        val plan = planOf(
            result(parts = 2, error = "${RecordError.ENGINE_ERROR}:LOOP", autoStopReason = AutoStopRules.REASON_CAMERA_LOST)
        )
        assertEquals(R.string.cam_record_saved_parts, plan.reason.res)
        assertEquals(listOf<Any>(2, "00:42"), plan.reason.args)
    }

    // ------------------------------------------------------------------ 干净成功（文案不变）

    @Test
    fun `单段干净成功_文案逐字不变`() {
        val plan = planOf(result(parts = 1))
        assertEquals(R.string.cam_record_saved, plan.reason.res)
        assertEquals(listOf<Any>("00:42"), plan.reason.args)
        assertEquals("无附注", emptyList<Int>(), noteRes(plan))
    }

    @Test
    fun `多段干净成功_原因句不变_计数句只给非干净成功`() {
        val plan = planOf(result(parts = 3))
        assertEquals(R.string.cam_record_saved, plan.reason.res)
        assertEquals(emptyList<Int>(), noteRes(plan))
    }

    @Test
    fun `单段加降级_走降级附注_原因句不变`() {
        val plan = planOf(result(parts = 1, degradedCode = "${RecordError.ENGINE_ERROR}:DRAIN"))
        assertEquals(R.string.cam_record_saved, plan.reason.res)
        assertEquals(listOf(R.string.cam_record_saved_degraded), noteRes(plan))
    }

    @Test
    fun `自动停录原因在干净成功时占原因句`() {
        assertEquals(
            R.string.cam_record_saved_auto_battery,
            planOf(result(parts = 1, autoStopReason = AutoStopRules.REASON_BATTERY)).reason.res
        )
        assertEquals(
            R.string.cam_record_saved_auto_storage,
            planOf(result(parts = 1, autoStopReason = AutoStopRules.REASON_STORAGE)).reason.res
        )
        assertEquals(
            R.string.cam_record_saved_auto_camera_lost,
            planOf(result(parts = 2, autoStopReason = AutoStopRules.REASON_CAMERA_LOST)).reason.res
        )
    }

    @Test
    fun `丢失附注门槛与秒数换算照旧`() {
        // 门槛下不出附注（健康换段的良性开销不当提示）；到门槛出附注且带秒数
        assertEquals(emptyList<Int>(), noteRes(planOf(result(parts = 1, lostMs = 999L))))
        val plan = planOf(result(parts = 1, lostMs = 1_500L))
        assertEquals(listOf(R.string.cam_record_saved_loss), noteRes(plan))
        assertEquals(listOf<Any>(rotateStallLostSeconds(1_500L)), plan.notes[0].args)
    }

    @Test
    fun `丢失附注对非干净成功同样出（该提醒的照旧）`() {
        val plan = planOf(result(parts = 2, error = RecordError.STOP_FAILED, lostMs = 3_000L))
        assertEquals(
            listOf(R.string.cam_record_saved_note_tail, R.string.cam_record_saved_loss),
            noteRes(plan)
        )
    }

    // ------------------------------------------------------------------ 无产物失败（通道不变）

    @Test
    fun `空产物加错误_计划为null_走既有失败文案通道`() {
        assertNull(recordTextPlan(result(parts = 0, error = RecordError.TOO_SHORT)))
        assertNull(recordTextPlan(result(parts = 0, error = RecordError.LOW_STORAGE + ":12")))
        assertNull(recordTextPlan(result(parts = 0, error = "${RecordError.ROTATE_STALL}:0")))
    }

    @Test
    fun `防御分支_ok成立即算含产物_与相册刷新条件同源`() {
        // 两路引擎都不产出"ok 但 parts 空"的态；这里钉的是契约：计划非 null ⇔ 含产物
        //（parts 非空或 ok）——CameraScreen 的 repo.invalidate 就挂在这个条件上
        val plan = planOf(result(parts = 0, okWithoutParts = true))
        assertEquals(R.string.cam_record_saved, plan.reason.res)
        assertTrue("含产物结果必须走「已保存」通道", plan.notes.isEmpty())
    }

    // ------------------------------------------------------------------ 失败码 → 软附注 全表

    @Test
    fun `失败码到软附注的映射全表`() {
        assertNull("无码 = 没有该说的", partialSavedNote(null))
        for (code in listOf(
            "${RecordError.ROTATE_STALL}:0",
            "${RecordError.ROTATE_STALL}:8123:SEAL",
            RecordError.ROTATE_STALL
        )) {
            assertEquals(
                "换段受阻族（含载荷变体）→ 后续画面未能录下：$code",
                R.string.cam_record_saved_note_rotate,
                partialSavedNote(code)
            )
        }
        for (code in listOf(
            "${RecordError.ENGINE_ERROR}:MUX_CLOSE",
            "${RecordError.ENGINE_ERROR}:DRAIN",
            RecordError.STOP_FAILED,
            RecordError.TOO_SHORT,
            "${RecordError.LOW_STORAGE}:12",
            RecordError.BAD_STATE
        )) {
            assertEquals("其余码 → 收尾未完成（不吓人的兜底）：$code", R.string.cam_record_saved_note_tail, partialSavedNote(code))
        }
    }
}
