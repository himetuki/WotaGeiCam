package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「state-only 帧不写编码器」的源码级守卫**（docs/plan/25 §3.9.2-1 / §5.4）。
 *
 * 缺陷形状（改前实测可在代码里读出来）：`drawFrame` 早期是 `var skip = false`，只有转换模式
 * （arcConvert ≠ null）的分支才会把 skip 置真。于是 `requestRender()` 触发的"无新帧状态刷新"
 * （切监看、拖斑马阈值/峰值强度、拖曲线）也会执行 `drawEncoderPass()`：同一张 OES 帧被再画一次、
 * 再盖一枚新 PTS，**成片里多一帧重复帧**。监看一旦允许录制中可调（B4），每切一次就多一帧。
 *
 * 修法 = `var skip = !hasFrame`（转换模式的既有判定继续叠加），并且 `drawEncoderPass()` 只许
 * 落在这个闸的块内；同时上屏槽的同步必须让到编码 pass **之后**（首次切监看的链接不许挡 swap）。
 *
 * 判据是源码结构：`hasFrame` 之外没有任何东西能把 state-only 帧放回编码支路——所以必须锁
 * **变量初始化形态**与**调用点所在块**，只写"文件里有 skip"那样的话守不住。
 */
class StateOnlyEncoderGuardTest {

