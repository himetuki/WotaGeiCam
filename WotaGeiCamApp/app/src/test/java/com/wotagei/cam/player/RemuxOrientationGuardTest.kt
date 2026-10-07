package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 remux 导出链（ClipExporter / TrackExporter）旋转透传的结构守卫。
 *
 * 缺陷（祖传）：两条导出链全文件不读源旋转、muxer 不写 `setOrientationHint` ⇒ 导出带旋转的源
 * （App 自录的横屏片常带 90/180/270）成片方向错。
 * 钉住（与 OrientationExportGuardTest 对 record/ 两条路的红线同一口径，延伸到 player/）：
 * - 两处 `setOrientationHint(` 都必须经 `exportOrientationHint(`（不得裸喂读数——写出口唯一，
 *   ORIENTATION_MUXER_CCW=false 下即"显示所需旋转角"归一化透传）；
 * - hint 必须写在 `muxer.start()` 之前（start 后写无效）；
 * - 双源读取（RemuxOrientation.kt）的 retriever 必须 release、裁决必须经 matchDecision。
 * 纯函数行为已有 OrientationExportTest 钉着，这里锁结构红线。
 *
 * 定位说明：`fun export` 是 `= withContext(...) { ... }` 的**表达式体**，bodyOf 按设计拒绝解
 * 表达式体 ⇒ 这两个函数体用「函数声明偏移到下一个函数声明」圈区间，`fun copy` 同理；
 * `muxer.start()` 在 copy 体内，set 在 export 体内，区间夹住即得顺序。
 */
class RemuxOrientationGuardTest {

    /** 在 [rel] 里验证 export/copy 声明可定位，并返回 (exportDecl, copyDecl, setAt, startAt) 偏移 */
    private fun offsetsOf(rel: String): List<Int> {
        val src = codeOnly(mainSourceText(rel))
        val exportDecl = Regex("\\bfun\\s+export\\s*\\(").find(src)?.range?.first
            ?: throw AssertionError("定位不到 fun export（守卫失去输入，不许算通过）")
        val copyDecl = Regex("\\bfun\\s+copy\\s*\\(").find(src)?.range?.first
            ?: throw AssertionError("定位不到 fun copy（守卫失去输入，不许算通过）")
        assertTrue("export 必须在 copy 之前（定位前提，守卫失去输入不许算通过）", exportDecl < copyDecl)
        val setAt = src.indexOf("setOrientationHint(exportOrientationHint(", exportDecl)
        val startAt = src.indexOf("muxer.start()", copyDecl)
        return listOf(exportDecl, copyDecl, setAt, startAt)
    }

    @Test
    fun `ClipExport 写 muxer 必须经exportOrientationHint且在start之前`() {
        val (exportDecl, copyDecl, setAt, startAt) = offsetsOf("player/ClipExport.kt")
        assertTrue(
            "剪辑导出的 muxer hint 必须经 exportOrientationHint 统一口径，且写在 export 体内（裸喂 = 旋转约定各自为政，90↔270 互换复发）",
            setAt in exportDecl until copyDecl
        )
        assertTrue(
            "setOrientationHint 必须在 muxer.start()（copy 体内）之前（start 后写无效）",
            startAt > copyDecl && setAt < startAt
        )
        assertFalse(
            "不得把裸 hint 直接喂给 setOrientationHint",
            codeOnly(mainSourceText("player/ClipExport.kt")).contains("setOrientationHint(hint)")
        )
    }

    @Test
    fun `TrackExport 写 muxer 必须经exportOrientationHint且在start之前`() {
        val (exportDecl, copyDecl, setAt, startAt) = offsetsOf("player/TrackExport.kt")
        assertTrue(
            "选轨导出的 muxer hint 必须经 exportOrientationHint 统一口径（与 ClipExporter 同源缺陷）",
            setAt in exportDecl until copyDecl
        )
        assertTrue(
            "setOrientationHint 必须在 muxer.start()（copy 体内）之前（start 后写无效）",
            startAt > copyDecl && setAt < startAt
        )
        assertFalse(
            "不得把裸 hint 直接喂给 setOrientationHint",
            codeOnly(mainSourceText("player/TrackExport.kt")).contains("setOrientationHint(hint)")
        )
    }

    @Test
    fun `双源读取的retriever必须release且裁决经matchDecision`() {
        val src = codeOnly(mainSourceText("player/RemuxOrientation.kt"))
        val retriever = flatten(bodyOf(src, "readRetrieverRotation"))
        val readAt = retriever.indexOf("METADATA_KEY_VIDEO_ROTATION")
        val releaseAt = retriever.indexOf("release()")
        assertTrue("retriever 读法必须还在（守卫失去输入，不许算通过）", readAt >= 0)
        assertTrue(
            "retriever 必须 release（读不到也走 finally；泄漏会驻留到进程死）",
            releaseAt > readAt
        )
        val decision = flatten(bodyOf(src, "readSourceRotationHint"))
        assertTrue(
            "双源裁决必须经 matchDecision（同 ArcRepairRunner 口径，单源在 extractor 给 0 的机型上读数错）",
            decision.contains("matchDecision(")
        )
    }

    @Test
    fun `突变自证_裸喂或删release必红`() {
        val clipSrc = codeOnly(mainSourceText("player/ClipExport.kt"))
        assertTrue(
            "良品必须先真的绿，否则突变体的红没有意义",
            clipSrc.contains("setOrientationHint(exportOrientationHint(")
        )
        // 突变 1：改回裸喂（文本级突变不编译，只验尺子会红）
        val mutant1 = clipSrc.replace(
            "muxer.setOrientationHint(exportOrientationHint(hint, ORIENTATION_MUXER_CCW))",
            "muxer.setOrientationHint(hint)"
        )
        assertTrue("改回裸喂后尺子必须红", !mutant1.contains("setOrientationHint(exportOrientationHint("))
        // 突变 2：retriever 摘掉 release（readRetrieverRotation 是普通块体，bodyOf 可解）
        val orientSrc = codeOnly(mainSourceText("player/RemuxOrientation.kt"))
        val mutant2 = flatten(
            bodyOf(orientSrc.replace("runCatching { r.release() }", "Unit"), "readRetrieverRotation")
        )
        assertTrue(
            "删掉 release 后尺子必须红",
            mutant2.indexOf("release()") <= mutant2.indexOf("METADATA_KEY_VIDEO_ROTATION")
        )
    }
}
