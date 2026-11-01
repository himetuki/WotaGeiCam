/**
 * 手动白平衡**走哪条路**的纯决策层——上游 core/WbPlan.kt（f4aeb36）+ RequestApplier 的
 * 色温/色调→增益模型（c0ea34a 订正版）的鸿蒙移植。
 *
 * # 为什么需要（上游现场取证，2026-10-28）
 * Camera2 的手动白平衡要 HAL 声明 TRANSFORM_MATRIX 才会采纳增益；未声明时**不是"没这功能"**，
 * 而是"增益不会被采纳"：WIKO GAR-AN60 实测——AWB OFF 照做（画面变传感器原色 ⇒ 发绿），
 * 四档色温预览逐像素无差别（增益被丢）。兜底口径：不支持增益时**不再关 AWB**，
 * 请求侧留在自动白平衡，画面侧由场景层（GL）乘一组**相对增益**——5500K 恒等，
 * 低于 5500 偏暖、高于 5500 偏冷；切兜底必须给运行时提示。
 *
 * # 本侧落位（平台裁定，登记）
 * - OHOS 相机面**没有** RGB 增益/色彩矩阵接口（setWhiteBalanceMode + setWhiteBalance(kelvin)
 *   二键，RequestApplier 头注释在案）⇒ 上游 c0ea34a/ea5b26e 修的两处模型缺陷
 *   （中性锚点 6000K 偏冷一档 / 绿增益被砍半 / identity CCM）在本侧**不存在同类代码**；
 * - 但"HAL 不采纳时画面层兜底"的决策与算式**平台无关**——本文件按直接译交付纯层 +
 *   全表用例；GL 场景层乘增益的 native 面（uniform + NAPI 桥 + 运行时提示 UI）待平台批，
 *   **grep 不到接线方时不伪造接线**（DirectDisplay/FrameRateFallback 同款纪律）；
 * - halSupportsGains 的本侧对位 = `CameraAbility.manualWhiteBalancePossible()`
 *   （探测表无 MANUAL ⇒ 走 GL 兜底）；上游"request-vs-result 采纳判据"（813e83f）
 *   依赖结果元数据读回，OHOS 无该 API 面，N/A 登记。
 */

/** 手动白平衡的中性色温（D55）：该档位下三通道增益完全相等 ⇒ 不加任何偏色。
 *  必须是 5500 而不是色温域中点（6000）——5500 是用户与行业共同的"日光参考"，
 *  把零偏色放在 6000 会让所有标称值整体偏冷一档（上游 c0ea34a 订正）。 */
import { WotaTiers } from './Constants';

const KELVIN_NEUTRAL: number = 5500;

/** tint ±50 满档在 0–255 域的张度（R/B 加、G 减） */
const TINT_SPAN_RGB: number = 38;
const TINT_SPAN_GREEN: number = 26;

/** 三锚点两段线性插值（t ≤ 0.5 走 a→b 段，否则 b→c 段） */
function lerp3(t: number, a: number, b: number, c: number): number {
  return t <= 0.5 ? a + (b - a) * (t * 2) : b + (c - b) * ((t - 0.5) * 2);
}

/**
 * 色温 + tint → 0–255 域 [R,G,B]（上游 kelvinTintToRgb255 直译）。
 * u=0 ↔ 2000K、u=0.5 ↔ 5500K（中性）、u=1 ↔ 10000K：两段各自线性，
 * 保证中性点精确落在 5500 而不是两端的中点（6000）。
 */
export function kelvinTintToRgb255(kelvin: number, tint: number): Array<number> {
  const k: number = Math.min(WotaTiers.KELVIN_MAX, Math.max(WotaTiers.KELVIN_MIN, kelvin));
  const u: number = k <= KELVIN_NEUTRAL
    ? 0.5 * (k - WotaTiers.KELVIN_MIN) / (KELVIN_NEUTRAL - WotaTiers.KELVIN_MIN)
    : 0.5 + 0.5 * (k - KELVIN_NEUTRAL) / (WotaTiers.KELVIN_MAX - KELVIN_NEUTRAL);
  const v: number = Math.min(1, Math.max(0, (tint - WotaTiers.TINT_MIN) / (WotaTiers.TINT_MAX - WotaTiers.TINT_MIN)));
  const r: number = lerp3(u, 0.298, 0.549, 1) * 255;
  const g: number = lerp3(u, 0.502, 0.549, 0.502) * 255;
  const b: number = lerp3(u, 1, 0.549, 0.298) * 255;
  const t: number = (v - 0.5) * 2; // −1 偏绿 / +1 偏品红
  return [
    Math.min(255, Math.max(0, r + t * TINT_SPAN_RGB)),
    Math.min(255, Math.max(0, g - t * TINT_SPAN_GREEN)),
    Math.min(255, Math.max(0, b + t * TINT_SPAN_RGB))
  ];
}

