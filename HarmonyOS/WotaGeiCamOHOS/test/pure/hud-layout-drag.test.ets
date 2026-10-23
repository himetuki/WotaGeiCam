/**
 * 条目换序 / 跨容器挪动 / 落点格位 / 同格交换守门（J2 批）。
 *
 * 直译上游 `HudLayoutDragTest.kt` 全量 + `HudLayoutSwapGuardTest.kt` 的行为面。
 * 上游 SwapGuard 后半段有三条"Compose 源码钉扎"（`KotlinSourceScan.bodyOf` 扫
 * `HudLayoutEditor.kt` 的 `snapOf`），本侧编辑器 UI 属 J3、尚无对应源码，故只移植其中的**行为证据**
 * 半段（带边界输入下两个 rows/cols 源真的分叉），钉扎半段如实留 J3。
 *
 * ## 真断言在哪
 * - 每次操作后都查「20 颗可编辑控件全表恰好各出现一次」；
 * - `movingEntryOutDoesNotResetContainerPosition` 钉的是"挪条目顺手把容器位置写回默认"这种退化；
 * - `dropIndex` 三档入参是**手摆的矩形**，与实现的读序方式无关，数格子那条算式错了会当场红；
 * - SwapGuard 那段全用**真实高度**（76/60/144/142px），"渲染格 ≠ 存储格"的自相矛盾表在这里红。
 */
import { CamPill, HudItem } from '../../entry/src/main/ets/core/Constants';
import {
  GRID_ROW_GROWTH_ROWS,
  GridCell,
  HudAreaDp,
  HudAxis,
  HudEntry,
  HudGridPlan,
  HudLayoutTable,
  HudPointDp,
  HudRectDp,
  HudZone,
  areaForRightDock,
  cellDropOf,
  cellRowSpan,
  dropIndexFor,
  freeCellNear,
  gridBoxOf,
  gridColsCapOf,
  gridRowPitchPx,
  hudGridGapDp,
  hudRowGroups,
  rightDockPacksLevelAndVolume,
  spannedCells
} from '../../entry/src/main/ets/core/HudLayout';

function e(pill: CamPill): HudEntry {
  return HudEntry.ofPill(pill);
}

function h(item: HudItem): HudEntry {
  return HudEntry.ofItem(item);
}

function idsOf(entries: Array<HudEntry>): string {
  return entries.map((x: HudEntry): string => x.id).join(',');
}

function setString(values: Set<string>): string {
  const arr: Array<string> = [];
  values.forEach((v: string): void => {
    arr.push(v);
  });
  arr.sort();
  return arr.join(',');
}

function groupsString(groups: Array<Array<HudEntry>>): string {
  return groups.map((g: Array<HudEntry>): string => idsOf(g)).join('|');
}

function once(table: HudLayoutTable, tag: string): void {
  const all: Array<HudEntry> = table.allEntries();
  assertEq(HudEntry.ALL.length, all.length, `${tag}：条目总数`);
  assertEq(all.length, new Set<string>(all.map((x: HudEntry): string => x.id)).size, `${tag}：不许一物两挂`);
}

/** 本文件测顺序、归属与落位：实测高一律 0（跨度恒 1）、格距手摆 30px */
const PLAN_ALL: HudGridPlan = new HudGridPlan(
  new Set<string>(HudEntry.ALL.map((x: HudEntry): string => x.id)),
  3,
  (_zone: HudZone): number => 30,
  (_entry: HudEntry): number => 0
);

test('跨容器挪动_两张清单都自洽', () => {
  const t: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.ZOOM), HudZone.LEFT, 0, PLAN_ALL);
  assertEq('P3,P6,P7,P8,P9', idsOf(t.orderOf(HudZone.LEFT)));
  assertEq('P0,P1,P2,P4,P5', idsOf(t.orderOf(HudZone.RIGHT)));
  assertEq(HudZone.LEFT, t.sourceZoneOf(e(CamPill.ZOOM)));
  once(t, '跨容器');
  assertEq(idsOf(HudLayoutTable.defaultOrderOf(HudZone.TOP)), idsOf(t.orderOf(HudZone.TOP)));
  assertEq(idsOf(HudLayoutTable.defaultOrderOf(HudZone.READOUT)), idsOf(t.orderOf(HudZone.READOUT)));
  assertEq('P10', idsOf(t.orderOf(HudZone.BOTTOM)));
});

