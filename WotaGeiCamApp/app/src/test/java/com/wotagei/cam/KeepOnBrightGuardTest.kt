package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制页「常亮 + 高亮」的**源码结构守卫**（产品裁决：进录制页 = 屏幕常亮 + 窗口亮度拉满，
 * 离开页面恢复进页前原状）。
 *
 * 行为（窗口属性真的变没变）JVM 测不到（WindowManager 是 Android 运行时件），这里钉结构红线：
 * - 常亮必须成对：本页 View 挂 keepScreenOn = true，onDispose 摘掉（false）——删掉恢复侧 =
 *   出页后屏幕仍常亮；
 * - 高亮必须成对：screenBrightness 置 BRIGHTNESS_OVERRIDE_FULL（1.0f 逐窗口覆写，无需任何
 *   权限），onDispose 用进页前保存的原值写回（原值 -1/NONE 即原样写回，等价恢复跟随系统）；
 * - 不许碰 Settings.System：全局亮度是红线，覆写只许落在窗口属性上。
 */
class KeepOnBrightGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun cameraScreenBody(): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedMain("ui/CameraScreen.kt"), "CameraScreen"))

    @Test
    fun `常亮挂上与摘除成对出现在录制页`() {
        val body = cameraScreenBody()
        assertTrue(
            "进页必须 keepScreenOn = true（删掉 = 录制页不再常亮）",
            body.contains("keepScreenOn = true")
        )
        assertTrue(
            "onDispose 必须 keepScreenOn = false 摘除（删掉 = 出页后屏幕仍常亮）",
            body.contains("keepScreenOn = false")
        )
    }

    @Test
    fun `高亮置满与按原值恢复成对出现在录制页`() {
        val body = cameraScreenBody()
        assertTrue(
            "进页前必须保存窗口亮度原值（没有保存就没有恢复口径）",
            body.contains("val savedBrightness")
        )
        assertTrue(
            "进页必须把窗口亮度覆写到 BRIGHTNESS_OVERRIDE_FULL（删掉 = 录制页不再拉满）",
            body.contains("BRIGHTNESS_OVERRIDE_FULL")
        )
        assertTrue(
            "onDispose 必须把保存的原值写回 screenBrightness（删掉 = 出页后亮度停在 1.0）",
            body.contains("screenBrightness = savedBrightness")
        )
    }

    @Test
    fun `录制页不许碰系统全局亮度`() {
        assertFalse(
            "ui/CameraScreen.kt 不许出现 Settings.System（全局亮度是红线，覆写只许落在窗口属性上）",
            maskedMain("ui/CameraScreen.kt").contains("Settings.System")
        )
    }
}
