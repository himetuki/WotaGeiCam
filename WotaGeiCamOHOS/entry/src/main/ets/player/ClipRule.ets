/**
 * A/B 无损剪辑的纯规则（直译上游 player/ClipExport.kt 的 ClipRule 子集，对齐上游 38d712a）。
 *
 * 平台差异 / 本批边界：
 * - 上游 clipNameOf 无时间戳（MediaStore 自动去重）；OHOS 落私有目录自行防覆盖，
 *   按本批约定追加 `_yyyyMMdd_HHmmss`；
 * - 上游 remapDrops 只保留 drops；本侧 ArcDropLog 多了 segments 段清单——剪辑成片是单文件、
 *   段序号已失效，故清空 segments（登记，不把源片多段账带进剪辑片档案）；
 * - 不移植 MediaStore pending/commit、TrackExport、RemuxOrientation 的平台段。
 */
import { ArcDropLog, DropMark } from '../record/ArcDropLog';

/** 保留段下限：比它短视为误操作（300ms 在 24fps 下连 8 帧都不到） */
export const MIN_KEEP_MS: number = 300;

/** 规范化后的剪辑区间（毫秒，均已夹到 [0, durationMs]） */
export class ClipRange {
  startMs: number;
  endMs: number;

  constructor(startMs: number, endMs: number) {
    this.startMs = startMs;
    this.endMs = endMs;
  }
}

/**
 * 入出点规范化：起点非负、终点不超片长、终点必须晚于起点、保留段不短于 MIN_KEEP_MS。
 * 不合法返回 undefined（调用方给“保留段太短”提示）。
 */
export function normalize(startMs: number, endMs: number, durationMs: number): ClipRange | undefined {
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || !Number.isFinite(durationMs)) {
    return undefined;
  }
  const duration: number = Math.max(0, durationMs);
  const s: number = Math.min(duration, Math.max(0, startMs));
  const e: number = Math.min(duration, Math.max(0, endMs));
  if (e - s < MIN_KEEP_MS) {
    return undefined;
  }
  return new ClipRange(s, e);
}

/**
 * 平移后相对 PTS 为负的样本不写（丢弃）：开放 GOP 的前导 B 帧引用剪辑起点之前的画面，
 * 且负 PTS 会让 muxer 写坏时间轴。视频轨/音频轨共用这一条判据。
 */
export function keepSample(relUs: number): boolean {
  return relUs >= 0;
}

/** 剪辑成片命名：`edit_<源名去扩展名>_<yyyyMMdd_HHmmss>.mp4` */
export function clipNameOf(srcName: string, stampMs: number): string {
  const dot: number = srcName.lastIndexOf('.');
  const stem: string = dot > 0 ? srcName.substring(0, dot) : srcName;
  const d: Date = new Date(Number.isFinite(stampMs) ? stampMs : Date.now());
  const stamp: string = `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}_` +
    `${pad2(d.getHours())}${pad2(d.getMinutes())}${pad2(d.getSeconds())}`;
  return `edit_${stem}_${stamp}.mp4`;
}

/** 本地时间两位补零（不依赖 Locale，避免格式化差异） */
function pad2(v: number): string {
  return v < 10 ? `0${v}` : `${v}`;
}

/**
 * 入点关键帧在抽帧成片里的输出帧序号：抽帧片 PTS = 帧序号 × 1e6/dstFps 的闭式反推（四舍五入）。
 * dstFps 非正或无 sidecar 时调用方不写 sidecar；这里防御性返回 0。
 */
export function keyFrameIndexOf(basePtsUs: number, dstFps: number): number {
  if (dstFps <= 0 || !Number.isFinite(basePtsUs)) {
    return 0;
  }
  return Math.max(0, Math.floor((basePtsUs * dstFps + 500000) / 1000000));
}

/**
 * sidecar 平移重映射：只保留输出帧序号 >= shift 的记录，位次整体前移 shift。
 * n 是增量（该帧之前丢了几个源帧），与全局序号无关，平移后原样保留；
 * segments 清空（见文件头：剪辑成片是单文件，源段账不再适用）。
 */
export function remapDrops(log: ArcDropLog, shift: number): ArcDropLog {
  const s: number = Number.isFinite(shift) ? Math.max(0, Math.trunc(shift)) : 0;
  const drops: Array<DropMark> = [];
  for (const mark of log.drops) {
    if (mark.out >= s) {
      drops.push(new DropMark(mark.out - s, mark.n));
    }
  }
  return new ArcDropLog(log.mode, log.dstFps, drops, []);
}

/** 音轨选择：-1 不导音频，0 第一条，1 第二条（容器音轨序） */
export enum TrackChoice {
  NO_AUDIO = -1,
  ENV = 0,
  CAP = 1
}

/** TrackChoice → native audioOrdinal（-1/0/1；未知值按 -1） */
export function audioOrdinalOf(track: TrackChoice): number {
  if (track === TrackChoice.ENV) {
    return 0;
  }
  if (track === TrackChoice.CAP) {
    return 1;
  }
  return -1;
}
