package com.wotagei.cam.ui

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「编辑控件」页与录制页共用的位置模型（docs/plan/13 第 5、8 条 + 任务 #74 的网格格子改造）。
 *
 * ## 两级：容器位置 + 条目格子
 * 可拖动的单位有两层：
 * - **容器整体**（[HudZone]，5 枚）各一份 (x, y)——**底栏只有 y 一轴**（x 恒哨兵）；
 * - **容器内的每一颗条目**各一个 [GridCell]（列 / 行）。**三枚容器走网格**
 *   （[HudZone.LEFT] / [HudZone.RIGHT] / [HudZone.READOUT]，为什么另两枚不走见 [HudZone.isGrid]），
 *   空格子就空着 ⇒ 拖动任意一颗都**不改变其他任何一颗的位置**。
 *
 * 这一条改掉的正是"有序列表"模型的固有耦合：条目位置若由「顺序 × `Arrangement.spacedBy` 紧凑排布」**推导**
 * 出来，把一个条目拖到别处，同容器里它后面的全部条目都会跟着重排——数据模型里根本没有"这一颗在第几格"
 * 这件事，所以那不是 bug，是模型。[order] 仍然留着，但**降级成只参与默认格子的推导**（以及同一格子里的
 * 稳定次序、[HudLayoutTable.visibleOrderOf] 的"隐藏再显示不回默认位置"），不再决定摆过的那颗在哪。
 *
 * ## 默认值走「推导」而不是手抄坐标
 * [GridCell.DEFAULT] 是哨兵，语义＝"这颗从没被摆过 ⇒ 回落到按 [hudRowGroups] 推导出的那一格"，
 * 与 [ZonePlacement.DEFAULT] 同源。这样"默认表"与"今天的排布"是**同一个函数**的输出，
 * 不存在手抄一份坐标然后与实现分叉的可能；v1 的存量串也正是靠这一条免费迁移（见 [HudLayoutTable.decode]）。
 *
 * 明确**不做**（留给后续批次）：改尺寸、改层级、格子上的位置动画。重叠只提示不禁止。
 *
 * ## 本文件是纯 Kotlin
 * 没有 Android/Compose 依赖，所以 schema 编解码、默认表、越界钳制、隐藏后位置保留、格子吸附与
 * 独立性保证、底栏 y 落位全部能在 JVM 单测里真跑（`HudLayout*Test`），不需要 Robolectric。
 * 读数块那三条纯算式（`hudPerRowFor` / `hudRoomDp` / `hudStripHeightDp`）与 [HudBlockPadDp] 同包直读，
 * 住在 `HudMetrics.kt`（#70 修复批次第 5 条：它们原先在 `ui/anim/LiquidMerge.kt` 里，让这个纯 Kotlin 文件
 * 反向依赖了一个带 `Path`/`Modifier`/`@Composable` 的动画文件）。
 */

/** 条目在容器内的排布主轴：决定「拖到第几格」怎么算 */
enum class HudAxis { ROW, COLUMN, GRID }

/**
 * 可拖动的容器。[key] 是持久化里的单字母 id（改它等于改存量配置语义，只能加不能改）。
 *
 * 每枚容器的**默认位置**不写在这里，而是「原生对齐」那条分支（见 [ZonePlacement.isDefault]）：
 * 默认值＝B1–B3 定稿时的对齐方式与内边距，由组合期实测避让量算出来，
 * 硬写一串 dp 数就等于把「8dp 起始内边距」这类既有真源复制一份到持久化层，迟早分叉。
 *
 * [isGrid] = 这一枚容器的条目是否走「固定格子」（任务 #74）。**本批只开三枚**（[HudZone.LEFT] /
 * [HudZone.RIGHT] / [HudZone.READOUT]），另两枚**故意不上网格**，不是漏做：
 * - [HudZone.BOTTOM] 那枚底板有「快门中心＝可视窗口水平中心」的 `W/2` 不变量
 *   （`2S + R + 2G + 2P` 那笔等宽槽算式，见 [com.wotagei.cam.ui.anim.MergeSlot] 与 docs/plan/13 §14.3），
 *   条目一旦离开"按顺序紧凑排布"就没有等宽槽可言，#71 那笔收拢动画的起点 `w(0)` 跟着塌；
 * - [HudZone.TOP] 的宽度账与容量段三档取位（`capacityTierText` 的阈值 272/242，§59/§61/§74）
 *   是按**段数增减**重标的，改成一格一颗就把那三档判据同时作废。
 * 这两枚继续用 [order] 紧凑排布；[GridCell] 对它们不生效（[HudLayoutTable.normalize] 与编码两处都把
 * 挂在非网格容器上的条目格子抹成哨兵，不留脏数据）。
 */
enum class HudZone(val key: String, val axis: HudAxis, val isGrid: Boolean) {
    /** 顶栏胶囊组：录制计时/状态那颗 + 画幅/容量段（**保持有序列表模型**，理由见枚举头） */
    TOP("T", HudAxis.ROW, false),

    /** 左竖 Dock：创作项 */
    LEFT("L", HudAxis.COLUMN, true),

    /** 右竖 Dock：取景辅助与变焦/对焦/防抖 */
    RIGHT("R", HudAxis.COLUMN, true),

    /** 右下常驻读数块（六项第 4 条搬到录制键右侧那一块） */
    READOUT("D", HudAxis.GRID, true),

    /**
     * 底栏 Dock：缩略图 + 快门 + 镜头那颗（**保持有序列表模型**，理由见枚举头）。
     * 它的 y 就是第 8 条的「上栏/下栏」，**x 不进表**（恒哨兵 ⇒ 横向永远重新居中，见 [normalizedPosOf]）。
     */
    BOTTOM("B", HudAxis.ROW, false);

    companion object {
        val ALL: List<HudZone> = values().toList()
        fun fromKey(raw: String?): HudZone? = ALL.firstOrNull { it.key == raw }
    }
}

/**
 * 一颗可编辑控件的引用。
 *
 * 两个命名空间必须分开编码：`CamPill.ZOOM`（右竖 Dock 那颗胶囊）与 `HudItem.ZOOM`（读数块那颗读数）
 * 是**两个东西**，共用 ordinal 会让持久化串自相矛盾。所以 id = 字母前缀 + ordinal：`P<n>` / `H<n>`。
 */
data class HudEntry(val kind: HudEntryKind, val ordinal: Int) {

    /** 对应 [CamPill] 开关位；[HudEntryKind.READOUT] 一律 null */
    val pill: CamPill? get() = if (kind == HudEntryKind.PILL) CamPill.ALL.getOrNull(ordinal) else null

    /** 对应 [HudItem] 开关位；[HudEntryKind.PILL] 一律 null */
    val item: HudItem? get() = if (kind == HudEntryKind.READOUT) HudItem.ALL.getOrNull(ordinal) else null

    /** 持久化 token；[pill]/[item] 都取不到（越界 ordinal）时仍是这个串，由 [HudEntry.fromId] 拒绝 */
    val id: String get() = (if (kind == HudEntryKind.PILL) "P" else "H") + ordinal

    /** 设置页/编辑页显示的名字（走资源，不散写字面量）；ordinal 越界的坏条目返回 null */
    val labelRes: Int? get() = pill?.labelRes ?: item?.labelRes

    /** 未编辑时的归属容器 = B1–B3 定稿位置 */
    val homeZone: HudZone
        get() = when (kind) {
            HudEntryKind.READOUT -> HudZone.READOUT
            // 认枚举名而不是 ordinal：新增一颗 CamPill 时这条 when 编译不过，逼着当场定容器；
            // 按 ordinal 写死数字位则会给它一个"看起来对"的默认落位，且整表错位测不出来
            HudEntryKind.PILL -> when (pill) {
                CamPill.SIZE, CamPill.STORAGE -> HudZone.TOP            // 顶栏两段
                CamPill.LENS -> HudZone.BOTTOM                           // 底栏那颗（六项第 7 条从顶栏搬来）
                CamPill.REFLINE, CamPill.MONITOR, CamPill.CURVE, CamPill.FLASH -> HudZone.LEFT
                CamPill.LEVEL, CamPill.VOLUME, CamPill.BT,
                CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB -> HudZone.RIGHT
                // 越界 ordinal：[HudLayoutTable.normalize] 会把这种坏条目丢掉，这里只需给个不抛的值
                null -> HudZone.RIGHT
            }
        }

    override fun toString(): String = id

    companion object {
        /** 全部可编辑条目 = 13 颗 CamPill + 7 颗 HudItem；顺序即持久化里的稳定顺序 */
        val ALL: List<HudEntry> =
            CamPill.ALL.map { HudEntry(HudEntryKind.PILL, it.ordinal) } +
                HudItem.ALL.map { HudEntry(HudEntryKind.READOUT, it.ordinal) }

        /** 坏串（手改 prefs、版本不符、ordinal 越界）返回 null，由调用方丢弃而不是抛 */
        fun fromId(raw: String): HudEntry? {
            if (raw.length < 2) return null
            val kind = when (raw[0]) {
                'P' -> HudEntryKind.PILL
                'H' -> HudEntryKind.READOUT
                else -> return null
            }
            val n = raw.substring(1).toIntOrNull() ?: return null
            val size = if (kind == HudEntryKind.PILL) CamPill.ALL.size else HudItem.ALL.size
            return if (n in 0 until size) HudEntry(kind, n) else null
        }

        fun of(pill: CamPill) = HudEntry(HudEntryKind.PILL, pill.ordinal)
        fun of(item: HudItem) = HudEntry(HudEntryKind.READOUT, item.ordinal)
    }
}