test('挪回原容器_顺序回到原样', () => {
  const out: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.ZOOM), HudZone.LEFT, 0, PLAN_ALL);
  const back: HudLayoutTable = out.moveEntryTo(e(CamPill.ZOOM), HudZone.RIGHT, 3, PLAN_ALL);
  assertEq(idsOf(HudLayoutTable.default().orderOf(HudZone.RIGHT)), idsOf(back.orderOf(HudZone.RIGHT)));
  assertEq(idsOf(HudLayoutTable.default().orderOf(HudZone.LEFT)), idsOf(back.orderOf(HudZone.LEFT)));
  once(back, '挪回');
});

test('同容器换序_条目数不变也不跑到别的容器', () => {
  const t: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.STAB), HudZone.RIGHT, 0, PLAN_ALL);
  assertEq('P5,P0,P1,P2,P3,P4', idsOf(t.orderOf(HudZone.RIGHT)));
  assertEq(6, t.orderOf(HudZone.RIGHT).length);
  let stabInLeft: boolean = false;
  for (const x of t.orderOf(HudZone.LEFT)) {
    if (x.id === e(CamPill.STAB).id) {
      stabInLeft = true;
    }
  }
  assertFalse(stabInLeft);
  once(t, '换序');
});

test('读数块换序_隐藏条目留在原位', () => {
  const t: HudLayoutTable = HudLayoutTable.default().moveEntryTo(h(HudItem.BITRATE), HudZone.READOUT, 0, PLAN_ALL);
  const visible: Set<string> = new Set<string>([
    h(HudItem.SHUTTER).id, h(HudItem.FPS).id, h(HudItem.BITRATE).id, h(HudItem.ZOOM).id
  ]);
  assertEq('H2,H0,H1,H6', idsOf(t.visibleOrderOf(HudZone.READOUT, visible)));
  assertEq(7, t.orderOf(HudZone.READOUT).length, '表本身仍是完整 7 颗');
  assertEq(4, t.orderOf(HudZone.READOUT).findIndex((x: HudEntry): boolean => x.id === h(HudItem.EV).id),
    'EV 仍排在 ISO 之后、白平衡之前');
  assertEq(h(HudItem.ISO).id, t.orderOf(HudZone.READOUT)[3].id);
  assertEq(h(HudItem.WB).id, t.orderOf(HudZone.READOUT)[5].id);
  once(t, '读数块换序');
});

test('挪条目不许顺手重置容器位置', () => {
  const positioned: HudLayoutTable = HudLayoutTable.default()
    .withZonePos(HudZone.LEFT, 200, 90)
    .withZonePos(HudZone.READOUT, 640, 250);
  const moved: HudLayoutTable = positioned
    .moveEntryTo(e(CamPill.CURVE), HudZone.TOP, 1, PLAN_ALL)
    .moveEntryTo(h(HudItem.ISO), HudZone.LEFT, 0, PLAN_ALL);
  assertEq(200, moved.posOf(HudZone.LEFT).xDp, '挪条目不许顺手重置左 Dock 的位置');
  assertEq(90, moved.posOf(HudZone.LEFT).yDp);
  assertEq(640, moved.posOf(HudZone.READOUT).xDp);
  assertEq(250, moved.posOf(HudZone.READOUT).yDp);
  assertTrue(moved.posOf(HudZone.TOP).isDefault, '只改顺序不改坐标：顶栏仍按定稿位置');
  once(moved, '带位置挪条目');
  assertEq(moved.encode(), HudLayoutTable.decode(moved.encode()).encode(), '编解码一轮之后位置与归属都还在');
});

test('底栏可以被清空', () => {
  const t: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.LENS), HudZone.TOP, 1, PLAN_ALL);
  assertEq(0, t.orderOf(HudZone.BOTTOM).length);
  assertEq('P11,P10,P12', idsOf(t.orderOf(HudZone.TOP)));
  once(t, '底栏清空');
});

test('越界index被夹而不是被忽略', () => {
  const tail: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.LENS), HudZone.RIGHT, 999, PLAN_ALL);
  assertEq(e(CamPill.LENS).id, tail.orderOf(HudZone.RIGHT)[tail.orderOf(HudZone.RIGHT).length - 1].id);
  const head: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.LENS), HudZone.RIGHT, -5, PLAN_ALL);
  assertEq(e(CamPill.LENS).id, head.orderOf(HudZone.RIGHT)[0].id);
  assertEq(7, head.orderOf(HudZone.RIGHT).length);
  once(tail, '越界 index');
  once(head, '负 index');
});

