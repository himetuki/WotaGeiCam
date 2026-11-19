package com.wotagei.cam.player

import com.wotagei.cam.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTasksTest {
    private val clip = ExportDetails.Clip(0L, 500L, 1000L)
    private val track = ExportDetails.Track(SelectedTrack.CAP, -25L, TrackSync.Source.MANUAL)
    private val sourceA = "content://media/external/video/media/1"
    private val sourceB = "content://media/external/video/media/2"

    /** Unconfined 会在 start() 的栈内执行到第一个挂起点，等价覆盖 Main.immediate 快返。 */
    private fun withTasks(test: (ExportTasks) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            test(ExportTasks(scope))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `同步BUSY完成后两类闸门都能立即再开`() = withTasks { tasks ->
        for (details in listOf(clip, track)) {
            assertTrue(tasks.start(details, sourceA, "A") {
                assertEquals(details.kind, tasks.state.value[details.kind]?.request?.kind)
                ClipResult.Fail(ClipError.BUSY)
            })
            assertNull(tasks.state.value[details.kind])
            assertTrue(tasks.start(details, sourceA, "A") { ClipResult.Done(10L, "export") })
            assertNull(tasks.state.value[details.kind])
        }
        assertEquals(
            listOf(ExportKind.CLIP, ExportKind.CLIP, ExportKind.TRACK, ExportKind.TRACK),
            tasks.state.value.results.map { it.request.kind }
        )
    }

    @Test
    fun `两条任务离页完成后仍可依次确认且新任务不清旧结果`() = withTasks { tasks ->
        val clipDone = CompletableDeferred<ClipResult>()
        val trackDone = CompletableDeferred<ClipResult>()
        assertTrue(tasks.start(clip, sourceA, "A") { clipDone.await() })
        assertTrue(tasks.start(track, sourceB, "B") { trackDone.await() })
        assertFalse(tasks.start(clip, sourceB, "B") { error("同类型在途时不得起跑") })

        // 离页时无人订阅。两条独立完成后才从宿主读取。
        trackDone.complete(ClipResult.Done(12L, "track"))
        clipDone.complete(ClipResult.Done(11L, "clip"))
        val (first, second) = tasks.state.value.results
        assertEquals(ExportKind.TRACK, first.request.kind)
        assertEquals(sourceB, first.request.sourceUri)
        assertEquals(R.string.player_track_export_done, exportResultRes(first))
        assertEquals(ExportKind.CLIP, second.request.kind)
        assertEquals(sourceA, second.request.sourceUri)
        assertEquals(R.string.player_clip_done, exportResultRes(second))

        assertTrue(tasks.start(clip, sourceA, "A") { awaitCancellation() })
        tasks.acknowledge(first.request.taskId)
        tasks.acknowledge(first.request.taskId)
        assertEquals(listOf(second), tasks.state.value.results)
        tasks.acknowledge(second.request.taskId)
        assertTrue(tasks.state.value.results.isEmpty())
    }

    @Test
    fun `取消和迟到回调只影响所属任务`() = withTasks { tasks ->
        val callbacks = mutableListOf<(Float) -> Unit>()
        assertTrue(tasks.start(track, sourceB, "B") { ClipResult.Done(12L, "track") })
        assertTrue(tasks.start(clip, sourceA, "A") { report ->
            callbacks.add(report)
            awaitCancellation()
        })
        val old = checkNotNull(tasks.state.value.clip).request
        callbacks.single()(0.3f)
        tasks.cancel(old)
        assertNull(tasks.state.value.clip)
        val trackResult = tasks.state.value.results.single()
        assertEquals(R.string.player_track_export_done, exportResultRes(trackResult))

        assertTrue(tasks.start(clip, sourceB, "B") { report ->
            callbacks.add(report)
            awaitCancellation()
        })
        val current = checkNotNull(tasks.state.value.clip).request
        assertNotEquals(old.taskId, current.taskId)
        callbacks[1](0.25f)
        callbacks[0](0.95f)
        callbacks[1](0.15f)
        callbacks[1](Float.NaN)
        tasks.cancel(old)
        assertEquals(0.25f, checkNotNull(tasks.state.value.clip).progress, 0f)
        assertEquals(current, tasks.state.value.latestFor(sourceB))
        assertNull(tasks.state.value.latestFor(sourceA))
    }

    @Test
    fun `取消后屏蔽进度但已开始的提交仍可完成`() = withTasks { tasks ->
        val provider = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<(Float) -> Unit>()
        assertTrue(tasks.start(clip, sourceA, "A") { report ->
            callbacks.add(report)
            report(0.8f)
            // 模拟已获得提交许可且尚未返回的同步调用。
            withContext(NonCancellable) { provider.await() }
            ClipResult.Done(11L, "clip")
        })
        tasks.cancel(checkNotNull(tasks.state.value.clip).request)
        callbacks.single()(1f)
        val cancelling = checkNotNull(tasks.state.value.clip)
        assertTrue(cancelling.cancelling)
        assertEquals(0.8f, cancelling.progress, 0f)
        provider.complete(Unit)
        assertNull(tasks.state.value.clip)
        assertEquals(ClipResult.Done(11L, "clip"), tasks.state.value.results.single().payload)
    }

    @Test
    fun `同片优先最新任务而其他片不自动开窗`() = withTasks { tasks ->
        assertTrue(tasks.start(clip, sourceA, "A") { awaitCancellation() })
        assertTrue(tasks.start(track, sourceA, "A") { awaitCancellation() })
        assertEquals(ExportKind.TRACK, tasks.state.value.latestFor(sourceA)?.kind)
        assertNull(tasks.state.value.latestFor(sourceB))
        tasks.cancel(checkNotNull(tasks.state.value.track).request)
        assertTrue(tasks.start(track, sourceB, "B") { awaitCancellation() })
        assertEquals(ExportKind.CLIP, tasks.state.value.latestFor(sourceA)?.kind)
        assertEquals(ExportKind.TRACK, tasks.state.value.latestFor(sourceB)?.kind)
    }

    @Test
    fun `宿主销毁后不会遗留未进入协程体的槽`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasks = ExportTasks(scope)
        scope.cancel()
        assertFalse(tasks.start(clip, sourceA, "A") { error("宿主已清除") })
        assertNull(tasks.state.value.clip)
        assertTrue(tasks.state.value.results.isEmpty())
    }
}
