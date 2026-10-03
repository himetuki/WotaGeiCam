package com.wotagei.cam

import com.wotagei.cam.camera.RequestApplier
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.forcedShutterOutOfRange
import com.wotagei.cam.core.shutterCeilingNs
import com.wotagei.cam.ui.dialog.shutterItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「强制快门档」1/24、1/25 的口径 —— 与「强制 24/25 帧率」那条对称：
 * 设备 `SENSOR_INFO_EXPOSURE_TIME_RANGE` 里没有这两档时，它们照样可选、并按该值下发
 * （是否被 HAL 接受由设备决定）。
 *
 * 钉四件事（都是"删掉实现就红"的真断言，不是恒等式）：
 * 1. **判据**：超设备范围才叫强制档命中（UI 打 `※` 用），能力缺失不算；
 * 2. **桥**：`shutterCeilingNs` 的"抬到强制档 / 1/10s / ≤1/帧周期"三步**取最小**；
 * 3. **下发**：`clampShutterNs` 的 force 只豁免设备曝光范围，**不**豁免 1/10s 与 ≤1/帧周期；
 * 4. **同源**：档位可选性的帧周期口径 == 下发/回写的帧周期口径（实际生效的 fps 上界），
 *    不许出现"列表说可选、点下去被回写成别的档"的死档位。
 */
class ForcedShutterTest {

    /** 与帧率侧同源：档位纳秒值一律现算 1e9/分母，不写第二份字面量 */
    private val ns24 = 1_000_000_000L / 24   // 41_666_666
    private val ns25 = 1_000_000_000L / 25   // 40_000_000

    @Test
    fun `强制档就是产品必须的那两档`() {
        assertEquals(setOf(ns24, ns25), WotaTiers.REQUIRED_SHUTTER_NS)
        assertTrue(WotaTiers.isRequiredShutterNs(ns24))
        assertTrue(WotaTiers.isRequiredShutterNs(ns25))
        assertFalse(WotaTiers.isRequiredShutterNs(1_000_000_000L / 30))
        // 1/10s 防手抖上限比这两档更长，"强制"才谈得上（否则它们本来就在范围内）
        assertTrue(ns24 < WotaTiers.SHUTTER_CAP_NS)
    }

    @Test
    fun `超出设备曝光范围才算强制档命中`() {
        // 设备曝光上限只到 1/30（33ms）⇒ 两档都在设备表外，UI 该打 ※
        val shortRange = RangeI(4_000_000, 33_000_000)
        assertTrue(forcedShutterOutOfRange(ns24, shortRange))
        assertTrue(forcedShutterOutOfRange(ns25, shortRange))
        // 设备表里本来就有 ⇒ 不打标（与帧率侧"只有近似才打 ※"同一条）
        assertFalse(forcedShutterOutOfRange(ns24, RangeI(4_000_000, 100_000_000)))
        // 非强制档永远不打标：1/30 在不在设备范围里都不是"强制快门档"的事
        assertFalse(forcedShutterOutOfRange(1_000_000_000L / 30, shortRange))
        // 能力缺失（closed）时不算超范围：那台设备整条手动曝光都不可用，另有灰显路径
        assertFalse(forcedShutterOutOfRange(ns24, RangeI.CLOSED))
    }

    @Test
    fun `设备没有该档时上限被抬到强制档`() {
        // 设备上限只到 1/30（33ms），但 24fps 的帧周期（41.67ms）容得下 ⇒ 上限抬到 1/24
        assertEquals(ns24, shutterCeilingNs(33_000_000L, 24))
        // 25fps 帧周期只有 40ms：1/25 可选、1/24 被帧周期挡在范围外（防丢帧刻意保留）
        assertEquals(ns25, shutterCeilingNs(33_000_000L, 25))
        // 设备上限本来够长时，真正定上限的是帧周期
        assertEquals(ns24, shutterCeilingNs(300_000_000L, 24))
        // 1/10s 防手抖上限独立生效（低帧率下帧周期比它更长）
        assertEquals(WotaTiers.SHUTTER_CAP_NS, shutterCeilingNs(4_000_000_000L, 10))
        // 高帧率把上限收紧到帧周期
        assertEquals(1_000_000_000L / 60, shutterCeilingNs(300_000_000L, 60))
    }

