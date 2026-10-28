/**
 * 帧率档位决议面（对齐上游 CameraAbilityFpsTest.kt 全表 + RequestApplierTest 帧基准 3 例）。
 *
 * 现场机型 vivo V2520A（上游 2026-10-26 用户报障，74d4fb2）：系统相机可录 50/60/120/240，
 * 帧率弹层只亮 24/30——根因是档位可用性按「当前会话模态」判定：默认（≤60）态下只查普通流，
 * 而该机普通流仅 [24,24]/[30,30]，50/60/120/240 全部只存在于高速表 → 按当前模态判定永远灰显。
 *
 * 修复语义（上游 2026-10-26 定版）：**一档的完整决议只走 decideFps 一个出口**——普通表先来
 * （覆盖得了就不开高速会话），覆盖不了才看高速表；两张表都不覆盖 ⇒ 灰显。50/60 在该机
 * 仍是可用档（高速表正是厂商把「高帧率录像」暴露给三方 App 的途径），实际速率由 HAL 在
 * 区间内定 ⇒ 界面标 ※；25 两表都不覆盖 → 维持普通会话 + 抽帧补弧路径。
 *
 * 下发同支（P1 根治）：旧下发分支自造 `[50,50]`（机器没声明过）⇒ 必被拒 ⇒ 取景器假死；
 * pickHighSpeedFpsRange 只返回声明表原样区间。帧周期/快门钳制基准 = **用户档位**
 * （Snapshot.fpsTarget，313e8e6）：可变高速档 50 → [30,120] 若吃 lo=30 会放行 33.3ms
 * 帧周期（>50fps 的 20ms）＝死档。
 *
 * 平台登记：鸿蒙侧 UI 帧率档当前只有 24/25/30 循环（取景引擎是 PhotoSession，无
 * VideoOutput.setFrameRate / VideoSession.setFrameRateRange 接线，高帧率档真值下发缺平台面），
 * 本批交付决议面纯函数 + 下发/钳制口径对齐；UI 高帧率档位随平台面批（登记 PORT_MAP）。
 */
import { RangeI, FpsPick, decideFps, fpsRangesUnion, needsHighSpeedFps,
  pickFpsRange, pickHighSpeedFpsRange } from '../../entry/src/main/ets/core/CameraAbility';
import { AeMode, AfMode, Flash, Rect, Stabilize, WbPreset, WotaTiers } from '../../entry/src/main/ets/core/Constants';
import { exposurePlan, clampShutterNs, Snapshot, State } from '../../entry/src/main/ets/camera/RequestApplier';

/** vivo V2520A 主摄日志原样（上游 WotaAbility fpsRanges= 行） */
const VIVO_NORMAL: Array<RangeI> = [
  new RangeI(5, 7), new RangeI(5, 15), new RangeI(5, 20), new RangeI(5, 30), new RangeI(7, 30),
  new RangeI(10, 10), new RangeI(10, 30), new RangeI(15, 15), new RangeI(24, 24), new RangeI(30, 30)
];

/** vivo V2520A 高速表 1920x1080 行（上游 WotaAbility highSpeed 行） */
const VIVO_HS_1080: Array<RangeI> = [
  new RangeI(30, 120), new RangeI(60, 120), new RangeI(120, 120),
  new RangeI(30, 240), new RangeI(60, 240), new RangeI(240, 240)
];

function pickTriple(p: FpsPick): string {
  return `${p.lo},${p.hi},${p.exact ? 1 : 0}`;
}

// ------------------------------------------------------------------ needsHighSpeedFps（74d4fb2）

test('vivo_50_60_120_240都需要高速会话', () => {
  for (const fps of [50, 60, 120, 240]) {
    assertTrue(needsHighSpeedFps(VIVO_NORMAL, VIVO_HS_1080, fps),
      `vivo 上 ${fps} 普通流无档、高速表有档 ⇒ 需要高速会话`);
  }
});

test('vivo_24_30走普通会话_25不误入高速', () => {
  assertFalse(needsHighSpeedFps(VIVO_NORMAL, VIVO_HS_1080, 24), '24 有原生 [24,24]');
  assertFalse(needsHighSpeedFps(VIVO_NORMAL, VIVO_HS_1080, 30), '30 有原生 [30,30]');
  assertFalse(needsHighSpeedFps(VIVO_NORMAL, VIVO_HS_1080, 25),
    '25 两表都不覆盖 ⇒ 维持普通会话 + 抽帧补弧路径（不得误入高速）');
});

