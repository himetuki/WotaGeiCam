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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.ui.HudFrost
import com.wotagei.cam.ui.anim.LocalMotion
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween

/**
 * 参考图语言里的四类基础控件：胶囊 chip、圆形图标钮、标签在上数值在下的参数卡、白描边角标。
 * 三屏重排只准用这几个，别再各自写一份 background+padding 的一次性组合，否则观感一定会漂。
 */

/**
 * 常驻控件卡的通用底：74.9% 不透明暖黑（alpha 0xBF，透光只有 25.1%）+ 极淡高光边。
 * 分层靠透明度、不靠投影；描边是那条 1dp 高光边，不是"靠描边分层"（观感规范见 docs/plan/11）。
 *
 * ⚠ **这一条只描述"霜没在屏幕上时"的样子**。取景 HUD 的底板走 [#84 步骤 2 的毛玻璃][wotaHudCard]：
 * 霜真在屏幕上时这一层 `background` 让位（74.9% 不透明的 fill 会把下面的霜全盖死，等于没做），
 * 只留描边、选中高亮与内容——那就是本函数去掉中间那一环之后的样子。
 * 纸面页（设置/媒体库/播放器/弹窗）永远走这里，一个像素都不受霜影响。
 */
fun Modifier.wotaCard(shape: androidx.compose.ui.graphics.Shape = WotaShape.card): Modifier =
    clip(shape).background(WotaColor.hudScrim).border(WotaStroke.hairline, WotaColor.acrylicBorder, shape)

/**
 * 一张 HUD 卡片在霜里的身份（#84 步骤 2 · A2 混合路线）。
 *
 * A2 的分工：**GL 只出「模糊纹理 + 圆角裁切 + 底板色」这一块板**，描边、选中高亮、图标、文字
 * 仍旧由 Compose 叠在 TextureView 之上画。所以本类不带任何颜色/笔刷，只带"该注册哪块矩形"这件事；
 * `ui/` 与 `camera/` 之间共享的就是这一张矩形表（`camera/FrostCardTable`），不是任何视觉。
 *
 * @param asPlate true = 自己就是一块玻璃板（把自己的窗口矩形注册进表）；
 *  false = 吃父板的玻璃（只把自己的纯色 fill 让掉，不注册）——竖 Dock 里那几颗胶囊就是这种，
 *  它们背后已经有整块板了，再注册一块只会叠一层。
 * @param radiusPx 圆角半径（px）；**负数 = 短边一半**（胶囊与圆形那一档，实测尺寸只有布局期才知道）。
 *  正数必须来自 [com.wotagei.cam.ui.design.WotaShape.radiusCard] 那批令牌，别写字面量。
 * @param ink 这张板承载的最弱那级文字，透光档位由 [frostScrimAlphaFor] 从它算出来（调用侧不许自带 alpha）。
 */
data class HudFrostCard(val asPlate: Boolean, val radiusPx: Float, val ink: HudInkLevel)

/** 自己出一块板：[radiusPx] 负数走"短边一半"，[ink] 决定透光档 */
fun hudFrostPlate(radiusPx: Float, ink: HudInkLevel = HudInkLevel.SECONDARY): HudFrostCard =
    HudFrostCard(asPlate = true, radiusPx = radiusPx, ink = ink)

/** 吃父板的玻璃：只让掉自己的 fill，不注册矩形 */
val hudFrostInherit: HudFrostCard = HudFrostCard(asPlate = false, radiusPx = 0f, ink = HudInkLevel.PRIMARY)

/**
 * HUD 底板的霜感知版 `wotaCard`：**同一套描边、同一套圆角，只有那层纯色 fill 会在霜真在屏幕上时让位**。
 *
 * 三条不可逆性守卫都落在这函数里：
 * - [frost] 为 null（纸面页/弹窗）或 [com.wotagei.cam.ui.HudFrost.live] 为 false（开关关 / DIRECT /
 *   离屏链停用 / 相机还没出帧）⇒ 走 [wotaCard] 那一条**逐字不变**的链，观感与接霜前完全相同；
 * - `live` 只在**GL 真画了板**之后才为 true，所以不会出现"fill 让开了而底下没有霜"的空窗；
 *   反过来 GL 撤报那一帧起 fill 就回来（撤回的延迟见 HudFrost 那条轮询，≤ 一个轮询周期，真机未量）。
 * - 让位的是 fill，**不是** clip、不是 border、不是内容：圆角仍由 Shape 裁、1dp 高光边仍在这里描、
 *   选中态那层 accent 实底由调用方的 `.background(fill)` 负责（它在链路更内侧，霜动不到它）。
 */
@Composable
fun Modifier.wotaHudCard(
    shape: androidx.compose.ui.graphics.Shape = WotaShape.card,
    frost: HudFrostCard? = null
): Modifier {
    if (frost == null || !HudFrost.live) return this.wotaCard(shape)
    val edged = this.clip(shape).border(WotaStroke.hairline, WotaColor.acrylicBorder, shape)
    if (!frost.asPlate) return edged
    return edged.then(hudFrostRectRegistrar(frost))
}

