package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方向导出的**源码结构守卫**（30fps→24fps 光弧修复成片"180° 旋转"修复）。
 *
 * 行为有 `OrientationExportTest` 钉着；这里钉结构红线，被"顺手重构"破坏时行为测试未必红：
 * - muxer 写出口**唯一**：ArcRepairRunner 与 CodecRecorder 都必须经 `exportOrientationHint`，
 *   直喂裸 hint = 两条路旋转约定各自为政（修了修复路、录制路还错）；
 * - 修复路旋转角必须**双源经 matchDecision**：只读 `KEY_ROTATION` 单源会让 extractor 给 0 的机型
 *   读数错（双源是稳健性；⚠ 它不是 2026-10-06 那次 180° 的根因——那次读数本就正确，见下一条）；
 * - 两处 `dec.configure(...)` 的实参必须是**剥掉旋转**的 format：裸 `vf` 喂带面解码器 = 平台把容器
 *   旋转烘进像素 → 与写出的 hint 叠加成双重旋转（本次 180° 的真因）。
 */
class OrientationExportGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    @Test
    fun `ArcRepairRunner 写 muxer 必须经统一出口`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("record/ArcRepairRunner.kt"), "openMuxer")
        )
        assertTrue(
            "ArcRepairRunner 写 muxer 必须经 exportOrientationHint（绕过出口 = 旋转约定各自为政，90↔270 互换复发）",
            body.contains("exportOrientationHint(")
        )
        assertFalse(
            "不得把裸 hint 直接喂给 setOrientationHint（负面断言：这就是绕开统一出口的写法）",
            body.contains("setOrientationHint(hint)")
        )
    }

    @Test
    fun `CodecRecorder 写 muxer 必须经统一出口`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("record/CodecRecorder.kt"), "openMuxer")
        )
        assertTrue(
            "CodecRecorder 写 muxer 必须经同一 exportOrientationHint（同源缺陷：录制路不统一则复发）",
            body.contains("exportOrientationHint(")
        )
        assertFalse(
            "不得把裸 hint 直接喂给 setOrientationHint",
            body.contains("setOrientationHint(hint)")
        )
    }

    @Test
    fun `修复路旋转角取双源经 matchDecision`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("record/ArcRepairRunner.kt"), "open")
        )
        assertTrue(
            "open() 必须经 matchDecision 裁决双源（只读 KEY_ROTATION 单源在 extractor 给 0 的机型上读数错）",
            body.contains("matchDecision(")
        )
        assertTrue(
            "open() 必须真的读 retriever 旋转角（返回 -1 表示不可用，供降级）",
            body.contains("readRetrieverRotation(")
        )
        assertTrue(
            "日志必须同时打出 extractor / retriever 两个原始读数（下次真机一眼定案）",
            body.contains("hintExtractor") && body.contains("hintRetriever")
        )
    }

    /**
     * 两处解码器 `configure` 的实参必须是**剥离旋转**后的 format。
     *
     * 这是本次 180° 的唯一结构红线：`dec.configure(vf, …)` 会把容器旋转交给平台烘进输出面，
     * 与 metadata 写出的同一个 hint 叠加成双重旋转。锁"唯一真源 `decFmt = decoderFormatOf(vf)`"
     * 比逐字锁两行实参更抗格式漂移，且新增解码路线时会因 configure 计数变化而红。
     */
    @Test
    fun `两处解码器 configure 必须用剥离旋转的 format`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("record/ArcRepairRunner.kt"), "open")
        )
        assertTrue(
            "open() 必须用 decoderFormatOf(vf) 造解码器 format（剥掉 KEY_ROTATION）",
            body.contains("decoderFormatOf(vf)")
        )
        assertEquals(
            "解码器 configure 应恰有 2 处（GPU/CPU 各一）；新增路线必须显式点名，否则本守卫漏挂",
            2,
            Regex("dec\\.configure\\(").findAll(body).count()
        )
        assertFalse(
            "不得再把裸 vf 喂给解码器 configure —— 那正是平台把容器旋转烘进像素的入口（本次 180° 根因）",
            body.contains("dec.configure(vf")
        )
        assertTrue(
            "GPU 路（有输出面）必须传剥离后的 decFmt",
            body.contains("dec.configure(decFmt, decSurface, null, 0)")
        )
        assertTrue(
            "CPU 路必须传同一个 decFmt（两路一致，避免将来只改一路）",
            body.contains("dec.configure(decFmt, null, null, 0)")
        )
    }

    /** `decoderFormatOf` 必须是"拷贝件 + 只删键"：拷贝用平台拷贝构造，删键只落在拷贝件上，入参只读 */
    @Test
    fun `decoderFormatOf 保真拷贝且不污染入参`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                maskedMain("record/OrientationExport.kt"),
                "decoderFormatOf"
            )
        )
        assertTrue(
            "必须用平台拷贝构造 MediaFormat(src) 保真复制（手抄最小必要键集漏键会让解码器建不出来）",
            body.contains("MediaFormat(src)")
        )
        assertTrue(
            "必须经 bakedRotationKeysToDrop 决定删哪些键（就是 JVM 单测钉住的那条纯函数）",
            body.contains("bakedRotationKeysToDrop(")
        )
        assertTrue("必须在拷贝件 dst 上删键", body.contains("dst.removeKey("))
        assertFalse("不得在入参 src 上删键（污染调用方 format）", body.contains("src.removeKey("))
        assertFalse("不得在入参 src 上写键（污染调用方 format）", body.contains("src.set"))
    }
}
