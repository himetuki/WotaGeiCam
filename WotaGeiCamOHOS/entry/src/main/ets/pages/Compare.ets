/**
 * 对比播放最小版（双 XComponent + 双 PlayerEngine，手动时间线）。
 *
 * 平台差异 / 本批边界：
 * - 两个预览面都是普通 XComponent(SURFACE)：AVPlayer 每实例独占一个 surface，
 *   不走 native libentry 的单路 GL 预览（那一路只有一个 NativeImage，不能同时喂两面）；
 * - 只做手动偏移：seek/±0.5s/归零 + 120ms 漂移校正（阈值 250ms 硬 seek、冷却 500ms）；
 *   不含上游 DecideCommands/AudioSync 的自动判定与自动对齐；
 * - 声音侧默认左（右侧静音），切换只改两路 AVPlayer 音量，不做混音；
 * - 右侧片源从 MediaRepo 现选，排除左片（id+path 双排除，左片未就绪时用路由参数原始 path）；
 *   两侧独立 attach/retry，页面销毁统一释放。
 *
 * T22 时间线双轴消费（对齐上游 CompareTimeline 几何）：
 * - 进度 Slider min/max = TimelineGeometry.tMin()/tMax()（off>0 时负轴 pre-roll 可拖），
 *   拖动值 t 即时间线坐标，seekBoth 走 leftTarget/rightTarget park 规则；
 * - 左端标签 = tMin()、右端标签 = timelineEnd()；off=0 时 tMin=0/tMax=max(L,R)，
 *   [0,L] 区间与旧单左轴逐像素等价（回归红线，test/pure/compare-timeline.test.ets 钉死）；
 * - timelineSpan 无 UI 消费（进度百分比语义未定），留白登记；
 * - 进度读数 positionMs 仍随左钟（pre-roll 段左 park 在 0，读数显 0，最小版不派生真 T）。
 *
 * T22 其余收口：
 * - 右片 surface 重建/重试恢复播放位置（resumeRightPosMs，复刻左片 resumeLeftPosMs 模式；
 *   换选右片不恢复）；左片既有恢复路径零改动；
 * - 隐藏期（!pageVisible）surface 重建只置 deferred 标志，onPageShow 消费执行重建；
 *   retry 入口与 rebuild then 链均再查 pageVisible（隐藏期不 release/attach，防双开销）；
 * - 漂移"速度微调"以平台限制登记关闭：AVPlayer setSpeed 仅枚举档
 *   （player/PlayerEngine.ets speedToEnum 为证，无任意 ratio 接口），邻域纠偏维持
 *   250ms 硬 seek/500ms 冷却（上游 AudioSync 的 setPlaybackParams 微调无 OHOS 等价物）。
 */
import { common } from '@kit.AbilityKit';
import { ArcDropLog } from '../record/ArcDropLog';
import { ClipFilter } from '../media/MediaTag';
import { MediaRepo, VideoClip } from '../media/MediaRepo';
import { formatDuration, formatSignedDuration } from '../media/MediaFormat';
import { PlayerEngine } from '../player/PlayerEngine';
import { PlayerSpeedTiers } from '../player/PlayerModel';
import { normalizeOffset, rightTargetFor, timelineEnd, TimelineGeometry } from '../player/CompareTimeline';

interface CompareParams {
  leftId: number;
  leftPath?: string;
}

const TICK_MS: number = 120;
const DRIFT_THRESHOLD_MS: number = 250;
const DRIFT_COOLDOWN_MS: number = 500;
const OFFSET_STEP_MS: number = 500;
const DEFAULT_FPS: number = 30;

/** 声音侧：0 左、1 右、2 静音 */
const SOUND_LEFT: number = 0;
const SOUND_RIGHT: number = 1;
const SOUND_MUTE: number = 2;

@Entry
@Component
struct Compare {
  @State leftName: string = '';
  @State rightName: string = '';
  @State leftReady: boolean = false;
  @State rightReady: boolean = false;
  @State leftLoading: boolean = false;
  @State rightLoading: boolean = false;
  @State leftError: string = '';
  @State rightError: string = '';
  @State fatalError: string = '';
  @State hint: string = '';
  @State playing: boolean = false;
  @State positionMs: number = 0;
  @State sliderValue: number = 0;
  @State sliderDragging: boolean = false;
  @State leftDurationMs: number = 0;
  @State rightDurationMs: number = 0;
  @State speed: number = PlayerSpeedTiers.DEFAULT;
  @State offsetMs: number = 0;
  @State soundSide: number = SOUND_LEFT;
  @State selectingRight: boolean = false;
  @State rightListLoading: boolean = false;
  @State rightList: Array<VideoClip> = [];

