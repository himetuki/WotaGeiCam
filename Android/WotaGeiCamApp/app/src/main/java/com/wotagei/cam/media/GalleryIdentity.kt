package com.wotagei.cam.media

/**
 * 媒体库选中态 / 菜单目标 / 系列代表段的**纯身份判据**（Y27，JVM 可测）。
 *
 * 为什么单独抽一层：[VideoClip] 里躺着 `android.net.Uri`，本仓 JVM 单测没有
 * Robolectric/mockk（android.jar 桩里 Uri 构造器直接抛 `Stub!`），真值表只能在裸
 * Long/Int 上写。UI 侧只做一次薄映射。
 */

/**
 * 选中态与当前可见行求交：已经不在列表里的键必须掉出选择。
 *
 * 原缺陷：`selectedIds` 只在 filter/scope/mode 变化时清空。外部删片、撤权、其他页签
 * 操作导致列表收窄后，选择条仍按旧 id 计数并让动作作用于已不存在的条目。
 *
 * 键的类型留给调用方（Y27 收口后 UI 传真实行 URI 的字符串，不再传裸 `_ID`）：
 * 聚合集合上不同卷的 `_ID` 会重号，用 Long 做键会把另一卷的同号行一起选中。
 */
internal fun <T> pruneSelection(selected: Set<T>, live: Set<T>): Set<T> =
    selected.filterTo(LinkedHashSet(selected.size)) { it in live }

/**
 * 一行的**真实身份**：`mediaId`（Room 台账仍按它关联）+ 行 URI（跨卷唯一）。
 *
 * 只在这里放两个裸值，JVM 真值表不必构造 [VideoClip]（其 `Uri` 在仓库的 android.jar
 * 桩里无法实例化）。
 */
internal data class RowIdentity(val mediaId: Long, val rowKey: String)

/** 具体卷集合里的一行：`content://media/<卷>/video/media/<id>`（[mediaRowRefOf] 的产物） */
internal data class MediaRowRef(val volume: String, val mediaId: Long)

/** UI / 列表 / Pager / 选择态的键：**真实行 URI**，不是裸 `_ID`。 */
internal fun rowKeyOf(clip: VideoClip): String = clip.uri.toString()

internal fun rowIdentities(clips: List<VideoClip>): List<RowIdentity> =
    clips.map { RowIdentity(it.id, rowKeyOf(it)) }

/**
 * 从**真实行 URI**解出的行身份：具体卷名 + `_ID`（Y27 正常播放/对比路由与加载的入口）。
 *
 * 只解析字符串（JVM 真值表可测），形状不对一律 null——调用方按「没有这一行」处理，
 * 绝不退回裸 `_ID` 猜。合成卷 `external` 也拒绝：它是跨所有卷的聚合集合，
 * 挂上 `_ID` 就是本轮要消灭的那枚歧义键（旧深链的裸 ID 走的是另一条明确入口）。
 */
internal fun mediaRowRefOf(uri: String): MediaRowRef? {
    val ref = mediaUriParts(uri) ?: return null
    if (ref.volume == MEDIA_VOLUME_EXTERNAL) return null
    return ref
}

/**
 * 旧版本记下的行 URI 可能长成聚合形态 `content://media/external/video/media/<id>`
 * （当时 readRows 不读 `VOLUME_NAME`）。只有**唯一且确认同一行**时才允许把它归一到
 * 具名主卷（[legacyTrashAliasTarget]）；这里只负责把形状解出来。
 */
internal fun legacyAggregateRowRefOf(uri: String): MediaRowRef? =
    mediaUriParts(uri)?.takeIf { it.volume == MEDIA_VOLUME_EXTERNAL }

