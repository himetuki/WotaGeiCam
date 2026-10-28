/**
 * 参数 → 相机会话的决策层（纯段）+ OHOS 下发适配（apply 段）——上游 camera/RequestApplier.kt
 * （487 行）的鸿蒙移植（对齐上游 38d712a）。
 *
 * 文件分区：前半【纯决策层】零 @kit import，可直接对拍/将来单测；后半【下发适配】只碰 camera 类型。
 *
 * 平台适配边界（登记，执行口径钉死）：
 * - OHOS 无 CaptureRequest builder，「混合键集」问题不存在（会话持有状态，setter 即生效）——
 *   上游 apply(builder,…) 换成 applySnapshot(session,…)：逐键 try/catch，产出 applied/skipped/failed 报告；
 * - 不移植项（无等价物）：kelvinTintToRgb255 / gain255ToUnit / rggbFromRgb255 / identityColorTransform
 *   （OHOS 手动白平衡走 setWhiteBalanceMode(MANUAL)+setWhiteBalance(kelvin)，无 RGB 增益接口，tint 副轴留白）；
 *   meteringBox / fullFrameBox / meteringRect（OHOS 用归一化 setFocusPoint/setMeteringPoint，TapPoint 本就 [0,1]）；
 *   cropBox（OHOS 只有 setZoomRatio）；afTrigger（无 AF 触发语义）；逐帧 CONTROL_AE_TARGET_FPS_RANGE
 *   （预览档 profile 固定，FpsPick 仅留作 State/dump）；
 * - 曝光映射：OHOS 无 AE OFF——MANUAL → ExposureMode.LOCKED + setExposureDuration + setIso，
 *   LOCK → LOCKED（同组键按快照写齐），AUTO → CONTINUOUS_AUTO。**不用 EXPOSURE_MODE_MANUAL**：
 *   d.ts 明确 setIso 仅 EXPOSURE_MODE_LOCKED 生效（会令手动 ISO 失效，登记）。
 *   **单位**：OHOS 曝光时长接口是微秒（getSupportedExposureDurationRange/setExposureDuration），
 *   core 契约 exposureNs 是纳秒——采集 ×1000、下发 /1000（KitAbility refine 与 apply 两处）；
 * - 曝光组按档语义：**MANUAL 写全**（时长 µs + ISO + EV 归零）；**LOCK/AUTO 只写模式与补偿**
 *   （LOCK 语义 = 冻结当前 AE 曝光：上游 AE_LOCK=true + AE_MODE_ON 且 SENSOR 键被 HAL 忽略，
 *   而 OHOS EXPOSURE_MODE_LOCKED 下 setIso 实际生效，写快照值会跳档——故不写，登记）；
 * - 手动对焦距离：OHOS ManualFocus.setFocusDistance（归一化 0.0 最近 / 1.0 最远，API24）——
 *   diopter/maxDiopter 线性折算；**refine 中 isFocusDistanceSupported() 探针门控**（API24，
 *   try/catch+抑制，结果经 CameraAbility.focusDistanceSupported 透传）——探针 false/不可得
 *   （6.0 无该 API）时进 skipped（manual 仅切模式，不是 failed），登记；
 * - EV 单位换算：OHOS getExposureBiasRange 给 EV 值、getExposureBiasStep 给步长——evRange 换算成档数
 *   （round(EV/step)）保持 core 契约（params.ev 是步数），下发时 setExposureBias(steps × evStep)；
 *   evStep ≤0/不可读 → 不猜 1，补偿键进 skipped 自曝；
 * - maxRegionsAf/Ae = 1（OHOS 单点对焦/测光，无区域数概念；上游该值仅用于门控区域写入与 dump，登记近似）。
 */
import {
  AeMode, AfMode, Flash, Rect, Size, Stabilize, TapPoint, WbPreset, WotaTag, WotaTiers
} from '../core/Constants';
import { CameraAbility, FpsPick, RangeI, RangeF, pickFpsRange, pickHighSpeedFpsRange } from '../core/CameraAbility';
import { WotaParams } from '../core/WotaParams';
import { camera } from '@kit.CameraKit';
import { Diag } from '../diag/Diag';
import {
  exposureModeFor, focusModeFor, oisModeFor, wbModeFor
} from './ParamMap';

// ---------------------------------------------------------------- 纯决策层（零平台依赖）

