package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分屏右窗 [SplitComparePlayer]（`ui/CameraScreen.kt`）音轨接线的**源码结构守卫**。
 *
 * 行为（真机上选轨到底响哪条）JVM 测不到（ExoPlayer/MediaExtractor 是 Android 运行时件），
 * 这里钉结构红线，防重构时顺手删：
 * - 挂片必须探测音轨数（`TrackSync.countAudioTracks`，与单播放页同口径）；
 * - override 必须经统一映射 `splitAudioPlanOf`（禁止裸写组下标，组下标真源 = `audioGroupIndexOf`）；
 * - 必须有「重放 effect」且依赖 `audioGroups.size`（attach 会 clearAudioOverride + 轨读数迟到，
 *   只在选轨那一刻下发会丢——"选完没效果"经典坑）；
 * - 音轨 chip **只在 `if (dualAudio)` 内渲染**（单音轨片/cap-only 单轨段静默不显，回归红线）。
 *
 * 全部判据作用在 [KotlinSourceScan.codeOnly] 遮蔽后的文本上（注释提到旧符号不算违规）；
 * 红绿已突变实证（见汇报）：拆 dualAudio 门 / 删 override 下发 / 去掉重放 effect 的 audioGroups 依赖 /
 * 探测改名，对应断言转红。
 */
class SplitAudioWiringGuardTest {

    private val masked: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
    }

    /** SplitComparePlayer 的**函数体**（已遮蔽注释/字面量，花括号已配对） */
    private val body: String by lazy { KotlinSourceScan.bodyOf(masked, "SplitComparePlayer") }

    /** 接线判据的复用版（真身与突变体同吃一把尺），返回缺失项说明 */
    private fun missingWires(b: String): List<String> = listOf(
        b.contains("TrackSync.countAudioTracks(") to
            "挂片必须探测音轨数（TrackSync.countAudioTracks，IO 线程，与 PlayerScreen 同口径）",
        b.contains("splitAudioPlanOf(") to
            "override 必须经统一映射 splitAudioPlanOf（禁止裸写组下标字面量）",
        b.contains("setAudioTrackOverride(") to "选内录必须下发 override（点了没反应的半实现要报红）",
        b.contains("clearAudioOverride()") to "选环境必须清 override 回默认（默认即环境轨）",
        b.contains("audioGroups.size") to
            "重放 effect 必须以 audioGroups.size 为 key（attach 清 override + 轨读数迟到，只在选轨那一刻下发会丢）",
        b.contains("if (dualAudio)") to "音轨 chip 必须被 dualAudio 门住（单音轨片静默不显）"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    /**
     * chip 是否真落在 `if (dualAudio) { ... }` 块内（**负面断言**：不得无条件渲染）。
     * 用遮蔽文本做括号配对（注释/字符串里的括号已被抹掉），从 `if (dualAudio)` 后的 `{` 配到闭 `}`。
     */
    private fun chipInDualAudioGate(b: String): Boolean {
        val label = b.indexOf("R.string.player_track_env")
        if (label < 0) return false
        val gate = b.lastIndexOf("if (dualAudio)", label)
        if (gate < 0) return false
        val open = b.indexOf('{', gate)
        if (open < 0 || open > label) return false
        var depth = 0
        var i = open
        while (i < b.length) {
            when (b[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return label < i
                }
            }
            i++
        }
        return false
    }

    @Test
    fun `分屏右窗_音轨接线完整且chip被双音轨门住`() {
        val missing = missingWires(body)
        assertEquals(
            "分屏右窗音轨接线断线：\n${missing.joinToString("\n")}",
            emptyList<String>(),
            missing
        )
        assertTrue(
            "音轨 chip 的标签必须落在 if (dualAudio) 块内（不得无条件渲染）——单音轨片/cap-only 单轨段静默不显是回归红线",
            chipInDualAudioGate(body)
        )
    }

    @Test
    fun `分屏右窗_只做选轨不补同步导出控件`() {
        // 需求只要「选择音频轨」；分屏没有导出动线，把这条写死防后来人"顺手补齐"
        assertFalse(
            "分屏右窗不得出现 TrackSync.run/导出调用（本轮需求只要选轨，不补同步/导出）",
            body.contains("TrackSync.run(") || body.contains("TrackExporter") || body.contains("trackExporter")
        )
    }

    @Test
    fun `突变自证_四条接线各自报红`() {
        // 突变 1：拆掉 dualAudio 门（chip 无条件渲染）→ 门断言必须红
        val mutantGate = body.replace("if (dualAudio) {", "if (true) {")
        assertTrue("拆门突变体必须报红", !chipInDualAudioGate(mutantGate) || missingWires(mutantGate).isNotEmpty())
        // 突变 2：选内录不下发 override（半实现）→ 必须红
        val mutantNoOverride = body.replace("setAudioTrackOverride(", "neverCalled(")
        assertTrue("删 override 下发突变体必须报红", missingWires(mutantNoOverride).isNotEmpty())
        // 突变 3：重放 effect 去掉 audioGroups 依赖（选完就丢）→ 必须红
        val mutantNoReplay = body.replace("audioGroups.size", "0")
        assertTrue("去重放依赖突变体必须报红", missingWires(mutantNoReplay).isNotEmpty())
        // 突变 4：探测改名（不数音轨）→ 必须红
        val mutantNoProbe = body.replace("TrackSync.countAudioTracks(", "countTracks(")
        assertTrue("删探测突变体必须报红", missingWires(mutantNoProbe).isNotEmpty())
        // 突变 5：override 不经统一映射（裸写组下标）→ 必须红
        val mutantLiteral = body.replace("splitAudioPlanOf(", "splitPlan(")
        assertTrue("绕过统一映射突变体必须报红", missingWires(mutantLiteral).isNotEmpty())
    }
}
