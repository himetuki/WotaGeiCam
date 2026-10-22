package com.wotagei.cam

import android.media.AudioDeviceInfo
import com.wotagei.cam.player.SelectedTrack
import com.wotagei.cam.player.TrackRule
import com.wotagei.cam.record.AudioPreflightPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sign

/**
 * 蓝牙预校准策略全表（纯函数）：符号、20ms 下限、600ms 上限、双音轨+A2DP 门、后校准 ±1000ms。
 *
 * 「尺子自己能红」：每个表都配一个符号写反/门槛写错的突变体，同一把尺必须报出差异。
 */
class AudioPreflightPolicyTest {

    @Test
    fun `预校准平移全表`() {
        // 低置信：再大的偏移也不动
        assertEquals(0L, AudioPreflightPolicy.capPreShiftUs(-300, 1.99))
        assertEquals(0L, AudioPreflightPolicy.capPreShiftUs(-300, 2.0, minConfidence = 2.5))
        // 内录不早（负数为正延迟 / 低于 20ms）→ 0
        assertEquals(0L, AudioPreflightPolicy.capPreShiftUs(120, 3.0))
        assertEquals(0L, AudioPreflightPolicy.capPreShiftUs(0, 3.0))
        assertEquals(0L, AudioPreflightPolicy.capPreShiftUs(-19, 3.0))
        // 20ms 起效，600ms 封顶
        assertEquals(20_000L, AudioPreflightPolicy.capPreShiftUs(-20, 3.0))
        assertEquals(300_000L, AudioPreflightPolicy.capPreShiftUs(-300, 3.0))
        assertEquals(600_000L, AudioPreflightPolicy.capPreShiftUs(-600, 3.0))
        assertEquals(600_000L, AudioPreflightPolicy.capPreShiftUs(-601, 3.0))
        assertEquals(600_000L, AudioPreflightPolicy.capPreShiftUs(-9_999, 3.0))
    }

    @Test
    fun `预校准与TrackRule符号一致`() {
        // 两个函数都是 shift = −offset：正偏移（内录晚）永不产生正平移；内录早 = 正平移
        for (offset in listOf(-900, -600, -120, -20, -19, 0, 120)) {
            val pre = AudioPreflightPolicy.capPreShiftUs(offset, 3.0)
            val export = TrackRule.shiftUsFor(SelectedTrack.CAP, offset.toLong())
            if (pre != 0L) {
                assertEquals(
                    "offset=$offset 的预校准平移与导出平移必须同号",
                    export.sign.toLong(),
                    pre.sign.toLong()
                )
            }
            // 正 offset（内录已晚）必须明确为 0；负 offset 才允许非负平移。
            // 旧写法 `offset > 0 || pre >= 0` 对正 offset 恒真，测不出"正偏移也预校准"的退化。
            if (offset > 0) {
                assertEquals("正 offset 不许预校准（实际 $pre）", 0L, pre)
            } else {
                assertTrue("负 offset 的预校准平移不得为负（实际 $pre）", pre >= 0L)
            }
        }
        // 环境轨永不平移：导出链的真源；预校准根本不碰环境通道
        assertEquals(0L, TrackRule.shiftUsFor(SelectedTrack.ENV, -900L))
    }

    @Test
    fun `突变_符号写反必被表测出`() {
        // 退化实现：把 −offset 写成 +offset（内录晚反而延后，越纠越错）
        val mutant: (Int, Double) -> Long = { offset, conf ->
            if (conf < AudioPreflightPolicy.MIN_CONFIDENCE) 0L
            else if (offset < AudioPreflightPolicy.MIN_PRE_SHIFT_MS) 0L
            else minOf(offset, AudioPreflightPolicy.MAX_PRE_SHIFT_MS).toLong() * 1000L
        }
        val probes = listOf(-20, -120, -600)
        assertTrue(
            "符号写反的突变体必须在全表上至少一处与真实现不同",
            probes.any { AudioPreflightPolicy.capPreShiftUs(it, 3.0) != mutant(it, 3.0) }
        )
    }

    @Test
    fun `预校准门_双音轨且A2DP`() {
        assertTrue(AudioPreflightPolicy.shouldPreflight(envOn = true, capOn = true, a2dp = true))
        assertFalse(AudioPreflightPolicy.shouldPreflight(false, true, true))
        assertFalse(AudioPreflightPolicy.shouldPreflight(true, false, true))
        assertFalse(AudioPreflightPolicy.shouldPreflight(true, true, false))
        assertFalse(AudioPreflightPolicy.shouldPreflight(false, false, false))
    }

    @Test
    fun `输出设备类型判A2DP`() {
        assertTrue(
            AudioPreflightPolicy.a2dpFromOutputTypes(
                intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            )
        )
        assertFalse(
            AudioPreflightPolicy.a2dpFromOutputTypes(
                intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            )
        )
        assertFalse(AudioPreflightPolicy.a2dpFromOutputTypes(IntArray(0)))
    }

    @Test
    fun `后校准范围与置信门槛`() {
        assertTrue(AudioPreflightPolicy.autoOffsetWithinRange(0, 2.0))
        assertTrue(AudioPreflightPolicy.autoOffsetWithinRange(-1000, 2.0))
        assertTrue(AudioPreflightPolicy.autoOffsetWithinRange(1000, 9.9))
        assertFalse(AudioPreflightPolicy.autoOffsetWithinRange(1001, 9.9))
        assertFalse(AudioPreflightPolicy.autoOffsetWithinRange(-1001, 9.9))
        assertFalse(AudioPreflightPolicy.autoOffsetWithinRange(120, 1.99))
    }
}
