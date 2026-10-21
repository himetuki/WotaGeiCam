/**
 * 一次录制的编码参数快照——直译自上游 record/RecordProfile.kt 的纯字段子集（对齐上游 38d712a）。
 * 字段名与顺序照上游契约；本批只带 AVRecorder 面输入需要的字段。
 *
 * 平台差异（登记）：
 * - videoMime（MediaFormat.MIMETYPE_*）不移植：OHOS 用 media.CodecMimeType 枚举，映射在 KitRecorder；
 * - audioUsable 不移植：麦克风权限拒绝时上层把 audioEnabled 置 false，KitRecorder 直接按
 *   audioAllowed 组配置（不复刻数值合法性判断）；
 * - captureAudio（内录双轨）/arcConvert/fpsNtsc 随音频双轨与抽帧批（留白）。
 */
import { BitratePolicy } from './BitratePolicy';

/** 参数表 codec 取值（与上游 MediaRecorder.VideoEncoder 契约一致） */
export const CODEC_H264: number = 0;
export const CODEC_HEVC: number = 1;

/** 录制时长短于此值按废片处理：删文件（04 文件 §5.4） */
export const MIN_KEEP_MS: number = 1_000;

export interface RecordProfileArgs {
  width: number;
  height: number;
  fps: number;
  captureRate: number;
  bitrate: number;
  codec: number;
  sampleRate: number;
  channels: number;
  audioBitrate: number;
  audioEnabled: boolean;
  orientationHint: number;
  mirrored: boolean;
  useGpu: boolean;
}

export class RecordProfile {
  width: number;
  height: number;
  fps: number;
  captureRate: number;
  bitrate: number;
  codec: number;
  sampleRate: number;
  channels: number;
  audioBitrate: number;
  audioEnabled: boolean;
  orientationHint: number;
  mirrored: boolean;
  useGpu: boolean;

  constructor(p: RecordProfileArgs) {
    this.width = p.width;
    this.height = p.height;
    this.fps = p.fps;
    this.captureRate = p.captureRate;
    this.bitrate = p.bitrate;
    this.codec = p.codec;
    this.sampleRate = p.sampleRate;
    this.channels = p.channels;
    this.audioBitrate = p.audioBitrate;
    this.audioEnabled = p.audioEnabled;
    this.orientationHint = p.orientationHint;
    this.mirrored = p.mirrored;
    this.useGpu = p.useGpu;
  }

  get hevc(): boolean {
    return this.codec === CODEC_HEVC;
  }

  /** 延时摄影：录得快、放得慢（captureRate = 实际采集帧率，fps = 容器声明帧率） */
  get timeLapse(): boolean {
    return this.captureRate > 0 && Math.trunc(this.captureRate) !== this.fps;
  }

  /** 三段式落地后的编码器码率（BitratePolicy.resolve 复用，不重写） */
  effectiveBitrate(): number {
    return BitratePolicy.resolve(this.bitrate, this.width, this.height, this.fps, this.hevc);
  }
}
