package com.wotagei.cam.update

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

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

    @get:Rule
    val temporary = TemporaryFolder(File("build/tmp/update-download-test").apply { mkdirs() })

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

    /**
     * 三行语义（2026-11-09 用户裁决：旧版兼容包=lite）：
     * 通用行服务旧版（无 flavor），必须=lite；full 只认 `SHA256-full:`（绝不回退通用行，
     * 否则 full 必错配）；lite 只认 `SHA256-lite:`。
     */
    @Test
    fun `说明提取_三行语义_通用行归旧版_full与lite各认专用行`() {
        val compat = "1111111111111111111111111111111111111111111111111111111111111111"
        val full = "2222222222222222222222222222222222222222222222222222222222222222"
        val lite = "3333333333333333333333333333333333333333333333333333333333333333"
        val notes = "AI 站位提示\n## 校验和\n" +
            "- 兼容旧版（无 flavor）lite 包 SHA256：\n- full 包 SHA256：\n- lite 包 SHA256：\n" +
            "SHA256: $compat\nSHA256-full: $full\nSHA256-lite: $lite"
        // 旧版（无 flavor / null）= 通用行（它下载的旧命名包正是 lite 内容）
        assertEquals(compat, ReleaseJson.parseSha256FromNotes(notes))
        assertEquals(full, ReleaseJson.parseSha256FromNotes(notes, "full"))
        assertEquals(lite, ReleaseJson.parseSha256FromNotes(notes, "lite"))
        // full 缺专用行时必须判无哈希：绝不回退通用行（那行是 lite 哈希 → 必然错配）
        val noFullLine = notes.lines().filterNot { it.startsWith("SHA256-full:") }.joinToString("\n")
        assertNull("full 缺专用行不许回退通用行", ReleaseJson.parseSha256FromNotes(noFullLine, "full"))
        val noLiteLine = notes.lines().filterNot { it.startsWith("SHA256-lite:") }.joinToString("\n")
        assertNull("lite 缺专用行必须判无哈希", ReleaseJson.parseSha256FromNotes(noLiteLine, "lite"))
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
            UpdateDownloader.sha256Hex(temporary.newFile("empty.tmp"))
        )
        val f = temporary.newFile("abc.tmp").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateDownloader.sha256Hex(f)
        )
        // 跨多块读取（>64KB buffer）也要对：64KB+1 字节的重复内容
        val big = temporary.newFile("big.tmp").apply {
            writeText("x".repeat(64 * 1024) + "y")
        }
        assertFalse(
            "64KB+1 不该等于 64KB 相同前缀的哈希",
            UpdateDownloader.sha256Hex(big) == UpdateDownloader.sha256Hex(
                temporary.newFile("big2.tmp").apply { writeText("x".repeat(64 * 1024) + "z") }
            )
        )
    }

    // ------------------------------------------------------------------ D04：真实下载编排（只替换传输，不复制循环）

    @Test
    fun `D04_首节点同长度PK坏SHA后仍尝试健康后备且清掉污染`() {
        val cache = temporary.newFolder("bad-then-good")
        val candidates = UpdateDownloader.downloadCandidates(DOWNLOAD_URL)
        val transport = ApkTransport { url ->
            when (url) {
                candidates[0] -> BAD_APK
                candidates[1] -> GOOD_APK
                else -> null
            }
        }
        val target = File(cache, "updates/$ASSET_NAME")
        transport.beforeDownload = { url ->
            if (url == candidates[1]) {
                assertFalse("开始后备节点前必须删除首节点污染包", target.exists())
                assertTrue("首节点的 part 也不能遗留", target.parentFile!!.listFiles().orEmpty().isEmpty())
            }
        }
        var speedCallbacks = 0
        val outcome = UpdateDownloader.downloadApk(
            cacheDir = cache,
            directUrl = DOWNLOAD_URL,
            expectedSize = GOOD_APK.size.toLong(),
            onProgress = { _, _ -> },
            isCancelled = { false },
            expectedSha256 = trustedSha256(),
            onSpeedTested = { speedCallbacks++ },
            openConnection = transport.open
        )
        assertEquals("坏响应与好响应必须同长度且都能过 PK 防线，确保真正命中 SHA 失败", GOOD_APK.size, BAD_APK.size)
        assertTrue("首节点坏 SHA 只能拒该渠道，不能终止真实下载编排：$outcome", outcome is UpdateDownloader.DownloadOutcome.Done)
        val done = outcome as UpdateDownloader.DownloadOutcome.Done
        assertEquals(candidates.take(2), transport.attempted)
        assertEquals(candidates.toSet(), transport.probed.toSet())
        assertEquals(1, speedCallbacks)
        assertEquals(target.canonicalFile, done.file.canonicalFile)
        assertEquals(GOOD_APK.size.toLong(), done.sizeBytes)
        assertArrayEquals("只有通过可信 SHA 的后备字节才可公开", GOOD_APK, done.file.readBytes())
        assertEquals(listOf(ASSET_NAME), target.parentFile!!.listFiles().orEmpty().map { it.name })
        assertTrue("每个真实响应必须断开连接", transport.connections.all { it.disconnected })
    }

    @Test
    fun `D04_所有节点坏SHA必须尝试全链后失败且不残留APK或part`() {
        val cache = temporary.newFolder("all-bad")
        val transport = ApkTransport { BAD_APK }
        val outcome = download(cache, transport, trustedSha256())
        assertTrue("不能绕过 SHA 将污染包当成 Done", outcome is UpdateDownloader.DownloadOutcome.Failed)
        assertEquals("必须在所有候选均校验失败后才报告全链失败", UpdateDownloader.downloadCandidates(DOWNLOAD_URL), transport.attempted)
        val remaining = File(cache, "updates").listFiles().orEmpty()
        assertTrue("全坏失败不得遗留 APK 或 part：${remaining.map { it.name to it.length() }}", remaining.isEmpty())
        assertTrue(transport.connections.all { it.disconnected })
    }

    @Test
    fun `D04_重下时全链坏SHA不能删除已有可信完成包`() {
        val cache = temporary.newFolder("keep-trusted")
        val target = File(cache, "updates/$ASSET_NAME").apply { parentFile!!.mkdirs(); writeBytes(GOOD_APK) }
        val transport = ApkTransport { BAD_APK }
        val outcome = download(cache, transport, trustedSha256())
        assertTrue(outcome is UpdateDownloader.DownloadOutcome.Failed)
        assertEquals(UpdateDownloader.downloadCandidates(DOWNLOAD_URL), transport.attempted)
        assertTrue("坏镜像不能先发布再删掉原可信完成路径", target.isFile)
        assertArrayEquals(GOOD_APK, target.readBytes())
        assertEquals(listOf(ASSET_NAME), target.parentFile!!.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `D04_首节点正确SHA直接完成不碰后备`() {
        val cache = temporary.newFolder("first-good")
        val transport = ApkTransport { GOOD_APK }
        val outcome = download(cache, transport, trustedSha256().uppercase())
        assertTrue(outcome is UpdateDownloader.DownloadOutcome.Done)
        assertEquals(listOf(DOWNLOAD_URL), transport.attempted)
        assertArrayEquals(GOOD_APK, (outcome as UpdateDownloader.DownloadOutcome.Done).file.readBytes())
        assertEquals(listOf(ASSET_NAME), File(cache, "updates").listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `D04_缺可信SHA保持既定PK和长度防线而非新增强制校验`() {
        val cache = temporary.newFolder("without-hash")
        val transport = ApkTransport { BAD_APK }
        val outcome = download(cache, transport, null)
        assertTrue("镜像探测和历史说明无可信哈希时仍保留原有行为", outcome is UpdateDownloader.DownloadOutcome.Done)
        assertEquals(listOf(DOWNLOAD_URL), transport.attempted)
        assertArrayEquals(BAD_APK, (outcome as UpdateDownloader.DownloadOutcome.Done).file.readBytes())
    }

    @Test
    fun `D04_下载中取消清理part且不尝试后备`() {
        val cache = temporary.newFolder("cancelled")
        val transport = ApkTransport { GOOD_APK }
        var cancelled = false
        val outcome = UpdateDownloader.downloadApk(
            cacheDir = cache,
            directUrl = DOWNLOAD_URL,
            expectedSize = GOOD_APK.size.toLong(),
            onProgress = { _, _ -> cancelled = true },
            isCancelled = { cancelled },
            expectedSha256 = trustedSha256(),
            openConnection = transport.open
        )
        assertTrue(outcome is UpdateDownloader.DownloadOutcome.Cancelled)
        assertEquals(listOf(DOWNLOAD_URL), transport.attempted)
        assertTrue(File(cache, "updates").listFiles().orEmpty().isEmpty())
        assertTrue(transport.connections.all { it.disconnected })
    }

    private fun download(cache: File, transport: ApkTransport, sha256: String?) = UpdateDownloader.downloadApk(
        cacheDir = cache,
        directUrl = DOWNLOAD_URL,
        expectedSize = GOOD_APK.size.toLong(),
        onProgress = { _, _ -> },
        isCancelled = { false },
        expectedSha256 = sha256,
        openConnection = transport.open
    )

    /** 可信期望取自测试的固定好字节，不调用被测摘要 helper 来决定真假。 */
    private fun trustedSha256(): String = MessageDigest.getInstance("SHA-256").digest(GOOD_APK)
        .joinToString("") { "%02x".format(it) }

    /** 测速连接均返回 null → 真实测速/排序自然给全 0，稳定保留生产候选顺序。无网络、无全局 hook。 */
    private class ApkTransport(private val payload: (String) -> ByteArray?) {
        val probed = ConcurrentLinkedQueue<String>()
        val attempted = mutableListOf<String>()
        val connections = mutableListOf<ApkConnection>()
        var beforeDownload: (String) -> Unit = {}
        val open: UpdateConnectionFactory = { url, _, _, deadline ->
            if (deadline != null) {
                probed.add(url)
                null
            } else {
                attempted.add(url)
                beforeDownload(url)
                payload(url)?.let { bytes -> ApkConnection(url, bytes).also { connections.add(it) } }
            }
        }
    }

    /** 仅模拟 HTTP 响应边界；PK/长度/写盘/校验/节点循环全部仍执行生产代码。 */
    private class ApkConnection(url: String, private val bytes: ByteArray) : HttpURLConnection(URL(url)) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = HTTP_OK
        override fun getContentLengthLong(): Long = bytes.size.toLong()
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }

    private companion object {
        const val ASSET_NAME = "WotaGeiCam-v0.0.12-r08-full-universal-release-Android.apk"
        val DOWNLOAD_URL = UpdateConfig.directDownloadUrl("v0.0.12-r08", ASSET_NAME)
        val GOOD_APK = "PK-good-APK".toByteArray(Charsets.UTF_8)
        val BAD_APK = "PK-evil-APK".toByteArray(Charsets.UTF_8)
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
        assertTrue("校验失败必须删除未发布候选", dl.contains("part.delete()"))
        assertTrue("SHA 必须在发布完成路径之前验证", dl.indexOf("sha256Matches(expectedSha256") < dl.indexOf("part.renameTo(target)"))
        val settings = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
        assertTrue(
            "UI 必须把探测得到的哈希传给下载（expectedSha256 = avail.sha256）",
            settings.contains("expectedSha256 = avail.sha256")
        )
        // 突变自证：校验判据被恒真短路（拆校验）后尺子必须红（目标串是纯代码，flatten 后必中）
        val mutated = dl.replace("sha256Matches(expectedSha256, sha256Hex(part))", "true")
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