test('有原生50的机型走普通会话', () => {
  const with50: Array<RangeI> = [new RangeI(24, 24), new RangeI(30, 30), new RangeI(50, 50)];
  assertFalse(needsHighSpeedFps(with50, VIVO_HS_1080, 50), '普通流直接给 [50,50]：50 不需要高速会话');
});

// ------------------------------------------------------------------ 并集可用性（74d4fb2）

test('并集让50_60_120_240全部可选', () => {
  const union: Array<RangeI> = fpsRangesUnion(VIVO_NORMAL, VIVO_HS_1080);
  for (const fps of [50, 60, 120, 240]) {
    assertTrue(pickFpsRange(union, fps) !== undefined, `并集必须覆盖 ${fps}（修复前该档灰显的根因）`);
  }
  assertEq('24,24,1', pickTriple(pickFpsRange(union, 24) as FpsPick), '既有档位语义不回退：[24,24] exact');
  assertTrue((pickFpsRange(union, 30) as FpsPick).exact, '30 应命中原生固定档（exact）');
});

test('精确性口径_固定档exact_区间内approx', () => {
  const union: Array<RangeI> = fpsRangesUnion(VIVO_NORMAL, VIVO_HS_1080);
  assertTrue((pickFpsRange(union, 120) as FpsPick).exact, '120 有原生 [120,120] ⇒ exact');
  assertTrue((pickFpsRange(union, 240) as FpsPick).exact, '240 有原生 [240,240] ⇒ exact');
  assertFalse((pickFpsRange(union, 50) as FpsPick).exact, '50 是 [30,120] 区间内近似 ⇒ ※');
  assertFalse((pickFpsRange(union, 60) as FpsPick).exact,
    '60 命中 [60,120] 区间 ⇒ ※（实际帧率由 HAL 在区间内决定）');
  const p25: FpsPick = pickFpsRange(union, 25) as FpsPick;
  assertFalse(p25.exact, '25 由普通流 [5,30] 近似覆盖 ⇒ ※（普通会话 + 抽帧补弧的机制）');
  assertEq('5,30,0', pickTriple(p25), '25 命中普通流 [5,30]');
});

test('无高速表的机型不受影响', () => {
  const normalOnly: Array<RangeI> = [new RangeI(24, 24), new RangeI(30, 30)];
  assertFalse(needsHighSpeedFps(normalOnly, [], 120));
  assertNull(pickFpsRange(fpsRangesUnion(normalOnly, []), 120));
  assertTrue(pickFpsRange(fpsRangesUnion(normalOnly, []), 24) !== undefined);
  assertTrue(pickFpsRange(fpsRangesUnion(normalOnly, []), 30) !== undefined);
});

// ------------------------------------------------------------------ decideFps / pickHighSpeedFpsRange

test('决议后下发的是机器声明过的区间', () => {
  // 这条正是 2026-10-26 的 P1：旧下发分支 `FpsPick(target, target, true)` 会造 [50,50]，
  // 而该机高速表里没有它 —— 请求被拒 ⇒ 掉进无退路的降档分支 ⇒ 取景器假死
  const d60: FpsPick = decideFps(VIVO_NORMAL, VIVO_HS_1080, 60)!.pick;
  assertEq('60,120,0', pickTriple(d60), '60 取「下界正好等于目标」里上界最小的 [60,120]');
  const d50: FpsPick = decideFps(VIVO_NORMAL, VIVO_HS_1080, 50)!.pick;
  assertEq('30,120,0', pickTriple(d50), '50 无 lo==50 的区间 ⇒ 退到覆盖它的 [30,120]（上界最小的那个）');
  assertTrue(decideFps(VIVO_NORMAL, VIVO_HS_1080, 120)!.pick.exact, '120 有原生 [120,120] ⇒ 精确');
  assertTrue(decideFps(VIVO_NORMAL, VIVO_HS_1080, 240)!.pick.exact, '240 有原生 [240,240] ⇒ 精确');
  assertTrue(decideFps(VIVO_NORMAL, VIVO_HS_1080, 50)!.highSpeed, '50 走高速会话');
  assertTrue(decideFps(VIVO_NORMAL, VIVO_HS_1080, 60)!.highSpeed, '60 走高速会话');
  assertFalse(decideFps(VIVO_NORMAL, VIVO_HS_1080, 30)!.highSpeed, '30 普通表覆盖 ⇒ 普通会话');
  assertFalse(decideFps(VIVO_NORMAL, VIVO_HS_1080, 24)!.highSpeed, '24 普通表覆盖 ⇒ 普通会话');
});

