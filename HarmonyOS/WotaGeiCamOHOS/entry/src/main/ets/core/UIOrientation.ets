/**
 * 设置页「界面方向」两档——上游 core/UIOrientation.kt 直译（持久化键 `ui_orientation`）。
 *
 * **本侧默认偏离登记**：上游出厂默认 LANDSCAPE（横屏优先）；鸿蒙取景 HUD 是 T31 真机取证后的
 * 竖屏优先设计（360vp 宽适配、竖屏取景布局），故本侧 DEFAULT = PORTRAIT（竖屏固定），
 * 用户可在设置页切到横屏。上游的论证全部保留：必须「锁轴不锁向」——AUTO_ROTATION_*
 * 允许同轴两朝向随重力翻转，反向横屏/倒置竖屏仍可用（#21 同条需求）。
 *
 * **零平台 import**：`preferredOrientationCodeOf` 产出 **OHOS window.Orientation 的契约数值**
 * （AUTO_ROTATION_PORTRAIT=6 / AUTO_ROTATION_LANDSCAPE=7，本机 SDK @ohos.window.d.ts 核实）——
 * 与上游「产出 ActivityInfo 编译期 Int 交给 requestedOrientation」完全同构：core 层只出契约
 * 常量，窗口调用（`win.setPreferredOrientation(code as window.Orientation)`）在
 * ui/WindowChrome.ets 的串行链里做。数值可在 test/pure 直接断言（7/6 手写字面量钉住）。
 */

/** 设置页「界面方向」两档（持久化键 ui_orientation） */
export enum UIOrientation {
  /** 竖屏使用：锁在竖屏，两个竖屏朝向的自动翻转保留 */
  PORTRAIT,

  /** 横屏使用：锁在横屏，两个横屏朝向的自动翻转保留 */
  LANDSCAPE
}

export class UIOrientations {
  static readonly ALL: Array<UIOrientation> = [UIOrientation.PORTRAIT, UIOrientation.LANDSCAPE];

  /**
   * 出厂默认：**横屏**（2026-10-28 用户裁决「横屏才是常用状态，做好」——撤销 T31 的
   * 竖屏默认偏离，回到上游 LANDSCAPE 口径；横屏 HUD 已按 T32 提前批落地右侧竖排 Dock，
   * 竖屏仍可在设置页切回并保持 T31 布局）。
   */
  static readonly DEFAULT: UIOrientation = UIOrientation.LANDSCAPE;

  static persistValue(o: UIOrientation): string {
    return o === UIOrientation.PORTRAIT ? 'portrait' : 'landscape';
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
   * 用户档位 → OHOS `window.Orientation` **契约数值**（唯一真源），对所有页生效
   * （上游 2026-10-02 指令）。必须是 AUTO_ROTATION_*（6/7）而不是单方向 PORTRAIT(1)/
   * LANDSCAPE(2)——后者把朝向钉死在单一方向，「反向也要能用」会被堵死。
   * 调用方（ui/WindowChrome）把它 cast 成 window.Orientation 传 setPreferredOrientation。
   */
  static preferredOrientationCodeOf(orientation: UIOrientation): number {
    return orientation === UIOrientation.PORTRAIT
      ? 6 // window.Orientation.AUTO_ROTATION_PORTRAIT
      : 7; // window.Orientation.AUTO_ROTATION_LANDSCAPE
  }
}
