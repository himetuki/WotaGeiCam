/**
 * 基于音频的自动同步 — DSP 纯函数层薄壳（对齐上游 e950328：纯 DSP 已抽到 core/AudioAlign，
 * 本文件保留原公开同名 API 并委托，既有经 AudioSync 引用的用例零改动）。
 *
 * 上游把「解码 + DSP + 结果模型」拆成 player/AudioSync（解码与委托）与 core/AudioAlign
 * （纯 DSP 单一真源）；本侧原本就没有解码面（decodeMono 平台面留 T27 不落），所以本文件只剩：
 * - 常量转发（TARGET_SR / MIN_CONFIDENCE 等单一真源在 core/AudioAlign）；
 * - resample / envelope / coarseAlign / refine / fft / inverseFft 同名委托；
 * - RefineResult 转出（既有测试从本文件 import，形状不变）。
 *
 * 偏移符号约定（与对比页一致）：**rightPos = leftPos + offsetMs**，即左视频第 0 毫秒对应
 * 右视频的 offsetMs。
 *
 * 浮点口径（登记，与 core/AudioAlign 一致）：Float32Array 存储按 float32 舍入；中间累加用
 * JS number；Kotlin Long 除法截断 → Math.trunc。
 */
import { AudioAlign, RefineResult } from '../core/AudioAlign';

export { RefineResult };

export class AudioSync {
  /** 目标采样率：与上游 TrackSync「至少 1 秒 PCM」判据同口径的单一真源 */
  static readonly TARGET_SR: number = AudioAlign.TARGET_SR;
  static readonly MAX_SECONDS: number = 30;
  static readonly ENV_WIN_MS: number = AudioAlign.ENV_WIN_MS;
  static readonly SEARCH_MS: number = AudioAlign.DEFAULT_SEARCH_MS;
  static readonly REFINE_MS: number = AudioAlign.DEFAULT_REFINE_MS;
  static readonly PEAK_GUARD_MS: number = AudioAlign.PEAK_GUARD_MS;
  /** 低置信度门槛（主峰/次峰比）：TrackSync 同口径复用 */
  static readonly MIN_CONFIDENCE: number = AudioAlign.MIN_CONFIDENCE;

  /** 线性插值重采样 + 截断到 maxSeconds（纯 DSP 委托 core/AudioAlign） */
  static resample(src: Float32Array, srcRate: number, dstRate: number, maxSeconds: number): Float32Array {
    return AudioAlign.resample(src, srcRate, dstRate, maxSeconds);
  }

  /** 10ms 窗 RMS 包络（默认 8kHz 口径，与原实现一致） */
  static envelope(pcm: Float32Array): Float32Array {
    return AudioAlign.envelope(pcm, AudioSync.TARGET_SR);
  }

  /** 粗偏移（ms）：±SEARCH_MS 内归一化互相关最大（默认搜索窗与原实现一致） */
  static coarseAlign(envL: Float32Array, envR: Float32Array): number {
    return AudioAlign.coarseAlign(envL, envR, AudioSync.SEARCH_MS);
  }

  /** FFT 精修 + 置信度（默认 8kHz / ±10s 搜索 / ±50ms 精修，与原实现一致） */
  static refine(pcmL: Float32Array, pcmR: Float32Array, coarseMs: number): RefineResult {
    return AudioAlign.refine(pcmL, pcmR, coarseMs, AudioSync.TARGET_SR, AudioSync.SEARCH_MS, AudioSync.REFINE_MS);
  }

  static fft(re: Float32Array, im: Float32Array): void {
    AudioAlign.fft(re, im);
  }

  static inverseFft(re: Float32Array, im: Float32Array): void {
    AudioAlign.inverseFft(re, im);
  }
}
