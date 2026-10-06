package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页「左右两栏」改造的**源码守卫**（尺子本身是 [KotlinSourceScan]）。
 *
 * 两栏改造最容易做半截：区块搬进具名 composable 时漏搬一两个，剩下的 `SettingGroup(...)` 继续
 * 挂在根 Column 上——编译全过、观感却一半两栏一半单栏，肉眼未必当场看出。三条判据：
 * 1. 11 个 `Block*` 具名 composable **逐个**都在（用 [bodyOf] 定位，缺一个直接红）；
 * 2. 「检查更新」入口的**唯一调用点**仍挂在关于区块 [bodyOf] 体内（搬动不许把入口搬丢/搬去别处）；
 * 3. **负面锁**：根 Column 所在的 `SettingsScreen` / `SettingsBody` 体内不得再出现裸 `SettingGroup(`。
 *
 * 三条判据都是吃 `masked: String` 的纯函数，真身与合成突变体同吃一把尺——每条都配「突变必红」自证。
 */
class SettingsTwoColumnGuardTest {

    private val realMasked: String by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
    }

    /** 11 个区块 composable 的函数名（枚举声明顺序）；具名强绑定，改一个名字这里就红 */
    private val blockFns = listOf(
        "BlockDefault",
        "BlockOrientation",
        "BlockMotion",
        "BlockHud",
        "BlockStorage",
        "BlockRefline",
        "BlockLevel",
        "BlockCompare",
        "BlockText",
        "BlockPerm",
        "BlockAbout",
    )

    // ------------------------------------------------------------------ 判据（纯函数，真身与突变体同吃）

    /** 缺体的区块 composable 名列表（空 = 全都在）。用 bodyOf 定位，找不到/只有表达式体都算缺 */
    private fun missingBlocks(masked: String): List<String> =
        blockFns.filter { name -> runCatching { bodyOf(masked, name) }.getOrNull().isNullOrBlank() }

    /** 根列体里裸挂 `SettingGroup(` 的问题列表（空 = 无越界挂载） */
    private fun bareSettingGroupIssues(masked: String): List<String> {
        val issues = ArrayList<String>()
        for (host in listOf("SettingsScreen", "SettingsBody")) {
            val body = runCatching { bodyOf(masked, host) }.getOrNull()
            if (body == null) {
                issues.add("定位不到 fun $host 的体——守卫失去输入，不许算通过")
                continue
            }
            if (occurrences(body, "SettingGroup").isNotEmpty()) {
                issues.add("$host 体内直接挂载了 SettingGroup——两栏改造做了半截，区块漏进根列")
            }
        }
        return issues
    }

    /** 「检查更新」入口的问题列表：调用点必须恰好 1 个、且落在 BlockAbout 体内 */
    private fun updateEntryIssues(masked: String): List<String> {
        val issues = ArrayList<String>()
        // 声明（`fun UpdateSection()`）也含子串，按前文是否以 `fun` 收尾剔掉
        val calls = occurrences(masked, "UpdateSection()")
            .filterNot { masked.substring(0, it).trimEnd().endsWith("fun") }
        if (calls.size != 1) {
            issues.add("UpdateSection() 的调用点数应为 1，实为 ${calls.size}——入口被搬丢或复制")
            return issues
        }
        val about = runCatching { KotlinSourceScan.regionOf(masked, "BlockAbout") }.getOrNull()
        if (about == null) {
            issues.add("定位不到 BlockAbout 的函数体区间——守卫失去输入")
            return issues
        }
        if (calls[0] !in about.start until about.end) {
            issues.add("检查更新入口不在 BlockAbout 体内——入口被搬去了别处")
        }
        return issues
    }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `十一个区块具名composable逐个都在`() {
        assertEquals("缺失/无体的区块 composable：", emptyList<String>(), missingBlocks(realMasked))
    }

    @Test
    fun `检查更新入口唯一调用点仍在关于区块内`() {
        // 前置：UpdateSection 函数体本身也还在（否则下面 regionOf 会抛）
        assertTrue("UpdateSection 函数体必须仍在本文件", bodyOf(realMasked, "UpdateSection").isNotBlank())
        assertEquals("检查更新入口越界：", emptyList<String>(), updateEntryIssues(realMasked))
    }

    @Test
    fun `根列体里不再裸挂SettingGroup`() {
        assertEquals("区块漏进根列：", emptyList<String>(), bareSettingGroupIssues(realMasked))
    }

    // ------------------------------------------------------------------ 突变自证（尺子自己能红）

    @Test
    fun `尺子自己能红_少一个区块具名composable必报红`() {
        val mutant = realMasked.replace("fun BlockOrientation", "fun RenamedBlockOrientation")
        assertTrue(
            "删掉/改名 BlockOrientation 后尺子必须红",
            missingBlocks(mutant).contains("BlockOrientation"),
        )
    }

    @Test
    fun `尺子自己能红_区块漏进根列必报红`() {
        val mutant = realMasked.replace(
            "SettingsBody(plan, prefs, onOpenHudEditor)",
            "SettingGroup(\"x\")\n            SettingsBody(plan, prefs, onOpenHudEditor)",
        )
        assertTrue(
            "根列里塞回一个裸 SettingGroup 后尺子必须红",
            bareSettingGroupIssues(mutant).any { it.contains("SettingGroup") },
        )
    }

    @Test
    fun `尺子自己能红_更新入口被复制或搬走必报红`() {
        // 复制一份到别的函数（调用点变 2）
        val duplicated = realMasked +
            "\nprivate fun mutantA() { UpdateSection() }\nprivate fun mutantB() { UpdateSection() }"
        assertTrue(
            "更新入口被复制后尺子必须红",
            updateEntryIssues(duplicated).any { it.contains("调用点数应为 1") },
        )
        // 从 BlockAbout 里搬走、挪到别处（调用点仍 1，但不在 BlockAbout 体内）
        val movedOut = realMasked.replace("        UpdateSection()", "") +
            "\nprivate fun mutantElsewhere() { UpdateSection() }"
        assertTrue(
            "更新入口被搬出 BlockAbout 后尺子必须红",
            updateEntryIssues(movedOut).any { it.contains("不在 BlockAbout 体内") },
        )
    }
}
