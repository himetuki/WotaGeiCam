package com.wotagei.cam

import com.wotagei.cam.ui.design.LikeBurst
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏红心动画的时间线守卫（2026-10-30，用户需求：相册"收藏"用 Bencho "Like" 的红心动画，
 * **不要数字滚动**）。
 *
 * 设计稿（MIT）把每段关键帧都写死了，本工程按同一条时间线移植到 `LikeBurst` 纯函数层。
 * 用例钉三类会真红的事：
 * 1. **分段取值**：红心 0→1.25→1、环 0.15→1→1.12、边框 1→2/17→0——任一关键帧写错立刻红；
 * 2. **延迟窗口**：彩点 200ms 后才开始（dotProgress < 0），这是设计稿"环先起飞"的秩序；
 * 3. **组件接线**：只播收藏那一下、不播取消；PLAIN/reduced 档不播；**没有数字滚动**。
 */
class LikeBurstTest {

    @Test
    fun `红心缩放按设计稿三段取值`() {
        assertEquals("起点不可见", 0f, LikeBurst.heartScale(0f), 0.0001f)
        assertEquals("28% 之前仍不可见", 0f, LikeBurst.heartScale(0.28f * 760f / 820f), 0.02f)
        assertEquals("62% 弹过自身尺寸 1.25", 1.25f, LikeBurst.heartScale(0.62f * 760f / 820f), 0.02f)
        assertEquals("终点回到 1", 1f, LikeBurst.heartScale(1f), 0.0001f)
        // 单调：28%~62% 区间必须一路涨（写反方向或漏 smooth 会红）
        val rising = (28..62 step 4).map { LikeBurst.heartScale((it / 100f) * 760f / 820f) }
        assertTrue("28%~62% 必须单调上升", rising.zipWithNext().all { (a, b) -> b >= a - 0.0001f })
    }

    @Test
    fun `绽放环按设计稿实心到空心`() {
        assertEquals("起点 0.15", 0.15f, LikeBurst.bloomScale(0f), 0.0001f)
        assertEquals("40% 长到原尺寸", 1f, LikeBurst.bloomScale(0.4f * 560f / 820f), 0.02f)
        assertEquals("终点 1.12", 1.12f, LikeBurst.bloomScale(1f), 0.0001f)
        assertEquals("起点是实心（边框占满半径）", 1f, LikeBurst.bloomBorderFraction(0f), 0.0001f)
        assertEquals(
            "75% 已抽成 2/17 的细环",
            2f / 17f,
            LikeBurst.bloomBorderFraction(0.75f * 560f / 820f),
            0.01f
        )
        assertEquals("终点边框归零（洞完全打开）", 0f, LikeBurst.bloomBorderFraction(1f), 0.0001f)
        assertEquals("75% 前不透明", 1f, LikeBurst.bloomAlpha(0.7f * 560f / 820f), 0.0001f)
        assertEquals("终点淡出", 0f, LikeBurst.bloomAlpha(1f), 0.0001f)
        assertEquals("40% 前是红", 0f, LikeBurst.bloomPurple(0.3f * 560f / 820f), 0.0001f)
        assertEquals("40% 后转紫", 1f, LikeBurst.bloomPurple(0.5f * 560f / 820f), 0.0001f)
    }

    @Test
    fun `彩点有延迟窗口且沿辐条甩到端点缩没`() {
        assertTrue("200ms 之前必须没开始（-1）", LikeBurst.dotProgress(0.1f) < 0f)
        assertEquals("终点进度 1", 1f, LikeBurst.dotProgress(1f), 0.0001f)
        // 径向：12 → 18 + fly×14 + far（far = 0 或 -5）
        assertEquals("起点 12px", 12f, LikeBurst.dotRadiusPx(0f, 0.5f, 0f), 0.0001f)
        assertEquals("终点 18+7+0", 25f, LikeBurst.dotRadiusPx(1f, 0.5f, 0f), 0.0001f)
        assertEquals("第二颗靠后 5px", 20f, LikeBurst.dotRadiusPx(1f, 0.5f, -5f), 0.0001f)
        // 侧向 ±4 → ±6.4；缩放 1 → 0；透明度 12% 前爬升
        assertEquals("侧向终点 1.6 倍", 6.4f, LikeBurst.dotSidePx(1f, 4f), 0.0001f)
        assertEquals("端点缩没", 0f, LikeBurst.dotScale(1f), 0.0001f)
        assertEquals("开始前不可见", 0f, LikeBurst.dotAlpha(0f), 0.0001f)
        assertEquals("12% 后全亮", 1f, LikeBurst.dotAlpha(0.5f), 0.0001f)
    }

    @Test
    fun `组件只播收藏那一下且没有数字滚动`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/design/LikeHeart.kt"))
        assertTrue("取消收藏不播效果（只掏空）", src.contains("if (!next || !playEffect) return"))
        assertTrue("PLAIN/reduced 档不播（设计稿 reduced-motion 同理）", src.contains("motion.mode != MotionMode.PLAIN && motion.durationMs != 0"))
        assertTrue("主进度总时长来自设计稿", src.contains("DURATION_MS = 820"))
        // 用户明确"不要数字变化"：底片不许出现任何位数/滚轮实现
        assertTrue("不许出现数字滚轮", !src.contains("lk-wheel") && !src.contains("Roll("))
        // 相册接线：收藏入口必须真的用上这颗心
        val gallery = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/GalleryScreen.kt"))
        assertTrue("相册收藏必须接 LikeHeart", gallery.contains("LikeHeart("))
        assertTrue("收藏回调必须走 setLike", gallery.contains("onToggle = { ops.setLike(clip, it) }"))
    }
}
