/**
 * 录制页参数持久化（直译自上游 ui/ParamsPersist.kt，2026-10-16 口径）：
 * 「只要在录制页改的设置都要做持久化，不随后台清理而回归默认」。
 *
 * 语义 = **键即启动档位**（上次用过就是下次启动值）——与设置页默认档共用同一套键。
 * 本文件是**纯逻辑**（零 `@ohos.*`/`@kit.*` import）：prefs 只经 [ParamsPrefs] 最小注入面，
 * 平台适配在 `ui/ParamsStore.ets`，单测用 map 替身（test/pure/params-persist.test.ets）。
 *
 * 两条链：
 * - **冷启恢复**：[ParamsPersist.restore] 读键 → 坏值回默认/跳过 → 写总线（只写 value，
 *   range/enabled/exact 不动，机型域钳制交给既有 `WotaParams.applyAbility`）；
 * - **落盘**：[ParamsPersistSession] 进录制页冻结一份「影子」（当前总线快照），只在用户动作
 *   完成点 flush，且**只写与影子不同的字段**——用户在设置页刚改的值不会被录制页的陈旧总线
 *   静默覆盖。
 *
 * **排除清单**（照搬上游，不许"补齐"）：
 * - `renderMode`/`sampleRate`：设置页独占——录制页写 renderMode 是「※ 档自动切 GPU」的自动降级，
 *   入档会把降级静默写成用户默认；
 * - `audioEnabled`（音源）：不持久化——开过内录的用户不该每次启动重新申请投屏授权；
 * - `focusPoint`：瞬时点按态，冷启不应钉死对焦框；
 * - `curve`/`arc_convert_mode`：各有专属直写链，避免第二写入方。
 *
 * 键名是**持久化契约**：24 个键逐字等于上游 `WotaSettings.KEY_*` 常量值；`SettingsStore` 的
 * 迁移读/写也复用这里的常量（一个字段只有一个权威键名）。
 */
import {
  AeMode, AfMode, ArcMergeDeadband, Flash, FrameEffect, PeakingColor, Size, Stabilize,
  WotaTiers, WbPreset
} from '../core/Constants';
import { LensType } from '../core/LensSlot';
import { ParamState, WotaParams } from '../core/WotaParams';

// ---------------------------------------------------------------- 键名（与上游 WotaSettings 逐字一致）

export const KEY_DEFAULT_FPS: string = 'default_fps';
export const KEY_DEFAULT_SHUTTER_NS: string = 'default_shutter_ns';
export const KEY_DEFAULT_BITRATE: string = 'default_bitrate';
export const KEY_DEFAULT_REF_LINES: string = 'default_ref_lines';
export const KEY_LEVEL_ENABLED: string = 'level_enabled';
export const KEY_DEFAULT_ISO: string = 'default_iso';
export const KEY_DEFAULT_AE_MODE: string = 'default_ae_mode';
export const KEY_DEFAULT_EV: string = 'default_ev';
export const KEY_DEFAULT_WB_PRESET: string = 'default_wb_preset';
export const KEY_DEFAULT_WB_KELVIN: string = 'default_wb_kelvin';
export const KEY_DEFAULT_WB_TINT: string = 'default_wb_tint';
export const KEY_DEFAULT_ZOOM: string = 'default_zoom';
export const KEY_DEFAULT_FLASH: string = 'default_flash';
export const KEY_DEFAULT_STABILIZE: string = 'default_stabilize';
export const KEY_DEFAULT_AF_MODE: string = 'default_af_mode';
export const KEY_DEFAULT_MF_DIOPTER: string = 'default_mf_diopter';
export const KEY_DEFAULT_SIZE: string = 'default_size';
export const KEY_DEFAULT_FRAME_EFFECT: string = 'default_frame_effect';
export const KEY_DEFAULT_ZEBRA_THRESHOLD: string = 'default_zebra_threshold';
export const KEY_DEFAULT_ZEBRA_DENSITY: string = 'default_zebra_density';
export const KEY_DEFAULT_PEAKING_STRENGTH: string = 'default_peaking_strength';
export const KEY_DEFAULT_PEAKING_COLOR: string = 'default_peaking_color';
export const KEY_DEFAULT_LENS: string = 'default_lens';
export const KEY_DEFAULT_ARC_MERGE_DEADBAND: string = 'default_arc_merge_deadband';