/** 3840×2160 及以上带宽下 EIS 不下发（上游门控 2） */
export const EIS_BLOCK_LONG_EDGE: number = 3840;
export const EIS_BLOCK_SHORT_EDGE: number = 2160;

/** Camera2 CONTROL_AE_MODE 契约值（上游 ExposurePlan.aeMode 字段口径） */
const AE_MODE_OFF: number = 0;
const AE_MODE_ON: number = 1;

/** 闪光方案：AE_MODE 语义 + 常亮灯开关（官方语义，不做反向映射） */
export enum FlashAe {
  ON,
  ON_ALWAYS_FLASH,
  ON_AUTO_FLASH
}

export class FlashPlan {
  ae: FlashAe;
  torch: boolean;

  constructor(ae: FlashAe, torch: boolean) {
    this.ae = ae;
    this.torch = torch;
  }
}

/**
 * 防抖方案。undefined = 该键不下发（能力没给，强设会让 HAL 行为未定义，上游门控 1）；
 * 非 undefined = 显式写 ON/OFF——关闭也要写值，「不写」等于交给 HAL 自主决定。
 * （上游用 null，ArkTS 惯例用 undefined，语义等价——登记）
 */
export class StabilizePlan {
  oisOn: boolean | undefined;
  eisOn: boolean | undefined;

  constructor(oisOn: boolean | undefined, eisOn: boolean | undefined) {
    this.oisOn = oisOn;
    this.eisOn = eisOn;
  }
}

/** 下发所需的设备侧快照，由 stateOf 从能力一次性算出；切镜头时整套重建 */
export interface StateArgs {
  activeArray: Rect;
  isoMin: number;
  isoMax: number;
  exposureMinNs: number;
  exposureMaxNs: number;
  evMinSteps: number;
  evMaxSteps: number;
  fpsRanges: Array<RangeI>;
  maxRegionsAf: number;
  maxRegionsAe: number;
  manualFocusMaxDiopter: number;
  zoomMin: number;
  zoomMax: number;
  useZoomRatio: boolean;
  flashAvailable: boolean;
  oisAvailable: boolean;
  eisAvailable: boolean;
  highSpeed: boolean;
  eisBlockedBySize: boolean;
  /** OHOS 会话 EV 步长（getExposureBiasStep；上游无此字段，为 setExposureBias 换算而增，登记） */
  evStep: number;
  /** OHOS 会话对焦距离能力探针（isFocusDistanceSupported，API24）：true 才下发 setFocusDistance */
  focusDistanceSupported: boolean;
  /** 手动白平衡探测（manualWhiteBalancePossible）：false 时 apply 对 MANUAL 档 skip 不硬发 */
  wbManualSupported: boolean;
}

export class State {
  activeArray: Rect;
  isoMin: number;
  isoMax: number;
  exposureMinNs: number;
  exposureMaxNs: number;
  evMinSteps: number;
  evMaxSteps: number;
  fpsRanges: Array<RangeI>;
  maxRegionsAf: number;
  maxRegionsAe: number;
  manualFocusMaxDiopter: number;
  zoomMin: number;
  zoomMax: number;
  useZoomRatio: boolean;
  flashAvailable: boolean;
  oisAvailable: boolean;
  eisAvailable: boolean;
  highSpeed: boolean;
  eisBlockedBySize: boolean;
  evStep: number;
  focusDistanceSupported: boolean;
  wbManualSupported: boolean;

  constructor(state: StateArgs) {
    this.activeArray = state.activeArray;
    this.isoMin = state.isoMin;
    this.isoMax = state.isoMax;
    this.exposureMinNs = state.exposureMinNs;
    this.exposureMaxNs = state.exposureMaxNs;
    this.evMinSteps = state.evMinSteps;
    this.evMaxSteps = state.evMaxSteps;
    this.fpsRanges = state.fpsRanges;
    this.maxRegionsAf = state.maxRegionsAf;
    this.maxRegionsAe = state.maxRegionsAe;
    this.manualFocusMaxDiopter = state.manualFocusMaxDiopter;
    this.zoomMin = state.zoomMin;
    this.zoomMax = state.zoomMax;
    this.useZoomRatio = state.useZoomRatio;
    this.flashAvailable = state.flashAvailable;
    this.oisAvailable = state.oisAvailable;
    this.eisAvailable = state.eisAvailable;
    this.highSpeed = state.highSpeed;
    this.eisBlockedBySize = state.eisBlockedBySize;
    this.evStep = state.evStep;
    this.focusDistanceSupported = state.focusDistanceSupported;
    this.wbManualSupported = state.wbManualSupported;
  }

