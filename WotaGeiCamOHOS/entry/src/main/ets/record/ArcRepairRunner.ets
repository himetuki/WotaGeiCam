/**
 * 离线光弧修复的 ArkTS 门面（T29：CPU 执行器核心；页面入口与音频轨 T30）。
 *
 * 链路：probe（只读探测）→ 纯逻辑判定（速率/NO_NEED/抽帧计划/码率）→ start（native worker）
 * → 200ms 轮询状态 → 结果归因（ArcRepairSessionModel.resolveRepairOutcome）→ 成功 stat /
 * 失败或取消 discard 半成品。
 *
 * fd 契约（与 ClipExporter 同口径）：输入 READ_ONLY、输出 READ_WRITE|CREATE|TRUNC，两者都归
 * ArkTS；native 绝不 close。取消/超时后 native 仍在跑时先宽限、再 linger 等它离开 running:，
 * 耗尽才挂 pendingFds（下一次调用或轮询回收），绝不关它正写的 outFd。
 *
 * 本批不产音频轨（T30 补直拷）；Player 页「修复光弧」入口也在 T30。
 */
import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { Diag } from '../diag/Diag';
import { VideoClip } from '../media/MediaRepo';
import { ArcRateProbe, MeasuredSrcFps } from './ArcKeepRule';
import { ArcRepairPlan } from './ArcRepairPlan';
import {
  ArcProbeInfo, ArcRepairError, ArcRepairOutcome, ArcRepairSessionModel, ArcRepairStatus
} from './ArcRepairSessionModel';
import { ClipExporter } from '../player/ClipExporter';
import { BitratePolicy } from './BitratePolicy';
import { VideoStore } from './VideoStore';

/** native 桥的最小视图（dynamic import 后 cast；与 types/libentry/index.d.ts 对齐） */
interface ArcRepairNativeLib {
  probeArcRepairSource: (inFd: number, inSize: number) => string;
  startArcRepair: (inFd: number, inSize: number, outFd: number, dstFps: number, bitrate: number,
    deadband: number, srcNum: number, srcDen: number, srcFrames: number, dstFrames: number,
    rotation: number, durationMs: number) => string;
  getArcRepairStatus: () => string;
  cancelArcRepair: () => void;
}

const TAG: string = 'WotaArcRepair';
const POLL_MS: number = 200;
/** 取消宽限：先等 native 自己收尾 */
const CANCEL_GRACE_MS: number = 5000;
/** 宽限耗尽后的 native 退场等待上限（linger 轮询） */
const NATIVE_LINGER_MS: number = 30 * 1000;

/** 探测结果（含纯逻辑判定：速率/计划/NO_NEED/码率） */
export class ArcRepairProbeResult {
  ok: boolean = false;
  error: string = '';
  width: number = 0;
  height: number = 0;
  rotation: number = 0;
  containerFps: number = 0;
  durationMs: number = 0;
  audioCount: number = 0;
  /** 实测源帧率（有理数真值） */
  srcNum: number = 30;
  srcDen: number = 1;
  /** 整数视图（仅命名/日志） */
  srcFps: number = 30;
  srcFrames: number = 0;
  dstFrames: number = 0;
  /** 源不比目标快：无需修复（不是故障） */
  noNeed: boolean = false;
  bitrate: number = 0;
  note: string = '';
}

/** 修复结果：ok=false 时 error 为机器码（此时产物已回收） */
export class ArcRepairResult {
  ok: boolean = false;
  path: string = '';
  bytes: number = 0;
  error: string = '';
  note: string = '';
}

/** 超时挂起的 fd（native 仍在 running: 时不能关） */
class PendingFd {
  inFd: number;
  outFd: number;
  path: string;
  parkedAtMs: number;

  constructor(inFd: number, outFd: number, path: string, parkedAtMs: number) {
    this.inFd = inFd;
    this.outFd = outFd;
    this.path = path;
    this.parkedAtMs = parkedAtMs;
  }
}

const pendingFds: Array<PendingFd> = [];
let cancelRequested: boolean = false;

function delayMs(ms: number): Promise<void> {
  return new Promise<void>((resolve: () => void): void => {
    setTimeout(resolve, ms);
  });
}

export class ArcRepairRunner {
  private static lib: ArcRepairNativeLib | undefined = undefined;

  private static async ensureLib(): Promise<ArcRepairNativeLib> {
    const cached: ArcRepairNativeLib | undefined = ArcRepairRunner.lib;
    if (cached !== undefined) {
      return cached;
    }
    const mod: ESObject = await import('../camera/GlLibBridge');
    const lib: ArcRepairNativeLib = mod as ArcRepairNativeLib;
    ArcRepairRunner.lib = lib;
    return lib;
  }

