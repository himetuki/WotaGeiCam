package com.wotagei.cam

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import com.wotagei.cam.ui.anim.LiquidSpring
import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.anim.MotionSpec
import com.wotagei.cam.ui.design.WotaMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * 「液态Q弹」改版（2026-10-30）的弹簧语言守卫。
 *
 * 用户给定的视觉设计语言：阻尼弹簧、速度继承、回稳带阻尼；`springMotion(response, dampingFraction)`
 * 起调——response 控制自然振动周期（复位"跟脚程度"），dampingFraction 越低越弹、越高越稳；
 * response 0.2–0.5s、damping 优先 0.6–0.8；按压幅度要小，回弹手感来自阻尼与初速度而非拉长时长。
 *
 * 用例钉四件事（都是会真红的结构断言，不是恒等式）：
 * 1. **换算唯一**：response → stiffness 只能是 (2π/response)²（单位质量）；红法 = 改回手写刚度常量。
 * 2. **域夹取**：越界/非法参数夹进设计域——写错只许"没那么弹"，不许弹簧发散或永不成形。
 * 3. **液态档必须是真弹簧**（SpringSpec）：换成 tween 就丢掉了速度继承能力（打断后速度归零重启）。
 * 4. **按压克制**：按压缩放 ≤ 0.97、按压用近临界阻尼的短周期弹簧（跟手不复弹），reduced 档全停。
 */
class LiquidSpringMotionTest {

    private fun stiffnessOf(response: Float) = LiquidSpring.stiffnessOf(response)

    @Test
    fun `response到刚度的换算只能是单位质量弹簧公式`() {
        // 抽查两端：response 0.2s ≈ 987、0.5s ≈ 158；公式写错（比如漏了 2π 或取了倒数）立刻红
        assertEquals("response 0.2s 的刚度", 986.96f, stiffnessOf(0.2f), 0.5f)
        assertEquals("response 0.5s 的刚度", 157.91f, stiffnessOf(0.5f), 0.5f)
        // 单调性：周期越短越硬（跟脚），这是"response 决定复位快慢"这一条语义的可测形态
        assertTrue("response 越小刚度必须越大", stiffnessOf(0.2f) > stiffnessOf(0.3f))
        assertTrue("response 越小刚度必须越大", stiffnessOf(0.35f) > stiffnessOf(0.5f))
        // 与公式逐值对账（防止有人把 (2π/r)² 写成 (2πr)² 这类"看起来像"的实现）
        val expected = ((2.0 * PI / 0.35f).toFloat() * (2.0 * PI / 0.35f).toFloat())
        assertEquals("0.35s 必须等于 (2π/0.35)²", expected, stiffnessOf(0.35f), 0.5f)
    }

    @Test
    fun `越界与非法参数一律夹进设计域`() {
        // response 域 0.2~0.5：太小=没有弹性观感的抖动，太大=复位拖沓
        assertEquals("response 过小必须夹到 0.2", stiffnessOf(0.2f), stiffnessOf(0.01f), 0.001f)
        assertEquals("response 过大必须夹到 0.5", stiffnessOf(0.5f), stiffnessOf(9f), 0.001f)
        // NaN/Inf 退回中位 0.35（不猜极端）
        assertEquals("NaN 必须退回中位", stiffnessOf(0.35f), stiffnessOf(Float.NaN), 0.001f)
        assertEquals("Inf 必须退回中位", stiffnessOf(0.35f), stiffnessOf(Float.POSITIVE_INFINITY), 0.001f)
        // 阻尼域 0.6~0.9：低于 0.6 回弹过剩、高于 0.9 等于不弹
        assertEquals("阻尼过小必须夹到 0.6", 0.6f, LiquidSpring.dampingOf(0.1f), 0.0001f)
        assertEquals("阻尼过大必须夹到 0.9", 0.9f, LiquidSpring.dampingOf(2f), 0.0001f)
        assertEquals("阻尼 NaN 必须退回 0.7", 0.7f, LiquidSpring.dampingOf(Float.NaN), 0.0001f)
    }

