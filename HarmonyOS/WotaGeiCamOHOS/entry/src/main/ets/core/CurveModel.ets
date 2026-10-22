/**
 * 直译自上游 core/CurveModel.kt（harmony 分支）。
 * 只做纯函数：控制点 → 256 档查表。GL 侧把表上传成一维纹理后在片元里查，
 * 必须保证「同一输入恒得同一输出」且「表值恒在 0..1」。
 * 通道语义按调色惯例：先过 MASTER（白），再分别过 R/G/B。
 * 平台差异：FloatArray/ByteArray → number[]/Uint8Array；String.format("%.3f") 手写。
 */

/** 一个控制点；x 与 y 都在 [0,1] */
export class CurvePoint {
  x: number;
  y: number;

  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

function clamp01(v: number): number {
  return v < 0 ? 0 : (v > 1 ? 1 : v);
}

/** Locale.US 等价的 "%.3f"（值域 [0,1]，手写保证逐字符一致） */
function fmt3(v: number): string {
  const r: number = Math.round(v * 1000);
  const i: number = Math.floor(r / 1000);
  const f: number = Math.abs(r % 1000);
  return `${i}.${f < 10 ? `00${f}` : f < 100 ? `0${f}` : `${f}`}`;
}

export class ColorCurve {
  points: Array<CurvePoint>;

  /** 查表精度：256 档对应 8bit 输入，肉眼无台阶且纹理一行放得下 */
  static readonly TABLE_SIZE: number = 256;

  static readonly IDENTITY: ColorCurve = new ColorCurve([]);

  /** 需求里「白/红/绿/蓝」四条曲线的槽位 */
  static readonly CHANNEL_MASTER: number = 0;
  static readonly CHANNEL_RED: number = 1;
  static readonly CHANNEL_GREEN: number = 2;
  static readonly CHANNEL_BLUE: number = 3;

  constructor(points: Array<CurvePoint>) {
    this.points = points;
  }

  /**
   * 分段线性插值。控制点按 x 升序；x 相同视为后一个覆盖前一个，避免除零。
   * 首点之前取首点 y、末点之后取末点 y（钳制，不外推，防止曲线甩出合法域）。
   */
  static sample(points: Array<CurvePoint>, x: number): number {
    if (points.length === 0) {
      return clamp01(x);
    }
    if (points.length === 1) {
      return clamp01(points[0].y);
    }
    const v: number = clamp01(x);
    if (v <= points[0].x) {
      return clamp01(points[0].y);
    }
    const last: CurvePoint = points[points.length - 1];
    if (v >= last.x) {
      return clamp01(last.y);
    }
    for (let i = 0; i < points.length - 1; i++) {
      const a: CurvePoint = points[i];
      const b: CurvePoint = points[i + 1];
      if (v >= a.x && v <= b.x) {
        const span: number = b.x - a.x;
        if (span <= 0) {
          return clamp01(b.y);
        }
        return clamp01(a.y + (b.y - a.y) * ((v - a.x) / span));
      }
    }
    return v;
  }

  /** 解析失败一律回落恒等：持久化串是用户数据，坏值不能把 App 崩掉 */
  static decode(raw: string | undefined): ColorCurve {
    if (raw === undefined || raw.length === 0) {
      return ColorCurve.IDENTITY;
    }
    const pts: Array<CurvePoint> = [];
    const segs: Array<string> = raw.split(',');
    for (const seg of segs) {
      const kv: Array<string> = seg.split(':');
      if (kv.length !== 2) {
        continue;
      }
      const x: number = Number(kv[0]);
      const y: number = Number(kv[1]);
      // 防御：外部损坏的持久串可能带 NaN/Infinity 记号，coerceIn 对 NaN 原样放行
      if (Number.isNaN(x) || Number.isNaN(y) || !Number.isFinite(x) || !Number.isFinite(y)) {
        continue;
      }
      pts.push(new CurvePoint(Math.min(1, Math.max(0, x)), Math.min(1, Math.max(0, y))));
    }
    const sorted: Array<CurvePoint> = pts.slice().sort((a: CurvePoint, b: CurvePoint): number => a.x - b.x);
    return new ColorCurve(sorted);
  }

