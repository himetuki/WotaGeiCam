package com.wotagei.cam.bt

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 蓝牙「设备发现」的位置权限守卫（r31 任务 10 / S4-M5-01 立，本轮 **S4-M5-01 续 / #10+#18 契约反转**）。
 *
 * 缺陷：`BtSpeakerController.missingPermissions()` 原先 `SDK < S` 一律 `return emptyArray()`，
 * 理由是"API 30 及以下走清单里的 install 期权限"。前半句对**连接态**成立，后半句对**扫描**不成立——
 * Android 10/11（API 29/30）上 `BluetoothAdapter#startDiscovery` 被平台硬绑 `ACCESS_FINE_LOCATION`：
 * 缺位时不抛异常、不发起扫描，只留一条日志（根因类型 10「失败静默」× 类型 1「平台 API 语义误读」）。
 * 用户看到的是「附近设备」永远空着，且应用里没有任何一处提示缺权限。
 *
 * 守卫钉四件事（全部作用在 codeOnly 遮蔽 + flatten 折叠后的文本上，注释提到不算）：
 * 1. **manifest 声明到位**，且带 `android:maxSdkVersion="30"`——把位置权限面收在真正需要它的
 *    API 29/30，不铺到 S 以上（S 起由 `BLUETOOTH_SCAN` + `neverForLocation` 承接）；
 * 2. `missingPermissions()` 的 **<S 分支**必须列出 `ACCESS_FINE_LOCATION`，且不得夹带
 *    `BLUETOOTH_CONNECT/SCAN`（31 才引入，低版本 `checkSelfPermission` 恒 DENIED，混进去引导面永远亮）；
 * 3. **≥S 分支逐字零变化**：仍是 CONNECT + SCAN 两条，且**不得**夹带位置权限；
 * 4. `startScan()` 的权限闸**先于** `startDiscovery`，闸内必须 `return`，且 `refresh()` 先于闸
 *    （`_hasPermission` 置 false 早于早退，UI 的授权引导面才有东西可亮）。
 *
 * # 设计决策反转（2026-10-11，#10 任务的必要前置，不是可选项）
 *
 * **谁**：本片（#10 设置页蓝牙权限行加位置子行 / #18 位置权限框文案重写）。
 * **什么时候**：紧接 `7666e95`（给 `startDiscovery` 在 API 29/30 补 `ACCESS_FINE_LOCATION`）之后。
 *
 * **为什么反转**：`7666e95` 立的原契约是「位置权限只服务蓝牙扫描，**不进** `ui/SettingsScreen.kt`」
 * （当时的第 9/10 号突变专门打这条：混进设置页 → 红）。可那条契约的判据是"权限申请面不外扩"，
 * 而 #10 要做的是**展示**该权限的状态——`7666e95` 之后设置页若不画这行，用户会看到「蓝牙已就绪」
 * 而首启又弹位置框，权限状态与系统实际声明/可能弹出的授权框对不上。所以契约从「文件级禁入」反转成
 * **权限面白名单**：
 * - `bt/BtSpeakerController.kt` —— 申请真源（<S / ≥S 两档判据见上面第 2、3 条）；
 * - `ui/SettingsScreen.kt` —— 展示面，**必须带 `<S` 版本闸**（闸先于 `ACCESS_FINE_LOCATION`），
 *   阈值真源是 `SettingsScreen.btLocationRowVisible`（= manifest `maxSdkVersion="30"` 同档）；
 * - `ui/MainActivity.kt` —— 首启权限申请面。**本片一字未动**（#18 范围更正：合并首启权限面已否决），
 *   故它当前零位置权限，这条状态被单独钉住：谁往里加 `ACCESS_FINE_LOCATION` 谁红。
 *
 * **没动的东西**：manifest 的 `maxSdkVersion="30"` 判据**保留原文**；`MainActivity` 的权限面一字未动；
 * 申请真源（`missingPermissions()` 的两档分支）逐字未动。白名单**之外**的任何主源码文件含
 * `ACCESS_FINE_LOCATION` → 红（泄漏面至少覆盖 `camera/CameraController.kt`、`ui/CameraScreen.kt`，
 * 突变自证里各打一次）。
 *
 * 「尺子自己能红」：每条判据都有对应突变体打同一把尺（见各突变注释）。
 */
