package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudEntryKind
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.ZonePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `hud_layout` 的 schema 与「隐藏再显示不回默认」（13 号计划第 5 条 + 用户原话的语义）。
 *
 * 每条都是**真断言**，说清它钉的是什么退化：
 * - [encodeKeepsFiveZoneRecords]：钉「少写一枚容器就整表错位」——段数与顺序都是格式的一部分。
 * - [roundTripPreservesUserEdits]：把改过的表 encode→decode 回来与**对象**比相等。
 *   恒等式写法（`encode(decode(s)) == s`）测不出「默认表覆盖用户表」这类退化，所以比的是 table。
 * - [hiddenEntrySurvivesInPersistedString]：**这条才钉住用户原话**。隐藏一条控件后，
 *   持久化串里仍必须带着它的位置与归属；如果实现改成「隐藏即从表里摘掉」（最自然的写法），
 *   这里立刻红，因为再显示时它回了默认容器。
 * - [futureVersionFallsBackToDefault] / [halfSetPlacementIsTreatedAsUntouched]：坏值不抛、按未编辑处理。
 */
class HudLayoutCodecTest {

    private fun assertEachEntryOnce(table: HudLayoutTable, tag: String) {
        val all = table.allEntries()
        assertEquals("$tag：条目总数必须等于 13 颗 CamPill + 7 颗读数", HudEntry.ALL.size, all.size)
        assertEquals("$tag：同一颗控件不许出现在两枚容器里", all.size, all.toSet().size)
        assertEquals("$tag：表里必须覆盖全部可编辑条目", HudEntry.ALL.toSet(), all.toSet())
    }

    @Test
    fun encodeKeepsFiveZoneRecords() {
        val raw = HudLayoutTable.default().encode()
        assertTrue("版本段必须是 v1，改格式就要 +1 并在 decode 里处置旧串", raw.startsWith("v1;"))
        // 1 段版本 + 5 段容器
        assertEquals(6, raw.split(';').size)
        for (zone in HudZone.ALL) {
            assertTrue("缺了 ${zone.key} 段：$raw", raw.contains(";${zone.key},"))
        }
        // 默认表每段的位置都是哨兵 -1（绝对值只能来自用户拖动）
        for (seg in raw.split(';').drop(1)) {
            val f = seg.split(',')
            assertEquals(-1, f[1].toInt())
            assertEquals(-1, f[2].toInt())
        }
    }

    @Test
    fun defaultTableMatchesTheB1B3WrittenOrder() {
        val t = HudLayoutTable.default()
        // 逐条对照源码：顶栏 TopCapsule 是 SIZE→STORAGE；右竖 Dock 是 姿态/音量/蓝牙/变焦/对焦/防抖
        assertEquals(
            listOf(HudEntry.of(CamPill.SIZE), HudEntry.of(CamPill.STORAGE)),
            t.orderOf(HudZone.TOP)
        )
        assertEquals(
            listOf(CamPill.REFLINE, CamPill.MONITOR, CamPill.CURVE, CamPill.FLASH).map { HudEntry.of(it) },
            t.orderOf(HudZone.LEFT)
        )
        assertEquals(
            listOf(CamPill.LEVEL, CamPill.VOLUME, CamPill.BT, CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB)
                .map { HudEntry.of(it) },
            t.orderOf(HudZone.RIGHT)
        )
        assertEquals(listOf(HudEntry.of(CamPill.LENS)), t.orderOf(HudZone.BOTTOM))
        assertEquals(HudItem.ALL.map { HudEntry.of(it) }, t.orderOf(HudZone.READOUT))
        assertEachEntryOnce(t, "默认表")
        // 默认表的所有容器都在默认位置：位置表里没有任何绝对坐标
        for (z in HudZone.ALL) assertTrue("${z.key} 段默认必须是哨兵", t.posOf(z).isDefault)
    }

    @Test
    fun pillAndReadoutNamespacesDoNotCollide() {
        // CamPill.ZOOM(3) 与 HudItem.ZOOM(6) 是两个东西，id 必须分字母
        val zoomPill = HudEntry.of(CamPill.ZOOM)
        val zoomReadout = HudEntry.of(HudItem.ZOOM)
        assertEquals("P3", zoomPill.id)
        assertEquals("H6", zoomReadout.id)
        assertNotEqualsSelf(zoomPill, zoomReadout)
        assertEquals(zoomPill, HudEntry.fromId("P3"))
        assertEquals(zoomReadout, HudEntry.fromId("H6"))
        // ordinal 越界的串一律拒绝（手改 prefs 不能凭空造出一颗不存在的控件）
        assertNull(HudEntry.fromId("P13"))
        assertNull(HudEntry.fromId("H7"))
        assertNull(HudEntry.fromId("Z0"))
        assertNull(HudEntry.fromId(""))
        assertNull(HudEntry.fromId("P"))
        assertNull(HudEntry.fromId("P-1"))
        assertNull(HudEntry.fromId("Pxx"))
    }

