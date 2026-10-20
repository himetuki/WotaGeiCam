package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * [ArcDropLog.renameFor] 的不覆盖守卫（2026-10-20）。
 *
 * 动机：File#renameTo 在 Linux 语义下目标存在即**静默替换**。清扫侧 RENAME_ARCHIVE 早已把
 * "目标已存在绝不 rename"定为红线并加了双保险，但 sidecar 搬运的单一出口 renameFor 没有同款
 * 守卫——MediaActions.rename 在回查真实名失败时退回"请求名"拼目标，撞上他片 sidecar 就会把
 * 别片的位次档案静默吃掉。本文件用真实 File 操作钉住"目标已存在 ⇒ 拒搬、源与目标都原样"。
 */
class ArcDropLogRenameGuardTest {

    @Test
    fun `目标已存在时拒搬_源与目标都原样`() {
        createTempDirectory("arc-rename-guard").toFile().useDir { dir ->
            val from = File(dir, "a.drops.json").apply { writeText("A") }
            val to = File(dir, "b.drops.json").apply { writeText("B") }
            val moved = ArcDropLog.renameFor(
                File(dir, "a.mp4").absolutePath,
                File(dir, "b.mp4").absolutePath
            )
            assertFalse("目标已存在必须拒搬（Linux rename 是静默替换，照搬会吃掉别片档案）", moved)
            assertEquals("目标内容不得被动过", "B", to.readText())
            assertTrue("源档案必须留在原地", from.isFile)
            assertEquals("A", from.readText())
        }
    }

    @Test
    fun `目标不存在时照常搬`() {
        createTempDirectory("arc-rename-guard").toFile().useDir { dir ->
            val from = File(dir, "a.drops.json").apply { writeText("A") }
            val moved = ArcDropLog.renameFor(
                File(dir, "a.mp4").absolutePath,
                File(dir, "b.mp4").absolutePath
            )
            assertTrue("目标不存在时必须照常搬走", moved)
            assertFalse(from.isFile)
            assertEquals("A", File(dir, "b.drops.json").readText())
        }
    }

    private fun File.useDir(block: (File) -> Unit) {
        try {
            block(this)
        } finally {
            deleteRecursively()
        }
    }
}
