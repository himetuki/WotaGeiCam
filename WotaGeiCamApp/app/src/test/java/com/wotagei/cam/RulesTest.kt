package com.wotagei.cam

import com.wotagei.cam.camera.RequestApplier
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.media.nextColumnTier
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.eqFocalMm
import com.wotagei.cam.core.pickFpsRange
import com.wotagei.cam.record.BitratePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相机/录制纯函数规则单测（JVM，不需设备）。
 * 依赖 Android 框架的部分（MediaStore 入库、点按对焦超时、AB 打点、音频同步）走真机验证，见 docs/plan/07 第 2 节。
 */
class RulesTest {

    private val fpsRanges = listOf(RangeI(15, 30), RangeI(10, 30), RangeI(24, 24), RangeI(25, 25), RangeI(30, 30), RangeI(1, 60))

    @Test
    fun `固定帧率范围优先于可变范围`() {
        assertEquals(24, pickFpsRange(fpsRanges, 24)?.lo)
        assertEquals(24, pickFpsRange(fpsRanges, 24)?.hi)
        assertTrue(pickFpsRange(fpsRanges, 24)!!.exact)
        assertEquals(25, RequestApplier.pickFps(fpsRanges, 25, false)?.hi)
    }

    @Test
    fun `无固定档时回退到包含目标的上限最大范围并标近似`() {
        val pick = pickFpsRange(fpsRanges, 50)
        assertTrue(pick != null && !pick.exact)
        // 24/25 之外的档位只有 [1,60] 能覆盖，回退后必须显式告知近似
        assertEquals(60, pick!!.hi)
        // 完全不被任何范围覆盖的高帧率：返回空，由 UI 灰显该档而非静默近似
        assertNull(RequestApplier.pickFps(listOf(RangeI(10, 30)), 120, false))
    }

    @Test
    fun `快门受帧率与防手抖双钳制`() {
        val shutter24 = WotaTiers.NS_PER_SECOND / 24
        // 1/24 快门在 24fps 下等于整帧周期，会被压到略小于 1/fps
        val clamped = RequestApplier.clampShutterNs(shutter24, 24, 1_000L, 1_000_000_000L)
        assertTrue(clamped <= WotaTiers.NS_PER_SECOND / 24)
        // 1s 快门在 25fps 下必须被压到 1/25 以内
        assertTrue(RequestApplier.clampShutterNs(1_000_000_000L, 25, 1_000L, 1_000_000_000L) <= WotaTiers.NS_PER_SECOND / 25)
        // 1/10 防手抖上限独立于帧率生效
        assertTrue(RequestApplier.clampShutterNs(500_000_000L, 10, 1_000L, 1_000_000_000L) <= WotaTiers.SHUTTER_CAP_NS)
        assertEquals(RequestApplier.frameDurationNs(25), WotaTiers.NS_PER_SECOND / 25)
    }

    @Test
    fun `色温增益方向符合白平衡语义`() {
        // 2000K 钨丝灯偏橙，需补蓝压红；10000K 蓝天光偏蓝，需补红压蓝
        val tungsten = RequestApplier.kelvinTintToRgb255(2000, 0)
        val blueSky = RequestApplier.kelvinTintToRgb255(10000, 0)
        assertTrue(tungsten[2] > tungsten[0])
        assertTrue(blueSky[0] > blueSky[2])
        // 蓝增益随色温单调下降，红增益单调上升
        assertTrue(tungsten[2] > blueSky[2])
        assertTrue(tungsten[0] < blueSky[0])
        // tint 轴：负值抬绿、正值压绿（品红侧）
        val neutral = RequestApplier.kelvinTintToRgb255(5500, 0)
        val green = RequestApplier.kelvinTintToRgb255(5500, -50)
        val magenta = RequestApplier.kelvinTintToRgb255(5500, 50)
        assertTrue(green[1] >= neutral[1])
        assertTrue(magenta[1] <= neutral[1])
        // 0–255 域线性映射到 1.0–3.0 增益域
        assertEquals(1f, RequestApplier.gain255ToUnit(0f), 0.001f)
        assertEquals(3f, RequestApplier.gain255ToUnit(255f), 0.001f)
    }

    @Test
    fun `码率三段式与高帧率翻倍`() {
        assertEquals(8_000_000, BitratePolicy.autoTable(1280, 720, 30))
        assertEquals(16_000_000, BitratePolicy.autoTable(1920, 1080, 30))
        assertEquals(32_000_000, BitratePolicy.autoTable(3840, 2160, 30))
        assertEquals(64_000_000, BitratePolicy.autoTable(3840, 2160, 60))
        // 用户自设档一律原样尊重（产品档位最高 50M，不存在越界自设）
        assertEquals(20_000_000, BitratePolicy.resolve(20_000_000, 1920, 1080, 30, false))
        assertEquals(35_000_000, BitratePolicy.resolve(35_000_000, 7680, 4320, 30, false))
        // 自动/兜底两段被产品最高档钳住：8K 的公式值 199M 会被手机编码器拒
        assertTrue(BitratePolicy.resolve(0, 7680, 4320, 30, false) <= WotaTiers.BITRATES.maxOrNull()!!)
        // 1440P 落在 1080P 带（分界取相邻参考分辨率的几何中间值）；8K 超出表 → 交给兜底公式
        assertEquals(16_000_000, BitratePolicy.autoTable(2560, 1440, 30))
        assertEquals(0, BitratePolicy.autoTable(7680, 4320, 30))
        assertTrue(BitratePolicy.resolve(0, 7680, 4320, 30, false) > 0)
        // HEVC 0.9 折算
        assertTrue(BitratePolicy.resolve(0, 1920, 1080, 30, true) < BitratePolicy.resolve(0, 1920, 1080, 30, false))
    }

    @Test
    fun `等效焦距公式`() {
        // 全画幅 36×24 传感器上的 50mm 镜头 ≈ 50mm 等效
        assertEquals(50f, eqFocalMm(36f, 24f, 50f), 0.01f)
        // 1/2.3 英寸（6.16×4.62）上 4.25mm ≈ 24mm 广角端
        assertEquals(24.4f, eqFocalMm(6.16f, 4.62f, 4.25f), 0.6f)
    }

    @Test
    fun `常驻 HUD 位掩码可持久化往返`() {
        // 默认档必须是「快门 / 帧率 / 码率」三项，且掩码能原样读回（存的是 SharedPreferences 里的 Int）
        assertEquals(3, HudItem.typesOf(HudItem.DEFAULT_MASK).size)
        assertEquals(HudItem.DEFAULT_MASK, HudItem.maskOf(HudItem.typesOf(HudItem.DEFAULT_MASK)))
        assertEquals(emptyList<HudItem>(), HudItem.typesOf(0))
        val all = HudItem.ALL.fold(0) { acc, t -> acc or t.bit }
        assertEquals(all, HudItem.maskOf(HudItem.ALL))
    }

    @Test
    fun `相册列数按 2-3-4-5 循环且越界回首档`() {
        assertEquals(3, nextColumnTier(2))
        assertEquals(4, nextColumnTier(3))
        assertEquals(5, nextColumnTier(4))
        assertEquals(2, nextColumnTier(5))
        // 旧持久化数据里的非法值不能卡死循环
        assertEquals(2, nextColumnTier(1))
        assertEquals(2, nextColumnTier(9))
    }
}
