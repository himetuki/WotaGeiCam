package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.GridAnchor
import com.wotagei.cam.ui.GridBox
import com.wotagei.cam.ui.GridCell
import com.wotagei.cam.ui.GridPlacement
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudGridItem
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudPointPx
import com.wotagei.cam.ui.HudRowGapDp
import com.wotagei.cam.ui.HudSizePx
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.blockingCells
import com.wotagei.cam.ui.cellPlaceOffsetPx
import com.wotagei.cam.ui.cellRowSpan
import com.wotagei.cam.ui.clampCellToBox
import com.wotagei.cam.ui.defaultCellsOf
import com.wotagei.cam.ui.freeCellNear
import com.wotagei.cam.ui.gridPitchPx
import com.wotagei.cam.ui.gridPlacementOf
import com.wotagei.cam.ui.gridRowPitchPx
import com.wotagei.cam.ui.hudGridGap
import com.wotagei.cam.ui.hudGridRowPitchPx
import com.wotagei.cam.ui.snapGridPitchPx
import com.wotagei.cam.ui.spannedCells
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务 **#75**：把"均匀行距"换成**固定格距 + 行跨度**，让右 Dock 的网格重新装得进横屏那条带。
 *
 * 缺陷本体（#74 第一版遗留）：`gridPitchPx` 的纵向取"容器内最高那颗的实测尺寸 + 行距"，
 * 于是右 Dock 那枚姿态仪自绘件（天地线 46 + 上下内边距 5+5 + spacedBy 2 + `labelSmall` 行高 14
 * = **72dp**）把行距顶到 ≈78dp，而它旁边四颗胶囊只有 30dp ⇒ 5 枚格子 ≈386dp，
 * 横屏带高只有 `360 − 顶栏 44 − 底栏 72 =` **244dp** ⇒ 溢出 142dp（1.8 行），对焦与防抖整颗掉到折叠线以下。
 * 改前那是 `Column` 的自然行高（74 + 4×30 + 4×4 ≈ 210dp），装得进——那正是 S2-2B 当年把姿态仪与
 * 音量表并排的理由，均匀行距把这笔账整个抵消了。
 *
 * 修法两条，本文件逐条打：
 * 1. 行距改成与住户无关的 [gridRowPitchPx]（一颗胶囊高 + 一道行距：100% 档 34dp、120% 档 38dp）；
 * 2. 高条目按 [cellRowSpan] 吃掉 `row .. row+span−1` 几档，**默认推导、占用、钳制、就近让位**四侧都按整块算。
 *
 * 恒等式与真断言的分工（AGENTS.md 那条）：期望值全是**人能手算**的 px/dp 与逐颗点名的格子；
 * 每条在注释里写明"改坏哪一行会红"。跨度**不进 schema**（[GridCell] 仍只有 (col,row)），
 * 所以本文件一条都不碰编解码——那部分由 `HudLayoutCodecTest` 与真机跨进程探针（docs/plan/13 §15.8）钉着。
 *
 * 本机参考值：density 2.0、横屏安全区 360dp 高、顶栏实测 44dp、底栏那排 72dp。
 */
class HudLayoutRowPitchTest {

    private fun e(p: CamPill) = HudEntry.of(p)
    private fun h(i: HudItem) = HudEntry.of(i)

    /** 面板 720×1600、density 2.0（与 `HudLayoutPairCellTest` 同一档） */
    private val density = 2f

    /** 竖 Dock 的行距档：真源是 `hudGridGap` 那一支（令牌 `WotaSpace.xs` = 4dp），**入参引令牌、期望值写字面量** */
    private val dockGapDp = hudGridGap(HudZone.LEFT).value

    /**
     * 录制中右 Dock 的实测高（px，100% 档，全部手算自既有令牌与既有自绘件尺寸）：
     * 姿态仪 46+5+5+2+14 = 72dp = 144px；音量表 13+4+6×(5+1+1) = 59dp，加上下内边距 6+6 = 71dp = 142px；
     * 蓝牙/变焦/对焦/防抖 = 18sp + 6+6 = 30dp = 60px。
     */
    private val rightHeights100 = mapOf(
        e(CamPill.LEVEL) to 144, e(CamPill.VOLUME) to 142,
        e(CamPill.BT) to 60, e(CamPill.ZOOM) to 60, e(CamPill.FOCUS) to 60, e(CamPill.STAB) to 60
    )
    private val rightWidths = mapOf(
        e(CamPill.LEVEL) to 108, e(CamPill.VOLUME) to 56,
        e(CamPill.BT) to 94, e(CamPill.ZOOM) to 94, e(CamPill.FOCUS) to 94, e(CamPill.STAB) to 94
    )

    /** 左竖 Dock 满配：参考线与闪光灯是 38dp 圆形图标钮 = 76px（**不吃字体缩放**），两颗胶囊 60px */
    private val leftHeights100 = mapOf(
        e(CamPill.REFLINE) to 76, e(CamPill.MONITOR) to 60, e(CamPill.CURVE) to 60, e(CamPill.FLASH) to 76
    )
    private val leftWidths = mapOf(
        e(CamPill.REFLINE) to 76, e(CamPill.MONITOR) to 136, e(CamPill.CURVE) to 139, e(CamPill.FLASH) to 76
    )

