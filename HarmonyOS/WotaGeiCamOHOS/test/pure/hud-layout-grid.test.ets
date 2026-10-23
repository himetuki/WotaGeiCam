/**
 * HUD 网格几何与配对格（J2 批，直译上游 `HudLayoutGridTest.kt` + `HudLayoutPairCellTest.kt` 全量）。
 *
 * ## 期望值全部手算 / 手写，不许从实现回抄
 * 屏幕坐标一律用**手摆的格长**（50×30、90×42、180×148 这种）喂进 `cellOffsetPx`，期望值是能口算的数；
 * 默认格子逐颗点名（(0,0)/(1,0)/(0,1)…），所以把 `defaultCellsOf` 换成任何手抄坐标表都会红。
 *
 * ## 恒等式与真断言的分工
 * - `gridPitchOfInvertsGridSizePx` 那对正反算式必须互逆，改坏任何一侧都会红；
 * - `thePairedCellIsWidthOwnerAndTheBoardIsNinetyFour` 手算 172 → 180 → 172 → 94dp 的整条宽度账；
 * - 跨度那一档一律喂 0（"这一帧还没量到" ⇒ 跨度 1），所以本文件逐颗点名的行号与 #74 那一版一字不动；
 *   跨度本身在 `hud-layout-rowpitch.test.ets` 喂真实高度另打，两件事不混成一条红。
 *
 * ## ArkTS 口径
 * 集合/Map 键是条目 id（`'P3'`/`'H6'`）与格子 token（`'0.2'`）；对象相等用 `token`/`x,y` 对撞。
 */
import { CamPill, HudItem } from '../../entry/src/main/ets/core/Constants';
import {
  GRID_ROW_GROWTH_ROWS,
  GridAnchor,
  GridBox,
  GridCell,
  GridPlacement,
  HudAreaDp,
  HudEntry,
  HudGridItem,
  HudGridPlan,
  HudLayoutTable,
  HudPointDp,
  HudPointPx,
  HudRectDp,
  HudSizePx,
  HudZone,
  IntRange,
  blockingCells,
  cellAtPointer,
  cellChildLeftsPx,
  cellContentWidthPx,
  cellDropOf,
  cellGroupMatesOf,
  cellOffsetPx,
  cellPlaceOffsetPx,
  clampCellToBox,
  clampStoredCell,
  defaultCellsOf,
  dropZoneOf,
  editableGridRowsOf,
  entryHitIndex,
  freeCellNear,
  gridBoxOf,
  gridColFromEndOf,
  gridColsCapOf,
  gridPitchOf,
  gridPitchPx,
  gridPlacementOf,
  gridRowPitchPx,
  gridSizePx,
  hudRowGroups,
  reservedGridColsOf,
  reservedGridRowsOf,
  resolveCells,
  zoneBandYRange
} from '../../entry/src/main/ets/core/HudLayout';

function e(pill: CamPill): HudEntry {
  return HudEntry.ofPill(pill);
}

function h(item: HudItem): HudEntry {
  return HudEntry.ofItem(item);
}

function near(expected: number, actual: number, eps: number, msg: string): void {
  assertTrue(Math.abs(expected - actual) <= eps, `${msg}: expected ${expected} but got ${actual}`);
}

function itemToken(items: Array<HudGridItem>, entry: HudEntry): string {
  for (const it of items) {
    if (it.entry.id === entry.id) {
      return it.cell.token;
    }
  }
  return '<none>';
}

function storedToken(table: HudLayoutTable, zone: HudZone, entry: HudEntry): string {
  const cell: GridCell | undefined = table.cellsOf(zone).get(entry.id);
  return cell === undefined ? '<none>' : cell.token;
}

function cellsOf(table: HudLayoutTable, zone: HudZone, plan: HudGridPlan): Map<string, string> {
  const out: Map<string, string> = new Map<string, string>();
  for (const it of table.gridItems(zone, plan)) {
    out.set(it.entry.id, it.cell.token);
  }
  return out;
}

function mapString(map: Map<string, string>): string {
  const keys: Array<string> = [];
  map.forEach((_v: string, k: string): void => {
    keys.push(k);
  });
  keys.sort();
  let out: string = '';
  for (const k of keys) {
    out += k + '=' + (map.get(k) === undefined ? '<none>' : map.get(k) as string) + ';';
  }
  return out;
}

function setString(values: Set<string>): string {
  const arr: Array<string> = [];
  values.forEach((v: string): void => {
    arr.push(v);
  });
  arr.sort();
  return arr.join(',');
}

function idsOf(entries: Array<HudEntry>): string {
  return entries.map((x: HudEntry): string => x.id).join(',');
}

function offsetString(p: HudPointPx): string {
  return `${p.x},${p.y}`;
}

function offsetsString(offs: Array<HudPointPx>): string {
  return offs.map((p: HudPointPx): string => offsetString(p)).join(';');
}

function sizeString(s: HudSizePx): string {
  return `${s.width}x${s.height}`;
}

/** 手摆的一帧网格输入：行距 30px、实测高一律 0（跨度恒 1） */
function planPerRow(perRow: number): HudGridPlan {
  return new HudGridPlan(
    new Set<string>(HudEntry.ALL.map((x: HudEntry): string => x.id)),
    perRow,
    (_zone: HudZone): number => 30,
    (_entry: HudEntry): number => 0
  );
}

const PLAN_ALL: HudGridPlan = planPerRow(3);

/** 手摆的格长（dp 无关，纯 px） */
const PITCH: HudSizePx = new HudSizePx(50, 30);

function cellAt(pointer: HudPointPx, origin: HudPointPx, size: HudSizePx): GridCell {
  return cellAtPointer(pointer, origin, size, 5 * size.width, 0, false);
}

// ================================================================== 一、默认表结构性等于今天的排布

test('默认格子就是今天的行组_左竖Dock四行_右Dock配对共格_读数块3加3加1', () => {
  const left: Map<string, string> = cellsOf(HudLayoutTable.default(), HudZone.LEFT, PLAN_ALL);
  assertEq('0.0', left.get(e(CamPill.REFLINE).id));
  assertEq('0.1', left.get(e(CamPill.MONITOR).id));
  assertEq('0.2', left.get(e(CamPill.CURVE).id));
  assertEq('0.3', left.get(e(CamPill.FLASH).id));
  // 右 Dock：S2-2 B 那条「姿态仪 + 音量表并排」原样落进**同一枚格子**（#74 后果修复 ⇒ 单列网格）
  const right: Map<string, string> = cellsOf(HudLayoutTable.default(), HudZone.RIGHT, PLAN_ALL);
  assertEq('0.0', right.get(e(CamPill.LEVEL).id));
  assertEq('0.0', right.get(e(CamPill.VOLUME).id), '配对同格 ⇒ 单列网格');
  assertEq('0.1', right.get(e(CamPill.BT).id));
  assertEq('0.2', right.get(e(CamPill.ZOOM).id));
  assertEq('0.3', right.get(e(CamPill.FOCUS).id));
  assertEq('0.4', right.get(e(CamPill.STAB).id));
  // 这一枚容器的默认形态**只有一列**
  let allColZero: boolean = true;
  right.forEach((token: string): void => {
    if (!token.startsWith('0.')) {
      allColZero = false;
    }
  });
  assertTrue(allColZero, '右 Dock 默认是单列网格');
  const readout: Map<string, string> = cellsOf(HudLayoutTable.default(), HudZone.READOUT, PLAN_ALL);
  assertEq('0.0', readout.get(h(HudItem.SHUTTER).id));
  assertEq('2.0', readout.get(h(HudItem.BITRATE).id));
  assertEq('0.1', readout.get(h(HudItem.ISO).id));
  assertEq('2.1', readout.get(h(HudItem.WB).id));
  assertEq('0.2', readout.get(h(HudItem.ZOOM).id));
  // 默认表里**一个显式格子都不许有**：默认值走推导
  for (const zone of [HudZone.TOP, HudZone.LEFT, HudZone.RIGHT, HudZone.READOUT, HudZone.BOTTOM]) {
    assertEq(0, HudLayoutTable.default().cellsOf(zone).size, `${zone} 段默认表不该有显式格子`);
  }
});

