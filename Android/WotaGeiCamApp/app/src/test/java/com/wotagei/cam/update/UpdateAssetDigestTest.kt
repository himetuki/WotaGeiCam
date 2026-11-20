package com.wotagei.cam.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y44：ABI 分包必须用**自己那枚资产**的摘要，不能拿 universal 的摘要去校验。
 *
 * 这些用例全部走真实解析路径（JSON → 资产名 → 摘要行 → 选中资产），而不是只测常量：
 * - 只有分包时，分包必须带自己的 `SHA256-asset:` 行才可选；
 * - 旧三行（`SHA256:` / `SHA256-full:` / `SHA256-lite:`）只绑定 universal，绝不落到分包；
 * - 镜像段（extractSha256=false）保持既有「无摘要也允许下载」的旧流程，由安装闸兜底。
 */
class UpdateAssetDigestTest {

    private val universalDigest = "a".repeat(64)
    private val arm64Digest = "b".repeat(64)
    private val armeabiDigest = "c".repeat(64)

    private fun apk(name: String, size: Long = 1024L) =
        ReleaseAsset(name, "https://example.invalid/$name", size)

    private fun releaseJson(tag: String, assets: List<ReleaseAsset>, body: String): String {
        val assetsJson = assets.joinToString(",") { asset ->
            """{"name":"${asset.name}","browser_download_url":"${asset.url}","size":${asset.sizeBytes}}"""
        }
        val escaped = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"tag_name":"$tag","assets":[$assetsJson],"body":"$escaped"}"""
    }

    // ------------------------------------------------------------------ 逐资产摘要解析

    @Test
    fun `逐资产摘要行_解析出资产名到摘要的映射`() {
        val notes = """
            SHA256-asset: WotaGeiCam-v0.0.12-r09-full-universal-release-Android.apk $universalDigest
            SHA256-asset: WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk $arm64Digest
        """.trimIndent()
        val digests = ReleaseJson.parseAssetDigests(notes)
        assertEquals(2, digests.size)
        assertEquals(universalDigest, digests["WotaGeiCam-v0.0.12-r09-full-universal-release-Android.apk"])
        assertEquals(arm64Digest, digests["WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"])
    }

    @Test
    fun `逐资产摘要行_同名重复以第一行为准`() {
        val name = "WotaGeiCam-v0.0.12-r09-full-universal-release-Android.apk"
        val notes = "SHA256-asset: $name $universalDigest\nSHA256-asset: $name $arm64Digest"
        assertEquals(universalDigest, ReleaseJson.parseAssetDigests(notes)[name])
    }

    @Test
    fun `逐资产摘要行_不接受非法资产名`() {
        assertTrue(ReleaseJson.parseAssetDigests("SHA256-asset: ../evil.apk $universalDigest").isEmpty())
    }

    @Test
    fun `摘要按资产名解析_不做跨资产复用`() {
        val universalName = "WotaGeiCam-v0.0.12-r09-full-universal-release-Android.apk"
        val arm64Name = "WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"
        val digests = ReleaseJson.resolveAssetDigests(
            listOf(apk(universalName), apk(arm64Name)),
            "SHA256-asset: $universalName $universalDigest",
            "full"
        )
        assertEquals(universalDigest, digests[universalName])
        assertNull("universal 的摘要绝不能落到 arm64 分包上", digests[arm64Name])
    }

    // ------------------------------------------------------------------ 选包：只有分包

    @Test
    fun `Y44_只有arm64分包且带自身摘要_选中分包并使用自身摘要`() {
        val name = "WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"
        val payload = releaseJson(
            "v0.0.12-r09", listOf(apk(name)),
            "SHA256-asset: $name $arm64Digest"
        )
        val info = ReleaseJson.parse(
            payload, extractSha256 = true, flavor = "full", deviceAbis = listOf("arm64-v8a")
        )!!
        assertEquals(name, info.asset?.name)
        assertEquals(arm64Digest, info.sha256)
    }

    @Test
    fun `Y44_只有分包但说明里只有universal旧摘要_拒绝选择`() {
        val universalName = "WotaGeiCam-v0.0.12-r09-full-universal-release-Android.apk"
        val arm64Name = "WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"
        // 发版说明还是旧的 SHA256-full: 行（绑定 universal），但这次发行没有 universal 资产。
        val payload = releaseJson(
            "v0.0.12-r09", listOf(apk(arm64Name)),
            "SHA256-full: $universalDigest"
        )
        val info = ReleaseJson.parse(
            payload, extractSha256 = true, flavor = "full", deviceAbis = listOf("arm64-v8a")
        )!!
        assertNull("没有分包自身摘要时宁可不提示，也不拿 universal 摘要凑数", info.asset)
        assertNull(info.sha256)
    }

    @Test
    fun `Y44_只有分包且完全没有摘要_可信段拒绝选择`() {
        val arm64Name = "WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"
        val payload = releaseJson("v0.0.12-r09", listOf(apk(arm64Name)), "release notes without digest")
        assertNull(
            ReleaseJson.parse(payload, extractSha256 = true, flavor = "full", deviceAbis = listOf("arm64-v8a"))?.asset
        )
    }

    @Test
    fun `镜像段无可信摘要时仍保留仅分包下载的旧流程`() {
        val arm64Name = "WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"
        val payload = releaseJson("v0.0.12-r09", listOf(apk(arm64Name)), "mirror body")
        val info = ReleaseJson.parse(
            payload, extractSha256 = false, flavor = "full", deviceAbis = listOf("arm64-v8a")
        )!!
        assertEquals("镜像段没有可信摘要，安装闸（版本/ABI/签名）兜底", arm64Name, info.asset?.name)
        assertNull(info.sha256)
    }

    // ------------------------------------------------------------------ 选包：universal 与分包共存

    @Test
    fun `Y44_universal存在时优先universal且只信任它自己的摘要`() {
        val universalName = "WotaGeiCam-v0.0.12-r09-full-universal-release-Android.apk"
        val arm64Name = "WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"
        val payload = releaseJson(
            "v0.0.12-r09", listOf(apk(arm64Name), apk(universalName)),
            "SHA256-asset: $arm64Name $arm64Digest\nSHA256-asset: $universalName $universalDigest"
        )
        val info = ReleaseJson.parse(
            payload, extractSha256 = true, flavor = "full", deviceAbis = listOf("arm64-v8a")
        )!!
        assertEquals(universalName, info.asset?.name)
        assertEquals("选中 universal 时摘要必须是 universal 自己的", universalDigest, info.sha256)
    }

    @Test
    fun `Y44_旧三行只绑定universal_分包不继承`() {
        val universalName = "WotaGeiCam-v0.0.12-r09-lite-universal-release-Android.apk"
        val arm64Name = "WotaGeiCam-v0.0.12-r09-lite-arm64-v8a-release-Android.apk"
        val digests = ReleaseJson.resolveAssetDigests(
            listOf(apk(universalName), apk(arm64Name)),
            "SHA256-lite: $universalDigest",
            "lite"
        )
        assertEquals(universalDigest, digests[universalName])
        assertNull(digests[arm64Name])
    }

    // ------------------------------------------------------------------ 设备 ABI 顺序与未知 ABI

    @Test
    fun `分包按设备ABI顺序优先_不按资产数组顺序`() {
        val arm64Name = "WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"
        val armeabiName = "WotaGeiCam-v0.0.12-r09-full-armeabi-v7a-release-Android.apk"
        val payload = releaseJson(
            "v0.0.12-r09", listOf(apk(armeabiName), apk(arm64Name)),
            """
            SHA256-asset: $armeabiName $armeabiDigest
            SHA256-asset: $arm64Name $arm64Digest
            """.trimIndent()
        )
        val info = ReleaseJson.parse(
            payload, extractSha256 = true, flavor = "full", deviceAbis = listOf("arm64-v8a", "armeabi-v7a")
        )!!
        assertEquals(arm64Name, info.asset?.name)
        assertEquals(arm64Digest, info.sha256)
    }

    @Test
    fun `不支持的ABI不因摘要齐全而被选中`() {
        val arm64Name = "WotaGeiCam-v0.0.12-r09-full-arm64-v8a-release-Android.apk"
        val payload = releaseJson(
            "v0.0.12-r09", listOf(apk(arm64Name)),
            "SHA256-asset: $arm64Name $arm64Digest"
        )
        assertNull(
            ReleaseJson.parse(
                payload, extractSha256 = true, flavor = "full", deviceAbis = listOf("armeabi-v7a")
            )?.asset
        )
    }

    @Test
    fun `未知ABI段一律拒绝选择`() {
        val x86Name = "WotaGeiCam-v0.0.12-r09-full-x86_64-release-Android.apk"
        assertEquals(UpdateAbi.UNKNOWN, UpdateAbi.fromAssetName(x86Name))
        assertNull(ReleaseJson.pickApkAsset(listOf(apk(x86Name)), "full"))
        assertFalse(UpdateAbi.UNKNOWN.presentIn(setOf("x86_64")))
    }
}