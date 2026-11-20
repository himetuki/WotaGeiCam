package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Y01 / R02 等号档曝光探测的**生命周期**行为测试：真实 Camera2Engine 字节码 + 平台边界桩。
 *
 * 三条纪律一次钉死：
 * 1. 拆会话必须清探测窗（新会话的帧间隔绝不能接旧窗计数）；
 * 2. 帧回执中断时，5s 超时必须由**独立计时任务**推进（帧回调不落 = 旧代码的超时分支永不执行）；
 * 3. 录制窗口（含 GPU 无 extraSurface 的整段）内绝不重新启动探测。
 *
 * 用 [CameraPlatformFixture] 的同步 Handler：post 立即执行、postDelayed 入队，测试显式
 * [CameraPlatformFixture.runDelayed] 推进超时任务；SystemClock 可由 [CameraPlatformFixture.clock] 拨动。
 */
class CameraExposureProbeLifecycleTest {

    @Test
    fun `拆会话清探测窗且新会话不沿用旧采样`() = platform { p ->
        val h = manualProbeHarness(p)
        val session = p.repeating(h, highSpeed = false)
        p.frame(session, 1L)
        assertTrue("预览态手动等号档必须进入探测", p.field(h.engine, "exposureProbeActive") as Boolean)

        p.call(h.engine, "stopPreview")
        assertFalse("拆会话后探测态必须清零（等号请求不许挂到停预览之后）",
            p.field(h.engine, "exposureProbeActive") as Boolean)
        assertEquals("探测键必须一并清零", null, p.field(h.engine, "exposureProbeKey"))
        assertEquals("旧窗采样必须清空", 0, p.field(h.engine, "exposureProbeFramesSeen"))

        // 重建会话后重新探测：帧计数从 1 起算，旧窗的采样不进新窗
        p.call(h.engine, "startPreview")
        val next = p.repeating(h, highSpeed = false)
        p.frame(next, 2L)
        assertTrue("新会话条件仍满足时必须重探", p.field(h.engine, "exposureProbeActive") as Boolean)
        assertEquals("新会话的探测窗必须从零采", 1, p.field(h.engine, "exposureProbeFramesSeen"))
    }

    @Test
    fun `帧回执中断时独立计时任务必须超时退出并进冷却`() = platform { p ->
        val h = manualProbeHarness(p)
        val session = p.repeating(h, highSpeed = false)
        p.frame(session, 1L)
        assertTrue(p.field(h.engine, "exposureProbeActive") as Boolean)
        // 帧回执的 SENSOR_TIMESTAMP 缺失（本夹具的 TotalCaptureResult 一律返回 null）：
        // 后续帧只会 framesSeen++ 后提前 return，旧实现里 5s 超时分支永远跑不到。
        repeat(3) { p.frame(session, (it + 2).toLong()) }
        assertTrue("停帧场景下探测仍必须保持 active（由独立计时兜底）",
            p.field(h.engine, "exposureProbeActive") as Boolean)

        p.clock(5_001L)
        p.runDelayed(h)

        assertFalse("独立超时任务必须把探测退出", p.field(h.engine, "exposureProbeActive") as Boolean)
        assertEquals("退出后必须进冷却，防死循环重试", 10_001L,
            (p.field(h.engine, "exposureProbeCooldownUntilMs") as Long).toLong())
    }

    @Test
    fun `录制窗口内必须立刻退出并严禁重新启动探测`() = platform { p ->
        val h = manualProbeHarness(p)
        val session = p.repeating(h, highSpeed = false)
        p.frame(session, 1L)
        assertTrue(p.field(h.engine, "exposureProbeActive") as Boolean)

        p.call(h.engine, "setRecordingWindowActive", true)
        assertFalse("录制窗口打开必须立刻退出探测（等号请求不能进录制）",
            p.field(h.engine, "exposureProbeActive") as Boolean)

        // 窗口内继续来帧：条件判据必须挡住，绝不能重新起探测
        p.clock(6_000L)
        repeat(5) { p.frame(session, (it + 2).toLong()) }
        assertFalse("录制窗口内帧回执不得重启探测", p.field(h.engine, "exposureProbeActive") as Boolean)
        assertEquals(null, p.field(h.engine, "exposureProbeKey"))

        // 关窗后（冷却已过）允许重探：窗口只挡窗口内，不是永久闩锁
        p.call(h.engine, "setRecordingWindowActive", false)
        p.frame(session, 9L)
        assertTrue("窗口关闭且冷却已过必须能重探", p.field(h.engine, "exposureProbeActive") as Boolean)
    }

    /**
     * 手动态 + 24fps 固定档 + 1/24 等号快门：三条探测准入全部成立（手动 / 纠偏自动 / 配置适用）。
     * 夹具的 `camera()` 只声明 [30,30]，这里把目标镜头的普通表改成 [24,24]，让 `fpsValue()` 与帧周期
     * 都是真实的 24fps，而不是靠「30fps 选 1/24」这种物理上不存在的组合凑出 applicable。
     */
    private fun manualProbeHarness(p: CameraPlatformFixture): CameraPlatformFixture.Harness {
        p.publicIds("0")
        val cc = p.camera("0")
        p.call(cc, "put", "CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES", intRanges(p, 24 to 24))
        val h = p.engine(fps = 24)
        p.open(h, p.slot("0"))
        val ae = p.call(h.params, "getAeMode") ?: error("WotaParams 没有 aeMode")
        p.call(ae, "setValue", p.type("com.wotagei.cam.core.AeMode").getField("MANUAL").get(null))
        // 1/24 = 41_666_666ns：WotaTiers.REQUIRED_SHUTTER_NS 之一，且 >= 24fps 帧周期（41_666_666）
        p.setParamValue(h.params, "Shutter", 41_666_666L)
        return h
    }

    private fun intRanges(p: CameraPlatformFixture, vararg ranges: Pair<Int, Int>): Any {
        val rangeType = p.type("android.util.Range")
        val array = java.lang.reflect.Array.newInstance(rangeType, ranges.size)
        ranges.forEachIndexed { index, (lo, hi) ->
            java.lang.reflect.Array.set(
                array, index,
                rangeType.getConstructor(Comparable::class.java, Comparable::class.java).newInstance(lo, hi)
            )
        }
        return array
    }

    private fun platform(block: (CameraPlatformFixture) -> Unit) {
        CameraPlatformFixture(classes, javaClass.classLoader!!).use(block)
    }

    companion object {
        @JvmField
        @ClassRule
        val temporary = TemporaryFolder(File("build/tmp/camera-exposure-probe-test").apply { mkdirs() })
        private val classes: File by lazy { CameraPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}