    @Test
    fun `强制档跳过设备范围钳制但不跳产品规则`() {
        // 设备曝光上限 33ms：不强制时 1/24 被夹回设备上限（旧行为，既有用例也是这条）
        assertEquals(33_000_000L, RequestApplier.clampShutterNs(ns24, 24, 4_000_000L, 33_000_000L))
        // 强制时按 1/24 原样下发（设备表外也认这个值）
        assertEquals(
            ns24,
            RequestApplier.clampShutterNs(ns24, 24, 4_000_000L, 33_000_000L, forceDeviceRange = true)
        )
        // 设备下限同样不许把强制档抬走（下限是设备最短曝光，远小于 1/24）
        assertEquals(
            ns25,
            RequestApplier.clampShutterNs(ns25, 25, 4_000_000L, 33_000_000L, forceDeviceRange = true)
        )
        // 1/10s 与 ≤1/帧周期两条产品规则**不豁免**：60fps 下 1/24 仍被压到 1/60
        assertEquals(
            1_000_000_000L / 60,
            RequestApplier.clampShutterNs(ns24, 60, 4_000_000L, 33_000_000L, forceDeviceRange = true)
        )
        // 默认形参保持旧行为：新增这一路不许改动既有调用点的语义
        assertEquals(ns24, RequestApplier.clampShutterNs(ns24, 24, 4_000_000L, 300_000_000L))
    }

    @Test
    fun `无精确档设备上强制档按档位帧周期下发`() {
        // [24,30] 可变范围（无固定 24 档，即帧率侧 ※ 档）：档位帧周期 41.67ms 落在合法域
        // [1/30, 1/24] 内 ⇒ r10 起帧周期按档位下发（HAL 节奏放到 24fps），
        // 强制 1/24 不再被 hi=30 的帧周期钳回 1/30（旧行为长曝光观感全失）。
        // exposurePlan 的 fps 源 = p.fps.value（档位），本用例钉住它下游的两条纯函数口径。
        assertEquals(ns24, RequestApplier.frameDurationNs(24))
        assertEquals(
            ns24,
            RequestApplier.clampShutterNs(ns24, 24, 4_000_000L, 33_000_000L, forceDeviceRange = true)
        )
        assertEquals(ns25, RequestApplier.frameDurationNs(25))
        assertEquals(
            ns25,
            RequestApplier.clampShutterNs(ns25, 25, 4_000_000L, 33_000_000L, forceDeviceRange = true)
        )
    }

    @Test
    fun `档位列表把设备表外的强制档标成可选并打标`() {
        val mark = "※"
        // 实际生效 fps 上界 24（帧周期 41.67ms）：设备表外也要能选到 1/24
        val short = shutterItems(RangeI(4_000_000, 33_000_000), fpsHi = 24, approxMark = mark)
        val t24 = short.first { it.value == ns24.toInt() }
        assertTrue("设备表外也要能选到 1/24", t24.supported)
        assertEquals("1/24$mark", t24.label)
        assertEquals("1/25$mark", short.first { it.value == ns25.toInt() }.label)
        // 设备表里本来就有锁定范围：同一档位不打标
        val full = shutterItems(RangeI(4_000_000, 100_000_000), fpsHi = 24, approxMark = mark)
        assertEquals("1/24", full.first { it.value == ns24.toInt() }.label)
        // 设备能力缺失（null）时也不打标，且不冒出"可选"的假象（整列灰显）
        val none = shutterItems(null, fpsHi = 24, approxMark = mark)
        assertEquals("1/24", none.first { it.value == ns24.toInt() }.label)
        assertFalse("能力缺失时 1/24 不许可选", none.first { it.value == ns24.toInt() }.supported)
    }

