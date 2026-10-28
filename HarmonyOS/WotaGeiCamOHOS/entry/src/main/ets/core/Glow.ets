/**
 * Glow（荧光棒光晕，上游 docs/plan/24 R3 §1.1–§1.5）的**参数模型与纯函数**。
 * 直译自上游 core/Glow.kt（ed59285）。
 *
 * # 它是 scene layer，不是监看叠加
 * 与斑马纹/峰值对焦相反：Glow 是**画面内容的一部分**（光晕该被录进去），所以两个 program 槽
 * （DISPLAY / MOVIE）都带它。它在 ProgramKey 里是**独立一维** `glow`，不是「有我没有」的互斥
 * 分支——曲线/降噪/斑马/峰值都能与它共存。
 *
 * # 为什么默认关
 * 默认档必须与改前逐字节一致（24 §〇.1 不变量 1）：关着时片元源码逐字不变、离屏链零调用。
 * 用户显式打开才付出 Glow 的 GPU 成本。
 *
 * # 颜色用 ARGB number 而不是 UI 色类型
 * 与 MarkerLineColor 同族：core 层不引 UI 色类型，且 `Color` 会与平台组件撞名。
 *
 * # 域钳在 sanitize
 * 所有数值都在构造/解码时钳进安全域，**shader 侧不再判断**（少一条分支 = 少一处驱动差异）。
 * NaN/Infinity 一律回落到该字段的出厂值：坏值若漏进 uniform，效果是「整帧看不出原因的发灰」。
 *
 * # 本侧登记（平台偏离）
 * ① 本文件当前**无平台消费方**（native Glow 链与胶囊 UI 随后续批，登记 PORT_MAP）——与 H 批
 * DirectSink/FrameRateFallback 同款「只交付纯函数 + 全表用例、不伪造接线」纪律；
 * ② encode 的浮点格式不保留 Kotlin Float.toString 的 ".0" 后缀（JS String(1.0)="1"）——编码串
 * 只在本平台自产自销（Glow 参数不跨端互认），往返精确性由 parseFloat 保证；
 * ③ glowStatusNote 返回**文案键**（上游返回 R.string 资源 id——core 不引资源的本侧等价物），
 * UI 批消费时再映射文案；
 * ④ DENOISE_FULL_KERNEL_PIXEL_RATE 单一真源在 core/Denoise.ets（本文件导入消费）——
 * glowScaleFor 与 denoiseKernelLite 同一魔数不造第二份。
 */
import { DENOISE_FULL_KERNEL_PIXEL_RATE } from './Denoise';

/** 全曲线负载阈值（像素 × 帧率）：≤ 40M 用 scale=1（24 §1.4，初版拍定、T3 真机回填） */
export const GLOW_FULL_MAX_PIXEL_RATE: number = 40_000_000;

/** 逐帧全分辨率 pass 的负载分水岭（真值常量）单一真源在文件顶部 import 的 core/Denoise.ets */

// 安全域（sanitize 与 UI 滑杆共用同一套端点，别在 UI 里再写一份）
export const GLOW_THRESHOLD_MIN: number = 0;
export const GLOW_THRESHOLD_MAX: number = 1;
export const GLOW_SOFT_MAX: number = 0.5;
export const GLOW_WIDTH_MAX: number = 0.2;
export const GLOW_GAIN_MAX: number = 4;
export const GLOW_ADD_MAX: number = 1;
export const GLOW_FREQ_MAX: number = 64;

/** 32 位 ARGB 掩码：颜色是 8 位四通道，超出部分是脏数据（JS 位运算截 int32，必须模 2^32） */
const GLOW_ARGB_MOD: number = 0x100000000;

function maskArgb(argb: number): number {
  const v: number = argb % GLOW_ARGB_MOD;
  return v < 0 ? v + GLOW_ARGB_MOD : v;
}

function glowClamp(value: number, lo: number, hi: number, fallback: number): number {
  return Number.isFinite(value) ? Math.min(hi, Math.max(lo, value)) : fallback;
}

export interface GlowParamsArgs {
  enabled?: boolean;
  threshold?: number;
  soft?: number;
  width?: number;
  gain?: number;
  add?: number;
  freq?: number;
  blend?: number;
  colorA?: number;
  colorB?: number;
}

