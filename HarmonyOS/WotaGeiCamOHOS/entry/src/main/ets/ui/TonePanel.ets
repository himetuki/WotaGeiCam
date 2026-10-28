/**
 * 色调（Tone）面板——安卓 TonePill（29 §4.4-4，截图 r03_tone_panel / r03_tone_shadows2）
 * 的 OHOS 直译。本批四滑杆：黑点 / 白点 / 阴影 / 高光。
 *
 * 纯 UI 组件：不持总线、不落盘——所有改动经 [onCommit] 上抛（宿主页 Camera 负责下发与
 * ToneStore 持久化），关闭只经 [onClose]。**所有回传值先过 `ToneParams.sanitize()`**：
 * 面板是连续滑杆值的唯一入口，域钳不寄希望于下游。
 *
 * 结构复用 `ui/PillPopup.ets` 的 WotaPillPopup（全屏关闭捕获层 + 量框锚定面板 +
 * 「收起」），参考 `ui/GlowPanel.ets` 的面板写法；内容区自上而下：
 * [两行灰色说明] → [黑点滑杆 + 两位小数] → [白点滑杆 + 两位小数]
 * → [阴影滑杆 + ±百分比] → [高光滑杆 + ±百分比]。「收起」由 WotaPillPopup 底部统一提供。
 *
 * 交互口径（对齐安卓 TonePill）：
 * - 黑点/白点产品域 0..0.35、步长 0.01，读数两位小数（安卓 toneFmt `%.2f`）；
 * - 阴影/高光 -100%..100%、步长 5%，读数带符号百分比（安卓 tonePercentFmt `%+.0f%%`）；
 * - 滑杆拖动即 onCommit（值经钳制）；面板内不做节流——宿主语义是「下一真帧生效」；
 * - 点遮罩 / 「收起」关闭。
 *
 * 平台偏离登记：安卓的录制只读态与 DIRECT 灰化提示未接（本批无宿主页接线，接口只暴露
 * onClose/onCommit）；「全部归零」按钮本批不做（截图滑杆形态，归零可由宿主复位）。
 * 本文件不 import 任何 @kit（沿用 PillPopup 的现成就位；持久化只在 ToneStore）。
 */
import {
  TONE_BLACK_MAX, TONE_DEFAULT, TONE_HIGHLIGHTS_MAX, TONE_LEVEL_MIN, TONE_SHADOWS_MAX, TONE_WHITE_MAX,
  ToneParams
} from '../core/Tone';
import { HudColor, HudType, PageColor } from './HudTokens';
import { PillNote, PillRect, WotaPillPopup } from './PillPopup';

/**
 * 连续值滑杆行：label + Slider + 右侧读数。
 * 黑点/白点走两位小数；阴影走 ±百分比（signedPercent = true）。
 */
@Component
struct ToneSliderRow {
  label: string = '';
  @Prop current: number = 0;
  lo: number = 0;
  hi: number = 1;
  step: number = 0.01;
  signedPercent: boolean = false;
  onValueChange: (v: number) => void = (_v: number): void => {};

  private shownValue(): number {
    if (!(this.hi > this.lo)) {
      return this.current;
    }
    return Math.min(this.hi, Math.max(this.lo, this.current));
  }

  /** 黑点/白点：两位小数；阴影：+12% / -100% / +0% */
  private readout(v: number): string {
    if (this.signedPercent) {
      const pct: number = Math.round(v * 100);
      return (pct >= 0 ? '+' : '') + pct + '%';
    }
    return v.toFixed(2);
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
          step: this.step,
          style: SliderStyle.InSet
        })
          .layoutWeight(1)
          .constraintSize({ maxWidth: 220 })
          .selectedColor(PageColor.ACCENT_BLUE)
          .trackColor(HudColor.DIVIDER)
          .onChange((raw: number, _mode: SliderChangeMode): void => {
            this.onValueChange(raw);
          })
        Text(this.readout(this.shownValue()))
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
export struct TonePanel {
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  tone: ToneParams = TONE_DEFAULT;
  onClose: () => void = (): void => {};
  onCommit: (p: ToneParams) => void = (_p: ToneParams): void => {};

  @State black: number = 0;
  @State white: number = 0;
  @State shadows: number = 0;
  @State highlights: number = 0;

  aboutToAppear(): void {
    this.adopt(this.tone.sanitize());
  }

  /** 接收一份参数刷新四个展示态（唯一写入点；面板打开后以自身状态为准） */
  private adopt(p: ToneParams): void {
    this.black = p.black;
    this.white = p.white;
    this.shadows = p.shadows;
    this.highlights = p.highlights;
  }

  /** 以面板现值做底只换显式改的字段，先钳再上抛（屏上所见即宿主所得） */
  private commit(black: number, white: number, shadows: number, highlights: number): void {
    const p: ToneParams = new ToneParams({
      black: black,
      white: white,
      shadows: shadows,
      highlights: highlights
    }).sanitize();
    this.adopt(p);
    this.onCommit(p);
  }

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: '色调',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      // 安卓 cam_tone_note 原文按逗号分两行（截图同款）：
      // 「色调进成片（烘进曲线同一张查找表），预览与成片同步变化」
      PillNote({ noteText: '色调进成片（烘进曲线同一张查找表）' })
      PillNote({ noteText: '预览与成片同步变化' })
      ToneSliderRow({
        label: '黑点',
        current: this.black,
        lo: TONE_LEVEL_MIN,
        hi: TONE_BLACK_MAX,
        step: 0.01,
        onValueChange: (v: number): void => {
          this.commit(v, this.white, this.shadows, this.highlights);
        }
      })
      ToneSliderRow({
        label: '白点',
        current: this.white,
        lo: TONE_LEVEL_MIN,
        hi: TONE_WHITE_MAX,
        step: 0.01,
        onValueChange: (v: number): void => {
          this.commit(this.black, v, this.shadows, this.highlights);
        }
      })
      ToneSliderRow({
        label: '阴影',
        current: this.shadows,
        lo: -TONE_SHADOWS_MAX,
        hi: TONE_SHADOWS_MAX,
        step: 0.05,
        signedPercent: true,
        onValueChange: (v: number): void => {
          this.commit(this.black, this.white, v, this.highlights);
        }
      })
      ToneSliderRow({
        label: '高光',
        current: this.highlights,
        lo: -TONE_HIGHLIGHTS_MAX,
        hi: TONE_HIGHLIGHTS_MAX,
        step: 0.05,
        signedPercent: true,
        onValueChange: (v: number): void => {
          this.commit(this.black, this.white, this.shadows, v);
        }
      })
    }
  }
}
