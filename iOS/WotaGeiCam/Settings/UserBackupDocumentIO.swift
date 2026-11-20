import Foundation

#if canImport(WotaCore)
import WotaCore
#endif

/// 备份快照的**本地文件 IO**（显式导出/导入；无自动上传、无自动联网）。
///
/// 数据格式与语义都在 `WotaCore.WotaUserBackup`（文件名 / VERSION=1 / 4MiB 上限 / 键白名单 /
/// additive 不覆盖）。这里只负责把它落到沙盒临时文件、或从用户选择的文件读回。
enum UserBackupDocumentIO {

    /// 导出目录（App 沙盒内临时目录；不向系统盘/工程目录写文件）。
    private static var exportDirectory: URL {
        let base = FileManager.default.temporaryDirectory
            .appendingPathComponent("WotaGeiCamBackup", isDirectory: true)
        if !FileManager.default.fileExists(atPath: base.path) {
            try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true, attributes: nil)
        }
        return base
    }

    /// 从存储导出快照并写入临时文件，返回文件 URL（失败返回 nil）。
    /// `tags` 由调用方提供（当前无媒体库实现时传空数组）。
    ///
    /// `Data.write(options: .atomic)` 先写同目录临时文件再原子替换，不会留下半份快照；
    /// 也不再先删旧文件（那会制造“旧文件已删、新文件未写”的空窗）。
    static func exportToFile(store: WotaSettingsStore, tags: [WotaUserBackup.Tag] = []) -> URL? {
        let snapshot = WotaUserBackup.snapshot(from: store, tags: tags)
        guard let data = try? WotaUserBackup.encode(snapshot) else { return nil }
        let url = exportDirectory.appendingPathComponent(WotaUserBackup.fileName)
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }

    /// 从用户选择的文件导入（additive：只补当前不存在的键，不覆盖已存在值含 false/0）。
    /// 返回本次实际写入的键数；读取失败/超过 4 MiB/格式错误返回 nil。
    ///
    /// 4 MiB 上限在**真实读取路径**上强制：`readAtMost` 最多只分配 `maxBytes + 1` 字节，
    /// 不会像 `Data(contentsOf:)` 那样先把整文件读进内存。调用方负责选择文件（系统文档选择器），
    /// 这里只读本地/安全作用域文件，不联网。
    static func importFromFile(url: URL, into store: WotaSettingsStore) -> Int? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        // 元数据只用于快速拒绝：iCloud 占位文件/第三方 provider 的 fileSize 可能是 0 或 nil，
        // 所以仍需 readAtMost 的实际读取结果兜底（不能只信 metadata）。
        if let values = try? url.resourceValues(forKeys: [.fileSizeKey]),
           let size = values.fileSize, size > WotaUserBackup.maxBytes {
            return nil
        }

        guard let data = readAtMost(url: url, limit: WotaUserBackup.maxBytes) else { return nil }
        return try? WotaUserBackup.importBackup(data, into: store)
    }

    /// 最多读取 `limit` 字节；文件更大时返回 nil。
    /// 单次 `readData(ofLength:)` 足够（文件小于 limit 时返回全部内容），不循环拼接。
    private static func readAtMost(url: URL, limit: Int) -> Data? {
        guard limit >= 0, limit < Int.max else { return nil }
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { handle.closeFile() }

        // 多读 1 字节用于判断“是否超限”，峰值占用上限为 limit + 1。
        let data = handle.readData(ofLength: limit + 1)
        guard data.count <= limit else { return nil }
        return data
    }
}
