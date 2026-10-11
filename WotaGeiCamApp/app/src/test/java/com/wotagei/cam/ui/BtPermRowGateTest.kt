package com.wotagei.cam.ui

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页「蓝牙权限行下的位置子行」的版本闸守卫（#10）。
 *
 * 背景：`7666e95` 给 `startDiscovery` 在 API 29/30 补了 `ACCESS_FINE_LOCATION`（manifest 按
 * `maxSdkVersion="30"` 收口）。设置页此前**从未呈现**这枚权限——用户在这里看到「蓝牙连接已就绪」，
 * 而系统实际声明的权限面里还多着一枚没被算进来的位置权限，两处口径不一致（**首启不会弹位置权限框**：
 * `missingStartupPermissions()` 只要相机/麦克风/媒体读取三项，位置权限的申请只在蓝牙面板里由用户
 * 点按钮触发；2026-10-11 口径订正，此前本段误写成「首启又弹位置框」）。子行的判据**逐字**是
 * `BtSpeakerController.missingPermissions()` 的 `<S` 分支（同枚 `ACCESS_FINE_LOCATION`）。
 *
 * 三条判据：
 * 1. **纯函数行为** [visibleOnlyBelowApi31]：29/30 可见、31/33 不可见（阈值真源只有一处）；
 * 2. **源码守卫**：[permissionRows] 体内**版本闸字面锚先于** `ACCESS_FINE_LOCATION`（闸掉了才准入），
 *    且阈值本身是 `< 31`（= manifest 的 `maxSdkVersion="30"` 同档）；
 * 3. **突变自证**：去掉闸（无条件加子行）、FINE 改 COARSE、阈值改成 `<30` —— 三者必红。
 *
 * 判据都吃「切出来的文本」，真身与突变体同吃一把尺。
 */
class BtPermRowGateTest {

    /** 版本闸的字面锚：`permissionRows` 里的准入闸，改写法即视为失去输入（守卫直接红） */
    private val gateAnchor = "if (btLocationRowVisible(android.os.Build.VERSION.SDK_INT)"

