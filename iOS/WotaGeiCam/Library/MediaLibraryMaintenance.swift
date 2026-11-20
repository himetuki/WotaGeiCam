import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 冷启动维护结果。
struct MediaMaintenanceReport {
    /// 第 1 趟：成片目录 pending 残留的处置条数（改名/归位/删重复档案）。
    var sweptStale = 0
    /// 第 2 趟：pending 成片救援（可播 ⇒ 归位到干净名）。
    var rescuedPending = 0
    /// 第 3 趟：库一致性修补——删掉的坏行（文件已不在）。
    var reconciled = 0
    /// 第 3 趟：补入库（有文件没行）的成片条数。
    var adopted = 0

    var total: Int { sweptStale + rescuedPending + reconciled + adopted }
}

/// 冷启动维护（等价于 Android `StalePendingSweep`），三趟：
///
/// 1. **成片/档案 pending 清扫**（仅 Camera 目录，年龄门槛 1h、条目上限 500、成片永不删、目标存在不覆盖）；
/// 2. **pending 成片救援**（三目录；年龄 ≥1h 且可播 ⇒ 归位到干净名，等价于 `rescuePendingRows`
///    的「收录」语义——iOS 无 IS_PENDING 行，未发布文件即「pending 文件」）；
/// 3. **库一致性修补**（删「行在文件不在」的坏行 + 给「有文件没行」的成片补入库，单轮上限 40）。
///
/// **必须在后台线程调用**；调用方（`MediaLibrary`）已用串行队列保证互斥。进程内只跑一次。
final class MediaLibraryMaintenance {

    private let paths: MediaLibraryPaths
    private let fileStore: LibraryFileStore
    private let indexStore: MediaIndexStore

    init(paths: MediaLibraryPaths, fileStore: LibraryFileStore, indexStore: MediaIndexStore) {
        self.paths = paths
        self.fileStore = fileStore
        self.indexStore = indexStore
    }

    // MARK: - 进程内只跑一次

    private static let onceLock = NSLock()
    private static var didRun = false

    static func markRunOnce() -> Bool {
        onceLock.lock()
        defer { onceLock.unlock() }
        if didRun { return false }
        didRun = true
        return true
    }

    /// 执行三趟维护；返回处置计数。`force` 为真时忽略「只跑一次」闸门（仅调试/回归用）。
    @discardableResult
    func run(force: Bool = false) -> MediaMaintenanceReport {
        if !force && !MediaLibraryMaintenance.markRunOnce() {
            return MediaMaintenanceReport()
        }

        do {
            try fileStore.ensureDirectories()
        } catch {
            MediaLibraryLog.error("维护：无法准备目录：\(error)")
            return MediaMaintenanceReport()
        }

        let now = MediaIndexStore.currentTimeMilliseconds()
        var report = MediaMaintenanceReport()

        report.sweptStale = sweepStalePending(now: now)
        report.rescuedPending = rescuePendingFiles(now: now)
        let reconcileResult = reconcileIndex(now: now)
        report.reconciled = reconcileResult.dropped
        report.adopted = reconcileResult.adopted

        if report.total > 0 {
            MediaLibraryLog.info("维护：pending 清扫 \(report.sweptStale)、救援 \(report.rescuedPending)、坏行 \(report.reconciled)、补入库 \(report.adopted)")
        }
        return report
    }

    // MARK: - 第 1 趟：pending 清扫（仅 Camera 目录）

    private func sweepStalePending(now: Int64) -> Int {
        let directory = paths.cameraDirectoryURL
        let relative = MediaStorageLayout.cameraRelativeDirectory
        let entries = fileStore.enumerateOwnedEntries(in: directory, relativeDirectory: relative)
        // 500 只限制「本轮处理多少条」，不限制「磁盘上有什么」：
        // 配对（剥段同名牌 / 侧车↔成片）必须看全量名字，否则窗口外的成片会被当成不存在。
        let capped = Array(entries.prefix(MediaSweepPlanner.maxEntriesPerRun))
        let directoryNames = Set(entries.map { $0.fileName })

        let stale = capped.filter { MediaPendingName.isPendingMarked($0.fileName) }
        let archives = stale.filter { MediaStorageLayout.isSidecarName($0.fileName) }
        let clips = stale.filter { !MediaStorageLayout.isSidecarName($0.fileName) }

        var acted = 0
        // 先成片、后档案（档案目标名取自成片真实名，须等成片先改成干净名）。
        for entry in clips {
            if applyClip(entry: entry, directory: directory, directoryNames: directoryNames, now: now) { acted += 1 }
        }
        for entry in archives {
            if applyArchive(entry: entry, directory: directory, directoryNames: directoryNames, now: now) { acted += 1 }
        }
        return acted
    }

