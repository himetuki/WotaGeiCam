package com.wotagei.cam

import androidx.compose.ui.unit.dp
import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.areaForRightDock
import com.wotagei.cam.ui.chipTierFor
import com.wotagei.cam.ui.design.WotaChipTier
import com.wotagei.cam.ui.planReadoutRow
import com.wotagei.cam.ui.readoutRoomBesideDockDp
import com.wotagei.cam.ui.readoutStripWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务 #70 A：读数块与底栏 Dock **同一条横带**的那次决策（`planReadoutRow`）+ 右 Dock 的让位取法。
 *
 * 这批用例全部是"计划 → 行为"的桥，不是由定义推出的恒等式（AGENTS 那条「恒等式不算证明」）：
 * 每个数都是**先按文档口径写死、再拿实现去撞**的。可证伪性逐条写在用例注释里，其中承重的三条是：
 * ① 把 `planReadoutRow` 里"同基线"那一档删掉（只剩退回整排之上），横屏用例会红；
 * ② 把右半带的底栏宽扣减项（`dockWidthDp / 2f`）删掉，横屏的 247 / perRow=2 都会红
 *    （那正是 r11 之后如果只做"stop 消费"而不改宽度账会留下的重叠缺陷）；
 * ③ 把 `areaForRightDock` 换回求和，同基线那两档会红。
 *
 * 数值档与 `HudLayoutClampTest` 同源：横屏安全区实测宽 **766**（挖孔让掉一条短边之后的 1532px/2）、
 * 竖屏 **360**，底栏带高 72（= 底板实测 60 + 上下外边距 6×2），同基线档 6（= `BottomBarOuterPadV`）。
 */
class HudReadoutRowPlanTest {

    private fun plan(
        count: Int,
        scale: Float,
        safeW: Int,
        dockW: Int,
        dockStrip: Int = 72,
        readoutW: Int = 0
    ) = planReadoutRow(
        readoutCount = count,
        fontScale = scale,
        safeWidthDp = safeW,
        dockWidthDp = dockW,
        dockStripDp = dockStrip,
        bottomRowPadDp = BottomRowPad,
        endPadDp = EndPad,
        dockGapDp = DockGap,
        readoutWidthDp = readoutW
    )

    @Test
    fun landscapeBottomRowIsSharedWithTheDock() {
        // 横屏 766、底板 216（100% 字体、镜头那颗占右槽）：默认常驻 3 颗读数
        val p = plan(count = 3, scale = 1f, safeW = 766, dockW = 216)
        assertTrue("横屏默认档必须走『与底栏同一行』，否则 #70 A 没修：$p", p.sharesDockRow)
        // 承重断言：底边让位是同基线那一档，**不再是底栏带高 72**。
        // 旧行为（把 dockStripH 直接喂给 padding(bottom=)）会把读数抬到 Dock 上方 23dp，就是 r11 的病灶。
        assertEquals(BottomRowPad, p.bottomAvoidDp)
        // 一行两颗：可用内宽 247 = 766/2 − 216/2 − 8（底栏右缘到块左缘那枚间距）− 8（设计留白）− 6×2（块内边距）
        // 一颗读数按 hudPerRowFor 的口径是 90dp ⇒ 两颗 186 + 行距 6 + 余量 24 = 216 ≤ 247，三颗 306 > 247
        assertEquals(247f, p.roomWidthDp, 0.001f)
        assertEquals(2, p.perRow)
    }

    @Test
    fun besideDockRoomSubtractsTheCenteredDockFootprint() {
        // 同一条带只把"底栏右缘以右"给读数块：底板变宽，可用宽必须 1:1 变窄（不是恒等式，
        // 实现里若漏掉 dockWidthDp 或漏掉居中带来的 /2，这两档都会红）
        assertEquals(247f, readoutRoomBesideDockDp(766, 216, EndPad, DockGap), 0.001f)
        assertEquals(139f, readoutRoomBesideDockDp(766, 432, EndPad, DockGap), 0.001f)
        // 底板宽到吃掉右半带时夹到 0，不许给负数（负数会让 hudPerRowFor 走进 chipCount<=0 那一支之外）
        assertEquals(0f, readoutRoomBesideDockDp(766, 900, EndPad, DockGap), 0.001f)
    }

