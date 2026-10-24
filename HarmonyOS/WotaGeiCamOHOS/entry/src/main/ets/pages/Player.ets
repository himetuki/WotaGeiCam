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
import { AbRange, clampPan, nextCcwQuarter, Pan, PlayerSpeedTiers } from '../player/PlayerModel';
import { TapArbiter, TapDecision } from '../player/TapArbiter';
import { ClipExportResult, ClipExporter, ClipTrackInfo } from '../player/ClipExporter';
import { audioOrdinalOf, audioShiftUs, ClipRange, MIN_KEEP_MS, TrackChoice } from '../player/ClipRule';
import { SyncPrefsStore } from '../player/SyncPrefsStore';
import { AbPersistStore } from '../player/AbPersistStore';
import { Source, SyncSnapshot, TrackSync } from '../player/TrackSync';
import { ArcRepairProbeResult, ArcRepairResult, ArcRepairRunner } from '../record/ArcRepairRunner';
import { ArcRepairSessionModel } from '../record/ArcRepairSessionModel';
import { ArcRateProbe, MeasuredSrcFps } from '../record/ArcKeepRule';

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
  /** RV：逆时针四分之一圈计数（会话态，不持久化、换片不清零——与镜像同口径） */
  @State quarterTurns: number = 0;
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
  /**
   * 音画同步读数（对齐上游 PlayerScreen 的 syncedOffsetMs/syncedSource）：
   * 进页面时从 prefs 读一次；导出面板展示「已同步 +Xms（自动校准/手动）」与诚实边界说明。
   * 说明：本侧尚无「同步音轨」入口与停止后自动校准（平台面挂 T27-4），所以读数通常为「未同步」；
   * 一旦 T27-4 落盘，这里无需改动即生效（键名/编解码与上游同源）。
   */
  @State syncOffsetMs: number = 0;
  @State syncSource: Source | undefined = undefined;
  @State syncKnown: boolean = false;
  /** T30「修复光弧」面板（CPU 唯一路线；页面销毁不强杀 worker，见 aboutToDisappear） */
  @State repairPanelVisible: boolean = false;
  @State repairBusy: boolean = false;
  @State repairProbing: boolean = false;
  @State repairProbeBlocked: boolean = false;
  @State repairProgress: number = 0;
  @State repairError: string = '';
  @State repairNote: string = '';
  @State repairDonePath: string = '';
  @State repairDoneSize: number = 0;
  @State repairDstFps: number = 24;
  @State repairDeadband: number = 12;
  /** 探测摘要（打开面板时展示：源实测帧率/计划帧数/音轨数） */
  @State repairSrcFps: number = 0;
  @State repairSrcFrames: number = 0;
  @State repairAudioCount: number = 0;
  @State repairAudioSamples: number = 0;

  private engine: PlayerEngine = new PlayerEngine();
  private xc: XComponentController = new XComponentController();
  private repo: MediaRepo | undefined = undefined;
  private videoClip: VideoClip | undefined = undefined;
  private ctx: common.Context | undefined = undefined;
  private exportAbA: number = -1;
  private exportAbB: number = -1;
  private exportProbeSeq: number = 0;
  /** 面板 probe 结果缓存（run 时复用，避免二次全量帧距扫描） */
  private repairProbed: ArcRepairProbeResult | undefined = undefined;
  private repairProbeSeq: number = 0;
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
      this.loadSyncSnapshot(clip);
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
    if (this.repairBusy) {
      // 只发取消位，不 join worker（native fd 由 Runner 的 linger/park 机制收尾）
      ArcRepairRunner.cancel();
    }
    this.stopPageTimer();
    this.cancelTapTimer();
    this.stepQueue = [];
    this.engine.release();
  }

  /** 导出/修复中拦住返回；面板打开时先关面板 */
  onBackPress(): boolean {
    if (this.exportBusy) {
      this.hint = '导出中，请先取消';
      return true;
    }
    if (this.repairBusy) {
      this.hint = '修复中，请先取消';
      return true;
    }
    if (this.exportPanelVisible) {
      this.closeExportPanel();
      return true;
    }
    if (this.repairPanelVisible) {
      this.closeRepairPanel();
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
      // ABP：全新进页（非 surface 重建）水化持久化 AB——restoreSession 路径已带会话 AB
      if (this.restoreAb === undefined) {
        this.loadPersistedAb(clip, engine);
      }
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
    this.persistAb();
  }

  private markB(): void {
    if (!this.engine.markB()) {
      this.hint = 'B 必须晚于 A';
    } else {
      this.persistAb();
    }
    this.syncAb();
  }

  private toggleAbLoop(): void {
    if (!this.engine.toggleAbLoop()) {
      this.hint = '需先设置合法的 A/B';
    } else {
      this.persistAb();
    }
    this.syncAb();
  }

  private clearAb(): void {
    this.engine.clearAb();
    this.hint = '';
    this.syncAb();
    this.persistAb();
  }

  /** ABP：attach 成功后水化持久化 AB（引擎换代/销毁即弃；会话已打点不覆盖） */
  private loadPersistedAb(clip: VideoClip, engine: PlayerEngine): void {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    AbPersistStore.load(ctx, clip.path).then((ab: AbRange | undefined): void => {
      if (this.disposed || this.engine !== engine || ab === undefined) {
        return;
      }
      engine.applyPersistedAb(ab);
      this.syncAb();
    }).catch((): void => {
      // load 内部已折 undefined，这里只兜 Promise 链意外
    });
  }

  /** ABP：打点/循环/清除即写（清除也写：空值落盘，下次进页不再水化旧段） */
  private persistAb(): void {
    const clip: VideoClip | undefined = this.videoClip;
    const ctx: common.Context | undefined = this.ctx;
    if (clip === undefined || ctx === undefined) {
      return;
    }
    AbPersistStore.save(ctx, clip.path, this.engine.ab);
  }

  private syncAb(): void {
    this.abA = this.engine.ab.aMs;
    this.abB = this.engine.ab.bMs;
    this.abLoop = this.engine.ab.loopEnabled;
  }

  /**
   * 读该片的同步快照（prefs 五键，key = 成片路径）。
   * 对齐上游 PlayerScreen 的 `TrackSync.cachedSync(app, clip.uri)`：读不到 = 未同步，
   * 不猜、不补 0。停止后自动校准完成时上游会重读；本侧该校准尚未落地（T27-4），
   * 因此只在进页面时读一次，届时补上 PostStopSync.running 的观察点即可。
   */
  private loadSyncSnapshot(clip: VideoClip): void {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    SyncPrefsStore.read(ctx, clip.path).then((snap: SyncSnapshot | undefined): void => {
      if (this.disposed) {
        return;
      }
      this.syncKnown = snap !== undefined;
      this.syncOffsetMs = snap === undefined ? 0 : snap.offsetMs;
      this.syncSource = snap === undefined ? undefined : snap.source;
    }).catch((e: Object): void => {
      if (this.disposed) {
        return;
      }
      this.syncKnown = false;
      this.syncOffsetMs = 0;
      this.syncSource = undefined;
    });
  }

  /** 偏移来源文案：AUTO = 自动校准；MANUAL / 无来源（老记录，上游同口径）= 手动 */
  private syncSourceLabel(): string {
    return this.syncSource === Source.AUTO ? '自动校准' : '手动';
  }

  /** 「已同步 +12ms（自动校准）」/「未同步（导出按 0 平移）」 */
  private syncReadoutText(): string {
    if (!this.syncKnown) {
      return '未同步：导出按 0 平移';
    }
    return `已同步 ${TrackSync.signedMs(this.syncOffsetMs)}ms（${this.syncSourceLabel()}）`;
  }

  /** 本次导出要用的音轨平移（µs）：起跑那一刻取快照，导出期间读数变化不影响本轮 */
  private exportAudioShiftUs(): number {
    const offsetMs: number = this.syncKnown ? this.syncOffsetMs : 0;
    return audioShiftUs(this.exportTrack, offsetMs);
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
    // 打开面板时重读一次同步快照（上游 LaunchedEffect(clip.uri) 的口径）：读数不因退出页面过期
    this.loadSyncSnapshot(clip);
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
    // 平移在起跑那一刻定版（与上游同口径）：导出期间同步读数被改写不影响本轮
    const shiftUs: number = this.exportAudioShiftUs();
    this.exportBusy = true;
    this.exportProgress = 0;
    this.exportError = '';
    this.exportNote = '';
    this.exportDonePath = '';
    this.exportDoneSize = 0;
    ClipExporter.exportClip(ctx, clip, range, audioOrdinalOf(this.exportTrack), shiftUs,
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

  // ---------------------------------------------------------------- T30 修复光弧面板

  private openRepairPanel(): void {
    const clip: VideoClip | undefined = this.videoClip;
    const ctx: common.Context | undefined = this.ctx;
    if (clip === undefined || ctx === undefined) {
      this.hint = '片源未就绪';
      return;
    }
    if (this.exportBusy) {
      this.hint = '导出中，稍后再试';
      return;
    }
    if (this.engine.playing) {
      this.engine.playPause(); // 修复期间保持暂停（CPU 修复会长时间占用 fd）
    }
    this.repairPanelVisible = true;
    this.repairBusy = false;
    this.repairProbing = true;
    this.repairProbeBlocked = false;
    this.repairProgress = 0;
    this.repairError = '';
    this.repairNote = '';
    this.repairDonePath = '';
    this.repairDoneSize = 0;
    this.repairSrcFps = 0;
    this.repairSrcFrames = 0;
    this.repairAudioCount = 0;
    this.repairAudioSamples = 0;
    this.repairProbed = undefined;
    const probeSeq: number = ++this.repairProbeSeq;
    ArcRepairRunner.probe(ctx, clip).then((probed: ArcRepairProbeResult): void => {
      if (this.disposed || probeSeq !== this.repairProbeSeq) {
        return;
      }
      this.repairProbing = false;
      if (!probed.ok) {
        this.repairProbeBlocked = true;
        this.repairError = `探测失败：${ArcRepairSessionModel.describeRepairError(probed.error)}`;
        return;
      }
      this.repairProbed = probed;
      this.repairSrcFps = probed.srcFps;
      this.repairSrcFrames = probed.srcFrames;
      this.repairAudioCount = probed.audioCount;
      // 守卫只看源侧事实（T30 审查 P1：dstFrames 是「按档计划」的派生值，不能当探测成功判据）
      if (probed.srcFrames <= 0) {
        this.repairProbeBlocked = true;
        this.repairError = ArcRepairSessionModel.describeRepairError('ARC_SOURCE');
        return;
      }
      const rate: MeasuredSrcFps = ArcRateProbe.measuredOf(probed.srcNum, probed.srcDen);
      if (!ArcRepairSessionModel.targetFeasible(rate, 24) &&
        !ArcRepairSessionModel.targetFeasible(rate, 25)) {
        // 两档都无需修复（源不高于 25fps）：整面板拦（不是故障）
        this.repairProbeBlocked = true;
        this.repairError = ArcRepairSessionModel.describeRepairError('ARC_NO_NEED');
        return;
      }
      // 默认档不可行时自动落到可行档，避免一进来就踩 NO_NEED
      if (!ArcRepairSessionModel.targetFeasible(rate, this.repairDstFps)) {
        this.repairDstFps = ArcRepairSessionModel.targetFeasible(rate, 24) ? 24 : 25;
      }
      this.refreshRepairNote();
    }).catch((e: Object): void => {
      if (this.disposed || probeSeq !== this.repairProbeSeq) {
        return;
      }
      this.repairProbing = false;
      this.repairProbeBlocked = true;
      this.repairError = `探测失败：${e}`;
    });
  }

  /** 面板当前目标档/死区对应的估算输出帧数（0 = 源无帧） */
  private repairDstFrames(): number {
    const probed: ArcRepairProbeResult | undefined = this.repairProbed;
    if (probed === undefined || probed.srcFrames <= 0) {
      return 0;
    }
    return ArcRepairSessionModel.planForTarget(probed.srcFrames,
      ArcRateProbe.measuredOf(probed.srcNum, probed.srcDen), this.repairDstFps).dstFrames;
  }

  /** 目标档是否可行（源帧率 > 目标）：false 的档按钮禁用 */
  private repairTargetFeasible(dstFps: number): boolean {
    const probed: ArcRepairProbeResult | undefined = this.repairProbed;
    if (probed === undefined) {
      return true;
    }
    return ArcRepairSessionModel.targetFeasible(
      ArcRateProbe.measuredOf(probed.srcNum, probed.srcDen), dstFps);
  }

  /** 按**当前所选**目标档/死区刷新预览说明（T30 审查 P2：说明必须随选项变化） */
  private refreshRepairNote(): void {
    const probed: ArcRepairProbeResult | undefined = this.repairProbed;
    if (probed === undefined) {
      this.repairNote = '';
      return;
    }
    this.repairNote = ArcRepairSessionModel.repairNote(probed.srcFps, probed.srcNum, probed.srcDen,
      probed.containerFps, this.repairDstFps, this.repairDeadband);
  }

  /** 切目标档：不可行档拒绝选择并提示 */
  private pickRepairDstFps(dstFps: number): void {
    if (!this.repairTargetFeasible(dstFps)) {
      this.hint = `源 ${this.repairSrcFps}fps 不高于目标 ${dstFps}fps，该档无需修复`;
      return;
    }
    this.repairDstFps = dstFps;
    this.refreshRepairNote();
  }

  /** 切死区：预览说明随档刷新 */
  private pickRepairDeadband(deadband: number): void {
    this.repairDeadband = deadband;
    this.refreshRepairNote();
  }

  private closeRepairPanel(): void {
    if (this.repairBusy) {
      return;
    }
    this.repairProbeSeq++;
    this.repairPanelVisible = false;
    this.repairProbed = undefined;
  }

  private startRepair(): void {
    const clip: VideoClip | undefined = this.videoClip;
    const ctx: common.Context | undefined = this.ctx;
    const probed: ArcRepairProbeResult | undefined = this.repairProbed;
    if (this.repairBusy || this.repairProbing || this.repairProbeBlocked || clip === undefined ||
      ctx === undefined || probed === undefined) {
      return;
    }
    if (this.exportBusy) {
      this.hint = '导出中，稍后再试';
      return;
    }
    this.repairBusy = true;
    this.repairProgress = 0;
    this.repairError = '';
    this.repairDonePath = '';
    this.repairDoneSize = 0;
    this.repairAudioSamples = 0;
    const dstFps: number = this.repairDstFps;
    // 光弧修复也应用同步残差（上游 arcRunner.start(..., capOffsetMs = syncedOffsetMs ?: 0)）：
    // 取缓存的偏移（未同步 = 0），native 只在双音轨的第 2 条 lane 上平移
    const capOffsetMs: number = this.syncKnown ? Math.trunc(this.syncOffsetMs) : 0;
    ArcRepairRunner.run(ctx, clip, dstFps, this.repairDeadband, capOffsetMs,
      (percent: number): void => {
      if (!this.disposed) {
        this.repairProgress = percent;
      }
    }, probed).then((result: ArcRepairResult): void => {
      if (this.disposed) {
        return;
      }
      this.repairBusy = false;
      if (result.ok) {
        this.repairDonePath = result.path;
        this.repairDoneSize = result.bytes;
        this.repairAudioSamples = result.audioSamples;
        this.repairNote = result.note;
      } else {
        this.repairNote = '';
        this.repairError = ArcRepairSessionModel.describeRepairError(result.error);
      }
    }).catch((e: Object): void => {
      if (this.disposed) {
        return;
      }
      this.repairBusy = false;
      this.repairNote = '';
      this.repairError = `修复失败：${e}`;
    });
  }

  private cancelRepair(): void {
    if (!this.repairBusy) {
      return;
    }
    ArcRepairRunner.cancel();
    this.repairNote = '正在取消…';
  }

  private openRepairGallery(): void {
    if (this.repairBusy) {
      return;
    }
    this.repairPanelVisible = false;
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
      Button(`旋转:${this.quarterTurns * 90}°`)
        .fontSize(11)
        .onClick((): void => {
          this.quarterTurns = nextCcwQuarter(this.quarterTurns);
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
            .enabled(!this.repairBusy)
            .onClick((): void => {
              this.openExportPanel();
            })
        }
        Button('修复光弧')
          .fontSize(11)
          .enabled(!this.exportBusy && !this.repairBusy)
          .onClick((): void => {
            this.openRepairPanel();
          })
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
  private repairPanel() {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Text('修复光弧')
          .fontSize(14)
          .fontColor(Color.White)
          .layoutWeight(1)
        Button('关闭')
          .fontSize(11)
          .enabled(!this.repairBusy)
          .onClick((): void => {
            this.closeRepairPanel();
          })
      }
      .width('100%')

      if (this.repairProbing) {
        Row({ space: 8 }) {
          LoadingProgress()
            .width(22)
            .height(22)
          Text('探测源视频…')
            .fontSize(11)
            .fontColor('#AAAAAA')
        }
        .width('100%')
      } else if (this.repairProbeBlocked) {
        Text(this.repairError)
          .fontSize(11)
          .fontColor('#FF8A80')
          .maxLines(3)
          .width('100%')
      } else {
        Text(`源实测≈${this.repairSrcFps}fps · ${this.repairSrcFrames} 源帧 → ` +
          `目标 ${this.repairDstFps}fps 输出 ${this.repairDstFrames()} 帧 · ` +
          `音轨 ${this.repairAudioCount} 条`)
          .fontSize(10)
          .fontColor('#B0BEC5')
          .width('100%')

        Text('目标帧率')
          .fontSize(10)
          .fontColor('#90A4AE')
          .width('100%')
        Row({ space: 6 }) {
          Button('24fps')
            .fontSize(11)
            .backgroundColor(this.repairDstFps === 24 ? '#FF3D71' : '#333333')
            .enabled(!this.repairBusy && this.repairTargetFeasible(24))
            .onClick((): void => {
              this.pickRepairDstFps(24);
            })
          Button('25fps')
            .fontSize(11)
            .backgroundColor(this.repairDstFps === 25 ? '#FF3D71' : '#333333')
            .enabled(!this.repairBusy && this.repairTargetFeasible(25))
            .onClick((): void => {
              this.pickRepairDstFps(25);
            })
        }
        .width('100%')

        Text('差分死区')
          .fontSize(10)
          .fontColor('#90A4AE')
          .width('100%')
        Row({ space: 6 }) {
          Button('0')
            .fontSize(11)
            .backgroundColor(this.repairDeadband === 0 ? '#FF3D71' : '#333333')
            .enabled(!this.repairBusy)
            .onClick((): void => {
              this.pickRepairDeadband(0);
            })
          Button('12')
            .fontSize(11)
            .backgroundColor(this.repairDeadband === 12 ? '#FF3D71' : '#333333')
            .enabled(!this.repairBusy)
            .onClick((): void => {
              this.pickRepairDeadband(12);
            })
          Button('32')
            .fontSize(11)
            .backgroundColor(this.repairDeadband === 32 ? '#FF3D71' : '#333333')
            .enabled(!this.repairBusy)
            .onClick((): void => {
              this.pickRepairDeadband(32);
            })
        }
        .width('100%')

        if (this.repairBusy) {
          Progress({ value: this.repairProgress, total: 100, type: ProgressType.Linear })
            .width('100%')
          Text(`修复中 ${this.repairProgress}%`)
            .fontSize(11)
            .fontColor('#FFCC80')
            .width('100%')
          if (this.repairNote !== '') {
            Text(this.repairNote)
              .fontSize(10)
              .fontColor('#90A4AE')
              .maxLines(2)
              .width('100%')
          }
          Button('取消修复')
            .fontSize(11)
            .onClick((): void => {
              this.cancelRepair();
            })
        } else if (this.repairDonePath !== '') {
          Text('修复完成')
            .fontSize(12)
            .fontColor('#81C784')
            .width('100%')
          Text(this.repairDonePath)
            .fontSize(10)
            .fontColor(Color.White)
            .maxLines(3)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
          Text(formatSize(this.repairDoneSize))
            .fontSize(10)
            .fontColor('#90A4AE')
            .width('100%')
          if (this.repairNote !== '') {
            Text(this.repairNote)
              .fontSize(10)
              .fontColor('#FFCC80')
              .maxLines(3)
              .width('100%')
          }
          Row({ space: 8 }) {
            Button('打开媒体库')
              .fontSize(11)
              .onClick((): void => {
                this.openRepairGallery();
              })
            Button('完成')
              .fontSize(11)
              .onClick((): void => {
                this.repairPanelVisible = false;
              })
          }
          .width('100%')
        } else {
          Button('开始修复')
            .fontSize(12)
            .onClick((): void => {
              this.startRepair();
            })
          if (this.repairError !== '') {
            Text(this.repairError)
              .fontSize(10)
              .fontColor('#FF8A80')
              .maxLines(3)
              .width('100%')
          }
          if (this.repairNote !== '') {
            Text(this.repairNote)
              .fontSize(10)
              .fontColor('#FFCC80')
              .maxLines(3)
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

      // 同步读数与来源（对齐上游 TrackExportPopup：已同步 +Xms（自动校准/手动）/ 未同步）
      Text(this.syncReadoutText())
        .fontSize(11)
        .fontColor('#B0BEC5')
        .width('100%')
      // 诚实边界：原片分享不重写容器，只有本页导出/光弧修复会应用同步残差
      Text('原片分享不重写容器；只有本页导出/光弧修复会应用同步残差')
        .fontSize(11)
        .fontColor('#78909C')
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
        .rotate({ x: 0, y: 0, z: 1, angle: this.quarterTurns * 90, centerX: '50%', centerY: '50%' })
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

      if (this.repairPanelVisible) {
        Column() {
          this.repairPanel()
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
