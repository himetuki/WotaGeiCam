package com.wotagei.cam.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新纯逻辑（[UpdateChecker] 全家）的行为测试 + 突变自证。
 *
 * 「尺子自己能红」：所有判据写成吃输入的纯函数（或对真身/突变体同打一把尺），
 * 防判据写成恒等式（AGENTS.md：由定义直接推出的等式测不出实现退化）。
 */
class UpdateLogicTest {

    // ------------------------------------------------------------------ 版本比较

    @Test
    fun `版本比较_同版本含r号_不算更新`() {
        assertTrue(CompareVersions.compare("0.0.7-r09", "v0.0.7-r09") == 0)
        assertFalse(CompareVersions.isNewerAvailable("0.0.7-r09", "v0.0.7-r09"))
    }

    @Test
    fun `版本比较_主版本三段数字比大小`() {
        // 远端主版本更大（哪怕 r 号更小）→ 提示
        assertTrue(CompareVersions.isNewerAvailable("0.0.7-r99", "v0.0.8"))
        assertTrue(CompareVersions.isNewerAvailable("0.0.9", "v0.1.0"))
        assertTrue(CompareVersions.isNewerAvailable("0.9.9", "v1.0.0"))
        // 远端主版本更小 → 不提示
        assertFalse(CompareVersions.isNewerAvailable("0.1.0", "v0.0.99-r99"))
        assertFalse(CompareVersions.isNewerAvailable("1.0.0", "v0.9.9"))
    }

    @Test
    fun `版本比较_主版本相同比r号_无r记0`() {
        assertTrue(CompareVersions.isNewerAvailable("0.0.7", "v0.0.7-r01"))
        assertTrue(CompareVersions.isNewerAvailable("0.0.7-r01", "v0.0.7-r02"))
        // 本机有 r、远端无 r（远端 r 记 0）→ 不提示
        assertFalse(CompareVersions.isNewerAvailable("0.0.7-r05", "v0.0.7"))
        // 两位数 r 号按数值比（不是字符串比）
        assertTrue(CompareVersions.isNewerAvailable("0.0.7-r09", "v0.0.7-r10"))
        assertFalse(CompareVersions.isNewerAvailable("0.0.7-r09", "v0.0.7-r08"))
    }

    @Test
    fun `版本比较_解析失败保守不提示`() {
        // 远端 tag 乱七八糟（未定义形态）一律不提示
        assertFalse(CompareVersions.isNewerAvailable("0.0.7-r09", "v0.0.8-beta"))
        assertFalse(CompareVersions.isNewerAvailable("0.0.7-r09", "v0.0.8-r01-x"))
        assertFalse(CompareVersions.isNewerAvailable("0.0.7-r09", "v0.0.8.1"))
        assertFalse(CompareVersions.isNewerAvailable("0.0.7-r09", "nonsense"))
        assertFalse(CompareVersions.isNewerAvailable("0.0.7-r09", ""))
        // 本机 versionName 坏了也不提示（保守）
        assertFalse(CompareVersions.isNewerAvailable("garbage", "v0.1.0"))
    }

    /** 突变自证：把比较函数退化成「恒不提示」，同尺必红 */
    @Test
    fun `版本比较尺子自己能红_恒等式突变体必报红`() {
        val mutant = { _: String, _: String -> false } // 退化：永远不提示
        assertTrue(
            "恒不提示的突变体必须被行为判据抓住",
            mutant("0.0.7-r09", "v0.1.0") != CompareVersions.isNewerAvailable("0.0.7-r09", "v0.1.0")
        )
    }

    // ------------------------------------------------------------------ 镜像链状态机

