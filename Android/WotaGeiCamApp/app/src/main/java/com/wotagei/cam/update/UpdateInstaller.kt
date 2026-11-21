package com.wotagei.cam.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.wotagei.cam.diag.Diag as Log
import java.io.File
import java.util.zip.ZipFile

/**
 * 下载完成的 APK 交系统安装器：FileProvider content Uri + ACTION_VIEW。
 *
 * 薄壳纪律：系统安装确认、「安装未知应用」授权仍归系统安装器，本应用不代办；
 * 本类只做最后一道**来源核对**与 Intent 组装。
 *
 * Y43 的教训：full/lite 共用 applicationId 与 versionCode，系统安装器只对「同包名」更新做
 * 签名强校验——同签名的另一 flavor、同版本号旧 r 构建都能通过。因此安装前必须把
 * **用户选中的发行 tag + flavor + ABI + 资产摘要**与归档内真实内容逐项对齐：
 * - 实际字节 SHA-256 = 下载完成时记录的 [UpdateDownloader.DownloadOutcome.Done.actualSha256]
 *   （以及探测得到的可信 [UpdateSelection.expectedSha256]，若有）；
 * - versionName = tag 推导值（lite 带 `-lite`），versionCode 不低于已安装版本；
 * - flavor 后缀一致（防止同签 full↔lite 互换）；
 * - ABI 段与归档 `lib/<abi>/` 相符（universal 恒通过；分包缺 so 时按不矛盾放行）；
 * - 包名 + 证书集合与已安装应用一致；拒绝 debuggable 归档（取证包与 release 共签名的 Y64 风险）。
 *
 * FileProvider paths 复用既有 `@xml/file_paths` 的 `<cache-path name="updates" path="updates/"/>`
 * （cacheDir/updates/ 已被覆盖，无需扩清单）。
 */
object UpdateInstaller {

    /** 与 media/Share.kt 同一枚 provider（authorities 单一，加一个包名的假面没意义） */
    private const val AUTHORITY = "com.wotagei.cam.provider"
    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val TAG = "WotaUpdate"

    /** 安装闸的拒绝原因（UI 按此显示失败原因，不一概说「下载完成」） */
    enum class InstallRejection {
        FILE_MISSING,
        UNREADABLE,
        DIGEST_MISMATCH,
        PACKAGE_MISMATCH,
        SIGNER_MISMATCH,
        DEBUGGABLE_ARCHIVE,
        VERSION_MISMATCH,
        FLAVOR_MISMATCH,
        ABI_MISMATCH,
        PROVIDER_FAILED,
        LAUNCH_FAILED
    }

    sealed interface InstallResult {
        /** 已拉起系统安装器（系统确认/授权仍由系统负责） */
        object Started : InstallResult

        /** 本应用最后一道闸拒绝：原因可用于 UI 失败文案 */
        data class Rejected(val reason: InstallRejection, val detail: String? = null) : InstallResult
    }

    /** 归档包的核对视图（纯 JVM 可构，便于把安装闸判据全测） */
    internal data class ArchiveIdentity(
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long,
        val debuggable: Boolean,
        val signers: Set<String>,
        val abis: Set<String>
    )

    /**
     * 校验并拉起安装器。**下载产出的 Done 是唯一生产入口**：它携带不可变选择与实际摘要；
     * 安装前重新读盘算 SHA，拒绝「下载完成后文件被替换」的 TOCTOU。
     */
    fun install(context: Context, artifact: UpdateDownloader.DownloadOutcome.Done): InstallResult =
        launchVerified(context, artifact.file, artifact.selection, artifact.actualSha256)

    /**
     * 旧调用兼容：只做包名/签名/（若给了期望哈希的）摘要核对。
     * 生产 UI 必须用 [install] 的 Done 重载，否则同签异变体仍可能通过。
     */
    fun install(context: Context, file: File): Boolean =
        launchVerified(context, file, null, null) is InstallResult.Started

