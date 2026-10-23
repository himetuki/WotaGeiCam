/**
 * 取景参考线（对齐上游 RefLineMaskTest.kt + RefLineHorizonTest.kt 全量）：
 * - 位掩码多选语义（开着再点 = 关、不串改别的位、逐颗点开 = 全开）；
 * - 水平线端点的**符号约定**（与 LevelMath.roll「正 = 屏幕右侧偏低」配对）。
 *
 * 水平线画反了，取景的人看到的引导与仪表读数正好相反——这是这两条用例存在的理由。
 */
import {
  CanvasBox, CanvasPoint, RefLine, RefLineType, horizonEndpoints, inscribedBox
} from '../../entry/src/main/ets/core/RefLine';

// ------------------------------------------------------------------ 位掩码（上游 RefLineMaskTest）

test('关回去得到原掩码', () => {
  const start: number = RefLine.bitOf(RefLineType.GRID) | RefLine.bitOf(RefLineType.CROSSHAIR);
  const opened: number = RefLine.toggle(start, RefLineType.DIAGONAL);
  assertEq(start, RefLine.toggle(opened, RefLineType.DIAGONAL));
});

test('开着的再点一次是关，不是保持', () => {
  const on: number = RefLine.toggle(0, RefLineType.HORIZON_LINE);
  assertTrue(RefLine.isOn(on, RefLineType.HORIZON_LINE));
  assertEq(0, RefLine.toggle(on, RefLineType.HORIZON_LINE));
  assertFalse(RefLine.isOn(RefLine.toggle(on, RefLineType.HORIZON_LINE), RefLineType.HORIZON_LINE));
});

test('逐颗点开等于全开', () => {
  let mask: number = 0;
  for (const e of RefLine.all()) {
    mask = RefLine.toggle(mask, e.type);
  }
  assertEq(RefLine.fullMask(), mask);
  assertEq(RefLine.all().length, RefLine.typesOf(mask).length);
  // 11 档 = 2047：位值是持久化契约，突变本行必红
  assertEq(2047, RefLine.fullMask(), '全开掩码必须是 2047');
});

test('点一颗不会串改别的线', () => {
  const base: number = RefLine.fullMask() & ~RefLine.bitOf(RefLineType.RED_TOP);
  const after: number = RefLine.toggle(base, RefLineType.RED_TOP);
  assertEq(RefLine.fullMask(), after);
  for (const e of RefLine.all()) {
    if (e.type !== RefLineType.RED_TOP) {
      assertTrue(RefLine.isOn(after, e.type), `${e.label} 应保持开着`);
    }
  }
});

test('全开态点掉一颗后计数跟着减', () => {
  const after: number = RefLine.toggle(RefLine.fullMask(), RefLineType.RED_MID);
  assertEq(RefLine.all().length - 1, RefLine.typesOf(after).length);
  assertFalse(RefLine.isOn(after, RefLineType.RED_MID));
});

test('位值表与上游逐值一致', () => {
  // 顺序与值都是持久化契约：改动会读回老用户的错误掩码
  assertEq(1, RefLine.bitOf(RefLineType.GRID_3X3));
  assertEq(2, RefLine.bitOf(RefLineType.DIAGONAL));
  assertEq(4, RefLine.bitOf(RefLineType.CROSSHAIR));
  assertEq(8, RefLine.bitOf(RefLineType.BROADCAST_SAFE));
  assertEq(16, RefLine.bitOf(RefLineType.SAFE_AREA));
  assertEq(32, RefLine.bitOf(RefLineType.GRID));
  assertEq(64, RefLine.bitOf(RefLineType.CENTER_MARK));
  assertEq(128, RefLine.bitOf(RefLineType.HORIZON_LINE));
  assertEq(256, RefLine.bitOf(RefLineType.RED_TOP));
  assertEq(512, RefLine.bitOf(RefLineType.RED_MID));
  assertEq(1024, RefLine.bitOf(RefLineType.RED_BOTTOM));
});

// ------------------------------------------------------------------ 水平线端点（上游 RefLineHorizonTest）

test('roll为零_水平线严格过心水平', () => {
  const pts: Array<CanvasPoint> = horizonEndpoints(100, 50, 50, 0);
  assertTrue(Math.abs(pts[0].y - 50) < 1e-4);
  assertTrue(Math.abs(pts[1].y - 50) < 1e-4);
  assertTrue(Math.abs(pts[0].x - 50) < 1e-4);
  assertTrue(Math.abs(pts[1].x - 150) < 1e-4);
});

test('正roll_右端抬起左端下沉', () => {
  const pts: Array<CanvasPoint> = horizonEndpoints(100, 50, 50, 10);
  assertTrue(pts[1].y < 50, '右端应在中心上方（正 roll = 右侧偏低）');
  assertTrue(pts[0].y > 50, '左端应在中心下方');
});

test('负roll镜像_左右抬向对调', () => {
  const pts: Array<CanvasPoint> = horizonEndpoints(100, 50, 50, -10);
  assertTrue(pts[0].y < 50);
  assertTrue(pts[1].y > 50);
});

test('过心不变量_两端点的中点恒为中心', () => {
  for (const roll of [-95, -45, 0, 30, 89]) {
    const pts: Array<CanvasPoint> = horizonEndpoints(100, 50, 50, roll);
    assertTrue(Math.abs((pts[0].x + pts[1].x) / 2 - 100) < 1e-4, `roll=${roll} 中点 x`);
    assertTrue(Math.abs((pts[0].y + pts[1].y) / 2 - 50) < 1e-4, `roll=${roll} 中点 y`);
  }
});

test('角度对称_端点关于水平轴镜像', () => {
  const up: Array<CanvasPoint> = horizonEndpoints(100, 50, 50, 10);
  const down: Array<CanvasPoint> = horizonEndpoints(100, 50, 50, -10);
  assertTrue(Math.abs(up[0].x - down[0].x) < 1e-4);
  assertTrue(Math.abs(up[0].y + down[0].y - 100) < 1e-3);
  assertTrue(Math.abs(up[1].x - down[1].x) < 1e-4);
  assertTrue(Math.abs(up[1].y + down[1].y - 100) < 1e-3);
});

// ------------------------------------------------------------------ 内接框（本侧补充）

test('内接框_画布比画面宽时按高度定框并居中', () => {
  // 画布 1000×500，画面 4:3（比画布「窄」）：框高=500、宽=666.7、左=(1000-666.7)/2
  const box: CanvasBox = inscribedBox(1000, 500, 4 / 3);
  assertTrue(Math.abs(box.height - 500) < 1e-6);
  assertTrue(Math.abs(box.width - 666.6667) < 1e-3);
  assertTrue(Math.abs(box.left - (1000 - box.width) / 2) < 1e-6);
  assertTrue(Math.abs(box.top - 0) < 1e-6);
});

test('内接框_非法宽高比铺满_非正画布返回空框', () => {
  const full: CanvasBox = inscribedBox(800, 600, 0);
  assertEq(0, full.left);
  assertEq(0, full.top);
  assertEq(800, full.width);
  assertEq(600, full.height);
  const nan: CanvasBox = inscribedBox(800, 600, Number.NaN);
  assertEq(800, nan.width);
  const empty: CanvasBox = inscribedBox(0, 600, 1.5);
  assertEq(0, empty.width);
  assertEq(0, empty.height);
});
