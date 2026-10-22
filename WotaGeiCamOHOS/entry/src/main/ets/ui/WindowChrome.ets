/**
 * 窗口外观适配（T31）：沉浸式取景 + 系统栏配色 + 安全区（挖孔/状态栏/导航条）。
 *
 * 取证（T31 真机 HUAWEI ALN-AL00 / 1260x2720 / API 26）：
 * - 未调 window 配置时 XComponent 只拿到 [0,124]..[1260,2629]（顶部状态栏 124px、底部导航条 91px），
 *   底部露白条 → 取景页必须 setWindowLayoutFullScreen(true) 并让系统栏透明；
 * - 浅色系统图标 + 深色相机底 → 统一「透明栏 + 白色图标」；设置/媒体库等普通页恢复黑底系统栏；
 * - HUD 需避开挖孔/状态栏：读 AvoidArea TYPE_SYSTEM + TYPE_CUTOUT 的 topRect/bottomRect，
 *   按 densityPixels 转 vp 交给页面做 padding。
 *
 * 兼容性：Promise 版 setWindowLayoutFullScreen / setWindowSystemBarProperties 均自 API 9/12 可用
 * （回调版自 12 起 deprecated，这里只用 Promise 版）；任何一步失败只记日志、不阻断页面
 * （真机若全屏导致 surface 异常，回退策略见 PORT_MAP：保持非全屏 + 黑底白图标）。
 */
import { display, window } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';

const TAG: string = 'WotaWindow';

/** 安全区（vp）：取系统状态栏与挖孔区并集的上边、导航条/手势条的下边 */
export class SafeInsets {
  top: number = 0;
  bottom: number = 0;
  left: number = 0;
  right: number = 0;
}

function densityPixels(): number {
  try {
    const d: display.Display = display.getDefaultDisplaySync();
    return d.densityPixels > 0 ? d.densityPixels : 1;
  } catch (e) {
    return 1;
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
   * 沉浸式取景：布局延伸进系统栏 + 系统栏透明 + 白色内容图标。
   * 返回安全区（vp），页面据此给 HUD 上下留边（挖孔不被读数遮挡）。
   */
  static async applyCamera(win: window.Window | undefined): Promise<SafeInsets> {
    const insets: SafeInsets = new SafeInsets();
    if (win === undefined) {
      return insets;
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

  /** 普通页（设置/媒体库/播放）：黑底系统栏 + 白色内容图标（与相机页一致的深色外观） */
  static async applyStandardDark(win: window.Window | undefined): Promise<void> {
    if (win === undefined) {
      return;
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
}
