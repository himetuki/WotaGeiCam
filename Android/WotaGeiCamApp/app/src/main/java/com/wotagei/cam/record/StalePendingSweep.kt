package com.wotagei.cam.record

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import com.wotagei.cam.diag.Diag as Log
import java.io.File
import java.io.FileNotFoundException
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

    /**
     * 清扫只认**主外部卷**：本包目录（[outputDir] 与上层喂进来的 externalVideoDir）全部挂在
     * `Environment.getExternalStorageDirectory()` 下。放到聚合集合上按名字查，SD 卡上同相对路径
     * 的同名文件会被当成"这一行的文件"——按名字改名/删行直接错卷（Y22）。
     */
    private val PRIMARY_VIDEO_URI: Uri
        get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** API 30+ 专用列；旧 Provider 遇到未知列会拒绝整条查询。 */
    private const val COL_OWNER = "owner_package_name"
    private const val COL_GENERATION = MediaStore.MediaColumns.GENERATION_MODIFIED

    /** 行快照基础列（[readRow] 的下标顺序即此顺序）：身份、路径、年龄、pending、卷 */
    private val BASE_ROW_COLUMNS = listOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.IS_PENDING,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED
    )

    /** API 29 不查询 owner 和修改代数，避免 Provider 拒绝整条查询。 */
    private val rowProjection: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (BASE_ROW_COLUMNS + COL_OWNER + COL_GENERATION).toTypedArray()
        } else {
            BASE_ROW_COLUMNS.toTypedArray()
        }

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
            // 探测受年龄门约束（2026-10-16 复核）：比 SWEEP_STALE_MS 新的条目计划内本就 LEAVE
            //（见 sweepPlan 的年龄门），没必要也不许去戳它（null = 未探，计划侧按不可播处理）
            val playable = if (ageMs >= SWEEP_STALE_MS) extractable(f) else null
            if (playable == false) {
                // 留据给将来人工清理（含条目名与大小）。保持 marker 名 = 不产生任何可见化：
                // 补入库趟的 isOurClipName 不认这种形态，扫不到它
                Log.i(TAG, "清扫：残片打不开，保持原状（未改名未登记）：${f.name} ${f.length() / 1_048_576}MB")
            }
            val plan = sweepPlan(f.name, ageMs, twin, null, clipPlayable = playable)
            if (apply(app, dir, f, plan)) acted++
        }
        for (f in archives) {
            val clipName = clipFor(f.name, dir)
            // 重复判据必须对**真实归位目标**（[ArcDropLog.sidecarName] 的规范名，即剥掉
            // `.mp4` 后的 `VID_x.drops.json`），不能只看"剥段后的档案名"：两者不一致时
            // 旧判据查不到目标已存在，`renameTo` 会把有效档案直接覆盖掉。目标已存在 ⇒ 删掉。
            // 名字必须走 sidecarName 单一出口——这里曾按 `成片名 + .drops.json` 拼成
            // `VID_x.mp4.drops.json`，与读取侧（readFrom 按成片路径查）永远对不上号（B04）。
            val twin = archiveTwinExists(f.name, clipName?.let { ArcDropLog.sidecarName(it) }, entries.map { it.name }.toHashSet())
            // 档案趟不探容器（JSON 不是媒体容器）：clipPlayable 显式传 null = 未探，计划里档案
            // 分支也不读它——探测只属于成片趟这一条结构由 StalePendingSweepTest 钉住
            val plan = sweepPlan(
                f.name, now - f.lastModified(), twin,
                clipName, clipPlayable = null
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

    /**
     * 库一致性修补：删"行在文件确认不在"的坏行 + 给"有文件没行"的成片补一次系统扫描入库。
     *
     * Y22：不再用 `File(path).exists()` 直接判缺失。单行处置走 [rowFixDecision]：
     * 文件状态三值（在盘/确认缺失/不可观测），删行前还要卷可见 + 按 URI 复核行身份。
     * 不可观测只落标记日志，绝不动行。删除与补入库共用 [SWEEP_MAX_INDEX] 预算。
     */
    private fun reconcileLibrary(app: Context, dir: File, now: Long): Int {
        // 查询失败或主卷不可见，不能拿空列表判断所有文件都缺索引。
        val volumes = visibleVolumes(app)
        if (!volumeVisible(MediaStore.VOLUME_EXTERNAL_PRIMARY, volumes)) return 0
        val rows = queryRows(app) ?: return 0
        var fixed = 0
        for (row in rows) {
            if (fixed >= SWEEP_MAX_INDEX) break
            if (performFix(app, row, fixRow(app, row, now, volumes))) fixed++
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
     * 单行判据装配：三值文件状态 + 卷可见性 + 按具体行 URI 复核 + 封口完整性 + owner。
     * 纯判据在 [rowFixDecision]；这里只负责把 Android 侧的事实取齐。
     */
    private fun fixRow(app: Context, row: Row, nowMs: Long, volumes: Set<String>?): RowFix {
        val ownerOurs = ownerAllowed(row.owner, app.packageName)
        if (!ownerOurs) return RowFix.KEEP
        val sealed = row.pending && SealMarker.isSealed(row.dataPath)
        val ageOk = sweepAgeOk(nowMs - row.addedMs, sealed)
        if (!ageOk) return RowFix.KEEP
        val volumeMounted = volumeVisible(concreteRowVolume(row.volume), volumes)
        if (!volumeMounted) return RowFix.MARK_UNOBSERVABLE
        val state = observeFile(app, row)
        // 按具体 URI 复核两种写操作；已发布且在盘的行不做额外查询。
        val needsCheck = state == RowFileState.ABSENT || (state == RowFileState.PRESENT && row.pending)
        val rowMatches = needsCheck && queryRowByUri(app, row) == row
        val playable = rowMatches && state == RowFileState.PRESENT && row.pending &&
            row.dataPath?.let { extractable(File(it)) } == true
        val fix = rowFixDecision(row.pending, state, ageOk, volumeMounted, rowMatches, playable, ownerOurs)
        if (fix == RowFix.KEEP && row.pending && state == RowFileState.PRESENT && !playable) {
            Log.w(TAG, "救援：pending 文件未通过封口校验（缺尾段/无视频轨），留原状 id=${row.id}")
        }
        return fix
    }

    /** 执行单行处置；返回是否真的动了行（删行 / 救援）。不可观测只标记，不算动作。 */
    private fun performFix(app: Context, row: Row, fix: RowFix): Boolean {
        return when (fix) {
            RowFix.DELETE_ROW -> if (deleteRow(app, row)) {
                Log.i(TAG, "清扫：删坏行（文件确认缺失，卷与 URI 已复核）${row.displayName}")
                true
            } else {
                false
            }
            RowFix.RESCUE_ROW -> if (unpendRow(app, row)) {
                SealMarker.clear(row.dataPath)
                Log.i(TAG, "救援：pending 成片收录 ${row.displayName}")
                true
            } else {
                false
            }
            RowFix.MARK_UNOBSERVABLE -> {
                Log.w(TAG, "清扫：文件不可观测，只标记不删 ${row.displayName} id=${row.id} path=${row.dataPath}")
                false
            }
            RowFix.KEEP -> false
        }
    }

    /**
     * 是不是我们自己产的成片名（`VID_<时间戳>` 或带转换前缀的 `XXftoXXf_VID_…`）。
     * **不认 marker 形态**（前缀里带 `.pending-<数字>-` 段的都判 false）——Q6 后这是"不可播残片
     * 保持原状就不会被补入库趟洗白"的承重判据，单独表测在 StalePendingSweepTest（故 internal）。
     */
    internal fun isOurClipName(name: String): Boolean =
        name.endsWith(".mp4") && name.contains("VID_") &&
            name.substringBefore("VID_").let { it.isEmpty() || it.matches(Regex("[0-9]+fto[0-9]+f_")) }

    /**
     * 本目录下的行快照：只在**主外部卷**上查（[PRIMARY_VIDEO_URI]）。聚合集合上按
     * RELATIVE_PATH 匹配会把 SD 卡同路径的行也收进来，后面的行操作就有错卷风险（Y22）。
     */
    private fun queryRows(app: Context): List<Row>? = queryRowsInDir(app, VideoStore.RELATIVE_PATH)

    private fun queryRowsInDir(app: Context, relativePath: String, pendingOnly: Boolean = false): List<Row>? {
        val pendingClause = if (pendingOnly) " AND ${MediaStore.MediaColumns.IS_PENDING}=1" else ""
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH}=?$pendingClause"
        return querySweepRows(app, PRIMARY_VIDEO_URI, where, arrayOf("$relativePath/"))
    }

    /** 查询失败与成功但零行有别。查询集合时显式包含本应用可见的 pending 行。 */
    private fun querySweepRows(
        app: Context,
        uri: Uri,
        where: String? = null,
        args: Array<String>? = null
    ): List<Row>? = runCatching {
        val resolver = app.contentResolver
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(uri, rowProjection, sweepArgs(where, args, MediaStore.MATCH_INCLUDE), null)
        } else {
            resolver.query(includePendingOnQ(uri), rowProjection, where, args, null)
        }
        cursor?.use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }
    }.getOrNull()

    @Suppress("DEPRECATION") // API 29 没有 QUERY_ARG_MATCH_PENDING。
    private fun includePendingOnQ(uri: Uri): Uri = MediaStore.setIncludePending(uri)

    private fun sweepArgs(where: String?, args: Array<String>?, matchPending: Int): Bundle = Bundle().apply {
        if (where != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
        if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, matchPending)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
    }

    /**
     * 行的**具体卷**条目 URI（Y22 的唯一拼法）：`VOLUME_NAME` → 卷集合 → 挂 `_ID`。
     * 卷名不是具体卷时回 null（合成卷 external 就是聚合集合，跨卷 `_ID` 可重号）。
     * 打开/复核/删/救援四路全部只认这一枚 URI——同一份"是哪条行"的判据不许第二把。
     */
    private fun rowItemUri(row: Row): Uri? {
        val volume = concreteRowVolume(row.volume) ?: return null
        return ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volume), row.id)
    }

    /**
     * 按**具体卷条目 URI**复核同一行（删行前的身份双检）：出错/行已不在/卷名非具体卷都返回 null
     * （调用方按不复核通过处理 ⇒ 不可观测，不删）。
     */
    private fun queryRowByUri(app: Context, row: Row): Row? = runCatching {
        val uri = rowItemUri(row) ?: return@runCatching null
        querySweepRows(app, uri)?.singleOrNull()
    }.getOrNull()

    private fun readRow(c: Cursor): Row {
        val ownerIdx = c.getColumnIndex(COL_OWNER)
        val generationIdx = c.getColumnIndex(COL_GENERATION)
        return Row(
            id = c.getLong(0),
            displayName = c.getString(1),
            dataPath = c.getString(2),
            addedMs = c.getLong(3) * 1000L,
            pending = c.getInt(4) != 0,
            volume = c.getString(5),
            relativePath = c.getString(6),
            sizeBytes = if (c.isNull(7)) null else c.getLong(7),
            modifiedSec = if (c.isNull(8)) null else c.getLong(8),
            owner = if (ownerIdx >= 0) c.getString(ownerIdx) else null,
            generationModified = if (generationIdx < 0 || c.isNull(generationIdx)) null else c.getLong(generationIdx)
        )
    }

    /** 当前可用的外部卷（查询失败 = null ⇒ [volumeVisible] 一律按不可见处理） */
    private fun visibleVolumes(app: Context): Set<String>? = runCatching {
        MediaStore.getExternalVolumeNames(app)
    }.getOrNull()

    /**
     * 文件可见性三值探测（Y22）。`File.exists()==false` **不是**缺失的充分条件：
     * FUSE 路径不可见、卷离线、DATA 过期都会让它回 false。
     *
     * 顺序：stat 在盘 → provider 打开（绕开路径层）→ 父目录可读列举。
     * 只有"父目录列举成功且确认不含该条目"才是 [RowFileState.ABSENT]；其余异常一律不可观测。
     */
    private fun observeFile(app: Context, row: Row): RowFileState {
        val path = row.dataPath ?: return RowFileState.UNOBSERVABLE
        val f = File(path)
        if (f.exists()) return RowFileState.PRESENT
        // provider 打开必须走**具体卷条目 URI**：聚合集合 + id 会打开另一卷的同号行，
        // "打开成功"就成了假证据。拼不回具体卷 = 钉不住是哪条行，一律不可观测。
        val uri = rowItemUri(row) ?: return RowFileState.UNOBSERVABLE
        var missing = false
        val opened = try {
            app.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            missing = true
            null
        } catch (e: Exception) {
            // SecurityException / provider 忙 / 卷未挂载：都不可观测
            return RowFileState.UNOBSERVABLE
        }
        if (opened != null) {
            runCatching { opened.close() }
            return RowFileState.PRESENT
        }
        // provider 回 null = 暂时打不开（文档语义：provider 崩溃），不可观测
        if (!missing) return RowFileState.UNOBSERVABLE
        val parent = f.parentFile ?: return RowFileState.UNOBSERVABLE
        if (!parent.isDirectory || !parent.canRead()) return RowFileState.UNOBSERVABLE
        val names = runCatching { parent.list() }.getOrNull() ?: return RowFileState.UNOBSERVABLE
        return if (names.contains(f.name)) RowFileState.PRESENT else RowFileState.ABSENT
    }

    /**
     * 删行：只删**具体卷条目 URI** 指向的那一条（Y22）。聚合集合 + id 在跨卷重号时会删到
     * 另一卷的同号行——删行连带删文件，等于删别人的成片。拼不回具体卷 ⇒ 不删。
     */
    private fun deleteRow(app: Context, row: Row): Boolean = runCatching {
        val uri = rowItemUri(row) ?: return@runCatching false
        val selection = writeSelection(row) ?: return@runCatching false
        val args = selection.args.toTypedArray()
        val resolver = app.contentResolver
        val rows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.delete(uri, sweepArgs(selection.where, args, pendingMatch(row)))
        } else {
            resolver.delete(includePendingOnQ(uri), selection.where, args)
        }
        rows > 0
    }.getOrDefault(false)

    /** 复查之后行仍可能变化；Provider 必须带原快照的条件执行写入。 */
    private fun writeSelection(row: Row): SweepWriteSelection? = sweepWriteSelection(
        row.displayName, row.dataPath, row.relativePath, row.addedMs / 1000L,
        row.sizeBytes, row.modifiedSec, row.pending, row.owner, row.generationModified,
        hasGeneration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    )

    private fun pendingMatch(row: Row): Int =
        if (row.pending) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE

    /** 无库行的文件也必须通过与救援同一把封口尺，才交系统扫描。 */
    private fun indexFile(app: Context, f: File): Boolean = runCatching {
        if (!extractable(f)) return@runCatching false
        MediaScannerConnection.scanFile(app, arrayOf(f.absolutePath), null) { _, _ -> }
        true
    }.getOrDefault(false)

    /**
     * 库行快照（[reconcileLibrary] / [rescuePendingRows] 用）。
     * [owner] 只从 API 30+ 的投影里有值（null = 当前系统读不到，见 [ownerAllowed]）。
     */
    private data class Row(
        val id: Long,
        val displayName: String?,
        val dataPath: String?,
        val addedMs: Long,
        val pending: Boolean,
        val volume: String?,
        val relativePath: String?,
        val sizeBytes: Long?,
        val modifiedSec: Long?,
        val owner: String?,
        val generationModified: Long?
    )

    /**
     * 档案对应的成片文件名（null = 找不到）。名字形态**三种都要试**：
     * ① 剥段后原样（`…-VID_x` 这种少了 `.mp4` 的幽灵档案能找到同名条目）；
     * ② 补 `.mp4`（正常档案 `VID_x.drops.json` 配 `VID_x.mp4`）；
     * ③ **成片仍带 pending 名**（成片趟 provider 改名失败的那一类：`.pending-9-VID_x.mp4`）——
     *    按"剥段后去掉 `.mp4`"与档案基名比对。旧实现只有 ①②，成片趟改名失败时档案趟
     *    找不到成片，会把仍有效的 sidecar 当无主档案删掉。
     */
    internal fun clipFor(archiveName: String, dir: File): String? {
        val base = PendingName.stripPendingJunk(archiveName).removeSuffix(ArcDropLog.EXTENSION)
        if (base.isEmpty()) return null
        val direct = File(dir, base)
        if (direct.isFile) return direct.name
        val withExt = File(dir, "$base.mp4")
        if (withExt.isFile) return withExt.name
        return dir.listFiles()?.firstOrNull { f ->
            f.isFile && f.name.endsWith(".mp4") &&
                PendingName.stripPendingJunk(f.name).removeSuffix(".mp4") == base
        }?.name
    }

    private fun apply(app: Context, dir: File, f: File, plan: SweepPlan): Boolean = runCatching {
        when (plan.action) {
            SweepAction.LEAVE -> false
            SweepAction.DELETE_ARCHIVE ->
                f.delete().also { if (it) Log.i(TAG, "清扫：删无主/重复档案 ${f.name}") }
            SweepAction.RENAME_ARCHIVE -> {
                val to = File(dir, plan.to ?: return@runCatching false)
                // 双保险：目标已存在就**绝不** rename——Linux rename 语义是替换，会把有效档案
                // 直接覆盖掉。计划侧 archiveTwinExists 已把这种形态判成 DELETE_ARCHIVE，
                // 这里再挡一次，防未来判据漂移（宁可留原名等下一轮，也不许丢档案）。
                if (to.exists()) {
                    Log.w(TAG, "清扫：档案归位目标已存在，不覆盖（留原名待下轮）：${f.name} -> ${plan.to}")
                    return@runCatching false
                }
                f.renameTo(to).also { if (it) Log.i(TAG, "清扫：档案归位 ${f.name} -> ${plan.to}") }
            }
            SweepAction.RENAME_CLIP -> {
                val to = File(dir, plan.to ?: return@runCatching false)
                // 【红线】有行的文件**只能**让 provider 改名：自己 rename 会让行里的 _data 指空，
                // 相册留下一条点开即坏的行（2026-10-08 真机实测：provider 那条路失败后回退 fs rename，
                // 10 条成片就这样变成了坏行）。所以：
                //   有行 ⇒ 走 provider，失败就**不动**（留原名，等下一轮/人工）；
                //   无行 ⇒ 本来就没人索引它，自己 rename 才是对的。
                //   **查不出有无行（provider 查询失败）⇒ 同"有行"处理：不动**——
                //   fs rename 赌"无行"赌错就是坏行事故重演，宁可留原名等下一轮。
                val rows = queryRenameRows(app, f) ?: return@runCatching false
                if (rows.isEmpty()) {
                    if (to.exists() || !f.renameTo(to)) return@runCatching false
                } else {
                    val row = rows.singleOrNull() ?: return@runCatching false
                    if (row.dataPath != f.absolutePath || !renameRow(app, row, to.name)) {
                        Log.w(TAG, "清扫：无法确认或改名，保持原状 id=${row.id}")
                        return@runCatching false
                    }
                }
                // 同名侧车一起搬（复用成片改名的既有单一出口，命名规则不分叉）
                ArcDropLog.renameFor(f.absolutePath, to.absolutePath)
                Log.i(TAG, "清扫：成片去 pending 段 ${f.name} -> ${to.name}")
                true
            }
        }
    }.getOrDefault(false)

    /** pending 文件在磁盘和 MediaStore 的名字可以不同，按实际路径和目录内旧名一起查。 */
    private fun queryRenameRows(app: Context, file: File): List<Row>? {
        val relPath = relativePathOf(file.parentFile ?: return null) ?: return null
        val where = "${MediaStore.MediaColumns.DATA}=? OR " +
            "(${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?)"
        return querySweepRows(app, PRIMARY_VIDEO_URI, where, arrayOf(file.absolutePath, file.name, "$relPath/"))
    }

    /** 复核具体条目后才改名及解除 pending，条件仍随 update 送给 Provider。 */
    private fun renameRow(app: Context, row: Row, newName: String): Boolean {
        if (!ownerAllowed(row.owner, app.packageName) || queryRowByUri(app, row) != row) return false
        return updateRow(app, row, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            if (row.pending) put(MediaStore.MediaColumns.IS_PENDING, 0)
        })
    }

    /** 成片目录（公共 Movies 下的固定相对路径）。路径口径统一取 [VideoStore.outputDir] 单一出口——
     *  清扫、空间探测、成片落地必须是同一块卷，分叉一次就会把别处当"文件不在"（B04 选卷）。 */
    fun outputDir(): File = VideoStore.outputDir()

    /**
     * #4 导出链 pending 行救援：导出在 `muxer.stop()` 之后 commit 全败时**保留行不删**
     * （成片字节已在盘上，删行 = MediaStore 连带删文件 = 丢成片，与 VideoStore.seal 同口径），
     * 留下的 IS_PENDING=1 行对外不可见（相册隐藏）、本应用列表也不查（IS_PENDING=0 门），
     * 只有这里能在冷启把它们收进来。
     *
     * **目录参数由调用方（ui 层）传入**——record 包不得反向依赖 player 包，剪辑/选轨的
     * 相对路径（ClipRule.RELATIVE_PATH / TrackRule.RELATIVE_PATH）只能由上层喂进来。
     *
     * **数据红线（四条，缺一不可）**：
     * 1. 年龄门槛 [sweepAgeOk]：普通遗留 ≥ [SWEEP_STALE_MS]；带 [SealMarker] 明确封口标记的行
     *    只等 [SealMarker.SEALED_RESCUE_MS]（已封口只差发布，不必和录制残片一起等一小时）；
     * 2. 「文件在盘」的行**永不删**：容器尾段完整且有视频轨 → 只 update 清 IS_PENDING（救援）；
     *    缺尾段/打不开 → Log.w 留原状（宁可留给下一轮/人工，也不猜）；
     * 3. 删行只走 [RowFix.DELETE_ROW]：文件**确认缺失**（stat 不可见 + provider 打开
     *    FileNotFoundException + 父目录可读列举不含该条目）+ 卷可见 + 按 URI 复核行身份一致；
     *    任一不满足 = 不可观测，只标记不删；
     * 4. 封口标记只缩短等待，**不替代**完整性校验（标记之后被截断的文件照样不许晋升）。
     *
     * 单轮上限 [SWEEP_MAX_INDEX]（收敛于多轮，不阻塞启动）。
     * @return 实际救援/清理的行数
     */
    fun rescuePendingRows(app: Context, dirs: List<File>): Int {
        val now = System.currentTimeMillis()
        val volumes = visibleVolumes(app)
        var acted = 0
        for (dir in dirs) {
            if (acted >= SWEEP_MAX_INDEX) break
            val relPath = relativePathOf(dir) ?: continue
            for (row in queryPendingRows(app, relPath)) {
                if (acted >= SWEEP_MAX_INDEX) break
                if (performFix(app, row, fixRow(app, row, now, volumes))) acted++
            }
        }
        if (acted > 0) {
            Log.i(TAG, "pending 行救援：本轮处置 $acted 条")
        }
        return acted
    }

    /** 只查主卷目录下可见的 pending 行；查询失败时本趟没有可处置的行。 */
    private fun queryPendingRows(app: Context, relPath: String): List<Row> =
        queryRowsInDir(app, relPath, pendingOnly = true).orEmpty()

    /** 目录 File → MediaStore RELATIVE_PATH（与 [outputDir] 的构造互逆）；不在外部存储根下返回 null */
    private fun relativePathOf(dir: File): String? = runCatching {
        @Suppress("DEPRECATION")
        dir.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath
    }.getOrNull()

    /**
     * 一次读取核对所有顶层 box 的长度与 moov，再由 MediaExtractor 确认存在可读视频轨。
     */
    private fun extractable(f: File): Boolean {
        if (!MoovSeal.complete(f)) return false
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            var hasVideo = false
            for (i in 0 until ex.trackCount) {
                val mime = runCatching {
                    ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                }.getOrNull()
                if (mime != null && mime.startsWith("video/")) {
                    hasVideo = true
                    break
                }
            }
            hasVideo
        } catch (e: Exception) {
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    /** 清除指定卷内这一行的 IS_PENDING。只有 Provider 确认更新后才能清封口标记。 */
    private fun unpendRow(app: Context, row: Row): Boolean = row.pending && updateRow(
        app, row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    )

    private fun updateRow(app: Context, row: Row, values: ContentValues): Boolean = runCatching {
        val uri = rowItemUri(row) ?: return@runCatching false
        val selection = writeSelection(row) ?: return@runCatching false
        val args = selection.args.toTypedArray()
        val resolver = app.contentResolver
        val affected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.update(uri, values, sweepArgs(selection.where, args, pendingMatch(row)))
        } else {
            resolver.update(includePendingOnQ(uri), values, selection.where, args)
        }
        affected > 0
    }.getOrDefault(false)
}

