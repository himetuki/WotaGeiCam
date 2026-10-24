/**
 * ST 批（设置数值档）：UserSettings 纯算式全表 + 接线守卫。
 *
 * 上游口径（WotaSettings 逐字）：compare_tick_ms 缺键 200、钳 100..1000、档位
 * [100,200,500,1000]；text_scale_* 缺键 100、钳 80..120、五档 [80,90,100,110,120]、
 * 产出倍率（pct/100）；diag_enabled/diag_verbose 布尔持久化。
 *
 * 决策钉子：本侧 compare_tick_ms 缺键回 **120**（本侧历史口径，偏离上游 200——登记）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { UserSettings } from '../../entry/src/main/ets/core/UserSettings';

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

// ------------------------------------------------------------------ 节拍钳制

test('节拍_缺键200_越界钳回100..1000', () => {
  assertEq(200, UserSettings.compareTickMsOf(undefined), '缺键 200（上游口径）');
  assertEq(200, UserSettings.compareTickMsOf(200));
  assertEq(100, UserSettings.compareTickMsOf(100));
  assertEq(1000, UserSettings.compareTickMsOf(1000));
  assertEq(100, UserSettings.compareTickMsOf(99), '低于下限钳到 100');
  assertEq(1000, UserSettings.compareTickMsOf(1001), '高于上限钳到 1000');
  // 非有限数（NaN/Inf）走缺键语义回 200（实现口径：非有限=未存；上游 getInt 不会有 NaN）
  assertEq(200, UserSettings.compareTickMsOf(Number.NaN), 'NaN 同缺键');
  assertEq(200, UserSettings.compareTickMsOf(Number.POSITIVE_INFINITY), 'Inf 同缺键');
});

test('节拍_档位与边界是绝对字面量', () => {
  assertEq('100,200,500,1000', UserSettings.COMPARE_TICK_TIERS.join(','),
    '档位表与上游逐字（改档 = 改设置页选项）');
  assertEq(100, UserSettings.COMPARE_TICK_MIN_MS);
  assertEq(1000, UserSettings.COMPARE_TICK_MAX_MS);
});

// ------------------------------------------------------------------ 文本缩放

test('缩放_缺键100_钳回80..120', () => {
  assertEq(100, UserSettings.textScalePctOf(undefined), '缺键 100（工程默认）');
  assertEq(80, UserSettings.textScalePctOf(80));
  assertEq(120, UserSettings.textScalePctOf(120));
  assertEq(80, UserSettings.textScalePctOf(79), '低于下限钳到 80');
  assertEq(120, UserSettings.textScalePctOf(121), '高于上限钳到 120');
  assertEq(100, UserSettings.textScalePctOf(Number.NaN), 'NaN 同缺键');
});

test('缩放_倍率换算与五档', () => {
  assertEq('80,90,100,110,120', UserSettings.TEXT_SCALE_PCTS.join(','),
    '五档与上游逐字（改档 = 改设置页选项）');
  assertEq(1, UserSettings.textScaleFactorOf(100), '100% = 1.0');
  assertEq(1.2, UserSettings.textScaleFactorOf(120), '120% = 1.2（喂 hudPerRowFor）');
  assertTrue(UserSettings.textScaleFactorOf(80) < 1, '80% < 1');
});

// ------------------------------------------------------------------ 接线守卫

test('接线_键名与消费方', () => {
  const store: string = sourceText('entry/src/main/ets/ui/SettingsStore.ets');
  for (const key of ['compare_tick_ms', 'text_scale_camera', 'diag_enabled', 'diag_verbose']) {
    assertTrue(store.indexOf(`'${key}'`) >= 0, `键 ${key} 必须在 SettingsStore（上游逐字）`);
  }
  assertTrue(store.indexOf('UserSettings.compareTickMsOf') >= 0 &&
    store.indexOf('UserSettings.textScalePctOf') >= 0,
    'load 必须经 UserSettings 纯算式收口（不许手写 min/max）');
  assertTrue(store.indexOf('static compareTickMsSync') >= 0 &&
    store.indexOf('static textScaleCameraSync') >= 0 &&
    store.indexOf('static diagPersistSync') >= 0,
    '三个同步读口必须存在（Compare/Camera/Index 冷启）');

  const cmp: string = sourceText('entry/src/main/ets/pages/Compare.ets');
  assertTrue(cmp.indexOf('this.tickMs = SettingsStore.compareTickMsSync(ctx0)') >= 0,
    'Compare 必须消费持久化节拍');
  assertTrue(cmp.indexOf('TICK_MS: number = 120') < 0,
    '旧写死常量必须被替换（缺键默认改为 TICK_MS_DEFAULT=120 登记）');

  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('UserSettings.textScaleFactorOf(this.settings.textScaleCameraPct)') >= 0,
    'Camera 字体档必须乘用户缩放倍率');

  const idx: string = sourceText('entry/src/main/ets/pages/Index.ets');
  assertTrue(idx.indexOf('SettingsStore.diagPersistSync(ctxD)') >= 0 &&
    idx.indexOf('Diag.setVerbose') >= 0 && idx.indexOf('this.persistDiagFlags()') >= 0,
    'Index 必须回填并持久化诊断两开关');

  const settings: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(settings.indexOf('compareTickGroup()') >= 0 && settings.indexOf('textScaleGroup()') >= 0,
    '设置页必须有节拍/缩放两个分组');
});
