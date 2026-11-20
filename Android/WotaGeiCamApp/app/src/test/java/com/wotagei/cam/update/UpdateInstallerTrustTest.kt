package com.wotagei.cam.update

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新安装前的包名/签名校验守卫。
 *
 * 背景：段 1（api.github.com）被墙时探测会走镜像；段 2/3 不提供可信 SHA-256，下载只靠
 * PK 魔数 + size。镜像返回一个不同包名的自签 APK 时，系统安装器会把它当**新应用**安装
 * （签名强校验只拦同包名更新）。这里在拉起安装器前核对包名与证书集合，把这条路堵死。
 *
 * Android PackageManager 真身 JVM 不可用，故行为部分只测纯函数 [UpdateInstaller.sameSignerSet]，
 * 接线部分用源码结构守卫钉住。
 */
class UpdateInstallerTrustTest {

    @Test
    fun `签名集合必须非空且完全相等`() {
        assertTrue("同一枚证书", UpdateInstaller.sameSignerSet(setOf("aa"), setOf("aa")))
        assertTrue("同一组多签名", UpdateInstaller.sameSignerSet(setOf("aa", "bb"), setOf("bb", "aa")))
        assertFalse("不同证书（同包名也会被系统拒，但这里提前拦）", UpdateInstaller.sameSignerSet(setOf("aa"), setOf("bb")))
        assertFalse("子集冒充", UpdateInstaller.sameSignerSet(setOf("aa"), setOf("aa", "bb")))
        assertFalse("未签名包", UpdateInstaller.sameSignerSet(emptySet(), emptySet()))
        assertFalse("空 vs 有", UpdateInstaller.sameSignerSet(emptySet(), setOf("aa")))
    }

    @Test
    fun `install 必须先过包名签名摘要与变体校验`() {
        val src = codeOnly(mainSourceText("update/UpdateInstaller.kt"))
        val launch = flatten(bodyOf(src, "launchVerified"))
        assertTrue("启动安装前必须重新算当前字节的 SHA", launch.contains("sha256Hex(file)"))
        assertTrue("启动安装前必须过纯判据 verifyArtifact", launch.contains("verifyArtifact("))
        val trust = flatten(bodyOf(src, "verifyArtifact"))
        assertTrue("必须核对包名", trust.contains("archive.packageName != installedPackage"))
        assertTrue("必须读归档签名并与已安装集合比对", trust.contains("sameSignerSet(archive.signers, installedSigners)"))
        assertTrue("必须核对下载完成时记录的实际摘要", trust.contains("expectedActualSha256"))
        assertTrue("必须核对探测得到的可信摘要", trust.contains("selection.expectedSha256"))
        assertTrue("必须按 tag 绑定 versionName", trust.contains("selection.expectedVersionName"))
        assertTrue("必须拒绝 debuggable 取证包（Y64）", trust.contains("archive.debuggable"))
        assertTrue("必须核对 ABI 段", trust.contains("selection.abi.presentIn(archive.abis)"))
    }
}
