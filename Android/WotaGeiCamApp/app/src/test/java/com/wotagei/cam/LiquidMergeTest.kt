package com.wotagei.cam

import com.wotagei.cam.ui.anim.LiquidMerge
import com.wotagei.cam.ui.anim.DockShell
import com.wotagei.cam.ui.anim.MergePlan
import com.wotagei.cam.ui.anim.MergeScene
import com.wotagei.cam.ui.anim.MergeSlot
import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.anim.chipClicksAccepted
import com.wotagei.cam.ui.anim.chipTravelForScene
import com.wotagei.cam.ui.anim.chipTravelPxOf
import com.wotagei.cam.ui.anim.chipVisualCx
import com.wotagei.cam.ui.anim.chipVisualDistToKeyPx
import com.wotagei.cam.ui.anim.chipVisualRadiusPx
import com.wotagei.cam.ui.anim.dragOwnedByRecordKey
import com.wotagei.cam.ui.anim.mergeProgressWithHook
import com.wotagei.cam.ui.anim.neckVisibleFor
import com.wotagei.cam.ui.hudPerRowFor
import com.wotagei.cam.ui.hudRoomDp
import com.wotagei.cam.ui.hudStripHeightDp
import com.wotagei.cam.ui.anim.linkAlphaFor
import com.wotagei.cam.ui.anim.mergePlanFor
import com.wotagei.cam.ui.anim.mergeProgressOf
import com.wotagei.cam.ui.anim.waistVisibleFor
import com.wotagei.cam.ui.design.WotaMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * B2 的纯函数层（六项第 3、4 条 + 底栏居中算式）。
 *
 * 分成两块证据：
 * ① **能静态证死的**：腰宽公式与断开阈值、PLAIN 档经三个桥函数退化成的直接切换、底栏居中的两条算式
 *    互相对撞、常驻读数的可用宽/换行档/高度预测、录制态位移的命中区安全余量。这些全在这里判。
 * ② **只能真机点的**：融合观感（半融帧里腰是不是连续变细）、动效全开时的预览帧率、
 *    实际像素下的裁字与叠字、以及**「Compose 节点到底有没有用这些纯函数的返回值」**——
 *    下面所有算式类用例都证不了这最后一条（它们只看函数，不看接线），照实记在批次报告点验清单里。
 */
class LiquidMergeTest {

    // ---------- ① 底栏居中算式：不变量①（紧凑胶囊）与②（快门居中）互相对撞 ----------

    /**
     * 快门中心（从底板左外缘量）= 内边距 + 槽宽 + 间距 + 录制键半径。这条是**独立写**的：
     * 它模拟 `Alignment.Center` 把录制键放在"内边距之内、左右等宽槽之间"的结果。
     *
     * 能与不能（审查 S4-2 要求说清楚）：本用例把「底板多宽」与「快门落在哪」两条式子对撞，
     * 任何一侧被单边改错（少算一个 2×pad、间距记两次、槽宽忘了取 max）都会当场失败；
     * 但它**测不出**「布局有没有真的用这两个值」，也测不出 `dockW` 是不是真传给了 `Modifier.width`。
     * 那一条只有真机截图能证（横屏 + 镜头关 + 有素材 三张）。
     */
    private fun recordCenterPx(slot: Float, record: Float, gap: Float, pad: Float) =
        pad + slot + gap + record / 2f

    private fun assertCenteredAndCompact(leftPx: Float, rightPx: Float, expectDock: Float, expectCenter: Float) {
        val record = RecordPx
        val slot = MergeSlot.slotWidthPx(leftPx, rightPx)
        val dock = MergeSlot.dockWidthPx(slot, record, GapPx, PadPx)
        assertEquals("底板宽就是 KDoc 表里那一档", expectDock, dock, 0.001f)
        assertEquals(
            "槽宽 max($leftPx, $rightPx) 时快门中心必须等于底板半宽",
            dock / 2f,
            recordCenterPx(slot, record, GapPx, PadPx),
            0.001f
        )
        assertEquals("快门中心 = 底板中心 = 可视窗口中心", expectCenter, dock / 2f, 0.001f)
    }

    @Test
    fun shutterStaysCenteredInBothReachableVisibilityCombos() {
        // 镜头开（有/无素材同宽：两颗都在同一枚 34dp 方框里画，槽宽由右槽 63 决定）→ W = 216，中心 108
        assertCenteredAndCompact(34f, 63f, expectDock = 216f, expectCenter = 108f)
        // 镜头关（那颗不组合 = 0dp）：槽宽由 63 变 34，底板缩到 158dp，快门仍在正中央
        assertCenteredAndCompact(34f, 0f, expectDock = 158f, expectCenter = 79f)
    }

    @Test
    fun zeroLeftSlotIsAPureFunctionBoundaryNotAUiState() {
        // 缩略图**没有** CamPill 开关位（快门/缩略图/设置入口不可隐藏），所以 leftPx=0 在 UI 上不可达。
        // 这里只钉纯函数本身的边界：两槽都 0 时底板退化成"只包住录制键"的小胶囊，仍然居中、不为负。
        val dock = MergeSlot.dockWidthPx(MergeSlot.slotWidthPx(0f, 0f), RecordPx, GapPx, PadPx)
        assertEquals(RecordPx + 2f * GapPx + 2f * PadPx, dock, 0.001f)
        assertEquals(0f, MergeSlot.slotWidthPx(0f, 0f), 0.001f)
    }

    @Test
    fun slotWidthIsTheMaxOfBothSides() {
        assertEquals(63f, MergeSlot.slotWidthPx(34f, 63f), 0.001f)
        assertEquals(34f, MergeSlot.slotWidthPx(34f, 0f), 0.001f)
        assertEquals(102f, MergeSlot.slotWidthPx(102f, 70f), 0.001f)
    }

    @Test
    fun compactDockIsNarrowerThanFullScreen() {
        // 横屏 800dp：紧凑底板 = 2×63 + 50 + 2×12 + 2×8 = 216dp（100% 字体缩放），远窄于铺满整排
        val slot = MergeSlot.slotWidthPx(34f, 63f)
        val dock = MergeSlot.dockWidthPx(slot, RecordPx, GapPx, PadPx)
        assertEquals(216f, dock, 0.001f)
        assertTrue("底板只包住内容", dock < 800f / 2f)
    }

    // ---------- ② PLAIN 档降级：确实是"消失"而不是"变快" ----------

    /**
     * PLAIN 的证据链是**四座桥**（mergeProgressOf / chipTravelPxOf / waistVisibleFor / linkAlphaFor），
     * 不是 plan 的四个布尔字段本身：字段全对但调用点不读它们，一样是假降级
     * （审查 S4-1/S4-2 点名的弱断言）。
     * 每条桥都喂一个"动画档才会有的中间值"，PLAIN 分支必须无视它——把 `if (plan.animated)` 之类
     * 判断从桥里删掉，这里的断言立刻红。
     */
    @Test
    fun plainModeHasNoAnimationWindowAtAll() {
        val plain = mergePlanFor(MotionMode.PLAIN)
        assertFalse("PLAIN 不建动画状态：进度直接取 0/1", plain.animated)
        assertFalse("PLAIN 不进入腰绘制分支", plain.drawWaist)
        assertFalse("PLAIN 零位移", plain.travel)
        // 进度：传进一个"腰还挂着"的 0.4f，PLAIN 也必须只认 recording（0/1），不走中间态
        assertEquals(1f, mergeProgressOf(plain, recording = true, animatedValue = 0.4f), 0.001f)
        assertEquals(0f, mergeProgressOf(plain, recording = false, animatedValue = 0.4f), 0.001f)
        // 位移：中间态进度下仍然是 0（纲放开之后这条更要紧：位移现在能到整段圆心距，PLAIN 必须一点不动）
        assertEquals(0f, chipTravelPxOf(plain, 0.5f, 68.5f), 0.001f)
        assertEquals(0f, chipTravelPxOf(plain, 1f, -68.5f), 0.001f)
        // 绘制：中间态进度不画腰，连通体也不描（一条路径都不建、一次 drawPath 都不发）
        assertFalse(waistVisibleFor(plain, 0.5f))
        assertFalse(waistVisibleFor(plain, 1f))
        assertEquals(0f, linkAlphaFor(plain, 0.5f), 0.001f)
        assertEquals(0f, linkAlphaFor(plain, 1f), 0.001f)
        // 时长是 0 而不是 120/1：根本不存在中间态，所以不可能被读成"动画变快了"
        assertEquals(0, plain.durationMs)
    }

