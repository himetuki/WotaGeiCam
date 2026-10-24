/**
 * 播放器纯模型（上游 player/PlayerEngine.kt 的 AbRange / PlayerSpeedTiers 与
 * player/PlayerScreen.kt 的 clampPan 直译，对齐上游 38d712a）。
 *
 * 平台偏离（登记）：上游倍速档 0.1/0.3/0.5/0.75/1.0；OHOS AVPlayer 无 0.1/0.3，
 * 本批用平台档 0.125/0.25/0.5/0.75/1.0。
 */

/** A/B 循环区间：aMs/bMs = -1 表示未打点；valid = A 有效且 B > A */
export class AbRange {
  aMs: number;
  bMs: number;
  loopEnabled: boolean;

  constructor(aMs: number = -1, bMs: number = -1, loopEnabled: boolean = false) {
    this.aMs = aMs;
    this.bMs = bMs;
    this.loopEnabled = loopEnabled;
  }

  get valid(): boolean {
    return this.aMs >= 0 && this.bMs > this.aMs;
  }
}

/** 平移结果（ArkTS 无元组/Offset） */
export class Pan {
  x: number;
  y: number;

  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

export class PlayerSpeedTiers {
  /** OHOS AVPlayer 支持档位（平台偏离见文件头） */
  static readonly TIERS: Array<number> = [0.125, 0.25, 0.5, 0.75, 1.0];
  static readonly DEFAULT: number = 1.0;

  /** 取最近档（上游 minByOrNull(abs(tier - f))） */
  static normalize(f: number): number {
    let best: number = PlayerSpeedTiers.DEFAULT;
    let bestDiff: number = Number.MAX_VALUE;
    for (const tier of PlayerSpeedTiers.TIERS) {
      const diff: number = Math.abs(tier - f);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = tier;
      }
    }
    return best;
  }

  /** 循环到下一档（UI 倍速按钮） */
  static next(f: number): number {
    const cur: number = PlayerSpeedTiers.normalize(f);
    for (let i = 0; i < PlayerSpeedTiers.TIERS.length; i++) {
      if (PlayerSpeedTiers.TIERS[i] === cur) {
        return PlayerSpeedTiers.TIERS[(i + 1) % PlayerSpeedTiers.TIERS.length];
      }
    }
    return PlayerSpeedTiers.DEFAULT;
  }

  static label(f: number): string {
    return `${PlayerSpeedTiers.normalize(f)}x`;
  }
}

function clampPanAxis(v: number, half: number): number {
  if (half <= 0) {
    return 0; // s<1 时半域为负，coerceIn 域会倒置
  }
  return Math.min(half, Math.max(-half, v));
}

/**
 * 双指平移的半域钳制（上游 clampPan 逐行）：每轴半域 = viewport×(scale−1)/2；
 * 半域 <= 0（缩到 1x 以下/视口未量出）直接归零。
 */
export function clampPan(panX: number, panY: number, scale: number,
  viewportW: number, viewportH: number): Pan {
  const halfX: number = viewportW * (scale - 1) / 2;
  const halfY: number = viewportH * (scale - 1) / 2;
  return new Pan(clampPanAxis(panX, halfX), clampPanAxis(panY, halfY));
}

/**
 * 逆时针旋转的四分之一圈计数（RV 批，直译上游 PlayerScreen.nextCcwQuarter）：
 * 0→3→2→1→0 循环，连按 4 次必回原角；负数/超界输入先按 4 折回等价角再走一步
 * （TS 的 % 与 Kotlin 同为保号余数，语义逐字）。会话态观看辅助：不持久化、
 * 换片不清零（与镜像开关同口径）。
 */
export function nextCcwQuarter(q: number): number {
  return ((q % 4) + 3) % 4;
}
