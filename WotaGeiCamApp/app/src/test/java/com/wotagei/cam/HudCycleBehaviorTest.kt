package com.wotagei.cam

import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.ui.hudCycleStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数胶囊点按循环的行为契约（鸿蒙化第 2 条）。
 *
 * 这里盯的是三件容易做错的事：**AE 锁不能被一次点击悄悄解开**、
 * 走完最后一档的下一拍才回 AUTO（不能顺手把第一档跳过）、
 * 能力缺失（range=null）时要返回 false 让 UI 给提示，而不是假装改了。
 */
class HudCycleBehaviorTest {

    private val nsRange = 4_000_000L..42_000_000L           // 覆盖 1/240 … 1/24 全部档位

    private fun params(): WotaParams = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun aeLockIsNotReleasedByTappingShutter() {
        val p = params()
        p.aeMode.value = AeMode.LOCK
        p.shutter.value = ParamState(40_000_000L, nsRange)
        val before = p.shutter.value.value

        assertFalse("AE 锁定时点快门不该有动作", hudCycleStep(HudItem.SHUTTER, p))
        assertEquals(AeMode.LOCK, p.aeMode.value)
        assertEquals(before, p.shutter.value.value)
    }

    @Test
    fun aeLockAlsoProtectsIso() {
        val p = params()
        p.aeMode.value = AeMode.LOCK
        p.iso.value = ParamState(400, 100..6400)
        assertFalse(hudCycleStep(HudItem.ISO, p))
        assertEquals(AeMode.LOCK, p.aeMode.value)
        assertEquals(400, p.iso.value.value)
    }

    @Test
    fun firstTapFromAutoEntersManualAtSlowestTier() {
        val p = params()
        p.shutter.value = ParamState(40_000_000L, nsRange)

        assertTrue(hudCycleStep(HudItem.SHUTTER, p))
        assertEquals(AeMode.MANUAL, p.aeMode.value)
        assertEquals("第一拍落在最慢档 1/24", 41_666_666L, p.shutter.value.value)
    }

    @Test
    fun lastTierWrapsBackToAutoWithoutSkippingSlowest() {
        val p = params()
        p.aeMode.value = AeMode.MANUAL
        val fastest = WotaTiers.SHUTTER_DENOM.map { WotaTiers.NS_PER_SECOND / it }.sortedDescending().last()
        p.shutter.value = ParamState(fastest, nsRange)

        assertTrue(hudCycleStep(HudItem.SHUTTER, p))
        assertEquals(AeMode.AUTO, p.aeMode.value)
        assertEquals("回 AUTO 这一拍不该把值改走", fastest, p.shutter.value.value)
    }

    @Test
    fun missingCapabilityReportsFailure() {
        val p = params()
        p.shutter.value = ParamState(40_000_000L, null)   // range=null：本机没这项能力
        assertFalse(hudCycleStep(HudItem.SHUTTER, p))
        assertEquals(AeMode.AUTO, p.aeMode.value)

        val q = params()
        q.fps.value = ParamState(25, null)
        assertFalse(hudCycleStep(HudItem.FPS, q))
    }

    @Test
    fun bitrateCycleVisitsEveryTierAndReturnsToStart() {
        val p = params()
        val start = p.bitrate.value
        val seen = linkedSetOf(start)
        repeat(WotaTiers.BITRATES.size) {
            assertTrue(hudCycleStep(HudItem.BITRATE, p))
            seen += p.bitrate.value
        }
        assertEquals(WotaTiers.BITRATES.toSet(), seen)
        assertEquals("整圈按完回到起点", start, p.bitrate.value)
    }
}
