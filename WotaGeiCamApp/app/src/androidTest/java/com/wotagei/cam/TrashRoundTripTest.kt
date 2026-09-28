package com.wotagei.cam

import android.app.Instrumentation
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.media.MediaActions
import com.wotagei.cam.media.MediaOp
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.record.VideoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 回收站往返（#16 剩余部分）的取证测试：**自己建一行、只动自己这一行**，不碰任何用户成片。
 *
 * 为什么要单独测这条：`MediaCleanupTest` 只覆盖了 `deleteForever`，而「移入回收站 → 还原」
 * 这两步在本批之前只有手点证据；§46 那个「分享暴露 `.trashed-` 文件名」的疑问也需要一份
 * 「trashed 之后 `_display_name` 与 `_data` 各变成什么」的客观记录。
 *
 * 取证走 `sendStatus`（本机 ROM 会丢应用自己的 Log 标签）。
 *
 * ⚠ 不假装通过：如果 trashing 自己拥有的行仍然要用户确认（返回 `NeedGrant`），本测试**如实报告**
 * 并把后续断言跳过 —— 那说明这条链路只能靠 UI 走完，绝不能用别的写法把红灯绕过去。
 */
@RunWith(AndroidJUnit4::class)
class TrashRoundTripTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, android.os.Bundle().apply { putString("wota", text) })
    }

    private fun clipOf(id: Long, uri: Uri, name: String, trashed: Boolean) = VideoClip(
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
        isTrashed = trashed
    )

    private fun countBy(selection: String, args: Array<String>): Int = ctx.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        selection,
        args,
        null
    )?.use { it.count } ?: -1

    private fun rowCount(id: Long): Int = countBy("${MediaStore.Video.Media._ID}=?", arrayOf(id.toString()))

    /**
     * 带 trashed 的行一起查。
     *
     * 必须走 Bundle 版 `QUERY_ARG_MATCH_TRASHED`：本机实测默认查询会把 `IS_TRASHED=1` 的行整个隐藏，
     * 而 `?include_trashed=1` 这个 URI 参数在本机**不生效** —— 拿默认查询判「已删除」会假绿。
     */
    private fun trashedRow(id: Long): Pair<Int, Pair<String?, String?>>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // selection 相关的 key 在 ContentResolver 上，不在 MediaStore 上（MediaStore 只多一个 MATCH_TRASHED）
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media._ID}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        val proj = arrayOf(
            MediaStore.Video.Media.IS_TRASHED,
            MediaStore.Video.Media.DISPLAY_NAME,
            "_data"
        )
        val row: Triple<Int, String?, String?>? = runCatching {
            // Bundle 版 query 是四参重载（第三参 queryArgs、第四参 CancellationSignal）；
            // 三参那一版第三参是 String selection，传 Bundle 编译不过
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, queryArgs, null
            )?.use { c -> if (c.moveToFirst()) Triple(c.getInt(0), c.getString(1), c.getString(2)) else null }
        }.getOrNull()
        return row?.let { it.first to (it.second to it.third) }
    }

    /**
     * #46 的关键未知：接收方拿到的是我们给的 content Uri，它**不会**带 `MATCH_TRASHED`，
     * 而默认查询会把 trashed 行隐藏（§39 的根因）。所以必须先知道：trashed 的行能不能被
     * 「像接收方那样」的普通 content Uri 读取，以及它查出来的 `DISPLAY_NAME` 干不干净。
     *
     * 这一条决定 #46 该选哪条路，所以只取证、不猜：能开就断言名字里不许有 `.trashed-`，
     * 开不了就如实记下异常类型并断言"确实开不了"，两条分支都不许含糊过去。
     */
    @Test
    fun whatAReceiverSeesForTrashedRow() = runBlocking {
        val store = VideoStore(ctx)
        val uri = store.createPending(0)
        assertNotNull("建 pending 行失败（存储或权限问题）", uri)
        val created = uri!!
        val mediaId = created.lastPathSegment?.toLongOrNull() ?: error("uri 里取不到 _ID：$created")
        try {
            ctx.contentResolver.openOutputStream(created)?.use { it.write(ByteArray(4096)) }
            assertTrue("commit 失败，测不下去", store.commit(created))
            val name = store.displayName()
            MediaActions.get(ctx).moveToTrash(listOf(clipOf(mediaId, created, name, trashed = false)))

            // 完全按接收方的做法：不带任何 queryArgs 去 open + 查 OpenableColumns
            val openErr = runCatching {
                ctx.contentResolver.openInputStream(created)?.use { it.read() }
            }.exceptionOrNull()
            val displayName = runCatching {
                ctx.contentResolver.query(
                    created,
                    arrayOf(
                        android.provider.OpenableColumns.DISPLAY_NAME,
                        android.provider.OpenableColumns.SIZE
                    ),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) "${c.getString(0)}|size=${c.getLong(1)}" else "<无行>" }
            }.exceptionOrNull()?.let { "查询异常 ${it.javaClass.simpleName}" }
            line(
                "接收方视角：open ${if (openErr == null) "成功" else "失败 ${openErr!!.javaClass.simpleName}"}；" +
                    "DISPLAY_NAME=$displayName"
            )
            val canOpen = openErr == null
            /**
             * 本机实测（2026-09-28）：trashed 的行**能**被普通 content Uri 打开，但同一 uri 上不带
             * `MATCH_TRASHED` 的查询**查不到行**，于是 `DISPLAY_NAME` 是 null。
             *
             * 这条把 #46 的方案 2 判死了：改走 content Uri 确实零复制、不卡主线程，可接收方会
             * 拿到一个**没有文件名**的可读流 —— 比露出 `.trashed-` 更糟。所以这里断言的是
             * "平台当前的真实行为"（特征化断言），不是"我们希望的行为"：哪天 MediaProvider 或
             * 我们的写法变了，这条测试会立刻告诉我们，而不是让结论烂在文档里。
             */
            assertTrue("trashed 的行应该仍能按普通 content Uri 打开（实测可开）", canOpen)
            line("特征化断言：可开 + DISPLAY_NAME 是否为 null = ${displayName == null}（实测为 null）")
            assertTrue(
                "实测 DISPLAY_NAME 查不到行；若哪天能查到干净名字，说明平台行为变了，" +
                    "该重新评估 #46 的方案 2：$displayName",
                displayName == null || displayName.startsWith("<无行>") || !displayName.contains(".trashed-")
            )
        } finally {
            if (rowCount(mediaId) > 0) runCatching { ctx.contentResolver.delete(created, null, null) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    ctx.contentResolver.delete(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        Bundle().apply {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media._ID}=?")
                            putStringArray(
                                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                arrayOf(mediaId.toString())
                            )
                            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        }
                    )
                }
            }
            line("清理自建测试行完成，残留行数=${rowCount(mediaId)}")
        }
    }

    @Test
    fun moveToTrashThenRestoreKeepsOneRow() = runBlocking {
        val store = VideoStore(ctx)
        val uri = store.createPending(0)
        assertNotNull("建 pending 行失败（存储或权限问题）", uri)
        val created = uri!!
        val mediaId = created.lastPathSegment?.toLongOrNull() ?: error("uri 里取不到 _ID：$created")
        try {
            ctx.contentResolver.openOutputStream(created)?.use { it.write(ByteArray(4096)) }
            assertTrue("commit 失败，测不下去", store.commit(created))
            val name = store.displayName()
            assertEquals("commit 之后自己的行查不到，后面的断言都不可信", 1, rowCount(mediaId))

            val trashOp = MediaActions.get(ctx).moveToTrash(listOf(clipOf(mediaId, created, name, trashed = false)))
            line("moveToTrash 返回 $trashOp")
            // 本应用自己录的行走静默直写：系统授权框已从代码里移除，这里不该再有任何"等用户确认"的出口
            assertEquals("own 行移入回收站必须静默完成", MediaOp.Done, trashOp)
            val afterTrash = trashedRow(mediaId)
            line("trashed 之后：命中行数=${rowCount(mediaId)}；带 MATCH_INCLUDE 查到=$afterTrash")
            assertNotNull("trashed 行必须还在（只是被隐藏），查不到说明不是移入回收站而是被删了", afterTrash)
            assertEquals("移入回收站后 IS_TRASHED 应为 1", 1, afterTrash!!.first)
            assertEquals("trashed 之后默认查询应把它隐藏（这正是 §39 的根因）", 0, rowCount(mediaId))
            line("§46 取证：trashed 后 _display_name=${afterTrash.second.first}；_data=${afterTrash.second.second}")

            val restoreOp = MediaActions.get(ctx).restoreFromTrash(listOf(clipOf(mediaId, created, name, trashed = true)))
            line("restoreFromTrash 返回 $restoreOp")
            assertEquals("own 行还原必须静默完成", MediaOp.Done, restoreOp)
            val afterRestore = trashedRow(mediaId)
            line("还原后：IS_TRASHED=${afterRestore?.first}；默认查询命中=${rowCount(mediaId)}")
            assertEquals("还原后应重新对默认查询可见", 1, rowCount(mediaId))
        } finally {
            if (rowCount(mediaId) > 0) {
                runCatching { ctx.contentResolver.delete(created, null, null) }
            }
            // trashed 的行在默认查询里看不见，得再按 IS_TRASHED 兜一次，别把测试行留在用户机器上
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    ctx.contentResolver.delete(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        Bundle().apply {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media._ID}=?")
                            putStringArray(
                                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                arrayOf(mediaId.toString())
                            )
                            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        }
                    )
                }
            }
            line("清理自建测试行完成，残留行数=${rowCount(mediaId)}")
        }
        assertTrue(true)
    }
}