enum class HudEntryKind { PILL, READOUT }

/**
 * 一枚容器的位置。
 *
 * 默认态 = **两轴都是哨兵**。"一轴绝对 + 一轴哨兵"里只有**底栏**是合法状态：它的 x 恒为哨兵
 * （横向永远跟着可视窗口居中，[clampZonePos] 与 [HudLayoutTable.normalize] 两处都把它抹成哨兵），
 * 长按换栏只写 y。其余四枚容器的半吊子串只能来自手改 prefs，一律按未编辑处理（[HudLayoutTable.normalize] 会抹平）。
 *
 * 绝对值是**容器左上角相对 root 安全区左上角**的 dp 偏移，不是相对原生对齐点的偏移——
 * 存绝对值才谈得上「钳进安全区」，也才让编辑页与录制页对同一个数给出同一个视觉位置。
 */
data class ZonePlacement(val xDp: Int, val yDp: Int) {
    /** 两轴都没绝对值 = 用户没把这枚容器拖离过定稿位置（底栏的 y-only 落位**不是**默认态） */
    val isDefault: Boolean get() = xDp < 0 && yDp < 0

    companion object {
        val DEFAULT = ZonePlacement(-1, -1)
    }
}

/**
 * 一颗条目在容器**格网**里的坐标（列 / 行，都从 0 起）。
 *
 * [DEFAULT] 是哨兵，语义＝"**这颗从没被摆过** ⇒ 回落到由 [hudRowGroups] 推导出的那一格"。
 * 手法与 [ZonePlacement.DEFAULT] 同源：默认值不写死一串坐标，而是每次现推，
 * 于是"默认表"与"B1–B3 定稿的排布"永远是同一个函数的输出，不会分叉。
 */
data class GridCell(val col: Int, val row: Int) {

    /** 没被摆过（两轴都是哨兵）= 用推导出来的默认格子 */
    val isDefault: Boolean get() = col < 0 && row < 0

    /** 持久化里的格子后缀（`0.2`）；哨兵写成 `-` */
    val token: String get() = if (isDefault) "-" else "$col.$row"

    companion object {
        val DEFAULT = GridCell(-1, -1)
    }
}

/** 一枚容器的完整状态。 */
data class ZoneState(
    val pos: ZonePlacement,
    /**
     * 容器内条目的**稳定次序**。#74 之后它只管三件事，**不再**是屏幕位置的来源：
     * ① 没摆过的条目按它推导默认格子（[defaultCellsOf]）；② 同一格子里叠放时的先后；
     * ③ [HudLayoutTable.visibleOrderOf] 那套"隐藏再显示不回默认位置"依赖它。
     */
    val order: List<HudEntry>,
    /**
     * 每颗条目**被用户摆过**的格子。缺席（或值为 [GridCell.DEFAULT]）＝没摆过 ⇒ 用推导格。
     *
     * 选 `Map<HudEntry, GridCell>` 而不是"与 [order] 并列的数组"：并列数组的下标就是 order 的下标，
     * 而 order 会在跨容器移动时增删——下标一生变，格子就跟着整体错位，那正是本次要拆掉的耦合。
     * 按条目身份 keyed 之后，增删顺序动不了任何人的格子。表里也**只存摆过的**（默认格不落表），
     * 持久化串才不会为 20 颗各写一份可推导的坐标。
     */
    val cells: Map<HudEntry, GridCell>
)

/**
 * 位置表本体（版本化）。持久化键 `hud_layout`，编解码见 [encode] / [decode]。
 */
data class HudLayoutTable(val version: Int, val zones: Map<HudZone, ZoneState>) {

    fun posOf(zone: HudZone): ZonePlacement = zones[zone]?.pos ?: ZonePlacement.DEFAULT

    /** 该容器的完整顺序（含被设置页关掉的条目） */
    fun orderOf(zone: HudZone): List<HudEntry> = zones[zone]?.order ?: emptyList()

    /**
     * 录制页/编辑页真正渲染的那份顺序：[visible]（设置里开着显示的条目）过滤一遍，**顺序仍来自表**。
     *
     * 「控件被隐藏、再打开显示时位置保持用户改过的值」就落在这里 + [moveEntryTo]：
     * 隐藏不写表，所以表里一直是用户放好的那个位置。
     */
    fun visibleOrderOf(zone: HudZone, visible: Set<HudEntry>): List<HudEntry> =
        orderOf(zone).filter { it in visible }

    /**
     * 写一枚容器的绝对位置。
     *
     * 底栏那枚**只该传 y**（x 传哨兵 -1，见 [normalizedPosOf]）：写入方是录制页的长按换栏与编辑页的整枚拖动，
     * 两处都只搬上下栏，写进绝对 x 就等于把快门横向钉死。越界钳制由调用方先做（[clampZonePos] 会把
     * 底栏的 x 强制抹回哨兵，调用方就算传了真值也存不进去）。
     */
    fun withZonePos(zone: HudZone, xDp: Int, yDp: Int): HudLayoutTable {
        val cur = zones[zone] ?: ZoneState(ZonePlacement.DEFAULT, emptyList(), emptyMap())
        return copy(zones = zones + (zone to cur.copy(pos = ZonePlacement(xDp, yDp))))
    }

    /** 该容器里**被用户摆过**的格子（含隐藏的条目；缺席或哨兵＝用推导格） */
    fun cellsOf(zone: HudZone): Map<HudEntry, GridCell> = zones[zone]?.cells ?: emptyMap()

    /** [cellsOf] 去掉哨兵与坏值后的净表（写回表里用的就是这一份） */
    private fun placedCellsOf(zone: HudZone): Map<HudEntry, GridCell> =
        cellsOf(zone).filter { (e, c) -> !c.isDefault && e in orderOf(zone) }

    /**
     * 这一枚容器**这一帧真正渲染**的格子清单（顺序＝[visibleOrderOf] 的顺序）。
     *
     * 两趟解析，规则只有一条：**任何一格不许有两条条目**（否则"移动一颗"又会通过重叠影响另一颗）：
     * 1. 摆过的条目先占自己的显式格子；
     * 2. 没摆过的条目按 [defaultCellsOf]（对**可见**顺序分行）取推导格，被占了就在容器内就近让到空格。
     * 默认表里没有任何显式格子 ⇒ 第 2 趟直接命中推导格 ⇒ 与今天的排布逐格相同（这是"默认表结构性等于
     * 今天"这条硬要求的落点，也是 v1 存量串免费迁移的原因）。
     */
    fun gridItems(zone: HudZone, plan: HudGridPlan): List<HudGridItem> {
        if (!zone.isGrid) return visibleOrderOf(zone, plan.visible).map { HudGridItem(it, GridCell.DEFAULT) }
        return resolveCells(
            order = visibleOrderOf(zone, plan.visible),
            placed = placedCellsOf(zone),
            derived = defaultCellsOf(zone, visibleOrderOf(zone, plan.visible), plan.perRowOf(zone)),
            cols = gridColsCapOf(zone),
            rows = GridRowHardCap
        )
    }

    /**
     * 该容器**当前被占住**的格子（可见条目按 [gridItems] 解析结果，隐藏条目按表里的显式格子）。
     *
     * 隐藏条目也算占用：它只是这帧不画，格子还是它的位置（"隐藏再显示不回默认位置"那套语义）。
     * 不把它算进来的话，用户往那颗的格子上放别的一条，它再显示时就会与别人挤在同一格。
     */
    fun occupiedCells(zone: HudZone, plan: HudGridPlan, exclude: HudEntry? = null): Set<GridCell> {
        if (!zone.isGrid) return emptySet()
        val visible = plan.visible
        val out = HashSet<GridCell>()
        gridItems(zone, plan).forEach { if (it.entry != exclude) out += it.cell }
        placedCellsOf(zone).forEach { (e, c) -> if (e !in visible && e != exclude) out += c }
        return out
    }

    /**
     * 把 [zones] 里这些网格容器的**推导格固化成显式格子**（只写格子，不改任何人的位置）。
     *
     * 这是"移动一颗不动另一颗"的实现核心：[order] 一旦被增删，还挂着哨兵的条目会按**新的**可见顺序
     * 重新推导 ⇒ 集体错位。所以任何改动 [order] 的操作之前，先把受牵连的那两枚容器（来源与目标）
     * 的当前格子原样钉住；钉过之后 [order] 就再也决定不了任何一颗的实际位置。
     */
    fun pinned(plan: HudGridPlan, vararg zones: HudZone): HudLayoutTable {
        var table = this
        zones.filter { it.isGrid }.distinct().forEach { zone ->
            val items = table.gridItems(zone, plan)
            if (items.isNotEmpty()) {
                val merged = table.placedCellsOf(zone) + items.associate { it.entry to it.cell }
                table = table.replaceCells(zone, merged)
            }
        }
        return table
    }

