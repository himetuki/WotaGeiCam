/**
 * 直译自上游 core/Constants.kt（harmony 分支）。
 * 平台差异处理：
 * - AndroidSize/AndroidRect 类型别名与其转换函数不移植（相机适配层再按 CameraKit 映射）；
 * - Kotlin Long 整除有截断语义，1e9/denom 一律 Math.floor 保持档位纳秒值一致；
 * - HudItem/CamPill 的 labelRes(R.string.id) 改为 labelKey 字符串键，UI 层按同键配置资源；
 * - "%.1f"/"%02d" 格式化用手写实现，保证与 Locale.US 逐字符一致。
 */

/** 画质档位用尺寸（对应上游契约里的 `Size`） */
export class Size {
  width: number;
  height: number;

  constructor(width: number, height: number) {
    this.width = width;
    this.height = height;
  }

  get pixels(): number {
    return this.width * this.height;
  }

  equalsSize(o: Size): boolean {
    return this.width === o.width && this.height === o.height;
  }
}

/** 传感器有效像素区（对应上游契约里的 `Rect`），坐标语义与 android.graphics.Rect 一致 */
export class Rect {
  left: number;
  top: number;
  right: number;
  bottom: number;

  constructor(left: number, top: number, right: number, bottom: number) {
    this.left = left;
    this.top = top;
    this.right = right;
    this.bottom = bottom;
  }

  get arrayWidth(): number {
    return this.right - this.left;
  }

  get arrayHeight(): number {
    return this.bottom - this.top;
  }

  isEmpty(): boolean {
    return this.arrayWidth <= 0 || this.arrayHeight <= 0;
  }
}

/** 产品档位表：与设备能力求交后才是 UI 可选项，交集外档位灰显 */
export class WotaTiers {
  /** 需求二：码率 5 档（bps） */
  static readonly BITRATES: Array<number> = [5_000_000, 10_000_000, 20_000_000, 35_000_000, 50_000_000];

  /** 需求二：音频采样率 3 档；默认 48k（视频工业标准，少一次重采样） */
  static readonly SAMPLE_RATES: Array<number> = [32_000, 44_100, 48_000];
  static readonly DEFAULT_SAMPLE_RATE: number = 48_000;

  /** 需求二：帧率档位；24/25 为必须，>60 需 CONSTRAINED_HIGH_SPEED_VIDEO */
  static readonly FPS: Array<number> = [24, 25, 30, 50, 60, 120, 240];
  static readonly REQUIRED_FPS: Set<number> = new Set<number>([24, 25]);

  /** 需求二：快门分子档位（分母即秒数），1/24 与 1/25 必须 */
  static readonly SHUTTER_DENOM: Array<number> = [24, 25, 30, 50, 60, 120, 240];

  /**
   * 「强制快门档」= 产品必须的 1/24、1/25（与 REQUIRED_FPS 一条对称口径）。
   * 设备曝光范围里没有这两档时照样可选并按该值下发（是否被 HAL 接受由设备决定）；
   * 只豁免设备曝光范围这一刀，1/10s 防手抖与 ≤1/帧周期两条硬规则照旧生效。
   */
  static readonly REQUIRED_SHUTTER_DENOM: Set<number> = new Set<number>([24, 25]);

  static readonly SHUTTER_CAP_NS: number = 100_000_000;   // 1/10s 防手抖钳制
  static readonly MAX_FILE_BYTES: number = 3_758_096_384; // 3.5GiB FAT32 安全上限
  static readonly MIN_FREE_MB: number = 200;              // 录像前余量门槛
  static readonly ASPECTS: Array<string> =
    ['16:9', '20:9' /* 原相机全屏档 */, '4:3', '1:1', '5:3', '4:3p' /* 4080x3060 类 */];

  static readonly NS_PER_SECOND: number = 1_000_000_000;
  /** >60fps 分水岭：会话/防抖/分析流行为全换 */
  static readonly HIGH_SPEED_FPS: number = 60;

  /** 快门档位对应的纳秒值，UI 与快门滑杆共用一份（Kotlin Long 整除截断 → Math.floor） */
  static readonly SHUTTER_TIER_NS: Array<number> = WotaTiers.SHUTTER_DENOM.map(
    (d: number): number => Math.floor(WotaTiers.NS_PER_SECOND / d));

