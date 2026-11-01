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
 * **eb956f5 右缘等比缩放 + 29 §1.2（320a956）存实际边长**：把手改 savedSide（dp）、
 * 抬手落盘一次；重置写回 200；
 * 右缘 22vp 命中条横向拖拽 1:1 换算增量，coerceIn(1, maxScale)；节点半径（6.5/9）与
 * 线宽（2）随 scale 等比（放大为手指大的人服务，网格/y=x 背景不缩放）；扇面重置钮
 * 连尺寸一起复位。尺寸会话级不落盘——「默认最小值」语义即每次进入回到最小。
 *
 * 与上游的简化偏离（登记）：① 扇钮弹簧/逐枚错峰动画以 ArkUI 属性动画近似（位置+
 * 透明度，无过冲弹簧与 stagger）；② 通道切换的曲线跳变 morph 补间不做（首版直切）；
 * ③ 逐指针消费语义（isConsumed 整手让路）以 hitTestBehavior(Block) 的命中隔离替代。
 */
import { ColorCurve, CurveEdit, CurveInsertResult, CurvePoint, CurveStack } from '../core/CurveModel';
import { CurvePanelSize } from '../core/CurvePanelSize';
import { CurvePanelSideStore } from './SettingsStore';
import { common } from '@kit.AbilityKit';
import { HistogramMath } from '../core/HistogramMath';

const CENTER_SIZE: number = 48;
const OPTION_SIZE: number = 40;
const OPTION_HIT_SIZE: number = 48;
const FAN_RADIUS: number = 64;
const CURVE_SIDE: number = 200;
const MIN_CURVE_SIDE: number = 120;
// 命中手感三常量（29 §3.2，上游 bda89bb；单位 vp，与 side 同域）
/** 端点角部扩展：命中区比画布四边各外扩的 dp（触点一半在画布外也抓得住端点） */
const HIT_EXT: number = 12;
/** 像素域命中半径（×面板等比），下限见下——归一化常数随面板放大相对手指变小 */
const HIT_RADIUS_DP: number = 20;
const HIT_RADIUS_MIN_DP: number = 14;
/** 长按删除到时（29 §3.2-3）：未移动且按住到时 → 删中间点，手势即止 */
const LONG_PRESS_DELETE_MS: number = 500;
const PANEL_PAD: number = 10;
const FAN_GAP: number = 6;
const PANEL_BREATH: number = 12;
const POPUP_MARGIN: number = 12;
const RESIZE_HIT: number = 22;

/** 五枚扇钮方位角（度，0°=朝画布内即向右）：白、红、绿、蓝、重置；半圆只朝右开 */
const FAN_ANGLES: Array<number> = [-90, -45, 0, 45, 90];

const CHANNEL_COLORS: Array<string> = ['#FFFFFFFF', '#FFEF6A5A', '#FF6FCE8F', '#FF5FA8E8'];

/** 「背景色趋势」（29 §2.2-3）：按通道的横向渐变两端色（0x33 α）；色标沿用 channelColor */
function trendColorsOf(channel: number): Array<string> {
  switch (channel) {
    case ColorCurve.CHANNEL_RED:
      return ['#33EF6A5A', '#3350C8D8'];
    case ColorCurve.CHANNEL_GREEN:
      return ['#336FCE8F', '#33D66AA0'];
    case ColorCurve.CHANNEL_BLUE:
      return ['#335FA8E8', '#33D8C050'];
    default:
      return ['#33111111', '#33EEEEEE'];
  }
}

