package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPU 路换段重挂窗口的**抑制守卫**（2026-10-07 缺陷修复）。
 *
 * 缺陷：分段轮转时 `closeSegmentEngine` 先 `codec.stop()`（旧编码面即死）而 GL 还绑着它，
 * 到重挂回调把新面挂好之间 ≫ 一个帧周期——窗口内相机帧照常驱动 `drawFrame`，死面上 swap
 * 必败 → `degradeArcConvert` 静默清账降级：DROP→直通把换段点之后的成片降成 1.25× 慢放
 *（encoderFps 仍 24、源 30fps 直通全按 1/24s 盖 PTS、音画渐进漂移）；MEND→DROP 清掉
 * 位次账（sidecar 缺前半段）。且旧 await 谓词在旧绑定还在时首查恒真（零等待），
 * 旧面会先于 GL 解绑被 release。
 *
 * 修复双保险，各锁一段：
 * - 显式抑制位 `segmentRotating`（mark/clear 成对、try/finally 保证），与 `recordTearingDown`
 *   并列进纯函数 [degradeSuppressed]——判据必须可 JVM 全表测桥本身，内联即失守；
 * - await 谓词收紧到「传入的新面」（isEncoderSurfaceNative），首段路径保持旧谓词。
 */
class SegmentRotateSuppressGuardTest {

    private fun maskedMain(rel: String): String = codeOnly(mainSourceText(rel))

    // ------------------------------------------------------------------ 纯函数（测桥本身）

    @Test
    fun `degradeSuppressed 全表_任一窗口为真即抑制`() {
        // 期望值手写（析取真值表），不许由实现推导
        assertEquals("两窗口都关：不抑制（真失败照旧降级）", false, degradeSuppressed(false, false))
        assertEquals("停止窗口单独为真：抑制", true, degradeSuppressed(true, false))
        assertEquals("换段窗口单独为真：抑制", true, degradeSuppressed(false, true))
        assertEquals("两窗口同时为真：抑制", true, degradeSuppressed(true, true))
    }

    @Test
    fun `尺子自己能红_析取被突变成合取时全表必报偏离`() {
        // 突变体（|| → &&）：单独换段窗口漏抑制——正是本缺陷的回归形态
        val mutant: (Boolean, Boolean) -> Boolean = { a, b -> a && b }
        val truthTable = listOf(
            Triple(false, false, false),
            Triple(true, false, true),
            Triple(false, true, true),
            Triple(true, true, true)
        )
        assertTrue(
            "合取突变体必须至少在一行上偏离析取真值表（否则全表测不出该突变，尺子失效）",
            truthTable.any { (a, b, expect) -> mutant(a, b) != expect }
        )
    }

    // ------------------------------------------------------------------ 结构守卫（GlRenderEngine 侧）

    @Test
    fun `degradeArcConvert 必须经纯函数判定抑制且先于两段降级`() {
        val engine = maskedMain("camera/GlRenderEngine.kt")
        val degrade = flatten(bodyOf(engine, "degradeArcConvert"))
        assertTrue(
            "degradeArcConvert 体内必须调用 degradeSuppressed(recordTearingDown, segmentRotating)" +
                "（判据散落内联 = 纯函数桥断，删掉抑制分支测试仍全绿）",
            degrade.contains("degradeSuppressed(recordTearingDown, segmentRotating)")
        )
        val suppress = degrade.indexOf("degradeSuppressed(")
        val twoStage = degrade.indexOf("setArcConvert(ArcConvertMode.DROP")
        assertTrue(
            "抑制判定必须先于 MEND→DROP 两段降级（顺序反 = 拆解伪影先清账再被抑制，账已毁）",
            suppress in 0 until twoStage
        )
    }

