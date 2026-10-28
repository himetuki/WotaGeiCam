/**
 * 手动白平衡能力门控（对齐上游 ab73ebf/ee9aa0e 口径的 OHOS 落点）+ diag 拆闸 + AF 遥测接线守卫。
 *
 * 本侧缺陷（2026-10-28 追赶轮发现）：① WotaParams.applyWhiteBalance 的兜底 `?? WbPreset.MANUAL`
 * 会把总线钉到设备探测表里没有的档上（能力假设）；② WbPillPopup 的 MANUAL 选项被
 * `p === WbPreset.MANUAL ||` 短路恒可选；③ apply 对 MANUAL 无探测守卫，下发必被拒或落错状态。
 * 三处同根：手动档的「能不能用」没走运行时探测（KitAbility 的 awbModes 恰恰就是探测产物）。
 *
 * diag 拆闸（上游 eefccc9）：hilog 通道 i/w/e 恒写、d/v 收详细档（V/D 不再无条件进系统缓冲）。
 * AF 遥测（上游 4457e4a 方案F 的 OHOS 对应面）：focusStateChange 1s 一拍 d 级 + 切手动档
 * 提示峰值（方案G 最小形态）。AE 状态事件 SDK 无对应（登记 N/A）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { CameraAbility, CameraAbilityArgs, RangeI } from '../../entry/src/main/ets/core/CameraAbility';
import { Rect, Size, WbPreset } from '../../entry/src/main/ets/core/Constants';

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

function ability(awb: Array<number>): CameraAbility {
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
    exposureNs: new RangeI(1000, 33_000_000),
    evRange: new RangeI(-4, 4),
    evStep: 1,
    afModes: new Set<number>([1]),
    awbModes: new Set<number>(awb),
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

// WbPreset 契约值（wbModesToAwb：MANUAL=0、AUTO=1、INCANDESCENT=2、DAYLIGHT=5）
const AWB_MANUAL: number = WbPreset.MANUAL as number;
const AWB_AUTO: number = WbPreset.AUTO as number;
const AWB_DAYLIGHT: number = WbPreset.DAYLIGHT as number;

test('探测表有MANUAL_手动白平衡可用', () => {
  assertTrue(ability([AWB_AUTO, AWB_MANUAL]).manualWhiteBalancePossible());
});

test('探测表无MANUAL_手动白平衡不可用', () => {
  assertFalse(ability([AWB_AUTO, AWB_DAYLIGHT]).manualWhiteBalancePossible());
});

test('探测表为空_视为可用_保持既有行为', () => {
  // 空集合 = 未探测/查不到（无法判定）——不因「查不到」把用户已选的手动档弹掉
  assertTrue(ability([]).manualWhiteBalancePossible());
});

test('接线_WbPillPopup的MANUAL选项必须过探测', () => {
  const src: string = sourceText('entry/src/main/ets/ui/ParamPills.ets');
  assertTrue(src.indexOf('this.ability.manualWhiteBalancePossible()') >= 0,
    'MANUAL 选项必须经 manualWhiteBalancePossible 探测（不许 p === MANUAL 短路恒可选）');
  assertFalse(src.indexOf('p === WbPreset.MANUAL || this.ability === undefined ||') >= 0,
    '旧的短路恒可选写法必须消失');
});

test('接线_总线fallback绝不落到未探测的MANUAL', () => {
  const src: string = sourceText('entry/src/main/ets/core/WotaParams.ets');
  assertTrue(src.indexOf('manualPossible') >= 0, 'applyWhiteBalance 必须先取 manualWhiteBalancePossible');
  assertFalse(src.indexOf('?? WbPreset.MANUAL;') >= 0,
    '裸兜底 ?? MANUAL（能力假设）必须消失');
  assertTrue(src.indexOf('this.wbMode.value === WbPreset.MANUAL && !manualPossible') >= 0,
    '已选手动但探测不可能时必须降档');
});

test('接线_apply对MANUAL有探测守卫', () => {
  const src: string = sourceText('entry/src/main/ets/camera/RequestApplier.ets');
  assertTrue(src.indexOf("whiteBalanceManual: mode unsupported by device probe") >= 0,
    'MANUAL 下发必须有 skip 分支（防御纵深）');
  assertTrue(src.indexOf('wbManualSupported: ability.manualWhiteBalancePossible()') >= 0,
    'stateOf 必须从能力探测装配 wbManualSupported');
});

test('接线_diag的d_v受详细档_不无条件进hilog', () => {
  const src: string = sourceText('entry/src/main/ets/diag/Diag.ets');
  assertTrue(src.indexOf('} else if (diagLevelEnabled(level, Diag.verbose)) {') >= 0,
    'hilog.debug 分支必须被 diagLevelEnabled 门控（上游 eefccc9 拆闸：d/v 收详细档）');
});

test('接线_AF遥测与手动档峰值提示', () => {
  const engine: string = sourceText('entry/src/main/ets/camera/GlCameraEngine.ets');
  assertTrue(engine.indexOf("session.on('focusStateChange'") >= 0,
    '必须挂 focusStateChange 遥测（上游 4457e4a 方案F 的 OHOS 对应面）');
  assertTrue(engine.indexOf('FOCUS_TELEMETRY_MS') >= 0, '遥测必须有 1s 拍点节流');
  const pills: string = sourceText('entry/src/main/ets/ui/ParamPills.ets');
  assertTrue(pills.indexOf('onManualSelected') >= 0, 'FocusPillPopup 必须有切入手动的提示钩子');
  const camera: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(camera.indexOf('onManualSelected') >= 0 &&
    camera.indexOf('手动对焦建议开「峰值」辅助') >= 0,
    '宿主页必须消费钩子给出峰值提示（方案G 最小形态）');
});