    private func applyClip(entry: MediaFileEntry, directory: URL, directoryNames: Set<String>, now: Int64) -> Bool {
        let clean = MediaPendingName.stripPendingJunk(entry.fileName)
        let ageMs = now - entry.modifiedAtMs
        let twinExists = directoryNames.contains(clean)
        // 探测受年龄门约束：比门槛新的条目本就 LEAVE，不去戳它。
        let playable = ageMs >= MediaSweepPlanner.staleMilliseconds
            ? MediaItemAssembler.isExtractable(url: entry.url)
            : nil
        let plan = MediaSweepPlanner.plan(
            name: entry.fileName, ageMs: ageMs, cleanTwinExists: twinExists,
            clipName: nil, clipPlayable: playable
        )
        switch plan.action {
        case .leave:
            return false
        case .renameClip:
            guard let target = plan.targetName else { return false }
            let destination = directory.appendingPathComponent(target, isDirectory: false)
            // 双保险：目标已存在绝不 rename（Linux/Unix rename 语义是替换）。
            if FileManager.default.fileExists(atPath: destination.path) { return false }
            guard let renamed = try? fileStore.rename(entry: entry, to: target) else { return false }
            remapIdentity(oldRelativePath: entry.relativeDirectory + "/" + entry.fileName, newEntry: renamed)
            MediaLibraryLog.info("清扫：成片去 pending 段 \(entry.fileName) -> \(target)")
            return true
        case .renameArchive, .deleteArchive:
            return false // 成片趟不处理档案
        }
    }

    private func applyArchive(entry: MediaFileEntry, directory: URL, directoryNames: Set<String>, now: Int64) -> Bool {
        let clipName = clipFor(archiveName: entry.fileName, entries: directoryNames)
        let targetName = clipName.map { $0 + MediaStorageLayout.sidecarExtension }
        let twin = MediaSweepPlanner.archiveTwinExists(
            archiveName: entry.fileName, targetName: targetName, directoryNames: directoryNames
        )
        let plan = MediaSweepPlanner.plan(
            name: entry.fileName, ageMs: now - entry.modifiedAtMs,
            cleanTwinExists: twin, clipName: clipName, clipPlayable: nil
        )
        switch plan.action {
        case .deleteArchive:
            // 删档案不可逆：`.deleteArchive` 有两种来由——「已有干净同名档案（重复）」与「找不到成片（无主）」。
            // 后者只以本轮枚举到的名字为准，可能被 500 条窗口截断 ⇒ 删前再到磁盘确认一次成片是否真的不在。
            // 前者（`twin`）已有干净同名牌在盘，pending 副本是重复件，不必再探。
            if !twin, let clip = firstExistingClip(forArchive: entry.fileName, in: directory) {
                MediaLibraryLog.info("清扫：档案对应成片仍在磁盘（\(clip)），跳过删除 \(entry.fileName)")
                return false
            }
            if fileStore.removeOwnedFile(at: entry.url) {
                MediaLibraryLog.info("清扫：删无主/重复档案 \(entry.fileName)")
                return true
            }
            return false
        case .renameArchive:
            guard let target = plan.targetName else { return false }
            let destination = directory.appendingPathComponent(target, isDirectory: false)
            if FileManager.default.fileExists(atPath: destination.path) {
                MediaLibraryLog.info("清扫：档案归位目标已存在，不覆盖（留原名）：\(entry.fileName)")
                return false
            }
            do {
                try FileManager.default.moveItem(at: entry.url, to: destination)
            } catch {
                MediaLibraryLog.error("档案归位失败：\(error)")
                return false
            }
            MediaLibraryLog.info("清扫：档案归位 \(entry.fileName) -> \(target)")
            return true
        case .leave, .renameClip:
            return false
        }
    }

