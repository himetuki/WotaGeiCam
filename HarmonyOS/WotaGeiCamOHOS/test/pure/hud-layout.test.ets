/**
 * HUD 位置模型：越界钳制 + 持久化契约（直译上游 `HudLayoutClampTest` + `HudLayoutCodecTest` 全量）。
 *
 * J1 交付了类型/编解码/夹取/落区；J2 在同文件补齐**底栏两档吸附、重叠提示、最内层命中**，以及
 * CodecTest 里依赖 `gridItems`/`placeEntryAt`/`defaultCellsOf` 的那些臂（v1 迁移逐颗落点、
 * 配对同格往返、隐藏态格位保留、坏格子后缀回推导格）。
 *
 * ## 数值档与上游同源
 * 横屏安全区 766×360（挖孔让掉一条短边之后的 1532px/2）、竖屏 360×800；顶栏 44dp、底栏 72dp。
 * 钳制本身是真断言：把安全区宽当根容器宽、把 topAvoid 当 0、区间算成负数、底栏落位写进绝对 x
 * 这四类退化都会当场红。
 *
 * ## 持久化契约是**逐字节**的
 * `hud_layout` 串的输出必须与上游一致：这里用**手写字面量**（不是由实现反推）钉住默认串与一条
 * 编辑过的串；把分号改逗号、把 `:格子` 后缀删掉、把哨兵 `-` 改 `-1`，那几条立刻红。
 *
 * ## ArkTS 口径
 * 集合/Map 键一律是持久化里已有的字符串：容器 key（`'T'`…）、条目 id（`'P3'`/`'H6'`）、
 * 格子 token（`'0.2'`/`'-'`）。表相等用 `encode()` 全串对撞（它含版本与五段，足够判等）。
 */
import { CamPill, HudItem, HudItems } from '../../entry/src/main/ets/core/Constants';
import {
  GridCell, HudAreaDp, HudDockStatus, HudEntry, HudGridItem, HudGridPlan, HudLayoutTable, HudPointDp, HudRectDp, HudZone,
  HudZonePair, IntRange, ZonePlacement, chromeBandBottomDp, clampStoredCell, clampZonePos, clampZoneX,
  clampZoneYRange, dockDragBlocked, dockLowerY, dockSnapY, dockUpperY, dropZoneOf, editorVisibleEntries,
  gridColsCapOf, gridRowHardCap, hudZoneAll, hudZoneAxis, hudZoneFromKey, hudZoneIsGrid, overlappingZones,
  pxToDp, zoneAt, zoneBandYRange
} from '../../entry/src/main/ets/core/HudLayout';

/** 横屏：safeDrawing 后实测 766×360，顶栏 44、底栏 72 */
const LAND: HudAreaDp = new HudAreaDp(766, 360, 44, 72);
/** 竖屏同一台机：360×800，侧边不让位 */
const PORT: HudAreaDp = new HudAreaDp(360, 800, 44, 72);

function e(pill: CamPill): HudEntry {
  return HudEntry.ofPill(pill);
}

function h(item: HudItem): HudEntry {
  return HudEntry.ofItem(item);
}

function sameTable(a: HudLayoutTable, b: HudLayoutTable): boolean {
  return a.encode() === b.encode();
}

function ids(entries: Array<HudEntry>): string {
  return entries.map((x: HudEntry): string => x.id).join(',');
}

function entryIdOf(raw: string): string {
  const entry: HudEntry | undefined = HudEntry.fromId(raw);
  return entry === undefined ? '<bad>' : entry.id;
}

function tokenOf(cells: Map<string, GridCell>, entry: HudEntry): string {
  const cell: GridCell | undefined = cells.get(entry.id);
  return cell === undefined ? '<none>' : cell.token;
}

/** J2 的格子解析输入：全部可见、读数块一行 3 颗、格距手摆 30px、实测高一律 0（跨度恒 1） */
const PLAN_ALL: HudGridPlan = new HudGridPlan(
  new Set<string>(HudEntry.ALL.map((x: HudEntry): string => x.id)),
  3,
  (_zone: HudZone): number => 30,
  (_entry: HudEntry): number => 0
);

function cellTokenAt(table: HudLayoutTable, zone: HudZone, entry: HudEntry): string {
  for (const it of table.gridItems(zone, PLAN_ALL)) {
    if (it.entry.id === entry.id) {
      return it.cell.token;
    }
  }
  return '<none>';
}

// ================================================================== 一、越界钳制（上游 HudLayoutClampTest）

test('右缘就是安全区右缘_不按方向再扣一笔', () => {
  // 横屏 766 就是可视右缘（挖孔那条边已由外层安全区让掉）；竖屏 360 同理
  assertEq(672, clampZoneX(LAND, 94));
  assertEq(266, clampZoneX(PORT, 94));
  // 容器比可视区还宽（120% 文本 + 极窄分屏）：贴左放，**不许为负**
  assertEq(0, clampZoneX(LAND, 900));
  // 零宽（条目全被关掉、底板空了）时上限就是可视右缘本身，不是 0
  assertEq(766, clampZoneX(LAND, 0));
});

test('底栏落位永远不写绝对x_纵向照常钳', () => {
  // B4 审查 S1：底栏的横向落位**不许进表**，给它一个再像真值不过的绝对 x 也必须是哨兵
  const inRow: ZonePlacement = clampZonePos(HudZone.BOTTOM, 500, 250, 216, 72, LAND, 0);
  assertEq(-1, inRow.xDp);
  assertEq(250, inRow.yDp);
  // 纵向照常钳（下界 = 安全区高 − 排高 = 288），只有 x 被抹
  assertEq(-1, clampZonePos(HudZone.BOTTOM, 0, 9999, 216, 72, LAND, 0).xDp);
  assertEq(288, clampZonePos(HudZone.BOTTOM, 0, 9999, 216, 72, LAND, 0).yDp);
  // 负 y 夹回 0
  assertEq(0, clampZonePos(HudZone.BOTTOM, -1, -50, 216, 72, LAND, 0).yDp);
  // 反面对照：另外四枚容器的 x 照旧钳进安全区，这条闸只作用底栏
  assertEq(466, clampZonePos(HudZone.READOUT, 9999, 300, 300, 42, LAND, 0).xDp);
  assertEq(246, clampZonePos(HudZone.READOUT, 9999, 300, 300, 42, LAND, 0).yDp);
  assertEq(672, clampZonePos(HudZone.RIGHT, 9999, 100, 94, 202, LAND, 0).xDp);
  assertEq(86, clampZonePos(HudZone.RIGHT, 9999, 100, 94, 202, LAND, 0).yDp);
});

test('三枚带容器同时吃顶栏与底栏两条实测避让量', () => {
  // 左/右/读数块：y 下界是顶栏实测高，上界是安全区高 − 底栏实测高 − 自身高
  assertEq('44..86', `${clampZoneYRange(HudZone.RIGHT, LAND, 202, 0).first}..${clampZoneYRange(HudZone.RIGHT, LAND, 202, 0).last}`);
  assertEq('44..278', `${clampZoneYRange(HudZone.LEFT, LAND, 10, 0).first}..${clampZoneYRange(HudZone.LEFT, LAND, 10, 0).last}`);
  assertEq('44..686', `${clampZoneYRange(HudZone.READOUT, PORT, 42, 0).first}..${clampZoneYRange(HudZone.READOUT, PORT, 42, 0).last}`);
  // 顶栏与底栏两枚贴的是屏幕边，不受这两条避让量约束（TOP 的 lo 由第四形参说了算）
  assertEq('0..316', `${clampZoneYRange(HudZone.TOP, LAND, 44, 0).first}..${clampZoneYRange(HudZone.TOP, LAND, 44, 0).last}`);
  assertEq('0..288', `${clampZoneYRange(HudZone.BOTTOM, LAND, 72, 0).first}..${clampZoneYRange(HudZone.BOTTOM, LAND, 72, 0).last}`);
});