test('高速选档绝不返回未声明的固定区间', () => {
  for (const fps of [50, 60]) {
    const pick: FpsPick = pickHighSpeedFpsRange(VIVO_HS_1080, fps) as FpsPick;
    assertTrue(VIVO_HS_1080.some((r: RangeI): boolean => r.lo === pick.lo && r.hi === pick.hi),
      `下发区间 ${fps} -> [${pick.lo},${pick.hi}] 必须是声明表里的原样一条`);
  }
  assertNull(pickHighSpeedFpsRange([new RangeI(120, 120), new RangeI(240, 240)], 60),
    '表里没有任何区间覆盖目标 ⇒ undefined（由失败路径降级，不许硬发）');
});

test('决议边界_两表同覆盖必选普通_固定档恒优先', () => {
  // ① 两表都覆盖 60：必须选普通表（白开高速会话 = 白白换结构 + 高速面约束）
  const normal: Array<RangeI> = [new RangeI(30, 60)];
  const hs: Array<RangeI> = [new RangeI(60, 120)];
  const d = decideFps(normal, hs, 60)!;
  assertFalse(d.highSpeed, '两表同覆盖 ⇒ 普通会话');
  assertEq('30,60,0', pickTriple(d.pick));
  // ② 固定档恒优先于「下界相等」：[60,60] 在场时 60 不该落到 [60,120]
  const both: Array<RangeI> = [new RangeI(60, 120), new RangeI(60, 60)];
  assertEq('60,60,1', pickTriple(decideFps([], both, 60)!.pick));
  // ③ [120,120] 与 [30,240] 并存，目标 120 ⇒ 固定档精确
  const both2: Array<RangeI> = [new RangeI(30, 240), new RangeI(120, 120)];
  assertEq('120,120,1', pickTriple(pickHighSpeedFpsRange(both2, 120) as FpsPick));
  // ④ 目标 0 / 负数 / 空表 ⇒ undefined（不硬发）
  assertNull(decideFps(normal, hs, 0));
  assertNull(decideFps(normal, hs, -50));
  assertNull(decideFps([], [], 24));
});

// ------------------------------------------------------------------ 帧周期/快门基准 = 目标档位（313e8e6）

/** 与上游 RequestApplierTest 同型的最小 State/Snapshot 便捷构造 */
function stateHs(): State {
  return new State({
    activeArray: new Rect(0, 0, 4000, 3000),
    isoMin: 100, isoMax: 16000,
    exposureMinNs: 1_000_000, exposureMaxNs: 667_847_000,
    evMinSteps: -4, evMaxSteps: 4,
    fpsRanges: [new RangeI(30, 120), new RangeI(60, 120), new RangeI(120, 120)],
    maxRegionsAf: 1, maxRegionsAe: 1,
    manualFocusMaxDiopter: 10, zoomMin: 1, zoomMax: 8,
    useZoomRatio: false, flashAvailable: true, oisAvailable: true, eisAvailable: true,
    highSpeed: true, eisBlockedBySize: false, evStep: 0, focusDistanceSupported: false, wbManualSupported: true
  });
}

function snapHs(target: number): Snapshot {
  return new Snapshot({
    aeMode: AeMode.MANUAL, iso: 800, shutterNs: 20_000_000, evSteps: 0,
    fps: new FpsPick(30, 120, false), fpsTarget: target,
    afMode: AfMode.CONTINUOUS_VIDEO, focusLocked: false, focusLockPoint: undefined,
    focusDiopter: 0, zoom: 1, wbPreset: WbPreset.AUTO, kelvin: 5500, tint: 0,
    flash: Flash.OFF, stabilize: Stabilize.OFF, tapPoint: undefined
  });
}

