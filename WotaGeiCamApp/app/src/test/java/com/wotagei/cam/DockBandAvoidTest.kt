package com.wotagei.cam

import com.wotagei.cam.ui.BottomBarSpaceFallback
import com.wotagei.cam.ui.BottomDockWidthFallback
import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.dockBottomAvoidDp
import com.wotagei.cam.ui.zoneBandHeight
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 用户 2026-10-01 第 5 项「左右侧 Dock 更高、内部控件显示更全」的账：竖 Dock 的底部避让
 * 只避**真正横向重叠**的邻居（[dockBottomAvoidDp]），不再一律避"底栏整带 72dp"。
 *
 * ## 数字证据（800×360 横屏、Dock 40dp、底栏 216dp、底栏带 72dp、读数块 6+42）
 * 旧账（`bottomAvoidDp = 底栏整带 72`）：右/左 Dock 可用高都 = 360 − 44 − 72 = **244dp**——
 * 右 Dock 5 组内容 ≈250dp 装不下，第 5 组「防抖」出带、默认态「对焦」裁半截。
 * 新账：底栏居中 [292, 508]，右 Dock [760, 800] 与左 Dock [0, 40] 都**不与它重叠** ⇒
 * 右 Dock 只剩读数块那一截 `6+42+2 = 50` ⇒ 可用高 = 360 − 44 − 50 = **266dp**（+22）；
 * 左 Dock 只留设计留白 `HudEdgePad`（8dp） ⇒ 可用高 = 360 − 44 − 8 = **308dp**（+64）。
 * 这只差那一枚 2dp 是**观感缝**（两块胶囊的边不相接），与 WotaSpace 的 4pt 栅格不是同一条账。
 *
 * ## 可证伪性（四条承重）
 * - 把"不重叠"那一档删掉（一律返回底栏带高）⇒ [docksGainHeightOnTheCommonLandscape] 红；
 * - 重叠判据写成 `<=`（边相接也算叠）⇒ [edgeTouchingIsNotOverlap] 红；
 * - 把 READOUT 那一截（`readoutBottomDp + readoutHeightDp + 缝`）删掉 ⇒ [emptyReadoutClosesTheReadoutSeam]
 *   与 [landscapeDocksOnlyDodgeTheOverlappingNeighbor] 的右 Dock 档红（5 组内容重新压上读数块）；
 * - 首帧那一支（`dockWidthDp ≤ 0`）改成"按不重叠算" ⇒ [unmeasuredDockStaysConservative] 红。
 *
 * 入参凡是调用点拿令牌的那几条**引令牌本体**（底栏宽、底栏带高），期望数字仍按文档口径写死——
 * 令牌一动就必须红一次、逼人来对账（与 `HudReadoutRowPlanTest` 同一套手法）。
 */
class DockBandAvoidTest {

    /** 录制页 / 编辑页那一串实参的同一批（读数块 6 + 42 是"同基线档 + 常态块高"那两个数） */
    private fun avoid(
        safeW: Int,
        dockW: Int,
        fromEnd: Boolean,
        readoutBottom: Int = 6,
        readoutHeight: Int = 42,
        bottomW: Int = DockW,
        bottomStrip: Int = DockStrip,
    ) = dockBottomAvoidDp(
        safeWidthDp = safeW,
        dockWidthDp = dockW,
        bottomDockWidthDp = bottomW,
        bottomDockStripDp = bottomStrip,
        fromEnd = fromEnd,
        readoutBottomDp = readoutBottom,
        readoutHeightDp = readoutHeight,
    )

    @Test
    fun landscapeDocksOnlyDodgeTheOverlappingNeighbor() {
        // 800dp 安全区：底栏居中 [292, 508]，两枚竖 Dock 都够不着它
        // 右 Dock [760, 800] 与读数块同贴右缘 ⇒ 只叠读数那一截 = 6 + 42 + 2 = 50（**不含** 72）
        assertEquals(50, avoid(safeW = 800, dockW = 40, fromEnd = true))
        // 左 Dock [0, 40] 谁也不叠 ⇒ 只剩设计留白（HudEdgePad = 8；那枚令牌一动这条就该红）
        assertEquals(8, avoid(safeW = 800, dockW = 40, fromEnd = false))
    }

