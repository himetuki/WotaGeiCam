/**
 * 等号档曝光纠偏（2026-11-01 用户定版；上游 docs/plan/31 三稿）——上游 core/ExposureCorrection.kt
 * 的鸿蒙直译（r42，8c30210+b2e5a8d）。
 *
 * 地基事实：相机合同只保证 帧周期 ≥ 曝光时长，「曝光 == 帧周期」（1/24@24、1/25@25 这两对
 * 满窗档）落在合同边界域——曝光与读出重叠时 HAL 拉不拉长帧周期是自由裁量，拉了帧率就掉档。
 * 传感器的读出（"搬运"）时间不在任何公开接口里，跨品牌读取接口建表违反「能力协商而非能力
 * 假设」⇒ **不问相机要参数，测相机对请求的反应**。
 *
 * 三条铁律（谁改这块谁先读）：
 * 1. **发送侧永不发等号值**：纠偏只动曝光时长这一个键——AE 状态、帧周期（节奏真值）、
 *    总线档位全部不动；
 * 2. **总线保真**：纠偏是发送侧行为，UI 档位名、滑杆、读数不动（`clampShutterNs` 本体不改，
 *    依赖精确档 ns 的判据与总线回写不变）；
 * 3. **探测只在预览态**：探测发等号请求，录制目标在场必须立刻退出探测态（等号不进录制）。
 *
 * 平台适配登记（对上游的差异）：
 * - 探测的帧到达间隔：上游吃 TotalCaptureResult 的 SENSOR_TIMESTAMP（ns 真值）；OHOS
 *   PreviewOutput 'frameStart' 回调无时间戳载荷 ⇒ 墙钟 ms × 1e6 折算（1ms 粒度，
 *   对 Δ=1~8ms 量级的折算足够），登记；
 * - 数值域：上游 Long，本侧 number（双精度）——纠偏域内（0..8ms、帧周期 ≤ 41.7ms、
 *   Δ ≤ 20% 帧周期）全部远小于 2^53，逐值精确；偶数中位数的整除用 Math.floor 对齐。
 */
import { WotaTiers } from './Constants';

export class ExposureCorrection {
  /** 手动微调滑杆域：0 ~ +8ms，0.5ms 步进（2026-11-01 用户裁决：负值区去掉——
   *  负值在等号档只会被帧周期上限饱和成满窗直发，是查不到实效的假域） */
  static readonly MANUAL_MIN_NS: number = 0;
  static readonly MANUAL_MAX_NS: number = 8_000_000;
  static readonly MANUAL_STEP_NS: number = 500_000;

  /** 自动余量下限 = 帧周期 × 5%（守约机的保守余量；Δ 缺失/为 0 时的取值） */
  static readonly AUTO_FLOOR_PERCENT: number = 5;

  /** 自动余量上限 = 帧周期 × 20%（实测 Δ 顶到上限 ⇒ 该机该帧率物理上装不下满窗） */
  static readonly AUTO_CAP_PERCENT: number = 20;

  /** 实测 Δ 之外再加的安全系数（1ms）：中位数会低估尾部，留一刀余量 */
  static readonly AUTO_SAFETY_NS: number = 1_000_000;

  /** 探测样本数（帧间隔数）：24fps × 32 ≈ 1.3s 窗口 */
  static readonly PROBE_SAMPLES: number = 32;

  /** 探测超时（帧流不足时放弃本轮）与冷却（防死循环重试） */
  static readonly PROBE_TIMEOUT_MS: number = 5_000;
  static readonly PROBE_COOLDOWN_MS: number = 5_000;

  /** 手动滑杆值夹进 [0, +8ms]（步进对齐在 UI 层做） */
  static clampManualNs(v: number): number {
    return Math.min(ExposureCorrection.MANUAL_MAX_NS,
      Math.max(ExposureCorrection.MANUAL_MIN_NS, v));
  }

