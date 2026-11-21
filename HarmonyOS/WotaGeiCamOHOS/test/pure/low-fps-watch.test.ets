/**
 * 运行时帧率坍塌守卫 + 换段预提示 + setFps 单一写入者——对拍上游 LowFpsWatchTest.kt +
 * ShutterRangeTest.kt（91e6353 + 36917e5 + 45c8500）。
 *
 * 钉五件事：①守卫判定（宽限/持续/滞回/换档重置/零到达）；②segmentCapApproached 的
 * -1 不可观测口径与 85% 界；③setFps 写值后快门可选域同一拍按新档位重算（域随档走）；
 * ④所有 fps 写入点走 setFps（源码接线守卫）；⑤引擎定时采样/页面三告警行接线在位。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { LowFpsHint, LowFpsWatch } from '../../entry/src/main/ets/core/LowFpsWatch';
import { WotaTiers, segmentCapApproached } from '../../entry/src/main/ets/core/Constants';
import { CameraAbility, CameraAbilityArgs, RangeI } from '../../entry/src/main/ets/core/CameraAbility';
import { Rect, Size } from '../../entry/src/main/ets/core/Constants';
import { ParamState, WotaParams } from '../../entry/src/main/ets/core/WotaParams';

// ------------------------------------------------------------------ 守卫判定

/** 24fps 档、固定 1s 步进喂拍的常用桩（上游 feed 同型） */
function feed(watch: LowFpsWatch, rates: Array<number>, startMs: number = 1_000): Array<boolean> {
  const out: Array<boolean> = [];
  for (let i: number = 0; i < rates.length; i++) {
    out.push(watch.onSample(startMs + i * 1_000, rates[i]));
  }
  return out;
}

test('宽限期内持续低帧不触发', () => {
  const w: LowFpsWatch = new LowFpsWatch(24);
  // 4s 宽限内的 4 拍全部 5fps：不告警
  const r: Array<boolean> = feed(w, [5, 5, 5, 5]);
  assertTrue(!r.some((v: boolean): boolean => v), '宽限内全低帧也不告警');
});

test('宽限期后连续四拍低帧才触发', () => {
  const w: LowFpsWatch = new LowFpsWatch(24);
  // 前四拍在宽限内（1s..4s），从第 5 秒起出宽限：连续 4 拍 5fps，第 4 拍翻转
  const r: Array<boolean> = feed(w, [5, 5, 5, 23, 5, 5, 5, 5]);
  const expect: Array<boolean> = [false, false, false, false, false, false, false, true];
  for (let i: number = 0; i < expect.length; i++) {
    assertEq(expect[i], r[i], `拍 ${i}`);
  }
});

test('单拍低帧被正常拍打断则连续计数清零', () => {
  const w: LowFpsWatch = new LowFpsWatch(24);
  // 5,5,5（宽限尾）→ 23（清零）→ 5,5,5（出宽限后只攒 3 拍）→ 不触发
  const r: Array<boolean> = feed(w, [5, 5, 5, 23, 5, 5, 5]);
  assertFalse(r[r.length - 1]);
});

test('触发后恢复带内保持告警，过恢复线才解除', () => {
  const w: LowFpsWatch = new LowFpsWatch(24);
  // 制造触发：出宽限后连 4 拍 5fps
  feed(w, [5, 5, 5, 5, 5, 5, 5, 5]);
  // 滞回：恢复带（< 0.85×24=20.4，取 19）内维持告警开
  assertTrue(w.onSample(20_000, 19), '恢复带内维持告警（不闪烁）');
  assertTrue(w.onSample(21_000, 5), '跌回去仍是告警');
  // 过恢复线（≥20.4，取 21）解除
  assertFalse(w.onSample(22_000, 21), '过恢复线解除');
  assertFalse(w.onSample(23_000, 5), '解除后重新攒连续计数');
});

