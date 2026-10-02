package com.wotagei.cam

import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.anim.MotionSpec
import com.wotagei.cam.ui.design.WotaMotion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #73 第 1 件的第二条入口：**动效时长倍率**（`MotionSpec` 的那一枚乘数）。
 *
 * 三条用例各钉一种退化，都不是恒等式：
 * - [scaleOneIsByteIdenticalToTheTokens]：倍率 1f 必须与令牌逐字相等。
 *   红法——把 `durationMs` 那一条 when 分支改回裸常量以外的任何东西（比如顺手加个 `+1`），或把
 *   默认倍率写成别的数（正常渲染从此不是"0.75 秒完成"，用户定的那一档就漂了）。
 * - [multiplierScalesEveryDuration]：×20 时 750ms 必须变 15000ms；PLAIN 档用比值钉
 *   （PLAIN_MS 是私有常量，比值断言照样能抓住"根本没乘"的实现）。
 * - [garbageScaleFallsBackToOne]：adb 敲进来的倍率是 0 / 负数 / NaN 时必须退回 1f。
 *   红法——删掉 `scale` 那枚夹取，FLUENT 的时长就变成 0（动画瞬完，取证帧一张都抓不到）。
 *
 * **测不到**：弹簧（LIQUID）档把倍率除在刚度上这件事在真机上到底跑得有多慢——只有实机能证。
 */
class MotionSpecScaleTest {

    @Test
    fun scaleOneIsByteIdenticalToTheTokens() {
        // 倍率 1f（= 没开钩子）时 FLUENT 的账面时长必须逐字等于令牌：用户定的「0.75 秒完成」不许被
        // 这套倍率机制顺手改掉。红法——把 `scaled(COMMIT_MS)` 改成任何别的表达式都红。
        assertEquals(WotaMotion.COMMIT_MS, MotionSpec(MotionMode.FLUENT, 1f).durationMs)
    }

    @Test
    fun multiplierScalesEveryDuration() {
        val twenty = MotionSpec(MotionMode.FLUENT, 20f).durationMs
        assertEquals("×20 就是 750ms → 15s（连拍才覆盖得了整个窗口）", WotaMotion.COMMIT_MS * 20, twenty)
        // PLAIN 档同一条乘数（没有它就没有"整屏动效一起放长"，取证时底栏慢了别处没慢）：
        // PLAIN_MS 是私有常量，所以钉比值而不是钉绝对值——"根本没乘"的实现一样会红
        assertEquals(MotionSpec(MotionMode.PLAIN, 1f).durationMs * 2, MotionSpec(MotionMode.PLAIN, 2f).durationMs)
        // LIQUID 没有固定时长（弹簧），倍率落在刚度上，但 durationMs 这条账面值仍要跟着放长
        assertEquals(MotionSpec(MotionMode.LIQUID, 1f).durationMs * 3, MotionSpec(MotionMode.LIQUID, 3f).durationMs)
    }

    @Test
    fun intSizeDurationFollowsInteractTokenAndReducedGoesZero() {
        // 10-01 布局批：Dock 平滑宽高的 IntSize 档——账面时长钉 INTERACT_MS 令牌（240），
        // 倍率照乘（×20 连拍取证时 Dock 也放长），reduced（系统减少动效）恒 0 = 直达
        assertEquals(
            com.wotagei.cam.ui.design.WotaMotion.INTERACT_MS,
            MotionSpec(MotionMode.FLUENT, 1f).intSizeDurationMs
        )
        assertEquals(
            com.wotagei.cam.ui.design.WotaMotion.INTERACT_MS * 20,
            MotionSpec(MotionMode.FLUENT, 20f).intSizeDurationMs
        )
        assertEquals(0, MotionSpec(MotionMode.FLUENT, 1f, reduced = true).intSizeDurationMs)
    }

    @Test
    fun garbageScaleFallsBackToOne() {
        for (bad in listOf(0f, -1f, -20f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("非法倍率 $bad 必须退回 1f", WotaMotion.COMMIT_MS, MotionSpec(MotionMode.FLUENT, bad).durationMs)
        }
    }
}