    /** 链行为判据：顺序、URL 变换、耗尽语义，真身与突变体同打一把尺 */
    private fun chainIssues(chain: MirrorChain, expectedLabels: List<String>, rawUrl: String): List<String> {
        val seen = ArrayList<String>()
        val urls = ArrayList<String>()
        while (!chain.exhausted) {
            seen.add(chain.currentLabel)
            urls.add(chain.urlFor(rawUrl))
            chain.advance()
        }
        val issues = ArrayList<String>()
        if (seen != expectedLabels) issues.add("节点顺序 $seen != $expectedLabels")
        if (chain.advance()) issues.add("耗尽后 advance 必须返回 false")
        if (urls.zip(expectedLabels).any { (url, label) ->
                url != if (label == "direct") rawUrl else label + rawUrl
            }
        ) issues.add("URL 变换与节点前缀不符：$urls")
        return issues
    }

    /**
     * 下载候选的判据（真身与突变体同打一把尺）：直连必须在首位、每个镜像前缀恰好出现一次、
     * 镜像候选 = 前缀 + 直连 URL（不做任何改写）。**不是**「结果等于构造式」那种恒等式。
     */
    private fun candidateIssues(candidates: List<String>, directUrl: String): List<String> {
        val issues = ArrayList<String>()
        if (candidates.firstOrNull() != directUrl) issues.add("直连不在首位：${candidates.firstOrNull()}")
        if (candidates.size != 1 + UpdateConfig.MIRROR_PREFIXES.size) {
            issues.add("候选数 ${candidates.size} != 1 直连 + ${UpdateConfig.MIRROR_PREFIXES.size} 镜像")
        }
        if (candidates.toSet().size != candidates.size) issues.add("候选里有重复 URL：$candidates")
        UpdateConfig.MIRROR_PREFIXES.forEachIndexed { i, prefix ->
            val url = candidates.getOrNull(i + 1)
            if (url != prefix + directUrl) issues.add("镜像候选 ${i + 1} 不是「前缀 + 直连」：$url")
        }
        return issues
    }

    @Test
    fun `下载候选_直连在首位_后接每个镜像前缀`() {
        val raw = "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.9-r06/v0.0.9-r06-universal-release-Android.apk"
        val issues = candidateIssues(UpdateDownloader.downloadCandidates(raw), raw)
        assertEquals("下载候选行为退化：${issues.joinToString()}", emptyList<String>(), issues)
        // 镜像表必须全部 HTTPS（明文代理等于让中间人换包）
        assertTrue(
            "镜像表里有非 HTTPS 前缀：${UpdateConfig.MIRROR_PREFIXES.filterNot { UpdateDownloader.isHttpsUrl(it + "x") }}",
            UpdateConfig.MIRROR_PREFIXES.all { UpdateDownloader.isHttpsUrl(it + "x") }
        )
    }

    @Test
    fun `探测镜像链_无直连_只走确认能代理API的前缀`() {
        val raw = UpdateConfig.REPO_API_LATEST
        assertEquals(
            "探测链行为退化：${chainIssues(MirrorChain.probeChain(), UpdateConfig.API_MIRROR_PREFIXES, raw).joinToString()}",
            emptyList<String>(),
            chainIssues(MirrorChain.probeChain(), UpdateConfig.API_MIRROR_PREFIXES, raw)
        )
        // API 前缀必须是镜像表的子集（不许凭空多出没经过下载实测的域名）
        assertTrue(
            "API 镜像链出现镜像表外的前缀：${UpdateConfig.API_MIRROR_PREFIXES - UpdateConfig.MIRROR_PREFIXES.toSet()}",
            UpdateConfig.MIRROR_PREFIXES.containsAll(UpdateConfig.API_MIRROR_PREFIXES)
        )
    }

    @Test
    fun `镜像链_reset后从头再试`() {
        val chain = MirrorChain.probeChain()
        chain.advance(); chain.advance()
        val before = chain.cursor
        chain.reset()
        assertTrue("reset 必须回到首节点", before > 0 && chain.cursor == 0)
    }

    /** 突变自证：把镜像链节点调换顺序，同尺必红 */
    @Test
    fun `镜像链尺子自己能红_调序突变体必报红`() {
        val raw = UpdateConfig.REPO_API_LATEST
        val reversed = MirrorChain(UpdateConfig.API_MIRROR_PREFIXES.reversed().map { p -> MirrorNode(p) { u -> p + u } })
        assertTrue(
            "调换镜像顺序后尺子必须红（否则顺序判据空转）",
            chainIssues(reversed, UpdateConfig.API_MIRROR_PREFIXES, raw).isNotEmpty()
        )
    }

