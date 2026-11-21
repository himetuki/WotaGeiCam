import Foundation
import AVFoundation
import CoreMedia
import CoreVideo
#if canImport(WotaCore)
import WotaCore
#endif

// MARK: - C2 离线光弧修复执行器
//
// 链路（与 Android `record/ArcRepairRunner.kt` 的结果语义对齐，实现为 iOS AVFoundation 原生）：
//   源 AVURLAsset → AVAssetReader（视频 BGRA）→ 逐帧计划（ArcRepairFlow）
//   → 既有 FrameRenderBackend.processArcMerge（C1 契约，不复制 Android GL/GLSL）
//   → AVAssetWriter 只写保留帧（PTS = 输出帧序号 / 目标帧率）
//   → 每条源音轨独立 AVAssetReader 直拷（原压缩样本、原时间轴，相对首帧归一）
//   → 写入 Clip 目录的 pending 暂存名 → 成功后 MediaLibrary.commit 原子发布。
//
// 失败/取消一律删除 pending 暂存文件，绝不覆盖原片，也不把半成品发布进媒体库。

/// 离线修复后台执行器（一个实例同一时刻只跑一个任务；取消只影响本实例）。
final class ArcRepairRunner {

    /// 进度回调（主线程）。
    var onProgress: ((ArcRepairProgressUpdate) -> Void)?

    /// 结束回调（主线程；成功/取消/失败三态各一次）。
    var onFinished: ((ArcRepairReport) -> Void)?

    private let library: MediaLibrary
    private let lock = NSLock()
    private var activeSession: ArcRepairSession?

    init(library: MediaLibrary = MediaLibrary.shared) {
        self.library = library
    }

    deinit {
        cancel()
    }

    var isRunning: Bool {
        lock.lock()
        let running = activeSession != nil
        lock.unlock()
        return running
    }

    /// 启动一次离线修复。已有任务在跑时立即回调 busy 失败，不排队。
    func start(
        sourceURL: URL,
        sourceFileName: String,
        configuration: ArcRepairConfiguration
    ) {
        lock.lock()
        if activeSession != nil {
            lock.unlock()
            let report = ArcRepairReport.failure(
                code: .engine,
                message: "已有修复任务在进行中。",
                notes: []
            )
            deliver(report)
            return
        }
        let session = ArcRepairSession(
            sourceURL: sourceURL,
            sourceFileName: sourceFileName,
            configuration: configuration.sanitized(),
            library: library
        )
        activeSession = session
        lock.unlock()

        session.onProgress = { [weak self] update in
            guard let self = self else { return }
            if let callback = self.onProgress {
                DispatchQueue.main.async { callback(update) }
            }
        }
        session.onFinished = { [weak self, weak session] report in
            guard let self = self else { return }
            self.lock.lock()
            if let current = self.activeSession, current === session {
                self.activeSession = nil
            }
            self.lock.unlock()
            self.deliver(report)
        }
        session.start()
    }

    /// 请求取消（非阻塞，幂等）。取消后 staging 文件由会话收尾删除。
    func cancel() {
        lock.lock()
        let session = activeSession
        lock.unlock()
        session?.requestCancel()
    }

    private func deliver(_ report: ArcRepairReport) {
        guard let callback = onFinished else { return }
        DispatchQueue.main.async {
            callback(report)
        }
    }
}

// MARK: - 单次会话

private final class ArcRepairSession {

    private struct ProbeResult {
        var rate: ArcRepairMeasuredRate
        var sourceFrames: Int
        var durationSeconds: Double
        var minimumPresentationUs: Int64
        var containerFps: Double
        var didReachEos: Bool
    }

    private struct BufferedFrame {
        var pixelBuffer: CVPixelBuffer
        var presentationTime: CMTime
    }

    private final class AudioLane {
        let label: String
        let reader: AVAssetReader
        let output: AVAssetReaderTrackOutput
        let input: AVAssetWriterInput
        var pendingSample: CMSampleBuffer?
        var done = false
        var failed = false
        var failureMessage: String?
        var copiedSamples = 0
        var lastOutputUs = Int64.min

        init(label: String, reader: AVAssetReader, output: AVAssetReaderTrackOutput, input: AVAssetWriterInput) {
            self.label = label
            self.reader = reader
            self.output = output
            self.input = input
        }

        func release() {
            if reader.status == .reading {
                reader.cancelReading()
            }
            pendingSample = nil
        }
    }

    // Android 侧同名阈值：探针上限、最短有效帧距样本、输出帧数上限。
    private static let maximumProbeSamples = 12_000
    private static let maximumSourceFrames = 2_000_000
    private static let frameReorderWindow = 8
    private static let writerReadyTimeout: TimeInterval = 10

    private let sourceURL: URL
    private let sourceFileName: String
    private let configuration: ArcRepairConfiguration
    private let library: MediaLibrary
    private let queue = DispatchQueue(label: "com.wotagei.arc.repair.session", qos: .userInitiated)
    private let stateLock = NSLock()