    /** 换掉一枚容器的格子表，其余字段（位置与顺序）一动不动 */
    private fun replaceCells(zone: HudZone, cells: Map<HudEntry, GridCell>): HudLayoutTable {
        val cur = zones[zone] ?: ZoneState(ZonePlacement.DEFAULT, defaultOrderOf(zone), emptyMap())
        return copy(zones = zones + (zone to cur.copy(cells = cells)))
    }

    /**
     * 把条目摆到 [target] 的第 [cell] 格（同容器换格 = 同一个调用；[cell] 传 [GridCell.DEFAULT]
     * 就是"只换顺序、位置交给推导"，[moveEntryTo] 走的就是这一支）。
     *
     * 四步，顺序有讲究：
     * 1. **先钉格子**（来源与目标两枚容器，[pinned]）——这一步保证"其他一颗都不动"；
     * 2. 再改顺序：从**所有**容器摘掉它，插进目标第 [index] 格（越界夹到 `[0, size]`）；
     *    先摘后插保证「一颗控件同时只属于一枚容器」；
     * 3. 格子落位：目标不是网格容器 ⇒ 抹成哨兵（不留脏数据）；是网格 ⇒ 先钳进容器硬上限，
     *    再在"被别人占住的格子"之外就近找空格（[occupiedCells] 已排除它自己那一格）；
     * 4. 其余三枚容器一行不碰 ⇒ 它们的顺序没变，哨兵条目继续推导出同一个格子。
     */
    fun placeEntryAt(
        entry: HudEntry,
        target: HudZone,
        index: Int,
        cell: GridCell,
        plan: HudGridPlan
    ): HudLayoutTable {
        val source = sourceZoneOf(entry)
        val pinnedTable = pinned(plan, source, target)
        val orders = LinkedHashMap<HudZone, MutableList<HudEntry>>()
        HudZone.ALL.forEach { z ->
            val list = (pinnedTable.zones[z]?.order ?: defaultOrderOf(z)).toMutableList()
            list.remove(entry)
            orders[z] = list
        }
        val targetOrder = orders.getValue(target)
        targetOrder.add(index.coerceIn(0, targetOrder.size), entry)
        val finalCell = when {
            // 非网格容器：格子对它不生效，一律抹成哨兵（[normalize] 那一道同样会抹）
            !target.isGrid -> GridCell.DEFAULT
            // 显式传哨兵 = "这次只换顺序，位置仍交给推导"（[moveEntryTo] 走的就是这一支）。
            // 与第 1 步的钉格配套：别人的格子已经钉死，所以这里"交给推导"不会牵动任何人。
            cell.isDefault -> GridCell.DEFAULT
            else -> freeCellNear(
                wanted = clampStoredCell(target, cell),
                occupied = pinnedTable.occupiedCells(target, plan, exclude = entry),
                cols = gridColsCapOf(target),
                rows = GridRowHardCap
            )
        }
        val nextZones = HudZone.ALL.associateWith { z ->
            val state = pinnedTable.zones[z] ?: ZoneState(ZonePlacement.DEFAULT, defaultOrderOf(z), emptyMap())
            val cells = if (z == target) {
                if (finalCell.isDefault) state.cells - entry else state.cells + (entry to finalCell)
            } else {
                state.cells - entry
            }
            state.copy(order = orders.getValue(z).toList(), cells = cells)
        }
        return HudLayoutTable(version, nextZones).normalize()
    }

    /**
     * 把条目挪到 [zone] 的第 [index] 格（同容器换序 = 同一个调用），**位置交给推导**。
     *
     * #74 之后它只是 [placeEntryAt] 的哨兵格版本：还留着是因为非网格容器（顶栏/底栏）本来就该按
     * 顺序排，且它同时负责"把来源容器钉住"这件必要的事——少了那一步，从网格容器抽走一颗就会让
     * 剩下的那颗集体上移（正是用户抱怨的那个耦合）。
     */
    fun moveEntryTo(entry: HudEntry, zone: HudZone, index: Int, plan: HudGridPlan): HudLayoutTable =
        placeEntryAt(entry, zone, index, GridCell.DEFAULT, plan)

    /** 条目当前归属（表里没有时回 [HudEntry.homeZone]） */
    fun sourceZoneOf(entry: HudEntry): HudZone =
        HudZone.ALL.firstOrNull { entry in orderOf(it) } ?: entry.homeZone

    /** 整张表的条目（按容器分组、容器按 [HudZone.ALL] 顺序） */
    fun allEntries(): List<HudEntry> = HudZone.ALL.flatMap { orderOf(it) }

    /**
     * 补全 + 去重 + 丢坏值：
     * - 同一个 id 出现在两枚容器 → **先到先得**，后出现的丢掉（手改 prefs 造成的重复不能让一颗控件渲染两遍）；
     * - 表里完全没有的条目 → 追加到它的 [HudEntry.homeZone] 末尾（新增 CamPill 位时老配置自动补齐）；
     * - ordinal 越界的条目 → 丢弃；
     * - 格子表跟着条目走：丢了的人的格子一起丢，非网格容器（[HudZone.isGrid] = false）上的格子一律抹成
     *   缺席（那两枚按顺序排，留着只会变成"下次进网格容器时莫名复活一个老位置"）。
     */
    fun normalize(): HudLayoutTable {
        val seen = HashSet<HudEntry>()
        val placed = LinkedHashMap<HudZone, MutableList<HudEntry>>()
        val placedCells = LinkedHashMap<HudZone, MutableMap<HudEntry, GridCell>>()
        HudZone.ALL.forEach { placed[it] = mutableListOf(); placedCells[it] = LinkedHashMap() }
        for (zone in HudZone.ALL) {
            val cells = cellsOf(zone)
            for (e in orderOf(zone)) {
                if (e.pill == null && e.item == null) continue
                if (seen.add(e)) {
                    placed.getValue(zone).add(e)
                    if (zone.isGrid) cells[e]?.let { c -> if (!c.isDefault) placedCells.getValue(zone)[e] = c }
                }
            }
        }
        for (e in HudEntry.ALL) {
            if (seen.add(e)) placed.getValue(e.homeZone).add(e)
        }
        val nextZones = LinkedHashMap<HudZone, ZoneState>()
        HudZone.ALL.forEach { z ->
            nextZones[z] = ZoneState(normalizedPosOf(z), placed.getValue(z).toList(), placedCells.getValue(z))
        }
        return HudLayoutTable(version, nextZones)
    }

    /**
     * 位置段的合规化（[normalize] 的其中一步，单独露出来给用例直接打）：
     * - **底栏只认 y**：绝对 x 一律抹回哨兵。落一次位就把 x 写成绝对值会把"快门跟随可视中心"冻住
     *   （90↔270 翻转与横竖换档都不再重新居中，B4 审查 S1 那条设计缺陷），所以这里连存量脏数据一起治；
     * - 其余四枚：一轴绝对、一轴哨兵的半吊子（只能来自手改 prefs）按未编辑处理，见 [ZonePlacement] 的说明。
     */
    fun normalizedPosOf(zone: HudZone): ZonePlacement {
        val raw = posOf(zone)
        return if (zone == HudZone.BOTTOM) {
            if (raw.yDp >= 0) ZonePlacement(-1, raw.yDp) else ZonePlacement.DEFAULT
        } else {
            if (raw.xDp >= 0 && raw.yDp >= 0) raw else ZonePlacement.DEFAULT
        }
    }

    /**
     * 编码（v2）：`v2;T,8,4,P11,P12;L,-1,-1,P6:0.0,P7:0.1,P8:0.2;…`
     *
     * 分号分「版本段 / 五个容器段」，逗号分「x / y / id 串」；位置段的哨兵 `-1` = 该容器仍在默认位置。
     * **v2 多的就是条目后缀**：`P8:0.2` = 摆到第 0 列第 2 行，光一串 `P8` = 没摆过（用推导格）。
     * 后缀只在 [HudZone.isGrid] 的容器上写，非网格容器（顶栏/底栏）写了也没人读，不留脏数据。
     * 与 [CurveStack] 一样是一条紧凑字符串，同一个 prefs 文件、同样的 `putString + runCatching` 风格。
     */
    fun encode(): String = buildString {
        append("v").append(version)
        for (zone in HudZone.ALL) {
            append(';').append(zone.key).append(',')
            append(posOf(zone).xDp).append(',').append(posOf(zone).yDp)
            append(',')
            val cells = placedCellsOf(zone)
            orderOf(zone).forEachIndexed { i, e ->
                if (i > 0) append(',')
                append(e.id)
                if (zone.isGrid) {
                    append(':')
                    append(cells[e]?.token ?: GridCell.DEFAULT.token)
                }
            }
        }
    }

