package com.wotagei.cam

import com.wotagei.cam.ui.HudAreaDp
import com.wotagei.cam.ui.HudPointDp
import com.wotagei.cam.ui.HudRectDp
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.ZonePlacement
import com.wotagei.cam.ui.clampZonePos
import com.wotagei.cam.ui.clampZoneX
import com.wotagei.cam.ui.clampZoneYRange
import com.wotagei.cam.ui.chromeBandBottomDp
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
 * 数值档用的是**录制页实测同源的那些数**：横屏那枚 `HudAreaDp.width` 取的是套上 `safeDrawingPadding()`
 * 之后的**安全区实测宽 766dp**（本机 1600px 面板让掉挖孔那条 68px 边），不是根容器的 800dp；
 * 竖屏 360dp（侧边没有不可视带，那一层只在上下让）。顶栏 44dp、底栏 72dp、底栏外边距 6dp、
 * 上栏间距取令牌 `WotaSpace.s` 8dp——都是首帧兜底/实测回报那两条真源，不是随手编的好算数。
 *
 * 钳制本身是真断言：把安全区宽当根容器宽、把 `topAvoid` 当 0、区间算成负数（`coerceIn` 会抛）、
 * 底栏落位写进绝对 x 这四类退化都会当场红。
 */
class HudLayoutClampTest {

    /** 横屏：safeDrawing 后实测 766×360（挖孔那条边已经在里面），顶栏 44（S2-1 实测回报的兜底档），底栏 72 */
    private val land = HudAreaDp(width = 766, height = 360, topAvoidDp = 44, bottomAvoidDp = 72)

    /** 竖屏同一台机：360×800，侧边不让位（旧写法在这里再扣 34dp 就是凭空吃掉一档） */
    private val port = HudAreaDp(width = 360, height = 800, topAvoidDp = 44, bottomAvoidDp = 72)

    @Test
    fun rightEdgeIsTheSafeAreaEdgeItself() {
        // 横屏：安全区宽 766 就是可视右缘（挖孔那条边已由外层 safeDrawingPadding 让掉，不再扣第二笔）
        assertEquals(672, clampZoneX(land, 94))
        // 竖屏：360 就是可视右缘，侧边本来就没有不可视带
        assertEquals(266, clampZoneX(port, 94))
        // 容器比可视区还宽（120% 文本 + 极窄分屏）：贴左放，**不许为负**
        assertEquals(0, clampZoneX(land, 900))
        // 零宽（条目全被关掉、底板空了）时上限就是可视右缘本身，不是 0
        assertEquals(766, clampZoneX(land, 0))
        // 算式里没有"方向"这一项：挖孔落左短边还是右短边，safeDrawingPadding 都给 766 宽的安全区，
        // 调用方把实测宽原样报上来就两个姿态都对（旧的 endInsetDp 是按方向写死的一补，换姿态必错一次，
        // #68 已删）。这条只能钉"入参里没有方向量"，姿态本身要真机量（见交付报告的真机待验清单）
    }

    @Test
    fun bottomDockDropNeverWritesAnXOverride() {
        // B4 审查 S1：底栏的横向落位**不许进表**。录制页 onDrop 与编辑页 dropContainer 都只把
        // clampZonePos 的结果写进表，所以这道闸就在这儿：给它一个再像真值不过的绝对 x，也必须是哨兵
        assertEquals(ZonePlacement(-1, 250), clampZonePos(HudZone.BOTTOM, 500, 250, 216, 72, land, topZoneMinYDp = 0))
        // 纵向照常钳（下界 = 安全区高 − 排高 = 288），只有 x 被抹
        assertEquals(ZonePlacement(-1, 288), clampZonePos(HudZone.BOTTOM, 0, 9999, 216, 72, land, topZoneMinYDp = 0))
        // 负 y 夹回 0（走到这一步说明 y 是拖出来的：两轴都是哨兵时调用方早早原样返回了）
        assertEquals(ZonePlacement(-1, 0), clampZonePos(HudZone.BOTTOM, -1, -50, 216, 72, land, topZoneMinYDp = 0))
        // 反面对照：另外四枚容器的 x 照旧钳进安全区，这条闸只作用底栏
        assertEquals(466, clampZonePos(HudZone.READOUT, 9999, 300, 300, 42, land, topZoneMinYDp = 0).xDp)
        assertEquals(672, clampZonePos(HudZone.RIGHT, 9999, 100, 94, 202, land, topZoneMinYDp = 0).xDp)
    }

