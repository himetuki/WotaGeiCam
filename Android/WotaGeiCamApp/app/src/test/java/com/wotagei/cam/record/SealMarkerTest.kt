package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R08 封口标记的**桥测**：只有「封口成功 + pending 行 + 发布失败」才落标记，其余组合清标记。
 *
 * 标记是"已知已封口、只差发布"的唯一持久证据：冷启救援据此把等待门槛从 1 小时缩到
 * [SealMarker.SEALED_RESCUE_MS]，但仍要求封口完整性（见 [rowFixDecision]）。录制/补弧两条
 * 发布链共用 [sealNeedsMarker]，避免一侧写一侧不写的口径分叉。
 */
class SealMarkerTest {

    @Test
    fun `只有封口成功且发布失败才需要标记`() {
        assertTrue("保留 + pending 行 + 未发布 = 待发布", sealNeedsMarker(keep = true, pendingRow = true, committed = false))
        assertFalse("发布成功不落标记", sealNeedsMarker(keep = true, pendingRow = true, committed = true))
        assertFalse("废片不落标记", sealNeedsMarker(keep = false, pendingRow = true, committed = false))
        assertFalse("File 输出没有 pending 行可等", sealNeedsMarker(keep = true, pendingRow = false, committed = false))
    }

    @Test
    fun `标记命名是同名旁路文件`() {
        assertEquals(".sealed", SealMarker.EXTENSION)
        assertEquals("a/b/VID_1.mp4.sealed", SealMarker.fileFor("a/b/VID_1.mp4").invariantSeparatorsPath)
        assertFalse("空路径不得当已封口", SealMarker.isSealed(null))
    }

    @Test
    fun `源码钉扎_录制封口与丢弃都走统一标记出口`() {
        val store = codeOnly(mainSourceText("record/VideoStore.kt"))
        val seal = bodyOf(store, "seal")
        assertTrue("发布结果必须经纯函数决定落/清标记", seal.contains("sealNeedsMarker("))
        assertTrue("待发布落标记", seal.contains("SealMarker.mark("))
        assertTrue("其余组合清旧标记", seal.contains("SealMarker.clear("))
        val discard = bodyOf(store, "discard")
        assertTrue("删行/删文件必须同时清标记（防已丢弃文件被标记为待发布）", discard.contains("SealMarker.clear("))
    }
}