    companion object {
        /**
         * 当前 schema 版本。v1 = 只有顺序没有格子（B4 那批），v2 = 每颗条目带一个格子（任务 #74）。
         * 改格式就 +1，并在 [decode] 里对旧版本做处置。
         */
        const val CURRENT_VERSION = 2

        /** 未编辑过的初值：位置全默认、顺序全按 [HudEntry.homeZone] 内的枚举声明序、格子全哨兵 */
        fun default(): HudLayoutTable = HudLayoutTable(
            CURRENT_VERSION,
            HudZone.ALL.associateWith {
                ZoneState(ZonePlacement.DEFAULT, defaultOrderOf(it), emptyMap())
            }
        ).normalize()

        /**
         * 默认表里每枚容器的条目顺序 = **B1–B3 定稿代码里的书写顺序**，逐个对过源码：
         * · 顶栏 `TopCapsule` 的 buildList：SIZE → STORAGE
         * · 左竖 Dock：参考线 → 屏幕监看 → RGB 曲线 → 闪光灯
         * · 右竖 Dock：姿态仪 → 音量表 → 蓝牙 → 变焦 → 对焦 → 防抖
         * · 读数块：`HudItem.typesOf(mask)` 的枚举序（快门 帧率 码率 ISO EV 白平衡 变焦）
         * · 底栏：只有镜头那颗是可编辑条目（缩略图与快门不可隐藏，不进表）
         *
         * 注意这张清单**只写顺序不写坐标**：默认格子由 [defaultCellsOf] 从它现推，
         * 于是"默认表"与"录制页今天的排布"是同一个函数的输出（AGENTS 那条"手抄的默认值会和实现分叉"）。
         */
        fun defaultOrderOf(zone: HudZone): List<HudEntry> = when (zone) {
            HudZone.TOP -> listOf(HudEntry.of(CamPill.SIZE), HudEntry.of(CamPill.STORAGE))
            HudZone.LEFT -> listOf(
                HudEntry.of(CamPill.REFLINE), HudEntry.of(CamPill.MONITOR),
                HudEntry.of(CamPill.CURVE), HudEntry.of(CamPill.FLASH)
            )
            HudZone.RIGHT -> listOf(
                HudEntry.of(CamPill.LEVEL), HudEntry.of(CamPill.VOLUME), HudEntry.of(CamPill.BT),
                HudEntry.of(CamPill.ZOOM), HudEntry.of(CamPill.FOCUS), HudEntry.of(CamPill.STAB)
            )
            HudZone.BOTTOM -> listOf(HudEntry.of(CamPill.LENS))
            HudZone.READOUT -> HudItem.ALL.map { HudEntry.of(it) }
        }

        /**
         * 解码：任何异常（null / 空串 / 缺段 / 未知容器 id / 版本不认识 / 数字炸）都**不抛**，
         * 坏的那一段丢掉、其余保留，整张表最后过一次 [normalize]。
         * 版本号**比本工程新**时整表按默认处理：新格式老代码读不懂，硬解会写出错位置。
         *
         * **v1 → v2 的迁移不换算坐标，而是"格子全留哨兵"**：v1 的位置本来就是「顺序 × 分行」推导出来的，
         * 而 v2 的默认格子用的正是同一条 [defaultCellsOf] ⇒ 老串读进来逐颗落点与改前相同，
         * 用户已经拖过的东西一样都不会被打回默认（这是本条迁移的硬要求）。
         * 解出来的表版本一律抬到 [CURRENT_VERSION]：下一次保存自然写成 v2。
         * 单条 token 的格子后缀坏了（`P6:0`、`P6:x.y`）只丢那一颗的格子，条目本身照留。
         */
        fun decode(raw: String?): HudLayoutTable {
            if (raw.isNullOrBlank()) return default()
            val parts = raw.split(';')
            val header = parts.firstOrNull().orEmpty()
            val version = header.removePrefix("v").toIntOrNull()
            if (version == null || version > CURRENT_VERSION) return default()
            // 只有 v1（顺序模型）与 v2（格子模型）认得；v0 / 负数一律按坏串回默认
            if (version < 1) return default()
            val zones = LinkedHashMap<HudZone, ZoneState>()
            for (seg in parts.drop(1)) {
                val f = seg.split(',')
                val zone = HudZone.fromKey(f.getOrNull(0)) ?: continue
                val x = f.getOrNull(1)?.toIntOrNull() ?: -1
                val y = f.getOrNull(2)?.toIntOrNull() ?: -1
                val order = mutableListOf<HudEntry>()
                val cells = LinkedHashMap<HudEntry, GridCell>()
                for (rawToken in f.drop(3)) {
                    val head = rawToken.substringBefore(':')
                    val entry = HudEntry.fromId(head) ?: continue
                    order += entry
                    if (!zone.isGrid) continue
                    val suffix = rawToken.substringAfter(':', "")
                    if (suffix.isEmpty() || suffix == "-") continue // v1 光串 / 显式哨兵 = 没摆过
                    parseCell(suffix)?.let { cells[entry] = clampStoredCell(zone, it) }
                }
                zones[zone] = ZoneState(ZonePlacement(x, y), order, cells)
            }
            return HudLayoutTable(CURRENT_VERSION, zones).normalize()
        }

        /** `0.2` → [GridCell]；缺列、非数字、负数一律 null（调用方把那颗按"没摆过"处理） */
        private fun parseCell(token: String): GridCell? {
            val f = token.split('.')
            if (f.size != 2) return null
            val col = f[0].toIntOrNull() ?: return null
            val row = f[1].toIntOrNull() ?: return null
            return if (col < 0 || row < 0) null else GridCell(col, row)
        }
    }
}

// ------------------------------------------------------------------ 越界钳制与落位（纯函数）

/**
 * 安全区与两条实测避让量，单位全 dp。
 *
 * - [width]/[height]：root 容器套上 `safeDrawingPadding()` 之后的**实测尺寸**（不是 `screenWidthDp`）。
 *   它就是位置表 (x, y) 的坐标参考：挖孔在哪条边，系统就把那条边让掉，于是这里**不需要**任何
 *   "按方向补一笔右缘让位"的字段——这里曾有 `endInsetDp`（喂的是写死的 `VisibleEndInset = 34.dp`），
 *   2026-09-29 真机复测证明那条"可视右缘 1532"是伪值、68px 让位来自会换边的挖孔，字段已删净
 *   （docs/plan/13 §九·补）。安全区本身已经把避让算进 [width]，再扣一次就是双重让位。
 * - [topAvoidDp]/[bottomAvoidDp]：顶栏与底栏的**实测**避让量（S2-1 / S2-2 C 那两路回报），
 *   不是新写的魔法数。编辑页的这两个值是它自己那条操作栏与底栏 Dock 实占带的高。
 *   ⚠ **#70 A 之后这两条轴是"按容器分别喂"的**：底栏那一排的带高只喂给 `LEFT` / `RIGHT`
 *   （它们几何上真会叠在底栏上）与"读数块退化到整排之上"那一档；`READOUT` 拿到的是
 *   [ReadoutRowPlan.bottomAvoidDp]。横屏恒与底栏**共用同一条基线**（定版：`读数缩到一行两颗，横屏永远同行`），
 *   所以同一帧里不同容器读到的 `bottomAvoidDp` 不再同一个数——这是有意的，别再合并回去。
 */
data class HudAreaDp(
    val width: Int,
    val height: Int,
    val topAvoidDp: Int,
    val bottomAvoidDp: Int
)