    @Test
    fun animatedModesReadThroughTheBridges() {
        for (mode in listOf(MotionMode.FLUENT, MotionMode.LIQUID)) {
            val plan: MergePlan = mergePlanFor(mode)
            assertTrue("$mode 要画腰", plan.drawWaist)
            assertTrue("$mode 要位移", plan.travel)
            assertTrue("$mode 要有中间态", plan.animated)
            // 动画档确实把动画状态的当前值当进度（可打断续接的那条路）
            assertEquals("$mode 应读中间值", 0.35f, mergeProgressOf(plan, true, 0.35f), 0.001f)
            // 状态还没建起来（null，首帧）时退化成 0/1，不许读出一个凭空的中间态
            assertEquals("$mode 无动画值时按开关态取", 1f, mergeProgressOf(plan, true, null), 0.001f)
            assertEquals("$mode 无动画值时按开关态取", 0f, mergeProgressOf(plan, false, null), 0.001f)
            // 位移带符号且**放开到全程**：−68.5 × 0.25 = −17.125（负号 = 往左吸向录制键）
            assertEquals("$mode 位移应为负（往录制键吸）", -17.125f, chipTravelPxOf(plan, 0.25f, -68.5f), 0.02f)
            // 纲 = 那段圆心距本身：p=1 那颗的中心正好落在键心（与 blobX/blobY 的终点同一处）
            assertEquals("$mode 终点落在键心", -68.5f, chipTravelPxOf(plan, 1f, -68.5f), 0.02f)
            assertTrue("$mode 中间态要画腰", waistVisibleFor(plan, 0.5f))
            assertEquals(
                "$mode 的连通体不透明度就是 linkAlpha 那一条曲线",
                LiquidMerge.linkAlpha(0.5f), linkAlphaFor(plan, 0.5f), 0.001f
            )
            assertFalse("$mode 进度 0 不画（未录制态一条路径都不建）", waistVisibleFor(plan, 0f))
            assertEquals(0f, linkAlphaFor(plan, 0f), 0.001f)
            assertFalse("$mode 淡出走完之后再画就是浪费", waistVisibleFor(plan, 1f))
            // 预算只有一个真源：令牌 WotaMotion.COMMIT_MS（用户定「FLUENT 档 0.75 秒完成」）。
            // 本字段运行时无消费方，它钉的是"散写 750 冒出来"这类回归（S4-2 已如实标注）
            assertEquals("$mode 的预算必须等于令牌时长", WotaMotion.COMMIT_MS, plan.durationMs)
            assertTrue("$mode 的预算必须在用户定的 0.75 秒之内", plan.durationMs <= 750)
        }
    }


    // ---------- ③ 腰宽公式与断开阈值 ----------

    @Test
    fun waistIsMaxWhenTouchingAndZeroPastThreshold() {
        val rA = 15f
        val rB = 25f
        val cut = LiquidMerge.cutDistancePx(rA, rB)
        assertEquals((rA + rB) * LiquidMerge.CUT_FACTOR, cut, 0.001f)
        // d=0：腰 = min(rA, rB)（两圆完全并成一体）
        assertEquals(rA, LiquidMerge.waistRadiusPx(0f, rA, rB, cut), 0.001f)
        // 到达阈值：收为 0，且此后一律 0（停止绘制由这个 0 触发）
        assertEquals(0f, LiquidMerge.waistRadiusPx(cut, rA, rB, cut), 0.001f)
        assertEquals(0f, LiquidMerge.waistRadiusPx(cut * 3f, rA, rB, cut), 0.001f)
        // 半径为 0（那颗没显示）或阈值非法：不画
        assertEquals(0f, LiquidMerge.waistRadiusPx(10f, 0f, rB, cut), 0.001f)
        assertEquals(0f, LiquidMerge.cutDistancePx(0f, rB), 0.001f)
    }

    @Test
    fun waistShrinksMonotonicallyWithDistance() {
        val rA = 17f
        val rB = 25f
        val cut = LiquidMerge.cutDistancePx(rA, rB)
        // 阈值就是 (rA+rB)×CUT_FACTOR：底栏镜头那颗 (15+25)×0.7 = 28dp，圆心距 68.5dp ⇒ 掐断在四成行程
        assertEquals((rA + rB) * LiquidMerge.CUT_FACTOR, cut, 0.001f)
        var previous = Float.MAX_VALUE
        var steps = 0
        var d = 0f
        while (d < cut) {
            val w = LiquidMerge.waistRadiusPx(d, rA, rB, cut)
            assertTrue("腰宽必须随圆心距单调不增（d=$d）", w <= previous + 0.0001f)
            previous = w
            steps++
            d += 0.5f
        }
        assertTrue("阈值内要有足够的采样密度才谈得上连续变化（每 0.5px 一步）", steps > 40)
    }

    @Test
    fun neckPinchesOffPartwayThroughTheBottomBarJourney() {
        // 底栏真实值：镜头那颗内切圆 15dp、录制键 25dp、圆心距 68.5dp
        val cut = LiquidMerge.cutDistancePx(15f, 25f)
        val travel = 68.5f
        assertTrue("阈值要落在行程中间，才有「半融 → 断开」那一帧可点验", cut < travel)
        assertTrue("阈值又不能太小，否则全程看不见腰", cut > travel * 0.25f)
        val breakProgress = cut / travel
        assertTrue("掐断点 $breakProgress 应在行程中段", breakProgress in 0.25f..0.75f)
        assertFalse("过了掐断点就不许再画", LiquidMerge.linkVisible(travel, 15f, 25f))
    }

    @Test
    fun linkVisibleCutsOffExactlyAtThreshold() {
        val rA = 15f
        val rB = 25f
        val cut = LiquidMerge.cutDistancePx(rA, rB)
        assertTrue(LiquidMerge.linkVisible(1f, rA, rB))
        assertTrue(LiquidMerge.linkVisible(cut * 0.85f, rA, rB))
        assertFalse("超过阈值必须停止绘制（细胞分裂的断开瞬间）", LiquidMerge.linkVisible(cut, rA, rB))
        assertFalse("重合态（d<1px）也不画，交给整体淡出收尾", LiquidMerge.linkVisible(0f, rA, rB))
        assertFalse("那颗没组合（半径 0）时不画", LiquidMerge.linkVisible(20f, 0f, rB))
    }

    @Test
    fun controlOffsetPlacesTheWaistAtMidpoint() {
        // 对称布点的三次贝塞尔：B(0.5) = (rA + 6c + rB)/8 ⇒ 反解出的 c 必须让中点宽度 = 腰宽
        val rA = 20f
        val rB = 25f
        for (waist in listOf(25f, 12f, 3f, 0.7f)) {
            val c = LiquidMerge.controlOffsetPx(waist, rA, rB)
            val mid = (rA + 6f * c + rB) / 8f
            assertEquals("腰宽 $waist 处在中点", waist, mid, 0.001f)
        }
        // 腰细到比两圆连线更窄时控制点变负：这就是"掐出细颈"的形状来源
        assertTrue(LiquidMerge.controlOffsetPx(1f, rA, rB) < 0f)
    }

    @Test
    fun capHandleIsLinearInRadius() {
        // 半圆帽的两段 90° 贝塞尔：控制柄必须 = KAPPA × 半径（半径一次函数）。
        // 这里钉的是"线性"这件事——写成 KAPPA × r² 的话圆帽会炸成巨大线圈，
        // 而那种错在静态检查与编译期都看不见，只有画出来才发现，所以用一条用例锁住。
        assertEquals(0.5523f, LiquidMerge.KAPPA, 0.0001f)
        val h10 = LiquidMerge.capHandlePx(10f)
        val h20 = LiquidMerge.capHandlePx(20f)
        assertEquals(h10 * 2f, h20, 0.001f)
        assertEquals(LiquidMerge.KAPPA * 10f, h10, 0.001f)
        assertTrue("控制柄短于半径，否则弧会鼓出去", h10 < 10f)
        assertEquals(0f, LiquidMerge.capHandlePx(0f), 0.001f)
    }

