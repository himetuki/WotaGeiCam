import Foundation

/// 双视频对比的一条历史记录，与 Android `CompareHistory.Entry` 对齐。
///
/// 右侧视频身份：库内条目用 `rightId`（>0），外来来源（照片选择器/文档 provider）用 `rightUri`。
/// 去重/删除键按命名空间隔离：`media:<id>` 与 `uri:<uri>`，避免同数字 id 与 uri 条目互相覆盖。
public struct MediaCompareEntry {
    public var rightId: Int64
    public var rightUri: String?
    public var leftId: Int64
    public var timestampMs: Int64

    public init(rightId: Int64, rightUri: String?, leftId: Int64, timestampMs: Int64) {
        self.rightId = rightId
        self.rightUri = rightUri
        self.leftId = leftId
        self.timestampMs = timestampMs
    }

    public var key: String {
        if rightId > 0 { return "media:\(rightId)" }
        return "uri:\(rightUri ?? "")"
    }
}

/// 对比历史的编解码与增删（纯逻辑），与 Android `CompareHistory` 对齐。
///
/// 结构化数据整表编成一段 JSON 串（键内容即契约：`[{"rightId":..,"rightUri":"..","leftId":..,"ts":..}]`，
/// 队首最新）。iOS 侧不用 `Codable`（iOS 11 才有），一律走 `JSONSerialization` + 手写字典。
public enum MediaCompareHistory {

    /// 历史上限：低频便利功能，最新 12 条足够回看。
    public static let maxEntries = 12

    /// 读全部记录（队首最新）。容错：缺串/整串解析失败返回空表；单条缺 `leftId`/`ts` 或类型不对的坏行跳过不抛。
    public static func decode(_ raw: String?) -> [MediaCompareEntry] {
        guard let raw = raw, let data = raw.data(using: .utf8) else { return [] }
        guard let object = try? JSONSerialization.jsonObject(with: data, options: []),
              let array = object as? [Any] else {
            return []
        }
        var out: [MediaCompareEntry] = []
        out.reserveCapacity(array.count)
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

    /// 编码为 JSON 串；失败返回 nil（调用方保留旧值，不写入坏串）。
    public static func encode(_ entries: [MediaCompareEntry]) -> String? {
        var array: [[String: Any]] = []
        array.reserveCapacity(entries.count)
        for entry in entries {
            var dict: [String: Any] = ["rightId": NSNumber(value: entry.rightId)]
            // 外来来源才写 uri；库内条目省写，只认 rightId 的旧读取方也能照旧消费。
            if let uri = entry.rightUri, !uri.isEmpty {
                dict["rightUri"] = uri
            }
            dict["leftId"] = NSNumber(value: entry.leftId)
            dict["ts"] = NSNumber(value: entry.timestampMs)
            array.append(dict)
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array, options: []) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 记一次对照。右侧身份取 `rightId`（库内）或 `rightUri`（外来），两者都空时不记。
    ///
    /// 按 `key` 去重：同一右侧视频无论之前搭过谁、记过几次，都只在队首留一条（更新 leftId + ts 后插队首）；
    /// 新条目同样插队首。超过 `maxEntries` 从队尾截断。
    public static func record(
        _ entries: [MediaCompareEntry],
        rightId: Int64,
        rightUri: String?,
        leftId: Int64,
        nowMs: Int64
    ) -> [MediaCompareEntry] {
        if rightId <= 0 && (rightUri?.isEmpty ?? true) { return entries }
        let entry = MediaCompareEntry(
            rightId: rightId,
            rightUri: (rightUri?.isEmpty ?? true) ? nil : rightUri,
            leftId: leftId,
            timestampMs: nowMs
        )
        var next = entries.filter { $0.key != entry.key }
        next.insert(entry, at: 0)
        if next.count > maxEntries { next = Array(next.prefix(maxEntries)) }
        return next
    }

    /// 按 `key` 摘掉一条（没有该条即无操作）。
    public static func remove(_ entries: [MediaCompareEntry], key: String) -> [MediaCompareEntry] {
        entries.filter { $0.key != key }
    }

    private static func int64(_ value: Any?) -> Int64? {
        if let number = value as? NSNumber { return number.int64Value }
        if let string = value as? String { return Int64(string) }
        return nil
    }
}
