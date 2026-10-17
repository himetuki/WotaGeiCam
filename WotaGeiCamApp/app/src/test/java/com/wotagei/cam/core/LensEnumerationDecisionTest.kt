package com.wotagei.cam.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逻辑多摄头「是否从档位表隐藏」的纯函数全表（[shouldHideLogicalHead]）。
 *
 * 回归题面：旧实现把 `physicalCameraIds` 的读取限定在 `SDK >= R`，在 Android 10（API 29）
 * 上 hiddenPhysicals 恒空，随后 `if (logical && hiddenPhysicals.isEmpty()) continue` 把整颗
 * 逻辑后摄跳过 —— 只暴露逻辑头的机型会变成"后摄一颗都没有"，只能退回前摄或直接打不开相机。
 * 正确语义只有一种可隐藏情形：逻辑头 + 读到了非空 physicalIds + 所有子头都已作为独立镜头
 * 暴露（hiddenPhysicals 为空）。其余（尤其读不到 physicalIds）必须保留逻辑头本身。
 */
class LensEnumerationDecisionTest {

    @Test
    fun `读不到 physicalCameraIds 时保留逻辑头`() {
        assertFalse(
            "API 29 / HAL 不报子头：hiddenPhysicals 为空且 physicalIds 为空 ⇒ 不隐藏（这颗后摄必须留在表里）",
            shouldHideLogicalHead(
                logical = true,
                physicalIds = emptySet(),
                hiddenPhysicals = emptyList()
            )
        )
    }

    @Test
    fun `子头全部已独立暴露时隐藏逻辑头`() {
        assertTrue(
            "physicalIds={2,3} 且 2/3 都已在 cameraIdList 里 ⇒ 逻辑头不占档（避免同一传感器列两遍）",
            shouldHideLogicalHead(
                logical = true,
                physicalIds = setOf("2", "3"),
                hiddenPhysicals = emptyList()
            )
        )
    }

    @Test
    fun `存在未暴露子头时展开子头_不隐藏逻辑头`() {
        assertFalse(
            "physicalIds={2,3}、只有 3 已独立暴露、2 是 hidden ⇒ 由 hiddenPhysicals 承载，逻辑头不参与",
            shouldHideLogicalHead(
                logical = true,
                physicalIds = setOf("2", "3"),
                hiddenPhysicals = listOf("2")
            )
        )
    }

    @Test
    fun `非逻辑头一律不隐藏`() {
        assertFalse(
            "普通单摄：logical=false ⇒ 永远进表",
            shouldHideLogicalHead(
                logical = false,
                physicalIds = emptySet(),
                hiddenPhysicals = emptyList()
            )
        )
        assertFalse(
            "普通单摄即使意外带 physicalIds 也不隐藏",
            shouldHideLogicalHead(
                logical = false,
                physicalIds = setOf("2"),
                hiddenPhysicals = emptyList()
            )
        )
    }
}
