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
    /** Y57 抽出的手势状态机：翻转/清理的实际实现在这里 */
    private val gestureSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/design/SwitchGestureState.kt"))
    }

    /** 液态开关函数体（Y57 的守卫只看这一枚组件内部） */
    private val switchBody by lazy {
        KotlinSourceScan.bodyOf(switchSrc, "LiquidGestureSwitch")
    }

    /** 手势收尾段：从 `finally {` 到函数体尾（清理语句必须落在这段里） */
    private fun finallyBlock(text: String): String {
        val flat = KotlinSourceScan.flatten(text)
        val i = flat.indexOf("finally {")
        return if (i < 0) "" else flat.substring(i)
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
            switchSrc.contains("initialVelocity = release.initialVelocity")
        )
        assertTrue(
            "手上速度约等于 0 时必须用当前动画速度续上（不许当成静止）",
            switchSrc.contains("x.velocity")
        )
        assertTrue(
            "速度继承的判据在状态机里：手上速度小才回落到 x.velocity",
            gestureSrc.contains("abs(dragVelocity) > 1f")
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

    // ------------------------------------------------------------------ Y57：跨中点手势不中断 + finally 清理

    @Test
    fun `跨中点翻转不许换掉手势键`() {
        val flat = KotlinSourceScan.flatten(switchBody)
        assertTrue(
            "手势键必须只由「可交互 + 行程」决定（checked 变化来自手势自己，不能反过来取消手势）",
            flat.contains("pointerInput(interactive, travelPx)")
        )
        assertFalse(
            "checked 不许进 pointerInput 键：过中点回调翻转它 → 重组换键 → 当前手势被取消",
            flat.contains("pointerInput(interactive, checked")
        )
        assertTrue(
            "checked 的最新值必须经 rememberUpdatedState 读取",
            flat.contains("rememberUpdatedState(checked)")
        )
        assertTrue(
            "手势内的翻转判断必须读本段手势自己的逻辑档（不等重组回灌）",
            gestureSrc.contains("past != checked") && gestureSrc.contains("checked = past")
        )
        assertTrue(
            "组件必须把移动交给状态机（跨中点后手势不中断）",
            flat.contains("gesture.move(at, grab, travelPx, velocity)")
        )
        assertTrue(
            "松手落位也必须读本段手势的逻辑档",
            gestureSrc.contains("if (checked) travelPx else 0f")
        )
        // 突变自证：把 checked 放回键里 → 上面第一条必须变红
        val mutant = switchSrc.replace(
            "pointerInput(interactive, travelPx)",
            "pointerInput(interactive, checked, travelPx)"
        )
        assertTrue("突变体必须真的改到了源码", mutant != switchSrc)
        assertFalse(
            "把 checked 放回手势键后守卫必须变红",
            KotlinSourceScan.flatten(mutant).contains("pointerInput(interactive, travelPx)")
        )
    }

    @Test
    fun `取消路径必须由finally清空拖动状态`() {
        val f = finallyBlock(switchBody)
        assertTrue("手势必须有 finally 清理段（正常/取消/异常都从这里出去）", f.isNotEmpty())
        assertTrue(
            "取消/异常统一走状态机的 endGesture（清空 + 回位参数）",
            f.contains("gesture.endGesture(travelPx)")
        )
        assertTrue("按压态要复位", gestureSrc.contains("pressing = false"))
        assertTrue("拖动残余要清空（取消后不许停在半路）", gestureSrc.contains("dragX = null"))
        assertTrue("速度要清零", gestureSrc.contains("dragVelocity = 0f"))
        assertTrue(
            "取消路径要把拇指从当前位置落回本段手势的逻辑档",
            gestureSrc.contains("!handled && moved && at != null")
        )
        // 突变自证：finally 改成普通块（清理不再保证执行）→ 识别不到清理段
        val mutant = switchBody.replace("} finally {", "} run {")
        assertTrue("突变体必须真的改到了源码", mutant != switchBody)
        assertTrue(
            "清理不在 finally 里时守卫必须变红",
            finallyBlock(mutant).isEmpty()
        )
    }
}
