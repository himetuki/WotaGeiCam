package com.wotagei.cam.ui

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import kotlin.math.abs
import kotlin.math.ceil
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
 * 格子是**位置单位**而不是"条目单位"：一枚格子可以住同属一个默认行组的多颗（今天只有右 Dock 那对
 * S2-2B 并排「姿态仪 + 音量表」），跨组共格一律算坏数据。这一条是 #74 的后果修复——上一批把
 * "均匀 pitch" 的 pitch 定成"最宽那颗"，于是那对并排的第二列也按姿态仪 54dp 撑，右 Dock 底板从
 * ≈94dp 涨到 ≈120dp，与用户这轮" Dock 还是太宽"的诉求正好相反（算式与三条收益见 [defaultCellsOf]）。
 *
 * ## 行距是**固定格距**，高的条目吃**行跨度**（任务 #75）
 * 另一半同族缺陷在**纵向**：#74 把行距定成"容器里最高那颗 + 一道行距"，于是右 Dock 那枚 ≈72dp 的
 * 姿态仪自绘件把行距顶到 ≈78dp，而它旁边四颗胶囊只有 30dp ⇒ 5 枚格子 ≈386dp，横屏带高只有 244dp，
 * **溢出 142dp（1.8 行）**，对焦与防抖整颗掉到折叠线以下——改前那是 `Column` 的自然行高 ≈210dp，
 * 装得进（那正是 S2-2B 当年把姿态仪与音量表并排的理由），均匀行距把这笔账抵消了。
 * 现在纵向恒等于 [gridRowPitchPx]（胶囊档 + 一道行距 ≈34dp，与住户无关），
 * 高的条目按 [cellRowSpan] 吃掉 `row .. row+span−1` 几档：占用、钳制、就近让位、默认推导四侧
 * 都按整块算（[blockingCells] / [clampCellToBox] / [freeCellNear] / [defaultCellsOf]），
 * 而**每颗的 row 仍是它自己那格的起始档**——跨度只决定"哪些档算被占"和"默认推导往哪推进"，
 * 永远不用来重排已经摆过的条目（独立性是 #74 的验收口径，一条没让）。
 * 跨度**不进 schema**：[GridCell] 仍然只有 (col, row)，v2 编解码与真机验过的迁移一字未动，
 * 理由与"实测高必须由调用方喂进来、形参不给默认值"都写在 [GridCell] 与 [HudGridPlan] 的注释里。
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
 * - [HudZone.TOP] 的宽度账与容量段三档取位（`capacityTierText` 三档判据，10-01 起由调用方
 *   TextMeasurer 实测两段文案宽传入，旧 272/242 常量已删；§59/§61/§74 的档位语义不变）
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
 *
 * ## 一枚格子可以住**同组的多颗**（#74 后果修复，见 [defaultCellsOf] 的三条收益）
 * 格子是"位置单位"而不是"条目单位"：[HudZone.RIGHT] 那对 S2-2B 并排（姿态仪 + 音量表）
 * 由 [hudRowGroups] 判成一个行组，行组 = **一枚格子**，两颗共用同一个 (col, row)。
 * 于是右 Dock 是**单列**网格、列宽由那枚配对格撑出来（86dp），底板回到 r11 实测的 ≈94dp。
 * 共格只允许发生在**同一枚默认组**内部（判据仍是 [defaultCellsOf] 的输出，没有第二份配对尺子）；
 * 跨组撞进同一格一律算坏数据，由 [resolveCells] 就近拆开。
 *
 * ## 格子**没有 rowSpan 字段**（任务 #75）
 * 高条目（右 Dock 那枚姿态仪自绘件 ≈72dp）吃掉几档行距，是由**实测内容高 ÷ 格距现算**出来的
 * （[cellRowSpan]），不是存进来的第 3 个坐标。理由三条，一条比一条硬：
 * 1. `GridCell(col, row)` 一动，v2 编码、解码、v1→v2 迁移、`encode/decode` 幂等那一组用例全要重做，
 *    而持久化已经由真机跨进程探针证过（docs/plan/13 §15.8）——那一层是最不该动的；
 * 2. 跨度是内容的**函数**：字体拉到 120%、文案变长、这颗被挪去别的格子，跨度都跟着变。
 *    存下来就是再造一份会与真源分叉的坐标（AGENTS 那条"手抄的默认值会和实现分叉"同一族）；
 * 3. 存了就要管迁移与钳制，而跨度**不该**由用户摆——用户摆的是格子，高矮是内容说了算。
 * 代价照实写：跨度要由调用方（渲染层与编辑页都量得到实测高）喂进来，那些形参**一律没有默认值**
 * （#69 铁律：给了默认值漏挂的那一处就静默退回"跨度恒为 1"，正是本次要修的形态）。
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
     * 两趟解析，规则只有一条：**一枚格子只许住"同一个默认组"的颗**（今天只有 S2-2B 那对
     * 「姿态仪 + 音量表」，见 [cellGroupMatesOf]）——跨组撞进同一格就是又造出"移动一颗会压到另一颗"，
     * 由 [resolveCells] 把后到的那颗就近挪到空格：
     * 1. 摆过的条目先占自己的显式格子；
     * 2. 没摆过的条目按 [defaultCellsOf]（对**可见**顺序分行）取推导格，**同组的颗整组一起落位**，
     *    被占了就在容器内就近让到空格。
     * 默认表里没有任何显式格子 ⇒ 第 2 趟直接命中推导格 ⇒ 与今天的排布逐格相同（这是"默认表结构性等于
     * 今天"这条硬要求的落点，也是 v1 存量串免费迁移的原因）。
     */
    fun gridItems(zone: HudZone, plan: HudGridPlan): List<HudGridItem> {
        if (!zone.isGrid) return visibleOrderOf(zone, plan.visible).map { HudGridItem(it, GridCell.DEFAULT) }
        return resolveCells(
            order = visibleOrderOf(zone, plan.visible),
            placed = placedCellsOf(zone),
            derived = defaultCellsOf(zone, visibleOrderOf(zone, plan.visible), plan),
            cols = gridColsCapOf(zone),
            rows = GridRowHardCap,
            cellHeightPx = plan.cellHeightOf,
            rowPitchPx = plan.rowPitchOf(zone)
        )
    }

    /**
     * 这一枚容器**默认表**的「条目 → 格子」（任务 #80：锚定与预留的唯一来源）。
     *
     * 与 [gridItems] 的区别就一件事：它**不看用户摆过的格子**，只按可见集推导。
     * 于是格长、预留档数、生长方向三样基准量全都成了"可见集的函数"，
     * 拖动任何一颗都动不了它们 ⇒ 「每颗的屏幕坐标 = f(它自己的 col,row) + 固定原点 + 固定格长」成立。
     * 两页（录制页 [com.wotagei.cam.ui.HudEntryGrid] 与编辑页的钳制带）必须共读这一条，不许各推一遍。
     */
    fun defaultGridOf(zone: HudZone, plan: HudGridPlan): Map<HudEntry, GridCell> =
        if (!zone.isGrid) emptyMap() else defaultCellsOf(zone, visibleOrderOf(zone, plan.visible), plan)

    /**
     * 该容器**当前挡得住一颗**的格子（可见条目按 [gridItems] 解析结果，隐藏条目按表里的显式格子）。
     *
     * 隐藏条目也算占用：它只是这帧不画，格子还是它的位置（"隐藏再显示不回默认位置"那套语义）。
     * 不把它算进来的话，用户往那颗的格子上放别的一条，它再显示时就会与别人挤在同一格。
     *
     * ## "被占了"的口径（#74 后果修复：一格多颗之后不再是"格子里有人就叫占"）
     * 一格只剩**与被拖那颗不同组**的住户时才算挡路。同组的颗（那对并排）不算 ⇒
     * 把姿态仪拖回音量表所在的那一格是**重新并排**，不是撞车；不这样判的话配对一旦被拆开就再也合不回去
     * （[placeEntryAt] 第一步就把两颗都钉成显式格，那一格永远"有人"，就近让位会把这颗甩到第 1 列，
     * 白白多撑一列宽）。跨组那颗照样挡路——用户拖不出"两颗叠在一格"这种形态。
     *
     * ## #75：返回的是**档**不是"格"
     * 高格（跨度 > 1）连它压住的 `row+1 .. row+span−1` 一起算占，所以编辑页与 [resolveCells] 拿到的
     * 是"这些档落下去会叠字"的完整清单。少了这一笔，一颗 72dp 的姿态仪只钉住自己那一档，
     * 第二、三档还能落别颗 ⇒ 两颗画在同一段像素上（[blockingCells] 那条"搭档只许落回起始档"的口径
     * 也是在这里生效的）。
     */
    fun occupiedCells(zone: HudZone, plan: HudGridPlan, exclude: HudEntry? = null): Set<GridCell> {
        if (!zone.isGrid) return emptySet()
        val visible = plan.visible
        return blockingCells(
            residentsOf(zone, plan),
            self = exclude,
            mates = if (exclude == null) emptySet() else cellGroupMatesOf(
                defaultCellsOf(zone, visibleOrderOf(zone, visible), plan), exclude
            ),
            cellHeightPx = plan.cellHeightOf,
            rowPitchPx = plan.rowPitchOf(zone)
        )
    }

    /**
     * 这一枚容器**这一帧的住户表**：锚点格 → 住在那里的条目（可见的那份来自渲染解析结果，
     * 隐藏的那份来自表里的显式格）。[occupiedCells] 的"挡路集"与 [placeEntryAt] 的"同格交换"
     * **共读这一份**（不许各建一遍：两张表一旦分叉，就会出现"预览说这格有人、写表说没人"）。
     *
     * 声明成 `internal` 而不是 `private`，是因为编辑页预览（`HudLayoutEditor.snapOf`）也要把同一张表
     * 喂进 [cellDropOf] 才能与写表读同一条裁决——把住户表藏进表内而让预览自己造一份，正是要防的分叉。
     */
    internal fun residentsOf(zone: HudZone, plan: HudGridPlan): Map<GridCell, List<HudEntry>> {
        if (!zone.isGrid) return emptyMap()
        val visible = plan.visible
        // 逐格累计住户：可见的那份来自渲染解析结果，隐藏的那份来自表里的显式格
        val residents = LinkedHashMap<GridCell, MutableList<HudEntry>>()
        gridItems(zone, plan).forEach { residents.getOrPut(it.cell) { mutableListOf() } += it.entry }
        placedCellsOf(zone).forEach { (entry, cell) ->
            if (entry !in visible) residents.getOrPut(cell) { mutableListOf() } += entry
        }
        return residents
    }

    /**
     * 把 [zones] 里这些网格容器的**推导格固化成显式格子**（只写格子，不改任何人的位置）。
     *
     * 这是"移动一颗不动另一颗"的实现核心：[order] 一旦被增删，还挂着哨兵的条目会按**新的**可见顺序
     * 重新推导 ⇒ 集体错位。所以任何改动 [order] 的操作之前，先把受牵连的那两枚容器（来源与目标）
     * 的当前格子原样钉住；钉过之后 [order] 就再也决定不了任何一颗的实际位置。
     *
     * 钉的是**格子**不是"每颗各一格"：配对那两颗钉完仍然共用同一枚格子（[gridItems] 解析出的就是同一格），
     * 所以这一步不会把 S2-2B 的并排拆开，也不会因为拆开而多撑出一列宽。
     *
     * #75：钉进来的也只有 (col, row) 这一对**起始档**，跨度**不进表**（[GridCell] 没有第三个坐标）——
     * 所以"钉过之后行距改了"或"这颗换了文案变矮了"都不需要迁移任何东西：跨度下一帧按实测重算。
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
     *    再定落点（[cellDropOf]）：
     *    · **同容器**（`target == source`）走交换语义——目标格住了"别人"就与它对调，被挤的那组去
     *      "你的原格"（[pinnedTable] 里那颗当前的格子），原格承接不了才就近让位；
     *    · **跨容器**仍走 [freeCellNear]（只落空格）：跨容器没有"你的原格"可退回，硬用交换语义会写出
     *      一枚只在本容器带内有意义的越区格。
     *    ⚠ 钳制与让位吃的都是**格子**这一层，不是条目那一层：同组两颗共用一格 ⇒ 越界钳回来仍是同一格，
     *    不会出现"配对格被钳进两格"这种顺手把配对拆开、连带撑出一列的写法（[clampCellToBox] 是格→格的函数）。
     *    ⚠ #75：这里的"空格"是**档**——[occupiedCells] / [cellDropOf] 已经把高格连它压住的几档一起算，
     *    [freeCellNear] 再拒掉"整块出带"的候选（吃 [droppedCellHeightPx] 与 `plan.rowPitchOf(target)`）。
     *    注意这一步**只挑这一颗与"被它挤开的那一组"的落点**：别人的格子在第 1 步就钉死了，
     *    交换也改动不了任何没被点名的条目。
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
        val drop: CellDrop = when {
            // 非网格容器：格子对它不生效，一律抹成哨兵（[normalize] 那一道同样会抹）
            !target.isGrid -> CellDrop(GridCell.DEFAULT, emptyMap())
            // 显式传哨兵 = "这次只换顺序，位置仍交给推导"（[moveEntryTo] 走的就是这一支）。
            // 与第 1 步的钉格配套：别人的格子已经钉死，所以这里"交给推导"不会牵动任何人。
            cell.isDefault -> CellDrop(GridCell.DEFAULT, emptyMap())
            // 同容器 ⇒ 交换：origin 是这一颗**当前**的格子（钉过之后必定是显式格）；
            // 它此刻还没被写进新表，所以 gridItems 读到的仍是原位
            target == source -> {
                val origin = pinnedTable.gridItems(target, plan).firstOrNull { it.entry == entry }?.cell
                    ?: GridCell.DEFAULT
                cellDropOf(
                    wanted = clampStoredCell(target, cell),
                    origin = origin,
                    residents = pinnedTable.residentsOf(target, plan),
                    self = entry,
                    mates = cellGroupMatesOf(pinnedTable.defaultGridOf(target, plan), entry),
                    // #75：这一颗要占几档由**它自己的实测高**说了算；合回配对那一格时按搭档的高取大
                    //（两块合成一块，跨度是整块的高而不是被拖那颗的高——按小的算会把搭档的尾巴挤出块外）
                    draggedHeightPx = droppedCellHeightPx(plan, entry, target),
                    cellHeightPx = plan.cellHeightOf,
                    cols = gridColsCapOf(target),
                    rows = GridRowHardCap,
                    rowPitchPx = plan.rowPitchOf(target)
                )
            }
            // 跨容器 ⇒ 维持"只落空格"：没有"你的原格"这一说，不许写出越区格
            else -> CellDrop(
                freeCellNear(
                    wanted = clampStoredCell(target, cell),
                    occupied = pinnedTable.occupiedCells(target, plan, exclude = entry),
                    cols = gridColsCapOf(target),
                    rows = GridRowHardCap,
                    contentHeightPx = droppedCellHeightPx(plan, entry, target),
                    rowPitchPx = plan.rowPitchOf(target)
                ),
                emptyMap()
            )
        }
        val nextZones = HudZone.ALL.associateWith { z ->
            val state = pinnedTable.zones[z] ?: ZoneState(ZonePlacement.DEFAULT, defaultOrderOf(z), emptyMap())
            val cells = if (z == target) {
                // 被拖那颗 → drop.dropped；被挤出的那组 → drop.moved，一并写进同一枚容器（都是显式格）
                if (drop.dropped.isDefault) state.cells - entry
                else state.cells + (entry to drop.dropped) + drop.moved
            } else {
                state.cells - entry
            }
            state.copy(order = orders.getValue(z).toList(), cells = cells)
        }
        return HudLayoutTable(version, nextZones).normalize()
    }

    /**
     * 被拖那颗落到目标容器之后**这一格的内容高**（px）：取"它自己"与"它同组搭档"里最高的那个。
     *
     * 为什么不是只取它自己：合回配对那一格时两颗共用一块，跨度是**整块**的高（姿态仪 ≈72dp）而不是
     * 被拖那颗音量表的高（≈71dp）；按小的算跨度会少一档，渲染层按实测排 → 那颗的尾巴伸出块外、
     * 与下一档的胶囊叠字。这一条只在 [freeCellNear] 的"块尾不许出带"判据里用一次，
     * 不参与任何人的位置推导（**跨度不许反过来重排已经摆过的条目**，那是 #74 立的独立性）。
     */
    private fun droppedCellHeightPx(plan: HudGridPlan, entry: HudEntry, zone: HudZone): Int {
        val own = plan.cellHeightOf(entry).coerceAtLeast(0)
        val mates = cellGroupMatesOf(defaultCellsOf(zone, visibleOrderOf(zone, plan.visible), plan), entry)
        return maxOf(own, mates.maxOfOrNull { plan.cellHeightOf(it).coerceAtLeast(0) } ?: 0)
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
     * **一格多颗不需要第二种写法**：`tok:col.row` 是逐颗写的，配对那两颗同格就是两个相同的后缀
     * （右 Dock 默认钉过之后是 `P0:0.0,P1:0.0`），schema 版本、键名、段结构一个都不动。
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

    /**
     * 与另一枚矩形的交叠判据（**唯一一处**，[overlappingZones] 与 #79 那条"顶栏有没有压在操作栏带里"
     * 共读这一条）。边界相接（`a.right == b.left`）算**不**交叠：那两块像素没有重叠。
     */
    fun intersects(other: HudRectDp): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
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
 *
 * ## [topZoneMinYDp] 是只有 [HudZone.TOP] 读的绝对定位支下限（本批补的那一格）
 * 与 [com.wotagei.cam.ui.HudLayer.HudZoneBox] 的 `nativeTopMinDp` 完全同构：那条管"原生对齐支"，
 * 这条给"绝对定位支"（用户把顶栏整枚拖过）立同一条下限。没有它时顶栏一旦被拖到编辑页那条操作栏
 * 的矩形里，整枚容器既点不到也拖不动（操作栏在拖拽捕获层之上）。
 *
 * ⚠ **录制页恒传 0**：录制页构造的 [HudAreaDp] 里 `topAvoidDp = topBarH`（实测顶栏高，不是 0），
 * 若让它吃 `topAvoidDp`，同一份位置表会在编辑页与录制页渲染出不同位置（编辑页画的是假的）。
 * 所以下限不取 area 里的任何字段，而是由调用方显式喂一个"这一帧量到的操作栏下缘"——
 * 编辑页喂 [chromeBandBottomDp] 的产物，其余调用点（含录制页）一律传 0 ⇒ 逐字退回改前。
 */
fun clampZoneYRange(zone: HudZone, area: HudAreaDp, heightDp: Int, topZoneMinYDp: Int): IntRange {
    val band = zone != HudZone.TOP && zone != HudZone.BOTTOM
    val lo = when {
        band -> area.topAvoidDp.coerceAtLeast(0)
        zone == HudZone.TOP -> topZoneMinYDp.coerceAtLeast(0)
        else -> 0
    }
    val hi = if (band) area.height - area.bottomAvoidDp - heightDp else area.height - heightDp
    // 带高不够（告警条 + 满配读数把带挤没了，或容器本身比屏还高）时退成整条安全区，
    // 区间不许为负 —— `coerceIn` 遇到 lo > hi 是直接抛的，那一抛整个 HUD 就没了
    return if (hi < lo) 0..(area.height - heightDp).coerceAtLeast(0) else lo..hi
}

/**
 * 一枚容器的**可放带**纵向区间（dp，安全区局部坐标，含端点；任务 #80 的归属裁决读它）。
 *
 * 与 [clampZoneYRange] 的差别只有一处：那条算的是"容器左上角最矮能放到哪"（要再减一枚容器高），
 * 这条算的是"这一条带本身吃到哪"（指针在不在带里）。两者共用同一批实测入参，
 * 分叉不了——所以 #80 里"拖过头"与"真要跨容器"判的是同一条带。
 * 带高不够（告警条把带挤没）时退成整条安全区，与 [clampZoneYRange] 同一条兜底。
 *
 * [topZoneMinYDp] 与 [clampZoneYRange] 同步加、TOP 的 lo 取**同一个值**：两者是"拖过头"与
 * "真要跨容器"判同一条带的一对，分叉了就会出现"看着还在顶栏带里、松手却换了容器"。
 */
fun zoneBandYRange(zone: HudZone, area: HudAreaDp, topZoneMinYDp: Int): IntRange {
    val band = zone != HudZone.TOP && zone != HudZone.BOTTOM
    val lo = when {
        band -> area.topAvoidDp.coerceAtLeast(0)
        zone == HudZone.TOP -> topZoneMinYDp.coerceAtLeast(0)
        else -> 0
    }
    val hi = (if (band) area.height - area.bottomAvoidDp else area.height).coerceAtLeast(lo)
    return lo..hi
}

/**
 * 绝对位置钳进安全区（尺寸未测到时传 0，等价于「只保证左上角不越界」）。
 *
 * **底栏的 x 在这里就被抹成哨兵**：它只能上下搬（第 8 条的上栏/下栏），写入方就算把实测左缘换算成
 * 绝对 x 送进来也存不进表——那会让快门从此不再跟随可视中心（90↔270 翻转、横竖换档都不重新居中，
 * B4 审查 S1 那条）。[HudLayoutTable.normalizedPosOf] 在读表时同样抹一次，把存量脏数据一起治掉。
 *
 * [topZoneMinYDp] 透传给 [clampZoneYRange]（只有 TOP 的绝对定位支读它）；录制页与其余调用点传 0
 * ⇒ 逐字退回改前那一档，同一份位置表在两页渲染出同一个位置。
 */
fun clampZonePos(
    zone: HudZone,
    xDp: Int,
    yDp: Int,
    wDp: Int,
    hDp: Int,
    area: HudAreaDp,
    topZoneMinYDp: Int
): ZonePlacement {
    val yRange = clampZoneYRange(zone, area, hDp, topZoneMinYDp)
    val x = if (zone == HudZone.BOTTOM) -1 else xDp.coerceIn(0, clampZoneX(area, wDp))
    return ZonePlacement(x, yDp.coerceIn(yRange.first, yRange.last))
}

/**
 * 「编辑控件」页那条**操作栏**在安全区局部坐标里压到多低（dp，任务 #79 的唯一算式）。
 *
 * 为什么要单独一条纯函数：这页整页只有一层拖拽捕获层，操作栏那三颗胶囊（返回/保存/恢复默认）
 * 必须是**根 Box 的最后一个子节点**才收得到点按（排在捕获层之前时每一指都被拖拽层吃掉，一颗都点不动）。
 * 挪到最上之后反过来压住了顶栏那两枚段——它们原生对齐只让一枚 [com.wotagei.cam.ui.TopTopPad]（4dp），
 * 正好落在操作栏的矩形里，于是**点不到也拖不动**（缺陷换了个方向）。所以顶栏原生对齐那一支的下限
 * 必须由这条算式给：操作栏在窗口坐标里的**下缘**减去安全区的**窗口原点**，就是它在安全区局部坐标里
 * 压到的那条线（两个数都是组合期实测回报，没有任何按方向写死的避让常量，AGENTS 那条铁照守）。
 *
 * ⚠ 回报点必须挂在操作栏 `safeDrawingPadding()` **之内**、它那圈 `padding(top/bottom)` 之外的那条链上：
 * 那样量到的下缘才是"顶栏内容最低能贴到哪"，含它自己的上下内边距、不含系统栏与挖孔那两截
 * （那两截安全区原点里已经扣过一次，再算一次就是双重让位——[HudAreaDp] 的注释记着同一笔账）。
 *
 * 量不到（回报 0）时返回 **0** = "这一帧不推"，与改前逐字同值；下一帧实测接管。
 * 不许返回负数：负 dp 喂进 `maxOf(TopTopPad, ·)` 虽然结果无害，但它会让"量到了多低"这件事
 * 在日志里变成一个看起来像真值的假数（#69 那族"哨兵值参与算式"的坑）。
 */
fun chromeBandBottomDp(chromeBottomWinPx: Int, safeOriginYPx: Int, density: Float): Int =
    pxToDp((chromeBottomWinPx - safeOriginYPx).coerceAtLeast(0).toFloat(), density)

// ------------------------------------------------------------------ 格子（#74：容器内的固定格网）

/**
 * 网格行数硬上限：条目总数。比条目总数还多的行编辑页**摆不出来**，只能来自手改 prefs。
 *
 * ⚠ #75 之后"每颗条目最多占一行"这条前提**不再成立**（高条目按 [cellRowSpan] 吃掉几档），
 * 但这个数本身一个字没动：它只管**存进来的起始行**不许离谱（[clampStoredCell] 与解码共用），
 * 与"那颗有多高、压住几档"是两件事。改成"条目数 ÷ 平均跨度"之类的算法会把上限变成内容的函数，
 * 那正好违反上面那条"跨度不进 schema"。
 */
private val GridRowHardCap: Int get() = HudEntry.ALL.size

/**
 * 网格列数硬上限（逐枚容器）：
 * - [HudZone.READOUT] → 3：`hudPerRowFor` 的最高档就是一行三颗，编辑页也摆不出第四列（这条从既有真源读出）；
 * - 两枚竖 Dock → 2：**#74 后果修复之后这一档不再是"并排占两列"**（配对那两颗现在共用一格，默认是单列），
 *   它是**存储层**的上限：第 2 列只由用户主动摆出来、或 [freeCellNear] 就近让位时使用，
 *   手改 prefs 造出的第 3 列一律在 [clampStoredCell] 钳回。真要把宽度封死靠的是运行时的
 *   [clampZoneX] 与带高那条 `heightIn(max =)`，不是这一档；上一版把"2"论证成"`hudRowGroups`
 *   右 Dock 分支最多一行两颗"，那条判据随配对同格一起作废了，照实改掉，不许留一句假论证。
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
 * 可放带 → [GridBox]：行由**带高 ÷ 格距**算（#75 之后这是"有几档可用"，不是"有几颗条目能排"——
 * 一枚高格要吃掉几档，判据在 [clampCellToBox] 与 [freeCellNear] 那一侧），列对读数块由**可用内宽**决定、
 * 对两枚竖 Dock 直接取 [gridColsCapOf]。
 *
 * ⚠ **#80 之后带还要再夹一次预留档数**（[reservedRows] / [reservedCols]，0 = 不夹）：
 * 底板预留了几档，落点就只许在那几档里——这才是"拖动撑不大自然就平移不了别人"的那一道闸。
 * 两个数由调用方从 [reservedGridRowsOf] / [reservedGridColsOf] 取（与渲染层同一个来源），
 * 这里不重算是因为实测高与格距的取器在这一层只有一份、传进来比再造一条算式好。
 *
 * 选「钳回可放带」而不是「扩行/列把容器撑出带外」：`heightIn(max = bandHeightDp)` 那条硬约束在这儿没变，
 * 真扩出去的行不会把带撑大，只会被 [com.wotagei.cam.ui.HudLayer] 的 `verticalScroll` 接走——
 * 那是"看得见但要点一下才够得着"，而把落点钳回来是"根本放不下"。两者都不静默裁字，但钳回不会让人
 * 以为控件丢了。列的方向没有滚动可依赖（[HudZone.READOUT] 那枚的宽度账由 `planReadoutRow` 说话），
 * 所以列必须钳：越过可用宽就是把 §58/§73 那族"按窄窗量出来的列数在宽窗里越界"再犯一遍。
 *
 * ## 本批：行档可**生长**一格（[GridRowGrowthRows]）
 * 原来这里是 `minOf(带内档数, 预留档数)`：预留几档就只许落那几档，"拖动根本撑不大"是 #80 立的规则，
 * 但它把用户能摆的范围也锁死在默认表用到的档数上（左 Dock 预留 4 档 ⇒ 第 5 档永远摆不到）。
 * 现在多出来的两个形参由调用方显式表态：编辑页传 `rowGrowth = [GridRowGrowthRows]`（放开一格）、
 * `colGrowth = 0`；**其余调用点全部传 0** ⇒ 逐字退回改前那一句 `minOf(带内, 预留)`。
 * 两个形参**没有默认值**（#69 铁律，与 `nativeTopMinDp` 同族）：漏挂必须编译不过，
 * 否则新调用点会静默拿到"有生长"或"无生长"的一侧，两页的钳制带当场分叉。
 */
fun gridBoxOf(
    zone: HudZone,
    pitchXDp: Int,
    pitchYDp: Int,
    bandHeightDp: Int,
    roomWidthDp: Int,
    reservedRows: Int,
    reservedCols: Int,
    rowGrowth: Int,
    colGrowth: Int
): GridBox {
    val rows = if (pitchYDp <= 0) 1 else (bandHeightDp / pitchYDp).coerceAtLeast(1)
    val cols = if (zone != HudZone.READOUT) gridColsCapOf(zone)
    else if (pitchXDp <= 0) 1 else (roomWidthDp / pitchXDp).coerceIn(1, gridColsCapOf(zone))
    // 预留夹一道：0 = 不预留（那一支与改前逐字同值）；夹完至少留 1 档，GridBox 的构造门守着。
    // 行侧的生长量走 [editableGridRowsOf]（预留 + rowGrowth，再夹进带内档数）；列侧同构，本批 colGrowth 恒 0
    return GridBox(
        cols = if (reservedCols > 0) minOf(cols, reservedCols + colGrowth).coerceAtLeast(1) else cols.coerceAtLeast(1),
        rows = editableGridRowsOf(reservedRows, rows, rowGrowth)
    )
}

/**
 * 编辑上限相对默认表预留的**额外行档数**（用户 10-01 明示放宽的那一格）。
 * 只由编辑页那一处消费（[gridBoxOf] 的 `rowGrowth` 形参），取值 1 = 允许比预留多摆一档。
 */
const val GridRowGrowthRows = 1

/**
 * 可编辑的行档数：预留 + 生长余量，再夹进带内实际档数；`reservedRows <= 0`（不预留，读数块那一支）
 * 与改前逐字同值 = 带内实际档数。生长由 [growthRows] 显式喂进来（默认 [GridRowGrowthRows] 只是
 * 为了让"用户批准的那一格"有一个可读的默认调用形态），**不许**在这里偷偷给别的调用点加档。
 * 带高不够（`bandRows <= reservedRows`）时结果就是 `bandRows`，不抛、不给空区间。
 */
fun editableGridRowsOf(
    reservedRows: Int,
    bandRows: Int,
    growthRows: Int = GridRowGrowthRows
): Int =
    if (reservedRows <= 0) bandRows.coerceAtLeast(1)
    else minOf(bandRows, reservedRows + growthRows).coerceAtLeast(1)

/**
 * 把格子钳进可放带（负数与越界都收回来；[GridCell.DEFAULT] 原样放行）
 *
 * ⚠ 它是**格 → 格**的函数，不吃条目身份，所以"配对格 86dp 宽 + 容器可用宽不够"这一档不可能把同组两颗
 * 钳进两个不同格子（那等于顺手拆开配对、再牵连别颗）：两颗拿到的是同一个入参、同一个出参。
 * 唯一会把它们拆开的是用户**自己**把其中一颗摆到别处（[HudLayoutTable.placeEntryAt] 那条路）。
 *
 * ## #75：纵向钳的是**整块**，不是起始档
 * 高条目吃掉 `row .. row+rowSpan−1` 这几档，只钳起始档会让那一块的**尾巴**伸出带外（带外那几档
 * 既不滚动也不显示，等于"那颗只露半截"——正是本次修的那个症状换了个入口）。
 * 所以行的上限是 `box.rows − rowSpan`：钳完之后整块都在带内。
 * 两个形参**没有默认值**：内容高由调用方实测（渲染层与编辑页都量得到），缺这一档就会静默退回
 * "只钳起始行"的老写法（#69 族），漏挂的调用点必须编译不过。
 * 带高连一档都放不下（`box.rows < rowSpan`）时夹成 0 行：宁可贴顶压出去，也不给 `coerceIn` 造出空区间。
 */
fun clampCellToBox(cell: GridCell, box: GridBox, contentHeightPx: Int, rowPitchPx: Int): GridCell =
    if (cell.isDefault) cell else GridCell(
        cell.col.coerceIn(0, box.cols - 1),
        cell.row.coerceIn(0, (box.rows - cellRowSpan(contentHeightPx, rowPitchPx)).coerceAtLeast(0))
    )

/**
 * 一次落点的裁决结果：被拖那颗落哪、被挤出的那些各自落哪（未发生交换/让位时 [moved] 为空）。
 *
 * [moved] 的键是**被挤出的住户**（它们本来就住在目标容器里，只是从被拖那颗抢来的那格挪开），
 * 值是它们的新锚点格。写表时与"被拖那颗 → [dropped]"一并写进同一枚容器的 cells。
 */
data class CellDrop(val dropped: GridCell, val moved: Map<HudEntry, GridCell>)

/**
 * 同容器内一次落点的唯一裁决（本批：拖到已占格**交换**，而不是跳到最近空格）。
 *
 * ## 为什么改成交换
 * 旧写法（[freeCellNear]）只落空格，拖到已占格会跳到最近一格，用户觉得"不听话"；把"同格里的人和被拖的人
 * 对调"才是手指的预期。**交换 ≠ #74 那条独立性退化**：交换只动"目标格那位"这一颗（[moved] 里点名），
 * 其余条目（含高格压住的档）一个都不动——所以"移一项其他项也会同时移动"那条诉求仍然成立，
 * 只是把"同时移动"限定成用户亲手指定的那一颗。
 *
 * ## 步骤（每条都有对应的可证伪用例）
 * 1. `wanted` 夹进 `[0,cols) × [0,rows)`；`span = cellRowSpan(draggedHeightPx, rowPitchPx)`。
 * 2. **块尾出带**（`r > rows - span`）⇒ 退回 [freeCellNear]（不挤人，这就是今天的兜底）。
 * 3. `occupant` = `residents[wanted]` 里**锚点恰为 `wanted`** 且不属于 `{self} ∪ mates` 的那些。
 *    ⚠ 只认锚点：`residents` 的键就是锚点格，所以这里天然只取"格主"。若 `wanted` 落在另一颗高格的
 *    跨度尾巴上（`residents` 里没人锚在这，但 [blockingCells] 展开的 `blocked` 含它），那不算"一格"，
 *    第 4 步会把它当"不是空格"处理——否则会把两颗画在同一段像素上。
 * 4. `occupant` 为空：`wanted` 真是空格（含"拖回自己同组搭档那一格"这一支 ⇒ 合对语义保持不变）
 *    ⇒ 落 `wanted`；若 `wanted` 在 `blocked` 里（只是别颗高格的跨度尾巴）⇒ 退回 [freeCellNear]。
 * 5. `occupant` 非空且 `origin` 能承接 ⇒ 交换：`occupant` 全部→`origin`，`self` 拿 `wanted`。
 *    能承接 = `origin != wanted`、在盒内、`origin + 占位组最高跨度 <= rows`、且 `origin` 那一整块
 *    （`origin .. origin+span−1`）不被其余住户占着——不查整块的话，一枚 3 档的高格会被换到只空 1 档的
 *    地方，尾巴压到第三颗身上（正是"不牵动第三颗"那条要禁的形态）。
 * 6. 否则 ⇒ `occupant` 全部就近让位到 [freeCellNear]（在"扣掉 self 与 occupant"之后的占位集里找空格），
 *    `self` 仍拿 `wanted`。
 *
 * 形参一律**没有默认值**：`residents` / `mates` / 实测高取器 / 格距缺一档，判据就静默退回旧行为，
 * 漏挂的调用点必须编译不过（#69 铁律）。本函数与 [HudLayoutTable.placeEntryAt] 是同一个裁决的两个入口，
 * 编辑页预览直接读它 ⇒"看着在哪一格 = 落在哪一格"。
 */
fun cellDropOf(
    wanted: GridCell,
    origin: GridCell,
    residents: Map<GridCell, Collection<HudEntry>>,
    self: HudEntry,
    mates: Set<HudEntry>,
    draggedHeightPx: Int,
    cellHeightPx: (HudEntry) -> Int,
    cols: Int,
    rows: Int,
    rowPitchPx: Int
): CellDrop {
    val target = GridCell(
        wanted.col.coerceIn(0, (cols - 1).coerceAtLeast(0)),
        wanted.row.coerceIn(0, (rows - 1).coerceAtLeast(0))
    )
    val span = cellRowSpan(draggedHeightPx, rowPitchPx)
    val blocked = blockingCells(residents, self, mates, cellHeightPx, rowPitchPx)
    // ② 块尾出带 ⇒ 退回就近找空格（与改前同一条兜底，只是不再吞掉"交换"这条路）
    if (target.row > rows - span) {
        return CellDrop(freeCellNear(target, blocked, cols, rows, draggedHeightPx, rowPitchPx), emptyMap())
    }
    // ③ 只认"锚点恰在 target"的住户当格主（mates 与 self 自己不算）
    val occupant = residents[target].orEmpty().filter { it != self && it !in mates }
    if (occupant.isEmpty()) {
        // ④ 真空格（含拖回同组搭档那一格）⇒ 落它；只是别颗高格的跨度尾巴 ⇒ 不是"一格"，退回让位
        return if (target !in blocked) CellDrop(target, emptyMap())
        else CellDrop(freeCellNear(target, blocked, cols, rows, draggedHeightPx, rowPitchPx), emptyMap())
    }
    // ⑤ 原格能不能承接这一组：扣掉 self 与 occupant 之后再算一遍"谁还占着哪一块"
    val rest = LinkedHashMap<GridCell, MutableList<HudEntry>>()
    residents.forEach { (cell, who) ->
        val keep = who.filter { it != self && it !in occupant }
        if (keep.isNotEmpty()) rest[cell] = keep.toMutableList()
    }
    val restBlocked = blockingCells(rest, self = null, mates = emptySet(), cellHeightPx, rowPitchPx)
    val occupantBlockPx = occupant.maxOf { cellHeightPx(it).coerceAtLeast(0) }
    val occupantSpan = cellRowSpan(occupantBlockPx, rowPitchPx)
    val originInBox = origin.col in 0 until cols && origin.row in 0 until rows
    val originHosts = origin != target && originInBox &&
        origin.row + occupantSpan <= rows &&
        spannedCells(origin, occupantSpan).none { it in restBlocked }
    if (originHosts) return CellDrop(target, occupant.associateWith { origin })
    // ⑥ 原格不可承接 ⇒ 占位者就近让位，被拖那颗仍拿目标格
    val fallback = freeCellNear(origin, restBlocked, cols, rows, occupantBlockPx, rowPitchPx)
    return CellDrop(target, occupant.associateWith { fallback })
}

/**
 * 目标格被占了就在可放带里就近找**空格**（曼哈顿距离最小，同距离按「先上后下、先左后右」定序）。
 *
 * ⚠ 本批之前这里的 KDoc 有一条"为什么不交换"的论证（"交换等于动一颗动另一颗，与用户诉求冲突"），
 * **那条论证已被推翻**：用户要的正是"拖到哪一格就落到哪一格"，交换与独立性可以同时成立——
 * 交换只动被指到的那一颗，其余条目一颗不动（见 [cellDropOf] 的第 5 步）。于是本函数退回它本来的
 * 职责：**只负责"在给定占位集里找最近空格"这一条算术**，被三个地方复用——
 * ① [cellDropOf] 的"块尾出带 / 跨度尾巴 / 占位者让位"三支；② 跨容器落点（没有"你的原格"可退回）；
 * ③ 渲染解析 [resolveCells] 的就近让位。它**不再**是同容器落点的唯一裁决（那条在 [cellDropOf]）。
 *
 * 带内一个空格都没有时返回 [wanted] 自己（调用方已钳过，此时带被填满）。
 *
 * ## #75：候选格必须**整块**放得下
 * [occupied] 喂进来的是[blockingCells] 展开之后的那一套（高格连它压住的几档一起算占），
 * 本条再加一道"这一颗的块不许越过第 [rows] 档"：`row + rowSpan − 1 <= rows − 1`。
 * 少了这一道，一颗 72dp 的姿态仪能被放到最后一档、尾巴伸出带外——那与本次修的回归同一个形状。
 * 两个形参与 [clampCellToBox] 同源同理由：**没有默认值**（缺了就静默退回一档到底的老写法）。
 */
fun freeCellNear(
    wanted: GridCell,
    occupied: Set<GridCell>,
    cols: Int,
    rows: Int,
    contentHeightPx: Int,
    rowPitchPx: Int
): GridCell {
    val c = wanted.col.coerceIn(0, (cols - 1).coerceAtLeast(0))
    val r = wanted.row.coerceIn(0, (rows - 1).coerceAtLeast(0))
    // 候选可用的最底一档：块放不下就往上退，档数不够（带比这颗还矮）时只留第 0 档
    val lastRow = (rows - cellRowSpan(contentHeightPx, rowPitchPx)).coerceAtLeast(0)
    if (GridCell(c, r) !in occupied && r <= lastRow) return GridCell(c, r)
    val span = (cols.coerceAtLeast(1) * rows.coerceAtLeast(1))
    for (d in 1..span) {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (row > lastRow) continue
                if (GridCell(col, row) in occupied) continue
                if (abs(col - c) + abs(row - r) != d) continue
                return GridCell(col, row)
            }
        }
    }
    return GridCell(c, r)
}

