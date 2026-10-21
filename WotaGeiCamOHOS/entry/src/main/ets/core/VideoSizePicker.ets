/**
 * 直译自上游 core/VideoSizePicker.kt（harmony 分支）。
 * 平台差异：
 * - videoSizeTable(map: StreamConfigurationMap) 与 minFrameDurationNs(map, size)
 *   不在本文件——OHOS 侧由相机适配层把 CameraKit 的输出能力帧周期接进
 *   buildVideoSizeTable 的 minDurationNs 注入参数，纯函数口径不变；
 * - Long → number（双精度，值域远小于 2^53，安全）。
 */
import { Diag } from '../diag/Diag';
import { Size, WotaTag, WotaTiers } from './Constants';
import { CameraAbility } from './CameraAbility';

/** 需求表里的一档分辨率（比例沿用需求文档给的字面标签） */
export class ReqSize {
  width: number;
  height: number;
  aspect: string;

  constructor(width: number, height: number, aspect: string) {
    this.width = width;
    this.height = height;
    this.aspect = aspect;
  }

  toSize(): Size {
    return new Size(this.width, this.height);
  }

  get pixels(): number {
    return this.width * this.height;
  }

  toString(): string {
    return `${this.width}x${this.height}(${this.aspect})`;
  }
}

/**
 * 候选表（参考表，最终可选项 = 与设备表求交）：需求「视频录制分辨率」19 档，按宽度降序。
 * 这里只放产品需求档位，不许写死任何机型尺寸；「原相机全屏分辨率」档在 buildVideoSizeTable
 * 里按设备屏幕比例运行时推导（传 screenAspect），换任何机型都会推出该机自己的全屏档。
 */
export class VideoSizeCandidates {
  /** 设备尺寸与需求档位的容差：HAL 常给出 4032x2268 之类与 4032x2272 差几像素的近似档 */
  static readonly TOLERANCE_PX: number = 2;

  /** 「设备支持但表内没有的最高档」补几项（02 文件第 4 节第 2 步） */
  static readonly EXTRA_TOP_COUNT: number = 1;

  static readonly CANDIDATES: Array<ReqSize> = [
    new ReqSize(7680, 4320, '16:9'),
    new ReqSize(4096, 3072, '4:3'),
    new ReqSize(4096, 2304, '16:9'),
    new ReqSize(4080, 3072, '4:3'),
    new ReqSize(4080, 3060, '4:3p'),
    new ReqSize(4032, 3024, '4:3'),
    new ReqSize(4032, 2272, '16:9'),
    new ReqSize(4000, 3000, '4:3'),
    new ReqSize(3840, 2160, '16:9'),
    new ReqSize(3648, 2736, '4:3'),
    new ReqSize(3440, 2448, '4:3'),
    new ReqSize(3264, 2448, '4:3'),
    new ReqSize(3024, 3024, '1:1'),
    new ReqSize(1920, 1440, '4:3'),
    new ReqSize(1920, 1080, '16:9'),
    new ReqSize(1280, 720, '16:9'),
    new ReqSize(1200, 720, '5:3'),
    new ReqSize(960, 720, '4:3'),
    new ReqSize(720, 720, '1:1')
  ];
}

/** 帧周期查不到（尺寸不在设备上任何输出表里）时的哨兵值 */
export const SIZE_UNSUPPORTED_DURATION: number = -1;

// 常用比例表按比值升序；20:9 是通用比例标签（全屏档在此展示），
// 只是"这个比值叫什么"的登记，不是机型参数
interface RatioEntry {
  label: string;
  ratio: number;
}

const STANDARD_RATIOS: Array<RatioEntry> = [
  { label: '1:1', ratio: 1.0 },
  { label: '5:3', ratio: 5.0 / 3.0 },
  { label: '4:3', ratio: 4.0 / 3.0 },
  { label: '3:2', ratio: 3.0 / 2.0 },
  { label: '16:10', ratio: 1.6 },
  { label: '16:9', ratio: 16.0 / 9.0 },
  { label: '18:9', ratio: 2.0 },
  { label: '20:9', ratio: 20.0 / 9.0 },
  { label: '21:9', ratio: 21.0 / 9.0 }
];

/**
 * 屏幕宽高比（无向，长边/短边）：「原相机全屏分辨率」档的运行时推导依据。
 * 竖横屏都归一到 ≥1 的比值——录像档天生长边在前（如 1920x1080），拿无向比才对得上。
 * 传 0 或负数返回 0（= 调用方还没量到屏幕，推导自动跳过）。
 */
