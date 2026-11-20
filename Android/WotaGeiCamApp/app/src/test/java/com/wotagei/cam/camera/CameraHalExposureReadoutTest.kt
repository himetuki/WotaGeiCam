package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * B01 行为测试：HUD 除「我方送出曝光」外还要有**HAL 结果侧独立读数**。
 *
 * `SENSOR_EXPOSURE_TIME` 在 `TotalCaptureResult` 里是 HAL 真正生效的曝光；设备范围夹取、
 * HAL 改写、等号档处理只体现在这里。请求侧读数只能证明"发了什么"，两者并排才能定位
 * "发的和跑的不一样"。采样按 500ms 节流（曝光随 AE 慢变），且本夹具的 `emitFrom(...)`
 * 可以逐拍指定结果值，用来验证节流不是逐帧刷新。
 */
class CameraHalExposureReadoutTest {

    @Test
    fun `HAL结果侧曝光独立发布且按500ms节流`() = platform { p ->
        p.publicIds("0")
        p.camera("0")
        val h = p.engine(fps = 30)
        p.open(h, p.slot("0"))
        val ae = p.call(h.params, "getAeMode") ?: error("WotaParams 没有 aeMode")
        p.call(ae, "setValue", p.type("com.wotagei.cam.core.AeMode").getField("MANUAL").get(null))
        // 10ms@30fps 不是等号档：探测不启动，用例只观察 HAL 结果侧读数
        p.setParamValue(h.params, "Shutter", 10_000_000L)
        val session = p.repeating(h, highSpeed = false)
        p.clock(1_000L)

        assertEquals("尚未观测到 HAL 结果前读数为 null", null, halReadout(p, h))
        val current = p.requestOf(session)
        p.frameWithResult(session, current, 1L, 1_000_000_000L, 38_000_000L)
        assertEquals("首个 HAL 结果必须发布实际生效曝光", 38_000_000L, halReadout(p, h))

        // 500ms 节流窗内：值变了也不刷新（逐帧读没有意义，还会增加装箱/总线写）
        p.clock(1_100L)
        p.frameWithResult(session, current, 2L, 1_033_000_000L, 20_000_000L)
        assertEquals("节流窗内不得刷新 HAL 读数", 38_000_000L, halReadout(p, h))

        p.clock(1_600L)
        p.frameWithResult(session, current, 3L, 1_066_000_000L, 20_000_000L)
        assertEquals("节流窗过后必须跟上 HAL 实际值", 20_000_000L, halReadout(p, h))
    }

    /** `params.actualHalExposureNs` StateFlow 的当前值（引擎回写、UI 只读）。 */
    private fun halReadout(p: CameraPlatformFixture, h: CameraPlatformFixture.Harness): Any? {
        val flow = p.call(h.params, "getActualHalExposureNs")
            ?: error("WotaParams 没有 actualHalExposureNs")
        return p.call(flow, "getValue")
    }

    private fun platform(block: (CameraPlatformFixture) -> Unit) {
        CameraPlatformFixture(classes, javaClass.classLoader!!).use(block)
    }

    companion object {
        @JvmField
        @ClassRule
        val temporary = TemporaryFolder(File("build/tmp/camera-hal-exposure-readout-test").apply { mkdirs() })
        private val classes: File by lazy { CameraPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