/**
 * 一条条目在这一格（渲染层拿到的就是这一对，不再有第二份算式）。
 *
 * **同一个格子可以出现多条**（同组那颗共用一格，见 [GridCell] 的"一格可住同组多颗"），它们在格内的
 * 左右次序 = 本清单里的先后 = [HudLayoutTable.visibleOrderOf] 的次序——渲染层（`HudEntryGrid`）与
 * 编辑页的命中裁决（[entryHitIndex]）都只读这一处，不许再排第二次序。
 */
data class HudGridItem(val entry: HudEntry, val cell: GridCell)

/**
 * 解析一帧的格子清单：摆过的先占位，没摆过的用 [defaultCellsOf] 推，撞了就就近让位。
 *
 * 两趟（先全部显式、再全部推导）而不是逐颗按顺序处理：否则"谁先占位"取决于 [order] 的先后，
 * 而 [order] 正是本次要降级的那个东西。落点规则是 [blockingCells] 那一条（**同组的颗可以共用一格**，
 * 跨组才算撞车），所以搜索盒必须用**整容器**的 `[0, cols) × [0, rows)`，不能拿"推导格自身那一小片"当盒——
 * 一片被占满时按小盒搜会搜不到空格而把两颗硬摞在同一格，那正是 #74 要修的老行为。
 *
 * 同组两颗不必在这里"成组处理"：第 2 趟逐颗走就行——先落的那颗占了推导格，后落的那颗发现
 * "那一格里只有我的搭档"（[cellGroupMatesOf]），于是不算挡路、原地落进同一格。格内的先后 = [order] 的先后。
 *
 * ## #75：被占的档由 [blockingCells] 按行跨度展开，本函数只看得到"某一格能不能落"
 * 高格压住的第 2、3 档已经在 [occupiedCells]/本函数的 blocked 集合里了，所以就近让位不会把一颗塞进
 * 那一块的中间；[freeCellNear] 另外还会拒掉"块尾出带"的候选。
 * 两个新形参（实测高取器与格距）**没有默认值**：漏挂的那一处会静默退回"人人只占一档"，
 * 而"人人只占一档 + 行距取最高那颗"正是本次拆掉的那个模型。
 */
