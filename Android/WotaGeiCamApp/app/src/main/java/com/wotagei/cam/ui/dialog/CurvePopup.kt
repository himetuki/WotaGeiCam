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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.roundToInt
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
 * - 面板**右缘可拖拽等比缩放**（2026-10-25）：默认最小尺寸只增不减，节点/线宽随画布等比
 *   放大（方便手指大的人调节）；扇面「重置」除回恒等外同时把尺寸复位到默认最小。
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
    visible: Boolean = true,
    /**
     * 直方图快照源（2026-10-27：曲线框背景直方图，调曲线参考用）。null/缺省 = 无直方图
     * （DIRECT 档无 GL 帧，本就不提供）。GL 侧 4Hz 采样，这里 150ms 轮询进重组状态。
     */
    histogramProvider: (() -> IntArray?)? = null
) {
    val context = LocalContext.current
    val prefs = remember(context) { WotaSettings.of(context) }
    // 抓点/插点/长按删除的确认震动（29 §3.2-4）：Compose 自带 HapticFeedback，无权限
    val haptics = LocalHapticFeedback.current
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
    // 画布手势进行中：拖动帧必须直贴（snap），morph 补间只留给换通道/重置的整条跳变。
    // 快拖的单帧偏移会超过 morph 的 0.08「跳变」判据，而每帧新 target 都把动画重启回
    // 起跳点——曲线被钉在起跳位置追不上手指（节点画的是本地 points 即时跟手，两者分离
    // ＝用户报的「曲线变化与节点呈现不跟手」，2026-10-25）
    var canvasGesture by remember { mutableStateOf(false) }
    // 面板等比缩放（右缘拖拽，2026-10-25）：1f = 默认最小尺寸（原设计档），只许放大不许
    // 缩小；扇面「重置」同时复位。**29 §1.2 F1（2026-10-28）改存实际边长 dp**：scale 是
    // 相对 baseSide 的比值，跨屏幕/转屏 baseSide 会变，存 scale 会漂移；把手拖拽改的是
    // savedSide，**抬手才落盘一次**（不逐帧写 prefs），重置写回 200。
    var savedSide by remember { mutableStateOf(WotaSettings.curvePanelSideDp(prefs)) }
    var panelPx by remember { mutableStateOf(IntSize.Zero) }

    // 直方图快照轮询（弹窗可见才轮询；GL 侧 4Hz 采样，这里 150ms 拉一次进状态）
    var histogram by remember { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(visible, histogramProvider) {
        while (visible) {
            histogram = histogramProvider?.invoke()
            kotlinx.coroutines.delay(150)
        }
        histogram = null
    }

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
                    // 曲线框设计档 200dp 为**默认最小值**（2026-10-25：右缘拖拽等比放大，方便
                    // 手指大的人调节；扇面重置钮同时把 panelScale 复位到 1f）。放大上限 =
                    // 弹窗可用区（宽向扣扇钮槽/内距/呼吸，高向扣内距/呼吸）。
                    val maxSide = minOf(
                        maxWidth - CENTER_SIZE.dp - FanGap.dp - PanelPad.dp * 2 - PopupMargin.dp,
                        maxHeight - PanelPad.dp * 2 - PopupMargin.dp
                    ).coerceAtLeast(MinCurveSide.dp)
                    val baseSide = maxOf(MinCurveSide.dp, minOf(CurveSide.dp, maxSide))
                    // 持久边长（dp）带组合期可用区二次钳（29 §1.2）：prefs 值比当前窗口大
                    // （换小屏/分屏）缩回可用区、比最小值小抬回最小；dot/线宽随 side/baseSide
                    // 等比放大，与旧 panelScale 语义逐字相同
                    val side = com.wotagei.cam.core.CurvePanelSize
                        .clamp(savedSide, maxSide.value, MinCurveSide.toFloat()).dp
                    val panelScale = if (baseSide > 0.dp) side / baseSide else 1f
                    // 面板卡 = [扇钮左槽 | 曲线框]：通道钮有了自己的格子，闭合态与曲线绘图区零重叠
                    // （用户 2026-10-04 投诉"通道切换按钮与曲线框重叠"的落点）；面板底/描边让弹窗读起来
                    // 是一枚完整的浮层而不是贴屏的裸框，屏缘再留 PanelBreath 呼吸。
                    // 叠层顺序刻意反直觉：曲线框先声明（画在下），扇钮后声明（画在上）——展开时朝右
                    // 扫进画布区的红/绿/蓝三枚必须可见，Row 布局里反过来会被曲线框整块盖掉
                    // （真机复验踩实过两回：Row 版 + 回滚版）
                    Box(
                        Modifier
                            .onSizeChanged { panelPx = it }
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
                        // 曲线框：左让出扇钮槽位与缝，先声明居下层。
                        // clip 刻意**不**放这层（29 §3.2-1）：命中区外扩层是它的兄弟，
                        // 这层的 clip 会把画布外 12dp 的角部触点裁掉
                        Box(
                            Modifier
                                .padding(start = (CENTER_SIZE + FanGap).dp)
                                .size(side)
                        ) {
                            CurveCanvas(
                                points = points,
                                selected = selected,
                                color = channelColor(channel),
                                enabled = gpuMode,
                                animateJump = !canvasGesture,
                                scale = panelScale,
                                histogram = histogram,
                                channel = channel,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(WotaShape.menu))
                            )
                            // 输入/输出读数（29 §2.2-4 / §3.2-5）：选中控制点时显示 (x·255, y·255)，
                            // 未选中不占位；拖动随 points 实时更新——回答「该往哪个方向调」
                            if (selected >= 0 && selected < points.size) {
                                val p = points[selected]
                                Text(
                                    stringResource(
                                        R.string.cam_curve_io_readout,
                                        (p.x * 255f).roundToInt(),
                                        (p.y * 255f).roundToInt()
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WotaColor.textHi,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                )
                            }
                            // 命中区（29 §3.2-1）：比画布四边各外扩 [HitExtend]dp 的透明层——
                            // 端点正好在画布四角，触点一半落在画布外会被面板吞噬层吃掉，
                            // 「左下/右上端点抓不住」。layout 外扩 + 负偏移放置；手势内先把
                            // 越界落点 clamp 回画布再判命中。画布圆角 clip 移到了 CurveCanvas
                            // 自己的 modifier 上（只裁绘制），不裁这层命中区
                            Box(
                                Modifier
                                    .layout { measurable, constraints ->
                                        val p = HitExtend.dp.roundToPx()
                                        val placeable = measurable.measure(
                                            constraints.copy(
                                                minWidth = constraints.maxWidth + 2 * p,
                                                maxWidth = constraints.maxWidth + 2 * p,
                                                minHeight = constraints.maxHeight + 2 * p,
                                                maxHeight = constraints.maxHeight + 2 * p
                                            )
                                        )
                                        layout(placeable.width, placeable.height) { placeable.place(-p, -p) }
                                    }
                                    // key **必须含 channel**：手势闭包捕获的 points/selected/commit
                                    // 都绑定在 remember(channel) 的 state 实例上，channel 换了而监听
                                    // 不重启，拖动会写进旧通道的旧 state——总线被写（画面变）而画布
                                    // 不动（显示绑的是新 state），正是用户报的「能调、画面变、曲线不动」
                                    // （59931ab 把 channel 从 key 里去掉引入，2026-10-04 真机复现归因）。
                                    // 代价是换通道瞬间的一次按压会被重启吞掉——点通道钮与画布操作
                                    // 本就不同指不同时，这个代价远小于写错通道。
                                    // panelScale 入 key：命中半径随面板缩放（放大是给手指大的人服务的），
                                    // 29 §3.2-1；缩放只在拖把手时变化，与画布手势不同指不同时
                                    .pointerInput(channel, gpuMode, panelScale) {
                                        if (!gpuMode) return@pointerInput
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            // 扇面认领过的按压整手忽略：那一指属于通道钮，不在画布落点
                                            if (down.isConsumed) return@awaitEachGesture
                                            // 点画布**不再收扇**（用户 2026-10-04）：点卫星钮稍有偏差落在
                                            // 画布上就会把整扇收掉，体感是"卫星钮吞了我的操作"；收扇只由
                                            // 中心钮再点与选完自动收回承担
                                            down.consume()
                                            if (size.width <= 0 || size.height <= 0) return@awaitEachGesture
                                            val ext = HitExtend.dp.toPx()
                                            val canvasW = (size.width - 2 * ext).coerceAtLeast(1f)
                                            val canvasH = (size.height - 2 * ext).coerceAtLeast(1f)
                                            // 端点角部扩展：画布外 ≤HitExtend 的落点先 clamp 进画布（29 §3.2-1）
                                            val cx = (down.position.x - ext).coerceIn(0f, canvasW)
                                            val cy = (down.position.y - ext).coerceIn(0f, canvasH)
                                            val gx = cx / canvasW
                                            val gy = 1f - cy / canvasH
                                            // 命中半径走像素域（29 §3.2-1）：20dp×面板缩放，下限 14dp
                                            val radiusPx = maxOf(HitRadiusMin.dp.toPx(), HitRadius.dp.toPx() * panelScale)
                                            var working = livePoints.value
                                            var index = CurveEdit.hitTestPx(
                                                working, gx, gy,
                                                radiusPx / canvasW, radiusPx / canvasH
                                            )
                                            if (index < 0) {
                                                val added = CurveEdit.inserted(working, gx, gy)
                                                if (added != null) {
                                                    working = added.first
                                                    index = added.second
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            }
                                            // 已到控制点上限且没点到现有点：本次手势什么都不做
                                            if (index >= 0) {
                                                canvasGesture = true
                                                val deletable = index > 0 && index < working.size - 1
                                                val secondTap = selected == index && deletable
                                                selected = index
                                                // 抓到点的确认震动（29 §3.2-4）
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                commit(working, persist = false)
                                                var dragging = true
                                                var moved = false
                                                var deleted = false
                                                while (dragging) {
                                                    // 长按删除（29 §3.2-3，已裁决）：未移动且长按到时 →
                                                    // 删中间点 + 震动，手势即止；端点不可删走普通等待
                                                    val change = if (!moved && deletable) {
                                                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                                            awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                                        }
                                                    } else {
                                                        awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                                    }
                                                    if (change == null && !moved) {
                                                        // 超时 = 长按成立：删点（端点走不进这支）
                                                        commit(CurveEdit.removed(livePoints.value, index), persist = true)
                                                        selected = -1
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        deleted = true
                                                        break
                                                    }
                                                    if (change == null) break
                                                    dragging = change.pressed
                                                    if (!moved &&
                                                        change.positionChange().getDistance() >
                                                        viewConfiguration.touchSlop
                                                    ) moved = true
                                                    if (moved) {
                                                        working = CurveEdit.moved(
                                                            working, index,
                                                            (change.position.x - ext).coerceIn(0f, canvasW) / canvasW,
                                                            1f - (change.position.y - ext).coerceIn(0f, canvasH) / canvasH
                                                        )
                                                        commit(working, persist = false)
                                                    }
                                                    change.consume()
                                                }
                                                when {
                                                    deleted -> Unit
                                                    // 二次点按改为「取消选中」（29 §3.2-3：与长按删除冲突，
                                                    // 快双击误删是 F3 的摩擦点之一）
                                                    secondTap && !moved -> selected = -1
                                                    else -> commit(working, persist = true)
                                                }
                                                canvasGesture = false
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
                                    // 重置连边长一起回设计档并落盘（29 §1.2：重置写回 200）
                                    savedSide = com.wotagei.cam.core.CurvePanelSize.DEFAULT_SIDE_DP
                                    WotaSettings.setCurvePanelSideDp(prefs, savedSide)
                                    fanOpen = false
                                }
                            )
                        }
                    }
                    // 右缘等比缩放把手（2026-10-25）：面板**外缘**的命中条（不占画布触区；
                    // 面板 Box 有 clip，clip 区外的子级收不到触点，所以必须是面板的兄弟），
                    // 横向拖拽 1:1 换算边长增量（px→dp 走 scope density）；下限 = 最小尺寸，
                    // 上限 = 可用区。**抬手才落盘一次**（29 §1.3 守卫：不许逐帧写 prefs）。
                    // 无 a11y 等价操作——画布拖点本身亦无（与既有口径一致）
                    val handleHeight = with(LocalDensity.current) { panelPx.height.toDp() }
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(panelPx.width, 0) }
                            .height(handleHeight)
                            .width(ResizeHit.dp)
                            .pointerInput(baseSide, maxSide) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val startSide = savedSide
                                    val startX = down.position.x
                                    val pxPerDp = density
                                    while (true) {
                                        val change = awaitPointerEvent()
                                            .changes.firstOrNull { it.id == down.id } ?: break
                                        savedSide = com.wotagei.cam.core.CurvePanelSize.clamp(
                                            startSide + (change.position.x - startX) / pxPerDp,
                                            maxSide.value,
                                            MinCurveSide.toFloat()
                                        )
                                        change.consume()
                                        if (!change.pressed) break
                                    }
                                    // 抬手（含拖到外面取消按压的路径）才写盘：一次手势一次
                                    WotaSettings.setCurvePanelSideDp(prefs, savedSide)
                                }
                            },
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .padding(vertical = 24.dp)
                                .width(3.dp)
                                .clip(CircleShape)
                                .background(WotaColor.acrylicBorder)
                        )
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

