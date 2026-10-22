package com.wotagei.cam

import com.wotagei.cam.media.WotaNav
import com.wotagei.cam.ui.ROUTE_CAMERA
import com.wotagei.cam.ui.ROUTE_HUD_EDITOR
import com.wotagei.cam.ui.ROUTE_SETTINGS
import com.wotagei.cam.ui.keepScreenOnForRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「路由 → 常亮」判定表的全表单测（产品口径：取景相关页面才常亮 + 高亮）。
 *
 * 期望值**逐条手写**：这张表是本次真机缺陷修复的输入端，表错了整条链路都错，
 * 所以不许用"实现推导期望"的写法（那等于没测）。
 */
class KeepOnRouteTableTest {

    @Test
    fun `取景相关页面常亮`() {
        assertTrue("录制页必须常亮", keepScreenOnForRoute("camera"))
        assertTrue("「编辑控件」页也在编辑取景页的控件，必须常亮", keepScreenOnForRoute("hudEditor"))
    }

    @Test
    fun `其余页面一律不常亮`() {
        assertFalse("媒体库不常亮", keepScreenOnForRoute("gallery"))
        assertFalse("设置页不常亮", keepScreenOnForRoute("settings"))
        assertFalse("播放页不常亮（路由模板形态）", keepScreenOnForRoute("player/{mediaId}"))
        assertFalse("对比页不常亮（路由模板形态）", keepScreenOnForRoute("compare?leftMediaId={leftMediaId}"))
    }

    @Test
    fun `未知路由与空值默认不常亮`() {
        assertFalse("null 不常亮", keepScreenOnForRoute(null))
        assertFalse("空串不常亮", keepScreenOnForRoute(""))
        assertFalse("未知路由不常亮", keepScreenOnForRoute("no-such-route"))
    }

    /**
     * 与路由常量对齐：这条不是"重复测试"，而是防「常量改了、判定表没跟着改」——
     * 上面两条用的是手写字面量（防实现漂移），这条用常量（防常量漂移），两条互补。
     */
    @Test
    fun `判定表与本仓路由常量一致`() {
        assertTrue(keepScreenOnForRoute(ROUTE_CAMERA))
        assertTrue(keepScreenOnForRoute(ROUTE_HUD_EDITOR))
        assertFalse(keepScreenOnForRoute(WotaNav.GALLERY))
        assertFalse(keepScreenOnForRoute(WotaNav.PLAYER))
        assertFalse(keepScreenOnForRoute(WotaNav.COMPARE))
        assertFalse(keepScreenOnForRoute(ROUTE_SETTINGS))
    }
}
