package com.wotagei.cam.ui

import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.TOP_BAR_CHROME_RESERVE_DP
import com.wotagei.cam.core.capacityNetRoomDp
import com.wotagei.cam.core.capacityTierText
import com.wotagei.cam.record.BitratePolicy
import com.wotagei.cam.record.VideoStore
import com.wotagei.cam.ui.anim.appendLiquidLink
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.design.WotaStroke
import com.wotagei.cam.ui.design.WotaType
import com.wotagei.cam.ui.design.pillAnchor
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.dialog.lensLabelRes
import com.wotagei.cam.ui.dialog.sizeText
import com.wotagei.cam.ui.dialog.wbLabelRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 提示水印层的文字最大宽度：占安全区宽的**比例**（不是 dp 常量，也不按方向写死）。
 *
 * 为什么是比例：水印排在五枚容器**之下**，而容器底板 74.9% 不透明——文字一旦钻到卡片底下
 * 就是半张糊字。竖屏安全宽 360dp、默认布局左 Dock 占 8..62dp（HudEdgePad 8 + 宽 54）、
 * 右 Dock 占 284..352dp（EdgePad 8 + 宽 68）⇒ 居中文字的右缘 ≤284 ⇒ 最宽 208dp ≈ 0.58×360。
 * 取 **0.56**（≈202dp）留 3dp 余量：默认布局下两枚 Dock 都压不到这段字。
 * 横屏安全宽 ≈732dp 时同比例给到 ≈410dp，落在左右两枚 Dock 之间的中腹空档。
 * ⚠ Dock 是用户可拖的，所以这一档只保证**默认布局**不压卡；拖开后叠上了也只是
 * "背景在卡片后面"这一设计本身的样貌（卡片透光 25.1%，糊字几乎读不出，不是 bug）。
 */
private const val NOTE_WATERMARK_WIDTH_FRACTION = 0.56f

/**
 * 「编辑控件」页（docs/plan/13 第 5 条 + 任务 #74 的网格改造）：拖容器改位置、拖条目改格子，保存写 `hud_layout`。
 *
 * ## 与录制页共用同一条渲染路
 * 五枚容器全走 [HudZoneBox] + [HudTopZone] / [HudDockZone] / [HudReadoutZone] / [HudBottomZone]，
 * 位置表读同一份 [HudLayoutTable]，钳制走同一条 [clampZonePos]，格子也走同一条 [HudLayoutTable.gridItems]
 * （**两页没有第二份"格子 → 坐标"**），读数块的"底边 + 一行几颗"走同一条 [planReadoutRow]（#70 A），
 * 胶囊档位走同一条 [chipTierFor]（#70 B）。
 * 所以"编辑页画了个假布局、两边分叉"在结构上不可能发生。**唯一**的差别是数据源：这页没有相机、
 * 没有传感器，读数取的是**用户设定的开机默认值**（[WotaSettings.applyDefaults] 那条链，与录制页
 * 同一份 prefs）与**真实剩余空间**（`VideoStore.freeSpaceMb()`，与录制页同一个来源）。
 *
 * ## 条目拖拽 = 吸附到格子（#74 的核心）
 * [HudZone.LEFT] / [HudZone.RIGHT] / [HudZone.READOUT] 三枚容器里，每颗条目自带一个 [GridCell]：
 * 拖动 ⇒ [cellAtPointer] 吸附到指针压住的那一格 ⇒ [clampCellToBox] 钳进可放带 ⇒ [cellDropOf] 定落点。
 * **拖到已占格 = 交换**（本批）：占位者挪到拖拽者的原格（原格承接不了才就近让位），被拖那颗拿到目标格，
 * 其余条目一颗不动。它只动"用户亲手点到的那一颗"，所以与 #74 的独立性诉求不冲突。
 * 唯一允许"落进已有颗那一格"而不挤人的是拖回自己同组搭档那一格 = 把 S2-2B 的配对合回去，见 [blockingCells]。
 * 顶栏与底栏仍按顺序插位（为什么那两枚不上网格见 [HudZone.isGrid]）。
 * 预览与写表读的是**同一个** [cellDropOf]，所以"看着在哪一格"与"落在哪一格"不可能差半格；
 * **列**长从网格节点实测矩形按 [gridPitchOf]（[gridSizePx] 的逆）除回来，用的列数与渲染层同一个数
 * （#80 之后那是"用到的列"与"预留的列"里的大者，少算预留会把格长除成两倍）；
 * **行**距不除回来（#75），走 [hudGridRowPitchPx]。
 * 起手那一指的命中裁决在 [entryAtPointer]（#74 后果修复：一枚格子里住两颗时按 x 定点名搬哪一颗，
 * 候选按表里的渲染序数，不靠 `entryRects` 这张可变 Map 的插入顺序）。
 *
 * ## 拖一颗不许动别颗（任务 #80 的三条落点）
 * 硬判据：**每颗的屏幕坐标 = f(它自己的 col,row) + 固定原点 + 固定格长**。这一页三处守着它：
 * 1. 吸附与钳制的**格长/档数**都从 [HudLayoutTable.defaultGridOf]（默认表，只看可见集）推，
 *    与"谁摆到了哪一格"无关 → 见 [snapOf] 里那一份 [GridAnchor] 与 [reservedGridRowsOf]；
 * 2. 贴右缘生长的 [HudZone.RIGHT] 列从右往里数（[gridColFromEndOf]），预览框与渲染层共读
 *    [cellSlotLeftPx] 那一条式子；
 * 3. 松手时"这颗归哪枚容器"由 [dropZoneOf] 裁决：**指针还在来源容器自己的带里就不许改归属**
 *    （真机那条"把右 Dock 的蓝牙往下拖，掉进了读数块，读数块整块上移 78px"就是缺这一条）。
 * 读数块的纵向是本批**有意留下**的缺口（它要贴住 #70 A 那条与底栏共用的基线，预留高度与允许多摆一档
 * 数学上不能同时成立），理由与两条出路都写在 [GridAnchor] 的文件头。
 *
 * ## 蓝牙/水平姿态/音量这三颗在这页拿到的是出厂态
 * （未连接 / 水平 / 静音），因为它们的数据源（蓝牙控制器、加速度计、编码器振幅）都挂在录制页上。
 * **不是假开关**：显隐判据一条没少，闪光灯（本机有无闪光）、对焦（能否调焦）、音量表（只在录制中）
 * 这三条运行时判据仍在 [CameraScreen] 的可见条目集里，这页能摆的只是**位置**。
 *
 * ## 手势：一层捕获层
 * 拖动是这页唯一的手势，所以整页盖一层透明捕获层（[pointerInput] + `detectDragGestures`）。
 * 捕获层排在容器之后 ⇒ z 序在上面：命中某颗条目 → 拖**条目**（原位留空壳 + 跟手的 ghost + 「腰」+
 * 落点格描边）；否则命中某枚容器 → 拖**整枚容器**（底栏这枚只认纵向：松手后横向弹回可视中心并给一句提示，
 * x 进不了表，见 [dropContainer]）。这样不会出现"想拖动却把参数面板点开"，也不让子控件的
 * `clickable` 与父层拖拽互相抢事件（那种组合在 Compose 里本就不可靠）。代价照实写：
 * 这页点不到控件，要改参数回录制页改。
 *
 * ## 动感复用录制那一路的连通体几何
 * 拖起来时那条「腰」就是 [appendLiquidLink]（两圆 + 两条公切贝塞尔，腰宽 = 圆心距的函数，
 * 超过阈值掐断），半径取条目与 ghost 的实测短边一半，**没有第二套腰公式**。
 * `Path` 与 [Stroke] 在组合期 [remember]，每帧只 `reset()` + 追加，零分配。
 * 红线照旧：拖动位移只进 draw 阶段与布局偏移之外的那一层（draw 阶段读快照状态只重画那一层，
 * 不重组整页），松手落位那一帧才把格子与绝对坐标写进表 —— 一次重排，不是动画。
 * **格子位置一律不做动画**（AGENTS.md 与 `Motion.kt` 的红线），落点预览也只是 draw 阶段一枚描边框。
 *
 * ## 越界钳制与重叠
 * 钳制吃**安全区实测宽高**（套了 safeDrawingPadding() 之后那块，挖孔让掉的边已经在里面）与本层实测的
 * 两条避让量：操作栏高（对应录制页的顶栏实测高）与底栏那排实占带高，没有新写魔法数，也没有按方向写死的
 * 右缘让位量（任务 #68 删的就是它，见 docs/plan/13 §九·补）。格子的可放带同理：行由带高除出来、
 * 读数块的列由 `planReadoutRow` 那条可用宽除出来（[gridBoxOf]），**两者再各夹一道默认表预留的档数**
 * （#80：[reservedGridRowsOf] / [reservedGridColsOf]），拖不出预留 ⇒ 底板撑不大 ⇒ 别颗不平移。
 * 本批行档放宽 [GridRowGrowthRows] 一格（用户 10-01 明示）：编辑页的钳制带比预留多一档，
 * 让用户能把条目再往下摆一格；列侧仍恒 0（读数块"横屏一行两颗"的定版不许被撬开）。
 * 重叠**只提示不禁止**（用户定的口径），
 * 提示把叠在一起的两枚容器点名，不静默。
 *
 * ## 提示语是背景，不占布局高度（本任务）
 * 操作说明三件套（`hud_edit_note` + 格边长读数 `hud_edit_page_cell_size` + 弱化标记
 * `hud_edit_hidden_entries_note`）以**水印层**呈现：一层 `matchParentSize()` 的纯 draw 层，
 * 排在可放范围底板与网格底纹之上、五枚容器之下，`drawBehind` 里一次 `drawText` 画完。
 * 它**不吃事件**（没有 pointerInput ⇒ 上面那条捕获层的命中区分派一字未改）、
 * **不参与兄弟测量**（matchParentSize ⇒ 五枚容器与操作栏的 Modifier 链一个字没动）。
 * 收益是真机量出来的（docs/plan/16「新发现的缺陷」1）：改前说明行 3 行高 112px + 它那档
 * 行间距 8px 把右 Dock 的可用带从 538px 压到 424px（对焦被裁 13px、防抖整颗跌出带外）；
 * 搬回背景后编辑页顶部避让 202px → ≈81px、带高 545px，右 Dock 拿回完整内容 526px。
 * 观感：颜色与字阶**逐字复用旧说明行那一档**（`WotaType.caption` + `WotaColor.textLo`，
 * contrast-audit 实测对 bg 3.84 / 对底板 hudScrim 3.83，过非正文 3:1 档），不是新造的淡色，
 * 所以"太淡看不见"不成立——它与改前那条前景说明行是同一档可读性，只是换了 z 序。
 * 只有**即时反馈**留前景：重叠警示与「已保存 / 已恢复默认」那类 `hint`
 * （干净基线里它们都是 0 行，一行都不多吃）。
 *
 * ## 保存语义
 * 改动先进内存草稿，**点「保存」才落 prefs**（录制页每次组合直读 prefs + 挂变更监听，所以立刻生效）；
 * 返回不保存就整批丢弃，文案里写明白了。「重置」两步确认（第一次只是武装，第二次才清），
 * 清完给一个**即时可重做**的「撤销」—— 靠的是重置前那一份编码串原样写回，不是内存里的手抄本。
 * 重置与撤销都连格子一起回默认（[HudLayoutTable.default] 的格子表是空的）。
 */