    /** 突变自证：丢掉直连候选（变成"永远走镜像"），同尺必红 */
    @Test
    fun `下载候选尺子自己能红_丢直连突变体必报红`() {
        val raw = "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.9-r06/a.apk"
        val noDirect = UpdateConfig.MIRROR_PREFIXES.map { it + raw }
        assertTrue(
            "丢掉直连候选后尺子必须红（测速前兜底首选必须是直连）",
            candidateIssues(noDirect, raw).isNotEmpty()
        )
    }

    // ------------------------------------------------------------------ JSON 提取

    /** GitHub releases/latest 响应节选（键序、缩进、额外键都还原真实形态） */
    private val sampleJson = """
    {
      "url": "https://api.github.com/repos/himetuki/WotaGeiCam/releases/1",
      "tag_name": "v0.0.8-r01",
      "name": "WotaGeiCam v0.0.8-r01",
      "assets": [
        {
          "url": "https://api.github.com/assets/11",
          "name": "v0.0.8-r01-release-Android.apk",
          "browser_download_url": "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/v0.0.8-r01-release-Android.apk",
          "size": 12345678
        },
        {
          "url": "https://api.github.com/assets/12",
          "name": "v0.0.8-r01-universal-release-Android.apk",
          "browser_download_url": "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/v0.0.8-r01-universal-release-Android.apk",
          "size": 23456789
        },
        {
          "url": "https://api.github.com/assets/13",
          "name": "mapping.txt",
          "browser_download_url": "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/mapping.txt",
          "size": 999
        }
      ],
      "body": "release notes with \"quoted\" text"
    }
    """.trimIndent()

    @Test
    fun `JSON提取_tag与资产_优先universal包`() {
        assertEquals("v0.0.8-r01", ReleaseJson.parseTag(sampleJson))
        val info = ReleaseJson.parse(sampleJson)!!
        assertEquals("v0.0.8-r01", info.tag)
        // 四件套里挑 universal（不是 plain 的 -release-Android.apk，也不是 mapping.txt）
        assertEquals("v0.0.8-r01-universal-release-Android.apk", info.asset?.name)
        assertEquals(23456789L, info.asset?.sizeBytes)
        assertEquals(
            "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/v0.0.8-r01-universal-release-Android.apk",
            info.asset?.url
        )
    }

    @Test
    fun `JSON提取_无universal时取第一个apk_无apk为null`() {
        val noUniversal = """
            {"tag_name": "v0.1.0", "assets": [
               {"name": "mapping.txt", "browser_download_url": "https://x/mapping.txt", "size": 1},
               {"name": "v0.1.0-release-Android.apk", "browser_download_url": "https://x/a.apk", "size": 7}
            ]}
        """.trimIndent()
        assertEquals("v0.1.0-release-Android.apk", ReleaseJson.pickApkAsset(ReleaseJson.parseAssets(noUniversal))?.name)

        val noApk = """
            {"tag_name": "v0.1.0", "assets": [{"name": "mapping.txt", "browser_download_url": "https://x/m.txt", "size": 1}]}
        """.trimIndent()
        assertNull(ReleaseJson.pickApkAsset(ReleaseJson.parseAssets(noApk)))
        assertNull(ReleaseJson.parse(noApk)?.asset)

        // 完全没有 assets 键
        assertNull(ReleaseJson.parse("""{"tag_name": "v0.1.0"}""")?.asset)
        // 没有 tag_name
        assertNull(ReleaseJson.parse("""{"assets": []}"""))
    }

    /** 突变自证：JSON 里 tag 换掉，提取必须跟着变（防提取器把常量焊死） */
    @Test
    fun `JSON提取尺子自己能红_tag变化必须被提取出来`() {
        val mutant = sampleJson.replace("v0.0.8-r01", "v0.9.9-r77")
        assertEquals("v0.9.9-r77", ReleaseJson.parseTag(mutant))
    }

