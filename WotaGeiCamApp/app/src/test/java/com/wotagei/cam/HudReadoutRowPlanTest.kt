package com.wotagei.cam

import androidx.compose.ui.unit.dp
import com.wotagei.cam.ui.BottomBarOuterPadV
import com.wotagei.cam.ui.BottomBarSpaceFallback
import com.wotagei.cam.ui.BottomDockWidthFallback
import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.HudBlockPadDp
import com.wotagei.cam.ui.HudEdgePad
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.areaForRightDock
import com.wotagei.cam.ui.chipTierFor
import com.wotagei.cam.ui.design.WotaChipTier
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.planReadoutRow
import com.wotagei.cam.ui.readoutRoomBesideDockDp
import com.wotagei.cam.ui.readoutStripWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 任务 #70 A：读数块与底栏 Dock **同一条横带**的那次决策（`planReadoutRow`）+ 右 Dock 的让位取法。
 *
 * ## 定版口径（用户 2026-09-29 12:20：「读数缩到一行两颗，横屏永远同行」）
 * 横屏 `sharesDockRow` 恒真、列数上限 2；横屏**不存在**"退回 Dock 上方"这一档；只有竖屏允许退回。
 * 横向装不下的解法是**少列**（两颗→一列），不是放弃同行。
 *
 * ## 这批用例全部是"计划 → 行为"的桥，不是由定义推出的恒等式（AGENTS 那条「恒等式不算证明」）
 * - **入参一律引令牌本体**（修复批次第 3 条）：以前这里另写了一份 6/8/8/216 字面量，谁把
 *   `BottomBarOuterPadV` 改成 10，测试全绿而"两块底边对齐"当场失效；现在入参跟着令牌动，
 *   而**期望输出仍是先按文档口径写死的数**——令牌动了就必须红一次、逼人来对账。
 * - 可证伪性逐条写在用例注释里，承重的是这四条：
 *   ① 横屏那一支改成"读实测块宽来决定同不同行/退不退列" ⇒ [landscapeAlwaysSharesTheDockRowEvenWhenTheBlockIsTooWide] 红
 *      （那正是 `列数→块宽→列数` 的每帧振荡环）；
 *   ② 把横屏列数上限 `minOf(DockRowPerRowCap, …)` 那层夹子删掉 ⇒ [landscapeColumnsCappedAtTwoEvenOnAWideLandscape] 红
 *      （宽幅横屏会算到 3 颗，定版「一行两颗」失效）；
 *   ③ 把退化档的 `minOf(perRowBeside, perRowFull)` 夹子删掉 ⇒ [portraitStaleWideBlockRecoversOnTheNextFrame] 红
 *      （读数永久闩在"整排之上 + 整幅 3 颗"那一档，就是 S1 那条）；
 *   ④ 把同基线档那笔 `基线 − HudBlockPadDp` 的减法删掉 ⇒ [landscapeRowBaselineAlignsTheVisibleChipBottomNotTheContainer] 红
 *      （胶囊可见底边比底板高出一整枚内边距，还是"浮在上方"）。
 *
 * 数值档与 `HudLayoutClampTest` 同源：横屏安全区实测宽 **766**（挖孔让掉一条短边之后的 1532px/2）、
 * 竖屏 **360**，底栏带高 72（=`BottomBarSpaceFallback`），底板常态宽 216（=`BottomDockWidthFallback`）。
 */
class HudReadoutRowPlanTest {

    private fun plan(
        count: Int,
        scale: Float,
        safeW: Int,
        dockW: Int,
        landscape: Boolean,
        dockStrip: Int = DockStrip,
        readoutW: Int = 0
    ) = planReadoutRow(
        readoutCount = count,
        fontScale = scale,
        safeWidthDp = safeW,
        dockWidthDp = dockW,
        dockStripDp = dockStrip,
        dockRowBaselineDp = DockRowBaseline,
        endPadDp = EdgePad,
        dockGapDp = DockGap,
        landscape = landscape,
        readoutWidthDp = readoutW
    )

    // ---------------------------------------------------------------- 横屏（定版：永远同行）

