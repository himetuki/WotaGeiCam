package com.wotagei.cam.ui.dialog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveEdit
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.ui.HudEdgePad
import com.wotagei.cam.ui.WotaSettings
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaShape

/**
 * RGB 曲线**左侧小弹窗**（原 CurveSheet 大底板的换装，需求「边调曲线边看预览」批）。
 *
 * - 面板里**只有曲线框**：标题/副标题/通道胶囊行/色调档行/计数全部退场（色调档按用户裁决整个
 *   移除，[CurvePreset] 连同枚举一并删除）。
 * - 通道选择（2026-10-04 二次改版）：**点通道钮循环换通道**（白→红→绿→蓝→白）、**长按复位全部**。
 *   初版是径向扇面（中心钮开扇＋五枚小钮错峰展开），真机上用户点小钮永远点空（展开动画期点
 *   最终位落空、点空又落在画布上触发收扇），两段式瞄准太重——用户点名「轻易通过点击切换通道
 *   或重置」⇒ 收敛为一颗钮两个手势，面板顶部一行小字写明用法。
 * - 通道钮住在面板卡左侧的**专用槽**里（同日投诉"通道切换按钮与曲线框重叠"的落点），闭合态与
 *   曲线绘图区零重叠；面板底/描边让弹窗读起来是一枚完整浮层，屏缘再留呼吸留白（同日投诉
 *   "弹窗不够完整"的另一半：裸框贴边 + Dock 滑出残段叠压）。
 * - 删控制点不再占按钮位：**选中后再点一次同一点＝删点**（端点不可删），点错用重置兜底。
 * - 边拖边写参数总线、抬手落盘的口径不变；CPU 渲染档整块置灰不可拖（提示改由打开入口的
 *   提示条承担，弹窗里没有副标题位）。
 * - 关闭：点弹窗外任意处 / BACK。不加暗化 scrim——预览要保持原亮度可看。
 */
