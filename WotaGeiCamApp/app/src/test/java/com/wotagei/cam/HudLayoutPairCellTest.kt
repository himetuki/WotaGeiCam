package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.GridAnchor
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
import com.wotagei.cam.ui.gridRowPitchPx
import com.wotagei.cam.ui.gridSizePx
import com.wotagei.cam.ui.hudRowGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 任务 #74 的**后果修复**：配对那一对（S2-2B「姿态仪 + 音量表」）从"相邻两格"改成"同一枚格子里的两个条目"。
 *
 * 派单给的方法论（格子间距 = 容器内最宽那颗）让右 Dock 底板从 ≈94dp 撑到 ≈120dp，方向与用户这轮
 * 「左dock栏还是太宽了」正好相反。本文件钉的是修复后的三件事：
 * 1. **宽度账**：pitch 由"最宽那枚**格子**"推出，配对格 86dp ⇒ 底板 94dp
 *    （[thePairedCellIsWidthOwnerAndTheBoardIsNinetyFour] 与
 *    [gridPlacementOfTheDefaultRightDockMeasuresTheNinetyFourBoard]）；
 * 2. **独立性**：拆对、合对、跨组撞格三条都有逐颗手算的格子/坐标清单（另外四条用例）；
 * 3. **配对格被点中时该搬哪一颗**：规则是纯函数 [entryHitIndex]，不靠 Compose 的矩形回报顺序；
 * 4. **#75 之后多出来的一条**：[rightDockGridNowFitsTheLandscapeBandThatWasTheKnownDefect]——
 *    它原来叫"已知缺陷的测量值"（均匀行距把网格撑到 386dp、超出横屏 244dp 带高 142dp），
 *    行距改成固定格距 + 高条目吃行跨度之后**同一条对撞翻成溢出 0**。
 *    纵向那一套算式本身（格距怎么推、跨度怎么算、跨度会不会动到别人的行号）在
 *    `HudLayoutRowPitchTest`，本文件只管"宽度账与配对语义没被纵向改动带偏"。
 *
 * 恒等式与真断言的分工（AGENTS.md 那条）：所有期望值都是**人能手算**的 px/dp 与逐颗点名的格子，
 * 没有任何一条是"拿实现的输出与实现自己比"。
 */
class HudLayoutPairCellTest {

    /**
     * #80 之后 [com.wotagei.cam.ui.gridPlacementOf] 多了一个 `anchor` 形参。
     * 本文件那几条测的是**宽度账与配对语义**（86dp 那格撑格长、底板 94dp），一律喂**空**的默认表：
     * 空 = "这一帧还没量到默认表"那一档 ⇒ 格长退回"取渲染分组里最宽那枚格子"、预留档数 0，
     * 与改前逐字同值。预留与"从右往里数"那两支的新行为在 `HudLayoutGridTest` 的 #80 那组用例里打。
     */
    private val rightAnchor = GridAnchor(HudZone.RIGHT, emptyMap())

