/**
 * 窗口外观适配（T31）：沉浸式取景 + 系统栏配色 + 安全区（状态栏/挖孔/导航条）。
 *
 * 取证（T31 真机 HUAWEI ALN-AL00 / 1260x2720 / API 26）：
 * - 未调 window 配置时 XComponent 只拿到 [0,124]..[1260,2629]（顶部状态栏 124px、底部导航条 91px），
 *   底部露白条 → 取景页必须 setWindowLayoutFullScreen(true) 并让系统栏透明；
 * - 浅色系统图标 + 深色相机底 → 统一「透明栏 + 白色图标」；设置/媒体库等普通页恢复黑底系统栏；
 * - HUD 需避开挖孔/状态栏：读 AvoidArea TYPE_SYSTEM + TYPE_CUTOUT 的 topRect/bottomRect，
 *   按 densityPixels 转 vp 交给页面做 padding。
 *
 * 首帧与竞态（T31 复审 P3-5）：
 * - 冷启动由 EntryAbility 在 loadContent **之前**调 prime() 同步落一次「全屏+透明栏+黑底」，
 *   消除首帧白条；
 * - 所有外观切换经模块级 Promise 链**串行**执行（后调用排在先调用之后），页面间快速来回
 *   不会出现「先设黑栏、后落全屏」的乱序；链内单步失败只记日志，不阻断后续。
 *
 * 兼容性：Promise 版 setWindowLayoutFullScreen / setWindowSystemBarProperties 均自 API 9/12 可用
 * （回调版自 12 起 deprecated，这里只用 Promise 版）；任何一步失败只记日志、不阻断页面
 * （真机若全屏导致 surface 异常，回退策略见 PORT_MAP：保持非全屏 + 黑底白图标）。
 */
import { display, window } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { UIOrientation, UIOrientations } from '../core/UIOrientation';

const TAG: string = 'WotaWindow';

/** 安全区（vp）：上（状态栏/挖孔）与下（导航条/手势条）。左右留白待横屏走查（T32 登记）。 */
export class SafeInsets {
  top: number = 0;
  bottom: number = 0;
}

let appearanceChain: Promise<void> = Promise.resolve();

/** 外观任务串行化：后调用排队在前调用之后（单步失败不打断链） */
function runSerialized(task: () => Promise<void>): Promise<void> {
  const next: Promise<void> = appearanceChain.then((): Promise<void> => task())
    .catch((e: Object): void => {
      Diag.w(TAG, `appearance task failed: ${e}`);
    });
  appearanceChain = next;
  return next;
}

function densityPixels(): number {
  try {
    const d: display.Display = display.getDefaultDisplaySync();
    return d.densityPixels > 0 ? d.densityPixels : 1;
  } catch (e) {
    return 1;
  }
}

/** 取景页外观任务（全屏 + 透明系统栏白图标 + 黑底防白闪） */
async function applyCameraTask(win: window.Window): Promise<void> {
  try {
    win.setWindowBackgroundColor('#FF000000');
  } catch (e) {
    Diag.w(TAG, `setWindowBackgroundColor failed: ${e}`);
  }
  try {
    await win.setWindowLayoutFullScreen(true);
  } catch (e) {
    Diag.w(TAG, `setWindowLayoutFullScreen failed: ${e}`);
  }
  try {
    await win.setWindowSystemBarProperties({
      statusBarColor: '#00000000',
      navigationBarColor: '#00000000',
      statusBarContentColor: '#FFFFFF',
      navigationBarContentColor: '#FFFFFF',
      isStatusBarLightIcon: true,
      isNavigationBarLightIcon: true
    });
  } catch (e) {
    Diag.w(TAG, `setWindowSystemBarProperties(fullscreen) failed: ${e}`);
  }
}

/** 普通页外观任务（黑底系统栏 + 白图标） */
async function applyStandardTask(win: window.Window): Promise<void> {
  try {
    win.setWindowBackgroundColor('#FF000000');
  } catch (e) {
    Diag.w(TAG, `setWindowBackgroundColor failed: ${e}`);
  }
  try {
    await win.setWindowLayoutFullScreen(false);
  } catch (e) {
    Diag.w(TAG, `setWindowLayoutFullScreen(false) failed: ${e}`);
  }
  try {
    await win.setWindowSystemBarProperties({
      statusBarColor: '#FF000000',
      navigationBarColor: '#FF000000',
      statusBarContentColor: '#FFFFFF',
      navigationBarContentColor: '#FFFFFF',
      isStatusBarLightIcon: true,
      isNavigationBarLightIcon: true
    });
  } catch (e) {
    Diag.w(TAG, `setWindowSystemBarProperties(dark) failed: ${e}`);
  }
}

