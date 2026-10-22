/**
 * OHOS CameraKit 枚举 ↔ Camera2 契约值映射层（参数下发批）。
 *
 * 契约面：core/Constants.ets 的枚举值保留 Android Camera2 契约值（AfMode 3/4/2/0、WbPreset 0..8、
 * AeMode、Flash），相机适配层负责换算——本文件就是这张换算表，输入契约值、输出 camera.* 枚举。
 *
 * 完整映射表（验收口径）：
 * | 契约 | OHOS | 说明 |
 * |---|---|---|
 * | AfMode.MANUAL(0) | FocusMode.FOCUS_MODE_MANUAL(0) | |
 * | AfMode.CONTINUOUS_VIDEO(3) / CONTINUOUS_PICTURE(4) | FocusMode.FOCUS_MODE_CONTINUOUS_AUTO(1) | 两档连续语义在 OHOS 合并；反向映射双发 {3,4}（登记） |
 * | AfMode.MACRO(2) | FocusMode.FOCUS_MODE_AUTO(2) | OHOS 无微距档（登记近似） |
 * | WbPreset.AUTO(1) | WhiteBalanceMode.AUTO(0) | |
 * | WbPreset.INCANDESCENT(2) | WhiteBalanceMode.INCANDESCENT(2) | |
 * | WbPreset.WARM_FLUORESCENT(4) | WhiteBalanceMode.INCANDESCENT(2) | OHOS 无暖白档（登记近似） |
 * | WbPreset.FLUORESCENT(3) | WhiteBalanceMode.FLUORESCENT(3) | |
 * | WbPreset.DAYLIGHT(5) | WhiteBalanceMode.DAYLIGHT(4) | |
 * | WbPreset.CLOUDY_DAYLIGHT(6) / TWILIGHT(7) / SHADE(8) | WhiteBalanceMode.CLOUDY(1) | 后两档无对应，归入阴天（登记近似） |
 * | WbPreset.MANUAL(0) | WhiteBalanceMode.MANUAL(5) | OHOS 手动白平衡走 setWhiteBalance(kelvin)，无 RGB 增益接口 |
 * | AeMode.AUTO | ExposureMode.EXPOSURE_MODE_CONTINUOUS_AUTO(2) | |
 * | AeMode.LOCK / MANUAL | ExposureMode.EXPOSURE_MODE_LOCKED(0) | OHOS 无 AE OFF；MANUAL 由显式时长+ISO 实现（登记） |
 * | Flash.OFF/ON/AUTO/TORCH | FlashMode.CLOSE/OPEN/AUTO/ALWAYS_OPEN (0/1/2/3) | 由 RequestApplier 的纯 flashPlan → flashModeOfPlan 派生（无灯/手动退化在 plan 层门控），本文件不提供独立映射函数 |
 * | OIS on/off | OISMode.AUTO(1)/OFF(0) | |
 * | EIS on/off | VideoStabilizationMode.AUTO(4)/OFF(0) | 强度由设备自选（登记） |
 *
 * 反向（能力探测）：isFocusModeSupported / isWhiteBalanceModeSupported 逐档探测后映射回契约值，
 * 供 abilityRefineWithSession 补 afModes/awbModes 真值。LOCKED / WHITE_BALANCE_MODE_LOCKED 无契约值，不产出。
 */
import { camera } from '@kit.CameraKit';
import { AeMode, AfMode, WbPreset } from '../core/Constants';

/** 契约 AfMode → OHOS FocusMode（连续两档合并、微距降级 AUTO，见头注释） */
export function focusModeFor(m: AfMode): camera.FocusMode {
  switch (m) {
    case AfMode.MANUAL: return camera.FocusMode.FOCUS_MODE_MANUAL;
    case AfMode.CONTINUOUS_VIDEO:
    case AfMode.CONTINUOUS_PICTURE:
      return camera.FocusMode.FOCUS_MODE_CONTINUOUS_AUTO;
    case AfMode.MACRO:
      return camera.FocusMode.FOCUS_MODE_AUTO;
    default:
      return camera.FocusMode.FOCUS_MODE_AUTO;
  }
}

/**
 * 反向：探测会话支持的对焦模式 → Camera2 契约值集合。
 * CONTINUOUS_AUTO 双发 {3,4}（连续语义同时满足录像/拍照两档，登记）；LOCKED 不产出。
 * FOCUS_MODE_AUTO 反解为 AfMode.MACRO(2)：core 的 AfMode 没有「AUTO=1」契约值
 * （AfModes.fromCam2(1) 返回 undefined，消费方无法识别裸 1），而前向映射 MACRO→FOCUS_MODE_AUTO，
 * 故按同一近似档位收回（只支持 AUTO 的设备在 UI 上落 MACRO 档，登记）。
 * 探针中途异常 → 清空已收集集合（返回空集，调用方回退 base），不返回半套结果。
 */
