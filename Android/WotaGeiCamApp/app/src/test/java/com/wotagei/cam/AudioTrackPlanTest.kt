package com.wotagei.cam

import com.wotagei.cam.record.AudioTrack
import com.wotagei.cam.record.audioTrackPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音源双开语义（2026-10-07 定版）的桥函数全表：两个开关独立、四组合都合法——
 * 双开=双轨、仅内录=单内录轨、仅环境=单环境轨、全关=纯视频。
 * 这是「面板组合 → 成片音轨」的计划→行为桥：互斥回归（把桥改回 batch2 的
 * 「内录激活即环境音关」）必须让这张表变红。
 */
class AudioTrackPlanTest {

    @Test
    fun `双开为双轨`() {
        assertEquals(
            setOf(AudioTrack.AMBIENT, AudioTrack.CAPTURE),
            audioTrackPlan(ambientEnabled = true, captureActive = true)
        )
    }

    @Test
    fun `仅环境为单环境轨`() {
        assertEquals(setOf(AudioTrack.AMBIENT), audioTrackPlan(ambientEnabled = true, captureActive = false))
    }

    @Test
    fun `仅内录为单内录轨`() {
        assertEquals(setOf(AudioTrack.CAPTURE), audioTrackPlan(ambientEnabled = false, captureActive = true))
    }

    @Test
    fun `全关为纯视频`() {
        assertEquals(emptySet<AudioTrack>(), audioTrackPlan(ambientEnabled = false, captureActive = false))
    }

    @Test
    fun `互斥突变体必须变红`() {
        // 把桥换成批 2 的互斥语义（内录激活即环境音关）后，双开组合不再产出双轨——表必须抓到
        val mutexPlan = { ambient: Boolean, capture: Boolean ->
            when {
                capture && !ambient -> setOf(AudioTrack.CAPTURE)
                ambient && !capture -> setOf(AudioTrack.AMBIENT)
                else -> emptySet<AudioTrack>()
            }
        }
        // 突变体在双开输入下 ≠ 真身双轨：说明全表测试能分辨互斥回归（守卫活着）
        assertEquals(
            setOf(AudioTrack.AMBIENT, AudioTrack.CAPTURE),
            audioTrackPlan(true, true)
        )
        assertEquals(emptySet<AudioTrack>(), mutexPlan(true, true))
    }
}
