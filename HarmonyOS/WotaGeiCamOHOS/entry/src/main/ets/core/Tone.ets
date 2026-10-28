/**
 * 色调（Tone）参数模型与 LUT 烘焙纯函数——**替代安卓 tone 面板的烘焙段**
 * （安卓把色调烘进曲线同一张查找表，预览与成片同步变化）。
 *
 * # 对拍来源
 * 主仓 Android `core/ToneParams.kt`（66f88e0，v0.0.11 交付截图 r03_tone_*）。
 * 本 worktree 的 `Android/` 快照停在 e7b7bf2，尚未含该文件；域与公式按主仓源码逐项对拍。
 * 本批接口收四项（黑点/白点/阴影/高光），高光与阴影对偶。
 *
 * # 域与默认值（= 安卓）
 * - black 0..0.35（产品滑杆域；sanitize 与安卓一样收进 ±0.35）
 * - white 0..0.35（同上）
 * - shadows -1..1
 * - highlights -1..1（与阴影对偶：正=提亮高光，负=压暗高光）
 * - 默认全 0 = 恒等
 *
 * 黑白点「互相钳制」：电平语义下有效黑点 = black、有效白点 = 1 - white。
 * 两端上限（0.35）保证 有效黑点 ≤ 0.35 < 0.65 ≤ 有效白点，归一化分母
 * `1 - black - white` 恒 ≥ 0.3，不会除零；越界/反序输入（如 white 电平把有效白点
 * 压到有效黑点之下）先被 sanitize 钳回产品域。
 *
 * # 传递函数（逐项对拍 Android ToneParams.apply）
 * 1. 电平：`y = (x - black) / (1 - black - white)`，clamp 0..1；
 * 2. 阴影：`y += 0.25 · shadows · (1-y)²`；
 * 3. 高光：`y += 0.25 · highlights · y²`（与阴影对偶，作用在阴影之后的 y 上）；
 * 4. 再 clamp 0..1。
 * k = 0.25 保证域内 256 点采样非降（最陡阴影+反向高光组合下导数下界 0.5）。
 *
 * # LUT 口径
 * `applyToneToLut` 入出同长：768 字节交错 RGB（256×3，out[3i]=R[i]、out[3i+1]=G[i]、
 * out[3i+2]=B[i]）。逐通道套同一条传递函数；量化 `round(y·255)` 等价安卓
 * `(y*255+0.5).toInt()`。**恒等参数提前返回逐字节副本**（守卫钉）。
 * 安卓烘焙顺序是 `curve(tone(x))`（先 tone 后曲线）；本批宿主（Camera.applyCurve）
 * 按「曲线烘焙 → 色调」后套本函数，得到 `tone(curve(x))`。两种都是单调映射，但强
 * S 曲线下观感不同——顺序差异登记为真机批校色项；若要严格对齐安卓，把 tone 前置到
 * `CurveStack.bakeBytes` 的采样之前（本函数仍可作 tone-only 段使用）。
 *
 * 纯逻辑：零 @ohos/@kit import，JVM 等价的 Node 纯测可全跑。
 */

/** 黑点电平上限（= 安卓 ToneParams.BLACK_MAX） */
export const TONE_BLACK_MAX: number = 0.35;
/** 白点电平上限（= 安卓 ToneParams.WHITE_MAX） */
export const TONE_WHITE_MAX: number = 0.35;
/** 黑点/白点滑杆的产品下限（面板 0..上限；sanitize 仍按安卓收 ±上限） */
export const TONE_LEVEL_MIN: number = 0;
/** 阴影幅度上限（负=压暗，正=提亮） */
export const TONE_SHADOWS_MAX: number = 1;
/** 阴影项增益（= 安卓 ToneParams.SHADOW_GAIN） */
export const TONE_SHADOW_GAIN: number = 0.25;
/** 高光幅度上限（负=压暗，正=提亮） */
export const TONE_HIGHLIGHTS_MAX: number = 1;
/** 高光项增益（= 安卓 ToneParams.HIGHLIGHT_GAIN） */
export const TONE_HIGHLIGHT_GAIN: number = 0.25;

/**
 * 域钳：NaN 回 0，±Infinity 收到端点，其余收进 [-max, max]。
 * 对齐安卓 `sanitizeRange`（`if (v.isNaN()) 0f else v.coerceIn(-max, max)`）。
 * -0 归一成 0，保证 encode 串与恒等判据稳定。
 */