test('落点索引沿主轴数格子_ROW与COLUMN与GRID三档', () => {
  const row: Array<HudRectDp> = [
    new HudRectDp(0, 0, 60, 30), new HudRectDp(70, 0, 130, 30), new HudRectDp(140, 0, 200, 30)
  ];
  assertEq(0, dropIndexFor(HudAxis.ROW, row, new HudPointDp(10, 15)));
  assertEq(1, dropIndexFor(HudAxis.ROW, row, new HudPointDp(50, 15)));
  assertEq(3, dropIndexFor(HudAxis.ROW, row, new HudPointDp(300, 15)));
  const col: Array<HudRectDp> = [
    new HudRectDp(0, 0, 60, 40), new HudRectDp(0, 50, 60, 90), new HudRectDp(0, 100, 60, 140)
  ];
  assertEq(2, dropIndexFor(HudAxis.COLUMN, col, new HudPointDp(30, 95)));
  assertEq(0, dropIndexFor(HudAxis.COLUMN, col, new HudPointDp(30, -10)));
  const grid: Array<HudRectDp> = [
    new HudRectDp(0, 0, 60, 30), new HudRectDp(70, 0, 130, 30),
    new HudRectDp(0, 40, 60, 70), new HudRectDp(70, 40, 130, 70)
  ];
  assertEq(3, dropIndexFor(HudAxis.GRID, grid, new HudPointDp(100, 55)), '第二行第二格');
  assertEq(0, dropIndexFor(HudAxis.GRID, grid, new HudPointDp(10, 20)), '第一行第一格');
  assertEq(1, dropIndexFor(HudAxis.GRID, grid, new HudPointDp(100, 10)), '第一行第二格');
  assertEq(0, dropIndexFor(HudAxis.ROW, [], new HudPointDp(50, 50)), '空容器插到第 0 格');
});

test('落点用矩形中心而不是均分宽度', () => {
  const uneven: Array<HudRectDp> = [
    new HudRectDp(0, 0, 200, 30), new HudRectDp(210, 0, 240, 30), new HudRectDp(250, 0, 600, 30)
  ];
  assertEq(1, dropIndexFor(HudAxis.ROW, uneven, new HudPointDp(150, 15)));
  assertEq(2, dropIndexFor(HudAxis.ROW, uneven, new HudPointDp(230, 15)));
  assertEq(3, dropIndexFor(HudAxis.ROW, uneven, new HudPointDp(599, 15)));
  assertEq(2, dropIndexFor(HudAxis.ROW, uneven, new HudPointDp(245, 15)), '落在缝里仍算前两颗之后');
});

test('右Dock只在姿态仪与音量表相邻时并排', () => {
  const def: Array<HudEntry> = HudLayoutTable.defaultOrderOf(HudZone.RIGHT);
  const groups: Array<Array<HudEntry>> = hudRowGroups(HudZone.RIGHT, def, 1);
  assertEq(5, groups.length);
  assertEq('P0,P1|P2|P3|P4|P5', groupsString(groups));
  assertTrue(rightDockPacksLevelAndVolume(def));
  const movedOut: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.VOLUME), HudZone.LEFT, 0, PLAN_ALL);
  const rest: Array<HudEntry> = movedOut.orderOf(HudZone.RIGHT);
  assertEq(5, rest.length);
  assertEq('P0|P2|P3|P4|P5', groupsString(hudRowGroups(HudZone.RIGHT, rest, 1)));
  assertFalse(rightDockPacksLevelAndVolume(rest));
  // 相邻但顺序颠倒：仍并排，组内保持用户那个顺序
  const swapped: Array<HudEntry> = HudLayoutTable.default()
    .moveEntryTo(e(CamPill.VOLUME), HudZone.RIGHT, 0, PLAN_ALL)
    .orderOf(HudZone.RIGHT);
  assertEq('P1,P0', idsOf(hudRowGroups(HudZone.RIGHT, swapped, 1)[0]));
  assertTrue(rightDockPacksLevelAndVolume(swapped));
});

