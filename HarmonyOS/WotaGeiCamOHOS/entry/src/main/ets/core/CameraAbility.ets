/**
 * 直译自上游 core/CameraAbility.kt（harmony 分支）。
 * 平台差异：
 * - from(CameraCharacteristics) 工厂与 eqFocalMmOf/hFovOf(cc) 不在本文件——
 *   OHOS 侧由相机适配层从 CameraKit 的 CameraInput/OutputCapability 现算后填本类，
 *   「能力一律运行时读取、不写死机型数值」的原则不变；
 * - cam2Mode 等 Android 契约常量原值保留（枚举值 = 契约值），由适配层负责换算；
 * - Map<Size, List<RangeI>> 键是对象引用相等，遍历必须走 entries + sizeCloseTo
 *   （上游 fpsRangesFor 本来就是这么写的，语义一致）。
 */
import { Diag } from '../diag/Diag';
import { Rect, Size, WotaTag, WotaTiers, WbPreset } from './Constants';
import { VideoSizeCandidates, aspectOf, sizeCloseTo } from './VideoSizePicker';

/** 整数能力范围；缺失能力用 CLOSED 标记，不伪造范围（02 文件第 4 节） */
export class RangeI {
  lo: number;
  hi: number;

  constructor(lo: number, hi: number) {
    this.lo = lo;
    this.hi = hi;
  }

  static readonly CLOSED: RangeI = new RangeI(0, 0);

  ok(): boolean {
    return this.hi > 0 && this.lo <= this.hi;
  }

  closed(): boolean {
    return !this.ok();
  }

  clamp(v: number): number {
    return this.closed() ? v : Math.min(this.hi, Math.max(this.lo, v));
  }

  toString(): string {
    return this.closed() ? 'closed' : `${this.lo}..${this.hi}`;
  }
}

/** 浮点能力范围（变焦倍率等） */
export class RangeF {
  lo: number;
  hi: number;

  constructor(lo: number, hi: number) {
    this.lo = lo;
    this.hi = hi;
  }

  static readonly CLOSED: RangeF = new RangeF(0, 0);

  ok(): boolean {
    return this.hi > 0 && this.lo <= this.hi;
  }

  closed(): boolean {
    return !this.ok();
  }

  clamp(v: number): number {
    return this.closed() ? v : Math.min(this.hi, Math.max(this.lo, v));
  }

  toString(): string {
    return this.closed() ? 'closed' : `${this.lo}..${this.hi}`;
  }
}

/** Camera2 INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY 契约值：LEGACY 设备无逐帧控制/无 ZOOM_RATIO */
const HARDWARE_LEVEL_LEGACY: number = 2;

/** 单个镜头的能力一次性收全并缓存（切镜头 = 整套换挡） */
export class CameraAbility {
  hardwareLevel: number;
  sensorOrientation: number;
  activeArray: Rect;
  jpegSizes: Array<Size>;
  highSpeedSizes: Array<Size>;
  videoSizes: Array<Size>;
  fpsRanges: Array<RangeI>;
  highSpeedFps: Map<Size, Array<RangeI>>;
  iso: RangeI;
  exposureNs: RangeI;
  evRange: RangeI;
  evStep: number;
  afModes: Set<number>;
  awbModes: Set<number>;
  flashAvailable: boolean;
  maxRegionsAf: number;
  maxRegionsAe: number;
  minFocusDistanceDiopter: number;
  zoomRatioRange: RangeF | undefined;
  maxDigitalZoom: number;
  oisAvailable: boolean;
  eisAvailable: boolean;
  highSpeedCapable: boolean;
  availableSurfaceFormats: Array<number>;
  /**
   * 预览/直显面专用单表（对应上游 ImageFormat.PRIVATE 单表尺寸）。
   * videoSizes 是预览 ∪ 照片表，DIRECT 模式挑预览流尺寸必须用这张单表。默认空表兼容旧构造点。
   */
  previewSizes: Array<Size>;
  /**
   * OHOS 适配层扩展字段（非上游 Camera2 字段，登记于 KitAbility.abilityRefineWithSession）：
   * 会话期 ManualFocusQuery.isFocusDistanceSupported（API24）探针结果。
   * undefined = 未探测/旧设备无该接口；true = 可下发 setFocusDistance；false = 设备拒绝该能力。
   */
  focusDistanceSupported: boolean | undefined;

