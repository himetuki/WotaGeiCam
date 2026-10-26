package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选片失败「按媒体权限分档」的接线守卫（2026-10-26）。
 *
 * 背景：对比播放右槽选片失败原先只有一句「无法读取所选视频」，而失败原因有五种
 * （回程 data 为 null / parseId 失败 / _ID 兜底失败 / 反查无行 / 行被权限过滤），
 * 且两条失败分支一条日志都没有。最要命的是 Android 14+「选择照片和视频」这类
 * 部分授权用户：启动门与设置页都显示「已授予」，相册里未被选中的视频对本应用
 * 不可见，选片必然失败且无从得知原因。
 *
 * 本守卫钉三件事（都是"删掉就悄悄退化"的接线，不看断言不会红）：
 * 1. 两处选片失败出口（[com.wotagei.cam.ui.CameraScreen] 分屏页 /
 *    [com.wotagei.cam.player.CompareScreen] 对比页）的 `reportPickFailure` 都必须
 *    探测权限、按权限分档、并把非权限原因单独归类；
 * 2. 选片回调必须**先清旧横幅**再判结果——分屏页原先只写不清，一次失败会把
 *    「无法读取所选视频」永久挂住，用户读作"一直失败/进不去选片界面"；
 * 3. 每条断言配突变自证（文本级突变不编译，只验尺子会红）。
 */
class MediaAccessHintWiringGuardTest {

    private fun body(rel: String, funName: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(codeOnly(mainSourceText(rel)), funName))

    /** 接线判据本体：良品与突变体走同一把尺 */
    private fun hintWired(b: String): Boolean =
        b.contains("mediaAccessState(") &&
            b.contains("needsMediaAccessHint(") &&
            b.contains("accessPrompt = access") &&
            b.contains("isMediaStoreVideoUri(")

    @Test
    fun `两处选片失败出口都按媒体权限分档`() {
        for (rel in listOf("ui/CameraScreen.kt", "player/CompareScreen.kt")) {
            assertTrue("$rel：reportPickFailure 的权限分档接线被拆了", hintWired(body(rel, "reportPickFailure")))
        }
    }

    @Test
    fun `突变_把权限判定短路后守卫必红`() {
        val good = body("player/CompareScreen.kt", "reportPickFailure")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", hintWired(good))
        val mutant = good.replace("needsMediaAccessHint(", "false || (")
        assertFalse(
            "把 needsMediaAccessHint 短路成恒假后守卫必须报红（否则部分授权用户重新退回含糊文案）",
            hintWired(mutant)
        )
    }

    @Test
    fun `选片回程必须先清旧横幅再处理结果`() {
        val cam = KotlinSourceScan.flatten(codeOnly(mainSourceText("ui/CameraScreen.kt")))
        val start = cam.indexOf("val picker = rememberLauncherForActivityResult")
        assertTrue("锚点丢失：没找到分屏 picker 回调", start >= 0)
        val clear = cam.indexOf("banner = null", start)
        val firstFailure = cam.indexOf("reportPickFailure(", start)
        assertTrue("分屏 picker 回调后没截到失败出口", firstFailure > start)
        assertTrue(
            "分屏选片回调必须在任何失败出口之前清旧横幅（只写不清 = 一次失败永久提示）",
            clear in (start + 1) until firstFailure
        )
    }
}
