package com.wotagei.cam.ui.anim

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
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
import kotlin.math.PI
import kotlin.math.roundToInt

/** 需求「UI 控制交互动画风格」三档；FLUENT 为默认（短促、有减速、不回弹过头） */
enum class MotionMode { PLAIN, FLUENT, LIQUID }

/**
 * 「液态Q弹」档的弹簧语言（2026-10-30 改版；用户给定的视觉设计语言）。
 *
 * ## 概念映射（ArkUI → Compose）
 * `curves.springMotion(response, dampingFraction)` 的两枚参数在这里原样保留语义：
 * - **response** = 弹簧的自然振动周期（秒）——决定复位的"跟脚程度"，越小越跟手；
 * - **dampingFraction** = 阻尼比——越低越弹、越高越稳。
 * Compose 的 `spring()` 用 `(dampingRatio, stiffness)` 表达，单位质量下换算为
 * `stiffness = (2π / response)²`。**这条换算是本工程弹簧参数的唯一实现**（纯函数、JVM 可测）：
 * 调用点只许写 response / dampingFraction，不许再各自报刚度常数。
 *
 * ## 取值纪律（用户口径）
 * - response 落 **0.2~0.5s**：0.2 = 按压等跟手反馈，0.3~0.4 = 常规状态切换，0.45~0.5 = 大位移收尾；
 * - dampingFraction 优先 **0.6~0.8**：0.6~0.7 明显回弹（"Q弹"本体），0.8~0.9 克制回弹（面板、按压）。
 * - 越界/非法值一律夹进这两段——参数写错只许"没那么弹"，绝不许弹簧发散或永不成形。
 *
 * ## 速度继承
 * 弹簧只是"能继承速度"的必要条件；真正让【可被用户连续打断的场景（拖拽、页签、滑出）】
 * 从当前运动状态平滑过渡、而不是速度归零后重启的，是 `Animatable.animateTo` 的默认参数
 * `initialVelocity = velocity`。所以交互路径一律用 `Animatable.animateTo(...)` 起跑，
 * 不许换成"snapTo + 重新计时"的写法（那会把离手/连点的速度丢掉）。
 */
internal object LiquidSpring {

    /** 弹簧周期域（秒）：低于 0.2 是硬到没有弹性观感的抖动，高于 0.5 复位拖沓 */
    const val RESPONSE_MIN = 0.2f
    const val RESPONSE_MAX = 0.5f

    /** 阻尼比域：低于 0.6 回弹过剩（怀疑像坏掉），高于 0.9 等于不弹 */
    const val DAMPING_MIN = 0.6f
    const val DAMPING_MAX = 0.9f

    /** response 的机械中位：非法输入（NaN/Inf）退回这里，不猜极端 */
    private const val RESPONSE_FALLBACK = 0.35f
    private const val DAMPING_FALLBACK = 0.7f

    /** `response → 刚度`：单位质量弹簧 ω = 2π / response，stiffness = ω²（夹进 [RESPONSE_MIN]..[RESPONSE_MAX]） */
    fun stiffnessOf(responseSeconds: Float): Float {
        val r = if (responseSeconds.isFinite()) {
            responseSeconds.coerceIn(RESPONSE_MIN, RESPONSE_MAX)
        } else {
            RESPONSE_FALLBACK
        }
        val omega = (2.0 * PI / r).toFloat()
        return omega * omega
    }

    /** `dampingFraction → 阻尼比`（夹进 [DAMPING_MIN]..[DAMPING_MAX]） */
    fun dampingOf(dampingFraction: Float): Float =
        if (dampingFraction.isFinite()) {
            dampingFraction.coerceIn(DAMPING_MIN, DAMPING_MAX)
        } else {
            DAMPING_FALLBACK
        }

