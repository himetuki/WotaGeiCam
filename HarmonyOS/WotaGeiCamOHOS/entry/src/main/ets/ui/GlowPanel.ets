/**
 * Glow（荧光棒光晕）参数面板——上游 GlowPopup（24 R3 §1.5 + 29 §4 用户口径两步走）
 * 的 OHOS 直译（04d1c62/0b9d275/350c671 终态）。
 *
 * 纯 UI 组件：不持总线、不落盘——所有改动经 [onCommit] 上抛（宿主页 Camera 负责下发与
 * GlowStore 持久化），关闭只经 [onClose]。**所有回传值先过 `GlowParams.sanitize()`**。
 *
 * 结构：[说明] → [关|开] → [颜色模式 choice：跟随原色 | 仅色盘颜色] → [色盘（画布）]
 * → [亮度阈值滑杆（0.6..1.0，读数 %）] → [软边滑杆] → [状态注记]。
 *
 * 交互口径（2026-10-28/29 用户定版）：
 * - **预设 A/B 与两端色带已删**（那是给整片换色）：出厂白 = 不改色（跟随光棒原色）；
 *   色盘自选单色；色盘上点圆心（饱和度 0）就是白，**双击色盘 = 归零回白**（350c671）；
 * - 关/开只改 enabled；颜色模式只改 colorOnly（主 pass 的二次染色由 native 侧防）；
 * - 滑杆连续拖动即 onCommit（调参在下一个真帧生效）。
 *
 * 平台偏离登记：抓点/双击确认震动无 VIBRATE 权限（红线在册）不做；色盘选中圈随拖更新。
 * 本文件不 import @kit（Canvas/TouchEvent 均 ArkUI 内建；持久化只在 GlowStore）。
 */
import {
  GLOW_DEFAULT, GLOW_SOFT_MAX, GLOW_THRESHOLD_MAX, GLOW_THRESHOLD_MIN,
  glowArgbFromWheel, glowWheelMarkerOf, GlowParams
} from '../core/Glow';
import { HudColor, HudType, PageColor } from './HudTokens';
import { PillChoices, PillNote, PillOption, PillRect, WotaPillPopup } from './PillPopup';

/** 色盘边长（vp）：小面板内可点的圆盘 */
const WHEEL_SIDE: number = 132;

/**
 * 连续值滑杆行：label + Slider + 读数。
 * Glow 的值域（阈值 0.6..1.0、软边 0..0.5）是连续量，无梯/无吸附语义，
 * 不走 PillSlider 的梯机制，按 PillSlider 的连续分支直绘。
 */