/**
 * 把这一枚节点的**窗口矩形**注册进 `FrostCardTable`（GL 每帧读那张表来贴板）。
 *
 * 每帧零分配怎么做到的（这一段是本任务唯一的资源纪律，写清以免被后来人"顺手改成每帧 new"）：
 * - 槽位 `remember` 一次，节点离开组合时 `releaseSlot`（fill 让位态结束时槽自动还回去）；
 * - 写表发生在 `LaunchedEffect` 里，**键变了才写**：布局矩形变、`live` 翻转、透光档位变这三件事
 *   才各花一次"表头事务 + 一格 7 个 float"；静止态一次都不写；
 * - 写之前先 [com.wotagei.cam.ui.HudFrost.refreshHeader]：视图原点用到的"组合根尺寸"与卡片矩形
 *   出自同一次布局，两件事不会因为一个改了另一个没跟而错开一整代（跨线程不撕裂那一半由
 *   `FrostCardTable` 的序号锁保证，两处各司其职、不是重复保险）。
 *
 * ⚠ 已知不吃位移：这里量的是**布局**矩形（`positionInWindow`），祖先那层 `graphicsLayer` 的
 * translation（编辑页拖拽、#71 吸收态那颗的飞行）不反映在实测值里——与
 * `ui/anim/LiquidMerge` 为同一个坑写过的注释是一条族谱。底板本身跟着动的那一路（底栏换栏拖拽、
 * 录制态收拢）由 [com.wotagei.cam.ui.anim.wotaDockShell] 自己在绘制期回报**可见**矩形，
 * 所以观感上不会脱节；单个条目被飞出去的位移那几帧不在本轮覆盖范围，留给真机核。
 */
@Composable
private fun Modifier.hudFrostRectRegistrar(frost: HudFrostCard): Modifier {
    val alpha = frostScrimAlphaFor(frost.ink)
    val live = HudFrost.live
    val slot = remember { FrostCardTable.acquireSlot() }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(slot) {
        onDispose {
            if (slot >= 0) FrostCardTable.releaseSlot(slot)
        }
    }
    LaunchedEffect(slot, coords, alpha, live) {
        val c = coords ?: return@LaunchedEffect
        if (slot < 0 || !live) return@LaunchedEffect
        val p = c.positionInWindow()
        val size = c.size
        HudFrost.refreshHeader()
        FrostCardTable.writeCard(
            slot = slot,
            leftPx = p.x,
            topPx = p.y,
            rightPx = p.x + size.width,
            bottomPx = p.y + size.height,
            radiusPx = frost.radiusPx,
            alpha = alpha
        )
    }
    return this.onGloballyPositioned { coords = it }
}

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
 *
 * [frost] 只有取景 HUD 会传（#84 步骤 2）：非 null 且霜真在屏幕上时，那层纯色 fill 让位给底下的玻璃。
 * 纸面页与弹窗一律留空 ⇒ 这个默认值就是"观感与接霜前逐字相同"的那道门，别顺手在别处传。
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
    dot: Color? = null,
    frost: HudFrostCard? = null
) = WotaChipImpl(
    label, selected, modifier, onClick, onLongClick, secondary, valueColor, dot, WotaChipTier.Standard, frost
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
    valueColor: Color? = null,
    frost: HudFrostCard? = null
) = WotaChipImpl(
    label, selected, modifier, onClick, onLongClick, secondary, valueColor, null, WotaChipTier.Dock, frost
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList")
private fun WotaChipImpl(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    secondary: String?,
    valueColor: Color?,
    dot: Color?,
    tier: WotaChipTier,
    frost: HudFrostCard?
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
            // 带 secondary 标签（textLo 那级）的胶囊**不接霜**：14 号计划 §二 的审计表里
            // textLo 在 0xA6~0xB3 这整个透光区间都不合格（最低也只有 2.80 < 4.5），
            // 而"给它自己一层更实的底衬"与"换成 textHi"都超出本轮批准范围（那条欠账明令不许顺手修）。
            // 所以这里把它摘成 null，让它继续用 74.9% 不透明的实底——档位区间是死的，不是保守。
            .wotaHudCard(WotaShape.pill, if (secondary != null) null else frost)
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

/**
 * 圆形图标钮（参考图右上角网格/亮度、右下 1x 都是这一枚）。
 * [frost] 同 [WotaChip]：只有取景 HUD 会传，圆形的半径就是短边一半（送负数哨兵，见 [HudFrostCard]）。
 */
@Composable
fun WotaIconButton(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = WotaHit.iconButton,
    glyph: Dp = WotaHit.iconGlyph,
    frost: HudFrostCard? = null
) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .wotaHudCard(CircleShape, frost)
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