    @Test
    fun `资产名安全判据_只接受纯文件名apk`() {
        assertTrue("正常 CI 产物名", ReleaseJson.isSafeReleaseAssetName("v0.0.9-r03-universal-release-Android.apk"))
        assertFalse("路径分隔符", ReleaseJson.isSafeReleaseAssetName("../evil.apk"))
        assertFalse("反斜杠", ReleaseJson.isSafeReleaseAssetName("dir\\evil.apk"))
        assertFalse("绝对路径", ReleaseJson.isSafeReleaseAssetName("/sdcard/evil.apk"))
        assertFalse("非 apk", ReleaseJson.isSafeReleaseAssetName("mapping.txt"))
        assertFalse("空名", ReleaseJson.isSafeReleaseAssetName(""))
        assertFalse("null", ReleaseJson.isSafeReleaseAssetName(null))
        assertFalse("超长名", ReleaseJson.isSafeReleaseAssetName("a".repeat(256) + ".apk"))
        // 不可信资产名不得被 pickApkAsset 选中（否则会拼进下载 URL）
        val unsafe = listOf(ReleaseAsset("../evil.apk", "https://x/e.apk", 1L))
        assertNull(ReleaseJson.pickApkAsset(unsafe))
    }

    // ------------------------------------------------------------------ 段 3：302 解析与资产名推导

    @Test
    fun `redirect解析_从Location抠tag`() {
        assertEquals(
            "v0.0.8-r01",
            RedirectProbe.tagFromLocation("https://github.com/himetuki/WotaGeiCam/releases/tag/v0.0.8-r01")
        )
        assertEquals(
            "v0.0.8-r01",
            RedirectProbe.tagFromLocation("https://github.com/himetuki/WotaGeiCam/releases/tag/v0.0.8-r01/")
        )
        assertNull(RedirectProbe.tagFromLocation("https://github.com/himetuki/WotaGeiCam/releases"))
        assertNull(RedirectProbe.tagFromLocation(""))
    }

    @Test
    fun `资产名推导_与CI产物四件套命名一致_平台后缀Android`() {
        // build-apk.yml 头部注释的产物名：v0.0.7-r08-universal-release-Android.apk
        // （tag 直接做前缀 + 扩展名前平台后缀 -Android）
        assertEquals(
            "v0.0.8-r01-universal-release-Android.apk",
            RedirectProbe.guessUniversalAssetName("v0.0.8-r01")
        )
        // 直连下载 URL 拼接
        assertEquals(
            "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/v0.0.8-r01-universal-release-Android.apk",
            UpdateConfig.directDownloadUrl("v0.0.8-r01", "v0.0.8-r01-universal-release-Android.apk")
        )
    }

    // ------------------------------------------------------------------ 段 1→2→3 编排（网络注入假身）

