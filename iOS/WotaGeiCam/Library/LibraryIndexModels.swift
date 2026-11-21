import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 索引里的媒体身份行：稳定 `mediaId` ↔ 物理文件的绑定 + 已探测元数据缓存。
///
/// Android 侧媒体身份由 MediaStore 行提供（`_ID` + DATA/SIZE/DURATION…）；iOS 无 MediaStore，
/// 改为在自有索引里保存身份行。`sizeBytes` + `modifiedAtMs` 用来判定缓存是否过期
/// （文件被外部替换/改动时重新探测，避免缓存漂移）。
struct LibraryIdentity {
    var mediaId: Int64
    var relativeDirectory: String
    var fileName: String
    var sizeBytes: Int64
    var durationMs: Int64
    var width: Int
    var height: Int
    var addedAtMs: Int64
    var modifiedAtMs: Int64

    var relativePath: String { relativeDirectory + "/" + fileName }

    func dictionary() -> [String: Any] {
        return [
            "mediaId": NSNumber(value: mediaId),
            "relativeDirectory": relativeDirectory,
            "fileName": fileName,
            "sizeBytes": NSNumber(value: sizeBytes),
            "durationMs": NSNumber(value: durationMs),
            "width": NSNumber(value: width),
            "height": NSNumber(value: height),
            "addedAtMs": NSNumber(value: addedAtMs),
            "modifiedAtMs": NSNumber(value: modifiedAtMs)
        ]
    }

    static func from(_ dict: [String: Any]) -> LibraryIdentity? {
        guard let mediaId = LibraryIndexJSON.int64(dict["mediaId"]),
              let relativeDirectory = dict["relativeDirectory"] as? String,
              let fileName = dict["fileName"] as? String else { return nil }
        return LibraryIdentity(
            mediaId: mediaId,
            relativeDirectory: relativeDirectory,
            fileName: fileName,
            sizeBytes: LibraryIndexJSON.int64(dict["sizeBytes"]) ?? 0,
            durationMs: LibraryIndexJSON.int64(dict["durationMs"]) ?? 0,
            width: LibraryIndexJSON.int(dict["width"]) ?? 0,
            height: LibraryIndexJSON.int(dict["height"]) ?? 0,
            addedAtMs: LibraryIndexJSON.int64(dict["addedAtMs"]) ?? 0,
            modifiedAtMs: LibraryIndexJSON.int64(dict["modifiedAtMs"]) ?? 0
        )
    }
}

/// 标签记录（收藏与自定义分类共用一张表），与 Android `MediaTag` 对齐。
/// 清理链路按 `mediaId` 走；`path` 同时用于受控改名时的身份核对。
struct LibraryTag {
    var mediaId: Int64
    var path: String
    var tag: String
    var createdAtMs: Int64

    func dictionary() -> [String: Any] {
        return [
            "mediaId": NSNumber(value: mediaId),
            "path": path,
            "tag": tag,
            "createdAtMs": NSNumber(value: createdAtMs)
        ]
    }

    static func from(_ dict: [String: Any]) -> LibraryTag? {
        guard let mediaId = LibraryIndexJSON.int64(dict["mediaId"]),
              let tag = dict["tag"] as? String else { return nil }
        return LibraryTag(
            mediaId: mediaId,
            path: (dict["path"] as? String) ?? "",
            tag: tag,
            createdAtMs: LibraryIndexJSON.int64(dict["createdAtMs"]) ?? 0
        )
    }
}

/// 本地回收站行（App 自有目录回收站），与 Android `TrashItem` 语义对齐（API29 回退路径口径）：
/// 只记一份「已回收」账，**文件仍在原地**，从常规页签隐藏；还原 = 删该行（不覆盖任何文件）。
struct LibraryTrashItem {
    /// 主键：相对路径（`Movies/WotaGeiCam/Camera/xxx.mp4`）。
    var relativePath: String
    var mediaId: Int64
    var name: String
    var deletedAtMs: Int64
    /// 原相对目录。
    var origin: String

    func dictionary() -> [String: Any] {
        return [
            "relativePath": relativePath,
            "mediaId": NSNumber(value: mediaId),
            "name": name,
            "deletedAtMs": NSNumber(value: deletedAtMs),
            "origin": origin
        ]
    }

    static func from(_ dict: [String: Any]) -> LibraryTrashItem? {
        guard let relativePath = dict["relativePath"] as? String else { return nil }
        return LibraryTrashItem(
            relativePath: relativePath,
            mediaId: LibraryIndexJSON.int64(dict["mediaId"]) ?? 0,
            name: (dict["name"] as? String) ?? "",
            deletedAtMs: LibraryIndexJSON.int64(dict["deletedAtMs"]) ?? 0,
            origin: (dict["origin"] as? String) ?? ""
        )
    }
}

/// 分段录制系列头，与 Android `MediaSeries` 对齐。
struct LibrarySeries {
    var seriesId: Int64
    var startedAtMs: Int64
    var partCount: Int

    func dictionary() -> [String: Any] {
        return [
            "seriesId": NSNumber(value: seriesId),
            "startedAtMs": NSNumber(value: startedAtMs),
            "partCount": NSNumber(value: partCount)
        ]
    }

    static func from(_ dict: [String: Any]) -> LibrarySeries? {
        guard let seriesId = LibraryIndexJSON.int64(dict["seriesId"]) else { return nil }
        return LibrarySeries(
            seriesId: seriesId,
            startedAtMs: LibraryIndexJSON.int64(dict["startedAtMs"]) ?? 0,
            partCount: LibraryIndexJSON.int(dict["partCount"]) ?? 0
        )
    }
}

