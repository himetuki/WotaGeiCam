/**
 * 取景参考线覆盖层（上游 ui/widget/RefLineOverlay.kt 的移植，11 档位掩码可叠加）。
 *
 * 与其他 UI 的分工：本组件只画线、不接收手势（HUD 在它之上，预览在它之下）。
 * 几何全部走 `core/RefLine` 的纯函数（内接框 + 水平线端点），本文件只做 Canvas 落笔。
 *
 * **平台偏离（登记）**：本侧预览由 XComponent 全屏直出、未做 letterbox 几何，
 * 所以 `aspect` 一般传 0（= 线铺满整屏），而不是上游那样按画面宽高比内接。
 * 一旦接上裁剪/letterbox，把真实宽高比传进来即可，绘制逻辑不用改。
 */
import {
  CanvasBox, CanvasPoint, RefLine, RefLineType, horizonEndpoints, inscribedBox
} from '../core/RefLine';
import { HudColor } from './HudTokens';

/** 细线 1vp、加粗 2vp（红线规范要求 2vp） */
const THIN: number = 1;
const THICK: number = 2;
/** 网格档的分割数（上游 GRID_LINES = 6） */
const GRID_DIVISIONS: number = 6;

@Component
export struct RefLineOverlay {
  /** `RefLineType` 的位或组合；0 = 什么都不画 */
  @Prop @Watch('redraw') lineMask: number;
  /** 画面宽高比（宽/高）；<=0 = 铺满整个组件 */
  @Prop @Watch('redraw') aspect: number;
  /** 设备横滚角（LevelMath.roll），只作用于水平线 */
  @Prop @Watch('redraw') rollDegrees: number;
  /** 水平线总开关：false 时即使 mask 含 HORIZON_LINE 也不画 */
  @Prop showLevelLine: boolean;

  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private canvas: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private ready: boolean = false;

  private redraw(): void {
    this.draw();
  }

  /** 某档是否要画（水平线额外受 showLevelLine 门控） */
  private on(type: RefLineType): boolean {
    if (type === RefLineType.HORIZON_LINE) {
      return this.showLevelLine && RefLine.isOn(this.lineMask, type);
    }
    return RefLine.isOn(this.lineMask, type);
  }

  private line(x1: number, y1: number, x2: number, y2: number, width: number, color: string): void {
    this.canvas.beginPath();
    this.canvas.strokeStyle = color;
    this.canvas.lineWidth = width;
    this.canvas.moveTo(x1, y1);
    this.canvas.lineTo(x2, y2);
    this.canvas.stroke();
  }

