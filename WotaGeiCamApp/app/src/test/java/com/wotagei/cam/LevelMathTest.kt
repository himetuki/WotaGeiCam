package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

/**
 * 水平仪角度换算单测。回归用例来自真机：竖屏下徽标读出 −97.9°，超出契约的 ±90。
 */
class LevelMathTest {

    private fun assertInRange(deg: Float) {
        assertTrue("读数 $deg 超出 (-90, 90]", deg > -90f && deg <= 90f)
    }

    @Test
    fun `真机竖屏读数不再超出正负90`() {
        // 实测重力分量 (9.07, -0.85, 3.02)，竖屏 displayDegrees=0
        val display = LevelMath.displayVector(9.07f, -0.85f, 0)
        val roll = LevelMath.rollOf(display[0], display[1])
        assertInRange(roll)
        // atan2(-9.07, -0.85) ≈ -95.35°，折回半圆 → +84.65°（机头右边朝下 = 屏幕右侧偏低，正号）
        assertEquals(84.65f, roll, 1.0f)
    }

    @Test
    fun `任意倾角都折进半圆`() {
        var steps = 0
        for (deg in 0..359 step 7) {
            val r = Math.toRadians(deg.toDouble())
            val roll = LevelMath.rollOf((-sin(r)).toFloat(), cos(r).toFloat())
            assertInRange(roll)
            steps++
        }
        assertTrue(steps > 0)
    }

    @Test
    fun `正负90 边界与整圈折叠`() {
        assertEquals(90f, LevelMath.foldToHalfCircle(90f), 0.001f)
        assertEquals(90f, LevelMath.foldToHalfCircle(-90f), 0.001f)
        assertEquals(0f, LevelMath.foldToHalfCircle(180f), 0.001f)
        assertEquals(0f, LevelMath.foldToHalfCircle(-180f), 0.001f)
        assertEquals(30f, LevelMath.foldToHalfCircle(210f), 0.001f)
    }

    @Test
    fun `右侧偏低为正且倒置仍读水平`() {
        // 重力偏向屏幕左 → 右侧偏低 → 正角
        assertTrue(LevelMath.rollOf(-5f, 5f) > 0f)
        // 左侧偏低 → 负角
        assertTrue(LevelMath.rollOf(5f, 5f) < 0f)
        // 相对显示正好倒置：水平仪应读 0（仍然是水平的）
        assertEquals(0f, LevelMath.rollOf(0f, -5f), 0.001f)
    }

    @Test
    fun `显示帧旋转四象限`() {
        // 机头「顶边」受重力（sy>0）：竖屏时是屏幕上方偏低
        assertEquals(0f, LevelMath.displayVector(0f, 5f, 0)[0], 0.001f)
        assertEquals(5f, LevelMath.displayVector(0f, 5f, 0)[1], 0.001f)
        // 横屏 90°：机头顶边重力变成屏幕右侧重力
        assertEquals(-5f, LevelMath.displayVector(0f, 5f, 90)[0], 0.001f)
        assertEquals(0f, LevelMath.displayVector(0f, 5f, 90)[1], 0.001f)
        assertEquals(0f, LevelMath.displayVector(0f, 5f, 180)[0], 0.001f)
        assertEquals(-5f, LevelMath.displayVector(0f, 5f, 180)[1], 0.001f)
        assertEquals(5f, LevelMath.displayVector(0f, 5f, 270)[0], 0.001f)
        assertEquals(0f, LevelMath.displayVector(0f, 5f, 270)[1], 0.001f)
        // 负角与非 90 倍数输入要归一：-270 等价 90
        assertEquals(-5f, LevelMath.displayVector(0f, 5f, -270)[0], 0.001f)
    }

    @Test
    fun `俯仰角语义`() {
        // 平放、镜头朝上：俯角最大
        assertEquals(-90f, LevelMath.pitchOf(0f, 0f, 9.8f), 0.001f)
        // 光轴水平
        assertEquals(0f, LevelMath.pitchOf(0f, 9.8f, 0f), 0.001f)
        // 仰 45°
        assertEquals(45f, LevelMath.pitchOf(0f, 6.93f, -6.93f), 0.5f)
    }
}
