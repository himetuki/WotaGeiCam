/**
 * 画面单击/双击仲裁（逐行直译上游 player/TapArbiter.kt，对齐上游 38d712a）。
 * 单击必须等满双击窗口才落地：否则第二击抬起时显隐已经切过一次，双击启停会顺带闪控件栏。
 */
export enum TapDecision {
  /** 先当单击：调用方挂起 windowMs 定时器，到点再切显隐 */
  DeferredToggle = 0,
  /** 双击的第二击：取消挂起显隐，改判启停 */
  PlayPause = 1
}

export class TapArbiter {
  private readonly windowMs: number;
  /** 挂起中的单击到期时刻（ms）；0 = 没有挂起 */
  private pendingDueMs: number = 0;

  constructor(windowMs: number = 280) {
    this.windowMs = windowMs;
  }

  onPointerUp(nowMs: number): TapDecision {
    if (this.pendingDueMs !== 0 && nowMs <= this.pendingDueMs) {
      this.pendingDueMs = 0;
      return TapDecision.PlayPause;
    }
    this.pendingDueMs = nowMs + this.windowMs;
    return TapDecision.DeferredToggle;
  }

  /** 挂起定时器到点：true 才真的切显隐（期间被双击/其他动作取消则 false） */
  onToggleDue(nowMs: number): boolean {
    if (this.pendingDueMs === 0 || nowMs < this.pendingDueMs) {
      return false;
    }
    this.pendingDueMs = 0;
    return true;
  }

  cancelPending(): void {
    this.pendingDueMs = 0;
  }
}
