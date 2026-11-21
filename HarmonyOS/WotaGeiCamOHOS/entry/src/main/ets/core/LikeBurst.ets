/**
 * 收藏红心动画的**纯函数层**——上游 ui/design/LikeHeart.kt LikeBurst object（a3855c3）
 * 直译。来源：Bencho "Like"（MIT，bencho.dev/licence）的红心部分。**刻意不移植数字滚动**
 * （本工程收藏没有计数）。
 *
 * 时间线（设计稿原值，一条主进度 820ms 统摄三元素）：
 * - **绽放环** 560ms：0% 半径 0.15 实心 → 40% 长到 1.0 仍实心 → 75% 边框收到 2/17（变环）
 *   → 100% 边框 0、放大 1.12、淡出；颜色 40% 由红转紫（#e5484d → #cc8ef5）；
 * - **红心** 760ms：0~28% 不可见 → 62% 弹到 1.25 → 100% 回 1；
 * - **七对彩点** 620ms 延迟 200ms：沿辐条 12px → (18 + fly×14 + far)，终点缩 0；
 *   12% 淡入；侧向 ±4px、第二颗靠后 5px。
 */
export class LikeBurst {
  /** 主进度总时长（红心 760ms + 彩点延迟余量） */
  static readonly DURATION_MS: number = 820;
  static readonly DOT_START_MS: number = 200;
  static readonly DOT_DURATION_MS: number = 620;
  static readonly BLOOM_MS: number = 560;
  static readonly HEART_MS: number = 760;

  /** 设计稿几何（px 按 dp 用）：20dp 心 / 34dp 环 / 5dp 点 */
  static readonly HEART_GLYPH_DP: number = 24;
  static readonly BLOOM_RADIUS_DP: number = 17;
  static readonly DOT_DP: number = 5;
  static readonly DOT_FAR_DP: number = -5;
  static readonly DOT_SIDE_DP: number = 4;
  /** 纸屑飞行幅度（设计稿 burst 默认 50 → fly = 0.5） */
  static readonly FLY_DEFAULT: number = 0.5;

  /** 设计稿红/紫（绽放环 40% 转色） */
  static readonly BLOOM_RED: number = 0xFFE5484D;
  static readonly BLOOM_PURPLE: number = 0xFFCC8EF5;
  /** 七色纸屑（每对两颗不同色；取色下标 pair==0 ? k : k+3） */
  static readonly CONFETTI: Array<number> = [
    0xFFF48EA7, 0xFFCC8EF5, 0xFF8CE8C3, 0xFF91D2FA,
    0xFFF5A524, 0xFFE5484D, 0xFF9FC7FA
  ];

  private static phase(t: number, startMs: number, durMs: number): number {
    return Math.min(1, Math.max(0, (t * LikeBurst.DURATION_MS - startMs) / durMs));
  }

  private static smooth(x: number): number {
    return x * x * (3 - 2 * x);
  }

  /** 红心缩放：0 →（28% 起）1.25 →（62% 起）1 */
  static heartScale(t: number): number {
    const p: number = LikeBurst.phase(t, 0, LikeBurst.HEART_MS);
    if (p < 0.28) {
      return 0;
    }
    if (p < 0.62) {
      return 1.25 * LikeBurst.smooth((p - 0.28) / 0.34);
    }
    return 1.25 - 0.25 * LikeBurst.smooth((p - 0.62) / 0.38);
  }

  /** 绽放环缩放（0.15 → 1 → 1.12） */
  static bloomScale(t: number): number {
    const p: number = LikeBurst.phase(t, 0, LikeBurst.BLOOM_MS);
    if (p < 0.4) {
      return 0.15 + 0.85 * (p / 0.4);
    }
    return 1 + 0.12 * LikeBurst.smooth((p - 0.4) / 0.6);
  }

  /** 绽放环"边框占半径"比例：1.0 = 实心 → 2/17（2px 边框）→ 0（空掉淡出） */
  static bloomBorderFraction(t: number): number {
    const p: number = LikeBurst.phase(t, 0, LikeBurst.BLOOM_MS);
    if (p < 0.4) {
      return 1;
    }
    if (p < 0.75) {
      return 1 - (1 - 2 / 17) * LikeBurst.smooth((p - 0.4) / 0.35);
    }
    return (2 / 17) * (1 - (p - 0.75) / 0.25);
  }

  /** 绽放环透明度：75% 前恒 1，之后收 0 */
  static bloomAlpha(t: number): number {
    const p: number = LikeBurst.phase(t, 0, LikeBurst.BLOOM_MS);
    return p < 0.75 ? 1 : Math.min(1, Math.max(0, 1 - (p - 0.75) / 0.25));
  }

  /** 红 → 紫混合量（0 = 设计稿红、1 = 设计稿紫）：40% 处一次跳到位 */
  static bloomPurple(t: number): number {
    return LikeBurst.phase(t, 0, LikeBurst.BLOOM_MS) < 0.4 ? 0 : 1;
  }

  /** ARGB 红→紫线性混合（bloomPurple 的取色面；uint 域，JS 位运算截 int32 由调用方掩回） */
  static bloomColorArgb(t: number): number {
    const p: number = LikeBurst.bloomPurple(t);
    const mix: (a: number, b: number) => number =
      (a: number, b: number): number => Math.round(a + (b - a) * p);
    const r: number = mix((LikeBurst.BLOOM_RED >>> 16) & 0xFF, (LikeBurst.BLOOM_PURPLE >>> 16) & 0xFF);
    const g: number = mix((LikeBurst.BLOOM_RED >>> 8) & 0xFF, (LikeBurst.BLOOM_PURPLE >>> 8) & 0xFF);
    const b: number = mix(LikeBurst.BLOOM_RED & 0xFF, LikeBurst.BLOOM_PURPLE & 0xFF);
    return (0xFF000000 + (r << 16) + (g << 8) + b) >>> 0;
  }

  /** 彩点进度；< 0 = 还没开始（延迟窗口）。取值 [-1, 1] */
  static dotProgress(t: number): number {
    const raw: number = (t * LikeBurst.DURATION_MS - LikeBurst.DOT_START_MS) / LikeBurst.DOT_DURATION_MS;
    return raw < 0 ? -1 : Math.min(1, raw);
  }

  /** 彩点沿辐条的径向距离（设计 px）：12 → 18 + fly×14 + far */
  static dotRadiusPx(p: number, fly: number, farPx: number): number {
    return p < 0 ? 0 : 12 + (6 + fly * 14 + farPx) * p;
  }

  /** 彩点侧向偏移（设计 px）：起点 ±4，终点 ±1.6 倍 */
  static dotSidePx(p: number, sidePx: number): number {
    return p < 0 ? 0 : sidePx * (1 + 0.6 * p);
  }

  /** 彩点缩放：终点缩到 0 */
  static dotScale(p: number): number {
    return p < 0 ? 1 : 1 - p;
  }

  /** 彩点透明度：12% 前淡入，之后恒 1 */
  static dotAlpha(p: number): number {
    if (p < 0) {
      return 0;
    }
    return p < 0.12 ? p / 0.12 : 1;
  }

  /** 第 k 条辐条的角度（度，设计稿 k·360/7 − 90；0° 在 +x、+y 向下） */
  static dotAngleDeg(k: number): number {
    return k * (360.0 / 7.0) - 90;
  }

  /** 第 (k, pair) 颗彩点的取色下标（pair==0 ? k : k+3） */
  static dotColorIndex(k: number, pair: number): number {
    return pair === 0 ? k : k + 3;
  }
}
