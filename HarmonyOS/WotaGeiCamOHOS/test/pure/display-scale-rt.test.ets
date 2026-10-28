/**
 * 上屏降档纯函数全表（对齐上游 DisplayScaleRtTest.kt 真值，1e61e15）。
 * 本侧只交付纯函数层：OHOS 预览链无中间取景器 RT，native 缩放上屏链随真机批（文件头注登记）。
 */
import {
  DisplayScale, MIN_DISPLAY_RT_EDGE, displayRtNeedsRebuild, displayScaleReadoutLabel,
  nearestDisplayScale, resolveDisplayRtInto, resolveDisplayRtOrNull
} from '../../entry/src/main/ets/core/DisplayScaleRt';

test('等比区按除数floor缩放', () => {
  const out: Array<number> = [0, 0];
  assertTrue(resolveDisplayRtInto(out, 1080, 1920, 2));
  assertEq(540, out[0]);
  assertEq(960, out[1]);
  // floor：997/2 = 498（不四舍五入）
  assertTrue(resolveDisplayRtInto(out, 997, 1999, 2));
  assertEq(498, out[0]);
  assertEq(999, out[1]);
});

test('任一维低于128不可用且不写数组', () => {
  const out: Array<number> = [-7, -7];
  assertFalse(resolveDisplayRtInto(out, 255, 1920, 2), '255/2=127 < 128 ⇒ 不可用');
  assertEq(-7, out[0], '不可用时不写调用方数组');
  assertEq(-7, out[1]);
  assertFalse(resolveDisplayRtInto(out, 1920, 255, 2));
  assertEq(-7, out[0]);
  assertTrue(resolveDisplayRtInto(out, 256, 256, 2), '128 恰好可用（含边界）');
  assertEq(128, out[0]);
  assertEq(128, MIN_DISPLAY_RT_EDGE, '阈值是常量（三处共用同一份）');
});

test('非法输入返回false', () => {
  const out: Array<number> = [-7, -7];
  assertFalse(resolveDisplayRtInto(out, 0, 1920, 2));
  assertFalse(resolveDisplayRtInto(out, 1080, 0, 2));
  assertFalse(resolveDisplayRtInto(out, 1080, 1920, 0));
  assertFalse(resolveDisplayRtInto(out, 1080, 1920, -2));
  assertEq(-7, out[0]);
});

test('输出数组不足两位是编程错误', () => {
  let threw: boolean = false;
  try {
    resolveDisplayRtInto([0], 1080, 1920, 2);
  } catch (e) {
    threw = true;
  }
  assertTrue(threw);
});

test('Pair版与热点版同判', () => {
  assertTrue(resolveDisplayRtOrNull(1080, 1920, 2) !== undefined);
  assertEq(undefined, resolveDisplayRtOrNull(255, 1920, 2));
  const rt: Array<number> = resolveDisplayRtOrNull(1080, 1920, 2) as Array<number>;
  assertEq(540, rt[0]);
  assertEq(960, rt[1]);
});

test('回落从下一档开始找_不回current_防无限循环', () => {
  // current=16 刚失败：从 8 开始找；1080×1920 下 8 = 135×240（≥128 可用）⇒ 落 8
  assertEq(8, nearestDisplayScale(1080, 1920, DisplayScale.SIXTEENTH).divisor,
    '绝不返回刚失败的 16');
  // current=8 刚失败：从 4 找 → 270×480 可用 ⇒ 4
  assertEq(4, nearestDisplayScale(1080, 1920, DisplayScale.EIGHTH).divisor);
  // current=2 刚失败：下一档 1==ORIG 的除数，循环不进 ⇒ ORIG
  assertEq(1, nearestDisplayScale(1080, 1920, DisplayScale.HALF).divisor);
  // current=ORIG 已到底
  assertEq(1, nearestDisplayScale(1080, 1920, DisplayScale.ORIG).divisor);
  // 等比区还没量到 ⇒ ORIG（不猜）
  assertEq(1, nearestDisplayScale(0, 0, DisplayScale.SIXTEENTH).divisor);
  // 小等比区：720×1280 下 8 = 90×160 < 128 不可用 → 4 = 180×320 可用
  assertEq(4, nearestDisplayScale(720, 1280, DisplayScale.EIGHTH).divisor, '中间档不可用时继续往下');
});

test('读数标签_原片用本地化标签_档位用除数字面量', () => {
  assertEq('原片', displayScaleReadoutLabel(DisplayScale.ORIG, '原片'));
  assertEq('2', displayScaleReadoutLabel(DisplayScale.HALF, '原片'));
  assertEq('16', displayScaleReadoutLabel(DisplayScale.SIXTEENTH, '原片'));
});

test('重建判据只看尺寸_与开关无关', () => {
  assertFalse(displayRtNeedsRebuild(540, 960, 540, 960), '同尺寸不重建');
  assertTrue(displayRtNeedsRebuild(540, 960, 540, 961), '任一维变即重建');
  assertTrue(displayRtNeedsRebuild(540, 960, 270, 480), '换档=换尺寸');
  assertFalse(displayRtNeedsRebuild(540, 960, 0, 0), '目标非法返回 false（不在这里拆资源）');
});

test('fromDivisor_未知除数回ORIG', () => {
  assertEq(DisplayScale.ORIG, DisplayScale.fromDivisor(1));
  assertEq(DisplayScale.EIGHTH, DisplayScale.fromDivisor(8));
  assertEq(DisplayScale.ORIG, DisplayScale.fromDivisor(3), '非 2 的幂不是合法档');
});