export class GlowParams {
  /** 开关：映射成 ProgramKey 的 glow 一维（换开关 = 换 program，走会话缓存） */
  enabled: boolean;
  /** 0..1 亮度阈值：超过才发光（与 Sapphire S_Glow.Threshold 同名同义） */
  threshold: number;
  /** 0..0.5 阈值软边宽度（smoothstep 的过渡带） */
  soft: number;
  /** 0..0.2 光晕扩散半径，以 **RT 短边** 为单位（各向同性，见 fillGlowBlurOffset） */
  width: number;
  /** 0..4 光晕强度倍数（主 pass） */
  gain: number;
  /** 0..1 额外叠加的光量（过曝感，主 pass） */
  add: number;
  /** 0..64 光晕噪声频率（0 = 干净），主 pass；链只跑一次所以噪声不能进链 */
  freq: number;
  /** 0..1 混合强度比例（0 = 原图，1 = 满强度） */
  blend: number;
  /** 色带 A 端（ARGB number）；hue 的位置参数取归一化亮度，不取红通道 */
  colorA: number;
  /** 色带 B 端（ARGB number） */
  colorB: number;

  constructor(args: GlowParamsArgs = {}) {
    this.enabled = args.enabled ?? false;
    this.threshold = args.threshold ?? 0.5;
    this.soft = args.soft ?? 0.05;
    this.width = args.width ?? 0.02;
    this.gain = args.gain ?? 1.0;
    this.add = args.add ?? 0.0;
    this.freq = args.freq ?? 0.0;
    this.blend = args.blend ?? 0.6;
    this.colorA = maskArgb(args.colorA ?? 0xFFFF3399);
    this.colorB = maskArgb(args.colorB ?? 0xFF33E6FF);
  }

  /** 域钳之后的同一份参数（幂等）。落盘/下发前必须过这一关，见文件头注 */
  sanitize(): GlowParams {
    return new GlowParams({
      enabled: this.enabled,
      threshold: glowClamp(this.threshold, GLOW_THRESHOLD_MIN, GLOW_THRESHOLD_MAX, 0.5),
      soft: glowClamp(this.soft, 0, GLOW_SOFT_MAX, 0.05),
      width: glowClamp(this.width, 0, GLOW_WIDTH_MAX, 0.02),
      gain: glowClamp(this.gain, 0, GLOW_GAIN_MAX, 1),
      add: glowClamp(this.add, 0, GLOW_ADD_MAX, 0),
      freq: glowClamp(this.freq, 0, GLOW_FREQ_MAX, 0),
      blend: glowClamp(this.blend, 0, 1, 0.6),
      colorA: maskArgb(this.colorA),
      colorB: maskArgb(this.colorB)
    });
  }

  /** 字段级相等（测试与快照对账用；ArkTS 无 data class 相等） */
  sameAs(o: GlowParams): boolean {
    return this.enabled === o.enabled && this.threshold === o.threshold && this.soft === o.soft &&
      this.width === o.width && this.gain === o.gain && this.add === o.add &&
      this.freq === o.freq && this.blend === o.blend && this.colorA === o.colorA &&
      this.colorB === o.colorB;
  }
}

/** 出厂预设 A：品红 → 青（用户拍板，见 24 §六-1） */
export const GLOW_PRESET_A: GlowParams = new GlowParams();

/** 预设 B：橙 → 绿（同一条色带公式，只换两端色与强度） */
export const GLOW_PRESET_B: GlowParams = new GlowParams({
  threshold: 0.45,
  blend: 0.55,
  colorA: 0xFFFF730D,
  colorB: 0xFF33D940
});

/** 出厂默认 = 预设 A */
export const GLOW_DEFAULT: GlowParams = GLOW_PRESET_A;

/** Glow 参数 → 单串持久化键（B7 用；与 hud_layout 的复合键同族）。写盘前先 sanitize */
export function encodeGlow(params: GlowParams): string {
  const p: GlowParams = params.sanitize();
  return 'enabled=' + (p.enabled ? 1 : 0) +
    ';threshold=' + p.threshold +
    ';soft=' + p.soft +
    ';width=' + p.width +
    ';gain=' + p.gain +
    ';add=' + p.add +
    ';freq=' + p.freq +
    ';blend=' + p.blend +
    ';colorA=' + p.colorA.toString(16).toUpperCase() +
    ';colorB=' + p.colorB.toString(16).toUpperCase();
}

/** 严格浮点解析（对齐 Kotlin toFloatOrNull：整串合法才算数，"9x"/"abc" 都不收） */
const FLOAT_RE: RegExp = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;

