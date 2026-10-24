package com.wotagei.cam.update

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载渠道**测速排序**与**请求目标安全门槛**的纯函数单测 + 接线守卫（2026-10-21）。
 *
 * 背景：用户裁决「每次下载时测速排序，按下载速度选渠道」。网络真身（并发采样）在 JVM 单测里
 * 跑不了（本模块的安全门槛会拒掉本地服务器，正是它该做的），所以这里测三件能测的：
 * - [UpdateDownloader.rankChannelsBySpeed]：排序判据（降序、0 垫底不丢、全 0 保持原序）；
 * - [UpdateDownloader.isSafeRemoteUrl] / [UpdateDownloader.isPublicHost]：环回/私有/保留地址判据；
 * - 接线守卫：下载前真的测速、重定向真的不许平台自动跟随、UI 真的接了测速态。
 *
 * 「尺子自己能红」：排序与主机判据都对真身与突变体同打一把尺（文件末尾两条）。
 */
class UpdateChannelSpeedTest {

    // ------------------------------------------------------------------ 排序判据

    private fun speed(url: String, bps: Long) = UpdateDownloader.ChannelSpeed(url, bps)

    @Test
    fun `排序_按实测速度降序_0垫底且不丢`() {
        val ranked = UpdateDownloader.rankChannelsBySpeed(
            listOf(
                speed("direct", 0),
                speed("slow", 182_000),
                speed("fast", 2_300_000),
                speed("mid", 1_800_000)
            )
        )
        assertEquals(listOf("fast", "mid", "slow", "direct"), ranked)
        // 0 速度的渠道必须仍在表里（还要当最后兜底），不是被过滤掉
        assertTrue("测速为 0 的渠道不许丢", ranked.contains("direct"))
    }

    @Test
    fun `排序_同速保持输入顺序_全0时输出与输入完全一致`() {
        assertEquals(
            listOf("a", "b", "c"),
            UpdateDownloader.rankChannelsBySpeed(listOf(speed("a", 5), speed("b", 5), speed("c", 5)))
        )
        // 全部测速失败 = 回到「直连优先 + 镜像表序」的旧 fallback
        val zeroes = listOf(speed("direct", 0), speed("m1", 0), speed("m2", 0))
        assertEquals(listOf("direct", "m1", "m2"), UpdateDownloader.rankChannelsBySpeed(zeroes))
        assertTrue(UpdateDownloader.rankChannelsBySpeed(emptyList()).isEmpty())
        // 负数（防御性：不该出现）同样按「垫底」处理
        assertEquals(
            listOf("good", "neg"),
            UpdateDownloader.rankChannelsBySpeed(listOf(speed("neg", -1), speed("good", 7)))
        )
    }

    @Test
    fun `测速上限是绝对字面量_256KiB与6秒预算`() {
        assertEquals("单渠道采样上限", 262_144, UpdateDownloader.SPEED_TEST_BYTES)
        assertEquals("测速整段预算", 6_000, UpdateDownloader.SPEED_TEST_BUDGET_MS)
        assertEquals("测速连接超时", 4_000, UpdateDownloader.SPEED_TEST_CONNECT_TIMEOUT_MS)
        assertEquals("测速读超时", 3_000, UpdateDownloader.SPEED_TEST_READ_TIMEOUT_MS)
        assertTrue("采样上限必须小于真实 APK 体量（否则测速=下载）", UpdateDownloader.SPEED_TEST_BYTES < 5_000_000)
    }

    // ------------------------------------------------------------------ 请求目标安全门槛

    @Test
    fun `URL门槛_只允许HTTPS_拒环回与私有地址`() {
        assertTrue(UpdateDownloader.isSafeRemoteUrl("https://gh-proxy.com/https://github.com/a.apk"))
        assertTrue(UpdateDownloader.isSafeRemoteUrl("https://api.github.com/repos/x"))
        assertFalse("明文 HTTP 必须拒", UpdateDownloader.isSafeRemoteUrl("http://gh-proxy.com/x"))
        assertFalse("非 URL 必须拒", UpdateDownloader.isSafeRemoteUrl("not a url"))
        assertFalse(UpdateDownloader.isSafeRemoteUrl("https://127.0.0.1/x"))
        assertFalse(UpdateDownloader.isSafeRemoteUrl("https://localhost/x"))
        assertFalse(UpdateDownloader.isSafeRemoteUrl("https://10.0.0.5/x"))
        assertFalse("userinfo 不能绕过主机判据", UpdateDownloader.isSafeRemoteUrl("https://user:pw@127.0.0.1/x"))
        assertFalse(UpdateDownloader.isSafeRemoteUrl("https://[::1]/x"))
    }

