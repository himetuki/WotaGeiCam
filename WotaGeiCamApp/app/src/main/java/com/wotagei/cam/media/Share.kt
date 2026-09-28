package com.wotagei.cam.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.wotagei.cam.R
import java.io.File

/**
 * 分享：ACTION_SEND / ACTION_SEND_MULTIPLE，EXTRA_STREAM 一律先过 FileProvider，
 * 不赌 content Uri 的普适性；不指定目标，全部交给系统 chooser。
 */
object WotaShare {

    private const val AUTHORITY = "com.wotagei.cam.provider"

/** 分享失败要能在 logcat 里查到原因，不能只剩一句提示文案 */
private const val TAG = "WotaShare"

    /** @return 失败时的提示文案 res id；成功返回 null */
    fun share(context: Context, clips: List<VideoClip>): Int? {
        if (clips.isEmpty()) return R.string.media_share_failed
        val pairs = clips.mapNotNull { resolve(context, it) }
        if (pairs.isEmpty()) return R.string.media_share_failed
        val mime = pairs.first().second
        return try {
            val intent = if (pairs.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, pairs.first().first)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(pairs.map { it.first }))
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 调用方给的一律是 applicationContext（MediaActions 的构造），非 Activity 上下文起界面必须
            // 带 NEW_TASK，否则框架抛 AndroidRuntimeException；那异常又被下面的 catch 吞成「分享失败」，
            // 表现就是chooser 从来没出现过。工程里另外两处非 UI 起界面（BtSpeakerSheet、openAppSettings）都带了
            val chooser = Intent.createChooser(intent, context.getString(R.string.gallery_share_title)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            null
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "share: 无可接收的应用 ${e.message}")
            R.string.media_share_no_app
        } catch (e: Exception) {
            // 这里原先什么都不记：本次 NEW_TASK 缺失被框架抛的 AndroidRuntimeException 正好落在这里，
            // 症状只是「点了分享什么也没发生」，没有日志就查不到根因
            Log.e(TAG, "share failed: ${e.javaClass.simpleName} ${e.message}")
            R.string.media_share_failed
        }
    }

    /** content Uri → FileProvider Uri（拿不到真实路径或不在 FileProvider 白名单时退回原 content Uri） */
    private fun resolve(context: Context, clip: VideoClip): Pair<Uri, String>? {
        val mime = mimeOf(clip)
        val path = clip.dataPath
        if (!path.isNullOrEmpty()) {
            val file = File(path)
            if (file.exists()) {
                val providerUri = runCatching { FileProvider.getUriForFile(context, AUTHORITY, file) }.getOrNull()
                if (providerUri != null) return providerUri to mime
            }
        }
        return clip.uri to mime
    }

    /** MIME 按扩展名判断，未知扩展名兜底为通配视频类型 */
    private fun mimeOf(clip: VideoClip): String {
        val ext = clip.name.substringAfterLast('.', "").lowercase()
        val byExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (byExt != null && byExt.startsWith("video/")) return byExt
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "video/*"
        }
    }
}
