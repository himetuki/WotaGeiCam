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
 * **只截视频轨那一支**判：整个 copy() 体内还有音频轨的同口径守卫，对全体做 contains 会被它喂绿
 * （实测：删掉视频轨的守卫后本守卫仍然全绿，正是假绿）。判据走 `codeOnly` 遮蔽，注释喂不绿。
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
}