import AVFoundation
import AudioToolbox
import CoreVideo
import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// One take, one serial queue. AVFoundation delivers samples directly on this queue so a
/// slow encoder or effect does not create an unbounded second queue of retained frames.
final class CameraMovieWriter: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate,
    AVCaptureAudioDataOutputSampleBufferDelegate {

    struct Setup {
        let configuration: RecordingConfiguration
        let effect: RenderEffectParameters
        let audioAttached: Bool
        let hevcAvailable: Bool
        let notes: [String]
    }

    enum Event {
        case started(String)
        case note(String)
        case stop(String)
        case failed(String)
    }

    enum Result {
        case completed(notes: [String])
        case discarded(String)
    }

    let queue = DispatchQueue(label: "com.wotagei.cam.record.writer", qos: .userInitiated)
    private let url: URL
    private let event: (Event) -> Void
    private var setup: Setup?
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var pixelInput: AVAssetWriterInputPixelBufferAdaptor?
    private var audioInput: AVAssetWriterInput?
    private var renderer: FrameRenderBackend?
    private var width = 0
    private var height = 0
    private var firstPTS: CMTime?
    private var lastVideoPTS: CMTime?
    private var lastAudioPTS: CMTime?
    private var lastVideoArrival: DispatchTime?
    private var lastStorageCheck = -1.0
    private var videoCount = 0
    private var audioCount = 0
    private var droppedVideo = 0
    private var droppedAudio = 0
    private var notes: [String] = []
    private var takingSamples = false
    private var failure: String?
    private var finishing = false
    private var finishReply: ((Result) -> Void)?

    init(url: URL, event: @escaping (Event) -> Void) {
        self.url = url
        self.event = event
        super.init()
    }

    func arm(_ setup: Setup) {
        queue.async {
            self.setup = setup
            self.notes = setup.notes
            self.takingSamples = true
            self.lastVideoArrival = .now()
            self.checkVideoArrival()
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard takingSamples, CMSampleBufferDataIsReady(sampleBuffer) else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        guard CMTimeGetSeconds(pts).isFinite else { return }
        autoreleasepool {
            if output is AVCaptureVideoDataOutput {
                appendVideo(sampleBuffer, at: pts)
            } else if output is AVCaptureAudioDataOutput {
                appendAudio(sampleBuffer, at: pts)
            }
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didDrop sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        if takingSamples, output is AVCaptureVideoDataOutput { droppedVideo += 1 }
    }

    private func appendVideo(_ sample: CMSampleBuffer, at pts: CMTime) {
        guard let setup = setup, let image = CMSampleBufferGetImageBuffer(sample) else { return }
        lastVideoArrival = .now()
        if let last = lastVideoPTS, CMTimeCompare(pts, last) <= 0 {
            droppedVideo += 1
            return
        }
        if writer == nil {
            do {
                try openWriter(sample: sample, image: image, at: pts, setup: setup)
            } catch {
                fail(error.localizedDescription)
                return
            }
        }
        guard let writer = writer, let input = videoInput, let start = firstPTS else { return }
        guard writer.status == .writing else {
            fail(writer.error?.localizedDescription ?? "视频编码器停止写入。")
            return
        }
        guard CVPixelBufferGetWidth(image) == width, CVPixelBufferGetHeight(image) == height else {
            finishAtBoundary("相机输出尺寸发生变化，已结束本段。")
            return
        }
        let elapsed = CMTimeGetSeconds(CMTimeSubtract(pts, start))
        if elapsed >= 300 {
            finishAtBoundary("本段已达到 5 分钟，正在保存。")
            return
        }
        if elapsed - lastStorageCheck >= 1 {
            lastStorageCheck = elapsed
            if let free = Self.freeBytes(at: url), free < 32 * 1_024 * 1_024 {
                finishAtBoundary("存储空间不足，已停止并保存当前片段。")
                return
            }
            let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            if Int64(bytes) >= RecordingTiers.maximumFileBytes - 32 * 1_024 * 1_024 {
                finishAtBoundary("本段接近文件大小上限，正在保存。")
                return
            }
        }
        guard input.isReadyForMoreMediaData else {
            droppedVideo += 1
            return
        }
        let appended: Bool
        if let pixelInput = pixelInput {
            appended = pixelInput.append(render(image, effect: setup.effect), withPresentationTime: pts)
        } else {
            appended = input.append(sample)
        }
        guard appended else {
            fail(writer.error?.localizedDescription ?? "视频帧写入失败。")
            return
        }
        videoCount += 1
        lastVideoPTS = pts
        if videoCount == 1 {
            let description = notes.joined(separator: "\n")
            post(.started(description))
        }
    }

    private func appendAudio(_ sample: CMSampleBuffer, at pts: CMTime) {
        guard let first = firstPTS, let input = audioInput, let writer = writer,
              writer.status == .writing, CMTimeCompare(pts, first) >= 0 else { return }
        if let last = lastAudioPTS, CMTimeCompare(pts, last) <= 0 {
            droppedAudio += 1
            return
        }
        guard input.isReadyForMoreMediaData else {
            droppedAudio += 1
            return
        }
        guard input.append(sample) else {
            fail(writer.error?.localizedDescription ?? "音频样本写入失败。")
            return
        }
        audioCount += 1
        lastAudioPTS = pts
    }

    private func openWriter(sample: CMSampleBuffer, image: CVPixelBuffer, at pts: CMTime,
                            setup: Setup) throws {
        let config = setup.configuration
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        self.writer = writer
        width = CVPixelBufferGetWidth(image)
        height = CVPixelBufferGetHeight(image)
        guard width > 0, height > 0 else { throw WriteError("相机未提供有效的视频尺寸。") }
        var codec: RecordingVideoCodec = config.codec == .hevc && setup.hevcAvailable ? .hevc : .h264
        func videoSettings(_ codec: RecordingVideoCodec) -> [String: Any] {
            let bitRate = RecordingBitratePolicy.resolve(userBitRate: config.userBitRate,
                width: width, height: height, frameRate: config.frameRate, codec: codec)
            // These FourCC values also work with the pre-iOS 11 AVVideoCodecKey API.
            return [AVVideoCodecKey: codec == .hevc ? "hvc1" : "avc1",
                    AVVideoWidthKey: width, AVVideoHeightKey: height,
                    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: bitRate,
                                                      AVVideoExpectedSourceFrameRateKey: config.frameRate]]
        }
        var settings = videoSettings(codec)
        if codec == .hevc, !writer.canApply(outputSettings: settings, forMediaType: .video) {
            codec = .h264
            settings = videoSettings(codec)
        }
        guard writer.canApply(outputSettings: settings, forMediaType: .video) else {
            throw WriteError("编码器不接受当前分辨率或码率，请降低录制档位。")
        }
        if codec != config.codec { notes.append("当前格式无法使用 HEVC，改用 H.264。") }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings,
                                       sourceFormatHint: CMSampleBufferGetFormatDescription(sample))
        input.expectsMediaDataInRealTime = true
        guard writer.canAdd(input) else { throw WriteError("无法连接视频编码器。") }
        writer.add(input)
        videoInput = input
        if setup.effect.isEnabled {
            pixelInput = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,
                sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                                              kCVPixelBufferWidthKey as String: width,
                                              kCVPixelBufferHeightKey as String: height])
            renderer = RenderBackendFactory.makeEchoBackend()
            if let renderer = renderer { notes.append("成片回波 · \(renderer.kind.displayName)") }
        }
        if setup.audioAttached { addAudio(to: writer, sampleRate: config.sampleRate, channels: config.channels) }
        guard writer.startWriting() else {
            throw WriteError(writer.error?.localizedDescription ?? "编码器无法启动。")
        }
        writer.startSession(atSourceTime: pts)
        firstPTS = pts
        let rate = RecordingBitratePolicy.resolve(userBitRate: config.userBitRate,
            width: width, height: height, frameRate: config.frameRate, codec: codec)
        let fps = config.frameRateIsNonIntegerStandard ? "可变帧率" : String(format: "%.2f fps", config.frameRate)
        let audio = audioInput == nil ? "无声" : "AAC · \(config.sampleRate) Hz"
        notes.insert(String(format: "%d×%d · %@ · %@ · 目标 %.1f Mbps · %@",
                            width, height, fps, codec.displayName, Double(rate) / 1_000_000, audio), at: 0)
    }

    private func addAudio(to writer: AVAssetWriter, sampleRate: Int, channels: Int) {
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC,
                                      AVSampleRateKey: sampleRate,
                                      AVNumberOfChannelsKey: max(1, channels),
                                      AVEncoderBitRateKey: RecordingBitratePolicy.audioBitRate]
        guard writer.canApply(outputSettings: settings, forMediaType: .audio) else {
            notes.append("音频编码器不接受此采样率，本次录像无声。")
            return
        }
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
        input.expectsMediaDataInRealTime = true
        guard writer.canAdd(input) else {
            notes.append("无法连接音频编码器，本次录像无声。")
            return
        }
        writer.add(input)
        audioInput = input
    }

    private func render(_ image: CVPixelBuffer, effect: RenderEffectParameters) -> CVPixelBuffer {
        guard let renderer = renderer else { return image }
        do {
            return try renderer.renderQueue.sync {
                try renderer.processEcho(frame: image, parameters: effect).pixelBuffer
            }
        } catch {
            releaseRenderer()
            let note = "回波处理失败，后续帧改为原画录制。"
            notes.append(note)
            post(.note(note))
            return image
        }
    }

    private func releaseRenderer() {
        if let renderer = renderer { renderer.renderQueue.sync { renderer.release() } }
        renderer = nil
    }

    private func checkVideoArrival() {
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self = self, self.takingSamples, let last = self.lastVideoArrival else { return }
            if DispatchTime.now().uptimeNanoseconds - last.uptimeNanoseconds >= 5_000_000_000 {
                if self.videoCount == 0 { self.fail("相机未送达视频帧，未生成录像。") }
                else { self.finishAtBoundary("相机停止送帧，正在保存已录部分。") }
            } else {
                self.checkVideoArrival()
            }
        }
    }

    private func fail(_ message: String) {
        guard failure == nil else { return }
        failure = message
        takingSamples = false
        post(.failed(message))
    }

    private func finishAtBoundary(_ note: String) {
        takingSamples = false
        post(.stop(note))
    }

    /// Called after removing the delegates. No staged file from this take can be published.
    func cancel(completion: @escaping () -> Void) {
        queue.async {
            self.takingSamples = false
            self.releaseRenderer()
            self.cancelIfWriting()
            DispatchQueue.main.async { completion() }
        }
    }

    /// Called after removing the delegates. Already delivered samples run first on this queue.
    func finish(completion: @escaping (Result) -> Void) {
        queue.async {
            guard !self.finishing else { return }
            self.finishing = true
            self.finishReply = completion
            self.takingSamples = false
            self.releaseRenderer()
            guard let writer = self.writer, writer.status == .writing, self.failure == nil,
                  let first = self.firstPTS, let last = self.lastVideoPTS,
                  self.videoCount >= 2, CMTimeGetSeconds(CMTimeSubtract(last, first)) >= 0.95 else {
                self.cancelIfWriting()
                self.reply(.discarded(self.failure ?? self.writer?.error?.localizedDescription ?? "录像不足 1 秒，未保存。"))
                return
            }
            self.videoInput?.markAsFinished()
            self.audioInput?.markAsFinished()
            writer.finishWriting { [weak self] in
                guard let self = self else { return }
                self.queue.async {
                    guard self.finishReply != nil else { return }
                    guard writer.status == .completed,
                          let bytes = (try? self.url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
                          bytes > 0 else {
                        self.reply(.discarded(writer.error?.localizedDescription ?? "录像封装失败。"))
                        return
                    }
                    let asset = AVURLAsset(url: self.url)
                    let duration = CMTimeGetSeconds(asset.duration)
                    guard duration.isFinite, duration >= 1, !asset.tracks(withMediaType: .video).isEmpty else {
                        self.reply(.discarded("录像文件不完整或不足 1 秒，未保存。"))
                        return
                    }
                    var resultNotes = Array(self.notes.dropFirst())
                    if self.audioInput != nil && (self.audioCount == 0 || asset.tracks(withMediaType: .audio).isEmpty) {
                        resultNotes.append("未收到可用的麦克风音轨，已保存无声录像。")
                    }
                    if self.droppedVideo > 0 || self.droppedAudio > 0 {
                        resultNotes.append("采集或编码来不及处理 \(self.droppedVideo) 个视频帧、\(self.droppedAudio) 个音频缓冲。")
                    }
                    self.reply(.completed(notes: resultNotes))
                }
            }
            self.queue.asyncAfter(deadline: .now() + 12) { [weak self] in
                guard let self = self, self.finishReply != nil else { return }
                self.cancelIfWriting()
                self.reply(.discarded("录像封装超时，未保存。"))
            }
        }
    }

    func expire() {
        queue.async {
            self.failure = "系统结束了后台保存时间。"
            self.takingSamples = false
            guard self.finishReply != nil else { return }
            self.cancelIfWriting()
            self.reply(.discarded(self.failure ?? "保存已取消。"))
        }
    }

    private func cancelIfWriting() {
        guard let writer = writer, writer.status == .writing || writer.status == .unknown else { return }
        writer.cancelWriting()
    }

    private func reply(_ result: Result) {
        let completion = finishReply
        finishReply = nil
        DispatchQueue.main.async { completion?(result) }
    }

    private func post(_ value: Event) {
        DispatchQueue.main.async { self.event(value) }
    }

    static func freeBytes(at url: URL) -> Int64? {
        let folder = url.deletingLastPathComponent()
        let attributes = try? FileManager.default.attributesOfFileSystem(forPath: folder.path)
        return (attributes?[.systemFreeSize] as? NSNumber)?.int64Value
    }

    private struct WriteError: LocalizedError {
        let errorDescription: String?
        init(_ message: String) { errorDescription = message }
    }
}
