/**
 * GL-4 GPU 录制门面（CodecRecorder + 光弧链在线版的 ArkTS 侧）：
 * 文件句柄归 ArkTS（fileIo.openSync 取 fd 传 native；native 绝不 close，停录后本类关闭并 stat 大小），
 * native 侧 OH_VideoEncoder(surface 输入) + OH_AVMuxer(fd, MPEG_4) 直写，视频-only、单段。
 *
 * 光弧链（本批）：起录时把 arcMode/arcDstFps/deadband 交给 native；停录严格顺序 =
 * flushArcPending（MEND 滞留帧）→ stopGpuRecord → drainArcDrops/帧计数/实测源帧率 →
 * close fd + stat → FrameLedger 成文进 diag → 写 .drops.json sidecar。
 * 旋转元数据由 native muxer SetRotation(normalizeQuarter(sensor−display)) 写入容器。
 *
 * 边界（登记）：无音频轨、无分段轮转、无参数下发（尺寸/码率由页面固定档传入）、无 PTS 网格化；
 * 输出落应用私有 filesDir/videos（VideoStore），sidecar 同目录同名 .drops.json。
 */
import { fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { ArcDropLog, ArcDropSegment, DropMark } from '../record/ArcDropLog';
import { FrameCounterSnapshot, FrameLedger, FrameLedgerReport, frameLedgerLine } from '../record/FrameLedger';
import { WOTA_RECORD_TAG } from '../record/RecordResult';
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
}

/** 原生五格计数快照（getGpuFrameCounters 的解析结果） */
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

/** "a,b,c,d,e" → 五格；任何一格缺失/非整数返回 undefined（解析失败不得抛） */
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

/** "k,n;k,n" → DropMark[]；空串 = []; 坏格跳过（sidecar 解析失败不得抛） */
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

/** stop 摘要里的 "samples=N"（无则 0） */
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

export class GlRecorder {
  private fd: number = -1;
  private path: string = '';
  private startedAtMs: number = 0;
  private lib: GlRecordLib | undefined = undefined;
  private arcMode: number = 0;
  private arcDstFps: number = 0;
  private ledgerStart: FrameCounterSnapshot | undefined = undefined;
  private countersStart: GpuCounters | undefined = undefined;