class BtScanLocationPermGuardTest {

    /** 版本闸的字面锚（控制器侧）：分档判据就写在这枚字符串上，改写法即视为失去输入（守卫直接红） */
    private val sGuard = "if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)"

    /**
     * 权限面白名单（含特殊裁定的面）：允许出现 `ACCESS_FINE_LOCATION` 的主源码文件。
     * 白名单**内**还要各自过自己的判据（控制器过两档分支、设置页过版本闸、MainActivity 过"零位置权限"），
     * 白名单**外**一律不许出现。
     */
    private val locationWhitelist = setOf(
        "bt/BtSpeakerController.kt",
        "ui/SettingsScreen.kt",
        "ui/MainActivity.kt"
    )

    /** `SettingsScreen` 的版本闸字面锚（`<S` 档的唯一入口；阈值真源是 `btLocationRowVisible`） */
    private val settingsGateAnchor = "if (btLocationRowVisible(android.os.Build.VERSION.SDK_INT)"

    // ------------------------------------------------------------------ 输入

    /** 主源码（遮蔽 + 折叠空白：结构判定不受换行缩进影响，也让突变体写法收敛成单行） */
    private fun controllerSrc(): String = flatten(codeOnly(mainSourceText("bt/BtSpeakerController.kt")))

    /** 位置权限的**白名单 + 守卫靶子**面，突变自证时喂变异字典 */
    private fun locationSurfaces(): Map<String, String> = mapOf(
        "bt/BtSpeakerController.kt" to controllerSrc(),
        "ui/SettingsScreen.kt" to codeOnly(mainSourceText("ui/SettingsScreen.kt")),
        "ui/MainActivity.kt" to codeOnly(mainSourceText("ui/MainActivity.kt")),
        // 泄漏靶子：白名单外的两个高危面（相机参数链 / 取景页），良品里它们零位置权限
        "camera/CameraController.kt" to codeOnly(mainSourceText("camera/CameraController.kt")),
        "ui/CameraScreen.kt" to codeOnly(mainSourceText("ui/CameraScreen.kt"))
    )

