/**
 * GL-4 GPU 录制门面（CodecRecorder 最小切片的 ArkTS 侧）：
 * 文件句柄归 ArkTS（fileIo.openSync 取 fd 传 native；native 绝不 close，停录后本类关闭并 stat 大小），
 * native 侧 OH_VideoEncoder(surface 输入) + OH_AVMuxer(fd, MPEG_4) 直写，视频-only、单段。
 *
 * 边界（登记）：无音频轨、无分段轮转、无抽帧/FrameLedger/.drops.json、无参数下发（尺寸/码率由页面
 * 固定档传入）；旋转元数据由 native muxer OH_AVMuxer_SetRotation((sensor−display+360)%360) 写入容器
 * （编码 UV 只送缓冲原样帧，stRotDeg 不镜像）；输出落应用私有 filesDir/videos（VideoStore）。
 */
import { fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { VideoStore } from '../record/VideoStore';

/** libentry.so 录制出口（camera/GlLibBridge 的导出子集） */
export interface GlRecordLib {
  startGpuRecord: (fd: number, width: number, height: number, fps: number, bitrate: number) => string;
  stopGpuRecord: () => string;
  isGpuRecording: () => boolean;
  getGpuRecordError: () => string;
}

export class GlRecorder {
  private fd: number = -1;
  private path: string = '';
  private startedAtMs: number = 0;
  private lib: GlRecordLib | undefined = undefined;

  /**
   * 起录：空串 = 成功；非空 = 机器码（LOW_STORAGE:<mb> / open-failed / napi-failed / native 各步机器码）。
   * 失败路径：native 已内部回收（native 错误码）→ close fd + discard 空文件；
   * napi 异常（start 可能已部分执行）→ 先 best-effort stop 再 close，文件保留待 triage。
   */
  start(ctx: common.Context, lib: GlRecordLib, width: number, height: number, fps: number,
    bitrate: number): string {
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
      error = lib.startGpuRecord(file.fd, width, height, fps, bitrate);
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
    return '';
  }

  /** 停录：native 摘要（ok samples=N eos=0/1 或错误串）→ close fd → stat 大小 → 人读摘要 */
  stop(): string {
    const lib: GlRecordLib | undefined = this.lib;
    if (lib === undefined || this.fd < 0) {
      return 'idle';
    }
    let status: string = '';
    try {
      status = lib.stopGpuRecord();
    } catch (e) {
      status = `napi-failed:${e}`;
    }
    // surface 销毁路径由 native 先行中止：取回中止原因（空串 = 正常）
    let nativeError: string = '';
    try {
      nativeError = lib.getGpuRecordError();
    } catch (e) {
      nativeError = '';
    }
    const fd: number = this.fd;
    const path: string = this.path;
    const durationMs: number = Date.now() - this.startedAtMs;
    this.fd = -1;
    this.path = '';
    this.startedAtMs = 0;
    this.lib = undefined;
    try {
      fileIo.closeSync(fd);
    } catch (e) {
    }
    const bytes: number = VideoStore.sizeOf(path);
    const tail: string = nativeError !== '' && status.indexOf(nativeError) < 0
      ? ` nativeError=${nativeError}` : '';
    return `path=${path} bytes=${bytes} duration=${durationMs}ms status=${status}${tail}`;
  }

  isRecording(): boolean {
    return this.fd >= 0;
  }
}