    @Test
    fun bandZonesEatBothMeasuredAvoidances() {
        // 左/右/读数块三枚：y 下界是顶栏实测高，上界是安全区高 − 底栏实测高 − 自身高
        assertEquals(44..86, clampZoneYRange(HudZone.RIGHT, land, 202, topZoneMinYDp = 0))
        assertEquals(44..278, clampZoneYRange(HudZone.LEFT, land, 10, topZoneMinYDp = 0))
        assertEquals(44..(800 - 72 - 42), clampZoneYRange(HudZone.READOUT, port, 42, topZoneMinYDp = 0))
        // 顶栏与底栏两枚贴的是屏幕边，不受这两条避让量约束（底栏还要往上搬一栏，见第 8 条）。
        // ⚠ TOP 的 lo 现在由第四个形参说了算：传 0 = 改前那一档（本批新增的那一条在下面单独打）
        assertEquals(0..(360 - 44), clampZoneYRange(HudZone.TOP, land, 44, topZoneMinYDp = 0))
        assertEquals(0..(360 - 72), clampZoneYRange(HudZone.BOTTOM, land, 72, topZoneMinYDp = 0))
    }

    @Test
    fun degenerateBandFallsBackInsteadOfThrowing() {
        // 告警条 + 满配读数把带挤没了：区间若为负，coerceIn 直接抛，整个 HUD 就没了
        val squeezed = HudAreaDp(width = 766, height = 200, topAvoidDp = 120, bottomAvoidDp = 100)
        val range = clampZoneYRange(HudZone.RIGHT, squeezed, 90, topZoneMinYDp = 0)
        assertTrue("区间必须非负：$range", range.first <= range.last)
        assertEquals(0..(200 - 90), range)
        // 落点仍然取得到，并且落在回退区间里
        assertEquals(110, clampZonePos(HudZone.RIGHT, 700, 9999, 94, 90, squeezed, topZoneMinYDp = 0).yDp)
        assertEquals(672, clampZonePos(HudZone.RIGHT, 700, 9999, 94, 90, squeezed, topZoneMinYDp = 0).xDp)
    }

    @Test
    fun absolutePositionIsClampedOnBothAxes() {
        // 右竖 Dock（实测 94×202dp）拖出可视右缘与上下界
        val far = clampZonePos(HudZone.RIGHT, 9999, 9999, 94, 202, land, topZoneMinYDp = 0)
        assertEquals(ZonePlacement(672, 86), far)
        val negative = clampZonePos(HudZone.RIGHT, -50, 10, 94, 202, land, topZoneMinYDp = 0)
        assertEquals(ZonePlacement(0, 44), negative)
        // 顶栏贴顶：允许 y=0，但下界是「安全区底 − 自身高」。
        // topZoneMinYDp 传 0 = 录制页/改前那一档（编辑页把操作栏下缘喂进来的那一档在下面单独打）
        val top = clampZonePos(HudZone.TOP, 0, 0, 300, 44, land, topZoneMinYDp = 0)
        assertEquals(ZonePlacement(0, 0), top)
        val topDown = clampZonePos(HudZone.TOP, 10, 9999, 300, 44, land, topZoneMinYDp = 0)
        assertEquals(ZonePlacement(10, 316), topDown)
        // 钳过的值再钳一次不变（幂等，编辑页连续拖动靠这条才不会漂）
        assertEquals(far, clampZonePos(HudZone.RIGHT, far.xDp, far.yDp, 94, 202, land, topZoneMinYDp = 0))
    }