@Composable
@Suppress("LongMethod")
fun HudLayoutEditorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext }
    val prefs = remember(app) { WotaSettings.of(app) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // ---- 草稿：进页时读已保存的那张表，「保存」之前不动 prefs，所以录制页不会被半成品改到
    var draft by remember(prefs) { mutableStateOf(WotaSettings.hudLayout(prefs)) }
    var saved by remember(prefs) { mutableStateOf(draft) }
    var hint by remember { mutableStateOf<String?>(null) }
    var resetArmed by remember { mutableStateOf(false) }
    var undoRaw by remember { mutableStateOf<String?>(null) }

    // ---- 数据源：开机默认参数（不接相机、不开传感器），标签与录制页默认档同一真源
    val params = remember(prefs) {
        WotaParams(CoroutineScope(Dispatchers.Unconfined)).also { WotaSettings.applyDefaults(prefs, it) }
    }
    var freeMb by remember { mutableLongStateOf(0L) }
    LaunchedEffect(app) { freeMb = runCatching { VideoStore(app).freeSpaceMb() }.getOrDefault(0L) }

    // ---- 可见条目：**全集**，不再看设置里那两枚开关位（docs/plan/17 §十一 2，用户 2026-10-02 明示：
    // 「编辑控件位置页时应当显示所有的可装载控件，不能只显示用户自己选择要显示的控件」）
    // 根因：改前这里与录制页同一套掩码判据 ⇒ 用户在图库/设置里关掉显示的控件，在编辑页既不显示也不能摆
    //（"隐藏即摘表"的同族缺陷）。真源是纯函数 [editorVisibleEntries]（四条判据的去留在它的 KDoc 里
    // 逐条说清），所以 JVM 用例能直接打；把这里改回掩码口径，可见性那几条当场红。
    val pillMask = WotaSettings.hudPills(prefs)
    val hudMask = WotaSettings.hudItems(prefs)
    val levelEnabled = WotaSettings.levelEnabled(prefs)
    val visibleEntries: Set<HudEntry> = remember(pillMask, hudMask, levelEnabled) {
        editorVisibleEntries(levelEnabled)
    }
    // §11.2 的**弱化标记**名单：两枚掩码关掉、但编辑页照旧列出来的那些条目。有了这一句说明，
    // 用户不会以为"我在编辑页摆的位没生效"（他摆的正是录制页当前不显示的那几颗）。
    // 与 [editorVisibleEntries] 取交集：那两份都不列的条目（码率、显示开关关掉的水平仪）不该出现在这句话里。
    val editorOnlyEntries: Set<HudEntry> = remember(pillMask, hudMask, levelEnabled) {
        buildSet {
            CamPill.hiddenOf(pillMask).forEach { add(HudEntry.of(it)) }
            HudItem.ALL.filter { it !in HudItem.typesOf(hudMask) }.forEach { add(HudEntry.of(it)) }
        }.intersect(visibleEntries)
    }

    // ---- 实测：安全盒尺寸与窗口原点、五枚卡片本体、每颗条目本体
    // 这批矩形必须**随方向复位**（与下面 safeW/safeH 同一个 key）：#70 A 的读数块决策吃底板宽与块宽，
    // 拿上一副姿态的陈旧值去判"装不下"会把读数闩在错误那一档（与录制页同一个理由，见 CameraScreen）
    val zoneRects = remember(configuration.orientation) { mutableStateMapOf<HudZone, IntRect>() }
    val entryRects = remember { mutableStateMapOf<HudEntry, IntRect>() }
    // #74：三枚网格容器的**网格节点**矩形（窗口 px）。格长由它反解，落点吸附与预览都读这一份。
    // 与录制页同一套"首帧量不到就退化成不吸附"的两轮收敛手法，见 snapOf 返回 null 那两支。
    val gridRects = remember { mutableStateMapOf<HudZone, IntRect>() }
    // #75：每颗条目的**实测高**（px）——行跨度唯一的数据源，写方只有 HudEntryGrid 一处（值真变才写）。
    // 与上面那三张矩形表同一套"首帧量不到 ⇒ 跨度按一档 ⇒ 下一帧实测接管"的两轮收敛手法。
    val entryHeights = remember { mutableStateMapOf<HudEntry, Int>() }
    // P1（docs/plan/17 §六）：整页网格的**格边长**（px）——页格与 Dock 内格同源同尺的那一档格距
    //（[pageCellPx] 直接复用 [gridRowPitchPx]，100% 档 34dp / 120% 档 38dp，随系统字号档一起涨）。
    // 两个消费方共读这一个数：下面的网格描边（draw 阶段）与水印层那条「格 N×N dp」读数。
    val cellPx = pageCellPx(density.fontScale, density.density)
    // 水印层里那个格边长读数（dp，取整）：与描边画的是同一把尺子
    val cellDp = pxToDp(cellPx.toFloat(), density.density)

    var safeW by remember(configuration.orientation) { mutableIntStateOf(configuration.screenWidthDp) }
    var safeH by remember(configuration.orientation) { mutableIntStateOf(configuration.screenHeightDp) }
    // ---- 提示语水印（文件头「提示语是背景」那一节）：原操作栏下那条常驻说明行整体搬进背景
    // 三份资源拼成同一段文字（与改前逐字同序同值），喂给下面 remember 住的 layout
    val noteMeasurer = rememberTextMeasurer()
    val noteText = stringResource(R.string.hud_edit_note) + " " +
        stringResource(R.string.hud_edit_page_cell_size, cellDp, cellDp) +
        if (editorOnlyEntries.isEmpty()) "" else " " + stringResource(R.string.hud_edit_hidden_entries_note)
    // 可用宽按安全区宽的固定比例推（理由见 [NOTE_WATERMARK_WIDTH_FRACTION]）：默认布局不压 Dock 卡
    val noteMaxWidthPx = with(density) { (safeW * NOTE_WATERMARK_WIDTH_FRACTION).dp.toPx() }
    // **每帧零分配**的关键一步：measure 与 layout 结果都钉在 remember 上。三个 key 一个都没漏——
    // 文字（含格边长读数与弱化标记进出）、可用宽（横竖屏 / 分屏 / 字号档让 safeW 变）、
    // measurer（自身随 fontScale 重建）。draw 里因此只做一次 drawText 与两次减法。
    // 颜色与字阶逐字复用旧说明行那一档 ⇒ 对比度与改前完全一致（audit：bg 3.84 / 底板 3.83）。
    val noteLayout = remember(noteMeasurer, noteText, noteMaxWidthPx) {
        noteMeasurer.measure(
            text = noteText,
            style = WotaType.caption.copy(color = WotaColor.textLo),
            constraints = Constraints(maxWidth = noteMaxWidthPx.roundToInt().coerceAtLeast(1))
        )
    }
    // 与录制页同一个"横屏"真源（定版：横屏永远同行）
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var originXPx by remember { mutableIntStateOf(0) }
    var originYPx by remember { mutableIntStateOf(0) }
    // 编辑页的"顶栏避让量"就是自己这条操作栏压到多低（与录制页读顶栏实测高同一手法）。
    // 回报的是**窗口坐标下的下缘 px**，不是内容高：内容高不含操作栏自己那圈上下内边距，
    // 少算那 8dp 就会让顶栏那两枚仍压在操作栏的下内边距里（#79，量法与理由见 [chromeBandBottomDp]）。
    // 挂在 `safeDrawingPadding()` 之内、那圈 padding 之外的链上，所以系统栏与挖孔那两截不会被重复扣。
    var chromeBottomWinPx by remember { mutableIntStateOf(0) }
    val chromeAvoidDp = chromeBandBottomDp(chromeBottomWinPx, originYPx, density.density)
    val bottomOuterPadDp = BottomBarOuterPadV.value.roundToInt()
    val dockCardH = zoneRects[HudZone.BOTTOM].dpHeightToDp(density)
    val dockStripH = if (dockCardH > 0) dockCardH + 2 * bottomOuterPadDp
    else BottomBarSpaceFallback.value.roundToInt()
    val hudStripH = zoneRects[HudZone.READOUT].dpHeightToDp(density)
    // 读数块实测宽：竖屏那道"估宽说谎时别压上底板"的安全网（横屏不读它，见 planReadoutRow；与录制页同一个入参）
    val hudStripW = zoneRects[HudZone.READOUT].dpWidthToDp(density)
    val baseArea = HudAreaDp(
        width = safeW,
        height = safeH,
        topAvoidDp = chromeAvoidDp,
        bottomAvoidDp = dockStripH
    )
    // #70 A：与录制页同一条决策（planReadoutRow），两个页面各摆一份 hudRoomDp/hudPerRowFor 就是 S3-5
    // 那条"两边判的不是同一条不等式"的老坑。底栏宽取本页实测（编辑页的底栏照样是居中的那枚底板），
    // 首帧量不到同样退到 BottomDockWidthFallback。
    val dockCardW = zoneRects[HudZone.BOTTOM].dpWidthToDp(density)
    val readoutCount = draft.visibleOrderOf(HudZone.READOUT, visibleEntries).size
    val readoutPlan = remember(
        readoutCount, density.fontScale, safeW, dockCardW, dockStripH, hudStripW, isLandscape
    ) {
        planReadoutRow(
            readoutCount = readoutCount,
            fontScale = density.fontScale,
            safeWidthDp = safeW,
            dockWidthDp = if (dockCardW > 0) dockCardW else BottomDockWidthFallback.value.roundToInt(),
            dockStripDp = dockStripH,
            // 与录制页同一个基线令牌：计划里会减掉读数块自己的 HudBlockPadDp，对齐的才是可见底边
            dockRowBaselineDp = bottomOuterPadDp,
            endPadDp = HudEdgePad.value,
            dockGapDp = WotaSpace.s.value,
            landscape = isLandscape,
            readoutWidthDp = hudStripW
        )
    }
    val readoutArea = baseArea.copy(bottomAvoidDp = readoutPlan.bottomAvoidDp)
    // 与录制页同一条：右竖 Dock 的下界还要让开读数块那一截，但同一条横带上取大不求和。
    // 10-01 第 5 项起两枚竖 Dock 都改读 dockBottomAvoidDp——只避**真横向重叠**的邻居（底栏居中，
    // 常规屏宽下与竖 Dock 不叠，那整条 72dp 不再白让）；编辑器与录制页必须同账，同一条函数同一批输入。
    val rightArea = baseArea.copy(
        bottomAvoidDp = dockBottomAvoidDp(
            safeWidthDp = safeW,
            dockWidthDp = zoneRects[HudZone.RIGHT].dpWidthToDp(density),   // 实测宽；0 = 首帧还没量到
            bottomDockWidthDp = dockCardW,
            bottomDockStripDp = dockStripH,
            fromEnd = true,
            readoutBottomDp = readoutPlan.bottomAvoidDp,
            readoutHeightDp = hudStripH,   // 本页的读数块实测高（与录制页同一个入参语义）
        )
    )
    // 左 Dock 同一条账、fromEnd = false：读数块在右缘 ⇒ 读数那两枚入参不参与左支，显式传 0
    val leftArea = baseArea.copy(
        bottomAvoidDp = dockBottomAvoidDp(
            safeWidthDp = safeW,
            dockWidthDp = zoneRects[HudZone.LEFT].dpWidthToDp(density),
            bottomDockWidthDp = dockCardW,
            bottomDockStripDp = dockStripH,
            fromEnd = false,
            readoutBottomDp = 0,
            readoutHeightDp = 0,
        )
    )
    val perRow = readoutPlan.perRow
    // #74：网格解析的两份运行时输入。与录制页同一份构造（可见集 + 读数块一行几颗），
    // 两页的"格子 → 坐标"都只经 [HudLayoutTable.gridItems] 这一条，不许出现第二份算式。
    // #75 又添两份：**纵向格距**（从令牌推，与格子里住了谁无关）与**每颗的实测高**
    // （[HudEntryGrid] 回报的那份）。高条目吃掉几档就由这两个数现算——少了它们就静默退回
    // "行距取容器里最高那颗"那一档，而那正是把右 Dock 撑到 386dp、超出横屏 244dp 带高的模型。
    // 两条都是**无默认值的必传形参**（#69 铁律），漏挂直接编译不过。
    val gridPlan = HudGridPlan(
        visible = visibleEntries,
        readoutPerRow = perRow,
        rowPitchOf = { zone -> hudGridRowPitchPx(zone, density) },
        cellHeightOf = { entry -> entryHeights[entry] ?: 0 }
    )
    // ⚠ 拖拽处理器在 `pointerInput(Unit)` 里 ⇒ 那条协程用的**始终是创建那帧**的闭包，直接读上面的
    // [gridPlan] 就是读一份过期快照：转一次屏幕后 `readoutPerRow` 还停在上一副姿态那一档、设置页改过
    // 显隐后 `visible` 还是老集合，于是命中判定与松手写表都按旧格子算（#74 后果修复补的这条）。
    // 与上面那几条 `dragY`/`drop` 一样走 [rememberUpdatedState]：读的时候才取当前值。
    val liveGridPlan = rememberUpdatedState(gridPlan)

    /** 拖拽那一路读网格档的唯一出口：见上面 [liveGridPlan] 那条"闭包捕获过期快照"的说明 */
    fun planNow(): HudGridPlan = liveGridPlan.value

    /** 这一枚容器的**可放带**（钳制格子落点用），与它自己的 [HudZoneBox] 喂的是同一份 area */
    fun bandOf(zone: HudZone): Int = when (zone) {
        HudZone.LEFT -> zoneBandHeight(HudZone.LEFT, leftArea)
        HudZone.RIGHT -> zoneBandHeight(HudZone.RIGHT, rightArea)
        HudZone.READOUT -> zoneBandHeight(HudZone.READOUT, readoutArea)
        else -> 0
    }

    /** 读数块那枚容器的**可用内宽**（[planReadoutRow] 算出来的那一档，横方向没有滚动可取用） */
    fun roomOf(zone: HudZone): Int = if (zone == HudZone.READOUT) readoutPlan.roomWidthDp.toInt() else 0

    /**
     * 这一枚容器的**可放带纵向区间**（dp，安全区局部坐标；#80 的归属裁决读它）。
     * 喂进去的 area 与 [bandOf] / 那枚 [HudZoneBox] 用的是同一份，所以"带"这一件事只有一个数。
     */
    fun bandYOf(zone: HudZone): IntRange = zoneBandYRange(
        zone,
        when (zone) {
            HudZone.LEFT -> leftArea
            HudZone.RIGHT -> rightArea
            HudZone.READOUT -> readoutArea
            else -> baseArea
        },
        // 与 [dropContainer] / [placementOf] 同一条：只有顶栏吃操作栏下缘这条下限，其余传 0。
        // 三处必须同值，否则"拖过头"与"真要跨容器"判的不是同一条带（#80 那条分叉）
        topZoneMinYDp = if (zone == HudZone.TOP) chromeAvoidDp else 0
    )

    // ---- 拖拽态。位移只在 draw 阶段与 ghost 的 offset 里用，所以是快照状态但只在绘制期读
    var dragEntry by remember { mutableStateOf<HudEntry?>(null) }
    var dragZone by remember { mutableStateOf<HudZone?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragShift by remember { mutableStateOf(Offset.Zero) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var ghostSize by remember { mutableStateOf(IntSize.Zero) }

    /** 窗口坐标 → 安全区局部坐标（位置表存的就是这一套，与录制页 [HudAreaDp] 同一参考） */
    fun local(rect: IntRect?): IntRect? = rect?.let {
        IntRect(it.left - originXPx, it.top - originYPx, it.right - originXPx, it.bottom - originYPx)
    }

    fun toDpRect(rect: IntRect?): HudRectDp? = local(rect)?.let {
        HudRectDp(
            pxToDp(it.left.toFloat(), density.density),
            pxToDp(it.top.toFloat(), density.density),
            pxToDp(it.right.toFloat(), density.density),
            pxToDp(it.bottom.toFloat(), density.density)
        )
    }

    /**
     * 每次都现算（不是组合期算好的一份快照）：拖拽处理器里的 `pointerInput` 闭包捕获的是**创建那帧**
     * 的实例，容器一挪、条目一改，快照就过期了，命中判定会指着老位置。
     */
    fun zoneRectsDp(): Map<HudZone, HudRectDp> = buildMap {
        HudZone.ALL.forEach { z -> toDpRect(zoneRects[z])?.let { put(z, it) } }
    }
    val overlaps = overlappingZones(zoneRectsDp())

    /**
     * 指针压住的是哪一颗条目（**拖拽的唯一命中裁决**，#74 后果修复）。
     *
     * 老写法是 `entryRects.keys.firstOrNull { 它的 rect 压住 }`——`keys` 的迭代序 = 各颗**回报矩形的先后**，
     * 与渲染顺序无关。每颗各占一格时那没问题（压住的至多一颗）；**一枚格子里住两颗**（右 Dock 那对
     * S2-2B 并排）之后必须给一个与回报顺序无关的答案，所以裁决搬进纯函数 [entryHitIndex]：
     * ① 候选取"压住指针的那些颗"，按**表里的渲染序**（[HudLayoutTable.allEntries]）数，不数 map 的序；
     * ② 一颗都不压住时，只补"配对格中间那道行距"这一档：把候选放宽到**同一格那几颗的并集矩形**
     *    （≥2 颗的格才补，所以单颗格子的落点判据与改前逐字相同），按 x 落在哪一颗上取中心最近的那颗；
     * ③ 还不算 ⇒ 返回 null，这一指按"拖整枚容器"处理（与改前同一条语义）。
     */
    fun entryAtPointer(px: Int, py: Int): HudEntry? {
        // [entryHitIndex] 吃的候选区间与候选颗必须同长同序；`inside` 是靠 rect 筛出来的，所以那颗一定有 rect
        val spansOf = { list: List<HudEntry> -> list.map { e -> val r = local(entryRects[e])!!; r.left until r.right } }
        val inside = draft.allEntries().filter { local(entryRects[it]).containsPoint(px, py) }
        if (inside.size == 1) return inside.first()
        if (inside.size > 1) return inside.getOrNull(entryHitIndex(spansOf(inside), px))
        for (zone in HudZone.ALL) {
            if (!zone.isGrid) continue
            for ((_, group) in draft.gridItems(zone, planNow()).groupBy { it.cell }) {
                val rects = group.mapNotNull { local(entryRects[it.entry]) }
                if (rects.size < 2) continue
                val inBox = py in rects.minOf { it.top } until rects.maxOf { it.bottom } &&
                    px in rects.minOf { it.left } until rects.maxOf { it.right }
                if (!inBox) continue
                return group.getOrNull(
                    entryHitIndex(rects.map { it.left until it.right }, px)
                )?.entry
            }
        }
        return null
    }

    /**
     * 指针 → 这一帧的落点格子（**跟手预览与松手写表共用这一条**，两处各算一次就会差半格）。
     *
     * 返回 null 的三种情况都按"不吸附、只换顺序"处理：① 目标不是网格容器；② 这一帧还没量到网格节点
     * （进页首帧，与 `dockStripH` 那一套两轮收敛同一手法）；③ 列长反解出 0。
     *
     * **列**长仍从网格节点的实测矩形**除回来**（渲染层的正算式是 [gridSizePx]，这里就是它的逆
     * [gridPitchOf]；[snapGridPitchPx] 只透出它算的那一轴）；
     * **行**距不除回来（#75）：高条目吃掉几档之后 `网格高 ÷ 行数` 已经不是行距了，除回来会把格子
     * 算矮，于是"预览框画在一格、松手落到另一格"。纵向恒取 [hudGridRowPitchPx]——与渲染层、
     * 与 [HudLayoutTable.gridItems] 里推默认行的那一个数，三处同一个来源。
     * 空容器（一列都没有）时列长退回"被拖那颗自己的实测宽 + 行距"，因为那一刻列长只能由它撑。
     */
    fun snapOf(dragged: HudEntry?): GridSnap? {
        val entry = dragged ?: return null
        val pointer = HudPointPx(dragPointer.x.roundToInt(), dragPointer.y.roundToInt())
        val pointerDp = HudPointDp(pxToDp(pointer.x.toFloat(), density.density), pxToDp(pointer.y.toFloat(), density.density))
        val source = draft.sourceZoneOf(entry)
        // #80 根因 3：指针离开底板矩形却还在自己带里时**不许**改归属（真机那条"蓝牙掉进读数块"）
        val zone = dropZoneOf(
            pointer = pointerDp,
            source = source,
            sourceCard = toDpRect(zoneRects[source]),
            sourceBandY = bandYOf(source),
            hitAtPointer = zoneAt(pointerDp, zoneRectsDp())
        )
        if (!zone.isGrid) return null
        val rect = gridRects[zone] ?: return null
        val plan = planNow()
        val anchor = GridAnchor(zone, draft.defaultGridOf(zone, plan))
        // 逆算列长要用的列数必须与渲染层 gridSizePx 用的是**同一个数**（#80 之后那是"用到的列"与
        // "预留的列"里的大者）：这里少算预留那一档就会把格长除成两倍，吸附出来的格子与画出来的对不上
        val cols = maxOf(
            (draft.gridItems(zone, plan).maxOfOrNull { it.cell.col.coerceAtLeast(0) } ?: -1) + 1,
            reservedGridColsOf(anchor)
        )
        val gapPx = with(density) { hudGridGap(zone).roundToPx() }
        val own = entryRects[entry]
        val rowPitchPx = hudGridRowPitchPx(zone, density)
        val pitch = snapGridPitchPx(
            gridWidthPx = rect.width, cols = cols, gapPx = gapPx,
            fallbackChildWidthPx = own?.width ?: ghostSize.width, rowPitchPx = rowPitchPx
        )
        if (pitch.width <= 0 || pitch.height <= 0) return null
        // 这一颗要吃几档：实测高优先取网格回报的那份（姿态仪与音量表根本不报锚点，见 HudEntryItem），
        // 两处都没量到就按 0 ⇒ 跨度 1，下一帧实测接管（两轮收敛，与首帧不吸附同一条手法）
        val contentHeightPx = maxOf(own?.height ?: 0, plan.cellHeightOf(entry))
        val box = gridBoxOf(
            zone = zone,
            pitchXDp = pxToDp(pitch.width.toFloat(), density.density),
            pitchYDp = pxToDp(pitch.height.toFloat(), density.density),
            bandHeightDp = bandOf(zone),
            roomWidthDp = roomOf(zone),
            // #80：落点只许在默认表预留的那几档里 ⇒ 底板永远撑不大自然就平移不了别人。
            // 本批：行档再放宽 [GridRowGrowthRows] 一格（用户 10-01 明示），列侧仍 0
            reservedRows = reservedGridRowsOf(anchor, plan.cellHeightOf, rowPitchPx),
            reservedCols = reservedGridColsOf(anchor),
            rowGrowth = GridRowGrowthRows,
            colGrowth = 0
        )
        val origin = HudPointPx(rect.left - originXPx, rect.top - originYPx)
        val colFromEnd = gridColFromEndOf(zone)
        // 钳制与让位吃的都是**整块**：起始档钳到 `带档数 − 跨度`，候选档还要检查块尾是否出带（#75）
        val wanted = clampCellToBox(
            cellAtPointer(pointer, origin, pitch, rect.width, gapPx, colFromEnd),
            box, contentHeightPx, rowPitchPx
        )
        // 落点裁决与写表**读同一条路**（写表侧在 [HudLayoutTable.placeEntryAt] 的同名分支）：
        // · 同容器（zone == source）读 [cellDropOf]：拖到已占格发生交换，拖回搭档那一格仍是合对；
        // · 跨容器仍读 [freeCellNear]：没有"你的原格"可退回，不许把目标容器里那颗挤走。
        // 预览与松手不可能差半格。`draft.gridItems(zone, plan)` 读到的就是这一颗**当前**的格子（同位相消即 origin）。
        //
        // ⚠ 同容器支的 `rows` 必须喂 **GridRowHardCap**（与写表侧 `placeEntryAt` 逐字同一个常量），
        // 不许喂 `box.rows`：`box.rows` 是**可放带内档数**（[gridBoxOf] 的 reservedRows + 生长余量），
        // 写表侧拿不到带高、只有存储层这个上限。两边一个是"带内"、一个是"上限"，落到 `freeCellNear`
        // 的兜底搜索上就是两只不同的盒子 ⇒ 带被占满时会"预览画一格、松手落另一格"
        // （r2_rootcause §二 同类隐患 4 那处）。对齐之后写表侧行为一字不改（它本来就用这个数），
        // 只是预览不再说谎。
        // "编辑页只给带内档"这条原意**没有让**：`wanted` 仍由上面的 [clampCellToBox] 钳进 `box`，
        // `gridBoxOf` 的 reservedRows/生长余量照旧只在这一个盒子上生效。
        // 只有"带内一个整块空格都没有"这一退化态下，兜底搜索才会落到带外档——而那时写表侧本来就会
        // 落到带外（旧预览画的是带内重叠格，松手存的却是带外格，两页本来就分叉）。
        //
        // ⚠ 同容器支的 `cols` 同样必须喂 [gridColsCapOf]（与写表侧 `placeEntryAt` 同容器支逐字同一个
        // 函数），不许喂 `box.cols`：`box.cols` 对读数块会被**可用内宽**再夹一刀（[gridBoxOf] 的
        // `roomWidthDp / pitchXDp`，外面还套一层预留列数），横屏"一行两颗"那一档带盒只剩 2 列，而
        // 写表侧拿不到带高、只有存储层的 3 列上限。两只盒子喂进 [cellDropOf] 会在三处判据上分叉：
        // ① `originInBox`（判据 B）：原格落在第 3 列时带盒判它"出盒" ⇒ 预览把交换降级成"占位者就近
        //    让位"，写表侧却照旧交换、把占位者送回原格 ⇒ "预览画一格、松手落另一格"（T9 的 cols 侧孪生）；
        // ② 第 ②/④/⑥/⑨ 步 `freeCellNear` 兜底/让位搜索的**可搜索列宽**（2 列 vs 3 列）；
        // ③ `target` 的列钳制——这一处是同值的（`wanted` 已被 [clampCellToBox] 钳进 `box`，列号本来就
        //    到不了带外），写出来只为说明前两处才是真分叉，别误以为"钳制已经保了险"。
        // "编辑页只给带内列"这条原意同样**没有让**：`wanted` 仍由 [clampCellToBox] 钳进 `box`，
        // 只有兜底/让位这两种退化态才会落到带外列，而那时写表侧本来就会落到带外。
        // 对齐之后写表侧行为一字不改（它本来就用这个函数），只是预览不再说谎。
        //
        // ⚠ 跨容器支的两个形参也必须与写表侧 `placeEntryAt` 跨容器支**逐字同源**：
        // `rows` 喂 [GridRowHardCap]、`cols` 喂 [gridColsCapOf]（那里喂的是 `target == zone`）。
        // 上一个同源修在 T9 只改了同容器支的 `rows`，跨容器支仍喂 `box.rows`/`box.cols`——
        // `box.rows` 是**可放带内档数**（[gridBoxOf] 的 reservedRows + 生长余量），`box.cols` 对
        // 读数块还会被 **可用内宽**再夹一刀（[gridBoxOf] 的 `roomWidthDp / pitchXDp`），而写表侧
        // 拿不到带高、只有存储层那两个上限。两只盒子落到 `freeCellNear` 的兜底搜索上就是两个答案
        // ⇒ 带被占满（或读数块内宽只容得下两列）时"预览画一格、松手落另一格"
        // （r2_rootcause §二 同类隐患 4 在跨容器支的孪生）。跨容器支**没有** `wanted` 之外的第二重
        // 钳制（`cellDropOf` 同容器支至少还拿 `box` 判过"块尾出带"），所以这里同源是唯一的收口。
        // 对齐之后写表侧行为一字不改（它本来就用这两个数），只是预览不再说谎。
        val cell = if (zone == source) {
            cellDropOf(
                wanted = wanted,
                origin = draft.gridItems(zone, plan).firstOrNull { it.entry == entry }?.cell ?: GridCell.DEFAULT,
                residents = draft.residentsOf(zone, plan),
                self = entry,
                mates = cellGroupMatesOf(draft.defaultGridOf(zone, plan), entry),
                draggedHeightPx = contentHeightPx,
                cellHeightPx = plan.cellHeightOf,
                cols = gridColsCapOf(zone),
                rows = GridRowHardCap,
                rowPitchPx = rowPitchPx
            ).dropped
        } else {
            freeCellNear(
                wanted, draft.occupiedCells(zone, plan, exclude = entry),
                gridColsCapOf(zone), GridRowHardCap, contentHeightPx, rowPitchPx
            )
        }
        return GridSnap(
            zone = zone,
            // #75 之后"跨度尾巴"也算占（[blockingCells] 把高格压住的几档一起给出来）；
            // 拖回自己同组搭档那一格 = 把配对合回去，[cellDropOf] 第 4 步照旧放行
            cell = cell,
            origin = origin,
            pitch = pitch,
            gapPx = gapPx,
            rowSpan = cellRowSpan(contentHeightPx, rowPitchPx),
            gridWidthPx = rect.width,
            colFromEnd = colFromEnd
        )
    }

    /**
     * 松手写表。两条路（[HudZone.isGrid] 说了算，本批只有左/右 Dock 与读数块走网格）：
     * - **网格容器** ⇒ 吸附到落点格（[cellDropOf]），经 [HudLayoutTable.placeEntryAt] 落表。
     *   这一步同时把来源与目标两枚容器的推导格**钉成显式格**，所以别的颗一颗都不会动；
     * - **顶栏 / 底栏** ⇒ 维持"按顺序插第 index 格"（[HudLayoutTable.moveEntryTo]），那两枚容器的
     *   宽度账与 `W/2` 居中不变量不能上网格，理由逐条写在 [HudZone] 的枚举头。
     * 两支都同样把来源容器钉住：从网格容器抽走一颗而不钉，剩下那些"没摆过"的颗会按新的可见顺序
     * 重新推导 ⇒ 集体上移，那正是用户抱怨的耦合。
     */
    fun dropEntry() {
        val entry = dragEntry ?: return
        val pointer = HudPointDp(pxToDp(dragPointer.x, density.density), pxToDp(dragPointer.y, density.density))
        val source = draft.sourceZoneOf(entry)
        val snap = snapOf(entry)
        // 非网格那一支（顶栏/底栏）没有吸附结果可读，归属裁决在这里走**同一条** [dropZoneOf]：
        // 两条路各判一次就会一个写在带内、一个写在带外（S3-5 那一族"两边判的不是同一条不等式"）
        val target = snap?.zone ?: dropZoneOf(
            pointer = pointer,
            source = source,
            sourceCard = toDpRect(zoneRects[source]),
            sourceBandY = bandYOf(source),
            hitAtPointer = zoneAt(pointer, zoneRectsDp())
        )
        val ordered = draft.visibleOrderOf(target, planNow().visible).filter { it != entry }
        val rects = ordered.mapNotNull { toDpRect(entryRects[it]) }
        // 网格容器按"行优先"数插位（与格网读序一致），非网格容器仍按各自主轴
        val axis = if (target.isGrid) HudAxis.GRID else target.axis
        val index = dropIndexFor(axis, rects, pointer)
        draft = if (snap != null) {
            draft.placeEntryAt(entry, target, index, snap.cell, planNow())
        } else {
            draft.moveEntryTo(entry, target, index, planNow())
        }
    }

    /**
     * 整枚容器落位：当前实测左上角 + 累计位移 → 绝对 dp → 钳进安全区。
     *
     * **底栏只有 y 进得了表**：[clampZonePos] 把它的 x 强制抹回哨兵，松手后那枚 Dock 就弹回可视中心
     * （跟手位移只进 graphicsLayer，所以回弹是看得见的）。横向确实拖过一把时再补一句提示，
     * 别让用户以为"没生效是 bug"——判据取"横位移比竖位移大"，也就是他明显想横着搬，而不是随手一拖。
     */
    fun dropContainer(zone: HudZone) {
        val rect = local(zoneRects[zone]) ?: return
        // 两枚竖 Dock 各读自己那份 area（10-01 第 5 项：只避真横向重叠的邻居），其余三枚仍读 baseArea
        val area = when (zone) {
            HudZone.LEFT -> leftArea
            HudZone.RIGHT -> rightArea
            else -> baseArea
        }
        val w = pxToDp(rect.width.toFloat(), density.density)
        val h = pxToDp(rect.height.toFloat(), density.density)
        val x = pxToDp(rect.left.toFloat(), density.density) + pxToDp(dragShift.x, density.density)
        val y = pxToDp(rect.top.toFloat(), density.density) + pxToDp(dragShift.y, density.density)
        // 只有顶栏这一枚吃"操作栏下缘"这条绝对定位下限（其余四枚传 0，与录制页同值）；
        // 顶栏被拖到操作栏矩形里时若不推，整枚容器既点不到也拖不动（操作栏在捕获层之上）
        val clamped = clampZonePos(
            zone, x, y, w, h, area,
            topZoneMinYDp = if (zone == HudZone.TOP) chromeAvoidDp else 0
        )
        draft = draft.withZonePos(zone, clamped.xDp, clamped.yDp)
        if (zone == HudZone.BOTTOM && abs(dragShift.x) > abs(dragShift.y)) {
            hint = context.getString(R.string.hud_edit_bottom_x_locked)
        }
    }

    // 「镜头」那颗在底栏占不占右槽，判据与录制页同一条（设置开关 + 归属容器），见 HudBottomZone 的宽度账
    val lensEntry = HudEntry.of(CamPill.LENS)

    /** 取位移的 getter：交给 [HudZoneBox] 在 graphicsLayer 块里读，拖动期间不重组整页 */
    fun shiftXOf(zone: HudZone): () -> Float = { if (dragZone == zone) dragShift.x else 0f }
    fun shiftYOf(zone: HudZone): () -> Float = { if (dragZone == zone) dragShift.y else 0f }

    // 「腰」的绘制材料：组合期各 remember 一次，每帧只 reset + 追加（审查 S3-4 那条纪律）
    val link = remember { Path() }
    val linkEdge = remember(density) {
        Stroke(width = with(density) { WotaStroke.hairline.toPx() }, cap = StrokeCap.Round)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(WotaColor.bg)
    ) {
        // ---- 安全区：位置表 (x, y) 的坐标参考，与录制页同一条（套 safeDrawing 之后那块）
        Box(
            Modifier
                .matchParentSize()
                .safeDrawingPadding()
                .onSizeChanged {
                    val w = with(density) { it.width.toDp().value.roundToInt() }
                    val h = with(density) { it.height.toDp().value.roundToInt() }
                    if (w != safeW) safeW = w
                    if (h != safeH) safeH = h
                }
                .onGloballyPositioned { coords ->
                    val p = coords.positionInWindow()
                    val nx = p.x.toInt()
                    val ny = p.y.toInt()
                    if (nx != originXPx) originXPx = nx
                    if (ny != originYPx) originYPx = ny
                }
        ) {
            // 一层极淡的描边把"可放范围"画出来，用的还是 wotaCard 那套令牌，不新造观感值
            // （card 是 Dp 件位值 24dp，要 Shape 就地包）
            Box(Modifier.matchParentSize().wotaCard(RoundedCornerShape(WotaShape.card)))
            // ---- P1（docs/plan/17 §六）：整页网格的**只读**描边。三条硬约束：
            // ① 只进 draw 阶段——这一层没有内容、没有布局参数、没有命中区（捕获层在更下面那枚
            //    `pointerInput` Box 上，一个字没动 ⇒ 拖动行为与改前逐像素相同）；
            // ② 必须排在五枚容器**之下**：写在它们之前，"先画线后画内容"就是这句话本身，格线不会
            //    盖住落点描边 / 「腰」/ ghost（那三样在更靠上的两层）；
            // ③ 描边复用既有令牌：宽度与圆头那一档**逐字取落点描边那枚 [linkEdge]**
            //    （它本身就是 [WotaStroke.hairline] 那一档，没有新观感值），颜色取 acrylicBorder
            //    ——与 wotaCard 的边框同一个色，画在安全区底衬之上是一张极淡的格网。
            //    注：drawLine 在 1.5.x 只有 strokeWidth/cap 两个形参（没有 DrawStyle 那个），
            //    所以从 [linkEdge] 上把宽度与圆头读出来，而不是另算一遍 hairline.toPx()。
            // 格边长与列/行数全部来自纯函数（[pageCellPx] / [pageGridBoxOf]），P3 的落点算式读同一条。
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        // 先画线后画内容：本层自己没有内容，drawContent() 是空操作，写在后头只为
                        // 让"网格在容器之下"这件事在代码里就是书写顺序
                        val box = pageGridBoxOf(size.width.toInt(), size.height.toInt(), cellPx)
                        if (box.cols > 1) {
                            for (col in 1 until box.cols) {
                                val x = col * cellPx.toFloat()
                                drawLine(
                                    WotaColor.acrylicBorder,
                                    Offset(x, 0f),
                                    Offset(x, size.height),
                                    strokeWidth = linkEdge.width,
                                    cap = linkEdge.cap
                                )
                            }
                        }
                        if (box.rows > 1) {
                            for (row in 1 until box.rows) {
                                val y = row * cellPx.toFloat()
                                drawLine(
                                    WotaColor.acrylicBorder,
                                    Offset(0f, y),
                                    Offset(size.width, y),
                                    strokeWidth = linkEdge.width,
                                    cap = linkEdge.cap
                                )
                            }
                        }
                        drawContent()
                    }
            )
            // ---- 提示语水印层（文件头「提示语是背景」那一节）：说明行走背景的唯一落点
            // 为什么画在网格之上、容器之下：水印字（textLo 40% 白）比 acrylicBorder 那档格线
            // （15% 白）亮，压在格线后面会让笔画上爬满细亮丝；反过来字盖线，线只在笔画间隙断一下。
            // 四条硬约束（一条都不许破，HudEditNoteWatermarkTest 逐条钉着）：
            // ① 只 draw、不吃事件：本层没有 pointerInput、没有内容 ⇒ 全屏捕获层的命中区分派
            //    一字未改（它在更下面那枚 matchParentSize Box 上，z 序与命中都不经过这里）；
            // ② matchParentSize ⇒ 不参与兄弟测量：五枚容器、操作栏、可放范围盒的 Modifier 链
            //    一个字没动，删掉的只有文案区那 120px 高度（说明行 112px + 它那档行间距 8px）；
            // ③ 每帧零分配：[noteLayout] 由 remember 钉住，本 lambda 里没有 measure、没有 remember，
            //    只有一次 drawText 与两次减法（Offset 是值类型，不落堆）；
            // ④ 居中画在安全区里：默认布局下正好落在左右两枚 Dock 之间的空列（宽度理由见
            //    [NOTE_WATERMARK_WIDTH_FRACTION]）。measure 得到的宽度已经排好版，这里只定位。
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val noteW = noteLayout.size.width
                        val noteH = noteLayout.size.height
                        if (noteW <= 0 || noteH <= 0) return@drawBehind
                        drawText(
                            textLayoutResult = noteLayout,
                            topLeft = Offset(
                                ((size.width - noteW) / 2f).coerceAtLeast(0f),
                                ((size.height - noteH) / 2f).coerceAtLeast(0f)
                            )
                        )
                    }
            )
            val ctx = editorCtx(
                prefs = prefs,
                params = params,
                freeMb = freeMb,
                entryRects = entryRects,
                gridRects = gridRects,
                entryHeights = entryHeights,
                // 与录制页同一条预留账（原散写的 66f 已收拢成 core 的 TOP_BAR_CHROME_RESERVE_DP）
                capacityRoomDp = (safeW - TOP_BAR_CHROME_RESERVE_DP).coerceAtLeast(1f),
                hiddenEntry = dragEntry
            )
            HudZoneBox(
                zone = HudZone.TOP,
                placement = placementOf(draft, HudZone.TOP, zoneRects, density, baseArea, chromeAvoidDp),
                area = baseArea,
                shiftXPx = shiftXOf(HudZone.TOP),
                shiftYPx = shiftYOf(HudZone.TOP),
                // 顶栏整条推到操作栏之下（#79）：0 = 还没量到那一帧，与改前逐字同值，下一帧实测接管
                nativeTopMinDp = chromeAvoidDp,
                onCardRect = { if (zoneRects[HudZone.TOP] != it) zoneRects[HudZone.TOP] = it }
            ) { HudTopZone(draft.visibleOrderOf(HudZone.TOP, visibleEntries), ctx) }
            HudZoneBox(
                zone = HudZone.LEFT,
                placement = placementOf(draft, HudZone.LEFT, zoneRects, density, leftArea, 0),
                // 10-01 第 5 项：左 Dock 与录制页同一条账（只避真横向重叠的邻居，见 leftArea）
                area = leftArea,
                shiftXPx = shiftXOf(HudZone.LEFT),
                shiftYPx = shiftYOf(HudZone.LEFT),
                nativeTopMinDp = 0,   // 只有 TOP 的原生对齐读它（#69：无默认值必传）
                onCardRect = { if (zoneRects[HudZone.LEFT] != it) zoneRects[HudZone.LEFT] = it }
            ) {
                HudDockZone(
                    HudZone.LEFT,
                    draft.gridItems(HudZone.LEFT, gridPlan),
                    ctx,
                    zoneBandHeight(HudZone.LEFT, leftArea),
                    draft.defaultGridOf(HudZone.LEFT, gridPlan),   // #80 锚定与预留的唯一来源
                    frostVisible = true   // 编辑页拖拽位移另有壳层回报，无滑出撤板一回事
                )
            }
            HudZoneBox(
                zone = HudZone.RIGHT,
                placement = placementOf(draft, HudZone.RIGHT, zoneRects, density, rightArea, 0),
                area = rightArea,
                shiftXPx = shiftXOf(HudZone.RIGHT),
                shiftYPx = shiftYOf(HudZone.RIGHT),
                nativeTopMinDp = 0,   // 同上：非顶栏容器不读这条下限
                onCardRect = { if (zoneRects[HudZone.RIGHT] != it) zoneRects[HudZone.RIGHT] = it }
            ) {
                HudDockZone(
                    HudZone.RIGHT,
                    draft.gridItems(HudZone.RIGHT, gridPlan),
                    ctx,
                    zoneBandHeight(HudZone.RIGHT, rightArea),
                    draft.defaultGridOf(HudZone.RIGHT, gridPlan),   // #80 锚定与预留的唯一来源
                    frostVisible = true
                )
            }
            HudZoneBox(
                zone = HudZone.READOUT,
                placement = placementOf(draft, HudZone.READOUT, zoneRects, density, readoutArea, 0),
                area = readoutArea,
                shiftXPx = shiftXOf(HudZone.READOUT),
                shiftYPx = shiftYOf(HudZone.READOUT),
                nativeTopMinDp = 0,   // 同上：非顶栏容器不读这条下限
                onCardRect = { if (zoneRects[HudZone.READOUT] != it) zoneRects[HudZone.READOUT] = it }
            ) {
                HudReadoutZone(
                    draft.gridItems(HudZone.READOUT, gridPlan),
                    ctx,
                    zoneBandHeight(HudZone.READOUT, readoutArea),
                    draft.defaultGridOf(HudZone.READOUT, gridPlan)   // #80：读数块只预留列数（透明底板）
                )
            }
            HudZoneBox(
                zone = HudZone.BOTTOM,
                placement = placementOf(draft, HudZone.BOTTOM, zoneRects, density, baseArea, 0),
                area = baseArea,
                shiftXPx = shiftXOf(HudZone.BOTTOM),
                shiftYPx = shiftYOf(HudZone.BOTTOM),
                nativeTopMinDp = 0,   // 同上：非顶栏容器不读这条下限
                onCardRect = { if (zoneRects[HudZone.BOTTOM] != it) zoneRects[HudZone.BOTTOM] = it }
            ) {
                // drag = null：长按换栏是**录制页**的手势，这页改 y 用整枚拖动。
                // showLens 判据与录制页同一条：既看设置开关，也看那颗归属哪枚容器（挪走了这里就不占槽）
                HudBottomZone(
                    showLens = lensEntry in visibleEntries &&
                        draft.sourceZoneOf(lensEntry) == HudZone.BOTTOM,
                    ctx = ctx,
                    drag = null
                )
            }

            // ---- 「腰」+ ghost + 捕获层（z 序在容器之上，所以这页条目的点按拿不到事件）
            val dragged = dragEntry
            val sourceLocal = local(dragged?.let { entryRects[it] })
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawContent()
                        // 落点预览（#74）：吸附结果与松手写表读的是**同一个** [snapOf]，
                        // 所以"看着在哪一格"与"落在哪一格"是同一个数，不会画一格落另一格。
                        // 只画描边（复用那枚 WotaStroke.hairline 的 linkEdge，没有新观感值）、
                        // 只进 draw 阶段：布局参数一个不动、没有位置动画（AGENTS.md 与 Motion.kt 的红线）
                        snapOf(dragEntry)?.let { snap ->
                            // #80：预览框的横向走 [cellSlotLeftPx]（贴右缘那枚容器要从右往里数），
                            // 与渲染层 cellChildLeftsPx 里那一支是同一个式子；纵向仍是 row × 格距（row 恒从上往下数）
                            val at = HudPointPx(
                                cellSlotLeftPx(snap.cell.col, snap.pitch, snap.gridWidthPx, snap.gapPx, snap.colFromEnd),
                                cellOffsetPx(snap.cell, snap.pitch).y
                            )
                            drawRect(
                                color = WotaColor.accentDim,
                                topLeft = Offset(
                                    (snap.origin.x + at.x).toFloat(),
                                    (snap.origin.y + at.y).toFloat()
                                ),
                                // #75：框的是**整块**（跨度 × 格距 − 一道行距），不是一档。
                                // 跨度已经由被拖那颗的实测高算好，与渲染层排的那几档同一个数
                                size = androidx.compose.ui.geometry.Size(
                                    (snap.pitch.width - snap.gapPx).toFloat(),
                                    (snap.pitch.height * snap.rowSpan - snap.gapPx).toFloat()
                                ),
                                style = linkEdge
                            )
                        }
                        val src = sourceLocal ?: return@drawWithContent
                        // draw 阶段读 dragStart/dragShift/ghostSize 这几个快照状态 ⇒ 只让这一层重画，
                        // 不重组整页（这是本文件唯一每帧被读的状态，其余都在松手之后才写）
                        val p = dragStart + dragShift
                        val rA = minOf(src.width, src.height) / 2f
                        val rB = minOf(ghostSize.width, ghostSize.height) / 2f
                        if (rA <= 0f || rB <= 0f) return@drawWithContent
                        link.reset()
                        val drew = link.appendLiquidLink(
                            src.left + src.width / 2f,
                            src.top + src.height / 2f,
                            rA,
                            p.x,
                            p.y,
                            rB
                        )
                        if (!drew) return@drawWithContent
                        drawPath(link, WotaColor.hudScrim)
                        drawPath(link, WotaColor.acrylicBorder, style = linkEdge)
                    }
            ) {
                if (dragged != null && sourceLocal != null) {
                    // ghost 那份不报锚点：条目矩形由原位那一份报，ghost 再报一次会让落点算式读到漂移的值
                    val ghostCtx = ctx.copy(anchorOf = { Modifier })
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .onSizeChanged { ghostSize = it }
                            .offset {
                                val p = dragStart + dragShift
                                IntOffset(
                                    (p.x - ghostSize.width / 2f).roundToInt(),
                                    (p.y - ghostSize.height / 2f).roundToInt()
                                )
                            }
                    ) {
                        // ghost 与原位那一颗必须同档位（#70 B）：从竖 Dock 拖出来的那颗在容器内是紧凑档，
                        // ghost 若走全局档会长一号，"跟手的比洞大"看着就是漂移。归属容器由表说了算
                        HudEntryItem(
                            dragged, ghostCtx,
                            tier = chipTierFor(draft.sourceZoneOf(dragged)),
                            // 编辑页没有录制态，闸门恒开（ghost 拿不到事件是靠上面那层捕获层，
                            // 不是靠这道闸门；#73 那条断链只在录制页底栏 Dock 有意义）
                            clicksAccepted = true
                        )
                    }
                }
            }
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                dragStart = pos
                                dragPointer = pos
                                dragShift = Offset.Zero
                                // 命中裁决走 [entryAtPointer]（#74 后果修复：一格多颗时要按 x 定点名哪一颗）
                                val hit = entryAtPointer(pos.x.roundToInt(), pos.y.roundToInt())
                                if (hit != null) {
                                    dragEntry = hit
                                    dragZone = null
                                } else {
                                    dragZone = zoneAt(
                                        HudPointDp(
                                            pxToDp(pos.x, density.density),
                                            pxToDp(pos.y, density.density)
                                        ),
                                        zoneRectsDp()
                                    )
                                    dragEntry = null
                                }
                            },
                            onDrag = { change, amount ->
                                // foundation 1.5.4 的 detectDragGestures 不给 onDragEnd 终点坐标，
                                // 所以把最后一个 change 的位置留住，松手时按它算落点
                                dragPointer = change.position
                                dragShift += amount
                                change.consume()
                            },
                            onDragEnd = {
                                if (dragEntry != null) dropEntry() else dragZone?.let { dropContainer(it) }
                                dragEntry = null
                                dragZone = null
                                dragShift = Offset.Zero
                            },
                            onDragCancel = {
                                dragEntry = null
                                dragZone = null
                                dragShift = Offset.Zero
                            }
                        )
                    }
            )
        }

        // ---- 操作栏（**必须是根 Box 的最后一个子节点**，画在捕获层之上，任务 #79 任务一）
        // 它排在捕获层之前时，那三颗 WotaChip 一个事件都收不到：捕获层 `matchParentSize()` 铺满整条安全区，
        // 而操作栏的内容区（`safeDrawingPadding()` 之下、从挖孔那条边起算）正好落在这一片里，
        // 触摸按 Compose 的命中序先给最上面那层带 pointerInput 的节点 ⇒ 每一指都被拖拽层吃掉。
        // 挪到最顶之后点按归这三颗；而这条 Column 与 Row 本身**没有** pointerInput，所以只有三颗胶囊
        // 自己的矩形会让拖拽失去那几像素。**反过来**：顶栏那两枚段原生对齐只让一枚 TopTopPad（4dp），
        // 正好落在这三颗的矩形里 ⇒ 那两枚就拖不动了（把一个缺陷换成另一个，正是上一批没收尾的那件事）。
        // 所以这里回报操作栏**窗口坐标下的下缘**，经 [chromeBandBottomDp] 换成安全区局部的避让量，
        // 同时喂三处：顶栏原生对齐的下限（nativeTopMinDp）、顶栏**绝对定位支**的下限
        // （[clampZoneYRange] 的 topZoneMinYDp，本批补上）与 baseArea.topAvoidDp。
        // 拖拽层原点一个字没动 ⇒ 安全区局部坐标那套算式（cellAtPointer/zoneAt/snapOf）不漂移。
        // 本批修掉上一批那条残留：用户把顶栏**整枚容器**拖进那三颗的矩形里，整枚容器被钳到操作栏下缘
        // 之下（[clampZonePos] 的 topZoneMinYDp），那几像素不再既点不到也拖不动。
        // ⚠ 录制页恒传 0（它的 topAvoidDp 是实测顶栏高、不是操作栏）⇒ 同一份表在两页渲染同一位置。
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .safeDrawingPadding()
                // 回报点挂在 safeDrawingPadding() **之内**、下面那圈 padding **之外**：这样量到的下缘
                // 含操作栏自己的上下内边距（顶栏最低只许贴到它之下），不含系统栏与挖孔那两截
                // （那两截安全区原点里已经扣过一次，再算就是双重让位——HudAreaDp 记着同一笔账）
                .onGloballyPositioned { coords ->
                    val bottom = coords.boundsInWindow().bottom.roundToInt()
                    if (chromeBottomWinPx != bottom) chromeBottomWinPx = bottom
                }
                .padding(start = WotaSpace.s, end = WotaSpace.s, top = TopTopPad, bottom = WotaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(WotaSpace.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WotaSpace.s)
            ) {
                WotaChip(
                    label = stringResource(R.string.hud_edit_back),
                    selected = false,
                    // 未保存就退出 = 整批丢弃草稿（prefs 一个字节都没写，录制页不受影响）
                    onClick = onBack
                )
                WotaChip(
                    label = stringResource(R.string.hud_edit_save),
                    // 有未保存改动时这颗亮着，让"改了还没落盘"在视觉上有落点
                    selected = draft != saved,
                    onClick = {
                        // commit() 的返回值就是"这次真落盘了吗"：没落成不许说"已保存"
                        val ok = WotaSettings.setHudLayout(prefs, draft)
                        saved = draft
                        undoRaw = null
                        resetArmed = false
                        hint = if (ok) {
                            context.getString(R.string.hud_edit_saved)
                        } else {
                            context.getString(R.string.hud_edit_save_failed)
                        }
                    }
                )
                WotaChip(
                    label = stringResource(
                        if (resetArmed) R.string.hud_edit_reset_confirm else R.string.hud_edit_reset
                    ),
                    selected = resetArmed,
                    onClick = {
                        if (!resetArmed) {
                            resetArmed = true
                            hint = context.getString(R.string.hud_edit_reset_arm_tip)
                        } else {
                            // 重置 = 删键（缺键就是默认表）；「撤销」靠的是把重置前那一份串原样写回来
                            undoRaw = WotaSettings.hudLayout(prefs).encode()
                            val ok = WotaSettings.clearHudLayout(prefs)
                            draft = HudLayoutTable.default()
                            saved = draft
                            resetArmed = false
                            hint = if (ok) {
                                context.getString(R.string.hud_edit_reset_done)
                            } else {
                                context.getString(R.string.hud_edit_save_failed)
                            }
                        }
                    }
                )
                undoRaw?.let { raw ->
                    WotaChip(
                        label = stringResource(R.string.hud_edit_undo),
                        selected = false,
                        onClick = {
                            val back = HudLayoutTable.decode(raw)
                            val ok = WotaSettings.setHudLayout(prefs, back)
                            draft = back
                            saved = back
                            undoRaw = null
                            hint = if (ok) {
                                context.getString(R.string.hud_edit_undo_done)
                            } else {
                                context.getString(R.string.hud_edit_save_failed)
                            }
                        }
                    )
                }
            }
            // 说明行已整体搬进背景水印层（文件头「提示语是背景」那一节，draw 阶段的 noteLayout）：
            // 改前这三份资源以 Text 排在这里，三行高 ≈112px + 行间距 8px 把右 Dock 的可用带
            // 从 538px 压到 424px（docs/plan/16「新发现的缺陷」1）。**不许在这里加回任何
            // 常驻说明文字**——那正是本任务要消掉的东西；操作栏只留即时反馈（下面那两行）。
            // 重叠只提示不禁止：把叠在一起的两枚容器点名，别静默
            if (overlaps.isNotEmpty()) {
                Text(
                    // 名称这条 joinToString 的 lambda 不是 composable 上下文，所以标签走 context.getString
                    //（真源仍是那五条资源，见 zoneLabelRes）
                    text = overlaps.joinToString("；") { (a, b) ->
                        context.getString(R.string.hud_edit_overlap_pair, context.getString(zoneLabelRes(a)), context.getString(zoneLabelRes(b)))
                    },
                    style = WotaType.caption,
                    color = WotaColor.warn
                )
            }
            hint?.let {
                Text(text = it, style = WotaType.caption, color = WotaColor.accent)
            }
        }
    }
}

