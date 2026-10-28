package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧率坍塌守卫的纯逻辑账（2026-10-28 用户6/7）：
 * 宽限 / 持续 / 滞回 / 换档重置 / 零到达，五个口径各钉一组。
 * 引擎侧采样（1s 一拍）与发布见 Camera2Engine.sampleCameraRate，这里只测判定。
 */
class LowFpsWatchTest {

    /** 24fps 档、固定 1s 步进喂拍的常用桩 */
    private fun feed(watch: LowFpsWatch, vararg rates: Float, startMs: Long = 1_000L): List<Boolean> {
        val out = mutableListOf<Boolean>()
        rates.forEachIndexed { i, r -> out += watch.onSample(startMs + i * 1_000L, r) }
        return out
    }

    @Test
    fun `宽限期内持续低帧不触发`() {
        val w = LowFpsWatch(24)
        // 4s 宽限内的 4 拍全部 5fps：不告警
        assertFalse(feed(w, 5f, 5f, 5f, 5f).any { it })
    }

    @Test
    fun `宽限期后连续四拍低帧才触发`() {
        val w = LowFpsWatch(24)
        // 前四拍在宽限内（1s..4s），从第 5 秒起出宽限：连续 4 拍 5fps，第 4 拍翻转
        val r = feed(w, 5f, 5f, 5f, 23f, 5f, 5f, 5f, 5f)
        assertEquals(listOf(false, false, false, false, false, false, false, true), r)
    }

    @Test
    fun `单拍低帧被正常拍打断则连续计数清零`() {
        val w = LowFpsWatch(24)
        // 5,5,5（宽限尾）→ 23（清零）→ 5,5,5（出宽限后只攒 3 拍）→ 不触发
        val r = feed(w, 5f, 5f, 5f, 23f, 5f, 5f, 5f)
        assertFalse(r.last())
    }

    @Test
    fun `触发后恢复带内保持告警_过恢复线才解除`() {
        val w = LowFpsWatch(24)
        // 制造触发：出宽限后连 4 拍 5fps
        feed(w, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f)
        // 滞回：恢复带（< 0.85×24=20.4，取 19）内维持告警开，不翻转也不闪烁
        assertTrue(w.onSample(20_000L, 19f))
        // 过恢复线（≥ 20.4）：解除
        assertFalse(w.onSample(21_000L, 21f))
        // 解除后正常拍保持关
        assertFalse(w.onSample(22_000L, 23f))
    }

    @Test
    fun `换档重置_旧账作废_新档按新目标判`() {
        val w = LowFpsWatch(24)
        feed(w, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f)
        assertTrue(w.onSample(9_000L, 5f)) // 已触发
        w.reset(120)
        // 120fps 档：5fps 连续四拍才触发（宽限也重来）；先来两拍低帧不该触发
        val r = feed(w, 5f, 5f, 5f, 5f, startMs = 10_000L)
        assertFalse(r.any { it })
    }

    @Test
    fun `零到达按低帧计_完全断流也要能告警`() {
        val w = LowFpsWatch(24)
        // 用户7 现场：实测 0fps（结果一拍都没来）也必须能走到触发
        val r = feed(w, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertTrue(r.last())
    }

    @Test
    fun `targets_配当前档位与否`() {
        val w = LowFpsWatch(24)
        assertTrue(w.targets(24))
        assertFalse(w.targets(25))
        w.reset(25)
        assertTrue(w.targets(25))
    }
}