  constructor(ability: CameraAbilityArgs) {
    this.hardwareLevel = ability.hardwareLevel;
    this.sensorOrientation = ability.sensorOrientation;
    this.activeArray = ability.activeArray;
    this.jpegSizes = ability.jpegSizes;
    this.highSpeedSizes = ability.highSpeedSizes;
    this.videoSizes = ability.videoSizes;
    this.fpsRanges = ability.fpsRanges;
    this.highSpeedFps = ability.highSpeedFps;
    this.iso = ability.iso;
    this.exposureNs = ability.exposureNs;
    this.evRange = ability.evRange;
    this.evStep = ability.evStep;
    this.afModes = ability.afModes;
    this.awbModes = ability.awbModes;
    this.flashAvailable = ability.flashAvailable;
    this.maxRegionsAf = ability.maxRegionsAf;
    this.maxRegionsAe = ability.maxRegionsAe;
    this.minFocusDistanceDiopter = ability.minFocusDistanceDiopter;
    this.zoomRatioRange = ability.zoomRatioRange;
    this.maxDigitalZoom = ability.maxDigitalZoom;
    this.oisAvailable = ability.oisAvailable;
    this.eisAvailable = ability.eisAvailable;
    this.highSpeedCapable = ability.highSpeedCapable;
    this.availableSurfaceFormats = ability.availableSurfaceFormats;
    this.previewSizes = ability.previewSizes ?? [];
    this.focusDistanceSupported = ability.focusDistanceSupported;
  }

  /** LEGACY 设备无逐帧控制/无 ZOOM_RATIO，UI 顶部要提示能力受限 */
  isLegacy(): boolean {
    return this.hardwareLevel === HARDWARE_LEVEL_LEGACY;
  }

  hasFixedFps(fps: number): boolean {
    return this.fpsRanges.some((r: RangeI): boolean => r.lo === fps && r.hi === fps);
  }

  fixedTiers(tiers: Set<number>): Set<number> {
    return new Set<number>(Array.from(tiers).filter((t: number): boolean => this.hasFixedFps(t)));
  }

  /** 手动曝光三件套里的 ISO+快门是否可用（能力表未给范围即不可手动） */
  manualExposurePossible(): boolean {
    return this.iso.ok() && this.exposureNs.ok();
  }

  /**
   * 手动白平衡（色温轴）在本机是否真能生效（上游 ab73ebf/ee9aa0e 口径的 OHOS 对应）：
   * 契约前提是会话探测表里**声明了 MANUAL 档**（KitAbility 会话期经
   * isWhiteBalanceModeSupported 逐档探测后映射回 awbModes——运行时探测，不是能力假设）。
   * 空集合 = 未探测/旧路径拿不到（无法判定）⇒ 视为可用，保持既有行为
   * （不因「查不到」把用户已选的手动档弹掉）。
   */
  manualWhiteBalancePossible(): boolean {
    return this.awbModes.size === 0 || this.awbModes.has(WbPreset.MANUAL as number);
  }

  /**
   * 帧率范围按尺寸分流：高速档是「尺寸 → 范围」表，
   * size 缺省或该尺寸未列入高速表时，退化成所有高速尺寸的并集，仅用于档位可用性判断。
   */
  fpsRangesFor(size: Size | undefined, highSpeed: boolean): Array<RangeI> {
    if (!highSpeed || !this.highSpeedCapable || this.highSpeedFps.size === 0) {
      return this.fpsRanges;
    }
    if (size === undefined) {
      // Kotlin 侧 distinct() 按值去重；RangeI 在 ArkTS 是引用相等，这里保留重复项
      // （该结果只用于档位可用性判断，重复范围不影响结论）
      const merged: Array<RangeI> = [];
      this.highSpeedFps.forEach((rs: Array<RangeI>): void => { merged.push(...rs); });
      return merged;
    }
    let exact: Array<RangeI> | undefined = undefined;
    this.highSpeedFps.forEach((rs: Array<RangeI>, s: Size): void => {
      if (exact === undefined && sizeCloseTo(s, size, VideoSizeCandidates.TOLERANCE_PX)) {
        exact = rs;
      }
    });
    return exact ?? [];
  }

