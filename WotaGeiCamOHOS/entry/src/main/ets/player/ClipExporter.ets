/**
 * A/B 无损剪辑导出的 ArkTS 门面（native remux worker + fd 生命周期 + sidecar 平移）。
 *
 * 加载顺序：本文件不静态 import 'libentry.so'（也不静态 import 桥），probe/export 时才
 * dynamic import '../camera/GlLibBridge'——Player 页静态 import 本文件不会让原生模块先于
 * XComponent 初始化。
 *
 * 失败语义：native 未 done 的任何失败/取消都删除本次新建的私有 videos 文件；done 之后
 * 只允许 sidecar 写失败（结果仍 ok，sidecarOk=false），绝不删已成片。
 *
 * T20 复审 P3（本批修复）：
 * - uniqueOutputPath：access 异常视为被占用继续 _N 循环，1..99 穷尽回退时间戳名，
 *   绝不返回 access 异常的原候选名（防 TRUNC 覆盖旧成片）；
 * - 超时 fd 延迟关闭：CANCEL_GRACE_MS 耗尽后不立即 close，进入 linger 轮询
 *   （NATIVE_LINGER_MS 上限）等 native 离开 running:；linger 内退出照常 close+discard，
 *   耗尽则挂模块级 pendingFds（下次 exportClip 入口/轮询 reap），Diag.w 登记泄漏，
 *   不关正被阻塞调用写的 fd。
 */
import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { Diag } from '../diag/Diag';
import { VideoClip } from '../media/MediaRepo';
import { VideoStore } from '../record/VideoStore';
import { ArcDropLog } from '../record/ArcDropLog';
import { ClipRange, clipNameOf, keyFrameIndexOf, normalize, remapDrops } from './ClipRule';

/** native 桥的最小视图（dynamic import 后 cast） */
interface ClipNativeLib {
  startClipExport: (inFd: number, inSize: number, outFd: number, startMs: number,
    endMs: number, audioOrdinal: number) => string;
  getClipExportStatus: () => string;
  cancelClipExport: () => void;
  probeClipTracks: (inFd: number, inSize: number) => string;
}

const POLL_MS: number = 200;
const EXPORT_TIMEOUT_MS: number = 10 * 60 * 1000;
const CANCEL_GRACE_MS: number = 5000;
/** 取消宽限耗尽后的 native 退场等待上限（linger 轮询；再不退才挂 pendingFds） */
const NATIVE_LINGER_MS: number = 30 * 1000;
/** 与 native 侧 OH_LOG_PRINT 同 tag（WotaClip），hilog 一并过滤 */
const WOTA_CLIP_TAG: string = 'WotaClip';

/** 超时未退场的导出 fd（挂起待 reap：native 离开 running: 之前不能关） */
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

/** 模块级挂起列表：跨导出调用存活，下一次 exportClip 入口/轮询时 reap */
const pendingFds: Array<PendingFd> = [];

/** 轨探测结果：error 非空 = 失败；否则 videoCount/audioCount 有效 */
export class ClipTrackInfo {
  videoCount: number;
  audioCount: number;
  error: string;

  constructor(videoCount: number, audioCount: number, error: string) {
    this.videoCount = videoCount;
    this.audioCount = audioCount;
    this.error = error;
  }
}

/** 导出结果：ok=false 时 error 为机器码/原因；done 后 sizeBytes/sidecarOk 有值 */
export class ClipExportResult {
  ok: boolean;
  path: string;
  basePtsUs: number;
  videoSamples: number;
  audioSamples: number;
  error: string;
  cancelled: boolean;
  audioSkipped: boolean;
  sizeBytes: number;
  sidecarOk: boolean;

  constructor(ok: boolean, path: string, basePtsUs: number, videoSamples: number, audioSamples: number,
    error: string, cancelled: boolean, audioSkipped: boolean, sizeBytes: number, sidecarOk: boolean) {
    this.ok = ok;
    this.path = path;
    this.basePtsUs = basePtsUs;
    this.videoSamples = videoSamples;
    this.audioSamples = audioSamples;
    this.error = error;
    this.cancelled = cancelled;
    this.audioSkipped = audioSkipped;
    this.sizeBytes = sizeBytes;
    this.sidecarOk = sidecarOk;
  }
}

