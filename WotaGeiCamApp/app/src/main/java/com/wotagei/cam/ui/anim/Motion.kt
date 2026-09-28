package com.wotagei.cam.ui.anim

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.IntOffset
import com.wotagei.cam.ui.design.WotaMotion

/** 需求「UI 控制交互动画风格」三档；FLUENT 为默认（短促、有减速、不回弹过头） */
enum class MotionMode { PLAIN, FLUENT, LIQUID }

/**
 * 动画规格集合（06 文档 §3）。
 * 约束：只用于 translation / scale / alpha 三类变换，不参与布局参数动画，避免预览层重排。
 */
class MotionSpec(val mode: MotionMode) {

    /** 标量变换（alpha / scale / 归一化位移） */
    val float: FiniteAnimationSpec<Float> = when (mode) {
        MotionMode.PLAIN -> tween(PLAIN_MS, easing = LinearEasing)
        MotionMode.FLUENT -> tween(COMMIT_MS, easing = LinearOutSlowInEasing)
        MotionMode.LIQUID -> spring(dampingRatio = LIQUID_DAMPING, stiffness = Spring.StiffnessLow)
    }

    /** 像素位移（抽屉/面板滑入滑出）；离散类型给零阈值保证动画收尾 */
    val offset: FiniteAnimationSpec<IntOffset> = when (mode) {
        MotionMode.PLAIN -> tween(PLAIN_MS, easing = LinearEasing)
        MotionMode.FLUENT -> tween(ENTER_MS, easing = LinearOutSlowInEasing)
        MotionMode.LIQUID -> spring(
            dampingRatio = LIQUID_DAMPING,
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = IntOffset.Zero
        )
    }

    /** 按钮按下缩放还原量：液态档回弹更明显 */
    val pressScale: Float = when (mode) {
        MotionMode.PLAIN -> 0.98f
        MotionMode.FLUENT -> 0.97f
        MotionMode.LIQUID -> 0.96f
    }

    /** 需要固定时长（如淡入淡出提示条）时用 */
    val durationMs: Int = when (mode) {
        MotionMode.PLAIN -> PLAIN_MS
        MotionMode.FLUENT -> COMMIT_MS
        MotionMode.LIQUID -> LIQUID_MS
    }

    companion object {
        private const val PLAIN_MS = 120
        private const val COMMIT_MS = WotaMotion.COMMIT_MS
        private const val ENTER_MS = WotaMotion.ENTER_MS
        private const val LIQUID_MS = 260
        private const val LIQUID_DAMPING = 0.55f
    }
}

/** 未提供 Provider 时按「无动画」降级，保证组件可独立预览 */
val LocalMotion = compositionLocalOf { MotionSpec(MotionMode.PLAIN) }

@Composable
fun WotaMotionProvider(mode: MotionMode, content: @Composable () -> Unit) {
    val spec = remember(mode) { MotionSpec(mode) }
    CompositionLocalProvider(LocalMotion provides spec, content = content)
}
