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
 * `ClipError.COMMIT` 之间的保留分支，剪辑/选轨两条导出链各钉一份；catch(Exception) 块另用
 * 独立窄切片钉「封轨后异常也按 bytesComplete 分岔」（r32 终审 P2：该块若恒走 fail()，将来
 * 任何人在 muxer.stop 之后新增一个会抛的调用，好片就会被静默删掉）。
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

    /**
     * 取「catch (e: Exception) → private fun copy(」的窄切片（含左锚，不含右锚）。
     * `catch (e: Exception)` 在两文件里各只有一个（export 收尾兜底；cancel 的 catch 类型名不同），
     * 以它为左锚可唯一定位；右锚选 export 之后第一个函数声明，窗口恰含 catch 块与 export 收尾，
     * 不含文件后段的 fail() 定义（那会让「true 支不含 fail(」喂假红）。
     */
    private fun catchSliceOf(code: String, rel: String): String {
        val c = code.indexOf("catch (e: Exception)")
        check(c >= 0) { "在 $rel 里定位 catch (e: Exception) 失败：收尾被改写，守卫失去输入，不许算通过" }
        val next = code.indexOf("private fun copy(", c)
        check(next > c) { "在 $rel 里定位 catch 块右界失败（守卫失去输入，不许算通过）" }
        return code.substring(c, next)
    }

    /** 「catch 也分岔：封轨后异常保留（Fail(COMMIT) 与全败同口径）、封轨前照删」的判定本体 */
    private fun catchBranchHolds(code: String, rel: String): Boolean {
        val slice = catchSliceOf(code, rel)
        val branchAt = slice.indexOf("if (bytesComplete)")
        // 分岔必须存在：摘掉 = 封轨后异常恒走 fail() 删行 = 好片被静默丢（本守卫要钉死的退化）
        if (branchAt < 0) return false
        val commitAt = slice.indexOf("ClipError.COMMIT)", branchAt)
        // true 支必须与 commit 全败同口径：直接构造 Fail(COMMIT) 保留，不许删行、不许走 fail()
        if (commitAt < 0) return false
        val keep = slice.substring(branchAt, commitAt)
        return !keep.contains(".delete(") && !keep.contains("fail(")
    }

    /**
     * 突变体构造：把 catch 块的 bytesComplete 分岔摘掉（封轨后异常也恒走 fail() 删行）。
     * 锚点以 catch 首行到 `} else {` 的原文逐字定位（该段含 catch 独有的日志锚，全文件唯一），
     * 替换不中直接抛——突变测试最怕"替换没咬合还当自己红了"。文本级突变不编译，只验尺子会红。
     */
    private fun catchBranchStripped(raw: String): String {
        val anchor = "        } catch (e: Exception) {\n" +
            "            if (bytesComplete) {\n" +
            "                // 封轨后异常：成片字节已在盘上，删行 = 丢成片；与 commit 全败同口径保留待救援\n" +
            "                Log.e(TAG, \"封轨后异常，成片已保留待救援 uri=\$pendingUri: \${e.message}\")\n" +
            "                return@withContext ClipResult.Fail(ClipError.COMMIT)\n" +
            "            } else {\n"
        val idx = raw.indexOf(anchor)
        check(idx >= 0) { "突变锚点没咬合（catch 分岔原文被改写），突变测试失去输入" }
        // 只摘 true 支与 else 关键字：剩余的 else 体原样变成 catch 的直落体 = 恒走 fail()
        return raw.replaceRange(idx, idx + anchor.length, "        } catch (e: Exception) {\n")
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
        // r32 终审 P2：catch(Exception) 也必须按 bytesComplete 分岔——封轨后异常保留不删
        assertTrue(
            "ClipExporter：catch(Exception) 内必须按 bytesComplete 分岔，封轨后异常保留（Fail(COMMIT)）" +
                "而非恒走 fail() 删行",
            catchBranchHolds(code, "player/ClipExport.kt")
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
        // r32 终审 P2：catch(Exception) 分岔，与剪辑链逐字同口径
        assertTrue(
            "TrackExporter：catch(Exception) 内必须按 bytesComplete 分岔，封轨后异常保留（Fail(COMMIT)）" +
                "而非恒走 fail() 删行",
            catchBranchHolds(code, "player/TrackExport.kt")
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

    @Test
    fun `突变_剪辑导出catch摘掉封轨后分岔必红`() {
        val raw = KotlinSourceScan.mainSourceText("player/ClipExport.kt")
        val good = flatCode("player/ClipExport.kt")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", catchBranchHolds(good, "player/ClipExport.kt"))
        // 突变：catch 块摘掉 bytesComplete 分岔（封轨后异常也恒走 fail() 删行）——将来任何人在
        // muxer.stop 之后新增一个会抛的调用，好片就会被静默删掉，这正是本守卫要钉死的退化
        val mutant = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(catchBranchStripped(raw))
        )
        assertFalse("catch 摘掉封轨后分岔（恒删行）守卫必须报红", catchBranchHolds(mutant, "player/ClipExport.kt"))
    }

    @Test
    fun `突变_选轨导出catch摘掉封轨后分岔必红`() {
        val raw = KotlinSourceScan.mainSourceText("player/TrackExport.kt")
        val good = flatCode("player/TrackExport.kt")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", catchBranchHolds(good, "player/TrackExport.kt"))
        val mutant = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(catchBranchStripped(raw))
        )
        assertFalse("catch 摘掉封轨后分岔（恒删行）守卫必须报红", catchBranchHolds(mutant, "player/TrackExport.kt"))
    }
}