  /**
   * 档位可用性判定域：普通 ∪ 高速（按尺寸）并集——档位「能不能选」看它。
   * 厂商常把 50/60 只放进高速表（如 vivo V2520A：普通流仅 [24,24]/[30,30]，
   * 高速 1080p 给 [30,120]/[60,120]/[120,120]/[30,240]/[60,240]/[240,240]），
   * 按当前会话模态判定会让 50/60/120/240 永远灰显（上游 2026-10-26 用户报障，74d4fb2）。
   * 会话内的下发 / 钳制仍按单模态 [fpsRangesFor]（当前会话实际给得到的范围）。
   */
  fpsRangesUnion(size: Size | undefined): Array<RangeI> {
    return fpsRangesUnion(this.fpsRanges, this.fpsRangesFor(size, true));
  }

  /** 该帧率普通会话覆盖不了、但高速表（按尺寸）覆盖 ⇒ 需要高速会话（结构性换挡） */
  needsHighSpeed(size: Size | undefined, fps: number): boolean {
    return needsHighSpeedFps(this.fpsRanges, this.fpsRangesFor(size, true), fps);
  }

  /**
   * 一档的**完整决议**：用哪张表、下发哪个区间、精不精确、要不要高速会话。
   * UI 的可选性/※ 标记、参数总线落档、引擎开会话与下发，四处都只认这一个出口
   * （上游 2026-10-26「三处判据分叉」事故的根治：各写一份就会「界面说可选、请求发的是非法值」）。
   */
  decideFps(size: Size | undefined, fps: number): FpsDecision | undefined {
    return decideFps(this.fpsRanges, this.fpsRangesFor(size, true), fps);
  }

  /** 录像尺寸上下限（UI「本机支持范围 …～」文案数据源） */
  videoBounds(): Array<Size | undefined> {
    if (this.videoSizes.length === 0) {
      return [undefined, undefined];
    }
    return [maxBySize(this.videoSizes), minBySize(this.videoSizes)];
  }

  /**
   * 调试用能力 dump（M1 门禁）：上下限/固定帧率档都在此打印，hilog 按 WotaAbility 过滤一眼可核。
   * @param label 镜头标识（cameraId / 档位名），能力表本身不保存 id，由调用方传入
   */
  dump(label: string = ''): void {
    const tag: string = WotaTag.ABILITY;
    const bounds: Array<Size | undefined> = this.videoBounds();
    const maxSize: Size | undefined = bounds[0];
    const minSize: Size | undefined = bounds[1];
    Diag.i(tag, `ability[${label}] level=${this.hardwareLevel} legacy=${this.isLegacy()}` +
      ` orient=${this.sensorOrientation}deg activeArray=${this.activeArray.arrayWidth}x${this.activeArray.arrayHeight}`);
    Diag.i(tag, `ability[${label}] iso=${this.iso} exposureNs=${this.exposureNs} ev=${this.evRange}` +
      ` evStep=${this.evStep} minFocusD=${this.minFocusDistanceDiopter}` +
      ` zoomRatio=${this.zoomRatioRange === undefined ? 'closed' : this.zoomRatioRange.toString()} maxDigital=${this.maxDigitalZoom}`);
    Diag.i(tag, `ability[${label}] afModes=${setToString(this.afModes)} awbModes=${setToString(this.awbModes)}` +
      ` flash=${this.flashAvailable} regionsAf=${this.maxRegionsAf} regionsAe=${this.maxRegionsAe}` +
      ` ois=${this.oisAvailable} eis=${this.eisAvailable}`);
    Diag.i(tag, `ability[${label}] fpsRanges=${this.fpsRanges.map((r: RangeI): string => `[${r.lo},${r.hi}]`).join(',')}` +
      ` fixed24=${this.hasFixedFps(24)} fixed25=${this.hasFixedFps(25)} highSpeedCapable=${this.highSpeedCapable}`);
    Diag.i(tag, `ability[${label}] videoSizes=${this.videoSizes.length}` +
      ` max=${fmtSize(maxSize)}(${aspectOfSize(maxSize)}) min=${fmtSize(minSize)}(${aspectOfSize(minSize)})` +
      ` jpeg=${this.jpegSizes.length} preview=${this.previewSizes.length}` +
      ` highSpeedSizes=${this.highSpeedSizes.length}`);
    Diag.i(tag, `ability[${label}] formats=${this.availableSurfaceFormats.join(',')}`);
    if (this.highSpeedFps.size > 0) {
      let n: number = 0;
      this.highSpeedFps.forEach((rs: Array<RangeI>, s: Size): void => {
        if (n < 8) {
          Diag.i(tag, `ability[${label}] highSpeed ${fmtSize(s)} -> ${rs.map((r: RangeI): string => `[${r.lo},${r.hi}]`).join(',')}`);
          n++;
        }
      });
    }
  }
}

