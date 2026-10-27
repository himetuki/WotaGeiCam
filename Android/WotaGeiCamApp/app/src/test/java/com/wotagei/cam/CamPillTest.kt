package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #54 的位掩码判据：默认必须**全开**（老用户升上来界面不能凭空少东西），
 * 勾掉一颗只影响那一颗，且与常驻读数的 `hud_items` 各走各的键。
 */
class CamPillTest {

    @Test
    fun defaultMaskShowsEveryPill() {
        assertTrue(CamPill.hiddenOf(CamPill.DEFAULT_MASK).isEmpty())
        assertEquals(CamPill.DEFAULT_MASK, CamPill.maskOf(CamPill.ALL))
    }

    @Test
    fun clearingOneBitHidesOnlyThatPill() {
        val mask = CamPill.DEFAULT_MASK and CamPill.FOCUS.bit.inv()
        assertEquals(setOf(CamPill.FOCUS), CamPill.hiddenOf(mask))
    }

    @Test
    fun onlyPickedBitsStayVisible() {
        // 「整个录制页只保留录制按钮」这条诉求的极端形态：只勾一颗也自洽
        val mask = CamPill.maskOf(listOf(CamPill.ZOOM))
        val hidden = CamPill.hiddenOf(mask)
        assertEquals(CamPill.ALL.size - 1, hidden.size)
        assertTrue(CamPill.ZOOM !in hidden)
        assertTrue(CamPill.BT in hidden && CamPill.CURVE in hidden)
    }

    @Test
    fun glowBitAndSchemaMigration() {
        // Glow（2026-10-27）：第 15 颗，位必须是一枚**新加**的（旧配置的 hud_pills 靠位序读回来）
        assertEquals(16384, CamPill.GLOW.bit)
        assertEquals(CamPill.ALL.size, 15)
        assertEquals(2, CamPill.MASK_SCHEMA)
        assertEquals(CamPill.GLOW.bit, CamPill.ADDED_IN_SCHEMA[2])
        // 老用户（版本 1）掩码里没有 Glow 位 ⇒ 升级补上（默认全开口径）
        val withoutGlow = CamPill.DEFAULT_MASK and CamPill.GLOW.bit.inv()
        assertEquals(CamPill.DEFAULT_MASK, CamPill.migrateMask(withoutGlow, 1))
        // 用户已在当前版关掉 Glow ⇒ 迁移不复位他的取舍
        assertEquals(withoutGlow, CamPill.migrateMask(withoutGlow, CamPill.MASK_SCHEMA))
        // 幂等
        val once = CamPill.migrateMask(withoutGlow, 0)
        assertEquals(once, CamPill.migrateMask(once, CamPill.MASK_SCHEMA))
        assertTrue(CamPill.GLOW in CamPill.hiddenOf(withoutGlow))
    }

    @Test
    fun bitsAreDistinctAndDense() {
        val bits = CamPill.ALL.map { it.bit }.toSet()
        assertEquals(CamPill.ALL.size, bits.size)
        // 位掩码要能塞进一个 Int 持久化，超到 1<<31 就存不下了
        assertTrue(bits.max() <= 1 shl 30)
    }
}
