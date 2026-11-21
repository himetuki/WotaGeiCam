package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 残影参数与累积面的**同代守卫**（Y13，2026-11-19 评审）——
 * 与 [EchoWiringGuardTest]（接线/顺序）互补，这里钉的是"跨代交错"这一类只在真机上偶发一帧的缺陷：
 *
 * - UI 意图（`echoParams`，@Volatile）一旦被帧循环直接读 ⇒ 发布参数与清累积面是两条路，
 *   两条路之间插进的那一帧会用**新参数**累积**旧历史面**，随后清面把它抹掉——
 *   观感是开启/切档后的第一帧颜色或门控错乱；
 * - 解发时的中间态（开→关→开）若按捕获值逐条落地 ⇒ 也会闪过一帧不该有的参数代；
 * - 累积趟与两条合成槽若各自再读一次可变字段 ⇒ 同一帧的 uniform 与累积面不同代。
 *
 * 修法：`setEchoParams` 在**同一次 `postGl`** 里取执行时刻的最新意图、安装 `echoLiveParams`、
 * 并在开启/语义切换时清面；`drawFrame` 开头一次性快照 `echoFrameParams`，帧内所有 pass 只读快照。
 *
 * 全部是源码级判据（GL 行为只有真机能证）。
 */
class EchoGenerationGuardTest {

    private val engineSrc by lazy { KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt") }
    private val engine by lazy { KotlinSourceScan.codeOnly(engineSrc) }

    private fun body(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, name))

    @Test
    fun `UI意图与GL生效代分离`() {
        // echoParams = UI 意图（@Volatile）；echoLiveParams/echoFrameParams = GL 线程独占（不加 @Volatile）
        assertTrue(
            "echoParams 必须仍是 @Volatile（UI 线程写、echoStatus 读）",
            Regex("@Volatile\\s*private var echoParams").containsMatchIn(engine)
        )
        assertFalse(
            "echoLiveParams 是 GL 线程独占，不许加 @Volatile（加了会掩盖帧内多次读）",
            Regex("@Volatile\\s*private var echoLiveParams").containsMatchIn(engine)
        )
        assertFalse(
            "echoFrameParams 是 GL 线程独占，不许加 @Volatile",
            Regex("@Volatile\\s*private var echoFrameParams").containsMatchIn(engine)
        )
        // 帧循环的每一个 pass 与 program 键都只认已生效代/快照
        for (fn in listOf(
            "drawFrame",
            "drawEchoPass",
            "drawPass",
            "syncMovieSlot",
            "syncDisplaySlot",
            "prepareGlowPrograms"
        )) {
            assertFalse(
                "$fn 不许读 UI 意图 echoParams（会与累积面跨代）",
                body(fn).contains("echoParams")
            )
        }
        assertTrue("program 键必须取已生效代", engine.contains("fun echoOn(): Boolean = echoLiveParams.enabled"))
    }

