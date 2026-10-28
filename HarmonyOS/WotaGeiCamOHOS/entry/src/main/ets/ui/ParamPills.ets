/**
 * 高级参数胶囊弹层——上游 ui/CameraPills.kt 的 AeModeRow/Shutter/Iso/Ev/Wb 五件直译
 * （对齐上游 38d712a，对拍段 :236-628）。
 *
 * 本批边界（登记 PORT_MAP §ui）：
 * - ZOOM 为 Dock 直循环胶囊（无弹层）；FOCUS 弹层已落（FC 批 2026-10-24）→
 *   RECORDING_EDITABLE_PILLS = {ISO, EV, WB, FOCUS}（对齐上游 {ISO,EV,WB,ZOOM,FOCUS} 减 ZOOM——
 *   本侧变焦无弹层，录制门控不适用）；
 * - 快门弹层不做快捷 chip（上游 2026-10-17 已删），直接对齐刻度滑杆最终形态；
 * - WbPill 只做预设 + 色温（tint 副轴**平台无等价物不迁**：OHOS setWhiteBalance 只吃 kelvin，
 *   无 RGB 增益接口——造假滑杆比没有更糟，登记 PORT_MAP）；
 * - 高级参数不持久化（上游 ParamsPersist 留白），会话内有效；
 * - 每次改动经 onApply 回调上抛，由 Camera 页 engine.applyParams 下发并汇总报告。
 */
import { AeMode, AfMode, WbPreset, WbPresets, WotaTiers } from '../core/Constants';
import { CameraAbility, FpsPick } from '../core/CameraAbility';
import { ParamState, WotaParams } from '../core/WotaParams';
import { evLadder, isoLadder, kelvinLadder, shutterLadder, diopterLadder } from './SliderLadder';
import { TierItem, aeLabel, evText, shutterItems, shutterText, wbLabel } from './ParamFormat';
import { PillChoices, PillNote, PillOption, PillRect, PillSlider, WotaPillPopup } from './PillPopup';
import { SliderKey } from './SliderSnap';

/**
 * 录制中仍可调参的白名单（对拍上游 CameraPills.kt:104 RECORDING_EDITABLE_PILLS）：
 * 其余胶囊录制中可开窗但控件只读。单点定义——弹窗控件闸引用这一张表。
 * 与上游差异：上游含 ZOOM/FOCUS，本批未移植这两颗胶囊（登记 PORT_MAP §ui）。
 */
export const RECORDING_EDITABLE_PILLS: Set<string> = new Set<string>(['iso', 'ev', 'wb', 'focus']);

/** 录制中该弹窗是否可调（非录制态由调用方直接判 editing 前置，与上游同形） */
export function pillEditableDuringRecording(key: string): boolean {
  return RECORDING_EDITABLE_PILLS.has(key);
}

/** 复制 ParamState 仅换值（Kotlin data class copy(value=) 的等价） */
function withValue(prev: ParamState, value: number): ParamState {
  return new ParamState(value, prev.range, prev.enabled, prev.exact);
}

/**
 * AE 模式行：曝光三件套共用，手动档只在真的能手动时才给选；
 * active 供录制中只读弹窗整行禁用。档位切换经 onModeChange 上抛——
 * 弹层内「快门滑杆只在 MANUAL 出现 / ISO 只在 MANUAL 可调 / EV 反之」的
 * 分支可见性都靠父组件的 @State 跟随这一回调刷新。
 */
@Component
export struct AeModeRow {
  params: WotaParams = new WotaParams();
  manualPossible: boolean = false;
  active: boolean = true;
  onApply: () => void = (): void => {};
  onModeChange: (m: AeMode) => void = (_m: AeMode): void => {};
  @State mode: number = 0;

  aboutToAppear(): void {
    this.mode = this.params.aeMode.value as number;
  }

