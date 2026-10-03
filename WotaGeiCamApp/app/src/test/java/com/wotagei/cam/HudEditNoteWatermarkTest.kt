package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提示语成为编辑页的背景」的守卫（2026-10-02，用户原话：编辑控件页面内，让提示语成为
 * 编辑页的背景，不要挤占控件画面空间）。
 *
 * 背景（为什么只能用源码扫描）：这次改的东西九成是 Compose 的 draw 阶段与**布局高度**，
 * JVM 单测定不了布局（无 Robolectric），可证伪的只有源码结构。真机收益的数字见
 * docs/plan/16「新发现的缺陷」1 与 `.tmp/forensics/r3_dock_clip.md`：改前说明行 3 行高
 * 112px + 它那档行间距 8px，把编辑页顶部避让推到 202px（录制页 88px），右 Dock 可用带
 * 从 538px 掉到 424px、内容 526px 被裁 102px（对焦矮 13px、防抖整颗跌出带外）。
 *
 * 本类钉六件事（每条都能单独红，改坏了它会怎么红写在各自 KDoc 上）：
 * 1. 操作栏 Column 里**不许**再出现三份提示资源（说明行回填 = 那 120px 又回来了）；
 * 2. 水印层必须是 `matchParentSize()` + `drawBehind`/`drawWithContent`，且**没有**
 *    pointerInput（背景层吃事件 = 改掉全屏捕获层的命中区分派）；
 * 3. measure 与 layout 结果必须被 `remember` 包住，draw 里不许 `.measure(`（每帧分配），
 *    且颜色/字阶逐字复用旧说明行那一档（contrast-audit 的口径，不新造淡色）；
 * 4. 三份提示资源仍被引用（不许删完资源没人用），水印宽度仍是 (0,1) 的一档比例；
 * 5. 捕获层 `onDragStart`/`onDrag`/`onDragEnd` 那段**逐字未改**（防"顺手改命中"）；
 * 6. 水印排在可放范围底板与网格底纹之上、五枚容器之下（Box 的书写顺序就是绘制顺序）。
 */
class HudEditNoteWatermarkTest {

    /** 三份"给用户看的说明/提示"资源：本任务的主角，一个都不许删 */
    private val noteRes = listOf(
        "hud_edit_note",
        "hud_edit_page_cell_size",
        "hud_edit_hidden_entries_note"
    )

    /**
     * 捕获层命中分派的**逐字段尺子**（在 [KotlinSourceScan.flatten] 之后的文本上比对，
     * 所以不受换行缩进影响；注释已被 codeOnly 抹平，注释里提到这些字段不算过关）。
     * 改坏任何一段（换命中判据、漏 consume、松手改派发） ⇒ 对应那条找不到 ⇒ 本类红。
     */
    private val dragNeedles = listOf(
        "onDragStart = { pos -> dragStart = pos dragPointer = pos dragShift = Offset.Zero",
        "val hit = entryAtPointer(pos.x.roundToInt(), pos.y.roundToInt())",
        "dragPointer = change.position dragShift += amount change.consume()",
        "if (dragEntry != null) dropEntry() else dragZone?.let { dropContainer(it) }"
    )

    // ------------------------------------------------------------------ 源码定位小工具

    private fun rawSrc(): String = KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt")

    /** 编辑页那个 @Composable 的函数体（遮蔽注释与字符串后的真代码） */
    private fun body(): String =
        KotlinSourceScan.bodyOf(codeOnly(rawSrc()), "HudLayoutEditorScreen")

