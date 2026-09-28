package com.wotagei.cam

import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 相册两种作用域的可见性取证（#15 外部视频分支、#13 手机相册形态）。
 *
 * 真机上切到「手机相册」仍只有本应用自录的两条，需要区分是 ROM 把读权限收窄了还是查询写错。
 * 取证走 `sendStatus`（本机 ROM 会丢应用自己的 Log 标签，logcat 不可靠，见 CameraEnumTest）。
 */
@RunWith(AndroidJUnit4::class)
class MediaScopeProbeTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, android.os.Bundle().apply { putString("wota", text) })
    }

    private fun names(uri: Uri, selection: String?, args: Array<String>?): List<String> {
        val out = ArrayList<String>()
        ctx.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.IS_TRASHED),
            selection,
            args,
            null
        )?.use { c ->
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iT = c.getColumnIndex(MediaStore.Video.Media.IS_TRASHED)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: "?"
                out += if (iT >= 0) "$name/t${c.getInt(iT)}" else name
            }
        }
        return out
    }

    private fun names(selection: String?, args: Array<String>?) =
        names(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selection, args)

    /** Bundle 版查询参数（API 26+ 重载）：回收站条目只能靠 `QUERY_ARG_MATCH_TRASHED` 取回 */
    private fun namesWithArgs(queryArgs: android.os.Bundle): List<String> {
        val out = ArrayList<String>()
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.IS_TRASHED),
            queryArgs,
            null
        )?.use { c ->
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iT = c.getColumnIndex(MediaStore.Video.Media.IS_TRASHED)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: "?"
                out += if (iT >= 0) "$name/t${c.getInt(iT)}" else name
            }
        }
        return out
    }

    private fun granted(permission: String) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Test
    fun probeExternalVideoVisibility() {
        val mine = names("MIME_TYPE LIKE ? AND RELATIVE_PATH LIKE ?", arrayOf("video/%", "%WotaGeiCam%"))
        val scoped = names("MIME_TYPE LIKE ?", arrayOf("video/%"))
        val unfiltered = names(null, null)
        val withTrashed = names(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendQueryParameter("include_trashed", "1").build(),
            "MIME_TYPE LIKE ?",
            arrayOf("video/%")
        )
        line("sdk=${Build.VERSION.SDK_INT} target=${ctx.applicationInfo.targetSdkVersion}")
        line("RES=${granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)} RMV=${granted("android.permission.READ_MEDIA_VIDEO")}")
        line("mine=${mine.size} $mine")
        line("scoped=${scoped.size} $scoped")
        line("unfiltered=${unfiltered.size} $unfiltered")
        line("include_trashed=${withTrashed.size} $withTrashed")
        val viaBundle = namesWithArgs(android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        })
        line("QUERY_ARG_MATCH_TRASHED=${viaBundle.size} $viaBundle")
        assertTrue("AllVideos 作用域必须包含本应用自录条目", scoped.containsAll(mine))
    }
}
