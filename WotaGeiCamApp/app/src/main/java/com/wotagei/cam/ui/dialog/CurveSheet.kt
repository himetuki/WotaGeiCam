package com.wotagei.cam.ui.dialog

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveEdit
import com.wotagei.cam.core.CurvePreset
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.ui.WotaSettings
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.sheetMaxHeight

/**
 * RGB 曲线面板（需求「RGB 曲线（白、蓝、绿、红）」+ 00 文档 T5 的程序化色调档）。
 *
 * 交互约定：拖点改亮度（端点只能纵向，中间点横纵都行），点空白加控制点，选中后才能删点。
 * 曲线**边拖边生效**——取景器本身就是预览，所以拖动期间只写参数总线，
 * 落盘（SharedPreferences）放在抬手那一刻，避免每帧一次 I/O。
 */
@Composable
fun CurveSheet(
    params: WotaParams,
    gpuMode: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val context = LocalContext.current
    val prefs = remember(context) { WotaSettings.of(context) }
    val stack by params.curve.observed()
    val channelNames = listOf(
        stringResource(R.string.cam_curve_ch_master),
        stringResource(R.string.cam_curve_ch_red),
        stringResource(R.string.cam_curve_ch_green),
        stringResource(R.string.cam_curve_ch_blue)
    )
    val limitText = stringResource(R.string.cam_curve_points_limit, CurveEdit.MAX_POINTS)
    var channel by remember { mutableStateOf(ColorCurve.CHANNEL_MASTER) }

    // 本通道的编辑态：只在换通道时重建。points 绝不能进 pointerInput 的 key——
    // 拖动每帧都在改 points，一改 key 手势就被重启，曲线会「拖不住」
    var points by remember(channel) { mutableStateOf(CurveEdit.pointsOf(stack.curveOf(channel))) }
    var selected by remember(channel) { mutableStateOf(-1) }
    val livePoints = rememberUpdatedState(points)

    // 以总线现值做底、只替换本通道：其它通道在拖动期间可能被色调档改过，不能用手里的旧 stack 合
    fun commit(next: List<ColorCurve.Point>, persist: Boolean) {
        points = next
        val merged = params.curve.value.withCurve(channel, asCurve(next))
        params.curve.value = merged
        if (persist) WotaSettings.setCurveStack(prefs, merged)
    }

    BottomPanel(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.cam_curve_title),
        subtitle = stringResource(if (gpuMode) R.string.cam_curve_tip else R.string.cam_monitor_need_gpu),
        modifier = modifier,
        // 动作行走 BottomPanel 的钉底槽位：排在滚动区之后、不参与滚动——以前它躺在滚动区末尾，
        // 横屏默认态整行沉到 y≈700-719 零亮像素（t8_07），用户每次用曲线都得先上滑才够得着
        bottomBar = {
            // 三枚文字动作保持横排（间距对齐 hw_button 的 12vp）：它们是面板内容工具行、
            // 不是弹窗按钮操作区，竖排会吃掉约 90dp 竖向空间——本面板竖向预算本就按 90% 短边封顶
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallTextButton(stringResource(R.string.cam_curve_del_point)) {
                    if (selected > 0 && selected < points.size - 1) {
                        commit(CurveEdit.removed(points, selected), persist = true)
                        selected = -1
                    }
                }
                SmallTextButton(stringResource(R.string.cam_curve_reset_ch)) {
                    commit(CurveEdit.pointsOf(ColorCurve.IDENTITY), persist = true)
                    selected = -1
                }
                SmallTextButton(stringResource(R.string.cam_curve_reset_all)) {
                    params.curve.value = CurveStack.IDENTITY
                    WotaSettings.setCurveStack(prefs, CurveStack.IDENTITY)
                    points = CurveEdit.pointsOf(ColorCurve.IDENTITY)
                    selected = -1
                }
            }
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            channelNames.forEachIndexed { i, name ->
                WotaChip(
                    label = name,
                    selected = channel == i,
                    onClick = {
                        channel = i
                        points = CurveEdit.pointsOf(params.curve.value.curveOf(i))
                        selected = -1
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 色调档排在画布**上面**：横屏 720px 高时画布吃掉竖向拖动，档位在下面的话根本滚不到
        // （真机实测：滑一下变成拖控制点），而档位又是这个面板最高频的入口
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CurvePreset.values().forEach { preset ->
                WotaChip(
                    label = preset.label,
                    selected = presetActive(stack, preset),
                    onClick = {
                        params.curve.value = preset.stack
                        WotaSettings.setCurveStack(prefs, preset.stack)
                        points = CurveEdit.pointsOf(preset.stack.curveOf(channel))
                        selected = -1
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .size(canvasSide())
                .align(Alignment.CenterHorizontally)
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
                                selected = index
                                commit(working, persist = false)
                                var dragging = true
                                while (dragging) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    dragging = change.pressed
                                    working = CurveEdit.moved(
                                        working, index,
                                        change.position.x / size.width,
                                        1f - change.position.y / size.height
                                    )
                                    commit(working, persist = false)
                                    change.consume()
                                }
                                commit(working, persist = true)
                            }
                        }
                    }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (points.size >= CurveEdit.MAX_POINTS) limitText
            else "${points.size}/${CurveEdit.MAX_POINTS}",
            style = MaterialTheme.typography.labelSmall,
            // 计数压 AcrylicScrim(0xB3)：textLo worst ≈2.83 过不了 scrim 上 3.0 的审计线；
            // scrim 上文字只准 textHi（worst 5.71），弱化语义交还给字号（labelSmall）本身
            color = WotaColor.textHi
        )
    }
}

