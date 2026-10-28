/**
 * 设置页（T31 重做，横屏双栏复刻安卓侧 v0.0.11 截图）：大号蓝色分组标题 + 深灰卡片组 +
 * 组内 1px 分隔线 + 40vp 胶囊；屏宽 > 屏高时两列并排（24vp 内边距 / 32vp 列距），竖屏单列。
 *
 * 布局与视觉：
 * - 横屏判别 = display.getDefaultDisplaySync 的 width/height 除以 densityPixels（实测 vp，
 *   随 display.on('change') 刷新）；双栏 = Row 内两个 Scroll 各 layoutWeight(1)。
 * - 组内行：左侧白 15fp 标题 + 灰 13fp 说明 + 右侧蓝 13fp 值 + 右侧 Toggle/胶囊；
 *   选中胶囊 = 亮蓝填充白字，未选中 = 深底浅灰字 + 1px 描边。
 * - 权限状态组：bundleManager accessTokenId + abilityAccessCtrl.checkAccessTokenSync 真查；
 *   查不到显示「未知」，不用编译期声明冒充运行态。
 *
 * 设置项：画幅/帧率/码率/光弧/效果/HUD 网格，点选即保存（preferences，串行化）。
 * 所有控件仍直写现有 CameraSettings 字段并走原 persist()/SettingsStore，功能与持久化不变。
 *
 * 应用内更新（对齐上游 SettingsScreen 的 UpdateSection，2026-10-23）：关于卡内的「检查更新」行，
 * 纯手动触发（不挂生命周期/进页面不自动检查）；说明经 ReleaseNotes 去 Markdown 后展示；
 * 下载按测速排序选渠道，落盘后 PK 魔数 + 体积 + SHA-256 三重校验，完成后走系统文件选择器
 * 另存，由用户在文件管理里交给系统安装器（OHOS 不开放三方拉起安装器，见 UpdateInstaller）。
 */
import { abilityAccessCtrl, bundleManager, common, Permissions } from '@kit.AbilityKit';
import { display } from '@kit.ArkUI';
import { CameraSettings, SettingsStore } from '../ui/SettingsStore';
import { HudColor, HudShape, HudSpace, HudTouch, HudType } from '../ui/HudTokens';
import { WindowChrome } from '../ui/WindowChrome';
import { SliderKey, decodeContinuousSliders, encodeContinuousSliders } from '../ui/SliderSnap';
import { formatSize } from '../media/MediaFormat';
import { HudItem, HudItems } from '../core/Constants';
import { REF_LINE_ENTRIES, RefLine, RefLineEntry } from '../core/RefLine';
import { UIOrientation } from '../core/UIOrientation';
import { UserSettings } from '../core/UserSettings';
import { ProbeKind, ProbeResult, ReleaseNotes, UpdateConfig, probeLatestRelease } from '../update/UpdateChecker';
import { DownloadKind, DownloadOutcome, UpdateDownloader } from '../update/UpdateDownloader';
import { SaveResult, UpdateInstaller } from '../update/UpdateInstaller';
import { Diag, ExportResult } from '../diag/Diag';
import { DiagShare } from '../diag/DiagShare';

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

/** 取景效果四档（下标即 settings.effect 的取值；「屏幕监看」与录制卡共用同一标签表） */
const EFFECT_LABELS: Array<string> = ['直通', '曲线', '斑马', '峰值'];

const DEV_UNLOCK_TAPS: number = 7;
const DEV_UNLOCK_WINDOW_MS: number = 2000;

/**
 * 设置页局部视觉令牌（横屏复刻安卓侧 v0.0.11 截图）：只在 Settings.ets 使用，
 * 不动 ui/HudTokens 公共真源。HudColor.ACCENT 是录制粉，截图里的设置页强调色是亮蓝。
 */
class SettingsVisual {
  static readonly BLUE: string = '#FF4D8DFF';
  /** 胶囊选中底（比标题蓝更饱和一档，对齐截图） */
  static readonly BLUE_ACTIVE: string = '#FF2F6BFF';
  static readonly CARD_BG: string = '#FF1E1E22';
  static readonly PILL_BG: string = '#FF17171A';
  static readonly PILL_BORDER: string = '#FF3C3C43';
  static readonly PILL_TEXT: string = '#FFD5D8DE';
  static readonly TEXT_DIM: string = '#FF9A9FA8';
  static readonly DIVIDER: string = '#FF2E2E33';
  static readonly TITLE: number = 24;
  static readonly ROW_TITLE: number = 15;
  static readonly ROW_DESC: number = 13;
  static readonly VALUE: number = 13;
  static readonly PILL_TEXT_SIZE: number = 14;
  static readonly PILL_HEIGHT: number = 40;
  static readonly PILL_RADIUS: number = 20;
  static readonly CARD_RADIUS: number = 16;
  static readonly CARD_PAD: number = 16;
  static readonly COLUMN_PAD: number = 24;
  static readonly COLUMN_GAP: number = 32;
  static readonly SECTION_GAP: number = 28;
  static readonly HEAD_GAP: number = 10;
  static readonly SCROLL_TOP: number = 8;
}

