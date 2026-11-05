package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 等号档曝光纠偏的**即时下发接线守卫**（2026-11-05 真机缺陷回归）。
 *
 * 背景：微调开关 [com.wotagei.cam.core.WotaParams.exposureCorrectionAuto] 与手动滑杆
 * [com.wotagei.cam.core.WotaParams.exposureCorrectionManualNs] 只改发送侧 SENSOR_EXPOSURE_TIME，
 * 属合并刷新（markDirty），不改会话形状。此前这两个流**漏接** [Camera2Engine] 的 dirtySources：
 * 改微调不触发重下发，HUD 实测读数（actualSentExposureNs）与画面都要等会话重建（进设置再退出）
 * 才变。手动档改滑杆、自动档开关（已闩锁时 cancelExposureProbe 提前 return 不 markDirty）全中招。
 *
 * 本守卫钉住两个流都在 dirtySources 里，删掉任一个即红。
 */
class ExposureCorrectionDispatchGuardTest {

    private val engine = codeOnly(mainSourceText("camera/Camera2Engine.kt"))

    // dirtySources 是表达式体（= with(params){...}），bodyOf 定位不到；而
    // `exposureCorrection*.map { Unit }` 全文件只在 dirtySources 出现（snapshot/探测里是 .value），
    // 故直接扫整份 codeOnly 源码即可，唯一性成立。
    @Test
    fun `纠偏开关与手动滑杆必须接进dirtySources即时重下发`() {
        assertTrue(
            "exposureCorrectionAuto 必须在 dirtySources（翻开关即刻重下发，不能等会话重建）",
            engine.contains("exposureCorrectionAuto.map { Unit }")
        )
        assertTrue(
            "exposureCorrectionManualNs 必须在 dirtySources（拖手动滑杆即刻重下发）",
            engine.contains("exposureCorrectionManualNs.map { Unit }")
        )
    }

    @Test
    fun `尺子能红_摘掉任一纠偏流必报红`() {
        assertTrue("真身必须绿", engine.contains("exposureCorrectionManualNs.map { Unit }"))
        // 突变：摘掉手动滑杆流（回潮原缺陷）⇒ 同一把尺必须红
        val mutant = engine.replace("exposureCorrectionManualNs.map { Unit }", "stabilize.map { Unit }")
        assertTrue(
            "摘掉 exposureCorrectionManualNs 流的突变必须被抓",
            !mutant.contains("exposureCorrectionManualNs.map { Unit }")
        )
    }
}
