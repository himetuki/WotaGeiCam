package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.HudAxis
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudPointDp
import com.wotagei.cam.ui.HudRectDp
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.ZonePlacement
import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.areaForRightDock
import com.wotagei.cam.ui.dropIndexFor
import com.wotagei.cam.ui.hudRowGroups
import com.wotagei.cam.ui.rightDockPacksLevelAndVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目换序与跨容器挪动（两级模型的第二级）+ 落点格位的算术。
 *
 * 真断言在哪：
 * - 每次操作后都查「20 颗可编辑控件全表恰好各出现一次」——丢了那颗、或一物两挂都会红；
 * - [movingEntryOutDoesNotResetContainerPosition] 钉的是「挪条目顺手把容器位置写回默认」这种实现里
 *   最容易顺手写出来的退化（重建 ZoneState 时把 pos 抄成 DEFAULT）；
 * - [dropIndex] 的三档入参是**手摆的矩形**，与实现的读序方式无关，所以数格子那条算式错了会当场红。
 */
class HudLayoutDragTest {

    private fun once(table: HudLayoutTable, tag: String) {
        val all = table.allEntries()
        assertEquals("$tag：条目总数", HudEntry.ALL.size, all.size)
        assertEquals("$tag：不许一物两挂", all.size, all.toSet().size)
    }

    private fun e(pill: CamPill) = HudEntry.of(pill)
    private fun e(item: HudItem) = HudEntry.of(item)

    @Test
    fun crossContainerMoveKeepsBothListsConsistent() {
        val t = HudLayoutTable.default().moveEntryTo(e(CamPill.ZOOM), HudZone.LEFT, 0)
        assertEquals(
            listOf(e(CamPill.ZOOM), e(CamPill.REFLINE), e(CamPill.MONITOR), e(CamPill.CURVE), e(CamPill.FLASH)),
            t.orderOf(HudZone.LEFT)
        )
        // 右 Dock 少了变焦，其余顺序原样（姿态/音量/蓝牙/对焦/防抖）
        assertEquals(
            listOf(e(CamPill.LEVEL), e(CamPill.VOLUME), e(CamPill.BT), e(CamPill.FOCUS), e(CamPill.STAB)),
            t.orderOf(HudZone.RIGHT)
        )
        assertEquals(HudZone.LEFT, t.sourceZoneOf(e(CamPill.ZOOM)))
        once(t, "跨容器")
        // 顶栏/读数块/底栏三枚没被牵连
        assertEquals(HudLayoutTable.defaultOrderOf(HudZone.TOP), t.orderOf(HudZone.TOP))
        assertEquals(HudLayoutTable.defaultOrderOf(HudZone.READOUT), t.orderOf(HudZone.READOUT))
        assertEquals(listOf(e(CamPill.LENS)), t.orderOf(HudZone.BOTTOM))
    }

    @Test
    fun moveBackToHomeZoneRestoresTheOriginalOrder() {
        val out = HudLayoutTable.default().moveEntryTo(e(CamPill.ZOOM), HudZone.LEFT, 0)
        val back = out.moveEntryTo(e(CamPill.ZOOM), HudZone.RIGHT, 3)
        assertEquals("回到右 Dock 第 3 格就是它原来那一格", HudLayoutTable.default().orderOf(HudZone.RIGHT), back.orderOf(HudZone.RIGHT))
        assertEquals(HudLayoutTable.default().orderOf(HudZone.LEFT), back.orderOf(HudZone.LEFT))
        once(back, "挪回")
    }

    @Test
    fun reorderInsideOneContainer() {
        val t = HudLayoutTable.default().moveEntryTo(e(CamPill.STAB), HudZone.RIGHT, 0)
        assertEquals(
            listOf(e(CamPill.STAB), e(CamPill.LEVEL), e(CamPill.VOLUME), e(CamPill.BT), e(CamPill.ZOOM), e(CamPill.FOCUS)),
            t.orderOf(HudZone.RIGHT)
        )
        // 同容器换序不许把条目变多变少，也不许让它出现在别的容器里
        assertEquals(6, t.orderOf(HudZone.RIGHT).size)
        assertFalse(e(CamPill.STAB) in t.orderOf(HudZone.LEFT))
        once(t, "换序")
    }