/// 分片归属行，与 Android `SeriesPart` 对齐。
struct LibrarySeriesPart {
    var seriesId: Int64
    var partIndex: Int
    var mediaId: Int64
    var path: String
    var durationMs: Int64

    func dictionary() -> [String: Any] {
        return [
            "seriesId": NSNumber(value: seriesId),
            "partIndex": NSNumber(value: partIndex),
            "mediaId": NSNumber(value: mediaId),
            "path": path,
            "durationMs": NSNumber(value: durationMs)
        ]
    }

    static func from(_ dict: [String: Any]) -> LibrarySeriesPart? {
        guard let seriesId = LibraryIndexJSON.int64(dict["seriesId"]),
              let partIndex = LibraryIndexJSON.int(dict["partIndex"]) else { return nil }
        return LibrarySeriesPart(
            seriesId: seriesId,
            partIndex: partIndex,
            mediaId: LibraryIndexJSON.int64(dict["mediaId"]) ?? 0,
            path: (dict["path"] as? String) ?? "",
            durationMs: LibraryIndexJSON.int64(dict["durationMs"]) ?? 0
        )
    }
}

/// 索引整体状态（与 Android Room 四张表 + `compare_history` 单键对应）。
struct LibraryIndexState {
    var identities: [LibraryIdentity] = []
    var tags: [LibraryTag] = []
    var trash: [LibraryTrashItem] = []
    var series: [LibrarySeries] = []
    var seriesParts: [LibrarySeriesPart] = []
    var compareHistory: [MediaCompareEntry] = []
    /// 下一个可分配的稳定 mediaId（单调递增，从 1 起；0 保留给外来来源）。
    var nextMediaId: Int64 = 1

    static let empty = LibraryIndexState()

    func dictionary() -> [String: Any] {
        return [
            "version": NSNumber(value: 1),
            "identities": identities.map { $0.dictionary() },
            "tags": tags.map { $0.dictionary() },
            "trash": trash.map { $0.dictionary() },
            "series": series.map { $0.dictionary() },
            "seriesParts": seriesParts.map { $0.dictionary() },
            "compareHistory": LibraryIndexJSON.compareArray(compareHistory),
            "nextMediaId": NSNumber(value: nextMediaId)
        ]
    }

    static func from(_ object: Any) -> LibraryIndexState? {
        guard let dict = object as? [String: Any] else { return nil }
        var state = LibraryIndexState()
        state.identities = (dict["identities"] as? [Any])?.compactMap {
            ($0 as? [String: Any]).flatMap(LibraryIdentity.from)
        }?.filter { $0.mediaId > 0 && $0.mediaId < Int64.max } ?? []
        state.tags = (dict["tags"] as? [Any])?.compactMap {
            ($0 as? [String: Any]).flatMap(LibraryTag.from)
        } ?? []
        state.trash = (dict["trash"] as? [Any])?.compactMap {
            ($0 as? [String: Any]).flatMap(LibraryTrashItem.from)
        } ?? []
        state.series = (dict["series"] as? [Any])?.compactMap {
            ($0 as? [String: Any]).flatMap(LibrarySeries.from)
        } ?? []
        state.seriesParts = (dict["seriesParts"] as? [Any])?.compactMap {
            ($0 as? [String: Any]).flatMap(LibrarySeriesPart.from)
        } ?? []
        state.compareHistory = LibraryIndexJSON.compareEntries(dict["compareHistory"])
        state.nextMediaId = LibraryIndexJSON.int64(dict["nextMediaId"]) ?? 1
        if state.nextMediaId < 1 { state.nextMediaId = 1 }
        // 防御：把 nextMediaId 顶到已有 id 之上，避免手改/坏档导致 id 复用。
        // `maxId + 1` 只在安全范围内计算；真正分配时（adopt）还会再挡一次 Int64.max。
        if let maxId = state.identities.map({ $0.mediaId }).max(),
           maxId < Int64.max, state.nextMediaId <= maxId {
            state.nextMediaId = maxId + 1
        }
        return state
    }
}

/// JSON 辅助（不用 `Codable`，iOS 11 才有）。
enum LibraryIndexJSON {

    static func int64(_ value: Any?) -> Int64? {
        if let number = value as? NSNumber { return number.int64Value }
        if let string = value as? String { return Int64(string) }
        return nil
    }

    static func int(_ value: Any?) -> Int? {
        if let number = value as? NSNumber { return number.intValue }
        if let string = value as? String { return Int(string) }
        return nil
    }

    static func compareArray(_ entries: [MediaCompareEntry]) -> [[String: Any]] {
        return entries.map { entry in
            var dict: [String: Any] = [
                "rightId": NSNumber(value: entry.rightId),
                "leftId": NSNumber(value: entry.leftId),
                "ts": NSNumber(value: entry.timestampMs)
            ]
            if let uri = entry.rightUri, !uri.isEmpty { dict["rightUri"] = uri }
            return dict
        }
    }

    static func compareEntries(_ value: Any?) -> [MediaCompareEntry] {
        guard let array = value as? [Any] else { return [] }
        var out: [MediaCompareEntry] = []
        for element in array {
            guard let dict = element as? [String: Any] else { continue }
            guard let leftId = int64(dict["leftId"]), let ts = int64(dict["ts"]) else { continue }
            let rightId = int64(dict["rightId"]) ?? 0
            let rightUri = (dict["rightUri"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            if rightId <= 0 && (rightUri?.isEmpty ?? true) { continue }
            out.append(MediaCompareEntry(rightId: rightId, rightUri: rightUri, leftId: leftId, timestampMs: ts))
        }
        return out
    }
}
