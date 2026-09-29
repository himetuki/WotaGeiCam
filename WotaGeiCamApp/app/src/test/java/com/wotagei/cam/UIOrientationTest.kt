package com.wotagei.cam

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import com.wotagei.cam.core.UIOrientation
import com.wotagei.cam.ui.WotaSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 13 号计划第 6 条（B3 默认方向）的纯函数回归：设置档 → `ActivityInfo` 方向常量的映射，
 * 以及 `ui_orientation` 的读取与回落。
 *
 * 三条语义各钉一例，因为它们都是「看着差不多但真机上行为完全不同」的坑：
 * - 横屏档必须是 `SENSOR_LANDSCAPE` 而不是 `LANDSCAPE`：前者才允许 90↔270 随重力翻转（横竖都能录是既有需求 #21，
 *   且正反向横屏窗口尺寸不变、`onSizeChanged` 不触发，录制页另挂了 DisplayListener 才跟着转）；
 * - 非录制页必须仍是 `FULL_USER`：本设置只管录制页，不该越界把设置页/相册/播放器锁死；
 * - 缺键与坏串都回落到横屏：默认档写错方向是用户第一眼就能看见的缺陷。
 */
class UIOrientationTest {

    @Test
    fun `横屏档锁横屏但保留两个朝向自动翻转`() {
        val o = UIOrientation.screenOrientationOf(hudPage = true, orientation = UIOrientation.LANDSCAPE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, o)
        // 钉死「不是单方向锁」：锁成 LANDSCAPE / USER_LANDSCAPE / LOCKED 会让反向横屏（270°）录不了，
        // 且 90↔270 切换不再触发显示变化，CameraScreen 的 DisplayListener 那条路就白挂了
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, o)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, o)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, o)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, o)
    }

    @Test
    fun `竖屏档锁竖屏且保留两个朝向自动翻转`() {
        val o = UIOrientation.screenOrientationOf(hudPage = true, orientation = UIOrientation.PORTRAIT)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, o)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, o)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, o)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, o)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, o)
    }

    @Test
    fun `非录制页两档都跟随用户系统旋转设置`() {
        for (orientation in UIOrientation.ALL) {
            assertEquals(
                "$orientation 档下非录制页不该被锁方向",
                ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
                UIOrientation.screenOrientationOf(hudPage = false, orientation = orientation)
            )
        }
    }

    @Test
    fun `默认档是横屏使用`() {
        assertEquals(UIOrientation.LANDSCAPE, UIOrientation.DEFAULT)
        assertEquals(UIOrientation.LANDSCAPE, UIOrientation.fromPersistValue(null))
    }

    @Test
    fun `未知值与空串一律回落默认档且不抛`() {
        for (bad in listOf("", "???", "sensor_landscape", "upside_down", "横屏")) {
            assertEquals(bad, UIOrientation.DEFAULT, UIOrientation.fromPersistValue(bad))
        }
    }

    @Test
    fun `存值与枚举可往返且大小写不敏感`() {
        for (o in UIOrientation.ALL) {
            assertEquals(o, UIOrientation.fromPersistValue(o.persistValue))
            assertEquals(o, UIOrientation.fromPersistValue(o.persistValue.uppercase()))
        }
        // 契约串按 13 号计划第六节定死：小写 landscape / portrait
        assertEquals("landscape", UIOrientation.LANDSCAPE.persistValue)
        assertEquals("portrait", UIOrientation.PORTRAIT.persistValue)
    }

    // ---------------------------------------------------------- 持久化读取（杀进程重进仍生效的读侧）

    @Test
    fun `没写过的键读到默认横屏`() {
        assertEquals(UIOrientation.LANDSCAPE, WotaSettings.uiOrientation(StringPrefs()))
    }

    @Test
    fun `写进 prefs 的档位读得回来`() {
        val prefs = StringPrefs()
        WotaSettings.setUiOrientation(prefs, UIOrientation.PORTRAIT)
        assertEquals(UIOrientation.PORTRAIT, WotaSettings.uiOrientation(prefs))
        // 落的是契约串，不是枚举 name
        assertEquals("portrait", prefs.map[WotaSettings.KEY_UI_ORIENTATION])
        // 键名即契约（13 号计划第六节）：改名要连累存量用户的设置，这里钉住
        assertEquals("ui_orientation", WotaSettings.KEY_UI_ORIENTATION)
    }

    @Test
    fun `坏串写进 prefs 也只回落默认不抛`() {
        val prefs = StringPrefs()
        prefs.map[WotaSettings.KEY_UI_ORIENTATION] = "follow_sensor"
        assertEquals(UIOrientation.LANDSCAPE, WotaSettings.uiOrientation(prefs))
    }

    /**
     * 只要 `getString` / `edit().putString` / 两个监听器注册口：[WotaSettings.uiOrientation] 与
     * `setUiOrientation` 只碰这些。与 [SettingsDefaultsTest] 里的假 prefs 同一手法，
     * 目的是让「设置 → 落盘 → 重进程读回」这条链在 JVM 上就有断言，不必全靠真机杀进程。
     */
    private class StringPrefs(val map: MutableMap<String, String> = HashMap()) : SharedPreferences {
        override fun getString(key: String, defValue: String?): String? = map[key] ?: defValue
        override fun getAll(): MutableMap<String, *> = HashMap<String, Any>(map)
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { value?.let { map[key] = it } }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = this
            override fun putInt(k: String?, v: Int) = this
            override fun putLong(k: String?, v: Long) = this
            override fun putFloat(k: String?, v: Float) = this
            override fun putBoolean(k: String?, v: Boolean) = this
            override fun remove(k: String?) = apply { map.remove(k) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }

        override fun getStringSet(key: String?, def: MutableSet<String>?): MutableSet<String>? = def
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
