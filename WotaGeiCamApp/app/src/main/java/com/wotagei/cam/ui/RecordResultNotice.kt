package com.wotagei.cam.ui

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.wotagei.cam.R
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.media.formatDuration
import com.wotagei.cam.record.AutoStopRules
import com.wotagei.cam.record.LOSS_NOTICE_MIN_MS
import com.wotagei.cam.record.RecordError
import com.wotagei.cam.record.RecordResult
import com.wotagei.cam.record.rotateStallLostMs
import com.wotagei.cam.record.rotateStallLostSeconds

/**
 * 结果提示里的一句：资源 id + 实参。实参与占位符**一一对应**（资源侧逐字表见
 * `SavedTextDispatchGuardTest`）：无实参的句走单参 `getString(id)`，资源里误留 `%` 也不会炸。
 */
internal data class RecordTextLine(@StringRes val res: Int, val args: List<Any> = emptyList())

/**
 * 「含已保存分段」结果的结果提示**计划**（原因句 + 附注句键组合）。
 * [reason] 是原因句（为什么停了/存了什么；时长只在这里带一次），[notes] 是附注句
 * （保存质量，按列表次序拼接、可任意并列，谁也不盖谁）。
 */
internal data class RecordTextPlan(val reason: RecordTextLine, val notes: List<RecordTextLine>)

/**
 * 录制结果 → 提示计划（**纯函数**，全表测试见 `ui/RecordTextPlanTest`）。
 *
 * @return null = **无产物失败**：走 [recordResultText] 的既有失败文案（一条独立提示，逐字不变），
 *   不归本函数管。非 null = 本次结果**含已保存分段**（干净成功、降级、混合、join 超时快照），
 *   一律走"已保存 + 附注"——这是"录制结果如实化"的呈现规则唯一落点。
 *
 * 规则（用户 2026-10-16 拍板）：
 * 1. 干净成功（error 空）：原因句按 [RecordResult.autoStopReason] 分流（与旧版逐字一致），
 *    附注 = 登记失败 / 降级 / 环境音 / 丢失（次序即契约，登记失败永远排最前）；
 * 2. **有已保存分段但非干净成功**（混合结果 / join 超时快照）：原因句只为"存了几段"——
 *    多段走「已保存 N 段」（[R.string.cam_record_saved_parts]）；单段**沿用现有「已保存 %1$s」
 *    措辞**（不许出现"已保存 1 段"这类口语倒退）；失败码语义转成不吓人的附注
 *    （[partialSavedNote]），诊断原文仍在 [RecordResult.error] 与落盘账目里（可查）；
 *    自动停录原因**降为一条并列短附注**（[autoStopNote]，2026-10-16 收尾批拍板：原因句被计数/
 *    时长占用时"该提醒的照旧"，用户仍能看到"为什么自己停了"，且短句不带时长、不与原因句重复）；
 * 3. 无产物失败：null（既有失败文案通道）。
 */
internal fun recordTextPlan(result: RecordResult): RecordTextPlan? {
    val saved = result.parts.isNotEmpty() || result.ok
    if (!saved) return null
    // 非干净成功 = 有产物但带错误码（混合结果/join 超时快照）；干净成功恒 false
    val partial = result.error != null
    val dur = formatDuration(result.durationMs)
    val reason = when {
        // 有已保存分段但非干净成功且不止一段：原因句只为"存了几段"（单段落 else，沿用现有措辞）
        partial && result.parts.size > 1 ->
            RecordTextLine(R.string.cam_record_saved_parts, listOf(result.parts.size, dur))
        // 自动停录原因句只在**干净成功**时占原因句（用户必须先知道"为什么自己停了"；三条整句
        // 都含「已保存 %1$s」）；非干净成功时它降为短附注（见 notes 里 autoStopNote 那节）
        !partial && result.autoStopReason == AutoStopRules.REASON_BATTERY ->
            RecordTextLine(R.string.cam_record_saved_auto_battery, listOf(dur))
        !partial && result.autoStopReason == AutoStopRules.REASON_STORAGE ->
            RecordTextLine(R.string.cam_record_saved_auto_storage, listOf(dur))
        !partial && result.autoStopReason == AutoStopRules.REASON_CAMERA_LOST ->
            RecordTextLine(R.string.cam_record_saved_auto_camera_lost, listOf(dur))
        else -> RecordTextLine(R.string.cam_record_saved, listOf(dur))
    }
    val notes = buildList {
        // 拼接次序即契约（P3-2）：登记失败排最前——它是唯一"这条成片可能暂时看不见"的提醒，
        // 不许被任何并列附注盖掉；其余并列叠加，谁也不盖谁
        if (result.uncommitted > 0) add(RecordTextLine(R.string.cam_record_saved_uncommitted))
        // 非干净成功且有自动停录原因：原因句已被计数/时长占用，"为什么自己停了"降为短附注
        //（排在任何质量类附注之前——它与登记失败同属"必须让用户看见"的停录原因，不属于质量描述）
        if (partial) autoStopNote(result.autoStopReason)?.let { add(RecordTextLine(it)) }
        // 非干净成功：失败码语义转成不吓人的附注（片已在相册，用户只需知道末尾这段**可能**没保住；
        // 抽干吃满 join 线后泵仍可能正常封段 commit——措辞两头都成立，见 note_tail 资源注释）
        if (partial) partialSavedNote(result.error)?.let { add(RecordTextLine(it)) }
        if (result.degradedCode != null) add(RecordTextLine(R.string.cam_record_saved_degraded))
        if (result.ambientLost) add(RecordTextLine(R.string.cam_record_saved_ambient_lost))
        if (result.lostMs >= LOSS_NOTICE_MIN_MS) {
            add(RecordTextLine(R.string.cam_record_saved_loss, listOf(rotateStallLostSeconds(result.lostMs))))
        }
    }
    return RecordTextPlan(reason, notes)
}