test('读数块推导跟着分行档走_写死常量的实现会红', () => {
  const order: Array<HudEntry> = HudLayoutTable.defaultOrderOf(HudZone.READOUT);
  assertEq('0.6', (defaultCellsOf(HudZone.READOUT, order, planPerRow(1)).get(h(HudItem.ZOOM).id) as GridCell).token);
  assertEq('0.2', (defaultCellsOf(HudZone.READOUT, order, planPerRow(3)).get(h(HudItem.ZOOM).id) as GridCell).token);
  // 与 hudRowGroups 逐行对齐（推导尺子只有一把）
  const two: Map<string, GridCell> = defaultCellsOf(HudZone.READOUT, order, planPerRow(2));
  const groups: Array<Array<HudEntry>> = hudRowGroups(HudZone.READOUT, order, 2);
  for (let row = 0; row < groups.length; row++) {
    for (let col = 0; col < groups[row].length; col++) {
      assertEq(`${col}.${row}`, (two.get(groups[row][col].id) as GridCell).token);
    }
  }
});

test('非网格容器永远拿哨兵格_列上限1且没有占用', () => {
  for (const it of HudLayoutTable.default().gridItems(HudZone.TOP, PLAN_ALL)) {
    assertTrue(it.cell.isDefault, '顶栏拿到的格子必须恒为哨兵');
  }
  for (const it of HudLayoutTable.default().gridItems(HudZone.BOTTOM, PLAN_ALL)) {
    assertTrue(it.cell.isDefault, '底栏拿到的格子必须恒为哨兵');
  }
  assertEq(1, gridColsCapOf(HudZone.TOP));
  assertEq(0, HudLayoutTable.default().occupiedCells(HudZone.TOP, PLAN_ALL).size);
});

// ================================================================== 二、独立性（本任务唯一的验收口径）

test('左Dock把监看拖到第4行_其余三颗的格子与坐标一字未变', () => {
  const before: Map<string, string> = cellsOf(HudLayoutTable.default(), HudZone.LEFT, PLAN_ALL);
  const moved: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.MONITOR), HudZone.LEFT, 3, new GridCell(0, 4), PLAN_ALL);
  const after: Map<string, string> = cellsOf(moved, HudZone.LEFT, PLAN_ALL);
  assertEq('0.4', after.get(e(CamPill.MONITOR).id), '监看必须落在用户要的第 4 行');
  // 手写的期望值（不是"与实现比"）
  assertEq('0.0', after.get(e(CamPill.REFLINE).id));
  assertEq('0.2', after.get(e(CamPill.CURVE).id));
  assertEq('0.3', after.get(e(CamPill.FLASH).id));
  assertEq('0,0', offsetString(cellOffsetPx(new GridCell(0, 0), PITCH)));
  assertEq('0,60', offsetString(cellOffsetPx(new GridCell(0, 2), PITCH)));
  assertEq('0,90', offsetString(cellOffsetPx(new GridCell(0, 3), PITCH)));
  // 顺序变了但位置没变
  assertEq('P6,P8,P9,P7', idsOf(moved.orderOf(HudZone.LEFT)));
  assertEq('0.2', moved.gridItems(HudZone.LEFT, PLAN_ALL)[1].cell.token,
    '顺序第 2 位的曲线仍在第 2 行');
  // 差分断言：除被拖那颗，逐颗完全相同
  before.delete(e(CamPill.MONITOR).id);
  after.delete(e(CamPill.MONITOR).id);
  assertEq(mapString(before), mapString(after), '其余三颗不许被牵连');
});

test('把蓝牙挪出右Dock_其余五颗的格子冻结', () => {
  const before: Map<string, string> = cellsOf(HudLayoutTable.default(), HudZone.RIGHT, PLAN_ALL);
  const moved: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.BT), HudZone.TOP, 1, PLAN_ALL);
  const after: Map<string, string> = cellsOf(moved, HudZone.RIGHT, PLAN_ALL);
  assertEq('P0,P1,P3,P4,P5', idsOf(moved.orderOf(HudZone.RIGHT)));
  assertEq('0.0', after.get(e(CamPill.LEVEL).id));
  assertEq('0.0', after.get(e(CamPill.VOLUME).id), '抽走蓝牙不该把配对拆开');
  assertEq('0.2', after.get(e(CamPill.ZOOM).id), '变焦不许上移');
  assertEq('0.3', after.get(e(CamPill.FOCUS).id), '对焦不许上移');
  assertEq('0.4', after.get(e(CamPill.STAB).id), '防抖不许上移');
  // 手算 px：格长 50×30
  assertEq('0,0', offsetString(cellOffsetPx(new GridCell(0, 0), PITCH)));
  assertEq('0,60', offsetString(cellOffsetPx(new GridCell(0, 2), PITCH)));
  assertEq('0,90', offsetString(cellOffsetPx(new GridCell(0, 3), PITCH)));
  assertEq('0,120', offsetString(cellOffsetPx(new GridCell(0, 4), PITCH)));
  // 差分断言
  before.delete(e(CamPill.BT).id);
  after.delete(e(CamPill.BT).id);
  assertEq(mapString(before), mapString(after));
  // 顶栏不是网格容器：那颗进去之后格子回哨兵
  assertEq(HudZone.TOP, moved.sourceZoneOf(e(CamPill.BT)));
  for (const it of moved.gridItems(HudZone.TOP, PLAN_ALL)) {
    assertTrue(it.cell.isDefault, '顶栏的格子必须回哨兵');
  }
  // 钉格子的证据：来源容器的五颗现在都带**显式**格子
  assertEq(5, moved.cellsOf(HudZone.RIGHT).size);
});

test('钉格子只钉来源与目标两枚容器', () => {
  const t: HudLayoutTable = HudLayoutTable.default().moveEntryTo(e(CamPill.CURVE), HudZone.READOUT, 0, PLAN_ALL);
  assertTrue(t.cellsOf(HudZone.LEFT).size > 0, '来源（左 Dock）要钉');
  assertTrue(t.cellsOf(HudZone.READOUT).size > 0, '目标（读数块）要钉');
  assertEq(0, t.cellsOf(HudZone.RIGHT).size, '没牵连的右 Dock 不许钉');
  assertEq(0, t.cellsOf(HudZone.BOTTOM).size, '没牵连的底栏不许写格子');
});

// ================================================================== 三、落点钳制与交换

test('就近找空格_永远不返回已占格_带外落点先钳再找', () => {
  const occupied: Set<string> = new Set<string>(['0.0', '1.0', '0.1']);
  assertEq('1.1', freeCellNear(new GridCell(0, 0), occupied, 2, 3, 0, 30).token);
  assertEq('1.0', freeCellNear(new GridCell(0, 0), new Set<string>(['0.0']), 2, 3, 0, 30).token);
  // 拖出带外：负列 → 0 列，99 行 → 最后一行，再找空位
  assertEq('0.2', freeCellNear(new GridCell(-9, 99), new Set<string>(), 2, 3, 0, 30).token);
  // 整带占满不许抛，也不许给被占格以外的野值
  const full: Set<string> = new Set<string>(['0.0', '1.0', '0.1', '1.1', '0.2', '1.2']);
  assertEq('0.0', freeCellNear(new GridCell(0, 0), full, 2, 3, 0, 30).token);
});

test('拖到已占格_两颗交换_其余不动', () => {
  // 把「闪光灯」拖到「RGB 曲线」已占的 (0,2)：曲线挪到闪光的原格 (0,3)，闪光拿 (0,2)
  // 退回"只落空格"的实现时闪光落到 (1,2)，本用例当场红
  const placed: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.FLASH), HudZone.LEFT, 2, new GridCell(0, 2), PLAN_ALL);
  const after: Map<string, string> = cellsOf(placed, HudZone.LEFT, PLAN_ALL);
  assertEq('0.2', after.get(e(CamPill.FLASH).id), '被拖那颗拿到目标格');
  assertEq('0.3', after.get(e(CamPill.CURVE).id), '占位者挪到被拖那颗的原格');
  assertEq('0.0', after.get(e(CamPill.REFLINE).id));
  assertEq('0.1', after.get(e(CamPill.MONITOR).id));
  assertEq('0.3', storedToken(placed, HudZone.LEFT, e(CamPill.CURVE)), '被挤者必须写成显式格');
  // 四颗仍各占一格
  assertEq(4, new Set<string>(['0.2', '0.3', '0.0', '0.1']).size);
});

