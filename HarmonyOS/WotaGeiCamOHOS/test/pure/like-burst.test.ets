/**
 * 收藏红心动画纯函数全表——对拍上游 LikeBurst 数学（a3855c3，Bencho "Like" 红心部分）
 * + 宿主接线守卫（全屏查看器星钮换装 LikeHeart）。
 */
import { LikeBurst } from '../../entry/src/main/ets/core/LikeBurst';

test('时间线常量=设计稿原值', () => {
  assertEq(820, LikeBurst.DURATION_MS, '主进度总时长');
  assertEq(560, LikeBurst.BLOOM_MS);
  assertEq(760, LikeBurst.HEART_MS);
  assertEq(200, LikeBurst.DOT_START_MS, '彩点延迟');
  assertEq(620, LikeBurst.DOT_DURATION_MS);
  assertEq(17, LikeBurst.BLOOM_RADIUS_DP, '绽放环半径');
  assertEq(0.5, LikeBurst.FLY_DEFAULT);
});

test('红心缩放：0~28% 不可见 → 62% 弹到 1.25 → 100% 回 1', () => {
  assertEq(0, LikeBurst.heartScale(0), '起飞前不可见');
  assertEq(0, LikeBurst.heartScale(0.2), '28% 前（0.2×820=164ms < 28%×760）');
  // 峰值在心相 62%：t = 0.62×760/820
  const peak: number = LikeBurst.heartScale(0.62 * 760 / 820);
  assertTrue(Math.abs(peak - 1.25) < 0.01, `心相 62% 峰值 1.25（实际 ${peak.toFixed(3)}）`);
  assertTrue(Math.abs(LikeBurst.heartScale(1) - 1) < 0.01, '主进度 100% 回 1');
});

test('绽放环：0.15 实心起步 → 40% 满 → 75% 变环 → 100% 淡出', () => {
  assertTrue(Math.abs(LikeBurst.bloomScale(0) - 0.15) < 1e-6);
  assertTrue(Math.abs(LikeBurst.bloomScale(0.4 * 560 / 820) - 1) < 0.01, '40% 满径');
  assertEq(1, LikeBurst.bloomBorderFraction(0), '起步实心（边框=半径）');
  assertTrue(Math.abs(LikeBurst.bloomBorderFraction(0.5) - 2 / 17) < 0.05, '75% 前收缩到 2/17 带');
  assertEq(0, LikeBurst.bloomBorderFraction(1), '终帧边框 0（完全空掉）');
  assertEq(1, LikeBurst.bloomAlpha(0.5), '75% 前不透明');
  assertEq(0, LikeBurst.bloomAlpha(1), '终帧淡出');
});

test('红转紫：心相 40% 一次跳位（t=0.2 ↔ 相 0.293、t=0.35 ↔ 相 0.513）', () => {
  assertEq(0, LikeBurst.bloomPurple(0.2), '相 40% 前红');
  assertEq(1, LikeBurst.bloomPurple(0.35), '相 40% 后紫');
  const red: number = LikeBurst.bloomColorArgb(0.2);
  const purple: number = LikeBurst.bloomColorArgb(0.35);
  assertEq(0xFFE5484D, red, '设计稿红');
  assertEq(0xFFCC8EF5, purple, '设计稿紫');
});

test('彩点：200ms 延迟、径向 12→(18+fly·14+far)、终点缩 0、12% 淡入', () => {
  assertEq(-1, LikeBurst.dotProgress(0), '延迟窗口内 = -1');
  assertEq(0, LikeBurst.dotProgress(200 / 820), '延迟结束起跑');
  assertTrue(Math.abs(LikeBurst.dotProgress(1) - 1) < 0.01);
  // 径向：pair0 far=0 → 12+(6+7+0)·p = 12+13p；pair1 far=-5 → 12+8p
  assertTrue(Math.abs(LikeBurst.dotRadiusPx(1, 0.5, 0) - 25) < 1e-6, 'pair0 终径 25px');
  assertTrue(Math.abs(LikeBurst.dotRadiusPx(1, 0.5, -5) - 20) < 1e-6, 'pair1 终径 20px（略靠后）');
  assertEq(0, LikeBurst.dotRadiusPx(-0.5, 0.5, 0), '未起飞不画');
  assertTrue(Math.abs(LikeBurst.dotSidePx(1, 4) - 6.4) < 1e-6, '侧向终点 ±1.6 倍');
  assertEq(0, LikeBurst.dotScale(1), '终点缩 0');
  assertEq(0, LikeBurst.dotAlpha(-0.5), '未起飞透明');
  assertEq(1, LikeBurst.dotAlpha(0.5), '12% 后恒 1');
});

test('辐条角与取色下标', () => {
  assertEq(-90, LikeBurst.dotAngleDeg(0), '0 号辐条朝上');
  assertEq(Math.round(360 / 7) - 90, Math.round(LikeBurst.dotAngleDeg(1)), '等分 7 条');
  assertEq(0, LikeBurst.dotColorIndex(0, 0));
  assertEq(3, LikeBurst.dotColorIndex(0, 1), 'pair1 = k+3');
  assertEq(3, LikeBurst.dotColorIndex(3, 0), 'pair0 = k');
  assertEq(6, LikeBurst.dotColorIndex(3, 1), 'pair1 = k+3');
});

test('接线：全屏查看器星钮换装 LikeHeart', () => {
  const { existsSync, readFileSync } = require('node:fs');
  const { resolve } = require('node:path');
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/core/CameraAbility.ets'))) {
      break;
    }
    dir = resolve(dir, '..');
  }
  const page: string = readFileSync(resolve(dir, 'entry/src/main/ets/pages/Gallery.ets'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
  assertTrue(page.indexOf('LikeHeart({') >= 0, '星钮宿主换装');
  assertTrue(page.indexOf("toggleLike(this.fsCurrentClip()!)") >= 0, '收藏链不变（toggleLike 复用）');
});
