/**
 * Glow（荧光棒光晕）参数面板——上游 GlowPopup（24 R3 §1.5）的 OHOS 直译。
 *
 * 纯 UI 组件：不持总线、不落盘——所有改动经 [onCommit] 上抛（宿主页 Camera 负责下发与
 * GlowStore 持久化），关闭只经 [onClose]。**所有回传值先过 `GlowParams.sanitize()`**：
 * 面板是连续滑杆值的唯一入口，域钳不寄希望于下游。
 *
 * 结构复用 `ui/PillPopup.ets` 的 WotaPillPopup（全屏关闭捕获层 + 量框锚定面板 +
 * 「收起」），参考 `ui/CurvePopup.ets` 的面板形态；内容区自上而下：
 * [说明两行] → [关|开] → [预设 A|B] → [亮度阈值滑杆 + 两位小数读数] → [软边滑杆 + 读数]
 * → [native 批生效注记]。
 *
 * 交互口径：
 * - 关/开只改 enabled，其余字段原样保留；预设 A/B 套用该预设全部字段但**保留当前 enabled**
 *   （开关是用户的显式决定，换色带不该顺手把光晕打开）；
 * - 预设高亮按「除 enabled 外全字段相等」判定（滑杆一动高亮即消失——高亮如实反映当前档）；
 * - 滑杆连续拖动即 onCommit（本批不做节流；上游语义是"调参在下一个真帧生效"）；
 * - 点遮罩 / 「收起」关闭。
 *
 * 平台偏离登记：上游 GlowPopup 的色带两行色点选色（GLOW_COLORS 八点）本批不做——预设 A/B
 * 已覆盖出厂两档，自选色留待后续批（core/Glow.ets 的调色板与 glowColorOf 已备好）。
 * 本文件不 import 任何 @kit（沿用 PillPopup 的现成就位；持久化只在 GlowStore）。
 */
import {
  GLOW_DEFAULT, GLOW_PRESET_A, GLOW_PRESET_B, GLOW_SOFT_MAX, GLOW_THRESHOLD_MAX, GLOW_THRESHOLD_MIN,
  GlowParams
} from '../core/Glow';
import { HudColor, HudType, PageColor } from './HudTokens';
import { PillChoices, PillNote, PillOption, PillRect, WotaPillPopup } from './PillPopup';

/** 除 enabled 外全字段相等（开关是正交维度：换开关不该改变"当前是哪个色带"） */
function glowMatchesIgnoringEnabled(a: GlowParams, b: GlowParams): boolean {
  return a.threshold === b.threshold && a.soft === b.soft && a.width === b.width &&
    a.gain === b.gain && a.add === b.add && a.freq === b.freq && a.blend === b.blend &&
    a.colorA === b.colorA && a.colorB === b.colorB;
}

/** 当前参数命中的预设下标（0=A / 1=B / -1=都不等） */
function glowPresetIndexOf(p: GlowParams): number {
  if (glowMatchesIgnoringEnabled(p, GLOW_PRESET_A)) {
    return 0;
  }
  if (glowMatchesIgnoringEnabled(p, GLOW_PRESET_B)) {
    return 1;
  }
  return -1;
}

/**
 * 连续值滑杆行：label + Slider + 两位小数读数。
 * Glow 的值域（阈值 0..1、软边 0..0.5）本身就是连续量，无梯/无吸附语义，
 * 因此不走 PillSlider 的梯机制，按 PillSlider 的连续分支直绘。
 */
@Component
struct GlowSliderRow {
  label: string = '';
  @Prop current: number = 0;
  lo: number = 0;
  hi: number = 1;
  onValueChange: (v: number) => void = (_v: number): void => {};

  private shownValue(): number {
    if (!(this.hi > this.lo)) {
      return this.current;
    }
    return Math.min(this.hi, Math.max(this.lo, this.current));
  }

  build() {
    Column({ space: 2 }) {
      Text(this.label)
        .fontSize(HudType.LABEL)
        .fontColor(HudColor.TEXT_TERTIARY)
        .maxLines(1)
      Row({ space: 6 }) {
        Slider({
          value: this.shownValue(),
          min: this.lo,
          max: this.hi,
          step: 0.01,
          style: SliderStyle.InSet
        })
          .layoutWeight(1)
          .constraintSize({ maxWidth: 220 })
          .selectedColor(PageColor.ACCENT_BLUE)
          .trackColor(HudColor.DIVIDER)
          .onChange((raw: number, _mode: SliderChangeMode): void => {
            this.onValueChange(raw);
          })
        Text(this.shownValue().toFixed(2))
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_PRIMARY)
          .maxLines(1)
          .constraintSize({ minWidth: 54 })
      }
      .height(40)
    }
  }
}

