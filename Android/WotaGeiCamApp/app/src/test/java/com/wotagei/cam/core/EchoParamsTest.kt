package com.wotagei.cam.core

import com.wotagei.cam.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EchoParams] / [echoDecayOf] / [encodeEcho] 的纯 JVM 性质测试（R2 残影，2026-11-05）。
 *
 * 与 [GlowParamsTest] 同族：域钳幂等、坏串整份回默认、编解码往返精确。
 * 独有的一条：**倍数 → 衰减的换算必须单调**——它是"拖影时长 ∝ 倍数"这条观感承诺的全部数学。
 */
class EchoParamsTest {

    // ------------------------------------------------------------------ 域钳

    @Test
    fun `sanitize 倍数出域回落默认2`() {
        assertEquals(2, EchoParams(multiplier = 1).sanitize().multiplier)
        assertEquals(2, EchoParams(multiplier = 0).sanitize().multiplier)
        assertEquals(2, EchoParams(multiplier = 11).sanitize().multiplier)
        assertEquals(2, EchoParams(multiplier = 100).sanitize().multiplier)
        // 域内原样保留（含两端）
        assertEquals(2, EchoParams(multiplier = 2).sanitize().multiplier)
        assertEquals(ECHO_MULTIPLIER_MAX, EchoParams(multiplier = ECHO_MULTIPLIER_MAX).sanitize().multiplier)
    }

    @Test
    fun `sanitize 阈值与强度钳进安全域且幂等`() {
        val p = EchoParams(threshold = 0.1f, gain = 9f)
        val s = p.sanitize()
        assertEquals(ECHO_THRESHOLD_MIN, s.threshold, 0f)
        assertEquals(ECHO_GAIN_MAX, s.gain, 0f)
        assertEquals("域钳幂等", s, s.sanitize())
        // NaN/Infinity 一律回出厂值（坏值漏进 uniform = 整帧看不出原因的发灰）
        assertEquals(0.9f, EchoParams(threshold = Float.NaN).sanitize().threshold, 0f)
        assertEquals(1f, EchoParams(gain = Float.POSITIVE_INFINITY).sanitize().gain, 0f)
    }

    @Test
    fun `默认档是保持亮度且默认关`() {
        // R2 §四.2：KEEP_BRIGHTNESS 是 B 站脚本默认档（星轨叠加法），暗光现场推荐默认；
        // enabled 默认关 = 零代价口径（关着时片元源码逐字不变）
        assertFalse(EchoParams.DEFAULT.enabled)
        assertEquals(EchoMode.KEEP_BRIGHTNESS, EchoParams.DEFAULT.mode)
        assertEquals(2, EchoParams.DEFAULT.multiplier)
    }

    // ------------------------------------------------------------------ 倍数 → 衰减

    @Test
    fun `衰减换算的锚点值`() {
        // k = 0.5^(1/(N-1))：三个手算锚点钉住公式本体，改公式必砸这里
        assertEquals(0.5f, echoDecayOf(2), 1e-6f)
        assertEquals(0.70710678f, echoDecayOf(3), 1e-6f)
        assertEquals(0.79370053f, echoDecayOf(4), 1e-6f)
        assertEquals(0.92587471f, echoDecayOf(10), 1e-6f)
    }

    @Test
    fun `衰减随倍数单调递增且在01开区间`() {
        var prev = 0f
        for (n in ECHO_MULTIPLIER_MIN..ECHO_MULTIPLIER_MAX) {
            val k = echoDecayOf(n)
            assertTrue("k 必须在 (0,1)：N=$n k=$k", k > 0f && k < 1f)
            assertTrue("k 必须随 N 单调递增（拖影时长 ∝ 倍数）：N=$n", k > prev)
            prev = k
        }
    }

    @Test
    fun `域外倍数按2处理不出假档`() {
        // 1 倍 = 没有拖影；不给「1 倍但开着」的假档，关掉才是正解
        assertEquals(echoDecayOf(2), echoDecayOf(1), 0f)
        assertEquals(echoDecayOf(2), echoDecayOf(0), 0f)
        assertEquals(echoDecayOf(2), echoDecayOf(-5), 0f)
    }

    // ------------------------------------------------------------------ 编解码

