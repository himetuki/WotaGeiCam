/**
 * 内录音源契约（record/CaptureSources.ets）单测——对齐鸿蒙 OH_AudioCaptureSourceType 数值契约。
 * 上游无同名纯函数（Android usage 白名单无 OHOS 等价物），期望值按 OHOS 枚举契约手写字面量。
 */
import {
  CaptureAudioSource, captureAudioSources, captureSourceLabel, envAudioSource
} from '../../entry/src/main/ets/record/CaptureSources';

test('枚举数值逐值对齐 OH_AudioCaptureSourceType', (): void => {
  assertEq(1, CaptureAudioSource.MIC, 'MIC=1');
  assertEq(2, CaptureAudioSource.ALL_PLAYBACK, 'ALL_PLAYBACK=2');
  assertEq(3, CaptureAudioSource.APP_PLAYBACK, 'APP_PLAYBACK=3');
});

test('环境源=MIC 内录优先级=[2,3]', (): void => {
  assertEq(CaptureAudioSource.MIC, envAudioSource(), '环境音恒麦克风');
  const sources: Array<CaptureAudioSource> = captureAudioSources();
  assertEq(2, sources.length, '内录源两条');
  assertEq(2, sources[0], '先 ALL_PLAYBACK');
  assertEq(3, sources[1], '再 APP_PLAYBACK');
});

test('源短名非空且互异', (): void => {
  const mic: string = captureSourceLabel(CaptureAudioSource.MIC);
  const all: string = captureSourceLabel(CaptureAudioSource.ALL_PLAYBACK);
  const app: string = captureSourceLabel(CaptureAudioSource.APP_PLAYBACK);
  assertEq('麦克风', mic, 'MIC 短名');
  assertEq('全部播放', all, 'ALL_PLAYBACK 短名');
  assertEq('应用播放', app, 'APP_PLAYBACK 短名');
  assertTrue(mic.length > 0 && all.length > 0 && app.length > 0, '短名非空');
  assertTrue(mic !== all && all !== app && mic !== app, '短名互异');
});
