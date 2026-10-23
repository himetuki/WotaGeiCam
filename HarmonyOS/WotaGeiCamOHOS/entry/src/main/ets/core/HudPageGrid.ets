/**
 * **整页网格**的坐标数学（纯函数层，全部能在 `test/pure` 里真跑）。
 *
 * 直译自上游 `ui/HudPageGrid.kt`（docs/plan/17 §六 P1 落地）。
 *
 * ## 页格与 Dock 内格**同源同尺**（拍板①甲案）
 * 页格边长 = 竖 Dock 那一档纵向格距：`hudChipHeightDp(fontScale) + 4dp（WotaSpace.xs）`，再乘 density
 * 向上取整（100% 档 34dp、120% 档 38dp，随系统字号一起涨）。页格与 Dock 内格大小相同但**坐标空间不同**
 * （页格原点 = 安全区左上角，Dock 内格原点 = 那枚底板），所以这一层用独立的 [HudPageCell] 而不是复用
 * [GridCell]——两个坐标空间混型正是「渲染格 ≠ 存储格」那一族自相矛盾表的入口。
 *
 * ## 与 HudLayout 的分工
 * [HudLayoutTable] 管五枚容器内的格；本文件管**整页**那一层。行跨度、占用判据这些已有件一律复用
 * （[blockingCells] / [cellRowSpan]），不在本文件造第二份算式——两份算式迟早分叉，
 * 那就是「预览画一格、松手落另一格」。
 *
 * ## 零平台 import
 * 只引 `core/HudLayout` 与 `core/HudMetrics`（都是纯逻辑）。上游这里引了一处 Compose 的 `WotaSpace.xs`
 * 令牌，本侧等价物是 [hudGridGapDp] 的 4dp 分支 + [hudChipHeightDp]，没有第二种写法。
 */
import {
  GridBox, GridCell, HudEntry, HudPointPx, HudZone, blockingCells, gridRowHardCap, hudGridGapDp
} from './HudLayout';
import { hudChipHeightDp } from './HudMetrics';

/**
 * 一颗条目在**页格**里的坐标（列 / 行，都从 0 起；页格原点 = 安全区左上角）。
 *
 * 与 [GridCell] **不是同一个类型**，尽管字段一模一样：Dock 内格与页格是两个坐标空间
 * （前者原点在那枚底板、列还可能贴右缘从右数；后者原点恒在安全区左上角、从左往右数）。
 * 需要复用吃 [GridCell] 的算式时，走 [pageCellToGridCell] / [gridCellToPageCell] 这一座显式的桥。
 *
 * [DEFAULT] 是哨兵，语义与 [GridCell.DEFAULT] 同源："这颗从没在页格上被摆过"。
 * [token] 给 P2 的 v3 `G` 段用（`<col>.<row>`，哨兵写 `-`，与 [GridCell.token] 同语法）。
 */
export class HudPageCell {
  readonly col: number;
  readonly row: number;

  constructor(col: number, row: number) {
    this.col = col;
    this.row = row;
  }

  /** 两轴都是哨兵 = 没被摆过 */
  get isDefault(): boolean {
    return this.col < 0 && this.row < 0;
  }

  /** 持久化 token（`0.2`）；哨兵写成 `-`（与 [GridCell.token] 同语法，P2 的 G 段直接复读） */
  get token(): string {
    return this.isDefault ? '-' : `${this.col}.${this.row}`;
  }

  equals(other: HudPageCell): boolean {
    return this.col === other.col && this.row === other.row;
  }

  static readonly DEFAULT: HudPageCell = new HudPageCell(-1, -1);
}

/** 页格坐标 → Dock 内格坐标的桥（同一对整数、两个坐标空间；**只在复用既有算式时用**） */
export function pageCellToGridCell(cell: HudPageCell): GridCell {
  return new GridCell(cell.col, cell.row);
}

/** Dock 内格坐标 → 页格坐标的桥（[pageCellToGridCell] 的逆） */
export function gridCellToPageCell(cell: GridCell): HudPageCell {
  return new HudPageCell(cell.col, cell.row);
}

/**
 * **页格边长**（px）= 与两枚竖 Dock 同源同尺的那一档格距。
 *
 * 直接复用 [hudChipHeightDp]（胶囊高）+ [hudGridGapDp] 的 Dock 档（4dp，**不是**读数块的 6dp
 * ——页格跟着 Dock 走）：
 * - 100% 档：(18 + 12 + 4) × 2 = **68px = 34dp**
 * - 120% 档：(21.6 + 12 + 4) × 2 = 75.2 → **76px = 38dp**
 * - 系统字号低于 100% 被 [hudChipHeightDp] 夹回 1f ⇒ 仍是 68px
 *
 * 为什么不复读一份 34：手抄的格长会在任何一次令牌调整后与渲染层分叉。三个消费方（编辑页画线、
 * 落点与渲染）必须读这一个函数。
 *
 * ⚠ J2 的 `gridRowPitchPx(fontScale, hudGridGapDp(LEFT), density)` 落位后，本函数改成它的调用；
 * 两条算式现在逐字同源（同一 `hudChipHeightDp` + 同一 4dp 档 + 同一 ceil），
 * 用例用两个独立算式对撞而不是让实现自己证自己。
 */
