package com.wotagei.cam.core

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
}