    /** 读数块满配 7 颗：全是全局档胶囊（主副标签共 30dp = 60px），宽按 `hudPerRowFor` 那笔 90dp 估 */
    private val readoutHeights100: Map<HudEntry, Int> = HudItem.ALL.associate { h(it) to 60 }
    private val readoutWidths: Map<HudEntry, Int> = HudItem.ALL.associate { h(it) to 180 }

    /** 120% 档：胶囊 21.6 + 12 = 33.6dp ≈ 68px；姿态仪 46+10+2+16.8 = 74.8dp ≈ 150px；
     *  音量表与图标钮是纯 dp 自绘件，不吃字体缩放 ⇒ 仍是 142 / 76px */
    private val rightHeights120 = mapOf(
        e(CamPill.LEVEL) to 150, e(CamPill.VOLUME) to 142,
        e(CamPill.BT) to 68, e(CamPill.ZOOM) to 68, e(CamPill.FOCUS) to 68, e(CamPill.STAB) to 68
    )
    private val leftHeights120 = mapOf(
        e(CamPill.REFLINE) to 76, e(CamPill.MONITOR) to 68, e(CamPill.CURVE) to 68, e(CamPill.FLASH) to 76
    )
    private val readoutHeights120: Map<HudEntry, Int> = HudItem.ALL.associate { h(it) to 68 }

    /** 一帧这一枚容器的解析输入（与两页同一个构造）：格距按字体缩放现算，实测高喂手算那张表 */
    private fun planOf(fontScale: Float, heights: Map<HudEntry, Int>, perRow: Int = 3): HudGridPlan {
        val rowPitch = gridRowPitchPx(fontScale, dockGapDp, density)
        val readoutPitch = gridRowPitchPx(fontScale, HudRowGapDp, density)
        return HudGridPlan(
            visible = HudEntry.ALL.toSet(),
            readoutPerRow = perRow,
            rowPitchOf = { zone -> if (zone == HudZone.READOUT) readoutPitch else rowPitch },
            cellHeightOf = { heights[it] ?: 0 }
        )
    }

    /**
     * 桥函数：默认表 → 格子清单 → 渲染层那条纯算式 → **整份测量结果**（格长 + 格网尺寸 + 逐颗落点）。
     * 与 `HudEntryGrid` 走的同一条 [gridPlacementOf]（那边只剩"量尺寸 + placeRelative"），
     * 所以这条桥红了，屏幕上必然跟着红——#74 那一版缺的正是这座桥（数值测准了却没去对撞带高）。
     */
    private fun gridPlaced(zone: HudZone, fontScale: Float, heights: Map<HudEntry, Int>, widths: Map<HudEntry, Int>, perRow: Int = 3): GridPlacement {
        val plan = planOf(fontScale, heights, perRow)
        val items = HudLayoutTable.default().gridItems(zone, plan)
        val gapPx = (hudGridGap(zone).value * density).toInt()
        return gridPlacementOf(
            items,
            items.map { HudSizePx(widths.getValue(it.entry), heights.getValue(it.entry)) },
            HudSizePx(gapPx, gapPx),
            plan.rowPitchOf(zone),
            // #80 的锚定输入：默认表就是 items 推导用的那一份 ⇒ used 档数 == 预留档数，
            // 本文件那些"格网高度对撞带高"的手算期望值一条都没动（这正是"默认观感一个字没改"的证据）
            GridAnchor(zone, HudLayoutTable.default().defaultGridOf(zone, plan))
        )
    }

    /** [gridPlaced] 只看格网尺寸那一支（对撞带高用） */
    private fun gridSize(zone: HudZone, fontScale: Float, heights: Map<HudEntry, Int>, widths: Map<HudEntry, Int>, perRow: Int = 3): HudSizePx =
        gridPlaced(zone, fontScale, heights, widths, perRow).size

    /** 逐颗左上角（拿手摆格长换算，期望值可口算）：与 `HudLayoutGridTest` 同一手法 */
    private fun cellOffset(cell: GridCell, pitch: HudSizePx): HudPointPx =
        HudPointPx(cell.col.coerceAtLeast(0) * pitch.width, cell.row.coerceAtLeast(0) * pitch.height)

    /** 整枚底板（格网 + 上下各一道内边距）对撞带高：溢出必须为 0，px 同一量纲比 */
    private fun assertFits(label: String, gridHeightPx: Int, bandPx: Int, gapPx: Int) {
        val cardPx = gridHeightPx + gapPx * 2
        val overflowPx = cardPx - bandPx
        assertEquals(
            "$label：整枚底板 ${cardPx}px 装不进带高 ${bandPx}px ⇒ 溢出 ${overflowPx}px。" +
                "红了就说明又让某颗异型件去顶行距了（#75 那个回归的原样）",
            0, maxOf(0, overflowPx)
        )
    }

    // ---------- 一、格距：与住户无关，从令牌推 ----------