export function screenAspectOf(screenW: number, screenH: number): number {
  if (screenW <= 0 || screenH <= 0) {
    return 0.0;
  }
  const a: number = screenW / screenH;
  return a >= 1.0 ? a : 1.0 / a;
}

/** 比例标签现算：先匹配常用比例（±0.02），否则 gcd 约分，避免机型怪尺寸显示 unknown */
export function aspectOf(w: number, h: number): string {
  if (w <= 0 || h <= 0) {
    return '-';
  }
  const ratio: number = w / h;
  for (const entry of STANDARD_RATIOS) {
    if (Math.abs(entry.ratio - ratio) <= 0.02) {
      return entry.label;
    }
  }
  const g: number = Math.max(1, gcd(w, h));
  return `${Math.floor(w / g)}:${Math.floor(h / g)}`;
}

function gcd(a: number, b: number): number {
  return b === 0 ? a : gcd(b, a % b);
}

export function sizeCloseTo(a: Size | undefined, b: Size | undefined,
  tolerancePx: number = VideoSizeCandidates.TOLERANCE_PX): boolean {
  if (a === undefined || b === undefined) {
    return false;
  }
  return Math.abs(a.width - b.width) <= tolerancePx && Math.abs(a.height - b.height) <= tolerancePx;
}

/**
 * 换镜头时把「用户要的那档」在新镜头能力表里落档。
 * 顺序：原样能给 → 同画幅比例里不超过原像素量的最大档 → 同比例里最接近的一档 →
 * 新镜头根本没有同比例档时，才退回与比例无关的最接近档。
 * 关键是按用户要的档位求解、不按上一镜头实际生效的档位求解，否则每换一次镜头
 * 就单向往下棘轮一次（1920x1080 → 1280x720 → 切回来再也回不去）。
 */
export function resolveSizeFor(want: Size, available: Array<Size>,
  tolerancePx: number = VideoSizeCandidates.TOLERANCE_PX): Size | undefined {
  if (available.length === 0) {
    return undefined;
  }
  const exact: Size | undefined = available.find((s: Size): boolean => sizeCloseTo(s, want, tolerancePx));
  if (exact !== undefined) {
    return exact;
  }
  const wantAspect: string = aspectOf(want.width, want.height);
  let pool: Array<Size> = available.filter((s: Size): boolean => aspectOf(s.width, s.height) === wantAspect);
  if (pool.length === 0) {
    pool = available;
  }
  const target: number = want.pixels;
  let bestBelow: Size | undefined = undefined;
  for (const s of pool) {
    if (s.pixels <= target && (bestBelow === undefined || s.pixels > bestBelow.pixels)) {
      bestBelow = s;
    }
  }
  if (bestBelow !== undefined) {
    return bestBelow;
  }
  let bestNear: Size | undefined = undefined;
  for (const s of pool) {
    if (bestNear === undefined || Math.abs(s.pixels - target) < Math.abs(bestNear.pixels - target)) {
      bestNear = s;
    }
  }
  if (bestNear !== undefined) {
    return bestNear;
  }
  let minSize: Size | undefined = undefined;
  for (const s of available) {
    if (minSize === undefined || s.pixels < minSize.pixels) {
      minSize = s;
    }
  }
  return minSize;
}

/** 一档 UI 可展示的分辨率选项；supported=false 即需求档位本机不支持（灰显） */
export class VideoSizeOption {
  size: Size;
  aspect: string;
  inRequirement: boolean;
  supported: boolean;
  feasibleFps: Array<number>;
  minFrameDurationNs: number;
  highSpeed: boolean;

  constructor(option: VideoSizeOptionArgs) {
    this.size = option.size;
    this.aspect = option.aspect;
    this.inRequirement = option.inRequirement;
    this.supported = option.supported;
    this.feasibleFps = option.feasibleFps;
    this.minFrameDurationNs = option.minFrameDurationNs;
    this.highSpeed = option.highSpeed;
  }

  /** 能否跑到目标帧率：高速专用尺寸（120/240）走 highSpeed 标记，不参与 24/25 可行性判断 */
  canRun(fps: number): boolean {
    return this.feasibleFps.includes(fps);
  }

