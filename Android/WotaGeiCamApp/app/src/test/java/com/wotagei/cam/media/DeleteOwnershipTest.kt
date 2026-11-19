package com.wotagei.cam.media

import com.wotagei.cam.source.KotlinSourceScan
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R09 的确定性账目测试：
 * 1. ContentResolver `delete()` 的**影响行数**是唯一成功判据（返回 0 行 ≠ 成功）；
 * 2. sidecar 只随真正删掉的行清理；
 * 3. 调用方（屏组合作用域）在删除途中被取消，独立所有者仍把整批删完并继续清账；
 * 4. 触摸文件系统的动作跑在所有者线程，不回到调用方线程；
 * 5. 源码钉扎：清账必须按 `(mediaId, pathKey/uri)` 身份对，不许退回裸 ID。
 */
class DeleteOwnershipTest {

    private fun target(id: Long) = DeleteTarget(
        mediaId = id,
        uri = "content://media/external/video/media/$id",
        pathKey = "/storage/emulated/0/Movies/WotaGeiCam/Camera/VID_$id.mp4",
        dataPath = "/storage/emulated/0/Movies/WotaGeiCam/Camera/VID_$id.mp4",
        name = "VID_$id.mp4",
        isTrashed = false
    )

    @Test
    fun `删除返回0行不记成功也不清sidecar`() = runBlocking {
        val sidecars = ArrayList<Long>()
        val batch = deleteBatch(
            targets = listOf(target(1)),
            deleteRow = { RowDeleteResult(rows = 0) },
            clearSidecar = { sidecars += it.mediaId }
        )
        assertEquals("0 行不是成功", emptyList<DeleteTarget>(), batch.deleted)
        assertEquals("0 行必须计入失败", 1, batch.failed)
        assertTrue("没删掉就不许清 sidecar", sidecars.isEmpty())
    }

    @Test
    fun `混合批次只清真正删掉的身份对`() = runBlocking {
        val sidecars = ArrayList<Long>()
        val batch = deleteBatch(
            targets = listOf(target(1), target(2), target(3)),
            deleteRow = { if (it.mediaId == 2L) RowDeleteResult(rows = 0) else RowDeleteResult(rows = 1) },
            clearSidecar = { sidecars += it.mediaId }
        )
        assertEquals(listOf(1L, 3L), batch.deleted.map { it.mediaId })
        assertEquals(1, batch.failed)
        assertEquals("sidecar 只跟成功删除的条目走", listOf(1L, 3L), sidecars)
    }

    @Test
    fun `权限异常单独记账`() = runBlocking {
        val batch = deleteBatch(
            targets = listOf(target(1)),
            deleteRow = { RowDeleteResult(rows = 0, permissionDenied = true, error = SecurityException("denied")) },
            clearSidecar = {}
        )
        assertEquals(1, batch.failed)
        assertTrue("只有 SecurityException 才置权限位", batch.permissionDenied)
    }

    /**
     * 核心证伪：调用方在第一批物理删除后就被取消（播放页退页），独立所有者仍要
     * 删完剩余条目并写回完整账目。退回"删循环跑在调用方协程里"的实现时这里会红。
     */
    @Test
    fun `调用方取消后所有者仍删完并交账`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val deletedIds = CopyOnWriteArrayList<Long>()
        val physical = CountDownLatch(2)

        val caller = launch(Dispatchers.Default) {
            runOnDeleteOwner(owner) {
                val batch = deleteBatch(
                    targets = listOf(target(1), target(2)),
                    deleteRow = { t ->
                        if (t.mediaId == 1L) {
                            entered.complete(Unit)
                            gate.await()
                        }
                        physical.countDown()
                        RowDeleteResult(rows = 1)
                    },
                    clearSidecar = {}
                )
                deletedIds.addAll(batch.deleted.map { it.mediaId })
            }
        }

        assertTrue("第一行删除必须先进入挂起点", entered.await())
        caller.cancel()
        assertTrue(
            "所有者线程必须在调用方取消后才被放行（证明取消没有传到独立作用域）",
            gate.complete(Unit)
        )
        assertTrue(
            "调用方取消后剩余行仍须删完（旧实现会停在第一条）",
            physical.await(5, TimeUnit.SECONDS)
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (deletedIds.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals("所有者必须交回完整账目", listOf(1L, 2L), deletedIds.toList())
    }

    @Test
    fun `删除与sidecar动作跑在所有者线程`() = runBlocking {
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "delete-owner-test") }
        val threads = CopyOnWriteArrayList<String>()
        try {
            val owner = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
            runOnDeleteOwner(owner) {
                deleteBatch(
                    targets = listOf(target(1), target(2)),
                    deleteRow = {
                        threads += Thread.currentThread().name
                        RowDeleteResult(rows = 1)
                    },
                    clearSidecar = { threads += Thread.currentThread().name }
                )
            }
        } finally {
            exec.shutdownNow()
        }
        assertEquals(4, threads.size)
        assertTrue("文件删除/sidecar 不许回到调用方线程：$threads", threads.all { it.startsWith("delete-owner-test") })
    }

    @Test
    fun `源码钉扎_删除所有权与身份对清账`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/MediaActions.kt"))
        val del = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "deleteOneByOne"))
        assertTrue("物理删除必须交给独立所有者", del.contains("runOnDeleteOwner(deleteOwner)"))
        assertTrue("成功判据必须看影响行数", del.contains("attempt.rows"))

        val cleanup = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "cleanupDeleted"))
        assertTrue("Room 清账必须在事务里", cleanup.contains("withTransaction"))
        assertTrue("标签必须按身份对清", cleanup.contains("dao.clearIdentity("))
        assertTrue("系列成员必须按身份对清", cleanup.contains("seriesDao.clearIdentity("))
        assertFalse("不许退回裸 ID 清标签（ID 复用会误清新片）", cleanup.contains("dao.clearFor("))
        assertFalse("不许退回裸 ID 清系列（ID 复用会误删新片成员）", cleanup.contains("seriesDao.clearFor("))

        val fin = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "finishDelete"))
        assertTrue(
            "调用方已取消（退页）时不许再替它出栈：pop 必须过存活令牌",
            fin.contains("if (callerToken.isActive()) pop()")
        )
        assertTrue(
            "UI 门面必须把调用屏的作用域当存活令牌（不是恒真）",
            src.contains("callerToken = DeleteCallerToken { scope.isActive }")
        )

        // SQL 断言吃**原文**：@Query 的语句本身是字符串字面量，codeOnly 会把内容遮成空格
        val db = KotlinSourceScan.mainSourceText("media/WotaDb.kt")
        assertTrue("标签身份对 SQL 必须双键", db.contains("DELETE FROM media_tag WHERE mediaId = :mediaId AND path = :path"))
        assertTrue("系列身份对 SQL 必须双键", db.contains("DELETE FROM series_part WHERE mediaId = :mediaId AND uri = :uri"))
    }
}
