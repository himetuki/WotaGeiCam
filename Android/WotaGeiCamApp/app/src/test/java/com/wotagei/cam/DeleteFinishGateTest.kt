package com.wotagei.cam

import com.wotagei.cam.media.DeleteBatch
import com.wotagei.cam.media.DeleteTarget
import com.wotagei.cam.media.MediaListRefreshGate
import com.wotagei.cam.media.runDeleteFinish
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteFinishGateTest {
    private val target = DeleteTarget(
        mediaId = 1L,
        uri = "content://media/external/video/media/1",
        pathKey = "/Movies/clip.mp4",
        dataPath = "/Movies/clip.mp4",
        name = "clip.mp4",
        isTrashed = false,
        dateAddedSec = 100L,
        sizeBytes = 1_000L,
        relativePath = "Movies/"
    )
    private val deleted = DeleteBatch(listOf(target), failed = 0, permissionDenied = false)

    @Test
    fun `先关闸再删除并清账，出栈后才更新列表`() = runBlocking {
        val events = mutableListOf<String>()
        val gate = MediaListRefreshGate { events += "invalidate" }
        runDeleteFinish(
            popDelayMs = 0,
            refreshDelayMs = 0,
            hold = gate::hold,
            deleteAndReconcile = {
                assertTrue(gate.state.value)
                events += "cleanup"
                deleted
            },
            pop = {
                assertTrue(gate.state.value)
                events += "pop"
            },
            refresh = { events += "refresh" }
        )
        assertFalse(gate.state.value)
        assertEquals(listOf("cleanup", "pop", "invalidate", "refresh"), events)
    }

    @Test
    fun `全部删除未命中时留在当前页并恢复查询`() = runBlocking {
        var invalidations = 0
        var popped = false
        val gate = MediaListRefreshGate { invalidations++ }
        val missed = DeleteBatch(emptyList(), failed = 1, permissionDenied = false)
        val result = runDeleteFinish(
            popDelayMs = 0,
            refreshDelayMs = 0,
            hold = gate::hold,
            deleteAndReconcile = { missed },
            pop = { popped = true },
            refresh = {}
        )
        assertSame(missed, result)
        assertFalse(popped)
        assertFalse(gate.state.value)
        assertEquals(1, invalidations)
    }

    @Test
    fun `并行收尾由最后一个批次开闸且旧批次不能重复释放`() = runBlocking {
        var invalidations = 0
        val gate = MediaListRefreshGate { invalidations++ }
        val first = gate.hold()
        val second = gate.hold()
        first.close()
        first.close()
        assertTrue(gate.state.value)
        assertEquals(0, invalidations)
        second.close()
        assertFalse(gate.state.value)
        assertEquals(1, invalidations)
    }

    @Test
    fun `收尾中发生异常仍归还本批次的持有权`() = runBlocking {
        val gate = MediaListRefreshGate {}
        val first = gate.hold()
        val failure = IllegalStateException("fixture")
        val result = runCatching {
            runDeleteFinish(0, 0, gate::hold, { throw failure }, {}, {})
        }
        val thrown = result.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(failure.message, thrown?.message)
        // 协程恢复异步调用栈时可能复制异常，原异常仍在 cause 链中。
        assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
        assertTrue(gate.state.value)
        first.close()
        assertFalse(gate.state.value)
    }

    @Test
    fun `出栈引起取消也不会遗漏刷新`() = runBlocking {
        val refreshing = CompletableDeferred<Unit>()
        val gate = MediaListRefreshGate {}
        val caller = launch {
            runDeleteFinish(
                popDelayMs = 0,
                refreshDelayMs = 1,
                hold = gate::hold,
                deleteAndReconcile = { deleted },
                pop = { coroutineContext[kotlinx.coroutines.Job]?.cancel() },
                refresh = { refreshing.complete(Unit) }
            )
        }
        withTimeout(5_000) { refreshing.await() }
        caller.join()
        assertTrue(caller.isCancelled)
        assertFalse(gate.state.value)
    }
}
