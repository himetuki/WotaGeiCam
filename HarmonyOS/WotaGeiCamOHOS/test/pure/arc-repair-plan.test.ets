/**
 * 抽帧计划（record/ArcRepairPlan.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/ArcRepairPlanTest.kt 的纯函数面（对齐上游 38d712a）。
 *
 * 15 条：空/负数、45@30→24、45@30→25、恒等直通、时长账、两档抽帧点、60→24 连续抽帧、
 * 保留集正逆自洽、负下标、进度边界、total=0、整数源 oracle 扫描（S∈8 档 × D∈2 档 ×
 * s≤50000 逐点等于独立旧式）、29.97 位次、25.4 可修。期望值一律手写字面量。
 *
 * 旧整数口径的独立参照（oracle）按历史公式逐字抄写——不得用被测实现反推。
 * 平台执行器（解码/编码/EGL/Player 面板）留 T29。
 */
import { ArcRepairPlan } from '../../entry/src/main/ets/record/ArcRepairPlan';

/** v2 旧 Int 口径的独立参照（逐字抄写历史公式） */
function legacyDstFrameCount(n: number, s: number, d: number): number {
  if (n <= 0) {
    return 0;
  }
  if (s <= 0 || d <= 0 || s <= d) {
    return n;
  }
  return Math.floor((n * d + s - 1) / s);
}

/** v2 旧 Int 口径的独立参照（isKept） */
function legacyIsKept(s: number, srcFps: number, dstFps: number): boolean {
  if (s < 0) {
    return false;
  }
  if (srcFps <= 0 || dstFps <= 0 || srcFps <= dstFps) {
    return true;
  }
  const j: number = Math.floor((s * dstFps + srcFps - 1) / srcFps);
  return Math.floor((j * srcFps) / dstFps) === s;
}

function keptIndices(n: number, num: number, den: number, dstFps: number): Array<number> {
  const out: Array<number> = [];
  for (let s: number = 0; s < n; s++) {
    if (ArcRepairPlan.isKept(s, num, den, dstFps)) {
      out.push(s);
    }
  }
  return out;
}

function droppedIndices(n: number, num: number, den: number, dstFps: number): Array<number> {
  const out: Array<number> = [];
  for (let s: number = 0; s < n; s++) {
    if (!ArcRepairPlan.isKept(s, num, den, dstFps)) {
      out.push(s);
    }
  }
  return out;
}

test('空与负数源帧数输出为零', (): void => {
  assertEq(0, ArcRepairPlan.dstFrameCount(0, 30, 1, 24), '0 帧');
  assertEq(0, ArcRepairPlan.dstFrameCount(-7, 30, 1, 24), '-7 帧');
  assertEq(0, ArcRepairPlan.of(-7, 30, 1, 24).dstFrames, 'of 负帧数归 0');
  assertEq(0, ArcRepairPlan.of(-7, 30, 1, 24).totalFrames(), 'totalFrames 同步归 0');
});

test('30到24每5抽1 45帧出36帧', (): void => {
  assertEq(36, ArcRepairPlan.dstFrameCount(45, 30, 1, 24), '公式');
  const p: ArcRepairPlan = ArcRepairPlan.of(45, 30, 1, 24);
  assertEq(36, p.dstFrames, 'of.dstFrames');
  assertEq(36, p.totalFrames(), 'totalFrames');
});

test('30到25每6抽1 45帧出38帧', (): void => {
  // ⌈45·25/30⌉ = ⌈37.5⌉ = 38：每 6 帧抽 1（丢 5,11,…），尾巴多保一枚
  assertEq(38, ArcRepairPlan.dstFrameCount(45, 30, 1, 25), '45@30→25');
});

test('源不快于目标时恒等直通 没有帧可抽', (): void => {
  assertEq(20, ArcRepairPlan.dstFrameCount(20, 24, 1, 24), '24→24');
  assertEq(20, ArcRepairPlan.dstFrameCount(20, 24, 1, 25), '24→25');
  assertEq(20, ArcRepairPlan.dstFrameCount(20, 0, 1, 24), '源 0 帧率直通');
  for (let s: number = 0; s < 20; s++) {
    assertTrue(ArcRepairPlan.isKept(s, 24, 1, 24), `s=${s} 恒等全保留`);
  }
});

test('输出时长与源恒等 30到24误差不足一帧', (): void => {
  // 保留帧数/D ≈ 源帧数/S：|N·D − K·S| < S（45 帧：36·30=1080 = 45·24=1080 严格恒等）
  assertEq(45 * 24, 36 * 30, '45 帧严格恒等');
  for (let n: number = 1; n <= 300; n++) {
    const k: number = ArcRepairPlan.dstFrameCount(n, 30, 1, 24);
    assertTrue(Math.abs(n * 24 - k * 30) < 30, `N=${n} 时长账越界`);
  }
});

test('30到24的抽帧点每5帧一个 不扎堆', (): void => {
  const drops: Array<number> = droppedIndices(45, 30, 1, 24);
  assertEq(9, drops.length, '抽 9 帧');
  const expected: Array<number> = [4, 9, 14, 19, 24, 29, 34, 39, 44];
  for (let i: number = 0; i < expected.length; i++) {
    assertEq(expected[i], drops[i], `抽帧点 ${i}`);
  }
});

test('30到25的抽帧点每6帧一个', (): void => {
  const drops: Array<number> = droppedIndices(45, 30, 1, 25);
  const expected: Array<number> = [5, 11, 17, 23, 29, 35, 41];
  assertEq(expected.length, drops.length, '抽 7 帧');
  for (let i: number = 0; i < expected.length; i++) {
    assertEq(expected[i], drops[i], `抽帧点 ${i}`);
  }
});