    @Test
    fun landscapeAlwaysSharesTheDockRowEvenWhenTheBlockIsTooWide() {
        // 横屏 766、底板 216、默认 3 颗读数：**陈旧块宽 294**（= 上一副姿态按整幅 3 颗排出来的那个宽度，
        // 已经越过底栏右缘那条带的 259dp）。这正是审查实测出的闩锁触发路径（竖屏起页后转横屏），
        // 定版要求横屏不出口 false ⇒ 同行不撤，只是列数按上限两颗排（块窄下来，下一帧实测就回到带内）
        val p = plan(count = 3, scale = 1f, safeW = LandSafeW, dockW = DockW, landscape = true, readoutW = 294)
        assertTrue("横屏必须同行（定版），否则 #70 又退回了浮在 Dock 上方：$p", p.sharesDockRow)
        assertEquals(2, p.perRow)
        // 可用内宽 247 = 766/2 − 216/2 − 8（底栏右缘到块的那枚间距）− 8（设计留白）− 6×2（块内边距）
        // 一颗读数按 hudPerRowFor 的口径估宽 90dp ⇒ 两颗 186 + 行距 6 + 余量 24 = 216 ≤ 247，三颗 306 > 247
        assertEquals(247f, p.roomWidthDp, 0.001f)
        // 反证：这一档**不许**拿实测块宽去改列数。把块宽喂成"一颗都放不下"的 400，列数仍按估宽给 2、
        // 仍留在同一行——若实现改成 `measuredFitsBeside` 也参与横屏列数，这里就会退成一列（振荡的起点）
        val huge = plan(count = 3, scale = 1f, safeW = LandSafeW, dockW = DockW, landscape = true, readoutW = 400)
        assertTrue(huge.sharesDockRow)
        assertEquals(2, huge.perRow)
    }

    @Test
    fun landscapeColumnsCappedAtTwoEvenOnAWideLandscape() {
        // 宽幅横屏（平板/折叠屏展开，安全区 1000dp）：裸档位函数 hudPerRowFor 会给到 3 颗一行，
        // 定版把横屏的列数上限钉在 2 ⇒ 删掉 planReadoutRow 里那层 minOf(DockRowPerRowCap, …) 就红在这里
        // （写成字面量 2 而不是引常量：谁把上限常量改成 3，这条必须红，否则定版就成了可调参数）
        val p = plan(count = 7, scale = 1f, safeW = 1000, dockW = DockW, landscape = true)
        assertTrue(p.sharesDockRow)
        assertEquals("横屏列数上限是定版的 2 颗，不是按剩余宽度算到 3", 2, p.perRow)
        // 对照：同一副几何把窗口收到 766（本机横屏）时估宽本来也给 2，两档同值 ⇒ 上限不是唯一生效的那层
        assertEquals(2, plan(count = 7, scale = 1f, safeW = LandSafeW, dockW = DockW, landscape = true).perRow)
        // 120% 文本 + 底板也长到 232dp（2×70.8 + 50 + 40）：右半带 239dp，两颗 108 的读数要 246 > 239
        // ⇒ 退成**一列**，但仍然同行（这条就是定版替旧"120% 放弃同行"换掉的解法）
        val big = plan(count = 3, scale = 1.2f, safeW = LandSafeW, dockW = 232, landscape = true)
        assertTrue("120% 档横屏也不许退回 Dock 上方", big.sharesDockRow)
        assertEquals(1, big.perRow)
        assertEquals(239f, big.roomWidthDp, 0.001f)
    }

    @Test
    fun landscapeRowBaselineAlignsTheVisibleChipBottomNotTheContainer() {
        // #70 修复批次第 6 条那笔 6dp：底栏容器吃 padding(bottom = BottomBarOuterPadV) ⇒ **底板可见底边**
        // 在 6dp 高；读数块那枚容器除了让位还自己吃了 .padding(HudBlockPadDp)（6dp），
        // 所以同基线档送下来的容器让位必须是 `基线 − 块内边距`（本机 0dp），胶囊可见底边才落在同一条线上。
        val p = plan(count = 3, scale = 1f, safeW = LandSafeW, dockW = DockW, landscape = true)
        // 钉住实现消费的正是底栏那枚令牌 + 块内边距这一笔减法（把减法删掉 → 6 + 6 = 12 ≠ 6 立刻红）
        assertEquals(
            "同行档：胶囊可见底边必须等于底板可见底边（= 真正被消费的那枚常量）",
            BottomBarOuterPadV.value.roundToInt(), p.bottomAvoidDp + HudBlockPadDp.roundToInt()
        )
        // 期望的容器让位本身也钉成字面量：谁改基线令牌或块内边距，这里红一次，逼人来对账
        assertEquals(0, p.bottomAvoidDp)
    }

