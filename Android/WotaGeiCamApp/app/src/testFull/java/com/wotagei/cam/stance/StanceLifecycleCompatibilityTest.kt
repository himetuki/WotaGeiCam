package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 平台/JNI 桩只记录边界；不复制装配、detect、语音队列或暂停分支，不冒充真 GPU 推理。 */
class StanceLifecycleCompatibilityTest {
    @get:Rule
    val temporary = TemporaryFolder(File("build/tmp/stance-lifecycle-test").apply { mkdirs() })

    @Test
    fun `N01_GPU构造Interpreter失败也关闭已建delegate`() = platform { p ->
        p.setStatic(INTERPRETER, "failGpuConstructor", true)
        val detector = loadedDetector(p)
        assertTrue(p.call(detector, "isLoaded") as Boolean)
        assertFalse(p.call(detector, "isGpuActive") as Boolean)
        assertEquals(1, p.static(DELEGATE, "created"))
        assertEquals("局部 delegate 不能在构造失败时漏关", 1, p.static(DELEGATE, "closed"))
        p.call(detector, "close")
        assertEquals("关闭 CPU 后不应重复关闭失败的 GPU delegate", 1, p.static(DELEGATE, "closed"))
    }

    @Test
    fun `N01_GPU首验失败关闭Interpreter与delegate并保留CPU兜底`() = platform { p ->
        p.setStatic(INTERPRETER, "failVerify", true)
        val detector = loadedDetector(p)
        assertFalse(p.call(detector, "isGpuActive") as Boolean)
        assertEquals(1, p.events().count { it.startsWith("gpu:close@") })
        assertEquals(1, p.static(DELEGATE, "closed"))
        assertTrue((p.call(detector, "getBackendName") as String).contains("CPU"))
        p.call(detector, "close")
    }

    @Test
    fun `N02_连续检测复用真实传入Interpreter的同形状输出缓冲`() = platform { p ->
        val detector = loadedDetector(p, "off")
        repeat(2) {
            assertEquals(1, (p.call(detector, "detect", intArrayOf(-1, -1, -1, -1), 2, 2, it.toLong()) as List<*>).size)
        }
        val outputs = p.static(INTERPRETER, "outputs") as List<*>
        assertEquals(2, outputs.size)
        assertSame("[1,5,2] 的外层是 1，不能拿外层长度和行数比较后每拍分配", outputs[0], outputs[1])
        p.call(detector, "close")
    }

    @Test
    fun `GPU运行期错误显式转CPU并返回本帧实际结果`() = platform { p ->
        val detector = loadedDetector(p)
        p.setStatic(INTERPRETER, "failNextDetect", true)
        val boxes = p.call(detector, "detect", intArrayOf(-1, -1, -1, -1), 2, 2, 1L) as List<*>
        assertEquals("GPU 失败不可伪装成正常空检测", 1, boxes.size)
        assertFalse(p.call(detector, "isGpuActive") as Boolean)
        assertTrue((p.call(detector, "getBackendName") as String).startsWith("CPU"))
        assertNotNull("降级原因必须可供 UI 消费", p.call(detector, "getGpuFallbackReason"))
        assertEquals(1, p.events().count { it.startsWith("gpu:close@") })
        assertEquals(1, p.static(DELEGATE, "closed"))
        assertEquals(1, p.events().count { it.startsWith("cpu:detect@") })
        p.call(detector, "close")
    }