  private leftEngine: PlayerEngine = new PlayerEngine();
  private rightEngine: PlayerEngine = new PlayerEngine();
  private leftXc: XComponentController = new XComponentController();
  private rightXc: XComponentController = new XComponentController();
  private leftSid: string = '';
  private rightSid: string = '';
  private leftAttachStarted: boolean = false;
  private rightAttachStarted: boolean = false;
  private leftClip: VideoClip | undefined = undefined;
  private rightClip: VideoClip | undefined = undefined;
  private repo: MediaRepo | undefined = undefined;
  private disposed: boolean = false;
  private pageVisible: boolean = true;
  private tickTimer: number = -1;
  private lastCorrectionMs: number = 0;
  private resumeLeftPosMs: number = -1;
  /** 右片重建/重试前的位置快照（-1 = 无待恢复；换选右片时清 -1 不恢复） */
  private resumeRightPosMs: number = -1;
  /** 隐藏期发生的 surface 重建（left/right 各一）：只置标志，onPageShow 消费执行重建 */
  private deferredLeftRebuild: boolean = false;
  private deferredRightRebuild: boolean = false;
  /** 路由参数原始左片 path（leftClip 未就绪时右列表按 path 排除用） */
  private paramsLeftPath: string = '';

  aboutToAppear(): void {
    this.disposed = false;
    this.pageVisible = true;
    const ctx: common.Context | undefined =
      this.getUIContext().getHostContext() as common.Context | undefined;
    if (ctx === undefined) {
      this.fatalError = 'no host context';
      return;
    }
    const raw: Object = this.getUIContext().getRouter().getParams();
    if (raw === undefined) {
      this.fatalError = '缺少左片参数';
      return;
    }
    const params: CompareParams = raw as CompareParams;
    this.paramsLeftPath = params.leftPath ?? '';
    this.repo = new MediaRepo(ctx);
    MediaRepo.resetProbeCapHit();
    this.startTick();
    this.repo.findById(params.leftId, params.leftPath ?? '').then((clip: VideoClip | undefined): void => {
      if (this.disposed) {
        return;
      }
      if (clip === undefined) {
        this.fatalError = '左片已不存在';
        return;
      }
      this.leftClip = clip;
      this.leftName = clip.name;
      this.leftDurationMs = Math.max(0, clip.durationMs);
      this.tryAttachLeft();
    }).catch((e: Object): void => {
      if (this.disposed) {
        return;
      }
      this.fatalError = `读取左片失败: ${e}`;
    });
  }

  onPageShow(): void {
    this.pageVisible = true;
    if (this.disposed) {
      return;
    }
    this.startTick();
    this.consumeDeferredRebuilds();
    if (this.leftReady) {
      this.leftEngine.resumeTick();
    }
    if (this.rightReady) {
      this.rightEngine.resumeTick();
    }
  }

  /** 隐藏期发生的 surface 重建在此消费：页面可见后才走 release/换引擎/attach（防隐藏期双开销） */
  private consumeDeferredRebuilds(): void {
    if (this.deferredLeftRebuild) {
      this.deferredLeftRebuild = false;
      this.resumeLeftPosMs = Math.max(0, this.positionMs);
      this.leftReady = false;
      this.leftLoading = true;
      this.leftAttachStarted = false;
      this.leftError = '';
      this.rebuildLeftEngine();
    }
    if (this.deferredRightRebuild) {
      this.deferredRightRebuild = false;
      this.snapshotRightForResume();
      this.rightReady = false;
      this.rightLoading = true;
      this.rightAttachStarted = false;
      this.rightError = '';
      this.rebuildRightEngine();
    }
  }

  onPageHide(): void {
    this.pageVisible = false;
    this.stopTick();
    this.pauseAll();
    this.leftEngine.suspendTick();
    this.rightEngine.suspendTick();
    this.sliderDragging = false;
    this.leftEngine.setSeeking(false);
  }

  aboutToDisappear(): void {
    this.disposed = true;
    this.pageVisible = false;
    this.stopTick();
    this.leftEngine.release();
    this.rightEngine.release();
  }

  private startTick(): void {
    if (this.tickTimer >= 0 || this.disposed) {
      return;
    }
    this.tickTimer = setInterval((): void => {
      this.onTick();
    }, TICK_MS);
  }

  private stopTick(): void {
    if (this.tickTimer >= 0) {
      clearInterval(this.tickTimer);
      this.tickTimer = -1;
    }
  }