  /** 必须帧率优先展示：命中需求表 + 支持 + 能跑必须档 三者叠加成分组序 */
  get displayRank(): number {
    if (!this.supported) {
      return 3;
    }
    return this.feasibleFps.length > 0 ? 0 : 1;
  }
}

export interface VideoSizeOptionArgs {
  size: Size;
  aspect: string;
  inRequirement: boolean;
  supported: boolean;
  feasibleFps: Array<number>;
  minFrameDurationNs: number;
  highSpeed: boolean;
}

/** 分辨率档位表：UI 顶部「本机支持范围 …～」文案数据 + 可选项列表 */
export class VideoSizeTable {
  options: Array<VideoSizeOption>;
  maxSize: Size | undefined;
  minSize: Size | undefined;
  aspects: Array<string>;
  requiredFps: Array<number>;
  fixedFps: Array<number>;

  constructor(table: VideoSizeTableArgs) {
    this.options = table.options;
    this.maxSize = table.maxSize;
    this.minSize = table.minSize;
    this.aspects = table.aspects;
    this.requiredFps = table.requiredFps;
    this.fixedFps = table.fixedFps;
  }

  get supported(): Array<VideoSizeOption> {
    return this.options.filter((o: VideoSizeOption): boolean => o.supported);
  }

  get unsupported(): Array<VideoSizeOption> {
    return this.options.filter((o: VideoSizeOption): boolean => !o.supported);
  }

  optionOf(size: Size, tolerancePx: number = VideoSizeCandidates.TOLERANCE_PX): VideoSizeOption | undefined {
    return this.options.find((o: VideoSizeOption): boolean => sizeCloseTo(o.size, size, tolerancePx));
  }

  isSupported(size: Size, tolerancePx: number = VideoSizeCandidates.TOLERANCE_PX): boolean {
    const o: VideoSizeOption | undefined = this.optionOf(size, tolerancePx);
    return o !== undefined && o.supported;
  }

  /** 换挡后回填默认尺寸用：优先「支持且能跑必须帧率」的最大档 */
  firstUsable(): Size | undefined {
    const usableList: Array<VideoSizeOption> = this.options.filter((o: VideoSizeOption): boolean => o.supported);
    const withFps: VideoSizeOption | undefined =
      usableList.find((o: VideoSizeOption): boolean => o.feasibleFps.length > 0);
    if (withFps !== undefined) {
      return withFps.size;
    }
    let best: Size | undefined = undefined;
    for (const o of usableList) {
      if (best === undefined || o.size.pixels > best.pixels) {
        best = o.size;
      }
    }
    return best;
  }
}

export interface VideoSizeTableArgs {
  options: Array<VideoSizeOption>;
  maxSize: Size | undefined;
  minSize: Size | undefined;
  aspects: Array<string>;
  requiredFps: Array<number>;
  fixedFps: Array<number>;
}

/**
 * 纯函数：需求候选表 × 设备输出表求交（±2 像素容差）。
 *
 * @param minDurationNs 该尺寸的最短帧周期；单测可直接注入函数，不需要平台环境
 * @param screenAspect 设备屏幕宽高比（screenAspectOf 归一值；0/null = 不推导全屏档）。
 *        「分辨率增加原相机全屏分辨率」的机型无关实现：在设备能力表里找与屏幕同比、
 *        像素最大的档进表，换任何机型推出的都是该机自己的全屏档，不写死任何尺寸。
 */
