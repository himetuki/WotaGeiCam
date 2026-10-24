/**
 * 「编辑控件」页与录制页共用的位置模型（纯逻辑）。
 *
 * J1 直译上游 `ui/HudLayout.kt` 的**类型 + 序列化 + 夹取/落区**子集；
 * J2 在同文件补齐**网格几何、拖放落点、表方法与读数行规划**（`gridBoxOf` / `clampCellToBox` /
 * `cellDropOf` / `freeCellNear` / `resolveCells` / `defaultCellsOf` / `gridPitchPx` /
 * `gridRowPitchPx` / `placeEntryAt` / `planReadoutRow` …），逐式等价上游 2252 行里的可纯逻辑面。
 * J1 已交付「位置表能不能安全存下来、拖出屏幕时钳到哪」这一层。
 *
 * ## 两级：容器位置 + 条目格子
 * - **容器整体**（[HudZone]，5 枚）各一份 (x, y)——**底栏只有 y 一轴**（x 恒哨兵）；
 * - **容器内的每一颗条目**各一个 [GridCell]（列 / 行）。三枚容器走网格
 *   （[HudZone.LEFT] / [HudZone.RIGHT] / [HudZone.READOUT]），空格子就空着。
 *
 * ## 默认值走「推导」而不是手抄坐标
 * [GridCell.DEFAULT] 是哨兵，语义＝"这颗从没被摆过 ⇒ 回落到推导格"（[defaultCellsOf] /
 * [resolveCells]，J2 已落）。J2 起 `gridItems` / `placeEntryAt` / `moveEntryTo` / `pinned` /
 * `occupiedCells` / `residentsOf` 也已在本文件（推导与落点裁决齐备后的完整语义）；
 * J3b 的顺序编辑器走更轻的 [HudLayoutTable.withZoneOrder]（同容器、无格子钉扎）。
 * 仍未移植：上游「编辑控件」页的自由拖拽 UI 本体（`HudLayoutEditor`，本侧登记为设计性偏离，
 * 见 PORT_MAP J3b 节）。
 *
 * ## 持久化契约（`hud_layout`）
 * [HudLayoutTable.encode] / [HudLayoutTable.decode] 的输出串**必须与上游逐字节一致**：
 * 分号分「版本段 / 五个容器段」，逗号分「x / y / id 串」，网格容器每颗带 `:格子` 后缀，
 * 哨兵格子写 `-`。键名 `hud_layout` 由调用方（J3 的编辑器 UI）持有；本模块只管串。
 *
 * ## ArkTS 口径（与 Kotlin 的唯一结构差异）
 * ArkTS 没有结构相等，Map/Set 的键必须是原始值。所以：
 * - 容器的 Map 键 = [HudZone] 的字符串枚举值（`'T'`/`'L'`/`'R'`/`'D'`/`'B'`）；
 * - 条目的 Map/Set 键 = 条目 id（`'P3'`/`'H6'`，与持久化 token 同一串）；
 * - 格子的 Map/Set 键 = [GridCell.token]（`'0.2'`，哨兵 `'-'`）。
 * 这些都是持久化里已有的串，不新造编码。
 *
 * ## 本文件是纯逻辑
 * 零 `@ohos.`/`@kit.` import，schema 编解码、越界钳制、底栏 y 落位全部能在 `test/pure` 里真跑
 * （`hud-layout.test.ets` 直译上游 `HudLayoutClampTest` + `HudLayoutCodecTest` 的可移植面）。
 */
import { CamPill, CamPills, HudItem, HudItems } from './Constants';
import {
  HUD_BLOCK_PAD_DP, HUD_ROW_GAP_DP, hudChipHeightDp, hudPerRowFor, hudRoomDp
} from './HudMetrics';

/** 条目在容器内的排布主轴：决定「拖到第几格」怎么算（J2 的落点裁决消费） */
export enum HudAxis {
  ROW = 'ROW',
  COLUMN = 'COLUMN',
  GRID = 'GRID'
}

/**
 * 可拖动的容器。字符串枚举值就是持久化里的单字母 id（`'T'`/`'L'`/`'R'`/`'D'`/`'B'`）——
 * 改它等于改存量配置语义，只能加不能改。
 *
 * 每枚容器的**默认位置**不写死坐标，而是「原生对齐」那条分支（见 [ZonePlacement.isDefault]）：
 * 默认值＝B1–B3 定稿时的对齐方式与内边距，由组合期实测避让量算出来。
 *
 * `isGrid` = 这一枚容器的条目是否走「固定格子」。**本批只开三枚**（[HudZone.LEFT] /
 * [HudZone.RIGHT] / [HudZone.READOUT]），另两枚**故意不上网格**：
 * - [HudZone.BOTTOM] 那枚底板有「快门中心＝可视窗口水平中心」的 `W/2` 不变量，条目一旦离开
 *   "按顺序紧凑排布"就没有等宽槽可言，#71 收拢动画的起点 `w(0)` 跟着塌；
 * - [HudZone.TOP] 的宽度账与容量段三档取位是按**段数增减**重标的，改成一格一颗会把那三档判据作废。
 * 这两枚继续用 order 紧凑排布；[GridCell] 对它们不生效（[HudLayoutTable.normalize] 与编码两处
 * 都把挂在非网格容器上的条目格子抹成哨兵，不留脏数据）。
 */
export enum HudZone {
  /** 顶栏胶囊组：录制计时/状态那颗 + 画幅/容量段（**保持有序列表模型**） */
  TOP = 'T',
  /** 左竖 Dock：创作项 */
  LEFT = 'L',
  /** 右竖 Dock：取景辅助与变焦/对焦/防抖 */
  RIGHT = 'R',
  /** 右下常驻读数块（六项第 4 条搬到录制键右侧那一块） */
  READOUT = 'D',
  /** 底栏 Dock：缩略图 + 快门 + 镜头那颗（**保持有序列表模型**）；x 恒哨兵 */
  BOTTOM = 'B'
}

/** 五枚容器的声明顺序（= 编码段顺序 = 上游 `HudZone.values()`） */
const HUD_ZONES: Array<HudZone> = [
  HudZone.TOP, HudZone.LEFT, HudZone.RIGHT, HudZone.READOUT, HudZone.BOTTOM
];

/** 全表（副本，调用方改不到本模块的顺序真源） */
export function hudZoneAll(): Array<HudZone> {
  return HUD_ZONES.slice();
}

/** 持久化里的单字母 id（与枚举值同一串；抽成函数是为了别让调用方散写 `zone as string`） */
export function hudZoneKey(zone: HudZone): string {
  return zone;
}

/** 容器排布主轴 */
export function hudZoneAxis(zone: HudZone): HudAxis {
  if (zone === HudZone.LEFT || zone === HudZone.RIGHT) {
    return HudAxis.COLUMN;
  }
  if (zone === HudZone.READOUT) {
    return HudAxis.GRID;
  }
  return HudAxis.ROW;
}

/** 这一枚容器的条目是否走固定格子（本批三枚；理由见 [HudZone]） */
export function hudZoneIsGrid(zone: HudZone): boolean {
  return zone === HudZone.LEFT || zone === HudZone.RIGHT || zone === HudZone.READOUT;
}

/** 坏串 / 未知容器 id 返回 undefined（调用方丢那一段而不是抛） */
export function hudZoneFromKey(raw: string | undefined): HudZone | undefined {
  if (raw === undefined) {
    return undefined;
  }
  for (const zone of HUD_ZONES) {
    if (hudZoneKey(zone) === raw) {
      return zone;
    }
  }
  return undefined;
}

/** 一颗条目的命名空间：`CamPill`（控件胶囊）与 `HudItem`（读数）是两个东西，编码必须分字母 */
export enum HudEntryKind {
  PILL = 'PILL',
  READOUT = 'READOUT'
}

/**
 * 一颗可编辑控件的引用。
 *
 * 两个命名空间必须分开编码：`CamPill.ZOOM`（右竖 Dock 那颗胶囊）与 `HudItem.ZOOM`（读数块那颗读数）
 * 是**两个东西**，共用 ordinal 会让持久化串自相矛盾。所以 id = 字母前缀 + ordinal：`P<n>` / `H<n>`，
 * ordinal 是该命名空间 `ALL` 清单里的**下标**（不是枚举位值——HudItem 的位值是 1/2/4/…）。
 */
export class HudEntry {
  readonly kind: HudEntryKind;
  readonly ordinal: number;

  constructor(kind: HudEntryKind, ordinal: number) {
    this.kind = kind;
    this.ordinal = ordinal;
  }

  /** 对应 CamPill 开关位；READOUT 或越界 ordinal 返回 undefined */
  get pill(): CamPill | undefined {
    return this.kind === HudEntryKind.PILL ? CamPills.ALL[this.ordinal] : undefined;
  }

  /** 对应 HudItem 开关位；PILL 或越界 ordinal 返回 undefined */
  get item(): HudItem | undefined {
    return this.kind === HudEntryKind.READOUT ? HudItems.ALL[this.ordinal] : undefined;
  }

  /** 持久化 token；越界 ordinal 仍拼得出串，由 [HudEntry.fromId] 拒绝 */
  get id(): string {
    return (this.kind === HudEntryKind.PILL ? 'P' : 'H') + this.ordinal;
  }

  /** 名称资源键（走 [CamPills.labelKey] / [HudItems.labelKey]）；ordinal 越界返回 undefined */
  get labelKey(): string | undefined {
    const p: CamPill | undefined = this.pill;
    if (p !== undefined) {
      return CamPills.labelKey(p);
    }
    const i: HudItem | undefined = this.item;
    return i === undefined ? undefined : HudItems.labelKey(i);
  }

  /** 坏条目（ordinal 越界）为 false；[HudLayoutTable.normalize] 会把它们丢掉 */
  get isValid(): boolean {
    return this.pill !== undefined || this.item !== undefined;
  }

  /** 未编辑时的归属容器 = B1–B3 定稿位置（认枚举名而不是位值，新增一颗时这条逼着当场定容器） */
  get homeZone(): HudZone {
    if (this.kind === HudEntryKind.READOUT) {
      return HudZone.READOUT;
    }
    const p: CamPill | undefined = this.pill;
    if (p === undefined) {
      return HudZone.RIGHT;
    }
    if (p === CamPill.SIZE) {
      return HudZone.TOP;
    }
    if (p === CamPill.STORAGE) {
      return HudZone.TOP;
    }
    if (p === CamPill.LENS) {
      return HudZone.BOTTOM;
    }
    if (p === CamPill.REFLINE || p === CamPill.MONITOR || p === CamPill.CURVE || p === CamPill.FLASH) {
      return HudZone.LEFT;
    }
    return HudZone.RIGHT;
  }

  equals(other: HudEntry): boolean {
    return this.kind === other.kind && this.ordinal === other.ordinal;
  }

  /** 全部可编辑条目 = 13 颗 CamPill + 7 颗 HudItem；**顺序即持久化里的稳定顺序** */
  static readonly ALL: Array<HudEntry> = HudEntry.buildAll();

  private static buildAll(): Array<HudEntry> {
    const out: Array<HudEntry> = [];
    for (let i = 0; i < CamPills.ALL.length; i++) {
      out.push(new HudEntry(HudEntryKind.PILL, i));
    }
    for (let i = 0; i < HudItems.ALL.length; i++) {
      out.push(new HudEntry(HudEntryKind.READOUT, i));
    }
    return out;
  }

  static ofPill(pill: CamPill): HudEntry {
    return new HudEntry(HudEntryKind.PILL, CamPills.ALL.indexOf(pill));
  }

  static ofItem(item: HudItem): HudEntry {
    return new HudEntry(HudEntryKind.READOUT, HudItems.ALL.indexOf(item));
  }

  /** 坏串（手改 prefs、ordinal 越界、缺前缀）返回 undefined，由调用方丢弃而不是抛 */
  static fromId(raw: string): HudEntry | undefined {
    if (raw.length < 2) {
      return undefined;
    }
    let kind: HudEntryKind;
    const head: string = raw.substring(0, 1);
    if (head === 'P') {
      kind = HudEntryKind.PILL;
    } else if (head === 'H') {
      kind = HudEntryKind.READOUT;
    } else {
      return undefined;
    }
    const n: number | undefined = intOrUndefined(raw.substring(1));
    if (n === undefined) {
      return undefined;
    }
    const size: number = kind === HudEntryKind.PILL ? CamPills.ALL.length : HudItems.ALL.length;
    return n >= 0 && n < size ? new HudEntry(kind, n) : undefined;
  }
}

/**
 * 一枚容器的位置。
 *
 * 默认态 = **两轴都是哨兵**。"一轴绝对 + 一轴哨兵"里只有**底栏**是合法状态：它的 x 恒为哨兵
 * （横向永远跟着可视窗口居中，[clampZonePos] 与 [HudLayoutTable.normalize] 两处都把它抹成哨兵），
 * 长按换栏只写 y。其余四枚容器的半吊子串只能来自手改 prefs，一律按未编辑处理。
 *
 * 绝对值是**容器左上角相对 root 安全区左上角**的 dp 偏移；存绝对值才谈得上「钳进安全区」，
 * 也才让编辑页与录制页对同一个数给出同一个视觉位置。
 */
export class ZonePlacement {
  readonly xDp: number;
  readonly yDp: number;

  constructor(xDp: number, yDp: number) {
    this.xDp = xDp;
    this.yDp = yDp;
  }

  /** 两轴都没绝对值 = 用户没把这枚容器拖离过定稿位置（底栏的 y-only 落位**不是**默认态） */
  get isDefault(): boolean {
    return this.xDp < 0 && this.yDp < 0;
  }

  equals(other: ZonePlacement): boolean {
    return this.xDp === other.xDp && this.yDp === other.yDp;
  }

  static readonly DEFAULT: ZonePlacement = new ZonePlacement(-1, -1);
}

/**
 * 一颗条目在容器**格网**里的坐标（列 / 行，都从 0 起）。
 *
 * [DEFAULT] 是哨兵，语义＝"**这颗从没被摆过** ⇒ 回落到由推导函数算出的那一格"（推导在 J2）。
 * 格子是"位置单位"而不是"条目单位"：一枚格子可以住同属一个默认行组的多颗（右 Dock 那对
 * S2-2B 并排「姿态仪 + 音量表」），跨组共格一律算坏数据。
 *
 * ## 格子**没有 rowSpan 字段**
 * 高条目吃掉几档行距由**实测内容高 ÷ 格距现算**（[cellRowSpan]），不是存进来的第 3 个坐标：
 * 跨度是内容的函数（字体 120%、文案变长、被挪去别格都会变），存下来就是再造一份会分叉的真源；
 * 而且跨度**不该**由用户摆——用户摆的是格子。代价照实写：跨度要由调用方（渲染层与编辑页都量得到
 * 实测高）喂进来，那些形参**一律没有默认值**（#69 铁律）。
 */
export class GridCell {
  readonly col: number;
  readonly row: number;

  constructor(col: number, row: number) {
    this.col = col;
    this.row = row;
  }

  /** 没被摆过（两轴都是哨兵）= 用推导出来的默认格子 */
  get isDefault(): boolean {
    return this.col < 0 && this.row < 0;
  }

  /** 持久化里的格子后缀（`0.2`）；哨兵写成 `-` —— 它就是 Map/Set 的键 */
  get token(): string {
    return this.isDefault ? '-' : `${this.col}.${this.row}`;
  }

  equals(other: GridCell): boolean {
    return this.col === other.col && this.row === other.row;
  }

  static readonly DEFAULT: GridCell = new GridCell(-1, -1);
}

