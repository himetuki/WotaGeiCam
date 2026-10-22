/**
 * 起录自检判定（record/RecordHealth.ets）的纯函数单测——直译上游
 * app/src/test/java/com/wotagei/cam/record/RecordHealthTest.kt 的**已移植面**
 * （对齐上游 38d712a；上游 MR 专用段 shouldDiscardAbandonedSink/segmentKeepDecision/
 * mrRotateRequested/mrReachedMaxFilesize/rotateSurfaceHandoffNeeded/engineWatchdogTrips
 * 本侧未移植，不进本文件）。
 *
 * 期望值一律手写字面量（不调被测实现反推）。判定顺序五条各被至少一条断言点名：
 * 1. errorCode 非空 → 立即判废（两格：三格全真 / 窗口未到）；
 * 2. 三格全真 → 健康（两格：窗内 / 超窗仍健康）；
 * 3. !videoTrackAdded 且过视频短窗 → 判废（1199/1200/1201 边界）；
 * 4. 过完整窗未立齐 → 判废（音轨慢形态 2999/3000、建轨无首样本）；
 * 5. 其余 → WAIT。
 * 平台差异：上游 errorCode: String? 用 null 表示无错，本侧用 ''（空串）。
 */
import {
  FULL_WINDOW_MS, HealthVerdict, MAX_RESTART_ATTEMPTS, MIN_OUTPUT_BYTES,
  RecordHealthWindow, RetryAction, VIDEO_WINDOW_MS, healthVerdict, resolveOutputBytes,
  retryActionOf, streamHealthVerdict
} from '../../entry/src/main/ets/record/RecordHealth';

const window: RecordHealthWindow = new RecordHealthWindow(1200, 3000, 2);

test('常量档位是既定设计值', (): void => {
  // 视频路 1200ms（首帧正常 <300ms 留 4 倍余量）；完整含音轨 3000ms；
  // 最多重启 2 次（含首个共 3 次机会）；MR 路产出活性门槛 64KiB（挡"只写容器头"的假活）
  assertEq(1200, VIDEO_WINDOW_MS, 'VIDEO_WINDOW_MS');
  assertEq(3000, FULL_WINDOW_MS, 'FULL_WINDOW_MS');
  assertEq(2, MAX_RESTART_ATTEMPTS, 'MAX_RESTART_ATTEMPTS');
  assertEq(65536, MIN_OUTPUT_BYTES, 'MIN_OUTPUT_BYTES');
});

test('三格全真且无错误即健康（判定顺序第 2 条）', (): void => {
  assertEq(HealthVerdict.HEALTHY, healthVerdict(
    true, true, true, '', 0, window), '三格全真窗内');
});

test('健康优先于超窗 三格全真即使超过窗口也健康', (): void => {
  assertEq(HealthVerdict.HEALTHY, healthVerdict(
    true, true, true, '', 9999, window), '三格全真远超窗');
});

test('errorCode 非空即使三格全真也判废（判定顺序第 1 条）', (): void => {
  assertEq(HealthVerdict.UNHEALTHY, healthVerdict(
    true, true, true, 'engine-error', 10, window), 'engine-error 三格全真');
});

test('errorCode 非空且窗口未到也立刻判废', (): void => {
  assertEq(HealthVerdict.UNHEALTHY, healthVerdict(
    false, false, false, 'engine-error:1:2', 5, window), '复合错误码窗口未到');
});

test('三格未立齐且两窗都未到即等待（判定顺序第 5 条）', (): void => {
  assertEq(HealthVerdict.WAIT, healthVerdict(
    false, false, false, '', 180, window), '两窗未到');
});

test('视频路短窗毫无音讯即判废 不等长窗（判定顺序第 3 条）', (): void => {
  assertEq(HealthVerdict.WAIT, healthVerdict(
    false, false, false, '', 1199, window), '1199ms 仍等');
  // 恰好到视频窗也算超窗（边界取 >= 而非 >）
  assertEq(HealthVerdict.UNHEALTHY, healthVerdict(
    false, false, false, '', 1200, window), '1200ms 判废');
  assertEq(HealthVerdict.UNHEALTHY, healthVerdict(
    false, false, false, '', 1201, window), '1201ms 判废');
});

test('音轨慢时短窗不误判 长窗到点才判废（判定顺序第 4 条）', (): void => {
  // 视频编码器建成（videoTrackAdded=true），muxer 待音轨 addTrack 才 start：
  // 视频窗到点但视频路已活 ⇒ 继续等，只在长窗到点判废
  assertEq(HealthVerdict.WAIT, healthVerdict(
    false, false, true, '', 1199, window), '1199ms 等');
  assertEq(HealthVerdict.WAIT, healthVerdict(
    false, false, true, '', 1200, window), '1200ms 视频路已活继续等');
  assertEq(HealthVerdict.WAIT, healthVerdict(
    false, false, true, '', 2999, window), '2999ms 等');
  assertEq(HealthVerdict.UNHEALTHY, healthVerdict(
    false, false, true, '', 3000, window), '3000ms 判废');
});

test('视频已建轨但首样本不到 长窗到点判废', (): void => {
  assertEq(HealthVerdict.WAIT, healthVerdict(
    false, true, true, '', 2999, window), '2999ms 等');
  assertEq(HealthVerdict.UNHEALTHY, healthVerdict(
    false, true, true, '', 3000, window), '3000ms 判废');
});