test('帧周期基准是目标档位而不是范围下界', () => {
  // vivo 50 → [30,120]：lo=30 若作基准会放行 33.3ms 帧周期（>50fps 的 20ms）＝死档；
  // 基准必须是目标 50。曝光 20ms 与 1/50 同域 ⇒ 不被钳
  const plan = exposurePlan(stateHs(), snapHs(50));
  assertEq(Math.floor(WotaTiers.NS_PER_SECOND / 50), plan.frameDurationNs);
  assertEq(20_000_000, plan.exposureNs, '曝光 20ms < 1/50 帧周期 ⇒ 原样');
});

test('快门钳制在可变高速档吃目标档位', () => {
  // 1/30（33.3ms）在 50fps 档必须被钳回 1/50（20ms）——这是 UI 梯与引擎共同的基准
  const clamped = clampShutterNs(33_333_333, 50, 1, 1_000_000_000);
  assertEq(Math.floor(WotaTiers.NS_PER_SECOND / 50), clamped);
});

test('普通档选下界回归锚_r10意图不变', () => {
  // 回归锚：r10 的原案 [24,30] 选 24（lo==目标）⇒ 41.67ms 帧周期，1/24 长曝光保留
  const s = new State({
    activeArray: new Rect(0, 0, 4000, 3000),
    isoMin: 100, isoMax: 16000,
    exposureMinNs: 1_000_000, exposureMaxNs: 667_847_000,
    evMinSteps: -4, evMaxSteps: 4,
    fpsRanges: [new RangeI(24, 30)],
    maxRegionsAf: 1, maxRegionsAe: 1,
    manualFocusMaxDiopter: 10, zoomMin: 1, zoomMax: 8,
    useZoomRatio: false, flashAvailable: true, oisAvailable: true, eisAvailable: true,
    highSpeed: false, eisBlockedBySize: false, evStep: 0, focusDistanceSupported: false, wbManualSupported: true
  });
  const snap = new Snapshot({
    aeMode: AeMode.MANUAL, iso: 800, shutterNs: 41_666_666, evSteps: 0,
    fps: new FpsPick(24, 30, false), fpsTarget: 24,
    afMode: AfMode.CONTINUOUS_VIDEO, focusLocked: false, focusLockPoint: undefined,
    focusDiopter: 0, zoom: 1, wbPreset: WbPreset.AUTO, kelvin: 5500, tint: 0,
    flash: Flash.OFF, stabilize: Stabilize.OFF, tapPoint: undefined
  });
  const plan = exposurePlan(s, snap);
  assertEq(Math.floor(WotaTiers.NS_PER_SECOND / 24), plan.frameDurationNs,
    '[24,30] 选 24：帧周期按档位 24（41.67ms），1/24 强制档不被钳掉');
});

test('两个来源都缺失才退HIGH_SPEED_FPS兜底', () => {
  const s = stateHs();
  const noFps = new Snapshot({
    aeMode: AeMode.MANUAL, iso: 800, shutterNs: 20_000_000, evSteps: 0,
    fps: undefined, fpsTarget: 0,
    afMode: AfMode.CONTINUOUS_VIDEO, focusLocked: false, focusLockPoint: undefined,
    focusDiopter: 0, zoom: 1, wbPreset: WbPreset.AUTO, kelvin: 5500, tint: 0,
    flash: Flash.OFF, stabilize: Stabilize.OFF, tapPoint: undefined
  });
  const plan = exposurePlan(s, noFps);
  assertEq(Math.floor(WotaTiers.NS_PER_SECOND / WotaTiers.HIGH_SPEED_FPS), plan.frameDurationNs,
    'fpsPick 与 fpsTarget 全缺（异常态）才退 HIGH_SPEED_FPS（=60）');
  // 只有 fpsTarget 在：直接用目标（fpsPick 来源缺失不炸）
  const targetOnly = new Snapshot({
    aeMode: AeMode.MANUAL, iso: 800, shutterNs: 20_000_000, evSteps: 0,
    fps: undefined, fpsTarget: 50,
    afMode: AfMode.CONTINUOUS_VIDEO, focusLocked: false, focusLockPoint: undefined,
    focusDiopter: 0, zoom: 1, wbPreset: WbPreset.AUTO, kelvin: 5500, tint: 0,
    flash: Flash.OFF, stabilize: Stabilize.OFF, tapPoint: undefined
  });
  const plan2 = exposurePlan(s, targetOnly);
  assertEq(Math.floor(WotaTiers.NS_PER_SECOND / 50), plan2.frameDurationNs);
});