    @Test
    fun `编排_段1直连成功即用段1`() {
        val hit = mutableListOf<String>()
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { url ->
                hit.add(url)
                if (url == UpdateConfig.REPO_API_LATEST) sampleJson else null
            },
            fetchRedirectLocation = { null }
        )
        assertTrue(result is ProbeResult.UpdateAvailable)
        assertEquals("v0.0.8-r01", (result as ProbeResult.UpdateAvailable).latestTag)
        // 段 1 成功就不该再碰镜像
        assertEquals(listOf(UpdateConfig.REPO_API_LATEST), hit)
    }

    @Test
    fun `编排_段1失败走段2镜像_镜像失败走段3`() {
        val hit = mutableListOf<String>()
        val mirrorJson = """{"tag_name": "v0.0.9", "assets": [{"name": "v0.0.9-universal-release-Android.apk", "browser_download_url": "https://x/u.apk", "size": 5}]}"""
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { url ->
                hit.add(url)
                // 段 1 直连挂；段 2 第 2 个镜像活（假身按常量表成员匹配——钉「按表逐个试」而非钉某个 host）
                if (url.startsWith(UpdateConfig.MIRROR_PREFIXES[1])) mirrorJson else null
            },
            fetchRedirectLocation = { null }
        )
        assertTrue(result is ProbeResult.UpdateAvailable)
        assertEquals("v0.0.9", (result as ProbeResult.UpdateAvailable).latestTag)
        assertTrue(
            "镜像节点必须按顺序逐个试且失败快速跳过",
            hit.first() == UpdateConfig.REPO_API_LATEST &&
                hit[1].startsWith(UpdateConfig.MIRROR_PREFIXES[0]) &&
                hit[2].startsWith(UpdateConfig.MIRROR_PREFIXES[1])
        )
    }

    /**
     * 镜像 body 不可信：即使 `browser_download_url` 指向攻击者主机，下载 URL 也必须由仓库常量 +
     * 已校验资产名重建。旧实现直接采信 JSON 里的 URL，镜像可把 app 引向任意 HTTPS 端点（SSRF/
     * 投递非本仓库 APK）；sha256 在段 2 恒 null，挡不住这条路。
     */
    @Test
    fun `编排_镜像JSON的下载URL不得被采信_一律重建为仓库直连`() {
        val evil = """
            {"tag_name": "v0.0.9", "assets": [
              {"name": "v0.0.9-universal-release-Android.apk",
               "browser_download_url": "https://evil.example/payload.apk", "size": 5}
            ]}
        """.trimIndent()
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { url -> if (url.startsWith(UpdateConfig.MIRROR_PREFIXES[0])) evil else null },
            fetchRedirectLocation = { null }
        )
        val avail = result as ProbeResult.UpdateAvailable
        assertEquals(
            "下载 URL 必须由可信仓库常量重建，不能采信镜像 JSON 的 browser_download_url",
            UpdateConfig.directDownloadUrl("v0.0.9", "v0.0.9-universal-release-Android.apk"),
            avail.downloadUrl
        )
        assertFalse("不得出现攻击者主机", avail.downloadUrl.contains("evil.example"))
    }

    @Test
    fun `下载传输只允许 HTTPS`() {
        assertTrue("GitHub releases 是 HTTPS", UpdateDownloader.isHttpsUrl("https://github.com/a.apk"))
        assertTrue("镜像前缀也是 HTTPS", UpdateDownloader.isHttpsUrl(UpdateConfig.MIRROR_PREFIXES[0] + "x"))
        assertFalse("明文 HTTP 必须拒", UpdateDownloader.isHttpsUrl("http://example.com/a.apk"))
        assertFalse("FTP 必须拒", UpdateDownloader.isHttpsUrl("ftp://example.com/a.apk"))
        assertFalse("非 URL 必须拒", UpdateDownloader.isHttpsUrl("not a url"))
    }

    @Test
    fun `编排_全JSON失败段3redirect给出tag与推导资产`() {
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { null },
            fetchRedirectLocation = { "https://github.com/himetuki/WotaGeiCam/releases/tag/v0.0.8-r01" }
        )
        val avail = result as ProbeResult.UpdateAvailable
        assertEquals("v0.0.8-r01", avail.latestTag)
        assertEquals("v0.0.8-r01-universal-release-Android.apk", avail.assetName)
        // 段 3 无 size → null（下载时用 Content-Length）
        assertNull(avail.sizeBytes)
        assertEquals(
            "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/v0.0.8-r01-universal-release-Android.apk",
            avail.downloadUrl
        )
    }

    @Test
    fun `编排_远端不比本机新_即时报已是最新不再走后续段`() {
        val hit = mutableListOf<String>()
        val olderJson = """{"tag_name": "v0.0.6", "assets": [{"name": "a.apk", "browser_download_url": "https://x/a.apk", "size": 5}]}"""
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { url ->
                hit.add(url)
                if (url == UpdateConfig.REPO_API_LATEST) olderJson else null
            },
            fetchRedirectLocation = { null }
        )
        assertTrue(result is ProbeResult.UpToDate)
        // 段 1 已给出结论（不比本机新），不该再打镜像
        assertEquals(1, hit.size)
    }

    @Test
    fun `编排_有新版但JSON无apk附件_该节点无结论继续下一段`() {
        val noApkJson = """{"tag_name": "v0.9.9", "assets": [{"name": "mapping.txt", "browser_download_url": "https://x/m.txt", "size": 1}]}"""
        val okJson = """{"tag_name": "v0.9.9", "assets": [{"name": "v0.9.9-universal-release-Android.apk", "browser_download_url": "https://x/u.apk", "size": 5}]}"""
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { url ->
                if (url == UpdateConfig.REPO_API_LATEST) noApkJson
                else if (url.startsWith(UpdateConfig.MIRROR_PREFIXES[0])) okJson
                else null
            },
            fetchRedirectLocation = { null }
        )
        assertTrue("无 apk 附件的节点应跳过、下一段补上", result is ProbeResult.UpdateAvailable)
    }

    @Test
    fun `编排_三段全失败_报失败`() {
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { null },
            fetchRedirectLocation = { null }
        )
        assertTrue(result is ProbeResult.Failed)
    }

    // ------------------------------------------------------------------ 取消语义（修复轮 P2-2）

    @Test
    fun `编排_开始前已取消_返回Cancelled且一个节点都不碰`() {
        val hit = mutableListOf<String>()
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { url -> hit.add(url); null },
            fetchRedirectLocation = { url -> hit.add(url); null },
            isCancelled = { true }
        )
        assertTrue(result is ProbeResult.Cancelled)
        assertTrue("取消探针在位时编排在段 1 前就该退出：$hit", hit.isEmpty())
    }

    @Test
    fun `编排_节点失败后取消_剩余镜像与段3全部跳过`() {
        val hit = mutableListOf<String>()
        var calls = 0
        val result = probeLatestRelease(
            localVersionName = "0.0.7-r09",
            fetchJson = { url -> hit.add(url); calls++; null },
            fetchRedirectLocation = { url -> hit.add(url); null },
            isCancelled = { calls >= 1 } // 段 1 直连发出后置位
        )
        assertTrue(result is ProbeResult.Cancelled)
        // 只打了段 1 直连一次：四个镜像与段 3 全被跳过（节点内不可打断，节点间必须让位）
        assertEquals(listOf(UpdateConfig.REPO_API_LATEST), hit)
    }

    // ------------------------------------------------------------------ 下载目录过期文件判据（修复轮 P3-3 桥函数）

    @Test
    fun `过期文件判据_删part与非本轮资产_保留本轮已完成APK`() {
        val dir = listOf(
            "v0.0.8-r01-universal-release-Android.apk",      // 本轮资产：保留（安装器可能还在读）
            "v0.0.8-r01-universal-release-Android.apk.part", // 半截文件：删
            // 按代唯一的 part（修复轮 P2：.part<nanoTime>）——上一代残留，靠「非本轮资产名」删
            "v0.0.8-r01-universal-release-Android.apk.part1738888888888",
            "v0.0.7-r08-universal-release-Android.apk",      // 上一轮的旧包：删
            "v0.0.9-arm64-v8a-release-Android.apk"           // 别的资产名：删
        )
        assertEquals(
            listOf(
                "v0.0.8-r01-universal-release-Android.apk.part",
                "v0.0.8-r01-universal-release-Android.apk.part1738888888888",
                "v0.0.7-r08-universal-release-Android.apk",
                "v0.0.9-arm64-v8a-release-Android.apk"
            ),
            UpdateDownloader.staleFilesOf(dir, "v0.0.8-r01-universal-release-Android.apk")
        )
        // 空目录与干净目录都返回空
        assertTrue(UpdateDownloader.staleFilesOf(emptyList(), "a.apk").isEmpty())
        assertTrue(UpdateDownloader.staleFilesOf(listOf("a.apk"), "a.apk").isEmpty())
    }
}
