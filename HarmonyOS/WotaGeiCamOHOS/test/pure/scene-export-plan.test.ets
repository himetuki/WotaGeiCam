/**
 * 后渲染导出纯决策层全表——对拍上游 SceneExportPlanTest.kt（dcaf45b/ba9f0f4/350c671
 * 的决策面）。native 管线面（解码→GL 三趟→重编码）待批，本文件先行钉死决策口径。
 */
import {
  SceneExportEffects, sceneExportBitrate, sceneExportEffects, sceneExportEffectsOf,
  sceneExportFps, sceneExportPtsUs, sceneExportShouldSubmit
} from '../../entry/src/main/ets/player/SceneExportPlan';
import { GLOW_DEFAULT, GlowParams } from '../../entry/src/main/ets/core/Glow';
import { VideoDenoise } from '../../entry/src/main/ets/core/Denoise';

test('未开光晕未开降噪时后渲染无意义（active=false ⇒ UI 置灰判据）', () => {
  const e: SceneExportEffects = sceneExportEffects(undefined, VideoDenoise.OFF);
  assertFalse(e.active(), '出厂 glow 关 + denoise OFF');
  assertTrue(e.glow === undefined);
  assertFalse(e.denoise);
});

test('坏串回落出厂关', () => {
  const e: SceneExportEffects = sceneExportEffects('garbage', VideoDenoise.OFF);
  assertFalse(e.active(), '坏串 = 出厂 = 关（光晕不许猜）');
});

test('光晕开启即应用且带完整参数（sanitized）', () => {
  const on: GlowParams = new GlowParams({ enabled: true, threshold: 9 });
  const e: SceneExportEffects = sceneExportEffectsOf(on, false);
  assertTrue(e.active());
  assertTrue(e.glow !== undefined);
  if (e.glow !== undefined) {
    assertEq(1.0, e.glow.threshold, '越界值已 sanitize（钳上限）');
  }
});

test('降噪任一档都按 GPU 双边处理（离线无 HAL ISP）', () => {
  assertTrue(sceneExportEffects(undefined, VideoDenoise.GPU_BILATERAL).denoise);
  assertTrue(sceneExportEffects(undefined, VideoDenoise.HAL_FAST).denoise, 'HAL 档离线退化为同一条双边核');
  assertTrue(sceneExportEffects(undefined, VideoDenoise.HAL_HIGH_QUALITY).denoise);
  assertFalse(sceneExportEffects(undefined, VideoDenoise.OFF).denoise);
});

test('白色 tint 是恒等（跟随原色）', () => {
  const e: SceneExportEffects = sceneExportEffectsOf(new GlowParams({ enabled: true }), false);
  const t: Array<number> = e.glowTint();
  assertEq(1, t[0]);
  assertEq(1, t[1]);
  assertEq(1, t[2]);
});

test('色盘选色会进 tint', () => {
  const e: SceneExportEffects = sceneExportEffectsOf(
    new GlowParams({ enabled: true, color: 0xFFFF3366 }), false);
  const t: Array<number> = e.glowTint();
  assertEq(1.0, t[0]);
  assertEq(0.2, t[1]);
  assertEq(0.4, t[2]);
});

test('帧率跟源且钳在 15..60', () => {
  assertEq(24, sceneExportFps(24));
  assertEq(60, sceneExportFps(60));
  assertEq(30, sceneExportFps(120), '高帧源钳 60');
  assertEq(30, sceneExportFps(0), '读不出按 30');
  assertEq(30, sceneExportFps(14), '低于域按 30');
});

test('码率跟源且异常回 8M', () => {
  assertEq(12_000_000, sceneExportBitrate(12_000_000));
  assertEq(8_000_000, sceneExportBitrate(0));
  assertEq(8_000_000, sceneExportBitrate(500_000), '异常小兜底');
  assertEq(8_000_000, sceneExportBitrate(999_999));
  assertEq(1_000_000, sceneExportBitrate(1_000_000), '下界含');
});

test('呈现时间戳减基准且不落负', () => {
  assertEq(33_333, sceneExportPtsUs(33_333, 0));
  assertEq(0, sceneExportPtsUs(10_000, 33_333), '早于基准钳 0');
});

test('区间判据左闭右开', () => {
  assertTrue(sceneExportShouldSubmit(1_000, 1_000, 2_000), '左闭');
  assertFalse(sceneExportShouldSubmit(2_000, 1_000, 2_000), '右开');
  assertFalse(sceneExportShouldSubmit(999, 1_000, 2_000));
  assertTrue(sceneExportShouldSubmit(5_000, 1_000, 1_000), 'end<=start = 不设上限（全收）');
});