/**
 * 点在矩形内（安全区局部坐标，右/下是开区间，与 [HudRectDp.contains] 同一口径）。
 * 收 null 是为了让 [HudLayoutEditorScreen.entryAtPointer] 那句筛选写成一趟而不是两处判空。
 */
private fun IntRect?.containsPoint(x: Int, y: Int): Boolean =
    this != null && x in left until right && y in top until bottom

/**
 * 一次拖动的吸附结果（安全区局部 px）：落在哪枚容器的哪一格、格网原点与格长各是多少。
 *
 * [pitch] 与 [origin] 一起带出来只为一个目的：**预览框与落点用同一份数据**，
 * 否则预览要再算一次格长，那就是"格子 → 坐标"的第二份算式（S3-5 那一族）。
 *
 * [rowSpan]（#75）= 这一格要吃掉几档行距，由被拖那颗的实测高 ÷ 格距现算。
 * 预览框必须按**整块**画：只画一档的话，拖一枚 72dp 的姿态仪时框子只有 34dp 高，
 * 松手之后那颗却占着三档——"看着在一格、落在三格"就是这一条没接上时的现象。
 */
private data class GridSnap(
    val zone: HudZone,
    val cell: GridCell,
    val origin: HudPointPx,
    val pitch: HudSizePx,
    val gapPx: Int,
    val rowSpan: Int,
    /** 格网实测宽（px，#80）：贴右缘生长的那枚容器要从右往里数，预览框必须用同一个 [gridWidthPx] */
    val gridWidthPx: Int,
    /** 这一枚容器的 col 0 贴哪条边（[gridColFromEndOf] 的产物，预览与吸附共读一份） */
    val colFromEnd: Boolean
)

