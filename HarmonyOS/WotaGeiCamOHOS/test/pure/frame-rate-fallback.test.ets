/**
 * fps 退档口径（对齐上游 FrameRateFallbackTest.kt 的纯函数全表 6 条 + 突变自证 1 条）。
 *
 * 缺陷本体（上游 2026-10-10 S3-S3-01）：旧口径 `usable > r.lo` 对固定档 `[f,f]` 恒为 false，
 * 所有固定档被排除；LEGACY 设备只报固定档，于是「可达上限」必然落到兜底
 * `minOfOrNull { it.hi }` = 全表**最小**上界。用户选 120fps、设备能到 60，却被退到 15fps。
 *
 * 跳过（登记）：上游同文件 14 个 @Test 里，除本文件翻译的 7 条外，其余是扫描
 * `Camera2Engine.ceilingFps`/`syncFpsPick` 源码结构的接线守卫与白平衡单调钟守卫。鸿蒙侧没有
 * `Camera2Engine` 这套引擎结构（相机引擎是 `camera/GlCameraEngine.ets`，帧率下发走
 * `RequestApplier.pickFps`），**没有对应接线点**，故不翻译、也不伪造一个可被扫描的假调用点。
 */
import { RangeI } from '../../entry/src/main/ets/core/CameraAbility';
import { WotaTiers } from '../../entry/src/main/ets/core/Constants';
import { FrameRateFallback } from '../../entry/src/main/ets/core/FrameRateFallback';

/**
 * 「计划→行为」桥：用例一律经它落到真函数上，测试里不许另写一份算法
 * （否则「改实现不改用例」时表照样全绿，正是静态证明最怕的假绿）。
 */
function ceiling(ranges: Array<RangeI>, cap: number): number {
  return FrameRateFallback.ceilingFps(ranges, cap);
}

/** 上游 Int.MAX_VALUE 的 ArkTS 等价物：远超任何帧率档，Math.min 在此量级仍精确 */
const NO_CAP: number = Number.MAX_SAFE_INTEGER;

/** 固定档表（LEGACY 设备按契约只报这种；cap 取无高帧率能力时的封顶） */
const FIXED_ONLY: Array<RangeI> = [
  new RangeI(15, 15), new RangeI(24, 24), new RangeI(25, 25), new RangeI(30, 30),
  new RangeI(60, 60), new RangeI(120, 120), new RangeI(240, 240)
];

/** 混合表：可变范围 + 固定档 + 一条整体在 cap 之上的范围 */
const MIXED: Array<RangeI> = [
  new RangeI(7, 30), new RangeI(30, 60), new RangeI(60, 60), new RangeI(120, 120), new RangeI(200, 240)
];

// ------------------------------------------------------------------ 纯函数全表（上游 6 条）

test('全固定档_退到cap封顶的固定档而不是全表最小值', () => {
  // 缺陷形态：旧口径此处返回 15（minOfOrNull{hi}），用户选 120fps 却拿到 15fps
  assertEq(60, ceiling(FIXED_ONLY, 60), '全固定档 + cap=60：120/240 被截断不可用，可达上限是 60');
  assertEq(60, ceiling([new RangeI(30, 30), new RangeI(60, 60)], 60), 'cap=60 时 60 档本身可用');
});

test('全固定档_有高帧率能力时退到全表最高固定档', () => {
  // 高帧率设备不设 cap：[240,240] 自然可用
  assertEq(240, ceiling(FIXED_ONLY, NO_CAP), 'cap 不设限时取全表最高固定档 240');
  assertEq(120, ceiling([new RangeI(30, 30), new RangeI(120, 120)], NO_CAP), '120 档设备退到 120');
});

test('混合表_可变范围按截断后的上界参与取最大', () => {
  assertEq(60, ceiling(MIXED, 60),
    '[7,30]→30、[30,60]→60、[60,60]→60、[120,120] 被截断、[200,240] 整段在 cap 之上');
  assertEq(240, ceiling(MIXED, NO_CAP), 'cap 不设限时 [200,240] 可用');
});

test('被cap截断后仍可用与不可用的分界', () => {
  // [60,120] 截断到 60 仍落在 [60,120] 内 ⇒ 可用，取 60
  assertEq(60, ceiling([new RangeI(60, 120)], 60));
  // [61,120] 截断到 60 已落在范围之外 ⇒ 不可用
  assertEq(0, ceiling([new RangeI(61, 120)], 60), '截断后低于 lo 的范围必须排除');
  // 同一条范围在不设 cap 下必然可用
  assertEq(120, ceiling([new RangeI(61, 120)], NO_CAP));
});

