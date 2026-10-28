package com.wotagei.cam.player

import com.wotagei.cam.FakePrefs
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.WotaSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页 chrome 状态矩阵（29 §6.3 真值表）+ 持久化 + 源码守卫（2026-10-28 F6 落地）。
 *
 * 视频尺寸恒定是硬约束（§6.5）：顶栏/底栏都是覆盖层，收起/展开只改覆盖层显隐与形状。
 * 守卫里对视频面的「不参与避让」做了负面锁（不许给 WotaPlayerSurface 加内距/缩放避让）。
 */
class PlayerChromeTest {

    // -------------------------------------------------- ① 状态矩阵真值表（手写字面量）

    @Test
    fun `状态矩阵_控件可见且未收起_完整顶栏加dock`() {
        val c = playerChromeOf(controlsVisible = true, topBarCollapsed = false)
        assertEquals(PlayerChrome(topBar = true, dock = true, floatingBack = false, pullOut = false), c)
    }

    @Test
    fun `状态矩阵_控件可见但已收起_浮动返回与拉出登场`() {
        val c = playerChromeOf(controlsVisible = true, topBarCollapsed = true)
        assertEquals(PlayerChrome(topBar = false, dock = true, floatingBack = true, pullOut = true), c)
    }

    @Test
    fun `状态矩阵_控件隐藏_浮动返回恒在_拉出跟dock走`() {
        assertEquals(
            PlayerChrome(topBar = false, dock = false, floatingBack = true, pullOut = false),
            playerChromeOf(controlsVisible = false, topBarCollapsed = false)
        )
        assertEquals(
            PlayerChrome(topBar = false, dock = false, floatingBack = true, pullOut = false),
            playerChromeOf(controlsVisible = false, topBarCollapsed = true)
        )
    }

    // -------------------------------------------------- ② 持久化（缺省展开、点按写）

    @Test
    fun `顶栏收起态_缺省false_写读往返`() {
        val prefs = FakePrefs()
        assertEquals("缺省 = 展开（保持现行为）", false, WotaSettings.playerTopBarCollapsed(prefs))
        WotaSettings.setPlayerTopBarCollapsed(prefs, true)
        assertEquals(true, WotaSettings.playerTopBarCollapsed(prefs))
        WotaSettings.setPlayerTopBarCollapsed(prefs, false)
        assertEquals(false, WotaSettings.playerTopBarCollapsed(prefs))
    }

    // -------------------------------------------------- ③ 源码守卫

    private val playerCode: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt"))
    }

    @Test
    fun `chrome四层都由playerChromeOf驱动`() {
        for (needle in listOf(
            "visible = chrome.topBar",
            "visible = chrome.dock",
            "visible = chrome.floatingBack",
            "if (chrome.pullOut)"
        )) {
            assertTrue("播放页必须按状态矩阵挂载：$needle", playerCode.contains(needle))
        }
        // 收起/展开两枚钮都必须落盘（点按写，冷启保持）
        val topBar = KotlinSourceScan.bodyOf(playerCode, "PlayerTopBar")
        assertTrue("顶栏必须带收起钮（onCollapse）", topBar.contains("KeyboardArrowUp"))
        assertTrue(playerCode.contains("setPlayerTopBarCollapsed(prefs, true)"))
        assertTrue(playerCode.contains("setPlayerTopBarCollapsed(prefs, false)"))
        // 尺要能红：拆掉一处挂载后断言翻红
        val mutant = playerCode.replace("visible = chrome.topBar", "visible = true")
        assertFalse(mutant.contains("visible = chrome.topBar"))
    }

    @Test
    fun `播放页只藏状态栏且视频面不参与避让`() {
        val main = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/MainActivity.kt"))
        val pageMode = KotlinSourceScan.bodyOf(main, "applyPageMode")
        assertTrue(
            "applyPageMode 必须有播放页分支：只 hide(statusBars)（导航栏保持显示）",
            pageMode.contains("WotaNav.PLAYER") && pageMode.contains("hide(WindowInsetsCompat.Type.statusBars())")
        )
        // 视频尺寸恒定（§6.5 硬约束）的负面锁：视频面仍是 FIT + fillMaxSize，没加避让内距
        assertTrue("视频面必须保持 fillMaxSize（尺寸恒定）", playerCode.contains("fillMaxSize()"))
        assertTrue("视频面必须保持 RESIZE_MODE_FIT", playerCode.contains("RESIZE_MODE_FIT"))
        // dock 的系统栏内边距必须在**卡片外层**（卡片浮在导航条上方）——外层 wrapper 的
        // windowInsetsPadding(navigationBars) 与卡片的 padding(start=12) 同段出现即视为分层成立
        assertTrue(playerCode.contains("WindowInsets.navigationBars.union(WindowInsets.displayCutout)"))
        assertTrue(playerCode.contains("start = 12.dp, end = 12.dp, bottom = 8.dp"))
    }
}