/** 五枚容器的中文名资源 id（重叠提示用，别露出 T/L/R/D/B 那种持久化 id） */
@androidx.annotation.StringRes
private fun zoneLabelRes(zone: HudZone): Int = when (zone) {
    HudZone.TOP -> R.string.hud_zone_top
    HudZone.LEFT -> R.string.hud_zone_left
    HudZone.RIGHT -> R.string.hud_zone_right
    HudZone.READOUT -> R.string.hud_zone_readout
    HudZone.BOTTOM -> R.string.hud_zone_bottom
}

/**
 * 表里的位置 → 这一帧用的位置。与录制页那段**同一条算式**（哨兵原样返回、拖过的先钳），
 * 抽出来只为了两个页面不各写一份钳制调用。
 *
 * [topZoneMinYDp] 透传给 [clampZonePos]：只有顶栏那一处调用点传 [HudLayoutEditorScreen] 实测的
 * `chromeAvoidDp`，其余四枚传 0（录制页那一路恒传 0 ⇒ 逐字退回改前，同一份表两页同位置）。
 */
private fun placementOf(
    table: HudLayoutTable,
    zone: HudZone,
    rects: Map<HudZone, IntRect>,
    density: Density,
    area: HudAreaDp,
    topZoneMinYDp: Int
): ZonePlacement {
    val raw = table.posOf(zone)
    if (raw.isDefault) return raw
    val rect = rects[zone]
    val w = rect.dpWidthToDp(density)
    val h = rect.dpHeightToDp(density)
    return clampZonePos(zone, raw.xDp, raw.yDp, w, h, area, topZoneMinYDp)
}