test('原格还住着第三颗时_拒绝交换改为占位者让位', () => {
  // 右 Dock：姿态仪 + 音量表共格 (0,0)。把音量表拖到蓝牙 (0,1) ⇒ 原格还有搭档 ⇒ 不交换
  const placed: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, new GridCell(0, 1), PLAN_ALL);
  const after: Map<string, string> = cellsOf(placed, HudZone.RIGHT, PLAN_ALL);
  assertEq('0.1', after.get(e(CamPill.VOLUME).id), '被拖那颗仍拿目标格');
  assertEq('1.0', after.get(e(CamPill.BT).id), '占位者（蓝牙）就近让到 (1,0)');
  assertEq('0.0', after.get(e(CamPill.LEVEL).id), '搭档一格不动');
  assertEq('0.0', storedToken(placed, HudZone.RIGHT, e(CamPill.LEVEL)));
  assertEq('1.0', storedToken(placed, HudZone.RIGHT, e(CamPill.BT)));
  assertEq('0.2', after.get(e(CamPill.ZOOM).id));
  assertEq('0.3', after.get(e(CamPill.FOCUS).id));
  assertEq('0.4', after.get(e(CamPill.STAB).id));
});

test('跨容器落到已占格_只落空格不交换', () => {
  // 把左 Dock 的参考线拖到右 Dock 蓝牙已占的 (0,1)：曼哈顿距离 1 的最近空格 (1,1)
  const placed: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.REFLINE), HudZone.RIGHT, 1, new GridCell(0, 1), PLAN_ALL);
  const after: Map<string, string> = cellsOf(placed, HudZone.RIGHT, PLAN_ALL);
  assertEq('1.1', after.get(e(CamPill.REFLINE).id));
  assertEq('0.1', after.get(e(CamPill.BT).id), '目标容器里那颗原地不动');
  assertEq('0.0', after.get(e(CamPill.LEVEL).id));
  // 既有缺陷（与本批无关，改前逐字同值）：存储层两颗仍钉 (0,0)，渲染把音量表挪到 (1,0)
  assertEq('1.0', after.get(e(CamPill.VOLUME).id), '既有缺陷：被拆出配对格');
  assertEq('0.0', storedToken(placed, HudZone.RIGHT, e(CamPill.LEVEL)));
  assertEq('0.0', storedToken(placed, HudZone.RIGHT, e(CamPill.VOLUME)));
  // 不写出越区格
  after.forEach((token: string): void => {
    assertTrue(token.startsWith('0.') || token.startsWith('1.'), `越区格：${token}`);
  });
  assertEq(HudZone.RIGHT, placed.sourceZoneOf(e(CamPill.REFLINE)));
  const left: Map<string, string> = cellsOf(placed, HudZone.LEFT, PLAN_ALL);
  assertEq('0.1', left.get(e(CamPill.MONITOR).id), '监看留在原格');
  assertEq('0.2', left.get(e(CamPill.CURVE).id));
  assertEq('0.3', left.get(e(CamPill.FLASH).id));
});

test('预览与写表同源_cellDropOf的落点等于placeEntryAt的落点', () => {
  const table: HudLayoutTable = HudLayoutTable.default();
  const wanted: GridCell = new GridCell(0, 2);
  const origin: GridCell = new GridCell(0, 3);
  const expected = cellDropOf(
    wanted,
    origin,
    table.residentsOf(HudZone.LEFT, PLAN_ALL),
    e(CamPill.FLASH),
    cellGroupMatesOf(table.defaultGridOf(HudZone.LEFT, PLAN_ALL), e(CamPill.FLASH)),
    0,
    PLAN_ALL.cellHeightOf,
    gridColsCapOf(HudZone.LEFT),
    HudEntry.ALL.length,
    PLAN_ALL.rowPitchOf(HudZone.LEFT)
  );
  assertEq('0.2', expected.dropped.token);
  assertEq(1, expected.moved.size);
  assertEq('0.3', (expected.moved.get(e(CamPill.CURVE).id) as GridCell).token);
  const written: HudLayoutTable = table.placeEntryAt(e(CamPill.FLASH), HudZone.LEFT, 2, wanted, PLAN_ALL);
  const after: Map<string, string> = cellsOf(written, HudZone.LEFT, PLAN_ALL);
  assertEq(expected.dropped.token, after.get(e(CamPill.FLASH).id), '预览落点必须等于写表落点');
  expected.moved.forEach((cell: GridCell, id: string): void => {
    assertEq(cell.token, after.get(id), `被挤者 ${id} 的落点`);
  });
});

test('可编辑行档等于预留加一_再夹进带内档数', () => {
  // 左 Dock 横屏带 308dp ÷ 34dp ≈ 9 档、预留 4 ⇒ 5
  assertEq(5, editableGridRowsOf(4, 9));
  assertEq(4, editableGridRowsOf(4, 4), '带高只够预留时生长被吃光');
  assertEq(7, editableGridRowsOf(7, 7));
});

test('没有预留那一支与改前逐字同值_且生长量可显式覆盖', () => {
  assertEq(9, editableGridRowsOf(0, 9));
  assertEq(1, editableGridRowsOf(0, 0));
  assertEq(1, editableGridRowsOf(-3, 0));
  assertEq(7, editableGridRowsOf(7, 9, 0));
  assertEq(9, editableGridRowsOf(7, 9, 5));
});

test('格盒按生长后的上限夹格子', () => {
  const grown: GridBox = gridBoxOf(HudZone.LEFT, 34, 30, 300, 0, 4, 0, GRID_ROW_GROWTH_ROWS, 0);
  assertEq(5, grown.rows);
  assertEq('0.4', clampCellToBox(new GridCell(0, 99), grown, 0, 30).token);
  const reservedOnly: GridBox = gridBoxOf(HudZone.LEFT, 34, 30, 300, 0, 4, 0, 0, 0);
  assertEq(4, reservedOnly.rows);
  assertEq('0.3', clampCellToBox(new GridCell(0, 99), reservedOnly, 0, 30).token);
  assertEq(4, gridBoxOf(HudZone.LEFT, 34, 30, 120, 0, 4, 0, GRID_ROW_GROWTH_ROWS, 0).rows,
    '带高不够时生长被吃光');
});

test('解析格子_显式优先且同格被拆开', () => {
  const order: Array<HudEntry> = [e(CamPill.REFLINE), e(CamPill.MONITOR), e(CamPill.CURVE)];
  const derived: Map<string, GridCell> = new Map<string, GridCell>();
  derived.set(e(CamPill.REFLINE).id, new GridCell(0, 0));
  derived.set(e(CamPill.MONITOR).id, new GridCell(0, 1));
  derived.set(e(CamPill.CURVE).id, new GridCell(0, 2));
  const noHeights = (_entry: HudEntry): number => 0;
  // ① 两颗显式同格 ⇒ 顺序在前保留，后者就近让位
  const collidedPlaced: Map<string, GridCell> = new Map<string, GridCell>();
  collidedPlaced.set(e(CamPill.REFLINE).id, new GridCell(0, 0));
  collidedPlaced.set(e(CamPill.MONITOR).id, new GridCell(0, 0));
  const collided: Array<HudGridItem> =
    resolveCells(order, collidedPlaced, derived, 2, 3, noHeights, 30);
  assertEq('0.0,1.0,0.2', collided.map((it: HudGridItem): string => it.cell.token).join(','));
  // ② 显式格压在别颗的推导格上 ⇒ 摆过的赢、没摆过的让
  const overridePlaced: Map<string, GridCell> = new Map<string, GridCell>();
  overridePlaced.set(e(CamPill.CURVE).id, new GridCell(0, 1));
  const override: Array<HudGridItem> =
    resolveCells(order, overridePlaced, derived, 2, 3, noHeights, 30);
  assertEq('0.0', override[0].cell.token);
  assertEq('1.1', override[1].cell.token, '推导那颗让位');
  assertEq('0.1', override[2].cell.token, '摆过那颗拿到它要的那格');
  assertEq(3, new Set<string>(['0.0', '1.1', '0.1']).size);
});

test('隐藏条目仍占着自己的格子_被压上时显式挤走', () => {
  const placed: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.BT), HudZone.RIGHT, 2, new GridCell(1, 3), PLAN_ALL);
  const visibleOnly: Set<string> = new Set<string>();
  for (const x of HudEntry.ALL) {
    if (x.id !== e(CamPill.BT).id) {
      visibleOnly.add(x.id);
    }
  }
  const hiddenPlan: HudGridPlan = new HudGridPlan(visibleOnly, 3,
    (_zone: HudZone): number => 30, (_entry: HudEntry): number => 0);
  assertTrue(placed.occupiedCells(HudZone.RIGHT, hiddenPlan).has('1.3'), '隐藏那颗的格子必须算占用');
  // 防抖（可见）拖到蓝牙（隐藏）那一格：交换 ⇒ 蓝牙挪到防抖原格 (0,4)
  const dropped: HudLayoutTable =
    placed.placeEntryAt(e(CamPill.STAB), HudZone.RIGHT, 4, new GridCell(1, 3), hiddenPlan);
  const cells: Map<string, string> = cellsOf(dropped, HudZone.RIGHT, hiddenPlan);
  assertEq('1.3', cells.get(e(CamPill.STAB).id), '被拖那颗拿到目标格');
  assertEq('0.4', storedToken(dropped, HudZone.RIGHT, e(CamPill.BT)), '隐藏条目被显式挤到防抖原格');
  assertEq('0.4', cellsOf(dropped, HudZone.RIGHT, PLAN_ALL).get(e(CamPill.BT).id),
    '蓝牙重新可见后落在被挤到的那一格');
});