test('带被挤没时退成整条安全区_区间永不为负', () => {
  // 告警条 + 满配读数把带挤没了：区间若为负，coerceIn 会抛，整个 HUD 就没了
  const squeezed: HudAreaDp = new HudAreaDp(766, 200, 120, 100);
  const range: IntRange = clampZoneYRange(HudZone.RIGHT, squeezed, 90, 0);
  assertTrue(range.first <= range.last, '区间必须非负');
  assertEq(0, range.first);
  assertEq(110, range.last); // 200 − 90
  // 落点仍然取得到，并且落在回退区间里
  assertEq(110, clampZonePos(HudZone.RIGHT, 700, 9999, 94, 90, squeezed, 0).yDp);
  assertEq(672, clampZonePos(HudZone.RIGHT, 700, 9999, 94, 90, squeezed, 0).xDp);
});

test('绝对位置两轴都钳_且幂等', () => {
  // 右竖 Dock（实测 94×202dp）拖出可视右缘与上下界
  const far: ZonePlacement = clampZonePos(HudZone.RIGHT, 9999, 9999, 94, 202, LAND, 0);
  assertEq(672, far.xDp);
  assertEq(86, far.yDp);
  const negative: ZonePlacement = clampZonePos(HudZone.RIGHT, -50, 10, 94, 202, LAND, 0);
  assertEq(0, negative.xDp);
  assertEq(44, negative.yDp);
  // 顶栏贴顶：允许 y=0，但下界是「安全区底 − 自身高」
  assertEq(0, clampZonePos(HudZone.TOP, 0, 0, 300, 44, LAND, 0).yDp);
  assertEq(316, clampZonePos(HudZone.TOP, 10, 9999, 300, 44, LAND, 0).yDp);
  // 钳过的值再钳一次不变（编辑页连续拖动靠这条才不会漂）
  const again: ZonePlacement = clampZonePos(HudZone.RIGHT, far.xDp, far.yDp, 94, 202, LAND, 0);
  assertTrue(far.equals(again), '钳制必须幂等');
});

test('顶栏绝对定位支吃操作栏实测下缘_非TOP不读它', () => {
  // 操作栏下缘换算成 56dp（与 chromeBandBottomDp(180,68,2) 同源）⇒ 顶栏 y=0 被推到 56
  assertEq(56, clampZonePos(HudZone.TOP, 0, 0, 300, 44, LAND, 56).yDp);
  // 已经在 56 之下（y=100）不动；上界仍是「安全区底 − 自身高」= 316
  assertEq(100, clampZonePos(HudZone.TOP, 0, 100, 300, 44, LAND, 56).yDp);
  assertEq(316, clampZonePos(HudZone.TOP, 0, 9999, 300, 44, LAND, 56).yDp);
  // 传播到区间那一层（两者必须同一条带）
  assertEq(56, clampZoneYRange(HudZone.TOP, LAND, 44, 56).first);
  assertEq(316, clampZoneYRange(HudZone.TOP, LAND, 44, 56).last);
  // 0 = 录制页/改前那一档：贴顶仍是 0
  assertEq(0, clampZonePos(HudZone.TOP, 0, 0, 300, 44, LAND, 0).yDp);
  // 非 TOP 容器**不读**这条下限（把它喂给 LEFT 也不该改变 44 的下界）
  assertEq(44, clampZonePos(HudZone.LEFT, 0, 0, 94, 10, LAND, 56).yDp);
});

test('可放带区间是带本身_与左上角区间同一批入参', () => {
  // RIGHT：44..(360−72)=288（**不减容器高**——这条判的是带，不是左上角）
  assertEq(44, zoneBandYRange(HudZone.RIGHT, LAND, 0).first);
  assertEq(288, zoneBandYRange(HudZone.RIGHT, LAND, 0).last);
  // TOP：默认 0..360；操作栏那档把 lo 抬到 56（与 clampZoneYRange 同一个值）
  assertEq(0, zoneBandYRange(HudZone.TOP, LAND, 0).first);
  assertEq(360, zoneBandYRange(HudZone.TOP, LAND, 0).last);
  assertEq(56, zoneBandYRange(HudZone.TOP, LAND, 56).first);
  // BOTTOM：0..360
  assertEq(360, zoneBandYRange(HudZone.BOTTOM, LAND, 0).last);
  // 空带（告警条把带挤没）退成 lo..lo 而不是 lo..更小的数（区间永不反转）
  const empty: HudAreaDp = new HudAreaDp(766, 100, 120, 0);
  const degenerate: IntRange = zoneBandYRange(HudZone.RIGHT, empty, 0);
  assertEq(120, degenerate.first);
  assertEq(120, degenerate.last);
});

test('操作栏下缘读实测窗口坐标_量不到给0', () => {
  // 手算：(180 − 68) / 2 = 56dp
  assertEq(56, chromeBandBottomDp(180, 68, 2));
  // 没量到（回报 0）必须给 0，不许给 −34：负 dp 会让"量到了多低"在日志里变成假真值
  assertEq(0, chromeBandBottomDp(0, 68, 2));
  // 坏值（下缘在原点之上）同样钳成 0
  assertEq(0, chromeBandBottomDp(40, 68, 2));
  // density 0（分屏过渡帧）退成 0 而不是除零
  assertEq(0, chromeBandBottomDp(180, 68, 0));
});

test('px转dp的整数换算', () => {
  assertEq(100, pxToDp(300, 3));
  // 半像素向上进位（4.5/3 = 1.5 → 2）
  assertEq(2, pxToDp(4.5, 3));
  assertEq(1, pxToDp(1.4, 1));
  // density 0（分屏过渡帧 / 测试桩）必须回 0 而不是抛除零
  assertEq(0, pxToDp(100, 0));
  assertEq(0, pxToDp(0, 2));
});

test('矩形工具不自己发明尺寸_右下缘开区间', () => {
  const r: HudRectDp = new HudRectDp(8, 60, 200, 262);
  assertEq(192, r.width);
  assertEq(202, r.height);
  assertTrue(r.contains(8, 60), '左上缘闭');
  assertFalse(r.contains(200, 262), '右下缘开区间，相邻两枚容器边贴边时不能两边都命中');
  // 边贴边不算交叠（底栏贴着读数块下沿就是这个形态）
  const a: HudRectDp = new HudRectDp(500, 100, 700, 200);
  const b: HudRectDp = new HudRectDp(300, 200, 600, 282);
  assertFalse(a.intersects(b), '边贴边不许报重叠');
  assertTrue(a.intersects(new HudRectDp(600, 150, 650, 250)), '真重叠必须报');
});

test('列上限与行上限_存储层护栏', () => {
  assertEq(20, gridRowHardCap());
  assertEq(3, gridColsCapOf(HudZone.READOUT));
  assertEq(2, gridColsCapOf(HudZone.LEFT));
  assertEq(2, gridColsCapOf(HudZone.RIGHT));
  // 非网格容器给 1（格子对它不生效，只保证钳制算式不除零）
  assertEq(1, gridColsCapOf(HudZone.TOP));
  assertEq(1, gridColsCapOf(HudZone.BOTTOM));
});

test('格子先钳列再钳行_哨兵原样放行', () => {
  // 左 Dock 最多两列：第 3 列钳回 1；行 99 钳回 19
  const clamped: GridCell = clampStoredCell(HudZone.LEFT, new GridCell(2, 99));
  assertEq(1, clamped.col);
  assertEq(19, clamped.row);
  // 读数块三列：第 5 列钳回 2
  assertEq(2, clampStoredCell(HudZone.READOUT, new GridCell(5, 0)).col);
  // 负数钳回 0（不是"当哨兵"——只有两轴同时为负才是哨兵）
  assertEq(0, clampStoredCell(HudZone.LEFT, new GridCell(-1, 3)).col);
  // 哨兵原样放行（它表示"没摆过"，不是越界值）
  assertTrue(clampStoredCell(HudZone.LEFT, GridCell.DEFAULT).isDefault);
});

