package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.ui.widget.SliderKey
import com.wotagei.cam.ui.widget.decodeContinuousSliders
import com.wotagei.cam.ui.widget.effectiveLadder
import com.wotagei.cam.ui.widget.encodeContinuousSliders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑杆吸附开关（2026-10-21 用户反馈：变焦只能落 0.5/1/1.5…，想调 1.2×/1.7×）。
 *
 * 三层被测：
 * - 持久化编解码（[encodeContinuousSliders] / [decodeContinuousSliders]）：缺键=全部吸附、
 *   坏串/未知 id 不炸、编码顺序稳定；
 * - 桥函数 [effectiveLadder]：开关关掉 → 梯变 null（滑杆退回连续区间），并配突变体自证；
 * - 接线守卫：`PillSlider` 的 `snapKey` **无默认值必传**、十根录制页滑杆逐个点名（含三根整数域
 *   滑杆显式传 null）、设置页组真的写 prefs。
 */
class SliderSnapTest {

    // ------------------------------------------------------------------ 编解码

    @Test
    fun `编码_空集为空串_按枚举声明顺序稳定输出`() {
        assertEquals("", encodeContinuousSliders(emptySet()))
        assertEquals(
            "shutter,zoom",
            encodeContinuousSliders(setOf(SliderKey.ZOOM, SliderKey.SHUTTER))
        )
        // 同一集合无论传入顺序如何，结果一致（便于人工排查 prefs）
        assertEquals(
            encodeContinuousSliders(setOf(SliderKey.ISO, SliderKey.KELVIN)),
            encodeContinuousSliders(setOf(SliderKey.KELVIN, SliderKey.ISO))
        )
    }

    @Test
    fun `解码_缺键与坏串为空集_未知id忽略_全量往返一致`() {
        assertTrue(decodeContinuousSliders(null).isEmpty())
        assertTrue(decodeContinuousSliders("").isEmpty())
        assertTrue("空白串", decodeContinuousSliders("   ").isEmpty())
        assertEquals(
            "未知 id（旧/新版本写入的）必须忽略，不许炸",
            setOf(SliderKey.ZOOM),
            decodeContinuousSliders("no_such_key,zoom")
        )
        assertEquals(
            setOf(SliderKey.ZOOM),
            decodeContinuousSliders(encodeContinuousSliders(setOf(SliderKey.ZOOM)))
        )
        val all = SliderKey.entries.toSet()
        assertEquals(all, decodeContinuousSliders(encodeContinuousSliders(all)))
        // 容忍空格（人工改 prefs 的常见形态）
        assertEquals(setOf(SliderKey.ISO, SliderKey.TINT), decodeContinuousSliders(" iso , tint "))
    }

    @Test
    fun `键表是手写的七个_整数域参数不许混进来`() {
        assertEquals(
            listOf("shutter", "iso", "kelvin", "tint", "zoom", "diopter", "peaking"),
            SliderKey.entries.map { it.id }
        )
    }

    // ------------------------------------------------------------------ 桥函数

    @Test
    fun `桥函数_关掉吸附时梯为null_否则原样返回`() {
        val ladder = listOf(1f, 1.5f, 2f)
        assertSame("吸附开着必须原样用梯", ladder, effectiveLadder(ladder, continuous = false))
        assertNull("关掉吸附必须退回连续滑杆", effectiveLadder(ladder, continuous = true))
        // 突变自证：忽略开关的突变体必须在 continuous=true 这一行与真身不同
        val mutant: (List<Float>, Boolean) -> List<Float>? = { l, _ -> l }
        assertTrue(
            "开关被忽略时本尺子必须能红",
            mutant(ladder, true) != effectiveLadder(ladder, true)
        )
    }

    // ------------------------------------------------------------------ 接线守卫

