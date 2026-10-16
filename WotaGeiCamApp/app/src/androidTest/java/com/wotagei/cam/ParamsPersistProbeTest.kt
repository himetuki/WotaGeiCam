package com.wotagei.cam

import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.PeakingColor
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.ui.ParamsPersistSession
import com.wotagei.cam.ui.WotaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 配置持久化（用户反馈第 1 条）的**真机跨进程**取证（锁屏也能跑：`am instrument` 不需要亮屏）。
 *
 * ## JVM 用例为什么不够
 * `ParamsPersistSessionTest` 证的是"同一段内存里读写自洽"，`FakePrefs` 的 `commit()` 恒返回 true。
 * 它不知道真机上 `commit()` 到底返回什么，更不知道进程死了以后 `wota_settings`（这里是独立探针文件）
 * 还在不在。用户那句「不随后台清理而回归默认」断的就是**跨进程**，所以必须换一个进程再读一次。
 *
 * ## 手法：同一条测试类跑两次 `am instrument`
 * - 第一次只跑 `step1_writeProbe`：走**生产类** [ParamsPersistSession]（commit 落盘），本进程结束；
 * - 第二次只跑 `step2_readProbe`：**新进程、新 SharedPreferences 实例**，走生产恢复入口
 *   [WotaSettings.applyDefaults]，与**手写常数**逐项比对。
 *
 * ## 期望值全是编译进包里的手写常数
 * 不拿"写入进程算出来的结果"传给读取进程比，那等于与实现自比。23 项期望值逐字手写，
 * 编解码或默认表一变这里就该红。
 *
 * ## 不污染用户存档
 * 探针读写独立 prefs 文件 [PROBE_PREFS]，**不是** `wota_settings` ⇒ 用户的设置与录制参数不受影响；
 * `step3_cleanupProbe` 再把探针文件清干净并复验键已消失。**任何自动清理（@After）都不允许存在**——
 * 跨进程探针的一步一自动清理，`step2` 就会读到空文件、把失败假象栽给 app
 * （HudLayoutPersistProbeTest 踩过的坑，原样继承）。
 *
 * ## 取证输出
 * 本机 ROM 会丢应用自己的 Log 标签，所以逐行 `sendStatus` 打到 `am instrument` 的 stdout。
 */
