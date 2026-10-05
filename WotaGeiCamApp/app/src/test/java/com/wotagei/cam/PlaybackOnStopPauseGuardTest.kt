package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切后台自动暂停的**源码结构守卫**（2026-10-04 产品裁决，翻案旧 R6「ON_STOP 不暂停」）。
 *
 * 行为（播放器真的停不停）JVM 测不到（ExoPlayer 是 Android 运行时件），这里钉**结构红线**：
 * - ON_STOP 暂停必须挂在 `rememberPlayerEngine` 工厂体内——单播放页/对比页（双引擎）/分屏练习
 *   /媒体库播放页四面都经它取引擎，一处挂齐全覆盖；谁把这段挪走/删掉，四面同时退回「后台继续出声」；
 * - 暂停必须走 `softPause(true)`（唯一允许的暂停语义：保进度不 seek）；
 * - 工厂体内**不许出现恢复方向的调用**（ON_RESUME 自动续播是被裁决否掉的产品行为，
 *   恢复只许由用户手点播放触发）。
 */
class PlaybackOnStopPauseGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    @Test
    fun `引擎工厂ON_STOP暂停在位且不自动续播`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("player/PlayerScreen.kt"), "rememberPlayerEngine")
        )
        assertTrue(
            "引擎工厂必须挂 Lifecycle.Event.ON_STOP 观察处（删掉 = 四面全部退回后台继续播）",
            body.contains("Lifecycle.Event.ON_STOP")
        )
        assertTrue(
            "ON_STOP 必须走 softPause(true)（唯一允许的暂停语义：只收 playWhenReady，进度/AB/循环全保留）",
            body.contains("softPause(true)")
        )
        assertFalse(
            "工厂体内不许出现 softPause(false)（回前台不自动续播是产品裁决，恢复只许用户手点）",
            body.contains("softPause(false)")
        )
        assertFalse(
            "工厂体内不许出现 play() 直调（同上，ON_RESUME 自动续播不许复活）",
            Regex("\\bplay\\(\\)").containsMatchIn(body)
        )
    }

    @Test
    fun `四个播放面都经引擎工厂取引擎`() {
        // 覆盖面成立的前提：四个播放面谁也不许绕开工厂自建 PlayerEngine
        listOf(
            "player/PlayerScreen.kt" to "单播放页",
            "player/CompareScreen.kt" to "对比页（双引擎）",
            "ui/CameraScreen.kt" to "分屏练习播放器",
            "media/GalleryScreen.kt" to "媒体库播放页"
        ).forEach { (rel, name) ->
            assertTrue(
                "$name 必须经 rememberPlayerEngine 取引擎（ON_STOP 暂停随工厂覆盖，绕开 = 该面漏挂）",
                maskedMain(rel).contains("rememberPlayerEngine(")
            )
        }
    }
}
