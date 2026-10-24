/**
 * 取景页 tap/双击/长按手势共用的**纯算式**（TFC 起步、FL 对齐上游，零平台 import）。
 *
 * 上游语义（ui/CameraScreen.kt 的 toTapPoint + tapFocus）：
 * - 点按预览 = 对焦 + 测光（tapPoint 归一化 [0,1]，RequestApplier 同款 setFocusPoint/
 *   setMeteringPoint 双驱动）；提供 onDoubleTap 后**单击派发等双击窗口结束才落**（Compose
 *   手势判定口径）——仲裁态机在本文件 FocusTapArbiter；
 * - 双击 = 该点锁定对焦；长按半秒 = 解锁并在该点重新对焦（锁定期）；
 * - 视图坐标即画面归一化坐标（容器按画面比例布局）；
 * - **视窗尺寸非法（<=0）回画面中心 (0.5,0.5)**，钳 [0,1] 无边缘余量（上游原样——
 *   TFC 期的 2% 边缘余量撤销，对齐上游；标记越界由标记本体尺寸/定位消化）。
 */
import { TapPoint } from './Constants';

export class FocusTapMath {
  /**
   * 窗口/视图坐标（vp）→ 归一化 TapPoint。
   * 视图尺寸 <=0（量不到）**回画面中心**（上游原样：TapPoint(0.5,0.5)），不返回 undefined。
   */
  static pointFromWindowTap(windowXVp: number, windowYVp: number, winWvp: number,
    winHvp: number): TapPoint {
    if (!(winWvp > 0) || !(winHvp > 0) || !Number.isFinite(windowXVp) || !Number.isFinite(windowYVp)) {
      return new TapPoint(0.5, 0.5);
    }
    const nx: number = Math.min(1, Math.max(0, windowXVp / winWvp));
    const ny: number = Math.min(1, Math.max(0, windowYVp / winHvp));
    return new TapPoint(nx, ny);
  }
}

/**
 * 点按对焦的三击派发仲裁（相机页语义；与 player/TapArbiter 同构但**无双击窗口取消语义**——
 * 相机页单击立即对焦、双击第二击加锁，两击都有效果，不存在「单击等窗口再落」的推迟）。
 *
 * 状态：0 = 空闲；1 = 刚单击（下一次 tap 在 **双击窗口** 内 = 双击锁定）。
 * windowMs 与上游双击判定窗口同量级（player/TapArbiter 的 280ms）。
 */
export class FocusTapArbiter {
  private readonly windowMs: number;
  private lastTapMs: number = 0;

  constructor(windowMs: number = 280) {
    this.windowMs = windowMs;
  }

  /**
   * 每次抬指调用。
   * @return true = 本次是**双击的第二击**（应锁定对焦）；false = 单击（应立即对焦）。
   */
  onTapUp(nowMs: number): boolean {
    const isDouble: boolean = nowMs - this.lastTapMs <= this.windowMs && this.lastTapMs > 0;
    this.lastTapMs = nowMs;
    return isDouble;
  }

  /** 解锁/长按后重置（长按重对焦不计入双击序列） */
  reset(): void {
    this.lastTapMs = 0;
  }
}
