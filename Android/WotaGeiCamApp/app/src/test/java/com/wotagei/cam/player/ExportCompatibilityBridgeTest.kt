package com.wotagei.cam.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** B01/B02/B04：运行真实预扫、音轨复制和 suspend export，不在测试里另写导出控制流。 */
class ExportCompatibilityBridgeTest {
    @Test
    fun `B01 CAP前移1000ms预扫可写则真实复制必须写出而非32包后放弃`() = platform { p ->
        p.script("audioPts", LongArray(160) { it * 1024L * 1_000_000L / 48_000L })
        val ex = p.new("android.media.MediaExtractor")
        assertTrue("真实预扫必须先证明选中轨存在可写样本", p.writable(ex, 0, -1_000_000, 2_000_000))
        copyAudio(p, ex, 0, TrackRule.shiftUsFor(SelectedTrack.CAP, 1000), 2_000_000)
        val written = p.list<Long>("audioWritten")
        assertTrue("真实 copyAudio 不得在第33个负PTS样本停掉、留下零样本音轨", written.isNotEmpty())
        assertEquals("48k/1024 第47包是首个非负平移样本，不应钳成0", 2_666L, written.first())
        assertTrue("不能把仍选着的视频或ENV数据混进CAP", p.list<Int>("audioTracks").all { it == 2 })
    }

    @Test
    fun `B01 ENV与CAP偏移矩阵预扫和真实写出PTS一致`() {
        val issues = mutableListOf<String>()
        for (track in SelectedTrack.values()) for (offset in listOf(-1000L, 0L, 500L, 1000L)) {
            for (packetSamples in listOf(960L, 1024L)) platform { p ->
                val pts = LongArray(220) { it * packetSamples * 1_000_000L / 48_000L }
                p.script("audioPts", pts)
                // base 跨过AAC包界；独立oracle不调用任何生产PTS/可写helper。
                val base = 21_334L
                val shift = if (track == SelectedTrack.CAP) -offset * 1000L else 0L
                val seekIndex = pts.indexOfLast { it <= base }.coerceAtLeast(0)
                val expected = pts.drop(seekIndex).map { it - base + shift }.filter { it in 0..2_000_000L }
                val ex = p.new("android.media.MediaExtractor")
                val audioIdx = if (track == SelectedTrack.ENV) 1 else 2
                val writable = p.writable(ex, base, shift, 2_000_000, audioIdx)
                copyAudio(p, ex, base, TrackRule.shiftUsFor(track, offset), base + 2_000_000, audioIdx)
                val actual = p.list<Long>("audioWritten")
                assertTrue("只能复制指定的源音轨", p.list<Int>("audioTracks").all { it == audioIdx })
                if (writable != expected.isNotEmpty() || actual != expected) {
                    issues.add("$track offset=$offset packet=$packetSamples 预扫=$writable 实写=${actual.size} 期望=${expected.size}")
                }
            }
        }
        assertEquals("预扫和写出必须是同一行为口径，不能靠helper同名假绿", emptyList<String>(), issues)
    }

    @Test
    fun `B01 真实复制先撤掉全部选择且负PTS丢弃不钳零`() = platform { p ->
        p.script("audioPts", longArrayOf(0, 20_000, 40_000, 60_000))
        val ex = p.new("android.media.MediaExtractor")
        p.call(ex, "selectTrack", 0)
        p.call(ex, "selectTrack", 1)
        copyAudio(p, ex, 0, -30_000, 1_000_000)
        assertEquals(listOf(10_000L, 30_000L), p.list<Long>("audioWritten"))
        assertEquals(listOf(2, 2), p.list<Int>("audioTracks"))
        val finalSelection = p.events.lastIndexOf("select:2")
        assertTrue((0..2).all { p.events.lastIndexOf("unselect:$it") in 0 until finalSelection })
    }

    @Test
    fun `B01 真实复制对同轨重复和倒退PTS仍走单调桥`() = platform { p ->
        p.script("audioPts", longArrayOf(0, 30_000, 20_000, 20_000, 40_000))
        copyAudio(p, p.new("android.media.MediaExtractor"), 0, 0, 100_000)
        assertEquals(listOf(0L, 30_000L, 30_001L, 30_002L, 40_000L), p.list<Long>("audioWritten"))
    }

    @Test
    fun `B01 真实复制保留尾部容忍内回界样本并在连续8包越界后停止`() = platform { p ->
        p.script("audioPts", longArrayOf(0, 80_000, 110_000, 90_000,
            120_000, 121_000, 122_000, 123_000, 124_000, 125_000, 126_000, 127_000, 70_000))
        copyAudio(p, p.new("android.media.MediaExtractor"), 0, 0, 100_000)
        assertEquals(listOf(0L, 80_000L, 90_000L), p.list<Long>("audioWritten"))
    }

