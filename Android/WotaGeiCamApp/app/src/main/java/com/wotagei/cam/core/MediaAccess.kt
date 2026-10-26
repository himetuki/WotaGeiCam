package com.wotagei.cam.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 媒体读取权限的**四态**（2026-10-26，对比播放右槽选片失败的运行时自省）。
 *
 * # 为什么需要它
 * 对比播放选右片走「系统相册选择 → 回程 URI → MediaStore 反查 id」，而 MediaStore 查询返回什么
 * 完全由本应用的媒体权限决定：
 * - 全量 [FULL_READ]：全机 video 行可见，选谁都能反查到；
 * - 部分授权 [PARTIAL_READ]（Android 14+ 权限框的「选择照片和视频」）：只有用户预选过的项 +
 *   本应用自己创建的文件可见。别人录的、下载的视频在查询里**根本不存在**，选中后必然报
 *   「无法读取所选视频」；
 * - 未授权 [NONE]：只剩本应用自录的行；
 * - [ALL_FILES]（「所有文件访问」）：不受上述过滤限制。
 *
 * **本应用自录的行在任何一态下都可见**——所以「切到手机相册页签仍能看到自己录的视频」不能证明
 * 权限正常，这条判据曾经把人带偏过。要在运行时区分，只能查权限本身，不能从列表内容反推。
 *
 * # 为什么部分授权要单列
 * 启动权限门（`ui/MainActivity.kt` 的 `missingStartupPermissions`）把部分授权视为「已满足」，
 * 因此这类用户**永远不会再看到系统权限框**，设置页也显示「已授予」。界面上没有任何地方告诉
 * 他「只能读到部分视频」——这正是需要运行时提示的原因：提示不是重复申请权限，而是把这条
 * 隐形的限制说清楚，并给出「去设置」的可操作出口。
 *
 * # 与 [WotaStorage] 的关系
 * 「所有文件访问」不是媒体权限，但它同样决定 MediaStore 可见性（MediaProvider 对持有它的应用
 * 跳过归属校验），所以放在同一个状态里优先判定：只要它开着，就不必再关心部分/全量。
 */
enum class MediaAccessState {
    /** 「所有文件访问」已开启：MediaStore 不做本应用可见性过滤 */
    ALL_FILES,

    /** 全量媒体读权限（Android 13+ 的 READ_MEDIA_VIDEO / 以下 READ_EXTERNAL_STORAGE） */
    FULL_READ,

    /** Android 14+ 部分授权：只有用户挑过的项 + 本应用自建文件可见 */
    PARTIAL_READ,

    /** 媒体读权限整条缺失 */
    NONE
}

/**
 * 纯函数：四态判定（JVM 全测，平台事实不进 UI）。
 *
 * [partialRead] 只在 API 34+ 有意义——34 以下没有这枚权限，传 true 也按未授权处理，
 * 避免调用方漏写版本闸时把「未知权限」误当成部分授权。
 */
internal fun mediaAccessStateFor(
    sdk: Int,
    fullRead: Boolean,
    partialRead: Boolean,
    allFiles: Boolean
): MediaAccessState = when {
    allFiles -> MediaAccessState.ALL_FILES
    fullRead -> MediaAccessState.FULL_READ
    partialRead && sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> MediaAccessState.PARTIAL_READ
    else -> MediaAccessState.NONE
}

/** 选片失败时该不该弹「去开权限」提示：只有部分授权与未授权两态需要（全量/所有文件访问下失败另有原因） */
fun needsMediaAccessHint(state: MediaAccessState): Boolean =
    state == MediaAccessState.PARTIAL_READ || state == MediaAccessState.NONE

/**
 * 媒体读权限按 SDK 分档：33+ 走细分权限，以下回退旧存储权限。
 * 与 `WotaSettings.mediaReadPermission()` 同一支（后者委托到这里），避免两处版本闸分叉。
 */
fun mediaReadPermissionFor(sdk: Int): String =
    if (sdk >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE

/** 运行时探测当前四态（失败归 [MediaAccessState.NONE]，绝不抛） */
fun mediaAccessState(context: Context): MediaAccessState {
    val sdk = Build.VERSION.SDK_INT
    val full = granted(context, mediaReadPermissionFor(sdk))
    val partial = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    return mediaAccessStateFor(sdk, full, partial, WotaStorage.hasAllFilesAccess())
}

/** 诊断导出/日志用的可读标签（枚举名进导出件不好读，统一走这里） */
fun mediaAccessLabel(state: MediaAccessState): String = when (state) {
    MediaAccessState.ALL_FILES -> "所有文件访问"
    MediaAccessState.FULL_READ -> "全量授权"
    MediaAccessState.PARTIAL_READ -> "部分授权(仅选中项)"
    MediaAccessState.NONE -> "未授权"
}

/**
 * 纯函数：回程 URI 是不是「MediaStore 视频集合条目」，形如
 * `content://media/external/video/media/<数字>`。
 *
 * 只有这一形态的末段才是 MediaStore 的 `_ID`。系统照片选择器的回程
 * `content://media/picker/0/…/media/<数字>` authority 同样是 `media`，但数字是选择器自己的
 * 命名空间——拿它去查 MediaStore 不是查无行，就是命中同号的**另一条视频**（静默挂错片）。
 * `ContentUris.parseId` 只要求"最后一段是数字"，不校验来源，所以判据必须单独写在这里。
 */
internal fun isMediaStoreVideoUriParts(authority: String?, segments: List<String>): Boolean =
    authority == "media" && segments.size == 4 && segments[0].isNotEmpty() &&
        segments[1] == "video" && segments[2] == "media" && segments[3].all { it.isDigit() }

/** [isMediaStoreVideoUriParts] 的 Uri 包装（null 安全） */
fun isMediaStoreVideoUri(uri: Uri?): Boolean =
    uri != null && isMediaStoreVideoUriParts(uri.authority, uri.pathSegments)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