    @Test
    fun `引擎侧抑制位与入口成对在位且跨线程可见`() {
        val engine = maskedMain("camera/GlRenderEngine.kt")
        // 泵线程 mark/clear、GL 线程读：非 @Volatile 就可能读到过期值（窗口漏抑制或漏清）
        assertTrue(
            "segmentRotating 必须 @Volatile（跨线程标志）",
            Regex("@Volatile\\s+private var segmentRotating").containsMatchIn(engine)
        )
        assertTrue(
            "markSegmentRotating 入口失踪（抑制窗口没人能开）",
            engine.contains("fun markSegmentRotating()")
        )
        assertTrue(
            "clearSegmentRotating 入口失踪（抑制窗口没人能关，之后真失败全被吞）",
            engine.contains("fun clearSegmentRotating()")
        )
        assertTrue(
            "新面谓词必须是 native 面同一性判定（isOutputSurfaceBound 看不出新旧面）",
            engine.contains("fun isEncoderSurfaceNative(s: Surface?): Boolean = encoderNative === s")
        )
    }

    // ------------------------------------------------------------------ 结构守卫（CameraScreen 侧）

    /** 重挂回调的抑制窗口接线体检：返回违规清单，空 = 接线完整（真身与突变体同吃） */
    private fun rebindViolations(screen: String): List<String> {
        val bad = ArrayList<String>()
        val begin = flatten(bodyOf(screen, "beginSession"))
        val mark = begin.indexOf("markSegmentRotating()")
        val setOut = begin.indexOf("setOutputSurface(s, profile.width, profile.height, profile.fps)")
        val awaitNew = begin.indexOf("awaitEncoderSurface(s)")
        val finallyKw = begin.indexOf("finally")
        val clear = begin.indexOf("clearSegmentRotating()")
        if (mark < 0) bad.add("重挂回调缺 markSegmentRotating（换段窗口降级不被抑制）")
        if (awaitNew < 0) bad.add("重挂回调必须等新面 awaitEncoderSurface(s)（等旧谓词 = 零等待）")
        if (clear < 0) bad.add("重挂回调缺 clearSegmentRotating（窗口漏关，之后真失败全被吞）")
        if (mark >= 0 && setOut >= 0 && mark > setOut) {
            bad.add("mark 必须在 setOutputSurface 之前（晚了盖不住旧面 release 前的窗口）")
        }
        if (setOut >= 0 && awaitNew >= 0 && setOut > awaitNew) {
            bad.add("setOutputSurface 必须先于 await（先等后挂 = 永久超时）")
        }
        if (finallyKw < 0 || awaitNew >= finallyKw || clear < finallyKw) {
            bad.add("clear 必须落在 finally 内（await 超时/抛异常都要出窗口，漏一路就永久抑制）")
        }
        return bad
    }

    @Test
    fun `重挂回调的抑制窗口接线完整且位置序正确`() {
        val violations = rebindViolations(maskedMain("ui/CameraScreen.kt"))
        assertEquals(
            "换段抑制窗口接线发现断点：\n${violations.joinToString("\n")}",
            0, violations.size
        )
    }

    @Test
    fun `尺子自己能红_删mark改旧谓词或拆finally必报红`() {
        val screen = maskedMain("ui/CameraScreen.kt")
        assertTrue("良品必须先真的绿，否则后面突变体的红没有意义", rebindViolations(screen).isEmpty())
        // 突变 1：删掉 mark（回到缺陷形态——窗口不设防）
        val noMark = screen.replace("gl?.markSegmentRotating()", "")
        assertTrue(
            "删 mark 后尺子必须红",
            rebindViolations(noMark).any { it.contains("markSegmentRotating") }
        )
        // 突变 2：重挂改回旧谓词 awaitEncoderSurface()（零等待，旧面未解绑先 release）
        val oldPredicate = screen.replace("awaitEncoderSurface(s)", "awaitEncoderSurface()")
        assertTrue(
            "重挂改回旧谓词后尺子必须红",
            rebindViolations(oldPredicate).any { it.contains("awaitEncoderSurface(s)") }
        )
        // 突变 3：finally 失效（clear 只在成功路径跑，await 超时/抛异常即永久抑制；
        // 换成 if(false) 保持花括号配平，遮蔽器才能继续解函数体）
        val noFinally = screen.replace("} finally {", "} if (false) {")
        assertTrue(
            "拆 finally 后尺子必须红",
            rebindViolations(noFinally).any { it.contains("finally") }
        )
    }
}