    /**
     * P2-①现场：fps≥25（或 24 退化成 [15,30]）且设备上限 < 41.67ms 时，
     * 1/24 被帧周期挡在范围外 ⇒ **灰显且不带 `※`**（旧实现会给出"灰显的 1/24※"）。
     */
    @Test
    fun `帧周期容不下时强制档灰显且不带标`() {
        val mark = "※"
        // 设备曝光上限 33ms、实际生效 fps 上界 30（无固定 [24,24]，退到 [15,30]）
        val at30 = shutterItems(RangeI(4_000_000, 33_000_000), fpsHi = 30, approxMark = mark)
        val t24 = at30.first { it.value == ns24.toInt() }
        assertFalse("30fps 帧周期 33.3ms 容不下 1/24，必须灰显", t24.supported)
        assertEquals("灰显档不许带 ※", "1/24", t24.label)
        // 25fps 帧周期 40ms 同样容不下 1/24；但 1/25 可选且因设备表外打 ※
        val at25 = shutterItems(RangeI(4_000_000, 33_000_000), fpsHi = 25, approxMark = mark)
        assertFalse("25fps 帧周期 40ms 容不下 1/24", at25.first { it.value == ns24.toInt() }.supported)
        val t25 = at25.first { it.value == ns25.toInt() }
        assertTrue("25fps 帧周期 40ms 容得下 1/25", t25.supported)
        assertEquals("1/25$mark", t25.label)
    }

    /**
     * P1现场：列表说可选 ⇒ `clampShutterToFrame`（引擎回写）不得把它回写成别的档。
     * 喂"实际 fps 上界 30"的入参：1/24 必须**要么灰显、要么选了不被回写**，二者自洽。
     * 若可选性退回"按用户档位算"（旧实现），这里会判成可选却仍被夹成 1/30 ⇒ 红。
     */
    @Test
    fun `列表说可选就不会被帧周期回写`() {
        val dev = RangeI(4_000_000, 33_000_000)
        val fpsHi = 30
        val t24 = shutterItems(dev, fpsHi, "※").first { it.value == ns24.toInt() }
        // 与 Camera2Engine.clampShutterToFrame 完全同一调用：force 只豁免设备范围，帧周期那刀照旧
        val clamped = RequestApplier.clampShutterNs(
            ns24, fpsHi, dev.lo.toLong(), dev.hi.toLong(),
            forceDeviceRange = WotaTiers.isRequiredShutterNs(ns24)
        )
        if (t24.supported) {
            assertEquals("列表说可选，回写却改了值：可选性与下发口径分裂", ns24, clamped)
        } else {
            assertFalse("列表灰显，回写却保留原值：口径不一致", clamped == ns24)
        }
    }

    /**
     * P1判据同源，可证伪：设备相同、只有 HAL 给的 fps 上界变，档位可选性必须跟着变。
     * 若判据退回"按用户档位算"（两个调用只差 fpsHi），两句会同时真或同时假 ⇒ 红。
     */
    @Test
    fun `档位可选性跟着实际 fps 上界变`() {
        val dev = RangeI(4_000_000, 33_000_000)
        val at24 = shutterItems(dev, fpsHi = 24, approxMark = "※").first { it.value == ns24.toInt() }
        val at30 = shutterItems(dev, fpsHi = 30, approxMark = "※").first { it.value == ns24.toInt() }
        assertTrue("24fps 帧周期 41.67ms 容得下 1/24", at24.supported)
        assertFalse("30fps 帧周期 33.3ms 容不下 1/24", at30.supported)
        assertTrue("两项必须分叉，否则判据没吃 fpsHi", at24.supported != at30.supported)
    }
}