  /** 强制快门档的纳秒值（1/24 = 41_666_666、1/25 = 40_000_000），下发与 UI 共用一份 */
  static readonly REQUIRED_SHUTTER_NS: Set<number> = new Set<number>(
    WotaTiers.SHUTTER_DENOM.filter((d: number): boolean => WotaTiers.REQUIRED_SHUTTER_DENOM.has(d))
      .map((d: number): number => Math.floor(WotaTiers.NS_PER_SECOND / d)));

  /** 强制档判据的唯一出处：下发侧（跳过设备曝光范围那两刀）与 UI（打 ※）必须同源 */
  static isRequiredShutterNs(ns: number): boolean {
    return WotaTiers.REQUIRED_SHUTTER_NS.has(ns);
  }

  // 色温/色调是「产品滑杆域」（u=(K-2000)/8000、v=(tint+50)/100），非设备能力
  static readonly KELVIN_MIN: number = 2000;
  static readonly KELVIN_MAX: number = 10_000;
  static readonly TINT_MIN: number = -50;
  static readonly TINT_MAX: number = 50;
}

/**
 * 光弧补弧合并的差分死区（8bit luma 级）——CPU/GPU 两路共用的唯一档位真源。
 * 判据：Δluma = luma(被抽帧) − luma(保留帧)；Δluma > D 时整颗像素取被抽帧，否则保持保留帧。
 * 这是差分死区，不是绝对亮度阈值；D=0 退化为纯整像素选择，仍不造色。
 * 改值只走 WotaParams.setArcMergeDeadband（经 publish 统一发布给无参数总线的消费方）。
 */
export class ArcMergeDeadband {
  static readonly DEFAULT: number = 12;
  static readonly MIN: number = 0;
  static readonly MAX: number = 32;

  static clamp(value: number): number {
    return Math.min(ArcMergeDeadband.MAX, Math.max(ArcMergeDeadband.MIN, value));
  }

  /** 归一到 GLSL 的 0..1 亮度差（uniform uDeadbandLuma） */
  static luma(value: number): number {
    return ArcMergeDeadband.clamp(value) / 255;
  }

  /** 最近一次由参数总线发布的值（UI 线程写、GL/修复线程读） */
  static deadband: number = ArcMergeDeadband.DEFAULT;

  static publish(value: number): void {
    ArcMergeDeadband.deadband = ArcMergeDeadband.clamp(value);
  }
}

/** 需求参考线 11 档，位掩码可组合；枚举值 = bit */
export enum RefLineType {
  GRID_3X3 = 1,
  DIAGONAL = 2,
  CROSSHAIR = 4,
  BROADCAST_SAFE = 8,
  SAFE_AREA = 16,
  GRID = 32,
  CENTER_MARK = 64,
  HORIZON_LINE = 128,
  RED_TOP = 256,
  RED_MID = 512,
  RED_BOTTOM = 1024
}

export class RefLines {
  static readonly ALL: Array<RefLineType> = [
    RefLineType.GRID_3X3, RefLineType.DIAGONAL, RefLineType.CROSSHAIR, RefLineType.BROADCAST_SAFE,
    RefLineType.SAFE_AREA, RefLineType.GRID, RefLineType.CENTER_MARK, RefLineType.HORIZON_LINE,
    RefLineType.RED_TOP, RefLineType.RED_MID, RefLineType.RED_BOTTOM
  ];

  static bit(t: RefLineType): number {
    return t as number;
  }

  static get FULL_MASK(): number {
    let m: number = 0;
    for (const t of RefLines.ALL) {
      m |= RefLines.bit(t);
    }
    return m;
  }

  static label(t: RefLineType): string {
    switch (t) {
      case RefLineType.GRID_3X3: return '三分法网格';
      case RefLineType.DIAGONAL: return '对角线';
      case RefLineType.CROSSHAIR: return '十字准线';
      case RefLineType.BROADCAST_SAFE: return '广播安全框';
      case RefLineType.SAFE_AREA: return '安全区域';
      case RefLineType.GRID: return '网格';
      case RefLineType.CENTER_MARK: return '中心标记';
      case RefLineType.HORIZON_LINE: return '水平线';
      case RefLineType.RED_TOP: return '上红线';
      case RefLineType.RED_MID: return '中红线';
      case RefLineType.RED_BOTTOM: return '下红线';
      default: return '';
    }
  }

  static typesOf(mask: number): Array<RefLineType> {
    return RefLines.ALL.filter((t: RefLineType): boolean => (mask & RefLines.bit(t)) !== 0);
  }

