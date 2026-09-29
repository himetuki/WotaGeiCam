package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.GridCell
import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudPointPx
import com.wotagei.cam.ui.HudSizePx
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.cellAtPointer
import com.wotagei.cam.ui.cellOffsetPx
import com.wotagei.cam.ui.cellPlaceOffsetPx
import com.wotagei.cam.ui.clampCellToBox
import com.wotagei.cam.ui.clampStoredCell
import com.wotagei.cam.ui.defaultCellsOf
import com.wotagei.cam.ui.freeCellNear
import com.wotagei.cam.ui.gridBoxOf
import com.wotagei.cam.ui.gridColsCapOf
import com.wotagei.cam.ui.gridPitchOf
import com.wotagei.cam.ui.gridSizePx
import com.wotagei.cam.ui.hudRowGroups
import com.wotagei.cam.ui.resolveCells
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务 #74：把「编辑控件」的容器内位置模型从**有序列表**改成**固定格子**。
 *
 * 用户原话是「移到一项其他项也会同时移动，这是极大的限制」，所以本文件的**主用例不是"逻辑上不会动"，
 * 而是逐颗列出格子与屏幕坐标**，证明它们一字未变。
 *
 * 恒等式与真断言的分工（AGENTS.md 明写那条）：
 * - 屏幕坐标一律用**手摆的格长**（50×30、90×42 这种）喂进 [cellOffsetPx]，期望值是人能口算出来的数，
 *   不是"由实现自己算出来再与自己比"；
 * - [gridPitchOfInvertsGridSizePx] 那对正反算式必须互逆，且**改坏任何一侧都会红**；
 * - 默认格子逐颗点名（(0,0)/(1,0)/(0,1)…），所以把 `defaultCellsOf` 换成任何手抄坐标表都会红。
 */
class HudLayoutGridTest {

    private val planAll = HudGridPlan(HudEntry.ALL.toSet(), readoutPerRow = 3)
    private fun e(p: CamPill) = HudEntry.of(p)
    private fun h(i: HudItem) = HudEntry.of(i)

    /** 手摆的格长（dp 无关，纯 px）：证明"格子 → 坐标"这条算式与容器里有几颗、谁在前都无关 */
    private val pitch = HudSizePx(50, 30)

    private fun cells(zone: HudZone, table: HudLayoutTable = HudLayoutTable.default()) =
        table.gridItems(zone, planAll).associate { it.entry to it.cell }

    // ---------- 一、默认表结构性等于今天的排布 ----------

    @Test
    fun defaultLatticeIsTheTodayRowGroups() {
        // 左 Dock：今天是一行一颗 ⇒ 四颗落在第 0 列的第 0..3 行
        val left = cells(HudZone.LEFT)
        assertEquals(GridCell(0, 0), left[e(CamPill.REFLINE)])
        assertEquals(GridCell(0, 1), left[e(CamPill.MONITOR)])
        assertEquals(GridCell(0, 2), left[e(CamPill.CURVE)])
        assertEquals(GridCell(0, 3), left[e(CamPill.FLASH)])
        // 右 Dock：S2-2 B 那条「姿态仪 + 音量表并排」必须原样落进格子（同一行两列），
        // 否则 #70 那笔省 75dp 的账在格网模型里就丢了
        val right = cells(HudZone.RIGHT)
        assertEquals(GridCell(0, 0), right[e(CamPill.LEVEL)])
        assertEquals(GridCell(1, 0), right[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 1), right[e(CamPill.BT)])
        assertEquals(GridCell(0, 2), right[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 3), right[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 4), right[e(CamPill.STAB)])
        // 读数块：一行 3 颗 ⇒ 7 颗排 3 + 3 + 1
        val readout = cells(HudZone.READOUT)
        assertEquals(GridCell(0, 0), readout[h(HudItem.SHUTTER)])
        assertEquals(GridCell(2, 0), readout[h(HudItem.BITRATE)])
        assertEquals(GridCell(0, 1), readout[h(HudItem.ISO)])
        assertEquals(GridCell(2, 1), readout[h(HudItem.WB)])
        assertEquals(GridCell(0, 2), readout[h(HudItem.ZOOM)])
        // 默认表里**一个显式格子都不许有**：默认值走推导，手抄就会与实现分叉
        for (z in HudZone.ALL) assertTrue("${z.key} 段默认表不该有显式格子", HudLayoutTable.default().cellsOf(z).isEmpty())
    }

