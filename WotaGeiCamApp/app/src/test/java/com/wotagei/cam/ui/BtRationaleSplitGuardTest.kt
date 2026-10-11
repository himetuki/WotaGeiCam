package com.wotagei.cam.ui

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝牙权限提示文案的**按档分流**守卫（#18 ① 文案重写）。
 *
 * 改前 `res/values/strings.xml` 的 `perm_bluetooth_rationale` 是一条串两档共用：
 * 「需要蓝牙权限才能连接音箱；Android 10/11 搜索附近设备还需位置权限」——它把两件权限塞进一句话，
 * 在 API 31+ 档里还平白多提一句那档根本用不上的「位置」。本轮拆成两条，按 SDK 在消费点分流：
 * - `perm_bluetooth_rationale_low`（API ≤ 30）：先点明功能（搜音箱）→ 再讲权限来源（系统要求）
 *   → 随即打消疑虑（不读位置）；
 * - `perm_bluetooth_rationale`（API 31+）：原句去掉「顺带位置」半句。
 *
 * 判据（作用在 codeOnly 遮蔽 + flatten 折叠后的 [fun BtSpeakerPanel] 体内，注释提到不算）：
 * 分流的字面锚 `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)` 必须在场，且**先于**低档串、
 * 两条串各出现一次、`else` 档走的是不带 `_low` 后缀的那条。
 *
 * 「尺子自己能红」：删掉分档只留一条 → 红（见突变自证）。
 */
class BtRationaleSplitGuardTest {

    /** 分流锚：`VERSION_CODES.S` 档位分界（31 起只申请蓝牙运行时权限） */
    private val sBranch = "if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)"

    private val realMasked: String by lazy {
        flatten(codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/BtSpeakerPanel.kt")))
    }

    /** 31+ 档那条（必须是**不带 `_low` 后缀**的 `perm_bluetooth_rationale`） */
    private val modernRes = Regex("""R\.string\.perm_bluetooth_rationale(?!_)""")

    /** 低档那条 */
    private val lowRes = "R.string.perm_bluetooth_rationale_low"

    // ------------------------------------------------------------------ 判据（纯函数，真身与突变体同吃）

    private fun splitIssues(masked: String): List<String> {
        val bad = ArrayList<String>()
        val body = runCatching { flatten(bodyOf(masked, "BtSpeakerPanel")) }.getOrNull()
        if (body.isNullOrBlank()) {
            bad.add("定位不到 fun BtSpeakerPanel 的函数体——守卫失去输入，不许算通过")
            return bad
        }
        val gateAt = body.indexOf(sBranch)
        val lowAt = body.indexOf(lowRes)
        val modernAt = modernRes.find(body)?.range?.first ?: -1
        if (gateAt < 0) {
            bad.add("BtSpeakerPanel 里没有 $sBranch 分档——两条文案退回单条（守卫失去输入）")
            return bad
        }
        if (lowAt < 0) bad.add("低档串 perm_bluetooth_rationale_low 没被消费")
        if (modernAt < 0) bad.add("31+ 档串 perm_bluetooth_rationale 没被消费")
        if (lowAt in 0 until gateAt) bad.add("低档串必须落在版本闸之后（分流点之前取串 = 无分档）")
        if (modernAt in 0 until gateAt) bad.add("31+ 档串必须落在版本闸之后（分流点之前取串 = 无分档）")
        return bad
    }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `权限提示文案按VERSION_CODES_S分档`() {
        assertEquals(
            "文案分流回归：\n${splitIssues(realMasked).joinToString("\n")}",
            emptyList<String>(),
            splitIssues(realMasked)
        )
    }

    // ------------------------------------------------------------------ 突变自证（尺子自己能红）

    @Test
    fun `尺子自己能红_删掉分档只留一条必报红`() {
        val real = realMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", splitIssues(real).isEmpty())
        // 突变 1：整个分档删掉，退回「一条串两档共用」的旧形态
        val oneLine = real.replace(
            Regex("""val rationaleRes\s*=\s*if \(Build\.VERSION\.SDK_INT < Build\.VERSION_CODES\.S\)\s*R\.string\.perm_bluetooth_rationale_low\s*else\s*R\.string\.perm_bluetooth_rationale"""),
            "val rationaleRes = R.string.perm_bluetooth_rationale"
        )
        assertTrue(
            "删掉分档后尺子必须红",
            splitIssues(oneLine).any { it.contains("分档") }
        )
        // 突变 2：留着分档但把低档串换成 31+ 那条（两档显示同一句话，低档的位置解释消失）
        val bothModern = real.replace(lowRes, "R.string.perm_bluetooth_rationale")
        assertTrue(
            "低档串被换成 31+ 串后尺子必须红",
            splitIssues(bothModern).any { it.contains("低档串") }
        )
    }
}
