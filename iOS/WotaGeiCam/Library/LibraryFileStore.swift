import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 文件操作错误。失败必须向上传播，不得静默吞掉。
enum MediaFileError: Error {
    /// 路径不在 App 自有媒体目录内（越界 / 非自有文件）。
    case notOwned(String)
    /// 目标名已存在（红线：绝不覆盖）。
    case targetExists(String)
    /// 源文件不存在。
    case fileMissing(String)
    /// 非法文件名（含路径分隔符 / `..` / 空）。
    case invalidName(String)
    /// 其它文件系统错误。
    case operationFailed(String)
}

/// 磁盘上的一个自有媒体条目（文件真源）。
struct MediaFileEntry {
    var url: URL
    var fileName: String
    var relativeDirectory: String
    var sizeBytes: Int64
    var modifiedAtMs: Int64
    var createdAtMs: Int64
}

/// 发布结果：文件已改名为最终名；`attributeWarning` 非 nil 表示改名成功但读不到落盘属性，
/// 调用方必须按「已保存 + 警告」上报，绝不能再按失败删文件。
struct MediaPublishResult {
    var entry: MediaFileEntry
    var attributeWarning: String?
}

/// 录制 pending 句柄（等价于 Android 的 pending uri）。
///
/// - `stagingFileName`：带 `.pending-<数字>-` 段的暂存名，由录制引擎在其上写入；
/// - `finalFileName`：发布后的成片名（与 Android `VID_...mp4` 同一套命名）；
/// - `pendingId`：该句柄专属的暂存段编号（同一进程内并发句柄互异）。取消/发布前用它重算
///   暂存名，确保只动「当前句柄」的文件，旧句柄不得误删别人的暂存文件；
/// - 发布 = 原子 rename（同目录换名），失败/取消 = 删除暂存文件。
struct MediaPendingHandle {
    let relativeDirectory: String
    let stagingFileName: String
    let finalFileName: String
    let pendingId: Int32
    let url: URL
    let createdAtMs: Int64
}

/// App 自有媒体目录的文件系统操作（不含索引元数据）。
///
/// 硬约束（逐条对齐 Android）：
/// - 只操作 `Movies/WotaGeiCam/{Camera,Clip,Inau}` **直接子文件**，不做递归、不越界；
/// - **绝不覆盖**：发布 / 改名 / sidecar 归位前都判目标是否存在，存在即失败；
/// - 成片永不删；只删通过 `isOwned` 校验的自有文件；
/// - 文件操作成功后再由上层更新索引。
final class LibraryFileStore {

    private let paths: MediaLibraryPaths
    private let fileManager: FileManager

    init(paths: MediaLibraryPaths, fileManager: FileManager = .default) {
        self.paths = paths
        self.fileManager = fileManager
    }

    static func currentTimeMilliseconds() -> Int64 {
        return Int64((Date().timeIntervalSince1970 * 1000).rounded(.down))
    }

    // MARK: - 目录

    func ensureDirectories() throws {
        for url in [paths.mediaRootURL, paths.cameraDirectoryURL, paths.clipDirectoryURL, paths.inauDirectoryURL, paths.privateDirectoryURL] {
            if !fileManager.fileExists(atPath: url.path) {
                do {
                    try fileManager.createDirectory(at: url, withIntermediateDirectories: true, attributes: nil)
                } catch {
                    throw MediaFileError.operationFailed("创建目录失败 \(url.lastPathComponent)：\(error)")
                }
            }
        }
    }

    var allMediaDirectoryURLs: [URL] { paths.mediaDirectoryURLs }

    // MARK: - 所有权 / 越界

