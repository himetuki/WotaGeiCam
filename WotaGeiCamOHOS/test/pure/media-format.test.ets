/**
 * 媒体格式化（media/MediaFormat.ets）单测——T23 新增：formatSignedDuration
 * （对比播放页时间线左端标签的负轴显示）。上游无对应物（Compose 侧内联拼串），
 * 期望值一律手写字面量；正/负/0 三向 + formatDuration 基础口径回归。
 */
import { formatDuration, formatSignedDuration } from '../../entry/src/main/ets/media/MediaFormat';

test('formatSignedDuration 正值透传', (): void => {
  assertEq('00:00', formatSignedDuration(0), '0 透传');
  assertEq('00:05', formatSignedDuration(5000), '5s 透传');
  assertEq('01:00', formatSignedDuration(60000), '整分透传');
  assertEq('1:00:00', formatSignedDuration(3600000), '整时透传（h:mm:ss 形）');
  assertEq('00:59', formatSignedDuration(59999), '毫秒截断与 formatDuration 同口径');
});

test('formatSignedDuration 负值带负号且亚秒不为零', (): void => {
  assertEq('-00:05', formatSignedDuration(-5000), '-5s');
  assertEq('-02:30', formatSignedDuration(-150000), '-2m30s');
  assertEq('-1:00:00', formatSignedDuration(-3600000), '-1h');
  assertEq('-00:01', formatSignedDuration(-499), '-499ms 亚秒负值下限 1 秒（避免与 0 混淆）');
  assertEq('-00:01', formatSignedDuration(-500), '-500ms 为对比页偏移步进，必须可见');
  assertEq('-00:01', formatSignedDuration(-1500), '-1.5s 仍按 floor 截断为 1 秒（不向上夸大）');
});

test('formatSignedDuration 与 formatDuration 基础口径对齐', (): void => {
  assertEq('2:00:50', formatSignedDuration(7250000), '正值 2h0m50s 手写字面量');
  assertEq('-2:00:50', formatSignedDuration(-7250000), '负值同串加负号');
  assertEq('02:00', formatDuration(120000), 'formatDuration 基线');
  assertEq('00:00', formatDuration(-1000), 'formatDuration 负值按 0（对照：带符号版不归 0）');
  assertEq('-00:01', formatSignedDuration(-1000), '带符号版负值不归 0');
});
