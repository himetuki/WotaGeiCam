/**
 * 分段轮转纯策略（record/SegmentPolicy.ets）单测——上游无独立 SegmentPolicy 测试
 * （上游 VideoStore 口径），本文件按常量/阈值边界/前缀和语义手写期望值钉行为
 * （2026-10-22 撤销「无自动化单测登记」，见 docs/PORT_MAP.md §record）。
 * 走 @kit.CoreFileKit stub 链（经 record/ArcDropLog.ets 顶层 import，惰性 Proxy 不触达）。
 */
import { SegmentPolicy } from '../../entry/src/main/ets/record/SegmentPolicy';

test('常量 3.5GiB 上限与 0.98 安全系数', (): void => {
  assertEq(3758096384, SegmentPolicy.MAX_FILE_BYTES, 'MAX_FILE_BYTES=3.5GiB');
  assertEq(0.98, SegmentPolicy.ROTATE_SAFETY, 'ROTATE_SAFETY=0.98');
});

test('阈值 floor 后取整且轮转边界取 >=', (): void => {
  const limit: number = SegmentPolicy.segmentLimitBytes();
  assertEq(3682934456, limit, 'floor(3.5GiB×0.98)');
  assertEq(980, SegmentPolicy.segmentLimitBytes(1000), '自定义档同口径 floor');
  assertTrue(SegmentPolicy.shouldRotate(limit, limit), '恰好阈值即轮转（>=）');
  assertTrue(SegmentPolicy.shouldRotate(limit + 1, limit), '超阈值轮转');
  assertFalse(SegmentPolicy.shouldRotate(limit - 1, limit), '差一字节不轮转');
  assertFalse(SegmentPolicy.shouldRotate(999999, 0), 'limit<=0 不轮转（未配置防误触）');
  assertFalse(SegmentPolicy.shouldRotate(0, -1), '负阈值不轮转');
});

test('buildDropSegments first 为前缀和 lostMs 逐段对齐', (): void => {
  const segments = SegmentPolicy.buildDropSegments([5, 3, 0], [0, 120, 80]);
  assertEq(3, segments.length, '段数=counts 数');
  assertEq(0, segments[0].segment, '段序号 0');
  assertEq(0, segments[0].first, '首段 first=0');
  assertEq(5, segments[0].count, '首段 count');
  assertEq(0, segments[0].lostMs, '首段 lostMs');
  assertEq(1, segments[1].segment, '段序号 1');
  assertEq(5, segments[1].first, '第二段 first=前缀和');
  assertEq(120, segments[1].lostMs, '第二段 lostMs');
  assertEq(2, segments[2].segment, '段序号 2');
  assertEq(8, segments[2].first, '第三段 first=累计前缀和');
  assertEq(0, segments[2].count, '零样本段也留 count=0 条目');
  // lostMs 短于 counts 时缺位补 0（防御）
  const short = SegmentPolicy.buildDropSegments([2, 2], [100]);
  assertEq(0, short[1].lostMs, 'lostMs 缺位补 0');
  assertEq(0, SegmentPolicy.buildDropSegments([], []).length, '空会话空清单');
});

test('rotateFramesUpper 换段丢帧上界取 ceil', (): void => {
  assertEq(0, SegmentPolicy.rotateFramesUpper(0, 30), '无停顿无丢帧');
  assertEq(0, SegmentPolicy.rotateFramesUpper(-5, 30), '负停顿按 0');
  assertEq(4, SegmentPolicy.rotateFramesUpper(120, 30), 'ceil(120×30/1000)=4');
  assertEq(3, SegmentPolicy.rotateFramesUpper(100, 30), '恰整 3');
  assertEq(1, SegmentPolicy.rotateFramesUpper(50, 12), 'ceil(50×12/1000)=1');
  assertEq(1, SegmentPolicy.rotateFramesUpper(50, 0), 'fps 非法按 1 兜底 ceil(50/1000)=1');
});

test('segmentSummary 段计数摘要', (): void => {
  assertEq('0:5,1:3', SegmentPolicy.segmentSummary([5, 3]), '两段');
  assertEq('0:0', SegmentPolicy.segmentSummary([0]), '零样本段如实上报');
  assertEq('', SegmentPolicy.segmentSummary([]), '空会话');
});