test('落区裁决_指针还在来源带里就不许改归属', () => {
  const source: HudZone = HudZone.RIGHT;
  const card: HudRectDp = new HudRectDp(600, 100, 700, 300);
  const band: IntRange = new IntRange(44, 288);
  // 指针在底板矩形里、也在带里 ⇒ 哪怕命中了读数块，仍归来源
  assertEq(source, dropZoneOf(new HudPointDp(650, 200), source, card, band, HudZone.READOUT));
  // 横向出了底板 ⇒ 允许改归属
  assertEq(HudZone.READOUT, dropZoneOf(new HudPointDp(500, 200), source, card, band, HudZone.READOUT));
  // 纵向出了带 ⇒ 允许改归属（哪怕横向还在底板）。注意判据只比横向 ± 带，不比底板自身的 y
  assertEq(HudZone.READOUT, dropZoneOf(new HudPointDp(650, 30), source, card, band, HudZone.READOUT));
  // 命中就是来源 / 一个都没命中 ⇒ 来源
  assertEq(source, dropZoneOf(new HudPointDp(650, 200), source, card, band, source));
  assertEq(source, dropZoneOf(new HudPointDp(650, 200), source, card, band, undefined));
  // 首帧量不到底板 ⇒ 按"不在带里"退回命中项（与改前同值）
  assertEq(HudZone.READOUT, dropZoneOf(new HudPointDp(650, 200), source, undefined, band, HudZone.READOUT));
});

test('容器元数据_顺序主轴与网格开关', () => {
  assertEq('T,L,R,D,B', hudZoneAll().map((z: HudZone): string => z).join(','));
  assertEq(HudZone.LEFT, hudZoneFromKey('L'));
  assertEq(HudZone.BOTTOM, hudZoneFromKey('B'));
  assertTrue(hudZoneFromKey('Q') === undefined);
  // 三枚网格容器，两枚有序列表
  assertTrue(hudZoneIsGrid(HudZone.LEFT));
  assertTrue(hudZoneIsGrid(HudZone.RIGHT));
  assertTrue(hudZoneIsGrid(HudZone.READOUT));
  assertFalse(hudZoneIsGrid(HudZone.TOP));
  assertFalse(hudZoneIsGrid(HudZone.BOTTOM));
  assertEq('COLUMN', hudZoneAxis(HudZone.LEFT));
  assertEq('GRID', hudZoneAxis(HudZone.READOUT));
  assertEq('ROW', hudZoneAxis(HudZone.BOTTOM));
});

// ================================================================== 二、持久化契约（上游 HudLayoutCodecTest）

test('默认表编码串逐字节手动字面量', () => {
  // 手写期望：版本段 + 五段（T/L/R/D/B），位置全哨兵 -1；
  // 三枚网格容器的每颗带 `:-` 后缀，顶栏/底栏只有裸 id。
  // 突变自证：把 ';' 换成 ','、把 ':-' 写成 '-1'、或漏掉某一颗后缀，本串立刻不等。
  const want: string = 'v2;T,-1,-1,P11,P12;' +
    'L,-1,-1,P6:-,P7:-,P8:-,P9:-;' +
    'R,-1,-1,P0:-,P1:-,P2:-,P3:-,P4:-,P5:-;' +
    'D,-1,-1,H0:-,H1:-,H2:-,H3:-,H4:-,H5:-,H6:-;' +
    'B,-1,-1,P10';
  assertEq(want, HudLayoutTable.default().encode());
});

test('编码保留五个容器段与版本头', () => {
  const raw: string = HudLayoutTable.default().encode();
  assertTrue(raw.startsWith('v2;'), '版本段必须是 v2');
  assertEq(6, raw.split(';').length); // 1 段版本 + 5 段容器
  for (const zone of hudZoneAll()) {
    assertTrue(raw.indexOf(';' + zone + ',') >= 0, `缺了 ${zone} 段`);
  }
  // 默认表每段的位置都是哨兵 -1（绝对值只能来自用户拖动）
  const segs: Array<string> = raw.split(';');
  for (let i = 1; i < segs.length; i++) {
    const f: Array<string> = segs[i].split(',');
    assertEq(-1, Number(f[1]));
    assertEq(-1, Number(f[2]));
  }
  // 网格容器每颗都带 `:格子` 后缀，默认表里全是哨兵 `-`
  const left: Array<string> = raw.split(';').filter((s: string): boolean => s.startsWith('L,'))[0].split(',').slice(3);
  for (const tok of left) {
    assertTrue(tok.endsWith(':-'), `左 Dock 每颗都必须带 :格子 后缀：${tok}`);
  }
  // 非网格容器（顶栏/底栏）不许带后缀：那两枚按顺序排，写了没人读就是脏数据
  const top: Array<string> = raw.split(';').filter((s: string): boolean => s.startsWith('T,'))[0].split(',').slice(3);
  for (const tok of top) {
    assertFalse(tok.indexOf(':') >= 0, `顶栏不该出现格子后缀：${tok}`);
  }
  assertTrue(top.indexOf('P11') >= 0, '顶栏必须含画幅那颗（P11）');
});

test('默认表顺序逐条对照B1B3书写顺序', () => {
  const t: HudLayoutTable = HudLayoutTable.default();
  assertEq('P11,P12', ids(t.orderOf(HudZone.TOP)));
  assertEq('P6,P7,P8,P9', ids(t.orderOf(HudZone.LEFT)));
  assertEq('P0,P1,P2,P3,P4,P5', ids(t.orderOf(HudZone.RIGHT)));
  assertEq('P10', ids(t.orderOf(HudZone.BOTTOM)));
  assertEq('H0,H1,H2,H3,H4,H5,H6', ids(t.orderOf(HudZone.READOUT)));
  assertEachEntryOnce(t, '默认表');
  for (const zone of hudZoneAll()) {
    assertTrue(t.posOf(zone).isDefault, `${zone} 段默认必须是哨兵`);
  }
});

function assertEachEntryOnce(table: HudLayoutTable, tag: string): void {
  const all: Array<HudEntry> = table.allEntries();
  assertEq(20, all.length, `${tag}：条目总数必须等于 13 颗 CamPill + 7 颗读数`);
  const unique: Set<string> = new Set<string>();
  for (const x of all) {
    unique.add(x.id);
  }
  assertEq(all.length, unique.size, `${tag}：同一颗控件不许出现在两枚容器里`);
  assertEq(HudEntry.ALL.length, unique.size, `${tag}：表里必须覆盖全部可编辑条目`);
}

test('两个命名空间不串号_坏id一律拒绝', () => {
  // CamPill.ZOOM 是 P3、HudItem.ZOOM 是 H6：两个东西，id 必须分字母
  assertEq('P3', e(CamPill.ZOOM).id);
  assertEq('H6', h(HudItem.ZOOM).id);
  assertFalse(e(CamPill.ZOOM).equals(h(HudItem.ZOOM)), '两个命名空间的同序号必须不相等');
  // 越界/坏串一律拒绝（手改 prefs 不能凭空造出一颗不存在的控件）
  for (const bad of ['P13', 'H7', 'Z0', '', 'P', 'P-1', 'Pxx']) {
    assertTrue(HudEntry.fromId(bad) === undefined, `坏 id [${bad}] 必须拒绝`);
  }
  // 合法 id 往返
  assertEq('P6', entryIdOf('P6'));
  assertEq('H3', entryIdOf('H3'));
});