export function focusModesToCam2(session: camera.PhotoSession): Set<number> {
  const out: Set<number> = new Set<number>();
  try {
    if (session.isFocusModeSupported(camera.FocusMode.FOCUS_MODE_MANUAL)) {
      out.add(AfMode.MANUAL as number); // 0
    }
    if (session.isFocusModeSupported(camera.FocusMode.FOCUS_MODE_AUTO)) {
      out.add(AfMode.MACRO as number); // 2（近似，见上）
    }
    if (session.isFocusModeSupported(camera.FocusMode.FOCUS_MODE_CONTINUOUS_AUTO)) {
      out.add(AfMode.CONTINUOUS_VIDEO as number); // 3
      out.add(AfMode.CONTINUOUS_PICTURE as number); // 4
    }
  } catch (e) {
    // 探针失败按空集返回（调用方保 base 值）
    out.clear();
  }
  return out;
}

/** 契约 WbPreset → OHOS WhiteBalanceMode（近似档见头注释） */
export function wbModeFor(p: WbPreset): camera.WhiteBalanceMode {
  switch (p) {
    case WbPreset.AUTO: return camera.WhiteBalanceMode.AUTO;
    case WbPreset.INCANDESCENT: return camera.WhiteBalanceMode.INCANDESCENT;
    case WbPreset.WARM_FLUORESCENT: return camera.WhiteBalanceMode.INCANDESCENT;
    case WbPreset.FLUORESCENT: return camera.WhiteBalanceMode.FLUORESCENT;
    case WbPreset.DAYLIGHT: return camera.WhiteBalanceMode.DAYLIGHT;
    case WbPreset.CLOUDY_DAYLIGHT:
    case WbPreset.TWILIGHT:
    case WbPreset.SHADE:
      return camera.WhiteBalanceMode.CLOUDY;
    case WbPreset.MANUAL: return camera.WhiteBalanceMode.MANUAL;
    default: return camera.WhiteBalanceMode.AUTO;
  }
}

/**
 * 反向：探测会话支持的白平衡模式 → Camera2 AWB_MODE 契约值集合
 * （AUTO→1、CLOUDY→6、INCANDESCENT→2、FLUORESCENT→3、DAYLIGHT→5、MANUAL→0；LOCKED 不产出）。
 */
export function wbModesToAwb(session: camera.PhotoSession): Set<number> {
  const out: Set<number> = new Set<number>();
  try {
    if (session.isWhiteBalanceModeSupported(camera.WhiteBalanceMode.AUTO)) {
      out.add(WbPreset.AUTO as number); // 1
    }
    if (session.isWhiteBalanceModeSupported(camera.WhiteBalanceMode.CLOUDY)) {
      out.add(WbPreset.CLOUDY_DAYLIGHT as number); // 6
    }
    if (session.isWhiteBalanceModeSupported(camera.WhiteBalanceMode.INCANDESCENT)) {
      out.add(WbPreset.INCANDESCENT as number); // 2
    }
    if (session.isWhiteBalanceModeSupported(camera.WhiteBalanceMode.FLUORESCENT)) {
      out.add(WbPreset.FLUORESCENT as number); // 3
    }
    if (session.isWhiteBalanceModeSupported(camera.WhiteBalanceMode.DAYLIGHT)) {
      out.add(WbPreset.DAYLIGHT as number); // 5
    }
    if (session.isWhiteBalanceModeSupported(camera.WhiteBalanceMode.MANUAL)) {
      out.add(WbPreset.MANUAL as number); // 0
    }
  } catch (e) {
    // 探针失败按空集返回（调用方保 base 值）
    out.clear();
  }
  return out;
}

/** 契约 AeMode → OHOS ExposureMode：OHOS 无 AE OFF，MANUAL 也走 LOCKED + 显式时长/ISO
 * （d.ts：setIso 仅 EXPOSURE_MODE_LOCKED 生效，故不用 EXPOSURE_MODE_MANUAL——它会令 setIso 失效，登记） */
export function exposureModeFor(m: AeMode): camera.ExposureMode {
  switch (m) {
    case AeMode.AUTO: return camera.ExposureMode.EXPOSURE_MODE_CONTINUOUS_AUTO;
    case AeMode.LOCK:
    case AeMode.MANUAL:
    default:
      return camera.ExposureMode.EXPOSURE_MODE_LOCKED;
  }
}

/** OIS 开关 → OHOS OISMode（ON 交给设备自选 AUTO，登记） */
export function oisModeFor(on: boolean): camera.OISMode {
  // @SuppressWarnings compatibility
  return on ? camera.OISMode.AUTO : camera.OISMode.OFF;
}

/** EIS 开关 → OHOS VideoStabilizationMode（ON 交给设备自选强度 AUTO，登记） */
export function stabilizationModeFor(on: boolean): camera.VideoStabilizationMode {
  return on ? camera.VideoStabilizationMode.AUTO : camera.VideoStabilizationMode.OFF;
}
