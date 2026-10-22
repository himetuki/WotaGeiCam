package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对比播放 r06（黑屏根因修复 + 剪辑模型）的**源码结构守卫**。
 *
 * 行为（面到底黑不黑、音频到底哪条在响）JVM 测不到（ExoPlayer/TextureView 是 Android 运行时件），
 * 这里钉结构红线，防重构时顺手删：
 * - 播放面绑定契约：`PlayerEngine.build()` 必须把 player 回推给已登记视图；`WotaPlayerSurface`
 *   的 factory 里必须 `engine.bind(`（不依赖 update 是否重跑）；
 * - 编排体仍走 `CompareTimeline.decide`，应用顺序「先停/走 → 再 seek → 最后软追赶」不变；
 * - 既有功能接线（AB 回绕、统一 seek、练习桥、镜像/倍速）仍在；
 * - 音频选择必须经 `CompareAudio`，不得在 `CompareScreen` 里裸调 `setAudioTrackOverride`。
 *
 * 红绿已突变实证（见汇报）：删 build() 回推行 / 删 factory 的 engine.bind / 在 CompareScreen 写回
 * `setAudioTrackOverride(...)` 字面量，对应断言转红。
 */
class ComparePlaybackGuardTest {

    private fun masked(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        KotlinSourceScan.bodyOf(masked(rel), funName)

    // ---------------------------------------------------------------- 播放面绑定契约

    @Test
    fun `引擎build_回推player给已登记视图`() {
        val build = KotlinSourceScan.flatten(body("player/PlayerEngine.kt", "build"))
        assertTrue(
            "build() 建好 player 后必须把它补挂给 boundViews（attach 懒建晚于首帧：factory/update 首次" +
                "bind 时 player 还是 null，没有回推则面拿不到 player 恒黑）",
            build.contains("boundViews") && build.contains(".player = p")
        )
    }

    @Test
    fun `播放面factory_立刻bind登记视图`() {
        val surface = KotlinSourceScan.flatten(body("player/PlayerScreen.kt", "WotaPlayerSurface"))
        val iUpdate = surface.indexOf("update = { view ->")
        val iFirstBind = surface.indexOf("engine.bind(view)")
        assertTrue(
            "WotaPlayerSurface 的 factory 里必须 engine.bind(view)（且在 update 之前）：把视图登记进 " +
                "boundViews，build() 的回推才找得到它（不依赖 update 的重跑语义）。只查 contains 会被 " +
                "update 里的那次 bind 蒙混过关，所以这里比顺序",
            iFirstBind in 0 until iUpdate
        )
        assertTrue(
            "update 的 engine.bind 必须保留（幂等增益）",
            iUpdate >= 0 && surface.indexOf("engine.bind(view)", iUpdate) >= 0
        )
    }

    // ---------------------------------------------------------------- 黑层绘制顺序（双黑真因）

    @Test
    fun `黑层Box_alpha必须在background之前`() {
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        assertTrue(
            "左窗黑层必须是 .alpha(...).background(Color.Black)：modifier 链左为外右为内，alpha 只作用在" +
                "它右边的绘制上；顺序反了黑底恒不透明，两窗被常驻纯黑永久盖死（2026-10-06 双黑真因）",
            content.contains("alpha(if (domBlackL) 1f else 0f).background(Color.Black)")
        )
        assertTrue(
            "右窗黑层同样是 .alpha(...).background(Color.Black)",
            content.contains("alpha(if (domBlackR) 1f else 0f).background(Color.Black)")
        )
        assertFalse(
            "黑层 Box 不得写回 .background(Color.Black).alpha(...)（这正是双黑真因的错误形态）",
            content.contains("background(Color.Black).alpha(")
        )
    }

    // ---------------------------------------------------------------- 编排顺序仍在

    @Test
    fun `编排体_decide在位且顺序不变`() {
        val body = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "orchestrateLoop"))
        assertTrue("编排体必须仍交 CompareTimeline.decide 判素材域", body.contains("CompareTimeline.decide("))
        val iPause = body.indexOf("cmd.pauseLeft")
        val iSeek = body.indexOf("cmd.seekLeftMs")
        val iNudge = body.indexOf("cmd.nudgeRightDriftMs")
        assertTrue(
            "应用顺序必须 pause → seek → nudge（乱序 = 回绕后右轨落错素材位）",
            iPause in 0 until iSeek && iSeek in 0 until iNudge
        )
    }

    // ---------------------------------------------------------------- 既有功能接线在册

    @Test
    fun `既有功能接线_回绕_统一seek_练习桥_镜像倍速仍在`() {
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        listOf(
            "abWrapHook =" to "AB 回绕 hook",
            "seekTimeline(" to "统一轴落位口",
            "ComparePractice.primeWith(" to "对着左片练的练习桥",
            "MirrorPopup(" to "镜像弹层",
            "SpeedTierPopup(" to "倍速弹层",
            "leftEngine.setSpeed(tier)" to "左片倍速下发",
            "rightEngine.setSpeed(tier)" to "右片倍速下发",
            "pinchZoom(" to "缩放/平移手势"
        ).forEach { (needle, why) ->
            assertTrue("既有功能接线缺 $needle ($why)——重构时被顺手删了", content.contains(needle))
        }
    }

    // ---------------------------------------------------------------- 选轨接线（重放 key + 门同源）

    @Test
    fun `对比页选轨重放effect_key必须含两侧audioGroups_size`() {
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        assertTrue(
            "选轨重放 effect 的 key 必须含两侧 audioGroups.size：attach 会 clearAudioOverride、轨读数要等 " +
                "onTracksChanged 才到，只在选轨那一刻下发会丢（\"选完没效果\"经典坑）。删掉这两个 key " +
                "旧守卫（全文件 contains / 925 测）一条都不会红——本断言就是补上这枚缺口。",
            content.contains(
                "LaunchedEffect(audioSide, audioTrack, r?.uri, leftAudioGroups.size, rightAudioGroups.size)"
            )
        )
    }

    @Test
    fun `对比页选轨UI门与行为门必须同源`() {
        // 审查实测：UI 门用 TrackSync.countAudioTracks，行为门却用引擎上报的 audioGroups.size ⇒
        // 两源不一致时「UI 能选、点了没效果」或「双音轨片看不到 chip」。收敛成唯一真源 audioSideDual。
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        assertTrue(
            "双音轨唯一真源必须来自 countAudioTracks 探测（audioSideDual = audioSideTrackCount >= 2）",
            content.contains("audioSideDual = audioSideTrackCount >= 2")
        )
        assertTrue(
            "行为门必须喂同一个 audioSideDual（不许再让 audioGroups.size 当行为门）",
            content.contains("CompareAudio.audioPlanOf(audioSide, audioTrack, audioSideDual)")
        )
        assertTrue(
            "音轨 chip 门必须用同一个 audioSideDual（与行为门同源）",
            content.contains("if (audioSideDual)")
        )
    }

    // ---------------------------------------------------------------- 音频必须经 CompareAudio

    @Test
    fun `音频选择必须经CompareAudio_不得裸调setAudioTrackOverride`() {
        val content = KotlinSourceScan.flatten(body("player/CompareScreen.kt", "CompareContent"))
        assertTrue(
            "对比页音频必须经 CompareAudio（audioPlanOf/applyOrderOf → applyAudioCommand）",
            content.contains("CompareAudio.audioPlanOf(") && content.contains("CompareAudio.applyOrderOf(")
        )
        assertFalse(
            "CompareScreen 不得裸调 setAudioTrackOverride（组下标必须单一映射，禁止各写字面量）",
            content.contains("setAudioTrackOverride(")
        )
        assertFalse("CompareScreen 不得裸调 clearAudioOverride", content.contains("clearAudioOverride("))
        assertFalse("CompareScreen 不得裸调 setVolume", content.contains("setVolume("))
    }
}
