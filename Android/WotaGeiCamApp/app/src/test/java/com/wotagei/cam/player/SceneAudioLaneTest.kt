package com.wotagei.cam.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * Y30：后渲染导出的音频车道——**按平移后 PTS 与视频交错写出**，超前时长有界（0.5 秒窗口），
 * 窗口外样本留在 extractor 里**不读进内存**（等待而非积压），单枚样本超过上限显式失败而不是
 * 按样本大小无限扩容。
 *
 * 全部用真实 [SceneAudioLane] 字节码与真实 Runner 跑：桩只提供脚本化样本流与 muxer 账，
 * 不另写复制控制流，也不在 fake 里替生产决定窗口。
 */
class SceneAudioLaneTest {

    @Test
    fun `Y30 窗口外样本不读入内存_等待视频追上而非积压`() = platform { p ->
        p.script("audioPts", longArrayOf(0, 100_000, 200_000, 300_000))
        val ex = p.new("android.media.MediaExtractor")
        val muxer = p.muxer()
        val track = p.call(muxer, "addTrack", p.call(ex, "getTrackFormat", 2))
        p.call(muxer, "start")
        val lane = p.lane(ex, 2, muxer, 0L, 0L, 1_000_000L)
        try {
            p.call(lane, "arm", p.audioSeekUs(0L, 0L), track)
            assertTrue("窗口外还有样本 ⇒ 车道不封口（等视频追上）", p.call(lane, "drainUpTo", 0L) as Boolean)
            assertEquals("只写窗口内的 0ms 样本", listOf(0L), p.list<Long>("audioWritten"))
            assertEquals("窗口外样本不得读入内存（等待而非积压）", listOf(0), p.list<Int>("audioReads"))

            assertTrue(p.call(lane, "drainUpTo", 250_000L) as Boolean)
            assertEquals(listOf(0L, 100_000L, 200_000L), p.list<Long>("audioWritten"))
            assertEquals("只读到 250ms 窗口内", listOf(0, 1, 2), p.list<Int>("audioReads"))

            p.call(lane, "drainAll")
            assertEquals(
                "视频 EOS 后尾部一次排空（窗口不再有意义）",
                listOf(0L, 100_000L, 200_000L, 300_000L), p.list<Long>("audioWritten")
            )
            assertEquals("段尾汇总只落一条 i 级（B06 不逐样本刷屏）", 1,
                p.events.count { it == "info:音轨写入=4 pts修正=0(back=0us)" })
        } finally {
            p.call(muxer, "release")
        }
    }

    @Test
    fun `Y30 超上限样本显式失败而不是按样本无限扩容`() = platform { p ->
        p.script("audioPts", longArrayOf(0, 100_000))
        p.script("sampleSizeOverride", 5L * 1024 * 1024)
        val ex = p.new("android.media.MediaExtractor")
        val muxer = p.muxer()
        val track = p.call(muxer, "addTrack", p.call(ex, "getTrackFormat", 2))
        p.call(muxer, "start")
        val lane = p.lane(ex, 2, muxer, 0L, 0L, 1_000_000L)
        try {
            p.call(lane, "arm", p.audioSeekUs(0L, 0L), track)
            p.call(lane, "drainUpTo", 0L)
            fail("超限样本必须显式失败（随后 pending 由 owner 回收）")
        } catch (e: IllegalStateException) {
            assertTrue(
                "失败原因必须点名单样本上限：${e.message}",
                e.message?.contains("超过单样本上限") == true
            )
        } finally {
            p.call(muxer, "release")
        }
        assertTrue("超限样本不得写出", !p.events.contains("audio.write"))
        assertEquals(0, p.list<Long>("audioWritten").size)
    }

    @Test
    fun `Y30 真实后渲染导出按PTS交错_音频超前不超过半秒`() = platform { p ->
        p.script("videoPts", longArrayOf(0, 1_000_000))
        p.script("videoSync", booleanArrayOf(true, true))
        p.script("audioPts", LongArray(20) { it * 100_000L })
        // 每次视频写出后记录"音频已写出/已读入"的量：窗口 = 该帧 PTS + LEAD_US。
        val windows = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        p.script("afterVideoWrite", Runnable {
            windows.add(p.list<Long>("audioWritten").size to p.list<Int>("audioReads").size)
        })
        val result = p.export(p.runner(), durationMs = 3_000, endMs = 2_000)
        assertEquals("Done", result.javaClass.simpleName)
        assertEquals(
            "两次视频写出时音频在途量必须有界：首帧封轨前为0，第二帧只超前到1.5s（16枚），不得全部20枚",
            listOf(0 to 0, 16 to 16), windows.toList()
        )
        assertEquals("视频 EOS 后尾部排空：全部 20 枚写出", 20, p.list<Long>("audioWritten").size)
        assertEquals(
            "音频写出 PTS = 源 PTS（shift=0、单调不减）",
            LongArray(20) { it * 100_000L }.toList(), p.list<Long>("audioWritten")
        )
        assertEquals("段尾汇总一条 i 级（B06）", 1,
            p.events.count { it == "info:音轨写入=20 pts修正=0(back=0us)" })
    }

    private fun platform(block: suspend (ExportPlatformFixture) -> Unit) = runBlocking {
        ExportPlatformFixture(classes, javaClass.classLoader).use { block(it) }
    }

    companion object {
        @ClassRule @JvmField
        val temporary = TemporaryFolder(File("build/tmp/export-audio-lane-test").apply { mkdirs() })
        private val classes by lazy { ExportPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}