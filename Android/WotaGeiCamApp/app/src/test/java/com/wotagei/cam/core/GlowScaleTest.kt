package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Glow 的**尺寸与分档账**（docs/plan/24 R3 §1.4 / §1.2）：`glowScaleFor` / `glowRtSizeInto` /
 * `fillGlowBlurOffset` / `glowRgb`。
 *
 * 这四条都是纯函数，但它们管的正是真机上最难归因的观感缺陷：
 * - 分档错 ⇒ 4K 上链跑满分辨率掉帧，或 720p 上被无谓地糊成一半；
 * - RT 夹取错（逐维独立夹）⇒ 光晕变椭圆（横看扁、竖看长）；
 * - 偏移错（不按短边换算）⇒ 同一组参数在 16:9 与 9:16 上半径不一致；
 * - 颜色位宽错 ⇒ 光晕整条变黑白或纯红。
 */
class GlowScaleTest {

    // ------------------------------------------------------------------ 分档

    @Test
    fun `分档按像素乘帧率落在三档`() {
        // 常见档位（24 §1.4 的表）
        assertEquals("720p30 应走原尺寸链", 1, glowScaleFor(1280, 720, 30))
        assertEquals("1080p30 走半尺寸", 2, glowScaleFor(1920, 1080, 30))
        assertEquals("1080p60 也走半尺寸（124M < 150M）", 2, glowScaleFor(1920, 1080, 60))
        assertEquals("1440p30 走半尺寸（110.6M）", 2, glowScaleFor(2560, 1440, 30))
        assertEquals("4K30 走四分之一（248.8M）", 4, glowScaleFor(3840, 2160, 30))
        assertEquals("1080p120 走四分之一", 4, glowScaleFor(1920, 1080, 120))
    }

    @Test
    fun `两个阈值的边界都在内侧`() {
        // 40M：恰好等于走 1，超过一格就落 2
        assertEquals(1, glowScaleFor(1000, 1000, 40))
        assertEquals(2, glowScaleFor(1000, 1000, 41))
        // 150M：恰好等于走 2（阈值复用 denoiseKernelLite 的 150M，不造第二个魔数）
        assertEquals(2, glowScaleFor(1000, 1000, 150))
        assertEquals(4, glowScaleFor(1000, 1000, 151))
        // 两个常量本身（改阈值必须同时改这里）
        assertEquals(40_000_000L, GLOW_FULL_MAX_PIXEL_RATE)
        assertEquals(150_000_000L, DENOISE_FULL_KERNEL_PIXEL_RATE)
    }

    @Test
    fun `信息不全时分档取最高质量档`() {
        // 还没拿到尺寸/帧率时链本来就不跑，别先猜一个粗档让首帧闪一下
        assertEquals(1, glowScaleFor(0, 0, 30))
        assertEquals(1, glowScaleFor(1920, 0, 30))
        assertEquals(1, glowScaleFor(1920, 1080, 0))
        assertEquals(1, glowScaleFor(-1, -1, -1))
    }

    // ------------------------------------------------------------------ RT 尺寸

    @Test
    fun `RT 尺寸按档缩并保底一像素`() {
        val out = IntArray(2)
        assertTrue(glowRtSizeInto(out, 1920, 1080, 2, 4096))
        assertEquals(960, out[0])
        assertEquals(540, out[1])
        assertTrue(glowRtSizeInto(out, 1920, 1080, 4, 4096))
        assertEquals(480, out[0])
        assertEquals(270, out[1])
        assertTrue(glowRtSizeInto(out, 1920, 1080, 1, 4096))
        assertEquals(1920, out[0])
        assertEquals(1080, out[1])
        // 最小 1：0 边长 RT 必然 FBO 不完整
        assertTrue(glowRtSizeInto(out, 2, 1, 4, 4096))
        assertEquals(1, out[0])
        assertEquals(1, out[1])
        assertTrue(glowRtSizeInto(out, 1, 1, 4, 4096))
        assertEquals(1, out[0])
        assertEquals(1, out[1])
    }

    @Test
    fun `超上限时按最长边等比夹取而不是逐维独立夹`() {
        val out = IntArray(2)
        // 7680×4320 在 maxTexture=4096 上：等比夹到 4096×2304（逐维独立夹会给 4096×4096 = 方形，光晕变椭圆）
        assertTrue(glowRtSizeInto(out, 7680, 4320, 1, 4096))
        assertEquals("最长边必须正好是上限", 4096, max(out[0], out[1]))
        assertEquals(4096, out[0])
        assertEquals(2304, out[1])
        // 宽高比必须原样保留（允许取整误差 1px）
        val srcRatio = 7680.0 / 4320.0
        val rtRatio = out[0].toDouble() / out[1]
        assertTrue("夹取后不许改变宽高比（$srcRatio vs $rtRatio）", abs(srcRatio - rtRatio) < 0.01)
        // 低端机 2048 上限 + 竖屏：同样按最长边夹
        assertTrue(glowRtSizeInto(out, 2160, 3840, 1, 2048))
        assertEquals(1152, out[0])
        assertEquals(2048, out[1])
    }

