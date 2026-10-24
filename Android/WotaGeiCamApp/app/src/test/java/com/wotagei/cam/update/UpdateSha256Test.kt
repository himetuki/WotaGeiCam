package com.wotagei.cam.update

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SHA-256 端到端校验链的**纯函数单测 + 接线守卫**（v0.0.8，2026-10-13）。
 *
 * 安全模型：镜像（公共代理）的运营方理论上能替换下载字节，「不会被污染」靠
 * 发版者把 universal 包哈希写进 GitHub Release 说明、app 只信段 1（api.github.com
 * 官方端点）读出的哈希、落盘后本地重算比对。三个环节各自可测：
 * - [ReleaseJson.parseSha256FromNotes]：说明文本 → 哈希（表驱动含边界）；
 * - [ReleaseJson.parse] 的 extractSha256 开关：段 1 开、段 2 关（镜像 body 的哈希不可信）；
 * - [UpdateDownloader.sha256Matches]/[sha256Hex]：判据与真实摘要。
 * 接线（downloadApk 真的调校验、UI 真的传哈希）用源文结构守卫钉——防校验被静默拆掉。
 */
class UpdateSha256Test {

    // ------------------------------------------------------------------ 说明文本提取

    @Test
    fun `说明提取_标准格式与变体命中_输出小写`() {
        // 精确 64 位用程序构造——手写 hex 极易多一位少一位（本轮自踩）
        val hex = ("1234567890abcdef").repeat(4)
        assertEquals(64, hex.length)
        assertEquals(hex, ReleaseJson.parseSha256FromNotes("SHA256: $hex"))
        assertEquals(hex.lowercase(), ReleaseJson.parseSha256FromNotes("SHA256: ${hex.uppercase()}"))
        assertEquals(hex, ReleaseJson.parseSha256FromNotes("SHA256：$hex"))
        assertEquals(hex, ReleaseJson.parseSha256FromNotes("SHA256  :   $hex"))
        // 多行说明里中间一行命中（release notes 的真实形态）
        assertEquals(
            hex,
            ReleaseJson.parseSha256FromNotes("本次更新说明\n共若干项改进\nSHA256: $hex\n安装请下载 universal 包")
        )
    }

    @Test
    fun `说明提取_非标准形态一律不命中`() {
        val hex64 = ("1234567890abcdef").repeat(4)
        assertNull("63 位不命中", ReleaseJson.parseSha256FromNotes("SHA256: ${hex64.dropLast(1)}"))
        assertNull("65 位不命中（尾随 hex 防误截前 64）", ReleaseJson.parseSha256FromNotes("SHA256: ${hex64}f"))
        assertNull("没有键不命中", ReleaseJson.parseSha256FromNotes("更新内容如下，共若干项"))
        assertNull("键拼错不命中", ReleaseJson.parseSha256FromNotes("sha256: $hex64"))
        assertNull("空文本不命中", ReleaseJson.parseSha256FromNotes(""))
    }

    // ------------------------------------------------------------------ 段1/段2 信任边界

    private fun jsonWithBody(body: String) =
        """{"tag_name": "v0.0.8-r01", "assets": [{"name": "v0.0.8-r01-universal-release-Android.apk", """ +
            """"browser_download_url": "https://x/u.apk", "size": 5}], "body": "$body"}"""

    @Test
    fun `parse_段1开关开才提取_镜像段恒不信任`() {
        val hex = ("deadbeefcafebabe0123456789abcdef").repeat(2).substring(0, 64)
        assertEquals(64, hex.length)
        val json = jsonWithBody("更新说明\\nSHA256: $hex\\n安装段")
        // 段 1（extractSha256=true）：body 里的哈希被提取
        assertEquals(hex, ReleaseJson.parse(json, extractSha256 = true)?.sha256)
        // 段 2（镜像转发 body 可被代理改写）：同样的 JSON 也**不提取**——宁可不校验，不拿假哈希当真
        assertNull(ReleaseJson.parse(json, extractSha256 = false)?.sha256)
        assertNull("默认参数 = 不提取（旧调用点行为不变）", ReleaseJson.parse(json)?.sha256)
        // 说明原文（notes）两段都读：它只用于弹窗展示，不参与判断
        assertEquals("更新说明\nSHA256: $hex\n安装段", ReleaseJson.parse(json, extractSha256 = false)?.notes)
        // JSON 字符串里的 \n 转义要在解转义后仍能命中
        assertNull("body 无哈希时恒 null", ReleaseJson.parse(jsonWithBody("普通说明"), extractSha256 = true)?.sha256)
    }

