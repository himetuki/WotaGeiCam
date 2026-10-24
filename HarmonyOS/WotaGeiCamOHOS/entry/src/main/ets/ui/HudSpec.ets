/**
 * 固定 HUD 规格（J1/J2 已落 HudMetrics/HudLayout 纯逻辑地基；拖拽布局编辑器留 J3b）。
 * 纯常量/纯函数，不 import UI Kit。尺寸随 HudTokens 的触控/字阶更新（T31 真机取证）：
 * - 顶栏固定两行读数（label 11fp + value 13fp），最小高 120vp 容纳渐变遮罩；
 * - 固定行之下是 hud_items 掩码驱动的「读数条」（J3a 临时落位，换行档走 HudMetrics/J2）；
 * - 底栏两行胶囊（2×4，48vp 高）+ 快门行（72vp）：**不设固定高**（内容自适应，
 *   降级态消息/权限/重试按钮出现时不裁快门）；上下渐变遮罩高度见 TOP/BOTTOM_SCRIM_HEIGHT。
 */
export class HudSpec {
  /** 顶部 HUD 最小高（遮罩渐变 + 两行读数） */
  static readonly TOP_BAR_HEIGHT: number = 120;
  /** 页面左右安全边距 */
  static readonly SAFE_MARGIN: number = 12;
  /** 顶部渐变遮罩高（只压暗 HUD 区，不挡触摸） */
  static readonly TOP_SCRIM_HEIGHT: number = 132;
  /** 底部渐变遮罩高 */
  static readonly BOTTOM_SCRIM_HEIGHT: number = 232;
  /** 读数条端侧设计留白（上游 HudEdgePad 8dp；hudRoomDp 的 endPadDp 实参同源，不许调用点散写 8） */
  static readonly HUD_EDGE_PAD: number = 8;

  /**
   * 顶部第一行固定读数（4 项）：镜头、画幅、电量、可用空间。
   * J3a：帧率迁入 hud_items 读数条（FPS 属 HudItem 集，同一读数不得两处出现），
   * 每项渲染为 label(11fp) + value(13fp) 两行，value 单行省略。
   */
  static readonly HUD_ROW1: Array<string> = ['lens', 'size', 'battery', 'free'];

  /**
   * 顶部第二行固定读数（1 项）：录制时长。
   * J3a：快门/ISO/EV/白平衡迁入 hud_items 读数条（位掩码可编排、设置页多选），
   * 固定行只留时长（duration 不在 HudItem 集，保留不动）。读数条渲染在两行固定行之下。
   */
  static readonly HUD_ROW2: Array<string> = ['duration'];

  /** 固定行读数短标签（迁入读数条的 7 档标签在 core.Constants 的 HudItems.label） */
  static rowLabelOf(key: string): string {
    if (key === 'lens') {
      return '镜头';
    }
    if (key === 'size') {
      return '画幅';
    }
    if (key === 'battery') {
      return '电量';
    }
    if (key === 'free') {
      return '空间';
    }
    return '时长';
  }

  /** 底部 Dock 主胶囊（2×4 两行）：第一行镜头/画幅/帧率/变焦，第二行快门/ISO/EV/WB */
  static readonly DOCK_ROW1: Array<string> = ['lens', 'size', 'fps', 'zoom'];
  static readonly DOCK_ROW2: Array<string> = ['shutter', 'iso', 'ev', 'wb'];
}