/** 一个 dp 矩形（安全区局部坐标，左上原点） */
data class HudRectDp(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

data class HudPointDp(val x: Int, val y: Int)

/**
 * 横向可放区间 `[0, 安全区宽 − 容器宽]`。安全区宽已经是"套上 `safeDrawingPadding()` 之后"的量，
 * 挖孔让掉的边在里面扣过了，所以这里不再按方向另扣一笔（旧写法扣 34dp，两个横屏姿态里必错一次）。
 * 容器比安全区还宽（120% 文本 + 极窄分屏）时上限夹成 0，也就是贴左放，**不许为负**。
 */
fun clampZoneX(area: HudAreaDp, widthDp: Int): Int =
    (area.width - widthDp).coerceAtLeast(0)

/**
 * 纵向可放区间。
 * - [HudZone.TOP] / [HudZone.BOTTOM]：整条安全区（顶栏本来贴顶、底栏可以搬到上栏，见第 8 条）；
 * - 其余三枚：`[topAvoid, height − bottomAvoid − h]` —— 这就是「钳制必须吃 topBarH / bottomBarH 实测避让量」的落点。
 *
 * 带高不够（告警条 + 满配读数把带挤没了）时退成整条安全区，**区间永不为负**，
 * 否则 `coerceIn` 会抛而整个 HUD 就没了。
 */
fun clampZoneYRange(zone: HudZone, area: HudAreaDp, heightDp: Int): IntRange {
    val band = zone != HudZone.TOP && zone != HudZone.BOTTOM
    val lo = if (band) area.topAvoidDp.coerceAtLeast(0) else 0
    val hi = if (band) area.height - area.bottomAvoidDp - heightDp else area.height - heightDp
    // 带高不够（告警条 + 满配读数把带挤没了，或容器本身比屏还高）时退成整条安全区，
    // 区间不许为负 —— `coerceIn` 遇到 lo > hi 是直接抛的，那一抛整个 HUD 就没了
    return if (hi < lo) 0..(area.height - heightDp).coerceAtLeast(0) else lo..hi
}

/**
 * 绝对位置钳进安全区（尺寸未测到时传 0，等价于「只保证左上角不越界」）。
 *
 * **底栏的 x 在这里就被抹成哨兵**：它只能上下搬（第 8 条的上栏/下栏），写入方就算把实测左缘换算成
 * 绝对 x 送进来也存不进表——那会让快门从此不再跟随可视中心（90↔270 翻转、横竖换档都不重新居中，
 * B4 审查 S1 那条）。[HudLayoutTable.normalizedPosOf] 在读表时同样抹一次，把存量脏数据一起治掉。
 */
fun clampZonePos(zone: HudZone, xDp: Int, yDp: Int, wDp: Int, hDp: Int, area: HudAreaDp): ZonePlacement {
    val yRange = clampZoneYRange(zone, area, hDp)
    val x = if (zone == HudZone.BOTTOM) -1 else xDp.coerceIn(0, clampZoneX(area, wDp))
    return ZonePlacement(x, yDp.coerceIn(yRange.first, yRange.last))
}

// ------------------------------------------------------------------ 格子（#74：容器内的固定格网）

/** 网格行数硬上限：每颗条目最多占一行，比条目总数还多的行编辑页**摆不出来**，只能来自手改 prefs */
private val GridRowHardCap: Int get() = HudEntry.ALL.size

/**
 * 网格列数硬上限（逐枚容器，都从既有真源推出来，不是新造的观感值）：
 * - [HudZone.READOUT] → 3：`hudPerRowFor` 的最高档就是一行三颗，编辑页也摆不出第四列；
 * - 两枚竖 Dock → 2：`hudRowGroups` 的 [HudZone.RIGHT] 分支最多一行两颗（S2-2 B 那条姿态仪 + 音量表
 *   并排），[HudZone.LEFT] 更是一行一颗；
 * - 非网格容器 → 1（格子对它不生效，这里只给个不为 0 的数免得钳制算式里出现除零）。
 */
fun gridColsCapOf(zone: HudZone): Int = when {
    !zone.isGrid -> 1
    zone == HudZone.READOUT -> 3
    else -> 2
}

/**
 * 存进表 / 从表里读出来的格子先过这一道：列钳进 [gridColsCapOf]、行钳进 [GridRowHardCap]。
 * 哨兵原样放行（它表示"没摆过"，不是越界值）。
 */
fun clampStoredCell(zone: HudZone, cell: GridCell): GridCell =
    if (cell.isDefault) cell else GridCell(
        cell.col.coerceIn(0, gridColsCapOf(zone) - 1),
        cell.row.coerceIn(0, GridRowHardCap - 1)
    )

/** 指针/坐标落在哪一格之外的"容器可放带"（单位 dp，由调用方从实测带高与可用宽换算来） */
data class GridBox(val cols: Int, val rows: Int) {
    init {
        require(cols >= 1 && rows >= 1) { "可放格数至少各 1，收到 cols=$cols rows=$rows" }
    }
}

/**
 * 可放带 → [GridBox]：行由**带高**决定（`带高 ÷ 行距`），列对读数块由**可用内宽**决定、对两枚竖 Dock
 * 直接取 [gridColsCapOf]。
 *
 * 选「钳回可放带」而不是「扩行/列把容器撑出带外」：`heightIn(max = bandHeightDp)` 那条硬约束在这儿没变，
 * 真扩出去的行不会把带撑大，只会被 [com.wotagei.cam.ui.HudLayer] 的 `verticalScroll` 接走——
 * 那是"看得见但要点一下才够得着"，而把落点钳回来是"根本放不下"。两者都不静默裁字，但钳回不会让人
 * 以为控件丢了。列的方向没有滚动可依赖（[HudZone.READOUT] 那枚的宽度账由 `planReadoutRow` 说话），
 * 所以列必须钳：越过可用宽就是把 §58/§73 那族"按窄窗量出来的列数在宽窗里越界"再犯一遍。
 */
fun gridBoxOf(zone: HudZone, pitchXDp: Int, pitchYDp: Int, bandHeightDp: Int, roomWidthDp: Int): GridBox {
    val rows = if (pitchYDp <= 0) 1 else (bandHeightDp / pitchYDp).coerceAtLeast(1)
    val cols = if (zone != HudZone.READOUT) gridColsCapOf(zone)
    else if (pitchXDp <= 0) 1 else (roomWidthDp / pitchXDp).coerceIn(1, gridColsCapOf(zone))
    return GridBox(cols.coerceAtLeast(1), rows.coerceAtLeast(1))
}

/** 把格子钳进可放带（负数与越界都收回来；[GridCell.DEFAULT] 原样放行） */
fun clampCellToBox(cell: GridCell, box: GridBox): GridCell =
    if (cell.isDefault) cell else GridCell(
        cell.col.coerceIn(0, box.cols - 1),
        cell.row.coerceIn(0, box.rows - 1)
    )

/**
 * 目标格被占了就在可放带里就近找**空格**（曼哈顿距离最小，同距离按「先上后下、先左后右」定序）。
 *
 * 为什么是"只落空格"而不是"与占位那颗交换"：交换的语义就是"移动一颗会动另一颗"，与用户这句诉求
 * （「移到一项其他项也会同时移动，这是极大的限制」）正面冲突；找空位是本次唯一能让"我只搬了这一颗"
 * 成立的写法。代价照实写：拖到已占的格子上时那颗不会如手指预期压上去，而是停在旁边一格。
 *
 * 带内一个空格都没有时返回 [wanted] 自己（调用方已钳过，此时带被填满——编辑页不会走到这一支，
 * 因为带内格子数恒 ≥ 该容器条目数 + 1；真走到了也不许把两颗摞在同一格，所以 [HudLayoutTable.placeEntryAt]
 * 的落点仍由 [freeCellNear] 决定，见它的 KDoc 第 3 步）。
 */
fun freeCellNear(wanted: GridCell, occupied: Set<GridCell>, cols: Int, rows: Int): GridCell {
    val c = wanted.col.coerceIn(0, (cols - 1).coerceAtLeast(0))
    val r = wanted.row.coerceIn(0, (rows - 1).coerceAtLeast(0))
    if (GridCell(c, r) !in occupied) return GridCell(c, r)
    val span = (cols.coerceAtLeast(1) * rows.coerceAtLeast(1))
    for (d in 1..span) {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (GridCell(col, row) in occupied) continue
                if (abs(col - c) + abs(row - r) != d) continue
                return GridCell(col, row)
            }
        }
    }
    return GridCell(c, r)
}

/** 一条条目在这一格（渲染层拿到的就是这一对，不再有第二份算式） */
data class HudGridItem(val entry: HudEntry, val cell: GridCell)

/**
 * 解析一帧的格子清单：摆过的先占位，没摆过的用 [defaultCellsOf] 推，撞了就就近让位。
 *
 * 两趟（先全部显式、再全部推导）而不是逐颗按顺序处理：否则"谁先占位"取决于 [order] 的先后，
 * 而 [order] 正是本次要降级的那个东西。返回值保证**逐格唯一**（[HudLayoutGridTest] 直接打这一条），
 * 所以搜索盒必须用**整容器**的 `[0, cols) × [0, rows)`，不能拿"推导格自身那一小片"当盒——
 * 一片被占满时按小盒搜会搜不到空格而把两颗摞在同一格，那正是本次要修的老行为。
 */
internal fun resolveCells(
    order: List<HudEntry>,
    placed: Map<HudEntry, GridCell>,
    derived: Map<HudEntry, GridCell>,
    cols: Int,
    rows: Int
): List<HudGridItem> {
    val taken = HashSet<GridCell>()
    val out = LinkedHashMap<HudEntry, GridCell>()
    // 第 1 趟：摆过的条目占位。两颗被手改成同一格时先到先得、后者就近让位——[order] 在这里就是
    // "同一格子里的稳定次序"那一条用途（枚举头的降级清单第 ① 条）
    for (e in order) {
        val want = placed[e]?.takeIf { !it.isDefault } ?: continue
        val cell = if (want in taken) freeCellNear(want, taken, cols, rows) else want
        taken += cell
        out[e] = cell
    }
    // 第 2 趟：没摆过的用推导格；推导格被摆过的那颗占了就让位（显式优先，不然"拖过去的那颗"会被
    // 一颗根本没编辑过的条目挤走）
    for (e in order) {
        if (out.containsKey(e)) continue
        val cell = freeCellNear(derived[e] ?: GridCell(0, 0), taken, cols, rows)
        taken += cell
        out[e] = cell
    }
    return order.map { HudGridItem(it, out.getValue(it)) }
}

/**
 * 「今天的排布」→ 格子的唯一一处换算：顺序按 [hudRowGroups] 分行，第几行就是 row、行内第几颗就是 col。
 *
 * **不许手抄一份默认坐标**（理由与 [ZonePlacement.DEFAULT] 同一条）：抄一份就会与实现分叉，
 * 而这条函数与容器渲染读的是同一个 [hudRowGroups]，所以"没编辑过的表"结构性等于 B1–B3 定稿排布。
 */
fun defaultCellsOf(zone: HudZone, order: List<HudEntry>, perRow: Int): Map<HudEntry, GridCell> =
    hudRowGroups(zone, order, perRow).flatMapIndexed { row, group ->
        group.mapIndexed { col, entry -> entry to GridCell(col, row) }
    }.toMap()

/** 一帧网格解析要的两份运行时输入（都来自组合期实测，表本身不该知道它们） */
data class HudGridPlan(
    /** 设置里开着显示的条目集：两页各有一份同源计算（CameraScreen 与编辑页），传进来而不是表里再算一遍 */
    val visible: Set<HudEntry>,
    /** 读数块"一行几颗"那一档（[hudPerRowFor] / [planReadoutRow] 的产物），只作用 [HudZone.READOUT] */
    val readoutPerRow: Int
) {
    /** 该容器推导默认格子时的分行档：只有读数块吃这个数，其余容器 [hudRowGroups] 不读它 */
    fun perRowOf(zone: HudZone): Int = if (zone == HudZone.READOUT) readoutPerRow else 1
}

