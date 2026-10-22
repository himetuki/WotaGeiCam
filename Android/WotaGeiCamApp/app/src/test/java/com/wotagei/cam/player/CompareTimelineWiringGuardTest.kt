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

    // ------------------------------------------------- 入口/选片起播（r05 修 r04 双黑）

    /**
     * r04 真机「进页左黑、选完右片双黑」的根因在**接线**不在内核：进页=两引擎 attach 后未起播
     * （PlayerEngine.attach 固定 playWhenReady=false）、无在册黑层、userPlaying=false——decide
     * 对这个态是不动点（resume 门=blackX && userPlaying，userPlaying 只能由已起的播放产生），
     * 不接线显式起播就永远静止。这里钉三条接线红线（红绿：修复前三条全红，修复后全绿）。
     */

    @Test
    fun `进页自动播_attach后立即起播左片`() {
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        val attach = "leftEngine.attach(left.uri)"
        val iAttach = content.indexOf(attach)
        val iPlay = content.indexOf("leftEngine.softPause(false)", iAttach)
        assertTrue(
            "左片 attach 后必须紧跟 softPause(false) 进页自动开播（需求 27 行「左右同时播放」+ 单播放页" +
                "PlayerScreen 同款；缺它 = 进页左片静止，且 decide 的 resume 门等不到 userPlaying，永久双黑）",
            iAttach >= 0 && iPlay == iAttach + attach.length + 1
        )
    }

    @Test
    fun `会话播放意图_进页为真且由setPlaying写`() {
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        assertTrue(
            "会话播放意图 wantPlaying 必须进页为 true（进页/选片后自动播是对比页的既定口径）",
            content.contains("var wantPlaying by remember { mutableStateOf(true) }")
        )
        val sp = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "setPlaying"))
        assertTrue(
            "setPlaying 必须写会话播放意图（用户显式暂停后，选片/练习回填不得自动唤醒）",
            sp.contains("wantPlaying = play")
        )
    }

    @Test
    fun `选片回填后_wantPlaying在册则setPlaying起播`() {
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        val iAttach = content.indexOf("rightEngine.attach(clip.uri)")
        val iStart = content.indexOf("wantPlaying) setPlaying(true)")
        assertTrue(
            "右片 attach 之后必须有会话意图门下的 setPlaying(true)：中途挂上的右轨没有任何起播路径" +
                "（交接 resume 只认在册黑层），选片器 round-trip 的 ON_STOP 软暂停也要靠这次用户动作续上；" +
                "且必须声明在 attach effect 之后（Compose effect 按声明序启动，起播前 attach 必须已落位）",
            iAttach in 0 until iStart
        )
    }

    // ------------------------------------------------------------------ 音频剪辑模型

    @Test
    fun `音频选择必须经CompareAudio_不得裸调选轨API`() {
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        assertTrue(
            "对比页音频必须经 CompareAudio（会话态 → audioPlanOf → applyOrderOf → applyAudioCommand），" +
                "组下标单一映射（audioGroupIndexOf），禁止各处各写 (1, 0) 字面量",
            content.contains("CompareAudio.audioPlanOf(") && content.contains("CompareAudio.applyOrderOf(")
        )
        assertFalse(
            "CompareScreen 不得裸调 setAudioTrackOverride（唯一映射函数之外写死组下标 = 迟早选轨失效）",
            content.contains("setAudioTrackOverride(")
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
