/**
 * 离线光弧修复的抽帧计划（直译上游 record/ArcRepairPlan.kt 的 ArcRepairPlan，对齐上游 38d712a）。
 *
 * 把实测 srcNum/srcDen（有理数源帧率，见 record/ArcKeepRule.ets 的 ArcRateProbe）的素材抽成
 * dstFps。v2 口径（用户 2026-10-03 定版，本文件是它的唯一真源；v1 的"插帧"被判定为方向性
 * 错误废弃）：
 * - 强制 24/25fps 在无固定档设备上传感器实际跑更高帧率（如 30），素材里**多出**的帧要**抽掉**，
 *   输出只含保留帧——**绝不插帧**（抽帧再插帧帧数绕回原点，且混合帧会把运动中的光弧拆成两道
 *   半亮重影）；
 * - 保留集 = { ⌊j·srcFps/dstFps⌋ : j }（对 30→24 即每 5 帧抽 1，分布均匀不扎堆）；
 * - 被抽帧的像素由 ArcRepairMerge 整像素并入前后保留帧；
 * - 输出 PTS 取均匀目标帧率步长（ArcRepairFlow），容器是真 24/25fps。
 *
 * 有理数真值（M6-01）：整数口径把 29.97 整成 30 会让保留位次漂一位（抽帧点 {4,9,…}→{5,10,…}），
 * 把 25.4 整成 25 会误判"无需抽帧"。本侧**不移植旧 Int 兼容外壳**（无历史调用点），统一有理数
 * 签名；整数源用 num/1 传入，行为与旧口径逐点一致（有独立 oracle 扫描用例钉住）。
 *
 * 精度登记：全程 number 整数域，只做 floor 与整除；`s·D·den ≤ 2e6×25×1e6 = 5e13`，
 * `j·num` 同量级，远小于 2^53，整数精确。
 *
 * 纯逻辑（不 import 平台 Kit）；执行器（解码/编码/EGL/Player 面板）留 T29。
 */

/** 抽帧计划快照（srcNum/srcDen 为有理数源帧率，dstFps 目标帧率） */
export class ArcRepairPlan {
  readonly srcNum: number;
  readonly srcDen: number;
  readonly dstFps: number;
  readonly srcFrames: number;
  /** 输出（保留）帧数，进度换算与引擎循环都用它 */
  readonly dstFrames: number;

  private constructor(srcNum: number, srcDen: number, dstFps: number, srcFrames: number) {
    this.srcNum = srcNum;
    this.srcDen = srcDen;
    this.dstFps = dstFps;
    this.srcFrames = srcFrames;
    this.dstFrames = ArcRepairPlan.dstFrameCount(srcFrames, srcNum, srcDen, dstFps);
  }

  /** 总输出帧数（= dstFrames） */
  totalFrames(): number {
    return this.dstFrames;
  }

  /**
   * 输出帧数 = 保留帧数 = |{ j : ⌊j·S/D⌋ < N }| = ⌈N·D/S⌉（有理数版：S = srcNum/srcDen）。
   * - N ≤ 0 → 0；S ≤ D（源不比目标快，没有帧可抽）→ N（恒等直通，调用方应提前拦），
   *   判据 `num ≤ D·den` 整数域无浮点
   * - 45@30→24 = 36（每 5 抽 1，抽 9 留 36）；时长账 36/24 = 45/30 严格恒等
   */
  static dstFrameCount(srcFrames: number, srcNum: number, srcDen: number, dstFps: number): number {
    if (srcFrames <= 0) {
      return 0;
    }
    if (srcNum <= 0 || srcDen <= 0 || dstFps <= 0 || srcNum <= dstFps * srcDen) {
      return srcFrames;
    }
    return Math.floor((srcFrames * dstFps * srcDen + srcNum - 1) / srcNum);
  }

  /** 由源帧数与两档帧率（有理数源帧率）造计划；负值帧数按 0 处理（后台线程不许因越界输入崩） */
  static of(srcFrames: number, srcNum: number, srcDen: number, dstFps: number): ArcRepairPlan {
    return new ArcRepairPlan(srcNum, srcDen, dstFps, srcFrames < 0 ? 0 : srcFrames);
  }

  /**
   * 源第 srcIndex 帧是否保留：s 保留 ⇔ ⌊⌈s·D/S⌉·S/D⌋ == s（存在输出下标 j 映到它）；
   * S = srcNum/srcDen 有理数。全程整数域，无浮点；负下标按不保留处理（防御）。
   */
  static isKept(srcIndex: number, srcNum: number, srcDen: number, dstFps: number): boolean {
    if (srcIndex < 0 || !Number.isFinite(srcIndex)) {
      return false;
    }
    if (srcNum <= 0 || srcDen <= 0 || dstFps <= 0 || srcNum <= dstFps * srcDen) {
      return true;
    }
    const s: number = srcIndex;
    const j: number = Math.floor((s * dstFps * srcDen + srcNum - 1) / srcNum); // ⌈s·D/S⌉
    return Math.floor((j * srcNum) / (dstFps * srcDen)) === s;
  }

  /**
   * 总进度 0..1：已处理帧 / 总输出帧。总帧数 ≤ 0 时返回 0；结果一律钳到 0..1
   * （引擎在解出真实帧数前会用估算值，可能超界）。
   */
  static progressOf(doneFrames: number, totalFrames: number): number {
    if (totalFrames <= 0) {
      return 0;
    }
    const ratio: number = doneFrames / totalFrames;
    if (!Number.isFinite(ratio)) {
      return 0;
    }
    return Math.min(1, Math.max(0, ratio));
  }
}
