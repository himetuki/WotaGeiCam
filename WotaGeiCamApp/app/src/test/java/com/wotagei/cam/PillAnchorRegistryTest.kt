package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.PillKey
import com.wotagei.cam.ui.pillAnchorWriters
import com.wotagei.cam.ui.pillKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 就近胶囊锚点登记表的完整性（审查 S2-2，§69「浮层甩到屏幕原点」缺陷族的结构闸）。
 *
 * 复发机理：某颗控件加了 `pop = PillKey.X` 触发点与 [com.wotagei.cam.ui.PillHost] 的内容分支，
 * 却没人把锚点挂到控件上 → `pillAnchors[X]` 恒为 null → 弹窗按 `IntRect.Zero` 永久钉在左上角。
 * 这种错 lint 不报、编译不报、JVM 也摸不到，只有真机点得到（蓝牙那颗就是）。
 *
 * B4 之后写入方收成了**一处**（[com.wotagei.cam.ui.HudEntryItem] 里 `ctx.anchorOf(entry)`），
 * 所以这里把三道闸换成四道：
 * - [everyPillKeyHasAnchorWriter]：表逐个 key 覆盖 [PillKey.values]，新增 key 忘了登记就红。
 *   取"全覆盖"而不是"只覆盖有触发点的 key"，是因为后者要在测试里再抄一份触发点清单，两处都可能漏抄。
 * - [everyWriterNamesTheEntryItemBranch]：**改前**这条要求写入方以 `CameraScreen.` 开头（锚点是各容器
 *   的具形参）；B4 起锚点只有一个挂点，所以断言换成「必须点名 HudLayer.HudEntryItem 的哪一条分支」——
 *   比原来更严：光说 CameraScreen 已经不说明谁挂了。
 * - [everyPillKeyIsOwnedByAnEntry]：每个 PillKey 都必须能从某颗可编辑条目映射到（[HudEntry.pillKey]）。
 *   这张对照表漏一条，那颗控件就永远不会报锚点，浮层又回原点——这一条是 B4 新增的结构闸。
 * - [entriesWithoutPillKeyAreExactlyTheReadOnlyOnes]：反过来钉「没有就近浮层」的那三条
 *   （曲线开整块面板、水平仪与音量表是读数），防止有人新加 PillKey 却把它做成没归属的孤儿。
 */
class PillAnchorRegistryTest {

    @Test
    fun everyPillKeyHasAnchorWriter() {
        val keys = PillKey.values().toSet()
        assertEquals("PillKey 的数量与登记表不一致：新增 key 必须同步登记锚点写入方", keys.size, pillAnchorWriters.size)
        assertEquals(keys, pillAnchorWriters.keys)
    }

    @Test
    fun everyWriterNamesTheEntryItemBranch() {
        // 写入方不能是空占位：每行都得点名 HudEntryItem 的哪条分支（= 哪个 when 臂挂的锚点），
        // 否则"登记了但没人挂"这种洞照样过得去
        pillAnchorWriters.forEach { (key, writer) ->
            assertTrue("$key 的写入方写得太少：'$writer'", writer.length > 12)
            assertTrue("$key 的写入方没落在唯一的锚点挂点上：'$writer'", writer.startsWith("HudLayer.HudEntryItem("))
            assertTrue("$key 的写入方没点名条目：'$writer'", writer.contains(key.name) || writer.contains("HudItem."))
        }
    }

    @Test
    fun everyPillKeyIsOwnedByAnEntry() {
        val owned = HudEntry.ALL.mapNotNull { it.pillKey() }.toSet()
        assertEquals(
            "有 PillKey 在 HudEntry.pillKey 里没有归属：那颗控件永远不会报锚点，浮层会弹到屏幕原点",
            PillKey.values().toSet(),
            owned
        )
    }

    @Test
    fun entriesWithoutPillKeyAreExactlyTheReadOnlyOnes() {
        val noKey = HudEntry.ALL.filter { it.pillKey() == null }.map { it.id }.toSet()
        assertEquals(
            "只有曲线（整块面板）与水平仪、音量表（纯读数）三条不该有就近浮层；多出来或少了都要改代码而不是改这里",
            setOf(
                HudEntry.of(CamPill.CURVE).id,
                HudEntry.of(CamPill.LEVEL).id,
                HudEntry.of(CamPill.VOLUME).id
            ),
            noKey
        )
    }

    @Test
    fun zoomAnchorHasOneWriterPerState() {
        // 变焦这颗有两个写入方按显隐接管（竖 Dock 那颗 / 读数块里那颗），登记在同一个 key 上要说得清，
        // 两处同时抢写会让弹窗在两个坐标之间抖——这条约束写在表里，改动时别把它抹掉
        val zoom = pillAnchorWriters[PillKey.ZOOM].orEmpty()
        assertTrue(
            "ZOOM 的登记要同时写明竖 Dock 那颗与读数块那棵的接管关系：'$zoom'",
            zoom.contains("HudReadoutZone")
        )
        // 两个候选写入方确实都在条目表里（B4 之后"谁能弹"由位置表说了算，这里钉的是映射本身）
        assertTrue(HudEntry.ALL.count { it.pillKey() == PillKey.ZOOM } == 2)
        // 且都是"同名不同物"：CamPill.ZOOM 与 HudItem.ZOOM 各自一条（持久化串靠字母前缀分开）
        val owners = HudEntry.ALL.filter { it.pillKey() == PillKey.ZOOM }.map { it.id }.toSet()
        assertEquals(setOf(HudEntry.of(CamPill.ZOOM).id, HudEntry.of(HudItem.ZOOM).id), owners)
    }
}