    @Test
    fun besideDockRoomSubtractsTheCenteredDockFootprint() {
        // 同一条带只把"底栏右缘以右"给读数块：底板变宽，可用宽必须 1:1 变窄（不是恒等式，
        // 实现里若漏掉 dockWidthDp 或漏掉居中带来的 /2，这两档都会红）
        assertEquals(247f, readoutRoomBesideDockDp(LandSafeW, DockW, EdgePad, DockGap), 0.001f)
        assertEquals(139f, readoutRoomBesideDockDp(LandSafeW, DockW * 2, EdgePad, DockGap), 0.001f)
        // 底板宽到吃掉右半带时夹到 0，不许给负数（负数会让 hudPerRowFor 走进 chipCount<=0 那一支之外）
        assertEquals(0f, readoutRoomBesideDockDp(LandSafeW, 900, EdgePad, DockGap), 0.001f)
    }

    // ---------------------------------------------------------------- 竖屏（唯一允许退回的姿态）

    @Test
    fun portraitFallsBackAboveTheDockWithoutLosingTheOldRow() {
        // 竖屏 360：底板 216 居中 ⇒ 右半只剩 64dp，一颗读数（90）都放不下 ⇒ 只能退回"整排之上"。
        // 这一档的 perRow 必须回到**整幅宽**取的旧档位 3，否则读数会无缘无故从一行三颗变成一列三颗
        val p = plan(count = 3, scale = 1f, safeW = PortSafeW, dockW = DockW, landscape = false)
        assertFalse("竖屏右半带装不下两颗，必须退化而不是压到底栏上", p.sharesDockRow)
        assertEquals(DockStrip, p.bottomAvoidDp)
        assertEquals(340f, p.roomWidthDp, 0.001f)
        assertEquals(3, p.perRow)
    }

    @Test
    fun portraitStaleWideBlockRecoversOnTheNextFrame() {
        // #70 修复批次第 1 条（S1）：实测块宽是**上一帧按当前档位排出来的产物**，退化档若照样取整幅宽
        // （3 颗 = 294dp）就永远比阈值宽 ⇒ `块宽→档位` 把自己闩死。修法：退化档在 perRowBeside ≥ 2 时
        // 夹成 min(perRowBeside, perRowFull)，块宽就对本帧决策不敏感了。
        // 场景取宽幅竖屏（平板/分屏，安全区 700）：右半带 214 够排两颗，但陈旧块宽 294 越过那条带的 226
        val stale = plan(count = 3, scale = 1f, safeW = 700, dockW = DockW, landscape = false, readoutW = 294)
        assertFalse("估宽说能排两颗、实测越线 ⇒ 先退到整排之上", stale.sharesDockRow)
        assertEquals("退化档必须夹到两颗（删掉 minOf(perRowBeside, perRowFull) 就红在这里）", 2, stale.perRow)
        // 第二帧：块按 2 颗重排 ⇒ 实测 198（2×90 + 行距 6 + 块内边距 12）回到那条带（226）以内
        val recovered = plan(count = 3, scale = 1f, safeW = 700, dockW = DockW, landscape = false, readoutW = 198)
        assertTrue("窄下来之后必须自动回到同一行（旧实现没有这条路）", recovered.sharesDockRow)
        assertEquals(2, recovered.perRow)
        assertEquals(0, recovered.bottomAvoidDp)
    }

