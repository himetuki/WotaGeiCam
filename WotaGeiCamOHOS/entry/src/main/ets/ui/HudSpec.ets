/**
 * 固定 HUD 规格（本批不移植上游 HudLayout/HudLayoutEditor；自定义布局留待 HUD 编辑器批）。
 * 纯常量/纯函数，不 import UI Kit。尺寸随 HudTokens 的触控/字阶更新（T31 真机取证）：
 * - 顶栏两行读数（每行 5 项，label 11fp + value 13fp），最小高 120vp 容纳渐变遮罩；
 * - 底栏两行胶囊（2×4，48vp 高）+ 快门行（72vp），最小高 220vp。
 */
export class HudSpec {
  /** 顶部 HUD 最小高（遮罩渐变 + 两行读数） */
  static readonly TOP_BAR_HEIGHT: number = 120;
  /** 底部 HUD 最小高（两行胶囊 + 快门行） */
  static readonly BOTTOM_BAR_HEIGHT: number = 220;
  /** 胶囊行高（= HudTouch.PILL_HEIGHT） */
  static readonly PILL_ROW_HEIGHT: number = 48;
  /** 页面左右安全边距 */
  static readonly SAFE_MARGIN: number = 12;
  /** 顶部渐变遮罩高（只压暗 HUD 区，不挡触摸） */
  static readonly TOP_SCRIM_HEIGHT: number = 132;
  /** 底部渐变遮罩高 */
  static readonly BOTTOM_SCRIM_HEIGHT: number = 232;

  static readonly GRID_DIVISIONS: number = 3;

  /** 3×3 网格线位置（1/3、2/3）——纯函数保留给测试/诊断；绘制已改确定性布局 */
  static gridFractions(): Array<number> {
    const out: Array<number> = [];
    for (let i = 1; i < HudSpec.GRID_DIVISIONS; i++) {
      out.push(i / HudSpec.GRID_DIVISIONS);
    }
    return out;
  }

  /**
   * 顶部第一行读数（5 项）：镜头、画幅、帧率、电量、可用空间。
   * 顺序与 Camera 顶栏一致；每项渲染为 label(11fp) + value(13fp) 两行，value 单行省略。
   */
  static readonly HUD_ROW1: Array<string> = ['lens', 'size', 'fps', 'battery', 'free'];

  /**
   * 顶部第二行读数（5 项）：快门、ISO、EV、白平衡、录制时长。
   * 偏离登记（PORT_MAP §ui）：上游 READOUT 是可编排网格（hud_items 位掩码 + HudLayoutEditor），
   * 本批固定两行 5 项。
   */
  static readonly HUD_ROW2: Array<string> = ['shutter', 'iso', 'ev', 'wb', 'duration'];

  /** 读数短标签（顶栏两项一行紧凑展示时的中文名） */
  static rowLabelOf(key: string): string {
    if (key === 'lens') {
      return '镜头';
    }
    if (key === 'size') {
      return '画幅';
    }
    if (key === 'fps') {
      return '帧率';
    }
    if (key === 'battery') {
      return '电量';
    }
    if (key === 'free') {
      return '空间';
    }
    if (key === 'shutter') {
      return '快门';
    }
    if (key === 'iso') {
      return 'ISO';
    }
    if (key === 'ev') {
      return 'EV';
    }
    if (key === 'wb') {
      return '白平衡';
    }
    return '时长';
  }

  /** 底部 Dock 主胶囊（2×4 两行）：第一行镜头/画幅/帧率/变焦，第二行快门/ISO/EV/WB */
  static readonly DOCK_ROW1: Array<string> = ['lens', 'size', 'fps', 'zoom'];
  static readonly DOCK_ROW2: Array<string> = ['shutter', 'iso', 'ev', 'wb'];
}
