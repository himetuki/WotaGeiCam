package com.wotagei.cam.update

import com.wotagei.cam.update.UpdateInstaller.ArchiveIdentity
import com.wotagei.cam.update.UpdateInstaller.InstallRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Y43：安装闸把「用户选中的发行/变体」与「归档真实内容 + 最终字节」绑定。
 *
 * 这里全部是可执行判据（不依赖 Android PackageManager）：同签 full/lite、同签旧 r、
 * 下载后被替换的字节、可调试取证包、ABI 不符都必须被拒；正确资产必须通过。
 */
class UpdateInstallBindingTest {

    private val signers = setOf("aa")

    private fun selection(
        tag: String = "v0.0.12-r08",
        flavor: String = "full",
        abi: UpdateAbi = UpdateAbi.UNIVERSAL,
        sha256: String? = "ab".repeat(32)
    ): UpdateSelection {
        val asset = when (abi) {
            UpdateAbi.ARM64_V8A -> "WotaGeiCam-$tag-$flavor-arm64-v8a-release-Android.apk"
            UpdateAbi.ARMEABI_V7A -> "WotaGeiCam-$tag-$flavor-armeabi-v7a-release-Android.apk"
            UpdateAbi.UNIVERSAL -> "WotaGeiCam-$tag-$flavor-universal-release-Android.apk"
            UpdateAbi.UNKNOWN -> "WotaGeiCam-$tag-$flavor-x86_64-release-Android.apk"
        }
        return UpdateSelection(
            releaseTag = tag,
            flavor = flavor,
            assetName = asset,
            abi = abi,
            expectedSha256 = sha256,
            sizeBytes = 1024L,
            downloadUrl = UpdateConfig.directDownloadUrl(tag, asset)
        )
    }

    private fun archive(
        packageName: String = "com.wotagei.cam",
        versionName: String = "0.0.12-r08",
        versionCode: Long = 12,
        debuggable: Boolean = false,
        signerSet: Set<String> = signers,
        abis: Set<String> = emptySet()
    ) = ArchiveIdentity(packageName, versionName, versionCode, debuggable, signerSet, abis)

    private fun verify(
        sel: UpdateSelection? = selection(),
        actual: String? = "ab".repeat(32),
        computed: String = "ab".repeat(32),
        arc: ArchiveIdentity = archive(),
        installedCode: Long = 11
    ): InstallRejection? = UpdateInstaller.verifyArtifact(
        selection = sel,
        expectedActualSha256 = actual,
        computedSha256 = computed,
        archive = arc,
        installedPackage = "com.wotagei.cam",
        installedSigners = signers,
        installedVersionCode = installedCode
    )

    @Test
    fun `正确资产通过全部绑定`() {
        assertNull(verify())
        assertNull("lite 版本名与 tag 的 -lite 后缀一致", verify(
            sel = selection(flavor = "lite"),
            arc = archive(versionName = "0.0.12-r08-lite")
        ))
    }

    @Test
    fun `Y43_同签名异变体在无可信摘要路径也必须拒绝`() {
        // 探测走镜像时 expectedSha256=null，installer 仍必须靠 version/flavor 绑定拒绝换包。
        val noTrustedDigest = selection(sha256 = null)
        assertEquals(
            InstallRejection.FLAVOR_MISMATCH,
            verify(sel = noTrustedDigest, actual = null, arc = archive(versionName = "0.0.12-r08-lite"))
        )
        assertEquals(
            InstallRejection.FLAVOR_MISMATCH,
            verify(
                sel = selection(flavor = "lite", sha256 = null),
                actual = null,
                arc = archive(versionName = "0.0.12-r08")
            )
        )
    }

    @Test
    fun `Y43_同签名旧r构建与错误版本号拒绝`() {
        assertEquals(
            InstallRejection.VERSION_MISMATCH,
            verify(
                sel = selection(sha256 = null), actual = null,
                arc = archive(versionName = "0.0.12-r07")
            )
        )
        assertEquals(
            InstallRejection.VERSION_MISMATCH,
            verify(
                sel = selection(sha256 = null), actual = null,
                arc = archive(versionName = "0.0.12-r09")
            )
        )
        assertEquals(
            "versionCode 低于已安装版本也拒绝",
            InstallRejection.VERSION_MISMATCH,
            verify(installedCode = 13)
        )
    }

