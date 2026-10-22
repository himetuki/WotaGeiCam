package com.wotagei.cam.update

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新探测/下载的**响应体上限守卫**（2026-10-07 缺陷修复）。
 *
 * 缺陷：`fetchJson` 曾 `readText()` 无界读入内存——探测链上有 4 枚第三方镜像前缀（不可信，
 * PK 魔数就是为它们设的），镜像回数 GB 的"JSON"即 OOM；`tryNode` 在无 Content-Length 且
 * 探测未给 size 时同样无界写盘。修复：内存侧 4MiB 帽（头判据 + 限流读取双保险）、磁盘侧
 * 2GiB 帽（头判据 + 读循环累计），超帽按该节点失败走既有 fallback。
 *
 * 上限判据抽成纯函数并用**绝对字面量**全表钉死：若测试只写「相对常量」的期望
 * （如 `MAX_PROBE_BYTES + 1` 必拒），把上限突变成长大值测试仍全绿——绝对字面量才测得出。
 */
class ProbeBodyCapGuardTest {

    // ------------------------------------------------------------------ 纯函数全表（绝对字面量）

    @Test
    fun `probeBytesAllowed 全表_未知长度放行_显式超帽即拒`() {
        // 期望值手写；4MiB 这组字面量同时钉死 MAX_PROBE_BYTES 的量级——上限突变 Long.MAX_VALUE 时必红
        assertTrue("未知长度（-1）必须放行，交给限流读取兜", UpdateDownloader.probeBytesAllowed(-1L))
        assertTrue("未知长度（0）必须放行", UpdateDownloader.probeBytesAllowed(0L))
        assertTrue("1 字节放行", UpdateDownloader.probeBytesAllowed(1L))
        assertTrue("GitHub releases/latest 实际几十 KB，必须放行", UpdateDownloader.probeBytesAllowed(64L * 1024))
        assertTrue("恰在上限（4MiB）放行", UpdateDownloader.probeBytesAllowed(4L * 1024 * 1024))
        assertFalse("恰超上限 1 字节必须拒", UpdateDownloader.probeBytesAllowed(4L * 1024 * 1024 + 1))
        assertFalse("64MiB 显式超帽必须拒", UpdateDownloader.probeBytesAllowed(64L * 1024 * 1024))
        assertFalse("Long.MAX_VALUE 必须拒", UpdateDownloader.probeBytesAllowed(Long.MAX_VALUE))
    }

    @Test
    fun `bytesOverLimit 全表_恰到帽不算越_超 1 字节即越`() {
        assertFalse("0 字节不越帽", UpdateDownloader.bytesOverLimit(0L, 4L * 1024 * 1024))
        assertFalse("恰到帽不越帽", UpdateDownloader.bytesOverLimit(4L * 1024 * 1024, 4L * 1024 * 1024))
        assertTrue("超帽 1 字节即越", UpdateDownloader.bytesOverLimit(4L * 1024 * 1024 + 1, 4L * 1024 * 1024))
        // 突变自证锚：MAX_PROBE_BYTES 若被改成 Long.MAX_VALUE，5MiB 的探测体必须仍被判越帽
        assertTrue(
            "探测帽对 5MiB 必须判越（上限突变成长大值时本行必红）",
            UpdateDownloader.bytesOverLimit(5L * 1024 * 1024, UpdateDownloader.MAX_PROBE_BYTES)
        )
        // APK 磁盘帽 2GiB 同样用绝对字面量钉死
        assertFalse("恰到磁盘帽不越", UpdateDownloader.bytesOverLimit(2L * 1024 * 1024 * 1024, UpdateDownloader.MAX_APK_BYTES))
        assertTrue(
            "磁盘帽对 2GiB+1 必须判越（上限突变时本行必红）",
            UpdateDownloader.bytesOverLimit(2L * 1024 * 1024 * 1024 + 1, UpdateDownloader.MAX_APK_BYTES)
        )
    }

