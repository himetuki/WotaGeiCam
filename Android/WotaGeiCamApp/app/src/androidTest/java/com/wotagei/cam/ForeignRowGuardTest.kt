package com.wotagei.cam

import android.app.Instrumentation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.core.WotaStorage
import com.wotagei.cam.media.MediaActions
import com.wotagei.cam.media.MediaOp
import com.wotagei.cam.media.MediaRepo
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

    @Test
    fun writesOutsideOurOwnDirStaySilentOnceAllFilesAccessIsGranted() = runBlocking {
        assertTrue("没拿到「所有文件访问」权限：先跑 appops 授权再测", WotaStorage.hasAllFilesAccess())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "wota-foreign-probe-${java.util.UUID.randomUUID()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // 关键：放进系统相机目录，这条行按目录判据就"不是本应用录的"
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val created = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("建探针行失败（存储或权限问题）", created)
        val uri = checkNotNull(created)
        val mediaId = checkNotNull(uri.lastPathSegment?.toLongOrNull())
        val resolver = ctx.contentResolver
        val repo = MediaRepo.get(ctx)
        val actions = MediaActions.get(ctx)
        var fixtureKey: String? = null
        var deleted = false
        try {
            // 先写字节，再以 Provider 返回的元数据构造操作快照。
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(ByteArray(4096)) }
            assertEquals(1, resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            ))
            assertEquals("探针行须已入库", 1, rowCount(mediaId))
            val clip = checkNotNull(repo.clipByIdOnce(mediaId))
            fixtureKey = clip.uri.toString()

            val trashOp = actions.moveToTrash(listOf(clip))
            line("moveToTrash → $trashOp，IS_TRASHED=${trashedFlag(mediaId)}")
            assertEquals("有权限时目录外的行也该静默进回收站", MediaOp.Done, trashOp)
            assertEquals("IS_TRASHED 应为 1", 1, trashedFlag(mediaId))
            assertEquals("回收站行应从默认查询隐藏", 0, rowCount(mediaId))
            val trashed = checkNotNull(repo.clipByIdOnce(mediaId))
            assertTrue(trashed.isTrashed)

            val restoreOp = actions.restoreFromTrash(listOf(trashed))
            line("restoreFromTrash → $restoreOp，IS_TRASHED=${trashedFlag(mediaId)}")
            assertEquals(MediaOp.Done, restoreOp)
            assertEquals("还原后应重新对默认查询可见", 1, rowCount(mediaId))
            val restored = checkNotNull(repo.clipByIdOnce(mediaId))
            assertTrue(!restored.isTrashed)

            val deleteOp = actions.deleteForever(listOf(restored), pop = {}, refresh = {})
            line("deleteForever → $deleteOp，_ID 命中=${rowCount(mediaId)}")
            assertEquals("目录外的行也该静默删掉", MediaOp.Done, deleteOp)
            deleted = true
            assertEquals("探针行应已被真删", 0, rowCount(mediaId))
            assertEquals("回收站查询也应查无此行", null, trashedFlag(mediaId))
        } finally {
            fixtureKey?.let { repo.db().trashDao().remove(it) }
            if (!deleted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
                resolver.delete(uri, Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                })
            }
        }
    }
}