    @Test
    fun `B01 平移后全不可写的真实export不建零样本音轨`() = platform { p ->
        p.script("audioPts", longArrayOf(0, 100_000, 200_000))
        val result = p.export(p.runner(), track = SelectedTrack.CAP, offsetMs = 1000)
        assertEquals("Done", result.javaClass.simpleName)
        assertFalse(p.events.any { it == "track.add:audio/mp4a-latm" })
        assertTrue(p.list<Long>("audioWritten").isEmpty())
    }

    @Test
    fun `B02 第3包音频写完取消真实export不得封轨提交`() = platform { p ->
        cancelDuringAudio(p, atWrite = 3)
    }

    @Test
    fun `B02 最后一包音频写完取消也不得封轨提交`() = platform { p ->
        cancelDuringAudio(p, atWrite = 5)
    }

    @Test
    fun `B02 音轨advance到EOF窄窗取消也不得提交`() = platform { p ->
        val job = Job()
        p.script("beforeAudioEof", Runnable { job.cancel() })
        val cancelled = cancelledExport(p, job)
        assertTrue("取消必须原样抛出而非Failed或Done", cancelled)
        assertEquals(5, p.list<Long>("audioWritten").size)
        assertNoUnsealedPublication(p)
    }

    @Test
    fun `B02 封轨前stop抛错回收pending并报MUXER`() = platform { p ->
        p.script("stopThrows", true)
        val result = p.export(p.runner())
        assertEquals("MUXER", p.call(result, "getReason").toString())
        assertEquals(1, p.events.count { it == "pending.delete" })
        assertFalse(p.events.contains("pending.commit"))
        assertResourcesReleased(p)
    }

    @Test
    fun `B02 正常封轨后commit两次零行或抛错必须保留完整成片`() {
        for (commitResult in listOf(0, -1)) platform { p ->
            p.script("commitResult", commitResult)
            val result = p.export(p.runner())
            assertEquals("COMMIT", p.call(result, "getReason").toString())
            assertEquals(1, p.events.count { it == "muxer.stop" })
            assertEquals(2, p.events.count { it == "pending.commit" })
            assertFalse("已封轨成片不得被MediaStore删行连带删除", p.events.contains("pending.delete"))
            assertResourcesReleased(p)
        }
    }

    @Test
    fun `B02 stop成功返回后的取消不得删除已封轨成片`() = platform { p ->
        val job = Job()
        p.script("afterStop", Runnable { job.cancel() })
        assertTrue(cancelledExport(p, job))
        assertEquals(1, p.events.count { it == "muxer.stop" })
        assertFalse(p.events.contains("pending.delete"))
        assertResourcesReleased(p)
    }

    @Test
    fun `B03 真实Scene渲染失败不得计成功或封轨公开`() = platform { p ->
        p.script("renderOk", false)
        val result = p.export(p.runner())
        assertEquals("GL", p.call(result, "getReason").toString())
        assertTrue(p.list<Long>("renderedSource").isEmpty())
        assertEquals(1, p.events.count { it == "pending.delete" })
        assertFalse(p.events.contains("muxer.stop"))
        assertFalse(p.events.contains("pending.commit"))
        assertResourcesReleased(p)
    }

    @Test
    fun `B04 无预取且首次越界前PTS单调也不得丢掉尚未读取的300ms目标帧`() = platform { p ->
        // 两个源可以共享完全相同的单调前缀；前缀本身不能证明余片再无界内PTS。
        p.script("videoPts", longArrayOf(0, 100_000, 200_000, 400_000, 300_000, 500_000))
        p.script("videoSync", booleanArrayOf(true, false, false, false, false, false))
        p.script("audioCount", 0)
        val result = p.export(p.runner(), durationMs = 1_000, endMs = 400)
        assertEquals("Done", result.javaClass.simpleName)
        assertEquals("400ms到来时300ms尚未喂给decoder；EOS排空不能凭空恢复未读样本",
            listOf(0L, 100_000L, 200_000L, 300_000L), p.list<Long>("renderedSource"))
        assertEquals(listOf(0L, 100_000L, 200_000L, 300_000L), p.list<Long>("renderedPts"))
        assertEquals(1, p.script("decoderEos"))
        assertEquals(1, p.script("encoderEos"))
    }

