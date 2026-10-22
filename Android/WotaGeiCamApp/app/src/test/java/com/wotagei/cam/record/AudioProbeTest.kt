package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音量表 dB 换算（[AudioProbe.dbOf]）的纯函数单测（2026-10-16 复核回补）。
 *
 * 口径：`20 × log10(amp / 0.1)`；amp ≤ 0 给下限 -160f（UI 直接显示，不许出 -Infinity/NaN）。
 * 为什么补：逐样本 RMS（旧 `rmsToDb`）退场后，音量表这条唯一在用的换算
 *（`ui/CameraScreen.kt` 的 `volumeDb.value = AudioProbe.dbOf(rec.amplitude())`）全仓无测试锚点。
 * 取整数友好点手写字面量（10 的幂 ⇒ 结果恰为 20 的整数倍），另加一个非整倍点锁公式本体
 *（防退化成"20×整数"的假实现）。
 */
class AudioProbeTest {

    @Test
    fun `dbOf换算_零与负给下限_十的幂恰在20的整数倍_非整倍点锁公式`() {
        assertEquals("amp=0（数字静音）给下限 -160f：UI 直接显示", -160f, AudioProbe.dbOf(0), 0.001f)
        assertEquals("amp<0 同零处理（量程外读数不许算出 -Infinity/NaN 展示）", -160f, AudioProbe.dbOf(-7), 0.001f)
        assertEquals("amp=1：20×log10(1/0.1)=20", 20f, AudioProbe.dbOf(1), 0.001f)
        assertEquals("amp=10：20×log10(10/0.1)=40", 40f, AudioProbe.dbOf(10), 0.001f)
        assertEquals("amp=1000：20×log10(1000/0.1)=80", 80f, AudioProbe.dbOf(1000), 0.001f)
        // 非整倍点：20×log10(2/0.1)=20×log10(20)≈26.0206
        assertEquals("amp=2：20×log10(20)≈26.0206", 26.0206f, AudioProbe.dbOf(2), 0.001f)
    }
}