    @Test
    fun readoutLatticeFollowsThePerRowTier() {
        // 同一张默认表、只换分行档：1 颗一列时 7 颗 7 行；3 颗一列时 3 行。
        // 这条钉的是"推导读的是 hudRowGroups 的档"，把 perRow 写死成常量的实现会当场红
        val order = HudLayoutTable.defaultOrderOf(HudZone.READOUT)
        assertEquals(GridCell(0, 6), defaultCellsOf(HudZone.READOUT, order, perRow = 1)[h(HudItem.ZOOM)])
        assertEquals(GridCell(0, 2), defaultCellsOf(HudZone.READOUT, order, perRow = 3)[h(HudItem.ZOOM)])
        // 与 hudRowGroups 逐行对齐（推导尺子只有一把）
        val two = defaultCellsOf(HudZone.READOUT, order, perRow = 2)
        val groups = hudRowGroups(HudZone.READOUT, order, perRow = 2)
        groups.forEachIndexed { row, group ->
            group.forEachIndexed { col, entry -> assertEquals("第 $row 行第 $col 列", GridCell(col, row), two[entry]) }
        }
    }

    @Test
    fun nonGridZonesNeverGetCells() {
        // 顶栏与底栏**本批不上网格**（W/2 居中不变量与容量段三档取位，理由见 HudZone.isGrid 的枚举头）：
        // 它们拿到的格子必须恒为哨兵，渲染那一路才不会误读
        assertTrue(HudLayoutTable.default().gridItems(HudZone.TOP, planAll).all { it.cell.isDefault })
        assertTrue(HudLayoutTable.default().gridItems(HudZone.BOTTOM, planAll).all { it.cell.isDefault })
        assertEquals(1, gridColsCapOf(HudZone.TOP))
        assertEquals(emptySet<GridCell>(), HudLayoutTable.default().occupiedCells(HudZone.TOP, planAll))
    }

    // ---------- 二、独立性：本任务唯一的验收口径 ----------

