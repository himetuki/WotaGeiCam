/**
 * 设置页最小版：画幅/帧率/码率/光弧档/效果/HUD 网格，点选即保存（@ohos.data.preferences）。
 * 开发者测试入口：Index（AVRecorder 最小录制）/GlPreview（GL 试验/分段测试）。
 * 高级参数（曝光/ISO/EV/WB/快门）与 HUD 自定义布局留白。
 */
import { common } from '@kit.AbilityKit';
import { CameraSettings, SettingsStore } from '../ui/SettingsStore';

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

@Entry
@Component
struct Settings {
  @State settings: CameraSettings = new CameraSettings();
  @State message: string = '';
  @State loading: boolean = true;
  private saving: boolean = false;
  private pendingSave: boolean = false;

  aboutToAppear(): void {
    const ctx: common.Context | undefined = this.hostContext();
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
      return;
    }
    this.saving = true;
    while (this.pendingSave) {
      this.pendingSave = false;
      try {
        await SettingsStore.save(ctx, this.settings);
      } catch (e) {
        this.message = `保存失败: ${e}`;
        this.saving = false;
        return;
      }
    }
    this.saving = false;
    this.message = '已保存';
  }

  @Builder
  private row(title: string, options: Array<string>, selected: number, onPick: (index: number) => void) {
    Column({ space: 4 }) {
      Text(title)
        .fontSize(12)
        .fontColor('#B0BEC5')
        .width('100%')
      Row({ space: 6 }) {
        ForEach(options, (label: string, index: number): void => {
          Button(label)
            .fontSize(11)
            .height(30)
            .backgroundColor(index === selected ? '#2E7D32' : '#333333')
            .onClick((): void => {
              onPick(index);
            })
        }, (label: string): string => label)
      }
      .width('100%')
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 6 })
  }

  build() {
    Column({ space: 6 }) {
      Row({ space: 10 }) {
        Button('返回')
          .fontSize(12)
          .onClick((): void => {
            this.getUIContext().getRouter().back();
          })
        Text('设置')
          .fontSize(18)
          .fontColor(Color.White)
          .layoutWeight(1)
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8 })

      if (this.loading) {
        LoadingProgress()
          .width(32)
          .height(32)
      } else {
        Scroll() {
          Column({ space: 8 }) {
            this.row('画幅', ['1080p', '4K'], this.settings.sizeW >= 3840 ? 1 : 0,
              (index: number): void => {
                this.settings.sizeW = index === 1 ? 3840 : 1920;
                this.settings.sizeH = index === 1 ? 2160 : 1080;
                this.persist();
              })
            this.row('帧率', ['24', '25', '30'], this.settings.fps === 24 ? 0 : (this.settings.fps === 25 ? 1 : 2),
              (index: number): void => {
                this.settings.fps = index === 0 ? 24 : (index === 1 ? 25 : 30);
                this.persist();
              })
            this.row('码率', BITRATE_OPTIONS.map((o: BitrateOption): string => o.label),
              this.bitrateIndex(),
              (index: number): void => {
                this.settings.bitrate = BITRATE_OPTIONS[index].value;
                this.persist();
              })
            this.row('光弧（24/25fps）', ['补弧', '抽帧'], this.settings.arcMode === 2 ? 1 : 0,
              (index: number): void => {
                this.settings.arcMode = index === 1 ? 2 : 1;
                this.persist();
              })
            this.row('效果', ['直通', '曲线', '斑马', '峰值'], this.settings.effect,
              (index: number): void => {
                this.settings.effect = index;
                this.persist();
              })
            this.row('HUD 网格', ['开', '关'], this.settings.grid ? 0 : 1,
              (index: number): void => {
                this.settings.grid = index === 0;
                this.persist();
              })

            Text('画幅/帧率在录制中不可切换；效果可切换并同步预览/编码')
              .fontSize(10)
              .fontColor('#78909C')
              .padding({ left: 12, right: 12 })
            Text(this.message)
              .fontSize(11)
              .fontColor('#FFCC80')
              .maxLines(2)
              .padding({ left: 12, right: 12 })

            Row({ space: 8 }) {
              Button('开发者: Index')
                .fontSize(11)
                .onClick((): void => {
                  this.getUIContext().getRouter().pushUrl({ url: 'pages/Index' });
                })
              Button('开发者: GL 试验')
                .fontSize(11)
                .onClick((): void => {
                  this.getUIContext().getRouter().pushUrl({ url: 'pages/GlPreview' });
                })
            }
            .padding({ left: 12, right: 12, bottom: 24 })
          }
          .width('100%')
        }
        .layoutWeight(1)
        .scrollable(ScrollDirection.Vertical)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
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
