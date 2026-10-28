/**
 * 直译自上游 core/WotaParams.kt（harmony 分支）。
 * 平台差异：
 * - Kotlin MutableStateFlow → 本文件提供的 StateFlow 轻量等价（值 + 订阅回调），
 *   UI/相机层消费语义不变：读快照用 .value，跟变化走 subscribe；
 * - ParamState<T: Comparable> 的实际用途全部是数值（Int/Long/Float → number），
 *   统一为 ParamState（number）+ NumRange；
 * - 上游经 ParamsPersist 持久化的键与值域不变（持久化层在 ui/ 侧，另行移植）。
 */
import {
  AeMode, AfMode, AfModes, ArcMergeDeadband, Flash, FrameEffect,
  PeakingColor, RenderMode, Stabilize, TapPoint, WotaTiers, WbPreset, WbPresets, Size
} from './Constants';
import { CameraAbility, FpsDecision, RangeF, shutterCeilingNs } from './CameraAbility';
import { lensTypeName, LensType } from './LensSlot';
import { CurveStack } from './CurveModel';
import { resolveSizeFor, sizeCloseTo, VideoSizeCandidates } from './VideoSizePicker';

export type FlowListener<T> = (value: T) => void;

/** Kotlin MutableStateFlow 的最小等价：当前值 + 订阅；写值即通知 */
export class StateFlow<T> {
  private current: T;
  private listeners: Array<FlowListener<T>> = [];

  constructor(initial: T) {
    this.current = initial;
  }

  get value(): T {
    return this.current;
  }

  set value(v: T) {
    this.current = v;
    const snapshot: Array<FlowListener<T>> = this.listeners.slice();
    for (const l of snapshot) {
      l(v);
    }
  }

  subscribe(listener: FlowListener<T>): () => void {
    this.listeners.push(listener);
    return (): void => {
      this.listeners = this.listeners.filter((l: FlowListener<T>): boolean => l !== listener);
    };
  }
}

/** 数值区间（Kotlin ClosedRange 的数值子域） */
export interface NumRange {
  lo: number;
  hi: number;
}

/**
 * 参数三件套：值 + 范围 + 是否可用（00 文件第 7 节）。
 * range=undefined 表示本机无该能力，enabled=false 时 UI 灰显；
 * exact=false 表示只能给近似值（如 fps 无固定档）。
 */
export class ParamState {
  value: number;
  range: NumRange | undefined;
  enabled: boolean;
  exact: boolean;

  constructor(value: number, range: NumRange | undefined, enabled: boolean, exact: boolean) {
    this.value = value;
    this.range = range;
    this.enabled = enabled;
    this.exact = exact;
  }

  static of(value: number): ParamState {
    return new ParamState(value, undefined, true, true);
  }

  static ofRange(value: number, lo: number, hi: number, exact: boolean = true): ParamState {
    return new ParamState(value, { lo: lo, hi: hi }, true, exact);
  }

  static disabled(value: number): ParamState {
    return new ParamState(value, undefined, false, true);
  }

  /** fps 无任何范围覆盖时的形态：不可用且非精确 */
  static inexactDisabled(value: number): ParamState {
    return new ParamState(value, undefined, false, false);
  }
}

/**
 * 参数总线：每个可调项一个流，UI 只读流、camera 层只读快照。
 * 切镜头调用 applyAbility 重算范围并钳制当前值。
 */
export class WotaParams {
  readonly lens: StateFlow<LensType> = new StateFlow<LensType>(LensType.WIDE);
  readonly renderMode: StateFlow<RenderMode> = new StateFlow<RenderMode>(RenderMode.GPU);
  readonly size: StateFlow<Size> = new StateFlow<Size>(new Size(1920, 1080));
  /**
   * 用户（或出厂默认）要的画幅档，与「当前镜头实际生效的 size」分开记：
   * 换镜头只把这一档在新镜头能力里重新落档，切回来还能恢复原分辨率。
   */
  private requestedSize: Size = new Size(1920, 1080);

  /** UI 选档走这里：同时记意图并立即生效 */
  requestSize(s: Size): void {
    this.requestedSize = s;
    this.size.value = s;
  }

  /** 意图画幅档的只读快照（持久化 flush 写的是它，不是「当前生效的 size」） */
  requestedSizeOf(): Size {
    return this.requestedSize;
  }