@Component
struct GlowSliderRow {
  label: string = '';
  @Prop current: number = 0;
  lo: number = 0;
  hi: number = 1;
  /** 读数格式（阈值用 %，软边两位小数） */
  format: (v: number) => string = (v: number): string => v.toFixed(2);
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
        Text(this.format(this.shownValue()))
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
  @State threshold: number = 0.7;
  @State soft: number = 0.05;
  /** 色盘当前色（ARGB number；白 = 跟随原色） */
  @State color: number = 0xFFFFFFFF;
  /** true = 仅色盘颜色（不乘原色） */
  @State colorOnly: boolean = false;
  /** 面板当前全量参数：面板直改 enabled/threshold/soft/color/colorOnly，其余原样带过 */
  private cur: GlowParams = GLOW_DEFAULT;
  private wheelSettings: RenderingContextSettings = new RenderingContextSettings(true);
  private wheelCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.wheelSettings);
  private wheelDrawn: boolean = false;
  /** 双击归零（回白）：上一次色盘点按的时刻 */
  private lastWheelTapMs: number = 0;

  aboutToAppear(): void {
    this.adopt(this.glow.sanitize());
  }

  /** 接收一份参数：刷新全量态 + 展示态（唯一写入点） */
  private adopt(p: GlowParams): void {
    this.cur = p;
    this.glowOn = p.enabled;
    this.threshold = p.threshold;
    this.soft = p.soft;
    this.color = p.color;
    this.colorOnly = p.colorOnly;
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
      color: this.color,
      colorOnly: this.colorOnly
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

  /** ARGB number → #RRGGBB（色盘着色用） */
  private hexOf(argb: number): string {
    const r: number = Math.floor(argb / 65536) % 256;
    const g: number = Math.floor(argb / 256) % 256;
    const b: number = argb % 256;
    const h: (v: number) => string = (v: number): string => v.toString(16).padStart(2, '0');
    return `#FF${h(r)}${h(g)}${h(b)}`;
  }

  /** 画色盘：色相环 + 白心径向饱和度衰减（明度恒 1）+ 选中圈 */
  private drawWheel(): void {
    const ctx: CanvasRenderingContext2D = this.wheelCtx;
    const r: number = WHEEL_SIDE / 2;
    // 36 色相扇 × 8 饱和度环近似圆盘（离散步进；视觉平滑已够小面板用）
    for (let a = 0; a < 36; a++) {
      for (let s = 0; s < 8; s++) {
        const hue: number = a / 36;
        const sat: number = (s + 0.5) / 8;
        const argb: number = glowArgbFromWheel(
          Math.cos(a * Math.PI / 18) * sat * r, Math.sin(a * Math.PI / 18) * sat * r, r);
        ctx.fillStyle = this.hexOf(argb);
        ctx.beginPath();
        ctx.arc(r, r, r - (7 - s) * (r / 8), 0, Math.PI * 2);
        ctx.fill();
      }
    }
    // 选中圈：白/灰在圆心（无色相），彩点按 sat 落半径
    const marker: Array<number> = glowWheelMarkerOf(this.color);
    const mx: number = r + marker[0] * (r - 6);
    const my: number = r + marker[1] * (r - 6);
    ctx.strokeStyle = '#FFFFFFFF';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(mx, my, 7, 0, Math.PI * 2);
    ctx.stroke();
    this.wheelDrawn = true;
  }

  /** 色盘触控：拖拽取色；500ms 内二次点按 = 归零回白（跟随原色，350c671） */
  private onWheelTouch(e: TouchEvent): void {
    if (e.touches.length === 0) {
      return;
    }
    const t = e.touches[0];
    const r: number = WHEEL_SIDE / 2;
    const dx: number = t.x - r;
    const dy: number = t.y - r;
    const now: number = Date.now();
    if (now - this.lastWheelTapMs < 400 && e.type === TouchType.Down) {
      // 双击归零：圆心 = 白 = 跟随光棒原色（350c671 色盘双击归零）
      this.lastWheelTapMs = 0;
      this.color = 0xFFFFFFFF;
      this.drawWheel();
      this.commit(this.withEdits(this.glowOn, this.threshold, this.soft));
      return;
    }
    if (e.type === TouchType.Down) {
      this.lastWheelTapMs = now;
    }
    if (e.type === TouchType.Down || e.type === TouchType.Move) {
      const argb: number = glowArgbFromWheel(dx, dy, r);
      this.color = argb;
      this.commit(this.withEdits(this.glowOn, this.threshold, this.soft));
      this.drawWheel();
    }
  }

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: 'Glow 光晕',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      PillNote({ noteText: '光晕进成片（烘焙进像素），调参在下一个真帧生效；出厂白 = 跟随光棒原色' })
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
      // 颜色模式（350c671）：0 = 达到阈值的像素发自身颜色；1 = 仅色盘颜色
      PillChoices({
        options: [
          new PillOption(0, '跟随原色', true),
          new PillOption(1, '仅色盘颜色', true)
        ],
        selected: this.colorOnly ? 1 : 0,
        perRow: 2,
        onPick: (opt: PillOption): void => {
          this.colorOnly = opt.value === 1;
          this.commit(this.withEdits(this.glowOn, this.threshold, this.soft));
        }
      })
      Column() {
        Canvas(this.wheelCtx)
          .width(WHEEL_SIDE)
          .height(WHEEL_SIDE)
          .onReady((): void => {
            this.drawWheel();
          })
          .onTouch((e: TouchEvent): void => {
            this.onWheelTouch(e);
          })
        Text(this.colorOnly
          ? `仅色盘颜色 · ${this.hexOf(this.color).substring(2)}`
          : '跟随原色（白 = 不改色；双击色盘回白）')
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_TERTIARY)
          .maxLines(1)
          .margin({ top: 4 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Center)
      GlowSliderRow({
        label: '亮度阈值（60~100%，100% = 只给最亮像素）',
        current: this.threshold,
        lo: GLOW_THRESHOLD_MIN,
        hi: GLOW_THRESHOLD_MAX,
        format: (v: number): string => `${Math.round(v * 100)}%`,
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
      // 状态行以 getGlowStatus 解析结果为唯一权威（与 chainActive 双源矛盾已收口单一来源）
      PillNote({ noteText: this.statusText !== '' ? this.statusText : '渲染链未生效（参数已保存）' })
    }
  }
}