/** CameraAbility 构造参数（对应上游 data class 的全字段构造；对象字面量必须有具名类型） */
export interface CameraAbilityArgs {
  hardwareLevel: number;
  sensorOrientation: number;
  activeArray: Rect;
  jpegSizes: Array<Size>;
  highSpeedSizes: Array<Size>;
  videoSizes: Array<Size>;
  fpsRanges: Array<RangeI>;
  highSpeedFps: Map<Size, Array<RangeI>>;
  iso: RangeI;
  exposureNs: RangeI;
  evRange: RangeI;
  evStep: number;
  afModes: Set<number>;
  awbModes: Set<number>;
  flashAvailable: boolean;
  maxRegionsAf: number;
  maxRegionsAe: number;
  minFocusDistanceDiopter: number;
  zoomRatioRange: RangeF | undefined;
  maxDigitalZoom: number;
  oisAvailable: boolean;
  eisAvailable: boolean;
  highSpeedCapable: boolean;
  availableSurfaceFormats: Array<number>;
  previewSizes?: Array<Size>;
  /** OHOS 适配层扩展（非上游字段）：ManualFocusQuery.isFocusDistanceSupported 探针结果 */
  focusDistanceSupported?: boolean;
}

/** 目标帧率 → 实际写入目标 fps 范围的档位（固定 [f,f] 优先，避免 AE 漂移） */
export class FpsPick {
  lo: number;
  hi: number;
  exact: boolean;

  constructor(lo: number, hi: number, exact: boolean) {
    this.lo = lo;
    this.hi = hi;
    this.exact = exact;
  }
}

/**
 * 快门「强制档」是否落在本机曝光范围之外 —— UI 打 `※` 的唯一判据
 * （与下发侧钳制用同一个 WotaTiers.isRequiredShutterNs）。
 * 能力缺失（closed）时不算超范围：那种设备整条手动曝光都不可用，另有灰显路径。
 */
export function forcedShutterOutOfRange(shutterNs: number, exposureNs: RangeI): boolean {
  return exposureNs.ok() && WotaTiers.isRequiredShutterNs(shutterNs) &&
    (shutterNs < exposureNs.lo || shutterNs > exposureNs.hi);
}

/**
 * 快门可用上限（纳秒）。三步取最小（顺序无关）：
 * 1. 先把设备曝光上限抬到强制档（1/24 = 41_666_666ns）——设备没有这两档时也要可选；
 * 2. 1/10s 防手抖上限；
 * 3. ≤1/帧周期——这一刀吃的是帧率档位 fpsHi（与下发侧 exposurePlan 同源）。
 * ⚠ 已知边界（刻意接受）：抬上限会让范围比设备真实上限宽出一段，滑杆能落到
 * 「设备范围外、又不是那两档精确值」的空档里，下发时仍会被夹回设备上限；
 * 强制值请走档位胶囊（给的是精确纳秒值，按该值下发）。
 */
export function shutterCeilingNs(deviceMaxNs: number, fpsHi: number): number {
  const raised: number = Math.max(deviceMaxNs, maxRequiredShutterNs());
  return Math.min(raised, WotaTiers.SHUTTER_CAP_NS, Math.floor(WotaTiers.NS_PER_SECOND / Math.max(1, fpsHi)));
}

