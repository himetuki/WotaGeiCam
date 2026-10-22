package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.ui.GridCell
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.cellDropOf
import com.wotagei.cam.ui.cellRowSpan
import com.wotagei.cam.ui.freeCellNear
import com.wotagei.cam.ui.GridRowGrowthRows
import com.wotagei.cam.ui.gridBoxOf
import com.wotagei.cam.ui.gridColsCapOf
import com.wotagei.cam.ui.gridRowPitchPx
import com.wotagei.cam.ui.hudGridGap
import com.wotagei.cam.ui.spannedCells
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「编辑控件位置」同格交换的写侧守门（2026-10-02 取证 `.tmp/forensics/r2_rootcause.md` §二）。
 *
 * 缺陷：`cellDropOf` 第 5 步只校验"被挤的那组能否落回原格"，不校验"被拖那颗的目标格其跨度尾
 * 会不会压到第三颗的锚点"。真机形态：左 Dock 可见 {参考线 76px/span2、监看 60px/span1、闪光
 * 76px/span2}、行距 68px，把参考线拖到监看格 (0,2)，旧实现判"交换成立"写出
 * 参考线(0,2)+监看(0,0)+闪光(0,3)，而参考线的跨度尾 (0,3) 正是闪光的锚点 ⇒ 渲染层 `resolveCells`
 * 把闪光让位到 (1,3)（真机 x 123→223），存储串却仍 `P9:0.3`——"渲染格 ≠ 存储格"的自相矛盾表。
 *
 * 修法（已评审，本文件逐条钉住）：
 * 1. **判据 A**：被拖那颗落下去占的整块（含跨度尾）不许压到第三颗 ⇒ 压到就**拒绝交换**
 *    （`moved` 为空，别颗不动），被拖那颗退回"不挤人"的就近空格；
 * 2. **判据 B 升级**：原格承接的排除集从 `restBlocked` 扩成 `restBlocked ∪ selfBlock`
 *    （原格可能正落在 self 新块中间）；
 * 3. **freeCellNear 半笔**：候选判据从"锚点不在占位集"升级为"整块不与占位集相交"
 *    （span=1 时逐字同值 ⇒ 既有用例期望值一条没动）；
 * 4. **④ 支同族半笔**：真空格支（`occupant` 为空）也要查被拖那颗自己的整块，排除集
 *    **mate 感知**（只算非 self 非 mate 的住户）——不 mate 感知会把"拖回搭档合对"误杀（T10）；
 * 5. **T9 rows/cols 同源**：编辑页预览 `snapOf` 的两个落点支都改喂写表侧那两个形参
 *    （同容器支 `cellDropOf` 的 `cols = gridColsCapOf(zone)` + `rows = GridRowHardCap`；
 *    跨容器支 `freeCellNear` 的 `cols = gridColsCapOf(zone)` + `rows = GridRowHardCap`）
 *    ——与 `placeEntryAt` 逐字同一个常量/函数，否则带边界形态下会"预览画一格、松手落另一格"（同类隐患 4）。
 *
 * 本文件一律用**真实高度**（76/60/144/142/60px，与 `HudLayoutRowPitchTest` 那张表同源）——
 * 既有用例喂 0 是为了钉"跨度恒 1"下与 #74 逐字同值的账；本文件查的恰是跨度 > 1 的形态，
 * 喂 0 就没有判定力。每条测试的注释写明"退回旧实现时这条会红"。
 */
class HudLayoutSwapGuardTest {

    private fun e(p: CamPill) = HudEntry.of(p)

    /** 面板 720×1600、density 2.0（与 `HudLayoutRowPitchTest` 同一档）：竖 Dock 行距 = 68px = 34dp */
    private val density = 2f
    private val dockGapDp = hudGridGap(HudZone.LEFT).value
    private val rowPitchPx = gridRowPitchPx(1f, dockGapDp, density)

    /** 左 Dock 实测高（px，100% 档）：两枚 38dp 图标钮 76px（span2），监看/曲线胶囊 60px（span1） */
    private val leftHeights = mapOf(
        e(CamPill.REFLINE) to 76, e(CamPill.MONITOR) to 60,
        e(CamPill.CURVE) to 60, e(CamPill.FLASH) to 76
    )

    /** 右 Dock 实测高（合对护栏用）：配对块 144/142px（span3），四颗胶囊 60px */
    private val rightHeights = mapOf(
        e(CamPill.LEVEL) to 144, e(CamPill.VOLUME) to 142,
        e(CamPill.BT) to 60, e(CamPill.ZOOM) to 60, e(CamPill.FOCUS) to 60, e(CamPill.STAB) to 60
    )

    private val heights = leftHeights + rightHeights

    private fun planOf(vararg visible: CamPill) = HudGridPlan(
        visible = visible.map { e(it) }.toSet(),
        readoutPerRow = 3,
        rowPitchOf = { rowPitchPx },
        cellHeightOf = { heights[it] ?: 0 }
    )

