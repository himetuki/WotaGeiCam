/**
 * 取景读数编辑页（J3b）：读数条的**顺序**与**显隐**。
 *
 * 与上游「编辑控件」页（HudLayoutEditor，自由拖拽换区/落格）的关系——**设计性偏离（登记）**：
 * 本侧取景 HUD 是 T31 真机取证后的固定结构（顶栏两行 + Dock 2×4 + 更多面板），没有上游
 * 五容器可自由布局的结构，自由拖拽没有对应落点；本页只做「READOUT 容器内的顺序 + 显隐」，
 * 顺序经 `HudLayoutTable.withZoneOrder` 落进 `hud_layout` 的**真实 v2 编码**（与上游逐字节
 * 同一份契约，将来若做完整编辑器，这份存量数据直接可用），显隐走 `hud_items` 掩码（J3a）。
 *
 * 纪律：编解码/顺序改写只经 core/HudLayout 与 core/Constants 的纯函数，本页不手写任何
 * 位运算或串拼接；每次动作即存（SettingsStore.save 串行化）。
 */
import { common } from '@kit.AbilityKit';
import { CameraSettings, SettingsStore } from '../ui/SettingsStore';
import { HudItem, HudItems } from '../core/Constants';
import { HudEntry, HudLayoutTable, HudZone } from '../core/HudLayout';
import { HudDragOrder } from '../core/HudDragOrder';
import { HudColor, HudShape, HudSpace, HudTouch, HudType } from '../ui/HudTokens';
import { WindowChrome } from '../ui/WindowChrome';

@Entry
@Component
struct HudEditor {
  @State settings: CameraSettings = new CameraSettings();
  /** READOUT 容器的完整顺序（含被隐藏的条目；隐藏只影响渲染，不改变顺序） */
  @State order: Array<HudEntry> = [];
  /** HDG 批：拖拽状态（-1 = 未拖）。按下记起下标，移动时按位移算目标下标，释放落位 */
  @State dragFrom: number = -1;
  @State dragTo: number = -1;
  private rowHeightVp: number = 0;
  @State loading: boolean = true;
  @State message: string = '';
  private saving: boolean = false;
  private pendingSave: boolean = false;
  private ctx: common.Context | undefined = undefined;

  aboutToAppear(): void {
    this.ctx = this.getUIContext().getHostContext() as common.Context | undefined;
    if (this.ctx === undefined) {
      this.loading = false;
      this.message = 'no host context';
      return;
    }
    WindowChrome.lastWindow(this.ctx).then((win): Promise<void> =>
      WindowChrome.applyStandardDark(win)).catch((e: Object): void => {
    });
    SettingsStore.load(this.ctx).then((s: CameraSettings): void => {
      this.settings = s;
      this.reloadOrder();
      this.loading = false;
    }).catch((e: Object): void => {
      this.loading = false;
      this.message = `读取设置失败: ${e}`;
    });
  }

  /** 从 hud_layout 原文解出 READOUT 顺序（坏串/空串 = 默认表 = HudItem 枚举声明序） */
  private reloadOrder(): void {
    const table: HudLayoutTable = HudLayoutTable.decode(this.settings.hudLayout);
    this.order = table.orderOf(HudZone.READOUT);
  }

  /** 保存串行化（与 Settings 页同款）：连点只落最后一次快照 */
  private persist(): void {
    if (this.ctx === undefined) {
      return;
    }
    this.pendingSave = true;
    if (!this.saving) {
      this.drainSave();
    }
  }