/** 一枚容器的完整状态。 */
export class ZoneState {
  readonly pos: ZonePlacement;
  /**
   * 容器内条目的**稳定次序**。它只管三件事，**不是**屏幕位置的来源：
   * ① 没摆过的条目按它推导默认格子（J2）；② 同一格子里叠放时的先后；
   * ③ `visibleOrderOf` 那套"隐藏再显示不回默认位置"依赖它。
   */
  readonly order: Array<HudEntry>;
  /**
   * 每颗条目**被用户摆过**的格子（键 = 条目 id）。缺席（或值为 [GridCell.DEFAULT]）＝没摆过。
   *
   * 选 Map 而不是"与 order 并列的数组"：并列数组的下标就是 order 的下标，而 order 会在跨容器
   * 移动时增删——下标一生变，格子就跟着整体错位。表里也**只存摆过的**（默认格不落表）。
   */
  readonly cells: Map<string, GridCell>;

  constructor(pos: ZonePlacement, order: Array<HudEntry>, cells: Map<string, GridCell>) {
    this.pos = pos;
    this.order = order;
    this.cells = cells;
  }
}

/**
 * 位置表本体（版本化）。持久化键 `hud_layout`，编解码见 [HudLayoutTable.encode] / [HudLayoutTable.decode]。
 */
export class HudLayoutTable {
  readonly version: number;
  /** 键 = [hudZoneKey]（`'T'`/`'L'`/…），不是对象——ArkTS 的 Map 键必须是原始值 */
  readonly zones: Map<string, ZoneState>;

  constructor(version: number, zones: Map<string, ZoneState>) {
    this.version = version;
    this.zones = zones;
  }

  posOf(zone: HudZone): ZonePlacement {
    const s: ZoneState | undefined = this.zones.get(hudZoneKey(zone));
    return s === undefined ? ZonePlacement.DEFAULT : s.pos;
  }

  /** 该容器的完整顺序（含被设置页关掉的条目）；返回副本，调用方改不到表 */
  orderOf(zone: HudZone): Array<HudEntry> {
    const s: ZoneState | undefined = this.zones.get(hudZoneKey(zone));
    return s === undefined ? [] : s.order.slice();
  }

  /**
   * 录制页/编辑页真正渲染的那份顺序：visible（设置里开着显示的条目 **id** 集）过滤一遍，
   * **顺序仍来自表**。「控件被隐藏、再打开显示时位置保持用户改过的值」就落在这里。
   */
  visibleOrderOf(zone: HudZone, visible: Set<string>): Array<HudEntry> {
    return this.orderOf(zone).filter((e: HudEntry): boolean => visible.has(e.id));
  }

  /**
   * 写一枚容器的绝对位置。
   *
   * 底栏那枚**只该传 y**（x 传哨兵 -1）：写入方是录制页的长按换栏与编辑页的整枚拖动，
   * 两处都只搬上下栏，写进绝对 x 就等于把快门横向钉死。越界钳制由调用方先做
   * （[clampZonePos] 会把底栏的 x 强制抹回哨兵，调用方就算传了真值也存不进去）。
   */
  withZonePos(zone: HudZone, xDp: number, yDp: number): HudLayoutTable {
    const next: Map<string, ZoneState> = new Map<string, ZoneState>();
    this.zones.forEach((state: ZoneState, key: string): void => {
      next.set(key, state);
    });
    const key: string = hudZoneKey(zone);
    const cur: ZoneState | undefined = this.zones.get(key);
    const state: ZoneState = cur === undefined
      ? new ZoneState(ZonePlacement.DEFAULT, [], new Map<string, GridCell>())
      : cur;
    next.set(key, new ZoneState(new ZonePlacement(xDp, yDp), state.order, state.cells));
    return new HudLayoutTable(this.version, next);
  }

  /**
   * 只改一枚容器内的条目顺序（位置与格子一律不动）。J3b 读数顺序编辑器的写入口：
   * 编辑器只在 READOUT 容器内做「上移/下移」，没有跨容器搬运、也没有格子钉扎需求，
   * 所以不走 [moveEntryTo] 的完整四步（那套要 HudGridPlan 与落点裁决），直接重建
   * ZoneState 后过一遍 [normalize]（非法/重复条目收口，格子照旧保留）。
   * [order] 应是**同一容器条目的一个排列**（缺的会被 `normalize` 补回、多的会被丢弃）。
   */
  withZoneOrder(zone: HudZone, order: Array<HudEntry>): HudLayoutTable {
    const next: Map<string, ZoneState> = new Map<string, ZoneState>();
    this.zones.forEach((state: ZoneState, key: string): void => {
      next.set(key, state);
    });
    const key: string = hudZoneKey(zone);
    const cur: ZoneState | undefined = this.zones.get(key);
    const state: ZoneState = cur === undefined
      ? new ZoneState(ZonePlacement.DEFAULT, [], new Map<string, GridCell>())
      : cur;
    next.set(key, new ZoneState(state.pos, order.slice(), state.cells));
    return new HudLayoutTable(this.version, next).normalize();
  }

  /** 该容器里**被用户摆过**的格子（键 = 条目 id；缺席或哨兵＝用推导格）；返回副本 */
  cellsOf(zone: HudZone): Map<string, GridCell> {
    const s: ZoneState | undefined = this.zones.get(hudZoneKey(zone));
    const out: Map<string, GridCell> = new Map<string, GridCell>();
    if (s !== undefined) {
      s.cells.forEach((cell: GridCell, id: string): void => {
        out.set(id, cell);
      });
    }
    return out;
  }

  /** [cellsOf] 去掉哨兵与坏值后的净表（写回串里用的就是这一份） */
  private placedCellsOf(zone: HudZone): Map<string, GridCell> {
    const out: Map<string, GridCell> = new Map<string, GridCell>();
    const cells: Map<string, GridCell> = this.cellsOf(zone);
    const inOrder: Set<string> = new Set<string>();
    for (const e of this.orderOf(zone)) {
      inOrder.add(e.id);
    }
    cells.forEach((cell: GridCell, id: string): void => {
      if (!cell.isDefault && inOrder.has(id)) {
        out.set(id, cell);
      }
    });
    return out;
  }

  /**
   * 这一枚容器**这一帧真正渲染**的格子清单（顺序＝[visibleOrderOf] 的顺序）。
   *
   * 两趟解析，规则只有一条：**一枚格子只许住"同一个默认组"的颗**（今天只有 S2-2B 那对
   * 「姿态仪 + 音量表」，见 [cellGroupMatesOf]）——跨组撞进同一格由 [resolveCells] 就近挪开。
   */
  gridItems(zone: HudZone, plan: HudGridPlan): Array<HudGridItem> {
    const order: Array<HudEntry> = this.visibleOrderOf(zone, plan.visible);
    if (!hudZoneIsGrid(zone)) {
      return order.map((entry: HudEntry): HudGridItem => new HudGridItem(entry, GridCell.DEFAULT));
    }
    return resolveCells(
      order,
      this.placedCellsOf(zone),
      defaultCellsOf(zone, order, plan),
      gridColsCapOf(zone),
      gridRowHardCap(),
      plan.cellHeightOf,
      plan.rowPitchOf(zone)
    );
  }

  /**
   * 这一枚容器**默认表**的「条目 id → 格子」（任务 #80：锚定与预留的唯一来源）。
   * 与 [gridItems] 的区别就一件事：它**不看用户摆过的格子**，只按可见集推导。
   */
  defaultGridOf(zone: HudZone, plan: HudGridPlan): Map<string, GridCell> {
    if (!hudZoneIsGrid(zone)) {
      return new Map<string, GridCell>();
    }
    return defaultCellsOf(zone, this.visibleOrderOf(zone, plan.visible), plan);
  }

  /**
   * 该容器**当前挡得住一颗**的格子（可见条目按 [gridItems] 解析结果，隐藏条目按表里的显式格子）。
   * 隐藏条目也算占用：它只是这帧不画，格子还是它的位置。
   *
   * 返回被占**档**的 token 集（`'0.2'` 形态；ArkTS 无结构相等的适配）。
   * `exclude` 为 null 是"格子里有人就算占"的旧口径。
   */
  occupiedCells(zone: HudZone, plan: HudGridPlan, exclude: HudEntry | null = null): Set<string> {
    if (!hudZoneIsGrid(zone)) {
      return new Set<string>();
    }
    let mates: Set<string> = new Set<string>();
    if (exclude !== null) {
      mates = cellGroupMatesOf(this.defaultGridOf(zone, plan), exclude);
    }
    return blockingCells(
      this.residentsOf(zone, plan),
      exclude,
      mates,
      plan.cellHeightOf,
      plan.rowPitchOf(zone)
    );
  }

  /**
   * 这一枚容器**这一帧的住户表**：锚点格 token → 住在那里的条目。
   * 可见的那份来自渲染解析结果，隐藏的那份来自表里的显式格。
   * [occupiedCells] 的"挡路集"与 [placeEntryAt] 的"同格交换"**共读这一份**。
   */
  residentsOf(zone: HudZone, plan: HudGridPlan): Map<string, Array<HudEntry>> {
    const out: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
    if (!hudZoneIsGrid(zone)) {
      return out;
    }
    for (const item of this.gridItems(zone, plan)) {
      putResident(out, item.cell, item.entry);
    }
    const placed: Map<string, GridCell> = this.placedCellsOf(zone);
    placed.forEach((cell: GridCell, id: string): void => {
      if (plan.visible.has(id)) {
        return;
      }
      const entry: HudEntry | undefined = HudEntry.fromId(id);
      if (entry === undefined) {
        return;
      }
      putResident(out, cell, entry);
    });
    return out;
  }

  /**
   * 把 [zones] 里这些网格容器的**推导格固化成显式格子**（只写格子，不改任何人的位置）。
   *
   * 这是"移动一颗不动另一颗"的实现核心：[order] 一旦被增删，还挂着哨兵的条目会按**新的**可见
   * 顺序重新推导 ⇒ 集体错位。所以任何改动 [order] 的操作之前，先把来源与目标两枚容器的当前格子
   * 原样钉住。钉的是**格子**不是"每颗各一格"：配对那两颗钉完仍共用同一枚格子。
   *
   * ArkTS 适配：上游是 `vararg zones`，这里收数组（调用方只有两处，显式传 `[source, target]`）。
   */
  pinned(plan: HudGridPlan, zones: Array<HudZone>): HudLayoutTable {
    let table: HudLayoutTable = this;
    const done: Set<string> = new Set<string>();
    for (const zone of zones) {
      if (!hudZoneIsGrid(zone) || done.has(hudZoneKey(zone))) {
        continue;
      }
      done.add(hudZoneKey(zone));
      const items: Array<HudGridItem> = table.gridItems(zone, plan);
      if (items.length > 0) {
        const merged: Map<string, GridCell> = new Map<string, GridCell>();
        table.placedCellsOf(zone).forEach((cell: GridCell, id: string): void => {
          merged.set(id, cell);
        });
        for (const item of items) {
          merged.set(item.entry.id, item.cell);
        }
        table = table.replaceCells(zone, merged);
      }
    }
    return table;
  }

  /** 换掉一枚容器的格子表，其余字段（位置与顺序）一动不动 */
  private replaceCells(zone: HudZone, cells: Map<string, GridCell>): HudLayoutTable {
    const next: Map<string, ZoneState> = new Map<string, ZoneState>();
    this.zones.forEach((state: ZoneState, key: string): void => {
      next.set(key, state);
    });
    const key: string = hudZoneKey(zone);
    const cur: ZoneState | undefined = this.zones.get(key);
    const state: ZoneState = cur === undefined
      ? new ZoneState(ZonePlacement.DEFAULT, HudLayoutTable.defaultOrderOf(zone), new Map<string, GridCell>())
      : cur;
    next.set(key, new ZoneState(state.pos, state.order, cells));
    return new HudLayoutTable(this.version, next);
  }

  /**
   * 把条目摆到 [target] 的第 [cell] 格（同容器换格 = 同一个调用；[cell] 传 [GridCell.DEFAULT]
   * 就是"只换顺序、位置交给推导"，[moveEntryTo] 走的就是这一支）。
   *
   * 四步：① 先钉格子（来源与目标两枚容器）；② 再改顺序（从**所有**容器摘掉它、插到目标第 [index]）；
   * ③ 格子落位（非网格 ⇒ 哨兵；同容器 ⇒ [cellDropOf] 交换语义；跨容器 ⇒ [freeCellNear] 只落空格）；
   * ④ 其余三枚容器一行不碰。
   */
  placeEntryAt(
    entry: HudEntry,
    target: HudZone,
    index: number,
    cell: GridCell,
    plan: HudGridPlan
  ): HudLayoutTable {
    const source: HudZone = this.sourceZoneOf(entry);
    const pinnedTable: HudLayoutTable = this.pinned(plan, [source, target]);
    const orders: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
    for (const z of HUD_ZONES) {
      const state: ZoneState | undefined = pinnedTable.zones.get(hudZoneKey(z));
      const list: Array<HudEntry> = state === undefined
        ? HudLayoutTable.defaultOrderOf(z)
        : state.order.slice();
      const at: number = list.findIndex((x: HudEntry): boolean => x.id === entry.id);
      if (at >= 0) {
        list.splice(at, 1);
      }
      orders.set(hudZoneKey(z), list);
    }
    const targetKey: string = hudZoneKey(target);
    const targetOrder: Array<HudEntry> | undefined = orders.get(targetKey);
    if (targetOrder !== undefined) {
      const at: number = index < 0 ? 0 : (index > targetOrder.length ? targetOrder.length : index);
      targetOrder.splice(at, 0, entry);
    }
    let drop: CellDrop;
    if (!hudZoneIsGrid(target)) {
      // 非网格容器：格子对它不生效，一律抹成哨兵（[normalize] 那一道同样会抹）
      drop = new CellDrop(GridCell.DEFAULT, new Map<string, GridCell>());
    } else if (cell.isDefault) {
      // 显式传哨兵 = "这次只换顺序，位置仍交给推导"（[moveEntryTo] 走的就是这一支）
      drop = new CellDrop(GridCell.DEFAULT, new Map<string, GridCell>());
    } else if (target === source) {
      let origin: GridCell = GridCell.DEFAULT;
      for (const item of pinnedTable.gridItems(target, plan)) {
        if (item.entry.id === entry.id) {
          origin = item.cell;
          break;
        }
      }
      drop = cellDropOf(
        clampStoredCell(target, cell),
        origin,
        pinnedTable.residentsOf(target, plan),
        entry,
        cellGroupMatesOf(pinnedTable.defaultGridOf(target, plan), entry),
        pinnedTable.droppedCellHeightPx(plan, entry, target),
        plan.cellHeightOf,
        gridColsCapOf(target),
        gridRowHardCap(),
        plan.rowPitchOf(target)
      );
    } else {
      // 跨容器 ⇒ 维持"只落空格"：没有"你的原格"这一说，不许写出越区格
      drop = new CellDrop(
        freeCellNear(
          clampStoredCell(target, cell),
          pinnedTable.occupiedCells(target, plan, entry),
          gridColsCapOf(target),
          gridRowHardCap(),
          pinnedTable.droppedCellHeightPx(plan, entry, target),
          plan.rowPitchOf(target)
        ),
        new Map<string, GridCell>()
      );
    }
    const nextZones: Map<string, ZoneState> = new Map<string, ZoneState>();
    for (const z of HUD_ZONES) {
      const key: string = hudZoneKey(z);
      const state: ZoneState | undefined = pinnedTable.zones.get(key);
      const base: ZoneState = state === undefined
        ? new ZoneState(ZonePlacement.DEFAULT, HudLayoutTable.defaultOrderOf(z), new Map<string, GridCell>())
        : state;
      const cells: Map<string, GridCell> = new Map<string, GridCell>();
      base.cells.forEach((c: GridCell, id: string): void => {
        if (id !== entry.id) {
          cells.set(id, c);
        }
      });
      if (z === target && !drop.dropped.isDefault) {
        cells.set(entry.id, drop.dropped);
        drop.moved.forEach((c: GridCell, id: string): void => {
          cells.set(id, c);
        });
      }
      const order: Array<HudEntry> | undefined = orders.get(key);
      nextZones.set(key, new ZoneState(base.pos,
        order === undefined ? [] : order, cells));
    }
    return new HudLayoutTable(this.version, nextZones).normalize();
  }