internal fun resolveCells(
    order: List<HudEntry>,
    placed: Map<HudEntry, GridCell>,
    derived: Map<HudEntry, GridCell>,
    cols: Int,
    rows: Int,
    cellHeightPx: (HudEntry) -> Int,
    rowPitchPx: Int
): List<HudGridItem> {
    val taken = LinkedHashMap<GridCell, MutableList<HudEntry>>()
    val out = LinkedHashMap<HudEntry, GridCell>()
    // 第 1 趟：摆过的条目占位。两颗**不同组**的被手改成同一格时先到先得、后者就近让位——
    // [order] 在这里就是"同一格子里的稳定次序"那一条用途（枚举头的降级清单第 ① 条）；
    // 同组两颗（钉过格子的 S2-2B 配对）共用一格是合法形态，不让位
    for (e in order) {
        val want = placed[e]?.takeIf { !it.isDefault } ?: continue
        val blocked = blockingCells(taken, self = e, mates = cellGroupMatesOf(derived, e), cellHeightPx, rowPitchPx)
        val cell = if (want in blocked)
            freeCellNear(want, blocked, cols, rows, cellHeightPx(e), rowPitchPx)
        else want
        taken.getOrPut(cell) { mutableListOf() }.add(e)
        out[e] = cell
    }
    // 第 2 趟：没摆过的用推导格；推导格被摆过的那颗占了就让位（显式优先，不然"拖过去的那颗"会被
    // 一颗根本没编辑过的条目挤走）
    for (e in order) {
        if (out.containsKey(e)) continue
        val blocked = blockingCells(taken, self = e, mates = cellGroupMatesOf(derived, e), cellHeightPx, rowPitchPx)
        val cell = freeCellNear(
            derived[e] ?: GridCell(0, 0), blocked, cols, rows, cellHeightPx(e), rowPitchPx
        )
        taken.getOrPut(cell) { mutableListOf() }.add(e)
        out[e] = cell
    }
    return order.map { HudGridItem(it, out.getValue(it)) }
}

