package com.wotagei.cam

import com.wotagei.cam.record.PendingName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * pending 临时名清洗（2026-10-07 命名 race 修复）的全表行为测试。
 *
 * 真机实证的烂名形态：`.pending-<id>-` 临时段被拼前缀烙进成片名
 * （`30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4`）。
 * 规则红线：正则钉住「.pending- + 纯数字 + -」形态，正常名字里含 "pending" 字样不受影响。
 */
class PendingNameTest {

    @Test
    fun `正常名原样返回`() {
        assertEquals("VID_20261007_215822_240.mp4", PendingName.stripPendingJunk("VID_20261007_215822_240.mp4"))
        assertEquals("30fto24f_VID_20261007_215822_240.mp4", PendingName.stripPendingJunk("30fto24f_VID_20261007_215822_240.mp4"))
    }

    @Test
    fun `裸临时名剥成原名`() {
        // commit 前 provider 侧的完整临时名形态（前缀段）
        assertEquals(
            "VID_20261007_215822_240.mp4",
            PendingName.stripPendingJunk(".pending-1791986302-VID_20261007_215822_240.mp4")
        )
    }

    @Test
    fun `带前缀的中缀临时名剥段保前缀`() {
        // 真机实证形态：前缀已拼上、临时段夹在中间
        assertEquals(
            "30fto24f_VID_20261007_215822_240.mp4",
            PendingName.stripPendingJunk("30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4")
        )
    }

    @Test
    fun `多个临时段全剥`() {
        assertEquals("VID_x.mp4", PendingName.stripPendingJunk(".pending-1-.pending-22-VID_x.mp4"))
        assertEquals("abc.mp4", PendingName.stripPendingJunk("a.pending-3-b.pending-45-c.mp4"))
    }

    @Test
    fun `非数字或无点形态不误伤`() {
        // id 段必须是纯数字：字母/混合形态不是 provider 的临时名
        assertEquals(".pending-abc-VID_x.mp4", PendingName.stripPendingJunk(".pending-abc-VID_x.mp4"))
        assertEquals(".pending-12ab-VID_x.mp4", PendingName.stripPendingJunk(".pending-12ab-VID_x.mp4"))
        // 没有 `.pending-` 前缀的 pending 字样是普通名字
        assertEquals("pending-123-VID_x.mp4", PendingName.stripPendingJunk("pending-123-VID_x.mp4"))
        assertEquals("my.pending-file.mp4", PendingName.stripPendingJunk("my.pending-file.mp4"))
    }

    @Test
    fun `空串与纯段输入`() {
        assertEquals("", PendingName.stripPendingJunk(""))
        assertEquals("", PendingName.stripPendingJunk(".pending-99-"))
    }

    @Test
    fun `commitRenameTarget单一出口`() {
        // 单一出口 = 前缀 + 剥净名：fetchedName 取到临时名也不怕
        assertEquals(
            "24fto24f_VID_x.mp4",
            PendingName.commitRenameTarget("24fto24f_", ".pending-7-VID_x.mp4")
        )
        assertEquals(
            "24fto24f_VID_x.mp4",
            PendingName.commitRenameTarget("24fto24f_", "VID_x.mp4")
        )
        // 等价于前缀 + stripPendingJunk（组合关系钉住，防止两套清洗规则漂移）
        assertEquals(
            "p_" + PendingName.stripPendingJunk(".pending-5-n.mp4"),
            PendingName.commitRenameTarget("p_", ".pending-5-n.mp4")
        )
    }
}