    /**
     * 本文件默认那一份输入：**实测高一律 0**（⇒ 行跨度恒为 1）、格距手摆 30px。
     * 这样 #74 那一版逐颗点名的行号与屏幕坐标一字不动——本文件查的是"配对共格 ⇒ 宽度回到 94dp"
     * 与独立性，跨度那一档另在 `HudLayoutRowPitchTest` 喂真实高度打，两条不混。
     * 唯一例外是 [rightDockGridNowFitsTheLandscapeBandThatWasTheKnownDefect]：那条就是要拿真实高度
     * 与真实格距去对撞带高（#75 的验收口径），它自己造 plan。
     */
    private val planAll = HudGridPlan(
        visible = HudEntry.ALL.toSet(),
        readoutPerRow = 3,
        rowPitchOf = { 30 },
        cellHeightOf = { 0 }
    )
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
        val derived = defaultCellsOf(HudZone.RIGHT, def, planAll)
        assertEquals(GridCell(0, 0), derived[e(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), derived[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 1), derived[e(CamPill.BT)])
        assertEquals(GridCell(0, 4), derived[e(CamPill.STAB)])
        assertEquals(setOf(0), derived.values.map { it.col }.toSet())
        // ③ 同组搭档：配对两颗互为搭档，其余颗谁都不是；读数块恒空（每颗各占一列，行为一条没变）
        assertEquals(setOf(e(CamPill.VOLUME)), cellGroupMatesOf(derived, e(CamPill.LEVEL)))
        assertTrue(cellGroupMatesOf(derived, e(CamPill.BT)).isEmpty())
        val readout = defaultCellsOf(HudZone.READOUT, HudLayoutTable.defaultOrderOf(HudZone.READOUT), planAll)
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
        // "一格多颗"之后「这格被占了」的口径（[blockingCells]）：**同组搭档不算占，跨组算占**。
        // 本批把落点改成交换之后，把变焦硬拖到配对那一格 (0,0) 的结果是：
        // 配对两颗（LEVEL+VOLUME，互为搭档）**整组**让到变焦的原格 (0,2)，变焦自己拿到 (0,0)。
        // 退回旧实现（只落空格）时配对两颗原地不动、变焦落在 (1,0)，本用例红。
        val after = rightCells(
            HudLayoutTable.default().placeEntryAt(e(CamPill.ZOOM), HudZone.RIGHT, 2, GridCell(0, 0), planAll)
        )
        assertEquals("跨组那颗拿到目标格", GridCell(0, 0), after[e(CamPill.ZOOM)])
        assertEquals("配对两颗整组让到变焦的原格（仍是同一格 ⇒ 配对没被拆开）", GridCell(0, 2), after[e(CamPill.LEVEL)])
        assertEquals(GridCell(0, 2), after[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 1), after[e(CamPill.BT)])
        assertEquals(GridCell(0, 3), after[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 4), after[e(CamPill.STAB)])
        assertEquals("配对仍是共格（交换不该把它拆开）", after[e(CamPill.LEVEL)], after[e(CamPill.VOLUME)])
        // 占用判据本身逐格点名（手摆住户表，不经过任何实现路径；这一块是 blockingCells 的直打，不受交换改动影响）
        val residents = mapOf(
            GridCell(0, 0) to listOf(e(CamPill.LEVEL), e(CamPill.VOLUME)),
            GridCell(0, 1) to listOf(e(CamPill.BT)),
            GridCell(1, 0) to listOf(e(CamPill.ZOOM))
        )
        val derived = defaultCellsOf(HudZone.RIGHT, HudLayoutTable.defaultOrderOf(HudZone.RIGHT), planAll)
        // 后面三形参里的 `{ 0 }` = "量不到高" ⇒ 跨度 1 ⇒ 被占的就是那一格本身（与 #74 逐字相同）。
        // 跨度 > 1 时"整块都算占"那一档在 HudLayoutRowPitchTest 用真实高度打，不混进这条共格口径的判据
        assertEquals(
            "音量表眼里：配对格不算挡路（它随时能合回去），别人的格子算",
            setOf(GridCell(0, 1), GridCell(1, 0)),
            blockingCells(residents, e(CamPill.VOLUME), cellGroupMatesOf(derived, e(CamPill.VOLUME)), { 0 }, 30)
        )
        assertEquals(
            "蓝牙眼里：配对格也挡路（跨组不许共格）",
            setOf(GridCell(0, 0), GridCell(1, 0)),
            blockingCells(residents, e(CamPill.BT), cellGroupMatesOf(derived, e(CamPill.BT)), { 0 }, 30)
        )
        assertEquals(
            "self = null 时逐字退回「格子里有人就算占」的旧口径",
            setOf(GridCell(0, 0), GridCell(0, 1), GridCell(1, 0)),
            blockingCells(residents, null, emptySet(), { 0 }, 30)
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
        // 手喂的行距：#74 那一版"实测最高那颗 142 + 行距 8"算出来的 150px。
        // 本用例查的是**宽度账**（底板 94dp），纵向取多少不影响宽度；#75 真实格距（68px）与
        // 由它推出的行跨度，在下面那条溢出用例和 `HudLayoutRowPitchTest` 里打。
        // 喂 150 之后每一格都只占一档（142 ≤ 150），所以格网尺寸与各颗落点与 #74 **逐字同值**——
        // 这正是"宽度那条账不许被纵向改动带偏"的证据本身
        val rowPitch = 150
        val items = HudLayoutTable.default().gridItems(HudZone.RIGHT, planAll)
        val sizes = mapOf(
            e(CamPill.LEVEL) to HudSizePx(108, 140),   // 姿态仪：54 × 70dp
            e(CamPill.VOLUME) to HudSizePx(56, 142),   // 音量表：28 × 71dp
            e(CamPill.BT) to HudSizePx(94, 60),        // 下面四颗都是紧凑档胶囊：47 × 30dp
            e(CamPill.ZOOM) to HudSizePx(94, 60),
            e(CamPill.FOCUS) to HudSizePx(94, 60),
            e(CamPill.STAB) to HudSizePx(94, 60)
        )
        // #80：喂**空** defaultCells = "默认表还没量到"那一档 ⇒ 格长退回"取渲染分组里最宽那枚格子"，
        // 与改前逐字同值；本条测的就是这条兜底路径（预留那一档在 HudLayoutGridTest 的 #80 那组里单独打）
        val placed = gridPlacementOf(items, items.map { sizes.getValue(it.entry) }, gap, rowPitch, rightAnchor)
        // 格长：宽 = 配对格 (108 + 8 + 56) + 8 = 180；高 = 直接就是喂进来的行距 150（#75：不再从颗里取）
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
        val brokenPlaced = gridPlacementOf(broken, broken.map { sizes.getValue(it.entry) }, gap, rowPitch, rightAnchor)
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
        // 与上面那条宽度用例同一个手喂行距（150px ⇒ 每格一档），本条只查"offsets 按下标对齐"
        val rowPitch = 150
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
        val placed = gridPlacementOf(shuffled, shuffled.map { sizes.getValue(it.entry) }, gap, rowPitch, rightAnchor)

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
        val naturalPlaced = gridPlacementOf(natural, natural.map { sizes.getValue(it.entry) }, gap, rowPitch, rightAnchor)
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

    /**
     * #74 那一版这条叫 `rightDockGridOverflowsTheLandscapeBandAndThatIsAKnownDefect`，
     * 断言的是"已知缺陷的测量值：溢出 142dp ≈ 1.8 行"。#75 修完了，**同一条对撞翻成溢出 0**——
     * 没有删它、没有放松成"不崩就行"，改的只是期望值与注释（那一档历史数值留在下面的对照段里）。
     *
     * 三段全是"计划 → 行为"的桥，不是恒等式：
     * 1. **行距与住户无关**：[gridRowPitchPx] = (18sp 行高 + 上下内边距 6+6 + 行距 4) × density 2 = **68px**。
     * 改坏它（换成"实测最高那颗 + 行距"那一支）⇒ 本条红在行距那一格断言上。
     * 2. **默认行按跨度推进**：配对块实测 144px ⇒ [com.wotagei.cam.ui.cellRowSpan] = 3 ⇒ 蓝牙 (0,3)、
     * 变焦 (0,4)、对焦 (0,5)、防抖 (0,6)。改坏 `defaultCellsOf` 的 `row += span`（退回 `row++`）
     * ⇒ 颗数那一组断言红（后面的胶囊会压在配对块身上，正是这次要禁的形态）。
     * 3. **网格高对撞带高**：`7 × 68 − 8 = 468px =` **234dp** ≤ 横屏带高 244dp ⇒ 溢出 0，
     * 且最底那颗（防抖）的**底边 + 底板内边距**也在带内 ⇒ 对焦与防抖这次露得出来。
     * 改坏 [gridPlacementOf] 的行数算式（不按跨度算总档）⇒ 高度那一条红。
     */
    @Test
    fun rightDockGridNowFitsTheLandscapeBandThatWasTheKnownDefect() {
        val density = 2f
        val gap = HudSizePx(8, 8)   // 令牌 WotaSpace.xs = 4dp
        val rowPitch = gridRowPitchPx(1f, 4f, density)
        assertEquals("格距 =（胶囊 18sp 行高 + 上下内边距 6+6 + 行距 4）× 2 ⇒ 68px = 34dp", 68, rowPitch)

        // 手写的实测高（px，本机 density 2.0）：
        // 姿态仪 46 天地线 + 上下内边距 5+5 + spacedBy 2 + labelSmall 行高 14sp = 72dp = 144px
        // 音量表 13 图标 + 4 间隔 + 6 格 ×(5 + 1+1) = 59dp + 上下内边距 6+6 = 71dp = 142px
        // 四颗紧凑档胶囊 = 18sp + 6+6 = 30dp = 60px
        val heights = mapOf(
            e(CamPill.LEVEL) to 144, e(CamPill.VOLUME) to 142,
            e(CamPill.BT) to 60, e(CamPill.ZOOM) to 60, e(CamPill.FOCUS) to 60, e(CamPill.STAB) to 60
        )
        val widths = mapOf(
            e(CamPill.LEVEL) to 108, e(CamPill.VOLUME) to 56,
            e(CamPill.BT) to 94, e(CamPill.ZOOM) to 94, e(CamPill.FOCUS) to 94, e(CamPill.STAB) to 94
        )
        val plan = HudGridPlan(
            visible = HudEntry.ALL.toSet(),
            readoutPerRow = 3,
            rowPitchOf = { rowPitch },
            cellHeightOf = { heights.getValue(it) }
        )
        val items = HudLayoutTable.default().gridItems(HudZone.RIGHT, plan)
        val cells = items.associate { it.entry to it.cell }
        // ② 默认行号逐颗点名（跨度推进之后的那一套；#74 那一版这里是 0,1,2,3,4）
        assertEquals(GridCell(0, 0), cells[e(CamPill.LEVEL)])
        assertEquals("配对两颗仍然共用第 0 格（宽度账没被纵向改动带偏）", GridCell(0, 0), cells[e(CamPill.VOLUME)])
        assertEquals("蓝牙必须让到配对块**之外**的第 3 档", GridCell(0, 3), cells[e(CamPill.BT)])
        assertEquals(GridCell(0, 4), cells[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 5), cells[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 6), cells[e(CamPill.STAB)])

        val placed = gridPlacementOf(items, items.map { HudSizePx(widths.getValue(it.entry), heights.getValue(it.entry)) }, gap, rowPitch, rightAnchor)
        // 宽度那一轴一条没动：配对格 108 + 8 + 56 = 172 ⇒ 格长 180 ⇒ 格网 172px = 86dp ⇒ 底板 94dp
        assertEquals(HudSizePx(180, rowPitch), placed.pitch)
        assertEquals("格网 = 172px 宽（底板 94dp）", 94, placed.size.width / 2 + 8)
        // ③ 总高 = 7 档 × 68 − 8 = 468px = 234dp；横屏带高 = 360 − 顶栏 44 − 底栏 72 = 244dp
        val bandDp = 360 - 44 - 72
        val gridHeightDp = placed.size.height / density
        assertEquals("7 档 ⇒ 468px = 234dp", 234.0f, gridHeightDp, 0.01f)
        val overflowDp = gridHeightDp - bandDp
        assertEquals(
            "#75 已修：网格高 ${gridHeightDp}dp 必须装进横屏带高 ${bandDp}dp（溢出取 max(0,·)）。" +
                "这里红就说明行距又被哪颗顶高了，或跨度没让后面的行号让开",
            0, maxOf(0, overflowDp.roundToInt())
        )
        // "露不露出"逐颗点名：比的是颗自己的**底边** + 底板那枚 WotaSpace.xs 内边距（8px），
        // 因为带高管的是整枚卡片；只比网格高的话，最后一颗伸出网格但仍在底板内会误报
        val bottoms = items.mapIndexed { i, it -> it.entry to placed.offsets[i].y + heights.getValue(it.entry) }
            .toMap()
        val cardBottomPx = placed.size.height + 8
        assertEquals("整枚底板（含 4dp 内边距）= 476px = 238dp ≤ 488px", 476, cardBottomPx)
        for (pill in listOf(CamPill.BT, CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB)) {
            assertTrue(
                "${pill.name} 的底边 ${bottoms.getValue(e(pill))}px + 底板内边距必须落在带内（带底 488px）",
                bottoms.getValue(e(pill)) + 8 <= bandDp * density
            )
        }
        // 对照段：把行距换回 #74 那一版"实测最高那颗 148 + 行距 8 = 156px"、人人各占一档，
        // 同一批函数（[gridSizePx]）算出来是 5 × 156 − 8 = 772px = 386dp ⇒ **溢出 142dp**——
        // 那就是被投诉的那个形态。这一段是"旧模型的手工复刻"，它红就说明有人改了格网那笔减法
        val oldModelDp = gridSizePx(1, 5, HudSizePx(180, 156), gap).height / density
        assertEquals("旧模型对照：386dp，比带高多 142dp ≈ 1.8 行（当年对焦/防抖整颗掉到带外）", 142.0f, oldModelDp - bandDp, 0.01f)
        // 而改前那条 `Column` 的自然行高（74 + 4×30 + 4×4 = 210dp）本来就装得进 244dp：
        // 均匀行距把它抵消了；现在按跨度排完之后 234dp，重新装得进，且不再靠"把两颗并排"省高度
        assertTrue("改前自然行高 210dp 是这条账的基准，装得进 ${bandDp}dp", 74.0 + 4 * 30.0 + 4 * 4.0 <= bandDp)
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
        // #75 之后 gridPitchPx 的纵向形参是"格距"（直接透传，不再 +行距）；这里仍喂手摆的 148/140，
        // 因为本用例只看**宽度**那一轴——纵向那一档在 HudLayoutRowPitchTest 与溢出用例里打
        val pitchW = gridPitchPx(maxCellWidthPx = 172, rowPitchPx = 148, gapXPx = gapX).width
        assertEquals(180, pitchW)
        val gridW = gridSizePx(cols = 1, rows = 5, pitch = HudSizePx(pitchW, 148), gap = HudSizePx(gapX, gapX)).width
        assertEquals(172, gridW)
        assertEquals("底板 = 86 + 4 + 4 = 94dp（r11 真机量到的那一档；本轮锁屏，是手算不是实测）", 94, gridW / 2 + 8)
        // 反证：上一批"配对占两列"那一档在同一个 gridSizePx 下给 224px = 112dp，底板 120dp（要抹掉的那 26dp）
        val oldPitch = gridPitchPx(maxCellWidthPx = attitude, rowPitchPx = 148, gapXPx = gapX).width
        assertEquals(
            224,
            gridSizePx(cols = 2, rows = 5, pitch = HudSizePx(oldPitch, 148), gap = HudSizePx(gapX, gapX)).width
        )
        // 拆开配对之后没有 86 那一格了，最宽格换成姿态仪那颗 54dp ⇒ 底板 62dp（比默认还窄）
        assertEquals(
            108,
            gridSizePx(
                cols = 1, rows = 6,
                pitch = HudSizePx(gridPitchPx(attitude, 148, gapX).width, 148),
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
        // #80 之后这两个函数各多两个形参（gridWidthPx / colFromEnd）：本文件这几条测的都是
        // **col 0 贴左缘**那一支（左 Dock 与读数块），所以 colFromEnd = false、gridWidthPx 只是名义值
        //（那一支不读它）；"贴右缘从右往里数"那一支在 HudLayoutGridTest 的 #80 那组用例里单独打
        assertEquals(
            "配对格内左缘 = 4 与 120（(180 − 172) / 2 = 4；4 + 108 + 8 = 120）",
            listOf(4, 120), cellChildLeftsPx(GridCell(0, 0), pitchPx, widths, 8, 352, colFromEnd = false)
        )
        assertEquals(
            "第 1 列要先加 col × pitch",
            listOf(184, 300), cellChildLeftsPx(GridCell(1, 0), pitchPx, widths, 8, 352, colFromEnd = false)
        )
        assertEquals(
            "单颗格子与 cellPlaceOffsetPx 的 x 逐字同值（两处不许各有一份居中算式）",
            listOf(cellPlaceOffsetPx(GridCell(2, 1), pitch, HudSizePx(30, 10), 1).x),
            cellChildLeftsPx(GridCell(2, 1), pitch, listOf(30), 8, 142, colFromEnd = false)
        )
        assertEquals(10, cellChildLeftsPx(GridCell(0, 0), pitch, listOf(30), 8, 142, colFromEnd = false).single())
        assertEquals(emptyList<Int>(), cellChildLeftsPx(GridCell(0, 0), pitch, emptyList(), 8, 142, colFromEnd = false))
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
        // 后两形参喂"量不到高"（0 ⇒ 跨度 1）：这一条查的是**同格不许被钳开**，跨度那一档在
        // HudLayoutRowPitchTest 打；两颗入参相同 ⇒ 出参相同，这条与 #74 逐字同值
        assertEquals(
            "钳制对同一枚格子给唯一结果（把两颗钳进两格的那一步在这里红）",
            1,
            listOf(GridCell(5, 9), GridCell(5, 9)).map { clampCellToBox(it, GridBox(2, 10), 0, 30) }.distinct().size
        )
        // 没被摆过的四颗仍按推导格落位：钳一枚配对格不该重排别人
        assertEquals(GridCell(0, 1), after[e(CamPill.BT)])
        assertEquals(GridCell(0, 2), after[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 4), after[e(CamPill.STAB)])
    }
}