    @Test
    fun `参数生效与清面在同一次GL操作里`() {
        val b = body("setEchoParams")
        assertTrue("意图必须整体替换（不是逐字段发布）", b.contains("echoParams = next"))
        assertEquals("setEchoParams 恰好一次 postGl", 1, KotlinSourceScan.occurrences(b, "postGl {").size)
        // 执行时刻取最新意图：同一批下发的中间态不该先落地一帧
        val latestAt = b.indexOf("val latest = echoParams")
        val installAt = b.indexOf("echoLiveParams = latest")
        assertTrue("锚点丢失：没截到执行时刻取最新意图", latestAt >= 0)
        assertTrue("live 代必须在 postGl 块内安装", installAt > latestAt)
        assertTrue("已生效代相同则早退（避免重复清面）", b.contains("if (echoLiveParams == latest) return@postGl"))
        // 比对必须用 prev（已生效代）与 latest（本次意图），不能拿 UI 意图互比
        assertTrue("模式切换判据必须基于 prev/latest", b.contains("(prev.mode == com.wotagei.cam.core.EchoMode.TRAIL)"))
        assertTrue("补丁档判据必须基于 prev/latest", b.contains("prev.trailColorMode != latest.trailColorMode"))
        // 开启与语义切换两条清面分支都必须在同一个块里
        assertTrue("开启分支必须清面", b.contains("echoChain?.reset()"))
        val enableAt = b.indexOf("if (!wasOn) {")
        val semanticAt = b.indexOf("} else if (trailChanged) {")
        assertTrue("锚点丢失：开启分支", enableAt >= 0)
        assertTrue("语义切换分支必须排在开启分支之后（elif 同链）", semanticAt > enableAt)
        assertTrue(
            "清面判定必须早于 live 代安装之后的任何 pass 机会（同块内无 yield）",
            b.indexOf("echoLiveParams = latest") < b.indexOf("if (!latest.enabled) return@postGl")
        )
        // EchoProvider 口径：参数下发不排帧（录制中拖滑杆不许塞重复帧）
        assertFalse(b.contains("requestRender"))
        assertFalse(b.contains("scheduleDraw"))
        assertFalse(b.contains("dirty = true"))
    }

    @Test
    fun `每帧一次性快照且帧内只读快照`() {
        val frame = body("drawFrame")
        assertEquals(
            "快照必须恰好一处（多处 = 帧内可能取到不同代）",
            1,
            KotlinSourceScan.occurrences(frame, "echoFrameParams = echoLiveParams").size
        )
        val snapAt = frame.indexOf("echoFrameParams = echoLiveParams")
        val echoAt = frame.indexOf("drawEchoPass(hasFrame)")
        val movieAt = frame.indexOf("syncMovieSlot()")
        assertTrue("快照必须早于成片槽同步（program 键与 uniform 同代）", snapAt in 0 until movieAt)
        assertTrue("快照必须早于累积趟", snapAt in 0 until echoAt)
        // 累积趟只读一次快照，不逐字段再读
        val pass = body("drawEchoPass")
        assertEquals("drawEchoPass 恰好一处取快照", 1, KotlinSourceScan.occurrences(pass, "val p = echoFrameParams").size)
        assertFalse("累积趟不许读 live 代（帧内可变）", pass.contains("echoLiveParams"))
    }

    @Test
    fun `两条合成槽共用同一帧快照`() {
        val pass = body("drawPass")
        assertEquals(
            "合成槽恰好一处取快照（同一枚 program 一帧可画多次）",
            1,
            KotlinSourceScan.occurrences(pass, "val frameEcho = echoFrameParams").size
        )
        val at = pass.indexOf("val frameEcho = echoFrameParams")
        assertTrue("染色模式必须读快照", pass.indexOf("frameEcho.mode", at) > at)
        assertTrue("增益必须读快照", pass.indexOf("frameEcho.gain", at) > at)
        assertTrue("补丁色必须读快照", pass.indexOf("echoTrailTintRgbOf(frameEcho)", at) > at)
        assertFalse("合成槽不许读 live 代或 UI 意图", pass.contains("echoLiveParams") || pass.contains("echoParams"))
    }

    @Test
    fun `释放清两代参数`() {
        val b = body("releaseGl")
        assertTrue(b.contains("echoLiveParams = com.wotagei.cam.core.EchoParams.DEFAULT"))
        assertTrue(b.contains("echoFrameParams = com.wotagei.cam.core.EchoParams.DEFAULT"))
        assertFalse("已应用快照字段已删除（清面在提交侧完成），不许回潮", engine.contains("echoAppliedTrailParams"))
    }

    // ------------------------------------------------------------------ 突变自证

    @Test
    fun `尺子自己能红_帧循环读回UI意图必红`() {
        val mutated = engine.replace(
            "val frameEcho = echoFrameParams",
            "val frameEcho = echoParams"
        )
        assertFalse(
            "合成槽读回 UI 意图的突变必须被看见",
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(mutated, "drawPass")).contains("frameEcho = echoFrameParams")
        )
    }
}