  private draw(): void {
    if (!this.ready) {
      return;
    }
    const w: number = this.canvas.width;
    const h: number = this.canvas.height;
    const ctx: CanvasRenderingContext2D = this.canvas;
    ctx.clearRect(0, 0, w, h);
    if (this.lineMask === 0 || !(w > 0) || !(h > 0)) {
      return;
    }
    const box: CanvasBox = inscribedBox(w, h, this.aspect);
    if (!(box.width > 0) || !(box.height > 0)) {
      return;
    }
    const right: number = box.left + box.width;
    const bottom: number = box.top + box.height;
    const minSide: number = Math.min(box.width, box.height);

    if (this.on(RefLineType.GRID_3X3)) {
      for (let i = 1; i <= 2; i++) {
        const x: number = box.left + box.width * i / 3;
        const y: number = box.top + box.height * i / 3;
        this.line(x, box.top, x, bottom, THIN, HudColor.REF_LINE);
        this.line(box.left, y, right, y, THIN, HudColor.REF_LINE);
      }
    }
    if (this.on(RefLineType.DIAGONAL)) {
      this.line(box.left, box.top, right, bottom, THIN, HudColor.REF_LINE);
      this.line(right, box.top, box.left, bottom, THIN, HudColor.REF_LINE);
      // 交点（中心）标圆
      ctx.beginPath();
      ctx.strokeStyle = HudColor.REF_LINE;
      ctx.lineWidth = THIN;
      ctx.arc(box.left + box.width / 2, box.top + box.height / 2, 4, 0, Math.PI * 2);
      ctx.stroke();
    }
    if (this.on(RefLineType.CROSSHAIR)) {
      const cx: number = box.left + box.width / 2;
      const cy: number = box.top + box.height / 2;
      const gap: number = 4; // 中心留缺口，不遮被摄主体
      this.line(cx, box.top, cx, cy - gap, THIN, HudColor.REF_LINE);
      this.line(cx, cy + gap, cx, bottom, THIN, HudColor.REF_LINE);
      this.line(box.left, cy, cx - gap, cy, THIN, HudColor.REF_LINE);
      this.line(cx + gap, cy, right, cy, THIN, HudColor.REF_LINE);
    }
    if (this.on(RefLineType.BROADCAST_SAFE)) {
      this.ratioFrame(box, 0.70, THIN);
      this.cornerTicks(this.shrink(box, 0.70), minSide * 0.06, THICK);
    }
    if (this.on(RefLineType.SAFE_AREA)) {
      this.ratioFrame(box, 0.95, THIN);
      this.ratioFrame(box, 0.90, THIN);
    }
    if (this.on(RefLineType.GRID)) {
      for (let i = 1; i < GRID_DIVISIONS; i++) {
        const x: number = box.left + box.width * i / GRID_DIVISIONS;
        const y: number = box.top + box.height * i / GRID_DIVISIONS;
        this.line(x, box.top, x, bottom, THIN, HudColor.REF_LINE);
        this.line(box.left, y, right, y, THIN, HudColor.REF_LINE);
      }
    }
    if (this.on(RefLineType.CENTER_MARK)) {
      const cx: number = box.left + box.width / 2;
      const cy: number = box.top + box.height / 2;
      const side: number = minSide * 0.08;
      ctx.beginPath();
      ctx.strokeStyle = HudColor.REF_LINE;
      ctx.lineWidth = THIN;
      ctx.strokeRect(cx - side / 2, cy - side / 2, side, side);
      const bracket: number = minSide * 0.30;
      this.cornerTicks(this.shrinkToCentre(box, bracket), minSide * 0.06, THICK);
    }
    if (this.on(RefLineType.HORIZON_LINE)) {
      const cx: number = box.left + box.width / 2;
      const cy: number = box.top + box.height / 2;
      const half: number = Math.sqrt(box.width * box.width + box.height * box.height) / 2;
      const pts: Array<CanvasPoint> = horizonEndpoints(cx, cy, half, this.rollDegrees);
      this.line(pts[0].x, pts[0].y, pts[1].x, pts[1].y, THIN, HudColor.REF_LINE);
    }
    // 红线最后画：WOTA 走位判定要压在其他线之上
    if (this.on(RefLineType.RED_TOP)) {
      this.fullWidthLine(box, 0.10);
    }
    if (this.on(RefLineType.RED_MID)) {
      this.fullWidthLine(box, 0.50);
    }
    if (this.on(RefLineType.RED_BOTTOM)) {
      this.fullWidthLine(box, 0.90);
    }
  }

  /** 按比例居中缩框 */
  private shrink(box: CanvasBox, ratio: number): CanvasBox {
    const w: number = box.width * ratio;
    const h: number = box.height * ratio;
    return new CanvasBox(box.left + (box.width - w) / 2, box.top + (box.height - h) / 2, w, h);
  }

  /** 以画布中心为心、边长 side 的正方框（中心标记的括号用） */
  private shrinkToCentre(box: CanvasBox, side: number): CanvasBox {
    const cx: number = box.left + box.width / 2;
    const cy: number = box.top + box.height / 2;
    return new CanvasBox(cx - side / 2, cy - side / 2, side, side);
  }

  private ratioFrame(box: CanvasBox, ratio: number, stroke: number): void {
    const frame: CanvasBox = this.shrink(box, ratio);
    this.canvas.beginPath();
    this.canvas.strokeStyle = HudColor.REF_LINE;
    this.canvas.lineWidth = stroke;
    this.canvas.strokeRect(frame.left, frame.top, frame.width, frame.height);
  }

  /** 四角各画一横一竖加粗短边 */
  private cornerTicks(box: CanvasBox, arm: number, stroke: number): void {
    const left: number = box.left;
    const top: number = box.top;
    const right: number = box.left + box.width;
    const bottom: number = box.top + box.height;
    // 左上
    this.line(left, top, left + arm, top, stroke, HudColor.REF_LINE);
    this.line(left, top, left, top + arm, stroke, HudColor.REF_LINE);
    // 右上
    this.line(right, top, right - arm, top, stroke, HudColor.REF_LINE);
    this.line(right, top, right, top + arm, stroke, HudColor.REF_LINE);
    // 左下
    this.line(left, bottom, left + arm, bottom, stroke, HudColor.REF_LINE);
    this.line(left, bottom, left, bottom - arm, stroke, HudColor.REF_LINE);
    // 右下
    this.line(right, bottom, right - arm, bottom, stroke, HudColor.REF_LINE);
    this.line(right, bottom, right, bottom - arm, stroke, HudColor.REF_LINE);
  }

  /** 满宽横线（fraction = 距顶比例），红线专用 2vp */
  private fullWidthLine(box: CanvasBox, fraction: number): void {
    const y: number = box.top + box.height * fraction;
    this.line(box.left, y, box.left + box.width, y, THICK, HudColor.REF_LINE_RED);
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
  }
}