  /** 能力缺失时 RangeI 是 closed(0..0)，不能拿它去 coerce，否则会把 ISO 压成 0 */
  clampIso(value: number): number {
    return this.isoMin >= 1 && this.isoMin <= this.isoMax ? Math.min(this.isoMax, Math.max(this.isoMin, value)) : value;
  }

  clampEv(steps: number): number {
    return this.evMaxSteps >= this.evMinSteps ? Math.min(this.evMaxSteps, Math.max(this.evMinSteps, steps)) : 0;
  }
}

/**
 * 一次下发的参数快照（值已由引擎钳制到 State 范围内）。
 * 删除字段登记：afModeOverride / flashOverride / afRegions / aeRegions / afTrigger 在 OHOS 无对应语义；
 * focusDiopter 保留（OHOS ManualFocus.setFocusDistance 归一化 0..1，见 apply 对焦段）；
 * tapPoint 同时驱动 focusPoint + meteringPoint。
 */
export interface SnapshotArgs {
  aeMode: AeMode;
  iso: number;
  shutterNs: number;
  evSteps: number;
  fps: FpsPick | undefined;
  afMode: AfMode;
  focusLocked: boolean;
  /** FL 批：锁定锚点（focusPoint 语义让位；缺省画面中心由消费方兜底） */
  focusLockPoint: TapPoint | undefined;
  /** 手动对焦屈光度（1/m）；OHOS 侧按 diopter/maxDiopter 归一化到 setFocusDistance 的 0..1 域 */
  focusDiopter: number;
  zoom: number;
  wbPreset: WbPreset;
  kelvin: number;
  tint: number;
  flash: Flash;
  stabilize: Stabilize;
  tapPoint: TapPoint | undefined;
  /**
   * 用户选的**帧率档位**（上游 2026-10-26 对齐）：帧周期与快门钳制的基准。
   *
   * 为什么不直接用 fps.lo：两个选档函数都只返回**包含目标**的区间（lo ≤ 目标恒成立），
   * 而区间下界在可变高速档上会**低于**目标——vivo 的 50 → `[30,120]`，lo=30 ⇒ 帧周期
   * 33.3ms > 50fps 的 20ms，快门梯因此放出一个「引擎必定被 HAL 压回」的死档。
   * 「钳制吃范围下界」的裁决针对的是 [24,30] 选 24（lo==目标==24）；在 lo < 目标
   * 的档上必须回到**目标档位**才与用户意图、与 core.shutterCeilingNs
   * （WotaParams.applyAbility 一直按目标档算）同一条线。取 max 是防御：万一未来出现
   * lo > 目标的选档（现在不可能），仍以较长帧周期为准不炸。
   */
  fpsTarget?: number;
}

export class Snapshot {
  aeMode: AeMode;
  iso: number;
  shutterNs: number;
  evSteps: number;
  fps: FpsPick | undefined;
  afMode: AfMode;
  focusLocked: boolean;
  focusLockPoint: TapPoint | undefined;
  focusDiopter: number;
  zoom: number;
  wbPreset: WbPreset;
  kelvin: number;
  tint: number;
  flash: Flash;
  stabilize: Stabilize;
  tapPoint: TapPoint | undefined;
  /** 见 SnapshotArgs.fpsTarget；缺省 0 = 来源缺失，exposurePlan 走兜底链 */
  fpsTarget: number;

  constructor(p: SnapshotArgs) {
    this.aeMode = p.aeMode;
    this.iso = p.iso;
    this.shutterNs = p.shutterNs;
    this.evSteps = p.evSteps;
    this.fps = p.fps;
    this.afMode = p.afMode;
    this.focusLocked = p.focusLocked;
    this.focusLockPoint = p.focusLockPoint;
    this.focusDiopter = p.focusDiopter;
    this.zoom = p.zoom;
    this.wbPreset = p.wbPreset;
    this.kelvin = p.kelvin;
    this.tint = p.tint;
    this.flash = p.flash;
    this.stabilize = p.stabilize;
    this.tapPoint = p.tapPoint;
    this.fpsTarget = p.fpsTarget ?? 0;
  }
}