    /// 相对目录 + 单段文件名 → 自有目录内的**直接子文件** URL；任一条件不满足返回 nil。
    ///
    /// 这是本层唯一的「相对名 → 绝对路径」入口（含索引行、句柄名、外部传入的 displayName）：
    /// 1. 相对目录必须是自有三目录之一（不接受 `..`、空串、任意子目录）；
    /// 2. 文件名必须是可安全拼接的**单段**名（见 `isSafeComponent`）；
    /// 3. 拼出的路径必须仍在目录内（`isPath` 字符串级消解 `.` / `..`）。
    ///
    /// 叶子是否为符号链接不在这里判断：读属性/删链接本身不会越界（`removeItem` 只删链接）；
    /// 写入与移动（`makePendingHandle` / `publish` / `cancel`）会单独拒绝软链。
    func ownedFileURL(relativeDirectory: String, fileName: String) -> URL? {
        guard MediaStorageLayout.allMediaRelativeDirectories.contains(relativeDirectory) else { return nil }
        guard LibraryFileStore.isSafeComponent(fileName) else { return nil }
        let directoryURL = paths.directoryURL(relativeDirectory: relativeDirectory)
        let url = directoryURL.appendingPathComponent(fileName, isDirectory: false)
        guard MediaStorageLayout.isPath(url.path, inside: directoryURL.path) else { return nil }
        return url
    }

    /// **磁盘上已有名字**能否直接拼成路径：非空、非 `.` / `..`、不含 `/` 与 NUL。
    ///
    /// 与 `MediaStorageLayout.sanitizedFileName` 分工不同：那个是**用户输入**的消毒尺（还修剪首尾空白、
    /// 拒 `\`）；磁盘条目名（APFS 上 `\` 与首尾空白都合法）不能套同一把尺，否则会把真实存在的文件
    /// 误判成「不存在」，进而在对账/删除时做出错误结论。
    static func isSafeComponent(_ name: String) -> Bool {
        if name.isEmpty || name == "." || name == ".." { return false }
        if name.contains("/") || name.contains("\u{0}") { return false }
        return true
    }

    /// 叶子（最后一段）是否是符号链接。
    ///
    /// `fileExists(atPath:)` 跟随软链：**悬空软链**会报「不存在」，据此打开写入就会顺链接写到目录外。
    /// 因此凡是「在目标上写字节 / 把文件搬成目标」的入口，都要额外做这一次 `lstat` 判定。
    func isSymbolicLink(at url: URL) -> Bool {
        let values = try? url.resourceValues(forKeys: [.isSymbolicLinkKey])
        return values?.isSymbolicLink ?? false
    }

    /// 路径是否为本 App 自有的媒体文件（位于自有目录直接子层，且名称为自有形态或 pending 暂存形态）。
    func isOwned(url: URL) -> Bool {
        guard MediaStorageLayout.isPath(url.path, inside: paths.mediaRootURL.path) else { return false }
        guard relativeDirectory(of: url) != nil else { return false }
        let name = url.lastPathComponent
        return MediaStorageLayout.isOwnedMediaFileName(name) || MediaPendingName.isPendingMarked(name)
    }

    /// 绝对路径 → 自有相对目录；不在自有三目录内返回 nil。
    func relativeDirectory(of url: URL) -> String? {
        let parent = MediaStorageLayout.normalizedAbsolutePath(url.deletingLastPathComponent().path)
        for (directory, relative) in zip(allMediaDirectoryURLs, MediaStorageLayout.allMediaRelativeDirectories) {
            if MediaStorageLayout.normalizedAbsolutePath(directory.path) == parent {
                return relative
            }
        }
        return nil
    }

    // MARK: - 扫描

    /// 枚举目录内全部自有条目（含 sidecar 与 pending 暂存），供冷启维护使用。
    func enumerateOwnedEntries(in directoryURL: URL, relativeDirectory: String) -> [MediaFileEntry] {
        guard let names = try? fileManager.contentsOfDirectory(atPath: directoryURL.path) else { return [] }
        var out: [MediaFileEntry] = []
        for name in names {
            let isOwnedName = MediaStorageLayout.isOwnedMediaFileName(name) || MediaPendingName.isPendingMarked(name)
            if !isOwnedName { continue }
            let url = directoryURL.appendingPathComponent(name, isDirectory: false)
            if let entry = fileEntry(at: url, relativeDirectory: relativeDirectory) {
                out.append(entry)
            }
        }
        return out
    }