test('分行不丢不重不换序_五枚容器逐条查', () => {
  const table: HudLayoutTable = HudLayoutTable.default();
  for (const zone of [HudZone.TOP, HudZone.LEFT, HudZone.RIGHT, HudZone.READOUT, HudZone.BOTTOM]) {
    const order: Array<HudEntry> = table.orderOf(zone);
    const flat: Array<HudEntry> = [];
    for (const group of hudRowGroups(zone, order, 2)) {
      for (const x of group) {
        flat.push(x);
      }
    }
    assertEq(order.length, flat.length, `${zone}：分组不许改变颗数`);
    assertEq(flat.length, new Set<string>(flat.map((x: HudEntry): string => x.id)).size,
      `${zone}：分组不许让一颗出现两遍`);
    assertEq(idsOf(order), idsOf(flat), `${zone}：分组不许换序`);
  }
  const topTwo: Array<HudEntry> = [e(CamPill.SIZE), e(CamPill.STORAGE)];
  assertEq('P11,P12', groupsString(hudRowGroups(HudZone.TOP, topTwo, 1)));
  assertEq('P11|P12', groupsString(hudRowGroups(HudZone.LEFT, topTwo, 3)));
  assertEq('P11|P12', groupsString(hudRowGroups(HudZone.READOUT, topTwo, 0)), 'perRow<1 按 1 处理');
  assertEq(0, hudRowGroups(HudZone.TOP, [], 1).length);
});

test('读数块分块跟着用户顺序而不是枚举序', () => {
  const t: HudLayoutTable = HudLayoutTable.default().moveEntryTo(h(HudItem.BITRATE), HudZone.READOUT, 0, PLAN_ALL);
  assertEq('H2,H0|H1,H3|H4,H5|H6', groupsString(hudRowGroups(HudZone.READOUT, t.orderOf(HudZone.READOUT), 2)));
});

test('底栏y-only落位_三个退化路径都要红', () => {
  const placed: HudLayoutTable = HudLayoutTable.default().withZonePos(HudZone.BOTTOM, -1, 282);
  assertFalse(placed.posOf(HudZone.BOTTOM).isDefault, '底栏 y-only 落位不是默认态');
  assertEq(-1, placed.posOf(HudZone.BOTTOM).xDp);
  assertEq(282, placed.posOf(HudZone.BOTTOM).yDp);
  const moved: HudLayoutTable = placed.moveEntryTo(e(CamPill.LENS), HudZone.TOP, 1, PLAN_ALL);
  assertEq(-1, moved.posOf(HudZone.BOTTOM).xDp, '挪条目不许顺手丢掉底栏的 y');
  assertEq(282, moved.posOf(HudZone.BOTTOM).yDp);
  const back: HudLayoutTable = HudLayoutTable.decode(placed.encode());
  assertEq(placed.posOf(HudZone.BOTTOM).xDp, back.posOf(HudZone.BOTTOM).xDp, '编解码之后 y 还在、x 仍是哨兵');
  assertEq(placed.posOf(HudZone.BOTTOM).yDp, back.posOf(HudZone.BOTTOM).yDp);
  // 其余四枚容器不吃这个特例
  const half: HudLayoutTable = HudLayoutTable.default().withZonePos(HudZone.LEFT, -1, 90);
  assertTrue(HudLayoutTable.decode(half.encode()).posOf(HudZone.LEFT).isDefault);
  once(moved, '底栏 y-only');
});

test('右Dock让位_同基线取大_退化档串联_空块收回', () => {
  const area: HudAreaDp = new HudAreaDp(766, 360, 44, 72);
  assertEq(114, areaForRightDock(area, 42, 72).bottomAvoidDp, '退化档：串联算式');
  const empty: HudAreaDp = areaForRightDock(area, 0, 72);
  assertEq(area.bottomAvoidDp, empty.bottomAvoidDp, '读数全关时那条缝必须收回去');
  assertEq(area.topAvoidDp, empty.topAvoidDp);
  assertEq(72, areaForRightDock(area, -50, 72).bottomAvoidDp, '坏值不许把下界拉得比底栏还高');
  assertEq(72, area.bottomAvoidDp);
  assertEq(72, areaForRightDock(area, 42, 6).bottomAvoidDp, '同基线档取大不求和');
});

// ================================================================== 同格交换守门（SwapGuard）

const DENSITY: number = 2;
const DOCK_GAP_DP: number = hudGridGapDp(HudZone.LEFT);
const ROW_PITCH_PX: number = gridRowPitchPx(1, DOCK_GAP_DP, DENSITY);

