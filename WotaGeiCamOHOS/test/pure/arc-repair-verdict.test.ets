/**
 * 离线修复成功/可保留判据（record/ArcRepairVerdict.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/ArcRepairOkTest.kt 的纯函数面（对齐上游 38d712a）。
 *
 * 5 条：三格全真+样本≥1、零样本整废、前两格任一假、负样本不算写出、keep 四组合表。
 * 上游第 6 条是 ArcRepairRunner 的源码结构守卫（依赖平台文件），登记 T29 随执行器批补。
 */
import { arcRepairKeep, arcRepairOk } from '../../entry/src/main/ets/record/ArcRepairVerdict';

test('三格全真且样本至少一枚 判成功', (): void => {
  assertTrue(arcRepairOk(true, true, 1), '标准成功形态（1 样本）');
  assertTrue(arcRepairOk(true, true, 1000), '多样本成功');
});

test('零样本整废 即使mux与轨都在', (): void => {
  assertFalse(arcRepairOk(true, true, 0), 'mux 起了 + 轨建了 + 零样本 ⇒ 失败（只剩 ftyp 头的废片）');
});

test('前两格任一假即失败', (): void => {
  assertFalse(arcRepairOk(false, true, 100), 'muxer 没 start');
  assertFalse(arcRepairOk(true, false, 100), '视频轨没建');
  assertFalse(arcRepairOk(false, false, 0), '全假');
});

test('负样本不算写出', (): void => {
  assertFalse(arcRepairOk(true, true, -1), '负样本数不得当成功（写失败不计数）');
  assertFalse(arcRepairOk(true, true, 0), '零样本');
});

test('keep四组合表 只有ok与stop同时真才保留', (): void => {
  assertTrue(arcRepairKeep(true, true), '样本成功 + stop 正常 ⇒ 保留');
  assertFalse(arcRepairKeep(true, false), 'stop 抛（moov 未写）⇒ 不保留');
  assertFalse(arcRepairKeep(false, true), '样本废 ⇒ 不保留（stop 正常也不救）');
  assertFalse(arcRepairKeep(false, false), '双假 ⇒ 不保留');
  // 与完整链路串起来：零样本 → ok=false → 即便 stop 成功也不保留
  assertFalse(arcRepairKeep(arcRepairOk(true, true, 0), true), '零样本链路不落盘');
});