  build() {
    PillChoices({
      options: [
        new PillOption(AeMode.AUTO as number, aeLabel(AeMode.AUTO), true),
        new PillOption(AeMode.MANUAL as number, aeLabel(AeMode.MANUAL), this.manualPossible),
        new PillOption(AeMode.LOCK as number, aeLabel(AeMode.LOCK), true)
      ],
      selected: this.mode,
      groupLabel: '曝光模式',
      perRow: 3,
      active: this.active,
      onPick: (opt: PillOption): void => {
        const next: AeMode = opt.value as AeMode;
        this.mode = opt.value;
        this.params.aeMode.value = next;
        this.onModeChange(next);
        this.onApply();
      }
    })
  }
}

/**
 * 快门弹层：梯只收 supported 档（被设备否决的档消失而不是灰 chip）；
 * 标签仍来自 shutterItems，所以强制档 1/24、1/25 的 ※ 保留。
 * 档位可选性吃帧率档位（fpsHi = pick.lo，与下发侧 exposurePlan 同源）。
 * 快门不在录制可调白名单：录制中开窗只读（Note + 控件全禁用）。
 */
@Component
export struct ShutterPillPopup {
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  params: WotaParams = new WotaParams();
  ability: CameraAbility | undefined = undefined;
  editable: boolean = true;
  onClose: () => void = (): void => {};
  onApply: () => void = (): void => {};
  @State aeMode: AeMode = AeMode.AUTO;
  @State shutterNs: number = 0;
  private loNs: number = 0;
  private hiNs: number = 0;
  private hasRange: boolean = false;
  private ladder: Array<number> = [];
  /** 梯值 → 原始档（上游按 Float 键反查原始 ns；ArkTS number 是双精度无损，结构照搬） */
  private byTier: Map<number, TierItem> = new Map<number, TierItem>();

  aboutToAppear(): void {
    this.aeMode = this.params.aeMode.value;
    this.shutterNs = this.params.shutter.value.value;
    const range = this.params.shutter.value.range;
    if (range !== undefined) {
      this.loNs = range.lo;
      this.hiNs = range.hi;
      this.hasRange = range.hi > range.lo;
    }
    // fpsHi 口径（上游 2026-10-26 对齐）：钳制基准 = **用户档位**（max(pick.lo, 目标)）——
    // 普通档 [24,30] 选 24 时 pick.lo==目标，行为不变；可变高速档（50 → [30,120]，lo=30）
    // 必须回到目标 50，否则快门梯放出一个引擎必定压回的 1/30 死档。取不到该档时退回用户档位。
    const targetFps: number = this.params.fps.value.value;
    const pick: FpsPick | undefined =
      this.ability === undefined ? undefined : this.ability.decideFps(this.params.size.value, targetFps)?.pick;
    const fpsHi: number = pick !== undefined ? Math.max(pick.lo, targetFps) : targetFps;
    const items: Array<TierItem> = shutterItems(
      this.ability === undefined ? undefined : this.ability.exposureNs, fpsHi, '※')
      .filter((t: TierItem): boolean => t.supported);
    for (const t of items) {
      this.byTier.set(t.value, t);
    }
    if (this.hasRange) {
      this.ladder = shutterLadder(items.map((t: TierItem): number => t.value), this.loNs, this.hiNs);
    }
  }

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: '快门速度',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      if (!this.editable) {
        PillNote({ noteText: '录制中不可调整，停止后可改' })
      }
      AeModeRow({
        params: this.params,
        manualPossible: this.ability !== undefined && this.ability.manualExposurePossible(),
        active: this.editable,
        onApply: (): void => {
          this.onApply();
        },
        onModeChange: (m: AeMode): void => {
          this.aeMode = m;
        }
      })
      if (this.aeMode === AeMode.MANUAL) {
        if (this.hasRange) {
          // 梯不足 2 点（无 supported 档）时 PillSlider 内部走禁用占位（不退回连续滑杆）
          PillSlider({
            current: this.shutterNs,
            lo: this.loNs,
            hi: this.hiNs,
            snapKey: SliderKey.SHUTTER,
            step: 1000,
            ladder: this.ladder,
            active: this.editable,
            onValueChange: (tick: number): void => {
              // 写回/查标签都经梯值反查原始档，避免精度/强制档判据（isRequiredShutterNs）失配
              const exact: TierItem | undefined = this.byTier.get(tick);
              const ns: number = exact !== undefined ? exact.value : Math.round(tick);
              this.shutterNs = ns;
              this.params.shutter.value = withValue(this.params.shutter.value, ns);
              this.onApply();
            },
            formatLabel: (tick: number): string => {
              const exact: TierItem | undefined = this.byTier.get(tick);
              return exact !== undefined ? exact.label : shutterText(Math.round(tick));
            }
          })
        }
      } else {
        PillNote({ noteText: '切到手动曝光档后可调' })
      }
    }
  }
}

