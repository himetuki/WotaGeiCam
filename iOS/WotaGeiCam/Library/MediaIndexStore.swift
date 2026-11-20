import Foundation

/// 索引读写错误。
enum MediaIndexError: Error {
    case persistFailed(String)
}

/// 媒体库索引存储（JSON 单文件，原子写）。
///
/// 对应 Android Room `wota_media.db`，但换成单文件 JSON：
/// - **原子写**：`Data.write(options: .atomic)` 先写同目录临时文件再替换，避免半截文件；
/// - **坏索引不误删媒体**：解析失败时把坏文件**改名备份**（`wota_media_index.corrupt-<ts>.json`）
///   后从空索引重建；媒体文件一个都不动；
/// - **文件成功才更新索引**：`apply` 先应用变更再落盘，落盘失败回滚内存状态并抛错（不伪更新）。
///
/// 所有访问由调用方 `MediaLibrary` 的串行队列串行化；`NSLock` 只做额外保险。
final class MediaIndexStore {

    private let paths: MediaLibraryPaths
    private let lock = NSLock()
    private var state: LibraryIndexState = .empty
    private var loaded = false

    init(paths: MediaLibraryPaths) {
        self.paths = paths
    }

    /// 读当前索引快照（首次调用从磁盘加载一次）。
    func currentState() -> LibraryIndexState {
        lock.lock()
        defer { lock.unlock() }
        loadLockedIfNeeded()
        return state
    }

    /// 应用一次变更并原子落盘。落盘失败则回滚内存状态并抛错。
    func apply(_ transform: (inout LibraryIndexState) -> Void) throws {
        lock.lock()
        defer { lock.unlock() }
        loadLockedIfNeeded()
        let previous = state
        var next = state
        transform(&next)
        do {
            try persistLocked(next)
        } catch {
            state = previous
            MediaLibraryLog.error("索引落盘失败，已回滚内存状态：\(error)")
            throw error
        }
        state = next
    }

    // MARK: - 私有（持锁）

    private func loadLockedIfNeeded() {
        if loaded { return }
        loaded = true
        let url = paths.indexFileURL
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: url.path) else {
            state = .empty
            return
        }
        do {
            let data = try Data(contentsOf: url)
            let object = try JSONSerialization.jsonObject(with: data, options: [])
            if let parsed = LibraryIndexState.from(object) {
                state = parsed
            } else {
                backUpCorruptIndex(reason: "顶层结构不符")
                state = .empty
            }
        } catch {
            // 坏索引：改名备份后从空索引重建；媒体文件不动。
            backUpCorruptIndex(reason: "\(error)")
            state = .empty
        }
    }

    private func persistLocked(_ state: LibraryIndexState) throws {
        let fileManager = FileManager.default
        let directory = paths.privateDirectoryURL
        if !fileManager.fileExists(atPath: directory.path) {
            do {
                try fileManager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
            } catch {
                throw MediaIndexError.persistFailed("创建索引目录失败：\(error)")
            }
        }
        let data: Data
        do {
            data = try JSONSerialization.data(withJSONObject: state.dictionary(), options: [])
        } catch {
            throw MediaIndexError.persistFailed("索引序列化失败：\(error)")
        }
        do {
            try data.write(to: paths.indexFileURL, options: .atomic)
        } catch {
            throw MediaIndexError.persistFailed("索引写入失败：\(error)")
        }
    }

    private func backUpCorruptIndex(reason: String) {
        let fileManager = FileManager.default
        let url = paths.indexFileURL
        guard fileManager.fileExists(atPath: url.path) else { return }
        let backup = paths.corruptIndexBackupURL(timestampMs: MediaIndexStore.currentTimeMilliseconds())
        do {
            if fileManager.fileExists(atPath: backup.path) {
                try fileManager.removeItem(at: backup)
            }
            try fileManager.moveItem(at: url, to: backup)
            MediaLibraryLog.error("索引损坏（\(reason)），已备份到 \(backup.lastPathComponent) 并从空索引重建；媒体文件未改动")
        } catch {
            MediaLibraryLog.error("索引损坏且备份失败（\(reason)）：\(error)")
        }
    }

    static func currentTimeMilliseconds() -> Int64 {
        return Int64((Date().timeIntervalSince1970 * 1000).rounded(.down))
    }
}