test('60到24允许连续抽帧 留集仍与帧数自洽', (): void => {
  const kept: Array<number> = keptIndices(60, 60, 1, 24);
  assertEq(ArcRepairPlan.dstFrameCount(60, 60, 1, 24), kept.length, '留集大小=帧数');
  // ⌊j·2.5⌋ 序列里有跳 2 的位次 ⇒ 必然出现连续抽帧（3,4 / 8,9 / …）
  let hasConsecutive: boolean = false;
  for (let i: number = 1; i < kept.length; i++) {
    if (kept[i] - kept[i - 1] > 2) {
      hasConsecutive = true;
    }
  }
  assertTrue(hasConsecutive, '60→24 必有连续抽帧');
});

test('保留集等于输出下标的floor映射 正逆自洽', (): void => {
  const sizes: Array<number> = [1, 2, 5, 45, 90, 301];
  for (const n of sizes) {
    const plan: ArcRepairPlan = ArcRepairPlan.of(n, 30, 1, 24);
    const keptByMap: Array<number> = [];
    for (let j: number = 0; j < plan.dstFrames; j++) {
      const s: number = Math.floor((j * 30) / 24);
      assertTrue(s < n, `j=${j} 映出的源下标越界（N=${n}）`);
      keptByMap.push(s);
    }
    const keptByTest: Array<number> = keptIndices(n, 30, 1, 24);
    assertEq(keptByMap.length, keptByTest.length, `N=${n} 两集合大小`);
    for (let i: number = 0; i < keptByMap.length; i++) {
      assertEq(keptByMap[i], keptByTest[i], `N=${n} 第 ${i} 位`);
    }
  }
});

test('负下标防御为不保留', (): void => {
  assertFalse(ArcRepairPlan.isKept(-1, 30, 1, 24), '-1 不保留');
  assertFalse(ArcRepairPlan.isKept(-100, 60, 1, 24), '-100 不保留');
  // 恒等直通档下负下标仍不保留（负下标判断先于直通判断）
  assertFalse(ArcRepairPlan.isKept(-1, 24, 1, 24), '直通档负下标');
});

test('进度在边界与超界处被钳制', (): void => {
  assertEq(0, ArcRepairPlan.progressOf(0, 36), '起点');
  assertEq(0.5, ArcRepairPlan.progressOf(18, 36), '中段');
  assertEq(1, ArcRepairPlan.progressOf(36, 36), '终点');
  assertEq(1, ArcRepairPlan.progressOf(40, 36), '超界钳 1');
  assertEq(0, ArcRepairPlan.progressOf(-3, 10), '负进度钳 0');
});

test('总帧数为0时进度归零而不是除零', (): void => {
  assertEq(0, ArcRepairPlan.progressOf(0, 0), '0/0');
  assertEq(0, ArcRepairPlan.progressOf(5, 0), '5/0');
  assertEq(0, ArcRepairPlan.progressOf(5, -1), '5/-1');
});

test('整数源逐点等于旧口径 独立oracle扫描', (): void => {
  // S 覆盖全部整数档 + 480（钳制上限），s 扫到 50000：8×2×50001×2 个断言逐点相等
  const rates: Array<number> = [24, 25, 30, 50, 60, 120, 240, 480];
  const dsts: Array<number> = [24, 25];
  for (const s of rates) {
    for (const d of dsts) {
      for (let n: number = 0; n <= 50000; n++) {
        assertEq(legacyDstFrameCount(n, s, d), ArcRepairPlan.dstFrameCount(n, s, 1, d),
          `S=${s} D=${d} n=${n}`);
        assertEq(legacyIsKept(n, s, d), ArcRepairPlan.isKept(n, s, 1, d),
          `S=${s} D=${d} s=${n}`);
      }
    }
  }
});

test('NTSC源30000比1001转24 帧数721 前9保留位次漂移被钉住', (): void => {
  // 29.97 的真值位次：抽帧点从整数口径的 {4,9,14,…} 推到 {5,10,15,…}——位次集合才是
  // 分叉的本体，只断帧数测不出
  const plan: ArcRepairPlan = ArcRepairPlan.of(900, 30000, 1001, 24);
  assertEq(721, plan.dstFrames, '900 帧 → 721');
  const kept: Array<number> = keptIndices(900, 30000, 1001, 24);
  assertEq(721, kept.length, '留集 721');
  const first9: Array<number> = [0, 1, 2, 3, 4, 6, 7, 8, 9];
  for (let i: number = 0; i < first9.length; i++) {
    assertEq(first9[i], kept[i], `真值前 9 第 ${i} 位`);
  }
  // 对照：整数 30fps 口径在这处与真值分叉
  const intKept: Array<number> = keptIndices(900, 30, 1, 24);
  const intFirst9: Array<number> = [0, 1, 2, 3, 5, 6, 7, 8, 10];
  for (let i: number = 0; i < intFirst9.length; i++) {
    assertEq(intFirst9[i], intKept[i], `整数口径前 9 第 ${i} 位`);
  }
});

test('25点4fps案 真值口径可修 整数口径误判NO_NEED', (): void => {
  // D=24：两口径都判"需修"，但帧数按真值 720（整数 25fps 口径会出 732）
  assertEq(720, ArcRepairPlan.dstFrameCount(762, 254, 10, 24), '25.4→24 = 720');
  assertEq(732, ArcRepairPlan.dstFrameCount(762, 25, 1, 24), '整数 25→24 = 732');
  // D=25：整数口径 25 ≤ 25 → 恒等直通（假阴性 NO_NEED）；真值 25.4 > 25 → 可修
  assertEq(762, ArcRepairPlan.dstFrameCount(762, 25, 1, 25), '整数 25→25 直通');
  assertEq(750, ArcRepairPlan.dstFrameCount(762, 254, 10, 25), '25.4→25 = 750');
});
