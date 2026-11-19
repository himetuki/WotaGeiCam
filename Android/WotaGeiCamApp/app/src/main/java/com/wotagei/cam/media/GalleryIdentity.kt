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

/** UI / 列表 / Pager / 选择态的键：**真实行 URI**，不是裸 `_ID`。 */
internal fun rowKeyOf(clip: VideoClip): String = clip.uri.toString()

internal fun rowIdentities(clips: List<VideoClip>): List<RowIdentity> =
    clips.map { RowIdentity(it.id, rowKeyOf(it)) }

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
 * Room 台账里存的行 URI（`SeriesPart.uri`）与当前 MediaStore 行是否同一存储卷（Y27）。
 *
 * 聚合 MediaStore 上不同卷的 `_ID` 可以重号。系列成员表按全局 Long 关联，读侧若只认
 * id，会把 SD 卡上同号的另一条视频当成这次录制的分段（两条片一起从列表消失）。
 * 这里只做**卷**的等价比较而不是整条 URI 相等：台账 URI 可能是录制时的合成卷
 * `external`（= 主卷 `external_primary`），改名也不会搬运 `SeriesPart.uri`。
 *
 * 任一侧解析不出卷名时返回 true：无法证明不同卷就不拆组，保持既有行为。
 */
internal fun sameStorageVolume(storedUri: String, rowUri: String): Boolean {
    val stored = mediaVolumeOf(storedUri) ?: return true
    val row = mediaVolumeOf(rowUri) ?: return true
    return canonicalMediaVolume(stored) == canonicalMediaVolume(row)
}

/** 合成卷 `external` 指所有外部卷（录制插入用的就是这个），与主卷身份等价 */
private fun canonicalMediaVolume(volume: String): String =
    if (volume == MEDIA_VOLUME_EXTERNAL) MEDIA_VOLUME_EXTERNAL_PRIMARY else volume

// 与 MediaStore.VOLUME_EXTERNAL / VOLUME_EXTERNAL_PRIMARY 同值；写成字面量让纯 JVM 真值表
// 不依赖 android.jar 桩，也避免把「合成卷等同主卷」这条判据藏进框架常量。
private const val MEDIA_URI_PREFIX = "content://media/"
private const val MEDIA_VOLUME_EXTERNAL = "external"
private const val MEDIA_VOLUME_EXTERNAL_PRIMARY = "external_primary"

/**
 * 同一 `mediaId` 对应 ≥2 条**不同行 URI** 的 id 集合（Y27 跨卷同号）。
 *
 * 只按裸 `_ID` 做的操作在这批 id 上没有无歧义映射：
 * - UI 的列表键/选中态/菜单重解析一律改用行 URI（本函数因此只在拒绝路径上用到）；
 * - 长按打开/对比的路由仍只吃 `Long`（`player/{mediaId}`、`compare?leftMediaId=`），
 *   命中这里就显式拒绝，不拿"第一条"猜；
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