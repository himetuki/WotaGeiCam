/**
 * GL-4 GPU 录制门面（CodecRecorder + 光弧链在线版 + 3.5GiB 分段轮转的 ArkTS 侧）：
 * 文件句柄归 ArkTS（fileIo.openSync 取 fd 传 native；native 绝不 close，停录后本类关闭并 stat），
 * native 侧 OH_VideoEncoder(surface 输入) + OH_AVMuxer(fd, MPEG_4) 直写，视频-only。
 *
 * 分段语义（登记）：
 * - 轮转 = 停当前段（EOS 收尾）→ 新文件 → 重建编码器/EGL 编码面 → 新段独立 MP4/独立时间轴；
 * - 段间短停摆按 lostMs 记账；每段独立 VideoStore.displayName()，段序号只进 parts/segments/日志；
 * - .drops.json 只写首段旁，segments 覆盖全部段；drops 只保留最后一段位次（上游已知跨段失真）；
 * - FrameLedger 逐段成文（partIndex），cameraFrames 仍为到达上界；
 * - 阈值默认 floor(3.5GiB × 0.98)，提供 8MB 测试阈值入口；AVRecorder 分段留白。
 *
 * 单段路径与上一版行为一致：不轮转时 parts 恒 1，sidecar segments 一条 segment=0/first=0/count。
 */
import { fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { ArcDropLog, ArcDropSegment, DropMark } from '../record/ArcDropLog';
import {
  FrameCounterSnapshot, FrameLedger, FrameLedgerReport, frameLedgerLine, frameSessionSummary
} from '../record/FrameLedger';
import { WOTA_RECORD_TAG } from '../record/RecordResult';
import { SegmentPolicy } from '../record/SegmentPolicy';
import { VideoStore } from '../record/VideoStore';

/** libentry.so 录制出口（camera/GlLibBridge 的导出子集） */
export interface GlRecordLib {
  startGpuRecord: (fd: number, width: number, height: number, fps: number, bitrate: number,
    arcMode: number, arcDstFps: number, deadband: number) => string;
  stopGpuRecord: () => string;
  isGpuRecording: () => boolean;
  getGpuRecordError: () => string;
  flushArcPending: () => number;
  drainArcDrops: () => string;
  measuredArcSrcFps: () => number;
  getGpuFrameCounters: () => string;
  getArcEffectiveMode: () => number;
  getGpuWrittenBytes: () => number;
  getGpuHealth: () => string;
}

class GpuCounters {
  glCallbacks: number;
  glConsumed: number;
  swapOk: number;
  swapFail: number;
  arcDropped: number;

  constructor(glCallbacks: number, glConsumed: number, swapOk: number, swapFail: number, arcDropped: number) {
    this.glCallbacks = glCallbacks;
    this.glConsumed = glConsumed;
    this.swapOk = swapOk;
    this.swapFail = swapFail;
    this.arcDropped = arcDropped;
  }
}

function parseCounters(raw: string): GpuCounters | undefined {
  try {
    const parts: Array<string> = raw.split(',');
    if (parts.length !== 5) {
      return undefined;
    }
    const v: Array<number> = [];
    for (const p of parts) {
      const n: number = Number(p);
      if (!Number.isInteger(n) || n < 0) {
        return undefined;
      }
      v.push(n);
    }
    return new GpuCounters(v[0], v[1], v[2], v[3], v[4]);
  } catch (e) {
    return undefined;
  }
}

function parseDrops(raw: string): Array<DropMark> {
  const marks: Array<DropMark> = [];
  if (raw === '') {
    return marks;
  }
  try {
    for (const pair of raw.split(';')) {
      const kv: Array<string> = pair.split(',');
      if (kv.length !== 2) {
        continue;
      }
      const out: number = Number(kv[0]);
      const n: number = Number(kv[1]);
      if (Number.isInteger(out) && Number.isInteger(n) && out >= 0 && n >= 0) {
        marks.push(new DropMark(out, n));
      }
    }
  } catch (e) {
    return marks;
  }
  return marks;
}

function parseSamples(status: string): number {
  try {
    const m: RegExpExecArray | null = new RegExp('samples=(\\d+)').exec(status);
    return m === null ? 0 : Number(m[1]);
  } catch (e) {
    return 0;
  }
}

function delta(now: number, start: number): number {
  return now > start ? now - start : 0;
}

class StartParams {
  width: number;
  height: number;
  fps: number;
  bitrate: number;
  arcMode: number;
  arcDstFps: number;
  deadband: number;

  constructor(width: number, height: number, fps: number, bitrate: number, arcMode: number,
    arcDstFps: number, deadband: number) {
    this.width = width;
    this.height = height;
    this.fps = fps;
    this.bitrate = bitrate;
    this.arcMode = arcMode;
    this.arcDstFps = arcDstFps;
    this.deadband = deadband;
  }
}

class GpuPart {
  partIndex: number;
  path: string;
  bytes: number;
  durationMs: number;
  samples: number;
  glOutFrames: number;
  drops: Array<DropMark>;
  lostMs: number;

  constructor(partIndex: number, path: string, bytes: number, durationMs: number, samples: number,
    glOutFrames: number, drops: Array<DropMark>, lostMs: number) {
    this.partIndex = partIndex;
    this.path = path;
    this.bytes = bytes;
    this.durationMs = durationMs;
    this.samples = samples;
    this.glOutFrames = glOutFrames;
    this.drops = drops;
    this.lostMs = lostMs;
  }
}

export class GlRecorder {
  private static activePath: string = '';
  private static segmentLimit: number = SegmentPolicy.segmentLimitBytes();

  static getActiveRecordingPath(): string {
    return GlRecorder.activePath;
  }

  static setSegmentLimitBytesForTest(bytes: number): void {
    GlRecorder.segmentLimit = bytes > 0 ? Math.floor(bytes) : SegmentPolicy.segmentLimitBytes();
  }

  static getSegmentLimitBytes(): number {
    return GlRecorder.segmentLimit;
  }

  static resetSegmentLimitBytes(): void {
    GlRecorder.segmentLimit = SegmentPolicy.segmentLimitBytes();
  }

  private fd: number = -1;
  private path: string = '';
  private lib: GlRecordLib | undefined = undefined;
  private startParams: StartParams | undefined = undefined;
  private parts: Array<GpuPart> = [];
  private partIndex: number = 0;
  private currentLostMs: number = 0;
  private segmentStartedAtMs: number = 0;
  private sessionStartedAtMs: number = 0;
  private lastSegmentEndMs: number = 0;
  private ledgerStart: FrameCounterSnapshot | undefined = undefined;
  private countersStart: GpuCounters | undefined = undefined;
  private lastStatus: string = '';
  private lastNativeError: string = '';
  private lastErrorReadOk: boolean = true;
  private lastDropsReadOk: boolean = true;
  private lastEffectiveMode: number = 0;
  private lastMeasuredSrcFps: number = 0;
  private lastFlushed: number = 0;
  private sessionError: string = '';

  start(ctx: common.Context, lib: GlRecordLib, width: number, height: number, fps: number,
    bitrate: number, arcMode: number, arcDstFps: number, deadband: number): string {
    if (this.fd >= 0) {
      return 'busy';
    }
    this.parts = [];
    this.partIndex = 0;
    this.currentLostMs = 0;
    this.sessionError = '';
    this.lastStatus = '';
    this.lastNativeError = '';
    this.lastErrorReadOk = true;
    this.lastDropsReadOk = true;
    this.lastEffectiveMode = arcMode;
    this.lastMeasuredSrcFps = 0;
    this.lastFlushed = 0;
    this.startParams = new StartParams(width, height, fps, bitrate, arcMode, arcDstFps, deadband);
    this.lib = lib;
    this.sessionStartedAtMs = Date.now();
    Diag.clearFrameAccount();
    FrameLedger.beginRecordingEpoch();
    const error: string = this.openSegment(ctx, lib, 0, 0);
    if (error !== '') {
      FrameLedger.endRecordingEpoch();
      const detail: string = error;
      this.clearSession();
      return detail;
    }
    return '';
  }

  private openSegment(ctx: common.Context, lib: GlRecordLib, index: number, lostMs: number): string {
    if (this.startParams === undefined) {
      this.sessionError = 'no-start-params';
      return this.sessionError;
    }
    const low: string | undefined = VideoStore.checkFreeSpace(ctx);
    if (low !== undefined) {
      this.sessionError = low;
      return low;
    }
    const name: string = VideoStore.displayName();
    const path: string = VideoStore.pathFor(ctx, name);
    let file: fileIo.File;
    try {
      file = fileIo.openSync(path,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    } catch (e) {
      this.sessionError = `open-failed:${e}`;
      return this.sessionError;
    }
    const params: StartParams = this.startParams;
    let error: string = '';
    try {
      error = lib.startGpuRecord(file.fd, params.width, params.height, params.fps, params.bitrate,
        params.arcMode, params.arcDstFps, params.deadband);
    } catch (e) {
      error = `napi-failed:${e} path=${path}`;
      Diag.e(WOTA_RECORD_TAG, `napi start failed, keep for triage: ${path}: ${e}`);
    }
    if (error !== '') {
      const napiFailed: boolean = error.indexOf('napi-failed') === 0;
      try {
        lib.stopGpuRecord();
      } catch (e) {
      }
      try {
        fileIo.closeSync(file.fd);
      } catch (e) {
      }
      // 契约恢复：napi-failed 保留文件待 triage；native 明确失败才 discard
      if (!napiFailed) {
        VideoStore.discard(path);
      }
      this.sessionError = error;
      return error;
    }
    this.fd = file.fd;
    this.path = path;
    GlRecorder.activePath = path;
    this.partIndex = index;
    this.currentLostMs = lostMs;
    this.segmentStartedAtMs = Date.now();
    this.ledgerStart = FrameLedger.snapshot();
    try {
      this.countersStart = parseCounters(lib.getGpuFrameCounters());
    } catch (e) {
      this.countersStart = undefined;
    }
    return '';
  }

  private captureSegment(lib: GlRecordLib): GpuPart | undefined {
    if (this.fd < 0 || this.startParams === undefined) {
      return undefined;
    }
    const params: StartParams = this.startParams;
    const fd: number = this.fd;
    const path: string = this.path;
    const partIndex: number = this.partIndex;
    const lostMs: number = this.currentLostMs;
    const segmentStart: number = this.segmentStartedAtMs;
    const ledgerStart: FrameCounterSnapshot | undefined = this.ledgerStart;
    const countersStart: GpuCounters | undefined = this.countersStart;
    this.lastSegmentEndMs = Date.now();

    let flushed: number = 0;
    if (params.arcMode === 1) {
      try {
        flushed = lib.flushArcPending();
      } catch (e) {
        flushed = 0;
      }
    }
    let endSnap: FrameCounterSnapshot | undefined = undefined;
    try {
      endSnap = FrameLedger.snapshot();
    } catch (e) {
      endSnap = undefined;
    }
    let endCounters: GpuCounters | undefined = undefined;
    try {
      endCounters = parseCounters(lib.getGpuFrameCounters());
    } catch (e) {
      endCounters = undefined;
    }
    let status: string = '';
    try {
      status = lib.stopGpuRecord();
    } catch (e) {
      status = `napi-failed:${e}`;
    }
    let errorReadOk: boolean = true;
    let dropsReadOk: boolean = true;
    let nativeError: string = '';
    try {
      nativeError = lib.getGpuRecordError();
    } catch (e) {
      errorReadOk = false;
    }
    let dropsRaw: string = '';
    try {
      dropsRaw = lib.drainArcDrops();
    } catch (e) {
      dropsReadOk = false;
    }
    let measuredSrcFps: number = 0;
    try {
      measuredSrcFps = lib.measuredArcSrcFps();
    } catch (e) {
      measuredSrcFps = 0;
    }
    let effectiveMode: number = params.arcMode;
    try {
      effectiveMode = lib.getArcEffectiveMode();
    } catch (e) {
      effectiveMode = params.arcMode;
    }
    this.fd = -1;
    if (GlRecorder.activePath === path) {
      GlRecorder.activePath = '';
    }
    this.path = '';
    this.ledgerStart = undefined;
    this.countersStart = undefined;
    try {
      fileIo.closeSync(fd);
    } catch (e) {
    }
    const bytes: number = VideoStore.sizeOf(path);
    const samples: number = parseSamples(status);
    const glOutFrames: number = (endCounters !== undefined && countersStart !== undefined)
      ? delta(endCounters.swapOk, countersStart.swapOk) : samples;
    const drops: Array<DropMark> = parseDrops(dropsRaw);
    const durationMs: number = this.lastSegmentEndMs - segmentStart;
    const part: GpuPart = new GpuPart(partIndex, path, bytes, durationMs, samples, glOutFrames,
      drops, lostMs);
    this.parts.push(part);
    if (ledgerStart !== undefined && countersStart !== undefined &&
      endSnap !== undefined && endCounters !== undefined) {
      try {
        const cameraDelta: number = delta(endSnap.cameraStarted, ledgerStart.cameraStarted);
        const glCbDelta: number = delta(endCounters.glCallbacks, countersStart.glCallbacks);
        const cameraFrames: number = Math.max(cameraDelta, glCbDelta);
        const report: FrameLedgerReport = new FrameLedgerReport(
          partIndex, true, durationMs, cameraFrames, glCbDelta,
          delta(endCounters.glConsumed, countersStart.glConsumed),
          delta(endCounters.swapOk, countersStart.swapOk),
          delta(endCounters.swapFail, countersStart.swapFail),
          samples, 0, 0,
          delta(endCounters.arcDropped, countersStart.arcDropped),
          lostMs, SegmentPolicy.rotateFramesUpper(lostMs, params.fps));
        const line: string = frameLedgerLine(report);
        Diag.i(WOTA_RECORD_TAG, line);
        Diag.frameAccount(line);
      } catch (e) {
        Diag.w(WOTA_RECORD_TAG, `frame ledger failed part=${partIndex}: ${e}`);
      }
    }
    this.lastStatus = status;
    this.lastNativeError = nativeError;
    this.lastErrorReadOk = errorReadOk;
    this.lastDropsReadOk = dropsReadOk;
    this.lastEffectiveMode = effectiveMode;
    this.lastMeasuredSrcFps = measuredSrcFps;
    this.lastFlushed = flushed;
    return part;
  }

  /** 页面 500ms 轮询：达到阈值 → 收当前段 → 新段；返回 ''/rotated:N/error:<code> */
  maybeRotate(ctx: common.Context, lib: GlRecordLib): string {
    if (this.fd < 0 || this.startParams === undefined) {
      return '';
    }
    let written: number = 0;
    try {
      written = lib.getGpuWrittenBytes();
    } catch (e) {
      return '';
    }
    if (!SegmentPolicy.shouldRotate(written, GlRecorder.segmentLimit)) {
      return '';
    }
    const endMs: number = Date.now();
    const part: GpuPart | undefined = this.captureSegment(lib);
    if (part === undefined) {
      this.sessionError = 'rotate-capture';
      return 'error:rotate-capture';
    }
    // 收段失败（写失败/引擎错误）不得静默开新段：保留已完成段并让页面走停止路径
    if (!this.lastStatus.startsWith('ok') || this.lastNativeError !== '') {
      this.sessionError = this.lastNativeError !== ''
        ? `native-${this.lastNativeError}` : `native-${this.lastStatus}`;
      return `error:${this.sessionError}`;
    }
    this.currentLostMs = Math.max(0, Date.now() - endMs);
    const nextIndex: number = this.parts.length;
    const error: string = this.openSegment(ctx, lib, nextIndex, this.currentLostMs);
    if (error !== '') {
      return `error:${error}`;
    }
    return `rotated:${this.parts.length}`;
  }

  stop(): string {
    const lib: GlRecordLib | undefined = this.lib;
    if (lib === undefined) {
      return 'idle';
    }
    if (this.fd >= 0) {
      this.captureSegment(lib);
    }
    FrameLedger.endRecordingEpoch();
    const params: StartParams | undefined = this.startParams;
    const parts: Array<GpuPart> = this.parts;
    if (parts.length === 0) {
      const error: string = this.sessionError !== '' ? this.sessionError
        : (this.lastStatus !== '' ? this.lastStatus : 'idle');
      this.clearSession();
      return error;
    }
    const counts: Array<number> = [];
    const losts: Array<number> = [];
    let totalBytes: number = 0;
    let totalSamples: number = 0;
    for (const part of parts) {
      // segments/frameSessionSummary 口径 = muxer 视频样本数（上游 VideoSegment.count）
      counts.push(part.samples);
      losts.push(part.lostMs);
      totalBytes += part.bytes;
      totalSamples += part.samples;
    }
    try {
      const sessionLine: string = frameSessionSummary(counts, losts);
      if (sessionLine !== '') {
        Diag.i(WOTA_RECORD_TAG, sessionLine);
      }
    } catch (e) {
    }
    const firstPath: string = parts[0].path;
    const lastPart: GpuPart = parts[parts.length - 1];
    const status: string = this.sessionError !== '' ? this.sessionError : this.lastStatus;
    let sidecar: string = 'na';
    if (params !== undefined && params.arcMode !== 0) {
      if (!this.lastErrorReadOk || !this.lastDropsReadOk) {
        sidecar = 'skipped(diag)';
      } else if (this.lastNativeError === '' && status.startsWith('ok') && firstPath !== '') {
        try {
          const modeName: string = params.arcMode === 1 ? 'mend' : 'drop';
          const segments: Array<ArcDropSegment> = SegmentPolicy.buildDropSegments(counts, losts);
          const ok: boolean = ArcDropLog.writeTo(firstPath,
            new ArcDropLog(modeName, params.arcDstFps, lastPart.drops, segments));
          sidecar = ok ? 'ok' : 'fail';
          if (!ok) {
            Diag.w(WOTA_RECORD_TAG, `drops sidecar write failed: ${firstPath}`);
          }
        } catch (e) {
          sidecar = 'fail';
          Diag.w(WOTA_RECORD_TAG, `drops sidecar failed: ${e}`);
        }
      }
    }
    const durationMs: number = Date.now() - this.sessionStartedAtMs;
    const arcName: string = params === undefined || params.arcMode === 0
      ? 'off' : (params.arcMode === 1 ? 'mend' : 'drop');
    const tail: string = this.lastNativeError !== '' && status.indexOf(this.lastNativeError) < 0
      ? ` nativeError=${this.lastNativeError}` : '';
    const summary: string = `path=${firstPath} bytes=${totalBytes} samples=${totalSamples} duration=${durationMs}ms status=${status}` +
      ` parts=${parts.length} totalBytes=${totalBytes} arc=${arcName} effective=${this.lastEffectiveMode}` +
      ` flushed=${this.lastFlushed} drops=${lastPart.drops.length} sidecar=${sidecar}` +
      ` measuredSrcFps=${this.lastMeasuredSrcFps}(lastSegment) segments=${SegmentPolicy.segmentSummary(counts)}${tail}`;
    this.clearSession();
    return summary;
  }

  private clearSession(): void {
    if (GlRecorder.activePath !== '') {
      GlRecorder.activePath = '';
    }
    this.fd = -1;
    this.path = '';
    this.lib = undefined;
    this.startParams = undefined;
    this.parts = [];
    this.partIndex = 0;
    this.currentLostMs = 0;
    this.segmentStartedAtMs = 0;
    this.sessionStartedAtMs = 0;
    this.lastSegmentEndMs = 0;
    this.ledgerStart = undefined;
    this.countersStart = undefined;
    this.lastStatus = '';
    this.lastNativeError = '';
    this.sessionError = '';
  }

  isRecording(): boolean {
    return this.fd >= 0;
  }

  getPartsCount(): number {
    return this.parts.length;
  }
}