    /// 档案对应的成片文件名（三种形态都试：剥段原样 / 补 `.mp4` / 成片仍带 pending 名）。
    private func clipFor(archiveName: String, entries: Set<String>) -> String? {
        let stripped = MediaPendingName.stripPendingJunk(archiveName)
        let base = stripped.hasSuffix(MediaStorageLayout.sidecarExtension)
            ? String(stripped.dropLast(MediaStorageLayout.sidecarExtension.count))
            : stripped
        if base.isEmpty { return nil }
        if entries.contains(base) { return base }
        let withExt = base + ".mp4"
        if entries.contains(withExt) { return withExt }
        for name in entries where name.hasSuffix(".mp4") {
            let candidateBase = String(MediaPendingName.stripPendingJunk(name).dropLast(4))
            if candidateBase == base { return name }
        }
        return nil
    }

    /// 磁盘上是否真的存在该档案对应的成片（删除前的最后一道确认）。
    ///
    /// 候选名由 `MediaSweepPlanner.sidecarClipProbeNames` 给出（剥段基名 / `.mp4` / `.mov`），
    /// 用 `fileExists` 查真实文件系统：跟随软链、按卷的大小写规则匹配，比 `Set<String>` 更接近事实。
    /// 目录不算成片（否则同名目录会让无主档案永远清不掉）。
    ///
    /// 候选名沿用磁盘上的原样（含首尾空白）去查，不替换成修剪后的名字：删除判断宁可多保留。
    private func firstExistingClip(forArchive archiveName: String, in directory: URL) -> String? {
        for candidate in MediaSweepPlanner.sidecarClipProbeNames(archiveName: archiveName) {
            // 候选来自磁盘上的真实条目名，但仍挡一遍分隔符与 `.` / `..`（坏名不得拼出目录外路径）。
            guard LibraryFileStore.isSafeComponent(candidate) else { continue }
            var isDirectory: ObjCBool = false
            let path = directory.appendingPathComponent(candidate, isDirectory: false).path
            if FileManager.default.fileExists(atPath: path, isDirectory: &isDirectory), !isDirectory.boolValue {
                return candidate
            }
        }
        return nil
    }

    // MARK: - 第 2 趟：pending 成片救援

    private func rescuePendingFiles(now: Int64) -> Int {
        var acted = 0
        for (directory, relative) in zip(fileStore.allMediaDirectoryURLs, MediaStorageLayout.allMediaRelativeDirectories) {
            if acted >= MediaSweepPlanner.maxIndexPerRun { break }
            let entries = fileStore.enumerateOwnedEntries(in: directory, relativeDirectory: relative)
            for entry in entries {
                if acted >= MediaSweepPlanner.maxIndexPerRun { break }
                guard MediaPendingName.isPendingMarked(entry.fileName) else { continue }
                guard !MediaStorageLayout.isSidecarName(entry.fileName) else { continue }
                let ageMs = now - entry.modifiedAtMs
                guard ageMs >= MediaSweepPlanner.staleMilliseconds else { continue }
                // 文件在盘且是完整合法容器才收录；打不开（半截文件）留原状不猜。
                guard MediaItemAssembler.isExtractable(url: entry.url) else { continue }
                let clean = MediaPendingName.stripPendingJunk(entry.fileName)
                let destination = directory.appendingPathComponent(clean, isDirectory: false)
                if FileManager.default.fileExists(atPath: destination.path) { continue }
                guard let renamed = try? fileStore.rename(entry: entry, to: clean) else { continue }
                remapIdentity(oldRelativePath: entry.relativeDirectory + "/" + entry.fileName, newEntry: renamed)
                MediaLibraryLog.info("救援：pending 成片收录 \(entry.fileName) -> \(clean)")
                acted += 1
            }
        }
        return acted
    }

    // MARK: - 第 3 趟：库一致性修补