// ------------------------------------------------------------------ 格子 ↔ 像素（渲染与编辑页共用）

/** 一个 px 尺寸（[HudLayout] 是纯 Kotlin，不引 Android 的 IntSize） */
data class HudSizePx(val width: Int, val height: Int)

/** 一个 px 坐标 */
data class HudPointPx(val x: Int, val y: Int)

/**
 * 格子边长 = **该容器内最宽/最高那颗的实测尺寸 + 一枚行距**。
 *
 * 定 pitch 是"移动一颗不挪另一颗"的另一半：格子位置只跟 col/row 有关，跟"有几颗、谁在前"无关。
 * pitch 取实测尺寸 ⇒ 字体拉到 120% 时格子自己变大，§58/§73 那族"按 100% 量出来的固定值在 120% 裁字"
 * 在这里不复发（与 [hudPerRowFor] 用 `fontScale` 折算同一族防线，只是这一处量的是真尺寸不是估宽）。
 *
 * 行距没有省掉：`pitch = 最宽颗 + gap` 才让"只有一列 / 只有一行"时的总宽总高等于今天
 * `spacedBy(gap)` 的紧凑排布——那正是"默认表结构性等于今天的排布"这条硬要求要的等式。
 */
fun gridPitchPx(maxChildWidthPx: Int, maxChildHeightPx: Int, gapXPx: Int, gapYPx: Int): HudSizePx =
    HudSizePx(maxChildWidthPx + gapXPx, maxChildHeightPx + gapYPx)

/** 网格总尺寸：`列数 × 格宽 − 一道行距`（最后一格后面不再有间距，与 `spacedBy` 同一条账） */
fun gridSizePx(cols: Int, rows: Int, pitch: HudSizePx, gap: HudSizePx): HudSizePx {
    if (cols <= 0 || rows <= 0) return HudSizePx(0, 0)
    return HudSizePx(cols * pitch.width - gap.width, rows * pitch.height - gap.height)
}

/**
 * [gridSizePx] 的逆算：编辑页拿的是网格节点的**实测矩形**，把格长除回来而不是再量一遍每颗条目。
 *
 * 为什么量网格不量条目：姿态仪与音量表那两颗本来就不报锚点（[HudEntry.pillKey] 返回 null 那三条之一），
 * 靠条目矩形取"最宽/最高那颗"会漏掉它们——那正是 §58/§73 那族"量窄了于是裁字"的成因。
 * 网格节点是渲染层用同一个 [gridPitchPx] 算完再 `layout()` 出去的，矩形与格长是同一个数的两面，
 * 所以这里能整除回去（`(矩形 + 一道行距) ÷ 格数`），不需要第二条公式。
 *
 * 一列/一行都还没有（空容器）时退回"被拖那颗自己的尺寸 + 行距"：那一刻格长只能由它撑。
 */
fun gridPitchOf(
    gridWidthPx: Int,
    gridHeightPx: Int,
    cols: Int,
    rows: Int,
    gapPx: Int,
    fallbackChildWidthPx: Int,
    fallbackChildHeightPx: Int
): HudSizePx = HudSizePx(
    width = if (cols > 0) (gridWidthPx + gapPx) / cols else (fallbackChildWidthPx + gapPx).coerceAtLeast(1),
    height = if (rows > 0) (gridHeightPx + gapPx) / rows else (fallbackChildHeightPx + gapPx).coerceAtLeast(1)
)

/** 某格的左上角相对网格原点（**格子 → 坐标的正算式**，[cellAtPointer] 是它的逆） */
fun cellOffsetPx(cell: GridCell, pitch: HudSizePx): HudPointPx =
    HudPointPx(cell.col.coerceAtLeast(0) * pitch.width, cell.row.coerceAtLeast(0) * pitch.height)

/**
 * 指针 → 它**压住的那一格**（**编辑页吸附用的逆算式**，与 [cellOffsetPx] 一对一）。
 *
 * 取"压住"（floor）而不是"中心最近"（round）：手指按在条目中心拖，条目中心落在哪一格就该是哪一格；
 * 用 round 会让指针对在格子左缘那一瞬跳到左边一格，视觉上像吸附迟滞了一格。
 * 网格原点由 `HudEntryGrid` 节点自己回报（窗口矩形），所以这里不需要知道格内居中偏移
 * （渲染层用 [cellPlaceOffsetPx] 补那半截，编辑页只按格网原点吸附，两条算式互不干扰）。
 */
fun cellAtPointer(pointer: HudPointPx, origin: HudPointPx, pitch: HudSizePx): GridCell {
    if (pitch.width <= 0 || pitch.height <= 0) return GridCell(0, 0)
    return GridCell(
        col = floorDiv(pointer.x - origin.x, pitch.width).coerceAtLeast(0),
        row = floorDiv(pointer.y - origin.y, pitch.height).coerceAtLeast(0)
    )
}

/** 格内的居中偏移（颗比格子小的时候补这一段；[cellOffsetPx] 是格网左上角，这一条才是 child 的左上角） */
fun cellPlaceOffsetPx(cell: GridCell, pitch: HudSizePx, child: HudSizePx): HudPointPx {
    val g = cellOffsetPx(cell, pitch)
    return HudPointPx(g.x + (pitch.width - child.width) / 2, g.y + (pitch.height - child.height) / 2)
}

/** 向下取整的除法（`/` 对负数是截断，指针拖到网格左上方时会给出 -0 这种暧昧值） */
private fun floorDiv(v: Int, p: Int): Int {
    val q = v / p
    return if (v % p != 0 && (v xor p) < 0) q - 1 else q
}

/**
 * 外接矩形重叠检测（重叠**只提示不禁止**，所以这里只负责「提示看得懂」：把重叠的两枚容器报出来）。
 * 返回按 [HudZone.ALL] 顺序去重后的容器名对，调用方拿它拼一句人话。
 */
fun overlappingZones(rects: Map<HudZone, HudRectDp>): List<Pair<HudZone, HudZone>> {
    val out = mutableListOf<Pair<HudZone, HudZone>>()
    val list = HudZone.ALL.filter { rects.containsKey(it) }
    for (i in list.indices) {
        for (j in i + 1 until list.size) {
            val a = rects.getValue(list[i])
            val b = rects.getValue(list[j])
            if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) {
                out += list[i] to list[j]
            }
        }
    }
    return out
}

/**
 * 落点在哪枚容器：命中**最里面**（面积最小）的那枚，都不命中返回 null（= 这次拖动不改归属）。
 * 取最小面积而不是第一个命中，是因为读数块贴在底栏 Dock 右上时会嵌套；按顺序取第一枚就会把
 * 「拖进读数块」判成「拖进底栏」。
 */
fun zoneAt(pointer: HudPointDp, rects: Map<HudZone, HudRectDp>): HudZone? =
    rects.filter { it.value.contains(pointer.x, pointer.y) }.minByOrNull { it.value.width * it.value.height }?.key

/**
 * 松手后条目该插到目标容器的第几格。
 *
 * [items] 是目标容器**当前渲染顺序**的实测矩形（安全区局部坐标，与 [pointer] 同一坐标系）。
 * - ROW / COLUMN：沿主轴数「有几颗的中心比指针更靠前」；
 * - GRID（读数块那种一行多颗、按 [HudZone] 换行）：先按上下边界把行分簇，再在指针那一行里数横向。
 *
 * 返回 0..size，越界由 [HudLayoutTable.moveEntryTo] 再夹一次。
 */
fun dropIndexFor(axis: HudAxis, items: List<HudRectDp>, pointer: HudPointDp): Int {
    if (items.isEmpty()) return 0
    return when (axis) {
        HudAxis.ROW -> items.count { (it.left + it.right) / 2 < pointer.x }
        HudAxis.COLUMN -> items.count { (it.top + it.bottom) / 2 < pointer.y }
        HudAxis.GRID -> {
            val rows = clusterRows(items)
            val row = rows.firstOrNull { pointer.y in it.first().top..it.last().bottom }
                ?: rows.minByOrNull { abs((it.first().top + it.last().bottom) / 2 - pointer.y) }
            if (row == null) items.size else {
                val beforeRows = rows.takeWhile { it !== row }.sumOf { it.size }
                beforeRows + row.count { (it.left + it.right) / 2 < pointer.x }
            }
        }
    }
}

/** 按竖直方向重叠把条目分簇成「行」（保持入参顺序，簇内也按入参顺序） */
private fun clusterRows(items: List<HudRectDp>): List<List<HudRectDp>> {
    val rows = mutableListOf<MutableList<HudRectDp>>()
    for (rect in items.sortedBy { it.top }) {
        val hit = rows.firstOrNull { row ->
            val r = row.first()
            rect.top < r.bottom && r.top < rect.bottom
        }
        (hit ?: mutableListOf<HudRectDp>().also { rows += it }).add(rect)
    }
    // 还原入参顺序，调用方要的是「按当前顺序插到哪」
    return rows.map { row -> items.filter { it in row } }.filter { it.isNotEmpty() }
}

// ------------------------------------------------------------------ 第 8 条：底栏 Dock 的上栏/下栏