function maxRequiredShutterNs(): number {
  let m: number = Number.NEGATIVE_INFINITY;
  WotaTiers.REQUIRED_SHUTTER_NS.forEach((ns: number): void => {
    if (ns > m) {
      m = ns;
    }
  });
  return m;
}

/** 返回 undefined 表示设备没有任何范围能覆盖 target，UI 侧该档位灰显 */
export function pickFpsRange(ranges: Array<RangeI>, target: number): FpsPick | undefined {
  if (ranges.length === 0) {
    return undefined;
  }
  const fixed: RangeI | undefined = ranges.find((r: RangeI): boolean => r.lo === target && r.hi === target);
  if (fixed !== undefined) {
    return new FpsPick(fixed.lo, fixed.hi, true);
  }
  let best: RangeI | undefined = undefined;
  for (const r of ranges) {
    if (r.lo <= target && target <= r.hi) {
      if (best === undefined || r.hi > best.hi) {
        best = r;
      }
    }
  }
  return best === undefined ? undefined : new FpsPick(best.lo, best.hi, false);
}

/**
 * 高速表专用选档（上游 2026-10-26 新增，decideFps 出口的一半）：**只用机器声明过的区间**，
 * 绝不自己造 `[f,f]`。
 *
 * 为什么不沿用 [pickFpsRange]：它「取覆盖目标里上界最大者」，放到高速表上会挑到 `[30,240]`
 * 这种极宽区间——用户选 60、HAL 却有机会跑到 240。高速表这里分三级：
 * 1. **固定档 `[f,f]`**（`[120,120]`/`[240,240]`）⇒ 精确，原样返回；
 * 2. **下界正好等于目标**的区间（目标 60 → `[60,120]`/`[60,240]`）⇒ 最贴近目标且只会往上，
 *    取其中上界最小者；
 * 3. 再退：覆盖目标者取上界最小者（目标 50 只有 `[30,120]`/`[30,240]` ⇒ 取 `[30,120]`）。
 *
 * 返回 undefined = 这张表没有任何区间覆盖目标（调用方按「本机做不到这一档」处理，
 * **不要**退回普通表硬发）。上游修正前的旧实现是 `FpsPick(target, target, true)`——直接造
 * `[50,50]`，那不是机器声明过的值，下发必被拒且没有退路 ⇒ 取景器假死。
 */
export function pickHighSpeedFpsRange(ranges: Array<RangeI>, target: number): FpsPick | undefined {
  if (ranges.length === 0 || target <= 0) {
    return undefined;
  }
  const fixed: RangeI | undefined = ranges.find((r: RangeI): boolean => r.lo === target && r.hi === target);
  if (fixed !== undefined) {
    return new FpsPick(fixed.lo, fixed.hi, true);
  }
  const containing: Array<RangeI> = ranges.filter((r: RangeI): boolean => r.lo <= target && target <= r.hi);
  if (containing.length === 0) {
    return undefined;
  }
  const loMatch: Array<RangeI> = containing.filter((r: RangeI): boolean => r.lo === target);
  const pool: Array<RangeI> = loMatch.length > 0 ? loMatch : containing;
  let chosen: RangeI = pool[0];
  for (const r of pool) {
    if (r.hi < chosen.hi) {
      chosen = r;
    }
  }
  return new FpsPick(chosen.lo, chosen.hi, false);
}

/** 档位可用性判定域：普通 ∪ 高速并集。UI 档位「能不能选」与换档精确性按它判定；会话内下发 / 钳制仍按单模态范围表 */
export function fpsRangesUnion(normal: Array<RangeI>, highSpeed: Array<RangeI>): Array<RangeI> {
  return normal.concat(highSpeed);
}

/** 该帧率普通会话覆盖不了、但高速会话（按尺寸的高速范围表）覆盖 ⇒ 需要高速会话 */
export function needsHighSpeedFps(normal: Array<RangeI>, highSpeed: Array<RangeI>, fps: number): boolean {
  return pickFpsRange(normal, fps) === undefined && pickHighSpeedFpsRange(highSpeed, fps) !== undefined;
}

/** 一档的完整决议（见 CameraAbility.decideFps）：下发哪个区间、走不走高速会话 */
export class FpsDecision {
  pick: FpsPick;
  highSpeed: boolean;