    @Test
    fun `Y43_下载完成后字节被替换必须拒绝`() {
        assertEquals(
            InstallRejection.DIGEST_MISMATCH,
            verify(computed = "cd".repeat(32), actual = "ab".repeat(32), sel = selection(sha256 = null))
        )
        assertEquals(
            "可信摘要也要在安装前重新核对当前字节",
            InstallRejection.DIGEST_MISMATCH,
            verify(computed = "cd".repeat(32), actual = null)
        )
        assertEquals(
            "大小写不同的可信摘要仍视为一致",
            null,
            verify(computed = "AB".repeat(32), actual = "ab".repeat(32))
        )
    }

    @Test
    fun `Y43_包名与签名集合不符拒绝`() {
        assertEquals(InstallRejection.PACKAGE_MISMATCH, verify(arc = archive(packageName = "com.evil")))
        assertEquals(InstallRejection.SIGNER_MISMATCH, verify(arc = archive(signerSet = setOf("bb"))))
        assertEquals(InstallRejection.SIGNER_MISMATCH, verify(arc = archive(signerSet = emptySet())))
    }

    @Test
    fun `Y64_同签debuggable取证包拒绝`() {
        assertEquals(InstallRejection.DEBUGGABLE_ARCHIVE, verify(arc = archive(debuggable = true)))
    }

    @Test
    fun `Y44_ABI分包与归档lib段不符拒绝`() {
        val arm64 = selection(abi = UpdateAbi.ARM64_V8A)
        assertEquals(
            InstallRejection.ABI_MISMATCH,
            verify(sel = arm64, arc = archive(abis = setOf("armeabi-v7a")))
        )
        assertNull(
            "归档确有 arm64 so 时通过",
            verify(sel = arm64, arc = archive(abis = setOf("arm64-v8a")))
        )
        assertEquals(
            "未知 ABI 段一律拒绝",
            InstallRejection.ABI_MISMATCH,
            verify(sel = selection(abi = UpdateAbi.UNKNOWN), arc = archive(abis = setOf("arm64-v8a")))
        )
    }

    @Test
    fun `旧调用无选择绑定时仍守包名签名与摘要`() {
        assertNull(verify(sel = null, actual = null, computed = "ab".repeat(32)))
        assertEquals(
            InstallRejection.SIGNER_MISMATCH,
            verify(sel = null, actual = null, arc = archive(signerSet = setOf("bb")))
        )
    }

    // ------------------------------------------------------------------ 来源绑定（Y43）

    @Test
    fun `重定向到非白名单域一律作废`() {
        assertEquals(
            "v0.0.12-r08",
            RedirectProbe.trustedTagFromLocation("https://github.com/himetuki/WotaGeiCam/releases/tag/v0.0.12-r08")
        )
        org.junit.Assert.assertNull(
            RedirectProbe.trustedTagFromLocation("https://evil.example/himetuki/WotaGeiCam/releases/tag/v9.9.9")
        )
        org.junit.Assert.assertNull(
            RedirectProbe.trustedTagFromLocation("https://github.com.evil.example/x/releases/tag/v9.9.9")
        )
    }

    @Test
    fun `官方JSON最终落地主机不可信时不采用该段结论`() {
        val sha = "ab".repeat(32)
        val asset = "WotaGeiCam-v0.0.12-r08-full-universal-release-Android.apk"
        val json = """
            {"tag_name":"v0.0.12-r08","assets":[
              {"name":"$asset","browser_download_url":"https://x/$asset","size":1024}
            ],"body":"SHA256-asset: $asset $sha"}
        """.trimIndent()
        val evil = probeLatestRelease(
            localVersionName = "0.0.12-r07",
            fetchJson = { null },
            fetchRedirectLocation = { null },
            flavor = "full",
            fetchJsonSource = { url, _ ->
                if (url == UpdateConfig.REPO_API_LATEST) FetchedJson(json, "https://evil.example/api") else null
            }
        )
        assertEquals("非官方最终来源不能提供可信版本/摘要", ProbeResult.Failed, evil)

        val official = probeLatestRelease(
            localVersionName = "0.0.12-r07",
            fetchJson = { null },
            fetchRedirectLocation = { null },
            flavor = "full",
            fetchJsonSource = { url, _ ->
                if (url == UpdateConfig.REPO_API_LATEST) {
                    FetchedJson(json, UpdateConfig.REPO_API_LATEST)
                } else {
                    null
                }
            }
        ) as ProbeResult.UpdateAvailable
        assertEquals(asset, official.assetName)
        assertEquals(sha, official.sha256)
        assertEquals(UpdateAbi.UNIVERSAL, official.abi)
    }
}