package com.wotagei.cam.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.wotagei.cam.diag.Diag as Log
import java.io.File

/**
 * 下载完成的 APK 交系统安装器：FileProvider content Uri + ACTION_VIEW。
 *
 * 薄壳纪律：系统安装确认、「安装未知应用」授权仍归系统安装器，本应用不代办；
 * 本类只做最后一道**来源核对**（包名 + 签名证书集合）与 Intent 组装。
 *
 * FileProvider paths 复用既有 `@xml/file_paths` 的 `<cache-path name="updates" path="updates/"/>`
 * （cacheDir/updates/ 已被覆盖，无需扩清单）。
 */
object UpdateInstaller {

    /** 与 media/Share.kt 同一枚 provider（authorities 单一，加一个包名的假面没意义） */
    private const val AUTHORITY = "com.wotagei.cam.provider"
    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val TAG = "WotaUpdate"

    /**
     * 返回 false = 文件不在了 / 包名或签名不符 / Uri 组装失败 / 没有可接手的安装器；不抛（UI 按失败提示）。
     *
     * **包名与签名校验是独立于 SHA 的最后一道闸**：段 1 被墙时探测会走镜像（段 2/3），
     * `expectedSha256` 此时为 null，镜像可以替换 APK 字节。系统安装器只对「同包名」的更新做
     * 签名强校验；一个不同包名的恶意 APK 会被当新应用安装。这里在拉起安装器前先核对
     * `packageName == 本应用包名` 且签名证书集合与已安装应用一致，把这条路堵死。
     */
    fun install(context: Context, file: File): Boolean {
        if (!file.isFile) return false
        if (!isTrustedUpdate(context, file)) {
            Log.e(TAG, "更新包校验失败（包名/签名与已安装应用不符），拒绝交给安装器：${file.name}")
            return false
        }
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

    /** 下载包必须是本应用 + 同一签名证书集合（见 [install] KDoc 的安全模型） */
    @Suppress("DEPRECATION") // getPackageArchiveInfo(String, Int) 在 API 33+ 有重载，minSdk 29 这条仍可用
    private fun isTrustedUpdate(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = runCatching { pm.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()
            ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
            ?: return false
        val archiveSigners = archive.signingInfo?.apkContentsSigners ?: return false
        val installedSigners = installed.signingInfo?.apkContentsSigners ?: return false
        return sameSignerSet(
            archiveSigners.map { it.toByteArray().toHex() }.toSet(),
            installedSigners.map { it.toByteArray().toHex() }.toSet()
        )
    }

    /**
     * 签名集合相等判据（纯函数，JVM 全测）：空集合一律 false——未签名包不能当成"和本应用一样"；
     * 集合相等（而不是"至少有一个交集"）防多签名包的子集冒充。
     */
    internal fun sameSignerSet(archive: Set<String>, installed: Set<String>): Boolean =
        archive.isNotEmpty() && archive == installed

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