    @Test
    fun portraitMeasuredBlockWidthOverridesTheEstimateBeforeItCoversTheDock() {
        // `hudPerRowFor` 的字宽是**算术估计**（汉字 1 em、拉丁 0.6 em），副标签「感光度」「曝光补偿」比它
        // 假设的两汉字宽 ⇒ 估宽说"装得下"而实测块宽已经越过那条带。竖屏这一档必须由实测兜住，
        // 否则读数块会直接画到底栏底板上、盖住镜头那颗（那是挡住入口，不是裁字那么轻）。
        // 底栏右缘那条带的最外沿 = 700/2 − 216/2 − 8(间距) − 8(留白) = 226dp
        assertEquals(226f, readoutStripWidthDp(700, DockW, EdgePad, DockGap), 0.001f)
        // 竖屏 360：180 − 108 − 8 − 8 = 56dp，一颗读数胶囊（估宽 90dp）都放不下 ⇒ 只能退化
        assertEquals(56f, readoutStripWidthDp(PortSafeW, DockW, EdgePad, DockGap), 0.001f)
        // 估宽说同行（214 ≥ 两颗要的 210），但实测 250 已经越线 ⇒ 退回整排之上
        val lied = plan(count = 3, scale = 1f, safeW = 700, dockW = DockW, landscape = false, readoutW = 250)
        assertFalse("实测块宽越过那条带还不退，就会压住底栏底板", lied.sharesDockRow)
        assertEquals(DockStrip, lied.bottomAvoidDp)
        // 实测仍在带内 ⇒ 同行不变（这一档防的是"实现把安全网写成了无条件退回"）
        val honest = plan(count = 3, scale = 1f, safeW = 700, dockW = DockW, landscape = false, readoutW = 220)
        assertTrue(honest.sharesDockRow)
        assertEquals(2, honest.perRow)
        // 临界：块宽恰好等于带宽 ⇒ 仍算装得下（判据是 ≤，写成 < 会白白多退一档）
        assertTrue(plan(count = 3, scale = 1f, safeW = 700, dockW = DockW, landscape = false, readoutW = 226)
            .sharesDockRow)
        // 首帧量不到（0）必须放行到估宽那一档，否则整个决策要等一帧才成立，进页会先看到旧落位
        assertTrue(plan(count = 3, scale = 1f, safeW = 700, dockW = DockW, landscape = false, readoutW = 0)
            .sharesDockRow)
    }

    @Test
    fun readoutCountNeverPushesTheRowOntoTheDockWhenItCannotFit() {
        // 满配 7 颗读数（快门/帧率/码率/ISO/EV/白平衡/变焦全开）：横屏走同行，一行两颗 ⇒ 四行
        val p = plan(count = 7, scale = 1f, safeW = LandSafeW, dockW = DockW, landscape = true)
        assertTrue(p.sharesDockRow)
        assertEquals(2, p.perRow)
        // 一颗的时候不许"因为没有第二颗可排"而退回整排之上：块宽 = 一颗，仍然容得下。
        // perRow 取到 2 是 hudPerRowFor 的既有语义（2 那一档不按颗数收敛），
        // 对一颗条目来说 chunked(2) 就是一行一颗，行为等价，所以这里钉的是"留在同一行"这件事
        val one = plan(count = 1, scale = 1f, safeW = LandSafeW, dockW = DockW, landscape = true)
        assertTrue("单颗也必须留在同一行：$one", one.sharesDockRow)
        assertEquals(0, one.bottomAvoidDp)
        assertEquals(2, one.perRow)
    }

    @Test
    fun rightDockDodgeIsParallelWhenRowIsSharedAndSeriesWhenItIsNot() {
        val land = HudAreaDp(width = LandSafeW, height = 360, topAvoidDp = 44, bottomAvoidDp = DockStrip)
        // 同基线档（胶囊底边对齐后容器让位归 0）：读数块矮于底栏那一排（42 < 72）⇒ 那条缝整个收回去
        assertEquals(DockStrip, areaForRightDock(land, 42, 0).bottomAvoidDp)
        // 读数块比底栏那一排高（同基线上长到 100）⇒ 按它自己的顶来让
        assertEquals(100, areaForRightDock(land, 100, 0).bottomAvoidDp)
        // 退化档（读数块在整排之上，容器让位 = 带高）⇒ 与 #70 之前**逐字相同**的串联算式，一条没丢
        assertEquals(114, areaForRightDock(land, 42, DockStrip).bottomAvoidDp)
        // 空块与坏值
        assertEquals(land, areaForRightDock(land, 0, 0))
        assertEquals(DockStrip, areaForRightDock(land, -50, 0).bottomAvoidDp)
    }