    @Test
    fun `编解码往返精确`() {
        val p = EchoParams(enabled = true, mode = EchoMode.TRAIL, multiplier = 6, threshold = 0.85f, gain = 1.5f)
        assertEquals(p, decodeEcho(encodeEcho(p)))
        // 默认档也往返（enabled=false 那支）
        assertEquals(EchoParams.DEFAULT, decodeEcho(encodeEcho(EchoParams.DEFAULT)))
    }

    @Test
    fun `坏串整份回默认不猜近似值`() {
        assertEquals(EchoParams.DEFAULT, decodeEcho(null))
        assertEquals(EchoParams.DEFAULT, decodeEcho(""))
        assertEquals(EchoParams.DEFAULT, decodeEcho("enabled=1;mode=谁;multiplier=3"))
        assertEquals(EchoParams.DEFAULT, decodeEcho("enabled=x;mode=TRAIL"))
        assertEquals(EchoParams.DEFAULT, decodeEcho("enabled=1;mode=TRAIL;multiplier=abc"))
        assertEquals(EchoParams.DEFAULT, decodeEcho("enabled=1;mode=TRAIL;multiplier=3;threshold=nan"))
    }

    @Test
    fun `未知键忽略前向兼容`() {
        val raw = encodeEcho(EchoParams(enabled = true, mode = EchoMode.TRAIL, multiplier = 4)) + ";future=42"
        assertEquals(
            EchoParams(enabled = true, mode = EchoMode.TRAIL, multiplier = 4),
            decodeEcho(raw)
        )
    }

    @Test
    fun `出域值解码后落在安全域`() {
        // 手改 prefs 的脏值：能解出来（键全、值是数）但 sanitize 钳回域内
        val decoded = decodeEcho("enabled=1;mode=TRAIL;multiplier=99;threshold=0.1;gain=99")
        assertTrue(decoded.enabled)
        assertEquals(EchoMode.TRAIL, decoded.mode)
        assertEquals(2, decoded.multiplier)
        assertEquals(ECHO_THRESHOLD_MIN, decoded.threshold, 0f)
        assertEquals(ECHO_GAIN_MAX, decoded.gain, 0f)
    }

    // ------------------------------------------------- 拖尾颜色补丁（docs/plan/35）

    @Test
    fun `补丁默认关且色白容差出厂`() {
        // 出厂 = 补丁整体关闭，画面与无补丁逐字节一致（plan/35 E2）
        assertEquals(EchoTrailColorMode.OFF, EchoParams.DEFAULT.trailColorMode)
        assertEquals(ECHO_TRAIL_COLOR_DEFAULT, EchoParams.DEFAULT.trailColor)
        assertEquals(0.3f, EchoParams.DEFAULT.trailTol, 0f)
    }

    @Test
    fun `sanitize 补丁色掩码与容差域钳且幂等`() {
        val dirty = EchoParams(
            mode = EchoMode.TRAIL, trailColorMode = EchoTrailColorMode.ONLY,
            trailColor = -0x100000000001L, trailTol = 9f
        )
        val s = dirty.sanitize()
        assertEquals("脏高位掩掉", ECHO_ARGB_MASK and dirty.trailColor, s.trailColor)
        assertEquals(ECHO_TRAIL_TOL_MAX, s.trailTol, 0f)
        assertEquals(0.3f, EchoParams(trailTol = Float.NaN).sanitize().trailTol, 0f)
        assertEquals(ECHO_TRAIL_TOL_MIN, EchoParams(trailTol = 0f).sanitize().trailTol, 0f)
        assertEquals("域钳幂等", s, s.sanitize())
    }

    @Test
    fun `补丁参数编解码往返精确`() {
        for (m in EchoTrailColorMode.entries) {
            val p = EchoParams(
                enabled = true, mode = EchoMode.TRAIL, multiplier = 6,
                trailColorMode = m, trailColor = 0xFF33CCL, trailTol = 0.45f
            )
            assertEquals("tcm=$m 往返", p, decodeEcho(encodeEcho(p)))
        }
    }

    @Test
    fun `旧串无补丁键解码回补丁出厂`() {
        // v0.0.12-r05 及之前落盘的串没有 tcm/tc/ttol：缺键 = 出厂（前向兼容，不许炸）
        val decoded = decodeEcho("enabled=1;mode=TRAIL;multiplier=4;threshold=0.85;gain=1.0")
        assertEquals(EchoTrailColorMode.OFF, decoded.trailColorMode)
        assertEquals(ECHO_TRAIL_COLOR_DEFAULT, decoded.trailColor)
        assertEquals(0.3f, decoded.trailTol, 0f)
    }

