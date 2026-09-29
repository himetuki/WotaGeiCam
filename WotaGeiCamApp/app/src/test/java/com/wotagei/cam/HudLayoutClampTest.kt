package com.wotagei.cam

import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.HudPointDp
import com.wotagei.cam.ui.HudRectDp
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.ZonePlacement
import com.wotagei.cam.ui.clampZonePos
import com.wotagei.cam.ui.clampZoneX
import com.wotagei.cam.ui.clampZoneYRange
import com.wotagei.cam.ui.dockLowerY
import com.wotagei.cam.camera.RecordStatus
import com.wotagei.cam.ui.dockDragBlocked
import com.wotagei.cam.ui.dockSnapY
import com.wotagei.cam.ui.dockUpperY
import com.wotagei.cam.ui.overlappingZones
import com.wotagei.cam.ui.pxToDp
import com.wotagei.cam.ui.zoneAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 越界钳制、重叠提示、底栏两档落位（13 号计划第 5、8 条里唯一能纯算的两块）。
 *
 * 数值档用的是**录制页实测同源的那些数**（横屏 800×360、右缘不可视带 34dp、顶栏 44dp、底栏 72dp、
 * 底栏外边距 6dp、上栏间距取令牌 `WotaSpace.s` 8dp），所以这里钉的是"钳制吃了哪些避让量"，
 * 而不是随手编一组好算的数。钳制本身是真断言：把 `endInsetDp` 忘了、把 `topAvoid` 当 0、
 * 区间算成负数（`coerceIn` 会抛）这三类退化都会当场红。
 */
class HudLayoutClampTest {

    /** 横屏默认档：safeDrawing 后 800×360，§58 的右缘让位 34，顶栏 44（S2-1 实测回报的兜底档），底栏 72 */
    private val land = HudAreaDp(width = 800, height = 360, endInsetDp = 34, topAvoidDp = 44, bottomAvoidDp = 72)

    /** 竖屏同一台机：根容器就是可视宽，右缘让位必须为 0（S3-3：套上去会把快门反向推偏） */
    private val port = HudAreaDp(width = 360, height = 800, endInsetDp = 0, topAvoidDp = 44, bottomAvoidDp = 72)

    @Test
    fun rightEdgeInsetOnlyComesFromLandscape() {
        // 可视右缘 = 800 − 34 = 766；宽 94dp 的右竖 Dock 最右只能摆到 766 − 94 = 672
        assertEquals(672, clampZoneX(land, 94))
        // 竖屏没有那条不可视带，360 就是右缘
        assertEquals(266, clampZoneX(port, 94))
        // 容器比可视区还宽（120% 文本 + 极窄分屏）：贴左放，**不许为负**
        assertEquals(0, clampZoneX(land, 900))
        // 零宽（条目全被关掉、底板空了）时上限就是可视右缘本身，不是 0
        assertEquals(766, clampZoneX(land, 0))
    }

    @Test
    fun bandZonesEatBothMeasuredAvoidances() {
        // 左/右/读数块三枚：y 下界是顶栏实测高，上界是安全区高 − 底栏实测高 − 自身高
        assertEquals(44..86, clampZoneYRange(HudZone.RIGHT, land, 202))
        assertEquals(44..278, clampZoneYRange(HudZone.LEFT, land, 10))
        assertEquals(44..(800 - 72 - 42), clampZoneYRange(HudZone.READOUT, port, 42))
        // 顶栏与底栏两枚贴的是屏幕边，不受这两条避让量约束（底栏还要往上搬一栏，见第 8 条）
        assertEquals(0..(360 - 44), clampZoneYRange(HudZone.TOP, land, 44))
        assertEquals(0..(360 - 72), clampZoneYRange(HudZone.BOTTOM, land, 72))
    }

