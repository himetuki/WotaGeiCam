package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「所有文件访问」平台事实的纯函数表（[WotaStorage.hasAllFilesAccessFor] /
 * [WotaStorage.supportsAllFilesAccessFor]）。
 *
 * 回归题面：旧 `hasAllFilesAccess()` 对 `SDK < R` 恒返回 true，且设置页在 API 29 显示"已开启"、
 * 点击跳两个 API 30 才存在的系统 action。Android 10 上这枚权限不存在，scoped storage 仍然生效；
 * 状态必须如实为 false，能力必须如实为"不支持"。
 */
class StorageAccessTest {

    @Test
    fun `API 29 没有所有文件访问能力`() {
        assertFalse("API 29 不支持该能力", WotaStorage.supportsAllFilesAccessFor(29))
        assertFalse(
            "API 29 即使系统误报 manager=true 也不能算已开启",
            WotaStorage.hasAllFilesAccessFor(sdk = 29, isExternalStorageManager = true)
        )
    }

    @Test
    fun `API 30 起能力存在且以系统状态为准`() {
        assertTrue("API 30 支持该能力", WotaStorage.supportsAllFilesAccessFor(30))
        assertTrue("API 34 支持该能力", WotaStorage.supportsAllFilesAccessFor(34))
        assertTrue("API 30 已开启", WotaStorage.hasAllFilesAccessFor(sdk = 30, isExternalStorageManager = true))
        assertFalse("API 30 未开启", WotaStorage.hasAllFilesAccessFor(sdk = 30, isExternalStorageManager = false))
        assertFalse("API 34 未开启", WotaStorage.hasAllFilesAccessFor(sdk = 34, isExternalStorageManager = false))
    }

    @Test fun `系统授权入口优先本应用失败才回退总页`() {
        val calls = arrayListOf<Pair<String, String?>>()
        assertTrue(WotaStorage.openSettingsFor(34, "com.wotagei.cam") { action, data ->
            calls += action to data
            calls.size == 2
        })
        assertEquals(listOf("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" to "package:com.wotagei.cam",
            "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION" to null), calls)
        calls.clear()
        assertTrue(WotaStorage.openSettingsFor(30, "com.wotagei.cam") { action, data ->
            calls += action to data
            true
        })
        assertEquals(1, calls.size)
    }

    @Test fun `API29不尝试不存在的授权页且两端入口均失败如实返回`() {
        var calls = 0
        assertFalse(WotaStorage.openSettingsFor(29, "com.wotagei.cam") { _, _ -> calls++; true })
        assertEquals(0, calls)
        assertFalse(WotaStorage.openSettingsFor(34, "com.wotagei.cam") { _, _ -> calls++; false })
        assertEquals(2, calls)
    }
}