/**
 * 「今天的排布」→ 格子的唯一一处换算：顺序按 [hudRowGroups] 分行。
 *
 * **不许手抄一份默认坐标**（理由与 [ZonePlacement.DEFAULT] 同一条）：抄一份就会与实现分叉，
 * 而这条函数与容器渲染读的是同一个 [hudRowGroups]，所以"没编辑过的表"结构性等于 B1–B3 定稿排布。
 *
 * ## 行组 → 格子：一枚行组＝一枚格子（竖 Dock），一行组＝一**排**格子（读数块）
 * [HudAxis.GRID]（读数块）那一支，行组里的每一颗各占一列——里面全是胶囊，没有异型件，
 * 均匀 pitch 不会产生离群列宽，本批**一行不碰**它的行为。
 * 其余（[HudZone.LEFT] / [HudZone.RIGHT]，[HudAxis.COLUMN]）走"整组共格"：列恒 0、行 = 行组下标。
 * 这一条就是 #74 后果修复的落点——S2-2B 那对「姿态仪 + 音量表」原先占 (0,0) 与 (1,0) **两格**，
 * 均匀 pitch 之下第 1 列也按姿态仪的 54dp 算 ⇒ 一行两列 ≈112dp、底板 ≈120dp，
 * 比 #74 之前的老排布（54 + 4 + 28 = 86 → 底板 94dp）**宽了 26dp**，而用户这一轮要的是**更窄**
 * （原话「左dock栏还是太宽了」）。改成共格之后三条收益：
 * 1. **宽度回到 ≈94dp**：右 Dock 变成单列网格，列宽 = 最宽那枚**格子**的内容宽 = 配对格的 86dp；
 *    窄的那些行（变焦/对焦 47dp）居中在 86 宽里，与今天 `Column` 的行为一致，观感不变。
 * 2. **独立性更强**：单列网格下**根本没有第二列**，任何条目在列内移动都不可能改变别颗的 x 坐标。
 * 3. **配对变成用户可解开的**：把配对里任意一颗拖到别的格子，配对自然拆开，拆开后两者各自独立——
 *    比"两列各自固定"更符合他要的自由度（合回去同理，见 [blockingCells]）。
 *
 * ## #75：row 按**行跨度**推进，不是 +1
 * 行距改成与住户无关的 [gridRowPitchPx]（≈34dp）之后，一枚高格（右 Dock 的配对格实测 ≈72dp ⇒ 3 档）
 * 会吃掉 `row .. row+span−1`。推导下一组时必须**跳过整块**（`row += span`），否则第二组就压在它身上——
 * 那等于把 #74 拆掉的"移动一颗会压到另一颗"从默认表这一侧又请回来。
 * 默认表于是变成：配对 (0,0)+跨度 3 ⇒ 蓝牙 (0,3)、变焦 (0,4)、对焦 (0,5)、防抖 (0,6)，
 * 7 档 × 34dp − 4dp = **234dp** ≤ 横屏带高 244dp（改前那一档是 386dp，溢出 142dp）。
 * 读数块（GRID 那一支）每档只住一颗胶囊（≈30dp ≤ 格距）⇒ 跨度恒为 1 ⇒ **行号与改前逐字相同**，
 * 这一条不是巧合：那条支路里格子内容与格距都还是胶囊档，所以这一批"一行不碰它的行为"照旧成立。
 * 实测高量不到（返回 0）那一档跨度按 1 算，与 #74 改前同形；下一帧实测接管（两轮收敛，`dockStripH` 那套手法）。
 */
