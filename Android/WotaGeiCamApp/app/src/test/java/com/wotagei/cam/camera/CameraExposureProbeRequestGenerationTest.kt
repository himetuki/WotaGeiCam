package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Y01 行为测试：等号档探测的采样窗必须绑定**本探测自己提交出去的那一代 repeating 请求**。
 *
 * 只校验 session 不够：探测请求刚下发 / builder 刚重建时，同一会话里先前那支请求的在途回执
 * 仍会到达（`onCaptureCompleted` 回传的是当时提交的请求实例）。旧实现按 session 放行并逐帧
 * `framesSeen++`，这些旧回执会把重建前的帧算成热身、把旧请求的帧间隔混进新窗，测得错误的 Δ。
 *
 * 平台边界只记录/投递请求实例：`emitFrom(request, frame)` 模拟「旧请求迟到」；当前提交实例
 * 由生产 `submitRepeating` 交给平台，测试只从会话桩读取，不自行选择回调。
 */
class CameraExposureProbeRequestGenerationTest {

    @Test
    fun `旧请求迟到不得推进探测窗 本世代请求回执才计数`() = platform { p ->
        val h = manualProbeHarness(p)
        val session = p.repeating(h, highSpeed = false)

        // 首拍开窗：这一拍来自探测请求**之前**的在途请求（探测此刻还没提交等号请求），
        // 只负责开窗，不采时间戳
        p.frame(session, 1L)
        assertTrue("预览态手动等号档必须进入探测", p.field(h.engine, "exposureProbeActive") as Boolean)
        assertEquals("开窗首拍计入热身", 1, p.field(h.engine, "exposureProbeFramesSeen"))
        val preProbeRequest = p.requestOf(session)

        // 同会话的**旧请求**迟到：不属于最近一次提交，绝不能推进采样窗
        val stale = p.new("android.hardware.camera2.CaptureRequest")
        repeat(5) { p.frameFrom(session, stale, (it + 2).toLong()) }
        assertEquals(
            "旧请求回执不得计入探测窗（否则重建前的帧会被当成热身/采样）",
            1,
            p.field(h.engine, "exposureProbeFramesSeen")
        )
        assertTrue("被旧回执填充后探测仍须保持 active", p.field(h.engine, "exposureProbeActive") as Boolean)

        // 探测开窗后 markDirty ⇒ 生产代码提交带等号请求的新一代 repeating
        p.call(h.engine, "markDirty")
        p.runDelayed(h)
        val probeRequest = p.requestOf(session)
        assertTrue(
            "探测提交必须换代（新请求实例）",
            probeRequest !== preProbeRequest
        )

        // 本世代（探测）请求的回执正常推进（身份门不能把探测饿死）
        repeat(5) { p.frameFrom(session, probeRequest, (it + 10).toLong()) }
        assertEquals(
            "最近一次提交请求的回执必须推进采样窗",
            6,
            p.field(h.engine, "exposureProbeFramesSeen")
        )

        // 换代之后再喂旧请求回执：照样不得推进（身份门在整场探测内持续生效）
        repeat(2) { p.frameFrom(session, stale, (it + 20).toLong()) }
        p.frameFrom(session, preProbeRequest, 30L)
        assertEquals("换代后的旧回执同样不得推进", 6, p.field(h.engine, "exposureProbeFramesSeen"))
    }

    /**
     * 手动态 + 24fps 固定档 + 1/24 等号快门：三条探测准入全部成立（手动 / 纠偏自动 / 配置适用）。
     * 与 [CameraExposureProbeLifecycleTest] 同一套能力表口径。
     */
    private fun manualProbeHarness(p: CameraPlatformFixture): CameraPlatformFixture.Harness {
        p.publicIds("0")
        val cc = p.camera("0")
        p.call(cc, "put", "CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES", intRanges(p, 24 to 24))
        // RequestApplier 的测光矩形以 active array 为分母（零面积会 coerceIn(1,0) 抛 IAE，
        // applyRepeatingNow 会静默吞掉 ⇒ 本用例就永远拿不到新一代请求）。这里给平台一个真实画幅。
        val rect = p.type("android.graphics.Rect").getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).newInstance(0, 0, 640, 480)
        p.call(cc, "put", "SENSOR_INFO_ACTIVE_ARRAY_SIZE", rect)
        val h = p.engine(fps = 24)
        p.open(h, p.slot("0"))
        // 走真实 onConfigured 让 prepareBuilder 就位：后面 markDirty 才能真的提交「探测请求那一代」
        p.configured()
        val ae = p.call(h.params, "getAeMode") ?: error("WotaParams 没有 aeMode")
        p.call(ae, "setValue", p.type("com.wotagei.cam.core.AeMode").getField("MANUAL").get(null))
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
        val temporary = TemporaryFolder(File("build/tmp/camera-exposure-probe-generation-test").apply { mkdirs() })
        private val classes: File by lazy { CameraPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
