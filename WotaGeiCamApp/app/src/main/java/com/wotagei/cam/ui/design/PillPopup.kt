package com.wotagei.cam.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.wotagei.cam.R
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import com.wotagei.cam.ui.anim.LocalMotion

/** 弹窗内容区最多占可视区高度的比例；超了就内部滚，保证「收起」永远在屏内 */
private const val MAX_HEIGHT_RATIO = 0.62f

/**
 * 就近锚定的胶囊小弹窗（仿 HarmonyOS Next 的控件级浮层）。
 *
 * 三条硬约定，都是为了让"控件即入口"这件事不打断取景：
 * - **同层浮层**，不用系统 `Dialog`/独立 Activity：系统弹层的遮罩会盖住取景器，边改边看就废了
 *   （与 `CameraDialogs` 早就定下的"同层覆盖"同一结论）；
 * - **弹窗只做被点那件事**：点分辨率只改分辨率，不顺手打开一整页参数；
 * - 点外部 / 返回键即关，当前档高亮。
 */
object PillAnchor {

    /**
     * 把弹窗摆到锚点附近：垂直优先放锚点**上方**（录制页控件大多贴在屏幕下沿，往上才不压控件本身），
     * 上方放不下退到下方，两侧都放不下就贴可视区底边；水平以锚点中心对齐并收回边距内。
     *
     * 纯函数、单位全是 px，所以贴边、贴角、弹窗比可视区还高这些情况都能 JVM 单测钉死。
     */
    fun place(anchor: IntRect, popup: IntSize, area: IntSize, gap: Int, margin: Int): IntOffset {
        val right = max(margin, area.width - margin - popup.width)
        val bottom = max(margin, area.height - margin - popup.height)
        val above = anchor.top - gap - popup.height
        val below = anchor.bottom + gap
        val y = when {
            above >= margin -> above
            below + popup.height <= area.height - margin -> below
            else -> bottom
        }
        val x = ((anchor.left + anchor.right) / 2 - popup.width / 2).coerceIn(margin, right)
        return IntOffset(x, y.coerceIn(margin, bottom))
    }
}

/** 记录控件在窗口里的矩形给上层当锚点（窗口坐标，与 `Popup` 偏移同一坐标系） */
fun Modifier.pillAnchor(onRect: (IntRect) -> Unit): Modifier =
    onGloballyPositioned {
        val r = it.boundsInWindow()
        onRect(
            IntRect(
                r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()
            )
        )
    }

/** 一档可选项：[value] 是写回参数总线的原始值，[disabledTip] 是灰显档被点时的提示文案 */
data class PillOption(
    val value: Int,
    val label: String,
    val enabled: Boolean = true,
    val disabledTip: String? = null
)

