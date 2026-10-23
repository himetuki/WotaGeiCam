/**
 * 参数落盘节流（纯逻辑，零 `@ohos.*`/`@kit.*` import）。
 *
 * 对齐上游「用户动作完成点落盘」：弹层滑杆的 `onValueChange` 每帧触发，若每帧都同步落盘
 * 就是每秒几十次同步 IO 打在 UI 线程上。本类把高频请求收敛成**尾随节流**：
 * - 窗口外的第一次请求**立刻**执行（拖杆起手就有一次落盘）；
 * - 窗口内的重复请求只在**尾部补一次**（定时器到点执行，且只执行一次）；
 * - [cancel] 取消未触发的尾随（关键点已立即落盘 / 页面销毁），之后回调即使被调用也不再触发。
 *
 * 时钟与定时器全部注入（单测用手摇 now/定时器，不碰真 `setTimeout`）。
 */
export interface FlushScheduler {
  /** 排一次延时回调，返回句柄（平台件用 setTimeout） */
  arm(delayMs: number, fn: () => void): number;
  /** 取消句柄对应的延时回调（平台件用 clearTimeout） */
  disarm(handle: number): void;
}

export class ParamsFlushThrottle {
  private readonly windowMs: number;
  private readonly now: () => number;
  private readonly scheduler: FlushScheduler;
  private readonly action: () => void;
  /** 上次真正执行 action 的时刻（-Infinity = 从未执行，首次必立即） */
  private lastRunMs: number = Number.NEGATIVE_INFINITY;
  /** 窗口内是否有待补的尾随 */
  private pending: boolean = false;
  private handle: number = -1;

  constructor(windowMs: number, now: () => number, scheduler: FlushScheduler, action: () => void) {
    this.windowMs = windowMs;
    this.now = now;
    this.scheduler = scheduler;
    this.action = action;
  }

  /**
   * 高频请求入口。返回 true = 本次已立刻执行；false = 已合并进尾随（或已有尾随在排）。
   */
  request(): boolean {
    const t: number = this.now();
    if (t - this.lastRunMs >= this.windowMs) {
      this.cancel(); // 立即通道：窗口外不再需要尾随
      this.lastRunMs = t;
      this.action();
      return true;
    }
    this.pending = true;
    if (this.handle < 0) {
      const delay: number = Math.max(0, this.windowMs - (t - this.lastRunMs));
      this.handle = this.scheduler.arm(delay, (): void => {
        this.handle = -1;
        this.fireTrailing();
      });
    }
    return false;
  }

  /** 取消未触发的尾随（不执行 action）；已执行过的窗口计时不受影响。 */
  cancel(): void {
    this.pending = false;
    if (this.handle >= 0) {
      const h: number = this.handle;
      this.handle = -1;
      this.scheduler.disarm(h);
    }
  }

  private fireTrailing(): void {
    if (!this.pending) {
      return; // cancel 后晚到的回调：不再触发
    }
    this.pending = false;
    this.lastRunMs = this.now();
    this.action();
  }
}
