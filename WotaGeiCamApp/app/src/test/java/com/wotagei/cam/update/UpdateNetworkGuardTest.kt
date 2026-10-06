package com.wotagei.cam.update

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「检查更新」联网边界的**源码/清单守卫**（2026-10-06 用户批准解除原「无 INTERNET」红线）：
 * - manifest：INTERNET 在位，且**除此之外不许出现任何其他网络类权限**（其余权限逐个对白名单核名）；
 * - 网络代码只允许住在 `update/` 包：全主源码 grep `HttpURLConnection|openConnection|java.net.`
 *   只准命中 update/ 包的文件——别的包想偷偷发请求，编译不拦、守卫拦；
 * - `update/` 包的引用面收敛：只许 update/ 包自己与 `ui/SettingsScreen.kt` 引用更新符号，
 *   防别的页面挂自动检查入口；
 * - [UpdateChecker] 保持纯 JVM（零 android import），行为测试直打的前提。
 *
 * 全部判据作用在 [KotlinSourceScan.codeOnly] 遮蔽后的文本上（注释里提到旧符号不算违规）。
 * 「尺子自己能红」：合成突变体（把网络符号塞进别的包、删 INTERNET、混入其他网络权限）
 * 同打一把尺，必报红。
 *
 * 诚实边界（修复轮 P3-4，绊线非对抗，接受现状）：
 * 1. 本守卫钉「入口符号」（probeLatestRelease/downloadApk/UpdateInstaller.install）的引用面与
 *    UpdateSection 函数体，SettingsScreen 体外**直接调 UpdateDownloader.fetchJson** 的旁路不在
 *    符号表里——绕过编排层探测是可能的，靠 code review 拦；
 * 2. 网络符号表只列 HttpURLConnection/openConnection/java.net.，SSLSocketFactory、
 *    Apache HttpClient、OkHttp 之类的旁路 API 不在表内——想对抗式绕过拦不住，本守卫只防「顺手污染」。
 */
class UpdateNetworkGuardTest {

    // ------------------------------------------------------------------ 文件定位与遍历

    /** 主源码包根定位：同 KotlinSourceScan 的逐层上溯策略；找不到必须抛，不给全绿假象 */
    private fun mainPackageDir(): File {
        val rel = "src/main/java/com/wotagei/cam"
        var dir: File = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val f = File(base, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到主源码包根 $rel——守卫不能在没有输入的情况下算通过")
    }

    private fun manifestText(): String {
        val rel = "src/main/AndroidManifest.xml"
        var dir: File = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val f = File(base, rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 AndroidManifest.xml——守卫不能在没有输入的情况下算通过")
    }

    /** 遍历主源码全部 .kt：relPath（`<相对包根路径>`）→ 遮蔽后的代码文本。跳过 `.` 开头的隐藏目录 */
    private fun walkMaskedSources(): Map<String, String> {
        val root = mainPackageDir()
        val out = LinkedHashMap<String, String>()
        fun walk(dir: File) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                when {
                    f.isDirectory && !f.name.startsWith(".") -> walk(f)
                    f.isFile && f.name.endsWith(".kt") ->
                        out[f.relativeTo(root).path] = KotlinSourceScan.codeOnly(f.readText(Charsets.UTF_8))
                }
            }
        }
        walk(root)
        assertTrue("主源码一个 .kt 都没读到——遍历器坏了", out.isNotEmpty())
        return out
    }

    // ------------------------------------------------------------------ 判据（纯函数，真身与突变体同吃）