export function buildVideoSizeTable(
  a: CameraAbility,
  minDurationNs: (s: Size) => number = (): number => SIZE_UNSUPPORTED_DURATION,
  requiredFps: Array<number> = Array.from(WotaTiers.REQUIRED_FPS).sort((x: number, y: number): number => x - y),
  tolerancePx: number = VideoSizeCandidates.TOLERANCE_PX,
  screenAspect: number | undefined = undefined
): VideoSizeTable {
  const device: Array<Size> = a.videoSizes;
  const options: Array<VideoSizeOption> = [];

  for (const cand of VideoSizeCandidates.CANDIDATES) {
    // 命中时以设备实际尺寸为准（录像面按它申请才不会 IllegalArgumentException）
    const match: Size | undefined = device.find((s: Size): boolean => sizeCloseTo(s, cand.toSize(), tolerancePx));
    options.push(optionOfArgs(cand.toSize(), match, cand.aspect, true, minDurationNs, requiredFps, a, tolerancePx));
  }

  // 全屏档：屏幕同比里像素最大的设备档；已与需求档求交过的（或屏幕还没量到）就不加
  // ——防同一尺寸出两行，也防 4:3 屏上把需求表已有的最大 4:3 档重复进表
  if (screenAspect !== undefined && screenAspect > 0.0) {
    let fullScreen: Size | undefined = undefined;
    for (const s of device) {
      if (s.width <= 0 || s.height <= 0) {
        continue;
      }
      const ratio: number = s.width / s.height;
      const unoriented: number = ratio >= 1.0 ? ratio : 1.0 / ratio;
      if (Math.abs(unoriented - screenAspect) <= ASPECT_RATIO_TOLERANCE * screenAspect) {
        if (fullScreen === undefined || s.pixels > fullScreen.pixels) {
          fullScreen = s;
        }
      }
    }
    if (fullScreen !== undefined && !options.some((o: VideoSizeOption): boolean => sizeCloseTo(o.size, fullScreen, tolerancePx))) {
      options.push(optionOfArgs(fullScreen, fullScreen, undefined, true, minDurationNs, requiredFps, a, tolerancePx));
    }
  }

  const extras: Array<Size> = device
    .filter((d: Size): boolean => !options.some((o: VideoSizeOption): boolean => sizeCloseTo(d, o.size, tolerancePx)))
    .sort((x: Size, y: Size): number => y.pixels - x.pixels)
    .slice(0, VideoSizeCandidates.EXTRA_TOP_COUNT);
  for (const s of extras) {
    options.push(optionOfArgs(s, s, undefined, false, minDurationNs, requiredFps, a, tolerancePx));
  }

  const sorted: Array<VideoSizeOption> = options.slice().sort((x: VideoSizeOption, y: VideoSizeOption): number => {
    if (x.displayRank !== y.displayRank) {
      return x.displayRank - y.displayRank;
    }
    if (x.inRequirement !== y.inRequirement) {
      return x.inRequirement ? -1 : 1;
    }
    return y.size.pixels - x.size.pixels;
  });

  let usable: Array<VideoSizeOption> = sorted.filter((o: VideoSizeOption): boolean => o.supported && o.feasibleFps.length > 0);
  if (usable.length === 0) {
    usable = sorted.filter((o: VideoSizeOption): boolean => o.supported);
  }
  const usableSizes: Array<Size> = usable.map((o: VideoSizeOption): Size => o.size);
  const maxSize: Size | undefined = maxPixelsSize(usableSizes);
  const minSize: Size | undefined = minPixelsSize(usableSizes);
  const supportedAspects: Array<string> =
    sorted.filter((o: VideoSizeOption): boolean => o.supported)
      .map((o: VideoSizeOption): string => o.aspect)
      .filter((v: string, i: number, arr: Array<string>): boolean => arr.indexOf(v) === i);
  const orderedAspects: Array<string> =
    WotaTiers.ASPECTS.filter((asp: string): boolean => supportedAspects.includes(asp))
      .concat(supportedAspects.filter((asp: string): boolean => !WotaTiers.ASPECTS.includes(asp)));

  return new VideoSizeTable({
    options: sorted,
    maxSize: maxSize,
    minSize: minSize,
    aspects: orderedAspects,
    requiredFps: requiredFps,
    fixedFps: requiredFps.filter((f: number): boolean => a.hasFixedFps(f))
  });
}

function optionOfArgs(
  reqSize: Size,
  deviceSize: Size | undefined,
  reqAspect: string | undefined,
  inRequirement: boolean,
  minDurationNs: (s: Size) => number,
  requiredFps: Array<number>,
  a: CameraAbility,
  tolerancePx: number
): VideoSizeOption {
  const supported: boolean = deviceSize !== undefined;
  const size: Size = deviceSize ?? reqSize;
  const duration: number = supported ? minDurationNs(size) : SIZE_UNSUPPORTED_DURATION;
  const feasible: Array<number> = duration > 0
    ? requiredFps.filter((f: number): boolean => f > 0 && duration <= Math.floor(WotaTiers.NS_PER_SECOND / f))
    : [];
  return new VideoSizeOption({
    size: size,
    aspect: reqAspect ?? aspectOf(size.width, size.height),
    inRequirement: inRequirement,
    supported: supported,
    feasibleFps: feasible,
    minFrameDurationNs: duration,
    highSpeed: a.highSpeedSizes.some((s: Size): boolean => sizeCloseTo(s, size, tolerancePx))
  });
}