/**
 * 失败码 → 不吓人的**附注**资源（只用于"有已保存分段但非干净成功"；全表测试见 `RecordTextPlanTest`）。
 * 片已经在相册里，用户只需要知道末尾这段**可能**没保住（抽干吃满 join 线后泵仍可能正常封段
 * commit，"未能保存"会是假话——措辞对两种结局都成立）；诊断原文仍在 [RecordResult.error]、
 * 日志与换段受阻落盘账目里可查。换段受阻单列：丢失秒数由 loss 附注承担，这里说"后续画面未能录下"
 * （与失败短文案 `cam_record_rotate_stall_short` 同口径）；其余码（ENGINE_ERROR/STOP_FAILED 等）
 * 一律归"收尾未完成"。null = 没有该说的（本函数只会被非空 error 调到）。
 */
@StringRes
internal fun partialSavedNote(code: String?): Int? = when {
    code == null -> null
    code.startsWith(RecordError.ROTATE_STALL) -> R.string.cam_record_saved_note_rotate
    else -> R.string.cam_record_saved_note_tail
}

/**
 * 自动停录原因 → **短附注**资源（只用于"有已保存分段但非干净成功"；全表测试见 `RecordTextPlanTest`）。
 * 该角的原因句已被「已保存 N 段」/「已保存，时长」占用，"为什么自己停了"降为一条并列短附注——
 * 用户 2026-10-16 拍板"该提醒的照旧"（收尾批）：干净成功时仍走三条整句资源，本函数不会被调到；
 * 短句不带时长（时长只由原因句带一次）、无占位符。null = 没有该说的（只会被非空原因调到）。
 */
@StringRes
internal fun autoStopNote(reason: String?): Int? = when (reason) {
    null -> null
    AutoStopRules.REASON_BATTERY -> R.string.cam_record_saved_auto_battery_short
    AutoStopRules.REASON_STORAGE -> R.string.cam_record_saved_auto_storage_short
    AutoStopRules.REASON_CAMERA_LOST -> R.string.cam_record_saved_auto_camera_lost_short
    else -> null
}

/**
 * 计划里的一句 → 用户可见文本。无实参走单参 `getString(id)`：**不跑 String.format**，
 * 资源里误留 `%` 只会原样渲染、不会抛（带实参而占位符不够才是抛的那一类）。
 */
internal fun Context.recordText(line: RecordTextLine): String =
    if (line.args.isEmpty()) getString(line.res)
    else getString(line.res, *line.args.toTypedArray())

/** 计划 → 整句（原因句 + 附注句叠加；`CameraScreen` 的提示出口只调它） */
internal fun Context.recordText(plan: RecordTextPlan): String = buildString {
    append(recordText(plan.reason))
    for (note in plan.notes) append(recordText(note))
}

/** 录制失败码 → 中文提示：机器码在 record 包，文案按码在此映射 */
internal fun recordResultText(res: Resources, code: String?): String? {
    if (code == null) return null
    val freeMb = code.substringAfter(':', "").toLongOrNull()
    return when {
        code.startsWith(RecordError.LOW_STORAGE) && freeMb != null ->
            res.getString(R.string.cam_record_low_storage, freeMb, WotaTiers.MIN_FREE_MB)
        code.startsWith(RecordError.LOW_STORAGE) -> res.getString(R.string.cam_record_low_storage_short)
        code == RecordError.NO_OUTPUT -> res.getString(R.string.cam_record_no_output)
        code == RecordError.PREPARE_FAILED -> res.getString(R.string.cam_record_prepare_failed)
        code == RecordError.START_FAILED -> res.getString(R.string.cam_record_start_failed)
        code.startsWith(RecordError.ENGINE_ERROR) -> res.getString(R.string.cam_record_engine_error)
        code == RecordError.TOO_SHORT -> res.getString(R.string.cam_record_too_short)
        code == RecordError.STOP_FAILED -> res.getString(R.string.cam_record_stop_failed)
        code == RecordError.RELEASED -> res.getString(R.string.cam_record_released)
        code == RecordError.SELF_CHECK_FAILED -> res.getString(R.string.record_self_check_failed)
        // 换段受阻（2026-10-08 真机缺陷）：码里带"丢失了多少毫秒"的载荷，文案要把它翻成秒——
        // 用户必须知道"内容丢了多久"，这正是此前静默丢几十秒时唯一缺的那条信息。
        // 载荷为 0 = 这一腿不是"慢慢等"而是直接失败（建新段失败），文案改说"后续画面未能录下"：
        // 两种出口（慢 / 败）对用户的含义不同，也不能让"丢失约 1 秒"误导成"只丢了一秒"
        code.startsWith(RecordError.ROTATE_STALL) -> {
            val lost = rotateStallLostMs(code) ?: 0L
            if (lost <= 0L) res.getString(R.string.cam_record_rotate_stall_short)
            else res.getString(R.string.cam_record_rotate_stall, rotateStallLostSeconds(lost))
        }
        else -> res.getString(R.string.cam_record_failed_generic)
    }
}
