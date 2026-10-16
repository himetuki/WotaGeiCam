package com.wotagei.cam.record

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import com.wotagei.cam.diag.Diag as Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 冷启维护清扫：把历史遗留的 `.pending-<id>-` 条目收干净（2026-10-08）。
 *
 * # 为什么需要它
 * `MediaStore` 的 pending 临时段有三条历史来源：①命名 race（r16 之前，临时名被烙进成片名，如
 * `30fto24f_.pending-1791986302-VID_….mp4`——**这些是有效成片**，只是名字难看、在相册里也这么显示）；
 * ②insert 在飞时进程被杀（进程内的交接闸救不了跨进程死亡）；③sidecar 归位缺陷（r21 之前每会话一条
 * `.pending-<id>-….drops.json` 无主档案）。根因修完只保证**新产生的不再留残**，历史留下的得靠一支
 * 一次性清扫——就是本文件。
 *
 * # 安全边界（四道，缺一不可）
 * 1. **只认我们自己的命名形态**：名字里必须含 `.pending-<纯数字>-`（[PendingName.stripPendingJunk]
 *    同一套正则），别人的文件、普通名字一个都不碰；
 * 2. **年龄门槛 [SWEEP_STALE_MS]**：正在录的那一段是秒级的（冷启时也不该被动），1 小时门槛把它挡死；
 * 3. **进程内只跑一次**、只扫成片目录（[VideoStore.RELATIVE_PATH]），不做全盘遍历。
 * 4. **成片趟先探容器可播（Q6）**：名字与年龄都过门的成片，还要 MediaExtractor 打得开才允许去
 *    pending 段——探测复用导出链救援那把尺（`extractable()`，同一份数据不许第二把尺）。录制中
 *    被杀/断电留下的半截 MP4（没写成 moov、点开即坏）旧行为会被"洗白"成相册可见坏条目；现在
 *    一律一动不动（连名字都不动；marker 名留着，补入库趟的 `isOurClipName` 就扫不到它），
 *    只留一条 i 级日志供将来人工清理。档案趟**不做**探测（JSON 不是媒体容器）。
 * 另有一条**数据红线**：成片永不删除——剥段后同名的**成片**已存在时只记录不动手（宁可留一条难看名字，
 * 也绝不误删用户成片）；只有"无主/重复的 sidecar 档案"才允许删（它是纯档案，类注见 [ArcDropLog]）。
 *
 * #4 起新增 [rescuePendingRows]：导出链 commit 全败留下的 IS_PENDING=1 行的救援通道
 * （按行查、不认文件名，与上面的按文件清扫互不重叠；数据红线见其 KDoc）。
 */
internal object StalePendingSweep {

    private const val TAG = "WotaSweep"

    /** 年龄门槛：只处理"比这个还老"的条目（正在录的那一段是秒级，绝不可能入内） */
    internal const val SWEEP_STALE_MS = 60L * 60L * 1000L

    /** 单次扫描条目上限（目录可能很大，清扫不值得阻塞启动太久） */
    internal const val SWEEP_MAX_ENTRIES = 500

    /** 单轮"库一致性修补"（删坏行 + 补入库）上限：收敛于多轮，别让冷启扫全库 */
    internal const val SWEEP_MAX_INDEX = 40

    private val started = AtomicBoolean(false)

