package com.wotagei.cam.ui.anim

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.wotagei.cam.ui.design.WotaEasing
import com.wotagei.cam.ui.design.WotaMotion
import kotlin.math.roundToInt

/** 需求「UI 控制交互动画风格」三档；FLUENT 为默认（短促、有减速、不回弹过头） */
enum class MotionMode { PLAIN, FLUENT, LIQUID }

/**
 * 动画规格集合（06 文档 §3）。
 * 约束：只用于 translation / scale / alpha 三类变换，不参与布局参数动画，避免预览层重排
 * （具名例外只有下面「#81 口径的具名例外」那节登记过的，不许再顺手加）。
 *
 * [timeScale] 是**取证用的时长倍率**（#73 第 1 件的第二个入口，写入方只有 [MergeDebugHook]）：
 * 1f 时下面每一项都与接钩子之前逐字相等，×20 时 FLUENT 的 `WotaMotion.COMMIT_MS` 750ms 变 15s，
 * 慢到这台没有 `screenrecord`、单张 screencap 要 464–541ms 的机器能连拍覆盖整个动画窗口。
 * 时长的**真源仍然是令牌**：这里只乘一个倍率，不新造任何时长数字；倍率本身只在 `debugHook`
 * 变体可能不是 1f（见 [com.wotagei.cam.BuildConfig.MERGE_HOOK] 与 [mergeHookArgsOf]）。
 * 液态档没有固定时长（弹簧），所以倍率落刚度上：除以倍率 = 同样形状、跑得慢 [timeScale] 倍。
 *
 * [reduced] 是**系统减少动效**档（#81 第 4 条，写入方只有 [WotaMotionProvider] 的系统设置读取）：
 * true 时一切动画 0 时长直达、按压形变停用——状态切换仍然发生，只是不再"动"。
 * 缓动统一走 [WotaEasing]（#81 第 3 条：S 型一处定义）；PLAIN 档保持线性（它是"无动画"降级档，
 * 不在观感动效语言之内）。
 *
 * ## #81 口径的具名例外
 * 「不参与布局参数动画」的例外都在这里登记，一个一个具名，不许散落在调用点各说各话。
 * 守卫（MotionHygieneTest）拦的是 animateColorAsState / animateDpAsState / expandIn( / shrinkOut( /
 * tween( 直调，不拦 animateContentSize——所以例外的落地形态只有"调用点用本类具名规格"这一种。
 * - **例外 #2 = Dock 尺寸自适应动画**（[intSize]，10-01 布局批）：用户 2026-10-01 指令明确要求
 *   左右侧 Dock「根据内部控件数量平滑地显示变化宽高」，与 AnimatedContent 那类"容器随内容过渡"
 *   的例外同性质——动的是 Dock 底板自己的测量尺寸，条目在格网里的坐标不受影响；
 *   tween( 直调只发生在本文件。
 */
class MotionSpec(val mode: MotionMode, private val timeScale: Float, private val reduced: Boolean = false) {

    /** 非法倍率（0 / 负数 / NaN）一律当 1f：这条值来自 adb，不许把动画算成 0 时长或无限长 */
    private val scale: Float = if (timeScale.isFinite() && timeScale > 0f) timeScale else 1f

    private fun scaled(ms: Int): Int = if (reduced) 0 else (ms * scale).roundToInt()

    /** FLUENT 档的缓动；减少动效档时长已是 0，缓动无所谓，仍统一给 S 型保持同一份代码路径 */
    private val fluentEasing = if (reduced) LinearEasing else WotaEasing

    /** 标量变换（alpha / scale / 归一化位移） */
    val float: FiniteAnimationSpec<Float> = when (mode) {
        MotionMode.PLAIN -> tween(scaled(PLAIN_MS), easing = LinearEasing)
        MotionMode.FLUENT -> tween(scaled(COMMIT_MS), easing = fluentEasing)
        MotionMode.LIQUID -> if (reduced) tween(0) else spring(dampingRatio = LIQUID_DAMPING, stiffness = Spring.StiffnessLow / scale)
    }

