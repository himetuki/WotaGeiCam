package com.wotagei.cam

import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.decideFps
import com.wotagei.cam.core.fpsRangesUnion
import com.wotagei.cam.core.pickHighSpeedFpsRange
import com.wotagei.cam.core.needsHighSpeedFps
import com.wotagei.cam.core.pickFpsRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧率档位可用性的机型 fixtures 测试（2026-10-26 用户报障）。
 *
 * 现场机型 vivo V2520A（Android 16，日志 `wotagei-log-20261026-140150.txt`）：
 * 系统相机可录 50/60/120/240，本应用帧率弹层只亮 24/30——根因是档位可用性按
 * 「当前会话模态」判定：默认（≤60）态下只查普通流，而该机普通流仅 [24,24]/[30,30]，
 * 50/60/120/240 全部只存在于高速表（1080p: [30,120]/[60,120]/[120,120]/
 * [30,240]/[60,240]/[240,240]）→ 按当前模态判定永远灰显（死循环：120/240 只有
 * 已在 >60 档的用户才查得到）。
 *
 * 修复语义（2026-10-26 定版）：**一档的完整决议只走 [decideFps] 一个出口** —— 普通表先来
 * （覆盖得了就不开高速会话），覆盖不了才看高速表；两张表都不覆盖 ⇒ 灰显。50/60 在该机
 * 仍是可用档（这张高速表正是厂商把"高帧率录像"暴露给第三方 App 的途径），实际速率由 HAL 在
 * 区间内定 ⇒ 界面标 ※；25 两表都不覆盖 → 维持普通会话 + 抽帧补弧路径。
 *
 * 【为什么不是"并集允许、下发另算"】并集只回答"能不能选"；而下发必须落在**机器声明过的区间**上。
 * 旧的下发分支自己造 `[50,50]`（机器没声明过）⇒ `createHighSpeedRequestList` 拒收 ⇒ 假死。
 * 所以判定与下发同一支，见本文件末尾那组下发区间断言。
 */
class CameraAbilityFpsTest {

    /** vivo V2520A 主摄日志原样（WotaAbility fpsRanges= 行） */
    private val vivoNormal = listOf(
        RangeI(5, 7), RangeI(5, 15), RangeI(5, 20), RangeI(5, 30), RangeI(7, 30),
        RangeI(10, 10), RangeI(10, 30), RangeI(15, 15), RangeI(24, 24), RangeI(30, 30)
    )

    /** vivo V2520A 高速表 1920x1080 行（WotaAbility highSpeed 行） */
    private val vivoHs1080 = listOf(
        RangeI(30, 120), RangeI(60, 120), RangeI(120, 120),
        RangeI(30, 240), RangeI(60, 240), RangeI(240, 240)
    )

    @Test
    fun `vivo 50_60_120_240 都需要高速会话`() {
        for (fps in listOf(50, 60, 120, 240)) {
            assertTrue(
                "vivo 上 $fps 普通流无档、高速表有档 ⇒ 需要高速会话",
                needsHighSpeedFps(vivoNormal, vivoHs1080, fps)
            )
        }
    }

    @Test
    fun `vivo 24_30 走普通会话_25 不误入高速`() {
        assertFalse("24 有原生 [24,24]", needsHighSpeedFps(vivoNormal, vivoHs1080, 24))
        assertFalse("30 有原生 [30,30]", needsHighSpeedFps(vivoNormal, vivoHs1080, 30))
        assertFalse(
            "25 两表都不覆盖 ⇒ 维持普通会话 + 抽帧补弧路径（不得误入高速）",
            needsHighSpeedFps(vivoNormal, vivoHs1080, 25)
        )
    }

    @Test
    fun `并集让 50_60_120_240 全部可选`() {
        val union = fpsRangesUnion(vivoNormal, vivoHs1080)
        for (fps in listOf(50, 60, 120, 240)) {
            assertNotNull(
                "并集必须覆盖 $fps（修复前该档灰显的根因）",
                pickFpsRange(union, fps)
            )
        }
        // 既有档位语义不回退
        assertEquals(24 to 24, pickFpsRange(union, 24)!!.let { it.lo to it.hi })
        assertTrue("30 应命中原生固定档（exact）", pickFpsRange(union, 30)!!.exact)
    }