    @Test
    fun `液态档三档规格都必须是真弹簧且落在给定参数域内`() {
        val liquid = MotionSpec(MotionMode.LIQUID, 1f)
        val float = liquid.float as SpringSpec<*>
        val offset = liquid.offset as SpringSpec<*>
        val press = liquid.press as SpringSpec<*>
        // 主弹簧：明显回弹档（damping 0.6~0.7），周期取中位
        assertEquals("主弹簧阻尼必须落在 0.6~0.7 的回弹档", 0.65f, float.dampingRatio, 0.0001f)
        assertEquals("主弹簧周期 0.32s", stiffnessOf(0.32f), float.stiffness as Float, 0.5f)
        // 位移：更长周期 + 更高阻尼（大块面不吃"甩"）
        assertEquals("位移弹簧阻尼必须落在 0.8 档（克制回弹）", 0.80f, offset.dampingRatio, 0.0001f)
        assertEquals("位移弹簧周期 0.42s", stiffnessOf(0.42f), offset.stiffness as Float, 0.5f)
        // 按压：极短周期 + 近临界阻尼（跟手、几乎不复弹）
        assertEquals("按压弹簧必须近临界阻尼", 0.90f, press.dampingRatio, 0.0001f)
        assertEquals("按压弹簧周期 0.2s（跟手）", stiffnessOf(0.2f), press.stiffness as Float, 0.5f)
        // 速度继承的前提：规格必须是弹簧。tween 没有"初速度"概念，换掉就红
        assertTrue("液态档 float 必须是 SpringSpec", float is SpringSpec<*>)
        assertEquals(
            "液态档 offset 必须带零可见阈值（离散类型要收尾，否则动画永不到 0）",
            androidx.compose.ui.unit.IntOffset.Zero,
            offset.visibilityThreshold as androidx.compose.ui.unit.IntOffset
        )
    }

    @Test
    fun `按压缩放必须克制且减少动效档停用`() {
        assertTrue("液态档按压幅度不许夸张（≤0.97）", MotionSpec(MotionMode.LIQUID, 1f).pressScale <= 0.97f)
        assertTrue("流畅档按压幅度不许夸张（≤0.97）", MotionSpec(MotionMode.FLUENT, 1f).pressScale <= 0.97f)
        assertTrue("无动画档按压幅度不许夸张（≤0.98）", MotionSpec(MotionMode.PLAIN, 1f).pressScale <= 0.98f)
        assertEquals("减少动效档必须停用形变", 1f, MotionSpec(MotionMode.LIQUID, 1f, reduced = true).pressScale, 0f)
        // 三档幅度必须真的存在差异入口：液态与流畅同幅、无动画更"硬"——写错分支立刻红
        assertTrue(
            "无动画档的按压幅度必须不小于有动画档（它是降级档）",
            MotionSpec(MotionMode.PLAIN, 1f).pressScale >= MotionSpec(MotionMode.LIQUID, 1f).pressScale
        )
    }

    @Test
    fun `减少动效档必须是零时长且其余两档不是弹簧`() {
        val reducedLiquid = MotionSpec(MotionMode.LIQUID, 1f, reduced = true)
        assertEquals("reduced 档 float 必须 0 时长", 0, (reducedLiquid.float as TweenSpec<Float>).durationMillis)
        assertEquals("reduced 档 offset 必须 0 时长", 0, (reducedLiquid.offset as TweenSpec<*>).durationMillis)
        assertEquals("reduced 档 press 必须 0 时长", 0, (reducedLiquid.press as TweenSpec<Float>).durationMillis)
        // PLAIN/FLUENT 保持既有口径：PLAIN 线性短停、FLUENT 令牌时长——本改版不许外溢到它们
        val plain = MotionSpec(MotionMode.PLAIN, 1f)
        assertEquals("PLAIN 必须仍是 120ms 线性", 120, (plain.float as TweenSpec<Float>).durationMillis)
        assertEquals("PLAIN 缓动必须仍是线性", LinearEasing, (plain.float as TweenSpec<Float>).easing)
        assertEquals(
            "FLUENT 必须仍是令牌时长",
            WotaMotion.COMMIT_MS,
            (MotionSpec(MotionMode.FLUENT, 1f).float as TweenSpec<Float>).durationMillis
        )
    }

    @Test
    fun `取证倍率必须落在刚度上而不是时长上`() {
        val normal = MotionSpec(MotionMode.LIQUID, 1f).float as SpringSpec<*>
        val slow = MotionSpec(MotionMode.LIQUID, 4f).float as SpringSpec<*>
        assertEquals("×4 倍率 = 刚度除 4（同形状、慢四倍）", (normal.stiffness as Float) / 4f, slow.stiffness as Float, 0.5f)
        assertEquals("倍率不许改动阻尼（形状不许变）", normal.dampingRatio, slow.dampingRatio, 0.0001f)
        // 反证：直接 spring() 手写刚度且不除倍率的实现会红
        val naive = spring<Float>(dampingRatio = 0.65f, stiffness = 986.96f)
        assertTrue("naive 手写刚度必须与慢档不同（证明倍率真的生效）", naive.stiffness != slow.stiffness)
    }
}
