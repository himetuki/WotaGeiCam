/**
 * 起录自检（直译上游 record/RecordHealth.kt 的 GPU 可用子集，对齐上游 38d712a）：
 * 三格 = 编码器建轨（videoTrackAdded）/ muxer 启动（muxStarted）/ 首个视频样本（firstVideoSample）。
 * 判定顺序严格照上游：错误码 → 三格全真 → 视频短窗无音讯 → 完整窗 → WAIT。
 *
 * 不移植（登记）：MR 专用 mrRotateRequested/mrReachedMaxFilesize/rotateSurfaceHandoffNeeded/
 * engineWatchdogTrips 等；streamHealthVerdict/resolveOutputBytes 作为纯函数保留，供后续
 * AVRecorder 路消费（本批未接线）。健康窗口 1.2s/3s、最多自动重录 2 次（含首个共 3 次机会）。
 */

export class RecordHealthWindow {
  videoWindowMs: number;
  fullWindowMs: number;
  maxAttempts: number;

  constructor(videoWindowMs: number, fullWindowMs: number, maxAttempts: number) {
    this.videoWindowMs = videoWindowMs;
    this.fullWindowMs = fullWindowMs;
    this.maxAttempts = maxAttempts;
  }
}

export enum HealthVerdict {
  WAIT = 0,
  HEALTHY = 1,
  UNHEALTHY = 2
}

export enum RetryAction {
  NONE = 0,
  RESTART = 1,
  GIVE_UP = 2
}

/** 视频路期限：编码器建轨正常 <300ms，取 4 倍余量 */
export const VIDEO_WINDOW_MS: number = 1200;
/** 完整期限：含 muxer/首样本 */
export const FULL_WINDOW_MS: number = 3000;
/** 自动重录上限：含首个共 3 次机会 */
export const MAX_RESTART_ATTEMPTS: number = 2;
/** 常规档产出活性门槛：64KiB（容器头 <1KB，顶档码率数十 ms 即写满） */
export const MIN_OUTPUT_BYTES: number = 64 * 1024;

export function defaultRecordHealthWindow(): RecordHealthWindow {
  return new RecordHealthWindow(VIDEO_WINDOW_MS, FULL_WINDOW_MS, MAX_RESTART_ATTEMPTS);
}

/**
 * 三格健康判定（判定顺序即优先级）：
 * 1. errorCode 非空 ⇒ 立刻判废（即便三格已全真）；
 * 2. 三格全真 ⇒ 健康；
 * 3. !videoTrackAdded 且已过视频短窗 ⇒ 判废（视频路没建起来）；
 * 4. 已过完整窗 ⇒ 判废；
 * 5. 否则 WAIT。
 */
export function healthVerdict(firstVideoSample: boolean, muxStarted: boolean, videoTrackAdded: boolean,
  errorCode: string, elapsedInWindowMs: number, window: RecordHealthWindow): HealthVerdict {
  if (errorCode !== '') {
    return HealthVerdict.UNHEALTHY;
  }
  if (videoTrackAdded && muxStarted && firstVideoSample) {
    return HealthVerdict.HEALTHY;
  }
  if (!videoTrackAdded && elapsedInWindowMs >= window.videoWindowMs) {
    return HealthVerdict.UNHEALTHY;
  }
  if (elapsedInWindowMs >= window.fullWindowMs) {
    return HealthVerdict.UNHEALTHY;
  }
  return HealthVerdict.WAIT;
}

/** 判废后动作：只有 UNHEALTHY 才动作，attempts < maxAttempts 重录，否则认输 */
export function retryActionOf(attempts: number, window: RecordHealthWindow,
  verdict: HealthVerdict): RetryAction {
  if (verdict !== HealthVerdict.UNHEALTHY) {
    return RetryAction.NONE;
  }
  return attempts < window.maxAttempts ? RetryAction.RESTART : RetryAction.GIVE_UP;
}

/** 双源字节交叉核对：两源都 -1（不可观测）→ -1；否则取最大可用值 */
export function resolveOutputBytes(fstatBytes: number, providerBytes: number): number {
  if (fstatBytes < 0 && providerBytes < 0) {
    return -1;
  }
  return Math.max(fstatBytes, providerBytes);
}

/**
 * 产出活性判定（AVRecorder/stream 路；本批保留纯函数，未接线）：
 * 错误码 → 不可观测(-1) WAIT → 字节足量 HEALTHY → 完整窗不足量 UNHEALTHY → WAIT。
 */
export function streamHealthVerdict(bytesWritten: number, errorCode: string,
  elapsedMs: number, window: RecordHealthWindow): HealthVerdict {
  if (errorCode !== '') {
    return HealthVerdict.UNHEALTHY;
  }
  if (bytesWritten < 0) {
    return HealthVerdict.WAIT;
  }
  if (bytesWritten >= MIN_OUTPUT_BYTES) {
    return HealthVerdict.HEALTHY;
  }
  if (elapsedMs >= window.fullWindowMs) {
    return HealthVerdict.UNHEALTHY;
  }
  return HealthVerdict.WAIT;
}
