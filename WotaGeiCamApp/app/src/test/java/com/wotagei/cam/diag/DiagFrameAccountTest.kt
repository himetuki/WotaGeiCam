package com.wotagei.cam.diag

import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断导出正文里的结构帧账段（2026-10-17）：帧账与 `.drops.json` 段摘要必须能被用户导出看到。 */
class DiagFrameAccountTest {

    @Test
    fun `空帧账也留段与占位说明`() {
        val s = diagFrameAccountSection(emptyList())
        assertTrue(s.contains("=== 录制帧账 ==="))
        assertTrue(s.contains("本次会话没有段收尾帧账"))
        assertTrue(s.contains("=== 录制帧账结束 ==="))
    }

    @Test
    fun `帧账行逐条进导出正文且保持顺序`() {
        val s = diagFrameAccountSection(
            listOf(
                "帧账 part=0 相机到达=100",
                "会话段清单(与.drops.json同源) 段数=1 [段0 first=0 count=72 lost=0ms]"
            )
        )
        val a = s.indexOf("帧账 part=0")
        val b = s.indexOf("会话段清单")
        assertTrue("段帧账行必须进正文", a >= 0)
        assertTrue("会话段清单必须进正文", b >= 0)
        assertTrue("正文顺序必须与记录顺序一致", a < b)
    }
}
