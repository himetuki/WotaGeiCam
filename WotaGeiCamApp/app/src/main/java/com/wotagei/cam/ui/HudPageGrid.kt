package com.wotagei.cam.ui

import com.wotagei.cam.ui.design.WotaSpace

/**
 * **整页网格**的坐标数学（docs/plan/17 §六 P1 落地，纯函数层，全部能在 JVM 单测里真跑）。
 *
 * ## 页格与 Dock 内格**同源同尺**（拍板①甲案，plan/17 §七.1）
 * 用户 2026-10-02 的需求是「整个录制页画面网格化，每个控件占固定宽高的格子」。格子尺寸不新造公式：
 * 页格边长 = [gridRowPitchPx](fontScale, `WotaSpace.xs`, density) —— 与两枚竖 Dock 的格**逐字同一个函数、
 * 同一档行距**（100% 档 34×34dp、120% 档 38×38dp，随系统字号档一起涨）。选甲案不选乙案（全页统一格、
 * Dock 内也按页格 + 跨度排）的理由是 Dock 宽度三条基线（左 53~54 / 右 62 / 录制中右 94dp）不许被动：
 * 乙案会把左 Dock 撑到 ≈76dp，与用户历史投诉「左 dock 还是太宽」正好相背。
 * 代价照实写：页格与 Dock 内格大小相同但**坐标空间不同**（页格原点 = 安全区左上角，Dock 内格原点 =
 * 那枚底板），所以这一层用独立的 [HudPageCell] 而不是复用 [GridCell]——两个坐标空间混型正是
 * 「渲染格 ≠ 存储格」那一族自相矛盾表的入口。
 *
 * ## 本文件与 [HudLayout] 的分工
 * [HudLayout] 管五枚容器内的格；本文件管**整页**那一层。行跨度、占用判据、就近让位这些已有件
 * 一律复用（[blockingCells] / [cellRowSpan]），不在这里造第二份算式——两份算式迟早分叉，
 * 那就是 r2_rootcause §二 同类隐患 4「预览画一格、松手落另一格」。
 *
 * ## 唯一的 `Dp` 引用
 * [pageCellPx] 要按拍板①的口径直接吃令牌 `WotaSpace.xs`（plan/17 §六 P1 原文），所以本文件引了一处
 * `com.wotagei.cam.ui.design.WotaSpace`。`Dp` 是 compose-ui-unit 的纯 Kotlin 值类、JVM 单测得跑得到
 * （不引任何 Android/渲染件），这也是"直接复用、不手抄一份 4f"与"纯函数层不 import 设计令牌"两条纪律
 * 之间的取舍：**手抄一份间距值迟早与令牌分叉**，那比多一处类型依赖贵得多。
 */

/**
 * 一颗条目在**页格**里的坐标（列 / 行，都从 0 起；页格原点 = 安全区左上角）。
 *
 * 与 [GridCell] **不是同一个类型**，尽管字段一模一样：Dock 内格与页格是两个坐标空间（前者原点在
 * 那枚底板、列还可能贴右缘从右数；后者原点恒在安全区左上角、从左往右数）。让两者混型，
 * P3 之后就会出现"把 Dock 内格当页格写"或反过来——那种表自相矛盾且编译一声不响。
 * 需要复用 [blockingCells] 那族吃 [GridCell] 的算式时，走 [toGridCell] / [toPageCell] 这一座显式的桥。
 *
 * [DEFAULT] 是哨兵，语义与 [GridCell.DEFAULT] 同源："这颗从没在页格上被摆过"。
 * [token] 给 P2 的 v3 `G` 段用（`<col>.<row>`，哨兵写 `-`，与 [GridCell.token] 同语法）。
 */
data class HudPageCell(val col: Int, val row: Int) {

    /** 两轴都是哨兵 = 没被摆过 */
    val isDefault: Boolean get() = col < 0 && row < 0

    /** 持久化 token（`0.2`）；哨兵写成 `-`（与 [GridCell.token] 同语法，P2 的 G 段直接复读） */
    val token: String get() = if (isDefault) "-" else "$col.$row"