/** 入档字段的键白名单（顺序照上游 `ParamsPersist.KEYS`；单测手写清单双向核对）。 */
export const PARAMS_KEYS: Array<string> = [
  KEY_DEFAULT_FPS,
  KEY_DEFAULT_SHUTTER_NS,
  KEY_DEFAULT_BITRATE,
  KEY_DEFAULT_REF_LINES,
  KEY_LEVEL_ENABLED,
  KEY_DEFAULT_ISO,
  KEY_DEFAULT_AE_MODE,
  KEY_DEFAULT_EV,
  KEY_DEFAULT_WB_PRESET,
  KEY_DEFAULT_WB_KELVIN,
  KEY_DEFAULT_WB_TINT,
  KEY_DEFAULT_ZOOM,
  KEY_DEFAULT_FLASH,
  KEY_DEFAULT_STABILIZE,
  KEY_DEFAULT_AF_MODE,
  KEY_DEFAULT_MF_DIOPTER,
  KEY_DEFAULT_SIZE,
  KEY_DEFAULT_FRAME_EFFECT,
  KEY_DEFAULT_ZEBRA_THRESHOLD,
  KEY_DEFAULT_ZEBRA_DENSITY,
  KEY_DEFAULT_PEAKING_STRENGTH,
  KEY_DEFAULT_PEAKING_COLOR,
  KEY_DEFAULT_LENS,
  KEY_DEFAULT_ARC_MERGE_DEADBAND
];

/**
 * prefs 最小注入面（对齐上游 SharedPreferences 的用到的子集）。
 * getter 一律带默认值语义：缺键/类型不符回默认值；putter 返回「是否写成功」。
 * 写入分两段对齐上游 `commit()` 的批写口径：putter 只进内存，[commit] 才同步落盘——
 * 一次 flush 无论改几个字段都只调一次 commit（滑杆逐帧落盘会变成同步 IO 风暴）。
 * 失败时 session 不推进影子（下次 flush 重试；上游 commit()=false 同义）。
 */
export interface ParamsPrefs {
  getInt(key: string, def: number): number;
  getBool(key: string, def: boolean): boolean;
  getFloat(key: string, def: number): number;
  getString(key: string, def: string): string;
  putInt(key: string, value: number): boolean;
  putBool(key: string, value: boolean): boolean;
  putFloat(key: string, value: number): boolean;
  putString(key: string, value: string): boolean;
  /** 把本批 put 一次性落盘；失败返回 false（session 不推进影子，下次重试） */
  commit(): boolean;
}

// ---------------------------------------------------------------- 枚举编解码（坏值 → 默认）

/** 参与持久化的枚举族（ArkTS 无 Kotlin 的 reified enumValueOf，逐个点名解析）。 */
export enum PrefEnum {
  AE_MODE,
  AF_MODE,
  WB_PRESET,
  FLASH,
  STABILIZE,
  FRAME_EFFECT,
  PEAKING_COLOR,
  LENS
}

/** 枚举值 → 串（与 Kotlin `enum.name` 逐字一致；未知值回默认档名）。 */
export function enumName(kind: PrefEnum, value: number): string {
  switch (kind) {
    case PrefEnum.AE_MODE: return aeName(value);
    case PrefEnum.AF_MODE: return afName(value);
    case PrefEnum.WB_PRESET: return wbName(value);
    case PrefEnum.FLASH: return flashName(value);
    case PrefEnum.STABILIZE: return stabilizeName(value);
    case PrefEnum.FRAME_EFFECT: return frameEffectName(value);
    case PrefEnum.PEAKING_COLOR: return peakingColorName(value);
    case PrefEnum.LENS: return lensName(value);
    default: return '';
  }
}

/**
 * 串 → 枚举值（大小写敏感，与 Kotlin `enumValueOf` 同口径）；坏串/未知串回 [def]。
 * 绝不抛。
 */
export function enumFrom(kind: PrefEnum, raw: string, def: number): number {
  switch (kind) {
    case PrefEnum.AE_MODE: return aeFrom(raw, def);
    case PrefEnum.AF_MODE: return afFrom(raw, def);
    case PrefEnum.WB_PRESET: return wbFrom(raw, def);
    case PrefEnum.FLASH: return flashFrom(raw, def);
    case PrefEnum.STABILIZE: return stabilizeFrom(raw, def);
    case PrefEnum.FRAME_EFFECT: return frameEffectFrom(raw, def);
    case PrefEnum.PEAKING_COLOR: return peakingColorFrom(raw, def);
    case PrefEnum.LENS: return lensFrom(raw, def);
    default: return def;
  }
}

function aeName(v: number): string {
  switch (v) {
    case AeMode.AUTO: return 'AUTO';
    case AeMode.MANUAL: return 'MANUAL';
    case AeMode.LOCK: return 'LOCK';
    default: return 'AUTO';
  }
}

function aeFrom(raw: string, def: number): number {
  if (raw === 'AUTO') {
    return AeMode.AUTO;
  }
  if (raw === 'MANUAL') {
    return AeMode.MANUAL;
  }
  if (raw === 'LOCK') {
    return AeMode.LOCK;
  }
  return def;
}

