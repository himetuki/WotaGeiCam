package com.wotagei.cam.diag

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内**诊断日志**（2026-10-09，用户指令「把搁置已久的日志功能加上」）。
 *
 * # 为什么需要它（不是"顺手加个日志"）
 * 本项目所有现成日志都走 `android.util.Log`，而真机取证时反复证实：**华为 EMUI / 鸿蒙容器的日志通道对
 * 我们不可见**（`logcat -b all` 里我们的 tag 恒 0 行、`dumpsys` 也被裁），历史上多条缺陷的根因都只能
 * 靠"写文件"绕（`.drops.json`、`rotate-fail.log`）。用户在鸿蒙 7 的卓易通里遇到「系统拒绝内录」时
 * 完全无从下手，正是因为**现场没有留下任何可导出的证据**。所以这里做的是「应用自持、用户可导出」的日志。
 *
 * # 模式（为"小项目、少用户、问题往往事后才报"设计）
 * - **默认常开、轻量**：环形缓冲（内存 [DIAG_RING_LINES] 行）+ 后台线程落盘，单文件滚到
 *   [DIAG_FILE_MAX_BYTES] 就轮转，最多留 [DIAG_FILE_KEEP] 个 ⇒ 常驻占用个位数 MB 以内。
 *   之所以不是"崩溃时才写"：本类要治的是**功能被系统拒绝**这类**不崩溃**的问题——崩溃日志帮不上忙，
 *   必须留得住事发前后的上下文。
 * - **详细档（用户可开）**：默认只记 `i/w/e`（关键事件），开启后连 `d/v` 一起记——配合用户复现时用。
 * - **导出由用户触发**：设置页一键把全部日志合并成一份 txt 放到公共 `Documents/WotaGeiCam/`，
 *   再拉起系统分享面板，用户想发给谁就发给谁。**日志只在本机、永不上传**（本包不含任何网络调用）。
 *
 * # 采集面（怎么把全工程 ~1000 个调用点接进来的）
 * 调用点保持原样不动——各文件只把 `import android.util.Log` 换成
 * `import com.wotagei.cam.diag.Diag as Log`（同签名转发），于是全应用 `Log.i/w/e` 自动落进本类。
 * 「除本包外不许再 import android.util.Log」由 `DiagGuardTest` 钉死（单一真源）。
 *
 * # 线程与失败口径
 * 记录动作**不许影响主流程**：全程 try/catch 吞掉（日志失败绝不能连坐录制/播放），落盘在后台线程，
 * 崩溃路径才同步写（进程要死了，必须写完）。异常/线程/时间戳都进行头。
 */
object Diag {

    /** 内存环形缓冲行数上限（导出时会带上；够覆盖"从冷启到复现"的上下文） */
    internal const val DIAG_RING_LINES = 3_000

    /** 单个日志文件上限，超过即轮转 */
    internal const val DIAG_FILE_MAX_BYTES = 512L * 1024L

    /** 轮转文件保留个数（含当前文件 ⇒ 磁盘占用上限 = 上限 × 个数） */
    internal const val DIAG_FILE_KEEP = 3

    /** 导出件最多带多少字节（防手滑把几十 MB 发出去） */
    internal const val DIAG_EXPORT_MAX_BYTES = 2L * 1024L * 1024L

    /** 崩溃件里带多少行上下文 */
    internal const val DIAG_CRASH_TAIL_LINES = 400

    private const val BASE_NAME = "wotagei.log"
    private const val CRASH_PREFIX = "crash-"
    private const val EXPORT_PREFIX = "wotagei-log-"
    private const val LOG_DIR_NAME = "logs"
    private const val EXPORT_DIR_NAME = "WotaGeiCam"

    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val pending = ConcurrentLinkedQueue<String>()
    private val installed = AtomicBoolean(false)

    /** 详细档：开启后 `d/v` 也记（用户复现问题时用，持久化由设置页负责） */
    @Volatile
    var verbose: Boolean = false
        private set

    private var appRef: Application? = null
    private var logDir: File? = null
    private var current: File? = null
    private var writer: Thread? = null
    private var lastPruneMs = 0L