// ================================================================== 四、格子 ↔ 像素

test('格网的逆算与正算互逆_单列与空容器各一档', () => {
  const p: HudSizePx = new HudSizePx(90, 42);
  const g: HudSizePx = new HudSizePx(6, 6);
  assertEq('264x78', sizeString(gridSizePx(3, 2, p, g)));
  assertEq('90x42', sizeString(gridPitchOf(264, 78, 3, 2, 6, 999, 999)));
  assertEq('84x36', sizeString(gridSizePx(1, 1, p, g)));
  assertEq('174x78', sizeString(gridSizePx(2, 2, p, g)));
  assertEq('71x42', sizeString(gridPitchOf(0, 0, 0, 0, 5, 66, 37)));
  assertEq('0x0', sizeString(gridSizePx(0, 3, p, g)));
});

test('格子坐标与指针往返_越界钳0_格长0不抛_格内居中', () => {
  const origin: HudPointPx = new HudPointPx(10, 20);
  assertEq('0,0', offsetString(cellOffsetPx(new GridCell(0, 0), PITCH)));
  assertEq('100,90', offsetString(cellOffsetPx(new GridCell(2, 3), PITCH)));
  for (const cell of [new GridCell(0, 0), new GridCell(1, 2), new GridCell(2, 4)]) {
    const at: HudPointPx = cellOffsetPx(cell, PITCH);
    assertEq(cell.token, cellAt(new HudPointPx(origin.x + at.x + 1, origin.y + at.y + 1), origin, PITCH).token);
  }
  assertEq('0.0', cellAt(new HudPointPx(0, 0), origin, PITCH).token, '负坐标钳成 0');
  assertEq('0.0', cellAt(new HudPointPx(99, 99), origin, new HudSizePx(0, 0)).token, '格长 0 不许抛');
  assertEq('110,100', offsetString(cellPlaceOffsetPx(new GridCell(2, 3), PITCH, new HudSizePx(30, 10), 1)));
});

test('钳制吃的是容器带_不是屏幕猜测', () => {
  const box: GridBox = gridBoxOf(HudZone.LEFT, 50, 30, 120, 0, 0, 0, 0, 0);
  assertEq(2, box.cols);
  assertEq(4, box.rows);
  assertEq('1.3', clampCellToBox(new GridCell(7, 99), box, 0, 30).token);
  assertEq('0.1', clampCellToBox(new GridCell(-3, 1), box, 0, 30).token);
  assertTrue(clampCellToBox(GridCell.DEFAULT, box, 0, 30).isDefault, '哨兵原样放行');
  // 读数块的列数由**可用内宽**说话：200 ÷ 90 = 2 列
  assertEq(2, gridBoxOf(HudZone.READOUT, 90, 42, 300, 200, 0, 0, 0, 0).cols);
  assertEq(1, gridBoxOf(HudZone.READOUT, 0, 0, 300, 200, 0, 0, 0, 0).rows);
  assertEq(2, gridColsCapOf(HudZone.LEFT));
  assertEq(2, gridColsCapOf(HudZone.RIGHT));
  assertEq(3, gridColsCapOf(HudZone.READOUT));
  assertEq(1, gridColsCapOf(HudZone.TOP));
  assertEq('1.19', clampStoredCell(HudZone.LEFT, new GridCell(99, 999)).token);
  assertTrue(clampStoredCell(HudZone.LEFT, GridCell.DEFAULT).isDefault);
});

test('格子落盘往返无损且幂等', () => {
  const t: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 0, new GridCell(1, 5), PLAN_ALL)
    .placeEntryAt(h(HudItem.EV), HudZone.READOUT, 3, new GridCell(2, 4), PLAN_ALL);
  const back: HudLayoutTable = HudLayoutTable.decode(t.encode());
  assertEq(t.encode(), back.encode(), '整张表必须无损');
  assertEq('1.5', storedToken(back, HudZone.LEFT, e(CamPill.REFLINE)));
  assertEq('2.4', storedToken(back, HudZone.READOUT, h(HudItem.EV)));
  assertEq(t.encode(), back.encode(), '编→解→编同串');
});

test('重置清掉每一格_音量表回到配对那一格', () => {
  const edited: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 5, new GridCell(1, 7), PLAN_ALL);
  assertTrue(edited.cellsOf(HudZone.RIGHT).size > 0, '编辑后该有显式格子');
  for (const zone of [HudZone.TOP, HudZone.LEFT, HudZone.RIGHT, HudZone.READOUT, HudZone.BOTTOM]) {
    assertEq(0, HudLayoutTable.default().cellsOf(zone).size, `${zone} 段重置后不许留格子`);
  }
  assertEq('0.0', cellsOf(HudLayoutTable.default(), HudZone.RIGHT, PLAN_ALL).get(e(CamPill.VOLUME).id),
    '重置后音量表回到配对那一格');
});

// ================================================================== 五、#80：锚定与预留

const GAP8: HudSizePx = new HudSizePx(8, 8);

function uniformLeftWidths(items: Array<HudGridItem>): Array<HudSizePx> {
  return items.map((_it: HudGridItem): HudSizePx => new HudSizePx(60, 20));
}

function rightWidths(items: Array<HudGridItem>): Array<HudSizePx> {
  return items.map((it: HudGridItem): HudSizePx => {
    if (it.entry.pill === CamPill.LEVEL) {
      return new HudSizePx(100, 20);
    }
    if (it.entry.pill === CamPill.VOLUME) {
      return new HudSizePx(60, 20);
    }
    return new HudSizePx(40, 20);
  });
}

function placedOf(zone: HudZone, items: Array<HudGridItem>): GridPlacement {
  const sizes: Array<HudSizePx> = zone === HudZone.LEFT ? uniformLeftWidths(items) : rightWidths(items);
  return gridPlacementOf(items, sizes, GAP8, 30,
    new GridAnchor(zone, HudLayoutTable.default().defaultGridOf(zone, PLAN_ALL)));
}

test('左Dock拖一颗只动它_板子会长宽但别人一个像素不动', () => {
  const table: HudLayoutTable = HudLayoutTable.default();
  const before: Array<HudGridItem> = table.gridItems(HudZone.LEFT, PLAN_ALL);
  const placedBefore: GridPlacement = placedOf(HudZone.LEFT, before);
  assertEq('68x30', sizeString(placedBefore.pitch));
  assertEq('60x112', sizeString(placedBefore.size));
  assertEq('4,5;4,35;4,65;4,95', offsetsString(placedBefore.offsets));
  const movedItems: Array<HudGridItem> = table
    .placeEntryAt(e(CamPill.MONITOR), HudZone.LEFT, 1, new GridCell(1, 1), PLAN_ALL)
    .gridItems(HudZone.LEFT, PLAN_ALL);
  const placedAfter: GridPlacement = placedOf(HudZone.LEFT, movedItems);
  assertEq('128x112', sizeString(placedAfter.size), '两列 ⇒ 格网宽 2×68−8');
  const byEntry: Map<string, string> = new Map<string, string>();
  for (let i = 0; i < movedItems.length; i++) {
    byEntry.set(movedItems[i].entry.id, offsetString(placedAfter.offsets[i]));
  }
  assertEq('4,5', byEntry.get(e(CamPill.REFLINE).id));
  assertEq('4,65', byEntry.get(e(CamPill.CURVE).id));
  assertEq('4,95', byEntry.get(e(CamPill.FLASH).id));
  assertEq('72,35', byEntry.get(e(CamPill.MONITOR).id), '左缘 1×68 + (68−60)/2');
});

