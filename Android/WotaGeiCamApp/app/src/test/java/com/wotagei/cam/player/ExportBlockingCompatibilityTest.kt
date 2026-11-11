package com.wotagei.cam.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.Collections
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * U05 的两个最小真实阻塞 RED：只运行真实 suspend export，在 Provider 边界用 latch 阻塞。
 *
 * caller 的完成回调必须在 proceed 放行前到达；Job.isCancelled 不是“已经结束等待”的证据。
 * 随后放行同步调用，检查迟到 URI/FD 的身份、同线程 owner 和一次回收，禁止后续业务/commit。
 * 不复制生产取消、单 owner 通道或回收算法，也不证明真实 Binder 可被中断。
 * finally 总会放行闩锁，有界等待 caller 完成及全部已启动平台资源归还；无 sleep/永久等待。
 */
class ExportBlockingCompatibilityTest {
    @Test
    fun `U05 insert阻塞期间取消必须先结束caller迟到URI由原owner回收`() {
        cancellationWhileBlocked(stage = "insert", expectFd = false)
    }

    @Test
    fun `U05 open阻塞期间取消必须先结束caller迟到FD和URI由原owner回收`() {
        cancellationWhileBlocked(stage = "open", expectFd = true)
    }

    @Test
    fun `U05 首次commit阻塞时取消返回零行后不得新发起第二次公开`() {
        cancelledLateCompletion("commit", intArrayOf(0, 1))
    }

    @Test
    fun `U05 已进入commit的许可可返回成功但不重试不删除封轨成片`() {
        cancelledLateCompletion("commit", intArrayOf(1, 1))
    }

    @Test
    fun `U05 render迟到返回不得在caller结束后再发布进度`() {
        cancelledLateCompletion("render")
    }