    @Test
    fun `真实模型装配首验检测热换和释放均由同一分析线程持有`() = platform { p ->
        val controller = controller(p)
        val pack = modelPack()
        try {
            // 模拟真实 Pack 线程入口；不先创建 reader，模型装配不能依赖用户已开启分析流。
            p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture")
            val analysis = requireNotNull(p.field(controller, "analysis"))
            assertEquals("只装配模型不应预先分配帧源", 0, p.static("android.media.ImageReader", "created"))
            p.call(analysis, "start", p.type("android.util.Size").getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(2, 2))
            p.onAnalysis(analysis)
            p.call(analysis, "resumeListening")
            p.emit(analysis)
            p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture")
            p.onAnalysis(analysis)
            assertSame("热换不更换分析管线", analysis, p.field(controller, "analysis"))
            p.emit(analysis)
            p.call(controller, "release")
            val worker = p.field(analysis, "thread") as Thread?
            worker?.join(5_000L)
            val events = p.events().filter { it.startsWith("gpu:") || it.startsWith("delegate:") }
            assertTrue("须实际执行首验及检测", events.any { it.startsWith("gpu:verify@") } && events.any { it.startsWith("gpu:detect@") })
            assertEquals("两代 GPU 必须均被释放", 2, p.static(DELEGATE, "closed"))
            val owners = events.map { it.substringAfter('@') }.toSet()
            assertEquals("创建/首验/invoke/热换close/最终close必须同线程：$events", 1, owners.size)
            assertTrue(owners.single().startsWith("WotaStanceAnalysis:"))
        } finally {
            p.call(controller, "release")
        }
    }

    @Test
    fun `暂停后即使恢复也拒绝上一代已投递结果`() = platform { p ->
        var callbacks = 0
        val result: (Any) -> Unit = { callbacks++ }
        val analysis = p.stance("StanceAnalysis").constructors.single().newInstance(result)
        val detector = loadedDetector(p, "off")
        p.call(analysis, "start", p.type("android.util.Size").getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(2, 2))
        p.callSuspending(analysis, "updateModel", detector, null, null)
        p.onAnalysis(analysis)
        p.call(analysis, "resumeListening")
        p.emit(analysis)
        p.call(analysis, "pauseListening")
        p.call(analysis, "resumeListening")
        p.mainIdle()
        assertEquals("恢复不能让暂停前的迟到结果复活", 0, callbacks)
        p.emit(analysis)
        p.mainIdle()
        assertEquals("新一代恢复后仍正常可用", 1, callbacks)
        p.call(analysis, "release")
    }

    @Test
    fun `起录暂停撤掉语音延迟并停在播stream且可复用`() = platform { p ->
        val controller = controller(p)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        p.inject(controller, "speaker", speaker)
        try {
            p.call(speaker, "speak", "2号位，往左")
            assertEquals(listOf("voice/num_2.wav"), p.static(SOUND_POOL, "plays"))
            p.call(controller, "suspendForRecording")
            p.advance(3_000L)
            assertEquals("300ms 方向词和 2400ms 尾词都必须作废", listOf("voice/num_2.wav"), p.static(SOUND_POOL, "plays"))
            assertEquals("暂停必须真正停 stream，而非只拒下一词", listOf(1), p.static(SOUND_POOL, "stops"))
            assertEquals("暂停不能永久 release 语音池", 0, p.static(SOUND_POOL, "releases"))
            p.call(controller, "resumeAfterRecording")
            p.call(speaker, "speak", "多人")
            assertEquals(listOf("voice/num_2.wav", "voice/st_extra.wav"), p.static(SOUND_POOL, "plays"))
        } finally {
            p.call(controller, "release")
        }
    }

    @Test
    fun `离页后迟到Controller结果不能再写叠加或播报`() = platform { p ->
        val controller = controller(p)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        p.inject(controller, "speaker", speaker)
        try {
            p.call(controller, "onPageExit")
            val output = p.stance("EngineOutput").constructors.single { it.parameterCount == 6 }
                .newInstance(listOf("2号位，往左"), emptyList<Any>(), 0, null, 0, emptyList<String>())
            p.call(controller, "onOutput", output)
            assertEquals(emptyList<String>(), p.static(SOUND_POOL, "plays"))
            val state = p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>
            assertEquals(null, p.call(requireNotNull(state.value), "getOutput"))
        } finally {
            p.call(controller, "release")
        }
    }

    @Test
    fun `N04_同一句播完静默后口癖只播一次`() = platform { p ->
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        try {
            p.call(speaker, "speak", "2号位，往左")
            p.advance(3_000L)
            assertEquals(listOf("voice/num_2.wav", "voice/dir_left.wav", "voice/end.wav"), p.static(SOUND_POOL, "plays"))
        } finally {
            p.call(speaker, "release")
        }
    }

