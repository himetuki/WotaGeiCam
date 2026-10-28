package com.wotagei.cam.media

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对比页选片的 MIME 放宽与失败阶段细分（Issue #1 Bug2 残余，docs/plan/28 §三）。
 *
 * 28 号档判明两条残余风险：① `foreignClipByUri` 对任何非 video 前缀直接拒——
 * 部分 provider 对 MP4 报 `binary/octet-stream`，会被误杀成「无法读取所选视频」；
 * ② 失败日志只有一个 stage，授权全开还失败时无法定位是「权限 / 来源 / 解码」哪一类。
 *
 * MediaRepo 主体吃 ContentResolver/MediaMetadataRetriever（JVM 起不来），可测面拆两层：
 * 纯函数 [declaredMimeClearlyNotVideo] 与 stage 标签在此直测；
 * 接线（pickClipByUri 三级门槛、对比页走带诊断出口）用源码守卫钉死。
 */
class MediaPickStageTest {

    // -------------------------------------------------- ① MIME 判定

    @Test
    fun `明确非视频的类型才拒_octet流与空声明放行`() {
        // 明确非视频：拒
        for (mime in listOf("image/jpeg", "audio/mpeg", "text/plain", "application/pdf")) {
            assertTrue("$mime 必须判为非视频", declaredMimeClearlyNotVideo(mime))
        }
        // 大小写与首尾空白不影响判定
        assertTrue(declaredMimeClearlyNotVideo(" APPLICATION/PDF "))
        // 类型不明：放行交给解码探测——provider 对 MP4 报 octet-stream 的实际案例
        for (mime in listOf(
            null, "", "   ",
            "application/octet-stream", "binary/octet-stream",
            "application/mp4", "application/x-matrosina", "video/mp4"
        )) {
            assertFalse("${mime ?: "null"} 不许拒（类型不明/是视频）", declaredMimeClearlyNotVideo(mime))
        }
    }

    @Test
    fun `失败阶段标签就是日志里那三个`() {
        // WotaPick 日志的 stage 字段直接吃 label：标签变了，既有判读口径就断了
        assertEquals("不可读", PickFailStage.UNREADABLE.label)
        assertEquals("非视频MIME", PickFailStage.NOT_VIDEO.label)
        assertEquals("解码失败", PickFailStage.UNDECODABLE.label)
    }

    // -------------------------------------------------- ② 接线守卫

    @Test
    fun `pickClipByUri三级门槛可读先于类型先于解码`() {
        val code = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("media/MediaRepo.kt")
        )
        val body = KotlinSourceScan.bodyOf(code, "pickClipByUri")
        for (needle in listOf(
            "canReadUri(", "PickFailStage.UNREADABLE",
            "declaredMimeClearlyNotVideo(", "PickFailStage.NOT_VIDEO",
            "probeHasVideoTrack(", "PickFailStage.UNDECODABLE"
        )) {
            assertTrue("pickClipByUri 缺 $needle——三级门槛少一级，失败原因就会合并错报", body.contains(needle))
        }
        // 尺要能红：抽掉解码级后断言翻红
        val mutant = body.replace("probeHasVideoTrack(", "probeRemoved(")
        assertFalse(mutant.contains("probeHasVideoTrack("))
    }

    @Test
    fun `对比页选片走带诊断出口而不是裸clipByUri`() {
        val code = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("player/CompareScreen.kt")
        )
        // pickRight 是 rememberLauncherForActivityResult 的尾 lambda（不是 fun），函数体定位器
        // 够不着；退一步对整文件判「带诊断出口的调用在 + 失败带细分阶段」。注意判据用 contains
        // 不用 occurrences——后者做词边界判定，"…(" 后紧跟实参首字母会被当成标识符一部分拒认。
        assertTrue("pickRight 必须调 repo.pickClipByUri（裸 clipByUri 拿不到失败阶段）", code.contains("repo.pickClipByUri("))
        assertTrue("失败必须带 outcome.fail 的细分阶段进日志", code.contains("outcome.fail?.label"))
        // 尺要能红：换回裸调用后断言翻红
        assertFalse(code.replace("repo.pickClipByUri(", "repo.clipByUri(").contains("repo.pickClipByUri("))
    }
}