    /**
     * 进程内只跑一次。**必须在后台线程调用**：会查/改 MediaStore 与文件系统。
     * @return 实际收干净的条目数（0 = 没有遗留，或已跑过）
     */
    fun runOnce(app: Context, dir: File): Int {
        if (!started.compareAndSet(false, true)) return 0
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        val now = System.currentTimeMillis()
        // 两趟：**先成片、后档案**。档案的目标名取自成片真实名，而"成对带烂名"的那类（真机第③类）
        // 要等成片先改成干净名，档案才找得到它的成片；否则那一趟会把它当无主档案删掉（丢档案）。
        val stale = entries.take(SWEEP_MAX_ENTRIES).filter { PendingName.stripPendingJunk(it.name) != it.name }
        val (archives, clips) = stale.partition { it.name.endsWith(ArcDropLog.EXTENSION) }
        var acted = 0
        for (f in clips) {
            val clean = PendingName.stripPendingJunk(f.name)
            val ageMs = now - f.lastModified()
            val twin = File(dir, clean).exists()
            // Q6：改名**之前**先探容器可播（判据归一：复用导出链救援的 extractable()，同一份
            // 数据不许第二把尺）。打不开 = 半截 MP4（录制中被杀/断电，moov 没写成）——旧行为
            // 会照样去 pending 段把它"洗白"成相册里点开即坏的条目，现在连名字都不动。
            val playable = extractable(f)
            if (!playable && ageMs >= SWEEP_STALE_MS) {
                // 留据给将来人工清理（含条目名与大小）。保持 marker 名 = 不产生任何可见化：
                // 补入库趟的 isOurClipName 不认这种形态，扫不到它
                Log.i(TAG, "清扫：残片打不开，保持原状（未改名未登记）：${f.name} ${f.length() / 1_048_576}MB")
            }
            val plan = sweepPlan(f.name, ageMs, twin, null, clipPlayable = playable)
            if (apply(app, dir, f, plan)) acted++
        }
        for (f in archives) {
            val clean = PendingName.stripPendingJunk(f.name)
            // 档案趟不探容器（JSON 不是媒体容器）：clipPlayable 显式传 null = 未探，计划里档案
            // 分支也不读它——探测只属于成片趟这一条结构由 StalePendingSweepTest 钉住
            val plan = sweepPlan(
                f.name, now - f.lastModified(), File(dir, clean).exists(),
                clipFor(f.name, dir), clipPlayable = null
            )
            if (apply(app, dir, f, plan)) acted++
        }
        // 第三趟：**库一致性**。两条互补的修补（都是"上一条的必然结果"）：
        // ① 行指向的文件已不在（历史遗留；也包括早期版本清扫直接改了文件名的情形）⇒ 删行，别让相册
        //    留一条点开是坏的行；
        // ② 目录里有成片却查不到行 ⇒ 交系统扫描重新入库，否则这些成片在应用里根本看不见。
        // 单轮上限 [SWEEP_MAX_INDEX] 条（收敛于多轮，不阻塞启动）。
        val fixed = reconcileLibrary(app, dir, now)
        if (acted > 0 || fixed > 0) {
            Log.i(TAG, "维护清扫：收干净 $acted 条 pending 遗留、库一致性修补 $fixed 条（目录 ${dir.absolutePath}）")
        }
        return acted + fixed
    }

    /** 库一致性修补：删"行在文件不在"的坏行 + 给"有文件没行"的成片补一次扫描入库 */
    private fun reconcileLibrary(app: Context, dir: File, now: Long): Int {
        val rows = queryRows(app).orEmpty()
        var fixed = 0
        for (row in rows) {
            val path = row.dataPath ?: continue
            val ageMs = now - row.addedMs
            if (!File(path).exists() && ageMs >= SWEEP_STALE_MS) {
                if (deleteRow(app, row.id)) {
                    Log.i(TAG, "清扫：删坏行（文件已不在）${row.displayName}")
                    fixed++
                }
            }
        }
        val byName = rows.mapNotNull { it.displayName }.toHashSet()
        for (f in runCatching { dir.listFiles() }.getOrNull().orEmpty()) {
            if (fixed >= SWEEP_MAX_INDEX) break
            if (!isOurClipName(f.name) || byName.contains(f.name)) continue
            if (indexFile(app, f)) {
                Log.i(TAG, "清扫：补入库 ${f.name}")
                fixed++
            }
        }
        return fixed
    }

    /**
     * 是不是我们自己产的成片名（`VID_<时间戳>` 或带转换前缀的 `XXftoXXf_VID_…`）。
     * **不认 marker 形态**（前缀里带 `.pending-<数字>-` 段的都判 false）——Q6 后这是"不可播残片
     * 保持原状就不会被补入库趟洗白"的承重判据，单独表测在 StalePendingSweepTest（故 internal）。
     */
    internal fun isOurClipName(name: String): Boolean =
        name.endsWith(".mp4") && name.contains("VID_") &&
            name.substringBefore("VID_").let { it.isEmpty() || it.matches(Regex("[0-9]+fto[0-9]+f_")) }