    @Test
    fun readoutReorderKeepsHiddenItemsInPlace() {
        // 读数块里把「码率」挪到第一格，同时设置页关掉了 EV 与白平衡
        val t = HudLayoutTable.default().moveEntryTo(e(HudItem.BITRATE), HudZone.READOUT, 0)
        val visible = setOf(e(HudItem.SHUTTER), e(HudItem.FPS), e(HudItem.BITRATE), e(HudItem.ZOOM))
        assertEquals(
            listOf(e(HudItem.BITRATE), e(HudItem.SHUTTER), e(HudItem.FPS), e(HudItem.ZOOM)),
            t.visibleOrderOf(HudZone.READOUT, visible)
        )
        // 表本身仍是完整 7 颗（隐藏的没被摘掉）；码率挪到首格之后 EV 的邻居关系不变
        assertEquals(7, t.orderOf(HudZone.READOUT).size)
        assertEquals("EV 仍排在 ISO 之后、白平衡之前", 4, t.orderOf(HudZone.READOUT).indexOf(e(HudItem.EV)))
        assertEquals(e(HudItem.ISO), t.orderOf(HudZone.READOUT)[3])
        assertEquals(e(HudItem.WB), t.orderOf(HudZone.READOUT)[5])
        once(t, "读数块换序")
    }

    @Test
    fun movingEntryOutDoesNotResetContainerPosition() {
        val positioned = HudLayoutTable.default()
            .withZonePos(HudZone.LEFT, 200, 90)
            .withZonePos(HudZone.READOUT, 640, 250)
        val moved = positioned
            .moveEntryTo(e(CamPill.CURVE), HudZone.TOP, 1)
            .moveEntryTo(e(HudItem.ISO), HudZone.LEFT, 0)
        assertEquals("挪条目不许顺手重置左 Dock 的位置", ZonePlacement(200, 90), moved.posOf(HudZone.LEFT))
        assertEquals("挪条目不许顺手重置读数块的位置", ZonePlacement(640, 250), moved.posOf(HudZone.READOUT))
        // 顶栏被塞进一颗也不代表它被"移动"过
        assertTrue("只改顺序不改坐标：顶栏仍按定稿位置", moved.posOf(HudZone.TOP).isDefault)
        once(moved, "带位置挪条目")
        // 编解码一轮之后位置与归属都还在
        assertEquals(moved, HudLayoutTable.decode(moved.encode()))
    }

    @Test
    fun emptyingBottomContainerIsLegal() {
        // 底栏只有「镜头」一颗可编辑；把它挪走之后那枚容器顺序为空，底板仍按等宽槽算（MergeSlot 有用例）
        val t = HudLayoutTable.default().moveEntryTo(e(CamPill.LENS), HudZone.TOP, 1)
        assertTrue(t.orderOf(HudZone.BOTTOM).isEmpty())
        assertEquals(listOf(e(CamPill.SIZE), e(CamPill.LENS), e(CamPill.STORAGE)), t.orderOf(HudZone.TOP))
        once(t, "底栏清空")
    }

    @Test
    fun indexOutOfRangeIsClampedNotIgnored() {
        val tail = HudLayoutTable.default().moveEntryTo(e(CamPill.LENS), HudZone.RIGHT, 999)
        assertEquals(e(CamPill.LENS), tail.orderOf(HudZone.RIGHT).last())
        val head = HudLayoutTable.default().moveEntryTo(e(CamPill.LENS), HudZone.RIGHT, -5)
        assertEquals(e(CamPill.LENS), head.orderOf(HudZone.RIGHT).first())
        assertEquals(7, head.orderOf(HudZone.RIGHT).size)
        once(tail, "越界 index")
        once(head, "负 index")
    }

    @Test
    fun dropIndexCountsCellsAlongTheMainAxis() {
        // ROW：顶栏/底栏那种横排，三颗中心分别在 30 / 100 / 170
        val row = listOf(HudRectDp(0, 0, 60, 30), HudRectDp(70, 0, 130, 30), HudRectDp(140, 0, 200, 30))
        assertEquals(0, dropIndexFor(HudAxis.ROW, row, HudPointDp(10, 15)))
        assertEquals(1, dropIndexFor(HudAxis.ROW, row, HudPointDp(50, 15)))
        assertEquals(3, dropIndexFor(HudAxis.ROW, row, HudPointDp(300, 15)))
        // COLUMN：竖 Dock 那三档
        val col = listOf(HudRectDp(0, 0, 60, 40), HudRectDp(0, 50, 60, 90), HudRectDp(0, 100, 60, 140))
        assertEquals(2, dropIndexFor(HudAxis.COLUMN, col, HudPointDp(30, 95)))
        assertEquals(0, dropIndexFor(HudAxis.COLUMN, col, HudPointDp(30, -10)))
        // GRID：读数块那种一行两颗 × 两行，行簇由上下边界决定
        val grid = listOf(
            HudRectDp(0, 0, 60, 30), HudRectDp(70, 0, 130, 30),
            HudRectDp(0, 40, 60, 70), HudRectDp(70, 40, 130, 70)
        )
        assertEquals("第二行第二格 = 3", 3, dropIndexFor(HudAxis.GRID, grid, HudPointDp(100, 55)))
        assertEquals("第一行第一格 = 0", 0, dropIndexFor(HudAxis.GRID, grid, HudPointDp(10, 20)))
        assertEquals("第一行第二格 = 1", 1, dropIndexFor(HudAxis.GRID, grid, HudPointDp(100, 10)))
        // 空容器（该容器条目全被关掉了）：插到第 0 格
        assertEquals(0, dropIndexFor(HudAxis.ROW, emptyList(), HudPointDp(50, 50)))
    }