const LEFT_HEIGHTS: Map<string, number> = new Map<string, number>();
LEFT_HEIGHTS.set(e(CamPill.REFLINE).id, 76);
LEFT_HEIGHTS.set(e(CamPill.MONITOR).id, 60);
LEFT_HEIGHTS.set(e(CamPill.CURVE).id, 60);
LEFT_HEIGHTS.set(e(CamPill.FLASH).id, 76);

const RIGHT_HEIGHTS: Map<string, number> = new Map<string, number>();
RIGHT_HEIGHTS.set(e(CamPill.LEVEL).id, 144);
RIGHT_HEIGHTS.set(e(CamPill.VOLUME).id, 142);
RIGHT_HEIGHTS.set(e(CamPill.BT).id, 60);
RIGHT_HEIGHTS.set(e(CamPill.ZOOM).id, 60);
RIGHT_HEIGHTS.set(e(CamPill.FOCUS).id, 60);
RIGHT_HEIGHTS.set(e(CamPill.STAB).id, 60);

const ALL_HEIGHTS: Map<string, number> = new Map<string, number>();
LEFT_HEIGHTS.forEach((v: number, k: string): void => {
  ALL_HEIGHTS.set(k, v);
});
RIGHT_HEIGHTS.forEach((v: number, k: string): void => {
  ALL_HEIGHTS.set(k, v);
});

function heightOf(entry: HudEntry): number {
  const v: number | undefined = ALL_HEIGHTS.get(entry.id);
  return v === undefined ? 0 : v;
}

/** `visible` 只列给定几颗（与上游 `planOf(vararg visible)` 同义） */
function planOf(visible: Array<CamPill>): HudGridPlan {
  const set: Set<string> = new Set<string>();
  for (const pill of visible) {
    set.add(e(pill).id);
  }
  return new HudGridPlan(set, 3, (_zone: HudZone): number => ROW_PITCH_PX, heightOf);
}

function stored(table: HudLayoutTable, zone: HudZone, entry: HudEntry): string {
  const c: GridCell | undefined = table.cellsOf(zone).get(entry.id);
  return c === undefined ? '<none>' : c.token;
}

function rendered(table: HudLayoutTable, zone: HudZone, plan: HudGridPlan, entry: HudEntry): string {
  for (const it of table.gridItems(zone, plan)) {
    if (it.entry.id === entry.id) {
      return it.cell.token;
    }
  }
  return '<none>';
}

/** 桥断言：写表之后，渲染解析逐颗 == 存储格（"渲染格 ≠ 存储格"的自相矛盾表在这里红） */
function assertBridge(zone: HudZone, table: HudLayoutTable, plan: HudGridPlan, label: string): void {
  for (const it of table.gridItems(zone, plan)) {
    assertEq(stored(table, zone, it.entry), it.cell.token,
      `${label}：${it.entry.id} 渲染格 != 存储格`);
  }
}

test('跨度尾会压到第三颗锚点_拒绝交换', () => {
  // 真机复刻（T1）：左 Dock 可见 {参考线, 监看, 闪光}，拖参考线到监看格 (0,2)
  const plan: HudGridPlan = planOf([CamPill.REFLINE, CamPill.MONITOR, CamPill.FLASH]);
  const placed: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 1, new GridCell(0, 2), plan);
  const refline: string = stored(placed, HudZone.LEFT, e(CamPill.REFLINE));
  assertFalse(refline === '0.2', '参考线不许落进监看格');
  assertFalse(refline === '0.3', '参考线不许落进闪光的锚点');
  assertEq('0.2', stored(placed, HudZone.LEFT, e(CamPill.MONITOR)), '监看仍钉在 (0,2)');
  assertEq('0.3', stored(placed, HudZone.LEFT, e(CamPill.FLASH)), '闪光存储格仍是 (0,3)');
  assertBridge(HudZone.LEFT, placed, plan, '拒绝交换');
});