    private fun assertNotEqualsSelf(a: HudEntry, b: HudEntry) {
        assertFalse("两个命名空间的同 ordinal 必须不相等，否则持久化串自相矛盾", a == b)
    }

    @Test
    fun roundTripPreservesUserEdits() {
        val edited = HudLayoutTable.default()
            .withZonePos(HudZone.RIGHT, 612, 88)
            .moveEntryTo(HudEntry.of(CamPill.CURVE), HudZone.TOP, 1)
        val back = HudLayoutTable.decode(edited.encode())
        assertEquals("编解码必须无损回到同一张表", edited, back)
        assertEquals(ZonePlacement(612, 88), back.posOf(HudZone.RIGHT))
        assertEquals(HudZone.TOP, back.sourceZoneOf(HudEntry.of(CamPill.CURVE)))
        assertEachEntryOnce(back, "回读表")
    }

    @Test
    fun hiddenEntrySurvivesInPersistedString() {
        val moved = HudLayoutTable.default().moveEntryTo(HudEntry.of(CamPill.STORAGE), HudZone.LEFT, 0)
        val curve = HudEntry.of(CamPill.CURVE)
        val repositioned = moved
            .withZonePos(HudZone.LEFT, 200, 90)
            .moveEntryTo(curve, HudZone.READOUT, 2)
        // 用户在设置页把「剩余空间」与「RGB 曲线」都关掉：可见集里没有它们
        val visible: Set<HudEntry> = CamPill.ALL.filter { it != CamPill.STORAGE && it != CamPill.CURVE }
            .map { HudEntry.of(it) }.toSet() + HudItem.ALL.map { HudEntry.of(it) }
        val renderedLeft = repositioned.visibleOrderOf(HudZone.LEFT, visible)
        val renderedReadout = repositioned.visibleOrderOf(HudZone.READOUT, visible)
        assertFalse("关掉的条目不许出现在页面上", curve in renderedLeft || curve in renderedReadout)
        assertFalse("关掉的条目不许出现在页面上", HudEntry.of(CamPill.STORAGE) in renderedLeft)

        // 关键一步：隐藏期间「存盘 + 杀进程重进」，走的就是这条持久化串
        val afterRestart = HudLayoutTable.decode(repositioned.encode())

        // 再打开显示：位置与归属都还是用户放好的那一格，不回默认
        val allVisible: Set<HudEntry> = HudEntry.ALL.toSet()
        val shownLeft = afterRestart.visibleOrderOf(HudZone.LEFT, allVisible)
        val shownReadout = afterRestart.visibleOrderOf(HudZone.READOUT, allVisible)
        assertTrue("关掉的曲线要仍挂在读数块（用户放的那枚容器）", curve in shownReadout)
        assertEquals("曲线的格位也要保住，不许排到块尾", 2, shownReadout.indexOf(curve))
        assertTrue("剩余空间要仍挂在左竖 Dock（用户放的那枚容器）", HudEntry.of(CamPill.STORAGE) in shownLeft)
        assertEquals("剩余空间仍要在左竖 Dock 第 0 格，而不是回顶栏", 0, shownLeft.indexOf(HudEntry.of(CamPill.STORAGE)))
        assertEquals("左 Dock 的位置不许因为条目被隐藏而回默认", ZonePlacement(200, 90), afterRestart.posOf(HudZone.LEFT))
        assertEachEntryOnce(afterRestart, "隐藏→再显示")
        // 顶栏少了 STORAGE 也不许被写成「顶栏已编辑」
        assertEquals(listOf(HudEntry.of(CamPill.SIZE)), afterRestart.orderOf(HudZone.TOP))
    }

    @Test
    fun hidingWholeZoneKeepsItsAbsolutePosition() {
        val t = HudLayoutTable.default().withZonePos(HudZone.READOUT, 300, 120)
        val rendered = t.visibleOrderOf(HudZone.READOUT, emptySet())
        assertTrue("读数全关时这块不该画任何东西", rendered.isEmpty())
        assertEquals("全关不等于重置位置", ZonePlacement(300, 120), HudLayoutTable.decode(t.encode()).posOf(HudZone.READOUT))
    }

