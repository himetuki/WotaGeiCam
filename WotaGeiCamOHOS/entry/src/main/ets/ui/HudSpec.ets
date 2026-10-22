/**
 * 固定 HUD 规格（本批不移植上游 HudLayout/HudLayoutEditor；自定义布局留待 HUD 编辑器批）。
 * 纯常量/纯函数，不 import UI Kit。
 */
export class HudSpec {
  static readonly TOP_BAR_HEIGHT: number = 56;
  static readonly BOTTOM_BAR_HEIGHT: number = 178;
  static readonly PILL_ROW_HEIGHT: number = 40;
  static readonly SAFE_MARGIN: number = 12;

  static readonly GRID_DIVISIONS: number = 3;

  /** 3×3 网格线位置（1/3、2/3） */
  static gridFractions(): Array<number> {
    const out: Array<number> = [];
    for (let i = 1; i < HudSpec.GRID_DIVISIONS; i++) {
      out.push(i / HudSpec.GRID_DIVISIONS);
    }
    return out;
  }

  /** 顶栏读数（顺序与 Camera 顶栏一致）：镜头、画幅、帧率、电量、可用空间、录制时长 */
  static readonly HUD_READOUTS: Array<string> = ['lens', 'size', 'fps', 'battery', 'free', 'duration'];

  /**
   * 顶栏第二行读数（T21 高级参数批）：快门/ISO/EV/白平衡。
   * 偏离登记（PORT_MAP §ui）：上游 HudLayer 的 READOUT 是可编排网格（hud_items 位掩码 +
   * HudLayoutEditor 拖拽），本批为固定两行——上行既有项、下行高级参数。
   */
  static readonly HUD_READOUTS_ROW2: Array<string> = ['shutter', 'iso', 'ev', 'wb'];
}