    /** 源 manifest（不遮蔽——判据要看属性与原文串） */
    private fun manifestText(): String {
        val rel = "src/main/AndroidManifest.xml"
        var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val f = File(base, rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 AndroidManifest.xml——守卫不能在没有输入的情况下算通过")
    }

    // ------------------------------------------------------------------ 判据（纯函数，真身与突变体同吃）

    /** manifest 判据：声明 `ACCESS_FINE_LOCATION`，且必须按 `maxSdkVersion="30"` 收口 */
    private fun manifestIssues(m: String): List<String> {
        val bad = ArrayList<String>()
        val decl = Regex(
            "<uses-permission[^>]*android:name\\s*=\\s*\"android\\.permission\\.ACCESS_FINE_LOCATION\"[^>]*>"
        ).find(m)
        if (decl == null) {
            bad.add("manifest 未声明 ACCESS_FINE_LOCATION（Android 10/11 的蓝牙设备发现缺它会静默无结果）")
            return bad
        }
        if (!decl.value.contains("maxSdkVersion=\"30\"")) {
            bad.add("ACCESS_FINE_LOCATION 必须带 android:maxSdkVersion=\"30\"：位置权限面只收在真正需要它的 API 29/30")
        }
        return bad
    }

    /** `missingPermissions()` 的两档分支判据（按版本闸切成 <S / ≥S 两段分别核） */
    private fun permIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        val body = flatten(bodyOf(src, "missingPermissions"))
        val cut = body.indexOf(sGuard)
        if (cut < 0) return listOf("missingPermissions 缺 API 级别分档闸「$sGuard」（守卫失去输入）")
        val modernAt = body.indexOf("buildList", cut)
        if (modernAt < 0) return listOf("找不到 S 以上的 buildList 分支（守卫失去输入）")
        val legacy = body.substring(cut, modernAt)
        val modern = body.substring(modernAt)

        if (!legacy.contains("ACCESS_FINE_LOCATION")) {
            bad.add("<S 分支缺 ACCESS_FINE_LOCATION（Android 10/11 设备发现硬依赖位置权限，缺位扫描静默无结果）")
        }
        if (legacy.contains("BLUETOOTH_CONNECT") || legacy.contains("BLUETOOTH_SCAN")) {
            bad.add("<S 分支夹带 BLUETOOTH_CONNECT/SCAN：API 31 才引入，低版本 checkSelfPermission 恒 DENIED，引导面会永远亮着")
        }
        if (!modern.contains("BLUETOOTH_CONNECT") || !modern.contains("BLUETOOTH_SCAN")) {
            bad.add("≥S 分支丢了 BLUETOOTH_CONNECT/BLUETOOTH_SCAN")
        }
        if (modern.contains("ACCESS_FINE_LOCATION")) {
            bad.add("≥S 分支夹带 ACCESS_FINE_LOCATION：该档由 BLUETOOTH_SCAN + neverForLocation 承接，多要位置权限就是把权限面白摊出去")
        }
        return bad
    }

/** `startScan()` 的前置闸判据：闸早于扫描、闸块内真 return、refresh 早于闸 */
    private fun scanGateIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        val body = flatten(bodyOf(src, "startScan"))
        val gate = body.indexOf("if (missingPermissions().isNotEmpty())")
        val discover = body.indexOf("startDiscovery")
        val refresh = body.indexOf("refresh()")
        if (gate < 0) return listOf("startScan 缺 missingPermissions() 前置闸（缺权限仍发起扫描 = 静默无结果）")
        if (discover < 0) return listOf("startScan 里找不到 startDiscovery（守卫失去输入）")
        if (refresh < 0) return listOf("startScan 里找不到 refresh()（守卫失去输入：_hasPermission 不会被重算）")
        if (gate > discover) bad.add("权限闸必须先于 startDiscovery")
        if (refresh > gate) bad.add("refresh() 必须先于权限闸：_hasPermission 置 false 要早于早退，UI 引导面才有东西可亮")
        // 闸块本体（`{ ... }` 之间，块内无嵌套大括号，撞到第一个 `}` 就是块尾）必须真早退
        val blockEnd = body.indexOf("}", gate)
        if (blockEnd < 0) bad.add("权限闸块没闭合（守卫失去输入）")
        else if (!body.substring(gate, blockEnd).contains("return")) {
            bad.add("闸内必须 return：只记日志继续往下走 = 照旧发起一次注定无结果的扫描")
        }
        return bad
    }

    /**
     * **权限面白名单**判据（本轮反转后的真身）：见类 KDoc 的「设计决策反转」一节。
     * 空列表 = 面干净且闸到位。
     */
    private fun surfaceIssues(sources: Map<String, String>): List<String> {
        val bad = ArrayList<String>()

        // 申请真源必须还在（不在就整把尺失去输入）
        if (!flatten(sources.getValue("bt/BtSpeakerController.kt")).contains("ACCESS_FINE_LOCATION")) {
            bad.add("bt/BtSpeakerController.kt 里找不到 ACCESS_FINE_LOCATION——扫描前置被拆了（守卫失去输入）")
        }

        // 白名单 · 展示面：设置页位置子行必须带版本闸，且闸先于权限取值
        val settings = flatten(sources.getValue("ui/SettingsScreen.kt"))
        val gateAt = settings.indexOf(settingsGateAnchor)
        val fineAt = settings.indexOf("ACCESS_FINE_LOCATION")
        if (fineAt < 0) {
            bad.add("ui/SettingsScreen.kt 找不到 ACCESS_FINE_LOCATION——#10 的位置子行被删（守卫失去输入）")
        } else if (gateAt < 0) {
            bad.add("ui/SettingsScreen.kt 有位置权限却没版本闸「$settingsGateAnchor」——子行在 API 31+ 也会亮")
        } else if (gateAt > fineAt) {
            bad.add("ui/SettingsScreen.kt 的版本闸必须先于 ACCESS_FINE_LOCATION")
        }

        // 白名单 · 首启申请面：#18 范围更正钉住「首启权限面一字不动」⇒ 它零位置权限
        if (flatten(sources.getValue("ui/MainActivity.kt")).contains("ACCESS_FINE_LOCATION")) {
            bad.add(
                "ui/MainActivity.kt 出现 ACCESS_FINE_LOCATION——#18 范围更正：首启权限面一字未动，" +
                    "位置权限不许进首启申请（它本片不在白名单的开口范围内）"
            )
        }

        // 白名单外一律禁入
        for ((path, src) in sources) {
            if (path in locationWhitelist) continue
            if (flatten(src).contains("ACCESS_FINE_LOCATION")) {
                bad.add("$path 夹带 ACCESS_FINE_LOCATION：位置权限只服务蓝牙扫描，白名单外一律不许")
            }
        }
        return bad
    }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `manifest必须声明位置权限并收在API30以内`() {
        val issues = manifestIssues(manifestText())
        assertEquals("蓝牙发现的位置权限声明回归：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `missingPermissions_S以下查位置权限_S以上零变化`() {
        val issues = permIssues(controllerSrc())
        assertEquals("蓝牙权限分档回归：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `startScan_权限闸先于扫描且闸内早退`() {
        val issues = scanGateIssues(controllerSrc())
        assertEquals("扫描前置闸回归：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `位置权限面白名单契约_展示面带闸_首启面零权限_白名单外禁入`() {
        val issues = surfaceIssues(locationSurfaces())
        assertEquals("位置权限面回归（白名单契约，2026-10-11 反转）：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    // ------------------------------------------------------------------ 突变自证（尺子自己能红）

    @Test
    fun `尺子自己能红_manifest删声明或去掉maxSdkVersion必报红`() {
        val real = manifestText()
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", manifestIssues(real).isEmpty())
        // 突变 1：整条声明删掉（回到 r30 形态——Android 10/11 无位置权限可申请）
        val dropped = Regex("\\s*<uses-permission[^>]*android:name=\"android\\.permission\\.ACCESS_FINE_LOCATION\"[^>]*>").replace(real, "")
        assertTrue("manifest 删掉位置权限声明后尺子必须红", manifestIssues(dropped).isNotEmpty())
        // 突变 2：留着声明但去掉 maxSdkVersion（把位置权限面铺到 S 以上）
        val noCap = real.replace(
            "android.permission.ACCESS_FINE_LOCATION\" android:maxSdkVersion=\"30\"",
            "android.permission.ACCESS_FINE_LOCATION\""
        )
        assertTrue(
            "抹掉 maxSdkVersion 后尺子必须红（位置权限面被铺到 S 以上）",
            manifestIssues(noCap).any { it.contains("maxSdkVersion") }
        )
    }

    @Test
    fun `尺子自己能红_权限分档被拆必报红`() {
        val real = controllerSrc()
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", permIssues(real).isEmpty())
        // 突变 3：版本闸失效（回到 r30 形态——S 以下直接空列表，位置前置整个消失）
        assertTrue(
            "版本闸失效后尺子必须红",
            permIssues(real.replace(sGuard, "if (false)")).isNotEmpty()
        )
        // 突变 4：<S 分支查成同族的 COARSE（系统对蓝牙发现认的是 FINE，粗定位不顶用）
        assertTrue(
            "位置权限换成 ACCESS_COARSE_LOCATION 后尺子必须红",
            permIssues(real.replace("Manifest.permission.ACCESS_FINE_LOCATION", "Manifest.permission.ACCESS_COARSE_LOCATION")).isNotEmpty()
        )
        // 突变 4b：<S 分支把 FINE 整枚删掉（权限前置名存实亡）
        assertTrue(
            "<S 分支删掉 FINE 后尺子必须红",
            permIssues(real.replace("Manifest.permission.ACCESS_FINE_LOCATION", "Manifest.permission.CAMERA"))
                .any { it.contains("<S 分支缺") }
        )
        // 突变 5：把位置权限挪到 ≥S 侧（把「S 以上零变化」破掉）
        val movedToModern = real.replace(
            "add(Manifest.permission.BLUETOOTH_SCAN) }.toTypedArray()",
            "add(Manifest.permission.BLUETOOTH_SCAN) " +
                "if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION) }.toTypedArray()"
        )
        assertTrue(
            "位置权限挪进 ≥S 分支后尺子必须红",
            permIssues(movedToModern).any { it.contains("≥S 分支夹带") }
        )
        // 突变 6：<S 分支误夹带 API 31 才有的 BLUETOOTH_SCAN（低版本恒 DENIED，引导面永远亮）
        assertTrue(
            "<S 分支夹带 BLUETOOTH_SCAN 后尺子必须红",
            permIssues(
                real.replace(
                    "if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {",
                    "if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { add(Manifest.permission.BLUETOOTH_SCAN);"
                )
            ).any { it.contains("<S 分支夹带") }
        )
    }

    @Test
    fun `尺子自己能红_扫描闸被拆必报红`() {
        val real = controllerSrc()
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", scanGateIssues(real).isEmpty())
        // 突变 7：闸内不早退（只记日志继续发起扫描）——按守卫同一条切法挖掉闸块里的 return
        val gateAt = real.indexOf("if (missingPermissions().isNotEmpty())")
        val blockEnd = real.indexOf("}", gateAt)
        assertTrue("守卫失去输入：找不到权限闸块（startScan 结构变了）", gateAt >= 0 && blockEnd > gateAt)
        val noEarlyReturn = real.substring(0, gateAt) +
            real.substring(gateAt, blockEnd).replace("return", "") +
            real.substring(blockEnd)
        assertTrue(
            "权限闸内不早退后尺子必须红",
            scanGateIssues(noEarlyReturn).any { it.contains("闸内必须 return") }
        )
        // 突变 8：把 refresh() 从闸前挪进闸块内（_hasPermission 晚于早退，UI 引导面晚一拍）
        val refreshAfterGate = real
            .replace("val bluetooth = adapter ?: return refresh() ", "val bluetooth = adapter ?: return ")
            .replace(
                "if (missingPermissions().isNotEmpty()) {",
                "if (missingPermissions().isNotEmpty()) { refresh();"
            )
        assertTrue(
            "refresh() 晚于权限闸后尺子必须红",
            scanGateIssues(refreshAfterGate).any { it.contains("refresh() 必须先于权限闸") }
        )
    }

    @Test
    fun `尺子自己能红_位置权限漏进白名单外必报红`() {
        val real = locationSurfaces()
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", surfaceIssues(real).isEmpty())
        // 突变 9：位置权限漏进相机参数链（白名单外）
        val controllerLeak = real.toMutableMap().apply {
            this["camera/CameraController.kt"] = this["camera/CameraController.kt"]!! +
                "\nprivate fun mutantLeak() = Manifest.permission.ACCESS_FINE_LOCATION"
        }
        assertTrue(
            "位置权限漏进 CameraController 后尺子必须红",
            surfaceIssues(controllerLeak).any { it.contains("CameraController") }
        )
        // 突变 10：位置权限漏进取景页（白名单外）
        val screenLeak = real.toMutableMap().apply {
            this["ui/CameraScreen.kt"] = this["ui/CameraScreen.kt"]!! +
                "\nprivate fun mutantLeak() = Manifest.permission.ACCESS_FINE_LOCATION"
        }
        assertTrue(
            "位置权限漏进 CameraScreen 后尺子必须红",
            surfaceIssues(screenLeak).any { it.contains("CameraScreen") }
        )
        // 突变 11：白名单文件里删版本闸（设置页子行无条件出现，与 manifest maxSdkVersion=30 相反）
        val gateStripped = real.toMutableMap().apply {
            this["ui/SettingsScreen.kt"] = this["ui/SettingsScreen.kt"]!!
                .replace("if (btLocationRowVisible(android.os.Build.VERSION.SDK_INT)) {", "")
        }
        assertTrue(
            "设置页删掉版本闸后尺子必须红",
            surfaceIssues(gateStripped).any { it.contains("没版本闸") }
        )
        // 突变 12：首启权限申请面混进位置权限（#18 范围更正明令「首启权限面一字不动」）
        val startupLeak = real.toMutableMap().apply {
            this["ui/MainActivity.kt"] = this["ui/MainActivity.kt"]!! +
                "\nprivate fun mutantLeak() = Manifest.permission.ACCESS_FINE_LOCATION"
        }
        assertTrue(
            "位置权限混进首启权限申请后尺子必须红",
            surfaceIssues(startupLeak).any { it.contains("MainActivity") }
        )
    }
}
