package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlowParams] 的模型层账（docs/plan/24 R3 §1.1 / §1.5）：出厂值、域钳、单串持久化键的往返与坏串。
 *
 * 这一层全是纯函数，所以 JVM 能全测；而它恰恰是"真机上查不出来"的那一类缺陷的所在地：
 * - 坏值漏进 uniform ⇒ 整帧发灰/发白，看不出原因（所以 [GlowParams.sanitize] 必须兜住 NaN）；
 * - 持久化往返丢精度 ⇒ 用户每次启动光晕都"变了一点"（所以浮点用 `Float.toString()` 的精确往返）；
 * - 颜色位宽搞错 ⇒ 光晕整条变黑白或变纯红（所以 ARGB 掩到 32 位）。
 */
class GlowParamsTest {

    @Test
    fun `出厂默认是预设A且关闭`() {
        val d = GlowParams.DEFAULT
        assertEquals("出厂必须关（零代价口径）", false, d.enabled)
        assertEquals(GlowParams.PRESET_A, d)
        assertEquals("DEFAULT 与 PRESET_A 是同一份参数", GlowParams.PRESET_A, d)
        // 逐字段点名：任何一个默认值被"顺手"改掉都要在这里有意识地改
        assertEquals(0.5f, d.threshold)
        assertEquals(0.05f, d.soft)
        assertEquals(0.02f, d.width)
        assertEquals(1.0f, d.gain)
        assertEquals(0.0f, d.add)
        assertEquals(0.0f, d.freq)
        assertEquals(0.6f, d.blend)
        assertEquals(0xFFFF3399L, d.colorA) // 品红
        assertEquals(0xFF33E6FFL, d.colorB) // 青
    }

    @Test
    fun `预设B只改两端色与强度`() {
        val b = GlowParams.PRESET_B
        assertNotEquals(GlowParams.PRESET_A, b)
        assertEquals(0xFFFF730DL, b.colorA) // 橙
        assertEquals(0xFF33D940L, b.colorB) // 绿
        assertEquals(0.45f, b.threshold)
        assertEquals(0.55f, b.blend)
        assertEquals("预设 B 也必须是关的（预设≠自动开启）", false, b.enabled)
    }

    @Test
    fun `sanitize 把每个维度钳进安全域`() {
        val wild = GlowParams(
            threshold = 9f,
            soft = -1f,
            width = 5f,
            gain = 99f,
            add = -3f,
            freq = 999f,
            blend = 2f
        ).sanitize()
        assertEquals(GLOW_THRESHOLD_MAX, wild.threshold)
        assertEquals(0f, wild.soft)
        assertEquals(GLOW_WIDTH_MAX, wild.width)
        assertEquals(GLOW_GAIN_MAX, wild.gain)
        assertEquals(0f, wild.add)
        assertEquals(GLOW_FREQ_MAX, wild.freq)
        assertEquals(1f, wild.blend)
        // 域内值原样保留（幂等）
        val inRange = GlowParams(threshold = 0.7f, soft = 0.1f, width = 0.05f, gain = 2f, add = 0.3f, freq = 8f, blend = 0.9f)
        assertEquals(inRange, inRange.sanitize())
        assertEquals(inRange, inRange.sanitize().sanitize())
    }

    @Test
    fun `sanitize 把 NaN 与 Infinity 回落到出厂值`() {
        // NaN 漏进 uniform 的表现是"整帧发灰且没有任何报错"——这里必须兜住
        val bad = GlowParams(
            threshold = Float.NaN,
            soft = Float.POSITIVE_INFINITY,
            width = Float.NaN,
            gain = Float.NEGATIVE_INFINITY,
            add = Float.NaN,
            freq = Float.POSITIVE_INFINITY,
            blend = Float.NaN
        ).sanitize()
        assertEquals(0.5f, bad.threshold)
        assertEquals(0.05f, bad.soft)
        assertEquals(0.02f, bad.width)
        assertEquals(1f, bad.gain)
        assertEquals(0f, bad.add)
        assertEquals(0f, bad.freq)
        assertEquals(0.6f, bad.blend)
    }

    @Test
    fun `sanitize 把颜色掩到32位`() {
        // -1L = 全 1 的 64 位；越界位必须被掩掉，否则 encode 出来的十六进制串不是 8 位
        assertEquals(0xFFFFFFFFL, GlowParams(colorA = -1L).sanitize().colorA)
        assertEquals(0xFFFF3399L, GlowParams(colorA = 0x1_FFFF_3399L).sanitize().colorA)
        assertEquals(0xFF000000L, GlowParams(colorB = 0xFF000000L).sanitize().colorB)
    }