@Component
export struct GlowPanel {
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  glow: GlowParams = GLOW_DEFAULT;
  /**
   * native 运行期状态行（页面 500ms 读 getGlowStatus → core.glowStatusNote 五键映射的中文）。
   * 空串 = 这帧不显示（关着/链未跑），面板不虚构状态。
   */
  @Prop statusText: string = '';
  /** getGlowStatus 非 off 且 !broken：底部注记的唯一判据（页面算好传进来，面板不碰 native） */
  @Prop chainActive: boolean = false;
  onClose: () => void = (): void => {};
  onCommit: (p: GlowParams) => void = (_p: GlowParams): void => {};

  /** 开关的展示态。不叫 enabled：CustomComponent 自带 enabled(value) 属性方法，同名会撞签名 */
  @State glowOn: boolean = false;
  @State threshold: number = 0.5;
  @State soft: number = 0.05;
  /** 命中预设的下标（@State：套档后刷新高亮；-1 = 手动值/自定义色带） */
  @State presetSel: number = -1;
  /** 面板当前全量参数：面板只直改 enabled/threshold/soft，但换档要整体带过去 */
  private cur: GlowParams = GLOW_DEFAULT;

  aboutToAppear(): void {
    this.adopt(this.glow.sanitize());
  }

  /** 接收一份参数：刷新全量态 + 三个展示态 + 预设高亮（唯一写入点） */
  private adopt(p: GlowParams): void {
    this.cur = p;
    this.glowOn = p.enabled;
    this.threshold = p.threshold;
    this.soft = p.soft;
    this.presetSel = glowPresetIndexOf(p);
  }

  /** 以面板现值做底只换显式改的字段（Kotlin copy(value=) 的等价物——ArkTS 无对象展开） */
  private withEdits(on: boolean, threshold: number, soft: number): GlowParams {
    return new GlowParams({
      enabled: on,
      threshold: threshold,
      soft: soft,
      width: this.cur.width,
      gain: this.cur.gain,
      add: this.cur.add,
      freq: this.cur.freq,
      blend: this.cur.blend,
      colorA: this.cur.colorA,
      colorB: this.cur.colorB
    });
  }

  /** 域钳后回传（先落面板态再上抛，宿主页拿到的就是屏上所见） */
  private commit(next: GlowParams): void {
    this.adopt(next.sanitize());
    this.onCommit(this.cur);
  }

  private setEnabled(on: boolean): void {
    this.commit(this.withEdits(on, this.threshold, this.soft));
  }

  private applyPreset(preset: GlowParams): void {
    this.commit(new GlowParams({
      enabled: this.glowOn,
      threshold: preset.threshold,
      soft: preset.soft,
      width: preset.width,
      gain: preset.gain,
      add: preset.add,
      freq: preset.freq,
      blend: preset.blend,
      colorA: preset.colorA,
      colorB: preset.colorB
    }));
  }

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: 'Glow 光晕',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      PillNote({ noteText: '光晕进成片（烘焙进像素），调参在下一个真帧生效' })
      PillChoices({
        options: [
          new PillOption(0, '关', true),
          new PillOption(1, '开', true)
        ],
        selected: this.glowOn ? 1 : 0,
        perRow: 2,
        onPick: (opt: PillOption): void => {
          this.setEnabled(opt.value === 1);
        }
      })
      PillChoices({
        options: [
          new PillOption(0, '预设 A · 品红→青', true),
          new PillOption(1, '预设 B · 橙→绿', true)
        ],
        selected: this.presetSel,
        perRow: 2,
        onPick: (opt: PillOption): void => {
          this.applyPreset(opt.value === 0 ? GLOW_PRESET_A : GLOW_PRESET_B);
        }
      })
      GlowSliderRow({
        label: '亮度阈值',
        current: this.threshold,
        lo: GLOW_THRESHOLD_MIN,
        hi: GLOW_THRESHOLD_MAX,
        onValueChange: (v: number): void => {
          this.commit(this.withEdits(this.glowOn, v, this.soft));
        }
      })
      GlowSliderRow({
        label: '软边',
        current: this.soft,
        lo: 0,
        hi: GLOW_SOFT_MAX,
        onValueChange: (v: number): void => {
          this.commit(this.withEdits(this.glowOn, this.threshold, v));
        }
      })
      // 状态行以 getGlowStatus 解析结果为唯一权威（2026-10-28：与 chainActive 双源曾出现
      // 「渲染链 2856×1320 · 1/2 档」与「渲染链未生效」同屏矛盾，现只留单一来源）。
      PillNote({ noteText: this.statusText !== '' ? this.statusText : '渲染链未生效（参数已保存）' })
    }
  }
}
