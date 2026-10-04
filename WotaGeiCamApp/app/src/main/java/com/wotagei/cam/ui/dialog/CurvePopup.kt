package com.wotagei.cam.ui.dialog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * RGB 曲线**左侧小弹窗**（原 CurveSheet 大底板的换装，需求「边调曲线边看预览」批）。
 *
 * - 面板里**只有曲线框**：标题/副标题/通道胶囊行/色调档行/计数全部退场（色调档按用户裁决整个
 *   移除，[CurvePreset] 连同枚举一并删除）；「重置（回原图默认）」收进扇面当第五枚小钮。
 * - 通道选择是**径向菜单**：当前通道合成一枚圆钮，住在面板卡左侧的**专用槽**里（2026-10-04
 *   不重叠改版——原先钉在曲线框左缘内，圆环永久压住绘图区，用户点名修掉）；点开后四通道＋重置
 *   五枚小钮朝**右**半扇逐枚弹出（朝左出屏，这是几何约束不是口味），扫过画布属瞬态、选中即收回；
 *   中心钮再点一次也是收回。
 * - 弹窗本体是一枚**面板卡**（surface 底 + 描边 + 圆角），左缘在安全区外再加 [PanelBreath]
 *   呼吸——不再贴死屏缘（同日投诉"弹窗不够完整"的另一半：裸框贴边 + Dock 滑出残段叠压）。
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
    val resetLabel = stringResource(R.string.cam_curve_reset_all)
    var channel by remember { mutableStateOf(ColorCurve.CHANNEL_MASTER) }
    var fanOpen by remember { mutableStateOf(false) }

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

    // 「重置」＝四通道全部回恒等（原图默认）；原面板「重置通道/重置全部」两枚合并成这一枚
    fun resetAll() {
        params.curve.value = CurveStack.IDENTITY
        WotaSettings.setCurveStack(prefs, CurveStack.IDENTITY)
        points = CurveEdit.pointsOf(ColorCurve.IDENTITY)
        selected = -1
    }

    // BACK 收弹窗：BottomPanel 时代就有的语义，换壳不丢——弹窗开着按 BACK 不该连页面一起退
    BackHandler(enabled = visible) { onDismiss() }

    // onDismiss 走 rememberUpdatedState：捕获层 pointerInput 的 key 必须恒定（Unit），
    // 若以调用点的内联 lambda 作 key，HUD 每秒刷读数的重组会不停重启手势监听，
    // 按压落进重启窗口就被吞（与扇钮 fanTap 同族，见该处注释）
    val currentDismiss by rememberUpdatedState(onDismiss)

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
                    .pointerInput(Unit) {
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
                            currentDismiss()
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
                    // 面板卡 = [扇钮左槽 | 曲线框]：通道钮有了自己的格子，闭合态与曲线绘图区零重叠
                    // （用户 2026-10-04 投诉"通道切换按钮与曲线框重叠"的落点）；面板底/描边让弹窗读起来
                    // 是一枚完整的浮层而不是贴屏的裸框，屏缘再留 PanelBreath 呼吸。
                    // 叠层顺序刻意反直觉：曲线框先声明（画在下），扇钮后声明（画在上）——展开时朝右
                    // 扫进画布区的红/绿/蓝三枚必须可见，Row 布局里反过来会被曲线框整块盖掉
                    // （真机复验踩实过两回：Row 版 + 回滚版）
                    Box(
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
                        // 曲线框：左让出扇钮槽位与缝，先声明居下层
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
                                    // key 只留 gpuMode：channel 变化会重启手势监听，换通道瞬间
                                    // 的按压会被吞；层内状态全走 rememberUpdatedState 的 livePoints
                                    .pointerInput(gpuMode) {
                                        if (!gpuMode) return@pointerInput
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            android.util.Log.i(
                                                "CurveDbg",
                                                "canvas down pos=${down.position} consumed=${down.isConsumed}"
                                            )
                                            // 扇面认领过的按压整手忽略：那一指属于通道钮，不在画布落点
                                            if (down.isConsumed) return@awaitEachGesture
                                            // 点画布**不再收扇**（用户 2026-10-04）：点卫星钮稍有偏差落在
                                            // 画布上就会把整扇收掉，体感是"卫星钮吞了我的操作"；收扇只由
                                            // 中心钮再点与选完自动收回承担
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
                        // 扇钮：居上层，住面板左槽（48dp 恰好占满），垂直居中于画布行；
                        // 展开朝右扫进画布区的三枚因此盖在曲线框上可见
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .size(CENTER_SIZE.dp)
                        ) {
                            ChannelFan(
                                channel = channel,
                                open = fanOpen,
                                names = channelNames,
                                resetLabel = resetLabel,
                                onToggle = { fanOpen = !fanOpen },
                                onChannel = { ch ->
                                    channel = ch
                                    points = CurveEdit.pointsOf(params.curve.value.curveOf(ch))
                                    selected = -1
                                    fanOpen = false   // 选完自动收回
                                },
                                onReset = {
                                    resetAll()
                                    fanOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }
        }
    }

// ------------------------------------------------------------------ 径向通道菜单

/** 中心钮（当前通道合成钮）边长 */
private const val CENTER_SIZE = 48

/** 扇面小钮边长（视觉）；命中盒另见 [OPTION_HIT_SIZE] */
private const val OPTION_SIZE = 40

/** 卫星钮命中盒边长：48dp 恰好与中心钮同大，包住 40dp 视觉钮（边缘手指不再滑出命中区） */
private const val OPTION_HIT_SIZE = 48

/** 小钮圆心到中心钮圆心的展开半径：四钮 45° 角距下弦距 ≈49dp，40dp 钮留缝充足 */
private const val FAN_RADIUS = 64f

/** 中心钮圆心到曲线框左缘的距离常量已废弃：扇钮改住面板左槽（2026-10-04 不重叠改版） */

/** 曲线框设计边长与下限 */
private const val CurveSide = 200
private const val MinCurveSide = 120

/** 面板卡内边距（包住左槽与曲线框） */
private const val PanelPad = 10

/** 左槽与曲线框的缝 */
private const val FanGap = 6

/** 面板左缘在安全区之外的呼吸留白（弹窗不再贴死屏缘，读起来是一枚完整浮层） */
private const val PanelBreath = 12

/** 弹窗与安全区边缘的呼吸留白 */
private const val PopupMargin = 12

/** 出场逐枚错峰（毫秒）；收回不 stagger，整扇一起收。16ms：展开全程压进 ~250ms，
 *  压缩"钮还在飞、点视觉终点落空"的窗口（用户 2026-10-04 点卫星钮无效的成因之一） */
private const val FanStaggerMs = 16L

/**
 * 扇面小钮的出场弹簧（过冲后落位，承接参考径向件的「逐枚发出」手感）。
 * 不走 [androidx.compose.animation.core.tween]：时长红线归 MotionSpec 管弹簧没有时长；
 * 减少动效档由调用点按 `motion.durationMs == 0` 直达。StiffnessMedium：比 MediumLow 收得快，
 * 同样是为了缩短点空窗口。
 */
private val FanSpring = spring<Float>(
    dampingRatio = 0.7f,
    stiffness = Spring.StiffnessMedium
)

/**
 * 五枚小钮的方位角（度，0°＝朝画布内即向右）：自上而下＝白、红、绿、蓝、重置。
 * 半圆扇面只朝右开——中心钮贴画布左缘，朝左弹出直接出屏。
 */
private val FanAngles = floatArrayOf(-90f, -45f, 0f, 45f, 90f)

/**
 * 径向通道菜单：中心钮（当前通道）＋五枚扇出小钮（四通道＋重置）。
 * 宿主是面板卡左侧的 48dp 专用槽（[CENTER_SIZE] 恰好占满），与曲线框不再有布局交叠。
 *
 * @param onToggle 中心钮点按（开/收扇）
 * @param onChannel 选定通道（调用方负责收扇——「选完自动收回」是需求语义，不在组件里隐藏）
 */
@Composable
private fun ChannelFan(
    channel: Int,
    open: Boolean,
    names: List<String>,
    resetLabel: String,
    onToggle: () -> Unit,
    onChannel: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val channelKeys = listOf(
            ColorCurve.CHANNEL_MASTER, ColorCurve.CHANNEL_RED,
            ColorCurve.CHANNEL_GREEN, ColorCurve.CHANNEL_BLUE
        )
        // 小钮先组合（z 序在下），中心钮最后组合恒在扇面之上；
        // 小钮用 graphicsLayer 平移/缩放/淡入（MotionSpec 只喂这三类变换的红线）
        FanAngles.forEachIndexed { i, angle ->
            val isReset = i == FanAngles.size - 1
            val key = if (isReset) -1 else channelKeys[i]
            val label = if (isReset) resetLabel else names[i]
            FanOption(
                index = i,
                angleDeg = angle,
                open = open,
                selected = !isReset && channel == key,
                description = label,
                onTap = {
                    when {
                        isReset -> onReset()
                        else -> onChannel(key)
                    }
                }
            ) {
                if (isReset) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = WotaColor.textHi,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(channelColor(key))
                    )
                }
            }
        }
        // 中心钮：边色＋芯点都取当前通道色，开合状态一眼可读
        val centerDesc = names[channel.coerceIn(0, names.size - 1)]
        Box(
            Modifier
                .semantics { contentDescription = centerDesc }
                .fanTap(enabled = true, onTap = onToggle)
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
}

/** 单枚扇面小钮：按 [FanStaggerMs] 错峰弹出、过冲落位，收回整扇齐收 */
@Composable
private fun FanOption(
    index: Int,
    angleDeg: Float,
    open: Boolean,
    selected: Boolean,
    description: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    face: @Composable () -> Unit
) {
    val motion = LocalMotion.current
    val frac = remember { Animatable(0f) }
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        if (frac.value == target) return@LaunchedEffect
        if (motion.durationMs == 0) {
            frac.snapTo(target)
            return@LaunchedEffect
        }
        if (open) delay(FanStaggerMs * index)
        frac.animateTo(target, FanSpring)
    }
    val rad = Math.toRadians(angleDeg.toDouble())
    // 命中盒 48dp（[OPTION_HIT_SIZE]）包住 40dp 视觉钮：手指按在圆钮边缘不滑出命中区。
    // 相邻钮 45° 角距 64dp 的弦距 ≈49dp > 48dp，命中区两两不相交。fanTap 在 graphicsLayer
    // **内侧**——外侧挂法命中留在布局原位、视觉/命中分离，点扇面位置永远点空（真机实证）
    Box(
        modifier
            .semantics { contentDescription = description }
            .graphicsLayer {
                translationX = cos(rad).toFloat() * FAN_RADIUS.dp.toPx() * frac.value
                translationY = sin(rad).toFloat() * FAN_RADIUS.dp.toPx() * frac.value
                val s = 0.4f + 0.6f * frac.value
                scaleX = s
                scaleY = s
                alpha = frac.value
            }
            .fanTap(enabled = open, onTap = onTap)
            .size(OPTION_HIT_SIZE.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(OPTION_SIZE.dp)
                .clip(CircleShape)
                .background(WotaColor.bg)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) WotaColor.textHi else WotaColor.acrylicBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            face()
        }
    }
}

