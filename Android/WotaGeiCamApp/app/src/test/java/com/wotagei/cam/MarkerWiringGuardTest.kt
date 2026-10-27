package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站位线 UI 接线守卫（2026-10-27）：真机首轮截图抓到的缺陷——
 * `MarkerLineOverlay` 无条件组合（`markerOn` 只控制了控件簇显隐）⇒ 线关不掉。
 * 这里把"开关的执行点在叠加层上"钉成源码断言，顺带钉控件簇与选色弹窗的三个口径：
 * - 圆钮在分屏钮左侧（padding 让位算式 = 分屏钮自己的再退一份）；
 * - 内联簇（+/线数/- 与滑杆）只在 markerOn 时组合（用户点名的无弹窗式控件）；
 * - 选色弹窗只含色点（线数/间距不入弹窗）。
 */
class MarkerWiringGuardTest {

    private val screen by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
    }

    @Test
    fun `叠加层必须挂markerOn开关_且在分屏钮左侧`() {
        // 开关执行点：if (markerOn) { MarkerLineOverlay( — 不是装饰，是线的生死线
        assertTrue(
            "MarkerLineOverlay 必须由 if (markerOn) 门控（否则线关不掉，真机首轮抓到的缺陷）",
            screen.contains("if (markerOn) {\nMarkerLineOverlay(") ||
                KotlinSourceScan.occurrences(
                    screen, "if (markerOn) { MarkerLineOverlay("
                ).isNotEmpty() ||
                KotlinSourceScan.flatten(screen).contains("if (markerOn) { MarkerLineOverlay(")
        )
        // 圆钮在分屏钮左侧：padding 让位 = 分屏钮自己的算式再退一份（同令牌）
        val splitAt = screen.indexOf("Icons.Outlined.Splitscreen")
        val markerAt = screen.indexOf("Icons.Outlined.ViewWeek")
        assertTrue("分屏钮必须存在", splitAt >= 0)
        assertTrue("站位线圆钮必须存在", markerAt >= 0)
        assertTrue("站位线圆钮必须排的分屏钮之后（渲染在左侧靠 padding 让位）", markerAt > splitAt)
        assertTrue(
            "让位算式必须用同套令牌再退一份",
            screen.contains("end = HudEdgePad + (WotaHit.iconButton + TopRowGap) * 2")
        )
    }

    @Test
    fun `内联簇与选色弹窗口径`() {
        // 内联簇只在开启时组合
        assertTrue(
            "内联控件簇必须由 if (markerOn) 门控（无弹窗式控件，不常驻）",
            KotlinSourceScan.flatten(screen).contains("if (markerOn) { Column(")
        )
        // 选色弹窗只含色点：不得混入线数/间距控件
        val popIdx = screen.indexOf("if (markerColorPop) {")
        assertTrue("选色弹窗必须在场", popIdx >= 0)
        val popBody = screen.substring(popIdx, minOf(popIdx + 2200, screen.length))
        assertTrue("弹窗里必须有色点", popBody.contains("MarkerLineColor.ALL.forEach"))
        assertTrue(
            "弹窗里不许出现线数/间距控件（用户口径：那些是无弹窗式页面控件）",
            !popBody.contains("markerCount.value =") && !popBody.contains("markerSpread.value =")
        )
    }

    @Test
    fun `尺子自己能红_摘掉开关必报红`() {
        val clean = KotlinSourceScan.flatten(screen)
        val needle = "if (markerOn) { MarkerLineOverlay("
        assertTrue("良品必须先绿", clean.contains(needle))
        val mutated = clean.replace(needle, "MarkerLineOverlay(")
        assertFalse("摘掉开关的突变必须被守卫抓到", mutated.contains(needle))
    }
}