private fun mediaUriParts(uri: String): MediaRowRef? {
    if (!uri.startsWith(MEDIA_URI_PREFIX)) return null
    val segs = uri.substring(MEDIA_URI_PREFIX.length).substringBefore('?').split('/')
    if (segs.size != 4) return null
    val volume = segs[0].takeIf { it.isNotBlank() } ?: return null
    if (segs[1] != MEDIA_VIDEO_SEGMENT || segs[2] != MEDIA_MEDIA_SEGMENT) return null
    val id = segs[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
    return MediaRowRef(volume, id)
}

/** 旧聚合账键（`content://media/external/video/media/<id>`）；构造口径只有这一处 */
internal fun aggregateRowUri(mediaId: Long): String =
    "$MEDIA_URI_PREFIX$MEDIA_VOLUME_EXTERNAL/$MEDIA_VIDEO_SEGMENT/$MEDIA_MEDIA_SEGMENT/$mediaId"

/** 行 URI 的**规范卷名**：合成卷 `external` 与主卷 `external_primary` 同一存储身份 */
internal fun canonicalMediaVolumeOf(uri: String): String? =
    mediaVolumeOf(uri)?.let(::canonicalMediaVolume)

/**
 * 从 MediaStore 内容 URI 字符串取存储卷名（Y27）：`content://media/<volume>/video/media/<id>`。
 *
 * 只解析字符串、不碰 `android.net.Uri`：JVM 单测里的 android.jar 桩不能实例化 Uri。
 * 不是 `content://media/` 形状的返回 null（调用方按「无法判定」处理，不猜）。
 */
internal fun mediaVolumeOf(uri: String): String? {
    if (!uri.startsWith(MEDIA_URI_PREFIX)) return null
    return uri.substring(MEDIA_URI_PREFIX.length).substringBefore('/').takeIf { it.isNotBlank() }
}

/**
 * Room 台账里存的行 URI（`SeriesPart.uri` / `TrashItem.uri`）与当前 MediaStore 行是否同一存储卷（Y27）。
 *
 * 聚合 MediaStore 上不同卷的 `_ID` 可以重号。系列成员表按全局 Long 关联，读侧若只认
 * id，会把 SD 卡上同号的另一条视频当成这次录制的分段（两条片一起从列表消失）。
 * 这里只做**卷**的等价比较而不是整条 URI 相等：台账 URI 可能是录制时的合成卷
 * `external`（= 主卷 `external_primary`），改名也不会搬运 `SeriesPart.uri`。
 *
 * [sameIdRows] 是当前快照里同一 `_ID` 命中的**不同行 URI 数**。合成卷记录自己没留下卷身份
 * （旧版本在聚合集合上读取，SD 卡行也被记成 `external`），所以只有在这个 id 唯一时才认
 * 主卷归属；命中多行时按「无法证明同一行」处理（不归组），绝不把另一卷的同号行认成成员。
 *
 * 任一侧解析不出卷名时返回 true：无法证明不同卷就不拆组，保持既有行为。
 */
internal fun sameStorageVolume(storedUri: String, rowUri: String, sameIdRows: Int = 1): Boolean {
    val stored = mediaVolumeOf(storedUri) ?: return true
    val row = mediaVolumeOf(rowUri) ?: return true
    if (stored == MEDIA_VOLUME_EXTERNAL) {
        return sameIdRows <= 1 && canonicalMediaVolume(row) == MEDIA_VOLUME_EXTERNAL_PRIMARY
    }
    return stored == row
}

/** 合成卷 `external` 指所有外部卷（录制插入用的就是这个），与主卷身份等价 */
private fun canonicalMediaVolume(volume: String): String =
    if (volume == MEDIA_VOLUME_EXTERNAL) MEDIA_VOLUME_EXTERNAL_PRIMARY else volume

// 与 MediaStore.VOLUME_EXTERNAL / VOLUME_EXTERNAL_PRIMARY 同值；写成字面量让纯 JVM 真值表
// 不依赖 android.jar 桩，也避免把「合成卷等同主卷」这条判据藏进框架常量。
private const val MEDIA_URI_PREFIX = "content://media/"
private const val MEDIA_VOLUME_EXTERNAL = "external"
private const val MEDIA_VOLUME_EXTERNAL_PRIMARY = "external_primary"
private const val MEDIA_VIDEO_SEGMENT = "video"
private const val MEDIA_MEDIA_SEGMENT = "media"

/**
 * 同一 `mediaId` 对应 ≥2 条**不同行 URI** 的 id 集合（Y27 跨卷同号）。
 *
 * 只按裸 `_ID` 做的操作在这批 id 上没有无歧义映射：
 * - UI 的列表键/选中态/菜单重解析一律改用行 URI；
 * - 打开/对比路由已改吃真实行 URI（`player/{mediaId}?row=`、`compare?...&row=`），
 *   不再需要在这里拒绝；
 * - 标签读端（[tagsForRow]）只认 `path` 与当前行相符的标签，即使另一卷已离线；
 * - Room 台账写入（收藏/tag）若在这些 id 上继续按 Long 落库，会把标签挂到同号的
 *   另一卷行上，因此同样显式拒绝（schema 不动，见 `MediaActions`）。
 *
 * 同一 URI 重复出现不算歧义：那只是同一条行被两个页签/两次快照各报了一遍。
 */
internal fun ambiguousMediaIds(rows: List<RowIdentity>): Set<Long> {
    if (rows.size < 2) return emptySet()
    val firstKey = HashMap<Long, String>(rows.size)
    val out = LinkedHashSet<Long>()
    for (row in rows) {
        val prev = firstKey.putIfAbsent(row.mediaId, row.rowKey)
        if (prev != null && prev != row.rowKey) out += row.mediaId
    }
    return out
}

/**
 * 一组分段里代表段的下标：`partIndex` 最小者（首段），脏数据（null）排到最后。
 *
 * 与 [SeriesGroup.groupSpans] 的排序口径一致。组卡动作（对比等）必须落到代表段，
 * 否则会开较晚的一段（Y27）。空列表返回 -1。
 */
internal fun representativePartIndex(partIndices: List<Int?>): Int {
    if (partIndices.isEmpty()) return -1
    return partIndices.indices.minByOrNull { partIndices[it] ?: Int.MAX_VALUE } ?: -1
}

/**
 * 一条标签在**当前行**上的可归属判据（Y27 标签读端；纯函数，`MediaTag` 不进纯层）。
 *
 * Room `media_tag` 的 `path` 是写入时的行身份（`dataPath`，缺失时是行 URI）。
 * 一条同号行可见不代表另一卷没记过同号标签，因此读端不按裸 id 兜底。
 * 兼容原始 DATA 和回收站归档前的 DATA，以及具名行 URI（曾拿不到 DATA 时的写入键）。
 */
internal fun tagsForRow(tags: List<MediaTag>, pathKeys: Set<String>): Set<String> =
    tags.asSequence().filter { it.path in pathKeys }.map { it.tag }.toCollection(LinkedHashSet())

internal fun VideoClip.tagPathKeys(): Set<String> = buildSet {
    add(uri.toString())
    dataPath?.let { path ->
        add(path)
        if (isTrashed) originalTrashPath(path)?.let(::add)
    }
}

internal fun VideoClip.tagPathForWrite(): String =
    dataPath?.let { path -> if (isTrashed) originalTrashPath(path) ?: path else path } ?: uri.toString()

/** 严格核对用户点中的快照；另一卷唯一可见、同 URI 换片或查询失败均不能落旧账。 */
internal data class LedgerRow(val row: MediaRowRef, val path: String, val addedSec: Long)

internal fun ledgerWriteMatches(target: LedgerRow?, hits: List<LedgerRow?>): Boolean =
    target != null && hits.distinct().singleOrNull() == target

internal fun VideoClip.ledgerRow(): LedgerRow? =
    mediaRowRefOf(uri.toString())?.takeIf { it.mediaId == id }?.let {
        LedgerRow(it, dataPath ?: uri.toString(), dateAddedSec)
    }

/**
 * 旧聚合回收站账键（`content://media/external/video/media/<id>`）的归一目标（Y27）。
 *
 * API 29 用本地 `trash_item` 表当回收站权威，旧版本把行 URI 记成聚合形态；行 URI 改按
 * `VOLUME_NAME` 拼回具体卷后，字符串直比会让已进回收站的行重新出现在「全部」页签。
 *
 * 兼容**只在这一种情况下成立**：同一个 `_ID` 在当前快照里只有一条行、该行在具名主卷、
 * 且台账里的名字与原相对路径都与该行一致（= 确认同一行，防 MediaStore 复用 `_ID` 后
 * 把旧账套到新片上）。任一条件不满足就返回 null——保留旧键当作匹配不上，宁可让一条旧
 * 记录失效，也不把另一卷的同号行判成「已删除」。
 */
internal data class RowProbe(val rowKey: String, val name: String, val relativePath: String)

internal fun legacyTrashAliasTarget(
    ledgerUri: String,
    ledgerName: String,
    ledgerOrigin: String,
    rowsWithSameId: List<RowProbe>
): String? {
    val ref = legacyAggregateRowRefOf(ledgerUri) ?: return null
    val distinct = rowsWithSameId.map { it.rowKey }.distinct()
    if (distinct.size != 1) return null
    val row = rowsWithSameId.first()
    if (canonicalMediaVolumeOf(row.rowKey) != MEDIA_VOLUME_EXTERNAL_PRIMARY) return null
    if (row.name != ledgerName || row.relativePath != ledgerOrigin) return null
    // 解析出的 id 与实际行 id 未必一致（同一 id 才会被传进来），这里再核一次形状里的 id
    if (mediaRowRefOf(row.rowKey)?.mediaId != ref.mediaId) return null
    return row.rowKey
}