/**
 * 扇面触点：down 即消费认领——曲线手势层与关闭捕获层靠 `isConsumed` 整手让路，
 * 不依赖 foundation clickable 的消费口径；抬手时未超滑动阈值才算一次点按。
 */
/**
 * 扇面触点：down 即消费认领——曲线手势层与关闭捕获层靠 `isConsumed` 整手让路，
 * 不依赖 foundation clickable 的消费口径；抬手时未超滑动阈值才算一次点按，按住超过
 * 系统长按时限不重复触发。
 *
 * **key 只有 [enabled]，[onTap] 走 [rememberUpdatedState]**：取景页 HUD 每秒刷读数，
 * 面板随之频繁重组，调用点的 lambda 每次重组都是新实例——若以 lambda 作 key，
 * pointerInput 会在重组时重启，手指 down 正好落进重启窗口就被整个吞掉
 * （用户 2026-10-04「卫星钮点不动」的根因；adb 偶尔成功只是没撞上重启窗口）。
 */
@android.annotation.SuppressLint("ComposableModifierFactory")
@Composable
private fun Modifier.fanTap(enabled: Boolean, onTap: () -> Unit): Modifier {
    val currentTap by rememberUpdatedState(onTap)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var moved = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!moved && change.positionChange().getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                change.consume()
                if (!change.pressed) break
            }
            if (!moved) currentTap()
        }
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