    @Test
    fun fadesFinishCleanly() {
        assertEquals(1f, LiquidMerge.chipAlpha(0f), 0.001f)
        assertEquals(0f, LiquidMerge.chipAlpha(1f), 0.001f)
        assertEquals("本体在液滴分离之后才开始消失", 1f, LiquidMerge.chipAlpha(LiquidMerge.CHIP_FADE_FROM), 0.001f)
        assertEquals(0f, LiquidMerge.linkAlpha(1f), 0.001f)
        assertEquals(1f, LiquidMerge.linkAlpha(0f), 0.001f)
        // 终点半径必须小于录制键半径，否则把按钮糊住而不是"吸进去"
        val rRec = 25f
        val blob = LiquidMerge.blobRadius(1f, 15f, rRec)
        assertTrue(blob < rRec)
        assertEquals(rRec * LiquidMerge.BLOB_TARGET_RATIO, blob, 0.001f)
    }

    // ---------- ④ #71 第一批第 2 件：位移放开到「条目中心 → 键心」，且全程落在收拢中的底板轮廓内 ----------

    /**
     * 纲换成那段实测圆心距本身（旧版是 `min(圆心距 × TRAVEL_FRACTION, clearance)`，底栏实测只有
     * 10.96dp / 13.28dp，那就是用户投诉的"只是原地淡化"的算术原因）。
     *
     * 红法：① 把 0.16 那套夹子写回来 → 前两条红（只剩 10.96/13.28）；② 进度不夹 0..1 → 第三、四条红
     * （LIQUID 弹簧过冲会把那颗甩过键心，反向甩出则冲出原位）；③ 取绝对值 → 第二条红。
     */
    @Test
    fun releasedTravelDeliversTheWholeGapToTheKeyCenter() {
        assertEquals("镜头那颗一步吸到键心", -68.5f, LiquidMerge.chipTravelPx(1f, -68.5f), 0.001f)
        assertEquals("缩略图那颗一步吸到键心", 83f, LiquidMerge.chipTravelPx(1f, 83f), 0.001f)
        assertEquals("中途线性、带符号", -34.25f, LiquidMerge.chipTravelPx(0.5f, -68.5f), 0.001f)
        assertEquals("正向过冲不许穿过键心", -68.5f, LiquidMerge.chipTravelPx(1.4f, -68.5f), 0.001f)
        assertEquals("反向过冲不许甩出原位", 0f, LiquidMerge.chipTravelPx(-0.4f, -68.5f), 0.001f)
        assertEquals("那颗本来就在键心时不位移", 0f, LiquidMerge.chipTravelPx(0.7f, 0f), 0.001f)
    }

    /**
     * 条目外缘与底板轮廓半宽的差（>0 = 露出轮廓）。中心位移与本体缩放都**直接调生产算式**
     * （[LiquidMerge.chipTravelPx] / [LiquidMerge.chipScale]），轮廓走 [DockShell.sidePx]，
     * 所以这不是"由定义推出的等式"，而是一条会因实现改动而红的行为断言。
     *
     * 三项对 p 都是线性的 ⇒「全程在内」⇔ 两个端点在内，端点账（100% 字体，dp）：
     * · p=0：`d + hw ≤ 布局盒半宽` ⇔ 68.5+31.5 = 100 ≤ 108、83+17 = 100 ≤ 108（承重的就是那 8dp 内边距）
     * · p=1：`0.72·hw ≤ 圆环半宽 23` ⇔ 镜头 22.68、缩略图 12.24（**靠 chipScale 在承重**）
     *
     * 红法：把位移改回 16% 那段夹子 → 循环里 p≥0.5 的采样全部红（那颗停在原地顶穿收拢中的轮廓）；
     * 把 `chipScale` 撤成恒 1 → 最后一条与 p=1 那一档红。这条用例也是"本批不给条目加 clip"的依据。
     */
    @Test
    fun releasedTravelKeepsChipsInsideTheCollapsingShell() {
        val chips = listOf(68.5f to 31.5f, 83f to 17f)   // 镜头那颗 / 缩略图那颗：离键心距离 + 位移轴半宽
        for ((delta, half) in chips) {
            var i = 0
            while (i <= 20) {
                val p = i / 20f
                val outside = edgeOutsideShellPx(delta, half, p, DockHalfStart, DockHalfEnd)
                assertTrue("离键心 ${delta}dp 的颗在 p=$p 露出轮廓 $outside dp", outside <= 0.001f)
                i++
            }
        }
        assertTrue(
            "终点帧的条目半宽（含 0.72 缩放）必须小于圆环半径，否则那颗顶出圆环",
            31.5f * LiquidMerge.chipScale(1f) < DockHalfEnd
        )
    }

    /**
     * 反面对照（留档，别下批以为"漏了个 clip"）：120% 字体那一档那颗有 70.8dp 宽（位移轴半宽 35.4），
     * 比 46dp 的圆环本身就宽，于是行程末段外缘会顶出收拢中的轮廓。露出量对 p 线性
     * （`72.4(1-p) + 35.4(1-0.28p) - (115.8 - 92.8p) = -8 + 10.488p`）⇒ p≈0.763 起为正，
     * 最坏（p=0.9）只有 1.44dp，而那一刻 `chipAlpha` 已经走到 0。
     * 定版是"不加裁切"，这条用例钉的就是"露出只发生在淡出末段、且不超过 1.5dp"这个已知边界：
     * 位移算式或淡出曲线被改坏（露出提前 / 变大）就红。
     */
    @Test
    fun largeFontLeakIsKnownAndOnlyInTheFadingTail() {
        val leakP = 0.77f
        val leak = edgeOutsideShellPx(72.4f, 35.4f, leakP, 115.8f, DockHalfEnd)
        assertTrue("末段才露，且不超过 1.5dp（实测 $leak）", leak in 0f..1.5f)
        assertTrue("露出那一帧本体淡出已经 ≤ 0.2", LiquidMerge.chipAlpha(leakP) <= 0.2f)
        // 100% 那一档的中段还在轮廓内 4dp 以上（上一条用例逐帧核过全程），这里钉一个对照数值
        assertTrue(edgeOutsideShellPx(68.5f, 31.5f, 0.5f, DockHalfStart, DockHalfEnd) < -4f)
    }

    @Test
    fun travelKeepsItsSignTowardTheButton() {
        // 左边那颗往右吸（正），右边那颗往左移（负）：取绝对值就会把镜头那颗反着推出去
        assertEquals(83f, LiquidMerge.chipTravelPx(1f, 83f), 0.01f)
        assertEquals(-68.5f, LiquidMerge.chipTravelPx(1f, -68.5f), 0.01f)
        assertEquals(0f, LiquidMerge.chipTravelPx(0f, 100f), 0.001f)
    }

    /** 条目外缘离收拢中的轮廓还有多少（正 = 露出）；中心与缩放与轮廓三条都走生产算式 */
    private fun edgeOutsideShellPx(deltaAbs: Float, chipHalfPx: Float, p: Float, dockHalfPx: Float, endHalfPx: Float): Float {
        val travel = LiquidMerge.chipTravelPx(p, deltaAbs)
        val center = deltaAbs - travel                       // 离键心还剩多少（体育场关于中心对称，取同侧幅度即可）
        val half = chipHalfPx * LiquidMerge.chipScale(p)     // 本体同帧在缩小
        val shellHalf = DockShell.sidePx(dockHalfPx, endHalfPx, p)
        return center + half - shellHalf
    }

    // ---------- ④·补 #71 第一批第 1 件：底板整枚收拢（长度缩减，短轴也贴合，全程合法体育场形） ----------

    /**
     * 半宽/半高的账（100% 字体、镜头开，全部由令牌推出，用一半的数值表达同一笔账）：
     * 布局盒 216×60 ⇒ 半宽 108、半高 30；终点是录制键**可见圆环** 46 ⇒ 半 23（不是 50 的命中盒）。
     *
     * 这条用例是"长度缩减 + 短轴同时贴合 + 没有椭圆帧"唯一能被静态核对的证据：
     * 红法①只缩长轴不缩短轴（第一版的错）→ 短轴那条单调断言红；
     * 红法②拿 `graphicsLayer.scaleX/scaleY` 非等比缩放代替自绘（第二版的错）→ 半径 = 短轴一半那条红；
     * 红法③终点写成 50（命中盒）→ 终点两轴相等那条虽然仍成立，但 [DockHalfEnd] 与令牌分叉，
     *   上面 `releasedTravelKeepsChipsInsideTheCollapsingShell` 的端点账立刻红（22.68 vs 25 那档）。
     */
    @Test
    fun shellCollapsesBothAxesIntoAStadiumNotAnEllipse() {
        var i = 0
        var previousW = Float.MAX_VALUE
        var previousH = Float.MAX_VALUE
        var stadiumFrames = 0
        while (i <= 20) {
            val p = i / 20f
            val w = DockShell.sidePx(DockHalfStart, DockHalfEnd, p)
            val h = DockShell.sidePx(DockHalfTop, DockHalfEnd, p)
            assertTrue("长轴单调收拢（p=$p）", w <= previousW + 0.0001f)
            assertTrue("短轴也必须收拢（p=$p）", h <= previousH + 0.0001f)
            previousW = w
            previousH = h
            assertEquals("半径恒等于短轴一半（描边因此全程不被压扁）", h, 2f * DockShell.cornerRadiusPx(w, h), 0.0001f)
            if (p < 1f) {
                assertTrue("到底之前长轴严格大于短轴（p=$p），平直段 = w−h 还在", w > h)
                stadiumFrames++
            }
            i++
        }
        assertEquals("终点两轴相等 ⇒ 那一帧是正圆", DockShell.sidePx(DockHalfStart, DockHalfEnd, 1f), DockShell.sidePx(DockHalfTop, DockHalfEnd, 1f), 0.0001f)
        assertTrue("中段要有足够帧数才谈得上「全程体育场」", stadiumFrames >= 15)
    }

