/**
 * 取景页 HUD 设计令牌（T31）：深色相机 UI 的色板 / 字阶 / 触控 / 形状 / 间距唯一真源。
 *
 * 取值口径（对齐上游 design/Tokens.kt 的意图，非逐值移植——上游是 HDS 令牌）：
 * - 背景恒为相机画面，所有浮层用**半透明深色**而不是浅色实底，保证任何画面下白字可读；
 * - 触控最小 48vp（上游 HDS 最小点击区）；录制快门 72vp 外环 + 44vp 红点（+24vp 停止方块）；
 * - 字阶三档：label 11fp / value 13fp / title 17fp；正文 13fp、辅助 11fp——**禁止 10fp**
 *   （真机 10fp 在 1260px 宽屏上过小，T31 取证 P1）。
 *
 * 纯常量类（不 import 平台 Kit）。
 */
export class HudColor {
  /** 页面底板（相机未起时的黑底） */
  static readonly BLACK: string = '#FF000000';
  /** 顶部/底部渐变遮罩起止（遮罩只压暗画面，不挡触摸） */
  static readonly SCRIM_STRONG: string = '#D9000000';
  static readonly SCRIM_TRANSLUCENT: string = '#66000000';
  static readonly SCRIM_CLEAR: string = '#00000000';
  /** 胶囊底：半透明深灰（任何画面下可读） */
  static readonly PILL_BG: string = '#B312171C';
  /** 胶囊按下/选中底 */
  static readonly PILL_BG_ACTIVE: string = '#E63B4A57';
  /** 胶囊禁用底（灰显但仍可读） */
  static readonly PILL_BG_DISABLED: string = '#7312171C';
  /** 面板/卡片底（弹层） */
  static readonly CARD_BG: string = '#F014191E';
  static readonly TEXT_PRIMARY: string = '#FFFFFFFF';
  static readonly TEXT_SECONDARY: string = '#B8C4CE';
  static readonly TEXT_TERTIARY: string = '#8A97A3';
  static readonly TEXT_DISABLED: string = '#66FFFFFF';
  /** 强调色（选中档/关键动作） */
  static readonly ACCENT: string = '#FF4D6A';
  /** 录制红点 */
  static readonly RECORD_DOT: string = '#FFFF3B30';
  static readonly RECORD_DOT_RECORDING: string = '#FFFF2D2D';
  static readonly RECORD_RING: string = '#FFFFFFFF';
  static readonly SUCCESS: string = '#FF81C784';
  static readonly WARN: string = '#FFFFCC80';
  static readonly DANGER: string = '#FFFF8A80';
  /** 网格线（细、低对比，压暗背景时仍可见） */
  static readonly GRID_LINE: string = '#66FFFFFF';
  static readonly DIVIDER: string = '#26FFFFFF';
}

/** 字阶（fp） */
export class HudType {
  static readonly LABEL: number = 11;
  static readonly VALUE: number = 13;
  static readonly TITLE: number = 17;
  static readonly BODY: number = 13;
  static readonly CAPTION: number = 11;
}

/** 触控与控件尺寸（vp） */
export class HudTouch {
  /** 最小可点区 */
  static readonly MIN: number = 48;
  static readonly PILL_HEIGHT: number = 48;
  static readonly BUTTON_HEIGHT: number = 48;
  /** 录制快门：外环 72、红点 44、录制中红方块 24 */
  static readonly SHUTTER: number = 72;
  static readonly SHUTTER_INNER: number = 44;
  static readonly SHUTTER_STOP: number = 24;
}

/** 形状（vp） */
export class HudShape {
  static readonly PILL_RADIUS: number = 24;
  static readonly CARD_RADIUS: number = 14;
  static readonly PANEL_RADIUS: number = 20;
  static readonly STOP_RADIUS: number = 4;
}

/** 间距（vp） */
export class HudSpace {
  static readonly XS: number = 4;
  static readonly S: number = 6;
  static readonly M: number = 8;
  static readonly L: number = 12;
  static readonly XL: number = 16;
}
