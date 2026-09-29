package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.GridBox
import com.wotagei.cam.ui.GridCell
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudGridItem
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudPointPx
import com.wotagei.cam.ui.HudSizePx
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.blockingCells
import com.wotagei.cam.ui.cellChildLeftsPx
import com.wotagei.cam.ui.cellContentWidthPx
import com.wotagei.cam.ui.cellGroupMatesOf
import com.wotagei.cam.ui.cellOffsetPx
import com.wotagei.cam.ui.cellPlaceOffsetPx
import com.wotagei.cam.ui.clampCellToBox
import com.wotagei.cam.ui.clampStoredCell
import com.wotagei.cam.ui.defaultCellsOf
import com.wotagei.cam.ui.entryHitIndex
import com.wotagei.cam.ui.gridPitchOf
import com.wotagei.cam.ui.gridPitchPx
import com.wotagei.cam.ui.gridPlacementOf
import com.wotagei.cam.ui.gridSizePx
import com.wotagei.cam.ui.hudRowGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务 #74 的**后果修复**：配对那一对（S2-2B「姿态仪 + 音量表」）从"相邻两格"改成"同一枚格子里的两个条目"。
 *
 * 派单给的方法论（格子间距 = 容器内最宽那颗）让右 Dock 底板从 ≈94dp 撑到 ≈120dp，方向与用户这轮
 * 「左dock栏还是太宽了」正好相反。本文件钉的是修复后的三件事：
 * 1. **宽度账**：pitch 由"最宽那枚**格子**"推出，配对格 86dp ⇒ 底板 94dp
 *    （[thePairedCellIsWidthOwnerAndTheBoardIsNinetyFour] 与
 *    [gridPlacementOfTheDefaultRightDockMeasuresTheNinetyFourBoard]）；
 * 2. **独立性**：拆对、合对、跨组撞格三条都有逐颗手算的格子/坐标清单（另外四条用例）；
 * 3. **配对格被点中时该搬哪一颗**：规则是纯函数 [entryHitIndex]，不靠 Compose 的矩形回报顺序。
 *
 * 恒等式与真断言的分工（AGENTS.md 那条）：所有期望值都是**人能手算**的 px/dp 与逐颗点名的格子，
 * 没有任何一条是"拿实现的输出与实现自己比"。
 */
class HudLayoutPairCellTest {

    private val planAll = HudGridPlan(HudEntry.ALL.toSet(), readoutPerRow = 3)
    private fun e(p: CamPill) = HudEntry.of(p)
    private fun h(i: HudItem) = HudEntry.of(i)

    /** 手摆格长（与 [HudLayoutGridTest] 同一档）：期望值是口算得出来的 px */
    private val pitch = HudSizePx(50, 30)

