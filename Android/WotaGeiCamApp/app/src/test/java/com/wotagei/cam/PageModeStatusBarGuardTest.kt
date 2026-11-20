package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页系统栏策略的结构守卫（Y59）。
 *
 * 窗口显隐只有真机才能看见（无 Robolectric/仪器测试），JVM 侧钉的是**同一分支里成对出现**：
 * 恢复状态栏 + 只藏导航栏。删掉 show(statusBars) 后，从全沉浸录制页直接进设置页就仍停在
 * 无状态栏状态——这正是卡片描述的缺陷。真机验收另记（见交付说明）。
 */
class PageModeStatusBarGuardTest {

    @Test
    fun `设置页分支显式恢复状态栏且只藏导航栏`() {
        val flat = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/MainActivity.kt"))
        )
        val marker = "route == ROUTE_SETTINGS ->"
        val start = flat.indexOf(marker)
        assertTrue("MainActivity 必须仍按设置页 route 分派窗口策略", start >= 0)
        // 分支体到下一个 when 分支之前（else ->）为止；固定窗口足够覆盖这三个调用
        val branch = flat.substring(start, minOf(flat.length, start + 400))
        assertTrue(
            "设置页必须显式 show(statusBars)：从 HUD 页进来时状态栏仍是隐藏态，只 hide(navigationBars) 恢复不了",
            branch.contains("controller.show(WindowInsetsCompat.Type.statusBars())")
        )
        assertTrue(
            "设置页仍只藏导航栏（不连状态栏一起藏）",
            branch.contains("controller.hide(WindowInsetsCompat.Type.navigationBars())")
        )
    }
}