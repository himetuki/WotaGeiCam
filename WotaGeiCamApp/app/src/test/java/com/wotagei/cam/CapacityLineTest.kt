package com.wotagei.cam

import com.wotagei.cam.core.capacityLineText
import com.wotagei.cam.core.capacityTierText
import com.wotagei.cam.core.freeSpaceShort
import com.wotagei.cam.core.recordableText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶栏容量段读数（鸿蒙化第 1 条）：裸字节要换算成「96.2G · 22h39m」这种能直接读的形态。
 * 换算只吃调用方现取的总码率，所以码率也进断言，防止有人把档位写死进函数里。
 */
class CapacityLineTest {

    @Test
    fun gigabytesWithHours() {
        assertEquals("96.2G", freeSpaceShort(98_508L))
        assertEquals("22h39m", recordableText(98_508L, 10_128_000))
        assertEquals("96.2G · 22h39m", capacityLineText(98_508L, 10_128_000))
    }

    @Test
    fun megabytesAndMinutes() {
        assertEquals("512M", freeSpaceShort(512L))
        assertEquals("18m", recordableText(1_368L, 10_128_000))
        assertEquals("<1m", recordableText(60L, 10_128_000))
    }

    @Test
    fun higherBitrateMeansLessTime() {
        // 同一块余量：5M 档 9h19m，50M 档只剩 55m —— 换算必须跟着当前码率走
        assertEquals("9h19m", recordableText(20_000L, 5_000_000))
        assertEquals("55m", recordableText(20_000L, 50_000_000))
    }

    @Test
    fun unknownInputsDoNotCrash() {
        assertEquals("--m", recordableText(0L, 10_128_000))
        assertEquals("--m", recordableText(1_000L, 0))
        assertEquals("0M · --m", capacityLineText(0L, 10_128_000))
    }

    @Test
    fun tiersFollowAvailableRoom() {
        // §74：竖屏 360dp 窗口扣掉顶栏固定预留 66dp 只剩 294dp → 走小时档；横屏 800dp 走满档
        assertEquals("96.2G · 22h39m", capacityTierText(98_508L, 10_128_000, 734f))
        assertEquals("96.2G · 22h", capacityTierText(98_508L, 10_128_000, 294f))
        assertEquals("96.2G", capacityTierText(98_508L, 10_128_000, 245f))
    }

    @Test
    fun subHourKeepsMinutesInHoursTier() {
        // 不足 1 小时时小时档没有可退的空间，与满档同形；再窄就只剩容量
        // 1368 MB 已过 1024 阈值，容量按 GB 形显示（freeSpaceShort 的规矩）
        assertEquals("1.3G · 18m", capacityTierText(1_368L, 10_128_000, 294f))
        assertEquals("1.3G", capacityTierText(1_368L, 10_128_000, 200f))
        assertEquals("0M · --m", capacityTierText(0L, 10_128_000, 734f))
    }
}
