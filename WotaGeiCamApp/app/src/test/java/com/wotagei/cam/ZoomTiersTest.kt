package com.wotagei.cam

import com.wotagei.cam.ui.nextInCycle
import com.wotagei.cam.ui.zoomPanelTiers
import com.wotagei.cam.ui.zoomQuickTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变焦「点按循环取值」的快捷档取档规则：只保留**运行时变焦范围里存在**的那些档。
 * 范围由 `CameraCharacteristics` 换算进参数总线，所以这里喂不同范围就能锁住行为，
 * 不需要真机，也不给任何机型硬编码留口子。
 *
 * 竖排快速变焦档已按六项第 1 条删除（`zoomRailTiers` 随之消失）。剩下的两个消费方——点按循环与
 * 长按面板——必须读同一张表（`zoomPanelTiers` 只是 `zoomQuickTiers` 的具名转发），由
 * [panelAndCycleShareOneLadder] 钉住。
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
        // 定焦（范围退化 1..1）时只剩 1×。§58 时期这里配的是 `size > 1 才画竖排` 的守卫
        // （防"竖排只剩一颗孤零零"），六项第 1 条把竖排整段删掉后那颗守卫没有对象了。
        // 现在这两个消费方各由什么保证：面板 `quick.isNotEmpty()` 才组合、且只有一颗 1.0× 的档
        // 是"当前值"而不是空壳；点按循环走满一圈仍停在同一档、不会跳到范围外的档。下面两条把这两点钉住。
        val tiers = zoomQuickTiers(1f, 1f)
        assertEquals(listOf(1f), tiers)
        assertTrue(tiers.size <= 1)
        assertEquals(1f, nextInCycle(tiers, 1f))
    }

    @Test
    fun fullLadderIsInsideAWideRangeLens() {
        // 范围给足 0.5×–10× 时整支梯子都在。长按面板与点按循环读的是同一张表（见下一条），
        // 面板里不再另有一份不含 0.5× 的内联档；连续倍率靠面板里的滑杆覆盖。
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 4f, 6f, 10f), zoomQuickTiers(0.5f, 10f))
    }

    @Test
    fun panelAndCycleShareOneLadder() {
        // 审查 S3-6 的回归闸：`CameraPills.ZoomPill` 那张快捷档表必须是 `zoomQuickTiers` 的同一份，
        // 否则同一台机会出现"点按能循环到 0.5×、面板里却没有那颗"的分裂。逐段范围都比对一遍。
        listOf(
            0.5f to 10f, 0.6f to 10f, 1f to 4f, 2f to 6f, 1f to 1f, 3f to 8f, 0.4f to 12f
        ).forEach { (lo, hi) ->
            assertEquals("范围 $lo..$hi 的两处倍率梯必须同源", zoomQuickTiers(lo, hi), zoomPanelTiers(lo, hi))
        }
    }
}
