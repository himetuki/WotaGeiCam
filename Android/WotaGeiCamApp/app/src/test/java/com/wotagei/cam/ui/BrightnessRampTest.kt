package com.wotagei.cam.ui

import com.wotagei.cam.FakePrefs
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 亮度渐变的纯函数层（29 §5.5）+ 设置读取钳制 + 接线守卫（docs/plan/29 §五）。
 *
 * 窗口写入本体吃 `Window`（JVM 起不来），可测面拆两层：[BrightnessRampMath] 与
 * `WotaSettings` 两个读取器在此直测；「目的地回调必须驱动 ramp、播放页必须上报起播」
 * 用源码守卫钉死。
 */
class BrightnessRampTest {

    // -------------------------------------------------- ① 纯函数

    @Test
    fun `系统亮度换算_越界与坏上限回中庸`() {
        assertEquals(128f / 255f, BrightnessRampMath.systemFraction(128), 1e-6f)
        assertEquals(0f, BrightnessRampMath.systemFraction(0), 1e-6f)
        assertEquals(1f, BrightnessRampMath.systemFraction(255), 1e-6f)
        assertEquals("负值回 0.5（不崩不闪满屏）", 0.5f, BrightnessRampMath.systemFraction(-1), 1e-6f)
        assertEquals("超上限回 0.5", 0.5f, BrightnessRampMath.systemFraction(300), 1e-6f)
        assertEquals("上限为 0 的坏域回 0.5", 0.5f, BrightnessRampMath.systemFraction(0, 0), 1e-6f)
    }

    @Test
    fun `渐变取值_线性_越界钳到端点_零时长即到点`() {
        assertEquals(0.25f, BrightnessRampMath.valueAt(1750, 7000, 0f, 1f), 1e-4f)
        assertEquals(0.5f, BrightnessRampMath.valueAt(3500, 7000, 0f, 1f), 1e-4f)
        assertEquals("超出时长钳到 to", 1f, BrightnessRampMath.valueAt(9999, 7000, 0f, 1f), 1e-6f)
        assertEquals("负时刻钳到 from", 0.25f, BrightnessRampMath.valueAt(-1, 7000, 0.25f, 1f), 1e-6f)
        assertEquals("rampMs<=0 = 立即到 to", 1f, BrightnessRampMath.valueAt(0, 0, 0f, 1f), 1e-6f)
        // 向下渐变（理论上不用，但纯函数不许有方向假设）
        assertEquals(0.5f, BrightnessRampMath.valueAt(500, 1000, 1f, 0f), 1e-6f)
    }

    @Test
    fun `步数_约117ms一步_短渐变至少两步_零时长零步`() {
        assertEquals(0, BrightnessRampMath.stepCount(0))
        assertEquals("7s 渐变 ≈ 60 步（7000/117=59）", 59, BrightnessRampMath.stepCount(7000))
        assertEquals("1s 渐变 8 步（1000/117）", 8, BrightnessRampMath.stepCount(1000))
        assertEquals("300ms 渐变 2 步", 2, BrightnessRampMath.stepCount(300))
        assertEquals("200ms 已不足 2 步，钳到 2（不能只剩一次跳变）", 2, BrightnessRampMath.stepCount(200))
    }

    // -------------------------------------------------- ② 设置读取（钳制与默认）

    @Test
    fun `两条亮度设置的默认与钳制`() {
        val prefs = FakePrefs()
        assertEquals("启动亮屏默认 1s（29 §九）", 1, WotaSettings.cameraBrightenDelaySec(prefs))
        assertEquals("起播渐亮默认 1.0s", 10, WotaSettings.playerBrightenRampTenths(prefs))
        // 0 是合法档（关闭），不许被"坏值回默认"吞掉
        prefs.map[WotaSettings.KEY_CAMERA_BRIGHTEN_DELAY_SEC] = 0
        prefs.map[WotaSettings.KEY_PLAYER_BRIGHTEN_RAMP_TENTHS] = 0
        assertEquals(0, WotaSettings.cameraBrightenDelaySec(prefs))
        assertEquals(0, WotaSettings.playerBrightenRampTenths(prefs))
        // 越界回钳制域边界，不崩
        prefs.map[WotaSettings.KEY_CAMERA_BRIGHTEN_DELAY_SEC] = 99
        prefs.map[WotaSettings.KEY_PLAYER_BRIGHTEN_RAMP_TENTHS] = -5
        assertEquals(10, WotaSettings.cameraBrightenDelaySec(prefs))
        assertEquals(0, WotaSettings.playerBrightenRampTenths(prefs))
    }

    // -------------------------------------------------- ③ 接线守卫

    @Test
    fun `目的地回调与播放页必须接上亮度驱动`() {
        val main = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/MainActivity.kt"))
        val routeBody = KotlinSourceScan.bodyOf(main, "onRouteForWindowKeepOn")
        assertTrue("目的地回调必须驱动 brightnessRamp.onRoute（同一回调保证恢复顺序）", routeBody.contains("brightnessRamp.onRoute("))
        assertTrue(
            "MainActivity 必须暴露 onPlayerPlaybackStarted 给播放页",
            KotlinSourceScan.regionsOf(main, "onPlayerPlaybackStarted").isNotEmpty()
        )
        val player = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt"))
        assertTrue(
            "播放页起播必须上报 onPlaybackStarted（页面知道何时起播，写入收口在窗口拥有者）",
            player.contains("onPlaybackStarted()")
        )
        // 尺要能红：拆掉上报点后断言翻红
        assertFalse(
            player.replace("if (playing) onPlaybackStarted()", "if (playing) Unit").contains("onPlaybackStarted()")
        )
    }

    @Test
    fun `常亮驱动必须跟随系统而不是强制拉满`() {
        val code = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/WindowKeepOn.kt"))
        val enter = KotlinSourceScan.bodyOf(code, "enter")
        assertTrue(
            "enter 必须写 BRIGHTNESS_OVERRIDE_NONE（29 §5.2：撤销强制拉满）",
            enter.contains("BRIGHTNESS_OVERRIDE_NONE")
        )
        assertFalse("enter 不许再写 BRIGHTNESS_OVERRIDE_FULL", enter.contains("BRIGHTNESS_OVERRIDE_FULL"))
        // 尺要能红：改回拉满后断言翻红
        assertFalse(enter.replace("BRIGHTNESS_OVERRIDE_NONE", "BRIGHTNESS_OVERRIDE_FULL").contains("BRIGHTNESS_OVERRIDE_NONE"))
    }
}
