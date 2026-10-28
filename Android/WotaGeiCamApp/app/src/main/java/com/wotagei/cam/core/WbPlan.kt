package com.wotagei.cam.core

import com.wotagei.cam.camera.RequestApplier

/**
 * 手动白平衡**走哪条路**的纯决策层（2026-10-28 用户需求：算法兜底 + 运行时提示）。
 *
 * # 为什么需要
 * Camera2 的手动白平衡要 HAL 声明 `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` 才会采纳
 * `COLOR_CORRECTION_GAINS`。[CameraAbility.colorCorrectionModes] 未声明时**不是"没这个功能"**，
 * 而是"增益不会被采纳"：WIKO GAR-AN60 真机实测——`AWB_MODE_OFF` 照做（画面立刻变成传感器原色、
 * 偏绿），但四档色温 2000/5000/5500/10000K 预览**逐像素无差别**（增益被丢）。
 * 于是用户看到的是"选了 5500K 却是绿的"。系统相机能改，是因为它走厂商私有元数据
 * （HAL 日志 `[SetProMode] ... MTK_3A_FEATURE_AWB_REQUEST_VALUE`），第三方拿不到。
 *
 * # 兜底口径
 * 不支持增益时**不再关 AWB**（关掉就只剩原色，正是那片绿），改为：
 * 请求侧留在自动白平衡，画面侧由场景层（GL）乘一组**相对增益**——
 * 5500K 恒等（画面与自动白平衡一致），低于 5500 偏暖、高于 5500 偏冷。
 * 这样"色温滑杆仍然可控"，代价是它相对自动白平衡而非常规意义上的绝对色温，
 * 所以 [WbApply.noticeNeeded] 为真时必须给运行时提示（文案键见 `cam_wb_gl_fallback`）。
 */
data class WbApply(
    /** true = 走 HAL（AWB_MODE_OFF + COLOR_CORRECTION_GAINS），常规路径 */
    val byHal: Boolean,
    /** true = 走画面算法（GL 场景层乘增益），HAL 侧保持自动白平衡 */
    val byGl: Boolean,
    /** GL 侧要乘的相对增益 [R,G,B]；null = 不改画面 */
    val gains: FloatArray?,
    /** true = 必须给用户一次运行时提示（"此设备未声明手动白平衡增益支持"） */
    val noticeNeeded: Boolean
) {
    companion object {
        /** 不做任何白平衡处理（自动/预设档，或手动档被禁用） */
        val NONE = WbApply(byHal = false, byGl = false, gains = null, noticeNeeded = false)
    }
}

/**
 * 决定这一拍白平衡怎么落地。
 *
 * @param manual 用户是否选了手动档（[WbPreset.MANUAL]）
 * @param halSupportsGains [CameraAbility.manualWhiteBalancePossible]：HAL 声明的
 *   colorCorrection 模式里有没有 TRANSFORM_MATRIX
 * @param kelvin 手动档色温（走 HAL 时直接下发；走 GL 时折算成相对增益）
 * @param tint 手动档色调（同上）
 *
 * 相对增益的归一化：以**最大通道为 1.0** 缩放 ⇒ 只改色不改亮度（自动曝光不必因白平衡跳档）。
 */
fun wbApplyPlan(
    manual: Boolean,
    halSupportsGains: Boolean,
    kelvin: Int,
    tint: Int
): WbApply {
    if (!manual) return WbApply.NONE
    if (halSupportsGains) return WbApply(byHal = true, byGl = false, gains = null, noticeNeeded = false)
    val raw = RequestApplier.manualGains(kelvin, tint)
    return WbApply(
        byHal = false,
        byGl = true,
        gains = normalizeMaxToUnit(raw),
        noticeNeeded = true
    )
}

/**
 * 三通道增益归一到"最大通道 = 1.0"：只表达**比例**，不改变整体亮度。
 * 非法输入（空/非有限/全 0）返回 null（调用方据此不做画面处理）。
 */
fun normalizeMaxToUnit(gains: FloatArray?): FloatArray? {
    if (gains == null || gains.size < 3) return null
    if (gains.any { !it.isFinite() || it <= 0f }) return null
    val max = maxOf(gains[0], gains[1], gains[2])
    if (max <= 0f) return null
    return floatArrayOf(gains[0] / max, gains[1] / max, gains[2] / max)
}

/** 这组增益是不是恒等（全 1.0，容差 1e-4）：恒等就别白跑一趟 LUT */
fun isIdentityGains(gains: FloatArray?): Boolean =
    gains == null || (gains.size >= 3 &&
        kotlin.math.abs(gains[0] - 1f) < 1e-4f &&
        kotlin.math.abs(gains[1] - 1f) < 1e-4f &&
        kotlin.math.abs(gains[2] - 1f) < 1e-4f)