    @Test
    fun `主机判据_环回私有链路本地保留全拒_公网域名与地址放行`() {
        // 放行
        listOf(
            "api.github.com", "gh-proxy.com", "gh-proxy.org", "gh.monlor.com", "ghproxy.imciel.com",
            "8.8.8.8", "172.32.0.1", "100.128.0.1", "example.invalid",
            "2001:4860:4860::8888", "[2001:4860:4860::8888]", "[::ffff:8.8.8.8]"
        ).forEach { assertTrue("应放行：$it", UpdateDownloader.isPublicHost(it)) }
        // 拒
        listOf(
            "", "localhost", "LOCALHOST.", "x.localhost",
            "127.0.0.1", "127.1.2.3", "0.0.0.0",
            "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.1.1", "100.64.0.1", "100.127.255.255",
            "192.0.2.1", "198.18.0.1", "198.51.100.7", "203.0.113.9",
            "224.0.0.1", "255.255.255.255",
            "[::1]", "::1", "[fd00::1]", "[fe80::1]", "[::ffff:127.0.0.1]",
            // 文档专用 / discard-only
            "[2001:db8::1]", "[100::1]"
        ).forEach { assertFalse("应拒绝：$it", UpdateDownloader.isPublicHost(it)) }
    }

    /**
     * 「像 IP 又不是规范点分十进制」的编码形式：旧判据把它们当**域名**放行，而平台的
     * inet_aton 语义会把它们解析成内网/环回地址——镜像一个 302 就把请求引到路由器上。
     */
    @Test
    fun `主机判据_非规范IP编码一律拒_不落域名分支放行`() {
        listOf(
            // 十进制整数 / 简写 IPv4（inet_aton：2130706433 与 127.1 都是 127.0.0.1）
            "2130706433", "127.1", "127.0.1", "10.1",
            // 八进制（inet_aton：0177 是八进制 = 127）
            "0177.0.0.1", "127.000.000.001",
            // 十六进制
            "0x7f.0.0.1", "0x7f.1",
            // 超范围段 / 段数不对：像 IP 又不是，禁猜
            "1.2.3.999", "1.2.3", "1.2.3.4.5",
            // IPv4 映射 IPv6 的十六进制形态（::ffff:c0a8:1 = 192.168.0.1）
            "[::ffff:c0a8:1]", "[::ffff:7f00:1]", "[0:0:0:0:0:ffff:127.0.0.1]", "[::ffff:10.0.0.1]",
            // IPv4 兼容形态（::a9fe:a9fe = 169.254.169.254 云元数据）与 NAT64
            "[::a9fe:a9fe]", "[64:ff9b::a9fe:a9fe]",
            // 其它非规范 IPv6
            "[::1%eth0]", "[1::2::3]", "[12345::]"
        ).forEach { assertFalse("应拒绝：$it", UpdateDownloader.isPublicHost(it)) }
    }

    /** 尺子自证：换回 2026-10-21 的旧判据（非 4 段/非纯数字就当域名），上面那把尺子必须红 */
    @Test
    fun `尺子自己能红_旧判据放行的编码形式新判据必须拒`() {
        // 旧 isPublicIpv4OrDomain 的判据复刻（4 段纯数字才判私网，否则一律放行）
        fun oldGate(h: String): Boolean {
            val parts = h.split('.')
            if (parts.size != 4) return true
            val o = IntArray(4)
            for (i in 0 until 4) {
                val s = parts[i]
                if (s.isEmpty() || s.length > 3 || !s.all { it.isDigit() }) return true
                o[i] = s.toIntOrNull() ?: return true
                if (o[i] !in 0..255) return true
            }
            val (a, b) = o[0] to o[1]
            return !(a == 127 || a == 10 || a == 0 ||
                (a == 192 && b == 168) || (a == 172 && b in 16..31) || a >= 224)
        }
        val bypass = listOf(
            "2130706433", "127.1", "0177.0.0.1", "0x7f.0.0.1", "1.2.3.999",
            "[::ffff:c0a8:1]", "[::ffff:7f00:1]", "[0:0:0:0:0:ffff:127.0.0.1]", "[::a9fe:a9fe]"
        )
        // 良品必须先真的绿
        bypass.forEach { assertFalse("新判据必须先拒：$it", UpdateDownloader.isPublicHost(it)) }
        // 旧判据在这些形态上必须放行（否则新尺子没有增量，测了个恒真式）
        val leaked = bypass.filter { host ->
            if (host.startsWith("[")) {
                // 旧 IPv6 判据：只认点分十进制的 ::ffff: 映射，其余全放行
                val inner = host.removePrefix("[").removeSuffix("]").lowercase()
                !(inner == "::1" || inner == "::" ||
                    inner.startsWith("fc") || inner.startsWith("fd") ||
                    inner.startsWith("fe8") || inner.startsWith("fe9") ||
                    inner.startsWith("fea") || inner.startsWith("feb") ||
                    (inner.startsWith("::ffff:") && !oldGate(inner.removePrefix("::ffff:"))))
            } else {
                oldGate(host)
            }
        }
        assertTrue("旧判据在这些编码形态上必须放行，新尺子才量到了东西：实际只有 $leaked", leaked.isNotEmpty())
        assertEquals("全部形态旧判据都放行", bypass.size, leaked.size)
    }