  readonly fps: StateFlow<ParamState> = new StateFlow<ParamState>(ParamState.of(25));
  readonly shutter: StateFlow<ParamState> = new StateFlow<ParamState>(ParamState.of(40_000_000)); // ns，1/25
  readonly iso: StateFlow<ParamState> = new StateFlow<ParamState>(ParamState.of(100));
  readonly aeMode: StateFlow<AeMode> = new StateFlow<AeMode>(AeMode.AUTO);
  readonly ev: StateFlow<ParamState> = new StateFlow<ParamState>(ParamState.of(0)); // 步数，非倍率
  readonly afMode: StateFlow<AfMode> = new StateFlow<AfMode>(AfMode.CONTINUOUS_VIDEO);
  readonly manualFocusDiopter: StateFlow<ParamState> = new StateFlow<ParamState>(ParamState.of(0));
  readonly focusPoint: StateFlow<TapPoint | undefined> = new StateFlow<TapPoint | undefined>(undefined);

  /**
   * 对焦锁定（用户反馈第 2 条）：锁定的唯一真源 + 锁定锚点（最近一次点按处，缺省画面中心）。
   * 刻意不持久化：锁定是瞬时拍摄态，冷启/清后台回来不该自动锁焦；
   * 清锁规则见 applyAbility（会话重建保持、相机整套重开清除）。
   */
  readonly focusLocked: StateFlow<boolean> = new StateFlow<boolean>(false);
  readonly focusLockPoint: StateFlow<TapPoint | undefined> = new StateFlow<TapPoint | undefined>(undefined);

  readonly zoom: StateFlow<ParamState> = new StateFlow<ParamState>(ParamState.of(1));
  readonly wbMode: StateFlow<WbPreset> = new StateFlow<WbPreset>(WbPreset.AUTO);
  readonly kelvin: StateFlow<ParamState> = new StateFlow<ParamState>(ParamState.of(5500));
  readonly tint: StateFlow<ParamState> = new StateFlow<ParamState>(ParamState.of(0));
  readonly flash: StateFlow<Flash> = new StateFlow<Flash>(Flash.OFF);
  readonly stabilize: StateFlow<Stabilize> = new StateFlow<Stabilize>(Stabilize.OFF);
  readonly bitrate: StateFlow<number> = new StateFlow<number>(10_000_000);
  readonly sampleRate: StateFlow<number> = new StateFlow<number>(48_000);
  readonly audioEnabled: StateFlow<boolean> = new StateFlow<boolean>(true);
  readonly frameEffect: StateFlow<FrameEffect> = new StateFlow<FrameEffect>(FrameEffect.NONE);
  readonly zebraThreshold: StateFlow<number> = new StateFlow<number>(0.8);
  readonly zebraDensity: StateFlow<number> = new StateFlow<number>(2);
  readonly peakingStrength: StateFlow<number> = new StateFlow<number>(0.6);
  readonly peakingColor: StateFlow<PeakingColor> = new StateFlow<PeakingColor>(PeakingColor.RED);

  /**
   * 补弧合并差分死区 D（8bit luma 级）：Δluma > D 时整颗像素取被抽帧，否则保持保留帧。
   * 默认 12；写入必须走 setArcMergeDeadband（同步发布到 ArcMergeDeadband 供 GL/离线
   * 修复等无参数总线实例的消费方读取），直接改 .value 会让两处读数分叉。
   */
  readonly arcMergeDeadband: StateFlow<number> = new StateFlow<number>(ArcMergeDeadband.DEFAULT);

  setArcMergeDeadband(value: number): void {
    const clamped: number = ArcMergeDeadband.clamp(value);
    this.arcMergeDeadband.value = clamped;
    ArcMergeDeadband.publish(clamped);
  }

  /** RGB 曲线：只走 GPU 渲染链（DIRECT 无片元 pass），恒等即关闭 */
  readonly curve: StateFlow<CurveStack> = new StateFlow<CurveStack>(CurveStack.IDENTITY);
  readonly refLines: StateFlow<number> = new StateFlow<number>(0); // RefLineType 位掩码
  readonly levelEnabled: StateFlow<boolean> = new StateFlow<boolean>(true);