  static maskOf(types: Array<RefLineType>): number {
    let m: number = 0;
    for (const t of types) {
      m |= RefLines.bit(t);
    }
    return m;
  }

  /** 就近胶囊的多选态：再点一次要能关回去 */
  static isOn(mask: number, type: RefLineType): boolean {
    return (mask & RefLines.bit(type)) !== 0;
  }

  static toggle(mask: number, type: RefLineType): number {
    return RefLines.isOn(mask, type) ? (mask & ~RefLines.bit(type)) : (mask | RefLines.bit(type));
  }
}

/**
 * 录制页常驻 HUD 可选项，位掩码可组合（持久化键 hud_items）。
 * labelKey 对应上游 R.string 键名，UI 层按同名资源键取文案。
 */
export enum HudItem {
  SHUTTER = 1,
  FPS = 2,
  BITRATE = 4,
  ISO = 8,
  EV = 16,
  WB = 32,
  ZOOM = 64
}

export class HudItems {
  static readonly ALL: Array<HudItem> = [
    HudItem.SHUTTER, HudItem.FPS, HudItem.BITRATE, HudItem.ISO, HudItem.EV, HudItem.WB, HudItem.ZOOM
  ];

  static bit(t: HudItem): number {
    return t as number;
  }

  static labelKey(t: HudItem): string {
    return `cam_hud_${HudItems.nameOf(t).toLowerCase()}`;
  }

  static nameOf(t: HudItem): string {
    switch (t) {
      case HudItem.SHUTTER: return 'SHUTTER';
      case HudItem.FPS: return 'FPS';
      case HudItem.BITRATE: return 'BITRATE';
      case HudItem.ISO: return 'ISO';
      case HudItem.EV: return 'EV';
      case HudItem.WB: return 'WB';
      case HudItem.ZOOM: return 'ZOOM';
      default: return '';
    }
  }

  /** 出厂默认上屏四档：快门 / 帧率 / 码率 / 感光度（只决定没存过 hud_items 的新装用户） */
  static readonly DEFAULT_MASK: number =
    HudItems.bit(HudItem.SHUTTER) | HudItems.bit(HudItem.FPS) | HudItems.bit(HudItem.BITRATE) | HudItems.bit(HudItem.ISO);

  static typesOf(mask: number): Array<HudItem> {
    return HudItems.ALL.filter((t: HudItem): boolean => (mask & HudItems.bit(t)) !== 0);
  }

  static maskOf(types: Array<HudItem>): number {
    let m: number = 0;
    for (const t of types) {
      m |= HudItems.bit(t);
    }
    return m;
  }
}

/** 核心渲染模式：GPU = 图像信号交 GPU 实时处理；DIRECT = 绕过 GPU 直连传感器与编码器 */
export enum RenderMode {
  GPU,
  DIRECT
}

/**
 * 强制 24/25fps 在无原生精确档设备上的录制期转换模式。
 * MEND 自动抽帧补弧：被抽帧以整像素选择 + 差分死区并入前后保留帧；
 * DROP 仅抽帧：只丢帧不补，被抽帧位次记 sidecar。
 */
export enum ArcConvertMode {
  MEND,
  DROP
}

/** AE 开关是枚举，手动档一次写全 ISO+快门+帧周期三件套，杜绝半手动中间态 */
export enum AeMode {
  AUTO,
  MANUAL,
  LOCK
}

/** AF 模式到 Camera2 CONTROL_AF_MODE 的一一映射；cam2Mode 保留 Android 契约值，OHOS 侧由相机适配层消费 */
export enum AfMode {
  CONTINUOUS_VIDEO = 3,
  CONTINUOUS_PICTURE = 4,
  MACRO = 2,
  MANUAL = 0
}

export class AfModes {
  /** 换挡回填优先级：录像连续优先，能力缺失时依次降级 */
  static readonly PREFERENCE: Array<AfMode> = [
    AfMode.CONTINUOUS_VIDEO, AfMode.CONTINUOUS_PICTURE, AfMode.MACRO, AfMode.MANUAL
  ];

  static cam2Mode(m: AfMode): number {
    return m as number;
  }

  static fromCam2(mode: number): AfMode | undefined {
    for (const m of AfModes.PREFERENCE) {
      if (AfModes.cam2Mode(m) === mode) {
        return m;
      }
    }
    return undefined;
  }
}