  private onTick(): void {
    if (this.disposed) {
      return;
    }
    const left: PlayerEngine = this.leftEngine;
    const right: PlayerEngine = this.rightEngine;
    if (this.leftReady) {
      if (left.error !== '' && this.leftError !== left.error) {
        this.leftError = left.error;
      }
      if (left.durationMs > 0 && left.durationMs !== this.leftDurationMs) {
        this.leftDurationMs = left.durationMs;
      }
      if (!this.sliderDragging) {
        const pos: number = Math.round(left.positionMs);
        if (pos !== this.positionMs) {
          this.positionMs = pos;
          this.sliderValue = pos;
        }
      }
    }
    if (this.rightReady) {
      if (right.error !== '' && this.rightError !== right.error) {
        this.rightError = right.error;
      }
      if (right.durationMs > 0 && right.durationMs !== this.rightDurationMs) {
        this.rightDurationMs = right.durationMs;
      }
    }
    const active: boolean = (this.leftReady && left.playing) ||
      (this.rightReady && right.playing);
    if (active !== this.playing) {
      this.playing = active;
    }
    // 漂移校正 + park 门控（offset<0 时右片在时间线前段无画面：停在首帧，等左片进入覆盖区再起播；
    // offset>0 时右片先结束则停在末帧）。左片暂停时不做任何自动起播。
    if (this.leftReady && this.rightReady && !this.sliderDragging &&
      left.playing && this.leftError === '' && this.rightError === '') {
      const geo: TimelineGeometry = new TimelineGeometry(this.leftDurationMs, this.rightDurationMs, this.offsetMs);
      const target: number = geo.rightTarget(left.positionMs);
      const now: number = Date.now();
      if (!geo.rightCovers(left.positionMs)) {
        if (right.playing) {
          right.playPause();
        }
        if (Math.abs(right.positionMs - target) > DRIFT_THRESHOLD_MS &&
          now - this.lastCorrectionMs >= DRIFT_COOLDOWN_MS) {
          right.seekTo(target);
          this.lastCorrectionMs = now;
        }
      } else if (!right.playing) {
        if (target < this.rightDurationMs - 1) {
          this.startRightAt(target);
          this.lastCorrectionMs = now;
        }
      } else if (Math.abs(right.positionMs - target) > DRIFT_THRESHOLD_MS &&
        now - this.lastCorrectionMs >= DRIFT_COOLDOWN_MS) {
        right.seekTo(target);
        this.lastCorrectionMs = now;
      }
    }
  }

  private fpsHint(clip: VideoClip): number {
    const drops: ArcDropLog | null = ArcDropLog.readFrom(clip.path);
    return drops !== null && drops.dstFps > 0 ? drops.dstFps : DEFAULT_FPS;
  }

  private tryAttachLeft(): void {
    const clip: VideoClip | undefined = this.leftClip;
    if (this.disposed || this.leftAttachStarted || clip === undefined || this.leftSid === '') {
      return;
    }
    this.leftAttachStarted = true;
    this.leftLoading = true;
    this.leftError = '';
    const engine: PlayerEngine = this.leftEngine;
    engine.attach(this.leftSid, clip, this.fpsHint(clip)).then((error: string): void => {
      if (this.disposed || this.leftEngine !== engine) {
        engine.release();
        return;
      }
      this.leftLoading = false;
      if (error === 'cancelled') {
        this.leftAttachStarted = false;
        return;
      }
      if (error !== '') {
        this.leftReady = false;
        this.leftAttachStarted = false;
        this.leftError = error;
        return;
      }
      this.leftReady = true;
      this.leftError = '';
      if (engine.durationMs > 0) {
        this.leftDurationMs = engine.durationMs;
      }
      this.normalizeOffsetNow();
      engine.setSpeed(this.speed);
      if (this.pageVisible) {
        engine.resumeTick();
      } else {
        engine.suspendTick();
      }
      this.applySound();
      if (this.resumeLeftPosMs >= 0) {
        engine.seekTo(this.resumeLeftPosMs);
        this.positionMs = this.resumeLeftPosMs;
        this.sliderValue = this.resumeLeftPosMs;
        this.resumeLeftPosMs = -1;
      }
    }).catch((e: Object): void => {
      if (this.disposed || this.leftEngine !== engine) {
        engine.release();
        return;
      }
      this.leftLoading = false;
      this.leftReady = false;
      this.leftAttachStarted = false;
      this.leftError = `播放器启动失败: ${e}`;
    });
  }

