/**
 * 「更多」胶囊面板（T31）：闪光/效果/光弧/网格——第二排主胶囊之外的低频开关收进这里，
 * 避免 12 颗胶囊挤单行（真机取证：仅 6 颗可见、30vp 触控）。
 *
 * 复用 PillPopup 的遮罩/量框基础设施（WotaPillPopup：全屏遮罩点击关 + 量框后按锚点定位），
 * 内容为 4 行开关；行内按钮 48vp、13fp 字阶，当前值以强调色高亮。
 * 门控：光弧仅 24/25fps 与未录制时可选（录制中禁用）；其余三项录制中可改（沿用旧胶囊行为）。
 */
import { HudColor, HudShape, HudTouch, HudType } from './HudTokens';
import { PillRect, WotaPillPopup } from './PillPopup';

@Component
export struct HudMoreSheet {
  anchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  @Prop flashOn: boolean = false;
  @Prop effectLabel: string = '';
  @Prop arcVisible: boolean = false;
  @Prop arcLabel: string = '';
  @Prop arcEnabled: boolean = true;
  @Prop gridOn: boolean = true;
  onToggleFlash: () => void = (): void => {};
  onCycleEffect: () => void = (): void => {};
  onCycleArc: () => void = (): void => {};
  onToggleGrid: () => void = (): void => {};
  onClose: () => void = (): void => {};

  build() {
    WotaPillPopup({
      anchor: this.anchor,
      panelTitle: '更多',
      onDismiss: (): void => {
        this.onClose();
      }
    }) {
      // 行内联（不用按值 @Builder：值参数会在首帧冻结，开关文案不随 @Prop 刷新）
      Row({ space: HudSpaceGap.M }) {
        Text('闪光')
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_SECONDARY)
          .width(56)
        Button(this.flashOn ? '常亮' : '关')
          .fontSize(HudType.VALUE)
          .fontColor(HudColor.TEXT_PRIMARY)
          .height(HudTouch.PILL_HEIGHT)
          .borderRadius(HudShape.PILL_RADIUS)
          .backgroundColor(this.flashOn ? HudColor.PILL_BG_ACTIVE : HudColor.PILL_BG)
          .padding({ left: 14, right: 14 })
          .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
          .onClick((): void => {
            this.onToggleFlash();
          })
        Blank()
      }
      .width('100%')

      Row({ space: HudSpaceGap.M }) {
        Text('效果')
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_SECONDARY)
          .width(56)
        Button(this.effectLabel)
          .fontSize(HudType.VALUE)
          .fontColor(HudColor.TEXT_PRIMARY)
          .height(HudTouch.PILL_HEIGHT)
          .borderRadius(HudShape.PILL_RADIUS)
          .backgroundColor(this.effectLabel !== '直通' ? HudColor.PILL_BG_ACTIVE : HudColor.PILL_BG)
          .padding({ left: 14, right: 14 })
          .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
          .onClick((): void => {
            this.onCycleEffect();
          })
        Blank()
      }
      .width('100%')

      Row({ space: HudSpaceGap.M }) {
        Text('光弧')
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_SECONDARY)
          .width(56)
        Button(this.arcVisible ? this.arcLabel : '仅 24/25fps')
          .fontSize(HudType.VALUE)
          .fontColor((this.arcVisible && this.arcEnabled)
            ? HudColor.TEXT_PRIMARY : HudColor.TEXT_DISABLED)
          .height(HudTouch.PILL_HEIGHT)
          .borderRadius(HudShape.PILL_RADIUS)
          .backgroundColor(this.arcLabel !== '' && this.arcVisible
            ? HudColor.PILL_BG_ACTIVE : HudColor.PILL_BG)
          .padding({ left: 14, right: 14 })
          .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
          .enabled(this.arcVisible && this.arcEnabled)
          .onClick((): void => {
            this.onCycleArc();
          })
        Blank()
      }
      .width('100%')

      Row({ space: HudSpaceGap.M }) {
        Text('网格')
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_SECONDARY)
          .width(56)
        Button(this.gridOn ? '开' : '关')
          .fontSize(HudType.VALUE)
          .fontColor(HudColor.TEXT_PRIMARY)
          .height(HudTouch.PILL_HEIGHT)
          .borderRadius(HudShape.PILL_RADIUS)
          .backgroundColor(this.gridOn ? HudColor.PILL_BG_ACTIVE : HudColor.PILL_BG)
          .padding({ left: 14, right: 14 })
          .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
          .onClick((): void => {
            this.onToggleGrid();
          })
        Blank()
      }
      .width('100%')
    }
  }
}

/** 行内水平间距（vp）：单独常量避免与 HudSpace 名字冲突的阅读歧义 */
class HudSpaceGap {
  static readonly M: number = 8;
}
