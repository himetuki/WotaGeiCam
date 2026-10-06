package com.wotagei.cam.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 下载完成的 APK 交系统安装器：FileProvider content Uri + ACTION_VIEW。
 *
 * 薄壳纪律：这里只组装 Intent，**不做任何安装决策**——系统安装确认、
 * 「安装未知应用」授权都是系统安装器的职责，本应用不代办、不预判。
 *
 * FileProvider paths 复用既有 `@xml/file_paths` 的 `<cache-path path="."/>`
 * （cacheDir/updates/ 已被覆盖，无需扩清单）。
 */
object UpdateInstaller {

    /** 与 media/Share.kt 同一枚 provider（authorities 单一，加一个包名的假面没意义） */
    private const val AUTHORITY = "com.wotagei.cam.provider"
    private const val MIME_APK = "application/vnd.android.package-archive"

    /** 返回 false = 文件不在了 / Uri 组装失败 / 没有可接手的安装器；不抛（UI 按失败提示） */
    fun install(context: Context, file: File): Boolean {
        if (!file.isFile) return false
        val uri = runCatching { FileProvider.getUriForFile(context, AUTHORITY, file) }.getOrNull()
            ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 设置页的 context 可能是 Activity 也可能被包一层；NEW_TASK 对前者无害、对后者是必须
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