    /** 像素位移（抽屉/面板滑入滑出）；离散类型给零阈值保证动画收尾 */
    val offset: FiniteAnimationSpec<IntOffset> = when (mode) {
        MotionMode.PLAIN -> tween(scaled(PLAIN_MS), easing = LinearEasing)
        MotionMode.FLUENT -> tween(scaled(ENTER_MS), easing = fluentEasing)
        MotionMode.LIQUID -> if (reduced) {
            tween(0, easing = LinearEasing)
        } else {
            spring(
                dampingRatio = LIQUID_DAMPING,
                stiffness = Spring.StiffnessLow / scale,
                visibilityThreshold = IntOffset.Zero
            )
        }
    }

    // ---- #81 第 5/6 条的三档词汇表（进场慢 / 项交互中 / 退出快）。
    // 供 AnimatedVisibility 的 enter/exit 与交互形变按**方向**取用；既有调用点还没换（编排批未做），
    // 在那之前 [float] / [offset] 的时长语义一字不动——MotionSpecScaleTest 钉着这条账。
    val enter: FiniteAnimationSpec<Float> = tween(scaled(WotaMotion.ENTER_MS), easing = fluentEasing)
    val interact: FiniteAnimationSpec<Float> = tween(scaled(WotaMotion.INTERACT_MS), easing = fluentEasing)
    val exit: FiniteAnimationSpec<Float> = tween(scaled(WotaMotion.EXIT_MS), easing = fluentEasing)

    /** [intSize] 的账面时长（JVM 测试口；reduced 档恒 0 = 直达），声明在 [intSize] 之前供其初始化 */
    internal val intSizeDurationMs: Int = scaled(WotaMotion.INTERACT_MS)

    /**
     * Dock 随内容数平滑变化宽高用（10-01 布局批，例外 #2 见类 KDoc）。
     * 各档**统一** INTERACT_MS + S 曲线——用户要的"平滑变化"不按档降级，PLAIN 档也是这段平滑
     * （与 float/offset 的"PLAIN=线性无动画降级"口径刻意不同）；reduced 时 0 时长直达，
     * 状态切换照旧发生、只是不再"动"。
     */
    val intSize: FiniteAnimationSpec<IntSize> = tween(intSizeDurationMs, easing = fluentEasing)

    /** 按钮按下缩放还原量：液态档回弹更明显；减少动效档整个停用（形变也是"动"） */
    val pressScale: Float = when {
        reduced -> 1f
        mode == MotionMode.PLAIN -> 0.98f
        mode == MotionMode.FLUENT -> 0.97f
        else -> 0.96f
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

/**
 * #81 第 4 条：系统「减少动效」偏好——读 `ANIMATOR_DURATION_SCALE` 与 `TRANSITION_ANIMATION_SCALE`，
 * **任一为 0**（用户关掉了动画）就换即时档；0.5 之类"变慢"不算（那仍然是动画）。
 * 进 Provider 时读一次：改了系统设置要重进页面才生效（与动效模式那枚 Watch 同一档容忍度）。
 * 读不到（安全模式/权限异常）按 false 处理——宁可多动，不许把设置页读挂。
 */
@Composable
private fun rememberReducedMotionPreferred(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
                Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

@Composable
fun WotaMotionProvider(mode: MotionMode, content: @Composable () -> Unit) {
    // 倍率来自 #73 的取证钩子：关闭态恒 1f，所以正常渲染下 spec 与接钩子之前是同一份。
    // reduced 来自系统设置（#81 第 4 条）：true 时一切动画 0 时长直达
    val timeScale = MergeDebugHook.timeScale
    val reduced = rememberReducedMotionPreferred()
    val spec = remember(mode, timeScale, reduced) { MotionSpec(mode, timeScale, reduced) }
    CompositionLocalProvider(LocalMotion provides spec, content = content)
}
