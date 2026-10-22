/**
 * 离线修复会话模型（record/ArcRepairSessionModel.ets）单测——对拍上游
 * record/ArcRepairRunner.kt 的常量区/判定区（对齐上游 38d712a）。
 *
 * 24 条：parseProbe 3（正常/err/坏字段数）、速率 4（样本不足回退/30/25/29.97）、NO_NEED 3、
 * 去重门限 3、帧数估算 3、状态解析 4、结果归因 3、note 字面量 1。期望值一律手写字面量。
 * 零平台依赖（不 import @ohos/@kit）。
 */
import { ArcRateProbe } from '../../entry/src/main/ets/record/ArcKeepRule';
import {
  ArcProbeInfo, ArcRepairError, ArcRepairOutcome, ArcRepairSessionModel, ArcRepairStatus
} from '../../entry/src/main/ets/record/ArcRepairSessionModel';

test('parseProbe 正常串十字段', (): void => {
  const info: ArcProbeInfo =
    ArcRepairSessionModel.parseProbe('ok:1920:1080:90:30:5000:120:33333:2:video/avc');
  assertTrue(info.ok, '解析成功');
  assertEq(1920, info.width, '宽');
  assertEq(1080, info.height, '高');
  assertEq(90, info.rotation, '旋转');
  assertEq(30, info.containerFps, '容器帧率');
  assertEq(5000, info.durationMs, '时长');
  assertEq(120, info.cleanCount, '干净样本数');
  assertEq(33333, info.medianUs, '中位帧距');
  assertEq(2, info.audioCount, '音轨数');
  assertEq('video/avc', info.mime, 'MIME');
  assertEq('', info.err, '无错误');
});

test('parseProbe err串', (): void => {
  const info: ArcProbeInfo = ArcRepairSessionModel.parseProbe('err:ARC_NO_VIDEO');
  assertFalse(info.ok, '失败');
  assertEq('ARC_NO_VIDEO', info.err, '错误码透传');
  assertEq(0, info.width, '失败时字段归零');
});

test('parseProbe 坏字段数归ARC_PROBE', (): void => {
  // 少字段 / 多字段 / 空串 / 前缀不认识 都不许猜
  assertEq(ArcRepairError.PROBE, ArcRepairSessionModel.parseProbe('ok:1920:1080:0:30').err, '少字段');
  assertEq(ArcRepairError.PROBE,
    ArcRepairSessionModel.parseProbe('ok:1:2:3:4:5:6:7:8:9:extra').err, '多字段');
  assertEq(ArcRepairError.PROBE, ArcRepairSessionModel.parseProbe('').err, '空串');
  assertEq(ArcRepairError.PROBE, ArcRepairSessionModel.parseProbe('weird').err, '未知前缀');
  assertEq(ArcRepairError.PROBE, ArcRepairSessionModel.parseProbe('err:a:b').err, 'err 多字段');
});

test('速率 样本不足回退容器帧率', (): void => {
  const rate = ArcRepairSessionModel.measuredRateFromProbe(7, 40000, 25);
  assertEq(25, rate.num, '回退容器 25 的分子');
  assertEq(1, rate.den, '整数口径分母 1');
  const rate2 = ArcRepairSessionModel.measuredRateFromProbe(0, 0, 30);
  assertEq(30, rate2.num, '零样本回退容器 30');
  // 容器帧率也缺失时回 30（防御，不产出 0 分母）
  const rate3 = ArcRepairSessionModel.measuredRateFromProbe(0, 0, 0);
  assertEq(30, rate3.num, '容器 0 兜底 30');
});

test('速率 33333us吸附30fps', (): void => {
  const rate = ArcRepairSessionModel.measuredRateFromProbe(100, 33333, 30);
  assertEq(30, rate.num, '30fps 分子');
  assertEq(1, rate.den, '30fps 分母');
});

test('速率 40000us吸附25fps', (): void => {
  const rate = ArcRepairSessionModel.measuredRateFromProbe(8, 40000, 30);
  assertEq(25, rate.num, '25fps 分子');
  assertEq(1, rate.den, '25fps 分母');
});

test('速率 33367us吸附29.97fps有理数', (): void => {
  // NTSC 30/1.001 ≈ 29.97：周期 33367µs 落在 30000/1001 档 ±2µs 窗内
  const rate = ArcRepairSessionModel.measuredRateFromProbe(50, 33367, 30);
  assertEq(30000, rate.num, 'NTSC 分子');
  assertEq(1001, rate.den, 'NTSC 分母');
});

test('NO_NEED 源不快于目标', (): void => {
  // 30fps 源对 30 目标：无帧可抽
  assertTrue(ArcRepairSessionModel.decideNoNeed(ArcRateProbe.measuredOf(30, 1), 30), '30→30 NO_NEED');
  assertTrue(ArcRepairSessionModel.decideNoNeed(ArcRateProbe.measuredOf(24, 1), 25), '24→25 NO_NEED');
});

