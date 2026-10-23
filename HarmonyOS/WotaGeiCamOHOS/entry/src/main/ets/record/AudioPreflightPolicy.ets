/**
 * 蓝牙内录预校准的纯策略（直译上游 record/AudioPreflightPolicy.kt，对齐上游 e950328；
 * 零状态、零平台 import，纯测试架直测）。
 *
 * 偏移语义与 TrackSync/ClipRule 同一套：`offsetMs = 内录内容时刻 − 环境内容时刻`。
 * 预校准只处理「内录早于环境」（Bluetooth A2DP 让现场声晚到）这一侧，所以平移量
 * `−offsetMs` 只在 ≥ MIN_PRE_SHIFT_MS 时生效；负 offset（内录已晚）留给录制后的自动校准。
 *
 * 20..600ms 的两端不是机型参数，而是**预校准的能力边界**：
 * - <20ms 听感上不可辨，不折腾 PTS，也避免把测量抖动写成偏移；
 * - >600ms 已超出 A2DP 常见缓冲范围，硬钳到 600ms 保留"能纠多少纠多少"，后校准仍会补残差。
 */

/** 纯策略命名空间（上游 object 语义） */
export class AudioPreflightPolicy {
  /** 预校准最小可辨延迟（ms）：低于它视为 0 */
  static readonly MIN_PRE_SHIFT_MS: number = 20;

  /** 预校准最大允许平移（ms） */
  static readonly MAX_PRE_SHIFT_MS: number = 600;

  /** 后校准允许写入的偏移范围（±ms）；超出的只记日志不落盘 */
  static readonly MAX_AUTO_OFFSET_MS: number = 1000;

  /** 预校准/后校准共用的置信度门槛（比对比页手动的 1.5 更严：无人值守自动写盘） */
  static readonly MIN_CONFIDENCE: number = 2.0;

  /**
   * 预校准平移量（µs）：正 = 内录轨整体延后。
   * 低置信度 → 0；`−offsetMs < 20`（内录不早）→ 0；>600 → 钳到 600。
   */
  static capPreShiftUs(
    offsetMs: number,
    confidence: number,
    minConfidence: number = AudioPreflightPolicy.MIN_CONFIDENCE
  ): number {
    if (confidence < minConfidence) {
      return 0;
    }
    const delayMs: number = -offsetMs;
    if (delayMs < AudioPreflightPolicy.MIN_PRE_SHIFT_MS) {
      return 0;
    }
    return Math.min(delayMs, AudioPreflightPolicy.MAX_PRE_SHIFT_MS) * 1000;
  }

  /** 只有「环境+内录双轨且输出当前是 A2DP」才值得在起录前多花 800ms */
  static shouldPreflight(envOn: boolean, capOn: boolean, a2dp: boolean): boolean {
    return envOn && capOn && a2dp;
  }

  /**
   * A2DP 判定的组合面（上游 a2dpFromOutputTypes 的纯化移植）：
   * 上游在纯函数里比对 Android 常量 `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`；本侧
   * `audio.DeviceType.BLUETOOTH_A2DP` 是平台枚举，比对移到 record/AudioRoute.ets（平台件），
   * 判定结果以**注入布尔**进入本策略，纯函数面只保留「活动路由优先、设备列表兜底」的组合语义。
   * 【语义差异登记】OHOS 无 isBluetoothA2dpOn 对应 API，AudioRoute 只能传 listHasA2dp
   * （上游的兜底路径），activeA2dp 恒 false——可能多跑一次预校准（无害：失败路径恒 shift=0）。
   */
  static a2dpFromOutputTypes(activeA2dp: boolean, listHasA2dp: boolean): boolean {
    return activeA2dp || listHasA2dp;
  }

  /** 后校准可落盘判据：置信度过门槛且偏移在 ±1000ms 内 */
  static autoOffsetWithinRange(
    offsetMs: number,
    confidence: number,
    minConfidence: number = AudioPreflightPolicy.MIN_CONFIDENCE
  ): boolean {
    return confidence >= minConfidence && Math.abs(offsetMs) <= AudioPreflightPolicy.MAX_AUTO_OFFSET_MS;
  }
}