  constructor(pick: FpsPick, highSpeed: boolean) {
    this.pick = pick;
    this.highSpeed = highSpeed;
  }
}

/**
 * 一档的完整决议（纯函数，全表单测）：**普通表先来，普通表覆盖不了才看高速表**。
 * 返回 undefined = 两张表都不覆盖 ⇒ 界面灰显、总线落 `enabled=false`，不硬发。
 *
 * 这条顺序就是产品口径：机器能在普通流里跑的就别开高速会话；只有普通流给不到的档才请出高速会话。
 * 它同时回答「50/60 该不该灰」——**看机器在哪张表里声明**：声明在普通表就是普通档；只在高速表
 * 声明的（如 vivo V2520A 的 50/60）仍然可用，因为那张表正是厂商把「高帧率录像」暴露给三方 App
 * 的途径，只是实际速率由 HAL 在区间内定 ⇒ 界面标 ※ 如实告知。
 */
export function decideFps(normal: Array<RangeI>, highSpeed: Array<RangeI>, fps: number): FpsDecision | undefined {
  if (fps <= 0) {
    return undefined;
  }
  const normalPick: FpsPick | undefined = pickFpsRange(normal, fps);
  if (normalPick !== undefined) {
    return new FpsDecision(normalPick, false);
  }
  const highSpeedPick: FpsPick | undefined = pickHighSpeedFpsRange(highSpeed, fps);
  if (highSpeedPick !== undefined) {
    return new FpsDecision(highSpeedPick, true);
  }
  return undefined;
}

/** 35mm 等效焦距：eq = round(43.27 / sqrt(sw²+sh²) × focal × 100) / 100；43.27 是全画幅对角线 */
export function eqFocalMm(sensorWidthMm: number, sensorHeightMm: number, focalMm: number): number {
  if (sensorWidthMm <= 0 || sensorHeightMm <= 0) {
    return 0;
  }
  const diagonal: number = Math.sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm);
  if (diagonal <= 0) {
    return 0;
  }
  return Math.round(43.27 / diagonal * focalMm * 100) / 100;
}

/** 水平视场角（度），FOV_h = 2 × atan(sensorW / 2 / focal) */
export function hFovDeg(sensorWidthMm: number, focalMm: number): number {
  if (sensorWidthMm <= 0 || focalMm <= 0) {
    return 0;
  }
  return 2 * Math.atan(sensorWidthMm / 2 / focalMm) * 180 / Math.PI;
}

/** ControlRequest CONTROL_AF_MODE_OFF 契约值（定焦） */
const AF_MODE_OFF: number = 0;

/**
 * #44 定版：这颗镜头到底有没有「可改的对焦」。
 * 只有 AF_MODE_OFF（定焦）且没有手动屈光度域 → 界面上没有任何东西能调，
 * 对焦入口整颗隐藏，而不是摆一排灰选项骗用户点。
 */
export function adjustableFocus(afModes: Set<number>, minFocusDistanceDiopter: number): boolean {
  for (const m of afModes) {
    if (m !== AF_MODE_OFF) {
      return true;
    }
  }
  return minFocusDistanceDiopter > 0;
}

export function hasAdjustableFocus(a: CameraAbility): boolean {
  return adjustableFocus(a.afModes, a.minFocusDistanceDiopter);
}

// ---- 私有工具 ----

function maxBySize(sizes: Array<Size>): Size | undefined {
  let best: Size | undefined = undefined;
  for (const s of sizes) {
    if (best === undefined || s.pixels > best.pixels) {
      best = s;
    }
  }
  return best;
}

function minBySize(sizes: Array<Size>): Size | undefined {
  let best: Size | undefined = undefined;
  for (const s of sizes) {
    if (best === undefined || s.pixels < best.pixels) {
      best = s;
    }
  }
  return best;
}

function fmtSize(s: Size | undefined): string {
  return s === undefined ? 'none' : `${s.width}x${s.height}`;
}

function aspectOfSize(s: Size | undefined): string {
  return s === undefined ? '-' : aspectOf(s.width, s.height);
}

function setToString(set: Set<number>): string {
  return `[${Array.from(set).join(',')}]`;
}