  /** 归一化输入 → 归一化输出；空点集即直通 */
  evaluate(x: number): number {
    return ColorCurve.sample(this.points, x);
  }

  /** 烘焙成 size 查表，索引 i 对应输入 i/(size-1) */
  table(size: number = ColorCurve.TABLE_SIZE): Array<number> {
    const out: Array<number> = new Array<number>(size);
    for (let i = 0; i < size; i++) {
      out[i] = this.evaluate(i / (size - 1));
    }
    return out;
  }

  /** 是否等价于直通：无控制点，或所有控制点都落在 y=x 上 */
  isIdentity(): boolean {
    return this.points.every((p: CurvePoint): boolean => p.x === p.y);
  }

  /** `x:y` 逗号串；空串表示恒等，供设置页持久化 */
  encode(): string {
    return this.points.map((p: CurvePoint): string => `${fmt3(p.x)}:${fmt3(p.y)}`).join(',');
  }
}

/**
 * 四条曲线的组合（白/红/绿/蓝），一次烘焙出 GL 直接可用的 TABLE_SIZE*3 张量。
 * 张量按 GL_RGB 交错布局存放：第 i 个 texel 连续携带 (R[i], G[i], B[i]) 三分量，
 * 一次 glTexImage2D 即可上传成 256×1 的 RGB 纹理。
 * 字节布局与 GL 侧纹理消费语义是同一个契约，两边不许各写一遍——改一处必须同步另一处。
 */
export class CurveStack {
  master: ColorCurve;
  red: ColorCurve;
  green: ColorCurve;
  blue: ColorCurve;

  constructor(master?: ColorCurve, red?: ColorCurve, green?: ColorCurve, blue?: ColorCurve) {
    this.master = master ?? ColorCurve.IDENTITY;
    this.red = red ?? ColorCurve.IDENTITY;
    this.green = green ?? ColorCurve.IDENTITY;
    this.blue = blue ?? ColorCurve.IDENTITY;
  }

  static readonly IDENTITY: CurveStack = new CurveStack();

  get isPassthrough(): boolean {
    return this.master.isIdentity() && this.red.isIdentity() && this.green.isIdentity() && this.blue.isIdentity();
  }

  /** 按通道槽位取对应的曲线 */
  curveOf(channel: number): ColorCurve {
    switch (channel) {
      case ColorCurve.CHANNEL_RED:
        return this.red;
      case ColorCurve.CHANNEL_GREEN:
        return this.green;
      case ColorCurve.CHANNEL_BLUE:
        return this.blue;
      default:
        return this.master;
    }
  }

  /** 替换单条通道，其余通道保持不动 */
  withCurve(channel: number, curve: ColorCurve): CurveStack {
    switch (channel) {
      case ColorCurve.CHANNEL_RED:
        return new CurveStack(this.master, curve, this.green, this.blue);
      case ColorCurve.CHANNEL_GREEN:
        return new CurveStack(this.master, this.red, curve, this.blue);
      case ColorCurve.CHANNEL_BLUE:
        return new CurveStack(this.master, this.red, this.green, curve);
      default:
        return new CurveStack(curve, this.red, this.green, this.blue);
    }
  }

  /** 单通道求值：先白后本色，顺序不能换（白曲线是全局对比度，本色是通道增益） */
  evaluate(channel: number, x: number): number {
    const afterMaster: number = this.master.evaluate(x);
    switch (channel) {
      case ColorCurve.CHANNEL_RED:
        return this.red.evaluate(afterMaster);
      case ColorCurve.CHANNEL_GREEN:
        return this.green.evaluate(afterMaster);
      case ColorCurve.CHANNEL_BLUE:
        return this.blue.evaluate(afterMaster);
      default:
        return afterMaster;
    }
  }

