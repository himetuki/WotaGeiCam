/**
 * 水平仪 / 俯仰仪的角度换算——上游 camera/LevelMath.kt 直译（纯函数，零平台 import）。
 *
 * 拆出来的原因与上游相同：传感器驱动（LevelSensor）要真机才能跑，「读数超出契约 ±90」
 * 这类纯数学缺陷只能在这一层留可测接缝（上游 LevelMathTest 的回归用例来自真机 −97.9°）。
 *
 * 角度约定（与上游逐字一致）：
 * - roll：绕取景光轴的左右倾角，0 = 左右水平，**正值 = 屏幕右侧偏低**，范围 (-90, 90]；
 * - pitch：镜头光轴相对水平面的仰俯角，0 = 镜头水平，**正值 = 仰角**，范围 (-90, 90]；
 * - 显示方向换算：加速度计是机头坐标系（X=机头右、Y=机头顶、Z=出屏），只在竖屏与屏幕系重合，
 *   故 roll 需按显示旋转做整数倍平面旋转（pitch 的分子分母不含平面内旋转，不需要换算）。
 */

/** 传感器低通/发布门槛等纯常量（上游 LevelSensor.companion 的纯值部分收在这里，供 UI 共用） */
export class LevelConst {
  /** 水平判定容差（度）：UI 配色与 isLevel 共用这一个阈值，避免两处各写一份 */
  static readonly LEVEL_TOLERANCE_DEG: number = 1.5;
  /** 低通系数：越小越稳但越钝；0.30 是上游「仪表更新延迟过高」修复后的值 */
  static readonly SMOOTH_ALPHA: number = 0.30;
  /** 小于该角度变化不发布新值（50Hz 原始事件直接驱动 UI 会白白重组发热） */
  static readonly EMIT_STEP_DEG: number = 0.1;
  /** 有效重力量下限（×9.80665 m/s²）：剧烈抖动/近似自由落体时读数不可信 */
  static readonly MIN_VALID_G: number = 0.6;
  /** X-Y 投影相对重量的下限：接近「平放」时 roll 数值失真，保持上一次值 */
  static readonly FLAT_GUARD: number = 0.25;
  /** 标准重力加速度 */
  static readonly GRAVITY: number = 9.80665;
  /** 回正震动时长（ms） */
  static readonly BUZZ_MS: number = 100;
  /** 注册后静默多久判「HAL 没吐数据」并重挂（首启传感器唤醒竞态的自愈） */
  static readonly WATCHDOG_DELAY_MS: number = 600;
  /** 自愈重挂上限（两次仍静默就放弃，避免死循环耗电） */
  static readonly WATCHDOG_MAX_RETRIES: number = 2;
}

export class LevelMath {
  /**
   * 机头重力分量旋到显示帧：整数倍平面旋转。
   * 用分支而不是 sin/cos，免浮点误差与逐事件建表。返回 [x, y]。
   */
  static displayVector(sx: number, sy: number, displayDegrees: number): Array<number> {
    const d: number = ((displayDegrees % 360) + 360) % 360;
    if (d === 90) {
      return [-sy, sx];
    }
    if (d === 180) {
      return [-sx, -sy];
    }
    if (d === 270) {
      return [sy, -sx];
    }
    return [sx, sy];
  }

  /**
   * 左右倾角（度），**保证落在 (-90, 90]**，正值 = 屏幕右侧偏低。
   *
   * `atan2(-ux, uy)` 原始值域是 ±180：重力落在显示帧下半区（uy<0，机位接近相对显示倒置）
   * 会读出 −95°、+170° 这类超出仪表刻度的值（上游真机竖屏实测 −97.9°）。
   * 水平仪表达的是「偏离水平多少」，超过 ±90 就折回另一半平面。
   */
  static rollOf(ux: number, uy: number): number {
    return LevelMath.foldToHalfCircle(LevelMath.toDegrees(Math.atan2(-ux, uy)));
  }

  /** 镜头仰俯角（度）：0 = 光轴水平，正值 = 仰角 */
  static pitchOf(sx: number, sy: number, sz: number): number {
    const planar: number = Math.sqrt(sx * sx + sy * sy);
    return LevelMath.toDegrees(Math.atan2(-sz, planar));
  }

  /** 把任意角度折到 (-90, 90]：每 180° 一个水平仪周期 */
  static foldToHalfCircle(degrees: number): number {
    if (!Number.isFinite(degrees)) {
      return 0;
    }
    let d: number = degrees;
    while (d > 90) {
      d -= 180;
    }
    while (d <= -90) {
      d += 180;
    }
    return d;
  }

  static toDegrees(radians: number): number {
    return radians * 180 / Math.PI;
  }

  /** 是否回正（|roll| 在容差内）：UI 着色与回正震动共用一条判据 */
  static isLevelOf(rollDegrees: number): boolean {
    return Math.abs(rollDegrees) < LevelConst.LEVEL_TOLERANCE_DEG;
  }
}
