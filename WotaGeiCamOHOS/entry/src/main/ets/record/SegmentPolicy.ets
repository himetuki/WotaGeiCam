/**
 * 分段轮转纯策略（对齐上游 record/VideoStore.kt 的 3.5GiB 分段口径与 .drops.json segments）：
 * - MAX_FILE_BYTES = 3_758_096_384（3.5GiB）；ROTATE_SAFETY = 0.98；
 * - 触发阈值 = floor(MAX_FILE_BYTES × 0.98)，已写字节 >= 阈值即轮转；
 * - 段清单 first 为前序 count 前缀和（全局输出位次），segment 为下标，lostMs 逐段；
 * - rotateFramesUpper 只作换段丢帧上界（诊断），与 FrameLedger 的 rotate 字段同义。
 */
import { ArcDropSegment } from './ArcDropLog';

export class SegmentPolicy {
  /** 3.5GiB（上游 MAX_FILE_BYTES） */
  static readonly MAX_FILE_BYTES: number = 3_758_096_384;
  /** 轮转安全系数（上游 ROTATE_SAFETY）：提前 2% 收段，吸收 CSD/容器开销 */
  static readonly ROTATE_SAFETY: number = 0.98;

  static segmentLimitBytes(maxBytes: number = SegmentPolicy.MAX_FILE_BYTES): number {
    return Math.floor(maxBytes * SegmentPolicy.ROTATE_SAFETY);
  }

  static shouldRotate(writtenBytes: number, limitBytes: number): boolean {
    return limitBytes > 0 && writtenBytes >= limitBytes;
  }

  /** 段清单：counts 为各段 GL 输出帧数，lostMs 为各段开始前的换段停顿 */
  static buildDropSegments(counts: Array<number>, lostMs: Array<number>): Array<ArcDropSegment> {
    const segments: Array<ArcDropSegment> = [];
    let first: number = 0;
    for (let i = 0; i < counts.length; i++) {
      const lost: number = i < lostMs.length ? lostMs[i] : 0;
      segments.push(new ArcDropSegment(i, first, counts[i], lost));
      first += counts[i];
    }
    return segments;
  }

  /** 换段丢帧上界：ceil(lostMs × fps / 1000) */
  static rotateFramesUpper(lostMs: number, fps: number): number {
    if (lostMs <= 0) {
      return 0;
    }
    return Math.ceil(lostMs * Math.max(1, fps) / 1000);
  }

  /** 段计数摘要（日志用）：seg=count,... */
  static segmentSummary(counts: Array<number>): string {
    const parts: Array<string> = [];
    for (let i = 0; i < counts.length; i++) {
      parts.push(`${i}:${counts[i]}`);
    }
    return parts.join(',');
  }
}
