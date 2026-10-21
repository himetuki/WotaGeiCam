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
}
