package com.wotagei.cam.bp

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 采集用例（四条热路径，2026-10-24 定版）：
 * ① 冷启动到首页首帧（startActivityAndWait 返回 = 首帧已出）；
 * ② 首页→相机预览（Camera2 起流 + GL 渲染首段，等 2s 覆盖引擎路径）；
 * ③ 预览→开始/停止录制（录制钮比例坐标，覆盖录制引擎与收尾入库）；
 * ④ 录制完成→媒体库列表加载（缩略图入口，覆盖 MediaRepo/GalleryScreen/ThumbLoader）。
 *
 * 坐标口径：比例坐标（与 baseline/raw 手工采集同位点），避免写死分辨率。
 * 权限：采集前经 uiAutomation 授 CAMERA/RECORD_AUDIO/READ_MEDIA_VIDEO + appops
 * MANAGE_EXTERNAL_STORAGE——全新安装无授权时相机不打开，②③④ 全部落空。
 *
 * 源集纪律：com.android.test 模块的 instrumentation 代码放 src/main/（放 src/androidTest/
 * 会产出零测试 APK——connected 任务报 "no tests to run"，2026-10-24 实证）。
 */
@RunWith(AndroidJUnit4::class)
class HotPathsProfileTest {

    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    // 必须以 TestRule 注册：collect 依赖框架 apply() 初始化的内部状态
    //（内联 BaselineProfileRule().collect(...) 报 currentDescription 未初始化，2026-10-24 实证）
    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun collectHotPaths() = baselineRule.collect(
        PACKAGE,
        includeInStartupProfile = true
    ) {
        grantPermissions()
        pressHome()
        // ① 冷启动到首页首帧
        startActivityAndWait()
        device.waitForIdle(5_000)
        val w = device.displayWidth
        val h = device.displayHeight
        // ② 首页→相机预览：相机起流与 GL 首段在首帧后异步发生
        Thread.sleep(2_000)
        // ③ 预览→开始/停止录制
        device.click((w * 0.5275f).toInt(), (h * 0.877f).toInt())
        Thread.sleep(5_000)
        device.click((w * 0.5275f).toInt(), (h * 0.877f).toInt())
        Thread.sleep(3_000)
        // ④ 录制完成→媒体库列表加载
        device.click((w * 0.4388f).toInt(), (h * 0.883f).toInt())
        Thread.sleep(3_000)
        device.pressBack()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.grantPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (p in listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_MEDIA_VIDEO"
        )) {
            runCatching { instrumentation.uiAutomation.grantRuntimePermission(PACKAGE, p) }
        }
        runCatching {
            instrumentation.uiAutomation.executeShellCommand(
                "appops set $PACKAGE MANAGE_EXTERNAL_STORAGE allow"
            ).close()
        }
    }

    private companion object {
        const val PACKAGE = "com.wotagei.cam"
    }
}
