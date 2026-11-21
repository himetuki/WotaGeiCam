/**
 * 亮度渐变纯算式 + 接线守卫——对拍上游 BrightnessRampTest.kt（5216e72，docs/plan/29 §5.5）。
 * 钉三件事：①三算式（系统亮度折算/线性插值/步数）；②两项设置读侧钳制口径；
 * ③取景页冷启闸 + 播放页起播武装 + 存储键 + 设置分组的接线在位。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { BrightnessRampMath } from '../../entry/src/main/ets/core/BrightnessRampMath';

test('systemFraction：正常折算 + 读不到/越界回 0.5 中庸兜底', () => {
  assertEq(0, BrightnessRampMath.systemFraction(0, 255));
  assertEq(1, BrightnessRampMath.systemFraction(255, 255));
  assertTrue(Math.abs(BrightnessRampMath.systemFraction(128, 255) - 128 / 255) < 1e-9);
  assertEq(0.5, BrightnessRampMath.systemFraction(-1, 255), '越界下');
  assertEq(0.5, BrightnessRampMath.systemFraction(256, 255), '越界上');
  assertEq(0.5, BrightnessRampMath.systemFraction(5, 0), 'max 非正（读不到）');
});

test('valueAt：0 档立即、线性插值、越窗钳终值', () => {
  assertEq(0.8, BrightnessRampMath.valueAt(0, 0, 0.3, 0.8), 'rampMs=0 立即到终值');
  assertEq(0.3, BrightnessRampMath.valueAt(0, 7_000, 0.3, 1), '起点');
  assertTrue(Math.abs(BrightnessRampMath.valueAt(3_500, 7_000, 0.3, 1) - 0.65) < 1e-9, '中点线性');
  assertEq(1, BrightnessRampMath.valueAt(9_000, 7_000, 0.3, 1), '超出钳终值');
});

test('stepCount：0 档 0 步、7s≈60 步、至少 2 步', () => {
  assertEq(0, BrightnessRampMath.stepCount(0));
  assertEq(0, BrightnessRampMath.stepCount(-5));
  assertEq(Math.floor(7_000 / 117), BrightnessRampMath.stepCount(7_000), '约 117ms 一步');
  assertEq(2, BrightnessRampMath.stepCount(100), '1 步 = 跳变，失去渐变语义 ⇒ 最少 2');
});

test('设置读侧：缺键默认（1s / 1.0s）、在册脏值钳回域、0=关闭是合法档', () => {
  assertEq(1, BrightnessRampMath.cameraBrightenDelaySec(undefined), '缺键回默认 1');
  assertEq(1, BrightnessRampMath.cameraBrightenDelaySec(NaN));
  assertEq(0, BrightnessRampMath.cameraBrightenDelaySec(-5), '在册脏值钳 0（=关闭）');
  assertEq(10, BrightnessRampMath.cameraBrightenDelaySec(99), '上限 10');
  assertEq(10, BrightnessRampMath.playerBrightenRampTenths(undefined), '缺键回默认 10（1.0s）');
  assertEq(0, BrightnessRampMath.playerBrightenRampTenths(-1));
  assertEq(30, BrightnessRampMath.playerBrightenRampTenths(77), '上限 30');
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

test('接线：冷启闸/起播武装/存储键/设置分组四点在位', () => {
  const camera: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  const player: string = sourceText('entry/src/main/ets/pages/Player.ets');
  const store: string = sourceText('entry/src/main/ets/ui/SettingsStore.ets');
  const settings: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  // 取景页：进程内一次闸 + 延迟取消 + 渐变驱动
  assertTrue(camera.indexOf('let camBrightenArmed') >= 0, '进程内冷启闸');
  assertTrue(camera.indexOf('armColdStartBrighten()') >= 0, '起镜成功处武装');
  assertTrue(camera.indexOf('cancelColdStartBrighten()') >= 0, '离页取消');
  assertTrue(camera.indexOf('this.winBrightness.cancelAndRestore') >= 0, '离页恢复跟随系统');
  assertTrue(camera.indexOf('7_000') >= 0, '7s 渐变时长（29 §九）');
  // 播放页：每次进入重新武装 + 首次起播渐变 + 离页恢复
  assertTrue(player.indexOf('this.playRampArmed = true') >= 0, 'onPageShow 重新武装');
  assertTrue(player.indexOf('e.playing && !this.playing && this.playRampArmed') >= 0,
    '首次起播边沿触发');
  assertTrue(player.indexOf('this.winBrightness.cancelAndRestore') >= 0, '离页恢复');
  // 存储键名 = 上游逐字契约
  assertTrue(store.indexOf("'camera_brighten_delay_sec'") >= 0);
  assertTrue(store.indexOf("'player_brighten_ramp_tenths'") >= 0);
  // 设置分组两行在位
  assertTrue(settings.indexOf('启动后自动亮屏') >= 0 && settings.indexOf('起播渐亮') >= 0);
  assertEq(2, occurrences(settings, 'this.brightnessGroup()'), '竖/横两列都挂载');
});