    @Test
    fun dropIndexUsesRectCentersNotEvenSpacings() {
        // 胶囊宽度按内容自适应，同一排里三颗可以差十倍宽。实现若按「指针 ÷ 平均宽」取整就会错
        val uneven = listOf(HudRectDp(0, 0, 200, 30), HudRectDp(210, 0, 240, 30), HudRectDp(250, 0, 600, 30))
        // 中心分别在 100 / 225 / 425
        assertEquals(1, dropIndexFor(HudAxis.ROW, uneven, HudPointDp(150, 15)))
        assertEquals(2, dropIndexFor(HudAxis.ROW, uneven, HudPointDp(230, 15)))
        assertEquals(3, dropIndexFor(HudAxis.ROW, uneven, HudPointDp(599, 15)))
        // 指针落在第二颗与第三颗之间那段空白（245）：仍算「前两颗之后」，不会插回第一格
        assertEquals(2, dropIndexFor(HudAxis.ROW, uneven, HudPointDp(245, 15)))
    }

    // ---------- 容器内分行（S2-2 B 那条并排规则 + 读数块换行） ----------

    @Test
    fun rightDockPacksLevelAndVolumeOnlyWhenAdjacent() {
        val def = HudLayoutTable.defaultOrderOf(HudZone.RIGHT)
        val groups = hudRowGroups(HudZone.RIGHT, def, perRow = 1)
        // 6 颗分 5 组 = 姿态仪与音量表并成一行两列，省下的正是 S2-2 B 那 ≈75dp
        assertEquals(5, groups.size)
        assertEquals(
            listOf(
                listOf(HudEntry.of(CamPill.LEVEL), HudEntry.of(CamPill.VOLUME)),
                listOf(HudEntry.of(CamPill.BT)),
                listOf(HudEntry.of(CamPill.ZOOM)),
                listOf(HudEntry.of(CamPill.FOCUS)),
                listOf(HudEntry.of(CamPill.STAB))
            ),
            groups
        )
        assertTrue(rightDockPacksLevelAndVolume(def))
        // 用户把音量表挪到左 Dock：右 Dock 少一颗、且不再并排（并排判据只看相邻，不看枚举序）
        val movedOut = HudLayoutTable.default().moveEntryTo(HudEntry.of(CamPill.VOLUME), HudZone.LEFT, 0)
        val rest = movedOut.orderOf(HudZone.RIGHT)
        assertEquals(5, rest.size)
        assertEquals(rest.map { listOf(it) }, hudRowGroups(HudZone.RIGHT, rest, perRow = 1))
        assertFalse(rightDockPacksLevelAndVolume(rest))
        // 相邻但顺序颠倒（用户拖出来的合法形态）：仍并排，组内保持用户那个顺序
        val swapped = HudLayoutTable.default()
            .moveEntryTo(HudEntry.of(CamPill.VOLUME), HudZone.RIGHT, 0)
            .orderOf(HudZone.RIGHT)
        assertEquals(
            listOf(HudEntry.of(CamPill.VOLUME), HudEntry.of(CamPill.LEVEL)),
            hudRowGroups(HudZone.RIGHT, swapped, perRow = 1).first()
        )
        assertTrue(rightDockPacksLevelAndVolume(swapped))
    }