    companion object {
        val DEFAULT = HudPageCell(-1, -1)
    }
}

/** 页格坐标 → Dock 内格坐标的桥（同一对整数、两个坐标空间；**只在复用既有算式时用**） */
internal fun HudPageCell.toGridCell(): GridCell = GridCell(col, row)

/** Dock 内格坐标 → 页格坐标的桥（[toGridCell] 的逆） */
internal fun GridCell.toPageCell(): HudPageCell = HudPageCell(col, row)

/**
 * **页格边长**（px）= 与两枚竖 Dock 同源同尺的那一档格距。
 *
 * 直接复用 [gridRowPitchPx]，行距档取 `WotaSpace.xs`（4dp，Dock 那一档，**不是**读数块的
 * [HudRowGapDp] 6dp——页格跟着 Dock 走，理由见 [HudPageGrid] 文件头）：
 * - 100% 档：(18 + 12 + 4) × 2 = **68px = 34dp**
 * - 120% 档：(21.6 + 12 + 4) × 2 = 75.2 → **76px = 38dp**
 * - 系统字号低于 100% 被 [com.wotagei.cam.ui.hudChipHeightDp] 夹回 1f ⇒ 仍是 68px
 *
 * 为什么不复读一份 34：手抄的格长会在任何一次令牌调整后与渲染层分叉，而那正是 AGENTS 那条
 * "手抄的默认值会和实现分叉"。三个消费方（编辑页画线、P3 的落点与渲染）必须读这一个函数。
 */
fun pageCellPx(fontScale: Float, density: Float): Int =
    gridRowPitchPx(fontScale, WotaSpace.xs.value, density)

/**
 * 安全区 → 页格盒（**列数 / 行数**，floor）。
 *
 * 末格不足一格的部分**弃掉，不补一个窄格**：格子是"固定宽高的定位单位"，落点算式（[pageCellAtPointer]）
 * 与渲染格必须逐一对上。补一个窄格会让最后一格的宽度不等于 `cellPx`，于是"指针在这半格里"这件事
 * 在逆算式里给不出一个正确的列号——要么把格长按最小格算（整页格长全变），要么给窄格单独一棵分支
 * （两套格长，正是要拆掉的那种分叉）。弃掉的那 ≤1 格在安全区边缘留成空白，与 [gridBoxOf] 里
 * "行由带高 ÷ 格距、不向上取整"是同一条纪律。
 *
 * `cellPx <= 0`（格长没算出来）时给 1×1：那是"这一帧还不能画/还不能落点"的退化态，
 * 与 [cellAtPointer] 在 `pitch <= 0` 时返回 (0,0) 同族，不抛、不除零。
 */
fun pageGridBoxOf(safeWPx: Int, safeHPx: Int, cellPx: Int): GridBox {
    if (cellPx <= 0) return GridBox(1, 1)
    val cols = (safeWPx / cellPx).coerceAtLeast(1)
    val rows = (safeHPx / cellPx).coerceAtLeast(1)
    return GridBox(cols, rows)
}

/**
 * 指针 → 它**压住的那一个页格**（[pageCellOffsetPx] 的逆，与 [cellAtPointer] 同族写法）。
 *
 * 取"压住"（floor）而不是"中心最近"（round）与 [cellAtPointer] 同一条理由：手指按在控件中心拖，
 * 控件中心落在哪一格就该是哪一格；round 会让指针对在格子左缘那一瞬跳到左边一格，看着像吸附迟滞。
 * **没有 `colFromEnd` 支**：页格不钉列锚定（左缘恒贴安全区左边、列从左往右数），而贴右缘那枚 Dock
 * 必须从右数（[gridColFromEndOf]）——少一支不是因为懒，是页格没有锚定边。
 *
 * 越界钳制与 [cellAtPointer] **逐字同构**：负的局部坐标钳到 0（负数那一档在 floor 与截断两条
 * 算式里给出同一个答案，因为紧跟一个 `coerceAtLeast(0)`，所以这里不把 [HudLayout] 那个私有
 * `floorDiv` 也搬一份）；上界**不钳**——越出安全区的列号由调用方拿 [pageGridBoxOf] 的盒子钳，
 * 与 Dock 那一侧"索引函数不钳带、钳制是 [clampCellToBox] 的事"是同一条分工。
 */
