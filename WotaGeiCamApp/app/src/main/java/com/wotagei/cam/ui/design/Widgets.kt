package com.wotagei.cam.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wotagei.cam.ui.anim.LocalMotion
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween

/**
 * 参考图语言里的四类基础控件：胶囊 chip、圆形图标钮、标签在上数值在下的参数卡、白描边角标。
 * 三屏重排只准用这几个，别再各自写一份 background+padding 的一次性组合，否则观感一定会漂。
 */

/** 常驻控件卡的通用底：25% 透明暖黑 + 极淡高光边（分层靠透明度，不靠投影） */
fun Modifier.wotaCard(shape: androidx.compose.ui.graphics.Shape = WotaShape.card): Modifier =
    clip(shape).background(WotaColor.hudScrim).border(WotaStroke.hairline, WotaColor.acrylicBorder, shape)

/**
 * 胶囊的**两档内剂量**（任务 #70 B）。做成"档"而不是直接改 [WotaChip] 的理由写在字段注释里：
 * 全局档是几处宽度账的输入，改它等于顺手改了别处的算式。
 *
 * - [horizontalPad]：Row 的左右内边距（上下两档共用 6dp，本任务只收宽度）；
 * - [minLabelEm]：主标签的最短宽度下限，单位是「几个汉字」（13sp × 该系数 × fontScale，走 sp 所以跟着字体缩放涨）。
 *   下限的作用见 [WotaChip] 原注释：两字标签别配成长胶囊。
 */
enum class WotaChipTier(val horizontalPad: Dp, val minLabelEm: Float) {
    /**
     * 全局档：`horizontalPad = WotaSpace.m`（12dp，与 #70 之前那枚字面量同值，只是改挂令牌）、下限 3 汉字。
     *
     * **不许收窄**：播放器 4 处、曲线面板 2 处、就近胶囊弹窗的选项 2 处、编辑页操作栏 4 处、
     * 取景顶栏那两颗与右下读数块、以及**底栏那颗镜头胶囊**共用这一档
     * （设置页那排不吃这个件：它走 `SettingsScreen.ChipCell`，自带同一条 3 汉字下限），
     * 而 `HudLayer.DockSlotSpace = 63dp`（= 13sp × 3 + 12 + 12）是按它量出来的槽宽，
     * 那个数又是 #71 底板收拢算式的 `w(0)` 起点。动这里 = 连带改 #71 的输入。
     */
    Standard(WotaSpace.m, 3f),

    /**
     * 竖 Dock 专用紧凑档：左右各一枚 [WotaSpace.s]（8dp）、下限 2 汉字。
     *
     * 只有 `HudZone.LEFT` / `HudZone.RIGHT` 走这一档（裁决在 `HudLayer.chipTierFor`，唯一一处）。
     * 一颗四汉字标签的控件胶囊：52 + 24 = 76dp → 52 + 16 = **68dp**（−8dp）。
     * 装不下字的风险没有：竖 Dock 的底板是**包内容**宽（`HudDockZone` 不写死宽度），
     * 收窄只让底板跟着变窄，`Text` 的自然宽一直在 `widthIn(min=)` 之下没有上限截它。
     */
    Dock(WotaSpace.s, 2f)
}

/**
 * 胶囊 chip：参考图顶栏「广角 13mm / 1080p 25p / 96.5G 3h20m」那一排。**全局档**，见 [WotaChipTier.Standard]。
 * [selected] 换成 accent 实底；[valueColor] 给「剩余空间不足」这类告警读数；[dot] 是录制中的红点。
 * [onClick] 传 null 就是**纯读数**（不响应点击、无按压形变）：状态类胶囊不该假装是入口。
 */
@Composable
fun WotaChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    secondary: String? = null,
    valueColor: Color? = null,
    dot: Color? = null
) = WotaChipImpl(
    label, selected, modifier, onClick, onLongClick, secondary, valueColor, dot, WotaChipTier.Standard
)

/**
 * 竖 Dock 里的那一颗胶囊：与 [WotaChip] **同一个实现**，只换一档内剂量（[WotaChipTier.Dock]，任务 #70 B）。
 *
 * 为什么另开一个入口而不给 `WotaChip` 加形参：全局那处的调用点一个都不许跟着变（见
 * [WotaChipTier.Standard] 那笔宽度账）；而"同一个实现"是硬性要求——`Widgets.kt` 文件头立着
 * 「别再各自写一份 background+padding 的一次性组合，否则观感一定会漂」，复制一份胶囊就违反它。
 */
