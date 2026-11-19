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
 * Y32：失败回收的**删行核对**与留痕重试。
 *
 * 缺陷：旧实现只 `runCatching { delete }` 不看受影响行数——删除没生效（0 行/抛异常）也会留一条
 * 半成品 pending 行而日志毫无痕迹。现在 0 行/抛异常重试一次，两次都没生效必须留痕（行还在盘上，
 * 冷启救援接手），且日志只写 MediaStore 数字 id（B06 不落片名/完整 URI）。
 *
 * 「缺尾段文件不晋升」由 MoovSealTest 的封轨闸字节表钉住（救援 extractable 先过 MoovSeal）。
 */
class RemuxDeleteTraceTest {

    @Test
    fun `Y32 两次零行删除失败_行保留并留痕待冷启救援`() = platform { p ->
        p.script("deleteResults", intArrayOf(0, 0))
        val deleted = p.deletePendingRowVerified(
            p.new("android.content.Context"), p.uri("content://output/42"), "ClipExport"
        )
        assertFalse("两次零行不得谎报已删", deleted)
        assertEquals("删失败必须重试一次（共 2 次尝试）", 2, p.script("deleteCalls"))
        val warns = p.events.filter { it.startsWith("warn:") }
        assertTrue("首次未生效必须留痕（带数字 id 与尝试序号）：$warns",
            warns.any { it.contains("删 pending 未生效 id=42") && it.contains("attempt=1") })
        assertTrue("重试后仍未生效必须留痕：$warns",
            warns.any { it.contains("删 pending 未生效 id=42") && it.contains("attempt=2") })
        assertTrue("两次未生效必须声明行保留待冷启救援：$warns",
            warns.any { it.contains("删 pending 两次未生效 id=42") && it.contains("行保留待冷启救援") })
        assertFalse("日志不得落完整 URI（B06）", warns.any { it.contains("content://") })
    }

    @Test
    fun `Y32 首次删除抛异常后重试成功_抛异常必须留痕`() = platform { p ->
        p.script("deleteResults", intArrayOf(-1, 1))
        val deleted = p.deletePendingRowVerified(
            p.new("android.content.Context"), p.uri("content://output/42"), "ClipExport"
        )
        assertTrue("重试成功必须报已删", deleted)
        assertEquals(2, p.script("deleteCalls"))
        val warns = p.events.filter { it.startsWith("warn:") }
        assertTrue("Provider 抛异常必须留痕（不许静默吞掉）：$warns",
            warns.any { it.contains("删 pending 抛异常 id=42 attempt=1") })
        assertFalse("重试成功不得再报未生效/保留：$warns",
            warns.any { it.contains("未生效") })
    }

    @Test
    fun `Y32 剪辑导出失败回收删不掉时不得谎报已删`() = platform { p ->
        p.script("deleteResults", intArrayOf(0, 0))
        p.script("stopThrows", true)
        val exporter = p.clipExporter()
        val result = runBlocking { p.clipExport(exporter) }
        assertEquals("封轨失败按 MUX 上报", "MUX", p.call(result, "getReason").toString())
        assertEquals("删两连败仍按核对口径各尝试一次", 2, p.events.count { it == "pending.delete" })
        assertFalse("未封轨输出不得公开提交", p.events.contains("pending.commit"))
        val warns = p.events.filter { it.startsWith("warn:") }
        assertTrue("失败回收必须把'没删掉'说清楚：$warns",
            warns.any { it.contains("删 pending 两次未生效 id=42") })
        assertEquals("输出 FD 仍由 owner 关闭一次", 1, p.events.count { it == "fd.close" })
        assertTrue("收尾必须关掉本 loader 的生产执行器",
            p.shutdownOwner("com.wotagei.cam.player.ClipExporter", 5_000L))
    }

    private fun platform(block: suspend (ExportPlatformFixture) -> Unit) = runBlocking {
        ExportPlatformFixture(classes, javaClass.classLoader).use { block(it) }
    }

    companion object {
        @ClassRule @JvmField
        val temporary = TemporaryFolder(File("build/tmp/export-remux-delete-test").apply { mkdirs() })
        private val classes by lazy { ExportPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}