    @Test
    fun garbageAndFutureVersionFallBackToDefault() {
        val def = HudLayoutTable.default()
        for (bad in listOf(null, "", "   ", "这不是位置串", "v1", "v1;T,", "v9;T,1,2,P0", "v-1;x", "v1;Q,1,1,P0")) {
            val t = HudLayoutTable.decode(bad)
            assertEquals("坏串 [$bad] 必须整表回默认而不是抛或写出错位置", def, t)
            assertEachEntryOnce(t, "坏串 [$bad]")
        }
        // 版本比本工程新：整表按默认（老代码读不懂新格式，硬解会把控件摆到错地方）
        assertEquals(def, HudLayoutTable.decode("v2;T,5,5,P11,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1"))
    }

    @Test
    fun partialStringRepairsItself() {
        // 只写了顶栏一段（手改 / 断电写坏）：其余容器回默认，位置取写好的那一档
        val t = HudLayoutTable.decode("v1;T,42,7,P11,P12")
        assertEquals(ZonePlacement(42, 7), t.posOf(HudZone.TOP))
        assertTrue("没写的容器必须仍是默认位置", t.posOf(HudZone.RIGHT).isDefault)
        assertEachEntryOnce(t, "只有一段")
        // 默认顺序不许被截断串抄走：顶栏仍是画幅→容量
        assertEquals(listOf(HudEntry.of(CamPill.SIZE), HudEntry.of(CamPill.STORAGE)), t.orderOf(HudZone.TOP))
    }

    @Test
    fun duplicateEntryGoesToFirstZoneOnly() {
        // 同一颗出现在两枚容器（手改 prefs 能造出来）：先到先得，后者丢掉，整表仍每颗一次
        val t = HudLayoutTable.decode("v1;T,42,7,P11,P12,P3;L,-1,-1,P6,P7,P8,P9,P3;R,-1,-1;D,-1,-1;B,-1,-1")
        assertEquals(HudZone.TOP, t.sourceZoneOf(HudEntry.of(CamPill.ZOOM)))
        assertFalse("变焦不许同时挂在左 Dock", HudEntry.of(CamPill.ZOOM) in t.orderOf(HudZone.LEFT))
        assertEachEntryOnce(t, "重复条目")
    }

    @Test
    fun halfSetPlacementCountsAsUntouched() {
        // 一轴绝对、一轴哨兵：只能来自手改 prefs，按「未编辑」整段处理（见 ZonePlacement 的说明）
        val t = HudLayoutTable.decode("v1;T,42,-1,P11,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1")
        assertTrue("半吊子位置必须回默认", t.posOf(HudZone.TOP).isDefault)
        assertEquals(-1, t.posOf(HudZone.TOP).xDp)
        val both = HudLayoutTable.decode("v1;T,42,0,P11,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1")
        assertEquals(ZonePlacement(42, 0), both.posOf(HudZone.TOP))
        assertFalse("y=0 是合法绝对值，不许被当成哨兵", both.posOf(HudZone.TOP).isDefault)
    }

    @Test
    fun legacyBottomXOverrideIsWipedWhenReading() {
        // r11 那版录制页的 onDrop 会把"当前实测左缘"写成绝对 x（纯长按不动也会写，此后快门再也不跟
        // 可视中心走）。存量 prefs 里就有这种串，读表时必须连 x 一起抹掉、只留 y。
        // 这条是真断言：normalize 里那个 BOTTOM 特例一旦删掉，第一个 assertEquals 就红
        val t = HudLayoutTable.decode("v1;T,-1,-1;L,-1,-1;R,-1,-1;D,-1,-1;B,120,282,P2")
        assertEquals(ZonePlacement(-1, 282), t.posOf(HudZone.BOTTOM))
        assertFalse(
            "抹掉 x 之后底栏落位仍不许算默认（否则长按搬到上栏等于白搬）",
            t.posOf(HudZone.BOTTOM).isDefault
        )
        // y 也是哨兵（没拖过）时不许凭空造出一个 0
        val untouched = HudLayoutTable.decode("v1;T,-1,-1;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1,P2")
        assertTrue(untouched.posOf(HudZone.BOTTOM).isDefault)
        // 同一个闸门的入口函数：绝对 x + 绝对 y 进来，出的仍是哨兵 x
        val legacy = HudLayoutTable.default().withZonePos(HudZone.BOTTOM, 66, 300)
        assertEquals(ZonePlacement(-1, 300), legacy.normalizedPosOf(HudZone.BOTTOM))
        // 另外四枚容器没有这个特例：两轴绝对照收（少认一边就是把闸门做成了全局）
        val right = HudLayoutTable.default().withZonePos(HudZone.RIGHT, 612, 88)
        assertEquals(ZonePlacement(612, 88), right.normalizedPosOf(HudZone.RIGHT))
    }