    // ---------------------------------------------------------------- #70 B 的档位裁决

    @Test
    fun onlyTheVerticalDocksGetTheCompactChipTier() {
        // #70 B 的裁决点：两枚竖 Dock 收窄，其余三枚一律全局档。
        // 这一条不是恒等式——它测的是"谁被路由到哪一档"，把 BOTTOM 误伤成紧凑档就会红，
        // 而 BOTTOM 的紧凑档会连带改掉 DockSlotSpace=63dp 那笔槽宽账，也就是 #71 收拢算式 w(0) 的起点。
        // 修复批次第 4 条之后 `HudEntryItem(tier=…)` **没有默认值**，五枚容器各自显式传 chipTierFor(...)：
        // 这些返回值全部有消费方（旧写法是 TOP/READOUT/BOTTOM 三处靠默认值拿 Standard，删掉这行判断也测不出）
        assertEquals(WotaChipTier.Dock, chipTierFor(HudZone.LEFT))
        assertEquals(WotaChipTier.Dock, chipTierFor(HudZone.RIGHT))
        assertEquals("底栏那颗必须留全局档（#71 的算式输入）", WotaChipTier.Standard, chipTierFor(HudZone.BOTTOM))
        assertEquals("顶栏容量段的窄屏阈值按全局档量", WotaChipTier.Standard, chipTierFor(HudZone.TOP))
        assertEquals("读数块必须留全局档（hudPerRowFor 的 90dp 估宽按它量）", WotaChipTier.Standard, chipTierFor(HudZone.READOUT))
    }

    @Test
    fun globalChipTierNumbersArePinnedSoNobodyCanShrinkThemByAccident() {
        // 全局档 = 12dp 左右内边距 + 3 汉字下限：`DockSlotSpace = 13×3 + 12 + 12 = 63dp`
        // 与 `hudPerRowFor` 里的 `ChipPaddingDp = 24` 都是照这两个数写的。
        // 这里把"计划里的数"钉死：谁把全局档改小，#71 的起点与换行算式就都会静默失真，必须让它先红在这里。
        assertEquals(12.dp, WotaChipTier.Standard.horizontalPad)
        assertEquals(3f, WotaChipTier.Standard.minLabelEm, 0.001f)
        // 紧凑档：8dp（令牌 WotaSpace.s）+ 2 汉字下限，比全局档窄 8dp（左右各 4）
        assertEquals(8.dp, WotaChipTier.Dock.horizontalPad)
        assertEquals(2f, WotaChipTier.Dock.minLabelEm, 0.001f)
        // 上下内边距两档共用（本任务只收宽度），所以胶囊高度不随档位变 ⇒ 120% 那档不会因为收窄而换行裁字
        assertTrue(WotaChipTier.Dock.horizontalPad < WotaChipTier.Standard.horizontalPad)
    }

    private companion object {
        // ↓ 入参全部**引令牌本体**（#70 修复批次第 3 条）。上面那些期望数字是先按文档口径写死的字面量，
        //   所以令牌一旦改动，这些用例就会红——那正是要的效果：改令牌必须重新对一遍这条横带的账。
        val DockRowBaseline = BottomBarOuterPadV.value.roundToInt()   // 6：底板可见底边（两枚容器共用的基线）
        val EdgePad = HudEdgePad.value                                 // 8f：读数块 padding(end=) 的设计留白
        val DockGap = WotaSpace.s.value                                // 8f：底栏右缘与读数块之间的间距令牌
        val DockW = BottomDockWidthFallback.value.roundToInt()         // 216：底板常态宽（= 首帧兜底值）
        val DockStrip = BottomBarSpaceFallback.value.roundToInt()      // 72：底栏那一排的带高
        const val LandSafeW = 766   // 本机横屏安全区实测宽（真机 dump，不是令牌）
        const val PortSafeW = 360   // 本机竖屏安全区实测宽
    }
}
