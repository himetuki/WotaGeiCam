/**
 * FC 批（对焦胶囊 + tint 决策）：
 * - 纯逻辑/接线守卫：FocusPillPopup 挂接（dock 行含 focus、录制可调集含 focus、
 *   弹层用 diopterLadder 与能力闸）；AF 模式/屈光度的值链（枚举契约 + 能力缺失的降级）。
 * - 决策钉子：tint 副轴**不迁**——OHOS setWhiteBalance 只吃 kelvin、无 RGB 增益接口
 *   （RequestApplier 登记在案），守卫钉住「WbPillPopup 里不许出现 tint 滑杆」防后人好心补上。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { AfMode } from '../../entry/src/main/ets/core/Constants';
import { HudSpec } from '../../entry/src/main/ets/ui/HudSpec';
import { diopterLadder } from '../../entry/src/main/ets/ui/SliderLadder';

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

function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

// ------------------------------------------------------------------ 契约与值链

test('AfMode枚举值_与ParamMap映射同源', () => {
  // 这些数值会进 focusModeFor 的映射表（RequestApplier），改值=改下发语义
  assertEq(3, AfMode.CONTINUOUS_VIDEO);
  assertEq(0, AfMode.MANUAL);
});

test('diopterLadder_等分11点_hi不大于lo时空表', () => {
  const ladder: Array<number> = diopterLadder(0, 10);
  assertEq(11, ladder.length);
  assertEq(0, ladder[0]);
  assertEq(10, ladder[10]);
  assertEq(5, ladder[5]);
  assertEq(0, diopterLadder(5, 5).length, 'hi<=lo 空（PillSlider 走禁用占位）');
});

// ------------------------------------------------------------------ 接线守卫

test('接线_dock与录制可调集都含focus', () => {
  assertTrue(HudSpec.DOCK_ROW2.indexOf('focus') >= 0, '第二行 Dock 必须含对焦胶囊');
  const pills: string = sourceText('entry/src/main/ets/ui/ParamPills.ets');
  assertTrue(pills.indexOf("['iso', 'ev', 'wb', 'focus']") >= 0,
    'RECORDING_EDITABLE_PILLS 必须含 focus（对齐上游 {ISO,EV,WB,ZOOM,FOCUS} 减无弹层的 ZOOM）');
});

test('接线_对焦弹层走能力闸与diopterLadder', () => {
  const pills: string = sourceText('entry/src/main/ets/ui/ParamPills.ets');
  assertTrue(pills.indexOf('FocusPillPopup') >= 0, '必须有 FocusPillPopup');
  assertTrue(pills.indexOf('diopterLadder(this.lo, this.hi)') >= 0,
    '屈光度滑杆必须用 diopterLadder（T22 备好的梯，不许手写档位）');
  assertTrue(pills.indexOf("params.manualFocusDiopter.value.range") >= 0,
    '能力闸：range 缺失（定焦头/探针不支持）不显示滑杆');
  assertTrue(pills.indexOf('params.afMode.value = next') >= 0, 'AF 模式写总线（engine.applyParams 下发）');

  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf("this.activePill === 'focus'") >= 0, 'Camera 必须渲染对焦弹层');
  assertTrue(cam.indexOf("AfMode.MANUAL ? '手动' : '连续'") >= 0, 'Dock 标签随 AF 模式刷新');
});

test('决策_tint副轴不迁_白平衡弹层无tint控件', () => {
  // 平台无等价物：setWhiteBalance 只吃 kelvin、无 RGB 增益接口（RequestApplier 登记）。
  // 造一个没有效果的滑杆比没有更糟——守卫钉住不许出现。
  const pills: string = sourceText('entry/src/main/ets/ui/ParamPills.ets');
  const start: number = pills.indexOf('export struct WbPillPopup');
  const end: number = pills.indexOf('export struct', start + 10);
  const wb: string = end > 0 ? pills.substring(start, end) : pills.substring(start);
  assertTrue(start >= 0, 'WbPillPopup 存在');
  assertTrue(wb.indexOf('tint') < 0, 'WbPillPopup 里不许出现 tint 控件（平台无等价物，登记 PORT_MAP）');
  const applier: string = sourceText('entry/src/main/ets/camera/RequestApplier.ets');
  assertTrue(applier.indexOf('setWhiteBalance(p.kelvin)') >= 0,
    '白平衡下发只有 kelvin 一条（无增益接口的平台事实）');
});
