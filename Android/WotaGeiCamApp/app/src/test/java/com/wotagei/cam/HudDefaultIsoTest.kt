package com.wotagei.cam

import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.BottomBarOuterPadV
import com.wotagei.cam.ui.BottomBarSpaceFallback
import com.wotagei.cam.ui.BottomDockWidthFallback
import com.wotagei.cam.ui.GridCell
import com.wotagei.cam.ui.HudEdgePad
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudGridPlan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.defaultCellsOf
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.planReadoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 「感光度读数默认上屏」这一轮的**计划 → 行为**桥（用户 2026-10-09）。
 *
 * 改动只有一处：[HudItem.DEFAULT_MASK] 并入 ISO。但只测那一行是恒等式（掩码自证），
 * 所以这里从**掩码一路桥到读数块的格子**：掩码 → 录制页可见集 → 容器内可见顺序 → [defaultCellsOf]
 * 的推导格，每一步都取生产代码（[HudItem.typesOf] / [HudLayoutTable.visibleOrderOf] /
 * [planReadoutRow] / [defaultCellsOf]），把它换成任何手抄表都会红。
 *
 * ## 三条口径（本机竖屏 360dp 安全区，density 2.0，与 [HudReadoutRowPlanTest] 同一批手算值）
 * - 默认掩码 = 1(快门) | 2(帧率) | 4(码率) | 8(感光度) = **15**，四项；
 * - 录制页可见集里**码率被滤掉**（10-01 布局批：它是 Dock 外固定读数，不进网格），
 *   所以读数块那一排只剩三颗，顺序 = 枚举序 快门 → 帧率 → 感光度；
 * - 竖屏 360：底板 216 居中 ⇒ 右半只剩 44dp，一颗读数（估宽 90）都放不下 ⇒ 退回"整排之上"，
 *   列数回到**整幅宽**取的 3 颗一行 ⇒ 三颗各占一列，感光度落在**第 0 行第 2 列**
 *   —— 正是码率自 10-01 移出网格后空出来的那一格。
 *
 * ## 老用户那一侧只钉"不改人设置"这一条
 * 已存过 `hud_items` 的用户读的是自己那一份（`SettingsScreen.hudItems` 的 getInt 回退只在**键缺失**时生效），
 * 所以三档掩码解出来仍然只有三档。这条钉住"没有顺手给老用户补感光度"。
 */
class HudDefaultIsoTest {

    private fun h(i: HudItem) = HudEntry.of(i)

    /** 本机竖屏安全区实测宽（真机 dump，不是令牌）：720px ÷ density 2.0 */
    private val portraitSafeW = 360

    /**
     * 一帧网格解析的最小输入（与 `HudPageGridTest` 同一份写法）：实测高喂 0 ⇒ 行跨度恒 1，
     * 分行档由生产算式 [planReadoutRow] 现给，不在本文件写死。
     */
    private fun planOf(visible: Set<HudEntry>, readoutPerRow: Int) = HudGridPlan(
        visible = visible,
        readoutPerRow = readoutPerRow,
        rowPitchOf = { 68 },
        cellHeightOf = { 0 }
    )

    @Test
    fun defaultMaskPutsIsoOnTheReadoutRowWithoutTouchingSavedUsers() {
        // ① 掩码这一层：默认四档含感光度（位 = 8，掩码 1|2|4|8 = 15）
        val defaults = HudItem.typesOf(HudItem.DEFAULT_MASK)
        assertTrue("默认掩码里必须有感光度", HudItem.ISO in defaults)
        assertEquals(
            "默认档 = 快门 / 帧率 / 码率 / 感光度（枚举序）",
            listOf(HudItem.SHUTTER, HudItem.FPS, HudItem.BITRATE, HudItem.ISO), defaults
        )
        assertEquals("掩码值 = 1|2|4|8", 15, HudItem.DEFAULT_MASK)

        // 兼容这一侧：老用户存的那份三档掩码解出来仍然只有三档（默认值不许反推回去给人补上）
        val saved = HudItem.maskOf(listOf(HudItem.SHUTTER, HudItem.FPS, HudItem.BITRATE))
        assertFalse(
            "已存过掩码的老用户保持自己的设置，不补感光度",
            HudItem.ISO in HudItem.typesOf(saved)
        )
        assertEquals(3, HudItem.typesOf(saved).size)
    }

    @Test
    fun isoShowsUpAsTheThirdSlotOfThePortraitReadoutRow() {
        // 录制页可见集：与 `CameraScreen` 的 `visibleEntries` 同一句 —— 掩码里的读数，码率除外
        val recordingVisible: Set<HudEntry> = buildSet {
            HudItem.typesOf(HudItem.DEFAULT_MASK)
                .filter { it != HudItem.BITRATE }
                .forEach { add(HudEntry.of(it)) }
        }
        assertEquals(3, recordingVisible.size)

        // ② 读数块的可见顺序：表里的顺序 = 枚举序，过滤后 = 快门 → 帧率 → 感光度
        val table = HudLayoutTable.default()
        val order = table.visibleOrderOf(HudZone.READOUT, recordingVisible)
        assertEquals(
            "读数块那一排的可见顺序 = 快门 → 帧率 → 感光度",
            listOf(HudItem.SHUTTER, HudItem.FPS, HudItem.ISO), order.map { it.item }
        )
        assertEquals("码率不进网格可见集", false, h(HudItem.BITRATE) in recordingVisible)

        // ③ 竖屏的"一行几颗"由生产算式现给（入参全引令牌，别在本文件另写一份 6/8/8/216）
        val perRow = planReadoutRow(
            readoutCount = order.size,
            fontScale = 1f,
            safeWidthDp = portraitSafeW,
            dockWidthDp = BottomDockWidthFallback.value.roundToInt(),
            dockStripDp = BottomBarSpaceFallback.value.roundToInt(),
            dockRowBaselineDp = BottomBarOuterPadV.value.roundToInt(),
            endPadDp = HudEdgePad.value,
            dockGapDp = WotaSpace.s.value,
            landscape = false,
            readoutWidthDp = 0   // 首帧量不到 = 先按估宽走，与录制页同一条退路
        ).perRow
        assertEquals("竖屏 360 退回整排之上后回到 3 颗一行", 3, perRow)

        // ③' 推导格：三颗各占一列 ⇒ 感光度落 (0,2) 这一行的第 2 列
        val derived = defaultCellsOf(HudZone.READOUT, order, planOf(recordingVisible, perRow))
        assertEquals(GridCell(0, 0), derived[h(HudItem.SHUTTER)])
        assertEquals(GridCell(1, 0), derived[h(HudItem.FPS)])
        assertEquals(
            "感光度落在第 0 行第 2 列（码率出网格后空出来的那一格）",
            GridCell(2, 0), derived[h(HudItem.ISO)]
        )
        // 只出现三个格：码率即使在掩码里也不占格，隐藏/移出的颗不许偷偷进推导表
        assertEquals(3, derived.size)
    }
}