  /**
   * 换挡：按新镜头能力重算 range 并钳制当前值。
   * 顺序有依赖——快门上限要用帧率（≤1/fps），故帧率先算。
   */
  applyAbility(a: CameraAbility): void {
    // 画幅先落档：帧率区间是按尺寸查的，顺序反过来会拿旧尺寸的区间去配新尺寸
    this.applySize(a);
    const targetFps: number = Math.max(1, this.fps.value.value);
    // 一档的完整决议交给 core.decideFps（普通表先来、覆盖不了才看高速表）——UI 的可用性/※、
    // 总线落档、会话选择（KitPreview）与下发钳制四处同源，不再各写一份（上游 2026-10-26 口径）。
    const decision: FpsDecision | undefined = a.decideFps(this.size.value, targetFps);
    this.fps.value = decision === undefined
      ? ParamState.inexactDisabled(targetFps)
      : ParamState.ofRange(targetFps, decision.pick.lo, decision.pick.hi, decision.pick.exact);

    // 快门上限吃「实际生效的帧周期上界」= pick 到的 hi（无固定 [f,f] 时是设备的可变范围上界），
    // 不是用户档位本身：否则用户选 24fps、设备只给 [15,30]（非精确）时，列表会按 1/24 判可选，
    // 下发/回写却按 30fps 帧周期压回 1/30，露出一个点不住的死档位。
    this.applyExposure(a, targetFps);
    this.applyEv(a);
    this.applyZoom(a);
    this.applyFocus(a);
    this.applyWhiteBalance(a);
    this.applyModeGates(a);

    // activeArray 随镜头变化，旧点按坐标语义失效（换算依赖 activeArray）
    this.focusPoint.value = undefined;
    // 对焦锁定清锁（Q4）：换镜头/切渲染模式/离开取景页再回等一切经本函数的
    // 「相机整套重开」都要解锁，锚点随锁一起失效（未锁定时不触碰）。这是清锁的唯一落点。
    if (this.focusLocked.value) {
      this.focusLocked.value = false;
      this.focusLockPoint.value = undefined;
    }
    // 对焦锁定清锁（Q4）：换镜头/切渲染模式等一切经本函数的「相机整套重开」都要解锁，
    // 锚点随锁一起失效。这是清锁的唯一落点。
    if (this.focusLocked.value) {
      this.focusLocked.value = false;
      this.focusLockPoint.value = undefined;
    }
    a.dump(lensTypeName(this.lens.value));
  }

  /**
   * @param targetFps 帧率档位（用户选的那档）。快门上限的帧周期口径与它同源
   *              （与下发侧 exposurePlan 一致：都吃档位、不再吃 range.hi——
   *              可变范围 [24,30] 上选 24，旧口径会把 1/24 压回 1/30，强制档成死档）。
   */
  private applyExposure(a: CameraAbility, targetFps: number): void {
    this.iso.value = a.iso.ok()
      ? ParamState.ofRange(clampN(this.iso.value.value, a.iso.lo, a.iso.hi), a.iso.lo, a.iso.hi)
      : ParamState.disabled(this.iso.value.value);

    // 快门上限吃帧率档位（与下发侧同源）：先把设备上限抬到强制档 1/24、1/25，
    // 再过 1/10s 与 1/帧周期。
    if (a.exposureNs.ok()) {
      const lo: number = a.exposureNs.lo;
      const hi: number = shutterCeilingNs(a.exposureNs.hi, targetFps);
      this.shutter.value = hi >= lo
        ? ParamState.ofRange(clampN(this.shutter.value.value, lo, hi), lo, hi)
        // 设备最短曝光下限已超出一帧周期，该帧率下手动快门无解
        : ParamState.disabled(lo);
    } else {
      this.shutter.value = ParamState.disabled(this.shutter.value.value);
    }
  }

  private applyEv(a: CameraAbility): void {
    this.ev.value = a.evRange.ok()
      ? ParamState.ofRange(clampN(this.ev.value.value, a.evRange.lo, a.evRange.hi), a.evRange.lo, a.evRange.hi)
      : ParamState.disabled(0);
  }

  private applyZoom(a: CameraAbility): void {
    // ZOOM_RATIO 优先（有能力时），否则回退数码变焦上限
    const ratio: RangeF | undefined = a.zoomRatioRange;
    const range: RangeF = ratio !== undefined && ratio.ok()
      ? ratio
      : new RangeF(1, Math.max(1, a.maxDigitalZoom));
    this.zoom.value = range.ok()
      ? ParamState.ofRange(clampN(this.zoom.value.value, range.lo, range.hi), range.lo, range.hi)
      : ParamState.disabled(1);
  }