    @Test
    fun movingLeftDockSecondEntryLeavesEveryOtherCellAndOffsetUntouched() {
        // 场景一：「把左 Dock 第 2 颗（屏幕监看）拖到第 4 行」。
        // 逐颗列出改前/改后的格子与屏幕坐标（格长按手摆的 50×30 px，与实现无关）。
        val before = cells(HudZone.LEFT)
        val beforePx = before.mapValues { (_, c) -> c to cellOffsetPx(c, pitch) }
        val after = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.MONITOR), HudZone.LEFT, 3, GridCell(0, 4), planAll)
            .let { cells(HudZone.LEFT, it) }
        val afterPx = after.mapValues { (_, c) -> c to cellOffsetPx(c, pitch) }
        // 被拖的那颗确实走了
        assertEquals("监看必须落在用户要的第 4 行", GridCell(0, 4), after[e(CamPill.MONITOR)])
        // 其余三颗逐颗点名：格子与屏幕坐标**一字未变**
        for (key in listOf(e(CamPill.REFLINE), e(CamPill.CURVE), e(CamPill.FLASH))) {
            assertEquals("$key 的格子不许被牵连", beforePx[key], afterPx[key])
        }
        // 手写的期望值（不是"与实现比"）：这样实现退化成"按顺序重排"时这里会红
        assertEquals(GridCell(0, 0) to HudPointPx(0, 0), afterPx[e(CamPill.REFLINE)])
        assertEquals(GridCell(0, 2) to HudPointPx(0, 60), afterPx[e(CamPill.CURVE)])
        assertEquals(GridCell(0, 3) to HudPointPx(0, 90), afterPx[e(CamPill.FLASH)])
        // 顺带钉住"顺序变了但位置没变"：监看被抽出来插到 order 末尾（第 3 位），
        // 曲线与闪光灯在顺序里都前移了一位，**格子却一格没动** —— 这就是旧模型做不到的那件事
        val movedTable = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.MONITOR), HudZone.LEFT, 3, GridCell(0, 4), planAll)
        assertEquals(
            listOf(e(CamPill.REFLINE), e(CamPill.CURVE), e(CamPill.FLASH), e(CamPill.MONITOR)),
            movedTable.orderOf(HudZone.LEFT)
        )
        assertEquals("顺序第 2 位的曲线仍在第 2 行（旧模型会让它上移到第 1 行）",
            GridCell(0, 2), movedTable.gridItems(HudZone.LEFT, planAll)[1].cell)
    }

    @Test
    fun movingOneEntryOutOfRightDockLeavesTheOtherFiveCellsFrozen() {
        // 场景二：「把右 Dock 第 3 颗（蓝牙）挪到顶栏」。剩下的五颗在**有序列表模型**里会集体上移：
        // 变焦从第 2 行到第 1 行、对焦从第 3 行到第 2 行、防抖从第 4 行到第 3 行——
        // 这正是用户抱怨的那个耦合，所以这一条是把"旧写法"钉死的桥函数用例
        val before = cells(HudZone.RIGHT)
        val after = cells(HudZone.RIGHT, HudLayoutTable.default().moveEntryTo(e(CamPill.BT), HudZone.TOP, 1, planAll))
        assertEquals(
            listOf(e(CamPill.LEVEL), e(CamPill.VOLUME), e(CamPill.ZOOM), e(CamPill.FOCUS), e(CamPill.STAB)),
            HudLayoutTable.default().moveEntryTo(e(CamPill.BT), HudZone.TOP, 1, planAll).orderOf(HudZone.RIGHT)
        )
        // 逐颗点名（手抄期望值，删掉 placeEntryAt 里那一步 pinned 就会红）
        assertEquals(GridCell(0, 0), after[e(CamPill.LEVEL)])
        assertEquals(GridCell(1, 0), after[e(CamPill.VOLUME)])
        assertEquals("变焦不许因为蓝牙被抽走而上移一行", GridCell(0, 2), after[e(CamPill.ZOOM)])
        assertEquals("对焦不许上移", GridCell(0, 3), after[e(CamPill.FOCUS)])
        assertEquals("防抖不许上移", GridCell(0, 4), after[e(CamPill.STAB)])
        // 屏幕坐标同样不变，且期望值是**手算**的 px（格长按 50×30 px 手摆）：
        // 只写"改前与改后相等"是同源自比，写出坐标本身才测得出"格子被重排了但两边一起错"
        assertEquals(HudPointPx(0, 0), cellOffsetPx(after.getValue(e(CamPill.LEVEL)), pitch))
        assertEquals(HudPointPx(50, 0), cellOffsetPx(after.getValue(e(CamPill.VOLUME)), pitch))
        assertEquals(HudPointPx(0, 60), cellOffsetPx(after.getValue(e(CamPill.ZOOM)), pitch))
        assertEquals(HudPointPx(0, 90), cellOffsetPx(after.getValue(e(CamPill.FOCUS)), pitch))
        assertEquals(HudPointPx(0, 120), cellOffsetPx(after.getValue(e(CamPill.STAB)), pitch))
        // 差分断言（与上面的手算值互补）：五颗的格子在"抽走蓝牙"前后必须**完全相同**
        assertEquals(
            before.filterKeys { it != e(CamPill.BT) },
            after.filterKeys { it != e(CamPill.BT) }
        )
        // 顶栏不是网格容器：那颗进去之后格子回哨兵，由顶栏的顺序排
        assertEquals(HudZone.TOP, HudLayoutTable.default().moveEntryTo(e(CamPill.BT), HudZone.TOP, 1, planAll).sourceZoneOf(e(CamPill.BT)))
        assertTrue(
            cells(HudZone.TOP, HudLayoutTable.default().moveEntryTo(e(CamPill.BT), HudZone.TOP, 1, planAll)).values.all { it.isDefault }
        )
        // 钉格子的证据：来源容器的五颗现在都带**显式**格子（没钉的话它们仍是哨兵，
        // 下一次顺序一变就会集体重排——那正是旧模型的行为）
        val moved = HudLayoutTable.default().moveEntryTo(e(CamPill.BT), HudZone.TOP, 1, planAll)
        assertEquals(5, moved.cellsOf(HudZone.RIGHT).size)
    }

    @Test
    fun pinningScopeIsExactlyTheTwoAffectedZones() {
        // 钉格子的范围只该是"来源 + 目标"两枚容器：把全表一起钉死会让默认推导再也回不来，
        // 而漏钉一枚就是"拖一颗动一片"
        val t = HudLayoutTable.default().moveEntryTo(e(CamPill.CURVE), HudZone.READOUT, 0, planAll)
        assertTrue("来源（左 Dock）要钉", t.cellsOf(HudZone.LEFT).isNotEmpty())
        assertTrue("目标（读数块）要钉", t.cellsOf(HudZone.READOUT).isNotEmpty())
        assertTrue("没牵连的右 Dock 不许钉", t.cellsOf(HudZone.RIGHT).isEmpty())
        assertTrue("没牵连的底栏不许写格子", t.cellsOf(HudZone.BOTTOM).isEmpty())
    }

    // ---------- 三、落点钳制：不许把两颗钳进同一格 ----------

    @Test
    fun freeCellNearNeverReturnsAnOccupiedCell() {
        // 手摆：2 列 3 行，(0,0)/(1,0)/(0,1) 已占。指针落在 (0,0) 那颗身上
        val occupied = setOf(GridCell(0, 0), GridCell(1, 0), GridCell(0, 1))
        // 期望值是口算出来的最近空格 (1,1)（距离 2；同距离按"先上后下、先左后右"定序）
        assertEquals(GridCell(1, 1), freeCellNear(GridCell(0, 0), occupied, cols = 2, rows = 3))
        // 空格就在旁边一步时不许跳到远处
        assertEquals(GridCell(1, 0), freeCellNear(GridCell(0, 0), setOf(GridCell(0, 0)), cols = 2, rows = 3))
        // 拖出带外的落点先钳进带内（负列 → 0 列，99 行 → 最后一行），再找空位
        assertEquals(GridCell(0, 2), freeCellNear(GridCell(-9, 99), emptySet(), cols = 2, rows = 3))
        // 整带占满时不许抛，也不许返回被占格以外的野值（这一支编辑页走不到，但模型必须自洽）
        val full = (0 until 2).flatMap { c -> (0 until 3).map { r -> GridCell(c, r) } }.toSet()
        assertEquals(GridCell(0, 0), freeCellNear(GridCell(0, 0), full, cols = 2, rows = 3))
    }

    @Test
    fun droppingIntoOccupiedCellDisplacesNobody() {
        // 用户把「闪光灯」拖到「RGB 曲线」已经占着的那一格 ⇒ 曲线原地不动、闪光灯就近让位。
        // 选"只落空格"而不是"与占位那颗交换"：交换的语义就是"移动一颗会动另一颗"，
        // 与用户那句「移到一项其他项也会同时移动，这是极大的限制」正面冲突
        val placed = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.FLASH), HudZone.LEFT, 2, GridCell(0, 2), planAll)
        val after = placed.gridItems(HudZone.LEFT, planAll).associate { it.entry to it.cell }
        assertEquals("被占的那颗（曲线）一格都不许让", GridCell(0, 2), after[e(CamPill.CURVE)])
        assertEquals("拖过来那颗就近让到隔壁空格", GridCell(1, 2), after[e(CamPill.FLASH)])
        assertEquals("其余两颗不动", GridCell(0, 0), after[e(CamPill.REFLINE)])
        assertEquals("其余两颗不动", GridCell(0, 1), after[e(CamPill.MONITOR)])
        assertEquals("四颗仍各占一格（叠格就是又一颗摞在另一颗上）", 4, after.values.toSet().size)
    }

    @Test
    fun resolveCellsGivesExplicitPlacementsPriorityAndKeepsThemDistinct() {
        // 直接打解析函数本身：手改 prefs 能把两颗写成同一格，解析必须拆开
        val order = listOf(e(CamPill.REFLINE), e(CamPill.MONITOR), e(CamPill.CURVE))
        val derived = mapOf(
            e(CamPill.REFLINE) to GridCell(0, 0),
            e(CamPill.MONITOR) to GridCell(0, 1),
            e(CamPill.CURVE) to GridCell(0, 2)
        )
        // ① 两颗显式同格 ⇒ 顺序在前的保留，后者就近让位（这就是 [order] 降级后剩下的用途①）
        val collided = resolveCells(
            order,
            mapOf(e(CamPill.REFLINE) to GridCell(0, 0), e(CamPill.MONITOR) to GridCell(0, 0)),
            derived, cols = 2, rows = 3
        )
        assertEquals(listOf(GridCell(0, 0), GridCell(1, 0), GridCell(0, 2)), collided.map { it.cell })
        // ② 显式格正好压在别颗的**推导格**上 ⇒ 摆过的那颗赢、没摆过的那颗让
        val override = resolveCells(order, mapOf(e(CamPill.CURVE) to GridCell(0, 1)), derived, cols = 2, rows = 3)
        assertEquals(GridCell(0, 0), override[0].cell)
        assertEquals("推导那颗让位", GridCell(1, 1), override[1].cell)
        assertEquals("摆过那颗拿到它要的那格", GridCell(0, 1), override[2].cell)
        assertEquals(3, override.map { it.cell }.toSet().size)
    }

    @Test
    fun hiddenEntriesStillHoldTheirCells() {
        // 隐藏的条目不画，但格子还是它的：不算进来就会两颗挤在同一格
        // （与 HudLayoutCodecTest.hiddenEntrySurvivesInPersistedString 同一条语义的另一半）
        val placed = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.BT), HudZone.RIGHT, 2, GridCell(1, 3), planAll)
        val visibleOnly = HudGridPlan(HudEntry.ALL.toSet() - e(CamPill.BT), readoutPerRow = 3)
        assertTrue("隐藏那颗的格子必须算占用", placed.occupiedCells(HudZone.RIGHT, visibleOnly).contains(GridCell(1, 3)))
        val dropped = placed.placeEntryAt(e(CamPill.STAB), HudZone.RIGHT, 4, GridCell(1, 3), visibleOnly)
        val cells = dropped.gridItems(HudZone.RIGHT, visibleOnly).associate { it.entry to it.cell }
        assertEquals("蓝牙那一格不许被压", GridCell(1, 3), dropped.cellsOf(HudZone.RIGHT)[e(CamPill.BT)])
        assertTrue("防抖被就近让开", cells.getValue(e(CamPill.STAB)) != GridCell(1, 3))
        assertEquals(
            "蓝牙重新可见后仍回到它被摆的那一格", GridCell(1, 3),
            dropped.gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }[e(CamPill.BT)]
        )
    }

    // ---------- 四、格子 ↔ 像素（渲染与编辑页共用的那一把尺子） ----------

    @Test
    fun gridPitchOfInvertsGridSizePx() {
        // 正算：读数块 3 列 2 行、格长 90×42、行距 6 ⇒ 宽 = 3×90−6 = 264、高 = 2×42−6 = 78
        val p = HudSizePx(90, 42)
        val g = HudSizePx(6, 6)
        assertEquals(HudSizePx(264, 78), gridSizePx(3, 2, p, g))
        // 逆算必须回到同一把格长（编辑页就是这么把格长从实测矩形里除回来的）——
        // 两侧任何一边改了"要不要减那道行距"，这里立刻红
        assertEquals(
            p,
            gridPitchOf(
                gridWidthPx = 264, gridHeightPx = 78, cols = 3, rows = 2, gapPx = 6,
                fallbackChildWidthPx = 999, fallbackChildHeightPx = 999
            )
        )
        // 一列一行时 = 最宽那颗 + 行距，与今天 spacedBy 的紧凑排布同宽同高（"默认表结构性等于今天"这条）
        // 一列一行时 = 90−6 = 84（**最宽那颗本身**），与今天 Column 的 wrap 宽同值
        assertEquals(HudSizePx(84, 36), gridSizePx(1, 1, p, g))
        assertEquals(HudSizePx(174, 78), gridSizePx(2, 2, p, g))
        // 空容器 ⇒ 退回"被拖那颗的尺寸 + 行距"，不许给 0（0 会让吸附算式除零）
        assertEquals(
            HudSizePx(71, 42),
            gridPitchOf(0, 0, cols = 0, rows = 0, gapPx = 5, fallbackChildWidthPx = 66, fallbackChildHeightPx = 37)
        )
        assertEquals(HudSizePx(0, 0), gridSizePx(0, 3, p, g))
    }

    @Test
    fun cellOffsetAndPointerRoundTrip() {
        // 手摆：格长 50×30，网格原点在 (10,20)
        val origin = HudPointPx(10, 20)
        assertEquals(HudPointPx(0, 0), cellOffsetPx(GridCell(0, 0), pitch))
        assertEquals(HudPointPx(100, 90), cellOffsetPx(GridCell(2, 3), pitch))
        // 正算出来的格网左上角再逆算回同一格（编辑页的吸附与渲染层的定位是同一条尺子）
        for (cell in listOf(GridCell(0, 0), GridCell(1, 2), GridCell(2, 4))) {
            val at = cellOffsetPx(cell, pitch)
            assertEquals(cell, cellAtPointer(HudPointPx(origin.x + at.x + 1, origin.y + at.y + 1), origin, pitch))
        }
        // 指针跑到网格左上方之外：floor 给负数，必须钳成 0 而不是 -1
        assertEquals(GridCell(0, 0), cellAtPointer(HudPointPx(0, 0), origin, pitch))
        // 格长 0（还没量到）时不许抛
        assertEquals(GridCell(0, 0), cellAtPointer(HudPointPx(99, 99), origin, HudSizePx(0, 0)))
        // 格内居中：30×10 的小颗放进 50×30 的格子里，左上角补 (10,10)
        assertEquals(HudPointPx(110, 100), cellPlaceOffsetPx(GridCell(2, 3), pitch, HudSizePx(30, 10)))
    }

    @Test
    fun cellClampUsesTheContainerBandNotAScreenGuess() {
        // 手摆：格长 50×30 dp、带高 120dp ⇒ 只放得下 4 行；拖到第 9 行必须钳回第 3 行
        val box = gridBoxOf(HudZone.LEFT, pitchXDp = 50, pitchYDp = 30, bandHeightDp = 120, roomWidthDp = 0)
        assertEquals(2, box.cols)
        assertEquals(4, box.rows)
        assertEquals(GridCell(1, 3), clampCellToBox(GridCell(7, 99), box))
        assertEquals(GridCell(0, 1), clampCellToBox(GridCell(-3, 1), box))
        // 哨兵不参与钳制（"没摆过"不是越界值，钳成 (0,0) 等于凭空造一个位置）
        assertEquals(GridCell.DEFAULT, clampCellToBox(GridCell.DEFAULT, box))
        // 读数块的列数由**可用内宽**说话：200dp ÷ 90dp 格长 = 2 列（够不到 3 列的上限）
        assertEquals(
            2,
            gridBoxOf(HudZone.READOUT, pitchXDp = 90, pitchYDp = 42, bandHeightDp = 300, roomWidthDp = 200).cols
        )
        // 带高不够（极矮窗口）也只给 1 行，不许 0 行让 coerceIn 抛
        assertEquals(1, gridBoxOf(HudZone.READOUT, 0, 0, 300, 200).rows)
        // 存储层硬上限都从既有真源推出来：竖 Dock 两列（hudRowGroups 的并排分支最多两颗）、
        // 读数块三列（hudPerRowFor 的最高档），行数 = 条目总数
        assertEquals(2, gridColsCapOf(HudZone.LEFT))
        assertEquals(2, gridColsCapOf(HudZone.RIGHT))
        assertEquals(3, gridColsCapOf(HudZone.READOUT))
        assertEquals(1, gridColsCapOf(HudZone.TOP))
        assertEquals(GridCell(1, 19), clampStoredCell(HudZone.LEFT, GridCell(99, 999)))
        assertEquals(GridCell.DEFAULT, clampStoredCell(HudZone.LEFT, GridCell.DEFAULT))
    }

    // ---------- 五、格子也要落盘与重置 ----------

    @Test
    fun cellsRoundTripThroughThePersistedString() {
        val t = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 0, GridCell(1, 5), planAll)
            .placeEntryAt(h(HudItem.EV), HudZone.READOUT, 3, GridCell(2, 4), planAll)
        val back = HudLayoutTable.decode(t.encode())
        assertEquals("整张表必须无损", t, back)
        assertEquals(GridCell(1, 5), back.cellsOf(HudZone.LEFT)[e(CamPill.REFLINE)])
        assertEquals(GridCell(2, 4), back.cellsOf(HudZone.READOUT)[h(HudItem.EV)])
        // 编码→解码→编码同串（幂等）：落盘的那份与内存里读回来的必须是同一张表
        assertEquals(t.encode(), back.encode())
    }

    @Test
    fun resetClearsEveryCell() {
        // 重置按钮走"删键 ⇒ 默认表"，格子必须一起回默认（不许留任何显式格）
        val edited = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 5, GridCell(1, 7), planAll)
        assertFalse("编辑后该有显式格子", edited.cellsOf(HudZone.RIGHT).isEmpty())
        for (z in HudZone.ALL) {
            assertTrue("${z.key} 段重置后不许留格子", HudLayoutTable.default().cellsOf(z).isEmpty())
        }
        assertEquals(
            "重置后音量表回到第 1 列第 0 行",
            GridCell(1, 0),
            HudLayoutTable.default().gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }[e(CamPill.VOLUME)]
        )
    }
}
