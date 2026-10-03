package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.GridBox
import com.wotagei.cam.ui.GridCell
import com.wotagei.cam.ui.GridRowHardCap
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudPageCell
import com.wotagei.cam.ui.HudPointPx
import com.wotagei.cam.ui.HudRowGapDp
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.clampCellToBox
import com.wotagei.cam.ui.editorVisibleEntries
import com.wotagei.cam.ui.freeCellNear
import com.wotagei.cam.ui.gridRowPitchPx
import com.wotagei.cam.ui.hudGridGap
import com.wotagei.cam.ui.pageCellAtPointer
import com.wotagei.cam.ui.pageCellOffsetPx
import com.wotagei.cam.ui.pageCellPx
import com.wotagei.cam.ui.pageCellsBlocked
import com.wotagei.cam.ui.pageGridBoxOf
import com.wotagei.cam.ui.pageGridColCap
import com.wotagei.cam.ui.pageGridRowCap
import com.wotagei.cam.ui.spannedCells
import com.wotagei.cam.ui.toGridCell
import com.wotagei.cam.ui.toPageCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务 **P1**（docs/plan/17 §六）：整页网格的坐标数学 + 编辑页可见性口径（§十一 2）。
 *
 * 本文件**还没有**任何"落点"用例——那是 P3 的事（`placeEntryAt` 的 PAGE 支、两页渲染）。
 * P1 只钉四样：页格数学（纯函数、手算档）、指针往返、占用判据（与 Dock 那一侧同族）、
 * 编辑页可见集（录制页口径一字不动）。
 *
 * ## 期望值全部**手算**，不许从实现回抄
 * 手算档（density 2.0，与 `HudLayoutRowPitchTest` 同一档）：
 * · 页格边长 = [gridRowPitchPx]：100% 档 (18 + 12 + 4) × 2 = **68px = 34dp**、
 *   120% 档 (21.6 + 12 + 4) × 2 = 75.2 → 向上取整 **76px = 38dp**、系统字号 <100% 被夹回 1f ⇒ 仍是 68px；
 * · 横屏安全区 = 1600×720px 里挖孔让掉左缘 ⇒ **1532px 宽（766dp）× 720px 高（360dp）**
 *   （plan/17 §六 P1 的手算档就是这么来的。AGENTS.md 记着那条教训：别信 dump 的表观右缘，
 *   可视边界读 `mAppBounds` / `DisplayCutout`——这里 1532 只是**输入**，不是本文件的断言对象）
 *   ⇒ 100% 档 1532 ÷ 68 = **22 列**（余 36px 弃掉）、720 ÷ 68 = **10 行**（余 40px 弃掉）；
 *   120% 档 1532 ÷ 76 = **20 列**（余 12px）、720 ÷ 76 = **9 行**（余 36px）。
 *
 * ## 恒等式不算证明（AGENTS.md）
 * 所以"页格与 Dock 同源"是拿两个**独立**算式对撞（[pageCellPx] vs [gridRowPitchPx]），
 * "末格弃掉"是拿 ceil 会给出的 23 列 / 11 行当反面对照，"整块判据"是拿 span=1 的对照支证明它在跑。
 */
class HudPageGridTest {

    /** 面板 density 2.0（与 `HudLayoutRowPitchTest` / `HudLayoutPairCellTest` 同一档） */
    private val density = 2f

    private fun e(p: CamPill) = HudEntry.of(p)
    private fun h(i: HudItem) = HudEntry.of(i)

    /** 横屏安全区（px）：宽 766dp（挖孔让掉左缘的那一版）、高 360dp */
    private val safeWPx = 1532
    private val safeHPx = 720

    /** 左竖 Dock 的行距档：令牌 `WotaSpace.xs` = 4dp，页格吃的是同一档（拍板①甲案） */
    private val dockGapDp = hudGridGap(HudZone.LEFT).value

