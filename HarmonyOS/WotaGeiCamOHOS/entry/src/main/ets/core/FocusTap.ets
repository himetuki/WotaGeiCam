/**
 * 取景页 tap-to-focus 的**纯算式**（FC 批后续，零平台 import）。
 *
 * 上游语义：点按预览 = 对焦 + 测光（tapPoint 归一化 [0,1]，同时驱动 setFocusPoint 与
 * setMeteringPoint——鸿蒙 RequestApplier 同款双驱动）。本模块只做「窗口坐标 → 归一化
 * TapPoint」的换算与边缘钳制；下发链（params.focusPoint → RequestApplier.setFocusPoint）
 * 在页面接线。
 *
 * 边缘钳制：归一化后钳到 [EDGE_MARGIN, 1-EDGE_MARGIN]——点在屏幕极边缘时对焦框会
 * 被系统栏裁掉一半，2% 余量保住可视反馈。
 */
import { TapPoint } from './Constants';

export class FocusTapMath {
  /** 归一化边缘余量：焦点标记不落在屏幕极边缘（2%） */
  static readonly EDGE_MARGIN: number = 0.02;

  /**
   * 窗口坐标（vp，ClickEvent.windowX/windowY）→ 归一化 TapPoint。
   *
   * @param winWvp/winHvp 窗口尺寸（vp）；<= 0（量不到）返回 undefined——
   *        宁可放弃这次对焦也不给 [0,0] 这类伪点。
   */
  static pointFromWindowTap(windowXVp: number, windowYVp: number, winWvp: number,
    winHvp: number): TapPoint | undefined {
    if (!(winWvp > 0) || !(winHvp > 0)) {
      return undefined;
    }
    if (!Number.isFinite(windowXVp) || !Number.isFinite(windowYVp)) {
      return undefined;
    }
    const m: number = FocusTapMath.EDGE_MARGIN;
    const nx: number = Math.min(1 - m, Math.max(m, windowXVp / winWvp));
    const ny: number = Math.min(1 - m, Math.max(m, windowYVp / winHvp));
    return new TapPoint(nx, ny);
  }
}
