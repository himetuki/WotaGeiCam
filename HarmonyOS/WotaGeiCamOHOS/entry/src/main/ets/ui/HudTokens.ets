/**
 * 深色 UI 设计令牌（T31 建，HM7-UI-02 扩）：色板 / 字阶 / 触控 / 形状 / 间距唯一真源。
 * 两族色板：HudColor = 取景 HUD（半透明浮层压在相机画面上）；PageColor = 页面语义
 * （媒体库/播放/对比的实底深色面板与文本阶）。
 *
 * 取值口径（对齐上游 design/Tokens.kt 的意图，非逐值移植——上游是 HDS 令牌）：
 * - 背景恒为相机画面，所有浮层用**半透明深色**而不是浅色实底，保证任何画面下白字可读；
 * - 触控最小 48vp（上游 HDS 最小点击区）；录制快门 72vp 外环 + 44vp 红点（+24vp 停止方块）；
 * - 字阶：label 11fp / value 13fp（顶栏读数与正文）/ pill 12fp（Dock 胶囊，360vp 宽下防截字）/
 *   title 17fp；**HUD 相关（含弹层）禁止 10fp**（真机 10fp 在 1260px 宽屏上过小，T31 取证 P1）。
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
  static readonly WARN: string = '#FFFFCC80';
  static readonly DANGER: string = '#FFFF8A80';
  /** 网格线（细、低对比，压暗背景时仍可见） */
  static readonly GRID_LINE: string = '#66FFFFFF';
  static readonly DIVIDER: string = '#26FFFFFF';
  /** 取景参考线（与上游 wota_refline 同语义：细、半透明白） */
  static readonly REF_LINE: string = '#59FFFFFF';
  /** 参考线里的 WOTA 走位红线（上游 wota_refline_red，2dp 加粗压在其余线之上） */
  static readonly REF_LINE_RED: string = '#CCFF3B30';
}

/**
 * 页面语义色板（HM7-UI-02 令牌统一批）：媒体库/播放/对比页既定视觉的收敛
 * （取值 = 迁移前各页在用字面量，只收敛不重设计）；与 HudColor 同为设计令牌真源。
 * 字阶偏离登记：页内显示档 9fp（角标）/15/16/18fp（节标题）/28fp（空态字形）为
 * 一次性展示尺寸，不入档（HUD 主档 11-17 不变，HUD 浮层仍禁 10fp）。
 */
export class PageColor {
  /** 列表卡/行底 */
  static readonly CARD: string = '#1F1F1F';
  /** 弹层/详情面板底（蓝灰深） */
  static readonly PANEL: string = '#263238';
  /** 次级按钮/页签底 */
  static readonly CHIP: string = '#333333';
  /** 次要文本（元信息行） */
  static readonly TEXT_SECONDARY: string = '#B0BEC5';
  /** 弱化文本（空态/提示） */
  static readonly TEXT_DIM: string = '#AAAAAA';
  /** 极弱文本（引导/日期头） */
  static readonly TEXT_FAINT: string = '#78909C';
  /** 提示文本（标签/注记） */
  static readonly TEXT_HINT: string = '#90A4AE';
  /** 收藏/强调粉 */
  static readonly LIKED: string = '#FF3D71';
  /** 角标底衬（强） */
  static readonly OVERLAY: string = '#AA000000';
  /** 角标底衬 */
  static readonly OVERLAY_SOFT: string = '#99000000';
  /** 对比页面板底 */
  static readonly PANEL_COMPARE: string = '#F0202020';
}

/** 字阶（fp） */
export class HudType {
  static readonly LABEL: number = 11;
  static readonly VALUE: number = 13;
  /** Dock 胶囊值字号：360vp 宽下 4 等分文本区约 60vp，"画幅:1080p" 13fp 会临界截断 */
  static readonly PILL: number = 12;
  static readonly TITLE: number = 17;
  static readonly BODY: number = 13;
  static readonly CAPTION: number = 11;
}

/** 触控与控件尺寸（vp） */
export class HudTouch {
  /** 胶囊高（本身即 ≥48vp 最小触控区） */
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
