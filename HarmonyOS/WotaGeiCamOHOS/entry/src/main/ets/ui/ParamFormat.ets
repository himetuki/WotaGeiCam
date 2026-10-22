/**
 * 高级参数的数值/文案格式化——上游 ui/dialog/CameraDialogs.kt 数值段 + shutterItems、
 * ui/HudLayer.kt wbShort 的纯函数直译（对齐上游 38d712a）。
 *
 * 数字与国际单位缩写不算界面中文文案，集中在此供录制页顶栏读数/胶囊/弹层共用。
 * 中文档名（自动/白炽灯/…）取上游 res/values/strings_camera.xml 的 zh 值逐条照搬，
 * 资源系统接入（ArkUI $r）随设计 Tokens 批，本批内联常量（登记 PORT_MAP §ui）。
 */
import { AeMode, WbPreset, WotaTiers } from '../core/Constants';
import { RangeI, forcedShutterOutOfRange, shutterCeilingNs } from '../core/CameraAbility';

/** Locale.US 的 "%+.1f" 等价（带符号一位小数；fixed1 只出不带符号版） */
function signedFixed1(v: number): string {
  const t: number = Math.round(Math.abs(v) * 10);
  const s: string = `${Math.floor(t / 10)}.${t % 10}`;
  return v < 0 ? `-${s}` : `+${s}`;
}

/** 快门：纳秒 → `1/25`；≥0.1s（SHUTTER_CAP_NS 防手抖上限）走秒制 */
export function shutterText(ns: number): string {
  if (ns <= 0) {
    return '-';
  }
  if (ns >= WotaTiers.SHUTTER_CAP_NS) {
    return `${signedFixed1Abs(ns / WotaTiers.NS_PER_SECOND)}s`;
  }
  return `1/${Math.round(WotaTiers.NS_PER_SECOND / ns)}`;
}

/** %.1fs 的无符号体（0.1s / 1.0s，快门秒制只会出现非负值） */
function signedFixed1Abs(v: number): string {
  const t: number = Math.round(v * 10);
  return `${Math.floor(t / 10)}.${t % 10}`;
}

/** 曝光补偿：步数 × 步长 → `+1.0EV`（core 契约 params.ev 是步数） */
export function evText(steps: number, evStep: number): string {
  return `${signedFixed1(steps * evStep)}EV`;
}

/** 白平衡预设中文名（上游 wbLabelRes 的 strings_camera.xml 值） */
export function wbLabel(preset: WbPreset): string {
  switch (preset) {
    case WbPreset.AUTO: return '自动';
    case WbPreset.INCANDESCENT: return '白炽灯';
    case WbPreset.FLUORESCENT: return '荧光灯';
    case WbPreset.WARM_FLUORESCENT: return '暖荧光';
    case WbPreset.DAYLIGHT: return '日光';
    case WbPreset.CLOUDY_DAYLIGHT: return '阴天';
    case WbPreset.TWILIGHT: return '黄昏';
    case WbPreset.SHADE: return '阴影';
    case WbPreset.MANUAL: return '手动色温';
    default: return '';
  }
}

/** AE 模式中文名（上游 aeLabelRes：cam_ae_auto/manual/lock） */
export function aeLabel(mode: AeMode): string {
  switch (mode) {
    case AeMode.AUTO: return '自动';
    case AeMode.MANUAL: return '手动';
    case AeMode.LOCK: return '锁定 AE/AF';
    default: return '';
  }
}

/** 白平衡读数（对拍 HudLayer.kt wbShort）：手动档显示色温，其余显示预设名 */
export function wbShort(mode: WbPreset, kelvin: number): string {
  return mode === WbPreset.MANUAL ? `${kelvin}K` : wbLabel(mode);
}

/** 一档快门选项：value 是纳秒原始值，label 含强制档 `※` 标，supported 只绑设备判定 */
export class TierItem {
  value: number;
  label: string;
  supported: boolean;

  constructor(value: number, label: string, supported: boolean) {
    this.value = value;
    this.label = label;
    this.supported = supported;
  }
}

/**
 * 快门药丸档位表（对拍 CameraDialogs.kt shutterItems）：
 * 产品档 ∩ 本机范围，交集外灰显（不进梯）。
 *
 * 可选性的上限由 shutterCeilingNs 按 fpsHi 现算（判据与下发/回写同源）：
 * 列表里可选 = 下发不会被帧周期改掉。`※` 只在 supported 为真时参与 label，
 * 灰显档一律不打标（与帧率侧同一规则）。
 */
export function shutterItems(deviceNs: RangeI | undefined, fpsHi: number, approxMark: string): Array<TierItem> {
  const exp: RangeI | undefined = deviceNs !== undefined && deviceNs.ok() ? deviceNs : undefined;
  const out: Array<TierItem> = [];
  for (const denom of WotaTiers.SHUTTER_DENOM) {
    // Kotlin Long 整除截断 → Math.floor（正数域两者一致）
    const ns: number = Math.floor(WotaTiers.NS_PER_SECOND / denom);
    const hi: number | undefined = exp !== undefined ? shutterCeilingNs(exp.hi, fpsHi) : undefined;
    const supported: boolean = exp !== undefined && hi !== undefined && ns >= exp.lo && ns <= hi;
    const forced: boolean = exp !== undefined && supported && forcedShutterOutOfRange(ns, exp);
    out.push(new TierItem(ns, `1/${denom}${forced ? approxMark : ''}`, supported));
  }
  return out;
}