    private fun rightCells(table: HudLayoutTable = HudLayoutTable.default()) =
        table.gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }

    @Test
    fun packedPairSharesOneCellAndTheRightDockHasNoSecondColumn() {
        val def = HudLayoutTable.defaultOrderOf(HudZone.RIGHT)
        // ① 配对判据仍只有 hudRowGroups 那一把尺子（本批不许写第二份"谁与谁算一对"的判据）
        assertEquals(
            listOf(e(CamPill.LEVEL), e(CamPill.VOLUME)),
            hudRowGroups(HudZone.RIGHT, def, perRow = 1).first()
        )
        // ② 默认推导把这一组落成**同一枚格子**，其余每颗各占一行；整容器只有一列
        val derived = defaultCellsOf(HudZone.RIGHT, def, perRow = 1)
        assertEquals(GridCell(0, 0), derived[e(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), derived[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 1), derived[e(CamPill.BT)])
        assertEquals(GridCell(0, 4), derived[e(CamPill.STAB)])
        assertEquals(setOf(0), derived.values.map { it.col }.toSet())
        // ③ 同组搭档：配对两颗互为搭档，其余颗谁都不是；读数块恒空（每颗各占一列，行为一条没变）
        assertEquals(setOf(e(CamPill.VOLUME)), cellGroupMatesOf(derived, e(CamPill.LEVEL)))
        assertTrue(cellGroupMatesOf(derived, e(CamPill.BT)).isEmpty())
        val readout = defaultCellsOf(HudZone.READOUT, HudLayoutTable.defaultOrderOf(HudZone.READOUT), perRow = 3)
        for (item in HudItem.ALL) {
            assertTrue("读数块不许出现共格：${item.name}", cellGroupMatesOf(readout, h(item)).isEmpty())
        }
        // 读数块的默认格逐颗点名（与 #74 改前逐字相同，这一条就是"非网格档不许变"的看门狗）
        assertEquals(GridCell(2, 0), readout[h(HudItem.BITRATE)])
        assertEquals(GridCell(0, 1), readout[h(HudItem.ISO)])
        assertEquals(GridCell(2, 1), readout[h(HudItem.WB)])
        // ④ 解析结果里那一格确实住两颗，且**格内左右次序 = 清单次序**（渲染层与命中裁决共读这一个序）
        val items = HudLayoutTable.default().gridItems(HudZone.RIGHT, planAll)
        assertEquals(
            listOf(e(CamPill.LEVEL), e(CamPill.VOLUME)),
            items.filter { it.cell == GridCell(0, 0) }.map { it.entry }
        )
        assertEquals("六颗只占五格（这一条红就说明配对被拆成了两列）", 5, items.map { it.cell }.distinct().size)
    }

    @Test
    fun breakingThePairedCellLeavesTheAttitudeAndAllOtherCellsFrozen() {
        // 场景一：把配对里的**音量表**从配对格拖到下面空着的第 5 行。
        // 期望值全部手算（格长按 50×30 px 手摆）：钉格子的范围只有右 Dock 一枚；配对两颗被钉进同一格
        // (0,0)，被拖那颗拿到用户要的 (0,5)（那一格本来就是空的，不该被就近让位改道），其余四颗原地不动
        val before = rightCells()
        val table = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, GridCell(0, 5), planAll)
        val after = rightCells(table)
        val px = after.mapValues { (_, c) -> cellOffsetPx(c, pitch) }
        assertEquals("姿态仪留在配对格 (0,0)，不许因为同伴走了被推去别处", GridCell(0, 0), after[e(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), table.cellsOf(HudZone.RIGHT)[e(CamPill.LEVEL)])
        assertEquals(HudPointPx(0, 0), px[e(CamPill.LEVEL)])
        assertEquals("音量表必须落在用户要的那一格", GridCell(0, 5), after[e(CamPill.VOLUME)])
        assertEquals(HudPointPx(0, 150), px[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 1), after[e(CamPill.BT)])
        assertEquals(HudPointPx(0, 30), px[e(CamPill.BT)])
        assertEquals(GridCell(0, 2), after[e(CamPill.ZOOM)])
        assertEquals(HudPointPx(0, 60), px[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 3), after[e(CamPill.FOCUS)])
        assertEquals(HudPointPx(0, 90), px[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 4), after[e(CamPill.STAB)])
        assertEquals(HudPointPx(0, 120), px[e(CamPill.STAB)])
        // 拆完之后仍然**只有一列**：没有一颗被顺手推去第 1 列（那正是把底板撑宽的机制）
        assertEquals(setOf(0), after.values.map { it.col }.toSet())
        // 差分断言（与上面的手算值互补）：除被拖那颗之外逐颗一字未变
        assertEquals(before.filterKeys { it != e(CamPill.VOLUME) }, after.filterKeys { it != e(CamPill.VOLUME) })
    }

    @Test
    fun movingZoomToAnotherRowLeavesThePairedCellAndEveryOtherEntryFrozen() {
        // 场景二：把**变焦**拖到下面空着的第 5 行。配对那一格两颗必须还在 (0,0)，
        // 其余各颗（蓝牙/对焦/防抖）一格都不许动——测的是"动一颗不动别颗"对配对格同样成立
        val before = rightCells()
        val after = rightCells(
            HudLayoutTable.default().placeEntryAt(e(CamPill.ZOOM), HudZone.RIGHT, 2, GridCell(0, 5), planAll)
        )
        val px = after.mapValues { (_, c) -> cellOffsetPx(c, pitch) }
        assertEquals(GridCell(0, 0), after[e(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), after[e(CamPill.VOLUME)])
        assertEquals(HudPointPx(0, 0), px[e(CamPill.LEVEL)])
        assertEquals(HudPointPx(0, 0), px[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 1), after[e(CamPill.BT)])
        assertEquals(HudPointPx(0, 30), px[e(CamPill.BT)])
        assertEquals(GridCell(0, 3), after[e(CamPill.FOCUS)])
        assertEquals(HudPointPx(0, 90), px[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 4), after[e(CamPill.STAB)])
        assertEquals(HudPointPx(0, 120), px[e(CamPill.STAB)])
        assertEquals(GridCell(0, 5), after[e(CamPill.ZOOM)])
        assertEquals(HudPointPx(0, 150), px[e(CamPill.ZOOM)])
        assertEquals(setOf(0), after.values.map { it.col }.toSet())
        assertEquals(before.filterKeys { it != e(CamPill.ZOOM) }, after.filterKeys { it != e(CamPill.ZOOM) })
    }

    @Test
    fun onlyCellMatesMayShareACellStrangersGetDisplaced() {
        // "一格多颗"之后「这格被占了」的新口径（[blockingCells]）：**同组搭档不算占，跨组算占**。
        // 把变焦硬拖到配对那一格 (0,0) ⇒ 配对两颗一颗都不许动，变焦就近让到 (1,0)
        //（曼哈顿距离 1 的两个候选里 (0,1) 被蓝牙占着，所以唯一解是 (1,0)）
        val after = rightCells(
            HudLayoutTable.default().placeEntryAt(e(CamPill.ZOOM), HudZone.RIGHT, 2, GridCell(0, 0), planAll)
        )
        assertEquals("配对那颗不许被挤走", GridCell(0, 0), after[e(CamPill.LEVEL)])
        assertEquals("配对那颗不许被挤走", GridCell(0, 0), after[e(CamPill.VOLUME)])
        assertEquals("跨组撞格 ⇒ 拖过来那颗就近让到隔壁空格", GridCell(1, 0), after[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 1), after[e(CamPill.BT)])
        assertEquals(GridCell(0, 3), after[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 4), after[e(CamPill.STAB)])
        // 占用判据本身逐格点名（手摆住户表，不经过任何实现路径）
        val residents = mapOf(
            GridCell(0, 0) to listOf(e(CamPill.LEVEL), e(CamPill.VOLUME)),
            GridCell(0, 1) to listOf(e(CamPill.BT)),
            GridCell(1, 0) to listOf(e(CamPill.ZOOM))
        )
        val derived = defaultCellsOf(HudZone.RIGHT, HudLayoutTable.defaultOrderOf(HudZone.RIGHT), perRow = 1)
        assertEquals(
            "音量表眼里：配对格不算挡路（它随时能合回去），别人的格子算",
            setOf(GridCell(0, 1), GridCell(1, 0)),
            blockingCells(residents, e(CamPill.VOLUME), cellGroupMatesOf(derived, e(CamPill.VOLUME)))
        )
        assertEquals(
            "蓝牙眼里：配对格也挡路（跨组不许共格）",
            setOf(GridCell(0, 0), GridCell(1, 0)),
            blockingCells(residents, e(CamPill.BT), cellGroupMatesOf(derived, e(CamPill.BT)))
        )
        assertEquals(
            "self = null 时逐字退回「格子里有人就算占」的旧口径",
            setOf(GridCell(0, 0), GridCell(0, 1), GridCell(1, 0)),
            blockingCells(residents, null, emptySet())
        )
    }

    @Test
    fun droppingAMateBackOntoItsCellReformsThePair() {
        // 配对是**用户可解开也合得回**的：拆开后把音量表拖回姿态仪那一格 ⇒ 两颗重新共用 (0,0)，
        // 其余各颗不动。走的是 [resolveCells] 第 1 趟"同组的显式颗可以共格"那一条（上一批的模型做不到）
        val broken = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, GridCell(0, 5), planAll)
        assertEquals(GridCell(0, 5), rightCells(broken)[e(CamPill.VOLUME)])
        val merged = broken.placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, GridCell(0, 0), planAll)
        val after = rightCells(merged)
        assertEquals("合回去：两颗同格", GridCell(0, 0), after[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 0), after[e(CamPill.LEVEL)])
        assertEquals(GridCell(0, 1), after[e(CamPill.BT)])
        assertEquals(GridCell(0, 2), after[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 3), after[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 4), after[e(CamPill.STAB)])
        // 表里两颗都带**显式**格且值相同：编码层天然表达得起来（串本身见 HudLayoutCodecTest.pairCellsRoundTrip）
        assertEquals(
            listOf(e(CamPill.LEVEL) to GridCell(0, 0), e(CamPill.VOLUME) to GridCell(0, 0)),
            merged.orderOf(HudZone.RIGHT)
                .filter { it == e(CamPill.LEVEL) || it == e(CamPill.VOLUME) }
                .map { it to merged.cellsOf(HudZone.RIGHT).getValue(it) }
        )
    }

    @Test
    fun gridPlacementOfTheDefaultRightDockMeasuresTheNinetyFourBoard() {
        // 渲染层那一整条算式搬进纯函数 [gridPlacementOf] 之后的**验收用例**：
        // 拿默认右 Dock 的格子清单 + 手写的实测尺寸（px，本机 density 2.0），逐颗列出落点。
        // `HudEntryGrid` 现在只剩"量尺寸 + placeRelative"，所以这一条就是底板宽度的 JVM 侧证据
        val gap = HudSizePx(8, 8)   // 令牌 WotaSpace.xs = 4dp
        val items = HudLayoutTable.default().gridItems(HudZone.RIGHT, planAll)
        val sizes = mapOf(
            e(CamPill.LEVEL) to HudSizePx(108, 140),   // 姿态仪：54 × 70dp
            e(CamPill.VOLUME) to HudSizePx(56, 142),   // 音量表：28 × 71dp
            e(CamPill.BT) to HudSizePx(94, 60),        // 下面四颗都是紧凑档胶囊：47 × 30dp
            e(CamPill.ZOOM) to HudSizePx(94, 60),
            e(CamPill.FOCUS) to HudSizePx(94, 60),
            e(CamPill.STAB) to HudSizePx(94, 60)
        )
        val placed = gridPlacementOf(items, items.map { sizes.getValue(it.entry) }, gap)
        // 格长：宽 = 配对格 (108 + 8 + 56) + 8 = 180；高 = 最高那颗 142 + 8 = 150
        assertEquals(HudSizePx(180, 150), placed.pitch)
        // 格网尺寸：单列 ⇒ 宽 = 1 × 180 − 8 = 172px = 86dp；5 行 ⇒ 高 = 5 × 150 − 8 = 742px
        assertEquals(HudSizePx(172, 742), placed.size)
        assertEquals(
            "底板 = 86 + 左右内边距 4+4 = 94dp（r11 真机量到的那一档；本轮锁屏，这是手算）",
            94, placed.size.width / 2 + 8
        )
        // 逐颗左上角（全部手算）：配对那一格在 180 宽的格子里居中到 4，两颗之间再留一道行距
        assertEquals(
            listOf(
                HudPointPx(4, 5),      // 姿态仪：格心 (180−172)/2 = 4；纵向 (150−140)/2 = 5
                HudPointPx(120, 4),    // 音量表：4 + 108 + 8 = 120；纵向 (150−142)/2 = 4
                HudPointPx(43, 195),   // 蓝牙：(180−94)/2 = 43；1 × 150 + (150−60)/2 = 195
                HudPointPx(43, 345),   // 变焦
                HudPointPx(43, 495),   // 对焦
                HudPointPx(43, 645)    // 防抖
            ),
            placed.offsets
        )
        // 拆开配对之后撑 pitch 的换成姿态仪那一格（54dp）⇒ 格网 108px ⇒ 底板 62dp，比默认还窄
        val broken = HudLayoutTable.default()
            .placeEntryAt(e(CamPill.VOLUME), HudZone.RIGHT, 1, GridCell(0, 5), planAll)
            .gridItems(HudZone.RIGHT, planAll)
        val brokenPlaced = gridPlacementOf(broken, broken.map { sizes.getValue(it.entry) }, gap)
        assertEquals(HudSizePx(116, 150), brokenPlaced.pitch)
        assertEquals("拆对后底板 = 54 + 4 + 4 = 62dp", 62, brokenPlaced.size.width / 2 + 8)
    }

    @Test
    fun offsetsFollowTheInputIndexNotTheCellOrder() {
        // 上一批的 `gridPlacementOfTheDefaultRightDockMeasuresTheNinetyFourBoard` 把 offsets 与手算 px
        // 钉住了，但那次的 `items` **恰好就是格子顺序** ⇒ 它区分不了两种实现：
        //   (a) offsets[i] 对应 items[i]（正确，也是 HudEntryGrid 依赖的语义）
        //   (b) offsets 按格子分组/排序输出（写错的方式，屏幕上会把胶囊贴到别的格子上）
        // 两种实现在那条用例里都绿。这条就是把 items **故意打乱**（含把配对格里的两颗反着列），
        // 让 (b) 立刻红——这是渲染胶水层唯一还没被执行过的测试覆盖到的不变量。
        val gap = HudSizePx(8, 8)
        val sizes = mapOf(
            e(CamPill.LEVEL) to HudSizePx(108, 140),
            e(CamPill.VOLUME) to HudSizePx(56, 142),
            e(CamPill.BT) to HudSizePx(94, 60),
            e(CamPill.ZOOM) to HudSizePx(94, 60),
            e(CamPill.FOCUS) to HudSizePx(94, 60),
            e(CamPill.STAB) to HudSizePx(94, 60)
        )
        val shuffled = listOf(
            HudGridItem(e(CamPill.STAB), GridCell(0, 4)),    // 最底行那颗排在**第 0 位**
            HudGridItem(e(CamPill.VOLUME), GridCell(0, 0)),  // 配对格：音量表排在姿态仪**之前**
            HudGridItem(e(CamPill.BT), GridCell(0, 1)),
            HudGridItem(e(CamPill.LEVEL), GridCell(0, 0)),
            HudGridItem(e(CamPill.ZOOM), GridCell(0, 2)),
            HudGridItem(e(CamPill.FOCUS), GridCell(0, 3))
        )
        val placed = gridPlacementOf(shuffled, shuffled.map { sizes.getValue(it.entry) }, gap)

        // 格长与格网尺寸**与次序无关**（撑 pitch 的还是那枚配对格）
        assertEquals(HudSizePx(180, 150), placed.pitch)
        assertEquals(HudSizePx(172, 742), placed.size)

        // 按下标取——offsets[0] 属于 STAB，不是左上角那一格。这就是 (b) 会红的地方
        assertEquals("第 0 位必须是 STAB 自己的落点", HudPointPx(43, 645), placed.offsets[0])
        // 逐颗按**条目身份**点名（手算）：单住户格子恒为 x=43（(180−94)/2），y 由行号推
        assertEquals(HudPointPx(43, 195), placed.offsets[2])   // BT  row 1
        assertEquals(HudPointPx(43, 345), placed.offsets[4])   // ZOOM row 2
        assertEquals(HudPointPx(43, 495), placed.offsets[5])   // FOCUS row 3
        // 配对格：格内左右按**输入次序**排 ⇒ 音量表这次占了左边 4，姿态仪跟在它后面
        // （上一批那条用例里是姿态仪在左、x=4；两颗互换位置是设计，不是 bug）
        assertEquals("音量表这次在配对格左侧", HudPointPx(4, 4), placed.offsets[1])
        assertEquals("姿态仪紧跟其后：4 + 56 + 8 = 68", HudPointPx(68, 5), placed.offsets[3])

        // 真正的不变量：**单住户格子的落点只由它自己的 (col,row) 与尺寸决定，与列表次序无关**。
        // 与"自然次序"那一版逐颗对撞——若实现把 offsets 按格子排，这里立刻红。
        val natural = listOf(
            HudGridItem(e(CamPill.LEVEL), GridCell(0, 0)),
            HudGridItem(e(CamPill.VOLUME), GridCell(0, 0)),
            HudGridItem(e(CamPill.BT), GridCell(0, 1)),
            HudGridItem(e(CamPill.ZOOM), GridCell(0, 2)),
            HudGridItem(e(CamPill.FOCUS), GridCell(0, 3)),
            HudGridItem(e(CamPill.STAB), GridCell(0, 4))
        )
        val naturalPlaced = gridPlacementOf(natural, natural.map { sizes.getValue(it.entry) }, gap)
        fun byEntry(items: List<HudGridItem>, pl: com.wotagei.cam.ui.GridPlacement) =
            items.mapIndexed { i, it -> it.entry to pl.offsets[i] }.toMap()
        val a = byEntry(shuffled, placed)
        val b = byEntry(natural, naturalPlaced)
        for (pill in listOf(CamPill.BT, CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB)) {
            assertEquals(
                "${pill.name} 的落点不该随列表次序变（变了就说明 offsets 是按格子排的）",
                b[e(pill)], a[e(pill)]
            )
        }
    }

    @Test
    fun thePairedCellIsWidthOwnerAndTheBoardIsNinetyFour() {
        // **本批唯一的验收口径**：录制中右 Dock 的宽度账（px；本机 density 2.0；颗宽取实测值）
        val gapX = 8            // 令牌 WotaSpace.xs = 4dp
        val attitude = 108      // 姿态仪那颗：46dp 天地线 + 左右内边距 4+4 = 54dp
        val volume = 56         // 音量表：16dp LED + 左右内边距 6+6 = 28dp
        val chip = 94           // 紧凑档胶囊那颗（「1.0x」47dp）
        assertEquals(
            "配对格内容宽 = 54 + 4 + 28 = 86dp（172px）——撑起底板的就是这一行",
            172, cellContentWidthPx(listOf(attitude, volume), gapX)
        )
        assertTrue(
            "配对格必须比最宽那颗还宽，否则 pitch 又退回「按颗算」那一档（120dp 的成因）",
            cellContentWidthPx(listOf(attitude, volume), gapX) > cellContentWidthPx(listOf(attitude), gapX)
        )
        assertEquals("一颗时不许多算行距", attitude, cellContentWidthPx(listOf(attitude), gapX))
        assertEquals(chip, cellContentWidthPx(listOf(chip), gapX))
        assertEquals(0, cellContentWidthPx(emptyList(), gapX))
        // 单列 ⇒ 格网宽 = 1 × pitch − 一道行距 = 最宽那枚格子的内容宽；底板再包左右各 4dp ⇒ 94dp
        val pitchW = gridPitchPx(maxCellWidthPx = 172, maxCellHeightPx = 140, gapXPx = gapX, gapYPx = 8).width
        assertEquals(180, pitchW)
        val gridW = gridSizePx(cols = 1, rows = 5, pitch = HudSizePx(pitchW, 148), gap = HudSizePx(gapX, gapX)).width
        assertEquals(172, gridW)
        assertEquals("底板 = 86 + 4 + 4 = 94dp（r11 真机量到的那一档；本轮锁屏，是手算不是实测）", 94, gridW / 2 + 8)
        // 反证：上一批"配对占两列"那一档在同一个 gridSizePx 下给 224px = 112dp，底板 120dp（要抹掉的那 26dp）
        val oldPitch = gridPitchPx(maxCellWidthPx = attitude, maxCellHeightPx = 140, gapXPx = gapX, gapYPx = 8).width
        assertEquals(
            224,
            gridSizePx(cols = 2, rows = 5, pitch = HudSizePx(oldPitch, 148), gap = HudSizePx(gapX, gapX)).width
        )
        // 拆开配对之后没有 86 那一格了，最宽格换成姿态仪那颗 54dp ⇒ 底板 62dp（比默认还窄）
        assertEquals(
            108,
            gridSizePx(
                cols = 1, rows = 6,
                pitch = HudSizePx(gridPitchPx(attitude, 140, gapX, 8).width, 148),
                gap = HudSizePx(gapX, gapX)
            ).width
        )
        // 逆算桥函数：编辑页就是从实测矩形按 gridPitchOf 除回这个格长（同一批真实数字再走一遍）
        assertEquals(
            HudSizePx(180, 148),
            gridPitchOf(
                gridWidthPx = 172, gridHeightPx = 5 * 148 - 8, cols = 1, rows = 5, gapPx = gapX,
                fallbackChildWidthPx = 1, fallbackChildHeightPx = 1
            )
        )
    }

    @Test
    fun inCellOffsetsAndPointerHitReadTheSameRuler() {
        // 格内左右偏移：整枚格子在格宽里居中，再按各颗实测宽 + 行距依次排开
        val pitchPx = HudSizePx(180, 148)
        val widths = listOf(108, 56)
        assertEquals(
            "配对格内左缘 = 4 与 120（(180 − 172) / 2 = 4；4 + 108 + 8 = 120）",
            listOf(4, 120), cellChildLeftsPx(GridCell(0, 0), pitchPx, widths, 8)
        )
        assertEquals("第 1 列要先加 col × pitch", listOf(184, 300), cellChildLeftsPx(GridCell(1, 0), pitchPx, widths, 8))
        assertEquals(
            "单颗格子与 cellPlaceOffsetPx 的 x 逐字同值（两处不许各有一份居中算式）",
            listOf(cellPlaceOffsetPx(GridCell(2, 1), pitch, HudSizePx(30, 10)).x),
            cellChildLeftsPx(GridCell(2, 1), pitch, listOf(30), 8)
        )
        assertEquals(10, cellChildLeftsPx(GridCell(0, 0), pitch, listOf(30), 8).single())
        assertEquals(emptyList<Int>(), cellChildLeftsPx(GridCell(0, 0), pitch, emptyList(), 8))
        // 命中裁决（配对格被点中时该搬哪一颗，不许靠 Compose 的矩形回报顺序）
        val spans = listOf(4..111, 120..175)
        assertEquals("压在姿态仪上 ⇒ 搬姿态仪", 0, entryHitIndex(spans, 60))
        assertEquals("压在音量表上 ⇒ 搬音量表", 1, entryHitIndex(spans, 130))
        assertEquals("落在两颗之间那道行距里 ⇒ 取中心更近的那颗", 1, entryHitIndex(spans, 116))
        assertEquals("格子居中的留白里 ⇒ 仍是靠左那颗", 0, entryHitIndex(spans, 6))
        assertEquals("同距离取靠左那颗（中心 4 / 24，指针 14）", 0, entryHitIndex(listOf(0..9, 20..29), 14))
        val reversed = spans.reversed()
        assertEquals(
            "候选列表反过来写也必须指向**同一颗**（测的是「按 x 定」，不是「取第一个」）",
            spans[entryHitIndex(spans, 130)],
            reversed[entryHitIndex(reversed, 130)]
        )
        assertEquals(-1, entryHitIndex(emptyList(), 42))
    }

    @Test
    fun clampingAPairedCellNeverSplitsItIntoTwoColumns() {
        // 手改 prefs 能把配对两颗写成同一枚**越界**格。钳制是"格 → 格"的函数，两颗入参相同 ⇒ 出参相同，
        // 不可能出现"钳进两个不同格子、顺手把配对拆开又牵连别颗"。这一条同时钉住 resolveCells 不拆它们
        val t = HudLayoutTable.decode("v2;R,-1,-1,P0:5.9,P1:5.9,P2:-,P3:-,P4:-,P5:-")
        val after = rightCells(t)
        assertEquals("两列上限 ⇒ 两颗一起钳到第 1 列", GridCell(1, 9), after[e(CamPill.LEVEL)])
        assertEquals("同格这条不许被钳制破坏", GridCell(1, 9), after[e(CamPill.VOLUME)])
        assertEquals(GridCell(1, 9), clampStoredCell(HudZone.RIGHT, GridCell(5, 9)))
        assertEquals(
            "钳制对同一枚格子给唯一结果（把两颗钳进两格的那一步在这里红）",
            1,
            listOf(GridCell(5, 9), GridCell(5, 9)).map { clampCellToBox(it, GridBox(2, 10)) }.distinct().size
        )
        // 没被摆过的四颗仍按推导格落位：钳一枚配对格不该重排别人
        assertEquals(GridCell(0, 1), after[e(CamPill.BT)])
        assertEquals(GridCell(0, 2), after[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 4), after[e(CamPill.STAB)])
    }
}