    private fun queryRows(app: Context): List<Row>? = runCatching {
        val out = ArrayList<Row>()
        app.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("${VideoStore.RELATIVE_PATH}/"),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                out += Row(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3) * 1000L)
            }
        }
        out
    }.getOrNull()

    private fun deleteRow(app: Context, id: Long): Boolean = runCatching {
        app.contentResolver.delete(
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), null, null
        ) > 0
    }.getOrDefault(false)

    /** 交系统扫描入库（`MediaScannerConnection` 是"文件已在磁盘、行还没有"的正解；不写文件） */
    private fun indexFile(app: Context, f: File): Boolean = runCatching {
        MediaScannerConnection.scanFile(app, arrayOf(f.absolutePath), null) { _, _ -> }
        true
    }.getOrDefault(false)

    /** 库行快照（[reconcileLibrary] 用） */
    private class Row(val id: Long, val displayName: String?, val dataPath: String?, val addedMs: Long)

    /**
     * 档案对应的成片文件名（null = 找不到）。名字形态**两种都要试**：真机的幽灵档案是
     * `…-VID_20261008_172951_262.drops.json`（脱离成片名时少了 `.mp4` 那一段），而正常档案是
     * `VID_….mp4.drops.json` ⇒ 先按"剥段去扩展名"原样找，再补 `.mp4` 找一次。
     */
    private fun clipFor(archiveName: String, dir: File): String? {
        val base = PendingName.stripPendingJunk(archiveName).removeSuffix(ArcDropLog.EXTENSION)
        if (base.isEmpty()) return null
        val direct = File(dir, base)
        if (direct.isFile) return direct.name
        val withExt = File(dir, "$base.mp4")
        if (withExt.isFile) return withExt.name
        return null
    }

    private fun apply(app: Context, dir: File, f: File, plan: SweepPlan): Boolean = runCatching {
        when (plan.action) {
            SweepAction.LEAVE -> false
            SweepAction.DELETE_ARCHIVE ->
                f.delete().also { if (it) Log.i(TAG, "清扫：删无主/重复档案 ${f.name}") }
            SweepAction.RENAME_ARCHIVE -> {
                val to = File(dir, plan.to ?: return@runCatching false)
                f.renameTo(to).also { if (it) Log.i(TAG, "清扫：档案归位 ${f.name} -> ${plan.to}") }
            }
            SweepAction.RENAME_CLIP -> {
                val to = File(dir, plan.to ?: return@runCatching false)
                // 【红线】有行的文件**只能**让 provider 改名：自己 rename 会让行里的 _data 指空，
                // 相册留下一条点开即坏的行（2026-10-08 真机实测：provider 那条路失败后回退 fs rename，
                // 10 条成片就这样变成了坏行）。所以：
                //   有行 ⇒ 走 provider，失败就**不动**（留原名，等下一轮/人工）；
                //   无行 ⇒ 本来就没人索引它，自己 rename 才是对的。
                if (rowExists(app, f.name)) {
                    if (!renameRow(app, f.name, to.name)) {
                        Log.w(TAG, "清扫：有行的成片 provider 改名失败，保持原名不动 ${f.name}")
                        return@runCatching false
                    }
                } else if (to != f && !f.renameTo(to)) {
                    return@runCatching false
                }
                // 同名侧车一起搬（复用成片改名的既有单一出口，命名规则不分叉）
                ArcDropLog.renameFor(f.absolutePath, to.absolutePath)
                Log.i(TAG, "清扫：成片去 pending 段 ${f.name} -> ${to.name}")
                true
            }
        }
    }.getOrDefault(false)

    /** 目录内是否已有这一行的成片（`renameRow` 的前置判据：**有行才必须走 provider**） */
    private fun rowExists(app: Context, name: String): Boolean = runCatching {
        app.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, "${VideoStore.RELATIVE_PATH}/"),
            null
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /**
     * 经 MediaStore 去掉 pending 段（顺带 `IS_PENDING=0` 兜一次：历史上"insert 在飞时被杀"的残留
     * 可能停在 pending 态，那样它在相册里永远不可见）。**目录内精确匹配**：`DISPLAY_NAME` 不唯一，
     * 必须叠 `RELATIVE_PATH`（Android 10+ 列）。
     */
    private fun renameRow(app: Context, oldName: String, newName: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        app.contentResolver.update(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(oldName, "${VideoStore.RELATIVE_PATH}/")
        ) > 0
    }.getOrDefault(false)

    /** 成片目录（公共 Movies 下的固定相对路径，同 [VideoStore.RELATIVE_PATH]） */
    @Suppress("DEPRECATION") // 公共目录定位：本工程持 MANAGE_EXTERNAL_STORAGE，走这条最稳
    fun outputDir(): File = File(Environment.getExternalStorageDirectory(), VideoStore.RELATIVE_PATH)

    /**
     * #4 导出链 pending 行救援：导出在 `muxer.stop()` 之后 commit 全败时**保留行不删**
     * （成片字节已在盘上，删行 = MediaStore 连带删文件 = 丢成片，与 VideoStore.seal 同口径），
     * 留下的 IS_PENDING=1 行对外不可见（相册隐藏）、本应用列表也不查（IS_PENDING=0 门），
     * 只有这里能在冷启把它们收进来。
     *
     * **目录参数由调用方（ui 层）传入**——record 包不得反向依赖 player 包，剪辑/选轨的
     * 相对路径（ClipRule.RELATIVE_PATH / TrackRule.RELATIVE_PATH）只能由上层喂进来。
     *
     * **数据红线（三条，缺一不可）**：
     * 1. 年龄 ≥ [SWEEP_STALE_MS]：在飞的导出是分钟级，绝不动；
     * 2. 「文件在盘」的行**永不删**：MediaExtractor 打得开且有轨 → 只 update 清 IS_PENDING
     *    （救援）；打不开 → Log.w 留原状（宁可留给下一轮/人工，也不猜）；
     * 3. 唯一删行分支在「文件确认缺失」：行指向的文件已不在，删坏行无数据可损失
     *    （复用 [deleteRow] 语义）。
     *
     * 单轮上限 [SWEEP_MAX_INDEX]（收敛于多轮，不阻塞启动）。
     * @return 实际救援/清理的行数
     */
    fun rescuePendingRows(app: Context, dirs: List<File>): Int {
        val now = System.currentTimeMillis()
        var acted = 0
        for (dir in dirs) {
            if (acted >= SWEEP_MAX_INDEX) break
            val relPath = relativePathOf(dir) ?: continue
            for (row in queryPendingRows(app, relPath)) {
                if (acted >= SWEEP_MAX_INDEX) break
                if (now - row.addedMs < SWEEP_STALE_MS) continue
                val f = row.dataPath?.let { File(it) }
                if (f == null || !f.exists()) {
                    // 唯一删行分支：文件确认缺失，删坏行（无数据可损失）
                    if (deleteRow(app, row.id)) {
                        Log.i(TAG, "救援：删坏行（文件已不在）${row.displayName}")
                        acted++
                    }
                } else if (extractable(f)) {
                    // 文件在盘且是完整合法的容器：只清 IS_PENDING 收进相册，绝不删
                    if (unpendRow(app, row.id)) {
                        Log.i(TAG, "救援：pending 成片收录 ${row.displayName}")
                        acted++
                    }
                } else {
                    // 打不开（半截文件/容器损坏）：留原状
                    Log.w(TAG, "救援：pending 文件打不开，留原状 ${row.displayName} path=${row.dataPath}")
                }
            }
        }
        if (acted > 0) {
            Log.i(TAG, "pending 行救援：本轮处置 $acted 条")
        }
        return acted
    }

    /** 查某相对路径下 owner 可见的 pending 行（IS_PENDING=1 对 owner 默认可见，只对外部 app 隐藏） */
    private fun queryPendingRows(app: Context, relPath: String): List<Row> = runCatching {
        val out = ArrayList<Row>()
        app.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATE_ADDED
            ),
            "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("$relPath/"),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                out += Row(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3) * 1000L)
            }
        }
        out
    }.getOrDefault(emptyList())

    /** 目录 File → MediaStore RELATIVE_PATH（与 [outputDir] 的构造互逆）；不在外部存储根下返回 null */
    private fun relativePathOf(dir: File): String? = runCatching {
        @Suppress("DEPRECATION")
        dir.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath
    }.getOrNull()

    /** 救援判据：MediaExtractor 打得开且有轨（只有完整合法的容器才值得清 IS_PENDING 收进相册） */
    private fun extractable(f: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            ex.trackCount > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    /** 救援动作本体：只 update 清 IS_PENDING，绝不 delete */
    private fun unpendRow(app: Context, id: Long): Boolean = runCatching {
        app.contentResolver.update(
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null
        ) > 0
    }.getOrDefault(false)
}

