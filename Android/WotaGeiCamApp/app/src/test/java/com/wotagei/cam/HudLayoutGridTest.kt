package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.GridAnchor
import com.wotagei.cam.ui.GridCell
import com.wotagei.cam.ui.GridRowGrowthRows
import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudPointDp
import com.wotagei.cam.ui.HudPointPx
import com.wotagei.cam.ui.HudRectDp
import com.wotagei.cam.ui.HudSizePx
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.cellAtPointer
import com.wotagei.cam.ui.cellDropOf
import com.wotagei.cam.ui.cellGroupMatesOf
import com.wotagei.cam.ui.cellOffsetPx
import com.wotagei.cam.ui.cellPlaceOffsetPx
import com.wotagei.cam.ui.clampCellToBox
import com.wotagei.cam.ui.clampStoredCell
import com.wotagei.cam.ui.defaultCellsOf
import com.wotagei.cam.ui.dropZoneOf
import com.wotagei.cam.ui.editableGridRowsOf
import com.wotagei.cam.ui.freeCellNear
import com.wotagei.cam.ui.gridBoxOf
import com.wotagei.cam.ui.gridColFromEndOf
import com.wotagei.cam.ui.gridColsCapOf
import com.wotagei.cam.ui.gridPitchOf
import com.wotagei.cam.ui.gridPlacementOf
import com.wotagei.cam.ui.gridSizePx
import com.wotagei.cam.ui.hudRowGroups
import com.wotagei.cam.ui.reservedGridColsOf
import com.wotagei.cam.ui.reservedGridRowsOf
import com.wotagei.cam.ui.resolveCells
import com.wotagei.cam.ui.zoneBandYRange
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

    /**
     * 手摆的一帧网格输入：行距与 [pitch] 的纵向同值（30px），**实测高一律 0**。
     *
     * 0 = "这一帧还没量到" ⇒ [com.wotagei.cam.ui.cellRowSpan] 恒为 1 ⇒ 本文件那些逐颗点名的行号
     * 与 #74 那一版**一字不动**。跨度本身的行为在 `HudLayoutRowPitchTest` 里喂真实高度另开一组，
     * 这样"改了跨度算式会不会悄悄动到独立性/钳制的老账"在本文件就红不了——它红只可能是有人动了
     * 独立性那条路，测的各自是各自的事。
     */
    private fun planPerRow(perRow: Int) = HudGridPlan(
        visible = HudEntry.ALL.toSet(),
        readoutPerRow = perRow,
        rowPitchOf = { 30 },
        cellHeightOf = { 0 }
    )

    private val planAll = planPerRow(3)
    private fun e(p: CamPill) = HudEntry.of(p)
    private fun h(i: HudItem) = HudEntry.of(i)

    /** 手摆的格长（dp 无关，纯 px）：证明"格子 → 坐标"这条算式与容器里有几颗、谁在前都无关 */
    private val pitch = HudSizePx(50, 30)

    /**
     * #80 之后 [cellAtPointer] 多了三个形参（[gridWidthPx]/[gapPx]/[colFromEnd]），只有"从右往里数"
     * 那一支读前两个。这里给一个够宽的名义格网与 0 行距：fromStart 那一支的结果与改前逐字同值，
     * 本文件的逆算用例都测 fromStart（"从右往里数"那一支在 #80 那组新用例里单独打）。
     */
    private fun cellAt(pointer: HudPointPx, origin: HudPointPx, size: HudSizePx) =
        cellAtPointer(pointer, origin, size, gridWidthPx = 5 * size.width, gapPx = 0, colFromEnd = false)

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
        // 右 Dock：S2-2 B 那条「姿态仪 + 音量表并排」必须原样落进格子——**同一枚格子的两颗**（#74 后果修复）。
        // 期望值重算的理由（不是为了让它绿）：上一批的默认是 LEVEL(0,0) + VOLUME(1,0)，均匀 pitch 之下
        // 第二列也按姿态仪的 54dp 撑 ⇒ 底板 120dp，把用户要的"更窄"做反了。现在配对共格、右 Dock 单列，
        // 底板回到 r11 实测的 94dp；行号与上一批逐颗相同（0..4），所以省下的那 ≈75dp 一分没丢。
        val right = cells(HudZone.RIGHT)
        assertEquals(GridCell(0, 0), right[e(CamPill.LEVEL)])
        assertEquals("音量表与姿态仪**共用第 0 行第 0 列**（配对同格 ⇒ 单列网格）", GridCell(0, 0), right[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 1), right[e(CamPill.BT)])
        assertEquals(GridCell(0, 2), right[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 3), right[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 4), right[e(CamPill.STAB)])
        // 这一枚容器的默认形态**只有一列**：任何一颗都不许落在第 1 列
        assertEquals("右 Dock 默认是单列网格（有第二列就是把宽度账做反的那一步）", setOf(0), right.values.map { it.col }.toSet())
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
        assertEquals(GridCell(0, 6), defaultCellsOf(HudZone.READOUT, order, planPerRow(1))[h(HudItem.ZOOM)])
        assertEquals(GridCell(0, 2), defaultCellsOf(HudZone.READOUT, order, planPerRow(3))[h(HudItem.ZOOM)])
        // 与 hudRowGroups 逐行对齐（推导尺子只有一把）
        val two = defaultCellsOf(HudZone.READOUT, order, planPerRow(2))
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
        // ⚠ 期望值按 #74 后果修复后的新默认**重算**过：配对那两颗现在共用 (0,0)（上一批是 (0,0)+(1,0)），
        // 行号仍是钉住之前那一档（蓝牙占第 1 行，抽走之后第 1 行留空）——断言一条没放松
        assertEquals(GridCell(0, 0), after[e(CamPill.LEVEL)])
        assertEquals("音量表与姿态仪仍在同一格（抽走蓝牙不该把配对拆开）", GridCell(0, 0), after[e(CamPill.VOLUME)])
        assertEquals("变焦不许因为蓝牙被抽走而上移一行", GridCell(0, 2), after[e(CamPill.ZOOM)])
        assertEquals("对焦不许上移", GridCell(0, 3), after[e(CamPill.FOCUS)])
        assertEquals("防抖不许上移", GridCell(0, 4), after[e(CamPill.STAB)])
        // 屏幕坐标同样不变，且期望值是**手算**的 px（格长按 50×30 px 手摆）：
        // 只写"改前与改后相等"是同源自比，写出坐标本身才测得出"格子被重排了但两边一起错"
        // 配对两颗同一格 ⇒ 屏幕坐标也是同一个点（格内的左右偏移由渲染层按实测宽算，不进这张表）
        assertEquals(HudPointPx(0, 0), cellOffsetPx(after.getValue(e(CamPill.LEVEL)), pitch))
        assertEquals(HudPointPx(0, 0), cellOffsetPx(after.getValue(e(CamPill.VOLUME)), pitch))
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
        // 入参里那颗高 0（跨度 1）⇒ 这一组期望值与 #74 逐字相同；跨度那一档单独在
        // HudLayoutRowPitchTest 里喂真实高度打（"块尾不许出带"），两条不许混着写
        val occupied = setOf(GridCell(0, 0), GridCell(1, 0), GridCell(0, 1))
        // 期望值是口算出来的最近空格 (1,1)（距离 2；同距离按"先上后下、先左后右"定序）
        assertEquals(GridCell(1, 1), freeCellNear(GridCell(0, 0), occupied, cols = 2, rows = 3, contentHeightPx = 0, rowPitchPx = 30))
        // 空格就在旁边一步时不许跳到远处
        assertEquals(GridCell(1, 0), freeCellNear(GridCell(0, 0), setOf(GridCell(0, 0)), 2, 3, 0, 30))
        // 拖出带外的落点先钳进带内（负列 → 0 列，99 行 → 最后一行），再找空位
        assertEquals(GridCell(0, 2), freeCellNear(GridCell(-9, 99), emptySet(), 2, 3, 0, 30))
        // 整带占满时不许抛，也不许返回被占格以外的野值（这一支编辑页走不到，但模型必须自洽）
        val full = (0 until 2).flatMap { c -> (0 until 3).map { r -> GridCell(c, r) } }.toSet()
        assertEquals(GridCell(0, 0), freeCellNear(GridCell(0, 0), full, 2, 3, 0, 30))
    }

    @Test
    fun droppingIntoOccupiedCellSwapsTheTwo() {
        // 本批把"拖到已占格"的语义从"跳到最近空格"改成**交换**（用户要的是手指的预期）。
        // 用户把「闪光灯」拖到「RGB 曲线」已经占着的那一格 (0,2)：曲线挪到闪光的**原格** (0,3)，
        // 闪光拿到目标格 (0,2)，其余两颗（参考线 / 监看）一颗都不动。
        // 退回旧实现（freeCellNear 只落空格）时"曲线"仍在 (0,2)、"闪光灯"落到 (1,2)，本用例当场红。
        val placed = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.FLASH), HudZone.LEFT, 2, GridCell(0, 2), planAll)
        val after = placed.gridItems(HudZone.LEFT, planAll).associate { it.entry to it.cell }
        assertEquals("被拖那颗拿到目标格", GridCell(0, 2), after[e(CamPill.FLASH)])
        assertEquals("占位者（曲线）挪到被拖那颗的原格 (0,3)", GridCell(0, 3), after[e(CamPill.CURVE)])
        // 其余两颗逐颗点名：一个都不许动
        assertEquals("参考线不动", GridCell(0, 0), after[e(CamPill.REFLINE)])
        assertEquals("监看不动", GridCell(0, 1), after[e(CamPill.MONITOR)])
        // 交换后两者都是**显式格**（写表时把被挤者一并写进去；漏写的话下一帧推导会把它打回默认）
        assertEquals(GridCell(0, 3), placed.cellsOf(HudZone.LEFT)[e(CamPill.CURVE)])
        assertEquals("四颗仍各占一格（叠格就是又一颗摞在另一颗上）", 4, after.values.toSet().size)
    }

    @Test
    fun exchangeIsRefusedWhenTheOriginStillHoldsAThirdEntry() {
        // 原格承接不了时：占位者就近让位，被拖那颗仍拿目标格，第三颗（这里是被拖那颗的同组搭档）不动。
        // 右 Dock：姿态仪 + 音量表共格 (0,0)。把音量表拖到蓝牙那一格 (0,1) ⇒ 格主是蓝牙，
        // 但"音量表的原格" (0,0) 里还住着搭档姿态仪（除自己与 occupant 之外还有住户）⇒ 不交换。
        // 于是蓝牙就近让到 (1,0)，音量表落到 (0,1)，姿态仪留在 (0,0) 一格不动。
        // 退回旧实现（只落空格）时音量表会停在 (0,1) 附近的空格、蓝牙原地不动，本用例红。
        val placed = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, GridCell(0, 1), planAll)
        val after = placed.gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }
        assertEquals("被拖那颗仍拿目标格", GridCell(0, 1), after[e(CamPill.VOLUME)])
        assertEquals("占位者（蓝牙）就近让到 (1,0)", GridCell(1, 0), after[e(CamPill.BT)])
        assertEquals("第三颗（搭档姿态仪）一格不动", GridCell(0, 0), after[e(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), placed.cellsOf(HudZone.RIGHT)[e(CamPill.LEVEL)])
        assertEquals(GridCell(1, 0), placed.cellsOf(HudZone.RIGHT)[e(CamPill.BT)])
        // 其余三颗（变焦/对焦/防抖）也不许被牵连
        assertEquals(GridCell(0, 2), after[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 3), after[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 4), after[e(CamPill.STAB)])
    }

    @Test
    fun crossContainerDropOntoAnOccupiedCellNeverSwaps() {
        // 跨容器没有"你的原格"可退回 ⇒ 仍走"只落空格"，且目标容器里那颗原地不动、不写出越区格。
        // 把左 Dock 的「参考线」拖到右 Dock 蓝牙已经占着的 (0,1)：
        // freeCellNear 在右 Dock 的占位集里按曼哈顿距离逐环找最近空格。距 (0,1) 为 1 的候选只有
        // (1,1)/(0,0)/(0,2)，其中 (1,1) 空着且块尾没出带 ⇒ 落 (1,1)（(1,0) 的距离是 2，轮不到它）。蓝牙仍在 (0,1)。
        val placed = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.REFLINE), HudZone.RIGHT, 1, GridCell(0, 1), planAll)
        val after = placed.gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }
        assertEquals("跨容器落点 = 曼哈顿距离 1 的最近空格 (1,1)", GridCell(1, 1), after[e(CamPill.REFLINE)])
        assertEquals("目标容器里那颗原地不动", GridCell(0, 1), after[e(CamPill.BT)])
        assertEquals("姿态仪仍在配对格 (0,0)", GridCell(0, 0), after[e(CamPill.LEVEL)])
        // ⚠ 既有缺陷（**与本批无关，改前逐字同值**，已记入 docs/plan/16 待办）：
        // 音量表的**存储格**被钉在 (0,0)（见下面 cellsOf 那两条），但渲染解析把它挪到了 (1,0)。
        // 机制：搭档关系由 `cellGroupMatesOf(defaultCellsOf(zone, **当前 order**), e)` 推出，而这一步
        // 把「参考线」插进了 RIGHT 的 order 第 1 位 ⇒ 音量表在**新 order** 里的推导格不再是 (0,0)
        // ⇒ 它认不出姿态仪是搭档 ⇒ 被 resolveCells 移到隔壁空格。`placeEntryAt` 的交换支读的却是
        // `defaultGridOf`（**默认 order**），两处尺子不同。要根治得让搭档表统一以默认 order 为准。
        assertEquals("音量表渲染落点（既有缺陷：被拆出配对格）", GridCell(1, 0), after[e(CamPill.VOLUME)])
        assertEquals("配对格在存储层没丢：姿态仪钉 (0,0)", GridCell(0, 0), placed.cellsOf(HudZone.RIGHT)[e(CamPill.LEVEL)])
        assertEquals("配对格在存储层没丢：音量表也钉 (0,0)", GridCell(0, 0), placed.cellsOf(HudZone.RIGHT)[e(CamPill.VOLUME)])
        // 不写出越区格：列不超过右 Dock 的列上限（2）
        assertTrue("越区格：$after", after.values.all { it.col in 0 until gridColsCapOf(HudZone.RIGHT) })
        // 来源容器不再有它，且来源剩下的三颗位置不变（钉格那一步的效力）
        assertEquals(HudZone.RIGHT, placed.sourceZoneOf(e(CamPill.REFLINE)))
        val left = placed.gridItems(HudZone.LEFT, planAll).associate { it.entry to it.cell }
        // 钉格的效力：抽走参考线之后，剩下三颗仍是原来的 (0,1)/(0,2)/(0,3)。
        // 少了钉格这一步，三颗会按新 order 集体上移成一格 (0,0)/(0,1)/(0,2)——正是 #74 要禁的耦合
        assertEquals("监看留在原格（没被上移）", GridCell(0, 1), left[e(CamPill.MONITOR)])
        assertEquals(GridCell(0, 2), left[e(CamPill.CURVE)])
        assertEquals(GridCell(0, 3), left[e(CamPill.FLASH)])
    }

    @Test
    fun cellDropOfAndPlaceEntryAtReadTheSameRuler() {
        // 桥测：同一组入参分别喂 cellDropOf 与 placeEntryAt，落点必须一致（预览与写表同源）。
        // 预览侧读 cellDropOf(...).dropped；写表侧 placeEntryAt 内部也走 cellDropOf。
        val table = HudLayoutTable.default()
        val plan = planAll
        val wanted = GridCell(0, 2)                     // 曲线那一格
        val origin = GridCell(0, 3)                     // 闪光灯当前那一格（默认表里它就在这）
        val expected = cellDropOf(
            wanted = wanted,
            origin = origin,
            residents = table.residentsOf(HudZone.LEFT, plan),
            self = e(CamPill.FLASH),
            mates = cellGroupMatesOf(table.defaultGridOf(HudZone.LEFT, plan), e(CamPill.FLASH)),
            draggedHeightPx = 0,
            cellHeightPx = plan.cellHeightOf,
            cols = gridColsCapOf(HudZone.LEFT),
            rows = HudEntry.ALL.size,                    // 与 placeEntryAt 内部的 GridRowHardCap 同值
            rowPitchPx = plan.rowPitchOf(HudZone.LEFT)
        )
        assertEquals(GridCell(0, 2), expected.dropped)
        assertEquals(mapOf(e(CamPill.CURVE) to GridCell(0, 3)), expected.moved)
        val written = table.placeEntryAt(e(CamPill.FLASH), HudZone.LEFT, 2, wanted, plan)
        val after = written.gridItems(HudZone.LEFT, plan).associate { it.entry to it.cell }
        assertEquals("预览落点必须等于写表落点", expected.dropped, after[e(CamPill.FLASH)])
        // 被挤者的落点也必须一致
        for ((who, cell) in expected.moved) assertEquals("被挤者 $who 的落点", cell, after[who])
    }

    @Test
    fun editableGridRowsGrowByOneOverTheReservation() {
        // 用户 10-01 明示放宽的那一格：可编辑行档 = 预留 + 1，再夹进带内实际档数。
        // 手算：左 Dock 横屏带 308dp ÷ 34dp ≈ 9 档、预留 4 ⇒ 可编辑 5（+1 生效）
        assertEquals(5, editableGridRowsOf(4, 9))
        // 带高只够预留那么多 ⇒ 生长被带高吃光，结果就是带内档数（不是没做）
        assertEquals(4, editableGridRowsOf(4, 4))
        // 右 Dock 横屏那一档：带 266dp ÷ 34dp ≈ 7 档、预留 7 ⇒ 可编辑 7（生长被带高吃光）
        assertEquals(7, editableGridRowsOf(7, 7))
    }

    @Test
    fun editableGridRowsWithoutReservationStayByteIdentical() {
        // reservedRows <= 0（读数块那一支）：结果必须与改前逐字同值 = 带内实际档数，与生长量无关。
        // 改错（比如把 0 那一支也加生长量 / 给了空区间）这里红
        assertEquals(9, editableGridRowsOf(0, 9))
        assertEquals(1, editableGridRowsOf(0, 0))        // 带连一档都没有也只给 1，不许 0 档
        assertEquals(1, editableGridRowsOf(-3, 0))
        // 生长量显式传别的值：只影响"有预留"的那一支，且夹子仍然生效
        assertEquals(7, editableGridRowsOf(7, 9, growthRows = 0))
        assertEquals(9, editableGridRowsOf(7, 9, growthRows = 5))
    }

    @Test
    fun gridBoxClampsToTheGrownRowLimit() {
        // 桥：gridBoxOf 真按生长后的上限夹。带 10 档、预留 4：传 growth=1 ⇒ 5 档（编辑页那一档）
        val grown = gridBoxOf(
            HudZone.LEFT, pitchXDp = 34, pitchYDp = 30, bandHeightDp = 300, roomWidthDp = 0,
            reservedRows = 4, reservedCols = 0, rowGrowth = GridRowGrowthRows, colGrowth = 0
        )
        assertEquals(5, grown.rows)
        // GridCell(0,99) 落在**生长后的最后一档** (0,4)，而不是预留那一档 (0,3)
        assertEquals(GridCell(0, 4), clampCellToBox(GridCell(0, 99), grown, contentHeightPx = 0, rowPitchPx = 30))
        // 同一入参 growth=0 ⇒ 逐字退回预留那一档（与既有的 rowOverflowBelowReservation 那条互补）
        val reservedOnly = gridBoxOf(
            HudZone.LEFT, pitchXDp = 34, pitchYDp = 30, bandHeightDp = 300, roomWidthDp = 0,
            reservedRows = 4, reservedCols = 0, rowGrowth = 0, colGrowth = 0
        )
        assertEquals(4, reservedOnly.rows)
        assertEquals(GridCell(0, 3), clampCellToBox(GridCell(0, 99), reservedOnly, 0, 30))
        // 带高不够时生长被吃光：带 4 档、预留 4、growth=1 ⇒ 仍是 4（不抛、不给空区间）
        assertEquals(
            4,
            gridBoxOf(
                HudZone.LEFT, pitchXDp = 34, pitchYDp = 30, bandHeightDp = 120, roomWidthDp = 0,
                reservedRows = 4, reservedCols = 0, rowGrowth = GridRowGrowthRows, colGrowth = 0
            ).rows
        )
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
        // 两颗都按"量不到高"（0 ⇒ 跨度 1）喂，与 #74 那一版逐字同值
        val noHeights = { _: HudEntry -> 0 }
        val collided = resolveCells(
            order,
            mapOf(e(CamPill.REFLINE) to GridCell(0, 0), e(CamPill.MONITOR) to GridCell(0, 0)),
            derived, cols = 2, rows = 3, cellHeightPx = noHeights, rowPitchPx = 30
        )
        assertEquals(listOf(GridCell(0, 0), GridCell(1, 0), GridCell(0, 2)), collided.map { it.cell })
        // ② 显式格正好压在别颗的**推导格**上 ⇒ 摆过的那颗赢、没摆过的那颗让
        val override = resolveCells(
            order, mapOf(e(CamPill.CURVE) to GridCell(0, 1)), derived,
            cols = 2, rows = 3, cellHeightPx = noHeights, rowPitchPx = 30
        )
        assertEquals(GridCell(0, 0), override[0].cell)
        assertEquals("推导那颗让位", GridCell(1, 1), override[1].cell)
        assertEquals("摆过那颗拿到它要的那格", GridCell(0, 1), override[2].cell)
        assertEquals(3, override.map { it.cell }.toSet().size)
    }

    @Test
    fun hiddenEntriesStillHoldTheirCells() {
        // 隐藏的条目不画，但格子还是它的：不算进来就会两颗挤在同一格。
        // 本批把落点语义改成交换之后，这条契约的**后半段**跟着变：隐藏条目仍算占用 ⇒ 被人压上时
        // 它被**显式挤走**（写进 cells），而不是"占着不动、让被拖那颗绕开"。
        // （与 HudLayoutCodecTest.hiddenEntrySurvivesInPersistedString 同一条语义的另一半）
        val placed = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.BT), HudZone.RIGHT, 2, GridCell(1, 3), planAll)
        val visibleOnly = HudGridPlan(
            visible = HudEntry.ALL.toSet() - e(CamPill.BT),
            readoutPerRow = 3,
            rowPitchOf = { 30 },
            cellHeightOf = { 0 }
        )
        assertTrue("隐藏那颗的格子必须算占用", placed.occupiedCells(HudZone.RIGHT, visibleOnly).contains(GridCell(1, 3)))
        // 防抖（可见）拖到蓝牙（隐藏）那一格 (1,3)：格主是隐藏的蓝牙 ⇒ 交换 ⇒ 蓝牙挪到防抖的原格 (0,4)
        val dropped = placed.placeEntryAt(e(CamPill.STAB), HudZone.RIGHT, 4, GridCell(1, 3), visibleOnly)
        val cells = dropped.gridItems(HudZone.RIGHT, visibleOnly).associate { it.entry to it.cell }
        assertEquals("被拖那颗拿到目标格", GridCell(1, 3), cells.getValue(e(CamPill.STAB)))
        assertEquals(
            "隐藏条目被**显式**挤到防抖的原格 (0,4)（写进 cells，不是绕开）",
            GridCell(0, 4), dropped.cellsOf(HudZone.RIGHT)[e(CamPill.BT)]
        )
        assertEquals(
            "蓝牙重新可见后落在它被挤到的那一格（= 被拖那颗的原格）", GridCell(0, 4),
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
            assertEquals(cell, cellAt(HudPointPx(origin.x + at.x + 1, origin.y + at.y + 1), origin, pitch))
        }
        // 指针跑到网格左上方之外：floor 给负数，必须钳成 0 而不是 -1
        assertEquals(GridCell(0, 0), cellAt(HudPointPx(0, 0), origin, pitch))
        // 格长 0（还没量到）时不许抛
        assertEquals(GridCell(0, 0), cellAt(HudPointPx(99, 99), origin, HudSizePx(0, 0)))
        // 格内居中：30×10 的小颗放进 50×30 的格子里，左上角补 (10,10)
        // 最后一形参是行跨度（#75）——1 档时与 #74 逐字同值，跨几档的那一档在 HudLayoutRowPitchTest 打
        assertEquals(HudPointPx(110, 100), cellPlaceOffsetPx(GridCell(2, 3), pitch, HudSizePx(30, 10), 1))
    }

    @Test
    fun cellClampUsesTheContainerBandNotAScreenGuess() {
        // 手摆：格长 50×30 dp、带高 120dp ⇒ 只放得下 4 行；拖到第 9 行必须钳回第 3 行
        // 后两形参（实测高、格距）#75 新加：这里一律喂"跨度 1"（高 0），钳制结果与 #74 逐字相同；
        // "跨度 > 1 时钳的是整块"那一档在 HudLayoutRowPitchTest 里喂真实高度另打
        val box = gridBoxOf(
            HudZone.LEFT, pitchXDp = 50, pitchYDp = 30, bandHeightDp = 120, roomWidthDp = 0,
            // #80 的预留夹档：0 = 不预留（与改前逐字同值），预留那一档在下面的 #80 那组用例里单独打。
            // 生长档本批是编辑页专用的那一档，这里显式传 0 ⇒ 期望值与改前一字不变
            reservedRows = 0, reservedCols = 0, rowGrowth = 0, colGrowth = 0
        )
        assertEquals(2, box.cols)
        assertEquals(4, box.rows)
        assertEquals(GridCell(1, 3), clampCellToBox(GridCell(7, 99), box, contentHeightPx = 0, rowPitchPx = 30))
        assertEquals(GridCell(0, 1), clampCellToBox(GridCell(-3, 1), box, 0, 30))
        // 哨兵不参与钳制（"没摆过"不是越界值，钳成 (0,0) 等于凭空造一个位置）
        assertEquals(GridCell.DEFAULT, clampCellToBox(GridCell.DEFAULT, box, 0, 30))
        // 读数块的列数由**可用内宽**说话：200dp ÷ 90dp 格长 = 2 列（够不到 3 列的上限）
        assertEquals(
            2,
            gridBoxOf(
                HudZone.READOUT, pitchXDp = 90, pitchYDp = 42, bandHeightDp = 300, roomWidthDp = 200,
                reservedRows = 0, reservedCols = 0, rowGrowth = 0, colGrowth = 0   // #80 预留那一档在下面的用例里单独打
            ).cols
        )
        // 带高不够（极矮窗口）也只给 1 行，不许 0 行让 coerceIn 抛
        assertEquals(1, gridBoxOf(HudZone.READOUT, 0, 0, 300, 200, reservedRows = 0, reservedCols = 0, rowGrowth = 0, colGrowth = 0).rows)
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
            "重置后音量表回到**配对那一格** (0,0)（#74 后果修复：上一批的默认是 (1,0)，那一档把底板撑到 120dp）",
            GridCell(0, 0),
            HudLayoutTable.default().gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }[e(CamPill.VOLUME)]
        )
    }

    // ---------- 六、#80：拖一颗不许动别颗（锚定与预留） ----------
    //
    // 硬判据（用户原话「移到一项其他项也会同时移动，这是极大的限制」的正解）：
    // **每颗的屏幕坐标 = f(它自己的 col,row) + 固定原点 + 固定格长**，式子里不许出现任何其他颗的信息。
    // 下面六条各自钉住那条式子的一个零件，期望值全部手算（写死常数），没有一条是"拿实现输出当期望值"：
    // 手摆尺寸一律 60×20 / 100×20 / 40×20 这一档小整数，行距 30、行距 gap 8，
    // 于是"居中补 (pitch − 内容)/2"与"每档 +30"都能口算。

    private val gap8 = HudSizePx(8, 8)

    /** 手摆实测尺寸（px）：左 Dock 四颗一样宽，右 Dock 让配对那两颗不一样宽（就是要考格长归属） */
    private fun uniformLeftWidths(entries: List<com.wotagei.cam.ui.HudGridItem>) =
        entries.map { HudSizePx(60, 20) }

    private fun rightWidths(entries: List<com.wotagei.cam.ui.HudGridItem>) = entries.map {
        when (it.entry.pill) {
            CamPill.LEVEL -> HudSizePx(100, 20)
            CamPill.VOLUME -> HudSizePx(60, 20)
            else -> HudSizePx(40, 20)
        }
    }

    private fun placedOf(zone: HudZone, items: List<com.wotagei.cam.ui.HudGridItem>) = gridPlacementOf(
        items,
        if (zone == HudZone.LEFT) uniformLeftWidths(items) else rightWidths(items),
        gap8,
        // 与 planAll.rowPitchOf 同值：30px 一档，且实测高喂 0 ⇒ 每格跨度 1
        30,
        GridAnchor(zone, HudLayoutTable.default().defaultGridOf(zone, planAll))
    )

    @Test
    fun leftDockMoveChangesOnlyTheDraggedEntryWhileTheBoardGrowsWider() {
        val table = HudLayoutTable.default()
        val before = table.gridItems(HudZone.LEFT, planAll)
        val placedBefore = placedOf(HudZone.LEFT, before)
        // 手算（默认四颗各占一档、格长 60 + 8 = 68）：格网 60×112，逐颗左上角
        assertEquals(HudSizePx(68, 30), placedBefore.pitch)
        assertEquals(HudSizePx(60, 112), placedBefore.size)
        assertEquals(
            listOf(HudPointPx(4, 5), HudPointPx(4, 35), HudPointPx(4, 65), HudPointPx(4, 95)),
            placedBefore.offsets
        )
        // 把监看摆到**第 1 列**（默认表单列，这一档就是把"用到的列数"从 1 撑到 2）
        val moved = table.placeEntryAt(e(CamPill.MONITOR), HudZone.LEFT, 1, GridCell(1, 1), planAll)
            .gridItems(HudZone.LEFT, planAll)
        val placedAfter = placedOf(HudZone.LEFT, moved)
        val byEntry = moved.mapIndexed { i, it -> it.entry to placedAfter.offsets[i] }.toMap()
        // ① "板子会长大"与"别人不动"是两件事，分开断：宽度确实从 60 涨到 128（两列），高度一字未变
        assertEquals("两列 ⇒ 格网宽 = 2×68−8 = 128（底板会长大，这条测的就是它）", HudSizePx(128, 112), placedAfter.size)
        // ② 其余三颗逐颗点名：左 Dock 贴左缘锚定，第 2 列只向右生长 ⇒ 第 0 列的颗一个像素都不许动
        assertEquals(HudPointPx(4, 5), byEntry[e(CamPill.REFLINE)])
        assertEquals(HudPointPx(4, 65), byEntry[e(CamPill.CURVE)])
        assertEquals(HudPointPx(4, 95), byEntry[e(CamPill.FLASH)])
        // ③ 被拖那颗落在 (1,1)：左缘 = 1×68 + (68−60)/2 = 72，纵向 = 1×30 + 5 = 35
        assertEquals(HudPointPx(72, 35), byEntry[e(CamPill.MONITOR)])
    }

    @Test
    fun rowOverflowIsClampedAndEvenBadDataNeverTranslatesOthers() {
        // ① 编辑页的钳制带：带高 300dp ÷ 档 30dp = 10 档，但默认表只预留 4 档 ⇒ 落点最多第 3 档
        val box = gridBoxOf(
            HudZone.LEFT, pitchXDp = 34, pitchYDp = 30, bandHeightDp = 300, roomWidthDp = 0,
            reservedRows = 4, reservedCols = 0, rowGrowth = 0, colGrowth = 0
        )
        assertEquals(2, box.cols)
        assertEquals("预留 4 档 ⇒ 带内 10 档也只许用 4 档（growth 传 0 = 拖不出预留）", 4, box.rows)
        assertEquals(GridCell(0, 3), clampCellToBox(GridCell(0, 99), box, contentHeightPx = 0, rowPitchPx = 30))
        // ② 坏数据那一档（手改 prefs / 预留之后又藏了一颗）：格网**会长高**，但别人的左上角照旧
        val table = HudLayoutTable.default()
        val over = listOf(
            com.wotagei.cam.ui.HudGridItem(e(CamPill.REFLINE), GridCell(0, 0)),
            com.wotagei.cam.ui.HudGridItem(e(CamPill.MONITOR), GridCell(0, 1)),
            com.wotagei.cam.ui.HudGridItem(e(CamPill.CURVE), GridCell(0, 2)),
            com.wotagei.cam.ui.HudGridItem(e(CamPill.FLASH), GridCell(0, 5))
        )
        val placed = placedOf(HudZone.LEFT, over)
        assertEquals("6 档 × 30 − 8 = 172（板子会长大）", 172, placed.size.height)
        val byEntry = over.mapIndexed { i, it -> it.entry to placed.offsets[i] }.toMap()
        assertEquals("参考线不动", HudPointPx(4, 5), byEntry[e(CamPill.REFLINE)])
        assertEquals("监看不动", HudPointPx(4, 35), byEntry[e(CamPill.MONITOR)])
        assertEquals("曲线不动", HudPointPx(4, 65), byEntry[e(CamPill.CURVE)])
    }

    @Test
    fun rightDockCountsColumnsFromItsAnchoredEdgeSoColZeroNeverMoves() {
        val table = HudLayoutTable.default()
        val before = table.gridItems(HudZone.RIGHT, planAll)
        val placedBefore = placedOf(HudZone.RIGHT, before)
        // 手算：配对格 100 + 8 + 60 = 168 ⇒ 格长 176；单列 ⇒ 格网宽 168、高 5×30−8 = 142
        assertEquals(HudSizePx(176, 30), placedBefore.pitch)
        assertEquals(HudSizePx(168, 142), placedBefore.size)
        // 单列时"从右往里数"与"从左往里数"必须逐字同值（默认观感一个字没动的证据）
        assertEquals(
            listOf(
                HudPointPx(4, 5),     // 姿态仪 (176−168)/2 = 4
                HudPointPx(112, 5),   // 音量表 4 + 100 + 8
                HudPointPx(68, 35),   // 蓝牙 (176−40)/2 = 68
                HudPointPx(68, 65),
                HudPointPx(68, 95),
                HudPointPx(68, 125)
            ),
            placedBefore.offsets
        )
        // 把蓝牙摆到第 1 列：格网从 168 涨到 2×176−8 = 344
        val moved = table.placeEntryAt(e(CamPill.BT), HudZone.RIGHT, 2, GridCell(1, 1), planAll)
            .gridItems(HudZone.RIGHT, planAll)
        val placedAfter = placedOf(HudZone.RIGHT, moved)
        assertEquals(HudSizePx(344, 142), placedAfter.size)
        // 右 Dock 的底板**贴右缘钉住**：屏幕上那颗离带右缘的距离 = size.width − offset.x，
        // 所以"别颗不动"要断的是这个差值（不是 offset.x 本身）——逐颗手算：
        // 改前 168 − 4 = 164，改后 344 − 180 = 164（格网向左长了 176，颗在格网里向右挪了同样的 176）
        val byEntry = moved.mapIndexed { i, it -> it.entry to placedAfter.offsets[i] }.toMap()
        for ((entry, beforeAt) in before.mapIndexed { i, it -> it.entry to placedBefore.offsets[i] }.toMap()) {
            val afterAt = byEntry.getValue(entry)
            if (entry == e(CamPill.BT)) continue
            assertEquals(
                "${entry.pill} 离带右缘的距离不许变",
                placedBefore.size.width - beforeAt.x,
                placedAfter.size.width - afterAt.x
            )
            assertEquals("${entry.pill} 的纵向不许变", beforeAt.y, afterAt.y)
        }
        // 被拖那颗自己：第 1 列在**左边**（背向锚定边生长），x = 344 − 2×176 + 8 + 68 = 244…手算核对
        assertEquals(HudPointPx(68, 35), byEntry[e(CamPill.BT)])   // 它自己在网内的左上角仍是 68
    }

    @Test
    fun splittingThePairKeepsTheReservedPitchAndTheOldModelWouldHaveMovedEveryone() {
        val table = HudLayoutTable.default()
        val before = table.gridItems(HudZone.RIGHT, planAll)
        // 拆对：把音量表摆到第 5 档，配对那一格从此只剩姿态仪 100 宽
        val split = table.placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, GridCell(0, 5), planAll)
            .gridItems(HudZone.RIGHT, planAll)
        val reserved = placedOf(HudZone.RIGHT, split)
        assertEquals("格长仍取**默认表**那枚配对格 168 + 8 = 176", 176, reserved.pitch.width)
        assertEquals("底板宽也不许因为拆对变窄（格长预留住了 ⇒ 1×176−8 = 168）", 168, reserved.size.width)
        val byEntry = split.mapIndexed { i, it -> it.entry to reserved.offsets[i] }.toMap()
        // ⚠ 留在原格那一颗会在**自己那一格里**重新居中：配对时格内容 168 ⇒ x = 4，只剩姿态仪 100 ⇒
        // x = (176−100)/2 = 38。这是"共格的那两颗本来就是一个横排单元"的必然结果，
        // 平移只发生在**同一枚格子内部**（±34px），跨格、跨列、跨行一颗都不动——
        // 本批硬判据覆盖的是后者，这一条残差照实写在这里与报告里，不当成已修
        assertEquals("姿态仪在自己那一格里重新居中", HudPointPx(38, 5), byEntry[e(CamPill.LEVEL)])
        assertEquals("蓝牙的 x 与 y 都不许被拆对带偏", HudPointPx(68, 35), byEntry[e(CamPill.BT)])
        assertEquals("对焦仍在第 3 档同一格", HudPointPx(68, 95), byEntry[e(CamPill.FOCUS)])
        // **反证**（真断言的另一半）：把默认表喂空 = 改前那条"格长取渲染分组里最宽那枚格子"的算式，
        // 格长立刻从 176 掉到 108，于是每一颗的 x 都平移 ⇒ 本批修的就是这个
        val oldModel = gridPlacementOf(split, rightWidths(split), gap8, 30, GridAnchor(HudZone.RIGHT, emptyMap()))
        assertEquals(108, oldModel.pitch.width)
    }

    @Test
    fun readoutReservesColumnsOnlyAndSaysSoAboutRows() {
        val table = HudLayoutTable.default()
        val anchor = GridAnchor(HudZone.READOUT, table.defaultGridOf(HudZone.READOUT, planAll))
        // 默认表（planAll 的 perRow = 3、七颗读数）⇒ 3 列、行号 0..2
        assertEquals(
            "读数块预留列数 = 默认表用到的列数", 3, reservedGridColsOf(anchor)
        )
        assertEquals(
            "读数块**故意不预留行档**（要贴住 #70 A 那条基线，预留与允许多摆一档不能同时成立）",
            0,
            reservedGridRowsOf(anchor, { 0 }, 30)
        )
        // 两枚竖 Dock 反过来：预留行、不预留列（列数由用户摆，贴边那侧生长）
        val rightAnchor = GridAnchor(HudZone.RIGHT, table.defaultGridOf(HudZone.RIGHT, planAll))
        assertEquals(0, reservedGridColsOf(rightAnchor))
        assertEquals("右 Dock 默认表用到 5 档", 5, reservedGridRowsOf(rightAnchor, { 0 }, 30))
        val leftAnchor = GridAnchor(HudZone.LEFT, table.defaultGridOf(HudZone.LEFT, planAll))
        assertEquals("左 Dock 默认表用到 4 档", 4, reservedGridRowsOf(leftAnchor, { 0 }, 30))
        // 钳制带：读数块可用宽 500dp ÷ 90dp = 5 列，但上限 3、预留 2 ⇒ 夹到 2（#70 A「横屏一行两颗」定版）
        assertEquals(
            2,
            gridBoxOf(
                HudZone.READOUT, pitchXDp = 90, pitchYDp = 36, bandHeightDp = 300, roomWidthDp = 500,
                reservedRows = 0, reservedCols = 2, rowGrowth = 0, colGrowth = 0
            ).cols
        )
        // 贴右缘生长的只有右 Dock（顶栏/底栏不上网格，读数块要保持"快门 | 帧率"那一对的左右序）
        assertTrue(gridColFromEndOf(HudZone.RIGHT))
        assertFalse(gridColFromEndOf(HudZone.LEFT))
        assertFalse(gridColFromEndOf(HudZone.READOUT))
    }

    @Test
    fun dropInsideTheSourceBandNeverReAssignsTheZone() {
        // 横屏那一份实测带：360 − 顶栏 44 − 底栏 72 ⇒ 右 Dock 的带是 44..288
        val land = HudAreaDp(width = 766, height = 360, topAvoidDp = 44, bottomAvoidDp = 72)
        assertEquals(44, zoneBandYRange(HudZone.RIGHT, land, topZoneMinYDp = 0).first)
        assertEquals(288, zoneBandYRange(HudZone.RIGHT, land, topZoneMinYDp = 0).last)
        // 顶栏与底栏吃整条安全区（topZoneMinYDp = 0 那一档 = 改前；本批新增的 TOP 下限在 HudLayoutClampTest 打）
        assertEquals(0..360, zoneBandYRange(HudZone.TOP, land, topZoneMinYDp = 0))
        // 带高不够（极矮窗口）时退成整条安全区，不许给空区间
        assertEquals(44..44, zoneBandYRange(HudZone.RIGHT, HudAreaDp(766, 50, 44, 72), topZoneMinYDp = 0))
        val rightCard = HudRectDp(660, 60, 758, 260)
        val band = zoneBandYRange(HudZone.RIGHT, land, topZoneMinYDp = 0)
        // ① 真机那条：指针拖出底板矩形（y=270 > 260）却还在带里 ⇒ 归属**不许**改给读数块
        assertEquals(
            HudZone.RIGHT,
            dropZoneOf(HudPointDp(700, 270), HudZone.RIGHT, rightCard, band, hitAtPointer = HudZone.READOUT)
        )
        // ② 指针拖出带外（y=300 > 288）⇒ 这是明显的跨容器动作，照读数块落
        assertEquals(
            HudZone.READOUT,
            dropZoneOf(HudPointDp(700, 300), HudZone.RIGHT, rightCard, band, hitAtPointer = HudZone.READOUT)
        )
        // ③ 横向拖离了底板那一列（x=620 < 660）⇒ 也是"往外搬"，允许改归属
        assertEquals(
            HudZone.READOUT,
            dropZoneOf(HudPointDp(620, 270), HudZone.RIGHT, rightCard, band, hitAtPointer = HudZone.READOUT)
        )
        // ④ 指针谁都没压住 / 压住的正是来源 ⇒ 一律留在来源
        assertEquals(HudZone.RIGHT, dropZoneOf(HudPointDp(700, 270), HudZone.RIGHT, rightCard, band, null))
        assertEquals(HudZone.RIGHT, dropZoneOf(HudPointDp(700, 100), HudZone.RIGHT, rightCard, band, HudZone.RIGHT))
        // ⑤ 首帧量不到底板矩形 ⇒ 退回改前那条算式（hit 说了算），两轮收敛手法
        assertEquals(HudZone.READOUT, dropZoneOf(HudPointDp(700, 270), HudZone.RIGHT, null, band, HudZone.READOUT))
    }
}
