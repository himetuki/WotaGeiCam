/**
 * 蓝牙音箱面板（上游 `ui/dialog/BtSpeakerPanel.kt` 的**减法版**）。
 *
 * 减法定版（登记，调研报告 §1）：本页只做「配对列表 + A2DP 连接态 + 电量（实时）」。
 * 不迁：音量滑杆（无 setVolume 等价）、播放/上一曲/下一曲三键（avsession 控制器需 system
 * 权限）、扫描发现（需定位权限、连接管理交给系统设置）——页脚一行说明讲清楚，不做残废按钮。
 *
 * 入口：相机页「更多」面板 →「蓝牙音箱」。页面隐藏/销毁即解电量监听（蓝牙订阅常开费电）。
 */
import { BtSpeakerController } from '../bt/BtSpeakerController';
import { BtEntry, BtSpeakerModel } from '../bt/BtSpeakerModel';
import { HudColor, HudShape, HudSpace, HudTouch, HudType } from '../ui/HudTokens';
import { WindowChrome } from '../ui/WindowChrome';

@Entry
@Component
struct BtPanel {
  @State entries: Array<BtEntry> = [];
  @State adapterOn: boolean = true;
  @State loading: boolean = true;
  @State message: string = '';
  private disposed: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    this.disposed = false;
    const ctx: Context | undefined = this.getUIContext().getHostContext() as Context | undefined;
    if (ctx !== undefined) {
      WindowChrome.lastWindow(ctx).then((win): Promise<void> =>
        WindowChrome.applyStandardDark(win)).catch((_e: Object): void => {
      });
    }
    this.reload();
    BtSpeakerController.watchBattery((level: number): void => {
      this.applyBattery(level);
    });
  }

  onPageShow(): void {
    if (this.disposed) {
      return;
    }
    this.reload();
  }

  aboutToDisappear(): void {
    this.disposed = true;
    BtSpeakerController.unwatchBattery();
  }

  /** 拉一份完整数据（代序防迟到回执串台）；电量监听在 aboutToAppear 挂一次即可 */
  private reload(): void {
    const seq: number = ++this.loadSeq;
    this.loading = true;
    this.adapterOn = BtSpeakerController.adapterOn();
    if (!this.adapterOn) {
      this.entries = [];
      this.loading = false;
      return;
    }
    BtSpeakerController.loadEntries().then((list: Array<BtEntry>): void => {
      if (this.disposed || seq !== this.loadSeq) {
        return;
      }
      this.entries = list;
      this.loading = false;
      this.message = list.length === 0 ? '没有已配对的蓝牙设备' : '';
    }).catch((e: Object): void => {
      if (this.disposed || seq !== this.loadSeq) {
        return;
      }
      this.loading = false;
      this.message = `读取失败: ${e}`;
    });
  }

  /**
   * 电量回执合并（BatteryInfo 不带 deviceId——SDK 核实，见 BtSpeakerController 登记说明）：
   * 落到**首个已连接**条目上；一个都没有就丢弃（回执早于列表加载完成的窗口）。
   */
  private applyBattery(level: number): void {
    if (this.disposed) {
      return;
    }
    const next: Array<BtEntry> = [];
    let hit: boolean = false;
    for (const e of this.entries) {
      if (!hit && e.a2dpConnected) {
        next.push(new BtEntry(e.deviceId, e.name, e.a2dpConnected, level));
        hit = true;
      } else {
        next.push(e);
      }
    }
    if (hit) {
      this.entries = next;
    }
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
        Text('蓝牙音箱')
          .fontSize(HudType.TITLE)
          .fontWeight(FontWeight.Medium)
          .fontColor(HudColor.TEXT_PRIMARY)
          .layoutWeight(1)
        Button('刷新')
          .fontSize(HudType.VALUE)
          .fontColor(HudColor.TEXT_SECONDARY)
          .height(HudTouch.BUTTON_HEIGHT)
          .borderRadius(HudShape.PILL_RADIUS)
          .backgroundColor(HudColor.PILL_BG)
          .padding({ left: 14, right: 14 })
          .onClick((): void => {
            this.reload();
          })
      }
      .width('100%')
      .padding({ left: HudSpace.L, right: HudSpace.L, top: HudSpace.M })

      Text(this.message)
        .fontSize(HudType.LABEL)
        .fontColor(HudColor.WARN)
        .maxLines(1)
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
      } else if (!this.adapterOn) {
        Column({ space: HudSpace.S }) {
          Text('蓝牙未开启')
            .fontSize(HudType.VALUE)
            .fontColor(HudColor.TEXT_PRIMARY)
          Text('请在系统控制中心或设置里开启蓝牙后回到本页刷新。')
            .fontSize(HudType.LABEL)
            .fontColor(HudColor.TEXT_TERTIARY)
            .textAlign(TextAlign.Center)
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .padding({ left: HudSpace.XL, right: HudSpace.XL })
      } else {
        List({ space: HudSpace.S }) {
          ForEach(this.entries, (entry: BtEntry): void => {
            ListItem() {
              Row({ space: HudSpace.S }) {
                Column({ space: 1 }) {
                  Text(entry.name === '' ? entry.deviceId : entry.name)
                    .fontSize(HudType.VALUE)
                    .fontColor(HudColor.TEXT_PRIMARY)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .layoutWeight(1)
                  Text(BtSpeakerModel.batteryText(entry.batteryLevel))
                    .fontSize(HudType.LABEL)
                    .fontColor(HudColor.TEXT_TERTIARY)
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)

                Text(BtSpeakerModel.stateText(entry.a2dpConnected))
                  .fontSize(HudType.LABEL)
                  .fontColor(entry.a2dpConnected ? HudColor.ACCENT : HudColor.TEXT_TERTIARY)
              }
              .width('100%')
              .padding({ left: HudSpace.L, right: HudSpace.L, top: HudSpace.S, bottom: HudSpace.S })
            }
          }, (entry: BtEntry): string => `${entry.deviceId}_${entry.batteryLevel}_${entry.a2dpConnected}`)
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
      }

      Text(BtSpeakerController.limitationNote())
        .fontSize(HudType.LABEL)
        .fontColor(HudColor.TEXT_TERTIARY)
        .width('100%')
        .padding({ left: HudSpace.L, right: HudSpace.L, bottom: HudSpace.L })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(HudColor.BLACK)
  }
}
