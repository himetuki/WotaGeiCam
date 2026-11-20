import AVFoundation
import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

struct PlayerClipExportFailure: Error, Equatable {
    let kind: ClipPlanError
    let message: String

    init(_ kind: ClipPlanError, _ message: String) {
        self.kind = kind
        self.message = message
    }
}

enum PlayerClipExportOutcome {
    /// 成片已经发布。索引告警与音轨、编码的实际落点随结果返回。
    case completed(URL, notes: [String])
    case cancelled
    case failed(PlayerClipExportFailure)
}

enum PlayerClipExportPhase {
    case preparing
    case writing(Float)
    case cancelling
    case cleaning
    case publishing
}

/// 主队列持有任务、导出会话与回执。素材探测、组合装配和 preset 选择在准备队列处理。
/// 每次写入只操作当前任务的 MP4 暂存文件。重试与取消均在写入结束、暂存清理后结算。
final class PlayerClipExporter {
    private struct PreparedExport {
        let asset: AVAsset
        let range: CMTimeRange
        let handle: MediaPendingHandle
        let presets: [String]
        let notes: [String]
    }

    private static let presets = [
        AVAssetExportPresetPassthrough,
        AVAssetExportPresetHighestQuality,
        AVAssetExportPreset1920x1080,
        AVAssetExportPreset1280x720,
        AVAssetExportPresetMediumQuality,
        AVAssetExportPresetLowQuality
    ]

    private let library: MediaLibrary
    private let preparationQueue = DispatchQueue(label: "cam.player.clip.prepare", qos: .userInitiated)
    private var session: AVAssetExportSession?
    private var progressTimer: Timer?
    private var completion: ((PlayerClipExportOutcome) -> Void)?
    private var cancellationRequested = false
    private var attemptNotes: [String] = []

    /// 公开入口与回调均在主队列使用。
    var onPhase: ((PlayerClipExportPhase) -> Void)?
    var isExporting: Bool { completion != nil }
    var canCancel: Bool {
        guard !cancellationRequested, let session = session else { return false }
        switch session.status {
        case .unknown, .waiting, .exporting: return true
        default: return false
        }
    }

    init(library: MediaLibrary = .shared) {
        self.library = library
    }

    deinit { progressTimer?.invalidate() }

    func export(
        sourceURL: URL,
        sourceDisplayName: String,
        range: ClipTimeRange,
        audioTrack: PlayerAudioTrackKind,
        offsetSeconds: Double,
        completion: @escaping (PlayerClipExportOutcome) -> Void
    ) {
        guard !isExporting else {
            completion(.failed(PlayerClipExportFailure(.busy, "已有导出任务进行中。")))
            return
        }
        self.completion = completion
        cancellationRequested = false
        attemptNotes.removeAll()
        onPhase?(.preparing)
        preparationQueue.async {
            let result = Self.prepare(
                store: self.library.fileStore, url: sourceURL, name: sourceDisplayName,
                range: range, pick: audioTrack, offsetSeconds: offsetSeconds
            )
            DispatchQueue.main.async {
                switch result {
                case .success(let work): self.startAttempt(work, remaining: work.presets[...])
                case .failure(let failure): self.finish(.failed(failure))
                }
            }
        }
    }

    func cancel() {
        guard canCancel, let session = session else { return }
        cancellationRequested = true
        stopProgress()
        onPhase?(.cancelling)
        session.cancelExport()
    }

