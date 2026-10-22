/**
 * 直译自上游 record/BitratePolicy.kt（harmony 分支，含同文件的两个 object：BitratePolicy 与 StorageEstimate）。
 * 平台差异：
 * - Kotlin Long 整除向零截断，值域全正 ⇒ 一律 Math.floor / Math.trunc 等价镜像；
 * - fallback 的 (0.2*100).toLong() 截断 → Math.trunc 镜像（0.2*100 双精度=20.000000000000004 → 20）；
 * - resolve 的 HEVC 缩放 (capped * 0.9f).toInt() → Math.trunc(Math.fround(capped * 0.9))：
 *   Kotlin 是先入 float32 再乘，本侧 double 乘后再入 float32——档位值域内（WotaTiers.BITRATES
 *   全部档位值）等价，档位表外奇数存在 ±2 分叉（UI 约束 userBitrate 取自档位表，实际不可达）；
 * - maxTier 空表哨兵 Kotlin Int.MAX_VALUE → 2_147_483_647；
 * - "%.1f" 一位小数格式化复用 core/Constants.ets 的 fixed1（单一实现）；humanize 的
 *   minutes/60f 除法先 Math.fround 对齐 float32 域再格式化（60..6000 全值域与上游逐字符一致）；
 * - resolve(p: RecordProfile) 便捷重载随 RecordProfile.ets 移植后补（本批未入库，见登记文档）。
 */

import { WotaTiers, fixed1 } from '../core/Constants';

/**
 * 三段式码率策略：用户自设 > 自动表 > 公式兜底，HEVC 再整体 ×0.9。
 *
 * 数值来源是产品档位而非机型能力，所以这里的分辨率分界、系数都是常量；
 * 需求给的 5/10/20/35/50 Mbps 六档在 `WotaTiers.BITRATES`，UI 只从那里取候选值。
 */
export class BitratePolicy {
  /** 音频码率固定 128 kbps */
  static readonly AUDIO_BITRATE: number = 128_000;

  /** 自动表：720P→8M、1080P→16M、4K→32M */
  private static readonly AUTO_720P: number = 8_000_000;
  private static readonly AUTO_1080P: number = 16_000_000;
  private static readonly AUTO_4K: number = 32_000_000;

  /**
   * 自动表的分界像素：720P/1080P 之间取两者像素数的算术中间值（不是几何），
   * 高于 4K 档上界即无表值 → 交给兜底公式
   */
  private static readonly PX_720P: number = 1280 * 720;
  private static readonly PX_1080P: number = 1920 * 1080;
  private static readonly PX_4K: number = 3840 * 2160;
  private static readonly BAND_1080_UPPER: number = 5_000_000;
  private static readonly BAND_4K_UPPER: number = 9_000_000;

  /** fps ≥ 60 时全档翻倍 */
  private static readonly DOUBLE_FPS: number = 60;

  /** 兜底公式 `w × h × 30 × 0.2` */
  private static readonly FALLBACK_FPS: number = 30;
  private static readonly FALLBACK_BPP: number = 0.2;

  /** HEVC / HDR 同画质省 10% */
  private static readonly HEVC_FACTOR: number = 0.9;

  /** 兜底下限：低于 1 Mbps 编码器画质会退化到不可用，用于钳住极小分辨率 */
  static readonly MIN_BITRATE: number = 1_000_000;

  /**
   * 自动表命中值；返回 0 表示分辨率超出表的上界（例如 8K、超宽画幅），调用方改用 fallback。
   * 注意 1440P（约 3.69M 像素）是落在表内的，按 1080P 档给 16M。
   */
  static autoTable(width: number, height: number, fps: number): number {
    const px: number = width * height;
    let base: number;
    if (px <= Math.floor((BitratePolicy.PX_720P + BitratePolicy.PX_1080P) / 2)) {
      base = BitratePolicy.AUTO_720P;
    } else if (px <= BitratePolicy.BAND_1080_UPPER) {
      base = BitratePolicy.AUTO_1080P;
    } else if (px <= BitratePolicy.BAND_4K_UPPER) {
      base = BitratePolicy.AUTO_4K;
    } else {
      base = 0;
    }
    if (base === 0) {
      return 0;
    }
    return fps >= BitratePolicy.DOUBLE_FPS ? base * 2 : base;
  }

  /** 兜底公式：`w × h × 30 × 0.2`（number 双精度无 8K 溢出，再钳回 Int 域） */
  static fallback(width: number, height: number): number {
    const bpp100: number = Math.trunc(BitratePolicy.FALLBACK_BPP * 100); // 镜像 Kotlin (0.2*100).toLong() 截断
    const v: number = Math.floor(width * height * BitratePolicy.FALLBACK_FPS * bpp100 / 100);
    return Math.min(2_147_483_647, Math.max(BitratePolicy.MIN_BITRATE, v));
  }

