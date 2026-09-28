package com.wotagei.cam.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.wotagei.cam.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Calendar
import java.util.Locale

/**
 * 媒体库条目。tags 来自 Room 标签表，isTrashed 来自 MediaStore（API 30+）或本地回收站记录。
 * 名称刻意用 VideoClip 而非 MediaItem，避免与 media3 的 MediaItem 混淆。
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
    val isTrashed: Boolean
) {
    val liked: Boolean get() = tags.contains(TagSpace.LIKE)
}

/**
 * 媒体仓库：MediaStore 查询 + Room 标签联表 + 时间线分组。
 * 查询一律 Dispatchers.IO；列表变化靠 [invalidate] 版本号驱动，删除/收藏/tag 改完后调用它。
 */
class MediaRepo private constructor(private val app: Context) {

    private val db = WotaDb.get(app)
    private val tagDao = db.tagDao()
    private val trashDao = db.trashDao()

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

    fun invalidate() {
        version.value += 1
    }

    fun db(): WotaDb = db

    private data class Snapshot(val version: Long, val tags: List<MediaTag>, val local: List<TrashItem>)

    private fun triggers(): Flow<Snapshot> =
        combine(version, tagDao.observeAll(), trashDao.observeAll()) { v, tags, local -> Snapshot(v, tags, local) }

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
                gated { filterByTab(joinTags(queryMediaStore(scope), s.tags), filter, s.local) }
            }
            .flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clipById(id: Long): Flow<VideoClip?> =
        triggers()
            .flatMapLatest { s -> gated { queryById(id)?.let { it.copy(tags = s.tags.filter { t -> t.mediaId == id }.map { t -> t.tag }.toSet()) } } }
            .flowOn(Dispatchers.IO)

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
        val selection = if (scope == MediaScope.WotaLibrary) {
            "MIME_TYPE LIKE ? AND RELATIVE_PATH LIKE ?"
        } else {
            "MIME_TYPE LIKE ?"
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
            val bmp = decode(app, uri, px)
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