    @Test
    fun `夹取判据用夹取前的原始最长边`() {
        // 4096×2160 @ scale=1、上限 4096：最长边**正好等于**上限 ⇒ 没夹（拿夹取后尺寸反推会误报）
        assertEquals(4096, glowRawLongestEdge(4096, 2160, 1))
        assertFalse("正好等于上限不算夹取", glowRawLongestEdge(4096, 2160, 1) > 4096)
        val out = IntArray(2)
        assertTrue(glowRtSizeInto(out, 4096, 2160, 1, 4096))
        assertEquals(4096, out[0])
        assertEquals(2160, out[1])
        // 4097 才夹
        assertEquals(4097, glowRawLongestEdge(4097, 2160, 1))
        assertTrue("超过一格必须判为夹取", glowRawLongestEdge(4097, 2160, 1) > 4096)
        assertTrue(glowRtSizeInto(out, 4097, 2160, 1, 4096))
        assertEquals(4096, out[0])
        assertEquals(2159, out[1])
        // scale 参与原始尺寸：8192×2160 @2 → 原始最长 4096（不夹）；@1 → 8192（夹）
        assertEquals(4096, glowRawLongestEdge(8192, 2160, 2))
        assertEquals(8192, glowRawLongestEdge(8192, 2160, 1))
        // 非法输入 → 0（调用方据此判"没有夹"）
        assertEquals(0, glowRawLongestEdge(0, 2160, 1))
        assertEquals(0, glowRawLongestEdge(4096, 2160, 0))
    }

    @Test
    fun `尺寸或档非法返回false且不改调用方数组`() {
        val out = intArrayOf(-7, -7)
        assertFalse(glowRtSizeInto(out, 0, 1080, 1, 4096))
        assertFalse(glowRtSizeInto(out, 1920, 0, 1, 4096))
        assertFalse(glowRtSizeInto(out, 1920, 1080, 0, 4096))
        assertFalse(glowRtSizeInto(out, 1920, 1080, 1, 0))
        assertEquals("非法输入不许写数组", -7, out[0])
        assertEquals(-7, out[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `输出数组不足两位是编程错误`() {
        glowRtSizeInto(IntArray(1), 1920, 1080, 2, 4096)
    }

    // ------------------------------------------------------------------ 模糊偏移

    @Test
    fun `模糊偏移以短边为单位且横纵各向同性`() {
        val out = FloatArray(2)
        // 横屏 2000×1000：短边是 1000
        assertTrue(fillGlowBlurOffset(out, 2000, 1000, 0.02f, horizontal = true))
        val horizontalUv = out[0]
        assertEquals(0.01f, horizontalUv, 1e-6f)
        assertEquals(0f, out[1], 0f)
        assertTrue(fillGlowBlurOffset(out, 2000, 1000, 0.02f, horizontal = false))
        val verticalUv = out[1]
        assertEquals(0f, out[0], 0f)
        assertEquals(0.02f, verticalUv, 1e-6f)
        // 换算回像素：两边都是 20px（0.01 × 2000 = 0.02 × 1000）
        assertEquals(20f, horizontalUv * 2000, 1e-3f)
        assertEquals(20f, verticalUv * 1000, 1e-3f)
    }

    @Test
    fun `竖屏 RT 的偏移也各向同性`() {
        val out = FloatArray(2)
        assertTrue(fillGlowBlurOffset(out, 1000, 2000, 0.02f, horizontal = true))
        val horizontalUv = out[0]
        assertEquals(0.02f, horizontalUv, 1e-6f)
        assertTrue(fillGlowBlurOffset(out, 1000, 2000, 0.02f, horizontal = false))
        val verticalUv = out[1]
        assertEquals(0.01f, verticalUv, 1e-6f)
        // 换算回像素：两边都是 20px（短边 1000）
        assertEquals(20f, horizontalUv * 1000, 1e-3f)
        assertEquals(20f, verticalUv * 2000, 1e-3f)
    }

    @Test
    fun `方形 RT 两维比例都是1`() {
        val out = FloatArray(2)
        assertTrue(fillGlowBlurOffset(out, 512, 512, 0.05f, horizontal = true))
        assertEquals(0.05f, out[0], 1e-6f)
        assertTrue(fillGlowBlurOffset(out, 512, 512, 0.05f, horizontal = false))
        assertEquals(0.05f, out[1], 1e-6f)
    }

    @Test
    fun `偏移非法输入返回false`() {
        val out = floatArrayOf(-1f, -1f)
        assertFalse(fillGlowBlurOffset(out, 0, 1000, 0.02f, horizontal = true))
        assertFalse(fillGlowBlurOffset(out, 1000, 0, 0.02f, horizontal = true))
        assertFalse(fillGlowBlurOffset(out, 1000, 1000, Float.NaN, horizontal = true))
        assertFalse(fillGlowBlurOffset(out, 1000, 1000, -0.1f, horizontal = true))
        assertEquals("非法输入不许写数组", -1f, out[0], 0f)
    }
}
