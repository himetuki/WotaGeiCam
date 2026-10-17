package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧账守卫（2026-10-17）：
 *
 * 1. `stampEncoderPresentation` 是录制 PTS 的唯一落点，**不得**读取 `newFrames` / 合帧计数 /
 *    [FrameLedger] 的任何值——观测数一旦进了时间戳，查掉帧的尺子就会改变被查的行为；
 * 2. 计数器必须真的接在链上（回调/消费/编码 swap/抽帧），否则帧账永远是 0 也是"全绿"。
 *
 * 突变自证：把 `newFrames` 写进打戳函数后守卫必须报红；只测良品等于没测。
 */
class FrameLedgerGuardTest {

    private val bannedInStamp = listOf("newFrames", "FrameLedger", "frameMerged", "glCallbacks", "glConsumed")

    /** 在打戳函数体内找禁用符号；返回违规清单，空 = 干净 */
    private fun stampViolations(gl: String): List<String> {
        val stamp = flatten(bodyOf(codeOnly(gl), "stampEncoderPresentation"))
        return bannedInStamp.filter { stamp.contains(it) }
    }

    @Test
    fun `打戳函数不得读取帧计数`() {
        val gl = mainSourceText("camera/GlRenderEngine.kt")
        assertTrue(
            "打戳函数里出现帧账符号：${stampViolations(gl)}",
            stampViolations(gl).isEmpty()
        )
    }

    @Test
    fun `尺子能红_计数写进打戳函数必须报红`() {
        val gl = mainSourceText("camera/GlRenderEngine.kt")
        val mutated = gl.replace(
            "val nowNs = System.nanoTime()",
            "val nowNs = System.nanoTime() + newFrames"
        )
        assertTrue("突变体没有真正改到打戳函数，守卫失去输入", mutated != gl)
        assertTrue("把 newFrames 写进打戳函数后守卫必须红", stampViolations(mutated).contains("newFrames"))

        val mutated2 = gl.replace(
            "val frameTsNs = lastDrawnTimestampNs",
            "val frameTsNs = lastDrawnTimestampNs + FrameLedger.snapshot().glConsumed"
        )
        assertTrue(stampViolations(mutated2).contains("FrameLedger"))
    }

    @Test
    fun `计数器必须真的接在链上`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        assertTrue("GL 回调必须计数", flatten(bodyOf(gl, "onFrameAvailable")).contains("FrameLedger.onGlCallback()"))
        assertTrue("updateTexImage 成功必须计数", flatten(bodyOf(gl, "consumeFrame")).contains("FrameLedger.onGlConsumed()"))
        assertTrue("编码面 swap 结果必须计数", flatten(bodyOf(gl, "swap")).contains("FrameLedger.onEncoderSwap"))
        assertTrue("有意抽帧必须计数", flatten(bodyOf(gl, "drawFrame")).contains("FrameLedger.onArcDrop()"))

        val cam = codeOnly(mainSourceText("camera/Camera2Engine.kt"))
        assertEquals(
            "相机 onCaptureStarted 必须接帧账（正常/高速两条重复回调都要接）",
            2,
            Regex("FrameLedger\\.onCameraStarted\\(\\)").findAll(cam).count()
        )
    }
}