  private tryAttachRight(): void {
    const clip: VideoClip | undefined = this.rightClip;
    if (this.disposed || this.rightAttachStarted || clip === undefined || this.rightSid === '') {
      return;
    }
    this.rightAttachStarted = true;
    this.rightLoading = true;
    this.rightError = '';
    const engine: PlayerEngine = this.rightEngine;
    engine.attach(this.rightSid, clip, this.fpsHint(clip)).then((error: string): void => {
      if (this.disposed || this.rightEngine !== engine) {
        engine.release();
        return;
      }
      this.rightLoading = false;
      if (error === 'cancelled') {
        this.rightAttachStarted = false;
        return;
      }
      if (error !== '') {
        this.rightReady = false;
        this.rightAttachStarted = false;
        this.rightError = error;
        return;
      }
      this.rightReady = true;
      this.rightError = '';
      if (engine.durationMs > 0) {
        this.rightDurationMs = engine.durationMs;
      }
      this.normalizeOffsetNow();
      engine.setSpeed(this.speed);
      if (this.pageVisible) {
        engine.resumeTick();
      } else {
        engine.suspendTick();
      }
      this.applySound();
      if (this.resumeRightPosMs >= 0) {
        // 右片重建/重试恢复位置（T22，复刻左片 resumeLeftPosMs 模式）
        engine.seekTo(this.resumeRightPosMs);
        this.resumeRightPosMs = -1;
      }
    }).catch((e: Object): void => {
      if (this.disposed || this.rightEngine !== engine) {
        engine.release();
        return;
      }
      this.rightLoading = false;
      this.rightReady = false;
      this.rightAttachStarted = false;
      this.rightError = `播放器启动失败: ${e}`;
    });
  }

  /** 右片重建/重试前的位置快照：就绪取引擎实位，否则按时间线换算（rightTargetFor 夹 0..R） */
  private snapshotRightForResume(): void {
    this.resumeRightPosMs = this.rightReady
      ? Math.max(0, Math.round(this.rightEngine.positionMs))
      : rightTargetFor(Math.max(0, this.positionMs), this.offsetMs, this.rightDurationMs);
  }

  private onSurfaceLoaded(isLeft: boolean): void {
    if (this.disposed) {
      return;
    }
    const sid: string = isLeft ? this.leftXc.getXComponentSurfaceId() : this.rightXc.getXComponentSurfaceId();
    if (sid === '') {
      this.hint = isLeft ? '左侧 surface 未就绪' : '右侧 surface 未就绪';
      return;
    }
    const oldSid: string = isLeft ? this.leftSid : this.rightSid;
    if (oldSid === sid) {
      if (isLeft) {
        this.tryAttachLeft();
      } else {
        this.tryAttachRight();
      }
      return;
    }
    if (isLeft) {
      this.leftSid = sid;
      if (oldSid !== '') {
        if (!this.pageVisible) {
          // 隐藏期 surface 重建：只置 deferred 标志（release/换引擎/attach 回页再做）
          this.deferredLeftRebuild = true;
          return;
        }
        // surface 重建：旧 AVPlayer 已绑在失效面上，release 后换新引擎重挂
        this.resumeLeftPosMs = Math.max(0, this.positionMs);
        this.leftReady = false;
        this.leftLoading = true;
        this.leftAttachStarted = false;
        this.leftError = '';
        this.rebuildLeftEngine();
      } else {
        this.tryAttachLeft();
      }
    } else {
      this.rightSid = sid;
      if (oldSid !== '') {
        if (!this.pageVisible) {
          this.deferredRightRebuild = true;
          return;
        }
        this.snapshotRightForResume();
        this.rightReady = false;
        this.rightLoading = true;
        this.rightAttachStarted = false;
        this.rightError = '';
        this.rebuildRightEngine();
      } else {
        this.tryAttachRight();
      }
    }
  }

  private rebuildLeftEngine(): void {
    const old: PlayerEngine = this.leftEngine;
    old.release().then((): void => {
      if (this.disposed) {
        return;
      }
      this.leftEngine = new PlayerEngine();
      this.leftAttachStarted = false;
      if (!this.pageVisible) {
        // 释放完成时页面已隐藏：attach 等回页（onPageShow 消费 deferred）
        this.deferredLeftRebuild = true;
        return;
      }
      this.tryAttachLeft();
    }).catch((e: Object): void => {
      if (this.disposed) {
        return;
      }
      this.leftLoading = false;
      this.leftError = `重启左侧失败: ${e}`;
    });
  }

  private rebuildRightEngine(): void {
    const old: PlayerEngine = this.rightEngine;
    old.release().then((): void => {
      if (this.disposed) {
        return;
      }
      this.rightEngine = new PlayerEngine();
      this.rightAttachStarted = false;
      if (!this.pageVisible) {
        this.deferredRightRebuild = true;
        return;
      }
      this.tryAttachRight();
    }).catch((e: Object): void => {
      if (this.disposed) {
        return;
      }
      this.rightLoading = false;
      this.rightError = `重启右侧失败: ${e}`;
    });
  }

  private retryLeft(): void {
    if (this.disposed || !this.pageVisible || this.leftLoading || this.leftClip === undefined) {
      return;
    }
    this.pauseAll();
    this.resumeLeftPosMs = Math.max(0, this.positionMs);
    this.leftReady = false;
    this.leftLoading = true;
    this.leftAttachStarted = false;
    this.leftError = '';
    this.rebuildLeftEngine();
  }

