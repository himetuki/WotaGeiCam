/**
 * 水平仪角度换算（对齐上游 LevelMathTest.kt 全量），回归用例来自真机：
 * 竖屏下徽标曾读出 −97.9°（超出契约的 ±90）。
 *
 * 纯函数（core/LevelMath.ets）零平台依赖，本文件只做算术断言；传感器驱动不在此列。
 */
import { LevelConst, LevelMath } from '../../entry/src/main/ets/core/LevelMath';

function assertInRange(deg: number): void {
  assertTrue(deg > -90 && deg <= 90, `读数 ${deg} 超出 (-90, 90]`);
}

test('真机竖屏读数不再超出正负90', () => {
  // 实测重力分量 (9.07, -0.85, 3.02)，竖屏 displayDegrees=0
  const display: Array<number> = LevelMath.displayVector(9.07, -0.85, 0);
  const roll: number = LevelMath.rollOf(display[0], display[1]);
  assertInRange(roll);
  // atan2(-9.07, -0.85) ≈ -95.35°，折回半圆 → +84.65°（机头右边朝下 = 屏幕右侧偏低，正号）
  assertTrue(Math.abs(roll - 84.65) < 1.0, `期望 ≈84.65，实得 ${roll}`);
});

test('任意倾角都折进半圆', () => {
  let steps: number = 0;
  for (let deg = 0; deg < 360; deg += 7) {
    const r: number = deg * Math.PI / 180;
    const roll: number = LevelMath.rollOf(-Math.sin(r), Math.cos(r));
    assertInRange(roll);
    steps++;
  }
  assertTrue(steps > 0);
});

test('正负90 边界与整圈折叠', () => {
  assertTrue(Math.abs(LevelMath.foldToHalfCircle(90) - 90) < 0.001);
  assertTrue(Math.abs(LevelMath.foldToHalfCircle(-90) - 90) < 0.001);
  assertTrue(Math.abs(LevelMath.foldToHalfCircle(180) - 0) < 0.001);
  assertTrue(Math.abs(LevelMath.foldToHalfCircle(-180) - 0) < 0.001);
  assertTrue(Math.abs(LevelMath.foldToHalfCircle(210) - 30) < 0.001);
});

test('右侧偏低为正且倒置仍读水平', () => {
  // 重力偏向屏幕左 → 右侧偏低 → 正角
  assertTrue(LevelMath.rollOf(-5, 5) > 0);
  // 左侧偏低 → 负角
  assertTrue(LevelMath.rollOf(5, 5) < 0);
  // 相对显示正好倒置：水平仪应读 0（仍然是水平的）
  assertTrue(Math.abs(LevelMath.rollOf(0, -5) - 0) < 0.001);
});

test('显示帧旋转四象限', () => {
  // 机头「顶边」受重力（sy>0）：竖屏时是屏幕上方偏低
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, 0)[0] - 0) < 0.001);
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, 0)[1] - 5) < 0.001);
  // 横屏 90°：机头顶边重力变成屏幕右侧重力
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, 90)[0] - (-5)) < 0.001);
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, 90)[1] - 0) < 0.001);
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, 180)[0] - 0) < 0.001);
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, 180)[1] - (-5)) < 0.001);
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, 270)[0] - 5) < 0.001);
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, 270)[1] - 0) < 0.001);
  // 负角与非 90 倍数输入要归一：-270 等价 90
  assertTrue(Math.abs(LevelMath.displayVector(0, 5, -270)[0] - (-5)) < 0.001);
});

test('俯仰角语义', () => {
  // 平放、镜头朝上：俯角最大
  assertTrue(Math.abs(LevelMath.pitchOf(0, 0, 9.8) - (-90)) < 0.001);
  // 光轴水平
  assertTrue(Math.abs(LevelMath.pitchOf(0, 9.8, 0) - 0) < 0.001);
  // 仰 45°
  assertTrue(Math.abs(LevelMath.pitchOf(0, 6.93, -6.93) - 45) < 0.5);
});

test('回正判据与容差常量', () => {
  assertEq(1.5, LevelConst.LEVEL_TOLERANCE_DEG, '容差是 1.5°（UI 着色与回正震动共用）');
  assertTrue(LevelMath.isLevelOf(0));
  assertTrue(LevelMath.isLevelOf(1.49));
  assertFalse(LevelMath.isLevelOf(1.5), '边界 1.5 不算回正（严格小于）');
  assertFalse(LevelMath.isLevelOf(-1.5));
  assertTrue(LevelMath.isLevelOf(-1.49));
  // 常量口径（低通/节流/丢帧闸）
  assertEq(0.30, LevelConst.SMOOTH_ALPHA, '低通系数');
  assertEq(0.1, LevelConst.EMIT_STEP_DEG, '发布节流步长');
  assertEq(0.6, LevelConst.MIN_VALID_G, '有效重力量下限');
  assertEq(0.25, LevelConst.FLAT_GUARD, '平放时的 roll 失真护栏');
});
