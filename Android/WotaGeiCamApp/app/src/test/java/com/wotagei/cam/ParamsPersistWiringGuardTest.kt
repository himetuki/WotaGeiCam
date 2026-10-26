package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import com.wotagei.cam.ui.ParamsPersist
import com.wotagei.cam.ui.WotaSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置持久化（用户反馈第 1 条）的**源码接线守卫**：Compose 点击行为在 JVM 单测里跑不到，
 * 四处动作完成点是不是真的接了 flush、恢复链是不是真的挂在 applyDefaults 上、键表有没有
 * 多写/漏写、排他字段（音源/自动降级）有没有被顺手持久化——全部用 [KotlinSourceScan] 钉住。
 *
 * 判据全部写成吃 `masked: String` 的纯函数，真身与合成突变体同吃一把尺（每条配「尺子自己能红」）。
 */
class ParamsPersistWiringGuardTest {

    private val maskedScreen by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")) }
    private val maskedSettings by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt")) }
    private val maskedPills by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")) }
    private val maskedPopup by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/design/PillPopup.kt")) }
    private val maskedPersist by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/ParamsPersist.kt")) }

    /** 拍板清单的手写字面量（与 `ParamsPersist.KEYS` 双向核对；顺序也钉） */
    private val expectedKeys = listOf(
        "default_fps", "default_shutter_ns", "default_bitrate", "default_ref_lines", "level_enabled",
        "default_iso", "default_ae_mode", "default_ev", "default_wb_preset", "default_wb_kelvin",
        "default_wb_tint", "default_zoom", "default_flash", "default_stabilize", "default_af_mode",
        "default_mf_diopter", "default_size", "default_frame_effect", "default_zebra_threshold",
        "default_zebra_density", "default_peaking_strength", "default_peaking_color", "default_lens",
        "default_arc_merge_deadband",
        // 2026-10-26：降噪两项（音频/画面）入档
        "denoise_audio", "denoise_video"
    )

    // ------------------------------------------------------------------ 取 lambda 体（PillEnabledGuardTest 同款花括号配对）

    /** 从遮蔽文本的开 `{` 处配对出整枚 lambda 体（遮蔽后无字符串/注释干扰，计数安全） */
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

    /** 锚串之后第一枚 lambda 的体；锚找不到返回 null（守卫失去输入，不许算通过） */
    private fun bodyAfterAnchor(text: String, anchor: String): String? {
        val at = text.indexOf(anchor)
        if (at < 0) return null
        val brace = text.indexOf('{', at)
        if (brace < 0) return null
        return lambdaBody(text, brace)
    }

    // ------------------------------------------------------------------ 判据（纯函数）

    /** 四个钩子（H1/H3/H5 + PillHost 透传）缺 flush 的问题列表；空 = 接线完整 */
    private fun flushHookIssues(masked: String): List<String> {
        val issues = ArrayList<String>()
        fun check(label: String, anchor: String) {
            val body = bodyAfterAnchor(masked, anchor)
            if (body == null) {
                issues.add("$label：找不到锚「$anchor」——守卫失去输入，不许算通过")
            } else if (occurrences(body, "persist.flush()").isEmpty()) {
                issues.add("$label：动作完成点没接 persist.flush()（记住这条用户反馈）")
            }
        }
        check("H1 closePill", "val closePill")
        check("H3 onLongPress", "onLongPress = {")
        // H5：ON_STOP 观察者要在 DisposableEffect 里，且分支里调 flush（窗口双向各取 400 字符）
        val at = masked.indexOf("Lifecycle.Event.ON_STOP")
        if (at < 0) {
            issues.add("H5：找不到 Lifecycle.Event.ON_STOP 观察点")
        } else {
            val window = masked.substring((at - 400).coerceAtLeast(0), (at + 400).coerceAtMost(masked.length))
            if (occurrences(window, "persist.flush()").isEmpty()) issues.add("H5：ON_STOP 分支没接 persist.flush()")
            if (!window.contains("DisposableEffect")) issues.add("H5：ON_STOP 观察者必须挂在 DisposableEffect（防重复注册）")
        }
        // 2026-10-17：点按循环（H2 onReadoutCycle / H4 onLensCycle）随功能删除，
        // flush 恰好 4 处 = H1/H3/H5 三钩子 + PillHost 透传；多一处（散落别处）少一处（钩子掉了）都红
        val n = occurrences(masked, "persist.flush()").size
        if (n != 4) issues.add("persist.flush() 出现 $n 次，应为恰好 4 处（H1/H3/H5 三钩子 + PillHost 透传）")
        return issues
    }

