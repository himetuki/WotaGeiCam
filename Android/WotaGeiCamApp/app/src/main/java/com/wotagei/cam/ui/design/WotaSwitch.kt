package com.wotagei.cam.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.anim.MotionMode
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * 全工程唯一的开关控件（2026-10-30）：**液态档走"液态手势开关"**，其余两档保持 Material3
 * 开关原样（同一对强调色，观感与改版前逐字一致）。
 *
 * # 液态档的设计来源与移植口径
 * 设计取自 Bencho 的 "Liquid toggle"（MIT，bencho.dev/licence）。那份实现是 Web（React +
 * framer-motion），本工程按**同一套视觉语言**在 Compose 重写，几何与物理逐条保留：
 *
 * - **几何**：轨道 92×46 → 这里等比缩到 64×32dp（宽高比仍 2:1）；拇指 36/46 高 ≈ 25dp，
 *   四周留白 5/46 ≈ 3.5dp——**留白是同一个数看两遍**（两端内缩与上下内缩），不是两个拍脑袋的值。
 * - **状态由轨道承载**：关 = 底板色（slab）轨道 + 一滴"向强调色混 19%"的液滴；开 = 反相
 *   ——强调色轨道 + 底板色液滴。颜色**不做动画**（本工程 #81 第 1 条的既定红线），瞬时反相。
 * - **沿行程拉伸而不是拖尾**：速度越大液滴沿运动方向越长、垂直方向越薄，面积感觉不变
 *   （scaleX = lengthen × swell，scaleY = swell / lengthen）。设计文档里写得很清楚：早先
 *   版本是"一个小水珠在后面追"，goo 滤镜把两个圆的脖子拉成一根线——那正是"尾巴"的样子；
 *   现在弹性长在**移动物体自己的形状**上，没有尾巴可留。拉伸上限 0.4、速度分母 600（px/s）。
 * - **软着地**：落位弹簧 = stiffness 170 / damping 21.5 / mass 0.9 ⇒ 阻尼比 ζ≈0.87
 *   （"带一点回让、但只有一点点"）。Compose 的 `spring()` 是单位质量表述，等价于
 *   `dampingRatio = 0.87, stiffness = 189`（= 170/0.9），换算写在下面的常量注释里。
 * - **拖动即状态**：过中点立刻翻转（轨道在手指下就回应），松手从**离手速度**接着落位
 *   （`Animatable.animateTo` 的 initialVelocity）——不是速度归零后重启；没移动过的按压
 *   是一次点击（开关终究要是开关）。
 * - **不挂 goo 滤镜**：源实现最终也删掉了它——单个圆做"模糊+阈值"只会把边缘磨糊。
 *
 * # 与工程守卫的关系
 * 只动 translation / scale / alpha（`MotionHygieneTest` 的红线）；不动任何布局参数；
 * 颜色瞬时切换；减少动效档一切直达；按压幅度走 `motion.press`（小幅度）。
 */
@Composable
fun WotaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val motion = LocalMotion.current
    if (motion.mode != MotionMode.LIQUID) {
        // 非液态档：Material3 开关原样（同一对强调色），观感与改版前逐字一致
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = WotaColor.accentActive,
                checkedThumbColor = WotaColor.onAccent
            )
        )
        return
    }
    LiquidGestureSwitch(checked, onCheckedChange, modifier, enabled)
}

// ---- 液态手势开关的几何（与设计稿等比：92×46 轨道 / 36 拇指 / 5 留白）----

/** 轨道高：设计 46 → 32dp */
private val TrackHeight: Dp = 32.dp

/** 轨道宽：设计 92 → 64dp（宽高比 2:1 不变） */
private val TrackWidth: Dp = 64.dp

/** 拇指：设计 36/46 ≈ 0.783 → 25dp */
private val ThumbSize: Dp = 25.dp

/** 留白：设计 5/46 ≈ 0.109 → 3.5dp（两端与上下是同一个数） */
private val ThumbPad: Dp = 3.5.dp

/**
 * 落位弹簧：设计稿 stiffness 170 / damping 21.5 / mass 0.9。
 * Compose 的 spring() 是单位质量表述：ζ = c / (2√(k·m)) = 21.5 / (2√(170×0.9)) ≈ 0.87，
 * 等效刚度 = k/m = 170/0.9 ≈ 189。两个数都由这一条换算来，别单独改其中一个。
 */
private val SettleSpring = spring<Float>(dampingRatio = 0.87f, stiffness = 189f)

/** 拉伸上限（设计稿 0.4：拇指最多长到 1.4 倍）与速度分母（600 px/s 到顶） */
private const val StretchMax = 0.4f
private const val StretchVelocityScalePx = 600f

/** 设计稿默认 stretch=36（0..100）：这里固定 36，拉伸峰值约 1.14 倍 */
private const val StretchAmount = 0.36f

/** 悬停/按压时的那一下"鼓"（设计稿 1.035，走 motion.press 的小幅度弹簧） */
private const val SwellPressed = 1.035f

