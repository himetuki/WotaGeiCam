/**
 * 播放引擎（上游 player/PlayerEngine.kt 的鸿蒙最小版，对齐上游 38d712a）：
 * AVPlayer + XComponent(SURFACE) + fdSrc；播放/暂停/seek、逐帧、A/B 循环、倍速、120ms 位置轮询。
 *
 * 平台偏离（登记）：
 * - 倍速无上游 0.1/0.3，使用 AVPlayer 档位 0.125/0.25/0.5/0.75/1.0；
 * - 逐帧使用 SeekMode.SEEK_CLOSEST（上游 Media3 1.1.1 无 SeekParameters；设备若落同步帧，
 *   退路为 SEEK_PREV_SYNC，登记待真机）；
 * - AVPlayer 无帧率读数：帧率由 .drops.json dstFps → 30 → 页面手动 24/25/30。
 */
import { fileIo } from '@kit.CoreFileKit';
import { media } from '@kit.MediaKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';
import { VideoClip } from '../media/MediaRepo';
import { AbRange, PlayerSpeedTiers } from './PlayerModel';
import { FrameStep } from './FrameStep';

const TAG: string = 'WotaPlayer';
const TICK_MS: number = 120;

export class PlayerEngine {
  private av: media.AVPlayer | undefined = undefined;
  private fd: number = -1;
  private released: boolean = false;
  private fallbackDurationMs: number = 0;

  state: string = 'idle';
  durationMs: number = 0;
  positionMs: number = 0;
  playing: boolean = false;
  ab: AbRange = new AbRange();
  speed: number = PlayerSpeedTiers.DEFAULT;
  actualSpeed: number = PlayerSpeedTiers.DEFAULT;
  fps: number = 30;
  error: string = '';

  private pendingStepMs: number | undefined = undefined;
  private pendingStepTicks: number = 0;
  private tickTimer: number = -1;
  private abWrapInFlight: boolean = false;
  private abUnlockTimer: number = -1;
  /** 进度条拖动中：暂停 AB 回绕，避免与用户 seek 打架 */
  private seeking: boolean = false;
  /** 最近一次 seek 是否 AB 回绕（仅回绕的 seekDone 才清 abWrapInFlight） */
  private lastSeekWasWrap: boolean = false;
  /** 回绕 seek 的目标（seekDone 的 time 与之匹配才认为是回绕落地，防旧事件误清） */
  private wrapSeekTarget: number = -1;
  /** 用户 seek 在途：回绕必须等它落地（800ms 后视为事件丢失，允许回绕继续） */
  private seekInFlight: boolean = false;
  private seekInFlightAt: number = 0;
  /** 页面隐藏：attach 完成时不得启动 tick */
  private suspended: boolean = false;

  /** 绑定 surface 与片源；返回空串 = 成功，'cancelled' = 页面已销毁，其余 = 错误串 */
  async attach(surfaceId: string, clip: VideoClip, fpsHint: number): Promise<string> {
    if (this.released) {
      return 'cancelled'; // release 之后不得复活
    }
    if (this.av !== undefined) {
      return '';
    }
    this.fps = fpsHint > 0 ? fpsHint : 30;
    this.fallbackDurationMs = clip.durationMs;
    let player: media.AVPlayer | undefined = undefined;
    try {
      const file: fileIo.File = await fileIo.open(clip.path, fileIo.OpenMode.READ_ONLY);
      this.fd = file.fd;
      if (this.released) {
        throw new Error('cancelled');
      }
      const st: fileIo.Stat = await fileIo.stat(file.fd);
      if (this.released) {
        throw new Error('cancelled');
      }
      player = await media.createAVPlayer();
      this.av = player;
      if (this.released) {
        throw new Error('cancelled');
      }
      this.registerEvents(player);
      player.surfaceId = surfaceId;
      const src: media.AVFileDescriptor = { fd: file.fd, offset: 0, length: st.size };
      player.fdSrc = src;
      await player.prepare();
      if (this.released) {
        throw new Error('cancelled');
      }
      this.durationMs = player.duration > 0 ? player.duration : clip.durationMs;
      this.positionMs = 0;
      if (!this.suspended) {
        this.startTick();
      }
      Diag.i(TAG, `attached ${clip.name} ${this.durationMs}ms fps=${this.fps}`);
      return '';
    } catch (e) {
      const cancelled: boolean = this.released;
      Diag.e(TAG, `attach ${cancelled ? 'cancelled' : 'failed'} ${clip.path}: ${e}`);
      await this.cleanupAttachFailure(player);
      return cancelled ? 'cancelled' : `attach-failed:${e}`;
    }
  }