test('拒绝交换后仍落到最近空格_占位者不动', () => {
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set('0.0', [e(CamPill.REFLINE)]);
  residents.set('0.2', [e(CamPill.MONITOR)]);
  residents.set('0.3', [e(CamPill.FLASH)]);
  const drop = cellDropOf(
    new GridCell(0, 2),
    new GridCell(0, 0),
    residents,
    e(CamPill.REFLINE),
    new Set<string>(),
    76,
    heightOf,
    gridColsCapOf(HudZone.LEFT),
    HudEntry.ALL.length,
    ROW_PITCH_PX
  );
  assertEq('1.2', drop.dropped.token, '拒绝交换：落点 = 曼哈顿最近的整块不撞格');
  assertEq(0, drop.moved.size, '别颗一律不动');
  const plan: HudGridPlan = planOf([CamPill.REFLINE, CamPill.MONITOR, CamPill.FLASH]);
  const placed: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 1, new GridCell(0, 2), plan);
  assertEq(drop.dropped.token, stored(placed, HudZone.LEFT, e(CamPill.REFLINE)), '预览落点必须等于写表落点');
  assertBridge(HudZone.LEFT, placed, plan, '拒绝交换的落点');
});

test('真空格的跨度尾压第三颗锚点_同样拒绝', () => {
  const plan: HudGridPlan = planOf([CamPill.REFLINE, CamPill.MONITOR, CamPill.CURVE, CamPill.FLASH]);
  const setup: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.MONITOR), HudZone.LEFT, 1, new GridCell(1, 2), plan);
  assertEq('1.2', stored(setup, HudZone.LEFT, e(CamPill.MONITOR)), '铺底：监看显式挪到第二列');
  const placed: HudLayoutTable = setup.placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 0, new GridCell(0, 2), plan);
  const refline: string = stored(placed, HudZone.LEFT, e(CamPill.REFLINE));
  assertFalse(refline === '0.2', '参考线不许落进真空格');
  assertFalse(refline === '0.3', '参考线不许落进曲线锚点');
  const reflineBlock: Array<GridCell> = spannedCells(
    new GridCell(Number(refline.split('.')[0]), Number(refline.split('.')[1])),
    cellRowSpan(heightOf(e(CamPill.REFLINE)), ROW_PITCH_PX)
  );
  const monitor: string = stored(placed, HudZone.LEFT, e(CamPill.MONITOR));
  const curve: string = stored(placed, HudZone.LEFT, e(CamPill.CURVE));
  const flash: string = stored(placed, HudZone.LEFT, e(CamPill.FLASH));
  for (const c of reflineBlock) {
    assertFalse(c.token === monitor || c.token === curve || c.token === flash,
      `参考线的块不许含别人的锚点：${c.token}`);
  }
  assertEq('1.2', monitor);
  assertEq('0.3', curve);
  assertEq('0.4', flash);
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set('0.0', [e(CamPill.REFLINE)]);
  residents.set('1.2', [e(CamPill.MONITOR)]);
  residents.set('0.3', [e(CamPill.CURVE)]);
  residents.set('0.4', [e(CamPill.FLASH)]);
  const drop = cellDropOf(new GridCell(0, 2), new GridCell(0, 0), residents, e(CamPill.REFLINE),
    new Set<string>(), 76, heightOf, gridColsCapOf(HudZone.LEFT), HudEntry.ALL.length, ROW_PITCH_PX);
  assertEq(refline, drop.dropped.token, '预览落点必须等于写表落点');
  assertEq(0, drop.moved.size, '真空格支没有 occupant ⇒ moved 为空');
  assertBridge(HudZone.LEFT, placed, plan, '真空格支拒绝');
});

test('占位者不许被塞进被拖那颗的新块', () => {
  const plan: HudGridPlan = planOf([CamPill.REFLINE, CamPill.MONITOR]);
  const setup: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 0, new GridCell(0, 3), plan);
  assertEq('0.3', stored(setup, HudZone.LEFT, e(CamPill.REFLINE)), '铺底：参考线显式在 (0,3)');
  assertEq('0.2', stored(setup, HudZone.LEFT, e(CamPill.MONITOR)), '铺底：监看显式在 (0,2)');
  const placed: HudLayoutTable = setup.placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 0, new GridCell(0, 2), plan);
  assertEq('0.2', stored(placed, HudZone.LEFT, e(CamPill.REFLINE)), '被拖那颗拿到目标格');
  const monitor: string = stored(placed, HudZone.LEFT, e(CamPill.MONITOR));
  assertFalse(monitor === '0.2' || monitor === '0.3', '监看不许被写进参考线的新块');
  assertBridge(HudZone.LEFT, placed, plan, '判据 B');
});