    /**
     * 一帧网格解析的最小输入：实测高喂 0 ⇒ 跨度恒 1（= 首帧还没量到那一帧的退化态），
     * 可见集由调用方点名——两页的差别全在这一个集合里。
     */
    private fun planOf(visible: Set<HudEntry>): HudGridPlan = HudGridPlan(
        visible = visible,
        readoutPerRow = 3,
        rowPitchOf = { 68 },
        cellHeightOf = { 0 }
    )

    // ---------- 一、格长：与 Dock 内格同源同尺 ----------

    @Test
    fun cellPxIsTheDockTierNotTheReadoutTier() {
        // 手算三档：100% = (18+12+4)×2 = 68px；120% = (21.6+12+4)×2 = 75.2 → 76px；0.8 档被夹回 1f ⇒ 68px
        assertEquals(68, pageCellPx(1f, density))
        assertEquals(76, pageCellPx(1.2f, density))
        assertEquals(68, pageCellPx(0.8f, density))
        // 同源：页格边长必须与两枚竖 Dock 的格**逐字同一个函数、同一档行距**。
        // 谁把 pageCellPx 的行距档换成读数块的 [HudRowGapDp]（6dp），下一页给 72 ≠ 68，本行红
        assertEquals(
            "页格边长 = 竖 Dock 那一档格距（拍板①甲案：页格与 Dock 内格同源同尺）",
            gridRowPitchPx(1f, dockGapDp, density), pageCellPx(1f, density)
        )
        // 反过来钉一句：读数块那一档（6dp ⇒ 72px）**不是**页格这一档。两条同时在才说得清"跟的是 Dock"
        assertTrue(
            "页格不许跟着读数块那一档走（6dp ⇒ 72px）",
            gridRowPitchPx(1f, HudRowGapDp, density) != pageCellPx(1f, density)
        )
        // 夹子：格距不许被字号档压到比默认档还矮（与 hudChipHeightDp 里那一夹同源）
        assertTrue("字号档只能让格变密或不变，不许变稀", pageCellPx(0.8f, density) == pageCellPx(1f, density))
    }

    // ---------- 二、安全区 → 列数 / 行数（floor，末格不足一格弃掉） ----------

    @Test
    fun gridBoxFloorsTheSafeAreaAndDropsTheLastPartialCell() {
        // 手算：1532 ÷ 68 = 22.53 → **22 列**；720 ÷ 68 = 10.59 → **10 行**
        val box = pageGridBoxOf(safeWPx, safeHPx, 68)
        assertEquals("横屏 100% 档：766dp ÷ 34dp = 22 列（plan/17 §六 P1 的手算档）", 22, box.cols)
        assertEquals("360dp ÷ 34dp = 10 行（余 40px 不够一格，弃掉）", 10, box.rows)
        // 末格弃掉的账：22 × 68 = 1496 ⇒ 右边剩 36px；10 × 68 = 680 ⇒ 下边剩 40px
        assertEquals(36, safeWPx - box.cols * 68)
        assertEquals(40, safeHPx - box.rows * 68)
        assertTrue(
            "弃掉的那截必须**不够一格**（够一格却弃了就是少算一列/一行）",
            safeWPx - box.cols * 68 < 68 && safeHPx - box.rows * 68 < 68
        )
        // 反面对照：改成 ceil 会把这两行变成 23 列 / 11 行——那正是"补一个窄格"的走法
        assertFalse("不许向上取整补格（那样会给 23 列）", box.cols == 23)
        assertFalse("不许向上取整补格（那样会给 11 行）", box.rows == 11)
        // 换一个"差 1px 就够一格"的安全区：余量 67px 也弃 ⇒ 仍是 22 列
        assertEquals("余 67px（差 1px 够一格）也弃 ⇒ 22 列", 22, pageGridBoxOf(22 * 68 + 67, safeHPx, 68).cols)
        // 宽正好铺满 22 列 ⇒ 余量 0，列数不变（这条防"整格被当成余量"）
        assertEquals("宽正好 22 整格 ⇒ 22 列", 22, pageGridBoxOf(22 * 68, 720, 68).cols)
        // 120% 档：格长 76px ⇒ 1532 ÷ 76 = 20.15 → 20 列；720 ÷ 76 = 9.47 → 9 行
        val box120 = pageGridBoxOf(safeWPx, safeHPx, 76)
        assertEquals("120% 档：766dp ÷ 38dp = 20 列（余 12px）", 20, box120.cols)
        assertEquals("120% 档：360dp ÷ 38dp = 9 行（余 36px）", 9, box120.rows)
        // 退化输入不抛、不除零：格长没算出来那一帧给 1×1（与 cellAtPointer 的 pitch<=0 支同族）
        assertEquals(GridBox(1, 1), pageGridBoxOf(safeWPx, safeHPx, 0))
        assertEquals(GridBox(1, 1), pageGridBoxOf(-10, -10, 68))
        assertEquals(GridBox(1, 1), pageGridBoxOf(0, 0, 68))
    }

