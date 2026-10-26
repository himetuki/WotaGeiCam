package com.wotagei.cam.diag

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.wotagei.cam.core.mediaAccessLabel
import com.wotagei.cam.core.mediaAccessState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 * - **总开关默认关（2026-10-13 用户裁决：避免不必要的空间占用）**：关着时常规记录（i/w/e/d/v）一行都不写；
 *   开启后环形缓冲（内存 [DIAG_RING_LINES] 行）+ 后台线程落盘，单文件滚到
 *   [DIAG_FILE_MAX_BYTES] 就轮转，最多留 [DIAG_FILE_KEEP] 个 ⇒ 常驻占用个位数 MB 以内。
 *   之所以保留"边用边记"而不是"崩溃时才写"：本类要治的是**功能被系统拒绝**这类**不崩溃**的问题——
 *   崩溃日志帮不上忙，必须留得住事发前后的上下文。
 *   **代价要如实说**：默认关着时功能异常不会留下现场，出了问题必须先开日志再复现才能拿到证据。
 * - **崩溃件不受总开关影响**：进程要死时同步直写 `crash-*.txt`（见 [installCrashHandler]，不走
 *   [record] 的开关门）——崩溃取证量小、价值极高、零常态占用，不许被开关卡住。
 * - **详细档（用户可开）**：总开关开着时默认只记 `i/w/e`（关键事件），详细档开启后连 `d/v` 一起记。
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

    /**
     * 待落盘队列行数上限（2026-10-10 r31·S2-M8-02）。
     *
     * 写盘线程每 1 秒醒一次、一次最多搬 500 行；一旦它被系统 IO 卡住（慢盘、
     * 大目录、空间紧张），[pending] 就是**只进不出**的无界队列，每行几百字节，几十秒就能吃掉几十 MB
     * ——日志把 app 的内存吃穿，比丢日志严重得多。所以必须封顶：超了就丢**最老**的。
     * 丢掉的条数记在 [droppedPending] 里并写进导出件头部（见 [diagDroppedNote]），否则用户会把
     * "这段时间现场不全"误当成"本来就没发生"。
     */
    internal const val DIAG_PENDING_MAX_LINES = 3_000

    /** 单个日志文件上限，超过即轮转 */
    internal const val DIAG_FILE_MAX_BYTES = 512L * 1024L

    /** 轮转文件保留个数（含当前文件 ⇒ 磁盘占用上限 = 上限 × 个数） */
    internal const val DIAG_FILE_KEEP = 3

    /** 导出件最多带多少字节（防手滑把几十 MB 发出去） */
    internal const val DIAG_EXPORT_MAX_BYTES = 2L * 1024L * 1024L

    /** 崩溃件里带多少行上下文 */
    internal const val DIAG_CRASH_TAIL_LINES = 400

    /** 结构帧账最多留多少行（段收尾一行 + 会话清单一行；64 段≈19KB 量级，远小于导出上限） */
    internal const val FRAME_ACCOUNT_MAX_LINES = 64

    private const val BASE_NAME = "wotagei.log"
    private const val CRASH_PREFIX = "crash-"
    private const val EXPORT_PREFIX = "wotagei-log-"
    private const val LOG_DIR_NAME = "logs"
    private const val EXPORT_DIR_NAME = "WotaGeiCam"

    private val lock = Any()

    /** 落盘/清空的互斥锁：写盘线程每拍 [drainToFile] 与用户点「清除」都抢它，清空才不会被在途批复活 */
    private val drainLock = Any()
    private val ring = ArrayDeque<String>()
    private val pending = ConcurrentLinkedQueue<String>()
    private val installed = AtomicBoolean(false)

    /** 详细档：开启后 `d/v` 也记（用户复现问题时用，持久化由设置页负责） */
    @Volatile
    var verbose: Boolean = false
        private set

    /**
     * 总开关：**默认关**（2026-10-13 用户裁决）。关 = 常规记录（i/w/e/d/v）一行都不写；
     * **崩溃件不归它管**（取证不许被开关卡住，见 [installCrashHandler]）。持久化由设置页负责。
     */
    @Volatile
    var enabled: Boolean = false
        private set

    // 以下五枚字段都被"主线程 / 写盘线程 / 崩溃线程 / 导出线程"交叉读写（2026-10-10 r31·S2-M8-01）。
    // 漏 @Volatile 的真实后果不是"读到旧值一次"，而是**永久卡在旧值**：主线程 install() 刚写完 appRef，
    // 崩溃线程可能一直读到 null ⇒ 崩溃件整个不落盘；current/logDir 同理会让导出件永远少一个最新日志件。
    // lastPruneMs 是"读-改-写"（先 get 再 set），@Volatile 只保证可见不保证原子 ⇒ 必须用 AtomicLong。
    @Volatile
    private var appRef: Application? = null

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var current: File? = null

    @Volatile
    private var writer: Thread? = null

    private val lastPruneMs = AtomicLong(0L)

    /** 待落盘队列被封顶丢掉的总行数（导出件头部要自曝，见 [diagDroppedNote]） */
    private val droppedPending = AtomicLong(0L)

    /**
     * **结构帧账**（2026-10-17）：录制段收尾的"相机→GL→编码器→muxer"一行汇总 + 会话段清单。
     *
     * 为什么不走 [record] 的总开关：它是**结构化诊断数据**，用户点「导出日志」时应当看到
     * 帧账，即使常规日志总开关处于默认关（[enabled] 默认 false 的裁决不变）。写入点只在
     * 段收尾/会话收尾（分钟级），不在逐帧热路径；容量封顶 [FRAME_ACCOUNT_MAX_LINES] 行。
     */
    private val frameAccountLines = ArrayDeque<String>()
    private val frameAccountLock = Any()

    /** 追加一行结构帧账（线程安全；超出上限丢最老的一行） */
    fun frameAccount(line: String) {
        synchronized(frameAccountLock) {
            frameAccountLines.addLast(line)
            while (frameAccountLines.size > FRAME_ACCOUNT_MAX_LINES) frameAccountLines.removeFirst()
        }
    }

    /** 新录制会话开始时清空帧账（只清结构段，不动常规日志环形缓冲） */
    fun clearFrameAccount() {
        synchronized(frameAccountLock) { frameAccountLines.clear() }
    }

    private fun frameAccountSnapshot(): List<String> =
        synchronized(frameAccountLock) { frameAccountLines.toList() }

    // region 生命周期

    /**
     * 装日志（冷启调一次；重复调用只更新 [enabled] / [verbose]）。**必须在写第一条日志之前调用**才能收全上下文。
     * @param enabled 总开关初值（设置页持久化的那份；默认关）
     * @param verbose 详细档初值（设置页持久化的那份）
     */
    fun install(app: Application, verbose: Boolean = false, enabled: Boolean = false) {
        this.enabled = enabled
        this.verbose = verbose
        if (!installed.compareAndSet(false, true)) return
        appRef = app
        // 崩溃处理器仍同步装（纯内存注册）：取证窗口不后移。logDir 未就绪的窗口期由
        // handler 内部的 filesDir 兜底（见 installCrashHandler），崩溃件不丢。
        installCrashHandler(app)
        // 磁盘初始化挪到后台线程（StrictMode P2，2026-10-24）：getExternalFilesDir 的目录
        // 存在性检查 + mkdirs + "wota_diag" prefs 读原先全在主线程冷启路径（DiskReadViolation ×5）。
        // 时序安全性：writer 未起时 record() 只进内存队列（drainToFile 对 logDir==null 幂等），
        // 目录建好后写盘线程一拍补落；@Volatile 字段本就按跨线程口径声明（见类注 S2-M8-01）。
        Thread({
            runCatching {
                logDir = File(app.getExternalFilesDir(null), LOG_DIR_NAME).apply { mkdirs() }
                current = File(logDir, BASE_NAME)
                startWriter()
                val prev = app.getSharedPreferences("wota_diag", Context.MODE_PRIVATE)
                    .getString("last_session", null)
                i("WotaDiag", "日志启动：上一会话结束于 ${prev ?: "（无记录）"}")
            }
            i("WotaDiag", "设备与版本：${deviceSummary(app)}")
        }, "wota-diag-init").apply { isDaemon = true; start() }
    }

    /** 用户切详细档（设置页调用；只影响之后的记录） */
    fun setVerbose(on: Boolean) {
        verbose = on
        i("WotaDiag", if (on) "详细日志已开启（含 d/v 级）" else "详细日志已关闭（只记 i/w/e）")
    }

    /**
     * 用户切总开关（设置页调用）。开/关各写一条状态行，且**关的那条必须在置 false 之前写**——
     * 否则会被自己的门拦掉；告别行让导出件能看出"从哪一刻起没现场了"。
     * 崩溃件不归本开关管（语义见类头），所以告别行写明"崩溃件除外"。
     */
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        if (on) {
            enabled = true
            i("WotaDiag", "日志已开启")
        } else {
            i("WotaDiag", "日志已关闭（此后不记录，崩溃件除外）")
            enabled = false
        }
    }

    /**
     * 清空全部诊断日志（设置页「清除日志」调用；**必须在后台线程调用**）：
     * 删掉日志目录下当前件 + 轮转件 + 崩溃件（删除判据在纯函数 [diagClearLogFiles]，JVM 可测），
     * 清空内存环形缓冲与待落盘队列，重置丢弃/巡检计数（轮转天然随文件删除归零）。
     * 收尾那句「日志已清除」走常规记录门：总开关开着才落盘；关着就"清了就是清了"。
     */
    fun clear() = runCatching {
        synchronized(drainLock) {
            // 在途的一拍先落盘再一并删掉；随后清队列——写盘线程与本函数同抢 [drainLock]，
            // 不会出现"删完又被删前捞走的旧批复活"（新记录进来的行是清后新日志，复活无妨）
            drainToFile()
            pending.clear()
            val removed = logDir?.let { diagClearLogFiles(it, BASE_NAME, CRASH_PREFIX) } ?: 0
            synchronized(lock) { ring.clear() }
            droppedPending.set(0L)
            lastPruneMs.set(0L)
            i("WotaDiag", "日志已清除 件数=$removed")
        }
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
        synchronized(drainLock) {
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
        if (now - lastPruneMs.get() < 60_000L) return
        lastPruneMs.set(now)
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
                // logDir 未就绪的窗口期（后台 init 线程还在跑，见 install）不许丢崩溃件：
                // 兜底写内部 files 目录（构造路径无磁盘存在性检查，崩溃线程上也没有 StrictMode 顾虑），
                // 代价是窗口期崩溃件落在私有目录而不在导出目录——比整个不落盘好。
                val dir = logDir ?: File(app.filesDir, LOG_DIR_NAME).apply { runCatching { mkdirs() } }
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
                // 未装（单测/JVM）时保持旧行为：只走系统日志
                when (level) {
                    'V' -> Log.v(tag, msg)
                    'D' -> Log.d(tag, msg)
                    'I' -> Log.i(tag, msg)
                    'W' -> Log.w(tag, msg)
                    else -> Log.e(tag, msg)
                }
                return
            }
            // ① logcat：**不看总开关**（2026-10-26 拆闸，理由见 [diagRecordGated] 的说明）。
            //    真机排查靠的就是这一路——改前默认关 = 连 logcat 都拿不到一行，现场只能靠用户口述。
            //    d/v 仍受详细档：逐帧日志会把系统环形缓冲冲掉，反而看不到关键事件。
            if (!diagLogcatGated(level, verbose)) {
                val text = if (tr == null) msg else "$msg | ${Log.getStackTraceString(tr)}"
                when (level) {
                    'V' -> Log.v(tag, text)
                    'D' -> Log.d(tag, text)
                    'I' -> Log.i(tag, text)
                    'W' -> Log.w(tag, text)
                    else -> Log.e(tag, text)
                }
            }
            // ② 文件落盘：仍受总开关（默认关 = 不占用户存储，2026-10-13 裁决不变）
            //    崩溃件不走这里（见 installCrashHandler 同步直写）——崩溃取证不许被开关卡住。
            if (diagRecordGated(level, enabled, verbose)) return
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
            // 队列必须封顶：写盘线程被系统 IO 卡住时这里只进不出（见 DIAG_PENDING_MAX_LINES 的病根说明）
            dropOldestPending()
        }
    }

    /** 待落盘队列封顶处理：超 [DIAG_PENDING_MAX_LINES] 就丢最老的，并累加丢弃条数 */
    private fun dropOldestPending() {
        val drop = diagPendingOverflow(pending.size, DIAG_PENDING_MAX_LINES)
        for (i in 0 until drop) if (pending.poll() != null) droppedPending.incrementAndGet()
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
     * 2. `@xml/file_paths` 里的 `cache-path path="share-out"` 已覆盖它，`FileProvider` 直接可授 URI。
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
        // 丢弃自曝紧跟头部：用户读导出件是从上往下的，等翻到末尾才发现前面缺了一整段，现场早没了
        body.append(diagDroppedNote(droppedPending.get()))
        // 结构帧账：与常规日志开关无关（见 [frameAccount] KDoc），放在日志件之前先给结论
        body.append(diagFrameAccountSection(frameAccountSnapshot()))
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
        // 媒体读取权限的授予状态：对比页选片失败、媒体库缺片这类问题的第一判据。
        // 只能查权限本身——本应用自录的行在「部分授权」下同样可见，看列表内容判断不了。
        append("媒体权限=${mediaAccessLabel(mediaAccessState(app))}\n")
        append("时间=${diagStamp(System.currentTimeMillis())} 时区=${java.util.TimeZone.getDefault().id}\n")
    }

    private fun appVersionName(): String = runCatching {
        appRef!!.packageManager.getPackageInfo(appRef!!.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /**
     * 版本号取 [android.content.pm.PackageInfo.longVersionCode]。
     *
     * 本工程 minSdk 29 ≥ 28（`longVersionCode` 自 API 28 起可用），所以不需要版本分支：
     * 原来的 `SDK_INT >= VERSION_CODES.P` 是**恒真**判断，Lint 报 `ObsoleteSdkInt`
     * （r31 十条 warning 里唯一由本轮引入的那条）。`runCatching` 外壳保留：
     * 拿不到 PackageInfo 时仍回 0L，不许让设备摘要整段炸掉。
     */
    private fun appVersionCode(): Long = runCatching {
        appRef!!.packageManager.getPackageInfo(appRef!!.packageName, 0).longVersionCode
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

/**
 * 常规记录的两道闸拆开：**[diagRecordGated] 管"要不要落盘"**，**[diagLogcatGated] 管"要不要进 logcat"**。
 *
 * 为什么必须拆（2026-10-26 真机取证踩到的）：
 * 改前总开关默认关 ⇒ `record()` 第一句就 return，于是 **logcat 里我们自己的 tag 一行都没有**。
 * 在 WIKO / 华为这类容器上真机排查"帧率到底下发了什么"时，用户手上没有任何可看的东西，
 * 只能靠反复描述现象——日志系统本来是为了省几 MB 磁盘，结果把最便宜的取证通道也一起关了。
 * 而 logcat 的环形缓冲由**系统**管理（按 tid/tag 定额，应用写多少都不会落进自己的存储），
 * 成本约等于零 ⇒ 没有理由受"省空间"这条约束。
 *
 * 现在的口径：
 * - **logcat：恒开**（i/w/e 恒写；d/v 仍受详细档，避免逐帧日志把系统缓冲冲掉）；
 * - **文件落盘：仍受总开关**（默认关，省空间这条裁决不变）；
 * - **崩溃件：两条路都不经过**（同步直写，取证语义不变）。
 */
internal fun diagRecordGated(level: Char, enabled: Boolean, verbose: Boolean): Boolean {
    if (!enabled) return true
    return !diagLevelEnabled(level, verbose)
}

/**
 * logcat 这一路要不要写：**不看总开关**，只看级别（详细档决定 d/v）。
 * 见 [diagRecordGated] 的说明——这条是把"取证通道"与"磁盘占用"解耦的地方。
 */
internal fun diagLogcatGated(level: Char, verbose: Boolean): Boolean = !diagLevelEnabled(level, verbose)

/**
 * 「清除日志」的删除判据（纯 JVM，只碰 java.io）：删掉目录下**本系统**写的全部日志件——
 * 当前件（[baseName] 精确名）、轮转件（`baseName.N`）、崩溃件（[crashPrefix] 前缀）；
 * 目录里的无关文件（如私有目录兜底的导出件）一律不动。返回实际删掉的件数。
 */
internal fun diagClearLogFiles(dir: File, baseName: String, crashPrefix: String): Int {
    if (!dir.isDirectory) return 0
    var removed = 0
    dir.listFiles()?.forEach { f ->
        val hit = f.isFile && (
            f.name == baseName || f.name.startsWith("$baseName.") || f.name.startsWith(crashPrefix)
            )
        if (hit && f.delete()) removed++
    }
    return removed
}

/** 当前文件是否该轮转 */
internal fun diagShouldRotate(currentBytes: Long, max: Long = Diag.DIAG_FILE_MAX_BYTES): Boolean =
    currentBytes >= max

/**
 * 待落盘队列当前[size]、上限[max]时要丢几行（丢**最老**的）；未超限返回 0。
 *
 * 纯函数便于表测，也便于 [Diag] 里那条调用点一眼看出"丢多少"是算出来的而非拍脑袋的。
 */
internal fun diagPendingOverflow(size: Int, max: Int): Int = (size - max).coerceAtLeast(0)

/**
 * 导出件头部的**丢弃自曝行**（2026-10-10 r31·S2-M8-02）。
 *
 * 没有它，被封顶丢掉的行就是**静默消失**——用户会把"这段时间没记录"当成"什么都没发生"，
 * 而丢的那些恰恰是崩溃/被系统拒绝发生前的最早几行。丢过才占一行；没丢过返回空串，
 * 保证正常路径的导出行数与之前完全一致。
 */
internal fun diagDroppedNote(dropped: Long): String =
    if (dropped <= 0L) ""
    else "注意：待落盘队列超 ${Diag.DIAG_PENDING_MAX_LINES} 行封顶，有 $dropped 行未落盘（丢的是最老的）。\n"

/**
 * 导出件里的**结构帧账**段（纯函数，便于 JVM 断言"行真的进正文"）。
 *
 * 空表也留一行占位说明：用户导出后看不到这一段时，能区分"本次没录/没到段尾"与
 * "导出逻辑把帧账丢了"。段名与首尾标记同 `diagHeader` 的 `===` 风格。
 */
internal fun diagFrameAccountSection(lines: List<String>): String = buildString {
    append("\n=== 录制帧账 ===\n")
    if (lines.isEmpty()) {
        append("（本次会话没有段收尾帧账：未录制或尚未到段尾）\n")
    } else {
        lines.forEach { append(it).append('\n') }
    }
    append("=== 录制帧账结束 ===\n")
}

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
