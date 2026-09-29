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
import kotlin.math.roundToInt

/** 需求「UI 控制交互动画风格」三档；FLUENT 为默认（短促、有减速、不回弹过头） */
enum class MotionMode { PLAIN, FLUENT, LIQUID }

/**
 * 动画规格集合（06 文档 §3）。
 * 约束：只用于 translation / scale / alpha 三类变换，不参与布局参数动画，避免预览层重排。
 *
 * [timeScale] 是**取证用的时长倍率**（#73 第 1 件的第二个入口，写入方只有 [MergeDebugHook]）：
 * 1f 时下面每一项都与接钩子之前逐字相等，×20 时 FLUENT 的 `WotaMotion.COMMIT_MS` 750ms 变 15s，
 * 慢到这台没有 `screenrecord`、单张 screencap 要 464–541ms 的机器能连拍覆盖整个动画窗口。
 * 时长的**真源仍然是令牌**：这里只乘一个倍率，不新造任何时长数字；倍率本身只在 `debugHook`
 * 变体可能不是 1f（见 [com.wotagei.cam.BuildConfig.MERGE_HOOK] 与 [mergeHookArgsOf]）。
 * 液态档没有固定时长（弹簧），所以倍率落刚度上：除以倍率 = 同样形状、跑得慢 [timeScale] 倍。
 */
class MotionSpec(val mode: MotionMode, private val timeScale: Float) {

    /** 非法倍率（0 / 负数 / NaN）一律当 1f：这条值来自 adb，不许把动画算成 0 时长或无限长 */
    private val scale: Float = if (timeScale.isFinite() && timeScale > 0f) timeScale else 1f

    private fun scaled(ms: Int): Int = (ms * scale).roundToInt()

    /** 标量变换（alpha / scale / 归一化位移） */
    val float: FiniteAnimationSpec<Float> = when (mode) {
        MotionMode.PLAIN -> tween(scaled(PLAIN_MS), easing = LinearEasing)
        MotionMode.FLUENT -> tween(scaled(COMMIT_MS), easing = LinearOutSlowInEasing)
        MotionMode.LIQUID -> spring(dampingRatio = LIQUID_DAMPING, stiffness = Spring.StiffnessLow / scale)
    }

    /** 像素位移（抽屉/面板滑入滑出）；离散类型给零阈值保证动画收尾 */
    val offset: FiniteAnimationSpec<IntOffset> = when (mode) {
        MotionMode.PLAIN -> tween(scaled(PLAIN_MS), easing = LinearEasing)
        MotionMode.FLUENT -> tween(scaled(ENTER_MS), easing = LinearOutSlowInEasing)
        MotionMode.LIQUID -> spring(
            dampingRatio = LIQUID_DAMPING,
            stiffness = Spring.StiffnessLow / scale,
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
        MotionMode.PLAIN -> scaled(PLAIN_MS)
        MotionMode.FLUENT -> scaled(COMMIT_MS)
        MotionMode.LIQUID -> scaled(LIQUID_MS)
    }

    companion object {
        private const val PLAIN_MS = 120
        private const val COMMIT_MS = WotaMotion.COMMIT_MS
        private const val ENTER_MS = WotaMotion.ENTER_MS
        private const val LIQUID_MS = 260
        private const val LIQUID_DAMPING = 0.55f
    }
}

/** 未提供 Provider 时按「无动画」降级，保证组件可独立预览；倍率恒 1f（这里没有钩子可读） */
val LocalMotion = compositionLocalOf { MotionSpec(MotionMode.PLAIN, 1f) }

@Composable
fun WotaMotionProvider(mode: MotionMode, content: @Composable () -> Unit) {
    // 倍率来自 #73 的取证钩子：关闭态恒 1f，所以正常渲染下 spec 与接钩子之前是同一份
    val timeScale = MergeDebugHook.timeScale
    val spec = remember(mode, timeScale) { MotionSpec(mode, timeScale) }
    CompositionLocalProvider(LocalMotion provides spec, content = content)
}
