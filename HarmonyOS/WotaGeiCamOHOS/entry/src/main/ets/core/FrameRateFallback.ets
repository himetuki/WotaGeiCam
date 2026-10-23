/**
 * fps 退档口径——上游 camera/FrameRateFallback.kt 直译（零平台 import，可直接在 pure-test 跑）。
 *
 * # 为什么拆出来（上游 2026-10-10 S3-S3-01，缺陷背景照抄）
 * 原口径内联在 `Camera2Engine.ceilingFps` 私有方法里，用 `usable > r.lo` 挑范围。对**固定档**
 * `[f,f]` 这个条件恒为 `f > f == false`，于是所有固定档全被排除；LEGACY 设备按契约只报固定档，
 * 「可达上限」必然退到兜底分支 `minOfOrNull { it.hi }`——那是全表**最小**的上界，与函数名/注释
 * 宣称的「最高」相反。用户选 120fps、设备其实能到 60，会被退到 15fps（成片帧率与所选差 8 倍）。
 *
 * 拆出来的另一个理由同族：口径是纯算术，却因为住在持有 `CameraAbility` 的引擎里而零测试覆盖，
 * 改动只能靠真机取证发现。
 *
 * 本侧接线登记：鸿蒙没有 `Camera2Engine`/`syncFpsPick` 这套引擎结构（相机引擎是
 * `camera/GlCameraEngine.ets`，帧率下发走 `RequestApplier.pickFps`），所以本批只交付纯函数
 * 与其全表用例，不伪造调用方。
 */
import { RangeI } from './CameraAbility';

export class FrameRateFallback {
  /**
   * 设备在 [cap] 封顶下**可达的最高帧率**。
   *
   * 口径：**只排除被 cap 截断后不可用的范围**。范围 `[lo,hi]` 截断后可用 ⟺ `min(hi,cap) >= lo`
   * （截断后仍能落在 `[lo,hi]` 里）；固定档 `[f,f]` 在 `f <= cap` 时自然成立，不再被误杀。
   * 返回所有可用范围「截断后上界」的最大值。
   *
   * @param cap 无高帧率能力时的上限（`WotaTiers.HIGH_SPEED_FPS`）；有高帧率能力传
   *        `Number.MAX_SAFE_INTEGER`（上游 `Int.MAX_VALUE` 的本侧等价物）
   * @return 可达上限；**一个可用范围都没有时返回 0**——含义是「无退档目标」，
   *         调用方必须保持总线原值而不是随便退到某个数（旧口径的兜底正是绕开 cap 写回
   *         一个设备给不出的帧率）。
   */
  static ceilingFps(ranges: Array<RangeI>, cap: number): number {
    let best: number = 0;
    for (const r of ranges) {
      const usable: number = Math.min(r.hi, cap);
      if (usable >= r.lo && usable > best) {
        best = usable;
      }
    }
    return best;
  }
}
