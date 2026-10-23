/**
 * 整页网格坐标数学（直译上游 `HudPageGridTest` 的页格数学/往返/占用/护栏五组；
 * 上游里吃 `placeEntryAt`/`freeCellNear`/`clampCellToBox` 的落点分支属 J2，本批不测）。
 *
 * ## 期望值全部手算，不许从实现回抄
 * 手算档（density 2.0）：页格边长 = (18 + 12 + 4) × 2 = 68px = 34dp；120% = (21.6 + 12 + 4) × 2 = 75.2
 * → 向上取整 76px；字号 <100% 被夹回 1f ⇒ 仍是 68px。
 * 横屏安全区 1532×720px（766dp × 360dp，挖孔让掉一条短边）：
 * 100% 档 1532 ÷ 68 = 22 列（余 36px）、720 ÷ 68 = 10 行（余 40px）；120% 档 20 列（余 12px）/ 9 行（余 36px）。
 *
 * ## 恒等式不算证明
 * "页格与 Dock 同源"拿**两个独立算式**对撞（页格函数 vs 胶囊高+4dp 的手算式）；
 * "末格弃掉"拿 ceil 会给出的 23 列 / 11 行当反面对照；"整块判据"拿 1 档的对照支证明它在跑。
 */
import {
  HudPageCell, gridCellToPageCell, pageCellAtPointer, pageCellOffsetPx, pageCellPx, pageCellsBlocked,
  pageCellToGridCell, pageGridBoxOf, pageGridColCap, pageGridRowCap
} from '../../entry/src/main/ets/core/HudPageGrid';
import { CamPill } from '../../entry/src/main/ets/core/Constants';
import {
  GridBox, GridCell, HudEntry, HudPointPx, blockingCells, gridRowHardCap
} from '../../entry/src/main/ets/core/HudLayout';
import { HUD_ROW_GAP_DP, hudChipHeightDp } from '../../entry/src/main/ets/core/HudMetrics';

const DENSITY: number = 2;
const SAFE_W_PX: number = 1532;
const SAFE_H_PX: number = 720;

function e(pill: CamPill): HudEntry {
  return HudEntry.ofPill(pill);
}

// ------------------------------------------------------------------ 一、格长：与 Dock 内格同源同尺

test('页格边长取竖Dock档_100与120与低于100', () => {
  // 100% = (18 + 12 + 4) × 2 = 68px；120% = 75.2 → 76px；0.8 档被夹回 1f ⇒ 68px
  assertEq(68, pageCellPx(1, DENSITY));
  assertEq(76, pageCellPx(1.2, DENSITY));
  assertEq(68, pageCellPx(0.8, DENSITY));
  // 同源的独立见证：Dock 纵向格距的手算式（胶囊高 + 4dp）× density 向上取整，与本函数逐字同值。
  // 突变：把 pageCellPx 的行距档换成读数块的 6dp，下面第一行给 72 ≠ 68 立刻红
  assertEq(Math.ceil((hudChipHeightDp(1) + 4) * DENSITY), pageCellPx(1, DENSITY));
  assertEq(68, pageCellPx(1, DENSITY));
});

test('页格不许跟着读数块那一档走', () => {
  // 读数块那一档是 6dp：(18+12+6)×2 = 72px。页格 68 ≠ 72 —— 两条同时在才说得清"跟的是 Dock"
  const readoutTier: number = Math.ceil((hudChipHeightDp(1) + HUD_ROW_GAP_DP) * DENSITY);
  assertEq(72, readoutTier);
  assertTrue(readoutTier !== pageCellPx(1, DENSITY), '页格必须取 4dp 档而不是 6dp 档');
  // 字号档只能让格变密或不变，不许变稀（<100% 被夹回 1f）
  assertEq(pageCellPx(1, DENSITY), pageCellPx(0.8, DENSITY));
});

// ------------------------------------------------------------------ 二、安全区 → 列数 / 行数（floor）