/** ISO 弹层：梯 = 1/3 档标准序列按设备范围过滤；仅 MANUAL 可调（AE 档下滑杆禁用） */
@Component
export struct IsoPillPopup {
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  params: WotaParams = new WotaParams();
  ability: CameraAbility | undefined = undefined;
  editable: boolean = true;
  onClose: () => void = (): void => {};
  onApply: () => void = (): void => {};
  @State aeMode: AeMode = AeMode.AUTO;
  @State iso: number = 0;
  private lo: number = 0;
  private hi: number = 0;
  private hasRange: boolean = false;
  private ladder: Array<number> = [];

  aboutToAppear(): void {
    this.aeMode = this.params.aeMode.value;
    this.iso = this.params.iso.value.value;
    const range = this.params.iso.value.range;
    if (range !== undefined) {
      this.lo = range.lo;
      this.hi = range.hi;
      this.hasRange = this.hi > this.lo;
    }
    if (this.hasRange) {
      this.ladder = isoLadder(this.lo, this.hi);
    }
  }

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: '感光度',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      AeModeRow({
        params: this.params,
        manualPossible: this.ability !== undefined && this.ability.manualExposurePossible(),
        active: this.editable,
        onApply: (): void => {
          this.onApply();
        },
        onModeChange: (m: AeMode): void => {
          this.aeMode = m;
        }
      })
      if (this.hasRange) {
        PillSlider({
          current: this.iso,
          lo: this.lo,
          hi: this.hi,
          snapKey: SliderKey.ISO,
          step: 1,
          ladder: this.ladder,
          active: this.editable && this.aeMode === AeMode.MANUAL,
          onValueChange: (v: number): void => {
            const value: number = Math.round(v);
            this.iso = value;
            this.params.iso.value = withValue(this.params.iso.value, value);
            this.onApply();
          },
          formatLabel: (v: number): string => `${Math.round(v)}`
        })
      } else {
        PillNote({ noteText: '本机不支持' })
      }
    }
  }
}

/**
 * EV 弹层：补偿只在 AE 参与档（AUTO/LOCK）有意义——MANUAL 下显只读注记
 * （上游 Note(cam_auto_exposure_only)）；梯 = 设备每个整数补偿步一档。
 */
@Component
export struct EvPillPopup {
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  params: WotaParams = new WotaParams();
  ability: CameraAbility | undefined = undefined;
  editable: boolean = true;
  onClose: () => void = (): void => {};
  onApply: () => void = (): void => {};
  @State aeMode: AeMode = AeMode.AUTO;
  @State ev: number = 0;
  private lo: number = 0;
  private hi: number = 0;
  private hasRange: boolean = false;
  private ladder: Array<number> = [];
  private evStep: number = 1;