@Composable
fun WotaDockChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    secondary: String? = null,
    valueColor: Color? = null
) = WotaChipImpl(
    label, selected, modifier, onClick, onLongClick, secondary, valueColor, null, WotaChipTier.Dock
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WotaChipImpl(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    secondary: String?,
    valueColor: Color?,
    dot: Color?,
    tier: WotaChipTier
) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    /**
     * 选中底色与文字色都走动画档。
     *
     * `WotaChip` 是顶栏读数、曲线面板的通道/色调档、各胶囊弹窗的选项共用的控件，
     * 原来选中态是 `if (selected) then(background(accent))` 的硬切：切通道时底色和字色同一帧跳掉。
     * 文字色必须与底色用**同一条 spec**，否则底色还在渐变、字已经先变成 onAccent。
     */
    val colorSpec = tween<Color>(motion.durationMs)
    val fill by animateColorAsState(
        if (selected) WotaColor.accent else Color.Transparent,
        colorSpec
    )
    val animatedLabelColor by animateColorAsState(
        if (selected) WotaColor.onAccent else WotaColor.textHi,
        colorSpec
    )
    val clickable = if (onClick == null) modifier else modifier
        .clip(WotaShape.pill)
        .then(
            // 长按开面板、点按循环取值是取景器参数胶囊的一对动作（鸿蒙化第 2 条），
            // 共用同一枚 interactionSource，按压缩放才不会只认其中一个手势
            if (onLongClick == null) Modifier.clickable(
                interactionSource = interaction, indication = null, onClick = onClick
            ) else Modifier.combinedClickable(
                interactionSource = interaction, indication = null,
                onClick = onClick, onLongClick = onLongClick
            )
        )
    Row(
        clickable
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .wotaCard(WotaShape.pill)
            .background(fill)
            .clip(WotaShape.pill)
            .padding(horizontal = tier.horizontalPad, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
        }
        Text(
            text = label,
            // 最短 3 个汉字长（全局档）：与设置页 ChipCell 同一条规则，避免两字标签配长胶囊。
            // 档位在 tier 里，竖 Dock 那两处走 Dock 档（2 汉字 + 8dp），#70 B
            modifier = Modifier.widthIn(
                min = (WotaType.chip.fontSize.value * tier.minLabelEm * LocalDensity.current.fontScale).dp
            ),
            style = WotaType.chip,
            color = valueColor ?: animatedLabelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (secondary != null) {
            Text(
                text = secondary,
                style = WotaType.label,
                color = if (selected) WotaColor.onAccent else WotaColor.textLo,
                maxLines = 1
            )
        }
    }
}

/** 圆形图标钮（参考图右上角网格/亮度、右下 1x 都是这一枚） */
@Composable
fun WotaIconButton(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = WotaHit.iconButton,
    glyph: Dp = WotaHit.iconGlyph
) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .wotaCard(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            image,
            contentDescription = description,
            tint = if (selected) WotaColor.accent else WotaColor.textHi,
            modifier = Modifier.size(glyph)
        )
    }
}


/**
 * 缩略图上的白描边角标：深色字 + 白环 + 半透明白底。
 * 纯 tint 图标压在亮画面上会整个看不见（真机截图核对过），所以必须自带底衬。
 */
@Composable
fun WotaOutlineIcon(
    image: ImageVector,
    description: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    glyph: Dp = 15.dp,
    stroke: Dp = 1.5.dp,
    edge: Color = Color.White,
    ink: Color = WotaColor.outlineInk
) {
    Box(
        modifier
            .size(size)
            .let { if (onClick != null) it.clip(CircleShape).clickable(onClick = onClick) else it }
            .background(edge.copy(alpha = 0.45f), CircleShape)
            .border(stroke, edge, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(image, contentDescription = description, tint = ink, modifier = Modifier.size(glyph))
    }
}

/** 缩略图左下角时长角标 */
@Composable
fun WotaDurationBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = WotaType.mono.copy(fontSize = 11.sp, lineHeight = 13.sp),
        color = WotaColor.textHi,
        maxLines = 1,
        modifier = modifier
            .wotaCard(WotaShape.pill)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 紧凑菜单的一行 */
data class WotaMenuEntry(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * 紧凑菜单弹层。不用 Material3 `AlertDialog`：它的 24dp 内边距 + 固定行距会把 7 项撑到超出屏幕，
 * 真机上「分类标签/重命名/彻底删除」三项直接被裁掉看不见。
 * 这里行高固定 34dp、超高滚动、点外关闭，弹层本身用近实底保证压在画面上可读。
 */
@Composable
fun WotaMenuPopup(
    onDismiss: () -> Unit,
    entries: List<WotaMenuEntry>,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    Popup(
        alignment = Alignment.BottomCenter,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true),
        onDismissRequest = onDismiss
    ) {
        // 长按卡片弹出的菜单原本是「啪」地贴上来，和就近胶囊弹窗对不上，这里补同一套入场：
        // 淡入 + 上浮 + 微缩放。用 Animatable 而不是 animateFloatAsState——
        // 后者在首次组合就直接落在目标值，弹层这种「刚出现就要动」的场景它不会跑
        val motion = LocalMotion.current
        val appear = remember { androidx.compose.animation.core.Animatable(0f) }
        val slidePx = with(LocalDensity.current) { 14.dp.toPx() }
        androidx.compose.runtime.LaunchedEffect(Unit) { appear.animateTo(1f, motion.float) }
        Column(
            modifier
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * slidePx
                    scaleX = 0.97f + 0.03f * appear.value
                    scaleY = 0.97f + 0.03f * appear.value
                }
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .widthIn(max = 300.dp)
                .clip(WotaShape.large)
                .background(WotaColor.surface.copy(alpha = 0.97f))
                .border(1.dp, WotaColor.acrylicBorder, WotaShape.large)
                .padding(vertical = 4.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = WotaType.label,
                    color = WotaColor.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                entries.forEach { entry ->
                    Text(
                        text = entry.label,
                        style = WotaType.chip,
                        color = if (entry.danger) WotaColor.rec else WotaColor.textHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(WotaShape.small)
                            // 不在这里替调用方 dismiss：有些项要打开二级弹窗，
                            // 菜单先卸载就会把二级弹窗的状态一起带走（真机踩过）
                            .clickable(onClick = entry.onClick)
                            .padding(horizontal = 14.dp),
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}