test('NO_NEED 源快于目标需修', (): void => {
  assertFalse(ArcRepairSessionModel.decideNoNeed(ArcRateProbe.measuredOf(30, 1), 24), '30→24 需修');
  assertFalse(ArcRepairSessionModel.decideNoNeed(ArcRateProbe.measuredOf(30000, 1001), 25), '29.97→25 需修');
});

test('NO_NEED 25点4不误判', (): void => {
  // 25.4 对 25：整数视图 25 ≤ 25 会误判 NO_NEED；有理数比较必须判"需修"
  assertFalse(ArcRepairSessionModel.decideNoNeed(ArcRateProbe.measuredOf(254, 10), 25), '25.4→25 需修');
  // dstFps 非法（0）时不报 NO_NEED（交给上层 ARC_SOURCE）
  assertFalse(ArcRepairSessionModel.decideNoNeed(ArcRateProbe.measuredOf(30, 1), 0), 'dst=0 不算 NO_NEED');
});

test('去重门限 半个源帧周期', (): void => {
  // 30fps：500000·1/30 = 16666（整数除）
  assertEq(16666, ArcRepairSessionModel.dedupeMinGapUs(ArcRateProbe.measuredOf(30, 1)), '30fps 门限');
  // 25fps：20000
  assertEq(20000, ArcRepairSessionModel.dedupeMinGapUs(ArcRateProbe.measuredOf(25, 1)), '25fps 门限');
});

test('去重门限 NTSC与非法源', (): void => {
  // 29.97：500000·1001/30000 = 16683.33 → 16683（整数除）
  assertEq(16683, ArcRepairSessionModel.dedupeMinGapUs(ArcRateProbe.measuredOf(30000, 1001)), 'NTSC 门限');
  // 非法（0 分子）→ 0（不去重，但也绝不除零）
  assertEq(0, ArcRepairSessionModel.dedupeMinGapUs(ArcRateProbe.measuredOf(0, 1)), '非法源归 0');
});

test('去重门限 24与60档字面量', (): void => {
  assertEq(20833, ArcRepairSessionModel.dedupeMinGapUs(ArcRateProbe.measuredOf(24, 1)), '24fps 门限');
  assertEq(8333, ArcRepairSessionModel.dedupeMinGapUs(ArcRateProbe.measuredOf(60, 1)), '60fps 门限');
});

test('帧数估算 30fps五秒', (): void => {
  // 5000ms × 30 = 150000ms·fps → (150000+0.5)/1000 = 150 帧
  assertEq(150, ArcRepairSessionModel.estimateSrcFrames(5000, ArcRateProbe.measuredOf(30, 1)), '5s@30');
});

test('帧数估算 四舍五入到帧', (): void => {
  // 29.97 × 1000ms = 29.97 帧 → 30（半帧进位）
  assertEq(30, ArcRepairSessionModel.estimateSrcFrames(1000, ArcRateProbe.measuredOf(30000, 1001)), '1s@29.97');
  // 24 × 250ms = 6 帧整
  assertEq(6, ArcRepairSessionModel.estimateSrcFrames(250, ArcRateProbe.measuredOf(24, 1)), '0.25s@24');
});

test('帧数估算 时长或速率缺失归零', (): void => {
  assertEq(0, ArcRepairSessionModel.estimateSrcFrames(0, ArcRateProbe.measuredOf(30, 1)), '时长为 0');
  assertEq(0, ArcRepairSessionModel.estimateSrcFrames(-5, ArcRateProbe.measuredOf(30, 1)), '时长负');
  assertEq(0, ArcRepairSessionModel.estimateSrcFrames(5000, ArcRateProbe.measuredOf(0, 1)), '速率非法');
});

test('状态解析 idle与running', (): void => {
  const idle: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('idle');
  assertEq('idle', idle.kind, 'idle');
  const running: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('running:42');
  assertEq('running', running.kind, 'running');
  assertEq(42, running.progress, '进度 42');
  const clamped: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('running:150');
  assertEq(100, clamped.progress, '进度钳 100');
});

test('状态解析 done六字段', (): void => {
  const done: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('done:120:0:1:1:1');
  assertEq('done', done.kind, 'done');
  assertEq(120, done.videoSamples, '视频样本');
  assertEq(0, done.audioSamples, '音轨样本（本批不产）');
  assertTrue(done.muxStarted, 'mux 已启动');
  assertTrue(done.trackAdded, '轨已建');
  assertTrue(done.muxStopOk, 'stop 成功');
});