function delayMs(ms: number): Promise<void> {
  return new Promise<void>((resolve: () => void): void => {
    setTimeout(resolve, ms);
  });
}

export class ClipExporter {
  private static lib: ClipNativeLib | undefined = undefined;

  private static async ensureLib(): Promise<ClipNativeLib> {
    const cached: ClipNativeLib | undefined = ClipExporter.lib;
    if (cached !== undefined) {
      return cached;
    }
    const mod: ESObject = await import('../camera/GlLibBridge');
    const lib: ClipNativeLib = mod as ClipNativeLib;
    ClipExporter.lib = lib;
    return lib;
  }

  /** 只读探测轨数；fd 在本方法内开关 */
  static async probe(ctx: common.Context, clip: VideoClip): Promise<ClipTrackInfo> {
    let fd: number = -1;
    try {
      const lib: ClipNativeLib = await ClipExporter.ensureLib();
      const stat: fileIo.Stat = fileIo.statSync(clip.path);
      if (stat.size <= 0) {
        return new ClipTrackInfo(0, 0, 'OPEN:empty-file');
      }
      fd = fileIo.openSync(clip.path, fileIo.OpenMode.READ_ONLY).fd;
      const raw: string = lib.probeClipTracks(fd, stat.size);
      if (raw.indexOf('err:') === 0) {
        return new ClipTrackInfo(0, 0, raw.substring(4));
      }
      const parts: Array<string> = raw.split(',');
      if (parts.length < 2) {
        return new ClipTrackInfo(0, 0, 'PROBE');
      }
      const videoCount: number = Number(parts[0]);
      const audioCount: number = Number(parts[1]);
      if (!Number.isInteger(videoCount) || !Number.isInteger(audioCount) ||
        videoCount < 0 || audioCount < 0) {
        return new ClipTrackInfo(0, 0, 'PROBE');
      }
      return new ClipTrackInfo(videoCount, audioCount, '');
    } catch (e) {
      return new ClipTrackInfo(0, 0, `OPEN:${e}`);
    } finally {
      ClipExporter.closeQuietly(fd);
    }
  }