test('手写已知串往返逐字节不变', () => {
  // 这条串是**手写**的：容器被拖过（T 42,7 / L 120,300）、左 Dock 有两颗显式格
  // （P6 第 1 列第 2 行、P8 第 0 列第 1 行）、右 Dock 配对两颗同格 (0,0)、读数 H0 在第 2 列第 1 行。
  // 全 20 颗都在串里（少一颗 decode 会补回 home 容器，串就变了）。
  // 突变自证：encode 漏写 `:格子`、或 decode 把后缀丢进 order 而不是 cells，往返串立刻不等。
  const raw: string = 'v2;T,42,7,P11,P12;' +
    'L,120,300,P6:1.2,P7:-,P8:0.1,P9:-;' +
    'R,-1,-1,P0:0.0,P1:0.0,P2:-,P3:-,P4:-,P5:-;' +
    'D,-1,-1,H0:2.1,H1:-,H2:-,H3:-,H4:-,H5:-,H6:-;' +
    'B,-1,-1,P10';
  const t: HudLayoutTable = HudLayoutTable.decode(raw);
  assertEq(raw, t.encode());
  // 位置与格子逐项点名（光看整串相等，查不出"顺序对了但格子错位"这类退化）
  assertEq(42, t.posOf(HudZone.TOP).xDp);
  assertEq(7, t.posOf(HudZone.TOP).yDp);
  assertEq(120, t.posOf(HudZone.LEFT).xDp);
  assertEq(300, t.posOf(HudZone.LEFT).yDp);
  const left: Map<string, GridCell> = t.cellsOf(HudZone.LEFT);
  assertEq('1.2', tokenOf(left, e(CamPill.REFLINE)));
  assertEq('0.1', tokenOf(left, e(CamPill.CURVE)));
  assertFalse(left.has(e(CamPill.MONITOR).id), '显式哨兵 `-` 不落格子表');
  // 右 Dock 配对两颗同格：编码里是两个相同的后缀，解码后两枚都读回 (0,0)
  const right: Map<string, GridCell> = t.cellsOf(HudZone.RIGHT);
  assertEq('0.0', tokenOf(right, e(CamPill.LEVEL)));
  assertEq('0.0', tokenOf(right, e(CamPill.VOLUME)));
  assertEq('2.1', tokenOf(t.cellsOf(HudZone.READOUT), h(HudItem.SHUTTER)));
  assertEachEntryOnce(t, '手写串回读');
});

test('坏串与未来版本整表回默认_不抛', () => {
  const def: HudLayoutTable = HudLayoutTable.default();
  const bads: Array<string | undefined> = [
    undefined, '', '   ', '这不是位置串', 'v1', 'v1;T,', 'v9;T,1,2,P0', 'v-1;x', 'v1;Q,1,1,P0'
  ];
  for (const bad of bads) {
    const t: HudLayoutTable = HudLayoutTable.decode(bad);
    assertTrue(sameTable(def, t), `坏串 [${String(bad)}] 必须整表回默认`);
    assertEachEntryOnce(t, `坏串 [${String(bad)}]`);
  }
  // 版本比本工程新：整表按默认（老代码读不懂新格式，硬解会把控件摆到错地方）
  assertTrue(sameTable(def, HudLayoutTable.decode(
    'v3;T,5,5,P11,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1')));
  // 版本号写成非数字 / 0 / 负数：一律按坏串回默认，不许走进 v1 迁移那一支
  for (const badHeader of ['v', 'v0', 'v-1', 'vx']) {
    assertTrue(sameTable(def, HudLayoutTable.decode(badHeader + ';T,5,5,P11,P12')), `坏版本头 [${badHeader}]`);
  }
});

test('未知容器段跳过_其余段保留', () => {
  // Q 段不认识 ⇒ 丢那一段；T 段的 42,7 照样读进来；其余容器回默认并补齐
  const t: HudLayoutTable = HudLayoutTable.decode('v1;Q,1,1,P0;T,42,7,P11,P12');
  assertEq(42, t.posOf(HudZone.TOP).xDp);
  assertEq(7, t.posOf(HudZone.TOP).yDp);
  assertTrue(t.posOf(HudZone.RIGHT).isDefault);
  assertEachEntryOnce(t, '未知段');
});

test('v1串迁移_位置与顺序原样_格子全留哨兵', () => {
  // **v1 → v2 迁移的硬要求**：用户已经拖过的东西一律不许被打回默认。
  // v1 的位置本来就是「顺序 × 分行」推导出来的，而 v2 的默认格子读的是同一条推导（J2），
  // 所以迁移的正确形态是"格子全留哨兵"——不换算坐标。
  const v1: string = 'v1;T,42,7,P11,P12;L,-1,-1,P6,P7,P8,P9;R,-1,-1,P0,P1,P2,P3,P4,P5;' +
    'D,-1,-1,H0,H1,H2,H3,H4,H5,H6;B,-1,-1,P2';
  // 注意末段 P2 是"变焦被拖去底栏"的存量形态：底栏只有镜头可编辑，normalize 会把 P2 送回去
  const t: HudLayoutTable = HudLayoutTable.decode(v1);
  assertEq(2, t.version, '读老串要把表版本抬到当前，下一次保存自然写成 v2');
  assertEq(42, t.posOf(HudZone.TOP).xDp);
  assertEq(7, t.posOf(HudZone.TOP).yDp);
  assertEq('P11,P12', ids(t.orderOf(HudZone.TOP)));
  assertEq('P6,P7,P8,P9', ids(t.orderOf(HudZone.LEFT)));
  // 迁移不写格子表：表里必须一个显式格子都没有
  for (const zone of hudZoneAll()) {
    assertEq(0, t.cellsOf(zone).size, `${zone} 段迁移后不该有显式格子`);
  }
  assertEachEntryOnce(t, 'v1 迁移表');
});

test('只写一段的半截串自我修复', () => {
  // 手改 / 断电写坏：其余容器回默认，位置取写好的那一档
  const t: HudLayoutTable = HudLayoutTable.decode('v1;T,42,7,P11,P12');
  assertEq(42, t.posOf(HudZone.TOP).xDp);
  assertEq(7, t.posOf(HudZone.TOP).yDp);
  assertTrue(t.posOf(HudZone.RIGHT).isDefault);
  assertEachEntryOnce(t, '只有一段');
  // 默认顺序不许被截断串抄走：顶栏仍是画幅→容量
  assertEq('P11,P12', ids(t.orderOf(HudZone.TOP)));
});

test('同一颗出现在两枚容器_先到先得', () => {
  const t: HudLayoutTable = HudLayoutTable.decode(
    'v1;T,42,7,P11,P12,P3;L,-1,-1,P6,P7,P8,P9,P3;R,-1,-1;D,-1,-1;B,-1,-1');
  // 第一段（TOP）先收下 P3，左 Dock 的那颗被丢掉
  let inTop: boolean = false;
  for (const x of t.orderOf(HudZone.TOP)) {
    if (x.id === 'P3') {
      inTop = true;
    }
  }
  assertTrue(inTop, '变焦留在先出现的顶栏段');
  for (const x of t.orderOf(HudZone.LEFT)) {
    assertFalse(x.id === 'P3', '变焦不许同时挂在左 Dock');
  }
  assertEachEntryOnce(t, '重复条目');
});

test('半吊子位置按未编辑处理_y为0是合法绝对值', () => {
  // 一轴绝对、一轴哨兵：只能来自手改 prefs，按「未编辑」整段处理
  const half: HudLayoutTable = HudLayoutTable.decode('v1;T,42,-1,P11,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1');
  assertTrue(half.posOf(HudZone.TOP).isDefault, '半吊子位置必须回默认');
  assertEq(-1, half.posOf(HudZone.TOP).xDp);
  const zero: HudLayoutTable = HudLayoutTable.decode('v1;T,42,0,P11,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1');
  assertEq(42, zero.posOf(HudZone.TOP).xDp);
  assertEq(0, zero.posOf(HudZone.TOP).yDp);
  assertFalse(zero.posOf(HudZone.TOP).isDefault, 'y=0 是合法绝对值，不许被当成哨兵');
});

