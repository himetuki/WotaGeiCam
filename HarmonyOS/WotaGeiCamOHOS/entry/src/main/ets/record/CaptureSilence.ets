/**
 * 内录静音判读与按场提示节流（直译上游 record/CodecRecorder.kt 的静音常量/纯函数 +
 * record/PlaybackCaptureController.kt 的 SilencePromptGate，对齐上游 38d712a）。
 *
 * 背景：部分固件的投影音频捕获会间歇性给出纯数字静音流（真机实证内录轨 7.76s、371 包、
 * 全程恒定零样本，环境轨同场正常）；内录轨「在却无声」用户只有录完播放才发现。
 * 三条硬约束：录制中就要提示；视频保持正常录制；走既有 tip 通道。
 *
 * 判据口径：采集侧只维持「本场是否见过非零样本」的字节级锁存（上游 AudioFeeder.sawNonZero），
 * 本文件只落纯判据与按场节流——见过非零后两个触发点都判 false（本场永久不再提示）。
 *
 * 纯逻辑（不 import 平台 Kit）；平台接线（采集/巡检/提示通道）留 T27。
 */

/** 同场静音提示的重弹节流窗：≥60s 才允许再弹（防漏看、防刷屏） */
export const SILENCE_REPROMPT_MS: number = 60_000;
/** 收尾兜底门槛：本段内录有效样本超过 2s 才判读（启动窗口内的短暂静音不告警） */
export const CAPTURE_SILENCE_MIN_MS: number = 2_000;
/** 录制中巡检门槛：≥5s 仍未见非零才判读（长于内录样本等待窗 1.5s 与起录自检长窗 3s） */
export const CAPTURE_SILENCE_LIVE_MIN_MS: number = 5_000;

/**
 * 内录静音提示的**按场**节流锁存（纯状态）：
 * - 每场首次恒放行（resetTake 后回到「本场未提示」——连录两场都静音要能各提示一次）；
 * - 同场距上次不足 SILENCE_REPROMPT_MS 一律拦下（防刷屏），达到即再放行（防漏看）。
 *
 * 「见过非零后本场永久停」不在这一层：由判据侧（sawNonZero 进 captureSilenceVerdict）保证。
 */
export class SilencePromptGate {
  /** 本场是否已提示过（独立于时刻：nowMs 传 0 也要语义正确） */
  private fired: boolean = false;
  /** 上次放行时刻（调用方时钟，elapsedRealtime 域等价物） */
  private lastFireMs: number = 0;

  /** 本拍是否允许提示：本场首触恒放行；距上次不足 SILENCE_REPROMPT_MS 拦下，≥ 再放行 */
  tryFire(nowMs: number): boolean {
    if (this.fired && nowMs - this.lastFireMs < SILENCE_REPROMPT_MS) {
      return false;
    }
    this.fired = true;
    this.lastFireMs = nowMs;
    return true;
  }

  /** 复位（每场录制开始）：下一次 tryFire 重新按「本场首触」放行 */
  resetTake(): void {
    this.fired = false;
    this.lastFireMs = 0;
  }
}

/**
 * 内录有效时长（ms）= 实喂编码器的 PCM 字节 ÷（采样率 × 声道数 × 2 字节）。
 * 录制中巡检与段收尾兜底共用同一换算（两处各写一份必漂移）；EOS 哨兵/喂码失败/段界丢弃都不计。
 */
export function fedMsOf(fedBytes: number, sampleRate: number, channels: number): number {
  if (fedBytes <= 0 || sampleRate <= 0 || channels <= 0) {
    return 0;
  }
  return Math.floor(fedBytes * 1000 / (sampleRate * channels * 2));
}

/**
 * 内录纯静音判据（纯函数）：轨有真实样本产出、有效时长过 minMs、且**本场仍未见任一非零样本**
 * 三者同时成立才告警。见过一次非零 ⇒ 本场永久判 false（两个触发点都拿不到 true）。
 * minMs 缺省 = 收尾兜底门槛；录制中巡检显式传 CAPTURE_SILENCE_LIVE_MIN_MS。
 */
export function captureSilenceVerdict(
  hasSamples: boolean,
  durationMs: number,
  sawNonZero: boolean,
  minMs: number = CAPTURE_SILENCE_MIN_MS
): boolean {
  return hasSamples && durationMs >= minMs && !sawNonZero;
}