    @Test
    fun rowPitchComesFromTheChipTierNotFromWhoLivesInTheCell() {
        // 手算三档（density 2.0）：
        // · 竖 Dock 100%：(18 + 12 + 4) × 2 = 68px = 34dp
        // · 竖 Dock 120%：(21.6 + 12 + 4) × 2 = 75.2 → 向上取整 76px = 38dp
        // · 读数块 100%：(18 + 12 + 6) × 2 = 72px = 36dp
        assertEquals(68, gridRowPitchPx(1f, dockGapDp, density))
        assertEquals(76, gridRowPitchPx(1.2f, dockGapDp, density))
        assertEquals(
            "读数块那一档必须与 #74 的实测行高逐字同值（60px 那颗胶囊 + 12px 行距）⇒ 读数块观感一条没变",
            72, gridRowPitchPx(1f, HudRowGapDp, density)
        )
        // 夹子：系统字号低于 100% 不许把行距缩到比默认档还矮（与 hudStripHeightDp / hudPerRowFor 同一条）
        assertEquals(68, gridRowPitchPx(0.8f, dockGapDp, density))
        // **与住户无关**才是本次正文：把配对那一格里那两颗异型件（姿态仪 144px + 音量表 142px）一起换成
        // 30dp 胶囊 ⇒ 那一块的跨度从 3 档掉回 1 档，**格长一个字不动**、格距一个字不动，只是整列少要两档。
        // ⚠ 上一批这里只换 LEVEL 一颗（144→60）：音量表那 142px 仍然把这块的内容高顶着 ⇒ 跨度还是 3、
        // 两份数据算出来都是 468px ⇒ 断言打的是"高度变了"，而本次要证的是"格距没变"，红的是用例自己的账
        //（"一块的高 = 格内最高那颗"是实现语义，由 [cellRowSpan] 与 `rowSpanCeilsContentAgainstTheFixedPitch`
        //  钉着，不许为了这条绿去把它改成"只取被拖那颗"）
        val withTall = gridPlaced(HudZone.RIGHT, 1f, rightHeights100, rightWidths)
        val withoutTall = gridPlaced(
            HudZone.RIGHT, 1f,
            rightHeights100 + mapOf(e(CamPill.LEVEL) to 60, e(CamPill.VOLUME) to 60), rightWidths
        )
        assertEquals("格距 = 胶囊档 + 一道行距，与格子里住了谁无关（#75 的核心那一档）", HudSizePx(180, 68), withTall.pitch)
        assertEquals(
            "换成两颗胶囊之后格长仍是同一个 180×68 ⇒ 谁走回「实测最高那颗 + 行距」这一行当场红",
            HudSizePx(180, 68), withoutTall.pitch
        )
        assertEquals("高的那一版：配对块 3 档 + 四颗各 1 档 = 7 档 ⇒ 7×68 − 8 = 468px", 468, withTall.size.height)
        assertEquals("矮的那一版：人人一档 = 5 档 ⇒ 5×68 − 8 = 332px", 332, withoutTall.size.height)
        assertTrue("高矮只改行数，不许改格距", withTall.size.height > withoutTall.size.height)
        assertEquals("同一容器、只有那颗变矮 ⇒ 宽度账不动（撑 pitch 的仍是那枚配对格 172px）", 172, withTall.size.width)
        assertEquals(172, withoutTall.size.width)
        // 三处消费方共读的那一条出口（渲染层 / 编辑页 / 位置表都经 hudGridRowPitchPx）：
        // 改坏 hudGridGap 的分支或那枚令牌，这几行会先与手算脱钩
        assertEquals(68, hudGridRowPitchPx(HudZone.LEFT, androidx.compose.ui.unit.Density(2f, 1f)))
        assertEquals(68, hudGridRowPitchPx(HudZone.RIGHT, androidx.compose.ui.unit.Density(2f, 1f)))
        assertEquals(72, hudGridRowPitchPx(HudZone.READOUT, androidx.compose.ui.unit.Density(2f, 1f)))
        assertEquals(76, hudGridRowPitchPx(HudZone.RIGHT, androidx.compose.ui.unit.Density(2f, 1.2f)))
        // 双算防护：格距**已经含**那道行距，所以 gridPitchPx 的纵向是透传。
        // 谁把 `+ gapYPx` 加回来（42dp / 38dp 那一族走法）本行就红
        assertEquals(68, gridPitchPx(maxCellWidthPx = 172, rowPitchPx = 68, gapXPx = 8).height)
        assertEquals("横向仍吃实测：最宽那枚格子的内容宽 + 一道列距", 180, gridPitchPx(172, 68, 8).width)
    }

    @Test
    fun rowSpanCeilsContentAgainstTheFixedPitch() {
        // 逐档手算（格距 68px）：60 → 1；68 → 1（刚好等于一档不许变两档）；69 → 2；76 → 2；144 → 3；142 → 3
        assertEquals(1, cellRowSpan(60, 68))
        assertEquals(1, cellRowSpan(68, 68))
        assertEquals(2, cellRowSpan(69, 68))
        assertEquals(2, cellRowSpan(76, 68))
        assertEquals(3, cellRowSpan(144, 68))
        assertEquals(3, cellRowSpan(142, 68))
        // 120% 那一档（格距 76px）：姿态仪实测 150px ⇒ 2 档（两档 152px，**余量只有 1dp**）。
        // 这三行是边界记录：152 → 2、153 → 3。真机若把那颗量成 153px，配对块就多吃一档、
        // 整列多一档 ⇒ 防抖要滚一下才全露（已写进交付报告的"已知缺陷与未验"）
        assertEquals(2, cellRowSpan(150, 76))
        assertEquals(2, cellRowSpan(152, 76))
        assertEquals(3, cellRowSpan(153, 76))
        // 退化输入不许抛、也不许给 0 档（0 档会让格网高度算成负数、吸附除零）
        assertEquals(1, cellRowSpan(0, 68))
        assertEquals(1, cellRowSpan(-10, 68))
        assertEquals(1, cellRowSpan(144, 0))
        // 跨度吃的那个实测高本身：手算式与上面的表同源，改了天地线/内边距/labelSmall 行高这里先红
        assertEquals("100% 那枚姿态仪 = 46 + 5+5 + 2 + 14 = 72dp", 144, (46 + 5 + 5 + 2 + 14) * 2)
    }

