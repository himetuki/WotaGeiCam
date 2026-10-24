/**
 * 媒体库最小版（对应上游 media/GalleryScreen.kt 的列表/页签/动作子集，对齐上游 38d712a）：
 * - 三页签：全部 / 收藏 / 回收站（自定义标签页留待标签管理批）；
 * - 卡片：文件名、时长、大小、日期、收藏星标、动作按钮；占位块不做缩略图；
 * - 点击卡片进入播放页；「信息」按钮显示元信息面板；
 * - 回收站语义：移入只写本地记录、文件原地保留；彻底删除二次确认后才 unlink 文件与 .drops.json。
 */
import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { MediaRepo, VideoClip } from '../media/MediaRepo';
import { ClipFilter, GalleryTab, GalleryTabs } from '../media/MediaTag';
import { GalleryEntry, expandSeriesTargets, galleryEntries } from '../media/SeriesGroup';
import { TagSpace } from '../media/MediaTag';
import { ThumbLoader } from '../media/ThumbLoader';
import { WotaShare } from '../media/WotaShare';
import { AlbumSaver } from '../media/AlbumSaver';
import { formatDuration, formatSize } from '../media/MediaFormat';
import {
  DEFAULT_LIST_MODE, GALLERY_COLUMNS_KEY, LIST_GRID, LIST_TIMELINE,
  TimelineGroup, galleryColumnsOf, groupTimeline
} from '../media/GalleryForms';
import { ParamsStore } from '../ui/ParamsStore';
import { WindowChrome } from '../ui/WindowChrome';

function pad2(v: number): string {
  return v < 10 ? `0${v}` : `${v}`;
}

