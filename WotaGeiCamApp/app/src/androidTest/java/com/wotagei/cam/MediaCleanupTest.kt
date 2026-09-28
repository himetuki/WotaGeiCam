package com.wotagei.cam

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.media.MediaActions
import com.wotagei.cam.media.MediaRepo
import com.wotagei.cam.media.MediaTag
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.record.VideoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「彻底删除必须清掉标签与回收站行」的取证测试（#14 / #16 的机器可验部分）。
 *
 *  定的就是这条：MediaStore `_ID` 有复用风险，删文件不清 `media_tag` 就会把旧标签
 * 串到后来的视频上。本测试**自己建一行、只删自己这一行**，不碰任何用户成片。
 *
 * 取证走 `sendStatus`（本机 ROM 会丢应用自己的 Log 标签，logcat 不可靠，见 CameraEnumTest）。
 */
@RunWith(AndroidJUnit4::class)
class MediaCleanupTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, android.os.Bundle().apply { putString("wota", text) })
    }

    private fun clipOf(id: Long, uri: Uri, name: String): VideoClip = VideoClip(
        id = id,
        uri = uri,
        name = name,
        dataPath = null,
        relativePath = VideoStore.RELATIVE_PATH,
        dateAddedSec = System.currentTimeMillis() / 1000L,
        sizeBytes = 0L,
        durationMs = 0L,
        width = 0,
        height = 0,
        mimeType = VideoStore.MIME_MP4,
        tags = emptySet(),
        isTrashed = false
    )

    /**
     * MediaStore 行是否还在：按 `_ID=?` 在集合 uri 上数行。
     *
     * 不能用 `query(单行 uri)` 的 `cursor.count`：那条路在本机测出来恒为 0（commit 刚成功也查不到），
     * 拿它断言"已删除"会退化成空断言 —— 假绿比不测更坏。
     */
    private fun rowCount(id: Long): Int = countBy("${MediaStore.Video.Media._ID}=?", arrayOf(id.toString()))

    private fun countBy(selection: String, args: Array<String>): Int = ctx.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        selection,
        args,
        null
    )?.use { it.count } ?: -1

    /** 单行 uri 上取 DISPLAY_NAME：查不到就返回 null，只用于诊断输出 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    @Test
    fun deleteForeverClearsTagsAndTrashRows() = runBlocking {
        val store = VideoStore(ctx)
        val uri = store.createPending(0)
        assertNotNull("建 pending 行失败（存储或权限问题）", uri)
        val created = uri!!
        // insert 返回的 uri 末段就是 _ID，比再查一次可靠
        val mediaId = created.lastPathSegment?.toLongOrNull() ?: error("uri 里取不到 _ID：$created")
        try {
            /**
             * 必须先写点字节：本机实测「0 字节的 pending 行 commit 成功（update 命中 1 行）但之后
             * 按 _ID / DISPLAY_NAME 都查不到，而集合里其他行照常可见」—— MediaProvider 不把空文件当有效媒体。
             * 不写这一段的话，后面「标签清零」「行数归零」两条断言会对着一条本来就不存在的行**空过**（假绿）。
             */
            ctx.contentResolver.openOutputStream(created)?.use { it.write(ByteArray(4096)) }
            assertTrue("commit 失败，测不下去", store.commit(created))
            val pendingName = queryDisplayName(created)
            line(
                "uri=$created _ID=$mediaId；按 _ID 命中=${rowCount(mediaId)}；" +
                    "按 DISPLAY_NAME 命中=${countBy("${MediaStore.Video.Media.DISPLAY_NAME}=?", arrayOf(pendingName ?: "?"))}；" +
                    "本应用可见视频行总数=${countBy("1=1", emptyArray())}"
            )
            val alive = rowCount(mediaId)
            assertEquals("commit 之后自己的行查不到，后面的断言都不可信", 1, alive)
            val name = store.displayName()
            val dao = MediaRepo.get(ctx).db().tagDao()

            dao.insert(
                MediaTag(mediaId = mediaId, path = name, tag = "wota-selftest", createdAt = System.currentTimeMillis())
            )
            val before = dao.observeAll().first().count { it.mediaId == mediaId }
            line("插入后该行的标签数=$before")
            assertEquals(1, before)

            MediaActions.get(ctx).deleteForever(listOf(clipOf(mediaId, created, name)), pop = {}, refresh = {})

            val after = dao.observeAll().first().count { it.mediaId == mediaId }
            line("删除后该行的标签数=$after；MediaStore 命中行数=${rowCount(mediaId)}")
            assertEquals("删完还剩残留标签 → id 复用时会串到新视频", 0, after)
            assertEquals("记录没被真删", 0, rowCount(mediaId))

            // 复跑一次确认幂等：对已消失的行再删不应抛
            MediaActions.get(ctx).deleteForever(listOf(clipOf(mediaId, created, name)), pop = {}, refresh = {})
            line("重复删除未抛异常，OK")
        } finally {
            // 只清自己这一行；失败也不影响用户数据
            if (rowCount(mediaId) > 0) runCatching { ctx.contentResolver.delete(created, null, null) }
            line("清理自建测试行完成，残留行数=${rowCount(mediaId)}")
        }
    }
}