    @Test
    fun portraitFallsBackAboveTheDockWithoutLosingTheOldRow() {
        // 竖屏 360：底板 216 居中 ⇒ 右半只剩 64dp，一颗读数（90）都放不下 ⇒ 只能退回"整排之上"。
        // 这一档的 perRow 必须回到**整幅宽**取的旧档位 3，否则读数会无缘无故从一行三颗变成一列三颗
        val p = plan(count = 3, scale = 1f, safeW = 360, dockW = 216)
        assertFalse("竖屏右半带装不下两颗，必须退化而不是压到底栏上", p.sharesDockRow)
        assertEquals(72, p.bottomAvoidDp)
        assertEquals(340f, p.roomWidthDp, 0.001f)
        assertEquals(3, p.perRow)
    }

    @Test
    fun measuredBlockWidthOverridesTheEstimateBeforeItCoversTheDock() {
        // `hudPerRowFor` 的字宽是**算术估计**（汉字 1 em、拉丁 0.6 em），副标签「感光度」「曝光补偿」比它
        // 假设的两汉字宽 ⇒ 估宽说"装得下"而实测块宽已经越过那条带。这一档必须由实测兜住，
        // 否则读数块会直接画到底栏底板上、盖住镜头那颗（那是挡住入口，不是裁字那么轻）。
        // 底栏右缘那条带的最外沿 = 766/2 − 216/2 − 8(间距) − 8(留白) = 259dp
        assertEquals(259f, readoutStripWidthDp(766, 216, EndPad, DockGap), 0.001f)
        // 竖屏 360：180 − 108 − 8 − 8 = 56dp，一颗读数胶囊（估宽 90dp）都放不下 ⇒ 只能退化
        assertEquals(56f, readoutStripWidthDp(360, 216, EndPad, DockGap), 0.001f)
        // 估宽说同行（247 ≥ 两颗要的 210），但实测 268 已经越线 ⇒ 退回整排之上，perRow 回整幅档 3
        val lied = plan(count = 3, scale = 1f, safeW = 766, dockW = 216, readoutW = 268)
        assertFalse("实测块宽越过那条带还不退，就会压住底栏底板", lied.sharesDockRow)
        assertEquals(72, lied.bottomAvoidDp)
        assertEquals(3, lied.perRow)
        // 实测仍在带内 ⇒ 同行不变（这一档防的是"实现把安全网写成了无条件退回"）
        val honest = plan(count = 3, scale = 1f, safeW = 766, dockW = 216, readoutW = 240)
        assertTrue(honest.sharesDockRow)
        assertEquals(2, honest.perRow)
        // 临界：块宽恰好等于带宽 ⇒ 仍算装得下（判据是 ≤，写成 < 会白白多退一档）
        assertTrue(plan(count = 3, scale = 1f, safeW = 766, dockW = 216, readoutW = 259).sharesDockRow)
        // 首帧量不到（0）必须放行到估宽那一档，否则整个决策要等一帧才成立，进页会先看到旧落位
        assertTrue(plan(count = 3, scale = 1f, safeW = 766, dockW = 216, readoutW = 0).sharesDockRow)
    }

