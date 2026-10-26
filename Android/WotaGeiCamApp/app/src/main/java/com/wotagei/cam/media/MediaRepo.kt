package com.wotagei.cam.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import com.wotagei.cam.R
import com.wotagei.cam.core.isMediaStoreVideoUri
import com.wotagei.cam.diag.Diag as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/** 媒体库读查询的日志锚点（查询失败的现场取证用） */
private const val TAG = "WotaMediaRepo"

/**
 * 媒体库条目。tags 来自 Room 标签表，isTrashed 来自 MediaStore（API 30+）或本地回收站记录。
 * 名称刻意用 VideoClip 而非 MediaItem，避免与 media3 的 MediaItem 混淆。
 *
 * 后三个 series 字段**全部带默认值**（#5 Step 4c）：`readRows` 的构造点一个字都不用改，
 * 单段片的语义与改动前逐字相同；分组信息由 [annotateSeries] 在联表那一层统一补。
 */
data class VideoClip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dataPath: String?,
    val relativePath: String,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val tags: Set<String>,
    val isTrashed: Boolean,
    /** 分段归组：所属系列 id（Room `series_part`）；null = 单段片 */
    val seriesId: Long? = null,
    /** 组内段序（Room `series_part.partIndex`） */
    val partIndex: Int? = null,
    /**
     * 该系列在库里的总段数（`media_series.partCount`）。**预留字段**：UI 角标实际消费
     * [GalleryEntry.seriesCount]（SeriesGroup 的呈现层投影），本字段当前在 main source
     * 零消费点，但 SeriesGroupGuardTest 钉住了这条声明与 annotateSeries 的填充——它是
     * 「系列元数据随 VideoClip 下发」的预留通道，删它得连守卫一起拆，故保留不删。
     */
    val seriesParts: Int = 1
) {
    val liked: Boolean get() = tags.contains(TagSpace.LIKE)
}

/**
 * 把 Room 的系列成员关系盖到 MediaStore 读出来的行上。
 *
 * 为什么在**联表这一层**做而不是在 `readRows` 里查：`readRows` 只认得 Cursor，
 * 系列信息在另一张表里；放这里就一处出口，`clips()` 与 `joinTags()` 并列在同一条管道上。
 *
 * 键是 `mediaId`（Room 里 `series_part.mediaId` 指向 MediaStore `_ID`）。
 * 命中不了的行原样返回：单段片逐字不变。
 */
internal fun annotateSeries(
    clips: List<VideoClip>,
    series: List<MediaSeries>,
    parts: List<SeriesPart>
): List<VideoClip> {
    if (series.isEmpty() && parts.isEmpty()) return clips
    val partCount = series.associate { it.seriesId to it.partCount }
    val byMedia = HashMap<Long, SeriesPart>(parts.size)
    parts.forEach { byMedia[it.mediaId] = it }
    return clips.map { c ->
        val p = byMedia[c.id] ?: return@map c
        c.copy(
            seriesId = p.seriesId,
            partIndex = p.partIndex,
            seriesParts = partCount[p.seriesId] ?: 1
        )
    }
}

/**
 * 媒体仓库：MediaStore 查询 + Room 标签联表 + 时间线分组。
 * 查询一律 Dispatchers.IO；列表变化靠 [invalidate] 版本号驱动，删除/收藏/tag 改完后调用它。
 */
class MediaRepo private constructor(private val app: Context) {

    private val db = WotaDb.get(app)
    private val tagDao = db.tagDao()
    private val trashDao = db.trashDao()
    private val seriesDao = db.seriesDao()

    private val version = MutableStateFlow(0L)

    /**
     * 列表刷新闸门。删除链路先关闸（Room 清标签会立刻触发列表重算），
     * pop 之后再开闸 ——  定版：列表刷新必须晚于播放页出栈，否则 pager 用陈旧 position 越界。
     */
    private val refreshGate = MutableStateFlow(false)

    fun holdListRefresh() {
        refreshGate.value = true
    }

    fun releaseListRefresh() {
        refreshGate.value = false
        invalidate()
    }