    /** 进度夹在 0..1：弹簧过冲不许把轮廓收成负尺寸，也不许让它反向长大到超出布局盒 */
    @Test
    fun shellSideClampsOvershoot() {
        assertEquals(DockHalfEnd, DockShell.sidePx(DockHalfStart, DockHalfEnd, 1f), 0.0001f)
        assertEquals(DockHalfEnd, DockShell.sidePx(DockHalfStart, DockHalfEnd, 1.4f), 0.0001f)
        assertEquals(DockHalfStart, DockShell.sidePx(DockHalfStart, DockHalfEnd, -0.4f), 0.0001f)
        // 不夹的话 p=1.4 会把长轴算成 108 − 85×1.4 = −11（负尺寸什么都画不出来，等于底板消失）
        assertTrue("夹住之后必须是正数", DockShell.sidePx(DockHalfStart, DockHalfEnd, 1.4f) > 0f)
        assertEquals("收拢中的轮廓留在布局盒正中（中心 ≡ 键心 ≡ 可视水平中心）", 42.5f, DockShell.insetPx(DockHalfStart, DockHalfEnd), 0.0001f)
    }

    /**
     * PLAIN 档的底板没有中间帧：形状只由 [mergeProgressOf] 那条桥决定，于是直接是起点或终点那一档。
     * 红法：把桥里的 `if (plan.animated)` 删掉 → PLAIN 吃到 0.4f 这个中间值，四条断言全红。
     */
    @Test
    fun plainShellIsTerminalShapeNotAnIntermediateOne() {
        val plain = mergePlanFor(MotionMode.PLAIN)
        for (recording in listOf(true, false)) {
            val p = mergeProgressOf(plain, recording, 0.4f)
            val expectHalf = if (recording) DockHalfEnd else DockHalfStart
            assertEquals("PLAIN 的长轴只能是端点", expectHalf, DockShell.sidePx(DockHalfStart, DockHalfEnd, p), 0.0001f)
            assertEquals("PLAIN 的短轴只能是端点", if (recording) DockHalfEnd else DockHalfTop, DockShell.sidePx(DockHalfTop, DockHalfEnd, p), 0.0001f)
            assertEquals("PLAIN 的位移恒 0（那颗不飞）", 0f, chipTravelPxOf(plain, p, -68.5f), 0.0001f)
        }
    }


    // ---------- ⑤ 场景坐标换算（绘制层读的是相对宿主节点的位置） ----------

    @Test
    fun sceneConvertsWindowCoordsToLocalCenters() {
        val scene = MergeScene()
        scene.put(MergeScene.CANVAS, 200f, 60f, 218f, 60f)
        scene.put(MergeScene.THUMB, 208f, 68f, 34f, 34f)
        scene.put(MergeScene.LENS, 353f, 70f, 63f, 30f)
        scene.put(MergeScene.RECORD, 300f, 65f, 50f, 50f)
        // 中心换算：窗口坐标 + 半个尺寸 − 宿主原点
        assertEquals(208f + 17f - 200f, scene.cx(MergeScene.THUMB), 0.001f)
        assertEquals(68f + 17f - 60f, scene.cy(MergeScene.THUMB), 0.001f)
        // 半径取短边一半：胶囊 63×30 → 15，录制键 50×50 → 25
        assertEquals(15f, scene.radius(MergeScene.LENS), 0.001f)
        assertEquals(25f, scene.radius(MergeScene.RECORD), 0.001f)
        // （原来这里还钉过 `halfWidth` —— 位移轴半宽，它是 clearance 夹子的输入。#71 第一批把纲换成
        //  「条目中心到键心的距离」之后那个夹子与 halfWidth 一起删了，半宽的账改由
        //  `edgeOutsideShellPx` 直接用 dp 数值表达，见 `releasedTravelKeepsChipsInsideTheCollapsingShell`）
        // 那颗没组合时面积为 0：绘制层据此不画腰、底板也不留空壳
        scene.put(MergeScene.LENS, 0f, 0f, 0f, 0f)
        assertEquals(0f, scene.area(MergeScene.LENS), 0.001f)
        // S4-5：CANVAS 走的是自己回报的尺寸（218×60），**不是**借录制键那一份面积。
        // 现在没有调用点，但 B4 复用同一套几何时"看起来合理"的错值最难查，所以钉住
        assertEquals(218f * 60f, scene.area(MergeScene.CANVAS), 0.001f)
        assertEquals(50f * 50f, scene.area(MergeScene.RECORD), 0.001f)
        // CANVAS 的中心换算也自洽：宿主自己相对自己 = 宽的一半
        assertEquals(109f, scene.cx(MergeScene.CANVAS), 0.001f)
    }