    @Test
    fun `编码解码往返逐字段一致`() {
        for (p in listOf(GlowParams.DEFAULT, GlowParams.PRESET_B, GlowParams(enabled = true, threshold = 0.37f, blend = 0.83f))) {
            assertEquals("encode→decode 必须逐字段往返（$p）", p.sanitize(), decodeGlow(encodeGlow(p)))
        }
        // 开着也要往返（enabled 是开关，不是"是否参与编码"）
        val on = GlowParams.PRESET_A.copy(enabled = true)
        assertTrue(decodeGlow(encodeGlow(on)).enabled)
    }

    @Test
    fun `坏串一律回出厂默认`() {
        val d = GlowParams.DEFAULT
        assertEquals(d, decodeGlow(null))
        assertEquals(d, decodeGlow(""))
        assertEquals(d, decodeGlow("   "))
        assertEquals(d, decodeGlow("garbage"))
        assertEquals(d, decodeGlow("enabled=1")) // 缺键
        // 单键坏：非数 / 非十六进制 / enabled 既不是 0 也不是 1
        val good = encodeGlow(GlowParams.DEFAULT)
        assertEquals(d, decodeGlow(good.replace("threshold=0.5", "threshold=abc")))
        assertEquals(d, decodeGlow(good.replace("colorA=FFFF3399", "colorA=ZZZZ3399")))
        assertEquals(d, decodeGlow(good.replace("enabled=0", "enabled=2")))
        assertEquals(d, decodeGlow(good.replace("soft=0.05", "soft=")))
    }

    @Test
    fun `未知键忽略且越界值解码时被钳住`() {
        val good = encodeGlow(GlowParams.DEFAULT)
        // 前向兼容：以后加参数时老版本读新串不许炸
        assertEquals(GlowParams.DEFAULT, decodeGlow("$good;futureParam=7"))
        // 脏串（人手改过 prefs）解码后仍在安全域内
        val clamped = decodeGlow(good.replace("threshold=0.5", "threshold=9").replace("blend=0.6", "blend=-2"))
        assertEquals(GLOW_THRESHOLD_MAX, clamped.threshold)
        assertEquals(0f, clamped.blend)
    }

    @Test
    fun `编码前先钳域且颜色固定八位大写十六进制`() {
        val encoded = encodeGlow(GlowParams(threshold = 9f, colorA = 0xFFFF3399L))
        assertTrue("越界值必须先钳再写盘：$encoded", encoded.contains("threshold=1.0"))
        assertTrue("颜色固定 8 位大写十六进制：$encoded", encoded.contains("colorA=FFFF3399"))
        assertTrue(encoded.contains("colorB=FF33E6FF"))
        assertFalse("编码串里不许出现负数或越界写法", encoded.contains("threshold=9.0"))
    }

    @Test
    fun `参数里的开关默认关且预设不改开关`() {
        assertFalse(GlowParams.PRESET_A.enabled)
        assertFalse(GlowParams.PRESET_B.enabled)
        assertFalse(decodeGlow(encodeGlow(GlowParams.DEFAULT)).enabled)
    }

    @Test
    fun `ARGB 换算只取RGB且忽略alpha`() {
        val magenta = glowRgb(0xFFFF3399L)
        assertEquals(3, magenta.size)
        assertEquals(1.0f, magenta[0], 1e-6f)   // FF
        assertEquals(0.2f, magenta[1], 1e-6f)   // 33
        assertEquals(0.6f, magenta[2], 1e-6f)   // 99
        // alpha 不参与（输出恒不透明，与 PASS_THROUGH_FS 同一条纪律）
        val sameRgbOtherAlpha = glowRgb(0x00FF3399L)
        assertEquals(magenta[0], sameRgbOtherAlpha[0], 0f)
        assertEquals(magenta[1], sameRgbOtherAlpha[1], 0f)
        assertEquals(magenta[2], sameRgbOtherAlpha[2], 0f)
        assertEquals(0f, glowRgb(0xFF000000L)[0], 0f)
        assertEquals(1f, glowRgb(0xFFFFFFFFL)[0], 1e-6f)
        // 预设两端色换算出来的正好是品红/青（避免"色值写反"无人发现）
        val presetA = glowRgb(GlowParams.PRESET_A.colorA)
        assertEquals(1f, presetA[0], 1e-6f)
        assertEquals(0.2f, presetA[1], 1e-6f)
        assertEquals(0.6f, presetA[2], 1e-6f)
        val cyan = glowRgb(GlowParams.PRESET_A.colorB)
        assertEquals(51f / 255f, cyan[0], 1e-6f)
        assertEquals(230f / 255f, cyan[1], 1e-6f)
        assertEquals(1f, cyan[2], 1e-6f)
    }
}
