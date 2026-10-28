package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlowParams] 的模型层账（docs/plan/24 R3 §1.1 / §1.5；2026-10-28 撤掉预设与色带）。
 *
 * 这一层全是纯函数，所以 JVM 能全测；而它恰恰是"真机上查不出来"的那一类缺陷的所在地：
 * - 坏值漏进 uniform ⇒ 整帧发灰/发白，看不出原因（所以 [GlowParams.sanitize] 必须兜住 NaN）；
 * - 持久化往返丢精度 ⇒ 用户每次启动光晕都"变了一点"（所以浮点用 `Float.toString()` 的精确往返）；
 * - **同色光晕**：光晕的颜色来自亮部提取（原色），参数里不再有 colorA/colorB——
 *   用户 2026-10-28 口径：预设 A/B（品红→青 / 橙→绿）是"变色光晕"，整组删除。
 */
class GlowParamsTest {

    @Test
    fun `出厂默认关且参数逐字段点名`() {
        val d = GlowParams.DEFAULT
        assertEquals("出厂必须关（零代价口径）", false, d.enabled)
        // 逐字段点名：任何一个默认值被"顺手"改掉都要在这里有意识地改
        assertEquals(0.5f, d.threshold)
        assertEquals(0.05f, d.soft)
        assertEquals(0.02f, d.width)
        assertEquals(1.0f, d.gain)
        assertEquals(0.0f, d.add)
        assertEquals(0.0f, d.freq)
        assertEquals(0.6f, d.blend)
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
    fun `编码解码往返逐字段一致`() {
        for (p in listOf(
            GlowParams.DEFAULT,
            GlowParams(enabled = true, threshold = 0.45f, blend = 0.55f),
            GlowParams(enabled = true, threshold = 0.37f, blend = 0.83f, gain = 2.5f, freq = 12f)
        )) {
            assertEquals("encode→decode 必须逐字段往返（$p）", p.sanitize(), decodeGlow(encodeGlow(p)))
        }
        // 开着也要往返（enabled 是开关，不是"是否参与编码"）
        val on = GlowParams.DEFAULT.copy(enabled = true)
        assertTrue(decodeGlow(encodeGlow(on)).enabled)
    }

    @Test
    fun `旧版带颜色的串仍能解码_颜色被忽略`() {
        // 2026-10-28 前的落盘串带 colorA/colorB；用户升级后旧串必须照常读回来（颜色丢弃），
        // 不许整串回默认——否则升级一次光晕参数全丢。
        val legacy = "enabled=1;threshold=0.45;soft=0.05;width=0.02;gain=1.5;add=0.0;" +
            "freq=0.0;blend=0.55;colorA=FFFF730D;colorB=FF33D940"
        val decoded = decodeGlow(legacy)
        assertEquals(true, decoded.enabled)
        assertEquals(0.45f, decoded.threshold)
        assertEquals(0.55f, decoded.blend)
        assertEquals(1.5f, decoded.gain)
        // 新编码串里不再出现颜色键
        assertFalse(encodeGlow(GlowParams.DEFAULT).contains("colorA"))
    }

    @Test
    fun `坏串一律回出厂默认`() {
        val d = GlowParams.DEFAULT
        assertEquals(d, decodeGlow(null))
        assertEquals(d, decodeGlow(""))
        assertEquals(d, decodeGlow("   "))
        assertEquals(d, decodeGlow("garbage"))
        assertEquals(d, decodeGlow("enabled=1")) // 缺键
        // 单键坏：非数 / enabled 既不是 0 也不是 1
        val good = encodeGlow(GlowParams.DEFAULT)
        assertEquals(d, decodeGlow(good.replace("threshold=0.5", "threshold=abc")))
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
    fun `编码前先钳域`() {
        val encoded = encodeGlow(GlowParams(threshold = 9f))
        assertTrue("越界值必须先钳再写盘：$encoded", encoded.contains("threshold=1.0"))
        assertFalse("编码串里不许出现负数或越界写法", encoded.contains("threshold=9.0"))
    }

    @Test
    fun `参数里的开关默认关`() {
        assertFalse(GlowParams.DEFAULT.enabled)
        assertFalse(decodeGlow(encodeGlow(GlowParams.DEFAULT)).enabled)
    }
}