test('存量底栏绝对x读表时被抹掉_只留y', () => {
  // r11 那版录制页的 onDrop 会把"当前实测左缘"写成绝对 x，存量 prefs 里就有这种串
  const t: HudLayoutTable = HudLayoutTable.decode('v1;T,-1,-1;L,-1,-1;R,-1,-1;D,-1,-1;B,120,282,P2');
  assertEq(-1, t.posOf(HudZone.BOTTOM).xDp);
  assertEq(282, t.posOf(HudZone.BOTTOM).yDp);
  assertFalse(t.posOf(HudZone.BOTTOM).isDefault, '抹掉 x 之后底栏落位仍不许算默认');
  // y 也是哨兵（没拖过）时不许凭空造出一个 0
  const untouched: HudLayoutTable = HudLayoutTable.decode('v1;T,-1,-1;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1,P2');
  assertTrue(untouched.posOf(HudZone.BOTTOM).isDefault);
  // 同一个闸门的入口函数：绝对 x + 绝对 y 进来，出的仍是哨兵 x
  const legacy: HudLayoutTable = HudLayoutTable.default().withZonePos(HudZone.BOTTOM, 66, 300);
  assertEq(-1, legacy.normalizedPosOf(HudZone.BOTTOM).xDp);
  assertEq(300, legacy.normalizedPosOf(HudZone.BOTTOM).yDp);
  // 另外四枚容器没有这个特例：两轴绝对照收
  const right: HudLayoutTable = HudLayoutTable.default().withZonePos(HudZone.RIGHT, 612, 88);
  assertEq(612, right.normalizedPosOf(HudZone.RIGHT).xDp);
  assertEq(88, right.normalizedPosOf(HudZone.RIGHT).yDp);
});

test('未知id丢弃_缺失条目回home容器末尾', () => {
  // P99 不存在 ⇒ 丢；缺 SIZE（P11）⇒ 补回顶栏末尾
  const t: HudLayoutTable = HudLayoutTable.decode('v1;T,1,1,P99,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1');
  assertTrue(HudEntry.fromId('P99') === undefined);
  assertEq('P12,P11', ids(t.orderOf(HudZone.TOP)));
  assertEachEntryOnce(t, '补全表');
});

test('坏格子后缀只丢那一颗的格子_条目照留', () => {
  // 手改 prefs 造的坏格子：后缀缺行、非数字、负数、越界列，全部**只丢那一颗的格子**
  const raw: string = 'v2;T,-1,-1,P11,P12;L,-1,-1,P6:0,P7:1.2,P8:x.y,P9:9.9;R,-1,-1,P0;D,-1,-1,H0;B,-1,-1,P2';
  const t: HudLayoutTable = HudLayoutTable.decode(raw);
  const left: Map<string, GridCell> = t.cellsOf(HudZone.LEFT);
  assertFalse(left.has(e(CamPill.REFLINE).id), 'P6:0 缺行 ⇒ 按没摆过处理');
  assertEq('1.2', tokenOf(left, e(CamPill.MONITOR)));
  assertFalse(left.has(e(CamPill.CURVE).id), 'P8:x.y 非数字 ⇒ 没摆过');
  // `P9:9.9` 的列越界（左 Dock 最多两列）⇒ 解码时就钳进上限；行 9 没超行上限（条目总数 20）所以照收
  assertEq('1.9', tokenOf(left, e(CamPill.FLASH)));
  assertEq(4, t.orderOf(HudZone.LEFT).length, '条目本身照留');
});

test('隐藏条目保留在串里_位置与归属不回默认', () => {
  // 「隐藏即摘表」是最自然的错误写法：隐藏不写表，所以表里一直是用户放好的顺序与位置。
  const edited: HudLayoutTable = HudLayoutTable.default().withZonePos(HudZone.LEFT, 200, 90);
  const hiddenVisible: Set<string> = new Set<string>();
  for (const x of HudEntry.ALL) {
    if (x.id !== e(CamPill.STORAGE).id && x.id !== e(CamPill.CURVE).id) {
      hiddenVisible.add(x.id);
    }
  }
  // 录制页的可见顺序过滤掉它们，但表（以及持久化串）原样不动
  const rendered: string = ids(edited.visibleOrderOf(HudZone.LEFT, hiddenVisible));
  assertFalse(rendered.indexOf(e(CamPill.CURVE).id) >= 0, '关掉的条目不许出现在页面上');
  const afterRestart: HudLayoutTable = HudLayoutTable.decode(edited.encode());
  assertEq(200, afterRestart.posOf(HudZone.LEFT).xDp);
  assertEq(90, afterRestart.posOf(HudZone.LEFT).yDp);
  const shown: Set<string> = new Set<string>();
  for (const x of HudEntry.ALL) {
    shown.add(x.id);
  }
  assertEq('P6,P7,P8,P9', ids(afterRestart.visibleOrderOf(HudZone.LEFT, shown)), '重开显示后位置/顺序仍是用户放好的');
  assertEachEntryOnce(afterRestart, '隐藏→再显示');
});

test('整块读数全关也不重置容器位置', () => {
  const t: HudLayoutTable = HudLayoutTable.default().withZonePos(HudZone.READOUT, 300, 120);
  assertEq(0, t.visibleOrderOf(HudZone.READOUT, new Set<string>()).length, '读数全关时这块不该画任何东西');
  assertEq(300, HudLayoutTable.decode(t.encode()).posOf(HudZone.READOUT).xDp);
  assertEq(120, HudLayoutTable.decode(t.encode()).posOf(HudZone.READOUT).yDp);
});

test('重置回默认_重置前的串仍可完整读回', () => {
  const edited: HudLayoutTable = HudLayoutTable.default()
    .withZonePos(HudZone.LEFT, 12, 300)
    .withZonePos(HudZone.READOUT, 500, 250);
  const beforeReset: string = edited.encode();
  const afterReset: HudLayoutTable = HudLayoutTable.default();
  // 重置按钮写下去的就是这一张表：任何绝对坐标都不许残留
  assertAnyAbsolutePos(afterReset);
  assertEq('P10', ids(afterReset.orderOf(HudZone.BOTTOM)));
  // 「即时可重做」的模型前提：重置前那张表的串还完整可读回来（撤销靠的是它）
  assertTrue(sameTable(edited, HudLayoutTable.decode(beforeReset)), '撤销重置要把前一张表原样读回来');
});

function assertAnyAbsolutePos(table: HudLayoutTable): void {
  for (const zone of hudZoneAll()) {
    assertTrue(table.posOf(zone).isDefault, `${zone} 段重置后必须回默认位置`);
  }
}

test('home容器与默认表不许分叉', () => {
  // 两条独立写的真源必须自洽：homeZone 的 when 分支 与 defaultOrderOf 的清单。
  for (const zone of hudZoneAll()) {
    for (const entry of HudLayoutTable.defaultOrderOf(zone)) {
      assertEq(zone, entry.homeZone, `${entry.id} 在默认表里属于 ${zone}，但 homeZone 说的是 ${entry.homeZone}`);
    }
  }
  const counts: Map<string, number> = new Map<string, number>();
  for (const x of HudEntry.ALL) {
    const k: string = x.homeZone;
    const cur: number | undefined = counts.get(k);
    counts.set(k, cur === undefined ? 1 : cur + 1);
  }
  assertEq(2, countOf(counts, HudZone.TOP));
  assertEq(4, countOf(counts, HudZone.LEFT));
  assertEq(6, countOf(counts, HudZone.RIGHT));
  assertEq(7, countOf(counts, HudZone.READOUT));
  assertEq(1, countOf(counts, HudZone.BOTTOM));
});

