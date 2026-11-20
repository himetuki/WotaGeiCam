package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.clampZonePos
import com.wotagei.cam.ui.dropAreaFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * READOUT 拖放区域必须与渲染区域同源（Y54）。
 *
 * 构造两枚 bottomAvoid 不同的区域（基础区让底栏 72dp、读数区让 140dp），把读数块拖到
 * 安全区最底：入档 y 必须落在读数区的边界上，且与渲染侧对同一个 y 再钳一次的结果一致。
 */
class ReadoutDropAreaTest {

    private val base = HudAreaDp(width = 800, height = 400, topAvoidDp = 0, bottomAvoidDp = 72)
    private val left = base.copy(bottomAvoidDp = 32)
    private val right = base.copy(bottomAvoidDp = 64)
    private val readout = base.copy(bottomAvoidDp = 140)

    @Test
    fun `读数块拖到边界后入档 y 与渲染 y 一致`() {
        val zone = HudZone.READOUT
        val w = 200
        val h = 48
        // 指针拖过底边，钳制只会取到边界
        val drop = clampZonePos(zone, xDp = 120, yDp = 9999, wDp = w, hDp = h,
            area = dropAreaFor(zone, base, left, right, readout), topZoneMinYDp = 0)
        // 读数区边界 = 400 - 140 - 48
        assertEquals(212, drop.yDp)
        // 渲染侧（编辑器 placementOf 的同一条算式）对入档值再钳一次，位置不许再变
        val render = clampZonePos(zone, drop.xDp, drop.yDp, w, h, readout, 0)
        assertEquals("入档 y 与渲染 y 必须一致", drop.yDp, render.yDp)
    }

    @Test
    fun `退回基础区会在同一次拖放里落到分叉位置`() {
        val zone = HudZone.READOUT
        // 旧形态：松手读 baseArea（让 72dp），读数区渲染只让到 212dp
        val old = clampZonePos(zone, 120, 9999, 200, 48, base, 0)
        assertEquals(280, old.yDp)
        val renderedOld = clampZonePos(zone, old.xDp, old.yDp, 200, 48, readout, 0)
        assertNotEquals("旧形态入档 280 与渲染 212 分叉——这正是 Y54", old.yDp, renderedOld.yDp)
        assertEquals(212, renderedOld.yDp)
    }

    @Test
    fun `区域选择与渲染的 when 同构`() {
        assertSame(left, dropAreaFor(HudZone.LEFT, base, left, right, readout))
        assertSame(right, dropAreaFor(HudZone.RIGHT, base, left, right, readout))
        assertSame(readout, dropAreaFor(HudZone.READOUT, base, left, right, readout))
        assertSame(base, dropAreaFor(HudZone.TOP, base, left, right, readout))
        assertSame(base, dropAreaFor(HudZone.BOTTOM, base, left, right, readout))
    }

    @Test
    fun `dropContainer 走同一选择函数`() {
        val editor = codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
        val body = KotlinSourceScan.flatten(bodyOf(editor, "dropContainer"))
        assertTrue(
            "容器松手必须 dropAreaFor(zone, baseArea, leftArea, rightArea, readoutArea)",
            body.contains("dropAreaFor(zone, baseArea, leftArea, rightArea, readoutArea)")
        )
        assertTrue(
            "读数分支必须来自同一选择，不许在 dropContainer 里另写一份 when",
            !body.contains("HudZone.READOUT ->")
        )
    }
}