/**
 * 直译自上游 record/ArcKeepRule.kt（harmony 分支）。
 * 平台差异：
 * - Long → number：所有运算值域 ≤ 2.4e11，远小于 2^53，整数精确性不受影响；
 * - Kotlin List<Int>/List<Long> 两个重载在 ArkTS 里合成一个 number 版（类型本就同一）；
 * - NOT_STARTED 哨兵 Long.MIN_VALUE → Number.MIN_SAFE_INTEGER（真实时间戳不可能等于它）。
 */

/**
 * 实测源帧率的有理数视图（num/den，已约分）：整数 fps 会把 30.0 与 29.97（30000/1001）
 * 压成同一个"30"，但抽帧计划的保留位次与"源不快于目标"的门槛判据都会因这 0.03fps 漂移
 * 出错（M6-01：离线修复链非整数源分叉的根因）。整数视图（roundedInt）只配给日志/前缀
 * 这类只看量级的消费点。
 *
 * ArkTS 差异：上游是 ArcRateProbe 的嵌套 data class，ArkTS 禁止类表达式/嵌套静态类声明，
 * 故提升为顶层导出类并置于 ArcRateProbe 之前（KNOWN_RATES 静态初始化即 new 本类，
 * 类声明必须先于使用求值）；约分构造 of 的等价物保留在 ArcRateProbe.measuredOf。
 */
export class MeasuredSrcFps {
  num: number;
  den: number;

  constructor(num: number, den: number) {
    this.num = num;
    this.den = den;
  }

  /** 四舍五入取整并钳到 1..MAX_MEASURED_FPS（半上：⌊(2num+den)/(2den)⌋，无浮点） */
  roundedInt(): number {
    const r: number = Math.floor((this.num * 2 + this.den) / (this.den * 2));
    return Math.min(ArcRateProbe.MAX_MEASURED_FPS, Math.max(1, r));
  }

  /** 有理数版门槛判：源帧率 ≤ 整数 n ⇔ num ≤ n·den（整数取整口径会把 25.4 打成 25 误判） */
  atMostInt(n: number): boolean {
    return this.num <= n * this.den;
  }
}

/**
 * 源帧率实测（帧距中位数，纯函数可测）：强制 24/25fps 档的容器帧率会标成目标值
 * 而传感器实际跑更高帧率，按容器值造抽帧计划会得出"无需抽帧"的假结论。
 * 中位数对重复/空样本免疫（帧距≈0 被过滤）：不数个数，量间隔。
 */
export class ArcRateProbe {
  /**
   * 抽帧处理成片的名称前缀（"XXftoXXf"，源在前目标在后），如 30fps 实测转 24fps → `30fto24f_`。
   * 光弧修复的成片与录制期转换的成片共用这一个构造。
   */
  static convertNamePrefix(srcFps: number, dstFps: number): string {
    return `${srcFps}fto${dstFps}f_`;
  }

  /** 有效帧距样本少于这个数（极短视频）时中位数不可信，退回容器帧率 */
  static readonly MIN_DELTAS: number = 8;

  /** 实测帧率上限（防畸变帧距把值算飞；产品最高档 240，留一倍余量） */
  static readonly MAX_MEASURED_FPS: number = 480;

  /** 滤噪带唯一字面量：帧距 <1ms 是重复样本、>1s 是轨道断层 */
  static readonly CLEAN_MIN_US: number = 1001;
  static readonly CLEAN_MAX_US: number = 1_000_000;

  /** 约分构造。非正数输入按 (0,1) 哨兵返回（防御，正常链路走不到） */
  static measuredOf(num: number, den: number): MeasuredSrcFps {
    if (num <= 0 || den <= 0) {
      return new MeasuredSrcFps(0, 1);
    }
    const g: number = ArcRateProbe.gcd(num, den);
    return g === 1 ? new MeasuredSrcFps(num, den) : new MeasuredSrcFps(num / g, den / g);
  }

  /** 欧几里得最大公约数（两参恒正） */
  private static gcd(a: number, b: number): number {
    let x: number = a;
    let y: number = b;
    while (y !== 0) {
      const t: number = x % y;
      x = y;
      y = t;
    }
    return x;
  }

  /** 已知档位快照表：exact 整数档在前、NTSC（÷1001）在后；各档 ±2µs 窗口互不重叠 */
  private static readonly KNOWN_RATES: Array<MeasuredSrcFps> = [
    ArcRateProbe.measuredOf(24, 1), ArcRateProbe.measuredOf(25, 1), ArcRateProbe.measuredOf(30, 1),
    ArcRateProbe.measuredOf(50, 1), ArcRateProbe.measuredOf(60, 1), ArcRateProbe.measuredOf(120, 1),
    ArcRateProbe.measuredOf(240, 1),
    ArcRateProbe.measuredOf(24_000, 1_001), ArcRateProbe.measuredOf(30_000, 1_001),
    ArcRateProbe.measuredOf(60_000, 1_001)
  ];

  /**
   * 中位帧距 dUs 吸附到已知档位：判据纯整数交叉相乘 `|d·num − den·10⁶| ≤ 2·num`
   * （⇔ |d − 该档周期| ≤ 2µs，容下解码 PTS 的量化抖动）；未命中退原始有理数 10⁶/d 约分
   * ——没有"整成整数 fps"这步损耗，29.97 就是 30000/1001。
   */
  static snapToKnownRate(dUs: number): MeasuredSrcFps {
    for (const r of ArcRateProbe.KNOWN_RATES) {
      const diff: number = dUs * r.num - 1_000_000 * r.den;
      if (diff <= 2 * r.num && diff >= -2 * r.num) {
        return r;
      }
    }
    return ArcRateProbe.measuredOf(1_000_000, dUs);
  }