/** 曲线框设计边长与下限；200dp 同时是右缘缩放的**默认最小值** */
private const val CurveSide = 200
private const val MinCurveSide = 120

/** 抓点命中半径（29 §3.2-1，像素域）：20dp×面板缩放，下限 14dp——归一化常数在面板放大后相对手指变小 */
private const val HitRadius = 20
private const val HitRadiusMin = 14

/** 端点角部扩展（29 §3.2-1）：命中区比画布四边各外扩的 dp；越界落点先 clamp 回画布再判命中 */
private const val HitExtend = 12

/** 右缘缩放把手的命中条宽（dp），贴面板右缘外侧 */
private const val ResizeHit = 22

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
    // 相邻钮 45° 角距 64dp 下，48dp **方形**命中盒按轴向判**相交**（相邻圆心差 dx≈45.25 /
    // dy≈18.75dp，双双 <48）——命中条真实存在；「弦距 ≈49dp 不相交」那笔账只对圆盒成立，
    // 别再拿它当方盒不相交的证据。同一指落进命中条时由 fanTap 的 isConsumed 整手让路兜底
    // （见该处注释）。fanTap 在 graphicsLayer **内侧**——外侧挂法命中留在布局原位、
    // 视觉/命中分离，点扇面位置永远点空（真机实证）
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
            // 相邻卫星钮的 48dp 方形命中盒在 45° 角距下真实相交（上面"弦距 ≈49dp > 48dp 不相交"
            // 那笔账只对圆盒成立，方盒要按轴向判：相邻圆心差 dx≈18.75 / dy≈45.25dp，双双 <48；
            // 展开途中 frac<1 时交叠更大）。Compose 对相交的兄弟节点是**全部**派发，命中序 = z 序
            // （后声明先测）⇒ 先测那枚已消费 down 时，后来者必须整手让路——不让路就是同一指
            // 两枚都在抬手放行 currentTap，一指落进相交条点出两次选通道（z 序靠后执行者胜）。
            // 本文件其余三层（捕获/吞噬/画布）靠的就是同一条 isConsumed 让路约定。
            if (down.isConsumed) return@awaitEachGesture
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

