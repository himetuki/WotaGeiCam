package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #6「选轨导出携带 sidecar」的源码结构守卫。
 *
 * 口径：选轨导出是**同轨全片 remux**（不裁帧、输出 0 点 = 视频首样本，对位次无影响）⇒
 * 源片 `.drops.json` **逐字直写**成片同名 sidecar，[com.wotagei.cam.player.ClipRule.remapDrops]
 * 不适用——体内出现 remapDrops 即口径错位（剪辑导出才有关键帧位次平移；shift=0 的重映射
 * 是语义空操作，也不许拿它当"写了"的对照）。commit 成功与"全败保留"两条路径都要写
 * （与 ClipExporter 同一套纪律）。
 */
class TrackSidecarGuardTest {

    private fun flatCode(rel: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel)))

    @Test
    fun `选轨导出的sidecar直写不带remap`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/TrackExport.kt")),
                "writeSidecar"
            )
        )
        assertTrue(
            "TrackExporter.writeSidecar 必须读源档案（ArcDropLog.readFrom）",
            body.contains("ArcDropLog.readFrom(")
        )
        assertTrue(
            "TrackExporter.writeSidecar 必须直写目标 sidecar（ArcDropLog.writeTo）",
            body.contains("ArcDropLog.writeTo(")
        )
        assertFalse(
            "选轨是全片 remux、无位次平移：writeSidecar 体内出现 remapDrops 即口径错位（那是剪辑导出的关键帧平移）",
            body.contains("remapDrops")
        )
    }

    @Test
    fun `成功与全败保留两条路径都写sidecar`() {
        val code = flatCode("player/TrackExport.kt")
        // 定义是 `writeSidecar(src: VideoClip, ...)`，调用是 `writeSidecar(src, pendingUri)`——
        // 按调用形态计数：成功路径 + 全败保留分支，共 2 处
        assertEquals(
            "TrackExporter 必须恰好在「commit 成功」与「全败保留」两条路径各写一次 sidecar（漏掉保留分支 = 被救援回的成片丢档案）",
            2, KotlinSourceScan.occurrences(code, "writeSidecar(src, pendingUri)").size
        )
        // 保留分支内的那次：最后一个 if (!committed) 分支到 COMMIT 返回之间必须有 writeSidecar
        val branch = code.lastIndexOf("if (!committed)")
        val commitFail = code.indexOf("ClipResult.Fail(ClipError.COMMIT)", branch)
        assertTrue("定位 COMMIT 保留分支失败（守卫失去输入，不许算通过）", branch >= 0 && commitFail > branch)
        assertTrue(
            "全败保留分支必须照成功路径同款写 sidecar（被救援回的成片档案要完整）",
            code.substring(branch, commitFail).contains("writeSidecar(src, pendingUri)")
        )
    }

    @Test
    fun `突变_删writeTo调用必红`() {
        val raw = KotlinSourceScan.mainSourceText("player/TrackExport.kt")
        val good = flatCode("player/TrackExport.kt")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", good.contains("ArcDropLog.writeTo("))
        // 突变：直写调用整个删掉（文本级突变不编译，只验尺子会红）。
        // ⚠ 不得拿 remapDrops(srcLog, 0) 当突变对照——shift=0 是语义空操作，测不出任何退化
        val mutant = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(
                raw.replace(
                    Regex.fromLiteral("val ok = ArcDropLog.writeTo(outPath, srcLog)"),
                    "val ok = false"
                )
            )
        )
        val mutantBody = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(mutant, "writeSidecar")
        )
        assertFalse("删掉 writeTo 后守卫必须报红", mutantBody.contains("ArcDropLog.writeTo("))
    }
}