  /**
   * 三段式取值：userBitrate > 0 直接用；否则自动表；自动表无档位再用兜底公式。
   * HEVC 时整体 ×0.9（对三段来源统一生效）。
   *
   * 自动/兜底两段被产品最高档（WotaTiers.BITRATES 末项）钳住：8K 的公式值是 199 Mbps，
   * 超出手机编码器实际能力，prepare 会被拒；用户自设档一律尊重不钳。
   */
  static resolve(userBitrate: number, width: number, height: number, fps: number, hevc: boolean): number {
    const fromUser: boolean = userBitrate > 0;
    const auto: number = BitratePolicy.autoTable(width, height, fps);
    let raw: number;
    if (fromUser) {
      raw = userBitrate;
    } else if (auto > 0) {
      raw = auto;
    } else {
      raw = BitratePolicy.fallback(width, height);
    }
    const capped: number = fromUser ? raw : Math.min(raw, BitratePolicy.maxTier());
    // 对应 Kotlin (capped * 0.9f).toInt()：档位值域内等价（档位表外奇数有 ±2 分叉，
    // UI 约束 userBitrate 取自档位表、实际不可达，见文件头平台差异）
    const scaled: number = hevc ? Math.trunc(Math.fround(capped * BitratePolicy.HEVC_FACTOR)) : capped;
    return Math.max(BitratePolicy.MIN_BITRATE, scaled);
  }

  /** 产品码率档位上限（档位表为空时退化为不钳制；哨兵 = Kotlin Int.MAX_VALUE） */
  static maxTier(): number {
    if (WotaTiers.BITRATES.length === 0) {
      return 2_147_483_647;
    }
    let m: number = WotaTiers.BITRATES[0];
    for (const b of WotaTiers.BITRATES) {
      if (b > m) {
        m = b;
      }
    }
    return m;
  }

  // resolve(p: RecordProfile) 便捷重载：随 RecordProfile.ets 移植后补（本批未入库）

  /**
   * 把任意码率吸附到最近的 `WotaTiers.BITRATES` 档位（5/10/20/35/50 Mbps）。
   * 用对数距离而非算术距离：码率档位是等比分布的，4K/60fps 的 64M 也不会错配到 5M。
   */
  static nearestTier(bitrate: number): number {
    if (WotaTiers.BITRATES.length === 0) {
      return bitrate;
    }
    let best: number = WotaTiers.BITRATES[0];
    let bestDist: number = Math.abs(Math.log(WotaTiers.BITRATES[0]) - Math.log(bitrate));
    for (const t of WotaTiers.BITRATES) {
      const dist: number = Math.abs(Math.log(t) - Math.log(bitrate));
      if (dist < bestDist) {
        best = t;
        bestDist = dist;
      }
    }
    return best;
  }

  /**
   * UI 默认推荐档：按分辨率/帧率给出应高亮的产品档位（用户可再改）。
   * 用户已自设且合法时，直接回落到用户档位的最近合法档。
   */
  static recommendTier(userBitrate: number, width: number, height: number, fps: number, hevc: boolean): number {
    return BitratePolicy.nearestTier(BitratePolicy.resolve(userBitrate, width, height, fps, hevc));
  }
}

/**
 * 「还能拍多久」的估算（顶栏容量胶囊用）。
 *
 * 只按视频码率折算，不含音频（约 128kbps，对分钟数影响 <1%）；
 * 结果是上界：分段开销与 Moov 原子没算进来，所以文案一律带「约」。
 */
export class StorageEstimate {
  /** 可用容量（MB）与视频码率（bps）→ 可拍分钟数，向下取整；码率非法时返回 0 */
  static minutesOf(freeMb: number, bitrateBps: number): number {
    if (freeMb <= 0 || bitrateBps <= 0) {
      return 0;
    }
    return Math.floor(freeMb * 1024 * 1024 * 8 / bitrateBps / 60);
  }

  /** 分钟数 → 读数：不足一小时给「N 分钟」，过了一小时给「h.h 小时」，超过 100 小时只报「100+」 */
  static humanize(minutes: number): string {
    if (minutes <= 0) {
      return '0';
    }
    if (minutes >= 6_000) {
      return '100+';
    }
    if (minutes >= 60) {
      // 对应 String.format(Locale.US, "%.1f", minutes / 60f)：除法先 Math.fround 入 float32 域
      // （对齐 Kotlin Float 除法；63/123 等 63 倍数邻域 double 除法会差 0.1），HALF_UP 取整
      // 由 fixed1 承担——float32 值 ×10 在 double 中精确，Math.round 的半上即 %.1f 的半上
      return fixed1(Math.fround(minutes / 60));
    }
    return `${minutes}`;
  }

  /** humanize 用的是小时还是分钟：决定文案单位 */
  static unitOf(minutes: number): boolean {
    return minutes >= 60;
  }
}