    /** applyDefaults 末尾必须接 ParamsPersist.restore；空 = 接线完整 */
    private fun restoreWiredIssues(masked: String): List<String> {
        val body = runCatching { bodyOf(masked, "applyDefaults") }.getOrNull()
            ?: return listOf("定位不到 fun applyDefaults 的体——冷启恢复链的挂点丢了")
        return if (body.contains("ParamsPersist.restore(prefs, params)")) emptyList()
        else listOf("applyDefaults 末尾没接 ParamsPersist.restore（冷启恢复链断）")
    }

    /** 键表白名单与手写清单的双向核对（多一少一、乱序、重复都报） */
    private fun keyTableIssues(keys: List<String>): List<String> {
        val issues = ArrayList<String>()
        if (keys.distinct().size != keys.size) issues.add("键表有重复项：$keys")
        if (keys != expectedKeys) {
            val missing = expectedKeys.filterNot { it in keys }
            val extra = keys.filterNot { it in expectedKeys }
            if (missing.isNotEmpty()) issues.add("键表缺项：$missing")
            if (extra.isNotEmpty()) issues.add("键表多项：$extra")
            if (missing.isEmpty() && extra.isEmpty()) issues.add("键表项齐但顺序与拍板清单不一致")
        }
        return issues
    }

    /** 26 个键常量在 ParamsPersist.kt 里的引用数至少 3（KEYS 表 1 + restore 1 + flush 1）；空 = 每个键都真的在两条链里 */
    private fun keyWiringIssues(masked: String, keyNames: List<String>): List<String> =
        keyNames.filter { occurrences(masked, it).size < 3 }
            .map { "$it 在 ParamsPersist.kt 里引用不足 3 处（KEYS 表/restore/flush 三者缺一）" }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `录制页四处动作完成点都接了flush`() {
        assertEquals("钩子接线缺件：", emptyList<String>(), flushHookIssues(maskedScreen))
    }

    @Test
    fun `恢复链挂在applyDefaults末尾`() {
        assertEquals("恢复链接线缺件：", emptyList<String>(), restoreWiredIssues(maskedSettings))
    }

    @Test
    fun `键表白名单恰好等于拍板清单`() {
        assertEquals("键表核对：", emptyList<String>(), keyTableIssues(ParamsPersist.KEYS))
        assertEquals(
            "键常量引用核对：",
            emptyList<String>(),
            keyWiringIssues(
                maskedPersist,
                listOf(
                    "KEY_DEFAULT_FPS", "KEY_DEFAULT_SHUTTER_NS", "KEY_DEFAULT_BITRATE",
                    "KEY_DEFAULT_REF_LINES", "KEY_LEVEL_ENABLED", "KEY_DEFAULT_ISO",
                    "KEY_DEFAULT_AE_MODE", "KEY_DEFAULT_EV", "KEY_DEFAULT_WB_PRESET",
                    "KEY_DEFAULT_WB_KELVIN", "KEY_DEFAULT_WB_TINT", "KEY_DEFAULT_ZOOM",
                    "KEY_DEFAULT_FLASH", "KEY_DEFAULT_STABILIZE", "KEY_DEFAULT_AF_MODE",
                    "KEY_DEFAULT_MF_DIOPTER", "KEY_DEFAULT_SIZE", "KEY_DEFAULT_FRAME_EFFECT",
                    "KEY_DEFAULT_ZEBRA_THRESHOLD", "KEY_DEFAULT_ZEBRA_DENSITY",
                    "KEY_DEFAULT_PEAKING_STRENGTH", "KEY_DEFAULT_PEAKING_COLOR", "KEY_DEFAULT_LENS",
                    "KEY_DEFAULT_ARC_MERGE_DEADBAND"
                )
            )
        )
    }

    @Test
    fun `R3_音源不持久化_录制页任何路径不写prefs`() {
        // 排除清单里的三个键一个都不许进键表
        for (k in listOf(
            WotaSettings.KEY_DEFAULT_AUDIO_MUTE,
            WotaSettings.KEY_DEFAULT_RENDER,
            WotaSettings.KEY_DEFAULT_SAMPLE_RATE
        )) {
            assertFalse("$k 属排除清单（音源不持久化/自动降级），不许进键表", ParamsPersist.KEYS.contains(k))
        }
        // 录制页两枚文件与持久化模块都不许引用静音键（写点只剩设置页那一处）
        assertFalse("CameraPills 的音源分支不许写 default_audio_muted（R3 删掉的正是这处计划）", maskedPills.contains("KEY_DEFAULT_AUDIO_MUTE"))
        assertFalse("CameraScreen 不许写 default_audio_muted", maskedScreen.contains("KEY_DEFAULT_AUDIO_MUTE"))
        assertFalse("ParamsPersist 不许引用静音键", maskedPersist.contains("KEY_DEFAULT_AUDIO_MUTE"))
        // 正对照：设置页「静音录制」开关仍在（R1 保留），键唯一写点在那边
        assertTrue("设置页静音开关必须仍在（R1：其他默认档保留不动）", maskedSettings.contains("KEY_DEFAULT_AUDIO_MUTE"))
    }

