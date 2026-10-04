package com.wotagei.cam.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * 参考图语言里的四类基础控件：胶囊 chip、圆形图标钮、标签在上数值在下的参数卡、白描边角标。
 * 三屏重排只准用这几个，别再各自写一份 background+padding 的一次性组合，否则观感一定会漂。
 */

/**
 * 常驻控件卡的通用底：74.9% 不透明暖黑（alpha 0xBF，透光只有 25.1%）+ 极淡高光边。
 * 分层靠透明度、不靠投影；描边是那条 1dp 高光边，不是"靠描边分层"（观感规范见 docs/plan/11）。
 *
 * 默认圆角是**件位档大卡 24dp**（`WotaShape.card`，design-spec §4.2 `ohos_id_corner_radius_card`，
 * Top 8 #2：相册卡/弹窗/半模态底板这一类）；要别的圆角照旧显式传。
 *
 * ⚠ **这一条只描述"霜没在屏幕上时"的样子**。取景 HUD 的底板走 [#84 步骤 2 的毛玻璃][wotaHudCard]：
 * 霜真在屏幕上时这一层 `background` 让位（74.9% 不透明的 fill 会把下面的霜全盖死，等于没做），
 * 只留描边、选中高亮与内容——那就是本函数去掉中间那一环之后的样子。
 * 纸面页（设置/媒体库/播放器/弹窗）永远走这里，一个像素都不受霜影响。
 */
fun Modifier.wotaCard(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(WotaShape.card)): Modifier =
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
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(WotaShape.card),
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
 * - 写表发生在 `LaunchedEffect` 里，**键变了才写**：布局矩形变、`intent` 翻转、透光档位变这三件事
 *   才各花一次"表头事务 + 一格 7 个 float"；静止态一次都不写；
 * - 写之前先 [com.wotagei.cam.ui.HudFrost.refreshHeader]：视图原点用到的"组合根尺寸"与卡片矩形
 *   出自同一次布局，两件事不会因为一个改了另一个没跟而错开一整代（跨线程不撕裂那一半由
 *   `FrostCardTable` 的双缓冲 + 引用交换保证，两处各司其职、不是重复保险）。
 *
 * ⚠ 已知不吃位移：这里量的是**布局**矩形（`positionInWindow`），祖先那层 `graphicsLayer` 的
 * translation（编辑页拖拽、#71 吸收态那颗的飞行）不反映在实测值里——与
 * `ui/anim/LiquidMerge` 为同一个坑写过的注释是一条族谱。底板本身跟着动的那一路（底栏换栏拖拽、
 * 录制态收拢）由 [com.wotagei.cam.ui.anim.wotaDockShell] 自己在绘制期回报**可见**矩形，
 * 所以观感上不会脱节；单个条目被飞出去的位移那几帧不在本轮覆盖范围，留给真机核。
 */
