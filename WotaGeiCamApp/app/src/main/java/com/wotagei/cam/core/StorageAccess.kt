package com.wotagei.cam.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 「所有文件访问」能力（用户 2026-09-28 定版：删除/回收站不许出现系统那个英文 ALLOW/DENY 框）。
 *
 * 分区存储下，改别人 app 建的视频行只有两条路：要么每次都由 `MediaProvider` 弹系统授权框，
 * 要么持有 `MANAGE_EXTERNAL_STORAGE` 让 MediaProvider 跳过归属校验。既然框不能要，就只剩这一条，
 * 没有第三种可能 —— 所以这里要的不是"降级方案"，而是把这件事做到底的唯一做法。
 *
 * 该权限只能在系统设置里由用户亲手开一次（`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`），
 * 那是设置页、不是确认框；开完之后本应用所有删除/回收站/重命名都静默完成。
 */
object WotaStorage {

    /**
     * 「所有文件访问」是否真的可用。**API 30+ 才存在这枚特殊权限**；API 29 虽然可以读全库，
     * 但跨应用写/删仍受 scoped storage 约束，且没有 `Environment.isExternalStorageManager()`。
     * 旧实现把 API 29 恒判 true，导致删除别人视频失败时给通用文案而不是"去开权限"，
     * 设置页也显示"已开启"（用户点了却无处可开）。这里把平台事实收成纯函数，JVM 可测。
     */
    internal fun hasAllFilesAccessFor(sdk: Int, isExternalStorageManager: Boolean): Boolean =
        sdk >= Build.VERSION_CODES.R && isExternalStorageManager

    /** 当前设备是否提供「所有文件访问」这项能力（API 30+） */
    fun supportsAllFilesAccess(): Boolean = supportsAllFilesAccessFor(Build.VERSION.SDK_INT)

    internal fun supportsAllFilesAccessFor(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.R

    fun hasAllFilesAccess(): Boolean =
        // 版本闸必须**先**短路：isExternalStorageManager 是 API 30 才有的静态方法，
        // API 29 上求值参数本身就会 NoSuchMethodError（不是返回 false）。
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching {
                hasAllFilesAccessFor(Build.VERSION.SDK_INT, Environment.isExternalStorageManager())
            }.getOrDefault(false)

    /**
     * 跳系统「所有文件访问权限」页，优先带上自己包名，ROM 不认这条就退回总页。
     *
     * 不用 `resolveActivity()` 探路：Android 11+ 的包可见性会为它单独报 `QueryPermissionsNeeded`，
     * 为一个跳转加 `<queries>` 不值当 —— 直接试，起不来再退。
     *
     * @return 有没有真的跳出去（false 时调用方给提示，别让用户点了没反应）
     */
    @SuppressLint("InlinedApi")
    fun openSettings(context: Context): Boolean {
        // API 29 没有这个系统页：两个 action 都起不来，直接返回 false，别让 UI 以为跳成功了
        if (!supportsAllFilesAccess()) return false
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
        return candidates.any { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}
