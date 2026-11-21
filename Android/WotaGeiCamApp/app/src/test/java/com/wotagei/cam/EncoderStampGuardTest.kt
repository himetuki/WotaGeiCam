package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y10 守卫：编码面打戳**只认 `true` 为成功**。
 *
 * `eglPresentationTimeANDROID` 正常返回 false 时 `runCatching` 仍是 `Result.success`——旧实现
 * 因此漏记失败、照常推进 `encoderLastPtsNs`/`encoderFrameIndex`，下一帧把一枚从未写入的戳当
 * 网格地板，位次账与容器永久错位。GL 调用在 JVM 里不可执行，这里按源码结构锁住：
 * - 布尔返回值与异常两条失败出口都计 [stampFailCount]；
 * - 失败即返回、不推进账；
 * - 空档观测只统计真正写进容器的戳（失败帧不记，避免下一帧重复计同一段空档）。
 */
class EncoderStampGuardTest {

    private val masked = KotlinSourceScan.codeOnly(
        KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt")
    )
    private val stamp = KotlinSourceScan.bodyOf(masked, "stampEncoderPresentation")
    private val flat = KotlinSourceScan.flatten(stamp)

    @Test
    fun `打戳函数必须返回布尔`() {
        assertTrue(
            "签名必须带 Boolean 返回（调用方可据此把本帧记为失败）",
            KotlinSourceScan.flatten(masked).contains(
                "private fun stampEncoderPresentation(encoder: EGLSurface): Boolean"
            )
        )
    }

    @Test
    fun `布尔false与异常都计失败`() {
        assertTrue("必须直接检查 EGL 的布尔返回值", flat.contains("if (EGLExt.eglPresentationTimeANDROID("))
        assertTrue("必须有异常兜底", flat.contains("catch (t: Throwable)"))
        assertEquals(
            "两条失败出口（false / 异常）各计一次",
            2,
            count(flat, "stampFailCount++")
        )
        assertFalse(
            "禁止再用 Result.isSuccess 判定（false 会被当成成功）",
            flat.contains(".isSuccess")
        )
        assertFalse("禁止只靠 runCatching 吞掉 false", flat.contains("runCatching {"))
    }

    @Test
    fun `失败不推进账且空档只记成功帧`() {
        val failReturn = flat.indexOf("if (!written) return false")
        val ledgerPts = flat.indexOf("encoderLastPtsNs = ptsNs")
        val ledgerIndex = flat.indexOf("encoderFrameIndex++")
        val gap = flat.indexOf("stampGapSlots +=")
        assertTrue("必须有失败早退", failReturn >= 0)
        assertTrue("失败早退必须排在最后一枚戳推进之前", failReturn < ledgerPts)
        assertTrue("失败早退必须排在帧序号推进之前", failReturn < ledgerIndex)
        assertTrue("空档统计必须排在成功判定之后（失败帧不计 gap）", gap > failReturn)
    }

    @Test
    fun `不打戳的合法情形不记失败`() {
        // fps=0（直注）/显示未就绪是"不适用"，返回 true 让调用方照常 swap，不得计入失败
        assertTrue("fps<=0 必须提前返回 true", flat.contains("if (fps <= 0) return true"))
        assertTrue(
            "显示未就绪必须提前返回 true",
            flat.contains("if (display === EGL14.EGL_NO_DISPLAY) return true")
        )
    }

    private fun count(src: String, needle: String): Int = src.split(needle).size - 1
}