package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对比播放 PR 式双轨时间线（r02）的**源码结构守卫**。
 *
 * 行为（编排拍真的怎么跑）JVM 测不到（ExoPlayer 是 Android 运行时件），这里钉结构红线：
 * - 编排循环体（`orchestrateLoop`）必须「decide → 先停/走、再 seek、最后软追赶 → 黑层写回」，
 *   应用顺序错位会让 resume 触发的 ENDED 自动回零盖掉 seek 目标；
 * - 旧纠偏循环的三删枝（`ended ||`、`wrapped &&`、`desired > rd-1 continue`）与
 *   `LoopMode.ONE` 不得回流（r02 根源性改动：EOS 从无缝回绕变成可检测事件）；
 * - seekTimeline/setPlaying/stepBoth 的时间线化与素材感知落点在位；
 * - 内核 [CompareTimeline] 保持纯 Kotlin（无任何 android 依赖，JVM 单测直打的前提）。
 *
 * 全部判据作用在 [KotlinSourceScan.codeOnly] 遮蔽后的文本上（注释提到旧符号不算违规）；
 * 红绿已突变实证：删掉 nudge 应用行 / 黑层写回行 / pre-roll 打点闸，对应断言转红。
 */
class CompareTimelineWiringGuardTest {

    private fun masked(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        KotlinSourceScan.bodyOf(masked(rel), funName)

    // ------------------------------------------------------------------ 编排循环体

    @Test
    fun `编排循环体_decide在位且应用顺序为停走_seek_软追赶_黑层写回`() {
        val body = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "orchestrateLoop"))
        listOf(
            "CompareTimeline.decide(" to "每拍必须交 decide 判素材域（删掉 = 退回无编排的双引擎各播各的）",
            "compareTickMs(prefs)" to "节拍必须从设置页读（删掉 = 用户设置不生效）",
            "cmd.pauseLeft" to "dormant 断言在位",
            "cmd.seekLeftMs" to "统一 seek 在位",
            "cmd.nudgeRightDriftMs" to "软追赶在位（删掉 = 双素材域漂移无纠偏）",
            "domBlackL = cmd.blackLeft" to "黑层位必须写回会话态（删掉 = 下一拍域判定失去回灌、黑层不亮）"
        ).forEach { (needle, why) ->
            assertTrue("编排循环体缺 $needle：$why", body.contains(needle))
        }
        // 应用顺序红线：pause/resume → seek → nudge → 黑层写回
        val iPause = body.indexOf("cmd.pauseLeft")
        val iSeek = body.indexOf("cmd.seekLeftMs")
        val iNudge = body.indexOf("cmd.nudgeRightDriftMs")
        val iBlack = body.indexOf("domBlackL = cmd.blackLeft")
        assertTrue(
            "应用顺序必须 pause → seek → nudge → 黑层写回（resume 触发的 ENDED 自动回零会被随后的 seek 覆盖，" +
                "nudge 必须落在最终位置上；乱序 = 回绕后右轨落错素材位）",
            iPause in 0 until iSeek && iSeek in 0 until iNudge && iNudge in 0 until iBlack
        )
    }

    @Test
    fun `旧纠偏循环三分枝与LoopMode_ONE不许回流`() {
        val content = body("player/CompareScreen.kt", "CompareContent")
        assertFalse(
            "LoopMode.ONE 不得回流（r02 根源改动：左引擎无缝回绕会让域判定永远读不出「左已尽」）",
            content.contains("LoopMode.ONE")
        )
        // 旧循环的检测记号：prevLeft/wrapped 是「靠位置回跳判回绕」的旧形态
        assertFalse(
            "旧纠偏循环的 prevLeft/wrapped 回绕检测不得回流（回绕语义已归 decide 的素材域判定）",
            content.contains("prevLeft") || content.contains("wrapped")
        )
        assertFalse(
            "旧纠偏周期常量 RESYNC_INTERVAL_MS 不得回流（节拍已归设置页 compare_tick_ms）",
            content.contains("RESYNC_INTERVAL_MS")
        )
    }

    // ------------------------------------------------------------------ 统一 seek / 播放 / 步进

    @Test
    fun `seekTimeline_park与黑层会话位在位`() {
        val body = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "seekTimeline"))
        assertTrue("无素材一侧必须就地 softPause(true)（park 完不许自己播走，黑层遮住）", body.contains("softPause(true)"))
        assertTrue(
            "黑层会话位必须按覆盖判定写入（domBlackL = …）：下一拍域判定靠它消歧「park 在边界」与「真双素材」",
            Regex("domBlack(L|R)\\s*=").containsMatchIn(body)
        )
        assertTrue("落位必须走几何 leftTarget/rightTarget（有素材精确落位、无素材 park）", body.contains("leftTarget(") && body.contains("rightTarget("))
    }

    @Test
    fun `setPlaying_素材感知与末态重播在位`() {
        val body = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "setPlaying"))
        assertTrue(
            "dormant 轨必须保持 softPause(true)（否则恢复播放会让黑层后的轨道偷偷走钟）",
            body.contains("domBlackL") && body.contains("domBlackR")
        )
        assertTrue(
            "时间线末点播放必须先 seekTimeline(Tmin) 再起播（引擎 ENDED 自动回零只回各引擎自己的 0，off≠0 时右轨落错素材位）",
            body.contains("seekTimeline(")
        )
    }

    @Test
    fun `stepBoth_主钟域步进在位`() {
        val body = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "stepBoth"))
        assertTrue("右独播域必须步进右引擎再减 off（主钟在右）", body.contains("rightEngine.stepFrame"))
        assertTrue("双素材/左独播域必须步进左引擎（主钟在左）", body.contains("leftEngine.stepFrame"))
        assertTrue("步进落点必须走 seekTimeline（无素材段 park 交给它）", body.contains("seekTimeline("))
        assertFalse(
            "步进不许直接 seekTo(t + offset) 旧形态（无素材段会被引擎 coerce 到 0 误播）",
            body.contains("offsetMs.value).coerceAtLeast")
        )
    }

    @Test
    fun `统一轴与读数时间线化在位`() {
        val content = body("player/CompareScreen.kt", "CompareContent")
        assertTrue(
            "进度条/读数必须按 TimelineGeometry 取 (x−Tmin)/(Tmax−Tmin) 口径",
            content.contains("TimelineGeometry(") && content.contains("CompareTimeline.resolveT(")
        )
        // 两窗黑层常驻 Box：alpha 0/1 切换（增删节点会闪帧）
        assertTrue(
            "左窗黑层常驻 Box 在位（alpha 0/1 切换）",
            content.contains("alpha(if (domBlackL) 1f else 0f)")
        )
        assertTrue(
            "右窗黑层常驻 Box 在位（alpha 0/1 切换）",
            content.contains("alpha(if (domBlackR) 1f else 0f)")
        )
        // pre-roll 打点闸：T<0 时 A/B 钮给横幅（复用 player_ab_invalid 通道）
        assertTrue(
            "pre-roll（T<0）必须挡下 AB 打点并给横幅（AB 数值是左片时间，pre-roll 区左片停在 park 位）",
            content.contains("currentTimelineMs() < 0L")
        )
        // 全程循环 pill 与 AB 回绕改道 seekTimeline
        assertTrue("全程循环开关（timelineLoop）在位", content.contains("timelineLoop = !timelineLoop"))
        assertTrue(
            "AB 回绕必须走 seekTimeline(aMs)（顺带修掉 off<0 且 A+off<0 时 seekTo 负值被 coerce 误播的旧边角）",
            content.contains("seekTimeline(aMs)")
        )
    }

    // ------------------------------------------------------------------ 内核纯度

    @Test
    fun `内核保持纯Kotlin_无android依赖`() {
        // 词法遮蔽后的文本 = 纯代码字符：import 与任何 android 引用都会在这里现形
        val kernel = masked("player/CompareTimeline.kt")
        assertFalse(
            "player/CompareTimeline.kt 必须保持纯 Kotlin（无 android/androidx 依赖）：JVM 单测直打的前提",
            kernel.contains("android")
        )
        // 域判定的退出条件与三入口在位（删任一分支，CompareTimelineTest 对应用例已实证转红）
        val domain = KotlinSourceScan.flatten(body("player/CompareTimeline.kt", "domainOf"))
        assertTrue("域判定必须带黑层在册消歧分支", domain.contains("blackLeft") && domain.contains("blackRight"))
        assertTrue("域判定必须带时间线末出口", domain.contains("TIMELINE_END"))
    }
}
