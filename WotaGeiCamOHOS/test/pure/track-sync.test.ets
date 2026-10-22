/**
 * 双音轨偏移合成与结果分类（player/TrackSync.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/TrackSyncTest.kt（对齐上游 38d712a）。
 *
 * 口径订正（T26 审查）：上游 TrackSyncTest 共 12 个 @Test = **11 条纯函数用例**
 * （合成 4 + resolve 2 + signed 3 + 失败分类 2）+ **1 条 extractor 源码结构守卫**
 * （依赖平台文件，本侧不移植，随 T27 以 ArkTS 结构检查补）。
 * 本侧 11 条覆盖全部 11 条纯函数用例；其中 `MIN_CONFIDENCE 同源` 是本侧**额外断言**
 * （钉 TrackSync.MIN_CONFIDENCE === AudioSync.MIN_CONFIDENCE 门槛复用），上游无此断言。
 * 期望值一律手写字面量。
 */
import { AudioSync } from '../../entry/src/main/ets/player/AudioSync';
import { TrackSync, TrackSyncFail } from '../../entry/src/main/ets/player/TrackSync';

test('合成 两轨同时起步只看内容差', (): void => {
  // 环境首样本 1_000_000us、内录首样本 1_000_000us：起点差 0，内容差即总偏移
  assertEq(120, TrackSync.combineOffsetMs(120, 1000000, 1000000), '起点差 0');
});

test('合成 内录首样本晚10ms要加进总偏移', (): void => {
  assertEq(130, TrackSync.combineOffsetMs(120, 1010000, 1000000), '内容差+10ms');
});

test('合成 负偏移与起点早的情形', (): void => {
  // 内录内容早 40ms、但轨起点早 5ms：-40 + (-5) = -45
  assertEq(-45, TrackSync.combineOffsetMs(-40, 995000, 1000000), '负内容差+早起点');
  // 负数向零截断（Kotlin Long 整除语义）：-5.5ms → -5，不是 floor 的 -6
  assertEq(-45, TrackSync.combineOffsetMs(-40, 994500, 1000000), '负亚毫秒向零截断');
});

test('合成 亚毫秒起点差向下取整', (): void => {
  // 1_500us 差 = 1.5ms：µs→ms 整除向下取 1ms（1ms 级误差在听感与导出语义下可忽略）
  assertEq(121, TrackSync.combineOffsetMs(120, 1001500, 1000000), '1.5ms → 1ms');
});

test('低置信度归零', (): void => {
  assertEq(0, TrackSync.resolveOffsetMs(357, true), '低置信度按录制起点对齐');
  assertEq(0, TrackSync.resolveOffsetMs(-357, true), '负偏移同样归零');
});

test('正常置信度原样保留', (): void => {
  assertEq(357, TrackSync.resolveOffsetMs(357, false), '正偏移');
  assertEq(-42, TrackSync.resolveOffsetMs(-42, false), '负偏移');
});

test('读数 正数带加号', (): void => {
  assertEq('+120', TrackSync.signedMs(120), '+120');
});

test('读数 负数自带减号', (): void => {
  assertEq('-45', TrackSync.signedMs(-45), '-45');
});

test('读数 零不带号', (): void => {
  assertEq('0', TrackSync.signedMs(0), '0');
});

test('失败分类 结构非双轨 如实报没有音轨', (): void => {
  const r: TrackSyncFail = TrackSync.failResult(false);
  assertFalse(r.ok, 'ok=false');
  assertFalse(r.dualAudio, '结构非双轨');
  assertEq('no-audio', r.reasonCode, '没有音轨');
});

test('失败分类 双轨在而读不出 报同步失败不误报没有音轨', (): void => {
  const r: TrackSyncFail = TrackSync.failResult(true);
  assertFalse(r.ok, 'ok=false');
  assertTrue(r.dualAudio, 'dualAudio 保持真');
  assertEq('sync-failed', r.reasonCode, '同步失败请重试');
  // 低置信度门槛复用 AudioSync 单一真源
  assertEq(AudioSync.MIN_CONFIDENCE, TrackSync.MIN_CONFIDENCE, 'MIN_CONFIDENCE 同源');
  assertEq(1.5, TrackSync.MIN_CONFIDENCE, '门槛字面量');
});