test('就近找空格的候选整块不许压人', () => {
  const occupied: Set<string> = new Set<string>(['0.3']);
  const got: GridCell = freeCellNear(new GridCell(0, 2), occupied, 2, 6, 76, 68);
  assertEq('0.1', got.token, 'd=1 行主序第一个整块不撞的格');
  for (const c of spannedCells(got, 2)) {
    assertFalse(occupied.has(c.token), `返回格的整块不许与占位集相交：${c.token}`);
  }
  // span=1 时与旧式逐字同值
  assertEq('0.2', freeCellNear(new GridCell(0, 2), occupied, 2, 6, 0, 68).token);
  assertEq('0.1', freeCellNear(new GridCell(0, 3), new Set<string>(['0.0']), 1, 4, 144, 68).token);
});

test('块尾不压人时交换照旧发生_判据A不是永久拒绝', () => {
  const plan: HudGridPlan = planOf([CamPill.REFLINE, CamPill.MONITOR, CamPill.FLASH]);
  const setup: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.FLASH), HudZone.LEFT, 2, new GridCell(0, 5), plan);
  assertEq('0.5', stored(setup, HudZone.LEFT, e(CamPill.FLASH)), '铺底：闪光显式在 (0,5)');
  const placed: HudLayoutTable = setup.placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 1, new GridCell(0, 2), plan);
  assertEq('0.2', stored(placed, HudZone.LEFT, e(CamPill.REFLINE)), '交换成立：参考线拿到目标格');
  assertEq('0.0', stored(placed, HudZone.LEFT, e(CamPill.MONITOR)), '交换成立：监看让到参考线原格');
  assertEq('0.5', stored(placed, HudZone.LEFT, e(CamPill.FLASH)), '第三颗一字不动');
  assertBridge(HudZone.LEFT, placed, plan, '正常交换');
});

test('拖回搭档那一格仍能合对_新判据不误杀', () => {
  const plan: HudGridPlan = planOf([CamPill.LEVEL, CamPill.VOLUME, CamPill.BT, CamPill.ZOOM,
    CamPill.FOCUS, CamPill.STAB]);
  const broken: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, new GridCell(0, 7), plan);
  assertEq('0.7', stored(broken, HudZone.RIGHT, e(CamPill.VOLUME)), '铺底：音量表拆到 (0,7)');
  const merged: HudLayoutTable = broken.placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, new GridCell(0, 0), plan);
  assertEq('0.0', stored(merged, HudZone.RIGHT, e(CamPill.VOLUME)), '合回去：音量表回配对格');
  assertEq('0.0', stored(merged, HudZone.RIGHT, e(CamPill.LEVEL)), '姿态仪没动');
  assertEq('0.3', stored(merged, HudZone.RIGHT, e(CamPill.BT)));
  assertEq('0.4', stored(merged, HudZone.RIGHT, e(CamPill.ZOOM)));
  assertEq('0.5', stored(merged, HudZone.RIGHT, e(CamPill.FOCUS)));
  assertEq('0.6', stored(merged, HudZone.RIGHT, e(CamPill.STAB)));
  assertBridge(HudZone.RIGHT, merged, plan, '合对');
});

test('预览与写表的行上限同源_带边界上两个rows源真的分叉', () => {
  // 带盒 2×2：带高 68 ÷ 34 = 2，预留 1 + 生长 1 = 2
  const box = gridBoxOf(HudZone.LEFT, 86, 34, 68, 100, 1, 0, GRID_ROW_GROWTH_ROWS, 0);
  assertEq(2, box.cols);
  assertEq(2, box.rows);
  assertTrue(box.rows < HudEntry.ALL.length, '带内档数必须真的少于写表侧上限');
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set('0.0', [e(CamPill.REFLINE)]);
  residents.set('1.0', [e(CamPill.MONITOR)]);
  residents.set('0.3', [e(CamPill.CURVE)]);
  residents.set('0.7', [e(CamPill.FLASH)]);
  const dropWith = (rows: number) => cellDropOf(
    new GridCell(0, 2),
    new GridCell(0, 7),
    residents,
    e(CamPill.FLASH),
    new Set<string>(),
    76,
    heightOf,
    gridColsCapOf(HudZone.LEFT),
    rows,
    ROW_PITCH_PX
  );
  assertEq('1.2', dropWith(HudEntry.ALL.length).dropped.token, '写表侧落点 = (1,2)');
  assertEq('0.1', dropWith(box.rows).dropped.token, '带盒侧落点是搜索放弃后还回来的 (0,1)');
  assertTrue(dropWith(box.rows).dropped.token !== dropWith(HudEntry.ALL.length).dropped.token,
    '两个 rows 源在带边界上必须分叉');
});

