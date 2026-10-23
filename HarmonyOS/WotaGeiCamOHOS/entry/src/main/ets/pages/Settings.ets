/**
 * 设置页（T31 重做）：深色卡片分组 + List 顶对齐 + 48vp 触控。
 *
 * 真机取证（T31）：旧实现 Scroll 内容在屏内垂直居中，顶部空出约 539px；10fp/11fp 字号过小；
 * 开发者入口（Index/GL 试验）默认可见且无页头。本版：
 * - List（ListItem 天然顶对齐，不依赖 Scroll 的居中行为）承载分组卡片；
 * - 13fp 标签、48vp 选项、说明 11fp；深色系统栏（WindowChrome.applyStandardDark）；
 * - 返回为 48vp 深色胶囊；保存提示固定在页头下一行（高度固定，不推动布局）；
 * - 开发者入口默认隐藏：版本号 7 连点（2s 内）显形。
 *
 * 设置项：画幅/帧率/码率/光弧档/效果/HUD 网格，点选即保存（preferences，串行化）。
 */
import { common } from '@kit.AbilityKit';
import { CameraSettings, SettingsStore } from '../ui/SettingsStore';
import { HudColor, HudShape, HudSpace, HudTouch, HudType } from '../ui/HudTokens';
import { WindowChrome } from '../ui/WindowChrome';
import { SliderKey, decodeContinuousSliders, encodeContinuousSliders } from '../ui/SliderSnap';

/**
 * 滑杆吸附分组只列**有消费方**的 3 键（快门/感光度/色温，对齐上游 BlockSlider 的子集）；
 * ZOOM/TINT/DIOPTER/PEAKING 在键表（ui/SliderSnap.ets）中存在、UI 留白——对应上游
 * 七 chip，待变焦/对焦等胶囊批次接入后自动列全（登记差异）。
 */
class SliderSnapEntry {
  key: SliderKey;
  label: string;

  constructor(key: SliderKey, label: string) {
    this.key = key;
    this.label = label;
  }
}

const SLIDER_SNAP_ENTRIES: Array<SliderSnapEntry> = [
  new SliderSnapEntry(SliderKey.SHUTTER, '快门速度'),
  new SliderSnapEntry(SliderKey.ISO, '感光度'),
  new SliderSnapEntry(SliderKey.KELVIN, '色温')
];

class BitrateOption {
  label: string;
  value: number;

  constructor(label: string, value: number) {
    this.label = label;
    this.value = value;
  }
}

const BITRATE_OPTIONS: Array<BitrateOption> = [
  new BitrateOption('自动', 0),
  new BitrateOption('5M', 5_000_000),
  new BitrateOption('10M', 10_000_000),
  new BitrateOption('20M', 20_000_000),
  new BitrateOption('35M', 35_000_000),
  new BitrateOption('50M', 50_000_000),
];

const DEV_UNLOCK_TAPS: number = 7;
const DEV_UNLOCK_WINDOW_MS: number = 2000;

@Entry
@Component
struct Settings {
  @State settings: CameraSettings = new CameraSettings();
  @State message: string = '';
  @State loading: boolean = true;
  /** 开发者入口：默认隐藏，版本号 7 连点显形（T31 P1 收口） */
  @State devUnlocked: boolean = false;
  private saving: boolean = false;
  private pendingSave: boolean = false;
  private devTapCount: number = 0;
  private devTapStartMs: number = 0;