function toneClampLevel(value: number, max: number): number {
  if (Number.isNaN(value)) {
    return 0;
  }
  if (!Number.isFinite(value)) {
    return value > 0 ? max : -max;
  }
  const v: number = Math.min(max, Math.max(-max, value));
  return v === 0 ? 0 : v;
}

/**
 * 一维传递函数（输入/输出都在 0..1）。内部再钳一遍域，调用方不必先 sanitize；
 * 坏输入（NaN）按黑处理成 0，Infinity 按端点。
 */
function toneTransfer(x: number, black: number, white: number, shadows: number,
  highlights: number): number {
  const b: number = toneClampLevel(black, TONE_BLACK_MAX);
  const w: number = toneClampLevel(white, TONE_WHITE_MAX);
  const s: number = toneClampLevel(shadows, TONE_SHADOWS_MAX);
  const h: number = toneClampLevel(highlights, TONE_HIGHLIGHTS_MAX);
  const xx: number = Number.isNaN(x) ? 0 : Math.min(1, Math.max(0, x));
  const span: number = 1 - b - w;
  // span 在产品域恒 ≥ 0.3；万一外部绕过 sanitize 造出反序黑白点，退回直通而不是除零
  let y: number = span > 0 ? (xx - b) / span : xx;
  y = y < 0 ? 0 : (y > 1 ? 1 : y);
  if (s !== 0) {
    const lift: number = 1 - y;
    y += TONE_SHADOW_GAIN * s * lift * lift;
  }
  // 与安卓一致：高光作用在阴影之后、末次钳制之前的 y 上（中间不钳）
  if (h !== 0) {
    y += TONE_HIGHLIGHT_GAIN * h * y * y;
  }
  return y < 0 ? 0 : (y > 1 ? 1 : y);
}

export interface ToneParamsArgs {
  black?: number;
  white?: number;
  shadows?: number;
  highlights?: number;
}

/**
 * 色调四参数。字段语义（与安卓同名同义）：
 * - black：黑点电平（0 = 不抬，越大越多暗部被剪到 0）；
 * - white：白点压白量（0 = 不压，越大越多亮部被钳到 1）；
 * - shadows：阴影提/压（负=压暗，正=提亮）；
 * - highlights：高光提/压（负=压暗，正=提亮；与阴影对偶）。
 */
export class ToneParams {
  black: number;
  white: number;
  shadows: number;
  highlights: number;

  constructor(args: ToneParamsArgs = {}) {
    this.black = args.black ?? 0;
    this.white = args.white ?? 0;
    this.shadows = args.shadows ?? 0;
    this.highlights = args.highlights ?? 0;
  }

  /** 域钳之后的同一份参数（幂等）。落盘/下发前必须过这一关 */
  sanitize(): ToneParams {
    return new ToneParams({
      black: toneClampLevel(this.black, TONE_BLACK_MAX),
      white: toneClampLevel(this.white, TONE_WHITE_MAX),
      shadows: toneClampLevel(this.shadows, TONE_SHADOWS_MAX),
      highlights: toneClampLevel(this.highlights, TONE_HIGHLIGHTS_MAX)
    });
  }

  /** 四项全 0 = 恒等：跳过 LUT 烘焙的唯一合法依据 */
  isIdentity(): boolean {
    return this.black === 0 && this.white === 0 && this.shadows === 0 && this.highlights === 0;
  }

  /** 字段级相等（ArkTS 无 data class 相等；测试与持久化对账用） */
  sameAs(o: ToneParams): boolean {
    return this.black === o.black && this.white === o.white && this.shadows === o.shadows &&
      this.highlights === o.highlights;
  }

  /**
   * 归一化输入 → 归一化输出。先 sanitize：滑杆直写与坏串都不需要调用方再收一遍。
   * 恒等参数返回 x（含端点精确 0/1）。
   */
  apply(x: number): number {
    const p: ToneParams = this.sanitize();
    return toneTransfer(x, p.black, p.white, p.shadows, p.highlights);
  }

  /** 有效黑点（输入域位置，电平语义 = black） */
  blackPoint(): number {
    return this.sanitize().black;
  }

