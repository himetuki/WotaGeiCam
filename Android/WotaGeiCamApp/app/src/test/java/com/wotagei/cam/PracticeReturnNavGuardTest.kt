package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 练习动线录成回程的**源码结构守卫**（2026-10-04 裁决：绕经媒体库再录成时，落点必须
 * 定向回对比页而不是只退一层；Y51 增补：保底裸弹必须有栈底守卫）。
 *
 * `onPracticeFinish` 是 lambda 不是 fun，`bodyOf` 锁不到函数体；改锁 MainActivity 里
 * 赋值处的**完整表达式**（注释已被遮蔽，断言只认代码字符）——删掉定向弹栈、保底回退、
 * 发起者核对或 `previousBackStackEntry` 守卫中任一段，或整体改回无差别
 * `popBackStack()` 旧形态，这里必红。
 */
class PracticeReturnNavGuardTest {

    private fun maskedMain(): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/MainActivity.kt"))

    /** 表达式跨行书写，结构断言统一按折叠空白后的文本匹配 */
    private fun flatMain(): String = KotlinSourceScan.flatten(maskedMain())

    @Test
    fun `录成回程必须定向弹回对比页`() {
        val main = flatMain()
assertTrue(
            "练习录成回程必须定向 popBackStack(WotaNav.COMPARE, false)：无差别 popBackStack() " +
                "只退一层，用户绕经媒体库再录成会落错层（数据不丢但人到媒体库）",
            main.contains("PracticeReturn.TO_COMPARE -> nav.popBackStack(WotaNav.COMPARE, false)")
        )
        assertTrue(
            "对比页在栈内必须用公开 API 的 route 查询（runCatching 兜住缺页），并取到条目本身",
            main.contains("val compareEntry = runCatching { nav.getBackStackEntry(WotaNav.COMPARE) }.getOrNull()")
        )
        assertTrue("决策输入取 compareEntry != null", main.contains("compareInStack = compareEntry != null"))
        assertFalse(
            "不许使用 @RestrictTo(LIBRARY_GROUP) 的 currentBackStack（Lint RestrictedApi 会报错）",
            main.contains("nav.currentBackStack.value")
        )
    }

    @Test
    fun `裸弹保底必须受返回栈非空守卫`() {
        val main = flatMain()
        assertTrue(
            "回程决策必须带上「栈上还有下层页」守卫（Y51）：根栈只剩当前页时裸弹会清空" +
                "返回栈，NavHost 无内容可画 = 整窗黑",
            main.contains("canPopOne = nav.previousBackStackEntry != null")
        )
        assertTrue(
            "对比页不在栈内时，普通弹栈只允许出现在 POP_ONE 分支（守卫通过后）",
            main.contains("PracticeReturn.POP_ONE -> nav.popBackStack()")
        )
        assertFalse(
            "不许把裸弹放在定向弹失败的无条件兜底里（旧形态 if (!nav.popBackStack(...)) nav.popBackStack()）",
            main.contains("if (!nav.popBackStack(WotaNav.COMPARE, false)) nav.popBackStack()")
        )
    }

    @Test
    fun `回程必须核对发起页身份`() {
        val main = flatMain()
        assertTrue(
            "回程必须取走并核对发起练习的对比页条目 id：过期回调（发起页已被别的动线弹掉）不动作",
            main.contains("val originId = ComparePractice.takeOrigin()")
        )
        assertTrue(
            "发起者失效时计划函数拿到的 originAlive 必须为 false，而不是被忽略",
            main.contains("val originAlive = originId == null || compareEntry?.id == originId")
        )
        assertTrue(
            "回程决策必须走纯计划函数（可桥测的单一真源）",
            main.contains("practiceReturnPlan(")
        )
        assertTrue(
            "计划函数三输入必须分别来自栈查询/发起者核对/返回栈非空判定",
            main.contains("originAlive = originAlive, canPopOne = nav.previousBackStackEntry != null")
        )
    }

    @Test
    fun `不许退回无差别弹栈旧形态`() {
        assertFalse(
            "onPracticeFinish 裸 popBackStack() 即为落错层缺陷本体",
            maskedMain().contains("onPracticeFinish = { nav.popBackStack() }")
        )
    }
}