  /**
   * 被拖那颗落到目标容器之后**这一格的内容高**（px）：取"它自己"与"它同组搭档"里最高的那个。
   * 只在 [freeCellNear] 的"块尾不许出带"判据里用一次，不参与任何人的位置推导。
   */
  droppedCellHeightPx(plan: HudGridPlan, entry: HudEntry, zone: HudZone): number {
    const own: number = plan.cellHeightOf(entry);
    let best: number = own > 0 ? own : 0;
    const mates: Set<string> = cellGroupMatesOf(
      defaultCellsOf(zone, this.visibleOrderOf(zone, plan.visible), plan), entry
    );
    mates.forEach((id: string): void => {
      const mate: HudEntry | undefined = HudEntry.fromId(id);
      if (mate !== undefined) {
        const h: number = plan.cellHeightOf(mate);
        if (h > best) {
          best = h;
        }
      }
    });
    return best;
  }

  /**
   * 把条目挪到 [zone] 的第 [index] 格（同容器换序 = 同一个调用），**位置交给推导**。
   * #74 之后它只是 [placeEntryAt] 的哨兵格版本；它同时负责"把来源容器钉住"这件必要的事。
   */
  moveEntryTo(entry: HudEntry, zone: HudZone, index: number, plan: HudGridPlan): HudLayoutTable {
    return this.placeEntryAt(entry, zone, index, GridCell.DEFAULT, plan);
  }

  /** 条目当前归属（表里没有时回 [HudEntry.homeZone]） */
  sourceZoneOf(entry: HudEntry): HudZone {
    for (const zone of HUD_ZONES) {
      for (const e of this.orderOf(zone)) {
        if (e.id === entry.id) {
          return zone;
        }
      }
    }
    return entry.homeZone;
  }

  /** 整张表的条目（按容器分组、容器按 [hudZoneAll] 顺序） */
  allEntries(): Array<HudEntry> {
    const out: Array<HudEntry> = [];
    for (const zone of HUD_ZONES) {
      for (const e of this.orderOf(zone)) {
        out.push(e);
      }
    }
    return out;
  }

  /**
   * 补全 + 去重 + 丢坏值：
   * - 同一个 id 出现在两枚容器 → **先到先得**，后出现的丢掉；
   * - 表里完全没有的条目 → 追加到它的 [HudEntry.homeZone] 末尾（新增 CamPill 位时老配置自动补齐）；
   * - ordinal 越界的条目 → 丢弃；
   * - 格子表跟着条目走：丢了的人的格子一起丢，非网格容器上的格子一律抹成缺席。
   */
  normalize(): HudLayoutTable {
    const seen: Set<string> = new Set<string>();
    const placed: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
    const placedCells: Map<string, Map<string, GridCell>> = new Map<string, Map<string, GridCell>>();
    for (const zone of HUD_ZONES) {
      placed.set(hudZoneKey(zone), []);
      placedCells.set(hudZoneKey(zone), new Map<string, GridCell>());
    }
    for (const zone of HUD_ZONES) {
      const key: string = hudZoneKey(zone);
      const cells: Map<string, GridCell> = this.cellsOf(zone);
      for (const e of this.orderOf(zone)) {
        if (!e.isValid) {
          continue;
        }
        if (seen.has(e.id)) {
          continue;
        }
        seen.add(e.id);
        const kept: Array<HudEntry> | undefined = placed.get(key);
        if (kept !== undefined) {
          kept.push(e);
        }
        if (hudZoneIsGrid(zone)) {
          const cell: GridCell | undefined = cells.get(e.id);
          const target: Map<string, GridCell> | undefined = placedCells.get(key);
          if (cell !== undefined && !cell.isDefault && target !== undefined) {
            target.set(e.id, cell);
          }
        }
      }
    }
    for (const e of HudEntry.ALL) {
      if (seen.has(e.id)) {
        continue;
      }
      seen.add(e.id);
      const home: Array<HudEntry> | undefined = placed.get(hudZoneKey(e.homeZone));
      if (home !== undefined) {
        home.push(e);
      }
    }
    const nextZones: Map<string, ZoneState> = new Map<string, ZoneState>();
    for (const zone of HUD_ZONES) {
      const key: string = hudZoneKey(zone);
      const order: Array<HudEntry> | undefined = placed.get(key);
      const cells: Map<string, GridCell> | undefined = placedCells.get(key);
      nextZones.set(key, new ZoneState(this.normalizedPosOf(zone),
        order === undefined ? [] : order,
        cells === undefined ? new Map<string, GridCell>() : cells));
    }
    return new HudLayoutTable(this.version, nextZones);
  }

  /**
   * 位置段的合规化（[normalize] 的其中一步，单独露出来给用例直接打）：
   * - **底栏只认 y**：绝对 x 一律抹回哨兵。落一次位就把 x 写成绝对值会把"快门跟随可视中心"冻住
   *   （90↔270 翻转与横竖换档都不再重新居中），所以这里连存量脏数据一起治；
   * - 其余四枚：一轴绝对、一轴哨兵的半吊子（只能来自手改 prefs）按未编辑处理。
   */
  normalizedPosOf(zone: HudZone): ZonePlacement {
    const raw: ZonePlacement = this.posOf(zone);
    if (zone === HudZone.BOTTOM) {
      return raw.yDp >= 0 ? new ZonePlacement(-1, raw.yDp) : ZonePlacement.DEFAULT;
    }
    return raw.xDp >= 0 && raw.yDp >= 0 ? raw : ZonePlacement.DEFAULT;
  }

  /**
   * 编码（v2）：`v2;T,8,4,P11,P12;L,-1,-1,P6:0.0,P7:0.1,P8:0.2;…`
   *
   * 分号分「版本段 / 五个容器段」，逗号分「x / y / id 串」；位置段的哨兵 `-1` = 该容器仍在默认位置。
   * **v2 多的就是条目后缀**：`P8:0.2` = 摆到第 0 列第 2 行，光一串 `P8` = 没摆过（用推导格）。
   * 后缀只在 [hudZoneIsGrid] 的容器上写，非网格容器写了也没人读，不留脏数据。
   * **一格多颗不需要第二种写法**：`tok:col.row` 是逐颗写的，配对那两颗同格就是两个相同的后缀。
   */
  encode(): string {
    let out: string = 'v' + this.version;
    for (const zone of HUD_ZONES) {
      out += ';' + hudZoneKey(zone) + ',';
      const pos: ZonePlacement = this.posOf(zone);
      out += pos.xDp + ',' + pos.yDp + ',';
      const cells: Map<string, GridCell> = this.placedCellsOf(zone);
      const grid: boolean = hudZoneIsGrid(zone);
      const order: Array<HudEntry> = this.orderOf(zone);
      for (let i = 0; i < order.length; i++) {
        if (i > 0) {
          out += ',';
        }
        const entry: HudEntry = order[i];
        out += entry.id;
        if (grid) {
          const cell: GridCell | undefined = cells.get(entry.id);
          out += ':' + (cell === undefined ? GridCell.DEFAULT.token : cell.token);
        }
      }
    }
    return out;
  }

  /**
   * 当前 schema 版本。v1 = 只有顺序没有格子（B4 那批），v2 = 每颗条目带一个格子（任务 #74）。
   * 改格式就 +1，并在 [decode] 里对旧版本做处置。
   */
  static readonly CURRENT_VERSION: number = 2;

  /** 未编辑过的初值：位置全默认、顺序全按 [HudEntry.homeZone] 内的枚举声明序、格子全哨兵 */
  static default(): HudLayoutTable {
    const zones: Map<string, ZoneState> = new Map<string, ZoneState>();
    for (const zone of HUD_ZONES) {
      zones.set(hudZoneKey(zone),
        new ZoneState(ZonePlacement.DEFAULT, HudLayoutTable.defaultOrderOf(zone), new Map<string, GridCell>()));
    }
    return new HudLayoutTable(HudLayoutTable.CURRENT_VERSION, zones).normalize();
  }

  /**
   * 默认表里每枚容器的条目顺序 = **B1–B3 定稿代码里的书写顺序**，逐个对过上游源码：
   * · 顶栏 `TopCapsule` 的 buildList：SIZE → STORAGE
   * · 左竖 Dock：参考线 → 屏幕监看 → RGB 曲线 → 闪光灯
   * · 右竖 Dock：姿态仪 → 音量表 → 蓝牙 → 变焦 → 对焦 → 防抖
   * · 读数块：`HudItems.typesOf(mask)` 的枚举序（快门 帧率 码率 ISO EV 白平衡 变焦）
   * · 底栏：只有镜头那颗是可编辑条目（缩略图与快门不可隐藏，不进表）
   *
   * 这张清单**只写顺序不写坐标**：默认格子由 J2 的推导函数从它现推，
   * 于是"默认表"与"录制页今天的排布"是同一个函数的输出。
   */
  static defaultOrderOf(zone: HudZone): Array<HudEntry> {
    if (zone === HudZone.TOP) {
      return [HudEntry.ofPill(CamPill.SIZE), HudEntry.ofPill(CamPill.STORAGE)];
    }
    if (zone === HudZone.LEFT) {
      return [
        HudEntry.ofPill(CamPill.REFLINE), HudEntry.ofPill(CamPill.MONITOR),
        HudEntry.ofPill(CamPill.CURVE), HudEntry.ofPill(CamPill.FLASH)
      ];
    }
    if (zone === HudZone.RIGHT) {
      return [
        HudEntry.ofPill(CamPill.LEVEL), HudEntry.ofPill(CamPill.VOLUME), HudEntry.ofPill(CamPill.BT),
        HudEntry.ofPill(CamPill.ZOOM), HudEntry.ofPill(CamPill.FOCUS), HudEntry.ofPill(CamPill.STAB)
      ];
    }
    if (zone === HudZone.BOTTOM) {
      return [HudEntry.ofPill(CamPill.LENS)];
    }
    const readout: Array<HudEntry> = [];
    for (const item of HudItems.ALL) {
      readout.push(HudEntry.ofItem(item));
    }
    return readout;
  }

  /**
   * 解码：任何异常（null / 空串 / 缺段 / 未知容器 id / 版本不认识 / 数字炸）都**不抛**，
   * 坏的那一段丢掉、其余保留，整张表最后过一次 [normalize]。
   * 版本号**比本工程新**时整表按默认处理：新格式老代码读不懂，硬解会写出错位置。
   *
   * **v1 → v2 的迁移不换算坐标，而是"格子全留哨兵"**：v1 的位置本来就是「顺序 × 分行」推导出来的，
   * 而 v2 的默认格子用的正是同一条推导（J2）⇒ 老串读进来逐颗落点与改前相同。
   * 解出来的表版本一律抬到 [CURRENT_VERSION]：下一次保存自然写成 v2。
   * 单条 token 的格子后缀坏了（`P6:0`、`P6:x.y`）只丢那一颗的格子，条目本身照留。
   */
  static decode(raw: string | undefined): HudLayoutTable {
    if (raw === undefined || raw.trim() === '') {
      return HudLayoutTable.default();
    }
    const parts: Array<string> = raw.split(';');
    const header: string = parts.length > 0 ? parts[0] : '';
    const versionText: string = header.startsWith('v') ? header.substring(1) : header;
    const version: number | undefined = intOrUndefined(versionText);
    if (version === undefined || version > HudLayoutTable.CURRENT_VERSION) {
      return HudLayoutTable.default();
    }
    // 只有 v1（顺序模型）与 v2（格子模型）认得；v0 / 负数一律按坏串回默认
    if (version < 1) {
      return HudLayoutTable.default();
    }
    const zones: Map<string, ZoneState> = new Map<string, ZoneState>();
    for (let i = 1; i < parts.length; i++) {
      const fields: Array<string> = parts[i].split(',');
      const zone: HudZone | undefined = hudZoneFromKey(fields.length > 0 ? fields[0] : undefined);
      if (zone === undefined) {
        continue;
      }
      const xRaw: number | undefined = intOrUndefined(fields.length > 1 ? fields[1] : undefined);
      const yRaw: number | undefined = intOrUndefined(fields.length > 2 ? fields[2] : undefined);
      const x: number = xRaw === undefined ? -1 : xRaw;
      const y: number = yRaw === undefined ? -1 : yRaw;
      const order: Array<HudEntry> = [];
      const cells: Map<string, GridCell> = new Map<string, GridCell>();
      for (let t = 3; t < fields.length; t++) {
        const rawToken: string = fields[t];
        const colon: number = rawToken.indexOf(':');
        const head: string = colon < 0 ? rawToken : rawToken.substring(0, colon);
        const entry: HudEntry | undefined = HudEntry.fromId(head);
        if (entry === undefined) {
          continue;
        }
        order.push(entry);
        if (!hudZoneIsGrid(zone)) {
          continue;
        }
        const suffix: string = colon < 0 ? '' : rawToken.substring(colon + 1);
        if (suffix === '' || suffix === '-') {
          continue; // v1 光串 / 显式哨兵 = 没摆过
        }
        const cell: GridCell | undefined = HudLayoutTable.parseCell(suffix);
        if (cell !== undefined) {
          cells.set(entry.id, clampStoredCell(zone, cell));
        }
      }
      zones.set(hudZoneKey(zone), new ZoneState(new ZonePlacement(x, y), order, cells));
    }
    return new HudLayoutTable(HudLayoutTable.CURRENT_VERSION, zones).normalize();
  }

  /** `0.2` → [GridCell]；缺列、非数字、负数一律 undefined（调用方把那颗按"没摆过"处理） */
  private static parseCell(token: string): GridCell | undefined {
    const f: Array<string> = token.split('.');
    if (f.length !== 2) {
      return undefined;
    }
    const col: number | undefined = intOrUndefined(f[0]);
    const row: number | undefined = intOrUndefined(f[1]);
    if (col === undefined || row === undefined) {
      return undefined;
    }
    return col < 0 || row < 0 ? undefined : new GridCell(col, row);
  }
}

// ------------------------------------------------------------------ 两页各自的可见集（纯函数）

/**
 * 「编辑控件」页的可见集（用户 2026-10-02 明示：**编辑页应当显示所有可装载控件，
 * 不能只显示用户自己选择要"显示"的那些**）。
 *
 * 四条判据与录制页分清：
 * 1. **`hud_pills` / `hud_items` 掩码**：这里**不读**。那是"用户不想显示"，而编辑页要的正是把
 *    这些也列出来、也能摆。录制页照旧过滤（[HudLayoutTable.visibleOrderOf] 只过滤不改表）。
 * 2. **canFlash / focusUsable**：编辑页没有相机，拿不到 ability ⇒ 这一档在本页本就无从判起。
 * 3. **levelEnabled**（设置页「显示水平仪与俯仰仪」开关）：**保留**——那颗在录制页
 *    `levelEnabled = false` 时整个返回空，列在编辑页只会得到一格看不见的空洞。
 * 4. **recording**：编辑页从设置页进、录制中进不来（`HudCtx.recording` 恒 false）。
 *
 * 唯一例外是码率（[HudItem.BITRATE]）：它移出网格、是 Dock 外的固定读数，**不进两页的可见集**。
 *
 * 返回条目 **id** 集（ArkTS 无结构相等；与 [HudLayoutTable.visibleOrderOf] 的入参同一口径）。
 */
