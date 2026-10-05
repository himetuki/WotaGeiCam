package com.wotagei.cam.player

import com.wotagei.cam.player.CompareDomain.DUAL
import com.wotagei.cam.player.CompareDomain.LEFT_SOLO
import com.wotagei.cam.player.CompareDomain.RIGHT_SOLO
import com.wotagei.cam.player.CompareDomain.TIMELINE_END
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双轨时间线内核的行为级表驱动单测（JVM，纯函数直打）。
 *
 * 场景对照（对比播放 PR 式重构任务书）：
 * - 三场景交接连续（pre-roll→双素材、双素材→尾段两向）——交接不 seek，只换 pause/黑层/主钟；
 * - 双覆盖软追赶共存（250ms 硬 seek / 邻域 nudge，公式与旧循环逐字一致）；
 * - AB 回绕（abWrapHook 管回绕，decide 不抢）；
 * - 时间线末回绕/停、ENDED 后不自动重启（循环关时重播是 setPlaying 的点播放路径）；
 * - pre-roll seek/park 目标；
 * - 拖动让路（压 seek/nudge/wrap，素材域 pause/黑层照做）；
 * - park 在覆盖边界不误判双素材域（黑层回灌消歧 + 素材尽头 park 到 durMs）；
 * - off=0 回归红线：Tmin=0、Tmax=max(L,R)，纠偏期望位=T、回绕目标=(0,0)，与旧单左轴观感一致。
 *
 * 红绿纪律：decide/domainOf/resolveT 每个分支都被下面的断言点名，删任一分支必红
 * （已做突变自证：注释掉 pre-roll 入口分支 / wrap 分支各跑一轮，对应用例转红）。
 */
class CompareTimelineTest {

    // ------------------------------------------------------------------ 工具