    // ---------- 二、跨度进推导：默认行号按整块推进 ----------

    @Test
    fun defaultRowsAdvanceByTheWholeBlockNotByOne() {
        val plan = planOf(1f, rightHeights100 + leftHeights100 + readoutHeights100)
        // 右 Dock：配对块 (0,0) 吃 0/1/2 三档 ⇒ 蓝牙 (0,3)、变焦 (0,4)、对焦 (0,5)、防抖 (0,6)。
        // 把 defaultCellsOf 里 `row += span` 改回 `row++` ⇒ 蓝牙落回 (0,1)，压在配对块身上，本行当场红
        val right = defaultCellsOf(HudZone.RIGHT, HudLayoutTable.defaultOrderOf(HudZone.RIGHT), plan)
        assertEquals(GridCell(0, 0), right[e(CamPill.LEVEL)])
        assertEquals("两颗同格 ⇒ 只有一块", GridCell(0, 0), right[e(CamPill.VOLUME)])
        assertEquals(GridCell(0, 3), right[e(CamPill.BT)])
        assertEquals(GridCell(0, 4), right[e(CamPill.ZOOM)])
        assertEquals(GridCell(0, 5), right[e(CamPill.FOCUS)])
        assertEquals(GridCell(0, 6), right[e(CamPill.STAB)])
        assertEquals("仍然只有**一列**（把高条目拆去第 2 列就是把宽度账做反的那一步）", setOf(0), right.values.map { it.col }.toSet())
        // 左 Dock：两枚 38dp 图标钮各吃 2 档 ⇒ 参考线 (0,0)、监看 (0,2)、曲线 (0,3)、闪光灯 (0,4)
        val left = defaultCellsOf(HudZone.LEFT, HudLayoutTable.defaultOrderOf(HudZone.LEFT), plan)
        assertEquals(GridCell(0, 0), left[e(CamPill.REFLINE)])
        assertEquals(GridCell(0, 2), left[e(CamPill.MONITOR)])
        assertEquals(GridCell(0, 3), left[e(CamPill.CURVE)])
        assertEquals(GridCell(0, 4), left[e(CamPill.FLASH)])
        // 读数块：每颗都是胶囊（60px ≤ 72px 一档）⇒ 行号与 #74 **逐字相同**（这一批没碰它的行为）
        val readout = defaultCellsOf(HudZone.READOUT, HudLayoutTable.defaultOrderOf(HudZone.READOUT), plan)
        assertEquals(GridCell(0, 0), readout[h(HudItem.SHUTTER)])
        assertEquals(GridCell(2, 0), readout[h(HudItem.BITRATE)])
        assertEquals(GridCell(0, 1), readout[h(HudItem.ISO)])
        assertEquals(GridCell(2, 1), readout[h(HudItem.WB)])
        assertEquals(GridCell(0, 2), readout[h(HudItem.ZOOM)])
        // 颗数与归属不受高矮影响
        assertEquals(6, right.size)
        assertEquals(4, left.size)
    }