    private static func prepare(
        store: LibraryFileStore, url: URL, name: String, range: ClipTimeRange,
        pick: PlayerAudioTrackKind, offsetSeconds: Double
    ) -> Result<PreparedExport, PlayerClipExportFailure> {
        let source = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
        let videoTracks = source.tracks(withMediaType: .video)
        guard let video = videoTracks.first else {
            return .failure(PlayerClipExportFailure(.unsupported, "源文件没有可读的视频轨。"))
        }
        let videoRange = video.timeRange
        guard range.isValid, videoRange.start.isNumeric, videoRange.end.isNumeric,
              let normalized = PlayerClipRule.normalize(
                start: max(range.start, videoRange.start), end: range.end, duration: videoRange.end
              ) else {
            return .failure(PlayerClipExportFailure(.range, "A/B 区间至少要有 300 毫秒的有效画面。"))
        }
        let trim = CMTimeRange(start: normalized.start, end: normalized.end)
        let audioTracks = source.tracks(withMediaType: .audio)
        var notes: [String] = []
        let selectedAudio = pick == .internalCapture && audioTracks.count > 1 ? 1 : 0
        if audioTracks.isEmpty {
            notes.append("源文件没有音轨，已导出纯视频。")
        } else if pick == .internalCapture && selectedAudio == 0 {
            notes.append("未找到内录音轨，已选用现场声。")
        }
        let shift = selectedAudio == 1 ? PlayerTrackRule.shiftSeconds(for: pick, offsetSeconds: offsetSeconds) : 0
        guard shift.isFinite, abs(shift) <= Double(Int64.max / 600) else {
            return .failure(PlayerClipExportFailure(.range, "音轨偏移无效。"))
        }
        let asset: AVAsset
        let exportRange: CMTimeRange
        if videoTracks.count > 1 || audioTracks.count > 1 {
            let audio = audioTracks.isEmpty ? nil : audioTracks[selectedAudio]
            guard let composition = makeComposition(video: video, audio: audio, trim: trim, shift: shift, notes: &notes) else {
                return .failure(PlayerClipExportFailure(.unsupported, "无法装配所选区间的视频轨。"))
            }
            asset = composition
            exportRange = CMTimeRange(start: .zero, duration: trim.duration)
        } else {
            asset = source
            exportRange = trim
        }
        let compatible = AVAssetExportSession.exportPresets(compatibleWith: asset)
        let attempts = presets.filter { compatible.contains($0) }
        guard !attempts.isEmpty else {
            return .failure(PlayerClipExportFailure(.unsupported, "当前素材没有可用的导出配置。"))
        }
        do {
            try store.ensureDirectories()
        } catch {
            return .failure(PlayerClipExportFailure(.open, "无法创建剪辑目录：\(error.localizedDescription)"))
        }
        let baseName = PlayerClipRule.clipName(from: name, fileExtension: "mp4")
        let stem = MediaStorageLayout.stem(of: baseName)
        for index in 0..<64 {
            let candidate = index == 0 ? baseName : "\(stem) \(index + 1).mp4"
            if let handle = store.makePendingHandle(
                relativeDirectory: MediaStorageLayout.clipRelativeDirectory, displayName: candidate
            ) {
                return .success(PreparedExport(asset: asset, range: exportRange, handle: handle, presets: attempts, notes: notes))
            }
        }
        return .failure(PlayerClipExportFailure(.open, "无法分配剪辑暂存文件名。"))
    }

    private static func makeComposition(
        video: AVAssetTrack, audio: AVAssetTrack?, trim: CMTimeRange, shift: Double, notes: inout [String]
    ) -> AVMutableComposition? {
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            return nil
        }
        do {
            try videoTrack.insertTimeRange(trim, of: video, at: .zero)
        } catch {
            return nil
        }
        videoTrack.preferredTransform = video.preferredTransform
        guard let audio = audio else { return composition }

