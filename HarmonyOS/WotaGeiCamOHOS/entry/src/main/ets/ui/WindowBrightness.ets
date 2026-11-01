/**
 * 「启动后自动亮屏」与「起播渐亮」的窗口亮度驱动——上游 BrightnessRamp（5216e72，
 * docs/plan/29 §5.3）的 OHOS 对应面。
 *
 * 平台映射（登记）：
 * - 上游写 `window.attributes.screenBrightness`（0..1 或 BRIGHTNESS_OVERRIDE_NONE 跟随系统）；
 *   OHOS 对位 = `window.Window.setWindowBrightness(brightness: number)`，0..1 覆写，
 *   **-1 = 取消覆写恢复跟随系统**（按平台公开文档口径实现并全 try/catch 包裹：失败只记
 *   日志，不崩不闪；d.ts 注解未写 -1 语义，未真机验证，登记）；
 * - 起坡值读 `getWindowProperties().brightness`（0..1；-1 = 跟随系统时读不到系统亮度
 *   ⇒ 按纯函数层的"宁可中庸"口径落 0.5，登记偏离：上游读的是系统亮度真值）；
 * - 上游驱动在 MainActivity（窗口拥有者）；本侧逐页 `window.getLastWindow(ctx)` 取窗（与
 *   setKeepScreenOn 同一条取窗路径），驱动器**页面持有**（离开页面必 restore）；
 * - 采样：纯函数层 core/BrightnessRampMath（valueAt/stepCount，纯测钉住）。
 *
 * 已知副作用（上游 §5.4 接受）：渐变期间用户手动调系统亮度会被窗口覆写压住，直到恢复。
 */
import { window } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { BrightnessRampMath } from '../core/BrightnessRampMath';
import { WotaTag } from '../core/Constants';

/** 取消覆写、恢复跟随系统的取值（平台公开文档口径） */
const FOLLOW_SYSTEM: number = -1;

export class WindowBrightness {
  private timer: number = -1;
  private ramping: boolean = false;

  /** 是否有进行中的渐变（页面 hide 时判断要不要恢复） */
  active(): boolean {
    return this.timer >= 0 || this.ramping;
  }

  /**
   * 从当前窗口亮度渐变到满亮度（冷启亮屏 / 起播渐亮共用一条驱动）。
   * @param rampMs 渐变时长 ms（0 = 立即满亮）
   */
  rampToFull(ctx: common.Context, rampMs: number): void {
    this.cancelTimer();
    this.ramping = true;
    window.getLastWindow(ctx).then((win: window.Window): void => {
      let from: number = 0.5;
      try {
        const cur: number = win.getWindowProperties().brightness;
        // 0..1 = 当前覆写/有效亮度；-1（跟随系统）读不到系统真值 ⇒ 中庸兜底（登记）
        from = BrightnessRampMath.systemFraction(Math.round(cur * 255), 255);
      } catch (e) {
        Diag.w(WotaTag.CORE, `brightness read failed: ${e}`);
      }
      const startedAtMs: number = Date.now();
      const steps: number = BrightnessRampMath.stepCount(rampMs);
      if (steps === 0) {
        this.write(win, 1);
        this.ramping = false;
        return;
      }
      const stepMs: number = rampMs / steps;
      let step: number = 0;
      this.timer = setInterval((): void => {
        step++;
        const elapsed: number = Date.now() - startedAtMs;
        const v: number = BrightnessRampMath.valueAt(elapsed, rampMs, from, 1);
        this.write(win, v);
        if (step >= steps || v >= 1) {
          this.cancelTimer();
          this.ramping = false;
        }
      }, Math.max(16, Math.round(stepMs)));
    }).catch((e: Object): void => {
      this.ramping = false;
      Diag.w(WotaTag.CORE, `brightness ramp: no window: ${e}`);
    });
  }

  /** 离开页面/用户关闸：取消渐变并取消覆写（恢复跟随系统；渐变从不写系统设置） */
  cancelAndRestore(ctx: common.Context): void {
    const wasActive: boolean = this.active();
    this.cancelTimer();
    this.ramping = false;
    if (!wasActive) {
      return;
    }
    window.getLastWindow(ctx).then((win: window.Window): void => {
      this.write(win, FOLLOW_SYSTEM);
    }).catch((_e: Object): void => {
    });
  }

  private cancelTimer(): void {
    if (this.timer >= 0) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  private write(win: window.Window, brightness: number): void {
    try {
      win.setWindowBrightness(brightness);
    } catch (e) {
      Diag.w(WotaTag.CORE, `setWindowBrightness ${brightness} failed: ${e}`);
    }
  }
}