    /**
     * 闸门当前态的**只读视图**（UI 侧唯一入口，写入仍只有 [holdListRefresh] / [releaseListRefresh]）。
     *
     * 为什么只放一个 StateFlow 出去、不让 UI 调那两个写方法：闸门的开合是删除收尾的编排
     * （见 [runDeleteFinish]），UI 只该"知道"它，不该"参与"它。
     *
     * 消费方是媒体库的空态裁决：关闸期间列表**故意**不下发（定版：刷新必须晚于播放页出栈），
     * 那一刻"列表是空的"是**编排的结果**而不是真相——播放器彻底删除后返回媒体库，最多约
     * `POP_DELAY + REFRESH_DELAY` 里 `collectAsState(emptyList())` 只有空列表可显示，
     * 照旧显示"这里还没有视频"会让用户以为 13 条视频没了。读到这里就该显示"刷新中"，
     * 闸门一开真实快照自然替换（见 `GalleryScreen.galleryEmptyState`）。
     */
    val listRefreshing: StateFlow<Boolean> get() = refreshGate

    fun invalidate() {
        version.value += 1
    }

    fun db(): WotaDb = db

    private data class Snapshot(
        val version: Long,
        val tags: List<MediaTag>,
        val local: List<TrashItem>,
        val series: List<MediaSeries>,
        val parts: List<SeriesPart>
    )

    private fun triggers(): Flow<Snapshot> = combine(
        version, tagDao.observeAll(), trashDao.observeAll(),
        seriesDao.observeAllSeries(), seriesDao.observeAllParts()
    ) { v, tags, local, series, parts -> Snapshot(v, tags, local, series, parts) }

    /** 关闸期间不下发任何列表，开闸后用最新快照重算 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T : Any> gated(builder: suspend () -> T?): Flow<T> =
        refreshGate.flatMapLatest { open ->
            if (open) flow<T> { } else flow { builder()?.let { emit(it) } }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clips(scope: MediaScope, filter: ClipFilter): Flow<List<VideoClip>> =
        triggers()
            .flatMapLatest { s ->
                gated {
                    filterByTab(
                        annotateSeries(joinTags(queryMediaStore(scope), s.tags), s.series, s.parts),
                        filter,
                        s.local
                    )
                }
            }
            .flowOn(Dispatchers.IO)

    /**
     * 单条查询。**不能复用 [gated]**：gated 的 builder 返回 null 时零发射，而"查无此片"必须作为
     * 一次 null 下发——吞掉 null 的话，消费侧（路由播放页）的"尚未回包"哨兵永远顶不掉，
     * "片已消失"空态不可达，页面停在转圈。闸门语义与 [gated] 相同：关闸期间不下发
     * （删除编排期不闪空态），开闸后按最新快照重查。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clipById(id: Long): Flow<VideoClip?> {
        return triggers()
            .flatMapLatest { s ->
                refreshGate.flatMapLatest { open ->
                    if (open) flow<VideoClip?> { } else flow { emit(queryById(id)?.let { it.copy(tags = s.tags.filter { t -> t.mediaId == id }.map { t -> t.tag }.toSet()) }) }
                }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * 一次性明确查询：不受列表刷新闸门约束（闸门只护列表/播放页两条流）。
     *
     * 为什么有了 [clipById] 还要它：关闸期 [clipById] 的闸门分支**零发射**，散点的单次反查
     * （练习回程 / 历史面板的库内条目 / 分屏自动挂载）用 `.first()` 收流会在零发射流上
     * **挂起整个关闸期**（约 1.15s）——挂起不是异常，但一样是缺陷。这里一次查完即返回，
     * 语义与 [clipById] 开闸后的单次发射相同：null = 查过且没有。手选右片自 2026-10-26 起
     * 改走 [clipByUri]（外来来源没有 mediaId 可查），不再经这里。
     */
    suspend fun clipByIdOnce(id: Long): VideoClip? {
        // 块体而非 `= withContext(...)` 表达式体：源码守卫（ClipByIdOnceGateGuardTest）按花括号体
        // 定位本函数，表达式体会让守卫失去输入
        return withContext(Dispatchers.IO) {
            val clip = queryById(id)
            clip?.copy(tags = tagDao.tagsOf(id).toSet())
        }
    }