    var onProgress: ((ArcRepairProgressUpdate) -> Void)?
    var onFinished: ((ArcRepairReport) -> Void)?

    private var cancelled = false
    private var finished = false
    private var readers: [AVAssetReader] = []

    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var pixelAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var audioLanes: [AudioLane] = []
    private var backend: FrameRenderBackend?
    private var pendingHandle: MediaPendingHandle?

    private var sourceFpsText = "-"
    private var sourceFrames = 0
    private var outputFrames = 0
    private var targetFps = 0
    private var engineName = "-"
    private var notes: [String] = []
    private var lastReportedProgress: Float = -1

    init(
        sourceURL: URL,
        sourceFileName: String,
        configuration: ArcRepairConfiguration,
        library: MediaLibrary
    ) {
        self.sourceURL = sourceURL
        self.sourceFileName = sourceFileName
        self.configuration = configuration
        self.library = library
    }

    func start() {
        queue.async { [weak self] in
            self?.run()
        }
    }

    /// `finished` 同时被 owner 队列和任意线程的 `requestCancel` 访问，统一走锁。
    private func isFinished() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return finished
    }

    /// 首次置位返回 true；已结束返回 false（用于拒绝重复报告）。
    @discardableResult
    private func markFinished() -> Bool {
        stateLock.lock()
        if finished {
            stateLock.unlock()
            return false
        }
        finished = true
        stateLock.unlock()
        return true
    }

    /// 可从任意线程调用：只标记取消并 cancelReading（中止 blocked 的解码读取）。
    func requestCancel() {
        stateLock.lock()
        if finished || cancelled {
            stateLock.unlock()
            return
        }
        cancelled = true
        let snapshot = readers
        stateLock.unlock()
        for reader in snapshot {
            reader.cancelReading()
        }
    }

    private var isCancelled: Bool {
        stateLock.lock()
        let value = cancelled
        stateLock.unlock()
        return value
    }

    // MARK: - 主流程

    private func run() {
        do {
            postProgress(fraction: 0, message: "正在读取源视频…", canCancel: true)
            let asset = AVURLAsset(url: sourceURL, options: nil)
            let videoTracks = asset.tracks(withMediaType: .video)
            guard let videoTrack = videoTracks.first else {
                throw Failure(.source, "源文件没有视频轨。")
            }
            let durationSeconds = resolvedDurationSeconds(asset: asset, track: videoTrack)
            guard durationSeconds > 0 else {
                throw Failure(.source, "无法读取源视频时长。")
            }
            let dimensions = try videoDimensions(track: videoTrack)
            targetFps = configuration.targetFps
            let containerFps = Double(videoTrack.nominalFrameRate)
            let probe = try probeRate(asset: asset, videoTrack: videoTrack, durationSeconds: durationSeconds)
            sourceFrames = probe.sourceFrames
            if probe.rate.atMost(targetFps) {
                sourceFpsText = "\(probe.rate.roundedFps)fps"
                throw Failure(
                    .noNeed,
                    "源视频实测约 \(probe.rate.roundedFps)fps，不高于目标 \(targetFps)fps，无需抽帧修复。"
                )
            }
            sourceFpsText = "\(probe.rate.roundedFps)fps（真值 \(probe.rate.text)）"
            notes.append("源实测约 \(probe.rate.roundedFps)fps（真值 \(probe.rate.text)，容器标 \(String(format: "%.2f", containerFps))fps）→ 目标 \(targetFps)fps。")
            notes.append("被抽帧画面按差分死区 D=\(configuration.deadband) 并入前后保留帧；原片不修改。")
            if !probe.didReachEos {
                notes.append("源较长，帧数按时长×实测帧率估算，用于进度与输出计划。")
            }

            let plan = ArcRepairPlan(
                srcNum: probe.rate.num,
                srcDen: probe.rate.den,
                dstFps: targetFps,
                srcFrames: sourceFrames
            )
            guard plan.dstFrames > 0 else {
                throw Failure(.source, "按当前帧率计算出的输出帧数为 0。")
            }
            let flow = ArcRepairFlow(plan: plan)
            let basePtsUs = probe.minimumPresentationUs

            try ensureNotCancelled()
            let handle = try makePendingHandle(sourceFps: probe.rate.roundedFps)
            pendingHandle = handle

            let renderer = makeBackend()
            backend = renderer
            engineName = renderer.kind.displayName
            notes.append("图像后端 \(engineName)。")
            if renderer.kind == .cpu {
                notes.append("CPU 后端在内部工作分辨率累积，弧细节较 GPU 略柔。")
            }

            try runPipeline(
                asset: asset,
                videoTrack: videoTrack,
                dimensions: dimensions,
                plan: plan,
                flow: flow,
                basePtsUs: basePtsUs,
                durationSeconds: durationSeconds
            )
        } catch let failure as Failure {
            abort(code: failure.code, message: failure.message)
        } catch {
            abort(code: .engine, message: "修复异常：\(error.localizedDescription)")
        }
    }

    private func runPipeline(
        asset: AVAsset,
        videoTrack: AVAssetTrack,
        dimensions: VideoDimensions,
        plan: ArcRepairPlan,
        flow: ArcRepairFlow,
        basePtsUs: Int64,
        durationSeconds: Double
    ) throws {
        let videoReader = try AVAssetReader(asset: asset)
        let videoSettings: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()
        ]
        let videoOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: videoSettings)
        guard videoReader.canAdd(videoOutput) else {
            throw Failure(.reader, "无法建立视频解码输出。")
        }
        videoReader.add(videoOutput)
        register(reader: videoReader)

        let writer = try AVAssetWriter(outputURL: pendingHandle!.url, fileType: .mp4)
        self.writer = writer
        let videoInput = try configureVideoWriter(writer: writer, track: videoTrack, dimensions: dimensions)
        self.videoInput = videoInput
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
                kCVPixelBufferWidthKey as String: dimensions.width,
                kCVPixelBufferHeightKey as String: dimensions.height
            ]
        )
        self.pixelAdaptor = adaptor
        try configureAudioWriter(writer: writer, asset: asset)

        guard writer.startWriting() else {
            throw Failure(.writer, writer.error?.localizedDescription ?? "视频编码器无法启动。")
        }
        writer.startSession(atSourceTime: .zero)

        guard videoReader.startReading() else {
            throw Failure(.reader, videoReader.error?.localizedDescription ?? "视频解码器无法启动。")
        }
        for lane in audioLanes where !lane.failed {
            if !lane.reader.startReading() {
                lane.failed = true
                lane.failureMessage = lane.reader.error?.localizedDescription ?? "音频解码器无法启动。"
            }
        }

        // 先排空 0 时刻之前的可写音频（负 PTS 在 lane 内丢弃）。
        drainAudio(upToUs: 0, basePtsUs: basePtsUs)

        let deadline = Date().addingTimeInterval(max(120, durationSeconds * 8 + 120))
        var buffered: [BufferedFrame] = []
        buffered.reserveCapacity(ArcRepairSession.frameReorderWindow + 1)
        var sourceIndex = 0
        var lastProcessedPts = CMTime.invalid

        while true {
            try ensureNotCancelled()
            if Date() > deadline {
                throw Failure(.timeout, "修复超时，已停止并清理暂存文件。")
            }
            guard let sample = videoOutput.copyNextSampleBuffer() else { break }
            let pts = CMSampleBufferGetPresentationTimeStamp(sample)
            guard let image = CMSampleBufferGetImageBuffer(sample) else {
                throw Failure(.reader, "解码帧没有像素缓冲。")
            }
            if CMTimeGetSeconds(pts).isFinite {
                if lastProcessedPts.isValid && CMTimeCompare(pts, lastProcessedPts) < 0 {
                    notes.append("检测到解码序早于已处理帧的样本，已按最多 \(ArcRepairSession.frameReorderWindow) 帧缓冲重排。")
                }
                buffered.append(BufferedFrame(pixelBuffer: image, presentationTime: pts))
                buffered.sort { CMTimeCompare($0.presentationTime, $1.presentationTime) < 0 }
            }
            while buffered.count > ArcRepairSession.frameReorderWindow {
                let frame = buffered.removeFirst()
                lastProcessedPts = frame.presentationTime
                try processSourceFrame(
                    pixelBuffer: frame.pixelBuffer,
                    sourceIndex: sourceIndex,
                    flow: flow,
                    plan: plan,
                    basePtsUs: basePtsUs
                )
                sourceIndex += 1
            }
        }

        if videoReader.status == .failed {
            throw Failure(.reader, videoReader.error?.localizedDescription ?? "视频解码失败。")
        }
        while !buffered.isEmpty {
            try ensureNotCancelled()
            let frame = buffered.removeFirst()
            lastProcessedPts = frame.presentationTime
            try processSourceFrame(
                pixelBuffer: frame.pixelBuffer,
                sourceIndex: sourceIndex,
                flow: flow,
                plan: plan,
                basePtsUs: basePtsUs
            )
            sourceIndex += 1
        }

        let eosOperations = flow.onSourceEos()
        try execute(operations: eosOperations, currentFrame: nil, plan: plan, basePtsUs: basePtsUs)

        // 尾巴上的音频也要写出去。
        drainAudio(upToUs: Int64.max, basePtsUs: basePtsUs)
        releaseReadersAndRenderer()

        for lane in audioLanes {
            lane.input.markAsFinished()
        }
        videoInput.markAsFinished()

        try ensureNotCancelled()
        postProgress(fraction: 0.97, message: "正在封装成片…", canCancel: false)
        finishWriting(writer: writer)
    }

    // MARK: - 帧处理

    private func processSourceFrame(
        pixelBuffer: CVPixelBuffer,
        sourceIndex: Int,
        flow: ArcRepairFlow,
        plan: ArcRepairPlan,
        basePtsUs: Int64
    ) throws {
        let operations = flow.onSourceFrame(srcIndex: sourceIndex)
        if operations.isEmpty { return }
        try execute(operations: operations, currentFrame: pixelBuffer, plan: plan, basePtsUs: basePtsUs)
    }

    private func execute(
        operations: [ArcRepairOperation],
        currentFrame: CVPixelBuffer?,
        plan: ArcRepairPlan,
        basePtsUs: Int64
    ) throws {
        var pendingBuffer: CVPixelBuffer?
        var accumulatedBuffer: CVPixelBuffer?
        if let existing = pendingPixelBuffer { pendingBuffer = existing }
        if let existing = accumulatedPixelBuffer { accumulatedBuffer = existing }

        for operation in operations {
            try ensureNotCancelled()
            switch operation {
            case .holdCurAsPending:
                guard let frame = currentFrame else {
                    throw Failure(.engine, "状态机缺少首枚保留帧。")
                }
                pendingBuffer = try renderFresh(frame: frame)
            case .accumulate(let fresh):
                guard let frame = currentFrame else {
                    throw Failure(.engine, "状态机缺少被抽帧。")
                }
                if fresh || accumulatedBuffer == nil {
                    accumulatedBuffer = try renderFresh(frame: frame)
                } else {
                    accumulatedBuffer = try renderAccumulate(frame: frame)
                }
            case .foldAccIntoPending:
                guard let kept = pendingBuffer, let dropped = accumulatedBuffer else {
                    throw Failure(.engine, "状态机缺少待发帧或累积面。")
                }
                pendingBuffer = try renderMerge(kept: kept, dropped: dropped)
            case .emit(let outputIndex):
                guard let pending = pendingBuffer else {
                    throw Failure(.engine, "状态机缺少待发帧。")
                }
                try appendVideoFrame(pending, outputIndex: outputIndex, plan: plan, basePtsUs: basePtsUs)
            case .mergeCurAsPending(let useAcc):
                guard let frame = currentFrame else {
                    throw Failure(.engine, "状态机缺少后一枚保留帧。")
                }
                if useAcc, let dropped = accumulatedBuffer {
                    pendingBuffer = try renderMerge(kept: frame, dropped: dropped)
                } else {
                    pendingBuffer = try renderFresh(frame: frame)
                }
            }
        }
        pendingPixelBuffer = pendingBuffer
        accumulatedPixelBuffer = accumulatedBuffer
    }

    private var pendingPixelBuffer: CVPixelBuffer?
    private var accumulatedPixelBuffer: CVPixelBuffer?

    private func renderFresh(frame: CVPixelBuffer) throws -> CVPixelBuffer {
        guard let backend = backend else {
            throw Failure(.render, "渲染后端未就绪。")
        }
        let parameters = ArcMergeParameters(deadband: configuration.deadband).sanitized()
        do {
            return try backend.renderQueue.sync {
                backend.resetEchoHistory()
                let output = try backend.processArcMerge(frame: frame, parameters: parameters)
                return output.pixelBuffer
            }
        } catch {
            throw Failure(.render, "补弧渲染失败：\(error.localizedDescription)")
        }
    }

    /// `acc = select(acc, frame)`（后端自身持有累积面）。
    private func renderAccumulate(frame: CVPixelBuffer) throws -> CVPixelBuffer {
        guard let backend = backend else {
            throw Failure(.render, "渲染后端未就绪。")
        }
        let parameters = ArcMergeParameters(deadband: configuration.deadband).sanitized()
        do {
            return try backend.renderQueue.sync {
                let output = try backend.processArcMerge(frame: frame, parameters: parameters)
                return output.pixelBuffer
            }
        } catch {
            throw Failure(.render, "补弧渲染失败：\(error.localizedDescription)")
        }
    }

    /// `select(kept, dropped)`：先以 kept 重置累积面，再把 dropped 并进去。
    ///
    /// 顺序不能反——差分死区判据 `Δluma = luma(dropped) − luma(kept) > D` 是单向的，
    /// 把两帧倒过来喂会得到不同的像素选择。
    private func renderMerge(kept: CVPixelBuffer, dropped: CVPixelBuffer) throws -> CVPixelBuffer {
        guard let backend = backend else {
            throw Failure(.render, "渲染后端未就绪。")
        }
        let parameters = ArcMergeParameters(deadband: configuration.deadband).sanitized()
        do {
            return try backend.renderQueue.sync {
                backend.resetEchoHistory()
                _ = try backend.processArcMerge(frame: kept, parameters: parameters)
                let output = try backend.processArcMerge(frame: dropped, parameters: parameters)
                return output.pixelBuffer
            }
        } catch {
            throw Failure(.render, "补弧渲染失败：\(error.localizedDescription)")
        }
    }

    private func appendVideoFrame(
        _ pixelBuffer: CVPixelBuffer,
        outputIndex: Int,
        plan: ArcRepairPlan,
        basePtsUs: Int64
    ) throws {
        guard let input = videoInput, let adaptor = pixelAdaptor else {
            throw Failure(.writer, "视频编码器未就绪。")
        }
        guard let writer = writer, writer.status == .writing else {
            throw Failure(.writer, writer?.error?.localizedDescription ?? "视频编码器已停止。")
        }
        let time = CMTime(value: Int64(outputIndex), timescale: CMTimeScale(targetFps))
        if !waitUntilReady(input, timeout: ArcRepairSession.writerReadyTimeout) {
            throw Failure(.writer, "等待视频编码器空闲超时。")
        }
        if !adaptor.append(pixelBuffer, withPresentationTime: time) {
            throw Failure(.writer, writer.error?.localizedDescription ?? "视频帧写入失败。")
        }
        outputFrames += 1
        let doneUs = Int64(outputIndex) * 1_000_000 / Int64(max(1, targetFps))
        drainAudio(upToUs: doneUs, basePtsUs: basePtsUs)
        let done = ArcRepairPlan.progressOf(doneFrames: outputFrames, totalFrames: plan.totalFrames)
        postProgress(
            fraction: 0.05 + done * 0.90,
            message: "正在补弧：已输出 \(outputFrames) / \(plan.totalFrames) 帧",
            canCancel: true
        )
    }

    // MARK: - 音频直拷

    private func configureAudioWriter(writer: AVAssetWriter, asset: AVAsset) throws {
        let audioTracks = asset.tracks(withMediaType: .audio)
        if audioTracks.isEmpty {
            notes.append("源无音轨，输出为无声视频。")
            return
        }

        var addedInputs = 0
        var index = 0
        for track in audioTracks {
            index += 1
            let label = "audio\(index)"
            var formatHint: CMFormatDescription?
            // formatDescriptions 的元素按 Any 桥接；对 CoreFoundation 类型
            // `as?` 恒成功属编译错误，必须用非条件 `as`。
            formatHint = track.formatDescriptions.first.map { $0 as! CMFormatDescription }
            let input = AVAssetWriterInput(
                mediaType: .audio,
                outputSettings: nil,
                sourceFormatHint: formatHint
            )
            input.expectsMediaDataInRealTime = false
            if !writer.canAdd(input) {
                notes.append("\(label) 音轨无法写入 MP4（格式不兼容），该轨未能保留。")
                continue
            }
            let reader: AVAssetReader
            do {
                reader = try AVAssetReader(asset: asset)
            } catch {
                notes.append("\(label) 音轨读取器建立失败：\(error.localizedDescription)")
                continue
            }
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
            guard reader.canAdd(output) else {
                notes.append("\(label) 音轨无法建立直拷输出。")
                continue
            }
            reader.add(output)
            writer.add(input)
            register(reader: reader)
            audioLanes.append(AudioLane(label: label, reader: reader, output: output, input: input))
            addedInputs += 1
        }

        if addedInputs == 0 {
            throw Failure(.audio, "源含 \(audioTracks.count) 条音轨，但编码器无法保留任何一条；已取消，避免生成无声成片。")
        }
        if addedInputs < audioTracks.count {
            notes.append("源 \(audioTracks.count) 条音轨中有 \(audioTracks.count - addedInputs) 条无法保留。")
        }
    }

    private func drainAudio(upToUs limitUs: Int64, basePtsUs: Int64) {
        for lane in audioLanes where !lane.done && !lane.failed {
            drainAudio(lane: lane, upToUs: limitUs, basePtsUs: basePtsUs)
        }
    }

    private func drainAudio(lane: AudioLane, upToUs limitUs: Int64, basePtsUs: Int64) {
        while !isCancelled && !lane.done && !lane.failed {
            var sample = lane.pendingSample
            if sample == nil {
                sample = lane.output.copyNextSampleBuffer()
                if sample == nil {
                    lane.done = true
                    if lane.reader.status == .failed {
                        lane.failed = true
                        lane.failureMessage = lane.reader.error?.localizedDescription ?? "音频读取失败。"
                    }
                    return
                }
            }
            guard let current = sample else { return }
            let seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(current))
            if !seconds.isFinite {
                lane.pendingSample = nil
                continue
            }
            let sourceUs = Int64((seconds * 1_000_000).rounded())
            let normalizedUs = sourceUs - basePtsUs
            if normalizedUs < 0 {
                lane.pendingSample = nil
                continue
            }
            if normalizedUs > limitUs {
                lane.pendingSample = current
                return
            }
            var outputUs = normalizedUs
            if lane.lastOutputUs != Int64.min && outputUs <= lane.lastOutputUs {
                outputUs = lane.lastOutputUs + 1
            }
            guard waitUntilReady(lane.input, timeout: ArcRepairSession.writerReadyTimeout) else {
                lane.failed = true
                lane.failureMessage = "等待音频编码器空闲超时。"
                return
            }
            guard let shifted = copySample(
                current,
                presentationTime: CMTime(value: outputUs, timescale: 1_000_000)
            ) else {
                lane.failed = true
                lane.failureMessage = "音频样本时间戳复制失败。"
                return
            }
            if !lane.input.append(shifted) {
                lane.failed = true
                lane.failureMessage = writer?.error?.localizedDescription ?? "音频样本写入失败。"
                return
            }
            lane.lastOutputUs = outputUs
            lane.copiedSamples += 1
            lane.pendingSample = nil
        }
    }

    private func copySample(_ sample: CMSampleBuffer, presentationTime: CMTime) -> CMSampleBuffer? {
        var timing = CMSampleTimingInfo(
            duration: CMSampleBufferGetDuration(sample),
            presentationTimeStamp: presentationTime,
            decodeTimeStamp: CMTime.invalid
        )
        var copy: CMSampleBuffer?
        let status = CMSampleBufferCreateCopyWithNewTiming(
            allocator: kCFAllocatorDefault,
            sampleBuffer: sample,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleBufferOut: &copy
        )
        guard status == noErr else { return nil }
        return copy
    }

    // MARK: - 编码器配置

    private struct VideoDimensions {
        var width: Int
        var height: Int
    }

    private func videoDimensions(track: AVAssetTrack) throws -> VideoDimensions {
        let size = track.naturalSize
        let width = Int(size.width.rounded())
        let height = Int(size.height.rounded())
        guard width > 1, height > 1 else {
            throw Failure(.source, "无法读取源视频尺寸。")
        }
        guard width % 2 == 0, height % 2 == 0 else {
            throw Failure(.source, "源视频尺寸 \(width)×\(height) 不是偶数，当前编码器无法处理。")
        }
        return VideoDimensions(width: width, height: height)
    }

    private func configureVideoWriter(
        writer: AVAssetWriter,
        track: AVAssetTrack,
        dimensions: VideoDimensions
    ) throws -> AVAssetWriterInput {
        let bitRate = resolvedBitRate(width: dimensions.width, height: dimensions.height, fps: targetFps)
        let compression: [String: Any] = [
            AVVideoAverageBitRateKey: bitRate,
            AVVideoExpectedSourceFrameRateKey: targetFps,
            AVVideoMaxKeyFrameIntervalKey: max(1, targetFps * 2),
            AVVideoAllowFrameReorderingKey: false
        ]
        let settings: [String: Any] = [
            AVVideoCodecKey: "avc1",
            AVVideoWidthKey: dimensions.width,
            AVVideoHeightKey: dimensions.height,
            AVVideoCompressionPropertiesKey: compression
        ]
        guard writer.canApply(outputSettings: settings, forMediaType: .video) else {
            throw Failure(.writer, "编码器不接受 \(dimensions.width)×\(dimensions.height) 的 H.264 参数。")
        }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        input.transform = track.preferredTransform
        guard writer.canAdd(input) else {
            throw Failure(.writer, "无法连接视频编码器。")
        }
        writer.add(input)
        return input
    }

    private func resolvedBitRate(width: Int, height: Int, fps: Int) -> Int {
        #if canImport(WotaCore)
        return RecordingBitratePolicy.resolve(
            userBitRate: 0,
            width: width,
            height: height,
            frameRate: Double(fps),
            codec: .h264
        )
        #else
        let pixels = Double(width) * Double(height)
        let estimate = Int(pixels * Double(fps) * 0.2)
        return max(1_000_000, min(50_000_000, estimate))
        #endif
    }

    // MARK: - 源帧率探针

    private func probeRate(
        asset: AVAsset,
        videoTrack: AVAssetTrack,
        durationSeconds: Double
    ) throws -> ProbeResult {
        let fallbackFps: Double = videoTrack.nominalFrameRate > 0
            ? Double(videoTrack.nominalFrameRate)
            : 30
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: nil)
        guard reader.canAdd(output) else {
            throw Failure(.reader, "无法建立源帧率探针。")
        }
        reader.add(output)
        register(reader: reader)
        guard reader.startReading() else {
            unregister(reader: reader)
            throw Failure(.reader, reader.error?.localizedDescription ?? "无法读取源视频样本。")
        }

        var timestamps: [Int64] = []
        timestamps.reserveCapacity(ArcRepairSession.maximumProbeSamples)
        var count = 0
        var didReachEos = false
        var minimumUs = Int64.max
        while count < ArcRepairSession.maximumProbeSamples {
            if isCancelled {
                reader.cancelReading()
                unregister(reader: reader)
                throw Failure(.cancelled, "已取消。")
            }
            guard let sample = output.copyNextSampleBuffer() else {
                didReachEos = reader.status != .failed
                break
            }
            let seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
            if seconds.isFinite {
                let us = Int64((seconds * 1_000_000).rounded())
                timestamps.append(us)
                if us < minimumUs { minimumUs = us }
            }
            count += 1
        }
        if !didReachEos && reader.status == .failed {
            let message = reader.error?.localizedDescription ?? "源视频样本读取失败。"
            reader.cancelReading()
            unregister(reader: reader)
            throw Failure(.reader, message)
        }
        if reader.status == .reading {
            reader.cancelReading()
        }
        unregister(reader: reader)

        let measured = ArcRepairMeasuredRate.measured(fromTimestampsUs: timestamps, fallbackFps: fallbackFps)
        let rate = measured.isValid ? measured : ArcRepairMeasuredRate.fallback(fps: fallbackFps)
        var sourceFrames = count
        if !didReachEos {
            let estimate = durationSeconds * Double(rate.num) / Double(rate.den)
            sourceFrames = Int(estimate.rounded())
        }
        if sourceFrames < 1 { sourceFrames = 1 }
        if sourceFrames > ArcRepairSession.maximumSourceFrames {
            sourceFrames = ArcRepairSession.maximumSourceFrames
        }
        let base = minimumUs == Int64.max ? 0 : minimumUs
        return ProbeResult(
            rate: rate,
            sourceFrames: sourceFrames,
            durationSeconds: durationSeconds,
            minimumPresentationUs: base,
            containerFps: Double(videoTrack.nominalFrameRate),
            didReachEos: didReachEos
        )
    }

    private func resolvedDurationSeconds(asset: AVAsset, track: AVAssetTrack) -> Double {
        let assetSeconds = CMTimeGetSeconds(asset.duration)
        if assetSeconds.isFinite && assetSeconds > 0 {
            return assetSeconds
        }
        let trackSeconds = CMTimeGetSeconds(track.timeRange.duration)
        if trackSeconds.isFinite && trackSeconds > 0 {
            return trackSeconds
        }
        return 0
    }

    // MARK: - 发布

    private func makePendingHandle(sourceFps: Int) throws -> MediaPendingHandle {
        do {
            try library.fileStore.ensureDirectories()
        } catch {
            throw Failure(.noOutput, "无法准备 Clip 目录：\(error.localizedDescription)")
        }
        let baseName = ArcRepairNaming.outputFileName(
            sourceFileName: sourceFileName,
            sourceFps: sourceFps,
            dstFps: targetFps
        )
        return try makePendingHandle(baseName: baseName)
    }

    private func makePendingHandle(baseName: String) throws -> MediaPendingHandle {
        for index in 0..<64 {
            let candidate = ArcRepairNaming.fileName(baseName, collisionIndex: index)
            if let handle = library.fileStore.makePendingHandle(
                relativeDirectory: MediaStorageLayout.clipRelativeDirectory,
                displayName: candidate
            ) {
                return handle
            }
        }
        throw Failure(.noOutput, "无法创建暂存文件（目标名已存在或非法）。")
    }

    private func finishWriting(writer: AVAssetWriter) {
        writer.finishWriting { [weak self] in
            guard let self = self else { return }
            self.queue.async {
                self.completeWriting(writer: writer)
            }
        }
    }

    private func completeWriting(writer: AVAssetWriter) {
        guard !isFinished() else { return }
        if isCancelled {
            writer.cancelWriting()
            abort(code: .cancelled, message: "已取消，未生成新片。")
            return
        }
        guard writer.status == .completed else {
            abort(
                code: .writer,
                message: writer.error?.localizedDescription ?? "视频封装失败。"
            )
            return
        }
        guard let handle = pendingHandle else {
            abort(code: .noOutput, message: "暂存句柄丢失。")
            return
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: handle.url.path)[.size] as? NSNumber)?
            .int64Value ?? 0
        guard FileManager.default.fileExists(atPath: handle.url.path), size > 0 else {
            abort(code: .writer, message: "封装完成但输出文件为空。")
            return
        }

        let audioFailure = evaluateAudioOutcome()
        if let failure = audioFailure {
            abort(code: .audio, message: failure)
            return
        }

        postProgress(fraction: 0.99, message: "正在入库…", canCancel: false)
        library.commit(handle) { [weak self] outcome in
            guard let self = self else { return }
            self.queue.async {
                switch outcome {
                case .published(let item, let indexWarning):
                    if let warning = indexWarning { self.notes.append(warning) }
                    guard let path = item.absolutePath else {
                        self.library.cancelPending(handle, completion: nil)
                        self.abort(code: .noOutput, message: "入库后没有绝对路径。")
                        return
                    }
                    guard self.markFinished() else { return }
                    self.stateLock.lock()
                    let snapshot = self.readers
                    self.readers = []
                    self.stateLock.unlock()
                    for reader in snapshot { reader.cancelReading() }
                    let report = ArcRepairReport(
                        outcome: .published,
                        outputURL: URL(fileURLWithPath: path, isDirectory: false),
                        mediaId: item.mediaId,
                        outputBytes: item.sizeBytes,
                        errorCode: nil,
                        message: "已保存到剪辑库：\(item.fileName)",
                        notes: self.notes,
                        sourceFpsText: self.sourceFpsText,
                        sourceFrames: self.sourceFrames,
                        outputFrames: self.outputFrames,
                        targetFps: self.targetFps,
                        engineName: self.engineName
                    )
                    self.postProgress(fraction: 1, message: report.message, canCancel: false)
                    self.onFinished?(report)
                case .failed(let message):
                    self.library.cancelPending(handle, completion: nil)
                    self.abort(code: .noOutput, message: "入库失败：\(message)")
                case .publishFailed(let message):
                    // 修复成片已完整封装；发布失败只保留暂存文件，不删成片。
                    self.abort(code: .noOutput,
                               message: "入库失败：\(message)。成片已保留为暂存文件，下次启动会尝试救援。",
                               retainPending: true)
                }
            }
        }
    }

    /// 音轨收尾判定；返回非 nil 表示必须失败（避免把无声/丢轨成片当成功发布）。
    private func evaluateAudioOutcome() -> String? {
        if audioLanes.isEmpty { return nil }
        let copied = audioLanes.reduce(0) { $0 + $1.copiedSamples }
        let failed = audioLanes.filter { $0.failed }
        if copied == 0 && !failed.isEmpty {
            let detail = failed.compactMap { $0.failureMessage }.first ?? "未知音轨错误"
            return "原音轨未能保留（\(detail)）；已取消保存，避免生成无声成片。"
        }
        if copied == 0 {
            notes.append("源音轨没有可写样本，输出为无声视频。")
            return nil
        }
        if !failed.isEmpty {
            notes.append("部分音轨未能完整保留；成片仍含 \(copied) 个已复制音频样本。")
        } else if audioLanes.count == 1 {
            notes.append("原音轨已直接复制（AAC 样本未重编码）。")
        } else {
            notes.append("原 \(audioLanes.count) 条音轨已直接复制（AAC 样本未重编码）。")
        }
        return nil
    }

    // MARK: - 中止 / 清理

    private struct Failure: Error {
        let code: ArcRepairFailureCode
        let message: String

        init(_ code: ArcRepairFailureCode, _ message: String) {
            self.code = code
            self.message = message
        }
    }

    private func abort(code: ArcRepairFailureCode, message: String, retainPending: Bool = false) {
        guard markFinished() else { return }
        let handle = pendingHandle
        pendingHandle = nil
        releaseReadersAndRenderer()
        if let writer = writer, writer.status == .writing {
            writer.cancelWriting()
        }
        writer = nil
        videoInput = nil
        pixelAdaptor = nil
        pendingPixelBuffer = nil
        accumulatedPixelBuffer = nil
        audioLanes = []
        if let handle = handle, !retainPending {
            library.cancelPending(handle, completion: nil)
        }
        let report: ArcRepairReport
        if code == .cancelled {
            report = ArcRepairReport.cancelled(
                sourceFpsText: sourceFpsText,
                sourceFrames: sourceFrames,
                outputFrames: outputFrames,
                targetFps: targetFps,
                engineName: engineName
            )
        } else {
            report = ArcRepairReport.failure(
                code: code,
                message: "\(message)（\(code.rawValue)）",
                notes: notes,
                sourceFpsText: sourceFpsText,
                sourceFrames: sourceFrames,
                outputFrames: outputFrames,
                targetFps: targetFps,
                engineName: engineName
            )
        }
        postProgress(fraction: lastReportedProgress < 0 ? 0 : lastReportedProgress,
                     message: report.message,
                     canCancel: false)
        onFinished?(report)
    }

    private func releaseReadersAndRenderer() {
        stateLock.lock()
        let snapshot = readers
        readers = []
        stateLock.unlock()
        for reader in snapshot {
            if reader.status == .reading { reader.cancelReading() }
        }
        for lane in audioLanes {
            lane.release()
        }
        if let backend = backend {
            backend.renderQueue.sync {
                backend.release()
            }
            self.backend = nil
        }
    }

    private func register(reader: AVAssetReader) {
        stateLock.lock()
        readers.append(reader)
        stateLock.unlock()
        if isCancelled {
            reader.cancelReading()
        }
    }

    private func unregister(reader: AVAssetReader) {
        stateLock.lock()
        if let index = readers.firstIndex(where: { $0 === reader }) {
            readers.remove(at: index)
        }
        stateLock.unlock()
    }

    // MARK: - 工具

    private func ensureNotCancelled() throws {
        if isCancelled {
            throw Failure(.cancelled, "已取消。")
        }
    }

    private func waitUntilReady(_ input: AVAssetWriterInput, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !input.isReadyForMoreMediaData {
            if isCancelled { return false }
            if Date() > deadline { return false }
            Thread.sleep(forTimeInterval: 0.002)
        }
        return true
    }

    private func makeBackend() -> FrameRenderBackend {
        switch configuration.backendPreference {
        case .cpu:
            return RenderBackendFactory.makeEchoBackend(preferred: .cpu)
        case .automatic:
            return RenderBackendFactory.makeEchoBackend(preferred: nil)
        }
    }

    private func postProgress(fraction: Float, message: String, canCancel: Bool) {
        let clamped = max(0, min(1, fraction))
        if clamped - lastReportedProgress < 0.005 && clamped < 1 && lastReportedProgress >= 0 {
            return
        }
        lastReportedProgress = clamped
        let update = ArcRepairProgressUpdate(fraction: clamped, message: message, canCancel: canCancel)
        onProgress?(update)
    }
}