    // region 生命周期

    /**
     * 装日志（冷启调一次；重复调用只更新 [verbose]）。**必须在写第一条日志之前调用**才能收全上下文。
     * @param verbose 详细档初值（设置页持久化的那份）
     */
    fun install(app: Application, verbose: Boolean = false) {
        this.verbose = verbose
        if (!installed.compareAndSet(false, true)) return
        appRef = app
        runCatching {
            logDir = File(app.getExternalFilesDir(null), LOG_DIR_NAME).apply { mkdirs() }
            current = File(logDir, BASE_NAME)
            startWriter()
            installCrashHandler(app)
            val prev = app.getSharedPreferences("wota_diag", Context.MODE_PRIVATE)
                .getString("last_session", null)
            i("WotaDiag", "日志启动：上一会话结束于 ${prev ?: "（无记录）"}")
        }
        i("WotaDiag", "设备与版本：${deviceSummary(app)}")
    }

    /** 用户切详细档（设置页调用；只影响之后的记录） */
    fun setVerbose(on: Boolean) {
        verbose = on
        i("WotaDiag", if (on) "详细日志已开启（含 d/v 级）" else "详细日志已关闭（只记 i/w/e）")
    }

    private fun startWriter() {
        if (writer != null) return
        val t = Thread(::writerLoop, "wota-diag")
        t.isDaemon = true
        writer = t
        t.start()
    }

    /** 后台落盘：攒一拍写一次，日志 I/O 不进调用线程 */
    private fun writerLoop() {
        while (true) {
            try {
                Thread.sleep(1000L)
                drainToFile()
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // 落盘失败不许把线程弄死（否则后续日志全丢）
            }
        }
    }

    private fun drainToFile() {
        val dir = logDir ?: return
        val batch = ArrayList<String>()
        while (true) {
            val line = pending.poll() ?: break
            batch.add(line)
            if (batch.size >= 500) break
        }
        if (batch.isEmpty()) return
        runCatching {
            rotateIfNeeded(dir)
            val f = current ?: File(dir, BASE_NAME).also { current = it }
            f.appendText(batch.joinToString(separator = ""))
            pruneOldFiles(dir)
        }
    }

    /** 到上限就轮转：`wotagei.log` → `wotagei.log.1` → …，超保留数的最老一件删掉 */
    private fun rotateIfNeeded(dir: File) {
        val f = current ?: return
        if (!diagShouldRotate(if (f.isFile) f.length() else 0L)) return
        // 从老到新依次后移：.1 → .2（最老一件先删）
        val oldest = File(dir, diagRotatedName(BASE_NAME, DIAG_FILE_KEEP - 1))
        if (oldest.isFile) oldest.delete()
        for (i in DIAG_FILE_KEEP - 2 downTo 1) {
            val from = File(dir, diagRotatedName(BASE_NAME, i))
            if (from.isFile) from.renameTo(File(dir, diagRotatedName(BASE_NAME, i + 1)))
        }
        f.renameTo(File(dir, diagRotatedName(BASE_NAME, 1)))
    }

