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

    /** API 29 走 legacy 外部存储，本来就能直删；30+ 才需要这个特殊访问权限 */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

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
