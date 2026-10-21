/**
 * 播放页最小版（上游 player/PlayerScreen.kt 的子集，对齐上游 38d712a）：
 * AVPlayer + XComponent(SURFACE)；进度/播放暂停/逐帧/A-B/倍速/帧率/镜像；单击显隐、双击启停；
 * 双指缩放（0.1..4）与双指平移（clampPan 半域）。
 *
 * 平台偏离（登记）：倍速 0.125/0.25/0.5/0.75/1.0（无 0.1/0.3）；帧率取 .drops.json dstFps →
 * 30 默认 → 页面 24/25/30 手动；逐帧 SEEK_CLOSEST；A/B 仅会话内保存。
 */
import { common } from '@kit.AbilityKit';
import { ArcDropLog } from '../record/ArcDropLog';
import { MediaRepo, VideoClip } from '../media/MediaRepo';
import { formatDuration, formatSize } from '../media/MediaFormat';
import { PlayerEngine } from '../player/PlayerEngine';
import { AbRange, clampPan, Pan, PlayerSpeedTiers } from '../player/PlayerModel';
import { TapArbiter, TapDecision } from '../player/TapArbiter';
import { ClipExportResult, ClipExporter, ClipTrackInfo } from '../player/ClipExporter';
import { audioOrdinalOf, ClipRange, MIN_KEEP_MS, TrackChoice } from '../player/ClipRule';

interface PlayerParams {
  id: number;
  path?: string;
}

/** Compare 页入参：左片 id + 路径提示（路径命中时 findById 不扫全库） */
interface CompareRouteParams {
  leftId: number;
  leftPath: string;
}

const FPS_OPTIONS: Array<number> = [24, 25, 30];
const TAP_WINDOW_MS: number = 280;

@Entry
@Component
struct Player {
  @State clipName: string = '';
  @State loading: boolean = true;
  /** 致命错误（attach/片源/引擎错误）：页面轮询不因瞬时提示停摆 */
  @State fatalError: string = '';
  /** 瞬时提示（A/B 校验、surface 等），可自愈/可点掉 */
  @State hint: string = '';
  /** findById 期间的探测满载提示（独立于 attach 提示，attach 成功不覆盖） */
  @State metaNotice: string = '';
  @State positionMs: number = 0;
  @State durationMs: number = 0;
  @State sliderValue: number = 0;
  @State sliderDragging: boolean = false;
  @State playing: boolean = false;
  @State speed: number = PlayerSpeedTiers.DEFAULT;
  @State actualSpeed: number = PlayerSpeedTiers.DEFAULT;
  @State fps: number = 30;
  @State mirrored: boolean = false;
  @State zoom: number = 1;
  @State panX: number = 0;
  @State panY: number = 0;
  @State controlsVisible: boolean = true;
  @State abA: number = -1;
  @State abB: number = -1;
  @State abLoop: boolean = false;
  @State exportPanelVisible: boolean = false;
  @State exportBusy: boolean = false;
  @State exportProbing: boolean = false;
  @State exportProgress: number = 0;
  @State exportError: string = '';
  @State exportNote: string = '';
  @State exportDonePath: string = '';
  @State exportDoneSize: number = 0;
  @State exportAudioCount: number = 0;
  @State exportTrack: TrackChoice = TrackChoice.NO_AUDIO;
  @State exportProbeBlocked: boolean = false;

  private engine: PlayerEngine = new PlayerEngine();
  private xc: XComponentController = new XComponentController();
  private repo: MediaRepo | undefined = undefined;
  private videoClip: VideoClip | undefined = undefined;
  private ctx: common.Context | undefined = undefined;
  private exportAbA: number = -1;
  private exportAbB: number = -1;
  private exportProbeSeq: number = 0;
  private surfaceId: string = '';
  private attachStarted: boolean = false;
  private disposed: boolean = false;
  private pageVisible: boolean = true;
  private stepping: boolean = false;
  private restorePosMs: number = -1;
  private restoreAb: AbRange | undefined = undefined;
  private restoreSpeed: number = PlayerSpeedTiers.DEFAULT;
  private restorePlaying: boolean = false;
  private stepQueue: Array<boolean> = [];
  private pageTimer: number = -1;
  private tapTimer: number = -1;
  private tapArbiter: TapArbiter = new TapArbiter(TAP_WINDOW_MS);
  private baseScale: number = 1;
  private basePanX: number = 0;
  private basePanY: number = 0;
  private viewW: number = 0;
  private viewH: number = 0;