/**
 * 一次性收全下发所需能力：区域上限、手动对焦域、变焦域、防抖能力、4K 门禁在此定型。
 * useZoomRatio：OHOS 恒按 ZOOM_RATIO（无 crop 等价物，登记）；evStep 由会话真值带入。
 */
export function stateOf(ability: CameraAbility, size: Size, highSpeed: boolean, evStep: number = 0): State {
  const ratio: RangeF | undefined = ability.zoomRatioRange;
  const zoomMinRaw: number = ratio !== undefined ? ratio.lo : 1;
  const zoomMaxRaw: number = ratio !== undefined ? ratio.hi : Math.max(1, ability.maxDigitalZoom);
  const longEdge: number = Math.max(size.width, size.height);
  const shortEdge: number = Math.min(size.width, size.height);
  return new State({
    activeArray: ability.activeArray,
    isoMin: ability.iso.lo,
    isoMax: ability.iso.hi,
    exposureMinNs: ability.exposureNs.lo,
    exposureMaxNs: ability.exposureNs.hi,
    evMinSteps: ability.evRange.lo,
    evMaxSteps: ability.evRange.hi,
    fpsRanges: ability.fpsRangesFor(size, highSpeed),
    maxRegionsAf: ability.maxRegionsAf,
    maxRegionsAe: ability.maxRegionsAe,
    manualFocusMaxDiopter: ability.minFocusDistanceDiopter,
    zoomMin: Math.min(zoomMinRaw, zoomMaxRaw),
    zoomMax: Math.max(zoomMinRaw, zoomMaxRaw),
    useZoomRatio: ratio !== undefined,
    flashAvailable: ability.flashAvailable,
    oisAvailable: ability.oisAvailable,
    eisAvailable: ability.eisAvailable,
    highSpeed: highSpeed,
    eisBlockedBySize: longEdge >= EIS_BLOCK_LONG_EDGE && shortEdge >= EIS_BLOCK_SHORT_EDGE,
    evStep: evStep,
    focusDistanceSupported: ability.focusDistanceSupported === true,
    wbManualSupported: ability.manualWhiteBalancePossible()
  });
}

/**
 * 快门双钳制（三条硬规则）：≤ 1/fps（否则丢帧）、≤ 1/10s（防手抖糊片），
 * 最后夹进设备曝光时间范围；范围缺失（closed）时跳过对应那一刀。
 *
 * forceDeviceRange 只给「强制快门档」（1/24、1/25）用：设备曝光范围里没有这两个值时也照样
 * 按该值下发（是否被 HAL 接受由设备决定）；只豁免设备范围那两刀，1/10s 与 1/帧周期照旧生效。
 */
export function clampShutterNs(
  shutterNs: number,
  fps: number,
  exposureMinNs: number,
  exposureMaxNs: number,
  forceDeviceRange: boolean = false
): number {
  const safeFps: number = Math.max(1, Math.floor(fps));
  const perFrame: number = Math.floor(WotaTiers.NS_PER_SECOND / safeFps);
  let value: number = Math.min(shutterNs, perFrame, WotaTiers.SHUTTER_CAP_NS);
  if (!forceDeviceRange) {
    if (exposureMaxNs > 0) {
      value = Math.min(value, exposureMaxNs);
    }
    if (exposureMinNs > 0) {
      value = Math.max(value, exposureMinNs);
    }
  }
  return value <= 0 ? 1 : value;
}

/** 手动曝光必须同帧给出的帧周期：1e9/fps */
export function frameDurationNs(fps: number): number {
  return Math.floor(WotaTiers.NS_PER_SECOND / Math.max(1, Math.floor(fps)));
}

/**
 * fps 档位 → 实际写入的范围。固定 [f,f] 优先（录像时间戳稳定的前提），无固定档时由 core 落
 * 「包含 f 且上界最大」的可变范围并标 exact=false；高速分支走 core 的 pickHighSpeedFpsRange
 * （**只用机器声明过的区间**，下界贴目标者优先——绝不自造 [f,f]，上游 2026-10-26 口径）。
 * 返回 undefined 表示设备没有任何范围覆盖 target（引擎按「本机做不到这一档」处理并明确失败，
 * 不静默跑另一个帧率——那会让 params.fps 与成片 fps 不一致）。
 * OHOS 侧 FpsPick 仅留作 State/dump（预览档 profile 固定，不逐帧下发，登记）。
 */