    // ---------- 三、指针 ↔ 格往返 ----------

    @Test
    fun pointerRoundTripsThroughTheCellOffset() {
        val cell = 68
        // 逐个手算（局部坐标 = 指针 − 原点 (10,20)，floor ÷ 68）：
        // (10,20)→局部(0,0)→(0,0)；(77,88)→局部(67,68)→(0,1)；(78,89)→局部(68,69)→(1,1)；
        // (486,420)→局部(476,400)→476÷68=7、400÷68=5⇒(7,5)；(1495,679)→局部(1485,659)→(21,9)
        val expected = mapOf(
            (10 to 20) to HudPageCell(0, 0),
            (77 to 88) to HudPageCell(0, 1),
            (78 to 89) to HudPageCell(1, 1),
            (486 to 420) to HudPageCell(7, 5),
            (1495 to 679) to HudPageCell(21, 9)
        )
        expected.forEach { (p, want) ->
            assertEquals(
                "指针 (${p.first},${p.second}) 必须压住 $want",
                want, pageCellAtPointer(p.first, p.second, 10, 20, cell)
            )
        }
        // 逆算式：格 → 左上角（原点 0,0 时就是"格号 × 格长"本身，与 cellOffsetPx 同族）
        assertEquals(HudPointPx(0, 0), pageCellOffsetPx(HudPageCell(0, 0), cell))
        assertEquals(HudPointPx(68, 68), pageCellOffsetPx(HudPageCell(1, 1), cell))
        assertEquals(HudPointPx(7 * 68, 5 * 68), pageCellOffsetPx(HudPageCell(7, 5), cell))
        // 遍历式往返：**每一个落在已铺满区域里的指针**，offset 都不大于它、且差不到一格
        // （这一条就是"逆算式互逆"的判据：谁把 col 多加 1、或乘了别的因子，这里红）
        var checked = 0
        for (gx in 0 until 22) {
            for (gy in 0 until 10) {
                // 每格取左上角与右下角前 1px 两个点（格边界那一线上归右/下属下一格，另测）
                for ((dx, dy) in listOf(0 to 0, 67 to 67)) {
                    val px = gx * cell + dx
                    val py = gy * cell + dy
                    val c = pageCellAtPointer(px, py, 0, 0, cell)
                    assertEquals("格内点 ($px,$py) 的列号", gx, c.col)
                    assertEquals("格内点 ($px,$py) 的行号", gy, c.row)
                    val off = pageCellOffsetPx(c, cell)
                    assertTrue("格左上角不许越过指针", off.x <= px && off.y <= py)
                    assertTrue("指针必须在格内（差不到一格）", px - off.x < cell && py - off.y < cell)
                    checked++
                }
            }
        }
        assertEquals("往返点数（22 列 × 10 行 × 每格 2 点）", 22 * 10 * 2, checked)
    }