  /** 帧周期：1e9 / 档位帧率（与 RequestApplier.frameDurationNs 同一条算式，core 侧副本） */
  static framePeriodNs(fps: number): number {
    return Math.floor(WotaTiers.NS_PER_SECOND / Math.max(1, Math.floor(fps)));
  }

  /**
   * 等号档判定：**强制快门档**（1/24、1/25）且快门 ≥ 帧周期。
   * 25fps 选 1/24（41.67ms 被钳到 40ms == 帧周期）同样命中；1/25@24（40 < 41.67）不命中——
   * 那对组合本来就不满窗，读出有余量，不需要纠偏。非强制档（如 1/30@30）不命中：UI 开关
   * 只在强制档露出，行为与入口同域。
   */
  static applicable(shutterNs: number, fps: number): boolean {
    return WotaTiers.isRequiredShutterNs(shutterNs) && shutterNs >= ExposureCorrection.framePeriodNs(fps);
  }

  /**
   * 自动余量：实测 Δ + 1ms 安全系数，夹进 [5%, 20%] 帧周期。
   * Δ 缺失（undefined = 没测到/键不匹配）或为 0（守约机）⇒ 落在下限 5%。
   * Δ 是**绝对量**（读出 ≈ 扫描耗时，不随帧率等比变）：换帧率后按新帧周期重新夹界，
   * 不做百分比外推。
   */
  static autoMarginNs(deltaNs: number | undefined, perFrameNs: number): number {
    const floor: number = Math.floor(perFrameNs * ExposureCorrection.AUTO_FLOOR_PERCENT / 100);
    const cap: number = Math.floor(perFrameNs * ExposureCorrection.AUTO_CAP_PERCENT / 100);
    const raw: number = Math.max(0, deltaNs === undefined ? 0 : deltaNs) + ExposureCorrection.AUTO_SAFETY_NS;
    return Math.min(cap, Math.max(floor, raw));
  }

  /** 手动滑杆值即纠偏值：正值 = 从满窗再缩短（更稳）；0 = 满窗直发（等号，与改版前一致）。 */
  static manualMarginNs(sliderNs: number): number {
    return ExposureCorrection.clampManualNs(sliderNs);
  }

  /**
   * 发送曝光 = 钳制后曝光 − 纠偏值，夹进 [1ns, 帧周期]。
   * 上限封死「曝光越窗」（防御：手动 0 = 满窗直发是合法取值，只防溢出不到负）；下限防 0/负值。
   */
  static correctedExposureNs(clampedNs: number, marginNs: number, perFrameNs: number): number {
    return Math.min(Math.max(clampedNs - marginNs, 1), perFrameNs);
  }

  /**
   * 探测折算：帧间隔**中位数** − 请求周期 = 拉长量 Δ。
   * 中位数抗突发抖动（编码器启停那一两拍的长间隔不该污染结论）；偶数个取中间两数平均
   * （整除向下，对齐上游 Long 除法）；Δ 为负（本机跑得比请求还快）归 0——「没拉长」不是「负拉长」。
   */
  static probeDeltaNs(intervalsNs: Array<number>, periodNs: number): number {
    if (intervalsNs.length === 0) {
      return 0;
    }
    const sorted: Array<number> = intervalsNs.slice().sort((a: number, b: number): number => a - b);
    const mid: number = sorted.length % 2 === 1
      ? sorted[Math.floor(sorted.length / 2)]
      : Math.floor((sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2);
    return Math.max(0, mid - periodNs);
  }
}

/**
 * 读出探测闩锁：预览态发等号请求测得的实际拉长量 Δ（帧间隔中位数 − 请求周期），
 * 按 key = "镜头|画幅|fps" 键控——读出时间随这三者变，键不匹配即重探。
 * 仅运行期，不落盘（固件/模式都可能变，每会话重测）。
 */
export class ReadoutLatch {
  key: string;
  deltaNs: number;

  constructor(key: string, deltaNs: number) {
    this.key = key;
    this.deltaNs = deltaNs;
  }
}
