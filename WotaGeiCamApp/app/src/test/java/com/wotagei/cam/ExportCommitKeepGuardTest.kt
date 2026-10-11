package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4「导出 commit 失败不再删成片」的源码结构守卫。
 *
 * 背景：`muxer.stop()` 已执行 ⇒ 容器里是完整合法 MP4；此前 commit 全败会走 `fail()` 删行，
 * 而 MediaStore 删行连带删文件 ⇒ 好端端的成片被删。修复（对齐录制路 VideoStore.seal 的
 * "只报不删"口径）：commit 立即 2 次 + 延时 1.5s/3s 各 1 次；全败**保留 pending 行不删**、
 * 直接返回 `Fail(COMMIT)`（冷启由 StalePendingSweep.rescuePendingRows 收录）；取消分支按
 * `bytesComplete` 分岔——封轨前取消照删（废段该删），封轨后取消保留。
 *
 * 为什么是窄切片：对整个 export() 体内做"不含 delete/fail"会被 OPEN/MUX/CANCELLED 的
 * 合法删行分支喂假绿（删行在**写一半**的路径上是对的）。所以切片只取 `muxer.stop()` 与
 * `ClipError.COMMIT` 之间的保留分支，剪辑/选轨两条导出链各钉一份。
 *
 * 实现注记：export() 是 `= withContext(...) { … }` 表达式体，[KotlinSourceScan.bodyOf]
 * 解不出花括号体会直接抛——所以切片在**遮蔽后的整文件**上按锚点取：两枚锚点在该文件
 * 的代码（注释/字符串已被遮蔽）里各只出现一次，且 COMMIT 保留分支在 catch 块之前，
 * 切片内容与"export 体切片"逐字等价。
 */
class ExportCommitKeepGuardTest {

    /** 遮蔽 + 折叠空白后的整文件代码文本（锚点定位用） */
    private fun flatCode(rel: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel)))

    /**
     * 取「muxer.stop() → Commit 保留分支」的窄切片（含两端锚点）。
     * 锚点缺失直接抛——守卫最怕的是"输入没了还能算通过"。
     */
    private fun keepSliceOf(code: String, rel: String): String {
        val a = code.indexOf("muxer.stop()")
        check(a >= 0) { "在 $rel 里定位 muxer.stop() 失败：收尾被改写，守卫失去输入，不许算通过" }
        val needle = "ClipResult.Fail(ClipError.COMMIT)"
        val b = code.indexOf(needle, a)
        check(b > a) { "在 $rel 里定位 COMMIT 保留分支失败（守卫失去输入，不许算通过）" }
        return code.substring(a, b + needle.length)
    }

    /** 「封轨后全败保留 + 取消分岔」的判定本体（良品与突变体走同一把尺） */
    private fun keepHolds(code: String, rel: String): Boolean {
        val slice = keepSliceOf(code, rel)
        // 保留分支必须是直接构造 Fail(COMMIT)，且已经过了 bytesComplete 置位点
        if (!slice.contains("ClipResult.Fail(ClipError.COMMIT)")) return false
        if (!slice.contains("bytesComplete = true")) return false
        // 保留分支内不许删行、不许走 fail()（删 pending 行 = MediaStore 连带删掉成片文件）
        if (slice.contains(".delete(") || slice.contains("fail(")) return false
        // 取消分支：封轨前删（fail）是合法路径，但封轨后的保留分支内不许出现 fail(
        val keepAt = code.indexOf("if (bytesComplete)")
        val elseAt = if (keepAt >= 0) code.indexOf("} else", keepAt) else -1
        if (keepAt < 0 || elseAt < 0) return false
        return !code.substring(keepAt, elseAt).contains("fail(")
    }

    @Test
    fun `剪辑导出commit全败保留成片不删`() {
        val code = flatCode("player/ClipExport.kt")
        assertTrue(
            "ClipExporter：muxer.stop 之后 commit 全败必须保留 pending 行（直接构造 Fail(COMMIT)，" +
                "不删行不走 fail()），取消按 bytesComplete 分岔",
            keepHolds(code, "player/ClipExport.kt")
        )
        // 保留成片的档案要完整：救援分支与成功路径共用 sidecar 落盘（切片内须有 writeSidecar 调用）
        assertTrue(
            "剪辑导出的全败保留分支必须照成功路径同款写 sidecar（被救援回的成片档案要完整）",
            keepSliceOf(code, "player/ClipExport.kt").contains("writeSidecar(")
        )
    }

    @Test
    fun `选轨导出commit全败保留成片不删`() {
        val code = flatCode("player/TrackExport.kt")
        assertTrue(
            "TrackExporter：与 ClipExporter 逐字同口径——muxer.stop 之后 commit 全败保留行不删，" +
                "取消按 bytesComplete 分岔",
            keepHolds(code, "player/TrackExport.kt")
        )
    }

    @Test
    fun `突变_剪辑导出改回删除后Fail必红`() {
        val raw = KotlinSourceScan.mainSourceText("player/ClipExport.kt")
        val good = flatCode("player/ClipExport.kt")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", keepHolds(good, "player/ClipExport.kt"))
        // 突变：保留分支改回「删记录后 Fail」（在 return 前插回 fail() 调用；文本级突变不编译，
        // 只验尺子会红）——真偏离：fail() 删 pending 行 = MediaStore 连带删掉成片文件。
        // 保留 return 锚点是为了让切片仍可定位（只把锚点换掉会让守卫以"锚点丢失"红，语义含混）
        val mutant = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(
                raw.replace(
                    Regex.fromLiteral("return@withContext ClipResult.Fail(ClipError.COMMIT)"),
                    "fail(pendingUri, ClipError.COMMIT)\n" +
                        "                        return@withContext ClipResult.Fail(ClipError.COMMIT)"
                )
            )
        )
        val mutantSlice = keepSliceOf(mutant, "player/ClipExport.kt")
        assertTrue("突变体必须真的改了回去（替换不中 = 尺子没咬合）", mutantSlice.contains("fail("))
        assertFalse("改回「删除后 Fail」守卫必须报红", keepHolds(mutant, "player/ClipExport.kt"))
    }

    @Test
    fun `突变_选轨导出改回删除后Fail必红`() {
        val raw = KotlinSourceScan.mainSourceText("player/TrackExport.kt")
        val good = flatCode("player/TrackExport.kt")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", keepHolds(good, "player/TrackExport.kt"))
        val mutant = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(
                raw.replace(
                    Regex.fromLiteral("return@withContext ClipResult.Fail(ClipError.COMMIT)"),
                    "fail(pendingUri, ClipError.COMMIT)\n" +
                        "                        return@withContext ClipResult.Fail(ClipError.COMMIT)"
                )
            )
        )
        val mutantSlice = keepSliceOf(mutant, "player/TrackExport.kt")
        assertTrue("突变体必须真的改了回去（替换不中 = 尺子没咬合）", mutantSlice.contains("fail("))
        assertFalse("改回「删除后 Fail」守卫必须报红", keepHolds(mutant, "player/TrackExport.kt"))
    }
}
