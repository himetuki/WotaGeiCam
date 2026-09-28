package com.wotagei.cam

import android.content.SharedPreferences
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.ui.WotaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设置页改默认档 → 录制页首帧套用」的 JVM 单测（#20 的核心命题）。
 *
 * 原来这条链只能靠真机「改设置→杀进程→重进」来验，因为 `applyDefaultsOnce` 直接收 Context。
 * 现把它拆成 `applyDefaults(prefs, params)` + 一层 Context 包装，就能用假 prefs 在 JVM 上真跑一遍：
 * 套没套上、越界有没有钳、坏值会不会抛，全部有断言而不是"看起来对"。
 */
class SettingsDefaultsTest {

    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String, def: String?): String? = map[key] as String? ?: def
        override fun getStringSet(key: String, def: MutableSet<String>?): MutableSet<String>? = def
        override fun getInt(key: String, def: Int): Int = (map[key] as Int?) ?: def
        override fun getLong(key: String, def: Long): Long = def
        override fun getFloat(key: String, def: Float): Float = def
        override fun getBoolean(key: String, def: Boolean): Boolean = (map[key] as Boolean?) ?: def
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { map[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) = apply { map[key] = value }
            override fun putInt(key: String, value: Int) = apply { map[key] = value }
            override fun putLong(key: String, value: Long) = apply { map[key] = value }
            override fun putFloat(key: String, value: Float) = apply { map[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
            override fun remove(key: String) = apply { map.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() { Unit }
        }

        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private fun params() = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `没改过设置时套用的是出厂默认`() {
        val p = params()
        WotaSettings.applyDefaults(FakePrefs(), p)
        assertEquals(25, p.fps.value.value)
        assertEquals(40_000_000L, p.shutter.value.value)
        assertEquals(10_000_000, p.bitrate.value)
        assertEquals(WotaTiers.DEFAULT_SAMPLE_RATE, p.sampleRate.value)
        assertEquals(RenderMode.GPU, p.renderMode.value)
        assertEquals(0, p.refLines.value)
        assertTrue(p.levelEnabled.value)
        assertTrue(p.curve.value.isPassthrough)
        assertTrue("出厂应带音轨", p.audioEnabled.value)
    }

    @Test
    fun `改过的默认档逐条落到参数总线`() {
        val prefs = FakePrefs()
        prefs.map[WotaSettings.KEY_DEFAULT_FPS] = 50
        prefs.map[WotaSettings.KEY_DEFAULT_SHUTTER_NS] = 20_000_000
        prefs.map[WotaSettings.KEY_DEFAULT_BITRATE] = 25_000_000
        prefs.map[WotaSettings.KEY_DEFAULT_SAMPLE_RATE] = 44_100
        prefs.map[WotaSettings.KEY_DEFAULT_RENDER] = RenderMode.DIRECT.name
        prefs.map[WotaSettings.KEY_DEFAULT_REF_LINES] = 5
        prefs.map[WotaSettings.KEY_LEVEL_ENABLED] = false
        prefs.map[WotaSettings.KEY_DEFAULT_AUDIO_MUTE] = true
        prefs.map[WotaSettings.KEY_CURVE_STACK] = "0.000:0.100,1.000:0.900;;;"
        val p = params()
        WotaSettings.applyDefaults(prefs, p)
        assertEquals(50, p.fps.value.value)
        assertEquals(20_000_000L, p.shutter.value.value)
        assertEquals(25_000_000, p.bitrate.value)
        assertEquals(44_100, p.sampleRate.value)
        assertEquals(RenderMode.DIRECT, p.renderMode.value)
        assertEquals(5, p.refLines.value)
        assertFalse(p.levelEnabled.value)
        assertFalse("曲线没被套用", p.curve.value.isPassthrough)
        assertEquals(0.1f, p.curve.value.master.evaluate(0f), 1e-3f)
        assertFalse("静音初始值没落到总线", p.audioEnabled.value)
    }

    @Test
    fun `套用只改值不破坏参数状态位`() {
        // ParamState 的 range/enabled 由能力换挡管；套用走 copy(value=) 所以必须原样留着
        val p = params()
        WotaSettings.applyDefaults(FakePrefs(), p)
        assertTrue(p.shutter.value.enabled)
        assertTrue(p.iso.value.enabled)
        assertEquals(0, p.ev.value.value)
        // range/enabled 属于能力换挡的结果，套用不能把它们抹掉
        assertTrue(p.ev.value.range == null)
    }

    @Test
    fun `帧率越界钳回产品档位区间`() {
        val high = FakePrefs()
        high.map[WotaSettings.KEY_DEFAULT_FPS] = 9999
        val pHigh = params()
        WotaSettings.applyDefaults(high, pHigh)
        assertEquals(WotaTiers.FPS.last(), pHigh.fps.value.value)

        val low = FakePrefs()
        low.map[WotaSettings.KEY_DEFAULT_FPS] = 1
        val pLow = params()
        WotaSettings.applyDefaults(low, pLow)
        assertEquals(WotaTiers.FPS.first(), pLow.fps.value.value)
    }

    @Test
    fun `坏值不抛异常只回落默认`() {
        val prefs = FakePrefs()
        prefs.map[WotaSettings.KEY_CURVE_STACK] = "这不是曲线串"
        prefs.map[WotaSettings.KEY_DEFAULT_RENDER] = "???"
        val p = params()
        WotaSettings.applyDefaults(prefs, p)
        assertEquals(RenderMode.GPU, p.renderMode.value)
        assertTrue(p.curve.value.isPassthrough)
        assertEquals(CurveStack.IDENTITY, p.curve.value)
    }
}
