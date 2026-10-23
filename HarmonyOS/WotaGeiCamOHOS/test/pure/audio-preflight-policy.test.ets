/**
 * 蓝牙预校准策略全表（record/AudioPreflightPolicy.ets 纯函数）——对拍上游
 * app/src/test/java/com/wotagei/cam/AudioPreflightPolicyTest.kt（对齐上游 e950328）。
 *
 * 「尺子自己能红」：每个表都配一个符号写反/门槛写错的突变体，同一把尺必须报出差异。
 *
 * 平台差异登记：
 * - 上游 `预校准与TrackRule符号一致` 里的 TrackRule.shiftUsFor（TrackExport.kt）在 OHOS 侧
 *   的对应面是 player/ClipRule.audioShiftUs（本批落的同一表达式），对拍同式；
 * - 上游 `输出设备类型判A2DP` 吃 Android 常量 intArrayOf(TYPE_BLUETOOTH_A2DP...)；本侧
 *   平台枚举比对移到 record/AudioRoute.ets，纯面收注入布尔（a2dpFromOutputTypes），
 *   期望值按组合语义手写。
 */
import { AudioPreflightPolicy } from '../../entry/src/main/ets/record/AudioPreflightPolicy';
import { audioShiftUs, TrackChoice } from '../../entry/src/main/ets/player/ClipRule';

test('预校准平移全表', (): void => {
  // 低置信：再大的偏移也不动
  assertEq(0, AudioPreflightPolicy.capPreShiftUs(-300, 1.99), '置信 1.99 < 2.0 不动');
  assertEq(0, AudioPreflightPolicy.capPreShiftUs(-300, 2.0, 2.5), '注入门槛 2.5 时 2.0 不动');
  // 内录不早（负数为正延迟 / 低于 20ms）→ 0
  assertEq(0, AudioPreflightPolicy.capPreShiftUs(120, 3.0), '正 offset（内录已晚）不动');
  assertEq(0, AudioPreflightPolicy.capPreShiftUs(0, 3.0), '零 offset 不动');
  assertEq(0, AudioPreflightPolicy.capPreShiftUs(-19, 3.0), '-19ms 低于 20ms 下限不动');
  // 20ms 起效，600ms 封顶
  assertEq(20000, AudioPreflightPolicy.capPreShiftUs(-20, 3.0), '恰 20ms 起效');
  assertEq(300000, AudioPreflightPolicy.capPreShiftUs(-300, 3.0), '300ms 全量');
  assertEq(600000, AudioPreflightPolicy.capPreShiftUs(-600, 3.0), '恰 600ms 封顶值');
  assertEq(600000, AudioPreflightPolicy.capPreShiftUs(-601, 3.0), '601ms 钳到 600');
  assertEq(600000, AudioPreflightPolicy.capPreShiftUs(-9999, 3.0), '超大钳到 600');
});

test('预校准与ClipRule符号一致', (): void => {
  // 两个函数都是 shift = −offset：正偏移（内录晚）永不产生正平移；内录早 = 正平移
  const probes: number[] = [-900, -600, -120, -20, -19, 0, 120];
  for (const offset of probes) {
    const pre: number = AudioPreflightPolicy.capPreShiftUs(offset, 3.0);
    const exportShift: number = audioShiftUs(TrackChoice.CAP, offset);
    if (pre !== 0) {
      assertTrue(
        Math.sign(exportShift) === Math.sign(pre),
        `offset=${offset} 的预校准平移与导出平移必须同号（pre=${pre} export=${exportShift}）`
      );
    }
    // 正 offset（内录已晚）必须明确为 0；负 offset 才允许非负平移
    if (offset > 0) {
      assertEq(0, pre, `正 offset 不许预校准（实际 ${pre}）`);
    } else {
      assertTrue(pre >= 0, `负 offset 的预校准平移不得为负（实际 ${pre}）`);
    }
  }
  // 环境轨永不平移：导出链的真源；预校准根本不碰环境通道
  assertEq(0, audioShiftUs(TrackChoice.ENV, -900), '环境轨导出恒 0');
});

test('突变_符号写反必被表测出', (): void => {
  // 退化实现：把 −offset 写成 +offset（内录晚反而延后，越纠越错）
  const mutant = (offset: number, conf: number): number => {
    if (conf < AudioPreflightPolicy.MIN_CONFIDENCE) {
      return 0;
    }
    if (offset < AudioPreflightPolicy.MIN_PRE_SHIFT_MS) {
      return 0;
    }
    return Math.min(offset, AudioPreflightPolicy.MAX_PRE_SHIFT_MS) * 1000;
  };
  const probes: number[] = [-20, -120, -600];
  let differs: boolean = false;
  for (const p of probes) {
    if (AudioPreflightPolicy.capPreShiftUs(p, 3.0) !== mutant(p, 3.0)) {
      differs = true;
    }
  }
  assertTrue(differs, '符号写反的突变体必须在全表上至少一处与真实现不同');
});

test('预校准门_双音轨且A2DP', (): void => {
  assertTrue(AudioPreflightPolicy.shouldPreflight(true, true, true), '双轨+A2DP 才预校准');
  assertFalse(AudioPreflightPolicy.shouldPreflight(false, true, true), '缺环境轨不跑');
  assertFalse(AudioPreflightPolicy.shouldPreflight(true, false, true), '缺内录轨不跑');
  assertFalse(AudioPreflightPolicy.shouldPreflight(true, true, false), '非 A2DP 不跑');
  assertFalse(AudioPreflightPolicy.shouldPreflight(false, false, false), '全关不跑');
});

test('输出设备判A2DP_注入布尔组合面', (): void => {
  // 列表出现 A2DP（上游兜底判据；OHOS AudioRoute 实际走的路径，活动位恒 false）
  assertTrue(AudioPreflightPolicy.a2dpFromOutputTypes(false, true), '列表含 A2DP = 疑似');
  // 仅扬声器/有线（对应上游 intArrayOf(SPEAKER, WIRED_HEADPHONES)）
  assertFalse(AudioPreflightPolicy.a2dpFromOutputTypes(false, false), '无 A2DP 不疑似');
  // 空设备列表
  assertFalse(AudioPreflightPolicy.a2dpFromOutputTypes(false, false), '空列表不疑似');
  // 活动路由判据（上游第一判据；OHOS 无 API，恒注入 false，登记）
  assertTrue(AudioPreflightPolicy.a2dpFromOutputTypes(true, false), '活动 A2DP 优先成立');
  assertTrue(AudioPreflightPolicy.a2dpFromOutputTypes(true, true), '两判据任一成立即疑似');
});

test('后校准范围与置信门槛', (): void => {
  assertTrue(AudioPreflightPolicy.autoOffsetWithinRange(0, 2.0), '零偏移过线');
  assertTrue(AudioPreflightPolicy.autoOffsetWithinRange(-1000, 2.0), '-1000 恰在界内');
  assertTrue(AudioPreflightPolicy.autoOffsetWithinRange(1000, 9.9), '1000 恰在界内');
  assertFalse(AudioPreflightPolicy.autoOffsetWithinRange(1001, 9.9), '1001 越界');
  assertFalse(AudioPreflightPolicy.autoOffsetWithinRange(-1001, 9.9), '-1001 越界');
  assertFalse(AudioPreflightPolicy.autoOffsetWithinRange(120, 1.99), '置信 1.99 不过线');
});
