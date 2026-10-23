/**
 * HUD 固定格距 + 行跨度（J2 批，直译上游 `HudLayoutRowPitchTest.kt` 全量）。
 *
 * 缺陷本体（#74 第一版遗留）：行距取"容器内最高那颗实测 + 行距" ⇒ 右 Dock 那枚姿态仪自绘件
 * （72dp）把行距顶到 ≈78dp，5 枚格子 ≈386dp，横屏带高只有 244dp ⇒ 溢出 142dp。
 * 修法两条，本文件逐条打：
 * 1. 行距改成与住户无关的 `gridRowPitchPx`（100% 34dp、120% 38dp）；
 * 2. 高条目按 `cellRowSpan` 吃掉 `row .. row+span−1` 几档，**默认推导、占用、钳制、就近让位**四侧
 *    都按整块算。
 *
 * ## 突变自证（写死在注释里，改哪一行必须红）
 * - 把 `hudChipHeightDp` 的胶囊纵向预算 12 改成 10（胶囊档 30 → 28）⇒ `68px` 那几条全红；
 * - 把 `defaultCellsOf` 的 `row += span` 改回 `row++` ⇒ 默认行号那组红；
 * - 把 `freeCellNear` 的"整块不与占位集相交"退回"只查锚点" ⇒ 带尾那两条红。
 *
 * 本机参考值：density 2.0、横屏安全区 360dp 高、顶栏实测 44dp、底栏那排 72dp。
 */
