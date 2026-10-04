package com.wotagei.cam

import com.wotagei.cam.player.ClipRule
import com.wotagei.cam.record.ArcDropLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 剪辑导出纯逻辑（ClipRule）单测：入出点规范化、sidecar 平移重映射、关键帧位次反推、命名。
 * 拷贝本体（MediaExtractor/MediaMuxer）依赖 Android framework，真机验收归真机验证清单。
 */
class ClipExportTest {

    // region normalize：入出点规范化

    @Test
    fun `normalize 中段区间原样通过`() {
        assertEquals(1000L to 4000L, ClipRule.normalize(1000L, 4000L, 10_000L))
    }

    @Test
    fun `normalize 起点负值钳到0`() {
        assertEquals(0L to 4000L, ClipRule.normalize(-500L, 4000L, 10_000L))
    }

    @Test
    fun `normalize 终点超片长钳到片长`() {
        assertEquals(1000L to 10_000L, ClipRule.normalize(1000L, 99_999L, 10_000L))
    }

    @Test
    fun `normalize 保留段不足下限判坏`() {
        assertNull(ClipRule.normalize(1000L, 1000L + ClipRule.MIN_KEEP_MS - 1, 10_000L))
    }

    @Test
    fun `normalize 入出点颠倒判坏`() {
        assertNull(ClipRule.normalize(4000L, 1000L, 10_000L))
    }

    @Test
    fun `normalize 零长片判坏`() {
        assertNull(ClipRule.normalize(0L, 1000L, 0L))
    }

    @Test
    fun `normalize 恰好等于下限通过`() {
        assertEquals(
            0L to ClipRule.MIN_KEEP_MS,
            ClipRule.normalize(0L, ClipRule.MIN_KEEP_MS, 10_000L)
        )
    }

    // endregion

    // region remapDrops：sidecar 平移（n 是增量，平移后原样保留）

    private fun log(vararg pairs: Pair<Int, Int>) = ArcDropLog("mend", 24, pairs.toList())

    @Test
    fun `remap shift为0时原样保留`() {
        val src = log(1 to 1, 6 to 1)
        assertEquals(log(1 to 1, 6 to 1), ClipRule.remapDrops(src, 0))
    }

    @Test
    fun `remap shift之前位次丢弃之后的平移`() {
        val src = log(1 to 1, 6 to 1, 9 to 2)
        // shift=3：k=1 随被剪画面丢弃；k=6→3、k=9→6，n 不动
        assertEquals(log(3 to 1, 6 to 2), ClipRule.remapDrops(src, 3))
    }

    @Test
    fun `remap 恰好在shift上的账目保留`() {
        val src = log(5 to 2)
        assertEquals(log(0 to 2), ClipRule.remapDrops(src, 5))
    }

    @Test
    fun `remap 全部落在shift之前清空账目`() {
        assertEquals(log(), ClipRule.remapDrops(log(1 to 1, 4 to 3), 10))
    }

    @Test
    fun `remap 空账目平移仍为空且mode与dstFps不变`() {
        assertEquals(ArcDropLog("mend", 25, emptyList()), ClipRule.remapDrops(ArcDropLog("mend", 25, emptyList()), 7))
    }

    @Test
    fun `remap 往返经encode-decode账目一致`() {
        // 桥测：平移结果要能原样走一遍真实 sidecar 的落盘格式（encode → decode）
        val remapped = ClipRule.remapDrops(log(2 to 1, 5 to 1, 11 to 3), 2)
        val round = ArcDropLog.decode(remapped.encode())
        assertEquals(remapped, round)
    }

    // endregion

    // region keyFrameIndexOf：抽帧成片 PTS = 帧序号×1e6/dstFps 的闭式反推

    @Test
    fun `keyIndex 片头为0`() {
        assertEquals(0, ClipRule.keyFrameIndexOf(0L, 24))
    }

    @Test
    fun `keyIndex 整秒精确反推`() {
        assertEquals(48, ClipRule.keyFrameIndexOf(2_000_000L, 24))
        assertEquals(50, ClipRule.keyFrameIndexOf(2_000_000L, 25))
    }

    @Test
    fun `keyIndex 非整点位次四舍五入`() {
        // 1_999_896us × 24fps = 47.997504 帧 → 48
        assertEquals(48, ClipRule.keyFrameIndexOf(1_999_896L, 24))
        // 29.5 帧 → 30（四舍五入进位）
        assertEquals(30, ClipRule.keyFrameIndexOf(1_229_167L, 24))
    }

    // endregion

    // region clipNameOf：剪辑成片命名（用户 2026-10-04 定版：edit_ 前缀）

    @Test
    fun `name 常规mp4加edit前缀`() {
        assertEquals("edit_VID_20261004_120000_001.mp4", ClipRule.clipNameOf("VID_20261004_120000_001.mp4"))
    }

    @Test
    fun `name 无扩展名直接加前缀`() {
        assertEquals("edit_take3.mp4", ClipRule.clipNameOf("take3"))
    }

    @Test
    fun `name 隐藏文件按无扩展名处理`() {
        // dot 在 0 位不算扩展名分隔（stem 不能空）
        assertEquals("edit_.hidden.mp4", ClipRule.clipNameOf(".hidden"))
    }

    // endregion
}
