package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R01 首段等待谓词的**源码接线守卫**。
 *
 * 首段（GPU 路）起录在 JVM 单测里跑不到 [com.wotagei.cam.ui.RecordRunner] 的控制线程链，
 * 所以「首段必须把本次编码面传进等待谓词、超时必须判废」用 [KotlinSourceScan] 钉住。
 *
 * 口径：`awaitEncoderSurface(expected)` 只有在 expected == null 时才允许用「任意面已绑定」的
 * 宽松谓词；首段与换段都必须传本段 Surface，走 `isEncoderSurfaceNative(expected)` 身份判定——
 * 换代瞬间旧绑定尚未解绑，宽松谓词会零等待放行，编码器把帧写进已死的旧 producer（静默零帧）。
 */
class CameraFirstSegmentGuardTest {

    private val maskedScreen by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
    }

    // ------------------------------------------------------------------ 文本定位小工具

    /** 从开 `{` 处配对出整枚块的体（FocusLockGuardTest 同款） */
    private fun blockBody(text: String, openBraceAt: Int): String? {
        var depth = 1
        for (i in openBraceAt + 1 until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openBraceAt + 1, i)
                }
            }
        }
        return null
    }

    /** 锚串之后第一枚 `{` 起的整枚块；锚找不到返回 null（守卫失去输入，不许算通过） */
    private fun bodyAfterAnchor(text: String, anchor: String): String? {
        val at = text.indexOf(anchor)
        if (at < 0) return null
        val brace = text.indexOf('{', at)
        if (brace < 0) return null
        return blockBody(text, brace)
    }

    // ------------------------------------------------------------------ 判据（纯函数）

    /**
     * 首段/换段必须都传本段 Surface；空 = 合规。
     * 注意：`awaitEncoderSurface(expected: Surface? = null)` 的**声明**里带默认值，
     * 但调用点必须显式给面——残留一处 `awaitEncoderSurface()` 就等于保留了一条乐观放行路。
     */
    private fun firstSegmentIssues(masked: String): List<String> {
        val issues = ArrayList<String>()
        val begin = runCatching { bodyOf(masked, "beginSession") }.getOrNull()
        if (begin == null) {
            return listOf("定位不到 beginSession 的体——起录会话链被改名/挪走，守卫失去输入")
        }
        if (occurrences(begin, "awaitEncoderSurface(surface)").isEmpty()) {
            issues.add("首段没有把本次编码面传进 awaitEncoderSurface(surface)——乐观谓词会零等待放行")
        }
        if (occurrences(masked, "awaitEncoderSurface()").isNotEmpty()) {
            issues.add("残留无参 awaitEncoderSurface() 调用——「任意面已绑定」的乐观放行路必须清零")
        }
        val fail = bodyAfterAnchor(begin, "if (!awaitEncoderSurface(surface))")
        if (fail == null) {
            issues.add("定位不到首段 awaitEncoderSurface(surface) 的失败分支")
        } else {
            if (occurrences(fail, "teardownAbandonedSession(rec)").isEmpty()) {
                issues.add("首段编码面未挂好时必须走 teardownAbandonedSession 收走半成品")
            }
            if (occurrences(fail, "failNow(RecordError.START_FAILED)").isEmpty()) {
                issues.add("首段编码面未挂好必须 failNow(START_FAILED)——不许静默继续 start")
            }
        }
        // 换段重挂同口径：新面必须交给等待谓词
        if (occurrences(begin, "awaitEncoderSurface(s)").isEmpty()) {
            issues.add("换段重挂没有把新面传进 awaitEncoderSurface(s)——与首段同一条身份判定")
        }
        return issues
    }

    /** 等待谓词本体：expected 非空必须走身份判定；空 = 合规 */
    private fun predicateIssues(masked: String): List<String> {
        val body = runCatching { bodyOf(masked, "awaitEncoderSurface") }.getOrNull()
            ?: return listOf("定位不到 awaitEncoderSurface 的体")
        val issues = ArrayList<String>()
        if (occurrences(body, "isEncoderSurfaceNative(expected)").isEmpty()) {
            issues.add("awaitEncoderSurface 非空 expected 分支没有走 isEncoderSurfaceNative 身份判定")
        }
        if (occurrences(body, "isOutputSurfaceBound()").isEmpty()) {
            issues.add("awaitEncoderSurface 的 null 兜底分支丢了 isOutputSurfaceBound")
        }
        if (occurrences(body, "stopRequested").isEmpty()) {
            issues.add("awaitEncoderSurface 轮询没带 stopRequested——停止请求不能打断等待窗")
        }
        return issues
    }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `首段与换段都按本次编码面身份等待`() {
        assertEquals("首段接线缺件：", emptyList<String>(), firstSegmentIssues(maskedScreen))
    }

    @Test
    fun `等待谓词按expected分岔`() {
        assertEquals("等待谓词缺件：", emptyList<String>(), predicateIssues(maskedScreen))
    }

    // ------------------------------------------------------------------ 尺子自己能红

    @Test
    fun `尺子自己能红_首段退回无参等待必报红`() {
        assertTrue("真身必须绿", firstSegmentIssues(maskedScreen).isEmpty())
        val mutant = maskedScreen.replace("if (!awaitEncoderSurface(surface))", "if (!awaitEncoderSurface())")
        assertTrue("首段退回无参等待后判据必须红", firstSegmentIssues(mutant).isNotEmpty())
    }

    @Test
    fun `尺子自己能红_等待谓词退回任意面必报红`() {
        assertTrue("真身必须绿", predicateIssues(maskedScreen).isEmpty())
        val mutant = maskedScreen.replace(
            "if (expected == null) gl.isOutputSurfaceBound() else gl.isEncoderSurfaceNative(expected)",
            "gl.isOutputSurfaceBound()"
        )
        assertTrue("等待谓词退回「任意面已绑定」后判据必须红", predicateIssues(mutant).isNotEmpty())
    }
}