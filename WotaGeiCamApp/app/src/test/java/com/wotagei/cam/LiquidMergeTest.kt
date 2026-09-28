package com.wotagei.cam

import com.wotagei.cam.ui.anim.LiquidMerge
import com.wotagei.cam.ui.anim.MergePlan
import com.wotagei.cam.ui.anim.MergeScene
import com.wotagei.cam.ui.anim.MergeSlot
import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.anim.chipTravelPxOf
import com.wotagei.cam.ui.anim.hudPerRowFor
import com.wotagei.cam.ui.anim.hudRoomDp
import com.wotagei.cam.ui.anim.hudStripHeightDp
import com.wotagei.cam.ui.anim.linkAlphaFor
import com.wotagei.cam.ui.anim.mergePlanFor
import com.wotagei.cam.ui.anim.mergeProgressOf
import com.wotagei.cam.ui.anim.waistVisibleFor
import com.wotagei.cam.ui.design.WotaMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // 位移：中间态进度下仍然是 0
        assertEquals(0f, chipTravelPxOf(plain, 0.5f, 68.5f, 12f), 0.001f)
        assertEquals(0f, chipTravelPxOf(plain, 1f, -68.5f, 12f), 0.001f)
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
            // 位移带符号且被实测空隙夹住：want = −68.5 × 0.16 = −10.96，夹到 −12 之内不触发，
            // 再乘进度 0.25 ⇒ −2.74（负号 = 往左吸向录制键）
            assertEquals("$mode 位移应为负（往录制键吸）", -2.74f, chipTravelPxOf(plan, 0.25f, -68.5f, 12f), 0.02f)
            // 夹住的分支：按圆心距取比例已经越过空隙时必须被 clearance 截住（120% 那一档就是这种）
            assertEquals("$mode 位移被实测空隙夹住", -12f, chipTravelPxOf(plan, 1f, -100f, 12f), 0.02f)
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

    // ---------- ④ 录制态位移的安全余量（不能被那颗吃掉"停止录制"的点击） ----------

    /**
     * 底栏真实几何（100% 字体缩放，用 dp 数值表达，比例与真机一致）：
     * 底板 216 = 内边距 8 + 左槽 63 + 间距 12 + 录制键 50 + 间距 12 + 右槽 63 + 内边距 8。
     * 缩略图 34dp 贴左槽起始边 ⇒ 占 [8, 42]；录制键触摸盒 = [83, 133]；镜头那颗填满右槽 ⇒ 占 [145, 208]。
     * 两颗的近缘与触摸盒之间只剩 41dp 与 **12dp**——位移必须被它夹住：那颗淡出之后命中区还在
     * （alpha = 0 仍可点），越界就把"停止录制"那一指吃掉。
     */
    @Test
    fun travelIsCappedByMeasuredClearance() {
        val thumbClear = LiquidMerge.clearancePx(25f, 17f, 108f, 25f)
        val lensClear = LiquidMerge.clearancePx(176.5f, 31.5f, 108f, 25f)
        assertEquals(41f, thumbClear, 0.001f)
        assertEquals(12f, lensClear, 0.001f)
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val thumbEdge = 42f + LiquidMerge.chipTravelPx(p, 83f, thumbClear)
            val lensEdge = 145f + LiquidMerge.chipTravelPx(p, -68.5f, lensClear)
            assertTrue("p=$p 缩略图近缘 $thumbEdge 必须 ≤ 83", thumbEdge <= 83f + 0.001f)
            assertTrue("p=$p 镜头近缘 $lensEdge 必须 ≥ 133", lensEdge >= 133f - 0.001f)
        }
    }

    @Test
    fun narrowClearanceWinsOverFraction() {
        // 120% 字体缩放：槽宽 63→70.8、那颗半宽 31.5→35.4，触摸盒右缘 140.8，右槽左缘只剩 144.4
        // ⇒ 空隙 3.6dp。按圆心距取比例会算出 −10.24dp，必须被夹到 −3.6dp：clearance 才是主约束
        val clear = LiquidMerge.clearancePx(179.8f, 35.4f, 115.8f, 25f)
        assertEquals(3.6f, clear, 0.01f)
        assertEquals(-3.6f, LiquidMerge.chipTravelPx(1f, 115.8f - 179.8f, clear), 0.01f)
        // 空隙算出来 ≤0（本来就重叠）时位移必须为 0：宁可不 animate，也不盖住录制键
        assertEquals(0f, LiquidMerge.chipTravelPx(1f, 60f, 0f), 0.001f)
        assertEquals(0f, LiquidMerge.chipTravelPx(1f, 60f, -8f), 0.001f)
    }

    @Test
    fun travelKeepsItsSignTowardTheButton() {
        // 左边那颗往右吸（正），右边那颗往左移（负）：取绝对值就会把镜头那颗反着推出去
        assertEquals(13.28f, LiquidMerge.chipTravelPx(1f, 83f, 41f), 0.01f)
        assertEquals(-10.96f, LiquidMerge.chipTravelPx(1f, -68.5f, 41f), 0.01f)
        assertEquals(0f, LiquidMerge.chipTravelPx(0f, 100f, 40f), 0.001f)
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
        // 位移轴上的半宽取的是**宽**的一半（31.5 而不是 15）：算命中区余量必须用它，
        // 用内切圆半径会把空隙高估一倍以上，正是第一次把位移算漏的原因
        assertEquals(31.5f, scene.halfWidth(MergeScene.LENS), 0.001f)
        assertEquals(25f, scene.halfWidth(MergeScene.RECORD), 0.001f)
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

    // ---------- ⑥ 常驻读数搬到录制键右侧后的换行档与高度档（六项第 4 条） ----------

    /**
     * 用例入参必须与调用点**同一个表达式**算出来的可用宽（审查 S3-5）。
     * 调用点：`hudPerRowFor(hudItems.size, fontScale, hudRoomDp(screenWidthDp, VisibleEndInset))`。
     * 判据本该是「3 颗的行宽 ≤ 窗口宽 − 右缘避让 34 − 块内边距 12」，旧写法比的是「行宽 + 24 ≤ 窗口宽」，
     * 少扣 22dp。反面对照用 115%（1.15f）：120% 在 360dp 窗口上只是**恰好**被判到 2 颗——
     * `3×1.2f` 换算成 Float 是 108.00000763，三颗加行距加余量得 360.00003 > 360，
     * 差 4×10⁻⁵ dp 才没取到 3 颗，那是浮点舍入给的运气，不是设计。
     */
    @Test
    fun threePerRowNeedsSafetyMargin() {
        val landscape = hudRoomDp(800f, EndInsetDp)   // 754dp
        val portrait = hudRoomDp(360f, EndInsetDp)    // 314dp
        assertEquals(754f, landscape, 0.001f)
        assertEquals(314f, portrait, 0.001f)
        // 横屏：一行 3 颗（100% 282dp / 120% 336dp）+ 24dp 余量都装得下
        assertEquals(3, hudPerRowFor(3, 1f, landscape))
        assertEquals(3, hudPerRowFor(3, 1.2f, landscape))
        // 竖屏 100%：306 ≤ 314 → 3 颗（块宽 294dp，右缘正好让开不可视带）
        assertEquals(3, hudPerRowFor(3, 1f, portrait))
        // 竖屏 115%：3 颗要 346.5dp 的位（行宽 322.5 + 余量），可用只有 314 → 退到 2 颗
        assertEquals(2, hudPerRowFor(3, 1.15f, portrait))
        assertEquals(2, hudPerRowFor(3, 1.2f, portrait))
        // 极窄（分屏 / 大字模式）：退到一行 1 颗，宁可高也不裁字
        assertEquals(1, hudPerRowFor(3, 1.2f, hudRoomDp(240f, EndInsetDp)))
        // 反面对照（留档 S3-5 的成因）：同一档字体、直接喂 screenWidthDp 就会错取 3 颗/行，
        // 块宽 322.5 + 12 内边距 + 34 让位 = 368.5dp > 360dp，最右那颗的副标签被裁
        assertEquals(3, hudPerRowFor(3, 1.15f, 360f))
    }

    @Test
    fun perRowNeverExceedsItemCount() {
        val landscape = hudRoomDp(800f, EndInsetDp)
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

    private companion object {
        const val RecordPx = 50f  // WotaHit.recordTouch
        const val GapPx = 12f     // WotaSpace.m
        const val PadPx = 8f      // WotaSpace.s
        const val EndInsetDp = 34f // CameraScreen.VisibleEndInset（§58 实测的横屏右缘不可视带）
        const val TopBarDp = 44f   // CameraScreen.TopBarSpace 首帧兜底
        const val BottomBarDp = 72f // CameraScreen.BottomBarSpaceFallback（50 + 5×2 + 6×2）
    }
}