/** 直方图按通道取哪一组（29 §2.2-2）：R/G/B 各自的组，master 取亮度 Y */
function histogramBaseOf(channel: number): number {
  switch (channel) {
    case ColorCurve.CHANNEL_RED:
      return 0;
    case ColorCurve.CHANNEL_GREEN:
      return HistogramMath.BINS;
    case ColorCurve.CHANNEL_BLUE:
      return 2 * HistogramMath.BINS;
    default:
      return HistogramMath.LUMA_BASE;
  }
}

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
  /**
   * 29 §1.2 F1（上游 320a956）：**存实际边长 dp** 不存 scale——跨屏幕/转屏 baseSide 会变，
   * 存 scale 会漂移。把手拖拽改 savedSide，**抬手才落盘一次**（不逐帧写存储）；重置写回 200。
   */
  @State savedSide: number = CurvePanelSize.DEFAULT_SIDE_DP;
  /** 弹窗可用区（关闭捕获层 onAreaChange 实测，缩放上限的输入） */
  @State availW: number = 0;
  @State availH: number = 0;
  /** 本通道编辑态（换通道重建——拖动写的是本态实例，见上游 channel-key 注记） */
  @State points: Array<CurvePoint> = CurveEdit.pointsOf(CurveStack.IDENTITY.curveOf(0));
  @State selected: number = -1;
  /** Down 是否认领到点（未认领的整手什么都不做，同上游「本次手势什么都不做」） */
  private gestureHadPoint: boolean = false;
  /** 长按删除定时器（Down 认领到可删点起跑；Move/Up/Cancel 取消） */
  private deleteTimer: number = -1;
  /** 长按删除已成立：本手势余下的 Move/Up 全部忽略（手势即止，上游 deleted 同义） */
  private gestureDead: boolean = false;
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

  /**
   * 曲线框边长：持久边长带组合期可用区二次钳（29 §1.2）——存储值比当前窗口大（换小屏/
   * 分屏）缩回可用区、比最小值小抬回最小；dot/线宽随 side/baseSide 等比放大，与旧
   * panelScale 语义逐字相同
   */
  private side(): number {
    return CurvePanelSize.clamp(this.savedSide, this.maxSide(), MIN_CURVE_SIDE);
  }

  /** 面板相对设计档的等比（dot/线宽放大的输入，与旧 panelScale 同义） */
  private panelScale(): number {
    const base: number = this.baseSide();
    return base > 0 ? this.side() / base : 1;
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

  /** 重置钮（扇面）：回恒等之外连尺寸一起复位并落盘（29 §1.2：重置写回 200） */
  private resetAllWithSize(): void {
    this.resetAll();
    this.savedSide = CurvePanelSize.DEFAULT_SIDE_DP;
    this.writeSide();
  }

  /** 抬手/重置的落盘点（一次手势一次；写失败只记日志） */
  private writeSide(): void {
    const ctx: common.Context | undefined =
      this.getUIContext().getHostContext() as common.Context | undefined;
    if (ctx !== undefined) {
      CurvePanelSideStore.write(ctx, this.savedSide);
    }
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
    this.cancelDeleteTimer();
  }

  /** visible 翻转 → 直方图轮询启停 + 开窗时读持久边长（纯 UI 设置，不走默认值应用链） */
  private onVisibleChanged(): void {
    if (this.visible) {
      const ctx: common.Context | undefined =
        this.getUIContext().getHostContext() as common.Context | undefined;
      if (ctx !== undefined) {
        this.savedSide = CurvePanelSideStore.read(ctx);
      }
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
    const scale: number = this.panelScale();
    const dim: number = this.gpuMode ? 1 : 0.35;
    ctx.clearRect(0, 0, side, side);
    ctx.fillStyle = '#FF000000';
    ctx.fillRect(0, 0, side, side);
    // 背景色趋势（29 §2.2-3）：按通道着色的横向渐变（暗端通道色 → 高端补色），
    // 透明度 0.2 档；master 用中性灰阶（不引入色偏）
    const trend: Array<string> = trendColorsOf(this.channel);
    const grad: CanvasGradient = ctx.createLinearGradient(0, 0, side, 0);
    grad.addColorStop(0, trend[0]);
    grad.addColorStop(1, trend[1]);
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, side, side);
    // 直方图背景（画在网格/曲线**之下**）：只画**当前通道**那一组（29 §2.2 F2 按通道区分，
    // 上游 320a956）：R/G/B 各自的组，master 用亮度 Y（不再三色混排）；半透明 0x30 通道色，
    // 条宽 = side/64、条高 = 通道内归一计数 × side——随框缩放是几何自然结果
    if (this.histCounts !== undefined) {
      const heights: Array<number> = HistogramMath.normalizedHeights(this.histCounts);
      const barW: number = side / 64;
      const base: number = histogramBaseOf(this.channel);
      ctx.fillStyle = this.withAlpha(channelColor(this.channel), 0x30 / 255);
      for (let i = 0; i < 64; i++) {
        const h: number = heights[base + i] * side;
        if (h <= 0) {
          continue;
        }
        ctx.fillRect(side * i / 64, side - h, barW, h);
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
  
    // 输入/输出读数（29 §2.2-4 / §3.2-5）：选中控制点时显示 (x·255, y·255)，
    // 未选中不占位；拖动随 points 实时更新——回答「该往哪个方向调」
    if (this.selected >= 0 && this.selected < this.points.length) {
      const sp: CurvePoint = this.points[this.selected];
      ctx.fillStyle = '#FFFFFFFF';
      ctx.font = '10px sans-serif';
      ctx.fillText(`${Math.round(sp.x * 255)} · ${Math.round(sp.y * 255)}`, 6, 14);
    }
  }
    ctx.stroke();
    // 空心点：实心点在亮画面上会和曲线糊成一团；半径随 scale 等比（eb956f5）
    for (let i = 0; i < this.points.length; i++) {
      const p: CurvePoint = this.points[i];
      const cx: number = side * p.x;
      const cy: number = side * (1 - p.y);
      // 端点略大（29 §3.2-2）：1.15×，与中间点可区分；选中继续放大
      const endpoint: boolean = i === 0 || i === this.points.length - 1;
      const r: number = (i === this.selected ? 9 : endpoint ? 7.5 : 6.5) * scale;
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

  /**
   * 画布触控（上游 awaitEachGesture 拖点协议的 onTouch 直译；29 §3.2 手感三件）：
   * ① 命中区外扩 [HIT_EXT]——落点先 clamp 回画布再换算（端点在四角，触点一半在外也抓住）；
   * ② 命中半径走**像素域**（radiusDp × 面板等比，下限 [HIT_RADIUS_MIN_DP]）；
   * ③ **长按删除**替换双击删除（29 §3.2-3 裁决：快双击误删是摩擦点）——未移动按住到时
   *    删中间点、手势即止；端点不可删走普通拖动。抓点/删点震动：本侧无 VIBRATE 权限
   *    （权限红线在册），N/A 登记。
   */
  private onCanvasTouch(e: TouchEvent): void {
    if (!this.gpuMode) {
      return;
    }
    const side: number = this.side();
    if (side <= 0 || e.touches.length === 0) {
      return;
    }
    const t = e.touches[0];
    // 命中区外扩：命中层比画布四边各大 HIT_EXT，落点先 clamp 进画布域再归一（29 §3.2-1）
    const cx: number = Math.min(side, Math.max(0, t.x - HIT_EXT));
    const cy: number = Math.min(side, Math.max(0, t.y - HIT_EXT));
    const gx: number = cx / side;
    const gy: number = Math.min(1, Math.max(0, 1 - cy / side));
    if (e.type === TouchType.Down) {
      this.gestureHadPoint = false;
      this.gestureDead = false;
      let working: Array<CurvePoint> = this.points;
      // 命中半径走像素域（随面板放大同步变大），x/y 各自按画布宽/高归一
      const radiusDp: number = Math.max(HIT_RADIUS_MIN_DP, HIT_RADIUS_DP * this.panelScale());
      let index: number = CurveEdit.hitTestPx(working, gx, gy, radiusDp / side, radiusDp / side);
      if (index < 0) {
        const added: CurveInsertResult | undefined = CurveEdit.inserted(working, gx, gy);
        if (added !== undefined) {
          working = added.points;
          index = added.selectedIndex;
        }
      }
      if (index >= 0) {
        this.gestureHadPoint = true;
        this.selected = index;
        // 长按删除（29 §3.2-3）：仅中间点可删；未移动按住到时 → 删点、手势即止
        if (index > 0 && index < working.length - 1) {
          this.cancelDeleteTimer();
          this.deleteTimer = setTimeout((): void => {
            this.deleteTimer = -1;
            if (this.gestureDead) {
              return;
            }
            this.gestureDead = true;
            this.commit(CurveEdit.removed(this.points, this.selected), true);
            this.selected = -1;
            this.drawCurve();
          }, LONG_PRESS_DELETE_MS);
        }
        this.commit(working, false);
      }
      return;
    }
    if (e.type === TouchType.Move) {
      // Down 里记下的工作点集与下标经 this.points/selected 承接（单指编辑，无并发手）
      if (!this.gestureHadPoint || this.gestureDead) {
        return;
      }
      this.cancelDeleteTimer(); // 移动即取消长按（moved 语义）
      const index: number = this.selected;
      if (index < 0) {
        return;
      }
      this.commit(CurveEdit.moved(this.points, index, gx, gy), false);
      return;
    }
    if (e.type === TouchType.Up || e.type === TouchType.Cancel) {
      if (!this.gestureHadPoint || this.gestureDead) {
        return;
      }
      this.cancelDeleteTimer();
      const index: number = this.selected;
      if (index < 0) {
        return;
      }
      this.commit(this.points, true);
    }
  }

  private cancelDeleteTimer(): void {
    if (this.deleteTimer >= 0) {
      clearTimeout(this.deleteTimer);
      this.deleteTimer = -1;
    }
  }

  /** 右缘把手横拖（eb956f5 + 29 §1.2）：1:1 换算**边长**增量（vp），下限 = 最小尺寸、上限 = 可用区 */
  private onHandlePan(e: PanGestureEvent): void {
    const start: number = this.savedSide;
    // 1:1 换算边长增量（vp）；上限 = 可用区、下限 = 最小尺寸（29 §1.2：clamp 收口一处）
    this.savedSide = CurvePanelSize.clamp(start + e.offsetX, this.maxSide(), MIN_CURVE_SIDE);
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

              // 命中区（29 §3.2-1）：比画布四边各外扩 HIT_EXT 的透明层——端点正好在画布
              // 四角，触点一半落在画布外会被面板吃掉，「左下/右上端点抓不住」；落点在
              // onCanvasTouch 里先 clamp 回画布再归一。画布圆角 clip 只裁绘制、不裁命中区
              Stack({ alignContent: Alignment.Center }) {
                Canvas(this.ctx)
                  .width(this.side())
                  .height(this.side())
                  .onReady((): void => {
                    this.drawCurve();
                  })
                  .borderRadius(14)
                  .clip(true)
              }
              .width(this.side() + 2 * HIT_EXT)
              .height(this.side() + 2 * HIT_EXT)
              .onTouch((e: TouchEvent): void => {
                this.onCanvasTouch(e);
              })
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