function afName(v: number): string {
  switch (v) {
    case AfMode.CONTINUOUS_VIDEO: return 'CONTINUOUS_VIDEO';
    case AfMode.CONTINUOUS_PICTURE: return 'CONTINUOUS_PICTURE';
    case AfMode.MACRO: return 'MACRO';
    case AfMode.MANUAL: return 'MANUAL';
    default: return 'CONTINUOUS_VIDEO';
  }
}

function afFrom(raw: string, def: number): number {
  if (raw === 'CONTINUOUS_VIDEO') {
    return AfMode.CONTINUOUS_VIDEO;
  }
  if (raw === 'CONTINUOUS_PICTURE') {
    return AfMode.CONTINUOUS_PICTURE;
  }
  if (raw === 'MACRO') {
    return AfMode.MACRO;
  }
  if (raw === 'MANUAL') {
    return AfMode.MANUAL;
  }
  return def;
}

function wbName(v: number): string {
  switch (v) {
    case WbPreset.MANUAL: return 'MANUAL';
    case WbPreset.AUTO: return 'AUTO';
    case WbPreset.INCANDESCENT: return 'INCANDESCENT';
    case WbPreset.FLUORESCENT: return 'FLUORESCENT';
    case WbPreset.WARM_FLUORESCENT: return 'WARM_FLUORESCENT';
    case WbPreset.DAYLIGHT: return 'DAYLIGHT';
    case WbPreset.CLOUDY_DAYLIGHT: return 'CLOUDY_DAYLIGHT';
    case WbPreset.TWILIGHT: return 'TWILIGHT';
    case WbPreset.SHADE: return 'SHADE';
    default: return 'AUTO';
  }
}

function wbFrom(raw: string, def: number): number {
  if (raw === 'MANUAL') {
    return WbPreset.MANUAL;
  }
  if (raw === 'AUTO') {
    return WbPreset.AUTO;
  }
  if (raw === 'INCANDESCENT') {
    return WbPreset.INCANDESCENT;
  }
  if (raw === 'FLUORESCENT') {
    return WbPreset.FLUORESCENT;
  }
  if (raw === 'WARM_FLUORESCENT') {
    return WbPreset.WARM_FLUORESCENT;
  }
  if (raw === 'DAYLIGHT') {
    return WbPreset.DAYLIGHT;
  }
  if (raw === 'CLOUDY_DAYLIGHT') {
    return WbPreset.CLOUDY_DAYLIGHT;
  }
  if (raw === 'TWILIGHT') {
    return WbPreset.TWILIGHT;
  }
  if (raw === 'SHADE') {
    return WbPreset.SHADE;
  }
  return def;
}

function flashName(v: number): string {
  switch (v) {
    case Flash.OFF: return 'OFF';
    case Flash.ON: return 'ON';
    case Flash.AUTO: return 'AUTO';
    case Flash.TORCH: return 'TORCH';
    default: return 'OFF';
  }
}

function flashFrom(raw: string, def: number): number {
  if (raw === 'OFF') {
    return Flash.OFF;
  }
  if (raw === 'ON') {
    return Flash.ON;
  }
  if (raw === 'AUTO') {
    return Flash.AUTO;
  }
  if (raw === 'TORCH') {
    return Flash.TORCH;
  }
  return def;
}

function stabilizeName(v: number): string {
  switch (v) {
    case Stabilize.OFF: return 'OFF';
    case Stabilize.OIS: return 'OIS';
    case Stabilize.EIS: return 'EIS';
    case Stabilize.OIS_EIS: return 'OIS_EIS';
    default: return 'OFF';
  }
}

function stabilizeFrom(raw: string, def: number): number {
  if (raw === 'OFF') {
    return Stabilize.OFF;
  }
  if (raw === 'OIS') {
    return Stabilize.OIS;
  }
  if (raw === 'EIS') {
    return Stabilize.EIS;
  }
  if (raw === 'OIS_EIS') {
    return Stabilize.OIS_EIS;
  }
  return def;
}

function frameEffectName(v: number): string {
  switch (v) {
    case FrameEffect.NONE: return 'NONE';
    case FrameEffect.ZEBRA: return 'ZEBRA';
    case FrameEffect.PEAKING: return 'PEAKING';
    default: return 'NONE';
  }
}

function frameEffectFrom(raw: string, def: number): number {
  if (raw === 'NONE') {
    return FrameEffect.NONE;
  }
  if (raw === 'ZEBRA') {
    return FrameEffect.ZEBRA;
  }
  if (raw === 'PEAKING') {
    return FrameEffect.PEAKING;
  }
  return def;
}

