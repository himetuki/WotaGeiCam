/**
 * 内录静音判据与按场节流（record/CaptureSilence.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/record/CaptureSilenceVerdictTest.kt 的纯函数/锁存面
 * （源码结构守卫与 UI 通道那几条依赖平台文件，不移植；对齐上游 38d712a）。
 *
 * 5 条：常量对账 / verdict 全表（含 minMs 边界与默认门槛）/ fedMsOf / gate 语义 / 判据×门控桥。
 * 期望值一律手写字面量。
 */
import {
  CAPTURE_SILENCE_LIVE_MIN_MS, CAPTURE_SILENCE_MIN_MS, SILENCE_REPROMPT_MS, SilencePromptGate,
  captureSilenceVerdict, fedMsOf
} from '../../entry/src/main/ets/record/CaptureSilence';

test('静音判据常量与取值对账', (): void => {
  assertEq(2000, CAPTURE_SILENCE_MIN_MS, '收尾兜底门槛 2s');
  assertEq(5000, CAPTURE_SILENCE_LIVE_MIN_MS, '录制中巡检门槛 5s');
  assertEq(60000, SILENCE_REPROMPT_MS, '同场重弹节流窗 60s');
});

test('captureSilenceVerdict全表 在场x时长x非零', (): void => {
  // 全表：hasSamples × 门槛端点 ±1 × sawNonZero；期望 = 在场 && 过门槛 && 未见非零
  const durations: Array<number> = [1999, 2000, 2001];
  const flags: Array<boolean> = [true, false];
  for (const hasSamples of flags) {
    for (const durationMs of durations) {
      for (const sawNonZero of flags) {
        const expected: boolean = hasSamples && durationMs >= 2000 && !sawNonZero;
        assertEq(expected, captureSilenceVerdict(hasSamples, durationMs, sawNonZero),
          `全表 hasSamples=${hasSamples} duration=${durationMs} sawNonZero=${sawNonZero}`);
      }
    }
  }
  // 录制中门槛（显式 minMs=5s）：4999 不判、5000 起判（含端点）、无边界的样本仍 false
  assertFalse(captureSilenceVerdict(true, 4999, false, CAPTURE_SILENCE_LIVE_MIN_MS), '5000-1 不判');
  assertTrue(captureSilenceVerdict(true, 5000, false, CAPTURE_SILENCE_LIVE_MIN_MS), '恰在门槛（含端点）');
  assertTrue(captureSilenceVerdict(true, 7760, false, CAPTURE_SILENCE_LIVE_MIN_MS), '真机实证 7.76s 全零');
  assertFalse(captureSilenceVerdict(true, 7760, true, CAPTURE_SILENCE_LIVE_MIN_MS), '见过非零不判');
  assertFalse(captureSilenceVerdict(false, 600000, false, CAPTURE_SILENCE_LIVE_MIN_MS), '无样本不判');
});

test('fedMsOf换算口径 实喂字节到毫秒', (): void => {
  // 48kHz 立体声帧 4 字节：5000ms = 48000×4×5 = 960000 字节
  assertEq(5000, fedMsOf(960000, 48000, 2), '48k/2ch 5s');
  assertEq(0, fedMsOf(0, 48000, 2), '零字节');
  // 单声道（帧 2 字节）：44100Hz × 2B × 3s = 264600
  assertEq(3000, fedMsOf(264600, 44100, 1), '44.1k/1ch 3s');
  // 不足 1ms 向下取整（口径同整数除法）
  assertEq(0, fedMsOf(47, 48000, 2), '47B 不足 1ms');
  assertEq(1, fedMsOf(192, 48000, 2), '192B = 1ms');
  // 防御：非法采样率/声道归 0（平台面不该传，纯函数不抛）
  assertEq(0, fedMsOf(960000, 0, 2), '采样率 0 归 0');
  assertEq(0, fedMsOf(960000, 48000, 0), '声道 0 归 0');
});

test('按场节流锁存语义 首触放行 60s节流 按场复位', (): void => {
  const gate: SilencePromptGate = new SilencePromptGate();
  assertTrue(gate.tryFire(10000), '本场首触必须放行');
  assertFalse(gate.tryFire(10000 + 59999), '距上次不足 60s 必须拦下');
  assertTrue(gate.tryFire(10000 + 60000), '距上次恰 60s 必须再放行（含端点）');
  assertFalse(gate.tryFire(10000 + 60000 + 1), '再放行后重新进入节流');
  gate.resetTake();
  assertTrue(gate.tryFire(900000), '复位后按本场首触放行（连录两场都要提示）');
  gate.resetTake();
  assertTrue(gate.tryFire(0), 'now=0 不得把首触误判成节流（fired 与时刻解耦）');
});

test('桥 每场一次 同场不叠条 非零后永久停', (): void => {
  const gate: SilencePromptGate = new SilencePromptGate();
  // 第一场：巡检首发（5s 仍全零）→ 弹；同场收尾兜底不叠条
  assertTrue(captureSilenceVerdict(true, 5000, false, CAPTURE_SILENCE_LIVE_MIN_MS) &&
    gate.tryFire(5000), '第一场巡检首发必须弹');
  assertFalse(captureSilenceVerdict(true, 30000, false) && gate.tryFire(30000), '同场收尾兜底不叠条');
  // 第二场（引擎 start 复位）：仍静音要能再提示
  gate.resetTake();
  assertTrue(captureSilenceVerdict(true, 5000, false, CAPTURE_SILENCE_LIVE_MIN_MS) &&
    gate.tryFire(1000000), '连录两场第二场仍静音必须能再提示');
  // 见过非零：判据侧永停（两个触发点都拿不到 true）
  assertFalse(captureSilenceVerdict(true, 600000, true, CAPTURE_SILENCE_LIVE_MIN_MS), '巡检判据永停');
  assertFalse(captureSilenceVerdict(true, 600000, true), '收尾兜底判据永停');
  // 节流锁存本身不拦长场次（>60s 会再放行）——非零永停必须靠判据 sawNonZero
  assertTrue(captureSilenceVerdict(true, 700000, false) && gate.tryFire(1000000 + SILENCE_REPROMPT_MS),
    '长场次节流会再放行（非零永停只能由判据侧保证）');
});