    @Test
    fun `B04 越界后缓存回退帧排出也不能代替继续读取剩余目标帧`() = platform { p ->
        p.script("videoPts", longArrayOf(0, 100_000, 400_000, 200_000, 300_000, 500_000))
        p.script("videoSync", booleanArrayOf(true, false, false, false, false, false))
        p.script("audioCount", 0)
        // 只描述codec外部调度：第四次喂入才输出400，200留在缓存，300仍未喂入。
        // EOS只能放出已经收到的200；不能由fixture替Runner读源或决定何时截断。
        p.script("decoderReadyAfterInputs", intArrayOf(1, 2, 4, 4, 5, 6))
        val result = p.export(p.runner(), durationMs = 1_000, endMs = 400)
        assertEquals("Done", result.javaClass.simpleName)
        assertEquals("已见缓存内200回退不意味着所有目标帧已进入decoder，还必须保住未读300",
            listOf(0L, 100_000L, 200_000L, 300_000L), p.list<Long>("renderedSource"))
        assertEquals(listOf(0L, 100_000L, 200_000L, 300_000L), p.list<Long>("renderedPts"))
        assertEquals(1, p.script("decoderEos"))
        assertEquals(1, p.script("encoderEos"))
    }

    @Test
    fun `B04 B帧跨出点前向参考与EOS尾排空仍保留全部目标帧`() = platform { p ->
        setBFrameSource(p, frames = 13)
        val result = p.export(p.runner(), durationMs = 1_000, endMs = 400)
        assertEquals("Done", result.javaClass.simpleName)
        assertEquals(TARGET_0_TO_400, p.list<Long>("renderedSource"))
        assertEquals(TARGET_0_TO_400, p.list<Long>("renderedPts"))
        assertEquals("输入EOS只能送一次，并须排空decoder后才结束encoder", 1, p.script("decoderEos"))
        assertEquals(1, p.script("encoderEos"))
        assertTrue(p.events.indexOf("decoder.eos") < p.events.indexOf("encoder.eos"))
    }

    @Test
    fun `B04 开放GOP负前导不能改变入点目标帧或旋转口径`() = platform { p ->
        setBFrameSource(p, frames = 31, openGop = true)
        val result = p.export(p.runner(), durationMs = 2_000, endMs = 400)
        assertEquals("Done", result.javaClass.simpleName)
        assertEquals(TARGET_0_TO_400, p.list<Long>("renderedSource"))
        assertTrue("前导负PTS和出点外帧不送编码器", p.list<Long>("renderedPts").all { it in 0 until 400_000L })
        assertTrue("必须剥解码format拷贝的旋转键", p.events.contains("decoder.rotation:false"))
        assertTrue("容器方向仍只写源旋转", p.events.contains("muxer.rotation:90"))
    }

    @Test
    fun `B04 相同片头短区间工作量不应随源剩余30秒和10分钟增长`() {
        val work = mutableListOf<Work>()
        for (frames in listOf(901, 18_001)) platform { p ->
            setBFrameSource(p, frames)
            val result = p.export(p.runner(), durationMs = frames * 1000L / 30, endMs = 400)
            assertEquals("Done", result.javaClass.simpleName)
            assertEquals("性能优化不得漏掉出点前迟到B帧", TARGET_0_TO_400, p.list<Long>("renderedSource"))
            work.add(Work(p.script("reads") as Int, p.script("decoded") as Int,
                p.script("awaits") as Int, p.script("acquiredOnly") as Int))
        }
        // 这里只声明此脚本的有限依赖边界（P参考跨度3、decoder缓存2），不是给生产硬编码通用32帧预算。
        // 未知codec/CSD不得靠猜此数截断；任何后续修复仍须独立证明适用范围。
        val targetFrames = 12
        val scriptedForwardReferenceSpan = 3
        val scriptedDecoderReorder = 2
        val scriptedBudget = targetFrames + scriptedForwardReferenceSpan + scriptedDecoderReorder
        assertTrue("正确性成立后，喂入/解码/Surface消费才应被目标区间与已知依赖界限约束：$work",
            work.all { it.reads <= scriptedBudget && it.decoded <= scriptedBudget && it.awaits <= scriptedBudget })
        assertEquals("同区间不应把10分钟余片全解码/搬进Surface", work[0], work[1])
    }

    @Test
    fun `B04 中部短区间仍保留关键帧预卷且不解码到整片尾`() = platform { p ->
        setBFrameSource(p, frames = 1_801)
        val result = p.export(p.runner(), durationMs = 60_000, startMs = 1_000, endMs = 1_400)
        assertEquals("Done", result.javaClass.simpleName)
        assertEquals(TARGET_0_TO_400.map { it + 1_000_000L }, p.list<Long>("renderedSource"))
        assertEquals(TARGET_0_TO_400, p.list<Long>("renderedPts"))
        assertTrue("脚本在1秒处有同步帧；不得把后续整片均解码", (p.script("decoded") as Int) <= 17)
    }

