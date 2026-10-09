package com.wotagei.cam.record

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
 * # 安全边界（三道，缺一不可）
 * 1. **只认我们自己的命名形态**：名字里必须含 `.pending-<纯数字>-`（[PendingName.stripPendingJunk]
 *    同一套正则），别人的文件、普通名字一个都不碰；
 * 2. **年龄门槛 [SWEEP_STALE_MS]**：正在录的那一段是秒级的（冷启时也不该被动），1 小时门槛把它挡死；
 * 3. **进程内只跑一次**、只扫成片目录（[VideoStore.RELATIVE_PATH]），不做全盘遍历。
 * 另有一条**数据红线**：成片永不删除——剥段后同名的**成片**已存在时只记录不动手（宁可留一条难看名字，
 * 也绝不误删用户成片）；只有"无主/重复的 sidecar 档案"才允许删（它是纯档案，类注见 [ArcDropLog]）。
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
            val plan = sweepPlan(f.name, now - f.lastModified(), File(dir, clean).exists(), null)
            if (apply(app, dir, f, plan)) acted++
        }
        for (f in archives) {
            val clean = PendingName.stripPendingJunk(f.name)
            val plan = sweepPlan(f.name, now - f.lastModified(), File(dir, clean).exists(), clipFor(f.name, dir))
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

    /** 是不是我们自己产的成片名（`VID_<时间戳>` 或带转换前缀的 `XXftoXXf_VID_…`） */
    private fun isOurClipName(name: String): Boolean =
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
 * | 成片 | 其余 | RENAME（去掉 pending 段） |
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
 */
internal fun sweepPlan(
    name: String,
    ageMs: Long,
    cleanTwinExists: Boolean,
    clipName: String?
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
        if (cleanTwinExists) SweepPlan(SweepAction.LEAVE, null)
        else SweepPlan(SweepAction.RENAME_CLIP, clean)
    }
}
