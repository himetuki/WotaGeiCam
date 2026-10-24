/**
 * 对比播放（双 XComponent + 双 PlayerEngine），T25 起消费上游 CompareTimeline decide 内核。
 *
 * 平台差异 / 本批边界：
 * - 两个预览面都是普通 XComponent(SURFACE)：AVPlayer 每实例独占一个 surface，
 *   不走 native libentry 的单路 GL 预览（那一路只有一个 NativeImage，不能同时喂两面）；
 * - 时间线编排：onTick 每拍交 decide 裁决（域判定/主钟/交接/纠偏/末段回绕/拖动让路），
 *   应用顺序固定 pause/resume → seek → nudge → 黑层写回；主钟读数走 resolveT（可为负轴 T）；
 * - 进页起播保持「用户手点播放」（上游 attach 后自动起播，登记偏离）；换右片/右片晚挂
 *   用 wantPlaying 续播意图补一次 setPlaying(true)；
 * - 静音保留三态扩展（左/右走 CompareAudio 计划，静音直接双 0）；双音轨门控用
 *   ClipExporter.probe 的 audioCount≥2 替代上游 TrackSync.countAudioTracks；
 * - 漂移速度微调为平台空操作（AVPlayer setSpeed 仅枚举档），250ms 硬 seek 保留；
 * - 对比历史走 preferences（store=wota_player_ab）+ 纯核心编解码；练习动线（ComparePractice）
 *   需分屏录制页，本批不接线（T26）。
 *
 * T25 审查修复（2026-10-22）：
 * - 重建续播：surface 重建/重试先记 wantPlaying（旧引擎 release 后 playing 归零），attach 成功
 *   走 seekAndPlayIfPaused（seek 落地再 play），maybeResumeWanted 不再以「任一侧在播」早退；
 * - 独播域复活：seekTimeline 写黑层后若 wantPlaying 即 resumeDomainAt(t)——拖回双覆盖区
 *   dormant 侧随松手复播，不再无黑层冻结；
 * - 音轨重探：换右片/左右 attach 成功均按当前音频侧重探 audioSideDual；静音态也落轨选择；
 * - 单侧进度：右片未选/报错时 positionMs/sliderValue 跟左钟（重建中保留 T，预录态不回落）。
 */
import { common } from '@kit.AbilityKit';
import { ArcDropLog } from '../record/ArcDropLog';
import { Diag } from '../diag/Diag';
import { ClipFilter } from '../media/MediaTag';
import { MediaRepo, VideoClip } from '../media/MediaRepo';
import { formatDuration, formatSignedDuration } from '../media/MediaFormat';
import { ClipExporter, ClipTrackInfo } from '../player/ClipExporter';
import { PlayerEngine } from '../player/PlayerEngine';
import { SettingsStore } from '../ui/SettingsStore';
import { PlayerSpeedTiers } from '../player/PlayerModel';
import {
  AudioTrackKind, CompareAudioPlan, EngineCommand, EngineCommandKind, Side, applyOrderOf, audioPlanOf
} from '../player/CompareAudio';
import { CompareHistoryEntry } from '../player/CompareHistory';
import { CompareHistoryStore } from '../player/CompareHistoryStore';
import {
  CompareDomain, DecideCommands, DecideInput, decide, domainOf, normalizeOffset, resolveT,
  rightResumeTargetOf, timelineEnd, TimelineGeometry
} from '../player/CompareTimeline';

interface CompareParams {
  leftId: number;
  leftPath?: string;
}

/** 历史面板一行（缺失 = 右片已不在媒体库，只能删） */
class CompareHistoryRow {
  readonly rightId: number;
  readonly leftId: number;
  readonly rightName: string;
  readonly leftName: string;
  readonly missing: boolean;
  readonly clip: VideoClip | undefined;

  constructor(rightId: number, leftId: number, rightName: string, leftName: string,
    missing: boolean, clip: VideoClip | undefined) {
    this.rightId = rightId;
    this.leftId = leftId;
    this.rightName = rightName;
    this.leftName = leftName;
    this.missing = missing;
    this.clip = clip;
  }
}