    /**
     * 按**任意可读的 content:// URI** 反查播放条目（对比页右槽的来源无关入口）。
     *
     * 两支：
     * 1. URI 是 MediaStore 视频集合条目（`content://media/<volume>/video/media/<id>`）：
     *    按 `_ID` 走 [queryById]，命中即补 Room 标签返回（与 [clipByIdOnce] 同口径）；
     * 2. 其它来源（照片选择器 / 文档 provider / 下载 provider …）或 MediaStore 查无此行：
     *    先做可读性预检（`openAssetFileDescriptor` 能打开才算可读），再取 `DISPLAY_NAME`、
     *    `MediaMetadataRetriever` 时长，构造一个**外来条目**。
     *
     * 返回条目的 `id == 0L` 是刻意语义：表示「非本机媒体库条目」（外来来源/照片选择器/文档
     * provider）。这类条目**只用于对比右槽播放与历史**，**不得进入媒体库列表**——它没有 Room
     * 标签、没有 MediaStore 行，混进列表会被删除/收藏/系列等按 `_ID` 工作的链路当成真条目。
     * 跨重启能否再读到它，取决于该 URI 的读授权是否被持久化（见 `persistPickReadPermission`，
     * 只有 ACTION_OPEN_DOCUMENT 类结果可持久化；ACTION_PICK 回程通常只在本次会话内有效）。
     *
     * 读不到（SecurityException / FileNotFound / provider 返回 null）或 URI 非法、任何异常
     * 一律返回 null，**不抛**。
     */
    suspend fun clipByUri(uri: Uri): VideoClip? {
        return withContext(Dispatchers.IO) {
            try {
                mediaStoreClipByUri(uri) ?: foreignClipByUri(uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消必须原样冒泡：吞掉会让调用方在已取消的协程里继续写 UI 状态
                throw e
            } catch (e: Exception) {
                // 失败现场要留：调用方只拿到 null，不看这条日志就分不清权限/IO/解码
                Log.w(TAG, "clipByUri 读取失败 uri=$uri", e)
                null
            }
        }
    }

    /** [clipByUri] 的 MediaStore 分支：命中返回带标签的库条目；非 MediaStore 形态或查无此行返回 null */
    private suspend fun mediaStoreClipByUri(uri: Uri): VideoClip? {
        if (!isMediaStoreVideoUri(uri)) return null
        // 末段超出 Long 范围时不要整条判死：交给外来分支按 URI 直读
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val hit = queryById(id) ?: return null
        // 标签是增强信息：Room 抖动不该把一条已命中的可播片判成「读取失败」
        return hit.copy(tags = runCatching { tagDao.tagsOf(id).toSet() }.getOrDefault(emptySet()))
    }

    /**
     * [clipByUri] 的「任意来源」分支。
     * 先验可读性：`openAssetFileDescriptor` 成功（并且 use 关闭）才算可读；抛异常或返回 null
     * 一律视为读不到（= null），绝不把一个打不开的 URI 塞进播放器。
     * provider 若声明了非视频类型（选中图片/文档/音频）直接拒——右槽是视频位，
     * 放进去只会在播放器里报一个难读的错误。
     */
    private fun foreignClipByUri(uri: Uri): VideoClip? {
        if (!canReadUri(uri)) return null
        val declared = runCatching { app.contentResolver.getType(uri) }.getOrNull()
        if (!declared.isNullOrEmpty() && !declared.startsWith("video/")) return null
        val name = displayNameOf(uri)
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: app.getString(R.string.compare_unnamed_video)
        return VideoClip(
            id = 0L,
            uri = uri,
            name = name,
            dataPath = null,
            relativePath = "",
            dateAddedSec = 0L,
            sizeBytes = 0L,
            durationMs = durationOf(uri),
            width = 0,
            height = 0,
            mimeType = "video/*",
            tags = emptySet(),
            isTrashed = false
        )
    }

    /** 可读性预检：能打开（并关闭）读描述符即可读；任何异常/返回 null 都按不可读处理 */
    private fun canReadUri(uri: Uri): Boolean = runCatching {
        app.contentResolver.openAssetFileDescriptor(uri, "r")?.let { fd -> fd.use { true } } ?: false
    }.getOrDefault(false)

    /** provider 的 DISPLAY_NAME；查不到/为空返回 null（由调用方回退到路径段/默认名） */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        app.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    /** 时长（ms）；取不到或任何异常一律 0，不影响播放 */
    private fun durationOf(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(app, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 自定义 tag 列表（按首次使用时间） */
    fun customTags(): Flow<List<String>> =
        tagDao.observeAll()
            .map { tags ->
                tags.filter { TagSpace.isCustom(it.tag) }
                    .sortedBy { it.createdAt }
                    .map { it.tag }
                    .distinct()
            }
            .flowOn(Dispatchers.IO)

    fun trashItems(): Flow<List<TrashItem>> = trashDao.observeAll().flowOn(Dispatchers.IO)

    // region MediaStore

    private val projectionBase = listOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.RELATIVE_PATH,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.MIME_TYPE
    )

    private val projection: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (projectionBase + TRASHED_COLUMN).toTypedArray()
        } else {
            projectionBase.toTypedArray()
        }

    private fun queryMediaStore(scope: MediaScope): List<VideoClip> {
        // IS_PENDING 门：写一半的 pending 记录对 owner 的查询默认可见（只对外部 app 隐藏），
        // 不挡的话录制中/剪辑导出中回到媒体库，网格里会出现 0 字节幽灵条目，点开是半成品文件；
        // 「commit 后相册立即可见」由该门 + 完成侧 invalidate 共同成立
        val pendingGate = "${MediaStore.MediaColumns.IS_PENDING} = 0 AND "
        val selection = if (scope == MediaScope.WotaLibrary) {
            "${pendingGate}MIME_TYPE LIKE ? AND RELATIVE_PATH LIKE ?"
        } else {
            "${pendingGate}MIME_TYPE LIKE ?"
        }
        val args = if (scope == MediaScope.WotaLibrary) {
            arrayOf("video/%", "%$WOTA_DIR_KEYWORD%")
        } else {
            arrayOf("video/%")
        }
        val live = readCursor(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )
        val trashed = trashedMatching(scope)
        return if (trashed.isEmpty()) live else live + trashed
    }

    /** 回收站行只能单独查，所以作用域过滤改在内存里做 */
    private fun trashedMatching(scope: MediaScope): List<VideoClip> =
        readTrashed().filter {
            // 再认一次列值：万一某个 ROM 对 MATCH_ONLY 支持不完整，也不该把在用的成片漏进回收站页签
            it.isTrashed && it.mimeType.startsWith("video/") &&
                (scope == MediaScope.AllVideos || it.relativePath.contains(WOTA_DIR_KEYWORD))
        }

    private fun queryById(id: Long): VideoClip? {
        val live = readCursor(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Video.Media._ID} = ?",
            arrayOf(id.toString()),
            null
        ).firstOrNull()
        return live ?: trashedMatching(MediaScope.AllVideos).firstOrNull { it.id == id }
    }

