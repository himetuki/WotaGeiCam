/**
 * 收藏红心——上游 ui/design/LikeHeart.kt（a3855c3）的 ArkUI 版：点按切换收藏，
 * **收藏那一下**播一次 Bencho "Like" 的红心动画（绽放环抽出 → 七对彩点甩出 →
 * 红心从无到有弹回）；取消收藏只把心掏空，不播效果。
 *
 * 动画驱动：一条主进度 @State t（setInterval 33ms 步进 820ms）→ Canvas 重绘
 * （绽放环描边圆 + 14 颗彩点）。数学全在 core/LikeBurst.ets（纯测全表）。
 *
 * 平台映射（登记）：上游 Animatable+keyframes → 本侧定时步进（33ms ≈ 30fps；动效是
 * 纯装饰，步进抖动可接受）；宿主 = 全屏查看器底栏的星钮（Android 相册覆盖层同位）；
 * 播放器顶栏无收藏宿主（3651901 的那半边 N/A 登记）。只动 scale/alpha/位置，不动布局。
 */
import { LikeBurst } from '../core/LikeBurst';
import { HudColor } from './HudTokens';

@Component
export struct LikeHeart {
  /** 受控收藏态 */
  @Prop liked: boolean = false;
  onToggle: (next: boolean) => void = (_next: boolean): void => {};
  contentDescription: string = '收藏';

  @State private playing: boolean = false;
  @State private t: number = 1;
  private timer: number = -1;

  /** ARGB number → #RRGGBBAA（Canvas 着色） */
  private static hex(argb: number): string {
    const v: number = argb >>> 0;
    const h: (x: number, bits: number) => string =
      (x: number, bits: number): string => ((x >>> bits) & 0xFF).toString(16).padStart(2, '0');
    return `#${h(v, 24)}${h(v, 16)}${h(v, 8)}${h(v, 0)}`;
  }

  private press(): void {
    const next: boolean = !this.liked;
    this.onToggle(next);
    if (!next || this.playing) {
      return; // 取消收藏不播效果；播放中不重放（上游 beat 口径的轻量近似）
    }
    this.playing = true;
    this.t = 0;
    this.drawBurst();
    this.timer = setInterval((): void => {
      this.t = Math.min(1, this.t + 33 / LikeBurst.DURATION_MS);
      this.drawBurst(); // Canvas 无脏回调：定时步进里直接重绘（JS 线程同侧，允许）
      if (this.t >= 1) {
        clearInterval(this.timer);
        this.timer = -1;
        this.playing = false;
      }
    }, 33);
  }

  aboutToDisappear(): void {
    if (this.timer >= 0) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  private drawBurst(): void {
    const ctx: CanvasRenderingContext2D = this.burstCtx;
    const side: number = 72;
    ctx.clearRect(0, 0, side, side);
    const cx: number = side / 2;
    const cy: number = side / 2;
    // 绽放环：实心圆 → 环 → 淡出（描边圆：半径 − stroke/2，与上游 drawCircle 同式）
    const alpha: number = LikeBurst.bloomAlpha(this.t);
    const borderFrac: number = LikeBurst.bloomBorderFraction(this.t);
    if (alpha > 0 && borderFrac > 0) {
      const radius: number = LikeBurst.BLOOM_RADIUS_DP * LikeBurst.bloomScale(this.t);
      const stroke: number = Math.max(0.5, radius * borderFrac);
      ctx.strokeStyle = LikeHeart.hex(LikeBurst.bloomColorArgb(this.t));
      ctx.globalAlpha = alpha;
      ctx.lineWidth = stroke;
      ctx.beginPath();
      ctx.arc(cx, cy, Math.max(0.1, radius - stroke / 2), 0, Math.PI * 2);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }
    // 七对彩点：各自辐条 + 侧向错位（设计稿 --side/--far）
    const dotP: number = LikeBurst.dotProgress(this.t);
    if (dotP >= 0) {
      const dotR: number = LikeBurst.DOT_DP / 2;
      for (let k = 0; k < 7; k++) {
        const ang: number = LikeBurst.dotAngleDeg(k) * Math.PI / 180;
        const ux: number = Math.cos(ang);
        const uy: number = Math.sin(ang);
        for (let pair = 0; pair < 2; pair++) {
          const sideOff: number = pair === 0 ? -LikeBurst.DOT_SIDE_DP : LikeBurst.DOT_SIDE_DP;
          const far: number = pair === 0 ? 0 : LikeBurst.DOT_FAR_DP;
          const radial: number = LikeBurst.dotRadiusPx(dotP, LikeBurst.FLY_DEFAULT, far);
          const lateral: number = LikeBurst.dotSidePx(dotP, sideOff);
          const px: number = cx + ux * radial - uy * lateral;
          const py: number = cy + uy * radial + ux * lateral;
          const colorIdx: number = LikeBurst.dotColorIndex(k, pair) % LikeBurst.CONFETTI.length;
          ctx.globalAlpha = LikeBurst.dotAlpha(dotP);
          ctx.fillStyle = LikeHeart.hex(LikeBurst.CONFETTI[colorIdx]);
          ctx.beginPath();
          ctx.arc(px, py, Math.max(0.1, dotR * LikeBurst.dotScale(dotP)), 0, Math.PI * 2);
          ctx.fill();
        }
      }
      ctx.globalAlpha = 1;
    }
  }

  private burstSettings: RenderingContextSettings = new RenderingContextSettings(true);
  private burstCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.burstSettings);

  build() {
    Stack({ alignContent: Alignment.Center }) {
      // 绽放层（播放期间覆盖在心之上；非播放不占命中）
      Canvas(this.burstCtx)
        .width(72)
        .height(72)
        .hitTestBehavior(HitTestMode.None)
        .onReady((): void => {
          this.drawBurst();
        })
        .opacity(this.playing ? 1 : 0)
      Text(this.liked ? '❤' : '♡')
        .fontSize(16)
        .fontColor(this.liked ? '#FFFF4D6A' : Color.White)
        .scale({
          x: this.playing ? LikeBurst.heartScale(this.t) : 1,
          y: this.playing ? LikeBurst.heartScale(this.t) : 1
        })
    }
    .width(40)
    .height(32)
    .onClick((): void => {
      this.press();
    })
    .accessibilityText(this.contentDescription)
  }
}