function strictFloat(raw: string): number | undefined {
  return FLOAT_RE.test(raw) && raw.trim() !== '' ? parseFloat(raw) : undefined;
}

function strictHexColor(raw: string): number | undefined {
  return /^[0-9a-fA-F]+$/.test(raw) ? maskArgb(parseInt(raw, 16)) : undefined;
}

/**
 * 单串持久化键 → GlowParams。**任何一处不认识/解析失败都整串回 GLOW_DEFAULT**：
 * 光晕是「能改画面」的参数，坏值宁可回出厂，也不许猜一个近似值。
 * 未知键忽略（前向兼容）；缺键、非数、颜色不是十六进制都算坏串。
 */
export function decodeGlow(raw: string | undefined | null): GlowParams {
  if (raw === undefined || raw === null || raw.trim() === '') {
    return GLOW_DEFAULT;
  }
  const values: Map<string, string> = new Map<string, string>();
  for (const pair of raw.split(';')) {
    const eq: number = pair.indexOf('=');
    if (eq <= 0) {
      continue;
    }
    values.set(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
  }
  const enabledRaw: string | undefined = values.get('enabled');
  let enabled: boolean;
  if (enabledRaw === '1') {
    enabled = true;
  } else if (enabledRaw === '0') {
    enabled = false;
  } else {
    return GLOW_DEFAULT;
  }
  const threshold: number | undefined = strictFloat(values.get('threshold') ?? '');
  const soft: number | undefined = strictFloat(values.get('soft') ?? '');
  const width: number | undefined = strictFloat(values.get('width') ?? '');
  const gain: number | undefined = strictFloat(values.get('gain') ?? '');
  const add: number | undefined = strictFloat(values.get('add') ?? '');
  const freq: number | undefined = strictFloat(values.get('freq') ?? '');
  const blend: number | undefined = strictFloat(values.get('blend') ?? '');
  const colorA: number | undefined = strictHexColor(values.get('colorA') ?? '');
  const colorB: number | undefined = strictHexColor(values.get('colorB') ?? '');
  if (threshold === undefined || soft === undefined || width === undefined || gain === undefined ||
    add === undefined || freq === undefined || blend === undefined || colorA === undefined ||
    colorB === undefined) {
    return GLOW_DEFAULT;
  }
  return new GlowParams({
    enabled: enabled,
    threshold: threshold,
    soft: soft,
    width: width,
    gain: gain,
    add: add,
    freq: freq,
    blend: blend,
    colorA: colorA,
    colorB: colorB
  }).sanitize();
}

/**
 * Glow 离屏链的分档：`像素 × 帧率` 越大档越粗（1 = 原尺寸链 / 2 = 半尺寸 / 4 = 四分之一）。
 * 与 denoiseKernelLite 同一套判据与同一个魔数（DENOISE_FULL_KERNEL_PIXEL_RATE = 150M），
 * 不造第二个阈值。按**相机帧**（= 录制档）判，不按上屏档判（成片那一路才是负载主体）。
 * 信息不全（尺寸/帧率未知）⇒ 1（最高质量档）：此时链本来就不会跑，别先猜一个粗档让首帧闪一下。
 */
export function glowScaleFor(widthPx: number, heightPx: number, fps: number): number {
  if (widthPx <= 0 || heightPx <= 0 || fps <= 0) {
    return 1;
  }
  const rate: number = widthPx * heightPx * fps;
  if (rate <= GLOW_FULL_MAX_PIXEL_RATE) {
    return 1;
  }
  if (rate <= DENOISE_FULL_KERNEL_PIXEL_RATE) {
    return 2;
  }
  return 4;
}

/** 单边按档缩：四舍五入、最小 1（与 glowRtSizeInto/glowRawLongestEdge 同源，别在别处再写一份） */
function glowScaledEdge(px: number, scale: number): number {
  return Math.max(1, Math.round(px / scale));
}

/**
 * 相机帧尺寸 + 档 → 离屏 RT 尺寸，写进 out[0]=宽 out[1]=高（热点路径，不返回数组）。
 *
 * 三条规则：
 * 1. 尺寸/档/maxTexture 非法（≤0）⇒ 返回 false = **不建链**（绝不硬给 1×1 去蒙）；
 * 2. 否则按 scale 等比缩小、四舍五入，两边最小 1（0 边长的 RT 必然 FBO 不完整）；
 * 3. 最长边超过 maxTexture（= 运行时查到的 GL_MAX_TEXTURE_SIZE）⇒ **按最长边等比夹取**。
 *    **不许逐维独立夹**：两维各夹一次会破坏宽高比，光晕在屏幕上变成椭圆——真机上
 *    「横向看是扁的、竖向看是长的」那类缺陷。
 *
 * 返回值只是「尺寸可用与否」；**「到底有没有被夹」必须用夹取前的 glowRawLongestEdge 判**。
 */
export function glowRtSizeInto(out: Array<number>, widthPx: number, heightPx: number,
  scale: number, maxTexture: number): boolean {
  if (out.length < 2) {
    throw new Error('out 至少两个 int，实际 ' + out.length);
  }
  if (widthPx <= 0 || heightPx <= 0 || scale <= 0 || maxTexture <= 0) {
    return false;
  }
  let w: number = glowScaledEdge(widthPx, scale);
  let h: number = glowScaledEdge(heightPx, scale);
  const longest: number = Math.max(w, h);
  if (longest > maxTexture) {
    const k: number = maxTexture / longest;
    w = Math.max(1, Math.round(w * k));
    h = Math.max(1, Math.round(h * k));
  }
  out[0] = w;
  out[1] = h;
  return true;
}

/**
 * **夹取前**的最长边（= glowRtSizeInto 除以 scale、四舍五入、最小 1 之后的最长边）。
 * 它是「这次到底有没有被 GL_MAX_TEXTURE_SIZE 夹」的唯一判据：夹取后最长边恒等于上限，
 * 拿夹取后尺寸反推会把「本来就正好等于上限」误报成夹取。非法输入（尺寸/档 ≤0）返回 0。
 */
export function glowRawLongestEdge(widthPx: number, heightPx: number, scale: number): number {
  if (widthPx <= 0 || heightPx <= 0 || scale <= 0) {
    return 0;
  }
  return Math.max(glowScaledEdge(widthPx, scale), glowScaledEdge(heightPx, scale));
}

/**
 * 可分离高斯某一趟的取样位移（写入调用方复用的数组 ⇒ 每帧零分配）。
 * 口径（24 §1.2）：width 以 **RT 短边** 为单位，长边那一维按比例缩小 ⇒
 * offset = width * vec2(min(1, rtH/rtW), min(1, rtW/rtH))，横/纵趟各取一个分量。
 * 于是「横向 20px、纵向 20px」恒成立（各向同性，圆光晕），换档/换朝向都不改观感比例。
 * 尺寸非法（≤0）或参数非有限/为负 ⇒ 不写数组并返回 false：调用方据此跳过本趟。
 */
export function fillGlowBlurOffset(out: Array<number>, rtWidthPx: number, rtHeightPx: number,
  widthParam: number, horizontal: boolean): boolean {
  if (out.length < 2 || rtWidthPx <= 0 || rtHeightPx <= 0) {
    return false;
  }
  if (!Number.isFinite(widthParam) || widthParam < 0) {
    return false;
  }
  const sx: number = Math.min(1, rtHeightPx / rtWidthPx);
  const sy: number = Math.min(1, rtWidthPx / rtHeightPx);
  if (horizontal) {
    out[0] = widthParam * sx;
    out[1] = 0;
  } else {
    out[0] = 0;
    out[1] = widthParam * sy;
  }
  return true;
}

/**
 * ARGB → 0..1 三元组（uniform 用）。只取 RGB：alpha 不参与光晕（输出恒不透明，
 * 与 PASS_THROUGH_FS 同一条「强制不透明」纪律）。
 */
export function glowRgb(argb: number): Array<number> {
  const v: number = maskArgb(argb);
  return [
    ((v >>> 16) & 0xFF) / 255,
    ((v >>> 8) & 0xFF) / 255,
    (v & 0xFF) / 255
  ];
}

/**
 * Glow 色带的**可选色点**（UI 两行色点用；参数本身仍只存 ARGB 的 colorA/B）。
 * 登记偏离：上游 label 是 R.string 资源 id，本侧为字面量（随 UI 批进资源）。
 * 调色板只约束「点选得到什么」，**不约束持久化域**——手改 prefs 写进任意 32 位色值也照单
 * 全收（解码过 sanitize 的掩码），此时 UI 上没有任何色点被点亮（of 返回 undefined）。
 */
export class GlowColor {
  readonly label: string;
  readonly argb: number;

  constructor(label: string, argb: number) {
    this.label = label;
    this.argb = argb;
  }
}

export const GLOW_COLORS: Array<GlowColor> = [
  new GlowColor('品红', 0xFFFF3399),
  new GlowColor('青', 0xFF33E6FF),
  new GlowColor('橙', 0xFFFF730D),
  new GlowColor('绿', 0xFF33D940),
  new GlowColor('黄', 0xFFFFE033),
  new GlowColor('蓝', 0xFF4D7CFF),
  new GlowColor('紫', 0xFFB44DFF),
  new GlowColor('白', 0xFFFFFFFF)
];

/** 参数里的色值 → 调色板项（没有精确匹配时 undefined = 这行一个点都不点亮，不猜） */
export function glowColorOf(argb: number): GlowColor | undefined {
  const masked: number = maskArgb(argb);
  return GLOW_COLORS.find((c: GlowColor): boolean => c.argb === masked);
}

/** Glow 胶囊状态行的文案键（上游返回 R.string 资源 id，本侧 core 不引资源 → 键，UI 批映射文案） */
export type GlowNoteKey =
  | 'cam_glow_direct'
  | 'cam_glow_broken'
  | 'cam_glow_clamped'
  | 'cam_glow_status_res_label'
  | 'cam_glow_status_auto';

/**
 * Glow 的运行期状态快照（native 链填、胶囊读数用）。纯数据，无 GL 依赖。
 */
export class GlowStatus {
  /** 链这一帧是否产出了可采样的 glow 纹理（false 时主 pass 绑 1×1 黑纹理，贡献为 0） */
  available: boolean;
  /** 用户开关（GlowParams.enabled） */
  enabled: boolean;
  /** 链连续报错/链接失败已停用（胶囊该显示「Glow 已停用」） */
  broken: boolean;
  /** 本帧分档（glowScaleFor） */
  scale: number;
  /** 判档用的输入尺寸（= 相机帧 = 录制档） */
  inputWidthPx: number;
  inputHeightPx: number;
  /** 判档用的帧率 */
  fps: number;
  /** 链 RT 实际尺寸（0 = 还没建） */
  rtWidthPx: number;
  rtHeightPx: number;
  /** 最长边是否被 GL_MAX_TEXTURE_SIZE 夹取（真机排障要看得见） */
  clamped: boolean;

  constructor(available: boolean, enabled: boolean, broken: boolean, scale: number,
    inputWidthPx: number, inputHeightPx: number, fps: number,
    rtWidthPx: number, rtHeightPx: number, clamped: boolean) {
    this.available = available;
    this.enabled = enabled;
    this.broken = broken;
    this.scale = scale;
    this.inputWidthPx = inputWidthPx;
    this.inputHeightPx = inputHeightPx;
    this.fps = fps;
    this.rtWidthPx = rtWidthPx;
    this.rtHeightPx = rtHeightPx;
    this.clamped = clamped;
  }
}

/**
 * Glow 胶囊状态行该显示哪一句（纯函数，全表测）：返回文案键，undefined = 这帧什么都不显示。
 *
 * 顺序是**语义优先级**，不是随意的：
 * 1. **DIRECT 先说清楚**：没有 GL 管线，后面所有状态都无意义（「需切到使用 GPU 才生效」）；
 * 2. **关着 ⇒ 什么都不显示**（含停用/夹取）：效果没开时这些残留状态都是噪声；
 * 3. 停用 ⇒ 「Glow 已停用」（链坏了，开了也没有光晕——必须让用户知道）；
 * 4. 被机型上限夹过**且 RT 真的建了** ⇒ 「已夹到 N×M」（rtW/rtH 为 0 时不能报一个假尺寸）；
 * 5. 否则报分档：已知输入尺寸报带分辨率的句式，未知报「自动」句式。
 */
export function glowStatusNote(status: GlowStatus | undefined, enabled: boolean,
  gpuMode: boolean): GlowNoteKey | undefined {
  if (!gpuMode) {
    return 'cam_glow_direct';
  }
  if (!enabled) {
    return undefined;
  }
  if (status === undefined) {
    return undefined;
  }
  if (status.broken) {
    return 'cam_glow_broken';
  }
  if (status.clamped && status.rtWidthPx > 0 && status.rtHeightPx > 0) {
    return 'cam_glow_clamped';
  }
  if (status.inputWidthPx > 0 && status.inputHeightPx > 0) {
    return 'cam_glow_status_res_label';
  }
  return 'cam_glow_status_auto';
}