test('行溢出被预留钳住_坏数据也只让板子长高', () => {
  const box: GridBox = gridBoxOf(HudZone.LEFT, 34, 30, 300, 0, 4, 0, 0, 0);
  assertEq(2, box.cols);
  assertEq(4, box.rows, '预留 4 档 ⇒ 带内 10 档也只许用 4 档');
  assertEq('0.3', clampCellToBox(new GridCell(0, 99), box, 0, 30).token);
  const over: Array<HudGridItem> = [
    new HudGridItem(e(CamPill.REFLINE), new GridCell(0, 0)),
    new HudGridItem(e(CamPill.MONITOR), new GridCell(0, 1)),
    new HudGridItem(e(CamPill.CURVE), new GridCell(0, 2)),
    new HudGridItem(e(CamPill.FLASH), new GridCell(0, 5))
  ];
  const placed: GridPlacement = placedOf(HudZone.LEFT, over);
  assertEq(172, placed.size.height, '6 档 × 30 − 8 = 172');
  const byEntry: Map<string, string> = new Map<string, string>();
  for (let i = 0; i < over.length; i++) {
    byEntry.set(over[i].entry.id, offsetString(placed.offsets[i]));
  }
  assertEq('4,5', byEntry.get(e(CamPill.REFLINE).id));
  assertEq('4,35', byEntry.get(e(CamPill.MONITOR).id));
  assertEq('4,65', byEntry.get(e(CamPill.CURVE).id));
});

test('右Dock从锚定边数列_第0列一颗都不动', () => {
  const table: HudLayoutTable = HudLayoutTable.default();
  const before: Array<HudGridItem> = table.gridItems(HudZone.RIGHT, PLAN_ALL);
  const placedBefore: GridPlacement = placedOf(HudZone.RIGHT, before);
  // 配对格 100 + 8 + 60 = 168 ⇒ 格长 176；单列 ⇒ 168 宽、5×30−8 = 142 高
  assertEq('176x30', sizeString(placedBefore.pitch));
  assertEq('168x142', sizeString(placedBefore.size));
  assertEq('4,5;112,5;68,35;68,65;68,95;68,125', offsetsString(placedBefore.offsets));
  const movedItems: Array<HudGridItem> = table
    .placeEntryAt(e(CamPill.BT), HudZone.RIGHT, 2, new GridCell(1, 1), PLAN_ALL)
    .gridItems(HudZone.RIGHT, PLAN_ALL);
  const placedAfter: GridPlacement = placedOf(HudZone.RIGHT, movedItems);
  assertEq('344x142', sizeString(placedAfter.size));
  const afterByEntry: Map<string, string> = new Map<string, string>();
  for (let i = 0; i < movedItems.length; i++) {
    afterByEntry.set(movedItems[i].entry.id, offsetString(placedAfter.offsets[i]));
  }
  // 别颗不动断的是"离带右缘的距离"（底板贴右缘钉住）
  for (let i = 0; i < before.length; i++) {
    const entry: HudEntry = before[i].entry;
    if (entry.id === e(CamPill.BT).id) {
      continue;
    }
    const beforeAt: HudPointPx = placedBefore.offsets[i];
    const afterAt: HudPointPx = placedAfter.offsets[i];
    assertEq(placedBefore.size.width - beforeAt.x, placedAfter.size.width - afterAt.x,
      `${entry.id} 离带右缘的距离不许变`);
    assertEq(beforeAt.y, afterAt.y, `${entry.id} 的纵向不许变`);
  }
  assertEq('68,35', afterByEntry.get(e(CamPill.BT).id));
});

test('拆对之后格长仍取默认配对格_旧模型会让所有人平移', () => {
  const table: HudLayoutTable = HudLayoutTable.default();
  const split: Array<HudGridItem> = table
    .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, new GridCell(0, 5), PLAN_ALL)
    .gridItems(HudZone.RIGHT, PLAN_ALL);
  const reserved: GridPlacement = placedOf(HudZone.RIGHT, split);
  assertEq(176, reserved.pitch.width, '格长仍取默认表那枚配对格 168 + 8');
  assertEq(168, reserved.size.width, '底板宽不许因为拆对变窄');
  const byEntry: Map<string, string> = new Map<string, string>();
  for (let i = 0; i < split.length; i++) {
    byEntry.set(split[i].entry.id, offsetString(reserved.offsets[i]));
  }
  assertEq('38,5', byEntry.get(e(CamPill.LEVEL).id), '姿态仪在自己那一格里重新居中');
  assertEq('68,35', byEntry.get(e(CamPill.BT).id));
  assertEq('68,95', byEntry.get(e(CamPill.FOCUS).id));
  // 反证：默认表喂空 = 改前"最宽那枚渲染格子" ⇒ 格长掉到 108
  const oldModel: GridPlacement = gridPlacementOf(split, rightWidths(split), GAP8, 30,
    new GridAnchor(HudZone.RIGHT, new Map<string, GridCell>()));
  assertEq(108, oldModel.pitch.width);
});

test('读数块只预留列不预留行_两枚竖Dock反过来', () => {
  const table: HudLayoutTable = HudLayoutTable.default();
  const anchor: GridAnchor = new GridAnchor(HudZone.READOUT, table.defaultGridOf(HudZone.READOUT, PLAN_ALL));
  assertEq(3, reservedGridColsOf(anchor), '读数块预留列数 = 默认表用到的列数');
  assertEq(0, reservedGridRowsOf(anchor, (_entry: HudEntry): number => 0, 30),
    '读数块故意不预留行档');
  const rightAnchor: GridAnchor = new GridAnchor(HudZone.RIGHT, table.defaultGridOf(HudZone.RIGHT, PLAN_ALL));
  assertEq(0, reservedGridColsOf(rightAnchor));
  assertEq(5, reservedGridRowsOf(rightAnchor, (_entry: HudEntry): number => 0, 30), '右 Dock 默认用到 5 档');
  const leftAnchor: GridAnchor = new GridAnchor(HudZone.LEFT, table.defaultGridOf(HudZone.LEFT, PLAN_ALL));
  assertEq(4, reservedGridRowsOf(leftAnchor, (_entry: HudEntry): number => 0, 30), '左 Dock 默认用到 4 档');
  // 读数块可用宽 500 ÷ 90 = 5 列，但上限 3、预留 2 ⇒ 夹到 2
  assertEq(2, gridBoxOf(HudZone.READOUT, 90, 36, 300, 500, 0, 2, 0, 0).cols);
  assertTrue(gridColFromEndOf(HudZone.RIGHT));
  assertFalse(gridColFromEndOf(HudZone.LEFT));
  assertFalse(gridColFromEndOf(HudZone.READOUT));
});

test('指针还在来源带里就不许改归属_带边界四条', () => {
  const land: HudAreaDp = new HudAreaDp(766, 360, 44, 72);
  assertEq(44, zoneBandYRange(HudZone.RIGHT, land, 0).first);
  assertEq(288, zoneBandYRange(HudZone.RIGHT, land, 0).last);
  assertEq(0, zoneBandYRange(HudZone.TOP, land, 0).first);
  assertEq(360, zoneBandYRange(HudZone.TOP, land, 0).last);
  assertEq(44, zoneBandYRange(HudZone.RIGHT, new HudAreaDp(766, 50, 44, 72), 0).first);
  assertEq(44, zoneBandYRange(HudZone.RIGHT, new HudAreaDp(766, 50, 44, 72), 0).last);
  const rightCard: HudRectDp = new HudRectDp(660, 60, 758, 260);
  const band = zoneBandYRange(HudZone.RIGHT, land, 0);
  // ① 拖出底板却还在带里 ⇒ 归属不许改
  assertEq(HudZone.RIGHT, dropZoneOf(
    new HudPointDp(700, 270), HudZone.RIGHT, rightCard, band, HudZone.READOUT));
  // ② 拖出带外 ⇒ 明显跨容器
  assertEq(HudZone.READOUT, dropZoneOf(
    new HudPointDp(700, 300), HudZone.RIGHT, rightCard, band, HudZone.READOUT));
  // ③ 横向离开底板 ⇒ 允许改归属
  assertEq(HudZone.READOUT, dropZoneOf(
    new HudPointDp(620, 270), HudZone.RIGHT, rightCard, band, HudZone.READOUT));
  // ④ 谁都没压住 / 压住的正是来源
  assertEq(HudZone.RIGHT, dropZoneOf(
    new HudPointDp(700, 270), HudZone.RIGHT, rightCard, band, undefined));
  assertEq(HudZone.RIGHT, dropZoneOf(
    new HudPointDp(700, 100), HudZone.RIGHT, rightCard, band, HudZone.RIGHT));
  // ⑤ 首帧量不到底板 ⇒ 退回 hit
  assertEq(HudZone.READOUT, dropZoneOf(
    new HudPointDp(700, 270), HudZone.RIGHT, undefined, band, HudZone.READOUT));
});