export function editorVisibleEntries(levelEnabled: boolean): Set<string> {
  const out: Set<string> = new Set<string>();
  for (const e of HudEntry.ALL) {
    // 码率不进两页的可见集（Dock 外固定读数）
    if (e.item === HudItem.BITRATE) {
      continue;
    }
    // 水平仪那颗在显示开关关掉时整颗不渲染 ⇒ 列出来也只是空格，本页不列
    if (e.pill === CamPill.LEVEL && !levelEnabled) {
      continue;
    }
    out.add(e.id);
  }
  return out;
}

// ------------------------------------------------------------------ 越界钳制与落位（纯函数）

/**
 * 安全区与两条实测避让量，单位全 dp。
 *
 * - width/height：root 容器套上 `safeDrawingPadding()` 之后的**实测尺寸**（不是 `screenWidthDp`）。
 *   它就是位置表 (x, y) 的坐标参考：挖孔在哪条边，系统就把那条边让掉，于是这里**不需要**任何
 *   "按方向补一笔右缘让位"的字段（上游曾有 `endInsetDp` 喂写死的 34dp，2026-09-29 真机复测证明
 *   那是伪值，字段已删净）。安全区本身已经把避让算进 width，再扣一次就是双重让位。
 * - topAvoidDp/bottomAvoidDp：顶栏与底栏的**实测**避让量，不是新写的魔法数。
 */
export class HudAreaDp {
  readonly width: number;
  readonly height: number;
  readonly topAvoidDp: number;
  readonly bottomAvoidDp: number;

  constructor(width: number, height: number, topAvoidDp: number, bottomAvoidDp: number) {
    this.width = width;
    this.height = height;
    this.topAvoidDp = topAvoidDp;
    this.bottomAvoidDp = bottomAvoidDp;
  }
}

/** 一个 dp 矩形（安全区局部坐标，左上原点） */
export class HudRectDp {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;

  constructor(left: number, top: number, right: number, bottom: number) {
    this.left = left;
    this.top = top;
    this.right = right;
    this.bottom = bottom;
  }

  get width(): number {
    return this.right - this.left;
  }

  get height(): number {
    return this.bottom - this.top;
  }

  contains(x: number, y: number): boolean {
    return x >= this.left && x < this.right && y >= this.top && y < this.bottom;
  }

  /**
   * 与另一枚矩形的交叠判据（边界相接 `a.right == b.left` 算**不**交叠：那两块像素没有重叠）。
   */
  intersects(other: HudRectDp): boolean {
    return this.left < other.right && other.left < this.right &&
      this.top < other.bottom && other.top < this.bottom;
  }
}

/** 一个 dp 坐标 */
export class HudPointDp {
  readonly x: number;
  readonly y: number;

  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

/** 一个 px 坐标（[HudLayout] 是纯逻辑，不引平台尺寸类型） */
export class HudPointPx {
  readonly x: number;
  readonly y: number;

  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

/** 闭区间（等价 Kotlin `IntRange`；端点含） */
export class IntRange {
  readonly first: number;
  readonly last: number;

  constructor(first: number, last: number) {
    this.first = first;
    this.last = last;
  }

  contains(value: number): boolean {
    return value >= this.first && value <= this.last;
  }
}

/**
 * 横向可放区间 `[0, 安全区宽 − 容器宽]`。安全区宽已经是"套上 `safeDrawingPadding()` 之后"的量，
 * 挖孔让掉的边在里面扣过了，所以这里不再按方向另扣一笔（旧写法扣 34dp，两个横屏姿态里必错一次）。
 * 容器比安全区还宽（120% 文本 + 极窄分屏）时上限夹成 0，也就是贴左放，**不许为负**。
 */
export function clampZoneX(area: HudAreaDp, widthDp: number): number {
  const hi: number = area.width - widthDp;
  return hi > 0 ? hi : 0;
}

/**
 * 纵向可放区间。
 * - [HudZone.TOP] / [HudZone.BOTTOM]：整条安全区（顶栏本来贴顶、底栏可以搬到上栏）；
 * - 其余三枚：`[topAvoid, height − bottomAvoid − h]`。
 *
 * 带高不够（告警条 + 满配读数把带挤没了）时退成整条安全区，**区间永不为负**，
 * 否则 `coerceIn` 会抛而整个 HUD 就没了（本侧用显式钳制，同一兜底）。
 *
 * topZoneMinYDp 是只有 [HudZone.TOP] 读的绝对定位支下限：没有它时顶栏一旦被拖到编辑页那条
 * 操作栏的矩形里，整枚容器既点不到也拖不动（操作栏在拖拽捕获层之上）。
 * ⚠ **录制页恒传 0**：下限不取 area 里的任何字段，而是由调用方显式喂"这一帧量到的操作栏下缘"
 * （编辑页喂 [chromeBandBottomDp] 的产物），其余调用点（含录制页）一律传 0 ⇒ 逐字退回改前。
 */
export function clampZoneYRange(zone: HudZone, area: HudAreaDp, heightDp: number, topZoneMinYDp: number): IntRange {
  const band: boolean = zone !== HudZone.TOP && zone !== HudZone.BOTTOM;
  let lo: number = 0;
  if (band) {
    lo = area.topAvoidDp > 0 ? area.topAvoidDp : 0;
  } else if (zone === HudZone.TOP) {
    lo = topZoneMinYDp > 0 ? topZoneMinYDp : 0;
  }
  const hi: number = band
    ? area.height - area.bottomAvoidDp - heightDp
    : area.height - heightDp;
  // 带高不够（或容器本身比屏还高）时退成整条安全区，区间不许为负
  if (hi < lo) {
    const fallbackHi: number = area.height - heightDp;
    return new IntRange(0, fallbackHi > 0 ? fallbackHi : 0);
  }
  return new IntRange(lo, hi);
}

/**
 * 一枚容器的**可放带**纵向区间（dp，安全区局部坐标，含端点；归属裁决读它）。
 *
 * 与 [clampZoneYRange] 的差别只有一处：那条算的是"容器左上角最矮能放到哪"（要再减一枚容器高），
 * 这条算的是"这一条带本身吃到哪"（指针在不在带里）。两者共用同一批实测入参，分叉不了。
 * 带高不够（告警条把带挤没）时退成整条安全区，与 [clampZoneYRange] 同一条兜底。
 */
export function zoneBandYRange(zone: HudZone, area: HudAreaDp, topZoneMinYDp: number): IntRange {
  const band: boolean = zone !== HudZone.TOP && zone !== HudZone.BOTTOM;
  let lo: number = 0;
  if (band) {
    lo = area.topAvoidDp > 0 ? area.topAvoidDp : 0;
  } else if (zone === HudZone.TOP) {
    lo = topZoneMinYDp > 0 ? topZoneMinYDp : 0;
  }
  const rawHi: number = band ? area.height - area.bottomAvoidDp : area.height;
  return new IntRange(lo, rawHi > lo ? rawHi : lo);
}

/**
 * 绝对位置钳进安全区（尺寸未测到时传 0，等价于「只保证左上角不越界」）。
 *
 * **底栏的 x 在这里就被抹成哨兵**：它只能上下搬，写入方就算把实测左缘换算成绝对 x 送进来也存不进表
 * ——那会让快门从此不再跟随可视中心。[HudLayoutTable.normalizedPosOf] 在读表时同样抹一次，
 * 把存量脏数据一起治掉。
 *
 * topZoneMinYDp 透传给 [clampZoneYRange]（只有 TOP 的绝对定位支读它）；录制页与其余调用点传 0
 * ⇒ 逐字退回改前那一档，同一份位置表在两页渲染出同一个位置。
 */
export function clampZonePos(
  zone: HudZone,
  xDp: number,
  yDp: number,
  wDp: number,
  hDp: number,
  area: HudAreaDp,
  topZoneMinYDp: number
): ZonePlacement {
  const yRange: IntRange = clampZoneYRange(zone, area, hDp, topZoneMinYDp);
  const clampedY: number = yDp < yRange.first ? yRange.first : (yDp > yRange.last ? yRange.last : yDp);
  if (zone === HudZone.BOTTOM) {
    return new ZonePlacement(-1, clampedY);
  }
  const xHi: number = clampZoneX(area, wDp);
  const clampedX: number = xDp < 0 ? 0 : (xDp > xHi ? xHi : xDp);
  return new ZonePlacement(clampedX, clampedY);
}

/**
 * 「编辑控件」页那条**操作栏**在安全区局部坐标里压到多低（dp，任务 #79 的唯一算式）。
 *
 * 操作栏在窗口坐标里的**下缘**减去安全区的**窗口原点**，就是它在安全区局部坐标里压到的那条线
 * （两个数都是组合期实测回报，没有任何按方向写死的避让常量）。
 * 量不到（回报 0）时返回 **0** = "这一帧不推"；下一帧实测接管。不许返回负数
 * （负 dp 会让"量到了多低"在日志里变成一个看起来像真值的假数——#69 那族"哨兵值参与算式"的坑）。
 */
export function chromeBandBottomDp(chromeBottomWinPx: number, safeOriginYPx: number, density: number): number {
  const diff: number = chromeBottomWinPx - safeOriginYPx;
  return pxToDp(diff > 0 ? diff : 0, density);
}

/** px → dp 的整数换算（编辑页与录制页共写位置时用，避免出现第二套换算式） */
export function pxToDp(px: number, density: number): number {
  if (density <= 0) {
    return 0;
  }
  const dp: number = px / density;
  // Kotlin roundToInt = floor(x + 0.5)；Math.round 对正数同口径，负数在本侧只有 0 与正差
  return Math.round(dp);
}

// ------------------------------------------------------------------ 格子（#74：容器内的固定格网）

/**
 * 网格行数硬上限：条目总数。比条目总数还多的行编辑页**摆不出来**，只能来自手改 prefs。
 *
 * "每颗条目最多占一行"这条前提**不成立**（高条目按 [cellRowSpan] 吃掉几档），但这个数本身不动：
 * 它只管**存进来的起始行**不许离谱（[clampStoredCell] 与解码共用），与"那颗有多高、压住几档"是两件事。
 * 改成"条目数 ÷ 平均跨度"之类的算法会把上限变成内容的函数，正好违反"跨度不进 schema"。
 */
export function gridRowHardCap(): number {
  return HudEntry.ALL.length;
}

/**
 * 网格列数硬上限（逐枚容器）：
 * - [HudZone.READOUT] → 3：换行档的最高档就是一行三颗，编辑页也摆不出第四列；
 * - 两枚竖 Dock → 2：**存储层**上限（配对两颗共用一格，默认是单列），第 2 列只由用户主动摆出来，
 *   手改 prefs 造出的第 3 列一律在 [clampStoredCell] 钳回；
 * - 非网格容器 → 1（格子对它不生效，只给个不为 0 的数免得钳制算式里出现除零）。
 */
export function gridColsCapOf(zone: HudZone): number {
  if (!hudZoneIsGrid(zone)) {
    return 1;
  }
  return zone === HudZone.READOUT ? 3 : 2;
}

/**
 * 存进表 / 从表里读出来的格子先过这一道：列钳进 [gridColsCapOf]、行钳进 [gridRowHardCap]。
 * 哨兵原样放行（它表示"没摆过"，不是越界值）。
 */
export function clampStoredCell(zone: HudZone, cell: GridCell): GridCell {
  if (cell.isDefault) {
    return cell;
  }
  const colHi: number = gridColsCapOf(zone) - 1;
  const rowHi: number = gridRowHardCap() - 1;
  const col: number = cell.col < 0 ? 0 : (cell.col > colHi ? colHi : cell.col);
  const row: number = cell.row < 0 ? 0 : (cell.row > rowHi ? rowHi : cell.row);
  return new GridCell(col, row);
}

/** 指针/坐标落在哪一格之外的「容器可放带」（单位 dp，由调用方从实测带高与可用宽换算来） */
export class GridBox {
  readonly cols: number;
  readonly rows: number;

  constructor(cols: number, rows: number) {
    this.cols = cols;
    this.rows = rows;
  }

  equals(other: GridBox): boolean {
    return this.cols === other.cols && this.rows === other.rows;
  }
}

// ------------------------------------------------------------------ J2：网格几何 / 拖放 / 读数行规划的纯类型

/** 一个 px 尺寸（[HudLayout] 是纯逻辑，不引平台尺寸类型） */
export class HudSizePx {
  readonly width: number;
  readonly height: number;

  constructor(width: number, height: number) {
    this.width = width;
    this.height = height;
  }

  equals(other: HudSizePx): boolean {
    return this.width === other.width && this.height === other.height;
  }
}

/**
 * 一帧网格解析要的运行时输入（都来自组合期实测，表本身不该知道它们）。
 *
 * `rowPitchOf` / `cellHeightOf` 是**取器**而不是两张 Map：录制页那份实测高住在快照状态里，
 * 解析时现读才能挂上读取订阅（量到之后那一帧才重组、跨度才接管）。
 *
 * ⚠ 两个取器与上游一样**没有默认值**（#69 铁律）：给默认值 `{ 1 }` / `{ 0 }` 会让新增调用点
 * 静默退回"每格一档 + 行距由最高那颗顶"，而那正是 #75 修掉的回归。
 *
 * ArkTS 适配：`visible` 是条目 **id** 集（与 [HudLayoutTable.visibleOrderOf] 同一口径），
 * 上游是 `Set<HudEntry>`；语义一一对应。
 */
export class HudGridPlan {
  /** 设置里开着显示的条目 id 集：两页各有一份同源计算，传进来而不是表里再算一遍 */
  readonly visible: Set<string>;
  /** 读数块"一行几颗"那一档（`hudPerRowFor` / `planReadoutRow` 的产物），只作用读数块 */
  readonly readoutPerRow: number;
  /** 该容器的**纵向格距**（px）：由胶囊档推出的那条 [gridRowPitchPx]，与格子里住了谁无关 */
  readonly rowPitchOf: (zone: HudZone) => number;
  /** 该条目的**实测高**（px）：量不到返回 0 ⇒ 那颗这一帧按一档算（下一帧实测接管） */
  readonly cellHeightOf: (entry: HudEntry) => number;

  constructor(
    visible: Set<string>,
    readoutPerRow: number,
    rowPitchOf: (zone: HudZone) => number,
    cellHeightOf: (entry: HudEntry) => number
  ) {
    this.visible = visible;
    this.readoutPerRow = readoutPerRow;
    this.rowPitchOf = rowPitchOf;
    this.cellHeightOf = cellHeightOf;
  }

  /** 该容器推导默认格子时的分行档：只有读数块吃这个数，其余容器 `hudRowGroups` 不读它 */
  perRowOf(zone: HudZone): number {
    return zone === HudZone.READOUT ? this.readoutPerRow : 1;
  }
}

/**
 * 一帧网格的**锚定与预留**（#80）。
 *
 * `defaultCells` = 该容器**默认表**的「条目 id → 格子」（[HudLayoutTable.defaultGridOf] 的产物；
 * 空 = 这一帧还没量到/容器空）。
 */
export class GridAnchor {
  readonly zone: HudZone;
  readonly defaultCells: Map<string, GridCell>;

  constructor(zone: HudZone, defaultCells: Map<string, GridCell>) {
    this.zone = zone;
    this.defaultCells = defaultCells;
  }
}

/** 一条条目在这一格（渲染层拿到的就是这一对，不再有第二份算式） */
export class HudGridItem {
  readonly entry: HudEntry;
  readonly cell: GridCell;