test('无退档目标返回0而不是随便一个数', () => {
  // 空表：旧兜底给 25，那是个设备根本没报过的帧率
  assertEq(0, ceiling([], 60), '空能力表不得凭空造档位');
  // 全部范围都在 cap 之上
  assertEq(0, ceiling([new RangeI(120, 120), new RangeI(240, 240)], 60), '全表在 cap 之上 = 无退档目标');
  // CLOSED 占位（能力缺失时的 [0,0]）不得被当成可用
  assertEq(0, ceiling([RangeI.CLOSED], 60));
  assertEq(60, ceiling([RangeI.CLOSED, new RangeI(30, 30), new RangeI(60, 60)], 60),
    'CLOSED 混在表里不影响其他范围');
});

test('cap口径取自产品域常量而非机型数值', () => {
  // 无高帧率能力时封顶 60 是 WotaTiers 的产品域分水岭，纯函数本身不内置任何机型值
  assertEq(WotaTiers.HIGH_SPEED_FPS, ceiling(FIXED_ONLY, WotaTiers.HIGH_SPEED_FPS));
  assertEq(30, ceiling(FIXED_ONLY, 59), 'cap=59 时 60 档整段不可用，可达上限落到 30');
});

// ------------------------------------------------------------------ 突变自证（尺子自己能红）

/**
 * 旧口径突变体：照抄缺陷形态（`usable > r.lo` + `minOfOrNull{hi} ?: 25`）。
 * ArkTS 没有 minOfOrNull，用循环求最小 hi 等价复刻；`?: 25` 的兜底是缺陷的第二半。
 */
function legacyCeiling(ranges: Array<RangeI>, cap: number): number {
  let best: number = 0;
  for (const r of ranges) {
    const usable: number = Math.min(r.hi, cap);
    if (usable > r.lo && usable > best) {
      best = usable;
    }
  }
  if (best > 0) {
    return best;
  }
  let minHi: number = 0;
  let seeded: boolean = false;
  for (const r of ranges) {
    if (!seeded || r.hi < minHi) {
      minHi = r.hi;
      seeded = true;
    }
  }
  return seeded ? minHi : 25;
}

interface LegacyCase {
  ranges: Array<RangeI>;
  cap: number;
  expect: number;
}

test('尺子自己能红_旧口径突变体必在全表上偏离', () => {
  const cases: Array<LegacyCase> = [
    { ranges: FIXED_ONLY, cap: 60, expect: 60 },
    { ranges: FIXED_ONLY, cap: NO_CAP, expect: 240 },
    { ranges: MIXED, cap: 60, expect: 60 },
    { ranges: MIXED, cap: NO_CAP, expect: 240 },
    { ranges: [new RangeI(60, 120)], cap: 60, expect: 60 },
    { ranges: [new RangeI(61, 120)], cap: 60, expect: 0 },
    { ranges: [new RangeI(61, 120)], cap: NO_CAP, expect: 120 },
    { ranges: [], cap: 60, expect: 0 },
    { ranges: [new RangeI(120, 120), new RangeI(240, 240)], cap: 60, expect: 0 },
    { ranges: [RangeI.CLOSED], cap: 60, expect: 0 },
    { ranges: [RangeI.CLOSED, new RangeI(30, 30), new RangeI(60, 60)], cap: 60, expect: 60 },
    { ranges: FIXED_ONLY, cap: 59, expect: 30 }
  ];
  let diffs: number = 0;
  for (const c of cases) {
    if (legacyCeiling(c.ranges, c.cap) !== c.expect) {
      diffs++;
    }
  }
  assertTrue(diffs > 0,
    `旧口径突变体必须在至少一条用例上偏离本表（否则这张表测不出本缺陷，尺子失效）：偏离 ${diffs}/${cases.length} 条`);
  // 本缺陷的核心那一格必须真的被抓住（不能只靠边角用例凑数）
  assertEq(15, legacyCeiling(FIXED_ONLY, 60), 'S3-01 本体：全固定档 + cap=60，旧口径退成最小值 15');
  assertEq(60, ceiling(FIXED_ONLY, 60), 'S3-01 本体：新口径退到 60');
  // 只删掉兜底（谓词已修好、兜底仍返回最小值）也必须被抓出来
  let noFallback: number = 0;
  for (const c of cases) {
    if (c.cap === 60 && legacyCeiling(c.ranges, c.cap) === legacyMinHi(c.ranges)) {
      noFallback++;
    }
  }
  assertTrue(noFallback > 0, "全表至少要有一条能区分'修好谓词但留着旧兜底'的用例");
});

/** 旧兜底的取值：全表最小 hi（上游 `ranges.minOfOrNull { it.hi }`） */
function legacyMinHi(ranges: Array<RangeI>): number {
  let minHi: number = 0;
  let seeded: boolean = false;
  for (const r of ranges) {
    if (!seeded || r.hi < minHi) {
      minHi = r.hi;
      seeded = true;
    }
  }
  return seeded ? minHi : 25;
}
