package com.wotagei.cam

import com.wotagei.cam.core.CAPACITY_ROOM_FIXED_CHROME_DP
import com.wotagei.cam.core.capacityHoursText
import com.wotagei.cam.core.capacityLineText
import com.wotagei.cam.core.capacityNetRoomDp
import com.wotagei.cam.core.capacityTierText
import com.wotagei.cam.core.freeSpaceShort
import com.wotagei.cam.core.recordableText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶栏容量段读数（鸿蒙化第 1 条）：裸字节要换算成「96.2G · 22h39m」这种能直接读的形态。
 * 换算只吃调用方现取的总码率，所以码率也进断言，防止有人把档位写死进函数里。
 *
 * ## 10-01 文本实测化改造后的取位用例
 * 三档阈值不再是测试机真机字体度量常量（272/242 已删），改由调用方 TextMeasurer 实测两段候选
 * 文案宽传入 ⇒ **文案宽与净可用宽都是入参**（真机来源），本文件只在 JVM 上钉取位逻辑：
 * 余量是否真在挡、比较方向对不对、三档文案各是什么。
 *
 * 突变自检（10-01 实跑四条突变，全数被咬红；不许恒等全绿）：
 * - 删掉 `+ marginDp` ⇒ [tiersFollowMeasuredWidths]、[subHourTierCollapsesToFullOrCapacity]、
 *   [netRoomBridgesIntoTier] 三例红；
 * - `roomDp >= x` 写反成 `roomDp <= x` ⇒ 同样三例红（含"room 大到离谱走满档 / room=0 只剩容量"）；
 * - [CAPACITY_ROOM_FIXED_CHROME_DP] 被挪（37 → 50）⇒ [fixedChromeIsPinned] 红，
 *   且 [netRoomBridgesIntoTier] 的边界用**字面量 37f** 算，桥也跟着红；
 * - `capacityNetRoomDp` 的画幅段减号写反 ⇒ [netRoomBridgesIntoTier] 红。
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

    /** 小时档文案与满档同源（调用方量宽用的就是这串字），分钟截断只发生在 ≥1h 时 */
    @Test
    fun hoursTextDropsMinutesOnlyAfterAnHour() {
        assertEquals("96.2G · 22h", capacityHoursText(98_508L, 10_128_000))
        // 不足 1 小时没有可退的空间，与满档同形
        assertEquals("1.3G · 18m", capacityHoursText(1_368L, 10_128_000))
        assertEquals("0M · --m", capacityHoursText(0L, 10_128_000))
    }

    /**
     * 三档取位：文案宽是入参（真机上由 TextMeasurer 量），这里只钉判据本身。
     * mock 满档 120dp、小时档 90dp（差的那 30dp 就是「39m」那截分钟），余量 2dp。
     */
    @Test
    fun tiersFollowMeasuredWidths() {
        val bps = 10_128_000
        val full = 120f
        val hours = 90f
        // 满档：净宽刚好 = 文案宽 + 2dp 余量
        assertEquals("96.2G · 22h39m", capacityTierText(98_508L, bps, full + 2f, full, hours))
        // 差 0.1dp 也不许放满档（**删掉 +2f 余量这一条会红**）
        assertEquals("96.2G · 22h", capacityTierText(98_508L, bps, full + 1.9f, full, hours))
        // 小时档同一条判据
        assertEquals("96.2G · 22h", capacityTierText(98_508L, bps, hours + 2f, full, hours))
        assertEquals("96.2G", capacityTierText(98_508L, bps, hours + 1.9f, full, hours))
        // 比较方向：room 大到离谱必须是满档、room=0 必须只剩容量（**把 >= 写成 <= 这两条会红**）
        assertEquals("96.2G · 22h39m", capacityTierText(98_508L, bps, 10_000f, full, hours))
        assertEquals("96.2G", capacityTierText(98_508L, bps, 0f, full, hours))
        // 档位之间不许串档：room 够小时档但绝不够满档时，必须给「96.2G · 22h」而不是另一端
        assertEquals("96.2G · 22h", capacityTierText(98_508L, bps, (full + 2f + hours + 2f) / 2f, full, hours))
    }

    /**
     * 不足 1 小时时满档与小时档**同文同宽**（[capacityHoursText] 那条分支返回的就是满档文案）：
     * 中档那一支因此不可达，净宽够就给全文案、不够就只剩容量，不存在"半退档"的中间态。
     */
    @Test
    fun subHourTierCollapsesToFullOrCapacity() {
        val bps = 10_128_000
        val same = 96f // 两段候选文案内容一样，实测宽自然相等
        assertEquals("1.3G · 18m", capacityTierText(1_368L, bps, same + 2f, same, same))
        assertEquals("1.3G", capacityTierText(1_368L, bps, same + 1.9f, same, same))
        // 剩余 0 也不崩，照旧给占位文案
        assertEquals("0M · --m", capacityTierText(0L, bps, 999f, 60f, 60f))
    }

    /**
     * 固定件账目被钉住：与 `ui/HudLayer.kt` 的 TopSegment 8+8 + 分隔线 1 + 卡 2+2 + 容量段 8+8 逐项对应，
     * 谁挪了这个数就是把判据与真盒子改分家了（HudLayer 不在改动域，两处必须同改）。
     */
    @Test
    fun fixedChromeIsPinned() {
        assertEquals(37f, CAPACITY_ROOM_FIXED_CHROME_DP, 0f)
    }

    /**
     * 「计划→行为」桥：整条链 = 顶栏可用宽 − 顶栏右端预留 − 画幅段实测宽 − 固定件 → 与两段文案宽比。
     * 输入换的是**画幅段有多宽**（随字体/语种变），输出必须跟着退档 —— 纯恒等式测不出这件事，
     * 所以这里给三档跳跃 + 一条余量边界，任何一档算错都红。
     */
    @Test
    fun netRoomBridgesIntoTier() {
        val bps = 10_128_000
        val room = 294f // 样例值：竖屏 360dp 窗口 − 顶栏右端固定件 66dp（算式与机型无关，数只是入参）
        val full = 120f
        val hours = 90f
        fun labelOf(sizeTextDp: Float) = capacityTierText(
            98_508L, bps, capacityNetRoomDp(room, sizeTextDp), full, hours
        )
        // 画幅段 119dp ⇒ 净宽 138 ≥ 120+2 ⇒ 满档
        assertEquals("96.2G · 22h39m", labelOf(119f))
        // 画幅段长到 150dp ⇒ 净宽 107 ⇒ 退到小时档（固定件被误改成 60dp 时这一条会掉成只剩容量 → 红）
        assertEquals("96.2G · 22h", labelOf(150f))
        // 画幅段 200dp ⇒ 净宽 57 ⇒ 只剩容量
        assertEquals("96.2G", labelOf(200f))
        // 余量进桥后的边界：净宽恰好 = 满档文案宽 + 2dp 走满档，再窄 1dp 立刻退（**删 +2f 这条会红**）。
        // 这里故意写字面量 37f 而不是 CAPACITY_ROOM_FIXED_CHROME_DP：桥要能独立咬住固定件——
        // 常量被挪（37→50）时它算出的边界宽就与实现对不上，桥跟着红，不至于只剩"钉常量"一条防线
        val sizeAtEdge = room - 37f - (full + 2f)
        assertEquals("96.2G · 22h39m", labelOf(sizeAtEdge))
        assertEquals("96.2G · 22h", labelOf(sizeAtEdge + 1f))
        // 方向也钉在行为里：上面三档是"画幅段越宽 ⇒ 越退档"，把减号写反（画幅越宽净宽越大）时
        // 150/200 两条会变满档 ⇒ 红，不靠恒等式自证
        assertEquals("96.2G", labelOf(200f))
    }
}
