package com.wotagei.cam

import com.wotagei.cam.ui.zoomQuickTiers
import com.wotagei.cam.ui.zoomRailTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖排变焦快捷档的取档规则（鸿蒙化第 3 条）：只保留**运行时变焦范围里存在**的那些档。
 * 范围由 `CameraCharacteristics` 换算进参数总线，所以这里喂不同范围就能锁住行为，
 * 不需要真机，也不给任何机型硬编码留口子。
 */
class ZoomTiersTest {

    @Test
    fun ultraWideCapablePhoneGetsHalfTier() {
        // 超广角端 0.6× 起步的机器：0.5× 不在范围内，不该出现（点了也设不进去）
        val tiers = zoomQuickTiers(0.6f, 10f)
        assertFalse(tiers.contains(0.5f))
        assertEquals(listOf(1f, 2f, 3f, 4f, 6f, 10f), tiers)
    }

    @Test
    fun phoneWithTrueHalfXShowsIt() {
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 4f), zoomQuickTiers(0.5f, 4f))
    }

    @Test
    fun digitalOnlyLensKeepsWhatFallsInside() {
        // 长焦端 2×–6×：只有 2/3/4/6 有意义
        assertEquals(listOf(2f, 3f, 4f, 6f), zoomQuickTiers(2f, 6f))
    }

    @Test
    fun fixedLensHasNoQuickTier() {
        // 定焦（范围退化成 1..1）时只剩 1×，右栏那段竖排因此不组合（size > 1 才画）
        val tiers = zoomQuickTiers(1f, 1f)
        assertEquals(listOf(1f), tiers)
        assertTrue(tiers.size <= 1)
    }

    @Test
    fun railShowsOnlyTheNearestFourTiers() {
        val all = listOf(0.5f, 1f, 2f, 3f, 4f, 6f, 10f)
        // 右栏不滚动、横屏只有约 360dp 高：铺满 7 档会被裁掉尾巴，所以只留离当前值最近的 4 档
        assertEquals(listOf(0.5f, 1f, 2f, 3f), zoomRailTiers(all, 1f))
        assertEquals(listOf(3f, 4f, 6f, 10f), zoomRailTiers(all, 10f))
        assertTrue(zoomRailTiers(all, 2f).size <= 4)
        // 长按面板里的完整档位不受影响，这里只是右栏的显示子集
        assertEquals(all, zoomQuickTiers(0.5f, 10f))
    }
}