/**
 * 编辑页读数底稿：把开机默认参数**一次**读全。
 *
 * 这条刻意不是 @Composable：这页的 [WotaParams] 是自己 new 的、且没有任何写入方（条目动作全是空实现），
 * 值不会变，所以既不需要挂订阅，也不该在组合期读 `StateFlow.value`
 * （lint 的 StateFlowValueCalledInComposition 拦的就是这个；为过 lint 而给这五个值挂订阅同样是错的）。
 */
private data class EditorReadout(
    val size: Size,
    val zoomLabel: String,
    val fpsLabel: String,
    val bitrateLabel: String,
    val bitrateBps: Int
)

private fun editorReadoutOf(params: WotaParams): EditorReadout = EditorReadout(
    size = params.size.value,
    zoomLabel = String.format(java.util.Locale.US, "%.1fx", params.zoom.value.value),
    fpsLabel = "${params.fps.value.value}",
    bitrateBps = params.bitrate.value,
    bitrateLabel = "${params.bitrate.value / 1_000_000}M"
)

/**
 * 编辑页的 [HudCtx]：读数取开机默认参数 + 真实剩余空间，动作一律空实现（捕获层在上面，这页点不到条目）。
 *
 * [HudCtx.anchorOf] 这一路在这页改写成「条目矩形回报」：就近浮层在这页不弹，但 ghost 半径
 * 要每颗条目的实测矩形，而 [HudEntryItem] 只认 `ctx.anchorOf` 这一处挂点 —— 所以复用它，
 * 不另开一条回报路（两条路迟早一边写了另一边没写）。
 *
 * [HudCtx.gridOf] 这一路在这页改写成「**网格节点**矩形回报」：#74 的格子吸附要格网原点与**列**长，
 * 而列长只能从渲染层那次 `gridSizePx` 反解（[gridPitchOf] 是它的逆）；姿态仪与音量表那两颗不报锚点，
 * 用条目矩形取"最宽/最高那颗"会漏掉它们 ⇒ 必须量网格本体那一个节点。录制页传的是空链，不做吸附。
 * ⚠ #75 之后**纵向不再从这里反解**（高条目吃几档 ⇒ `网格高 ÷ 行数 ≠ 行距`），行距走 [hudGridRowPitchPx]，
 * 见 [snapGridPitchPx]。
 *
 * [HudCtx.entryHeights] 这一路是「每颗条目的**实测高**回报」：行跨度（[cellRowSpan]）唯一的证据来源，
 * 写方只有 `HudEntryGrid` 一处、读方是 [HudGridPlan.cellHeightOf]（与录制页同一条链，见 CameraScreen）。
 *
 * 容量段取哪一档与录制页**同一条账**：[capacityRoomDp] 是「安全区实测宽 − 顶栏右端固定件预留」
 * （同一枚 `TOP_BAR_CHROME_RESERVE_DP`），再经 [capacityNetRoomDp] 扣掉画幅段文案实测宽与卡内固定件；
 * 两段候选文案宽也与录制页同一条 [rememberCapacityTextWidths] 实测链。这页没有录制页那层
 * `widthIn(max = topBarMaxWidthDp)` 的硬约束，room 只当取档输入 —— 摆位看的正是这一档会画出来的实宽，
 * 预览与真机因此不会一宽一窄。
 */