    @Test
    fun `B04 视频重排排空期间取消仍须清未封轨pending`() = platform { p ->
        setBFrameSource(p, frames = 901)
        val job = Job()
        p.script("afterDecoded", Runnable { if (p.script("decoded") == 3) job.cancel() })
        val cancelled = try {
            withContext(job) { p.export(p.runner(), durationMs = 30_000, endMs = 400) }
            false
        } catch (_: CancellationException) { true }
        assertTrue(cancelled)
        assertEquals(3, p.script("decoded"))
        assertNoUnsealedPublication(p)
    }

    @Test
    fun `B04 真实export保留deadline保护并释放未封轨资源`() = platform { p ->
        p.script("clockStepMs", 120_001L)
        val result = p.export(p.runner())
        assertEquals("TIMEOUT", p.call(result, "getReason").toString())
        assertTrue(p.list<Long>("renderedSource").isEmpty())
        assertNoUnsealedPublication(p)
    }

    private suspend fun cancelDuringAudio(p: ExportPlatformFixture, atWrite: Int) {
        val job = Job()
        p.script("afterAudioWrite", Runnable { if (p.list<Long>("audioWritten").size == atWrite) job.cancel() })
        assertTrue(cancelledExport(p, job))
        assertEquals("必须真正到达题面音轨复制阶段", atWrite, p.list<Long>("audioWritten").size)
        assertNoUnsealedPublication(p)
    }
    private suspend fun cancelledExport(p: ExportPlatformFixture, job: Job): Boolean = try {
        withContext(job) { p.export(p.runner()) }
        false
    } catch (_: CancellationException) { true }
    private fun assertNoUnsealedPublication(p: ExportPlatformFixture) {
        // caller 取消是立即完成的，owner 的 delete/finally 仍在后台；先等它退出再读资源账。
        assertTrue("取消后 owner 必须在有界时间内完成清理", p.awaitOwnerQuiescence(5_000L))
        assertFalse("封轨前取消不能继续stop并把截断音轨当成完整成片", p.events.contains("muxer.stop"))
        assertFalse("取消绝不能先commit再靠withContext末尾抛异常假装已撤销", p.events.contains("pending.commit"))
        assertEquals(1, p.events.count { it == "pending.delete" })
        assertResourcesReleased(p)
    }
    private fun assertResourcesReleased(p: ExportPlatformFixture) {
        for (event in listOf("decoder.release", "encoder.release", "muxer.release", "gl.release", "extractor.release", "fd.close")) {
            assertTrue("finally必须归还$event", p.events.contains(event))
        }
    }
    private fun copyAudio(p: ExportPlatformFixture, ex: Any, base: Long, shift: Long, end: Long, audioIdx: Int = 2) {
        val muxer = p.type("android.media.MediaMuxer").getConstructor(java.io.FileDescriptor::class.java, Int::class.javaPrimitiveType)
            .newInstance(java.io.FileDescriptor(), 0)
        p.call(muxer, "addTrack", p.call(ex, "getTrackFormat", audioIdx))
        p.call(muxer, "start")
        try {
            p.call(p.runner(), "copyAudio", ex, audioIdx, 0, muxer, base, shift, end, Job())
        } finally {
            p.call(muxer, "release")
        }
    }
    private fun setBFrameSource(p: ExportPlatformFixture, frames: Int, openGop: Boolean = false) {
        // H.264式解码序 I0 P3 B1 B2 P6 B4 B5；decoder缓存两个显示帧，EOS再排尾。
        // 这里只脚本化codec外部边界，不在fake内判断区间、停止喂入或替生产决定EOS。
        val order = mutableListOf(0L)
        if (openGop) order.addAll(listOf(-66_666L, -33_333L))
        var i = 1
        while (i < frames) {
            val last = minOf(i + 2, frames - 1)
            order.add(last * 1_000_000L / 30)
            for (b in i until last) order.add(b * 1_000_000L / 30)
            i = last + 1
        }
        val pts = order.toLongArray()
        p.script("videoPts", pts)
        p.script("videoSync", BooleanArray(pts.size) { pts[it] >= 0 && pts[it] % 1_000_000L == 0L })
        p.script("reorderDepth", 2)
        p.script("audioCount", 0)
    }
    private data class Work(val reads: Int, val decoded: Int, val awaits: Int, val acquireOnly: Int)
    private fun platform(block: suspend (ExportPlatformFixture) -> Unit) = runBlocking {
        ExportPlatformFixture(classes, javaClass.classLoader).use { block(it) }
    }
    companion object {
        @ClassRule @JvmField
        val temporary = TemporaryFolder(File("build/tmp/export-compatibility-test").apply { mkdirs() })
        private val classes by lazy { ExportPlatformFixture.compile(temporary.newFolder("platform")) }
        private val TARGET_0_TO_400 = listOf(0L, 33_333L, 66_666L, 100_000L, 133_333L, 166_666L,
            200_000L, 233_333L, 266_666L, 300_000L, 333_333L, 366_666L)
    }
}