test('页格盒floor且丢弃末格的零头', () => {
  // 1532 ÷ 68 = 22.53 → 22 列；720 ÷ 68 = 10.59 → 10 行
  const box: GridBox = pageGridBoxOf(SAFE_W_PX, SAFE_H_PX, 68);
  assertEq(22, box.cols);
  assertEq(10, box.rows);
  // 末格弃掉的账：22×68 = 1496 ⇒ 右剩 36px；10×68 = 680 ⇒ 下剩 40px，都必须**不够一格**
  assertEq(36, SAFE_W_PX - box.cols * 68);
  assertEq(40, SAFE_H_PX - box.rows * 68);
  assertTrue(SAFE_W_PX - box.cols * 68 < 68 && SAFE_H_PX - box.rows * 68 < 68, '弃掉的零头必须不够一格');
  // 反面对照：改成 ceil 会给 23 列 / 11 行——那正是"补一个窄格"的走法
  assertFalse(box.cols === 23, '不许向上取整补格（那样会给 23 列）');
  assertFalse(box.rows === 11, '不许向上取整补格（那样会给 11 行）');
});

test('页格盒边界_余67px仍弃_正好整格不弃', () => {
  // 余 67px（差 1px 够一格）也弃 ⇒ 仍 22 列
  assertEq(22, pageGridBoxOf(22 * 68 + 67, SAFE_H_PX, 68).cols);
  // 宽正好 22 整格 ⇒ 余 0，列数不变（防"整格被当成余量"）
  assertEq(22, pageGridBoxOf(22 * 68, SAFE_H_PX, 68).cols);
});

test('120%档的列行数与退化输入', () => {
  const box120: GridBox = pageGridBoxOf(SAFE_W_PX, SAFE_H_PX, 76);
  assertEq(20, box120.cols); // 1532 ÷ 76 = 20.15（余 12px）
  assertEq(9, box120.rows);  // 720 ÷ 76 = 9.47（余 36px）
  // 格长没算出来那一帧给 1×1，不抛、不除零
  assertTrue(new GridBox(1, 1).equals(pageGridBoxOf(SAFE_W_PX, SAFE_H_PX, 0)));
  assertTrue(new GridBox(1, 1).equals(pageGridBoxOf(-10, -10, 68)));
  assertTrue(new GridBox(1, 1).equals(pageGridBoxOf(0, 0, 68)));
});

// ------------------------------------------------------------------ 三、指针 ↔ 格往返

test('指针逐个手算落格', () => {
  const cell: number = 68;
  // 局部坐标 = 指针 − 原点 (10,20)，再 floor ÷ 68：
  // (10,20)→(0,0)；(77,88)→(67,68)→(0,1)；(78,89)→(68,69)→(1,1)；
  // (486,420)→(476,400)→(7,5)；(1495,679)→(1485,659)→(21,9)
  const cases: Array<HudPageCell> = [
    new HudPageCell(0, 0), new HudPageCell(0, 1), new HudPageCell(1, 1),
    new HudPageCell(7, 5), new HudPageCell(21, 9)
  ];
  const xs: Array<number> = [10, 77, 78, 486, 1495];
  const ys: Array<number> = [20, 88, 89, 420, 679];
  for (let i = 0; i < cases.length; i++) {
    const got: HudPageCell = pageCellAtPointer(xs[i], ys[i], 10, 20, cell);
    assertEq(cases[i].token, got.token, `指针 (${xs[i]},${ys[i]}) 的格`);
  }
});

test('页格反算式给左上角', () => {
  const cell: number = 68;
  assertEq(0, pageCellOffsetPx(new HudPageCell(0, 0), cell).x);
  assertEq(0, pageCellOffsetPx(new HudPageCell(0, 0), cell).y);
  assertEq(68, pageCellOffsetPx(new HudPageCell(1, 1), cell).x);
  assertEq(68, pageCellOffsetPx(new HudPageCell(1, 1), cell).y);
  assertEq(7 * 68, pageCellOffsetPx(new HudPageCell(7, 5), cell).x);
  assertEq(5 * 68, pageCellOffsetPx(new HudPageCell(7, 5), cell).y);
  // 哨兵/负格按 0 贴原点（偏移不许为负）
  const sentinel: HudPointPx = pageCellOffsetPx(HudPageCell.DEFAULT, cell);
  assertEq(0, sentinel.x);
  assertEq(0, sentinel.y);
});

