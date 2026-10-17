package com.wotagei.cam.media

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FileProvider 暴露面的最小化守卫。
 *
 * 旧 `file_paths.xml` 用四条 `path="."` 覆盖整个共享存储 / 整个 cache / 整个私有 files：
 * FileProvider 虽不导出，但 `WotaShare.resolve` 会把 MediaStore 的 `DATA` 原样喂给
 * `FileProvider.getUriForFile`。根一旦过宽，任何指向共享存储敏感文件的路径都会铸出可读 URI。
 * 现在只保留实际递出的三个目录；媒体库里非本应用目录的视频由 `Share.resolve` 自动退回
 * 原 content Uri（同样带读授权），功能不受影响。
 */
class FileProviderScopeGuardTest {

    private fun resFile(rel: String): String {
        var d = KotlinSourceScan.mainSourceFile("media/Share.kt").parentFile
        while (d != null) {
            val f = File(d, "res/$rel")
            if (f.isFile) return f.readText(Charsets.UTF_8)
            d = d.parentFile
        }
        throw AssertionError("找不到 res/$rel（资源被挪走/改名，守卫失去输入）")
    }

    @Test
    fun `FileProvider 只暴露三个实际目录_不得整盘或整cache`() {
        val xml = resFile("xml/file_paths.xml")
        assertTrue("成片目录必须覆盖 Movies/WotaGeiCam/", xml.contains("path=\"Movies/WotaGeiCam/\""))
        assertTrue("更新 APK 目录必须覆盖 cache/updates/", xml.contains("path=\"updates/\""))
        assertTrue("诊断分享目录必须覆盖 cache/share-out/", xml.contains("path=\"share-out/\""))
        assertFalse("不得再用 path=\".\" 的整盘/整 cache 根", xml.contains("path=\".\""))
        assertFalse("不再需要整 files 根", xml.contains("<files-path"))
        assertFalse("不再需要整 external-files 根", xml.contains("<external-files-path"))
    }
}
