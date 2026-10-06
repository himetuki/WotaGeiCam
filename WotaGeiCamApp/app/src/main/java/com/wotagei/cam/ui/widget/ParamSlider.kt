package com.wotagei.cam.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaRec
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import kotlin.math.roundToInt

/**
 * 一档药丸：[value] 是写回参数总线的原始值，[label] 是展示文案，[supported]=false 即本机不支持（灰显）。
 *
 * 【`supported` 的灰化目前是死路，别误当成在用】没有任何 [TierPicker] 调用点传 `supported=false`
 * （SettingsScreen 各档全是默认 true，唯一显式传参的 TEXT_SCALE_PCTS 也传 `true`），所以本控件里
 * 吃它的 [TierPill] 灰化分支尚无活消费方、`tierAlpha` 恒 1f。注意：`shutterItems` 也会构造
 * `supported=false` 的 [TierItem]，但那份列表的消费方是 `CameraPills.ShutterPill` 转成 `PillOption`
 * 走 `PillChoices`/`WotaChip`，**不经过本控件**——带 supported 语义的快门档是那条路，不是这条。
 */
data class TierItem(val value: Int, val label: String, val supported: Boolean = true)

/** 长按药丸后出现的连续滑杆域；[max] <= [min] 表示该参数没有连续域 */
data class SliderDomain(val min: Int, val max: Int, val step: Int = 1) {
    val usable: Boolean get() = max > min
}

/**
 * 离散档位药丸 + 长按切连续滑杆（06 文档 §4 的 `ParamSlider`）。
 *
 * 灰显档点击回调 [onUnsupported]（上层弹「本机不支持」提示）；[zeroValue] 非空时双击数值文本归零。
 */
@Composable
fun TierPicker(
    label: String,
    selected: Int,
    items: List<TierItem>,
    format: (Int) -> String,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    slider: SliderDomain? = null,
    enabled: Boolean = true,
    zeroValue: Int? = null,
    onUnsupported: () -> Unit = {},
    note: String? = null
) {
    val continuousDomain = slider?.takeIf { it.usable }
    var continuous by remember(label) { mutableStateOf(false) }
    val showSlider = continuous && continuousDomain != null
    Column(modifier.fillMaxWidth()) {
        ValueHeader(
            label = label,
            valueText = format(selected),
            onDoubleTapZero = zeroValue?.let { z -> { onPick(z) } },
            sliderMode = if (continuousDomain == null) null else showSlider,
            onToggleSlider = { continuous = !continuous },
            enabled = enabled
        )
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = WotaTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        val domain = continuousDomain
        if (showSlider && domain != null) {
            ContinuousSlider(
                label = label,
                value = selected.toFloat(),
                valueRange = domain.min.toFloat()..domain.max.toFloat(),
                onValueChange = { onPick(it.roundToInt()) },
                quantize = domain.step.toFloat(),
                enabled = enabled,
                zeroValue = zeroValue?.toFloat(),
                format = { format(it.roundToInt()) },
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    TierPill(
                        text = item.label,
                        selected = item.value == selected,
                        supported = item.supported && enabled,
                        onClick = { if (item.supported) onPick(item.value) else onUnsupported() },
                        onLongPress = { if (domain != null && enabled) continuous = true }
                    )
                }
            }
        }
    }
}