  aboutToAppear(): void {
    this.disposed = false;
    this.pageVisible = true;
    const ctx: common.Context | undefined =
      this.getUIContext().getHostContext() as common.Context | undefined;
    if (ctx === undefined) {
      this.loading = false;
      this.fatalError = 'no host context';
      return;
    }
    const raw: Object = this.getUIContext().getRouter().getParams();
    if (raw === undefined) {
      this.loading = false;
      this.fatalError = '缺少片源参数';
      return;
    }
    const params: PlayerParams = raw as PlayerParams;
    this.ctx = ctx;
    this.repo = new MediaRepo(ctx);
    MediaRepo.resetProbeCapHit();
    this.repo.findById(params.id, params.path ?? '').then((clip: VideoClip | undefined): void => {
      if (this.disposed) {
        return;
      }
      if (clip === undefined) {
        this.loading = false;
        this.fatalError = '片已不存在';
        return;
      }
      this.videoClip = clip;
      this.clipName = clip.name;
      if (MediaRepo.probeCapHit()) {
        this.metaNotice = '部分视频元数据暂不可用（探测繁忙），可返回后刷新';
      }
      this.durationMs = clip.durationMs;
      let fpsHint: number = 30;
      const drops: ArcDropLog | null = ArcDropLog.readFrom(clip.path);
      if (drops !== null && drops.dstFps > 0) {
        fpsHint = drops.dstFps;
      }
      this.fps = this.snapFps(fpsHint);
      this.tryAttach();
    }).catch((e: Object): void => {
      this.loading = false;
      this.fatalError = `读取片源失败: ${e}`;
    });
  }

  onPageShow(): void {
    this.pageVisible = true;
    // 不依赖 attachStarted：av 未就绪时 resumeTick 是安全空操作；后续 attach 成功会再显式 resume
    if (!this.disposed && this.fatalError === '') {
      this.engine.resumeTick();
      if (this.attachStarted) {
        this.startPageTimer();
      }
    }
  }

  onPageHide(): void {
    this.pageVisible = false;
    this.stopPageTimer();
    this.engine.suspendTick();
    this.cancelTapTimer();
    // 拖动中隐藏可能收不到 End：强制清拖动态，回页后位置与 AB 回绕不被锁死
    this.sliderDragging = false;
    this.engine.setSeeking(false);
    this.stepQueue = [];
    // surface 重建待恢复的播放意图在隐藏期间必须作废（隐藏页不得后台起播）
    this.restorePlaying = false;
    if (this.engine.playing) {
      this.engine.playPause(); // 隐藏即暂停（返回时保留位置）
    }
  }

  aboutToDisappear(): void {
    this.disposed = true;
    this.pageVisible = false;
    if (this.exportBusy) {
      ClipExporter.cancel(); // 页面销毁不得让 native worker 继续持有已关的 fd
    }
    this.stopPageTimer();
    this.cancelTapTimer();
    this.stepQueue = [];
    this.engine.release();
  }

  /** 导出中拦住返回；面板打开时先关面板 */
  onBackPress(): boolean {
    if (this.exportBusy) {
      this.hint = '导出中，请先取消';
      return true;
    }
    if (this.exportPanelVisible) {
      this.closeExportPanel();
      return true;
    }
    return false;
  }

