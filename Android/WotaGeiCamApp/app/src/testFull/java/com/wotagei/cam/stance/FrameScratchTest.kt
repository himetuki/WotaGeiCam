package com.wotagei.cam.stance

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B15：分析帧缓冲池 [FrameScratch] 的复用契约（独立复核点名的“无单测钉住复用”）。
 *
 * 只钉“同尺寸/更小尺寸返回同一数组、放大才换新数组”，不重复 YUV 转换的面积计算——
 * 转换本身由 `StanceMath.yuv420ToRgba` 的纯函数测试覆盖。
 */
class FrameScratchTest {

    @Test
    fun `同尺寸重复取用不重新分配`() {
        val s = FrameScratch()
        val a = s.yuv(16, 8, 8)
        val b = s.yuv(16, 8, 8)
        assertSame("Y 平面必须复用", a.first, b.first)
        assertSame("U 平面必须复用", a.second, b.second)
        assertSame("V 平面必须复用", a.third, b.third)
        assertSame("RGBA 必须复用", s.rgba(16 * 16), s.rgba(16 * 16))
        assertSame("旋转输出必须复用", s.rotated(16 * 16), s.rotated(16 * 16))
    }

    @Test
    fun `更小尺寸仍复用同一数组`() {
        val s = FrameScratch()
        val big = s.yuv(32, 16, 16)
        val small = s.yuv(16, 8, 8)
        assertSame(big.first, small.first)
        assertSame(big.second, small.second)
        assertSame(big.third, small.third)
        val rgba = s.rgba(32 * 32)
        assertSame(rgba, s.rgba(16 * 16))
        val rotated = s.rotated(32 * 32)
        assertSame(rotated, s.rotated(16 * 16))
    }

    @Test
    fun `只有放大才换新数组且新数组足够大`() {
        val s = FrameScratch()
        val small = s.yuv(8, 4, 4)
        val big = s.yuv(64, 32, 32)
        assertNotSame(small.first, big.first)
        assertNotSame(small.second, big.second)
        assertNotSame(small.third, big.third)
        assertTrue(big.first.size >= 64)
        assertTrue(big.second.size >= 32)
        assertTrue(big.third.size >= 32)

        val smallRgba = s.rgba(8 * 8)
        val bigRgba = s.rgba(64 * 64)
        assertNotSame(smallRgba, bigRgba)
        assertTrue(bigRgba.size >= 64 * 64)

        val smallRot = s.rotated(8 * 8)
        val bigRot = s.rotated(64 * 64)
        assertNotSame(smallRot, bigRot)
        assertTrue(bigRot.size >= 64 * 64)
    }
}
