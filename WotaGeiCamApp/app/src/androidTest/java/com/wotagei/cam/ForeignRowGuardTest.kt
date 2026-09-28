package com.wotagei.cam

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContentResolver
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.core.WotaStorage
import com.wotagei.cam.media.MediaActions
import com.wotagei.cam.media.MediaOp
import com.wotagei.cam.media.VideoClip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「删除/回收站全程不出现系统英文授权框」的取证测试（#55 的第二版语义）。
 *
 * 定版做法是拿「所有文件访问」权限让 `MediaProvider` 跳过归属校验，而不是每次都弹框：
 * 所以这里刻意把探针行建在 `DCIM/Camera`（**不是**我们自己的目录），
 * 三种写操作必须**全部静默成功**才算达标 —— 一旦哪天有人把授权框加回来，这里就会红。
 *
 * 权限由 `adb shell appops set --uid com.wotagei.cam MANAGE_EXTERNAL_STORAGE allow` 预先给上，
 * 真机上则由用户在系统设置里开一次。测试自己建行、只删自己这一行，不碰任何用户成片。
 *
 * 取证走 `sendStatus`（本机 ROM 会丢应用自己的 Log 标签，logcat 不可靠，见 CameraEnumTest）。
 */
@RunWith(AndroidJUnit4::class)
class ForeignRowGuardTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, Bundle().apply { putString("wota", text) })
    }

    private fun countBy(selection: String, args: Array<String>): Int = ctx.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        selection,
        args,
        null
    )?.use { it.count } ?: -1

    private fun rowCount(id: Long): Int = countBy("${MediaStore.Video.Media._ID}=?", arrayOf(id.toString()))

    /** trashed 行在默认查询里看不见，必须用 Bundle 版 + MATCH_INCLUDE 才查得到（本机实测 URI 参数不生效） */
    private fun trashedFlag(id: Long): Int? = runCatching {
        val bundle = Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(id.toString())
            )
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media._ID}=?")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.IS_TRASHED),
            bundle,
            null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
    }.getOrNull()

    private fun clipOf(id: Long, uri: Uri, name: String): VideoClip = VideoClip(
        id = id,
        uri = uri,
        name = name,
        dataPath = null,
        relativePath = "DCIM/Camera",
        dateAddedSec = System.currentTimeMillis() / 1000L,
        sizeBytes = 0L,
        durationMs = 0L,
        width = 0,
        height = 0,
        mimeType = "video/mp4",
        tags = emptySet(),
        isTrashed = false
    )

    @Test
    fun writesOutsideOurOwnDirStaySilentOnceAllFilesAccessIsGranted() = runBlocking {
        assertTrue("没拿到「所有文件访问」权限：先跑 appops 授权再测", WotaStorage.hasAllFilesAccess())
        val name = "wota-foreign-probe.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // 关键：放进系统相机目录，这条行按目录判据就"不是本应用录的"
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val created = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("建探针行失败（存储或权限问题）", created)
        val uri = created!!
        val mediaId = uri.lastPathSegment?.toLongOrNull() ?: error("uri 里取不到 _ID：$uri")
        try {
            // 必须先写点字节：0 字节的行按 _ID 查不到，断言会对着不存在的行空过（假绿）
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(ByteArray(4096)) }
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
            assertEquals("探针行没真正入库，后面的断言都不可信", 1, rowCount(mediaId))

            val clip = clipOf(mediaId, uri, name)
            val actions = MediaActions.get(ctx)

            val trashOp = actions.moveToTrash(listOf(clip))
            line("moveToTrash → $trashOp；IS_TRASHED=${trashedFlag(mediaId)}")
            assertEquals("有权限时别人目录的行也该静默进回收站", MediaOp.Done, trashOp)
            assertEquals("IS_TRASHED 应为 1", 1, trashedFlag(mediaId))
            assertEquals("trashed 后默认查询应把它隐藏", 0, rowCount(mediaId))

            val restoreOp = actions.restoreFromTrash(listOf(clipOf(mediaId, uri, name)))
            line("restoreFromTrash → $restoreOp；IS_TRASHED=${trashedFlag(mediaId)}")
            assertEquals(MediaOp.Done, restoreOp)
            assertEquals("还原后应重新对默认查询可见", 1, rowCount(mediaId))

            val deleteOp = actions.deleteForever(listOf(clip), pop = {}, refresh = {})
            line("deleteForever → $deleteOp；_ID 命中=${rowCount(mediaId)}")
            assertEquals("别人目录的行也该静默删掉，不弹任何系统框", MediaOp.Done, deleteOp)
            assertEquals("探针行应已被真删", 0, rowCount(mediaId))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertEquals("回收站本地表也该跟着清干净", null, trashedFlag(mediaId))
            }
        } finally {
            if (rowCount(mediaId) > 0) runCatching { ctx.contentResolver.delete(uri, null, null) }
            line("清理自建探针行完成，残留行数=${rowCount(mediaId)}")
        }
    }
}