    @Test
    fun degenerateBandFallsBackInsteadOfThrowing() {
        // 告警条 + 满配读数把带挤没了：区间若为负，coerceIn 直接抛，整个 HUD 就没了
        val squeezed = HudAreaDp(width = 800, height = 200, endInsetDp = 34, topAvoidDp = 120, bottomAvoidDp = 100)
        val range = clampZoneYRange(HudZone.RIGHT, squeezed, 90)
        assertTrue("区间必须非负：$range", range.first <= range.last)
        assertEquals(0..(200 - 90), range)
        // 落点仍然取得到，并且落在回退区间里
        assertEquals(110, clampZonePos(HudZone.RIGHT, 700, 9999, 94, 90, squeezed).yDp)
        assertEquals(672, clampZonePos(HudZone.RIGHT, 700, 9999, 94, 90, squeezed).xDp)
    }

    @Test
    fun absolutePositionIsClampedOnBothAxes() {
        // 右竖 Dock（实测 94×202dp）拖出可视右缘与上下界
        val far = clampZonePos(HudZone.RIGHT, 9999, 9999, 94, 202, land)
        assertEquals(ZonePlacement(672, 86), far)
        val negative = clampZonePos(HudZone.RIGHT, -50, 10, 94, 202, land)
        assertEquals(ZonePlacement(0, 44), negative)
        // 顶栏贴顶：允许 y=0，但下界是「安全区底 − 自身高」
        val top = clampZonePos(HudZone.TOP, 0, 0, 300, 44, land)
        assertEquals(ZonePlacement(0, 0), top)
        val topDown = clampZonePos(HudZone.TOP, 10, 9999, 300, 44, land)
        assertEquals(ZonePlacement(10, 316), topDown)
        // 钳过的值再钳一次不变（幂等，编辑页连续拖动靠这条才不会漂）
        assertEquals(far, clampZonePos(HudZone.RIGHT, far.xDp, far.yDp, 94, 202, land))
    }

    // ---------- 第 8 条：底栏 Dock 的上栏 / 下栏 ----------

    @Test
    fun dockTwoRowsComeFromMeasuredHeights() {
        // 下栏＝定稿位置（底边让开 BottomBarOuterPadV 6dp）；上栏＝再上一整排 + 一个令牌间距
        val lower = dockLowerY(land, heightDp = 72, bottomPadDp = 6)
        val upper = dockUpperY(land, heightDp = 72, bottomPadDp = 6, gapDp = 8)
        assertEquals(282, lower)
        assertEquals(202, upper)
        // 排高变了（120% 文本 → 底板更高）两档跟着分开，不是写死的 80dp：
        // 下栏 360−6−78 = 276，上栏 276−78−8 = 190（间距被排高顶开到 86dp）
        assertEquals(190, dockUpperY(land, heightDp = 78, bottomPadDp = 6, gapDp = 8))
        assertEquals(276, dockLowerY(land, heightDp = 78, bottomPadDp = 6))
    }

    @Test
    fun dockSnapsToTwoRowsOnlyAndUsesHalfPitch() {
        val lower = dockLowerY(land, 72, 6)
        val upper = dockUpperY(land, 72, 6, 8)
        // 原地松手 / 往下拖 / 往上但没过半程 → 下栏
        assertEquals(lower, dockSnapY(land, 72, 6, 8, deltaYDp = 0))
        assertEquals(lower, dockSnapY(land, 72, 6, 8, deltaYDp = 30))
        assertEquals(lower, dockSnapY(land, 72, 6, 8, deltaYDp = -39))
        // 过半程（间距 80dp，半程 40）→ 上栏；继续往上也是上栏，不许出现第三档
        assertEquals(upper, dockSnapY(land, 72, 6, 8, deltaYDp = -40))
        assertEquals(upper, dockSnapY(land, 72, 6, 8, deltaYDp = -999))
        // 竖屏（800 高）那两档同样只由实测高决定
        assertEquals(dockLowerY(port, 72, 6), dockSnapY(port, 72, 6, 8, 0))
        assertEquals(dockUpperY(port, 72, 6, 8), dockSnapY(port, 72, 6, 8, -999))
    }

    @Test
    fun dockRowSnapIsStableWhenThereIsNoSecondRow() {
        // 极矮窗口（分屏 / 120% 文本把带挤光）：两档重合时不许跳，一律按下栏处理
        val tiny = HudAreaDp(width = 800, height = 70, endInsetDp = 34, topAvoidDp = 0, bottomAvoidDp = 0)
        assertEquals(0, dockLowerY(tiny, heightDp = 72, bottomPadDp = 6))
        assertEquals(0, dockUpperY(tiny, heightDp = 72, bottomPadDp = 6, gapDp = 8))
        assertEquals(0, dockSnapY(tiny, 72, 6, 8, -999))
    }

