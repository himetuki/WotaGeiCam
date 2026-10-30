package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 液态手势开关的接线守卫（2026-10-30，用户指令「液态Q弹风格下所有开关控件变成这样」）。
 *
 * 设计取自 Bencho 的 "Liquid toggle"（MIT），本工程按同一套视觉语言在 Compose 重写
 * （`ui/design/WotaSwitch.kt`）。本类钉四件会真红的事：
 *
 * 1. **液态档才是液态**：不是 LIQUID 时必须是 Material3 开关原样（强调色对不变），
 *    不许把别档也悄悄换成新控件；
 * 2. **全工程开关收口**：设置页与内录音源面板不许再出现裸 Material3 `Switch(`，
 *    否则"所有开关控件"这条要求就会漏；
 * 3. **速度继承**：落位必须 `animateTo(..., initialVelocity = ...)`——松手速度接着走，
 *    不是归零重启（这是这次视觉语言的原话）；
 * 4. **软着地弹簧的换算**：设计稿 stiffness 170 / damping 21.5 / mass 0.9 ⇔ 单位质量
 *    ζ≈0.87、等效刚度≈189；两个数必须同源出现在源码里（单独改一个 = 形状漂了）。
 */
class LiquidSwitchWiringTest {

    private val switchSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/design/WotaSwitch.kt"))
    }
    private val settingsSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
    }
    private val audioSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/AudioSourcePanel.kt"))
    }

    @Test
    fun `只有液态档换成液态开关_其余档保持Material3原样`() {
        assertTrue(
            "必须按动效档分流（motion.mode != LIQUID 走 Material3 开关）",
            switchSrc.contains("if (motion.mode != MotionMode.LIQUID)")
        )
        assertTrue(
            "非液态档必须仍是强调色对（accentActive 轨道 / onAccent 拇指）",
            switchSrc.contains("checkedTrackColor = WotaColor.accentActive") &&
                switchSrc.contains("checkedThumbColor = WotaColor.onAccent")
        )
        assertTrue(
            "液态档必须走手势开关实现（不是又一个 Material3 换皮）",
            switchSrc.contains("LiquidGestureSwitch(")
        )
    }

    @Test
    fun `全工程开关必须收口到WotaSwitch`() {
        // 这条是"所有开关控件"的字面落实：两处 UI 里不许再有裸的 Material3 Switch(
        assertFalse("设置页不许再出现裸 Switch(", settingsSrc.contains("Switch(") && !settingsSrc.contains("WotaSwitch("))
        assertTrue("设置页必须使用 WotaSwitch", settingsSrc.contains("WotaSwitch("))
        assertTrue("内录音源面板必须使用 WotaSwitch", audioSrc.contains("WotaSwitch("))
        assertFalse("内录音源面板不许再出现裸 Switch(", audioSrc.contains("\n        Switch("))
    }

    @Test
    fun `松手必须继承速度而不是归零重启`() {
        assertTrue(
            "落位动画必须带 initialVelocity（离手速度接着走）",
            switchSrc.contains("initialVelocity = v0")
        )
        assertTrue(
            "手上速度约等于 0 时必须用当前动画速度续上（不许当成静止）",
            switchSrc.contains("x.velocity")
        )
        assertFalse(
            "不许用 snapTo 直接落位（那是速度归零的实现）——snapTo 只允许出现在起跑/直达路径",
            switchSrc.contains("snapTo(target)") && !switchSrc.contains("if (hardSnap)")
        )
    }

    @Test
    fun `软着地弹簧与几何来自设计稿的同一条换算`() {
        // ζ = 21.5 / (2√(170×0.9)) ≈ 0.87；等效单位质量刚度 = 170/0.9 ≈ 189
        assertTrue("着地弹簧的阻尼比必须来自设计稿换算", switchSrc.contains("dampingRatio = 0.87f"))
        assertTrue("着地弹簧的等效刚度必须来自设计稿换算", switchSrc.contains("stiffness = 189f"))
        // 几何：92×46 / 36 / 5 等比缩到 64×32 / 25 / 3.5——数字写错守卫要红
        assertTrue("轨道几何必须与设计稿等比", switchSrc.contains("TrackWidth: Dp = 64.dp") && switchSrc.contains("TrackHeight: Dp = 32.dp"))
        assertTrue("拇指与留白必须与设计稿等比", switchSrc.contains("ThumbSize: Dp = 25.dp") && switchSrc.contains("ThumbPad: Dp = 3.5.dp"))
        // 沿行程拉伸（不是拖尾）：上限 0.4、速度分母 600 px/s
        assertTrue(
            "拉伸参数必须来自设计稿（上限 0.4 / 分母 600 px/s）",
            switchSrc.contains("StretchMax = 0.4f") && switchSrc.contains("StretchVelocityScalePx = 600f")
        )
    }
}