/**
 * DIRECT 直显预览流的面积上限（降热：预览不必超过 1080p 档）。
 * 这是产品功耗策略常量，不是机型数值——具体挑哪一档仍由设备能力表决定。
 */
export const DIRECT_PREVIEW_MAX_PIXELS: number = 1920 * 1080;

/** 宽高比一致性判定容差：HAL 常给 1920x1088 / 4160x1872 这类近似档 */
const ASPECT_RATIO_TOLERANCE: number = 0.02;

/**
 * 纯函数：给 DIRECT 直显挑一条预览流尺寸（不交给 HAL 自选，否则会被拉成视图比例导致失真）。
 * 规则（全部基于能力表，无机型硬编码）：
 * 1. 宽高比必须与录像档一致（±2%），保证预览与录出同框；
 * 2. 面积不超过 min(录像档, DIRECT_PREVIEW_MAX_PIXELS)——预览流不该比录像流还大；
 * 3. 在满足前两条的档里取「面积不小于承载视图」的最小档（够清晰又省电）；
 *    全部小于视图面积时退到最大档（此时靠等比适配加黑边，仍不失真）。
 */
export function pickDirectPreviewSize(
  candidates: Array<Size>,
  record: Size,
  viewWidthPx: number,
  viewHeightPx: number,
  maxPixels: number = DIRECT_PREVIEW_MAX_PIXELS
): Size | undefined {
  if (candidates.length === 0 || record.width <= 0 || record.height <= 0) {
    return undefined;
  }
  const ratio: number = record.width / record.height;
  let sameAspect: Array<Size> = candidates.filter((s: Size): boolean => {
    const r: number = s.width / s.height;
    return Math.abs(r - ratio) <= ASPECT_RATIO_TOLERANCE * ratio;
  });
  if (sameAspect.length === 0) {
    sameAspect = candidates;
  }
  const ceiling: number = Math.min(record.pixels, maxPixels);
  const pool: Array<Size> = sameAspect.filter((s: Size): boolean => s.pixels >= 1 && s.pixels <= ceiling);
  if (pool.length === 0) {
    return undefined;
  }
  const viewArea: number = viewWidthPx * viewHeightPx;
  // 视图还没布局（面积 0）时不能按「不小于视图」筛，否则会一路退到最小档
  if (viewArea <= 0) {
    return maxPixelsSize(pool);
  }
  const bigEnough: Array<Size> = pool.filter((s: Size): boolean => s.pixels >= viewArea);
  return (minPixelsSize(bigEnough) ?? maxPixelsSize(pool));
}

/** 调试用：打印档位表上下限、可用比例与 24/25 固定档判定 */
export function dumpVideoSizeTable(table: VideoSizeTable): void {
  const tag: string = WotaTag.VIDEO_SIZE;
  Diag.i(tag, `sizeTable options=${table.options.length} supported=${table.supported.length}` +
    ` max=${fmtSize(table.maxSize)} min=${fmtSize(table.minSize)} aspects=${table.aspects.join(',')}`);
  Diag.i(tag, `sizeTable requiredFps=${table.requiredFps.join(',')} fixedFps=${table.fixedFps.join(',')}`);
  const head: Array<VideoSizeOption> = table.options.slice(0, 24);
  for (const o of head) {
    Diag.i(tag, `size ${fmtSize(o.size)}(${o.aspect}) supported=${o.supported}` +
      ` required=${o.inRequirement} minDuration=${o.minFrameDurationNs} feasibleFps=${o.feasibleFps.join(',')}` +
      ` highSpeed=${o.highSpeed}`);
  }
}

function maxPixelsSize(sizes: Array<Size>): Size | undefined {
  let best: Size | undefined = undefined;
  for (const s of sizes) {
    if (best === undefined || s.pixels > best.pixels) {
      best = s;
    }
  }
  return best;
}

function minPixelsSize(sizes: Array<Size>): Size | undefined {
  let best: Size | undefined = undefined;
  for (const s of sizes) {
    if (best === undefined || s.pixels < best.pixels) {
      best = s;
    }
  }
  return best;
}

function fmtSize(s: Size | undefined): string {
  return s === undefined ? 'none' : `${s.width}x${s.height}`;
}