@Composable
fun CurvePopup(
    params: WotaParams,
    gpuMode: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val context = LocalContext.current
    val prefs = remember(context) { WotaSettings.of(context) }
    val stack by params.curve.observed()
    val motion = LocalMotion.current
    val channelNames = listOf(
        stringResource(R.string.cam_curve_ch_master),
        stringResource(R.string.cam_curve_ch_red),
        stringResource(R.string.cam_curve_ch_green),
        stringResource(R.string.cam_curve_ch_blue)
    )
    val hint = stringResource(R.string.cam_curve_hint)
    var channel by remember { mutableStateOf(ColorCurve.CHANNEL_MASTER) }

    // 本通道的编辑态：只在换通道时重建。points 绝不能进 pointerInput 的 key——
    // 拖动每帧都在改 points，一改 key 手势就被重启，曲线会「拖不住」
    var points by remember(channel) { mutableStateOf(CurveEdit.pointsOf(stack.curveOf(channel))) }
    var selected by remember(channel) { mutableStateOf(-1) }
    val livePoints = rememberUpdatedState(points)

    // 以总线现值做底、只替换本通道：其它通道在拖动期间可能被改过，不能用手里的旧 stack 合
    fun commit(next: List<ColorCurve.Point>, persist: Boolean) {
        points = next
        val merged = params.curve.value.withCurve(channel, asCurve(next))
        params.curve.value = merged
        if (persist) WotaSettings.setCurveStack(prefs, merged)
    }

    /** 点中心钮 = 循环换通道（白→红→绿→蓝→白）；换通道即换编辑态，不落盘（拖动/复位才落） */
    fun cycleChannel() {
        val next = when (channel) {
            ColorCurve.CHANNEL_MASTER -> ColorCurve.CHANNEL_RED
            ColorCurve.CHANNEL_RED -> ColorCurve.CHANNEL_GREEN
            ColorCurve.CHANNEL_GREEN -> ColorCurve.CHANNEL_BLUE
            else -> ColorCurve.CHANNEL_MASTER
        }
        channel = next
        points = CurveEdit.pointsOf(params.curve.value.curveOf(next))
        selected = -1
    }

    // 「重置」＝四通道全部回恒等（原图默认）；原面板「重置通道/重置全部」两枚合并成这一枚
    fun resetAll() {
        params.curve.value = CurveStack.IDENTITY
        WotaSettings.setCurveStack(prefs, CurveStack.IDENTITY)
        points = CurveEdit.pointsOf(ColorCurve.IDENTITY)
        selected = -1
    }

    // BACK 收弹窗：BottomPanel 时代就有的语义，换壳不丢——弹窗开着按 BACK 不该连页面一起退
    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(motion.offset) { -it } + fadeIn(motion.float),
        exit = slideOutHorizontally(motion.offset) { -it } + fadeOut(motion.float),
        modifier = modifier
    ) {
        // AnimatedVisibility 的内容不在 BoxScope 里，铺满只能用 fillMaxSize（matchParentSize 要 Box 接收器）
        Box(Modifier.fillMaxSize()) {
            // 关闭捕获层：无视觉（预览不暗化）。只认领**未被消费**的按压——面板上的触点
            // （扇面/画布手势层）都会先消费 down，落不到这里，天然不会「点面板也关弹窗」
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(onDismiss) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.isConsumed) return@awaitEachGesture
                            down.consume()
                            var dragging = true
                            while (dragging) {
                                val change = awaitPointerEvent()
                                change.changes.forEach { it.consume() }
                                dragging = change.changes.any { it.pressed }
                            }
                            onDismiss()
                        }
                    }
            )
            // 贴边避让交给系统（safeDrawingPadding，不按方向写死），弹窗本体再贴一枚设计留白
            Box(Modifier.matchParentSize().padding(HudEdgePad)) {
                BoxWithConstraints(Modifier.align(Alignment.CenterStart).padding(start = PanelBreath.dp)) {
                    // 曲线框设计档 200dp；安全区给不出就收进「边长−两侧留白」，下限 120dp 保最差档仍能拖点
                    val side = maxOf(
                        MinCurveSide.dp,
                        minOf(
                            CurveSide.dp,
                            maxWidth - PanelBreath.dp - PopupMargin.dp * 2,
                            maxHeight - PopupMargin.dp * 2
                        )
                    )
                    // 面板卡 = 提示行 + [通道钮左槽 | 曲线框]：通道钮有自己的格子，闭合态与曲线
                    // 绘图区零重叠（用户 2026-10-04 投诉"通道切换按钮与曲线框重叠"的落点）；面板底/
                    // 描边让弹窗读起来是一枚完整的浮层而不是贴屏的裸框，屏缘再留 PanelBreath 呼吸。
                    // 叠层顺序刻意反直觉：曲线框先声明（画在下），通道钮后声明（画在上）——
                    // 通道钮叠在槽内、不扫进画布，这里 z 序只保证钮的描边完整显示。
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(WotaShape.menu))
                            .background(WotaColor.surface.copy(alpha = 0.97f))
                            .border(1.dp, WotaColor.acrylicBorder, RoundedCornerShape(WotaShape.menu))
                            // 面板本体是吞噬层：落在内边距/缝隙上的按压不外溢给关闭捕获层
                            // （子级钮/画布在 Main pass 先消费，这里只兜没人认领的）
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (down.isConsumed) return@awaitEachGesture
                                    down.consume()
                                    var swallowing = true
                                    while (swallowing) {
                                        val change = awaitPointerEvent()
                                        change.changes.forEach { it.consume() }
                                        swallowing = change.changes.any { it.pressed }
                                    }
                                }
                            }
                            .padding(PanelPad.dp)
                    ) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = WotaColor.textLo,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(HintGap.dp))
                        // 画布行内容区（BoxScope）：曲线框偏右让出左槽，通道钮 CenterStart 叠层
                        Box(Modifier) {
                        // 曲线框：左让出通道钮槽位与缝，先声明居下层
                        Box(
                            Modifier
                                .padding(start = (CENTER_SIZE + FanGap).dp)
                                .size(side)
                                .clip(RoundedCornerShape(WotaShape.menu))
                        ) {
                            CurveCanvas(
                                points = points,
                                selected = selected,
                                color = channelColor(channel),
                                enabled = gpuMode,
                                modifier = Modifier.matchParentSize()
                            )
                        Box(
                            Modifier
                                .matchParentSize()
                                .pointerInput(channel, gpuMode) {
                                    if (!gpuMode) return@pointerInput
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        // 通道钮认领过的按压整手忽略：那一指属于通道钮，不在画布落点
                                        if (down.isConsumed) return@awaitEachGesture
                                        down.consume()
                                        if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
                                        var working = livePoints.value
                                        val gx = down.position.x / size.width
                                        val gy = 1f - down.position.y / size.height
                                        var index = CurveEdit.hitTest(working, gx, gy)
                                        if (index < 0) {
                                            val added = CurveEdit.inserted(working, gx, gy)
                                            if (added != null) {
                                                working = added.first
                                                index = added.second
                                            }
                                        }
                                        // 已到控制点上限且没点到现有点：本次手势什么都不做
                                        if (index >= 0) {
                                            val deletable = index > 0 && index < working.size - 1
                                            val secondTap = selected == index && deletable
                                            selected = index
                                            commit(working, persist = false)
                                            var dragging = true
                                            var moved = false
                                            while (dragging) {
                                                val change = awaitPointerEvent()
                                                    .changes.firstOrNull { it.id == down.id } ?: break
                                                dragging = change.pressed
                                                if (!moved &&
                                                    change.positionChange().getDistance() >
                                                    viewConfiguration.touchSlop
                                                ) moved = true
                                                if (moved) {
                                                    working = CurveEdit.moved(
                                                        working, index,
                                                        change.position.x / size.width,
                                                        1f - change.position.y / size.height
                                                    )
                                                    commit(working, persist = false)
                                                }
                                                change.consume()
                                            }
                                            if (secondTap && !moved) {
                                                // 二次点按同一点＝删点：原「删除控制点」动作钮的就地化
                                                commit(CurveEdit.removed(livePoints.value, index), persist = true)
                                                selected = -1
                                            } else {
                                                commit(working, persist = true)
                                            }
                                        }
                                    }
                                }
                        )
                        }
                        // 通道钮：居上层，住面板左槽（48dp 恰好占满），垂直居中于画布行。
                        // 点=循环换通道、长按=全部复位（用户 2026-10-04 二次改版：径向扇面点不动，
                        // 移除五枚小钮，交互收敛到一颗钮上）
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .size(CENTER_SIZE.dp)
                        ) {
                            ChannelButton(
                                channel = channel,
                                description = channelNames[channel.coerceIn(0, channelNames.size - 1)],
                                onCycle = ::cycleChannel,
                                onReset = ::resetAll
                            )
                        }
                        }
                    }
            }
        }
        }
    }
}