  constructor(entry: HudEntry, cell: GridCell) {
    this.entry = entry;
    this.cell = cell;
  }
}

/**
 * 一次落点的裁决结果：被拖那颗落哪、被挤出的那些各自落哪（未发生交换/让位时 [moved] 为空）。
 *
 * ArkTS 适配：[moved] 的键是被挤出住户的**条目 id**（无结构相等的适配），值是它们的新锚点格。
 */
export class CellDrop {
  readonly dropped: GridCell;
  readonly moved: Map<string, GridCell>;

  constructor(dropped: GridCell, moved: Map<string, GridCell>) {
    this.dropped = dropped;
    this.moved = moved;
  }
}

/** [gridPlacementOf] 的产物：渲染层只读这三件，不再自己算任何一格的位置 */
export class GridPlacement {
  readonly pitch: HudSizePx;
  readonly size: HudSizePx;
  readonly offsets: Array<HudPointPx>;

  constructor(pitch: HudSizePx, size: HudSizePx, offsets: Array<HudPointPx>) {
    this.pitch = pitch;
    this.size = size;
    this.offsets = offsets;
  }
}

/** 读数块与底栏 Dock 之间那条底部横带的关系（任务 #70 A 的唯一裁决点） */
export class ReadoutRowPlan {
  /** 这一帧到底同不同行（裁决本身的露出点） */
  readonly sharesDockRow: boolean;
  /** 喂给读数块容器（`HudAreaDp.bottomAvoidDp`）的**容器**让位 */
  readonly bottomAvoidDp: number;
  /** 一行几颗（横屏再经 [DOCK_ROW_PER_ROW_CAP] 夹一次） */
  readonly perRow: number;
  /** 这一帧真正可用的**块内宽**（已扣设计留白与块自身内边距） */
  readonly roomWidthDp: number;

  constructor(sharesDockRow: boolean, bottomAvoidDp: number, perRow: number, roomWidthDp: number) {
    this.sharesDockRow = sharesDockRow;
    this.bottomAvoidDp = bottomAvoidDp;
    this.perRow = perRow;
    this.roomWidthDp = roomWidthDp;
  }
}

/** 一对重叠的容器（上游 `Pair<HudZone, HudZone>`；ArkTS 无元组） */
export class HudZonePair {
  readonly first: HudZone;
  readonly second: HudZone;

  constructor(first: HudZone, second: HudZone) {
    this.first = first;
    this.second = second;
  }

