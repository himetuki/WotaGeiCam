package com.wotagei.cam

import com.wotagei.cam.player.AudioSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 对比页音频同步的 **DSP 纯函数**覆盖（`AudioSync` 全部 internal 函数，JVM 直跑）。
 *
 * 信号设计要点：refine 的置信度 = 主峰/次峰（次峰取主峰 ±150ms 之外），所以精修用例
 * 必须用**短突发**而不是连续正弦——连续音的互相关以音周期（2ms@500Hz）自相关，次峰
 * 全在保护窗内，置信度恒 ≈1，测不出"置信度随峰锐度变化"这件它该测的事。
 */
class AudioSyncTest {

    // region 重采样

    @Test
    fun `同源采样率直接拷贝_常量样样保持`() {
        val src = floatArrayOf(0.5f, 0.25f, 1f, 0.75f)
        val out = AudioSync.resample(src, 8000, 8000, 10)
        assertEquals(src.size, out.size)
        for (i in src.indices) assertEquals(src[i], out[i], 1e-6f)
    }

    @Test
    fun `降采样长度减半_常量电平不漂`() {
        val src = FloatArray(48_000) { 0.5f }          // 1s @48k
        val out = AudioSync.resample(src, 48_000, 24_000, 10)
        assertEquals(24_000, out.size)
        out.forEach { assertEquals(0.5f, it, 1e-6f) }  // 线性插值对常量无纹波
    }

    @Test
    fun `maxSeconds截断输出时长`() {
        val src = FloatArray(48_000) { 0.5f }          // 1s @48k
        val out = AudioSync.resample(src, 48_000, 24_000, 1)   // 只取 1s@24k
        assertEquals(24_000, out.size)
    }

    // endregion

    // region 包络

    @Test
    fun `包络_响段归一到1_静段归零`() {
        // 2s @8k：前 1s 0.5 幅度、后 1s 静音 → 10ms 窗共 200 点。
        // 归一化按**包络最大值**（0.25）做：响段 0.25/0.25 = 1.0，静段低于均值钳 0
        val pcm = FloatArray(16_000) { if (it < 8_000) 0.5f else 0f }
        val env = AudioSync.envelope(pcm)
        assertEquals(200, env.size)
        assertEquals(1f, env[50], 1e-4f)
        assertEquals(0f, env[150], 1e-6f)
    }

    @Test
    fun `全静音包络不除零_全零返回`() {
        val env = AudioSync.envelope(FloatArray(16_000))
        assertEquals(200, env.size)
        env.forEach { assertEquals(0f, it, 1e-6f) }
    }

    // endregion

    // region 粗对齐

    @Test
    fun `包络延迟3步_粗对齐返回正30ms`() {
        // 口径：left(t) ≈ right(t + offset)，offset 正 = 右片晚响
        val envL = FloatArray(300) { if (it in 100..130) 1f else 0f }
        val envR = FloatArray(300) { if (it >= 3) envL[it - 3] else 0f }   // 右片晚 3 步 = 30ms
        assertEquals(30L, AudioSync.coarseAlign(envL, envR))
    }

    @Test
    fun `右片早响_粗对齐返回负偏移`() {
        val envR = FloatArray(300) { if (it in 100..130) 1f else 0f }
        val envL = FloatArray(300) { if (it >= 3) envR[it - 3] else 0f }   // 左片晚 3 步
        assertEquals(-30L, AudioSync.coarseAlign(envL, envR))
    }

    // endregion

    // region FFT 精修与往返

    @Test
    fun `fft逆变换往返_随机信号复原`() {
        // 本实现的 1/n 折在调用点（refine 里 `reL[it] / n` 即契约）：逆变换后需除以 n 才复原
        val n = 1024
        val rnd = Random(7)                                    // 种子固定：失败可复现（非加密用途）
        val re = FloatArray(n) { rnd.nextFloat() - 0.5f }
        val im = FloatArray(n)
        val origRe = re.copyOf()
        AudioSync.fft(re, im)
        AudioSync.inverseFft(re, im)
        for (i in 0 until n) {
            assertEquals(origRe[i], re[i] / n, 1e-3f)
            assertEquals(0f, im[i] / n, 1e-3f)
        }
    }

    @Test
    fun `常量信号的fft只有直流分量`() {
        val n = 256
        val re = FloatArray(n) { 1f }
        val im = FloatArray(n)
        AudioSync.fft(re, im)
        assertEquals(n.toFloat(), re[0], 1e-2f)
        assertEquals(0f, im[0], 1e-2f)
        for (k in 1 until n) {
            assertEquals("bin $k 实部", 0f, re[k], 1e-2f)
            assertEquals("bin $k 虚部", 0f, im[k], 1e-2f)
        }
    }

    @Test
    fun `精修_短突发延迟5ms_返回正5毫秒且高置信`() {
        val sr = 8000
        val n = 4000                                           // 0.5s @8k
        val left = FloatArray(n) { i ->
            if (i in 400 until 1200) (sin(2.0 * PI * 500.0 * i / sr) * 0.8f).toFloat() else 0f
        }
        val delaySamples = 40                                  // 5ms
        val right = FloatArray(n) { i -> if (i >= delaySamples) left[i - delaySamples] else 0f }
        val (ms, conf) = AudioSync.refine(left, right, coarseMs = 0L)
        assertTrue("精修应落在 +5ms 附近（实际 $ms）", ms in 4..6)
        assertTrue("突发互相关主峰应显著（conf=$conf）", conf > 2f)
    }

    // endregion
}