  /**
   * 烘焙成 size*3 的 RGB 张量，供一维纹理上传。布局必须是交错的：
   * out[3i]=R[i]、out[3i+1]=G[i]、out[3i+2]=B[i]。若按平面三段（R‖G‖B）产出，
   * GPU 把乱序字节当交错读，每通道变成三轮 0→255 锯齿 ⇒ 花图。
   */
  bake(size: number = ColorCurve.TABLE_SIZE): Array<number> {
    const out: Array<number> = new Array<number>(size * 3);
    for (let i = 0; i < size; i++) {
      const v: number = i / (size - 1);
      out[3 * i] = this.evaluate(ColorCurve.CHANNEL_RED, v);
      out[3 * i + 1] = this.evaluate(ColorCurve.CHANNEL_GREEN, v);
      out[3 * i + 2] = this.evaluate(ColorCurve.CHANNEL_BLUE, v);
    }
    return out;
  }

  /**
   * 上传给 glTexImage2D 的 256×1 RGB 纹理数据：bake 的交错布局量化到 8bit。
   * 必须走 UNSIGNED_BYTE：GLES 2.0 里 GL_RGB + GL_FLOAT 不是合法组合格式。
   * （上游 ByteArray 有符号，这里用 Uint8Array 0..255，GL 侧语义相同。）
   */
  bakeBytes(size: number = ColorCurve.TABLE_SIZE): Uint8Array {
    const f: Array<number> = this.bake(size);
    const out: Uint8Array = new Uint8Array(f.length);
    for (let i = 0; i < f.length; i++) {
      // Kotlin: (x*255+0.5).toInt() 即四舍五入
      const q: number = Math.round(f[i] * 255);
      out[i] = Math.min(255, Math.max(0, q));
    }
    return out;
  }

  /** 四段 `x:y` 串按 `;` 分隔（段序与 CHANNEL_* 一致） */
  encode(): string {
    return [this.master, this.red, this.green, this.blue]
      .map((c: ColorCurve): string => c.encode())
      .join(';');
  }

  /** 段数不对或含坏值一律回落恒等 */
  static decode(raw: string | undefined): CurveStack {
    if (raw === undefined || raw.length === 0) {
      return CurveStack.IDENTITY;
    }
    const seg: Array<string> = raw.split(';');
    if (seg.length !== 4) {
      return CurveStack.IDENTITY;
    }
    return new CurveStack(
      ColorCurve.decode(seg[0]), ColorCurve.decode(seg[1]), ColorCurve.decode(seg[2]), ColorCurve.decode(seg[3]));
  }
}

/** 曲线编辑器的增删改结果：新点集与选中下标 */
export class CurveInsertResult {
  points: Array<CurvePoint>;
  selectedIndex: number;

  constructor(points: Array<CurvePoint>, selectedIndex: number) {
    this.points = points;
    this.selectedIndex = selectedIndex;
  }
}

/**
 * 曲线编辑器的纯逻辑：命中测试、增删改控制点。
 * 端点（x=0 与 x=1）恒在且横向锁定，中间点只能插在两个邻居之间，
 * 所以点集永远严格升序——sample 的除零保护因此不会被 UI 触到。
 */
export class CurveEdit {
  /** 单条通道最多几个控制点：再多折线就成台阶了 */
  static readonly MAX_POINTS: number = 8;

  /** 相邻控制点的最小横向间距，避免拖出零宽线段 */
  static readonly MIN_DX: number = 0.04;

  /** 触点判定半径（归一化到画布宽高的比例，与像素无关，便于单测） */
  static readonly HIT_RADIUS: number = 0.07;