test('桥函数 判废且未触顶即重录', (): void => {
  assertEq(RetryAction.RESTART, retryActionOf(0, window, HealthVerdict.UNHEALTHY), 'attempts=0');
  assertEq(RetryAction.RESTART, retryActionOf(1, window, HealthVerdict.UNHEALTHY), 'attempts=1');
});

test('桥函数 判废且触顶即认输', (): void => {
  // attempts=2 = 已重录 2 次（含首个共 3 次机会用尽）→ 必须认输；
  // 把桥上边界写成 <= 会让这一格变 RESTART，本断言当场红
  assertEq(RetryAction.GIVE_UP, retryActionOf(2, window, HealthVerdict.UNHEALTHY), 'attempts=2');
  assertEq(RetryAction.GIVE_UP, retryActionOf(3, window, HealthVerdict.UNHEALTHY), 'attempts=3');
});

test('桥函数 健康或等待都不动作', (): void => {
  assertEq(RetryAction.NONE, retryActionOf(0, window, HealthVerdict.HEALTHY), '健康');
  assertEq(RetryAction.NONE, retryActionOf(0, window, HealthVerdict.WAIT), '等待');
  assertEq(RetryAction.NONE, retryActionOf(9, window, HealthVerdict.HEALTHY), '健康且 attempts 超界');
});

test('产出活性 字节够量即健康', (): void => {
  // 恰好门槛即健康（边界取 >= 而非 >）
  assertEq(HealthVerdict.HEALTHY, streamHealthVerdict(65536, '', 0, window), '恰好门槛');
  assertEq(HealthVerdict.HEALTHY, streamHealthVerdict(999999, '', 3000, window), '远超门槛且超窗');
});

test('产出活性 差一字节即未够量 窗口未到则等待', (): void => {
  assertEq(HealthVerdict.WAIT, streamHealthVerdict(65535, '', 180, window), '差一字节窗内');
});

test('产出活性 只写容器头的假活不算健康', (): void => {
  // <1KB 是"一帧都没写、只剩 ftyp/moov 头"的形态：窗口内等、窗口末判废
  assertEq(HealthVerdict.WAIT, streamHealthVerdict(1000, '', 180, window), '窗口内等');
  assertEq(HealthVerdict.UNHEALTHY, streamHealthVerdict(1000, '', 3000, window), '窗口末判废');
});

test('产出活性 窗口末字节不足即判废 恰好到窗算超窗', (): void => {
  assertEq(HealthVerdict.WAIT, streamHealthVerdict(65535, '', 2999, window), '2999ms 等');
  assertEq(HealthVerdict.UNHEALTHY, streamHealthVerdict(65535, '', 3000, window), '3000ms 判废');
  assertEq(HealthVerdict.UNHEALTHY, streamHealthVerdict(65535, '', 3001, window), '3001ms 判废');
});

test('产出活性 errorCode 非空即使字节足量也判废', (): void => {
  assertEq(HealthVerdict.UNHEALTHY, streamHealthVerdict(
    10000000, 'engine-error', 0, window), '足量但有错');
});

test('产出活性 不可观测时两个时刻都等待 不误杀', (): void => {
  // fstat 量不到可信值时引擎传 -1：判定必须退回"只认错误码"，窗口末也不得判废
  assertEq(HealthVerdict.WAIT, streamHealthVerdict(-1, '', 180, window), '窗内');
  assertEq(HealthVerdict.WAIT, streamHealthVerdict(-1, '', 3000, window), '窗口末');
  assertEq(HealthVerdict.WAIT, streamHealthVerdict(-1, '', 99999, window), '远超窗');
});

test('产出活性 errorCode 与字节同时异常时 errorCode 优先', (): void => {
  assertEq(HealthVerdict.UNHEALTHY, streamHealthVerdict(
    -1, 'stop-failed', 100, window), '不可观测+错');
  assertEq(HealthVerdict.UNHEALTHY, streamHealthVerdict(
    0, 'engine-error:1:2', 100, window), '零字节+复合错');
});

test('桥函数 产出活性判废也走同一重录上界', (): void => {
  const verdict: HealthVerdict = streamHealthVerdict(0, '', 3000, window);
  assertEq(HealthVerdict.UNHEALTHY, verdict, '长窗零字节判废');
  assertEq(RetryAction.RESTART, retryActionOf(0, window, verdict), '未触顶重录');
  assertEq(RetryAction.GIVE_UP, retryActionOf(2, window, verdict), '触顶认输');
});

test('产出字节双源取可用源最大值', (): void => {
  assertEq(-1, resolveOutputBytes(-1, -1), '两源都量不到 ⇒ 不可观测 -1');
  assertEq(0, resolveOutputBytes(-1, 0), 'fd 量不到 provider 说 0 ⇒ 0');
  assertEq(0, resolveOutputBytes(0, -1), 'fd 说 0 provider 量不到 ⇒ 0');
  assertEq(5, resolveOutputBytes(5, -1), '只有 fd 有数 ⇒ 取 fd');
  assertEq(7, resolveOutputBytes(-1, 7), '只有 provider 有数 ⇒ 取 provider');
  assertEq(9, resolveOutputBytes(3, 9), '两源都有数 ⇒ 取大（provider 大）');
  assertEq(9, resolveOutputBytes(9, 3), '两源都有数 ⇒ 取大（fd 大）');
  assertEq(65536, resolveOutputBytes(65536, -1), 'fd 已够量时取 fd');
});