    @Test
    fun outOfRangePointerClampsBelowButNeverClampsAbove() {
        // 与 cellAtPointer 同族：负的局部坐标钳到第 0 格
        assertEquals(HudPageCell(0, 0), pageCellAtPointer(-5, -5, 0, 0, 68))
        // 原点在 (10,20) 时指针 (-5,-5) 的局部坐标是 (-15,-25) ⇒ 仍钳到 (0,0)（floor 与截断在这支同答案）
        assertEquals(HudPageCell(0, 0), pageCellAtPointer(-5, -5, 10, 20, 68))
        // 格长没算出来 ⇒ (0,0)，不除零（与 cellAtPointer 的 pitch<=0 支同族）
        assertEquals(HudPageCell(0, 0), pageCellAtPointer(500, 500, 0, 0, 0))
        // 越出安全区**不钳上界**：索引函数只管"哪一格"，钳盒子是调用方的事
        //（Dock 那一侧同一条分工：cellAtPointer 不钳、clampCellToBox 才钳）
        val outside = pageCellAtPointer(2000, 900, 0, 0, 68)
        assertEquals("2000 ÷ 68 = 29（越出 22 列的运行时盒子）", 29, outside.col)
        assertEquals("900 ÷ 68 = 13（越出 10 行的运行时盒子）", 13, outside.row)
        // 所以调用方必须自己拿 pageGridBoxOf 的盒子钳——这两行是那条分工的证据
        val box = pageGridBoxOf(safeWPx, safeHPx, 68)
        assertTrue("索引函数给出的列可能越出运行时盒子 ⇒ 调用方必须钳", outside.col >= box.cols)
        assertTrue("索引函数给出的行可能越出运行时盒子 ⇒ 调用方必须钳", outside.row >= box.rows)
        // 而钳制那一侧是现成的（Dock 那一族的 clampCellToBox，页格盒子直接喂得进去）
        assertEquals(
            "钳回来落在盒子的右下角 (21,9)", GridCell(21, 9),
            clampCellToBox(outside.toGridCell(), box, 0, 68)
        )
    }

    // ---------- 四、占用判据：整块不相交 ----------