  equals(other: HudZonePair): boolean {
    return this.first === other.first && this.second === other.second;
  }
}

/**
 * 底栏拖拽 gate 读的录制态（上游 `camera.RecordStatus` 的五档）。
 *
 * 本侧录制管线没有与上游同名的状态枚举（内录状态机是另一套），所以这里只镜像上游那**五个值**，
 * 由 J3 的编辑器/录制页在调用点替它映射。判据逐字照搬：PREPARE / START / STOPPING 拒绝。
 */
export enum HudDockStatus {
  IDLE = 'IDLE',
  PREPARE = 'PREPARE',
  START = 'START',
  STOPPING = 'STOPPING',
  ERROR = 'ERROR'
}

/**
 * 一枚格子的**行跨度**（占掉几档）：内容高 ÷ 格距向上取整，至少 1。
 *
 * 取"向上"是硬的：往下取就把一颗画到下一档身上（重叠），而重叠是这次要修的形态的另一半。
 * 内容高量不到（0 / 负）或格距没喂进来（≤0）时按 1 档——那是"还没量到"那一帧的已知退化。
 *
 * ⚠ **依赖闭包登记**：本函数与 [spannedCells] / [blockingCells] 属于 J2 的网格几何，但
 * `HudPageGrid.pageCellsBlocked`（J1 必移植）在上游就是**复用它们**的桥。为避免出现第二份占用判据
 * （两份迟早分叉，就会出现"编辑页预览说这格有人、写表说没人"），J1 预置这三件，
 * J2 直接在此基础上加 `gridBoxOf`/`clampCellToBox`/`cellDropOf`/`freeCellNear`/`resolveCells`。
 */
export function cellRowSpan(contentHeightPx: number, rowPitchPx: number): number {
  if (contentHeightPx <= 0 || rowPitchPx <= 0) {
    return 1;
  }
  const span: number = Math.ceil(contentHeightPx / rowPitchPx);
  return span > 1 ? span : 1;
}

/** 一枚格子连同它的行跨度吃掉的档：`(col,row) .. (col,row+span−1)`；哨兵返回空（没摆过 ⇒ 不占档） */
export function spannedCells(cell: GridCell, rowSpan: number): Array<GridCell> {
  if (cell.isDefault) {
    return [];
  }
  const out: Array<GridCell> = [];
  const count: number = rowSpan < 1 ? 1 : rowSpan;
  for (let i = 0; i < count; i++) {
    out.push(new GridCell(cell.col, cell.row + i));
  }
  return out;
}

/**
 * 「这一格（连同它压住的几档）挡不挡 [self] 的路」的唯一判据。
 *
 * 两条口径叠在一起，缺一条都测不出退化：
 * 1. **#74 后果修复**：格子里住着**既不是它自己、也不是它同组搭档**的颗才算挡它的**起始档**。
 *    只住着搭档的那一格不算挡（把姿态仪放回音量表那一格是**重新并排**，不是撞车）。
 * 2. **#75 行跨度**：高格外面那几档 `row+1 .. row+span−1` **一律算占**，连搭档也不例外——
 *    搭档只许落回**同一枚格子**（起始档），不许落进它那块的中间。
 *    self 为 null 是"格子里有人就算占"的旧口径，那时整块都算占。
 *
 * `residents` 的键 = [GridCell.token]（ArkTS 无结构相等的适配）；`mates` = 同组搭档的**条目 id**。
 * 返回被占档的 token 集（`'0.2'` 形态），与入参同一编码。
 */
export function blockingCells(
  residents: Map<string, Array<HudEntry>>,
  self: HudEntry | null,
  mates: Set<string>,
  cellHeightPx: (entry: HudEntry) => number,
  rowPitchPx: number
): Set<string> {
  const out: Set<string> = new Set<string>();
  residents.forEach((who: Array<HudEntry>, token: string): void => {
    const cell: GridCell = gridCellOfToken(token);
    if (cell.isDefault || who.length === 0) {
      return;
    }
    let tallest: number = 0;
    for (const e of who) {
      const h: number = cellHeightPx(e);
      const clamped: number = h > 0 ? h : 0;
      if (clamped > tallest) {
        tallest = clamped;
      }
    }
    const span: number = cellRowSpan(tallest, rowPitchPx);
    let blocksAnchor: boolean = self === null;
    if (self !== null) {
      for (const e of who) {
        if (e.id !== self.id && !mates.has(e.id)) {
          blocksAnchor = true;
          break;
        }
      }
    }
    if (blocksAnchor) {
      for (const c of spannedCells(cell, span)) {
        out.add(c.token);
      }
    } else {
      for (let i = 1; i < span; i++) {
        out.add(new GridCell(cell.col, cell.row + i).token);
      }
    }
  });
  return out;
}

/** `'0.2'` → GridCell；`'-'` / 坏串 → 哨兵（与 [GridCell.token] 同一编码） */
export function gridCellOfToken(token: string): GridCell {
  if (token === GridCell.DEFAULT.token) {
    return GridCell.DEFAULT;
  }
  const f: Array<string> = token.split('.');
  if (f.length !== 2) {
    return GridCell.DEFAULT;
  }
  const col: number | undefined = intOrUndefined(f[0]);
  const row: number | undefined = intOrUndefined(f[1]);
  if (col === undefined || row === undefined) {
    return GridCell.DEFAULT;
  }
  return new GridCell(col, row);
}

/**
 * 松手时"这颗归哪枚容器"（#80 根因 3 的唯一裁决）。
 *
 * 真机那一条：把右 Dock 的蓝牙往下拖，指针**离开底板矩形却还在右 Dock 自己的可放带里**，
 * 老写法 `zoneAt(指针, 各容器矩形)` 命中了贴在下面的读数块 ⇒ 这颗被真写进读数块，
 * 读数块因此多一档、整块上移，快门/帧率/码率全跟着动。
 * 现在的规矩：**指针还在来源容器那条带里（且横向还在它的底板区间里）就不许改归属**，
 * 纵向拖过头交给钳制带回原带内最近的一档。真要把一颗搬去别的容器，把手指拖出来源那条带即可。
 * sourceCard 量不到（首帧，undefined）时按"不在带里"处理 ⇒ 退回 hitAtPointer，与改前同值。
 */
export function dropZoneOf(
  pointer: HudPointDp,
  source: HudZone,
  sourceCard: HudRectDp | undefined,
  sourceBandY: IntRange,
  hitAtPointer: HudZone | undefined
): HudZone {
  const hit: HudZone = hitAtPointer === undefined ? source : hitAtPointer;
  if (hit === source) {
    return source;
  }
  const insideSource: boolean = sourceCard !== undefined &&
    pointer.x >= sourceCard.left && pointer.x < sourceCard.right &&
    sourceBandY.contains(pointer.y);
  return insideSource ? source : hit;
}

// ------------------------------------------------------------------ 小工具

/**
 * Kotlin `toIntOrNull` 的等价物：只认可选正负号 + 十进制数字，且必须落进 Int 值域
 * （Kotlin 接受 `"+5"`，这里也接受，保持手改 prefs 的容错口径一致）。
 * 不用 `parseInt`（它会接受 `'12abc'` / 前导空格，坏串会被当成合法位置）。
 */
export function intOrUndefined(raw: string | undefined): number | undefined {
  if (raw === undefined || raw.length === 0) {
    return undefined;
  }
  let start: number = 0;
  const head: string = raw.charAt(0);
  if (head === '-' || head === '+') {
    start = 1;
  }
  if (start >= raw.length) {
    return undefined;
  }
  for (let i = start; i < raw.length; i++) {
    const c: number = raw.charCodeAt(i);
    if (c < 48 || c > 57) {
      return undefined;
    }
  }
  const value: number = Number(raw);
  if (!Number.isFinite(value) || value < -2147483648 || value > 2147483647) {
    return undefined;
  }
  return value;
}

/**
 * 网格纵向格距的行距档（dp）：两枚竖 Dock 与整页页格用 4dp（上游 `WotaSpace.xs`），
 * 读数块用 [HUD_ROW_GAP_DP]（6dp）。J2 的 `gridRowPitchPx` 与 `HudPageGrid.pageCellPx`
 * **共读这一条**，不许各写一个 4/6——那正是上游 `hudGridGap` 的两个分支。
 */
export function hudGridGapDp(zone: HudZone): number {
  return zone === HudZone.READOUT ? HUD_ROW_GAP_DP : 4;
}

// ------------------------------------------------------------------ J2：可放带 → 格盒（#80 预留/生长）

/** 编辑上限相对默认表预留的**额外行档数**（用户 10-01 明示放宽的那一格） */
export const GRID_ROW_GROWTH_ROWS: number = 1;

/** 把条目登记进住户表（token → 条目数组），保持插入序 —— 与上游 `getOrPut { mutableListOf() }` 同义 */
function putResident(
  residents: Map<string, Array<HudEntry>>, cell: GridCell, entry: HudEntry
): void {
  const cur: Array<HudEntry> | undefined = residents.get(cell.token);
  if (cur === undefined) {
    residents.set(cell.token, [entry]);
  } else {
    cur.push(entry);
  }
}

/** Kotlin `coerceIn(lo, hi)`（lo ≤ hi 由调用方保证，与上游一致） */
function clampInt(v: number, lo: number, hi: number): number {
  return v < lo ? lo : (v > hi ? hi : v);
}

/** Kotlin Int 除法（向零截断；JS 的 `/` 是浮点，`Math.trunc` 对齐上游 Int 语义） */
function truncDiv(v: number, p: number): number {
  return Math.trunc(v / p);
}

/** Kotlin `maxOfOrNull` 的等价物：空数组给 fallback */
function maxOrNull(values: Array<number>, fallback: number): number {
  let best: number = fallback;
  for (const v of values) {
    if (v > best) {
      best = v;
    }
  }
  return best;
}

/**
 * 可放带 → [GridBox]：行由**带高 ÷ 格距**算（#75 之后这是"有几档可用"，不是"有几颗条目能排"），
 * 列对读数块由**可用内宽**决定、对两枚竖 Dock 直接取 [gridColsCapOf]。
 *
 * #80 之后带还要再夹一次预留档数（`reservedRows` / `reservedCols`，0 = 不夹）。两个生长量由调用方
 * 显式表态：编辑页传 `rowGrowth = GRID_ROW_GROWTH_ROWS`、`colGrowth = 0`；**其余调用点全部传 0**
 * ⇒ 逐字退回改前那句 `min(带内, 预留)`。四个数**没有默认值**（#69 铁律）。
 */
export function gridBoxOf(
  zone: HudZone,
  pitchXDp: number,
  pitchYDp: number,
  bandHeightDp: number,
  roomWidthDp: number,
  reservedRows: number,
  reservedCols: number,
  rowGrowth: number,
  colGrowth: number
): GridBox {
  const rows: number = pitchYDp <= 0 ? 1 : (truncDiv(bandHeightDp, pitchYDp) > 1 ? truncDiv(bandHeightDp, pitchYDp) : 1);
  let cols: number;
  if (zone !== HudZone.READOUT) {
    cols = gridColsCapOf(zone);
  } else if (pitchXDp <= 0) {
    cols = 1;
  } else {
    cols = clampInt(truncDiv(roomWidthDp, pitchXDp), 1, gridColsCapOf(zone));
  }
  const clampedCols: number = reservedCols > 0
    ? Math.max(1, Math.min(cols, reservedCols + colGrowth))
    : (cols > 1 ? cols : 1);
  return new GridBox(clampedCols, editableGridRowsOf(reservedRows, rows, rowGrowth));
}

/**
 * 可编辑的行档数：预留 + 生长余量，再夹进带内实际档数；`reservedRows <= 0`（读数块那一支）
 * 与改前逐字同值 = 带内实际档数。生长由 `growthRows` 显式喂进来（上游给了一个可读默认值，
 * 本侧逐字保留）。带高不够时结果就是 `bandRows`，不抛、不给空区间。
 */
export function editableGridRowsOf(
  reservedRows: number, bandRows: number, growthRows: number = GRID_ROW_GROWTH_ROWS
): number {
  if (reservedRows <= 0) {
    return bandRows > 1 ? bandRows : 1;
  }
  const grown: number = reservedRows + growthRows;
  const capped: number = bandRows < grown ? bandRows : grown;
  return capped > 1 ? capped : 1;
}

/**
 * 把格子钳进可放带（负数与越界都收回来；[GridCell.DEFAULT] 原样放行）。
 *
 * ⚠ 它是**格 → 格**的函数，不吃条目身份，所以配对格不可能被钳进两个不同格子。
 * #75：纵向钳的是**整块**，不是起始档（行上限 = `box.rows − rowSpan`）。
 * 两个形参**没有默认值**：内容高由调用方实测（#69 铁律）。
 */
export function clampCellToBox(
  cell: GridCell, box: GridBox, contentHeightPx: number, rowPitchPx: number
): GridCell {
  if (cell.isDefault) {
    return cell;
  }
  const col: number = clampInt(cell.col, 0, box.cols - 1);
  const rowHi: number = box.rows - cellRowSpan(contentHeightPx, rowPitchPx);
  const row: number = clampInt(cell.row, 0, rowHi > 0 ? rowHi : 0);
  return new GridCell(col, row);
}

/**
 * 同容器内一次落点的唯一裁决（拖到已占格**交换**，而不是跳到最近空格）。
 *
 * 九步见上游 KDoc；要点：块尾出带 / 别颗高格跨度尾 / 整块压人 ⇒ 退回 [freeCellNear]；
 * 交换只动被点名的那一颗（`moved`），跨度尾压到第三颗时**拒绝交换**。
 *
 * `residents` 的键是锚点格 token；`mates` 是同组搭档的**条目 id** 集；
 * `moved` 是「被挤出住户 id → 新锚点格」。所有实测/格距形参**没有默认值**（#69 铁律）。
 */
export function cellDropOf(
  wanted: GridCell,
  origin: GridCell,
  residents: Map<string, Array<HudEntry>>,
  self: HudEntry,
  mates: Set<string>,
  draggedHeightPx: number,
  cellHeightPx: (entry: HudEntry) => number,
  cols: number,
  rows: number,
  rowPitchPx: number
): CellDrop {
  const target: GridCell = new GridCell(
    clampInt(wanted.col, 0, cols - 1 > 0 ? cols - 1 : 0),
    clampInt(wanted.row, 0, rows - 1 > 0 ? rows - 1 : 0)
  );
  const span: number = cellRowSpan(draggedHeightPx, rowPitchPx);
  const blocked: Set<string> = blockingCells(residents, self, mates, cellHeightPx, rowPitchPx);
  // ② 块尾出带 ⇒ 退回就近找空格（与改前同一条兜底，只是不再吞掉"交换"这条路）
  if (target.row > rows - span) {
    return new CellDrop(
      freeCellNear(target, blocked, cols, rows, draggedHeightPx, rowPitchPx),
      new Map<string, GridCell>()
    );
  }
  // ③ 只认"锚点恰在 target"的住户当格主（mates 与 self 自己不算）
  const occupant: Array<HudEntry> = [];
  const here: Array<HudEntry> | undefined = residents.get(target.token);
  if (here !== undefined) {
    for (const e of here) {
      if (!e.equals(self) && !mates.has(e.id)) {
        occupant.push(e);
      }
    }
  }
  if (occupant.length === 0) {
    // ④ 真空格（含拖回同组搭档那一格）⇒ 落它；只是别颗高格的跨度尾巴 ⇒ 退回让位。
    // ④′ 同族整块判据：被拖那颗落下去占的整块（含跨度尾）不许压到**别人**的块上。
    //    排除集必须 mate 感知：只算"非 self 非 mate"的住户，否则合对会被误杀。
    const others: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
    residents.forEach((who: Array<HudEntry>, token: string): void => {
      const keep: Array<HudEntry> = who.filter((e: HudEntry): boolean => !e.equals(self) && !mates.has(e.id));
      if (keep.length > 0) {
        others.set(token, keep);
      }
    });
    const othersBlocked: Set<string> =
      blockingCells(others, null, new Set<string>(), cellHeightPx, rowPitchPx);
    let tailClear: boolean = true;
    for (const c of spannedCells(target, span)) {
      if (othersBlocked.has(c.token)) {
        tailClear = false;
        break;
      }
    }
    if (!blocked.has(target.token) && tailClear) {
      return new CellDrop(target, new Map<string, GridCell>());
    }
    return new CellDrop(
      freeCellNear(target, blocked, cols, rows, draggedHeightPx, rowPitchPx),
      new Map<string, GridCell>()
    );
  }
  // ⑤ 原格能不能承接这一组：扣掉 self 与 occupant 之后再算一遍"谁还占着哪一块"
  const rest: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  const occupantIds: Set<string> = new Set<string>();
  for (const e of occupant) {
    occupantIds.add(e.id);
  }
  residents.forEach((who: Array<HudEntry>, token: string): void => {
    const keep: Array<HudEntry> = who.filter((e: HudEntry): boolean => !e.equals(self) && !occupantIds.has(e.id));
    if (keep.length > 0) {
      rest.set(token, keep);
    }
  });
  const restBlocked: Set<string> = blockingCells(rest, null, new Set<string>(), cellHeightPx, rowPitchPx);
  let occupantBlockPx: number = 0;
  for (const e of occupant) {
    const h: number = cellHeightPx(e);
    const c: number = h > 0 ? h : 0;
    if (c > occupantBlockPx) {
      occupantBlockPx = c;
    }
  }
  const occupantSpan: number = cellRowSpan(occupantBlockPx, rowPitchPx);
  const originInBox: boolean = origin.col >= 0 && origin.col < cols && origin.row >= 0 && origin.row < rows;
  // ⑤a【判据 A】被拖那颗落下去占的整块（含跨度尾）不许压到第三颗的块上
  const selfBlock: Array<GridCell> = spannedCells(target, span);
  let selfClear: boolean = true;
  for (const c of selfBlock) {
    if (restBlocked.has(c.token)) {
      selfClear = false;
      break;
    }
  }
  // ⑤b【判据 B】原格承接：既要避开第三颗，也要避开 self 落下去那一块
  let originClear: boolean = true;
  for (const c of spannedCells(origin, occupantSpan)) {
    let inside: boolean = restBlocked.has(c.token);
    if (!inside) {
      for (const s of selfBlock) {
        if (s.token === c.token) {
          inside = true;
          break;
        }
      }
    }
    if (inside) {
      originClear = false;
      break;
    }
  }
  const originHosts: boolean = !origin.equals(target) && originInBox &&
    origin.row + occupantSpan <= rows && originClear;
  if (selfClear && originHosts) {
    const moved: Map<string, GridCell> = new Map<string, GridCell>();
    for (const e of occupant) {
      moved.set(e.id, origin);
    }
    return new CellDrop(target, moved);
  }
  if (selfClear) {
    // ⑥ 只剩"原格承接不了" ⇒ 占位者就近让位；让位搜索的占位集并进 selfBlock
    const union: Set<string> = new Set<string>();
    restBlocked.forEach((token: string): void => {
      union.add(token);
    });
    for (const c of selfBlock) {
      union.add(c.token);
    }
    const moved: Map<string, GridCell> = new Map<string, GridCell>();
    for (const e of occupant) {
      moved.set(e.id, freeCellNear(origin, union, cols, rows, occupantBlockPx, rowPitchPx));
    }
    return new CellDrop(target, moved);
  }
  // ⑥′ 交换会让 self 的跨度尾压到第三颗 ⇒ 拒绝交换（moved 为空），退回"不挤人"的就近空格
  return new CellDrop(
    freeCellNear(target, blocked, cols, rows, draggedHeightPx, rowPitchPx),
    new Map<string, GridCell>()
  );
}

/**
 * 目标格被占了就在可放带里就近找**空格**（曼哈顿距离最小，同距离按「先上后下、先左后右」定序）。
 *
 * #75：候选格必须**整块**放得下（`spannedCells` 不与 [occupied] 相交），且 `row + rowSpan − 1 <= rows − 1`。
 * 带内一个空格都没有时返回钳进带内的 [wanted] 自己。
 * `occupied` 是**档 token** 集（[blockingCells] 展开后的那一套）。
 */
export function freeCellNear(
  wanted: GridCell,
  occupied: Set<string>,
  cols: number,
  rows: number,
  contentHeightPx: number,
  rowPitchPx: number
): GridCell {
  const c: number = clampInt(wanted.col, 0, cols - 1 > 0 ? cols - 1 : 0);
  const r: number = clampInt(wanted.row, 0, rows - 1 > 0 ? rows - 1 : 0);
  const rowSpan: number = cellRowSpan(contentHeightPx, rowPitchPx);
  const lastRow: number = rows - rowSpan > 0 ? rows - rowSpan : 0;
  const fits: (cell: GridCell) => boolean = (cell: GridCell): boolean => {
    for (const x of spannedCells(cell, rowSpan)) {
      if (occupied.has(x.token)) {
        return false;
      }
    }
    return true;
  };
  if (r <= lastRow && fits(new GridCell(c, r))) {
    return new GridCell(c, r);
  }
  const maxD: number = (cols > 1 ? cols : 1) * (rows > 1 ? rows : 1);
  for (let d = 1; d <= maxD; d++) {
    for (let row = 0; row < rows; row++) {
      if (row > lastRow) {
        continue;
      }
      for (let col = 0; col < cols; col++) {
        const candidate: GridCell = new GridCell(col, row);
        if (!fits(candidate)) {
          continue;
        }
        if (Math.abs(col - c) + Math.abs(row - r) !== d) {
          continue;
        }
        return candidate;
      }
    }
  }
  return new GridCell(c, r);
}

/**
 * 解析一帧的格子清单：摆过的先占位，没摆过的用 [defaultCellsOf] 推，撞了就就近让位。
 *
 * 两趟（先全部显式、再全部推导）：落点规则是 [blockingCells] 那一条（**同组的颗可以共用一格**，
 * 跨组才算撞车），搜索盒用**整容器**的 `[0, cols) × [0, rows)`。
 * 第 2 趟与第 1 趟同一条"锚点优先"口径：推导格的锚点没被占就直接落（合对那一支），
 * 被占了才进 [freeCellNear] 的整块搜索。
 *
 * `placed` / `derived` 的键是条目 id；两个新形参（实测高取器与格距）**没有默认值**（#69 铁律）。
 */
export function resolveCells(
  order: Array<HudEntry>,
  placed: Map<string, GridCell>,
  derived: Map<string, GridCell>,
  cols: number,
  rows: number,
  cellHeightPx: (entry: HudEntry) => number,
  rowPitchPx: number
): Array<HudGridItem> {
  const taken: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  const out: Map<string, GridCell> = new Map<string, GridCell>();
  // 第 1 趟：摆过的条目占位。两颗**不同组**的被手改成同一格时先到先得、后者就近让位；
  // 同组两颗（钉过格子的 S2-2B 配对）共用一格是合法形态，不让位
  for (const e of order) {
    const want: GridCell | undefined = placed.get(e.id);
    if (want === undefined || want.isDefault) {
      continue;
    }
    const blocked: Set<string> = blockingCells(taken, e, cellGroupMatesOf(derived, e), cellHeightPx, rowPitchPx);
    const cell: GridCell = blocked.has(want.token)
      ? freeCellNear(want, blocked, cols, rows, cellHeightPx(e), rowPitchPx)
      : want;
    putResident(taken, cell, e);
    out.set(e.id, cell);
  }
  // 第 2 趟：没摆过的用推导格；推导格被摆过的那颗占了就让位（显式优先）
  for (const e of order) {
    if (out.has(e.id)) {
      continue;
    }
    const blocked: Set<string> = blockingCells(taken, e, cellGroupMatesOf(derived, e), cellHeightPx, rowPitchPx);
    const d: GridCell | undefined = derived.get(e.id);
    const want: GridCell = d === undefined ? new GridCell(0, 0) : d;
    const cell: GridCell = blocked.has(want.token)
      ? freeCellNear(want, blocked, cols, rows, cellHeightPx(e), rowPitchPx)
      : want;
    putResident(taken, cell, e);
    out.set(e.id, cell);
  }
  const result: Array<HudGridItem> = [];
  for (const e of order) {
    const cell: GridCell | undefined = out.get(e.id);
    result.push(new HudGridItem(e, cell === undefined ? GridCell.DEFAULT : cell));
  }
  return result;
}

/**
 * 「今天的排布」→ 格子的唯一一处换算：顺序按 [hudRowGroups] 分行。
 *
 * 行组 → 格子：一枚行组＝一枚格子（竖 Dock），一行组＝一**排**格子（读数块）。
 * #75：row 按**行跨度**推进（`row += span`），不是 +1。
 * 实测高量不到（0）那一档跨度按 1 算，下一帧实测接管。
 */
export function defaultCellsOf(
  zone: HudZone, order: Array<HudEntry>, plan: HudGridPlan
): Map<string, GridCell> {
  const rowPitchPx: number = plan.rowPitchOf(zone);
  let row: number = 0;
  const out: Map<string, GridCell> = new Map<string, GridCell>();
  for (const group of hudRowGroups(zone, order, plan.perRowOf(zone))) {
    let contentPx: number = 0;
    for (const e of group) {
      const h: number = plan.cellHeightOf(e);
      const c: number = h > 0 ? h : 0;
      if (c > contentPx) {
        contentPx = c;
      }
    }
    const span: number = cellRowSpan(contentPx, rowPitchPx);
    if (hudZoneAxis(zone) === HudAxis.GRID) {
      for (let col = 0; col < group.length; col++) {
        out.set(group[col].id, new GridCell(col, row));
      }
    } else {
      for (const entry of group) {
        out.set(entry.id, new GridCell(0, row));
      }
    }
    row += span;
  }
  return out;
}

/**
 * 与 [entry] **同属一枚默认格组**的其他颗（可以合法与它共用一格的那些）。
 * 判据只有一条：**推导格相同**。配对尺子仍然只有 [hudRowGroups] 那一把。
 * 返回条目 id 集（ArkTS 无结构相等）。
 */
export function cellGroupMatesOf(derived: Map<string, GridCell>, entry: HudEntry): Set<string> {
  const out: Set<string> = new Set<string>();
  const home: GridCell | undefined = derived.get(entry.id);
  if (home === undefined) {
    return out;
  }
  derived.forEach((cell: GridCell, id: string): void => {
    if (id !== entry.id && cell.equals(home)) {
      out.add(id);
    }
  });
  return out;
}

// ------------------------------------------------------------------ 格子 ↔ 像素（渲染与编辑页共用）

/**
 * 格长 = **横向：该容器内最宽那枚格子的内容宽 + 一道列距；纵向：与住户无关的固定格距**。
 * #75 起纵向不再"取最高那颗"：纵向恒等于 [gridRowPitchPx]（**已经含那道行距**，不再 `+ gapYPx`）。
 */
export function gridPitchPx(maxCellWidthPx: number, rowPitchPx: number, gapXPx: number): HudSizePx {
  return new HudSizePx(maxCellWidthPx + gapXPx, rowPitchPx);
}

/**
 * **纵向格距**（px）＝ 一颗紧凑档胶囊的高 + 该容器的一道行距，向上取整。
 * 本机（density 2.0）手算：竖 Dock 100% **68px = 34dp**、120% 76px；读数块 100% **72px = 36dp**。
 * 向上取整是有意的：格距是"一行有多少预算"，不是量出来的尺寸。
 */
export function gridRowPitchPx(fontScale: number, gapDp: number, density: number): number {
  const px: number = Math.ceil((hudChipHeightDp(fontScale) + gapDp) * density);
  return px > 1 ? px : 1;
}

/**
 * 按容器取格距（上游 UI 层 `hudGridRowPitchPx(zone, Density)` 的纯逻辑等价物）：
 * 行距档走 [hudGridGapDp]（竖 Dock 4dp / 读数块 6dp）——三处消费方共读这一条出口。
 * ArkTS 适配：上游吃 Compose `Density`，这里吃 `fontScale` + `density` 两个数（纯逻辑不引平台类型）。
 */
export function hudGridRowPitchPx(zone: HudZone, fontScale: number, density: number): number {
  return gridRowPitchPx(fontScale, hudGridGapDp(zone), density);
}

/**
 * 一枚格子的**内容宽** = 格内各颗实测宽之和 + (n − 1) 道行距（一颗时就是它自己的宽）。
 * 录制中右 Dock 那枚配对格：姿态仪 108 + 8 + 音量表 56 = **172px = 86dp**。
 */
export function cellContentWidthPx(childWidthsPx: Array<number>, gapXPx: number): number {
  if (childWidthsPx.length === 0) {
    return 0;
  }
  let sum: number = 0;
  for (const w of childWidthsPx) {
    sum += w;
  }
  return sum + gapXPx * (childWidthsPx.length - 1);
}

/**
 * 一枚格子的**左缘**相对格网原点（px，#80）：col 0 贴哪条边由 [gridColFromEndOf] 说了算。
 * 贴右缘那一支：`slot(c) = gridWidth + gap − (c+1)×pitch`；单列时与从左数**逐字同值**。
 */
export function cellSlotLeftPx(
  col: number, pitch: HudSizePx, gridWidthPx: number, gapXPx: number, colFromEnd: boolean
): number {
  const c: number = col > 0 ? col : 0;
  return colFromEnd ? gridWidthPx - (c + 1) * pitch.width + gapXPx : c * pitch.width;
}

/**
 * 格内各颗相对**网格原点**的左缘（px）：整枚格子在它那一格里居中，再按各颗实测宽 + 行距依次排开。
 * 单颗格子时与 [cellPlaceOffsetPx] 的 x 逐字同值。
 * [gridWidthPx] 与 [colFromEnd] 是 #80 加的，**没有默认值**（给了默认值就等于把"贴右缘从右数"
 * 悄悄退回从左数）。
 */
export function cellChildLeftsPx(
  cell: GridCell,
  pitch: HudSizePx,
  childWidthsPx: Array<number>,
  gapXPx: number,
  gridWidthPx: number,
  colFromEnd: boolean
): Array<number> {
  const out: Array<number> = [];
  if (childWidthsPx.length === 0) {
    return out;
  }
  const content: number = cellContentWidthPx(childWidthsPx, gapXPx);
  let x: number = cellSlotLeftPx(cell.col, pitch, gridWidthPx, gapXPx, colFromEnd) +
    truncDiv(pitch.width - content, 2);
  for (const width of childWidthsPx) {
    out.push(x);
    x += width + gapXPx;
  }
  return out;
}

/**
 * 该容器的 col 0 贴哪条边（#80）。只有右竖 Dock 贴右缘：它原生对齐是 `CenterEnd`，
 * 第 2 列必须**向左**生长，否则一用出第 2 列整块就向左平移一档。
 * 左 Dock 贴左缘 ⇒ 第 2 列向右生长；读数块保持"col 0 在左"（那两枚一组的默认顺序是定版观感）。
 */
export function gridColFromEndOf(zone: HudZone): boolean {
  return zone === HudZone.RIGHT;
}

/** 默认表里每枚格子的住户实测高（预留行档数与预留格长共用的那一份分组） */
function groupDefaultCellHeights(
  defaultCells: Map<string, GridCell>, cellHeightPx: (entry: HudEntry) => number
): Map<string, Array<number>> {
  const out: Map<string, Array<number>> = new Map<string, Array<number>>();
  defaultCells.forEach((cell: GridCell, id: string): void => {
    const entry: HudEntry | undefined = HudEntry.fromId(id);
    if (entry === undefined) {
      return;
    }
    const h: number = cellHeightPx(entry);
    const c: number = h > 0 ? h : 0;
    const cur: Array<number> | undefined = out.get(cell.token);
    if (cur === undefined) {
      out.set(cell.token, [c]);
    } else {
      cur.push(c);
    }
  });
  return out;
}

/** 默认表里最宽那枚格子的内容宽（px，#80：格长的唯一来源）；量不到宽的颗按 0 算 */
function defaultCellWidthsPx(
  defaultCells: Map<string, GridCell>, childWidthOf: (entry: HudEntry) => number, gapXPx: number
): Array<number> {
  const grouped: Map<string, Array<number>> = new Map<string, Array<number>>();
  defaultCells.forEach((cell: GridCell, id: string): void => {
    const entry: HudEntry | undefined = HudEntry.fromId(id);
    if (entry === undefined) {
      return;
    }
    const w: number = childWidthOf(entry);
    const c: number = w > 0 ? w : 0;
    const cur: Array<number> | undefined = grouped.get(cell.token);
    if (cur === undefined) {
      grouped.set(cell.token, [c]);
    } else {
      cur.push(c);
    }
  });
  const out: Array<number> = [];
  grouped.forEach((widths: Array<number>): void => {
    out.push(cellContentWidthPx(widths, gapXPx));
  });
  return out;
}

/**
 * 预留的**行档数**（#80）：默认表用到几档就是几档，0 = 不预留（读数块那一支）。
 *
 * 档数按 `max(row + 行跨度)` 算，与 [defaultCellsOf] 推进默认行号用的是同一条 [cellRowSpan] 算式。
 * [cellHeightPx] 与 [rowPitchPx] 都是无默认值的必传形参（#69 铁律）。
 */
export function reservedGridRowsOf(
  anchor: GridAnchor, cellHeightPx: (entry: HudEntry) => number, rowPitchPx: number
): number {
  if (anchor.zone === HudZone.READOUT || anchor.defaultCells.size === 0) {
    return 0;
  }
  const heightsByCell: Map<string, Array<number>> = groupDefaultCellHeights(anchor.defaultCells, cellHeightPx);
  let best: number = 0;
  heightsByCell.forEach((heights: Array<number>, token: string): void => {
    const cell: GridCell = gridCellOfToken(token);
    const row: number = cell.row > 0 ? cell.row : 0;
    const end: number = row + cellRowSpan(maxOrNull(heights, 0), rowPitchPx);
    if (end > best) {
      best = end;
    }
  });
  return best;
}

/**
 * 预留的**列数**（#80）：只对读数块生效（透明底板 ⇒ 预留不花观感钱），其余容器返回 0 = 底板包内容。
 */
export function reservedGridColsOf(anchor: GridAnchor): number {
  if (anchor.zone !== HudZone.READOUT || anchor.defaultCells.size === 0) {
    return 0;
  }
  let widest: number = 0;
  anchor.defaultCells.forEach((cell: GridCell): void => {
    const col: number = cell.col > 0 ? cell.col : 0;
    if (col > widest) {
      widest = col;
    }
  });
  return widest + 1;
}

/**
 * 一帧格网的**完整测量结果**：格长、格网尺寸、每颗相对网格原点的左上角。
 *
 * 三条规则：① 逐格累计住户（清单序 = 格内左右序）；② 格长横向取**默认分组**里最宽那枚格子
 * （#80；渲染分组永远不宽过它，取两者的大只兜坏数据），纵向 = 传进来的固定 [rowPitchPx]；
 * ③ 总行数**按跨度算**（`max(row + span − 1) + 1`），漏掉这一笔最后一枚高格的尾巴会画出格网。
 *
 * [rowPitchPx] **没有默认值**：必须由调用方喂进来，否则两页的格子高度立刻分叉。
 */
export function gridPlacementOf(
  items: Array<HudGridItem>,
  childSizes: Array<HudSizePx>,
  gapPx: HudSizePx,
  rowPitchPx: number,
  anchor: GridAnchor
): GridPlacement {
  if (items.length !== childSizes.length) {
    throw new Error(`格网测量：颗数与尺寸数不等 ${items.length} vs ${childSizes.length}`);
  }
  const widthOf: Map<string, number> = new Map<string, number>();
  const heightOf: Map<string, number> = new Map<string, number>();
  for (let i = 0; i < items.length; i++) {
    widthOf.set(items[i].entry.id, childSizes[i].width);
    heightOf.set(items[i].entry.id, childSizes[i].height);
  }
  const cells: Map<string, Array<number>> = new Map<string, Array<number>>();
  for (let i = 0; i < items.length; i++) {
    const token: string = items[i].cell.token;
    const cur: Array<number> | undefined = cells.get(token);
    if (cur === undefined) {
      cells.set(token, [i]);
    } else {
      cur.push(i);
    }
  }
  const cellWidths: Array<number> = [];
  cells.forEach((idx: Array<number>): void => {
    const widths: Array<number> = [];
    for (const i of idx) {
      widths.push(childSizes[i].width);
    }
    cellWidths.push(cellContentWidthPx(widths, gapPx.width));
  });
  const defaultWidths: Array<number> = defaultCellWidthsPx(anchor.defaultCells, (entry: HudEntry): number => {
    const w: number | undefined = widthOf.get(entry.id);
    return w === undefined ? 0 : w;
  }, gapPx.width);
  const maxWidth: number = Math.max(maxOrNull(defaultWidths, 0), maxOrNull(cellWidths, 0));
  const pitch: HudSizePx = gridPitchPx(maxWidth, rowPitchPx, gapPx.width);
  // 每枚格子的行跨度：格内最高那颗 ÷ 格距
  const spans: Map<string, number> = new Map<string, number>();
  cells.forEach((idx: Array<number>, token: string): void => {
    let tallest: number = 0;
    for (const i of idx) {
      const h: number = childSizes[i].height;
      if (h > tallest) {
        tallest = h;
      }
    }
    spans.set(token, cellRowSpan(tallest, pitch.height));
  });
  let usedCols: number = 0;
  for (const item of items) {
    const col: number = item.cell.col > 0 ? item.cell.col : 0;
    if (col + 1 > usedCols) {
      usedCols = col + 1;
    }
  }
  let usedRows: number = 0;
  cells.forEach((idx: Array<number>, token: string): void => {
    const cell: GridCell = gridCellOfToken(token);
    const span: number | undefined = spans.get(token);
    const s: number = span === undefined ? 1 : span;
    const end: number = (cell.row > 0 ? cell.row : 0) + (s - 1);
    if (end + 1 > usedRows) {
      usedRows = end + 1;
    }
  });
  const cols: number = Math.max(usedCols, reservedGridColsOf(anchor));
  const rows: number = Math.max(usedRows, reservedGridRowsOf(anchor, (entry: HudEntry): number => {
    const h: number | undefined = heightOf.get(entry.id);
    return h === undefined ? 0 : h;
  }, pitch.height));
  const size: HudSizePx = gridSizePx(cols, rows, pitch, gapPx);
  const colFromEnd: boolean = gridColFromEndOf(anchor.zone);
  const offsets: Array<HudPointPx> = [];
  for (let i = 0; i < items.length; i++) {
    offsets.push(new HudPointPx(0, 0));
  }
  cells.forEach((idx: Array<number>, token: string): void => {
    const cell: GridCell = gridCellOfToken(token);
    const widths: Array<number> = [];
    for (const i of idx) {
      widths.push(childSizes[i].width);
    }
    const lefts: Array<number> = cellChildLeftsPx(cell, pitch, widths, gapPx.width, size.width, colFromEnd);
    const spanValue: number | undefined = spans.get(token);
    const s: number = spanValue === undefined ? 1 : spanValue;
    for (let k = 0; k < idx.length; k++) {
      const childIndex: number = idx[k];
      offsets[childIndex] = new HudPointPx(lefts[k],
        cellPlaceOffsetPx(cell, pitch, childSizes[childIndex], s).y);
    }
  });
  return new GridPlacement(pitch, size, offsets);
}

/**
 * 一格多颗时"指针压住的是哪一颗"（配对 = 一格两颗，编辑器必须能点名要搬哪一颗）。
 * 先取横向区间真的压住指针的那些颗；都不压住时把候选放宽到全部；候选里取**区间中心离指针最近**
 * 的那颗，同距离取靠左那颗。空候选返回 -1。
 */
export function entryHitIndex(spans: Array<IntRange>, pointerX: number): number {
  if (spans.length === 0) {
    return -1;
  }
  const inside: Array<number> = [];
  for (let i = 0; i < spans.length; i++) {
    if (spans[i].contains(pointerX)) {
      inside.push(i);
    }
  }
  const pool: Array<number> = inside.length > 0 ? inside : spans.map((_s: IntRange, i: number): number => i);
  let best: number = pool[0];
  let bestD: number = -1;
  for (const i of pool) {
    const center: number = truncDiv(spans[i].first + spans[i].last, 2);
    const d: number = Math.abs(center - pointerX);
    if (bestD < 0 || d < bestD) {
      bestD = d;
      best = i;
    }
  }
  return best;
}

/** 网格总尺寸：`列数 × 格宽 − 一道行距`（最后一格后面不再有间距） */
export function gridSizePx(cols: number, rows: number, pitch: HudSizePx, gap: HudSizePx): HudSizePx {
  if (cols <= 0 || rows <= 0) {
    return new HudSizePx(0, 0);
  }
  return new HudSizePx(cols * pitch.width - gap.width, rows * pitch.height - gap.height);
}

/**
 * [gridSizePx] 的逆算：编辑页拿网格节点的**实测矩形**把**列**长除回来。
 * 空容器退回"被拖那颗自己的尺寸 + 行距"。
 * #75 之后纵向**不许**再从这条逆算（带跨度后 `网格高 ÷ 行数 ≠ 行距`）；本函数的 height
 * 只是"与 [gridSizePx] 互为逆运算"这条性质的测试对象，生产调用点只取 `.width`，纵向走 [snapGridPitchPx]。
 */
export function gridPitchOf(
  gridWidthPx: number,
  gridHeightPx: number,
  cols: number,
  rows: number,
  gapPx: number,
  fallbackChildWidthPx: number,
  fallbackChildHeightPx: number
): HudSizePx {
  const w: number = cols > 0 ? truncDiv(gridWidthPx + gapPx, cols) : Math.max(1, fallbackChildWidthPx + gapPx);
  const h: number = rows > 0 ? truncDiv(gridHeightPx + gapPx, rows) : Math.max(1, fallbackChildHeightPx + gapPx);
  return new HudSizePx(w, h);
}

/**
 * 编辑页吸附用的**格长**：横向从网格实测矩形逆算（[gridPitchOf]），纵向**直接用格距**。
 * [rowPitchPx] 与 [fallbackChildWidthPx] 都是无默认值的必传形参（#69 铁律）。
 */
export function snapGridPitchPx(
  gridWidthPx: number,
  cols: number,
  gapPx: number,
  fallbackChildWidthPx: number,
  rowPitchPx: number
): HudSizePx {
  const width: number = gridPitchOf(gridWidthPx, 0, cols, 0, gapPx, fallbackChildWidthPx, 0).width;
  return new HudSizePx(width, rowPitchPx);
}

/** 某格的左上角相对网格原点（格子 → 坐标的正算式，[cellAtPointer] 是它的逆） */
export function cellOffsetPx(cell: GridCell, pitch: HudSizePx): HudPointPx {
  const col: number = cell.col > 0 ? cell.col : 0;
  const row: number = cell.row > 0 ? cell.row : 0;
  return new HudPointPx(col * pitch.width, row * pitch.height);
}

/** 向下取整的除法（`/` 对负数是截断，指针拖到网格左上方时会给出 -0 这种暧昧值） */
function floorDiv(v: number, p: number): number {
  const q: number = truncDiv(v, p);
  return v % p !== 0 && (v < 0) !== (p < 0) ? q - 1 : q;
}

/** 向上取整的除法（#80 那一支"从右往里数列"的逆算式用；与 [floorDiv] 同一套负数语义） */
function ceilDiv(v: number, p: number): number {
  return -floorDiv(-v, p);
}

/**
 * 指针 → 它**压住的那一格**（编辑页吸附用的逆算式，与 [cellOffsetPx] / [cellSlotLeftPx] 一对一）。
 * 取"压住"（floor）而不是"中心最近"（round）。
 * [gridWidthPx]、[gapPx]、[colFromEnd] 三个形参**没有默认值**（漏挂会把"从右数"退回"从左数"）。
 */
export function cellAtPointer(
  pointer: HudPointPx,
  origin: HudPointPx,
  pitch: HudSizePx,
  gridWidthPx: number,
  gapPx: number,
  colFromEnd: boolean
): GridCell {
  if (pitch.width <= 0 || pitch.height <= 0) {
    return new GridCell(0, 0);
  }
  const localX: number = pointer.x - origin.x;
  let col: number;
  if (colFromEnd) {
    col = ceilDiv(gridWidthPx + gapPx - localX, pitch.width) - 1;
  } else {
    col = floorDiv(localX, pitch.width);
  }
  const row: number = floorDiv(pointer.y - origin.y, pitch.height);
  return new GridCell(col > 0 ? col : 0, row > 0 ? row : 0);
}

/**
 * 格内的居中偏移：纵向在**整块**里居中（`span=1` 时与 #74 逐字同值）。
 * [rowSpan] **没有默认值**：漏挂会把 72dp 的姿态仪塞回一档里、与下一颗叠字。
 */
export function cellPlaceOffsetPx(
  cell: GridCell, pitch: HudSizePx, child: HudSizePx, rowSpan: number
): HudPointPx {
  const g: HudPointPx = cellOffsetPx(cell, pitch);
  const span: number = rowSpan > 1 ? rowSpan : 1;
  const blockHeightPx: number = pitch.height * span;
  return new HudPointPx(
    g.x + truncDiv(pitch.width - child.width, 2),
    g.y + truncDiv(blockHeightPx - child.height, 2)
  );
}

/**
 * 外接矩形重叠检测（重叠**只提示不禁止**）：返回按 [hudZoneAll] 顺序去重后的容器名对。
 * `rects` 的键是容器 key（ArkTS 无结构相等的适配）。
 */
export function overlappingZones(rects: Map<string, HudRectDp>): Array<HudZonePair> {
  const out: Array<HudZonePair> = [];
  const present: Array<HudZone> = [];
  for (const zone of HUD_ZONES) {
    if (rects.has(hudZoneKey(zone))) {
      present.push(zone);
    }
  }
  for (let i = 0; i < present.length; i++) {
    for (let j = i + 1; j < present.length; j++) {
      const a: HudRectDp | undefined = rects.get(hudZoneKey(present[i]));
      const b: HudRectDp | undefined = rects.get(hudZoneKey(present[j]));
      if (a !== undefined && b !== undefined && a.intersects(b)) {
        out.push(new HudZonePair(present[i], present[j]));
      }
    }
  }
  return out;
}

/**
 * 落点在哪枚容器：命中**最里面**（面积最小）的那枚，都不命中返回 undefined（= 这次拖动不改归属）。
 * 取最小面积而不是第一个命中，是因为读数块贴在底栏 Dock 右上时会嵌套。
 */
export function zoneAt(pointer: HudPointDp, rects: Map<string, HudRectDp>): HudZone | undefined {
  let best: HudZone | undefined = undefined;
  let bestArea: number = -1;
  // 按调用方 Map 的插入序扫（与上游 `rects.filter{…}.minByOrNull` 同一条定序）；
  // 面积相同时保留先出现的那个（`minByOrNull` 返回首个最小值）。
  rects.forEach((rect: HudRectDp, key: string): void => {
    const zone: HudZone | undefined = hudZoneFromKey(key);
    if (zone === undefined || !rect.contains(pointer.x, pointer.y)) {
      return;
    }
    const area: number = rect.width * rect.height;
    if (bestArea < 0 || area < bestArea) {
      bestArea = area;
      best = zone;
    }
  });
  return best;
}

/** 按竖直方向重叠把条目分簇成「行」（保持入参顺序，簇内也按入参顺序） */
function clusterRows(items: Array<HudRectDp>): Array<Array<HudRectDp>> {
  const rows: Array<Array<HudRectDp>> = [];
  const sorted: Array<HudRectDp> = items.slice().sort((a: HudRectDp, b: HudRectDp): number => a.top - b.top);
  for (const rect of sorted) {
    let hit: Array<HudRectDp> | undefined = undefined;
    for (const row of rows) {
      const r: HudRectDp = row[0];
      if (rect.top < r.bottom && r.top < rect.bottom) {
        hit = row;
        break;
      }
    }
    if (hit === undefined) {
      const created: Array<HudRectDp> = [];
      rows.push(created);
      created.push(rect);
    } else {
      hit.push(rect);
    }
  }
  const out: Array<Array<HudRectDp>> = [];
  for (const row of rows) {
    const ordered: Array<HudRectDp> = [];
    for (const rect of items) {
      if (row.indexOf(rect) >= 0) {
        ordered.push(rect);
      }
    }
    if (ordered.length > 0) {
      out.push(ordered);
    }
  }
  return out;
}

/**
 * 松手后条目该插到目标容器的第几格（返回 0..size，越界由 [HudLayoutTable.moveEntryTo] 再夹一次）。
 * ROW / COLUMN：沿主轴数「有几颗的中心比指针更靠前」；GRID：先按上下边界把行分簇，再在指针那一行里数横向。
 */
export function dropIndexFor(axis: HudAxis, items: Array<HudRectDp>, pointer: HudPointDp): number {
  if (items.length === 0) {
    return 0;
  }
  if (axis === HudAxis.ROW) {
    let n: number = 0;
    for (const it of items) {
      if (truncDiv(it.left + it.right, 2) < pointer.x) {
        n++;
      }
    }
    return n;
  }
  if (axis === HudAxis.COLUMN) {
    let n: number = 0;
    for (const it of items) {
      if (truncDiv(it.top + it.bottom, 2) < pointer.y) {
        n++;
      }
    }
    return n;
  }
  const rows: Array<Array<HudRectDp>> = clusterRows(items);
  let row: Array<HudRectDp> | undefined = undefined;
  for (const r of rows) {
    if (pointer.y >= r[0].top && pointer.y <= r[r.length - 1].bottom) {
      row = r;
      break;
    }
  }
  if (row === undefined) {
    let bestD: number = -1;
    for (const r of rows) {
      const center: number = truncDiv(r[0].top + r[r.length - 1].bottom, 2);
      const d: number = Math.abs(center - pointer.y);
      if (bestD < 0 || d < bestD) {
        bestD = d;
        row = r;
      }
    }
  }
  if (row === undefined) {
    return items.length;
  }
  let before: number = 0;
  for (const r of rows) {
    if (r === row) {
      break;
    }
    before += r.length;
  }
  let n: number = 0;
  for (const it of row) {
    if (truncDiv(it.left + it.right, 2) < pointer.x) {
      n++;
    }
  }
  return before + n;
}

// ------------------------------------------------------------------ 第 8 条：底栏 Dock 的上栏 / 下栏

/**
 * 底栏 Dock 的两档 y：**下栏 = 定稿位置**（底边贴安全区底再让开 `bottomPadDp`），
 * **上栏 = 下栏再上一整排**，间距一枚 `gapDp`。全程只有实测高与令牌，没有机型数值。
 */
export function dockLowerY(area: HudAreaDp, heightDp: number, bottomPadDp: number): number {
  const y: number = area.height - bottomPadDp - heightDp;
  return y > 0 ? y : 0;
}

export function dockUpperY(area: HudAreaDp, heightDp: number, bottomPadDp: number, gapDp: number): number {
  const y: number = dockLowerY(area, heightDp, bottomPadDp) - heightDp - gapDp;
  return y > 0 ? y : 0;
}

/**
 * 拖动位移 → 落在哪一栏（**只有两档**）：往上过半程去上栏，其余一律回下栏。
 * [deltaYDp] 是相对下栏的竖直位移（屏幕坐标，向上为负）。
 */
export function dockSnapY(
  area: HudAreaDp, heightDp: number, bottomPadDp: number, gapDp: number, deltaYDp: number
): number {
  const lower: number = dockLowerY(area, heightDp, bottomPadDp);
  const upper: number = dockUpperY(area, heightDp, bottomPadDp, gapDp);
  const pitch: number = lower - upper;
  return pitch > 0 && -deltaYDp >= pitch / 2 ? upper : lower;
}

/**
 * 第 8 条进入拖拽的 gate（正在录的时候不能让人把底栏搬走）。
 * PREPARE / START / STOPPING 三态一律拒绝；ERROR 不算忙。
 */
export function dockDragBlocked(status: HudDockStatus): boolean {
  return status === HudDockStatus.PREPARE || status === HudDockStatus.START ||
    status === HudDockStatus.STOPPING;
}

/**
 * 右竖 Dock 的下界要让开「读数块所在的那一横带」，但**不许低于底栏那一排自己的下界**：
 * 两块共用底栏那一行（同一基线）是**并联**关系 ⇒ 取大而不是求和。
 * 坏值（负高）按 0 处理。
 */
export function areaForRightDock(area: HudAreaDp, readoutHeightDp: number, readoutBottomDp: number): HudAreaDp {
  const h: number = readoutHeightDp > 0 ? readoutHeightDp : 0;
  const wanted: number = readoutBottomDp + h;
  return new HudAreaDp(area.width, area.height, area.topAvoidDp,
    area.bottomAvoidDp > wanted ? area.bottomAvoidDp : wanted);
}

// ------------------------------------------------------------------ #70 A：读数块与底栏那一行

/** 用户 2026-09-29 12:20 定版的列数上限：横屏「读数缩到一行两颗」，不是按剩余宽度算到 3 */
export const DOCK_ROW_PER_ROW_CAP: number = 2;

/**
 * 「这一帧有横行可言」的下限：右半带至少排得下两颗才算同行。
 * 与 [DOCK_ROW_PER_ROW_CAP] 同为 2 但**不是同一个数**：那条是上限，这条是门槛。
 */
const MIN_COLUMNS_FOR_SHARED_ROW: number = 2;

/**
 * 与底栏同一行时**整块读数**（含它自己的内边距）允许占的那一段宽度：
 * 底栏右缘 + 一枚间距 → 安全区右缘 − 设计留白。
 */
export function readoutStripWidthDp(
  safeWidthDp: number, dockWidthDp: number, endPadDp: number, dockGapDp: number
): number {
  const w: number = safeWidthDp / 2 - dockWidthDp / 2 - dockGapDp - endPadDp;
  return w > 0 ? w : 0;
}

/**
 * 与底栏同一行时读数块的可用内宽（走 [hudRoomDp]，与整幅取档那条同一个表达式、同一批真源）。
 * 极窄时整条被 [hudRoomDp] 夹到 0，不会给负数。
 */
export function readoutRoomBesideDockDp(
  safeWidthDp: number, dockWidthDp: number, endPadDp: number, dockGapDp: number
): number {
  return hudRoomDp(safeWidthDp / 2 - dockWidthDp / 2 - dockGapDp, endPadDp);
}

/**
 * [ReadoutRowPlan] 的算式本体。两页（录制页 / 编辑页）必须调这一条，不许各写一份判据（S3-5 同源）。
 *
 * 两个形参**没有默认值**，都是承重的（#69 铁律）：`landscape` 由调用方从方向送进来；
 * `readoutWidthDp` 实测块宽（给默认值 0 等于"永远放行"，新调用点漏传就拆了竖屏那道安全网）。
 *
 * @param dockRowBaselineDp 两枚容器共用的那条基线（= 底板可见底边的高度）
 */
export function planReadoutRow(
  readoutCount: number,
  fontScale: number,
  safeWidthDp: number,
  dockWidthDp: number,
  dockStripDp: number,
  dockRowBaselineDp: number,
  endPadDp: number,
  dockGapDp: number,
  landscape: boolean,
  readoutWidthDp: number
): ReadoutRowPlan {
  const besideRoom: number = readoutRoomBesideDockDp(safeWidthDp, dockWidthDp, endPadDp, dockGapDp);
  const perRowBeside: number = hudPerRowFor(readoutCount, fontScale, besideRoom);
  // 同基线档的容器让位：基线减掉块自己那枚内边距，胶囊可见底边才与底板可见底边齐平
  const dockRowBottomDp: number = Math.max(0, dockRowBaselineDp - HUD_BLOCK_PAD_DP);
  if (landscape) {
    // 横屏（定版）：恒同行、列数夹到 [DOCK_ROW_PER_ROW_CAP]。这一支**不读** readoutWidthDp
    return new ReadoutRowPlan(true, dockRowBottomDp,
      Math.min(DOCK_ROW_PER_ROW_CAP, perRowBeside), besideRoom);
  }
  const fullRoom: number = hudRoomDp(safeWidthDp, endPadDp);
  const perRowFull: number = hudPerRowFor(readoutCount, fontScale, fullRoom);
  // 安全网：估宽偏小 ⇒ 再用**实测块宽**兜一道（量不到那一帧 0 = 先按估宽走）
  const stripWidth: number = readoutStripWidthDp(safeWidthDp, dockWidthDp, endPadDp, dockGapDp);
  const measuredFitsBeside: boolean = readoutWidthDp <= 0 || readoutWidthDp <= stripWidth;
  if (perRowBeside >= MIN_COLUMNS_FOR_SHARED_ROW && measuredFitsBeside) {
    return new ReadoutRowPlan(true, dockRowBottomDp, perRowBeside, besideRoom);
  }
  // 退化档的列数必须对**本帧这次决策不敏感**，否则 `块宽→档位` 会把自己闩死（S1）
  const perRow: number = perRowBeside >= MIN_COLUMNS_FOR_SHARED_ROW
    ? Math.min(perRowBeside, perRowFull)
    : perRowFull;
  return new ReadoutRowPlan(false, dockStripDp, perRow, fullRoom);
}

// ------------------------------------------------------------------ 容器内的条目渲染分组

/**
 * 把一枚容器的可见条目切成「一行一行」，五枚容器共用这一条算式（B1–B3 的高度账就靠它）。
 *
 * - 顶栏 / 底栏：横排一行；
 * - 左竖 Dock：一行一颗；
 * - 右竖 Dock：**S2-2 B 那条并排规则保留**——姿态仪与音量表相邻时并成一行（#74 之后是**一枚格子
 *   里放两颗**，见 [defaultCellsOf]，用户拆开就不再并排）；
 * - 读数块：按 `perRow` 分行。
 */
export function hudRowGroups(zone: HudZone, order: Array<HudEntry>, perRow: number): Array<Array<HudEntry>> {
  if (zone === HudZone.TOP || zone === HudZone.BOTTOM) {
    return order.length === 0 ? [] : [order.slice()];
  }
  if (zone === HudZone.LEFT) {
    return order.map((entry: HudEntry): Array<HudEntry> => [entry]);
  }
  if (zone === HudZone.RIGHT) {
    const groups: Array<Array<HudEntry>> = [];
    const levelId: string = HudEntry.ofPill(CamPill.LEVEL).id;
    const volumeId: string = HudEntry.ofPill(CamPill.VOLUME).id;
    let i: number = 0;
    while (i < order.length) {
      const next: HudEntry | undefined = i + 1 < order.length ? order[i + 1] : undefined;
      const nextId: string | undefined = next === undefined ? undefined : next.id;
      if (order[i].id === levelId && nextId === volumeId) {
        groups.push([order[i], order[i + 1]]);
        i += 2;
      } else if (order[i].id === volumeId && nextId === levelId) {
        groups.push([order[i], order[i + 1]]);
        i += 2;
      } else {
        groups.push([order[i]]);
        i++;
      }
    }
    return groups;
  }
  const per: number = perRow < 1 ? 1 : perRow;
  const out: Array<Array<HudEntry>> = [];
  for (let i = 0; i < order.length; i += per) {
    out.push(order.slice(i, i + per));
  }
  return out;
}

/**
 * 右竖 Dock 的「并排」判据（单独暴露给用例）：姿态仪与音量表**相邻**才并排。
 * 用户把音量表挪到别处、或把它关掉了，都必须退回一行一颗。
 */
export function rightDockPacksLevelAndVolume(order: Array<HudEntry>): boolean {
  const levelId: string = HudEntry.ofPill(CamPill.LEVEL).id;
  const volumeId: string = HudEntry.ofPill(CamPill.VOLUME).id;
  let level: number = -1;
  let volume: number = -1;
  for (let i = 0; i < order.length; i++) {
    if (level < 0 && order[i].id === levelId) {
      level = i;
    }
    if (volume < 0 && order[i].id === volumeId) {
      volume = i;
    }
  }
  return level >= 0 && volume >= 0 && Math.abs(level - volume) === 1;
}