    /**
     * 本批新增：TOP 的**绝对定位支**下限（[clampZoneYRange] 的 `topZoneMinYDp`）。
     * 场景是编辑页那条操作栏压在顶栏上——把顶栏整枚拖进操作栏矩形里时，纵向下限必须把它推到
     * 操作栏下缘之下，否则那几像素既点不到也拖不动（操作栏在拖拽捕获层之上）。
     *
     * 可证伪：把 TOP 的 lo 改回恒 0（或错取 area.topAvoidDp）这两条就红。
     * `topZoneMinYDp=0` 那一条同时钉住"录制页逐字不变"（录制页恒传 0）。
     */
    @Test
    fun topZoneAbsolutePlacementEatsTheMeasuredActionBarBottom() {
        // 操作栏下缘换算成 56dp（与 chromeBandBottomDp(180, 68, 2f) 同源）⇒ 顶栏 y=0 被推到 56
        assertEquals(56, clampZonePos(HudZone.TOP, 0, 0, 300, 44, land, topZoneMinYDp = 56).yDp)
        // 已经在 56 之下（y=100）不动；上界仍是「安全区底 − 自身高」= 316
        assertEquals(100, clampZonePos(HudZone.TOP, 0, 100, 300, 44, land, topZoneMinYDp = 56).yDp)
        assertEquals(316, clampZonePos(HudZone.TOP, 0, 9999, 300, 44, land, topZoneMinYDp = 56).yDp)
        // 传播到区间那一层（两者必须同一条带）
        assertEquals(56..316, clampZoneYRange(HudZone.TOP, land, 44, topZoneMinYDp = 56))
        // 0 = 录制页/改前那一档：贴顶仍是 0
        assertEquals(0, clampZonePos(HudZone.TOP, 0, 0, 300, 44, land, topZoneMinYDp = 0).yDp)
        // 非 TOP 容器**不读**这条下限（把它喂给 LEFT 也不该改变 44 的下界）
        assertEquals(44, clampZonePos(HudZone.LEFT, 0, 0, 94, 10, land, topZoneMinYDp = 56).yDp)
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
        val tiny = HudAreaDp(width = 766, height = 70, topAvoidDp = 0, bottomAvoidDp = 0)
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

    // ---------- #79：操作栏与顶栏那两枚段的交叠判定 ----------

    /**
     * 竖屏本机一手算出来的那些数（density 2.0，单位 dp 一律安全区局部坐标，原点 = 套了
     * `safeDrawingPadding()` 之后那块）：
     * · 安全区窗口原点 y = **68px**（`dumpsys display` 实测挖孔让位 `insets=Rect(0,68-0,0)`）
     * · 操作栏下缘 = 原点 + 上内边距 4dp(8px) + 胶囊行 30dp(60px) + 行距 4dp(8px) +
     *   说明文字 14dp(28px) + 下内边距 4dp(8px) = 68 + 112 = **180px** ⇒ 让位量 = (180−68)/2 = **56dp**
     *   （胶囊行 30dp = `WotaType.chip` 行高 18sp + 纵向内边距预算 12——WotaChip 实高 28dp（5+5），
     *   预算按旧档 30dp 保守多留 2dp，与 [hudChipHeightDp] 同一条尺子）
     * · 三颗胶囊自己的矩形 = 左 8dp（`WotaSpace.s`）起、上 4dp（`TopTopPad`）起、高 30dp、
     *   宽 返回50 + 间距8 + 保存50 + 间距8 + 恢复默认76 = **192dp** ⇒ 右缘 200dp
     *   （胶囊宽 = 汉字数 × 13sp + 左右内边距 12+12；「恢复默认」四字 52+24 = 76）
     * · 顶栏那两枚段 = 真机 bounds 整宽 **271dp**（两段之间只差 `WotaStroke.hairline` 1dp），
     *   原生对齐起点 = 左 8dp（`HudEdgePad`）、上 4dp（`TopTopPad`），高 = labelMedium 16dp + 上下内边距 4+4 = **24dp**
     */
    private val chips = HudRectDp(8, 4, 200, 34)
    private val topUnpushed = HudRectDp(8, 4, 8 + 271, 4 + 24)

    @Test
    fun chromeBandBottomReadsTheMeasuredWindowBottom() {
        // 手算：(180 − 68) / 2 = 56dp
        assertEquals(56, chromeBandBottomDp(180, 68, 2f))
        // 没量到（回报 0）必须给 0，不许给 -34：负 dp 喂进 maxOf 结果虽无害，但日志里那是个假真值
        assertEquals(0, chromeBandBottomDp(0, 68, 2f))
        // 坏值（下缘在原点之上）同样钳成 0
        assertEquals(0, chromeBandBottomDp(40, 68, 2f))
        // density 0（分屏过渡帧）退成 0 而不是除零
        assertEquals(0, chromeBandBottomDp(180, 68, 0f))
    }

    @Test
    fun topZoneOverlapsTheActionBarChipsUnlessPushedToTheMeasuredBand() {
        // ① **证实**上一批没收尾的那件事：不推（nativeTopMinDp = 0）时顶栏那两枚段整条落在
        //    三颗胶囊的矩形里 ⇒ 操作栏在捕获层之上，那几像素既点不到顶栏段也拖不动它
        assertTrue("不推就必须交叠（这条红 = 交叠判据或那两组手算数被动过）", topUnpushed.intersects(chips))
        // ② 推到实测带（56dp）之后不再交叠
        val pushed = HudRectDp(8, 56, 8 + 271, 56 + 24)
        assertFalse("推到操作栏下缘之后必须完全让开", pushed.intersects(chips))
        // ③ 承重的是**哪条线**：三颗胶囊自己的矩形下缘在 34dp（4 + 30），说明文字那行没有 pointerInput
        //    ⇒ 只判"点得到/拖得动"的话 34dp 就够；本批取整条带的 56dp，多让的那 22dp 是**观感**账
        //    （顶栏胶囊不许压在说明文字上）。34 这一档必须验，否则让位量取 30、取 20 都测不出来
        assertTrue("33dp 那一档仍压在胶囊行里", HudRectDp(8, 33, 8 + 271, 33 + 24).intersects(chips))
        assertFalse("34dp 正好脱离胶囊行（下缘是开区间）", HudRectDp(8, 34, 8 + 271, 34 + 24).intersects(chips))
        // ④ 横向确实重叠（不重叠的话①③测不到任何东西）：顶栏段左 8 右 279 vs 胶囊行左 8 右 200
        assertTrue(topUnpushed.left < chips.right && chips.left < topUnpushed.right)
        // ⑤ maxOf(TopTopPad, ·) 那一支：让位量为 0 时必须等于改前的 4dp（录制页五处就是这个数）
        assertEquals(4, maxOf(4, chromeBandBottomDp(0, 68, 2f)))
        assertEquals(56, maxOf(4, chromeBandBottomDp(180, 68, 2f)))
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