  private retryRight(): void {
    if (this.disposed || !this.pageVisible || this.rightLoading || this.rightClip === undefined) {
      return;
    }
    this.pauseAll();
    this.snapshotRightForResume();
    this.rightReady = false;
    this.rightLoading = true;
    this.rightAttachStarted = false;
    this.rightError = '';
    this.rebuildRightEngine();
  }

  private openRightPicker(): void {
    this.pauseAll();
    this.selectingRight = true;
    this.loadRightList();
  }

  private loadRightList(): void {
    const repo: MediaRepo | undefined = this.repo;
    if (repo === undefined) {
      return;
    }
    this.rightListLoading = true;
    this.rightList = [];
    repo.load(ClipFilter.all()).then((list: Array<VideoClip>): void => {
      if (this.disposed) {
        return;
      }
      // T22：id+path 双排除左片（ID=FNV-1a 有碰撞面，path 补刀）；leftClip 未就绪时
      // 用路由参数原始 path（paramsLeftPath）兜底，避免选到自己正在看的那条
      const left: VideoClip | undefined = this.leftClip;
      const leftPath: string = left !== undefined ? left.path : this.paramsLeftPath;
      this.rightList = list.filter((clip: VideoClip): boolean =>
        (left === undefined || clip.id !== left.id) && (leftPath === '' || clip.path !== leftPath));
      this.rightListLoading = false;
    }).catch((e: Object): void => {
      if (this.disposed) {
        return;
      }
      this.rightListLoading = false;
      this.hint = `读取媒体库失败: ${e}`;
    });
  }

  private chooseRight(clip: VideoClip): void {
    const left: VideoClip | undefined = this.leftClip;
    if (this.disposed || (left !== undefined && clip.id === left.id)) {
      return;
    }
    this.selectingRight = false;
    this.pauseAll();
    this.rightClip = clip;
    this.rightName = clip.name;
    this.rightDurationMs = Math.max(0, clip.durationMs);
    this.rightReady = false;
    this.rightLoading = true;
    this.rightAttachStarted = false;
    this.rightError = '';
    this.resumeRightPosMs = -1; // 换选右片是全新片源：不恢复旧片位置
    this.normalizeOffsetNow();
    this.rebuildRightEngine();
  }

  private normalizeOffsetNow(): void {
    this.offsetMs = normalizeOffset(this.offsetMs, this.leftDurationMs, this.rightDurationMs);
  }

  /** 当前时间线几何（双轴进度条的 min/max/标签统一取此快照） */
  private timelineGeo(): TimelineGeometry {
    return new TimelineGeometry(this.leftDurationMs, this.rightDurationMs, this.offsetMs);
  }

  /** 时间线左端（off>0 时为负轴 pre-roll） */
  private timelineTMin(): number {
    return this.timelineGeo().tMin();
  }

  /** 时间线右端（max(L, R−off)；双片未就绪时至少保 1ms 槽宽防 Slider min===max） */
  private timelineTMax(): number {
    return Math.max(this.timelineGeo().tMax(), this.timelineTMin() + 1);
  }

  private applySound(): void {
    if (this.leftReady) {
      this.leftEngine.setVolume(this.soundSide === SOUND_LEFT ? 1 : 0);
    }
    if (this.rightReady) {
      this.rightEngine.setVolume(this.soundSide === SOUND_RIGHT ? 1 : 0);
    }
  }

  private cycleSound(): void {
    this.soundSide = (this.soundSide + 1) % 3;
    this.applySound();
    this.hint = `声音：${this.soundLabel()}`;
  }

  private soundLabel(): string {
    if (this.soundSide === SOUND_LEFT) {
      return '左';
    }
    if (this.soundSide === SOUND_RIGHT) {
      return '右';
    }
    return '静音';
  }

  private pauseAll(): void {
    if (this.leftReady && this.leftEngine.playing) {
      this.leftEngine.playPause();
    }
    if (this.rightReady && this.rightEngine.playing) {
      this.rightEngine.playPause();
    }
    this.playing = false;
  }

  /** 右侧从 target 起播；completed 的 playPause 会先回 0，故先起播再 seek 到时间线目标 */
  private startRightAt(target: number): void {
    const right: PlayerEngine = this.rightEngine;
    if (this.disposed || !this.rightReady) {
      return;
    }
    if (target >= this.rightDurationMs - 1) {
      right.seekTo(this.rightDurationMs);
      return;
    }
    if (right.state === 'completed') {
      right.playPause();
      right.seekTo(target);
      return;
    }
    right.seekTo(target);
    if (!right.playing) {
      right.playPause();
    }
  }