  /**
   * 由原始相邻样本帧距（微秒）测源帧率（有理数版）：先滤噪（<1ms 或 >1s 的样本丢弃），
   * 再取中位数吸附已知档位；有效样本不足退回 fallback。帧距在滤噪之前不得降位/取整。
   */
  static measuredSrcFps(
    rawDeltasUs: Array<number>,
    fallbackNum: number,
    fallbackDen: number = 1
  ): MeasuredSrcFps {
    const clean: Array<number> = rawDeltasUs.filter(
      (d: number): boolean => d >= ArcRateProbe.CLEAN_MIN_US && d <= ArcRateProbe.CLEAN_MAX_US);
    if (clean.length < ArcRateProbe.MIN_DELTAS) {
      return ArcRateProbe.measuredOf(fallbackNum, fallbackDen);
    }
    const sorted: Array<number> = clean.slice().sort((a: number, b: number): number => a - b);
    return ArcRateProbe.snapToKnownRate(sorted[Math.floor(clean.length / 2)]);
  }

  /** 旧整数签名口径（兼容外壳）：样本不足时 fallbackFps 原样返回（不钳制） */
  static measuredFps(rawDeltasUs: Array<number>, fallbackFps: number): number {
    const clean: Array<number> = rawDeltasUs.filter(
      (d: number): boolean => d >= ArcRateProbe.CLEAN_MIN_US && d <= ArcRateProbe.CLEAN_MAX_US);
    if (clean.length < ArcRateProbe.MIN_DELTAS) {
      return fallbackFps;
    }
    return ArcRateProbe.measuredSrcFps(rawDeltasUs, fallbackFps).roundedInt();
  }
}

/** 相位容差占槽宽的比例（%）：运行时参数，不在判定里写毫秒常量（10% @24fps ≈ 4.17ms） */
export const ARC_KEEP_TOLERANCE_PERCENT: number = 10;

/**
 * 槽宽 → 相位容差（纯函数；slotNs ≤ 0 或比例为 0 ⇒ 0，保持"关闭转换"路径零等待）。
 * 比例钳在 0..100：超过一整个槽的"容差"等于把上一条槽也放进来，语义已不是相位容差。
 */
export function arcKeepToleranceNs(slotNs: number, percent: number = ARC_KEEP_TOLERANCE_PERCENT): number {
  if (slotNs <= 0) {
    return 0;
  }
  const p: number = Math.min(100, Math.max(0, percent));
  return Math.floor(slotNs * p / 100);
}

/**
 * 录制在线路的抽帧保留规则。
 *
 * 录制侧没有"先知帧率"：传感器在无精确档范围里实际跑多快由 HAL 决定，所以不用离线计划那套
 * 整除取位，改用应到时刻判定——输出第 k 帧的应到时刻 = 锚点 + k·帧间隔，相机帧到达时刻
 * 不早于它才保留为第 k 帧，否则丢弃。规则自追平（停滞后来帧不从落后账里连发）、自纠偏
 * （传感器快慢漂移直接反映到丢弃分布），长期输出节奏收敛到恰为 D fps。
 *
 * 相位容差：时间戳带调度抖动，零容差会把"刚好早到 1ms"的正常帧判成抽帧；更硬的一条是
 * 25fps 源转 24fps——源帧距 40ms < 槽宽 41.667ms，零容差每 25 帧会多抽一两帧。所以保留
 * 判据为 `ts >= nextDue - toleranceNs`：容差只吸收"早到不超过槽宽 10%"的相位抖动，
 * nextDue 仍按 `+= slotNs` 推进（不是从 ts 重算），停滞追平分支原样保留。
 */
export class ArcKeepRule {
  private readonly slotNs: number;
  private readonly toleranceNs: number;

  /** 下一输出帧的应到时刻；NOT_STARTED = 还没见到首帧 */
  private static readonly NOT_STARTED: number = Number.MIN_SAFE_INTEGER;

  private nextDueNs: number = ArcKeepRule.NOT_STARTED;

  /** 自上一保留帧以来累计丢弃数（供被抽帧位次 sidecar 记账） */
  private pendingDrops: number = 0;

  constructor(slotNs: number, toleranceNs: number = arcKeepToleranceNs(slotNs)) {
    this.slotNs = slotNs;
    this.toleranceNs = toleranceNs;
  }

  /**
   * 相机帧到达（tsNs = SurfaceTexture 时间戳）。
   * @return true = 保留为本输出帧（调用方推进输出帧序号）
   */
  onFrame(tsNs: number): boolean {
    if (this.slotNs <= 0) {
      return true;
    }
    if (this.nextDueNs === ArcKeepRule.NOT_STARTED) {
      // 首帧保留并锚定节奏：下一帧的应到时刻 = 首帧时刻 + 帧间隔
      this.nextDueNs = tsNs + this.slotNs;
      return true;
    }
    if (tsNs >= this.nextDueNs - this.toleranceNs) {
      // 保留帧不清 pendingDrops——"自上一保留帧以来丢了几个"要在发出那一帧时
      // 才被记账方 takeDrops() 取走，这里清了会把区间账抹成 0
      this.nextDueNs += this.slotNs;
      if (this.nextDueNs <= tsNs) {
        this.nextDueNs = tsNs + this.slotNs; // 停滞追平，不连发
      }
      return true;
    }
    this.pendingDrops++;
    return false;
  }

  /** 取走"最近一次保留之前丢弃了几帧"（Emit 记账用；取走即清零） */
  takeDrops(): number {
    const n: number = this.pendingDrops;
    this.pendingDrops = 0;
    return n;
  }

  /** 重置（每次录制开始/编码面重挂时调用） */
  reset(): void {
    this.nextDueNs = ArcKeepRule.NOT_STARTED;
    this.pendingDrops = 0;
  }
}