function peakingColorName(v: number): string {
  switch (v) {
    case PeakingColor.WHITE: return 'WHITE';
    case PeakingColor.GREEN: return 'GREEN';
    case PeakingColor.BLUE: return 'BLUE';
    case PeakingColor.ORANGE: return 'ORANGE';
    case PeakingColor.RED: return 'RED';
    default: return 'RED';
  }
}

function peakingColorFrom(raw: string, def: number): number {
  if (raw === 'WHITE') {
    return PeakingColor.WHITE;
  }
  if (raw === 'GREEN') {
    return PeakingColor.GREEN;
  }
  if (raw === 'BLUE') {
    return PeakingColor.BLUE;
  }
  if (raw === 'ORANGE') {
    return PeakingColor.ORANGE;
  }
  if (raw === 'RED') {
    return PeakingColor.RED;
  }
  return def;
}

function lensName(v: number): string {
  switch (v) {
    case LensType.SUPER_WIDE: return 'SUPER_WIDE';
    case LensType.WIDE: return 'WIDE';
    case LensType.TELEPHOTO: return 'TELEPHOTO';
    case LensType.SUPER_TELEPHOTO: return 'SUPER_TELEPHOTO';
    case LensType.AUTO: return 'AUTO';
    case LensType.FRONT: return 'FRONT';
    default: return 'WIDE';
  }
}

function lensFrom(raw: string, def: number): number {
  if (raw === 'SUPER_WIDE') {
    return LensType.SUPER_WIDE;
  }
  if (raw === 'WIDE') {
    return LensType.WIDE;
  }
  if (raw === 'TELEPHOTO') {
    return LensType.TELEPHOTO;
  }
  if (raw === 'SUPER_TELEPHOTO') {
    return LensType.SUPER_TELEPHOTO;
  }
  if (raw === 'AUTO') {
    return LensType.AUTO;
  }
  if (raw === 'FRONT') {
    return LensType.FRONT;
  }
  return def;
}

// ---------------------------------------------------------------- 尺寸串编解码

/**
 * 尺寸串 `"WxH"`：两段都必须是非空纯数字且 >0 才认；坏串返回 undefined
 * （调用方跳过、保持出厂默认）。与 Kotlin `toIntOrNull` 一样拒绝小数/符号。
 */
export function parseSize(raw: string): Size | undefined {
  const at: number = raw.indexOf('x');
  if (at <= 0) {
    return undefined;
  }
  if (raw.indexOf('x', at + 1) >= 0) {
    return undefined;
  }
  const w: number = parseDim(raw.substring(0, at));
  const h: number = parseDim(raw.substring(at + 1));
  if (w <= 0 || h <= 0) {
    return undefined;
  }
  return new Size(w, h);
}

/** 尺寸串编码（flush 侧调用）；与 [parseSize] 一一对应 */
export function formatSize(size: Size): string {
  return `${size.width}x${size.height}`;
}

function parseDim(raw: string): number {
  if (raw.length === 0) {
    return -1;
  }
  for (let i = 0; i < raw.length; i++) {
    const c: number = raw.charCodeAt(i);
    if (c < 48 || c > 57) {
      return -1;
    }
  }
  return Number(raw);
}

// ---------------------------------------------------------------- 迁移读（键名迁移的新键优先）

/**
 * 键名迁移的**纯判定**：新键在册且值可用就用新键，否则回退旧键（`SettingsStore` 的
 * `fps→default_fps` / `bitrate→default_bitrate` / `sizeW/sizeH→default_size` 三条）。
 * 平台件负责先读成 `undefined | 原始值`（hasSync 判在册），判定本身留在这里供单测。
 * 旧键不删（无害），下一次 `SettingsStore.save` 起只写新键。
 */
export class ParamsMigrate {
  static int(newValue: number | undefined, legacyValue: number | undefined, def: number): number {
    if (newValue !== undefined && Number.isFinite(newValue)) {
      return Math.trunc(newValue);
    }
    if (legacyValue !== undefined && Number.isFinite(legacyValue)) {
      return Math.trunc(legacyValue);
    }
    return def;
  }

  static size(newRaw: string | undefined, legacyW: number | undefined, legacyH: number | undefined,
    defW: number, defH: number): Size {
    if (newRaw !== undefined) {
      const parsed: Size | undefined = parseSize(newRaw);
      if (parsed !== undefined) {
        return parsed;
      }
    }
    if (legacyW !== undefined && legacyH !== undefined) {
      return new Size(Math.trunc(legacyW), Math.trunc(legacyH));
    }
    return new Size(defW, defH);
  }
}

// ---------------------------------------------------------------- 冷启恢复

