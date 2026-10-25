/**
 * PTS-GRID 批（2026-10-25）：编码器 PTS 网格纯算式全表——对拍上游
 * NtscGrid.ntscGridStepNs / GlRenderEngine.encoderSlotPtsNs / encoderAdaptivePtsNs /
 * encoderSlotGap。期望值一律手写字面量。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { EncoderPts } from '../../entry/src/main/ets/core/EncoderPts';

function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/ui/HudSpec.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

function sourceText(rel: string): string {
  return readFileSync(resolve(srcRoot(), rel), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

const SEC: number = 1000000000;

// ------------------------------------------------------------------ NTSC 步长表

test('NTSC步长_三档逐字_其余为零', () => {
  assertEq(EncoderPts.ntscGridStepNs(24), 41708333, '241000/1001 档');
  assertEq(EncoderPts.ntscGridStepNs(30), 33366667, '30000/1001 档');
  assertEq(EncoderPts.ntscGridStepNs(60), 16683333, '60000/1001 档');
  // 25/50 是 exact 档、120/240 不进表、未知档一律 0
  for (const f of [0, 25, 50, 120, 240, 23, 31]) {
    assertEq(EncoderPts.ntscGridStepNs(f), 0, `不进表：${f}`);
  }
});

test('NTSC步长_比整数步长更接近真周期', () => {
  // 23.976 真周期 = 1e9×1001/24000 = 41708333.33…；整数档 1e9/24 = 41666666
  const ntsc: number = EncoderPts.ntscGridStepNs(24);
  const integer: number = EncoderPts.integerStepNs(24);
  assertTrue(Math.abs(ntsc - 41708333) <= 1, 'NTSC 步长贴真周期');
  assertTrue(ntsc > integer, '比整数步长大（0.1% 系统性差）');
  // 683 帧累积差 = 41667ns × 683 ≈ 28.5ms（0.1% 系统性差，一轮 28.7s 录制同量级）
  const drift: number = (ntsc - integer) * 683;
  assertTrue(drift > 20000000 && drift < 40000000, `683 帧累计差应在数十毫秒量级，实测 ${drift}ns`);
});

// ------------------------------------------------------------------ 网格落戳内核

test('网格落戳_落网格_取自真实时钟_不回退', () => {
  const step: number = EncoderPts.integerStepNs(30); // 33333333
  // (now-base)=100ms → 落到第 3 格（99999999）
  assertEq(EncoderPts.encoderSlotPtsNs(0, 100000000, 30, 0), 99999999);
  // 同格再来一帧：不前进（允许相等），绝不回退
  assertEq(EncoderPts.encoderSlotPtsNs(0, 100000000, 30, 99999999), 99999999);
  assertEq(EncoderPts.encoderSlotPtsNs(0, 99000000, 30, 99999999), 99999999, '退格必须夹地板');
});

test('网格落戳_时钟回退与不可用档', () => {
  // elapsed<=0 → 退基准（pts=base=500），再与上一枚取大 → 500（基准高于地板）
  assertEq(EncoderPts.encoderSlotPtsNs(500, 400, 30, 123), 500, 'elapsed<=0 退基准');
  assertEq(EncoderPts.encoderSlotPtsNs(100, 400, 30, 123), 123, '退基准后仍被地板夹住');
  assertEq(EncoderPts.encoderSlotPtsNs(0, SEC, 0, 777), 777, 'fps<=0 原样返回上一枚');
});

// ------------------------------------------------------------------ 自适应接管（形态 B）

test('自适应_首帧与段首退化逐字同旧口径', () => {
  const step: number = EncoderPts.integerStepNs(24);
  // frameIndex=0：走名义网格（不是 base+elapsed）——上游「必须早于接管判定」的落点
  const first: number = EncoderPts.encoderAdaptivePtsNs(0, 50000, step, 0, 0);
  assertEq(first, 0, '首帧落在第 0 格');
  assertEq(first, EncoderPts.encoderSlotPtsNs(0, 50000, 24, 0), '与名义网格逐字相同');
});

test('自适应_源慢接管_帧长恒定无空洞', () => {
  const step: number = EncoderPts.integerStepNs(24); // 41666666
  // 源实到 23.75fps：帧间隔 ≈42105263ns。frameIndex=5 → 名义格只到 5×step=208333330
  const now: number = 5 * 42105263 + 1000; // 210527315
  const pts: number = EncoderPts.encoderAdaptivePtsNs(0, now, step, 208333330, 5);
  assertEq(pts, now, '接管：PTS 跟真实 elapsed');
  assertEq(EncoderPts.encoderSlotGap(208333330, pts, 24), 0, '接管后无空档（帧长均匀）');
  assertTrue(pts - 208333330 < step, '相邻差 < 一格（接管而非跳格）');
});

test('自适应_源快走名义网格_行为不变', () => {
  const step: number = EncoderPts.integerStepNs(24);
  // frameIndex=10 → 名义已到 10×step=416666660；源更快，elapsed 更小
  const now: number = 380000000;
  assertEq(EncoderPts.encoderAdaptivePtsNs(0, now, step, 375000000, 10), 375000000,
    '不接管：沿用名义网格（与旧口径逐字）');
});

test('自适应_不可用步长与时钟回退', () => {
  assertEq(EncoderPts.encoderAdaptivePtsNs(0, SEC, 0, 12345, 3), 12345, 'step<=0 原样');
  assertEq(EncoderPts.encoderAdaptivePtsNs(1000, 500, 33333333, 777, 2), 1000, 'elapsed<=0 退基准');
  assertEq(EncoderPts.encoderAdaptivePtsNs(100, 500, 33333333, 777, 2), 777, '退基准后仍夹地板');
});

// ------------------------------------------------------------------ 空档与实测帧率

test('空档_只数不改', () => {
  const step: number = EncoderPts.integerStepNs(24);
  assertEq(EncoderPts.encoderSlotGap(0, 5 * step, 24), 0, '首帧无上一枚');
  assertEq(EncoderPts.encoderSlotGap(step, 2 * step, 24), 0, '满一格无空档');
  assertEq(EncoderPts.encoderSlotGap(step, step, 24), 0, '同格多帧');
  assertEq(EncoderPts.encoderSlotGap(step, 4 * step, 24), 2, '跳三格 = 空 2 格');
  assertEq(EncoderPts.encoderSlotGap(step, step - 1, 24), 0, '未前进');
  assertEq(EncoderPts.encoderSlotGap(step, 2 * step, 0), 0, '档位不可用');
});

test('实测帧率_厘帧与零边界', () => {
  assertEq(EncoderPts.measuredFpsCenti(0, SEC), 0, '零帧');
  assertEq(EncoderPts.measuredFpsCenti(5, 0), 0, '零跨度');
  assertEq(EncoderPts.measuredFpsCenti(5, -1), 0, '负跨度');
  assertEq(EncoderPts.measuredFpsCenti(2400, 100 * SEC), 2400, '24.00fps');
  // 683 帧 / 28.79s ≈ 23.72fps（上游真机取证的同量级读数）
  const centi: number = EncoderPts.measuredFpsCenti(683, 28790000000);
  assertTrue(centi > 2300 && centi < 2400, `实测应落在 23.xx，实际 ${centi}`);
});

// ------------------------------------------------------------------ 接线守卫

test('接线_纯逻辑零平台import_星号唯一真源', () => {
  const core: string = sourceText('entry/src/main/ets/core/EncoderPts.ets');
  assertTrue(core.indexOf('@kit') < 0 && core.indexOf('@ohos') < 0, '必须零平台 import');
  // 网格算术只有一份：落网格算式只能在私有内核里出现一次（禁两处各写一遍）
  assertEq((core.match(/Math\.floor\(elapsed \/ stepNs\)/g) ?? []).length, 1,
    '落网格算式唯一（游标内核）');
  assertTrue((core.match(/slotGridPtsNs\(/g) ?? []).length >= 3,
    '私有内核被两个调用点复用');
  const raw: string = readFileSync(resolve(srcRoot(), 'entry/src/main/ets/core/EncoderPts.ets'), 'utf8');
  assertTrue(raw.indexOf('随真机批落地并验收') >= 0,
    'native 接线必须登记为真机面（不可静态验证）');
});