  private registerEvents(player: media.AVPlayer): void {
    player.on('stateChange', (state: media.AVPlayerState, _reason: media.StateChangeReason): void => {
      if (this.released || this.av !== player) {
        return;
      }
      const wasPlaying: boolean = this.playing;
      this.state = state;
      this.playing = state === 'playing';
      if (state === 'completed' && wasPlaying && !this.suspended && !this.seeking &&
        this.pendingStepMs === undefined && !this.abWrapInFlight && !this.seekInFlight &&
        this.ab.valid && this.ab.loopEnabled) {
        // 仅自然播放到片尾时回 A；暂停/逐帧/拖动/隐藏/已在回绕中一律不自动拉起
        this.wrapToA();
        this.playing = true;
        player.play().catch((e: Object): void => {
          this.playing = false;
        });
      }
    });
    player.on('timeUpdate', (time: number): void => {
      if (this.released || this.av !== player) {
        return;
      }
      if (Number.isFinite(time) && time >= 0) {
        this.positionMs = time;
      }
    });
    player.on('durationUpdate', (duration: number): void => {
      if (this.released || this.av !== player) {
        return;
      }
      if (Number.isFinite(duration) && duration > 0) {
        this.durationMs = duration;
      }
    });
    player.on('seekDone', (time: number): void => {
      if (this.released || this.av !== player) {
        return;
      }
      const matchesWrap: boolean = this.wrapSeekTarget < 0 ||
        (Number.isFinite(time) && Math.abs(time - this.wrapSeekTarget) <= 1);
      if (this.lastSeekWasWrap && matchesWrap) {
        this.abWrapInFlight = false;
        this.lastSeekWasWrap = false;
        this.wrapSeekTarget = -1;
        this.seekInFlight = false;
        if (this.abUnlockTimer >= 0) {
          clearTimeout(this.abUnlockTimer);
          this.abUnlockTimer = -1;
        }
      } else if (!this.lastSeekWasWrap) {
        this.seekInFlight = false;
      }
    });
    player.on('speedDone', (speed: number): void => {
      if (this.released || this.av !== player) {
        return;
      }
      this.actualSpeed = PlayerEngine.tierFromEnum(speed);
    });
    player.on('error', (err: BusinessError): void => {
      if (this.released || this.av !== player) {
        return;
      }
      this.error = `engine-error:${err.code}:${err.message}`;
      this.playing = false;
      Diag.e(TAG, `player error ${err.code} ${err.message}`);
    });
  }

  private startTick(): void {
    if (this.released) {
      return;
    }
    this.stopTick();
    this.tickTimer = setInterval((): void => {
      this.onTick();
    }, TICK_MS);
  }

  /** 页面隐藏：停轮询（播放器状态保留；attach 完成时也不得重启） */
  suspendTick(): void {
    this.suspended = true;
    this.stopTick();
  }

  /** 页面重新可见：恢复轮询（release 后不复活） */
  resumeTick(): void {
    this.suspended = false;
    if (!this.released && this.av !== undefined) {
      this.startTick();
    }
  }

  private stopTick(): void {
    if (this.tickTimer >= 0) {
      clearInterval(this.tickTimer);
      this.tickTimer = -1;
    }
  }

  private onTick(): void {
    const player: media.AVPlayer | undefined = this.av;
    if (this.released || player === undefined) {
      return;
    }
    const pos: number = player.currentTime;
    if (Number.isFinite(pos) && pos >= 0) {
      this.positionMs = pos;
    }
    const dur: number = player.duration;
    if (Number.isFinite(dur) && dur > 0) {
      this.durationMs = dur;
    } else if (this.fallbackDurationMs > 0) {
      this.durationMs = this.fallbackDurationMs;
    }
    if (this.pendingStepMs !== undefined) {
      const frameMs: number = 1000 / Math.max(1, this.fps);
      const landed: boolean = Math.abs(this.positionMs - this.pendingStepMs) <= frameMs / 2;
      if (landed || this.pendingStepTicks >= 3) {
        this.pendingStepMs = undefined;
      } else {
        this.pendingStepTicks++;
      }
    }
    const seekStale: boolean = this.seekInFlight && (Date.now() - this.seekInFlightAt > 800);
    if (this.ab.valid && this.ab.loopEnabled && this.playing && !this.seeking &&
      (!this.seekInFlight || seekStale) &&
      this.pendingStepMs === undefined && this.positionMs >= this.ab.bMs && !this.abWrapInFlight) {
      this.wrapToA();
    }
  }

  /** AB 回绕到 A：标记回绕 seek 目标并用 500ms 兜底解锁（seekDone 匹配目标时提前解锁） */
  private wrapToA(): void {
    this.abWrapInFlight = true;
    this.lastSeekWasWrap = true;
    this.wrapSeekTarget = this.ab.aMs;
    this.seekInternal(this.ab.aMs, true);
    if (this.abUnlockTimer >= 0) {
      clearTimeout(this.abUnlockTimer);
    }
    this.abUnlockTimer = setTimeout((): void => {
      this.abWrapInFlight = false;
      this.lastSeekWasWrap = false;
      this.wrapSeekTarget = -1;
      this.abUnlockTimer = -1;
    }, 500);
  }