    @Test
    fun tallCellBlocksEveryRowItCoversAndAMateMayStillLandOnItsAnchor() {
        val rowPitch = 68
        val residents = mapOf(
            GridCell(0, 0) to listOf(e(CamPill.LEVEL), e(CamPill.VOLUME)),
            GridCell(0, 3) to listOf(e(CamPill.BT))
        )
        val heightOf = { entry: HudEntry -> rightHeights100[entry] ?: 0 }
        val derived = defaultCellsOf(
            HudZone.RIGHT, HudLayoutTable.defaultOrderOf(HudZone.RIGHT), planOf(1f, rightHeights100)
        )
        // ① 跨组那颗（蓝牙）眼里三件事同时成立，逐格点名：
        //    · 配对块吃掉的**三档** (0,0)(0,1)(0,2) 全部算占 ⇒ 落不进那一块的中间；
        //    · 别的跨组住户 (0,5) 那一格也算占（矮的那格不许被"整块才算占"那一支漏掉）；
        //    · (0,3) 是**蓝牙自己那一档**，不算挡它自己的路——[HudLayoutTable.placeEntryAt] 第 3 步读的是
        //      `occupiedCells(target, plan, exclude = 被拖那颗)`，把它算进挡路集就等于"原地再摆一次"
        //      也被 [freeCellNear] 甩到别处（那才是真缺陷）。上一批把 (0,3) 写进期望值，红的是用例的账：
        //      那一版既测不出"自格算占"这个实现退化，又与函数 KDoc 那句"挡不挡 self 的路"正面矛盾
        assertEquals(
            setOf(GridCell(0, 0), GridCell(0, 1), GridCell(0, 2), GridCell(0, 5)),
            blockingCells(
                residents + (GridCell(0, 5) to listOf(e(CamPill.ZOOM))),
                e(CamPill.BT), emptySet(), heightOf, rowPitch
            )
        )
        // ② 搭档（音量表）眼里：起始档 (0,0) 仍空（那是"合回配对"的唯一入口），
        //    但 (0,1)(0,2) 算占——不许落进块里与姿态仪画在同一段像素上。
        //    把这条退回"搭档整块都空"，第 2 组期望值就少两格
        assertEquals(
            setOf(GridCell(0, 1), GridCell(0, 2), GridCell(0, 3)),
            blockingCells(residents, e(CamPill.VOLUME), setOf(e(CamPill.LEVEL)), heightOf, rowPitch)
        )
        // ③ self = null 的旧口径：有人就算占 ⇒ 整块 + 那颗蓝牙
        assertEquals(
            setOf(GridCell(0, 0), GridCell(0, 1), GridCell(0, 2), GridCell(0, 3)),
            blockingCells(residents, null, emptySet(), heightOf, rowPitch)
        )
        // ④ 跨度只决定"哪些档算被占"，**不**改变格子身份：同组两颗的推导格仍然相同
        assertEquals(derived.getValue(e(CamPill.LEVEL)), derived.getValue(e(CamPill.VOLUME)))
        // ⑤ 展开算式本身（上面三条读的就是它）：哨兵不占任何档
        assertEquals(listOf(GridCell(1, 4), GridCell(1, 5)), spannedCells(GridCell(1, 4), 2))
        assertEquals(emptyList<GridCell>(), spannedCells(GridCell.DEFAULT, 3))
        // ⑥ 位置表那一侧同一条：occupiedCells 给出的必须是展开后的档
        val occupied = HudLayoutTable.default().occupiedCells(HudZone.RIGHT, planOf(1f, rightHeights100))
        assertTrue(
            "occupiedCells 没把高格的第 2、3 档算进去（$occupied）",
            occupied.containsAll(setOf(GridCell(0, 1), GridCell(0, 2)))
        )
        // ⑦ ①那一条口径走**生产路径**再打一遍：手摆住户表测不到 `exclude` 那一支的接线，
        //    而 placeEntryAt 只读这一条。默认表的挡路全集（全部手算，配对块的三档 + 蓝牙以下三颗，
        //    **不含蓝牙自己的 (0,3)**）⇒ 谁把自格也算成占，这里先红；谁不展开整块，(0,1)(0,2) 缺格也红
        assertEquals(
            setOf(GridCell(0, 0), GridCell(0, 1), GridCell(0, 2), GridCell(0, 4), GridCell(0, 5), GridCell(0, 6)),
            HudLayoutTable.default().occupiedCells(HudZone.RIGHT, planOf(1f, rightHeights100), exclude = e(CamPill.BT))
        )
        // 与之成对：不带 exclude 的旧口径下蓝牙那一档**必须**算占（编辑页判"这格有没有人"读的就是它）
        assertTrue(
            "exclude = null 时 (0,3) 必须算占（$occupied）",
            occupied.contains(GridCell(0, 3))
        )
    }

    // ---------- 三、独立性不许因为跨度而退化（#74 的验收口径，一条没让） ----------

    /**
     * #76：把"应用内文本高度"与"系统字体档"**当成两个独立旋钮**来撞带高。
     *
     * 为什么必须拆开：格距走 [gridRowPitchPx]，吃的是 `Density.fontScale`（系统档），因为
     * `WotaType.chip` 是静态字阶、不吃应用内设置（#72 那条排版双真源）；
     * 而撑起右 Dock 那枚配对块的是姿态仪的状态文字，它走 `MaterialTheme.typography.labelSmall`，
     * **吃应用内 `text_scale_camera`**（`TextScaleLayer` 只换 MaterialTheme）。
     * 于是同一枚格子里，"块的高"与"格距"由两个不同的设置各管一半——
     * 上面那批 `*Heights120` 矩阵把两个旋钮当成同一个数喂，正好漏掉最坏那一角。
     *
     * 块高的构成（dp，与 `AttitudeCard` 的布局输入一一对应）：
     *   天地线 Canvas 46 + `padding(vertical=5)`×2 + `spacedBy(2)` = **58dp 固定**，
     *   再加状态文字行高 `labelSmall.lineHeight 14sp` × 应用档 × 系统档。
     * 格距构成：`WotaType.chip.lineHeight 18sp` × 系统档 + 上下内边距 6+6 + 一道行距 4。
     */
    @Test
    fun theTwoTextScaleKnobsTogetherPushTheRightDockOverTheLandscapeBand() {
        val bandDp = 360f - 44f - 72f          // 横屏带高 = 带宽 − 顶栏 − 底栏
        // (应用内文本高度档, 系统字体档)
        val corners = listOf(
            ScaleCorner("app100 sys100", 1f, 1f),
            ScaleCorner("app120 sys100", 1.2f, 1f),
            ScaleCorner("app100 sys120", 1f, 1.2f),
            ScaleCorner("app120 sys120", 1.2f, 1.2f)
        )
        val overflow = mutableMapOf<String, Float>()
        for (c in corners) {
            // 格距向上取整到整 dp（生产的口径：34dp / 38dp，不是 37.6）
            val pitchDp = kotlin.math.ceil(18.0 * c.systemScale + 16.0).toFloat()
            val blockDp = 58f + 14f * c.appScale * c.systemScale
            val span = Math.ceil((blockDp / pitchDp).toDouble()).toInt().coerceAtLeast(1)
            // 配对块吃 span 档，其余四颗各一档 ⇒ 一档都省不掉
            val rows = span + 4
            val gridDp = rows * pitchDp - 4f
            overflow[c.name] = gridDp - bandDp
            // 走生产纯函数复核跨度，不接受这里只是手算（块高换算成 px 喂进去）
            val heights = rightHeights100 + mapOf(
                e(CamPill.LEVEL) to (blockDp * density).toInt(),
                e(CamPill.VOLUME) to (blockDp * density).toInt()
            )
            val placed = gridPlaced(HudZone.RIGHT, c.systemScale, heights, rightWidths)
            assertEquals(
                "${c.name}：格距必须由系统档推出，不许被应用档带着走",
                (pitchDp * density).toInt(), placed.pitch.height
            )
            assertEquals(
                "${c.name}：网格总高对撞带高（红就说明某个旋钮组合把右 Dock 顶出带外）",
                (gridDp * density).toInt(), placed.size.height
            )
        }
        // 三档装得进、最坏那一角**超带 18dp**：这条断言的是已知极限的测量值。
        assertEquals("app120 sys100 仍装得进", 0f, maxOf(0f, overflow.getValue("app120 sys100")), 0.01f)
        assertEquals("app100 sys120 最松（块只涨一档而格距涨 10%）", 0f, maxOf(0f, overflow.getValue("app100 sys120")), 0.01f)
        assertEquals(
            "#76 已知极限：应用档与系统档**同时** 120% 时配对块要 3 档，7×38−4 = 262dp > 244dp，" +
                "超带 18dp ⇒ 防抖要滚一下才全露。这一条断的是已知极限，真机确认之前不许当成已修",
            18f, overflow.getValue("app120 sys120"), 0.5f
        )
    }

