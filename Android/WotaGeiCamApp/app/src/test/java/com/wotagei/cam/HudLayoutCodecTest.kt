package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudEntryKind
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.ZonePlacement
import com.wotagei.cam.ui.GridCell
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
    /** #74：格子解析要的两份运行时输入。测试里一律"全部可见 + 读数块一行 3 颗"，
     * 与 [HudLayoutTable.default] 那张表的默认推导档同源（`hudPerRowFor` 在 360dp 宽给 3）。
     * #75 添的两份在这里取"手摆一档 + 量不到高"：本文件测的是**编解码与迁移**，
     * 跨度按 1 才能让"格子后缀原样往返"那几条期望值与 #74 逐字相同（跨度那一档另开文件打）。 */
    private val planAll = HudGridPlan(
        visible = HudEntry.ALL.toSet(),
        readoutPerRow = 3,
        rowPitchOf = { 30 },
        cellHeightOf = { 0 }
    )


    private fun assertEachEntryOnce(table: HudLayoutTable, tag: String) {
        val all = table.allEntries()
        assertEquals("$tag：条目总数必须等于 13 颗 CamPill + 7 颗读数", HudEntry.ALL.size, all.size)
        assertEquals("$tag：同一颗控件不许出现在两枚容器里", all.size, all.toSet().size)
        assertEquals("$tag：表里必须覆盖全部可编辑条目", HudEntry.ALL.toSet(), all.toSet())
    }

    @Test
    fun encodeKeepsFiveZoneRecords() {
        val raw = HudLayoutTable.default().encode()
        assertTrue("版本段必须是 v2（#74 起每颗条目带格子），改格式就要 +1 并在 decode 里处置旧串",
            raw.startsWith("v2;"))
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
        // #74 新增的两条格式约束：
        // ① **网格容器**（左/右 Dock、读数块）每颗都带 `:格子` 后缀，默认表里全是哨兵 `-`；
        //    实现若退回头都不写后缀，v2 与 v1 就同串了，格子存不下也读不出。
        val left = raw.split(';').first { it.startsWith("L,") }.split(',').drop(3)
        assertTrue("左 Dock 每颗都必须带 :格子 后缀：$raw", left.all { it.endsWith(":-") })
        // ② **非网格容器**（顶栏/底栏）不许带后缀：那两枚按顺序排，写了没人读就是脏数据
        val top = raw.split(';').first { it.startsWith("T,") }.split(',').drop(3)
        assertTrue("顶栏不该出现格子后缀（本批不上网格）：$raw", top.none { it.contains(':') })
        assertTrue(top.contains("P11"))
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
            .moveEntryTo(HudEntry.of(CamPill.CURVE), HudZone.TOP, 1, planAll)
            // #74：把左 Dock 的「闪光灯」摆到第 1 列第 3 行（一列之外的格子，只有格子模型表达得出来）
            .placeEntryAt(
                HudEntry.of(CamPill.FLASH), HudZone.LEFT, 3, GridCell(1, 3), planAll
            )
        val back = HudLayoutTable.decode(edited.encode())
        assertEquals("编解码必须无损回到同一张表", edited, back)
        assertEquals(ZonePlacement(612, 88), back.posOf(HudZone.RIGHT))
        assertEquals(HudZone.TOP, back.sourceZoneOf(HudEntry.of(CamPill.CURVE)))
        // 格子本身也要无损：光看"整张表相等"看不出 encode 漏写后缀（decode 会拿默认格子补齐，
        // 而默认格子恰好等于钉住之前那一格，表照样相等——只有直接点格子值才测得出来）
        assertEquals("摆过的格子必须原样读回来", GridCell(1, 3), back.cellsOf(HudZone.LEFT)[HudEntry.of(CamPill.FLASH)])
        assertEachEntryOnce(back, "回读表")
    }

    @Test
    fun placedCellSurvivesAcrossRestartForGridZonesOnly() {
        // 编码层的容器分治：网格容器写 `:列.行`，非网格容器写裸 id。
        // 顶栏那颗就算被塞进 cellsOf 也不许进串（进了也没人读，留着只会变成下一次进网格容器时复活的老位置）
        val t = HudLayoutTable.default()
            .placeEntryAt(HudEntry.of(CamPill.BT), HudZone.RIGHT, 4, GridCell(1, 2), planAll)
        val seg = t.encode().split(';').first { it.startsWith("R,") }
        assertTrue("右 Dock 的格子后缀没写进串：$seg", seg.contains("${HudEntry.of(CamPill.BT).id}:1.2"))
        val top = t.encode().split(';').first { it.startsWith("T,") }
        assertFalse("顶栏写了格子后缀：$top", top.contains(':'))
        val back = HudLayoutTable.decode(t.encode())
        assertEquals(GridCell(1, 2), back.cellsOf(HudZone.RIGHT)[HudEntry.of(CamPill.BT)])
    }

    @Test
    fun hiddenEntrySurvivesInPersistedString() {
        val moved = HudLayoutTable.default().moveEntryTo(HudEntry.of(CamPill.STORAGE), HudZone.LEFT, 0, planAll)
        val curve = HudEntry.of(CamPill.CURVE)
        val repositioned = moved
            .withZonePos(HudZone.LEFT, 200, 90)
            .moveEntryTo(curve, HudZone.READOUT, 2, planAll)
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
        // 版本比本工程新：整表按默认（老代码读不懂新格式，硬解会把控件摆到错地方）。
        // #74 之后"新"是 v3（本工程当前 v2）——这条必须跟着版本走，否则它测的就不再是"未来版本"
        assertEquals(def, HudLayoutTable.decode("v3;T,5,5,P11,P12;L,-1,-1;R,-1,-1;D,-1,-1;B,-1,-1"))
        // 版本号写成非数字 / 0 / 负数：一律按坏串回默认，不许走进 v1 迁移那一支
        for (badHeader in listOf("v", "v0", "v-1", "vx")) {
            assertEquals("坏版本头 [$badHeader] 必须整表回默认", def, HudLayoutTable.decode("$badHeader;T,5,5,P11,P12"))
        }
    }

    @Test
    fun v1StringMigratesToTheCellsItIsAlreadyShowing() {
        // **v1 → v2 迁移的硬要求**：用户已经拖过的东西一律不许被打回默认。
        // v1 的屏幕位置本来就是「顺序 × 分行」推导出来的，而 v2 的默认格子读的是同一个 hudRowGroups，
        // 所以迁移的正确形态是"格子全留哨兵"——不换算坐标，而是让同一把推导尺子量出同一格。
        // 这条是"计划→行为"的桥：把 defaultCellsOf 换成任何手抄的坐标表，下面逐颗的格子立刻红。
        val v1 = "v1;T,42,7,P11,P12;L,-1,-1,P6,P7,P8,P9;R,-1,-1,P0,P1,P2,P3,P4,P5;" +
            "D,-1,-1,H0,H1,H2,H3,H4,H5,H6;B,-1,-1,P2"
        val t = HudLayoutTable.decode(v1)
        assertEquals("读老串要把表版本抬到当前，下一次保存自然写成 v2", 2, t.version)
        assertEquals("老串里已经拖过的容器位置必须原样保住", ZonePlacement(42, 7), t.posOf(HudZone.TOP))
        // 逐颗点名默认格子：左 Dock 一行一颗 ⇒ 第 0..3 行
        val left = t.gridItems(HudZone.LEFT, planAll).associate { it.entry to it.cell }
        assertEquals(GridCell(0, 0), left[HudEntry.of(CamPill.REFLINE)])
        assertEquals(GridCell(0, 2), left[HudEntry.of(CamPill.CURVE)])
        assertEquals(GridCell(0, 3), left[HudEntry.of(CamPill.FLASH)])
        // 右 Dock 的「姿态仪 + 音量表」并排 ⇒ **同一枚格子的两颗**（#74 后果修复；上一批这里是 (0,0)+(1,0)，
        // 均匀 pitch 之下那两列把底板撑到 120dp）。v1 存量串迁移后必须与新默认同形，
        // 否则用户一进编辑页就会看到右 Dock 突然变宽
        val right = t.gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }
        assertEquals(GridCell(0, 0), right[HudEntry.of(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), right[HudEntry.of(CamPill.VOLUME)])
        assertEquals(GridCell(0, 1), right[HudEntry.of(CamPill.BT)])
        assertEquals(GridCell(0, 4), right[HudEntry.of(CamPill.STAB)])
        // 读数块一行 3 颗 ⇒ 第 7 颗（变焦读数）落在第 2 行第 0 列
        val readout = t.gridItems(HudZone.READOUT, planAll).associate { it.entry to it.cell }
        assertEquals(GridCell(2, 1), readout[HudEntry.of(HudItem.WB)])
        assertEquals(GridCell(0, 2), readout[HudEntry.of(HudItem.ZOOM)])
        // 迁移不写格子表：表里必须一个显式格子都没有（写了就是把手抄的默认值钉进持久化）
        for (zone in HudZone.ALL) {
            assertTrue("${zone.key} 段迁移后不该有显式格子", t.cellsOf(zone).isEmpty())
        }
        // 非网格容器（顶栏/底栏）永远拿哨兵格：那两枚还按顺序排
        assertTrue(t.gridItems(HudZone.TOP, planAll).all { it.cell.isDefault })
        assertEachEntryOnce(t, "v1 迁移表")
    }

    @Test
    fun v1EditedOrderMigratesToWhereThatOrderRenders() {
        // 上一批 B4 里用户能做的编辑只有"换序 / 跨容器"。取一条真实的 v1 串（变焦被拖到左 Dock 第 0 格、
        // 左 Dock 整体被拖到 (120,300)），迁移后它必须**还在**第 0 行第 0 列、容器落点一个字没变。
        val v1 = "v1;T,-1,-1,P11,P12;L,120,300,P3,P6,P7,P8,P9;R,-1,-1,P0,P1,P2,P4,P5;" +
            "D,-1,-1,H0,H1,H2,H3,H4,H5,H6;B,-1,-1,P2"
        val t = HudLayoutTable.decode(v1)
        assertEquals(HudZone.LEFT, t.sourceZoneOf(HudEntry.of(CamPill.ZOOM)))
        val left = t.gridItems(HudZone.LEFT, planAll)
        assertEquals("拖到左 Dock 第 0 格的变焦必须还在第 0 格", GridCell(0, 0), left.first().cell)
        assertEquals(HudEntry.of(CamPill.ZOOM), left.first().entry)
        assertEquals(GridCell(0, 1), left[1].cell)
        assertEquals("容器整体落点必须原样搬过来", ZonePlacement(120, 300), t.posOf(HudZone.LEFT))
        // 右 Dock 少了变焦 ⇒ 姿态仪/音量表仍相邻、仍**共用同一枚格子**（#74 后果修复之后是两颗同格 (0,0)，
        // 上一批是 (0,0)+(1,0)）；对焦那颗仍在第 2 行——行号是"行组下标"，配对吃掉哪一列都不影响它
        val right = t.gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }
        assertEquals(GridCell(0, 0), right[HudEntry.of(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), right[HudEntry.of(CamPill.VOLUME)])
        assertEquals(GridCell(0, 2), right[HudEntry.of(CamPill.FOCUS)])
    }

    @Test
    fun badCellTokensFallBackToDerivedCellNotCrash() {
        // 手改 prefs 造的坏格子：后缀缺行、非数字、负数、越界列，全部**只丢那一颗的格子**，
        // 条目本身照留（与"坏 id 丢掉、其余保留"同一条纪律），整表不许抛
        val raw = "v2;T,-1,-1,P11,P12;L,-1,-1,P6:0,P7:1.2,P8:x.y,P9:9.9;R,-1,-1,P0;D,-1,-1,H0;B,-1,-1,P2"
        val t = HudLayoutTable.decode(raw)
        val left = t.gridItems(HudZone.LEFT, planAll).associate { it.entry to it.cell }
        assertEquals("${HudEntry.of(CamPill.REFLINE).id}:0 缺行 ⇒ 按没摆过处理", GridCell(0, 0), left[HudEntry.of(CamPill.REFLINE)])
        assertEquals("合法后缀必须收下", GridCell(1, 2), left[HudEntry.of(CamPill.MONITOR)])
        assertEquals(GridCell(0, 2), left[HudEntry.of(CamPill.CURVE)])
        // `P9:9.9` 的列越界（左 Dock 最多两列）⇒ 解码时就钳进上限；行 9 没超行上限（条目总数 20）所以照收
        assertEquals("越界列必须钳进 gridColsCapOf", GridCell(1, 9), left[HudEntry.of(CamPill.FLASH)])
        assertEquals(4, t.orderOf(HudZone.LEFT).size)
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
            .moveEntryTo(HudEntry.of(CamPill.LENS), HudZone.RIGHT, 0, planAll)
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
    fun pairCellsRoundTripThroughThePersistedString() {
        // **同格两颗必须能 encode/decode 往返**（#74 后果修复的持久化前提：配对改成共格之后，
        // `tok:col.row` 这套格式对两颗同格是天然可表达的——不许为此动 schema 版本、不许新造键）
        val paired = HudLayoutTable.default()
            .placeEntryAt(HudEntry.of(CamPill.BT), HudZone.RIGHT, 2, GridCell(1, 0), planAll)
        // 真实串（钉的是格式本身：换成任何"两颗合并写"的新写法，这一条就红）
        assertEquals(
            "R,-1,-1,P0:0.0,P1:0.0,P2:1.0,P3:0.2,P4:0.3,P5:0.4",
            paired.encode().split(';').first { it.startsWith("R,") }
        )
        val back = HudLayoutTable.decode(paired.encode())
        assertEquals("整张表无损", paired, back)
        assertEquals("编→解→编同串（幂等）", paired.encode(), back.encode())
        assertEquals(GridCell(0, 0), back.cellsOf(HudZone.RIGHT)[HudEntry.of(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), back.cellsOf(HudZone.RIGHT)[HudEntry.of(CamPill.VOLUME)])
        assertEquals(
            "解析后两颗仍共用 (0,0)，且**格内左右次序 = 清单次序**（渲染层与命中裁决读的就是这一个序）",
            listOf(HudEntry.of(CamPill.LEVEL), HudEntry.of(CamPill.VOLUME)),
            back.gridItems(HudZone.RIGHT, planAll).filter { it.cell == GridCell(0, 0) }.map { it.entry }
        )
        assertEachEntryOnce(back, "配对同格往返")
        // 出厂默认表（格子全哨兵）里两颗也共用推导格 (0,0)：与上面那段是**两条不同的码路**
        // （一条走显式格的编解码，一条走哨兵格的默认推导），改坏任何一条都有一处红
        val derived = HudLayoutTable.default().gridItems(HudZone.RIGHT, planAll).associate { it.entry to it.cell }
        assertEquals(GridCell(0, 0), derived[HudEntry.of(CamPill.LEVEL)])
        assertEquals(GridCell(0, 0), derived[HudEntry.of(CamPill.VOLUME)])
    }

    @Test
    fun v1StringWithDraggedContainerAndMovedEntryMigratesWhole() {
        // 交付报告第 3 项那条"真实 v1 串"：容器被拖过（左 Dock 到 (200,90)）+ 两颗被搬过容器
        // （剩余空间 P12 进左 Dock 第 0 位、RGB 曲线 P8 进读数块第 2 位）+ 之后用户在设置页把这两颗关掉。
        // 这一条把"迁移 + 隐藏态"两层语义一起钉住。
        val v1 = "v1;T,-1,-1,P11;L,200,90,P12,P6,P7,P9;R,-1,-1,P0,P1,P2,P3,P4,P5;" +
            "D,-1,-1,H0,H1,P8,H2,H3,H4,H5,H6;B,-1,-1,P10"
        val t = HudLayoutTable.decode(v1)
        // ① 容器落点原样
        assertEquals(ZonePlacement(200, 90), t.posOf(HudZone.LEFT))
        assertTrue("没拖过的容器仍必须是哨兵", t.posOf(HudZone.RIGHT).isDefault)
        // ② 归属与顺序原样（跨容器那两颗还在原位）
        assertEquals(
            listOf(HudEntry.of(CamPill.STORAGE), HudEntry.of(CamPill.REFLINE),
                HudEntry.of(CamPill.MONITOR), HudEntry.of(CamPill.FLASH)),
            t.orderOf(HudZone.LEFT)
        )
        assertEquals(HudZone.READOUT, t.sourceZoneOf(HudEntry.of(CamPill.CURVE)))
        // ③ 逐颗的格子＝改前那副排布（全可见时：左 Dock 一列四行；读数块 3+3+2）
        val left = t.gridItems(HudZone.LEFT, planAll).associate { it.entry to it.cell }
        assertEquals(GridCell(0, 0), left[HudEntry.of(CamPill.STORAGE)])
        assertEquals(GridCell(0, 1), left[HudEntry.of(CamPill.REFLINE)])
        assertEquals(GridCell(0, 3), left[HudEntry.of(CamPill.FLASH)])
        val readout = t.gridItems(HudZone.READOUT, planAll).associate { it.entry to it.cell }
        assertEquals(GridCell(2, 0), readout[HudEntry.of(CamPill.CURVE)])
        assertEquals(GridCell(1, 1), readout[HudEntry.of(HudItem.ISO)])
        assertEquals(GridCell(1, 2), readout[HudEntry.of(HudItem.ZOOM)])
        // ④ 关掉那两颗再重开：显示回来的位置还是"改前它所在的那一格"，且没有回出厂容器
        val hidden: Set<HudEntry> = CamPill.ALL.filter { it != CamPill.STORAGE && it != CamPill.CURVE }
            .map { HudEntry.of(it) }.toSet() + HudItem.ALL.map { HudEntry.of(it) }
        val whileHidden = t.visibleOrderOf(HudZone.LEFT, hidden)
        assertFalse("关掉的条目不许出现在页面上", HudEntry.of(CamPill.STORAGE) in whileHidden)
        val shown = HudLayoutTable.decode(t.encode()).visibleOrderOf(HudZone.LEFT, HudEntry.ALL.toSet())
        assertEquals("重开显示必须回到左 Dock 第 0 位（不是出厂的顶栏）",
            HudEntry.of(CamPill.STORAGE), shown.first())
        assertEquals("重开后落点还是第 0 行", GridCell(0, 0),
            HudLayoutTable.decode(t.encode()).gridItems(HudZone.LEFT, planAll).first().cell)
        // ⑤ 重开进程仍一样：读的就是这一份串（`WotaSettings.setHudLayout` 走同步 commit，
        //    这里用"同一串二次解码与首次逐字段相同"表达"进程重启看到的表不变"）
        assertEquals(t, HudLayoutTable.decode(t.encode()))
        assertEachEntryOnce(t, "v1 拖过 + 隐藏过")
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