    @Test
    fun docksGainHeightOnTheCommonLandscape() {
        // 现值（底栏整带 72 那一档）：两枚竖 Dock 都只剩 360 − 44 − 72 = 244dp
        val old = HudAreaDp(width = 800, height = 360, topAvoidDp = 44, bottomAvoidDp = DockStrip)
        assertEquals(244, zoneBandHeight(HudZone.RIGHT, old))
        assertEquals(244, zoneBandHeight(HudZone.LEFT, old))
        // 新账右 Dock：360 − 44 − 50 = 266（多出 22dp ⇒ 第 5 组「防抖」进得来、默认那几颗不再裁半截）
        val rightNew = old.copy(bottomAvoidDp = avoid(safeW = 800, dockW = 40, fromEnd = true))
        assertEquals(50, rightNew.bottomAvoidDp)
        assertEquals(266, zoneBandHeight(HudZone.RIGHT, rightNew))
        // 新账左 Dock：360 − 44 − 8 = 308（多出 64dp）
        val leftNew = old.copy(bottomAvoidDp = avoid(safeW = 800, dockW = 40, fromEnd = false))
        assertEquals(8, leftNew.bottomAvoidDp)
        assertEquals(308, zoneBandHeight(HudZone.LEFT, leftNew))
    }

    @Test
    fun narrowWindowFallsBackToTheWholeStrip() {
        // 260dp：底栏 216 居中 [22, 238]；右 Dock [220, 260] 与左 Dock [0, 40] 都真压上它
        // ⇒ 退回旧行为（降级路径一条不丢）：右 = max(72, 50) = 72、左 = 72
        assertEquals(72, avoid(safeW = 260, dockW = 40, fromEnd = true))
        assertEquals(72, avoid(safeW = 260, dockW = 40, fromEnd = false))
        // 重叠档里 READOUT 那一截仍在：块长到 100 时取大 = max(72, 6+100+2=108) = 108（并联不求和）
        assertEquals(108, avoid(safeW = 260, dockW = 40, fromEnd = true, readoutHeight = 100))
    }

    @Test
    fun edgeTouchingIsNotOverlap() {
        // 260dp、Dock 22dp：左 Dock [0, 22] 的右缘正好压在底栏左缘 22 上——两块像素没有重叠
        // ⇒ 判据写成 `<=` 这条就红
        assertEquals(8, avoid(safeW = 260, dockW = 22, fromEnd = false))
        // 右 Dock [238, 260] 与底栏右缘 238 相接：同样不叠，只剩读数那一截 50
        assertEquals(50, avoid(safeW = 260, dockW = 22, fromEnd = true))
    }

    @Test
    fun splitScreenStillGivesTheWholeStrip() {
        // 200dp 分屏：底栏 216 比屏还宽（居中后 [-8, 208]），两枚 Dock 都直接压上去 ⇒ 两侧都退回整条带
        assertEquals(72, avoid(safeW = 200, dockW = 40, fromEnd = true))
        assertEquals(72, avoid(safeW = 200, dockW = 40, fromEnd = false))
    }

    @Test
    fun unmeasuredDockStaysConservative() {
        // 首帧 Dock 宽还是 0（底栏宽同样没量到）：判不了重叠就别赌，两侧都按整条底栏带给
        // ——下一帧实测接管，与 topBarH / dockStripH 那套"两轮收敛"同一条
        assertEquals(72, avoid(safeW = 800, dockW = 0, fromEnd = true))
        assertEquals(72, avoid(safeW = 800, dockW = 0, fromEnd = false))
        // 坏值（负宽）同一条保守路径
        assertEquals(72, avoid(safeW = 800, dockW = -40, fromEnd = false))
    }

    @Test
    fun emptyReadoutClosesTheReadoutSeam() {
        // 读数全关（块回报 0）：右 Dock 那一截自己收回去，只剩设计留白 max(8, 6+0+2) = 8
        assertEquals(8, avoid(safeW = 800, dockW = 40, fromEnd = true, readoutHeight = 0))
        // 坏值（负高）按 0 处理：与 areaForRightDock 同一条语义，不许把下界拉到更低
        assertEquals(8, avoid(safeW = 800, dockW = 40, fromEnd = true, readoutHeight = -50))
        // 同基线档常态（读数块底边让位 = 基线 − 块内边距 = 0）：0+0+2 = 2 仍被设计留白 8 兜住
        assertEquals(8, avoid(safeW = 800, dockW = 40, fromEnd = true, readoutBottom = 0, readoutHeight = 0))
        // 左 Dock 与读数块无关：读数空不空都是 8
        assertEquals(8, avoid(safeW = 800, dockW = 40, fromEnd = false, readoutHeight = 0))
    }

    private companion object {
        val DockW = BottomDockWidthFallback.value.roundToInt()      // 216：底板常态宽（= 首帧兜底值）
        val DockStrip = BottomBarSpaceFallback.value.roundToInt()   // 72：底栏那一排的带高
    }
}
