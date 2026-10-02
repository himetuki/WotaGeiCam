package com.wotagei.cam

import com.wotagei.cam.core.RangeI
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.ui.design.PillOption
import com.wotagei.cam.ui.dialog.shutterItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「灰显必须有执行力」的通道守卫（2026-10-02 真机事故）。
 *
 * 事故口径：判据层已经修好（本机 24fps 判非精确档、实际 fps 上界 30，[shutterItems] 已把 1/24
 * 判成 supported=false，见 ForcedShutterTest），**但灰显没有执行力**——WotaChip 没有 enabled 通道
 * （enabled=false 只把文字调暗，clickable 照样回调）、PillChoices 没把 opt.enabled 传下去、
 * CameraPills.ShutterPill.onPick 又是全工程唯一不查 enabled 就直写参数总线的回调：
 * 本机 24※ 下点 1/24，引擎 5ms 合并后被 clampShutterToFrame 按帧周期钳回 1/30。
 *
 * 本类钉住「判据 → 选项 → chip → 回调」三段接线，全部可证伪（`尺子自己能红`）：
 * 1. **判据 → 选项**：事故参数（设备曝光上限 33ms + 实际 fps 上界 30）下，与 ShutterPill 同形的
 *    PillOption 映射产物里 1/24 必须 enabled=false；
 * 2. **选项 → chip**：PillChoices / PillToggles 必须把 opt.enabled 传进 WotaChip；
 * 3. **回调兜底**：ShutterPill.onPick 必须查 opt.enabled（灰显档不许进参数总线）。
 *
 * Compose 的点击行为在 JVM 单测里跑不起来（无 Robolectric），所以 2/3 用源码扫描
 * （KotlinSourceScan，判定全部作用在 codeOnly 遮蔽后的文本上——注释里提到 opt.enabled 不算接线，
 * 这批迁移自己的注释不会把守卫打红）。
 */
class PillEnabledGuardTest {

    /** 与 ForcedShutterTest 同源：档位纳秒值现算 1e9/分母，不写第二份字面量 */
    private val ns24 = 1_000_000_000L / 24

    @Test
    fun `本机事故参数下强制快门档必须灰显`() {
        // 与真机一致：设备曝光上限只到 1/30（33ms），24fps 非精确档退成 [15,30] ⇒ 实际 fps 上界 30
        val dev = RangeI(4_000_000, 33_000_000)
        // 与 CameraPills.ShutterPill 逐字同形的映射（it.supported → PillOption.enabled）。
        // 生产里的这一步抄丢不归本用例盯（它在 Composable 里），由接线守卫锁
        val options = shutterItems(dev, fpsHi = 30, approxMark = "※")
            .map { PillOption(it.value, it.label, it.supported) }
        val t24 = options.first { it.value == ns24.toInt() }
        assertFalse("真机事故参数下 1/24 必须灰显（enabled=false）", t24.enabled)
        assertEquals("灰显档不带 ※（灰显与打标互斥）", "1/24", t24.label)
        val t50 = options.first { it.value == (1_000_000_000L / 50).toInt() }
        assertTrue("正对照：同表 1/50 仍可选（20ms 容得进设备范围与 30fps 帧周期）", t50.enabled)
    }

    /** ShutterPill 的 onPick lambda 体（遮蔽文本上花括号配对——字符串/注释已抹平，计数是安全的） */
    private fun lambdaBody(text: String, openBraceAt: Int): String? {
        var depth = 1
        for (i in openBraceAt + 1 until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openBraceAt + 1, i)
                }
            }
        }
        return null
    }

    /** 三段接线检测：返回违规清单，空 = 接线完整 */
    private fun violations(pills: String, popup: String): List<String> {
        val bad = ArrayList<String>()
        val shutter = KotlinSourceScan.bodyOf(pills, "ShutterPill")
        // ① 判据 → 选项：PillOption 第三参必须接 it.supported（抄丢 = 全表默认 enabled=true）
        if (!KotlinSourceScan.flatten(shutter).contains("PillOption(it.value, it.label, it.supported)")) {
            bad.add("ShutterPill 的 PillOption 映射没接 it.supported（灰显判据抄丢，全表默认可选）")
        }
        // ③ 回调兜底：onPick lambda 里必须检查 opt.enabled
        val at = shutter.indexOf("onPick = {")
        val lambda = if (at < 0) null else lambdaBody(shutter, at + "onPick = {".length - 1)
        if (lambda == null) {
            bad.add("ShutterPill 里找不到 onPick = { lambda（回调被改名/挪走，守卫失去输入，不许算通过）")
        } else if (KotlinSourceScan.occurrences(lambda, "opt.enabled").isEmpty()) {
            bad.add("ShutterPill.onPick 不再检查 opt.enabled（灰显档直写参数总线的回归）")
        }
        // ② 选项 → chip：PillChoices / PillToggles 必须把 opt.enabled 传进 WotaChip
        for (name in listOf("PillChoices", "PillToggles")) {
            val body = KotlinSourceScan.bodyOf(popup, name)
            if (KotlinSourceScan.occurrences(body, "enabled = opt.enabled").isEmpty()) {
                bad.add("$name 没把 opt.enabled 传进 WotaChip（灰显只调暗文字、chip 点得动）")
            }
        }
        return bad
    }

    @Test
    fun `灰显通道的接线在场`() {
        val pills = codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt"))
        val popup = codeOnly(KotlinSourceScan.mainSourceText("ui/design/PillPopup.kt"))
        val bad = violations(pills, popup)
        assertEquals(
            "灰显通道三段接线发现断点：\n${bad.joinToString("\n")}",
            0, bad.size
        )
    }

    @Test
    fun `尺子自己能红`() {
        val pills = codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt"))
        val popup = codeOnly(KotlinSourceScan.mainSourceText("ui/design/PillPopup.kt"))
        assertEquals("良品必须先真的绿，否则后面三个红没有意义", 0, violations(pills, popup).size)
        // 删掉 ShutterPill.onPick 的守卫（CameraPills.kt:300 那条）⇒ 必红
        assertTrue(
            "守卫被删必须红",
            violations(pills.replace("!opt.enabled", "true"), popup).any { it.contains("opt.enabled") }
        )
        // PillOption 映射抄丢 it.supported（PillOption.enabled 有默认 true，编译照样过）⇒ 必红
        assertTrue(
            "判据抄丢必须红",
            violations(
                pills.replace("PillOption(it.value, it.label, it.supported)", "PillOption(it.value, it.label)"),
                popup
            ).any { it.contains("it.supported") }
        )
        // PillChoices / PillToggles 改传常量 ⇒ 必红
        assertTrue(
            "弹窗接线断开必须红",
            violations(pills, popup.replace("enabled = opt.enabled", "enabled = true")).any { it.contains("WotaChip") }
        )
    }
}
