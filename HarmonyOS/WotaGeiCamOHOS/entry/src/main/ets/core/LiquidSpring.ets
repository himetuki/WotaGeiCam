/**
 * 「液态Q弹」动效语言（上游 core/LiquidSpring.kt，81059e2）的 OHOS 纯层：
 * ArkUI `curves.springMotion(response, damping)` 与上游同语义参数（response=弹簧感知时长的
 * 半周期量纲、dampingFraction=阻尼比），参数面直译、消费面换 ArkUI curves。
 *
 * 三枚规格（上游 2026-10-30 定版）：
 * - float 0.32/0.65（浮层缩放/滑杆落位等常规浮层动效）；
 * - offset 0.42/0.80（弹层定位/位移类）；
 * - press 0.20/0.90（按压回弹 0.97 缩放）。
 *
 * 平台映射（登记）：上游 Compose 用 stiffness=(2π/response)² 自实现弹簧（VelocityScope 速度
 * 继承三处）；ArkUI curves.springMotion(response, damping) 就是同一对语义参数，**不换算
 * stiffness**——纯层交付规格表 + 域钳 + 一致的参数名，消费面直接喂 curves.springMotion。
 */
export class LiquidSpringSpec {
  response: number;
  damping: number;

  constructor(response: number, damping: number) {
    this.response = response;
    this.damping = damping;
  }
}

export class LiquidSpring {
  /** 浮层缩放/滑杆落位等常规浮层动效 */
  static readonly FLOAT: LiquidSpringSpec = new LiquidSpringSpec(0.32, 0.65);
  /** 弹层定位/位移类 */
  static readonly OFFSET: LiquidSpringSpec = new LiquidSpringSpec(0.42, 0.80);
  /** 按压回弹（pressScale 0.97） */
  static readonly PRESS: LiquidSpringSpec = new LiquidSpringSpec(0.20, 0.90);

  /** 压按下的缩放（Bencho Liquid toggle 的按压口径） */
  static readonly PRESS_SCALE: number = 0.97;

  /**
   * 规格合法性：response ∈ [0.2, 0.5]、damping ∈ [0.6, 0.9]（上游域钳一致）；
   * 非有限数也算坏。返回 false 的规格不许进 curves（坏弹簧比无动效更糟）。
   */
  static isValid(spec: LiquidSpringSpec): boolean {
    return Number.isFinite(spec.response) && Number.isFinite(spec.damping) &&
      spec.response >= 0.2 && spec.response <= 0.5 &&
      spec.damping >= 0.6 && spec.damping <= 0.9;
  }

  /** clamp 进合法域（NaN 回 FLOAT 出厂档——坏值宁可保守，不崩不抖） */
  static clamp(spec: LiquidSpringSpec): LiquidSpringSpec {
    if (!Number.isFinite(spec.response) || !Number.isFinite(spec.damping)) {
      return LiquidSpring.FLOAT;
    }
    return new LiquidSpringSpec(
      Math.min(0.5, Math.max(0.2, spec.response)),
      Math.min(0.9, Math.max(0.6, spec.damping))
    );
  }
}