    @Test
    fun rowGroupsNeverLoseOrDuplicateEntries() {
        // 五枚容器逐条查「分组后仍然每颗一次」——并排那条分支最容易把某颗吞掉或数两遍
        val table = HudLayoutTable.default()
        for (zone in HudZone.ALL) {
            val order = table.orderOf(zone)
            val flat = hudRowGroups(zone, order, perRow = 2).flatten()
            assertEquals("$zone：分组不许改变颗数", order.size, flat.size)
            assertEquals("$zone：分组不许让一颗出现两遍", flat.size, flat.toSet().size)
            assertEquals("$zone：分组不许换序", order, flat)
        }
        // 顶栏/底栏是横排：无论几颗都只有一组；左竖 Dock 永远一行一颗
        val topTwo = listOf(HudEntry.of(CamPill.SIZE), HudEntry.of(CamPill.STORAGE))
        assertEquals(listOf(topTwo), hudRowGroups(HudZone.TOP, topTwo, perRow = 1))
        assertEquals(
            listOf(
                listOf(HudEntry.of(CamPill.SIZE)),
                listOf(HudEntry.of(CamPill.STORAGE))
            ),
            hudRowGroups(HudZone.LEFT, topTwo, perRow = 3)
        )
        // 读数块 perRow<1 时必须按 1 处理，否则 chunked(0) 直接抛
        assertEquals(
            topTwo.map { listOf(it) },
            hudRowGroups(HudZone.READOUT, topTwo, perRow = 0)
        )
        assertTrue(hudRowGroups(HudZone.TOP, emptyList(), perRow = 1).isEmpty())
    }

    @Test
    fun readoutChunkingFollowsUserOrderNotEnumOrder() {
        // 把码率挪到首格后，一行 2 颗的分法是「码率+快门 / 帧率+ISO」，不是枚举序的那两组
        val t = HudLayoutTable.default().moveEntryTo(HudEntry.of(HudItem.BITRATE), HudZone.READOUT, 0)
        val groups = hudRowGroups(HudZone.READOUT, t.orderOf(HudZone.READOUT), perRow = 2)
        assertEquals(
            listOf(
                listOf(HudEntry.of(HudItem.BITRATE), HudEntry.of(HudItem.SHUTTER)),
                listOf(HudEntry.of(HudItem.FPS), HudEntry.of(HudItem.ISO)),
                listOf(HudEntry.of(HudItem.EV), HudEntry.of(HudItem.WB)),
                listOf(HudEntry.of(HudItem.ZOOM))
            ),
            groups
        )
    }

    @Test
    fun bottomDockKeepsItsYWhenItNeverHasAnX() {
        // 底栏的落位是"y 绝对 + x 哨兵"这一支。三个退化都要在这里红：
        // ① isDefault 若还是"任一轴哨兵就算默认"，那 (-1, 282) 会被当成没拖过 ⇒ 快门回到底边、上栏白搬；
        // ② normalize/decode 若把"一轴绝对"整段抹平，重启后同样回默认；
        // ③ moveEntryTo 重建 ZoneState 时若把 pos 抄成 DEFAULT，挪一颗条目就把落位丢了。
        val placed = HudLayoutTable.default().withZonePos(HudZone.BOTTOM, -1, 282)
        assertFalse("底栏 y-only 落位不是默认态", placed.posOf(HudZone.BOTTOM).isDefault)
        assertEquals(ZonePlacement(-1, 282), placed.posOf(HudZone.BOTTOM))
        val moved = placed.moveEntryTo(e(CamPill.LENS), HudZone.TOP, 1)
        assertEquals("挪条目不许顺手丢掉底栏的 y", ZonePlacement(-1, 282), moved.posOf(HudZone.BOTTOM))
        val back = HudLayoutTable.decode(placed.encode())
        assertEquals("编解码一轮之后 y 还在、x 仍是哨兵", placed.posOf(HudZone.BOTTOM), back.posOf(HudZone.BOTTOM))
        // 其余四枚容器不吃这个特例：半吊子仍然按未编辑处理（见 HudLayoutCodecTest）
        val half = HudLayoutTable.default().withZonePos(HudZone.LEFT, -1, 90)
        assertTrue("左 Dock 只有一轴绝对仍算没拖过", HudLayoutTable.decode(half.encode()).posOf(HudZone.LEFT).isDefault)
        once(moved, "底栏 y-only")
    }

    @Test
    fun rightDockBandEatsReadoutHeightAndClosesWhenItIsEmpty() {
        // 横屏安全区实测宽 766（不是根容器 800）：与 HudLayoutClampTest 那两个 area 同一个来源
        val area = HudAreaDp(width = 766, height = 360, topAvoidDp = 44, bottomAvoidDp = 72)
        // 默认 3 读数一行 ≈ 42dp：右 Dock 的下界从 72 抬到 114
        assertEquals(114, areaForRightDock(area, 42).bottomAvoidDp)
        // 读数全关（块回报 0）时那条缝必须收回去，回到只有底栏那一截
        assertEquals(area, areaForRightDock(area, 0))
        // 坏值（负高）不许把下界拉到比底栏还高，等于让 Dock 伸进底栏
        assertEquals(72, areaForRightDock(area, -50).bottomAvoidDp)
        // 其余两枚容器不受这条影响（左 Dock 与读数块都不在右缘那一列）
        assertEquals(72, area.bottomAvoidDp)
    }
}