    @Test
    fun movingTheTallEntryLeavesEveryOtherCellAndOffsetUntouched() {
        val rowPitch = gridRowPitchPx(1f, dockGapDp, density)
        val plan = planOf(1f, rightHeights100)
        val hand = HudSizePx(180, rowPitch)   // 手摆格长：期望 px 要能口算
        val before = HudLayoutTable.default().gridItems(HudZone.RIGHT, plan).associate { it.entry to it.cell }
        val moved = HudLayoutTable.default().placeEntryAt(e(CamPill.ZOOM), HudZone.RIGHT, 2, GridCell(0, 7), plan)
        val after = moved.gridItems(HudZone.RIGHT, plan).associate { it.entry to it.cell }
        // 被拖那颗拿到它要的那一格（第 7 档本来就空，不该被就近让位改道）
        assertEquals(GridCell(0, 7), after[e(CamPill.ZOOM)])
        // 其余五颗逐颗点名：格子与屏幕坐标**一字未变**
        // 这一条就是"跨度不许反过来重排已经摆过的条目"——有人拿 span 去推别人的 row 就在这里红
        assertEquals(HudPointPx(0, 0), cellOffset(after.getValue(e(CamPill.LEVEL)), hand))
        assertEquals(HudPointPx(0, 0), cellOffset(after.getValue(e(CamPill.VOLUME)), hand))
        assertEquals("蓝牙还在第 3 档（不许因为变焦走了而上移）", GridCell(0, 3), after[e(CamPill.BT)])
        assertEquals(HudPointPx(0, 204), cellOffset(after.getValue(e(CamPill.BT)), hand))
        assertEquals(GridCell(0, 5), after[e(CamPill.FOCUS)])
        assertEquals(HudPointPx(0, 340), cellOffset(after.getValue(e(CamPill.FOCUS)), hand))
        assertEquals(GridCell(0, 6), after[e(CamPill.STAB)])
        assertEquals(HudPointPx(0, 408), cellOffset(after.getValue(e(CamPill.STAB)), hand))
        assertEquals(HudPointPx(0, 476), cellOffset(after.getValue(e(CamPill.ZOOM)), hand))
        // 差分断言（与手算值互补）：除被拖那颗之外逐颗完全相同
        assertEquals(before.filterKeys { it != e(CamPill.ZOOM) }, after.filterKeys { it != e(CamPill.ZOOM) })
        // 高格挪走之后各块跨度照常：配对块仍吃 3 档，格网 = 8 档 × 68 − 8 = 536px
        val list = after.toList()
        val placed = gridPlacementOf(
            list.map { HudGridItem(it.first, it.second) },
            list.map { HudSizePx(rightWidths.getValue(it.first), rightHeights100.getValue(it.first)) },
            HudSizePx(8, 8), rowPitch,
            // #80：变焦被摆到第 7 档 ⇒ used 8 档 > 预留 7 档，取大 = 8 档 ⇒ 536px 这条期望值一个字没动
            GridAnchor(HudZone.RIGHT, HudLayoutTable.default().defaultGridOf(HudZone.RIGHT, plan))
        )
        assertEquals(536, placed.size.height)
        assertEquals("宽度账与挪动无关（底板仍 94dp）", 172, placed.size.width)
    }

    // ---------- 四、钳制与就近让位吃的是整块 ----------

