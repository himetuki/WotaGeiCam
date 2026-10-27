package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站位线 UI 接线守卫（2026-10-27，三轮反馈逐条钉）。
 *
 * 1. 首轮：`MarkerLineOverlay` 曾无条件组合 ⇒ 线关不掉。现在开关 = **线数 > 0**，
 *    执行点必须钉在叠加层的 `if` 上。
 * 2. 二轮：点按圆钮弹的浮层一旦被误点（点外部即关）就连带把线关掉 —— 用户点名"改成
 *    线数为零时关闭"。守卫钉：页面里**不存在**独立开关 `markerOn`；
 *    `if (markerControlsOpen)` 那层控件**完全透明**（无 `wotaCard()` 卡片底）。
 * 3. 三轮：浮层曾被移到 TopCenter 而"太靠中间" ⇒ 必须贴着圆钮落点，且右缘不得越过
 *    实测的右 Dock 槽左缘（`MarkerPopupPosition` + `core.popupBelowRight`）。
 */
class MarkerWiringGuardTest {

    private val screen by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
    }

    private val flat by lazy { KotlinSourceScan.flatten(screen) }

    /** 控件浮层源码段（`--` 切到选色窗之前；选色窗段单独取） */
    private fun controlsBody(): String {
        val i = screen.indexOf("if (markerControlsOpen) {")
        val j = screen.indexOf("if (markerColorPop) {", i + 1)
        assertTrue("控件浮层必须在场", i >= 0)
        assertTrue("选色窗必须在控件浮层之后", j > i)
        return screen.substring(i, j)
    }

    private fun colorPopBody(): String {
        val i = screen.indexOf("if (markerColorPop) {")
        assertTrue("选色弹窗必须在场", i >= 0)
        return screen.substring(i, minOf(i + 1600, screen.length))
    }

    @Test
    fun `叠加层必须挂线数门控_且没有独立开关`() {
        // 开关执行点：if (markerCount > 0) { MarkerLineOverlay( —— 线的生死线
        assertTrue(
            "MarkerLineOverlay 必须由 if (markerCount > 0) 门控（否则线关不掉）",
            flat.contains("if (markerCount > 0) { MarkerLineOverlay(")
        )
        // 二轮定版：不许再出现独立的开关流（有它 = 误关浮层会连带关线）
        assertFalse(
            "页面不许再引用 markerOn（线的开/关只由线数驱动）",
            screen.contains("markerOn")
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
    fun `点按浮层完全透明_亮暗可辨的浮动控件`() {
        val body = controlsBody()
        // 完全透明背景：不许出现卡片底（wotaCard/wotaHudCard 都会铺一块底板）
        assertFalse("控件浮层不许铺卡片底（用户点名完全透明）", body.contains("wotaCard"))
        assertFalse("控件浮层不许铺 HUD 卡片底", body.contains("wotaHudCard"))
        // 但必须仍有可辨的控件本体：半透明白圆钮 + 带阴影的白字
        assertTrue("+/− 浮动圆钮必须在场", body.contains("MarkerClusterButton("))
        assertTrue("线数读字必须在场", body.contains("markerCount.toString()"))
        assertTrue("间距滑杆必须在场", body.contains("Slider("))
        assertTrue("线数必须可被 +/− 改", body.contains("params.markerCount.value ="))
        assertTrue("间距必须可被滑杆改", body.contains("params.markerSpread.value ="))
    }

    @Test
    fun `两个浮层都贴圆钮落点_且实测右Dock左缘作保留区`() {
        val body = controlsBody()
        // 三轮反馈：不要漂在画面中间，也不要压右 Dock ⇒ 两个浮层都走实测锚点定位器
        assertTrue(
            "控件浮层必须用 MarkerPopupPosition 实测落点",
            body.contains("popupPositionProvider = MarkerPopupPosition(")
        )
        assertFalse("控件浮层不许再按窗口对齐（那会漂离控件）", body.contains("alignment = Alignment.TopCenter"))
        assertFalse("控件浮层不许贴 TopEnd（会压右 Dock）", body.contains("alignment = Alignment.TopEnd"))
        val pop = colorPopBody()
        assertTrue(
            "选色窗必须用同一套实测落点",
            pop.contains("popupPositionProvider = MarkerPopupPosition(")
        )
        assertFalse("选色窗不许再按窗口对齐", pop.contains("alignment = Alignment.TopCenter"))
        // 锚点与保留区都必须是**实测**矩形：圆钮的 boundsInWindow、右 Dock 槽的 boundsInWindow
        assertTrue(
            "圆钮必须回报自身窗口矩形当锚点",
            flat.contains("markerAnchorRect = r") && flat.contains("boundsInWindow()")
        )
        assertTrue(
            "右 Dock 槽必须回报窗口矩形当右侧保留区",
            flat.contains("rightDockSlotRect = r")
        )
        assertTrue(
            "两个浮层都要把保留区左缘喂给定位器",
            KotlinSourceScan.occurrences(flat, "rightKeepOutPx = rightDockSlotRect?.left?.roundToInt()").size >= 2
        )
        // 定位器内部走纯算术出口（JVM 单测在 PopupPlacementTest）
        assertTrue(
            "定位器必须走 core.popupBelowRight（贴右下的算术唯一出口）",
            flat.contains("com.wotagei.cam.core.popupBelowRight(")
        )
    }

    @Test
    fun `选色窗只含色点_线数间距不入窗`() {
        val pop = colorPopBody()
        assertTrue("弹窗里必须有色点", pop.contains("MarkerLineColor.ALL.forEach"))
        assertFalse(
            "弹窗里不许出现线数控件（用户口径：那是透明浮层里的页面控件）",
            pop.contains("params.markerCount.value =")
        )
        assertFalse(
            "弹窗里不许出现间距滑杆",
            pop.contains("params.markerSpread.value =")
        )
    }

    @Test
    fun `尺子自己能红_摘掉线数门控必报红`() {
        val clean = flat
        val needle = "if (markerCount > 0) { MarkerLineOverlay("
        assertTrue("良品必须先绿", clean.contains(needle))
        val mutated = clean.replace(needle, "MarkerLineOverlay(")
        assertFalse("摘掉线数门控的突变必须被守卫抓到", mutated.contains(needle))
    }
}