    private fun readCursor(
        contentUri: Uri,
        selection: String?,
        args: Array<String>?,
        sort: String?
    ): List<VideoClip> {
        val out = ArrayList<VideoClip>()
        val resolver = app.contentResolver
        runCatching {
            resolver.query(contentUri, projection, selection, args, sort)?.use { c ->
                readRows(c, out)
            }
        }.onFailure {
            // 语义不变：失败仍返回（可能是空的）out。补日志只为把"媒体库列表空"从
            // 「本来就没视频」和「查询炸了」这两种现场里区分出来——不记就永远无从判断。
            Log.e(TAG, "查询失败 uri=$contentUri selection=$selection → 按空/半截列表返回", it)
        }
        return out
    }

    /**
     * 已进回收站的行。
     *
     * 必须单独查：MediaStore 默认把 trashed 行整个隐藏（本机实测 `?include_trashed=1` 这个 URI 参数
     * 根本不生效，只有 Bundle 版 `QUERY_ARG_MATCH_TRASHED` 认）。不查它的话「回收站」页签恒显示空，
     * 而条目同时已从「全部」消失 —— 用户彻底找不到自己的废片。
     *
     * **只能 API 30+ 查**：`QUERY_ARG_MATCH_TRASHED` 是 API 30 才有的，API 29 的 provider 会把它当
     * 未知参数忽略掉，那条查询就退化成"返回全部视频"，而 29 上 `is_trashed` 列也不存在、行解析出来
     * 全是 `isTrashed=false`，`filterByTab` 的回收站判据又只看本地表 —— 结果会是每条成片在网格里出现两遍。
     * 29 上本地 `trash_item` 表才是唯一权威（见 [SYSTEM_TRASH_SUPPORTED]），直接返回空。
     */
    private fun readTrashed(): List<VideoClip> {
        if (!SYSTEM_TRASH_SUPPORTED) return emptyList()
        val out = ArrayList<VideoClip>()
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        runCatching {
            app.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                queryArgs,
                null
            )?.use { c -> readRows(c, out) }
        }.onFailure {
            // 同上：失败仍返回空列表（回收站页签显示空），只是把失败现场留下来。
            // 不记的话「回收站是空的」与「系统回收站查询炸了」在界面上长得一模一样。
            Log.e(TAG, "回收站查询失败（API ${Build.VERSION.SDK_INT}）→ 按空列表返回", it)
        }
        return out
    }

    private fun readRows(c: android.database.Cursor, out: ArrayList<VideoClip>) {
        val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val iName = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
        val iAdded = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
        val iSize = c.getColumnIndex(MediaStore.Video.Media.SIZE)
        val iDur = c.getColumnIndex(MediaStore.Video.Media.DURATION)
        val iW = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
        val iH = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
        val iRel = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
        val IData = c.getColumnIndex(MediaStore.Video.Media.DATA)
        val iMime = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
        val iTrashed = c.getColumnIndex(TRASHED_COLUMN)
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        while (c.moveToNext()) {
            val id = c.getLong(iId)
            out += VideoClip(
                id = id,
                uri = android.content.ContentUris.withAppendedId(base, id),
                name = if (iName >= 0) c.getString(iName) ?: "" else "",
                dataPath = if (IData >= 0) c.getString(IData) else null,
                relativePath = if (iRel >= 0) c.getString(iRel) ?: "" else "",
                dateAddedSec = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                sizeBytes = if (iSize >= 0) c.getLong(iSize) else 0L,
                durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                width = if (iW >= 0) c.getInt(iW) else 0,
                height = if (iH >= 0) c.getInt(iH) else 0,
                mimeType = if (iMime >= 0) c.getString(iMime) ?: "video/mp4" else "video/mp4",
                tags = emptySet(),
                isTrashed = iTrashed >= 0 && c.getInt(iTrashed) == 1
            )
        }
    }

    // endregion

    private fun joinTags(raw: List<VideoClip>, tags: List<MediaTag>): List<VideoClip> {
        if (tags.isEmpty()) return raw
        val byId = HashMap<Long, MutableSet<String>>()
        tags.forEach { t -> byId.getOrPut(t.mediaId) { HashSet() }.add(t.tag) }
        return raw.map { c -> c.copy(tags = byId[c.id] ?: emptySet()) }
    }

    private fun filterByTab(list: List<VideoClip>, filter: ClipFilter, localTrash: List<TrashItem>): List<VideoClip> {
        val localUris = localTrash.map { it.uri }.toSet()
        return when (filter) {
            ClipFilter.All -> list.filter { !trashedFor(it, localUris) }
            ClipFilter.Like -> list.filter { !trashedFor(it, localUris) && it.liked }
            // 回收站 = 系统侧已进回收站（API 30+）/ 本地记录（API 29 退化路径），两者按系统是否支持分别取信
            ClipFilter.Trash -> list.filter { trashedFor(it, localUris) }
            is ClipFilter.Tagged -> list.filter { !trashedFor(it, localUris) && filter.tag in it.tags }
        }
    }

    /**
     * 隐藏条件必须与系统真实态一致：API 30+ 以 IS_TRASHED 为唯一权威 —— 本地 trash_item 只是授权成功后的
     * 记账，弹窗被拒（或用户在系统相册里还原）后会残留，此时文件仍在，不该从常规页签消失。
     * API 29 没有 is_trashed 列，本地记录才是退化路径的唯一凭据。
     */
    private fun trashedFor(clip: VideoClip, localUris: Set<String>): Boolean =
        clip.isTrashed || (!SYSTEM_TRASH_SUPPORTED && localUris.contains(clip.uri.toString()))

    companion object {

        /** 系统回收站语义是否可用（API 30 起 MediaStore 才有 is_trashed 列） */
        private val SYSTEM_TRASH_SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        /** 与 04 录制管线入库路径 Movies/WotaGeiCam/Camera 对应 */
        const val WOTA_DIR_KEYWORD = "WotaGeiCam"
        private const val TRASHED_COLUMN = "is_trashed"

        // 单例只持 applicationContext（见下方 get()），不会把 Activity 留在进程里
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: MediaRepo? = null

        fun get(context: Context): MediaRepo =
            instance ?: synchronized(this) {
                instance ?: MediaRepo(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 时间线分组文案（今天 / 昨天 / 某月某日 / 跨年补年）。
 * 放仓库层，UI 只做渲染，避免中文出现在 Compose 里。
 */
fun groupTimeline(clips: List<VideoClip>, app: Context): List<TimelineGroup> {
    if (clips.isEmpty()) return emptyList()
    val todayEpochDay = epochDay(Calendar.getInstance())
    val groups = LinkedHashMap<String, MutableList<VideoClip>>()
    clips.forEach { c ->
        val cal = Calendar.getInstance().apply { timeInMillis = c.dateAddedSec * 1000L }
        val epochDay = epochDay(cal)
        val diff = todayEpochDay - epochDay
        val sameYear = cal.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
        val key = epochDay.toString()
        val label = when {
            diff == 0L -> app.getString(R.string.gallery_day_today)
            diff == 1L -> app.getString(R.string.gallery_day_yesterday)
            sameYear -> app.getString(
                R.string.gallery_day_month_date,
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            else -> app.getString(
                R.string.gallery_day_year_date,
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
        }
        groups.getOrPut("$key|$label") { ArrayList() }.add(c)
    }
    return groups.entries.map { (k, v) ->
        val i = k.indexOf('|')
        TimelineGroup(label = k.substring(i + 1), dayKey = k.substring(0, i), clips = v)
    }
}

/** 距 1970-01-01 的天数（比 DAY_OF_YEAR 安全，跨年不会误判「昨天」） */
private fun epochDay(cal: Calendar): Long {
    val copy = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return copy.timeInMillis / 86_400_000L
}

/** mm:ss / h:mm:ss */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 文件大小可读化（单位是硬编码英文缩写不算界面中文文案？不，走资源更安全，但 KB/MB 为国际单位符号，保留） */
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        else -> String.format(Locale.US, "%.0f KB", bytes / kb)
    }
}

/**
 * 缩略图加载：ContentResolver.loadThumbnail（API 29+）失败回退 MediaMetadataRetriever。
 * 并发限 2；列表滑动中由 [paused] 暂停新请求（06 文档性能节）。
 *
 * ⚠ **解码必须切到 [Dispatchers.IO]**：本函数的调用方是 `VideoThumbnail` 的 `LaunchedEffect`，
 * 那是组合期上下文（AndroidUiDispatcher.Main）。`MediaMetadataRetriever.setDataSource` +
 * `getFrameAtTime` 是**阻塞调用**，不切线程就是把每张缩略图的解码直接压在主线程上——
 * 进媒体库时网格一次组合出十几张卡，主线程被排队的解码逐张占住，转场动画就一顿一顿的
 * （这正是"进出媒体库动画卡顿"那一条的定位结论，`GalleryScreen.kt` 的 `LaunchedEffect(uri, px)`）。
 * 切到 IO 之后阻塞调用不再参与上屏帧预算；调用方取消后**本次结果不再写缓存、也不再返回**，
 * 但**本次解码仍会跑完**——`MediaMetadataRetriever.setDataSource` / `getFrameAtTime` 阻塞且不可中断，
 * 取消只能作废结果（原先在主线程上的 decode 连这一点都做不到）。
 */
object ThumbLoader {

    private const val CACHE_BYTES = 48 * 1024 * 1024
    private const val DEFAULT_PX = 512

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val permits = Semaphore(2)

    /** 滑动中置 true：新请求直接排队等待，不抢解码资源 */
    @Volatile
    var paused: Boolean = false

    fun bitmapKey(uri: Uri, px: Int): String = "$uri@$px"

    fun peek(uri: Uri, px: Int = DEFAULT_PX): Bitmap? = cache.get(bitmapKey(uri, px))

    suspend fun load(app: Context, uri: Uri, px: Int = DEFAULT_PX): Bitmap? {
        val key = bitmapKey(uri, px)
        cache.get(key)?.let { return it }
        while (paused) {
            kotlinx.coroutines.delay(80)
        }
        return permits.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val bmp = withContext(Dispatchers.IO) { decode(app, uri, px) }
            if (bmp != null) cache.put(key, bmp)
            bmp
        }
    }

    /**
     * 取**中点帧**而不是首帧：成片开头经常是黑帧或手挡着，首帧当缩略图根本认不出内容。
     * retriever 没有系统缩略图缓存，靠并发限 2 + LruCache 兜；打不开（损坏/无权限）才退回 loadThumbnail。
     */
    private fun decode(app: Context, uri: Uri, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val mid = try {
            retriever.setDataSource(app, uri)
            val durUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = (px / 2).coerceAtLeast(64) * 2
            val src = if (durUs > 0L) {
                retriever.getFrameAtTime(durUs / 2, MediaMetadataRetriever.OPTION_CLOSEST)
            } else {
                retriever.frameAtTime
            }
            src?.let {
                if (it.width <= w) it
                else {
                    val h = (it.height.toLong() * w / it.width.toLong()).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(it, w, h, true)
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        if (mid != null) return mid
        return runCatching { app.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
    }

    fun evict(uri: Uri) {
        cache.remove(bitmapKey(uri, DEFAULT_PX))
    }
}