fun defaultCellsOf(zone: HudZone, order: List<HudEntry>, plan: HudGridPlan): Map<HudEntry, GridCell> {
    val rowPitchPx = plan.rowPitchOf(zone)
    var row = 0
    val out = LinkedHashMap<HudEntry, GridCell>()
    for (group in hudRowGroups(zone, order, plan.perRowOf(zone))) {
        // 一枚格子的内容高 = 格内最高那颗（同组的颗是横着排的，见 [cellContentWidthPx] 的同一条理由）；
        // GRID 那一支每格只住一颗，取最大值就是它自己，行步进取的是**这一排里最高的那块**
        val contentPx = group.maxOf { plan.cellHeightOf(it).coerceAtLeast(0) }
        val span = cellRowSpan(contentPx, rowPitchPx)
        if (zone.axis == HudAxis.GRID) {
            group.forEachIndexed { col, entry -> out[entry] = GridCell(col, row) }
        } else {
            group.forEach { entry -> out[entry] = GridCell(0, row) }
        }
        row += span
    }
    return out
}

/**
 * 与 [entry] **同属一枚默认格组**的其他颗（也就是可以合法与它共用一格的那些）。
 *
 * 判据只有一条：**推导格相同**（[defaultCellsOf] 的输出）。所以配对尺子仍然只有 [hudRowGroups] 那一把，
 * 本文件不许出现第二份"姿态仪与音量表算一对"的写死判据。今天唯一的多颗组是右 Dock 那对 S2-2B 并排；
 * 读数块每颗各占一列 ⇒ 这条对任何读数都是空集，行为与改前逐字相同。
 * 隐藏的颗拿不到推导格（它不在可见顺序里）⇒ 它谁的搭档都不是，照常挡路（与改前同一条语义）。
 */
fun cellGroupMatesOf(derived: Map<HudEntry, GridCell>, entry: HudEntry): Set<HudEntry> {
    val home = derived[entry] ?: return emptySet()
    return derived.filterValues { it == home }.keys - entry
}

/**
 * 「这一格（连同它压住的几档）挡不挡 [self] 的路」的唯一判据。
 *
 * 两条口径叠在一起，缺一条都测不出退化：
 * 1. **#74 后果修复**：格子里住着**既不是它自己、也不是它同组搭档**的颗才算挡它的**起始档**。
 *    只住着搭档的那一格不算挡（把姿态仪放回音量表那一格是**重新并排**，不是撞车）。
 * 2. **#75 行跨度**：高格外面那几档 `row+1 .. row+span−1` **一律算占**，连搭档也不例外——
 *    搭档只许落回**同一枚格子**（起始档），不许落进它那块的中间（那是两颗画在同一段像素上，
 *    比跨组撞格更糟：跨组会被就近让开，同档共格才是设计允许的并排）。
 *    [self] 为 null 是"格子里有人就算占"的旧口径（编辑页判"这一格能不能落"之外的用途），
 *    那时整块都算占。
 *
 * [resolveCells] 与 [HudLayoutTable.occupiedCells] 共读这一条，渲染解析与写表落位不会分叉。
 * 两个新形参**没有默认值**（#69 铁律）：漏一处就静默退回"每格只占一档"。
 */
fun blockingCells(
    residents: Map<GridCell, Collection<HudEntry>>,
    self: HudEntry?,
    mates: Set<HudEntry>,
    cellHeightPx: (HudEntry) -> Int,
    rowPitchPx: Int
): Set<GridCell> {
    val out = mutableSetOf<GridCell>()
    for ((cell, who) in residents) {
        if (cell.isDefault || who.isEmpty()) continue
        val span = cellRowSpan(who.maxOf { cellHeightPx(it).coerceAtLeast(0) }, rowPitchPx)
        val blocksAnchor = self == null || who.any { it != self && it !in mates }
        if (blocksAnchor) out += spannedCells(cell, span)
        else if (span > 1) out += (1 until span).map { GridCell(cell.col, cell.row + it) }
    }
    return out
}

/**
 * 一枚格子的**行跨度**（占掉几档）：内容高 ÷ 格距向上取整，至少 1。
 *
 * 取"向上"是硬的：往下取就把一颗画到下一档身上（重叠），而重叠是这次要修的形态的另一半。
 * 内容高量不到（0 / 负）或格距没喂进来（≤0）时按 1 档——那是"还没量到"那一帧的已知退化，
 * 与 [HudGridPlan] 里 `cellHeightOf` 返回 0 同一条语义，不是静默兜底。
 */
fun cellRowSpan(contentHeightPx: Int, rowPitchPx: Int): Int =
    if (contentHeightPx <= 0 || rowPitchPx <= 0) 1
    else ceil(contentHeightPx.toDouble() / rowPitchPx).toInt().coerceAtLeast(1)

/** 一枚格子连同它的行跨度吃掉的档：`(col,row) .. (col,row+span−1)`；哨兵返回空（没摆过 ⇒ 不占档） */
fun spannedCells(cell: GridCell, rowSpan: Int): List<GridCell> =
    if (cell.isDefault) emptyList() else (0 until rowSpan.coerceAtLeast(1)).map { GridCell(cell.col, cell.row + it) }

/**
 * 一帧网格解析要的运行时输入（都来自组合期实测，表本身不该知道它们）。
 *
 * #74 起是两份（可见集 + 读数块分行档），#75 添第三、第四份：**纵向格距**与**每颗的实测高**。
 * 后两条是"行跨度"这件事的唯一数据来源——[GridCell] 里没有第三个坐标，表也不算内容高，
 * 所以两页（录制页 [com.wotagei.cam.ui.HudEntryGrid] 量的尺寸、编辑页同一份）都必须显式喂进来。
 *
 * **两个取器都是无默认值的必传形参**（#69 铁律，与 [HudCtx.gridOf] / `tier` / `readoutWidthDp` 同族）：
 * 给默认值 `{ 1 }` / `{ 0 }` 的话，新增调用点漏挂会静默退回"每格一档 + 行距由最高那颗顶"，
 * 而那正是 #75 修的那个回归；让它编译不过才是这条防线本身。
 *
 * 传**取器**而不是传两张 Map：录制页那份实测高住在快照状态里（`mutableStateMapOf`），
 * 在解析时现读才能挂上读取订阅——量到之后那一帧才重组、跨度才接管（两轮收敛）。
 */
data class HudGridPlan(
    /** 设置里开着显示的条目集：两页各有一份同源计算（CameraScreen 与编辑页），传进来而不是表里再算一遍 */
    val visible: Set<HudEntry>,
    /** 读数块"一行几颗"那一档（[hudPerRowFor] / [planReadoutRow] 的产物），只作用 [HudZone.READOUT] */
    val readoutPerRow: Int,
    /**
     * 该容器的**纵向格距**（px）：由胶囊档推出的那一条 [gridRowPitchPx]，与格子里住了谁无关。
     * 两枚竖 Dock 与读数块的行距档不同（[com.wotagei.cam.ui.hudGridGap] 两分支），所以按容器取。
     */
    val rowPitchOf: (HudZone) -> Int,
    /**
     * 该条目的**实测高**（px）：由 [com.wotagei.cam.ui.HudEntryGrid] 量出来回报的那份。
     * 量不到返回 0 ⇒ 那颗这一帧按一档算（下一帧实测接管），坏值（负）按 0 处理。
     */
    val cellHeightOf: (HudEntry) -> Int
) {
    /** 该容器推导默认格子时的分行档：只有读数块吃这个数，其余容器 [hudRowGroups] 不读它 */
    fun perRowOf(zone: HudZone): Int = if (zone == HudZone.READOUT) readoutPerRow else 1
}

// ------------------------------------------------------------------ #80：网格的锚定与预留

/**
 * 一帧网格的**锚定与预留**（任务 #80，用户这句诉求的正解：「移到一项其他项也会同时移动，这是极大的限制」）。
 *
 * 硬判据：**每颗的屏幕坐标 = f(它自己的 col,row) + 固定原点 + 固定格长，式子里不许出现任何其他颗的信息。**
 * 改这一批之前这条在三个地方破（三条都是真机实测出来的，不是推想）：
 * 1. **格长**取"这一容器里最宽那枚格子"⇒ 右 Dock 那对配对格（86dp）一拆开，撑底板的换成 54dp 那颗
 *    ⇒ 格长从 90dp 掉到 58dp ⇒ **每一颗的 x 都跟着平移**（式子里出现了"最宽那枚格子里住了谁"）；
 * 2. **格网尺寸**取"用到了第几行/第几列"⇒ 底板包内容，而底板在带里的锚定边是**居中**（左 Dock）
 *    或**贴底**（读数块）
 *    ⇒ 多撑一档就把整枚底板往锚定边那侧推，已落位的颗跟着平移
 *    （真机：监看 row1→row3 让参考线/闪光各 dy=−6px、底板高 378→390px；参考线 row0→row1 让闪光 dx=+100px、
 *    底板宽 108→208px）；
 * 3. 归属裁决看的是各枚容器的**矩形**，拖出底板矩形却还在自己带里时会被下面的容器捡走（[dropZoneOf]）。
 *
 * 现在 1 与 2 的基准量一律由**默认表**（[HudLayoutTable.defaultGridOf]，只吃可见集、不吃谁摆到哪）说了算：
 * - 格长 = 默认分组里最宽那枚格子的内容宽 + 一道列距（[gridPitchPx] 那条一个字没改，改的是"喂进去的分组"）；
 *   渲染分组永远不宽过默认分组（跨组不许共格，判据在 [blockingCells]），所以取两者的大只是兜坏数据；
 * - 预留档数 = 默认表用到几档（[reservedGridRowsOf] / [reservedGridColsOf]），编辑页的钳制带钳到同一档
 *   ⇒ **拖动根本撑不大自然就平移不了别人**，底板仍然包内容、不臃肿（主智能体那条方向偏好一条没违背：
 *   没有"吃满带容量的固定大面板"，横屏 234dp 那块板没换成本机 244dp 带高整枚）；
 * - 生长方向 = [gridColFromEndOf]：底板贴哪条边，col 0 就贴那条边，第 2 列只会**背向**锚定边长出去。
 *
 * ⚠ **读数块的纵向是有意留的缺口**（[reservedGridRowsOf] 对它返回 0 = 不预留）：
 * 那块底板是**透明**的（一颗一颗独立悬浮胶囊，没有外层底，见 [com.wotagei.cam.ui.HudReadoutZone] 的文件头），
 * 它的下缘必须钉在 #70 A 那条与底栏共用的基线上（定版），于是"预留高度"与"允许往下多摆一档"
 * 数学上不能同时成立：预留 = 默认档数就把带内空格清零（六颗读数正好占满 2×3），拖哪颗都弹回原地，
 * 那是把用户已有的能力**静默关掉**；不预留就还是"多一档就整块上移"。
 * 本批选后者并留真机待验，横向那一轴已经预留（[reservedGridColsOf] 只对读数块生效，透明 ⇒ 零观感代价），
 * 它顺手把 #70 A「横屏一行两颗」的定版在编辑页变成了硬钳制（以前能摆出第三列）。
 */