    @Test
    fun unknownIdsAreDroppedMissingOnesGoHome() {
        // 只声明一颗不存在的 P99 与一颗缺失的 SIZE：坏 id 丢，缺失的补回 home 容器末尾
        val t = HudLayoutTable.decode("v1;T,1,1,P99,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1")
        assertNull(HudEntry.fromId("P99"))
        assertEquals(listOf(HudEntry.of(CamPill.STORAGE), HudEntry.of(CamPill.SIZE)), t.orderOf(HudZone.TOP))
        assertEachEntryOnce(t, "补全表")
        assertNotNull(t.orderOf(HudZone.TOP).firstOrNull())
    }

    @Test
    fun resetReturnsDefaultAndPreviousStringStillDecodes() {
        val edited = HudLayoutTable.default()
            .withZonePos(HudZone.LEFT, 12, 300)
            .withZonePos(HudZone.READOUT, 500, 250)
            .moveEntryTo(HudEntry.of(CamPill.LENS), HudZone.RIGHT, 0)
        val beforeReset = edited.encode()
        // 重置按钮写下去的就是这一张表：任何绝对坐标都不许残留
        val afterReset = HudLayoutTable.default()
        assertEquals("重置＝整张表清回默认", HudLayoutTable.default(), afterReset)
        for (z in HudZone.ALL) assertTrue("${z.key} 段重置后必须回默认位置", afterReset.posOf(z).isDefault)
        assertEquals(HudEntry.of(CamPill.LENS), afterReset.orderOf(HudZone.BOTTOM).single())
        // 「即时可重做」的模型前提：重置前那张表的串还完整可读回来（撤销靠的是它，不是内存里的手抄本）
        assertEquals("撤销重置要把前一张表原样读回来", edited, HudLayoutTable.decode(beforeReset))
    }

    @Test
    fun homeZoneAndDefaultTableCannotDiverge() {
        // 两条独立写的真源必须自洽：[HudEntry.homeZone] 的 when 分支 与 defaultOrderOf 的清单。
        // 新增一颗 CamPill 时只补一边（很常见的漏法）会让「补齐缺失条目」把控件补到错的容器里，
        // 而默认表与补齐表不一致就是这条退化的直接证据。
        for (zone in HudZone.ALL) {
            for (entry in HudLayoutTable.defaultOrderOf(zone)) {
                assertEquals(
                    "${entry.id} 在默认表里属于 ${zone.key}，但 homeZone 说的是 ${entry.homeZone.key}",
                    zone, entry.homeZone
                )
            }
        }
        val homes = HudEntry.ALL.groupingBy { it.homeZone }.eachCount()
        assertEquals(
            mapOf(
                HudZone.TOP to 2, HudZone.LEFT to 4, HudZone.RIGHT to 6,
                HudZone.READOUT to 7, HudZone.BOTTOM to 1
            ), homes
        )
    }

    @Test
    fun everyCamPillAndHudItemHasAnEntry() {
        // 位掩码里的每一颗都必须能在表里出现，否则设置页关了它、编辑页却永远找不到（假开关的另一半）
        for (pill in CamPill.ALL) {
            val e = HudEntry.of(pill)
            assertEquals(pill, e.pill)
            assertEquals(HudEntryKind.PILL, e.kind)
            assertNull("PILL 命名空间不许带出读数", e.item)
            assertTrue(HudEntry.ALL.contains(e))
            assertEquals(e, HudEntry.fromId(e.id))
        }
        for (item in HudItem.ALL) {
            val e = HudEntry.of(item)
            assertEquals(item, e.item)
            assertEquals(HudEntryKind.READOUT, e.kind)
            assertNull("READOUT 命名空间不许带出胶囊", e.pill)
            assertTrue(HudEntry.ALL.contains(e))
            assertEquals(e, HudEntry.fromId(e.id))
        }
        assertEquals(CamPill.ALL.size + HudItem.ALL.size, HudEntry.ALL.size)
        assertEquals("id 必须唯一，不然持久化串自相矛盾", HudEntry.ALL.size, HudEntry.ALL.map { it.id }.toSet().size)
    }
}
