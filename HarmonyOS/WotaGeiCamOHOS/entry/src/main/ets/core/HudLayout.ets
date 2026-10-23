/**
 * 「编辑控件」页与录制页共用的位置模型（纯逻辑地基，J1 批）。
 *
 * 直译自上游 `ui/HudLayout.kt` 的**类型 + 序列化 + 夹取/落区**子集。上游文件 2252 行里有大半是
 * 网格几何与拖拽落点（`gridBoxOf` / `clampCellToBox` / `cellDropOf` / `freeCellNear` /
 * `resolveCells` / `defaultCellsOf` / `gridPitchPx` / `gridRowPitchPx` …），那些留 J2；
 * 本批只交付「位置表能不能安全存下来、拖出屏幕时钳到哪」这一层。
 *
 * ## 两级：容器位置 + 条目格子
 * - **容器整体**（[HudZone]，5 枚）各一份 (x, y)——**底栏只有 y 一轴**（x 恒哨兵）；
 * - **容器内的每一颗条目**各一个 [GridCell]（列 / 行）。三枚容器走网格
 *   （[HudZone.LEFT] / [HudZone.RIGHT] / [HudZone.READOUT]），空格子就空着。
 *
 * ## 默认值走「推导」而不是手抄坐标
 * [GridCell.DEFAULT] 是哨兵，语义＝"这颗从没被摆过 ⇒ 回落到推导格"。本批只保留哨兵与序列化语义，
 * 推导函数 `defaultCellsOf` 与 `resolveCells` 在 J2；所以 `gridItems` / `placeEntryAt` /
 * `moveEntryTo` / `pinned` / `occupiedCells` / `residentsOf` 本批**没有移植**——
 * 没有推导与落点裁决就搬它们，只会得到一半语义的假 API。
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
import { HUD_ROW_GAP_DP } from './HudMetrics';

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