@Composable
private fun Modifier.hudFrostRectRegistrar(frost: HudFrostCard, visible: Boolean = true): Modifier {
    val alpha = frostScrimAlphaFor(frost.ink)
    // 喂表只看"开关意图"，不看 live —— 用 live 当闸门会启动死锁：卡片要 live 才写、
    // live 要 GL 画过板才真、GL 要表里有卡片才画得出。分工见 HudFrost.intent 的注释。
    val intent = HudFrost.intent
    val slot = if (intent) remember(intent) { FrostCardTable.acquireSlot() } else -1
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(slot) {
        onDispose {
            if (slot >= 0) FrostCardTable.releaseSlot(slot)
        }
    }
    LaunchedEffect(slot, coords, alpha, intent, visible) {
        val c = coords ?: return@LaunchedEffect
        if (slot < 0 || !intent) return@LaunchedEffect
        val p = c.positionInWindow()
        val size = c.size
        HudFrost.refreshHeader()
        if (!visible) {
            // 屏外哨兵：板画在视口外 = 视觉上消失。**注册必须常驻**——若靠离场撤槽让板消失，
            // 面板期其他板一并退场会把表清空 → GL drew=0 → live=false → wotaHudCard 被 live
            // 短路成实底、registrar 永不回来 = 板永久回不来（2026-10-04 Dock 滑出真机死锁实证）
            FrostCardTable.writeCard(
                slot = slot,
                leftPx = -99999f,
                topPx = -99999f,
                rightPx = -99998f,
                bottomPx = -99998f,
                radiusPx = frost.radiusPx,
                alpha = 0f
            )
            return@LaunchedEffect
        }
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
 * 竖 Dock 滑出用的常驻注册（用户 2026-10-04「Dock 背影未收回」修复）：[frostVisible]=false 时
 * 板写屏外哨兵而不是撤槽离场——离场会让 GL 表清空、live 翻 false，恢复被 [wotaHudCard] 的
 * live 短路挡死（死锁机理见 [hudFrostRectRegistrar] 屏外哨兵分支注释）。
 * 本包装与 [wotaHudCard] 霜分支的差别只有一条：**不读 [com.wotagei.cam.ui.HudFrost.live]**，
 * 调用方（HudDockZone）自己决定 fill 是否让位。
 */
@Composable
internal fun Modifier.hudFrostDockRegistrar(radiusPx: Float, frostVisible: Boolean): Modifier =
    hudFrostRectRegistrar(hudFrostPlate(radiusPx, HudInkLevel.PRIMARY), frostVisible)

/**
 * 胶囊的**两档内剂量**（任务 #70 B）。做成"档"而不是直接改 [WotaChip] 的理由写在字段注释里：
 * 全局档是几处宽度账的输入，改它等于顺手改了别处的算式。
 *
 * - [horizontalPad]：Row 的左右内边距（上下两档共用 5dp——HDS 件高 28vp = 18sp 行高 + 2×5dp，
 *   component-map Top 8 #3；2026-10-02 从 6dp 收了 1dp，高从 30 变 28，宽度账不受影响）；
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
 * Chip 的件位形（圆角 18dp = `WotaShape.chip`，design-spec §4.2 `ohos_id_corner_radius_tips_instant_tip`）
 * 与菜单弹层的件位形（20dp = `WotaShape.menu`）。放文件级省得每颗胶囊都 new 一枚。
 * 28dp 高的件上 18dp 半径会被裁成短边一半（14dp），观感仍近全圆——HDS 就是这个口径（design-spec §4.4）。
 */
private val chipShape = RoundedCornerShape(WotaShape.chip)
private val menuShape = RoundedCornerShape(WotaShape.menu)

/**
 * 胶囊 chip：参考图顶栏「广角 13mm / 1080p 25p / 96.5G 3h20m」那一排。**全局档**，见 [WotaChipTier.Standard]。
 * [selected] 换成 [WotaColor.accentSurface] 实底 + 白字（13sp 小字承载面，见该令牌注）；
 * [valueColor] 给「剩余空间不足」这类告警读数；[dot] 是录制中的红点。
 * [onClick] 传 null 就是**纯读数**（不响应点击、无按压形变）：状态类胶囊不该假装是入口。
 *
 * 视觉规格 2026-10-02 对齐 HDS Chip（component-map Top 8 #3，design-spec §4.2/§5.2）：
 * 高 28dp、常态底 [WotaColor.chipBg]（#0C182431 原样照用）、激活 = 强调底 + 白字、圆角 [chipShape]；
 * 行为/语义（[enabled] 通道、[onClick]、命中区）一律没动。
 *
 * [enabled] 是 2026-10-02 给既有组件**补的通道**（真机事故：灰显档只调暗了文字、点上去照样回调，
 * 见 [com.wotagei.cam.ui.dialog.shutterItems] 的口径注释）：它只把点击真正关掉
 * （`clickable(enabled=false)`，无障碍树会如实报 `enabled=false`——这正是灰显档真机验收的判据），
 * **不新造视觉**——灰显观感仍由调用方用 `valueColor = textLo` 那套表达。
 * 默认 `true` 是有意的：这是给既有组件补通道，不是新增必传协作件——缺省值就是所有既有调用点的
 * 现状（一行都不用跟着改），而"调用方该传表达式却漏传"这种错不因必传而拦得住，所以不适用
 * 「就近弹窗锚点无默认值必传」那条纪律。它与 HudLayer.gatedClick 那条「刻意不用 clickable(enabled=false)」
 * 也不冲突：那里断的是命中区（#73 命中权交接，节点要整个让位），这里保的是灰显节点的 Disabled 语义
 * （节点必须留在无障碍树里、只是报 disabled）——两种刻意不同，别"统一"成一种。
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
    enabled: Boolean = true,
    frost: HudFrostCard? = null
) = WotaChipImpl(
    label, selected, modifier, onClick, onLongClick, secondary, valueColor, dot, WotaChipTier.Standard, enabled, frost
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
    enabled: Boolean = true,
    frost: HudFrostCard? = null
) = WotaChipImpl(
    label, selected, modifier, onClick, onLongClick, secondary, valueColor, null, WotaChipTier.Dock, enabled, frost
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
    enabled: Boolean,
    frost: HudFrostCard?
) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    /**
     * 选中底色与文字色**即时切换**（#81 第 1 条定版：只动画 alpha 与 scale，禁动画颜色）。
     * 原来这里走 `animateColorAsState` 渐变（理由是"硬切读起来像卡了一下"）；定版之后两枚值
     * **同一帧**一起换，渐变版最怕的"底还在渐变、字先跳成 onAccent"那类分叉反而结构性不存在。
     *
     * 底色对齐 HDS Chip（component-map Top 8 #3）：常态 = [WotaColor.chipBg]（#0C182431 薄罩，原样照用），
     * 选中 = [WotaColor.accentSurface]——13sp 白字要过 AA 正文，白字对 #007DFF 只有 3.91:1，
     * 对旧蓝 5.55:1（scripts/contrast-audit.py 实测），所以承载小字的选中面不走 [WotaColor.accent]。
     */
    val fill = if (selected) WotaColor.accentSurface else WotaColor.chipBg
    val labelColor = if (selected) WotaColor.onAccent else WotaColor.textHi
    val clickable = if (onClick == null) modifier else modifier
        .clip(chipShape)
        .then(
            // 长按开面板、点按循环取值是取景器参数胶囊的一对动作（鸿蒙化第 2 条），
            // 共用同一枚 interactionSource，按压缩放才不会只认其中一个手势
            // enabled 走 Compose 的 Disabled 语义：点击/长按都不回调，无障碍树如实报 enabled=false。
            // 灰显视觉不在这里做——调用方用 valueColor=textLo 表达（见 WotaChip 的 [enabled] 注）
            if (onLongClick == null) Modifier.clickable(
                interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick
            ) else Modifier.combinedClickable(
                interactionSource = interaction, indication = null, enabled = enabled,
                onClick = onClick, onLongClick = onLongClick
            )
        )
    Row(
        clickable
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // 带 secondary 标签（textLo 那级）的胶囊**不接霜**：14 号计划 §二 的审计表里
            // textLo 在 0xA6~0xB3 这整个透光区间都不合格（worst case 2.52 < 4.5；换成白基 40% 后
            // 实底上也只有 3.83，仍只够辅助文本档），所以承载它的件只准用 74.9% 不透明的实底。
            .wotaHudCard(chipShape, if (secondary != null) null else frost)
            .background(fill)
            .clip(chipShape)
            // 高 28dp = HDS 件高档（ohos_id_piece_height，component-map Top 8 #3）：
            // 18sp 行高 + 2×5dp 内边距正好 28，heightIn 兜住更小字体缩放的情形，随字体放大照常长高。
            .heightIn(min = 28.dp)
            .padding(horizontal = tier.horizontalPad, vertical = 5.dp),
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
            color = valueColor ?: labelColor,
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
 *
 * 选中态分两种落点（accent 压霜板收口 2026-10-02）：
 * - **霜真在屏幕上**（[frost] 非 null 且 `HudFrost.live`——与 [wotaHudCard] 让位 fill 的判据同一条）：
 *   玻璃上不能再用 accent 作图标 tint——worst case（白墙透光）只有 1.49~1.79，图形档 3.0 都不过
 *   （accentActive 同样 1.46~1.75；textHi 能过 4.80+ 但选中态就和常态不可区分了）。
 *   改走与 [WotaChip] 选中同一套语言：accentSurface **实底** + onAccent 白图形——实底不透明，
 *   霜动不到它（`HudInkLevel.PRIMARY` 注明的「自带实底的选中态」正是这条豁免），白图形压它 5.55。
 * - **霜不在**（纸面页 / 实底兜底）：维持 accent tint——压 hudScrim 实底 4.79 ≥ 图形 3.0，观感不变。
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
    // 与 wotaHudCard 内部同一条判据：live 只在 GL 真画了板之后才为 true，
    // 所以 onFrost 为真时玻璃必然在场，选中态的 accentSurface 实底正是给这块玻璃兜住图形的
    val onFrost = frost != null && HudFrost.live
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .wotaHudCard(CircleShape, frost)
            // 实底画在 wotaHudCard 内侧（链上更里一环）：clip 已是圆形，fill 跟着裁；霜的让位只发生在
            // wotaHudCard 自己那层 background，这里的 fill 与 WotaChipImpl 选中底同一做法，霜动不到
            .then(
                if (selected && onFrost) Modifier.background(WotaColor.accentSurface, CircleShape)
                else Modifier
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            image,
            contentDescription = description,
            tint = if (selected) {
                if (onFrost) WotaColor.onAccent else WotaColor.accent
            } else {
                WotaColor.textHi
            },
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
 * 圆角走 HDS 菜单件位 20dp（`WotaShape.menu`，design-spec §4.2 `ohos_id_corner_radius_menu`；原 18dp）。
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
                .clip(menuShape)
                .background(WotaColor.surface.copy(alpha = 0.97f))
                .border(1.dp, WotaColor.acrylicBorder, menuShape)
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