/**
 * 底栏 Dock 的两档 y（用户第 8 条：长按收起两颗 → 上下拖 → 松手落「上栏 / 下栏」）。
 *
 * **下栏 = 定稿位置**（底边贴安全区底再让开 [bottomPadDp]，调用方传 `BottomBarOuterPadV`），
 * **上栏 = 下栏再上一整排**，间距一枚 [gapDp]（调用方传令牌 `WotaSpace.s`，这里不散写观感值）：
 * `上栏 = 安全区高 − 下边距 − 排高 − 排高 − gap`。全程只有实测高与令牌，没有机型数值。
 */
fun dockLowerY(area: HudAreaDp, heightDp: Int, bottomPadDp: Int): Int =
    (area.height - bottomPadDp - heightDp).coerceAtLeast(0)

fun dockUpperY(area: HudAreaDp, heightDp: Int, bottomPadDp: Int, gapDp: Int): Int =
    (dockLowerY(area, heightDp, bottomPadDp) - heightDp - gapDp).coerceAtLeast(0)

/**
 * 拖动位移 → 落在哪一栏（**只有两档**，第 8 条要的是「上栏 / 下栏」而不是自由 y）。
 *
 * [deltaYDp] 是相对下栏的竖直位移（屏幕坐标，向上为负）：往上过半程去上栏，其余一律回下栏。
 * 上档取的是 [dockUpperY]，它本身已经被安全区顶边钳过，所以拖出上边界也只会贴到能放的最上面一格。
 */
fun dockSnapY(area: HudAreaDp, heightDp: Int, bottomPadDp: Int, gapDp: Int, deltaYDp: Int): Int {
    val lower = dockLowerY(area, heightDp, bottomPadDp)
    val upper = dockUpperY(area, heightDp, bottomPadDp, gapDp)
    val pitch = lower - upper
    return if (pitch > 0 && -deltaYDp.toFloat() >= pitch / 2f) upper else lower
}

/** px → dp 的整数换算（编辑页与录制页共写位置时用，避免出现第二套换算式） */
fun pxToDp(px: Float, density: Float): Int =
    if (density <= 0f) 0 else (px / density).roundToInt()

/**
 * 右竖 Dock 的下界要让开「读数块所在的那一横带」，但**不许低于底栏那一排自己的下界**。
 *
 * #70 A 之前这条算式是 `bottomAvoidDp + readoutHeightDp`：那时读数块浮在底栏**上方**，两截让位是串联的，
 * 加在一起才对。现在两块共用底栏那一行（同一基线），是**并联**关系 ⇒ 取大而不是求和，
 * 否则右 Dock 白白多让一整排（横屏实测会多让 66dp，把最高的那颗条目挤进滚动区）。
 *
 * - [readoutBottomDp]：读数块**容器**自己的底边让位（就是 [ReadoutRowPlan.bottomAvoidDp]）。与底栏同基线时
 *   它是 `基线 − 块内边距`（本机 6 − 6 = 0dp，块比那一排矮 ⇒ 这条缝整个收回去）；
 *   窄窗退化到"让到整排之上"时它本身就是底栏带高，此时 `readoutBottomDp + readoutHeightDp`
 *   又回到旧的串联算式，**退化路径一条没丢**。
 * - 读数块空了回报 0，这一截缝自己收回去（与改前同一条语义）。坏值（负高）按 0 处理。
 */
fun areaForRightDock(area: HudAreaDp, readoutHeightDp: Int, readoutBottomDp: Int): HudAreaDp =
    area.copy(
        bottomAvoidDp = maxOf(area.bottomAvoidDp, readoutBottomDp + readoutHeightDp.coerceAtLeast(0))
    )

// ------------------------------------------------------------------ #70 A：读数块与底栏那一行

/** 用户 2026-09-29 12:20 定版的列数上限：横屏「读数缩到一行两颗」，不是按剩余宽度算到 3 */
const val DockRowPerRowCap = 2

/**
 * 「这一帧有横行可言」的下限：右半带至少排得下 [MinColumnsForSharedRow] 颗才算同行（只剩一颗就是竖列，
 * 不构成横行）。与 [DockRowPerRowCap] 同为 2 但**不是同一个数**：那条是上限（少列的目标），这条是门槛
 * （够不够格同行）；竖屏退化档的判据读这条，横屏的列数读那条。
 */
private const val MinColumnsForSharedRow = 2

/**
 * 读数块与底栏 Dock 之间那一条**底部横带**的关系，一次算清（任务 #70 A 的唯一裁决点）。
 * 两个底边档位都由 [planReadoutRow] 的入参喂进来：`dockRowBaselineDp`（与底栏共用那条基线那一档）与
 * `dockStripDp`（底栏那一排的带高，退化时用）。
 *
 * ## 定版口径（用户 2026-09-29 12:20：「读数缩到一行两颗，横屏永远同行」）
 * - **横屏没有"退回 Dock 上方"这一档**：[sharesDockRow] 恒真，列数上限 [DockRowPerRowCap]。
 *   横向装不下的解法是让读数**变少列**（两颗→一列），而不是放弃同行。
 * - **只有竖屏允许退回**：底板右缘那条带连两颗都排不下时，整块让到底栏那一排之上（旧落位）。
 *
 * - [sharesDockRow]：这一帧到底同不同行。**它是裁决本身的露出点**（用例与调试读它），UI 侧只消费
 *   [bottomAvoidDp] 与 [perRow]——照 [com.wotagei.cam.ui.anim.MergePlan.durationMs] 的规矩如实写在这里。
 * - [bottomAvoidDp]：喂给读数块那枚容器（`HudAreaDp.bottomAvoidDp` → `padding(bottom=)`）的**容器**让位。
 *   两档：同基线 = `dockRowBaselineDp − HudBlockPadDp`（见下面那笔 6dp 账）；退化 = `dockStripDp`。
 * - [perRow]：一行几颗（横屏再经 [DockRowPerRowCap] 夹一次）。
 * - [roomWidthDp]：这一帧真正可用的**块内宽**（已经扣掉设计留白与块自身内边距）。
 *
 * ## 第 6 条那笔 6dp：同基线档为什么要减一枚 [HudBlockPadDp]
 * 底栏那枚容器 `padding(bottom = BottomBarOuterPadV)`（6dp），量的就是**底板可见底边**到安全区底；
 * 读数块那枚容器除了自己的让位，内部还吃了 `HudReadoutZone` 的 `.padding(HudBlockPadDp)`（6dp）⇒
 * 两枚容器若用同一个 6dp，胶囊的可见底边就停在 12dp 高，比底板底边**高出一整枚内边距**——
 * 那就是"浮在 Dock 上方"，只是从 66dp 缩成了 6dp。所以同基线档返回 `基线 − 块内边距`（本机 0dp，
 * 只用现有令牌相减，不新造散值），让**胶囊可见底边**与**底板可见底边**落在同一条线上。
 * 夹到 ≥0：万一基线令牌改得比块内边距还小，宁可差几 dp 也不把块画出安全区底。
 * 退化档不减：那一档的语义本来就是"整排之上"，容器底边压在带顶上，留缝是设计不是缺陷。
 *
 * ## 竖屏为什么必须有"退化"这一档，以及它的判据为什么长这样
 * 底栏在可视窗口里**水平居中**（`clampZonePos` 把它的 x 恒抹成哨兵，见本文件第 8 条那一段），
 * 所以它与读数块争的是同一段右半区：底栏右缘 = `安全区宽/2 + 底板宽/2`。
 * 竖屏 360dp 上底板 216dp ⇒ 那条带只剩 56dp（180 − 108 − 8 间距 − 8 留白），
 * **连一颗读数胶囊（估宽 90dp）都放不下**。那种时候只有两条路：压住底栏那颗镜头胶囊（等于挡住入口，
 * 不可接受），或让到整排之上（就是旧行为，而且此时两枚容器**几何上真会重叠**——恰好落在"让位量只许
 * 用在几何上真会重叠的那一对"这条规矩里）。横屏没有这个问题，所以横屏不再有这一档。
 *
 * 判据取 `hudPerRowFor(…, 右半可用宽) ≥ [MinColumnsForSharedRow]`，不是"能不能装下一颗"：
 * - `hudPerRowFor` 只在「两颗 + 行距 + 安全余量 24dp ≤ 可用宽」时才给 2 以上，
 *   所以选了这一档之后**块宽必然 ≤ 可用宽 − 24dp**，同一行不会撞底栏，这是它自带的算术保证；
 * - 给到 1 就意味着那条右半带连"两颗 + 余量"都容不下，竖屏此时退回整排之上并用**整幅宽**取档
 *   （回到改前的 3 颗一行，不产生新的裁字面）。
 */
data class ReadoutRowPlan(
    val sharesDockRow: Boolean,
    val bottomAvoidDp: Int,
    val perRow: Int,
    val roomWidthDp: Float
)

/**
 * 与底栏同一行时**整块读数**（含它自己的内边距）允许占的那一段宽度：底栏右缘 + 一枚间距 → 安全区右缘 − 设计留白。
 *
 * 与 [readoutRoomBesideDockDp] 是同一笔账的两个口径：那条给 `hudPerRowFor` 用（块**内**可用宽，
 * 已再扣掉左右各 [HudBlockPadDp]），这条给"实测块宽 ≤ 容得下吗"用。
 * 两处共用同一个表达式，不许各写一份。
 */