    /** 某个 `(` 的配对 `)` 下标（遮蔽文本上数：串与注释里的括号已抹平，计数是安全的） */
    private fun closeParenAt(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** 某个 `{` 的配对 `}` 下标 */
    private fun closeBraceAt(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /**
     * 取某次 `keyword(...)` 调用的**完整区间**：圆括号参数表 + 紧随其后的尾随 lambda。
     * 尾随 lambda 必须连起来看：Compose 的 `Column(args) { 子节点 }` 把内容放在 lambda 里，
     * 只取参数表就看不到那颗 `Text` —— 那正是本任务要判的那东西。
     * `)` 与 `{` 之间只许空白；夹了别的一律不收（不收半份文本，宁可当没有）。
     */
    private fun callRegions(text: String, keyword: String): List<Pair<Int, String>> =
        Regex("\\b" + Regex.escape(keyword) + "\\s*\\(").findAll(text).mapNotNull { m ->
            val open = m.range.last
            val paren = closeParenAt(text, open) ?: return@mapNotNull null
            var i = paren + 1
            while (i < text.length && text[i].isWhitespace()) i++
            val end = if (i < text.length && text[i] == '{') {
                closeBraceAt(text, i) ?: return@mapNotNull null
            } else {
                paren
            }
            m.range.first to text.substring(m.range.first, end + 1)
        }.toList()

    /** 含 [marker] 的那一层调用：嵌套时取**最里层**（起点最大那次；外层 Box 的尾随 lambda 会连它一并包含） */
    private fun innermostOf(text: String, keyword: String, marker: String): String? =
        callRegions(text, keyword).filter { it.second.contains(marker) }.maxByOrNull { it.first }?.second

    /** 所有 `remember(...) { ... }` 的整段文本（参数表 + lambda 体；没有尾随 lambda 的那次调用不收） */
    private fun rememberBlocks(text: String): List<String> =
        callRegions(text, "remember").map { it.second }.filter { it.contains('{') }

    // ------------------------------------------------------------------ 六个 Topic：各自返回违规清单

    /**
     * Topic 1：常驻提示语不许回到操作栏。
     * 改坏了它会怎么红：把 `Text(stringResource(R.string.hud_edit_note)…)`（或那两份 P1 读数/提示）
     * 重新排进操作栏 Column ⇒ 红。那正是本任务要消掉的 120px（说明行 112px + 行间距 8px）。
     */
    private fun topicNoteOutOfActionBar(body: String): List<String> {
        val bad = ArrayList<String>()
        val bar = callRegions(body, "Column").map { it.second }
            .singleOrNull { it.contains("hud_edit_back") }
        if (bar == null) {
            bad.add("找不到操作栏那条 Column（含 hud_edit_back）——守卫失去输入，不许算通过")
            return bad
        }
        for (res in noteRes) {
            if (bar.contains(res)) {
                bad.add("操作栏 Column 里又出现 $res（常驻说明行回填：右 Dock 可用带会被重新压掉 120px）")
            }
        }
        return bad
    }

    /**
     * Topic 2：水印层是纯 draw、不吃事件、不参与兄弟测量、不在 draw 里量文字。
     * 改坏了它会怎么红：① 挂上 pointerInput（想吃事件）⇒ 红，命中区分派会被改；
     * ② 去掉 matchParentSize 改用 weight/height 之类 ⇒ 红，会重新挤控件画面；
     * ③ 把 measure 挪进 draw lambda ⇒ 红，每帧分配；④ 整层删掉/改名 ⇒ 红。
     */
    private fun topicWatermarkIsPureDraw(body: String): List<String> {
        val bad = ArrayList<String>()
        val mark = innermostOf(body, "Box", "drawText(")
        if (mark == null) {
            bad.add("找不到水印层（drawText 所在的那层 Box 被删/改名）")
            return bad
        }
        if (!mark.contains("drawBehind") && !mark.contains("drawWithContent")) {
            bad.add("水印层不是 draw 阶段（既不是 drawBehind 也不是 drawWithContent）")
        }
        if (mark.contains("pointerInput")) {
            bad.add("水印层挂了 pointerInput——背景层不许吃事件（会改掉全屏捕获层的命中区分派）")
        }
        if (!mark.contains("matchParentSize")) {
            bad.add("水印层没有 matchParentSize（会参与兄弟测量，重新挤控件画面）")
        }
        if (mark.contains(".measure(")) {
            bad.add("水印层在 draw 里现量文字（.measure( 每帧分配；measure 必须在组合期 remember）")
        }
        return bad
    }

    /**
     * Topic 3：measure 与 layout 结果被 remember 钉住 + 颜色字阶复用旧说明行那一档。
     * 改坏了它会怎么红：① 去掉 remember ⇒ 红；② 颜色换成 textHi/textMid 或任何新造 alpha ⇒ 红
     * （绕过 contrast-audit 的审计口径）；③ 出现第二处 .measure( ⇒ 红。
     */
    private fun topicMeasureIsRemembered(body: String): List<String> {
        val bad = ArrayList<String>()
        val block = rememberBlocks(body).firstOrNull { it.contains(".measure(") }
        if (block == null) {
            bad.add("量文字那一步没有被 remember 包住（每次组合都重量：既违反每帧零分配纪律，也白算）")
        } else {
            for (token in listOf("WotaType.caption", "WotaColor.textLo", "Constraints(")) {
                if (!block.contains(token)) {
                    bad.add("水印 measure 里少了 $token——颜色/字阶必须逐字复用旧说明行那一档（contrast-audit 的审计口径，不许新造淡色）")
                }
            }
        }
        // 注意：尺子必须用词边界完整的针（occurrences 是词边界匹配，".measure(" 前面那个
        // `r`（noteMeasurer 的尾字母）会被判成"词的一部分"而匹不到）——用调用本体当针。
        val n = KotlinSourceScan.occurrences(body, "noteMeasurer.measure(").size
        if (n != 1) {
            bad.add("noteMeasurer.measure( 在编辑页出现 $n 次（多于一次就有第二处量文字，每帧零分配守不住）")
        }
        return bad
    }

    /**
     * Topic 4：三份资源仍被引用（水印层没被删成孤儿）+ 宽度比例仍是 (0,1) 的一档。
     * 改坏了它会怎么红：① 以为"搬进水印=资源没用了"删掉任一资源 ⇒ 红；
     * ② 宽度改成 1.0f（整幅安全区宽）⇒ 红，默认布局下字会钻到两枚 Dock 卡片底下；
     * ③ 删掉比例常量改用写死 dp ⇒ 红（那是按方向写死避让的同族错误）。
     */
    private fun topicResourcesAndWidthFraction(src: String, body: String): List<String> {
        val bad = ArrayList<String>()
        for (res in noteRes) {
            if (!body.contains(res)) {
                bad.add("$res 在编辑页已无引用（水印层被删/资源变孤儿）")
            }
        }
        val frac = Regex("NOTE_WATERMARK_WIDTH_FRACTION\\s*=\\s*([0-9]*\\.?[0-9]+)f").find(src)
        if (frac == null) {
            bad.add("NOTE_WATERMARK_WIDTH_FRACTION 常量不在场（水印宽度退化成什么在统治？）")
        } else {
            val v = frac.groupValues[1].toFloat()
            if (v <= 0f || v >= 1f) {
                bad.add("水印宽度比例 = $v 不在 (0,1)：取 1 会整幅铺开、钻到 Dock 卡片底下去（卡片 74.9% 不透明，压上就是糊字）")
            }
        }
        if (!body.contains("NOTE_WATERMARK_WIDTH_FRACTION")) {
            bad.add("水印层没有读 NOTE_WATERMARK_WIDTH_FRACTION（比例常量成了摆设）")
        }
        return bad
    }

    /**
     * Topic 5：捕获层命中分派逐字未改。
     * 改坏了它会怎么红：动 onDragStart 的命中裁决、动 change.consume()、动松手派发
     * （dropEntry / dropContainer）任一段 ⇒ 对应逐字段尺子找不到 ⇒ 红。
     */
    private fun topicCaptureLayerVerbatim(body: String): List<String> {
        val bad = ArrayList<String>()
        val capture = innermostOf(body, "Box", "detectDragGestures")
        if (capture == null) {
            bad.add("找不到捕获层（含 detectDragGestures 的那层 Box）——守卫失去输入，不许算通过")
            return bad
        }
        if (KotlinSourceScan.occurrences(capture, "pointerInput(Unit)").size != 1) {
            bad.add("捕获层的 pointerInput(Unit) 不在场或不止一处（拖拽捕获层被动了）")
        }
        val flat = KotlinSourceScan.flatten(capture)
        for (needle in dragNeedles) {
            if (!flat.contains(needle)) {
                bad.add("捕获层被改动过：找不到逐字段「$needle」（命中分派/松手派发一字不许改）")
            }
        }
        return bad
    }

    /**
     * Topic 6：z 序 = 书写顺序：可放范围底板 → 网格底纹 → 水印 → 五枚容器。
     * 改坏了它会怎么红：把水印 Box 挪到五枚容器之后（或挪到 wotaCard 底板之下）⇒ 红。
     * 挪到底板之下最阴——那层底板 74.9% 不透明，水印会整片消失且不报错。
     */
    private fun topicWatermarkUnderContainers(body: String): List<String> {
        val bad = ArrayList<String>()
        val plateAt = body.indexOf("wotaCard(")
        val gridAt = body.indexOf("pageGridBoxOf")
        val markAt = body.indexOf("drawText(")
        val zoneAt = body.indexOf("HudZoneBox(")
        if (plateAt < 0 || gridAt < 0 || markAt < 0 || zoneAt < 0) {
            bad.add("z 序判据缺原料（wotaCard( / pageGridBoxOf / drawText( / HudZoneBox( 之一不在场）")
            return bad
        }
        if (!(plateAt < gridAt && gridAt < markAt && markAt < zoneAt)) {
            bad.add(
                "水印层排错了：必须在可放范围底板与网格底纹之上、五枚容器之下" +
                    "（plate=$plateAt grid=$gridAt mark=$markAt zone=$zoneAt）"
            )
        }
        return bad
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun `常驻提示语已搬进水印层_操作栏Column里不再有说明行`() {
        val bad = topicNoteOutOfActionBar(body())
        assertEquals(
            "操作栏里又出现常驻提示语：\n${bad.joinToString("\n")}",
            0, bad.size
        )
    }

    @Test
    fun `水印层是纯draw的matchParentSize层_不吃事件也不在draw里量文字`() {
        val bad = topicWatermarkIsPureDraw(body())
        assertEquals(
            "水印层的四条硬约束有断点：\n${bad.joinToString("\n")}",
            0, bad.size
        )
    }

    @Test
    fun `量文字的结果被remember钉住_颜色字阶复用旧说明行那一档`() {
        val bad = topicMeasureIsRemembered(body())
        assertEquals(
            "水印的 measure/layout 没有钉住，或颜色字阶换了档：\n${bad.joinToString("\n")}",
            0, bad.size
        )
    }

    @Test
    fun `三份提示资源仍被引用_水印宽度仍是小于一的一档比例`() {
        val src = rawSrc()
        val bad = topicResourcesAndWidthFraction(src, body())
        assertEquals(
            "资源引用或宽度比例出了问题：\n${bad.joinToString("\n")}",
            0, bad.size
        )
    }

    @Test
    fun `捕获层的命中分派逐字未改`() {
        val bad = topicCaptureLayerVerbatim(body())
        assertEquals(
            "捕获层被改动过：\n${bad.joinToString("\n")}",
            0, bad.size
        )
    }

    @Test
    fun `水印排在底板与网格之上_五枚容器之下`() {
        val bad = topicWatermarkUnderContainers(body())
        assertEquals(
            "水印层 z 序不对：\n${bad.joinToString("\n")}",
            0, bad.size
        )
    }

    // ------------------------------------------------------------------ 尺子自己能红

    @Test
    fun `尺子自己能红`() {
        val src = rawSrc()
        val body = body()
        // 良品必须先真的绿，否则后面七个红没有意义
        val allBad = topicNoteOutOfActionBar(body) + topicWatermarkIsPureDraw(body) +
            topicMeasureIsRemembered(body) + topicResourcesAndWidthFraction(src, body) +
            topicCaptureLayerVerbatim(body) + topicWatermarkUnderContainers(body)
        assertEquals("良品必须先真的绿：\n${allBad.joinToString("\n")}", 0, allBad.size)

        // ① 说明行回填（操作栏里重新排一条提示 Text）⇒ Topic 1 红
        val backInBar = body.replaceFirst(
            "hint?.let {",
            "Text(text = stringResource(R.string.hud_edit_note)) hint?.let {"
        )
        assertTrue("变异①必须先真的打上去", backInBar != body)
        assertTrue(
            "说明行回填必须红",
            topicNoteOutOfActionBar(backInBar).any { it.contains("hud_edit_note") }
        )

        // ② 水印层挂上 pointerInput（想吃事件）⇒ Topic 2 红
        val eatsEvents = body.replaceFirst(".drawBehind {", ".pointerInput(Unit) {")
        assertTrue("变异②必须先真的打上去", eatsEvents != body)
        assertTrue(
            "背景层吃事件必须红",
            topicWatermarkIsPureDraw(eatsEvents).any { it.contains("pointerInput") }
        )

        // ③ 水印层在 draw 里现量文字 ⇒ Topic 2 红
        val measuresInDraw = body.replaceFirst(
            "val noteW = noteLayout.size.width",
            "val noteW = noteMeasurer.measure(text = noteText).size.width"
        )
        assertTrue("变异③必须先真的打上去", measuresInDraw != body)
        assertTrue(
            "draw 里现量文字必须红",
            topicWatermarkIsPureDraw(measuresInDraw).any { it.contains(".measure(") }
        )

        // ④ 去掉 remember（量文字退回每组合重量）⇒ Topic 3 红
        val forgetsRemember = body.replaceFirst("remember(noteMeasurer", "later(noteMeasurer")
        assertTrue("变异④必须先真的打上去", forgetsRemember != body)
        assertTrue(
            "measure 没被 remember 必须红",
            topicMeasureIsRemembered(forgetsRemember).any { it.contains("remember") }
        )

        // ④b 水印颜色换成 textMid ⇒ Topic 3 红（绕过审计口径）
        val wrongInk = body.replaceFirst(
            "WotaType.caption.copy(color = WotaColor.textLo)",
            "WotaType.caption.copy(color = WotaColor.textMid)"
        )
        assertTrue("变异④b 必须先真的打上去", wrongInk != body)
        assertTrue(
            "水印换了字色档必须红",
            topicMeasureIsRemembered(wrongInk).any { it.contains("WotaColor.textLo") }
        )

        // ⑤ 宽度比例改成 1.0 ⇒ Topic 4 红
        val fullWidth = src.replaceFirst(
            "NOTE_WATERMARK_WIDTH_FRACTION = 0.56f",
            "NOTE_WATERMARK_WIDTH_FRACTION = 1.0f"
        )
        assertTrue("变异⑤必须先真的打上去", fullWidth != src)
        assertTrue(
            "宽度取满幅必须红",
            topicResourcesAndWidthFraction(fullWidth, body).any { it.contains("1.0") }
        )

        // ⑥ 捕获层命中裁决被改 ⇒ Topic 5 红
        val touchedDrag = body.replaceFirst(
            "val hit = entryAtPointer(pos.x.roundToInt(), pos.y.roundToInt())",
            "val hit = null"
        )
        assertTrue("变异⑥必须先真的打上去", touchedDrag != body)
        assertTrue(
            "命中裁决被改必须红",
            topicCaptureLayerVerbatim(touchedDrag).any { it.contains("entryAtPointer") }
        )

        // ⑦ 水印层排到网格之下（文本等价物：把网格那次调用改名叫 drawText，绘制点前移）⇒ Topic 6 红
        val underGrid = body.replaceFirst("pageGridBoxOf", "drawText(")
        assertTrue("变异⑦必须先真的打上去", underGrid != body)
        assertTrue(
            "水印排在网格之下/容器之下必须红",
            topicWatermarkUnderContainers(underGrid).isNotEmpty()
        )
    }
}
