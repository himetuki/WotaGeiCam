package com.wotagei.cam.update

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新弹窗展示 GitHub Release 说明（notes）的**纯函数单测 + 接线守卫**（2026-10-21）。
 *
 * 三条被测线：
 * - 探测层：`body` 段 1/段 2 都读（2026-10-21 用户裁决：说明只用于展示，不参与安装判断），
 *   但前缀段 3 只有 Location，说明恒 null；
 * - 展示层 [ReleaseNotes.forDisplay]：去 Markdown 标记、隐去机器用的 SHA256 行、超长按行截断；
 * - 接线：探测结果真的把 notes 带进弹窗，且弹窗真的整形后再展示。
 *
 * 「尺子自己能红」：整形判据对真身与「原样返回」突变体同打一把尺（见最后两条）。
 */
class UpdateNotesTest {

    /** 64 位十六进制用程序构造——手写极易多一位少一位 */
    private val hashHex = "0123456789abcdef".repeat(4)

    private fun jsonWithBody(body: String) =
        """{"tag_name": "v0.0.9-r07", "assets": [{"name": "v0.0.9-r07-universal-release-Android.apk", """ +
            """"browser_download_url": "https://x/u.apk", "size": 5}], "body": "$body"}"""

    // ------------------------------------------------------------------ 探测层：说明只在段 1

    @Test
    fun `parse_说明原文两段都读_哈希仍只信段1`() {
        val json = jsonWithBody("第一行\\n第二行")
        assertEquals("第一行\n第二行", ReleaseJson.parse(json, extractSha256 = true)?.notes)
        assertEquals(
            "镜像段也读说明（2026-10-21 用户裁决：只用于展示，不参与判断）",
            "第一行\n第二行",
            ReleaseJson.parse(json, extractSha256 = false)?.notes
        )
        assertEquals("默认调用同样读说明", "第一行\n第二行", ReleaseJson.parse(json)?.notes)
        // 说明里没有正文（body 为 null）时不能凭空造出 notes
        assertNull(
            ReleaseJson.parse("""{"tag_name": "v0.0.9-r07", "body": null}""", extractSha256 = true)?.notes
        )
    }

    @Test
    fun `编排_段1与镜像段的新版都带说明`() {
        val json = jsonWithBody("说明正文\\n- 条目")
        val direct = probeLatestRelease(
            localVersionName = "0.0.9-r06",
            fetchJson = { url -> if (url == UpdateConfig.REPO_API_LATEST) json else null },
            fetchRedirectLocation = { null }
        ) as ProbeResult.UpdateAvailable
        assertEquals("说明正文\n- 条目", direct.notes)

        val viaMirror = probeLatestRelease(
            localVersionName = "0.0.9-r06",
            // 段 1 挂、任一镜像回同样的 JSON（含 body）
            fetchJson = { url -> if (url != UpdateConfig.REPO_API_LATEST) json else null },
            fetchRedirectLocation = { null }
        ) as ProbeResult.UpdateAvailable
        assertEquals("镜像段的新版同样带说明（只展示、不参与安装判断）", "说明正文\n- 条目", viaMirror.notes)
    }

    @Test
    fun `编排_段3redirect没有说明`() {
        val viaRedirect = probeLatestRelease(
            localVersionName = "0.0.9-r06",
            fetchJson = { null },
            fetchRedirectLocation = { "https://github.com/himetuki/WotaGeiCam/releases/tag/v0.0.9-r07" }
        ) as ProbeResult.UpdateAvailable
        assertNull("段 3 只有 Location、没有正文，说明只能是 null", viaRedirect.notes)
    }

    // ------------------------------------------------------------------ 展示层整形