/** 0–255 → 增益 1.0–3.0（线性映射，不自己构造色度矩阵） */
export function gain255ToUnit(x: number): number {
  return 1 + (x / 255) * 2;
}

/**
 * 手动档增益的**纯口径**（上游 manualGains 直译；绿通道满额——上游 c0ea34a 订正
 * "g/2 砍半 ⇒ 偏品红发冷"，本模型生来就是满额单条 G）：
 * 色温 + tint → 单位域 [R,G,B]。
 */
export function manualGains(kelvin: number, tint: number): Array<number> {
  const rgb: Array<number> = kelvinTintToRgb255(kelvin, tint);
  return [gain255ToUnit(rgb[0]), gain255ToUnit(rgb[1]), gain255ToUnit(rgb[2])];
}

/** 手动白平衡落地路径（上游 WbApply data class 直译） */
export class WbApply {
  /** true = 走 HAL（常规路径；本侧 = setWhiteBalanceMode(MANUAL)+setWhiteBalance(kelvin)） */
  byHal: boolean;
  /** true = 走画面算法（GL 场景层乘增益），HAL 侧保持自动白平衡 */
  byGl: boolean;
  /** GL 侧要乘的相对增益 [R,G,B]；undefined = 不改画面 */
  gains: Array<number> | undefined;
  /** true = 必须给用户一次运行时提示（"此设备未声明手动白平衡增益支持"） */
  noticeNeeded: boolean;

  constructor(byHal: boolean, byGl: boolean, gains: Array<number> | undefined, noticeNeeded: boolean) {
    this.byHal = byHal;
    this.byGl = byGl;
    this.gains = gains;
    this.noticeNeeded = noticeNeeded;
  }

  /** 不做任何白平衡处理（自动/预设档，或手动档被禁用） */
  static NONE(): WbApply {
    return new WbApply(false, false, undefined, false);
  }
}

/**
 * 决定这一拍白平衡怎么落地（上游 wbApplyPlan 直译）。
 *
 * @param manual 用户是否选了手动档（WbPreset.MANUAL）
 * @param halSupportsGains 本侧对位 = CameraAbility.manualWhiteBalancePossible()：
 *   探测表声明 MANUAL 才算支持（能力协商而非能力假设）
 * @param kelvin 手动档色温（走 HAL 时直接下发；走 GL 时折算成相对增益）
 * @param tint 手动档色调（同上）
 *
 * 相对增益的归一化：以**最大通道为 1.0** 缩放 ⇒ 只改色不改亮度（自动曝光不必因白平衡跳档）。
 */
export function wbApplyPlan(manual: boolean, halSupportsGains: boolean,
  kelvin: number, tint: number): WbApply {
  if (!manual) {
    return WbApply.NONE();
  }
  if (halSupportsGains) {
    return new WbApply(true, false, undefined, false);
  }
  return new WbApply(false, true, normalizeMaxToUnit(manualGains(kelvin, tint)), true);
}

/**
 * 三通道增益归一到"最大通道 = 1.0"：只表达**比例**，不改变整体亮度。
 * 非法输入（空/非有限/全 0）返回 undefined（调用方据此不做画面处理）。
 */
export function normalizeMaxToUnit(gains: Array<number> | undefined): Array<number> | undefined {
  if (gains === undefined || gains.length < 3) {
    return undefined;
  }
  for (const g of gains) {
    if (!Number.isFinite(g) || g <= 0) {
      return undefined;
    }
  }
  const max: number = Math.max(gains[0], gains[1], gains[2]);
  if (max <= 0) {
    return undefined;
  }
  return [gains[0] / max, gains[1] / max, gains[2] / max];
}

/** 这组增益是不是恒等（全 1.0，容差 1e-4）：恒等就别白跑一趟 LUT */
export function isIdentityGains(gains: Array<number> | undefined): boolean {
  if (gains === undefined) {
    return true;
  }
  return gains.length >= 3 &&
    Math.abs(gains[0] - 1) < 1e-4 &&
    Math.abs(gains[1] - 1) < 1e-4 &&
    Math.abs(gains[2] - 1) < 1e-4;
}
