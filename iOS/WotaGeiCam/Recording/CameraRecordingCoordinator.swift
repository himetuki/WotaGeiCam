import AVFoundation
import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

/// 主队列拥有一次录制的启停和发布，会话队列拥有采集连接，写入队列拥有样本与文件。
final class CameraRecordingCoordinator {
    enum State {
        case idle
        case preparing
        case recording(String)
        case saving
        case saved(MediaItem, [String])
        case failed(String)

        var isBusy: Bool {
            switch self {
            case .preparing, .recording, .saving: return true
            case .idle, .saved, .failed: return false
            }
        }
    }

    struct Selection {
        var profile = RecordingConfigurationBridge.Request()
        var echo = RenderEffectParameters.default
        var stabilization = CameraStabilizationMode.off
    }

    private final class Take {
        let id = UUID()
        let selection: Selection
        let date = Date()
        let inputs = CaptureInputs()
        var pending: MediaPendingHandle?
        var writer: CameraMovieWriter?
        var closingNote: String?
        var storageTask: UIBackgroundTaskIdentifier = .invalid

        init(selection: Selection) { self.selection = selection }
    }

    var onStateChange: ((State) -> Void)?
    var privateNotice: ((String) -> Void)?
    private(set) var state = State.idle {
        didSet { onStateChange?(state) }
    }

    private let camera: CameraSessionController
    private let library: MediaLibrary
    private var take: Take?

    init(camera: CameraSessionController, library: MediaLibrary = .shared) {
        self.camera = camera
        self.library = library
    }

    /// 麦克风只有在用户实际点下录像、且选择录入现场声时才授权和连接。
    func start(_ selection: Selection) {
        guard take == nil, UIApplication.shared.applicationState == .active else { return }
        let take = Take(selection: selection)
        self.take = take
        state = .preparing
        guard selection.profile.audioEnabled else {
            reserve(take, audioAllowed: false, tries: 0)
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            reserve(take, audioAllowed: true, tries: 0)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { [weak self, weak take] granted in
                DispatchQueue.main.async {
                    guard let self = self, let take = take, self.take === take else { return }
                    self.reserve(take, audioAllowed: granted, tries: 0)
                }
            }
        case .denied, .restricted:
            reserve(take, audioAllowed: false, tries: 0)
        @unknown default:
            reserve(take, audioAllowed: false, tries: 0)
        }
    }

    private func reserve(_ take: Take, audioAllowed: Bool, tries: Int) {
        guard self.take === take else { return }
        guard UIApplication.shared.applicationState != .background else {
            finish(take, with: .failed("已离开前台，未开始录像。"))
            return
        }
        let suffix = RecordingFileNaming.randomSuffix()
        let name = RecordingFileNaming.displayName(createdAt: take.date, suffix: suffix)
        let library = library
        library.beginPending(displayName: name) { [weak self, weak take] handle in
            guard let self = self, let take = take, self.take === take else {
                if let handle = handle { library.cancelPending(handle) }
                return
            }
            guard let handle = handle else {
                if tries < 3 { self.reserve(take, audioAllowed: audioAllowed, tries: tries + 1) }
                else { self.finish(take, with: .failed("无法在应用媒体库创建录像暂存文件。")) }
                return
            }
            take.pending = handle
            if UIApplication.shared.applicationState == .background {
                self.discard(take, reason: "已离开前台，未开始录像。")
            } else {
                self.attach(take, handle: handle, audioAllowed: audioAllowed)
            }
        }
    }

    private func attach(_ take: Take, handle: MediaPendingHandle, audioAllowed: Bool) {
        let id = take.id
        let writer = CameraMovieWriter(url: handle.url) { [weak self] event in
            self?.accept(event, id: id)
        }
        take.writer = writer
        camera.sessionQueue.async {
            let error = take.inputs.attach(
                to: self.camera, at: handle.url, writer: writer,
                selection: take.selection, audioAllowed: audioAllowed
            )
            if let error = error {
                DispatchQueue.main.async {
                    guard self.take === take else { return }
                    if case .saving = self.state { return }
                    self.discard(take, reason: error)
                }
            }
        }
    }

