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
    fun `install 必须先过包名与签名校验`() {
        val src = codeOnly(mainSourceText("update/UpdateInstaller.kt"))
        val install = flatten(bodyOf(src, "install"))
        assertTrue("install 必须先调 isTrustedUpdate", install.contains("isTrustedUpdate(context, file)"))
        val trust = flatten(bodyOf(src, "isTrustedUpdate"))
        assertTrue("必须核对包名", trust.contains("archive.packageName != context.packageName"))
        assertTrue("必须读归档签名", trust.contains("signingInfo") && trust.contains("apkContentsSigners"))
        assertTrue("必须与已安装应用签名集合比对", trust.contains("sameSignerSet("))
    }
}