/** 直方图按通道取哪一组：R/G/B 各自的组，master 取亮度 Y（29 §2.2-2） */
private fun histogramBase(channel: Int): Int = when (channel) {
    ColorCurve.CHANNEL_RED -> 0
    ColorCurve.CHANNEL_GREEN -> com.wotagei.cam.core.HistogramMath.BINS
    ColorCurve.CHANNEL_BLUE -> 2 * com.wotagei.cam.core.HistogramMath.BINS
    else -> com.wotagei.cam.core.HistogramMath.LUMA_BASE
}

/**
 * 「背景色趋势」（29 §2.2-3）：按通道着色的横向渐变（暗端通道色 → 高端补色），
 * 透明度 0.2 在 0.18–0.25 档内；master 用中性灰阶（不引入色偏）。色标沿用
 * [channelColor]，不新增色板。
 */
private fun trendColors(channel: Int): List<Color> = when (channel) {
    ColorCurve.CHANNEL_RED -> listOf(Color(0x33EF6A5A), Color(0x3350C8D8))
    ColorCurve.CHANNEL_GREEN -> listOf(Color(0x336FCE8F), Color(0x33D66AA0))
    ColorCurve.CHANNEL_BLUE -> listOf(Color(0x335FA8E8), Color(0x33D8C050))
    else -> listOf(Color(0x33111111), Color(0x33EEEEEE))
}