    /** manifest 权限判据：INTERNET 在位；其余每个权限都在白名单里（新权限必须显式过审） */
    private fun manifestIssues(m: String): List<String> {
        val allowed = setOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.VIBRATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.INTERNET",
        )
        val issues = ArrayList<String>()
        if (!m.contains("android.permission.INTERNET")) issues.add("缺 INTERNET：检查更新功能的联网前提")
        Regex("uses-permission\\s+android:name\\s*=\\s*\"([^\"]+)\"").findAll(m).forEach { match ->
            val name = match.groupValues[1]
            if (name !in allowed) issues.add("未过审的权限 $name（新权限必须先进 UpdateNetworkGuardTest 白名单）")
        }
        // 网络类权限只许 INTERNET 一个（ACCESS_NETWORK_STATE / WIFI_STATE 等一律不许进源清单；
        // media3 合并进来的 ACCESS_NETWORK_STATE 只读"有没有网"不打 socket，不在源 manifest 里，别误报）
        val networkDeny = listOf("ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE", "CHANGE_WIFI_STATE")
        networkDeny.forEach {
            if (m.contains("android.permission.$it")) issues.add("网络类权限 $it 不许进 manifest（联网只许 INTERNET 一条）")
        }
        return issues
    }

    /** 网络符号只许住在 update/ 包。返回违规的 (文件, 符号) 列表 */
    private fun networkSymbolViolations(sources: Map<String, String>): List<String> =
        sources.flatMap { (path, masked) ->
            val symbols = listOf("HttpURLConnection", "openConnection", "java.net.")
                .filter { masked.contains(it) }
            if (symbols.isNotEmpty() && !path.contains("update")) {
                listOf("$path 含网络符号 $symbols——网络代码只允许在 update/ 包")
            } else {
                emptyList()
            }
        }

    /** 更新符号（入口函数/类名）只许 update/ 包与 SettingsScreen 引用——防别的页面挂自动检查 */
    private fun updateSymbolViolations(sources: Map<String, String>): List<String> =
        sources.flatMap { (path, masked) ->
            val symbols = listOf("probeLatestRelease", "UpdateDownloader", "UpdateInstaller", "ProbeResult", "UpdateChecker")
                .filter { masked.contains(it) }
            val allowedLocation = path.contains("update") || path.replace('\\', '/') == "ui/SettingsScreen.kt"
            if (symbols.isNotEmpty() && !allowedLocation) {
                listOf("$path 引用了更新符号 $symbols——更新入口只允许 SettingsScreen（手动点击触发）")
            } else {
                emptyList()
            }
        }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `manifest_INTERNET在位且无其他网络权限_权限逐个对白名单`() {
        val issues = manifestIssues(manifestText())
        assertEquals("manifest 联网边界被破坏：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `网络符号只出现在update包`() {
        val violations = networkSymbolViolations(walkMaskedSources())
        assertEquals("网络代码越界：\n${violations.joinToString("\n")}", emptyList<String>(), violations)
    }

    @Test
    fun `更新符号只被update包与设置页引用`() {
        val violations = updateSymbolViolations(walkMaskedSources())
        assertEquals("更新入口越界：\n${violations.joinToString("\n")}", emptyList<String>(), violations)
    }

    @Test
    fun `UpdateChecker保持纯JVM_零android依赖`() {
        val masked = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("update/UpdateChecker.kt")
        )
        assertTrue(
            "update/UpdateChecker.kt 必须保持纯 JVM（无 android/androidx import）：JVM 单测直打的前提",
            !masked.contains("import android") && !masked.contains("androidx")
        )
    }

    // ------------------------------------------------------------------ 无自动检查（用户裁决的功能语义）

    /**
     * 「更新只许点击触发」的判据（纯函数，真身与突变体同吃）：
     * - SettingsScreen 里**所有**探测/下载/安装入口的调用点都必须落在 `fun UpdateSection` 体内——
     *   入口一旦出现在别的函数（尤其生命周期观察者里），就是自动检查回归；
     * - UpdateSection 体内不许出现任何生命周期/重组自动触发符号
     *   （DisposableEffect、LifecycleEventObserver、LaunchedEffect、LocalLifecycleOwner、ON_RESUME、
     *   collectAsState）。注意 SettingsScreen 其他地方本就有权限刷新用的观察者，所以必须按
     *   函数体圈范围，不能对整文件做「不含」判定。
     */
    private fun autoCheckIssues(masked: String): List<String> {
        val issues = ArrayList<String>()
        val region = try {
            KotlinSourceScan.regionOf(masked, "UpdateSection")
        } catch (e: AssertionError) {
            return listOf("找不到唯一的 fun UpdateSection 体内区间——守卫失去输入：${e.message}")
        }
        for (symbol in listOf("probeLatestRelease(", "downloadApk(", "UpdateInstaller.install(")) {
            KotlinSourceScan.occurrences(masked, symbol).forEach { off ->
                if (off !in region.start until region.end) {
                    issues.add("更新入口 $symbol 出现在 UpdateSection 之外——自动检查的防线上在引用面")
                }
            }
        }
        val body = masked.substring(region.start, region.end)
        for (token in listOf(
            "DisposableEffect", "LifecycleEventObserver", "LaunchedEffect",
            "LocalLifecycleOwner", "ON_RESUME", "collectAsState"
        )) {
            if (body.contains(token)) {
                issues.add("UpdateSection 体内出现生命周期/自动触发符号 $token——更新只许用户点击触发")
            }
        }
        // 修复轮 P3-2 收口：接线锚必须落在 **probeLatestRelease 调用区域之内**（probeTail），
        // 不再对整个 UpdateSection 体做 contains——否则下载侧/别处的同名满足会掩盖探测接线被删
        val probeTail = body.substringAfter("probeLatestRelease(", missingDelimiterValue = "")
        if (probeTail.isEmpty()) {
            issues.add("UpdateSection 里找不到 probeLatestRelease 调用——守卫失去输入")
        } else {
            val flatTail = KotlinSourceScan.flatten(probeTail)
            if (!flatTail.contains("isCancelled = { probeCancelled.get() }")) {
                issues.add("探测调用必须传取消探针 isCancelled = { probeCancelled.get() }（检查中点行 = 取消）")
            }
            if (!probeTail.contains("PROBE_READ_TIMEOUT_MS")) {
                issues.add("探测必须用专用短读超时 PROBE_READ_TIMEOUT_MS（P2-2：「检查中…」不可挂数分钟）")
            }
            // 修复轮 P3-2（末轮）：非 Idle 探测回执（UpdateAvailable/UpToDate/Failed）写回前都必须查
            // probeCancelled——探针参数 1 处 + 三处写回 = 至少 4 处
            if (KotlinSourceScan.occurrences(flatTail, "probeCancelled.get()").size < 4) {
                issues.add("非 Idle 探测回执写回前必须查 probeCancelled（P3-1/P3-2：取消落在最后一个在飞请求窗口内统一回 Idle）")
            }
        }
        // 修复轮 P2 按代隔离接线：下载取消标志每轮新建、捕获进协程闭包，行点击永不复位
        if (!body.contains("val genCancelled = AtomicBoolean(false)")) {
            issues.add("下载取消标志必须按代隔离（onDownload 里新建 AtomicBoolean 捕获进闭包，修复轮 P2）")
        }
        if (!KotlinSourceScan.flatten(body).contains("isCancelled = { genCancelled.get() }")) {
            issues.add("下载协程必须用本代取消标志 genCancelled（修复轮 P2：共享标志跨代复位会复活已取消下载）")
        }
        if (body.contains("downloadGenFlag.set(false)") || body.contains("downloadGenFlag?.set(false)")) {
            issues.add("行点击（检查）不许触碰下载取消标志（修复轮 P2：跨代复位会复活已取消下载/交错写 part）")
        }
        // 修复轮 P3-1（末轮）：下载回执与进度写回必须判代——旧代一律丢弃，不碰当前可见代
        if (!body.contains("downloadGenFlag !== genCancelled")) {
            issues.add("下载回执写回前必须判代（downloadGenFlag !== genCancelled，修复轮 P3-1：旧代回执不许清掉新一代）")
        }
        if (!body.contains("downloadGenFlag === genCancelled")) {
            issues.add("下载进度写回前必须判代（downloadGenFlag === genCancelled，修复轮 P3-1：旧代进度不许碰新一代对话框）")
        }
        return issues
    }

    @Test
    fun `设置页更新入口只活在UpdateSection体内_无生命周期自动触发`() {
        val masked = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt")
        )
        // 守卫必须有输入：UpdateSection 里真的挂着这三个入口
        assertTrue("UpdateSection 里必须真的有探测入口（否则守卫空转）", masked.contains("probeLatestRelease("))
        val issues = autoCheckIssues(masked)
        assertEquals("「无自动检查」被破坏：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `update包文件不挂生命周期观察者`() {
        val updateFiles = walkMaskedSources().filterKeys { it.contains("update") }
        assertTrue("update 包一个文件都没读到——遍历器坏了", updateFiles.isNotEmpty())
        updateFiles.forEach { (path, masked) ->
            for (token in listOf("Lifecycle", "DisposableEffect", "LaunchedEffect", "ON_RESUME")) {
                assertTrue(
                    "$path 出现 $token——update 包不许观察生命周期（无自动检查）",
                    !masked.contains(token)
                )
            }
        }
    }

    @Test
    fun `尺子自己能红_入口挪出UpdateSection或挂观察者必报红`() {
        val real = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt")
        )
        // 突变 1：把探测入口复制一份到 UpdateSection 之外的普通函数（模拟别的入口/自动触发）
        val outside = real + "\nprivate fun mutantHook() { probeLatestRelease(\"0.0.7\", { null }, { null }) }"
        val issueOutside = autoCheckIssues(outside)
        assertTrue("入口挪出 UpdateSection 后尺子必须红", issueOutside.any { it.contains("probeLatestRelease") })
        // 突变 2：给 UpdateSection 挂上生命周期观察者（模拟 ON_RESUME 自动检查）
        val region = KotlinSourceScan.regionOf(real, "UpdateSection")
        val withObserver = real.substring(0, region.end - 1) + "\nval o = LifecycleEventObserver { _, _ -> }\n" +
            real.substring(region.end - 1)
        assertTrue(
            "UpdateSection 挂观察者后尺子必须红",
            autoCheckIssues(withObserver).any { it.contains("LifecycleEventObserver") }
        )
        // 突变 3：探测改回长读超时（P2-2 短超时接线被拆）
        val longTimeout = real.replace("UpdateDownloader.PROBE_READ_TIMEOUT_MS", "UpdateDownloader.READ_TIMEOUT_MS")
        assertTrue(
            "探测读超时改回 30s 后尺子必须红（检查中会挂数分钟）",
            autoCheckIssues(longTimeout).any { it.contains("PROBE_READ_TIMEOUT_MS") }
        )
        // 突变 4（修复轮 P3-2）：删掉探测的取消接线（isCancelled 参数）——上一轮的弱锚被下载侧
        // 三处 cancelled.get() 满足会漏，现在锚钉在 probe 调用区域内必红
        val probeWiringStripped = real.replace("isCancelled = { probeCancelled.get() }", "")
        assertTrue(
            "探测取消接线被删后尺子必须红（弱锚收口）",
            autoCheckIssues(probeWiringStripped).any { it.contains("isCancelled = { probeCancelled.get() }") }
        )
        // 突变 5（修复轮 P2）：下载取消标志回退成共享——genCancelled 不再是每轮新建
        val sharedFlag = real.replace("val genCancelled = AtomicBoolean(false)", "val genCancelled = probeCancelled")
        assertTrue(
            "下载取消标志回退为共享后尺子必须红（跨代复位复活已取消下载）",
            autoCheckIssues(sharedFlag).any { it.contains("按代隔离") }
        )
        // 突变 6（修复轮 P2）：行点击触碰下载标志（复位它）
        val rowTouchesDownload = real.substring(0, real.indexOf("probeLatestRelease(")) +
            "downloadGenFlag?.set(false)\n" + real.substring(real.indexOf("probeLatestRelease("))
        assertTrue(
            "行点击复位下载标志后尺子必须红",
            autoCheckIssues(rowTouchesDownload).any { it.contains("行点击（检查）不许触碰下载取消标志") }
        )
        // 突变 7（修复轮 P3-2 末轮）：非 Idle 探测回执的取消检查全部被删
        val writeBackStripped = real
            .replace("if (!probeCancelled.get())", "if (true)")
            .replace("rowState = if (probeCancelled.get())", "rowState = if (false)")
        assertTrue(
            "非 Idle 探测回执的取消检查被删后尺子必须红",
            autoCheckIssues(writeBackStripped).any { it.contains("P3-2") }
        )
        // 突变 8（修复轮 P3-1 末轮）：下载回执的判代被删（回退成无条件写回）
        val receiptGenStripped = real.replace("if (downloadGenFlag !== genCancelled) return@launch", "")
        assertTrue(
            "下载回执判代被删后尺子必须红",
            autoCheckIssues(receiptGenStripped).any { it.contains("downloadGenFlag !== genCancelled") }
        )
        // 突变 9（修复轮 P3-1 末轮）：下载进度写回的判代被删
        val progressGenStripped = real.replace("downloadGenFlag === genCancelled", "true")
        assertTrue(
            "下载进度判代被删后尺子必须红",
            autoCheckIssues(progressGenStripped).any { it.contains("downloadGenFlag === genCancelled") }
        )
    }

    // ------------------------------------------------------------------ 突变自证（尺子自己能红）

    @Test
    fun `尺子自己能红_网络符号越界突变体必报红`() {
        val mutant = mapOf("media/Share.kt" to "val c = java.net. URL(x).openConnection() as HttpURLConnection")
        assertTrue(
            "把网络符号塞进 media 包后尺子必须红（否则网络边界判据空转）",
            networkSymbolViolations(mutant).isNotEmpty()
        )
        // update/ 包内的同名符号不算违规（正例也过一遍尺子）
        val ok = mapOf("update/UpdateDownloader.kt" to "val c = URL(x).openConnection() as HttpURLConnection")
        assertTrue(networkSymbolViolations(ok).isEmpty())
    }

    @Test
    fun `尺子自己能红_删INTERNET与混入网络权限突变体必报红`() {
        val real = manifestText()
        assertTrue("删掉 INTERNET 后尺子必须红", manifestIssues(real.replace("android.permission.INTERNET", "")).isNotEmpty())
        assertTrue(
            "混入 ACCESS_NETWORK_STATE 后尺子必须红",
            manifestIssues(real + "\n<uses-permission android:name=\"android.permission.ACCESS_NETWORK_STATE\" />").isNotEmpty()
        )
        assertTrue("未过审的杂权限进 manifest 必须红", manifestIssues(real + "\n<uses-permission android:name=\"android.permission.NFC\" />").isNotEmpty())
    }

    @Test
    fun `尺子自己能红_更新符号被别的页面引用突变体必报红`() {
        val mutant = mapOf(
            "ui/CameraScreen.kt" to "val r = probeLatestRelease(\"0.0.7\", { null }, { null })",
            "update/UpdateChecker.kt" to "fun probeLatestRelease() {}"
        )
        val issues = updateSymbolViolations(mutant)
        assertTrue(
            "CameraScreen 引用更新入口后尺子必须红（自动检查的防线上在引用面）",
            issues.any { it.contains("CameraScreen") }
        )
    }
}
