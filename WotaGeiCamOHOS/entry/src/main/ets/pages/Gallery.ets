/**
 * 媒体库最小版（对应上游 media/GalleryScreen.kt 的列表/页签/动作子集，对齐上游 38d712a）：
 * - 三页签：全部 / 收藏 / 回收站（自定义标签页留待标签管理批）；
 * - 卡片：文件名、时长、大小、日期、收藏星标、动作按钮；占位块不做缩略图；
 * - 点击卡片进入播放页；「信息」按钮显示元信息面板；
 * - 回收站语义：移入只写本地记录、文件原地保留；彻底删除二次确认后才 unlink 文件与 .drops.json。
 */
import { common } from '@kit.AbilityKit';
import { MediaRepo, VideoClip } from '../media/MediaRepo';
import { ClipFilter, GalleryTab, GalleryTabs } from '../media/MediaTag';
import { formatDuration, formatSize } from '../media/MediaFormat';
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
  @State selectedTab: number = 0;
  @State counts: Array<number> = [0, 0, 0];
  @State loading: boolean = true;
  @State actionBusy: boolean = false;
  @State message: string = '';
  @State dbOk: boolean = true;
  @State showDetail: boolean = false;
  @State detailName: string = '';
  @State detailMeta: string = '';

  private repo: MediaRepo | undefined = undefined;
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
    if (ctx === undefined) {
      this.loading = false;
      this.message = 'no host context';
      return;
    }
    // T31：媒体库恢复黑底系统栏（相机页是全屏透明栏，离开相机页必须复位）
    WindowChrome.lastWindow(ctx).then((win): Promise<void> =>
      WindowChrome.applyStandardDark(win)).catch((e: Object): void => {
    });
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

  private openPlayer(clip: VideoClip): void {
    try {
      this.getUIContext().getRouter().pushUrl({ url: 'pages/Player', params: { id: clip.id, path: clip.path } });
    } catch (e) {
      this.message = `打开播放页失败: ${e}`;
    }
  }

  private showDetailOf(clip: VideoClip): void {
    const tags: string = clip.tags.size === 0 ? '无' : Array.from(clip.tags).join(', ');
    this.detailName = clip.name;
    this.detailMeta = `${formatDuration(clip.durationMs)} · ${formatSize(clip.sizeBytes)} · ` +
      `${formatDate(clip.dateAddedSec)}\n${clip.width}x${clip.height} · ${clip.mimeType}\n` +
      `标签: ${tags}\n路径: ${clip.relativePath}\n`;
    this.showDetail = true;
  }

  private async toggleLike(clip: VideoClip): Promise<void> {
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      const ok: boolean = await repo.setLike(clip, !clip.liked);
      const notice: string = ok ? (clip.liked ? '已取消收藏' : '已收藏') : '收藏操作失败（数据库不可用）';
      await this.refreshAll(notice);
    } finally {
      this.actionBusy = false;
    }
  }

  private async moveToTrash(clip: VideoClip): Promise<void> {
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      const ok: boolean = await repo.moveToTrash(clip);
      const notice: string = ok ? '已移入回收站（文件仍保留）' : '移入回收站失败';
      await this.refreshAll(notice);
    } finally {
      this.actionBusy = false;
    }
  }

  private async restore(clip: VideoClip): Promise<void> {
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined || this.actionBusy) {
      return;
    }
    this.actionBusy = true;
    try {
      const ok: boolean = await repo.restore(clip.path);
      const notice: string = ok ? '已还原' : '还原失败';
      await this.refreshAll(notice);
    } finally {
      this.actionBusy = false;
    }
  }

  private confirmDelete(clip: VideoClip): void {
    this.getUIContext().showAlertDialog({
      title: '彻底删除',
      message: `文件与同名 .drops.json 将被永久删除：\n${clip.name}`,
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
      const ok: boolean = await repo.deleteForever(clip);
      const notice: string = ok ? '已彻底删除' : '删除未完全成功（部分文件/记录失败，详见日志）';
      await this.refreshAll(notice);
    } finally {
      this.actionBusy = false;
    }
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
      }
    }
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
        Button('刷新')
          .fontSize(12)
          .enabled(!this.loading)
          .onClick((): void => {
            this.refreshAll();
          })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8 })

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
        List({ space: 8 }) {
          ForEach(this.clips, (clip: VideoClip): void => {
            ListItem() {
              Column({ space: 6 }) {
                Row({ space: 10 }) {
                  Column() {
                    Text('MP4')
                      .fontSize(12)
                      .fontColor(Color.White)
                  }
                  .width(52)
                  .height(52)
                  .justifyContent(FlexAlign.Center)
                  .backgroundColor('#37474F')
                  .borderRadius(6)
                  .onClick((): void => {
                    this.openPlayer(clip);
                  })

                  Column({ space: 2 }) {
                    Text(clip.liked ? `★ ${clip.name}` : clip.name)
                      .fontSize(13)
                      .fontColor(Color.White)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(`${formatDuration(clip.durationMs)} · ${formatSize(clip.sizeBytes)} · ` +
                      `${formatDate(clip.dateAddedSec)}`)
                      .fontSize(10)
                      .fontColor('#B0BEC5')
                    if (clip.width > 0 && clip.height > 0) {
                      Text(`${clip.width}x${clip.height}`)
                        .fontSize(10)
                        .fontColor('#78909C')
                    }
                  }
                  .layoutWeight(1)
                  .alignItems(HorizontalAlign.Start)
                  .onClick((): void => {
                    this.openPlayer(clip);
                  })
                }
                this.actionRow(clip)
              }
              .width('100%')
              .padding(10)
              .backgroundColor('#1F1F1F')
              .borderRadius(8)
            }
          }, (clip: VideoClip): string => clip.path)
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 12, right: 12 })
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
  }
}