    @Test
    fun clampAndFreeCellKeepTheWholeBlockInsideTheBand() {
        // 带高 7 档、格距 68px：一颗 144px（3 档）的条目最多只能落在第 4 档（4+3−1 = 6）
        val box = GridBox(cols = 2, rows = 7)
        assertEquals("跨度 3 ⇒ 起始档最多 4（钳的是整块，不是起始行）", GridCell(0, 4), clampCellToBox(GridCell(0, 99), box, 144, 68))
        assertEquals(GridCell(1, 4), clampCellToBox(GridCell(7, 99), box, 144, 68))
        // 同一格、同一带，跨度 1（量不到高）时仍是"钳到最后一档"⇒ 与 #74 逐字同值
        assertEquals(GridCell(0, 6), clampCellToBox(GridCell(0, 99), box, 0, 68))
        // 哨兵原样放行
        assertEquals(GridCell.DEFAULT, clampCellToBox(GridCell.DEFAULT, box, 144, 68))
        // 带比块还矮时夹成第 0 档，不许给 coerceIn 造出空区间（那一抛整个 HUD 就没了）
        assertEquals(GridCell(0, 0), clampCellToBox(GridCell(0, 5), GridBox(2, 2), 144, 68))
        // 就近让位也要拒"块尾出带"的候选：1 列 4 档、(0,0) 已占、指针落在 (0,3)
        // 跨度 3 ⇒ 可用最底档 = 4−3 = 1 ⇒ 只能退到 (0,1)（(0,2) 出带、(0,0) 被占）
        assertEquals(GridCell(0, 1), freeCellNear(GridCell(0, 3), setOf(GridCell(0, 0)), 1, 4, 144, 68))
        // 同一入参、跨度 1 ⇒ 就地落 (0,3)。这两行**成对**才测得出"出带"那条判据真的在跑
        assertEquals(GridCell(0, 3), freeCellNear(GridCell(0, 3), setOf(GridCell(0, 0)), 1, 4, 0, 68))
    }

    @Test
    fun cellPlaceOffsetCentersAcrossTheWholeBlock() {
        val pitch = HudSizePx(180, 68)
        // 一档：与 #74 逐字同值（(68 − 60) / 2 = 4）
        assertEquals(HudPointPx(60, 4), cellPlaceOffsetPx(GridCell(0, 0), pitch, HudSizePx(60, 60), 1))
        // 三档：块高 204px、那颗 144px ⇒ 上下各留 30px。
        // 顶到块的天花板（写成 `y = row * pitch`）会压到下一块的第一颗；贴底同理，两种走法都在这里红
        assertEquals(HudPointPx(0, 30), cellPlaceOffsetPx(GridCell(0, 0), pitch, HudSizePx(180, 144), 3))
        // 第 4 档起步的三档块：y = 4 × 68 + 30 = 302
        assertEquals(HudPointPx(60, 302), cellPlaceOffsetPx(GridCell(0, 4), pitch, HudSizePx(60, 144), 3))
    }

    // ---------- 五、编辑页的吸附不许再把网格高除回来当行距 ----------

    @Test
    fun editorSnapPitchIgnoresTheGridHeight() {
        // 列长仍从网格实测矩形逆算：172px 宽、1 列、行距 8 ⇒ 180
        assertEquals(
            "纵向必须等于喂进来的格距，而不是 (网格高 ÷ 行数) 除回来的那个数",
            HudSizePx(180, 68),
            snapGridPitchPx(gridWidthPx = 172, cols = 1, gapPx = 8, fallbackChildWidthPx = 90, rowPitchPx = 68)
        )
        // 带跨度之后网格高与"档数 × 行距"不再互逆：7 档 × 68 − 8 = 468px，而 max(row)+1 = 5，
        // 逆算给的是 (468 + 8) / 5 = 95px ⇒ 吸附会画在一格、落到另一格。这一行红就说明有人换回逆算了
        assertEquals(
            HudSizePx(174, 68),
            snapGridPitchPx(gridWidthPx = 340, cols = 2, gapPx = 8, fallbackChildWidthPx = 1, rowPitchPx = 68)
        )
        // 空容器（一列都还没量到）退回"被拖那颗的宽 + 行距"，纵向照旧是格距
        assertEquals(HudSizePx(98, 68), snapGridPitchPx(0, 0, 8, 90, 68))
    }

    // ---------- 六、网格高对撞带高（#74 漏掉的那一类，横屏满配、两档字体各一份） ----------