    @Test
    fun `R1_设置页不再有帧率快门写点`() {
        val def = runCatching { bodyOf(maskedSettings, "BlockDefault") }.getOrNull()
            ?: throw AssertionError("定位不到 BlockDefault——守卫失去输入")
        assertFalse("设置页默认档区块不得再写 default_fps（R1：改由录制页持久化写）", def.contains("KEY_DEFAULT_FPS"))
        assertFalse("设置页默认档区块不得再写 default_shutter_ns", def.contains("KEY_DEFAULT_SHUTTER_NS"))
        assertFalse("帧率/快门两行选档 UI 必须已删", def.contains("set_default_fps") || def.contains("set_default_shutter"))
        // 键本身照旧存在（只是写点换了人）
        assertTrue("default_fps 键必须保留", maskedSettings.contains("KEY_DEFAULT_FPS"))
        assertTrue("default_shutter_ns 键必须保留", maskedSettings.contains("KEY_DEFAULT_SHUTTER_NS"))
    }

    @Test
    fun `R4_十个滑杆调用点全接松手落盘`() {
        assertEquals("PillSlider 调用点数（拍板以实查 10 处为准）", 10, occurrences(maskedPills, "PillSlider(").size)
        assertEquals(
            "补传 onValueChangeFinished 的调用点数",
            10, occurrences(maskedPills, "onValueChangeFinished = onPersist").size
        )
        // 八个带滑杆的面板逐个点名牌：漏挂一颗 = 该面板滑杆拖完被 force-stop 丢值
        val pillFns = listOf("ShutterPill", "IsoPill", "EvPill", "WbPill", "ZoomPill", "FocusPill", "MonitorPill", "FpsPill")
        val misses = pillFns.filter { fn ->
            val body = runCatching { bodyOf(maskedPills, fn) }.getOrNull()
            body == null || occurrences(body, "onValueChangeFinished = onPersist").isEmpty()
        }
        assertEquals("这些面板的滑杆没接松手落盘：", emptyList<String>(), misses)
        assertTrue(
            "PillSlider 必须提供可选 onValueChangeFinished 通道",
            maskedPopup.contains("onValueChangeFinished: (() -> Unit)?")
        )
        assertTrue(
            "CameraScreen 必须把 persist.flush() 接进 PillHost",
            maskedScreen.contains("onPersist = { persist.flush() }")
        )
    }

    // ------------------------------------------------------------------ 尺子自己能红

    @Test
    fun `尺子自己能红_拆掉任一钩子必报红`() {
        assertTrue("真身必须绿", flushHookIssues(maskedScreen).isEmpty())
        // 拆掉第一处（H1 closePill 里的）flush：钩子体检查与"恰好 4 处"两道都要踩中
        val mutant = maskedScreen.replaceFirst("persist.flush()", "Unit")
        assertTrue("拆掉 H1 的 flush 后钩子判据必须红", flushHookIssues(mutant).isNotEmpty())
    }

    @Test
    fun `尺子自己能红_恢复链接线拆掉必报红`() {
        assertTrue("真身必须绿", restoreWiredIssues(maskedSettings).isEmpty())
        val mutant = maskedSettings.replace("ParamsPersist.restore(prefs, params)", "Unit")
        assertTrue("拆掉 applyDefaults 末尾的 restore 后判据必须红", restoreWiredIssues(mutant).isNotEmpty())
    }

    @Test
    fun `尺子自己能红_键表多一个少一个都报红`() {
        assertTrue("真身必须绿", keyTableIssues(ParamsPersist.KEYS).isEmpty())
        assertTrue("少一个键必红", keyTableIssues(ParamsPersist.KEYS.drop(1)).isNotEmpty())
        assertTrue("多一个键（把排他键塞进来）必红", keyTableIssues(ParamsPersist.KEYS + "hud_items").isNotEmpty())
        assertTrue(
            "恢复/落盘链漏引用一个键常量必红",
            keyWiringIssues(maskedPersist.replace("KEY_DEFAULT_ISO", "KEY_DEFAULT_ISO_X"), listOf("KEY_DEFAULT_ISO")).isNotEmpty()
        )
    }
}