@Composable
private fun LiquidGestureSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val motion = LocalMotion.current
    val interactive = enabled && onCheckedChange != null
    val hardSnap = motion.durationMs == 0

    val travelPx = with(density) { (TrackWidth - ThumbSize - ThumbPad * 2).toPx() }
    val padPx = with(density) { ThumbPad.toPx() }

    // 位置：一根 Animatable，手势与弹簧写同一个值（设计稿 "ONE POSITION, WRITTEN TWO WAYS"）。
    // px 单位：拉伸公式的速度分母（600 px/s）与设计稿同量纲。
    val x = remember { Animatable(0f) }
    // 手势作用域是受限挂起（AwaitPointerEventScope 不许直接调 Animatable 的挂起函数），
    // 落位动画统一投到这枚组合作用域上——它与页面同寿命，恰是"这次开关交互"的边界
    val scope = rememberCoroutineScope()
    var dragX by remember { mutableStateOf<Float?>(null) }
    var dragVelocity by remember { mutableStateOf(0f) }
    var pressing by remember { mutableStateOf(false) }

    // 目标位置随 checked 变化（拖动中由手势自己写，这里不抢）
    LaunchedEffect(checked, travelPx, hardSnap, interactive) {
        if (dragX != null) return@LaunchedEffect
        val target = if (checked) travelPx else 0f
        if (x.value == target) return@LaunchedEffect
        if (hardSnap) x.snapTo(target) else x.animateTo(target, SettleSpring)
    }

    val swell by animateFloatAsState(
        if (pressing && interactive) SwellPressed else 1f,
        motion.press
    )

    // 关 = 底板色轨道 + 混色液滴；开 = 强调色轨道 + 底板色液滴（反相；颜色不画动画）
    val slab = WotaColor.surface
    val trackColor = if (checked) WotaColor.accentActive else slab
    val thumbColor = if (checked) slab else lerp(slab, WotaColor.accentActive, 0.19f)
    val edge = WotaColor.acrylicBorder

    Box(
        modifier
            .size(TrackWidth, TrackHeight)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(WotaShape.pill)
            .drawBehind {
                // 轨道：整枚药丸即状态本体（描边只在关态画——底板色在近黑底上需要一道轮廓才成"物体"）
                drawRect(trackColor)
                if (!checked) {
                    drawRoundRect(
                        color = edge,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                }
            }
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                if (onCheckedChange != null && enabled) {
                    onClick(label = null) {
                        onCheckedChange(!checked)
                        true
                    }
                }
            }
            .then(
                if (!interactive) Modifier else Modifier.pointerInput(interactive, checked, travelPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        pressing = true
                        var grab: Float? = null
                        var moved = false

                        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }
                        if (slopChange != null) {
                            moved = true
                            // 偏移在**第一次移动**时才取（设计稿的教训：按下就取会让液滴瞬移到手指下）
                            grab = slopChange.position.x - (dragX ?: x.value)
                            trackHorizontalDrag(tracker, slopChange.id) { at, velocity ->
                                val next = (at - grab!!).coerceIn(0f, travelPx)
                                dragX = next
                                dragVelocity = velocity
                                // 过中点立刻翻转：轨道在手指下就回应（不必等松手）
                                val past = next > travelPx / 2f
                                if (past != checked) onCheckedChange?.invoke(past)
                            }
                        }
                        pressing = false
                        val released = dragX
                        dragX = null
                        if (!moved) {
                            // 没移动过 = 一次点击（开关要是开关）
                            onCheckedChange?.invoke(!checked)
                        } else {
                            val at = released ?: x.value
                            // 从离手位置 + 离手速度接着落位；若这一帧手上速度约等于 0（其实是
                            // 上一段动画在飞），用 x 自己的速度续上——绝不"归零重启"
                            val v0 = if (abs(dragVelocity) > 1f) dragVelocity else x.velocity
                            val target = if (checked) travelPx else 0f
                            scope.launch {
                                x.snapTo(at)
                                if (hardSnap) {
                                    x.snapTo(target)
                                } else {
                                    // 速度继承：`initialVelocity` 就是"离手瞬间的速度"，
                                    // 不是速度归零后重启（设计稿的 ANIMATABLE 口径）
                                    x.animateTo(target, SettleSpring, initialVelocity = v0)
                                }
                            }
                        }
                        dragVelocity = 0f
                    }
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    // 只动 translation + scale，不动布局参数
                    translationX = padPx + (dragX ?: x.value)
                    val speed = abs(dragVelocity).takeIf { it > 1f } ?: abs(x.velocity)
                    val lengthen = 1f + min(StretchMax, speed / StretchVelocityScalePx) * StretchAmount
                    scaleX = lengthen * swell
                    scaleY = swell / lengthen
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                }
                .size(ThumbSize)
                .clip(WotaShape.pill)
                .drawBehind { drawRect(thumbColor) }
        )
    }
}

/**
 * 横向拖动循环：把每次移动的绝对 x（px）与最近一次的 x 速度交给调用方，直到抬手/取消。
 * 单独抽出来是为了让 `awaitEachGesture` 的主线一眼能读完——拖拽本身没有业务判断。
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.trackHorizontalDrag(
    tracker: VelocityTracker,
    pointerId: androidx.compose.ui.input.pointer.PointerId,
    onAt: (atPx: Float, velocityPx: Float) -> Unit
) {
    drag(pointerId) { change ->
        tracker.addPosition(change.uptimeMillis, change.position)
        change.consume()
        val v = tracker.calculateVelocity().x
        onAt(change.position.x, if (v.isFinite()) v else 0f)
    }
}
