package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

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
            p.startAnalysis(analysis)
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
        p.startAnalysis(analysis)
        p.call(analysis, "updateModel", detector, null, null, null)
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
            val output = p.stance("EngineOutput").constructors.single { it.parameterCount == 7 }
                .newInstance(listOf("2号位，往左"), emptyList<Any>(), 0, null, 0, emptyList<String>(), false)
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
            p.call(speaker, "speak", "2号位，往左", true)
            p.advance(3_000L)
            assertEquals(listOf("voice/num_2.wav", "voice/dir_left.wav", "voice/end.wav"), p.static(SOUND_POOL, "plays"))
        } finally {
            p.call(speaker, "release")
        }
    }

    @Test
    fun `Y75_语音申请焦点且尾词播完才归还`() = platform { p ->
        p.type(AUDIO_MANAGER).getMethod("reset").invoke(null)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        try {
            p.call(speaker, "speak", "多人", true)
            assertEquals(1, p.static(AUDIO_MANAGER, "requests"))
            p.advance(3_000L)
            assertEquals("尾词播完才归还，不能刚入队就放掉焦点", 1, p.static(AUDIO_MANAGER, "abandons"))
        } finally {
            p.call(speaker, "release")
        }
    }

    @Test
    fun `Y75_焦点丢失清空待播队列并停掉在播stream`() = platform { p ->
        p.type(AUDIO_MANAGER).getMethod("reset").invoke(null)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        try {
            p.call(speaker, "speak", "2号位，往左")
            assertEquals(listOf("voice/num_2.wav"), p.static(SOUND_POOL, "plays"))
            p.type(AUDIO_MANAGER).getMethod("change", Int::class.javaPrimitiveType).invoke(null, -1)
            p.mainIdle()
            assertEquals("在播的方向词必须立即停", listOf(1), p.static(SOUND_POOL, "stops"))
            p.advance(3_000L)
            assertEquals("焦点丢失后不得继续播后续词", listOf("voice/num_2.wav"), p.static(SOUND_POOL, "plays"))
        } finally {
            p.call(speaker, "release")
        }
    }

    @Test
    fun `Y74_轮内3秒两号位待播时尾词必须最后`() = platform { p ->
        p.type(AUDIO_MANAGER).getMethod("reset").invoke(null)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        try {
            // 第一轮首句：1 号位先响，队列自然播完后仍保持焦点等待轮末。
            p.call(speaker, "speak", "1号位，往后")
            p.advance(3_000L)
            assertEquals(listOf("voice/num_1.wav", "voice/dir_backward.wav"), p.static(SOUND_POOL, "plays"))

            // 轮内 3s 后第二号位才到（roundEnded=true）：尾词必须排在这句之后。
            p.call(speaker, "speak", "2号位，往右", true)
            p.advance(500L)
            assertEquals(
                "方向词不能和刚提交的号位词重叠",
                listOf("voice/num_1.wav", "voice/dir_backward.wav", "voice/num_2.wav"),
                p.static(SOUND_POOL, "plays"),
            )
            p.advance(1_000L)
            assertEquals(
                "方向词必须等号位词素材时长走完",
                listOf("voice/num_1.wav", "voice/dir_backward.wav", "voice/num_2.wav", "voice/dir_right.wav"),
                p.static(SOUND_POOL, "plays"),
            )
            p.advance(1_000L)
            assertEquals(
                "尾词必须最后响",
                listOf(
                    "voice/num_1.wav", "voice/dir_backward.wav",
                    "voice/num_2.wav", "voice/dir_right.wav", "voice/end.wav",
                ),
                p.static(SOUND_POOL, "plays"),
            )
        } finally {
            p.call(speaker, "release")
        }
    }

    @Test
    fun `Y74_队列按WAV素材真实时长推进而非固定静默窗`() = platform { p ->
        // 把随包真实 wav 喂给平台 AssetManager：时长解析走生产 parseWavDurationMs，
        // 队列必须等每个词的实际素材时长 + 词间静默走完，不能按固定窗提前提交下一词。
        @Suppress("UNCHECKED_CAST")
        val assets = p.static("android.content.res.AssetManager", "files") as MutableMap<String, ByteArray>
        val voice = File("src/full/assets/voice")
        val wavs = requireNotNull(voice.listFiles { f -> f.isFile && f.extension == "wav" })
        wavs.forEach { assets["voice/${it.name}"] = it.readBytes() }
        // 独立按随包 PCM 标准头（44 字节）+ 文件剩余字节算时长，不复用生产解析器，
        // 否则解析器算错时测试会跟着错，无法证明队列等的是素材真实时长。
        fun duration(name: String): Long {
            val bytes = File(voice, "$name.wav").readBytes()
            val byteRate = (bytes[28].toLong() and 0xFF) or
                ((bytes[29].toLong() and 0xFF) shl 8) or
                ((bytes[30].toLong() and 0xFF) shl 16) or
                ((bytes[31].toLong() and 0xFF) shl 24)
            return (bytes.size - 44L) * 1000L / byteRate
        }

        p.type(AUDIO_MANAGER).getMethod("reset").invoke(null)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        val gap = StanceSpeaker.WORD_GAP_MS
        try {
            p.call(speaker, "speak", "1号位，往后")
            p.advance(duration("num_1") + gap - 50)
            assertEquals("首词实际时长未走完前不能提交方向词", listOf("voice/num_1.wav"), p.static(SOUND_POOL, "plays"))
            p.advance(100)
            assertEquals(
                listOf("voice/num_1.wav", "voice/dir_backward.wav"),
                p.static(SOUND_POOL, "plays"),
            )

            // 此时 dir_backward 刚播约 50ms；新一轮的号位词也必须排在它真实时长之后，
            // 不能因为「新一句到了」就切断/重叠上一词。
            p.call(speaker, "speak", "2号位，往右", true)
            p.advance(duration("dir_backward") + gap - 100)
            assertEquals(
                "上一词真实时长未走完前，新一轮号位词不得插入",
                listOf("voice/num_1.wav", "voice/dir_backward.wav"),
                p.static(SOUND_POOL, "plays"),
            )
            p.advance(100)
            assertEquals(
                "上一词播完后号位词按队列接上",
                listOf("voice/num_1.wav", "voice/dir_backward.wav", "voice/num_2.wav"),
                p.static(SOUND_POOL, "plays"),
            )
            // 号位词已播 100ms，剩余等待 = 素材时长 + 静默 - 100；再差 50ms 时方向词不得响。
            p.advance(duration("num_2") + gap - 150)
            assertEquals(
                "号位词实际时长未走完前不能提交方向词",
                listOf("voice/num_1.wav", "voice/dir_backward.wav", "voice/num_2.wav"),
                p.static(SOUND_POOL, "plays"),
            )
            p.advance(100)
            assertEquals(
                listOf("voice/num_1.wav", "voice/dir_backward.wav", "voice/num_2.wav", "voice/dir_right.wav"),
                p.static(SOUND_POOL, "plays"),
            )
            // 方向词刚在上一毫秒起播；差 50ms 走完时尾词不得响。
            p.advance(duration("dir_right") + gap - 50)
            assertEquals("尾词必须等方向词真实时长走完", 4, (p.static(SOUND_POOL, "plays") as List<*>).size)
            p.advance(100)
            assertEquals("voice/end.wav", (p.static(SOUND_POOL, "plays") as List<*>).last())
        } finally {
            p.call(speaker, "release")
        }
    }

    @Test
    fun `Y75_轮末到达时最后一个词仍在播不得提前归还`() = platform { p ->
        p.type(AUDIO_MANAGER).getMethod("reset").invoke(null)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        val tail = StanceSpeaker.DEFAULT_WORD_MS + StanceSpeaker.WORD_GAP_MS
        try {
            p.call(speaker, "speak", "多人")
            p.advance(100L)
            // 队列已提交完但最后一个词还在播：此时轮末收口只能标记，不能立刻放焦点。
            p.call(speaker, "endRound")
            p.advance(tail - 150L)
            assertEquals("最后一个词的素材时长未走完，焦点不能提前归还", 0, p.static(AUDIO_MANAGER, "abandons"))
            p.advance(100L)
            assertEquals("最后一个词真正播完后必须归还焦点", 1, p.static(AUDIO_MANAGER, "abandons"))
        } finally {
            p.call(speaker, "release")
        }
    }

    @Test
    fun `Y75_轮末没有可播尾词也必须归还焦点`() = platform { p ->
        p.type(AUDIO_MANAGER).getMethod("reset").invoke(null)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        try {
            p.call(speaker, "speak", "多人")
            p.advance(3_000L)
            assertEquals("普通句子播完不归还，等本轮显式收口", 0, p.static(AUDIO_MANAGER, "abandons"))
            // roundEnded=true 但文本没有可播词位：不能依赖 end.wav 存在才归还。
            p.call(speaker, "speak", "未知句", true)
            p.mainIdle()
            assertEquals("轮末没有尾词也必须归还焦点", 1, p.static(AUDIO_MANAGER, "abandons"))
            assertEquals("未知句不得产生额外播放", listOf("voice/st_extra.wav"), p.static(SOUND_POOL, "plays"))
        } finally {
            p.call(speaker, "release")
        }
    }

    @Test
    fun `Y75_Controller轮末即使无lines也收口语音焦点`() = platform { p ->
        p.type(AUDIO_MANAGER).getMethod("reset").invoke(null)
        val controller = controller(p)
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        p.inject(controller, "speaker", speaker)
        setEnabledState(p, controller)
        try {
            p.call(speaker, "speak", "多人")
            p.advance(3_000L)
            assertEquals(0, p.static(AUDIO_MANAGER, "abandons"))
            val output = p.stance("EngineOutput").constructors.single { it.parameterCount == 7 }
                .newInstance(emptyList<String>(), emptyList<Any>(), 0, null, 0, emptyList<String>(), true)
            p.call(controller, "onOutput", output)
            p.mainIdle()
            assertEquals("Controller 收到轮末即使 lines 为空也必须让播报器归还焦点", 1, p.static(AUDIO_MANAGER, "abandons"))
        } finally {
            p.call(controller, "release")
        }
    }

    @Test
    fun `Y77_内置模型按APK清单摘要换代并原子替换`() = platform { p ->
        val root = temporary.newFolder()
        val context = p.context(root)
        @Suppress("UNCHECKED_CAST")
        val assets = p.static("android.content.res.AssetManager", "files") as MutableMap<String, ByteArray>
        val modelPack = p.stance("StanceModelPack")
        val instance = requireNotNull(modelPack.getField("INSTANCE").get(null))
        try {
            val v1 = stanceTestPayload()
            assets.clear()
            v1.forEach { (name, bytes) -> assets["model_pack/$name"] = bytes }
            assertTrue(p.call(instance, "installBuiltin", context) as Boolean)
            val builtin = File(root, "model_pack_builtin")
            assertNotNull(StanceModelPack.validateDir(builtin))
            assertEquals(
                v1.getValue("head.json").toString(Charsets.UTF_8),
                File(builtin, "head.json").readText(),
            )
            // APK 换代：清单摘要变化 → 旧缓存必须被替换，而不是继续沿用。
            val v2 = stanceTestPayload(head = """{"coef":[0,0,0,0,6],"intercept":-3}""")
            assets.clear()
            v2.forEach { (name, bytes) -> assets["model_pack/$name"] = bytes }
            assertTrue(p.call(instance, "installBuiltin", context) as Boolean)
            assertEquals(
                v2.getValue("head.json").toString(Charsets.UTF_8),
                File(builtin, "head.json").readText(),
            )
            assertTrue(
                "暂存/旧目录必须收干净",
                root.listFiles()!!.none { it.name.startsWith("model_pack_builtin_old_") || it.name.startsWith("model_pack_builtin_import_") },
            )
        } finally {
            assets.clear()
        }
    }

    @Test
    fun `N05_full门面保留引擎拒绝挂槽的Boolean回执`() = platform { p ->
        val controller = controller(p)
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val feature = requireNotNull(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, p.stance("StanceFeature")))
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
    fun `坏导入不能取消冷启装配与待补挂意图`() = platform { p ->
        val root = temporary.newFolder()
        modelPack(File(root, "model_pack_user").apply { mkdir() })
        assertNotNull("夹具模型组必须能通过激活前校验", StanceModelPack.validateDir(File(root, "model_pack_user")))
        val context = p.context(root)
        val controller = controller(p, context)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val imported = CountDownLatch(1)
        val attached = CountDownLatch(1)
        p.setStatic(INTERPRETER, "verifyEntered", entered)
        p.setStatic(INTERPRETER, "verifyContinue", proceed)
        setEnabledState(p, controller)
        p.call(controller, "setAttachHook", { _: Any, _: Any? -> attached.countDown(); true })
        p.call(controller, "onPageEnter")
        p.call(controller, "reloadAsync", context)
        val initial = p.field(controller, "packJob") as Job
        try {
            assertTrue(
                "GPU 首验入口未到（tip=${p.call(stateValue(p, controller), "getFallbackTip")}，" +
                    "events=${p.static(INTERPRETER, "events")}）",
                entered.await(5, TimeUnit.SECONDS),
            )
            p.call(controller, "importAsync", context, {
                imported.countDown()
                throw IllegalArgumentException("fixture bad zip")
            })
            packBarrier(p, controller)
            assertTrue("坏导入排队时不可取消尚未完成的冷启", initial.isActive)
            assertEquals(1L, imported.count)
            proceed.countDown()
            join(initial)
            assertTrue(imported.await(5, TimeUnit.SECONDS))
            packBarrier(p, controller)
            p.settleAttach(controller)
            assertTrue("初次装配仍须在就绪后补挂", attached.await(5, TimeUnit.SECONDS))
            assertFalse(initial.isCancelled)
            assertNotNull(p.field(controller, "detector"))
            val state = (p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>).value!!
            assertTrue((p.call(state, "getFallbackTip") as String).contains("fixture bad zip"))
        } finally {
            proceed.countDown()
            p.call(controller, "release")
        }
    }

    @Test
    fun `取消native装配后导入必须等旧owner关闭候选才取得事务锁`() = platform { p ->
        val root = temporary.newFolder()
        modelPack(File(root, "model_pack_user").apply { mkdir() })
        val context = p.context(root)
        val controller = controller(p, context)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val imported = CountDownLatch(1)
        val closedAtImport = java.util.concurrent.atomic.AtomicInteger(-1)
        p.setStatic(INTERPRETER, "verifyEntered", entered)
        p.setStatic(INTERPRETER, "verifyContinue", proceed)
        p.call(controller, "reloadAsync", context)
        val previous = p.field(controller, "packJob") as Job
        try {
            assertTrue(
                "GPU 首验入口未到（tip=${p.call(stateValue(p, controller), "getFallbackTip")}，" +
                    "逗留 events=${p.static(INTERPRETER, "events")}）",
                entered.await(5, TimeUnit.SECONDS),
            )
            p.call(controller, "reloadAsync", context)
            val replacement = p.field(controller, "packJob") as Job
            p.call(controller, "importAsync", context, {
                closedAtImport.set(p.static(DELEGATE, "closed") as Int)
                imported.countDown()
                throw IllegalArgumentException("fixture no promotion")
            })
            packBarrier(p, controller)
            assertTrue(previous.isCancelled)
            assertFalse("取消协程不等于 native owner 已退出", previous.isCompleted)
            assertTrue((p.field(controller, "packMutex") as Mutex).isLocked)
            assertEquals("不能在旧包仍被读取时进入促活事务", 1L, imported.count)
            assertEquals(null, p.field(controller, "detector"))
            proceed.countDown()
            assertTrue(imported.await(5, TimeUnit.SECONDS))
            join(previous)
            join(replacement)
            assertTrue("旧候选须先在 owner 关闭", closedAtImport.get() >= 1)
            assertEquals(2, p.static(DELEGATE, "created"))
            assertEquals("取消票据不得交接旧候选", 1, p.static(DELEGATE, "closed"))
            assertNotNull("新票据可以正常装配", p.field(controller, "detector"))
        } finally {
            proceed.countDown()
            p.call(controller, "release")
        }
    }

    @Test
    fun `release退役执行器必须排空已接受的解锁恢复任务`() = platform { p ->
        val root = temporary.newFolder()
        modelPack(File(root, "model_pack_user").apply { mkdir() })
        val context = p.context(root)
        val controller = controller(p, context)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val imported = CountDownLatch(1)
        p.setStatic(INTERPRETER, "verifyEntered", entered)
        p.setStatic(INTERPRETER, "verifyContinue", proceed)
        p.call(controller, "reloadAsync", context)
        val previous = p.field(controller, "packJob") as Job
        val executor = p.field(controller, "packExecutor") as ExecutorService
        val mutex = p.field(controller, "packMutex") as Mutex
        try {
            assertTrue(
                "GPU 首验入口未到（tip=${p.call(stateValue(p, controller), "getFallbackTip")}，" +
                    "逗留 events=${p.static(INTERPRETER, "events")}）",
                entered.await(5, TimeUnit.SECONDS),
            )
            val analysis = requireNotNull(p.field(controller, "analysis"))
            executor.execute {
                blocked.countDown()
                // 即使 shutdownNow 中断 blocker，也等显式放闩，确保恢复任务仍在待执行队列。
                var waiting = true
                while (waiting) {
                    try { waiting = !unblock.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                }
            }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            proceed.countDown()
            p.onAnalysis(analysis)
            assertTrue("owner 已退出，但恢复任务必须尚在旧 executor 排队", mutex.isLocked)
            assertFalse(previous.isCompleted)
            p.call(controller, "release")
            assertSame("退役不能换锁绕过旧 owner", mutex, p.field(controller, "packMutex"))
            p.call(controller, "importAsync", context, {
                imported.countDown()
                throw IllegalArgumentException("fixture no promotion")
            })
            unblock.countDown()
            assertTrue("不能丢弃已接受的恢复任务而永久占锁", imported.await(5, TimeUnit.SECONDS))
            join(previous)
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            packBarrier(p, controller)
            assertFalse(mutex.isLocked)
            assertEquals("release 后的旧候选不可交接", null, p.field(controller, "detector"))
        } finally {
            proceed.countDown()
            unblock.countDown()
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
        attachForDecisionTest(p, controller)
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
    fun `Y70_请求后置实际回退前置时关闭开关且切镜窗口先停分析`() = platform { p ->
        val controller = controller(p)
        val analysis = p.stance("StanceAnalysis").constructors.single().newInstance({ _: Any -> Unit })
        val speaker = p.stance("StanceSpeaker").getConstructor(p.type("android.content.Context")).newInstance(p.context())
        p.inject(controller, "analysis", analysis)
        p.inject(controller, "speaker", speaker)
        p.inject(controller, "attached", true)
        setEnabledState(p, controller)
        val listening = p.field(analysis, "listening") as java.util.concurrent.atomic.AtomicBoolean
        // 恢复分支要求检测器已装载（否则按「装配未完成」挂起，不恢复监听）
        val detector = loadedDetector(p)
        p.inject(controller, "detector", detector)
        var detached = 0
        p.call(controller, "setDetachHook", { detached++ })
        try {
            analysisDecision(p, controller, "INCLUDED")
            p.call(analysis, "resumeListening")

            // 切镜未决（实际槽位尚未发布）：即使请求档位已回到后置，也必须先停分析；
            // 落定（settling=false）且仍在后置时才允许恢复。
            p.call(controller, "setLensSettling", true)
            assertFalse("切镜未决必须停听", listening.get())
            var state = requireNotNull(
                (p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>).value
            )
            assertEquals(false, p.call(state, "getRunning"))
            p.call(controller, "setLensSettling", false)
            assertTrue("落定且在后置时恢复监听", listening.get())
            state = requireNotNull((p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>).value)
            assertEquals(true, p.call(state, "getRunning"))

            // 设备只有前置可开：请求 WIDE 经 resolveLensSlot 回退到 FRONT（core 侧已单测），
            // 录制页按实际槽位调 setFrontLens(true) —— D13 必须关闭并重置开关。
            p.call(controller, "setFrontLens", true)
            state = requireNotNull((p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>).value)
            assertEquals("前置必须关闭开关（D13）", false, p.call(state, "getEnabled"))
            assertEquals(false, p.call(state, "getRunning"))
            assertFalse("前置必须停听", listening.get())
            assertEquals("非录制态切前置要摘槽", 1, detached)
            assertFalse(p.field(controller, "attached") as Boolean)

            // 前置落定后再收到 settling=false：开关已关，绝不能被错误地重新挂回。
            p.call(controller, "setLensSettling", true)
            p.call(controller, "setLensSettling", false)
            assertFalse("前置关闭后 settling 落定不得恢复", listening.get())
        } finally {
            p.call(detector, "close")
            p.call(controller, "release")
        }
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
    fun `Y73_force跳过兼容列表但仍执行运行期验证`() = platform { p ->
        val detector = loadedDetector(p, "force")
        try {
            assertTrue(p.call(detector, "isGpuActive") as Boolean)
            assertEquals("force 不能依赖 CompatibilityList（白名单外机型也要能试）", 0, p.static("org.tensorflow.lite.gpu.CompatibilityList", "created"))
            assertEquals(1, p.static(DELEGATE, "created"))
            assertTrue(p.events().any { it.startsWith("gpu:verify@") })
        } finally {
            p.call(detector, "close")
        }
    }

    @Test
    fun `Y73_越域输出必须判失败并回退CPU`() = platform { p ->
        // 假 Interpreter 的 verify 路径往输出缓冲写一个远离画面尺寸的坐标。
        p.setStatic(INTERPRETER, "verifyCoord", 1e9f)
        val detector = loadedDetector(p, "force")
        try {
            assertFalse("越域坐标不得判 GPU 就绪", p.call(detector, "isGpuActive") as Boolean)
            val reason = p.call(detector, "getGpuFallbackReason") as String
            assertTrue("回退原因必须点名运行期验证失败：$reason", reason.contains("运行期验证失败"))
            assertEquals("force 仍必须尝试默认 delegate", 1, p.static(DELEGATE, "created"))
            assertEquals(
                "force 不查兼容列表（越域判定不依赖白名单）",
                0,
                p.static("org.tensorflow.lite.gpu.CompatibilityList", "created")
            )
        } finally {
            p.call(detector, "close")
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
            p.startAnalysis(analysis)
            p.call(analysis, "updateModel", loadedDetector(p, "off"), null, null, null)
            p.onAnalysis(analysis)
            p.call(analysis, "resumeListening")
            p.emit(analysis)
            // 故意不消费 Main：旧模型的结果已排队，新模型先在 owner 上完成交接。
            p.call(analysis, "updateModel", loadedDetector(p, "off"), null, null, null)
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
            attachForDecisionTest(p, controller)
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

    @Test
    fun `R16_首次初始化成功后才确认并删除上一套模型组`() = platform { p ->
        val root = temporary.newFolder()
        val user = File(root, "model_pack_user").also { writeTestPack(it, COEF_NEW) }
        val previous = File(root, "model_pack_previous").also { writeTestPack(it, COEF_OLD) }
        val context = p.context(root)
        val controller = controller(p, context)
        try {
            p.call(controller, "reloadAsync", context)
            packIdle(p, controller)
            assertNotNull(
                "装配成功必须已见到新模型（tip=${p.call(stateValue(p, controller), "getFallbackTip")}）",
                p.field(controller, "detector"),
            )
            assertFalse("首次初始化成功才允许删除上一套", previous.exists())
            assertNotNull(StanceModelPack.validateDir(user))
            assertTrue((p.call(stateValue(p, controller), "getVersionText") as String).contains("user"))
            val cpuProbes = p.events().count { it.startsWith("cpu:new@") }
            assertTrue("删除上一套之前必须真实探测 CPU 兜底可装配", cpuProbes >= 1)
            // 已确认包（无 previous）正常重复装载不得再付 CPU 探针成本。
            p.call(controller, "reloadAsync", context)
            packIdle(p, controller)
            assertEquals("正常重复装载不得重复探针 CPU", cpuProbes, p.events().count { it.startsWith("cpu:new@") })
        } finally { p.call(controller, "release") }
    }

    @Test
    fun `R16_GPU成功但CPU兜底不可装配时保留上一套并回滚`() = platform { p ->
        val root = temporary.newFolder()
        // 新包：清单/头/张量元数据全合法，GPU 也成功；只有 CPU 权重无法装配（模拟运行期才会炸的包）。
        val newCpu = StanceTestFixtures.tflite(inputName = "cpu-new")
        val user = File(root, "model_pack_user").also { writeTestPack(it, COEF_NEW, cpuModel = newCpu) }
        val previous = File(root, "model_pack_previous").also { writeTestPack(it, COEF_OLD) }
        val context = p.context(root)
        val controller = controller(p, context)
        p.setStatic(INTERPRETER, "failCpuCapacity", newCpu.size)
        try {
            p.call(controller, "reloadAsync", context)
            packIdle(p, controller)
            val events = p.events()
            assertTrue("正向对照：GPU 必须先真实成功", events.any { it.startsWith("gpu:verify@") })
            assertTrue("GPU 成功不能掩盖 CPU 非法：必须真实尝试 CPU 装配", events.any { it.startsWith("cpu:new@") })
            assertFalse("CPU 兜底不可用不得确认删除上一套", previous.exists())
            assertEquals("新包必须被上一套替换", COEF_OLD, File(user, "head.json").readText())
            val tip = p.call(stateValue(p, controller), "getFallbackTip") as String
            assertTrue("必须如实提示 CPU 兜底失败：$tip", tip.contains("CPU"))
            assertTrue("必须如实说明已回滚：$tip", tip.contains("已恢复上一套模型组"))
            assertNotNull("回滚后的上一套仍可装配", p.field(controller, "detector"))
        } finally {
            p.setStatic(INTERPRETER, "failCpuCapacity", -1)
            p.call(controller, "release")
        }
    }

    @Test
    fun `R16_初始化失败回滚上一套并如实提示`() = platform { p ->
        val root = temporary.newFolder()
        val user = File(root, "model_pack_user").also { writeTestPack(it, COEF_NEW) }
        val previous = File(root, "model_pack_previous").also { writeTestPack(it, COEF_OLD) }
        val context = p.context(root)
        val controller = controller(p, context)
        p.setStatic(INTERPRETER, "failCpuConstructor", true)
        p.setStatic(INTERPRETER, "failGpuConstructor", true)
        try {
            p.call(controller, "reloadAsync", context)
            packIdle(p, controller)
            assertEquals("无可用内置包时不能交出检测器", null, p.field(controller, "detector"))
            assertFalse("回滚后 previous 已移回激活位", previous.exists())
            assertEquals("坏掉的新包必须被上一套替换", COEF_OLD, File(user, "head.json").readText())
            val tip = p.call(stateValue(p, controller), "getFallbackTip") as String
            assertTrue("回滚成功必须如实说明：$tip", tip.contains("已恢复上一套模型组"))
            assertFalse("不能把已回滚说成未变：$tip", tip.contains("原模型组未变"))
            assertTrue("内置也不可用时不能假装装配成功：$tip", tip.contains("内置模型组也不可用"))
        } finally {
            p.setStatic(INTERPRETER, "failCpuConstructor", false)
            p.setStatic(INTERPRETER, "failGpuConstructor", false)
            p.call(controller, "release")
        }
    }

    @Test
    fun `Y76_待恢复的回滚状态不能提示原模型组未变`() = platform { p ->
        val root = temporary.newFolder()
        val context = p.context(root)
        val controller = controller(p, context)
        try {
            val status = p.stance("StanceModelPack\$RollbackStatus").enumConstants.single { it.toString() == "PENDING_RECOVERY" }
            val failure = p.stance("StanceModelPack\$ImportException").getDeclaredConstructor(
                String::class.java,
                status.javaClass,
                Throwable::class.java,
            ).apply { isAccessible = true }.newInstance(
                "模型组激活失败，原模型组待恢复（仍在 model_pack_previous）",
                status,
                null,
            )
            p.call(controller, "importAsync", context, { throw failure as Throwable })
            packBarrier(p, controller)
            val tip = p.call(stateValue(p, controller), "getFallbackTip") as String
            assertTrue("待恢复必须单独提示：$tip", tip.contains("待恢复"))
            assertFalse("不能把待恢复提示成未变：$tip", tip.contains("原模型组未变"))
        } finally { p.call(controller, "release") }
    }

    @Test
    fun `B16_渲染非GPU时推理装配与弹窗口径一致`() = platform { p ->
        val controller = controller(p)
        val pack = modelPack()
        try {
            p.call(controller, "setRenderGpuAllowed", false)
            p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture")
            val cpu = requireNotNull(p.field(controller, "detector"))
            assertEquals("DIRECT 下装配必须与弹窗「关」一致", "CPU", p.call(cpu, "getBackendName"))
            assertFalse(p.call(cpu, "isGpuActive") as Boolean)
            assertEquals("不可用的加速路径不能先建 delegate 再判", 0, p.static(DELEGATE, "created"))
            // 渲染切回 GPU：保留的偏好重新生效
            p.call(controller, "setRenderGpuAllowed", true)
            p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture")
            assertTrue(p.call(requireNotNull(p.field(controller, "detector")), "isGpuActive") as Boolean)
        } finally { p.call(controller, "release") }
    }

    @Test
    fun `Y68_热换语义变化重置判定而纯后端切换保留`() = platform { p ->
        val analysis = p.stance("StanceAnalysis").constructors.single().newInstance({ _: Any -> Unit })
        try {
            val engine = requireNotNull(p.field(analysis, "engine"))
            p.call(engine, "setMode", "manual", 1)
            val marginsA = """{"bands":{"left":[0.1,0.4],"right":[0.1,0.4],"bottom":[0.0,0.2]}}"""
            val marginsB = """{"bands":{"left":[0.2,0.5],"right":[0.2,0.5],"bottom":[0.0,0.3]}}"""
            // 指纹由生产函数从清单推导：同 CPU、仅 GPU 资产变化必须得到不同语义。
            val fpA = packFingerprint("cpu-sha-a", "gpu-sha-a")
            val fpGpu = packFingerprint("cpu-sha-a", "gpu-sha-b")
            val fpCpu = packFingerprint("cpu-sha-b", "gpu-sha-b")
            assertNotEquals(fpA, fpGpu)
            p.callSuspending(analysis, "updateModel", loadedDetector(p, "off"), null, marginsA, fpA)
            p.onAnalysis(analysis)
            p.onAnalysis(analysis) { p.call(engine, "observe", frameObs(p, 1000L)) }
            assertNotNull("正向对照必须先锁定人数", p.call(engine, "getNConfirmed"))
            // 纯后端切换（同一包，指纹/标定都不变）：判定状态保留
            p.callSuspending(analysis, "updateModel", loadedDetector(p, "off"), null, marginsA, fpA)
            p.onAnalysis(analysis)
            assertNotNull("仅 GPU/CPU 后端切换不得清判定状态", p.call(engine, "getNConfirmed"))
            // 仅 GPU 检测资产换代：同一 CPU/标定，也必须重置判定语义
            p.callSuspending(analysis, "updateModel", loadedDetector(p, "off"), null, marginsA, fpGpu)
            p.onAnalysis(analysis)
            assertEquals("仅换 GPU 检测权重必须重置判定", null, p.call(engine, "getNConfirmed"))
            // 标定语义变化：必须 reset（人数锁定/锚点/平滑/完成态重来）
            p.onAnalysis(analysis) { p.call(engine, "observe", frameObs(p, 1500L)) }
            assertNotNull(p.call(engine, "getNConfirmed"))
            p.callSuspending(analysis, "updateModel", loadedDetector(p, "off"), null, marginsB, fpGpu)
            p.onAnalysis(analysis)
            assertEquals("换安全区必须重置判定", null, p.call(engine, "getNConfirmed"))
            // 检测主模型换代（int8 指纹变化）同样重置
            p.onAnalysis(analysis) { p.call(engine, "observe", frameObs(p, 2000L)) }
            assertNotNull(p.call(engine, "getNConfirmed"))
            p.callSuspending(analysis, "updateModel", loadedDetector(p, "off"), null, marginsB, fpCpu)
            p.onAnalysis(analysis)
            assertEquals("检测模型换代必须重置判定", null, p.call(engine, "getNConfirmed"))
        } finally { p.call(analysis, "release") }
    }

    @Test
    fun `Y68_Controller装配仅GPU资产变化仍重置而同包后端重载保留`() = platform { p ->
        val controller = controller(p)
        // 两套包只有 GPU 权重不同；CPU、判定头、安全区完全一致。
        val packA = modelPack()
        val packB = modelPack(
            gpuModel = StanceTestFixtures.tflite(inputName = "gpu-b"),
        )
        val metaA = requireNotNull(StanceModelPack.validateDir(packA))
        val metaB = requireNotNull(StanceModelPack.validateDir(packB))
        try {
            // Controller 的 syncEngineParams 会按 UI 状态回写模式：先把状态设为 manual，
            // 否则同包重载会用默认 auto 覆盖引擎模式（那是用户改模式，不是指纹变化）。
            setControllerMode(p, controller, "manual", 1)
            p.callSuspending(controller, "applyPack", packA, metaA, "fixture")
            val analysis = requireNotNull(p.field(controller, "analysis"))
            val engine = requireNotNull(p.field(analysis, "engine"))
            p.onAnalysis(analysis) { p.call(engine, "observe", frameObs(p, 1000L)) }
            assertNotNull("正向对照必须先锁定人数", p.call(engine, "getNConfirmed"))
            // 同包重载（等价 auto/force/off 后端切换）：判定状态保留。
            p.callSuspending(controller, "applyPack", packA, metaA, "fixture")
            p.onAnalysis(analysis)
            assertNotNull("同包换后端不得清判定状态", p.call(engine, "getNConfirmed"))
            // 只换 GPU 权重：Controller 必须从清单推导出不同指纹并重置。
            p.callSuspending(controller, "applyPack", packB, metaB, "fixture")
            p.onAnalysis(analysis)
            assertEquals("仅 GPU 资产变化必须重置判定", null, p.call(engine, "getNConfirmed"))
        } finally { p.call(controller, "release") }
    }

    @Test
    fun `Y72_装配与暂停交错不丢后端与版本文案`() = platform { p ->
        val controller = controller(p)
        val pack = modelPack()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val packer = Thread {
            try {
                start.await()
                repeat(60) { p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture") }
            } catch (t: Throwable) { errors += t } finally { done.countDown() }
        }.also { it.start() }
        val pauser = Thread {
            try {
                start.await()
                repeat(60) {
                    p.call(controller, "suspendForRecording")
                    p.call(controller, "resumeAfterRecording")
                }
            } catch (t: Throwable) { errors += t } finally { done.countDown() }
        }.also { it.start() }
        try {
            start.countDown()
            assertTrue("并发压测必须收敛", done.await(30, TimeUnit.SECONDS))
            assertTrue("并发压测不能抛异常：$errors", errors.isEmpty())
            val state = stateValue(p, controller)
            assertTrue("装配写的后端不能被暂停快照覆盖", (p.call(state, "getBackendName") as String).isNotEmpty())
            assertTrue("装配写的版本不能被暂停快照覆盖", (p.call(state, "getVersionText") as String).contains("模型组"))
        } finally {
            packer.join(5_000)
            pauser.join(5_000)
            p.call(controller, "release")
        }
    }

    @Test
    fun `Y72_转屏切镜与装配交错不得丢后端与版本文案`() = platform { p ->
        // 先钉结构：UiState 只许 update{}，读-改-写整对象会在并发下把别人的字段写回旧值。
        val source = File("src/full/java/com/wotagei/cam/stance/StanceController.kt")
            .readText(Charsets.UTF_8).lineSequence().joinToString("\n")
        val code = com.wotagei.cam.source.KotlinSourceScan.codeOnly(source)
        assertFalse(
            "Y72：禁止 _state.value = _state.value.copy(...)（旧快照覆盖并发写入）",
            code.contains("_state.value = _state.value.copy")
        )

        val controller = controller(p)
        val pack = modelPack()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val packer = Thread {
            try {
                start.await()
                repeat(40) { p.callSuspending(controller, "applyPack", pack, emptyMap<String, Any>(), "fixture") }
            } catch (t: Throwable) { errors += t } finally { done.countDown() }
        }.also { it.start() }
        val rotator = Thread {
            try {
                start.await()
                repeat(200) { i ->
                    p.call(controller, "setRotation", (i % 4) * 90)
                    p.call(controller, "setLensSettling", true)
                    p.call(controller, "setLensSettling", false)
                }
            } catch (t: Throwable) { errors += t } finally { done.countDown() }
        }.also { it.start() }
        try {
            start.countDown()
            assertTrue("并发压测必须收敛", done.await(30, TimeUnit.SECONDS))
            assertTrue("并发压测不能抛异常：$errors", errors.isEmpty())
            val state = stateValue(p, controller)
            assertTrue("转屏/切镜的旧快照不得覆盖装配写的后端", (p.call(state, "getBackendName") as String).isNotEmpty())
            assertTrue("转屏/切镜的旧快照不得覆盖装配写的版本文案", (p.call(state, "getVersionText") as String).contains("模型组"))
        } finally {
            packer.join(5_000)
            rotator.join(5_000)
            p.call(controller, "release")
        }
    }

    private fun frameObs(p: StanceLifecyclePlatform, ts: Long): Any {
        val box = p.stance("PersonBox").getConstructor(
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        ).newInstance(0.5f, 0.5f, 0.2f, 0.4f, 0.9f)
        return p.stance("FrameObs").getConstructor(Long::class.javaPrimitiveType, List::class.java)
            .newInstance(ts, listOf(box))
    }
    private fun stateValue(p: StanceLifecyclePlatform, controller: Any): Any {
        val flow = p.call(controller, "getState") as kotlinx.coroutines.flow.StateFlow<*>
        return requireNotNull(flow.value)
    }
    private fun writeTestPack(
        dir: File,
        head: String,
        cpuModel: ByteArray = StanceTestFixtures.tflite(),
        gpuModel: ByteArray = StanceTestFixtures.tflite(),
    ) {
        stanceTestPayload(head = head, model = cpuModel, gpuModel = gpuModel).forEach { (name, bytes) ->
            File(dir, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        }
    }
    private fun analysisDecision(p: StanceLifecyclePlatform, controller: Any, value: String) {
        assertTrue("Controller 必须消费引擎分析流回执，不可只有声明", controller.javaClass.declaredMethods.any { it.name == "onAnalysisSessionDecision" })
        val decision = p.type("com.wotagei.cam.camera.AnalysisSessionDecision").enumConstants.single { it.toString() == value }
        p.call(controller, "onAnalysisSessionDecision", decision)
    }
    private fun setEnabledState(p: StanceLifecyclePlatform, controller: Any) {
        @Suppress("UNCHECKED_CAST")
        val state = p.field(controller, "_state") as kotlinx.coroutines.flow.MutableStateFlow<Any>
        val getters = listOf("getEnabled", "getRunning", "getBackendName", "getOutput", "getGeometry", "getMode", "getNManual", "getBeta", "getBrighten", "getGpuMode", "getLineIntervalMs", "getPollIntervalMs", "getVersionText", "getFallbackTip")
        val args = getters.map { p.call(state.value, it) }.toMutableList()
        args[0] = true
        args[1] = true
        state.value = requireNotNull(p.call(state.value, "copy", *args.toTypedArray()))
    }

    private fun setControllerMode(p: StanceLifecyclePlatform, controller: Any, mode: String, n: Int) {
        p.call(controller, "setMode", p.context(), mode, n)
    }

    private fun attachForDecisionTest(p: StanceLifecyclePlatform, controller: Any) {
        setEnabledState(p, controller)
        p.call(controller, "setAttachHook", { _: Any, _: Any? -> true })
        p.call(controller, "onPageEnter")
        p.settleAttach(controller)
        assertEquals("必须经生产分配回执挂槽", true, p.field(controller, "attached"))
    }

    private fun loadedDetector(p: StanceLifecyclePlatform, mode: String = "auto"): Any {
        val pack = modelPack()
        val detector = p.instance("StanceDetector")
        assertEquals(true, p.call(detector, "load", File(pack, "yolo11n_int8.tflite"), File(pack, "yolo11n_fp32.tflite"), mode))
        return detector
    }
    private fun modelPack(
        dir: File = temporary.newFolder(),
        gpuModel: ByteArray = StanceTestFixtures.tflite(),
    ): File = dir.apply {
        // R16/Y67：SHA 合法还不够，装配前会读 tflite 张量元数据；夹具必须是结构合法的 FlatBuffer。
        File(this, "yolo11n_int8.tflite").writeBytes(StanceTestFixtures.tflite())
        File(this, "yolo11n_fp32.tflite").writeBytes(gpuModel)
        File(this, "head.json").writeText("""{"coef":[0,0,0,0,0],"intercept":0}""")
        File(this, "margins.json").writeText("""{"bands":{"left":[0.1,0.4],"right":[0.1,0.4],"bottom":[0.0,0.2]}}""")
        val hashes = listFiles()!!.joinToString(",") { file -> "\"${file.name}\":\"${StanceModelPack.sha256Of(file)}\"" }
        File(this, "pack.meta.json").writeText("""{"schema":1,"features":["cx","by","w","h","conf"],"files":{$hashes}}""")
    }

    /** 用生产语义指纹函数从清单推导（证明 CPU/GPU 权重都参与指纹，后端切换不参与）。 */
    private fun packFingerprint(cpuSha: String, gpuSha: String): String = requireNotNull(
        StanceModelPack.semanticFingerprint(
            mapOf(
                "files" to mapOf(
                    "yolo11n_int8.tflite" to cpuSha,
                    "yolo11n_fp32.tflite" to gpuSha,
                ),
            ),
        ),
    )
    private fun controller(p: StanceLifecyclePlatform, context: Any = p.context()): Any = p.stance("StanceController")
        .getDeclaredConstructor(p.type("android.content.Context")).apply { isAccessible = true }.newInstance(context)
    private fun packBarrier(p: StanceLifecyclePlatform, controller: Any) {
        (p.field(controller, "packExecutor") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
    }
    /** 等装配协程真正结束（barrier 只排空当前队列，协程在分析 owner 上挂起时仍会提前返回）。 */
    private fun packIdle(p: StanceLifecyclePlatform, controller: Any) {
        packBarrier(p, controller)
        (p.field(controller, "packJob") as Job?)?.let { join(it) }
    }
    private fun join(job: Job) = runBlocking { withTimeout(5_000L) { job.join() } }
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun platform(body: (StanceLifecyclePlatform) -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try { StanceLifecyclePlatform(temporary.newFolder()).use(body) } finally { Dispatchers.resetMain() }
    }
    companion object {
        private const val COEF_NEW = """{"coef":[0,0,0,0,4],"intercept":-2}"""
        private const val COEF_OLD = """{"coef":[0,0,0,0,5],"intercept":-3}"""
        private const val INTERPRETER = "org.tensorflow.lite.Interpreter"
        private const val DELEGATE = "org.tensorflow.lite.gpu.GpuDelegate"
        private const val SOUND_POOL = "android.media.SoundPool"
        private const val AUDIO_MANAGER = "android.media.AudioManager"
    }
}
