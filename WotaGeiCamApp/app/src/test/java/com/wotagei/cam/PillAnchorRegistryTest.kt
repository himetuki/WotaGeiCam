package com.wotagei.cam

import com.wotagei.cam.ui.PillKey
import com.wotagei.cam.ui.pillAnchorWriters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 就近胶囊锚点登记表的完整性（审查 S2-2，§69「浮层甩到屏幕原点」缺陷族的结构闸）。
 *
 * 复发机理：某颗控件加了 `pop = PillKey.X` 触发点与 [com.wotagei.cam.ui.PillHost] 的内容分支，
 * 却没人把 `anchorOf(PillKey.X)` 挂到控件上 → `pillAnchors[X]` 恒为 null → 弹窗按 `IntRect.Zero`
 * 永久钉在左上角。这种错 lint 不报、编译不报、JVM 也摸不到，只有真机点得到（蓝牙那颗就是），
 * 所以这里用一张登记表把它拉到 JVM 层：
 * - [everyPillKeyHasAnchorWriter]：表必须逐个 key 覆盖 [PillKey.values]，**新增 PillKey 忘了同步登记就红**。
 *   取"全覆盖"而不是"只覆盖有触发点的 key"，是因为后者要在测试里再抄一份触发点清单，
 *   两处都可能漏抄；全量覆盖没有循环论证，也不给"这颗特殊、不用锚点"留口子（真不用的也得写明写入方）。
 * - 另一半防线在代码里：承载触发点的控件把锚点形参改成**无默认值的必传参数**（漏挂 = 编译错误）。
 */
class PillAnchorRegistryTest {

    @Test
    fun everyPillKeyHasAnchorWriter() {
        val keys = PillKey.values().toSet()
        assertEquals("PillKey 的数量与登记表不一致：新增 key 必须同步登记锚点写入方", keys.size, pillAnchorWriters.size)
        assertEquals(keys, pillAnchorWriters.keys)
    }

    @Test
    fun eachWriterNamesTheHostControl() {
        // 写入方不能是空占位：每行都得说清"哪个控件的哪个形参"，否则登记等于没登记
        pillAnchorWriters.forEach { (key, writer) ->
            assertTrue("$key 的写入方写得太少：'$writer'", writer.length > 12)
            assertTrue("$key 的写入方没落在取景页：'$writer'", writer.startsWith("CameraScreen."))
        }
    }

    @Test
    fun zoomAnchorHasOneWriterPerState() {
        // 变焦这颗有两个写入方按显隐接管（右 Dock 那颗 / HUD 读数），登记在同一个 key 上要说得清，
        // 两处同时抢写会让弹窗位置在两个坐标之间抖——这条约束写在表里，改动时别把它抹掉
        val zoom = pillAnchorWriters[PillKey.ZOOM].orEmpty()
        assertTrue("ZOOM 的登记要同时写明右 Dock 与 HUD 的接管关系：'$zoom'", zoom.contains("ParamsHud"))
    }
}
