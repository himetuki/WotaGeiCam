/**
 * RGB 曲线编辑弹窗（CURVE 批，2026-10-25，直译上游 ui/dialog/CurvePopup.kt 含
 * eb956f5 右缘缩放修订）。
 *
 * 结构（与上游对齐）：全屏透明关闭捕获层 → 面板卡（surface 底 + 描边 + 圆角）＝
 * [通道扇钮左槽 | 曲线框] → 右缘等比缩放把手（eb956f5）。
 * 编辑语义：点空白＝加点（上限 8）、拖点＝改、**再点一次同一点＝删点**（端点不可删）、
 * 重置＝四通道回恒等 + 尺寸回默认最小；边拖边写总线（onCommit persist=false），
 * 抬手落盘（persist=true，Camera 写 curve_stack 持久化）。
 *
 * **eb956f5 右缘等比缩放**：panelScale 会话态 1=默认最小（200vp 设计档），只增不减；
 * 右缘 22vp 命中条横向拖拽 1:1 换算增量，coerceIn(1, maxScale)；节点半径（6.5/9）与
 * 线宽（2）随 scale 等比（放大为手指大的人服务，网格/y=x 背景不缩放）；扇面重置钮
 * 连尺寸一起复位。尺寸会话级不落盘——「默认最小值」语义即每次进入回到最小。
 *
 * 与上游的简化偏离（登记）：① 扇钮弹簧/逐枚错峰动画以 ArkUI 属性动画近似（位置+
 * 透明度，无过冲弹簧与 stagger）；② 通道切换的曲线跳变 morph 补间不做（首版直切）；
 * ③ 逐指针消费语义（isConsumed 整手让路）以 hitTestBehavior(Block) 的命中隔离替代。
 */
import { ColorCurve, CurveEdit, CurveInsertResult, CurvePoint, CurveStack } from '../core/CurveModel';
import { HistogramMath } from '../core/HistogramMath';

const CENTER_SIZE: number = 48;
const OPTION_SIZE: number = 40;
const OPTION_HIT_SIZE: number = 48;
const FAN_RADIUS: number = 64;
const CURVE_SIDE: number = 200;
const MIN_CURVE_SIDE: number = 120;
const PANEL_PAD: number = 10;
const FAN_GAP: number = 6;
const PANEL_BREATH: number = 12;
const POPUP_MARGIN: number = 12;
const RESIZE_HIT: number = 22;

/** 五枚扇钮方位角（度，0°=朝画布内即向右）：白、红、绿、蓝、重置；半圆只朝右开 */
const FAN_ANGLES: Array<number> = [-90, -45, 0, 45, 90];

const CHANNEL_COLORS: Array<string> = ['#FFFFFFFF', '#FFEF6A5A', '#FF6FCE8F', '#FF5FA8E8'];

function channelColor(channel: number): string {
  return CHANNEL_COLORS[channel >= 0 && channel <= 3 ? channel : 0];
}

@Component
export struct CurvePopup {  @Prop @Watch('onVisibleChanged') visible: boolean = false;
  /** 总线现值（Camera 持有；弹窗经 onCommit 回写——不直改，避免 @Link 与回调双写） */
  stack: CurveStack = CurveStack.IDENTITY;
  /** CPU 渲染档整块置灰不可拖（上游 gpuMode 门） */
  @Prop gpuMode: boolean = true;
  /**
   * 直方图快照源（f31d18c：曲线框背景，调曲线参考用）。由宿主页注入（GlLibBridge 的
   * getHistogramSnapshot——**UI 层不得静态 import 桥**，load-order 契约与 Camera 页同款）；
   * undefined = 无样本（未采/已清），背景不画。
   */
  histogramProvider: () => Int32Array | undefined = (): Int32Array | undefined => undefined;
  onDismiss: () => void = (): void => {};
  /** persist=false = 拖动中（Camera 只更总线态）；true = 抬手落盘（Camera 另写持久化） */
  onCommit: (stack: CurveStack, persist: boolean) => void =
    (_stack: CurveStack, _persist: boolean): void => {};