export function pickFps(ranges: Array<RangeI>, target: number, highSpeed: boolean): FpsPick | undefined {
  if (target <= 0) {
    return undefined;
  }
  return highSpeed ? pickHighSpeedFpsRange(ranges, target) : pickFpsRange(ranges, target);
}

/**
 * 闪光方案：ON→常闪、AUTO→自动预闪、TORCH→AE ON + 常亮（官方语义，不做反向映射）。
 * 手动曝光（AE OFF 等价）下无法用 AE 驱动的闪光模式，退化为 FLASH=OFF，torch 仍保留。
 */
export function flashPlan(flash: Flash, flashAvailable: boolean, manualAe: boolean): FlashPlan {
  if (!flashAvailable) {
    return new FlashPlan(FlashAe.ON, false);
  }
  if (manualAe) {
    return new FlashPlan(FlashAe.ON, flash === Flash.TORCH);
  }
  switch (flash) {
    case Flash.OFF: return new FlashPlan(FlashAe.ON, false);
    case Flash.ON: return new FlashPlan(FlashAe.ON_ALWAYS_FLASH, false);
    case Flash.AUTO: return new FlashPlan(FlashAe.ON_AUTO_FLASH, false);
    case Flash.TORCH: return new FlashPlan(FlashAe.ON, true);
    default: return new FlashPlan(FlashAe.ON, false);
  }
}

/**
 * 防抖三门控：能力门控 / 4K 禁 EIS / 高帧率会话整体不下发。
 * 「显式关闭写 OFF 而不是不写」体现为返回 false 而不是 undefined。
 */
export function stabilizePlan(
  stabilize: Stabilize,
  oisAvailable: boolean,
  eisAvailable: boolean,
  eisBlockedBySize: boolean,
  highSpeed: boolean
): StabilizePlan {
  if (highSpeed) {
    return new StabilizePlan(undefined, undefined);
  }
  const wantsOis: boolean = stabilize === Stabilize.OIS || stabilize === Stabilize.OIS_EIS;
  const wantsEis: boolean = stabilize === Stabilize.EIS || stabilize === Stabilize.OIS_EIS;
  const ois: boolean | undefined = oisAvailable ? wantsOis : undefined;
  // 4K 下即使用户选了 EIS 也不下发该键（带宽不足，HAL 会静默降帧）
  const eis: boolean | undefined = eisAvailable && !(wantsEis && eisBlockedBySize) ? wantsEis : undefined;
  return new StabilizePlan(ois, eis);
}

/** 曝光组一次下发的完整键值计划（对应上游 ExposurePlan；OHOS 下发只用 aeComp/sensitivity/exposureNs） */
export interface ExposurePlanArgs {
  aeLock: boolean;
  aeMode: number;
  aeComp: number;
  sensitivity: number;
  exposureNs: number;
  frameDurationNs: number;
}

export class ExposurePlan {
  aeLock: boolean;
  aeMode: number;
  aeComp: number;
  sensitivity: number;
  exposureNs: number;
  frameDurationNs: number;

  constructor(plan: ExposurePlanArgs) {
    this.aeLock = plan.aeLock;
    this.aeMode = plan.aeMode;
    this.aeComp = plan.aeComp;
    this.sensitivity = plan.sensitivity;
    this.exposureNs = plan.exposureNs;
    this.frameDurationNs = plan.frameDurationNs;
  }
}

/**
 * 曝光组键值计划（纯函数）。帧周期/快门钳制的基准 = **用户档位**（Snapshot.fpsTarget，
 * 上游 2026-10-26 对齐）：普通档 [24,30] 选 24 时 lo==目标，行为与旧的「吃范围下界」一致；
 * 可变高速档（vivo 50 → [30,120]，lo=30 < 目标 50）必须回到目标，否则帧周期 33.3ms
 * 超过 50fps 的 20ms，快门梯放出一个必定被压回的死档。
 * 兜底链：两个来源都拿不到（异常态）才退 HIGH_SPEED_FPS。
 * manual 时 aeComp=0（AE 关闭下补偿无语义）。
 */