    /**
     * 构造弹簧规格。[timeScale] 是取证钩子的倍率（#73）：落在**刚度**上——刚度除以倍率 =
     * 同样的形状、跑得慢 N 倍（弹簧没有"时长"可乘）。
     */
    fun <T> spec(
        responseSeconds: Float,
        dampingFraction: Float,
        timeScale: Float = 1f,
        visibilityThreshold: T? = null
    ): SpringSpec<T> = spring(
        dampingRatio = dampingOf(dampingFraction),
        stiffness = stiffnessOf(responseSeconds) / timeScale,
        visibilityThreshold = visibilityThreshold
    )
}


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
 * - **例外 #2 = 容器随内容过渡的尺寸动画**（[intSize]，10-01 布局批）：用户 2026-10-01 指令明确要求
 *   左右侧 Dock「根据内部控件数量平滑地显示变化宽高」——动的是 Dock 底板自己的测量尺寸，
 *   条目在格网里的坐标不受影响。同族还有内录批 2 的音源管理弹窗（`AnimatedContent` +
 *   `SizeTransform(clip = false)` 接同一份 [intSize]，切页签时弹窗大小随内容量平滑变化），
 *   同性质、共用同一份规格，不另立例外；
 *   tween( 直调只发生在本文件。
 */
class MotionSpec(val mode: MotionMode, private val timeScale: Float, private val reduced: Boolean = false) {

    /** 非法倍率（0 / 负数 / NaN）一律当 1f：这条值来自 adb，不许把动画算成 0 时长或无限长 */
    private val scale: Float = if (timeScale.isFinite() && timeScale > 0f) timeScale else 1f

    private fun scaled(ms: Int): Int = if (reduced) 0 else (ms * scale).roundToInt()

    /** FLUENT 档的缓动；减少动效档时长已是 0，缓动无所谓，仍统一给 S 型保持同一份代码路径 */
    private val fluentEasing = if (reduced) LinearEasing else WotaEasing

    /** 标量变换（alpha / scale / 归一化位移）：液态档走主弹簧（Q弹本体） */
    val float: FiniteAnimationSpec<Float> = when (mode) {
        MotionMode.PLAIN -> tween(scaled(PLAIN_MS), easing = LinearEasing)
        MotionMode.FLUENT -> tween(scaled(COMMIT_MS), easing = fluentEasing)
        MotionMode.LIQUID -> if (reduced) {
            tween(0)
        } else {
            LiquidSpring.spec(
                responseSeconds = LIQUID_FLOAT_RESPONSE,
                dampingFraction = LIQUID_FLOAT_DAMPING,
                timeScale = scale
            )
        }
    }

    /** 像素位移（抽屉/面板滑入滑出）；离散类型给零阈值保证动画收尾 */
    val offset: FiniteAnimationSpec<IntOffset> = when (mode) {
        MotionMode.PLAIN -> tween(scaled(PLAIN_MS), easing = LinearEasing)
        MotionMode.FLUENT -> tween(scaled(ENTER_MS), easing = fluentEasing)
        MotionMode.LIQUID -> if (reduced) {
            tween(0, easing = LinearEasing)
        } else {
            // 大面积位移用更长的周期 + 更高的阻尼：同样是弹簧，但不让整块面板"甩"出画面
            LiquidSpring.spec(
                responseSeconds = LIQUID_OFFSET_RESPONSE,
                dampingFraction = LIQUID_OFFSET_DAMPING,
                timeScale = scale,
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

    /**
     * 按压反馈（按下收缩 / 松开回弹）：**幅度小、不许夸张回弹**（用户口径）。
     * 液态档给"响应优先"的弹簧（response 0.2、damping 0.9：跟手、按下即到、几乎不复弹）——
     * 回弹的"手感"来自阻尼与初速度的配合，不靠拉长时长制造。
     * 其余档沿用 S 曲线短停；减少动效档 0 时长（形变也是"动"）。
     */
    val press: FiniteAnimationSpec<Float> = when (mode) {
        MotionMode.LIQUID -> if (reduced) {
            tween(0)
        } else {
            LiquidSpring.spec(
                responseSeconds = LIQUID_PRESS_RESPONSE,
                dampingFraction = LIQUID_PRESS_DAMPING,
                timeScale = scale
            )
        }
        else -> tween(scaled(WotaMotion.INTERACT_MS), easing = fluentEasing)
    }

    /** 按钮按下缩放还原量：幅度克制（液态 0.97 / 流畅 0.97 / 无动画 0.98）；减少动效档整个停用 */
    val pressScale: Float = when {
        reduced -> 1f
        mode == MotionMode.PLAIN -> 0.98f
        else -> 0.97f
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

        // ---- 液态档弹簧参数（response 秒 / dampingFraction 无量纲；域与换算见 [LiquidSpring]）。
        // 主弹簧：明显回弹的那一档（damping 0.6~0.7 区间），周期取中位——状态切换带惯性落地；
        // 位移：周期更长、阻尼更高，大块面不吃"甩"；
        // 按压：极短周期 + 接近临界阻尼，跟手且不复弹。
        private const val LIQUID_FLOAT_RESPONSE = 0.32f
        private const val LIQUID_FLOAT_DAMPING = 0.65f
        private const val LIQUID_OFFSET_RESPONSE = 0.42f
        private const val LIQUID_OFFSET_DAMPING = 0.80f
        private const val LIQUID_PRESS_RESPONSE = 0.20f
        private const val LIQUID_PRESS_DAMPING = 0.90f
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
