package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「常亮 + 高亮」的**源码结构守卫**（2026-10-07 真机缺陷 #103 之后的新结构）。
 *
 * 行为（窗口属性真的变没变）JVM 测不到（WindowManager 是 Android 运行时件），这里钉结构红线：
 * - 常亮必须成对：进页 `addFlags(FLAG_KEEP_SCREEN_ON)`，出页 `clearFlags`——删掉任一侧 =
 *   出页后屏幕仍常亮 / 进页不再常亮；
 * - 高亮必须成对：进页把 `screenBrightness` 覆写到 `BRIGHTNESS_OVERRIDE_FULL`，
 *   出页用进页前保存的原值写回（原值 -1/NONE 即原样写回，等价恢复跟随系统）；
 * - **顺序红线**：亮度整份下发必须在 `addFlags` **之后**（`window.attributes` 整份赋值会把窗口
 *   flags 换成客户端快照，flag 得先落在同一份活对象上）；
 * - **单一真源**：`keepScreenOn` / `screenBrightness` / `FLAG_KEEP_SCREEN_ON` 只允许出现在
 *   `ui/WindowKeepOn.kt`（旧的「录制页 View.keepScreenOn」实现已作废，不许复活成第二套）；
 * - 接线红线：目的地变化 → MainActivity 驱动（与方向/沉浸同一条 NavController 回调），
 *   不依赖任何页面的组合/重组时机；
 * - 不许碰 Settings.System：全局亮度是红线，覆写只许落在窗口属性上。
 */
class KeepOnBrightGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun body(file: String, fn: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedMain(file), fn))

    // ------------------------------------------------------------------ 成对与顺序

    @Test
    fun `常亮挂上与摘除成对出现在驱动里`() {
        val enter = body("ui/WindowKeepOn.kt", "enter")
        val exit = body("ui/WindowKeepOn.kt", "exit")
        assertTrue(
            "进页必须 addFlags(FLAG_KEEP_SCREEN_ON)（删掉 = 取景页不再常亮）",
            enter.contains("addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)")
        )
        assertTrue(
            "出页必须 clearFlags(FLAG_KEEP_SCREEN_ON)（删掉 = 出页后屏幕仍常亮）",
            exit.contains("clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)")
        )
    }

    @Test
    fun `高亮置满与按原值恢复成对出现在驱动里`() {
        val enter = body("ui/WindowKeepOn.kt", "enter")
        val exit = body("ui/WindowKeepOn.kt", "exit")
        assertTrue(
            "进页前必须保存窗口亮度原值（没有保存就没有恢复口径）",
            enter.contains("savedBrightness = window.attributes.screenBrightness")
        )
        assertTrue(
            "进页必须把窗口亮度覆写到 BRIGHTNESS_OVERRIDE_FULL（删掉 = 取景页不再拉满）",
            enter.contains("screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL")
        )
        assertTrue(
            "亮度写入必须是「基于 getter 活对象、整份赋回」的形态（否则下发不到 WindowManager）",
            enter.contains("window.attributes = window.attributes.apply")
        )
        assertTrue(
            "出页必须把保存的原值写回 screenBrightness（删掉 = 出页后亮度停在 1.0）",
            exit.contains("screenBrightness = savedBrightness")
        )
    }

    @Test
    fun `亮度整份下发排在 addFlags 之后`() {
        val enter = body("ui/WindowKeepOn.kt", "enter")
        val addFlagsAt = enter.indexOf("addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)")
        val brightnessAt = enter.indexOf("screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL")
        assertTrue("两个写入点都得在（否则顺序判据没有输入）", addFlagsAt >= 0 && brightnessAt >= 0)
        assertTrue(
            "addFlags 必须排在亮度整份下发之前：后者的整份赋值会把客户端快照覆盖到窗口 flags 上，" +
                "flag 必须先落在同一份活对象里（实测顺序对调 = 真机再次丢失 KEEP_SCREEN_ON）",
            addFlagsAt < brightnessAt
        )
    }

    // ------------------------------------------------------------------ 接线

    @Test
    fun `目的地变化驱动窗口模式且与方向沉浸同一条回调`() {
        val root = body("ui/MainActivity.kt", "WotaRoot")
        assertTrue(
            "目的地回调里必须同时驱动方向/沉浸与常亮（删掉 = 页面往返后常亮不恢复）",
            root.contains("applyPageMode(activity, destination.route)")
        )
        assertTrue(
            "常亮必须由目的地回调驱动（NavController 在 navigate/popBackStack/setGraph 同步派发，注册时回放当前目的地）",
            root.contains("onRouteForWindowKeepOn(destination.route)")
        )
    }

    @Test
    fun `MainActivity 把路由交给驱动`() {
        val dispatch = body("ui/MainActivity.kt", "onRouteForWindowKeepOn")
        assertTrue(
            "窗口驱动必须按路由走（删掉 = 路由变化不再切换常亮）",
            dispatch.contains("windowKeepOn.apply(window, route)")
        )
    }

    @Test
    fun `驱动按判定表选择进入或退出`() {
        val apply = body("ui/WindowKeepOn.kt", "apply")
        assertTrue(
            "进入/退出必须由判定表决定（判定表有全表单测，见 KeepOnRouteTableTest）",
            apply.contains("keepScreenOnForRoute(route)")
        )
        assertTrue("必须有进入支", apply.contains("enter(window)"))
        assertTrue("必须有退出支", apply.contains("exit(window)"))
    }

    // ------------------------------------------------------------------ 单一真源

    /** 主源码里允许出现窗口常亮/亮度符号的文件（相对 `com/wotagei/cam`）；只许这一个 */
    private val windowOwnerFiles = setOf("ui/WindowKeepOn.kt")

    @Test
    fun `窗口常亮与亮度覆写只有唯一实现`() {
        val anchor = KotlinSourceScan.mainSourceFile("ui/WindowKeepOn.kt")
        val root = checkNotNull(anchor.parentFile?.parentFile) { "定位不到主源码包根：$anchor" }
        val needles = listOf("keepScreenOn", "screenBrightness", "FLAG_KEEP_SCREEN_ON")
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { f ->
                // 只看代码（注释里提到旧实现不算第二套实现）
                val text = KotlinSourceScan.codeOnly(f.readText(Charsets.UTF_8))
                f.relativeTo(root).path.replace(File.separatorChar, '/') to needles.filter { text.contains(it) }
            }
            .filter { it.second.isNotEmpty() }
            .toList()
        assertEquals(
            "窗口常亮/亮度只许在 $windowOwnerFiles 里实现（两套并存会互相打架）——实际命中：$hits",
            windowOwnerFiles,
            hits.map { it.first }.toSet()
        )
        assertFalse(
            "录制页不许再自己写窗口属性（旧实现会在页面往返后静默失效，见 #103）",
            hits.any { it.first == "ui/CameraScreen.kt" }
        )
    }

    @Test
    fun `驱动与接线不许碰系统全局亮度`() {
        assertFalse(
            "ui/WindowKeepOn.kt 不许出现 Settings.System（全局亮度是红线，覆写只许落在窗口属性上）",
            maskedMain("ui/WindowKeepOn.kt").contains("Settings.System")
        )
        assertFalse(
            "ui/MainActivity.kt 不许出现 Settings.System（同上）",
            maskedMain("ui/MainActivity.kt").contains("Settings.System")
        )
    }
}
