package com.wotagei.cam.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Y43：官方 API 正文的可信标记必须绑定**最终落地 URL**，而不是起始 URL。
 *
 * 这里只替换连接边界（不碰生产重定向逻辑）：传输层把「最终 URL」报告给 [UpdateDownloader]，
 * 官方路径的允许集合之外的落地页，哪怕正文长得像 GitHub Release JSON，也必须整段作废；
 * 镜像段（allowedHosts=null）沿用旧的「只展示、不出摘要」流程。
 */
class UpdateProbeSourceTest {

    private fun tracked(finalUrl: String, body: String): UpdateTrackedConnectionFactory =
        { _, _, _, _ ->
            TrackedConnection(JsonConnection(body), finalUrl)
        }

    @Test
    fun `官方正文最终落地主机不在允许集合_丢弃`() {
        val body = """{"tag_name":"v9.9.9"}"""
        assertNull(
            UpdateDownloader.fetchJsonSource(
                url = UpdateConfig.REPO_API_LATEST,
                allowedHosts = UpdateConfig.OFFICIAL_API_HOSTS,
                readTimeoutMs = 1_000,
                openTracked = tracked("https://evil.example/api", body)
            )
        )
        assertNull(
            "相似域名后缀不算官方",
            UpdateDownloader.fetchJsonSource(
                url = UpdateConfig.REPO_API_LATEST,
                allowedHosts = UpdateConfig.OFFICIAL_API_HOSTS,
                readTimeoutMs = 1_000,
                openTracked = tracked("https://api.github.com.evil.example/x", body)
            )
        )
    }

    @Test
    fun `官方正文最终落地主机在允许集合_保留正文与最终URL`() {
        val body = """{"tag_name":"v9.9.9"}"""
        val got = UpdateDownloader.fetchJsonSource(
            url = UpdateConfig.REPO_API_LATEST,
            allowedHosts = UpdateConfig.OFFICIAL_API_HOSTS,
            readTimeoutMs = 1_000,
            openTracked = tracked(UpdateConfig.REPO_API_LATEST, body)
        )
        assertNotNull(got)
        assertEquals(body, got!!.body)
        assertEquals(UpdateConfig.REPO_API_LATEST, got.finalUrl)
    }

    @Test
    fun `镜像段无允许集合时沿用旧流程`() {
        val body = """{"tag_name":"v9.9.9"}"""
        val got = UpdateDownloader.fetchJsonSource(
            url = "https://gh-proxy.com/${UpdateConfig.REPO_API_LATEST}",
            allowedHosts = null,
            readTimeoutMs = 1_000,
            openTracked = tracked("https://mirror.example/api", body)
        )
        assertEquals(body, got?.body)
        assertEquals("https://mirror.example/api", got?.finalUrl)
    }

    /** 只模拟 HTTP 响应边界；正文读取/上限/最终来源判断仍走生产代码。 */
    private class JsonConnection(private val body: String) :
        HttpURLConnection(URL(UpdateConfig.REPO_API_LATEST)) {
        private val bytes = body.toByteArray(StandardCharsets.UTF_8)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = HTTP_OK
        override fun getContentLengthLong(): Long = bytes.size.toLong()
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
}