/**
 * 纯连续滑杆：越界钳制 + 可选量化步进（复用已单测的 [snap]）+ 双击数值文本归零。
 * 值落在域外（换挡后本机能力收窄是常态）时数值转红，避免看着合法其实做不到。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinuousSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    quantize: Float = 0f,
    zeroValue: Float? = null,
    enabled: Boolean = true,
    unavailableText: String? = null,
    format: (Float) -> String = { it.toString() }
) {
    val usable = valueRange.endInclusive > valueRange.start
    if (!usable && unavailableText != null) {
        Text(
            text = unavailableText,
            style = MaterialTheme.typography.bodyMedium,
            color = WotaTextDim,
            modifier = modifier.padding(vertical = 6.dp)
        )
        return
    }
    val latest by rememberUpdatedState(onValueChange)
    val outOfRange = value < valueRange.start || value > valueRange.endInclusive
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) WotaText else WotaTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = format(value),
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = when {
                    !enabled -> WotaTextDim
                    outOfRange -> WotaRec
                    else -> WotaAccent
                },
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .clip(WotaShape.small)
                    .combinedClickable(
                        enabled = zeroValue != null,
                        onClick = { },
                        onDoubleClick = { zeroValue?.let(latest::invoke) }
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { latest(snap(it, quantize, valueRange.start, valueRange.endInclusive)) },
            valueRange = valueRange,
            enabled = enabled && usable,
            colors = SliderDefaults.colors(
                thumbColor = WotaAccent,
                activeTrackColor = WotaAccent,
                inactiveTrackColor = WotaDivider
            )
        )
    }
}

/**
 * 量化到步长栅格。两个易错点都在这里修掉（`internal` 供 JVM 单测直接打）：
 * - 栅格必须锚在范围下限而不是 0：快门域的上下限直接来自 HAL，常见 390625ns（1/2556s）
 *   这类非步长整数倍的值，锚在 0 会让「下限那一档」根本拖不到；
 * - 量化后必须再回钳：四舍五入会把值顶出上限（lo=0、hi=2399、step=50 时 2399→2400），
 *   请求层虽有 clamp 兜底，但参数总线和 HUD 会显示一个本机做不到的数，换挡时还会被悄悄改回去。
 */
internal fun snap(value: Float, step: Float, lo: Float, hi: Float): Float {
    if (step <= 0f) return value
    val q = lo + ((value - lo) / step).roundToInt() * step
    return q.coerceIn(lo, hi)
}

// ------------------------------------------------------------------ 内部件

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TierPill(
    text: String,
    selected: Boolean,
    supported: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) motion.pressScale else 1f,
        animationSpec = motion.float
    )
    // 借 WotaChip 的配色语义而不直接用它：它内部写死 WotaType.chip，「文本高度」设置会对档位失效
    // #81 第 1 条：选中底色与文字色即时切换（禁动画颜色）；"本机不支持"的灰化走下面的 alpha（允许档）
    // ⚠ alpha 必须排在绘制类 modifier（wotaCard 的描边/background 的选中底）**之前**：modifier 链左为外、
    // 右为内，`alpha` 只作用在它右边的绘制上——排在 background 之后时只淡了文字，整档并没灰化
    // （与本轮 CompareScreen 双黑真因同族）。这里 alpha 紧跟 graphicsLayer(scale)，覆盖描边+底+文字。
    // 选中底走 accentSurface：选中胶囊上压的是 onAccent 白字小字，白字对 #007DFF(accent)
    // 只有 3.91:1 不过 AA 正文，对 #0A59F7 5.55:1 过（见 WotaColor.accentSurface 注）
    val tierFill = if (selected) WotaColor.accentSurface else Color.Transparent
    val tierAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (supported) 1f else 0.35f,
        animationSpec = motion.float
    )
    val tierTextColor = if (selected) WotaColor.onAccent else WotaColor.textHi
    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(tierAlpha)
            .wotaCard(WotaShape.pill)
            .background(tierFill)
            .clip(WotaShape.pill)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                interactionSource = interaction,
                indication = null
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = tierTextColor
        )
    }
}

/** 左标签 + 右当前值（双击归零）+ 药丸/滑杆输入方式切换按钮 */
@Composable
private fun ValueHeader(
    label: String,
    valueText: String,
    onDoubleTapZero: (() -> Unit)?,
    sliderMode: Boolean?,
    onToggleSlider: (() -> Unit)?,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) WotaText else WotaTextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = valueText,
            style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
            color = if (enabled) WotaAccent else WotaTextDim,
            modifier = if (onDoubleTapZero == null) Modifier
            else Modifier
                // 双击归零要有可点的命中区，同时不裁掉长数值
                .widthIn(min = 56.dp, max = 110.dp)
                .height(26.dp)
                .pointerInput(onDoubleTapZero) {
                    detectTapGestures(onDoubleTap = { onDoubleTapZero.invoke() })
                }
        )
        if (sliderMode != null && onToggleSlider != null) {
            IconButton(onClick = onToggleSlider, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = if (sliderMode) Icons.Filled.Tune else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.cam_switch_input_mode),
                    tint = WotaTextDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
