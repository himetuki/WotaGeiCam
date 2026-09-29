package com.wotagei.cam.ui

import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
 * 拖动 ⇒ [cellAtPointer] 吸附到指针压住的那一格 ⇒ [clampCellToBox] 钳进可放带 ⇒ [freeCellNear] 找空格
 * （**只落不挡路的格子，不与占位那颗交换**——交换等于"动一颗会动另一颗"，与用户这句诉求正面冲突；
 * 唯一允许落进"已有颗的那一格"是拖回自己同组搭档那一格 = 把 S2-2B 的配对合回去，见 [blockingCells]）。
 * 顶栏与底栏仍按顺序插位（为什么那两枚不上网格见 [HudZone.isGrid]）。
 * 预览与写表读的是**同一个** [snapOf]，所以"看着在哪一格"与"落在哪一格"不可能差半格；
 * 格长不另量，从网格节点实测矩形按 [gridPitchOf]（[gridSizePx] 的逆）除回来。
 * 起手那一指的命中裁决在 [entryAtPointer]（#74 后果修复：一枚格子里住两颗时按 x 定点名搬哪一颗，
 * 候选按表里的渲染序数，不靠 `entryRects` 这张可变 Map 的插入顺序）。
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
 * 读数块的列由 `planReadoutRow` 那条可用宽除出来（[gridBoxOf]）。重叠**只提示不禁止**（用户定的口径），
 * 提示把叠在一起的两枚容器点名，不静默。
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

    // ---- 可见条目：只认设置里的两个开关位（13 号计划第 5 条：只有已开启显示的控件可编辑）
    val pillMask = WotaSettings.hudPills(prefs)
    val hudMask = WotaSettings.hudItems(prefs)
    val visibleEntries: Set<HudEntry> = remember(pillMask, hudMask) {
        buildSet {
            CamPill.ALL.filter { it !in CamPill.hiddenOf(pillMask) }.forEach { add(HudEntry.of(it)) }
            HudItem.typesOf(hudMask).forEach { add(HudEntry.of(it)) }
        }
    }

    // ---- 实测：安全盒尺寸与窗口原点、五枚卡片本体、每颗条目本体
    // 这批矩形必须**随方向复位**（与下面 safeW/safeH 同一个 key）：#70 A 的读数块决策吃底板宽与块宽，
    // 拿上一副姿态的陈旧值去判"装不下"会把读数闩在错误那一档（与录制页同一个理由，见 CameraScreen）
    val zoneRects = remember(configuration.orientation) { mutableStateMapOf<HudZone, IntRect>() }
    val entryRects = remember { mutableStateMapOf<HudEntry, IntRect>() }
    // #74：三枚网格容器的**网格节点**矩形（窗口 px）。格长由它反解，落点吸附与预览都读这一份。
    // 与录制页同一套"首帧量不到就退化成不吸附"的两轮收敛手法，见 snapCellOf 返回 null 那两支。
    val gridRects = remember { mutableStateMapOf<HudZone, IntRect>() }
    // #75：每颗条目的**实测高**（px）——行跨度唯一的数据源，写方只有 HudEntryGrid 一处（值真变才写）。
    // 与上面那三张矩形表同一套"首帧量不到 ⇒ 跨度按一档 ⇒ 下一帧实测接管"的两轮收敛手法。
    val entryHeights = remember { mutableStateMapOf<HudEntry, Int>() }
    var safeW by remember(configuration.orientation) { mutableIntStateOf(configuration.screenWidthDp) }
    var safeH by remember(configuration.orientation) { mutableIntStateOf(configuration.screenHeightDp) }
    // 与录制页同一个"横屏"真源（定版：横屏永远同行）
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var originXPx by remember { mutableIntStateOf(0) }
    var originYPx by remember { mutableIntStateOf(0) }
    // 编辑页的"顶栏避让量"就是自己这条操作栏的实测高（与录制页读顶栏实测高同一手法）
    var chromeH by remember { mutableIntStateOf(0) }
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
        topAvoidDp = chromeH,
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
    // 与录制页同一条：右竖 Dock 的下界还要让开读数块那一截，但同一条横带上取大不求和
    val rightArea = areaForRightDock(baseArea, hudStripH, readoutPlan.bottomAvoidDp)
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
        HudZone.LEFT -> zoneBandHeight(HudZone.LEFT, baseArea)
        HudZone.RIGHT -> zoneBandHeight(HudZone.RIGHT, rightArea)
        HudZone.READOUT -> zoneBandHeight(HudZone.READOUT, readoutArea)
        else -> 0
    }

    /** 读数块那枚容器的**可用内宽**（[planReadoutRow] 算出来的那一档，横方向没有滚动可取用） */
    fun roomOf(zone: HudZone): Int = if (zone == HudZone.READOUT) readoutPlan.roomWidthDp.toInt() else 0

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
        val zone = zoneAt(
            HudPointDp(pxToDp(pointer.x.toFloat(), density.density), pxToDp(pointer.y.toFloat(), density.density)),
            zoneRectsDp()
        ) ?: draft.sourceZoneOf(entry)
        if (!zone.isGrid) return null
        val rect = gridRects[zone] ?: return null
        val plan = planNow()
        val items = draft.gridItems(zone, plan)
        val cols = (items.maxOfOrNull { it.cell.col.coerceAtLeast(0) } ?: -1) + 1
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
            roomWidthDp = roomOf(zone)
        )
        val origin = HudPointPx(rect.left - originXPx, rect.top - originYPx)
        // 钳制与让位吃的都是**整块**：起始档钳到 `带档数 − 跨度`，候选档还要检查块尾是否出带（#75）
        val wanted = clampCellToBox(cellAtPointer(pointer, origin, pitch), box, contentHeightPx, rowPitchPx)
        return GridSnap(
            zone = zone,
            // 挡路的格子不许落（[blockingCells]：跨组那颗算挡路、同组搭档不算；高格连它压住的几档一起算），
            // 被挡就就近让到空格。唯一允许的"落进已有颗的那一格"是拖回自己搭档那一格 = 把配对合回去
            cell = freeCellNear(
                wanted, draft.occupiedCells(zone, plan, exclude = entry),
                box.cols, box.rows, contentHeightPx, rowPitchPx
            ),
            origin = origin,
            pitch = pitch,
            gapPx = gapPx,
            rowSpan = cellRowSpan(contentHeightPx, rowPitchPx)
        )
    }

    /**
     * 松手写表。两条路（[HudZone.isGrid] 说了算，本批只有左/右 Dock 与读数块走网格）：
     * - **网格容器** ⇒ 吸附到最近的可落空格（[snapCellOf]），经 [HudLayoutTable.placeEntryAt] 落表。
     *   这一步同时把来源与目标两枚容器的推导格**钉成显式格**，所以别的颗一颗都不会动；
     * - **顶栏 / 底栏** ⇒ 维持"按顺序插第 index 格"（[HudLayoutTable.moveEntryTo]），那两枚容器的
     *   宽度账与 `W/2` 居中不变量不能上网格，理由逐条写在 [HudZone] 的枚举头。
     * 两支都同样把来源容器钉住：从网格容器抽走一颗而不钉，剩下那些"没摆过"的颗会按新的可见顺序
     * 重新推导 ⇒ 集体上移，那正是用户抱怨的耦合。
     */
    fun dropEntry() {
        val entry = dragEntry ?: return
        val pointer = HudPointDp(pxToDp(dragPointer.x, density.density), pxToDp(dragPointer.y, density.density))
        val snap = snapOf(entry)
        val target = snap?.zone ?: zoneAt(pointer, zoneRectsDp()) ?: draft.sourceZoneOf(entry)
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
        val area = if (zone == HudZone.RIGHT) rightArea else baseArea
        val w = pxToDp(rect.width.toFloat(), density.density)
        val h = pxToDp(rect.height.toFloat(), density.density)
        val x = pxToDp(rect.left.toFloat(), density.density) + pxToDp(dragShift.x, density.density)
        val y = pxToDp(rect.top.toFloat(), density.density) + pxToDp(dragShift.y, density.density)
        val clamped = clampZonePos(zone, x, y, w, h, area)
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
        // ---- 操作栏（它的实测高就是本层给钳制用的顶栏避让量）
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(start = WotaSpace.s, end = WotaSpace.s, top = TopTopPad, bottom = WotaSpace.xs)
                .onSizeChanged {
                    val h = with(density) { it.height.toDp().value.roundToInt() }
                    if (chromeH != h) chromeH = h
                },
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
            Text(
                text = stringResource(R.string.hud_edit_note),
                style = WotaType.caption,
                color = WotaColor.textLo
            )
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
            Box(Modifier.matchParentSize().wotaCard(WotaShape.card))
            val ctx = editorCtx(
                prefs = prefs,
                params = params,
                freeMb = freeMb,
                entryRects = entryRects,
                gridRects = gridRects,
                entryHeights = entryHeights,
                capacityRoomDp = (safeW - 66f).coerceAtLeast(1f),
                hiddenEntry = dragEntry
            )
            HudZoneBox(
                zone = HudZone.TOP,
                placement = placementOf(draft, HudZone.TOP, zoneRects, density, baseArea),
                area = baseArea,
                shiftXPx = shiftXOf(HudZone.TOP),
                shiftYPx = shiftYOf(HudZone.TOP),
                onCardRect = { if (zoneRects[HudZone.TOP] != it) zoneRects[HudZone.TOP] = it }
            ) { HudTopZone(draft.visibleOrderOf(HudZone.TOP, visibleEntries), ctx) }
            HudZoneBox(
                zone = HudZone.LEFT,
                placement = placementOf(draft, HudZone.LEFT, zoneRects, density, baseArea),
                area = baseArea,
                shiftXPx = shiftXOf(HudZone.LEFT),
                shiftYPx = shiftYOf(HudZone.LEFT),
                onCardRect = { if (zoneRects[HudZone.LEFT] != it) zoneRects[HudZone.LEFT] = it }
            ) {
                HudDockZone(
                    HudZone.LEFT,
                    draft.gridItems(HudZone.LEFT, gridPlan),
                    ctx,
                    zoneBandHeight(HudZone.LEFT, baseArea)
                )
            }
            HudZoneBox(
                zone = HudZone.RIGHT,
                placement = placementOf(draft, HudZone.RIGHT, zoneRects, density, rightArea),
                area = rightArea,
                shiftXPx = shiftXOf(HudZone.RIGHT),
                shiftYPx = shiftYOf(HudZone.RIGHT),
                onCardRect = { if (zoneRects[HudZone.RIGHT] != it) zoneRects[HudZone.RIGHT] = it }
            ) {
                HudDockZone(
                    HudZone.RIGHT,
                    draft.gridItems(HudZone.RIGHT, gridPlan),
                    ctx,
                    zoneBandHeight(HudZone.RIGHT, rightArea)
                )
            }
            HudZoneBox(
                zone = HudZone.READOUT,
                placement = placementOf(draft, HudZone.READOUT, zoneRects, density, readoutArea),
                area = readoutArea,
                shiftXPx = shiftXOf(HudZone.READOUT),
                shiftYPx = shiftYOf(HudZone.READOUT),
                onCardRect = { if (zoneRects[HudZone.READOUT] != it) zoneRects[HudZone.READOUT] = it }
            ) {
                HudReadoutZone(
                    draft.gridItems(HudZone.READOUT, gridPlan),
                    ctx,
                    zoneBandHeight(HudZone.READOUT, readoutArea)
                )
            }
            HudZoneBox(
                zone = HudZone.BOTTOM,
                placement = placementOf(draft, HudZone.BOTTOM, zoneRects, density, baseArea),
                area = baseArea,
                shiftXPx = shiftXOf(HudZone.BOTTOM),
                shiftYPx = shiftYOf(HudZone.BOTTOM),
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
                            val at = cellOffsetPx(snap.cell, snap.pitch)
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
    val rowSpan: Int
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
 */
private fun placementOf(
    table: HudLayoutTable,
    zone: HudZone,
    rects: Map<HudZone, IntRect>,
    density: Density,
    area: HudAreaDp
): ZonePlacement {
    val raw = table.posOf(zone)
    if (raw.isDefault) return raw
    val rect = rects[zone]
    val w = rect.dpWidthToDp(density)
    val h = rect.dpHeightToDp(density)
    return clampZonePos(zone, raw.xDp, raw.yDp, w, h, area)
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
 * 容量段按**满档**取（[capacityRoomDp] 给的是宽裕值）：这页没有录制页那 66dp 的固定预留可量，
 * 摆位置要看的是"这一段最多占多宽"，§74 三档里满档就是最宽的那一档，按最宽的摆不会挤。
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
    return HudCtx(
        recording = false,
        busy = false,
        recStateLabel = null,
        elapsedLabel = "00:00",
        sizeLabel = sizeText(size),
        capacityLabel = capacityTierText(
            freeMb,
            d.bitrateBps + BitratePolicy.AUDIO_BITRATE,
            capacityRoomDp
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
