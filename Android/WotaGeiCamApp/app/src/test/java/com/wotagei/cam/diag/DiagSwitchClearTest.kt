package com.wotagei.cam.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 诊断日志**总开关（默认关）与清除**的纯函数表（2026-10-13 用户裁决）。
 *
 * 总开关关 ⇒ 常规记录一行都不写（省空间）；崩溃件不走这道门（取证不许被开关卡住，那条语义
 * 由 `DiagGuardTest` 的结构守卫钉住）。清除 = 删净当前件/轮转件/崩溃件、不动无关文件。
 */
class DiagSwitchClearTest {

    // ---- 总开关 × 级别档位（diagRecordGated = 落盘闸；diagLogcatGated = logcat 闸）----

    @Test
    fun `总开关关=不落盘（任何级别都不记，连详细档也救不回来）`() {
        for (lvl in listOf('V', 'D', 'I', 'W', 'E', 'X')) {
            assertTrue("关 + 默认档：$lvl 必须拒", diagRecordGated(lvl, enabled = false, verbose = false))
            assertTrue("关 + 详细档：$lvl 仍必须拒", diagRecordGated(lvl, enabled = false, verbose = true))
        }
    }

    @Test
    fun `logcat通道不看总开关（取证纪律，2026-10-26 拆闸）`() {
        // 总开关关着，i/w/e 也必须能进 logcat —— 真机排查全靠这一路
        for (lvl in listOf('I', 'W', 'E')) {
            assertFalse("总开关关：$lvl 仍要进 logcat", diagLogcatGated(lvl, verbose = false))
        }
        // d/v 仍受详细档（逐帧日志会冲掉系统环形缓冲，反而看不到关键事件）
        assertTrue(diagLogcatGated('D', verbose = false))
        assertTrue(diagLogcatGated('V', verbose = false))
        assertFalse(diagLogcatGated('D', verbose = true))
        assertFalse(diagLogcatGated('V', verbose = true))
    }

    @Test
    fun `总开关开=按级别档位判（关掉的只是总闸，不是档位）`() {
        for (lvl in listOf('I', 'W', 'E')) {
            assertFalse("开 + 默认档：$lvl 要记", diagRecordGated(lvl, enabled = true, verbose = false))
        }
        assertTrue("开 + 默认档：D 不记（要详细档）", diagRecordGated('D', enabled = true, verbose = false))
        assertTrue("开 + 默认档：V 不记（要详细档）", diagRecordGated('V', enabled = true, verbose = false))
        assertFalse("开 + 详细档：D 要记", diagRecordGated('D', enabled = true, verbose = true))
        assertFalse("开 + 详细档：V 要记", diagRecordGated('V', enabled = true, verbose = true))
        assertFalse("未知级别按要记处理 ⇒ 不拒（与 diagLevelEnabled 的口径一致）", diagRecordGated('X', enabled = true, verbose = false))
    }

    // ---- 清除的文件删除判据（diagClearLogFiles）----

    private fun tempDir(): File = Files.createTempDirectory("wota-diag-clear").toFile()

    @Test
    fun `清除删净当前件轮转件崩溃件，不动无关文件`() {
        val dir = tempDir()
        val keep = File(dir, "keep.txt").apply { writeText("留") }
        val logFiles = listOf(
            File(dir, "wotagei.log"),
            File(dir, "wotagei.log.1"),
            File(dir, "wotagei.log.2"),
            File(dir, "crash-20261013-120000.txt"),
            File(dir, "crash-20261013-130000.txt")
        ).onEach { it.writeText("x") }
        assertEquals("五件日志全被删", 5, diagClearLogFiles(dir, "wotagei.log", "crash-"))
        assertTrue("无关文件不许动", keep.isFile)
        logFiles.forEach { assertFalse("${it.name} 应已删净", it.exists()) }
    }

    @Test
    fun `目录不存在-为空-删不掉时都不炸且计数诚实`() {
        assertEquals("目录不存在回 0", 0, diagClearLogFiles(File(tempDir(), "nope"), "wotagei.log", "crash-"))
        assertEquals("空目录回 0", 0, diagClearLogFiles(tempDir(), "wotagei.log", "crash-"))
        // 名字像日志的**目录**不许当文件删（isFile 判据）
        val dir = tempDir()
        File(dir, "wotagei.log").mkdirs()
        assertEquals("同名目录不删", 0, diagClearLogFiles(dir, "wotagei.log", "crash-"))
        assertTrue("同名目录原样保留", File(dir, "wotagei.log").isDirectory)
    }

    @Test
    fun `轮转件判据按前缀匹配不误伤同前缀无关目录`() {
        val dir = tempDir()
        // 只有 baseName 的"前缀家族"（wotagei.log*）与 crash 前缀算命中；别的 base 名不动
        File(dir, "other.log").writeText("x")
        File(dir, "wotagei.log.10").writeText("x")
        assertEquals(1, diagClearLogFiles(dir, "wotagei.log", "crash-"))
        assertTrue("别的 base 名的日志不许动", File(dir, "other.log").isFile)
        assertTrue("深层轮转件也是轮转件", !File(dir, "wotagei.log.10").exists())
    }
}