    private func accept(_ event: CameraMovieWriter.Event, id: UUID) {
        guard take?.id == id else { return }
        if case .saving = state { return }
        switch event {
        case .started(let description): state = .recording(description)
        case .note(let note): privateNotice?(note)
        case .stop(let note), .failed(let note): stop(reason: note)
        }
    }

    /// 准备阶段取消只撤销此次录制。即使第一帧已写入，也不发布暂存文件。
    func cancelPreparation() {
        guard case .preparing = state, let take = take else { return }
        cancel(take, reason: "已取消录像准备。")
    }

    private func cancel(_ take: Take, reason: String) {
        guard let writer = take.writer else {
            discard(take, reason: reason)
            return
        }
        state = .saving
        camera.sessionQueue.async {
            take.inputs.detach(from: self.camera.session)
            writer.cancel { self.discard(take, reason: reason) }
        }
    }

    /// 背景保存只给已开录的文件收尾。回到前台后要重新点录像。
    func stop(reason: String? = nil) {
        guard let take = take else { return }
        if case .saving = state { return }
        if case .preparing = state {
            cancel(take, reason: reason ?? "已取消录像准备。")
            return
        }
        guard let writer = take.writer else {
            discard(take, reason: reason ?? "已取消准备。")
            return
        }
        take.closingNote = reason
        state = .saving
        take.storageTask = UIApplication.shared.beginBackgroundTask(withName: "Finish WOTA video") { [weak take] in
            guard let take = take else { return }
            writer.expire()
            if take.storageTask != .invalid {
                UIApplication.shared.endBackgroundTask(take.storageTask)
                take.storageTask = .invalid
            }
        }
        camera.sessionQueue.async {
            take.inputs.detach(from: self.camera.session)
            writer.finish { result in
                guard self.take === take else { return }
                switch result {
                case .discarded(let message): self.discard(take, reason: message)
                case .completed(let notes): self.publish(take, notes: notes)
                }
            }
        }
    }

    private func publish(_ take: Take, notes: [String]) {
        guard let pending = take.pending else {
            finish(take, with: .failed("录像暂存句柄已失效。"))
            return
        }
        library.commit(pending) { result in
            switch result {
            case .published(let item, let indexWarning):
                var messages = notes
                if let warning = indexWarning { messages.insert(warning, at: 0) }
                if let closingNote = take.closingNote { messages.append(closingNote) }
                self.finish(take, with: .saved(item, messages))
            case .failed(let reason): self.discard(take, reason: "入库失败：\(reason)")
            case .publishFailed(let reason):
                // 录像已完整封装，只是最终名发布失败（如目标名被占用）。
                // 保留暂存文件交给冷启维护救援；绝不能在这里 cancelPending 删除成片。
                self.finish(take, with: .failed("入库失败：\(reason)。录像已保留为暂存文件，下次启动会尝试救援。"))
            }
        }
    }

    private func discard(_ take: Take, reason: String) {
        if let pending = take.pending {
            state = .saving
            library.cancelPending(pending) { _ in self.finish(take, with: .failed(reason)) }
        } else {
            finish(take, with: .failed(reason))
        }
    }

    private func finish(_ take: Take, with state: State) {
        if take.storageTask != .invalid {
            UIApplication.shared.endBackgroundTask(take.storageTask)
            take.storageTask = .invalid
        }
        guard self.take === take else { return }
        self.take = nil
        self.state = state
    }
}

/// 所有连接级读写都在 CameraSessionController.sessionQueue 上进行。
private final class CaptureInputs {
    private var video: AVCaptureVideoDataOutput?
    private var audio: AVCaptureAudioDataOutput?
    private var mic: AVCaptureDeviceInput?

