/**
 * 对比页练习桥（player/ComparePractice.ets）的状态语义单测——对拍上游
 * app/src/test/java/com/wotagei/cam/ComparePracticeTest.kt（对齐上游 38d712a）。
 *
 * 覆盖：armed 门 + pendingRightId 记账 + 参考片 takeLeftId 一次性取走 + 普通录制不污染。
 * 每个用例先 reset（上游 @After 的等价物：清公开态并经 takeLeftId 清私有 pendingLeftId）。
 *
 * **分屏练拍页面未移植，动线接线阻塞**（T25+ 随对比页接线/录制页分屏模式一起接）。
 */
import { ComparePractice, comparePractice } from '../../entry/src/main/ets/player/ComparePractice';

function resetPractice(): void {
  comparePractice.armed = false;
  comparePractice.pendingRightId = undefined;
  // pendingLeftId 是 private，经 takeLeftId 清空
  comparePractice.takeLeftId();
}

test('prime 置armed并记左片', (): void => {
  resetPractice();
  comparePractice.primeWith(77);
  assertTrue(comparePractice.armed, 'armed 置真');
  assertEq(77, comparePractice.takeLeftId() ?? -1, '取到参考片 77');
  // 意图仍在（等录完 deliver）
  assertTrue(comparePractice.armed, '取参考片不清 armed');
});

test('takeLeftId 读后即清 二次为空', (): void => {
  resetPractice();
  comparePractice.primeWith(77);
  assertEq(77, comparePractice.takeLeftId() ?? -1, '首次取到');
  assertNull(comparePractice.takeLeftId(), '二次为空（一次性取走）');
  // 未发起时直接取也是空
  resetPractice();
  assertNull(comparePractice.takeLeftId(), '未发起为空');
  assertFalse(comparePractice.armed, '未发起不 armed');
});

test('未armed的deliver拒绝且不记账', (): void => {
  resetPractice();
  assertFalse(comparePractice.deliver(7), '未 armed 拒绝');
  assertNull(comparePractice.pendingRightId, '不写 pendingRight');
});

test('armed时deliver记账并消费armed', (): void => {
  resetPractice();
  comparePractice.armed = true;
  assertTrue(comparePractice.deliver(42), '属练习动线');
  assertEq(42, comparePractice.pendingRightId ?? -1, '记账 42');
  assertFalse(comparePractice.armed, '消费即关闸');
});

test('二次deliver拒绝且保留原记账', (): void => {
  resetPractice();
  comparePractice.armed = true;
  assertTrue(comparePractice.deliver(42), '首次成功');
  // 消费即关闸：第二次投递是另一条不属练习动线的停止
  assertFalse(comparePractice.deliver(43), '二次拒绝');
  assertEq(42, comparePractice.pendingRightId ?? -1, '保留首次记账');
});

test('普通录制不污染pendingRight', (): void => {
  resetPractice();
  // 情形 A：自愈清态后普通录制停止
  comparePractice.armed = true;
  comparePractice.pendingRightId = undefined;
  comparePractice.armed = false;
  assertFalse(comparePractice.deliver(9), '自愈后 deliver 拒绝');
  assertNull(comparePractice.pendingRightId, '不写 pendingRight');
  // 情形 B：已有待消费记账时，普通录制停止不得覆盖
  comparePractice.pendingRightId = 5;
  assertFalse(comparePractice.deliver(10), '普通录制拒绝');
  assertEq(5, comparePractice.pendingRightId ?? -1, '原记账保持不变');
});
