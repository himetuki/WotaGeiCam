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
 * 2. 渲染必须是**遍历**：锚是 `warns.forEach` 这一串字面，找不到就是没在逐行画；
 * 3. **`isNotEmpty()` 分叉必须在场，且先于遍历**——这是「无告警时整块不组合」的唯一保证：
 *    删掉分叉后 `Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp))` 在**零子元素时
 *    照样按 padding 量出 20×2dp 的隐形盒**（Compose 的 padding 参与测量，不依赖子元素），
 *    于是干净态下 `topBarH` 静默 +2dp、竖 Dock 上界与 HintBar 整体下移 2dp——用户看不出来，
 *    但 #3 承诺过「无告警时组合体为空、不占位」，承诺了就得有人看管；
 * 4. **不得**出现取单条的形态：`.firstOrNull(` / `.lastOrNull(` / 按索引取一条。
 *
 * # 覆盖边界（本尺是启发式，说清它抓得到什么、抓不到什么）
 * 判据 2/3 的遍历锚是**字面** `warns.forEach`，判据 4 只禁上面三种字面形态。逐类过一遍：
 * - `Text(stringResource(warns.first()))`（整个丢掉遍历）→ 判据 2 红；
 * - `warns.take(1).forEach { … }`（恒长 1 的子列表）→ 字面断成 `warns.take(1).forEach`，判据 2 红；
 * - `warns.forEachIndexed { i, _ -> if (i == 0) Text(…) }` → `warns.forEach` 是它的**前缀**，
 *   判据 2/3/4 全绿 ⇒ **本尺的盲区**；
 * - 在 lambda 内用标志位掐掉第二条起（`warns.forEach { res -> if (drawAll) … }`）⇒ 同为盲区。
 * 后两类需要时**另加判据**，不为假想写法把尺子搞脆（AGENTS.md：静态"证明"要分清恒等式与真断言，
 * 宁可把边界写明白，也不写一条测不出退化的空断言）。同理，锚串与局部变量名 `warns` 绑定，
 * 连同分叉锚一起改名会让判据 2/3 一起红——那是有意的：改名者必须回来更新这把尺。
 *
 * 「尺子自己能红」：四条突变体打同一把尺（见各突变注释）。
 */
class WarnRowsRenderGuardTest {

    /** 主源码（遮蔽 + 折叠：结构判定不受换行缩进影响，突变体写法因此收敛成单行） */
    private val realMasked: String by lazy {
        flatten(codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")))
    }

    /** 定位用的名字带接收者：`fun BoxScope.HudTopChrome(`（bodyOf 的正则按原文逐字匹配） */
    private val chromeFn = "BoxScope.HudTopChrome"

    /** 分叉锚：「无告警时整块不组合」就写在这枚字符串上 */
    private val branchAnchor = "if (warns.isNotEmpty())"

    /** 遍历锚：判据 2 的字面，也是判据 3 次序比对的落点（见 KDoc 覆盖边界） */
    private val loopAnchor = "warns.forEach"

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
        // 判据 3：分叉在场，且先于遍历
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
        // （删 `if (…)` 会留下一枚配对 `}`，不影响 bodyOf：matchBraces 提前一位收体，切出的文本里两者俱在）
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
}