        // 输出时刻 = 源音频时刻 − 视频入点 + 偏移。只读取映射进成片时间窗且实际存在的声音。
        let shiftTime = CMTime(seconds: shift, preferredTimescale: 600)
        let sourceWindowStart = trim.start - shiftTime
        let sourceWindowEnd = trim.end - shiftTime
        let audioRange = audio.timeRange
        guard sourceWindowStart.isNumeric, sourceWindowEnd.isNumeric,
              audioRange.start.isNumeric, audioRange.end.isNumeric else {
            notes.append("所选音轨时间无效，已导出纯视频。")
            return composition
        }
        let start = max(sourceWindowStart, audioRange.start)
        let end = min(sourceWindowEnd, audioRange.end)
        guard end > start,
              let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            notes.append("所选音轨在剪辑区间内不可用，已导出纯视频。")
            return composition
        }
        do {
            try audioTrack.insertTimeRange(CMTimeRange(start: start, end: end), of: audio, at: start - sourceWindowStart)
        } catch {
            composition.removeTrack(audioTrack)
            notes.append("所选音轨写入组合失败，已导出纯视频。")
        }
        return composition
    }

    private func startAttempt(_ work: PreparedExport, remaining: ArraySlice<String>) {
        onPhase?(.preparing)
        preparationQueue.async {
            for index in remaining.indices {
                let preset = remaining[index]
                guard let candidate = AVAssetExportSession(asset: work.asset, presetName: preset),
                      candidate.supportedFileTypes.contains(.mp4) else { continue }
                candidate.outputURL = work.handle.url
                candidate.outputFileType = .mp4
                candidate.shouldOptimizeForNetworkUse = true
                candidate.timeRange = work.range
                let next = remaining[remaining.index(after: index)...]
                DispatchQueue.main.async { self.write(candidate, preset: preset, work: work, remaining: next) }
                return
            }
            DispatchQueue.main.async {
                let failure = self.attemptNotes.last ?? "当前素材没有可用的 MP4 导出配置。"
                self.discard(work.handle, outcome: .failed(PlayerClipExportFailure(.mux, failure)))
            }
        }
    }

    private func write(_ candidate: AVAssetExportSession, preset: String, work: PreparedExport, remaining: ArraySlice<String>) {
        session = candidate
        candidate.exportAsynchronously {
            DispatchQueue.main.async { self.didFinishWriting(candidate, preset: preset, work: work, remaining: remaining) }
        }
        let timer = Timer(timeInterval: 0.2, repeats: true) { [weak self, weak candidate] _ in
            guard let self = self, let candidate = candidate, self.session === candidate else { return }
            self.onPhase?(.writing(candidate.progress))
        }
        progressTimer = timer
        RunLoop.main.add(timer, forMode: .common)
        onPhase?(.writing(0))
    }

    private func didFinishWriting(_ candidate: AVAssetExportSession, preset: String, work: PreparedExport, remaining: ArraySlice<String>) {
        guard isExporting, session === candidate else { return }
        session = nil
        stopProgress()
        if cancellationRequested || candidate.status == .cancelled {
            discard(work.handle, outcome: .cancelled)
            return
        }
        switch candidate.status {
        case .completed:
            publish(work, preset: preset)
        case .failed:
            let failure = candidate.error?.localizedDescription ?? "系统没有提供错误详情。"
            let message = "导出配置 \(preset) 失败：\(failure)"
            attemptNotes.append(message)
            guard !remaining.isEmpty else {
                discard(work.handle, outcome: .failed(PlayerClipExportFailure(.mux, message)))
                return
            }
            onPhase?(.cleaning)
            library.cancelPending(work.handle) { removed in
                if removed { self.startAttempt(work, remaining: remaining) }
                else { self.finish(.failed(PlayerClipExportFailure(.mux, "无法清理上次导出的暂存文件。"))) }
            }
        default:
            discard(work.handle, outcome: .failed(PlayerClipExportFailure(.mux, "导出状态异常：\(candidate.status.rawValue)")))
        }
    }

    private func discard(_ handle: MediaPendingHandle, outcome: PlayerClipExportOutcome) {
        onPhase?(.cleaning)
        library.cancelPending(handle) { removed in
            if removed { self.finish(outcome) }
            else { self.finish(.failed(PlayerClipExportFailure(.mux, "无法清理剪辑暂存文件。"))) }
        }
    }

    private func publish(_ work: PreparedExport, preset: String) {
        onPhase?(.publishing)
        library.commit(work.handle) { outcome in
            switch outcome {
            case .published(let item, let indexWarning):
                guard let path = item.absolutePath else {
                    self.finish(.failed(PlayerClipExportFailure(.mux, "成片已保存，但片库未返回文件路径。")))
                    return
                }
                var notes = work.notes
                if preset != AVAssetExportPresetPassthrough { notes.append("已重编码（\(preset)）。") }
                if !self.attemptNotes.isEmpty { notes.append("较早的导出配置失败，已换档完成。") }
                if let warning = indexWarning { notes.insert(warning, at: 0) }
                self.finish(.completed(URL(fileURLWithPath: path), notes: notes))
            case .failed(let message):
                self.discard(work.handle, outcome: .failed(PlayerClipExportFailure(.mux, "无法发布剪辑：\(message)")))
            case .publishFailed(let message):
                // 导出文件已完整写入暂存区；发布失败不能删成片，留给冷启维护救援。
                self.finish(.failed(PlayerClipExportFailure(
                    .mux,
                    "无法发布剪辑：\(message)。成片已保留为暂存文件，下次启动会尝试救援。"
                )))
            }
        }
    }

    private func stopProgress() {
        progressTimer?.invalidate()
        progressTimer = nil
    }

    private func finish(_ outcome: PlayerClipExportOutcome) {
        stopProgress()
        session = nil
        let handler = completion
        completion = nil
        handler?(outcome)
    }
}
