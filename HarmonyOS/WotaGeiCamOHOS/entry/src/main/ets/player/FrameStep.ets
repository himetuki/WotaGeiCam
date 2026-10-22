/**
 * 帧步进的纯帧号算术（逐行直译上游 player/FrameStep.kt，对齐上游 38d712a）。
 *
 * 锁定的性质：连点必须单调前进、来回点不漂。
 * 1) 浮点帧长（29.97fps → 33.3667ms）下，帧首四舍五入成整毫秒再 floor 反查会退回同一帧；
 *    这里先按 1.5ms 容差把位置吸到最近帧首再定位。
 * 2) seek 未落地期间 currentTime 仍是旧值，连点会互相吞掉——调用方把上一次已发出的目标
 *    作为 pendingMs 传进来当锚点。
 */
export const SNAP_TOL_MS: number = 1.5;

export class FrameStep {
  /**
   * @param pendingMs 上一次已发出但未落地的步进目标；无则 undefined
   * @param positionMs 播放器当前位置（仅 pendingMs 缺省时使用）
   * @param frameMs 单帧时长（ms，可为小数）
   * @param durationMs 片长；<=0 表示未知，不限上限
   * @returns 新的步进目标（ms，>=0）
   */
  static next(pendingMs: number | undefined, positionMs: number, frameMs: number,
    fwd: boolean, durationMs: number): number {
    const anchor: number = pendingMs !== undefined ? pendingMs : Math.max(0, positionMs);
    if (frameMs <= 0) {
      return Math.max(0, Math.trunc(anchor));
    }
    const raw: number = fwd ? (anchor + SNAP_TOL_MS) / frameMs : (anchor - SNAP_TOL_MS) / frameMs;
    const frame: number = fwd ? Math.floor(raw) : Math.ceil(raw);
    const targetFrame: number = Math.max(0, fwd ? frame + 1 : frame - 1);
    const targetMs: number = Math.round(targetFrame * frameMs);
    const headroom: number = durationMs > 0 ? durationMs : Number.MAX_SAFE_INTEGER;
    return Math.min(headroom, Math.max(0, targetMs));
  }
}