    private val settingsMasked: String by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
    }

    /**
     * 取表达式体函数的声明段（`fun <name>( … ) = <体>` 到下一个 `fun ` 之前）。
     *
     * `KotlinSourceScan.bodyOf` 只解花括号体，表达式体（`=` 开头）返回 null——本文件两枚被判函数
     * 都是表达式体，所以按「下一个函数声明」切片；找不到入口直接抛，不给空文本蒙混。
     */
    private fun exprBodySlice(masked: String, signature: String): String {
        val at = masked.indexOf(signature)
        if (at < 0) throw AssertionError("主源码里找不到 $signature——守卫失去输入，不许算通过")
        val next = masked.indexOf("fun ", at + signature.length)
        if (next < 0) throw AssertionError("$signature 之后找不到下一个函数声明，切片失去边界")
        return flatten(masked.substring(at, next))
    }

    /** [permissionRows] 的声明段（含 buildList 体） */
    private fun permissionRowsSlice(masked: String): String =
        exprBodySlice(masked, "fun permissionRows(")

    /** [btLocationRowVisible] 的声明段（含阈值） */
    private fun gateFnSlice(masked: String): String =
        exprBodySlice(masked, "fun btLocationRowVisible(")

    // ------------------------------------------------------------------ 判据（纯函数，真身与突变体同吃）

    private fun rowsIssues(masked: String): List<String> {
        val bad = ArrayList<String>()
        val body = permissionRowsSlice(masked)
        val gateAt = body.indexOf(gateAnchor)
        val fineAt = body.indexOf("ACCESS_FINE_LOCATION")
        if (gateAt < 0) {
            bad.add("permissionRows 体内缺版本闸字面锚「$gateAnchor」——子行变成无条件加入（守卫失去输入）")
            return bad
        }
        if (fineAt < 0) {
            bad.add("permissionRows 体内找不到 ACCESS_FINE_LOCATION——位置子行被删（守卫失去输入）")
            return bad
        }
        if (gateAt > fineAt) {
            bad.add("版本闸必须先于 ACCESS_FINE_LOCATION 的取值：闸在后 = 无条件取权限值再靠别处丢弃")
        }
        if (!body.contains("R.string.set_perm_bt_location")) {
            bad.add("位置子行丢了 set_perm_bt_location 标签")
        }
        return bad
    }

    private fun thresholdIssues(masked: String): List<String> {
        val bad = ArrayList<String>()
        val body = gateFnSlice(masked)
        if (!body.contains("sdkInt < 31")) {
            bad.add("btLocationRowVisible 的阈值不是 sdkInt < 31——与 manifest maxSdkVersion=\"30\" 不同档")
        }
        if (body.contains("< 30")) {
            bad.add("阈值被收成 < 30：API 30（Android 11）会被漏掉，那一档的 discovery 恰恰要位置权限")
        }
        return bad
    }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `visibleOnlyBelowApi31`() {
        assertTrue("API 29（Android 10）必须显示位置子行", btLocationRowVisible(29))
        assertTrue("API 30（Android 11）必须显示位置子行", btLocationRowVisible(30))
        assertFalse("API 31 起改走 BLUETOOTH_SCAN + neverForLocation，不再显示", btLocationRowVisible(31))
        assertFalse("API 33 同上", btLocationRowVisible(33))
    }

    @Test
    fun `permissionRows里版本闸先于位置权限`() {
        assertEquals(
            "位置子行的版本闸回归：\n${rowsIssues(settingsMasked).joinToString("\n")}",
            emptyList<String>(),
            rowsIssues(settingsMasked)
        )
    }

    @Test
    fun `阈值与manifest_maxSdkVersion30同档`() {
        assertEquals(
            "版本闸阈值回归：\n${thresholdIssues(settingsMasked).joinToString("\n")}",
            emptyList<String>(),
            thresholdIssues(settingsMasked)
        )
    }

    // ------------------------------------------------------------------ 突变自证（尺子自己能红）

    @Test
    fun `尺子自己能红_去掉闸无条件加子行必报红`() {
        val real = settingsMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", rowsIssues(real).isEmpty())
        // 突变 1：把准入闸整个删掉——位置子行在 API 31+ 也出现（与 manifest 的 maxSdkVersion="30" 收口相反）
        val mutant = real.replace("$gateAnchor) {", "")
        assertTrue(
            "去掉版本闸后尺子必须红",
            rowsIssues(mutant).any { it.contains("缺版本闸") }
        )
    }

    @Test
    fun `尺子自己能红_位置权限换成COARSE必报红`() {
        val real = settingsMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", rowsIssues(real).isEmpty())
        // 突变 2：FINE 改成同族的 COARSE——系统对蓝牙设备发现认的是 FINE，粗定位不顶用
        val mutant = real.replace("Manifest.permission.ACCESS_FINE_LOCATION", "Manifest.permission.ACCESS_COARSE_LOCATION")
        assertTrue(
            "ACCESS_FINE_LOCATION 换成 ACCESS_COARSE_LOCATION 后尺子必须红",
            rowsIssues(mutant).any { it.contains("ACCESS_FINE_LOCATION") }
        )
    }

    @Test
    fun `尺子自己能红_阈值改成30以下必报红`() {
        val real = settingsMasked
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", thresholdIssues(real).isEmpty())
        // 突变 3：阈值从 `< 31` 收成 `< 30`——API 30 被漏掉，而那一档的 discovery 恰恰被平台硬绑位置权限
        assertTrue(
            "阈值改成 < 30 后尺子必须红",
            thresholdIssues(real.replace("sdkInt < 31", "sdkInt < 30")).any { it.contains("< 30") }
        )
        // 反面自证：良品在 API 30 上必须可见（否则上面那条断言靠「函数恒返回 false」也红不了/也绿不了）
        assertTrue("API 30 必须可见（纯函数真身）", btLocationRowVisible(30))
    }
}