    @Test
    fun `补丁键坏值整串回默认不猜`() {
        assertEquals(EchoParams.DEFAULT, decodeEcho("enabled=1;mode=TRAIL;multiplier=4;threshold=0.9;gain=1.0;tcm=谁"))
        assertEquals(EchoParams.DEFAULT, decodeEcho("enabled=1;mode=TRAIL;multiplier=4;threshold=0.9;gain=1.0;tc=zz"))
        assertEquals(
            EchoParams.DEFAULT,
            decodeEcho("enabled=1;mode=TRAIL;multiplier=4;threshold=0.9;gain=1.0;ttol=abc")
        )
    }

    @Test
    fun `uniform映射_KB档恒0_TRAIL档按补丁模式映射`() {
        // E7 双保险的引擎半边：KB 档无论补丁模式是什么，mask/tint 都必须 0
        for (m in EchoTrailColorMode.entries) {
            val kb = EchoParams(mode = EchoMode.KEEP_BRIGHTNESS, trailColorMode = m)
            assertEquals("KB mask m=$m", 0, echoTrailMaskOf(kb))
            assertEquals("KB tint m=$m", 0, echoTrailTintOf(kb))
        }
        // TRAIL 档：择=1、除=2、其余 mask=0；加=1、减=2、其余 tint=0
        assertEquals(1, echoTrailMaskOf(EchoParams(mode = EchoMode.TRAIL, trailColorMode = EchoTrailColorMode.ONLY)))
        assertEquals(2, echoTrailMaskOf(EchoParams(mode = EchoMode.TRAIL, trailColorMode = EchoTrailColorMode.EXCEPT)))
        assertEquals(0, echoTrailMaskOf(EchoParams(mode = EchoMode.TRAIL, trailColorMode = EchoTrailColorMode.ADD)))
        assertEquals(1, echoTrailTintOf(EchoParams(mode = EchoMode.TRAIL, trailColorMode = EchoTrailColorMode.ADD)))
        assertEquals(2, echoTrailTintOf(EchoParams(mode = EchoMode.TRAIL, trailColorMode = EchoTrailColorMode.SUBTRACT)))
        assertEquals(0, echoTrailTintOf(EchoParams(mode = EchoMode.TRAIL, trailColorMode = EchoTrailColorMode.ONLY)))
        assertEquals(0, echoTrailTintOf(EchoParams(mode = EchoMode.TRAIL, trailColorMode = EchoTrailColorMode.OFF)))
    }

    @Test
    fun `uniform映射_补丁色与容差换算`() {
        val rgb = echoTrailTintRgbOf(EchoParams(trailColor = 0xFFFF0000L))
        assertEquals(1f, rgb[0], 1e-6f)
        assertEquals(0f, rgb[1], 1e-6f)
        assertEquals(0f, rgb[2], 1e-6f)
        assertEquals(1f, echoTrailTintRgbOf(EchoParams.DEFAULT)[0], 1e-6f) // 白 = 中性元
        assertEquals(0.45f, echoTrailTolOf(EchoParams(trailTol = 0.45f)), 1e-6f)
        assertEquals(ECHO_TRAIL_TOL_MIN, echoTrailTolOf(EchoParams(trailTol = -1f)), 1e-6f)
    }

    // ------------------------------------------------------------------ 状态行

    @Test
    fun `状态行优先级DIRECT最高关着不显示停用必可见`() {
        val ok = EchoStatus(available = true, enabled = true, broken = false)
        val broken = EchoStatus(available = false, enabled = true, broken = true)
        assertEquals(R.string.cam_echo_direct, echoStatusNote(ok, true, gpuMode = false))
        assertNull("关着什么都不显示（残留状态都是噪声）", echoStatusNote(ok, enabled = false, gpuMode = true))
        assertNull("引擎未建好不显示", echoStatusNote(null, true, gpuMode = true))
        assertEquals(R.string.cam_echo_broken, echoStatusNote(broken, true, gpuMode = true))
        assertNull("链健康时不显示", echoStatusNote(ok, true, gpuMode = true))
    }
}
