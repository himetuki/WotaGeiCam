import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 区分成片已经落盘和发布前失败。索引暂不可写时仍可回放已保存的文件。
enum MediaCommitOutcome {
    case published(MediaItem, indexWarning: String?)
    /// 暂存文件本身不可用（无视频轨/时长）⇒ 调用方可以清理。
    case failed(String)
    /// 暂存文件有效但改名/发布失败（如目标名已存在）⇒ 调用方必须保留暂存文件。
    case publishFailed(String)
}

/// 改名结果。
enum MediaRenameOutcome {
    case renamed(MediaItem)
    case failed(String)
}

/// 永久删除结果（逐条计成功/失败，任一条失败不吞掉）。
struct MediaDeleteReport {
    var deleted: Int
    var failed: Int
}

/// 分段落库输入（录制层把各段归一到纯数据后再喂进来）。
struct MediaSeriesPartInput {
    var partIndex: Int
    var mediaId: Int64
    var path: String
    var durationMs: Int64
}

/// 本地媒体数据层入口（可供后续录制与播放器直接调用）。
///
/// 平台差异（已在 `docs/modules/D1-media.md` 记录）：iOS 无 MediaStore / FileProvider /
/// 跨 App 回收站。本层改为 **App 自有目录 + 自有 JSON 索引**：
/// - 媒体真源：`Documents/Movies/WotaGeiCam/{Camera,Clip,Inau}`；
/// - 索引：`Documents/WotaGeiCam/wota_media_index.json`（标签/回收站/系列/对比历史）；
/// - 回收站：自有目录回收站（索引标记隐藏，文件不动，还原不覆盖）；
/// - 永久删除：仅删自有目录内通过所有权校验的文件（含 sidecar）。
///
/// 线程模型：所有写/查都在**单一串行队列**上执行；完成回调一律回到主队列。
/// 未使用 async/await / Combine / SwiftUI（iOS 10 基线）。
final class MediaLibrary {

    let paths: MediaLibraryPaths
    let fileStore: LibraryFileStore
    let indexStore: MediaIndexStore
    private let maintenance: MediaLibraryMaintenance
    private let queue = DispatchQueue(label: "com.wotagei.cam.media.library")

    /// 默认实例：容器为 `Documents`。
    static let shared = MediaLibrary(containerURL: MediaLibrary.documentsContainerURL())

    init(containerURL: URL) {
        let paths = MediaLibraryPaths(containerURL: containerURL)
        let fileStore = LibraryFileStore(paths: paths)
        let indexStore = MediaIndexStore(paths: paths)
        self.paths = paths
        self.fileStore = fileStore
        self.indexStore = indexStore
        self.maintenance = MediaLibraryMaintenance(paths: paths, fileStore: fileStore, indexStore: indexStore)
    }

