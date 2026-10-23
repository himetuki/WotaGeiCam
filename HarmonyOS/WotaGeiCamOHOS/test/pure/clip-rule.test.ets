/**
 * A/B 剪辑纯规则（player/ClipRule.ets）单测——对拍上游 app/src/test/java/com/wotagei/cam/
 * player/ClipExportTest.kt 的 ClipRule 断言面并按本侧裁剪（上游平台段不进本文件）。
 * 平台差异照登记：clipNameOf 追加 `_yyyyMMdd_HHmmss` 时间戳（OHOS 私有目录防覆盖）；
 * remapDrops 清空 segments（剪辑成片是单文件，源段账不再适用）。
 * 走 @kit.CoreFileKit stub 链（经 record/ArcDropLog.ets 顶层 import，惰性 Proxy 不触达）。
 */
import { ArcDropLog, ArcDropSegment, DropMark } from '../../entry/src/main/ets/record/ArcDropLog';
import {
  MIN_KEEP_MS, TrackChoice, audioOrdinalOf, audioShiftUs, clipNameOf, keyFrameIndexOf,
  keepSample, normalize, remapDrops
} from '../../entry/src/main/ets/player/ClipRule';

test('MIN_KEEP_MS 恰等通过 差一毫秒拒绝', (): void => {
  assertEq(300, MIN_KEEP_MS, '常量 300ms');
  const ok = normalize(0, 300, 1000);
  if (ok === undefined) {
    assertTrue(false, '恰等 300ms 应通过');
    return;
  }
  assertEq(0, ok.startMs, '起点');
  assertEq(300, ok.endMs, '终点');
  assertTrue(normalize(0, 299, 1000) === undefined, '差 1ms 视为误操作');
  assertTrue(normalize(500, 500, 1000) === undefined, '零长拒绝');
});

test('normalize 夹紧到 0 到 duration 且拒绝非有限数', (): void => {
  const clamped = normalize(-100, 5000, 1000);
  if (clamped === undefined) {
    assertTrue(false, '夹紧后应合法');
    return;
  }
  assertEq(0, clamped.startMs, '负起点夹 0');
  assertEq(1000, clamped.endMs, '超片长终点夹 duration');
  const swap = normalize(800, 100, 1000);
  assertTrue(swap === undefined, '起终点颠倒拒绝（终点必须晚于起点，夹紧不换序）');
  const narrow = normalize(700, 5000, 1000);
  if (narrow === undefined) {
    assertTrue(false, '夹到 700..1000 恰 300ms 应通过');
    return;
  }
  assertEq(700, narrow.startMs, '夹紧后起点');
  assertEq(1000, narrow.endMs, '夹紧后终点');
  assertTrue(normalize(Number.NaN, 500, 1000) === undefined, 'NaN 拒绝');
  assertTrue(normalize(0, 500, Number.POSITIVE_INFINITY) === undefined, '非有限片长拒绝');
  assertTrue(normalize(0, 500, 0) === undefined, '零片长必不足保留段');
  assertTrue(keepSample(0), 'relUs=0 保留');
  assertFalse(keepSample(-1), '负 PTS 丢弃（开放 GOP 前导 B 帧）');
  assertTrue(keepSample(41666), '正 PTS 保留');
});

test('clipNameOf 格式 edit_源名_时间戳', (): void => {
  // 本地时区自洽：以本地字段构造 stamp，格式化读回同字段（不跨时区对拍）
  const stamp: number = new Date(2026, 9, 21, 13, 5, 9).getTime();
  assertEq('edit_VID_20261021_130509_001_20261021_130509.mp4',
    clipNameOf('VID_20261021_130509_001.mp4', stamp), '常规扩展名剥除');
  assertEq('edit_noext_20261021_130509.mp4',
    clipNameOf('noext', stamp), '无扩展名整名当 stem');
  assertEq('edit_.hidden_20261021_130509.mp4',
    clipNameOf('.hidden', stamp), '首字符点不算扩展名分隔');
});

test('keyFrameIndexOf 四舍五入与防御', (): void => {
  assertEq(0, keyFrameIndexOf(0, 24), '0 位置');
  assertEq(24, keyFrameIndexOf(1000000, 24), '(1e6×24+5e5)/1e6=24.5 → 24');
  assertEq(25, keyFrameIndexOf(1021000, 24), '(1.021e6×24+5e5)/1e6=25.004 → 25');
  assertEq(0, keyFrameIndexOf(1000000, 0), 'dstFps 非正防御 0');
  assertEq(0, keyFrameIndexOf(Number.NaN, 24), '非有限 PTS 防御 0');
  assertEq(0, keyFrameIndexOf(-500000, 24), '负 PTS 夹 0');
});

test('remapDrops 平移位次且清空 segments', (): void => {
  const log: ArcDropLog = new ArcDropLog('mend', 24,
    [new DropMark(1, 1), new DropMark(6, 1), new DropMark(8, 2)],
    [new ArcDropSegment(0, 0, 72), new ArcDropSegment(1, 72, 30, 120)]);
  const out: ArcDropLog = remapDrops(log, 4);
  assertEq('mend', out.mode, 'mode 透传');
  assertEq(24, out.dstFps, 'dstFps 透传');
  assertEq(2, out.drops.length, 'out>=shift 的记录保留');
  assertEq(2, out.drops[0].out, '6-4=2');
  assertEq(1, out.drops[0].n, '增量 n 原样保留');
  assertEq(4, out.drops[1].out, '8-4=4');
  assertEq(2, out.drops[1].n, 'n=2');
  assertEq(0, out.segments.length, 'segments 清空（单文件段账失效，登记）');
  // shift=0 全保留；非法 shift 按 0
  const zero: ArcDropLog = remapDrops(log, 0);
  assertEq(3, zero.drops.length, 'shift=0 全保留');
  const bad: ArcDropLog = remapDrops(log, Number.NaN);
  assertEq(3, bad.drops.length, '非有限 shift 按 0');
});

test('TrackChoice 序数与 audioOrdinalOf 映射', (): void => {
  assertEq(-1, TrackChoice.NO_AUDIO, 'NO_AUDIO=-1');
  assertEq(0, TrackChoice.ENV, 'ENV=0');
  assertEq(1, TrackChoice.CAP, 'CAP=1');
  assertEq(-1, audioOrdinalOf(TrackChoice.NO_AUDIO), '不导音频');
  assertEq(0, audioOrdinalOf(TrackChoice.ENV), '第一条音轨');
  assertEq(1, audioOrdinalOf(TrackChoice.CAP), '第二条音轨');
});

test('内录轨按负偏移平移_环境轨恒零', (): void => {
  // 对拍上游 ClipExportShiftTest（e950328）`内录轨按负偏移平移_环境轨恒零` 全表
  assertEq(-120000, audioShiftUs(TrackChoice.CAP, 120), 'CAP +120ms → -120000µs（向前拉）');
  assertEq(120000, audioShiftUs(TrackChoice.CAP, -120), 'CAP -120ms → +120000µs（延后）');
  assertEq(0, audioShiftUs(TrackChoice.CAP, 0), 'CAP 零偏移不平移');
  assertEq(0, audioShiftUs(TrackChoice.ENV, 120), 'ENV 恒不平移');
  assertEq(0, audioShiftUs(TrackChoice.ENV, -999), 'ENV 负偏移也不平移');
  assertEq(0, audioShiftUs(TrackChoice.NO_AUDIO, 120), '无音轨无可平移');
});