  aboutToAppear(): void {
    this.aeMode = this.params.aeMode.value;
    this.ev = this.params.ev.value.value;
    // evStep ≤0/不可读 → 不猜 1 用于下发；显示格式化按上游口径回落 1
    this.evStep = this.ability !== undefined && this.ability.evStep > 0 ? this.ability.evStep : 1;
    const range = this.params.ev.value.range;
    if (range !== undefined) {
      this.lo = range.lo;
      this.hi = range.hi;
      this.hasRange = this.hi > this.lo;
    }
    if (this.hasRange) {
      this.ladder = evLadder(this.lo, this.hi);
    }
  }

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: '曝光补偿',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      AeModeRow({
        params: this.params,
        manualPossible: this.ability !== undefined && this.ability.manualExposurePossible(),
        active: this.editable,
        onApply: (): void => {
          this.onApply();
        },
        onModeChange: (m: AeMode): void => {
          this.aeMode = m;
        }
      })
      if (!this.hasRange) {
        PillNote({ noteText: '本机不支持' })
      } else if (this.aeMode === AeMode.MANUAL) {
        PillNote({ noteText: '自动曝光档下生效' })
      } else {
        PillSlider({
          current: this.ev,
          lo: this.lo,
          hi: this.hi,
          // EV 是整数域（每个整数一档），连续模式与梯等价，不提供开关
          snapKey: null,
          ladder: this.ladder,
          active: this.editable,
          onValueChange: (v: number): void => {
            const value: number = Math.round(v);
            this.ev = value;
            this.params.ev.value = withValue(this.params.ev.value, value);
            this.onApply();
          },
          formatLabel: (v: number): string => evText(Math.round(v), this.evStep)
        })
      }
    }
  }
}

/**
 * 白平衡弹层：预设网格（MANUAL 恒可选；其余档吃 awbModes 能力）+
 * MANUAL 时色温滑杆（500K 网格梯）。tint 副轴留白（OHOS 无 RGB 增益接口，
 * 下发侧恒 skipped 自曝，登记 RequestApplier 头注释）。
 * WB 属录制中可调白名单：预设行保持可选，否则手动组（色温）不可达。
 */
@Component
export struct WbPillPopup {
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  params: WotaParams = new WotaParams();
  ability: CameraAbility | undefined = undefined;
  editable: boolean = true;
  onClose: () => void = (): void => {};
  onApply: () => void = (): void => {};
  @State wbMode: WbPreset = WbPreset.AUTO;
  @State kelvin: number = 5500;
  private kelvinLo: number = WotaTiers.KELVIN_MIN;
  private kelvinHi: number = WotaTiers.KELVIN_MAX;
  private ladder: Array<number> = [];
  private wbOptions: Array<PillOption> = [];

  aboutToAppear(): void {
    this.wbMode = this.params.wbMode.value;
    this.kelvin = this.params.kelvin.value.value;
    const range = this.params.kelvin.value.range;
    if (range !== undefined) {
      this.kelvinLo = range.lo;
      this.kelvinHi = range.hi;
    }
    this.ladder = kelvinLadder(this.kelvinLo, this.kelvinHi);
    // 能力未知（ability undefined）时预设按可选处理（上游 awbModes?.contains != false 同义）；
    // MANUAL 例外（2026-10-28 对齐 ab73ebf/ee9aa0e）：**必须经 manualWhiteBalancePossible 探测**
    // ——设备不声明手动档就不再摆可点选项（点了会下发被拒/错误状态），灰显 + 点不动。
    const options: Array<PillOption> = [];
    for (const p of WbPresets.ALL) {
      const supported: boolean = p === WbPreset.MANUAL
        ? this.ability === undefined || this.ability.manualWhiteBalancePossible()
        : this.ability === undefined || this.ability.awbModes.has(WbPresets.awbMode(p));
      options.push(new PillOption(p as number, wbLabel(p), supported));
    }
    this.wbOptions = options;
  }

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: '白平衡',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      PillChoices({
        options: this.wbOptions,
        selected: this.wbMode as number,
        perRow: 4,
        active: this.editable,
        onPick: (opt: PillOption): void => {
          const next: WbPreset = opt.value as WbPreset;
          this.wbMode = next;
          this.params.wbMode.value = next;
          this.onApply();
        }
      })
      if (this.wbMode === WbPreset.MANUAL) {
        PillSlider({
          current: this.kelvin,
          lo: this.kelvinLo,
          hi: this.kelvinHi,
          snapKey: SliderKey.KELVIN,
          step: 50,
          ladder: this.ladder,
          active: this.editable,
          sliderLabel: '色温',
          onValueChange: (v: number): void => {
            const value: number = Math.round(v);
            this.kelvin = value;
            this.params.kelvin.value = withValue(this.params.kelvin.value, value);
            this.onApply();
          },
          formatLabel: (v: number): string => `${Math.round(v)}K`
        })
      }
    }
  }
}

