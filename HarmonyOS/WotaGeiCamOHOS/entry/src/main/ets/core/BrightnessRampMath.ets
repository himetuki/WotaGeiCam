/**
 * 取景器/播放页亮度渐变的**纯函数层**——上游 ui/BrightnessRamp.kt（5216e72）的算式面直译
 * （docs/plan/29 §5.5，上游 JVM 全测）。窗口属性面在 ui/WindowBrightness.ets。
 *
 * 机制事实（上游 §5.4 已验）：窗口亮度覆写是**逐窗口**的，取值 0..1，-1 = 取消覆写
 * （恢复跟随系统）；改它不需要任何权限、不写系统设置。系统没有亮度动画 API，
 * 按固定步长写窗口属性即是全部实现。
 */
export class BrightnessRampMath {
  /** 系统亮度（0..max）→ 0..1 覆写域；读不到/越界回 0.5（宁可中庸，不崩不闪满屏） */
  static systemFraction(value: number, max: number = 255): number {
    if (max <= 0 || value < 0 || value > max) {
      return 0.5;
    }
    return value / max;
  }

  /**
   * 渐变第 elapsedMs 毫秒的值：from→to 线性，超出 rampMs 钳到 to。
   * rampMs<=0 视为「立即」，直接 to——0 档语义必须比除零更明确。
   */
  static valueAt(elapsedMs: number, rampMs: number, from: number, to: number): number {
    if (rampMs <= 0) {
      return to;
    }
    const t: number = Math.min(1, Math.max(0, elapsedMs / rampMs));
    return from + (to - from) * t;
  }

  /**
   * 渐变步数：约 stepMs 一步（7s≈60 步），至少 2 步（只有 1 步就等于跳变，失去"渐变"语义）；
   * rampMs<=0 = 0 步（立即写终值）。
   */
  static stepCount(rampMs: number, stepMs: number = 117): number {
    if (rampMs <= 0) {
      return 0;
    }
    return Math.max(2, Math.floor(rampMs / stepMs));
  }

  /**
   * 「启动后自动亮屏」延迟秒数（0–10 步长 1）：缺键/非有限回**默认 1**（默认值=上游 29 §九
   * 推荐；0 是合法档=关闭，不能当缺键兜底）；在册脏值钳回域（-5 → 0 = 关闭，与上游
   * coerceIn(0,10) 同口径）。
   */
  static cameraBrightenDelaySec(raw: number | undefined): number {
    if (raw === undefined || !Number.isFinite(raw)) {
      return 1;
    }
    return Math.min(10, Math.max(0, Math.trunc(raw)));
  }

  /**
   * 「起播渐亮」时长（十分之一秒，0–30 步 5）：坏值/越界回默认 10（=1.0s，上游 29 §五 表定）。
   */
  static playerBrightenRampTenths(raw: number | undefined): number {
    if (raw === undefined || !Number.isFinite(raw)) {
      return 10;
    }
    return Math.min(30, Math.max(0, Math.trunc(raw)));
  }
}