export function exposurePlan(state: State, p: Snapshot): ExposurePlan {
  const manual: boolean = p.aeMode === AeMode.MANUAL;
  const fpsBase: number = Math.max(p.fpsTarget, p.fps !== undefined ? p.fps.lo : 0);
  const fps: number = fpsBase > 0 ? fpsBase : WotaTiers.HIGH_SPEED_FPS;
  return new ExposurePlan({
    aeLock: !manual && p.aeMode === AeMode.LOCK,
    aeMode: manual ? AE_MODE_OFF : AE_MODE_ON,
    aeComp: manual ? 0 : state.clampEv(p.evSteps),
    sensitivity: state.clampIso(p.iso),
    exposureNs: clampShutterNs(
      p.shutterNs, fps, state.exposureMinNs, state.exposureMaxNs,
      // 强制快门档（1/24、1/25）：设备曝光范围容不下也按该值下发
      WotaTiers.isRequiredShutterNs(p.shutterNs)
    ),
    frameDurationNs: frameDurationNs(fps)
  });
}

/** 从参数总线取一帧快照（StateFlow .value；fps 经 pickFps 落档；fpsTarget = 用户档位，钳制基准） */
export function snapshotOf(params: WotaParams, state: State): Snapshot {
  const fpsPick: FpsPick | undefined = pickFps(state.fpsRanges, params.fps.value.value, state.highSpeed);
  return new Snapshot({
    aeMode: params.aeMode.value,
    iso: params.iso.value.value,
    shutterNs: params.shutter.value.value,
    evSteps: params.ev.value.value,
    fps: fpsPick,
    fpsTarget: params.fps.value.value,
    afMode: params.afMode.value,
    focusLocked: params.focusLocked.value,
    focusLockPoint: params.focusLockPoint.value,
    focusDiopter: params.manualFocusDiopter.value.value,
    zoom: params.zoom.value.value,
    wbPreset: params.wbMode.value,
    kelvin: params.kelvin.value.value,
    tint: params.tint.value.value,
    flash: params.flash.value,
    stabilize: params.stabilize.value,
    tapPoint: params.focusPoint.value
  });
}

// ---------------------------------------------------------------- 下发适配（OHOS 会话）
// （import 已按 ArkTS 约束全部置顶；本分区起只碰 camera 类型与 Diag）

/** 一次 apply 逐键结果（key 名即报告口径） */
export class ApplyReport {
  applied: Array<string> = [];
  skipped: Array<string> = [];
  failed: Array<string> = [];

  summary(): string {
    return `applied=${this.applied.length}[${this.applied.join(',')}]` +
      ` skipped=${this.skipped.length}[${this.skipped.join('|')}]` +
      ` failed=${this.failed.length}[${this.failed.join('|')}]`;
  }
}

/**
 * FlashPlan → OHOS FlashMode（消费纯 flashPlan 的五分支结果，不再绕过可用性判断）：
 * 无灯（flashPlan 退化为 ON/false）→ CLOSE；手动曝光非 TORCH → CLOSE；TORCH → ALWAYS_OPEN；
 * ON → FLASH_MODE_OPEN；AUTO → FLASH_MODE_AUTO。
 */
function flashModeOfPlan(plan: FlashPlan, manualAe: boolean): camera.FlashMode {
  if (manualAe && !plan.torch) {
    return camera.FlashMode.FLASH_MODE_CLOSE;
  }
  if (plan.torch) {
    return camera.FlashMode.FLASH_MODE_ALWAYS_OPEN;
  }
  switch (plan.ae) {
    case FlashAe.ON_ALWAYS_FLASH: return camera.FlashMode.FLASH_MODE_OPEN;
    case FlashAe.ON_AUTO_FLASH: return camera.FlashMode.FLASH_MODE_AUTO;
    default: return camera.FlashMode.FLASH_MODE_CLOSE;
  }
}

/**
 * 把一帧参数快照写进会话（逐键 try/catch：单键失败进 failed 桶，不中断其余键）。
 * 曝光组按档语义：**仅 MANUAL 写 SENSOR 键**（时长 µs + ISO）并把 EV 归零；
 * LOCK 只写 ExposureMode=LOCKED（冻结当前 AE 曝光——OHOS LOCKED 下 setIso 实际生效，
 * 写快照值会跳档，故不写，登记）；AUTO 只写补偿。
 * 单位：plan.exposureNs 是 ns（core 契约），setExposureDuration 要 µs → /1000。
 * 不移植键名以 skipped 自曝：tint（无 RGB 增益接口）、eis（PhotoSession 无接口，按用户意图判定）。
 */