  aboutToAppear(): void {
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx !== undefined) {
      // 设置页恢复黑底系统栏（相机页是全屏透明栏，离开相机页必须复位）
      WindowChrome.lastWindow(ctx).then((win): Promise<void> =>
        WindowChrome.applyStandardDark(win)).catch((e: Object): void => {
      });
    }
    if (ctx === undefined) {
      this.loading = false;
      this.message = 'no host context';
      return;
    }
    SettingsStore.load(ctx).then((s: CameraSettings): void => {
      this.settings = s;
      this.loading = false;
    }).catch((e: Object): void => {
      this.loading = false;
      this.message = `读取设置失败: ${e}`;
    });
  }

  private hostContext(): common.Context | undefined {
    return this.getUIContext().getHostContext() as common.Context | undefined;
  }

  /** 保存串行化：连点时只落最后一次快照，避免后发先至 */
  private persist(): void {
    this.pendingSave = true;
    if (!this.saving) {
      this.drainSave();
    }
  }

  private async drainSave(): Promise<void> {
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined) {
      this.saving = false;
      this.pendingSave = false;
      this.message = '无上下文，未保存';
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

  /** 版本号连点解锁开发者入口（2s 内 7 次） */
  private onVersionTap(): void {
    const now: number = Date.now();
    if (now - this.devTapStartMs > DEV_UNLOCK_WINDOW_MS) {
      this.devTapStartMs = now;
      this.devTapCount = 0;
    }
    this.devTapCount++;
    if (this.devTapCount >= DEV_UNLOCK_TAPS) {
      this.devTapCount = 0;
      this.devUnlocked = true;
      this.message = '开发者入口已显示';
    }
  }

  /** 该滑杆当前是否为「连续值」模式（选中 = 不吸附刻度）；缺键/坏串 = 未选中 */
  private sliderSnapOn(key: SliderKey): boolean {
    return decodeContinuousSliders(this.settings.sliderContinuous).has(key);
  }

  /** 切换某参数的吸附开关并整串写回 prefs（点选 = 该参数改用连续值） */
  private toggleSliderSnap(key: SliderKey): void {
    const next: Set<SliderKey> = decodeContinuousSliders(this.settings.sliderContinuous);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    this.settings.sliderContinuous = encodeContinuousSliders(next);
    this.persist();
  }

  /** 分组卡片：标题 13fp + 选项 Flex 换行（全部 48vp 高） */
  @Builder
  private optionGroup(title: string, options: Array<string>, selected: number,
    onPick: (index: number) => void) {
    Column({ space: HudSpace.S }) {
      Text(title)
        .fontSize(HudType.VALUE)
        .fontColor(HudColor.TEXT_SECONDARY)
        .width('100%')
      Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
        ForEach(options, (label: string, index: number): void => {
          Button(label)
            .fontSize(HudType.VALUE)
            .fontColor(index === selected ? HudColor.TEXT_PRIMARY : HudColor.TEXT_SECONDARY)
            .height(HudTouch.PILL_HEIGHT)
            .borderRadius(HudShape.PILL_RADIUS)
            .border({ width: 1, color: index === selected ? HudColor.ACCENT : HudColor.DIVIDER })
            .backgroundColor(index === selected ? HudColor.PILL_BG_ACTIVE : HudColor.PILL_BG)
            .padding({ left: 16, right: 16 })
            .margin({ right: HudSpace.S, bottom: HudSpace.S })
            .onClick((): void => {
              onPick(index);
            })
        }, (label: string): string => label)
      }
      .width('100%')
    }
    .width('100%')
    .padding(HudSpace.L)
    .backgroundColor(HudColor.CARD_BG)
    .borderRadius(HudShape.CARD_RADIUS)
  }

  /**
   * 滑杆吸附分组（对齐上游 0dee86d SettingsBlock.SLIDER，紧跟「HUD 网格」之后）：
   * 默认每根滑杆拖动必落刻度，这里可逐个参数改成连续值。选中 = 该参数改用连续值
   * （不吸附）。曝光补偿本身按整数逐格取值，连续与梯等价，不列出。
   */
  @Builder
  private sliderSnapGroup() {
    Column({ space: HudSpace.S }) {
      Text('滑杆吸附')
        .fontSize(HudType.VALUE)
        .fontColor(HudColor.TEXT_SECONDARY)
        .width('100%')
      Text('默认每根滑杆拖动都会吸附到刻度；点选某个参数后它的滑杆改为连续值，可以停在任意位置（例如色温 5350K）。曝光补偿本身按整数逐格取值，不在此列。')
        .fontSize(HudType.LABEL)
        .fontColor(HudColor.TEXT_TERTIARY)
        .width('100%')
      Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
        ForEach(SLIDER_SNAP_ENTRIES, (entry: SliderSnapEntry, _index: number): void => {
          Button(entry.label)
            .fontSize(HudType.VALUE)
            .fontColor(this.sliderSnapOn(entry.key) ? HudColor.TEXT_PRIMARY : HudColor.TEXT_SECONDARY)
            .height(HudTouch.PILL_HEIGHT)
            .borderRadius(HudShape.PILL_RADIUS)
            .border({ width: 1, color: this.sliderSnapOn(entry.key) ? HudColor.ACCENT : HudColor.DIVIDER })
            .backgroundColor(this.sliderSnapOn(entry.key) ? HudColor.PILL_BG_ACTIVE : HudColor.PILL_BG)
            .padding({ left: 16, right: 16 })
            .margin({ right: HudSpace.S, bottom: HudSpace.S })
            .onClick((): void => {
              this.toggleSliderSnap(entry.key);
            })
        }, (entry: SliderSnapEntry): string => entry.label)
      }
      .width('100%')
    }
    .width('100%')
    .padding(HudSpace.L)
    .backgroundColor(HudColor.CARD_BG)
    .borderRadius(HudShape.CARD_RADIUS)
  }

  @Builder
  private header() {
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
      Text('设置')
        .fontSize(HudType.TITLE)
        .fontWeight(FontWeight.Medium)
        .fontColor(HudColor.TEXT_PRIMARY)
        .layoutWeight(1)
    }
    .width('100%')
    .padding({ left: HudSpace.L, right: HudSpace.L, top: HudSpace.M })
  }

  build() {
    Column({ space: HudSpace.XS }) {
      this.header()
      // 保存提示：固定一行高度，出现/消失不推动下方列表（T31 布局稳定）
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
        // List + ListItem 天然顶对齐（旧 Scroll 内容垂直居中导致顶部大空白）
        List({ space: HudSpace.M }) {
          ListItem() {
            this.optionGroup('画幅', ['1080p', '4K'], this.settings.sizeW >= 3840 ? 1 : 0,
              (index: number): void => {
                this.settings.sizeW = index === 1 ? 3840 : 1920;
                this.settings.sizeH = index === 1 ? 2160 : 1080;
                this.persist();
              })
          }
          ListItem() {
            this.optionGroup('帧率', ['24', '25', '30'],
              this.settings.fps === 24 ? 0 : (this.settings.fps === 25 ? 1 : 2),
              (index: number): void => {
                this.settings.fps = index === 0 ? 24 : (index === 1 ? 25 : 30);
                this.persist();
              })
          }
          ListItem() {
            this.optionGroup('码率', BITRATE_OPTIONS.map((o: BitrateOption): string => o.label),
              this.bitrateIndex(),
              (index: number): void => {
                this.settings.bitrate = BITRATE_OPTIONS[index].value;
                this.persist();
              })
          }
          ListItem() {
            this.optionGroup('光弧（24/25fps）', ['补弧', '抽帧'], this.settings.arcMode === 2 ? 1 : 0,
              (index: number): void => {
                this.settings.arcMode = index === 1 ? 2 : 1;
                this.persist();
              })
          }
          ListItem() {
            this.optionGroup('效果', ['直通', '曲线', '斑马', '峰值'], this.settings.effect,
              (index: number): void => {
                this.settings.effect = index;
                this.persist();
              })
          }
          ListItem() {
            this.optionGroup('HUD 网格', ['开', '关'], this.settings.grid ? 0 : 1,
              (index: number): void => {
                this.settings.grid = index === 0;
                this.persist();
              })
          }
          ListItem() {
            this.sliderSnapGroup()
          }
          ListItem() {
            Text('画幅/帧率在录制中不可切换；效果槽同时作用于预览与编码（同一 GL 效果 program）')
              .fontSize(HudType.LABEL)
              .fontColor(HudColor.TEXT_TERTIARY)
              .padding({ left: HudSpace.L, right: HudSpace.L })
          }
          ListItem() {
            Column({ space: HudSpace.XS }) {
              Text('版本 1.0.0')
                .fontSize(HudType.LABEL)
                .fontColor(HudColor.TEXT_TERTIARY)
                .onClick((): void => {
                  this.onVersionTap();
                })
              if (this.devUnlocked) {
                Row({ space: HudSpace.S }) {
                  Button('开发者: Index')
                    .fontSize(HudType.LABEL)
                    .fontColor(HudColor.TEXT_SECONDARY)
                    .height(HudTouch.BUTTON_HEIGHT)
                    .borderRadius(HudShape.PILL_RADIUS)
                    .backgroundColor(HudColor.PILL_BG)
                    .padding({ left: 12, right: 12 })
                    .onClick((): void => {
                      this.getUIContext().getRouter().pushUrl({ url: 'pages/Index' });
                    })
                  Button('开发者: GL 试验')
                    .fontSize(HudType.LABEL)
                    .fontColor(HudColor.TEXT_SECONDARY)
                    .height(HudTouch.BUTTON_HEIGHT)
                    .borderRadius(HudShape.PILL_RADIUS)
                    .backgroundColor(HudColor.PILL_BG)
                    .padding({ left: 12, right: 12 })
                    .onClick((): void => {
                      this.getUIContext().getRouter().pushUrl({ url: 'pages/GlPreview' });
                    })
                }
              }
            }
            .width('100%')
            .alignItems(HorizontalAlign.Start)
            .padding({ left: HudSpace.L, right: HudSpace.L, bottom: 24 })
          }
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: HudSpace.L, right: HudSpace.L })
        .scrollBar(BarState.Off)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(HudColor.BLACK)
  }

  private bitrateIndex(): number {
    for (let i = 0; i < BITRATE_OPTIONS.length; i++) {
      if (BITRATE_OPTIONS[i].value === this.settings.bitrate) {
        return i;
      }
    }
    return 0;
  }
}