    static func documentsContainerURL() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    }

    // MARK: - 启动维护入口（App owner 启动时调用一次）

    /// App owner 启动时**调用一次**的维护入口：异步在串行队列执行冷启三趟维护。
    ///
    /// **必须与业务共用同一个 `MediaLibrary` 实例**（默认 `.shared`）：索引是「每实例一份内存快照 +
    /// 整表原子写」，维护另建实例会与业务实例互相覆盖，丢标签/回收站/mediaId 分配。
    /// 需要自定义容器（测试）时，自行创建并**长期持有**该实例，随后所有业务都用它，再传进来。
    static func runStartupMaintenance(
        on library: MediaLibrary = .shared,
        completion: ((MediaMaintenanceReport) -> Void)? = nil
    ) {
        library.runMaintenance(completion: completion)
    }

    /// 在串行队列执行冷启维护。发起时强持有 `self`（`self` 是闭包的强引用），
    /// 执行体全程持有实例，直到 `report` 产生并投递回调；无泄漏。
    func runMaintenance(completion: ((MediaMaintenanceReport) -> Void)? = nil) {
        queue.async {
            let library = self
            let report = library.maintenance.run()
            MediaLibrary.deliver(report, completion)
        }
    }

    // MARK: - 列表 / 查询

    /// 列出库内条目（按创建时刻倒序）。传 nil 取得包含回收站的同一份快照，供片库本地切页。
    func list(filter: MediaClipFilter? = .all, completion: @escaping ([MediaItem]) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.synchronizeWithDisk()
            let items = self.assembleItems().sorted {
                if $0.createdAtMs == $1.createdAtMs { return $0.mediaId > $1.mediaId }
                return $0.createdAtMs > $1.createdAtMs
            }
            let visible = filter.map { MediaClipFiltering.apply(items, filter: $0) } ?? items
            MediaLibrary.deliver(visible, completion)
        }
    }

    /// 单条查询；`nil` = 查过且没有（含已删除）。回调在主队列。
    func item(mediaId: Int64, completion: @escaping (MediaItem?) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.synchronizeWithDisk()
            let found = self.assembleItems().first { $0.mediaId == mediaId }
            MediaLibrary.deliver(found, completion)
        }
    }

    /// 分段归组展示单元（在库条目之上做折叠）。回调在主队列。
    func galleryEntries(filter: MediaClipFilter = .all, completion: @escaping ([MediaGalleryEntry]) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.synchronizeWithDisk()
            let items = self.assembleItems().sorted { $0.createdAtMs > $1.createdAtMs }
            let filtered = MediaClipFiltering.apply(items, filter: filter)
            let entries = MediaSeriesGrouper.galleryEntries(filtered)
            MediaLibrary.deliver(entries, completion)
        }
    }

    // MARK: - pending 生命周期（录制）

    /// 生成录制 pending 句柄（不创建文件；引擎在 `handle.url` 上写入）。
    /// 撞名到已存在的成片名时返回 nil（调用方换名重试）。
    func beginPending(displayName: String, completion: @escaping (MediaPendingHandle?) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            try? self.fileStore.ensureDirectories()
            let handle = self.fileStore.makePendingHandle(
                relativeDirectory: MediaStorageLayout.cameraRelativeDirectory,
                displayName: displayName
            )
            MediaLibrary.deliver(handle, completion)
        }
    }

    /// 先验证暂存视频，再以最终名发布。索引写入失败不丢弃已保存的字节。
    func commit(_ handle: MediaPendingHandle, completion: @escaping (MediaCommitOutcome) -> Void) {
        queue.async {
            guard let source = self.fileStore.pendingURL(for: handle),
                  let metadata = MediaItemAssembler.probe(url: source) else {
                MediaLibrary.deliver(MediaCommitOutcome.failed("暂存文件没有有效的视频轨或时长。"), completion)
                return
            }
            do {
                let published = try self.fileStore.publishResult(handle: handle)
                var savedNotes = [published.attributeWarning].compactMap { $0 }
                if let item = self.adoptIntoIndex(entry: published.entry, metadata: metadata) {
                    MediaLibrary.deliver(
                        MediaCommitOutcome.published(item, indexWarning: savedNotes.isEmpty ? nil : savedNotes.joined(separator: "\n")),
                        completion
                    )
                } else {
                    let provisional = LibraryIdentity(
                        mediaId: 0, relativeDirectory: published.entry.relativeDirectory, fileName: published.entry.fileName,
                        sizeBytes: published.entry.sizeBytes, durationMs: metadata.durationMs,
                        width: metadata.width, height: metadata.height,
                        addedAtMs: published.entry.createdAtMs, modifiedAtMs: published.entry.modifiedAtMs
                    )
                    let item = self.mediaItem(identity: provisional, entry: published.entry, state: self.indexStore.currentState())
                    savedNotes.append("成片已保存，片库索引暂未写入。重新打开片库时会重试。")
                    MediaLibrary.deliver(
                        MediaCommitOutcome.published(item, indexWarning: savedNotes.joined(separator: "\n")),
                        completion
                    )
                }
            } catch {
                // 走到这里说明暂存文件已通过视频轨/时长校验，只是改名/发布失败。
                // 不能按 `.failed` 让调用方 cancelPending——那会删掉完整成片。
                MediaLibrary.deliver(MediaCommitOutcome.publishFailed("\(error)"), completion)
            }
        }
    }

    /// 失败 / 取消：删除暂存文件。
    func cancelPending(_ handle: MediaPendingHandle, completion: ((Bool) -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let ok = self.fileStore.cancel(handle: handle)
            MediaLibrary.deliver(ok, completion)
        }
    }

    // MARK: - 收藏 / 标签

    func setLike(mediaId: Int64, liked: Bool, completion: ((Bool) -> Void)? = nil) {
        mutateTag(mediaId: mediaId, tag: MediaTagSpace.like, add: liked, completion: completion)
    }

    func addTag(mediaId: Int64, rawTag: String, completion: ((Bool) -> Void)? = nil) {
        guard let normalized = MediaTagSpace.normalize(rawTag) else {
            MediaLibrary.deliver(false, completion)
            return
        }
        mutateTag(mediaId: mediaId, tag: normalized, add: true, completion: completion)
    }

    func removeTag(mediaId: Int64, tag: String, completion: ((Bool) -> Void)? = nil) {
        mutateTag(mediaId: mediaId, tag: tag, add: false, completion: completion)
    }

    private func mutateTag(mediaId: Int64, tag: String, add: Bool, completion: ((Bool) -> Void)?) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let identity = self.identity(mediaId: mediaId) else {
                MediaLibrary.deliver(false, completion)
                return
            }
            let path = identity.relativePath
            let now = MediaIndexStore.currentTimeMilliseconds()
            do {
                try self.indexStore.apply { state in
                    if add {
                        if !state.tags.contains(where: { $0.mediaId == mediaId && $0.tag == tag }) {
                            state.tags.append(LibraryTag(mediaId: mediaId, path: path, tag: tag, createdAtMs: now))
                        }
                    } else {
                        state.tags.removeAll { $0.mediaId == mediaId && $0.tag == tag }
                    }
                }
                MediaLibrary.deliver(true, completion)
            } catch {
                MediaLibraryLog.error("标签写入失败：\(error)")
                MediaLibrary.deliver(false, completion)
            }
        }
    }

    // MARK: - 重命名

    func rename(mediaId: Int64, newName: String, completion: @escaping (MediaRenameOutcome) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let identity = self.identity(mediaId: mediaId) else {
                MediaLibrary.deliver(MediaRenameOutcome.failed("条目不存在"), completion)
                return
            }
            guard let entry = self.fileEntry(for: identity) else {
                MediaLibrary.deliver(MediaRenameOutcome.failed("文件不存在"), completion)
                return
            }
            let oldRelativePath = identity.relativePath
            do {
                let renamed = try self.fileStore.rename(entry: entry, to: newName)
                do {
                    try self.maintenance.remapIdentity(oldRelativePath: oldRelativePath, newEntry: renamed)
                } catch {
                    // 文件已改名但索引没跟上：把文件搬回原名，保持索引/磁盘一致。
                    let rolledBack = (try? self.fileStore.rename(entry: renamed, to: identity.fileName)) != nil
                    let message = rolledBack
                        ? "索引写入失败，已回滚改名：\(error)"
                        : "索引写入失败且回滚改名失败，文件现为 \(renamed.fileName)：\(error)"
                    MediaLibrary.deliver(MediaRenameOutcome.failed(message), completion)
                    return
                }
                self.refreshIdentityMetadata(mediaId: mediaId, entry: renamed)
                let item = self.assembleItems().first { $0.mediaId == mediaId }
                MediaLibrary.deliver(MediaRenameOutcome.renamed(item ?? self.mediaItem(identity: identity, entry: renamed, state: self.indexStore.currentState())), completion)
            } catch {
                MediaLibrary.deliver(MediaRenameOutcome.failed("\(error)"), completion)
            }
        }
    }

    // MARK: - 回收站

    /// 进回收站：写本地回收站行（文件仍原地，从常规页签隐藏），等价 Android API29 回退路径。
    func moveToTrash(mediaIds: [Int64], completion: ((Bool) -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let now = MediaIndexStore.currentTimeMilliseconds()
            var ok = true
            for id in mediaIds {
                guard let identity = self.identity(mediaId: id) else { ok = false; continue }
                do {
                    try self.indexStore.apply { state in
                        state.trash.removeAll { $0.relativePath == identity.relativePath }
                        state.trash.append(LibraryTrashItem(
                            relativePath: identity.relativePath,
                            mediaId: id,
                            name: identity.fileName,
                            deletedAtMs: now,
                            origin: identity.relativeDirectory
                        ))
                    }
                } catch {
                    MediaLibraryLog.error("进回收站写索引失败：\(error)")
                    ok = false
                }
            }
            MediaLibrary.deliver(ok, completion)
        }
    }

    /// 还原：删本地回收站行。文件从未移动 ⇒ 还原天然不覆盖。
    func restoreFromTrash(mediaIds: [Int64], completion: ((Bool) -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let ids = Set(mediaIds)
            do {
                try self.indexStore.apply { state in
                    state.trash.removeAll { ids.contains($0.mediaId) }
                }
                MediaLibrary.deliver(true, completion)
            } catch {
                MediaLibraryLog.error("还原写索引失败：\(error)")
                MediaLibrary.deliver(false, completion)
            }
        }
    }

    /// 永久删除：仅删自有文件的字节 + sidecar，并清标签/系列/回收站行。逐条计成功/失败。
    func deleteForever(mediaIds: [Int64], completion: @escaping (MediaDeleteReport) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            var report = MediaDeleteReport(deleted: 0, failed: 0)
            for id in mediaIds {
                guard let identity = self.identity(mediaId: id) else {
                    report.failed += 1
                    continue
                }
                guard let entry = self.fileEntry(for: identity) else {
                    // 文件已不在：仍清索引（幂等）。
                    self.purgeIndex(mediaId: id, relativePath: identity.relativePath)
                    report.deleted += 1
                    continue
                }
                do {
                    try self.fileStore.delete(entry: entry)
                    self.purgeIndex(mediaId: id, relativePath: identity.relativePath)
                    report.deleted += 1
                } catch {
                    MediaLibraryLog.error("永久删除失败 \(identity.fileName)：\(error)")
                    report.failed += 1
                }
            }
            MediaLibrary.deliver(report, completion)
        }
    }

    // MARK: - 系列

    /// 录制收尾落库分段归组（等价 Android `SeriesStore.record`）。
    /// 规则：`seriesId == 0` 或有效段（mediaId > 0）不足 2 ⇒ 什么都不写（单段不成系列）。
    func recordSeries(
        seriesId: Int64,
        startedAtMs: Int64,
        parts: [MediaSeriesPartInput],
        completion: (() -> Void)? = nil
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if seriesId == 0 {
                MediaLibrary.deliver((), completion)
                return
            }
            let valid = parts.filter { $0.mediaId > 0 }
            if valid.count < MediaSeriesGrouper.minGroupSize {
                MediaLibrary.deliver((), completion)
                return
            }
            do {
                try self.indexStore.apply { state in
                    state.series.removeAll { $0.seriesId == seriesId }
                    state.series.append(LibrarySeries(seriesId: seriesId, startedAtMs: startedAtMs, partCount: valid.count))
                    state.seriesParts.removeAll { $0.seriesId == seriesId }
                    for part in valid {
                        state.seriesParts.append(LibrarySeriesPart(
                            seriesId: seriesId,
                            partIndex: part.partIndex,
                            mediaId: part.mediaId,
                            path: part.path,
                            durationMs: part.durationMs
                        ))
                    }
                }
            } catch {
                MediaLibraryLog.error("分段归组落库失败 seriesId=\(seriesId)：\(error)")
            }
            MediaLibrary.deliver((), completion)
        }
    }

    // MARK: - 对比历史

    func compareHistory(completion: @escaping ([MediaCompareEntry]) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let entries = self.indexStore.currentState().compareHistory
            MediaLibrary.deliver(entries, completion)
        }
    }

    func recordCompare(rightId: Int64, rightUri: String?, leftId: Int64, completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let now = MediaIndexStore.currentTimeMilliseconds()
            try? self.indexStore.apply { state in
                state.compareHistory = MediaCompareHistory.record(
                    state.compareHistory, rightId: rightId, rightUri: rightUri, leftId: leftId, nowMs: now
                )
            }
            MediaLibrary.deliver((), completion)
        }
    }

    func removeCompare(key: String, completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self = self else { return }
            try? self.indexStore.apply { state in
                state.compareHistory = MediaCompareHistory.remove(state.compareHistory, key: key)
            }
            MediaLibrary.deliver((), completion)
        }
    }

    // MARK: - 内部

    private func identity(mediaId: Int64) -> LibraryIdentity? {
        return indexStore.currentState().identities.first { $0.mediaId == mediaId }
    }

    private func fileEntry(for identity: LibraryIdentity) -> MediaFileEntry? {
        // 索引行可能是坏 JSON / 手改档：先按自有目录 + 单段名重建路径，越界行当作「文件不存在」。
        guard let url = fileStore.ownedFileURL(
            relativeDirectory: identity.relativeDirectory,
            fileName: identity.fileName
        ) else { return nil }
        guard fileStore.isOwned(url: url) else { return nil }
        return fileStore.fileEntry(at: url, relativeDirectory: identity.relativeDirectory)
    }

    /// 补录入索引（发布后用）；返回组装好的条目（nil = 索引写入失败）。
    private func adoptIntoIndex(entry: MediaFileEntry, metadata: MediaItemAssembler.ProbedMetadata) -> MediaItem? {
        guard maintenance.adopt(entry: entry, metadata: metadata) else { return nil }
        let state = indexStore.currentState()
        guard let identity = state.identities.first(where: { $0.relativePath == entry.relativeDirectory + "/" + entry.fileName }) else {
            return nil
        }
        return mediaItem(identity: identity, entry: entry, state: state)
    }

    private func refreshIdentityMetadata(mediaId: Int64, entry: MediaFileEntry) {
        let probe = MediaItemAssembler.probe(url: entry.url)
        try? indexStore.apply { state in
            for index in state.identities.indices where state.identities[index].mediaId == mediaId {
                state.identities[index].sizeBytes = entry.sizeBytes
                state.identities[index].modifiedAtMs = entry.modifiedAtMs
                state.identities[index].durationMs = probe?.durationMs ?? state.identities[index].durationMs
                state.identities[index].width = probe?.width ?? state.identities[index].width
                state.identities[index].height = probe?.height ?? state.identities[index].height
            }
        }
    }

    private func purgeIndex(mediaId: Int64, relativePath: String) {
        try? indexStore.apply { state in
            state.identities.removeAll { $0.mediaId == mediaId }
            state.tags.removeAll { $0.mediaId == mediaId }
            state.seriesParts.removeAll { $0.mediaId == mediaId || $0.path == relativePath }
            state.trash.removeAll { $0.mediaId == mediaId || $0.relativePath == relativePath }
            let live = Set(state.seriesParts.map { $0.seriesId })
            state.series.removeAll { !live.contains($0.seriesId) }
        }
    }

    /// 与磁盘同步：修正已变更文件的元数据；补录未入库的自有成片（不做删除，删除交给维护趟）。
    private func synchronizeWithDisk() {
        try? fileStore.ensureDirectories()
        let state = indexStore.currentState()
        var knownPaths = Set(state.identities.map { $0.relativePath })
        var missing: [MediaFileEntry] = []
        for entry in fileStore.enumerateLibraryVideos() {
            let relativePath = entry.relativeDirectory + "/" + entry.fileName
            if let identity = state.identities.first(where: { $0.relativePath == relativePath }) {
                if identity.sizeBytes != entry.sizeBytes || identity.modifiedAtMs != entry.modifiedAtMs {
                    refreshIdentityMetadata(mediaId: identity.mediaId, entry: entry)
                }
                continue
            }
            // 批量补录：一次 `apply` 写完整表，避免 N 个新文件做 N 次整表序列化（O(N²)）。
            if knownPaths.insert(relativePath).inserted {
                missing.append(entry)
            }
        }
        if !missing.isEmpty {
            _ = maintenance.adopt(entries: missing)
        }
    }

    /// 索引 + 磁盘 → 条目列表（跳过文件已不存在的身份行；由维护趟清理）。
    private func assembleItems() -> [MediaItem] {
        let state = indexStore.currentState()
        var tagMap: [Int64: Set<String>] = [:]
        for tag in state.tags { tagMap[tag.mediaId, default: []].insert(tag.tag) }
        var trashedPaths = Set<String>()
        for item in state.trash { trashedPaths.insert(item.relativePath) }
        var partMap: [Int64: LibrarySeriesPart] = [:]
        for part in state.seriesParts { partMap[part.mediaId] = part }
        var partCounts: [Int64: Int] = [:]
        for series in state.series { partCounts[series.seriesId] = series.partCount }

        var out: [MediaItem] = []
        for identity in state.identities {
            // 坏索引行（目录越界 / 文件名带分隔符）不参与展示，也不得驱动任何文件操作。
            guard let url = fileStore.ownedFileURL(
                relativeDirectory: identity.relativeDirectory,
                fileName: identity.fileName
            ) else { continue }
            guard FileManager.default.fileExists(atPath: url.path) else { continue }
            let part = partMap[identity.mediaId]
            let item = MediaItem(
                mediaId: identity.mediaId,
                fileName: identity.fileName,
                relativeDirectory: identity.relativeDirectory,
                absolutePath: url.path,
                sizeBytes: identity.sizeBytes,
                durationMs: identity.durationMs,
                width: identity.width,
                height: identity.height,
                mimeType: MediaStorageLayout.videoMimeType,
                createdAtMs: identity.addedAtMs,
                tags: tagMap[identity.mediaId] ?? [],
                isTrashed: trashedPaths.contains(identity.relativePath),
                seriesId: part?.seriesId,
                partIndex: part?.partIndex,
                seriesParts: part.flatMap { partCounts[$0.seriesId] } ?? 1
            )
            out.append(item)
        }
        return out
    }

    private func mediaItem(identity: LibraryIdentity, entry: MediaFileEntry, state: LibraryIndexState) -> MediaItem {
        let tags = Set(state.tags.filter { $0.mediaId == identity.mediaId }.map { $0.tag })
        let part = state.seriesParts.first { $0.mediaId == identity.mediaId }
        let partCount = part.flatMap { p in state.series.first { $0.seriesId == p.seriesId }?.partCount } ?? 1
        return MediaItem(
            mediaId: identity.mediaId,
            fileName: entry.fileName,
            relativeDirectory: entry.relativeDirectory,
            absolutePath: entry.url.path,
            sizeBytes: entry.sizeBytes,
            durationMs: identity.durationMs,
            width: identity.width,
            height: identity.height,
            mimeType: MediaStorageLayout.videoMimeType,
            createdAtMs: identity.addedAtMs,
            tags: tags,
            isTrashed: state.trash.contains { $0.relativePath == entry.relativeDirectory + "/" + entry.fileName },
            seriesId: part?.seriesId,
            partIndex: part?.partIndex,
            seriesParts: partCount
        )
    }

    /// 回到主队列投递结果。
    private static func deliver<T>(_ value: T, _ completion: ((T) -> Void)?) {
        guard let completion = completion else { return }
        DispatchQueue.main.async { completion(value) }
    }
}