/** 清扫处置（见 [sweepPlan] 的表） */
internal enum class SweepAction { LEAVE, RENAME_CLIP, RENAME_ARCHIVE, DELETE_ARCHIVE }

/** @param to 目标**条目名**（不含目录）；LEAVE/DELETE 时为 null */
internal data class SweepPlan(val action: SweepAction, val to: String?)

/**
 * 逐条处置计划。**这是清扫的全部判断**（纯函数，全表测试在 `StalePendingSweepTest`）：
 *
 * | 形态 | 条件 | 处置 |
 * |---|---|---|
 * | 非 `pending` 形态 | 名字剥段后没变 | LEAVE（不是我们的东西） |
 * | 任何 | 比门槛新 | LEAVE（可能是正在录的那一段） |
 * | 侧车 `.drops.json` | 剥段后同名档案已在 | DELETE（重复档案，纯档案无数据损失） |
 * | 侧车 `.drops.json` | 对应成片找得到 | RENAME（归位到**成片真实名** + 扩展名） |
 * | 侧车 `.drops.json` | 对应成片也找不到 | DELETE（无主档案） |
 * | 成片 | 剥段后同名成片已在 | **LEAVE**（数据红线：成片绝不删） |
 * | 成片 | 容器打不开（或未探） | **LEAVE**（Q6：半截 MP4 一动不动，连名字都不动） |
 * | 成片 | 探过且打得开 | RENAME（去掉 pending 段） |
 *
 * 注意档案的**目标名取自成片真实名**（`clipName + ".drops.json"`），不是"剥段后的档案名"：真机实证
 * 那些幽灵档案的名字是 `…-VID_20261008_172951_262.drops.json`（**没有 `.mp4` 那一段**，因为当时落盘
 * 用的路径就少扩展名），而成片的规矩是 `成片路径 + .drops.json`（见 [ArcDropLog.sidecarFor]）——
 * 按"剥段后的档案名"摆回去，App 永远读不到它（[ArcDropLog.readFrom] 按成片路径查）。
 *
 * @param name 目录里的条目名
 * @param ageMs 条目距今毫秒（门槛见 [StalePendingSweep.SWEEP_STALE_MS]）
 * @param cleanTwinExists 剥段后的同名**条目**是否已在磁盘上
 * @param clipName 仅侧车用：该档案对应**已存在的成片文件名**（null = 找不到 ⇒ 无主档案）
 * @param clipPlayable 仅成片用：容器可播探测结果（[StalePendingSweep.extractable] 那把尺）。
 *   **null = 未探**（档案趟显式传 null；调用侧漏探也归此列）⇒ 按不可播处理；`RENAME_CLIP`
 *   只认"探过且可播"（== true）这一种正向确认——不可播/未确认的成片在任何组合下都到不了 RENAME_CLIP。
 */
internal fun sweepPlan(
    name: String,
    ageMs: Long,
    cleanTwinExists: Boolean,
    clipName: String?,
    clipPlayable: Boolean?
): SweepPlan {
    val clean = PendingName.stripPendingJunk(name)
    if (clean.isEmpty() || clean == name) return SweepPlan(SweepAction.LEAVE, null)
    if (ageMs < StalePendingSweep.SWEEP_STALE_MS) return SweepPlan(SweepAction.LEAVE, null)
    return if (clean.endsWith(ArcDropLog.EXTENSION)) {
        when {
            cleanTwinExists -> SweepPlan(SweepAction.DELETE_ARCHIVE, null)
            clipName != null -> SweepPlan(SweepAction.RENAME_ARCHIVE, clipName + ArcDropLog.EXTENSION)
            else -> SweepPlan(SweepAction.DELETE_ARCHIVE, null)
        }
    } else {
        when {
            cleanTwinExists -> SweepPlan(SweepAction.LEAVE, null)
            clipPlayable != true -> SweepPlan(SweepAction.LEAVE, null)
            else -> SweepPlan(SweepAction.RENAME_CLIP, clean)
        }
    }
}