    @Test
    fun dockDragGateRejectsEveryRecordingState() {
        // 第 8 条的 gate：正在录 / 正在准备 / 正在收尾都不许把底栏搬走（停止录制那一指最高优先级）
        assertTrue(dockDragBlocked(RecordStatus.PREPARE))
        assertTrue(dockDragBlocked(RecordStatus.START))
        assertTrue("STOPPING 也被挡：分裂动画进行中没有第二指的空间", dockDragBlocked(RecordStatus.STOPPING))
        // 空闲与失败态放行（ERROR 那档 Recorder 已复位，此时搬底栏不影响停录）
        assertFalse(dockDragBlocked(RecordStatus.IDLE))
        assertFalse(dockDragBlocked(RecordStatus.ERROR))
        // 五档逐个都判过：新增一档时这条会要求显式表态，而不是默认落到"放行"
        assertEquals(5, RecordStatus.values().size)
    }

    // ---------- 重叠只提示不禁止 ----------

    @Test
    fun overlapDetectionReportsPairsNotCounts() {
        val rects = mapOf(
            HudZone.LEFT to HudRectDp(8, 60, 200, 262),
            HudZone.READOUT to HudRectDp(150, 200, 400, 262),
            HudZone.TOP to HudRectDp(8, 4, 300, 48)
        )
        val pairs = overlappingZones(rects)
        assertEquals("只该报出真重叠的那一对", listOf(HudZone.LEFT to HudZone.READOUT), pairs)
        // 外接矩形边贴边不算叠（底栏贴着读数块下沿就是这个形态）
        val touching = mapOf(
            HudZone.READOUT to HudRectDp(500, 100, 700, 200),
            HudZone.BOTTOM to HudRectDp(300, 200, 600, 282)
        )
        assertTrue("边贴边不许报重叠，否则默认态就会满屏假提示", overlappingZones(touching).isEmpty())
        // 空表 / 只有一枚容器：不抛
        assertTrue(overlappingZones(emptyMap()).isEmpty())
        assertTrue(overlappingZones(mapOf(HudZone.TOP to HudRectDp(0, 0, 10, 10))).isEmpty())
    }

    @Test
    fun pointerPicksInnermostZoneNotFirstMatch() {
        val rects = mapOf(
            HudZone.LEFT to HudRectDp(0, 0, 100, 300),
            HudZone.READOUT to HudRectDp(50, 200, 150, 260)
        )
        // 读数块整个压在左 Dock 的角上：小面积的那枚才是用户看着的那块
        assertEquals(HudZone.READOUT, zoneAt(HudPointDp(60, 210), rects))
        assertEquals(HudZone.LEFT, zoneAt(HudPointDp(10, 10), rects))
        assertNull("指针在两枚容器之外：这次拖动不改归属", zoneAt(HudPointDp(999, 999), rects))
        assertNull(zoneAt(HudPointDp(0, 0), emptyMap()))
    }

    @Test
    fun rectHelpersDoNotInventSizes() {
        val r = HudRectDp(8, 60, 200, 262)
        assertEquals(192, r.width)
        assertEquals(202, r.height)
        // 边界：右缘与下缘是**开区间**（相邻两枚外接矩形边贴边时 contains 不能两边都 true）
        assertTrue(r.contains(8, 60))
        assertFalse("右下缘是开区间，相邻两枚容器边贴边时不能两边都命中", r.contains(200, 262))
    }

    @Test
    fun pxToDpHandlesZeroDensityWithoutCrashing() {
        assertEquals(100, pxToDp(300f, 3f))
        // 半像素向上进位（300/3=100 整除，4.5/3=1.5 → 2）
        assertEquals(2, pxToDp(4.5f, 3f))
        assertEquals(1, pxToDp(1.4f, 1f))
        // density 0（分屏过渡帧 / 测试桩）必须回 0 而不是抛除零
        assertEquals(0, pxToDp(100f, 0f))
        assertEquals(0, pxToDp(0f, 2f))
    }
}