fun pageCellAtPointer(pointerX: Int, pointerY: Int, originX: Int, originY: Int, cellPx: Int): HudPageCell =
    if (cellPx <= 0) HudPageCell(0, 0) else HudPageCell(
        col = ((pointerX - originX) / cellPx).coerceAtLeast(0),
        row = ((pointerY - originY) / cellPx).coerceAtLeast(0)
    )

/** 一个页格的左上角相对**页格原点**（px，[pageCellAtPointer] 的正算式；与 [cellOffsetPx] 同族） */
fun pageCellOffsetPx(cell: HudPageCell, cellPx: Int): HudPointPx =
    HudPointPx(cell.col.coerceAtLeast(0) * cellPx, cell.row.coerceAtLeast(0) * cellPx)

/**
 * 页格占用判据：**哪些档被占**（挡不挡 [self] 的路），判据整块不相交。
 *
 * 就是 [blockingCells] 的页格版：行人复用 [blockingCells]（含 #75 那条"高格连它压住的
 * `row+1 .. row+span−1` 一起算占"与 mate 感知的两条口径），只在两侧过 [toGridCell] / [toPageCell]
 * 这一座桥。**不在本文件另写一份占用逻辑**——两套判据分叉时，编辑页预览说"这格有人"而 P3 写表说
 * "没人"，正是 r2_rootcause §二 同类隐患 4 的形状。
 *
 * [residents] 的键是**锚点格**（与 [HudLayoutTable.residentsOf] 同义），[self] 为 null 是
 * "有人就算占"的旧口径（编辑页判"这一格能不能落"之外的用途），[mates] 是同组搭档（合对那一支）。
 * 页格条目**同样有 rowSpan**（[cellRowSpan]，页面条目与 Dock 内同一条），所以跨两档的高条目
 * 会把它的跨度尾也算成占。
 */
fun pageCellsBlocked(
    residents: Map<HudPageCell, Collection<HudEntry>>,
    self: HudEntry?,
    mates: Set<HudEntry>,
    cellHeightPx: (HudEntry) -> Int,
    rowPitchPx: Int
): Set<HudPageCell> {
    val asGrid: Map<GridCell, Collection<HudEntry>> = residents.mapKeys { it.key.toGridCell() }
    return blockingCells(asGrid, self, mates, cellHeightPx, rowPitchPx).map { it.toPageCell() }.toSet()
}

/**
 * 页格**列**的存储层上限（P2 的 `G` 段钳制用）。
 *
 * 取"条目数 × 2"而不是手写一个魔法数：20 颗条目是这张表的物理上限，"每颗独占一列"的极端表也只要
 * 20 列，再留一倍余量给手改 prefs 造出的离谱列号。真正的"越区格"拦截在**运行时的**
 * [pageGridBoxOf]（安全区 ÷ 格长现算）——那一层才是权威，这一档只是存储层的宽容护栏，
 * 与 [gridColsCapOf] / [GridRowHardCap] 在 Dock 那一侧的分工同构（一个护栏一个上限，
 * 都不许变成"内容的函数"）。
 */
fun pageGridColCap(): Int = GridRowHardCap * 2

/**
 * 页格**行**的存储层上限（P2 的 `G` 段钳制用）：沿用 [GridRowHardCap] 的量级（= 条目总数）。
 * 本机横屏运行时只有 10 行（见 `HudPageGridTest` 的手算档），所以 20 行这个上限从来没被合法数据
 * 碰到过——它只管手改 prefs。值本身一个字不改（改它等于改 Dock 那一侧的同一道闸）。
 */
fun pageGridRowCap(): Int = GridRowHardCap