  private seekInternal(ms: number, isWrap: boolean = false): void {
    const player: media.AVPlayer | undefined = this.av;
    if (this.released || player === undefined) {
      return;
    }
    try {
      player.seek(ms, media.SeekMode.SEEK_CLOSEST);
      if (!isWrap) {
        this.seekInFlight = true; // 用户 seek 在途：回绕让路
        this.seekInFlightAt = Date.now();
      }
    } catch (e) {
      Diag.w(TAG, `seek ${ms} failed: ${e}`);
    }
  }

  /** 进度条 Begin/Moving = true、End = false：拖动期间不触发 AB 回绕 */
  setSeeking(active: boolean): void {
    this.seeking = active;
  }

  playPause(): void {
    const player: media.AVPlayer | undefined = this.av;
    if (this.released || player === undefined) {
      return;
    }
    if (this.state === 'completed') {
      this.pendingStepMs = undefined;
      this.cancelWrap();
      // 播完时仅在 AB 循环开启时回 A，否则从 0 重播
      this.seekInternal(this.ab.valid && this.ab.loopEnabled ? this.ab.aMs : 0);
      this.playing = true;
      player.play().catch((e: Object): void => {
        this.playing = false;
        Diag.w(TAG, `replay failed: ${e}`);
      });
      return;
    }
    if (this.playing) {
      this.playing = false;
      player.pause().catch((e: Object): void => {
        Diag.w(TAG, `pause failed: ${e}`);
      });
    } else {
      this.playing = true;
      player.play().catch((e: Object): void => {
        this.playing = false;
        Diag.w(TAG, `play failed: ${e}`);
      });
    }
  }

  seekTo(ms: number): void {
    if (this.released || this.av === undefined) {
      return;
    }
    const target: number = Math.max(0, this.durationMs > 0 ? Math.min(ms, this.durationMs) : ms);
    this.pendingStepMs = undefined;
    this.cancelWrap(); // 用户 seek 优先，作废在途回绕
    this.positionMs = target; // 拖动即时反馈（轮询随后回读校正）
    this.seekInternal(target);
  }

  /**
   * 逐帧：先等 pause 落地，再以 currentPos（pending 优先，含有限性/负值保护）为锚点；
   * 返回对齐后的目标（取消/异常时返回当前位置）。
   */
  async step(fwd: boolean): Promise<number> {
    const player: media.AVPlayer | undefined = this.av;
    if (this.released || player === undefined) {
      return this.positionMs;
    }
    if (this.playing) {
      this.playing = false;
      try {
        await player.pause();
      } catch (e) {
      }
    }
    if (this.released || this.av !== player) {
      return this.positionMs;
    }
    const base: number = this.currentPos();
    if (!Number.isFinite(base) || base < 0) {
      return this.positionMs;
    }
    this.cancelWrap();
    const frameMs: number = 1000 / Math.max(1, this.fps);
    const target: number = FrameStep.next(this.pendingStepMs, base, frameMs, fwd, this.durationMs);
    if (!Number.isFinite(target)) {
      return this.positionMs;
    }
    this.seekInternal(target);
    this.pendingStepMs = target;
    this.pendingStepTicks = 0;
    return target;
  }

  private cancelWrap(): void {
    this.abWrapInFlight = false;
    this.lastSeekWasWrap = false;
    this.wrapSeekTarget = -1;
    if (this.abUnlockTimer >= 0) {
      clearTimeout(this.abUnlockTimer);
      this.abUnlockTimer = -1;
    }
  }

  setFps(fps: number): void {
    if (fps > 0) {
      this.fps = fps;
      this.pendingStepMs = undefined;
      this.pendingStepTicks = 0;
    }
  }

  setSpeed(tier: number): void {
    const normalized: number = PlayerSpeedTiers.normalize(tier);
    this.speed = normalized;
    const player: media.AVPlayer | undefined = this.av;
    if (this.released || player === undefined) {
      return;
    }
    try {
      player.setSpeed(PlayerEngine.speedToEnum(normalized));
    } catch (e) {
      Diag.w(TAG, `setSpeed ${normalized} failed: ${e}`);
    }
  }

  /** 打点 A：已有 B 且 B <= A 时清 B 并关循环 */
  markA(): boolean {
    if (this.released) {
      return false;
    }
    const pos: number = this.currentPos();
    const b: number = this.ab.bMs > pos ? this.ab.bMs : -1;
    const loop: boolean = this.ab.loopEnabled && b > 0 && b > pos;
    this.ab = new AbRange(pos, b, loop);
    return true;
  }