    /**
     * 桥断言：写表之后，渲染解析（`gridItems`）逐颗 == 存储格（`cellsOf`）。
     * 这就是"渲染格 == 存储格"本身的落点——旧实现写出自相矛盾的表时，让位的那一颗在这里红。
     */
    private fun assertBridge(zone: HudZone, table: HudLayoutTable, plan: HudGridPlan, label: String) {
        val stored = table.cellsOf(zone)
        val rendered = table.gridItems(zone, plan).associate { it.entry to it.cell }
        for ((entry, cell) in rendered) {
            assertEquals("$label：$entry 渲染格 != 存储格（自相矛盾的表又回来了）", stored[entry], cell)
        }
    }

    // ---------- 一、判据 A：跨度尾压到第三颗 ⇒ 拒绝交换 ----------

    @Test
    fun swapRefusedWhenTheDraggedBlockTailWouldCoverAThirdAnchor() {
        // 真机复刻（取证 T1，本组最能证伪的一条）：左 Dock 可见 {参考线, 监看, 闪光}，
        // 拖前推导格 = 参考线 (0,0)（span2）、监看 (0,2)、闪光 (0,3)（span2）。
        // 把参考线拖到监看那一格 (0,2)：参考线的块 = (0,2)+(0,3)，尾巴 (0,3) 正是闪光的锚点。
        // 退回旧实现（无判据 A）时：参考线==>(0,2)、监看==>(0,0)、
        // 且渲染把闪光让到 (1,3) 而存储仍 (0,3) ⇒ 下面三条 + 桥断言同时红。
        val plan = planOf(CamPill.REFLINE, CamPill.MONITOR, CamPill.FLASH)
        val placed = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 1, GridCell(0, 2), plan)
        val stored = placed.cellsOf(HudZone.LEFT)
        // 被拖那颗：拒绝交换 ⇒ 退回就近空格，落点不许是目标格 (0,2)、也不许是它尾巴压到的那格 (0,3)
        val refline = stored.getValue(e(CamPill.REFLINE))
        assertFalse("参考线不许落进监看格 (0,2)（那就是旧实现的交换）", refline == GridCell(0, 2))
        assertFalse("参考线不许落进 (0,3)（闪光的锚点）", refline == GridCell(0, 3))
        // 别颗一律不动（#74 独立性：拒绝支一颗不改）
        assertEquals("监看仍钉在 (0,2)", GridCell(0, 2), stored.getValue(e(CamPill.MONITOR)))
        assertEquals("闪光存储格仍是 (0,3)", GridCell(0, 3), stored.getValue(e(CamPill.FLASH)))
        // 桥断言：渲染格逐颗 == 存储格（旧实现下闪光会渲染在 (1,3)、x 右移一个格距 100px）
        assertBridge(HudZone.LEFT, placed, plan, "拒绝交换")
    }

    @Test
    fun refusedSwapStillLandsOnTheNearestCellWithoutTouchingTheOccupant() {
        // 拒绝后的落点具名（取证 T2）：与上一条同一形态，直打 `cellDropOf` 并与写表对撞。
        // 住户表 = pinned 之后的 {参考线(0,0)、监看(0,2)、闪光(0,3)}。
        // 退回旧实现：dropped == (0,2) 且 moved == {监看→(0,0)}，两条同时红。
        val residents = mapOf(
            GridCell(0, 0) to listOf(e(CamPill.REFLINE)),
            GridCell(0, 2) to listOf(e(CamPill.MONITOR)),
            GridCell(0, 3) to listOf(e(CamPill.FLASH))
        )
        val drop = cellDropOf(
            wanted = GridCell(0, 2),
            origin = GridCell(0, 0),
            residents = residents,
            self = e(CamPill.REFLINE),
            mates = emptySet(),
            draggedHeightPx = 76,
            cellHeightPx = { heights[it] ?: 0 },
            cols = gridColsCapOf(HudZone.LEFT),
            rows = HudEntry.ALL.size,
            rowPitchPx = rowPitchPx
        )
        assertEquals(
            "拒绝交换：落点 = 曼哈顿最近的整块不撞格 (1,2)（d=1 行主序首个 fits 的候选）",
            GridCell(1, 2), drop.dropped
        )
        assertTrue("拒绝交换：别颗一律不动（moved 必须为空）", drop.moved.isEmpty())
        // 预览与写表同源：编辑页预览读同一个 cellDropOf ⇒ 写出的存储格必须等于直打的落点
        val plan = planOf(CamPill.REFLINE, CamPill.MONITOR, CamPill.FLASH)
        val placed = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 1, GridCell(0, 2), plan)
        assertEquals(
            "预览落点必须等于写表落点",
            drop.dropped,
            placed.cellsOf(HudZone.LEFT).getValue(e(CamPill.REFLINE))
        )
        assertBridge(HudZone.LEFT, placed, plan, "拒绝交换的落点")
    }

    // ---------- 一·补、第 ④ 支（真空格）的整块判据：跨度尾不许压第三颗的锚点 ----------

    @Test
    fun vacantCellIsRefusedWhenTheDraggedBlockTailWouldCoverAThirdAnchor() {
        // 第 ④ 支的同形取证（T11）：左 Dock 四颗默认表 ⇒ 参考线 (0,0)（span2）、监看 (0,2)、
        // 曲线 (0,3)、闪光 (0,4)（span2）。① 先把监看显式挪到第二列 (1,2) ⇒ (0,2) 变成**真空格**；
        // ② 再把参考线拖到 (0,2)：`occupant` 为空 ⇒ 第 ⑤/⑥ 步一行不跑，落点全由第 ④ 支裁决。
        // 退回没有 ④′ 那一笔的旧实现：只查"锚点不压人" ⇒ 直接落 (0,2)，跨度尾 (0,3) 正是曲线的锚点
        // ⇒ 渲染把曲线让到 (1,3)、存储仍 (0,3) ⇒ 下面几条 + 桥断言同时红。
        val plan = planOf(CamPill.REFLINE, CamPill.MONITOR, CamPill.CURVE, CamPill.FLASH)
        val setup = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.MONITOR), HudZone.LEFT, 1, GridCell(1, 2), plan)
        assertEquals(
            "铺底：监看显式挪到第二列 (1,2)，(0,2) 因此成为真空格",
            GridCell(1, 2), setup.cellsOf(HudZone.LEFT).getValue(e(CamPill.MONITOR))
        )
        val placed = setup.placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 0, GridCell(0, 2), plan)
        val stored = placed.cellsOf(HudZone.LEFT)
        // 被拖那颗：整块判据不过 ⇒ 退回就近空格，落点不许是真空格 (0,2)、也不许是它尾巴压到的那格 (0,3)
        val refline = stored.getValue(e(CamPill.REFLINE))
        assertFalse("参考线不许落进真空格 (0,2)（跨度尾会压住曲线的锚点）", refline == GridCell(0, 2))
        assertFalse("参考线不许落进 (0,3)（曲线的锚点）", refline == GridCell(0, 3))
        val reflineBlock = spannedCells(refline, cellRowSpan(leftHeights.getValue(e(CamPill.REFLINE)), rowPitchPx))
        assertTrue(
            "参考线的块 $reflineBlock 不许含别人的锚点",
            reflineBlock.none {
                it == stored.getValue(e(CamPill.MONITOR)) || it == stored.getValue(e(CamPill.CURVE)) ||
                    it == stored.getValue(e(CamPill.FLASH))
            }
        )
        // 别颗一律不动（#74 独立性：这一支没有 occupant，一个都不该被挪）
        assertEquals("监看仍钉在 (1,2)", GridCell(1, 2), stored.getValue(e(CamPill.MONITOR)))
        assertEquals("曲线存储格仍是 (0,3)", GridCell(0, 3), stored.getValue(e(CamPill.CURVE)))
        assertEquals("闪光存储格仍是 (0,4)", GridCell(0, 4), stored.getValue(e(CamPill.FLASH)))
        // 直打 cellDropOf：与上面同一个住户表，落点必须与写表逐字相同（预览 == 写表）。
        // 旧实现下 dropped == (0,2)、且渲染会把曲线挪到 (1,3) ⇒ 两条同时红。
        val drop = cellDropOf(
            wanted = GridCell(0, 2),
            origin = GridCell(0, 0),
            residents = mapOf(
                GridCell(0, 0) to listOf(e(CamPill.REFLINE)),
                GridCell(1, 2) to listOf(e(CamPill.MONITOR)),
                GridCell(0, 3) to listOf(e(CamPill.CURVE)),
                GridCell(0, 4) to listOf(e(CamPill.FLASH))
            ),
            self = e(CamPill.REFLINE),
            mates = emptySet(),
            draggedHeightPx = leftHeights.getValue(e(CamPill.REFLINE)),
            cellHeightPx = { heights[it] ?: 0 },
            cols = gridColsCapOf(HudZone.LEFT),
            rows = HudEntry.ALL.size,
            rowPitchPx = rowPitchPx
        )
        assertEquals("预览落点必须等于写表落点", refline, drop.dropped)
        assertTrue("真空格支没有 occupant ⇒ moved 必须为空", drop.moved.isEmpty())
        // 桥断言：渲染格逐颗 == 存储格（旧实现下曲线会渲染在 (1,3)、x 右移一个列距）
        assertBridge(HudZone.LEFT, placed, plan, "真空格支拒绝")
    }

    // ---------- 二、判据 B：原格不许落进 self 的新块 ----------

    @Test
    fun occupantIsNotParkedInsideTheDraggedBlock() {
        // 判据 B（取证 T3）：原格正落在 self 新块中间。可见 {参考线, 监看}：
        // ① 先把参考线显式钉到 (0,3)（span2 ⇒ 块 (0,3)+(0,4)），监看显式在 (0,2)；
        // ② 把参考线拖回 (0,2)——它的"原格" (0,3) 正落在新块 (0,2)+(0,3) 中间。
        // 退回旧判据 B（排除集只有 restBlocked={}）时 originHosts=true ⇒ 监看被写进 (0,3)
        // = 参考线新块里，渲染侧 resolveCells 又把它挪走 ⇒ 桥断言红。
        val plan = planOf(CamPill.REFLINE, CamPill.MONITOR)
        val setup = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 0, GridCell(0, 3), plan)
        assertEquals("铺底：参考线显式在 (0,3)", GridCell(0, 3), setup.cellsOf(HudZone.LEFT).getValue(e(CamPill.REFLINE)))
        assertEquals("铺底：监看显式在 (0,2)", GridCell(0, 2), setup.cellsOf(HudZone.LEFT).getValue(e(CamPill.MONITOR)))
        val placed = setup.placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 0, GridCell(0, 2), plan)
        val stored = placed.cellsOf(HudZone.LEFT)
        assertEquals("被拖那颗拿到目标格", GridCell(0, 2), stored.getValue(e(CamPill.REFLINE)))
        val monitor = stored.getValue(e(CamPill.MONITOR))
        assertFalse(
            "监看不许被写进参考线的新块 (0,2)/(0,3)（旧判据 B 会写进 (0,3)）",
            monitor == GridCell(0, 2) || monitor == GridCell(0, 3)
        )
        // 监看的整块与参考线的整块不相交
        val monitorBlock = spannedCells(monitor, cellRowSpan(leftHeights.getValue(e(CamPill.MONITOR)), rowPitchPx))
        assertTrue(
            "监看的块 $monitorBlock 与参考线的块 (0,2)+(0,3) 不许相交",
            monitorBlock.none { it == GridCell(0, 2) || it == GridCell(0, 3) }
        )
        assertBridge(HudZone.LEFT, placed, plan, "判据 B")
    }

    // ---------- 三、freeCellNear 的半笔：候选整块不许压人 ----------

    @Test
    fun freeCellNearNeverReturnsABlockThatCoversAnAnchor() {
        // 直打（取证 T4）：wanted (0,2)、占位 {(0,3)}、跨度 2（76px / 68px）。
        // 退回旧实现（只查锚点）时：(0,2) 不在占位集 ⇒ 直接返回 (0,2)，其块尾 (0,3) 压住别人 ⇒ 两条都红。
        val occupied = setOf(GridCell(0, 3))
        val got = freeCellNear(GridCell(0, 2), occupied, cols = 2, rows = 6, contentHeightPx = 76, rowPitchPx = 68)
        assertEquals("d=1 行主序第一个整块不撞的格 = (0,1)", GridCell(0, 1), got)
        assertTrue(
            "返回格的整块 ${spannedCells(got, 2)} 不许与占位集相交",
            spannedCells(got, 2).none { it in occupied }
        )
        // span=1 时与旧式逐字同值（"既有用例期望值一条不改"的保证）：同一入参、高量不到（0 ⇒ 跨度 1）
        // ⇒ (0,2) 锚点不在占位集 ⇒ 原地返回，与 #75 那一版逐字同值
        assertEquals(
            GridCell(0, 2),
            freeCellNear(GridCell(0, 2), occupied, cols = 2, rows = 6, contentHeightPx = 0, rowPitchPx = 68)
        )
        // 成对对照（与 HudLayoutRowPitchTest.clampAndFreeCellKeepTheWholeBlockInsideTheBand 同一手法）：
        // 跨度 3 的块在第 4 档带里也只许落整块装得下的候选——(0,3) 出带被拒，退到 (0,1)
        assertEquals(
            GridCell(0, 1),
            freeCellNear(GridCell(0, 3), setOf(GridCell(0, 0)), 1, 4, 144, 68)
        )
    }

    // ---------- 四、护栏：正常交换与合对不许被误杀 ----------

    @Test
    fun swapStillHappensWhenTheDraggedBlockTailIsClear() {
        // 护栏（取证 T6）：块尾不压人时交换必须照旧发生——判据 A 不许写成"永久拒绝"。
        // 可见 {参考线, 监看, 闪光}：先把闪光显式挪到 (0,5)（块 = (0,5)+(0,6)），(0,3)/(0,4) 空着
        // ⇒ 拖参考线到监看格 (0,2) 时块尾 (0,3) 不压任何人 ⇒ 交换成立。
        // 反证：把判据 A 改成"总是拒绝" ⇒ 参考线落到 (1,2)、监看停在 (0,2)，两条同时红；
        // 把 spannedCells 误写成"整块不许与任何人重叠（含自己原格）"也会在这里红。
        val plan = planOf(CamPill.REFLINE, CamPill.MONITOR, CamPill.FLASH)
        val setup = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.FLASH), HudZone.LEFT, 2, GridCell(0, 5), plan)
        assertEquals("铺底：闪光显式在 (0,5)", GridCell(0, 5), setup.cellsOf(HudZone.LEFT).getValue(e(CamPill.FLASH)))
        val placed = setup.placeEntryAt(e(CamPill.REFLINE), HudZone.LEFT, 1, GridCell(0, 2), plan)
        val stored = placed.cellsOf(HudZone.LEFT)
        assertEquals("交换成立：参考线拿到目标格", GridCell(0, 2), stored.getValue(e(CamPill.REFLINE)))
        assertEquals("交换成立：监看让到参考线的原格 (0,0)", GridCell(0, 0), stored.getValue(e(CamPill.MONITOR)))
        assertEquals("第三颗（闪光）一字不动", GridCell(0, 5), stored.getValue(e(CamPill.FLASH)))
        assertBridge(HudZone.LEFT, placed, plan, "正常交换")
    }

    @Test
    fun droppingAMateBackStillReformsThePairAfterTheGate() {
        // 护栏（取证 T10）："拖回搭档那一格 = 合对"走 cellDropOf 第 4 步（occupant 为空那一支），
        // 判据 A/B 根本不参与——本条钉住新判据没有把它误杀。真实高：配对块 span3。
        // 退回旧实现它本来就绿（护栏）；红的方向 = 有人把整块判据误写进第 4 步（occupant 为空那一支）。
        val plan = planOf(
            CamPill.LEVEL, CamPill.VOLUME, CamPill.BT, CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB
        )
        val broken = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, GridCell(0, 7), plan)
        assertEquals("铺底：音量表拆到 (0,7)", GridCell(0, 7), broken.cellsOf(HudZone.RIGHT).getValue(e(CamPill.VOLUME)))
        val merged = broken.placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, GridCell(0, 0), plan)
        val stored = merged.cellsOf(HudZone.RIGHT)
        assertEquals("合回去：音量表回配对格 (0,0)", GridCell(0, 0), stored.getValue(e(CamPill.VOLUME)))
        assertEquals("姿态仪没动", GridCell(0, 0), stored.getValue(e(CamPill.LEVEL)))
        assertEquals(GridCell(0, 3), stored.getValue(e(CamPill.BT)))
        assertEquals(GridCell(0, 4), stored.getValue(e(CamPill.ZOOM)))
        assertEquals(GridCell(0, 5), stored.getValue(e(CamPill.FOCUS)))
        assertEquals(GridCell(0, 6), stored.getValue(e(CamPill.STAB)))
        assertBridge(HudZone.RIGHT, merged, plan, "合对")
    }

    // ---------- 五、T9：预览侧与写表侧的 rows 必须同源（r2_rootcause §二 同类隐患 4） ----------

    /**
     * 取某次调用的整个参数表（按圆括号配平）。入参先用 [KotlinSourceScan.codeOnly] 遮蔽过，
     * 串与注释里不会有没配平的圆括号，所以这里的计数是安全的。
     */
    private fun callArgsOf(text: String, call: String): String {
        val at = text.indexOf(call)
        assertTrue("源码里找不到 $call 调用", at >= 0)
        var i = text.indexOf('(', at)
        assertTrue("$call 之后没有参数表", i >= 0)
        var depth = 0
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(at, i + 1)
                }
            }
            i++
        }
        throw AssertionError("$call 的参数表没有配平——守卫不能在没有输入的情况下算通过")
    }

    @Test
    fun previewAndWritePathReadTheSameRowCapSoTheBandEdgeCannotSplitThem() {
        // T9（取证 §二 同类隐患 4）：编辑页预览 `snapOf` 的同容器支原先喂 `rows = box.rows`
        // （可放带内档数），写表侧 `placeEntryAt` 喂 `rows = GridRowHardCap`（存储层上限）——
        // 两只不同的盒子，`freeCellNear` 的兜底搜索在带边界上给出不同落点 ⇒
        // "预览画一格、松手落另一格"。修法：预览侧改喂同一个 GridRowHardCap（写表侧一字未改）。
        //
        // 本条分两半：
        // ① 行为证据——造一个**带边界**输入（带盒 2×2 被占满、目标格的跨度尾压在带外那颗的锚点上），
        //    证明两个 rows 源在这上面**真的**分叉。这一条是"同源不是可选"的证据，也是本条判出力的来源；
        // ② 源码钉扎——`snapOf` 是 `HudLayoutEditorScreen` 里的**局部函数**，要吃 dp 矩形/密度/指针
        //    一整套实测入参，JVM 侧构造不齐（这是 T9 的阻塞点，如实记账），所以钉"喂的是哪个常量"。
        val box = gridBoxOf(
            zone = HudZone.LEFT,
            pitchXDp = 86,
            pitchYDp = 34,
            bandHeightDp = 68,
            roomWidthDp = 100,
            reservedRows = 1,
            reservedCols = 0,
            rowGrowth = GridRowGrowthRows,
            colGrowth = 0
        )
        assertEquals("本用例的前提：带盒 = 2 列 × 2 行", 2, box.cols)
        assertEquals("本用例的前提：带盒 = 2 列 × 2 行", 2, box.rows)
        assertTrue(
            "本用例的前提：带内档数必须真的少于写表侧的上限，否则本条没有判出力",
            box.rows < HudEntry.ALL.size
        )

        // 带内 2×2 被两颗 span2 占满；(0,3) 另住一颗 ⇒ 目标 (0,2) 的跨度尾 (0,3) 正压在它身上；
        // 闪光是自己（span2）住在 (0,7)，被拖去 (0,2)
        val residents = mapOf(
            GridCell(0, 0) to listOf(e(CamPill.REFLINE)),
            GridCell(1, 0) to listOf(e(CamPill.MONITOR)),
            GridCell(0, 3) to listOf(e(CamPill.CURVE)),
            GridCell(0, 7) to listOf(e(CamPill.FLASH))
        )
        fun dropWith(rows: Int) = cellDropOf(
            wanted = GridCell(0, 2),
            origin = GridCell(0, 7),
            residents = residents,
            self = e(CamPill.FLASH),
            mates = emptySet(),
            draggedHeightPx = leftHeights.getValue(e(CamPill.FLASH)),
            cellHeightPx = { heights[it] ?: 0 },
            cols = gridColsCapOf(HudZone.LEFT),
            rows = rows,
            rowPitchPx = rowPitchPx
        )
        // 写表侧（上限 20 行）：(0,2) 的块压 (0,3) ⇒ 就近退到 (1,2)
        assertEquals(
            "写表侧落点 = 行主序 d=1 首个整块不撞的格 (1,2)",
            GridCell(1, 2), dropWith(HudEntry.ALL.size).dropped
        )
        // 带盒（2 行）里一个整块空格都没有 ⇒ 兜底搜索只能把被钳进带内的 wanted (0,1) 原样还回去
        assertEquals(
            "带盒侧的落点是「搜索放弃」后还回来的 (0,1)，与写表侧分叉",
            GridCell(0, 1), dropWith(box.rows).dropped
        )
        assertTrue(
            "两个 rows 源在带边界上必须分叉（若哪天上限被改得与带盒一样高，本条自己红）",
            dropWith(box.rows).dropped != dropWith(HudEntry.ALL.size).dropped
        )
        // 源码钉扎：同容器支必须喂 GridRowHardCap，不许回退成 box.rows
        val masked = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
        val sameZoneCall = callArgsOf(KotlinSourceScan.bodyOf(masked, "snapOf"), "cellDropOf")
        assertTrue(
            "snapOf 的 cellDropOf 同容器支必须喂 rows = GridRowHardCap（与写表侧同一个常量）",
            sameZoneCall.contains("rows = GridRowHardCap")
        )
        assertFalse(
            "snapOf 的 cellDropOf 同容器支不许喂 box.rows（可放带内档数）——那正是 T9 抓到的不同源",
            sameZoneCall.contains("box.rows")
        )
    }

    /**
     * T9 的孪生：**跨容器支**也必须与写表侧 `placeEntryAt` 跨容器支逐字同源。
     *
     * 上一波（T9 修复）只统一了同容器支的 `rows`，跨容器支仍喂 `box.cols` / `box.rows`。而写表侧
     * 跨容器支喂的是 `gridColsCapOf(target)` / `GridRowHardCap` ⇒ 「带边界形态 + 跨容器」这一象限里
     * 两页**仍然**分叉：`box.rows` 是可放带内档数、`box.cols` 对读数块还会被可用内宽再夹一刀
     * （[gridBoxOf] 的 `roomWidthDp / pitchXDp`），写表侧拿不到带高、只有存储层那两个上限。
     * 分叉只在 `freeCellNear` 的兜底搜索上显形（`wanted` 那一格整块放得下时两支都原地返回），
     * 所以本条先造一个"跨容器 + 带内整块空格没有 + 跨度尾压人"的输入，再用源码钉扎钉住形参。
     *
     * 证伪点（各能单独抓一类回退）：
     * ① 行为证据——`freeCellNear` 用带盒（`box`）与用存储层上限**真的**给出两个答案；
     * ② 源码钉扎——预览侧跨容器支必须喂 `gridColsCapOf(zone)` 与 `GridRowHardCap`，
     *    改回 `box.cols`/`box.rows` 时本条红；写表侧那两个常量是常量本身，无第二处可退。
     */
    @Test
    fun previewCrossZoneDropReadsTheWritePathCapsSoTheBandEdgeCannotSplitThem() {
        // 带盒 2×2（两枚竖 Dock 的带内档数），四颗把它占满；(0,3) 另住一颗
        // ⇒ 目标 (0,2) 的跨度尾 (0,3) 正压在它身上；被拖那颗住 (0,7)，跨容器拖来 (0,2)
        val box = gridBoxOf(
            zone = HudZone.RIGHT,
            pitchXDp = 86,
            pitchYDp = 34,
            bandHeightDp = 68,
            roomWidthDp = 100,
            reservedRows = 1,
            reservedCols = 0,
            rowGrowth = GridRowGrowthRows,
            colGrowth = 0
        )
        assertEquals("本用例的前提：带盒 = 2 列 × 2 行", 2, box.cols)
        assertEquals("本用例的前提：带盒 = 2 列 × 2 行", 2, box.rows)
        assertTrue(
            "本用例的前提：带内档数必须真的少于写表侧的上限，否则本条没有判出力",
            box.rows < HudEntry.ALL.size
        )
        assertTrue(
            "本用例的前提：竖 Dock 的带盒列数必须与存储层列上限同值，否则分叉都归因不到 rows 上",
            box.cols == gridColsCapOf(HudZone.RIGHT)
        )

        val occupied = setOf(
            GridCell(0, 0), GridCell(1, 0),
            GridCell(0, 1), GridCell(1, 1),
            GridCell(0, 3)
        )
        // 真实高 144px ⇒ 跨度 3（与右 Dock 配对块同一档）：少了跨度，`wanted` (0,2) 自己就整块放得下，
        // 两支都原地返回，就显不出两只盒子的差别（这一条正是"跨容器分叉只在兜底搜索上现形"的注脚）
        fun dropWith(cols: Int, rows: Int) = freeCellNear(
            wanted = GridCell(0, 2),
            occupied = occupied,
            cols = cols,
            rows = rows,
            contentHeightPx = rightHeights.getValue(e(CamPill.LEVEL)),
            rowPitchPx = rowPitchPx
        )
        // 写表侧（存储层上限 2 列 × 20 行）：(0,2) 的块压 (0,3) ⇒ 行主序 d=1 里第一个不撞的格 (1,2)
        assertEquals(
            "写表侧落点 = 行主序 d=1 首个整块不撞的格 (1,2)",
            GridCell(1, 2), dropWith(gridColsCapOf(HudZone.RIGHT), HudEntry.ALL.size)
        )
        // 带盒（2 列 × 2 行）：跨度 3 的块在 2 档带里 lastRow 被夹成 0 ⇒ 只有第 0 档可落，而两颗已占
        // ⇒ 兜底搜索放弃，把钳回带内的 wanted (0,1) 原样还回来
        assertEquals(
            "带盒侧落点是「搜索放弃」后还回来的 (0,1)，与写表侧分叉——这就是跨容器支的分叉形状",
            GridCell(0, 1), dropWith(box.cols, box.rows)
        )
        assertTrue(
            "两个（cols, rows）源在带边界上必须分叉（若哪天上限被改得与带盒一样高，本条自己红）",
            dropWith(gridColsCapOf(HudZone.RIGHT), HudEntry.ALL.size) !=
                dropWith(box.cols, box.rows)
        )
        // 源码钉扎：预览侧跨容器支必须喂写表侧那两个形参，不许回退成 box.cols / box.rows
        val masked = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
        val snapBody = KotlinSourceScan.bodyOf(masked, "snapOf")
        val crossZoneCall = callArgsOf(snapBody, "freeCellNear")
        assertTrue(
            "snapOf 的 freeCellNear 跨容器支必须喂 cols = gridColsCapOf(zone)（与写表侧同一个函数）",
            crossZoneCall.contains("gridColsCapOf(zone)")
        )
        assertTrue(
            "snapOf 的 freeCellNear 跨容器支必须喂 rows = GridRowHardCap（与写表侧同一个常量）",
            crossZoneCall.contains("GridRowHardCap")
        )
        assertFalse(
            "snapOf 的 freeCellNear 跨容器支不许喂 box.cols / box.rows——那就是本条要抓的退化",
            crossZoneCall.contains("box.cols") || crossZoneCall.contains("box.rows")
        )
        // 写表侧（`HudLayoutTable.placeEntryAt`）必须仍是 GridRowHardCap / gridColsCapOf(target)：
        // 上一条只钉了预览，这里钉住"对齐"是双向同源，不是把写表侧拉到带盒
        val writeMasked = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayout.kt"))
        val writeCrossCall = callArgsOf(KotlinSourceScan.bodyOf(writeMasked, "placeEntryAt"), "freeCellNear")
        assertTrue("写表侧跨容器支必须仍喂 GridRowHardCap", writeCrossCall.contains("GridRowHardCap"))
        assertTrue(
            "写表侧跨容器支必须仍喂 gridColsCapOf(target)",
            writeCrossCall.contains("gridColsCapOf(target)")
        )
    }

    // ---------- 六、T9 的 cols 侧孪生：同容器支的 cols 也必须与写表侧同源 ----------

    /**
     * T9 的 cols 侧孪生：**同容器支**的 `cols` 也必须与写表侧 `placeEntryAt` 同容器支逐字同源。
     *
     * 上一波（T9 修复）只统一了同容器支的 `rows`，`cols` 仍喂 `box.cols`。而写表侧同容器支喂的是
     * `gridColsCapOf(target)`——读数块那一枚 `box.cols` 会被**可用内宽**再夹一刀
     * （[gridBoxOf] 的 `roomWidthDp / pitchXDp`，外套预留列数），横屏"一行两颗"那一档带盒只剩 2 列，
     * 写表侧拿不到带高、只有存储层的 3 列上限。分叉只在这两处显形：
     * ① `cellDropOf` 的 `originInBox`（判据 B）：原格在第 3 列时带盒判它"出盒" ⇒ 交换被降级成
     *    "占位者就近让位"，写表侧却照旧交换、把占位者送回原格；
     * ② 兜底/让位那几步 `freeCellNear` 的**可搜索列宽**。
     * （`target` 的列钳制那一处是同值的：`wanted` 已被 `clampCellToBox` 钳进 `box`，列号到不了带外。）
     *
     * 证伪点：
     * ① 行为证据——同一次落点用带盒（2 列）与用存储层上限（3 列）给出的 `moved` **真的**不同；
     * ② 写表侧桥——真实 `placeEntryAt` 走同容器支，证明松手后格主确实被送回第 3 列的原格；
     * ③ 源码钉扎——预览侧同容器支必须喂 `cols = gridColsCapOf(zone)`，改回 `box.cols` 时本条红。
     */
    @Test
    fun previewSameZoneDropReadsTheWritePathColCapSoTheReadoutBandCannotSplitThem() {
        val cap = gridColsCapOf(HudZone.READOUT)
        // 带盒：读数块可用内宽装得下 3 列，但这一档只预留 2 列 ⇒ 带盒 cols = 2 < 存储层上限 3
        val box = gridBoxOf(
            zone = HudZone.READOUT,
            pitchXDp = 86,
            pitchYDp = 34,
            bandHeightDp = 68,
            roomWidthDp = 258,
            reservedRows = 0,
            reservedCols = 2,
            rowGrowth = GridRowGrowthRows,
            colGrowth = 0
        )
        assertEquals("本用例的前提：读数块带盒 = 2 列", 2, box.cols)
        assertTrue(
            "本用例的前提：带盒列数必须真的少于写表侧的上限，否则本条没有判出力",
            box.cols < cap
        )

        val iso = HudEntry.of(HudItem.ISO)
        val ev = HudEntry.of(HudItem.EV)
        // 同容器支的住户表：被拖那颗（ISO）原格在第 3 列，目标格 (1,1) 的格主是 EV
        val residents = mapOf(
            GridCell(2, 1) to listOf(iso),
            GridCell(1, 1) to listOf(ev)
        )
        fun dropWith(cols: Int) = cellDropOf(
            wanted = GridCell(1, 1),
            origin = GridCell(2, 1),
            residents = residents,
            self = iso,
            mates = emptySet(),
            draggedHeightPx = 60,
            cellHeightPx = { heights[it] ?: 0 },
            cols = cols,
            rows = HudEntry.ALL.size,
            rowPitchPx = rowPitchPx
        )
        // 写表侧（上限 3 列）：原格 (2,1) 在盒内 ⇒ 交换成立，EV 被送回原格
        assertEquals("写表侧：被拖那颗拿目标格", GridCell(1, 1), dropWith(cap).dropped)
        assertEquals(
            "写表侧：格主被送回第 3 列的原格 (2,1)",
            GridCell(2, 1), dropWith(cap).moved.getValue(ev)
        )
        // 带盒（2 列）：原格被判定"出盒" ⇒ 判据 B 不成立，EV 被挤到附近的空格 (1,0)
        assertEquals("带盒侧：被拖那颗仍拿目标格（分叉只在格主去哪上现形）", GridCell(1, 1), dropWith(box.cols).dropped)
        assertEquals(
            "带盒侧：原格出盒 ⇒ 格主被挤到 (1,0)，与写表侧分叉",
            GridCell(1, 0), dropWith(box.cols).moved.getValue(ev)
        )
        assertTrue(
            "两个 cols 源在读数块带边界上必须分叉（若哪天府上限被改成 2 列，本条自己红）",
            dropWith(cap).moved != dropWith(box.cols).moved
        )

        // 写表侧桥：真实 placeEntryAt 走同容器支（cols = gridColsCapOf(target) = 3），
        // 证明松手后格主确实回到第 3 列——预览若还喂 box.cols，画的就是 (1,0) 而表里存的是 (2,1)
        val plan = HudGridPlan(
            visible = setOf(iso, ev),
            readoutPerRow = 3,
            rowPitchOf = { rowPitchPx },
            cellHeightOf = { heights[it] ?: 0 }
        )
        val setup = HudLayoutTable.default()
            .placeEntryAt(ev, HudZone.READOUT, 1, GridCell(1, 1), plan)
            .placeEntryAt(iso, HudZone.READOUT, 0, GridCell(2, 1), plan)
        assertEquals("铺底：ISO 显式钉在第 3 列", GridCell(2, 1), setup.cellsOf(HudZone.READOUT).getValue(iso))
        assertEquals("铺底：EV 显式在目标格 (1,1)", GridCell(1, 1), setup.cellsOf(HudZone.READOUT).getValue(ev))
        val swapped = setup.placeEntryAt(iso, HudZone.READOUT, 0, GridCell(1, 1), plan)
        assertEquals("交换成立：ISO 拿到目标格", GridCell(1, 1), swapped.cellsOf(HudZone.READOUT).getValue(iso))
        assertEquals(
            "写表侧把格主送回 ISO 的原格 (2,1)——这正是预览必须画出来的那一格",
            GridCell(2, 1), swapped.cellsOf(HudZone.READOUT).getValue(ev)
        )

        // 源码钉扎：预览侧同容器支必须喂 gridColsCapOf(zone)，不许回退成 box.cols
        val masked = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
        val sameZoneCall = callArgsOf(KotlinSourceScan.bodyOf(masked, "snapOf"), "cellDropOf")
        assertTrue(
            "snapOf 的 cellDropOf 同容器支必须喂 cols = gridColsCapOf(zone)（与写表侧同一个函数）",
            sameZoneCall.contains("cols = gridColsCapOf(zone)")
        )
        assertFalse(
            "snapOf 的 cellDropOf 同容器支不许喂 box.cols——读数块带盒会被可用内宽夹到 2 列，那就是本条要抓的退化",
            sameZoneCall.contains("box.cols")
        )
        // 写表侧同容器支必须仍是 gridColsCapOf(target)（"对齐"是双向同源，不是把写表侧拉到带盒）
        val writeMasked = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayout.kt"))
        val writeSameCall = callArgsOf(KotlinSourceScan.bodyOf(writeMasked, "placeEntryAt"), "cellDropOf")
        assertTrue(
            "写表侧同容器支必须仍喂 cols = gridColsCapOf(target)",
            writeSameCall.contains("cols = gridColsCapOf(target)")
        )
    }
}
