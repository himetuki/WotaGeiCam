import Foundation

/// 媒体库数据范围：本应用目录（`Movies/WotaGeiCam`）与手机相册（全量视频）。
/// 与 Android `MediaScope` 对齐；iOS 侧 `allVideos` 只用于展示 App 自有目录之外的本机视频，
/// 不改变「App 自有目录才是可写媒体」的约束。
public enum MediaScope {
    case wotaLibrary
    case allVideos
}

/// 列表形态：时间线 / 网格 / 全屏滑动，与 Android `ListMode` 对齐。
public enum MediaListMode {
    case timeline
    case grid
    case fullscreen
}

/// 顶部页签过滤条件。页签顺序定版：全部 → 收藏 → 回收站 → 各自定义 tag。
public enum MediaClipFilter: Equatable {
    case all
    case like
    case trash
    case tagged(String)
}

/// 标签空间，与 Android `TagSpace` 对齐：系统保留 tag（like/trash）+ 用户自定义 tag。
///
/// 系统 tag 只有两个；自定义 tag 直接存原文（数量不限）。
public enum MediaTagSpace {
    public static let like = "like"
    public static let trash = "trash"

    public static let system: Set<String> = [like, trash]

    public static func isSystem(_ tag: String) -> Bool { system.contains(tag) }

    public static func isCustom(_ tag: String) -> Bool { !system.contains(tag) }

    /// 自定义 tag 合法性：去空白后非空、不含保留词（大小写无关）、长度受限（≤24，避免药丸换行炸布局）。
    ///
    /// 与 Android `TagSpace.normalize` 逐条对齐；非法返回 nil，不静默截断。
    public static func normalize(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.count > 24 { return nil }
        if system.contains(where: { $0.caseInsensitiveCompare(trimmed) == .orderedSame }) { return nil }
        return trimmed
    }
}

/// 媒体身份指纹（纯值）：目录 + 文件名 + 大小 + 时长。
///
/// Android 侧媒体身份键用指纹（relativePath/name/size/duration），而不是 MediaStore `_ID`
/// （备份还原后按指纹重绑）。iOS 无 MediaStore，本结构是**唯一**的跨重启身份口径，
/// 索引里的稳定 `mediaId` 由它派生并持久保存。
public struct MediaFingerprint: Hashable {
    public var relativeDirectory: String
    public var fileName: String
    public var sizeBytes: Int64
    public var durationMs: Int64

    public init(relativeDirectory: String, fileName: String, sizeBytes: Int64, durationMs: Int64) {
        self.relativeDirectory = relativeDirectory
        self.fileName = fileName
        self.sizeBytes = sizeBytes
        self.durationMs = durationMs
    }

    /// 稳定字符串键（用于索引里 id ↔ 指纹的重绑，不写 JSON 的复杂结构）。
    public var key: String {
        "\(relativeDirectory)/\(fileName)|\(sizeBytes)|\(durationMs)"
    }
}

/// 媒体库条目。
///
/// 字段刻意与 Android `VideoClip` 对齐，但**不携带** `android.net.Uri`：iOS 侧条目由
/// App 自有目录扫描得到，`fileName` + `relativeDirectory` 即物理定位。
/// `mediaId` 语义与 Android 一致：库内条目为持久化稳定 id（>0）；外来来源（照片选择器、
/// 文档 provider）为 `0`，只用于对比右槽播放/历史，**不得进入媒体库列表**。
public struct MediaItem {

    /// 稳定媒体 id；0 = 非本机库内条目（外来来源，不可进入列表）。
    public var mediaId: Int64
    public var fileName: String
    /// 相对 App 容器（Documents）的目录，如 `Movies/WotaGeiCam/Camera`。
    public var relativeDirectory: String
    /// 绝对路径（App 沙盒内）；外来来源可为 nil。
    public var absolutePath: String?
    public var sizeBytes: Int64
    public var durationMs: Int64
    public var width: Int
    public var height: Int
    public var mimeType: String
    /// 入库时刻（epoch ms）；Android 用 MediaStore DATE_ADDED。
    public var createdAtMs: Int64
    public var tags: Set<String>
    public var isTrashed: Bool
    /// 分段归组：所属系列 id；nil = 单段片。
    public var seriesId: Int64?
    /// 组内段序。
    public var partIndex: Int?
    /// 该系列在库里的总段数（默认 1，单段片语义与改动前逐字相同）。
    public var seriesParts: Int

    public init(
        mediaId: Int64,
        fileName: String,
        relativeDirectory: String,
        absolutePath: String?,
        sizeBytes: Int64,
        durationMs: Int64,
        width: Int,
        height: Int,
        mimeType: String,
        createdAtMs: Int64,
        tags: Set<String>,
        isTrashed: Bool,
        seriesId: Int64? = nil,
        partIndex: Int? = nil,
        seriesParts: Int = 1
    ) {
        self.mediaId = mediaId
        self.fileName = fileName
        self.relativeDirectory = relativeDirectory
        self.absolutePath = absolutePath
        self.sizeBytes = sizeBytes
        self.durationMs = durationMs
        self.width = width
        self.height = height
        self.mimeType = mimeType
        self.createdAtMs = createdAtMs
        self.tags = tags
        self.isTrashed = isTrashed
        self.seriesId = seriesId
        self.partIndex = partIndex
        self.seriesParts = seriesParts
    }

    public var liked: Bool { tags.contains(MediaTagSpace.like) }

    /// Android `VideoClip.relativePath` 的等价物（目录 + `/`）。
    public var relativePath: String { relativeDirectory + "/" }

    public var fingerprint: MediaFingerprint {
        MediaFingerprint(
            relativeDirectory: relativeDirectory,
            fileName: fileName,
            sizeBytes: sizeBytes,
            durationMs: durationMs
        )
    }

    /// 唯一键：库内按 id，库外按绝对路径。
    public var identityKey: String {
        mediaId > 0 ? "media:\(mediaId)" : "path:\(absolutePath ?? "\(relativeDirectory)/\(fileName)")"
    }
}

/// 页签过滤（纯函数，与 Android `MediaRepo.filterByTab` 同口径）：
/// 回收站条目不出现在全部/收藏/自定义页签，常规条目不出现在回收站页签。
public enum MediaClipFiltering {
    public static func apply(_ items: [MediaItem], filter: MediaClipFilter) -> [MediaItem] {
        switch filter {
        case .all:
            return items.filter { !$0.isTrashed }
        case .like:
            return items.filter { !$0.isTrashed && $0.liked }
        case .trash:
            return items.filter { $0.isTrashed }
        case .tagged(let tag):
            return items.filter { !$0.isTrashed && $0.tags.contains(tag) }
        }
    }
}
