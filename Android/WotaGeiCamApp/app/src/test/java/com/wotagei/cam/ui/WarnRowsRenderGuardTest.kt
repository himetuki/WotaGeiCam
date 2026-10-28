package com.wotagei.cam.ui

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 告警条多槽化的**源码守卫**（#3 渲染侧；范式同 `bt/BtScanLocationPermGuardTest`）。
 *
 * [WarnRowsTest] 测的是「该返回哪几行」，这条测的是「渲染侧真的把返回值**逐行**画出来了吗」——
 * 纯函数改对了、渲染却退回单条，JVM 行为测试全绿而真机仍只显示一条，正是最坏的一种假绿。
 *
 * 四条判据（全部作用在 `codeOnly` 遮蔽 + `flatten` 折叠后的 [fun HudTopChrome] 体上，注释提到不算）：
 * 1. 体内必须出现 `warnRows(` 调用（告警条的数据源是纯函数，不是就地 `when`）；
 * 2. 渲染必须是**遍历**：锚是 `warns.forEach` 这一串字面，找不到就是没在**按本形**逐行画
 *    （找得到也不等于逐行画——见下方「抓不到」里**前两类**盲区：`forEachIndexed` 与标志位掐条，
 *    它们都留下了 `warns.forEach` 字面却并没画全；这条判据是假言而非等价）；
 * 3. **`isNotEmpty()` 分叉必须在场，且先于遍历**——这是「无告警时整块不组合」的唯一保证：
 *    删掉分叉后 `Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp))` 在**零子元素时
 *    照样按 padding 量出 20×2dp 的隐形盒**（Compose 的 padding 参与测量，不依赖子元素），
 *    于是干净态下 `topBarH` 静默 +2dp、竖 Dock 上界与 HintBar 整体下移 2dp——用户看不出来，
 *    但 #3 承诺过「无告警时组合体为空、不占位」，承诺了就得有人看管；
 * 4. **不得**出现取单条的形态：`.firstOrNull(` / `.lastOrNull(` / 按索引取一条。
 *
 * V7 增补两条（同体、同法）：
 * 5. **尺寸过渡动画壳必须在场**（锚 `animateContentSize(motion.intSize)`），且 `onSizeChanged`
 *    必须排在它**外侧**（文本在前）——动画壳挂在常驻外层 Column 上，0→1 行的整节点进场才有得动画；
 *    链序翻了（animateContentSize 到外层去）则 onSizeChanged 直接量到终值，topBarH 与竖 Dock
 *    上界 / HintBar 瞬跳、只有告警区自己在动，观感断裂（同 HudDockZone「外层吃内层上报尺寸」
 *    的同一套链序道理）；
 * 6. **告警专属 padding 必须排在 isNotEmpty() 分叉之内**——常驻动画壳若带上了内层专属 padding，
 *    零告警时照样按 padding 量出隐形盒：判据 3 防的是「分叉被删」，这条防的是「分叉还在、
 *    padding 被提到壳上」的 +2dp 幽灵盒同义重排。
 *
 * # 覆盖边界（本尺是启发式：说清它抓得到什么、抓不到什么、以及会误报什么）
 * 判据 2/3 的遍历锚是**字面** `warns.forEach`，判据 4 只禁上面三种字面形态，判据 5/6 各绑一枚
 * 字面锚（`animateContentSize(motion.intSize)` / `padding(start = 10.dp`）。分块列。
 *
 * **抓得到**（正面清单，实测红）：
 * - `Text(stringResource(warns.first()))`（整个丢掉遍历）→ 判据 2 红；
 * - `warns.take(1).forEach { … }`（遍历一个被篡改过的列表）→ 字面断成 `warns.take(1).forEach`，
 *   判据 2 红——同族的 `warns.filter{…}` / `.drop` 也一并红，这是锚收窄顺带闭合的老盲区；
 * - 删掉 `if (warns.isNotEmpty())` 分叉 → 判据 3 红（突变 4）；
 * - 摘掉 `animateContentSize(motion.intSize)` → 判据 5 红（突变 5，行数变化退回瞬跳）；
 * - 动画壳翻到 `onSizeChanged` 外侧 → 判据 5 次序红（突变 6，topBarH 瞬跳不跟动画）；
 * - 告警专属 padding 提到分叉之外（壳上）→ 判据 6 红（突变 7，幽灵盒换形）。
 *
 * **抓不到（盲区 = 假绿）**：
 * - `warns.forEachIndexed { i, _ -> if (i == 0) Text(…) }` → `warns.forEach` 是它的**前缀**，
 *   判据 2/3/4 全绿；
 * - 在 lambda 内用标志位掐掉第二条起（`warns.forEach { res -> if (drawAll) … }`）⇒ 同为盲区；
 * - **分叉掏空**：`if (warns.isNotEmpty()) { }` 保留、`Column(…){ warns.forEach }` 搬到分叉外无条件跑
 *   ⇒ 判据 1/2/3/4 **全绿**。根因是**先序 ≠ 包含**——判据 3 比的是两枚字面在文本里的**先后**，
 *   而不是「遍历真的落在分叉花括号内」，所以它只防「遍历被提拉出分叉」那一型（突变 4 实测红），
 *   防不住「分叉被掏空、遍历本来就在外面」这一型。这恰是判据 3 声称要防的 +2dp 隐形盒回归换个
 *   同义重排就漏过去。判据 6 与此同构（比的是 padding 锚与分叉锚的先后），同样防不住掏空型；
 * - 判据 5 绑死**具名规格**这一种写法：换 `animateContentSize()` 默认 spring 或另造规格，字面
 *   断掉即红——这属**误红面**（Motion 例外 #2 的具名规格纪律要求调用点用 `motion.intSize`，
 *   真要换规格的人本就该回来看这把尺）。
 *
 * **会误红（假红 = 合法改写被判违规）**：判据 2/3 的锚绑死**局部变量名 `warns` 与写法形态**，
 * 判据 5/6 绑死 modifier 链的字面形态，下列语义等价的写法会被判红（逐条实测）：
 * - `val texts = warns.map { stringResource(it) }` 预计算、再 `texts.forEach` 渲染 → 判据 2 红
 *   （老锚 `contains("forEach")` 下是绿的，**这是锚收窄新增的最大代价**）；
 * - `warnRows(…).forEach { }` 内联调用 + `if (warns)` 这类简写分叉 → 判据 2 红（`warns` 字面没了）；
 * - `if (warns.size > 0)` 等价分叉拼写 → 判据 3 红；
 * - `for (res in warns) { … }` 循环形态 → 判据 2 红；
 * - **预遍历写在分叉之前**（`warns.forEach { stash.add(it) }` 排在 `if` 之前，真正的渲染遍历仍乖乖
 *   待在分叉内）→ 判据 3 次序红，且 message 写「遍历在分叉外」**与事实相反**——已知误导，
 *   红了不要拿这条 message 当结构证据；
 * - 改名局部变量 `warns` → 判据 2/3 一起红（**有意的**：改名者必须回来更新这把尺与本节）；
 * - 改 `motion` 局部名 / 动画规格改写 / padding 数值微调 → 判据 5/6 红（**有意的**：改者必须
 *   回来更新锚字面与本节）。
 *
 * **处置约定**：守卫红了**先读本节**——属误红面则把锚字面改到新写法上（锚常量与本节清单在
 * 同一次提交里一起改，别只改一半）；属盲区则加一枚防突变体钉住它、或另加判据，两种都要留下
 * 可追溯的说明。不为盲区**当场**叠解析器（AGENTS.md：静态"证明"要分清恒等式与真断言，
 * 宁可把边界写明白，也不写一条测不出退化的空断言；而解析器一旦引入就再难拆掉）。
 *
 * 「尺子自己能红」：七枚突变体打同一把尺（见各突变注释）。
 */