    @Test
    fun `展示整形_去标题井号与粗体与条目符号_文字不动`() {
        val raw = """
            # WotaGeiCam v0.0.9-r07

            一句话说明。

            ## 本次更新

            ### 视频更安全

            - **导出失败留下的视频更容易被找回来**：说明文字。
            * 第二条目 `代码样式` 保留原文。
            + 第三条目
            ---
        """.trimIndent()
        assertEquals(
            listOf(
                "WotaGeiCam v0.0.9-r07",
                "",
                "一句话说明。",
                "",
                "本次更新",
                "",
                "视频更安全",
                "",
                "• 导出失败留下的视频更容易被找回来：说明文字。",
                "• 第二条目 代码样式 保留原文。",
                "• 第三条目"
            ).joinToString("\n"),
            ReleaseNotes.forDisplay(raw)
        )
    }

    @Test
    fun `展示整形_SHA256行与它上面的标签行隐去`() {
        val raw = """
            ## 安装

            - 请下载 universal 包。
            - universal 包 SHA256：
            SHA256: $hashHex
            - 升级请直接覆盖安装。
        """.trimIndent()
        assertEquals(
            "安装\n\n• 请下载 universal 包。\n• 升级请直接覆盖安装。",
            ReleaseNotes.forDisplay(raw)
        )
        // 标签与哈希挤在同一行（半角冒号）也要连行带掉
        assertEquals(
            "安装\n\n• 升级请直接覆盖安装。",
            ReleaseNotes.forDisplay("## 安装\n\nuniversal 包 SHA256: $hashHex\n- 升级请直接覆盖安装。")
        )
    }

    @Test
    fun `展示整形_空说明与只剩哈希行返回null`() {
        assertNull(ReleaseNotes.forDisplay(null))
        assertNull(ReleaseNotes.forDisplay("   \n\t\n"))
        assertNull("只剩机器哈希行时弹窗不显示说明区", ReleaseNotes.forDisplay("SHA256: $hashHex"))
    }

    @Test
    fun `展示整形_超长按行截断补省略号_上限是绝对4000`() {
        assertEquals(
            "展示上限是写死的字数（突变成长大值本行必红）",
            4000, ReleaseNotes.MAX_DISPLAY_CHARS
        )
        val long = (1..1200).joinToString("\n") { "第${it}行" }
        assertTrue("样本必须真的超上限，否则测不到截断", long.length > ReleaseNotes.MAX_DISPLAY_CHARS)
        val out = ReleaseNotes.forDisplay(long)!!
        assertTrue("截断后不得超过上限（外加一行省略号）", out.length <= ReleaseNotes.MAX_DISPLAY_CHARS + 2)
        assertTrue("截断必须补省略号", out.endsWith("\n…"))
        val kept = out.removeSuffix("\n…").lines()
        assertEquals("截断必须落在行边界：最后一行是完整的一条", "第${kept.size}行", kept.last())
        assertFalse("截断后不该还含整段原文的尾部", out.contains("第1200行"))
    }

    // ------------------------------------------------------------------ 尺子自己能红

    @Test
    fun `尺子自己能红_原样返回的突变体必须偏离`() {
        val raw = "## 标题\n- **条目**\nSHA256: $hashHex"
        val mutant: (String?) -> String? = { it } // 退化：不做任何整形
        assertTrue(
            "整形逻辑被退化成原样返回时，本节判据必须能红",
            mutant(raw) != ReleaseNotes.forDisplay(raw)
        )
    }

    // ------------------------------------------------------------------ 接线守卫

    @Test
    fun `接线_探测把notes带进结论_弹窗整形后展示`() {
        val checker = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("update/UpdateChecker.kt"))
        )
        assertTrue(
            "jsonNode 必须把 info.notes 传进 UpdateAvailable（否则弹窗永远拿不到说明）",
            checker.contains("info.sha256, info.notes")
        )
        val settings = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
        assertTrue(
            "UI 必须把探测结果的说明传进更新弹窗（漏传 = 本功能静默失效）",
            settings.contains("notes = avail.notes")
        )
        assertTrue(
            "弹窗必须整形后再展示（原样 Markdown 会带一堆 # 与 **）",
            settings.contains("ReleaseNotes.forDisplay(notes)")
        )
    }
}