    /** 接线体检：返回违规清单，空 = 干净（真身与突变体同吃） */
    private fun wiringIssues(
        popup: String,
        pills: String,
        settings: String,
        player: String
    ): List<String> {
        val bad = ArrayList<String>()

        // 1) 形参：snapKey 无默认值必传；梯不再可空
        if (!popup.contains("snapKey: SliderKey?")) bad.add("PillSlider 缺 snapKey 形参（吸附开关没入口）")
        if (popup.contains("snapKey: SliderKey? =")) bad.add("snapKey 不许给默认值（漏挂必须编译不过）")
        if (!popup.contains("ladder: List<Float>,")) bad.add("ladder 必须是必传的非空梯")
        if (popup.contains("ladder: List<Float>? =")) bad.add("ladder 仍是可空+默认值（会悄悄退回无梯滑杆）")

        // 2) 控件内部真的读了开关、并把开关应用到梯上
        val slider = runCatching { bodyOf(popup, "PillSlider") }.getOrNull()
        if (slider == null) {
            bad.add("定位不到 PillSlider 函数体")
        } else {
            if (!slider.contains("rememberSliderContinuous(snapKey)")) {
                bad.add("PillSlider 没读设置页的吸附开关")
            }
            if (!slider.contains("effectiveLadder(ladder, continuous)")) {
                bad.add("PillSlider 读了开关却没用它挑梯")
            }
        }
        val helper = runCatching { bodyOf(popup, "rememberSliderContinuous") }.getOrNull()
        if (helper == null || !helper.contains("WotaSettings.sliderContinuous(")) {
            bad.add("rememberSliderContinuous 没读 WotaSettings.sliderContinuous")
        }

        // 3) 录制页 21 根滑杆逐个点名（14 根显式 null：3 根整数域 + Glow 七条 + 色调四条（29 §4.4）；
        //    7 根各点一次 SliderKey）
        if (occurrences(pills, "PillSlider(") != 21) bad.add("PillSlider 调用点不是 21 处")
        // 21 根全部点名（7 根各自的 SliderKey + 14 根显式 null）
        if (occurrences(pills, "snapKey =") != 21) bad.add("有滑杆没点名 snapKey")
        if (occurrences(pills, "snapKey = null") != 14) {
            bad.add("整数域/色调滑杆（EV/斑马阈值/并入门槛 + Glow 七条 + TONE 四条）必须显式传 snapKey = null，且恰好 14 处")
        }
        SliderKey.entries.forEach { key ->
            val n = occurrences(pills, "snapKey = SliderKey.${key.name}")
            if (n != 1) bad.add("录制页滑杆 ${key.name} 的 snapKey 出现 $n 次（应恰 1 次）")
        }

        // 4) 设置页：组里列出全部键，并把整集合写回 prefs
        val block = runCatching { bodyOf(settings, "BlockSlider") }.getOrNull()
        if (block == null) {
            bad.add("设置页缺 BlockSlider（功能没有入口）")
        } else {
            if (!block.contains("SliderKey.entries")) bad.add("BlockSlider 没遍历全部滑杆键")
            if (!block.contains("WotaSettings.setContinuousSliders(prefs, next)")) {
                bad.add("BlockSlider 没写 prefs（点了不生效）")
            }
        }
        if (!settings.contains("KEY_SLIDER_CONTINUOUS")) bad.add("缺 slider_continuous 键")

        // 5) 另两处并入门槛滑杆同样是整数域
        if (occurrences(settings, "snapKey = null") != 1) {
            bad.add("设置页并入门槛滑杆必须传 snapKey = null")
        }
        // 2026-10-29：播放器里的整数域滑杆共 4 根——并入门槛 1 根 + 导出配置的光晕阈值/强度/混合 3 根
        if (occurrences(player, "snapKey = null") != 4) {
            bad.add("播放器整数域滑杆（并入门槛 + 导出配置三项）必须都传 snapKey = null（当前 ${occurrences(player, "snapKey = null")} 处）")
        }
        return bad
    }

    private fun occurrences(text: String, needle: String): Int {
        var i = text.indexOf(needle)
        var n = 0
        while (i >= 0) {
            n++
            i = text.indexOf(needle, i + needle.length)
        }
        return n
    }

    @Test
    fun `接线_每根滑杆都点名吸附开关_设置页真的写prefs`() {
        val popup = codeOnly(mainSourceText("ui/design/PillPopup.kt"))
        val pills = codeOnly(mainSourceText("ui/CameraPills.kt"))
        val settings = codeOnly(mainSourceText("ui/SettingsScreen.kt"))
        val player = codeOnly(mainSourceText("player/PlayerScreen.kt"))
        val issues = wiringIssues(popup, pills, settings, player)
        assertEquals("滑杆吸附接线发现断点：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `尺子自己能红_拆开关或漏点名必报红`() {
        val popup = codeOnly(mainSourceText("ui/design/PillPopup.kt"))
        val pills = codeOnly(mainSourceText("ui/CameraPills.kt"))
        val settings = codeOnly(mainSourceText("ui/SettingsScreen.kt"))
        val player = codeOnly(mainSourceText("player/PlayerScreen.kt"))
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", wiringIssues(popup, pills, settings, player).isEmpty())

        // 突变 1：某根滑杆漏挂 snapKey
        val missingKey = pills.replace("snapKey = SliderKey.ZOOM,", "")
        assertTrue(
            "漏点名后尺子必须红",
            wiringIssues(popup, missingKey, settings, player).any { it.contains("snapKey") }
        )
        // 突变 2：PillSlider 读了开关却不应用（梯恒按原样用）
        val ignoresSwitch = popup.replace("effectiveLadder(ladder, continuous)", "ladder")
        assertTrue(
            "开关没应用到梯上时必须红",
            wiringIssues(ignoresSwitch, pills, settings, player).any { it.contains("没用它挑梯") }
        )
        // 突变 3：设置页只改内存不落盘
        val noWrite = settings.replace("WotaSettings.setContinuousSliders(prefs, next)", "Unit")
        assertTrue(
            "设置页不写 prefs 时必须红",
            wiringIssues(popup, pills, noWrite, player).any { it.contains("不生效") }
        )
        // 突变 4：snapKey 给了默认值（漏挂不再编译不过）
        val defaulted = popup.replace("snapKey: SliderKey?,", "snapKey: SliderKey? = null,")
        assertTrue(
            "snapKey 带默认值时必须红",
            wiringIssues(defaulted, pills, settings, player).any { it.contains("不许给默认值") }
        )
    }

    @Test
    fun `编码器突变自证_漏掉某个键必须与真身不同`() {
        val keys = setOf(SliderKey.ZOOM)
        val mutant: (Set<SliderKey>) -> String = { "" }
        assertFalse(
            "编码器退化成空串时本尺子必须能红",
            mutant(keys) == encodeContinuousSliders(keys)
        )
    }
}