    @Test
    fun `N05_full门面保留引擎拒绝挂槽的Boolean回执`() = platform { p ->
        val controller = controller(p)
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val feature = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, p.stance("StanceFeature"))
            p.inject(feature, "ctrl", controller)
            val refused: (Any, Any?) -> Boolean = { _, _ -> false }
            p.call(feature, "setAttachHook", refused)
            @Suppress("UNCHECKED_CAST")
            val actual = p.call(controller, "getAttachHook") as (Any, Any?) -> Any?
            val surface = p.type("android.view.Surface").getConstructor().newInstance()
            assertEquals("门面不得吞掉拒绝回执并伪造 running=true", false, actual(surface, null))
        } finally {
            p.call(controller, "release")
        }
    }

    @Test
    fun `释放与模型首验交错不能迟到发布模型状态`() = platform { p ->
        val controller = controller(p)
        val pack = modelPack()
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        p.setStatic(INTERPRETER, "verifyEntered", entered)
        p.setStatic(INTERPRETER, "verifyContinue", proceed)
        val completion = java.util.concurrent.FutureTask {
            runCatching { p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture") }
        }
        val packing = Thread(completion, "fixture-pack").apply { start() }
        try {
            assertTrue("必须真正进入首验", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val analysis = requireNotNull(p.field(controller, "analysis"))
            p.call(controller, "release")
            proceed.countDown()
            completion.get(5, java.util.concurrent.TimeUnit.SECONDS)
            (p.field(analysis, "thread") as Thread).join(5_000)
            assertEquals("release 后旧装配不能回填 detector", null, p.field(controller, "detector"))
            val state = p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>
            assertEquals("release 后不能恢复旧后端文案", "", p.call(requireNotNull(state.value), "getBackendName"))
            assertEquals("候选 GPU 必须在原 owner 回收", 1, p.static(DELEGATE, "closed"))
            assertEquals(1, p.events().filter { it.startsWith("gpu:") || it.startsWith("delegate:") }.map { it.substringAfter('@') }.toSet().size)
        } finally {
            proceed.countDown()
            packing.join(5_000)
            p.call(controller, "release")
        }
    }

    @Test
    fun `取消热换首验后不替换仍可用旧模型`() = platform { p ->
        val controller = controller(p)
        val pack = modelPack()
        p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture")
        val previous = requireNotNull(p.field(controller, "detector"))
        val analysis = requireNotNull(p.field(controller, "analysis"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        p.setStatic(INTERPRETER, "verifyEntered", entered)
        p.setStatic(INTERPRETER, "verifyContinue", proceed)
        val job = kotlinx.coroutines.Job()
        val completion = java.util.concurrent.FutureTask {
            kotlinx.coroutines.runBlocking(job) {
                runCatching { p.callSuspendingInContext(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture") }
            }
        }
        val packing = Thread(completion, "fixture-cancel-pack").apply { start() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            proceed.countDown()
            runCatching { completion.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            p.onAnalysis(analysis)
            assertSame("取消不能安装已作废候选", previous, p.field(controller, "detector"))
            assertEquals("只关闭候选，不提前关旧模型", 1, p.static(DELEGATE, "closed"))
        } finally {
            proceed.countDown()
            packing.join(5_000)
            p.call(controller, "release")
        }
    }

    @Test
    fun `A07_高速回执暂停真实分析与语音但不摘槽不重置开关`() = platform { p ->
        val controller = controller(p)
        val analysis = p.stance("StanceAnalysis").constructors.single().newInstance({ _: Any -> Unit })
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        p.inject(controller, "analysis", analysis)
        p.inject(controller, "speaker", speaker)
        p.inject(controller, "attached", true)
        setEnabledState(p, controller)
        var detached = 0
        p.call(controller, "setDetachHook", { detached++ })
        try {
            p.call(analysis, "resumeListening")
            p.call(speaker, "speak", "2号位，往左")
            analysisDecision(p, controller, "BLOCKED_HIGH_SPEED")
            p.advance(3_000)
            val state = (p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>).value!!
            assertEquals(true, p.call(state, "getEnabled"))
            assertEquals(false, p.call(state, "getRunning"))
            assertFalse((p.field(analysis, "listening") as java.util.concurrent.atomic.AtomicBoolean).get())
            assertTrue((p.call(state, "getFallbackTip") as String).contains("高速"))
            assertEquals(listOf("voice/num_2.wav"), p.static(SOUND_POOL, "plays"))
            assertEquals(listOf(1), p.static(SOUND_POOL, "stops"))
            assertEquals(0, detached)
            assertEquals(true, p.field(controller, "attached"))
        } finally { p.call(controller, "release") }
    }

    @Test
    fun `A07_高速拒绝不能被回页停录绕过且普通回执自动恢复同一管线`() = platform { p ->
        val controller = controller(p)
        val pack = modelPack()
        p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture")
        val analysis = requireNotNull(p.field(controller, "analysis"))
        p.call(analysis, "start", p.type("android.util.Size").getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(2, 2))
        p.inject(controller, "attached", true)
        setEnabledState(p, controller)
        try {
            analysisDecision(p, controller, "BLOCKED_HIGH_SPEED")
            p.call(controller, "onPageEnter")
            p.call(controller, "resumeAfterRecording")
            assertFalse((p.field(analysis, "listening") as java.util.concurrent.atomic.AtomicBoolean).get())
            p.call(controller, "suspendForRecording")
            analysisDecision(p, controller, "INCLUDED")
            assertFalse("普通回执不能越过录制门", (p.field(analysis, "listening") as java.util.concurrent.atomic.AtomicBoolean).get())
            p.call(controller, "resumeAfterRecording")
            assertTrue((p.field(analysis, "listening") as java.util.concurrent.atomic.AtomicBoolean).get())
            assertSame(analysis, p.field(controller, "analysis"))
            assertEquals(1, p.static("android.media.ImageReader", "created"))
        } finally { p.call(controller, "release") }
    }

    @Test
    fun `N07_释放门面使下一窗口可重建状态订阅而非复用取消作用域`() = platform { p ->
        val controller = controller(p)
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val feature = requireNotNull(unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, p.stance("StanceFeature")))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        p.inject(feature, "ctrl", controller)
        p.inject(feature, "scope", scope)
        val cached = p.stance("StanceFeature").getDeclaredField("instance").apply { isAccessible = true }
        cached.set(null, feature)
        try {
            p.call(feature, "release")
            assertFalse(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
            assertEquals("get 不能继续返回已经取消状态订阅的旧门面", null, cached.get(null))
        } finally {
            cached.set(null, null)
            p.call(controller, "release")
        }
    }

    @Test
    fun `N08_成功或失败的GPU能力探测资源都恰好关闭一次`() {
        for (fail in listOf(false, true)) platform { p ->
            p.setStatic(INTERPRETER, "failGpuConstructor", fail)
            val detector = loadedDetector(p)
            try {
                assertEquals(1, p.static("org.tensorflow.lite.gpu.CompatibilityList", "created"))
                assertEquals("能力探测不是长寿命delegate，装配完成就应关闭", 1, p.static("org.tensorflow.lite.gpu.CompatibilityList", "closed"))
            } finally { p.call(detector, "close") }
        }
    }

    @Test
    fun `N09_GPU运行失败且CPU也不可用时不能继续报GPU就绪`() = platform { p ->
        val detector = loadedDetector(p)
        val changes = mutableListOf<String>()
        val callback: (String, String?) -> Unit = { backend, reason -> changes += "$backend:$reason" }
        p.call(detector, "setOnBackendChanged", callback)
        p.setStatic(INTERPRETER, "failNextDetect", true)
        p.setStatic(INTERPRETER, "failCpuConstructor", true)
        try {
            val result = runCatching { p.call(detector, "detect", intArrayOf(-1, -1, -1, -1), 2, 2, 1L) }
            assertTrue("失败帧不能被当成无人，交给分析层丢弃", result.isFailure)
            assertFalse("没有可用后端就不能仍标记loaded", p.call(detector, "isLoaded") as Boolean)
            assertTrue((p.call(detector, "getBackendName") as String).contains("不可用"))
            assertEquals("终态失败必须可见，而非保留GPU成功文案", 1, changes.size)
            assertEquals(1, p.static(DELEGATE, "closed"))
        } finally { p.call(detector, "close") }
    }

    @Test
    fun `N10_热换成功后拒绝旧模型已经排到主线程的结果`() = platform { p ->
        var callbacks = 0
        val analysis = p.stance("StanceAnalysis").constructors.single().newInstance({ _: Any -> callbacks++ })
        try {
            p.call(analysis, "start", p.type("android.util.Size").getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(2, 2))
            p.call(analysis, "updateModel", loadedDetector(p, "off"), null, null)
            p.onAnalysis(analysis)
            p.call(analysis, "resumeListening")
            p.emit(analysis)
            // 故意不消费 Main：旧模型的结果已排队，新模型先在 owner 上完成交接。
            p.call(analysis, "updateModel", loadedDetector(p, "off"), null, null)
            p.onAnalysis(analysis)
            p.mainIdle()
            assertEquals("新模型接管后不能再发布旧框或旧判定", 0, callbacks)
            p.emit(analysis)
            p.mainIdle()
            assertEquals("热换不得永久停掉新模型的结果", 1, callbacks)
        } finally { p.call(analysis, "release") }
    }

    @Test
    fun `N11_首次owner启动与释放交错不能留下未退出的分析线程`() = platform { p ->
        val analysis = p.stance("StanceAnalysis").constructors.single().newInstance({ _: Any -> Unit })
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val releaseEntered = java.util.concurrent.CountDownLatch(1)
        p.setStatic("android.os.HandlerThread", "startEntered", entered)
        p.setStatic("android.os.HandlerThread", "startContinue", proceed)
        val creating = java.util.concurrent.FutureTask {
            runCatching { p.callSuspending(analysis, "onAnalysis", { Unit }) }
        }
        val releasing = java.util.concurrent.FutureTask {
            releaseEntered.countDown()
            p.call(analysis, "release")
        }
        val creator = Thread(creating, "fixture-owner-create").apply { start() }
        val releaser = Thread(releasing, "fixture-owner-release")
        try {
            assertTrue("必须卡在真实owner已start但字段未发布的窗口", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            releaser.start()
            assertTrue(releaseEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // 正确同步时 release 等创建锁；旧实现会直接完成。两种都先确认已进入该窗口。
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!releasing.isDone && releaser.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertTrue("release 必须已完成或正在等待创建锁", releasing.isDone || releaser.state == Thread.State.BLOCKED)
            proceed.countDown()
            creating.get(5, java.util.concurrent.TimeUnit.SECONDS)
            releasing.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val workers = (p.static("android.os.HandlerThread", "threads") as List<*>).filterIsInstance<Thread>()
            assertEquals(1, workers.size)
            workers.single().join(5_000)
            assertFalse("release 不能漏掉尚未发布字段的已启动owner", workers.single().isAlive)
        } finally {
            proceed.countDown()
            creator.join(5_000)
            if (releaser.state != Thread.State.NEW) releaser.join(5_000)
            p.call(analysis, "release")
        }
    }

    @Test
    fun `N09_不可用回执停止Controller判定播报且普通回执不能复活故障模型`() = platform { p ->
        val controller = controller(p)
        val pack = modelPack()
        try {
            p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture")
            val analysis = requireNotNull(p.field(controller, "analysis"))
            p.call(analysis, "start", p.type("android.util.Size").getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(2, 2))
            p.inject(controller, "attached", true)
            setEnabledState(p, controller)
            analysisDecision(p, controller, "INCLUDED")
            p.emit(analysis)
            p.mainIdle()
            val stateFlow = p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>
            assertNotNull("正向对照必须先发布真实有效帧", p.call(stateFlow.value!!, "getOutput"))
            val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
            p.inject(controller, "speaker", speaker)
            p.call(speaker, "speak", "2号位，往左")
            p.setStatic(INTERPRETER, "failNextDetect", true)
            p.setStatic(INTERPRETER, "failCpuConstructor", true)
            p.emit(analysis)
            p.mainIdle()
            val failed = stateFlow.value!!
            assertEquals("后端不可用不能继续声称running", false, p.call(failed, "getRunning"))
            assertEquals("不能保留故障前叠加结果", null, p.call(failed, "getOutput"))
            assertTrue((p.call(failed, "getFallbackTip") as String).contains("不可用"))
            assertFalse((p.field(analysis, "listening") as java.util.concurrent.atomic.AtomicBoolean).get())
            p.advance(3_000)
            assertEquals(listOf("voice/num_2.wav"), p.static(SOUND_POOL, "plays"))
            assertEquals(listOf(1), p.static(SOUND_POOL, "stops"))
            p.call(controller, "onPageEnter")
            p.call(controller, "resumeAfterRecording")
            analysisDecision(p, controller, "INCLUDED")
            assertFalse("会话接纳不等于故障模型已恢复", (p.field(analysis, "listening") as java.util.concurrent.atomic.AtomicBoolean).get())
            p.setStatic(INTERPRETER, "failCpuConstructor", false)
            p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture")
            analysisDecision(p, controller, "INCLUDED")
            assertTrue("明确重载成功后可以恢复同一reader", (p.field(analysis, "listening") as java.util.concurrent.atomic.AtomicBoolean).get())
            assertEquals(1, p.static("android.media.ImageReader", "created"))
        } finally { p.call(controller, "release") }
    }

    private fun analysisDecision(p: StanceLifecyclePlatform, controller: Any, value: String) {
        assertTrue("Controller 必须消费引擎分析流回执，不可只有声明", controller.javaClass.declaredMethods.any { it.name == "onAnalysisSessionDecision" })
        val decision = p.type("com.wotagei.cam.camera.AnalysisSessionDecision").enumConstants.single { it.toString() == value }
        p.call(controller, "onAnalysisSessionDecision", decision)
    }
    private fun setEnabledState(p: StanceLifecyclePlatform, controller: Any) {
        @Suppress("UNCHECKED_CAST")
        val state = p.field(controller, "_state") as kotlinx.coroutines.flow.MutableStateFlow<Any>
        val getters = listOf("getEnabled", "getRunning", "getBackendName", "getOutput", "getMode", "getNManual", "getBeta", "getBrighten", "getGpuMode", "getLineIntervalMs", "getPollIntervalMs", "getVersionText", "getFallbackTip")
        val args = getters.map { p.call(state.value, it) }.toMutableList()
        args[0] = true
        args[1] = true
        state.value = requireNotNull(p.call(state.value, "copy", *args.toTypedArray()))
    }

    private fun loadedDetector(p: StanceLifecyclePlatform, mode: String = "auto"): Any {
        val pack = modelPack()
        val detector = p.instance("StanceDetector")
        assertEquals(true, p.call(detector, "load", File(pack, "yolo11n_int8.tflite"), File(pack, "yolo11n_fp32.tflite"), mode))
        return detector
    }
    private fun modelPack(): File = temporary.newFolder().apply {
        File(this, "yolo11n_int8.tflite").writeBytes(ByteArray(16))
        File(this, "yolo11n_fp32.tflite").writeBytes(ByteArray(16))
        File(this, "head.json").writeText("""{"coef":[0,0,0,0,0],"intercept":0}""")
        File(this, "margins.json").writeText("""{"bands":{}}""")
    }
    private fun controller(p: StanceLifecyclePlatform): Any = p.stance("StanceController")
        .getDeclaredConstructor(p.type("android.content.Context")).apply { isAccessible = true }.newInstance(p.context())
    private fun platform(body: (StanceLifecyclePlatform) -> Unit) {
        StanceLifecyclePlatform(temporary.newFolder()).use(body)
    }
    companion object {
        private const val INTERPRETER = "org.tensorflow.lite.Interpreter"
        private const val DELEGATE = "org.tensorflow.lite.gpu.GpuDelegate"
        private const val SOUND_POOL = "android.media.SoundPool"
    }
}