    @Test
    fun cellBlockedTakesTheWholeBlockAndItsTailPressesAnchors() {
        // 实测高（与 HudLayoutRowPitchTest 同一批手算值）：姿态仪 144px ÷ 68px = 3 档
        val heights = mapOf(e(CamPill.LEVEL) to 144, e(CamPill.VOLUME) to 142)
        val heightOf = { entry: HudEntry -> heights[entry] ?: 0 }
        val residents = mapOf(
            HudPageCell(0, 0) to listOf(e(CamPill.LEVEL)),
            HudPageCell(0, 3) to listOf(e(CamPill.BT))
        )
        // ① self = null（旧口径"有人就算占"）：配对块的三档 (0,0)(0,1)(0,2) + 蓝牙 (0,3)
        assertEquals(
            setOf(HudPageCell(0, 0), HudPageCell(0, 1), HudPageCell(0, 2), HudPageCell(0, 3)),
            pageCellsBlocked(residents, null, emptySet(), heightOf, 68)
        )
        // ② 蓝牙眼里：别人的三档全算占，自己那一档 (0,3) 不算（"原地再摆一次"也被挡就是判据退化）
        assertEquals(
            setOf(HudPageCell(0, 0), HudPageCell(0, 1), HudPageCell(0, 2)),
            pageCellsBlocked(residents, e(CamPill.BT), emptySet(), heightOf, 68)
        )
        // ③ 搭档（音量表）眼里：起始档 (0,0) 不挡（合回配对的入口），块的中间两档照旧挡
        assertEquals(
            setOf(HudPageCell(0, 1), HudPageCell(0, 2), HudPageCell(0, 3)),
            pageCellsBlocked(residents, e(CamPill.VOLUME), setOf(e(CamPill.LEVEL)), heightOf, 68)
        )
        // ④ 哨兵格不占任何档（没摆过的条目不挡路）
        assertEquals(
            emptySet<HudPageCell>(),
            pageCellsBlocked(mapOf(HudPageCell.DEFAULT to listOf(e(CamPill.LEVEL))), null, emptySet(), heightOf, 68)
        )
        // ⑤ **跨格块的尾巴压锚点要挡**：候选 (0,0) 是 144px ⇒ 3 档 (0,0)(0,1)(0,2)，
        //    其中 (0,1) 正是别颗（蓝牙）的锚点 ⇒ 整块与占位集相交 ⇒ 挡
        val candidate = HudPageCell(0, 0)
        val blocked = pageCellsBlocked(
            mapOf(HudPageCell(0, 1) to listOf(e(CamPill.BT))), null, emptySet(), heightOf, 68
        )
        val block = spannedCells(candidate.toGridCell(), 3).map { it.toPageCell() }
        assertEquals(3, block.size)
        assertTrue("候选三档里有一档被占（尾巴 (0,1) 压住蓝牙的锚点）", block.any { it in blocked })
        // ⑥ 同族判据在**生产路径**上：把这份占位集喂回 [freeCellNear]，它不许落在压人的 (0,0)，就近退 (1,0)
        //    （旧式"只查锚点"会给回 (0,0)——那正是 2026-10-02 升级整块判据要修的形状）
        assertEquals(
            HudPageCell(1, 0),
            freeCellNear(
                candidate.toGridCell(), blocked.map { it.toGridCell() }.toSet(), 2, 4, 144, 68
            ).toPageCell()
        )
        // ⑦ 成对对照：同一颗换成 1 档（量不到高）时就该落在 (0,0)。没有这一条，⑥ 测不出"整块"真的在跑
        assertEquals(
            HudPageCell(0, 0),
            freeCellNear(
                candidate.toGridCell(), blocked.map { it.toGridCell() }.toSet(), 2, 4, 0, 68
            ).toPageCell()
        )
    }

    // ---------- 五、编辑页可见集（§十一 2）：录制页口径一字不动 ----------