    /// 枚举库内可见成片（视频、非 pending、非 sidecar），供列表使用。
    func enumerateLibraryVideos() -> [MediaFileEntry] {
        var out: [MediaFileEntry] = []
        for (directory, relative) in zip(allMediaDirectoryURLs, MediaStorageLayout.allMediaRelativeDirectories) {
            for entry in enumerateOwnedEntries(in: directory, relativeDirectory: relative) {
                let name = entry.fileName
                if MediaPendingName.isPendingMarked(name) { continue }
                if MediaStorageLayout.isSidecarName(name) { continue }
                if !MediaStorageLayout.isOwnedVideoFileName(name) { continue }
                out.append(entry)
            }
        }
        return out
    }

    /// 读取文件属性（大小 / mtime / 创建时刻）。
    func fileEntry(at url: URL, relativeDirectory: String) -> MediaFileEntry? {
        guard let attributes = try? fileManager.attributesOfItem(atPath: url.path) else { return nil }
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        let modified = (attributes[.modificationDate] as? Date) ?? Date(timeIntervalSince1970: 0)
        let created = (attributes[.creationDate] as? Date) ?? modified
        return MediaFileEntry(
            url: url,
            fileName: url.lastPathComponent,
            relativeDirectory: relativeDirectory,
            sizeBytes: size,
            modifiedAtMs: Int64((modified.timeIntervalSince1970 * 1000).rounded(.down)),
            createdAtMs: Int64((created.timeIntervalSince1970 * 1000).rounded(.down))
        )
    }

    // MARK: - pending 生命周期

    /// 暂存段编号分配：进程内单调递增；段数字同时编码进程号（见 ``nextPendingId``）。
    ///
    /// 同一进程里两个并发句柄（例如同时开始的剪辑导出与补弧）不能共用暂存名：
    /// 旧实现只用 pid，名字相同的两个句柄会指向同一个暂存文件，任一取消都会删掉对方正在写的字节。
    /// 发布/取消/读取前用 ``pendingId`` 重算暂存名校验句柄身份，旧句柄不得误动别人的暂存文件。
    private static let pendingIdLock = NSLock()
    private static var pendingIdSequence: Int32 = 0

    static func nextPendingId() -> Int32 {
        pendingIdLock.lock()
        defer { pendingIdLock.unlock() }
        pendingIdSequence = (pendingIdSequence % 999_999) + 1
        // 段数字同时编码进程号与进程内序号：跨进程残留可分辨，同一进程的并发句柄互异。
        let raw = Int64(max(0, currentProcessIdRaw()))
        let bucket = Int32((raw % 900) * 1_000_000)
        return bucket + pendingIdSequence
    }

    private static func currentProcessIdRaw() -> Int32 {
        return ProcessInfo.processInfo.processIdentifier
    }