/** AWB 预设，枚举值 = CONTROL_AWB_MODE 契约值；MANUAL 走色温+色调（AWB_MODE=OFF） */
export enum WbPreset {
  MANUAL = 0,
  AUTO = 1,
  INCANDESCENT = 2,
  FLUORESCENT = 3,
  WARM_FLUORESCENT = 4,
  DAYLIGHT = 5,
  CLOUDY_DAYLIGHT = 6,
  TWILIGHT = 7,
  SHADE = 8
}

export class WbPresets {
  static readonly ALL: Array<WbPreset> = [
    WbPreset.AUTO, WbPreset.INCANDESCENT, WbPreset.FLUORESCENT, WbPreset.WARM_FLUORESCENT,
    WbPreset.DAYLIGHT, WbPreset.CLOUDY_DAYLIGHT, WbPreset.TWILIGHT, WbPreset.SHADE, WbPreset.MANUAL
  ];

  static awbMode(p: WbPreset): number {
    return p as number;
  }

  static fromAwbMode(mode: number): WbPreset | undefined {
    for (const p of WbPresets.ALL) {
      if (WbPresets.awbMode(p) === mode) {
        return p;
      }
    }
    return undefined;
  }
}

export enum Flash {
  OFF,
  ON,
  AUTO,
  TORCH
}

/** UI 四选项（关闭/OIS/EIS/OIS+EIS），能力门控后才有档位 */
export enum Stabilize {
  OFF,
  OIS,
  EIS,
  OIS_EIS
}

/** 单效果槽：监看类效果互斥（开启特效要求 renderMode == GPU） */
export enum FrameEffect {
  NONE,
  ZEBRA,
  PEAKING
}

export enum PeakingColor {
  WHITE,
  GREEN,
  BLUE,
  ORANGE,
  RED
}

/**
 * 点按对焦/测光落点。x/y 为预览画面归一化坐标 [0,1]，已按 sensorOrientation 摆正；
 * 换算成测光区域是相机适配层的事（需 activeArray 与区域上限）。
 */
export class TapPoint {
  x: number;
  y: number;

  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

/** 日志 Tag：沿用上游口径，hilog 按 tag 过滤 */
export class WotaTag {
  static readonly ABILITY: string = 'WotaAbility';
  static readonly LENS: string = 'WotaLens';
  static readonly PARAMS: string = 'WotaParams';
  static readonly VIDEO_SIZE: string = 'WotaVideoSize';
}

/**
 * 录制页上「点了会弹面板」的控件胶囊（#54）。与 HudItem 的常驻读数分两套位掩码：
 * 读数走 hud_items，控件走 hud_pills，默认全开。labelKey 对应上游 R.string 键名。
 * 快门、缩略图、设置齿轮不在这一套里：关掉它们等于把应用锁死。
 */
export enum CamPill {
  LEVEL = 1,
  VOLUME = 2,
  BT = 4,
  ZOOM = 8,
  FOCUS = 16,
  STAB = 32,
  REFLINE = 64,
  MONITOR = 128,
  CURVE = 256,
  FLASH = 512,
  LENS = 1024,
  SIZE = 2048,
  STORAGE = 4096
}

export class CamPills {
  static readonly ALL: Array<CamPill> = [
    CamPill.LEVEL, CamPill.VOLUME, CamPill.BT, CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB,
    CamPill.REFLINE, CamPill.MONITOR, CamPill.CURVE, CamPill.FLASH, CamPill.LENS, CamPill.SIZE,
    CamPill.STORAGE
  ];

  static bit(t: CamPill): number {
    return t as number;
  }

  static labelKey(t: CamPill): string {
    return `cam_pill_${CamPills.nameOf(t).toLowerCase()}`;
  }

  static nameOf(t: CamPill): string {
    switch (t) {
      case CamPill.LEVEL: return 'LEVEL';
      case CamPill.VOLUME: return 'VOLUME';
      case CamPill.BT: return 'BT';
      case CamPill.ZOOM: return 'ZOOM';
      case CamPill.FOCUS: return 'FOCUS';
      case CamPill.STAB: return 'STAB';
      case CamPill.REFLINE: return 'REFLINE';
      case CamPill.MONITOR: return 'MONITOR';
      case CamPill.CURVE: return 'CURVE';
      case CamPill.FLASH: return 'FLASH';
      case CamPill.LENS: return 'LENS';
      case CamPill.SIZE: return 'SIZE';
      case CamPill.STORAGE: return 'STORAGE';
      default: return '';
    }
  }

  static readonly DEFAULT_MASK: number = CamPills.ALL.reduce(
    (acc: number, t: CamPill): number => acc | CamPills.bit(t), 0);

