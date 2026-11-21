/**
 * 曲线面板边长的组合期钳制（29 §1.3 F1，上游 320a956 纯函数直译）。
 *
 * prefs 读回的 sideDp 已经过读取侧钳（负数/NaN 回 200），这里带**组合期才有**的 maxSide
 * （弹窗可用区）做第二次钳：持久值比当前窗口大（换小屏/分屏）时缩回可用区，
 * 比最小值小（手改存储）时抬回最小——两边都不许破面板可用性。
 */
export class CurvePanelSize {
  /** 设计档边长（与 CurvePopup 的 CURVE_SIDE 同源：200），重置写回它 */
  static readonly DEFAULT_SIDE_DP: number = 200;

  static clamp(sideDp: number, maxSideDp: number, minSideDp: number): number {
    const base: number = Number.isNaN(sideDp) || sideDp < 0 ? CurvePanelSize.DEFAULT_SIDE_DP : sideDp;
    const hi: number = Math.max(maxSideDp, minSideDp); // maxSide 坏域（< 最小值）时以最小值为准
    return Math.min(hi, Math.max(minSideDp, base));
  }

  /**
   * 存储读回侧钳（上游 WotaSettings.curvePanelSideDp 同口径）：坏值/缺键回 200（设计档）；
   * NaN/负数在**读取侧**只钳下限——上限（可用区）是组合期才有的输入，由 [clamp] 二次钳。
   */
  static readSideOf(raw: number | undefined): number {
    if (raw === undefined || Number.isNaN(raw) || raw < 0) {
      return CurvePanelSize.DEFAULT_SIDE_DP;
    }
    return raw;
  }
}