export class WindowChrome {
  /** 取当前窗口（失败返回 undefined，调用方保持默认外观） */
  static async lastWindow(ctx: common.Context): Promise<window.Window | undefined> {
    try {
      return await window.getLastWindow(ctx as common.BaseContext);
    } catch (e) {
      Diag.w(TAG, `getLastWindow failed: ${e}`);
      return undefined;
    }
  }

  /**
   * 冷启动首帧预置（EntryAbility 在 loadContent 之前调用，不等待）：
   * 入队「全屏 + 透明栏 + 黑底」，页面后续 applyCamera 会排在其后，不会乱序。
   */
  static prime(win: window.Window | undefined): void {
    if (win === undefined) {
      return;
    }
    runSerialized((): Promise<void> => applyCameraTask(win));
  }

  /**
   * 沉浸式取景（串行）：布局延伸进系统栏 + 系统栏透明 + 白色内容图标 + 黑底。
   * 返回安全区（vp），页面据此给 HUD 上下留边（挖孔不被读数遮挡）。
   */
  static async applyCamera(win: window.Window | undefined): Promise<SafeInsets> {
    const insets: SafeInsets = new SafeInsets();
    if (win === undefined) {
      return insets;
    }
    await runSerialized((): Promise<void> => applyCameraTask(win));
    const density: number = densityPixels();
    try {
      const sys: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      if (sys.visible && sys.topRect.height > 0) {
        insets.top = Math.max(insets.top, sys.topRect.height / density);
      }
      if (sys.visible && sys.bottomRect.height > 0) {
        insets.bottom = Math.max(insets.bottom, sys.bottomRect.height / density);
      }
    } catch (e) {
      Diag.w(TAG, `avoidArea(system) failed: ${e}`);
    }
    try {
      const cutout: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_CUTOUT);
      if (cutout.visible && cutout.topRect.height > 0) {
        insets.top = Math.max(insets.top, cutout.topRect.height / density);
      }
      if (cutout.visible && cutout.bottomRect.height > 0) {
        insets.bottom = Math.max(insets.bottom, cutout.bottomRect.height / density);
      }
    } catch (e) {
      // TYPE_CUTOUT 个别设备不支持：忽略（状态栏高度已覆盖绝大多数挖孔）
    }
    Diag.i(TAG, `camera insets top=${Math.round(insets.top)} bottom=${Math.round(insets.bottom)} ` +
      `density=${density}`);
    return insets;
  }

  /**
   * 界面方向（UO 批）：把设置页的「竖屏固定/横屏」档位写进窗口首选方向（串行，同一链）。
   * 窗口级 sticky：一次设置对所有页生效（上游全页统一口径）；方向变化由系统旋转完成，
   * 页面 SafeInsets 已挂 display.on('change') 的消费方会自动重算。
   * 失败只记日志（方向锁不影响功能），下次冷启 EntryAbility 会再按持久化档位落一次。
   */
  static applyOrientation(win: window.Window | undefined, orientation: UIOrientation): void {
    if (win === undefined) {
      return;
    }
    const code: number = UIOrientations.preferredOrientationCodeOf(orientation);
    runSerialized(async (): Promise<void> => {
      try {
        await win.setPreferredOrientation(code as window.Orientation);
        Diag.i(TAG, `preferred orientation -> ${UIOrientations.persistValue(orientation)}`);
      } catch (e) {
        Diag.w(TAG, `setPreferredOrientation(${code}) failed: ${e}`);
      }
    });
  }

  /** 普通页（设置/媒体库/播放）：黑底系统栏 + 白色内容图标（串行，与取景页同一链） */
  static async applyStandardDark(win: window.Window | undefined): Promise<void> {
    if (win === undefined) {
      return;
    }
    await runSerialized((): Promise<void> => applyStandardTask(win));
  }
}