  @State channel: number = 0;
  @State fanOpen: boolean = false;
  /** 直方图计数快照（3×64，R/G/B；150ms 轮询，弹窗可见才轮询） */
  @State histCounts: Array<number> | undefined = undefined;
  private histTimer: number = -1;
  /** eb956f5：面板等比缩放，1 = 默认最小（只增不减）；会话级不落盘 */
  @State panelScale: number = 1;
  /** 弹窗可用区（关闭捕获层 onAreaChange 实测，缩放上限的输入） */
  @State availW: number = 0;
  @State availH: number = 0;
  /** 本通道编辑态（换通道重建——拖动写的是本态实例，见上游 channel-key 注记） */
  @State points: Array<CurvePoint> = CurveEdit.pointsOf(CurveStack.IDENTITY.curveOf(0));
  @State selected: number = -1;
  /** Down 是否认领到点（未认领的整手什么都不做，同上游「本次手势什么都不做」） */
  private gestureHadPoint: boolean = false;
  /** 二次点按同一点的删除预记（Move 消旗，Up 消费） */
  private pendingDelete: boolean = false;
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);

  /** 通道名（读屏与扇钮 description） */
  private channelName(channel: number): string {
    const names: Array<string> = ['白曲线', '红曲线', '绿曲线', '蓝曲线'];
    return names[channel >= 0 && channel <= 3 ? channel : 0];
  }

  /** 可用区上限：宽向扣扇钮槽/内距/呼吸，高向扣内距/呼吸（上游 maxSide 算式） */
  private maxSide(): number {
    const w: number = this.availW - CENTER_SIZE - FAN_GAP - PANEL_PAD * 2 - POPUP_MARGIN;
    const h: number = this.availH - PANEL_PAD * 2 - POPUP_MARGIN;
    return Math.max(MIN_CURVE_SIDE, Math.min(Math.max(0, w), Math.max(0, h)));
  }

  private baseSide(): number {
    return Math.max(MIN_CURVE_SIDE, Math.min(CURVE_SIDE, this.maxSide()));
  }

  /** 曲线框边长 = 默认最小档 × panelScale，夹可用区上限（eb956f5） */
  private side(): number {
    return Math.min(this.baseSide() * this.panelScale, this.maxSide());
  }

  private pickChannel(channel: number): void {
    this.channel = channel;
    this.points = CurveEdit.pointsOf(this.stack.curveOf(channel));
    this.selected = -1;
    this.fanOpen = false;
    this.drawCurve();
  }

  private resetAll(): void {
    this.stack = CurveStack.IDENTITY;
    this.onCommit(CurveStack.IDENTITY, true);
    this.points = CurveEdit.pointsOf(ColorCurve.IDENTITY);
    this.selected = -1;
    this.drawCurve();
  }

  /** 重置钮（扇面）：回恒等之外连尺寸一起复位（eb956f5 需求③） */
  private resetAllWithSize(): void {
    this.resetAll();
    this.panelScale = 1;
  }

  /** 以总线现值做底只换本通道：拖动期间其它通道可能被改过，不能用手里的旧 stack 合 */
  private commit(next: Array<CurvePoint>, persist: boolean): void {
    this.points = next;
    const curve: ColorCurve = new ColorCurve(next);
    const merged: CurveStack =
      this.stack.withCurve(this.channel, curve.isIdentity() ? ColorCurve.IDENTITY : curve);
    this.stack = merged;
    this.onCommit(merged, persist);
  }

  aboutToAppear(): void {
    this.points = CurveEdit.pointsOf(this.stack.curveOf(this.channel));
    if (this.visible) {
      this.startHistogramPolling();
    }
  }

  aboutToDisappear(): void {
    this.stopHistogramPolling();
  }

  /** visible 翻转 → 直方图轮询启停（native 采样开关由宿主页管，这里只管消费节奏） */
  onVisibleChanged(): void {
    if (this.visible) {
      this.startHistogramPolling();
    } else {
      this.stopHistogramPolling();
    }
  }

  private startHistogramPolling(): void {
    if (this.histTimer >= 0) {
      return;
    }
    // 150ms 轮询（上游同节奏）：native 250ms 一拍，轮询侧重复读同快照无害
    this.histTimer = setInterval((): void => {
      const snap: Int32Array | undefined = this.histogramProvider();
      if (snap === undefined) {
        if (this.histCounts !== undefined) {
          this.histCounts = undefined;
          this.drawCurve();
        }
        return;
      }
      const counts: Array<number> = new Array<number>(snap.length).fill(0);
      for (let i = 0; i < snap.length; i++) {
        counts[i] = snap[i];
      }
      this.histCounts = counts;
      this.drawCurve();
    }, 150);
  }

  private stopHistogramPolling(): void {
    if (this.histTimer >= 0) {
      clearInterval(this.histTimer);
      this.histTimer = -1;
    }
    if (this.histCounts !== undefined) {
      this.histCounts = undefined;
    }
  }

  /** 画布重绘入口：任何编辑态变化后手动调（ArkUI Canvas 无自动脏检查） */
  private drawCurve(): void {
    const ctx: CanvasRenderingContext2D = this.ctx;
    const side: number = this.side();
    if (side <= 0) {
      return;
    }
    const scale: number = this.panelScale;
    const dim: number = this.gpuMode ? 1 : 0.35;
    ctx.clearRect(0, 0, side, side);
    ctx.fillStyle = '#FF000000';
    ctx.fillRect(0, 0, side, side);
    // 直方图背景（画在网格/曲线**之下**）：三通道 R/G/B 半透明 0x30 条形，
    // 条宽 = side/64、条高 = 各通道内归一计数 × side——随框缩放是几何自然结果
    if (this.histCounts !== undefined) {
      const heights: Array<number> = HistogramMath.normalizedHeights(this.histCounts);
      const barW: number = side / 64;
      const histColors: Array<string> = ['#30FF0000', '#3000FF00', '#300000FF'];
      for (let c = 0; c < 3; c++) {
        ctx.fillStyle = histColors[c];
        for (let i = 0; i < 64; i++) {
          const h: number = heights[c * 64 + i] * side;
          if (h <= 0) {
            continue;
          }
          ctx.fillRect(side * i / 64, side - h, barW, h);
        }
      }
    }
    const grid: string = '#26FFFFFF';
    ctx.strokeStyle = grid;
    ctx.lineWidth = 1 * scale;
    for (let i = 1; i <= 2; i++) {
      const t: number = i / 3;
      ctx.beginPath();
      ctx.moveTo(side * t, 0);
      ctx.lineTo(side * t, side);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(0, side * t);
      ctx.lineTo(side, side * t);
      ctx.stroke();
    }
    // y=x 基准线：曲线贴着它即等于不改变
    ctx.strokeStyle = '#CCFFFFFF';
    ctx.lineWidth = 1.5 * scale;
    ctx.beginPath();
    ctx.moveTo(0, side);
    ctx.lineTo(side, 0);
    ctx.stroke();
    // 折线 = ColorCurve.table 采样：画出来的就是真正上传给 GPU 的那张表
    const table: Array<number> = new ColorCurve(this.points).table(64);
    ctx.strokeStyle = this.withAlpha(channelColor(this.channel), this.gpuMode ? 1 : 0.4);
    ctx.lineWidth = 2 * scale;
    ctx.beginPath();
    for (let i = 0; i < table.length; i++) {
      const x: number = side * i / (table.length - 1);
      const y: number = side * (1 - table[i]);
      if (i === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    }
    ctx.stroke();
    // 空心点：实心点在亮画面上会和曲线糊成一团；半径随 scale 等比（eb956f5）
    for (let i = 0; i < this.points.length; i++) {
      const p: CurvePoint = this.points[i];
      const cx: number = side * p.x;
      const cy: number = side * (1 - p.y);
      const r: number = (i === this.selected ? 9 : 6.5) * scale;
      ctx.fillStyle = i === this.selected ? channelColor(this.channel) : '#FFB8C4CE';
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#FF000000';
      ctx.beginPath();
      ctx.arc(cx, cy, r - 2.5 * scale, 0, Math.PI * 2);
      ctx.fill();
      if (i === this.selected) {
        ctx.fillStyle = channelColor(this.channel);
        ctx.beginPath();
        ctx.arc(cx, cy, r - 2.5 * scale, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  private withAlpha(argb: string, alpha: number): string {
    const a: number = Math.round(alpha * 255);
    const hex: string = a.toString(16).padStart(2, '0').toUpperCase();
    return '#' + hex + argb.substring(3);
  }

  /** 画布触控（ArkUI onTouch 直译上游 awaitEachGesture 拖点协议） */
  private onCanvasTouch(e: TouchEvent): void {
    if (!this.gpuMode) {
      return;
    }
    const side: number = this.side();
    if (side <= 0 || e.touches.length === 0) {
      return;
    }
    const t = e.touches[0];
    const gx: number = Math.min(1, Math.max(0, t.x / side));
    const gy: number = Math.min(1, Math.max(0, 1 - t.y / side));
    if (e.type === TouchType.Down) {
      this.gestureHadPoint = false;
      this.pendingDelete = false;
      let working: Array<CurvePoint> = this.points;
      let index: number = CurveEdit.hitTest(working, gx, gy);
      if (index < 0) {
        const added: CurveInsertResult | undefined = CurveEdit.inserted(working, gx, gy);
        if (added !== undefined) {
          working = added.points;
          index = added.selectedIndex;
        }
      }
      if (index >= 0) {
        this.gestureHadPoint = true;
        const deletable: boolean = index > 0 && index < working.length - 1;
        if (this.selected === index && deletable) {
          this.pendingDelete = true;
        }
        this.selected = index;
        this.commit(working, false);
      }
      return;
    }
    if (e.type === TouchType.Move) {
      // Down 里记下的工作点集与下标经 this.points/selected 承接（单指编辑，无并发手）
      if (!this.gestureHadPoint) {
        return;
      }
      this.pendingDelete = false;
      const index: number = this.selected;
      if (index < 0) {
        return;
      }
      this.commit(CurveEdit.moved(this.points, index, gx, gy), false);
      return;
    }
    if (e.type === TouchType.Up || e.type === TouchType.Cancel) {
      if (!this.gestureHadPoint) {
        return;
      }
      this.gestureHadPoint = false;
      const index: number = this.selected;
      if (index < 0) {
        return;
      }
      // 二次点按同一点＝删点（端点不可删）：Move 已消旗，Up 只认「Down 立旗且未移动」
      if (this.pendingDelete) {
        this.pendingDelete = false;
        this.commit(CurveEdit.removed(this.points, index), true);
        this.selected = -1;
      } else {
        this.commit(this.points, true);
      }
    }
  }

  /** 右缘把手横拖（eb956f5）：1:1 换算 panelScale 增量，下限 1（默认最小）只增不减 */
  private onHandlePan(e: PanGestureEvent): void {
    const base: number = this.baseSide();
    if (base <= 0) {
      return;
    }
    const maxScale: number = this.maxSide() / base;
    this.panelScale = Math.min(maxScale, Math.max(1, this.panelScale + e.offsetX / base));
    this.drawCurve();
  }

  build() {
    Stack() {
      if (this.visible) {
        // 关闭捕获层：预览不暗化；点弹窗外任意处收（面板 Block 命中不外溢）
        Column()
          .width('100%')
          .height('100%')
          .onClick((): void => {
            this.onDismiss();
          })
          .onAreaChange((_o: Area, area: Area): void => {
            this.availW = Number(area.width);
            this.availH = Number(area.height);
          })

        Stack({ alignContent: Alignment.Start }) {
          Column({ space: 0 }) {
            // 面板卡 = [扇钮左槽 | 曲线框]；叠层：曲线框先声明（画在下），扇钮后声明（画在上）
            Row({ space: FAN_GAP }) {
              Stack({ alignContent: Alignment.Center }) {
                this.fanButtons()
                this.fanCenter()
              }
              .width(CENTER_SIZE)
              .height(CENTER_SIZE)

              Stack() {
                Canvas(this.ctx)
                  .width(this.side())
                  .height(this.side())
                  .onReady((): void => {
                    this.drawCurve();
                  })
                  .onTouch((e: TouchEvent): void => {
                    this.onCanvasTouch(e);
                  })
              }
              .width(this.side())
              .height(this.side())
              .borderRadius(14)
              .clip(true)
            }
            .padding(PANEL_PAD)
          }
          .borderRadius(14)
          .backgroundColor('#F714161A')
          .border({ width: 1, color: '#33FFFFFF' })
          .padding(PANEL_PAD)
          .hitTestBehavior(HitTestMode.Block)

          // eb956f5：右缘等比缩放把手——面板**外缘**命中条（不占画布触区），居中竖条可视
          Stack({ alignContent: Alignment.End }) {
            Column()
              .width(3)
              .height(Math.max(24, this.side() - 48))
              .borderRadius(2)
              .backgroundColor('#33FFFFFF')
          }
          .height(Math.max(CENTER_SIZE, this.side()))
          .width(RESIZE_HIT)
          .gesture(
            PanGesture({ fingers: 1, direction: PanDirection.Horizontal, distance: 1 })
              .onActionUpdate((e: GestureEvent): void => {
                this.onHandlePan(e);
              })
          )
        }
        .margin({ left: PANEL_BREATH, top: POPUP_MARGIN, bottom: POPUP_MARGIN, right: POPUP_MARGIN })
      }
    }
    .width('100%')
    .height('100%')
    // 真机取证修正（2026-10-26）：隐藏档根层必须整体退出命中树——全屏 Stack 常驻时（Default 命中）
    // 会挡掉整页兄弟节点的触摸（真机上表现为录制页全部控件点不动）。None = 布局与命中都不参与。
    .visibility(this.visible ? Visibility.Visible : Visibility.None)
  }

  @Builder
  fanButtons() {
    ForEach(FAN_ANGLES, (angleDeg: number, i: number): void => {
      this.fanOption(i, angleDeg)
    }, (angleDeg: number): string => `fan_${angleDeg}`)
  }

  @Builder
  fanOption(index: number, angleDeg: number) {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width(OPTION_SIZE)
        .height(OPTION_SIZE)
        .borderRadius(OPTION_SIZE / 2)
        .backgroundColor('#FF0A0C0F')
        .border({
          width: this.fanSelectedWidth(index),
          color: this.fanSelectedColor(index)
        })
    }
    .width(OPTION_HIT_SIZE)
    .height(OPTION_HIT_SIZE)
    .offset({
      x: Math.cos(angleDeg * Math.PI / 180) * FAN_RADIUS * (this.fanOpen ? 1 : 0),
      y: Math.sin(angleDeg * Math.PI / 180) * FAN_RADIUS * (this.fanOpen ? 1 : 0)
    })
    .opacity(this.fanOpen ? 1 : 0)
    .scale({ x: this.fanOpen ? 1 : 0.4, y: this.fanOpen ? 1 : 0.4 })
    .animation({ duration: 220, curve: Curve.Sharp })
    .onClick((): void => {
      if (!this.fanOpen) {
        return;
      }
      if (index === FAN_ANGLES.length - 1) {
        this.resetAllWithSize();
        this.fanOpen = false;
      } else {
        this.pickChannel(index);
      }
    })
  }

  private fanSelected(index: number): boolean {
    return index < FAN_ANGLES.length - 1 && this.channel === index;
  }

  private fanSelectedWidth(index: number): number {
    return this.fanSelected(index) ? 2 : 1;
  }

  private fanSelectedColor(index: number): string {
    return this.fanSelected(index) ? '#FFFFFFFF' : '#33FFFFFF';
  }

  @Builder
  fanCenter() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width(18)
        .height(18)
        .borderRadius(9)
        .backgroundColor(channelColor(this.channel))
    }
    .width(CENTER_SIZE)
    .height(CENTER_SIZE)
    .borderRadius(CENTER_SIZE / 2)
    .backgroundColor('#FF000000')
    .border({ width: 2, color: channelColor(this.channel) })
    .accessibilityText(`当前通道：${this.channelName(this.channel)}，点按${this.fanOpen ? '收起' : '展开'}通道菜单`)
    .onClick((): void => {
      this.fanOpen = !this.fanOpen;
    })
  }
}
