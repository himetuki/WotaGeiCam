package com.wotagei.cam.diag

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * 读 `res/` 下的资源文件（如 `xml/file_paths.xml`）：[mainSourceText] 只认 `src/main/java/...` 下的
     * 源码路径，资源文件得从源码位置向上找（与 `RotateStallGuardTest` 找 strings 资源同一手法）。
     */
    private fun resFile(rel: String): String {
        var d = com.wotagei.cam.source.KotlinSourceScan.mainSourceFile("diag/Diag.kt").parentFile
        while (d != null) {
            val f = java.io.File(d, "res/$rel")
            if (f.isFile) return f.readText(Charsets.UTF_8)
            d = d.parentFile
        }
        throw AssertionError("找不到 res/$rel（资源被挪走/改名，守卫失去输入）")
    }

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
        assertTrue("总开关初值要从设置读（2026-10-13 默认关）", create.contains("diagEnabled("))
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

    /**
     * 用户 2026-10-09 追加：「不用去文件管理器找文件，直接分享给我」。
     * 钉的是**分享这条路不许被"公共目录能不能写"卡住**：分享件落应用缓存目录，且 `FileProvider` 的
     * 路径表必须覆盖 `cache-path`——否则 URI 授不出去、面板弹不出来（这正是"点了没反应"的那类坑）。
     */
    @Test
    fun `直接分享必须走缓存目录且 FileProvider 覆盖缓存`() {
        val diagRaw = mainSourceText("diag/Diag.kt")
        val shareFn = diagRaw.substringAfter("fun exportForShare(")
        assertTrue("分享件落应用缓存目录", shareFn.contains("app.cacheDir"))
        assertTrue("分享件是一次性的：先清上次的", shareFn.contains("listFiles()?.forEach"))
        assertTrue("分享与导出共用同一份报告正文", diagRaw.contains("buildReport(app)"))
        val exportFn = diagRaw.substringAfter("fun export(")
        assertTrue("导出那条路仍写公共 Documents（两条路分工不同）", exportFn.contains("\"Documents/"))
        assertTrue("provider 路径表必须覆盖 cache-path", resFile("xml/file_paths.xml").contains("<cache-path"))
        val block = codeOnly(mainSourceText("ui/SettingsScreen.kt")).substringAfter("private fun BlockDiag(")
        assertTrue("必须有「直接分享」入口", block.contains("Diag.exportForShare("))
        assertTrue("两颗按钮各有忙态、互不串台", block.contains("ACTION_SHARE") && block.contains("ACTION_EXPORT"))
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

    /**
     * 记录入口的两条**语义锚**（2026-10-26 拆闸后的口径）：
     * 1. **logcat 那一路不许被总开关挡住**——真机排查靠它，改前"默认关 ⇒ logcat 一行没有"，
     *    现场只能靠用户口述；改后 `record()` 里必须先走 [com.wotagei.cam.diag.diagLogcatGated]
     *    再走 `diagRecordGated`（顺序钉死：先保住取证通道，再谈要不要落盘）；
     * 2. **崩溃写入路径不许出现 enabled 短路**——本日志系统存在的理由就是"被系统拒绝类问题必须留现场"。
     * 判据全部走函数体定位 + 突变自证，防"文本在哪都行"的假绿。
     */
    @Test
    fun `logcat通道不受总开关，落盘才受`() {
        val record = bodyOf(diag, "record")
        val logcat = record.indexOf("diagLogcatGated(")
        val file = record.indexOf("diagRecordGated(")
        assertTrue("record 必须先判 logcat 通道（总开关关也要能进 logcat）", logcat >= 0)
        assertTrue("落盘仍必须走总开关", file >= 0)
        assertTrue("logcat 判定必须先于落盘判定（先保住取证通道，再谈省空间）", logcat < file)
        // 突变：把 logcat 那一路挪到总开关之后 ⇒ 顺序判据必红
        val swapped = record.replace("diagLogcatGated(", "diagLogcatGatedTmp(")
            .let { it.replace("diagRecordGated(", "diagLogcatGated(") }
            .replace("diagLogcatGatedTmp(", "diagRecordGated(")
        val mutatedRecord = diag.replace(record, swapped)
        val mL = bodyOf(mutatedRecord, "record").indexOf("diagLogcatGated(")
        val mF = bodyOf(mutatedRecord, "record").indexOf("diagRecordGated(")
        assertTrue("对照突变体必须真的改动", mutatedRecord != diag)
        assertFalse("顺序颠倒后判据必须红", mL in 0 until mF)
    }

    @Test
    fun `崩溃写入路径不受总开关影响（取证语义锚）`() {
        val crash = bodyOf(diag, "installCrashHandler")
        assertFalse("崩溃写入函数体内不许出现 enabled 短路（崩溃件必须无条件落盘）", crash.contains("enabled"))
        // 突变自证：往崩溃路径塞一道总开关 ⇒ 判据必红（证明判据真看得见这个函数体）
        val mutant = diag.replace(
            "File(dir, diagCrashName(diagFileStamp(System.currentTimeMillis()))).writeText(text)",
            "if (!enabled) return\n" +
                "File(dir, diagCrashName(diagFileStamp(System.currentTimeMillis()))).writeText(text)"
        )
        assertFalse("对照突变体必须真的改动", mutant == diag)
        assertTrue("崩溃路径被开关卡住后判据必须报出来", bodyOf(mutant, "installCrashHandler").contains("enabled"))
    }

    @Test
    fun `总开关必须落库即时生效关时详细档置灰且清除走Diag_clear`() {
        val block = codeOnly(mainSourceText("ui/SettingsScreen.kt")).substringAfter("private fun BlockDiag(")
        assertTrue("总开关要持久化", block.contains("WotaSettings.setDiagEnabled("))
        assertTrue("总开关要立即生效（不必重启应用）", block.contains("Diag.setEnabled("))
        assertTrue(
            "总开关接线必须排在详细档之前（UI 层级：先能记，才谈记多细）",
            block.indexOf("diagEnabled(prefs)") in 0 until block.indexOf("diagVerbose(prefs)")
        )
        assertTrue("总开关关时详细档 Switch 必须置灰（enabled = diagOn）", block.contains("enabled = diagOn"))
        assertTrue("必须有清除按钮且走 Diag.clear(", block.contains("Diag.clear("))
    }

    /** 判据本体：`diagRecordGated` 里总开关判定（`if (!enabled)`）先于级别档位判定（[diagLevelEnabled]） */
    private fun gateOrderOk(src: String): Boolean {
        val gate = bodyOf(src, "diagRecordGated")
        val on = gate.indexOf("if (!enabled)")
        val lvl = gate.indexOf("diagLevelEnabled(")
        return on in 0 until lvl
    }

    @Test
    fun `落盘总开关的短路顺序仍必须是先开关后档位`() {
        assertTrue("对照组：真源码必须通过", gateOrderOk(diag))
        val m1 = diag.replace("if (!enabled) return true", "")
        assertFalse("对照突变体必须真的改动", m1 == diag)
        assertFalse("摘掉总开关短路后必须红", gateOrderOk(m1))
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

    /**
     * 跨线程状态不许裸奔（2026-10-10 r31·S2-M8-01）。
     *
     * Diag 有四个线程碰它：主线程（install/导出）、写盘线程（writerLoop）、崩溃线程（uncaught handler）、
     * 导出线程（IO）。漏可见性的后果不是"偶尔读到旧值一次"，而是**永久卡在旧值**——
     * install 刚写完 appRef，崩溃线程可能一直读到 null，崩溃件整个不落盘，而那正是最该留的证据。
     *
     * 判据用**反射读 ACC_VOLATILE 位**，不是文本匹配：源码里随便哪行写个 `@Volatile` 就能喂绿文本匹配。
     * lastPruneMs 走读-改-写（先 get 再 set），@Volatile 只保证可见不保证原子 ⇒ 只能是 AtomicLong。
     */
    @Test
    fun `七个跨线程字段必须收口（六个 Volatile 加一个 AtomicLong）`() {
        // 2026-10-13：总开关 enabled 与详细档 verbose 一样由主线程写、任意记录线程读，同入本表
        for (name in listOf("appRef", "logDir", "current", "writer", "enabled", "verbose")) {
            val f = Diag::class.java.getDeclaredField(name)
            assertTrue("$name 漏 @Volatile：别的线程可能永久读到旧值", Modifier.isVolatile(f.modifiers))
        }
        val prune = Diag::class.java.getDeclaredField("lastPruneMs")
        assertEquals("lastPruneMs 必须 AtomicLong（读-改-写会丢更新）", AtomicLong::class.java, prune.type)
    }

    /**
     * 待落盘队列必须封顶（2026-10-10 r31·S2-M8-02）。
     *
     * 写盘线程被系统 IO 卡住时 pending 只进不出，是无界队列；每行几百字节，吃穿内存比丢日志严重。
     * 丢了还得**在导出件头部自曝**——否则用户把"这段现场不全"当成"本来就没发生"。
     */
    @Test
    fun `待落盘队列要封顶且丢弃数要进导出头`() {
        assertTrue("队列上限常量必须存在", diag.contains("DIAG_PENDING_MAX_LINES"))
        // 判据必须**按函数体**取：substringAfter 会一路读到文件尾，把 dropOldestPending 自己的定义
        // 也算进 record 的证据里——那样"把 record 里那行删掉"照样全绿（本项目踩过的假绿同族）。
        val record = bodyOf(diag, "record")
        assertTrue("记录路径每次入队后都要走封顶处理", record.contains("dropOldestPending()"))
        val drop = bodyOf(diag, "dropOldestPending")
        assertTrue("丢多少要走判据（纯函数，不是拍脑袋的魔数）", drop.contains("diagPendingOverflow("))
        assertTrue("丢的必须是最老的（poll 从队头取）", drop.contains("pending.poll()"))
        assertTrue("丢掉的行必须计数（不计数就自曝不了）", drop.contains("droppedPending.incrementAndGet()"))
        val report = bodyOf(diag, "buildReport")
        assertTrue("丢弃计数要写进导出头", report.contains("diagDroppedNote(droppedPending.get())"))
        // 位置也要钉：自曝行必须紧跟头部信息，埋到末尾等于没有（用户从上往下读）。
        // 偏移取自遮蔽文本（与原文逐位等长），"=== 结束" 是字面量 ⇒ 回原文比对
        val raw = mainSourceText("diag/Diag.kt")
        val region = regionOf(diag, "buildReport")
        val reportRaw = raw.substring(region.start, region.end)
        val noteAt = reportRaw.indexOf("diagDroppedNote(droppedPending.get())")
        val endAt = reportRaw.indexOf("=== 结束")
        assertTrue("找不到丢弃自曝调用点", noteAt >= 0)
        assertTrue("找不到结束标记", endAt >= 0)
        assertTrue("丢弃自曝必须排在结束标记之前（不能埋到末尾）", noteAt < endAt)
    }

    /**
     * `appVersionCode` 不许再留版本分支（#14：清掉 `ObsoleteSdkInt`）。
     *
     * 本工程 minSdk 29 ≥ 28，`PackageInfo.longVersionCode` 自 API 28 起就有 ⇒ 原来那句
     * `if (SDK_INT >= VERSION_CODES.P)` 是**恒真**判断，Lint 报 `ObsoleteSdkInt`
     * （r31 十条 warning 里唯一由本轮引入的一条）。清掉后 10→9，防掩盖后续新增告警。
     *
     * 它是表达式体（`= runCatching { … }.getOrDefault(0L)`），`bodyOf` 定位不到，按声明切片。
     * 切片判据含 `getOrDefault(0L)` 作落点自检：切错了立刻红，不给半份文本当证据。
     */
    @Test
    fun `版本号取longVersionCode且不许有恒真的版本分支`() {
        val b = appVersionCodeBody(diag)
        assertTrue("拿不到 PackageInfo 仍要回 0L（切片必须落在 appVersionCode 上）", b.contains("getOrDefault(0L)"))
        assertTrue("必须读 longVersionCode", b.contains("longVersionCode"))
        assertFalse("minSdk 29 下 SDK_INT >= VERSION_CODES.P 是恒真判断（ObsoleteSdkInt）", b.contains("VERSION_CODES.P"))
        assertFalse("不许再有 SDK_INT 版本分支", b.contains("SDK_INT"))
        assertFalse("不许回退到已废弃的 versionCode", b.contains("versionCode.toLong()"))
        assertTrue("取不到 PackageInfo 的兜底不许去掉（设备摘要整段会炸）", b.contains("runCatching"))
    }

    /**
     * 突变自证：把恒真分支改回去（= 恢复 `ObsoleteSdkInt`），判据必须红。
     *
     * 突变体是**真偏离**而非语义空操作：改回去后 `SDK_INT` 与 `VERSION_CODES.P` 两个符号
     * 真重新出现在该函数切片里，而它们在真值表上原本是「不存在」。
     */
    @Test
    fun `突变自证：把恒真版本分支改回去必红`() {
        val real = appVersionCodeBody(diag)
        assertFalse("对照组：真源码上判据必须为假（否则下面证明不了什么）", versionBranchIn(real))
        val mutant = real.replace(
            "appRef!!.packageManager.getPackageInfo(appRef!!.packageName, 0).longVersionCode",
            "val ai = appRef!!.packageManager.getPackageInfo(appRef!!.packageName, 0) " +
                "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ai.longVersionCode " +
                "else @Suppress(\"DEPRECATION\") ai.versionCode.toLong()"
        )
        assertFalse("对照组突变体必须真的改动了源码（没改动 = 这条自证什么也证明不了）", mutant == real)
        assertTrue("改回恒真版本分支后判据必须报出来", versionBranchIn(mutant))
    }

    /**
     * `appVersionCode` 是表达式体，按声明切片到下一个顶层 `}`。
     *
     * 声明没了必须抛：`substringAfter` 找不到定界符时**原样返回整篇源码**，那样切片会一路读到
     * 第一个 `\n}`，里面照样找得到 `runCatching`/`getOrDefault` —— 守卫就变成了"文件里哪都行"，
     * 函数被改名或挪走时它照样全绿。定位不到输入的守卫是最坏的一种守卫。
     */
    private fun appVersionCodeBody(src: String): String {
        val anchor = "private fun appVersionCode("
        if (!src.contains(anchor)) throw AssertionError("找不到 $anchor —— 函数被改名/挪走，本守卫失去输入")
        return com.wotagei.cam.source.KotlinSourceScan.flatten(
            src.substringAfter(anchor).substringBefore("\n}")
        )
    }

    /** 判据本体：该切片里是否又出现了恒真的版本分支（`VERSION_CODES.P` 或 `SDK_INT`） */
    private fun versionBranchIn(body: String): Boolean =
        body.contains("VERSION_CODES.P") || body.contains("SDK_INT")
}