// ================================================================== 六、配对格（HudLayoutPairCellTest）

const RIGHT_ANCHOR_EMPTY: GridAnchor = new GridAnchor(HudZone.RIGHT, new Map<string, GridCell>());

test('配对共格_右Dock没有第二列_六颗只占五格', () => {
  const def: Array<HudEntry> = HudLayoutTable.defaultOrderOf(HudZone.RIGHT);
  assertEq('P0,P1', idsOf(hudRowGroups(HudZone.RIGHT, def, 1)[0]));
  const derived: Map<string, GridCell> = defaultCellsOf(HudZone.RIGHT, def, PLAN_ALL);
  assertEq('0.0', derived.get(e(CamPill.LEVEL).id) === undefined ? '<none>' : (derived.get(e(CamPill.LEVEL).id) as GridCell).token);
  assertEq('0.0', (derived.get(e(CamPill.VOLUME).id) as GridCell).token);
  assertEq('0.1', (derived.get(e(CamPill.BT).id) as GridCell).token);
  assertEq('0.4', (derived.get(e(CamPill.STAB).id) as GridCell).token);
  let onlyColZero: boolean = true;
  derived.forEach((cell: GridCell): void => {
    if (cell.col !== 0) {
      onlyColZero = false;
    }
  });
  assertTrue(onlyColZero);
  assertEq('P1', setString(cellGroupMatesOf(derived, e(CamPill.LEVEL))));
  assertEq(0, cellGroupMatesOf(derived, e(CamPill.BT)).size);
  const readout: Map<string, GridCell> =
    defaultCellsOf(HudZone.READOUT, HudLayoutTable.defaultOrderOf(HudZone.READOUT), PLAN_ALL);
  for (const item of [HudItem.SHUTTER, HudItem.FPS, HudItem.BITRATE, HudItem.ISO, HudItem.EV,
    HudItem.WB, HudItem.ZOOM]) {
    assertEq(0, cellGroupMatesOf(readout, h(item)).size, `读数块不许出现共格：${item}`);
  }
  assertEq('2.0', (readout.get(h(HudItem.BITRATE).id) as GridCell).token);
  assertEq('0.1', (readout.get(h(HudItem.ISO).id) as GridCell).token);
  assertEq('2.1', (readout.get(h(HudItem.WB).id) as GridCell).token);
  const items: Array<HudGridItem> = HudLayoutTable.default().gridItems(HudZone.RIGHT, PLAN_ALL);
  const inPair: Array<string> = [];
  for (const it of items) {
    if (it.cell.token === '0.0') {
      inPair.push(it.entry.id);
    }
  }
  assertEq('P0,P1', inPair.join(','), '格内左右次序 = 清单次序');
  assertEq(5, new Set<string>(items.map((it: HudGridItem): string => it.cell.token)).size,
    '六颗只占五格（配对被拆成两列时这里红）');
});

test('拆开配对只动音量表_姿态仪与其余四颗冻结', () => {
  const before: Map<string, string> = cellsOf(HudLayoutTable.default(), HudZone.RIGHT, PLAN_ALL);
  const table: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, new GridCell(0, 5), PLAN_ALL);
  const after: Map<string, string> = cellsOf(table, HudZone.RIGHT, PLAN_ALL);
  assertEq('0.0', after.get(e(CamPill.LEVEL).id));
  assertEq('0.0', storedToken(table, HudZone.RIGHT, e(CamPill.LEVEL)));
  assertEq('0,0', offsetString(cellOffsetPx(new GridCell(0, 0), PITCH)));
  assertEq('0.5', after.get(e(CamPill.VOLUME).id));
  assertEq('0,150', offsetString(cellOffsetPx(new GridCell(0, 5), PITCH)));
  assertEq('0.1', after.get(e(CamPill.BT).id));
  assertEq('0,30', offsetString(cellOffsetPx(new GridCell(0, 1), PITCH)));
  assertEq('0.2', after.get(e(CamPill.ZOOM).id));
  assertEq('0,60', offsetString(cellOffsetPx(new GridCell(0, 2), PITCH)));
  assertEq('0.3', after.get(e(CamPill.FOCUS).id));
  assertEq('0,90', offsetString(cellOffsetPx(new GridCell(0, 3), PITCH)));
  assertEq('0.4', after.get(e(CamPill.STAB).id));
  assertEq('0,120', offsetString(cellOffsetPx(new GridCell(0, 4), PITCH)));
  // 拆完仍只有一列
  after.forEach((token: string): void => {
    assertTrue(token.startsWith('0.'), `不许被顺手推去第 1 列：${token}`);
  });
  before.delete(e(CamPill.VOLUME).id);
  after.delete(e(CamPill.VOLUME).id);
  assertEq(mapString(before), mapString(after), '除被拖那颗逐颗一字未变');
});

test('把变焦拖到空行_配对格与其余条目冻结', () => {
  const before: Map<string, string> = cellsOf(HudLayoutTable.default(), HudZone.RIGHT, PLAN_ALL);
  const after: Map<string, string> = cellsOf(HudLayoutTable.default()
    .placeEntryAt(e(CamPill.ZOOM), HudZone.RIGHT, 2, new GridCell(0, 5), PLAN_ALL), HudZone.RIGHT, PLAN_ALL);
  assertEq('0.0', after.get(e(CamPill.LEVEL).id));
  assertEq('0.0', after.get(e(CamPill.VOLUME).id));
  assertEq('0,0', offsetString(cellOffsetPx(new GridCell(0, 0), PITCH)));
  assertEq('0,0', offsetString(cellOffsetPx(new GridCell(0, 0), PITCH)));
  assertEq('0.1', after.get(e(CamPill.BT).id));
  assertEq('0,30', offsetString(cellOffsetPx(new GridCell(0, 1), PITCH)));
  assertEq('0.3', after.get(e(CamPill.FOCUS).id));
  assertEq('0,90', offsetString(cellOffsetPx(new GridCell(0, 3), PITCH)));
  assertEq('0.4', after.get(e(CamPill.STAB).id));
  assertEq('0,120', offsetString(cellOffsetPx(new GridCell(0, 4), PITCH)));
  assertEq('0.5', after.get(e(CamPill.ZOOM).id));
  assertEq('0,150', offsetString(cellOffsetPx(new GridCell(0, 5), PITCH)));
  after.forEach((token: string): void => {
    assertTrue(token.startsWith('0.'), `不许被顺手推去第 1 列：${token}`);
  });
  before.delete(e(CamPill.ZOOM).id);
  after.delete(e(CamPill.ZOOM).id);
  assertEq(mapString(before), mapString(after));
});

test('同组搭档才可共格_跨组撞格整组让位', () => {
  const after: Map<string, string> = cellsOf(HudLayoutTable.default()
    .placeEntryAt(e(CamPill.ZOOM), HudZone.RIGHT, 2, new GridCell(0, 0), PLAN_ALL), HudZone.RIGHT, PLAN_ALL);
  assertEq('0.0', after.get(e(CamPill.ZOOM).id));
  assertEq('0.2', after.get(e(CamPill.LEVEL).id), '配对两颗整组让到变焦原格');
  assertEq('0.2', after.get(e(CamPill.VOLUME).id));
  assertEq('0.1', after.get(e(CamPill.BT).id));
  assertEq('0.3', after.get(e(CamPill.FOCUS).id));
  assertEq('0.4', after.get(e(CamPill.STAB).id));
  assertEq(after.get(e(CamPill.LEVEL).id), after.get(e(CamPill.VOLUME).id), '配对仍是共格');
  // 占用判据本身逐格点名
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set('0.0', [e(CamPill.LEVEL), e(CamPill.VOLUME)]);
  residents.set('0.1', [e(CamPill.BT)]);
  residents.set('1.0', [e(CamPill.ZOOM)]);
  const derived: Map<string, GridCell> =
    defaultCellsOf(HudZone.RIGHT, HudLayoutTable.defaultOrderOf(HudZone.RIGHT), PLAN_ALL);
  const zero = (_entry: HudEntry): number => 0;
  assertEq('0.1,1.0', setString(
    blockingFor(residents, e(CamPill.VOLUME), cellGroupMatesOf(derived, e(CamPill.VOLUME)), zero, 30)),
    '音量表眼里：配对格不算挡路');
  assertEq('0.0,1.0', setString(
    blockingFor(residents, e(CamPill.BT), cellGroupMatesOf(derived, e(CamPill.BT)), zero, 30)),
    '蓝牙眼里：配对格也挡路');
  assertEq('0.0,0.1,1.0', setString(blockingFor(residents, null, new Set<string>(), zero, 30)));
});

