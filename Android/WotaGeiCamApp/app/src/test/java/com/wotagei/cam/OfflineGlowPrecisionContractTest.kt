package com.wotagei.cam

import com.wotagei.cam.camera.Shaders
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅核验真实shader的预处理分支，不模拟浮点/GPU像素；编译链接与像素门仍需GLES设备。 */
class OfflineGlowPrecisionContractTest {
    @Test
    fun `无highp的Glow离线编译单元不包含hash并退化为干净光晕`() {
        assertLowPrecision(Shaders.SCENE_COMPOSITE_FS, echo = false)
    }

    @Test
    fun `无highp的Glow加Echo编译单元不包含hash且保留残影合成`() {
        assertLowPrecision(Shaders.SCENE_COMPOSITE_ECHO_FS, echo = true)
    }

    @Test
    fun `highp离线Glow保留频率hash而不引入变量级highp`() {
        assertHighPrecision(Shaders.SCENE_COMPOSITE_FS)
    }

    @Test
    fun `highp离线Glow加Echo保留频率hash和原合成顺序`() {
        assertHighPrecision(Shaders.SCENE_COMPOSITE_ECHO_FS)
        val unit = preprocess(Shaders.SCENE_COMPOSITE_ECHO_FS, highp = true)
        assertTrue(unit.contains("applyEcho(withGlow, e)"))
    }

    private fun assertLowPrecision(source: String, echo: Boolean) {
        val unit = preprocess(source, highp = false)
        assertFalse("不能仅切换默认精度后继续执行fp16不安全的hash", unit.contains("glowHash"))
        assertTrue("无highp与实时链同口径，频率退化为n=1", unit.contains("float n = 1.0;"))
        assertTrue(unit.contains("g * uTint * (uGain + uAdd) * (uBlend * n)"))
        if (echo) {
            assertTrue(unit.contains("applyEcho(withGlow, e)"))
            assertTrue(unit.contains("e *= (uEchoTintMode < 1.5) ? uEchoTint : (vec3(1.0) - uEchoTint);"))
        }
    }

    private fun assertHighPrecision(source: String) {
        val unit = preprocess(source, highp = true)
        assertTrue(unit.contains("precision highp float;"))
        assertTrue(unit.contains("q += dot(q, q.yzx + 33.33);"))
        assertTrue(unit.contains("step(0.0001, uFreq)"))
        assertFalse("Maleoon已知编译器兼容防线", unit.contains("highp vec3") || unit.contains("highp vec2"))
    }

    private fun preprocess(source: String, highp: Boolean): String {
        val active = ArrayDeque<Boolean>().apply { addLast(true) }
        return buildString {
            for (line in source.lineSequence()) {
                when (line.trim()) {
                    "#ifdef GL_FRAGMENT_PRECISION_HIGH" -> active.addLast(active.last() && highp)
                    "#else" -> {
                        val previous = active.removeLast()
                        active.addLast(active.last() && !previous)
                    }
                    "#endif" -> active.removeLast()
                    else -> if (active.last()) appendLine(line)
                }
            }
            check(active.size == 1) { "shader条件编译块未闭合" }
        }
    }
}