/**
 * 对焦弹层（FC 批，上游 CameraPills 的 FocusPill 对应物）：
 * AF 模式（连续/手动）+ MANUAL 时的屈光度滑杆（diopterLadder，T22 备好）。
 * 手动对焦距离受能力闸：range 缺失（定焦头/探针不支持）时滑杆不显示、只留说明。
 * 录制中可调（RECORDING_EDITABLE_PILLS 含 focus，对齐上游）。
 */
@Component
export struct FocusPillPopup {
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  params: WotaParams = new WotaParams();
  ability: CameraAbility | undefined = undefined;
  editable: boolean = true;
  onClose: () => void = (): void => {};
  onApply: () => void = (): void => {};
  /** FL：锁定/解除开关（宿主页过闸后落总线；缺省空实现防漏挂——但接线守卫会点名） */
  onToggleLock: () => void = (): void => {};
  /** 切入手动档的一次性提示钩子（上游 4457e4a 方案G 最小形态：宿主页提示开峰值辅助） */
  onManualSelected: () => void = (): void => {};
  @State afMode: AfMode = AfMode.CONTINUOUS_VIDEO;
  @State diopter: number = 0;
  private lo: number = 0;
  private hi: number = 0;
  private hasRange: boolean = false;
  private ladder: Array<number> = [];

  aboutToAppear(): void {
    this.afMode = this.params.afMode.value;
    this.diopter = this.params.manualFocusDiopter.value.value;
    const range = this.params.manualFocusDiopter.value.range;
    if (range !== undefined && range.hi > range.lo) {
      this.lo = range.lo;
      this.hi = range.hi;
      this.hasRange = true;
      this.ladder = diopterLadder(this.lo, this.hi);
    }
  }

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: '对焦',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      PillChoices({
        options: [
          new PillOption(AfMode.CONTINUOUS_VIDEO as number, '连续', true),
          new PillOption(AfMode.MANUAL as number, '手动',
            this.hasRange || this.ability === undefined)
        ],
        selected: this.afMode as number,
        perRow: 2,
        active: this.editable,
        onPick: (opt: PillOption): void => {
          const next: AfMode = opt.value as AfMode;
          const enteredManual: boolean = next === AfMode.MANUAL && this.afMode !== AfMode.MANUAL;
          this.afMode = next;
          this.params.afMode.value = next;
          if (enteredManual) {
            this.onManualSelected();
          }
          this.onApply();
        }
      })
      // FL 批：锁定对焦/解除锁定条目（上游「副控制=对焦胶囊里的锁定/解除条目」）。
      // 门禁与落点由宿主页 onToggleLock 闭包裁决（闸：手动档/录制中，同上游 lockFocusAt）；
      // 锁定成功后 afMode 不变——引擎侧 FOCUS_MODE_LOCKED 接管（focusLocked 态门控点按重焦）。
      PillChoices({
        options: [
          new PillOption(1, this.params.focusLocked.value ? '解除锁定' : '锁定对焦', true)
        ],
        selected: -1,
        perRow: 1,
        active: this.editable,
        onPick: (_opt: PillOption): void => {
          this.onToggleLock();
        }
      })
      if (this.afMode === AfMode.MANUAL && this.hasRange) {
        PillSlider({
          current: this.diopter,
          lo: this.lo,
          hi: this.hi,
          snapKey: SliderKey.DIOPTER,
          step: (this.hi - this.lo) / 40,
          ladder: this.ladder,
          active: this.editable,
          sliderLabel: '对焦距离',
          onValueChange: (v: number): void => {
            const value: number = Math.round(v * 10) / 10;
            this.diopter = value;
            this.params.manualFocusDiopter.value = withValue(this.params.manualFocusDiopter.value, value);
            this.onApply();
          },
          formatLabel: (v: number): string => `${(Math.round(v * 10) / 10).toFixed(1)}m⁻¹`
        })
      }
      if (this.afMode === AfMode.MANUAL && !this.hasRange) {
        PillNote({ noteText: '本机镜头不支持手动对焦距离（定焦头或探针不支持），仅切模式。' })
      }
    }
  }
}
