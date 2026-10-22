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
