package com.wotagei.cam

import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.media.MediaActions
import com.wotagei.cam.media.MediaOp
import com.wotagei.cam.media.MediaRepo
import com.wotagei.cam.media.MediaTag
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
        instr.sendStatus(android.app.Activity.RESULT_OK, Bundle().apply { putString("wota", text) })
    }

    @Test
    fun deleteForeverClearsTagsAndTrashRows() = runBlocking {
        val store = VideoStore(ctx)
        val uri = store.createPending(0)
        assertNotNull("建 pending 行失败（存储或权限问题）", uri)
        val created = checkNotNull(uri)
        val mediaId = checkNotNull(created.lastPathSegment?.toLongOrNull())
        val repo = MediaRepo.get(ctx)
        val db = repo.db()
        val actions = MediaActions.get(ctx)
        var fixtureTag: MediaTag? = null
        var fixtureUri: String? = null
        var deleted = false
        try {
            // 0 字节的行即使清了 pending 也可能查不到。先写字节，确保下面的行数断言有对照。
            checkNotNull(ctx.contentResolver.openOutputStream(created)).use { it.write(ByteArray(4096)) }
            assertTrue("commit 失败，测不下去", store.commit(created))
            val clip = checkNotNull(repo.clipByIdOnce(mediaId)) { "公开后查不到测试行" }
            val key = clip.uri.toString()
            fixtureUri = key
            val tag = MediaTag(
                mediaId = mediaId,
                path = clip.dataPath ?: key,
                tag = "wota-selftest",
                createdAt = System.currentTimeMillis()
            )
            fixtureTag = tag
            db.tagDao().insert(tag)
            assertTrue(tag.tag in db.tagDao().tagsOf(mediaId))

            // 同 URI 的过期快照必须让 Provider 匹配到 0 行，真实行与标签都应保留。
            val stale = clip.copy(sizeBytes = clip.sizeBytes + 1)
            var popCalls = 0
            val staleResult = actions.deleteForever(listOf(stale), pop = { popCalls++ }, refresh = {})
            assertTrue(staleResult is MediaOp.Message)
            assertEquals("未删除就不能出栈", 0, popCalls)
            assertEquals(clip.sizeBytes, repo.clipByIdOnce(mediaId)?.sizeBytes)
            assertTrue(tag.tag in db.tagDao().tagsOf(mediaId))

            assertEquals(MediaOp.Done, actions.moveToTrash(listOf(clip)))
            val trashed = checkNotNull(repo.clipByIdOnce(mediaId)) { "回收站查不到测试行" }
            assertTrue(trashed.isTrashed)
            assertTrue(db.trashDao().observeAll().first().any { it.uri == key })

            val result = actions.deleteForever(listOf(trashed), pop = {}, refresh = {})
            assertEquals(MediaOp.Done, result)
            deleted = true
            assertEquals(null, repo.clipByIdOnce(mediaId))
            assertTrue(tag.tag !in db.tagDao().tagsOf(mediaId))
            assertTrue(db.trashDao().observeAll().first().none { it.uri == key })

            // 重复请求应明确未命中，也不能把同身份后来写入的账目误清。
            db.tagDao().insert(tag)
            assertTrue(actions.deleteForever(listOf(trashed), pop = {}, refresh = {}) is MediaOp.Message)
            assertTrue(tag.tag in db.tagDao().tagsOf(mediaId))
            line("测试行已删除，过期请求与重复请求均保留未命中行的账目")
        } finally {
            fixtureTag?.let { db.tagDao().clearIdentity(it.mediaId, it.path) }
            fixtureUri?.let { db.trashDao().remove(it) }
            if (!deleted) runCatching {
                val resolver = ctx.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    resolver.delete(created, Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    })
                } else {
                    resolver.delete(created, null, null)
                }
            }
        }
    }
}
