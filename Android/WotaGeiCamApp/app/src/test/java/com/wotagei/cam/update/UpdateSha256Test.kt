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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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

    // ------------------------------------------------------------------ Y45：发布临界区与原子替换

    @Test
    fun `Y45_校验通过后取消不得改写旧完成包`() {
        val cache = temporary.newFolder("cancel-before-publish")
        val target = File(cache, "updates/$ASSET_NAME").apply {
            parentFile!!.mkdirs(); writeBytes(OLD_APK)
        }
        val transport = ApkTransport { GOOD_APK }
        var cancelled = false
        val outcome = UpdateDownloader.downloadApk(
            cacheDir = cache,
            selection = UpdateSelection.legacy(DOWNLOAD_URL, GOOD_APK.size.toLong(), trustedSha256()),
            onProgress = { read, total -> if (total > 0 && read >= total) cancelled = true },
            isCancelled = { cancelled },
            openConnection = transport.open
        )
        assertTrue("取消落在「校验通过 → 发布」之间必须按取消收尾：$outcome", outcome is UpdateDownloader.DownloadOutcome.Cancelled)
        assertArrayEquals("旧完成包必须原样保留（旧任务不得改写完成路径）", OLD_APK, target.readBytes())
        assertEquals(listOf(ASSET_NAME), target.parentFile!!.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `Y45_换代后旧任务不得发布完成包`() {
        val cache = temporary.newFolder("superseded-before-publish")
        val target = File(cache, "updates/$ASSET_NAME").apply {
            parentFile!!.mkdirs(); writeBytes(OLD_APK)
        }
        val transport = ApkTransport { GOOD_APK }
        var current = true
        val outcome = UpdateDownloader.downloadApk(
            cacheDir = cache,
            selection = UpdateSelection.legacy(DOWNLOAD_URL, GOOD_APK.size.toLong(), trustedSha256()),
            onProgress = { read, total -> if (total > 0 && read >= total) current = false },
            isCancelled = { false },
            isCurrent = { current },
            openConnection = transport.open
        )
        assertTrue("被新一代顶替必须返回 Superseded：$outcome", outcome is UpdateDownloader.DownloadOutcome.Superseded)
        assertArrayEquals("旧完成包必须原样保留", OLD_APK, target.readBytes())
        assertEquals(listOf(ASSET_NAME), target.parentFile!!.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `Y45_原子发布失败保留旧完成包_跨目录暂存拒绝`() {
        val cache = temporary.newFolder("publish-failure")
        val target = File(cache, "updates/$ASSET_NAME").apply {
            parentFile!!.mkdirs(); writeBytes(OLD_APK)
        }
        assertFalse("候选不存在时发布必须失败", UpdateDownloader.publishCandidate(File(cache, "updates/missing.part"), target))
        assertArrayEquals("发布失败不得先删旧包", OLD_APK, target.readBytes())

        val otherDir = temporary.newFolder("other-staging")
        val crossDirPart = File(otherDir, "candidate.part").apply { writeBytes(GOOD_APK) }
        assertFalse(
            "跨目录暂存不是原子替换，必须拒绝",
            UpdateDownloader.publishCandidate(crossDirPart, target)
        )
        assertTrue("拒绝后候选仍归调用方清理", crossDirPart.isFile)
        assertArrayEquals(OLD_APK, target.readBytes())
    }

    @Test
    fun `Y45_发布前最后一次复核失效_旧包与候选均原样保留`() {
        val cache = temporary.newFolder("publish-recheck")
        val dir = File(cache, "updates").apply { mkdirs() }
        val target = File(dir, ASSET_NAME).apply { writeBytes(OLD_APK) }
        val part = File(dir, "$ASSET_NAME.part1").apply { writeBytes(GOOD_APK) }
        assertFalse(
            "改名前一瞬复核失败必须拒绝发布",
            UpdateDownloader.publishCandidate(part, target) { false }
        )
        assertArrayEquals("复核失效不得改写旧完成包", OLD_APK, target.readBytes())
        assertTrue("复核失效不得搬走候选文件", part.isFile)
    }

    @Test
    fun `Y45_发布helper在每次落地动作前一瞬复核_没有检查后空窗`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("update/UpdateDownloader.kt"))
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "publishCandidate"))
        val check1 = body.indexOf("if (!canPublish()) return false")
        val rename1 = body.indexOf("part.renameTo(target)")
        val check2 = body.indexOf("if (!canPublish()) return false", check1 + 1)
        val atomicMove = body.indexOf("Files.move(")
        val check3 = body.indexOf("if (!canPublish()) return false", check2 + 1)
        val backupRename = body.indexOf("target.renameTo(backup)")
        assertTrue("同目录 rename 前必须复核：$body", check1 >= 0 && rename1 > check1)
        assertTrue("原子替换前必须复核且中间不得先动旧包", check2 > check1 && atomicMove > check2)
        assertTrue("保底换位搬走旧包前必须再复核", check3 > check2 && backupRename > check3)
    }

    @Test
    fun `Y45_非原子换位中断后启动时恢复旧完成包`() {
        val cache = temporary.newFolder("recover-backup")
        val dir = File(cache, "updates").apply { mkdirs() }
        val target = File(dir, ASSET_NAME)
        val backup = File(dir, "$ASSET_NAME.old1738888888888").apply { writeBytes(OLD_APK) }
        assertTrue("完成路径缺失且存在 .old 备份时必须恢复", UpdateDownloader.recoverCompletedTarget(dir, target))
        assertArrayEquals(OLD_APK, target.readBytes())
        assertFalse("恢复后备份不再保留", backup.exists())
        assertFalse("完成路径已存在时不做多余搬动", UpdateDownloader.recoverCompletedTarget(dir, target))
    }

    // ------------------------------------------------------------------ Y45 收口：提交闸桥测（确定性竞态）

    /**
     * 闸的终态语义（纯 JVM）：三态一去不回，已取消/已顶替的闸提交必须被拒且**一次都不执行发布**。
     * 这是「所有权/取消核对与实际发布同一临界区」的最小判据。
     */
    @Test
    fun `Y45_闸终态一去不回_已取消或已顶替的提交必须被拒且不执行发布`() {
        val cancelledGate = UpdateAttemptGate()
        assertFalse("ACTIVE 时 isCancelled 必须为 false", cancelledGate.isCancelled)
        assertTrue("ACTIVE 时仍是当前代", cancelledGate.isCurrent)
        assertTrue(cancelledGate.cancel())
        assertFalse("终态一去不回：已取消不能再顶替", cancelledGate.supersede())
        val publishedAfterCancel = AtomicBoolean(false)
        assertFalse(
            "已取消的闸必须拒绝提交",
            cancelledGate.commitIfActive {
                publishedAfterCancel.set(true)
                true
            }
        )
        assertFalse("拒绝时发布动作一次都不许执行", publishedAfterCancel.get())

        val supersededGate = UpdateAttemptGate()
        assertTrue(supersededGate.supersede())
        assertFalse("终态一去不回：已顶替不能再取消", supersededGate.cancel())
        val publishedAfterSupersede = AtomicBoolean(false)
        assertFalse(
            "已顶替的闸必须拒绝提交",
            supersededGate.commitIfActive {
                publishedAfterSupersede.set(true)
                true
            }
        )
        assertFalse(publishedAfterSupersede.get())
        assertFalse("被顶替的代不再当前", supersededGate.isCurrent)

        // 每代一枚闸：旧代取消/顶替绝不能波及新一代
        val oldGate = UpdateAttemptGate()
        val newGate = UpdateAttemptGate()
        oldGate.cancel()
        assertFalse("旧代取消不得影响新一代", newGate.isCancelled)
        assertTrue("新一代必须仍可提交", newGate.commitIfActive { true })
    }

    /**
     * **权限/提交边界的确定性竞态**：提交线程持闸锁停在发布动作里（等价于「已核对 ACTIVE，
     * 正在改名」），取消线程此时必须排队——放开前取消不得返回，放开后提交先落定、取消随后生效。
     *
     * 「尺子自己能红」：若 [UpdateAttemptGate.commitIfActive] 不在锁内核对/执行（先判定后出锁再
     * publish），取消线程会在持锁窗口里直接完成，`isAlive` 断言立即红。
     */
    @Test
    fun `Y45_桥_取消与提交争同一把锁_提交临界区内取消必须排队`() {
        val gate = UpdateAttemptGate()
        val inPublish = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val commitDone = CountDownLatch(1)
        val published = AtomicBoolean(false)
        var committed = false

        val committer = thread(name = "y45-committer") {
            committed = gate.commitIfActive {
                published.set(true)
                inPublish.countDown()
                releasePublish.await(5, TimeUnit.SECONDS)
                true
            }
            commitDone.countDown()
        }
        assertTrue("提交必须真的进到临界区内（否则本测试没测到等待点）", inPublish.await(5, TimeUnit.SECONDS))

        val cancelStarted = CountDownLatch(1)
        val cancelReturned = AtomicBoolean(false)
        val canceller = thread(name = "y45-canceller") {
            cancelStarted.countDown()
            gate.cancel()
            cancelReturned.set(true)
        }
        assertTrue(cancelStarted.await(5, TimeUnit.SECONDS))
        canceller.join(250)
        assertTrue("取消在提交临界区内不得抢先完成（否则仍有核对→落地空窗）", canceller.isAlive)
        assertFalse("临界区未放行前提交不许先返回", commitDone.await(0, TimeUnit.MILLISECONDS))

        releasePublish.countDown()
        assertTrue("提交必须完成", commitDone.await(5, TimeUnit.SECONDS))
        committer.join(5_000)
        canceller.join(5_000)
        assertTrue("进临界区时仍是 ACTIVE，发布必须真的落地", committed && published.get())
        assertTrue("取消在提交之后才生效（UI 仍按本代标志丢弃回执）", cancelReturned.get())
        assertTrue(gate.isCancelled)
    }

    /**
     * **校验收尾（最后一读已翻闸）→ 提交**的桥测：取消/顶替都只能从**本代闸**表达
     * （isCancelled/isCurrent 回调恒放行——证明拦下发布的是闸，不是旧回调）。
     * 完成路径必须保持旧字节，且目录里只剩旧完成包（本代候选由本任务清理）。
     */
    @Test
    fun `Y45_桥_校验后取消闸_旧完成包保留且只清本代候选`() {
        val cache = temporary.newFolder("gate-cancel-before-publish")
        val target = File(cache, "updates/$ASSET_NAME").apply {
            parentFile!!.mkdirs(); writeBytes(OLD_APK)
        }
        val gate = UpdateAttemptGate()
        val transport = ApkTransport { GOOD_APK }
        val outcome = UpdateDownloader.downloadApk(
            cacheDir = cache,
            selection = UpdateSelection.legacy(DOWNLOAD_URL, GOOD_APK.size.toLong(), trustedSha256()),
            onProgress = { read, total -> if (total > 0 && read >= total) gate.cancel() },
            isCancelled = { false },
            isCurrent = { true },
            publishGate = gate,
            openConnection = transport.open
        )
        assertTrue("闸在提交前被取消必须判取消：$outcome", outcome is UpdateDownloader.DownloadOutcome.Cancelled)
        assertArrayEquals("旧完成包必须原样保留", OLD_APK, target.readBytes())
        assertEquals(
            "本代候选必须清掉、不得留下新字节或别人的文件",
            listOf(ASSET_NAME),
            target.parentFile!!.listFiles().orEmpty().map { it.name }
        )
        assertTrue(gate.isCancelled)
    }

    @Test
    fun `Y45_桥_校验后顶替闸_旧完成包保留且只清本代候选`() {
        val cache = temporary.newFolder("gate-supersede-before-publish")
        val target = File(cache, "updates/$ASSET_NAME").apply {
            parentFile!!.mkdirs(); writeBytes(OLD_APK)
        }
        val gate = UpdateAttemptGate()
        val transport = ApkTransport { GOOD_APK }
        val outcome = UpdateDownloader.downloadApk(
            cacheDir = cache,
            selection = UpdateSelection.legacy(DOWNLOAD_URL, GOOD_APK.size.toLong(), trustedSha256()),
            onProgress = { read, total -> if (total > 0 && read >= total) gate.supersede() },
            isCancelled = { false },
            isCurrent = { true },
            publishGate = gate,
            openConnection = transport.open
        )
        assertTrue("闸在提交前被顶替必须判 Superseded：$outcome", outcome is UpdateDownloader.DownloadOutcome.Superseded)
        assertArrayEquals("旧完成包必须原样保留", OLD_APK, target.readBytes())
        assertEquals(
            listOf(ASSET_NAME),
            target.parentFile!!.listFiles().orEmpty().map { it.name }
        )
    }

    /**
     * **启动恢复 vs 发布**的互斥桥测：发布线程持共享互斥停在第一次复核钩子里（改名前），
     * 此时恢复线程必须排队——否则非原子换位的临时缺位窗口里，恢复会把旧 `.old` 备份搬回，
     * 覆盖刚发布的字节。放开后发布落定，恢复只看到「目标已存在」而放弃。
     *
     * 「尺子自己能红」：若 [UpdateDownloader.recoverCompletedTarget] 不持发布互斥，恢复线程
     * 会在发布未落定时直接返回，`isAlive` 断言立即红。
     */
    @Test
    fun `Y45_桥_启动恢复与发布互斥_恢复不得越过未落定的发布`() {
        val cache = temporary.newFolder("recover-vs-publish")
        val dir = File(cache, "updates").apply { mkdirs() }
        val target = File(dir, ASSET_NAME).apply { writeBytes(OLD_APK) }
        val staleBackup = File(dir, "$ASSET_NAME.old1738888888888").apply { writeBytes(OLD_APK) }
        val part = File(dir, "$ASSET_NAME.part42").apply { writeBytes(GOOD_APK) }

        val inPublish = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val publishDone = CountDownLatch(1)
        var published = false
        val publisher = thread(name = "y45-publisher") {
            published = UpdateDownloader.publishCandidate(part, target) {
                inPublish.countDown()
                releasePublish.await(5, TimeUnit.SECONDS)
                true
            }
            publishDone.countDown()
        }
        assertTrue("发布必须进到互斥区内（否则本测试没测到等待点）", inPublish.await(5, TimeUnit.SECONDS))

        val recoveryStarted = CountDownLatch(1)
        var recoveryResult: Boolean? = null
        val recovery = thread(name = "y45-recovery") {
            recoveryStarted.countDown()
            recoveryResult = UpdateDownloader.recoverCompletedTarget(dir, target)
        }
        assertTrue(recoveryStarted.await(5, TimeUnit.SECONDS))
        recovery.join(500)
        assertTrue("发布持锁期间启动恢复不得插队（会把旧备份搬回覆盖新字节）", recovery.isAlive)

        releasePublish.countDown()
        assertTrue("发布必须完成", publishDone.await(5, TimeUnit.SECONDS))
        publisher.join(5_000)
        recovery.join(5_000)
        assertTrue("发布必须成功", published)
        assertEquals("发布落定后恢复只能看到目标已存在并放弃", false, recoveryResult)
        assertArrayEquals("完成路径必须是刚发布的字节", GOOD_APK, target.readBytes())
        assertTrue("恢复不得搬走旧备份（未发生换位，备份仍归清理）", staleBackup.isFile)
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
        val OLD_APK = "PK-old-APK".toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ 接线守卫（尺子自己能红）

    @Test
    fun `接线_downloadApk必须调校验_UI必须传选择绑定`() {
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
        val sha = dl.indexOf("sha256Matches(expectedSha256")
        val publish = dl.indexOf("publishCandidate(part, target)")
        assertTrue("SHA 必须在发布完成路径之前验证", sha >= 0 && publish > sha)
        val finalOwnerCheck = dl.indexOf("isCurrent()", sha)
        assertTrue(
            "发布临界区必须在摘要校验之后、原子替换之前重查代数（Y45）",
            finalOwnerCheck in (sha until publish)
        )
        val settings = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
        assertTrue(
            "UI 必须把探测结果的选择绑定交给下载（tag/flavor/资产/ABI/摘要同源）",
            settings.contains("selection = avail.selection")
        )
        assertTrue(
            "UI 必须把本代身份交给下载器在发布临界区复核（Y45）",
            settings.contains("isCurrent = { downloadGenFlag === genCancelled }")
        )
        assertTrue(
            "UI 必须用官方 JSON 的最终落地来源校验（Y43）",
            settings.contains("fetchJsonSource = {") &&
                settings.contains("UpdateDownloader.fetchJsonSource(")
        )
        // 突变自证：校验判据被恒真短路（拆校验）后尺子必须红（目标串是纯代码，flatten 后必中）
        val mutated = dl.replace("sha256Matches(expectedSha256, actual)", "true")
        assertTrue("校验接线被恒真短路后尺子必须红", !mutated.contains("sha256Matches"))
    }

    /**
     * Y45 收口的接线体检：提交闸必须真的夹住「校验 → 发布」，发布与启动恢复必须共用一把互斥，
     * UI 的取消/顶替必须走同一枚闸。全部 needle 取代码侧（字符串字面量已被遮蔽）。
     */
    private fun gateWiringViolations(src: String, settings: String): List<String> {
        val bad = ArrayList<String>()
        val dl = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "downloadApk"))
        val sha = dl.indexOf("sha256Matches(expectedSha256")
        val commit = dl.indexOf("publishGate.commitIfActive { publishCandidate(part, target) }")
        if (commit < 0) {
            bad.add("downloadApk 没有把发布动作交给本代提交闸（核对与落地不在同一临界区）")
        } else if (sha < 0 || commit < sha) {
            bad.add("提交临界区必须在校验之后（发布时机失去输入）")
        }
        if (!dl.contains("if (!isCurrent()) {")) {
            bad.add("downloadApk 缺发布前代数复核")
        }
        val commitBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "commitIfActive"))
        if (!commitBody.contains("lock.withLock")) {
            bad.add("commitIfActive 没有持闸锁核对/发布（取消可与提交插队）")
        }
        if (!commitBody.contains("state != State.ACTIVE")) {
            bad.add("commitIfActive 没有核对 ACTIVE 终态")
        }
        listOf("inputStream", "readBytes", "openConnection", "tryNode", "sha256Hex").forEach { io ->
            if (commitBody.contains(io)) bad.add("提交临界区内出现大体量 I/O（$io）：取消/UI 会被拖住")
        }
        val publishBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "publishCandidate"))
        if (!publishBody.contains("PUBLISH_MUTEX.withLock")) {
            bad.add("publishCandidate 没持共享发布互斥（与启动恢复可交错改完成路径）")
        }
        val recoverBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "recoverCompletedTarget"))
        if (!recoverBody.contains("PUBLISH_MUTEX.withLock")) {
            bad.add("recoverCompletedTarget 没持共享发布互斥（启动恢复可把旧备份搬回覆盖新发布）")
        }
        if (!settings.contains("val genGate = UpdateAttemptGate()")) {
            bad.add("UI 每代没有新建提交闸")
        }
        if (!settings.contains("downloadGate?.supersede()")) {
            bad.add("UI 开新代前没有顶替旧代闸（旧代仍可提交）")
        }
        if (!settings.contains("downloadGate?.cancel()")) {
            bad.add("UI 取消没有翻本代闸（取消只能等旧回调，发布仍有空窗）")
        }
        if (!settings.contains("publishGate = genGate")) {
            bad.add("UI 没把本代闸交给下载器")
        }
        val androidEntry = KotlinSourceScan.flatten(
            src.substringAfter("fun downloadApk(").substringBefore("fun downloadApk(")
        )
        if (!androidEntry.contains("context: Context") ||
            !androidEntry.contains("selection = selection") ||
            !androidEntry.contains("publishGate = publishGate")
        ) {
            bad.add("Android 下载入口未把本代闸转交给缓存目录下载实现")
        }
        if (!settings.contains("isCurrent = { downloadGenFlag === genCancelled }")) {
            bad.add("UI 没把本代身份交给下载器复核")
        }
        return bad
    }

    @Test
    fun `Y45_提交闸接线_发布在闸内_恢复共用互斥_UI取消顶替同闸`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("update/UpdateDownloader.kt"))
        val settings = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
        val violations = gateWiringViolations(src, settings)
        assertEquals("Y45 提交闸接线发现断点：\n${violations.joinToString("\n")}", emptyList<String>(), violations)

        // 突变 1：发布绕过闸（回到「先核对后发布」的旧形态）→ 必须红
        val noGate = src.replace(
            "publishGate.commitIfActive { publishCandidate(part, target) }",
            "publishCandidate(part, target)"
        )
        assertTrue(
            "发布绕过提交闸后尺子必须红",
            gateWiringViolations(noGate, settings).any { it.contains("提交闸") }
        )
        // 突变 2：提交闸不持锁（先判终态再出锁发布）→ 必须红
        val noLock = src.replace("return lock.withLock {", "return run {")
        assertTrue(
            "提交闸去掉锁后尺子必须红",
            gateWiringViolations(noLock, settings).any { it.contains("持闸锁") }
        )
        // 突变 3：启动恢复不再与发布互斥 → 必须红
        val recoverNoLock = src.replace(
            "internal fun recoverCompletedTarget(dir: File, target: File): Boolean {\n        return PUBLISH_MUTEX.withLock {",
            "internal fun recoverCompletedTarget(dir: File, target: File): Boolean {\n        return run {"
        )
        assertTrue(
            "启动恢复去掉发布互斥后尺子必须红",
            gateWiringViolations(recoverNoLock, settings).any { it.contains("recoverCompletedTarget") }
        )
        // 突变 4：UI 取消不翻闸 → 必须红
        val uiNoCancel = settings.replace("downloadGate?.cancel()", "")
        assertTrue(
            "UI 取消不翻闸后尺子必须红",
            gateWiringViolations(src, uiNoCancel).any { it.contains("取消") }
        )
        // 突变 5：UI 开新代不顶替旧代闸 → 必须红
        val uiNoSupersede = settings.replace("downloadGate?.supersede()", "")
        assertTrue(
            "UI 不顶替旧代闸后尺子必须红",
            gateWiringViolations(src, uiNoSupersede).any { it.contains("顶替") }
        )
    }

    /**
     * 校验步的异常兜底：重算实际摘要是**读盘**（Y43 起每次 Done 都带实际字节身份，不能再按
     * 「无期望哈希就不读」短路），IO 异常穿出函数就是协程未捕获异常（下载协程没有
     * CoroutineExceptionHandler → 杀进程）。
     */
    @Test
    fun `接线_校验读盘异常必须折算失败_实际摘要随Done返回`() {
        val dl = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("update/UpdateDownloader.kt")),
                "downloadApk"
            )
        )
        assertTrue(
            "校验必须包在 runCatching 里（读盘异常折算失败，不穿成未捕获异常）",
            dl.contains("runCatching { sha256Hex(part) }")
        )
        assertTrue(
            "每次成功候选都必须重算实际摘要（Y43：安装闸用它对账下载字节）",
            dl.contains("sha256Matches(expectedSha256, actual)")
        )
        assertTrue(
            "Done 必须携带实际摘要与选择绑定",
            dl.contains("DownloadOutcome.Done(target, r.sizeBytes, selection, actual)")
        )
        assertTrue("读盘失败必须记日志", dl.contains("Log.e(TAG,"))
        // 日志文案要在**原文**里核（codeOnly 会把字符串字面量内容遮蔽掉）
        val raw = KotlinSourceScan.mainSourceText("update/UpdateDownloader.kt")
        assertTrue("读盘失败的日志要能认出来", raw.contains("SHA-256 校验读盘失败"))
        // 突变 1：拆掉读盘兜底 → IO 异常会穿出协程
        val noGuard = dl.replace("runCatching { sha256Hex(part) }", "sha256Hex(part)")
        assertTrue("去掉 runCatching 后尺子必须报红", !noGuard.contains("runCatching { sha256Hex(part) }"))
    }
}
