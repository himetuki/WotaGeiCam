package com.wotagei.cam

import android.content.SharedPreferences
import com.wotagei.cam.ui.WotaSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 对比播放编排节拍设置（键 `compare_tick_ms`，r02）的读写行为单测：
 * 默认 200、范围 100–1000、越界脏值读侧钳回（手写 prefs 不炸）、写读回环一致。
 */
class CompareTickSettingTest {

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

    @Test
    fun `缺键默认200`() {
        assertEquals(200, WotaSettings.compareTickMs(FakePrefs()))
    }

    @Test
    fun `越界脏值钳回100到1000`() {
        val p = FakePrefs()
        p.edit().putInt(WotaSettings.KEY_COMPARE_TICK_MS, 50).apply()
        assertEquals(100, WotaSettings.compareTickMs(p))
        p.edit().putInt(WotaSettings.KEY_COMPARE_TICK_MS, 5000).apply()
        assertEquals(1000, WotaSettings.compareTickMs(p))
    }

    @Test
    fun `档位写读回环一致`() {
        val p = FakePrefs()
        WotaSettings.COMPARE_TICK_TIERS.forEach { tier ->
            p.edit().putInt(WotaSettings.KEY_COMPARE_TICK_MS, tier).apply()
            assertEquals(tier, WotaSettings.compareTickMs(p))
        }
        // 档位本身必须落在允许区间内且含默认档 200（设置页高亮与实际生效值才对得上）
        assertEquals(listOf(100, 200, 500, 1000), WotaSettings.COMPARE_TICK_TIERS)
    }
}