test('跨容器预览与写表的列行上限同源_带边界上真的分叉', () => {
  const box = gridBoxOf(HudZone.RIGHT, 86, 34, 68, 100, 1, 0, GRID_ROW_GROWTH_ROWS, 0);
  assertEq(2, box.cols);
  assertEq(2, box.rows);
  assertEq(gridColsCapOf(HudZone.RIGHT), box.cols, '竖 Dock 的带盒列数必须与存储层上限同值');
  const occupied: Set<string> = new Set<string>(['0.0', '1.0', '0.1', '1.1', '0.3']);
  const dropWith = (cols: number, rows: number): GridCell => freeCellNear(
    new GridCell(0, 2), occupied, cols, rows,
    ALL_HEIGHTS.get(e(CamPill.LEVEL).id) as number, ROW_PITCH_PX);
  assertEq('1.2', dropWith(gridColsCapOf(HudZone.RIGHT), HudEntry.ALL.length).token, '写表侧 = (1,2)');
  assertEq('0.1', dropWith(box.cols, box.rows).token, '带盒侧 = (0,1)');
  assertTrue(dropWith(box.cols, box.rows).token !==
    dropWith(gridColsCapOf(HudZone.RIGHT), HudEntry.ALL.length).token, '两个源必须分叉');
});

test('同容器预览与写表的列上限同源_读数块带边界上真的分叉', () => {
  const cap: number = gridColsCapOf(HudZone.READOUT);
  const box = gridBoxOf(HudZone.READOUT, 86, 34, 68, 258, 0, 2, GRID_ROW_GROWTH_ROWS, 0);
  assertEq(2, box.cols, '读数块带盒 = 2 列');
  assertTrue(box.cols < cap, '带盒列数必须真的少于写表侧上限');
  const iso: HudEntry = h(HudItem.ISO);
  const ev: HudEntry = h(HudItem.EV);
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set('2.1', [iso]);
  residents.set('1.1', [ev]);
  const dropWith = (cols: number) => cellDropOf(
    new GridCell(1, 1), new GridCell(2, 1), residents, iso, new Set<string>(),
    60, heightOf, cols, HudEntry.ALL.length, ROW_PITCH_PX);
  assertEq('1.1', dropWith(cap).dropped.token, '写表侧：被拖那颗拿目标格');
  assertEq('2.1', (dropWith(cap).moved.get(ev.id) as GridCell).token, '写表侧：格主被送回第 3 列原格');
  assertEq('1.1', dropWith(box.cols).dropped.token);
  assertEq('1.0', (dropWith(box.cols).moved.get(ev.id) as GridCell).token, '带盒侧：原格出盒 ⇒ 格主被挤到 (1,0)');
  assertTrue((dropWith(cap).moved.get(ev.id) as GridCell).token !==
    (dropWith(box.cols).moved.get(ev.id) as GridCell).token, '两个 cols 源必须分叉');
  // 写表侧桥：真实 placeEntryAt 走同容器支（cols = 3）
  const visible: Set<string> = new Set<string>([iso.id, ev.id]);
  const plan: HudGridPlan = new HudGridPlan(visible, 3, (_zone: HudZone): number => ROW_PITCH_PX, heightOf);
  const setup: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(ev, HudZone.READOUT, 1, new GridCell(1, 1), plan)
    .placeEntryAt(iso, HudZone.READOUT, 0, new GridCell(2, 1), plan);
  assertEq('2.1', stored(setup, HudZone.READOUT, iso), '铺底：ISO 显式钉在第 3 列');
  assertEq('1.1', stored(setup, HudZone.READOUT, ev), '铺底：EV 显式在目标格');
  const swapped: HudLayoutTable = setup.placeEntryAt(iso, HudZone.READOUT, 0, new GridCell(1, 1), plan);
  assertEq('1.1', stored(swapped, HudZone.READOUT, iso), '交换成立：ISO 拿到目标格');
  assertEq('2.1', stored(swapped, HudZone.READOUT, ev), '写表侧把格主送回 ISO 原格');
});
