package com.wotagei.cam.diag

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志的**接线与红线守卫**（2026-10-09）。
 *
 * 钉四件事，每一件都有"漏了就白做"的后果：
 * 1. **单一真源**：除本包外全工程不许再 `import android.util.Log`——漏一个文件，那个子系统的日志就
 *    在真机上（EMUI/鸿蒙容器看不到 logcat）彻底消失，而这正是本轮要治的病；
 * 2. **网络红线**：日志子系统**不许有任何网络调用**（工程红线是网络只允许出现在 `update/` 包），
 *    导出走本地文件 + 系统分享；
 * 3. **崩溃不许吃掉别人的处理器**：必须链到上一个处理器再交回去；
 * 4. **导出必须离开主线程**且落在用户可见目录、带应用私有目录兜底。
 */
class DiagGuardTest {

    private val diag = codeOnly(mainSourceText("diag/Diag.kt"))

    @Test
    fun `全工程只有 diag 包可以用 android_util_Log`() {
        // 直接扫源码树：除 diag/Diag.kt 外，任何文件出现 import android.util.Log 都判失败
        val root = com.wotagei.cam.source.KotlinSourceScan.mainSourceFile("diag/Diag.kt").parentFile!!.parentFile!!
        val offenders = ArrayList<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (rel.startsWith("diag/")) return@forEach
                val text = f.readText(Charsets.UTF_8)
                if (text.contains("import android.util.Log")) offenders.add(rel)
            }
        assertTrue("除 diag 包外不许再 import android.util.Log（漏一个就丢一个子系统的日志）：$offenders", offenders.isEmpty())
    }

    @Test
    fun `日志子系统不许有任何网络调用（红线：网络只在 update 包）`() {
        for (banned in listOf("java.net", "HttpURLConnection", "URL(", "OkHttp", "okhttp", "Socket")) {
            assertFalse("diag 包出现网络痕迹：$banned", diag.contains(banned))
        }
    }

    @Test
    fun `崩溃处理器必须链到上一个处理器`() {
        val install = diag.substringAfter("private fun installCrashHandler(")
        assertTrue("必须取到上一个处理器", install.contains("Thread.getDefaultUncaughtExceptionHandler()"))
        assertTrue("必须把异常交回上一个处理器（否则吃掉别人的处理）", install.contains("prev.uncaughtException(thread, tr)"))
        assertTrue("崩溃件必须同步写（进程要死了）", install.contains("writeText(text)"))
    }

    @Test
    fun `启动时必须装日志且在业务之前`() {
        val app = codeOnly(mainSourceText("WotaApp.kt"))
        val create = bodyOf(app, "onCreate")
        assertTrue("冷启必须装", create.contains("Diag.install("))
        assertTrue("详细档要从设置读", create.contains("diagVerbose("))
    }

    @Test
    fun `导出必须切 IO-写用户可见目录-带私有兜底-走系统分享`() {
        val screen = codeOnly(mainSourceText("ui/SettingsScreen.kt"))
        val block = screen.substringAfter("private fun BlockDiag(")
        assertTrue("导出必须切 IO 线程", block.contains("withContext(Dispatchers.IO)"))
        assertTrue("导出完要拉起系统分享", block.contains("shareLogFile("))
        val share = screen.substringAfter("private fun shareLogFile(")
        assertTrue("分享走 FileProvider（不许 file:// 裸 Uri）", share.contains("FileProvider.getUriForFile("))
        assertTrue("分享用 ACTION_SEND", share.contains("Intent.ACTION_SEND"))
        // 这两条看的是"导出的落点文案/兜底分支"，涉及字符串字面量 ⇒ 必须对**原始源码**断言
        // （codeOnly 会把字面量遮蔽成空格，用它判字面量必假——本项目踩过同一坑）
        val diagRaw = mainSourceText("diag/Diag.kt")
        assertTrue("导出件写公共 Documents（用户能自己找）", diagRaw.contains("\"Documents/"))
        assertTrue("公共目录不可写时要退到应用私有目录", diagRaw.contains("File(dir, name)"))
    }

    @Test
    fun `设置页必须有诊断日志区块（枚举-两栏表-派发三处齐）`() {
        // 枚举那行含字符串字面量 ⇒ 对原始源码断言
        assertTrue("枚举要有 DIAG", mainSourceText("ui/SettingsLayout.kt").contains("DIAG(\"diag\")"))
        val screen = codeOnly(mainSourceText("ui/SettingsScreen.kt"))
        assertTrue("派发要有 DIAG 分支", screen.contains("SettingsBlock.DIAG -> BlockDiag(prefs)"))
    }

    @Test
    fun `详细档开关必须同时落库与即时生效`() {
        val screen = codeOnly(mainSourceText("ui/SettingsScreen.kt"))
        val block = screen.substringAfter("private fun BlockDiag(")
        assertTrue("要持久化", block.contains("WotaSettings.setDiagVerbose("))
        assertTrue("要立即生效（不必重启应用）", block.contains("Diag.setVerbose("))
    }

    @Test
    fun `记录动作不许把主流程带崩（全程吞异常）`() {
        val record = diag.substringAfter("private fun record(")
        assertTrue("记录入口必须包 runCatching", record.contains("runCatching"))
        val apply = diag.substringAfter("private fun drainToFile(")
        assertTrue("落盘失败不许弄死写线程", apply.contains("runCatching"))
    }

    @Test
    fun `上限必须真被用到（环形裁剪-轮转判定-导出截断）`() {
        assertTrue("环形缓冲要裁剪", diag.contains("while (ring.size > DIAG_RING_LINES)"))
        assertTrue("轮转要走判定函数", diag.contains("diagShouldRotate("))
        assertTrue("导出要截断", diag.contains("DIAG_EXPORT_MAX_BYTES"))
        assertTrue("崩溃件要限行数", diag.contains("DIAG_CRASH_TAIL_LINES"))
    }

    @Test
    fun `内录被拒的关键事实必须留全（本轮要治的病）`() {
        // 断言的是"日志行里记了哪些字段"（全在字符串字面量里）⇒ 用原始源码
        val capture = mainSourceText("record/PlaybackCaptureController.kt")
        assertTrue("能力探测要记（容器没实现媒体投影是最常见形态）", capture.contains("projectionManager="))
        assertTrue("授权回执结果要记", capture.contains("onConsentResult: resultCode="))
        assertTrue("采集器建失败的异常类名与请求参数要记", capture.contains("e.javaClass.name"))
        assertTrue("请求参数（采样率/声道/编码/缓冲）要记", capture.contains("req rate="))
        val apply = capture.substringAfter("private fun apply(")
        assertTrue("状态迁移要留痕（卡在哪一态是唯一线索）", apply.contains("capture 状态"))
    }
}