export class ParamsPersist {
  /**
   * 冷启恢复：读键 → 坏值回默认/跳过 → 写总线（只写 value，不碰状态位）。
   * 与上游同风格；绝不抛——手改 prefs 的脏数据最多让它落到出厂默认或产品域边界。
   * 机型域钳制不在本函数：起镜后 `applyAbility` 仍按能力收口。
   */
  static restore(prefs: ParamsPrefs, params: WotaParams): void {
    // ① 帧率：产品档位区间钳（与上游 applyDefaults 同一口径）
    const fps: number = clampInt(prefs.getInt(KEY_DEFAULT_FPS, 25),
      WotaTiers.FPS[0], WotaTiers.FPS[WotaTiers.FPS.length - 1]);
    params.fps.value = withValue(params.fps.value, fps);
    // ② 快门（ns）：>0 才套；坏值跳过 = 维持出厂默认（最终设备域由 applyAbility 收口）
    const shutterNs: number = prefs.getInt(KEY_DEFAULT_SHUTTER_NS, 40_000_000);
    if (shutterNs > 0) {
      params.shutter.value = withValue(params.shutter.value, shutterNs);
    }
    // ③ 码率（档位域由档表管，坏值原样交给 UI/引擎；不做钳）
    params.bitrate.value = prefs.getInt(KEY_DEFAULT_BITRATE, 10_000_000);
    // ④ 参考线掩码（多选位掩码，无越界概念）
    params.refLines.value = prefs.getInt(KEY_DEFAULT_REF_LINES, 0);
    // ⑤ 水平仪开关
    params.levelEnabled.value = prefs.getBool(KEY_LEVEL_ENABLED, true);
    // ⑥ ISO：>0 才套
    const iso: number = prefs.getInt(KEY_DEFAULT_ISO, 100);
    if (iso > 0) {
      params.iso.value = withValue(params.iso.value, iso);
    }
    // ⑦ AE 模式：AUTO/MANUAL；LOCK 与坏串一律归 AUTO（锁是瞬时态，不跨进程）
    const aeRaw: number = enumFrom(PrefEnum.AE_MODE,
      prefs.getString(KEY_DEFAULT_AE_MODE, 'AUTO'), AeMode.AUTO);
    params.aeMode.value = ParamsPersist.persistedAe(aeRaw as AeMode);
    // ⑧ EV（设备域由 applyEv 钳制）
    params.ev.value = withValue(params.ev.value, prefs.getInt(KEY_DEFAULT_EV, 0));
    // ⑨ 白平衡三件套（awbModes 闸门由 applyWhiteBalance 管；色温/色调是产品滑杆域）
    params.wbMode.value = enumFrom(PrefEnum.WB_PRESET,
      prefs.getString(KEY_DEFAULT_WB_PRESET, 'AUTO'), WbPreset.AUTO) as WbPreset;
    params.kelvin.value = withValue(params.kelvin.value, prefs.getInt(KEY_DEFAULT_WB_KELVIN, 5500));
    params.tint.value = withValue(params.tint.value, prefs.getInt(KEY_DEFAULT_WB_TINT, 0));
    // ⑩ 变焦：>0 才套
    const zoom: number = prefs.getFloat(KEY_DEFAULT_ZOOM, 1);
    if (zoom > 0) {
      params.zoom.value = withValue(params.zoom.value, zoom);
    }
    // ⑪ 闪光/防抖（能力闸门在 applyModeGates）
    params.flash.value = enumFrom(PrefEnum.FLASH,
      prefs.getString(KEY_DEFAULT_FLASH, 'OFF'), Flash.OFF) as Flash;
    params.stabilize.value = enumFrom(PrefEnum.STABILIZE,
      prefs.getString(KEY_DEFAULT_STABILIZE, 'OFF'), Stabilize.OFF) as Stabilize;
    // ⑫ AF 模式与屈光度（能否保留 MANUAL 由 applyFocus 按能力裁决）
    params.afMode.value = enumFrom(PrefEnum.AF_MODE,
      prefs.getString(KEY_DEFAULT_AF_MODE, 'CONTINUOUS_VIDEO'), AfMode.CONTINUOUS_VIDEO) as AfMode;
    const diopter: number = prefs.getFloat(KEY_DEFAULT_MF_DIOPTER, 0);
    params.manualFocusDiopter.value =
      withValue(params.manualFocusDiopter.value, diopter < 0 ? 0 : diopter);
    // ⑬ 分辨率：存的是**意图值**（requestedSize）；坏串跳过（保持出厂默认）。
    //    生效尺寸由 applySize 按新镜头能力重落档，这里不写死第二份逻辑。
    const size: Size | undefined = parseSize(prefs.getString(KEY_DEFAULT_SIZE, ''));
    if (size !== undefined) {
      params.requestSize(size);
    }
    // ⑭ 监看一族（产品域 sanity，不随机型）
    params.frameEffect.value = enumFrom(PrefEnum.FRAME_EFFECT,
      prefs.getString(KEY_DEFAULT_FRAME_EFFECT, 'NONE'), FrameEffect.NONE) as FrameEffect;
    params.zebraThreshold.value = clampFloat(prefs.getFloat(KEY_DEFAULT_ZEBRA_THRESHOLD, 0.8), 0.6, 1);
    params.zebraDensity.value = clampInt(prefs.getInt(KEY_DEFAULT_ZEBRA_DENSITY, 2), 1, 3);
    params.peakingStrength.value = clampFloat(prefs.getFloat(KEY_DEFAULT_PEAKING_STRENGTH, 0.6), 0, 1);
    params.peakingColor.value = enumFrom(PrefEnum.PEAKING_COLOR,
      prefs.getString(KEY_DEFAULT_PEAKING_COLOR, 'RED'), PeakingColor.RED) as PeakingColor;
    // ⑮ 镜头：按 LensType 名落档；找不到该档时起镜侧选档兜底退 WIDE
    params.lens.value = enumFrom(PrefEnum.LENS,
      prefs.getString(KEY_DEFAULT_LENS, 'WIDE'), LensType.WIDE) as LensType;
    // ⑯ 补弧差分死区：走总线 setter（同步发布给 GL/离线修复的只读快照），坏值/越界钳回 0..32
    params.setArcMergeDeadband(prefs.getInt(KEY_DEFAULT_ARC_MERGE_DEADBAND, ArcMergeDeadband.DEFAULT));
  }