/** 折线直接用 [ColorCurve.table] 采样：画出来的就是真正上传给 GPU 的那张表 */
@Composable
private fun CurveCanvas(
    points: List<ColorCurve.Point>,
    selected: Int,
    color: Color,
    enabled: Boolean,
    animateJump: Boolean,
    scale: Float,
    histogram: IntArray? = null,
    channel: Int = ColorCurve.CHANNEL_MASTER,
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
        // 拖动手势期间（animateJump=false）无条件直贴：快拖的单帧偏移会超 0.08 判据，
        // morph 又被每帧新 target 反复重启回 0——曲线钉在起跳点追不上手指（2026-10-25
        // 「不跟手」的根因）。morph 只服务换通道/重置的整条跳变（animateJump=true）
        if (!animateJump) {
            shown.value = target
            return@LaunchedEffect
        }
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
        // 线宽与点半径一律走 dp：DrawScope 自带 Density，写裸数字会在 2x 屏上糊成看不见的点。
        // 交互内容（线/节点）随面板等比缩放同步放大（放大是为手指大的人服务的，点不放大就
        // 失去意义）；网格与 y=x 基准是背景家具，不缩放
        val dot = 6.5.dp.toPx() * scale
        val dotSel = 9.dp.toPx() * scale
        drawRect(WotaColor.bg)
        // 背景色趋势（29 §2.2-3）：按通道着色的横向渐变条铺在画布底部，画在网格/曲线之下
        val trendStrip = 8.dp.toPx()
        drawRect(
            Brush.horizontalGradient(trendColors(channel)),
            topLeft = Offset(0f, h - trendStrip),
            size = Size(w, trendStrip)
        )
        // 直方图背景（2026-10-27 起；29 §2.2 改**按通道**）：只画当前通道那一组——
        // master 用亮度 Y（中性白），R/G/B 各用自己的通道色，不再三色混排；
        // 随画布尺寸缩放（条宽 = w/64，条高 = 归一计数 × h）。口径 = 曲线输出后的画面。
        if (histogram != null) {
            val heights = com.wotagei.cam.core.HistogramMath.normalizedHeights(histogram)
            val barW = w / com.wotagei.cam.core.HistogramMath.BINS
            val base = histogramBase(channel)
            val tint = channelColor(channel).copy(alpha = 0.30f)
            for (i in 0 until com.wotagei.cam.core.HistogramMath.BINS) {
                val hgt = heights[base + i]
                if (hgt <= 0f) continue
                drawRect(
                    tint,
                    topLeft = Offset(w * i / com.wotagei.cam.core.HistogramMath.BINS, h * (1f - hgt)),
                    size = androidx.compose.ui.geometry.Size(barW + 0.5f, h * hgt)
                )
            }
        }
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
        drawPath(path, strokeColor, style = Stroke(width = 2.dp.toPx() * scale))
        points.forEachIndexed { i, p ->
            val c = Offset(w * p.x, h * (1f - p.y))
            val endpoint = i == 0 || i == points.size - 1
            // 选中 1.2 倍放大；端点略大（29 §3.2-2：端点要与中间点可区分）
            val r = when {
                i == selected -> dotSel * 1.2f
                endpoint -> dot * 1.15f
                else -> dot
            }
            if (endpoint) {
                // 端点双层环：外环通道色 + 内芯背景色（29 §3.2-2），与中间点的空心样式区分
                drawCircle(dotColor, radius = r, center = c)
                drawCircle(WotaColor.bg, radius = r * 0.62f, center = c)
            } else {
                // 空心点：实心点在亮画面上会和曲线糊成一团
                drawCircle(if (i == selected) dotColor else WotaColor.textHi, radius = r, center = c)
                drawCircle(WotaColor.bg, radius = r - 2.5.dp.toPx(), center = c)
                if (i == selected) drawCircle(dotColor, radius = r - 2.5.dp.toPx(), center = c)
            }
        }
    }
}