/** 弹窗内的档位行：横向铺开、当前档 accent 实底，与顶栏胶囊同一套视觉。[label] 非空时给一行小标题 */
@Composable
fun PillChoices(
    options: List<PillOption>,
    selected: Int,
    onPick: (PillOption) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    label: String? = null
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) PillGroupLabel(label)
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { opt ->
                    WotaChip(
                        label = opt.label,
                        selected = opt.value == selected,
                        // 不支持的档位必须看得出「灰着」：抽屉时代 TierPicker 自带淡显，
                        // 胶囊里若不淡显，点上去只弹一句「本机不支持」，等于让用户试错（真机 7680x4320 踩过）
                        valueColor = if (opt.enabled) null else WotaColor.textLo,
                        onClick = { onPick(opt) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 末行不满时补位，保证每个 chip 同宽
                repeat(max(0, columns - row.size)) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 多选档（参考线那类「同时开好几项」的控件）：与 [PillChoices] 同一套视觉，
 * 但选中态由调用方的 [isOn] 判定而不是比单个 selected 值。
 */
@Composable
fun PillToggles(
    options: List<PillOption>,
    isOn: (PillOption) -> Boolean,
    onToggle: (PillOption) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    label: String? = null
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) PillGroupLabel(label)
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { opt ->
                    WotaChip(
                        label = opt.label,
                        selected = isOn(opt),
                        valueColor = if (opt.enabled) null else WotaColor.textLo,
                        onClick = { onToggle(opt) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(max(0, columns - row.size)) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** 带说明行的一维列表（镜头选择这类"每颗还要给等效焦距/视场角"的档） */
data class PillRow(val key: String, val label: String, val detail: String, val active: Boolean)

@Composable
fun PillRowList(
    rows: List<PillRow>,
    onPick: (PillRow) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) PillGroupLabel(label)
        rows.forEach { row ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(WotaShape.small)
                    .clickable { onPick(row) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = row.label,
                    style = WotaType.chip,
                    color = if (row.active) WotaColor.accent else WotaColor.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.detail,
                    style = WotaType.label,
                    color = WotaColor.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PillGroupLabel(text: String) {
    Text(
        text = text,
        style = WotaType.label,
        color = WotaColor.textLo,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 弹窗内的连续量滑杆：量化与上下限回钳复用 `snap`（同一套已单测过的边界规则）。
 * [label] 只在同一个弹窗里出现两根滑杆时才需要（如白平衡的色温 + 色度），否则标题已经说明白了。
 */
@Composable
fun PillSlider(
    value: Float,
    lo: Float,
    hi: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    format: (Float) -> String = { it.toString() },
    enabled: Boolean = true,
    label: String? = null
) {
    val usable = hi > lo
    val shown = if (usable) value.coerceIn(lo, hi) else value
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (label != null) {
            Text(
                text = label,
                style = WotaType.label,
                color = WotaColor.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = shown,
                onValueChange = { raw ->
                    if (usable) {
                        val v = raw.coerceIn(lo, hi)
                        onValueChange(com.wotagei.cam.ui.widget.snap(v, step, lo, hi))
                    }
                },
                valueRange = if (usable) lo..hi else 0f..1f,
                enabled = enabled && usable,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = WotaColor.accent,
                    activeTrackColor = WotaColor.accent,
                    inactiveTrackColor = WotaColor.outline
                )
            )
            Text(
                text = format(shown),
                style = WotaType.mono.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = if (enabled) WotaColor.textHi else WotaColor.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(min = 54.dp).padding(start = 6.dp)
            )
        }
    }
}

/**
 * 就近胶囊浮层本体。
 *
 * 第一帧量不到弹窗尺寸，所以先以 `alpha = 0` 存在、量到再定位淡入 ——
 * 否则会看见弹窗从锚点位置"跳"一下，这类浮层最忌讳的就是跳。
 */
@Composable
fun WotaPillPopup(
    anchor: IntRect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val root = LocalView.current.rootView
    val gapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { 10.dp.roundToPx() }
    var popupSize by remember { mutableStateOf(IntSize.Zero) }
    val area = IntSize(max(1, root.width), max(1, root.height))
    val placed = PillAnchor.place(anchor, popupSize, area, gapPx, marginPx)
    val measured = popupSize.width > 0 && popupSize.height > 0
    /**
     * 量到真实尺寸之后才淡入+从 0.94 长开。
     *
     * 原来这里是 `alpha = if (measured) 1f else 0f` 的硬切：弹窗会先在左上角闪一帧空框、再瞬移到
     * 锚点旁边，看着像"跳出来"。走动画档之后，锚定跳变被量框那一帧的透明期吃掉，出现动作是连续的。
     */
    val motion = LocalMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(measured) { if (measured) shown = true }
    val appearAlpha by animateFloatAsState(if (shown) 1f else 0f, motion.float)
    val appearScale by animateFloatAsState(if (shown) 1f else 0.94f, motion.float)

    Popup(
        alignment = Alignment.TopStart,
        offset = placed,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true)
    ) {
        // 内容区自己滚、标题与「收起」钉住：本机分辨率有 16 档，整块不滚的话「收起」被顶出屏幕底
        // （720p 横屏真机截图核对），弹窗也会盖住整屏
        val maxContentH = (area.height * MAX_HEIGHT_RATIO / density.density).dp
        Column(
            modifier
                .widthIn(max = 320.dp)
                .onSizeChanged { popupSize = it }
                .graphicsLayer {
                    alpha = appearAlpha
                    scaleX = appearScale
                    scaleY = appearScale
                }
                .clip(WotaShape.large)
                .background(WotaColor.surface.copy(alpha = 0.97f))
                .border(1.dp, WotaColor.acrylicBorder, WotaShape.large)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = WotaType.label,
                    color = WotaColor.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                Modifier.heightIn(max = maxContentH).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
            // 取景页上"点外面"很容易被误当成点按对焦，所以再给一个明确的收起命中区
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(WotaShape.pill)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.pill_dismiss),
                    style = WotaType.label,
                    color = WotaColor.textLo
                )
            }
        }
    }
}