  private togglePlay(): void {
    if (!this.leftReady) {
      this.hint = '左片尚未就绪';
      return;
    }
    const left: PlayerEngine = this.leftEngine;
    const right: PlayerEngine = this.rightEngine;
    const active: boolean = left.playing || (this.rightReady && right.playing);
    if (active) {
      this.pauseAll();
      return;
    }
    let start: number = Math.round(left.positionMs);
    if (this.leftDurationMs > 0 && start >= this.leftDurationMs - 100) {
      left.seekTo(0);
      start = 0;
      this.positionMs = 0;
      this.sliderValue = 0;
    }
    left.playPause();
    if (this.rightReady) {
      const geo: TimelineGeometry = new TimelineGeometry(this.leftDurationMs, this.rightDurationMs, this.offsetMs);
      if (geo.rightCovers(start)) {
        this.startRightAt(geo.rightTarget(start));
      } else {
        // 右片在起点无覆盖：停在 park 位，进入覆盖区由 tick 自动起播
        right.seekTo(geo.rightTarget(start));
      }
    }
    this.lastCorrectionMs = Date.now();
    this.playing = true;
  }

  /**
   * 双轴时间线落位（T22）：ms 为时间线坐标（左端 tMin、右端 tMax）——
   * 左走 leftTarget park 规则（pre-roll 停 0、越尾停 durMs），右走 rightTarget
   * （pre-roll 落 t+off、越尾停 durMs）。off=0 时在旧单左轴域 [0, min(L,max)] 逐像素等价（回归红线）。
   */
  private seekBoth(ms: number): void {
    const geo: TimelineGeometry = this.timelineGeo();
    const t: number = Math.min(geo.tMax(), Math.max(geo.tMin(), ms));
    this.positionMs = Math.round(t);
    this.sliderValue = this.positionMs;
    if (this.leftReady) {
      this.leftEngine.seekTo(geo.leftTarget(t));
    }
    if (this.rightReady) {
      this.rightEngine.seekTo(geo.rightTarget(t));
    }
    this.lastCorrectionMs = Date.now();
  }

  private cycleSpeed(): void {
    this.speed = PlayerSpeedTiers.next(this.speed);
    if (this.leftReady) {
      this.leftEngine.setSpeed(this.speed);
    }
    if (this.rightReady) {
      this.rightEngine.setSpeed(this.speed);
    }
    this.hint = `倍速 ${PlayerSpeedTiers.label(this.speed)}`;
  }

  private adjustOffset(deltaMs: number): void {
    if (this.rightClip === undefined) {
      this.hint = '请先选择右侧片源';
      return;
    }
    const next: number = normalizeOffset(this.offsetMs + deltaMs, this.leftDurationMs, this.rightDurationMs);
    if (next === this.offsetMs) {
      this.hint = '已到偏移边界';
      return;
    }
    this.offsetMs = next;
    if (this.rightReady) {
      this.rightEngine.seekTo(rightTargetFor(this.positionMs, this.offsetMs, this.rightDurationMs));
    }
    this.lastCorrectionMs = Date.now();
    this.hint = `偏移 ${this.offsetText()}`;
  }

  private resetOffset(): void {
    if (this.rightClip === undefined) {
      this.hint = '请先选择右侧片源';
      return;
    }
    this.offsetMs = 0;
    if (this.rightReady) {
      this.rightEngine.seekTo(rightTargetFor(this.positionMs, 0, this.rightDurationMs));
    }
    this.lastCorrectionMs = Date.now();
    this.hint = '偏移已归零';
  }

  private offsetText(): string {
    return `${this.offsetMs >= 0 ? '+' : ''}${this.offsetMs}ms`;
  }

  private canRetryLeft(): boolean {
    return this.leftError.indexOf('attach-failed') === 0 ||
      this.leftError.indexOf('播放器启动失败') === 0 ||
      this.leftError.indexOf('engine-error') === 0 ||
      this.leftError.indexOf('重启左侧失败') === 0;
  }

  private canRetryRight(): boolean {
    return this.rightError.indexOf('attach-failed') === 0 ||
      this.rightError.indexOf('播放器启动失败') === 0 ||
      this.rightError.indexOf('engine-error') === 0 ||
      this.rightError.indexOf('重启右侧失败') === 0;
  }

  @Builder
  private leftPane() {
    Column({ space: 2 }) {
      Text(this.leftName === '' ? '左' : `左 · ${this.leftName}`)
        .fontSize(11)
        .fontColor('#B0BEC5')
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
      Stack() {
        XComponent({ type: XComponentType.SURFACE, controller: this.leftXc })
          .onLoad((): void => {
            this.onSurfaceLoaded(true);
          })
          .width('100%')
          .height('100%')
        if (this.leftError !== '') {
          Column({ space: 6 }) {
            Text(this.leftError)
              .fontSize(10)
              .fontColor('#FF8A80')
              .maxLines(3)
              .textAlign(TextAlign.Center)
            if (this.canRetryLeft()) {
              Button('重试左侧')
                .fontSize(11)
                .onClick((): void => {
                  this.retryLeft();
                })
            }
          }
          .width('100%')
          .height('100%')
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#AA000000')
        } else if (!this.leftReady) {
          Column({ space: 6 }) {
            LoadingProgress()
              .width(28)
              .height(28)
            Text(this.leftClip === undefined ? '读取左片…' : '左片准备中…')
              .fontSize(10)
              .fontColor('#AAAAAA')
          }
          .width('100%')
          .height('100%')
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#AA000000')
        }
      }
      .layoutWeight(1)
      .width('100%')
      Text(formatDuration(this.leftDurationMs))
        .fontSize(9)
        .fontColor('#78909C')
    }
    .layoutWeight(1)
    .height('100%')
    .padding(2)
  }