  /** LOCK 归一：写 `default_ae_mode` 时 LOCK 一律存/恢复为 AUTO（锁是瞬时态）。 */
  static persistedAe(mode: AeMode): AeMode {
    return mode === AeMode.LOCK ? AeMode.AUTO : mode;
  }
}

// ---------------------------------------------------------------- 落盘会话

/**
 * 持久化会话（会话影子 + 用户动作完成点 flush）。
 *
 * - 构造时冻结**影子** = 当前总线值（进录制页重建；离开即销毁）；
 * - [flush] 只把「与影子不同」的字段一次批量写出；无差异不触碰 prefs（返回 true）；
 * - 写失败不推进影子（返回 false），下次 flush 重试——因此重复 flush 幂等；
 * - 影子 diff 同时封死「静默覆盖设置页」：用户在设置页改了某个键、而录制页总线还停在旧值，
 *   此后任何 flush 都不会把那个陈旧值写回。
 */
export class ParamsPersistSession {
  private readonly prefs: ParamsPrefs;
  private readonly params: WotaParams;

  // ---- 影子（构造/resync 时冻结；flush 成功后才更新）----
  private shFps: number = 0;
  private shShutter: number = 0;
  private shBitrate: number = 0;
  private shRefLines: number = 0;
  private shLevel: boolean = false;
  private shIso: number = 0;
  private shAe: AeMode = AeMode.AUTO;
  private shEv: number = 0;
  private shWb: WbPreset = WbPreset.AUTO;
  private shKelvin: number = 0;
  private shTint: number = 0;
  private shZoom: number = 0;
  private shFlash: Flash = Flash.OFF;
  private shStabilize: Stabilize = Stabilize.OFF;
  private shAf: AfMode = AfMode.CONTINUOUS_VIDEO;
  private shDiopter: number = 0;
  private shSizeW: number = 0;
  private shSizeH: number = 0;
  private shEffect: FrameEffect = FrameEffect.NONE;
  private shZebraT: number = 0;
  private shZebraD: number = 0;
  private shPeakS: number = 0;
  private shPeakC: PeakingColor = PeakingColor.RED;
  private shLens: LensType = LensType.WIDE;
  private shArcDeadband: number = 0;

  constructor(prefs: ParamsPrefs, params: WotaParams) {
    this.prefs = prefs;
    this.params = params;
    this.resync();
  }

  /**
   * 重新冻结影子。用于「先建会话、后做冷启 restore」的启动顺序：恢复值不是用户动作，
   * 不该在第一次 flush 时被当成改动写回。清理时机与构造同义。
   */
  resync(): void {
    this.shFps = this.params.fps.value.value;
    this.shShutter = this.params.shutter.value.value;
    this.shBitrate = this.params.bitrate.value;
    this.shRefLines = this.params.refLines.value;
    this.shLevel = this.params.levelEnabled.value;
    this.shIso = this.params.iso.value.value;
    this.shAe = ParamsPersist.persistedAe(this.params.aeMode.value);
    this.shEv = this.params.ev.value.value;
    this.shWb = this.params.wbMode.value;
    this.shKelvin = this.params.kelvin.value.value;
    this.shTint = this.params.tint.value.value;
    this.shZoom = this.params.zoom.value.value;
    this.shFlash = this.params.flash.value;
    this.shStabilize = this.params.stabilize.value;
    this.shAf = this.params.afMode.value;
    this.shDiopter = this.params.manualFocusDiopter.value.value;
    const size: Size = this.params.requestedSizeOf();
    this.shSizeW = size.width;
    this.shSizeH = size.height;
    this.shEffect = this.params.frameEffect.value;
    this.shZebraT = this.params.zebraThreshold.value;
    this.shZebraD = this.params.zebraDensity.value;
    this.shPeakS = this.params.peakingStrength.value;
    this.shPeakC = this.params.peakingColor.value;
    this.shLens = this.params.lens.value;
    this.shArcDeadband = this.params.arcMergeDeadband.value;
  }

