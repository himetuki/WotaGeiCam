package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「EV 闪退第 2 轮」守门：格网条目的**发射数必须恒等于 items 数**（真机两次全新启动 100% 复现：
 * `java.lang.IllegalArgumentException: 格网测量：颗数与尺寸数不等 3 vs 2`，崩在主线程 measure 期
 * `HudLayer.kt` 的 HudEntryGrid → `gridPlacementOf` 的 require）。
 *
 * 错位机理（谁在什么时候用哪个状态）：READOUT 网格 items = [快门, 帧率, EV] 三颗，恒含 EV；
 * 旧实现里 EV 那颗的条目节点就是 `AnimatedVisibility(visible = value != null)` 本身——
 * 点快门胶囊翻 MANUAL 后 `readoutValue(EV)` 变 null，退场动画（淡出）结束时 AnimatedVisibility
 * 的内容离开组合，**不再给父 Layout 留任何 measurable**；而 HudEntryGrid 的作用域没重组
 * （items 形参没变，仍是 3 颗），measure 块重跑时 measurables=2、items=3 ⇒ require 炸。
 * （`items` 是配置驱动，从不按读数值过滤；淡出动画期间两者仍相等，错位只在"内容离组"那一拍。）
 *
 * 修法（方案 a 的"节点恒在"变体）：条目外面包一枚恒在的 `Box(modifier = modifier)`，
 * AnimatedVisibility 收进 Box 内部只管淡出/淡入——颗数恒等于 items.size，"节点有无"不再随值变。
 *
 * 这是"组合期发射数 vs measure 期 items 数"的 Compose 时序问题，JVM 测不了 Compose 本身，
 * 全部用源码钉扎（仿 [EvCrashGuardTest] / [PillEnabledGuardTest] 扫描式），判定只作用在
 * codeOnly 遮蔽后的文本上（注释不参与，钉的是结构不是说明文字）。
 */
class HudGridEmissionGuardTest {

    @Test
    fun readoutEntryNodeIsAlwaysPresentAndVisibilityLivesInside() {
        val body = bodyOf(codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")), "HudEntryItem")
        val box = KotlinSourceScan.occurrences(body, "Box(modifier = modifier)")
        val av = KotlinSourceScan.occurrences(body, "AnimatedVisibility(")
        assertTrue(
            "读数条目必须有一枚恒在的 Box 承接条目位（退回旧实现＝AnimatedVisibility 自身当节点，红）",
            box.isNotEmpty()
        )
        assertTrue("读数条目的 AnimatedVisibility 调用点在场（守卫失去输入）", av.isNotEmpty())
        assertTrue(
            "恒在 Box 必须包在 AnimatedVisibility 外面（可见性住在条目内部，而不是节点有无随值变）",
            box[0] < av[0]
        )
        assertFalse(
            "退场节点不许再当外层发射：AnimatedVisibility 不许自带 modifier = modifier" +
                "（旧实现形态，退场结束内容离组 → 父格网少一颗 → 3 vs 2 崩栈）",
            KotlinSourceScan.flatten(body).contains(", modifier = modifier ) {")
        )
    }

    @Test
    fun gridEmissionStaysUnconditionalAndPlacementGuardStays() {
        val grid = bodyOf(codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")), "HudEntryGrid")
        assertTrue(
            "HudEntryGrid 的发射必须与 items 同源：items.forEach 恒发射，不许在发射侧按读数值省节点" +
                "（发射侧省节点会造出 2 vs 3 的反向错位，淡出动画也被砍）",
            KotlinSourceScan.occurrences(grid, "items.forEach { content(it.entry) }").isNotEmpty()
        )
        val placement = bodyOf(codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayout.kt")), "gridPlacementOf")
        assertTrue(
            "gridPlacementOf 的颗数守卫必须保留（它是正确性哨兵；不许改成容错排列掩盖状态失联）",
            KotlinSourceScan.occurrences(placement, "require(items.size == childSizes.size)").isNotEmpty()
        )
    }

    @Test
    fun rulerItselfCanTurnRed() {
        // 良品先绿，mutate 后各钉必红（写法对照 EvCrashGuardTest.rulerItselfCanTurnRed）
        val src = codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt"))
        val body = bodyOf(src, "HudEntryItem")
        assertTrue(KotlinSourceScan.occurrences(body, "Box(modifier = modifier)").isNotEmpty())
        assertFalse(KotlinSourceScan.flatten(body).contains(", modifier = modifier ) {"))

        // 退回旧形态之一：恒在 Box 被摘（钉 1 红）
        val noBox = src.replace("Box(modifier = modifier)", "Box(modifier = Modifier)")
        assertEquals(
            0, KotlinSourceScan.occurrences(bodyOf(noBox, "HudEntryItem"), "Box(modifier = modifier)").size
        )

        // 退回旧形态之二：AnimatedVisibility 重新自己当外层发射（负向钉红）
        val legacy = src.replace(
            "exit = fadeOut(motion.float) + slideOutVertically(motion.offset) { it / 3 }",
            "exit = fadeOut(motion.float) + slideOutVertically(motion.offset) { it / 3 }, modifier = modifier"
        )
        assertTrue(
            KotlinSourceScan.flatten(bodyOf(legacy, "HudEntryItem")).contains(", modifier = modifier ) {")
        )
    }
}