  /** 打点 B：需已有 A 且 B > A，成功自动开循环 */
  markB(): boolean {
    if (this.released) {
      return false;
    }
    const pos: number = this.currentPos();
    if (this.ab.aMs < 0 || pos <= this.ab.aMs) {
      return false;
    }
    this.ab = new AbRange(this.ab.aMs, pos, true);
    return true;
  }

  toggleAbLoop(): boolean {
    if (this.released || !this.ab.valid) {
      return false;
    }
    this.ab = new AbRange(this.ab.aMs, this.ab.bMs, !this.ab.loopEnabled);
    return true;
  }

  clearAb(): void {
    this.ab = new AbRange();
    this.cancelWrap();
  }

  isReady(): boolean {
    if (this.released || this.av === undefined) {
      return false;
    }
    return this.state === 'prepared' || this.state === 'playing' || this.state === 'paused' ||
      this.state === 'completed';
  }

  /** surface 重建后恢复会话（位置/AB/倍速）；引擎需已 attach/prepare */
  restoreSession(positionMs: number, ab: AbRange, speed: number): void {
    if (this.released || this.av === undefined) {
      return;
    }
    const target: number = Math.max(0, this.durationMs > 0 ? Math.min(positionMs, this.durationMs) : positionMs);
    this.positionMs = target;
    this.ab = new AbRange(ab.aMs, ab.bMs, ab.loopEnabled && ab.valid);
    this.setSpeed(speed);
    this.seekInternal(target);
  }

  /** 打点/回绕用位置：步进目标未落地时以目标为准 */
  currentPos(): number {
    if (this.pendingStepMs !== undefined) {
      return this.pendingStepMs;
    }
    const player: media.AVPlayer | undefined = this.av;
    if (player !== undefined && Number.isFinite(player.currentTime) && player.currentTime >= 0) {
      return player.currentTime;
    }
    return this.positionMs;
  }

  async release(): Promise<void> {
    if (this.released) {
      return;
    }
    this.released = true;
    this.stopTick();
    if (this.abUnlockTimer >= 0) {
      clearTimeout(this.abUnlockTimer);
      this.abUnlockTimer = -1;
    }
    const player: media.AVPlayer | undefined = this.av;
    this.av = undefined;
    if (player !== undefined) {
      try {
        player.off('stateChange');
        player.off('timeUpdate');
        player.off('durationUpdate');
        player.off('seekDone');
        player.off('speedDone');
        player.off('error');
      } catch (e) {
      }
      try {
        await player.release();
      } catch (e) {
        Diag.w(TAG, `release player failed: ${e}`);
      }
    }
    this.closeFd();
    Diag.i(TAG, 'released');
  }

  private async cleanupAttachFailure(playerHint?: media.AVPlayer): Promise<void> {
    this.stopTick();
    const player: media.AVPlayer | undefined = playerHint !== undefined ? playerHint : this.av;
    this.av = undefined;
    if (player !== undefined) {
      try {
        player.off('stateChange');
        player.off('timeUpdate');
        player.off('durationUpdate');
        player.off('seekDone');
        player.off('speedDone');
        player.off('error');
      } catch (e) {
      }
      try {
        await player.release();
      } catch (e) {
      }
    }
    this.closeFd();
    this.released = true;
  }

  private closeFd(): void {
    if (this.fd >= 0) {
      try {
        fileIo.closeSync(this.fd);
      } catch (e) {
      }
      this.fd = -1;
    }
  }

  private static speedToEnum(tier: number): media.PlaybackSpeed {
    if (tier <= 0.125) {
      return media.PlaybackSpeed.SPEED_FORWARD_0_125_X;
    }
    if (tier <= 0.25) {
      return media.PlaybackSpeed.SPEED_FORWARD_0_25_X;
    }
    if (tier <= 0.5) {
      return media.PlaybackSpeed.SPEED_FORWARD_0_50_X;
    }
    if (tier <= 0.75) {
      return media.PlaybackSpeed.SPEED_FORWARD_0_75_X;
    }
    return media.PlaybackSpeed.SPEED_FORWARD_1_00_X;
  }

  private static tierFromEnum(speed: number): number {
    switch (speed) {
      case media.PlaybackSpeed.SPEED_FORWARD_0_125_X:
        return 0.125;
      case media.PlaybackSpeed.SPEED_FORWARD_0_25_X:
        return 0.25;
      case media.PlaybackSpeed.SPEED_FORWARD_0_50_X:
        return 0.5;
      case media.PlaybackSpeed.SPEED_FORWARD_0_75_X:
        return 0.75;
      default:
        return PlayerSpeedTiers.normalize(1.0);
    }
  }
}
