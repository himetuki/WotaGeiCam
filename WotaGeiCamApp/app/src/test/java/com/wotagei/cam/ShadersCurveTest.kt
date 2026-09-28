package com.wotagei.cam

import com.wotagei.cam.camera.Shaders
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 着色器源码组成的守卫（JVM，只查字符串，不建 GL 上下文）。
 *
 * 要锁的是「曲线关着的默认态不得引入任何新东西」：直通 program 是链接失败后唯一没退路的兜底路径，
 * 一旦被曲线污染，坏驱动就会连预览一起没有。
 */
class ShadersCurveTest {

    @Test
    fun `直通着色器不含任何曲线痕迹`() {
        assertFalse(Shaders.PASS_THROUGH_FS.contains("uCurve"))
        assertFalse(Shaders.PASS_THROUGH_FS.contains("applyCurve"))
        assertTrue(Shaders.PASS_THROUGH_FS.contains("GL_OES_EGL_image_external"))
    }

    @Test
    fun `关掉曲线时效果着色器不声明取样器`() {
        listOf(Shaders.zebraFragment(false), Shaders.peakingFragment(false)).forEach { src ->
            // 关曲线还声明 uCurve 会让「链接失败就没图」的兜底路径多背一次风险
            assertFalse(src.contains("uCurve"))
            assertFalse(src.contains("applyCurve"))
            // 效果本身的核心 uniform 不能被裁掉
            assertTrue(src.contains("uFrame"))
        }
        assertTrue(Shaders.zebraFragment(true).contains("texture2D(uCurve"))
        assertTrue(Shaders.peakingFragment(true).contains("uniform sampler2D uCurve"))
        assertTrue(Shaders.CURVE_FS.contains("applyCurve"))
    }

    @Test
    fun `每个片元着色器都以扩展声明开头`() {
        val all = listOf(
            Shaders.PASS_THROUGH_FS,
            Shaders.CURVE_FS,
            Shaders.zebraFragment(true),
            Shaders.peakingFragment(true)
        )
        all.forEach { src ->
            val first = src.trimStart().lineSequence().first { it.isNotBlank() }
            assertTrue("首行必须是 #extension：$first", first.startsWith("#extension GL_OES_EGL_image_external"))
        }
    }
}
