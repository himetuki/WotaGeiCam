/**
 * 等号档曝光纠偏纯逻辑守卫——对拍上游 ExposureCorrectionTest.kt（5 用例）+
 * ExposureCorrectionPlanTest.kt（7 用例）（2026-11-01 用户定版，docs/plan/31）。
 *
 * 钉三件事：①手动等号档**永不发等号值**（探测量程除外）；②纠偏只动曝光时长——
 * AE 状态、帧周期、ISO 全部原样；③总线档位值原样透传（发送侧纠偏不改用户快门）。
 * Plan 段直接驱动本侧 RequestApplier.exposurePlan（同一份纯函数，下发适配层也吃它）。
 */
import { ExposureCorrection, ReadoutLatch } from '../../entry/src/main/ets/core/ExposureCorrection';
import { AeMode, AfMode, Flash, Rect, Stabilize, WbPreset } from '../../entry/src/main/ets/core/Constants';
import { FpsPick, RangeI } from '../../entry/src/main/ets/core/CameraAbility';
import { ExposurePlan, Snapshot, State, exposurePlan } from '../../entry/src/main/ets/camera/RequestApplier';

// ------------------------------------------------------------------ 常量对拍

const T24: number = 41_666_666;
const T25: number = 40_000_000;

function ms(n: number): number {
  return n * 1_000_000;
}

// ---------------------------------------------------------- 适用判定（与 UI 开关露出域同一条线）

test('适用判定=强制档且满窗（1/24@24、1/25@25、25fps+1/24 命中；1/25@24 不命中）', () => {
  assertTrue(ExposureCorrection.applicable(T24, 24), '1/24@24 满窗');
  assertTrue(ExposureCorrection.applicable(T25, 25), '1/25@25 满窗');
  assertTrue(ExposureCorrection.applicable(T24, 25), '25fps+1/24 被钳到 40ms == 帧周期，同样是等号');
  assertFalse(ExposureCorrection.applicable(T25, 24), '1/25@24 不满窗（40 < 41.67），读出本来就有余量');
  assertFalse(ExposureCorrection.applicable(33_333_333, 30), '非强制档不命中（UI 开关只在 1/24、1/25 露出，行为与入口同域）');
  assertFalse(ExposureCorrection.applicable(20_000_000, 25), '短快门（远小于帧周期）不命中');
});

// ---------------------------------------------------------- 自动余量：下限 / 上限 / 安全系数

test('自动余量：Δ缺失/0 走 5% 下限，Δ+1ms 安全系数，顶 20% 封顶', () => {
  assertEq(2_000_000, ExposureCorrection.autoMarginNs(undefined, T25), '守约机（Δ 缺失）走 5% 下限');
  assertEq(2_000_000, ExposureCorrection.autoMarginNs(0, T25), 'Δ=0（实测没拉长）同样走下限');
  assertEq(7_000_000, ExposureCorrection.autoMarginNs(6_000_000, T25), 'Δ+1ms 安全系数');
  assertEq(8_000_000, ExposureCorrection.autoMarginNs(30_000_000, T25), 'Δ 顶到 20% 上限封顶');
  assertEq(2_000_000, ExposureCorrection.autoMarginNs(-3_000_000, T25), '负 Δ 归 0 后走下限（没拉长不是负拉长）');
  // 24fps 帧周期 41_666_666：floor = 2_083_333、cap = 8_333_333
  assertEq(2_083_333, ExposureCorrection.autoMarginNs(undefined, T24));
  assertEq(8_333_333, ExposureCorrection.autoMarginNs(99_000_000, T24));
});

// ---------------------------------------------------------- 纠偏合成：永不越窗、永不归零

