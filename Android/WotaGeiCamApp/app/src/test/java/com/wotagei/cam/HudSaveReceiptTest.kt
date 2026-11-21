package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.savedAfterCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局保存回执桥（Y53）：只有 `commit()` 成功才推进「已保存」基线。
 */
class HudSaveReceiptTest {

    private val defaultTable = HudLayoutTable.default()

    @Test
    fun `写盘失败不推进已保存基线`() {
        val old = defaultTable
        val target = defaultTable.copy(zones = emptyMap())
        // 失败：基线保持旧值 → draft != saved 继续成立（未保存高亮保持、重试入口可用）
        assertEquals(old, savedAfterCommit(ok = false, saved = old, target = target))
        // 成功：基线推进到目标
        assertEquals(target, savedAfterCommit(ok = true, saved = old, target = target))
    }

    @Test
    fun `三个提交分支都按回执推进基线`() {
        val flat = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
        )
        assertTrue(
            "保存分支必须 saved = savedAfterCommit(ok, saved, draft)",
            flat.contains("saved = savedAfterCommit(ok, saved, draft)")
        )
        assertTrue(
            "重置分支必须 saved = savedAfterCommit(ok, saved, target)",
            flat.contains("saved = savedAfterCommit(ok, saved, target)")
        )
        assertTrue(
            "撤销分支必须 saved = savedAfterCommit(ok, saved, back)",
            flat.contains("saved = savedAfterCommit(ok, saved, back)")
        )
    }

    @Test
    fun `失败时撤销与重置武装不被熄灭`() {
        val flat = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
        )
        // 保存成功才清撤销串/重置武装；重置成功才记撤销串并放掉武装；撤销成功才清撤销串
        assertTrue("保存分支的 undoRaw/resetArmed 清理必须在 ok 内", flat.contains("if (ok) { undoRaw = null resetArmed = false }"))
        assertTrue("重置分支必须只在 ok 时记撤销串并放掉武装", flat.contains("if (ok) { undoRaw = prevRaw resetArmed = false }"))
        assertTrue("撤销分支必须只在 ok 时清撤销串", flat.contains("if (ok) undoRaw = null"))
    }

    @Test
    fun `不许退回无条件推进基线的旧形态`() {
        val flat = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
        )
        assertFalse("旧形态 saved = draft 必须消失", flat.contains("saved = draft"))
        assertFalse("旧形态 saved = back 必须消失", flat.contains("saved = back"))
    }
}