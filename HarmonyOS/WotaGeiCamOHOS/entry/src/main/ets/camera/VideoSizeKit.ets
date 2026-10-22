/**
 * 帧周期注入的 Kit 版（探测版适配层）——对应上游 core/VideoSizePicker.kt 的
 * minFrameDurationNs(map, size) 与 videoSizeTable(a, map)（对齐上游 38d712a）。
 *
 * 平台差异（登记）：Android 按 (format, size) 逐项查 StreamConfigurationMap 的
 * getOutputMinFrameDuration（上游取 PRIVATE/JPEG 两表最小值）；OHOS 无逐项等价物，
 * 按 videoProfiles 命中档的 frameRateRange.max 折算 1e9/maxFps——与上游同为
 * 「最好情况」口径（能跑到的最快帧周期）。
 */
import { camera } from '@kit.CameraKit';
import { Size } from '../core/Constants';
import { CameraAbility } from '../core/CameraAbility';
import {
  VideoSizeTable, buildVideoSizeTable, SIZE_UNSUPPORTED_DURATION, sizeCloseTo, VideoSizeCandidates
} from '../core/VideoSizePicker';

/**
 * 该尺寸的最短帧周期（ns）：在 videoProfiles 里按 ±TOLERANCE_PX 容差找命中档，
 * 取各命中档 1e9/frameRateRange.max 的最小值（最快档）；无命中档 → SIZE_UNSUPPORTED_DURATION。
 */
export function minFrameDurationNsFromKit(capability: camera.CameraOutputCapability, size: Size): number {
  const profiles: Array<camera.VideoProfile> =
    capability.videoProfiles ?? [];
  let best: number = SIZE_UNSUPPORTED_DURATION;
  for (const p of profiles) {
    if (!sizeCloseTo(new Size(p.size.width, p.size.height), size, VideoSizeCandidates.TOLERANCE_PX)) {
      continue;
    }
    const range: camera.FrameRateRange | undefined = p.frameRateRange;
    if (range === undefined || range.max <= 0) {
      continue;
    }
    const duration: number = Math.round(1_000_000_000 / range.max);
    if (duration > 0 && (best <= 0 || duration < best)) {
      best = duration;
    }
  }
  return best;
}

/** 探测版档位表：buildVideoSizeTable 的 minDurationNs 注入点（上游 videoSizeTable(a, map) 等价） */
export function videoSizeTableFromKit(a: CameraAbility, capability: camera.CameraOutputCapability): VideoSizeTable {
  return buildVideoSizeTable(a, (s: Size): number => minFrameDurationNsFromKit(capability, s));
}