  /**
   * 起录：空串 = 成功；非空 = 机器码（LOW_STORAGE:<mb> / open-failed / napi-failed / native 各步机器码）。
   * 失败路径：native 已内部回收（native 错误码）→ close fd + discard 空文件；
   * napi 异常（start 可能已部分执行）→ 先 best-effort stop 再 close，文件保留待 triage。
   */
  start(ctx: common.Context, lib: GlRecordLib, width: number, height: number, fps: number,
    bitrate: number, arcMode: number, arcDstFps: number, deadband: number): string {
    if (this.fd >= 0) {
      return 'busy';
    }
    // 起录余量预检（VideoStore 现成能力；探测失败不拦录制，同上游口径）
    const low: string | undefined = VideoStore.checkFreeSpace(ctx);
    if (low !== undefined) {
      return low;
    }
    const name: string = VideoStore.displayName();
    const path: string = VideoStore.pathFor(ctx, name);
    let file: fileIo.File;
    try {
      file = fileIo.openSync(path,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    } catch (e) {
      return `open-failed:${e}`;
    }
    let error: string = '';
    try {
      error = lib.startGpuRecord(file.fd, width, height, fps, bitrate, arcMode, arcDstFps, deadband);
    } catch (e) {
      error = `napi-failed:${e}`;
    }
    if (error !== '') {
      const napiFailed: boolean = error.indexOf('napi-failed') === 0;
      if (napiFailed) {
        // 无法确认 native 是否已起录：先 best-effort 停（幂等），避免编码器继续写已关闭的 fd
        try {
          lib.stopGpuRecord();
        } catch (e) {
        }
      }
      try {
        fileIo.closeSync(file.fd);
      } catch (e) {
      }
      // 仅 native 明确失败（已自清理、未产出样本）时删除空文件；napi-failed 保留待 triage
      if (!napiFailed) {
        VideoStore.discard(path);
      }
      return error;
    }
    this.fd = file.fd;
    this.path = path;
    this.startedAtMs = Date.now();
    this.lib = lib;
    this.arcMode = arcMode;
    this.arcDstFps = arcDstFps;
    // 帧账起账：只清结构段计数，相机到达格来自 PreviewOutput.frameStart
    Diag.clearFrameAccount();
    this.ledgerStart = FrameLedger.snapshot();
    try {
      this.countersStart = parseCounters(lib.getGpuFrameCounters());
    } catch (e) {
      this.countersStart = undefined;
    }
    return '';
  }

  /**
   * 停录（顺序契约）：flush MEND 滞留帧 → 起停快照在阻塞式 stop 前冻结 → native stop →
   * 逐项取诊断（错误/drops/实测帧率/实际档）→ close fd + stat → FrameLedger 成文 → sidecar。
   * 帧账窗口：end 快照必须在 stopGpuRecord 之前取（EOS 等待期间 UI 阻塞，之后取会把尾段
   * 入队帧算进下一窗口/丢掉）；相机帧与 GL 计数因此同窗口对齐（审查 P3）。
   */
  stop(): string {
    const lib: GlRecordLib | undefined = this.lib;
    if (lib === undefined || this.fd < 0) {
      return 'idle';
    }
    let flushed: number = 0;
    if (this.arcMode === 1) {
      try {
        flushed = lib.flushArcPending();
      } catch (e) {
        flushed = 0;
      }
    }
    // 阻塞 stop 前冻结 end 快照（cameraStarted 与 native 五格）
    let endSnap: FrameCounterSnapshot | undefined = undefined;
    let endCounters: GpuCounters | undefined = undefined;
    try {
      endSnap = FrameLedger.snapshot();
    } catch (e) {
      endSnap = undefined;
    }
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
    // 诊断逐项取：任一异常不吞后续项；失败记入 diagError 并进摘要
    let diagError: string = '';
    let nativeError: string = '';
    try {
      nativeError = lib.getGpuRecordError();
    } catch (e) {
      diagError = diagError === '' ? `getGpuRecordError:${e}` : diagError;
    }
    let dropsRaw: string = '';
    try {
      dropsRaw = lib.drainArcDrops();
    } catch (e) {
      diagError = diagError === '' ? `drainArcDrops:${e}` : diagError;
    }
    let measuredSrcFps: number = 0;
    try {
      measuredSrcFps = lib.measuredArcSrcFps();
    } catch (e) {
      diagError = diagError === '' ? `measuredArcSrcFps:${e}` : diagError;
    }
    let effectiveMode: number = this.arcMode;
    try {
      effectiveMode = lib.getArcEffectiveMode();
    } catch (e) {
      diagError = diagError === '' ? `getArcEffectiveMode:${e}` : diagError;
    }
    const fd: number = this.fd;
    const path: string = this.path;
    const arcMode: number = this.arcMode;
    const arcDstFps: number = this.arcDstFps;
    const startSnap: FrameCounterSnapshot | undefined = this.ledgerStart;
    const startCounters: GpuCounters | undefined = this.countersStart;
    const durationMs: number = Date.now() - this.startedAtMs;
    this.fd = -1;
    this.path = '';
    this.startedAtMs = 0;
    this.lib = undefined;
    this.arcMode = 0;
    this.arcDstFps = 0;
    this.ledgerStart = undefined;
    this.countersStart = undefined;
    try {
      fileIo.closeSync(fd);
    } catch (e) {
    }
    const bytes: number = VideoStore.sizeOf(path);
    const samples: number = parseSamples(status);
    // GL 输出帧数（编码 swap 差值；不含 muxer CSD，sidecar segments.count 用它）
    const glOutFrames: number = (endCounters !== undefined && startCounters !== undefined)
      ? delta(endCounters.swapOk, startCounters.swapOk) : samples;

    // ---- 帧账（混合口径）：cameraStarted 取 ArkTS 快照差；其余取原生五格差 ----
    if (startSnap !== undefined && startCounters !== undefined &&
      endSnap !== undefined && endCounters !== undefined) {
      try {
        const cameraDelta: number = delta(endSnap.cameraStarted, startSnap.cameraStarted);
        const glCbDelta: number = delta(endCounters.glCallbacks, startCounters.glCallbacks);
        // frameStart 不可用（注册失败/平台未派发）时以原生 GL 回调为上界（登记口径）
        const cameraFrames: number = cameraDelta > 0 ? cameraDelta : glCbDelta;
        const report: FrameLedgerReport = new FrameLedgerReport(
          0, true, durationMs,
          cameraFrames,
          glCbDelta,
          delta(endCounters.glConsumed, startCounters.glConsumed),
          delta(endCounters.swapOk, startCounters.swapOk),
          delta(endCounters.swapFail, startCounters.swapFail),
          samples,
          0, 0,
          delta(endCounters.arcDropped, startCounters.arcDropped),
          0, 0);
        const line: string = frameLedgerLine(report);
        Diag.i(WOTA_RECORD_TAG, line);
        Diag.frameAccount(line);
      } catch (e) {
        Diag.w(WOTA_RECORD_TAG, `frame ledger failed: ${e}`);
      }
    }

    // ---- .drops.json sidecar：请求弧模式 + native 无错误 + 成片 ok 才写 ----
    const marks: Array<DropMark> = parseDrops(dropsRaw);
    let sidecar: string = 'na';
    if (arcMode !== 0 && nativeError === '' && status.startsWith('ok') && path !== '') {
      try {
        const modeName: string = arcMode === 1 ? 'mend' : 'drop';
        const segments: Array<ArcDropSegment> = [new ArcDropSegment(0, 0, glOutFrames)];
        const ok: boolean = ArcDropLog.writeTo(path,
          new ArcDropLog(modeName, arcDstFps, marks, segments));
        sidecar = ok ? 'ok' : 'fail';
        if (!ok) {
          Diag.w(WOTA_RECORD_TAG, `drops sidecar write failed: ${path}`);
        }
      } catch (e) {
        sidecar = 'fail';
        Diag.w(WOTA_RECORD_TAG, `drops sidecar failed: ${e}`);
      }
    }
    const arcName: string = arcMode === 0 ? 'off' : (arcMode === 1 ? 'mend' : 'drop');
    const tail: string = nativeError !== '' && status.indexOf(nativeError) < 0
      ? ` nativeError=${nativeError}` : '';
    const diagTail: string = diagError === '' ? '' : ` diag=napi-failed(${diagError})`;
    return `path=${path} bytes=${bytes} duration=${durationMs}ms status=${status}` +
      ` arc=${arcName} effective=${effectiveMode} flushed=${flushed} drops=${marks.length}` +
      ` sidecar=${sidecar} measuredSrcFps=${measuredSrcFps} glOutFrames=${glOutFrames}` +
      `${tail}${diagTail}`;
  }

  isRecording(): boolean {
    return this.fd >= 0;
  }
}