  @Builder
  private rightPane() {
    Column({ space: 2 }) {
      Text(this.rightName === '' ? '右' : `右 · ${this.rightName}`)
        .fontSize(11)
        .fontColor('#B0BEC5')
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
      Stack() {
        XComponent({ type: XComponentType.SURFACE, controller: this.rightXc })
          .onLoad((): void => {
            this.onSurfaceLoaded(false);
          })
          .width('100%')
          .height('100%')
        if (this.rightClip === undefined) {
          Column({ space: 6 }) {
            Text('未选择右侧片源')
              .fontSize(10)
              .fontColor('#AAAAAA')
            Button('选择右侧片源')
              .fontSize(11)
              .onClick((): void => {
                this.openRightPicker();
              })
          }
          .width('100%')
          .height('100%')
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#AA000000')
        } else if (this.rightError !== '') {
          Column({ space: 6 }) {
            Text(this.rightError)
              .fontSize(10)
              .fontColor('#FF8A80')
              .maxLines(3)
              .textAlign(TextAlign.Center)
            if (this.canRetryRight()) {
              Button('重试右侧')
                .fontSize(11)
                .onClick((): void => {
                  this.retryRight();
                })
            }
          }
          .width('100%')
          .height('100%')
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#AA000000')
        } else if (!this.rightReady) {
          Column({ space: 6 }) {
            LoadingProgress()
              .width(28)
              .height(28)
            Text('右片准备中…')
              .fontSize(10)
              .fontColor('#AAAAAA')
          }
          .width('100%')
          .height('100%')
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#AA000000')
        }
      }
      .layoutWeight(1)
      .width('100%')
      Text(formatDuration(this.rightDurationMs))
        .fontSize(9)
        .fontColor('#78909C')
    }
    .layoutWeight(1)
    .height('100%')
    .padding(2)
  }

