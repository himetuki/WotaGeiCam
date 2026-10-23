/**
 * 取景参考线（上游 core/Constants.kt 的 RefLineType 11 档 + ui/widget/RefLineOverlay.kt 的两条纯函数）。
 *
 * 位掩码可组合：持久化到 prefs `default_ref_lines`（键名与上游逐字一致），
 * 多选胶囊的「再点一次要能关回去、且不动别的线」由上层的 isOn/toggle 保证。
 *
 * 零平台 import：掩码与水平线端点都可在 test/pure 里直接钉住（上游 RefLineMaskTest /
 * RefLineHorizonTest 的同一批断言）。
 */

/** 参考线 11 档（bit 与上游逐值一致，只许追加不许改名——它是持久化契约） */
export enum RefLineType {
  GRID_3X3 = 'grid_3x3',
  DIAGONAL = 'diagonal',
  CROSSHAIR = 'crosshair',
  BROADCAST_SAFE = 'broadcast_safe',
  SAFE_AREA = 'safe_area',
  GRID = 'grid',
  CENTER_MARK = 'center_mark',
  HORIZON_LINE = 'horizon_line',
  RED_TOP = 'red_top',
  RED_MID = 'red_mid',
  RED_BOTTOM = 'red_bottom'
}

/** 一档参考线的位值 + 中文名（按声明顺序手写钉死，等价 Kotlin enum 的构造参数） */
export class RefLineEntry {
  type: RefLineType;
  bit: number;
  label: string;

  constructor(type: RefLineType, bit: number, label: string) {
    this.type = type;
    this.bit = bit;
    this.label = label;
  }
}

/** 全表（顺序 = 声明顺序 = 设置页 chip 顺序） */
export const REF_LINE_ENTRIES: Array<RefLineEntry> = [
  new RefLineEntry(RefLineType.GRID_3X3, 1, '三分法网格'),
  new RefLineEntry(RefLineType.DIAGONAL, 2, '对角线'),
  new RefLineEntry(RefLineType.CROSSHAIR, 4, '十字准线'),
  new RefLineEntry(RefLineType.BROADCAST_SAFE, 8, '广播安全框'),
  new RefLineEntry(RefLineType.SAFE_AREA, 16, '安全区域'),
  new RefLineEntry(RefLineType.GRID, 32, '网格'),
  new RefLineEntry(RefLineType.CENTER_MARK, 64, '中心标记'),
  new RefLineEntry(RefLineType.HORIZON_LINE, 128, '水平线'),
  new RefLineEntry(RefLineType.RED_TOP, 256, '上红线'),
  new RefLineEntry(RefLineType.RED_MID, 512, '中红线'),
  new RefLineEntry(RefLineType.RED_BOTTOM, 1024, '下红线')
];

export class RefLine {
  /** 全表（与 REF_LINE_ENTRIES 同源） */
  static all(): Array<RefLineEntry> {
    return REF_LINE_ENTRIES;
  }

  /** 全开掩码（11 档 = 2047） */
  static fullMask(): number {
    let mask: number = 0;
    for (const e of REF_LINE_ENTRIES) {
      mask = mask | e.bit;
    }
    return mask;
  }

  static bitOf(type: RefLineType): number {
    for (const e of REF_LINE_ENTRIES) {
      if (e.type === type) {
        return e.bit;
      }
    }
    return 0;
  }

  static labelOf(type: RefLineType): string {
    for (const e of REF_LINE_ENTRIES) {
      if (e.type === type) {
        return e.label;
      }
    }
    return '';
  }

  static typesOf(mask: number): Array<RefLineType> {
    const out: Array<RefLineType> = [];
    for (const e of REF_LINE_ENTRIES) {
      if ((mask & e.bit) !== 0) {
        out.push(e.type);
      }
    }
    return out;
  }

  static maskOf(types: Array<RefLineType>): number {
    let mask: number = 0;
    for (const t of types) {
      mask = mask | RefLine.bitOf(t);
    }
    return mask;
  }

  /** 该档是否开着 */
  static isOn(mask: number, type: RefLineType): boolean {
    return (mask & RefLine.bitOf(type)) !== 0;
  }

  /** 多选胶囊的点按语义：开着 → 关，关着 → 开；绝不串改别的位 */
  static toggle(mask: number, type: RefLineType): number {
    const bit: number = RefLine.bitOf(type);
    return RefLine.isOn(mask, type) ? (mask & ~bit) >>> 0 : (mask | bit) >>> 0;
  }
}

/** 画布上的一个点（纯值对象，避免把 Canvas 类型拖进纯逻辑） */
export class CanvasPoint {
  x: number;
  y: number;

  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

/** 画面内容的内接矩形（vp/px 同式；aspect<=0 或非有限 = 铺满整个画布） */
export class CanvasBox {
  left: number;
  top: number;
  width: number;
  height: number;

  constructor(left: number, top: number, width: number, height: number) {
    this.left = left;
    this.top = top;
    this.width = width;
    this.height = height;
  }
}

/**
 * 把参考线约束在画面内容的内接矩形内，避免黑边区域也画线。
 * aspect = 画面宽/高；画布比画面更「宽」时按高度定框，反之按宽度定框。
 */
export function inscribedBox(canvasW: number, canvasH: number, aspect: number): CanvasBox {
  if (!(canvasW > 0) || !(canvasH > 0)) {
    return new CanvasBox(0, 0, 0, 0);
  }
  if (!Number.isFinite(aspect) || aspect <= 0) {
    return new CanvasBox(0, 0, canvasW, canvasH);
  }
  const ratio: number = canvasW / canvasH;
  let boxW: number;
  let boxH: number;
  if (ratio > aspect) {
    boxH = canvasH;
    boxW = boxH * aspect;
  } else {
    boxW = canvasW;
    boxH = boxW / aspect;
  }
  return new CanvasBox((canvasW - boxW) / 2, (canvasH - boxH) / 2, boxW, boxH);
}

/**
 * 水平线两端点（过心线，画布 Y 轴向下）。
 * `LevelMath.roll` 规定「正 = 屏幕右侧偏低」，右侧下沉时真实地平在画面里向右上方抬起；
 * 故右端取 `cy - dy`。线长取对角线长度，保证任意角度都横跨画面。
 * 抽成纯函数是为了让**符号约定**（roll 正负 ↔ 线的抬向）可被单测钉住——
 * 画反了地平线，取景的人看到的引导与仪表读数正好相反。
 */
export function horizonEndpoints(cx: number, cy: number, half: number, rollDegrees: number):
  Array<CanvasPoint> {
  const rad: number = (Number.isFinite(rollDegrees) ? rollDegrees : 0) * Math.PI / 180;
  const dx: number = Math.cos(rad) * half;
  const dy: number = Math.sin(rad) * half;
  return [new CanvasPoint(cx - dx, cy + dy), new CanvasPoint(cx + dx, cy - dy)];
}
