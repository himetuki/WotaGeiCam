/**
 * HUD 胶囊组件（T31 P0 修复）：`@Component + @Prop` 替代原 `@Builder` 按值传 label。
 *
 * 根因（真机取证）：Camera.ets 旧实现用 `@Builder pill(label: string, …)`；@Builder 的值参数
 * 只在首次构建时取值，随后 aria 状态（镜头/画幅/快门…）变化不会重跑 builder —— 真机看到
 * 顶栏已是「广角 / 1080p / +0.0EV」而胶囊仍是「镜头:— / 画幅:— / EV:-」。
 * 本组件把 label/enabled/active 声明为 @Prop：父 @State 一变即触发子组件刷新。
 *
 * 触控/字阶走 HudTokens（最小 48vp；label 11fp/value 13fp 口径的胶囊用 13fp 单行）。
 * 锚点 id 不放在本组件上（自定义组件的 id 解析不可依赖）：调用方用外层 Row 承载
 * id（`pill_shutter` 等）并给 layoutWeight，弹层 getRectangleById 量外层矩形。
 */
import { HudColor, HudShape, HudTouch, HudType } from './HudTokens';

@Component
export struct HudPill {
  @Prop label: string = '';
  /** 门控（不能叫 enabled：与 CustomComponent 的通用属性 enabled 冲突） */
  @Prop pillEnabled: boolean = true;
  /** 选中/激活态（如当前声音侧、开关开） */
  @Prop active: boolean = false;
  onTap: () => void = (): void => {};

  build() {
    Button(this.label)
      .fontSize(HudType.VALUE)
      .fontColor(this.pillEnabled ? HudColor.TEXT_PRIMARY : HudColor.TEXT_DISABLED)
      .width('100%')
      .height(HudTouch.PILL_HEIGHT)
      .borderRadius(HudShape.PILL_RADIUS)
      .backgroundColor(this.active ? HudColor.PILL_BG_ACTIVE
        : (this.pillEnabled ? HudColor.PILL_BG : HudColor.PILL_BG_DISABLED))
      .padding({ left: 10, right: 10 })
      .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
      .enabled(this.pillEnabled)
      .onClick((): void => {
        this.onTap();
      })
  }
}

/**
 * HUD 文本按钮（设置/媒体库/更多等入口）：深色文字胶囊、48vp 高、11fp 字阶说明用 13fp 单行。
 * 与 HudPill 分开是为了让「纯入口」和「带值胶囊」在视觉上区分（入口更暗、无值文案）。
 */
@Component
export struct HudEntryButton {
  @Prop label: string = '';
  @Prop entryEnabled: boolean = true;
  onTap: () => void = (): void => {};

  build() {
    Button(this.label)
      .fontSize(HudType.VALUE)
      .fontColor(this.entryEnabled ? HudColor.TEXT_PRIMARY : HudColor.TEXT_DISABLED)
      .height(HudTouch.BUTTON_HEIGHT)
      .borderRadius(HudShape.PILL_RADIUS)
      .backgroundColor(HudColor.PILL_BG)
      .padding({ left: 12, right: 12 })
      .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
      .enabled(this.entryEnabled)
      .onClick((): void => {
        this.onTap();
      })
  }
}