test('状态解析 failed与cancelled', (): void => {
  const failed: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('failed:ARC_DECODER');
  assertEq('failed', failed.kind, 'failed');
  assertEq('ARC_DECODER', failed.error, '错误码透传');
  const cancelled: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('cancelled');
  assertEq('cancelled', cancelled.kind, 'cancelled');
  assertEq(ArcRepairError.CANCELLED, cancelled.error, '取消码');
});

test('状态解析 坏串与坏done', (): void => {
  assertEq('idle', ArcRepairSessionModel.parseRepairStatus('').kind, '空串按 idle（native 首启未跑）');
  const badDone: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('done:1:2');
  assertEq('failed', badDone.kind, 'done 字段不足归 failed');
  assertEq(ArcRepairError.PROBE, badDone.error, '归 ARC_PROBE');
  const unknown: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('what');
  assertEq('failed', unknown.kind, '未知状态归 failed');
});

test('结果归因 三格齐全且stop成功才保留', (): void => {
  const ok: ArcRepairOutcome =
    ArcRepairSessionModel.resolveRepairOutcome(ArcRepairSessionModel.parseRepairStatus('done:36:0:1:1:1'));
  assertTrue(ok.ok, 'ok');
  assertTrue(ok.keep, 'keep');
  assertEq('', ok.error, '无错误');
});

test('结果归因 stop失败归ENGINE:MUX_CLOSE', (): void => {
  const bad: ArcRepairOutcome =
    ArcRepairSessionModel.resolveRepairOutcome(ArcRepairSessionModel.parseRepairStatus('done:36:0:1:1:0'));
  assertFalse(bad.ok, 'ok=false');
  assertFalse(bad.keep, '不保留（坏容器）');
  assertEq('ARC_ENGINE:MUX_CLOSE', bad.error, 'moov 未写归因');
  // 零样本但建轨成功：归 ARC_NO_VIDEO（上游同口径，不是 ARC_ENGINE）
  const zero: ArcRepairOutcome =
    ArcRepairSessionModel.resolveRepairOutcome(ArcRepairSessionModel.parseRepairStatus('done:0:0:1:1:1'));
  assertFalse(zero.keep, '零样本不保留');
  assertEq('ARC_NO_VIDEO', zero.error, '零样本归因 ARC_NO_VIDEO（钉码）');
});

test('结果归因 无轨与失败透传', (): void => {
  const noTrack: ArcRepairOutcome =
    ArcRepairSessionModel.resolveRepairOutcome(ArcRepairSessionModel.parseRepairStatus('done:0:0:0:0:1'));
  assertEq(ArcRepairError.NO_VIDEO, noTrack.error, '无轨归 ARC_NO_VIDEO');
  const failed: ArcRepairOutcome =
    ArcRepairSessionModel.resolveRepairOutcome(ArcRepairSessionModel.parseRepairStatus('failed:ARC_TIMEOUT'));
  assertEq('ARC_TIMEOUT', failed.error, '失败码透传');
  assertFalse(failed.keep, '失败不保留');
});

test('note字面量 用户口径说明', (): void => {
  assertEq('源实测≈30fps（真值 30/1，容器标 30）→ 目标 24fps，' +
    '被抽帧画面已按整像素选择（差分死区 D=12）并入前后帧；不改原片',
  ArcRepairSessionModel.repairNote(30, 30, 1, 30, 24, 12), 'note 逐字');
});

test('音轨硬闸码字面量 与既有错误码互异', (): void => {
  // T29 临时硬闸：源含音轨时拒绝执行（T30 补音频后移除）；码值必须自成一类
  assertEq('ARC_NO_AUDIO_LANE', ArcRepairError.NO_AUDIO_LANE, '硬闸码');
  const codes: Array<string> = [ArcRepairError.SOURCE, ArcRepairError.DECODER,
    ArcRepairError.ENCODER, ArcRepairError.MUXER, ArcRepairError.CPU_YUV,
    ArcRepairError.CPU_FEED, ArcRepairError.NO_VIDEO, ArcRepairError.CANCELLED,
    ArcRepairError.TIMEOUT, ArcRepairError.ENGINE, ArcRepairError.NO_NEED,
    ArcRepairError.PROBE];
  for (const c of codes) {
    assertTrue(c !== ArcRepairError.NO_AUDIO_LANE, `硬闸码不得与 ${c} 重名`);
  }
});

test('取消状态串 解析为 cancelled 终态', (): void => {
  // native 取消收尾改为 cancelled 终态（不再 failed:ARC_CANCELLED），契约两处一致
  const cancelled: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('cancelled');
  assertEq('cancelled', cancelled.kind, '终态 kind');
  const outcome: ArcRepairOutcome = ArcRepairSessionModel.resolveRepairOutcome(cancelled);
  assertFalse(outcome.ok, '取消不算成功');
  assertEq(ArcRepairError.CANCELLED, outcome.error, '归因取消码');
});
