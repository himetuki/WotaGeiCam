package com.wotagei.cam.media

/**
 * 媒体库的一**条**展示单元：单个片，或一次录制归成的系列组。
 *
 * @property seriesId 组 id；单片恒为 null（null ⇔ 这条不是组）
 * @property rep 代表段（组内 `partIndex` 最小者，即首段）：组级动作与显示字段全取它
 * @property parts 升序成员；单片时是只含自己的单元素列表
 */
data class GalleryEntry(
    val clip: VideoClip,
    val seriesId: Long?,
    val rep: VideoClip,
    val parts: List<VideoClip>
) {
    /** 组总时长（单片即它自己） */
    val totalDurationMs: Long get() = parts.sumOf { it.durationMs }

    /** 组标题：代表段（首段）的名字——不发明新名，用户认得出这是哪一次录制 */
    val title: String get() = rep.name

    /**
     * 组内段数；单片返回 null（UI 据此决定显不显示角标）。
     *
     * 只给**数字**不给成句文案：中文文案必须走 `stringResource`（见 `strings_gallery.xml`），
     * 仓库层不许出现面向用户的中文串（与 `groupTimeline` 同一纪律）。
     */
    val seriesCount: Int?
        get() = if (seriesId == null || parts.size < MIN_GROUP_SIZE) null else parts.size
}

/** 归组判定要用的三个字段（把 Android 类型挡在纯逻辑之外，JVM 单测才跑得动） */
internal data class SeriesKey(
    val id: Long,
    val seriesId: Long?,
    val partIndex: Int?
)

/** 一条 entry 覆盖输入列表的哪几项（`indices` 已按 `partIndex` 升序排好） */
internal data class GroupSpan(val seriesId: Long?, val indices: List<Int>)

/**
 * 分段系列的归组（#5 Step 4c）。纯逻辑，不碰 Room / Compose / Context。
 *
 * # 分组维度
 * `seriesId`，且**仅当可聚合到 ≥ [MIN_GROUP_SIZE] 个可见成员时**才成组。
 * 成员关系是「剩下的成员」上的关系：`All/Like/Tagged/Trash` 各页签只负责过滤成员，
 * 不加任何特例；某段被单独删 / 回收导致只剩 1 段时，这一组**自动退化成单条**。
 *
 * # 代表段
 * `partIndex` 最小者（首段）。动作扇出、显示字段全取代表段：因为组级动作对全部 parts
 * 生效，全体成员保持一致，`rep` 就是真实的组态。只有「展开后单独改某一段」会分叉——
 * 那时卡片仍显示首段（有据可依，不编造聚合值）。
 *
 * # 顺序
 * 严格保持输入顺序：一条 entry 的站位取**该组成员在输入里首次出现的那个位置**。
 * 媒体库按 `DATE_ADDED DESC` 排，同一次录制的各段本来就连在一起，
 * 稳定排序才不会让卡片在每次重算时跳位。
 *
 * # 「合并」的含义
 * `collapse = true`（默认）是**视觉折叠成 1 个 entry**，**不是**媒体字节拼接——
 * 把多段 MP4 合成一个文件要解码 + 重编码时间线，本仓没有这个能力，属另一个新功能。
 * `collapse = false` 返回与输入逐字等价的全单条列表，那就是今天扁平列表的基线，
 * 也是回退开关。
 */
object SeriesGroup {

    /** 可聚合到这么多个可见成员才成组 */
    const val MIN_GROUP_SIZE = 2

    /**
     * 媒体库条目 → 展示单元。薄适配层：真正的规则在 [groupSpans]（纯逻辑、无 Android 类型）。
     */
    fun galleryEntries(clips: List<VideoClip>, collapse: Boolean = true): List<GalleryEntry> =
        groupSpans(clips.map { SeriesKey(it.id, it.seriesId, it.partIndex) }, collapse).map { span ->
            val members = span.indices.map { clips[it] }
            val rep = members.first()
            GalleryEntry(clip = rep, seriesId = span.seriesId, rep = rep, parts = members)
        }

    /**
     * 归组的纯核心：把键序列切成若干 [GroupSpan]。
     *
     * 抽出「只吃 [SeriesKey]、只吐索引」这一层，是因为 [VideoClip] 里躺着 `android.net.Uri`，
     * 而本仓的 JVM 单测没有 Robolectric/mockk（android.jar 的桩里 `Uri.EMPTY` 是 null、
     * 构造器直接抛 `Stub!`）⇒ 直接在 [VideoClip] 上写真值表根本跑不起来。
     */
    internal fun groupSpans(keys: List<SeriesKey>, collapse: Boolean = true): List<GroupSpan> {
        if (keys.isEmpty()) return emptyList()
        // 不折叠 = 今天的扁平列表基线：一条不折、顺序逐字不变（回退开关）
        if (!collapse) return keys.indices.map { GroupSpan(null, listOf(it)) }

        // 按 seriesId 归堆；LinkedHashMap 保证首见即定序
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        keys.forEachIndexed { idx, k ->
            val sid = k.seriesId ?: return@forEachIndexed
            groups.getOrPut(sid) { ArrayList() } += idx
        }

        val out = ArrayList<GroupSpan>(keys.size)
        val emitted = HashSet<Long>()
        keys.forEachIndexed { idx, k ->
            val sid = k.seriesId
            // 无归属的片：各自成一条
            if (sid == null) {
                out += GroupSpan(null, listOf(idx))
                return@forEachIndexed
            }
            // 这一组已经在首见位整体处理过了，后面的成员不再单独出条
            if (!emitted.add(sid)) return@forEachIndexed

            val members = groups[sid].orEmpty()
            if (members.size < MIN_GROUP_SIZE) {
                // 组内只剩 1 个可见成员 ⇒ 退化成单条（逐个出，各占各的站位）
                members.forEach { out += GroupSpan(null, listOf(it)) }
                return@forEachIndexed
            }
            // 脏数据（partIndex 为 null）排到末尾，不许因此把整组打散
            val ordered = members.sortedBy { keys[it].partIndex ?: Int.MAX_VALUE }
            out += GroupSpan(sid, ordered)
        }
        return out
    }

    /**
     * **组级动作扇出**：把选中的片展开成「要真正动到的那些片」。
     *
     * 多选里点中的是代表段，但分享 / 收藏 / 移入回收站 / 还原 / 彻底删除都必须落到该系列的
     * **每一个可见成员**上 —— 否则会出现「只把首段移进回收站、其余段还在原地」的半截状态。
     *
     * 只在**确实成组**（成员 ≥ [MIN_GROUP_SIZE]）时展开：退化成单片的那一组没有别的成员可动。
     * 去重靠 `distinct()`：同组的两个成员都被选中时不该动两遍。
     *
     * @param picked 选中的片（通常是代表段）
     * @param all 当前可见的全部片（成员关系的来源）
     */
    fun expandSeriesTargets(picked: List<VideoClip>, all: List<VideoClip>): List<VideoClip> {
        if (picked.isEmpty() || all.isEmpty()) return picked
        val members = HashMap<Long, MutableList<VideoClip>>()
        all.filter { it.seriesId != null }.forEach { members.getOrPut(it.seriesId!!) { ArrayList() } += it }
        if (members.isEmpty()) return picked
        return picked.flatMap { c ->
            val sid = c.seriesId
            val group = if (sid == null) null else members[sid]
            if (group != null && group.size >= MIN_GROUP_SIZE) group else listOf(c)
        }.distinct()
    }
}
