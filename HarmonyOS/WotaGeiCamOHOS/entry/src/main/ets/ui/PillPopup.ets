/**
 * 就近锚定的胶囊小弹层基础设施——上游 ui/design/PillPopup.kt 的最小移植
 * （对齐上游 38d712a）。
 *
 * 三条硬约定照搬（都是为了让"控件即入口"不打断取景）：
 * - 同层浮层（全屏遮罩 + position 定位面板），不用系统 Dialog——系统弹层遮罩会盖住取景器；
 * - 弹窗只做被点那件事；
 * - 点外部（遮罩）/「收起」即关，当前档高亮。
 *
 * 最小实现边界（登记 PORT_MAP §ui）：不迁上游动效层（LocalMotion 淡入/0.94 长开，这里
 * 量框后直接显形）、文字缩放层（TextScaleLayer）、HDS 阴影/令牌色（用静态深色近似）、
 * SheetSpec；锚点量取从 Compose onGloballyPositioned(boundsInWindow) 换成
 * getUIContext().getComponentUtils().getRectangleById(id)（窗口坐标系 px，同一语义）。
 * 取不到锚点（首帧未量到/id 失效/异常）回退底贴屏居中——回退分支必须存在。
 */
import { display } from '@kit.ArkUI';
import { nearestLadderIndex } from './SliderLadder';
import { HudType } from './HudTokens';

/** 弹窗内容区最多占可视区高度的比例；超了就内部滚，保证「收起」永远在屏内（上游 0.62f） */
export class PillAnchor {
  static readonly MAX_HEIGHT_RATIO: number = 0.62;
  /** HDS「气泡到屏幕边缘最小可以到 6vp」→ margin */
  static readonly MARGIN_VP: number = 6;
  /** 弹窗与锚点的间隙 */
  static readonly GAP_VP: number = 8;
  /** HDS「最大拉伸到 400vp 宽度时不再跟随放大」→ 面板 max 宽 */
  static readonly MAX_WIDTH_VP: number = 400;

  /**
   * 把弹窗摆到锚点附近（纯函数、单位全 px，直译 PillAnchor.place）：
   * 垂直优先放锚点上方（录制页控件大多贴屏幕下沿，往上才不压控件本身），
   * 上方放不下退到下方，两侧都放不下就贴可视区底边；水平以锚点中心对齐并收回边距内。
   */
  static place(anchor: PillRect, popup: PillSize, area: PillSize, gap: number, margin: number): PillPoint {
    const right: number = Math.max(margin, area.width - margin - popup.width);
    const bottom: number = Math.max(margin, area.height - margin - popup.height);
    const above: number = anchor.top - gap - popup.height;
    const below: number = anchor.bottom + gap;
    let y: number;
    if (above >= margin) {
      y = above;
    } else if (below + popup.height <= area.height - margin) {
      y = below;
    } else {
      y = bottom;
    }
    const xRaw: number = (anchor.left + anchor.right) / 2 - popup.width / 2;
    const x: number = Math.min(right, Math.max(margin, xRaw));
    return new PillPoint(x, Math.min(bottom, Math.max(margin, y)));
  }
}

/** 锚点矩形（窗口坐标 px；left/top/right/bottom 与上游 IntRect 同名同义） */
export interface PillRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** 尺寸（px） */
export interface PillSize {
  width: number;
  height: number;
}

/** 放置点（px） */
export class PillPoint {
  x: number;
  y: number;

  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

/** 全零矩形 = 锚点无效（量框未完成/条目刚被显隐收掉），上游 IntRect.Zero 同义 */
export function pillRectIsZero(r: PillRect): boolean {
  return r.left === 0 && r.top === 0 && r.right === 0 && r.bottom === 0;
}

/** 一档可选项：value 是写回参数总线的原始值（弹层内 disabled 档灰显且点不动） */
export class PillOption {
  value: number;
  label: string;
  enabled: boolean;

  constructor(value: number, label: string, enabled: boolean) {
    this.value = value;
    this.label = label;
    this.enabled = enabled;
  }
}

@Builder
function pillPopupEmptyContent() {
}

/**
 * 就近胶囊浮层本体：半透明遮罩（点击关）+ 量框后定位的面板（标题/滚动内容区/收起钮）。
 * 第一帧量不到面板尺寸时以透明存在、量到再定位显形——否则会看见弹窗从原点"跳"一下。
 */
@Component
export struct WotaPillPopup {
  /** 锚点（窗口 px）。零矩形走底贴屏回退 */
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  panelTitle: string = '';
  onDismiss: () => void = (): void => {};
  @BuilderParam content: () => void = pillPopupEmptyContent;
  /** 量框完成并定位后置真（透明期不拦截视觉） */
  @State shown: boolean = false;
  @State posX: number = 0;
  @State posY: number = 0;
  @State maxContentVp: number = 200;
  private density: number = 1;
  private screenW: number = 0;
  private screenH: number = 0;
  /** 上次放置所用面板尺寸（vp）：onAreaChange 报告同尺寸直接跳过，防重入环 */
  private lastWVp: number = 0;
  private lastHVp: number = 0;

