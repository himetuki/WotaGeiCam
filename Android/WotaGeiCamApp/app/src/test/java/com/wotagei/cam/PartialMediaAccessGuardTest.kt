package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android 14+「选择照片和视频」部分授权的接线守卫（2026-10-20）。
 *
 * 平台语义：用户在权限框选那一档时系统授 READ_MEDIA_VISUAL_USER_SELECTED、**不授**
 * READ_MEDIA_VIDEO，两条互斥。四件套缺一都会把部分授权变成"等于没授"或"被误判成拒绝"：
 * ① manifest 不声明 ⇒ 那一档什么权限都落不下；② 启动门只认 READ_MEDIA_VIDEO ⇒ 选过
 * 部分授权的用户每次冷启都重弹框；③ 弹框回调按"任一 false"全判拒 ⇒ 部分授权当场弹
 * "去设置"；④ 设置页权限行只认全量 ⇒ 误报"未授予"。
 */
class PartialMediaAccessGuardTest {

    private val partial = "READ_MEDIA_VISUAL_USER_SELECTED"

    @Test
    fun `manifest 必须声明部分授权权限`() {
        val m = manifestText()
        assertTrue(
            "未声明 $partial：Android 14+ 用户选「选择照片和视频」时什么权限都拿不到",
            Regex("uses-permission\\s+android:name\\s*=\\s*\"android\\.permission\\.$partial\"")
                .containsMatchIn(m)
        )
    }

    @Test
    fun `启动权限门必须把部分授权视为已满足并成对请求`() {
        val f = codeOnly(mainSourceText("ui/MainActivity.kt"))
        val gate = f.substringAfter("private fun missingStartupPermissions(").substringBefore("\n}")
        assertTrue("启动门必须认 $partial（否则选过部分授权的用户每次冷启都重弹框）", gate.contains(partial))
    }

    @Test
    fun `弹框回调必须按全量或部分二选一判拒`() {
        val f = codeOnly(mainSourceText("ui/MainActivity.kt"))
        val launcher = f.substringAfter("val launcher = rememberLauncherForActivityResult(")
            .substringBefore("LaunchedEffect")
        assertTrue(
            "回调必须把媒体对（MEDIA_PERMISSION_KEYS）与其余权限分开判（任一 false 全判拒会把部分授权误判成拒绝）",
            launcher.contains("MEDIA_PERMISSION_KEYS") && launcher.contains("mediaDenied")
        )
        // 常量比较收口进顶层集合后，钉住集合本身必须含部分授权档（漏了它回调判拒就退回只认全量）
        val keys = f.substringAfter("val MEDIA_PERMISSION_KEYS = setOf(").substringBefore(")")
        assertTrue("媒体权限对必须包含部分授权档 $partial", keys.contains(partial))
    }

    @Test
    fun `设置页媒体权限行必须认部分授权`() {
        val f = codeOnly(mainSourceText("ui/SettingsScreen.kt"))
        val rows = f.substringAfter("private fun permissionRows(").substringBefore("\n}")
        assertTrue("权限行只认 READ_MEDIA_VIDEO 会把部分授权误报成未授予", rows.contains(partial))
    }

    private fun manifestText(): String {
        val rel = "src/main/AndroidManifest.xml"
        var dir: File = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val f = File(base, rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            val parent = dir.parentFile
                ?: throw AssertionError("找不到 AndroidManifest.xml——守卫不能在没有输入的情况下算通过")
            dir = parent
        }
        throw AssertionError("找不到 AndroidManifest.xml——守卫不能在没有输入的情况下算通过")
    }
}