    func attach(to camera: CameraSessionController, at url: URL, writer: CameraMovieWriter,
                selection: CameraRecordingCoordinator.Selection, audioAllowed: Bool) -> String? {
        let session = camera.session
        guard session.isRunning, !session.isInterrupted, camera.currentVideoDevice() != nil else {
            return "相机未就绪，无法开始录像。"
        }
        guard let freeBytes = CameraMovieWriter.freeBytes(at: url) else {
            return "无法读取应用媒体库的可用空间。"
        }
        let megabytes = freeBytes / (1_024 * 1_024)
        guard RecordingConfigurationBridge.hasEnoughFreeSpace(megabytes: megabytes) else {
            return "录像至少需要 \(RecordingTiers.minimumFreeMegabytes) MB 可用空间。"
        }
        let video = AVCaptureVideoDataOutput()
        video.alwaysDiscardsLateVideoFrames = true
        var effect = selection.echo
        var notes: [String] = []
        if effect.isEnabled {
            if video.availableVideoPixelFormatTypes.contains(kCVPixelFormatType_32BGRA) {
                video.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            } else {
                effect.isEnabled = false
                notes.append("相机输出不支持回波所需的像素格式，本次保留原画。")
            }
        }
        let before = camera.currentParameterState()
        session.beginConfiguration()
        guard session.canAddOutput(video) else {
            session.commitConfiguration()
            return "相机不接受当前录像配置，请降低分辨率或帧率。"
        }
        session.addOutput(video)
        self.video = video
        if selection.profile.audioEnabled {
            if audioAllowed {
                if !attachAudio(to: session) { notes.append("无法连接麦克风，本次录像无声。") }
            } else {
                notes.append("麦克风权限不可用，本次录像无声。")
            }
        }
        session.commitConfiguration()

        guard let connection = video.connection(with: .video), connection.isVideoOrientationSupported else {
            detach(from: session)
            return "当前录像连接无法设置竖屏方向。"
        }
        connection.videoOrientation = .portrait
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = selection.profile.mirrored
        } else if selection.profile.mirrored {
            notes.append("当前录像连接不支持镜像，保留相机原始方向。")
        }
        let stabilization = selection.stabilization.deviceMode
        if connection.isVideoStabilizationSupported,
           camera.currentVideoDevice()?.activeFormat.isVideoStabilizationModeSupported(stabilization) == true {
            connection.preferredVideoStabilizationMode = stabilization
        } else if selection.stabilization != .off {
            notes.append("当前录像连接无法使用所选防抖档。")
        }
        let ability = camera.currentAbilitySnapshot()
        let actual = camera.currentParameterState()
        var request = selection.profile
        request.audioEnabled = audio != nil
        let config: RecordingConfiguration
        switch RecordingConfigurationBridge.build(ability: ability, state: actual, request: request) {
        case .ready(let resolved): config = resolved
        case .unavailable(let reason):
            detach(from: session)
            return reason
        }
        if actual.resolution != before.resolution || actual.frameRate != before.frameRate {
            notes.append("接入录像输出后相机更换了采集档位，按实际送帧写入。")
        }
        let hevc: Bool
        if #available(iOS 11.0, *) {
            hevc = video.availableVideoCodecTypesForAssetWriter(writingTo: .mp4).contains(.hevc)
        } else {
            hevc = false
        }
        writer.arm(.init(configuration: config, effect: effect, audioAttached: audio != nil,
                         hevcAvailable: hevc, notes: notes + config.notes))
        video.setSampleBufferDelegate(writer, queue: writer.queue)
        audio?.setSampleBufferDelegate(writer, queue: writer.queue)
        return nil
    }

    /// 只在未提交的配置事务中调用，添加音频输出失败时撤回麦克风输入。
    private func attachAudio(to session: AVCaptureSession) -> Bool {
        guard let device = AVCaptureDevice.default(for: .audio),
              let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return false }
        session.addInput(input)
        let output = AVCaptureAudioDataOutput()
        guard session.canAddOutput(output) else {
            session.removeInput(input)
            return false
        }
        session.addOutput(output)
        self.mic = input
        self.audio = output
        return true
    }

    func detach(from session: AVCaptureSession) {
        guard video != nil || audio != nil || mic != nil else { return }
        video?.setSampleBufferDelegate(nil, queue: nil)
        audio?.setSampleBufferDelegate(nil, queue: nil)
        session.beginConfiguration()
        if let video = video, session.outputs.contains(where: { $0 === video }) { session.removeOutput(video) }
        if let audio = audio, session.outputs.contains(where: { $0 === audio }) { session.removeOutput(audio) }
        if let mic = mic, session.inputs.contains(where: { $0 === mic }) { session.removeInput(mic) }
        session.commitConfiguration()
        video = nil
        audio = nil
        mic = nil
    }
}