  /**
   * 导出 A/B 区间。range 可为原始 A/B（内部再 normalize）；非法返回 RANGE。
   * onProgress 0..100，UI 线程回调。
   */
  static async exportClip(ctx: common.Context, clip: VideoClip, range: ClipRange, audioOrdinal: number,
    onProgress: (percent: number) => void): Promise<ClipExportResult> {
    // 元数据探测失败的片 durationMs 可能为 0；用 A/B 终点兜底，native 仍按源实际 EOF 截止。
    const durationMs: number = clip.durationMs > 0 ? clip.durationMs :
      Math.max(range.startMs, range.endMs);
    const valid: ClipRange | undefined = normalize(range.startMs, range.endMs, durationMs);
    if (valid === undefined) {
      return ClipExporter.failed('', 'RANGE', false);
    }
    let inFd: number = -1;
    let outFd: number = -1;
    let outPath: string = '';
    let completed: boolean = false;
    try {
      const lib: ClipNativeLib = await ClipExporter.ensureLib();
      ClipExporter.reapPendingFds(); // 入口回收上一轮超时挂起的 fd（native 已退场则关闭）
      const stat: fileIo.Stat = fileIo.statSync(clip.path);
      if (stat.size <= 0) {
        return ClipExporter.failed('', 'OPEN', false);
      }
      inFd = fileIo.openSync(clip.path, fileIo.OpenMode.READ_ONLY).fd;
      outPath = ClipExporter.uniqueOutputPath(ctx, clipNameOf(clip.name, Date.now()));
      outFd = fileIo.openSync(outPath,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC).fd;
      onProgress(0);
      const startError: string = lib.startClipExport(inFd, stat.size, outFd, valid.startMs, valid.endMs,
        audioOrdinal < 0 ? -1 : audioOrdinal);
      if (startError !== '') {
        ClipExporter.closeQuietly(outFd);
        outFd = -1;
        ClipExporter.closeQuietly(inFd);
        inFd = -1;
        VideoStore.discard(outPath);
        return ClipExporter.failed(outPath, startError, false);
      }

      let status: string = '';
      let timeoutAt: number = 0;
      let cancelAt: number = 0;
      const deadline: number = Date.now() + EXPORT_TIMEOUT_MS;
      for (;;) {
        status = lib.getClipExportStatus();
        ClipExporter.reapPendingFds(); // 轮询顺带回收挂起 fd（native 退场即关；空表 no-op）
        if (status.indexOf('running:') === 0) {
          const pct: number = Number(status.substring('running:'.length));
          if (Number.isFinite(pct)) {
            onProgress(Math.min(99, Math.max(0, Math.round(pct))));
          }
        } else if (status.indexOf('done:') === 0 || status === 'cancelled' ||
          status.indexOf('failed:') === 0) {
          break;
        } else if (status === 'idle') {
          status = 'failed:idle';
          break;
        }
        const now: number = Date.now();
        if (now > deadline && timeoutAt === 0) {
          timeoutAt = now;
          cancelAt = now;
          lib.cancelClipExport();
        } else if (timeoutAt > 0 && now - cancelAt > CANCEL_GRACE_MS) {
          // 宽限耗尽（T20 复审 P3）：不立即 close——先 linger 轮询等 native 离开 running:。
          // native 单线程 worker，卡死时关掉它正写的 outFd 语义未定义（半成品+可能的崩溃面）。
          const lingerDeadline: number = Date.now() + NATIVE_LINGER_MS;
          let nativeLeft: boolean = false;
          for (;;) {
            let probe: string = '';
            try {
              probe = lib.getClipExportStatus();
            } catch (e) {
              probe = '';
            }
            if (probe.indexOf('running:') !== 0) {
              nativeLeft = true;
              break;
            }
            if (Date.now() > lingerDeadline) {
              break;
            }
            await delayMs(POLL_MS);
          }
          if (nativeLeft) {
            // linger 内退场：照常 close + 按超时失败 discard（下方统一路径）
            status = 'failed:timeout';
            break;
          }
          // linger 耗尽仍 running:：fd 挂 pendingFds 延迟关闭，输出文件仍按失败 discard
          // （unlink 后 native 继续写已解除链接的 inode，reap close 后空间回收）
          ClipExporter.parkPendingFds(outFd, inFd, outPath);
          outFd = -1;
          inFd = -1;
          status = 'failed:timeout';
          break;
        }
        await delayMs(POLL_MS);
      }

      ClipExporter.closeQuietly(outFd);
      outFd = -1;
      ClipExporter.closeQuietly(inFd);
      inFd = -1;

      if (status === 'cancelled') {
        VideoStore.discard(outPath);
        return ClipExporter.failed(outPath, 'CANCELLED', true);
      }
      if (status.indexOf('failed:') === 0) {
        VideoStore.discard(outPath);
        return ClipExporter.failed(outPath, status.substring('failed:'.length), false);
      }
      if (status.indexOf('done:') !== 0) {
        VideoStore.discard(outPath);
        return ClipExporter.failed(outPath, 'STATUS', false);
      }

      completed = true;
      const parts: Array<string> = status.split(':');
      const basePtsUs: number = parts.length > 1 ? Number(parts[1]) : 0;
      const videoSamples: number = parts.length > 2 ? Number(parts[2]) : 0;
      const audioSamples: number = parts.length > 3 ? Number(parts[3]) : 0;
      const audioSkipped: boolean = parts.length > 4 && parts[4] === 'audio=skipped';
      onProgress(100);
      let sizeBytes: number = 0;
      try {
        sizeBytes = fileIo.statSync(outPath).size;
      } catch (e) {
        sizeBytes = 0;
      }
      const sidecarOk: boolean = ClipExporter.writeSidecar(clip, outPath, basePtsUs);
      return new ClipExportResult(true, outPath,
        Number.isFinite(basePtsUs) ? basePtsUs : 0,
        Number.isFinite(videoSamples) ? videoSamples : 0,
        Number.isFinite(audioSamples) ? audioSamples : 0,
        '', false, audioSkipped, sizeBytes, sidecarOk);
    } catch (e) {
      ClipExporter.closeQuietly(outFd);
      outFd = -1;
      ClipExporter.closeQuietly(inFd);
      inFd = -1;
      if (!completed && outPath !== '') {
        VideoStore.discard(outPath);
        ArcDropLog.deleteFor(outPath);
      }
      return ClipExporter.failed(outPath, `EXPORT:${e}`, false);
    } finally {
      ClipExporter.closeQuietly(outFd);
      ClipExporter.closeQuietly(inFd);
    }
  }

