/**
 * CPU 路线的整像素选择算子（直译上游 record/ArcRepairPlan.kt 的 ArcRepairMerge，对齐上游 38d712a）。
 *
 * 判据：`Δluma = Y_b − Y_a`；块内取 4 个 Y 的最大 Δ（2~4px 细弧不被块平均吃掉），`maxΔ > D`
 * （**严格大于**）时整块（4 个 Y + 1 个 U + 1 个 V）取自 b，否则整块保持 a。死区 D 经
 * core/Constants.ets 的 ArcMergeDeadband.clamp 归一到 0..32。
 *
 * **绝不 Y 逐点选、U/V 按块选**——跨帧拼合会造色。旧口径「CPU 按 YUV 逐平面取大」会把两个
 * 色相拼成原片不存在的颜色（极橙 + 纯黑 → 珊瑚粉 (255,109,158)，蓝通道凭空 +158），已废弃。
 * 与 GPU 路 `ARC_MERGE_FS`（shaders.h）同一规则：输出每个像素严格等于某一输入帧的原值。
 *
 * 量纲（刻意近似）：Δ 直接来自解码器原始 Y（limited range 16..235、HD 常 BT.709），GPU 路是
 * full-range RGB 的 BT.601 luma；同一 D 在两路的有效门槛约差 1.16 倍（见 ArcRepairPlan 头注）。
 *
 * a 就地更新：输出写回 aY/aU/aV。a 与 b 必须是不同数组；调用方负责保证 width/height 为偶数
 * （生产路径在 allocPlanes 里先验，本函数对奇数宽高显式抛错而不是静默截断）。
 *
 * 纯逻辑（不 import 平台 Kit）；执行器（解码/编码/EGL）留 T29。
 */
import { ArcMergeDeadband } from '../core/Constants';

export class ArcRepairMerge {
  /**
   * a = select(a, b)，按 2×2 块整体决策；共 (width/2)*(height/2) 个色度样本。
   * Uint8Array 存储：下标读取天然无符号（等价上游 `byte and 0xFF`）。
   * @throws Error 宽高非正/非偶数或平面长度不足（防御性断言，生产路径先验过）
   */
  static selectInPlace(
    aY: Uint8Array, aU: Uint8Array, aV: Uint8Array,
    bY: Uint8Array, bU: Uint8Array, bV: Uint8Array,
    width: number, height: number, deadband: number
  ): void {
    if (!(width > 0 && height > 0 && width % 2 === 0 && height % 2 === 0)) {
      throw new Error(`4:2:0 整像素选择要求正偶数宽高，实际 ${width}x${height}`);
    }
    const cw: number = width / 2;
    const ch: number = height / 2;
    if (aY.length < width * height || bY.length < width * height) {
      throw new Error('Y 平面长度不足');
    }
    if (aU.length < cw * ch || aV.length < cw * ch) {
      throw new Error('a 色度平面长度不足');
    }
    if (bU.length < cw * ch || bV.length < cw * ch) {
      throw new Error('b 色度平面长度不足');
    }
    const d: number = Math.trunc(ArcMergeDeadband.clamp(deadband));

    let c: number = 0;
    let rowStart: number = 0;
    for (let by: number = 0; by < ch; by++) {
      let i: number = rowStart;
      for (let bx: number = 0; bx < cw; bx++) {
        const i1: number = i + 1;
        const i2: number = i + width;
        const i3: number = i2 + 1;
        const delta: number = Math.max(
          bY[i] - aY[i],
          bY[i1] - aY[i1],
          bY[i2] - aY[i2],
          bY[i3] - aY[i3]
        );
        if (delta > d) {
          // 整块同帧搬运：4Y + U + V 一起取自 b，杜绝跨帧拼合造色
          aY[i] = bY[i];
          aY[i1] = bY[i1];
          aY[i2] = bY[i2];
          aY[i3] = bY[i3];
          aU[c] = bU[c];
          aV[c] = bV[c];
        }
        i += 2;
        c++;
      }
      rowStart += width * 2;
    }
  }
}
