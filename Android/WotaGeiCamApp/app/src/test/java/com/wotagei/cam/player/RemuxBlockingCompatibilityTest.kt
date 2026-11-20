package com.wotagei.cam.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Y31/Y32：无损剪辑/选轨导出的**独立等待侧**与公开终态真话。只跑真实 ClipExporter/TrackExporter，
 * 桩只阻塞 Provider 同步入口（insert/open/commit），不替生产决定放弃、回收或提交。
 *
 * 验收（审查卡）：
 * - `插入阻塞 → 等待方按预算返回、闸门可再用`：预算到点等待方先拿 BUSY，闸门是单 owner 零等待队列
 *   （在途时新请求直接 BUSY，不排队）；迟到 URI/FD 仍由原 owner 登记并回收一次，不继续公开提交；
 *   上一 owner 收口后同一执行器上的下一次导出必须能完成（Done）。
 * - 公开终态按任务身份回填（Y32）：已领许可的 commit 在等待方离开后才返回成功时，迟到 Done 必须
 *   如实回填给常驻宿主（onLateOutcome），不许把"错过等待"谎报成没写成、也不许删已封轨成片。
 */
class RemuxBlockingCompatibilityTest {

    @Test
    fun `Y31 剪辑插入阻塞时等待方按预算返回_BUSY不排队且闸门可再用`() = platform { p ->
        val probe = Probe(p)
        probe.enable(INSERT)
        p.script("clockStepMs", 1L)
        val exporter = p.clipExporter()
        val caller = launchClip(p, exporter)
        var endedBeforeRelease = false
        try {
            check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) { "未进入真实 insert 阻塞：${probe.detail()}" }
            // insert 在导出主体之前：生产主循环还没来得及建立任何检查点，只能靠独立等待侧计时。
            p.script("clockStepMs", 1_000_000L)
            endedBeforeRelease = caller.completed.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)
            assertTrue("预算到点必须先结束等待方，不能等插入返回", endedBeforeRelease)
            assertNull("等待方不得收到生产异常", caller.error.get())
            assertEquals(
                "等待方视角只能报 BUSY（真话由 owner 迟到回填）",
                "BUSY", p.call(caller.result.get()!!, "getReason").toString()
            )
            assertEquals("insert 必须仍阻塞，不许靠平台提前返回冒充预算", 1L, probe.proceed.count)
            // 闸门零等待队列：上一 owner 未收口时，新请求必须立即 BUSY（不排队堆积）
            assertEquals(
                "在途时新请求必须立即 BUSY",
                "BUSY", p.call(runBlocking { p.clipExport(p.clipExporter()) }, "getReason").toString()
            )
        } finally {
            probe.disable()
            probe.proceed.countDown()
            caller.close()
            assertTrue("放行后 owner 必须在有界时间内收口", p.awaitOwnerIdle(CLIP, CLEANUP_WAIT_MS))
        }
        assertFalse("迟到 insert 返回后不得继续公开提交", p.events.contains("pending.commit"))
        assertFalse("放弃后不得继续建 muxer/封轨", p.events.contains("muxer.start"))
        assertEquals("迟到 pending URI 由原 owner 回收一次", 1, p.events.count { it == "pending.delete" })
        assertEquals("插入阶段从未打开输出 FD", 0, p.events.count { it == "fd.close" })

        // 闸门可再用：上一 owner 收口后，同一执行器上的下一次导出必须能完成
        p.script("clockStepMs", 0L)
        assertEquals("Done", runBlocking { p.clipExport(exporter) }.javaClass.simpleName)
        assertEquals("第二次导出必须真正入库", 2, p.events.count { it == "pending.insert" })
        assertEquals(1, p.events.count { it == "pending.commit" })
        assertTrue("第二次导出必须真正封轨写出", p.events.contains("muxer.stop"))
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(CLIP, CLEANUP_WAIT_MS))
    }

    @Test
    fun `Y31 剪辑开FD阻塞时等待方按预算返回_迟到FD由原owner关闭一次`() = platform { p ->
        val probe = Probe(p)
        probe.enable(OPEN)
        p.script("clockStepMs", 1L)
        val caller = launchClip(p, p.clipExporter())
        var endedBeforeRelease = false
        try {
            check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) { "未进入真实 open 阻塞：${probe.detail()}" }
            p.script("clockStepMs", 1_000_000L)
            endedBeforeRelease = caller.completed.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)
            assertTrue("预算到点必须先结束等待方，不能等开 FD 返回", endedBeforeRelease)
            assertNull("等待方不得收到生产异常", caller.error.get())
            assertEquals(
                "等待方视角只能报 BUSY（真话由 owner 迟到回填）",
                "BUSY", p.call(caller.result.get()!!, "getReason").toString()
            )
            assertEquals("open 必须仍阻塞，不许靠平台提前返回冒充预算", 1L, probe.proceed.count)
        } finally {
            probe.disable()
            probe.proceed.countDown()
            caller.close()
            assertTrue("放行后 owner 必须在有界时间内收口", p.awaitOwnerIdle(CLIP, CLEANUP_WAIT_MS))
        }
        assertFalse("迟到 FD 返回后不得继续公开提交", p.events.contains("pending.commit"))
        assertEquals("迟到 FD 必须由原 owner 关闭一次", 1, p.events.count { it == "fd.close" })
        assertEquals("未封轨输出由原 owner 回收一次", 1, p.events.count { it == "pending.delete" })
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(CLIP, CLEANUP_WAIT_MS))
    }

    @Test
    fun `Y31 选轨插入阻塞时等待方按预算返回_迟到URI回收且闸门可再用`() = platform { p ->
        val probe = Probe(p)
        probe.enable(INSERT)
        p.script("clockStepMs", 1L)
        val exporter = p.trackExporter()
        val caller = launchTrack(p, exporter)
        var endedBeforeRelease = false
        try {
            check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) { "未进入真实 insert 阻塞：${probe.detail()}" }
            p.script("clockStepMs", 1_000_000L)
            endedBeforeRelease = caller.completed.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)
            assertTrue("预算到点必须先结束等待方，不能等插入返回", endedBeforeRelease)
            assertNull("等待方不得收到生产异常", caller.error.get())
            assertEquals(
                "等待方视角只能报 BUSY（真话由 owner 迟到回填）",
                "BUSY", p.call(caller.result.get()!!, "getReason").toString()
            )
            assertEquals("insert 必须仍阻塞，不许靠平台提前返回冒充预算", 1L, probe.proceed.count)
        } finally {
            probe.disable()
            probe.proceed.countDown()
            caller.close()
            assertTrue("放行后 owner 必须在有界时间内收口", p.awaitOwnerIdle(TRACK, CLEANUP_WAIT_MS))
        }
        assertFalse("迟到 insert 返回后不得继续公开提交", p.events.contains("pending.commit"))
        assertEquals("迟到 pending URI 由原 owner 回收一次", 1, p.events.count { it == "pending.delete" })
        assertEquals("插入阶段从未打开输出 FD", 0, p.events.count { it == "fd.close" })

        p.script("clockStepMs", 0L)
        assertEquals("Done", runBlocking { p.trackExport(exporter) }.javaClass.simpleName)
        assertEquals("第二次导出必须真正入库", 2, p.events.count { it == "pending.insert" })
        assertEquals(1, p.events.count { it == "pending.commit" })
        assertTrue("第二次导出必须真正封轨写出", p.events.contains("muxer.stop"))
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(TRACK, CLEANUP_WAIT_MS))
    }

    @Test
    fun `Y32 已领许可的commit在等待方离开后返回成功_迟到Done按任务身份回填`() = platform { p ->
        val probe = Probe(p)
        probe.enable(COMMIT)
        p.script("clockStepMs", 1L)
        val late = Collections.synchronizedList(mutableListOf<Any?>())
        val caller = launchClip(p, p.clipExporter(), onLate = { late.add(it) })
        var endedBeforeRelease = false
        try {
            check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) { "未进入真实 commit 阻塞：${probe.detail()}" }
            p.script("clockStepMs", 1_000_000L)
            endedBeforeRelease = caller.completed.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)
            assertTrue("预算到点必须先结束等待方，不能等 commit 返回", endedBeforeRelease)
            assertNull("等待方不得收到生产异常", caller.error.get())
            assertEquals(
                "等待方视角只能报 BUSY（真话由 owner 迟到回填）",
                "BUSY", p.call(caller.result.get()!!, "getReason").toString()
            )
            assertTrue("封轨必须在 commit 之前已经正常完成", p.events.contains("muxer.stop"))
            assertEquals("已领许可的 commit 必须仍阻塞", 1L, probe.proceed.count)
        } finally {
            probe.disable()
            probe.proceed.countDown()
            caller.close()
            assertTrue("放行后 owner 必须在有界时间内收口", p.awaitOwnerIdle(CLIP, CLEANUP_WAIT_MS))
        }
        assertEquals("已进入的许可返回成功后不得再发起第二次公开", 1, p.events.count { it == "pending.commit" })
        assertFalse("已封轨成片不得因等待方离开被删行（删行=丢成片）", p.events.contains("pending.delete"))
        assertEquals("迟到真话必须回填且只回填一次", 1, late.size)
        assertEquals("真话是提交成功 Done", "Done", late.single()!!.javaClass.simpleName)
        assertEquals("按任务身份带成片 id", 42L, p.call(late.single()!!, "getMediaId") as Long)
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(CLIP, CLEANUP_WAIT_MS))
    }

    /**
     * Y32（已审出的缺口）：等待方**取消**后，已领许可的 commit 返回成功。旧实现把取消当成
     * "不需要回填"，迟到 Done 被静默吞掉——相册不刷新、任务停在取消态，可成片已经入库。
     * 取消只代表等待方离开，公开终态是已经发生的事实，必须按任务身份回填一次。
     */
    @Test
    fun `Y32 等待方取消后已领许可的commit返回成功_迟到Done仍回填且封轨标记清除`() = platform { p ->
        val probe = Probe(p)
        probe.enable(COMMIT)
        p.script("clockStepMs", 1L)
        p.script("commitResults", intArrayOf(1, 1))
        val row = File(temporary.root, "seal-late-done/out42.mp4").apply { parentFile.mkdirs() }
        p.script("rowData", row.absolutePath)
        val marker = File(row.absolutePath + ".sealed")
        val late = Collections.synchronizedList(mutableListOf<Any?>())
        val caller = launchClip(p, p.clipExporter(), onLate = { late.add(it) })
        try {
            check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) { "未进入真实 commit 阻塞：${probe.detail()}" }
            assertTrue("封轨即落 SealMarker（commit 尚在途）", marker.isFile)
            caller.job.cancel()
            assertTrue("取消必须立刻结束等待方", caller.completed.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS))
            assertTrue("等待方因取消离开（不是生产异常）", caller.error.get() is CancellationException)
            assertNull("取消的等待方没有结果", caller.result.get())
            assertEquals("commit 必须仍阻塞，不许靠平台提前返回冒充取消", 1L, probe.proceed.count)
        } finally {
            probe.disable()
            probe.proceed.countDown()
            assertTrue("放行后 owner 必须在有界时间内收口", p.awaitOwnerIdle(CLIP, CLEANUP_WAIT_MS))
            caller.close()
        }
        assertEquals("已进入的许可只发一次，取消不得重试", 1, p.events.count { it == "pending.commit" })
        assertFalse("已封轨成片不得因等待方取消被删行", p.events.contains("pending.delete"))
        assertEquals("取消后迟到真话必须回填且只一次", 1, late.size)
        assertEquals("真话是提交成功 Done", "Done", late.single()!!.javaClass.simpleName)
        assertEquals("按任务身份带成片 id", 42L, p.call(late.single()!!, "getMediaId") as Long)
        assertFalse("发布成功必须清掉封轨标记", marker.isFile)
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(CLIP, CLEANUP_WAIT_MS))
    }

    /** Y32：取消 + commit 返回零行 → 行保留待救援、迟到真话 COMMIT、封轨标记留下。 */
    @Test
    fun `Y32 等待方取消后commit返回零行_迟到COMMIT回填且封轨标记保留`() = platform { p ->
        val probe = Probe(p)
        probe.enable(COMMIT)
        p.script("clockStepMs", 1L)
        // 已领许可那次零行；放弃已赢后不得再发起第二次公开提交。
        p.script("commitResults", intArrayOf(0, 1))
        val row = File(temporary.root, "seal-late-commit/out42.mp4").apply { parentFile.mkdirs() }
        p.script("rowData", row.absolutePath)
        val marker = File(row.absolutePath + ".sealed")
        val late = Collections.synchronizedList(mutableListOf<Any?>())
        val caller = launchClip(p, p.clipExporter(), onLate = { late.add(it) })
        try {
            check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) { "未进入真实 commit 阻塞：${probe.detail()}" }
            caller.job.cancel()
            assertTrue("取消必须立刻结束等待方", caller.completed.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS))
        } finally {
            probe.disable()
            probe.proceed.countDown()
            assertTrue("放行后 owner 必须在有界时间内收口", p.awaitOwnerIdle(CLIP, CLEANUP_WAIT_MS))
            caller.close()
        }
        assertEquals("放弃已赢后不得再发起第二次公开提交", 1, p.events.count { it == "pending.commit" })
        assertFalse("封轨成片不得被删行", p.events.contains("pending.delete"))
        assertEquals("迟到真话必须回填且只一次", 1, late.size)
        assertEquals("零行提交的真话是 COMMIT（保留待救援）", "COMMIT", p.call(late.single()!!, "getReason").toString())
        assertTrue("保留待救援的封轨成片必须留下 SealMarker（冷启 5 分钟快通道）", marker.isFile)
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(CLIP, CLEANUP_WAIT_MS))
    }

    /** Y32：选轨链与剪辑链同口径——封轨即落标记、commit 成功即清。 */
    @Test
    fun `Y32 选轨封轨即落标记_commit成功即清`() = platform { p ->
        p.script("commitResults", intArrayOf(1, 1))
        val row = File(temporary.root, "track-seal-done/out42.mp4").apply { parentFile.mkdirs() }
        p.script("rowData", row.absolutePath)
        val marker = File(row.absolutePath + ".sealed")
        val late = Collections.synchronizedList(mutableListOf<Any?>())
        val result = runBlocking { p.trackExport(p.trackExporter(), onLateOutcome = { late.add(it) }) }
        assertEquals("Done", result.javaClass.simpleName)
        assertTrue(
            "封轨后必须查询过 DATA 列以落标记（标记与 sidecar 各一次）",
            (p.script("rowQueries") as Int) >= 1
        )
        assertFalse("发布成功必须清掉封轨标记", marker.isFile)
        assertTrue("等待方还在时真话直接送达", late.isEmpty())
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(TRACK, CLEANUP_WAIT_MS))
    }

    /**
     * Y31：owner 侧异常终止（Error 不被 catch(Exception) 捕获）且没有公开终态时，仍在等待的
     * caller 必须**当场**结算 BUSY，不能吊满整个等待预算（本用例预算 120s，断言远早于它返回）。
     */
    @Test
    fun `Y31 owner异常终止无公开终态时等待方当场结算BUSY`() = platform { p ->
        p.script("stopThrowsError", true)
        val began = System.nanoTime()
        val result = runBlocking { withTimeout(20_000L) { p.clipExport(p.clipExporter()) } }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000L
        assertEquals("无终态的异常终止由等待方按 BUSY 结算", "BUSY", p.call(result, "getReason").toString())
        assertTrue("必须远早于等待预算返回（预算 120s，这里 <30s）：$elapsedMs ms", elapsedMs < 30_000L)
        assertFalse("未封轨异常不得公开提交", p.events.contains("pending.commit"))
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(CLIP, CLEANUP_WAIT_MS))
    }

    // ---- 等待方（与生产 owner 分线程；放行与时钟由用例控制） ----

    private class Caller(
        val job: Job,
        val result: AtomicReference<Any?>,
        val error: AtomicReference<Throwable?>,
        val completed: CountDownLatch,
        private val scope: CoroutineScope,
        private val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher,
        private val executor: java.util.concurrent.ExecutorService
    ) {
        fun close() {
            job.cancel()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun launchClip(
        p: ExportPlatformFixture, exporter: Any, onLate: ((Any?) -> Unit)? = null
    ): Caller {
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "remux-wait-caller").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val job = scope.launch {
            try {
                result.set(p.clipExport(exporter, onLateOutcome = onLate ?: {}))
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                completed.countDown()
            }
        }
        return Caller(job, result, error, completed, scope, dispatcher, executor)
    }

    private fun launchTrack(
        p: ExportPlatformFixture, exporter: Any, onLate: ((Any?) -> Unit)? = null
    ): Caller {
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "remux-wait-caller").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val job = scope.launch {
            try {
                result.set(p.trackExport(exporter, onLateOutcome = onLate ?: {}))
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                completed.countDown()
            }
        }
        return Caller(job, result, error, completed, scope, dispatcher, executor)
    }

    /** 只读平台阻塞钩子；不替换、不缩短生产等待预算，也不替生产放行。 */
    private class Probe(p: ExportPlatformFixture) {
        private val type = p.type("probe.ExportBlocking")
        val entered: CountDownLatch get() = type.getField("entered").get(null) as CountDownLatch
        val proceed: CountDownLatch get() = type.getField("proceed").get(null) as CountDownLatch
        fun enable(stage: String) { type.getField("blockAt").set(null, stage) }
        fun disable() { type.getField("blockAt").set(null, null) }
        fun detail(): String {
            val events = type.getField("events").get(null) as List<*>
            return synchronized(events) {
                events.joinToString { raw ->
                    val e = requireNotNull(raw)
                    "${e.javaClass.getField("operation").get(e)}@${e.javaClass.getField("thread").getLong(e)}"
                }
            }
        }
    }

    private fun platform(block: suspend (ExportPlatformFixture) -> Unit) = runBlocking {
        ExportPlatformFixture(classes, javaClass.classLoader).use { block(it) }
    }

    companion object {
        private const val INSERT = "insert"
        private const val OPEN = "open"
        private const val COMMIT = "commit"
        private const val ENTRY_WAIT_MS = 5_000L
        private const val CLEANUP_WAIT_MS = 5_000L
        private const val CLIP = "com.wotagei.cam.player.ClipExporter"
        private const val TRACK = "com.wotagei.cam.player.TrackExporter"

        @ClassRule @JvmField
        val temporary = TemporaryFolder(File("build/tmp/export-remux-blocking-test").apply { mkdirs() })
        private val classes by lazy { ExportPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}