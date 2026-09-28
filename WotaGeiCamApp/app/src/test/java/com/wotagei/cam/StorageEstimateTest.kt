package com.wotagei.cam

import com.wotagei.cam.record.StorageEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏容量胶囊的「约可录多久」估算（§37 第 4 条）。
 *
 * 纯函数所以能钉死边界：非法码率/零容量不能算出负数或除零，
 * 单位切换（分钟↔小时）与「100+」封顶也不能靠肉眼看图。
 */
class StorageEstimateTest {

    @Test
    fun `10Mbps 下 1GB 约 14 分钟`() {
        // 1024MB = 1.0737e9 字节，×8 / 10Mbps = 858.99 秒 = 14.31 分钟，向下取整 14
        assertEquals(14L, StorageEstimate.minutesOf(1024L, 10_000_000))
    }

    @Test
    fun `非法输入一律给 0 而不是负数或异常`() {
        assertEquals(0L, StorageEstimate.minutesOf(0L, 10_000_000))
        assertEquals(0L, StorageEstimate.minutesOf(-5L, 10_000_000))
        assertEquals(0L, StorageEstimate.minutesOf(1024L, 0))
        assertEquals(0L, StorageEstimate.minutesOf(1024L, -1))
    }

    @Test
    fun `码率翻倍则可拍时长减半`() {
        val base = StorageEstimate.minutesOf(200_000L, 10_000_000)
        val doubled = StorageEstimate.minutesOf(200_000L, 20_000_000)
        assertTrue("翻倍后没近似减半：$base vs $doubled", kotlin.math.abs(base / 2 - doubled) <= 1L)
    }

    @Test
    fun `单位切换与封顶`() {
        assertEquals("45", StorageEstimate.humanize(45L))
        assertFalse(StorageEstimate.unitOf(45L))
        assertEquals("1.5", StorageEstimate.humanize(90L))
        assertTrue(StorageEstimate.unitOf(90L))
        assertEquals("100+", StorageEstimate.humanize(9_000L))
        assertEquals("0", StorageEstimate.humanize(0L))
    }
}
