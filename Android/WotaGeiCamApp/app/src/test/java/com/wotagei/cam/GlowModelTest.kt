package com.wotagei.cam

import com.wotagei.cam.core.GLOW_COLOR_DEFAULT
import com.wotagei.cam.core.GLOW_THRESHOLD_MAX
import com.wotagei.cam.core.GLOW_THRESHOLD_MIN
import com.wotagei.cam.core.GlowParams
import com.wotagei.cam.core.decodeGlow
import com.wotagei.cam.core.encodeGlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glow 参数模型的两条 2026-10-29 用户口径（纯 JVM）：
 *
 * 1. **亮度阈值域 60~100%**：100% = 只给画面里最亮的像素加光晕（软边在着色器里朝 1.0 收敛，
 *    见 GlowShaderTest）；低端不再允许 0（整帧发光没有意义）。
 * 2. **光晕颜色模式**：关闭 = 亮部发像素自身颜色；开启 = 仅色盘选中颜色。落盘为可选键
 *    `coloronly`（旧串缺键 = 关闭，向后兼容；在但坏值 = 整串回默认，与 color 同一条"坏值不许猜"）。
 */
class GlowModelTest {

    @Test
    fun `阈值域与默认值都在60到100之间`() {
        assertEquals(0.6f, GLOW_THRESHOLD_MIN, 1e-6f)
        assertEquals(1f, GLOW_THRESHOLD_MAX, 1e-6f)
        val d = GlowParams.DEFAULT
        assertTrue("默认阈值必须在域内", d.threshold in GLOW_THRESHOLD_MIN..GLOW_THRESHOLD_MAX)
        assertEquals("0 阈值必须被钳到域下界", 0.6f, GlowParams(threshold = 0f).sanitize().threshold, 1e-6f)
        assertEquals("1.5 阈值必须被钳到域上界", 1f, GlowParams(threshold = 1.5f).sanitize().threshold, 1e-6f)
        assertEquals("NaN 阈值必须回落默认", 0.7f, GlowParams(threshold = Float.NaN).sanitize().threshold, 1e-6f)
    }

    @Test
    fun `颜色模式默认关闭且能往返`() {
        assertFalse("出厂 = 像素自身颜色", GlowParams.DEFAULT.colorOnly)
        val on = GlowParams(enabled = true, colorOnly = true, color = 0xFFFF0000L)
        val back = decodeGlow(encodeGlow(on))
        assertTrue("coloronly 必须落盘并读回", back.colorOnly)
        assertEquals("颜色必须原样", 0xFFFF0000L, back.color)
    }

    @Test
    fun `旧串缺coloronly键按关闭处理`() {
        // 2026-10-28 的旧串（无 coloronly）
        val legacy = "enabled=1;threshold=0.7;soft=0.05;width=0.02;gain=1.0;add=0.0;freq=0.0;blend=0.6;color=FFFFFFFF"
        val p = decodeGlow(legacy)
        assertTrue("旧串必须能读（enabled 生效）", p.enabled)
        assertTrue("缺键 = 关闭（像素自身颜色）", !p.colorOnly)
        assertEquals(GLOW_COLOR_DEFAULT, p.color)
    }

    @Test
    fun `坏coloronly值整串回默认`() {
        val bad = "enabled=1;threshold=0.7;soft=0.05;width=0.02;gain=1.0;add=0.0;freq=0.0;blend=0.6;color=FFFFFFFF;coloronly=yes"
        assertEquals("坏布尔值不许猜，整串回默认", GlowParams.DEFAULT, decodeGlow(bad))
    }

    @Test
    fun `encode必须带coloronly键`() {
        assertTrue(encodeGlow(GlowParams()).contains(";coloronly=0"))
        assertTrue(encodeGlow(GlowParams(colorOnly = true)).contains(";coloronly=1"))
    }
}
