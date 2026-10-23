/**
 * 姿态仪卡片（上游 ui/widget/AttitudeMeter.kt 的移植）：圆形人工地平仪 + 状态行，
 * 点击整卡在「气泡 / 数值」两种模式间切换（会话态，不持久化，退出页面重置）。
 *
 * 只画不判：容差带、到水平震动等判定都在 `camera/LevelSensor` 与设置开关里；
 * 本组件拿已换算到显示方向、已折叠的 roll/pitch（定义域由 `core/LevelMath` 保证）。
 *
 * 直接画、不叠动画（上游「仪表更新延迟过高」修复同口径）：数据源已是 50Hz + 低通的平滑流，
 * 再套一层长缓动只会让仪表永远在追。
 */
import { HudColor, HudShape, HudSpace, HudType } from './HudTokens';
import { LevelConst } from '../core/LevelMath';

/** 横滚/俯仰画到地平仪上时的满量程（超出即钳边，不让天地线转出圆外） */
const ROLL_FULL_SCALE_DEG: number = 30;
const PITCH_FULL_SCALE_DEG: number = 45;
/** 仪表直径（vp） */
const HORIZON_SIZE: number = 46;

@Component
export struct AttitudeMeter {
  @Prop @Watch('redraw') roll: number;
  @Prop @Watch('redraw') pitch: number;
  @Prop @Watch('redraw') isLevel: boolean;
  /** 数值模式（点击整卡切换；会话态，不进 prefs） */
  @State private numeric: boolean = false;
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private canvas: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private ready: boolean = false;

  private redraw(): void {
    this.draw();
  }

  /** 带符号一位小数：`+12.3` / `-1.6` / `0.0`（上游 level_angle_value 口径） */
  private signed(deg: number): string {
    const v: number = Number.isFinite(deg) ? deg : 0;
    const text: string = Math.abs(v) < 0.05 ? '0.0' : v.toFixed(1);
    return v > 0 && Math.abs(v) >= 0.05 ? `+${text}` : text;
  }

  private draw(): void {
    if (!this.ready) {
      return;
    }
    const w: number = this.canvas.width;
    const h: number = this.canvas.height;
    if (!(w > 0) || !(h > 0)) {
      return;
    }
    const ctx: CanvasRenderingContext2D = this.canvas;
    const radius: number = Math.min(w, h) / 2;
    const cx: number = w / 2;
    const cy: number = h / 2;
    const inner: number = radius - 1.6;
    const rollClamped: number = Math.max(-ROLL_FULL_SCALE_DEG,
      Math.min(ROLL_FULL_SCALE_DEG, Number.isFinite(this.roll) ? this.roll : 0));
    const pitchClamped: number = Math.max(-PITCH_FULL_SCALE_DEG,
      Math.min(PITCH_FULL_SCALE_DEG, Number.isFinite(this.pitch) ? this.pitch : 0));

    ctx.clearRect(0, 0, w, h);
    // 环底：实心圆只铺到内半径，露出来那一圈就是边框（省一次 stroke 的版本差异）
    ctx.beginPath();
    ctx.fillStyle = HudColor.DIVIDER;
    ctx.arc(cx, cy, radius - 0.8, 0, Math.PI * 2);
    ctx.fill();

    // 圆内裁剪：天地线转到圆外时不画出边框
    ctx.save();
    ctx.beginPath();
    ctx.arc(cx, cy, inner, 0, Math.PI * 2);
    ctx.clip();
    ctx.translate(cx, cy);
    ctx.rotate(-rollClamped * Math.PI / 180);
    const shift: number = pitchClamped * (inner / PITCH_FULL_SCALE_DEG);
    // 天（上）/ 地（下）半透明色块：整体旋转，读的是「机体相对水平面偏了多少」
    ctx.fillStyle = 'rgba(255,77,106,0.16)';
    ctx.fillRect(-inner * 2, shift - inner * 2, inner * 4, inner * 2);
    ctx.fillStyle = 'rgba(255,204,128,0.16)';
    ctx.fillRect(-inner * 2, shift, inner * 4, inner * 2);
    // 地平线
    ctx.beginPath();
    ctx.strokeStyle = HudColor.TEXT_PRIMARY;
    ctx.lineWidth = 1.4;
    ctx.moveTo(-inner, shift);
    ctx.lineTo(inner, shift);
    ctx.stroke();
    // 俯仰刻度：±10 / ±20（±20 的刻度更长）
    const tickDegs: Array<number> = [-20, -10, 10, 20];
    ctx.strokeStyle = HudColor.TEXT_TERTIARY;
    ctx.lineWidth = 1;
    for (const deg of tickDegs) {
      const tickY: number = shift + deg * (inner / PITCH_FULL_SCALE_DEG);
      const half: number = deg % 20 === 0 ? inner * 0.28 : inner * 0.16;
      ctx.beginPath();
      ctx.moveTo(-half, tickY);
      ctx.lineTo(half, tickY);
      ctx.stroke();
    }
    ctx.restore();

    // 固定机标：左右两翼 + 中心竖线，代表「我」，永远不随地平线转
    ctx.strokeStyle = HudColor.ACCENT;
    ctx.lineWidth = 2.2;
    ctx.beginPath();
    ctx.moveTo(cx - inner * 0.44, cy);
    ctx.lineTo(cx - inner * 0.14, cy);
    ctx.moveTo(cx + inner * 0.14, cy);
    ctx.lineTo(cx + inner * 0.44, cy);
    ctx.moveTo(cx, cy - inner * 0.14);
    ctx.lineTo(cx, cy + inner * 0.10);
    ctx.stroke();
  }

  build() {
    Column({ space: 2 }) {
      if (this.numeric) {
        Text(`俯仰 ${this.signed(this.pitch)}°`)
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_PRIMARY)
          .maxLines(1)
        Text(`倾角 ${this.signed(this.roll)}°`)
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_PRIMARY)
          .maxLines(1)
      } else {
        Canvas(this.canvas)
          .width(HORIZON_SIZE)
          .height(HORIZON_SIZE)
          .onReady((): void => {
            this.ready = true;
            this.draw();
          })
      }
      Text(this.isLevel ? '已回正' : '偏斜中')
        .fontSize(HudType.LABEL)
        .fontColor(this.isLevel ? HudColor.ACCENT : HudColor.WARN)
        .maxLines(1)
    }
    // 宽度包内容（两模式自然宽度都 > 46vp，不设 min 假守卫）
    .padding({ left: 4, right: 4, top: 5, bottom: 5 })
    .backgroundColor(HudColor.PILL_BG)
    .borderRadius(HudShape.CARD_RADIUS)
    .onClick((): void => {
      this.numeric = !this.numeric;
    })
  }
}

/** 水平判定的对外口径（UI 着色与回正震动共用 LevelConst 的同一阈值） */
export function isLevelOf(rollDeg: number): boolean {
  return Math.abs(rollDeg) < LevelConst.LEVEL_TOLERANCE_DEG;
}
