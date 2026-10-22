package com.wotagei.cam

import com.wotagei.cam.core.AudioAlign
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 纯 DSP 对齐核心（core.AudioAlign）：偏移符号、±120ms 恢复精度、静音低置信。
 *
 * 信号用**非周期**短音突发（间隔伪随机）：连续正弦的自相关次峰全在主峰保护窗内，
 * 置信度测不出；等间隔突发又会在 ±周期 处产生真歧义。伪随机间隔两头都避开。
 */
class AudioAlignTest {

    private val sr = 8000

    private fun burstSignal(seconds: Double, seed: Int): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        var pos = sr / 10 + rnd.nextInt(sr / 10)      // 首突发 100..200ms
        while (pos < n - sr / 5) {
            val len = sr / 20 + rnd.nextInt(sr / 25)  // 50..90ms 响段
            val freq = 300.0 + rnd.nextInt(900)
            for (j in 0 until len) {
                val i = pos + j
                if (i >= n) break
                out[i] = (0.8 * sin(2.0 * PI * freq * i / sr)).toFloat()
            }
            pos += len + sr / 5 + rnd.nextInt(sr / 5) // 200..400ms 静音
        }
        return out
    }

    /** 循环平移：shift>0 = 内容晚到（用环形避免首尾补零制造虚假边界峰） */
    private fun shifted(src: FloatArray, shiftSamples: Int): FloatArray {
        val n = src.size
        return FloatArray(n) { i ->
            val j = ((i - shiftSamples) % n + n) % n
            src[j]
        }
    }

    @Test
    fun `内录晚120毫秒_恢复误差不超过2毫秒`() {
        val env = burstSignal(2.0, 11)
        val cap = shifted(env, sr * 120 / 1000)
        val est = AudioAlign.estimateOffsetMs(env, cap, sr)
        assertNotNull("有明确突发的双路信号必须给出估计", est)
        assertTrue("偏移符号：cap 晚到 = 正（实际 ${est!!.offsetMs}）", abs(est.offsetMs - 120) <= 2)
        assertTrue("突发信号主峰应显著（conf=${est.confidence}）", est.confidence > 2.0)
    }

    @Test
    fun `内录早120毫秒_恢复误差不超过2毫秒且为负`() {
        val env = burstSignal(2.0, 29)
        val cap = shifted(env, -sr * 120 / 1000)
        val est = AudioAlign.estimateOffsetMs(env, cap, sr)
        assertNotNull("有明确突发的双路信号必须给出估计", est)
        assertTrue("偏移符号：cap 早到 = 负（实际 ${est!!.offsetMs}）", abs(est.offsetMs + 120) <= 2)
        assertTrue(est.confidence > 2.0)
    }

    @Test
    fun `符号方向_正负60毫秒各自过零轴`() {
        val env = burstSignal(1.5, 7)
        val late = AudioAlign.estimateOffsetMs(env, shifted(env, sr * 60 / 1000), sr)
        val early = AudioAlign.estimateOffsetMs(env, shifted(env, -sr * 60 / 1000), sr)
        assertNotNull(late)
        assertNotNull(early)
        assertTrue("晚到应为正（实际 ${late!!.offsetMs}）", abs(late.offsetMs - 60) <= 2)
        assertTrue("早到应为负（实际 ${early!!.offsetMs}）", abs(early.offsetMs + 60) <= 2)
    }

    @Test
    fun `单路静音_低置信返回null`() {
        val env = burstSignal(1.5, 3)
        assertNull(AudioAlign.estimateOffsetMs(env, FloatArray(env.size), sr))
        assertNull(AudioAlign.estimateOffsetMs(FloatArray(env.size), FloatArray(env.size), sr))
    }

    @Test
    fun `过短输入_不硬凑偏移`() {
        val tiny = FloatArray(sr / 10) { 0.5f }
        assertNull(AudioAlign.estimateOffsetMs(tiny, tiny, sr))
    }

    @Test
    fun `core纯度_不依赖android与player`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("core/AudioAlign.kt"))
        assertFalse("core/AudioAlign 不得 import android.*", src.contains("import android."))
        assertFalse(
            "core/AudioAlign 不得反向依赖 player.*（对比页与预校准共用的是这枚纯 DSP）",
            src.contains("import com.wotagei.cam.player.")
        )
    }
}
