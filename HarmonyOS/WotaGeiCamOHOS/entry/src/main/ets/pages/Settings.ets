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
 *
 * 应用内更新（对齐上游 SettingsScreen 的 UpdateSection，2026-10-23）：关于卡内的「检查更新」行，
 * 纯手动触发（不挂生命周期/进页面不自动检查）；说明经 ReleaseNotes 去 Markdown 后展示；
 * 下载按测速排序选渠道，落盘后 PK 魔数 + 体积 + SHA-256 三重校验，完成后走系统文件选择器
 * 另存，由用户在文件管理里交给系统安装器（OHOS 不开放三方拉起安装器，见 UpdateInstaller）。
 */
import { bundleManager, common } from '@kit.AbilityKit';
import { CameraSettings, SettingsStore } from '../ui/SettingsStore';
import { HudColor, HudShape, HudSpace, HudTouch, HudType } from '../ui/HudTokens';
import { WindowChrome } from '../ui/WindowChrome';
import { SliderKey, decodeContinuousSliders, encodeContinuousSliders } from '../ui/SliderSnap';
import { formatSize } from '../media/MediaFormat';
import { ProbeKind, ProbeResult, ReleaseNotes, UpdateConfig, probeLatestRelease } from '../update/UpdateChecker';
import { DownloadKind, DownloadOutcome, UpdateDownloader } from '../update/UpdateDownloader';
import { SaveResult, UpdateInstaller } from '../update/UpdateInstaller';

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

  // ---- 应用内更新（对齐上游 SettingsScreen 的 UpdateSection：纯手动、不挂生命周期） ----
  /** 行内状态：idle / checking / cancelling / latest / failed（有新版转对话框，行回 idle） */
  @State updateRow: string = 'idle';
  @State updatePromptVisible: boolean = false;
  @State updateTag: string = '';
  @State updateSizeText: string = '';
  @State updateNotesText: string = '';
  @State downloadVisible: boolean = false;
  /** 正在给各渠道测速选路（还没开始真正下载，进度数字尚无意义） */
  @State downloadMeasuring: boolean = false;
  @State downloadDone: boolean = false;
  @State downloadFailed: boolean = false;
  @State downloadRead: number = 0;
  @State downloadTotal: number = -1;
  @State versionName: string = '1.0.0';
  /** 版本号读不到时保持 0：任何可比 tag 都算「有新版」——宁可多提示一次，不漏更新 */
  private versionCode: number = 0;
  /** 探测取消探针（检查中点行 = 取消）；编排只在节点前查，置位后最坏再等一个探测超时 */
  private probeCancelled: boolean = false;
  /** 下载代际：每轮下载自增；旧代回调一律丢弃（取消/重开互不污染） */
  private downloadGen: number = 0;
  private pendingAvailable: ProbeResult | undefined = undefined;
  private downloadedPath: string = '';

  aboutToAppear(): void {
    this.readVersion();
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

  /** 版本号/版本码（更新比较用 versionCode：鸿蒙 versionName 不含 -rNN，见 UpdateChecker 文件头） */
  private readVersion(): void {
    try {
      const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
        bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
      this.versionName = info.versionName;
      this.versionCode = info.versionCode;
    } catch (e) {
      this.versionName = '未知';
      this.versionCode = 0;
    }
  }

  /** 「检查更新」行尾状态文案（与上游 strings_settings.xml 同语义） */
  private updateStatusText(): string {
    if (this.updateRow === 'checking') {
      return '检查中…';
    }
    if (this.updateRow === 'cancelling') {
      return '取消中…';
    }
    if (this.updateRow === 'latest') {
      return '已是最新版本';
    }
    if (this.updateRow === 'failed') {
      return '检查失败，请稍后重试';
    }
    return '点此联网检查 GitHub 最新版';
  }

  /**
   * 「检查更新」行点击：Idle → Checking →（Cancelling）→ Latest/Failed；
   * Checking 时再点 = 取消（置取消探针，行等编排跳完剩余节点回来）。
   * **纯手动纪律**：联网只由本方法触发，不挂生命周期/进页面自动检查。
   */
  private async onUpdateRowClick(): Promise<void> {
    if (this.updateRow === 'checking') {
      this.probeCancelled = true;
      this.updateRow = 'cancelling';
      return;
    }
    if (this.updateRow === 'cancelling' || this.downloadVisible) {
      return;
    }
    this.probeCancelled = false;
    this.updateRow = 'checking';
    let r: ProbeResult;
    try {
      r = await probeLatestRelease(this.versionCode,
        (url: string): Promise<string | undefined> =>
          UpdateDownloader.fetchJson(url, UpdateDownloader.PROBE_READ_TIMEOUT_MS),
        (url: string): Promise<string | undefined> => UpdateDownloader.fetchRedirectLocation(url),
        (): boolean => this.probeCancelled);
    } catch (e) {
      // 编排自身不该抛（网络层已全部收口）；真抛了也不能让行卡在「检查中…」
      this.updateRow = 'failed';
      return;
    }
    if (r.kind === ProbeKind.UPDATE_AVAILABLE) {
      // 取消落在最后一个在飞请求窗口内 → 丢弃回执，不许凭空弹「发现新版本」
      if (!this.probeCancelled) {
        this.pendingAvailable = r;
        this.updateTag = r.latestTag;
        this.updateSizeText = r.sizeBytes > 0 ? formatSize(r.sizeBytes) : '大小未知';
        const notes: string | undefined = ReleaseNotes.forDisplay(r.notes);
        this.updateNotesText = notes === undefined ? '' : notes;
        this.updatePromptVisible = true;
      }
      this.updateRow = 'idle';
      return;
    }
    if (r.kind === ProbeKind.UP_TO_DATE) {
      this.updateRow = this.probeCancelled ? 'idle' : 'latest';
      return;
    }
    if (r.kind === ProbeKind.CANCELLED) {
      this.updateRow = 'idle';
      return;
    }
    this.updateRow = this.probeCancelled ? 'idle' : 'failed';
  }

  /** 开始下载（测速选路 → 进度 → 完成/失败）；每轮新建代际，旧代回调一律丢弃 */
  private startDownload(): void {
    const ctx: common.Context | undefined = this.hostContext();
    const avail: ProbeResult | undefined = this.pendingAvailable;
    if (ctx === undefined || avail === undefined) {
      return;
    }
    this.updatePromptVisible = false;
    this.downloadVisible = true;
    this.downloadMeasuring = true;
    this.downloadDone = false;
    this.downloadFailed = false;
    this.downloadRead = 0;
    this.downloadTotal = avail.sizeBytes;
    const gen: number = ++this.downloadGen;
    UpdateDownloader.downloadPackage(ctx, avail.downloadUrl, avail.sizeBytes, avail.sha256,
      (read: number, total: number): void => {
        if (gen === this.downloadGen) {
          this.downloadRead = read;
          this.downloadTotal = total;
        }
      },
      (): boolean => gen !== this.downloadGen,
      (): void => {
        if (gen === this.downloadGen) {
          this.downloadMeasuring = false;
        }
      }).then((o: DownloadOutcome): void => {
        if (gen !== this.downloadGen) {
          return;
        }
        if (o.kind === DownloadKind.DONE) {
          this.downloadedPath = o.path;
          this.downloadRead = o.sizeBytes;
          this.downloadTotal = o.sizeBytes;
          this.downloadMeasuring = false;
          this.downloadDone = true;
          return;
        }
        if (o.kind === DownloadKind.CANCELLED) {
          this.downloadVisible = false;
          this.downloadMeasuring = false;
          return;
        }
        this.downloadMeasuring = false;
        this.downloadFailed = true;
      });
  }

  /** 取消下载：自增代际 → 旧代 isCancelled 立即为真，迟到回执全部丢弃 */
  private cancelDownload(): void {
    this.downloadGen++;
    this.downloadVisible = false;
    this.downloadMeasuring = false;
    this.downloadDone = false;
    this.downloadFailed = false;
  }

  /** 另存到用户可见目录（文件管理里点按即交给系统安装器） */
  private async saveUpdate(): Promise<void> {
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined || this.downloadedPath === '') {
      return;
    }
    const name: string = this.downloadedPath.substring(this.downloadedPath.lastIndexOf('/') + 1);
    const r: SaveResult = await UpdateInstaller.saveAs(ctx, this.downloadedPath, name);
    this.message = r.message;
    if (r.ok) {
      this.downloadVisible = false;
    }
  }

  /** 打开 Release 页（浏览器兜底：没有可拉起的安装器时用） */
  private async openReleasePage(): Promise<void> {
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined) {
      return;
    }
    const ok: boolean = await UpdateInstaller.openReleasePage(ctx, UpdateConfig.RELEASES_LATEST_PAGE);
    if (!ok) {
      this.message = '打开浏览器失败';
    }
  }

  /** 下载进度文案（总长未知时退化为只显示已下体量） */
  private downloadProgressText(): string {
    const total: number = this.downloadTotal;
    if (total > 0) {
      const pct: number = Math.min(100, Math.floor(this.downloadRead * 100 / total));
      return `${pct}% · 已下 ${formatSize(this.downloadRead)} / ${formatSize(total)}`;
    }
    return `已下 ${formatSize(this.downloadRead)}`;
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

  /** 弹层遮罩：全屏压暗 + 居中卡片（点击卡片外不关，与上游弹窗的显式按钮一致） */
  @Builder
  private modalBackdrop() {
    Column()
      .width('100%')
      .height('100%')
      .backgroundColor(HudColor.SCRIM_STRONG)
  }

  /** 更新可用提示框：版本号 + 包体大小 + 说明（限高滚动）+ 取消/下载 */
  @Builder
  private updatePromptDialog() {
    Stack({ alignContent: Alignment.Center }) {
      this.modalBackdrop()
      Column({ space: HudSpace.M }) {
        Text('发现新版本')
          .fontSize(HudType.TITLE)
          .fontColor(HudColor.TEXT_PRIMARY)
          .width('100%')
        Text(`新版本 ${this.updateTag} · HAP ${this.updateSizeText}`)
          .fontSize(HudType.VALUE)
          .fontColor(HudColor.TEXT_SECONDARY)
          .width('100%')
        if (this.updateNotesText !== '') {
          Text('更新内容')
            .fontSize(HudType.LABEL)
            .fontColor(HudColor.TEXT_TERTIARY)
            .width('100%')
          Scroll() {
            Text(this.updateNotesText)
              .fontSize(HudType.LABEL)
              .fontColor(HudColor.TEXT_SECONDARY)
              .width('100%')
          }
          .width('100%')
          // 说明区限高：上百行说明也不把按钮顶出屏幕（对齐上游 min(40% 窗高, 260dp)）
          .constraintSize({ maxHeight: 260 })
          .align(Alignment.TopStart)
        }
        Row({ space: HudSpace.S }) {
          Button('取消')
            .fontSize(HudType.VALUE)
            .fontColor(HudColor.TEXT_SECONDARY)
            .height(HudTouch.BUTTON_HEIGHT)
            .borderRadius(HudShape.PILL_RADIUS)
            .backgroundColor(HudColor.PILL_BG)
            .layoutWeight(1)
            .onClick((): void => {
              this.updatePromptVisible = false;
              this.pendingAvailable = undefined;
            })
          Button('下载更新')
            .fontSize(HudType.VALUE)
            .fontColor(HudColor.TEXT_PRIMARY)
            .height(HudTouch.BUTTON_HEIGHT)
            .borderRadius(HudShape.PILL_RADIUS)
            .backgroundColor(HudColor.ACCENT)
            .layoutWeight(1)
            .onClick((): void => {
              this.startDownload();
            })
        }
        .width('100%')
        .margin({ top: HudSpace.S })
      }
      .width('86%')
      .padding(HudSpace.XL)
      .backgroundColor(HudColor.CARD_BG)
      .borderRadius(HudShape.CARD_RADIUS)
    }
    .width('100%')
    .height('100%')
  }

  /** 下载对话框：选路 → 进度 → 完成（保存/打开下载页）→ 失败，四态 */
  @Builder
  private downloadDialog() {
    Stack({ alignContent: Alignment.Center }) {
      this.modalBackdrop()
      Column({ space: HudSpace.M }) {
        Text(this.downloadDone ? '下载完成，可以安装'
          : (this.downloadFailed ? '下载失败，请稍后重试'
            : (this.downloadMeasuring ? '正在选择最快的下载通道…' : '正在下载更新')))
          .fontSize(HudType.TITLE)
          .fontColor(this.downloadFailed ? HudColor.DANGER : HudColor.TEXT_PRIMARY)
          .width('100%')
        if (this.downloadMeasuring) {
          LoadingProgress()
            .width(36)
            .height(36)
          Row() {
            this.dialogCancelButton()
          }
          .width('100%')
        } else if (this.downloadDone) {
          Text(`更新包 ${formatSize(this.downloadRead)} 已校验通过，另存后用文件管理打开安装`)
            .fontSize(HudType.VALUE)
            .fontColor(HudColor.TEXT_SECONDARY)
            .width('100%')
          Button('保存到文件（安装）')
            .fontSize(HudType.VALUE)
            .fontColor(HudColor.TEXT_PRIMARY)
            .height(HudTouch.BUTTON_HEIGHT)
            .borderRadius(HudShape.PILL_RADIUS)
            .backgroundColor(HudColor.ACCENT)
            .width('100%')
            .onClick((): void => {
              this.saveUpdate();
            })
          Row({ space: HudSpace.S }) {
            Button('打开下载页')
              .fontSize(HudType.VALUE)
              .fontColor(HudColor.TEXT_SECONDARY)
              .height(HudTouch.BUTTON_HEIGHT)
              .borderRadius(HudShape.PILL_RADIUS)
              .backgroundColor(HudColor.PILL_BG)
              .layoutWeight(1)
              .onClick((): void => {
                this.openReleasePage();
              })
            Button('关闭')
              .fontSize(HudType.VALUE)
              .fontColor(HudColor.TEXT_SECONDARY)
              .height(HudTouch.BUTTON_HEIGHT)
              .borderRadius(HudShape.PILL_RADIUS)
              .backgroundColor(HudColor.PILL_BG)
              .layoutWeight(1)
              .onClick((): void => {
                this.downloadVisible = false;
              })
          }
          .width('100%')
        } else if (this.downloadFailed) {
          Row() {
            Button('关闭')
              .fontSize(HudType.VALUE)
              .fontColor(HudColor.TEXT_SECONDARY)
              .height(HudTouch.BUTTON_HEIGHT)
              .borderRadius(HudShape.PILL_RADIUS)
              .backgroundColor(HudColor.PILL_BG)
              .width('100%')
              .onClick((): void => {
                this.downloadVisible = false;
              })
          }
          .width('100%')
        } else {
          Text(this.downloadProgressText())
            .fontSize(HudType.VALUE)
            .fontColor(HudColor.TEXT_SECONDARY)
            .width('100%')
          if (this.downloadTotal > 0) {
            Progress({
              value: Math.min(this.downloadRead, this.downloadTotal),
              total: this.downloadTotal,
              type: ProgressType.Linear
            })
              .width('100%')
              .color(HudColor.ACCENT)
              .backgroundColor(HudColor.DIVIDER)
          } else {
            LoadingProgress()
              .width(36)
              .height(36)
          }
          Row() {
            this.dialogCancelButton()
          }
          .width('100%')
        }
      }
      .width('86%')
      .padding(HudSpace.XL)
      .backgroundColor(HudColor.CARD_BG)
      .borderRadius(HudShape.CARD_RADIUS)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  private dialogCancelButton() {
    Button('取消')
      .fontSize(HudType.VALUE)
      .fontColor(HudColor.TEXT_SECONDARY)
      .height(HudTouch.BUTTON_HEIGHT)
      .borderRadius(HudShape.PILL_RADIUS)
      .backgroundColor(HudColor.PILL_BG)
      .layoutWeight(1)
      .onClick((): void => {
        this.cancelDownload();
      })
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
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
                this.updateRowCard()
                Text(`版本 ${this.versionName}`)
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

      if (this.updatePromptVisible) {
        this.updatePromptDialog()
      }
      if (this.downloadVisible) {
        this.downloadDialog()
      }
    }
    .width('100%')
    .height('100%')
  }

  /**
   * 「检查更新」行（关于卡内、版本行上方）：纯手动——探测/下载只由本行点击触发，
   * 不挂任何生命周期观察者（上游 UpdateNetworkGuardTest 的纪律在鸿蒙侧靠此结构保持）。
   */
  @Builder
  private updateRowCard() {
    Row() {
      Text('检查更新')
        .fontSize(HudType.VALUE)
        .fontColor(HudColor.TEXT_PRIMARY)
        .layoutWeight(1)
      Text(this.updateStatusText())
        .fontSize(HudType.LABEL)
        .fontColor(this.updateRow === 'failed' ? HudColor.DANGER : HudColor.TEXT_TERTIARY)
    }
    .width('100%')
    .height(HudTouch.BUTTON_HEIGHT)
    .padding({ left: HudSpace.L, right: HudSpace.L })
    .borderRadius(HudShape.PILL_RADIUS)
    .backgroundColor(HudColor.PILL_BG)
    .onClick((): void => {
      this.onUpdateRowClick();
    })
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