data class GridAnchor(
    val zone: HudZone,
    /** 该容器**默认表**的「条目 → 格子」（[HudLayoutTable.defaultGridOf] 的产物；空 = 这一帧还没量到/容器空） */
    val defaultCells: Map<HudEntry, GridCell>
)

/**
 * 该容器的 col 0 贴哪条边（#80）。只有 [HudZone.RIGHT] 贴右缘：它原生对齐是 `CenterEnd`、
 * 绝对落位也在带内贴右，所以第 2 列必须**向左**生长，否则一用出第 2 列整块就向左平移一档。
 * [HudZone.LEFT] 贴左缘（`CenterStart`）⇒ 第 2 列向右生长，天然不动别人。
 * [HudZone.READOUT] 保持"col 0 在左"：它那两枚一组的默认顺序（快门 | 帧率）是定版观感，
 * 从右往左数会把这一对**镜像**，所以它改用[reservedGridColsOf] 预留宽度来钉住左缘。
 */
fun gridColFromEndOf(zone: HudZone): Boolean = zone == HudZone.RIGHT

/**
 * 预留的**行档数**（#80）：默认表用到几档就是几档，0 = 不预留（读数块那一支，理由见 [GridAnchor] 文件头）。
 *
 * 档数按 `max(row + 行跨度)` 算，与 [defaultCellsOf] 推进默认行号用的是同一条 [cellRowSpan] 算式，
 * 所以"配对块吃三档"那一笔不会在这里变成第二个数（#75 那条"行距与住户无关"照守）。
 * [cellHeightPx] 与 [rowPitchPx] 都是无默认值的必传形参（#69 铁律）：漏挂会静默退回"人人一档"。
 */
fun reservedGridRowsOf(anchor: GridAnchor, cellHeightPx: (HudEntry) -> Int, rowPitchPx: Int): Int {
    if (anchor.zone == HudZone.READOUT || anchor.defaultCells.isEmpty()) return 0
    val heightsByCell = groupDefaultCellHeights(anchor.defaultCells, cellHeightPx)
    return heightsByCell.maxOf { (cell, heights) ->
        cell.row.coerceAtLeast(0) + cellRowSpan(heights.max(), rowPitchPx)
    }
}

/** 预留的**列数**（#80）：只对读数块生效（透明底板 ⇒ 预留不花观感钱），其余容器返回 0 = 底板包内容 */
fun reservedGridColsOf(anchor: GridAnchor): Int =
    if (anchor.zone != HudZone.READOUT || anchor.defaultCells.isEmpty()) 0
    else (anchor.defaultCells.values.maxOfOrNull { it.col.coerceAtLeast(0) } ?: 0) + 1

/** 默认表里每枚格子的住户实测高（预留行档数与预留格长共用的那一份分组，两处不许各数一遍） */
private fun groupDefaultCellHeights(
    defaultCells: Map<HudEntry, GridCell>,
    cellHeightPx: (HudEntry) -> Int
): Map<GridCell, List<Int>> = defaultCells.entries
    .groupBy({ it.value }, { cellHeightPx(it.key).coerceAtLeast(0) })
    .filterValues { it.isNotEmpty() }

/** 默认表里最宽那枚格子的内容宽（px，#80：格长的唯一来源）；量不到宽的颗按 0 算 */
private fun defaultCellWidthsPx(
    defaultCells: Map<HudEntry, GridCell>,
    childWidthOf: (HudEntry) -> Int,
    gapXPx: Int
): List<Int> = defaultCells.entries
    .groupBy({ it.value }, { childWidthOf(it.key).coerceAtLeast(0) })
    .map { (_, widths) -> cellContentWidthPx(widths, gapXPx) }

/**
 * 松手时"这颗归哪枚容器"（#80 根因 3 的唯一裁决）。
 *
 * 真机那一条：把右 Dock 的蓝牙往下拖，指针**离开底板矩形却还在右 Dock 自己的可放带里**，
 * 老写法 `zoneAt(指针, 各容器矩形)` 命中了贴在下面的读数块 ⇒ 这颗被真写进读数块，
 * 读数块因此多一档、整块上移 78px，快门/帧率/码率全跟着动。
 * 现在的规矩：**指针还在来源容器那条带里（且横向还在它的底板区间里）就不许改归属**，
 * 纵向拖过头交给钳制带回原带内最近的一档。真要把一颗搬去别的容器，把手指拖出来源那条带即可——
 * 落点预览画的就是这条裁决的结果（编辑页的预览与写表共读 [com.wotagei.cam.ui.HudLayoutTable.gridItems]
 * 那一条链），所以"会落到哪枚容器"在松手前就看得见，不是拖完才知道。
 * [sourceCard] 量不到（首帧）时按"不在带里"处理 ⇒ 退回 [hitAtPointer]，与改前同值（两轮收敛手法）。
 */
fun dropZoneOf(
    pointer: HudPointDp,
    source: HudZone,
    sourceCard: HudRectDp?,
    sourceBandY: IntRange,
    hitAtPointer: HudZone?
): HudZone {
    val hit = hitAtPointer ?: return source
    if (hit == source) return source
    val insideSource = sourceCard != null &&
        pointer.x in sourceCard.left until sourceCard.right &&
        pointer.y in sourceBandY
    return if (insideSource) source else hit
}

// ------------------------------------------------------------------ 格子 ↔ 像素（渲染与编辑页共用）

/** 一个 px 尺寸（[HudLayout] 是纯 Kotlin，不引 Android 的 IntSize） */
data class HudSizePx(val width: Int, val height: Int)

/** 一个 px 坐标 */
data class HudPointPx(val x: Int, val y: Int)

/**
 * 格长 = **横向：该容器内最宽那枚格子的内容宽 + 一道列距；纵向：与住户无关的固定格距**。
 *
 * ⚠ 宽度这一侧吃的是**格子**不是"颗"（#74 后果修复）：配对那两颗共用一格，格子的内容宽由
 * [cellContentWidthPx] 算（各颗之和 + 行距）。按"最宽那颗"定 pitch 会让第二列也撑到姿态仪的 54dp，
 * 一行两列 = 112dp、底板 120dp——那正是把右 Dock 加宽 26dp 的那次回归。
 *
 * ⚠ 高度这一侧 **#75 起不再"取最高那颗"**（原来这里是 `maxCellHeightPx + gapYPx`）：
 * 那一档把行距变成"这一容器里最高那颗的函数"，右 Dock 那枚 ≈72dp 的姿态仪自绘件把行距顶到 ≈78dp，
 * 而它旁边的胶囊只有 30dp ⇒ 5 枚格子 ≈386dp，横屏带高只有 244dp，**溢出 142dp（1.8 行）**，
 * 对焦与防抖整颗掉到折叠线以下。改前那是 `Column` 的自然行高（74 + 4×30 + 4×4 ≈ 210dp），装得进——
 * 均匀行距把 S2-2B 当年"并排省高度"那笔账整个抵消了。
 * 现在纵向恒等于 [gridRowPitchPx]（**已经含那道行距**，所以这里不再 `+ gapYPx`，加了就是双算），
 * 高的条目用 [cellRowSpan] 吃掉后续几档来解决，不再靠把所有人挤高。
 *
 * 定 pitch 是"移动一颗不挪另一颗"的另一半：格子位置只跟 col/row 有关，跟"有几颗、谁在前"无关。
 * 横向仍取实测 ⇒ 字体拉到 120% 时格子自己变宽，§58/§73 那族"按 100% 量出来的固定值在 120% 裁字"
 * 在这里不复发（与 [hudPerRowFor] 用 `fontScale` 折算同一族防线，只是这一处量的是真尺寸不是估宽）；
 * 纵向那一档的 120% 防线搬到了 [gridRowPitchPx] 里——它吃 `fontScale`，所以格子仍然跟着字体涨。
 *
 * 列距没有省掉：`pitch = 最宽格 + gap` 才让"只有一列"时的总宽等于今天 `spacedBy(gap)` 的紧凑排布——
 * 那正是"默认表结构性等于今天的排布"这条硬要求要的等式。
 */
fun gridPitchPx(maxCellWidthPx: Int, rowPitchPx: Int, gapXPx: Int): HudSizePx =
    HudSizePx(maxCellWidthPx + gapXPx, rowPitchPx)

/**
 * **纵向格距**（px）＝ 一颗竖 Dock 紧凑档胶囊的高 + 该容器的一道行距，向上取整。
 *
 * 这是 #75 的核心那一档：行距由**胶囊档**推出来，与"这一容器里最高那颗是谁"完全无关。
 * 每一项都有真源，一个都不许换成散值：
 * - 胶囊高走 [hudChipHeightDp]（`WotaType.chip` 的 lineHeight 18sp + 纵向内边距预算 12——
 *   WotaChip 实高 28dp（5+5），预算按旧档 30dp 保守多留 2dp，与读数块高度预测 [hudStripHeightDp]
 *   同一把尺子）；紧凑档与全局档**只在横向内边上分档**
 *   （`WotaChipTier` 的 `horizontalPad`/`minLabelEm`），纵向两档同值，所以这一条对五枚容器都成立；
 * - 行距由调用方传该容器 [com.wotagei.cam.ui.hudGridGap] 的那一档（两枚竖 Dock = `WotaSpace.xs` 4dp、
 *   读数块 = [HudRowGapDp] 6dp），与列向、格内各颗之间用的是同一个数；
 * - 字体缩放吃 `Density.fontScale`：18sp 那一截随系统字号一起涨，120% 时这一档自己变高。
 *
 * 本机（density 2.0）手算：
 * · 100%：(18 + 12 + 4) × 2 = **68px = 34dp**
 * · 120%：(21.6 + 12 + 4) × 2 = 75.2 → **76px = 38dp**
 * · 读数块 100%：(18 + 12 + 6) × 2 = **72px = 36dp**，与 #74 那一档"实测最高那颗 60px + 行距 12px"
 *   逐字同值——读数块里每颗都是胶囊、没有异型件，所以这一批它的观感一条没变（有看门狗用例钉着）。
 *
 * **向上取整**是有意的：格距是"一行有多少预算"，不是量出来的尺寸。取小了（比如 75px 那一档）
 * 一旦实测胶囊高刚好顶到 76px，一颗常规胶囊就会被算成跨两档、整容器凭空高一倍。
 * 高的东西不靠放大预算解决，靠 [cellRowSpan]。
 * 各档的期望值由 `HudLayoutRowPitchTest` 用**手算 px** 钉住（含"读数块 100% 那一档与 #74 同值"那一条：
 * 它红就说明格距算式与实测行高分叉了）。
 */
fun gridRowPitchPx(fontScale: Float, gapDp: Float, density: Float): Int =
    ceil((hudChipHeightDp(fontScale) + gapDp) * density.toDouble()).toInt().coerceAtLeast(1)

/**
 * 一枚格子的**内容宽** = 格内各颗实测宽之和 + (n − 1) 道行距（一颗时就是它自己的宽，不多算行距）。
 *
 * 行距取的就是该容器既有那档 [hudGridGap]（竖 Dock = `WotaSpace.xs` = 4dp），**没有新造间距值**。
 * 录制中右 Dock 那枚配对格：姿态仪 46 + 4 + 4 = 54dp，音量表 16 + 6 + 6 = 28dp
 * ⇒ 54 + 4 + 28 = **86dp**，与 #74 之前那行 `Row(spacedBy(4.dp))` 的实测宽同值（老 94dp 底板的账）。
 */
