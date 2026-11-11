package com.wotagei.cam.stance

/**
 * 站位功能对 UI 的中立状态（docs/plan/33 桥接：CameraScreen 只见此类型，
 * 不直接 import flavor 源集的实现类——full/lite 两变体各有一份同 FQ 名的
 * [StanceFeature]（full=包装 Controller 的完整实现，lite=导流桩），
 * 本类型是两份实现共同遵守的对外面。
 */
data class StanceUi(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val lineIntervalMs: Long = 2000L,
    val pollIntervalMs: Long = 4000L,
    val fallbackTip: String? = null,
)