  @Builder
  private pickerOverlay() {
    Column() {
      Blank()
      Column({ space: 8 }) {
        Row({ space: 8 }) {
          Text('选择右侧片源')
            .fontSize(14)
            .fontColor(Color.White)
            .layoutWeight(1)
          Button('关闭')
            .fontSize(11)
            .onClick((): void => {
              this.selectingRight = false;
            })
        }
        .width('100%')
        if (this.rightListLoading) {
          Column({ space: 6 }) {
            LoadingProgress()
              .width(28)
              .height(28)
            Text('读取媒体库…')
              .fontSize(10)
              .fontColor('#AAAAAA')
          }
          .layoutWeight(1)
          .width('100%')
          .justifyContent(FlexAlign.Center)
        } else if (this.rightList.length === 0) {
          Column({ space: 8 }) {
            Text('库中没有可用的其他视频')
              .fontSize(11)
              .fontColor('#AAAAAA')
            Button('返回')
              .fontSize(11)
              .onClick((): void => {
                this.selectingRight = false;
              })
          }
          .layoutWeight(1)
          .width('100%')
          .justifyContent(FlexAlign.Center)
        } else {
          List({ space: 4 }) {
            ForEach(this.rightList, (clip: VideoClip): void => {
              ListItem() {
                Row({ space: 8 }) {
                  Text(clip.name)
                    .fontSize(11)
                    .fontColor(Color.White)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .layoutWeight(1)
                  Text(formatDuration(clip.durationMs))
                    .fontSize(10)
                    .fontColor('#90A4AE')
                }
                .width('100%')
                .padding(10)
                .backgroundColor('#22FFFFFF')
                .borderRadius(6)
                .onClick((): void => {
                  this.chooseRight(clip);
                })
              }
            }, (clip: VideoClip): string => clip.id.toString())
          }
          .layoutWeight(1)
          .width('100%')
          .scrollBar(BarState.Off)
        }
      }
      .width('100%')
      .height('72%')
      .padding(12)
      .backgroundColor('#F0202020')
      .borderRadius({ topLeft: 12, topRight: 12 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#66000000')
  }

  build() {
    Stack() {
      Column() {
        Row({ space: 6 }) {
          Button('返回')
            .fontSize(12)
            .onClick((): void => {
              this.getUIContext().getRouter().back();
            })
          Text('对比播放')
            .fontSize(13)
            .fontColor(Color.White)
            .layoutWeight(1)
          Button(this.rightClip === undefined ? '选右片' : '换右片')
            .fontSize(11)
            .onClick((): void => {
              this.openRightPicker();
            })
          Button(`声音:${this.soundLabel()}`)
            .fontSize(11)
            .onClick((): void => {
              this.cycleSound();
            })
        }
        .width('100%')
        .padding({ left: 10, right: 10, top: 8, bottom: 4 })

        Row() {
          this.leftPane()
          Column()
            .width(1)
            .height('100%')
            .backgroundColor('#33FFFFFF')
          this.rightPane()
        }
        .layoutWeight(1)
        .width('100%')

        Column({ space: 4 }) {
          Row({ space: 6 }) {
            // T22 双轴时间线：左端标签 = tMin()（off>0 时负轴，T23 起负值走 formatSignedDuration
            // 显示 -m:ss；亚秒负值下限 -00:01，避免 -500ms 步进时与 0 混淆）；滑杆值即时间线
            // 坐标 t；仅左端标签带符号，进度读数仍随左钟口径不变
            Text(formatSignedDuration(this.timelineTMin()))
              .fontSize(10)
              .fontColor('#B0BEC5')
            Slider({
              value: this.sliderValue,
              min: this.timelineTMin(),
              max: this.timelineTMax(),
              step: 1
            })
              .layoutWeight(1)
              .enabled(this.leftReady && this.leftDurationMs > 0)
              .onChange((value: number, mode: SliderChangeMode): void => {
                this.sliderValue = value;
                if (mode === SliderChangeMode.Begin || mode === SliderChangeMode.Moving) {
                  this.sliderDragging = true;
                  this.leftEngine.setSeeking(true);
                  this.positionMs = Math.round(value);
                } else if (mode === SliderChangeMode.End) {
                  this.sliderDragging = false;
                  this.leftEngine.setSeeking(false);
                  this.seekBoth(value);
                } else if (mode === SliderChangeMode.Click) {
                  this.sliderDragging = false;
                  this.leftEngine.setSeeking(false);
                  this.seekBoth(value);
                }
              })
            // 右端标签 = timelineEnd()（= tMax；off=0 时即 max(L,R)，与旧左轴时长标签衔接）
            Text(formatDuration(timelineEnd(this.timelineGeo())))
              .fontSize(10)
              .fontColor('#B0BEC5')
          }
          .width('100%')

          Row({ space: 6 }) {
            Button(this.playing ? '暂停' : '播放')
              .fontSize(11)
              .onClick((): void => {
                this.togglePlay();
              })
            Button(`倍速:${PlayerSpeedTiers.label(this.speed)}`)
              .fontSize(11)
              .onClick((): void => {
                this.cycleSpeed();
              })
            Button(`声音:${this.soundLabel()}`)
              .fontSize(11)
              .onClick((): void => {
                this.cycleSound();
              })
            Button('偏移归零')
              .fontSize(11)
              .onClick((): void => {
                this.resetOffset();
              })
          }
          .width('100%')

          Row({ space: 6 }) {
            Text('手动偏移')
              .fontSize(10)
              .fontColor('#90A4AE')
            Button('-0.5s')
              .fontSize(11)
              .onClick((): void => {
                this.adjustOffset(-OFFSET_STEP_MS);
              })
            Text(this.offsetText())
              .fontSize(11)
              .fontColor('#FFCC80')
              .width(76)
              .textAlign(TextAlign.Center)
            Button('+0.5s')
              .fontSize(11)
              .onClick((): void => {
                this.adjustOffset(OFFSET_STEP_MS);
              })
            Blank()
            Text('右片相对左片').fontSize(9).fontColor('#607D8B')
          }
          .width('100%')

          if (this.hint !== '') {
            Text(this.hint)
              .fontSize(10)
              .fontColor('#FFCC80')
              .maxLines(2)
              .width('100%')
              .onClick((): void => {
                this.hint = '';
              })
          }
        }
        .width('100%')
        .padding({ left: 10, right: 10, bottom: 10, top: 4 })
      }
      .width('100%')
      .height('100%')

      if (this.selectingRight) {
        this.pickerOverlay()
      }

      if (this.fatalError !== '') {
        Column({ space: 8 }) {
          Text(this.fatalError)
            .fontSize(12)
            .fontColor('#FF8A80')
            .maxLines(4)
            .textAlign(TextAlign.Center)
          Button('返回')
            .fontSize(12)
            .onClick((): void => {
              this.getUIContext().getRouter().back();
            })
        }
        .padding(16)
        .backgroundColor('#CC101010')
        .borderRadius(8)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
}
