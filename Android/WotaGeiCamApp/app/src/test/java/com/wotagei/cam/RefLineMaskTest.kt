package com.wotagei.cam

import com.wotagei.cam.core.RefLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参考线多选胶囊的位掩码纯逻辑（`RefLineType.isOn/toggle`）。
 *
 * 面板时代这段逻辑写在 composable 里，点没点中只能靠眼睛；搬成纯函数后
 * 「再点一次要能关回去、且不动别的线」这类规则可以钉死。
 */
class RefLineMaskTest {

    @Test
    fun `关回去得到原掩码`() {
        val start = RefLineType.GRID.bit or RefLineType.CROSSHAIR.bit
        assertEquals(start, RefLineType.toggle(RefLineType.toggle(start, RefLineType.DIAGONAL), RefLineType.DIAGONAL))
    }

    @Test
    fun `开着的再点一次是关，不是保持`() {
        val on = RefLineType.toggle(0, RefLineType.HORIZON_LINE)
        assertTrue(RefLineType.isOn(on, RefLineType.HORIZON_LINE))
        assertEquals(0, RefLineType.toggle(on, RefLineType.HORIZON_LINE))
        assertFalse(RefLineType.isOn(RefLineType.toggle(on, RefLineType.HORIZON_LINE), RefLineType.HORIZON_LINE))
    }

    @Test
    fun `逐颗点开等于全开`() {
        var mask = 0
        RefLineType.ALL.forEach { mask = RefLineType.toggle(mask, it) }
        assertEquals(RefLineType.FULL_MASK, mask)
        assertEquals(RefLineType.ALL.size, RefLineType.typesOf(mask).size)
    }

    @Test
    fun `点一颗不会串改别的线`() {
        val base = RefLineType.FULL_MASK and RefLineType.RED_TOP.bit.inv()
        val after = RefLineType.toggle(base, RefLineType.RED_TOP)
        assertEquals(RefLineType.FULL_MASK, after)
        RefLineType.ALL.filter { it != RefLineType.RED_TOP }.forEach {
            assertTrue("${it.name} 应保持开着", RefLineType.isOn(after, it))
        }
    }

    @Test
    fun `全开态点掉一颗后计数跟着减`() {
        val after = RefLineType.toggle(RefLineType.FULL_MASK, RefLineType.RED_MID)
        assertEquals(RefLineType.ALL.size - 1, RefLineType.typesOf(after).size)
        assertFalse(RefLineType.isOn(after, RefLineType.RED_MID))
    }
}
