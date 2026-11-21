/**
 * 「液态Q弹」动效语言纯层——对拍上游 LiquidSpringMotionTest.kt（81059e2）的 OHOS 臂：
 * 三枚规格（float/offset/press）、域钳（response 0.2~0.5 / damping 0.6~0.9）、坏值回 FLOAT。
 * ArkUI 消费面 = curves.springMotion(同语义参数)，stiffness 不换算（WotaSwitch.ets 头注）。
 */
import { LiquidSpring, LiquidSpringSpec } from '../../entry/src/main/ets/core/LiquidSpring';

test('三枚规格=上游 2026-10-30 定版逐值', () => {
  assertEq(0.32, LiquidSpring.FLOAT.response);
  assertEq(0.65, LiquidSpring.FLOAT.damping);
  assertEq(0.42, LiquidSpring.OFFSET.response);
  assertEq(0.80, LiquidSpring.OFFSET.damping);
  assertEq(0.20, LiquidSpring.PRESS.response);
  assertEq(0.90, LiquidSpring.PRESS.damping);
  assertEq(0.97, LiquidSpring.PRESS_SCALE, '按压缩放');
});

test('全部规格都合法（参数面直接喂 curves 不炸）', () => {
  assertTrue(LiquidSpring.isValid(LiquidSpring.FLOAT));
  assertTrue(LiquidSpring.isValid(LiquidSpring.OFFSET));
  assertTrue(LiquidSpring.isValid(LiquidSpring.PRESS));
});

test('域钳：response 0.2~0.5、damping 0.6~0.9；坏值回 FLOAT 出厂档', () => {
  const t: LiquidSpringSpec = LiquidSpring.clamp(new LiquidSpringSpec(0.1, 0.95));
  assertEq(0.2, t.response, 'response 下限');
  assertEq(0.9, t.damping, 'damping 上限');
  const t2: LiquidSpringSpec = LiquidSpring.clamp(new LiquidSpringSpec(0.9, 0.3));
  assertEq(0.5, t2.response, 'response 上限');
  assertEq(0.6, t2.damping, 'damping 下限');
  const bad: LiquidSpringSpec = LiquidSpring.clamp(new LiquidSpringSpec(NaN, Infinity));
  assertEq(0.32, bad.response, 'NaN 回 FLOAT');
  assertEq(0.65, bad.damping);
});

test('springCurve 换算面：stiffness=(2π/r)²、c=2ζ√(mass·k)', () => {
  const r: number = 0.42;
  const k: number = LiquidSpring.stiffnessOf(r);
  const omega: number = 2 * Math.PI / r;
  assertTrue(Math.abs(k - omega * omega) < 1e-9, 'k = ω²');
  const c: number = LiquidSpring.dampingCoeffOf(r, 0.80, 1);
  assertTrue(Math.abs(c - 2 * 0.80 * Math.sqrt(k)) < 1e-9, 'c = 2ζ√(mass·k)');
  const c2: number = LiquidSpring.dampingCoeffOf(r, 0.80, 4);
  assertTrue(Math.abs(c2 - 2 * c) < 1e-9, 'mass×4 ⇒ c×2（√4）');
});
