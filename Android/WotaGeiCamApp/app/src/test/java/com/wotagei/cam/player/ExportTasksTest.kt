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
            assertTrue(tasks.start(details, sourceA, "A") { _, _ ->
                assertEquals(details.kind, tasks.state.value[details.kind]?.request?.kind)
                ClipResult.Fail(ClipError.BUSY)
            })
            assertNull(tasks.state.value[details.kind])
            assertTrue(tasks.start(details, sourceA, "A") { _, _ -> ClipResult.Done(10L, "export") })
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
        assertTrue(tasks.start(clip, sourceA, "A") { _, _ -> clipDone.await() })
        assertTrue(tasks.start(track, sourceB, "B") { _, _ -> trackDone.await() })
        assertFalse(tasks.start(clip, sourceB, "B") { _, _ -> error("同类型在途时不得起跑") })

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

        assertTrue(tasks.start(clip, sourceA, "A") { _, _ -> awaitCancellation() })
        tasks.acknowledge(first.request.taskId)
        tasks.acknowledge(first.request.taskId)
        assertEquals(listOf(second), tasks.state.value.results)
        tasks.acknowledge(second.request.taskId)
        assertTrue(tasks.state.value.results.isEmpty())
    }

    @Test
    fun `取消和迟到回调只影响所属任务`() = withTasks { tasks ->
        val callbacks = mutableListOf<(Float) -> Unit>()
        assertTrue(tasks.start(track, sourceB, "B") { _, _ -> ClipResult.Done(12L, "track") })
        assertTrue(tasks.start(clip, sourceA, "A") { report, _ ->
            callbacks.add(report)
            awaitCancellation()
        })
        val old = checkNotNull(tasks.state.value.clip).request
        callbacks.single()(0.3f)
        tasks.cancel(old)
        assertNull(tasks.state.value.clip)
        val trackResult = tasks.state.value.results.single()
        assertEquals(R.string.player_track_export_done, exportResultRes(trackResult))

        assertTrue(tasks.start(clip, sourceB, "B") { report, _ ->
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
        assertTrue(tasks.start(clip, sourceA, "A") { report, _ ->
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
        assertTrue(tasks.start(clip, sourceA, "A") { _, _ -> awaitCancellation() })
        assertTrue(tasks.start(track, sourceA, "A") { _, _ -> awaitCancellation() })
        assertEquals(ExportKind.TRACK, tasks.state.value.latestFor(sourceA)?.kind)
        assertNull(tasks.state.value.latestFor(sourceB))
        tasks.cancel(checkNotNull(tasks.state.value.track).request)
        assertTrue(tasks.start(track, sourceB, "B") { _, _ -> awaitCancellation() })
        assertEquals(ExportKind.CLIP, tasks.state.value.latestFor(sourceA)?.kind)
        assertEquals(ExportKind.TRACK, tasks.state.value.latestFor(sourceB)?.kind)
    }

    @Test
    fun `宿主销毁后不会遗留未进入协程体的槽`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasks = ExportTasks(scope)
        scope.cancel()
        assertFalse(tasks.start(clip, sourceA, "A") { _, _ -> error("宿主已清除") })
        assertNull(tasks.state.value.clip)
        assertTrue(tasks.state.value.results.isEmpty())
    }

    // ---- Y32：迟到终态按任务身份回填（等待方先报的假话被真话顶掉） ----

    @Test
    fun `预算到点先报BUSY_迟到Done按任务身份顶掉假话`() = withTasks { tasks ->
        val lateSink = CompletableDeferred<(ClipResult) -> Unit>()
        val notified = mutableListOf<ClipResult>()
        assertTrue(
            tasks.start(clip, sourceA, "A", onLateOutcome = { notified.add(it) }) { _, late ->
                lateSink.complete(late)
                // 等待预算到点：caller 先拿 BUSY 离开，owner 稍后才真正提交完。
                ClipResult.Fail(ClipError.BUSY)
            }
        )
        assertNull("槽已在等待方返回时归还", tasks.state.value.clip)
        assertEquals(
            "先入队的是等待方的 BUSY 假话",
            ClipResult.Fail(ClipError.BUSY), tasks.state.value.results.single().payload
        )

        val truth = ClipResult.Done(21L, "late-commit")
        lateSink.getCompleted().invoke(truth)
        assertEquals("迟到 Done 必须顶掉同任务的 BUSY 条目", truth, tasks.state.value.results.single().payload)
        assertEquals("迟到终态只入队一次（不追加第二条）", 1, tasks.state.value.results.size)
        assertEquals("宿主须收到迟到回调（索引刷新/提示依赖它）", listOf<ClipResult>(truth), notified)
    }

    @Test
    fun `迟到终态先于完成回调到达_完成回调不重复入队`() = withTasks { tasks ->
        assertTrue(
            tasks.start(clip, sourceA, "A") { _, late ->
                // owner 在等待方放弃前就已提交完成：迟到真话先落账，随后 run 返回等待方视角的 BUSY。
                late(ClipResult.Done(22L, "late-commit"))
                ClipResult.Fail(ClipError.BUSY)
            }
        )
        assertEquals("迟到 Done 与 run 返回值不得各入一条", 1, tasks.state.value.results.size)
        assertEquals(ClipResult.Done(22L, "late-commit"), tasks.state.value.results.single().payload)
        assertNull(tasks.state.value.clip)
    }

    @Test
    fun `取消后迟到终态仍按任务身份入账`() = withTasks { tasks ->
        val lateSink = CompletableDeferred<(ClipResult) -> Unit>()
        assertTrue(tasks.start(track, sourceB, "B") { _, late ->
            lateSink.complete(late)
            awaitCancellation()
        })
        val request = checkNotNull(tasks.state.value.track).request
        tasks.cancel(request)
        assertNull(tasks.state.value.track)
        assertTrue("取消本身不产生结果", tasks.state.value.results.isEmpty())

        lateSink.getCompleted().invoke(ClipResult.Done(23L, "late-sealed"))
        assertEquals(1, tasks.state.value.results.size)
        assertEquals(request, tasks.state.value.results.single().request)
        assertEquals(ClipResult.Done(23L, "late-sealed"), tasks.state.value.results.single().payload)
        // 清掉槽只是释放闸门；确认仍按 taskId 生效
        tasks.acknowledge(request.taskId)
        assertTrue(tasks.state.value.results.isEmpty())
    }

    @Test
    fun `重复迟到只记第一次_终态单调`() = withTasks { tasks ->
        val lateSink = CompletableDeferred<(ClipResult) -> Unit>()
        val notified = mutableListOf<ClipResult>()
        assertTrue(
            tasks.start(clip, sourceA, "A", onLateOutcome = { notified.add(it) }) { _, late ->
                lateSink.complete(late)
                ClipResult.Fail(ClipError.BUSY)
            }
        )
        val first = ClipResult.Done(24L, "first")
        lateSink.getCompleted().invoke(first)
        lateSink.getCompleted().invoke(ClipResult.Fail(ClipError.COMMIT))
        assertEquals("终态单调：后来的迟到值不改写已入账结局", first, tasks.state.value.results.single().payload)
        assertEquals("宿主也只收到第一次", listOf<ClipResult>(first), notified)
    }

    @Test
    fun `迟到回填不碰其他任务的已入队结果`() = withTasks { tasks ->
        val lateSink = CompletableDeferred<(ClipResult) -> Unit>()
        assertTrue(tasks.start(track, sourceB, "B") { _, _ -> ClipResult.Done(12L, "track") })
        assertTrue(tasks.start(clip, sourceA, "A") { _, late ->
            lateSink.complete(late)
            ClipResult.Fail(ClipError.BUSY)
        })
        lateSink.getCompleted().invoke(ClipResult.Done(25L, "clip-late"))
        val results = tasks.state.value.results
        assertEquals(2, results.size)
        assertEquals("track 的完成结果不受剪辑迟到回填影响", ExportKind.TRACK, results[0].request.kind)
        assertEquals(ClipResult.Done(12L, "track"), results[0].payload)
        assertEquals(ExportKind.CLIP, results[1].request.kind)
        assertEquals(ClipResult.Done(25L, "clip-late"), results[1].payload)
    }
}