    /// 计算发布用的最终名并生成 pending 句柄（不创建文件；录制引擎在 `url` 上写入）。
    /// 最终名已存在（撞名）、非法、或目标/暂存位是符号链接则返回 nil。
    func makePendingHandle(relativeDirectory: String, displayName: String) -> MediaPendingHandle? {
        guard MediaStorageLayout.allMediaRelativeDirectories.contains(relativeDirectory) else {
            MediaLibraryLog.error("拒绝非法媒体目录：\(relativeDirectory)")
            return nil
        }
        // 外部输入先过与改名同一把消毒尺：非法即拒绝，不做静默改写（只接受首尾空白被安全修剪的名字）。
        guard let safeName = MediaStorageLayout.sanitizedFileName(displayName),
              MediaStorageLayout.isOwnedVideoFileName(safeName), !MediaPendingName.isPendingMarked(safeName) else {
            MediaLibraryLog.error("拒绝非法媒体文件名：\(displayName)")
            return nil
        }
        let directoryURL = paths.directoryURL(relativeDirectory: relativeDirectory)
        let finalURL = directoryURL.appendingPathComponent(safeName, isDirectory: false)
        // 双保险：消毒后仍复核目录归属（防消毒规则将来放宽后 `..` / 子目录逃逸）。
        guard MediaStorageLayout.isPath(finalURL.path, inside: directoryURL.path) else {
            MediaLibraryLog.error("媒体文件目标越界：\(safeName)")
            return nil
        }
        // 录制引擎会直接在这个路径上写字节：软链（尤其悬空软链）一律拒绝，避免写到媒体目录之外。
        guard !isSymbolicLink(at: finalURL) else {
            MediaLibraryLog.error("媒体文件目标是符号链接，拒绝：\(safeName)")
            return nil
        }
        if fileManager.fileExists(atPath: finalURL.path) { return nil }

        // 暂存段数字由进程号 + 进程内序号组成：并发句柄互异，且冷启残留可按段识别。
        let pendingId = Self.nextPendingId()
        let stagingName = MediaPendingName.stagingFileName(
            displayName: safeName,
            processId: pendingId
        )
        let stagingURL = directoryURL.appendingPathComponent(stagingName, isDirectory: false)
        guard MediaStorageLayout.isPath(stagingURL.path, inside: directoryURL.path), !isSymbolicLink(at: stagingURL) else {
            MediaLibraryLog.error("媒体暂存目标越界或为符号链接：\(stagingName)")
            return nil
        }
        // 极端撞名（同 PID 同毫秒）也不会覆盖：已存在则再退一个后缀。
        if fileManager.fileExists(atPath: stagingURL.path) { return nil }
        return MediaPendingHandle(
            relativeDirectory: relativeDirectory,
            stagingFileName: stagingName,
            finalFileName: safeName,
            pendingId: pendingId,
            url: stagingURL,
            createdAtMs: LibraryFileStore.currentTimeMilliseconds()
        )
    }

    func pendingURL(for handle: MediaPendingHandle) -> URL? {
        // 句柄身份校验：暂存名必须等于「最终名 + pendingId」重算的形态，
        // 防止旧句柄或手工构造的句柄指向别人的暂存文件。
        guard isCurrentStagingName(handle),
              let url = ownedFileURL(relativeDirectory: handle.relativeDirectory, fileName: handle.stagingFileName),
              url == handle.url, !isSymbolicLink(at: url) else { return nil }
        return url
    }

    /// 句柄身份校验：暂存名必须等于「最终名 + pendingId」重算的形态。
    private func isCurrentStagingName(_ handle: MediaPendingHandle) -> Bool {
        guard MediaPendingName.isPendingMarked(handle.stagingFileName) else { return false }
        let recomputed = MediaPendingName.stagingFileName(
            displayName: handle.finalFileName,
            processId: handle.pendingId
        )
        return recomputed == handle.stagingFileName
    }