  aboutToAppear(): void {
    try {
      const d: display.Display = display.getDefaultDisplaySync();
      this.density = d.densityPixels > 0 ? d.densityPixels : 1;
      this.screenW = Math.max(1, d.width);
      this.screenH = Math.max(1, d.height);
    } catch (e) {
      // 读不到显示参数（极端）：保持 density=1/屏幕 1px，place() 的钳制全部退到 margin 兜底
    }
    this.maxContentVp = PillAnchor.MAX_HEIGHT_RATIO * this.screenH / this.density;
  }

  /**
   * 量到面板真实尺寸后按锚点定位（锚点无效 → 底贴屏居中回退分支）。
   * T22 P3：撤销 shown 封口——内容增删导致面板尺寸变化时重跑 PillAnchor.place 更新位置态
   * （上游 Compose onGloballyPositioned 每次尺寸变化都会重定位）；同尺寸跳过，
   * 且 place() 对同一尺寸是纯函数（同进同出），position 变化不会再触发尺寸变化 → 无重入环。
   * shown 的首测语义保留：第一次量到才显形（opacity 0→1）。
   */
  private onMeasured(wVp: number, hVp: number): void {
    if (!(wVp > 0) || !(hVp > 0)) {
      return;
    }
    if (this.shown && wVp === this.lastWVp && hVp === this.lastHVp) {
      return;
    }
    const popup: PillSize = { width: wVp * this.density, height: hVp * this.density };
    const gapPx: number = PillAnchor.GAP_VP * this.density;
    const marginPx: number = PillAnchor.MARGIN_VP * this.density;
    let p: PillPoint;
    if (pillRectIsZero(this.anchor)) {
      // 回退：锚点未量到/失效——底贴屏、水平居中（绝不在原点闪现）
      p = new PillPoint(
        Math.max(marginPx, (this.screenW - popup.width) / 2),
        Math.max(marginPx, this.screenH - marginPx - popup.height));
    } else {
      p = PillAnchor.place(this.anchor, popup, { width: this.screenW, height: this.screenH }, gapPx, marginPx);
    }
    this.lastWVp = wVp;
    this.lastHVp = hVp;
    this.posX = p.x / this.density;
    this.posY = p.y / this.density;
    this.shown = true;
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#33000000')
        .onClick((): void => {
          this.onDismiss();
        })
      Column({ space: 8 }) {
        if (this.panelTitle !== '') {
          Text(this.panelTitle)
            .fontSize(11)
            .fontColor('#99FFFFFF')
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        Scroll() {
          Column({ space: 8 }) {
            this.content()
          }
        }
        .constraintSize({ maxHeight: this.maxContentVp })
        .scrollBar(BarState.Off)
        Text('收起')
          .fontSize(11)
          .fontColor('#99FFFFFF')
          .padding({ left: 10, right: 10, top: 4, bottom: 4 })
          .borderRadius(13)
          .backgroundColor('#22000000')
          .onClick((): void => {
            this.onDismiss();
          })
      }
      .constraintSize({ maxWidth: PillAnchor.MAX_WIDTH_VP })
      .backgroundColor('#E01C1C1E')
      .borderRadius(20)
      .border({ width: 1, color: '#26FFFFFF' })
      .padding({ left: 10, right: 10, top: 8, bottom: 8 })
      .position({ x: this.posX, y: this.posY })
      .opacity(this.shown ? 1 : 0)
      .onAreaChange((_old: Area, newArea: Area): void => {
        this.onMeasured(newArea.width as number, newArea.height as number);
      })
    }
    .width('100%')
    .height('100%')
  }
}

/**
 * 弹窗内的档位行：按 perRow 铺开、当前档高亮实底（上游 PillChoices）。
 * active 是整行总闸（录制中只读）：false 时每个 chip 灰显且点不动，
 * 与单档 PillOption.enabled 取与——能力否决与录制只读各管一段。
 */
@Component
export struct PillChoices {
  options: Array<PillOption> = [];
  @Prop selected: number = -1;
  onPick: (opt: PillOption) => void = (_opt: PillOption): void => {};
  perRow: number = 3;
  groupLabel: string = '';
  active: boolean = true;

  private rows(): Array<Array<PillOption>> {
    const out: Array<Array<PillOption>> = [];
    const per: number = Math.max(1, this.perRow);
    for (let i: number = 0; i < this.options.length; i += per) {
      out.push(this.options.slice(i, i + per));
    }
    return out;
  }