    @Test
    fun editorPageListsMaskedOffEntriesWhileTheRecordingPageStillFiltersThem() {
        // 掩码输入：关掉「监看」与「闪光」两颗（hud_pills）；读数只留快门/帧率（hud_items 默认档）
        val pillMask = CamPill.maskOf(CamPill.ALL - CamPill.MONITOR - CamPill.FLASH)
        val hudMask = HudItem.DEFAULT_MASK
        val hiddenPills = CamPill.hiddenOf(pillMask)
        assertEquals(setOf(CamPill.MONITOR, CamPill.FLASH), hiddenPills)
        // 录制页那一份（与 CameraScreen 同一条规则）：掩码 + 码率不进可见集
        val recordingVisible: Set<HudEntry> = buildSet {
            CamPill.ALL.filter { it !in hiddenPills }.forEach { add(HudEntry.of(it)) }
            HudItem.typesOf(hudMask).filter { it != HudItem.BITRATE }.forEach { add(HudEntry.of(it)) }
        }

        // ① 编辑页可见集**含**被掩码关掉的那两颗（§11.2 的核心那一句）
        val editorVisible = editorVisibleEntries(levelEnabled = true)
        assertTrue("编辑页必须列出被掩码关掉的监看", e(CamPill.MONITOR) in editorVisible)
        assertTrue("编辑页必须列出被掩码关掉的闪光灯", e(CamPill.FLASH) in editorVisible)
        // 码率是唯一例外（10-01 布局批：Dock 外固定读数，不进两页的可见集）
        assertFalse("码率不进编辑页可见集", h(HudItem.BITRATE) in editorVisible)
        assertEquals("编辑页可见集 = 全集 20 − 码率 = 19 颗", 19, editorVisible.size)
        // 水平仪的显示开关关掉时那颗整颗不渲染 ⇒ 编辑页不列（理由在 editorVisibleEntries 的 KDoc 里）
        assertFalse(
            "显示开关关掉水平仪时编辑页不列它", e(CamPill.LEVEL) in editorVisibleEntries(levelEnabled = false)
        )
        assertTrue("显示开关打开时编辑页列它", e(CamPill.LEVEL) in editorVisibleEntries(levelEnabled = true))

        // ② 同一份表、同一个 visibleOrderOf：换可见集就换答案 ⇒ 录制页口径一字未动
        val table = HudLayoutTable.default()
        assertEquals(
            "录制页：掩码关掉的两颗被过滤掉（顺序仍来自表）",
            listOf(e(CamPill.REFLINE), e(CamPill.CURVE)),
            table.visibleOrderOf(HudZone.LEFT, recordingVisible)
        )
        assertEquals(
            "编辑页：同样的表、同样的函数，四颗全列",
            listOf(e(CamPill.REFLINE), e(CamPill.MONITOR), e(CamPill.CURVE), e(CamPill.FLASH)),
            table.visibleOrderOf(HudZone.LEFT, editorVisible)
        )

        // ③ 被掩码关掉的那颗**能在编辑页写格**，且 token 进编码串（防"隐藏即摘表"）
        val placed = table.placeEntryAt(e(CamPill.MONITOR), HudZone.LEFT, 1, GridCell(1, 0), planOf(editorVisible))
        assertEquals(
            "监看被摆到左 Dock 第 2 列第 0 档",
            GridCell(1, 0), placed.cellsOf(HudZone.LEFT).getValue(e(CamPill.MONITOR))
        )
        assertTrue("编码串保留这颗的 token（P7:1.0）", placed.encode().contains("P7:1.0"))

        // ④ 录制页把它藏着的时候格子仍在（隐藏不写表 ⇒ 再显示不回默认）
        assertTrue(
            "录制页仍不渲染它",
            placed.gridItems(HudZone.LEFT, planOf(recordingVisible)).none { it.entry == e(CamPill.MONITOR) }
        )
        assertEquals(
            "但格子还在表里（隐藏 ≠ 摘表）",
            GridCell(1, 0), placed.cellsOf(HudZone.LEFT).getValue(e(CamPill.MONITOR))
        )
        // 用户把显示打开 ⇒ 回到自己那一格（而不是默认推导格 (0,1)）
        val reenabled = placed.gridItems(
            HudZone.LEFT, planOf(recordingVisible + e(CamPill.MONITOR))
        ).first { it.entry == e(CamPill.MONITOR) }
        assertEquals("恢复显示即回到被摆的那一格", GridCell(1, 0), reenabled.cell)
        assertFalse("不许退回默认推导格 (0,1)", reenabled.cell == GridCell(0, 1))
    }

    // ---------- 六、存储层上限：护栏，不是把运行时格数写死 ----------

    @Test
    fun pageGridCapsAreGenerousStorageGuardsAroundTheRuntimeBox() {
        val box = pageGridBoxOf(safeWPx, safeHPx, 68)
        assertEquals("行上限沿用 Dock 那一档的量级（条目总数 20）", HudEntry.ALL.size, pageGridRowCap())
        assertEquals("行上限就是 GridRowHardCap 那一档（一个字没改）", GridRowHardCap, pageGridRowCap())
        assertEquals("列上限 = 条目数 × 2（每颗独占一列的极端表再留一倍余量）", 2 * HudEntry.ALL.size, pageGridColCap())
        // 桥：运行时的盒子必须落得进存储层的护栏
        assertTrue("本机横屏运行时 22 列必须落得进列护栏", box.cols <= pageGridColCap())
        assertTrue("本机横屏运行时 10 行必须落得进行护栏", box.rows <= pageGridRowCap())
        assertTrue("护栏要真的宽过运行时（不然等于把运行时的格数写死了）", pageGridColCap() > box.cols)
    }
}