  /** 有效白点（输入域位置；white 是压白量，所以是 1 - white） */
  whitePoint(): number {
    return 1 - this.sanitize().white;
  }
}

/** 出厂默认 = 四项全 0（恒等，与改前逐字节一致） */
export const TONE_DEFAULT: ToneParams = new ToneParams();

/**
 * 单串持久化键：`black=..;white=..;shadows=..;highlights=..`（与 core/Glow.ets 同族）。
 * 写盘前先 sanitize：越界值不许以越界写法落盘。
 */
export function toneEncode(p: ToneParams): string {
  const s: ToneParams = p.sanitize();
  return 'black=' + s.black + ';white=' + s.white + ';shadows=' + s.shadows +
    ';highlights=' + s.highlights;
}

/** 严格浮点解析（对齐 Kotlin toFloatOrNull：整串合法才算数，"9x"/"abc" 都不收） */
const TONE_FLOAT_RE: RegExp = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;

function toneStrictFloat(raw: string): number | undefined {
  return TONE_FLOAT_RE.test(raw) && raw.trim() !== '' ? parseFloat(raw) : undefined;
}

/**
 * 单串持久化键 → ToneParams。**任何一处缺键/非数都整串回 TONE_DEFAULT**：
 * 色调能改画面，坏值宁可回出厂，也不许猜一个近似值。未知键忽略（前向兼容）；
 * 数值越界走 sanitize 收域（与安卓 decode 同口径）。
 *
 * 前向兼容例外：第四轴 `highlights` 是后加的，旧串没有该键——**键缺失时按 0 读入**
 * （旧串语义就是无高光）；键存在但不是数仍是坏串，整串回默认。
 */
export function toneDecode(raw?: string): ToneParams {
  if (raw === undefined || raw === null || raw.trim() === '') {
    return TONE_DEFAULT;
  }
  const values: Map<string, string> = new Map<string, string>();
  for (const pair of raw.split(';')) {
    const eq: number = pair.indexOf('=');
    if (eq <= 0) {
      continue;
    }
    values.set(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
  }
  const black: number | undefined = toneStrictFloat(values.get('black') ?? '');
  const white: number | undefined = toneStrictFloat(values.get('white') ?? '');
  const shadows: number | undefined = toneStrictFloat(values.get('shadows') ?? '');
  const highlightsRaw: string | undefined = values.get('highlights');
  const highlights: number | undefined =
    highlightsRaw === undefined ? 0 : toneStrictFloat(highlightsRaw);
  if (black === undefined || white === undefined || shadows === undefined ||
    highlights === undefined) {
    return TONE_DEFAULT;
  }
  return new ToneParams({
    black: black,
    white: white,
    shadows: shadows,
    highlights: highlights
  }).sanitize();
}

/** 256 档 8bit 输入 → 输出的量化映射表（色调对 R/G/B 同一套传递函数） */
function toneByteMap(p: ToneParams): Uint8Array {
  const map: Uint8Array = new Uint8Array(256);
  for (let i: number = 0; i < 256; i++) {
    map[i] = Math.round(toneTransfer(i / 255, p.black, p.white, p.shadows, p.highlights) * 255);
  }
  return map;
}

/**
 * 对交错 RGB 8bit LUT 逐通道套色调传递函数：入出同长（约定 768 = 256×3）。
 * - 恒等参数：返回入参的逐字节副本（提前返回，绝不因浮点舍入动一个字节）；
 * - 非恒等：预先算 256 档映射，再对每个 texel 的三个分量查表；
 * - 长度不是 3 的倍数时，尾部不足一组的字节原样保留（防御，不改变入出同长）。
 * 不修改入参：调用方拿到的永远是独立数组。
 */
export function applyToneToLut(lut: Uint8Array, p: ToneParams): Uint8Array {
  const out: Uint8Array = new Uint8Array(lut.length);
  out.set(lut);
  const s: ToneParams = p.sanitize();
  if (s.isIdentity()) {
    return out;
  }
  const map: Uint8Array = toneByteMap(s);
  for (let i: number = 0; i + 2 < out.length; i += 3) {
    out[i] = map[lut[i]];
    out[i + 1] = map[lut[i + 1]];
    out[i + 2] = map[lut[i + 2]];
  }
  return out;
}