    private fun launchVerified(
        context: Context,
        file: File,
        selection: UpdateSelection?,
        expectedActualSha256: String?
    ): InstallResult {
        if (!file.isFile) return InstallResult.Rejected(InstallRejection.FILE_MISSING)
        val computed = runCatching { UpdateDownloader.sha256Hex(file) }.getOrNull()
            ?: return reject(InstallRejection.UNREADABLE, "APK 读盘失败")
        val archive = readArchiveIdentity(context, file)
            ?: return reject(InstallRejection.UNREADABLE, "无法解析 APK 归档")
        val installed = readInstalledIdentity(context)
            ?: return reject(InstallRejection.UNREADABLE, "无法读取已安装应用信息")
        verifyArtifact(
            selection = selection,
            expectedActualSha256 = expectedActualSha256,
            computedSha256 = computed,
            archive = archive,
            installedPackage = context.packageName,
            installedSigners = installed.signers,
            installedVersionCode = installed.versionCode
        )?.let { return reject(it, describe(file, selection, archive, installed)) }

        val uri = runCatching { FileProvider.getUriForFile(context, AUTHORITY, file) }.getOrNull()
            ?: return reject(InstallRejection.PROVIDER_FAILED, "FileProvider Uri 组装失败")
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 设置页的 context 可能是 Activity 也可能被包一层；NEW_TASK 对前者无害、对后者是必须
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.Started
        }.getOrElse { e ->
            reject(InstallRejection.LAUNCH_FAILED, e.javaClass.simpleName)
        }
    }

    /**
     * 安装闸的**纯判据**（JVM 全测）：null = 通过，非 null = 拒绝原因。
     *
     * [expectedActualSha256] 是下载完成时记录的字节身份；它为 null 只表示旧调用没有记录，
     * 不表示可以跳过 [UpdateSelection.expectedSha256]（若有）。两项摘要都按忽略大小写精确比对。
     */
    internal fun verifyArtifact(
        selection: UpdateSelection?,
        expectedActualSha256: String?,
        computedSha256: String,
        archive: ArchiveIdentity,
        installedPackage: String,
        installedSigners: Set<String>,
        installedVersionCode: Long
    ): InstallRejection? {
        if (expectedActualSha256 != null &&
            !UpdateDownloader.sha256Matches(expectedActualSha256, computedSha256)
        ) {
            return InstallRejection.DIGEST_MISMATCH
        }
        if (selection?.expectedSha256 != null &&
            !UpdateDownloader.sha256Matches(selection.expectedSha256, computedSha256)
        ) {
            return InstallRejection.DIGEST_MISMATCH
        }
        if (archive.packageName != installedPackage) return InstallRejection.PACKAGE_MISMATCH
        if (!sameSignerSet(archive.signers, installedSigners)) return InstallRejection.SIGNER_MISMATCH
        // Y64：取证包（debugHook/probe）与 release 同证书但 debuggable=true；绝不能作为更新装入。
        if (archive.debuggable) return InstallRejection.DEBUGGABLE_ARCHIVE
        if (selection != null && selection.flavor != null) {
            val version = archive.versionName
                ?: return InstallRejection.VERSION_MISMATCH
            val expected = selection.expectedVersionName
            if (selection.flavor == "lite" && !version.endsWith("-lite")) {
                return InstallRejection.FLAVOR_MISMATCH
            }
            if (selection.flavor == "full" && version.endsWith("-lite")) {
                return InstallRejection.FLAVOR_MISMATCH
            }
            if (version != expected) return InstallRejection.VERSION_MISMATCH
            // 同 versionCode 允许覆盖（full/lite 共用 code）；低于已安装版本一律拒绝。
            if (archive.versionCode < installedVersionCode) return InstallRejection.VERSION_MISMATCH
        }
        if (selection != null) {
            if (selection.abi == UpdateAbi.UNKNOWN || !selection.abi.presentIn(archive.abis)) {
                return InstallRejection.ABI_MISMATCH
            }
        }
        return null
    }

    /**
     * 签名集合相等判据（纯函数，JVM 全测）：空集合一律 false——未签名包不能当成"和本应用一样"；
     * 集合相等（而不是"至少有一个交集"）防多签名包的子集冒充。
     */
    internal fun sameSignerSet(archive: Set<String>, installed: Set<String>): Boolean =
        archive.isNotEmpty() && archive == installed

    /** 归档里的 `lib/<abi>/` 下的 `.so` 段（ZIP 解析失败视为无从判断，返回空集合） */
    internal fun archiveAbis(file: File): Set<String> = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().mapNotNull { entry ->
                val name = entry.name
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    name.removePrefix("lib/").substringBefore('/').takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }.toSet()
        }
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION") // getPackageArchiveInfo(String, Int) 在 API 33+ 有重载，minSdk 29 这条仍可用
    private fun readArchiveIdentity(context: Context, file: File): ArchiveIdentity? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = runCatching { pm.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()
            ?: return null
        val signers = archive.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().toHex() }?.toSet() ?: emptySet()
        val debuggable = (archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return ArchiveIdentity(
            packageName = archive.packageName,
            versionName = archive.versionName,
            versionCode = archive.longVersionCode,
            debuggable = debuggable,
            signers = signers,
            abis = archiveAbis(file)
        )
    }

    private data class InstalledIdentity(val versionCode: Long, val signers: Set<String>)

    @Suppress("DEPRECATION") // getPackageInfo(String, Int) 在 API 33+ 有重载，minSdk 29 这条仍可用
    private fun readInstalledIdentity(context: Context): InstalledIdentity? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, flags) }
            .getOrNull() ?: return null
        val signers = installed.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray().toHex() }?.toSet() ?: return null
        return InstalledIdentity(installed.longVersionCode, signers)
    }

    private fun reject(reason: InstallRejection, detail: String?): InstallResult.Rejected {
        Log.e(TAG, "更新包安装闸拒绝：$reason ${detail ?: ""}")
        return InstallResult.Rejected(reason, detail)
    }

    private fun describe(
        file: File,
        selection: UpdateSelection?,
        archive: ArchiveIdentity,
        installed: InstalledIdentity
    ): String = buildString {
        append("file=").append(file.name)
        append(" pkg=").append(archive.packageName)
        append(" version=").append(archive.versionName)
        append(" code=").append(archive.versionCode).append('/').append(installed.versionCode)
        append(" abis=").append(archive.abis)
        if (selection != null) {
            append(" want=").append(selection.expectedVersionName)
            append(" abi=").append(selection.abi)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
