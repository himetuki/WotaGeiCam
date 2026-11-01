/**
 * 运行时帧率坍塌守卫（2026-10-28 用户6/7 反馈；上游 91e6353+36917e5 纯逻辑直译）。
 *
 * ## 为什么必须有
 * 帧率档位决议只保证**下发值合法**（机器声明过才发），保证不了**实际到帧率**：
 * - 变量档设备（WIKO：24fps 决议到 `[5,30]`）暗场下 AE 可以合法掉到 5fps；
 * - 固定档设备（华为 ALN-AL00：`[24,24]`）HAL 也会在曝光吃紧时把**结果侧**范围放宽成
 *   `[15,24]`（2026-10-28 用户6 日志 3A 遥测实证）；
 * - vivo r03 时代更是整段录在 4.98fps 上（成片容器 25fps）。
 * 三种塌法用户看到的都是同一件事——「录出来很卡」。按「一切自动降级必提示」的既定口径，
 * 这里给出运行时提示的**判定逻辑**；采样与发布在引擎侧（camera/GlCameraEngine）。
 *
 * ## 判定口径
 * - **宽限**：首次采样后 GRACE_MS 内不判低——起录预热/暗场切换的暂态不算账；
 * - **持续**：连续 SUSTAIN_SAMPLES 拍（≈1s 一拍）实测 < 目标×LOW_RATIO 才触发，
 *   单拍抖动（对焦搜索、瞬间遮挡）不触发；
 * - **滞回**：触发后要实测 ≥ 目标×RECOVER_RATIO 才解除——恢复带内不翻转，提示不闪烁；
 * - **换档即重置**：目标档位变了，旧账（宽限起点/连续计数/触发态）全部作废。
 *
 * 线程约定：所有方法只允许在**同一个**串行执行上下文调用（引擎侧即采样定时器），
 * 内部字段不加锁——跨线程发布由调用方经参数总线（StateFlow）完成。
 */

/** 帧率坍塌提示的负载（引擎写进参数总线、HUD 警告行读）：目标档位 + 实测整数值。
 *  achievedFps 只做展示粒度（四舍五入到整数），判定永远用浮点原值。 */
export class LowFpsHint {
  targetFps: number;
  achievedFps: number;

  constructor(targetFps: number, achievedFps: number) {
    this.targetFps = targetFps;
    this.achievedFps = achievedFps;
  }

  /** 总线去重用（上游 data class 相等语义的等价面） */
  sameAs(other: LowFpsHint | undefined): boolean {
    return other !== undefined && other.targetFps === this.targetFps && other.achievedFps === this.achievedFps;
  }
}

export class LowFpsWatch {
  /** 当前判据档位；换档必须走 reset，别直接改 */
  private target: number;
  private firstSampleAtMs: number = LowFpsWatch.NEVER;
  private lowRun: number = 0;
  private active: boolean = false;

  constructor(targetFps: number) {
    this.target = targetFps;
  }

  /**
   * 喂一拍实测帧率，返回**本拍之后**提示应当处于开还是关。
   * 这里返回的是当前应有的状态，不是边沿；翻转去重由调用方做（总线同值去重）。
   */
  onSample(nowMs: number, measuredFps: number): boolean {
    if (this.target <= 0 || measuredFps < 0) {
      return this.active;
    }
    if (this.firstSampleAtMs === LowFpsWatch.NEVER) {
      this.firstSampleAtMs = nowMs;
    }
    if (this.active) {
      // 滞回：恢复阈值高于触发阈值，恢复带内维持原状（提示不闪烁）
      if (measuredFps >= this.target * LowFpsWatch.RECOVER_RATIO) {
        this.active = false;
        this.lowRun = 0;
      }
    } else {
      if (nowMs - this.firstSampleAtMs < LowFpsWatch.GRACE_MS) {
        return false;
      }
      this.lowRun = measuredFps < this.target * LowFpsWatch.LOW_RATIO ? this.lowRun + 1 : 0;
      if (this.lowRun >= LowFpsWatch.SUSTAIN_SAMPLES) {
        this.active = true;
      }
    }
    return this.active;
  }

  /** 换档/会话重建：目标、宽限起点、连续计数、触发态全部作废 */
  reset(newTarget: number): void {
    this.target = newTarget;
    this.firstSampleAtMs = LowFpsWatch.NEVER;
    this.lowRun = 0;
    this.active = false;
  }

  /** 引擎侧判断手里这枚守卫是否还配当前档位，不配就换新的 */
  targets(fps: number): boolean {
    return this.target === fps;
  }

  /** 首拍之后的判低宽限：起录预热与转场暂态不算账 */
  static readonly GRACE_MS: number = 4_000;

  /** 判低线 = 目标×0.7：24fps 档实测跌破 ~17 才算塌（23.75 这种正常抖动不告警） */
  static readonly LOW_RATIO: number = 0.7;

  /** 解除线 = 目标×0.85：高于触发线，滞回防提示在阈值附近闪烁 */
  static readonly RECOVER_RATIO: number = 0.85;

  /** 持续拍数：1s 一拍 × 4 ≈ 连续 4 秒低帧才提示 */
  static readonly SUSTAIN_SAMPLES: number = 4;

  private static readonly NEVER: number = Number.MIN_SAFE_INTEGER;
}