    /// 原子发布：把暂存文件改名为最终名。**目标存在绝不覆盖**，源不存在即失败。
    ///
    /// 返回 ``MediaPublishResult``：改名成功后即使读不到落盘属性，也按「已保存」回报，
    /// 属性缺失只进 `attributeWarning`——调用方不得再按失败删掉已发布的成片；
    /// 只有改名本身失败 / 目标已存在才抛错。
    func publishResult(handle: MediaPendingHandle) throws -> MediaPublishResult {
        // 句柄可能不是本实例造的：发布前按同一把尺复核目录归属、单段名与软链，
        // 越界一律抛 `invalidName`（不产生父目录外文件）。
        guard isCurrentStagingName(handle),
              let source = ownedFileURL(relativeDirectory: handle.relativeDirectory, fileName: handle.stagingFileName) else {
            throw MediaFileError.invalidName(handle.stagingFileName)
        }
        guard let destination = ownedFileURL(relativeDirectory: handle.relativeDirectory, fileName: handle.finalFileName) else {
            throw MediaFileError.invalidName(handle.finalFileName)
        }
        guard !isSymbolicLink(at: source), !isSymbolicLink(at: destination) else {
            throw MediaFileError.invalidName(handle.finalFileName)
        }

        guard fileManager.fileExists(atPath: source.path) else {
            throw MediaFileError.fileMissing(handle.stagingFileName)
        }
        if fileManager.fileExists(atPath: destination.path) {
            // 目标已存在：**绝不覆盖**。保留暂存文件，交由冷启维护/调用方处理。
            throw MediaFileError.targetExists(handle.finalFileName)
        }
        do {
            try fileManager.moveItem(at: source, to: destination)
        } catch {
            throw MediaFileError.operationFailed("发布失败：\(error)")
        }
        if let entry = fileEntry(at: destination, relativeDirectory: handle.relativeDirectory) {
            return MediaPublishResult(entry: entry, attributeWarning: nil)
        }
        // 改名已成功，成片已落盘：属性读不到只影响本次展示，下一次片库对账会修正。
        let fallback = MediaFileEntry(
            url: destination,
            fileName: handle.finalFileName,
            relativeDirectory: handle.relativeDirectory,
            sizeBytes: 0,
            modifiedAtMs: handle.createdAtMs,
            createdAtMs: handle.createdAtMs
        )
        return MediaPublishResult(
            entry: fallback,
            attributeWarning: "已保存为 \(handle.finalFileName)，但读取文件属性失败。"
        )
    }

    /// 失败 / 取消：删除暂存文件（仅限身份校验通过的当前句柄）。
    @discardableResult
    func cancel(handle: MediaPendingHandle) -> Bool {
        guard isCurrentStagingName(handle),
              let source = ownedFileURL(relativeDirectory: handle.relativeDirectory, fileName: handle.stagingFileName),
              source == handle.url, !isSymbolicLink(at: source) else { return false }
        guard fileManager.fileExists(atPath: source.path) else { return true }
        do {
            try fileManager.removeItem(at: source)
            return true
        } catch {
            MediaLibraryLog.error("取消 pending 失败 \(handle.stagingFileName)：\(error)")
            return false
        }
    }

    // MARK: - 改名（跟随 sidecar）

    /// 重命名自有成片，并同步搬动同名 sidecar。**目标存在绝不覆盖**；用户输入先消毒。
    /// 返回改名后的实际条目（撞名后缀等情况由调用方回查真实名决定，这里不做隐式加序号）。
    func rename(entry: MediaFileEntry, to rawName: String) throws -> MediaFileEntry {
        guard isOwned(url: entry.url) else { throw MediaFileError.notOwned(entry.fileName) }
        guard let sanitized = MediaStorageLayout.sanitizedFileName(rawName) else {
            throw MediaFileError.invalidName(rawName)
        }
        let newName = MediaStorageLayout.ensuringSuffix(sanitized, like: entry.fileName)
        // 改名后仍必须是本工程自有成片形态（不能把 VID_x.mp4 改成 foo.mp4）：
        // 否则 `isOwned` 会拒绝它，永久删除会清索引却删不掉字节。
        guard MediaStorageLayout.isOwnedVideoFileName(newName),
              !MediaPendingName.isPendingMarked(newName) else {
            throw MediaFileError.invalidName(rawName)
        }
        let directoryURL = entry.url.deletingLastPathComponent()
        let destination = directoryURL.appendingPathComponent(newName, isDirectory: false)

        if destination.path == entry.url.path {
            return entry // 名字未变
        }
        // 目标已存在，或目标位是（含悬空的）符号链接：都不搬（`fileExists` 对悬空软链为 false）。
        if fileManager.fileExists(atPath: destination.path) || isSymbolicLink(at: destination) {
            throw MediaFileError.targetExists(newName)
        }
        guard fileManager.fileExists(atPath: entry.url.path) else {
            throw MediaFileError.fileMissing(entry.fileName)
        }

        do {
            try fileManager.moveItem(at: entry.url, to: destination)
        } catch {
            throw MediaFileError.operationFailed("改名失败：\(error)")
        }

        // sidecar 跟随：成片改名后位次档案的对应关系不能断（无可搬文件返回 false）。
        moveSidecar(fromVideo: entry.url, toVideo: destination)

        guard let renamed = fileEntry(at: destination, relativeDirectory: entry.relativeDirectory) else {
            throw MediaFileError.operationFailed("改名后读取属性失败")
        }
        return renamed
    }