/** 清扫处置（见 [sweepPlan] 的表） */
internal enum class SweepAction { LEAVE, RENAME_CLIP, RENAME_ARCHIVE, DELETE_ARCHIVE }

/** @param to 目标**条目名**（不含目录）；LEAVE/DELETE 时为 null */
internal data class SweepPlan(val action: SweepAction, val to: String?)

/**
 * 档案"重复件"判据（纯函数，JVM 全测）：目录里是否已有这条档案的**真实归位目标**，
 * 或剥段后的同名条目。
 *
 * 为什么必须看 [targetName]：真机幽灵档案 `…-VID_x.drops.json` 少了 `.mp4` 段，归位目标
 * （规范名，见 [ArcDropLog.sidecarName]）也是 `VID_x.drops.json`；但旧实现把目标拼成
 * `VID_x.mp4.drops.json`，与规范名分叉——目标已存在时查不到 ⇒ `renameTo` 直接覆盖，
 * 有效档案被旧幽灵件顶掉。现在调用侧统一喂规范名，本函数仍按"目标 + 剥段名"两条判，
 * 防未来有人再传别的形态。
 *
 * 只有这两种情形算重复：两种名字指向同一份数据（档案只是"该成片的补弧账"），
 * 删掉待归位件不会少任何账目。
 */