  /** 掩码里没置位的那些就是要隐藏的胶囊 */
  static hiddenOf(mask: number): Set<CamPill> {
    return new Set<CamPill>(CamPills.ALL.filter((t: CamPill): boolean => (mask & CamPills.bit(t)) === 0));
  }

  static maskOf(types: Array<CamPill>): number {
    let m: number = 0;
    for (const t of types) {
      m |= CamPills.bit(t);
    }
    return m;
  }
}

/** Locale.US 等价的定点格式化（值域非负，手写实现保证逐字符一致） */
function fixed1(v: number): string {
  const t: number = Math.round(v * 10);
  return `${Math.floor(t / 10)}.${Math.abs(t % 10)}`;
}

function pad2(v: number): string {
  return v < 10 ? `0${v}` : `${v}`;
}

/** 剩余空间读数：≥1G 走一位小数（96.2G），否则直接 MB（512M） */
export function freeSpaceShort(mb: number): string {
  return mb >= 1024 ? `${fixed1(mb / 1024)}G` : `${mb}M`;
}

/**
 * 剩余空间按当前总码率还能录多久：3h18m / 18m / <1m，读不出来时给 --m。
 * 换算按 1 MiB = 1048576 B 与总 bps 直算。
 */
export function recordableText(freeMb: number, totalBitrateBps: number): string {
  if (freeMb <= 0 || totalBitrateBps <= 0) {
    return '--m';
  }
  const sec: number = Math.floor(freeMb * 1_048_576 * 8 / totalBitrateBps);
  if (sec < 60) {
    return '<1m';
  }
  if (sec < 3600) {
    return `${Math.floor(sec / 60)}m`;
  }
  return `${Math.floor(sec / 3600)}h${pad2(Math.floor((sec % 3600) / 60))}m`;
}

/** 顶栏容量段读数：「96.2G · 3h18m」 */
export function capacityLineText(freeMb: number, totalBitrateBps: number): string {
  return `${freeSpaceShort(freeMb)} · ${recordableText(freeMb, totalBitrateBps)}`;
}

/** 满档之外再退一档：只留小时「96.2G · 3h」，1 小时以内与满档同形 */
export function recordableHoursText(freeMb: number, totalBitrateBps: number): string {
  if (freeMb <= 0 || totalBitrateBps <= 0) {
    return '--m';
  }
  const sec: number = Math.floor(freeMb * 1_048_576 * 8 / totalBitrateBps);
  return sec < 3600 ? recordableText(freeMb, totalBitrateBps) : `${Math.floor(sec / 3600)}h`;
}

/** 顶栏右端固定件吃掉的宽度（dp）：内边距与间距之和，与上游同一条账、同一个来源 */
export const TOP_BAR_CHROME_RESERVE_DP: number = 66;

/** 顶栏元信息卡里与文字无关的固定件（dp）：与 HudLayer 渲染路的内边距逐项对应 */
export const CAPACITY_ROOM_FIXED_CHROME_DP: number = 37;

/** 容量段的净可用宽（dp）：roomDp − 画幅段文案实测宽 − 固定件 */
export function capacityNetRoomDp(roomDp: number, sizeTextDp: number): number {
  return roomDp - sizeTextDp - CAPACITY_ROOM_FIXED_CHROME_DP;
}

/** 小时档文案「96.2G · 22h」 */
export function capacityHoursText(freeMb: number, totalBitrateBps: number): string {
  return `${freeSpaceShort(freeMb)} · ${recordableHoursText(freeMb, totalBitrateBps)}`;
}

/**
 * 顶栏容量段按净可用宽取三档：判据两侧都是实测宽（roomDp 与两段候选文案宽），
 * 系统字体、OEM 字形怎么变，判据都跟着变。本函数仍是纯取位，可单测。
 */
export function capacityTierText(
  freeMb: number,
  totalBitrateBps: number,
  roomDp: number,
  fullTextDp: number,
  hoursTextDp: number
): string {
  // 2dp 余量：文案宽度是量出来的、可用宽是容器回报的，贴着边排会来回抖
  const marginDp: number = 2;
  if (roomDp >= fullTextDp + marginDp) {
    return capacityLineText(freeMb, totalBitrateBps);
  }
  if (roomDp >= hoursTextDp + marginDp) {
    return capacityHoursText(freeMb, totalBitrateBps);
  }
  return freeSpaceShort(freeMb);
}