/**
 * 画布边长：按「面板最大高 − 画布以外全部 chrome」让位（chrome 账见 [PanelChromeH]）。
 * 超出预算的内容交给面板自身的滚动区兜底（动作行已钉底，滚动的只有档位/画布/计数）。
 */
@Composable
private fun canvasSide(): Dp {
    val budget = sheetMaxHeight() - PanelChromeH
    return budget.coerceIn(MinCanvasH, MaxCanvasH)
}

/**
 * 面板内画布以外的竖向开销（fontScale=1 估算，逐项对得上件）：
 *   内边距 12×2=24 ＋ 标题行（titleMedium 24 ＋ 副标题 bodyMedium 两行 40，关闭钮 30 不绑定）
 *   ＋ 分隔 4+1 ＋ 通道胶囊行 28（WotaChip 高 `heightIn(min=28)`）＋ 间距 6 ＋ 色调档行 28
 *   ＋ 间距 8 ＋ 间距 6 ＋ 计数 labelSmall 16 ＋ 钉底动作行（上边距 4 ＋ SmallTextButton
 *   labelMedium 16 + 竖边距 12 = 28）＝ **217dp**。
 * 旧值 198 是漏账的估算（实际同结构 ≈218），正是「画布把动作行挤出屏」的账源之一。
 */
private val PanelChromeH = 217.dp

/**
 * 画布下限取 60dp：真机横屏（窗口 ≈766x360dp、挖孔列被扣）sheetMaxHeight 实测 ≈278dp，
 * 278−217=61 ≥ 60——最差档画布仍装得下、内容不再超面板；再小的窗口由滚动区吸收，
 * 动作行钉底不受影响。上限 230 供平板/竖屏大窗口用满。
 */
private val MinCanvasH = 60.dp
private val MaxCanvasH = 230.dp

/** 色调档是否正在生效：按编码串比对，比引用相等可靠（曲线从 prefs 恢复后仍是同一条） */
private fun presetActive(stack: CurveStack, preset: CurvePreset): Boolean {
    if (preset == CurvePreset.NONE) return stack.isPassthrough
    return stack.encode() == preset.stack.encode()
}

/** 点集回到 y=x 就存成恒等（空点集），这样「原图」档与复位后的编码串能对上 */
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
    // 折线直接用 [ColorCurve.table] 采样：画出来的就是真正上传给 GPU 的那张表
    val target = remember(points) { ColorCurve(points).table(64) }
    val motion = LocalMotion.current
    // 换通道 / 点预设是整条曲线跳变，补间过去；但拖动期间逐帧差很小，必须原样跟手，
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

/** 参数抽屉里那一行的读数 */
@Composable
fun curveSummaryText(stack: CurveStack): String {
    if (stack.isPassthrough) return stringResource(R.string.cam_effect_none)
    CurvePreset.values().firstOrNull { presetActive(stack, it) }?.let { return it.label }
    return stringResource(R.string.cam_curve_custom)
}
