/**
 * 站位指示线覆盖层（2026-10-27 用户需求）——上游 ui/widget/MarkerLineOverlay.kt 直译。
 *
 * 与 RefLineOverlay 同一通道：**纯 UI 层绘制，不进录像画面**（录制采集的是相机/GL 面，
 * UI 叠加结构上进不了编码器，无需开关防呆）。只画线不收手势（控制在取景页圆钮浮层）。
 *
 * 线的 x 用 MarkerLines.lineXsFraction 的比例（居中对称、整组占 spread 比例宽），
 * 映射进与 RefLineOverlay 同款的**画面内接矩形**（黑边区域不画线）；线全长（顶到底）、
 * 2vp、55% 透明度——"透明"是产品口径（站位线要显眼但不抢画面）。
 */
import { CanvasBox, inscribedBox } from '../core/RefLine';
import { MarkerLines } from '../core/MarkerLines';

@Component
export struct MarkerLineOverlay {
  /** 线数（0 = 关闭，什么都不画） */
  @Prop @Watch('redraw') count: number;
  /** 整组展开宽度（0.15..1.0 预览宽比例） */
  @Prop @Watch('redraw') spread: number;
  /** 颜色（ARGB number；透明度由本层统一 0.55） */
  @Prop @Watch('redraw') argb: number;
  /** 画面宽高比（宽/高）；<=0 = 铺满整个组件 */
  @Prop @Watch('redraw') aspect: number;

  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private canvas: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private ready: boolean = false;
  private lastW: number = 0;
  private lastH: number = 0;

  private redraw(): void {
    this.draw();
  }

  private draw(): void {
    if (!this.ready) {
      return;
    }
    const ctx: CanvasRenderingContext2D = this.canvas;
    const w: number = this.lastW;
    const h: number = this.lastH;
    if (w <= 0 || h <= 0) {
      return;
    }
    ctx.clearRect(0, 0, w, h);
    const xs: Array<number> = MarkerLines.lineXsFraction(this.count, this.spread);
    if (xs.length === 0) {
      return;
    }
    const box: CanvasBox = inscribedBox(w, h, this.aspect);
    if (!(box.width > 0) || !(box.height > 0)) {
      return;
    }
    const v: number = this.argb >>> 0;
    const r: number = (v >>> 16) & 0xFF;
    const g: number = (v >>> 8) & 0xFF;
    const b: number = v & 0xFF;
    ctx.strokeStyle = `rgba(${r},${g},${b},0.55)`;
    ctx.lineWidth = 2;
    for (const x of xs) {
      const px: number = box.left + box.width * x;
      ctx.beginPath();
      ctx.moveTo(px, box.top);
      ctx.lineTo(px, box.top + box.height);
      ctx.stroke();
    }
  }

  build() {
    Canvas(this.canvas)
      .width('100%')
      .height('100%')
      .hitTestBehavior(HitTestMode.None)
      .onReady((): void => {
        this.ready = true;
        this.draw();
      })
      .onAreaChange((_o: Area, a: Area): void => {
        const w: number = a.width as number;
        const h: number = a.height as number;
        if (w > 0 && h > 0) {
          this.lastW = w;
          this.lastH = h;
          this.draw();
        }
      })
  }
}