// ------------------------------------------------------------------ 径向通道菜单

/** 通道钮（当前通道合成钮）边长；面板左槽与曲线框的缝以它为基准 */
private const val CENTER_SIZE = 48

/** 曲线框设计边长与下限 */
private const val CurveSide = 200
private const val MinCurveSide = 120

/** 面板卡内边距（包住左槽与曲线框） */
private const val PanelPad = 10

/** 左槽与曲线框的缝 */
private const val FanGap = 6

/** 提示行与画布行的间距 */
private const val HintGap = 4

/** 面板左缘在安全区之外的呼吸留白（弹窗不再贴死屏缘，读起来是一枚完整浮层） */
private const val PanelBreath = 12

/** 弹窗与安全区边缘的呼吸留白 */
private const val PopupMargin = 12

// ------------------------------------------------------------------ 通道钮（点循环 / 长按复位）

/**
 * 通道钮：边色＋芯点都取当前通道色，点一下循环换通道（白→红→绿→蓝→白）、长按复位全部。
 *
 * 2026-10-04 二次改版：径向扇面（五枚小钮错峰展开）整组移除——真机上用户点小钮永远点空
 * （展开动画期点最终位落空、点空又落在画布上触发收扇），且"点中心钮开扇再瞄准 40dp 小钮"
 * 两段式太重；用户诉求「轻易通过点击切换通道或重置」⇒ 收敛为一颗钮，点/长按两个手势直达。
 *
 * @param description 当前通道名（无障碍读出；视觉状态由颜色承担）
 */
@Composable
private fun ChannelButton(
    channel: Int,
    description: String,
    onCycle: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .semantics { contentDescription = description }
            .channelTap(onTap = onCycle, onLongPress = onReset)
            .size(CENTER_SIZE.dp)
            .clip(CircleShape)
            .background(WotaColor.bg)
            .border(2.dp, channelColor(channel), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(channelColor(channel))
        )
    }
}

/**
 * 通道钮触点：down 即消费认领——曲线手势层与关闭捕获层靠 `isConsumed` 整手让路，
 * 不依赖 foundation clickable 的消费口径。点按（未超滑动阈值、未到长按时限）触发 [onTap]；
 * 按住超过系统长按时限触发一次 [onLongPress]，之后抬手不再触发点按。
 */
