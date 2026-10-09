package com.wotagei.cam.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志的**格式与档位**纯函数表（2026-10-09，用户指令「把日志功能加上」）。
 *
 * 这些函数决定"写出来的文件长什么样、多大、叫什么名字"，是导出件可读性的全部依据：
 * 行格式错则时间线读不出来，文件名带冒号则写不进去（Android/Windows 都不认），
 * 档位判错则要么把用户日志刷爆、要么把关键事件丢掉。
 */
class DiagFormatTest {

    // ---- 行格式 ----

    @Test
    fun `日志行格式：时间 级别 TAG 线程 正文`() {
        val line = diagLine("10-09 14:03:22.123", 'W', "WotaRec", "wota-handler", "换段腿超预算 3000ms")
        assertEquals("10-09 14:03:22.123 W WotaRec [wota-handler] 换段腿超预算 3000ms\n", line)
    }

    @Test
    fun `正文里的换行被压成一行（保证一行一条，导出件可 grep）`() {
        val line = diagLine("10-09 14:03:22.123", 'E', "T", "t", "第一行\n第二行\r\n第三行")
        assertFalse("不许出现裸换行", line.trimEnd('\n').contains("\n"))
        assertTrue("换行要变成可见符号", line.contains("⏎"))
        assertEquals("整行仍以单个换行收尾", 1, line.length - line.trimEnd('\n').length)
    }

    // ---- 档位 ----

    @Test
    fun `默认档只记 i-w-e，详细档才带 d-v`() {
        for (lvl in listOf('I', 'W', 'E')) {
            assertTrue("$lvl 必须常记", diagLevelEnabled(lvl, verbose = false))
            assertTrue("$lvl 详细档也记", diagLevelEnabled(lvl, verbose = true))
        }
        assertFalse("默认档不记 D", diagLevelEnabled('D', verbose = false))
        assertFalse("默认档不记 V", diagLevelEnabled('V', verbose = false))
        assertTrue("详细档记 D", diagLevelEnabled('D', verbose = true))
        assertTrue("详细档记 V", diagLevelEnabled('V', verbose = true))
    }

    @Test
    fun `未知级别按要记处理（宁可多记，别把新增级别悄悄丢了）`() {
        assertTrue(diagLevelEnabled('X', verbose = false))
    }

    // ---- 轮转与命名 ----

    @Test
    fun `到上限才轮转`() {
        val max = Diag.DIAG_FILE_MAX_BYTES
        assertFalse("差一字节不轮转", diagShouldRotate(max - 1, max))
        assertTrue("刚好到上限就轮转", diagShouldRotate(max, max))
        assertTrue("超了当然轮转", diagShouldRotate(max + 1, max))
    }

    @Test
    fun `轮转件名递增且带序号`() {
        assertEquals("wotagei.log.1", diagRotatedName("wotagei.log", 1))
        assertEquals("wotagei.log.2", diagRotatedName("wotagei.log", 2))
    }

    @Test
    fun `文件名不许含冒号（Android-Windows 都不认）`() {
        val stamp = diagFileStamp(1_760_000_000_000L)
        for (name in listOf(diagExportName(stamp), diagCrashName(stamp))) {
            assertFalse("$name 含冒号", name.contains(":"))
            assertFalse("$name 含空格", name.contains(" "))
            assertTrue("$name 应是 txt", name.endsWith(".txt"))
        }
        assertTrue("导出件名带前缀", diagExportName(stamp).startsWith("wotagei-log-"))
        assertTrue("崩溃件名带前缀", diagCrashName(stamp).startsWith("crash-"))
    }

    @Test
    fun `两个时间戳函数各司其职：行内可读、文件名安全`() {
        val ms = 1_760_000_000_000L
        assertTrue("行内形如 MM-dd HH:mm:ss.SSS", Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").matches(diagStamp(ms)))
        assertTrue("文件名形如 yyyyMMdd-HHmmss", Regex("""\d{8}-\d{6}""").matches(diagFileStamp(ms)))
    }

    // ---- 导出件头 ----

    @Test
    fun `导出件头含用途与隐私声明，并原样带上设备信息`() {
        val header = diagHeader("应用=com.wotagei.cam 版本=0.0.7-r25\n系统=Android 11", "com.wotagei.cam")
        assertTrue("要有用途说明", header.contains("用途"))
        assertTrue("要说明不外传", header.contains("不会上传"))
        assertTrue("要说明不含画面与声音", header.contains("不含画面与声音"))
        assertTrue("设备信息原样带上", header.contains("Android 11"))
        assertTrue("包名带上", header.contains("com.wotagei.cam"))
    }

    @Test
    fun `上限常量取值合理（防手滑改成 0 或超大）`() {
        assertTrue("环形缓冲至少留几百行", Diag.DIAG_RING_LINES in 200..20_000)
        assertTrue("单文件上限 64KB~4MB 之间", Diag.DIAG_FILE_MAX_BYTES in 64L * 1024..4L * 1024 * 1024)
        assertTrue("保留件数 2~5", Diag.DIAG_FILE_KEEP in 2..5)
        assertTrue("导出件上限不超过 8MB", Diag.DIAG_EXPORT_MAX_BYTES <= 8L * 1024 * 1024)
        assertTrue("崩溃件上下文至少 100 行", Diag.DIAG_CRASH_TAIL_LINES >= 100)
    }
}
