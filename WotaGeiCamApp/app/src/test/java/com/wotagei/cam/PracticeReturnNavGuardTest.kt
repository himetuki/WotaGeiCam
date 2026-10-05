package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 练习动线录成回程的**源码结构守卫**（2026-10-04 裁决：绕经媒体库再录成时，落点必须
 * 定向回对比页而不是只退一层）。
 *
 * `onPracticeFinish` 是 lambda 不是 fun，`bodyOf` 锁不到函数体；改锁 MainActivity 里
 * 赋值处的**完整表达式**（注释已被遮蔽，断言只认代码字符）——删掉定向弹栈或保底回退
 * 任一段、或整体改回无差别 `popBackStack()` 旧形态，这里必红。
 */
class PracticeReturnNavGuardTest {

    private fun maskedMain(): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/MainActivity.kt"))

    @Test
    fun `录成回程必须定向弹回对比页且带保底`() {
        val main = maskedMain()
        assertTrue(
            "练习录成回程必须定向 popBackStack(WotaNav.COMPARE, false)：无差别 popBackStack() " +
                "只退一层，用户绕经媒体库再录成会落错层（数据不丢但人到媒体库）",
            main.contains("popBackStack(WotaNav.COMPARE, false)")
        )
        assertTrue(
            "定向弹栈必须带保底回退：对比页不在栈内时定向弹返回 false，须回普通弹栈兜底，" +
                "否则异常动线一次都退不出去",
            main.contains("if (!nav.popBackStack(WotaNav.COMPARE, false)) nav.popBackStack()")
        )
        assertFalse(
            "不许退回无差别弹栈旧形态（onPracticeFinish 裸 popBackStack() 即为落错层缺陷本体）",
            main.contains("onPracticeFinish = { nav.popBackStack() }")
        )
    }
}
