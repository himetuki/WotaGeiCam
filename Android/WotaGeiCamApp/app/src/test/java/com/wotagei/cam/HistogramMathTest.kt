package com.wotagei.cam

import com.wotagei.cam.core.HistogramMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直方图分箱与归一（2026-10-27 曲线框背景直方图）的纯函数全测。
 *
 * 数据路：GL 64×64 RGBA 读回 → [HistogramMath.binRgba] 三通道 64 档 →
 * [HistogramMath.normalizedHeights] 各通道内按最大值归一。两条都是纯函数，JVM 直测。
 */
class HistogramMathTest {

    private fun rgba(r: Int, g: Int, b: Int) = byteArrayOf(
        r.toByte(), g.toByte(), b.toByte(), 0xFF.toByte()
    )

    @Test
    fun `纯色帧落单档_三通道各自计数`() {
        // 全红帧：R 落最高档（255 shr 2 = 63），G/B 落 0 档
        val px = ByteArray(64 * 64 * 4)
        for (i in 0 until 64 * 64) {
            val o = i * 4
            px[o] = 0xFF.toByte(); px[o + 1] = 0; px[o + 2] = 0; px[o + 3] = 0xFF.toByte()
        }
        val bins = HistogramMath.binRgba(px, 64, 64)
        assertEquals(HistogramMath.VALUES, bins.size)
        assertEquals("4096 个红像素全落 R63", 4096, bins[63])
        assertEquals("G0 满计数", 4096, bins[HistogramMath.BINS])
        assertEquals("B0 满计数", 4096, bins[2 * HistogramMath.BINS])
        assertEquals("R 其余档为空", 0, (0 until 63).sumOf { bins[it] })
    }

    @Test
    fun `灰阶梯度均分到各档`() {
        // 0..63 灰阶各出现 4 次（64×64=4096 / 64 档 = 64 次/档？——64 个灰度值各 64 像素）
        val px = ByteArray(64 * 64 * 4)
        var w = 0
        for (y in 0 until 64) for (x in 0 until 64) {
            val v = (((x + y) % 64) shl 2)     // 0,4,8..252 循环 → 每个灰度恰好 64 像素
            px[w++] = v.toByte(); px[w++] = v.toByte(); px[w++] = v.toByte(); px[w++] = 0xFF.toByte()
        }
        val bins = HistogramMath.binRgba(px, 64, 64)
        for (i in 0 until HistogramMath.BINS) {
            assertEquals("灰阶均分：R 每档 64", 64, bins[i])
            assertEquals("G 同", 64, bins[HistogramMath.BINS + i])
            assertEquals("B 同", 64, bins[2 * HistogramMath.BINS + i])
        }
    }

    @Test
    fun `缓冲不足_返回全零而不抛`() {
        val bins = HistogramMath.binRgba(ByteArray(10), 64, 64)
        assertEquals(HistogramMath.VALUES, bins.size)
        assertTrue(bins.all { it == 0 })
    }

    @Test
    fun `归一按各通道最大值_形状保留_全零返回全零`() {
        val counts = IntArray(HistogramMath.VALUES)
        counts[10] = 500               // R 峰在 10
        counts[5] = 250                // R 次峰在 5（峰的一半）
        counts[HistogramMath.BINS + 40] = 1000   // G 峰在 40（计数是 R 的两倍）
        val h = HistogramMath.normalizedHeights(counts)
        assertEquals("R 峰归一后 = 1", 1f, h[10])
        assertEquals("G 峰归一后 = 1（按各自最大）", 1f, h[HistogramMath.BINS + 40])
        assertEquals("R 次峰 250/500 = 0.5", 0.5f, h[5], 1e-6f)
        assertEquals("B 全零通道高度全零", 0f, h[2 * HistogramMath.BINS + 10])
    }

    @Test
    fun `空快照_归一全零`() {
        val h = HistogramMath.normalizedHeights(IntArray(HistogramMath.VALUES))
        assertTrue(h.all { it == 0f })
    }

    // ---------------------------------------------- 第 4 组亮度 Y（29 §2.2，2026-10-28）

    @Test
    fun `四组输出_Y落在基址段`() {
        assertEquals("VALUES = 4×64", 256, HistogramMath.VALUES)
        assertEquals(4, HistogramMath.CHANNELS)
        // 纯白帧：R/G/B 全落 63 档，Y = (54+183+19)*255 shr 10 = 63 档
        val white = ByteArray(64 * 64 * 4)
        for (i in 0 until 64 * 64) {
            val o = i * 4
            white[o] = 0xFF.toByte(); white[o + 1] = 0xFF.toByte(); white[o + 2] = 0xFF.toByte(); white[o + 3] = 0xFF.toByte()
        }
        val bins = HistogramMath.binRgba(white, 64, 64)
        assertEquals(4096, bins[63])
        assertEquals(4096, bins[HistogramMath.BINS + 63])
        assertEquals(4096, bins[2 * HistogramMath.BINS + 63])
        assertEquals("白帧 Y63 满计数", 4096, bins[HistogramMath.LUMA_BASE + 63])
        // 纯黑帧：Y0 满计数
        val blackBins = HistogramMath.binRgba(ByteArray(64 * 64 * 4), 64, 64)
        assertEquals(4096, blackBins[HistogramMath.LUMA_BASE])
    }

    @Test
    fun `纯红纯绿纯蓝的Y落点符合601权重`() {
        fun frame(rr: Int, gg: Int, bb: Int): ByteArray {
            val px = ByteArray(64 * 64 * 4)
            for (i in 0 until 64 * 64) {
                val o = i * 4
                px[o] = rr.toByte(); px[o + 1] = gg.toByte(); px[o + 2] = bb.toByte(); px[o + 3] = 0xFF.toByte()
            }
            return px
        }
        assertEquals("纯红 Y = 54*255 shr 10 = 13（满计数 4096）", 4096,
            HistogramMath.binRgba(frame(255, 0, 0), 64, 64)[HistogramMath.LUMA_BASE + 13])
        assertEquals("纯绿 Y = 183*255 shr 10 = 45", 4096,
            HistogramMath.binRgba(frame(0, 255, 0), 64, 64)[HistogramMath.LUMA_BASE + 45])
        assertEquals("纯蓝 Y = 19*255 shr 10 = 4", 4096,
            HistogramMath.binRgba(frame(0, 0, 255), 64, 64)[HistogramMath.LUMA_BASE + 4])
        assertEquals("灰 128 混色 → Y32", 4096,
            HistogramMath.binRgba(frame(128, 128, 128), 64, 64)[HistogramMath.LUMA_BASE + 32])
    }

    @Test
    fun `Y组归一与其余通道同口径`() {
        val counts = IntArray(HistogramMath.VALUES)
        counts[HistogramMath.LUMA_BASE + 20] = 800
        counts[HistogramMath.LUMA_BASE + 10] = 400
        val h = HistogramMath.normalizedHeights(counts)
        assertEquals("Y 峰归一 = 1", 1f, h[HistogramMath.LUMA_BASE + 20])
        assertEquals("Y 次峰 = 0.5", 0.5f, h[HistogramMath.LUMA_BASE + 10], 1e-6f)
    }
}