/** [blockingCells] 的薄包装（本文件只用它打共格口径） */
function blockingFor(
  residents: Map<string, Array<HudEntry>>,
  self: HudEntry | null,
  mates: Set<string>,
  cellHeightPx: (entry: HudEntry) => number,
  rowPitchPx: number
): Set<string> {
  return blockingCells(residents, self, mates, cellHeightPx, rowPitchPx);
}

test('把搭档拖回配对格_重新合对', () => {
  const broken: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, new GridCell(0, 5), PLAN_ALL);
  assertEq('0.5', cellsOf(broken, HudZone.RIGHT, PLAN_ALL).get(e(CamPill.VOLUME).id));
  const merged: HudLayoutTable =
    broken.placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, new GridCell(0, 0), PLAN_ALL);
  const after: Map<string, string> = cellsOf(merged, HudZone.RIGHT, PLAN_ALL);
  assertEq('0.0', after.get(e(CamPill.VOLUME).id), '合回去：两颗同格');
  assertEq('0.0', after.get(e(CamPill.LEVEL).id));
  assertEq('0.1', after.get(e(CamPill.BT).id));
  assertEq('0.2', after.get(e(CamPill.ZOOM).id));
  assertEq('0.3', after.get(e(CamPill.FOCUS).id));
  assertEq('0.4', after.get(e(CamPill.STAB).id));
  assertEq('0.0', storedToken(merged, HudZone.RIGHT, e(CamPill.LEVEL)));
  assertEq('0.0', storedToken(merged, HudZone.RIGHT, e(CamPill.VOLUME)));
});

test('默认右Dock的整条宽度账_底板94dp', () => {
  const gap: HudSizePx = new HudSizePx(8, 8);
  const rowPitch: number = 150; // 手喂：每格一档（142 ≤ 150）⇒ 与 #74 逐字同值
  const items: Array<HudGridItem> = HudLayoutTable.default().gridItems(HudZone.RIGHT, PLAN_ALL);
  const sizes: Map<string, HudSizePx> = new Map<string, HudSizePx>();
  sizes.set(e(CamPill.LEVEL).id, new HudSizePx(108, 140));
  sizes.set(e(CamPill.VOLUME).id, new HudSizePx(56, 142));
  sizes.set(e(CamPill.BT).id, new HudSizePx(94, 60));
  sizes.set(e(CamPill.ZOOM).id, new HudSizePx(94, 60));
  sizes.set(e(CamPill.FOCUS).id, new HudSizePx(94, 60));
  sizes.set(e(CamPill.STAB).id, new HudSizePx(94, 60));
  const placed: GridPlacement = gridPlacementOf(items,
    items.map((it: HudGridItem): HudSizePx => sizes.get(it.entry.id) as HudSizePx),
    gap, rowPitch, RIGHT_ANCHOR_EMPTY);
  assertEq('180x150', sizeString(placed.pitch));
  assertEq('172x742', sizeString(placed.size));
  assertEq(94, Math.trunc(placed.size.width / 2) + 8, '底板 = 86 + 4 + 4 = 94dp');
  assertEq('4,5;120,4;43,195;43,345;43,495;43,645', offsetsString(placed.offsets));
  // 拆开配对之后撑 pitch 的换成姿态仪那一格 ⇒ 底板 62dp
  const broken: Array<HudGridItem> = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, new GridCell(0, 5), PLAN_ALL)
    .gridItems(HudZone.RIGHT, PLAN_ALL);
  const brokenPlaced: GridPlacement = gridPlacementOf(broken,
    broken.map((it: HudGridItem): HudSizePx => sizes.get(it.entry.id) as HudSizePx),
    gap, rowPitch, RIGHT_ANCHOR_EMPTY);
  assertEq('116x150', sizeString(brokenPlaced.pitch));
  assertEq(62, Math.trunc(brokenPlaced.size.width / 2) + 8, '拆对后底板 = 54 + 4 + 4');
});

test('offsets按入参下标对齐_不是按格子分组输出', () => {
  const gap: HudSizePx = new HudSizePx(8, 8);
  const rowPitch: number = 150;
  const sizes: Map<string, HudSizePx> = new Map<string, HudSizePx>();
  sizes.set(e(CamPill.LEVEL).id, new HudSizePx(108, 140));
  sizes.set(e(CamPill.VOLUME).id, new HudSizePx(56, 142));
  sizes.set(e(CamPill.BT).id, new HudSizePx(94, 60));
  sizes.set(e(CamPill.ZOOM).id, new HudSizePx(94, 60));
  sizes.set(e(CamPill.FOCUS).id, new HudSizePx(94, 60));
  sizes.set(e(CamPill.STAB).id, new HudSizePx(94, 60));
  const shuffled: Array<HudGridItem> = [
    new HudGridItem(e(CamPill.STAB), new GridCell(0, 4)),
    new HudGridItem(e(CamPill.VOLUME), new GridCell(0, 0)),
    new HudGridItem(e(CamPill.BT), new GridCell(0, 1)),
    new HudGridItem(e(CamPill.LEVEL), new GridCell(0, 0)),
    new HudGridItem(e(CamPill.ZOOM), new GridCell(0, 2)),
    new HudGridItem(e(CamPill.FOCUS), new GridCell(0, 3))
  ];
  const placed: GridPlacement = gridPlacementOf(shuffled,
    shuffled.map((it: HudGridItem): HudSizePx => sizes.get(it.entry.id) as HudSizePx),
    gap, rowPitch, RIGHT_ANCHOR_EMPTY);
  assertEq('180x150', sizeString(placed.pitch));
  assertEq('172x742', sizeString(placed.size));
  assertEq('43,645', offsetString(placed.offsets[0]), '第 0 位必须是 STAB 自己的落点');
  assertEq('43,195', offsetString(placed.offsets[2]));
  assertEq('43,345', offsetString(placed.offsets[4]));
  assertEq('43,495', offsetString(placed.offsets[5]));
  assertEq('4,4', offsetString(placed.offsets[1]), '音量表这次在配对格左侧');
  assertEq('68,5', offsetString(placed.offsets[3]), '姿态仪紧跟其后');
  // 单住户格子的落点只由它自己的 (col,row) 与尺寸决定，与列表次序无关
  const natural: Array<HudGridItem> = [
    new HudGridItem(e(CamPill.LEVEL), new GridCell(0, 0)),
    new HudGridItem(e(CamPill.VOLUME), new GridCell(0, 0)),
    new HudGridItem(e(CamPill.BT), new GridCell(0, 1)),
    new HudGridItem(e(CamPill.ZOOM), new GridCell(0, 2)),
    new HudGridItem(e(CamPill.FOCUS), new GridCell(0, 3)),
    new HudGridItem(e(CamPill.STAB), new GridCell(0, 4))
  ];
  const naturalPlaced: GridPlacement = gridPlacementOf(natural,
    natural.map((it: HudGridItem): HudSizePx => sizes.get(it.entry.id) as HudSizePx),
    gap, rowPitch, RIGHT_ANCHOR_EMPTY);
  const a: Map<string, string> = new Map<string, string>();
  for (let i = 0; i < shuffled.length; i++) {
    a.set(shuffled[i].entry.id, offsetString(placed.offsets[i]));
  }
  const b: Map<string, string> = new Map<string, string>();
  for (let i = 0; i < natural.length; i++) {
    b.set(natural[i].entry.id, offsetString(naturalPlaced.offsets[i]));
  }
  for (const pill of [CamPill.BT, CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB]) {
    assertEq(b.get(e(pill).id), a.get(e(pill).id), `${pill} 的落点不该随列表次序变`);
  }
});