private fun Modifier.channelTap(onTap: () -> Unit, onLongPress: () -> Unit): Modifier =
    pointerInput(onTap, onLongPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var moved = false
            var longFired = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!moved && change.positionChange().getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                if (!longFired && !moved &&
                    change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                ) {
                    longFired = true
                    onLongPress()
                }
                change.consume()
                if (!change.pressed) break
            }
            if (!moved && !longFired) onTap()
        }
    }

// ------------------------------------------------------------------ 画布与通道（自 CurveSheet 原样继承的部分）

/** 点集回到 y=x 就存成恒等（空点集），这样「重置」后的编码串与原图档对得上 */
private fun asCurve(points: List<ColorCurve.Point>): ColorCurve {
    val curve = ColorCurve(points)
    return if (curve.isIdentity()) ColorCurve.IDENTITY else curve
}

private fun channelColor(channel: Int): Color = when (channel) {
    ColorCurve.CHANNEL_RED -> Color(0xFFEF6A5A)
    ColorCurve.CHANNEL_GREEN -> Color(0xFF6FCE8F)
    ColorCurve.CHANNEL_BLUE -> Color(0xFF5FA8E8)
    else -> WotaColor.textHi
}

/** 折线直接用 [ColorCurve.table] 采样：画出来的就是真正上传给 GPU 的那张表 */
@Composable
private fun CurveCanvas(
    points: List<ColorCurve.Point>,
    selected: Int,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val target = remember(points) { ColorCurve(points).table(64) }
    val motion = LocalMotion.current
    // 换通道是整条曲线跳变，补间过去；但拖动期间逐帧差很小，必须原样跟手，
    // 否则曲线会拖在手指后面。判据用单帧最大偏移，不用时间窗，避免快速拖动被误判
    val shown = remember { mutableStateOf(target) }
    val morph = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(target) {
        val from = shown.value
        if (from === target) return@LaunchedEffect
        val jump = from.size == target.size &&
            (0 until target.size).any { kotlin.math.abs(from[it] - target[it]) > 0.08f }
        if (!jump) { shown.value = target; return@LaunchedEffect }
        morph.snapTo(0f)
        morph.animateTo(1f, motion.float)
        shown.value = target
    }
    val t = morph.value
    val sample = if (t < 1f && shown.value !== target) {
        val from = shown.value
        FloatArray(target.size) { from[it] + (target[it] - from[it]) * t }
    } else target
    // #81 第 1 条：颜色即时切换（禁动画颜色）；灰化程度由下面那条 dim（alpha，允许档）承担
    val strokeColor = color.copy(alpha = if (enabled) 1f else 0.4f)
    val dotColor = color
    val dim by animateFloatAsState(if (enabled) 1f else 0.35f, motion.float)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // 线宽与点半径一律走 dp：DrawScope 自带 Density，写裸数字会在 2x 屏上糊成看不见的点
        val dot = 6.5.dp.toPx()
        val dotSel = 9.dp.toPx()
        drawRect(WotaColor.bg)
        val grid = WotaColor.acrylicBorder.copy(alpha = 0.15f * dim)
        for (i in 1..2) {
            val t = i / 3f
            drawLine(grid, Offset(w * t, 0f), Offset(w * t, h), 1.dp.toPx())
            drawLine(grid, Offset(0f, h * t), Offset(w, h * t), 1.dp.toPx())
        }
        // y=x 基准线：曲线贴着它即等于不改变
        drawLine(WotaColor.outline.copy(alpha = 0.8f * dim), Offset(0f, h), Offset(w, 0f), 1.5.dp.toPx())
        val path = Path()
        for (i in sample.indices) {
            val x = w * i / (sample.size - 1)
            val y = h * (1f - sample[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, strokeColor, style = Stroke(width = 2.dp.toPx()))
        points.forEachIndexed { i, p ->
            val c = Offset(w * p.x, h * (1f - p.y))
            val r = if (i == selected) dotSel else dot
            // 空心点：实心点在亮画面上会和曲线糊成一团
            drawCircle(if (i == selected) dotColor else WotaColor.textHi, radius = r, center = c)
            drawCircle(WotaColor.bg, radius = r - 2.5.dp.toPx(), center = c)
            if (i == selected) drawCircle(dotColor, radius = r - 2.5.dp.toPx(), center = c)
        }
    }
}