test('换档重置：旧账（宽限/连续/触发态）作废', () => {
  const w: LowFpsWatch = new LowFpsWatch(24);
  feed(w, [5, 5, 5, 5]); // 攒低帧账（未到触发）
  w.reset(30);
  assertTrue(w.targets(30), '目标已换');
  assertFalse(w.targets(24));
  // 宽限重新起算：reset 后立刻又是 4 拍 5fps 不触发
  const r: Array<boolean> = feed(w, [5, 5, 5, 5], 50_000);
  assertTrue(!r.some((v: boolean): boolean => v), '重置后宽限重新起算');
});

test('零到达如实喂 0fps：出宽限后连续 4 拍即触发', () => {
  const w: LowFpsWatch = new LowFpsWatch(24);
  const r: Array<boolean> = feed(w, [0, 0, 0, 0, 0, 0, 0, 0]);
  assertTrue(r[r.length - 1], '零回调（HAL 断流）也要能告警');
});

test('档位非正不判：维持现状', () => {
  const w: LowFpsWatch = new LowFpsWatch(0);
  assertFalse(w.onSample(1_000, 5));
});

test('LowFpsHint 展示粒度与去重判等', () => {
  const h: LowFpsHint = new LowFpsHint(24, 16);
  assertTrue(h.sameAs(new LowFpsHint(24, 16)), '同值判等（总线去重语义）');
  assertFalse(h.sameAs(new LowFpsHint(24, 17)));
  assertFalse(h.sameAs(undefined));
});

// ------------------------------------------------------------------ 换段预提示判据

test('segmentCapApproached：-1 不可观测恒 false，85% 界两侧各钉一值', () => {
  assertFalse(segmentCapApproached(-1, 1_000_000), '"量不到"不是"超了"');
  assertFalse(segmentCapApproached(0, 1_000_000));
  assertFalse(segmentCapApproached(849_999, 1_000_000), '85% 界下方');
  assertTrue(segmentCapApproached(850_000, 1_000_000), '恰在 85% 界上');
  assertTrue(segmentCapApproached(900_000, 1_000_000));
  // 默认参数 = 产品常量 3.5GiB
  assertFalse(segmentCapApproached(0));
  assertTrue(segmentCapApproached(WotaTiers.MAX_FILE_BYTES));
});

// ------------------------------------------------------------------ setFps 单一写入者

/** 假能力：fps 表 [24,30] 与曝光域由用例点菜（wb-manual-gate 手填惯例） */
function ability(exposureMaxNs: number): CameraAbility {
  const args: CameraAbilityArgs = {
    hardwareLevel: 1,
    sensorOrientation: 90,
    activeArray: new Rect(0, 0, 4000, 3000),
    jpegSizes: [],
    highSpeedSizes: [],
    videoSizes: [new Size(1920, 1080)],
    fpsRanges: [new RangeI(24, 24), new RangeI(30, 30)],
    highSpeedFps: new Map<Size, Array<RangeI>>(),
    iso: new RangeI(100, 3200),
    exposureNs: new RangeI(1000, exposureMaxNs),
    evRange: new RangeI(-4, 4),
    evStep: 1,
    afModes: new Set<number>([1]),
    awbModes: new Set<number>([1]),
    flashAvailable: true,
    maxRegionsAf: 1,
    maxRegionsAe: 1,
    minFocusDistanceDiopter: 1.0,
    zoomRatioRange: undefined,
    maxDigitalZoom: 8,
    oisAvailable: false,
    eisAvailable: true,
    highSpeedCapable: false,
    availableSurfaceFormats: []
  };
  return new CameraAbility(args);
}