  build() {
    Column({ space: 6 }) {
      if (this.groupLabel !== '') {
        Text(this.groupLabel)
          .fontSize(11)
          .fontColor('#99FFFFFF')
          .maxLines(1)
      }
      ForEach(this.rows(), (row: Array<PillOption>, _idx: number): void => {
        Row({ space: 6 }) {
          ForEach(row, (opt: PillOption): void => {
            Text(opt.label)
              .fontSize(11)
              .fontColor(this.pickable(opt)
                ? (opt.value === this.selected ? '#FFFFFFFF' : '#E6FFFFFF')
                : '#59FFFFFF')
              .backgroundColor(opt.value === this.selected && this.pickable(opt)
                ? '#FF0A59F7' : '#33000000')
              .borderRadius(14)
              .padding({ left: 10, right: 10, top: 5, bottom: 5 })
              .maxLines(1)
              .onClick((): void => {
                if (this.pickable(opt)) {
                  this.onPick(opt);
                }
              })
          }, (opt: PillOption): string => `${opt.value}`)
        }
      }, (_row: Array<PillOption>, idx: number): string => `${idx}`)
    }
  }

  private pickable(opt: PillOption): boolean {
    return opt.enabled && this.active;
  }
}

/**
 * 弹窗内的刻度滑杆（上游 PillSlider 的 ladder 档位路径）：
 * - 梯（升序、已按运行时范围过滤）有效时滑杆在索引空间 0..size-1 内运行，
 *   松手/拖动回调都换回梯值——写进参数总线的值必为刻度值；
 * - 梯不足 2 点时不许静默退回连续滑杆（否则拖动可写出非刻度值，违反
 *   "拖动必落刻度"的验收口径）——禁用占位比假连续诚实（上游审查 P3 同款）；
 * - 当前值不在梯上（旧持久化恢复）时滑杆停在最近刻度，读数仍显示真实值。
 */
@Component
export struct PillSlider {
  @Prop current: number = 0;
  lo: number = 0;
  hi: number = 1;
  ladder: Array<number> = [];
  onValueChange: (v: number) => void = (_v: number): void => {};
  formatLabel: (v: number) => string = (v: number): string => `${v}`;
  active: boolean = true;
  sliderLabel: string = '';

  private ticks(): Array<number> {
    return this.ladder !== undefined && this.ladder.length >= 2 ? this.ladder : [];
  }

  private usable(): boolean {
    return this.hi > this.lo;
  }

  private shownValue(): number {
    return this.usable() ? Math.min(this.hi, Math.max(this.lo, this.current)) : this.current;
  }

  private tickIndexOf(v: number, ticks: Array<number>): number {
    return nearestLadderIndex(v, ticks);
  }

  build() {
    Column({ space: 2 }) {
      if (this.sliderLabel !== '') {
        Text(this.sliderLabel)
          .fontSize(11)
          .fontColor('#99FFFFFF')
          .maxLines(1)
      }
      Row({ space: 6 }) {
        if (this.ticks().length >= 2) {
          Slider({
            value: this.tickIndexOf(this.shownValue(), this.ticks()),
            min: 0,
            max: this.ticks().length - 1,
            step: 1,
            style: SliderStyle.InSet
          })
            .layoutWeight(1)
            .constraintSize({ maxWidth: 220 })
            .selectedColor('#FF0A59F7')
            .trackColor('#33FFFFFF')
            .showSteps(true)
            .enabled(this.active)
            .onChange((raw: number, _mode: SliderChangeMode): void => {
              const ticks: Array<number> = this.ticks();
              const i: number = Math.min(ticks.length - 1, Math.max(0, Math.round(raw)));
              const tick: number = ticks[i];
              // 梯值也必须落在参数域内：梯构造一般已按 range 过滤，这里是最后一道闸
              this.onValueChange(this.usable() ? Math.min(this.hi, Math.max(this.lo, tick)) : tick);
            })
        } else {
          // 梯不足 2 点：禁用占位（不退回连续滑杆）
          Slider({ value: 0, min: 0, max: 1, step: 1, style: SliderStyle.InSet })
            .layoutWeight(1)
            .constraintSize({ maxWidth: 220 })
            .trackColor('#33FFFFFF')
            .enabled(false)
        }
        Text(this.formatLabel(this.shownValue()))
          .fontSize(11)
          .fontColor(this.active ? '#E6FFFFFF' : '#59FFFFFF')
          .maxLines(1)
          .constraintSize({ minWidth: 54 })
      }
      .height(40)
    }
  }
}

/** 说明行（上游 Note/NoteText：一行小字注记） */
@Component
export struct PillNote {
  noteText: string = '';

  build() {
    Text(this.noteText)
      .fontSize(HudType.LABEL)
      .fontColor('#8CFFFFFF')
      .maxLines(2)
      .textAlign(TextAlign.Start)
  }
}