@Entry
@Component
struct Settings {
  @State settings: CameraSettings = new CameraSettings();
  @State message: string = '';
  @State loading: boolean = true;
  /** 开发者入口：默认隐藏，版本号 7 连点显形（T31 P1 收口） */
  @State devUnlocked: boolean = false;
  /** 横屏（实测 vp 宽 > 高）：双栏并排；竖屏保持单列 */
  @State landscape: boolean = false;
  /** 权限状态：真实查询授权态（查不到 = 未知，不造假运行态） */
  @State permCamera: string = '未知';
  @State permMic: string = '未知';
  private displayCallback: ((id: number) => void) | undefined = undefined;
  /** DIAG：诊断区块动作忙态（clear/export 互斥）与结果行 */
  @State diagBusy: string = '';
  @State diagStatus: string = '';
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
    this.updateLayoutMode();
    this.watchDisplay();
    this.readPermissions();
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

  aboutToDisappear(): void {
    const cb: ((id: number) => void) | undefined = this.displayCallback;
    if (cb !== undefined) {
      try {
        display.off('change', cb);
      } catch (e) {
      }
      this.displayCallback = undefined;
    }
  }

  /** 回落本页时刷新实测布局与授权态（权限可能在取景页弹框后变化） */
  onPageShow(): void {
    this.updateLayoutMode();
    this.readPermissions();
  }

  /** 横屏判别：实测屏幕 vp 宽高（display 尺寸 px / densityPixels），旋转后随 display.change 刷新 */
  private updateLayoutMode(): void {
    try {
      const d: display.Display = display.getDefaultDisplaySync();
      const density: number = d.densityPixels > 0 ? d.densityPixels : 1;
      this.landscape = d.width / density > d.height / density;
    } catch (e) {
      this.landscape = false;
    }
  }

  private watchDisplay(): void {
    if (this.displayCallback !== undefined) {
      return;
    }
    const cb: (id: number) => void = (id: number): void => {
      this.updateLayoutMode();
    };
    this.displayCallback = cb;
    try {
      display.on('change', cb);
    } catch (e) {
      this.displayCallback = undefined;
    }
  }

  /**
   * 权限状态：bundle 的 accessTokenId + abilityAccessCtrl.checkAccessTokenSync 查真实授权态。
   * 任何一步取不到（未声明/系统拒绝）都显示「未知」——不用编译期声明冒充运行态。
   */
  private readPermissions(): void {
    try {
      const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
        bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
      const tokenId: number = info.appInfo.accessTokenId;
      const at: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
      const camPerm: Permissions = 'ohos.permission.CAMERA';
      const micPerm: Permissions = 'ohos.permission.MICROPHONE';
      this.permCamera = at.checkAccessTokenSync(tokenId, camPerm) ===
        abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED ? '已授予' : '未授予';
      this.permMic = at.checkAccessTokenSync(tokenId, micPerm) ===
        abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED ? '已授予' : '未授予';
    } catch (e) {
      this.permCamera = '未知';
      this.permMic = '未知';
    }
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

  // ------------------------------------------------------------------ DIAG：诊断区块（HM7-UI-02）

  /** 总开关：运行期立即生效（日志器不重启应用，上游 Diag.setEnabled 同口径）；关闸级联关详细档 */
  private setDiagEnabled(on: boolean): void {
    this.settings.diagEnabled = on;
    if (!on && this.settings.diagVerbose) {
      this.settings.diagVerbose = false;
      Diag.setVerbose(false);
    }
    Diag.setEnabled(on);
    this.persistDiag();
  }

  private setDiagVerbose(on: boolean): void {
    this.settings.diagVerbose = on;
    Diag.setVerbose(on);
    this.persistDiag();
  }

  /** 专用同步落盘（persistDiagFlags：putSync+flushSync；通用 persist() 的异步批写不走这里） */
  private persistDiag(): void {
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined) {
      return;
    }
    SettingsStore.persistDiagFlags(ctx, this.settings.diagEnabled, this.settings.diagVerbose);
  }

  private async clearDiag(): Promise<void> {
    if (this.diagBusy !== '') {
      return;
    }
    this.diagBusy = 'clear';
    try {
      await Diag.clear();
      this.diagStatus = '日志已清除（当前件/轮转件/崩溃件）';
    } catch (e) {
      this.diagStatus = `清除失败：${e}`;
    } finally {
      this.diagBusy = '';
    }
  }

