package com.wotagei.cam

import com.wotagei.cam.core.AudioAlign
import com.wotagei.cam.record.AudioPreflightPolicy
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
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

    /**
     * 预校准的**真实输入形态**（record/PreflightMonoPcm 的口径）：两路独立采集、按共同时间原点
     * 落位、空洞补零——**不是**环形平移。env 取源开头的 [windowMs]，cap 取源从 [offsetMs]
     * 处开始的同样长度窗口（offset>0 = 内录内容晚响，窗前的部分是零）。
     *
     * 为什么必须单独一座尺子：环形平移会造出 `±(窗长−真偏移)` 的别名峰，与真实采集的峰结构
     * 完全不同；符号正确性是**输入形状相关**的，只测环形的那几把尺子量不到生产路径。
     */
    private fun preflightPair(
        source: FloatArray,
        windowMs: Int,
        offsetMs: Int
    ): Pair<FloatArray, FloatArray> {
        val w = sr * windowMs / 1000
        val d = sr * offsetMs / 1000
        val env = FloatArray(w) { source.getOrElse(it) { 0f } }
        val cap = FloatArray(w) { i ->
            val j = i - d
            if (j >= 0) source.getOrElse(j) { 0f } else 0f
        }
        return env to cap
    }

    /** 与 record/AudioPreflight 生产调用同口径：8k、搜索 ±600ms、置信度门槛 2.0 */
    private fun preflightEstimate(env: FloatArray, cap: FloatArray) =
        AudioAlign.estimateOffsetMs(
            envPcm = env,
            capPcm = cap,
            sampleRate = AudioAlign.TARGET_SR,
            searchMs = 600,
            minConfidence = AudioPreflightPolicy.MIN_CONFIDENCE
        )

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

    /**
     * 预校准形态（800ms 共同原点窗口）的符号与幅值：这一条钉的是生产路径的形状。
     * 覆盖 ±600ms 的能力边界（AudioPreflightPolicy 的 20..600ms 钳位区间两端）与几个中间档。
     */
    @Test
    fun `预校准形态_800ms共同原点窗口_符号与幅值全对`() {
        val source = burstSignal(3.0, 41)
        for (offsetMs in listOf(-600, -300, -120, -20, 20, 120, 300)) {
            val (env, cap) = preflightPair(source, windowMs = 800, offsetMs = offsetMs)
            val est = preflightEstimate(env, cap)
            assertNotNull("offset=${offsetMs}ms 必须有估计（预校准形态）", est)
            assertTrue(
                "符号：offset=${offsetMs}ms 应原样恢复（实际 ${est!!.offsetMs}ms conf=${est.confidence}）",
                abs(est.offsetMs - offsetMs) <= 2
            )
            assertTrue(
                "预校准形态也要过 2.0 置信门槛（offset=${offsetMs}ms conf=${est.confidence}）",
                est.confidence >= AudioPreflightPolicy.MIN_CONFIDENCE
            )
            // 策略口径：内录早（offset<0）→ shift 为正（延后内录轨）；内录晚 → 不平移。
            // 断言吃**估计值**而非标称值（±2ms 容差内 600 → 599 是正常的）
            val shiftUs = AudioPreflightPolicy.capPreShiftUs(est.offsetMs, est.confidence)
            if (est.offsetMs <= -AudioPreflightPolicy.MIN_PRE_SHIFT_MS) {
                assertEquals(
                    "内录早 ${-est.offsetMs}ms 应延后内录轨",
                    minOf(-est.offsetMs, AudioPreflightPolicy.MAX_PRE_SHIFT_MS) * 1000L,
                    shiftUs
                )
            } else {
                assertEquals("内录不早（${est.offsetMs}ms）不该有预平移", 0L, shiftUs)
            }
        }
    }

    /** 同一形态下内录晚（正偏移）不该产生预平移——那留给录制后的自动校准 */
    @Test
    fun `预校准形态_内录晚响_不平移`() {
        val source = burstSignal(3.0, 43)
        val (env, cap) = preflightPair(source, windowMs = 800, offsetMs = 300)
        val est = preflightEstimate(env, cap)
        assertNotNull(est)
        assertEquals(0L, AudioPreflightPolicy.capPreShiftUs(est!!.offsetMs, est.confidence))
    }

    /** 尺子自证：把「共同原点」换成环形平移，符号尺子必须能红（否则它没在量生产形状） */
    @Test
    fun `尺子自己能红_环形形态与共同原点形态不是同一把尺`() {
        val source = burstSignal(3.0, 47)
        val (env, cap) = preflightPair(source, windowMs = 800, offsetMs = -300)
        val real = preflightEstimate(env, cap)
        assertNotNull("共同原点形态必须先真的绿", real)
        assertEquals(-300, real!!.offsetMs)
        // 环形形态（旧尺子的形状）：同一 800ms 窗下会产生 ±(窗长−真偏移) 的别名峰
        val w = sr * 800 / 1000
        val ring = shifted(source.copyOf(w), -sr * 300 / 1000)
        val ringEst = AudioAlign.estimateOffsetMs(
            envPcm = source.copyOf(w),
            capPcm = ring,
            sampleRate = AudioAlign.TARGET_SR,
            searchMs = 600,
            minConfidence = AudioPreflightPolicy.MIN_CONFIDENCE
        )
        assertTrue(
            "两种形态必须给出可区分的结果，否则新增的尺子没有增量",
            ringEst == null || abs(ringEst.offsetMs + 300) > 2
        )
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