test('setFps 后快门可选域同一拍按新档位收口（域随档走）', () => {
  const params: WotaParams = new WotaParams();
  const a: CameraAbility = ability(33_000_000);
  params.applyAbility(a); // 立快照（applyAbility 内部按默认 25fps 重算）
  // 先把档位放到 30：快门上限 = min(设备抬升后, 1/30)
  params.setFps(ParamState.of(30));
  const hi30: number = params.shutter.value.range !== undefined ? params.shutter.value.range.hi : 0;
  assertEq(Math.floor(1_000_000_000 / 30), hi30, '30fps 档快门上限 = 1/30');
  // 切 24：域必须**立即**跟上 1/24（缺陷行为 = 停在 1/30）
  params.setFps(ParamState.of(24));
  const hi24: number = params.shutter.value.range !== undefined ? params.shutter.value.range.hi : 0;
  assertEq(Math.floor(1_000_000_000 / 24), hi24, '24fps 档快门上限 = 1/24（域随档走）');
  // 当前值超新域时钳回：1/30 快门在 24fps 档合法（33.3ms < 41.7ms）不钳；1/24 值在 60 档会被钳
  params.setFps(ParamState.of(30));
  const hiBack: number = params.shutter.value.range !== undefined ? params.shutter.value.range.hi : 0;
  assertEq(Math.floor(1_000_000_000 / 30), hiBack, '切回 30 又收口到 1/30');
});

test('无能力快照时 setFps 只写值不炸（冷启口径）', () => {
  const params: WotaParams = new WotaParams();
  params.setFps(ParamState.of(24));
  assertEq(24, params.fps.value.value, '值已写');
  assertTrue(params.shutter.value.range === undefined, '域等 applyAbility 再算（出厂态）');
});

// ------------------------------------------------------------------ 接线守卫（源码扫描）

function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/core/CameraAbility.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

function occurrences(hay: string, needle: string): number {
  let n: number = 0;
  let at: number = hay.indexOf(needle);
  while (at >= 0) {
    n++;
    at = hay.indexOf(needle, at + needle.length);
  }
  return n;
}

test('接线：所有 fps 写入点走 setFps（直写 fps.value 归零）', () => {
  const page: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  const persist: string = sourceText('entry/src/main/ets/ui/ParamsPersist.ets');
  const params: string = sourceText('entry/src/main/ets/core/WotaParams.ets');
  assertEq(0, occurrences(page, 'params.fps.value ='), '取景页不许直写 fps.value');
  assertEq(0, occurrences(persist, 'params.fps.value ='), '恢复链不许直写 fps.value');
  // WotaParams 内部直写恰两处：setFps 写值 + applyAbility 决议落档（重算点）
  assertEq(2, occurrences(params, 'this.fps.value ='), '总线内直写 = setFps 与 applyAbility 两处');
  assertEq(1, occurrences(params, 'setFps(next: ParamState)'), '单一写入者定义在场');
  assertEq(2, occurrences(page, '.setFps('), '取景页两个写入点（起镜预写/弹层选档）');
  assertEq(1, occurrences(persist, '.setFps('), '恢复链走单一入口');
});

test('接线：引擎定时采样 + 守卫 + 页面三告警行在位', () => {
  const engine: string = sourceText('entry/src/main/ets/camera/GlCameraEngine.ets');
  const page: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  // 独立 1s 定时拍（零回调盲区补丁）：采样不挂帧回调
  assertEq(1, occurrences(engine, 'this.startFpsSampler()'), 'start 开采样拍');
  assertEq(1, occurrences(engine, 'private startFpsSampler('), '采样拍定义唯一');
  assertTrue(engine.indexOf('setInterval') >= 0 && engine.indexOf('sampleCameraRate()') >= 0,
    '定时拍驱动采样');
  assertTrue(engine.indexOf('new LowFpsWatch(target)') >= 0, '守卫在引擎侧');
  // 页面三行
  assertEq(1, occurrences(page, 'segmentCapApproached('), '换段预提示判据接线');
  assertTrue(page.indexOf('this.approxManual =') >= 0, '近似档+手动预告知接线');
  assertTrue(page.indexOf('实际帧率只有约') >= 0, '低帧告警行文案在位');
});
