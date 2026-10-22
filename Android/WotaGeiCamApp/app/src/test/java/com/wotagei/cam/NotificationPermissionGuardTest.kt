package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `POST_NOTIFICATIONS`（Android 13+）权限面守卫（2026-10-18）。
 *
 * 缺陷：内录前台服务用常驻通知承载会话（含「停止内录」钮），但 manifest 没声明、代码没申请
 * 通知权限 ⇒ Android 13/14 上通知不可见，用户失去自建停止入口。
 *
 * 边界：只在**用户打开内录开关**时申请（不扩首启权限面）；拒绝也不阻断内录。
 */
class NotificationPermissionGuardTest {

    private fun manifestText(): String {
        val rel = "src/main/AndroidManifest.xml"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var hops = 0
        while (dir != null && hops <= 6) {
            val d = dir
            val candidates = listOf(
                File(d, rel),
                File(File(d, "app"), rel),
                File(File(d, "WotaGeiCamApp"), rel),
                File(File(File(d, "WotaGeiCamApp"), "app"), rel)
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            dir = d.parentFile
            hops++
        }
        throw AssertionError("找不到 AndroidManifest.xml——守卫不能在没有输入的情况下算通过")
    }

    @Test
    fun `manifest必须声明通知权限`() {
        assertTrue(
            "缺 POST_NOTIFICATIONS：内录前台服务通知在 Android 13+ 不可见",
            manifestText().contains("android.permission.POST_NOTIFICATIONS")
        )
    }

    @Test
    fun `通知权限只在打开内录开关时申请且拒绝不阻断`() {
        val raw = mainSourceText("ui/CameraScreen.kt")
        val screen = codeOnly(raw)
        val flat = flatten(screen)
        assertTrue("必须用 RequestPermission 契约", screen.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(
            "必须版本闸到 33+（低版本没有这枚运行时权限）",
            flat.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU")
        )
        assertTrue(
            "申请的是 POST_NOTIFICATIONS",
            flat.contains("notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)")
        )
        // 授权回执必须继续走内录授权链（拒绝也继续；回调参数不使用 → 不能拿结果当门）
        val launcher = flat.substringAfter("val notifPermission = rememberLauncherForActivityResult(")
            .substringBefore("val enableCapture")
        assertTrue(
            "权限回执必须继续拉起内录授权（拒绝也继续，否则功能被通知权限阻断）",
            launcher.contains("launchCaptureConsent(capture, captureConsent, showTip, app)")
        )
        assertFalse(
            "不得把授权结果当门（`if (granted)` 才继续）",
            launcher.contains("if (granted)")
        )
        // 不得混进首启权限面（missingStartupPermissions 是禁入面）
        val activity = codeOnly(mainSourceText("ui/MainActivity.kt"))
        assertFalse(
            "通知权限不得混进首启权限申请（不扩首启权限面）",
            activity.contains("POST_NOTIFICATIONS")
        )
    }

    @Test
    fun `前台服务通知渠道仍由启动期创建`() {
        val app = codeOnly(mainSourceText("WotaApp.kt"))
        assertTrue("启动期必须建内录通知渠道", app.contains("createCaptureChannel()"))
        assertTrue("渠道 id 与前台服务一致", app.contains("CaptureFgService.CHANNEL_ID"))
    }
}