test('铺满区里每个格内点都往返到同一格', () => {
  const cell: number = 68;
  let checked: number = 0;
  for (let gx = 0; gx < 22; gx++) {
    for (let gy = 0; gy < 10; gy++) {
      // 每格取左上角与右下角前 1px 两个点
      for (let d = 0; d < 2; d++) {
        const dx: number = d === 0 ? 0 : 67;
        const px: number = gx * cell + dx;
        const py: number = gy * cell + dx;
        const c: HudPageCell = pageCellAtPointer(px, py, 0, 0, cell);
        assertEq(gx, c.col, `格内点 (${px},${py}) 的列号`);
        assertEq(gy, c.row, `格内点 (${px},${py}) 的行号`);
        const off: HudPointPx = pageCellOffsetPx(c, cell);
        assertTrue(off.x <= px && off.y <= py, '格左上角不许越过指针');
        assertTrue(px - off.x < cell && py - off.y < cell, '指针必须在格内（差不到一格）');
        checked++;
      }
    }
  }
  assertEq(22 * 10 * 2, checked);
});

test('越界指针下界钳0_上界不钳', () => {
  // 负的局部坐标钳到第 0 格（floor 与截断在 coerceAtLeast(0) 之后同答案）
  assertEq(new HudPageCell(0, 0).token, pageCellAtPointer(-5, -5, 0, 0, 68).token);
  assertEq(new HudPageCell(0, 0).token, pageCellAtPointer(-5, -5, 10, 20, 68).token);
  // 格长没算出来 ⇒ (0,0)，不除零
  assertEq(new HudPageCell(0, 0).token, pageCellAtPointer(500, 500, 0, 0, 0).token);
  // 越出安全区**不钳上界**：索引函数只管"哪一格"，钳盒子是调用方的事
  const outside: HudPageCell = pageCellAtPointer(2000, 900, 0, 0, 68);
  assertEq(29, outside.col); // 2000 ÷ 68
  assertEq(13, outside.row); // 900 ÷ 68
  const box: GridBox = pageGridBoxOf(SAFE_W_PX, SAFE_H_PX, 68);
  assertTrue(outside.col >= box.cols, '索引函数给出的列可能越出运行时盒子 ⇒ 调用方必须钳');
  assertTrue(outside.row >= box.rows, '索引函数给出的行可能越出运行时盒子 ⇒ 调用方必须钳');
});

// ------------------------------------------------------------------ 四、占用判据：整块不相交

test('占用判据_整块压住后续档_搭档只占起始档', () => {
  // 实测高（与上游同一批手算值）：姿态仪 144px ÷ 68px = 3 档
  const heights: Map<string, number> = new Map<string, number>();
  heights.set(e(CamPill.LEVEL).id, 144);
  heights.set(e(CamPill.VOLUME).id, 142);
  const heightOf = (entry: HudEntry): number => {
    const v: number | undefined = heights.get(entry.id);
    return v === undefined ? 0 : v;
  };
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set(new HudPageCell(0, 0).token, [e(CamPill.LEVEL)]);
  residents.set(new HudPageCell(0, 3).token, [e(CamPill.BT)]);

  // ① self = null（旧口径"有人就算占"）：配对块的三档 (0,0)(0,1)(0,2) + 蓝牙 (0,3)
  assertEq('0.0,0.1,0.2,0.3',
    Array.from(pageCellsBlocked(residents, null, new Set<string>(), heightOf, 68)).sort().join(','));
  // ② 蓝牙眼里：别人的三档全算占，自己那一档 (0,3) 不算
  assertEq('0.0,0.1,0.2',
    Array.from(pageCellsBlocked(residents, e(CamPill.BT), new Set<string>(), heightOf, 68)).sort().join(','));
  // ③ 搭档（音量表）眼里：起始档 (0,0) 不挡（合回配对的入口），块的中间两档照旧挡
  const mates: Set<string> = new Set<string>();
  mates.add(e(CamPill.LEVEL).id);
  assertEq('0.1,0.2,0.3',
    Array.from(pageCellsBlocked(residents, e(CamPill.VOLUME), mates, heightOf, 68)).sort().join(','));
  // ④ 哨兵格不占任何档（没摆过的条目不挡路）
  const sentinelResidents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  sentinelResidents.set(HudPageCell.DEFAULT.token, [e(CamPill.LEVEL)]);
  assertEq(0, pageCellsBlocked(sentinelResidents, null, new Set<string>(), heightOf, 68).size);
});