@Composable
private fun editorCtx(
    prefs: SharedPreferences,
    params: WotaParams,
    freeMb: Long,
    entryRects: MutableMap<HudEntry, IntRect>,
    gridRects: MutableMap<HudZone, IntRect>,
    entryHeights: MutableMap<HudEntry, Int>,
    capacityRoomDp: Float,
    hiddenEntry: HudEntry?
): HudCtx {
    val d = remember(params) { editorReadoutOf(params) }
    val size: Size = d.size
    val zoomLabel = d.zoomLabel
    val sizeLabel = sizeText(size)
    val totalBps = d.bitrateBps + BitratePolicy.AUDIO_BITRATE
    val widths = rememberCapacityTextWidths(freeMb, totalBps, sizeLabel)
    return HudCtx(
        recording = false,
        busy = false,
        recStateLabel = null,
        elapsedLabel = "00:00",
        sizeLabel = sizeLabel,
        capacityLabel = capacityTierText(
            freeMb,
            totalBps,
            capacityNetRoomDp(capacityRoomDp, widths.sizeDp),
            widths.fullDp,
            widths.hoursDp
        ),
        freeLow = freeMb < WotaTiers.MIN_FREE_MB,
        zoomLabel = zoomLabel,
        focusLabel = stringResource(R.string.pill_focus),
        stabLabel = stringResource(R.string.cam_p_stab),
        stabActive = false,
        focusActive = false,
        refLineOn = false,
        monitorLabel = stringResource(R.string.cam_p_monitor),
        monitorActive = false,
        curveOn = false,
        flash = Flash.OFF,
        lensLabel = stringResource(lensLabelRes(LensType.WIDE)),
        btConnected = false,
        btVolumePct = 0,
        captureActive = false,
        levelEnabled = WotaSettings.levelEnabled(prefs),
        roll = 0f,
        pitch = 0f,
        db = MIN_DB,
        lastUri = null,
        aeLocked = false,
        readoutValue = { item ->
            // 这页不接相机：曝光三件套按"开机默认 = 自动档"摆位，与录制页首帧同形
            when (item) {
                HudItem.SHUTTER -> "AUTO"
                HudItem.ISO -> "AUTO"
                HudItem.EV -> "0.0"
                HudItem.WB -> stringResource(wbLabelRes(WbPreset.AUTO))
                HudItem.ZOOM -> zoomLabel
                HudItem.FPS -> d.fpsLabel
                HudItem.BITRATE -> d.bitrateLabel
            }
        },
        anchorOf = { entry ->
            Modifier.pillAnchor { rect -> if (entryRects[entry] != rect) entryRects[entry] = rect }
        },
        gridOf = { zone ->
            Modifier.pillAnchor { rect -> if (gridRects[zone] != rect) gridRects[zone] = rect }
        },
        entryHeights = entryHeights,
        onSizeClick = { },
        onFreeClick = { },
        onRefLineClick = { },
        onMonitorClick = { },
        onFlashClick = { },
        onCurveClick = { },
        onZoomClick = { },
        onFocusClick = { },
        onStabClick = { },
        onBtClick = { },
        onLensCycle = { },
        onOpenLensPanel = { },
        onRecordClick = { },
        onThumbClick = { },
        onReadoutCycle = { },
        onReadoutOpen = { },
        hiddenEntry = hiddenEntry
    )
}
