import Foundation

/// 归组判定要用的三个字段（把条目其余字段挡在纯逻辑之外）。
public struct MediaSeriesKey {
    public let id: Int64
    public let seriesId: Int64?
    public let partIndex: Int?
    public init(id: Int64, seriesId: Int64?, partIndex: Int?) {
        self.id = id
        self.seriesId = seriesId
        self.partIndex = partIndex
    }
}

/// 一条 entry 覆盖输入列表的哪几项（`indices` 已按 `partIndex` 升序排好）。
public struct MediaGroupSpan {
    public let seriesId: Int64?
    public let indices: [Int]
    public init(seriesId: Int64?, indices: [Int]) {
        self.seriesId = seriesId
        self.indices = indices
    }
}

/// 媒体库的一**条**展示单元：单个片，或一次录制归成的系列组。
public struct MediaGalleryEntry {
    public let clip: MediaItem
    public let seriesId: Int64?
    public let rep: MediaItem
    public let parts: [MediaItem]

    /// 组总时长（单片即它自己）。
    public var totalDurationMs: Int64 { parts.reduce(0) { $0 + $1.durationMs } }

    /// 组标题：代表段（首段）的名字。
    public var title: String { rep.fileName }

    /// 组内段数；单片返回 nil（UI 据此决定显不显示角标）。
    public var seriesCount: Int? {
        if seriesId == nil || parts.count < MediaSeriesGrouper.minGroupSize { return nil }
        return parts.count
    }
}

/// 分段系列的归组，与 Android `SeriesGroup` 对齐。纯逻辑，不碰文件系统 / UI。
///
/// 分组维度：`seriesId`，且仅当可聚合到 ≥ `minGroupSize` 个可见成员时才成组。
/// 代表段：`partIndex` 最小者（首段）。顺序：严格保持输入顺序，一条 entry 的站位取该组成员
/// 在输入里首次出现的位置（媒体库按时间倒序排，同一次录制的各段本就相邻）。
/// 「合并」= 视觉折叠成 1 个 entry，**不是**媒体字节拼接。
public enum MediaSeriesGrouper {

    /// 可聚合到这么多个可见成员才成组。
    public static let minGroupSize = 2

    /// 媒体库条目 → 展示单元。
    public static func galleryEntries(_ items: [MediaItem], collapse: Bool = true) -> [MediaGalleryEntry] {
        let keys = items.map { MediaSeriesKey(id: $0.mediaId, seriesId: $0.seriesId, partIndex: $0.partIndex) }
        return groupSpans(keys, collapse: collapse).map { span in
            let members = span.indices.map { items[$0] }
            let rep = members.first!
            return MediaGalleryEntry(clip: rep, seriesId: span.seriesId, rep: rep, parts: members)
        }
    }

    /// 归组的纯核心：把键序列切成若干 `MediaGroupSpan`。
    public static func groupSpans(_ keys: [MediaSeriesKey], collapse: Bool = true) -> [MediaGroupSpan] {
        if keys.isEmpty { return [] }
        // 不折叠 = 扁平列表基线：一条不折、顺序逐字不变（回退开关）。
        if !collapse {
            return keys.indices.map { MediaGroupSpan(seriesId: nil, indices: [$0]) }
        }

        // 按 seriesId 归堆；首见即定序。
        var groups: [Int64: [Int]] = [:]
        var groupOrder: [Int64] = []
        for (idx, key) in keys.enumerated() {
            guard let sid = key.seriesId else { continue }
            if groups[sid] == nil {
                groups[sid] = []
                groupOrder.append(sid)
            }
            groups[sid]!.append(idx)
        }

        var out: [MediaGroupSpan] = []
        out.reserveCapacity(keys.count)
        var emitted = Set<Int64>()
        for (idx, key) in keys.enumerated() {
            guard let sid = key.seriesId else {
                out.append(MediaGroupSpan(seriesId: nil, indices: [idx]))
                continue
            }
            // 这一组已在首见位整体处理过了。
            if emitted.contains(sid) { continue }
            emitted.insert(sid)

            let members = groups[sid] ?? []
            if members.count < minGroupSize {
                // 组内只剩 1 个可见成员 ⇒ 退化成单条。
                for member in members { out.append(MediaGroupSpan(seriesId: nil, indices: [member])) }
                continue
            }
            // 脏数据（partIndex 为 nil）排到末尾，不许因此把整组打散。
            let ordered = members.sorted { lhs, rhs in
                let l = keys[lhs].partIndex ?? Int.max
                let r = keys[rhs].partIndex ?? Int.max
                if l != r { return l < r }
                return lhs < rhs
            }
            out.append(MediaGroupSpan(seriesId: sid, indices: ordered))
        }
        return out
    }

    /// 组级动作扇出：把选中的片展开成「要真正动到的那些片」（分享/收藏/回收/还原/彻底删除）。
    ///
    /// 只在确实成组（成员 ≥ minGroupSize）时展开；退化成单片的那一组没有别的成员可动。
    /// 去重靠 id：同组的两个成员都被选中时不该动两遍。
    public static func expandSeriesTargets(picked: [MediaItem], all: [MediaItem]) -> [MediaItem] {
        if picked.isEmpty || all.isEmpty { return picked }
        var members: [Int64: [MediaItem]] = [:]
        for item in all {
            if let sid = item.seriesId { members[sid, default: []].append(item) }
        }
        if members.isEmpty { return picked }
        var expanded: [MediaItem] = []
        var seen = Set<String>()
        for clip in picked {
            var bucket: [MediaItem] = [clip]
            if let sid = clip.seriesId, let group = members[sid], group.count >= minGroupSize {
                bucket = group
            }
            for item in bucket where !seen.contains(item.identityKey) {
                seen.insert(item.identityKey)
                expanded.append(item)
            }
        }
        return expanded
    }
}
