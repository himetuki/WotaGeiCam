package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #8「B 帧源导出单调兜底」的源码结构守卫。
 *
 * 背景：全仓 muxer 写入路径里只有录制链（CodecRecorder.writeSample）有逐轨单调桥；
 * 两条导出链（剪辑/选轨）此前零处理——B 帧源的 PTS 在界内界外来回跳（ClipExport.kt 头注
 * 自认），同轨倒退会让 `MediaMuxer.writeSampleData` 抛 IllegalStateException 让导出当场结束。
 * 修复 = 写出前过 [com.wotagei.cam.record.monotonicPts] 桥（判据仍吃 raw 值，只改写出的戳），
 * 两轨独立账本，收尾 Log.i 汇总（逐样本明细只落 d 档，不重蹈录制链的 Log.w 洪泛）。
 *
 * **每条写入支各钉一条、切片互不遮蔽**（复用 ExportNegPtsGuardTest 的标记切片技法，
 * 防跨支假绿）：视频/音频各走自己的窄切片，删掉任一支的桥，只有对应那条断言变红。
 * `monotonicPts` 纯函数表已有 MuxPtsMonotonicTest 全量覆盖，这里只钉接线，不新增定义式重复行。
 */
class ExportMuxMonotonicGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    /** copy() 体内视频轨那一支的窄切片（标记缺失直接抛，不许"输入没了还通过"） */
    private fun videoBranchOf(masked: String, from: String, to: String): String {
        val body = KotlinSourceScan.bodyOf(masked, "copy")
        val a = body.indexOf(from)
        val b = if (a >= 0) body.indexOf(to, a) else -1
        check(a >= 0 && b > a) {
            "在 copy() 里定位视频轨分支失败（from=$from at=$a, to=$to at=$b）：分支被改写或挪走了，守卫失去输入"
        }
        return KotlinSourceScan.flatten(body.substring(a, b))
    }

    /** copy() 体内音频轨那一支的窄切片：从视频支的 else 标记之后到循环末 ex.advance() */
    private fun audioBranchOf(masked: String, videoFrom: String, videoElse: String, to: String): String {
        val body = KotlinSourceScan.bodyOf(masked, "copy")
        val v = body.indexOf(videoFrom)
        val e = if (v >= 0) body.indexOf(videoElse, v) else -1
        check(v >= 0 && e > v) {
            "在 copy() 里定位视频轨分支失败（from=$videoFrom at=$v, else=$videoElse at=$e）：分支被改写或挪走了，守卫失去输入"
        }
        val a = e + videoElse.length
        val b = body.indexOf(to, a)
        check(b > a) {
            "在 copy() 里定位音频轨分支失败（else=$videoElse at=$e, to=$to at=$b）：分支被改写或挪走了，守卫失去输入"
        }
        return KotlinSourceScan.flatten(body.substring(a, b))
    }

    /** 单条写入支的判定：过桥 + lastPts 回写 + 写出的戳是桥给出的值 */
    private fun branchHolds(branch: String, lastPtsWriteback: String): Boolean =
        branch.contains("monotonicPts(") &&
            branch.contains(lastPtsWriteback) &&
            branch.contains("info.set(0, size.toInt(), fix.ptsUs, ex.sampleFlags)")

    @Test
    fun `剪辑导出视频轨写前过单调桥`() {
        val branch = videoBranchOf(
            maskedMain("player/ClipExport.kt"), "if (isVideo)", "else if (!audioClosed)"
        )
        assertTrue(
            "ClipExporter.copy 的视频轨写入必须过 monotonicPts 桥（B 帧源同轨倒退会让 Muxer 抛异常结束导出）",
            branchHolds(branch, "vLastPts = fix.ptsUs")
        )
    }

    @Test
    fun `剪辑导出音频轨写前过单调桥`() {
        val branch = audioBranchOf(
            maskedMain("player/ClipExport.kt"), "if (isVideo)", "else if (!audioClosed)", "ex.advance()"
        )
        assertTrue(
            "ClipExporter.copy 的音频轨写入必须过 monotonicPts 桥（两轨独立账本）",
            branchHolds(branch, "aLastPts = fix.ptsUs")
        )
    }

    @Test
    fun `选轨导出视频轨写前过单调桥`() {
        val branch = videoBranchOf(
            maskedMain("player/TrackExport.kt"), "if (ex.sampleTrackIndex == videoTrack)", "else {"
        )
        assertTrue(
            "TrackExporter.copy 的视频轨写入必须过 monotonicPts 桥",
            branchHolds(branch, "vLastPts = fix.ptsUs")
        )
    }

    @Test
    fun `选轨导出音频轨写前过单调桥`() {
        val branch = audioBranchOf(
            maskedMain("player/TrackExport.kt"), "if (ex.sampleTrackIndex == videoTrack)", "else {", "ex.advance()"
        )
        assertTrue(
            "TrackExporter.copy 的音频轨写入必须过 monotonicPts 桥（判据吃 shiftedPts 的 raw 值，戳过桥）",
            branchHolds(branch, "aLastPts = fix.ptsUs")
        )
    }

    @Test
    fun `两条导出链收尾都有修正汇总与maxBack账`() {
        for (rel in listOf("player/ClipExport.kt", "player/TrackExport.kt")) {
            val body = KotlinSourceScan.flatten(maskedMain(rel).let { KotlinSourceScan.bodyOf(it, "copy") })
            assertTrue(
                "$rel 的 copy() 收尾必须带两轨修正计数（不逐样本刷就得有汇总出口）",
                body.contains("vPtsFixes") && body.contains("aPtsFixes")
            )
            assertTrue(
                "$rel 的 copy() 收尾必须带最大倒退量账（微秒级修正也要能量出量级）",
                body.contains("vPtsMaxBackUs") && body.contains("aPtsMaxBackUs")
            )
        }
    }

    @Test
    fun `突变_删剪辑视频轨lastPts回写必红`() {
        val raw = KotlinSourceScan.mainSourceText("player/ClipExport.kt")
        val good = videoBranchOf(
            maskedMain("player/ClipExport.kt"), "if (isVideo)", "else if (!audioClosed)"
        )
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", branchHolds(good, "vLastPts = fix.ptsUs"))
        // 突变：视频轨不回写 lastPts（桥形同虚设，下一枚样本又从 -1 从头判定；文本级突变不编译，只验尺子会红）
        val mutant = KotlinSourceScan.codeOnly(
            raw.replace(Regex.fromLiteral("vLastPts = fix.ptsUs"), "vLastPts = rel")
        )
        val branch = videoBranchOf(mutant, "if (isVideo)", "else if (!audioClosed)")
        assertFalse("删 lastPts 回写后守卫必须报红", branchHolds(branch, "vLastPts = fix.ptsUs"))
    }

    @Test
    fun `突变_删选轨音频轨lastPts回写必红`() {
        val raw = KotlinSourceScan.mainSourceText("player/TrackExport.kt")
        val good = audioBranchOf(
            maskedMain("player/TrackExport.kt"), "if (ex.sampleTrackIndex == videoTrack)", "else {", "ex.advance()"
        )
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", branchHolds(good, "aLastPts = fix.ptsUs"))
        val mutant = KotlinSourceScan.codeOnly(
            raw.replace(Regex.fromLiteral("aLastPts = fix.ptsUs"), "aLastPts = outPts")
        )
        val branch = audioBranchOf(mutant, "if (ex.sampleTrackIndex == videoTrack)", "else {", "ex.advance()")
        assertFalse("删 lastPts 回写后守卫必须报红", branchHolds(branch, "aLastPts = fix.ptsUs"))
    }
}