test('占用判据_候选跨格块的尾巴压别人锚点也算挡', () => {
  // 候选 (0,0) 是 144px ⇒ 3 档 (0,0)(0,1)(0,2)，其中 (0,1) 正是蓝牙的锚点 ⇒ 整块与占位集相交 ⇒ 挡
  const heights: Map<string, number> = new Map<string, number>();
  heights.set(e(CamPill.LEVEL).id, 144);
  const heightOf = (entry: HudEntry): number => {
    const v: number | undefined = heights.get(entry.id);
    return v === undefined ? 0 : v;
  };
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set(new HudPageCell(0, 1).token, [e(CamPill.BT)]);
  const blocked: Set<string> = pageCellsBlocked(residents, null, new Set<string>(), heightOf, 68);
  const block: Array<GridCell> = [new GridCell(0, 0), new GridCell(0, 1), new GridCell(0, 2)];
  assertEq(3, block.length);
  let hit: boolean = false;
  for (const c of block) {
    if (blocked.has(c.token)) {
      hit = true;
    }
  }
  assertTrue(hit, '候选三档里有一档被占（尾巴 (0,1) 压住蓝牙的锚点）');
  // 成对对照：同一颗换成 1 档（量不到高）时占位集只剩蓝牙自己那一档，候选 (0,0) 不再压人
  const flat: Set<string> = pageCellsBlocked(residents, null, new Set<string>(),
    (entry: HudEntry): number => 0, 68);
  assertEq(1, flat.size);
  assertTrue(flat.has(new HudPageCell(0, 1).token));
  assertFalse(flat.has(new HudPageCell(0, 0).token));
});

test('页格占用桥与Dock占用判据逐字同源', () => {
  // 页格 token 与 Dock 格 token 是同一编码（`0.2` / `-`），所以桥两侧的集合可以直接对撞：
  // 这条防"页格另写一份占用逻辑"——那份副本会在 mate 与跨度这两档与 Dock 分叉。
  const heights: Map<string, number> = new Map<string, number>();
  heights.set(e(CamPill.LEVEL).id, 144);
  const heightOf = (entry: HudEntry): number => {
    const v: number | undefined = heights.get(entry.id);
    return v === undefined ? 0 : v;
  };
  const residents: Map<string, Array<HudEntry>> = new Map<string, Array<HudEntry>>();
  residents.set('0.0', [e(CamPill.LEVEL)]);
  const asPage: Set<string> = pageCellsBlocked(residents, null, new Set<string>(), heightOf, 68);
  const asGrid: Set<string> = blockingCells(residents, null, new Set<string>(), heightOf, 68);
  assertEq(Array.from(asGrid).sort().join(','), Array.from(asPage).sort().join(','));
});

test('页格与Dock格的桥保留坐标与token', () => {
  const page: HudPageCell = new HudPageCell(3, 4);
  const grid: GridCell = pageCellToGridCell(page);
  assertEq(3, grid.col);
  assertEq(4, grid.row);
  const back: HudPageCell = gridCellToPageCell(grid);
  assertEq(page.token, back.token);
  // 两个哨兵写同一个 '-'（持久化 G 段直接复读的依据）
  assertEq(GridCell.DEFAULT.token, HudPageCell.DEFAULT.token);
  assertEq('-', HudPageCell.DEFAULT.token);
});

// ------------------------------------------------------------------ 五、存储层上限：护栏，不是把运行时格数写死

test('页格行列护栏宽过运行时盒子', () => {
  const box: GridBox = pageGridBoxOf(SAFE_W_PX, SAFE_H_PX, 68);
  assertEq(20, gridRowHardCap());
  assertEq(20, HudEntry.ALL.length);
  // 行上限 = 条目总数；列上限 = 条目数 × 2
  assertEq(20, pageGridRowCap());
  assertEq(40, pageGridColCap());
  // 桥：运行时的盒子必须落得进存储层的护栏，且护栏要真的宽过运行时（否则等于把运行时格数写死了）
  assertTrue(box.cols <= pageGridColCap(), '22 列必须落得进列护栏');
  assertTrue(box.rows <= pageGridRowCap(), '10 行必须落得进行护栏');
  assertTrue(pageGridColCap() > box.cols, '列护栏要宽过运行时');
});
