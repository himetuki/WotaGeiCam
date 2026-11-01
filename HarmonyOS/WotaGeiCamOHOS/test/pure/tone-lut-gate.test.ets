/**
 * 色调 LUT 开关判据接线守卫（上游 66f88e0 红线的 OHOS 臂）：**曲线与色调合取**——
 * 只用曲线一侧决定 setCurveEnabled，恒等曲线+非恒等色调时 LUT 被禁用，色调被静默丢掉。
 * 色调映射/编解码本体在 core/Tone（此前已对拍上游 ToneParams，常量逐值一致）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { ToneParams, TONE_DEFAULT } from '../../entry/src/main/ets/core/Tone';

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
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ 	]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

test('开关判据=曲线与色调合取（66f88e0 红线）', () => {
  const camera: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  const n: number = camera.split('setCurveEnabled(!this.curveStack.isPassthrough || !this.toneParams.isIdentity())').length - 1;
  assertEq(2, n, '两处 LUT 上传点（applyCurve/applyEffect）都必须用合取判据');

});

test('tone.isIdentity 语义在册（四项全 0 = 恒等）', () => {
  assertTrue(TONE_DEFAULT.isIdentity(), '出厂默认恒等');
  assertFalse(new ToneParams({ shadows: 0.1 }).isIdentity(), '任一项非 0 = 非恒等');
});