fun cellContentWidthPx(childWidthsPx: List<Int>, gapXPx: Int): Int =
    if (childWidthsPx.isEmpty()) 0 else childWidthsPx.sum() + gapXPx * (childWidthsPx.size - 1)

/**
 * 一枚格子的**左缘**相对格网原点（px，任务 #80）：col 0 贴哪条边由 [gridColFromEndOf] 说了算。
 *
 * 贴右缘的那枚容器（[HudZone.RIGHT]）必须从右往里数，式子是
 * `slot(c) = [gridWidth + gap − (c+1)×pitch , gridWidth + gap − c×pitch)`：
 * 只用到的列是 1 时它退化成 `[0, pitch)`，与从左边数**逐字同值**（这条是"默认观感一个字没动"的证据，
 * `HudLayoutGridTest.rightInwardColumnNumberingMatchesLeftInwardForASingleColumn` 钉着），
 * 用出第 2 列时那一列落在**左边**，而格网右缘是钉住的 ⇒ 已落位的 col 0 一颗都不动。
 * 那道 `+ gap` 是格网尾部不留行距那一笔（[gridSizePx] 减掉的同一道）从右边补回来，
 * 所以两支算式在单列时严格相等，不是巧合。
 */
fun cellSlotLeftPx(col: Int, pitch: HudSizePx, gridWidthPx: Int, gapXPx: Int, colFromEnd: Boolean): Int =
    if (colFromEnd) gridWidthPx - (col.coerceAtLeast(0) + 1) * pitch.width + gapXPx
    else col.coerceAtLeast(0) * pitch.width

/**
 * 格内各颗相对**网格原点**的左缘（px）：整枚格子在它那一格里居中（窄行居中在宽格里，与今天 `Column`
 * 的行为一致），再按各颗实测宽 + 行距依次排开。
 *
 * 渲染层（[com.wotagei.cam.ui.HudEntryGrid] 的 `layout` 块）与编辑页的"指针压在哪一颗"
 * （[entryHitIndex]）共读这一条算式，两页不会各有一份格内偏移。
 * 单颗格子时它与 [cellPlaceOffsetPx] 的 x 逐字同值（那是 #74 原来的唯一一支）。
 * [gridWidthPx] 与 [colFromEnd] 是 #80 加的：档数没有默认值可给，漏挂的那一处必须编译不过
 * （给了默认值就等于把"贴右缘从右数"那一支悄悄退回从左数，正是本批要修的平移）。
 */
fun cellChildLeftsPx(
    cell: GridCell,
    pitch: HudSizePx,
    childWidthsPx: List<Int>,
    gapXPx: Int,
    gridWidthPx: Int,
    colFromEnd: Boolean
): List<Int> {
    if (childWidthsPx.isEmpty()) return emptyList()
    val content = cellContentWidthPx(childWidthsPx, gapXPx)
    var x = cellSlotLeftPx(cell.col, pitch, gridWidthPx, gapXPx, colFromEnd) + (pitch.width - content) / 2
    return childWidthsPx.map { width -> val left = x; x += width + gapXPx; left }
}

/**
 * 一帧格网的**完整测量结果**：格长、格网尺寸、每颗相对网格原点的左上角。
 *
 * ## 为什么把这段算术从渲染层搬进来
 * [com.wotagei.cam.ui.HudEntryGrid] 那个 `Layout` 在 JVM 单测里打不到（本工程不引 Robolectric），
 * 而"pitch 到底取最宽那枚**格子**还是最宽那**颗**"恰恰是 #74 后果修复唯一会走错的一步——
 * 留在 `Layout` 里就是"只有真机能验、用例证不了"的缺口（AGENTS 那条"恒等式不算证明"最容易被这种
 * 地方蒙过去）。搬成纯函数之后，底板那笔宽度账（172px = 86dp ⇒ 底板 94dp）有手算期望值可打，
 * `HudEntryGrid` 退化成"量尺寸 + `placeRelative`"两条没有分支的调用。
 *
 * 三条规则：① 逐格累计住户（**清单序** = 格内左右序，与 [HudGridItem] 的说明同源）；
 * ② 格长 = 最宽那枚格子的内容宽（[cellContentWidthPx]）+ 一道列距，纵向 = 传进来的固定 [rowPitchPx]
 *    （[gridPitchPx]；#75 起**不再从 [childSizes] 里取最高那颗**，那是把右 Dock 撑到 386dp 的那一步）；
 * ③ 每颗落点 = 所在格子的内容在格宽里居中 + 格内左缘（[cellChildLeftsPx]），
 *    纵向沿用 [cellPlaceOffsetPx]，但居中基准是**整块**（`rowSpan × rowPitch`）而不是一档。
 *
 * 还有一条是这次新增的：**总行数按跨度算**。
 * 跨度来自 [cellRowSpan]（该格实测内容高 ÷ 格距向上取整），所以 `行数 = max(row + span − 1) + 1`，
 * 不再是 `max(row) + 1`。漏掉这一笔的话最后一枚高格的尾巴会画到格网之外（`size.height` 报小了），
 * 底板于是比内容矮一截、`verticalScroll` 的范围也算错——两种都是"看起来没溢出其实裁了"。
 *
 * 要求 [childSizes] 与 [items] 等长（同一个 `Layout` 的 measurables 必然等长；不等长是编程错误，直接抛）。
 * [rowPitchPx] **没有默认值**：它必须由调用方（渲染层从令牌算，见 `HudLayer.hudGridRowPitchPx`）喂进来，
 * 一旦给默认值就会有调用点悄悄用到"别的"行距，两页的格子高度立刻分叉。
 */
fun gridPlacementOf(
    items: List<HudGridItem>,
    childSizes: List<HudSizePx>,
    gapPx: HudSizePx,
    rowPitchPx: Int,
    anchor: GridAnchor
): GridPlacement {
    require(items.size == childSizes.size) { "格网测量：颗数与尺寸数不等 ${items.size} vs ${childSizes.size}" }
    val widthOf = HashMap<HudEntry, Int>()
    val heightOf = HashMap<HudEntry, Int>()
    items.forEachIndexed { i, item ->
        widthOf[item.entry] = childSizes[i].width
        heightOf[item.entry] = childSizes[i].height
    }
    val cells = LinkedHashMap<GridCell, MutableList<Int>>()
    items.forEachIndexed { i, item -> cells.getOrPut(item.cell) { mutableListOf() }.add(i) }
    val cellWidths = cells.map { (_, idx) -> cellContentWidthPx(idx.map { childSizes[it].width }, gapPx.width) }
    // 格长的宽度那一轴取**默认分组**里最宽那枚格子（#80）：渲染分组永远不宽过它（跨组不许共格，
    // 判据在 [blockingCells]），所以 `maxOf` 只兜"默认表还没量到"与手改 prefs 造出的坏数据两档
    val defaultWidths = defaultCellWidthsPx(anchor.defaultCells, { widthOf[it] ?: 0 }, gapPx.width)
    val pitch = gridPitchPx(
        maxCellWidthPx = maxOf(defaultWidths.maxOrNull() ?: 0, cellWidths.maxOrNull() ?: 0),
        rowPitchPx = rowPitchPx,
        gapXPx = gapPx.width
    )
    // 每枚格子的行跨度：格内最高那颗 ÷ 格距（同组的颗是横着排的，所以取最大值就是这枚格子的高）
    val spans = cells.mapValues { (_, idx) -> cellRowSpan(idx.maxOf { childSizes[it].height }, pitch.height) }
    val usedCols = (items.maxOfOrNull { it.cell.col.coerceAtLeast(0) } ?: -1) + 1
    val usedRows = (cells.keys.maxOfOrNull { it.row.coerceAtLeast(0) + (spans.getValue(it) - 1) } ?: -1) + 1
    // 档数取默认表的预留（#80）：底板于是"最多就默认那么大"，拖动撑不大自然就平移不了别人
    val cols = maxOf(usedCols, reservedGridColsOf(anchor))
    val rows = maxOf(usedRows, reservedGridRowsOf(anchor, { heightOf[it] ?: 0 }, pitch.height))
    val size = gridSizePx(cols, rows, pitch, gapPx)
    val colFromEnd = gridColFromEndOf(anchor.zone)
    val offsets = Array(items.size) { HudPointPx(0, 0) }
    cells.forEach { (cell, idx) ->
        val lefts = cellChildLeftsPx(
            cell, pitch, idx.map { childSizes[it].width }, gapPx.width, size.width, colFromEnd
        )
        val span = spans.getValue(cell)
        idx.forEachIndexed { k, childIndex ->
            offsets[childIndex] = HudPointPx(
                x = lefts[k], y = cellPlaceOffsetPx(cell, pitch, childSizes[childIndex], span).y
            )
        }
    }
    return GridPlacement(pitch = pitch, size = size, offsets = offsets.toList())
}

/**
 * [gridPlacementOf] 的产物：渲染层只读这三件，不再自己算任何一格的位置。
 *
 * ⚠ [size] 的纵向已经是"按跨度算出来的行数 × 格距 − 一道行距"，所以**不能**再拿它去除回格距
 * 来反解行距（编辑页 #74 是这么干的，#75 之后改成 [snapGridPitchPx]）。
 */
data class GridPlacement(val pitch: HudSizePx, val size: HudSizePx, val offsets: List<HudPointPx>)

/**
 * 一格多颗时"指针压住的是哪一颗"（#74 后果修复：配对 = 一格两颗，编辑器必须能点名要搬哪一颗）。
 *
 * 规则两条，写在这里而不是靠 Compose 的命中回报顺序：
 * 1. 先取**横向区间真的压住指针**的那些颗；都不压住（指针对在两颗之间那道行距里）时把候选放宽到全部，
 *    这样"按在缝上"也抓得起一颗，而不是整枚容器跟着走；
 * 2. 候选里取**区间中心离指针最近**的那颗，同距离取靠左那颗（`minByOrNull` 返回首个最小值，定序稳定）。
 *
 * [spans] 是格内各颗的实测横向闭区间（**格内渲染顺序**，与 [cellChildLeftsPx] 同序），返回下标；
 * 空候选返回 -1。
 */
fun entryHitIndex(spans: List<IntRange>, pointerX: Int): Int {
    if (spans.isEmpty()) return -1
    val inside = spans.indices.filter { pointerX in spans[it] }
    val pool = inside.ifEmpty { spans.indices.toList() }
    return pool.minByOrNull { abs((spans[it].first + spans[it].last) / 2 - pointerX) } ?: pool.first()
}

/** 网格总尺寸：`列数 × 格宽 − 一道行距`（最后一格后面不再有间距，与 `spacedBy` 同一条账） */
fun gridSizePx(cols: Int, rows: Int, pitch: HudSizePx, gap: HudSizePx): HudSizePx {
    if (cols <= 0 || rows <= 0) return HudSizePx(0, 0)
    return HudSizePx(cols * pitch.width - gap.width, rows * pitch.height - gap.height)
}

