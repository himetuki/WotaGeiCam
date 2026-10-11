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
 * 三条判据（全部作用在 `codeOnly` 遮蔽 + `flatten` 折叠后的 [fun HudTopChrome] 体上，注释提到不算）：
 * 1. 体内必须出现 `warnRows(` 调用（告警条的数据源是纯函数，不是就地 `when`）；
 * 2. 渲染必须是**遍历**（`forEach`），无告警时组合体整块不组合；
 * 3. **不得**出现取单条的形态：`.firstOrNull(` / `.lastOrNull(` / 按索引取一条。
 *
 * 「尺子自己能红」：两条突变体打同一把尺（见各突变注释）。
 */
class WarnRowsRenderGuardTest {

    /** 主源码（遮蔽 + 折叠：结构判定不受换行缩进影响，突变体写法因此收敛成单行） */
    private val realMasked: String by lazy {
        flatten(codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")))
    }

    /** 定位用的名字带接收者：`fun BoxScope.HudTopChrome(`（bodyOf 的正则按原文逐字匹配） */
    private val chromeFn = "BoxScope.HudTopChrome"

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
        if (!body.contains("forEach")) {
            bad.add("告警条渲染不是遍历（forEach）——多槽纯函数算对了也没被逐行画出来")
        }
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
        val mutant = real.replace("warns.forEach {", "warns.firstOrNull()?.let {")
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
        // 突变 2：遍历整枚换成 let（体里没了 forEach；括号仍配平，bodyOf 照常能解出函数体）
        val mutant = real.replace("warns.forEach {", "warns.let {")
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
}