    private func reconcileIndex(now: Int64) -> (dropped: Int, adopted: Int) {
        // (a) 删「行在文件不在」的坏行（年龄门槛，避免动正在写入的条目）。
        var dropped = 0
        let state = indexStore.currentState()
        var droppedIds = Set<Int64>()
        var droppedPaths = Set<String>()
        for identity in state.identities {
            // 坏索引行（相对目录越界 / 文件名带分隔符）不是「行在文件不在」，原样留着不碰：
            // 它既不能用来删索引，更不能用来推出一条文件路径去做删除判断。
            guard let url = fileStore.ownedFileURL(
                relativeDirectory: identity.relativeDirectory,
                fileName: identity.fileName
            ) else { continue }
            if FileManager.default.fileExists(atPath: url.path) { continue }
            let ageMs = now - identity.addedAtMs
            guard ageMs >= MediaSweepPlanner.staleMilliseconds else { continue }
            droppedIds.insert(identity.mediaId)
            droppedPaths.insert(identity.relativePath)
            dropped += 1
        }
        if dropped > 0 {
            try? indexStore.apply { s in
                s.identities.removeAll { droppedIds.contains($0.mediaId) }
                s.tags.removeAll { droppedIds.contains($0.mediaId) }
                s.seriesParts.removeAll { droppedIds.contains($0.mediaId) }
                s.trash.removeAll { droppedIds.contains($0.mediaId) || droppedPaths.contains($0.relativePath) }
                let live = Set(s.seriesParts.map { $0.seriesId })
                s.series.removeAll { !live.contains($0.seriesId) }
            }
        }

        // (b) 给「有文件没行」的成片补入库（单轮上限）。
        var adopted = 0
        let knownPaths = Set(indexStore.currentState().identities.map { $0.relativePath })
        for entry in fileStore.enumerateLibraryVideos() {
            if adopted >= MediaSweepPlanner.maxIndexPerRun { break }
            let relativePath = entry.relativeDirectory + "/" + entry.fileName
            if knownPaths.contains(relativePath) { continue }
            guard MediaItemAssembler.isExtractable(url: entry.url) else { continue }
            if adopt(entry: entry) {
                MediaLibraryLog.info("清扫：补入库 \(entry.fileName)")
                adopted += 1
            }
        }
        return (dropped, adopted)
    }

    // MARK: - 索引：身份补录 / 改名重绑

    /// 把一个文件补录进索引（分配稳定 id + 探测元数据）。返回是否成功。
    @discardableResult
    func adopt(entry: MediaFileEntry, metadata: MediaItemAssembler.ProbedMetadata? = nil) -> Bool {
        let relativePath = entry.relativeDirectory + "/" + entry.fileName
        guard let probe = metadata ?? MediaItemAssembler.probe(url: entry.url) else { return false }
        do {
            try indexStore.apply { s in
                if s.identities.contains(where: { $0.relativePath == relativePath }) { return }
                let id = s.nextMediaId
                s.nextMediaId = id + 1
                s.identities.append(LibraryIdentity(
                    mediaId: id,
                    relativeDirectory: entry.relativeDirectory,
                    fileName: entry.fileName,
                    sizeBytes: entry.sizeBytes,
                    durationMs: probe.durationMs,
                    width: probe.width,
                    height: probe.height,
                    addedAtMs: entry.createdAtMs > 0 ? entry.createdAtMs : LibraryFileStore.currentTimeMilliseconds(),
                    modifiedAtMs: entry.modifiedAtMs
                ))
            }
            return true
        } catch {
            MediaLibraryLog.error("补入库失败 \(entry.fileName)：\(error)")
            return false
        }
    }

    /// 改名后把身份行重绑到新名（保留 mediaId ⇒ 标签/系列不失联）。
    func remapIdentity(oldRelativePath: String, newEntry: MediaFileEntry) {
        let newRelativePath = newEntry.relativeDirectory + "/" + newEntry.fileName
        try? indexStore.apply { s in
            for index in s.identities.indices where s.identities[index].relativePath == oldRelativePath {
                s.identities[index].fileName = newEntry.fileName
                s.identities[index].relativeDirectory = newEntry.relativeDirectory
                s.identities[index].sizeBytes = newEntry.sizeBytes
                s.identities[index].modifiedAtMs = newEntry.modifiedAtMs
            }
            for index in s.tags.indices where s.tags[index].path == oldRelativePath {
                s.tags[index].path = newRelativePath
            }
            for index in s.seriesParts.indices where s.seriesParts[index].path == oldRelativePath {
                s.seriesParts[index].path = newRelativePath
            }
            for index in s.trash.indices where s.trash[index].relativePath == oldRelativePath {
                s.trash[index].relativePath = newRelativePath
                s.trash[index].name = newEntry.fileName
            }
        }
    }
}
