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
        RecorderPlatformFixture(classes, javaClass.classLoader).use(block)
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