  private async drainSave(): Promise<void> {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      this.saving = false;
      this.pendingSave = false;
      return;
    }
    this.saving = true;
    while (this.pendingSave) {
      this.pendingSave = false;
      const ok: boolean = await SettingsStore.save(ctx, this.settings);
      if (!ok) {
        this.saving = false;
        this.pendingSave = false;
        this.message = '保存失败';
        return;
      }
    }
    this.saving = false;
    this.message = '已保存';
  }

  /** 顺序写回：withZoneOrder（normalize 收口）→ encode → hud_layout 原文 */
  private writeOrder(next: Array<HudEntry>): void {
    const table: HudLayoutTable = HudLayoutTable.decode(this.settings.hudLayout);
    const rewritten: HudLayoutTable = table.withZoneOrder(HudZone.READOUT, next);
    this.settings.hudLayout = rewritten.encode();
    this.order = rewritten.orderOf(HudZone.READOUT);
    this.persist();
  }

  private onMoveUp(index: number): void {
    if (index <= 0 || index >= this.order.length) {
      return;
    }
    const next: Array<HudEntry> = this.order.slice();
    const tmp: HudEntry = next[index - 1];
    next[index - 1] = next[index];
    next[index] = tmp;
    this.writeOrder(next);
  }

  private onMoveDown(index: number): void {
    if (index < 0 || index >= this.order.length - 1) {
      return;
    }
    const next: Array<HudEntry> = this.order.slice();
    const tmp: HudEntry = next[index + 1];
    next[index + 1] = next[index];
    next[index] = tmp;
    this.writeOrder(next);
  }

  /**
   * HDG 批：拖拽落位（长按拾起 → 上下拖 → 释放）。
   * 落点算式走 `HudDragOrder`（纯逻辑，单一真源）；顺序改写仍走 `writeOrder`
   * （`HudLayoutTable.withZoneOrder` 同容器路径），本页不手写任何位运算/串拼接。
   */
  private beginRowDrag(index: number): void {
    this.dragFrom = index;
    this.dragTo = index;
  }

  private updateRowDrag(offsetY: number): void {
    if (this.dragFrom < 0) {
      return;
    }
    this.dragTo = HudDragOrder.targetIndex(this.dragFrom, offsetY,
      this.rowHeightVp > 0 ? this.rowHeightVp : HudTouch.BUTTON_HEIGHT + HudSpace.S,
      this.order.length);
  }

  private finishRowDrag(): void {
    const from: number = this.dragFrom;
    const to: number = this.dragTo;
    this.dragFrom = -1;
    this.dragTo = -1;
    if (!HudDragOrder.changed(from, to)) {
      return; // 没换位：不写盘（免无谓保存与 IO）
    }
    this.writeOrder(HudDragOrder.moveTo(this.order, from, to));
    this.message = `已移到第 ${to + 1} 位`;
  }

  /** 行高实测（onAreaChange 落值；量不到时落价用兜底行高） */
  private onRowArea(area: Area): void {
    const h: number = Number(area.height);
    if (Number.isFinite(h) && h > 0) {
      this.rowHeightVp = h;
    }
  }

  private onToggleVisible(item: HudItem): void {
    this.settings.hudItems = HudItems.toggle(this.settings.hudItems, item);
    this.persist();
  }

  /** 恢复默认：整表回默认（本侧容器位置/格子本来就是默认值，等价于只重置顺序） */
  private onResetOrder(): void {
    const table: HudLayoutTable = HudLayoutTable.default();
    this.settings.hudLayout = table.encode();
    this.order = table.orderOf(HudZone.READOUT);
    this.persist();
  }

  /** 行渲染只认 READOUT 里 item 可解析的条目（越界 ordinal 的坏条目由 normalize 丢弃，防御渲染） */
  private rowLabel(entry: HudEntry): string {
    const item: HudItem | undefined = entry.item;
    return item === undefined ? entry.id : HudItems.label(item);
  }

  build() {
    Column({ space: HudSpace.XS }) {
      Row({ space: HudSpace.M }) {
        Button('返回')
          .fontSize(HudType.VALUE)
          .fontColor(HudColor.TEXT_PRIMARY)
          .height(HudTouch.BUTTON_HEIGHT)
          .borderRadius(HudShape.PILL_RADIUS)
          .backgroundColor(HudColor.PILL_BG)
          .padding({ left: 14, right: 14 })
          .onClick((): void => {
            this.getUIContext().getRouter().back();
          })
        Text('自定义取景读数')
          .fontSize(HudType.TITLE)
          .fontWeight(FontWeight.Medium)
          .fontColor(HudColor.TEXT_PRIMARY)
          .layoutWeight(1)
      }
      .width('100%')
      .padding({ left: HudSpace.L, right: HudSpace.L, top: HudSpace.M })

      Text(this.message)
        .fontSize(HudType.LABEL)
        .fontColor(this.message.indexOf('失败') >= 0 ? HudColor.DANGER : HudColor.WARN)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .height(20)
        .padding({ left: HudSpace.L, right: HudSpace.L })

      if (this.loading) {
        Column() {
          LoadingProgress()
            .width(32)
            .height(32)
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
      } else {
        Column({ space: HudSpace.S }) {
          Text('顺序自上而下对应取景页读数条从左到右、从上到下；长按 ≡ 拖动或点 ↑/↓ 调整。关掉显示的读数保留位置，重新打开回到原位。')
            .fontSize(HudType.LABEL)
            .fontColor(HudColor.TEXT_TERTIARY)
            .width('100%')
          List({ space: HudSpace.S }) {
            ForEach(this.order, (entry: HudEntry, index: number): void => {
              ListItem() {
                Row({ space: HudSpace.S }) {
                  // HDG 批：长按拾起拖拽把手（≡）——与 ↑/↓ 按钮并存，两种操作都可
                  Text('≡')
                    .fontSize(HudType.VALUE)
                    .fontColor(this.dragFrom === index ? HudColor.ACCENT : HudColor.TEXT_TERTIARY)
                    .width(24)
                    .textAlign(TextAlign.Center)
                    .accessibilityText('长按拖动调整顺序')
                    .gesture(
                      LongPressGesture({ repeat: false, duration: 400 })
                        .onAction((): void => {
                          this.beginRowDrag(index);
                        })
                    )
                  Text(this.rowLabel(entry))
                    .fontSize(HudType.VALUE)
                    .fontColor(HudColor.TEXT_PRIMARY)
                    .layoutWeight(1)
                  Button('↑')
                    .fontSize(HudType.VALUE)
                    .fontColor(HudColor.TEXT_SECONDARY)
                    .height(HudTouch.BUTTON_HEIGHT)
                    .width(HudTouch.BUTTON_HEIGHT)
                    .borderRadius(HudShape.PILL_RADIUS)
                    .backgroundColor(HudColor.PILL_BG)
                    .enabled(index > 0)
                    .onClick((): void => {
                      this.onMoveUp(index);
                    })
                  Button('↓')
                    .fontSize(HudType.VALUE)
                    .fontColor(HudColor.TEXT_SECONDARY)
                    .height(HudTouch.BUTTON_HEIGHT)
                    .width(HudTouch.BUTTON_HEIGHT)
                    .borderRadius(HudShape.PILL_RADIUS)
                    .backgroundColor(HudColor.PILL_BG)
                    .enabled(index < this.order.length - 1)
                    .onClick((): void => {
                      this.onMoveDown(index);
                    })
                  Toggle({ type: ToggleType.Switch, isOn: this.rowVisible(entry) })
                    .selectedColor(HudColor.ACCENT)
                    .onChange((on: boolean): void => {
                      const item: HudItem | undefined = entry.item;
                      if (item !== undefined && on !== this.rowVisible(entry)) {
                        this.onToggleVisible(item);
                      }
                    })
                }
                .width('100%')
                .padding({ left: HudSpace.L, right: HudSpace.L })
                .onAreaChange((_o: Area, area: Area): void => {
                  this.onRowArea(area);
                })
                // 拖拽跟手：仅当本行是拾起行时吃纵向拖动（其余行不拦触摸）
                .gesture(
                  PanGesture({ fingers: 1, direction: PanDirection.Vertical, distance: 1 })
                    .onActionUpdate((e: GestureEvent): void => {
                      if (this.dragFrom === index) {
                        this.updateRowDrag(e.offsetY);
                      }
                    })
                    .onActionEnd((): void => {
                      if (this.dragFrom === index) {
                        this.finishRowDrag();
                      }
                    })
                )
                .opacity(this.dragFrom >= 0 && this.dragFrom !== index ? 0.6 : 1)
              }
            }, (entry: HudEntry): string => `${entry.id}_${this.order.indexOf(entry)}`)
          }
          .layoutWeight(1)
          .width('100%')
          .scrollBar(BarState.Off)

          Button('恢复默认顺序')
            .fontSize(HudType.VALUE)
            .fontColor(HudColor.TEXT_SECONDARY)
            .height(HudTouch.BUTTON_HEIGHT)
            .borderRadius(HudShape.PILL_RADIUS)
            .backgroundColor(HudColor.PILL_BG)
            .width('100%')
            .margin({ bottom: HudSpace.L })
            .onClick((): void => {
              this.onResetOrder();
            })
        }
        .width('100%')
        .layoutWeight(1)
        .padding({ left: HudSpace.L, right: HudSpace.L })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(HudColor.BLACK)
  }

  /** 该行是否开着显示（hud_items 掩码；item 解析不出的坏条目按关显示，不可切） */
  private rowVisible(entry: HudEntry): boolean {
    const item: HudItem | undefined = entry.item;
    return item === undefined ? false : HudItems.isOn(this.settings.hudItems, item);
  }
}