    /**
     * 底板那枚 `clip` 撤掉之后（#71 第一批把 `wotaCard` 换成纯绘制的 [com.wotagei.cam.ui.anim.wotaDockShell]），
     * 腰（连通体）必须**自己**出不了布局盒，否则细胞分裂那几帧会画到底板外面。
     *
     * 布局盒 216×60 ⇒ 半宽 108、半高 30。腰的全部材料 = 原位圆 + 液滴圆 + 两条公切贝塞尔，
     * 而三颗的中心都在同一水平中线上（底栏的几何前提，第二条断言就在校这件事），所以轴是水平的、
     * 法向就是竖直的 ⇒ 横向最远 = 两圆心离盒心的最大距离 + 各自半径，纵向最远 = max(半径, |控制点偏移|)。
     * 三次贝塞尔不会超出控制点凸包，所以这个上界是**闭式**的，不用逐点采样曲线。
     *
     * 红法：`CUT_FACTOR`/`WAIST_EXPONENT`/`controlOffsetPx` 任一处被改大使腰鼓出去，或 `BLOB_TARGET_RATIO`
     * 被抬到让液滴比键还粗 → 上界超过 108/30；`blobX` 写反方向 → 第一条坐标前提红。
     */
    @Test
    fun linkCannotEscapeTheLayoutBoxNowThatTheClipIsGone() {
        val scene = MergeScene()
        scene.put(MergeScene.CANVAS, 0f, 0f, 216f, 60f)
        scene.put(MergeScene.THUMB, 8f, 13f, 34f, 34f)   // 左槽：内边距 8dp 起，垂直居中
        scene.put(MergeScene.LENS, 145f, 15f, 63f, 30f)  // 右槽：那颗填满 63dp 槽，垂直居中
        scene.put(MergeScene.RECORD, 83f, 5f, 50f, 50f)
        val rcx = scene.cx(MergeScene.RECORD)
        val rcy = scene.cy(MergeScene.RECORD)
        val rRec = scene.radius(MergeScene.RECORD)
        assertEquals("键心 ≡ 布局盒中心（不变量②，坐标前提就这一条）", 108f, rcx, 0.001f)
        for (target in listOf(MergeScene.THUMB, MergeScene.LENS)) {
            val hx = scene.cx(target)
            val hr = scene.radius(target)
            assertEquals("两颗中心与键心同一条水平中线", rcy, scene.cy(target), 0.001f)
            var i = 1
            while (i <= 20) {
                val p = i / 20f
                val bx = LiquidMerge.blobX(p, hx, rcx)
                val br = LiquidMerge.blobRadius(p, hr, rRec)
                val waist = LiquidMerge.waistRadiusPx(
                    abs(bx - hx), hr, br, LiquidMerge.cutDistancePx(hr, br)
                )
                val c = abs(LiquidMerge.controlOffsetPx(waist, hr, br))
                val farX = max(abs(hx - rcx) + hr, abs(bx - rcx) + br)
                val farY = max(max(hr, br), c)
                assertTrue("target=$target p=$p 腰横向最远 $farX 必须 ≤ 108", farX <= 108f + 0.001f)
                assertTrue("target=$target p=$p 腰纵向最远 $farY 必须 ≤ 30", farY <= 30f + 0.001f)
                i++
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownMergeTargetFailsLoud() {
        // 第五个槽位值只能是新增槽位时漏改分支；静默 fallback 到录制键那一档就是 S4-5 报的那个坑
        MergeScene().put(99, 0f, 0f, 10f, 10f)
    }

    @Test
    fun blobInterpolatesFromHomeToButton() {
        assertEquals(10f, LiquidMerge.blobX(0f, 10f, 110f), 0.001f)
        assertEquals(110f, LiquidMerge.blobX(1f, 10f, 110f), 0.001f)
        assertEquals(60f, LiquidMerge.blobX(0.5f, 10f, 110f), 0.001f)
        assertEquals(50f, LiquidMerge.blobY(0.4f, 50f, 50f), 0.001f)
        // 进度越界要夹住：动画状态在 PLAIN 档直接给 0/1，别处万一给了越界值也不能画到窗口外面去
        assertEquals(110f, LiquidMerge.blobX(2f, 10f, 110f), 0.001f)
        assertEquals(10f, LiquidMerge.blobX(-1f, 10f, 110f), 0.001f)
    }

    // ---------- ⑥ 常驻读数的换行档与高度档（六项第 4 条；#70 修复批次第 5 条起，这三条纯函数住在
    // `ui/HudMetrics.kt`，不在 `ui/anim/LiquidMerge.kt` 里——用例仍留在本文件这一组，只换 import） ----------

    /**
     * 用例入参必须与调用点**同一个表达式**算出来的可用宽（审查 S3-5）。
     * 调用点：`planReadoutRow(…)` → `readoutRoomBesideDockDp` / `hudRoomDp(safeW, HudEdgePad.value)`，
     * `safeW` 是套了 `safeDrawingPadding()` 之后那层的**实测宽**（本机横屏 766dp、竖屏 360dp），
     * `HudEdgePad` 是读数块 `padding(end=)` 那枚 8dp 设计留白（与左竖 Dock 起始边同一枚令牌）。
     * #68 之前这里喂的是 `hudRoomDp(screenWidthDp, VisibleEndInset)`：那笔 34dp 是按方向写死的假避让，
     * 竖屏也跟着扣（白退一档），横屏则与外层 safeDrawingPadding 重复扣一次。
     * 反面对照用 115%（1.15f）：直接喂窗口宽就把 8dp 留白与 24dp 估算余量一起吃掉，会多取一颗。
     *
     * ⚠ 这里测的是**裸档位函数** `hudPerRowFor`（能给到 3）。横屏实际落地的列数还要过
     * `DockRowPerRowCap` 那道上限（定版「一行两颗」），那一档由 `HudReadoutRowPlanTest` 钉。
     */
    @Test
    fun threePerRowNeedsSafetyMargin() {
        val landscape = hudRoomDp(766f, EdgePadDp)   // 746dp（本机横屏安全区实测宽 − 8 − 12）
        val portrait = hudRoomDp(360f, EdgePadDp)     // 340dp（竖屏侧边不让位，只扣留白与内边距）
        assertEquals(746f, landscape, 0.001f)
        assertEquals(340f, portrait, 0.001f)
        // 横屏：一行 3 颗（100% 282dp / 120% 336dp）+ 24dp 余量都装得下
        assertEquals(3, hudPerRowFor(3, 1f, landscape))
        assertEquals(3, hudPerRowFor(3, 1.2f, landscape))
        // 竖屏 100%：行宽 282 + 余量 24 = 306 ≤ 340 → 3 颗（块宽 294 + 留白 8 = 302 ≤ 360 安全区）
        assertEquals(3, hudPerRowFor(3, 1f, portrait))
        // 竖屏 110%：3×99=297 加两条行距 12 → 309，309 + 24 = 333 ≤ 340 → 仍取 3 颗（旧写法只剩 314，这一档白退一级）
        assertEquals(3, hudPerRowFor(3, 1.1f, portrait))
        // 竖屏 115%：3 颗要 322.5 + 24 = 346.5 > 340 → 退到 2 颗
        assertEquals(2, hudPerRowFor(3, 1.15f, portrait))
        assertEquals(2, hudPerRowFor(3, 1.2f, portrait))
        // 极窄（分屏 / 大字模式）：退到一行 1 颗，宁可高也不裁字
        assertEquals(1, hudPerRowFor(3, 1.2f, hudRoomDp(240f, EdgePadDp)))
        // 反面对照（留档 S3-5 的成因）：同一档字体、直接喂 screenWidthDp 就会错取 3 颗/行
        // （346.5 ≤ 360），块宽 334.5 + 留白 8 = 342.5 离 360 只剩 17.5dp——24dp 估算余量被吃光，
        // 而字宽是"汉字 1em / 拉丁 0.6em"的算术估计，偏一点就从右缘画出去
        assertEquals(3, hudPerRowFor(3, 1.15f, 360f))
    }

    @Test
    fun perRowNeverExceedsItemCount() {
        val landscape = hudRoomDp(766f, EdgePadDp)
        assertEquals(1, hudPerRowFor(1, 1f, landscape))
        assertEquals(2, hudPerRowFor(2, 1f, landscape))
        // 一颗都没开时返回 1（chunked(1) 对空表不产生行，perRow 只需保证 ≥1 不炸）
        assertEquals(1, hudPerRowFor(0, 1f, landscape))
    }

    /**
     * 读数块的高度档：这些就是 RightDock / LeftDock KDoc 里引用的那几个数，钉住它们是为了让
     * 注释里的"带高/露出颗数"算式与代码同源（改公式就必须同步改注释，不许注释一套实现一套）。
     * 44/72 是顶栏与底栏的**首帧兜底**，真值由实测回报接管（真机点验项）。
     */
    @Test
    fun readoutStripHeightsMatchTheDockBandAccount() {
        // 默认 3 读数、100%、横屏一行 3 颗 = 1 行 → 12 + 30 = 42dp
        assertEquals(42f, hudStripHeightDp(3, 3, 1f), 0.001f)
        // 满配 7 读数、横屏一行 3 颗 = 3 行 → 12 + 3×30 + 2×6 = 114dp
        assertEquals(114f, hudStripHeightDp(7, 3, 1f), 0.001f)
        // 满配 7 读数、竖屏 120% 一行 2 颗 = 4 行 → 12 + 4×33.6 + 3×6 = 164.4dp
        assertEquals(164.4f, hudStripHeightDp(7, 2, 1.2f), 0.05f)
        // 读数全关（且未锁 AE）时块高归 0：右 Dock 下边界那条缝要收回去
        assertEquals(0f, hudStripHeightDp(0, 3, 1f), 0.001f)

        // 横屏带高 = 360 − 顶栏 44 − 底栏 72 − 读数块高
        assertEquals(202f, 360f - TopBarDp - BottomBarDp - 42f, 0.001f)
        assertEquals(130f, 360f - TopBarDp - BottomBarDp - 114f, 0.001f)
        // 告警条（顶栏 66）叠加满配读数时带高仍为正：Dock 靠 verticalScroll 取用，不许是负数
        assertTrue(360f - 66f - BottomBarDp - 114f > 0f)
        // 读数块自己那条带：360 − 顶栏 44 − 底栏 72 = 244dp，最满的一档也装得下（不需要滚动就不裁字）
        assertTrue(hudStripHeightDp(7, 2, 1.2f) <= 360f - TopBarDp - BottomBarDp)
    }

    // ---------- ⑦ #73：取证钩子的上游覆盖 + 吸收期命中权交接 ----------

    /**
     * 钩子只能作为 [mergeProgressOf] 这条桥**上游**的一个可选覆盖。
     * 三条断言各钉一种退化：
     * - 钉值顶掉动画值 → 红在第一条（把 `pinned ?: animatedValue` 写反或忽略 pinned 就红）；
     * - 钩子不许漏进 PLAIN 档 → 红在第二条（把 [mergeProgressWithHook] 实现成
     *   `pinned ?: mergeProgressOf(...)`，PLAIN 也会出现中间态，S3-1 那条"确实消失"就白写了）；
     * - 默认路径（pinned = null）逐字不变 → 红在第三、四条（动画值与开关态两种喂法各一条）。
     */
    @Test
    fun hookOverrideEntersOnlyUpstreamOfTheBridge() {
        val fluent = mergePlanFor(MotionMode.FLUENT)
        val plain = mergePlanFor(MotionMode.PLAIN)
        // ① 动画档：钉值赢过动画当前值（哪怕录制态还是 false，画面也该停在半融）
        assertEquals(0.75f, mergeProgressWithHook(fluent, false, 0f, 0.75f), 0.0001f)
        assertEquals(0.5f, mergeProgressWithHook(fluent, true, 0.2f, 0.5f), 0.0001f)
        // ② PLAIN 档：桥自己的 `plan.animated` 分支必须把钉值一起吃掉
        assertEquals(0f, mergeProgressWithHook(plain, false, 0.3f, 0.9f), 0.0001f)
        assertEquals(1f, mergeProgressWithHook(plain, true, 0.3f, 0f), 0.0001f)
        // ③ 没钩子（null）时与不接钩子的写法逐字同一：动画值优先、没有动画值才退开关态
        assertEquals(0.2f, mergeProgressWithHook(fluent, true, 0.2f, null), 0.0001f)
        assertEquals(1f, mergeProgressWithHook(fluent, true, null, null), 0.0001f)
        assertEquals(0f, mergeProgressWithHook(fluent, false, null, null), 0.0001f)
        // ④ 钉 0 是合法档（两颗回原位、且不收点击），所以它不能被当成"没钉"
        assertEquals(0f, mergeProgressWithHook(fluent, false, 0.4f, 0f), 0.0001f)
    }

    /**
     * 吸收期那两颗**不可命中**的判据（#73 第 2 件第一刀）。
     *
     * 断的不是"alpha 到 0"而是"位移一开始就断"，这才是 #71b 敢把位移从 10.96dp 放开到 68.5dp 的前提：
     * 半融那一帧那颗明明还看得见（chipAlpha > 0），却已经点不动了。
     * 红法：把 `progress <= 0f` 改成 `chipAlpha(progress) > 0f` 之类"看得见了才收点击"的写法，
     * p=0.2 这一条立刻红；改成恒 `true`（今天的行为）也一样红。
     */
    @Test
    fun chipsStopAcceptingClicksAsSoonAsAbsorptionStarts() {
        assertTrue("进度 0 = 原位，正常收点击", chipClicksAccepted(0f))
        assertFalse("位移刚起步就不许再收点击", chipClicksAccepted(0.0001f))
        assertFalse("半融那一帧不可点（此时它视觉上还在：alpha 还没到 0）", chipClicksAccepted(0.2f))
        assertFalse("终点态也不可点（键正被两颗压着）", chipClicksAccepted(1f))
        // 与淡出曲线对照：0.2 处 alpha 还远大于 0，说明"不可点"早于"看不见"，不是同一条判据
        assertTrue(LiquidMerge.chipAlpha(0.2f) > 0.5f)
        assertTrue("越界值也当已吸收（动画收尾前的 overshoot 不许把点击放回来）", !chipClicksAccepted(1.4f))
        // 反向过冲（LIQUID 弹簧从 1 回到 0 会甩到负值）按"已回原位"对待：放行点击是安全的——
        // 那一刻两颗在原生位置**之外**（chipTravelPx 带符号，负进度只会把它推离录制键），不可能盖住键
        assertTrue("负过冲 = 已回到原位之外，恢复收点击", chipClicksAccepted(-0.01f))
    }

    /**
     * 底板那枚长按换栏探测器落在录制键上时必须**不接管**（#73 第 2 件第三刀）。
     *
     * 坐标系是"相对底板左上角"，所以用例特意把 CANVAS 原点挪到 (200, 60)：
     * 红法①把 `- canvasLeft` 那两笔减法删掉 → 落在键内那条断言红（窗口坐标被当本地坐标比）；
     * 红法②把"量不到就返回 false"那条守卫删掉 → 未测量那条断言红（首帧所有字段都是 0，
     *   (0,0) 会被算成"在键里"，于是首帧长按永远换不了栏）。
     */
    @Test
    fun longPressInsideTheRecordKeyIsLeftToTheButton() {
        val scene = MergeScene()
        scene.put(MergeScene.CANVAS, 200f, 60f, 216f, 60f)
        scene.put(MergeScene.RECORD, 300f, 65f, 50f, 50f)
        // 键在底板本地坐标里就是 [100,5]–[150,55]
        assertTrue("正中：这一指归键", dragOwnedByRecordKey(scene, 125f, 30f))
        assertTrue("左上角点上也算键内（边界含）", dragOwnedByRecordKey(scene, 100f, 5f))
        assertFalse("左边差一点：底板接管", dragOwnedByRecordKey(scene, 99f, 30f))
        assertFalse("下边差一点：底板接管", dragOwnedByRecordKey(scene, 125f, 56f))
        assertFalse("底板最左上的缩略图那一片：正常换栏入口", dragOwnedByRecordKey(scene, 20f, 30f))
        assertFalse("首帧还没量到键的矩形 → 退回旧行为（别凭空拒绝手势）", dragOwnedByRecordKey(MergeScene(), 0f, 0f))
    }

    // ---------- ⑧ #71 第二批：接触颈的端点必须跟着位移走（视觉圆心桥）+ 光感亮缘 ----------

    /**
     * 主测机（720×1600、density 2）100% 字体下底栏的**实测几何，px 为单位**。
     * 必须用 px：[LiquidMerge.MIN_WAIST_PX] / [LiquidMerge.MIN_DIST_PX] 两条门槛本来就是像素值，
     * 拿 dp 喂它们等于把门槛放大两倍，"颈到底画不画"的判定就假了。
     * 逐格对齐 HudLayer 那三格排布：布局盒 432×120、左槽 34dp 方框、右槽 63×30dp 胶囊、键 50dp。
     */
    private fun bottomScenePx(): MergeScene {
        val s = MergeScene()
        s.put(MergeScene.CANVAS, 0f, 0f, 432f, 120f)
        s.put(MergeScene.THUMB, 16f, 26f, 68f, 68f)
        s.put(MergeScene.LENS, 290f, 30f, 126f, 60f)
        s.put(MergeScene.RECORD, 166f, 10f, 100f, 100f)
        return s
    }

    /**
     * **本批承重的证明**：颈的端点是「布局圆心 + 同源位移」，不是布局圆心。
     *
     * 红法①（这条用例的存在理由）：把 [chipVisualCx] 退回 `scene.cx(target)` → 除 p=0 那一条之外全红
     * （p=0.5 时期望 283.25px 却拿到 353px）。
     * 红法②：把位移换成"重算第二份"（例如在桥里另写 `delta × p` 而不走 [chipTravelForScene]）
     * 而那颗那层仍走 plan 闸门 → PLAIN 那两条红。
     * 红法③：位移纲被改回 16% 夹子 → 终点态 `= 键心` 那条红（137 × 0.16 走不到 216）。
     */
    @Test
    fun neckEndpointFollowsTheDisplacementNotTheLayoutSlot() {
        val scene = bottomScenePx()
        val plan = mergePlanFor(MotionMode.FLUENT)
        val layoutCx = scene.cx(MergeScene.LENS)      // 353px（= 176.5dp，那颗离开之前的原位）
        val keyCx = scene.cx(MergeScene.RECORD)       // 216px
        assertEquals(353f, layoutCx, 0.001f)
        assertEquals(216f, keyCx, 0.001f)
        // 逐档钉死视觉圆心：p=0 还在原位，之后线性落到键心
        assertEquals("p=0 不动", layoutCx, chipVisualCx(scene, MergeScene.LENS, plan, 0f), 0.001f)
        assertEquals(284.5f, chipVisualCx(scene, MergeScene.LENS, plan, 0.5f), 0.001f)
        assertEquals(243.4f, chipVisualCx(scene, MergeScene.LENS, plan, 0.8f), 0.001f)
        assertEquals("p=1 那颗的中心正好落在键心", keyCx, chipVisualCx(scene, MergeScene.LENS, plan, 1f), 0.001f)
        // 任何 p>0 都不许等于布局圆心（这就是"退回 scene.cx 必红"的那一条）
        var i = 1
        while (i <= 20) {
            val p = i / 20f
            assertTrue("p=$p 时颈的端点还钉在原位 $layoutCx", chipVisualCx(scene, MergeScene.LENS, plan, p) != layoutCx)
            // 圆心距按 (1−p) 线性塌缩：68.5dp → 0
            assertEquals(
                "p=$p 的视觉圆心距", (1f - p) * 137f,
                chipVisualDistToKeyPx(scene, MergeScene.LENS, plan, p), 0.001f
            )
            i++
        }
        // 左边的缩略图那颗带符号：它在键的**左侧**，位移是正方向
        assertEquals(50f, chipVisualCx(scene, MergeScene.THUMB, plan, 0f), 0.001f)
        assertEquals(133f, chipVisualCx(scene, MergeScene.THUMB, plan, 0.5f), 0.001f)
        assertEquals(216f, chipVisualCx(scene, MergeScene.THUMB, plan, 1f), 0.001f)
        // 桥与那颗那层 graphicsLayer 吃的是**同一个数**（同源，不许两份算式）
        for (p in listOf(0f, 0.3f, 0.5f, 0.72f, 0.9f, 1f)) {
            assertEquals(
                "p=$p 位移与视觉圆心必须同源",
                chipTravelForScene(scene, MergeScene.LENS, plan, p),
                chipVisualCx(scene, MergeScene.LENS, plan, p) - layoutCx,
                0.0001f
            )
        }
    }

    /**
     * PLAIN 档：桥本身不吃中间态（位移恒 0 ⇒ 视觉圆心恒等于布局圆心），
     * 且 [neckVisibleFor] 恒 false ⇒ 绘制层一条路径都不描。
     * 红法：把 [chipTravelForScene] 里的 `chipTravelPxOf` 换成裸 `LiquidMerge.chipTravelPx`（绕过 plan 闸门）
     * → 前两条红；把 [waistVisibleFor] 的 `plan.drawWaist` 删掉 → 后两条红。
     */
    @Test
    fun plainModeHasNoNeckAndNoVisualDisplacement() {
        val scene = bottomScenePx()
        val plain = mergePlanFor(MotionMode.PLAIN)
        for (recording in listOf(true, false)) {
            val p = mergeProgressOf(plain, recording, 0.75f)
            for (target in listOf(MergeScene.THUMB, MergeScene.LENS)) {
                assertEquals(
                    "PLAIN 的视觉圆心 == 布局圆心（recording=$recording）",
                    scene.cx(target), chipVisualCx(scene, target, plain, p), 0.0001f
                )
                assertFalse("PLAIN 任何档都不长颈", neckVisibleFor(plain, scene, target, p))
            }
        }
        // 半径那条桥故意不吃 plan（PLAIN 的进度只有 0/1，缩到位那一档本体 alpha 也已经是 0）：
        // 这里钉的是"桥与那颗那层 graphicsLayer 用同一个 chipScale"，两个数值都是独立字面量
        assertEquals(30f, chipVisualRadiusPx(scene, MergeScene.LENS, 0f), 0.001f)
        assertEquals("录制态那一档那颗缩到 0.72", 21.6f, chipVisualRadiusPx(scene, MergeScene.LENS, 1f), 0.001f)
    }

    /**
     * 颈的端点半径 = 那颗**缩之后**的实体半径。
     * 红法：把 `× chipScale(progress)` 删掉（直接用 `scene.radius`）→ 后两条红，
     * 而画面上是"颈的帽比那颗本身还粗"，从键里糊出一圈没有来路的暗盘。
     */
    @Test
    fun neckRadiusIsTheShrunkenBody() {
        val scene = bottomScenePx()
        assertEquals(30f, chipVisualRadiusPx(scene, MergeScene.LENS, 0f), 0.001f)
        assertEquals(30f * LiquidMerge.chipScale(0.5f), chipVisualRadiusPx(scene, MergeScene.LENS, 0.5f), 0.001f)
        assertEquals("终点缩到 0.72 档", 21.6f, chipVisualRadiusPx(scene, MergeScene.LENS, 1f), 0.001f)
        assertTrue("中途必须严格小于布局半径", chipVisualRadiusPx(scene, MergeScene.LENS, 0.8f) < 30f)
        assertEquals(34f, chipVisualRadiusPx(scene, MergeScene.THUMB, 0f), 0.001f)
        assertEquals(24.48f, chipVisualRadiusPx(scene, MergeScene.THUMB, 1f), 0.001f)
        // 那颗没组合（面积 0）⇒ 半径 0 ⇒ 颈无从长起
        val noLens = bottomScenePx().apply { put(MergeScene.LENS, 0f, 0f, 0f, 0f) }
        assertEquals(0f, chipVisualRadiusPx(noLens, MergeScene.LENS, 0.8f), 0.001f)
        assertFalse("没组合的那颗不许长颈", neckVisibleFor(mergePlanFor(MotionMode.FLUENT), noLens, MergeScene.LENS, 0.8f))
    }

    /**
     * 两条**独立写法**必须落在同一个点上：`blobX`（从插值出发）与 [chipVisualCx]（从"布局圆心 + 同源位移"出发）。
     * 这条不是恒等式——两边各有一条自己的算式，任一侧单边改错（插值方向写反、纲换掉、闸门漏挂）就红。
     */
    @Test
    fun visualCenterIsTheSamePointAsTheBlobPath() {
        val scene = bottomScenePx()
        val plan = mergePlanFor(MotionMode.FLUENT)
        for (target in listOf(MergeScene.THUMB, MergeScene.LENS)) {
            val home = scene.cx(target)
            val key = scene.cx(MergeScene.RECORD)
            var i = 0
            while (i <= 25) {
                val p = i / 25f
                assertEquals(
                    "target=$target p=$p",
                    LiquidMerge.blobX(p, home, key),
                    chipVisualCx(scene, target, plan, p),
                    0.001f
                )
                i++
            }
        }
    }

    /**
     * **旧的假颈确实存在过，现在被消除**（这条是本批"脱节"症状的回归锁）。
     *
     * 旧写法在同一张 Path 上追加两段：`布局原位圆 ↔ 液滴` 与 `液滴 ↔ 键`。第一段把端点钉在布局原位，
     * 而那颗已经跟着 translationX 飞走 —— 于是 p 小的那一段（那颗才走了两三成路）在原位留出一截
     * 画得出来的圆帽 + 尾巴。这里用生产算式把这截**量化**出来（p=0.2 时腰宽 6.7px，完全看得见），
     * 再断言新写法的两段并成一段：条目那一端与那颗的视觉位置重合 ⇒ 长度 0 ⇒ `linkVisible` 拒绝。
     * 红法：把 `appendChipLink` 改回两段（第一段喂 `scene.cx`）→ 第一条断言就从"证据"变成"缺陷"，
     * 但真正会红的是 [neckEndpointFollowsTheDisplacementNotTheLayoutSlot]；这条负责说明为什么要改。
     */
    @Test
    fun phantomTailAtTheLayoutSlotIsEliminated() {
        val scene = bottomScenePx()
        val plan = mergePlanFor(MotionMode.FLUENT)
        val home = scene.cx(MergeScene.LENS)
        val key = scene.cx(MergeScene.RECORD)
        val p = 0.2f
        // 旧的第一段：布局原位 ↔ 液滴（液滴 = 那颗此刻的视觉位置）
        val phantomLen = abs(LiquidMerge.blobX(p, home, key) - home)
        assertEquals("那颗才走了 27.4px", 27.4f, phantomLen, 0.01f)
        val phantomWaist = LiquidMerge.waistRadiusPx(
            phantomLen, scene.radius(MergeScene.LENS),
            LiquidMerge.blobRadius(p, scene.radius(MergeScene.LENS), scene.radius(MergeScene.RECORD)),
            LiquidMerge.cutDistancePx(
                scene.radius(MergeScene.LENS),
                LiquidMerge.blobRadius(p, scene.radius(MergeScene.LENS), scene.radius(MergeScene.RECORD))
            )
        )
        assertTrue("p=0.2 时那一截假颈腰宽 $phantomWaist px 是真画得出来的（>2px）", phantomWaist > 2f)
        // 新写法：颈的条目端 == 那颗的视觉位置 ⇒ 没有"原位↔液滴"这一段
        assertEquals(0f, abs(chipVisualCx(scene, MergeScene.LENS, plan, p) - LiquidMerge.blobX(p, home, key)), 0.001f)
        assertFalse(
            "同一点 ⇒ MIN_DIST_PX 挡掉，不再有任何留在原位的几何",
            LiquidMerge.linkVisible(0f, chipVisualRadiusPx(scene, MergeScene.LENS, p), scene.radius(MergeScene.RECORD))
        )
    }

    /**
     * **颈真的会长出来，一个常数都没抬**（`CUT_FACTOR = 0.7` 原值）。
     *
     * 位移纲放开之后，视觉圆心距从 137px 线性塌到 0，而阈值 `cut = 0.7 × (rA + 50px)` 本身也随那颗缩小
     * 轻微收窄（rA = 30px × chipScale），两者在 p ≈ 0.643 交叉 —— 那之后颈逐帧变粗。
     * 红法：把位移改回 16% 夹子 → 视觉圆心距最低只到 115px，全程 > cut ⇒ 四条断言全红
     * （这就是"第一批放开位移把这条死路径自己盘活"的那句验收）。
     */
    @Test
    fun neckAppearsOnItsOwnOnceTravelIsReleased() {
        val scene = bottomScenePx()
        val plan = mergePlanFor(MotionMode.FLUENT)
        assertFalse("p=0.5 还没有颈", neckVisibleFor(plan, scene, MergeScene.LENS, 0.5f))
        assertFalse("p=0.60 还差一点", neckVisibleFor(plan, scene, MergeScene.LENS, 0.60f))
        assertTrue("p=0.65 颈刚长出来（腰已过 0.6px 门槛）", neckVisibleFor(plan, scene, MergeScene.LENS, 0.65f))
        assertTrue("p=0.80 颈在变粗", neckVisibleFor(plan, scene, MergeScene.LENS, 0.80f))
        assertTrue("p=0.90 颈最粗（整体正在淡出）", neckVisibleFor(plan, scene, MergeScene.LENS, 0.90f))
        // 交叉点必须在行程后段、且在淡出起点之前，否则肉眼窗口太短
        var cross = 1f
        var k = 0
        while (k <= 200) {
            val p = k / 200f
            if (neckVisibleFor(plan, scene, MergeScene.LENS, p)) { cross = p; break }
            k++
        }
        assertTrue("颈的起点 p=$cross 应落在 0.55~0.72（淡出从 LINK_FADE_FROM=${LiquidMerge.LINK_FADE_FROM} 起）",
            cross in 0.55f..0.72f)
        // 缩略图那颗更远（166px）但半径更大 ⇒ 颈起点略晚，同样不需要抬常数
        assertFalse(neckVisibleFor(plan, scene, MergeScene.THUMB, 0.6f))
        assertTrue(neckVisibleFor(plan, scene, MergeScene.THUMB, 0.75f))
    }

    /**
     * 颈可见的**行程换算成毫秒**（FLUENT = `tween(WotaMotion.COMMIT_MS, LinearOutSlowInEasing)`）。
     * 判据：这一窗口的时长。太短就等于没有，跟阈值交叉在 p 的哪一档无关。
     *
     * 用库里的缓动函数把 p 反对回去（不是自己抄一条曲线），所以这条同时钉住
     * 「时长只从令牌取」那一条：红法——把 `MotionSpec.float` 的时长换小，窗口跟着缩就红。
     */
    @Test
    fun neckVisibleWindowIsLongEnoughToSee() {
        val scene = bottomScenePx()
        val plan = mergePlanFor(MotionMode.FLUENT)
        val steps = 2000
        var visible = 0
        var firstT = -1f
        var lastT = -1f
        for (n in 0..steps) {
            val t = n / steps.toFloat()
            val p = androidx.compose.animation.core.LinearOutSlowInEasing.transform(t)
            if (neckVisibleFor(plan, scene, MergeScene.LENS, p)) {
                visible++
                if (firstT < 0f) firstT = t
                lastT = t
            }
        }
        val ms = visible * WotaMotion.COMMIT_MS / steps
        assertTrue("颈可见窗口只有 $ms ms（${firstT}~${lastT} 段），低于 350ms 就肉眼不可用", ms >= 350)
        assertTrue("窗口要覆盖动画后半段（firstT=$firstT）", firstT in 0.20f..0.45f)
    }

    /**
     * 颈按**收拢中的轮廓**逐帧不出界（旧 clip 撤掉之后唯一的那道账，本批改了端点口径就要重算）。
     * 条目那一端：视觉圆心距 + 视觉半径 ≤ 本帧底板轮廓半宽。
     * ⚠ 键那一端的帽半径恒等于键自己那条圆（50px > 收拢后的半高 46px），它不在这笔账里，
     *   因为录制键以 `RecordZIndex` 后画、正好把它压住——这条事实由真机点验，不由本用例。
     */
    @Test
    fun neckStaysInsideTheCollapsingShell() {
        val scene = bottomScenePx()
        val plan = mergePlanFor(MotionMode.FLUENT)
        val shellHalfStart = 216f   // 布局盒半宽 432/2
        val shellHalfEnd = 46f      // 终点 = recordRing 46dp 的半宽（23dp × 2）
        var i = 0
        while (i <= 40) {
            val p = i / 40f
            val shellHalf = DockShell.sidePx(shellHalfStart, shellHalfEnd, p)
            for (target in listOf(MergeScene.THUMB, MergeScene.LENS)) {
                if (!neckVisibleFor(plan, scene, target, p)) continue
                val outside = chipVisualDistToKeyPx(scene, target, plan, p) +
                    chipVisualRadiusPx(scene, target, p) - shellHalf
                assertTrue("$target 在 p=$p 的颈露出收拢中的轮廓 $outside px", outside <= 0.001f)
            }
            i++
        }
        // 最紧的一档就是颈刚长出来那一刻（p≈0.65），这里把它和轮廓的余量钉住：余量突然变小 = 端点口径被改坏
        val tight = chipVisualDistToKeyPx(scene, MergeScene.LENS, plan, 0.65f) +
            chipVisualRadiusPx(scene, MergeScene.LENS, 0.65f) -
            DockShell.sidePx(shellHalfStart, shellHalfEnd, 0.65f)
        assertTrue("刚长出颈时还要有 20px 以上余量（实测 $tight）", tight < -20f)
    }

    /**
     * 光感：亮缘半径与峰值档是**两枚独立字面量**，乘回去必须正好落在录制键自己的圆周（接触圈）。
     * 红法：只改 [LiquidMerge.NECK_GLOW_SPAN]（半径变长）或只改 [LiquidMerge.NECK_GLOW_PEAK_STOP]
     * （峰值那一档挪走）→ 乘积偏离键半径 >0.5% 即红。写成 `1f / span` 的派生式就测不出任何东西，
     * 所以故意不派生（AGENTS「恒等式不算证明」）。
     */
    @Test
    fun neckGlowPeakSitsOnTheKeyCircle() {
        val rRec = 50f
        val radius = LiquidMerge.neckGlowRadiusPx(rRec)
        assertTrue("跨度必须大于键半径，否则外缘连那颗露出的月牙都盖不到", radius > rRec)
        assertEquals("峰值档乘回半径 = 键半径", rRec, radius * LiquidMerge.NECK_GLOW_PEAK_STOP, rRec * 0.005f)
        assertTrue("峰值档必须落在 0..1 之间", LiquidMerge.NECK_GLOW_PEAK_STOP in 0f..1f)
        assertEquals(70f, radius, 0.001f)
        // 键没量到（半径 0）时半径归 0，绘制层本来就在第一条 return，不许凭空起一枚亮斑
        assertEquals(0f, LiquidMerge.neckGlowRadiusPx(0f), 0.001f)
    }

    private companion object {
        const val RecordPx = 50f  // WotaHit.recordTouch
        const val GapPx = 12f     // WotaSpace.m
        const val PadPx = 8f      // WotaSpace.s
        const val EdgePadDp = 8f   // HudLayer.HudEdgePad（WotaSpace.s）：读数块 padding(end=) 那枚设计留白
        const val TopBarDp = 44f   // CameraScreen.TopBarSpace 首帧兜底
        const val BottomBarDp = 72f // CameraScreen.BottomBarSpaceFallback（50 + 5×2 + 6×2）

        // #71 第一批的底板收拢账（**半宽/半高**表达，100% 字体、镜头开，全部由令牌推出）：
        // 布局盒 216×60 ⇒ 半宽 108、半高 30；终点是录制键可见圆环 WotaHit.recordRing 46 ⇒ 半 23
        // （不是 50 的命中盒 recordTouch——那是命中区不是形状）。见 DockShell 的 KDoc。
        const val DockHalfStart = 108f
        const val DockHalfTop = 30f
        const val DockHalfEnd = 23f
    }
}