    /// 搬动 sidecar（同目录同名换扩展名）。**目标已存在绝不覆盖**，宁可留原名。
    @discardableResult
    func moveSidecar(fromVideo oldVideo: URL, toVideo newVideo: URL) -> Bool {
        let from = sidecarURL(forVideo: oldVideo)
        let to = sidecarURL(forVideo: newVideo)
        if from.path == to.path { return true }
        guard fileManager.fileExists(atPath: from.path) else { return false }
        if fileManager.fileExists(atPath: to.path) || isSymbolicLink(at: to) {
            MediaLibraryLog.info("sidecar 归位目标已存在，不覆盖：\(from.lastPathComponent) -> \(to.lastPathComponent)")
            return false
        }
        do {
            try fileManager.moveItem(at: from, to: to)
            return true
        } catch {
            MediaLibraryLog.error("sidecar 搬动失败：\(error)")
            return false
        }
    }

    func sidecarURL(forVideo videoURL: URL) -> URL {
        let directory = videoURL.deletingLastPathComponent()
        let name = MediaStorageLayout.sidecarFileName(forVideoFileName: videoURL.lastPathComponent)
        return directory.appendingPathComponent(name, isDirectory: false)
    }

    /// 删除 sidecar（存在则删，不存在返回 false）。
    @discardableResult
    func deleteSidecar(forVideo videoURL: URL) -> Bool {
        let sidecar = sidecarURL(forVideo: videoURL)
        guard fileManager.fileExists(atPath: sidecar.path) else { return false }
        do {
            try fileManager.removeItem(at: sidecar)
            return true
        } catch {
            MediaLibraryLog.error("删除 sidecar 失败：\(error)")
            return false
        }
    }

    // MARK: - 删除（仅自有文件）

    /// 删除自有成片 + sidecar。越界/非自有抛错，绝不删外部文件。
    /// 文件删除失败即抛错，调用方不得据此更新索引。
    func delete(entry: MediaFileEntry) throws {
        guard isOwned(url: entry.url) else { throw MediaFileError.notOwned(entry.fileName) }
        guard fileManager.fileExists(atPath: entry.url.path) else {
            // 文件已不在：视为已删除（幂等），由上层清索引。
            return
        }
        do {
            try fileManager.removeItem(at: entry.url)
        } catch {
            throw MediaFileError.operationFailed("删除失败：\(error)")
        }
        deleteSidecar(forVideo: entry.url)
    }

    /// 删除任意自有形态文件（供清扫删除重复/无主 sidecar 用）。
    func removeOwnedFile(at url: URL) -> Bool {
        guard isOwned(url: url) else {
            MediaLibraryLog.error("拒绝删除非自有文件：\(url.lastPathComponent)")
            return false
        }
        guard fileManager.fileExists(atPath: url.path) else { return false }
        do {
            try fileManager.removeItem(at: url)
            return true
        } catch {
            MediaLibraryLog.error("删除文件失败：\(error)")
            return false
        }
    }
}