  /** 请求取消当前修复（页面/返回键用；无任务时 no-op） */
  static cancel(): void {
    cancelRequested = true;
    const lib: ArcRepairNativeLib | undefined = ArcRepairRunner.lib;
    if (lib !== undefined) {
      try {
        lib.cancelArcRepair();
      } catch (e) {
        Diag.w(TAG, `cancelArcRepair failed: ${e}`);
      }
    }
  }

  /**
   * 只读探测 + 纯逻辑判定（不写文件、不启 native 线程）。
   * 返回 noNeed=true 时调用方应给「源帧率不高于目标」提示并结束（不是故障）。
   */
  static async probe(ctx: common.Context, clip: VideoClip): Promise<ArcRepairProbeResult> {
    const out: ArcRepairProbeResult = new ArcRepairProbeResult();
    let fd: number = -1;
    try {
      const lib: ArcRepairNativeLib = await ArcRepairRunner.ensureLib();
      const stat: fileIo.Stat = fileIo.statSync(clip.path);
      if (stat.size <= 0) {
        out.error = ArcRepairError.SOURCE;
        return out;
      }
      fd = fileIo.openSync(clip.path, fileIo.OpenMode.READ_ONLY).fd;
      const info: ArcProbeInfo = ArcRepairSessionModel.parseProbe(
        lib.probeArcRepairSource(fd, stat.size));
      if (!info.ok) {
        out.error = info.err;
        return out;
      }
      out.width = info.width;
      out.height = info.height;
      out.rotation = info.rotation;
      out.containerFps = info.containerFps;
      out.durationMs = info.durationMs > 0 ? info.durationMs : Math.max(0, clip.durationMs);
      out.audioCount = info.audioCount;
      const rate: MeasuredSrcFps = ArcRepairSessionModel.measuredRateFromProbe(
        info.cleanCount, info.medianUs, info.containerFps);
      out.srcNum = rate.num;
      out.srcDen = rate.den;
      out.srcFps = rate.roundedInt();
      if (out.durationMs <= 0) {
        // 时长不可得：进度分母 / deadline 都无从建立——按失败返回，不产出 ok=true + error 的矛盾态
        out.ok = false;
        out.error = ArcRepairError.SOURCE;
        return out;
      }
      out.ok = true;
      out.srcFrames = ArcRepairSessionModel.estimateSrcFrames(out.durationMs, rate);
      const fpsForBitrate: number = out.containerFps > 0 ? out.containerFps : out.srcFps;
      out.bitrate = BitratePolicy.resolve(0, out.width, out.height, fpsForBitrate, false);
      out.noNeed = ArcRepairSessionModel.decideNoNeed(rate, 24) && ArcRepairSessionModel.decideNoNeed(rate, 25);
      out.note = ArcRepairSessionModel.repairNote(out.srcFps, out.srcNum, out.srcDen,
        out.containerFps, 24, 12);
      Diag.i(TAG, `probe ${out.width}x${out.height} rot=${out.rotation} container=${out.containerFps} ` +
        `src=${out.srcFps}(${out.srcNum}/${out.srcDen}) dur=${out.durationMs} ` +
        `srcFrames=${out.srcFrames} audio=${out.audioCount} bitrate=${out.bitrate}`);
      return out;
    } catch (e) {
      out.ok = false;
      out.error = `${ArcRepairError.SOURCE}`;
      Diag.w(TAG, `probe failed: ${e}`);
      return out;
    } finally {
      if (fd >= 0) {
        try {
          fileIo.closeSync(fd);
        } catch (e) {
        }
      }
    }
  }