@RunWith(AndroidJUnit4::class)
class ParamsPersistProbeTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun probePrefs(): SharedPreferences =
        instr.targetContext.getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE)

    private fun freshParams() = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    /** 第 1 步：用生产类写 23 项全非默认（AS IS：不跑 applyAbility，写什么就是什么） */
    @Test
    fun step1_writeProbe() {
        val bus = freshParams().apply {
            fps.value = fps.value.copy(value = FPS)
            shutter.value = shutter.value.copy(value = SHUTTER_NS)
            bitrate.value = BITRATE
            refLines.value = REF_MASK
            levelEnabled.value = false
            iso.value = iso.value.copy(value = ISO)
            aeMode.value = AeMode.MANUAL
            ev.value = ev.value.copy(value = -2)
            wbMode.value = WbPreset.DAYLIGHT
            kelvin.value = kelvin.value.copy(value = KELVIN)
            tint.value = tint.value.copy(value = TINT)
            zoom.value = zoom.value.copy(value = ZOOM)
            flash.value = Flash.AUTO
            stabilize.value = Stabilize.EIS
            afMode.value = AfMode.MANUAL
            manualFocusDiopter.value = manualFocusDiopter.value.copy(value = DIOPTER)
            requestSize(SIZE)
            frameEffect.value = FrameEffect.PEAKING
            zebraThreshold.value = ZEBRA_THRESHOLD
            zebraDensity.value = ZEBRA_DENSITY
            peakingStrength.value = PEAKING_STRENGTH
            peakingColor.value = PeakingColor.BLUE
            lens.value = LensType.TELEPHOTO
        }
        val session = ParamsPersistSession(probePrefs(), bus)
        line("STEP1_WRITE pid=${android.os.Process.myPid()}")
        assertTrue(
            "flush() 的 commit 返回 false ⇒ 真机上根本没落成盘",
            session.flush()
        )
        // 落盘值用手写常数逐键复验（不拿写方自算值比）
        val prefs = probePrefs()
        assertEquals("default_fps", FPS, prefs.getInt(WotaSettings.KEY_DEFAULT_FPS, -1))
        assertEquals("default_shutter_ns", SHUTTER_NS.toInt(), prefs.getInt(WotaSettings.KEY_DEFAULT_SHUTTER_NS, -1))
        assertEquals("default_iso", ISO, prefs.getInt(WotaSettings.KEY_DEFAULT_ISO, -1))
        assertEquals("default_ae_mode", "MANUAL", prefs.getString(WotaSettings.KEY_DEFAULT_AE_MODE, null))
        assertEquals("default_wb_preset", "DAYLIGHT", prefs.getString(WotaSettings.KEY_DEFAULT_WB_PRESET, null))
        assertEquals("default_size", "3840x2160", prefs.getString(WotaSettings.KEY_DEFAULT_SIZE, null))
        assertEquals("default_lens", "TELEPHOTO", prefs.getString(WotaSettings.KEY_DEFAULT_LENS, null))
        assertEquals("default_zoom", ZOOM, prefs.getFloat(WotaSettings.KEY_DEFAULT_ZOOM, -1f), 1e-6f)
        line("RAW_AF=${prefs.getString(WotaSettings.KEY_DEFAULT_AF_MODE, null)} RAW_SIZE=${prefs.getString(WotaSettings.KEY_DEFAULT_SIZE, null)}")
    }

    /** 第 2 步：**必须另跑一次 am instrument**（新进程），走生产恢复入口与手写常数比对 */
    @Test
    fun step2_readProbe() {
        val bus = freshParams()
        WotaSettings.applyDefaults(probePrefs(), bus)
        line("STEP2_READ pid=${android.os.Process.myPid()}")
        assertEquals(FPS, bus.fps.value.value)
        assertEquals(SHUTTER_NS, bus.shutter.value.value)
        assertEquals(BITRATE, bus.bitrate.value)
        assertEquals(REF_MASK, bus.refLines.value)
        assertFalse(bus.levelEnabled.value)
        assertEquals(ISO, bus.iso.value.value)
        assertEquals(AeMode.MANUAL, bus.aeMode.value)
        assertEquals(-2, bus.ev.value.value)
        assertEquals(WbPreset.DAYLIGHT, bus.wbMode.value)
        assertEquals(KELVIN, bus.kelvin.value.value)
        assertEquals(TINT, bus.tint.value.value)
        assertEquals(ZOOM, bus.zoom.value.value, 1e-6f)
        assertEquals(Flash.AUTO, bus.flash.value)
        assertEquals(Stabilize.EIS, bus.stabilize.value)
        assertEquals(AfMode.MANUAL, bus.afMode.value)
        assertEquals(DIOPTER, bus.manualFocusDiopter.value.value, 1e-6f)
        assertEquals(SIZE, bus.requestedSize)
        assertEquals(FrameEffect.PEAKING, bus.frameEffect.value)
        assertEquals(ZEBRA_THRESHOLD, bus.zebraThreshold.value, 1e-6f)
        assertEquals(ZEBRA_DENSITY, bus.zebraDensity.value)
        assertEquals(PEAKING_STRENGTH, bus.peakingStrength.value, 1e-6f)
        assertEquals(PeakingColor.BLUE, bus.peakingColor.value)
        assertEquals(LensType.TELEPHOTO, bus.lens.value)
        line("READ_OK all23")
    }

    /**
     * 第 3 步（**必须显式跑，不能用 @After**）：清空探针文件并复验键消失。
     */
    @Test
    fun step3_cleanupProbe() {
        val prefs = probePrefs()
        prefs.edit().clear().commit()
        assertNull("default_fps 还在", prefs.getString(WotaSettings.KEY_DEFAULT_FPS, null))
        assertNull("default_size 还在", prefs.getString(WotaSettings.KEY_DEFAULT_SIZE, null))
        assertFalse("探针文件没清干净", prefs.contains(WotaSettings.KEY_DEFAULT_LENS))
        line("STEP3_CLEAN")
    }

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, Bundle().apply { putString("wota", text) })
    }

    companion object {
        /** 独立探针文件，不是 `wota_settings` */
        private const val PROBE_PREFS = "wota_params_probe"

        // ---- 手写常数（写/读两边共用编译期真源；与 JVM 用例的 busyParams 同值以便对照）----
        private const val FPS = 60
        private const val SHUTTER_NS = 8_333_333L
        private const val BITRATE = 35_000_000
        private const val REF_MASK = 20        // 十字准线(4) | 安全区域(16)
        private const val ISO = 800
        private const val KELVIN = 4000
        private const val TINT = 12
        private const val ZOOM = 2.5f
        private const val DIOPTER = 1.5f
        private const val ZEBRA_THRESHOLD = 0.95f
        private const val ZEBRA_DENSITY = 3
        private const val PEAKING_STRENGTH = 0.75f
        private val SIZE = Size(3840, 2160)
    }
}