class WarnRowsRenderGuardTest {

    /** 主源码（遮蔽 + 折叠：结构判定不受换行缩进影响，突变体写法因此收敛成单行） */
    private val realMasked: String by lazy {
        flatten(codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")))
    }

    /** 定位用的名字带接收者：`fun BoxScope.HudTopChrome(`（bodyOf 的正则按原文逐字匹配） */
    private val chromeFn = "BoxScope.HudTopChrome"

    /**
     * 分叉锚：「无告警时整块不组合」就写在这枚字符串上。
     * 【2026-10-28 扩展】帧率坍塌提示（lowFpsNote，见 Camera2Engine/LowFpsWatch）同住这条分叉，
     * 条件从 `if (warns.isNotEmpty())` 扩成带 `|| lowFpsNote != null` 的全字面——锚跟着改
     * （处置约定：锚常量与源码形态同一次提交一起改）。「零子元素不组合」的语义不变。
     */
    private val branchAnchor = "if (warns.isNotEmpty() || lowFpsNote != null)"

    /** 遍历锚：判据 2 的字面，也是判据 3 次序比对的落点（见 KDoc 覆盖边界） */
    private val loopAnchor = "warns.forEach"

    /** 动画壳锚：判据 5 的字面——Motion 例外 #2 的具名规格，与 HudDockZone 同源（改规格回本尺） */
    private val animAnchor = ".animateContentSize(motion.intSize)"

    /** 告警专属 padding 锚：判据 6 的字面——只许活在 isNotEmpty() 分叉之内（改值回本尺） */
    private val padAnchor = "padding(start = 10.dp"

    // ------------------------------------------------------------------ 判据（纯函数，真身与突变体同吃）

    private fun renderIssues(masked: String): List<String> {
        val bad = ArrayList<String>()
        val body = runCatching { flatten(bodyOf(masked, chromeFn)) }.getOrNull()
        if (body.isNullOrBlank()) {
            bad.add("定位不到 fun $chromeFn 的函数体——守卫失去输入，不许算通过")
            return bad
        }
        if (!body.contains("warnRows(")) {
            bad.add("HudTopChrome 体内找不到 warnRows( 调用——告警条退回旧的 warnRes 单条 when（守卫失去输入）")
            return bad
        }
        val branchAt = body.indexOf(branchAnchor)
        val loopAt = body.indexOf(loopAnchor)
        // 判据 2
        if (loopAt < 0) {
            bad.add("告警条渲染不是遍历（体内找不到 $loopAnchor 字面）——多槽纯函数算对了也没被逐行画出来")
        }
        // 判据 3：分叉在场，且先于遍历。
        // 存在理由与失效方式——**文本先序只表达「遍历在分叉内」的有界含义**：它防得住「遍历被提拉出
        // 分叉」那一型（突变 4 实测红），防不住「分叉被掏空、遍历本来就在分叉外」那一型（T9 全绿）。
        // 两条边都写死在类 KDoc 的覆盖边界里；不要为 T9 在这里叠解析器——叠了就把尺子搞脆。
        // 已知误红：预遍历排在分叉之前时本条会红，而 message「遍历在分叉外」与事实相反（见 KDoc 误红面）。
        if (branchAt < 0) {
            bad.add("告警条缺 isNotEmpty() 分叉——零告警时 Column 仍按 padding 量出隐形盒，topBarH 静默 +2dp")
        } else if (loopAt in 0 until branchAt) {
            bad.add("isNotEmpty() 分叉必须先于 $loopAnchor——分叉在遍历之后 = 遍历在分叉外")
        }
        // 判据 4
        for (take in listOf(".firstOrNull(", ".lastOrNull(")) {
            if (body.contains(take)) {
                bad.add("体内出现 $take——按序取单条，多条降级并存时优先级低的告警又被吞掉")
            }
        }
        if (Regex("""\[\s*\d+\s*\]""").containsMatchIn(body)) {
            bad.add("体内出现按下标取单条的形态（[n]）——同 firstOrNull，只画一条")
        }
        // 判据 5：尺寸过渡动画壳在场，且 onSizeChanged 排在它外侧（文本在前＝链上更外层）。
        // 链序的语义：onSizeChanged 吃的是 animateContentSize 向外回报的**动画中间尺寸**，topBarH
        // 才能逐帧刷新、Dock/HintBar 跟着动画走；翻过来 onSizeChanged 量到的就是终值——告警区还在
        // 动、Dock/HintBar 已瞬跳，观感断裂。
        val animAt = body.indexOf(animAnchor)
        val sizeAt = body.indexOf(".onSizeChanged")
        if (animAt < 0) {
            bad.add("告警容器丢了 animateContentSize 尺寸过渡壳——行数变化（含 DIRECT 特效增删）退回 22dp/行瞬跳，V7 平滑承诺失效")
        } else if (sizeAt >= 0 && animAt < sizeAt) {
            bad.add("animateContentSize 排到了 onSizeChanged 外侧——onSizeChanged 直接量到终值，topBarH 与 Dock/HintBar 瞬跳、不跟动画")
        }
        // 判据 6：告警专属 padding 必须排在 isNotEmpty() 分叉之内（branchAt < 0 时判据 3 已红，这里不重复计）。
        // 常驻动画壳若带上了这枚 padding，零告警时壳照样按 padding 量出 20×2dp 隐形盒——判据 3 防
        // 「分叉被删」，这条防「分叉还在、padding 被提到壳上」的幽灵盒同义重排。
        val padAt = body.indexOf(padAnchor)
        if (padAt < 0) {
            bad.add("体内找不到 $padAnchor——告警条专属 padding 形态变了，回本尺更新锚字面")
        } else if (branchAt >= 0 && padAt < branchAt) {
            bad.add("告警专属 padding 排到 isNotEmpty() 分叉之外——常驻壳带内层专属 padding，零告警时照样量出隐形盒，+2dp 幽灵盒回归")
        }
        return bad
    }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `告警条按warnRows逐行遍历渲染且不取单条`() {
        assertEquals("告警条渲染回归：\n${renderIssues(realMasked).joinToString("\n")}", emptyList<String>(), renderIssues(realMasked))
    }

    // ------------------------------------------------------------------ 突变自证（尺子自己能红）

    @Test
    fun `尺子自己能红_渲染改成firstOrNull取单条必报红`() {
        val real = realMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", renderIssues(real).isEmpty())
        // 突变 1：遍历改成取第一条（语义真实变化：第二条起不再绘制，同时踩中「遍历」与「取单条」两条判据）
        val mutant = real.replace("$loopAnchor {", "warns.firstOrNull()?.let {")
        assertTrue("渲染改成 firstOrNull 后尺子必须红", renderIssues(mutant).isNotEmpty())
        assertTrue(
            "突变体的红要落在「取单条」这条判据上",
            renderIssues(mutant).any { it.contains("firstOrNull") }
        )
    }

    @Test
    fun `尺子自己能红_渲染丢掉遍历必报红`() {
        val real = realMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", renderIssues(real).isEmpty())
        // 突变 2：遍历整枚换成 let（体里没了 warns.forEach 字面；括号仍配平，bodyOf 照常能解出函数体）
        val mutant = real.replace("$loopAnchor {", "warns.let {")
        assertTrue("渲染丢掉遍历后尺子必须红", renderIssues(mutant).any { it.contains("遍历") })
    }

    @Test
    fun `尺子自己能红_调用点退回旧的warnRes_when必报红`() {
        val real = realMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", renderIssues(real).isEmpty())
        // 突变 3：调用点整体退回改前那道「warnRes 单条 when」——warnRows( 调用整个消失，
        // 守卫按「输入型错误」报红（这正是它要防的那件事：多槽化被静默回退）
        val mutant = real.replace(
            Regex("""val warns = warnRows\([^)]*\)"""),
            "val warnRes = when { deviceFailedHighFps -> R.string.cam_state_high_fps; else -> null }"
        )
        assertTrue(
            "warnRows( 调用消失后尺子必须红（守卫以输入型错误报红）",
            renderIssues(mutant).any { it.contains("warnRows(") }
        )
    }

    @Test
    fun `尺子自己能红_删掉isNotEmpty分叉必报红`() {
        val real = realMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", renderIssues(real).isEmpty())
        // 突变 4：把「无告警时整块不组合」的分叉整个删掉——这是**真结构变化**、不是语义空操作：
        // 删掉后 `warns.forEach` 仍在、体内 `warnRows(` 调用仍在，判据 1/2/4 原样通过，只有判据 3 变红。
        // 若判据 3 缺席，这把尺子对「干净态 topBarH 静默 +2dp」就是完全盲的——这就是它必须存在的理由。
        // （2026-10-28 起分叉条件含 lowFpsNote，锚字面见 branchAnchor 注；删 `if (…)` 留孤儿 `}` 的
        //  配平行为不变）
        val mutant = real.replace("$branchAnchor {", "")
        assertTrue(
            "删掉 isNotEmpty() 分叉后尺子必须红",
            renderIssues(mutant).any { it.contains("isNotEmpty() 分叉") }
        )
        assertTrue(
            "突变体必须仍持有 warnRows( 与遍历字面——否则这颗针头没扎在判据 3 上（扎偏的红不算数）",
            renderIssues(mutant).none {
                it.contains("warnRows( 调用——") || it.contains("渲染不是遍历") || it.contains("firstOrNull")
            }
        )
    }

    @Test
    fun `尺子自己能红_摘掉尺寸过渡动画壳必报红`() {
        val real = realMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", renderIssues(real).isEmpty())
        // 突变 5：把 animateContentSize 摘掉——行数变化回到 22dp/行瞬跳，V7 的平滑承诺整个失效
        //（真语义变化：体内没了动画壳字面，判据 5 独自红，其余判据不受牵连）
        val mutant = real.replace(animAnchor, "")
        assertTrue(
            "摘掉动画壳后尺子必须红",
            renderIssues(mutant).any { it.contains("animateContentSize 尺寸过渡壳") }
        )
        assertTrue(
            "突变体的红要落在判据 5 上（其余判据照常绿，说明锚没绑歪）",
            renderIssues(mutant).size == 1
        )
    }

    @Test
    fun `尺子自己能红_动画壳翻到onSizeChanged外侧必报红`() {
        val real = realMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", renderIssues(real).isEmpty())
        // 突变 6：链序翻面——animateContentSize 挪到 onSizeChanged 外侧，onSizeChanged 直接量到
        // 终值：告警区还在动画、topBarH 与 Dock/HintBar 已瞬跳到位（观感断裂，真语义变化非空操作）
        val report = ".onSizeChanged { onHeightChanged(with(density) { it.height.toDp() }.value.roundToInt()) }"
        val mutant = real.replace("$report $animAnchor", "$animAnchor $report")
        assertTrue(
            "动画壳翻到 onSizeChanged 外侧后尺子必须红",
            renderIssues(mutant).any { it.contains("排到了 onSizeChanged 外侧") }
        )
    }

    @Test
    fun `尺子自己能红_告警专属padding提到分叉之外必报红`() {
        val real = realMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", renderIssues(real).isEmpty())
        // 突变 7：常驻动画壳带上告警专属 padding、内层分叉里摘掉——零告警时壳照样按 padding 量出
        // 20×2dp 隐形盒（+2dp 幽灵盒换了个同义重排）。判据 3 的分叉还在、防不住这型，判据 6 专职盯它
        val pad = "Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp)"
        val mutant = real
            .replace("Column( $pad,", "Column( Modifier,")
            .replace(animAnchor, "$animAnchor$pad")
        assertTrue(
            "告警专属 padding 出了分叉后尺子必须红",
            renderIssues(mutant).any { it.contains("幽灵盒回归") }
        )
    }
}