  /**
   * 执行修复。成功返回成片路径与字节数；**失败/取消一律先 unlink 半成品**（与 ClipExporter 同口径）：
   * native 若仍在写，写的是已解除链接的 inode，不会在媒体库留下垃圾；fd 等 native 离开 running:
   * 后再关（linger 耗尽则挂 pendingFds，由下一次调用/轮询回收并兜底再次 discard）。
   * 调用方应先 probe 并处理 noNeed / 音轨硬闸。
   */
  static async run(ctx: common.Context, clip: VideoClip, dstFps: number, deadband: number,
    onProgress: (percent: number) => void): Promise<ArcRepairResult> {
    const result: ArcRepairResult = new ArcRepairResult();
    let inFd: number = -1;
    let outFd: number = -1;
    let outPath: string = '';
    let lib: ArcRepairNativeLib | undefined = undefined;
    try {
      lib = await ArcRepairRunner.ensureLib();
      ArcRepairRunner.reapPendingFds();
      cancelRequested = false;
      const probed: ArcRepairProbeResult = await ArcRepairRunner.probe(ctx, clip);
      if (!probed.ok) {
        result.error = probed.error;
        return result;
      }
      if (ArcRepairSessionModel.decideNoNeed(ArcRateProbe.measuredOf(probed.srcNum, probed.srcDen),
        dstFps)) {
        result.error = ArcRepairError.NO_NEED;
        result.note = probed.note;
        return result;
      }
      // T29 临时硬闸：源含音轨时本批无法产出音频轨（T30 补直拷）——拒绝而不是静默丢音
      if (probed.audioCount > 0) {
        result.error = ArcRepairError.NO_AUDIO_LANE;
        result.note = probed.note;
        return result;
      }
      const plan: ArcRepairPlan = ArcRepairPlan.of(probed.srcFrames, probed.srcNum, probed.srcDen, dstFps);
      if (plan.dstFrames <= 0) {
        result.error = ArcRepairError.SOURCE;
        return result;
      }
      const namePrefix: string = ArcRateProbe.convertNamePrefix(probed.srcFps, dstFps);
      // 防覆盖：1/1000 随机后缀可能撞名，存在即换 _N（与 ClipExporter 同一实现）
      outPath = ClipExporter.uniqueOutputPath(ctx,
        VideoStore.displayName(Date.now(), namePrefix));
      const stat: fileIo.Stat = fileIo.statSync(clip.path);
      inFd = fileIo.openSync(clip.path, fileIo.OpenMode.READ_ONLY).fd;
      outFd = fileIo.openSync(outPath,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC).fd;
      onProgress(0);
      const startError: string = lib.startArcRepair(inFd, stat.size, outFd, dstFps, probed.bitrate,
        deadband, probed.srcNum, probed.srcDen, probed.srcFrames, plan.dstFrames, probed.rotation,
        probed.durationMs);
      if (startError !== '') {
        ArcRepairRunner.closeQuietly(outFd);
        outFd = -1;
        ArcRepairRunner.closeQuietly(inFd);
        inFd = -1;
        VideoStore.discard(outPath);
        result.error = startError === 'busy' || startError === 'args' || startError === 'worker'
          ? ArcRepairError.ENGINE : startError;
        return result;
      }

      let status: ArcRepairStatus = new ArcRepairStatus('idle', 0, 0, 0, false, false, false, '');
      const deadline: number = Date.now() + probed.durationMs * 8 + 120 * 1000;
      let cancelAt: number = 0;
      /** native 已收到终态（此后 fd 可安全关闭；否则按「仍在写」处理） */
      let nativeSettled: boolean = false;
      for (;;) {
        const raw: string = lib.getArcRepairStatus();
        status = ArcRepairSessionModel.parseRepairStatus(raw);
        ArcRepairRunner.reapPendingFds();
        if (status.kind === 'running') {
          onProgress(Math.min(99, status.progress));
        } else if (status.kind === 'done' || status.kind === 'failed' || status.kind === 'cancelled') {
          nativeSettled = true;
          break;
        } else if (status.kind === 'idle') {
          status = ArcRepairSessionModel.parseRepairStatus(`failed:${ArcRepairError.ENGINE}`);
          nativeSettled = true;
          break;
        }
        const now: number = Date.now();
        if (cancelRequested && cancelAt === 0) {
          cancelAt = now;
          lib.cancelArcRepair();
        } else if (now > deadline && cancelAt === 0) {
          cancelAt = now;
          lib.cancelArcRepair();
        }
        if (cancelAt > 0 && now - cancelAt > CANCEL_GRACE_MS) {
          // 宽限耗尽：linger 等 native 离开 running:（单线程 worker 正在写 outFd，不能抢先关）
          const lingerDeadline: number = Date.now() + NATIVE_LINGER_MS;
          let nativeLeft: boolean = false;
          for (;;) {
            let probeText: string = '';
            try {
              probeText = lib.getArcRepairStatus();
            } catch (e) {
              probeText = '';
            }
            if (probeText.indexOf('running:') !== 0) {
              nativeLeft = true;
              break;
            }
            if (Date.now() > lingerDeadline) {
              break;
            }
            await delayMs(POLL_MS);
          }
          if (nativeLeft) {
            status = ArcRepairSessionModel.parseRepairStatus(lib.getArcRepairStatus());
            nativeSettled = true;
          } else {
            // native 始终未退场：**先 unlink 半成品**（写已解除链接的 inode，安全），
            // fd 挂起等它退场后再关（绝不能关它正写的 outFd）
            status = ArcRepairSessionModel.parseRepairStatus(`failed:${ArcRepairError.TIMEOUT}`);
            nativeSettled = false;
          }
          break;
        }
        await delayMs(POLL_MS);
      }

      const outcome: ArcRepairOutcome = ArcRepairSessionModel.resolveRepairOutcome(status);
      result.ok = outcome.ok;
      result.note = probed.note;
      if (outcome.ok) {
        // 成功：fd 先关再 stat（关闭触发落盘收尾）
        ArcRepairRunner.closeQuietly(outFd);
        outFd = -1;
        ArcRepairRunner.closeQuietly(inFd);
        inFd = -1;
        result.path = outPath;
        result.bytes = VideoStore.sizeOf(outPath);
        onProgress(100);
        Diag.i(TAG, `repair done ${outPath} bytes=${result.bytes} samples=${status.videoSamples} ` +
          `dstFrames=${plan.dstFrames} delta=${status.videoSamples - plan.dstFrames}`);
      } else {
        result.error = outcome.error;
        if (nativeSettled) {
          ArcRepairRunner.closeQuietly(outFd);
          outFd = -1;
          ArcRepairRunner.closeQuietly(inFd);
          inFd = -1;
        }
        ArcRepairRunner.parkOrDiscard(outPath, inFd, outFd, !nativeSettled);
        inFd = -1;
        outFd = -1;
        Diag.w(TAG, `repair failed ${outcome.error} (status=${status.kind} v=${status.videoSamples})`);
      }
      return result;
    } catch (e) {
      result.ok = false;
      result.error = ArcRepairError.ENGINE;
      Diag.w(TAG, `run failed: ${e}`);
      // 与收尾同一口径：native 可能仍在写 → 先 unlink 再挂 fd；否则关 fd
      const nativeMaybeRunning: boolean = inFd >= 0 && outFd >= 0;
      ArcRepairRunner.parkOrDiscard(outPath, inFd, outFd, nativeMaybeRunning);
      inFd = -1;
      outFd = -1;
      return result;
    }
  }