    /** 顺带清掉过期的崩溃件（只留最近 3 份，避免导出越攒越大） */
    private fun pruneOldFiles(dir: File) {
        val now = System.currentTimeMillis()
        if (now - lastPruneMs < 60_000L) return
        lastPruneMs = now
        runCatching {
            dir.listFiles { f -> f.name.startsWith(CRASH_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { it.delete() }
        }
    }

    /**
     * 崩溃捕获：把「设备信息 + 环形缓冲尾部 + 堆栈」同步写进 `crash-<时间戳>.txt`，
     * 然后**照旧交给上一个处理器**（系统默认行为/其它 SDK 的处理都不改）。
     */
    private fun installCrashHandler(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, tr ->
            runCatching {
                val dir = logDir
                if (dir != null) {
                    val text = buildString {
                        append(diagHeader(deviceSummary(app), app.packageName))
                        append("\n=== 崩溃 ===\n线程：${thread.name}\n")
                        append(Diag.getStackTraceString(tr))
                        append("\n=== 崩溃前日志（末 ${DIAG_CRASH_TAIL_LINES} 行）===\n")
                        synchronized(lock) { ring.toList().takeLast(DIAG_CRASH_TAIL_LINES) }
                            .forEach { append(it) }
                    }
                    File(dir, diagCrashName(diagFileStamp(System.currentTimeMillis()))).writeText(text)
                    drainToFile()
                }
            }
            if (prev != null) prev.uncaughtException(thread, tr)
        }
    }

    /** 会话收尾留痕：下次冷启能看到"上一会话结束于"（区分"被杀"与"正常退出"） */
    fun markCleanExit() {
        runCatching {
            appRef?.getSharedPreferences("wota_diag", Context.MODE_PRIVATE)
                ?.edit()?.putString("last_session", diagStamp(System.currentTimeMillis()))?.apply()
        }
    }

    // endregion

    // region 记录入口（签名与 android.util.Log 对齐，供 import 别名转发）

    fun v(tag: String, msg: String) = record('V', tag, msg, null)
    fun d(tag: String, msg: String) = record('D', tag, msg, null)
    fun i(tag: String, msg: String) = record('I', tag, msg, null)
    fun w(tag: String, msg: String) = record('W', tag, msg, null)
    fun e(tag: String, msg: String) = record('E', tag, msg, null)

    fun v(tag: String, msg: String, tr: Throwable) = record('V', tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable) = record('D', tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable) = record('I', tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable) = record('W', tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable) = record('E', tag, msg, tr)

    fun getStackTraceString(t: Throwable?): String =
        if (t == null) "" else Log.getStackTraceString(t)

    /**
     * 与平台同义（由系统属性 `log.tag.<TAG>` 决定）。**故意不改成恒 true**：
     * 调用点用它包"很啰嗦、平时不想开"的调试段，替它做决定会把这些段变成常开、日志量失控。
     */
    fun isLoggable(tag: String, level: Int): Boolean = Log.isLoggable(tag, level)

    private fun record(level: Char, tag: String, msg: String, tr: Throwable?) {
        runCatching {
            if (!installed.get()) {
                // 未装（单测/JVM）时保持旧行为：仍旧只走系统日志
                when (level) {
                    'V' -> Log.v(tag, msg)
                    'D' -> Log.d(tag, msg)
                    'I' -> Log.i(tag, msg)
                    'W' -> Log.w(tag, msg)
                    else -> Log.e(tag, msg)
                }
                return
            }
            if (!diagLevelEnabled(level, verbose)) return
            val line = diagLine(
                stamp = diagStamp(System.currentTimeMillis()),
                level = level, tag = tag,
                thread = Thread.currentThread().name,
                msg = if (tr == null) msg else "$msg | ${Log.getStackTraceString(tr)}"
            )
            synchronized(lock) {
                ring.addLast(line)
                while (ring.size > DIAG_RING_LINES) ring.removeFirst()
            }
            pending.add(line)
        }
    }

    // endregion

    // region 导出

    /** 导出结果：文件 + 是否落在公共可见目录（false = 退化到应用私有目录） */
    data class ExportResult(val file: File, val inPublicDir: Boolean)

    /**
     * 把全部日志（轮转件 + 当前件 + 崩溃件 + 头部信息）合并导出成一份 txt 落到**公共**目录
     * `Documents/WotaGeiCam/`（写不进去退应用私有目录）。**只写文件、不弹分享**——
     * 要给用户"文件在这儿、留着/自己找"这条路；一步直达分享走 [exportForShare]。
     * **必须在后台线程调用**（会读多个文件、写公共目录）。
     */
    fun export(app: Application): ExportResult? = runCatching {
        val name = diagExportName(diagFileStamp(System.currentTimeMillis()))
        val publicDir = File(Environment.getExternalStorageDirectory(), "Documents/$EXPORT_DIR_NAME")
        val dir = logDir
        val target = if (runCatching { publicDir.mkdirs() }.getOrDefault(false) || publicDir.isDirectory) {
            File(publicDir, name)
        } else if (dir != null) {
            File(dir, name)
        } else {
            return@runCatching null
        }
        target.writeText(buildReport(app))
        val res = ExportResult(target, target.parentFile == publicDir)
        i("WotaDiag", "日志已导出 path=${target.absolutePath} bytes=${target.length()} 公共=${res.inPublicDir}")
        res
    }.onFailure { e("WotaDiag", "日志导出失败", it) }.getOrNull()

    /**
     * 生成一份**专供分享**的报告（2026-10-09 用户追加：「不用去文件管理器找文件，直接分享」）。
     *
     * 落点用**应用缓存目录**（`cacheDir/…`）而不是公共 Documents，两个理由：
     * 1. 分享这条路**不该被"公共目录能不能写"卡住**（个别 ROM/容器不给 Documents 写权限，
     *    而缓存目录永远可写）——用户点「分享」就必须弹出分享面板；
     * 2. `@xml/file_paths` 里 `cache-path path="."` 已覆盖它，`FileProvider` 直接可授 URI。
     * 缓存件会被系统在空间紧张时自动清掉，正合"分享完就没用了"的语义。
     */
    fun exportForShare(app: Application): ExportResult? = runCatching {
        val dir = File(app.cacheDir, "share-out").apply { mkdirs() }
        // 清掉上次的分享件，避免缓存目录里越攒越多（分享件是一次性的）
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        val target = File(dir, diagExportName(diagFileStamp(System.currentTimeMillis())))
        target.writeText(buildReport(app))
        i("WotaDiag", "日志已导出 path=${target.absolutePath} bytes=${target.length()} 公共=${false}（分享件）")
        ExportResult(target, inPublicDir = false)
    }.onFailure { e("WotaDiag", "日志导出失败", it) }.getOrNull()

    /** 报告正文：头部 + 崩溃件（新→旧）+ 日志件（老→新），总量受 [DIAG_EXPORT_MAX_BYTES] 约束 */
    private fun buildReport(app: Application): String {
        drainToFile()
        val dir = logDir
        val body = StringBuilder()
        body.append(diagHeader(deviceSummary(app), app.packageName))
        // 崩溃件（新→旧）：最常被问到的就是"崩的时候发生了什么"
        val crashes = dir?.listFiles { f -> f.name.startsWith(CRASH_PREFIX) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        for (c in crashes) {
            body.append("\n=== 崩溃件 ${c.name} ===\n")
            body.append(readBounded(c, DIAG_EXPORT_MAX_BYTES - body.length))
            if (body.length >= DIAG_EXPORT_MAX_BYTES) break
        }
        // 日志：老 → 新（.2 .1 当前），读起来就是时间顺序
        val logs = ArrayList<File>()
        if (dir != null) {
            for (i in DIAG_FILE_KEEP - 1 downTo 1) {
                val f = File(dir, diagRotatedName(BASE_NAME, i))
                if (f.isFile) logs.add(f)
            }
        }
        current?.takeIf { it.isFile }?.let { logs.add(it) }
        for (f in logs) {
            if (body.length >= DIAG_EXPORT_MAX_BYTES) break
            body.append("\n=== 日志件 ${f.name} ===\n")
            body.append(readBounded(f, DIAG_EXPORT_MAX_BYTES - body.length))
        }
        // 尾标记：导出件被截断时，只有头部的行尾没有"结束"可对——用户/我们判断"这份报告完整吗"
        // 只能猜。这里补一行行数/字节数/时间，风格与 diagHeader 的 === 段一致
        body.append(
            "=== 结束 行数=${body.lineSequence().count()} 字节=${body.length} " +
                "时间=${diagStamp(System.currentTimeMillis())} ===\n"
        )
        return body.toString()
    }

    /** 导出件与工作日志的目录（设置页显示用） */
    fun workingDir(): File? = logDir

    /** 当前日志占用（设置页显示用） */
    fun usedBytes(): Long = runCatching {
        val dir = logDir ?: return 0L
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    private fun readBounded(f: File, budget: Long): String {
        val max = budget.coerceAtLeast(0L).coerceAtMost(DIAG_EXPORT_MAX_BYTES)
        if (max <= 0L) return "（已达导出上限，省略）\n"
        return runCatching {
            val len = f.length()
            // 截断与读取失败原来都只留一行占位文本，导出件里看不出"丢了多少/为什么丢"——自记一条
            if (len <= max) f.readText() else {
                i("WotaDiag", "日志导出截断：件 ${f.name} 总长${len}B 预算${max}B，只带尾部 ${max}B")
                f.readText().takeLast(max.toInt())
            }
        }.getOrElse { tr ->
            e("WotaDiag", "日志导出读取失败 件 ${f.name} 预算${max}B", tr)
            "（读取失败）\n"
        }
    }

    // endregion

    /** 设备与版本摘要（导出与崩溃件的头一段）：机型/系统/容器差异一眼可辨 */
    private fun deviceSummary(app: Application): String = buildString {
        append("应用=${app.packageName} 版本=${appVersionName()} (code ${appVersionCode()})\n")
        append("系统=Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ")
        append("品牌=${Build.BRAND} 厂商=${Build.MANUFACTURER} 机型=${Build.MODEL} 设备=${Build.DEVICE}\n")
        append("硬件=${Build.HARDWARE} 主板=${Build.BOARD} 指纹=${Build.FINGERPRINT}\n")
        append("ABI=${Build.SUPPORTED_ABIS.joinToString(",")}\n")
        append("时间=${diagStamp(System.currentTimeMillis())} 时区=${java.util.TimeZone.getDefault().id}\n")
    }

    private fun appVersionName(): String = runCatching {
        appRef!!.packageManager.getPackageInfo(appRef!!.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun appVersionCode(): Long = runCatching {
        val ai = appRef!!.packageManager.getPackageInfo(appRef!!.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ai.longVersionCode else @Suppress("DEPRECATION") ai.versionCode.toLong()
    }.getOrDefault(0L)
}

// ------------------------------------------------------------------ 纯函数（全表测试在 DiagFormatTest）

/** 行头时间戳：`MM-dd HH:mm:ss.SSS`（本地时区，够读且不拖长度） */
internal fun diagStamp(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))

/** 文件名里的时间戳：`yyyyMMdd-HHmmss`（文件名不能带冒号，Android/Windows 都不认） */
internal fun diagFileStamp(epochMs: Long): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(epochMs))

/** 日志行：时间 级别 TAG [线程] 正文（正文自带换行的会被压成一行，保证"一行一条"） */
internal fun diagLine(stamp: String, level: Char, tag: String, thread: String, msg: String): String =
    "$stamp $level $tag [$thread] ${msg.replace('\n', '⏎')}\n"

/**
 * 该级别在当前档位下要不要记：默认档只记 `i/w/e`（关键事件），详细档才带 `d/v`。
 * 未知级别按要记处理（宁可多记，别把新增级别悄悄丢了）。
 */
internal fun diagLevelEnabled(level: Char, verbose: Boolean): Boolean = when (level) {
    'V', 'D' -> verbose
    else -> true
}

/** 当前文件是否该轮转 */
internal fun diagShouldRotate(currentBytes: Long, max: Long = Diag.DIAG_FILE_MAX_BYTES): Boolean =
    currentBytes >= max

/** 轮转件名：`wotagei.log.1`（index 从 1 起） */
internal fun diagRotatedName(base: String, index: Int): String = "$base.$index"

/** 导出件名 */
internal fun diagExportName(stamp: String): String = "wotagei-log-$stamp.txt"

/** 崩溃件名 */
internal fun diagCrashName(stamp: String): String = "crash-$stamp.txt"

/**
 * 导出件头一段（纯函数，便于测试与复用）：设备信息 + 用途说明 + 隐私声明。
 * [device] 与 [pkg] 由调用方注入（纯函数不碰 Android API）。
 */
internal fun diagHeader(device: String, pkg: String): String = buildString {
    append("WotaGeiCam 诊断日志\n")
    append("==================\n")
    append("用途：这份文件用于把问题现场交给开发者定位（功能异常、被系统拒绝、崩溃等）。\n")
    append("隐私：仅本机生成、由你主动导出，不含画面与声音内容，也不会上传。\n\n")
    append(device)
    append("\n")
}