    @Test
    fun `尺子自己能红_判据突变成恒放行必报偏离`() {
        // 突变体（bytesOverLimit → 恒 false = 无帽读）：全表的"越帽"行必须能把它揪出来
        val mutant: (Long, Long) -> Boolean = { _, _ -> false }
        val overLimitRows = listOf(
            4L * 1024 * 1024 + 1 to 4L * 1024 * 1024,
            5L * 1024 * 1024 to UpdateDownloader.MAX_PROBE_BYTES,
            2L * 1024 * 1024 * 1024 + 1 to UpdateDownloader.MAX_APK_BYTES
        )
        assertTrue(
            "恒放行突变体必须至少在一行上偏离（否则全表测不出该突变，尺子失效）",
            overLimitRows.any { (total, limit) -> mutant(total, limit) != UpdateDownloader.bytesOverLimit(total, limit) }
        )
    }

    // ------------------------------------------------------------------ 结构守卫（接线不许拆）

    /** 上限接线体检：返回违规清单，空 = 接线完整（真身与突变体同吃） */
    private fun capWiringViolations(src: String): List<String> {
        val bad = ArrayList<String>()
        val fetch = flatten(bodyOf(src, "fetchJson"))
        if (!fetch.contains("probeBytesAllowed(conn.contentLengthLong)")) {
            bad.add("fetchJson 缺 Content-Length 头判据（显式超帽的长体照读 = OOM）")
        }
        if (!fetch.contains("bytesOverLimit(total, MAX_PROBE_BYTES)")) {
            bad.add("fetchJson 缺限流读取的越帽判定（无 Content-Length 时无界读入内存）")
        }
        if (fetch.contains("readText()")) {
            bad.add("fetchJson 复活 readText() 无界读（readText 读到 EOF 才返回，帽形同虚设）")
        }
        val tryNode = flatten(bodyOf(src, "tryNode"))
        if (!tryNode.contains("bytesOverLimit(declared, MAX_APK_BYTES)")) {
            bad.add("tryNode 缺磁盘帽的头判据（显式超限的响应照写盘）")
        }
        if (!tryNode.contains("bytesOverLimit(read, MAX_APK_BYTES)")) {
            bad.add("tryNode 缺读循环的磁盘帽累计判定（无 Content-Length 时无界写盘）")
        }
        return bad
    }

    @Test
    fun `fetchJson与tryNode的响应体上限接线完整`() {
        val violations = capWiringViolations(
            codeOnly(mainSourceText("update/UpdateDownloader.kt"))
        )
        assertEquals(
            "响应体上限接线发现断点：\n${violations.joinToString("\n")}",
            0, violations.size
        )
    }

    @Test
    fun `尺子自己能红_拆帽接线必报红`() {
        val src = codeOnly(mainSourceText("update/UpdateDownloader.kt"))
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", capWiringViolations(src).isEmpty())
        // 突变 1：头判据短路（恒放行）
        val noHeaderGate = src.replace("!probeBytesAllowed(conn.contentLengthLong)", "false")
        assertTrue(
            "拆头判据后尺子必须红",
            capWiringViolations(noHeaderGate).any { it.contains("Content-Length 头判据") }
        )
        // 突变 2：读循环的越帽判定失效（恒不越）
        val noStreamCap = src.replace("bytesOverLimit(total, MAX_PROBE_BYTES)", "false")
        assertTrue(
            "拆限流读取帽后尺子必须红",
            capWiringViolations(noStreamCap).any { it.contains("越帽判定") }
        )
        // 突变 3：readText() 无界读复活（回到 e42d6db 形态；文本级突变，needle 全取代码侧——
        // 遮蔽文本里字符串字面量已被抹成空格，含字面量的长针匹配不上）
        val readTextBack = src.replace("body.toString(", "readText() + body.toString(")
        assertTrue(
            "readText 复活后尺子必须红",
            capWiringViolations(readTextBack).any { it.contains("readText") }
        )
    }
}
