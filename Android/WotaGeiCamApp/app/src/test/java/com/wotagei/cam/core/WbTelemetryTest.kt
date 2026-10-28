package com.wotagei.cam.core

import com.wotagei.cam.camera.RequestApplier
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白平衡取证遥测纯函数层（28 §2.4 批次 1）+ 引擎接线守卫。
 *
 * 这批遥测是 B3（设备标定相对模型）的前置实据：AUTO 档回报增益定「中性在哪」，
 * request vs result 对比定「HAL 是否采纳」，中性点定「CCT 换算走哪条路」。
 * 判读表见 [WbTelemetry] 的 KDoc，标签串就是日志原文，断言必须逐字对上。
 */
class WbTelemetryTest {

    // -------------------------------------------------- ① 格式化（日志原文契约）

    @Test
    fun `增益与矩阵格式化_缺报一律记无`() {
        assertEquals("无", WbTelemetry.formatGains(null))
        assertEquals("无", WbTelemetry.formatGains(floatArrayOf(1f)))
        assertEquals("[1.500,1.000,1.412]", WbTelemetry.formatGains(floatArrayOf(1.5f, 1f, 1.4123f)))
        assertEquals("无", WbTelemetry.formatGainsRatios(null))
        assertEquals("无", WbTelemetry.formatGainsRatios(floatArrayOf(1.5f, 0f, 1.4f)))
        assertEquals("r/g=1.620 b/g=1.410", WbTelemetry.formatGainsRatios(floatArrayOf(1.62f, 1f, 1.41f)))
        assertEquals("无", WbTelemetry.formatNeutralPoint(null))
        assertEquals("[1.600,1.000,1.400]", WbTelemetry.formatNeutralPoint(floatArrayOf(1.6f, 1f, 1.4f)))
    }

    @Test
    fun `CCM摘要对角加首行_identity一眼可辨`() {
        assertEquals("无", WbTelemetry.formatTransform9(null))
        assertEquals("无", WbTelemetry.formatTransform9(FloatArray(8) { 1f }))
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(
            "diag=[1.000,1.000,1.000] row0=[1.000,0.000,0.000]",
            WbTelemetry.formatTransform9(identity)
        )
        assertTrue(WbTelemetry.isIdentityTransform9(identity))
        // 非对角有值 / 对角换位都算「不是 identity」——R2 的判据（identity 抹掉标定 CCM）
        assertFalse(WbTelemetry.isIdentityTransform9(floatArrayOf(1.2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertFalse(WbTelemetry.isIdentityTransform9(floatArrayOf(1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertFalse(WbTelemetry.isIdentityTransform9(null))
    }

    // -------------------------------------------------- ② 判读

    @Test
    fun `auto增益偏离判读_中性在6500K附近不报偏`() {
        assertFalse(WbTelemetry.autoGainsDeviateFromNeutral(null))
        assertFalse(WbTelemetry.autoGainsDeviateFromNeutral(floatArrayOf(1f, 0f, 1f)))
        assertFalse(WbTelemetry.autoGainsDeviateFromNeutral(floatArrayOf(1f, 1f, 1f)))
        // 形状级噪声（±0.03）不报；典型白点偏移（R/G 1.5+）必报
        assertFalse(WbTelemetry.autoGainsDeviateFromNeutral(floatArrayOf(1.03f, 1f, 0.98f)))
        assertTrue(WbTelemetry.autoGainsDeviateFromNeutral(floatArrayOf(1.5f, 1f, 1.4f)))
        assertTrue(WbTelemetry.autoGainsDeviateFromNeutral(floatArrayOf(1.0f, 1f, 1.4f)))
    }

    @Test
    fun `采纳判据_result缺报按未采纳_容差内算采纳`() {
        val request = floatArrayOf(1.5f, 1f, 1.4f)
        assertFalse("result 缺报 = 拿不到实据，按未采纳报告", WbTelemetry.gainsAdopted(request, null))
        assertFalse(WbTelemetry.gainsAdopted(request, floatArrayOf(1.5f, 1f)))
        assertTrue(WbTelemetry.gainsAdopted(request, floatArrayOf(1.5f, 1f, 1.4f)))
        assertTrue("HAL 量化噪声（±0.01）在容差内", WbTelemetry.gainsAdopted(request, floatArrayOf(1.51f, 1f, 1.39f)))
        assertFalse("R 通道差 0.03 已出容差", WbTelemetry.gainsAdopted(request, floatArrayOf(1.53f, 1f, 1.4f)))
    }

    // -------------------------------------------------- ③ 下发侧纯口径

    @Test
    fun `manualGains与下发同源_5500K中性_两端方向正确`() {
        val mid = RequestApplier.manualGains(5500, 0)
        // 模型内 5500K（D55）是中性锚点：三通道相等（2026-10-28 从 6000K 订正过来）
        assertEquals(mid[0], mid[1], 1e-4f)
        assertEquals(mid[1], mid[2], 1e-4f)
        // 色温方向：越高越冷 → R 抬、B 压
        val warm = RequestApplier.manualGains(2000, 0)
        val cold = RequestApplier.manualGains(10000, 0)
        assertTrue("低色温端 B 必须高于 R（补蓝）", warm[2] > warm[0])
        assertTrue("高色温端 R 必须高于 B（补红）", cold[0] > cold[2])
        // tint 方向（改前模型的绝对偏移口径）：+50 = 品红方向 → G 压、R/B 抬
        val magenta = RequestApplier.manualGains(5500, 50)
        assertTrue("tint=+50 必须压 G", magenta[1] < mid[1])
        assertTrue("tint=+50 必须抬 R/B", magenta[0] > mid[0] && magenta[2] > mid[2])
    }

    // -------------------------------------------------- ④ 引擎接线守卫

    @Test
    fun `引擎结果回调必须挂白平衡遥测且走纯函数层`() {
        val code = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt")
        )
        val body = KotlinSourceScan.bodyOf(code, "onCaptureCompleted")
        assertTrue("onCaptureCompleted 必须调 logWbTelemetry（取证是 B3 的前置实据）", body.contains("logWbTelemetry("))
        val tele = KotlinSourceScan.bodyOf(code, "logWbTelemetry")
        // 增益读取抽在 gainsOf 助手（RggbChannelVector → 三元组），遥测体经它取数
        assertTrue("manual 分支必须经 gainsOf 读回报增益", tele.contains("gainsOf("))
        assertTrue("gainsOf 必须读 COLOR_CORRECTION_GAINS",
            KotlinSourceScan.bodyOf(code, "gainsOf").contains("COLOR_CORRECTION_GAINS"))
        assertTrue("采纳判据必须走 WbTelemetry 纯函数（判读口径不许散落引擎）", tele.contains("gainsAdopted("))
        assertTrue(tele.contains("WbTelemetry."))
        // 尺要能红：拆掉挂载点后断言翻红
        assertFalse(body.replace("logWbTelemetry(", "logWbRemoved(").contains("logWbTelemetry("))
    }
}