internal fun archiveTwinExists(
    archiveName: String,
    targetName: String?,
    dirNames: Set<String>
): Boolean {
    if (targetName != null && targetName != archiveName && targetName in dirNames) return true
    val stripped = PendingName.stripPendingJunk(archiveName)
    return stripped != archiveName && stripped != targetName && stripped in dirNames
}

/**
 * 逐条处置计划。**这是清扫的全部判断**（纯函数，全表测试在 `StalePendingSweepTest`）：
 *
 * | 形态 | 条件 | 处置 |
 * |---|---|---|
 * | 非 `pending` 形态 | 名字剥段后没变 | LEAVE（不是我们的东西） |
 * | 任何 | 比门槛新 | LEAVE（可能是正在录的那一段） |
 * | 侧车 `.drops.json` | 剥段后同名档案已在 | DELETE（重复档案，纯档案无数据损失） |
 * | 侧车 `.drops.json` | 对应成片找得到 | RENAME（归位到**规范名** `成片名去扩展名 + .drops.json`） |
 * | 侧车 `.drops.json` | 对应成片也找不到 | DELETE（无主档案） |
 * | 成片 | 剥段后同名成片已在 | **LEAVE**（数据红线：成片绝不删） |
 * | 成片 | 容器打不开（或未探） | **LEAVE**（Q6：半截 MP4 一动不动，连名字都不动） |
 * | 成片 | 探过且打得开 | RENAME（去掉 pending 段） |
 *
 * 注意档案的**目标名取自成片真实名**、且必须走 [ArcDropLog.sidecarName] 的**规范名**
 * （`VID_x.mp4` → `VID_x.drops.json`），不是"成片名 + 扩展名"也不是"剥段后的档案名"：
 * 真机实证那些幽灵档案的名字是 `…-VID_20261008_172951_262.drops.json`（**没有 `.mp4` 那一段**，
 * 因为当时落盘用的路径就少扩展名），而成片侧读写的规矩是 `成片路径去扩展名 + .drops.json`
 * （见 [ArcDropLog.sidecarFor]）。旧的 `成片名 + ".drops.json"` 拼出 `VID_x.mp4.drops.json`，
 * App 永远读不到它（[ArcDropLog.readFrom] 按成片路径查）——B04 统一到规范名这一条出口。
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
            clipName != null -> SweepPlan(SweepAction.RENAME_ARCHIVE, ArcDropLog.sidecarName(clipName))
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