function countOf(counts: Map<string, number>, zone: HudZone): number {
  const v: number | undefined = counts.get(zone);
  return v === undefined ? -1 : v;
}

test('每颗CamPill与HudItem都有条目', () => {
  // 位掩码里的每一颗都必须能在表里出现，否则设置页关了它、编辑页却永远找不到（假开关的另一半）
  for (const pill of [CamPill.LEVEL, CamPill.VOLUME, CamPill.BT, CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB,
    CamPill.REFLINE, CamPill.MONITOR, CamPill.CURVE, CamPill.FLASH, CamPill.LENS, CamPill.SIZE, CamPill.STORAGE]) {
    const entry: HudEntry = HudEntry.ofPill(pill);
    assertEq(pill, entry.pill);
    assertTrue(entry.item === undefined, 'PILL 命名空间不许带出读数');
    const back: HudEntry | undefined = HudEntry.fromId(entry.id);
    assertTrue(back !== undefined && back.id === entry.id, `${entry.id} 必须能往返`);
  }
  for (const item of [HudItem.SHUTTER, HudItem.FPS, HudItem.BITRATE, HudItem.ISO, HudItem.EV, HudItem.WB,
    HudItem.ZOOM]) {
    const entry: HudEntry = HudEntry.ofItem(item);
    assertEq(item, entry.item);
    assertTrue(entry.pill === undefined, 'READOUT 命名空间不许带出胶囊');
    const back: HudEntry | undefined = HudEntry.fromId(entry.id);
    assertTrue(back !== undefined && back.id === entry.id, `${entry.id} 必须能往返`);
  }
  assertEq(20, HudEntry.ALL.length);
  assertEq(20, new Set<string>(HudEntry.ALL.map((x: HudEntry): string => x.id)).size, 'id 必须唯一');
});

test('编辑页可见集列全部可装载控件_码率与关掉的水平仪除外', () => {
  const visible: Set<string> = editorVisibleEntries(true);
  // 20 − 码率 = 19；掩码关掉的两颗照样列出来（编辑页的核心诉求）
  assertEq(19, visible.size);
  assertTrue(visible.has(e(CamPill.MONITOR).id), '编辑页必须列出被掩码关掉的监看');
  assertTrue(visible.has(e(CamPill.FLASH).id), '编辑页必须列出被掩码关掉的闪光灯');
  assertFalse(visible.has(h(HudItem.BITRATE).id), '码率不进编辑页可见集（Dock 外固定读数）');
  // 水平仪的显示开关关掉时那颗整颗不渲染 ⇒ 编辑页不列
  assertFalse(editorVisibleEntries(false).has(e(CamPill.LEVEL).id));
  assertTrue(editorVisibleEntries(true).has(e(CamPill.LEVEL).id));
});

test('hud_items掩码契约_位值默认档与往返', () => {
  // 位值与上游逐值一致（持久化契约）：SHUTTER 1 / FPS 2 / BITRATE 4 / ISO 8 / EV 16 / WB 32 / ZOOM 64
  assertEq(1, HudItems.bit(HudItem.SHUTTER));
  assertEq(2, HudItems.bit(HudItem.FPS));
  assertEq(4, HudItems.bit(HudItem.BITRATE));
  assertEq(8, HudItems.bit(HudItem.ISO));
  assertEq(16, HudItems.bit(HudItem.EV));
  assertEq(32, HudItems.bit(HudItem.WB));
  assertEq(64, HudItems.bit(HudItem.ZOOM));
  // 出厂默认上屏四档 = 快门 | 帧率 | 码率 | 感光度 = 15（与上游 DEFAULT_MASK 同值）
  assertEq(15, HudItems.DEFAULT_MASK);
  assertEq('H0,H1,H2,H3', ids(HudItems.typesOf(HudItems.DEFAULT_MASK).map(
    (i: HudItem): HudEntry => HudEntry.ofItem(i))));
  // 掩码往返：全开 127、来回不丢位
  const full: number = HudItems.maskOf(HudItems.ALL);
  assertEq(127, full);
  assertEq(full, HudItems.maskOf(HudItems.typesOf(full)));
  assertEq(0, HudItems.typesOf(0).length);
});


// ================================================================== 三、底栏两档吸附（上游第 8 条）

test('底栏上下两档由实测高推出_不是写死80dp', () => {
  const lower: number = dockLowerY(LAND, 72, 6);
  const upper: number = dockUpperY(LAND, 72, 6, 8);
  assertEq(282, lower, '下栏 = 360 − 6 − 72');
  assertEq(202, upper, '上栏 = 下栏 − 72 − 8');
  // 排高变了（120% 文本 → 底板更高）两档跟着分开
  assertEq(276, dockLowerY(LAND, 78, 6));
  assertEq(190, dockUpperY(LAND, 78, 6, 8));
});

test('底栏只吸两档_往上过半程才去上栏', () => {
  const lower: number = dockLowerY(LAND, 72, 6);
  const upper: number = dockUpperY(LAND, 72, 6, 8);
  assertEq(lower, dockSnapY(LAND, 72, 6, 8, 0), '原地松手');
  assertEq(lower, dockSnapY(LAND, 72, 6, 8, 30), '往下拖');
  assertEq(lower, dockSnapY(LAND, 72, 6, 8, -39), '往上但没过半程');
  assertEq(upper, dockSnapY(LAND, 72, 6, 8, -40), '过半程（间距 80，半程 40）');
  assertEq(upper, dockSnapY(LAND, 72, 6, 8, -999), '再往上也是上栏，没有第三档');
  assertEq(dockLowerY(PORT, 72, 6), dockSnapY(PORT, 72, 6, 8, 0));
  assertEq(dockUpperY(PORT, 72, 6, 8), dockSnapY(PORT, 72, 6, 8, -999));
});

test('极矮窗口两档重合时_一律按下栏处理', () => {
  const tiny: HudAreaDp = new HudAreaDp(766, 70, 0, 0);
  assertEq(0, dockLowerY(tiny, 72, 6));
  assertEq(0, dockUpperY(tiny, 72, 6, 8));
  assertEq(0, dockSnapY(tiny, 72, 6, 8, -999));
});

test('录制三态拒绝搬底栏_空闲与失败放行', () => {
  assertTrue(dockDragBlocked(HudDockStatus.PREPARE));
  assertTrue(dockDragBlocked(HudDockStatus.START));
  assertTrue(dockDragBlocked(HudDockStatus.STOPPING), 'STOPPING 也被挡：分裂动画中没有第二指的空间');
  assertFalse(dockDragBlocked(HudDockStatus.IDLE));
  assertFalse(dockDragBlocked(HudDockStatus.ERROR));
  // 五档逐个都判过：新增一档时这里要求显式表态
  assertEq(5, [HudDockStatus.IDLE, HudDockStatus.PREPARE, HudDockStatus.START,
    HudDockStatus.STOPPING, HudDockStatus.ERROR].length);
});

// ================================================================== 四、重叠只提示不禁止 + 最内层命中

test('重叠检测报成对而不是计数_边贴边不算叠', () => {
  const rects: Map<string, HudRectDp> = new Map<string, HudRectDp>();
  rects.set(HudZone.LEFT, new HudRectDp(8, 60, 200, 262));
  rects.set(HudZone.READOUT, new HudRectDp(150, 200, 400, 262));
  rects.set(HudZone.TOP, new HudRectDp(8, 4, 300, 48));
  const pairs: Array<HudZonePair> = overlappingZones(rects);
  assertEq(1, pairs.length, '只该报出真重叠的那一对');
  assertEq(HudZone.LEFT, pairs[0].first);
  assertEq(HudZone.READOUT, pairs[0].second);
  // 外接矩形边贴边不算叠
  const touching: Map<string, HudRectDp> = new Map<string, HudRectDp>();
  touching.set(HudZone.READOUT, new HudRectDp(500, 100, 700, 200));
  touching.set(HudZone.BOTTOM, new HudRectDp(300, 200, 600, 282));
  assertEq(0, overlappingZones(touching).length, '边贴边不许报重叠');
  assertEq(0, overlappingZones(new Map<string, HudRectDp>()).length);
  const single: Map<string, HudRectDp> = new Map<string, HudRectDp>();
  single.set(HudZone.TOP, new HudRectDp(0, 0, 10, 10));
  assertEq(0, overlappingZones(single).length);
});