function formatDate(sec: number): string {
  const d: Date = new Date(sec * 1000);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

@Entry
@Component
struct Gallery {
  @State clips: Array<VideoClip> = [];
  @State tabs: Array<GalleryTab> = GalleryTabs.build([]);
  /** HM7-1：半模态面板种类（'none' | 'series' | 'tags'） */
  @State sheetKind: string = 'none';
  @State selectedTab: number = 0;
  @State counts: Array<number> = [0, 0, 0];
  @State loading: boolean = true;
  @State actionBusy: boolean = false;
  @State message: string = '';
  @State dbOk: boolean = true;
  @State showDetail: boolean = false;
  @State detailName: string = '';
  @State detailMeta: string = '';
  /** 分段折叠开关（会话态，默认开=组卡；关=与上游 collapse=false 同义的扁平基线） */
  @State seriesCollapsed: boolean = true;
  // G3a：列表形态（上游 ListMode，默认网格逐字；全屏滑动形态 G3b）与网格列数（prefs 持久化）
  @State listMode: string = DEFAULT_LIST_MODE;
  @State gridColumns: number = 2;
  @State expandedSeriesIds: Set<string> = new Set<string>();
  @State menuClip: VideoClip | undefined = undefined;
  /** CT 批：自定义标签（media_tag 行派生，页签行追加自定义页签） */
  @State customTags: Array<string> = [];
  /** 加标签弹层（对应上游 MediaTagDialog）：对指定片加/移除自定义标签 */
  @State tagDialogClip: VideoClip | undefined = undefined;
  @State tagInput: string = '';
  /** 弹层内该片的自定义标签清单（来自该片 tags 的 isCustom 子集） */
  @State tagDialogTags: Array<string> = [];
  /** 分段展开面板：非 null = 显示该组的成员清单（只钉 seriesId，按当前 clips 重解析） */
  @State seriesSheetId: number | undefined = undefined;

  private repo: MediaRepo | undefined = undefined;
  /** 页面宿主上下文（分享面板要 UIAbilityContext；本页宿主就是它） */
  private ctx: common.Context | undefined = undefined;
  private allClips: Array<VideoClip> = [];
  private likeClips: Array<VideoClip> = [];
  private trashClips: Array<VideoClip> = [];
  /** 异步竞态：只接受最新一次加载；页面销毁后丢弃所有回包 */
  private requestSeq: number = 0;
  private disposed: boolean = false;
  /** 从播放页返回（onPageShow）时刷新；首次进入不重复（aboutToAppear 已加载） */
  private pageShownOnce: boolean = false;

  aboutToAppear(): void {
    this.disposed = false;
    const ctx: common.Context | undefined =
      this.getUIContext().getHostContext() as common.Context | undefined;
    this.ctx = ctx;
    if (ctx === undefined) {
      this.loading = false;
      this.message = 'no host context';
      return;
    }
    // T31：媒体库恢复黑底系统栏（相机页是全屏透明栏，离开相机页必须复位）
    WindowChrome.lastWindow(ctx).then((win): Promise<void> =>
      WindowChrome.applyStandardDark(win)).catch((e: Object): void => {
    });
    // G3a：网格列数（上游 WotaSettings.galleryColumns，同 store 单文件口径）
    const prefs = ParamsStore.open(ctx);
    if (prefs !== undefined) {
      this.gridColumns = galleryColumnsOf(prefs.getInt(GALLERY_COLUMNS_KEY, 2));
    }
    this.repo = new MediaRepo(ctx);
    this.refreshAll();
  }

  onPageShow(): void {
    if (!this.pageShownOnce) {
      this.pageShownOnce = true;
      return;
    }
    if (!this.disposed) {
      this.refreshAll();
    }
  }

  aboutToDisappear(): void {
    this.disposed = true;
    this.requestSeq++;
  }

  private applyDbAvailability(repo: MediaRepo): void {
    this.dbOk = repo.isDbAvailable();
  }

  private currentTab(): GalleryTab {
    return this.tabs[this.selectedTab];
  }

  private applyCached(): void {
    if (this.selectedTab === 0) {
      this.clips = this.allClips;
    } else if (this.selectedTab === 1) {
      this.clips = this.likeClips;
    } else if (this.selectedTab === 2) {
      this.clips = this.trashClips;
    } else {
      this.clips = this.allClips;
    }
  }

  /** 三页签全量刷新：列表 + 数量 + DB 可用性；notice 保留动作反馈 */
  private async refreshAll(notice: string = ''): Promise<void> {
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined) {
      return;
    }
    const seq: number = ++this.requestSeq;
    this.loading = true;
    MediaRepo.resetProbeCapHit();
    try {
      const all: Array<VideoClip> = await repo.load(ClipFilter.all());
      if (this.disposed || seq !== this.requestSeq) {
        return;
      }
      const liked: Array<VideoClip> = await repo.load(ClipFilter.like());
      if (this.disposed || seq !== this.requestSeq) {
        return;
      }
      const trashed: Array<VideoClip> = await repo.load(ClipFilter.trash());
      if (this.disposed || seq !== this.requestSeq) {
        return;
      }
      this.customTags = await repo.customTags();
      this.refreshTabs();
      // 自定义页签缩可能使选中越界/失配：统一矫正（上游 clampedSelection 调用点同款）
      const sel = GalleryTabs.clampedSelection(this.tabs, this.selectedTab, this.currentTab().filter);
      this.selectedTab = sel.index;
      this.allClips = all;
      this.likeClips = liked;
      this.trashClips = trashed;
      this.counts = [all.length, liked.length, trashed.length];
      this.applyDbAvailability(repo);
      this.applyCached();
      if (!this.dbOk) {
        this.message = '数据库不可用：仅显示文件列表，收藏/回收站暂不可用';
      } else {
        this.message = notice;
      }
      this.message = this.withProbeCapNotice(this.message);
    } catch (e) {
      if (!this.disposed && seq === this.requestSeq) {
        this.message = `加载失败: ${e}`;
        this.clips = [];
        this.allClips = [];
        this.likeClips = [];
        this.trashClips = [];
        this.counts = [0, 0, 0];
      }
    } finally {
      if (!this.disposed && seq === this.requestSeq) {
        this.loading = false;
      }
    }
  }

  /** 页签切换：按该页签 filter 重新加载（数量保持全量刷新结果） */
  private async switchTab(index: number): Promise<void> {
    if (this.tabs.length === 0) {
      return;
    }
    const prevIndex: number = this.selectedTab;
    const prevClips: Array<VideoClip> = this.clips;
    const sel = GalleryTabs.clampedSelection(this.tabs, index, this.currentTab().filter);
    this.selectedTab = sel.index;
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined) {
      return;
    }
    const seq: number = ++this.requestSeq;
    this.loading = true;
    MediaRepo.resetProbeCapHit();
    try {
      const list: Array<VideoClip> = await repo.load(this.currentTab().filter);
      if (this.disposed || seq !== this.requestSeq) {
        return;
      }
      this.applyDbAvailability(repo);
      if (this.selectedTab === 0) {
        this.allClips = list;
        this.counts[0] = list.length;
      } else if (this.selectedTab === 1) {
        this.likeClips = list;
        this.counts[1] = list.length;
      } else if (this.selectedTab === 2) {
        this.trashClips = list;
        this.counts[2] = list.length;
      }
      this.clips = list;
      this.message = this.withProbeCapNotice(this.message);
    } catch (e) {
      if (!this.disposed && seq === this.requestSeq) {
        // 失败回退到原页签与原列表，避免空列表/错位
        this.selectedTab = prevIndex;
        this.clips = prevClips;
        this.applyDbAvailability(repo);
        this.message = `加载失败（已回退）: ${e}`;
      }
    } finally {
      // 页签切换也收尾 loading（刷新在途被新请求顶掉时，旧请求不会清）
      if (!this.disposed && seq === this.requestSeq) {
        this.loading = false;
      }
    }
  }

  private async retryDb(): Promise<void> {
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.loading) {
      return;
    }
    const seq: number = ++this.requestSeq;
    this.loading = true;
    try {
      const ok: boolean = await repo.retryDb();
      if (this.disposed || seq !== this.requestSeq) {
        return;
      }
      this.applyDbAvailability(repo);
      if (ok) {
        await this.refreshAll('数据库已恢复');
      } else {
        this.message = '数据库仍不可用：保持仅文件列表';
      }
    } catch (e) {
      if (!this.disposed && seq === this.requestSeq) {
        this.message = `数据库重试失败: ${e}`;
      }
    } finally {
      if (!this.disposed && seq === this.requestSeq) {
        this.loading = false;
      }
    }
  }

  private withProbeCapNotice(base: string): string {
    if (!MediaRepo.probeCapHit()) {
      return base;
    }
    const capMsg: string = '部分视频元数据暂不可用（探测繁忙），可稍后刷新';
    return base === '' ? capMsg : `${base}；${capMsg}`;
  }

  private tabLabel(tab: GalleryTab, index: number): string {
    if (index < 3) {
      return `${tab.label} ${this.counts[index]}`;
    }
    return tab.label;
  }

  private emptyTitle(): string {
    if (this.selectedTab === 1) {
      return '还没有收藏';
    }
    if (this.selectedTab === 2) {
      return '回收站为空';
    }
    return '这里还没有视频';
  }

  private emptyHint(): string {
    if (this.selectedTab === 1) {
      return '在全部页签点星标即可收藏';
    }
    if (this.selectedTab === 2) {
      return '移入回收站的文件仍保留在私有目录，彻底删除才会移除';
    }
    return '先在 GL 试验页录制一段 GPU 视频';
  }

  // ------------------------------------------------------------------ 分段归组（上游 SeriesGroup）

  /** 当前页签的展示单元（分段合并成一条；单片与今天逐字等价） */
  /** CT 批：页签表 = 系统 3 页签 + 自定义页签（media_tag 派生，按首次使用时间） */
  private refreshTabs(): void {
    this.tabs = GalleryTabs.build(this.customTags);
  }

  private entryList(): Array<GalleryEntry> {
    return galleryEntries(this.clips, this.seriesCollapsed);
  }

  /** 组级动作扇出：点在代表段上，真正动到整组所有可见成员 */
  private seriesTargets(clip: VideoClip): Array<VideoClip> {
    return expandSeriesTargets([clip], this.clips);
  }

  /** 动作结果提示：组级动作补一句段数，避免用户以为只动了一段 */
  private actionNotice(base: string, moved: number): string {
    return moved > 1 ? `${base}（含 ${moved} 段）` : base;
  }

  /** CT 批：打开标签弹层（回填该片现有自定义标签） */
  private openTagDialog(clip: VideoClip): void {
    this.tagDialogClip = clip;
    this.reloadTagDialogTags(clip);
    this.sheetKind = 'tags';
  }

  private reloadTagDialogTags(clip: VideoClip): void {
    this.tagDialogTags = Array.from(clip.tags).filter(
      (t: string): boolean => TagSpace.isCustom(t));
  }

  /** CT 批：给片加自定义标签（normalize 守卫；重复加同 tag 由 DB UNIQUE 幂等） */
  private async addTagToClip(clip: VideoClip, rawTag: string): Promise<void> {
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      const result: string = await repo.addTag(clip, rawTag);
      this.message = result === '' ? `已加标签「${rawTag.trim()}」` : result;
      await this.refreshAll(this.message);
      // 刷新后 clip 对象换新：按路径找回，弹层清单随之更新
      const again: VideoClip | undefined = this.clips.find(
        (c: VideoClip): boolean => c.path === clip.path);
      if (again !== undefined) {
        this.tagDialogClip = again;
        this.reloadTagDialogTags(again);
      }
    } finally {
      this.actionBusy = false;
    }
  }

  /** CT 批：从片移除自定义标签 */
  private async removeTagFromClip(tag: string): Promise<void> {
    const repo: MediaRepo | undefined = this.repo;
    const clip: VideoClip | undefined = this.tagDialogClip;
    if (repo === undefined || clip === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      const ok: boolean = await repo.removeTag(clip, tag);
      if (ok) {
        this.message = `已移除标签「${tag}」`;
      }
      await this.refreshAll(this.message);
      const again: VideoClip | undefined = this.clips.find(
        (c: VideoClip): boolean => c.path === clip.path);
      if (again !== undefined) {
        this.tagDialogClip = again;
        this.reloadTagDialogTags(again);
      }
    } finally {
      this.actionBusy = false;
    }
  }

  private openPlayer(clip: VideoClip): void {
    try {
      this.getUIContext().getRouter().pushUrl({ url: 'pages/Player', params: { id: clip.id, path: clip.path } });
    } catch (e) {
      this.message = `打开播放页失败: ${e}`;
    }
  }

  /** 分段展开面板的成员（按当前 clips 重解析：面板打开期间刷新也拿到最新成员表） */
  private seriesSheetParts(): Array<VideoClip> {
    const sid: number | undefined = this.seriesSheetId;
    if (sid === undefined) {
      return [];
    }
    const entry: GalleryEntry | undefined = this.entryList().find(
      (e: GalleryEntry): boolean => e.seriesId === sid);
    return entry === undefined ? [] : entry.parts;
  }

  /** G3a：网格卡 [⋯]/长按开动作菜单（bindSheet menu 分支渲染 actionRow） */
  private openClipMenu(clip: VideoClip): void {
    this.menuClip = clip;
    this.sheetKind = 'menu';
  }

  /** G3a：网格列数档位循环（2→3→4→5→2，上游顶栏 onColumns 同一持久化键） */
  private cycleColumns(): void {
    const tiers: Array<number> = [2, 3, 4, 5];
    const next: number = tiers[(tiers.indexOf(this.gridColumns) + 1) % tiers.length];
    this.gridColumns = next;
    const ctx: common.Context | undefined = this.ctx;
    const prefs = ctx === undefined ? undefined : ParamsStore.open(ctx);
    if (prefs !== undefined) {
      prefs.putInt(GALLERY_COLUMNS_KEY, next);
      prefs.commit();
    }
  }

  /** G3a：时间线按日分组（吃 entryList 的代表段序，输入序即组内序；携带回查好的 entry） */
  private timelineDays(): Array<TimelineDay> {
    const entries: Array<GalleryEntry> = this.entryList();
    const byPath: Map<string, GalleryEntry> = new Map<string, GalleryEntry>();
    for (const e of entries) {
      byPath.set(e.clip.path, e);
    }
    const reps: Array<VideoClip> = entries.map((e: GalleryEntry): VideoClip => e.clip);
    return groupTimeline(reps, Date.now()).map((g: TimelineGroup): TimelineDay => {
      const dayEntries: Array<GalleryEntry> = [];
      for (const c of g.clips) {
        const e: GalleryEntry | undefined = byPath.get(c.path);
        if (e !== undefined) {
          dayEntries.push(e);
        }
      }
      return new TimelineDay(g.label, g.dayKey, dayEntries);
    });
  }

  private gridColumnsTemplate(): string {
    let t: string = '';
    for (let i = 0; i < this.gridColumns; i++) {
      t += '1fr ';
    }
    return t.trim();
  }

  private entryByPath(): Map<string, GalleryEntry> {
    const m: Map<string, GalleryEntry> = new Map<string, GalleryEntry>();
    for (const e of this.entryList()) {
      m.set(e.clip.path, e);
    }
    return m;
  }

  /** G3a：时间线系列行点按 = 内联展开/收起（纯视觉，上游 expandedSeries 同款会话态） */
  private toggleSeriesExpand(seriesId: number): void {
    const key: string = String(seriesId);
    const next: Set<string> = new Set<string>(this.expandedSeriesIds);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    this.expandedSeriesIds = next;
  }

  private openSeriesSheet(entry: GalleryEntry): void {
    if (entry.seriesId !== undefined) {
      this.seriesSheetId = entry.seriesId;
      this.sheetKind = 'series';
    }
  }

  private showDetailOf(clip: VideoClip): void {
    if (this.sheetKind === 'menu') {
      this.closeSheet();
    }
    const tags: string = clip.tags.size === 0 ? '无' : Array.from(clip.tags).join(', ');
    this.detailName = clip.name;
    this.detailMeta = `${formatDuration(clip.durationMs)} · ${formatSize(clip.sizeBytes)} · ` +
      `${formatDate(clip.dateAddedSec)}\n${clip.width}x${clip.height} · ${clip.mimeType}\n` +
      `标签: ${tags}\n路径: ${clip.relativePath}\n`;
    this.showDetail = true;
  }

  private async toggleLike(clip: VideoClip): Promise<void> {
    if (this.sheetKind === 'menu') {
      this.closeSheet();
    }
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      // 组级扇出：代表段上的收藏状态对整组生效（上游 expandSeriesTargets 语义）
      const targets: Array<VideoClip> = this.seriesTargets(clip);
      let ok: boolean = true;
      for (const t of targets) {
        ok = (await repo.setLike(t, !clip.liked)) && ok;
      }
      const base: string = ok ? (clip.liked ? '已取消收藏' : '已收藏') : '收藏操作失败（数据库不可用）';
      await this.refreshAll(this.actionNotice(base, targets.length));
    } finally {
      this.actionBusy = false;
    }
  }

  private async moveToTrash(clip: VideoClip): Promise<void> {
    if (this.sheetKind === 'menu') {
      this.closeSheet();
    }
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      const targets: Array<VideoClip> = this.seriesTargets(clip);
      let ok: boolean = true;
      for (const t of targets) {
        ok = (await repo.moveToTrash(t)) && ok;
      }
      const base: string = ok ? '已移入回收站（文件仍保留）' : '移入回收站失败';
      await this.refreshAll(this.actionNotice(base, targets.length));
    } finally {
      this.actionBusy = false;
    }
  }

  private async restore(clip: VideoClip): Promise<void> {
    if (this.sheetKind === 'menu') {
      this.closeSheet();
    }
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      const targets: Array<VideoClip> = this.seriesTargets(clip);
      let ok: boolean = true;
      for (const t of targets) {
        ok = (await repo.restore(t.path)) && ok;
      }
      const base: string = ok ? '已还原' : '还原失败';
      await this.refreshAll(this.actionNotice(base, targets.length));
    } finally {
      this.actionBusy = false;
    }
  }

  private confirmDelete(clip: VideoClip): void {
    if (this.sheetKind === 'menu') {
      this.closeSheet();
    }
    const targets: Array<VideoClip> = this.seriesTargets(clip);
    const scope: string = targets.length > 1
      ? `本组 ${targets.length} 段的文件与同名 .drops.json 将被永久删除：\n${clip.name}` :
      `文件与同名 .drops.json 将被永久删除：\n${clip.name}`;
    this.getUIContext().showAlertDialog({
      title: '彻底删除',
      message: scope,
      primaryButton: {
        value: '取消',
        action: (): void => {
        }
      },
      secondaryButton: {
        value: '彻底删除',
        fontColor: Color.Red,
        action: (): void => {
          this.deleteForever(clip);
        }
      }
    });
  }

  private async deleteForever(clip: VideoClip): Promise<void> {
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      const targets: Array<VideoClip> = this.seriesTargets(clip);
      let ok: boolean = true;
      for (const t of targets) {
        ok = (await repo.deleteForever(t)) && ok;
      }
      const base: string = ok ? '已彻底删除' : '删除未完全成功（部分文件/记录失败，详见日志）';
      await this.refreshAll(this.actionNotice(base, targets.length));
    } finally {
      this.actionBusy = false;
    }
  }

  /** G3a：时间线行（上游 TimelineList：日头 + 78x58 缩略行 + 系列内联展开；动作沿用 actionRow） */
  @Builder
  timelineCard(entry: GalleryEntry) {
    Column({ space: 6 }) {
      Row({ space: 10 }) {
        Stack({ alignContent: Alignment.BottomStart }) {
          GalleryThumb({
            path: entry.clip.path,
            durationMs: entry.clip.durationMs
          })
          if (entry.seriesCount() !== undefined) {
            Text(`${entry.seriesCount()} 段`)
              .fontSize(9)
              .fontColor('#FFCC80')
              .backgroundColor('#99000000')
              .borderRadius(4)
              .padding({ left: 3, right: 3 })
              .margin(2)
          }
        }
        .width(78)
        .height(58)
        .borderRadius(6)
        .clip(true)
        .onClick((): void => {
          this.openPlayer(entry.clip);
        })

        Column({ space: 2 }) {
          Text(entry.clip.liked ? `★ ${entry.title()}` : entry.title())
            .fontSize(13)
            .fontColor(Color.White)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(`${entry.seriesCount() !== undefined
            ? `${entry.seriesCount()} 段 · ` : ''}${formatDuration(entry.totalDurationMs())} · ` +
            `${formatSize(entry.clip.sizeBytes)} · ${formatDate(entry.clip.dateAddedSec)}`)
            .fontSize(10)
            .fontColor('#B0BEC5')
          if (entry.clip.width > 0 && entry.clip.height > 0) {
            Text(`${entry.clip.width}x${entry.clip.height}`)
              .fontSize(10)
              .fontColor('#78909C')
          }
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        .onClick((): void => {
          if (entry.seriesId !== undefined) {
            this.toggleSeriesExpand(entry.seriesId);
          } else {
            this.openPlayer(entry.clip);
          }
        })

        if (entry.seriesId !== undefined) {
          Text(this.expandedSeriesIds.has(String(entry.seriesId)) ? '－' : '＋')
            .fontSize(16)
            .fontColor('#78909C')
            .width(20)
            .textAlign(TextAlign.Center)
            .accessibilityText(this.expandedSeriesIds.has(String(entry.seriesId))
              ? '收起分段' : '展开分段')
            .onClick((): void => {
              if (entry.seriesId !== undefined) {
                this.toggleSeriesExpand(entry.seriesId);
              }
            })
        }
      }
      .width('100%')
      this.actionRow(entry.clip)
      if (entry.seriesId !== undefined && this.expandedSeriesIds.has(String(entry.seriesId))) {
        ForEach(entry.parts.slice(1), (part: VideoClip, idx: number): void => {
          Row({ space: 8 }) {
            Text(`第 ${idx + 2} 段`)
              .fontSize(10)
              .fontColor('#FFCC80')
              .width(40)
            Column() {
              GalleryThumb({ path: part.path, durationMs: part.durationMs })
            }
            .width(52)
            .height(38)
            .borderRadius(4)
            .clip(true)
            Column({ space: 2 }) {
              Text(part.name)
                .fontSize(11)
                .fontColor(Color.White)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(`${formatDuration(part.durationMs)} · ${formatSize(part.sizeBytes)}`)
                .fontSize(10)
                .fontColor('#B0BEC5')
            }
            .layoutWeight(1)
            .alignItems(HorizontalAlign.Start)
          }
          .width('100%')
          .padding({ left: 34, top: 4, bottom: 4 })
          .accessibilityText(`第 ${idx + 2} 段，${part.name}，点按打开播放页`)
          .onClick((): void => {
            this.openPlayer(part);
          })
        }, (part: VideoClip): string => part.path)
      }
    }
    .width('100%')
    .padding(10)
    .backgroundColor('#1F1F1F')
    .borderRadius(8)
    .gesture(
      LongPressGesture({ repeat: false, duration: 500 })
        .onAction((e: GestureEvent): void => {
          if (entry.seriesCount() !== undefined) {
            this.openSeriesSheet(entry);
          }
        })
    )
  }

  /** G3a：网格卡（上游 ClipGrid/ClipCard：16:9 缩略 + 名字/角标 + [⋯] 菜单；动作进 bindSheet） */
  @Builder
  gridCard(entry: GalleryEntry) {
    Column({ space: 4 }) {
      Stack({ alignContent: Alignment.TopStart }) {
        Column() {
          GalleryThumb({ path: entry.clip.path, durationMs: entry.clip.durationMs })
        }
        .width('100%')
        .aspectRatio(16 / 9)
        .borderRadius(6)
        .clip(true)
        Row({ space: 4 }) {
          if (entry.seriesCount() !== undefined) {
            Text(`${entry.seriesCount()} 段`)
              .fontSize(9)
              .fontColor('#FFCC80')
              .backgroundColor('#99000000')
              .borderRadius(4)
              .padding({ left: 3, right: 3 })
          }
          if (entry.clip.liked) {
            Text('★')
              .fontSize(10)
              .fontColor('#FF4D6A')
              .backgroundColor('#99000000')
              .borderRadius(4)
              .padding({ left: 3, right: 3 })
          }
        }
        Row() {
          Text('⋯')
            .fontSize(14)
            .fontColor(Color.White)
            .backgroundColor('#99000000')
            .borderRadius(11)
            .width(22)
            .height(22)
            .textAlign(TextAlign.Center)
            .accessibilityText('操作菜单')
            .onClick((): void => {
              this.openClipMenu(entry.clip);
            })
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
      }
      Text(entry.clip.liked ? `★ ${entry.title()}` : entry.title())
        .fontSize(12)
        .fontColor(Color.White)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
      Text(`${entry.seriesCount() !== undefined
        ? `${entry.seriesCount()} 段 · ${formatDuration(entry.totalDurationMs())}` : formatDuration(entry.clip.durationMs)} · ${formatSize(entry.clip.sizeBytes)}`)
        .fontSize(10)
        .fontColor('#B0BEC5')
        .maxLines(1)
        .width('100%')
    }
    .width('100%')
    .padding(6)
    .backgroundColor('#1F1F1F')
    .borderRadius(8)
    .onClick((): void => {
      if (entry.seriesId !== undefined) {
        this.openSeriesSheet(entry);
      } else {
        this.openPlayer(entry.clip);
      }
    })
    .gesture(
      LongPressGesture({ repeat: false, duration: 500 })
        .onAction((e: GestureEvent): void => {
          if (entry.seriesCount() !== undefined) {
            this.openSeriesSheet(entry);
          } else {
            this.openClipMenu(entry.clip);
          }
        })
    )
  }

  @Builder
  private actionRow(clip: VideoClip) {
    Row({ space: 6 }) {
      Button('信息')
        .fontSize(11)
        .height(26)
        .onClick((): void => {
          this.showDetailOf(clip);
        })
      if (this.selectedTab === 2) {
        Button('还原')
          .fontSize(11)
          .height(26)
          .enabled(!this.actionBusy && this.dbOk)
          .onClick((): void => {
            this.restore(clip);
          })
        Button('彻底删除')
          .fontSize(11)
          .height(26)
          .backgroundColor(Color.Red)
          .enabled(!this.actionBusy && this.dbOk)
          .onClick((): void => {
            this.confirmDelete(clip);
          })
      } else {
        Button(clip.liked ? '取消收藏' : '收藏')
          .fontSize(11)
          .height(26)
          .enabled(!this.actionBusy && this.dbOk)
          .onClick((): void => {
            this.toggleLike(clip);
          })
        Button('移入回收站')
          .fontSize(11)
          .height(26)
          .enabled(!this.actionBusy && this.dbOk)
          .onClick((): void => {
            this.moveToTrash(clip);
          })
        Button('标签')
          .fontSize(11)
          .height(26)
          .enabled(!this.actionBusy && this.dbOk)
          .onClick((): void => {
            this.openTagDialog(clip);
          })
        Button('分享')
          .fontSize(11)
          .height(26)
          .enabled(!this.actionBusy)
          .onClick((): void => {
            this.shareClip(clip);
          })
        Button('存入相册')
          .fontSize(11)
          .height(26)
          .enabled(!this.actionBusy)
          .onClick((): void => {
            this.saveClip(clip);
          })
      }
    }
  }

  /** 系统分享面板（组级动作扇出到整组成员；批量走 BATCH 模式） */
  private async shareClip(clip: VideoClip): Promise<void> {
    if (this.sheetKind === 'menu') {
      this.closeSheet();
    }
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      this.message = '无上下文，无法分享';
      return;
    }
    const targets: Array<VideoClip> = this.seriesTargets(clip);
    this.message = targets.length > 1 ? `正在拉起分享（${targets.length} 段）…` : '正在拉起分享…';
    const err: string = await WotaShare.share(ctx as common.UIAbilityContext, targets);
    this.message = err === '' ? '' : err;
  }

  /** 存入系统相册（showAssetsCreationDialog 系统确认框；组级扇出到整组） */
  private async saveClip(clip: VideoClip): Promise<void> {
    if (this.sheetKind === 'menu') {
      this.closeSheet();
    }
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      this.message = '无上下文，无法保存';
      return;
    }
    const targets: Array<VideoClip> = this.seriesTargets(clip);
    this.message = '等待系统确认…';
    const result: string = await AlbumSaver.saveToGallery(ctx, targets);
    this.message = result === '' ? this.actionNotice('已存入相册', targets.length) : result;
  }

  build() {
    Column({ space: 8 }) {
      Row({ space: 10 }) {
        Button('返回')
          .fontSize(12)
          .onClick((): void => {
            this.getUIContext().getRouter().back();
          })
        Text('媒体库')
          .fontSize(18)
          .fontColor(Color.White)
          .layoutWeight(1)
        Button(this.listMode === LIST_TIMELINE ? '网格' : '时间线')
          .fontSize(11)
          .height(26)
          .onClick((): void => {
            this.listMode = this.listMode === LIST_TIMELINE ? LIST_GRID : LIST_TIMELINE;
          })
        if (this.listMode === LIST_GRID) {
          Button(`${this.gridColumns}列`)
            .fontSize(11)
            .height(26)
            .onClick((): void => {
              this.cycleColumns();
            })
        }
        Button('刷新')
          .fontSize(12)
          .enabled(!this.loading)
          .onClick((): void => {
            this.refreshAll();
          })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8 })

      // 分段折叠开关（会话态）：关 = 上游 collapse=false 的扁平基线（逐字等价、回退开关）
      Row({ space: 6 }) {
        Text('分段合并')
          .fontSize(11)
          .fontColor('#B0BEC5')
        Toggle({ type: ToggleType.Switch, isOn: this.seriesCollapsed })
          .selectedColor('#FF4D6A')
          .onChange((on: boolean): void => {
            this.seriesCollapsed = on;
          })
        Blank()
        if (this.selectedTab !== 2) {
          Text('长按组卡可展开分段清单')
            .fontSize(10)
            .fontColor('#78909C')
        }
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 4 })

      Row({ space: 6 }) {
        ForEach(this.tabs, (tab: GalleryTab, index: number): void => {
          Button(this.tabLabel(tab, index))
            .fontSize(12)
            .backgroundColor(this.selectedTab === index ? '#2E7D32' : '#333333')
            .onClick((): void => {
              this.switchTab(index);
            })
        }, (tab: GalleryTab): string => tab.label)
      }
      .width('100%')
      .padding({ left: 12, right: 12 })

      if (this.message !== '') {
        Row({ space: 8 }) {
          Text(this.message)
            .fontSize(11)
            .fontColor('#FFCC80')
            .maxLines(2)
            .layoutWeight(1)
          if (!this.dbOk) {
            Button('重试数据库')
              .fontSize(11)
              .height(26)
              .enabled(!this.loading)
              .onClick((): void => {
                this.retryDb();
              })
          }
        }
        .width('100%')
        .padding({ left: 12, right: 12 })
      }

      if (this.loading) {
        Column({ space: 8 }) {
          LoadingProgress()
            .width(36)
            .height(36)
          Text('加载中…')
            .fontSize(12)
            .fontColor('#AAAAAA')
        }
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.clips.length === 0) {
        Column({ space: 6 }) {
          Text(this.emptyTitle())
            .fontSize(15)
            .fontColor(Color.White)
          Text(this.emptyHint())
            .fontSize(11)
            .fontColor('#AAAAAA')
            .textAlign(TextAlign.Center)
            .maxLines(3)
        }
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
        .padding(24)
      } else {
        if (this.listMode === LIST_TIMELINE) {
          List({ space: 6 }) {
            ForEach(this.timelineDays(), (day: TimelineDay): void => {
              ListItem() {
                Text(day.label)
                  .fontSize(13)
                  .fontColor('#78909C')
                  .width('100%')
                  .padding({ top: 8, bottom: 0, left: 2 })
              }
              ForEach(day.entries, (entry: GalleryEntry): void => {
                ListItem() {
                  this.timelineCard(entry)
                }
              }, (entry: GalleryEntry): string =>
                entry.seriesId === undefined ? entry.clip.path : `series_${entry.seriesId}`)
            }, (day: TimelineDay): string => `d_${day.dayKey}`)
          }
          .layoutWeight(1)
          .width('100%')
          .padding({ left: 12, right: 12 })
        } else {
          Grid() {
            ForEach(this.entryList(), (entry: GalleryEntry): void => {
              GridItem() {
                this.gridCard(entry)
              }
            }, (entry: GalleryEntry): string =>
              entry.seriesId === undefined ? entry.clip.path : `series_${entry.seriesId}`)
          }
          .columnsTemplate(this.gridColumnsTemplate())
          .columnsGap(6)
          .rowsGap(8)
          .layoutWeight(1)
          .width('100%')
          .padding({ left: 12, right: 12 })
        }
      }

      if (this.showDetail) {
        Column({ space: 4 }) {
          Row({ space: 8 }) {
            Text(this.detailName)
              .fontSize(13)
              .fontColor(Color.White)
              .layoutWeight(1)
              .maxLines(1)
            Button('关闭')
              .fontSize(11)
              .height(24)
              .onClick((): void => {
                this.showDetail = false;
              })
          }
          Text(this.detailMeta)
            .fontSize(11)
            .fontColor('#B0BEC5')
            .maxLines(6)
        }
        .width('100%')
        .padding(12)
        .backgroundColor('#263238')
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
    // HM7-1：半模态面板（SheetSpec 消费面，走鸿蒙原生 bindSheet）——
    // 分段清单与标签弹层共用；高度 SheetSize.MEDIUM（屏半高）、dragBar、深色底。
    // 宽 480vp 上限由系统面板形态承担（手机 < 480 自然合规，SheetSpec.clampSheetWidth 同义，登记）。
    .bindSheet(this.sheetKind !== 'none', (): void => {
      this.gallerySheetBuilder()
    }, {
      height: SheetSize.MEDIUM,
      dragBar: true,
      showClose: true,
      title: { title: this.sheetTitle() },
      maskColor: '#33000000',
      backgroundColor: '#263238'
    } as SheetOptions)
  }

  /** HM7-1：半模态面板内容（CustomBuilder 无参，内容读组件状态） */
  @Builder
  gallerySheetBuilder() {
    if (this.sheetKind === 'series') {
      Column({ space: 6 }) {
        Text('点按任一段直接打开播放页；组级动作（收藏/回收站/分享）作用于全部段。')
          .fontSize(11)
          .fontColor('#78909C')
          .width('100%')
        Scroll() {
          Column({ space: 4 }) {
            ForEach(this.seriesSheetParts(), (part: VideoClip, idx: number): void => {
              Row({ space: 8 }) {
                Text(`第 ${idx + 1} 段`)
                  .fontSize(11)
                  .fontColor('#FFCC80')
                  .width(52)
                Text(part.name)
                  .fontSize(12)
                  .fontColor(Color.White)
                  .layoutWeight(1)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(`${formatDuration(part.durationMs)} · ${formatSize(part.sizeBytes)}`)
                  .fontSize(10)
                  .fontColor('#B0BEC5')
              }
              .width('100%')
              .padding(6)
              .backgroundColor('#1F1F1F')
              .borderRadius(6)
              .accessibilityText(`第 ${idx + 1} 段，${part.name}，${formatDuration(part.durationMs)}，点按打开播放页`)
              .onClick((): void => {
                this.closeSheet();
                this.openPlayer(part);
              })
            }, (part: VideoClip): string => part.path)
          }
          .width('100%')
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
      }
      .width('100%')
      .height('100%')
    } else if (this.sheetKind === 'menu') {
      Column({ space: 8 }) {
        if (this.menuClip !== undefined && this.seriesTargets(this.menuClip).length > 1) {
          Text('组级动作自动作用于该次录制的全部段。')
            .fontSize(11)
            .fontColor('#78909C')
            .width('100%')
        }
        if (this.menuClip !== undefined) {
          this.actionRow(this.menuClip)
        }
        Button('关闭')
          .fontSize(11)
          .height(26)
          .width('100%')
          .onClick((): void => {
            this.closeSheet();
          })
      }
      .width('100%')
      .height('100%')
        .padding({ top: 4 })
    } else {
      Column({ space: 6 }) {
        Row({ space: 6 }) {
          TextInput({ text: this.tagInput, placeholder: '新标签（1-24 字）' })
            .fontSize(12)
            .layoutWeight(1)
            .height(30)
            .onChange((v: string): void => {
              this.tagInput = v;
            })
          Button('添加')
            .fontSize(11)
            .height(26)
            .enabled(!this.actionBusy && this.dbOk && this.tagInput.trim() !== '')
            .onClick((): void => {
              const clip: VideoClip | undefined = this.tagDialogClip;
              const input: string = this.tagInput.trim();
              if (clip !== undefined && input !== '') {
                this.addTagToClip(clip, input);
                this.tagInput = '';
              }
            })
        }
        .width('100%')
        if (this.tagDialogTags.length === 0) {
          Text('暂无自定义标签')
            .fontSize(11)
            .fontColor('#78909C')
        }
        List({ space: 4 }) {
          ForEach(this.tagDialogTags, (tag: string): void => {
            ListItem() {
              Row({ space: 8 }) {
                Text(tag)
                  .fontSize(12)
                  .fontColor(Color.White)
                  .layoutWeight(1)
                  .maxLines(1)
                Button('移除')
                  .fontSize(11)
                  .height(24)
                  .accessibilityText(`移除标签 ${tag}`)
                  .onClick((): void => {
                    this.removeTagFromClip(tag);
                  })
              }
              .width('100%')
            }
          }, (tag: string): string => tag)
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
      }
      .width('100%')
      .height('100%')
    }
  }

  private sheetTitle(): string {
    if (this.sheetKind === 'series') {
      return `分段清单（${this.seriesSheetParts().length} 段）`;
    }
    if (this.sheetKind === 'tags') {
      return `标签：${this.tagDialogClip !== undefined ? this.tagDialogClip.name : ''}`;
    }
    if (this.sheetKind === 'menu') {
      return `操作：${this.menuClip !== undefined ? this.menuClip.name : ''}`;
    }
    return '';
  }

  private closeSheet(): void {
    this.sheetKind = 'none';
    this.seriesSheetId = undefined;
    this.tagDialogClip = undefined;
    this.tagInput = '';
    this.menuClip = undefined;
  }
}

/**
 * 媒体卡缩略图（上游 VideoThumbnail 的对应物）：ThumbLoader 取中点帧，命中即显示，
 * 未就绪/失败退 MP4 占位块。@Prop 驱动：列表滚动复用组件时 path 变化会重新加载。
 */
/** G3a：时间线一个日组（label/dayKey 来自纯逻辑；entries 已回查成页面 entry） */
class TimelineDay {
  label: string;
  dayKey: string;
  entries: Array<GalleryEntry>;

  constructor(label: string, dayKey: string, entries: Array<GalleryEntry>) {
    this.label = label;
    this.dayKey = dayKey;
    this.entries = entries;
  }
}

@Component
struct GalleryThumb {
  @Prop @Watch('onPathChange') path: string = '';
  @Prop durationMs: number = 0;
  @State private pm: image.PixelMap | undefined = undefined;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    this.loadThumb();
  }

  /** 列表复用导致 path 变化时重载（@Prop 更新不走 aboutToAppear，必须挂 @Watch） */
  private onPathChange(): void {
    this.pm = undefined;
    this.loadThumb();
  }

  /** 加载（旧请求回执按代序丢弃，复用/快速刷新不串图） */
  private loadThumb(): void {
    const path: string = this.path;
    const seq: number = ++this.loadSeq;
    ThumbLoader.load(path, this.durationMs, 160).then((pm: image.PixelMap | undefined): void => {
      if (seq === this.loadSeq) {
        this.pm = pm;
      }
    }).catch((_e: Object): void => {
      // 失败保持占位块（ThumbLoader 内已记日志）
    });
  }

  build() {
    if (this.pm !== undefined) {
      Image(this.pm)
        .width('100%')
        .height('100%')
        .objectFit(ImageFit.Cover)
        .interpolation(ImageInterpolation.Medium)
    } else {
      Column() {
        Text('MP4')
          .fontSize(12)
          .fontColor(Color.White)
      }
      .width('100%')
      .height('100%')
      .justifyContent(FlexAlign.Center)
      .backgroundColor('#37474F')
    }
  }
}
