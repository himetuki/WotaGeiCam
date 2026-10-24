/**
 * 半模态尺寸纪律（上游 ui/design/SheetSpec.kt 直译；Top8 #8，bindsheet.md 规格节）。
 *
 * 口径（与上游逐字）：
 *   宽 480vp 上限        ——「手机横屏时保持宽度 480vp 最大宽度」
 *   最小高 320vp         ——「半模态最小高度为 320vp」
 *   最大高=短边 90%      ——「半模态最大高度为屏幕短边的 90% 高度」
 *   距信号栏 8vp         ——「高度距离信号栏保持 8vp 间距」（inset 运行时读）
 *   >600vp 短边转居中    ——「大于 600vp 设备断点以上时…屏幕居中显示」（断点输入是**短边**：
 *                           平板/折叠屏展开态才居中，手机横竖屏一律底贴屏；2026-10-02 裁决，
 *                           原「宽度>600」会让手机横屏常驻居中；短边运行时取，不按方向写死）
 *
 * 含窗口/inset 的量一律运行时取，禁止按方向写死（本机横屏挖孔在左右两侧，
 * 同一枚写死方向的常量在两个横屏方向必错一次）。
 *
 * **零平台 import**：全部算式参数化（shortSide/windowHeight/topInset 由调用方运行时读入），
 * 可在 test/pure 钉住（上游 sheetMaxHeight 的 ≈278dp 真机实测即手写期望）。
 *
 * **本侧消费方登记**：暂无半模态面板消费方（Gallery 分段清单是内嵌 Column、非半模态）——
 * 纯逻辑先行（半模态面板批落地时直接消费，J3/半模态面板批）。
 */
export class SheetSpec {
  /** 「手机横屏时保持宽度 480vp 最大宽度」 */
  static readonly SHEET_MAX_WIDTH: number = 480;
  /** 「半模态最小高度为 320vp」 */
  static readonly SHEET_MIN_HEIGHT: number = 320;
  /** 「半模态最大高度为屏幕短边的 90% 高度」 */
  static readonly SHEET_MAX_SHORT_RATIO: number = 0.9;
  /** 「高度距离信号栏保持 8vp 间距」 */
  static readonly SHEET_SIGNAL_GAP: number = 8;
  /** 弹窗内间距 12vp（design-spec §5.1） */
  static readonly SHEET_INNER_PADDING: number = 12;
  /** 「大于 600vp 设备断点以上时…屏幕居中显示」（断点输入=短边） */
  static readonly SHEET_CENTER_BREAKPOINT: number = 600;
  /** 面板顶部给页面顶栏的兜底预留（上游 PanelTopKeep 48dp；已知债：顶栏实测更高时不足） */
  static readonly PANEL_TOP_KEEP: number = 48;

  /**
   * 半模态面板的最大高（vp）：短边 90% 与「窗口高 − 顶部避让」取小，下限 0。
   * 顶部避让 = max(信号栏 inset + 8, 48 兜底)——inset 由调用方运行时读。
   * 上游真机实测（横屏 1600x720 density 2，shortSide=326）：返回 ≈278——
   * 278 < 最小高 320：横屏装不下时面板按可用高给满，属**有意的退化**。
   *
   * @param shortSideDp 窗口短边（vp，运行时读）
   * @param windowHeightDp 窗口高（vp）
   * @param topInsetDp 信号栏 inset（vp，运行时读；挖孔在左右两侧时自然为 0）
   */
  static sheetMaxHeight(shortSideDp: number, windowHeightDp: number, topInsetDp: number): number {
    const topKeep: number = Math.max(topInsetDp + SheetSpec.SHEET_SIGNAL_GAP,
      SheetSpec.PANEL_TOP_KEEP);
    const byRatio: number = shortSideDp * SheetSpec.SHEET_MAX_SHORT_RATIO;
    const byWindow: number = windowHeightDp - topKeep;
    return Math.max(0, Math.min(byRatio, byWindow));
  }

  /**
   * 半模态面板宽度：内容自然宽与 480vp 上限取小（窄屏按可用宽，调用方先夹）。
   */
  static clampSheetWidth(widthVp: number): number {
    return Math.max(0, Math.min(widthVp, SheetSpec.SHEET_MAX_WIDTH));
  }

  /**
   * 断点判定：短边 >600vp 的设备（平板/折叠屏展开态）半模态转屏幕居中；
   * 手机横竖屏一律底贴屏。输入是**短边**（运行时取，不按方向写死——2026-10-02 裁决）。
   */
  static centeredForShortSide(shortSideVp: number): boolean {
    return shortSideVp > SheetSpec.SHEET_CENTER_BREAKPOINT;
  }
}
