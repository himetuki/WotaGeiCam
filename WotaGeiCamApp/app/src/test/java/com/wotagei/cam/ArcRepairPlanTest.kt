package com.wotagei.cam

import com.wotagei.cam.record.ArcRepairPlan
import com.wotagei.cam.record.FrameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复**插帧计划**的 JVM 全覆盖（用户需求第 7 项）。
 *
 * 本文件只钉"计划"这一层：帧数、帧来源、权重、进度。**解码/编码/EGL/Canvas 只能在真机验**
 * （MediaCodec、EGL14、Surface.lockCanvas 在 JVM 里都不存在），所以这里刻意不碰它们——
 * 真机那一半的证据来自 `ArcRepairRunner`/`ArcRepairGl` 的日志与成片。
 *
 * 判据里既测"公式"也测"计划→行为"的桥：`sourceAt` 是行为（引擎按它决定这一帧怎么来），
 * `originalOutIndex`/`blendOutIndex` 是计划侧的逆映射；两者必须自洽，否则引擎 PTS 会错位。
 * 结尾一条"整段枚举"用例把 N=1..8 的每一帧逐个对账，专门防"删掉某个分支仍全绿"。
 */
class ArcRepairPlanTest {

    // region 帧数

    @Test
    fun `空与单帧_输出帧数不放大`() {
        assertEquals(0, ArcRepairPlan.dstFrameCount(0))
        assertEquals(1, ArcRepairPlan.dstFrameCount(1))
    }

    @Test
    fun `负数源帧数按 0 处理_不崩`() {
        assertEquals(0, ArcRepairPlan.dstFrameCount(-7))
        assertEquals(0, ArcRepairPlan.of(-7).dstFrames)
        assertEquals(0, ArcRepairPlan.of(-7).srcFrames)
    }

    @Test
    fun `帧数满足 2N 减 1`() {
        // 每对相邻原帧插 1 帧、末帧不插 ⇒ 输出 = N + (N−1) = 2N−1
        assertEquals(3, ArcRepairPlan.dstFrameCount(2))
        assertEquals(5, ArcRepairPlan.dstFrameCount(3))
        assertEquals(19, ArcRepairPlan.dstFrameCount(10))
        assertEquals(2 * 240 - 1, ArcRepairPlan.dstFrameCount(240))
    }

    @Test
    fun `of 同时给出源与目标且自洽`() {
        val p = ArcRepairPlan.of(4)
        assertEquals(4, p.srcFrames)
        assertEquals(7, p.dstFrames)
        assertEquals(7, p.totalFrames)
        assertEquals(p.dstFrames, ArcRepairPlan.dstFrameCount(p.srcFrames))
    }

    // endregion

    // region 帧来源

    @Test
    fun `偶数位是原帧_奇数位是混合帧且权重居中`() {
        assertEquals(FrameSource.Original(0), ArcRepairPlan.sourceAt(0))
        assertEquals(FrameSource.Original(1), ArcRepairPlan.sourceAt(2))
        assertEquals(FrameSource.Original(3), ArcRepairPlan.sourceAt(6))
        assertEquals(FrameSource.Blend(0, 0.5f), ArcRepairPlan.sourceAt(1))
        assertEquals(FrameSource.Blend(3, 0.5f), ArcRepairPlan.sourceAt(7))
    }

    @Test
    fun `混合权重定版居中`() {
        assertEquals(0.5f, ArcRepairPlan.blendWeight(), 0f)
    }

    @Test
    fun `末帧不插_总落在原帧上`() {
        // dstFrames = 2N−1 恒为奇数 ⇒ 末位下标 2N−2 是偶数位
        for (n in 1..12) {
            val dst = ArcRepairPlan.dstFrameCount(n)
            val last = ArcRepairPlan.sourceAt(dst - 1)
            assertEquals("N=$n 的末帧应是原帧 N−1", FrameSource.Original(n - 1), last)
        }
    }

    @Test
    fun `越界下标不抛异常`() {
        // 后台线程的"绝不崩"纪律：计划侧不做输入校验崩溃，只做有定义的退化
        assertEquals(FrameSource.Original(0), ArcRepairPlan.sourceAt(-1))
        assertEquals(FrameSource.Original(0), ArcRepairPlan.sourceAt(-99))
        // 超出上界仍按奇偶公式给出定义值（引擎保证只喂合法下标）
        assertEquals(FrameSource.Original(50), ArcRepairPlan.sourceAt(100))
    }

    // endregion

    // region 计划 → 行为的桥

    @Test
    fun `输出下标映射与 sourceAt 严格互逆`() {
        for (k in 0 until 6) {
            assertEquals(ArcRepairPlan.sourceAt(ArcRepairPlan.originalOutIndex(k)), FrameSource.Original(k))
            if (k < 5) {
                val s = ArcRepairPlan.sourceAt(ArcRepairPlan.blendOutIndex(k))
                assertEquals(FrameSource.Blend(k, ArcRepairPlan.blendWeight()), s)
            }
        }
    }

    @Test
    fun `混合帧永不引用末帧之外的下一帧`() {
        // 整段枚举：N=1..8 的每一帧逐个对账（防删掉分支仍全绿）
        for (n in 1..8) {
            val dst = ArcRepairPlan.dstFrameCount(n)
            var originals = 0
            var blends = 0
            for (i in 0 until dst) {
                when (val s = ArcRepairPlan.sourceAt(i)) {
                    is FrameSource.Original -> {
                        assertTrue("原帧下标越界 i=$i N=$n", s.index in 0 until n)
                        originals++
                    }
                    is FrameSource.Blend -> {
                        // 混合帧必须同时有左原帧与右原帧（index+1 ≤ n−1）
                        assertTrue("混合帧越界 i=$i N=$n", s.index in 0 until n - 1)
                        assertEquals(0.5f, s.weight, 0f)
                        blends++
                    }
                }
            }
            assertEquals("原帧数应等于源帧数 N=$n", n, originals)
            assertEquals("混合帧数应为 N−1，N=$n", n - 1, blends)
            assertEquals("输出帧数应为 2N−1，N=$n", dst, originals + blends)
        }
    }

    // endregion

    // region 进度

    @Test
    fun `进度在边界与超界处被钳制`() {
        assertEquals(0f, ArcRepairPlan.progressOf(0, 10), 0f)
        assertEquals(0.5f, ArcRepairPlan.progressOf(5, 10), 0f)
        assertEquals(1f, ArcRepairPlan.progressOf(10, 10), 0f)
        assertEquals(1f, ArcRepairPlan.progressOf(20, 10), 0f)
        assertEquals(0f, ArcRepairPlan.progressOf(-3, 10), 0f)
    }

    @Test
    fun `总帧数为 0 时进度归零而不是除零`() {
        assertEquals(0f, ArcRepairPlan.progressOf(0, 0), 0f)
        assertEquals(0f, ArcRepairPlan.progressOf(5, 0), 0f)
        assertEquals(0f, ArcRepairPlan.progressOf(5, -1), 0f)
    }

    // endregion
}