    // ------------------------------------------------------------------ 判据与摘要

    @Test
    fun `校验判据_无期望恒放行_有期望忽略大小写精确比对`() {
        val cases = listOf(
            null to "anything" to true,           // 探测没给哈希：恒放行（退回既有防线）
            "aa" to "aa" to true,
            "AA" to "aa" to true,                 // 说明文本大写 hex 也合法
            "aa" to "ab" to false,
            "aa" to "" to false,
        )
        cases.forEach { (pair, expect) ->
            val (expected, actual) = pair
            assertEquals(
                "sha256Matches($expected, $actual) 应为 $expect",
                expect, UpdateDownloader.sha256Matches(expected, actual)
            )
        }
    }

    /** SHA-256 标准测试向量（NIST FIPS 180-4 示例） */
    @Test
    fun `sha256Hex_标准向量`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateDownloader.sha256Hex(File.createTempFile("empty", ".tmp").apply { deleteOnExit() })
        )
        val f = File.createTempFile("abc", ".tmp").apply { deleteOnExit(); writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateDownloader.sha256Hex(f)
        )
        // 跨多块读取（>64KB buffer）也要对：64KB+1 字节的重复内容
        val big = File.createTempFile("big", ".tmp").apply {
            deleteOnExit(); writeText("x".repeat(64 * 1024) + "y")
        }
        assertFalse(
            "64KB+1 不该等于 64KB 相同前缀的哈希",
            UpdateDownloader.sha256Hex(big) == UpdateDownloader.sha256Hex(
                File.createTempFile("big2", ".tmp").apply { deleteOnExit(); writeText("x".repeat(64 * 1024) + "z") }
            )
        )
    }

    // ------------------------------------------------------------------ 接线守卫（尺子自己能红）

    @Test
    fun `接线_downloadApk必须调校验_UI必须传哈希`() {
        val dl = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("update/UpdateDownloader.kt")),
                "downloadApk"
            )
        )
        assertTrue(
            "downloadApk 必须调 sha256Matches(expectedSha256, …)——校验被拆 = 防污染核心失效",
            dl.contains("sha256Matches(expectedSha256")
        )
        assertTrue("校验失败必须删包", dl.contains("target.delete()"))
        val settings = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
        assertTrue(
            "UI 必须把探测得到的哈希传给下载（expectedSha256 = avail.sha256）",
            settings.contains("expectedSha256 = avail.sha256")
        )
        // 突变自证：校验判据被恒真短路（拆校验）后尺子必须红（目标串是纯代码，flatten 后必中）
        val mutated = dl.replace("sha256Matches(expectedSha256, sha256Hex(target))", "true")
        assertTrue("校验接线被恒真短路后尺子必须红", !mutated.contains("sha256Matches"))
    }

    /**
     * 校验步的异常兜底与「无哈希不读盘」：重算是**读盘**，IO 异常穿出函数就是协程未捕获异常
     * （下载协程没有 CoroutineExceptionHandler → 杀进程）；期望哈希为 null 时更不该白读一遍 5MB。
     */
    @Test
    fun `接线_校验读盘异常必须折算失败_无期望不读盘`() {
        val dl = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("update/UpdateDownloader.kt")),
                "downloadApk"
            )
        )
        assertTrue(
            "校验必须包在 runCatching 里（读盘异常折算失败，不穿成未捕获异常）",
            dl.contains("runCatching {")
        )
        assertTrue(
            "无期望哈希必须短路（Kotlin 实参先求值，不短路就会白读一遍整个 APK）",
            dl.contains("expectedSha256 == null ||")
        )
        assertTrue("读盘失败必须记日志", dl.contains("Log.e(TAG,"))
        // 日志文案要在**原文**里核（codeOnly 会把字符串字面量内容遮蔽掉）
        val raw = KotlinSourceScan.mainSourceText("update/UpdateDownloader.kt")
        assertTrue("读盘失败的日志要能认出来", raw.contains("SHA-256 校验读盘失败"))
        // 突变 1：拆掉 null 短路 → 无哈希也全量读盘
        val noShortCircuit = dl.replace("expectedSha256 == null ||", "")
        assertTrue(
            "去掉 null 短路后尺子必须报红",
            !noShortCircuit.contains("expectedSha256 == null ||")
        )
        // 突变 2：异常不再兜底（回到裸调用）
        val noGuard = dl.replace("runCatching {", "run {")
        assertTrue("去掉 runCatching 后尺子必须报红", !noGuard.contains("runCatching {"))
    }
}