test('右Dock网格装进横屏带_旧模型溢出142dp作对照', () => {
  const density: number = 2;
  const gap: HudSizePx = new HudSizePx(8, 8);
  const rowPitch: number = gridRowPitchPx(1, 4, density);
  assertEq(68, rowPitch, '格距 =（18 + 12 + 4）× 2 ⇒ 68px');
  const heights: Map<string, number> = new Map<string, number>();
  heights.set(e(CamPill.LEVEL).id, 144);
  heights.set(e(CamPill.VOLUME).id, 142);
  heights.set(e(CamPill.BT).id, 60);
  heights.set(e(CamPill.ZOOM).id, 60);
  heights.set(e(CamPill.FOCUS).id, 60);
  heights.set(e(CamPill.STAB).id, 60);
  const widths: Map<string, number> = new Map<string, number>();
  widths.set(e(CamPill.LEVEL).id, 108);
  widths.set(e(CamPill.VOLUME).id, 56);
  widths.set(e(CamPill.BT).id, 94);
  widths.set(e(CamPill.ZOOM).id, 94);
  widths.set(e(CamPill.FOCUS).id, 94);
  widths.set(e(CamPill.STAB).id, 94);
  const plan: HudGridPlan = new HudGridPlan(
    new Set<string>(HudEntry.ALL.map((x: HudEntry): string => x.id)), 3,
    (_zone: HudZone): number => rowPitch,
    (entry: HudEntry): number => heights.get(entry.id) === undefined ? 0 : heights.get(entry.id) as number
  );
  const items: Array<HudGridItem> = HudLayoutTable.default().gridItems(HudZone.RIGHT, plan);
  const cells: Map<string, string> = cellsOf(HudLayoutTable.default(), HudZone.RIGHT, plan);
  assertEq('0.0', cells.get(e(CamPill.LEVEL).id));
  assertEq('0.0', cells.get(e(CamPill.VOLUME).id), '配对仍共用第 0 格');
  assertEq('0.3', cells.get(e(CamPill.BT).id), '蓝牙让到配对块之外的第 3 档');
  assertEq('0.4', cells.get(e(CamPill.ZOOM).id));
  assertEq('0.5', cells.get(e(CamPill.FOCUS).id));
  assertEq('0.6', cells.get(e(CamPill.STAB).id));
  const placed: GridPlacement = gridPlacementOf(items,
    items.map((it: HudGridItem): HudSizePx => new HudSizePx(
      widths.get(it.entry.id) as number, heights.get(it.entry.id) as number)),
    gap, rowPitch, RIGHT_ANCHOR_EMPTY);
  assertEq('180x68', sizeString(placed.pitch));
  assertEq(94, Math.trunc(placed.size.width / 2) + 8, '格网 172px 宽 ⇒ 底板 94dp');
  const bandDp: number = 360 - 44 - 72;
  const gridHeightDp: number = placed.size.height / density;
  near(234, gridHeightDp, 0.01, '7 档 ⇒ 468px = 234dp');
  assertEq(0, Math.max(0, Math.round(gridHeightDp - bandDp)), '网格高必须装进横屏带高');
  const cardBottomPx: number = placed.size.height + 8;
  assertEq(476, cardBottomPx, '整枚底板（含 4dp 内边距）= 476px = 238dp');
  for (let i = 0; i < items.length; i++) {
    const bottom: number = placed.offsets[i].y + (heights.get(items[i].entry.id) as number);
    if (items[i].entry.pill === CamPill.BT || items[i].entry.pill === CamPill.ZOOM ||
      items[i].entry.pill === CamPill.FOCUS || items[i].entry.pill === CamPill.STAB) {
      assertTrue(bottom + 8 <= bandDp * density, `${items[i].entry.id} 的底边必须落在带内`);
    }
  }
  // 旧模型对照：行距 = 实测最高那颗 148 + 8 = 156、人人一档 ⇒ 5×156−8 = 772px = 386dp（溢出 142dp）
  const oldModelDp: number = gridSizePx(1, 5, new HudSizePx(180, 156), gap).height / density;
  near(142, oldModelDp - bandDp, 0.01, '旧模型对照：386dp 比带高多 142dp');
});

test('配对格内容宽是宽度账的唯一来源_94dp底板', () => {
  const gapX: number = 8;
  const attitude: number = 108;
  const volume: number = 56;
  const chip: number = 94;
  assertEq(172, cellContentWidthPx([attitude, volume], gapX), '配对格内容宽 = 54 + 4 + 28');
  assertTrue(cellContentWidthPx([attitude, volume], gapX) > cellContentWidthPx([attitude], gapX),
    '配对格必须比最宽那颗还宽');
  assertEq(attitude, cellContentWidthPx([attitude], gapX), '一颗时不许多算行距');
  assertEq(chip, cellContentWidthPx([chip], gapX));
  assertEq(0, cellContentWidthPx([], gapX));
  const pitchW: number = gridPitchPx(172, 148, gapX).width;
  assertEq(180, pitchW);
  const gridW: number = gridSizePx(1, 5, new HudSizePx(pitchW, 148), new HudSizePx(gapX, gapX)).width;
  assertEq(172, gridW);
  assertEq(94, Math.trunc(gridW / 2) + 8);
  // 反证：上一批"配对占两列"在同一个 gridSizePx 下给 224px = 112dp（要抹掉的 26dp）
  const oldPitch: number = gridPitchPx(attitude, 148, gapX).width;
  assertEq(224, gridSizePx(2, 5, new HudSizePx(oldPitch, 148), new HudSizePx(gapX, gapX)).width);
  // 拆对之后最宽格换成姿态仪 54dp ⇒ 底板 62dp
  assertEq(108, gridSizePx(1, 6, new HudSizePx(gridPitchPx(attitude, 148, gapX).width, 148),
    new HudSizePx(gapX, gapX)).width);
  // 逆算桥函数
  assertEq('180x148', sizeString(gridPitchOf(172, 5 * 148 - 8, 1, 5, gapX, 1, 1)));
});

test('格内偏移与命中裁决共读一把尺子', () => {
  const pitchPx: HudSizePx = new HudSizePx(180, 148);
  const widths: Array<number> = [108, 56];
  assertEq('4,120', cellChildLeftsPx(new GridCell(0, 0), pitchPx, widths, 8, 352, false).join(','));
  assertEq('184,300', cellChildLeftsPx(new GridCell(1, 0), pitchPx, widths, 8, 352, false).join(','));
  assertEq(cellPlaceOffsetPx(new GridCell(2, 1), PITCH, new HudSizePx(30, 10), 1).x,
    cellChildLeftsPx(new GridCell(2, 1), PITCH, [30], 8, 142, false)[0], '单颗与居中算式逐字同值');
  assertEq(10, cellChildLeftsPx(new GridCell(0, 0), PITCH, [30], 8, 142, false)[0]);
  assertEq(0, cellChildLeftsPx(new GridCell(0, 0), PITCH, [], 8, 142, false).length);
  // 命中裁决
  const spans: Array<IntRange> = [new IntRange(4, 111), new IntRange(120, 175)];
  assertEq(0, entryHitIndex(spans, 60), '压在姿态仪上');
  assertEq(1, entryHitIndex(spans, 130), '压在音量表上');
  assertEq(1, entryHitIndex(spans, 116), '落在缝里取中心更近的');
  assertEq(0, entryHitIndex(spans, 6), '格子居中的留白里仍是靠左那颗');
  assertEq(0, entryHitIndex([new IntRange(0, 9), new IntRange(20, 29)], 14), '同距离取靠左');
  const reversed = [spans[1], spans[0]];
  assertEq(spans[entryHitIndex(spans, 130)].first, reversed[entryHitIndex(reversed, 130)].first,
    '候选列表反过来也必须指向同一颗');
  assertEq(-1, entryHitIndex([], 42));
});

test('配对格被钳制不许拆成两列_推导格仍按原序', () => {
  const t: HudLayoutTable = HudLayoutTable.decode('v2;R,-1,-1,P0:5.9,P1:5.9,P2:-,P3:-,P4:-,P5:-');
  const after: Map<string, string> = cellsOf(t, HudZone.RIGHT, PLAN_ALL);
  assertEq('1.9', after.get(e(CamPill.LEVEL).id), '两列上限 ⇒ 两颗一起钳到第 1 列');
  assertEq('1.9', after.get(e(CamPill.VOLUME).id), '同格不许被钳制破坏');
  assertEq('1.9', clampStoredCell(HudZone.RIGHT, new GridCell(5, 9)).token);
  assertEq(1, new Set<string>([
    clampCellToBox(new GridCell(5, 9), new GridBox(2, 10), 0, 30).token,
    clampCellToBox(new GridCell(5, 9), new GridBox(2, 10), 0, 30).token
  ]).size, '钳制对同一枚格子给唯一结果');
  assertEq('0.1', after.get(e(CamPill.BT).id));
  assertEq('0.2', after.get(e(CamPill.ZOOM).id));
  assertEq('0.4', after.get(e(CamPill.STAB).id));
});
