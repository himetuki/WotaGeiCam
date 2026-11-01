/**
 * 直方图分箱与归一（纯函数，全表单测）——RGB 曲线框背景的直方图数据路。
 * 直译自上游 core/HistogramMath.kt（f31d18c）。
 *
 * 采样在 GL 线程（64×64 RGBA 读回），分箱在 native（cpp/gl_render.cpp 的
 * BinHistogramRgba 与本文件 binRgba **同式镜像**——契约在两处出现，接线守卫钉住），
 * 归一/绘制在本侧 UI：R/G/B/亮度 Y 各 64 档（bin = v >> 2，0..255 → 0..63，与 8bit 域的整除一致）。
 *
 * 第 4 组亮度 Y（29 §2.2 F2，按通道区分的直方图）：「白（master）」通道不再三色混排，
 * 画自己的 Y 直方图（Rec.601 整数权重 54/183/19，移位代替乘浮点，CPU 每帧多 4096 次乘加
 * 可忽略；GL 读回不变）。旧 3 组消费方按「前 3×64 仍是 R/G/B」兼容。
 *
 * 口径：**曲线输出后**的画面（当前 program 含曲线）——弹窗的用途是「边调边看结果」，
 * 输出直方图比输入直方图更贴合（调完即见分布变化）。要输入口径需另编 identity program，
 * 登记为后续选项，不混在本批。
 */

export class HistogramMath {
  static readonly BINS: number = 64;
  static readonly CHANNELS: number = 4;
  static readonly VALUES: number = HistogramMath.BINS * HistogramMath.CHANNELS;
  /** master（白）组在 binRgba 输出里的基址：R=0、G=[BINS]、B=2×[BINS]、Y=3×[BINS] */
  static readonly LUMA_BASE: number = 3 * HistogramMath.BINS;

  /**
   * RGBA(8888) 像素缓冲 → 4×64 档计数（R/G/B/Y 依次排列）。
   * rgba 长度必须是 width*height*4；行序无关（直方图不计空间）。
   * （native 侧 C++ 镜像实现同一公式，见 gl_render.cpp BinHistogramRgba——像素住在 GL 线程，
   * 16KB 读回后近地分箱再传 256 个 int，跨 napi 的带宽是 1/85。）
   */
  static binRgba(rgba: ArrayBuffer, width: number, height: number): Array<number> {
    const out: Array<number> = new Array<number>(HistogramMath.VALUES).fill(0);
    const px: number = width * height;
    if (rgba.byteLength < px * 4) {
      return out;
    }
    const bytes: Uint8Array = new Uint8Array(rgba);
    for (let i = 0; i < px; i++) {
      const o: number = i * 4;
      out[bytes[o] >> 2]++;
      out[HistogramMath.BINS + (bytes[o + 1] >> 2)]++;
      out[2 * HistogramMath.BINS + (bytes[o + 2] >> 2)]++;
      // Y：Rec.601 整数权重（54/183/19），>>8 归一后再 >>2 落档 = >>10（上游 320a956 同式）
      out[HistogramMath.LUMA_BASE + ((54 * bytes[o] + 183 * bytes[o + 1] + 19 * bytes[o + 2]) >> 2 >> 8)]++;
    }
    return out;
  }

  /**
   * 渲染用的归一高度（0..1，各通道内按各自最大值归一）：直方图看的是**形状**不是绝对计数，
   * 64×64=4096 样本下四通道计数天然不等（画面偏色时 R/G/B 峰高差很大），
   * 共用同一最大值会把小通道压扁。全零（还没采到）返回全 0。
   */
  static normalizedHeights(counts: Array<number>): Array<number> {
    const out: Array<number> = new Array<number>(HistogramMath.VALUES).fill(0);
    if (counts.length < HistogramMath.VALUES) {
      return out;
    }
    for (let c = 0; c < HistogramMath.CHANNELS; c++) {
      let max: number = 0;
      for (let i = 0; i < HistogramMath.BINS; i++) {
        const v: number = counts[c * HistogramMath.BINS + i];
        if (v > max) {
          max = v;
        }
      }
      if (max <= 0) {
        continue;
      }
      for (let i = 0; i < HistogramMath.BINS; i++) {
        out[c * HistogramMath.BINS + i] = counts[c * HistogramMath.BINS + i] / max;
      }
    }
    return out;
  }
}
