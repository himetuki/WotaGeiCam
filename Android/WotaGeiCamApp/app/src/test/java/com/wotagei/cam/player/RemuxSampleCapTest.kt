package com.wotagei.cam.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Y30（重封链残留）：单枚样本有上界，声明损坏时**显式失败**而不是按声明无限扩容。
 *
 * 旧实现 `if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)` 在容器声明的
 * 样本大小损坏（坏 index/错位读取）时会吃到 GB 级分配、`toInt()*2` 还可能整数溢出。现在超过
 * [ClipRule.MAX_REMUX_SAMPLE_BYTES] 一律抛错，沿既有未封轨失败路径回收 pending 行（成片没写成，
 * 不留幽灵条目），不静默截断、不降级。真实 4K 关键帧远低于该上限，正常导出行为不变。
 *
 * 注入点只用平台桩的脚本化样本大小，复制/回收控制流全部是生产字节码。
 */
class RemuxSampleCapTest {

    @Test
    fun `Y30 剪辑导出单枚视频样本超上限_显式失败并回收pending`() = platform { p ->
        p.script("sampleSizeOverride", ClipRule.MAX_REMUX_SAMPLE_BYTES + 1L)
        val result = runBlocking { p.clipExport(p.clipExporter()) }
        assertEquals("超限样本按未写成上报（MUX）", "MUX", p.call(result, "getReason").toString())
        assertTrue(
            "失败原因必须点名单样本上限：${p.events.filter { it.startsWith("error:") }}",
            p.events.any { it.startsWith("error:") && it.contains("超过单样本上限") }
        )
        assertEquals("未封轨输出由 owner 回收一次", 1, p.events.count { it == "pending.delete" })
        assertFalse("超限样本不得进入封轨", p.events.contains("muxer.stop"))
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(CLIP, CLEANUP_WAIT_MS))
    }

    @Test
    fun `Y30 选轨导出单枚视频样本超上限_显式失败并回收pending`() = platform { p ->
        p.script("sampleSizeOverride", ClipRule.MAX_REMUX_SAMPLE_BYTES + 1L)
        val result = runBlocking { p.trackExport(p.trackExporter()) }
        assertEquals("超限样本按未写成上报（MUX）", "MUX", p.call(result, "getReason").toString())
        assertTrue(
            "失败原因必须点名单样本上限：${p.events.filter { it.startsWith("error:") }}",
            p.events.any { it.startsWith("error:") && it.contains("超过单样本上限") }
        )
        assertEquals("未封轨输出由 owner 回收一次", 1, p.events.count { it == "pending.delete" })
        assertFalse("超限样本不得进入封轨", p.events.contains("muxer.stop"))
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(TRACK, CLEANUP_WAIT_MS))
    }

    /** 上界之内不误伤：贴着上限的正常样本照常完成（防止"一刀切坏正常导出"）。 */
    @Test
    fun `Y30 上限之内样本不误伤_导出正常完成`() = platform { p ->
        p.script("sampleSizeOverride", ClipRule.MAX_REMUX_SAMPLE_BYTES)
        val result = runBlocking { p.clipExport(p.clipExporter()) }
        assertEquals("贴上界的合法样本不得误判失败", "Done", result.javaClass.simpleName)
        assertTrue("正常路径必须真的封轨", p.events.contains("muxer.stop"))
        assertTrue("收尾必须关掉本 loader 的生产执行器", p.shutdownOwner(CLIP, CLEANUP_WAIT_MS))
    }

    private fun platform(block: suspend (ExportPlatformFixture) -> Unit) = runBlocking {
        ExportPlatformFixture(classes, javaClass.classLoader!!).use { block(it) }
    }

    companion object {
        private const val CLIP = "com.wotagei.cam.player.ClipExporter"
        private const val TRACK = "com.wotagei.cam.player.TrackExporter"
        private const val CLEANUP_WAIT_MS = 5_000L

        @ClassRule
        @JvmField
        val temporary = TemporaryFolder(File("build/tmp/export-remux-sample-cap-test").apply { mkdirs() })
        private val classes by lazy { ExportPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