  /** 侧车 fps 吸附到页面三档（29.97/60 等非标值取最近档；实际使用档由按钮显示） */
  private snapFps(fps: number): number {
    let best: number = FPS_OPTIONS[0];
    let bestDiff: number = Number.MAX_VALUE;
    for (const option of FPS_OPTIONS) {
      const diff: number = Math.abs(option - fps);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = option;
      }
    }
    return best;
  }

  private tryAttach(): void {
    const clip: VideoClip | undefined = this.videoClip;
    if (this.disposed || this.attachStarted || clip === undefined || this.surfaceId === '') {
      return;
    }
    this.attachStarted = true;
    this.loading = true;
    const engine: PlayerEngine = this.engine; // 捕获实例：surface 重建会换新引擎
    engine.attach(this.surfaceId, clip, this.fps).then((error: string): void => {
      if (this.disposed || this.engine !== engine || error === 'cancelled') {
        // 页面已销毁/引擎已换代：attach 可能已内部清理，再对**本实例**幂等 release
        engine.release();
        return;
      }
      this.loading = false;
      if (error !== '') {
        this.fatalError = error;
        this.attachStarted = false; // 允许 same-sid 回调/返回重进后重建引擎再试
        engine.release();
        this.engine = new PlayerEngine();
        return;
      }
      this.fatalError = '';
      this.hint = '';
      this.durationMs = engine.durationMs > 0 ? engine.durationMs : this.durationMs;
      let shouldPlay: boolean = false;
      if (this.restorePosMs >= 0 && this.restoreAb !== undefined) {
        engine.restoreSession(this.restorePosMs, this.restoreAb, this.restoreSpeed);
        this.positionMs = Math.round(this.restorePosMs);
        this.sliderValue = this.positionMs;
        this.abA = this.restoreAb.aMs;
        this.abB = this.restoreAb.bMs;
        this.abLoop = this.restoreAb.loopEnabled;
        this.speed = this.restoreSpeed;
        this.restorePosMs = -1;
        this.restoreAb = undefined;
        // 播放意图只在可见时消费；隐藏路径恢复位置/AB/倍速但保持暂停
        shouldPlay = this.restorePlaying && this.pageVisible;
        this.restorePlaying = false;
      }
      if (this.pageVisible) {
        engine.resumeTick(); // attach 期间页面可能 hide/show 过：成功且可见时显式恢复
        if (shouldPlay) {
          engine.playPause();
        }
        this.startPageTimer();
      } else {
        this.restorePlaying = false; // 双重抑制：隐藏期间 attach 成功也不起播
        engine.suspendTick();
      }
    }).catch((e: Object): void => {
      this.loading = false;
      this.fatalError = `播放器启动失败: ${e}`;
    });
  }

  private canRetryAttach(): boolean {
    return this.fatalError.indexOf('attach-failed') === 0 ||
      this.fatalError.indexOf('播放器启动失败') === 0;
  }

  private retryAttach(): void {
    if (this.disposed || this.loading) {
      return;
    }
    this.engine.release();
    this.engine = new PlayerEngine();
    this.attachStarted = false;
    this.stepQueue = [];
    this.fatalError = '';
    this.loading = true;
    if (this.surfaceId === '') {
      this.loading = false;
      this.hint = 'surface 未就绪';
      return;
    }
    this.tryAttach();
  }

  private startPageTimer(): void {
    this.stopPageTimer();
    this.pageTimer = setInterval((): void => {
      if (this.disposed) {
        return;
      }
      const e: PlayerEngine = this.engine;
      if (e.error !== '' && this.fatalError === '') {
        this.fatalError = e.error;
      }
      this.playing = e.playing;
      this.speed = e.speed;
      this.actualSpeed = e.actualSpeed;
      this.abA = e.ab.aMs;
      this.abB = e.ab.bMs;
      this.abLoop = e.ab.loopEnabled;
      if (e.durationMs > 0) {
        this.durationMs = e.durationMs;
      }
      if (!this.sliderDragging) {
        const pos: number = Math.round(e.positionMs);
        if (pos !== this.positionMs) {
          this.positionMs = pos;
          this.sliderValue = pos;
        }
      }
    }, 120);
  }

  private stopPageTimer(): void {
    if (this.pageTimer >= 0) {
      clearInterval(this.pageTimer);
      this.pageTimer = -1;
    }
  }

  private cancelTapTimer(): void {
    if (this.tapTimer >= 0) {
      clearTimeout(this.tapTimer);
      this.tapTimer = -1;
    }
    this.tapArbiter.cancelPending();
  }

  private onSurfaceLoaded(): void {
    if (this.disposed) {
      return;
    }
    const sid: string = this.xc.getXComponentSurfaceId();
    if (sid === '') {
      this.loading = false;
      this.hint = 'surface 未就绪，等待重建';
      return;
    }
    if (sid === this.surfaceId) {
      // same-sid 重回调：若上次 attach 失败/未开始，清错误提示后重建再试一次
      if (this.surfaceId !== '' && !this.attachStarted) {
        this.hint = '';
        if (this.fatalError.indexOf('attach-failed') === 0 ||
          this.fatalError.indexOf('播放器启动失败') === 0) {
          this.fatalError = '';
        }
        if (this.fatalError === '') {
          this.tryAttach();
        }
      }
      return;
    }
    if (this.surfaceId !== '' && this.attachStarted) {
      // surface 重建：旧引擎不可复用（release 后拒绝复活），换新实例重新 attach。
      // 仅旧引擎已 ready 时保存会话（位置/AB/倍速/playing），且不覆盖首次待恢复值。
      if (this.engine.isReady() && this.restorePosMs < 0) {
        this.restorePosMs = this.engine.positionMs;
        this.restoreAb = new AbRange(this.engine.ab.aMs, this.engine.ab.bMs, this.engine.ab.loopEnabled);
        this.restoreSpeed = this.engine.speed;
        this.restorePlaying = this.engine.playing;
      }
      this.engine.release();
      this.engine = new PlayerEngine();
      this.attachStarted = false;
      this.stepQueue = [];
      this.loading = true;
      this.sliderDragging = false;
      this.engine.setSeeking(false);
      this.fatalError = '';
      this.hint = '';
    }
    this.surfaceId = sid;
    this.tryAttach();
  }

  private onTap(): void {
    const decision: TapDecision = this.tapArbiter.onPointerUp(Date.now());
    if (decision === TapDecision.PlayPause) {
      this.cancelTapTimer();
      this.engine.playPause();
      return;
    }
    if (this.tapTimer >= 0) {
      clearTimeout(this.tapTimer);
    }
    this.tapTimer = setTimeout((): void => {
      this.tapTimer = -1;
      if (this.tapArbiter.onToggleDue(Date.now())) {
        this.controlsVisible = !this.controlsVisible;
      }
    }, TAP_WINDOW_MS);
  }

  private toggleFps(): void {
    let idx: number = FPS_OPTIONS.indexOf(this.fps);
    idx = idx < 0 ? 0 : (idx + 1) % FPS_OPTIONS.length;
    this.fps = FPS_OPTIONS[idx];
    this.engine.setFps(this.fps);
  }

  private cycleSpeed(): void {
    const next: number = PlayerSpeedTiers.next(this.speed);
    this.speed = next;
    this.engine.setSpeed(next);
  }

  private async stepFrame(fwd: boolean): Promise<void> {
    if (this.stepping) {
      // 连点排队（上限 8）：FIFO 消费，当前 step 完成后继续推进
      if (this.stepQueue.length < 8) {
        this.stepQueue.push(fwd);
      }
      return;
    }
    this.stepping = true;
    const engine: PlayerEngine = this.engine;
    try {
      const target: number = await engine.step(fwd);
      if (this.disposed || this.engine !== engine) {
        return; // 引擎已换代：旧目标不得写回页面
      }
      this.positionMs = Math.round(target);
      this.sliderValue = this.positionMs;
      this.playing = false;
    } catch (e) {
      this.stepQueue = []; // 异常后不再消费积压方向
    } finally {
      this.stepping = false;
    }
    const queued: boolean | undefined = this.stepQueue.shift();
    if (queued !== undefined && !this.disposed && this.engine === engine) {
      this.stepFrame(queued);
    }
  }

  private markA(): void {
    this.engine.markA();
    this.syncAb();
  }

  private markB(): void {
    if (!this.engine.markB()) {
      this.hint = 'B 必须晚于 A';
    }
    this.syncAb();
  }

  private toggleAbLoop(): void {
    if (!this.engine.toggleAbLoop()) {
      this.hint = '需先设置合法的 A/B';
    }
    this.syncAb();
  }

  private clearAb(): void {
    this.engine.clearAb();
    this.hint = '';
    this.syncAb();
  }

  private syncAb(): void {
    this.abA = this.engine.ab.aMs;
    this.abB = this.engine.ab.bMs;
    this.abLoop = this.engine.ab.loopEnabled;
  }

  private canExportAb(): boolean {
    return this.abA >= 0 && this.abB > this.abA && this.abB - this.abA >= MIN_KEEP_MS;
  }

  private openExportPanel(): void {
    const clip: VideoClip | undefined = this.videoClip;
    const ctx: common.Context | undefined = this.ctx;
    if (clip === undefined || ctx === undefined) {
      this.hint = '片源未就绪';
      return;
    }
    if (!this.canExportAb()) {
      this.hint = `保留段至少 ${MIN_KEEP_MS}ms`;
      return;
    }
    if (this.engine.playing) {
      this.engine.playPause(); // 导出期间保持暂停；不动物理 A/B
    }
    this.exportAbA = this.abA;
    this.exportAbB = this.abB;
    this.exportPanelVisible = true;
    this.exportBusy = false;
    this.exportProbing = true;
    this.exportProbeBlocked = false;
    this.exportProgress = 0;
    this.exportError = '';
    this.exportNote = '';
    this.exportDonePath = '';
    this.exportDoneSize = 0;
    this.exportAudioCount = 0;
    this.exportTrack = TrackChoice.NO_AUDIO;
    const probeSeq: number = ++this.exportProbeSeq;
    ClipExporter.probe(ctx, clip).then((info: ClipTrackInfo):void => {
      if (this.disposed || probeSeq !== this.exportProbeSeq) {
        return;
      }
      this.exportProbing = false;
      if (info.error !== '') {
        this.exportProbeBlocked = true;
        this.exportError = `轨道探测失败: ${info.error}`;
        return;
      }
      if (info.videoCount <= 0) {
        this.exportProbeBlocked = true;
        this.exportError = '源无视频轨';
        return;
      }
      this.exportAudioCount = info.audioCount;
      this.exportTrack = info.audioCount > 0 ? TrackChoice.ENV : TrackChoice.NO_AUDIO;
    }).catch((e: Object): void => {
      if (this.disposed || probeSeq !== this.exportProbeSeq) {
        return;
      }
      this.exportProbing = false;
      this.exportProbeBlocked = true;
      this.exportError = `轨道探测失败: ${e}`;
    });
  }

  private closeExportPanel(): void {
    if (this.exportBusy) {
      return;
    }
    this.exportProbeSeq++;
    this.exportPanelVisible = false;
  }

  private startExport(): void {
    const clip: VideoClip | undefined = this.videoClip;
    const ctx: common.Context | undefined = this.ctx;
    if (this.exportBusy || this.exportProbing || this.exportProbeBlocked || clip === undefined ||
      ctx === undefined) {
      return;
    }
    const range: ClipRange = new ClipRange(this.exportAbA, this.exportAbB);
    this.exportBusy = true;
    this.exportProgress = 0;
    this.exportError = '';
    this.exportNote = '';
    this.exportDonePath = '';
    this.exportDoneSize = 0;
    ClipExporter.exportClip(ctx, clip, range, audioOrdinalOf(this.exportTrack),
      (percent: number): void => {
        if (!this.disposed) {
          this.exportProgress = percent;
        }
      }).then((result: ClipExportResult): void => {
        if (this.disposed) {
          return;
        }
        this.exportBusy = false;
        if (result.ok) {
          this.exportDonePath = result.path;
          this.exportDoneSize = result.sizeBytes;
          const audioSkipped: boolean = result.audioSkipped && this.exportTrack !== TrackChoice.NO_AUDIO;
          let note: string = '';
          if (audioSkipped) {
            note = '所选音轨区间内无样本，已导出纯视频';
          }
          if (!result.sidecarOk) {
            note = note === '' ? '.drops.json 平移写失败（成片不受影响）' :
              `${note}；.drops.json 平移写失败（成片不受影响）`;
          }
          this.exportNote = note;
        } else if (result.cancelled) {
          this.exportNote = '';
          this.exportError = '已取消，半成品已删除';
        } else {
          this.exportNote = '';
          this.exportError = `导出失败: ${result.error}`;
        }
      }).catch((e: Object): void => {
        if (this.disposed) {
          return;
        }
        this.exportBusy = false;
        this.exportError = `导出失败: ${e}`;
      });
  }

  private cancelExport(): void {
    if (!this.exportBusy) {
      return;
    }
    ClipExporter.cancel();
    this.exportNote = '正在取消…';
  }

  private openExportGallery(): void {
    if (this.exportBusy) {
      return;
    }
    this.exportPanelVisible = false;
    this.getUIContext().getRouter().pushUrl({ url: 'pages/Gallery' }).catch((e: Object): void => {
      this.hint = `打开媒体库失败: ${e}`;
    });
  }

  @Builder
  private topBar() {
    Row({ space: 8 }) {
      Button('返回')
        .fontSize(12)
        .onClick((): void => {
          this.getUIContext().getRouter().back();
        })
      Text(this.clipName)
        .fontSize(12)
        .fontColor(Color.White)
        .maxLines(1)
        .layoutWeight(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Button('对比')
        .fontSize(11)
        .enabled(this.videoClip !== undefined)
        .onClick((): void => {
          const clip: VideoClip | undefined = this.videoClip;
          if (clip === undefined) {
            return;
          }
          const params: CompareRouteParams = { leftId: clip.id, leftPath: clip.path };
          this.getUIContext().getRouter().pushUrl({ url: 'pages/Compare', params: params });
        })
      Button(`帧率:${this.fps}`)
        .fontSize(11)
        .onClick((): void => {
          this.toggleFps();
        })
      Button(`镜像:${this.mirrored ? '开' : '关'}`)
        .fontSize(11)
        .onClick((): void => {
          this.mirrored = !this.mirrored;
        })
    }
    .width('100%')
    .padding({ left: 10, right: 10, top: 8 })
  }

  @Builder
  private bottomBar() {
    Column({ space: 4 }) {
      Row({ space: 8 }) {
        Text(formatDuration(this.positionMs))
          .fontSize(10)
          .fontColor('#B0BEC5')
        Slider({
          value: this.sliderValue,
          min: 0,
          max: this.durationMs > 0 ? this.durationMs : 1,
          step: 1
        })
          .layoutWeight(1)
          .onChange((value: number, mode: SliderChangeMode): void => {
            this.sliderValue = value;
            if (mode === SliderChangeMode.Begin || mode === SliderChangeMode.Moving) {
              this.sliderDragging = true;
              this.engine.setSeeking(true);
              this.positionMs = Math.round(value);
            } else if (mode === SliderChangeMode.End) {
              this.sliderDragging = false;
              this.engine.setSeeking(false);
              this.positionMs = Math.round(value);
              this.engine.seekTo(value);
            } else if (mode === SliderChangeMode.Click) {
              this.sliderDragging = false;
              this.engine.setSeeking(false);
              this.positionMs = Math.round(value);
              this.engine.seekTo(value);
            }
          })
        Text(formatDuration(this.durationMs))
          .fontSize(10)
          .fontColor('#B0BEC5')
      }
      .width('100%')

      Scroll() {
        Row({ space: 6 }) {
        Button('◀帧')
          .fontSize(11)
          .onClick((): void => {
            this.stepFrame(false);
          })
        Button('帧▶')
          .fontSize(11)
          .onClick((): void => {
            this.stepFrame(true);
          })
        Button(`A:${this.abA >= 0 ? formatDuration(this.abA) : '--'}`)
          .fontSize(11)
          .onClick((): void => {
            this.markA();
          })
        Button(`B:${this.abB >= 0 ? formatDuration(this.abB) : '--'}`)
          .fontSize(11)
          .onClick((): void => {
            this.markB();
          })
        Button(`循环:${this.abLoop ? '开' : '关'}`)
          .fontSize(11)
          .onClick((): void => {
            this.toggleAbLoop();
          })
        Button('清除')
          .fontSize(11)
          .onClick((): void => {
            this.clearAb();
          })
        if (this.canExportAb()) {
          Button('导出 A/B')
            .fontSize(11)
            .onClick((): void => {
              this.openExportPanel();
            })
        }
        Button(`倍速:${this.speed}/${this.actualSpeed}`)
          .fontSize(11)
          .onClick((): void => {
            this.cycleSpeed();
          })
        }
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .height(38)
      .width('100%')
    }
    .width('100%')
    .padding({ left: 10, right: 10, bottom: 10 })
  }

  @Builder
  private exportPanel() {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Text('导出 A/B')
          .fontSize(14)
          .fontColor(Color.White)
          .layoutWeight(1)
        Button('关闭')
          .fontSize(11)
          .enabled(!this.exportBusy)
          .onClick((): void => {
            this.closeExportPanel();
          })
      }
      .width('100%')

      Text(`${formatDuration(this.exportAbA)} - ${formatDuration(this.exportAbB)}`)
        .fontSize(11)
        .fontColor('#B0BEC5')
        .width('100%')

      if (this.exportProbing) {
        Row({ space: 8 }) {
          LoadingProgress()
            .width(22)
            .height(22)
          Text('探测音轨…')
            .fontSize(11)
            .fontColor('#AAAAAA')
        }
        .width('100%')
      } else if (this.exportProbeBlocked) {
        Text(this.exportError)
          .fontSize(11)
          .fontColor('#FF8A80')
          .maxLines(3)
          .width('100%')
      } else {
        Text('音轨')
          .fontSize(10)
          .fontColor('#90A4AE')
          .width('100%')
        Row({ space: 6 }) {
          Button('无音轨')
            .fontSize(11)
            .backgroundColor(this.exportTrack === TrackChoice.NO_AUDIO ? '#FF3D71' : '#333333')
            .enabled(!this.exportBusy)
            .onClick((): void => {
              this.exportTrack = TrackChoice.NO_AUDIO;
            })
          if (this.exportAudioCount >= 1) {
            Button('音轨 1')
              .fontSize(11)
              .backgroundColor(this.exportTrack === TrackChoice.ENV ? '#FF3D71' : '#333333')
              .enabled(!this.exportBusy)
              .onClick((): void => {
                this.exportTrack = TrackChoice.ENV;
              })
          }
          if (this.exportAudioCount >= 2) {
            Button('音轨 2')
              .fontSize(11)
              .backgroundColor(this.exportTrack === TrackChoice.CAP ? '#FF3D71' : '#333333')
              .enabled(!this.exportBusy)
              .onClick((): void => {
                this.exportTrack = TrackChoice.CAP;
              })
          }
        }
        .width('100%')

        if (this.exportBusy) {
          Progress({ value: this.exportProgress, total: 100, type: ProgressType.Linear })
            .width('100%')
          Text(`导出中 ${this.exportProgress}%`)
            .fontSize(11)
            .fontColor('#FFCC80')
            .width('100%')
          if (this.exportNote !== '') {
            Text(this.exportNote)
              .fontSize(10)
              .fontColor('#90A4AE')
              .width('100%')
          }
          Button('取消导出')
            .fontSize(11)
            .onClick((): void => {
              this.cancelExport();
            })
        } else if (this.exportDonePath !== '') {
          Text('导出成功')
            .fontSize(12)
            .fontColor('#81C784')
            .width('100%')
          Text(this.exportDonePath)
            .fontSize(10)
            .fontColor(Color.White)
            .maxLines(3)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
          Text(formatSize(this.exportDoneSize))
            .fontSize(10)
            .fontColor('#90A4AE')
            .width('100%')
          if (this.exportNote !== '') {
            Text(this.exportNote)
              .fontSize(10)
              .fontColor('#FFCC80')
              .maxLines(2)
              .width('100%')
          }
          Row({ space: 8 }) {
            Button('打开媒体库')
              .fontSize(11)
              .onClick((): void => {
                this.openExportGallery();
              })
            Button('完成')
              .fontSize(11)
              .onClick((): void => {
                this.exportPanelVisible = false;
              })
          }
          .width('100%')
        } else {
          Button('开始导出')
            .fontSize(12)
            .onClick((): void => {
              this.startExport();
            })
          if (this.exportError !== '') {
            Text(this.exportError)
              .fontSize(10)
              .fontColor('#FF8A80')
              .maxLines(3)
              .width('100%')
          }
          if (this.exportNote !== '') {
            Text(this.exportNote)
              .fontSize(10)
              .fontColor('#FFCC80')
              .width('100%')
          }
        }
      }
    }
    .width('90%')
    .padding(14)
    .backgroundColor('#F0202020')
    .borderRadius(12)
  }

  build() {
    Stack() {
      XComponent({ type: XComponentType.SURFACE, controller: this.xc })
        .onLoad((): void => {
          this.onSurfaceLoaded();
        })
        .onAreaChange((_old: Area, area: Area): void => {
          this.viewW = Number(area.width);
          this.viewH = Number(area.height);
        })
        .scale({ x: this.mirrored ? -this.zoom : this.zoom, y: this.zoom })
        .translate({ x: this.panX, y: this.panY })
        .height('100%')
        .width('100%')

      // 手势层（在控制层之下）：单击/双击仲裁 + 双指缩放/平移
      Column()
        .width('100%')
        .height('100%')
        .gesture(
          GestureGroup(GestureMode.Parallel,
            TapGesture({ count: 1 })
              .onAction((): void => {
                this.onTap();
              }),
            PinchGesture({ fingers: 2 })
              .onActionStart((): void => {
                this.baseScale = this.zoom;
              })
              .onActionUpdate((event: GestureEvent): void => {
                const s: number = Math.min(4, Math.max(0.1, this.baseScale * event.scale));
                this.zoom = s;
                const p: Pan = clampPan(this.panX, this.panY, s, this.viewW, this.viewH);
                this.panX = p.x;
                this.panY = p.y;
              })
              .onActionEnd((): void => {
                const p: Pan = clampPan(this.panX, this.panY, this.zoom, this.viewW, this.viewH);
                this.panX = p.x;
                this.panY = p.y;
              }),
            PanGesture({ fingers: 2, direction: PanDirection.All, distance: 1 })
              .onActionStart((): void => {
                this.basePanX = this.panX;
                this.basePanY = this.panY;
              })
              .onActionUpdate((event: GestureEvent): void => {
                const p: Pan = clampPan(this.basePanX + event.offsetX,
                  this.basePanY + event.offsetY, this.zoom, this.viewW, this.viewH);
                this.panX = p.x;
                this.panY = p.y;
              })
              .onActionEnd((): void => {
                this.basePanX = this.panX;
                this.basePanY = this.panY;
              })
          )
        )

      if (this.controlsVisible) {
        Column() {
          this.topBar()
          Blank()
          Button(this.playing ? '⏸' : '▶')
            .fontSize(28)
            .width(64)
            .height(64)
            .backgroundColor('#66000000')
            .borderRadius(32)
            .onClick((): void => {
              this.engine.playPause();
            })
          Blank()
          this.bottomBar()
        }
        .width('100%')
        .height('100%')
        .hitTestBehavior(HitTestMode.Transparent)
      }

      if (this.metaNotice !== '') {
        Text(this.metaNotice)
          .fontSize(10)
          .fontColor('#FFCC80')
          .maxLines(2)
          .textAlign(TextAlign.Center)
          .width('100%')
          .padding({ top: 64, left: 12, right: 12 })
          .position({ x: 0, y: 0 })
      }

      if (this.loading) {
        Column({ space: 8 }) {
          LoadingProgress()
            .width(36)
            .height(36)
          Text('准备播放…')
            .fontSize(12)
            .fontColor('#AAAAAA')
          Button('返回')
            .fontSize(12)
            .onClick((): void => {
              this.getUIContext().getRouter().back();
            })
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
      }

      if (this.hint !== '') {
        Text(this.hint)
          .fontSize(11)
          .fontColor('#FFCC80')
          .maxLines(2)
          .padding(8)
          .backgroundColor('#66000000')
          .borderRadius(6)
          .onClick((): void => {
            this.hint = '';
          })
      }

      if (this.fatalError !== '') {
        Column({ space: 8 }) {
          Text(this.fatalError)
            .fontSize(12)
            .fontColor('#FF8A80')
            .maxLines(4)
          if (this.canRetryAttach()) {
            Button('重试播放')
              .fontSize(12)
              .onClick((): void => {
                this.retryAttach();
              })
          }
        }
        .padding(16)
        .backgroundColor('#66000000')
        .borderRadius(8)
      }

      if (this.exportPanelVisible) {
        Column() {
          this.exportPanel()
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#88000000')
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
}