export function applySnapshot(session: camera.PhotoSession, state: State, p: Snapshot): ApplyReport {
  const report: ApplyReport = new ApplyReport();
  const plan: ExposurePlan = exposurePlan(state, p);
  const manual: boolean = p.aeMode === AeMode.MANUAL;

  // 1. 曝光：MANUAL 写全 SENSOR 键（时长+ISO+EV 归零）；LOCK/AUTO 只写模式与补偿。
  // LOCK 语义 = 冻结当前 AE 曝光（上游 AE_LOCK=true + AE_MODE_ON，SENSOR 键在 AE 开启下被 HAL
  // 忽略；而 OHOS EXPOSURE_MODE_LOCKED 下 setIso 实际生效，写快照值会把曝光跳档——故 LOCK 不写
  // SENSOR 键，登记）。
  try {
    session.setExposureMode(exposureModeFor(p.aeMode));
    report.applied.push('exposureMode');
  } catch (e) {
    report.failed.push(`exposureMode(${e})`);
  }
  if (manual) {
    try {
      // ns → µs（Math.max(1, …)：亚微秒钳到 1µs，避免 0 被策略拒绝）
      session.setExposureDuration(Math.max(1, Math.round(plan.exposureNs / 1000)));
      report.applied.push('exposureDuration');
    } catch (e) {
      report.failed.push(`exposureDuration(${e})`);
    }
    try {
      session.setIso(plan.sensitivity);
      report.applied.push('iso');
    } catch (e) {
      report.failed.push(`iso(${e})`);
    }
    // 上游 aeComp=0：手动档显式归零，清掉上一档 EV 残值
    try {
      session.setExposureBias(0);
      report.applied.push('exposureBias(manual=0)');
    } catch (e) {
      report.failed.push(`exposureBias(${e})`);
    }
  } else if (state.evStep > 0) {
    try {
      session.setExposureBias(plan.aeComp * state.evStep);
      report.applied.push('exposureBias');
    } catch (e) {
      report.failed.push(`exposureBias(${e})`);
    }
  } else {
    // 步长不可读（6.0 设备无该 API）：不拿 1 猜，自曝跳过
    report.skipped.push('exposureBias: no evStep');
  }

  // 2. 对焦：模式 + 点按（tapPoint 同时驱动 focusPoint 与 meteringPoint）+ 手动对焦距离。
  // FL 批：focusLocked = 对焦锁定（上游 engageFocusLock 的 OHOS 对应物）——
  // OHOS 原生 FOCUS_MODE_LOCKED（镜头停在合焦位，不排恢复），锚点取 focusLockPoint
  // （缺省画面中心）；锁定时 tapPoint 语义让位（轻触/双击不重焦由页面手势门控）。
  // 能力闸（进闸现读）：session.isFocusModeSupported(LOCKED) 为 false → skipped（拒绝假开关）。
  if (p.focusLocked) {
    try {
      if (!session.isFocusModeSupported(camera.FocusMode.FOCUS_MODE_LOCKED)) {
        report.skipped.push('focusLock: LOCKED not supported');
      } else {
        session.setFocusMode(camera.FocusMode.FOCUS_MODE_LOCKED);
        report.applied.push('focusMode(locked)');
        if (p.focusLockPoint !== undefined) {
          const lockPt: camera.Point = { x: p.focusLockPoint.x, y: p.focusLockPoint.y };
          try {
            session.setFocusPoint(lockPt);
            report.applied.push('focusPoint(locked)');
          } catch (e) {
            report.failed.push(`focusPoint(locked)(${e})`);
          }
        }
      }
    } catch (e) {
      report.failed.push(`focusLock(${e})`);
    }
  } else {
  try {
    session.setFocusMode(focusModeFor(p.afMode));
    report.applied.push('focusMode');
  } catch (e) {
    report.failed.push(`focusMode(${e})`);
  }
  if (p.afMode === AfMode.MANUAL) {
    if (!state.focusDistanceSupported) {
      // 能力探针（refine 的 isFocusDistanceSupported）为 false/不可得（6.0 无该 API）：
      // skipped 而非 failed——manual 仅切模式，距离留待 API24 设备
      report.skipped.push('focusDistance: not supported (probe/API24 gate)');
    } else if (state.manualFocusMaxDiopter <= 0) {
      report.skipped.push('focusDistance: no manual range on this lens');
    } else {
      // 屈光度 → setFocusDistance 归一化域：0.0 最近 / 1.0 最远（d.ts），diopter 越大越近
      try {
        const maxDiopter: number = state.manualFocusMaxDiopter;
        const clamped: number = Math.min(maxDiopter, Math.max(0, p.focusDiopter));
        const distance: number = 1 - clamped / maxDiopter;
        // @SuppressWarnings compatibility
        session.setFocusDistance(distance);
        report.applied.push('focusDistance(manual)');
      } catch (e) {
        // 探针通过但 setter 仍抛错：真失败进 failed 桶（真机取证可见）
        report.failed.push(`focusDistance(${e})`);
      }
    }
  }
  }
    if (p.tapPoint !== undefined) {
      const pt: camera.Point = { x: p.tapPoint.x, y: p.tapPoint.y };
      try {
        session.setFocusPoint(pt);
        report.applied.push('focusPoint');
      } catch (e) {
        report.failed.push(`focusPoint(${e})`);
      }
      try {
        session.setMeteringPoint(pt);
        report.applied.push('meteringPoint');
      } catch (e) {
        report.failed.push(`meteringPoint(${e})`);
      }
    }

  // 3. 变焦（OHOS 恒 ratio 路径，crop 不移植）
  try {
    session.setZoomRatio(Math.min(state.zoomMax, Math.max(state.zoomMin, p.zoom)));
    report.applied.push('zoom');
  } catch (e) {
    report.failed.push(`zoom(${e})`);
  }

  // 4. 白平衡：MANUAL → 模式与色温分两个 try（半状态在报告里可辨）；否则预设档。
  // tint 无 OHOS 等价（skipped 自曝）。MANUAL 先过探测门（ab73ebf/ee9aa0e 口径）：
  // 设备没声明手动档就不硬发（总线侧已由 applyWhiteBalance 降档，这里是防御纵深）。
  if (p.wbPreset === WbPreset.MANUAL) {
    if (!state.wbManualSupported) {
      report.skipped.push('whiteBalanceManual: mode unsupported by device probe');
    } else {
      try {
        session.setWhiteBalanceMode(camera.WhiteBalanceMode.MANUAL);
        report.applied.push('whiteBalanceMode(MANUAL)');
      } catch (e) {
        report.failed.push(`whiteBalanceMode(${e})`);
      }
      try {
        session.setWhiteBalance(p.kelvin);
        report.applied.push('whiteBalanceKelvin');
      } catch (e) {
        report.failed.push(`whiteBalanceKelvin(${e})`);
      }
    }
  } else {
    try {
      session.setWhiteBalanceMode(wbModeFor(p.wbPreset));
      report.applied.push('whiteBalanceMode');
    } catch (e) {
      report.failed.push(`whiteBalanceMode(${e})`);
    }
  }
  report.skipped.push('tint: no OHOS equivalent');

  // 5. 闪光：由纯 flashPlan 驱动（可用性/手动退化/torch 全走 plan，不再绕过 state.flashAvailable）
  const fp: FlashPlan = flashPlan(p.flash, state.flashAvailable, manual);
  if (!state.flashAvailable) {
    report.skipped.push('flash: not available');
  }
  try {
    session.setFlashMode(flashModeOfPlan(fp, manual));
    report.applied.push('flashMode');
  } catch (e) {
    report.failed.push(`flashMode(${e})`);
  }

  // 6. 防抖：OIS 走 setOISMode；EIS 属 VideoSession 参数面——按**用户意图**判定是否自曝，
  // 并如实说明原因（PhotoSession 未暴露 EIS 参数面 ≠ 镜头不支持；能力压根未被探测）
  const sp: StabilizePlan = stabilizePlan(
    p.stabilize, state.oisAvailable, state.eisAvailable, state.eisBlockedBySize, state.highSpeed);
  if (sp.oisOn !== undefined) {
    try {
      session.setOISMode(oisModeFor(sp.oisOn));
      report.applied.push('oisMode');
    } catch (e) {
      report.failed.push(`oisMode(${e})`);
    }
  }
  const wantsEis: boolean = p.stabilize === Stabilize.EIS || p.stabilize === Stabilize.OIS_EIS;
  if (wantsEis) {
    if (state.highSpeed) {
      report.skipped.push('eis: high-speed session (no EIS key)');
    } else if (state.eisBlockedBySize) {
      report.skipped.push('eis: 4K gate (>=3840x2160)');
    } else {
      report.skipped.push('eis: not exposed by PhotoSession (ability unprobed)');
    }
  }

  Diag.i(WotaTag.ABILITY, `apply ${report.summary()}`);
  return report;
}