  private async exportDiag(): Promise<void> {
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined || this.diagBusy !== '') {
      return;
    }
    this.diagBusy = 'export';
    try {
      const r: ExportResult | null = await Diag.export(ctx);
      this.diagStatus = r === null ? '导出失败' : `已导出：${r.path}（${r.bytes}B）`;
    } catch (e) {
      this.diagStatus = `导出失败：${e}`;
    } finally {
      this.diagBusy = '';
    }
  }

  /** DIAG-SHARE：导出到缓存目录并拉起系统分享面板（不联网） */
  private async shareDiag(): Promise<void> {
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined || this.diagBusy !== '') {
      return;
    }
    this.diagBusy = 'share';
    try {
      const err: string = await DiagShare.share(ctx as common.UIAbilityContext);
      this.diagStatus = err === '' ? '已拉起分享面板（目标应用由你选择）' : err;
    } catch (e) {
      this.diagStatus = `分享失败：${e}`;
    } finally {
      this.diagBusy = '';
    }
  }

  /** DIAG-SHARE：报告交系统保存选择器，用户自选位置（公共目录的零权限等价路径） */
  private async saveDiagAs(): Promise<void> {
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined || this.diagBusy !== '') {
      return;
    }
    this.diagBusy = 'saveas';
    try {
      const err: string = await DiagShare.saveAs(ctx as common.UIAbilityContext);
      this.diagStatus = err === '' ? '已保存到你选定的位置' : err;
    } catch (e) {
      this.diagStatus = `另存失败：${e}`;
    } finally {
      this.diagBusy = '';
    }
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

  // ------------------------------------------------ 设置页视觉（横屏复刻安卓侧 v0.0.11 截图）

  /** 分组标题：大号亮蓝加粗（屏宽 > 屏高时双栏，标题跨列内宽） */
  @Builder
  private sectionTitle(title: string) {
    Text(title)
      .fontSize(SettingsVisual.TITLE)
      .fontWeight(FontWeight.Bold)
      .fontColor(SettingsVisual.BLUE)
      .width('100%')
  }

  /** 卡片组：深灰圆角容器（#1E1E22 / 16vp 圆角 / 16vp 内边距） */
  @Builder
  private cardDivider() {
    Divider()
      .strokeWidth(0.5)
      .color(SettingsVisual.DIVIDER)
      .width('100%')
  }

  /** 行头：白 15fp 标题 + 灰 13fp 说明（有才画）+ 右值蓝 13fp（有才画） */
  @Builder
  private rowHeader(title: string, desc: string, value: string) {
    Row() {
      Column({ space: 3 }) {
        Text(title)
          .fontSize(SettingsVisual.ROW_TITLE)
          .fontColor(HudColor.TEXT_PRIMARY)
          .width('100%')
        if (desc !== '') {
          Text(desc)
            .fontSize(SettingsVisual.ROW_DESC)
            .fontColor(SettingsVisual.TEXT_DIM)
            .width('100%')
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      if (value !== '') {
        Text(value)
          .fontSize(SettingsVisual.VALUE)
          .fontColor(SettingsVisual.BLUE)
          .maxLines(1)
          .margin({ left: 12, top: 1 })
      }
    }
    .width('100%')
    .alignItems(VerticalAlign.Top)
  }

  /** 选项胶囊：选中 = 亮蓝填充白字；未选中 = 深底浅灰字 + 1px 描边（全圆 / 40vp / 14fp） */
  @Builder
  private pill(label: string, active: boolean, onTap: () => void) {
    Button(label)
      .fontSize(SettingsVisual.PILL_TEXT_SIZE)
      .fontWeight(active ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(active ? HudColor.TEXT_PRIMARY : SettingsVisual.PILL_TEXT)
      .height(SettingsVisual.PILL_HEIGHT)
      .borderRadius(SettingsVisual.PILL_RADIUS)
      .backgroundColor(active ? SettingsVisual.BLUE_ACTIVE : SettingsVisual.PILL_BG)
      .border({ width: 1, color: active ? SettingsVisual.BLUE_ACTIVE : SettingsVisual.PILL_BORDER })
      .padding({ left: 12, right: 12 })
      .margin({ right: 8, bottom: 8 })
      .onClick((): void => {
        onTap();
      })
  }

  /** 单选胶囊行：等距（8vp）排布，宽度随文字、放不下自动换行 */
  @Builder
  private pillRow(labels: Array<string>, selected: number, onPick: (index: number) => void) {
    Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
      ForEach(labels, (label: string, index: number): void => {
        this.pill(label, index === selected, (): void => {
          onPick(index);
        })
      }, (label: string): string => label)
    }
    .width('100%')
    .margin({ top: 10 })
  }

  /** 整行：标题/说明/右值 + 胶囊选项（下一行） */
  @Builder
  private optionRow(title: string, desc: string, value: string, labels: Array<string>,
    selected: number, onPick: (index: number) => void) {
    Column() {
      this.rowHeader(title, desc, value)
      this.pillRow(labels, selected, onPick)
    }
    .width('100%')
    .padding({ top: 14, bottom: 6 })
  }

  /** 开关行：标题/说明 + 右侧 Toggle（disabled 时标题置灰） */
  @Builder
  private switchRow(title: string, desc: string, isOn: boolean, enabled: boolean,
    onChange: (on: boolean) => void) {
    Row({ space: 12 }) {
      Column({ space: 3 }) {
        Text(title)
          .fontSize(SettingsVisual.ROW_TITLE)
          .fontColor(enabled ? HudColor.TEXT_PRIMARY : HudColor.TEXT_TERTIARY)
          .width('100%')
        if (desc !== '') {
          Text(desc)
            .fontSize(SettingsVisual.ROW_DESC)
            .fontColor(SettingsVisual.TEXT_DIM)
            .width('100%')
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      Toggle({ type: ToggleType.Switch, isOn: isOn })
        .selectedColor(SettingsVisual.BLUE_ACTIVE)
        .switchPointColor(HudColor.TEXT_PRIMARY)
        .enabled(enabled)
        .onChange(onChange)
    }
    .width('100%')
    .padding({ top: 14, bottom: 14 })
    .alignItems(VerticalAlign.Center)
  }

  /** 纯值行（权限状态 / 版本等）：左侧白 15fp，右侧蓝 13fp */
  @Builder
  private valueRow(title: string, value: string) {
    Row() {
      Text(title)
        .fontSize(SettingsVisual.ROW_TITLE)
        .fontColor(HudColor.TEXT_PRIMARY)
        .layoutWeight(1)
      Text(value)
        .fontSize(SettingsVisual.VALUE)
        .fontColor(SettingsVisual.BLUE)
        .margin({ left: 12 })
    }
    .width('100%')
    .padding({ top: 14, bottom: 14 })
    .alignItems(VerticalAlign.Center)
  }

  /** 诊断动作钮（胶囊描边；忙态统一置灰） */
  @Builder
  private diagAction(label: string, onTap: () => void) {
    Button(label)
      .fontSize(SettingsVisual.PILL_TEXT_SIZE)
      .fontColor(SettingsVisual.PILL_TEXT)
      .height(SettingsVisual.PILL_HEIGHT)
      .borderRadius(SettingsVisual.PILL_RADIUS)
      .backgroundColor(SettingsVisual.PILL_BG)
      .border({ width: 1, color: SettingsVisual.PILL_BORDER })
      .layoutWeight(1)
      .enabled(this.diagBusy === '')
      .onClick((): void => {
        onTap();
      })
  }

  /**
   * 录制分组（画幅/帧率/码率/光弧/效果/HUD 网格）：一张卡多行，行间 1px 分隔线。
   * 每个胶囊仍直写原设置字段 + persist()（功能与持久化不动）。
   */
  @Builder
  private recordGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('录制')
      Column() {
        this.optionRow('画幅', '', this.sizeLabel(), ['1080p', '4K'],
          this.settings.sizeW >= 3840 ? 1 : 0,
          (index: number): void => {
            this.settings.sizeW = index === 1 ? 3840 : 1920;
            this.settings.sizeH = index === 1 ? 2160 : 1080;
            this.persist();
          })
        this.cardDivider()
        this.optionRow('帧率', '', `${this.settings.fps} fps`, ['24', '25', '30'],
          this.settings.fps === 24 ? 0 : (this.settings.fps === 25 ? 1 : 2),
          (index: number): void => {
            this.settings.fps = index === 0 ? 24 : (index === 1 ? 25 : 30);
            this.persist();
          })
        this.cardDivider()
        this.optionRow('码率', '', this.bitrateLabel(),
          BITRATE_OPTIONS.map((o: BitrateOption): string => o.label),
          this.bitrateIndex(),
          (index: number): void => {
            this.settings.bitrate = BITRATE_OPTIONS[index].value;
            this.persist();
          })
        this.cardDivider()
        this.optionRow('光弧（24/25fps）', '', this.arcLabel(), ['补弧', '抽帧'],
          this.settings.arcMode === 2 ? 1 : 0,
          (index: number): void => {
            this.settings.arcMode = index === 1 ? 2 : 1;
            this.persist();
          })
        this.cardDivider()
        this.optionRow('HUD 网格', '', this.settings.grid ? '开' : '关', ['开', '关'],
          this.settings.grid ? 0 : 1,
          (index: number): void => {
            this.settings.grid = index === 0;
            this.persist();
          })
        Text('画幅/帧率在录制中不可切换；监看叠加（斑马/峰值）只上屏、不进成片（2026-10-28 双 program 槽口径）')
          .fontSize(SettingsVisual.ROW_DESC)
          .fontColor(SettingsVisual.TEXT_DIM)
          .width('100%')
          .padding({ top: 12 })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /**
   * 取景辅助分组（对齐上游 0dee86d/需求 24/25：「显示水平仪与俯仰仪」「0° 时震动」）：
   * 两个开关都直写 prefs，相机页进页面时读取（进页面即生效，无需重启）。
   */
  @Builder
  private levelGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('取景辅助')
      Column() {
        this.switchRow('显示水平仪与俯仰仪', '', this.settings.levelEnabled, true,
          (on: boolean): void => {
            this.settings.levelEnabled = on;
            this.persist();
          })
        this.cardDivider()
        this.switchRow('水平仪、俯仰仪 0° 时震动', '回正瞬间轻震一次提示；关闭后两者都不震动',
          this.settings.levelBuzz, true, (on: boolean): void => {
            this.settings.levelBuzz = on;
            this.persist();
          })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /**
   * 取景参考线分组（上游 11 档位掩码，多选叠加）：每颗 chip 点一次开、再点一次关，
   * 位运算全部走 `core/RefLine` 的纯函数（不许在这里手写 `mask ^ bit`）。
   * 水平线额外受「显示水平仪与俯仰仪」总开关约束（关掉就没有 roll 读数可画）。
   */
  @Builder
  private refLineGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('取景参考线')
      Column() {
        this.rowHeader('参考线叠加',
          '可多选叠加；红线用于 WOTA 走位判定（2vp 加粗，画在其余线之上）。水平线跟随水平仪读数，关掉水平仪则不画。',
          `${this.refLineCount()} 条`)
        Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
          ForEach(REF_LINE_ENTRIES, (entry: RefLineEntry): void => {
            this.pill(entry.label, RefLine.isOn(this.settings.refLines, entry.type), (): void => {
              this.settings.refLines = RefLine.toggle(this.settings.refLines, entry.type);
              this.persist();
            })
          }, (entry: RefLineEntry): string => entry.type as string)
        }
        .width('100%')
        .margin({ top: 10 })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /**
   * 对比页节拍分组（ST 批，对齐上游 compare_tick_ms）：四档 [100,200,500,1000]，
   * 缺键 200。数值钳制走 core/UserSettings 纯函数（调用点不许手写 min/max）。
   */
  @Builder
  private compareTickGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('对比播放')
      Column() {
        this.optionRow('同步检查节拍',
          '双视频对比时两轨对齐、跨段交接与循环回绕的检查间隔；越小跟得越紧、越耗电。',
          `${this.settings.compareTickMs} ms`,
          UserSettings.COMPARE_TICK_TIERS.map((ms: number): string => `${ms}`),
          UserSettings.COMPARE_TICK_TIERS.indexOf(this.settings.compareTickMs),
          (index: number): void => {
            this.settings.compareTickMs = UserSettings.compareTickMsOf(UserSettings.COMPARE_TICK_TIERS[index]);
            this.persist();
          })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /**
   * 取景字体缩放分组（ST 批，对齐上游 text_scale_camera）：五档 [80..120]%，
   * 乘系统字体缩放后喂 hudPerRowFor 宽度账（J3a 换行档随档位自动退/进）。
   */
  @Builder
  private textScaleGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('文本高度')
      Column() {
        this.optionRow('取景字体缩放',
          '各屏独立调节，100% 为工程默认排版；影响取景页读数条文字与换行档。',
          `${this.settings.textScaleCameraPct}%`,
          UserSettings.TEXT_SCALE_PCTS.map((pct: number): string => `${pct}%`),
          UserSettings.TEXT_SCALE_PCTS.indexOf(this.settings.textScaleCameraPct),
          (index: number): void => {
            this.settings.textScaleCameraPct =
              UserSettings.textScalePctOf(UserSettings.TEXT_SCALE_PCTS[index]);
            this.persist();
          })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /**
   * 界面方向分组（UO 批，对齐上游 BlockUiOrientation / ui_orientation 键）：
   * 竖屏固定（本侧默认，T31 竖屏取证设计）与横屏两档；点选即写 prefs 并立刻
   * 经 WindowChrome 串行链落到当前窗口（窗口级 sticky，全页生效）。
   * 默认偏离登记：上游出厂 landscape，本侧 portrait（T31 竖屏取证设计）。
   */
  @Builder
  private uiOrientationGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('界面方向')
      Text('对所有页面生效；两档都会随重力在各自轴向的两个朝向间自动翻转（竖屏含倒置、横屏含反向）。')
        .fontSize(SettingsVisual.ROW_DESC)
        .fontColor(SettingsVisual.TEXT_DIM)
        .width('100%')
      Column() {
        this.optionRow('默认方向', '', this.orientationLabel(), ['横屏使用', '竖屏使用'],
          this.settings.uiOrientation === UIOrientation.LANDSCAPE ? 0 : 1,
          (index: number): void => {
            const o: UIOrientation = index === 0 ? UIOrientation.LANDSCAPE : UIOrientation.PORTRAIT;
            if (this.settings.uiOrientation === o) {
              return;
            }
            this.settings.uiOrientation = o;
            this.persist();
            const ctx: common.Context | undefined = this.hostContext();
            if (ctx !== undefined) {
              WindowChrome.lastWindow(ctx).then((win): void => {
                WindowChrome.applyOrientation(win, o);
              }).catch((_e: Object): void => {
              });
            }
          })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /**
   * 取景读数分组（J3a，对齐上游 BlockHud 的 hud_items 段）：7 档位掩码多选，
   * 相机页顶栏读数条按同一掩码渲染。位运算全部走 `core/Constants` 的 HudItems 纯函数
   * （选中态 isOn / 写入 toggle），调用点不许手写 `mask ^ bit`。
   */
  @Builder
  private hudItemsGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('取景读数')
      Column() {
        this.rowHeader('读数条目',
          '可多选叠加在取景页顶栏的常驻读数条；默认上屏 快门/帧率/码率/感光度，其余点选追加，全部取消则不显示读数条。',
          `${this.hudItemCount()} 项`)
        Row() {
          this.pill('调整顺序 →', false, (): void => {
            this.getUIContext().getRouter().pushUrl({ url: 'pages/HudEditor' });
          })
        }
        .width('100%')
        .margin({ top: 10 })
        Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
          ForEach(HudItems.ALL, (item: HudItem): void => {
            this.pill(HudItems.label(item), HudItems.isOn(this.settings.hudItems, item), (): void => {
              this.settings.hudItems = HudItems.toggle(this.settings.hudItems, item);
              this.persist();
            })
          }, (item: HudItem): string => HudItems.nameOf(item))
        }
        .width('100%')
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /**
   * 屏幕监看分组（安卓侧撤「更多」面板后，取景页的效果循环与蓝牙入口由设置页承载）：
   * 效果四档胶囊单选（下标直写 settings.effect，0..3）走原 persist()；蓝牙音箱行只做路由跳转。
   */
  @Builder
  private monitorGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('屏幕监看')
      Column() {
        this.optionRow('效果', '监看叠加只上屏、不进成片；曲线与光晕属画面内容会进成片',
          this.effectLabel(), EFFECT_LABELS, this.settings.effect,
          (index: number): void => {
            this.settings.effect = index;
            this.persist();
          })
        this.cardDivider()
        Row({ space: 12 }) {
          Text('蓝牙音箱')
            .fontSize(SettingsVisual.ROW_TITLE)
            .fontColor(HudColor.TEXT_PRIMARY)
            .layoutWeight(1)
          Text('›')
            .fontSize(SettingsVisual.ROW_TITLE)
            .fontColor(SettingsVisual.TEXT_DIM)
        }
        .width('100%')
        .padding({ top: 14, bottom: 14 })
        .alignItems(VerticalAlign.Center)
        .onClick((): void => {
          this.getUIContext().getRouter().pushUrl({ url: 'pages/BtPanel' });
        })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /** 权限状态：两行真实授权态（相机/麦克风），右侧蓝色值 */
  @Builder
  private permissionGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('权限状态')
      Column() {
        this.valueRow('相机', this.permCamera)
        this.cardDivider()
        this.valueRow('麦克风', this.permMic)
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /** 诊断日志：开关 + 两排动作钮 + 状态行（功能与落盘路径逐字不变） */
  @Builder
  private diagGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('诊断日志')
      Column() {
        Row({ space: 12 }) {
          Column({ space: 3 }) {
            Text('记录日志')
              .fontSize(SettingsVisual.ROW_TITLE)
              .fontColor(HudColor.TEXT_PRIMARY)
              .width('100%')
            Text('默认关：关闭期间不写常规日志（崩溃件不受影响）；开启后运行期立即生效')
              .fontSize(SettingsVisual.ROW_DESC)
              .fontColor(SettingsVisual.TEXT_DIM)
              .width('100%')
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          Toggle({ type: ToggleType.Switch, isOn: this.settings.diagEnabled })
            .selectedColor(SettingsVisual.BLUE_ACTIVE)
            .switchPointColor(HudColor.TEXT_PRIMARY)
            .accessibilityText('记录日志')
            .onChange((on: boolean): void => {
              this.setDiagEnabled(on);
            })
        }
        .width('100%')
        .padding({ top: 14, bottom: 14 })
        .alignItems(VerticalAlign.Center)
        this.cardDivider()
        Row({ space: 12 }) {
          Column({ space: 3 }) {
            Text('详细日志')
              .fontSize(SettingsVisual.ROW_TITLE)
              .fontColor(this.settings.diagEnabled ? HudColor.TEXT_PRIMARY : HudColor.TEXT_TERTIARY)
              .width('100%')
            Text('总开关关闭时置灰：没开着记录，记多细无从谈起（上游同层级闸）')
              .fontSize(SettingsVisual.ROW_DESC)
              .fontColor(SettingsVisual.TEXT_DIM)
              .width('100%')
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          Toggle({ type: ToggleType.Switch, isOn: this.settings.diagVerbose })
            .selectedColor(SettingsVisual.BLUE_ACTIVE)
            .switchPointColor(HudColor.TEXT_PRIMARY)
            .enabled(this.settings.diagEnabled)
            .accessibilityText('详细日志')
            .onChange((on: boolean): void => {
              this.setDiagVerbose(on);
            })
        }
        .width('100%')
        .padding({ top: 14, bottom: 14 })
        .alignItems(VerticalAlign.Center)
        this.cardDivider()
        Row({ space: 8 }) {
          this.diagAction(this.diagBusy === 'clear' ? '清除中…' : '清除日志', (): void => {
            this.clearDiag();
          })
          this.diagAction(this.diagBusy === 'export' ? '导出中…' : '导出日志', (): void => {
            this.exportDiag();
          })
        }
        .width('100%')
        .padding({ top: 14, bottom: 4 })
        Row({ space: 8 }) {
          this.diagAction(this.diagBusy === 'share' ? '分享中…' : '分享日志', (): void => {
            this.shareDiag();
          })
          this.diagAction(this.diagBusy === 'saveas' ? '保存中…' : '另存为', (): void => {
            this.saveDiagAs();
          })
        }
        .width('100%')
        .padding({ top: 4, bottom: 4 })
        if (this.diagStatus !== '') {
          Text(this.diagStatus)
            .fontSize(SettingsVisual.ROW_DESC)
            .fontColor(HudColor.TEXT_SECONDARY)
            .width('100%')
            .padding({ top: 8 })
        }
        Text('导出落应用目录；分享走系统面板，另存可自选位置（均不联网）')
          .fontSize(SettingsVisual.ROW_DESC)
          .fontColor(SettingsVisual.TEXT_DIM)
          .width('100%')
          .padding({ top: 8 })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /** 检查更新：纯手动行 + 版本行（版本 7 连点解锁开发者入口，逻辑不变） */
  @Builder
  private aboutGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('检查更新')
      Column() {
        this.updateRowCard()
        this.cardDivider()
        Row() {
          Text('版本')
            .fontSize(SettingsVisual.ROW_TITLE)
            .fontColor(HudColor.TEXT_PRIMARY)
            .layoutWeight(1)
          Text(this.versionName)
            .fontSize(SettingsVisual.VALUE)
            .fontColor(SettingsVisual.BLUE)
        }
        .width('100%')
        .padding({ top: 14, bottom: 14 })
        .alignItems(VerticalAlign.Center)
        .onClick((): void => {
          this.onVersionTap();
        })
        if (this.devUnlocked) {
          this.cardDivider()
          Row() {
            this.pill('开发者: Index', false, (): void => {
              this.getUIContext().getRouter().pushUrl({ url: 'pages/Index' });
            })
            this.pill('开发者: GL 试验', false, (): void => {
              this.getUIContext().getRouter().pushUrl({ url: 'pages/GlPreview' });
            })
          }
          .width('100%')
          .padding({ top: 12 })
        }
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  /** 横屏左列：录制 / 滑杆吸附 / 取景辅助 / 参考线 / 界面方向 */
  @Builder
  private leftColumn() {
    Column({ space: SettingsVisual.SECTION_GAP }) {
      this.recordGroup()
      this.sliderSnapGroup()
      this.levelGroup()
      this.refLineGroup()
      this.uiOrientationGroup()
    }
    .width('100%')
  }

  /** 横屏右列：取景读数 / 屏幕监看 / 对比播放 / 文本高度 / 权限状态 / 诊断日志 / 检查更新 */
  @Builder
  private rightColumn() {
    Column({ space: SettingsVisual.SECTION_GAP }) {
      this.hudItemsGroup()
      this.monitorGroup()
      this.compareTickGroup()
      this.textScaleGroup()
      this.permissionGroup()
      this.diagGroup()
      this.aboutGroup()
    }
    .width('100%')
  }

  /** 竖屏单列：顺序沿用旧页（录制 → 吸附 → 取景辅助/参考线 → 屏幕监看 → 对比/缩放 → 方向/读数 → 权限/诊断/更新） */
  @Builder
  private portraitColumn() {
    Column({ space: SettingsVisual.SECTION_GAP }) {
      this.recordGroup()
      this.sliderSnapGroup()
      this.levelGroup()
      this.refLineGroup()
      this.monitorGroup()
      this.compareTickGroup()
      this.textScaleGroup()
      this.uiOrientationGroup()
      this.hudItemsGroup()
      this.permissionGroup()
      this.diagGroup()
      this.aboutGroup()
    }
    .width('100%')
  }

  /**
   * 滑杆吸附分组（对齐上游 0dee86d SettingsBlock.SLIDER）：默认每根滑杆拖动必落刻度，
   * 这里可逐个参数改成连续值。选中 = 该参数改用连续值（不吸附）。曝光补偿本身按整数
   * 逐格取值，连续与梯等价，不列出。
   */
  @Builder
  private sliderSnapGroup() {
    Column({ space: SettingsVisual.HEAD_GAP }) {
      this.sectionTitle('滑杆吸附')
      Column() {
        this.rowHeader('连续值参数',
          '默认每根滑杆都会吸附刻度；点选参数后改为连续值，可停在任意位置（例如色温 5350K）。曝光补偿本身按整数逐格取值，不在此列。',
          this.sliderContinuousCount() === 0 ? '全部吸附' : `${this.sliderContinuousCount()} 项`)
        Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start }) {
          ForEach(SLIDER_SNAP_ENTRIES, (entry: SliderSnapEntry): void => {
            this.pill(entry.label, this.sliderSnapOn(entry.key), (): void => {
              this.toggleSliderSnap(entry.key);
            })
          }, (entry: SliderSnapEntry): string => entry.label)
        }
        .width('100%')
        .margin({ top: 10 })
      }
      .width('100%')
      .padding(SettingsVisual.CARD_PAD)
      .backgroundColor(SettingsVisual.CARD_BG)
      .borderRadius(SettingsVisual.CARD_RADIUS)
    }
    .width('100%')
  }

  @Builder
  private header() {
    Row({ space: HudSpace.M }) {
      Button('返回')
        .fontSize(SettingsVisual.PILL_TEXT_SIZE)
        .fontColor(SettingsVisual.PILL_TEXT)
        .height(SettingsVisual.PILL_HEIGHT)
        .borderRadius(SettingsVisual.PILL_RADIUS)
        .backgroundColor(SettingsVisual.PILL_BG)
        .border({ width: 1, color: SettingsVisual.PILL_BORDER })
        .padding({ left: 16, right: 16 })
        .onClick((): void => {
          this.getUIContext().getRouter().back();
        })
      Text('设置')
        .fontSize(SettingsVisual.TITLE)
        .fontWeight(FontWeight.Bold)
        .fontColor(HudColor.TEXT_PRIMARY)
        .layoutWeight(1)
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({
      left: this.landscape ? SettingsVisual.COLUMN_PAD : HudSpace.L,
      right: this.landscape ? SettingsVisual.COLUMN_PAD : HudSpace.L,
      top: HudSpace.M
    })
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
            .backgroundColor(SettingsVisual.BLUE_ACTIVE)
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
            .backgroundColor(SettingsVisual.BLUE_ACTIVE)
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
              .color(SettingsVisual.BLUE)
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
          .padding({
            left: this.landscape ? SettingsVisual.COLUMN_PAD : HudSpace.L,
            right: this.landscape ? SettingsVisual.COLUMN_PAD : HudSpace.L
          })

        if (this.loading) {
          Column() {
            LoadingProgress()
              .width(32)
              .height(32)
          }
          .layoutWeight(1)
          .width('100%')
          .justifyContent(FlexAlign.Center)
        } else if (this.landscape) {
          // 横屏双栏：两列各占一半（layoutWeight 1:1），左右 24vp 内边距、列间 32vp
          Row({ space: SettingsVisual.COLUMN_GAP }) {
            Scroll() {
              this.leftColumn()
            }
            .layoutWeight(1)
            .height('100%')
            .scrollBar(BarState.Off)
            Scroll() {
              this.rightColumn()
            }
            .layoutWeight(1)
            .height('100%')
            .scrollBar(BarState.Off)
          }
          .layoutWeight(1)
          .width('100%')
          .alignItems(VerticalAlign.Top)
          .padding({
            left: SettingsVisual.COLUMN_PAD,
            right: SettingsVisual.COLUMN_PAD,
            top: SettingsVisual.SCROLL_TOP,
            bottom: 24
          })
        } else {
          // 竖屏单列：沿用旧顺序，滚动区自然顶对齐
          Scroll() {
            this.portraitColumn()
          }
          .layoutWeight(1)
          .width('100%')
          .padding({
            left: HudSpace.L,
            right: HudSpace.L,
            top: SettingsVisual.SCROLL_TOP,
            bottom: 24
          })
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
    Row({ space: 12 }) {
      Text('检查更新')
        .fontSize(SettingsVisual.ROW_TITLE)
        .fontColor(HudColor.TEXT_PRIMARY)
        .layoutWeight(1)
      Text(this.updateStatusText())
        .fontSize(SettingsVisual.VALUE)
        .fontColor(this.updateRow === 'failed' ? HudColor.DANGER : SettingsVisual.BLUE)
        .textAlign(TextAlign.End)
        .maxLines(1)
    }
    .width('100%')
    .padding({ top: 14, bottom: 14 })
    .alignItems(VerticalAlign.Center)
    .onClick((): void => {
      this.onUpdateRowClick();
    })
  }

  private sizeLabel(): string {
    return this.settings.sizeW >= 3840 ? '4K' : '1080p';
  }

  private arcLabel(): string {
    return this.settings.arcMode === 2 ? '抽帧' : '补弧';
  }

  private effectLabel(): string {
    const i: number = this.settings.effect;
    return i >= 0 && i < EFFECT_LABELS.length ? EFFECT_LABELS[i] : EFFECT_LABELS[0];
  }

  private bitrateLabel(): string {
    return BITRATE_OPTIONS[this.bitrateIndex()].label;
  }

  private sliderContinuousCount(): number {
    return decodeContinuousSliders(this.settings.sliderContinuous).size;
  }

  private refLineCount(): number {
    return RefLine.typesOf(this.settings.refLines).length;
  }

  private hudItemCount(): number {
    return HudItems.typesOf(this.settings.hudItems).length;
  }

  private orientationLabel(): string {
    return this.settings.uiOrientation === UIOrientation.LANDSCAPE ? '横屏使用' : '竖屏使用';
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