    @Test
    fun twelveHundredPercentTextGivesUpTheRowInsteadOfClipping() {
        // 120% 文本 + 底板也长到 232dp（2×70.8 + 50 + 40）：右半带 239dp，两颗 108 的读数要 246 ⇒ 退化。
        // 这一档退化后与 #70 之前的观感**完全一致**（整排之上、一行三颗），所以不是新缺陷，是"没改善"。
        val p = plan(count = 3, scale = 1.2f, safeW = 766, dockW = 232)
        assertFalse("120% 档宁可退回旧落位也不许裁字或压住底栏", p.sharesDockRow)
        assertEquals(72, p.bottomAvoidDp)
        assertEquals(3, p.perRow)
        // 单调性：同一套输入下底板越宽越不可能同行（判据不许反过来长）
        assertFalse(plan(count = 3, scale = 1f, safeW = 766, dockW = 520).sharesDockRow)
        assertTrue(plan(count = 3, scale = 1f, safeW = 766, dockW = 100).sharesDockRow)
    }

    @Test
    fun readoutCountNeverPushesTheRowOntoTheDockWhenItCannotFit() {
        // 满配 7 颗读数（快门/帧率/码率/ISO/EV/白平衡/变焦全开）：横屏仍走同行，但一行两颗 ⇒ 四行
        val p = plan(count = 7, scale = 1f, safeW = 766, dockW = 216)
        assertTrue(p.sharesDockRow)
        assertEquals(2, p.perRow)
        // 一颗的时候不许"因为没有第二颗可排"而退回整排之上：块宽 = 一颗，仍然容得下。
        // perRow 取到 2 是 hudPerRowFor 的既有语义（只有 3 那一档会按颗数收敛，2 那一档不会），
        // 对一颗条目来说 chunked(2) 就是一行一颗，行为等价，所以这里钉的是"留在同一行"这件事
        val one = plan(count = 1, scale = 1f, safeW = 766, dockW = 216)
        assertTrue("单颗也必须留在同一行：$one", one.sharesDockRow)
        assertEquals(BottomRowPad, one.bottomAvoidDp)
        assertEquals(2, one.perRow)
    }

    @Test
    fun rightDockDodgeIsParallelWhenRowIsSharedAndSeriesWhenItIsNot() {
        val land = HudAreaDp(width = 766, height = 360, topAvoidDp = 44, bottomAvoidDp = 72)
        // 同基线档：读数块矮于底栏那一排（42 < 66）⇒ 那条缝整个收回去，右 Dock 不再多让一整排
        assertEquals(72, areaForRightDock(land, readoutHeightDp = 42, readoutBottomDp = BottomRowPad).bottomAvoidDp)
        // 读数块比底栏那一排高（同基线上长到 100）⇒ 按它自己的顶来让
        assertEquals(106, areaForRightDock(land, readoutHeightDp = 100, readoutBottomDp = BottomRowPad).bottomAvoidDp)
        // 退化档（读数块在整排之上）⇒ 与 #70 之前**逐字相同**的串联算式，一条没丢
        assertEquals(114, areaForRightDock(land, readoutHeightDp = 42, readoutBottomDp = 72).bottomAvoidDp)
        // 空块与坏值
        assertEquals(land, areaForRightDock(land, 0, BottomRowPad))
        assertEquals(72, areaForRightDock(land, -50, BottomRowPad).bottomAvoidDp)
    }

    @Test
    fun onlyTheVerticalDocksGetTheCompactChipTier() {
        // #70 B 的裁决点：两枚竖 Dock 收窄，其余三枚一律全局档。
        // 这一条不是恒等式——它测的是"谁被路由到哪一档"，把 BOTTOM 误伤成紧凑档就会红，
        // 而 BOTTOM 的紧凑档会连带改掉 DockSlotSpace=63dp 那笔槽宽账，也就是 #71 收拢算式 w(0) 的起点。
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
        const val BottomRowPad = 6     // BottomBarOuterPadV：底栏与读数块共用的那条基线
        const val EndPad = 8f          // HudEdgePad（WotaSpace.s）：读数块贴右缘的设计留白
        const val DockGap = 8f         // WotaSpace.s：底栏右缘与读数块之间留一枚间距令牌
    }
}
