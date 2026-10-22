/**
 * 内录音源契约（对齐鸿蒙 OH_AudioCaptureSourceType，非上游直译——见差异登记）。
 *
 * 差异登记（2026-10-22，T26）：上游 record/PlaybackCaptureController.kt 用 Android
 * `AudioPlaybackCaptureConfiguration.Builder.addMatchingUsage` 的 **usage 白名单**
 * （USAGE_MEDIA/USAGE_GAME/USAGE_UNKNOWN 等）筛可捕获应用；鸿蒙 AVScreenCapture /
 * OH_AudioCapturer 的公开能力是 **capture source 枚举**（MIC / ALL_PLAYBACK / APP_PLAYBACK），
 * 没有等价的 usage 白名单接口。本侧因此改为「源枚举 + 内录源优先列表」：
 * 先试 ALL_PLAYBACK（系统混音/全部播放），不可用再退 APP_PLAYBACK（仅本应用播放），
 * 环境音恒走 MIC。平台能力差（隐私授权、视频轨必选、单轨限制）见 PORT_MAP「T26 平台调研」。
 *
 * 纯逻辑（不 import 平台 Kit）；实际建路留 T27 真机批。
 */

/** 音源枚举（数值逐值对齐 OH_AudioCaptureSourceType） */
export enum CaptureAudioSource {
  /** 麦克风（环境音） */
  MIC = 1,
  /** 全部播放（系统混音内录） */
  ALL_PLAYBACK = 2,
  /** 仅本应用播放（内录） */
  APP_PLAYBACK = 3
}

/** 环境音源恒为麦克风（与上游 pickSource 的 MIC 回退口径一致；CAMCORDER 无 OHOS 对应） */
export function envAudioSource(): CaptureAudioSource {
  return CaptureAudioSource.MIC;
}

/** 内录源优先级：先全量播放、再仅本应用（平台探测按序降级，T27） */
export function captureAudioSources(): Array<CaptureAudioSource> {
  return [CaptureAudioSource.ALL_PLAYBACK, CaptureAudioSource.APP_PLAYBACK];
}

/** 源短名（日志/UI；非空且互异） */
export function captureSourceLabel(source: CaptureAudioSource): string {
  if (source === CaptureAudioSource.MIC) {
    return '麦克风';
  }
  if (source === CaptureAudioSource.ALL_PLAYBACK) {
    return '全部播放';
  }
  return '应用播放';
}
