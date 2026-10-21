/**
 * 直译自上游 core/UIOrientation.kt（harmony 分支）。
 * 平台差异：上游 screenOrientationOf 映射到 ActivityInfo.SCREEN_ORIENTATION_SENSOR_*；
 * OHOS 侧等价物是 window.Orientation.AUTO_ROTATION_LANDSCAPE/PORTRAIT——
 * 同样「锁轴、但轴向两个朝向随重力翻转」，语义对齐（上游注释里强调不能用单方向锁死）。
 */
import { window } from '@kit.ArkUI';

/** 设置页「默认方向」两档（持久化键 ui_orientation，默认 LANDSCAPE） */
export enum UIOrientation {
  /** 横屏使用：锁在横屏，两个横屏朝向仍随重力自动翻转 */
  LANDSCAPE,

  /** 竖屏使用：锁在竖屏，两个竖屏朝向的自动翻转保留 */
  PORTRAIT
}

export class UIOrientations {
  static readonly ALL: Array<UIOrientation> = [UIOrientation.LANDSCAPE, UIOrientation.PORTRAIT];

  /** 出厂默认：横屏使用 */
  static readonly DEFAULT: UIOrientation = UIOrientation.LANDSCAPE;

  static persistValue(o: UIOrientation): string {
    return o === UIOrientation.LANDSCAPE ? 'landscape' : 'portrait';
  }

  /** 存进去的串按 persistValue 取，缺失/坏串一律回落 DEFAULT，不抛；大小写不敏感 */
  static fromPersistValue(raw: string | undefined): UIOrientation {
    if (raw === undefined) {
      return UIOrientations.DEFAULT;
    }
    const lower: string = raw.toLowerCase();
    for (const o of UIOrientations.ALL) {
      if (UIOrientations.persistValue(o) === lower) {
        return o;
      }
    }
    return UIOrientations.DEFAULT;
  }

  /**
   * 用户档位 → 窗口首选方向的映射（唯一真源），对所有页生效（上游 2026-10-02 指令）。
   * 必须用 AUTO_ROTATION_* 而不是单方向 LANDSCAPE/PORTRAIT——后者把朝向钉死在单一方向，
   * 「反向横屏也要能用」的既有需求会被堵死（与上游 SENSOR_* 论证同一条）。
   */
  static preferredOrientationOf(orientation: UIOrientation): window.Orientation {
    return orientation === UIOrientation.LANDSCAPE
      ? window.Orientation.AUTO_ROTATION_LANDSCAPE
      : window.Orientation.AUTO_ROTATION_PORTRAIT;
  }
}
