package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #54 的位掩码判据：默认**全开**（老用户升上来界面不能凭空少东西）——**TONE / ECHO 例外**
 * （29 §4.4-2 与 R2 同口径：新胶囊默认关，出厂掩码不含它，用户从编辑页/设置页手动打开），
 * 勾掉一颗只影响那一颗，且与常驻读数的 `hud_items` 各走各的键。
 */
class CamPillTest {

    @Test
    fun defaultMaskShowsEveryPillExceptToneAndEcho() {
        val hidden = CamPill.hiddenOf(CamPill.DEFAULT_MASK)
        assertEquals("出厂默认只藏 TONE 与 ECHO（29 §4.4-2 / R2）", setOf(CamPill.TONE, CamPill.ECHO), hidden)
        assertEquals(
            "手动全开 = maskOf(ALL)",
            CamPill.maskOf(CamPill.ALL),
            CamPill.DEFAULT_MASK or CamPill.TONE.bit or CamPill.ECHO.bit
        )
    }

    @Test
    fun clearingOneBitHidesOnlyThatPill() {
        // 出厂默认已藏 TONE/ECHO（默认关口径）：在「全开」的掩码上勾 FOCUS，只影响那一颗
        val full = CamPill.DEFAULT_MASK or CamPill.TONE.bit or CamPill.ECHO.bit
        val mask = full and CamPill.FOCUS.bit.inv()
        assertEquals(setOf(CamPill.FOCUS), CamPill.hiddenOf(mask) - setOf(CamPill.TONE, CamPill.ECHO))
        assertTrue(CamPill.TONE in CamPill.hiddenOf(CamPill.DEFAULT_MASK))
        assertTrue(CamPill.ECHO in CamPill.hiddenOf(CamPill.DEFAULT_MASK))
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
        assertEquals(CamPill.ALL.size, 17)   // 2026-11-05 起 17 颗（+ECHO）
        assertEquals(4, CamPill.MASK_SCHEMA) // schema 随 ECHO +1（R2）
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
    fun toneBitAndSchemaMigration() {
        // TONE（29 §4.4-2，2026-10-28）：第 16 颗，位=32768；**默认关**且**不进 ADDED_IN_SCHEMA**
        assertEquals(32768, CamPill.TONE.bit)
        assertTrue("TONE 刻意不登记自动补位（默认关口径）", CamPill.TONE.bit !in CamPill.ADDED_IN_SCHEMA.values)
        // 老用户（版本 ≤2）升级到 3：**不**补 TONE 位（他们本来就没见过它）。
        // 「版本 2 时代的全开」= 除 TONE/ECHO 外全开 = 今天的 DEFAULT_MASK（ECHO 同样不该被补）
        val oldMask = CamPill.DEFAULT_MASK
        assertEquals(oldMask, CamPill.migrateMask(oldMask, 2))
        assertEquals(oldMask, CamPill.migrateMask(oldMask, 1))
        // 用户手动打开 TONE 后落盘，迁移/重读不丢
        val opened = oldMask or CamPill.TONE.bit
        assertEquals(opened, CamPill.migrateMask(opened, 2))
    }

    @Test
    fun echoBitAndSchemaMigration() {
        // ECHO（R2 残影，2026-11-05）：第 17 颗，位=65536；与 TONE 同口径——**默认关**且不进 ADDED_IN_SCHEMA
        assertEquals(65536, CamPill.ECHO.bit)
        assertTrue("ECHO 刻意不登记自动补位（默认关口径）", CamPill.ECHO.bit !in CamPill.ADDED_IN_SCHEMA.values)
        // 老用户（版本 ≤3）升级到 4：**不**补 ECHO 位（他们本来就没见过它）。
        // 「版本 3 时代的全开」= 除 ECHO 外全开 = DEFAULT_MASK or TONE
        val oldMask = CamPill.DEFAULT_MASK or CamPill.TONE.bit
        assertEquals(oldMask, CamPill.migrateMask(oldMask, 3))
        assertEquals(oldMask, CamPill.migrateMask(oldMask, 2))
        // 用户手动打开 ECHO 后落盘，迁移/重读不丢
        val opened = oldMask or CamPill.ECHO.bit
        assertEquals(opened, CamPill.migrateMask(opened, 3))
    }

    @Test
    fun bitsAreDistinctAndDense() {
        val bits = CamPill.ALL.map { it.bit }.toSet()
        assertEquals(CamPill.ALL.size, bits.size)
        // 位掩码要能塞进一个 Int 持久化，超到 1<<31 就存不下了
        assertTrue(bits.max() <= 1 shl 30)
    }
}