test('纠偏合成：满窗−余量；手动 0=等号直发；巨额纠偏钳 1ns 下限', () => {
  assertEq(38_000_000, ExposureCorrection.correctedExposureNs(T25, 2_000_000, T25));
  assertEq(T25, ExposureCorrection.correctedExposureNs(T25, 0, T25), '手动 0 = 满窗直发（等号），与改版前行为一致');
  assertEq(39_583_333, ExposureCorrection.correctedExposureNs(T24, 2_083_333, T24),
    '1/24@24：满窗 41_666_666 − 5% 下限 2_083_333 = 39_583_333');
  assertEq(1, ExposureCorrection.correctedExposureNs(T25, 999_999_999, T25), '巨额纠偏不许把曝光压到 0/负');
});

test('手动滑杆域：负值区已去掉（钳 0），上限 +8ms', () => {
  assertEq(0, ExposureCorrection.clampManualNs(-99_000_000), '负值区已去掉：手改 prefs 的负值钳回 0');
  assertEq(8_000_000, ExposureCorrection.clampManualNs(99_000_000));
  assertEq(500_000, ExposureCorrection.clampManualNs(500_000));
  assertEq(0, ExposureCorrection.clampManualNs(0));
});

// ---------------------------------------------------------- 探测折算：中位数抗抖动、负 Δ 归 0

test('探测折算：偶数取中间两数平均、奇数取中位、快机归 0、空样本 0', () => {
  assertEq(ms(3), ExposureCorrection.probeDeltaNs([ms(42), ms(44)], T25),
    '偶数个取中间两数平均：42、44 → 43ms ⇒ Δ=3ms');
  assertEq(ms(2), ExposureCorrection.probeDeltaNs([ms(42), ms(42), ms(200), ms(42), ms(42)], T25),
    '奇数个取中位：编码器启停那一拍 200ms 长尾不污染结论');
  assertEq(0, ExposureCorrection.probeDeltaNs([ms(39), ms(39)], T25), '本机跑得比请求还快 ⇒ Δ 归 0');
  assertEq(0, ExposureCorrection.probeDeltaNs([], T25), '空样本 = 没测到 ⇒ 0（走下限）');
});

// ------------------------------------------------------------------ exposurePlan 消费（集成守卫）

/** 与上游 ExposureCorrectionPlanTest.state() 同参的最小 State（能力域与上游一致） */
function planState(): State {
  return new State({
    activeArray: new Rect(0, 0, 4000, 3000),
    isoMin: 100, isoMax: 16000,
    exposureMinNs: 1_000_000, exposureMaxNs: 667_847_000,
    evMinSteps: -4, evMaxSteps: 4,
    fpsRanges: [new RangeI(5, 30)],
    maxRegionsAf: 1, maxRegionsAe: 1,
    manualFocusMaxDiopter: 10, zoomMin: 1, zoomMax: 8,
    useZoomRatio: false, flashAvailable: true, oisAvailable: true, eisAvailable: true,
    highSpeed: false, eisBlockedBySize: false, evStep: 0, focusDistanceSupported: false, wbManualSupported: true
  });
}

function planSnapshot(shutterNs: number, fps: number, auto: boolean, manualNs: number,
  deltaNs: number | undefined, probeActive: boolean): Snapshot {
  return new Snapshot({
    aeMode: AeMode.MANUAL, iso: 800, shutterNs: shutterNs, evSteps: 0,
    fps: new FpsPick(fps, fps, true), fpsTarget: fps,
    afMode: AfMode.CONTINUOUS_VIDEO, focusLocked: false, focusLockPoint: undefined,
    focusDiopter: 0, zoom: 1, wbPreset: WbPreset.AUTO, kelvin: 5500, tint: 0,
    flash: Flash.OFF, stabilize: Stabilize.OFF, tapPoint: undefined,
    correctionAuto: auto, correctionManualNs: manualNs, readoutDeltaNs: deltaNs, probeActive: probeActive
  });
}