export function pageCellPx(fontScale: number, density: number): number {
  const pitch: number = Math.ceil((hudChipHeightDp(fontScale) + hudGridGapDp(HudZone.LEFT)) * density);
  return pitch > 1 ? pitch : 1;
}

/**
 * 安全区 → 页格盒（**列数 / 行数**，floor）。
 *
 * 末格不足一格的部分**弃掉，不补一个窄格**：格子是"固定宽高的定位单位"，落点算式
 * （[pageCellAtPointer]）与渲染格必须逐一对上。补一个窄格会让最后一格的宽度不等于 `cellPx`，
 * 于是"指针在这半格里"这件事在逆算式里给不出一个正确的列号。弃掉的那 ≤1 格在安全区边缘留成空白。
 *
 * `cellPx <= 0`（格长没算出来）时给 1×1：那是"这一帧还不能画/还不能落点"的退化态，
 * 与 [pageCellAtPointer] 在 `cellPx <= 0` 时返回 (0,0) 同族，不抛、不除零。
 */
export function pageGridBoxOf(safeWPx: number, safeHPx: number, cellPx: number): GridBox {
  if (cellPx <= 0) {
    return new GridBox(1, 1);
  }
  const cols: number = Math.floor(safeWPx / cellPx);
  const rows: number = Math.floor(safeHPx / cellPx);
  return new GridBox(cols > 1 ? cols : 1, rows > 1 ? rows : 1);
}

/**
 * 指针 → 它**压住的那一个页格**（[pageCellOffsetPx] 的逆）。
 *
 * 取"压住"（floor）而不是"中心最近"（round）：手指按在控件中心拖，控件中心落在哪一格就该是哪一格；
 * round 会让指针对在格子左缘那一瞬跳到左边一格，看着像吸附迟滞。
 * **没有 `colFromEnd` 支**：页格不钉列锚定（左缘恒贴安全区左边、列从左往右数），
 * 而贴右缘那枚 Dock 必须从右数——少一支不是因为懒，是页格没有锚定边。
 *
 * 越界钳制：负的局部坐标钳到 0；上界**不钳**——越出安全区的列号由调用方拿 [pageGridBoxOf] 的盒子钳，
 * 与 Dock 那一侧"索引函数不钳带、钳制是 `clampCellToBox` 的事"是同一条分工。
 */
export function pageCellAtPointer(
  pointerX: number, pointerY: number, originX: number, originY: number, cellPx: number
): HudPageCell {
  if (cellPx <= 0) {
    return new HudPageCell(0, 0);
  }
  const col: number = Math.floor((pointerX - originX) / cellPx);
  const row: number = Math.floor((pointerY - originY) / cellPx);
  return new HudPageCell(col > 0 ? col : 0, row > 0 ? row : 0);
}

/** 一个页格的左上角相对**页格原点**（px，[pageCellAtPointer] 的正算式） */
export function pageCellOffsetPx(cell: HudPageCell, cellPx: number): HudPointPx {
  const col: number = cell.col > 0 ? cell.col : 0;
  const row: number = cell.row > 0 ? cell.row : 0;
  return new HudPointPx(col * cellPx, row * cellPx);
}

/**
 * 页格占用判据：**哪些档被占**（挡不挡 [self] 的路），判据整块不相交。
 *
 * 就是 `blockingCells` 的页格版：行人复用 [blockingCells]（含 #75 那条"高格连它压住的
 * `row+1 .. row+span−1` 一起算占"与 mate 感知的两条口径），只在两侧过
 * [pageCellToGridCell] / [gridCellToPageCell] 这一座桥。**不在本文件另写一份占用逻辑**——
 * 两套判据分叉时，编辑页预览说"这格有人"而写表说"没人"。
 *
 * `residents` 的键是**锚点格的 token**（与 [GridCell.token] 同语法），值是锚点格里的条目；
 * self 为 null 是"有人就算占"的旧口径，mates 是同组搭档的**条目 id** 集。
 * 返回值是被占档的 token 集（页格 token 与 Dock 格 token 同语法，桥两侧逐字相同）。
 */
export function pageCellsBlocked(
  residents: Map<string, Array<HudEntry>>,
  self: HudEntry | null,
  mates: Set<string>,
  cellHeightPx: (entry: HudEntry) => number,
  rowPitchPx: number
): Set<string> {
  return blockingCells(residents, self, mates, cellHeightPx, rowPitchPx);
}

/**
 * 页格**列**的存储层上限（P2 的 `G` 段钳制用）。
 *
 * 取"条目数 × 2"而不是手写一个魔法数：20 颗条目是这张表的物理上限，"每颗独占一列"的极端表也只要
 * 20 列，再留一倍余量给手改 prefs 造出的离谱列号。真正的"越区格"拦截在**运行时的**
 * [pageGridBoxOf]（安全区 ÷ 格长现算）——那一层才是权威，这一档只是存储层的宽容护栏。
 */
export function pageGridColCap(): number {
  return gridRowHardCap() * 2;
}

/**
 * 页格**行**的存储层上限（P2 的 `G` 段钳制用）：沿用 [gridRowHardCap] 的量级（= 条目总数）。
 * 本机横屏运行时只有 10 行，所以 20 行这个上限从来没被合法数据碰到过——它只管手改 prefs。
 */
export function pageGridRowCap(): number {
  return gridRowHardCap();
}
