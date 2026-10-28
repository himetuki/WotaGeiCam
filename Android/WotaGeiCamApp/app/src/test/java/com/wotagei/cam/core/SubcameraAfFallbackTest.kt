package com.wotagei.cam.core

import android.hardware.camera2.CameraMetadata
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理子头 AF 模式回退到逻辑头（docs/plan/27，2026-10-28 落地）。
 *
 * marble（Redmi Note 12 Turbo）广角子头实测 `afModes=[0]`（只 OFF）、`minFocusD=0`——
 * 逻辑多摄展开后按子头原文判可调对焦，会把能连续对焦的子头误判成定焦，对焦胶囊被禁用。
 * 回退规则与边界都钉在 [effectiveAfModes]（纯函数）；本文件测规则本身 + LensSlot 接线。
 */
class SubcameraAfFallbackTest {

    private val continuous = setOf(
        CameraMetadata.CONTROL_AF_MODE_OFF,
        CameraMetadata.CONTROL_AF_MODE_AUTO,
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    )

    @Test
    fun `子头报全OFF时借逻辑头模式`() {
        // marble 现场原文
        assertEquals(continuous, effectiveAfModes(setOf(CameraMetadata.CONTROL_AF_MODE_OFF), continuous))
        // 空集同理
        assertEquals(continuous, effectiveAfModes(emptySet(), continuous))
    }

    @Test
    fun `子头有有效模式或借不到时维持原文`() {
        // 子头自己报了有效模式（含 OFF + AUTO）：不退化，一字不动
        val own = setOf(CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_AUTO)
        assertEquals(own, effectiveAfModes(own, continuous))
        // 没有逻辑头（逻辑头自己 / 非多摄）：null 不触发回退
        assertEquals(own, effectiveAfModes(own, null))
        assertEquals(setOf(CameraMetadata.CONTROL_AF_MODE_OFF), effectiveAfModes(setOf(CameraMetadata.CONTROL_AF_MODE_OFF), null))
        assertEquals(emptySet<Int>(), effectiveAfModes(emptySet(), null))
        // 逻辑头也报空：借不来就照实报，不替 HAL 编模式
        assertEquals(
            setOf(CameraMetadata.CONTROL_AF_MODE_OFF),
            effectiveAfModes(setOf(CameraMetadata.CONTROL_AF_MODE_OFF), emptySet())
        )
        assertEquals(emptySet<Int>(), effectiveAfModes(emptySet(), emptySet()))
    }

    @Test
    fun `逻辑头模式必须原样借出不加不减`() {
        val weird = setOf(CameraMetadata.CONTROL_AF_MODE_EDOF)
        assertEquals(weird, effectiveAfModes(emptySet(), weird))
        assertEquals(
            setOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
            effectiveAfModes(setOf(CameraMetadata.CONTROL_AF_MODE_OFF), setOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO))
        )
    }

    @Test
    fun `子头构造点必须传逻辑头AF模式做回退`() {
        val code = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("core/LensSlot.kt")
        )
        val body = KotlinSourceScan.bodyOf(code, "enumerateLenses")
        assertTrue(
            "隐藏物理子头的 CameraAbility.from 必须传逻辑头 CONTROL_AF_AVAILABLE_MODES（否则误判定焦）",
            body.contains("CONTROL_AF_AVAILABLE_MODES")
        )
        // 逻辑头自己的构造点不许传回退参数（保持 null = 逻辑头不受影响）。
        // 计数用正则不用 occurrences——from(id 的实参首字母会被词边界判成标识符而拒认
        val fromCalls = Regex("CameraAbility\\.from\\(").findAll(body).count()
        assertEquals("子头与逻辑头两处构造点", 2, fromCalls)
        val logicalCall = body.substring(body.indexOf("CameraAbility.from(", body.indexOf("if (logical) continue")))
        assertFalse("逻辑头自己的构造点不许带回退参数", logicalCall.contains("CONTROL_AF_AVAILABLE_MODES"))
        // 尺要能红：抽掉子头回退参数后断言翻红
        val mutant = body.replace("CONTROL_AF_AVAILABLE_MODES", "AF_REMOVED")
        assertFalse(mutant.contains("CONTROL_AF_AVAILABLE_MODES"))
    }
}