  /**
   * 返回 true = 无差异或全部写成功；false = 有 put/commit 失败（影子保持旧值，下次重试）。
   *
   * 批写口径（对齐上游 `commit()`）：有差异时先把所有变化字段 put 进内存，最后**只调一次**
   * [ParamsPrefs.commit] 落盘；无差异直接返回、不 commit（空落盘也是同步 IO）。
   */
  flush(): boolean {
    // 先读一遍当前总线（一次 flush 内 diff 与影子同步都吃这一份，避免中途再变）
    const fps: number = this.params.fps.value.value;
    const shutter: number = this.params.shutter.value.value;
    const bitrate: number = this.params.bitrate.value;
    const refLines: number = this.params.refLines.value;
    const level: boolean = this.params.levelEnabled.value;
    const iso: number = this.params.iso.value.value;
    const ae: AeMode = ParamsPersist.persistedAe(this.params.aeMode.value);
    const ev: number = this.params.ev.value.value;
    const wb: WbPreset = this.params.wbMode.value;
    const kelvin: number = this.params.kelvin.value.value;
    const tint: number = this.params.tint.value.value;
    const zoom: number = this.params.zoom.value.value;
    const flash: Flash = this.params.flash.value;
    const stabilize: Stabilize = this.params.stabilize.value;
    const af: AfMode = this.params.afMode.value;
    const diopter: number = this.params.manualFocusDiopter.value.value;
    const size: Size = this.params.requestedSizeOf();
    const effect: FrameEffect = this.params.frameEffect.value;
    const zebraT: number = this.params.zebraThreshold.value;
    const zebraD: number = this.params.zebraDensity.value;
    const peakS: number = this.params.peakingStrength.value;
    const peakC: PeakingColor = this.params.peakingColor.value;
    const lens: LensType = this.params.lens.value;
    const arcDeadband: number = this.params.arcMergeDeadband.value;

    // 无差异：不 put、不 commit（一次空落盘也不做；影子与总线本来就一致）
    const changed: boolean = fps !== this.shFps || shutter !== this.shShutter ||
      bitrate !== this.shBitrate || refLines !== this.shRefLines || level !== this.shLevel ||
      iso !== this.shIso || ae !== this.shAe || ev !== this.shEv || wb !== this.shWb ||
      kelvin !== this.shKelvin || tint !== this.shTint || zoom !== this.shZoom ||
      flash !== this.shFlash || stabilize !== this.shStabilize || af !== this.shAf ||
      diopter !== this.shDiopter || size.width !== this.shSizeW || size.height !== this.shSizeH ||
      effect !== this.shEffect || zebraT !== this.shZebraT || zebraD !== this.shZebraD ||
      peakS !== this.shPeakS || peakC !== this.shPeakC || lens !== this.shLens ||
      arcDeadband !== this.shArcDeadband;
    if (!changed) {
      return true;
    }

    let ok: boolean = true;
    if (fps !== this.shFps) {
      ok = this.prefs.putInt(KEY_DEFAULT_FPS, Math.trunc(fps)) && ok;
    }
    if (shutter !== this.shShutter) {
      ok = this.prefs.putInt(KEY_DEFAULT_SHUTTER_NS, Math.trunc(shutter)) && ok;
    }
    if (bitrate !== this.shBitrate) {
      ok = this.prefs.putInt(KEY_DEFAULT_BITRATE, Math.trunc(bitrate)) && ok;
    }
    if (refLines !== this.shRefLines) {
      ok = this.prefs.putInt(KEY_DEFAULT_REF_LINES, Math.trunc(refLines)) && ok;
    }
    if (level !== this.shLevel) {
      ok = this.prefs.putBool(KEY_LEVEL_ENABLED, level) && ok;
    }
    if (iso !== this.shIso) {
      ok = this.prefs.putInt(KEY_DEFAULT_ISO, Math.trunc(iso)) && ok;
    }
    if (ae !== this.shAe) {
      ok = this.prefs.putString(KEY_DEFAULT_AE_MODE, enumName(PrefEnum.AE_MODE, ae)) && ok;
    }
    if (ev !== this.shEv) {
      ok = this.prefs.putInt(KEY_DEFAULT_EV, Math.trunc(ev)) && ok;
    }
    if (wb !== this.shWb) {
      ok = this.prefs.putString(KEY_DEFAULT_WB_PRESET, enumName(PrefEnum.WB_PRESET, wb)) && ok;
    }
    if (kelvin !== this.shKelvin) {
      ok = this.prefs.putInt(KEY_DEFAULT_WB_KELVIN, Math.trunc(kelvin)) && ok;
    }
    if (tint !== this.shTint) {
      ok = this.prefs.putInt(KEY_DEFAULT_WB_TINT, Math.trunc(tint)) && ok;
    }
    if (zoom !== this.shZoom) {
      ok = this.prefs.putFloat(KEY_DEFAULT_ZOOM, zoom) && ok;
    }
    if (flash !== this.shFlash) {
      ok = this.prefs.putString(KEY_DEFAULT_FLASH, enumName(PrefEnum.FLASH, flash)) && ok;
    }
    if (stabilize !== this.shStabilize) {
      ok = this.prefs.putString(KEY_DEFAULT_STABILIZE, enumName(PrefEnum.STABILIZE, stabilize)) && ok;
    }
    if (af !== this.shAf) {
      ok = this.prefs.putString(KEY_DEFAULT_AF_MODE, enumName(PrefEnum.AF_MODE, af)) && ok;
    }
    if (diopter !== this.shDiopter) {
      ok = this.prefs.putFloat(KEY_DEFAULT_MF_DIOPTER, diopter) && ok;
    }
    if (size.width !== this.shSizeW || size.height !== this.shSizeH) {
      ok = this.prefs.putString(KEY_DEFAULT_SIZE, formatSize(size)) && ok;
    }
    if (effect !== this.shEffect) {
      ok = this.prefs.putString(KEY_DEFAULT_FRAME_EFFECT, enumName(PrefEnum.FRAME_EFFECT, effect)) && ok;
    }
    if (zebraT !== this.shZebraT) {
      ok = this.prefs.putFloat(KEY_DEFAULT_ZEBRA_THRESHOLD, zebraT) && ok;
    }
    if (zebraD !== this.shZebraD) {
      ok = this.prefs.putInt(KEY_DEFAULT_ZEBRA_DENSITY, Math.trunc(zebraD)) && ok;
    }
    if (peakS !== this.shPeakS) {
      ok = this.prefs.putFloat(KEY_DEFAULT_PEAKING_STRENGTH, peakS) && ok;
    }
    if (peakC !== this.shPeakC) {
      ok = this.prefs.putString(KEY_DEFAULT_PEAKING_COLOR, enumName(PrefEnum.PEAKING_COLOR, peakC)) && ok;
    }
    if (lens !== this.shLens) {
      ok = this.prefs.putString(KEY_DEFAULT_LENS, enumName(PrefEnum.LENS, lens)) && ok;
    }
    if (arcDeadband !== this.shArcDeadband) {
      ok = this.prefs.putInt(KEY_DEFAULT_ARC_MERGE_DEADBAND, Math.trunc(arcDeadband)) && ok;
    }

    if (!ok) {
      return false; // 写内存失败：影子保持旧值，下次 flush 重试
    }
    if (!this.prefs.commit()) {
      return false; // 落盘失败：已 put 的值仍在内存，下次 flush 重 put + 再 commit
    }
    // 落盘成功才把新值记入影子
    this.shFps = fps;
    this.shShutter = shutter;
    this.shBitrate = bitrate;
    this.shRefLines = refLines;
    this.shLevel = level;
    this.shIso = iso;
    this.shAe = ae;
    this.shEv = ev;
    this.shWb = wb;
    this.shKelvin = kelvin;
    this.shTint = tint;
    this.shZoom = zoom;
    this.shFlash = flash;
    this.shStabilize = stabilize;
    this.shAf = af;
    this.shDiopter = diopter;
    this.shSizeW = size.width;
    this.shSizeH = size.height;
    this.shEffect = effect;
    this.shZebraT = zebraT;
    this.shZebraD = zebraD;
    this.shPeakS = peakS;
    this.shPeakC = peakC;
    this.shLens = lens;
    this.shArcDeadband = arcDeadband;
    return true;
  }
}

// ---------------------------------------------------------------- 内部数值工具

/** 只写 value、不碰 range/enabled/exact（上游 `ParamState.copy(value=)` 同义）。 */
function withValue(s: ParamState, value: number): ParamState {
  return new ParamState(value, s.range, s.enabled, s.exact);
}

function clampInt(value: number, lo: number, hi: number): number {
  const v: number = Math.trunc(value);
  return Math.min(hi, Math.max(lo, v));
}

function clampFloat(value: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, value));
}
