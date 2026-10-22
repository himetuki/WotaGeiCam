package com.wotagei.cam

import android.content.SharedPreferences

/**
 * 真存取的假 SharedPreferences（int/long/float/string/bool 全实现）。
 *
 * 与 `SettingsDefaultsTest` 里的私有假件刻意分开：那个的 `getLong/getFloat` 恒返回默认值
 * （它只服务于「默认档套用」，从不依赖这两种存取），拿来验 [com.wotagei.cam.ui.ParamsPersist]
 * 的 float/long 往返会恒绿成假象。本件把每种 getter 都打到真实 map 上，坏类型**不**静默转默认
 * （`as?` 返回 null → 回默认值——真机手改 prefs 的脏数据也是这个语义）。
 *
 * [commitCount]/[applyCount] 可计数：用来钉「无差异不写盘」（flush 不许产生任何 commit）。
 */
class FakePrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    var commitCount = 0
    var applyCount = 0

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, def: String?): String? = map[key] as? String ?: def
    override fun getStringSet(key: String, def: MutableSet<String>?): MutableSet<String>? = def
    override fun getInt(key: String, def: Int): Int = map[key] as? Int ?: def
    override fun getLong(key: String, def: Long): Long = map[key] as? Long ?: def
    override fun getFloat(key: String, def: Float): Float = map[key] as? Float ?: def
    override fun getBoolean(key: String, def: Boolean): Boolean = map[key] as? Boolean ?: def
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
        override fun commit(): Boolean {
            commitCount++
            return true
        }

        override fun apply() {
            applyCount++
        }
    }

    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}
