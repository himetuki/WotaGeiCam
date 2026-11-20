package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A01/A02/A06：执行真实 prepare、换段、runSegment，不复制录制分支，也不用源码字符串当行为证据。 */
class RecorderCompatibilityBridgeTest {
    @Test
    fun `A01 DIRECT首段真实prepare和start先configure空输出再绑定persistent`() = platform { p ->
        val recorder = p.recorder()
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(), p.sink()))
            p.call(recorder, "start")
            val loop = p.field(recorder, "loop") as Thread
            loop.join(2_000)
            assertFalse("平台脚本应使生产泵线程正常收口", loop.isAlive)
            assertNull("不能靠泵线程异常退出假绿", p.field(recorder, "engineError"))
            assertEquals(listOf("codec1.configure:null", "codec1.bind:persistent1", "codec1.start"),
                p.events.filter { it.startsWith("codec1.configure:") || it.startsWith("codec1.bind:") || it == "codec1.start" })
        } finally {
            p.call(recorder, "release")
        }
    }

    @Test
    fun `A01 DIRECT换段重新绑定同一persistent且先于start`() = platform { p ->
        val recorder = p.recorder()
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(), p.sink()))
            val surface = p.call(recorder, "getSurface")
            p.call(recorder, "closeSegmentEngine")
            assertEquals(true, p.call(recorder, "openNextSegment", p.sink(1)))
            assertSame(surface, p.call(recorder, "getSurface"))
            assertEquals(1, p.type("android.media.MediaCodec").getField("persistentCount").get(null))
            assertEquals(listOf("codec2.configure:null", "codec2.bind:persistent1", "codec2.start"),
                p.events.filter { it.startsWith("codec2.configure:") || it.startsWith("codec2.bind:") || it == "codec2.start" })
        } finally {
            p.call(recorder, "release")
        }
    }

    @Test
    fun `A01 GPU首段仍使用perCodec面而不是persistent绑定`() = platform { p ->
        val recorder = p.recorder()
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(gpu = true), p.sink()))
            assertEquals("codec1-input", p.call(recorder, "getSurface").toString())
            assertEquals(listOf("codec1.configure:null", "codec1.create:codec1-input"), p.events.toList())
            assertEquals(0, p.type("android.media.MediaCodec").getField("persistentCount").get(null))
        } finally {
            p.call(recorder, "release")
        }
    }

    @Test
    fun `A01 GPU换段通知新面后释放旧面再start`() = platform { p ->
        val recorder = p.recorder()
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(gpu = true), p.sink()))
            val old = p.call(recorder, "getSurface")
            p.call(recorder, "setOnInputSurfaceRecreated", { value: Any -> p.events.add("handoff:$value") })
            p.call(recorder, "closeSegmentEngine")
            p.events.clear()
            assertEquals(true, p.call(recorder, "openNextSegment", p.sink(1)))
            assertNotSame(old, p.call(recorder, "getSurface"))
            assertEquals(listOf("codec2.configure:null", "codec2.create:codec2-input", "handoff:codec2-input",
                "surface.release:codec1-input", "codec2.start"), p.events.toList())
        } finally {
            p.call(recorder, "release")
        }
    }

    @Test
    fun `A01 persistent绑定被拒不能成功prepare且释放新编码器`() = platform { p ->
        p.static("android.media.MediaCodec", "rejectBinding", true)
        val recorder = p.recorder()
        try {
            assertEquals(false, p.call(recorder, "prepare", p.profile(), p.sink()))
            assertTrue(p.events.contains("codec1.release"))
            assertFalse(p.events.contains("codec1.start"))
        } finally {
            p.call(recorder, "release")
        }
    }

    @Test
    fun `A02 真正MrRecorder保存同一个回调对象并可清空`() = platform { p ->
        val recorder = p.recorder(mr = true)
        val callback: (Any) -> Unit = {}
        p.call(recorder, "setOnInputSurfaceRecreated", callback)
        assertSame("接口默认setter不能丢弃上层赋值", callback, p.call(recorder, "getOnInputSurfaceRecreated"))
        p.call(recorder, "setOnInputSurfaceRecreated", null)
        assertNull(p.call(recorder, "getOnInputSurfaceRecreated"))
    }

    @Test
    fun `A02 DIRECT真实802换段先通知新面再start且只通知一次`() = mrRotate(gpu = false)

    @Test
    fun `A02 GPU拒绝persistent后的真实802换段也通知新面`() = mrRotate(gpu = true, rejected = true)

    @Test
    fun `A02 GPU接受persistent跨段同面不通知`() = mrRotate(gpu = true)

    @Test
    fun `A02 回调抛错仍按MR既定策略启动新段`() = mrRotate(gpu = false, callbackThrows = true)

    private fun mrRotate(gpu: Boolean, rejected: Boolean = false, callbackThrows: Boolean = false) = platform { p ->
        p.static("android.media.MediaRecorder", "rejectPersistent", rejected)
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(gpu), p.sink()))
            val old = p.call(recorder, "getSurface")
            val seen = ArrayList<Any>()
            val callback: (Any) -> Unit = { surface ->
                seen.add(surface)
                p.events.add("handoff:$surface")
                if (callbackThrows) throw IllegalStateException("测试接线方异常")
            }
            p.call(recorder, "setOnInputSurfaceRecreated", callback)
            p.call(recorder, "start")
            p.events.clear()
            p.call(recorder, "onInfo", null, 802, 0)
            val current = p.call(recorder, "getSurface")
            assertEquals("必须真正走完新实例prepare与start", 1, p.field(recorder, "partIndex"))
            assertNull(p.field(recorder, "engineError"))
            assertTrue(p.events.contains("mr2.start"))
            if (gpu && !rejected) {
                assertSame(old, current)
                assertTrue(seen.isEmpty())
            } else {
                assertNotSame(old, current)
                assertEquals("换面必须同步通知一次", listOf(current), seen)
                assertTrue(p.events.indexOf("handoff:$current") < p.events.indexOf("mr2.start"))
            }
        } finally {
            p.call(recorder, "release")
        }
    }

    @Test
    fun `A06 视频非空EOS保留payload且先写后release`() = drain { p, v, _, _ ->
        p.format(v)
        p.packet(v, 128, EOS or KEY_FRAME, 1_000, offset = 3)
        Expected(listOf("V:128:17"), listOf("V"), listOf("V:0"))
    }

    @Test
    fun `A06 视频空EOS不写空样本且只释放一次`() = drain { p, v, _, _ ->
        p.format(v)
        p.packet(v, 0, EOS, 1_000)
        Expected(emptyList(), listOf("V"), listOf("V:0"))
    }

    @Test
    fun `A06 ENV唯一非空EOS在启动闸前暂存后写入不能废轨`() = drain(env = true) { p, v, a, _ ->
        ordinaryVideo(p, v)
        p.format(a!!)
        p.packet(a, 128, EOS, 1_000, offset = 5)
        Expected(listOf("V:64:17", "A:128:17"), listOf("V", "A"), listOf("V:0", "V:1", "A:0"))
    }

    @Test
    fun `A06 CAP唯一非空EOS在启动闸前暂存后写入不能废轨`() = drain(cap = true) { p, v, _, c ->
        ordinaryVideo(p, v)
        p.format(c!!)
        p.packet(c, 128, EOS, 1_000, offset = 5)
        Expected(listOf("V:64:17", "C:128:17"), listOf("V", "C"), listOf("V:0", "V:1", "C:0"))
    }

    @Test
    fun `A06 三轨同时非空EOS在最后done前冲完held和preMux`() = drain(env = true, cap = true) { p, v, a, c ->
        listOf(v, a!!, c!!).forEach {
            p.format(it)
            p.packet(it, 128, EOS, 1_000)
        }
        Expected(listOf("V:128:17", "A:128:17", "C:128:17"), listOf("V", "A", "C"), listOf("V:0", "A:0", "C:0"))
    }

    @Test
    fun `A06 视频EOS在muxer未启动时进preMux并等ENV后补写`() = drain(env = true) { p, v, a, _ ->
        p.format(v)
        p.packet(v, 128, EOS or KEY_FRAME, 1_000)
        p.waitOutput(a!!)
        p.format(a)
        p.packet(a, 96, EOS, 1_000)
        Expected(listOf("V:128:17", "A:96:17"), listOf("V", "A"), listOf("V:0", "A:0"), videoBuffered = true)
    }

    @Test
    fun `A06 ENV已有held时后续非空EOS不越过旧包或被丢弃`() = drain(env = true, cap = true) { p, v, a, c ->
        ordinaryVideo(p, v)
        p.format(a!!)
        p.packet(a, 70, 0, 1_000)
        p.packet(a, 73, EOS, 2_000)
        p.waitOutput(c!!)
        p.waitOutput(c)
        p.format(c)
        p.packet(c, 90, EOS, 1_000)
        Expected(listOf("V:64:17", "A:70:17", "A:73:18", "C:90:17"), listOf("V", "A", "C"),
            listOf("V:0", "V:1", "A:0", "A:1", "C:0"))
    }

    @Test
    fun `A06 CAP已有held时后续非空EOS不越过旧包或被丢弃`() = drain(env = true, cap = true) { p, v, a, c ->
        ordinaryVideo(p, v)
        p.waitOutput(a!!)
        p.waitOutput(a)
        p.format(a)
        p.packet(a, 90, EOS, 1_000)
        p.format(c!!)
        p.packet(c, 70, 0, 1_000)
        p.packet(c, 73, EOS, 2_000)
        Expected(listOf("V:64:17", "A:90:17", "C:70:17", "C:73:18"), listOf("V", "A", "C"),
            listOf("V:0", "V:1", "A:0", "C:0", "C:1"))
    }

    @Test
    fun `A06 CODEC_CONFIG不能冒充视频有效样本`() = configThenEos("V")

    @Test
    fun `A06 CODEC_CONFIG不能冒充ENV首样本或写进音轨`() = configThenEos("A")

    @Test
    fun `A06 CODEC_CONFIG不能冒充CAP首样本或写进音轨`() = configThenEos("C")

    private fun configThenEos(kind: String) = drain(env = kind == "A", cap = kind == "C") { p, v, a, c ->
        val target = when (kind) { "A" -> a!!; "C" -> c!!; else -> v }
        if (kind != "V") ordinaryVideo(p, v)
        p.format(target)
        p.packet(target, 12, CONFIG, 0)
        p.packet(target, 128, EOS, 1_000)
        val writes = if (kind == "V") listOf("V:128:18") else listOf("V:64:17", "$kind:128:18")
        val releases = if (kind == "V") listOf("V:0", "V:1") else listOf("V:0", "V:1", "$kind:0", "$kind:1")
        Expected(writes, if (kind == "V") listOf("V") else listOf("V", kind), releases)
    }

    @Test
    fun `A06 视频CONFIG同时携带EOS只结束不写有效帧`() = configOnlyEos("V")

    @Test
    fun `A06 ENV CONFIG同时携带EOS不能建零样本轨`() = configOnlyEos("A")

    @Test
    fun `A06 CAP CONFIG同时携带EOS不能建零样本轨`() = configOnlyEos("C")

    private fun configOnlyEos(kind: String) = drain(env = kind == "A", cap = kind == "C") { p, v, a, c ->
        val target = when (kind) { "A" -> a!!; "C" -> c!!; else -> v }
        if (kind != "V") ordinaryVideo(p, v)
        p.format(target)
        p.packet(target, 12, CONFIG or EOS, 0)
        Expected(if (kind == "V") emptyList() else listOf("V:64:17"), listOf("V"),
            if (kind == "V") listOf("V:0") else listOf("V:0", "V:1", "$kind:0"))
    }

    @Test
    fun `A06 普通双音轨payload加独立空EOS仍保序且无重复写释放`() = drain(env = true, cap = true) { p, v, a, c ->
        ordinaryVideo(p, v)
        listOf(a!!, c!!).forEach {
            p.format(it)
            p.packet(it, 128, 0, 1_000)
            p.packet(it, 0, EOS, 2_000)
        }
        Expected(listOf("V:64:17", "A:128:17", "C:128:17"), listOf("V", "A", "C"),
            listOf("V:0", "V:1", "A:0", "A:1", "C:0", "C:1"))
    }

    // ---- Y17：HEVC 预筛通过但实际 configure 被拒 → 释放失败实例后按完整 AVC 重试 ----

    /**
     * 鉴别力：旧实现只有"能力表预筛"一层，configure 实拒直接走 PREPARE_FAILED。
     * 本例让能力表说支持 HEVC（空口通过预筛）、configure 抛拒收，断言**两次 configure 真发生**、
     * 失败实例被释放、重试落在新实例上，且生效 profile 的 codec 与码率都落到 AVC 口径。
     */
    @Test
    fun `Y17 Codec实配被拒后按AVC重试且码率不乘HEVC系数`() = platform { p ->
        p.static("android.media.MediaCodec", "rejectHevcConfigure", true)
        val recorder = p.recorder()
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(hevc = true, bitrate = 0), p.sink()))
            @Suppress("UNCHECKED_CAST")
            val mimes = p.type("android.media.MediaCodec").getField("configureMimes").get(null) as List<String>
            assertEquals(
                "必须先按 HEVC 实配、被拒后再按 AVC 重试（两次 configure 都要真发生）",
                listOf("video/hevc", "video/avc"), mimes
            )
            assertTrue("失败实例必须被释放（不许留在场上）", p.events.contains("codec1.release"))
            val effective = p.field(recorder, "profile")!!
            assertEquals("生效 profile 的 codec 必须固化成 AVC", false, p.call(effective, "getHevc"))
            assertEquals(
                "码率必须按 AVC 口径（640x480@30 自动表 8M；HEVC 0.9 不得再参与）",
                8_000_000, p.call(effective, "effectiveBitrate")
            )
        } finally {
            p.call(recorder, "release")
        }
    }

    /**
     * MR 参照路：平台在 setVideoEncoder(HEVC) 拒收（运行时异常）——必须**释放旧实例**、
     * 在新实例上按 H264 重试（同一实例二次 configure 是 2026-10-08「第二轮不可靠」的形态）。
     */
    @Test
    fun `Y17 MrRecorder HEVC被拒后换新实例按AVC重试`() = platform { p ->
        p.static("android.media.MediaRecorder", "rejectHevcEncoder", true)
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(hevc = true, bitrate = 0), p.sink()))
            assertTrue("失败实例必须 release（不许原地复用）", p.events.contains("mr1.release"))
            val mr = p.field(recorder, "mr")!!
            assertEquals("重试必须发生在新实例上", "mr2", p.field(mr, "id"))
            val effective = p.field(recorder, "profile")!!
            assertEquals(false, p.call(effective, "getHevc"))
            assertEquals(8_000_000, p.call(effective, "effectiveBitrate"))
        } finally {
            p.call(recorder, "release")
        }
    }

    // ---- Y19：MR 换段/错误回调必须核实例；封口取独立信号；ERROR 态可挽救段保留 ----

    /**
     * 旧实例（被换代销毁的那一枚）排队中的 801/802 与 MEDIA_ERROR 迟到送达时，不许斩到新段：
     * 判据是**对象身份**（见 [mrCallbackIsStale]）。同一条回调换当前实例送来必须照常生效，
     * 证明不是"一刀切忽略"的假绿。
     */
    @Test
    fun `Y19 旧实例迟到的换段与错误回调整条忽略_当前实例照常`() = platform { p ->
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(), p.sink()))
            p.call(recorder, "start")
            val current = p.field(recorder, "mr")!!
            val stale = p.new("android.media.MediaRecorder")
            p.events.clear()
            p.call(recorder, "onInfo", stale, 802, 0)
            p.call(recorder, "onError", stale, 1, 0)
            assertEquals("旧实例的换段请求不许切新段", 0, p.field(recorder, "partIndex"))
            assertNull("旧实例的错误不许把新段判废", p.field(recorder, "engineError"))
            // 对照：当前实例的 802 必须真正换段（证明判据只拦旧实例）
            p.call(recorder, "onInfo", current, 802, 0)
            assertEquals(1, p.field(recorder, "partIndex"))
            assertNull(p.field(recorder, "engineError"))
        } finally {
            p.call(recorder, "release")
        }
    }

    /**
     * ERROR 态不等于容器坏：stop 正常返回（moov 已写完）就可挽救整段。
     * 旧实现 ERROR 态直接 discardCurrent ⇒ 这里会是 `store.seal:…:false`；修复后必须 keep=true。
     */
    @Test
    fun `Y19 引擎报错但停录正常返回时保留可挽救段`() = platform { p ->
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(), p.sink()))
            p.call(recorder, "start")
            p.call(recorder, "onError", p.field(recorder, "mr"), 1, 0)
            ageSegment(p, recorder, 2_000)
            p.events.clear()
            p.call(recorder, "stop")
            val seal = p.events.single { it.startsWith("store.seal:0:") }
            assertTrue("stop 正常返回 = 容器已封口，整段必须保留（实际 $seal）", seal.endsWith(":true"))
        } finally {
            p.call(recorder, "release")
        }
    }

    /** 反面对照：ERROR 态 + stop 抛异常（容器确实没封口）⇒ 才按废片回收。 */
    @Test
    fun `Y19 引擎报错且收口抛异常时才按废片回收`() = platform { p ->
        p.static("android.media.MediaRecorder", "stopThrows", true)
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(), p.sink()))
            p.call(recorder, "start")
            p.call(recorder, "onError", p.field(recorder, "mr"), 1, 0)
            ageSegment(p, recorder, 2_000)
            p.events.clear()
            p.call(recorder, "stop")
            val seal = p.events.single { it.startsWith("store.seal:0:") }
            assertTrue("收口失败 = 容器没封口，只能按废片（实际 $seal）", seal.endsWith(":false"))
        } finally {
            p.call(recorder, "release")
        }
    }

    /** release 也是会话收尾：ERROR 态不许只 releaseEngine 把当前 pending 晾成幽灵行。 */
    @Test
    fun `Y19 release在ERROR态也收口并保留可挽救段`() = platform { p ->
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(), p.sink()))
            p.call(recorder, "start")
            p.call(recorder, "onError", p.field(recorder, "mr"), 1, 0)
            ageSegment(p, recorder, 2_000)
            p.events.clear()
            p.call(recorder, "release")
            val seal = p.events.single { it.startsWith("store.seal:0:") }
            assertTrue("release 收尾必须与 stop 同口径（实际 $seal）", seal.endsWith(":true"))
            assertTrue("实例必须被释放", p.events.any { it.startsWith("mr") && it.endsWith(".release") })
        } finally {
            p.call(recorder, "release")
        }
    }

    /**
     * 801（平台已自行停录并封口）之后再 stop 必抛；此时"容器已封口"由 801 这条独立信号给出，
     * 不许按 STOP_FAILED 把刚写完的整段删掉。
     */
    @Test
    fun `Y19 801自停后的stop失败仍按已封口保留旧段`() = platform { p ->
        p.static("android.media.MediaRecorder", "stopThrows", true)
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(true, p.call(recorder, "prepare", p.profile(), p.sink()))
            p.call(recorder, "start")
            // 保留判据同时受 MIN_KEEP_MS 约束：把本段拨到门槛之外，测的才是"801 已封口"这一条，
            // 而不是 1ms 的废片（后者按 TOO_SHORT 删掉是对的）
            ageSegment(p, recorder, 2_000)
            p.events.clear()
            p.call(recorder, "onInfo", p.field(recorder, "mr"), 801, 0)
            assertEquals("801 必须触发换段", 1, p.field(recorder, "partIndex"))
            val seal = p.events.single { it.startsWith("store.seal:0:") }
            assertTrue("801 = 平台已封口，旧段必须保留（实际 $seal）", seal.endsWith(":true"))
        } finally {
            p.call(recorder, "release")
        }
    }

    // ---- Y20：prepare 期真正部分获取的输出句柄/pending 必须在收口层回收 ----

    /**
     * Codec 路：编码器创建抛运行时异常（不属于任何既有失败分支）——不许漏给调用方，
     * 必须关掉已打开的 fd、回收 pending，并按 PREPARE_FAILED 收场。
     */
    @Test
    fun `Y20 Codec prepare期运行时异常也收干净fd与pending`() = platform { p ->
        p.static("android.media.MediaCodec", "throwOnCreate", true)
        val recorder = p.recorder()
        try {
            assertEquals(false, p.call(recorder, "prepare", p.profile(), p.sink()))
            assertEquals(
                "拿到的输出句柄必须关闭（不许悬到进程结束）",
                1, p.type("com.wotagei.cam.record.OutputTarget").getField("closes").get(null)
            )
            assertTrue("pending 必须回收（否则冷启清扫/幽灵条目）", p.events.contains("store.discard"))
            assertEquals(RecordError.PREPARE_FAILED, p.field(recorder, "engineError"))
        } finally {
            p.call(recorder, "release")
        }
    }

    /** MR 路：实例构造抛运行时异常（applyConfig 都还没进）——外层兜底也必须关 fd、回收 pending。 */
    @Test
    fun `Y20 MrRecorder构造期异常也不漏fd与pending`() = platform { p ->
        p.static("android.media.MediaRecorder", "throwOnConstruct", true)
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(false, p.call(recorder, "prepare", p.profile(), p.sink()))
            assertEquals(
                "输出句柄必须关闭", 1,
                p.type("com.wotagei.cam.record.OutputTarget").getField("closes").get(null)
            )
            assertTrue("pending 必须回收", p.events.contains("store.discard"))
            assertEquals(RecordError.PREPARE_FAILED, p.field(recorder, "engineError"))
        } finally {
            p.call(recorder, "release")
        }
    }

    /** MR 路：prepare() 抛非 ISE 的运行时异常——applyConfig 的兜底分支必须把它翻成"本次下发失败"。 */
    @Test
    fun `Y20 MrRecorder applyConfig运行时异常按prepare失败收口`() = platform { p ->
        p.static("android.media.MediaRecorder", "prepareThrow", RuntimeException("boundary boom"))
        val recorder = p.recorder(mr = true)
        try {
            assertEquals(false, p.call(recorder, "prepare", p.profile(), p.sink()))
            assertTrue("pending 必须回收", p.events.contains("store.discard"))
            assertEquals(
                "输出句柄必须关闭", 1,
                p.type("com.wotagei.cam.record.OutputTarget").getField("closes").get(null)
            )
            assertEquals(RecordError.PREPARE_FAILED, p.field(recorder, "engineError"))
        } finally {
            p.call(recorder, "release")
        }
    }

    private data class Expected(
        val writes: List<String>, val tracks: List<String>, val releases: List<String>, val videoBuffered: Boolean = false
    )

    private fun ordinaryVideo(p: RecorderPlatformFixture, video: Any) {
        p.format(video)
        p.packet(video, 64, KEY_FRAME, 1_000)
        p.packet(video, 0, EOS, 3_000)
    }

    private fun drain(env: Boolean = false, cap: Boolean = false,
                      script: (RecorderPlatformFixture, Any, Any?, Any?) -> Expected) = platform { p ->
        val recorder = p.recorder()
        val v = p.codec("V")
        val a = if (env) p.codec("A") else null
        val c = if (cap) p.codec("C") else null
        val expected = script(p, v, a, c)
        val muxer = p.type("android.media.MediaMuxer").getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("boundary.mp4", 0)
        p.set(recorder, "profile", p.profile())
        p.set(recorder, "vCodec", v)
        p.set(recorder, "aCodec", a)
        p.set(recorder, "capCodec", c)
        p.set(recorder, "muxer", muxer)
        if (a != null) p.set(recorder, "feeder", p.new("com.wotagei.cam.record.AudioFeeder"))
        if (c != null) p.set(recorder, "capFeeder", p.new("com.wotagei.cam.record.AudioFeeder"))
        try {
            assertEquals(true, p.call(recorder, "runSegment"))
            assertNull("必须按三轨done正常收口，不接受错误路径假绿", p.field(recorder, "engineError"))
            @Suppress("UNCHECKED_CAST")
            val writes = muxer.javaClass.getField("writes").get(muxer) as List<String>
            @Suppress("UNCHECKED_CAST")
            val tracks = muxer.javaClass.getField("tracks").get(muxer) as List<String>
            assertEquals("所有有效payload只写一次，CONFIG与空EOS不写", expected.writes.sorted(), writes.sorted())
            assertEquals("不能因唯一有效包携带EOS就废掉该音轨", expected.tracks, tracks)
            assertEquals(expected.releases.sorted(), p.events.filter { it.startsWith("release:") }.map { it.removePrefix("release:") }.sorted())
            for (kind in listOf("V", "A", "C")) {
                assertEquals("同轨样本必须保持输出到达顺序", expected.writes.filter { it.startsWith("$kind:") },
                    writes.filter { it.startsWith("$kind:") })
            }
            // 音频held必须先写后释放；视频preMux允许先复制并释放、随后再补写。
            expected.writes.forEach { value ->
                val kind = value.substringBefore(':')
                if (kind != "V" || !expected.videoBuffered && !env && !cap) {
                    val marker = value.substringAfterLast(':').toInt() - 17
                    assertTrue("有效负载须在释放该输出前写入：$value", p.events.indexOf("write:$value") < p.events.indexOf("release:$kind:$marker"))
                }
            }
        } finally {
            p.call(recorder, "release")
        }
    }

    private fun platform(block: (RecorderPlatformFixture) -> Unit) {
        RecorderPlatformFixture(classes, requireNotNull(javaClass.classLoader)).use(block)
    }

    /** 把当前段的起钟拨到 [ms] 毫秒之前（封段判据的 MIN_KEEP_MS 门槛要真跨过去，不许靠 sleep 拖测试）。 */
    private fun ageSegment(p: RecorderPlatformFixture, recorder: Any, ms: Long) {
        val clock = p.field(recorder, "clock")!!
        val now = p.type("android.os.SystemClock").getMethod("elapsedRealtime").invoke(null) as Long
        p.set(clock, "segmentStartMs", now - ms)
    }

    companion object {
        private const val EOS = 4
        private const val CONFIG = 2
        private const val KEY_FRAME = 1
        @JvmField @ClassRule
        val temporary = TemporaryFolder(File("build/tmp/recorder-compatibility-test").apply { mkdirs() })
        private val classes: File by lazy { RecorderPlatformFixture.compile(temporary.newFolder("boundaries")) }
    }
}