    // ------------------------------------------------------------------ 接线守卫

    /** 接线体检：返回违规清单，空 = 接线完整（真身与突变体同吃） */
    private fun wiringViolations(src: String): List<String> {
        val bad = ArrayList<String>()
        val download = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "downloadApk"))
        if (!download.contains("measureAndRank(downloadCandidates(directUrl)")) {
            bad.add("downloadApk 没有「先测速排序再下载」：候选未经测速直接按原序试")
        }
        val measureAt = download.indexOf("measureAndRank(")
        val tryAt = download.indexOf("tryNode(")
        if (measureAt < 0 || tryAt < 0) {
            bad.add("downloadApk 里找不到测速或下载调用（守卫失去输入）")
        } else if (measureAt > tryAt) {
            bad.add("测速排序发生在下载之后——速度没起作用")
        }
        if (!download.contains("onSpeedTested()")) {
            bad.add("测速完成没有回调 onSpeedTested（UI 会一直显示「正在选路」）")
        }
        val raw = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "openRaw"))
        if (!raw.contains("isSafeRemoteUrl(url)")) {
            bad.add("openRaw 缺 URL 安全门槛（环回/私有地址照发）")
        }
        if (!raw.contains("instanceFollowRedirects = false")) {
            bad.add("重定向被交回平台自动跟随（不校验目标主机，可被镜像引向内网）")
        }
        val follow = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "openFollowing"))
        if (!follow.contains("openRaw(")) {
            bad.add("openFollowing 没有逐跳开新连接（重定向目标没过安全门槛）")
        }
        if (!follow.contains("MAX_REDIRECTS")) {
            bad.add("openFollowing 缺跳数上限（重定向环可无限跟）")
        }
        return bad
    }

    private fun downloaderSource(): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("update/UpdateDownloader.kt"))

    @Test
    fun `接线_下载前测速_重定向手写跟随_UI接测速态`() {
        val violations = wiringViolations(downloaderSource())
        assertEquals("接线发现断点：\n${violations.joinToString("\n")}", emptyList<String>(), violations)
        val settings = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
        assertTrue("UI 必须把 onSpeedTested 接进下载协程", settings.contains("onSpeedTested = {"))
        assertTrue("下载初始态必须是测速态", settings.contains("UpdateDownloadState(measuring = true)"))
        assertTrue("测速完成必须落 measuring = false", settings.contains("measuring = false"))
    }

    @Test
    fun `尺子自己能红_拆测速或放回自动重定向必报红`() {
        val src = downloaderSource()
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", wiringViolations(src).isEmpty())
        // 突变 1：把「测速排序」退化成直接用原序候选
        val noSpeed = src.replace(
            "measureAndRank(downloadCandidates(directUrl), isCancelled)",
            "downloadCandidates(directUrl)"
        )
        assertTrue(
            "拆掉测速后尺子必须红",
            wiringViolations(noSpeed).any { it.contains("测速") }
        )
        // 突变 2：重定向交回平台自动跟随
        val autoFollow = src.replace("instanceFollowRedirects = false", "instanceFollowRedirects = true")
        assertTrue(
            "放回自动重定向后尺子必须红",
            wiringViolations(autoFollow).any { it.contains("重定向") }
        )
        // 突变 3：拆 URL 安全门槛
        val noHostCheck = src.replace("if (!isSafeRemoteUrl(url)) return null", "")
        assertTrue(
            "拆掉主机门槛后尺子必须红",
            wiringViolations(noHostCheck).any { it.contains("安全") }
        )
    }

    @Test
    fun `尺子自己能红_排序退化成原序突变体必须偏离`() {
        val input = listOf(speed("direct", 10), speed("mirror", 900), speed("dead", 0))
        val mutant: (List<UpdateDownloader.ChannelSpeed>) -> List<String> = { list -> list.map { it.url } }
        assertTrue(
            "排序被退化成「原样返回」时本尺子必须能红",
            mutant(input) != UpdateDownloader.rankChannelsBySpeed(input)
        )
    }
}
