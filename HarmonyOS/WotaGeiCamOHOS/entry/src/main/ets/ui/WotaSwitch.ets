/**
 * 液态开关 WotaSwitch——上游 ui/design/WotaSwitch.kt（f0716fe，Bencho "Liquid toggle"
 * 的 Compose 移植）的 ArkUI 版。
 *
 * 形态口径（上游 2026-10-30 定版）：64×32 轨道、25 球、3.5 内距；**状态由轨道承载反色**
 * （开=品牌蓝轨+白球，关=灰轨）；**拖动过中点即翻转**（不按终点判定）；松手弹簧落位。
 *
 * 平台映射（登记）：上游 Compose 自绘 + Animatable + Velocity 速度继承三处；ArkUI 版 =
 * Row 轨道 + Circle 球 + animateTo(curves.springMotion(response, damping))——**同语义
 * 参数面**（response/dampingFraction，与上游 stiffness=(2π/response)² 自实现同源）；
 * 速度继承不做（ArkUI springMotion 的 initialVelocity 在 animateTo 逐手势场景无入口，
 * 登记平台面）。按压回弹 = PRESS 规格 + pressScale 0.97。
 */
import { curves } from '@kit.ArkUI';
import { LiquidSpring, LiquidSpringSpec } from '../core/LiquidSpring';
import { HudColor, PageColor } from './HudTokens';

@Component
export struct WotaSwitch {
  /** 受控态：开 = 品牌蓝轨 */
  @Prop isOn: boolean = false;
  /** 整行总闸（录制只读弹窗同 PillSlider 的 active 语义） */
  @Prop active: boolean = true;
  onChange: (on: boolean) => void = (_on: boolean): void => {};

  @State private pressScale: number = 1;
  /** 球的渲染位置偏移（0=关位 1=开位；animateTo 弹簧驱动） */
  @State private ballPos: number = 0;
  private dragging: boolean = false;

  private trackWidth: number = 64;
  private trackHeight: number = 32;
  private ball: number = 25;
  private inset: number = 3.5;

  /** 球行程（关位左缘 → 开位左缘） */
  private travel(): number {
    return this.trackWidth - this.ball - 2 * this.inset;
  }

  /**
   * 弹簧到受控态落点（沿行程拉伸非拖尾）：**速度继承**——松手瞬间的手指速度作为初速度
   * 进入弹簧（上游 VelocityScope 语义的 ArkUI 对应面：curves.springCurve(velocity, mass,
   * stiffness, damping)；stiffness=(2π/response)²、c=2ζ√(mass·k)，与 springMotion 同一
   * 参数语义的物理式换算）。velocity=0 时与 springMotion 观感一致。
   */
  private settle(velocityVpPerS: number = 0): void {
    const target: number = this.isOn ? 1 : 0;
    if (this.ballPos === target) {
      return;
    }
    const spec: LiquidSpringSpec = LiquidSpring.OFFSET;
    const curve: ICurve = curves.springCurve(velocityVpPerS, 1,
      LiquidSpring.stiffnessOf(spec.response), LiquidSpring.dampingCoeffOf(spec.response, spec.damping));
    this.getUIContext().animateTo({ duration: 350, curve: curve }, (): void => {
      this.ballPos = target;
    });
  }

  /** 拖动过中点即翻转（不按终点判定）：球当前渲染位置 + 手指增量过轨道中线 */
  private flipAtMidpoint(offsetX: number): void {
    const rendered: number = this.inset + this.ballPos * this.travel() + this.ball / 2 + offsetX;
    const next: boolean = rendered >= this.trackWidth / 2;
    if (next !== this.isOn) {
      this.onChange(next);
    }
  }

  build() {
    Row() {
      Circle({ width: this.ball, height: this.ball })
        .fill(Color.White)
        .shadow({ radius: 4, color: '#33000000', offsetY: 1 })
        .margin({ left: this.inset + this.ballPos * this.travel() })
    }
    .width(this.trackWidth)
    .height(this.trackHeight)
    .borderRadius(this.trackHeight / 2)
    .backgroundColor(this.isOn ? PageColor.ACCENT_BLUE : '#33FFFFFF')
    .scale({ x: this.pressScale, y: this.pressScale })
    .enabled(this.active)
    .gesture(
      PanGesture({ fingers: 1, direction: PanDirection.Horizontal, distance: 2 })
        .onActionStart((_e: GestureEvent) => {
          this.dragging = true;
          this.getUIContext().animateTo(
            { duration: 120, curve: curves.springMotion(LiquidSpring.PRESS.response, LiquidSpring.PRESS.damping) },
            (): void => {
              this.pressScale = LiquidSpring.PRESS_SCALE;
            });
        })
        .onActionUpdate((e: GestureEvent) => {
          this.flipAtMidpoint(e.offsetX);
        })
        .onActionEnd((e: GestureEvent) => {
          this.dragging = false;
          this.getUIContext().animateTo(
            { duration: 120, curve: curves.springMotion(LiquidSpring.PRESS.response, LiquidSpring.PRESS.damping) },
            (): void => {
              this.pressScale = 1;
            });
          // 速度继承：松手速度直接进弹簧（vp/s；方向即拖动方向）
          this.settle(e.velocityX);
        })
        .onActionCancel((e: GestureEvent) => {
          this.dragging = false;
          this.settle(e !== undefined ? e.velocityX : 0);
        })
    )
    .onClick((): void => {
      if (!this.dragging) {
        this.onChange(!this.isOn);
      }
    })
  }
}