fun readoutStripWidthDp(safeWidthDp: Int, dockWidthDp: Int, endPadDp: Float, dockGapDp: Float): Float =
    (safeWidthDp / 2f - dockWidthDp / 2f - dockGapDp - endPadDp).coerceAtLeast(0f)

/**
 * 与底栏同一行时读数块的可用内宽 = 「底栏右缘 + 一条设计间距」到「安全区右缘 − 设计留白」这一段，
 * 再扣掉块自身左右内边距各一份（走 [hudRoomDp]，与整幅取档那条同一个表达式、同一批真源）。
 *
 * 推导：底栏居中 ⇒ 右缘在 `safeWidthDp/2 + dockWidthDp/2`；读数块右缘钉在 `safeWidthDp − endPadDp`；
 * 两者之间再留一枚 [dockGapDp]（调用方传令牌 `WotaSpace.s`）。
 * 所以 `hudRoomDp(safeWidthDp/2 − dockWidthDp/2 − dockGapDp, endPadDp)` 就是这个数（[hudRoomDp] 里
 * 还会再扣 `2 × HudBlockPadDp`，那正是块内左右各一份内边距）。
 * 极窄时整条被 [hudRoomDp] 夹到 0，不会给负数。
 */
fun readoutRoomBesideDockDp(safeWidthDp: Int, dockWidthDp: Int, endPadDp: Float, dockGapDp: Float): Float =
    hudRoomDp(safeWidthDp / 2f - dockWidthDp / 2f - dockGapDp, endPadDp)

/**
 * [ReadoutRowPlan] 的算式本体。两页（录制页 / 编辑页）必须调这一条，不许各写一份判据（S3-5 同源）。
 *
 * 两个形参**没有默认值**，都是承重的（#69 那条铁律：该必传的形参给了默认值，漏挂就静默失去安全网）：
 * - [landscape]：定版那条"横屏永远同行"由调用方从 `Configuration.orientation` 送进来（两页本来就在用它
 *   复位 `safeW`/`safeH`，同一个真源，不另造第二套横屏判据）；
 * - [readoutWidthDp]：实测块宽。给默认值 0 等于"永远放行"，新调用点漏传就等于把竖屏那道安全网拆了。
 *
 * @param dockRowBaselineDp 两枚容器共用的那条基线（= 底板可见底边的高度，调用方传 `BottomBarOuterPadV`）
 */
fun planReadoutRow(
    readoutCount: Int,
    fontScale: Float,
    safeWidthDp: Int,
    dockWidthDp: Int,
    dockStripDp: Int,
    dockRowBaselineDp: Int,
    endPadDp: Float,
    dockGapDp: Float,
    landscape: Boolean,
    readoutWidthDp: Int
): ReadoutRowPlan {
    val besideRoom = readoutRoomBesideDockDp(safeWidthDp, dockWidthDp, endPadDp, dockGapDp)
    val perRowBeside = hudPerRowFor(readoutCount, fontScale, besideRoom)
    // 同基线档的容器让位：基线减掉块自己那枚内边距，胶囊可见底边才与底板可见底边齐平（第 6 条那笔账）
    val dockRowBottomDp = (dockRowBaselineDp - HudBlockPadDp.roundToInt()).coerceAtLeast(0)
    if (landscape) {
        // 横屏（定版）：恒同行、列数夹到 [DockRowPerRowCap]。这一支**不读** [readoutWidthDp]——
        // 拿实测块宽去改横屏列数会形成 `列数→块宽→列数` 的每帧振荡（2 颗嫌宽退 1 颗，1 颗又"装得下"
        // 于是回到 2 颗），而那正是要防的那个环。横屏既然不许退，窄的办法就只剩少列。
        return ReadoutRowPlan(true, dockRowBottomDp, minOf(DockRowPerRowCap, perRowBeside), besideRoom)
    }
    val fullRoom = hudRoomDp(safeWidthDp.toFloat(), endPadDp)
    val perRowFull = hudPerRowFor(readoutCount, fontScale, fullRoom)
    // 安全网：`hudPerRowFor` 的字宽是**算术估计**（汉字 1 em、拉丁 0.6 em，见其 KDoc），
    // 「感光度 AUTO」「曝光补偿 +1.0」这类副标签比它假设的 2 汉字宽，估宽会偏小 ⇒ 光靠估宽判断可能真压上底板。
    // 所以再用**实测块宽**兜一道：量到且已经越过那一段宽度，就退回整排之上（量不到那一帧 0 = 先按估宽走，
    // 下一帧实测接管，与 topBarH / dockStripH 同一套"两轮收敛"手法）。
    val stripWidth = readoutStripWidthDp(safeWidthDp, dockWidthDp, endPadDp, dockGapDp)
    val measuredFitsBeside = readoutWidthDp <= 0 || readoutWidthDp <= stripWidth
    if (perRowBeside >= MinColumnsForSharedRow && measuredFitsBeside) {
        return ReadoutRowPlan(true, dockRowBottomDp, perRowBeside, besideRoom)
    }
    // 退化档的列数必须对**本帧这次决策不敏感**，否则 `块宽→档位` 会把自己闩死（#70 修复批次第 1 条，S1）：
    // 实测块宽是上一帧按当前档位排出来的布局产物，退化档若照样取整幅宽（3 颗）就永远比阈值宽 ⇒ 判据
    // 永久 false（旧注释把这写成"稳在 false"的优点，判反了：那是卡死，不是稳定）。
    // 夹成 min(perRowBeside, perRowFull) 之后：陈旧宽 → 下一帧按 2 颗重排 → 实测回到阈值以内 → 自动 true；
    // 而"估宽说谎、2 颗真装不下"时块保持 2 颗宽度、稳定 false ⇒ 防振荡的收益一条不丢。
    val perRow = if (perRowBeside >= MinColumnsForSharedRow) minOf(perRowBeside, perRowFull) else perRowFull
    return ReadoutRowPlan(false, dockStripDp, perRow, fullRoom)
}

/**
 * 第 8 条进入拖拽的 gate（13 号计划：正在录的时候不能让人把底栏搬走）。
 *
 * PREPARE / START / STOPPING 三态一律拒绝——「停止录制」是取景页最高优先级的手势，而拖拽会把
 * 那一指的触摸盒整枚搬走；ERROR 不算忙（Recorder 那一侧已经复位，此时搬底栏不影响停录）。
 */
fun dockDragBlocked(status: com.wotagei.cam.camera.RecordStatus): Boolean =
    status == com.wotagei.cam.camera.RecordStatus.PREPARE ||
        status == com.wotagei.cam.camera.RecordStatus.START ||
        status == com.wotagei.cam.camera.RecordStatus.STOPPING

// ------------------------------------------------------------------ 容器内的条目渲染分组

/**
 * 把一枚容器的可见条目切成「一行一行」，五枚容器共用这一条算式（B1–B3 的高度账就靠它）。
 *
 * - [HudZone.TOP] / [HudZone.BOTTOM]：横排一行；
 * - [HudZone.LEFT]：一行一颗；
 * - [HudZone.RIGHT]：**S2-2 B 那条并排规则保留**——姿态仪与音量表在顺序里相邻时并成一行两列
 *   （省下 ≈75dp，正好把变焦/对焦从折叠线下捞回来）。顺序被用户拆开就不再并排，
 *   这是"用户可以重排"与"横屏 360dp 带高不够"两条要求唯一能同时成立的写法；
 * - [HudZone.READOUT]：按 [perRow] 分行（[hudPerRowFor] 按可用宽与字体缩放取档，横屏那一路还要过
 *   [DockRowPerRowCap] 这道上限）。
 */
fun hudRowGroups(zone: HudZone, order: List<HudEntry>, perRow: Int): List<List<HudEntry>> = when (zone) {
    HudZone.TOP, HudZone.BOTTOM -> if (order.isEmpty()) emptyList() else listOf(order)
    HudZone.LEFT -> order.map { listOf(it) }
    HudZone.RIGHT -> {
        val groups = mutableListOf<List<HudEntry>>()
        val level = HudEntry.of(CamPill.LEVEL)
        val volume = HudEntry.of(CamPill.VOLUME)
        var i = 0
        while (i < order.size) {
            val next = order.getOrNull(i + 1)
            if (order[i] == level && next == volume) {
                groups += listOf(level, volume)
                i += 2
            } else if (order[i] == volume && next == level) {
                groups += listOf(volume, level)
                i += 2
            } else {
                groups += listOf(order[i])
                i++
            }
        }
        groups
    }
    HudZone.READOUT -> order.chunked(if (perRow < 1) 1 else perRow)
}

/**
 * 右竖 Dock 的「并排」判据（单独暴露给用例）：姿态仪与音量表**相邻**才并排。
 * 用户把音量表挪到别处、或把它关掉了（[order] 里没有它），都必须退回一行一颗。
 */
fun rightDockPacksLevelAndVolume(order: List<HudEntry>): Boolean {
    val level = order.indexOf(HudEntry.of(CamPill.LEVEL))
    val volume = order.indexOf(HudEntry.of(CamPill.VOLUME))
    return level >= 0 && volume >= 0 && kotlin.math.abs(level - volume) == 1
}