/**
 * [gridSizePx] 的逆算：编辑页拿的是网格节点的**实测矩形**，把**列**长除回来而不是再量一遍每颗条目。
 *
 * 为什么量网格不量条目：姿态仪与音量表那两颗本来就不报锚点（[HudEntry.pillKey] 返回 null 那三条之一），
 * 靠条目矩形取"最宽/最高那颗"会漏掉它们——那正是 §58/§73 那族"量窄了于是裁字"的成因。
 * 网格节点是渲染层用同一个 [gridPitchPx] 算完再 `layout()` 出去的，矩形与格长是同一个数的两面，
 * 所以这里能整除回去（`(矩形 + 一道行距) ÷ 格数`），不需要第二条公式。
 *
 * 一列/一行都还没有（空容器）时退回"被拖那颗自己的尺寸 + 行距"：那一刻格长只能由它撑。
 *
 * ⚠ **#75 之后纵向不许再从这条逆算**：格子带行跨度 ⇒ `网格高 ÷ 行数 ≠ 行距`（高格那一块把 3 档
 * 算成 1 行时，除回来的行距会虚高一倍，吸附出来的格子与画出来的对不上）。
 * 本函数的 [HudSizePx.height] 现在只是"与 [gridSizePx] 互为逆运算"这一性质的**测试对象**
 * （`HudLayoutGridTest.gridPitchOfInvertsGridSizePx` 钉着），生产调用点只取 `.width`，
 * 纵向走 [snapGridPitchPx] 那条与 [gridRowPitchPx] 同源的格距。
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

/**
 * 编辑页吸附用的**格长**：横向从网格实测矩形逆算（[gridPitchOf]，列宽仍然是"最宽那枚格子"撑出来的），
 * 纵向**直接用格距**（[gridRowPitchPx] 那份，由调用方传进来）。
 *
 * 为什么要单独一条函数而不是在 `snapOf` 里手写两句：
 * "纵向不能再除回来"这件事一旦退回成 `gridPitchOf(...).height`，屏幕上就变成"预览框画在一格、
 * 松手落到另一格"，而那是 Compose 侧才看得见的错（本工程不引 Robolectric）。抽成纯函数之后
 * "喂一个与网格高不匹配的 rowPitch 进来，输出的纵向必须等于 rowPitch 而不是除回来的那个数"
 * 就是能打的一条用例（`HudLayoutRowPitchTest.editorSnapPitchIgnoresTheGridHeight`）。
 *
 * [rowPitchPx] 与 [fallbackChildWidthPx] 都是无默认值的必传形参（#69 铁律）。
 */
fun snapGridPitchPx(
    gridWidthPx: Int,
    cols: Int,
    gapPx: Int,
    fallbackChildWidthPx: Int,
    rowPitchPx: Int
): HudSizePx = HudSizePx(
    // 只取逆算出来的**列**长；那两条纵向入参对本函数的输出没有影响（height 这里被丢掉）
    width = gridPitchOf(
        gridWidthPx = gridWidthPx, gridHeightPx = 0, cols = cols, rows = 0, gapPx = gapPx,
        fallbackChildWidthPx = fallbackChildWidthPx, fallbackChildHeightPx = 0
    ).width,
    height = rowPitchPx
)

/** 某格的左上角相对网格原点（**格子 → 坐标的正算式**，[cellAtPointer] 是它的逆） */
fun cellOffsetPx(cell: GridCell, pitch: HudSizePx): HudPointPx =
    HudPointPx(cell.col.coerceAtLeast(0) * pitch.width, cell.row.coerceAtLeast(0) * pitch.height)

/**
 * 指针 → 它**压住的那一格**（**编辑页吸附用的逆算式**，与 [cellOffsetPx] / [cellSlotLeftPx] 一对一）。
 *
 * 取"压住"（floor）而不是"中心最近"（round）：手指按在条目中心拖，条目中心落在哪一格就该是哪一格；
 * 用 round 会让指针对在格子左缘那一瞬跳到左边一格，视觉上像吸附迟滞了一格。
 * 网格原点由 `HudEntryGrid` 节点自己回报（窗口矩形），所以这里不需要知道格内居中偏移
 * （渲染层用 [cellPlaceOffsetPx] 补那半截，编辑页只按格网原点吸附，两条算式互不干扰）。
 *
 * ⚠ #80：贴右缘生长的那枚容器（[gridColFromEndOf]）列要**从右往里**数，所以 [gridWidthPx]、[gapPx]、
 * [colFromEnd] 三个形参都没有默认值——漏挂的那一处会静默把"从右数"退回"从左数"，
 * 于是编辑页吸附到的列与画出来的列左右互换（#75 那条"看着在一格、松手落另一格"的又一种走法）。
 * [colFromEnd] = false 那一支只读 [origin] 与 [pitch]，与改前逐字同值。
 */
fun cellAtPointer(
    pointer: HudPointPx,
    origin: HudPointPx,
    pitch: HudSizePx,
    gridWidthPx: Int,
    gapPx: Int,
    colFromEnd: Boolean
): GridCell {
    if (pitch.width <= 0 || pitch.height <= 0) return GridCell(0, 0)
    val localX = pointer.x - origin.x
    val col = if (colFromEnd) {
        // [cellSlotLeftPx] 那一支的逆：slot(c) = [W+gap −(c+1)pitch, W+gap −c·pitch) ⇒ c = ceil(u/pitch) − 1
        ceilDiv(gridWidthPx + gapPx - localX, pitch.width) - 1
    } else {
        floorDiv(localX, pitch.width)
    }
    return GridCell(
        col = col.coerceAtLeast(0),
        row = floorDiv(pointer.y - origin.y, pitch.height).coerceAtLeast(0)
    )
}

/**
 * 格内的居中偏移（颗比格子小的时候补这一段；[cellOffsetPx] 是格网左上角，这一条才是 child 的左上角）
 *
 * [rowSpan] = 这一枚格子吃掉几档（[cellRowSpan]，#75）。纵向在**整块**里居中：
 * 一档的常规颗算式与 #74 逐字同值（span=1 ⇒ `(pitch.height − h) / 2`），
 * 高条目则在自己的几档里居中，上下各留 (span×pitch − 内容) ÷ 2 的余量——
 * 顶到块的天花板上不行（它会压到下一块的第一颗），一律贴顶也不行（每一档的观感会随跨度跳）。
 * 形参**没有默认值**：漏挂的那一处会把 72dp 的姿态仪塞回一档里、与下一颗叠字。
 */
fun cellPlaceOffsetPx(cell: GridCell, pitch: HudSizePx, child: HudSizePx, rowSpan: Int): HudPointPx {
    val g = cellOffsetPx(cell, pitch)
    val blockHeightPx = pitch.height * rowSpan.coerceAtLeast(1)
    return HudPointPx(
        g.x + (pitch.width - child.width) / 2,
        g.y + (blockHeightPx - child.height) / 2
    )
}

/** 向下取整的除法（`/` 对负数是截断，指针拖到网格左上方时会给出 -0 这种暧昧值） */
private fun floorDiv(v: Int, p: Int): Int {
    val q = v / p
    return if (v % p != 0 && (v xor p) < 0) q - 1 else q
}

/** 向上取整的除法（#80 那一支"从右往里数列"的逆算式用；与 [floorDiv] 同一套负数语义） */
private fun ceilDiv(v: Int, p: Int): Int = -floorDiv(-v, p)

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
            if (a.intersects(b)) {
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
 *
 * ⚠ 2026-10-01（用户第 5 项「Dock 更高、显示更全」）：录制页与编辑页的右 Dock 已改调 [dockBottomAvoidDp]
 * ——那条在"取大不求和"之外还多了「只避真正横向重叠的邻居」这一档。本条只剩它自己钉住的并联语义
 * （`HudReadoutRowPlanTest` / `HudLayoutDragTest` 仍逐档读它），**生产调用点已归零**、别再往回接。
 */
fun areaForRightDock(area: HudAreaDp, readoutHeightDp: Int, readoutBottomDp: Int): HudAreaDp =
    area.copy(
        bottomAvoidDp = maxOf(area.bottomAvoidDp, readoutBottomDp + readoutHeightDp.coerceAtLeast(0))
    )

/**
 * 竖 Dock 与"真压上来的邻居"之间那枚**观感缝**（dp）：只为让两块胶囊的边不相接。
 * 与 [com.wotagei.cam.ui.design.WotaSpace] 的 4pt 栅格**不是同一条账**（那不是设计节奏，是"两块贴边"
 * 这件事的间隙），所以它在这里就地命名，不往令牌表里塞第二枚同值令牌。
 */
private const val DockNeighborGapDp = 2

/**
 * 竖 Dock 的底部避让（dp）：只避**真正横向重叠**的邻居（用户 2026-10-01 第 5 项「左右侧 Dock 更高、
 * 内部控件显示更全」）。
 *
 * 背景：竖 Dock 贴边、底栏**居中**（本机常态宽 216dp），常规屏宽下两者横向**不重叠**——此前一律避
 * "底栏整带 72dp"，把不重叠的那截空间白白送掉：右 Dock 5 组内容 ≈250dp 被夹进 244dp 带里，
 * 第 5 组（防抖）出带、默认态「对焦」裁半截。本条按几何一次算清该避多少，录制页与编辑页**同账**
 * （两页只调这一条，不许各摆一份判据——S3-5 那条「两边判的不是同一条不等式」的老坑）。
 *
 * 规则：
 * - Dock 与底栏横向不重叠 ⇒ 底栏不构成避让，该侧只留 [HudEdgePad] 那枚设计留白；
 * - 横向重叠（窄窗 / 分屏）⇒ 退回 [bottomDockStripDp]（**旧行为，降级路径一条不丢**）；
 * - 右 Dock（[fromEnd] = true）与读数块同贴右缘、恒横向重叠 ⇒ 还要叠 READOUT 那一截
 *   （`readoutBottomDp + readoutHeightDp + 一枚 [DockNeighborGapDp]`），与 [areaForRightDock] 的
 *   **并联取大**同一条语义（同一条横带上两块各占一半，求和会让 Dock 白让一整排）；
 * - [dockWidthDp] ≤ 0（首帧还没量到那个宽）⇒ 保守返回 [bottomDockStripDp]：无从判重叠就别赌，
 *   下一帧实测接管（与 topBarH / dockStripH 同一套"两轮收敛"手法）。
 *
 * 重叠判据用**严格不等**（与 [HudRectDp.intersects] 同一条边界语义：两块边相接时那几像素没有重叠）。
 *
 * @param fromEnd 该 Dock 是否从右缘起算（RIGHT = true / LEFT = false）
 */
internal fun dockBottomAvoidDp(
    safeWidthDp: Int,
    dockWidthDp: Int,
    bottomDockWidthDp: Int,
    bottomDockStripDp: Int,
    fromEnd: Boolean,
    readoutBottomDp: Int,
    readoutHeightDp: Int,
): Int {
    // 首帧还没量到 Dock 自己的宽：判不了重叠，保守按"会重叠"给（下一帧实测接管）
    if (dockWidthDp <= 0) return bottomDockStripDp
    // 底栏横向恒居中：x 范围 = [(safe − bw)/2, (safe + bw)/2]；Dock 贴自己那条边
    val bottomLeft = (safeWidthDp - bottomDockWidthDp) / 2
    val bottomRight = (safeWidthDp + bottomDockWidthDp) / 2
    val dockLeft = if (fromEnd) safeWidthDp - dockWidthDp else 0
    val dockRight = if (fromEnd) safeWidthDp else dockWidthDp
    val overlaps = dockLeft < bottomRight && bottomLeft < dockRight
    // 不重叠 ⇒ 这一侧的自由边只剩设计留白；重叠 ⇒ 退回旧行为（整条底栏带）
    val stripDp = if (overlaps) bottomDockStripDp else HudEdgePad.value.roundToInt()
    // 左 Dock 不叠读数块（它在右缘）：读数那两枚入参不参与这一支
    if (!fromEnd) return stripDp
    return maxOf(stripDp, readoutBottomDp + readoutHeightDp.coerceAtLeast(0) + DockNeighborGapDp)
}

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
 * - [HudZone.RIGHT]：**S2-2 B 那条并排规则保留**——姿态仪与音量表在顺序里相邻时并成一行
 *   （省下的是**宽度与列数**：不并排就是两列、第二列也按姿态仪的 54dp 撑 ⇒ 底板 120dp，见 [defaultCellsOf]。
 *   #74 之前那句"省 ≈75dp 高度、把变焦/对焦从折叠线下捞回来"已经被 #75 接管：高度那笔账现在由
 *   [gridRowPitchPx] 固定格距 + [cellRowSpan] 行跨度负责，配对块只吃它自己需要的几档，
 *   不再替别的胶囊决定行距）。
 *   #74 后果修复之后这一行是**一枚格子里放两颗**而不是"一行两列"：列数不再被撑开，
 *   底板宽度才回到 r11 实测的 ≈94dp（读这份分组的唯一一处是 [defaultCellsOf]，见它的三条收益）。
 *   顺序被用户拆开就不再并排，这是"用户可以重排"与"横屏 360dp 带高不够"两条要求唯一能同时成立的写法；
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