    @Test
    fun `U05 准备期open永久阻塞时预算到点由独立等待侧先结束caller`() {
        ExportPlatformFixture(classes, javaClass.classLoader).use { p ->
            val probe = BlockingProbe(p)
            probe.enable("open")
            // 先用 1ms 步进让 deadline 由测试时钟基准建立，进入阻塞后再跳跃到预算之外。
            p.script("clockStepMs", 1L)
            val caller = launchExport(p)
            var endedBeforeRelease = false
            var reason: String? = null
            try {
                check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) { "未进入真实 open 阻塞" }
                // 生产主循环还没建立 deadline，必须靠独立等待侧。
                p.script("clockStepMs", 1_000_000L)
                endedBeforeRelease = caller.completed.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)
                reason = caller.result.get()?.let { p.call(it, "getReason").toString() }
                assertEquals("open 必须仍阻塞，caller 不是因同步调用返回而结束", 1L, probe.proceed.count)
            } finally {
                caller.close()
                probe.proceed.countDown()
                assertTrue("放行后 owner 必须在有界时间内完成清理", p.awaitOwnerQuiescence(5_000L))
            }
            assertTrue("预算到点必须先结束 caller 的等待", endedBeforeRelease)
            assertEquals("TIMEOUT", reason)
            assertEquals("未封轨输出由原 owner 回收一次", 1, p.events.count { it == "pending.delete" })
            assertEquals("迟到 FD 仍由原 owner 关闭", 1, p.events.count { it == "fd.close" })
            assertTrue("不得继续建 muxer/编码器", !p.events.contains("muxer.start"))
        }
    }

    @Test
    fun `U05 失败清理delete阻塞时预算到点caller先结束且资源随后归还`() {
        ExportPlatformFixture(classes, javaClass.classLoader).use { p ->
            val probe = BlockingProbe(p)
            probe.enable("delete")
            p.script("renderOk", false)
            p.script("clockStepMs", 1L)
            val caller = launchExport(p)
            var endedBeforeRelease = false
            var reason: String? = null
            try {
                check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) { "未进入真实 delete 阻塞" }
                p.script("clockStepMs", 1_000_000L)
                endedBeforeRelease = caller.completed.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)
                reason = caller.result.get()?.let { p.call(it, "getReason").toString() }
                assertEquals("delete 必须仍阻塞，不能用它已返回冒充超时", 1L, probe.proceed.count)
            } finally {
                caller.close()
                probe.proceed.countDown()
                assertTrue("放行后 owner 必须在有界时间内完成清理", p.awaitOwnerQuiescence(5_000L))
            }
            assertTrue("预算到点必须先结束 caller 的等待", endedBeforeRelease)
            assertEquals("TIMEOUT", reason)
            for (event in listOf("decoder.release", "encoder.release", "muxer.release", "gl.release", "fd.close")) {
                assertEquals("删除阻塞不能拖住资源归还：$event", 1, p.events.count { it == event })
            }
            assertEquals("迟到删除仍由原 owner 领取一次", 1, p.events.count { it == "pending.delete" })
        }
    }

    @Test
    fun `U05 GL线程未真正退出时拒绝第二套资源退出后才恢复准入`() {
        ExportPlatformFixture(classes, javaClass.classLoader).use { p ->
            // 用真实存活线程代表“有界 release 已返回、GL owner 实际仍卡在 native”的窗口。
            val settling = Thread {
                try {
                    Thread.sleep(60_000L)
                } catch (_: InterruptedException) {
                    // 用例收尾
                }
            }.apply { isDaemon = true; start() }
            try {
                p.script("glThread", settling)
                p.script("glThreadAlive", true)
                assertEquals("首轮应完成", "Done", runBlocking { p.export(p.runner()) }.javaClass.simpleName)
                assertEquals("首轮建过一次 pending", 1, p.events.count { it == "pending.insert" })

                val busy = runBlocking { p.export(p.runner()) }
                assertEquals("GL owner 未退出时不得建第二套资源", "BUSY", p.call(busy, "getReason").toString())
                assertEquals("被拒请求不得插入新 pending", 1, p.events.count { it == "pending.insert" })

                p.script("glThreadAlive", false)
                settling.interrupt()
                settling.join(2_000L)
                assertEquals("确认退出后应恢复准入", "Done", runBlocking { p.export(p.runner()) }.javaClass.simpleName)
                assertEquals(2, p.events.count { it == "pending.insert" })
            } finally {
                settling.interrupt()
            }
        }
    }

    private class ExportCaller(
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

    /** 真实 suspend export 在独立线程起跑；放行与时钟由用例控制，出测试不再留后台 caller。 */
    private fun launchExport(p: ExportPlatformFixture): ExportCaller {
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "U05-budget-caller").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val job = scope.launch {
            try {
                result.set(p.export(p.runner()))
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                completed.countDown()
            }
        }
        return ExportCaller(job, result, error, completed, scope, dispatcher, executor)
    }

    private fun cancelledLateCompletion(stage: String, commitResults: IntArray? = null) {
        ExportPlatformFixture(classes, javaClass.classLoader).use { p ->
            val probe = BlockingProbe(p)
            probe.enable(stage)
            p.script("commitResults", commitResults)
            val progress = Collections.synchronizedList(mutableListOf<Float>())
            val bodyExited = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val cancelled = AtomicBoolean()
            val result = AtomicReference<Any?>()
            val unexpected = AtomicReference<Throwable?>()
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "U05-late-$stage-caller").apply { isDaemon = true }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val ownerExecutor = ownerExecutor(p)
            var caller: Job? = null
            var endedBeforeRelease = false
            var ownerExited = false
            var progressAtCancel = -1
            try {
                val runner = p.runner()
                caller = scope.launch {
                    try {
                        result.set(p.export(runner, onProgress = { progress.add(it) }))
                    } catch (_: CancellationException) {
                        cancelled.set(true)
                    } catch (failure: Throwable) {
                        unexpected.set(failure)
                    } finally {
                        bodyExited.countDown()
                    }
                }
                caller.invokeOnCompletion { completed.countDown() }
                check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    "没有进入真实 $stage 阻塞边界，不能计为有效RED：${probe.snapshot()}"
                }
                caller.cancel()
                endedBeforeRelease = completed.await(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS) && bodyExited.count == 0L
                progressAtCancel = progress.size
                check(probe.proceed.count == 1L && probe.returned.count == 1L && !probe.guardTripped()) {
                    "同步边界必须仍阻塞，不能靠自动放行证明取消"
                }
            } finally {
                caller?.cancel()
                // 每例有独立 loader/生产静态执行器；只关准入，不打断在途任务。
                // 等实际 owner Runnable 退出，才能证明后面不会再发 update/进度，不以 sleep 猜时序。
                ownerExecutor.shutdown()
                probe.proceed.countDown()
                ownerExited = ownerExecutor.awaitTermination(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS)
                scope.cancel()
                dispatcher.close()
                if (!executor.awaitTermination(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS)) executor.shutdownNow()
            }
            unexpected.get()?.let { throw AssertionError("平台或生产异常，不是题面行为RED", it) }
            assertTrue("取消必须在同步调用放行前使 caller 实际完成", endedBeforeRelease)
            assertTrue("真实 export 必须向 caller 抛出协程取消", cancelled.get())
            assertEquals("取消不得被吞成普通结果", null, result.get())
            assertTrue("放行后真实 owner 必须已退出，不能在半途读取资源账", ownerExited)
            assertTrue("测试安全上限不得触发", !probe.guardTripped())
            if (stage == "commit") {
                assertEquals("取消已经完成后不能新领取第二次公开许可", 1, p.script("commitCalls"))
                assertEquals("许可之前确已正常封轨", 1, p.events.count { it == "muxer.stop" })
                assertEquals("已封轨成片不能因取消被删除", 0, p.events.count { it == "pending.delete" })
            } else {
                assertEquals("迟到render返回不得重写已结束任务的进度", progressAtCancel, progress.size)
                assertEquals("封轨前取消不能公开", 0, p.script("commitCalls"))
                assertEquals("未封轨输出由原owner回收一次", 1, p.events.count { it == "pending.delete" })
            }
            for (event in listOf("decoder.release", "encoder.release", "muxer.release", "gl.release", "fd.close")) {
                assertEquals("退出后资源须恰好归还：$event", 1, p.events.count { it == event })
            }
            // 方向读取助手自己还会建/释放一枚短期 extractor，与主提取器各一次。
            assertEquals("方向助手与主提取器各释放一次", 2, p.events.count { it == "extractor.release" })
        }
    }

    private fun cancellationWhileBlocked(stage: String, expectFd: Boolean) {
        ExportPlatformFixture(classes, javaClass.classLoader).use { p ->
            val probe = BlockingProbe(p)
            probe.enable(stage)
            val callerBodyExited = CountDownLatch(1)
            val callerCompleted = CountDownLatch(1)
            val cancellationObserved = AtomicBoolean()
            val result = AtomicReference<Any?>()
            val unexpected = AtomicReference<Throwable?>()
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "U05-test-caller-$stage").apply { isDaemon = true }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            var caller: Job? = null
            var endedBeforeProceed = false
            var returnedAfterProceed = false
            var resourcesDrained = false
            var noPrematureRelease = false
            val issues = mutableListOf<String>()
            try {
                // 先构造真实Runner；缺构造/类加载失败不能被下面的取消断言冒充行为RED。
                val runner = p.runner()
                caller = scope.launch {
                    try {
                        result.set(p.export(runner))
                    } catch (_: CancellationException) {
                        cancellationObserved.set(true)
                    } catch (failure: Throwable) {
                        unexpected.set(failure)
                    } finally {
                        callerBodyExited.countDown()
                    }
                }
                caller.invokeOnCompletion { callerCompleted.countDown() }
                check(probe.entered.await(ENTRY_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    "U05 平台阻塞入口未到达，不能计为有效取消RED；账=${probe.snapshot()}"
                }
                check(probe.proceed.count == 1L) { "阻塞入口必须由测试保持关闭" }
                caller.cancel()
                // 记录真实完成事实后才放行；不能事后放行了再拿isCancelled作证。
                endedBeforeProceed = callerCompleted.await(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS) &&
                    callerBodyExited.count == 0L
                noPrematureRelease = probe.snapshot().none {
                    it.operation in setOf("pending.delete", "pending.release", "fd.close", "fd.release")
                }
                check(probe.proceed.count == 1L && probe.returned.count == 1L && !probe.guardTripped()) {
                    "U05 同步边界提前返回/安全上限触发，不能计为有效阻塞RED"
                }
            } finally {
                // 无论断言、反射或生产异常都先放行：不让失败测试留下永远挂住的IO任务。
                caller?.cancel()
                probe.proceed.countDown()
                try {
                    returnedAfterProceed = caller == null || callerCompleted.await(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS)
                    resourcesDrained = probe.awaitDrained(CLEANUP_WAIT_MS)
                } finally {
                    scope.cancel()
                    dispatcher.close()
                    if (!executor.awaitTermination(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        executor.shutdownNow()
                        issues.add("测试caller线程未在有界收尾内退出（线程为daemon，不阻塞JVM退出）")
                    }
                }
            }

            unexpected.get()?.let {
                throw AssertionError("U05 生产/平台异常不是题面取消行为RED", it)
            }
            val ledger = probe.snapshot()
            if (!endedBeforeProceed) issues.add("proceed放开前caller未实际完成，取消只改了Job标记")
            if (!noPrematureRelease) issues.add("同步调用仍阻塞时caller提前回收资源，违反单owner")
            if (!returnedAfterProceed) issues.add("放行后caller未在有界收尾内完成")
            if (!resourcesDrained) issues.add("放行后已返回的平台调用/资源未全部归还")
            if (probe.guardTripped()) issues.add("测试阻塞钩子的30秒安全上限触发，不能当有效业务RED")
            if (!cancellationObserved.get()) issues.add("真实export未向caller抛出CancellationException")
            if (result.get() != null) issues.add("取消被吞成普通结果：${result.get()}")

            val insert = ledger.filter { it.operation == "insert.return" }
            if (insert.size != 1) issues.add("必须恰好返回一枚pending URI，实得$insert")
            val owner = insert.singleOrNull()?.thread
            val uri = insert.singleOrNull()?.resource
            if (uri != null && owner != null) {
                onceOnOwner(ledger, "pending.create", uri, owner, issues)
                onceOnOwner(ledger, "pending.delete", uri, owner, issues)
                onceOnOwner(ledger, "pending.release", uri, owner, issues)
            }
            if (expectFd) {
                val opened = ledger.filter { it.operation == "open.return" }
                if (opened.size != 1) issues.add("必须恰好迟到返回一枚FD，实得$opened")
                opened.singleOrNull()?.let { fd ->
                    if (fd.thread != owner) issues.add("insert/open资源跨owner取得")
                    onceOnOwner(ledger, "fd.create", fd.resource, fd.thread, issues)
                    onceOnOwner(ledger, "fd.close", fd.resource, fd.thread, issues)
                    onceOnOwner(ledger, "fd.release", fd.resource, fd.thread, issues)
                }
            } else if (ledger.any { it.operation.startsWith("open.") || it.operation.startsWith("fd.") }) {
                issues.add("insert迟到后仍打开输出FD，取消后继续了业务")
            }
            val forbidden = setOf("fd.use", "muxer.create", "extractor.create", "extractor.source",
                "retriever.create", "retriever.source", "encoder.create", "decoder.create", "gl.create", "pending.commit")
            val continued = ledger.filter { it.operation in forbidden }
            if (continued.isNotEmpty()) issues.add("迟到资源返回后仍进入后续业务/commit：$continued")
            if (p.events.any { it == "muxer.stop" || it == "pending.commit" }) {
                issues.add("准备阶段取消不得封轨或commit")
            }
            assertTrue("必须确实执行题面平台调用", ledger.any { it.operation == "$stage.enter" })
            assertEquals("U05 $stage 阻塞取消缺口：\n${issues.joinToString("\n")}\n资源线程账=$ledger",
                emptyList<String>(), issues)
        }
    }

    /** 只定位生产静态执行器以关闭准入；不替换、不缩短其寿命。 */
    private fun ownerExecutor(p: ExportPlatformFixture): ThreadPoolExecutor {
        val runnerType = p.type("com.wotagei.cam.player.SceneExportRunner")
        val companionType = p.type("com.wotagei.cam.player.SceneExportRunner\$Companion")
        val onOuter = runCatching { runnerType.getDeclaredField("EXPORT_OWNER_EXECUTOR") }.getOrNull()
        val field = onOuter ?: companionType.getDeclaredField("EXPORT_OWNER_EXECUTOR")
        field.isAccessible = true
        val target = if (onOuter != null) null else runnerType.getField("Companion").get(null)
        return field.get(target) as ThreadPoolExecutor
    }

    private fun onceOnOwner(
        ledger: List<Entry>, operation: String, resource: String, owner: Long, issues: MutableList<String>
    ) {
        val matching = ledger.filter { it.operation == operation && it.resource == resource }
        if (matching.size != 1 || matching.singleOrNull()?.thread != owner) {
            issues.add("$operation 必须由原owner=${owner}恰执行一次，资源=$resource 实得=$matching")
        }
    }

    private data class Entry(val operation: String, val resource: String, val thread: Long)

    /** 只读取新增平台账；闩锁与账不触及Runner私有字段，不替生产做回收。 */
    private class BlockingProbe(p: ExportPlatformFixture) {
        private val type = p.type("probe.ExportBlocking")
        val entered: CountDownLatch get() = type.getField("entered").get(null) as CountDownLatch
        val proceed: CountDownLatch get() = type.getField("proceed").get(null) as CountDownLatch
        val returned: CountDownLatch get() = type.getField("returned").get(null) as CountDownLatch
        fun enable(stage: String) { type.getField("blockAt").set(null, stage) }
        fun guardTripped(): Boolean = type.getField("guardTripped").getBoolean(null)
        fun awaitDrained(timeoutMs: Long): Boolean = try {
            type.getMethod("awaitDrained", Long::class.javaPrimitiveType).invoke(null, timeoutMs) as Boolean
        } catch (e: InvocationTargetException) { throw e.targetException }
        fun snapshot(): List<Entry> {
            val events = type.getField("events").get(null) as List<*>
            return synchronized(events) {
                events.map { raw ->
                    val event = requireNotNull(raw)
                    Entry(event.javaClass.getField("operation").get(event) as String,
                        event.javaClass.getField("resource").get(event) as String,
                        event.javaClass.getField("thread").getLong(event))
                }
            }
        }
    }

    companion object {
        private const val ENTRY_WAIT_MS = 5_000L
        private const val CANCEL_WAIT_MS = 1_500L
        private const val CLEANUP_WAIT_MS = 5_000L
        @ClassRule @JvmField
        val temporary = TemporaryFolder(File("build/tmp/export-u05-blocking-test").apply { mkdirs() })
        private val classes by lazy { ExportPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