test('1/25@25 默认自动：满窗 − 5% 下限 2ms = 38ms，永不发等号值；AE 状态/帧周期原样', () => {
  const plan: ExposurePlan = exposurePlan(planState(), planSnapshot(T25, 25, true, 0, undefined, false));
  assertEq(38_000_000, plan.exposureNs, '满窗 − 下限，永不发等号值');
  assertEq(T25, plan.frameDurationNs, '帧周期 = 节奏真值，不许动');
  assertEq(0, plan.aeMode, 'AE_OFF（Camera2 契约值）');
  assertTrue(plan.sensitivity !== undefined, '手动档 ISO 必须在册');
  assertEq(0, plan.aeComp, 'AE_OFF 下补偿恒 0（纠偏不改变关 AE 的状态）');
  assertFalse(plan.aeLock);
});

test('1/24@24：满窗 − 2_083_333 = 39_583_333', () => {
  const plan: ExposurePlan = exposurePlan(planState(), planSnapshot(T24, 24, true, 0, undefined, false));
  assertEq(39_583_333, plan.exposureNs);
  assertEq(T24, plan.frameDurationNs);
});

test('闩锁 Δ 提升余量：+1ms 安全系数、20% 封顶', () => {
  assertEq(33_000_000, exposurePlan(planState(), planSnapshot(T25, 25, true, 0, 6_000_000, false)).exposureNs,
    'Δ=6ms ⇒ 6+1=7ms 余量');
  assertEq(32_000_000, exposurePlan(planState(), planSnapshot(T25, 25, true, 0, 30_000_000, false)).exposureNs,
    'Δ=30ms ⇒ 顶到 20% 上限 8ms ⇒ 32ms');
});

test('探测中发等号（这就是量 Δ 的量程）；只允许出现在预览态', () => {
  const plan: ExposurePlan = exposurePlan(planState(), planSnapshot(T25, 25, true, 0, undefined, true));
  assertEq(T25, plan.exposureNs);
  assertEq(T25, plan.frameDurationNs);
  assertEq(0, plan.aeMode);
});

test('关自动走手动滑杆：+2ms/+0.5ms/0=满窗直发（负值区已去掉）', () => {
  assertEq(38_000_000, exposurePlan(planState(), planSnapshot(T25, 25, false, 2_000_000, undefined, false)).exposureNs,
    '关自动 + 手动 +2ms ⇒ 38ms');
  assertEq(39_500_000, exposurePlan(planState(), planSnapshot(T25, 25, false, 500_000, undefined, false)).exposureNs,
    '半毫步进：+0.5ms ⇒ 39.5ms');
  assertEq(T25, exposurePlan(planState(), planSnapshot(T25, 25, false, 0, undefined, false)).exposureNs,
    '手动 0 = 用户明确要满窗（等号直发，与改版前行为一致）');
});

test('1/25@24 本来就不满窗（读出有余量），纠偏不适用、曝光原样', () => {
  const plan: ExposurePlan = exposurePlan(planState(), planSnapshot(T25, 24, true, 0, undefined, false));
  assertEq(T25, plan.exposureNs);
  assertEq(T24, plan.frameDurationNs, '帧周期按 24fps 档位');
});

test('总线档位值原样透传：25fps+1/24 被钳到帧周期后再纠偏，40 − 2 = 38ms', () => {
  const plan: ExposurePlan = exposurePlan(planState(), planSnapshot(T24, 25, true, 0, undefined, false));
  assertEq(38_000_000, plan.exposureNs);
  assertEq(T25, plan.frameDurationNs);
});

test('ReadoutLatch 键控语义：同键命中、异键不匹配（换帧率 ⇒ 重探）', () => {
  const latch: ReadoutLatch = new ReadoutLatch('WIDE|1920x1080|25', 6_000_000);
  assertTrue(latch.key === 'WIDE|1920x1080|25' && latch.deltaNs === 6_000_000);
  assertFalse(latch.key === 'WIDE|1920x1080|24', '换帧率 ⇒ 键不匹配 ⇒ 重探');
});