  /** 编辑器看到的点集：补齐缺失的端点，恒等曲线即 (0,0)-(1,1) 两点 */
  static pointsOf(curve: ColorCurve): Array<CurvePoint> {
    const src: Array<CurvePoint> = curve.points;
    if (src.length === 0) {
      return [new CurvePoint(0, 0), new CurvePoint(1, 1)];
    }
    let head: Array<CurvePoint> = src[0].x === 0 ? src : [new CurvePoint(0, src[0].y), ...src];
    if (head[head.length - 1].x !== 1) {
      head = [...head, new CurvePoint(1, head[head.length - 1].y)];
    }
    return head.slice().sort((a: CurvePoint, b: CurvePoint): number => a.x - b.x);
  }

  /** 最近的点；超出 HIT_RADIUS 返回 -1（表示点的是空白，应新增控制点） */
  static hitTest(points: Array<CurvePoint>, x: number, y: number): number {
    let best: number = -1;
    let bestDist: number = Number.MAX_VALUE;
    for (let i = 0; i < points.length; i++) {
      const p: CurvePoint = points[i];
      const dx: number = p.x - x;
      const dy: number = p.y - y;
      const d: number = Math.sqrt(dx * dx + dy * dy);
      if (d <= CurveEdit.HIT_RADIUS && d < bestDist) {
        bestDist = d;
        best = i;
      }
    }
    return best;
  }

  /** 拖动第 index 个点：端点只跟 Y，中间点 X 被夹在两个邻居之间 */
  static moved(points: Array<CurvePoint>, index: number, x: number, y: number): Array<CurvePoint> {
    if (index < 0 || index >= points.length) {
      return points;
    }
    const last: number = points.length - 1;
    const fixed: boolean = index === 0 || index === last;
    const lo: number = index === 0 ? 0 : points[index - 1].x + CurveEdit.MIN_DX;
    const hi: number = index === last ? 1 : points[index + 1].x - CurveEdit.MIN_DX;
    const cur: CurvePoint = points[index];
    let nx: number;
    if (fixed) {
      nx = cur.x;
    } else if (lo <= hi) {
      nx = Math.min(hi, Math.max(lo, x));
    } else {
      nx = cur.x; // 邻居挤到没有可放空间：保持原位
    }
    const ny: number = Math.min(1, Math.max(0, y));
    const next: Array<CurvePoint> = points.slice();
    next[index] = new CurvePoint(nx, ny);
    return next;
  }

  /**
   * 在空白处新增控制点，返回新点集与选中下标；已到上限或两邻居间没有合法插缝返回 undefined。
   * 插入 x 除贴边钳制外还要避让邻居（≥MIN_DX），否则会插出 x 重复的点——
   * 零宽线段在采样里造成跳变，且重复点在 moved 里 lo>hi 永远保持原位、几乎无法选中。
   */
  static inserted(points: Array<CurvePoint>, x: number, y: number): CurveInsertResult | undefined {
    if (points.length >= CurveEdit.MAX_POINTS) {
      return undefined;
    }
    let at: number = points.findIndex((p: CurvePoint): boolean => p.x > x);
    if (at < 0) {
      at = points.length;
    }
    const lo: number = Math.max(CurveEdit.MIN_DX, at > 0 ? points[at - 1].x + CurveEdit.MIN_DX : CurveEdit.MIN_DX);
    const hi: number = Math.min(1 - CurveEdit.MIN_DX, at < points.length ? points[at].x - CurveEdit.MIN_DX : 1 - CurveEdit.MIN_DX);
    if (lo > hi) {
      return undefined;
    }
    const v: number = Math.min(hi, Math.max(lo, x));
    const next: Array<CurvePoint> = points.slice();
    next.splice(at, 0, new CurvePoint(v, Math.min(1, Math.max(0, y))));
    return new CurveInsertResult(next, at);
  }

  /** 删除控制点；端点不可删 */
  static removed(points: Array<CurvePoint>, index: number): Array<CurvePoint> {
    if (index <= 0 || index >= points.length - 1) {
      return points;
    }
    const next: Array<CurvePoint> = points.slice();
    next.splice(index, 1);
    return next;
  }
}