    /**
     * 三枚网格容器各自对撞自己的带高，100% 与 120% 各一份。
     *
     * 带高（`zoneBandHeight` = 安全区高 − 顶栏实测 − 底栏实测，本机横屏）：
     * · 两枚竖 Dock = 360 − 44 − 72 = **244dp = 488px**；
     * · 读数块与底栏**共用那条基线**（#70 A），它的 `bottomAvoidDp` 是 0 ⇒ 360 − 44 = **316dp = 632px**。
     * 比的是"整枚底板（格网 + 底板上下各一道内边距）"，不是格网自己——只比格网会把底板内边距漏掉。
     *
     * 断言的是**溢出为 0**，不是 `placed.size` 那个数本身：#74 那一版正是把 742px 测得很准、
     * 却从没拿它去比带高，才让 142dp 的溢出混过去（AGENTS"恒等式不算证明"的新变体）。
     */
    @Test
    fun landscapeBandCollisionIsZeroAtBothTextScales() {
        val dockBandPx = (360 - 44 - 72) * 2      // 488px
        val readoutBandPx = (360 - 44) * 2        // 632px

        // ---- 100%（格距 68px = 34dp）----
        // 右 Dock：配对块 3 档 + 四颗各 1 档 = 7 档 ⇒ 7×68 − 8 = 468px = 234dp
        assertEquals(468, gridSize(HudZone.RIGHT, 1f, rightHeights100, rightWidths).height)
        // 左 Dock：两枚图标钮各 2 档 ⇒ 6 档 ⇒ 6×68 − 8 = **400px = 200dp**
        //（上一批这里手算成 392，是 408 − 8 那道减法口算错了；带高那一侧的结论一个字没变：
        // 200dp 仍装得进 244dp。改期望值之前先核实现——同一把尺子在右 Dock 那行给的是 7×68−8 = 468，
        // 与 `rightDockGridNowFitsTheLandscapeBand…` 独立钉住的数一致，所以是实现侧没有双算行距）
        assertEquals(400, gridSize(HudZone.LEFT, 1f, leftHeights100, leftWidths).height)
        // 读数块：格距 72px、一行两颗 ⇒ 4 行 × 72 − 12 = 276px = 138dp
        assertEquals(276, gridSize(HudZone.READOUT, 1f, readoutHeights100, readoutWidths, perRow = 2).height)
        assertFits("右 Dock 100%", 468, dockBandPx, 8)
        assertFits("左 Dock 100%", 400, dockBandPx, 8)
        assertFits("读数块 100%", 276, readoutBandPx, 12)

        // ---- 120%（格距 76px = 38dp；读数块 ceil((33.6+6)×2) = 80px）----
        // 右 Dock：配对块 150px ≤ 两档(152px) ⇒ 2+4 = 6 档 ⇒ 6×76 − 8 = 448px = 224dp
        assertEquals(448, gridSize(HudZone.RIGHT, 1.2f, rightHeights120, rightWidths).height)
        // 左 Dock：76px 刚好等于一档 ⇒ 4 档 ⇒ 296px = 148dp
        assertEquals(296, gridSize(HudZone.LEFT, 1.2f, leftHeights120, leftWidths).height)
        // 读数块：4 行 × 80 − 12 = 308px = 154dp
        assertEquals(308, gridSize(HudZone.READOUT, 1.2f, readoutHeights120, readoutWidths, perRow = 2).height)
        assertFits("右 Dock 120%", 448, dockBandPx, 8)
        assertFits("左 Dock 120%", 296, dockBandPx, 8)
        assertFits("读数块 120%", 308, readoutBandPx, 12)

        // ---- "露出的颗数"：逐颗点名底边，不许只数 placed.size ----
        val plan100 = planOf(1f, rightHeights100)
        val items = HudLayoutTable.default().gridItems(HudZone.RIGHT, plan100)
        val placed = gridPlacementOf(
            items, items.map { HudSizePx(rightWidths.getValue(it.entry), rightHeights100.getValue(it.entry)) },
            HudSizePx(8, 8), plan100.rowPitchOf(HudZone.RIGHT),
            GridAnchor(HudZone.RIGHT, HudLayoutTable.default().defaultGridOf(HudZone.RIGHT, plan100))
        )
        val bottoms = items.mapIndexed { i, it -> it.entry to placed.offsets[i].y + rightHeights100.getValue(it.entry) }
        // 手算（100%）：对焦 5×68 + 4 = 344 ⇒ 底边 404px；防抖 6×68 + 4 = 412 ⇒ 底边 472px；
        // 加底板内边距 8px ⇒ 480px ≤ 带底 488px ⇒ **六颗全部露出**（缺陷那一版对焦与防抖整颗在带外）
        assertEquals(404, bottoms.first { it.first == e(CamPill.FOCUS) }.second)
        assertEquals(472, bottoms.first { it.first == e(CamPill.STAB) }.second)
        assertEquals(
            "100% 满配横屏：露出的颗数（底边 + 内边距落在带内）",
            6, bottoms.count { it.second + 8 <= dockBandPx }
        )
        // 120% 同样六颗全露：防抖底边 = 5×76 + 4 + 68 = 452px ≤ 488px
        val plan120 = planOf(1.2f, rightHeights120)
        val items120 = HudLayoutTable.default().gridItems(HudZone.RIGHT, plan120)
        val placed120 = gridPlacementOf(
            items120, items120.map { HudSizePx(rightWidths.getValue(it.entry), rightHeights120.getValue(it.entry)) },
            HudSizePx(8, 8), plan120.rowPitchOf(HudZone.RIGHT),
            GridAnchor(HudZone.RIGHT, HudLayoutTable.default().defaultGridOf(HudZone.RIGHT, plan120))
        )
        val bottoms120 = items120.mapIndexed { i, it -> it.entry to placed120.offsets[i].y + rightHeights120.getValue(it.entry) }
        assertEquals(
            "120% 满配横屏：露出的颗数",
            6, bottoms120.count { it.second + 8 <= dockBandPx }
        )
    }
}

/** #76 那个角落矩阵的一行：两个旋钮各给一档 */
private data class ScaleCorner(val name: String, val appScale: Float, val systemScale: Float)