  /**
   * 半成品/资源收尾（失败与异常共用）：**先无条件 unlink 成片路径**（与 ClipExporter 同口径；
   * native 写的是已解除链接的 inode，媒体库不会出现半成品），随后按 native 是否仍运行决定
   * 「挂起 fd 等 reap」还是「立即关闭」。
   */
  private static parkOrDiscard(outPath: string, inFd: number, outFd: number,
    nativeRunning: boolean): void {
    if (outPath !== '') {
      VideoStore.discard(outPath);
    }
    if (nativeRunning && (inFd >= 0 || outFd >= 0)) {
      pendingFds.push(new PendingFd(inFd, outFd, outPath, Date.now()));
      Diag.w(TAG, `parked fd until native exits: ${outPath}`);
      return;
    }
    ArcRepairRunner.closeQuietly(outFd);
    ArcRepairRunner.closeQuietly(inFd);
  }

  /** native 契约摘要（EntryAbility 启动日志；编译门：本模块由此进入 abc） */
  static contract(): string {
    return 'probe/start/status/cancel | status=idle|running:pct|' +
      'done:v:a:mux:track:stop|failed:code|cancelled | err=ARC_SOURCE/DECODER/ENCODER/MUXER/' +
      'CPU_YUV/CPU_FEED/NO_VIDEO/CANCELLED/TIMEOUT/ENGINE/NO_NEED/NO_AUDIO_LANE';
  }

  /** 回收超时挂起的 fd（native 已离开 running: 才关闭；否则下一轮再试） */
  private static reapPendingFds(): void {
    const lib: ArcRepairNativeLib | undefined = ArcRepairRunner.lib;
    if (lib === undefined || pendingFds.length === 0) {
      return;
    }
    let busy: boolean = false;
    try {
      busy = lib.getArcRepairStatus().indexOf('running:') === 0;
    } catch (e) {
      busy = true;
    }
    if (busy) {
      return;
    }
    while (pendingFds.length > 0) {
      const pending: PendingFd | undefined = pendingFds.shift();
      if (pending === undefined) {
        break;
      }
      ArcRepairRunner.closeQuietly(pending.inFd);
      ArcRepairRunner.closeQuietly(pending.outFd);
      if (pending.path !== '') {
        // 兜底：park 时已 unlink；这里再 discard 一次（幂等），防任何遗漏的半成品进媒体库
        VideoStore.discard(pending.path);
      }
      Diag.w(TAG, `reaped parked fd after ${Date.now() - pending.parkedAtMs}ms: ${pending.path}`);
    }
  }

  private static closeQuietly(fd: number): void {
    if (fd < 0) {
      return;
    }
    try {
      fileIo.closeSync(fd);
    } catch (e) {
    }
  }
}