    /** 一拍输入的默认底座：L=10s、R=20s、off=0，双素材域中段、在播、不拖动、无在册黑层 */
    private fun input(
        geo: TimelineGeometry = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 0L),
        leftLive: Long = 5_000L,
        rightLive: Long = 5_000L + geo.offsetMs,
        userPlaying: Boolean = true,
        abLoop: Boolean = false,
        timelineLoop: Boolean = true,
        dragging: Boolean = false,
        blackLeft: Boolean = false,
        blackRight: Boolean = false,
        hardResyncMs: Long = 250L
    ) = DecideInput(
        geo = geo,
        leftLiveMs = leftLive,
        rightLiveMs = rightLive,
        userPlaying = userPlaying,
        abLoopActive = abLoop,
        timelineLoop = timelineLoop,
        dragging = dragging,
        blackLeft = blackLeft,
        blackRight = blackRight,
        hardResyncMs = hardResyncMs
    )

    // ------------------------------------------------------------------ 几何

    @Test
    fun `off0 回归红线_Tmin0_Tmax取两片大者`() {
        // off=0：Tmin=0、Tmax=max(L,R)；进度条/读数第三格数值与旧单左轴口径衔接
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 6_000L, offsetMs = 0L)
        assertEquals(0L, g.tMin)
        assertEquals(10_000L, g.tMax)
        val g2 = TimelineGeometry(leftDurMs = 4_000L, rightDurMs = 9_000L, offsetMs = 0L)
        assertEquals(0L, g2.tMin)
        assertEquals(9_000L, g2.tMax)
        // 右片没选（R=0）：Tmax=L，与旧版完全一致
        val g0 = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 0L, offsetMs = 0L)
        assertEquals(10_000L, g0.tMax)
    }

    @Test
    fun `几何_pre-roll与尾段的park落点`() {
        // off>0：T<0 时左 park 到 0、右在素材域内即 T+off；越过右尾 park 到素材尽头（ENDED 停末帧）
        val pre = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 5_000L)
        assertEquals(-5_000L, pre.tMin)
        assertEquals(15_000L, pre.tMax)
        assertEquals(0L, pre.leftTarget(-2_000L))
        assertEquals(3_000L, pre.rightTarget(-2_000L))
        assertEquals(0L, pre.leftTarget(0L))
        assertEquals(5_000L, pre.rightTarget(0L))
        // T 越过左尾（10s）而右还在（右素材段 [−5000, 15000) 覆盖 12000）：左 park 到 durMs
        // （不是 dur−1：dur−1 仍落在覆盖判定区间内，下一拍会被误判成双素材域引来纠偏 seek，
        // 见内核类注释），右照常落素材位 T+off
        assertEquals(10_000L, pre.leftTarget(12_000L))
        assertEquals(17_000L, pre.rightTarget(12_000L))
        // off<0：右素材段 [3000, 23000)、左 [0, 10000)，Tmin=0（min(0, −off)=0，左头）
        val neg = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = -3_000L)
        assertEquals(0L, neg.tMin)
        assertEquals(23_000L, neg.tMax)
        assertEquals(2_500L, neg.leftTarget(2_500L))
        assertEquals(0L, neg.rightTarget(2_500L))
        assertEquals(0L, neg.rightTarget(3_000L))
        // T 越过左尾（10000）而右还在：左 park 到 durMs、右照常落素材位 T+off
        assertEquals(10_000L, neg.leftTarget(13_500L))
        assertEquals(10_500L, neg.rightTarget(13_500L))
    }

    // ------------------------------------------------------------------ 域判定

    @Test
    fun `域判定_pre-roll入口与黑层在册消歧`() {
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 5_000L)
        // 无在册黑层：右钟报出 T<0 也认得 pre-roll（安全网）
        assertEquals(RIGHT_SOLO, CompareTimeline.domainOf(g, leftLiveMs = 0L, rightLiveMs = 4_900L, false, false))
        // 在册右独播：左停在覆盖边界 0 是 park 不是双素材域——没有黑层回灌位这里会误判 DUAL
        assertEquals(RIGHT_SOLO, CompareTimeline.domainOf(g, leftLiveMs = 0L, rightLiveMs = 4_900L, true, false))
        // T 回到 0：双覆盖区，交回双主钟
        assertEquals(DUAL, CompareTimeline.domainOf(g, leftLiveMs = 0L, rightLiveMs = 5_000L, true, false))
    }

    @Test
    fun `域判定_off负头段_park在0不误判_回含边界即交接`() {
        // 审查修复轮 1 P1 复现迹线：attach 对齐是裸 seekTo(leftPos+off)，off=−3000 被钳 0
        // → 右引擎 park 在素材 0、tR = rightLive − off = |off| 恰落右覆盖段 [3000, 23000)
        // 的含边界左缘。判定必须给 LEFT_SOLO（右 dormant+黑层），绝不能进 DUAL 发出
        // desired = tL + off < 0 的负值 seekRight（会被引擎钳 0，每拍冲刷解码器）
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = -3_000L)
        val c = CompareTimeline.decide(input(geo = g, leftLive = 1_000L, rightLive = 0L))
        assertEquals(LEFT_SOLO, CompareTimeline.domainOf(g, 1_000L, 0L, false, false))
        assertTrue(c.pauseRight)
        assertTrue(c.blackRight)
        assertNull(c.seekLeftMs)
        assertNull(c.seekRightMs)
        assertNull(c.nudgeRightDriftMs)
        // 交接沿是含边界：T 到 |off| → 双素材域，右轨从素材 0 起播（resume，不需要 seek）
        assertEquals(DUAL, CompareTimeline.domainOf(g, 3_000L, 0L, false, true))
        val c2 = CompareTimeline.decide(
            input(geo = g, leftLive = 3_000L, rightLive = 0L, blackRight = true)
        )
        assertTrue(c2.resumeRight)
        assertFalse(c2.blackRight)
        assertNull(c2.seekRightMs)
    }

    @Test
    fun `域判定_双素材到尾段两向与时间线末`() {
        // 尾段右向：off=0、右比左长，左播尽（park 到 durMs=ENDED 位）后右继续
        val a = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 0L)
        assertEquals(DUAL, CompareTimeline.domainOf(a, 9_900L, 9_900L, false, false))
        assertEquals(RIGHT_SOLO, CompareTimeline.domainOf(a, 10_000L, 10_000L, false, false))
        assertEquals(RIGHT_SOLO, CompareTimeline.domainOf(a, 10_000L, 15_000L, false, false))
        assertEquals(TIMELINE_END, CompareTimeline.domainOf(a, 10_000L, 20_000L, false, false))
        // 尾段左向：右比左短，右播尽后左继续
        val b = TimelineGeometry(leftDurMs = 20_000L, rightDurMs = 10_000L, offsetMs = 0L)
        assertEquals(DUAL, CompareTimeline.domainOf(b, 9_900L, 9_900L, false, false))
        assertEquals(LEFT_SOLO, CompareTimeline.domainOf(b, 10_000L, 10_000L, false, false))
        assertEquals(LEFT_SOLO, CompareTimeline.domainOf(b, 19_999L, 10_000L, false, false))
        assertEquals(TIMELINE_END, CompareTimeline.domainOf(b, 20_000L, 10_000L, false, false))
        // 在册左独播：左在覆盖区内、右没素材 → 左独播；左也尽 → 时间线末（末态优先于在册域）
        assertEquals(LEFT_SOLO, CompareTimeline.domainOf(b, 15_000L, 10_000L, false, true))
        assertEquals(TIMELINE_END, CompareTimeline.domainOf(b, 20_000L, 10_000L, false, true))
    }

    @Test
    fun `域判定_off0拖到最尾_park在素材尽头不误判双素材域`() {
        // 回归锚：seekTimeline(Tmax) 把两轨 park 到各自 durMs（ENDED 位），位置读数落在
        // 覆盖区外 → 时间线末，纠偏不会把任一轨拽回旧位。若 park 落在 dur−1 这拍就会误判
        // DUAL 并触发硬 seek（任务书 dur−1 字面在域判定下不自洽，内核类注释有记录）
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 6_000L, offsetMs = 0L)
        assertEquals(TIMELINE_END, CompareTimeline.domainOf(g, 10_000L, 6_000L, false, false))
    }

    @Test
    fun `resolveT_按域取主钟`() {
        val pre = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 5_000L)
        assertEquals(-1_000L, CompareTimeline.resolveT(pre, 0L, 4_000L, true, false))
        assertEquals(0L, CompareTimeline.resolveT(pre, 0L, 5_000L, true, false))
        val dual = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 0L)
        assertEquals(5_000L, CompareTimeline.resolveT(dual, 5_000L, 5_000L, false, false))
        val tailL = TimelineGeometry(leftDurMs = 20_000L, rightDurMs = 10_000L, offsetMs = 0L)
        assertEquals(15_000L, CompareTimeline.resolveT(tailL, 15_000L, 10_000L, false, true))
        val end = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 6_000L, offsetMs = 0L)
        assertEquals(10_000L, CompareTimeline.resolveT(end, 10_000L, 6_000L, false, false))
    }

    // ------------------------------------------------------------------ 交接连续（不 seek）

    @Test
    fun `交接_pre-roll到双素材_只唤醒不seek`() {
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 5_000L)
        // 拍 1：T=−100，右独播（左 park 在 0 黑层中）：断言左停 + 左窗黑，不许有任何 seek
        val c1 = CompareTimeline.decide(
            input(geo = g, leftLive = 0L, rightLive = 4_900L, blackLeft = true)
        )
        assertEquals(RIGHT_SOLO, CompareTimeline.domainOf(g, 0L, 4_900L, true, false))
        assertTrue(c1.pauseLeft)
        assertTrue(c1.blackLeft)
        assertFalse(c1.blackRight)
        assertNull(c1.seekLeftMs)
        assertNull(c1.seekRightMs)
        // 拍 2：T=+50 跨过 0：双素材域——只唤醒左（用户在播）+ 撤黑层 + 软追赶，仍不许 seek
        val c2 = CompareTimeline.decide(
            input(geo = g, leftLive = 0L, rightLive = 5_050L, blackLeft = true)
        )
        assertTrue(c2.resumeLeft)
        assertFalse(c2.blackLeft)
        assertNull(c2.seekLeftMs)
        assertNull(c2.seekRightMs)
        assertEquals(50L, c2.nudgeRightDriftMs)
    }

    @Test
    fun `交接_双素材到尾段右向_左置dormant不seek`() {
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 0L)
        // 拍 1：双素材域中段，纠偏在跑
        val c1 = CompareTimeline.decide(input(geo = g, leftLive = 9_900L, rightLive = 9_900L))
        assertEquals(DUAL, CompareTimeline.domainOf(g, 9_900L, 9_900L, false, false))
        assertNull(c1.seekLeftMs)
        assertFalse(c1.pauseLeft)
        // 拍 2：左播尽（位置=L=ENDED 位）：右独播——左断言停+黑层，主钟换右，交接不 seek
        val c2 = CompareTimeline.decide(input(geo = g, leftLive = 10_000L, rightLive = 10_000L))
        assertEquals(RIGHT_SOLO, CompareTimeline.domainOf(g, 10_000L, 10_000L, false, false))
        assertTrue(c2.pauseLeft)
        assertTrue(c2.blackLeft)
        assertNull(c2.seekLeftMs)
        assertNull(c2.seekRightMs)
        // 拍 3：右到尾：时间线末——两轨素材都尽，撤黑层（末帧不盖黑）
        val c3 = CompareTimeline.decide(input(geo = g, leftLive = 10_000L, rightLive = 20_000L))
        assertEquals(TIMELINE_END, CompareTimeline.domainOf(g, 10_000L, 20_000L, false, false))
        assertFalse(c3.blackLeft)
        assertFalse(c3.blackRight)
    }

    @Test
    fun `交接_双素材到尾段左向_右置dormant不seek`() {
        val g = TimelineGeometry(leftDurMs = 20_000L, rightDurMs = 10_000L, offsetMs = 0L)
        val c = CompareTimeline.decide(input(geo = g, leftLive = 10_000L, rightLive = 10_000L))
        assertEquals(LEFT_SOLO, CompareTimeline.domainOf(g, 10_000L, 10_000L, false, false))
        assertTrue(c.pauseRight)
        assertTrue(c.blackRight)
        assertFalse(c.blackLeft)
        assertNull(c.seekLeftMs)
        assertNull(c.seekRightMs)
        // 用户暂停时跨进左独播：右保持停；用户回拖到双覆盖区（seekTimeline 已重定位右轨）
        // 再点播放：交接唤醒只在 userPlaying 时下发
        val paused = CompareTimeline.decide(
            input(geo = g, leftLive = 10_000L, rightLive = 10_000L, userPlaying = false, blackRight = true)
        )
        assertTrue(paused.pauseRight)
        assertFalse(paused.resumeRight)
        val woke = CompareTimeline.decide(
            input(geo = g, leftLive = 9_000L, rightLive = 9_000L, userPlaying = true, blackRight = true)
        )
        assertTrue(woke.resumeRight)
        assertFalse(woke.blackRight)
    }

    // ------------------------------------------------------------------ 纠偏

    @Test
    fun `双覆盖软追赶_邻域nudge与漂出硬seek共存`() {
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 3_000L)
        // 邻域内（|drift|=100 ≤ 250）：只软追赶，不动位置、不碰 pause/黑层
        val nudge = CompareTimeline.decide(input(geo = g, leftLive = 5_000L, rightLive = 8_100L))
        assertEquals(DUAL, CompareTimeline.domainOf(g, 5_000L, 8_100L, false, false))
        assertEquals(100L, nudge.nudgeRightDriftMs)
        assertNull(nudge.seekRightMs)
        assertNull(nudge.seekLeftMs)
        assertFalse(nudge.pauseLeft)
        assertFalse(nudge.pauseRight)
        assertFalse(nudge.blackLeft)
        assertFalse(nudge.blackRight)
        // 漂出邻域（drift=300 > 250）：一次硬 seek 拉回期望位（T+off），不下发 nudge
        val seek = CompareTimeline.decide(input(geo = g, leftLive = 5_000L, rightLive = 8_300L))
        assertNull(seek.nudgeRightDriftMs)
        assertEquals(8_000L, seek.seekRightMs)
    }

    @Test
    fun `off0回归红线_纠偏期望位就等于左片时间`() {
        // off=0：desired = T + 0 = T，与旧公式取值一致（公式未动，只是 T 的取值范围变了）
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 10_000L, offsetMs = 0L)
        val c = CompareTimeline.decide(input(geo = g, leftLive = 4_000L, rightLive = 4_200L))
        assertEquals(200L, c.nudgeRightDriftMs)
        val c2 = CompareTimeline.decide(input(geo = g, leftLive = 4_000L, rightLive = 4_600L))
        assertEquals(4_000L, c2.seekRightMs)
    }

    // ------------------------------------------------------------------ 时间线末

    @Test
    fun `时间线末_循环开到尾即回绕Tmin`() {
        // off=5000：Tmin=−off=−5000（pre-roll 是时间线的一段），回绕落 T=−5000：
        // 左 park 到 0（黑层在册，播进 T≥0 自然醒）、右落到自己素材头 0
        val pre = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 5_000L)
        val w1 = CompareTimeline.decide(input(geo = pre, leftLive = 10_000L, rightLive = 20_000L))
        assertEquals(0L, w1.seekLeftMs)
        assertEquals(0L, w1.seekRightMs)
        // park 侧直接带 pause+黑层（先 resume 后 park 会让 dormant 侧以播放态停在 park 位空走
        // ≤1 tick，审查修复轮 1 P3；黑层同步亮起遮住 park 侧并给下一拍域判定回灌）；
        // Tmin 处有素材的一侧才 resume 起播、不盖黑
        assertFalse(w1.resumeLeft)
        assertTrue(w1.pauseLeft)
        assertTrue(w1.blackLeft)
        assertTrue(w1.resumeRight)
        assertFalse(w1.pauseRight)
        assertFalse(w1.blackRight)
        // off=−3000：Tmin=0（min(0, −off)=0，左头），回绕落 T=0：左=0 起播、右 park 到 0 带
        // pause（右素材段从 T=3000 才开始，T=0 处右无素材）
        val neg = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = -3_000L)
        val w2 = CompareTimeline.decide(
            input(geo = neg, leftLive = 10_000L, rightLive = 20_000L, timelineLoop = true)
        )
        assertEquals(0L, w2.seekLeftMs)
        assertEquals(0L, w2.seekRightMs)
        assertTrue(w2.resumeLeft)
        assertFalse(w2.pauseLeft)
        assertFalse(w2.blackLeft)
        assertFalse(w2.resumeRight)
        assertTrue(w2.pauseRight)
        assertTrue(w2.blackRight)
    }

    @Test
    fun `时间线末_循环关停末帧且不自动重启`() {
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 0L)
        // 循环关、播完停末帧：isPlaying 已落的自然播完（userPlaying=false）不回绕
        val stop = CompareTimeline.decide(
            input(geo = g, leftLive = 10_000L, rightLive = 20_000L, userPlaying = false, timelineLoop = false)
        )
        assertNull(stop.seekLeftMs)
        assertNull(stop.seekRightMs)
        assertFalse(stop.blackLeft)
        assertFalse(stop.blackRight)
        // 循环关、播放意图还挂着（isPlaying 轮询滞后）也不能回绕：循环关的"ENDED 后点播放
        // 再重播"是 setPlaying 这个用户动作的职责，decide 一自动重启就会违背"停末帧"裁决
        val noRestart = CompareTimeline.decide(
            input(geo = g, leftLive = 10_000L, rightLive = 20_000L, userPlaying = true, timelineLoop = false)
        )
        assertNull(noRestart.seekLeftMs)
        assertNull(noRestart.seekRightMs)
        // 循环关+用户真点了播放：同样不给 wrap（重播走 seekTimeline(Tmin)，见编排守卫）
        val replay = CompareTimeline.decide(
            input(geo = g, leftLive = 10_000L, rightLive = 20_000L, userPlaying = true, timelineLoop = false)
        )
        assertNull(replay.seekLeftMs)
    }

    @Test
    fun `AB段循环激活时_decide不抢时间线回绕`() {
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 0L)
        val c = CompareTimeline.decide(
            input(geo = g, leftLive = 10_000L, rightLive = 20_000L, abLoop = true, timelineLoop = true)
        )
        assertNull(c.seekLeftMs)
        assertNull(c.seekRightMs)
        assertFalse(c.resumeLeft)
    }

    // ------------------------------------------------------------------ 拖动让路

    @Test
    fun `拖动让路_压seek_nudge_wrap_素材域pause黑层照做`() {
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 3_000L)
        // 双素材域拖动：纠偏全停
        val d = CompareTimeline.decide(input(geo = g, leftLive = 5_000L, rightLive = 8_300L, dragging = true))
        assertNull(d.seekRightMs)
        assertNull(d.nudgeRightDriftMs)
        assertFalse(d.pauseLeft)
        // 时间线末拖动：不回绕（松手后的 seekUnified 才落位）
        val e = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 6_000L, offsetMs = 0L)
        val w = CompareTimeline.decide(
            input(geo = e, leftLive = 10_000L, rightLive = 6_000L, dragging = true, timelineLoop = true)
        )
        assertNull(w.seekLeftMs)
        assertNull(w.seekRightMs)
        // 独播域拖动：dormant 断言照做（黑层不许闪）
        val s = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 0L)
        val solo = CompareTimeline.decide(
            input(geo = s, leftLive = 10_000L, rightLive = 15_000L, dragging = true)
        )
        assertTrue(solo.pauseLeft)
        assertTrue(solo.blackLeft)
    }

    // ------------------------------------------------------------------ pre-roll seek/park 全链路

    @Test
    fun `pre-roll_seek落park后_拍间状态自洽`() {
        val g = TimelineGeometry(leftDurMs = 10_000L, rightDurMs = 20_000L, offsetMs = 5_000L)
        // seekUnified 拖到 T=−2000：左 park 0（+黑层+停）、右落 T+off=3000（几何给目标）
        assertEquals(0L, g.leftTarget(-2_000L))
        assertEquals(3_000L, g.rightTarget(-2_000L))
        // 编排拍：黑层在册 → 右独播维持（左保持停、黑层不闪、无 seek）
        val c = CompareTimeline.decide(
            input(geo = g, leftLive = 0L, rightLive = 3_000L, blackLeft = true)
        )
        assertTrue(c.pauseLeft)
        assertTrue(c.blackLeft)
        assertNull(c.seekLeftMs)
        assertNull(c.seekRightMs)
    }
}
