/**
 * 站位指示线（2026-10-27 用户需求）——上游 core/MarkerLines.kt 直译：
 * WOTA 艺多人排练时标出各站位用的等距竖线。
 *
 * # 几何口径
 * 线在预览**宽度比例**空间里居中对称分布：lineXsFraction(count, spread) 返回 n 个 0..1 的
 * x 比例——spread 是"整组线占预览宽的比例"，间距 = spread/(n-1) 随之等距。滑杆调 spread
 * （用户语义"调间距"：拉宽组 = 拉大间距，方向一致），count 调线数；两参任意组合都保证
 * 所有线落在 0..1 之内（不裁剪、不出画）——这是不用"裸间距"参数的原因：
 * 裸间距 × 8 条线会超出画幅（0.4 × 7 = 2.8 倍宽）。
 *
 * # 颜色
 * MarkerLineColor 六色固定色板（预览叠加需要的是"看得清的对比色"；自选色未要求）。
 *
 * # 不入成片
 * 渲染走 UI 层 Canvas（ui/MarkerLineOverlay），与取景参考线同一通道——录制采集的是相机/GL 面，
 * UI 叠加**结构上**进不了编码器，无需任何开关防呆。
 */
export class MarkerLines {
  static readonly MIN_COUNT: number = 0;
  static readonly MAX_COUNT: number = 8;
  static readonly MIN_SPREAD: number = 0.15;
  static readonly MAX_SPREAD: number = 1.00;

  /**
   * 出厂默认 = **0 条 = 关闭**（2026-10-27 二轮定版）：线的开/关只由线数驱动，没有独立开关，
   * 所以"默认关"只能靠默认线数为 0 表达；用户点浮层里的 + 从 1 条开始加。
   */
  static readonly DEFAULT_COUNT: number = 0;
  static readonly DEFAULT_SPREAD: number = 0.60;

  static clampCount(v: number): number {
    const n: number = Math.trunc(v);
    return Math.min(MarkerLines.MAX_COUNT, Math.max(MarkerLines.MIN_COUNT, n));
  }

  static clampSpread(v: number): number {
    if (!Number.isFinite(v)) {
      return MarkerLines.DEFAULT_SPREAD;
    }
    return Math.min(MarkerLines.MAX_SPREAD, Math.max(MarkerLines.MIN_SPREAD, v));
  }

  /**
   * 等距竖线的 x 比例（升序，全部落在 0..1）：居中对称，整组占 spread 比例的宽度。
   * count=1 恒居中（0.5）；count/spread 越界一律钳回合法域（滑杆外的脏值不炸渲染）。
   * **count = 0 返回空表 = 站位线关闭**（线的开/关只由线数驱动，不设独立开关）。
   */
  static lineXsFraction(count: number, spread: number): Array<number> {
    const n: number = MarkerLines.clampCount(count);
    const w: number = MarkerLines.clampSpread(spread);
    if (n <= 1) {
      return n === 1 ? [0.5] : [];
    }
    const out: Array<number> = [];
    for (let i: number = 0; i < n; i++) {
      out.push(0.5 - w / 2 + w * i / (n - 1));
    }
    return out;
  }
}

/**
 * 站位线色板：argb 供 Canvas 直接着色；label 供选色浮层的无障碍文案。
 * 透明度由渲染层统一施加（预览叠加 0.55），色板只定义色相。
 */
export class MarkerLineColor {
  readonly label: string;
  readonly argb: number;

  constructor(label: string, argb: number) {
    this.label = label;
    this.argb = argb;
  }

  static readonly YELLOW: MarkerLineColor = new MarkerLineColor('黄', 0xFFE6C700);
  static readonly CYAN: MarkerLineColor = new MarkerLineColor('青', 0xFF35D6E0);
  static readonly MAGENTA: MarkerLineColor = new MarkerLineColor('品红', 0xFFE64AC8);
  static readonly GREEN: MarkerLineColor = new MarkerLineColor('绿', 0xFF4CE05A);
  static readonly WHITE: MarkerLineColor = new MarkerLineColor('白', 0xFFF2F2F2);
  static readonly ORANGE: MarkerLineColor = new MarkerLineColor('橙', 0xFFFF8A1E);

  static readonly ALL: Array<MarkerLineColor> = [
    MarkerLineColor.YELLOW, MarkerLineColor.CYAN, MarkerLineColor.MAGENTA,
    MarkerLineColor.GREEN, MarkerLineColor.WHITE, MarkerLineColor.ORANGE
  ];

  /** 序号 → 颜色（越界回黄——坏值不炸渲染；持久化按**名**存储，此函数供旧序兼容） */
  static fromOrdinal(v: number): MarkerLineColor {
    return v >= 0 && v < MarkerLineColor.ALL.length ? MarkerLineColor.ALL[v] : MarkerLineColor.YELLOW;
  }

  /** 名字 → 颜色（持久化键 default_marker_color 的值域：名字逐字；坏名回黄） */
  static fromName(name: string): MarkerLineColor {
    for (const c of MarkerLineColor.ALL) {
      if (c.label === name || c.enumName() === name) {
        return c;
      }
    }
    return MarkerLineColor.YELLOW;
  }

  /** 持久化名（与上游 enum.name 逐字一致：YELLOW/CYAN/MAGENTA/GREEN/WHITE/ORANGE） */
  enumName(): string {
    switch (this.argb) {
      case 0xFFE6C700: return 'YELLOW';
      case 0xFF35D6E0: return 'CYAN';
      case 0xFFE64AC8: return 'MAGENTA';
      case 0xFF4CE05A: return 'GREEN';
      case 0xFFF2F2F2: return 'WHITE';
      case 0xFFFF8A1E: return 'ORANGE';
      default: return 'YELLOW';
    }
  }

  sameAs(other: MarkerLineColor): boolean {
    return other.argb === this.argb;
  }
}