import { CamPill, HudItem, HudItems } from '../../entry/src/main/ets/core/Constants';
import { HUD_ROW_GAP_DP } from '../../entry/src/main/ets/core/HudMetrics';
import {
  GridAnchor,
  GridBox,
  GridCell,
  GridPlacement,
  HudEntry,
  HudGridItem,
  HudGridPlan,
  HudLayoutTable,
  HudPointPx,
  HudSizePx,
  HudZone,
  blockingCells,
  cellPlaceOffsetPx,
  cellRowSpan,
  clampCellToBox,
  defaultCellsOf,
  freeCellNear,
  gridPitchPx,
  gridPlacementOf,
  gridRowPitchPx,
  hudGridGapDp,
  hudGridRowPitchPx,
  snapGridPitchPx,
  spannedCells
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

function setString(values: Set<string>): string {
  const arr: Array<string> = [];
  values.forEach((v: string): void => {
    arr.push(v);
  });
  arr.sort();
  return arr.join(',');
}

function cellToken(map: Map<string, GridCell>, entry: HudEntry): string {
  const c: GridCell | undefined = map.get(entry.id);
  return c === undefined ? '<none>' : c.token;
}

function offsetOf(cell: GridCell, pitch: HudSizePx): HudPointPx {
  return new HudPointPx((cell.col > 0 ? cell.col : 0) * pitch.width, (cell.row > 0 ? cell.row : 0) * pitch.height);
}

function offsetString(p: HudPointPx): string {
  return `${p.x},${p.y}`;
}

const DENSITY: number = 2;
const DOCK_GAP_DP: number = hudGridGapDp(HudZone.LEFT);

/** 右 Dock 实测高（px，100% 档，全部手算自既有令牌与既有自绘件尺寸） */
const RIGHT_HEIGHTS_100: Map<string, number> = new Map<string, number>();
RIGHT_HEIGHTS_100.set(e(CamPill.LEVEL).id, 144);
RIGHT_HEIGHTS_100.set(e(CamPill.VOLUME).id, 142);
RIGHT_HEIGHTS_100.set(e(CamPill.BT).id, 60);
RIGHT_HEIGHTS_100.set(e(CamPill.ZOOM).id, 60);
RIGHT_HEIGHTS_100.set(e(CamPill.FOCUS).id, 60);
RIGHT_HEIGHTS_100.set(e(CamPill.STAB).id, 60);

const RIGHT_WIDTHS: Map<string, number> = new Map<string, number>();
RIGHT_WIDTHS.set(e(CamPill.LEVEL).id, 108);
RIGHT_WIDTHS.set(e(CamPill.VOLUME).id, 56);
RIGHT_WIDTHS.set(e(CamPill.BT).id, 94);
RIGHT_WIDTHS.set(e(CamPill.ZOOM).id, 94);
RIGHT_WIDTHS.set(e(CamPill.FOCUS).id, 94);
RIGHT_WIDTHS.set(e(CamPill.STAB).id, 94);

/** 左竖 Dock 满配：两枚 38dp 圆形图标钮 = 76px（不吃字体缩放），两颗胶囊 60px */
const LEFT_HEIGHTS_100: Map<string, number> = new Map<string, number>();
LEFT_HEIGHTS_100.set(e(CamPill.REFLINE).id, 76);
LEFT_HEIGHTS_100.set(e(CamPill.MONITOR).id, 60);
LEFT_HEIGHTS_100.set(e(CamPill.CURVE).id, 60);
LEFT_HEIGHTS_100.set(e(CamPill.FLASH).id, 76);

const LEFT_WIDTHS: Map<string, number> = new Map<string, number>();
LEFT_WIDTHS.set(e(CamPill.REFLINE).id, 76);
LEFT_WIDTHS.set(e(CamPill.MONITOR).id, 136);
LEFT_WIDTHS.set(e(CamPill.CURVE).id, 139);
LEFT_WIDTHS.set(e(CamPill.FLASH).id, 76);

const READOUT_HEIGHTS_100: Map<string, number> = new Map<string, number>();
const READOUT_WIDTHS: Map<string, number> = new Map<string, number>();
for (const item of HudItems.ALL) {
  READOUT_HEIGHTS_100.set(h(item).id, 60);
  READOUT_WIDTHS.set(h(item).id, 180);
}

/** 120% 档：胶囊 21.6 + 12 = 33.6dp ≈ 68px；姿态仪 ≈150px；纯 dp 自绘件仍是 142 / 76px */
const RIGHT_HEIGHTS_120: Map<string, number> = new Map<string, number>();
RIGHT_HEIGHTS_120.set(e(CamPill.LEVEL).id, 150);
RIGHT_HEIGHTS_120.set(e(CamPill.VOLUME).id, 142);
RIGHT_HEIGHTS_120.set(e(CamPill.BT).id, 68);
RIGHT_HEIGHTS_120.set(e(CamPill.ZOOM).id, 68);
RIGHT_HEIGHTS_120.set(e(CamPill.FOCUS).id, 68);
RIGHT_HEIGHTS_120.set(e(CamPill.STAB).id, 68);

const LEFT_HEIGHTS_120: Map<string, number> = new Map<string, number>();
LEFT_HEIGHTS_120.set(e(CamPill.REFLINE).id, 76);
LEFT_HEIGHTS_120.set(e(CamPill.MONITOR).id, 68);
LEFT_HEIGHTS_120.set(e(CamPill.CURVE).id, 68);
LEFT_HEIGHTS_120.set(e(CamPill.FLASH).id, 76);

const READOUT_HEIGHTS_120: Map<string, number> = new Map<string, number>();
for (const item of HudItems.ALL) {
  READOUT_HEIGHTS_120.set(h(item).id, 68);
}

function withOverrides(base: Map<string, number>, overrides: Map<string, number>): Map<string, number> {
  const out: Map<string, number> = new Map<string, number>();
  base.forEach((v: number, k: string): void => {
    out.set(k, v);
  });
  overrides.forEach((v: number, k: string): void => {
    out.set(k, v);
  });
  return out;
}

/** 一帧这一枚容器的解析输入：格距按字体缩放现算，实测高喂手算那张表 */
function planOf(fontScale: number, heights: Map<string, number>, perRow: number = 3): HudGridPlan {
  const rowPitch: number = gridRowPitchPx(fontScale, DOCK_GAP_DP, DENSITY);
  const readoutPitch: number = gridRowPitchPx(fontScale, HUD_ROW_GAP_DP, DENSITY);
  return new HudGridPlan(
    new Set<string>(HudEntry.ALL.map((x: HudEntry): string => x.id)),
    perRow,
    (zone: HudZone): number => zone === HudZone.READOUT ? readoutPitch : rowPitch,
    (entry: HudEntry): number => heights.get(entry.id) === undefined ? 0 : heights.get(entry.id) as number
  );
}

function heightOf(entry: HudEntry, heights: Map<string, number>): number {
  const v: number | undefined = heights.get(entry.id);
  return v === undefined ? 0 : v;
}

function widthOf(entry: HudEntry, widths: Map<string, number>): number {
  const v: number | undefined = widths.get(entry.id);
  return v === undefined ? 0 : v;
}

/** 桥函数：默认表 → 格子清单 → 渲染层那条纯算式 → 整份测量结果 */
function gridPlaced(
  zone: HudZone, fontScale: number, heights: Map<string, number>, widths: Map<string, number>, perRow: number = 3
): GridPlacement {
  const plan: HudGridPlan = planOf(fontScale, heights, perRow);
  const items: Array<HudGridItem> = HudLayoutTable.default().gridItems(zone, plan);
  const gapPx: number = hudGridGapDp(zone) * DENSITY;
  return gridPlacementOf(items,
    items.map((it: HudGridItem): HudSizePx => new HudSizePx(widthOf(it.entry, widths), heightOf(it.entry, heights))),
    new HudSizePx(gapPx, gapPx),
    plan.rowPitchOf(zone),
    new GridAnchor(zone, HudLayoutTable.default().defaultGridOf(zone, plan)));
}

function gridSize(
  zone: HudZone, fontScale: number, heights: Map<string, number>, widths: Map<string, number>, perRow: number = 3
): HudSizePx {
  return gridPlaced(zone, fontScale, heights, widths, perRow).size;
}

function assertFits(label: string, gridHeightPx: number, bandPx: number, gapPx: number): void {
  const cardPx: number = gridHeightPx + gapPx * 2;
  const overflowPx: number = cardPx - bandPx;
  assertEq(0, Math.max(0, overflowPx),
    `${label}：整枚底板 ${cardPx}px 装不进带高 ${bandPx}px ⇒ 溢出 ${overflowPx}px`);
}

// ---------- 一、格距：与住户无关，从令牌推 ----------

test('行距由胶囊档推出_与格子里住了谁无关', () => {
  // 手算三档（density 2.0）：竖 Dock 100% 68px、120% 76px、读数块 100% 72px
  assertEq(68, gridRowPitchPx(1, DOCK_GAP_DP, DENSITY));
  assertEq(76, gridRowPitchPx(1.2, DOCK_GAP_DP, DENSITY));
  assertEq(72, gridRowPitchPx(1, HUD_ROW_GAP_DP, DENSITY), '读数块那一档必须与 #74 实测行高同值');
  assertEq(68, gridRowPitchPx(0.8, DOCK_GAP_DP, DENSITY), '系统字号低于 100% 被夹回 1f');
  const withTall: GridPlacement = gridPlaced(HudZone.RIGHT, 1, RIGHT_HEIGHTS_100, RIGHT_WIDTHS);
  const tallOverrides: Map<string, number> = new Map<string, number>();
  tallOverrides.set(e(CamPill.LEVEL).id, 60);
  tallOverrides.set(e(CamPill.VOLUME).id, 60);
  const withoutTall: GridPlacement = gridPlaced(HudZone.RIGHT, 1,
    withOverrides(RIGHT_HEIGHTS_100, tallOverrides), RIGHT_WIDTHS);
  assertEq('180x68', `${withTall.pitch.width}x${withTall.pitch.height}`,
    '格距 = 胶囊档 + 一道行距，与格子里住了谁无关');
  assertEq('180x68', `${withoutTall.pitch.width}x${withoutTall.pitch.height}`, '换成两颗胶囊之后格长仍是同一个');
  assertEq(468, withTall.size.height, '配对块 3 档 + 四颗各 1 档 = 7 档 ⇒ 468px');
  assertEq(332, withoutTall.size.height, '人人一档 = 5 档 ⇒ 332px');
  assertEq(172, withTall.size.width, '宽度账不动');
  assertEq(172, withoutTall.size.width);
  // 三处消费方共读的那一条出口（渲染层 / 编辑页 / 位置表都经 hudGridRowPitchPx）
  assertEq(68, hudGridRowPitchPx(HudZone.LEFT, 1, 2));
  assertEq(68, hudGridRowPitchPx(HudZone.RIGHT, 1, 2));
  assertEq(72, hudGridRowPitchPx(HudZone.READOUT, 1, 2));
  assertEq(76, hudGridRowPitchPx(HudZone.RIGHT, 1.2, 2));
  // 双算防护：格距**已经含**那道行距，gridPitchPx 的纵向是透传
  assertEq(68, gridPitchPx(172, 68, 8).height);
  assertEq(180, gridPitchPx(172, 68, 8).width);
});

test('跨度向上取整_刚好等于一档不许变两档', () => {
  assertEq(1, cellRowSpan(60, 68));
  assertEq(1, cellRowSpan(68, 68));
  assertEq(2, cellRowSpan(69, 68));
  assertEq(2, cellRowSpan(76, 68));
  assertEq(3, cellRowSpan(144, 68));
  assertEq(3, cellRowSpan(142, 68));
  assertEq(2, cellRowSpan(150, 76));
  assertEq(2, cellRowSpan(152, 76));
  assertEq(3, cellRowSpan(153, 76));
  assertEq(1, cellRowSpan(0, 68));
  assertEq(1, cellRowSpan(-10, 68));
  assertEq(1, cellRowSpan(144, 0));
  assertEq(144, (46 + 5 + 5 + 2 + 14) * 2, '100% 那枚姿态仪 = 72dp');
});

// ---------- 二、跨度进推导：默认行号按整块推进 ----------

test('默认行按整块推进_不是加一', () => {
  const plan: HudGridPlan = planOf(1,
    withOverrides(withOverrides(RIGHT_HEIGHTS_100, LEFT_HEIGHTS_100), READOUT_HEIGHTS_100));
  const right: Map<string, GridCell> = defaultCellsOf(HudZone.RIGHT, HudLayoutTable.defaultOrderOf(HudZone.RIGHT), plan);
  assertEq('0.0', cellToken(right, e(CamPill.LEVEL)));
  assertEq('0.0', cellToken(right, e(CamPill.VOLUME)), '两颗同格 ⇒ 只有一块');
  assertEq('0.3', cellToken(right, e(CamPill.BT)));
  assertEq('0.4', cellToken(right, e(CamPill.ZOOM)));
  assertEq('0.5', cellToken(right, e(CamPill.FOCUS)));
  assertEq('0.6', cellToken(right, e(CamPill.STAB)));
  let onlyColZero: boolean = true;
  right.forEach((cell: GridCell): void => {
    if (cell.col !== 0) {
      onlyColZero = false;
    }
  });
  assertTrue(onlyColZero, '仍然只有一列');
  const left: Map<string, GridCell> = defaultCellsOf(HudZone.LEFT, HudLayoutTable.defaultOrderOf(HudZone.LEFT), plan);
  assertEq('0.0', cellToken(left, e(CamPill.REFLINE)));
  assertEq('0.2', cellToken(left, e(CamPill.MONITOR)));
  assertEq('0.3', cellToken(left, e(CamPill.CURVE)));
  assertEq('0.4', cellToken(left, e(CamPill.FLASH)));
  const readout: Map<string, GridCell> =
    defaultCellsOf(HudZone.READOUT, HudLayoutTable.defaultOrderOf(HudZone.READOUT), plan);
  assertEq('0.0', cellToken(readout, h(HudItem.SHUTTER)));
  assertEq('2.0', cellToken(readout, h(HudItem.BITRATE)));
  assertEq('0.1', cellToken(readout, h(HudItem.ISO)));
  assertEq('2.1', cellToken(readout, h(HudItem.WB)));
  assertEq('0.2', cellToken(readout, h(HudItem.ZOOM)));
  assertEq(6, right.size);
  assertEq(4, left.size);
});

test('高格占满整个块_搭档只许落回起始档', () => {
  const rowPitch: number = 68;
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set('0.0', [e(CamPill.LEVEL), e(CamPill.VOLUME)]);
  residents.set('0.3', [e(CamPill.BT)]);
  const heightOfFn = (entry: HudEntry): number => heightOf(entry, RIGHT_HEIGHTS_100);
  const derived: Map<string, GridCell> =
    defaultCellsOf(HudZone.RIGHT, HudLayoutTable.defaultOrderOf(HudZone.RIGHT), planOf(1, RIGHT_HEIGHTS_100));
  // ① 跨组那颗（蓝牙）眼里：配对块三档 + 别的跨组住户 (0,5) 算占；(0,3) 是它自己那一档，不算
  const withZoom: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.forEach((who: Array<HudEntry>, token: string): void => {
    withZoom.set(token, who);
  });
  withZoom.set('0.5', [e(CamPill.ZOOM)]);
  assertEq('0.0,0.1,0.2,0.5',
    setString(blockingCells(withZoom, e(CamPill.BT), new Set<string>(), heightOfFn, rowPitch)));
  // ② 搭档（音量表）眼里：起始档 (0,0) 仍空，但 (0,1)(0,2) 算占
  assertEq('0.1,0.2,0.3',
    setString(blockingCells(residents, e(CamPill.VOLUME), new Set<string>([e(CamPill.LEVEL).id]),
      heightOfFn, rowPitch)));
  // ③ self = null 的旧口径：有人就算占 ⇒ 整块 + 蓝牙
  assertEq('0.0,0.1,0.2,0.3',
    setString(blockingCells(residents, null, new Set<string>(), heightOfFn, rowPitch)));
  // ④ 跨度不改变格子身份：同组两颗的推导格仍然相同
  assertEq(cellToken(derived, e(CamPill.LEVEL)), cellToken(derived, e(CamPill.VOLUME)));
  // ⑤ 展开算式本身
  assertEq('1.4,1.5', spannedCells(new GridCell(1, 4), 2).map((c: GridCell): string => c.token).join(','));
  assertEq(0, spannedCells(GridCell.DEFAULT, 3).length);
  // ⑥ occupiedCells 给出的必须是展开后的档
  const occupied: Set<string> = HudLayoutTable.default().occupiedCells(HudZone.RIGHT, planOf(1, RIGHT_HEIGHTS_100));
  assertTrue(occupied.has('0.1') && occupied.has('0.2'), `occupiedCells 没把高格第 2、3 档算进去（${setString(occupied)}）`);
  // ⑦ 生产路径的 exclude 支：不含蓝牙自己的 (0,3)
  assertEq('0.0,0.1,0.2,0.4,0.5,0.6',
    setString(HudLayoutTable.default().occupiedCells(HudZone.RIGHT, planOf(1, RIGHT_HEIGHTS_100), e(CamPill.BT))));
  assertTrue(occupied.has('0.3'), 'exclude = null 时 (0,3) 必须算占');
});

// ---------- 三、独立性不许因为跨度而退化 ----------

test('两个文字缩放旋钮一起撞带高_最坏角落超带18dp是已知极限', () => {
  const bandDp: number = 360 - 44 - 72;
  const names: Array<string> = ['app100 sys100', 'app120 sys100', 'app100 sys120', 'app120 sys120'];
  const appScales: Array<number> = [1, 1.2, 1, 1.2];
  const sysScales: Array<number> = [1, 1, 1.2, 1.2];
  const overflow: Map<string, number> = new Map<string, number>();
  for (let i = 0; i < names.length; i++) {
    const appScale: number = appScales[i];
    const systemScale: number = sysScales[i];
    // 格距向上取整到整 dp（生产口径：34dp / 38dp，不是 37.6）
    const pitchDp: number = Math.ceil(18 * systemScale + 16);
    const blockDp: number = 58 + 14 * appScale * systemScale;
    const span: number = Math.max(1, Math.ceil(blockDp / pitchDp));
    const rows: number = span + 4;
    const gridDp: number = rows * pitchDp - 4;
    overflow.set(names[i], gridDp - bandDp);
    const blockHeights: Map<string, number> = new Map<string, number>();
    blockHeights.set(e(CamPill.LEVEL).id, Math.trunc(blockDp * DENSITY));
    blockHeights.set(e(CamPill.VOLUME).id, Math.trunc(blockDp * DENSITY));
    const heights: Map<string, number> = withOverrides(RIGHT_HEIGHTS_100, blockHeights);
    const placed: GridPlacement = gridPlaced(HudZone.RIGHT, systemScale, heights, RIGHT_WIDTHS);
    assertEq(Math.trunc(pitchDp * DENSITY), placed.pitch.height,
      `${names[i]}：格距必须由系统档推出，不许被应用档带着走`);
    assertEq(Math.trunc(gridDp * DENSITY), placed.size.height,
      `${names[i]}：网格总高对撞带高`);
  }
  near(0, Math.max(0, overflow.get('app120 sys100') as number), 0.01, 'app120 sys100 仍装得进');
  near(0, Math.max(0, overflow.get('app100 sys120') as number), 0.01, 'app100 sys120 最松');
  near(18, overflow.get('app120 sys120') as number, 0.5,
    '#76 已知极限：两个旋钮同时 120% 时超带 18dp');
});

test('挪走高条目只动它_其余格子与坐标一字未变', () => {
  const rowPitch: number = gridRowPitchPx(1, DOCK_GAP_DP, DENSITY);
  const plan: HudGridPlan = planOf(1, RIGHT_HEIGHTS_100);
  const hand: HudSizePx = new HudSizePx(180, rowPitch);
  const before: Map<string, string> = new Map<string, string>();
  for (const it of HudLayoutTable.default().gridItems(HudZone.RIGHT, plan)) {
    before.set(it.entry.id, it.cell.token);
  }
  const moved: HudLayoutTable =
    HudLayoutTable.default().placeEntryAt(e(CamPill.ZOOM), HudZone.RIGHT, 2, new GridCell(0, 7), plan);
  const after: Map<string, string> = new Map<string, string>();
  for (const it of moved.gridItems(HudZone.RIGHT, plan)) {
    after.set(it.entry.id, it.cell.token);
  }
  assertEq('0.7', after.get(e(CamPill.ZOOM).id), '被拖那颗拿到第 7 档');
  assertEq('0,0', offsetString(offsetOf(new GridCell(0, 0), hand)));
  assertEq('0,0', offsetString(offsetOf(new GridCell(0, 0), hand)));
  assertEq('0.3', after.get(e(CamPill.BT).id), '蓝牙还在第 3 档');
  assertEq('0,204', offsetString(offsetOf(new GridCell(0, 3), hand)));
  assertEq('0.5', after.get(e(CamPill.FOCUS).id));
  assertEq('0,340', offsetString(offsetOf(new GridCell(0, 5), hand)));
  assertEq('0.6', after.get(e(CamPill.STAB).id));
  assertEq('0,408', offsetString(offsetOf(new GridCell(0, 6), hand)));
  assertEq('0,476', offsetString(offsetOf(new GridCell(0, 7), hand)));
  // 差分断言
  before.delete(e(CamPill.ZOOM).id);
  after.delete(e(CamPill.ZOOM).id);
  const beforeStr: Array<string> = [];
  const afterStr: Array<string> = [];
  before.forEach((v: string, k: string): void => {
    beforeStr.push(`${k}=${v}`);
  });
  after.forEach((v: string, k: string): void => {
    afterStr.push(`${k}=${v}`);
  });
  assertEq(beforeStr.sort().join(';'), afterStr.sort().join(';'));
  // 高格挪走之后各块跨度照常：配对块仍吃 3 档，格网 = 8 档 × 68 − 8 = 536px
  const list: Array<HudGridItem> = moved.gridItems(HudZone.RIGHT, plan);
  const placed: GridPlacement = gridPlacementOf(list,
    list.map((it: HudGridItem): HudSizePx =>
      new HudSizePx(widthOf(it.entry, RIGHT_WIDTHS), heightOf(it.entry, RIGHT_HEIGHTS_100))),
    new HudSizePx(8, 8), rowPitch,
    new GridAnchor(HudZone.RIGHT, HudLayoutTable.default().defaultGridOf(HudZone.RIGHT, plan)));
  assertEq(536, placed.size.height);
  assertEq(172, placed.size.width, '宽度账与挪动无关');
});

test('高格参与交换_第三颗一格不动', () => {
  const plan: HudGridPlan = planOf(1, RIGHT_HEIGHTS_100);
  const moved: HudLayoutTable =
    HudLayoutTable.default().placeEntryAt(e(CamPill.STAB), HudZone.RIGHT, 5, new GridCell(0, 0), plan);
  const after: Map<string, string> = new Map<string, string>();
  for (const it of moved.gridItems(HudZone.RIGHT, plan)) {
    after.set(it.entry.id, it.cell.token);
  }
  assertEq('0.0', after.get(e(CamPill.STAB).id), '被拖那颗拿到配对高格');
  assertEq('0.6', after.get(e(CamPill.LEVEL).id), '配对整组让到防抖原格');
  assertEq('0.6', after.get(e(CamPill.VOLUME).id));
  assertEq('0.3', after.get(e(CamPill.BT).id));
  assertEq('0.4', after.get(e(CamPill.ZOOM).id));
  assertEq('0.5', after.get(e(CamPill.FOCUS).id));
  assertEq(after.get(e(CamPill.LEVEL).id), after.get(e(CamPill.VOLUME).id), '配对仍是共格');
});

// ---------- 四、钳制与就近让位吃的是整块 ----------

test('钳制与就近让位把整块关在带内', () => {
  const box: GridBox = new GridBox(2, 7);
  assertEq('0.4', clampCellToBox(new GridCell(0, 99), box, 144, 68).token, '跨度 3 ⇒ 起始档最多 4');
  assertEq('1.4', clampCellToBox(new GridCell(7, 99), box, 144, 68).token);
  assertEq('0.6', clampCellToBox(new GridCell(0, 99), box, 0, 68).token, '跨度 1 时钳到最后一档');
  assertTrue(clampCellToBox(GridCell.DEFAULT, box, 144, 68).isDefault);
  assertEq('0.0', clampCellToBox(new GridCell(0, 5), new GridBox(2, 2), 144, 68).token,
    '带比块还矮时夹成第 0 档');
  // 就近让位要拒"块尾出带"的候选：跨度 3 ⇒ 4−3 = 1 ⇒ 只能退到 (0,1)
  assertEq('0.1', freeCellNear(new GridCell(0, 3), new Set<string>(['0.0']), 1, 4, 144, 68).token);
  // 成对对照：跨度 1 ⇒ 就地落 (0,3)
  assertEq('0.3', freeCellNear(new GridCell(0, 3), new Set<string>(['0.0']), 1, 4, 0, 68).token);
});

test('格内居中基准是整块而不是一档', () => {
  const pitch: HudSizePx = new HudSizePx(180, 68);
  assertEq('60,4', offsetString(cellPlaceOffsetPx(new GridCell(0, 0), pitch, new HudSizePx(60, 60), 1)));
  assertEq('0,30', offsetString(cellPlaceOffsetPx(new GridCell(0, 0), pitch, new HudSizePx(180, 144), 3)));
  assertEq('60,302', offsetString(cellPlaceOffsetPx(new GridCell(0, 4), pitch, new HudSizePx(60, 144), 3)));
});

// ---------- 五、编辑页的吸附不许再把网格高除回来当行距 ----------

test('编辑页吸附的纵向直接用格距_不除网格高', () => {
  const a: HudSizePx = snapGridPitchPx(172, 1, 8, 90, 68);
  assertEq('180x68', `${a.width}x${a.height}`, '纵向必须等于喂进来的格距');
  const b: HudSizePx = snapGridPitchPx(340, 2, 8, 1, 68);
  assertEq('174x68', `${b.width}x${b.height}`);
  const c: HudSizePx = snapGridPitchPx(0, 0, 8, 90, 68);
  assertEq('98x68', `${c.width}x${c.height}`, '空容器退回被拖那颗宽 + 行距');
});

// ---------- 六、网格高对撞带高（横屏满配、两档字体各一份） ----------

test('横屏带高对撞两档字体都零溢出_逐颗底边露出', () => {
  const dockBandPx: number = (360 - 44 - 72) * 2;
  const readoutBandPx: number = (360 - 44) * 2;
  // 100%：右 Dock 468、左 Dock 400、读数块 276
  assertEq(468, gridSize(HudZone.RIGHT, 1, RIGHT_HEIGHTS_100, RIGHT_WIDTHS).height);
  assertEq(400, gridSize(HudZone.LEFT, 1, LEFT_HEIGHTS_100, LEFT_WIDTHS).height);
  assertEq(276, gridSize(HudZone.READOUT, 1, READOUT_HEIGHTS_100, READOUT_WIDTHS, 2).height);
  assertFits('右 Dock 100%', 468, dockBandPx, 8);
  assertFits('左 Dock 100%', 400, dockBandPx, 8);
  assertFits('读数块 100%', 276, readoutBandPx, 12);
  // 120%：右 Dock 448、左 Dock 296、读数块 308
  assertEq(448, gridSize(HudZone.RIGHT, 1.2, RIGHT_HEIGHTS_120, RIGHT_WIDTHS).height);
  assertEq(296, gridSize(HudZone.LEFT, 1.2, LEFT_HEIGHTS_120, LEFT_WIDTHS).height);
  assertEq(308, gridSize(HudZone.READOUT, 1.2, READOUT_HEIGHTS_120, READOUT_WIDTHS, 2).height);
  assertFits('右 Dock 120%', 448, dockBandPx, 8);
  assertFits('左 Dock 120%', 296, dockBandPx, 8);
  assertFits('读数块 120%', 308, readoutBandPx, 12);
  // 逐颗点名底边（100%）
  const plan100: HudGridPlan = planOf(1, RIGHT_HEIGHTS_100);
  const items: Array<HudGridItem> = HudLayoutTable.default().gridItems(HudZone.RIGHT, plan100);
  const placed: GridPlacement = gridPlacementOf(items,
    items.map((it: HudGridItem): HudSizePx =>
      new HudSizePx(widthOf(it.entry, RIGHT_WIDTHS), heightOf(it.entry, RIGHT_HEIGHTS_100))),
    new HudSizePx(8, 8), plan100.rowPitchOf(HudZone.RIGHT),
    new GridAnchor(HudZone.RIGHT, HudLayoutTable.default().defaultGridOf(HudZone.RIGHT, plan100)));
  const bottoms: Map<string, number> = new Map<string, number>();
  for (let i = 0; i < items.length; i++) {
    bottoms.set(items[i].entry.id,
      placed.offsets[i].y + heightOf(items[i].entry, RIGHT_HEIGHTS_100));
  }
  assertEq(404, bottoms.get(e(CamPill.FOCUS).id), '对焦 5×68 + 4 = 344 ⇒ 底边 404');
  assertEq(472, bottoms.get(e(CamPill.STAB).id), '防抖 6×68 + 4 = 412 ⇒ 底边 472');
  let visible: number = 0;
  bottoms.forEach((bottom: number): void => {
    if (bottom + 8 <= dockBandPx) {
      visible++;
    }
  });
  assertEq(6, visible, '100% 满配横屏六颗全露');
  // 120%
  const plan120: HudGridPlan = planOf(1.2, RIGHT_HEIGHTS_120);
  const items120: Array<HudGridItem> = HudLayoutTable.default().gridItems(HudZone.RIGHT, plan120);
  const placed120: GridPlacement = gridPlacementOf(items120,
    items120.map((it: HudGridItem): HudSizePx =>
      new HudSizePx(widthOf(it.entry, RIGHT_WIDTHS), heightOf(it.entry, RIGHT_HEIGHTS_120))),
    new HudSizePx(8, 8), plan120.rowPitchOf(HudZone.RIGHT),
    new GridAnchor(HudZone.RIGHT, HudLayoutTable.default().defaultGridOf(HudZone.RIGHT, plan120)));
  let visible120: number = 0;
  for (let i = 0; i < items120.length; i++) {
    const bottom: number = placed120.offsets[i].y + heightOf(items120[i].entry, RIGHT_HEIGHTS_120);
    if (bottom + 8 <= dockBandPx) {
      visible120++;
    }
  }
  assertEq(6, visible120, '120% 满配横屏六颗全露');
});