test('指针命中面积最小的那枚容器_不是第一个命中', () => {
  const rects: Map<string, HudRectDp> = new Map<string, HudRectDp>();
  rects.set(HudZone.LEFT, new HudRectDp(0, 0, 100, 300));
  rects.set(HudZone.READOUT, new HudRectDp(50, 200, 150, 260));
  assertEq(HudZone.READOUT, zoneAt(new HudPointDp(60, 210), rects), '读数块整个压在左 Dock 的角上');
  assertEq(HudZone.LEFT, zoneAt(new HudPointDp(10, 10), rects));
  assertTrue(zoneAt(new HudPointDp(999, 999), rects) === undefined, '两枚之外：本次拖动不改归属');
  assertTrue(zoneAt(new HudPointDp(0, 0), new Map<string, HudRectDp>()) === undefined);
});

test('操作栏与顶栏的交叠判定_33dp仍压住_34dp脱离', () => {
  // 竖屏本机手算：三颗胶囊左 8、上 4、高 30、宽 192；顶栏两段整宽 271、高 24
  const chips: HudRectDp = new HudRectDp(8, 4, 200, 34);
  const topUnpushed: HudRectDp = new HudRectDp(8, 4, 8 + 271, 4 + 24);
  assertTrue(topUnpushed.intersects(chips), '不推就必须交叠');
  const pushed: HudRectDp = new HudRectDp(8, 56, 8 + 271, 56 + 24);
  assertFalse(pushed.intersects(chips), '推到操作栏下缘之后必须完全让开');
  assertTrue(new HudRectDp(8, 33, 8 + 271, 33 + 24).intersects(chips), '33dp 那一档仍压在胶囊行里');
  assertFalse(new HudRectDp(8, 34, 8 + 271, 34 + 24).intersects(chips), '34dp 正好脱离');
  assertTrue(topUnpushed.left < chips.right && chips.left < topUnpushed.right, '横向确实重叠');
  assertEq(4, Math.max(4, chromeBandBottomDp(0, 68, 2)), '让位量 0 时等于改前的 4dp');
  assertEq(56, Math.max(4, chromeBandBottomDp(180, 68, 2)));
});

// ================================================================== 五、J2 补的编解码臂

test('编辑过的表编解码无损_含跨容器换序与显式格', () => {
  const edited: HudLayoutTable = HudLayoutTable.default()
    .withZonePos(HudZone.RIGHT, 612, 88)
    .moveEntryTo(e(CamPill.CURVE), HudZone.TOP, 1, PLAN_ALL)
    .placeEntryAt(e(CamPill.FLASH), HudZone.LEFT, 3, new GridCell(1, 3), PLAN_ALL);
  const back: HudLayoutTable = HudLayoutTable.decode(edited.encode());
  assertEq(edited.encode(), back.encode(), '编解码必须无损回到同一张表');
  assertEq(612, back.posOf(HudZone.RIGHT).xDp);
  assertEq(88, back.posOf(HudZone.RIGHT).yDp);
  assertEq(HudZone.TOP, back.sourceZoneOf(e(CamPill.CURVE)));
  // 光看整张表相等看不出 encode 漏写后缀（默认格恰好等于钉住之前那一格）⇒ 直接点格子值
  assertEq('1.3', tokenOf(back.cellsOf(HudZone.LEFT), e(CamPill.FLASH)));
  assertEachEntryOnce(back, '回读表');
});

test('显式格只对网格容器落串_非网格容器不留脏数据', () => {
  const t: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.BT), HudZone.RIGHT, 4, new GridCell(1, 2), PLAN_ALL);
  let rightSeg: string = '';
  let topSeg: string = '';
  for (const seg of t.encode().split(';')) {
    if (seg.startsWith('R,')) {
      rightSeg = seg;
    }
    if (seg.startsWith('T,')) {
      topSeg = seg;
    }
  }
  assertTrue(rightSeg.indexOf('P2:1.2') >= 0, `右 Dock 的格子后缀没写进串：${rightSeg}`);
  assertFalse(topSeg.indexOf(':') >= 0, `顶栏写了格子后缀：${topSeg}`);
  assertEq('1.2', tokenOf(HudLayoutTable.decode(t.encode()).cellsOf(HudZone.RIGHT), e(CamPill.BT)));
});

test('v1串迁移_逐颗落点等于推导格_格子表全空', () => {
  // **v1 → v2 迁移的硬要求**：用户已经拖过的东西一律不许被打回默认。
  const v1: string = 'v1;T,42,7,P11,P12;L,-1,-1,P6,P7,P8,P9;R,-1,-1,P0,P1,P2,P3,P4,P5;' +
    'D,-1,-1,H0,H1,H2,H3,H4,H5,H6;B,-1,-1,P2';
  const t: HudLayoutTable = HudLayoutTable.decode(v1);
  assertEq(2, t.version, '读老串要把表版本抬到当前');
  assertEq(42, t.posOf(HudZone.TOP).xDp, '老串里拖过的容器位置必须原样保住');
  assertEq(7, t.posOf(HudZone.TOP).yDp);
  // 逐颗点名默认格子（v1 的顺序 × 分行 ⇒ 与 v2 推导同形）
  assertEq('0.0', cellTokenAt(t, HudZone.LEFT, e(CamPill.REFLINE)));
  assertEq('0.2', cellTokenAt(t, HudZone.LEFT, e(CamPill.CURVE)));
  assertEq('0.3', cellTokenAt(t, HudZone.LEFT, e(CamPill.FLASH)));
  // 右 Dock 配对共格（v1 迁移后必须与新默认同形，否则一进编辑页就看到右 Dock 变宽）
  assertEq('0.0', cellTokenAt(t, HudZone.RIGHT, e(CamPill.LEVEL)));
  assertEq('0.0', cellTokenAt(t, HudZone.RIGHT, e(CamPill.VOLUME)));
  assertEq('0.1', cellTokenAt(t, HudZone.RIGHT, e(CamPill.BT)));
  assertEq('0.4', cellTokenAt(t, HudZone.RIGHT, e(CamPill.STAB)));
  assertEq('2.1', cellTokenAt(t, HudZone.READOUT, h(HudItem.WB)));
  assertEq('0.2', cellTokenAt(t, HudZone.READOUT, h(HudItem.ZOOM)));
  // 迁移不写格子表
  for (const zone of hudZoneAll()) {
    assertEq(0, t.cellsOf(zone).size, `${zone} 段迁移后不该有显式格子`);
  }
  assertTrue(cellTokenAt(t, HudZone.TOP, e(CamPill.SIZE)) === '-', '非网格容器永远拿哨兵格');
  assertEachEntryOnce(t, 'v1 迁移表');
});