  private applyFocus(a: CameraAbility): void {
    const maxDiopter: number = a.minFocusDistanceDiopter;
    this.manualFocusDiopter.value = maxDiopter > 0
      ? ParamState.ofRange(clampN(this.manualFocusDiopter.value.value, 0, maxDiopter), 0, maxDiopter)
      : ParamState.disabled(0); // 定焦镜头（前摄常见）无手动对焦能力

    // 换挡口径（用户反馈第 1 条 · 拍板 Q3）：AF 模式能力含当前档就保留，只在能力不含时
    // 才回落 PREFERENCE 首档。无条件回填连续档会把「持久化 AF 模式」恢复的值当场覆盖掉。
    if (a.afModes.size === 0) {
      this.afMode.value = AfMode.MANUAL;
    } else if (a.afModes.has(AfModes.cam2Mode(this.afMode.value))) {
      // 保留当前档
    } else {
      const fallback: AfMode | undefined = AfModes.PREFERENCE.find(
        (m: AfMode): boolean => a.afModes.has(AfModes.cam2Mode(m)));
      this.afMode.value = fallback ?? AfMode.MANUAL;
    }
  }

  private applyWhiteBalance(a: CameraAbility): void {
    // 手动档门控（上游 ab73ebf/ee9aa0e 口径，2026-10-28 修）：探测表里没有 MANUAL 时**绝不
    // 落到手动档**——旧兜底 `?? WbPreset.MANUAL` 会把总线钉到设备不支持的档上（能力假设），
    // 下发 setWhiteBalanceMode(MANUAL) 被拒或落在错误状态。能力未知（空表）时保持既有行为。
    const manualPossible: boolean = a.manualWhiteBalancePossible();
    if (a.awbModes.size > 0 && !a.awbModes.has(WbPresets.awbMode(this.wbMode.value))) {
      const fallback: WbPreset | undefined = manualPossible
        ? WbPresets.ALL.find((p: WbPreset): boolean => a.awbModes.has(WbPresets.awbMode(p)))
        : WbPresets.ALL.find(
          (p: WbPreset): boolean => p !== WbPreset.MANUAL && a.awbModes.has(WbPresets.awbMode(p)));
      this.wbMode.value = fallback ?? (manualPossible ? WbPreset.MANUAL : WbPreset.AUTO);
    }
    if (this.wbMode.value === WbPreset.MANUAL && !manualPossible) {
      // 当前已选手动但本机不可能生效（探测表在且无 MANUAL）：降到第一个受支持预设
      const demote: WbPreset | undefined = WbPresets.ALL.find(
        (p: WbPreset): boolean => p !== WbPreset.MANUAL && a.awbModes.has(WbPresets.awbMode(p)));
      if (demote !== undefined) {
        this.wbMode.value = demote;
      }
    }
    // 色温/色调是产品滑杆域（双轴插值），不随机型变化
    this.kelvin.value = ParamState.ofRange(
      clampN(this.kelvin.value.value, WotaTiers.KELVIN_MIN, WotaTiers.KELVIN_MAX),
      WotaTiers.KELVIN_MIN, WotaTiers.KELVIN_MAX);
    this.tint.value = ParamState.ofRange(
      clampN(this.tint.value.value, WotaTiers.TINT_MIN, WotaTiers.TINT_MAX),
      WotaTiers.TINT_MIN, WotaTiers.TINT_MAX);
  }

  /** 落档到当前镜头能力：只按「用户要的那档」求解 */
  private applySize(a: CameraAbility): void {
    const picked: Size | undefined = resolveSizeFor(this.requestedSize, a.videoSizes);
    if (picked === undefined) {
      return;
    }
    if (!sizeCloseTo(this.size.value, picked, VideoSizeCandidates.TOLERANCE_PX)) {
      this.size.value = picked;
    }
  }

  private applyModeGates(a: CameraAbility): void {
    if (this.aeMode.value === AeMode.MANUAL && !a.manualExposurePossible()) {
      this.aeMode.value = AeMode.AUTO;
    }
    if (!a.flashAvailable) {
      this.flash.value = Flash.OFF;
    }
    switch (this.stabilize.value) {
      case Stabilize.OIS:
        this.stabilize.value = a.oisAvailable ? Stabilize.OIS : Stabilize.OFF;
        break;
      case Stabilize.EIS:
        this.stabilize.value = a.eisAvailable ? Stabilize.EIS : Stabilize.OFF;
        break;
      case Stabilize.OIS_EIS:
        if (a.oisAvailable && a.eisAvailable) {
          this.stabilize.value = Stabilize.OIS_EIS;
        } else if (a.oisAvailable) {
          this.stabilize.value = Stabilize.OIS;
        } else if (a.eisAvailable) {
          this.stabilize.value = Stabilize.EIS;
        } else {
          this.stabilize.value = Stabilize.OFF;
        }
        break;
      default:
        break;
    }
  }
}

function clampN(value: number, lo: number, hi: number): number {
  if (hi < lo) {
    return value;
  }
  if (value < lo) {
    return lo;
  }
  if (value > hi) {
    return hi;
  }
  return value;
}
