package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出侧「负 PTS 样本丢弃」的**源码结构守卫**（逻辑自检批次：同族缺陷修一处漏一处）。
 *
 * 背景（2026-10-09 真机解盒实证）：外部带 B 帧的开放 GOP 源做剪辑导出，视频轨直写负
 * presentationTimeUs ⇒ Muxer 给整轨 `baseMediaDecodeTime` 加 162000000 个 90k-tick（1800s）
 * 偏移，并写出 `sample_count=0` 的畸形 `ctts`；QQ 内嵌核等不认 `elst` 的播放器表现为无声/音画不同步。
 * 同一根因的第二种表现：WIKO（Android r29，装修复前）中段关键帧起点导出直接失败
 * （`writeSampleData returned an error` / `failed reason=MUX`），对齐首帧起点则成功。
 *
 * 为什么要结构守卫而不止纯函数单测：[ClipRule.keepSample] 的单测只钉规则本身，
 * 把运行时里那两处 `if (keepSample(rel))` 删掉，测试仍然全绿——而缺陷照样复现。
 * 所以这里补一层"计划→行为"的桥：剪辑导出与选轨导出的**视频轨**写入分支必须真的过这条判据
 * （剪辑的音频轨早就判了，视频轨漏判；选轨导出连注释都写着"rel 恒 ≥ 0"）。
 *
 * **每支各钉一条、切片互不遮蔽**：对整个 copy() 体内做 contains 会被同函数的另一支守卫喂绿
 * （实测：删掉视频轨的守卫后整段判定仍然全绿，正是假绿）。视频轨/音频轨各走自己的窄切片，
 * 删掉任一支的守卫，只有对应那条断言变红。
 */
class ExportNegPtsGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    /**
     * 取 copy() 体内视频轨那一支的切片：两个标记都必须在、都得按序，缺一个直接抛——
     * 守卫最怕的是"输入没了还能算通过"。
     */
    private fun videoBranchOf(file: String, from: String, to: String): String {
        val body = KotlinSourceScan.bodyOf(maskedMain(file), "copy")
        val a = body.indexOf(from)
        val b = if (a >= 0) body.indexOf(to, a) else -1
        check(a >= 0 && b > a) {
            "在 $file 的 copy() 里定位视频轨分支失败（from=$from at=$a, to=$to at=$b）：" +
                "分支被改写或挪走了，守卫失去输入，不许算通过"
        }
        return KotlinSourceScan.flatten(body.substring(a, b))
    }

    /**
     * 取 copy() 体内**音频轨那一支**的切片：先按视频支的 [videoFrom]→[videoElse] 定位，
     * 音频支就从那枚 else 标记**之后**起（两个文件里视频支之后的第一枚 else 标记都是
     * `} else {`，取它后面一段才真正落在音频支上），到 [to]（循环末的 `ex.advance()`）为止。
     * 三个标记缺一即抛——守卫最怕的是"输入没了还能算通过"。
     */
    private fun audioBranchOf(file: String, videoFrom: String, videoElse: String, to: String): String {
        val body = KotlinSourceScan.bodyOf(maskedMain(file), "copy")
        val v = body.indexOf(videoFrom)
        val e = if (v >= 0) body.indexOf(videoElse, v) else -1
        check(v >= 0 && e > v) {
            "在 $file 的 copy() 里定位视频轨分支失败（from=$videoFrom at=$v, else=$videoElse at=$e）：" +
                "分支被改写或挪走了，守卫失去输入，不许算通过"
        }
        val a = e + videoElse.length
        val b = body.indexOf(to, a)
        check(b > a) {
            "在 $file 的 copy() 里定位音频轨分支失败（else=$videoElse at=$e, to=$to at=$b）：" +
                "分支被改写或挪走了，守卫失去输入，不许算通过"
        }
        return KotlinSourceScan.flatten(body.substring(a, b))
    }

    @Test
    fun `剪辑导出视频轨写入前判负PTS丢弃`() {
        val branch = videoBranchOf("player/ClipExport.kt", "if (isVideo)", "else if (!audioClosed)")
        assertTrue(
            "ClipExporter.copy 的视频轨写入必须过 ClipRule.keepSample：负 rel 直写会让 Muxer 给整轨加" +
                "巨大 baseMediaDecodeTime 偏移并生成畸形 ctts（与音频轨的守卫同口径）",
            branch.contains("ClipRule.keepSample(rel)")
        )
    }

    @Test
    fun `剪辑导出音频轨写入前判负PTS丢弃`() {
        val branch = audioBranchOf(
            "player/ClipExport.kt", "if (isVideo)", "else if (!audioClosed)", "ex.advance()"
        )
        assertTrue(
            "ClipExporter.copy 的音频轨写入必须过 ClipRule.keepSample，且判据吃平移后的 audioRel：" +
                "AAC 帧界早于视频关键帧时 rel 为负，负 pts 直写会让 Muxer 给整轨加巨大 " +
                "baseMediaDecodeTime 偏移并生成畸形 ctts",
            branch.contains("ClipRule.keepSample(audioRel)")
        )
        assertTrue(
            "内录音轨的平移必须进入写出判据（audioRel = rel + shiftUs），环境轨 shiftUs 恒 0",
            branch.contains("val audioRel = rel + shiftUs")
        )
    }

    @Test
    fun `选轨导出视频轨写入前判负PTS丢弃`() {
        val branch = videoBranchOf(
            "player/TrackExport.kt", "if (ex.sampleTrackIndex == videoTrack)", "else {"
        )
        assertTrue(
            "TrackExporter.copy 的视频轨写入必须过 ClipRule.keepSample：开放 GOP 外部源在入点关键帧" +
                "之后仍有前导 B 帧（rel 为负），\"baseUs 是首样本、rel 恒 ≥0\" 只对 closed GOP 成立",
            branch.contains("ClipRule.keepSample(rel)")
        )
    }

    @Test
    fun `选轨导出音频轨写入前判负PTS丢弃`() {
        val branch = audioBranchOf(
            "player/TrackExport.kt", "if (ex.sampleTrackIndex == videoTrack)", "else {", "ex.advance()"
        )
        assertTrue(
            "TrackExporter.copy 的音频轨写入必须过 TrackRule.shiftedPts（内部委托 ClipRule.keepSample 丢弃负 PTS）：" +
                "选内录轨还要再平移 −offset，前导负样本只会比环境轨更多",
            branch.contains("TrackRule.shiftedPts(")
        )
    }
}