test('v1编辑过的顺序迁移到它渲染的那一格', () => {
  // 变焦被拖到左 Dock 第 0 格、左 Dock 整体被拖到 (120,300)
  const v1: string = 'v1;T,-1,-1,P11,P12;L,120,300,P3,P6,P7,P8,P9;R,-1,-1,P0,P1,P2,P4,P5;' +
    'D,-1,-1,H0,H1,H2,H3,H4,H5,H6;B,-1,-1,P2';
  const t: HudLayoutTable = HudLayoutTable.decode(v1);
  assertEq(HudZone.LEFT, t.sourceZoneOf(e(CamPill.ZOOM)));
  const left: Array<HudGridItem> = t.gridItems(HudZone.LEFT, PLAN_ALL);
  assertEq(e(CamPill.ZOOM).id, left[0].entry.id, '拖到左 Dock 第 0 格的变焦仍在第 0 位');
  assertEq('0.0', left[0].cell.token, '落点仍是第 0 行第 0 列');
  assertEq('0.1', left[1].cell.token);
  assertEq(120, t.posOf(HudZone.LEFT).xDp, '容器整体落点必须原样搬过来');
  assertEq(300, t.posOf(HudZone.LEFT).yDp);
  assertEq('0.0', cellTokenAt(t, HudZone.RIGHT, e(CamPill.LEVEL)));
  assertEq('0.0', cellTokenAt(t, HudZone.RIGHT, e(CamPill.VOLUME)));
  assertEq('0.2', cellTokenAt(t, HudZone.RIGHT, e(CamPill.FOCUS)));
});

test('v1拖过容器加搬过条目加隐藏_整串迁移且重开回原位', () => {
  const v1: string = 'v1;T,-1,-1,P11;L,200,90,P12,P6,P7,P9;R,-1,-1,P0,P1,P2,P3,P4,P5;' +
    'D,-1,-1,H0,H1,P8,H2,H3,H4,H5,H6;B,-1,-1,P10';
  const t: HudLayoutTable = HudLayoutTable.decode(v1);
  assertEq(200, t.posOf(HudZone.LEFT).xDp, '容器落点原样');
  assertEq(90, t.posOf(HudZone.LEFT).yDp);
  assertTrue(t.posOf(HudZone.RIGHT).isDefault, '没拖过的容器仍必须是哨兵');
  assertEq('P12,P6,P7,P9', ids(t.orderOf(HudZone.LEFT)), '归属与顺序原样');
  assertEq(HudZone.READOUT, t.sourceZoneOf(e(CamPill.CURVE)));
  // 逐颗的格子 = 改前那副排布
  assertEq('0.0', cellTokenAt(t, HudZone.LEFT, e(CamPill.STORAGE)));
  assertEq('0.1', cellTokenAt(t, HudZone.LEFT, e(CamPill.REFLINE)));
  assertEq('0.3', cellTokenAt(t, HudZone.LEFT, e(CamPill.FLASH)));
  assertEq('2.0', cellTokenAt(t, HudZone.READOUT, e(CamPill.CURVE)));
  assertEq('1.1', cellTokenAt(t, HudZone.READOUT, h(HudItem.ISO)));
  assertEq('1.2', cellTokenAt(t, HudZone.READOUT, h(HudItem.ZOOM)));
  // 关掉那两颗再重开：位置还是"改前它所在的那一格"，且没有回出厂容器
  const hidden: Set<string> = new Set<string>();
  for (const pill of [CamPill.LEVEL, CamPill.VOLUME, CamPill.BT, CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB,
    CamPill.REFLINE, CamPill.MONITOR, CamPill.FLASH, CamPill.LENS, CamPill.SIZE]) {
    hidden.add(e(pill).id);
  }
  for (const item of HudItems.ALL) {
    hidden.add(h(item).id);
  }
  let storageVisible: boolean = false;
  for (const x of t.visibleOrderOf(HudZone.LEFT, hidden)) {
    if (x.id === e(CamPill.STORAGE).id) {
      storageVisible = true;
    }
  }
  assertFalse(storageVisible, '关掉的条目不许出现在页面上');
  const reopened: HudLayoutTable = HudLayoutTable.decode(t.encode());
  const shown: Array<HudEntry> = reopened.visibleOrderOf(HudZone.LEFT, new Set<string>(
    HudEntry.ALL.map((x: HudEntry): string => x.id)));
  assertEq(e(CamPill.STORAGE).id, shown[0].id, '重开显示必须回到左 Dock 第 0 位（不是出厂的顶栏）');
  assertEq('0.0', reopened.gridItems(HudZone.LEFT, PLAN_ALL)[0].cell.token, '重开后落点还是第 0 行');
  assertEq(t.encode(), HudLayoutTable.decode(t.encode()).encode(), '同一串二次解码与原表一致');
  assertEachEntryOnce(t, 'v1 拖过 + 隐藏过');
});

test('配对同格同串往返_格内次序等于清单次序', () => {
  const paired: HudLayoutTable = HudLayoutTable.default()
    .placeEntryAt(e(CamPill.BT), HudZone.RIGHT, 2, new GridCell(1, 0), PLAN_ALL);
  let rightSeg: string = '';
  for (const seg of paired.encode().split(';')) {
    if (seg.startsWith('R,')) {
      rightSeg = seg;
    }
  }
  assertEq('R,-1,-1,P0:0.0,P1:0.0,P2:1.0,P3:0.2,P4:0.3,P5:0.4', rightSeg,
    '真实串钉的是格式本身（两颗同格不许新造写法）');
  const back: HudLayoutTable = HudLayoutTable.decode(paired.encode());
  assertEq(paired.encode(), back.encode(), '整张表无损 / 编→解→编同串');
  assertEq('0.0', tokenOf(back.cellsOf(HudZone.RIGHT), e(CamPill.LEVEL)));
  assertEq('0.0', tokenOf(back.cellsOf(HudZone.RIGHT), e(CamPill.VOLUME)));
  const inPair: Array<string> = [];
  for (const it of back.gridItems(HudZone.RIGHT, PLAN_ALL)) {
    if (it.cell.token === '0.0') {
      inPair.push(it.entry.id);
    }
  }
  assertEq('P0,P1', inPair.join(','), '格内左右次序 = 清单次序');
  assertEachEntryOnce(back, '配对同格往返');
  // 出厂默认表（格子全哨兵）里两颗也共用推导格 ⇒ 与上面是两条不同的码路
  assertEq('0.0', cellTokenAt(HudLayoutTable.default(), HudZone.RIGHT, e(CamPill.LEVEL)));
  assertEq('0.0', cellTokenAt(HudLayoutTable.default(), HudZone.RIGHT, e(CamPill.VOLUME)));
});

test('坏格子后缀只丢那一颗_推导格补回', () => {
  // 缺行、非数字、负数、越界列：全部只丢那一颗的格子，条目本身照留
  const raw: string = 'v2;T,-1,-1,P11,P12;L,-1,-1,P6:0,P7:1.2,P8:x.y,P9:9.9;R,-1,-1,P0;D,-1,-1,H0;B,-1,-1,P2';
  const t: HudLayoutTable = HudLayoutTable.decode(raw);
  // ① 存储层：只有合法后缀收下，`P9:9.9` 的列被钳进两列上限
  assertEq('1.2', tokenOf(t.cellsOf(HudZone.LEFT), e(CamPill.MONITOR)));
  assertEq('1.9', tokenOf(t.cellsOf(HudZone.LEFT), e(CamPill.FLASH)));
  assertFalse(t.cellsOf(HudZone.LEFT).has(e(CamPill.REFLINE).id), 'P6:0 缺行 ⇒ 按没摆过处理');
  assertFalse(t.cellsOf(HudZone.LEFT).has(e(CamPill.CURVE).id), 'P8:x.y 非数字 ⇒ 没摆过');
  assertEq(4, t.orderOf(HudZone.LEFT).length, '条目本身照留');
  // ② 解析层：没摆过的那两颗回落到推导格（不是 (0,0) 也不是别的野值）
  assertEq('0.0', cellTokenAt(t, HudZone.LEFT, e(CamPill.REFLINE)));
  assertEq('1.2', cellTokenAt(t, HudZone.LEFT, e(CamPill.MONITOR)));
  assertEq('0.2', cellTokenAt(t, HudZone.LEFT, e(CamPill.CURVE)));
  assertEq('1.9', cellTokenAt(t, HudZone.LEFT, e(CamPill.FLASH)));
});
