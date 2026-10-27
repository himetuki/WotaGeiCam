package com.wotagei.cam.core

/**
 * 直方图分箱与归一（纯函数，JVM 全测）——RGB 曲线框背景的直方图数据路。
 *
 * 采样在 GL 线程（64×64 RGBA 读回），分箱在这里：三通道各 64 档
 * （bin = v shr 2，0..255 → 0..63，与 8bit 域的整除一致，无需浮点）。
 *
 * 口径：**曲线输出后**的画面（当前 program 含曲线/降噪）——弹窗的用途是"边调边看结果"，
 * 输出直方图比输入直方图更贴合（调完 即见分布变化）。要输入口径需另编 identity program，
 * 登记为后续选项，不混在本批。
 */
object HistogramMath {

    const val BINS = 64
    const val CHANNELS = 3
    const val VALUES = BINS * CHANNELS

    /**
     * RGBA(8888) 像素缓冲 → 3×64 档计数（R/G/B 依次排列）。
     * [rgba] 长度必须是 width*height*4；行序无关（直方图不计空间）。
     */
    fun binRgba(rgba: ByteArray, width: Int, height: Int): IntArray {
        val out = IntArray(VALUES)
        val px = width * height
        if (rgba.size < px * 4) return out
        for (i in 0 until px) {
            val o = i * 4
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            out[r shr 2]++                    // R: bins 0..63
            out[BINS + (g shr 2)]++           // G
            out[2 * BINS + (b shr 2)]++       // B
        }
        return out
    }

    /**
     * 渲染用的归一高度（0f..1f，各通道内按各自最大值归一）：直方图看的是**形状**不是绝对计数，
     * 64×64=4096 样本下三通道计数天然不等（画面偏色时 R/G/B 峰高差很大），
     * 共用同一最大值会把小通道压扁。全零（还没采到）返回全 0。
     */
    fun normalizedHeights(counts: IntArray): FloatArray {
        val out = FloatArray(VALUES)
        if (counts.size < VALUES) return out
        for (c in 0 until CHANNELS) {
            var max = 0
            for (i in 0 until BINS) {
                val v = counts[c * BINS + i]
                if (v > max) max = v
            }
            if (max <= 0) continue
            for (i in 0 until BINS) out[c * BINS + i] = counts[c * BINS + i].toFloat() / max
        }
        return out
    }
}