const TAG: string = 'WotaCompare';
/** 默认节拍（上游 compare_tick_ms 缺键 200——本侧历史口径 120，登记为缺键默认） */
const TICK_MS_DEFAULT: number = 120;
/** decide 的硬 seek 阈值（上游 HARD_RESYNC_MS；邻域内交 nudgeSpeedForSync 软追赶） */
const HARD_RESYNC_MS: number = 250;
const OFFSET_STEP_MS: number = 500;
const DEFAULT_FPS: number = 30;
/** 音轨探测在途的陈旧阈值：超过此值视为探测丢失（兜底解除 override 抑制） */
const AUDIO_PROBE_STALE_MS: number = 2000;
/** 黑层总开关（review 退路：false = 不渲染黑层；dormant pause 仍由 decide 下发） */
const BLACK_LAYER_ENABLED: boolean = true;

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
  /** T25：全程循环（时间线末回绕 Tmin；关 = 停末帧） */
  @State timelineLoop: boolean = true;
  /** T25：dormant 黑层（decide 输出写回；UI 常驻透明度层） */
  @State domBlackLeft: boolean = false;
  @State domBlackRight: boolean = false;
  /** T25：音频侧/轨（env=组 0、cap=组 1；双音轨才显示 chip） */
  @State audioTrack: AudioTrackKind = AudioTrackKind.ENV;
  @State audioSideDual: boolean = false;
  /** 音轨探测在途（P3-3）：在途时跳过 selectAudioGroup（音量照发），2s 陈旧兜底 */
  @State audioProbePending: boolean = false;
  /** T25：历史面板 */
  @State historyOpen: boolean = false;
  @State historyLoading: boolean = false;
  @State historyRows: Array<CompareHistoryRow> = [];

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
  private ctx: common.Context | undefined = undefined;
  private disposed: boolean = false;
  private pageVisible: boolean = true;
  private tickTimer: number = -1;
  /** 编排节拍（ST 批：compare_tick_ms 持久化，缺键回本侧历史 120） */
  private tickMs: number = TICK_MS_DEFAULT;
  /** 当前音频侧（静音时保留最后一次有声侧；声音循环同步） */
  private audioSide: Side = Side.LEFT;
  /** 音轨探测发起的墙钟毫秒（audioProbePending 的陈旧判定基准） */
  private audioProbePendingAt: number = 0;
  /** 续播意图：换右片/右片晚挂/重建后 attach 成功时补一次 setPlaying(true) */
  private wantPlaying: boolean = false;
  private resumeLeftPosMs: number = -1;
  /** 右片重建/重试前的位置快照（-1 = 无待恢复；换选右片时清 -1 不恢复） */
  private resumeRightPosMs: number = -1;
  /** 隐藏期发生的 surface 重建（left/right 各一）：只置标志，onPageShow 消费执行重建 */
  private deferredLeftRebuild: boolean = false;
  private deferredRightRebuild: boolean = false;
  /** 路由参数原始左片 path（leftClip 未就绪时右列表按 path 排除用） */
  private paramsLeftPath: string = '';

  aboutToAppear(): void {
    // ST 批：编排节拍持久化（上游 compare_tick_ms；缺键回本侧历史 120）
    const ctx0: common.Context | undefined =
      this.getUIContext().getHostContext() as common.Context | undefined;
    if (ctx0 !== undefined) {
      this.tickMs = SettingsStore.compareTickMsSync(ctx0);
    }
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
    this.ctx = ctx;
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
      // 重建前记住续播意图（旧引擎放掉后 playing 就没了，不能等 attach 时再读）
      this.wantPlaying = this.wantPlaying || this.leftEngine.playing ||
        (this.rightReady && this.rightEngine.playing);
      this.snapshotLeftForResume();
      this.leftReady = false;
      this.leftLoading = true;
      this.leftAttachStarted = false;
      this.leftError = '';
      this.rebuildLeftEngine();
    }
    if (this.deferredRightRebuild) {
      this.deferredRightRebuild = false;
      this.wantPlaying = this.wantPlaying || this.leftEngine.playing ||
        (this.rightReady && this.rightEngine.playing);
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
    }, this.tickMs);
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
    // 双片就绪且无错误才交 decide：单侧/错误态由加载层与错误层接管，不猜编排。
    // 但进度读数不能跟着停：右片未选/报错时仍按左钟（currentTimelineT 已回退左片素材位）
    // 更新 positionMs/sliderValue，否则播放中滑杆冻结（T25 审查修复）。
    if (!this.leftReady || !this.rightReady || this.leftError !== '' || this.rightError !== '') {
      // 右片未选/报错：进度跟左钟；右片重建/加载中保留 T（预录态不能回退成左素材 0）
      const rightAbsent: boolean = this.rightClip === undefined;
      const rightBroken: boolean = this.rightError !== '' && !this.rightLoading;
      if (this.leftReady && !this.sliderDragging && (rightAbsent || rightBroken)) {
        const fallback: number = Math.round(Math.min(this.timelineTMax(),
          Math.max(this.timelineTMin(), this.currentTimelineT())));
        if (fallback !== this.positionMs) {
          this.positionMs = fallback;
        }
        if (fallback !== this.sliderValue) {
          this.sliderValue = fallback;
        }
      }
      return;
    }
    const geo: TimelineGeometry = this.timelineGeo();
    const abLoopActive: boolean = left.ab.valid && left.ab.loopEnabled;
    // userPlaying 取「续播意图 ∪ 引擎在播」：ENDED 后引擎 playing 已落，仅靠并集会丢循环/交接
    // 的用户意图（上游 isPlaying 在 ENDED 同样为假，这里用 wantPlaying 补足意图位，登记偏离）
    const userPlaying: boolean = this.wantPlaying || left.playing || right.playing;
    const cmd: DecideCommands = decide(new DecideInput(geo,
      Math.max(0, left.positionMs), Math.max(0, right.positionMs),
      userPlaying, abLoopActive, this.timelineLoop, this.sliderDragging,
      this.domBlackLeft, this.domBlackRight, HARD_RESYNC_MS));
    // 应用顺序红线（不可换）：pause/resume → seek → nudge → 黑层写回。
    // resume 触发的 ENDED 自动回零会被随后的 seek 覆盖，nudge 永远落在最终位置上。
    if (cmd.pauseLeft) {
      left.pauseIfPlaying();
    }
    if (cmd.pauseRight) {
      right.pauseIfPlaying();
    }
    if (cmd.resumeLeft) {
      left.playIfPaused();
    }
    if (cmd.resumeRight) {
      right.playIfPaused();
    }
    if (cmd.seekLeftMs !== undefined) {
      left.seekTo(cmd.seekLeftMs);
    }
    if (cmd.seekRightMs !== undefined) {
      right.seekTo(cmd.seekRightMs);
    }
    if (cmd.nudgeRightDriftMs !== undefined) {
      right.nudgeSpeedForSync(cmd.nudgeRightDriftMs);
    }
    this.domBlackLeft = cmd.blackLeft;
    this.domBlackRight = cmd.blackRight;
    // P3-1：自然播完（时间线末 + 循环关 + 双引擎已停）清续播意图——否则 wantPlaying 会
    // 让下一拍 decide 把 userPlaying 当假真，末态读数/回绕判断失真（用户再点播放才重新置位）。
    const ended: CompareDomain = domainOf(geo, Math.max(0, left.positionMs),
      Math.max(0, right.positionMs), this.domBlackLeft, this.domBlackRight);
    if (!this.timelineLoop && !this.sliderDragging && !left.playing && !right.playing &&
      ended === CompareDomain.TIMELINE_END) {
      this.wantPlaying = false;
    }
    // 主钟读数走 resolveT：双素材/左独播随左钟、右独播随右钟、末态 Tmax（pre-roll 为负 T）
    if (!this.sliderDragging) {
      const t: number = Math.round(resolveT(geo, Math.max(0, left.positionMs),
        Math.max(0, right.positionMs), this.domBlackLeft, this.domBlackRight));
      if (t !== this.positionMs) {
        this.positionMs = t;
      }
      if (t !== this.sliderValue) {
        this.sliderValue = t;
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
      this.applyAudioPlan();
      this.probeAudioSide();
      if (this.resumeLeftPosMs >= 0) {
        const resume: number = this.resumeLeftPosMs;
        this.resumeLeftPosMs = -1;
        // 重建续播：seek 落地后再起播（与 decide 的 resume→seek 顺序不抢状态机）
        if (this.wantPlaying) {
          engine.seekAndPlayIfPaused(resume);
        } else {
          engine.seekTo(resume);
        }
      }
      this.maybeResumeWanted();
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
      this.applyAudioPlan();
      this.probeAudioSide();
      if (this.resumeRightPosMs >= 0) {
        // 右片重建/重试恢复位置（素材位快照）。审查修复 P2：**始终用快照**算恢复目标——
        // 新引擎刚 attach 时 positionMs 恒 0，拿它参与 resolveT 会在右独播域（左已播尽）
        // 被读成双覆盖、T 落到左片时长，右窗回跳（off>0 pre-roll 更会回素材 0）。
        // rightResumeTargetOf 以快照为右钟输入做域判定，右独播/左独播/末态 park 都落正确素材位；
        // 这里只发一次 seek（wantPlaying 时 seek 落地即播），maybeResumeWanted 随后复算 T
        // （此时引擎位置已是目标位）只做域内唤醒，不再二次 seek。
        const snapshot: number = this.resumeRightPosMs;
        this.resumeRightPosMs = -1;
        // T26 审查 P3-a：左片未就绪（双表面重建右先挂）时左引擎 positionMs 恒 0，不得参与
        // 域判定——直接用快照作恢复位（右片自身素材位），左片挂上后由 decide/resume 对齐。
        let resumeAt: number = snapshot;
        if (this.leftReady) {
          const target: number | undefined = rightResumeTargetOf(this.timelineGeo(),
            Math.max(0, this.leftEngine.positionMs), snapshot, this.domBlackLeft, this.domBlackRight);
          if (target !== undefined) {
            resumeAt = target;
          }
        }
        if (this.wantPlaying) {
          engine.seekAndPlayIfPaused(resumeAt);
        } else {
          engine.seekTo(resumeAt);
        }
      }
      this.maybeResumeWanted();
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

  /** 左片重建/重试前的位置快照：就绪取引擎实位（素材位），否则按时间线 park 落点 */
  private snapshotLeftForResume(): void {
    this.resumeLeftPosMs = this.leftReady
      ? Math.max(0, Math.round(this.leftEngine.positionMs))
      : this.timelineGeo().leftTarget(this.positionMs);
  }

  /** 右片重建/重试前的位置快照：就绪取引擎实位（素材位），否则按时间线 park 落点 */
  private snapshotRightForResume(): void {
    this.resumeRightPosMs = this.rightReady
      ? Math.max(0, Math.round(this.rightEngine.positionMs))
      : this.timelineGeo().rightTarget(this.positionMs);
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
        // T25 审查修复：先记住续播意图（旧引擎 release 后 playing 归零），attach 成功据此复播
        this.wantPlaying = this.wantPlaying || this.leftEngine.playing ||
          (this.rightReady && this.rightEngine.playing);
        // T25：快照改素材位（时间线 T 可为负轴，不能直接当素材位置）
        this.snapshotLeftForResume();
        // 黑层/域状态保留：重建期间右独播/尾段的主钟口径不能丢，attach 后由 maybeResumeWanted 复算
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
        // T25 审查修复：右片重建同样先记住续播意图，否则另一侧在播会让 maybeResumeWanted 早退
        this.wantPlaying = this.wantPlaying || this.leftEngine.playing ||
          (this.rightReady && this.rightEngine.playing);
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
    this.wantPlaying = this.wantPlaying || this.leftEngine.playing ||
      (this.rightReady && this.rightEngine.playing);
    this.pauseAll(false);
    this.snapshotLeftForResume();
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
    this.wantPlaying = this.wantPlaying || this.leftEngine.playing ||
      (this.rightReady && this.rightEngine.playing);
    this.pauseAll(false);
    this.snapshotRightForResume();
    this.rightReady = false;
    this.rightLoading = true;
    this.rightAttachStarted = false;
    this.rightError = '';
    this.rebuildRightEngine();
  }

  private openRightPicker(): void {
    // 选片期间暂停但保留续播意图：选中后 chooseRight 会带进 wantPlaying，关掉面板则清意图
    const wasPlaying: boolean = this.wantPlaying ||
      this.leftEngine.playing || (this.rightReady && this.rightEngine.playing);
    this.pauseAll(false);
    this.wantPlaying = wasPlaying;
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
    // 换片前保留续播意图：新右片 attach 成功后由 maybeResumeWanted 补一次 setPlaying(true)
    const wasPlaying: boolean = this.wantPlaying ||
      this.leftEngine.playing || (this.rightReady && this.rightEngine.playing);
    this.selectingRight = false;
    this.pauseAll(false);
    this.wantPlaying = wasPlaying;
    this.rightClip = clip;
    this.rightName = clip.name;
    this.rightDurationMs = Math.max(0, clip.durationMs);
    this.rightReady = false;
    this.rightLoading = true;
    this.rightAttachStarted = false;
    this.rightError = '';
    this.resumeRightPosMs = -1; // 换选右片是全新片源：不恢复旧片位置
    // 换片：黑层/域状态保留（attach 后 maybeResumeWanted 按新片位置复算），
    // 音轨门控按当前音频侧重探（审查修复：不能只置 false 等 attach，默认音频侧=左时换右片
    // 不会触发左 attach，chip 会永久消失）
    this.audioTrack = AudioTrackKind.ENV;
    this.audioSideDual = false;
    this.normalizeOffsetNow();
    this.probeAudioSide();
    if (left !== undefined && this.ctx !== undefined) {
      CompareHistoryStore.record(this.ctx, clip.id, left.id).then((): void => {
        // 落盘异步；失败在 store 内 Diag.w，不阻塞换片
      });
    }
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

  /** 当前时间线主钟读数（resolveT；右片未就绪时退回左片素材位） */
  private currentTimelineT(): number {
    if (!this.leftReady) {
      return this.positionMs;
    }
    const leftPos: number = Math.max(0, this.leftEngine.positionMs);
    if (!this.rightReady) {
      return Math.round(leftPos);
    }
    const rightPos: number = Math.max(0, this.rightEngine.positionMs);
    return Math.round(resolveT(this.timelineGeo(), leftPos, rightPos, this.domBlackLeft, this.domBlackRight));
  }

  private engineForSide(side: Side): PlayerEngine {
    return side === Side.LEFT ? this.leftEngine : this.rightEngine;
  }

  private sideReady(side: Side): boolean {
    return side === Side.LEFT ? this.leftReady : this.rightReady;
  }

  /**
   * 音频计划下发（T25）：静音=双 0（本侧三态扩展）；否则 CompareAudio 的固定命令序
   * ——先静音另一侧 → 再设/清 override（selectAudioGroup）→ 最后落选中侧音量 1。
   * 未就绪侧跳过（其 attach 成功后会重新 applyAudioPlan）。
   */
  private applyAudioPlan(): void {
    if (this.soundSide === SOUND_MUTE) {
      if (this.leftReady) {
        this.leftEngine.setVolume(0);
      }
      if (this.rightReady) {
        this.rightEngine.setVolume(0);
      }
      // 静音态也落轨选择（音量保持 0）：静音中切「内录/环境」对引擎**静默生效**，
      // 解除静音不必重选、也不会把用户选过的轨复位（T25 审查修复 P3）
      if (this.audioSideDual && this.sideReady(this.audioSide)) {
        const mutedPlan: CompareAudioPlan =
          audioPlanOf(this.audioSide, this.audioTrack, this.audioSideDual);
        this.engineForSide(this.audioSide).selectAudioGroup(mutedPlan.overrideGroupIndex);
      }
      return;
    }
    const plan: CompareAudioPlan = audioPlanOf(this.audioSide, this.audioTrack, this.audioSideDual);
    const cmds: Array<EngineCommand> = applyOrderOf(plan);
    // P3-3：探测在途时跳过 selectAudioGroup（旧结论可能指向不存在的组，越界会被引擎拒并刷日志），
    // 音量命令照发；2s 后视为探测丢失，不再抑制。
    const probeBlocked: boolean = this.audioProbePending &&
      (Date.now() - this.audioProbePendingAt) < AUDIO_PROBE_STALE_MS;
    for (const cmd of cmds) {
      if (!this.sideReady(cmd.side)) {
        continue;
      }
      const engine: PlayerEngine = this.engineForSide(cmd.side);
      if (cmd.kind === EngineCommandKind.SET_VOLUME) {
        engine.setVolume(cmd.volume);
      } else if (probeBlocked) {
        // 探测在途：override/清 override 一律让路（音量已在上面落定）
      } else if (cmd.kind === EngineCommandKind.SET_AUDIO_OVERRIDE) {
        engine.selectAudioGroup(cmd.groupIndex);
      } else {
        engine.selectAudioGroup(undefined);
      }
    }
  }

  /** 探当前音频侧片源的音轨数（audioCount≥2 才允许内录 chip；失败/单轨归 false 并复位 ENV） */
  private clearAudioProbePending(): void {
    this.audioProbePending = false;
    this.audioProbePendingAt = 0;
  }

  private probeAudioSide(): void {
    const ctx: common.Context | undefined = this.ctx;
    const side: Side = this.audioSide;
    const clip: VideoClip | undefined = side === Side.LEFT ? this.leftClip : this.rightClip;
    if (ctx === undefined || clip === undefined) {
      // 早退也要清在途位（审查修复 P3）：上一次探测若未落地，残留的 pending 会压掉 override
      this.clearAudioProbePending();
      this.audioSideDual = false;
      this.resetAudioTrackIfNeeded();
      return;
    }
    this.audioProbePending = true;
    this.audioProbePendingAt = Date.now();
    ClipExporter.probe(ctx, clip).then((info: ClipTrackInfo): void => {
      if (this.disposed || this.audioSide !== side) {
        // 侧已切换：pending 归新一次探测所有，不得在此清（否则会解开新探测的抑制）
        return;
      }
      this.clearAudioProbePending();
      this.audioSideDual = info.error === '' && info.audioCount >= 2;
      this.resetAudioTrackIfNeeded();
      this.applyAudioPlan();
      Diag.i(TAG, `audio side=${side} count=${info.audioCount} err=${info.error}`);
    }).catch((e: Object): void => {
      if (this.disposed || this.audioSide !== side) {
        return;
      }
      this.clearAudioProbePending();
      this.audioSideDual = false;
      this.resetAudioTrackIfNeeded();
      this.applyAudioPlan();
      Diag.w(TAG, `audio probe failed: ${e}`);
    });
  }

  private resetAudioTrackIfNeeded(): void {
    if (!this.audioSideDual && this.audioTrack === AudioTrackKind.CAP) {
      this.audioTrack = AudioTrackKind.ENV;
    }
  }

  private cycleSound(): void {
    this.soundSide = (this.soundSide + 1) % 3;
    if (this.soundSide !== SOUND_MUTE) {
      this.audioSide = this.soundSide === SOUND_RIGHT ? Side.RIGHT : Side.LEFT;
      // 切边复探：探测完成前保留旧结论（dual=false 时 resetAudioTrackIfNeeded 会复位环境），
      // 不在切边瞬间清 audioTrack —— 否则静音/切边往返会静默丢掉用户选过的内录（T25 审查修复）
      this.probeAudioSide();
    }
    this.applyAudioPlan();
    this.hint = `声音：${this.soundLabel()}`;
  }

  private cycleAudioTrack(): void {
    if (!this.audioSideDual) {
      return;
    }
    this.audioTrack = this.audioTrack === AudioTrackKind.CAP ? AudioTrackKind.ENV : AudioTrackKind.CAP;
    this.applyAudioPlan();
    this.hint = `音轨：${this.audioTrack === AudioTrackKind.CAP ? '内录' : '环境'}`;
  }

  private toggleTimelineLoop(): void {
    this.timelineLoop = !this.timelineLoop;
    this.hint = `全程循环：${this.timelineLoop ? '开' : '关'}`;
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

  /** 统一暂停（clearWant=true 清续播意图；换片/重试保留意图时传 false） */
  private pauseAll(clearWant: boolean = true): void {
    if (this.leftReady && this.leftEngine.playing) {
      this.leftEngine.pauseIfPlaying();
    }
    if (this.rightReady && this.rightEngine.playing) {
      this.rightEngine.pauseIfPlaying();
    }
    this.playing = false;
    if (clearWant) {
      this.wantPlaying = false;
    }
  }

  private togglePlay(): void {
    if (!this.leftReady) {
      this.hint = '左片尚未就绪';
      return;
    }
    const left: PlayerEngine = this.leftEngine;
    const active: boolean = left.playing || (this.rightReady && this.rightEngine.playing);
    if (active) {
      this.setPlaying(false);
      return;
    }
    this.setPlaying(true);
  }

  /**
   * 播放意图入口（T25）：want=true 时末态先回 Tmin，再按当前域恢复主钟侧、dormant 侧保持停；
   * want=false 统一暂停并清意图。attach 成功后的续播也走这里（wantPlaying 门）。
   */
  private setPlaying(want: boolean): void {
    if (this.disposed) {
      return;
    }
    if (!want) {
      this.pauseAll();
      return;
    }
    if (!this.leftReady) {
      this.hint = '左片尚未就绪';
      return;
    }
    this.wantPlaying = true;
    const geo: TimelineGeometry = this.timelineGeo();
    let t: number = this.currentTimelineT();
    if (t >= geo.tMax() - 100) {
      this.seekTimeline(geo.tMin());
      t = geo.tMin();
    }
    this.resumeDomainAt(t);
    this.playing = true;
  }

  /** 按时间线位 t 恢复域内主钟侧播放、dormant 侧暂停；黑层按覆盖判定写回（末态不盖黑） */
  private resumeDomainAt(t: number): void {
    const geo: TimelineGeometry = this.timelineGeo();
    const leftCovers: boolean = geo.leftCovers(t);
    const rightCovers: boolean = this.rightReady && geo.rightCovers(t);
    const atEnd: boolean = t >= geo.tMax();
    if (this.leftReady) {
      if (leftCovers) {
        this.leftEngine.playIfPaused();
      } else {
        this.leftEngine.pauseIfPlaying();
      }
    }
    if (this.rightReady) {
      if (rightCovers) {
        this.rightEngine.playIfPaused();
      } else {
        this.rightEngine.pauseIfPlaying();
      }
    }
    this.domBlackLeft = !atEnd && !leftCovers;
    this.domBlackRight = !atEnd && !rightCovers;
  }

  /**
   * 续播意图消费（T25 审查修复）：不再以「任一侧在播」为门（重建侧停着也早退），
   * 而是以 wantPlaying + 本侧覆盖判定为准：先把新挂侧对齐到时间线期望素材位，
   * 再统一 resumeDomainAt —— 覆盖侧 playIfPaused、dormant 侧保持停、黑层按覆盖写回。
   * 入口态 wantPlaying=false 时不动（进页起播仍归用户手点，登记偏离）。
   */
  private maybeResumeWanted(): void {
    if (this.disposed || !this.wantPlaying || !this.leftReady) {
      return;
    }
    const geo: TimelineGeometry = this.timelineGeo();
    const t: number = this.currentTimelineT();
    if (t >= geo.tMax()) {
      // 末态续播：走 setPlaying 的 Tmin 回绕（与用户点播放同路径）。
      // P3-1：这里只认严格到顶；100ms 容差只留给 setPlaying 的「用户点播放」路径。
      this.setPlaying(true);
      return;
    }
    // 新挂右侧对齐：从旧位置/0 起播会白闪一帧，先 seek 到 T 对应素材位（已覆盖才对齐）。
    // T26 审查 P3-a：右片**已在播**时 seekAndPlayIfPaused 会早退（playing 门），必须用
    // seekTo 纠偏——例如右片重建后已按快照起播、左片挂上后 T 变为右主钟的另一位置。
    if (this.rightReady && geo.rightCovers(t)) {
      const target: number = geo.rightTarget(t);
      if (Math.abs(this.rightEngine.positionMs - target) > 1) {
        if (this.rightEngine.playing) {
          this.rightEngine.seekTo(target);
        } else {
          this.rightEngine.seekAndPlayIfPaused(target);
        }
      }
    }
    this.resumeDomainAt(t);
    if (this.leftEngine.playing || (this.rightReady && this.rightEngine.playing)) {
      this.playing = true;
    }
  }

  /**
   * 时间线落位（T25 统一入口）：ms 为时间线坐标（可负，夹 [tMin,tMax]）——
   * 左走 leftTarget park 规则、右走 rightTarget；无覆盖侧暂停并亮黑层，末态撤黑。
   * 播放权不在本方法：松手后的起播由 decide/setPlaying 决定。
   */
  private seekTimeline(ms: number): void {
    const geo: TimelineGeometry = this.timelineGeo();
    const t: number = Math.round(Math.min(geo.tMax(), Math.max(geo.tMin(), ms)));
    this.positionMs = t;
    this.sliderValue = t;
    const leftCovers: boolean = geo.leftCovers(t);
    const rightCovers: boolean = geo.rightCovers(t);
    const atEnd: boolean = t >= geo.tMax();
    if (this.leftReady) {
      if (!leftCovers) {
        this.leftEngine.pauseIfPlaying();
      }
      this.leftEngine.seekTo(geo.leftTarget(t));
    }
    if (this.rightReady) {
      if (!rightCovers) {
        this.rightEngine.pauseIfPlaying();
      }
      this.rightEngine.seekTo(geo.rightTarget(t));
    }
    if (atEnd) {
      this.domBlackLeft = false;
      this.domBlackRight = false;
    } else {
      this.domBlackLeft = !leftCovers;
      this.domBlackRight = !rightCovers;
    }
    // T25 审查修复：拖回双覆盖区/跨域后按用户播放意图统一复活覆盖侧。
    // 只写黑层=false 会抹掉 decide「该侧原 dormant、需要唤醒」的唯一信号（resume=black&&userPlaying），
    // dormant 侧将无黑层地冻在暂停帧；这里松手即按覆盖判定 resumeDomainAt（播放权仍归 wantPlaying）。
    if (this.wantPlaying) {
      this.resumeDomainAt(t);
    }
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
    const t: number = this.currentTimelineT();
    this.offsetMs = next;
    this.seekTimeline(t);
    this.hint = `偏移 ${this.offsetText()}`;
  }

  private resetOffset(): void {
    if (this.rightClip === undefined) {
      this.hint = '请先选择右侧片源';
      return;
    }
    const t: number = this.currentTimelineT();
    this.offsetMs = 0;
    this.seekTimeline(t);
    this.hint = '偏移已归零';
  }

  private offsetText(): string {
    return `${this.offsetMs >= 0 ? '+' : ''}${this.offsetMs}ms`;
  }

  /** 打开历史面板：先暂停（保留续播意图，选中条目后自动续播），再异步载入 */
  private openHistory(): void {
    // 与选片同口径：保留续播意图供条目选择后续播；直接关闭面板时清意图
    const wasPlaying: boolean = this.wantPlaying ||
      this.leftEngine.playing || (this.rightReady && this.rightEngine.playing);
    this.pauseAll(false);
    this.wantPlaying = wasPlaying;
    this.historyOpen = true;
    this.loadHistory();
  }

  /**
   * 载入历史列表：CompareHistoryStore 读 preferences（坏值返回空表），
   * MediaRepo.load(ClipFilter.all()) 一次建 id→clip 映射；右片缺失标「缺失」且只能删。
   */
  private loadHistory(): void {
    const ctx: common.Context | undefined = this.ctx;
    const repo: MediaRepo | undefined = this.repo;
    if (ctx === undefined || repo === undefined) {
      this.historyRows = [];
      return;
    }
    this.historyLoading = true;
    CompareHistoryStore.load(ctx).then((entries: Array<CompareHistoryEntry>): void => {
      if (this.disposed) {
        return;
      }
      repo.load(ClipFilter.all()).then((clips: Array<VideoClip>): void => {
        if (this.disposed) {
          return;
        }
        const byId: Map<number, VideoClip> = new Map();
        for (const c of clips) {
          byId.set(c.id, c);
        }
        const rows: Array<CompareHistoryRow> = [];
        for (const e of entries) {
          const right: VideoClip | undefined = byId.get(e.rightId);
          const left: VideoClip | undefined = byId.get(e.leftId);
          const rightName: string = right !== undefined ? right.name : '缺失';
          const leftName: string = left !== undefined ? left.name : '缺失';
          rows.push(new CompareHistoryRow(e.rightId, e.leftId, rightName, leftName,
            right === undefined, right));
        }
        this.historyRows = rows;
        this.historyLoading = false;
      }).catch((e: Object): void => {
        if (this.disposed) {
          return;
        }
        this.historyLoading = false;
        this.historyRows = [];
        this.hint = `读取媒体库失败: ${e}`;
      });
    }).catch((e: Object): void => {
      if (this.disposed) {
        return;
      }
      this.historyLoading = false;
      this.historyRows = [];
      this.hint = `读取历史失败: ${e}`;
    });
  }

  /** 点历史条目填右槽（缺失条目只提示，不换片） */
  private pickHistoryRow(row: CompareHistoryRow): void {
    const clip: VideoClip | undefined = row.clip;
    if (clip === undefined) {
      this.hint = '该记录的视频已不在媒体库，可删除此记录';
      return;
    }
    this.historyOpen = false;
    this.chooseRight(clip);
  }

  private deleteHistoryRow(row: CompareHistoryRow): void {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    CompareHistoryStore.remove(ctx, row.rightId).then((): void => {
      if (this.disposed) {
        return;
      }
      this.loadHistory();
    });
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
        // T25 dormant 黑层：常驻透明层，只在左独播域外/左独播时由 decide 写回；不拦触摸
        if (BLACK_LAYER_ENABLED) {
          Column()
            .width('100%')
            .height('100%')
            .backgroundColor(Color.Black)
            .opacity(this.domBlackLeft ? 1 : 0)
            .hitTestBehavior(HitTestMode.None)
        }
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
        // T25 dormant 黑层（右）
        if (BLACK_LAYER_ENABLED) {
          Column()
            .width('100%')
            .height('100%')
            .backgroundColor(Color.Black)
            .opacity(this.domBlackRight ? 1 : 0)
            .hitTestBehavior(HitTestMode.None)
        }
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
              this.pauseAll();
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
                this.pauseAll();
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

  /** T25 对比历史面板：条目名/缺失标记/点击填右槽/删除 */
  @Builder
  private historyOverlay() {
    Column() {
      Blank()
      Column({ space: 8 }) {
        Row({ space: 8 }) {
          Text('对比历史')
            .fontSize(14)
            .fontColor(Color.White)
            .layoutWeight(1)
          Button('关闭')
            .fontSize(11)
            .onClick((): void => {
              this.pauseAll();
              this.historyOpen = false;
            })
        }
        .width('100%')
        if (this.historyLoading) {
          Column({ space: 6 }) {
            LoadingProgress()
              .width(28)
              .height(28)
            Text('读取历史…')
              .fontSize(10)
              .fontColor('#AAAAAA')
          }
          .layoutWeight(1)
          .width('100%')
          .justifyContent(FlexAlign.Center)
        } else if (this.historyRows.length === 0) {
          Column({ space: 8 }) {
            Text('暂无对比历史')
              .fontSize(11)
              .fontColor('#AAAAAA')
            Text('选过一次右侧片源后会自动记录')
              .fontSize(9)
              .fontColor('#78909C')
          }
          .layoutWeight(1)
          .width('100%')
          .justifyContent(FlexAlign.Center)
        } else {
          List({ space: 4 }) {
            ForEach(this.historyRows, (row: CompareHistoryRow): void => {
              ListItem() {
                Row({ space: 8 }) {
                  Column({ space: 2 }) {
                    Text(`${row.rightName} ↔ ${row.leftName}`)
                      .fontSize(11)
                      .fontColor(row.missing ? '#FF8A80' : Color.White)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .width('100%')
                    Text(row.missing ? '缺失：右片已不在媒体库' : `右:${row.rightName}`)
                      .fontSize(9)
                      .fontColor('#90A4AE')
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .width('100%')
                  }
                  .layoutWeight(1)
                  .alignItems(HorizontalAlign.Start)
                  Button('删除')
                    .fontSize(10)
                    .onClick((): void => {
                      this.deleteHistoryRow(row);
                    })
                }
                .width('100%')
                .padding(10)
                .backgroundColor('#22FFFFFF')
                .borderRadius(6)
                .onClick((): void => {
                  this.pickHistoryRow(row);
                })
              }
            }, (row: CompareHistoryRow): string => `${row.rightId}_${row.leftId}_${row.missing}`)
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
          Button('历史')
            .fontSize(11)
            .onClick((): void => {
              this.openHistory();
            })
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
                  this.seekTimeline(value);
                } else if (mode === SliderChangeMode.Click) {
                  this.sliderDragging = false;
                  this.leftEngine.setSeeking(false);
                  this.seekTimeline(value);
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
            Button(this.timelineLoop ? '循环:开' : '循环:关')
              .fontSize(11)
              .onClick((): void => {
                this.toggleTimelineLoop();
              })
            if (this.audioSideDual) {
              Button(`音轨:${this.audioTrack === AudioTrackKind.CAP ? '内录' : '环境'}`)
                .fontSize(11)
                .onClick((): void => {
                  this.cycleAudioTrack();
                })
            }
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

      if (this.historyOpen) {
        this.historyOverlay()
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