  /** 取消当前 native 导出（无导出 = no-op；必须在 probe/export 之后加载过桥） */
  static cancel(): void {
    const lib: ClipNativeLib | undefined = ClipExporter.lib;
    if (lib === undefined) {
      return;
    }
    try {
      lib.cancelClipExport();
    } catch (e) {
    }
  }

  /** 超时未退场：挂起 fd 不关闭（泄漏登记 Diag.w；native 退场后由 reapPendingFds 关） */
  private static parkPendingFds(outFd: number, inFd: number, path: string): void {
    pendingFds.push(new PendingFd(inFd, outFd, path, Date.now()));
    Diag.w(WOTA_CLIP_TAG, `export timeout: native still running, fds parked for delayed close: ${path}`);
  }

  /** 挂起 fd 回收：native 已离开 running: 才关（exportClip 入口/轮询双时机调用；空表 no-op） */
  static reapPendingFds(): void {
    if (pendingFds.length === 0) {
      return;
    }
    const lib: ClipNativeLib | undefined = ClipExporter.lib;
    if (lib === undefined) {
      return;
    }
    const keep: Array<PendingFd> = [];
    for (const p of pendingFds) {
      let running: boolean = true;
      try {
        running = lib.getClipExportStatus().indexOf('running:') === 0;
      } catch (e) {
        running = true; // 状态不可得：保守保留，等下一轮
      }
      if (running) {
        keep.push(p);
      } else {
        ClipExporter.closeQuietly(p.outFd);
        ClipExporter.closeQuietly(p.inFd);
        Diag.i(WOTA_CLIP_TAG,
          `parked fds reaped after native exit: ${p.path} (held ${Date.now() - p.parkedAtMs}ms)`);
      }
    }
    pendingFds.length = 0;
    for (const p of keep) {
      pendingFds.push(p);
    }
  }

  private static writeSidecar(clip: VideoClip, outPath: string, basePtsUs: number): boolean {
    try {
      const source: ArcDropLog | null = ArcDropLog.readFrom(clip.path);
      if (source === null || source.dstFps <= 0) {
        return true; // 不是抽帧成片：输出不需要 sidecar
      }
      const shift: number = keyFrameIndexOf(basePtsUs, source.dstFps);
      return ArcDropLog.writeTo(outPath, remapDrops(source, shift));
    } catch (e) {
      return false;
    }
  }

  /**
   * 同名文件已存在时追加 _N，绝不 TRUNC 覆盖上一次导出。
   * T20 复审 P3：accessSync 异常（权限/并发删除等）按「被占用」处理继续 _N 循环；
   * 1..99 穷尽回退 `${stem}_${Date.now()}.mp4`——绝不返回 access 异常的原候选名
   * （调用方以 TRUNC 打开，返回旧名等于覆盖旧成片）。
   */
  private static uniqueOutputPath(ctx: common.Context, name: string): string {
    const occupied = (p: string): boolean => {
      try {
        return fileIo.accessSync(p);
      } catch (e) {
        return true; // 查询失败按占用保守处理：宁可换名不可覆盖
      }
    };
    const first: string = VideoStore.pathFor(ctx, name);
    if (!occupied(first)) {
      return first;
    }
    const dot: number = name.lastIndexOf('.');
    const stem: string = dot > 0 ? name.substring(0, dot) : name;
    for (let i: number = 1; i < 100; i++) {
      const candidate: string = VideoStore.pathFor(ctx, `${stem}_${i}.mp4`);
      if (!occupied(candidate)) {
        return candidate;
      }
    }
    return VideoStore.pathFor(ctx, `${stem}_${Date.now()}.mp4`);
  }

  private static failed(path: string, error: string, cancelled: boolean): ClipExportResult {
    return new ClipExportResult(false, path, 0, 0, 0, error, cancelled, false, 0, true);
  }

  private static closeQuietly(fd: number): void {
    if (fd < 0) {
      return;
    }
    try {
      fileIo.closeSync(fd);
    } catch (e) {
      // fd 已关/无效不再影响结果
    }
  }
}
