package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线光弧修复执行端（ArcRepairRunner）**换线守卫**（M6-01：探测/计划核已是有理数真值链，
 * 执行端若回潮旧整数口径会让修复在 29.97/25.4 这类源上重新分叉——29.97 被整成 30 保留位次
 * 漂一位，25.4 被整成 25 误判 NO_NEED 漏修）。行为测试够不着 MediaCodec 路线，这里钉源文结构。
 */
class ArcRepairRunnerRationalGuardTest {

    @Test
    fun `runner探测必须走有理数真值_旧整数口径不得回潮`() {
        val runner = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("record/ArcRepairRunner.kt"))
        assertEquals(
            "旧整数口径 measuredFps 不得回潮（29.97 整成 30 位次漂移、25.4 整成 25 漏修，见 M6-01）",
            0,
            KotlinSourceScan.occurrences(runner, "measuredFps(").size
        )
        assertTrue(
            "探测必须走 measuredSrcFps（有理数真值链入口缺失 = 整数视图在喂计划核）",
            runner.contains("measuredSrcFps(")
        )
        assertTrue(
            "NO_NEED 门槛必须判有理数真值（整数取整把 25.4 打成 25 漏修；atMostInt 接线缺失 = 门槛回潮）",
            runner.contains("srcRate.atMostInt(dstFps)")
        )
        assertTrue(
            "抽帧计划必须吃 num/den 真值（吃整数视图 = 非整数源位次分叉）",
            runner.contains("ArcRepairPlan.of(srcFrames, srcRate.num, srcRate.den, dstFps)")
        )
        assertTrue(
            "去重阈值必须跟有理数真值（它决定哪些源帧被采用；den/num 接线缺失 = 半周期判距回潮整数）",
            runner.contains("500_000L * srcRate.den / srcRate.num")
        )
        // 整数视图只许出现在量级型消费点：成片名前缀（30fto24f 只要量级对）
        val prefixBody = KotlinSourceScan.bodyOf(runner, "open")
        assertTrue(
            "成片名前缀应保持整数视图（convertNamePrefix 只看量级）",
            prefixBody.contains("ArcRateProbe.convertNamePrefix(srcFps, dstFps)")
        )
    }
}