    @Test
    fun `精确性口径_固定档exact_区间内approx`() {
        val union = fpsRangesUnion(vivoNormal, vivoHs1080)
        assertTrue("120 有原生 [120,120] ⇒ exact", pickFpsRange(union, 120)!!.exact)
        assertTrue("240 有原生 [240,240] ⇒ exact", pickFpsRange(union, 240)!!.exact)
        assertFalse("50 是 [30,120] 区间内近似 ⇒ ※", pickFpsRange(union, 50)!!.exact)
        assertFalse("60 命中 [60,120] 区间 ⇒ ※（实际帧率由 HAL 在区间内决定）", pickFpsRange(union, 60)!!.exact)
        // 25 由普通流 [5,30] 可变档近似覆盖（vivo 普通流含 [5,30]）——这正是该机 25 走
        // 「普通会话 + 抽帧补弧」的机制：覆盖来自普通表、exact=false ⇒ convertActive 成立
        val p25 = pickFpsRange(union, 25)
        assertNotNull("25 由普通流 [5,30] 近似覆盖", p25)
        assertFalse("25 是近似覆盖 ⇒ ※", p25!!.exact)
        assertEquals("25 命中普通流 [5,30]", 5 to 30, p25.lo to p25.hi)
    }

    @Test
    fun `无高速表的机型不受影响`() {
        // 模拟器/老机型：普通流之外无高速能力 → 并集 = 普通流，判定与修复前一致
        val normalOnly = listOf(RangeI(24, 24), RangeI(30, 30))
        assertFalse(needsHighSpeedFps(normalOnly, emptyList(), 120))
        assertNull(pickFpsRange(fpsRangesUnion(normalOnly, emptyList()), 120))
        assertNotNull(pickFpsRange(fpsRangesUnion(normalOnly, emptyList()), 24))
        assertNotNull(pickFpsRange(fpsRangesUnion(normalOnly, emptyList()), 30))
    }

    @Test
    fun `有原生50的机型走普通会话`() {
        // 部分机型普通流直接给 [50,50]：50 不需要高速会话
        val with50 = listOf(RangeI(24, 24), RangeI(30, 30), RangeI(50, 50))
        assertFalse(needsHighSpeedFps(with50, vivoHs1080, 50))
    }

    // ---------------------------------------------------------------- 决议与下发同一支（2026-10-26）

    @Test
    fun `决议后下发的是机器声明过的区间`() {
        // 这条正是 2026-10-26 的 P1：旧下发分支 `FpsPick(target, target, true)` 会造 [50,50]，
        // 而该机高速表里没有它 —— 请求被拒 ⇒ 掉进无退路的降档分支 ⇒ 取景器假死
        assertEquals(
            "60 取「下界正好等于目标」里上界最小的 [60,120]",
            Triple(60, 120, false),
            decideFps(vivoNormal, vivoHs1080, 60)!!.let { Triple(it.pick.lo, it.pick.hi, it.pick.exact) }
        )
        assertEquals(
            "50 无 lo==50 的区间 ⇒ 退到覆盖它的 [30,120]（上界最小的那个）",
            Triple(30, 120, false),
            decideFps(vivoNormal, vivoHs1080, 50)!!.let { Triple(it.pick.lo, it.pick.hi, it.pick.exact) }
        )
        assertTrue("120 有原生 [120,120] ⇒ 精确", decideFps(vivoNormal, vivoHs1080, 120)!!.pick.exact)
        assertTrue("240 有原生 [240,240] ⇒ 精确", decideFps(vivoNormal, vivoHs1080, 240)!!.pick.exact)
        assertTrue("50 走高速会话", decideFps(vivoNormal, vivoHs1080, 50)!!.highSpeed)
        assertTrue("60 走高速会话", decideFps(vivoNormal, vivoHs1080, 60)!!.highSpeed)
        assertFalse("30 普通表覆盖 ⇒ 普通会话", decideFps(vivoNormal, vivoHs1080, 30)!!.highSpeed)
        assertFalse("24 普通表覆盖 ⇒ 普通会话", decideFps(vivoNormal, vivoHs1080, 24)!!.highSpeed)
    }

    @Test
    fun `高速选档绝不返回未声明的固定区间`() {
        for (fps in listOf(50, 60)) {
            val pick = pickHighSpeedFpsRange(vivoHs1080, fps)!!
            assertTrue(
                "下发区间 $fps -> [${pick.lo},${pick.hi}] 必须是声明表里的原样一条",
                vivoHs1080.any { it.lo == pick.lo && it.hi == pick.hi }
            )
        }
        assertNull(
            "表里没有任何区间覆盖目标 ⇒ null（由失败路径降级，不许硬发）",
            pickHighSpeedFpsRange(listOf(RangeI(120, 120), RangeI(240, 240)), 60)
        )
    }
}