    private val engineSrc by lazy { KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt") }

    /** 遮蔽后的文本：注释与字面量内容不再参与结构判定（行号与原文一致） */
    private val engine by lazy { KotlinSourceScan.codeOnly(engineSrc) }

    /**
     * 从 `{` 起配对到与之匹配的 `}`（遮蔽文本上做，注释/字符串里的括号已不存在）。
     * 返回闭区间；不配平返回 null（宁可报红也不要给半份区间）。
     */
    private fun blockRange(masked: String, openAt: Int): IntRange? {
        if (openAt < 0 || openAt >= masked.length || masked[openAt] != '{') return null
        var depth = 0
        var i = openAt
        while (i < masked.length) {
            when (masked[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return openAt..i
                }
            }
            i++
        }
        return null
    }

    /**
     * `drawFrame` 的完整判据。抽成纯函数是为了让突变自证与真身同吃一把尺。
     *
     * @return 问题清单（空 = 绿）
     */
    private fun frameIssues(masked: String): List<String> {
        val bad = ArrayList<String>()
        val body = runCatching { KotlinSourceScan.bodyOf(masked, "drawFrame") }
            .getOrElse { return listOf("drawFrame 的函数体定位失败（改名/改成表达式体？）：${it.message}") }

        if (!body.contains("var skip = !hasFrame")) {
            bad.add("drawFrame 必须写 `var skip = !hasFrame`——state-only 重画不许进编码支路（会往成片塞重复帧）")
        }
        // 「`skip = false` 一次都不许出现」：它等价于"这一帧一定要编码"，是把重复帧塞回成片的唯一写法
        if (Regex("\\bskip\\s*=\\s*false").containsMatchIn(body)) {
            bad.add("drawFrame 里出现了 `skip = false`——没有新帧的状态刷新会重新进编码支路")
        }
        // 其余 skip 赋值只许待在 if (arcConvert != null) 块内（那是转换账的既有判定，不是第二个放行口）
        val arcAt = body.indexOf("if (arcConvert != null)")
        val arcBlock = if (arcAt < 0) null else blockRange(body, body.indexOf('{', arcAt))
        if (arcAt < 0 || arcBlock == null) {
            bad.add("drawFrame 里找不到 `if (arcConvert != null)` 的块（转换判定被挪走了？）")
        }
        val initAt = body.indexOf("var skip = !hasFrame")
        val initEnd = if (initAt < 0) -1 else initAt + "var skip = !hasFrame".length
        val assigns = Regex("\\bskip\\s*=").findAll(body)
            .map { it.range.first }
            .filter { it > initEnd } // 初始化那一处不算赋值
            .toList()
        if (assigns.isEmpty()) {
            bad.add("drawFrame 里除初始化外一处 skip 赋值都没有——MEND/DROP 的跳过逻辑被删了？")
        }
        for (at in assigns) {
            if (arcBlock == null || at !in arcBlock) {
                bad.add(
                    "skip 赋值不在 `if (arcConvert != null)` 块内（命中位置 $at）：" +
                        "state-only 的放行口又开了一处"
                )
            }
        }
        val calls = KotlinSourceScan.occurrences(body, "drawEncoderPass()")
        if (calls.size != 1) {
            bad.add("drawEncoderPass() 在帧循环里必须恰好一处（实际 ${calls.size} 处）")
            return bad
        }
        val call = calls[0]
        val guard = body.indexOf("if (!skip)")
        if (guard < 0) {
            return bad + "drawFrame 里找不到 `if (!skip)` 闸——编码 pass 的调用点失去唯一入口"
        }
        val block = blockRange(body, body.indexOf('{', guard))
            ?: return bad + "`if (!skip)` 的块不配平（守卫拿不到区间，不许算通过）"
        if (call !in block) {
            bad.add("drawEncoderPass() 必须落在 `if (!skip)` 块内（命中位置 $call，闸区间 ${block.first}..${block.last}）")
        }

        // 顺序：成片槽 → 编码 pass → 上屏槽（§3.9.2-2：display 的链接不许挡在编码 swap 前面）
        val movie = body.indexOf("syncMovieSlot()")
        val display = body.indexOf("syncDisplaySlot()")
        if (movie < 0) bad.add("drawFrame 缺 syncMovieSlot()（编码 pass 前必须先定好成片槽）")
        if (display < 0) bad.add("drawFrame 缺 syncDisplaySlot()")
        if (movie >= 0 && movie > call) bad.add("syncMovieSlot() 必须在 drawEncoderPass() 之前")
        if (display >= 0 && display < call) {
            bad.add("syncDisplaySlot() 必须排在 drawEncoderPass() 之后（首次切监看的链接不许挡在编码 swap 前）")
        }
        return bad
    }

    @Test
    fun `state-only帧不进编码支路且上屏同步让到编码pass之后`() {
        val issues = frameIssues(engine)
        assertEquals("帧循环红线被破了：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `尺子自己能红_skip改回false必报红`() {
        assertEquals("真身必须绿", emptyList<String>(), frameIssues(engine))
        val mutated = engine.replace("var skip = !hasFrame", "var skip = false")
        assertTrue("突变点没找到（drawFrame 里没有 `var skip = !hasFrame`？）", mutated != engine)
        assertTrue(
            "把 `var skip = !hasFrame` 改回 `var skip = false` 必须被守卫抓到",
            frameIssues(mutated).isNotEmpty()
        )
    }

    @Test
    fun `尺子自己能红_初始化之后插skip equals false必报红`() {
        val body = KotlinSourceScan.bodyOf(engine, "drawFrame")
        // 突变：初始化之后补一句 `skip = false`（= 任何没被 arc 分支置真的帧都重新进编码支路）
        val mutatedBody = body.replace("var skip = !hasFrame", "var skip = !hasFrame\n        skip = false")
        val mutated = engine.replace(body, mutatedBody)
        assertTrue("初始化后插 skip = false 的突变必须被守卫抓到", frameIssues(mutated).isNotEmpty())
        // 突变：把 MEND 的置真挪到 arc 块外（等价于删掉那层保护）
        val leakBody = body.replace(
            "if (arcConvert != null) {",
            "skip = true\n        if (arcConvert != null) {"
        )
        assertTrue("arc 块外出现 skip 赋值的突变必须被守卫抓到", frameIssues(engine.replace(body, leakBody)).isNotEmpty())
    }

    @Test
    fun `尺子自己能红_编码调用挪出skip块与调换display顺序都要红`() {
        val body = KotlinSourceScan.bodyOf(engine, "drawFrame")
        // 突变 1：把 drawEncoderPass() 挪到 `if (!skip)` 块外（= 恢复"状态刷新也编码"的旧形态）
        val outsideBody = body.replace("if (!skip) {", "drawEncoderPass()\n        if (!skip) {")
        val outside = engine.replace(body, outsideBody)
        assertTrue("挪出闸的突变必须报红", frameIssues(outside).isNotEmpty())
        // 突变 2：把上屏槽同步挪到编码 pass 之前（= 首次切监看的编译挡 swap）
        val swappedBody = body.replaceFirst("syncMovieSlot()", "syncMovieSlot()\n        syncDisplaySlot()")
        val swapped = engine.replace(body, swappedBody)
        assertTrue("syncDisplaySlot 前置的突变必须报红", frameIssues(swapped).isNotEmpty())
        // 对照：真身的帧循环里上屏槽同步只有一处调用点
        assertEquals(1, KotlinSourceScan.occurrences(body, "syncDisplaySlot()").size)
    }
}
