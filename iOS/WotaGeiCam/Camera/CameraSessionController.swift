import AVFoundation
import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 基础相机会话控制器（UIKit / iOS 10 基线，Swift 5.6 语言兼容）。
///
/// - 所有 `AVCaptureSession` 配置与 start/stop 都在同一条串行队列 [sessionQueue] 上执行，
///   避免 Camera2 式的并发启动竞态；
/// - 权限、硬件缺失、配置失败、运行时中断都通过 [CameraSessionStateMachine] 收敛成
///   可展示的状态，而不是留一个黑屏；
/// - 预留后续录制/渲染的接入点（[configureSession] / [addOutput] / [removeOutput] /
///   [currentVideoDevice]），本任务不实现录制。
final class CameraSessionController {

    /// 会话串行队列。后续录制与渲染**必须**在同一条队列上操作 session 与设备。
    let sessionQueue: DispatchQueue

    let session: AVCaptureSession

    /// 状态变化回调，固定派发到主线程。
    var onPhaseChange: ((CameraSessionPhase) -> Void)?

    /// 当前视频输入设备输入。仅在 [sessionQueue] 上读写。
    private(set) var videoInput: AVCaptureDeviceInput?

    private let permission: CameraPermissionProviding
    private var machine = CameraSessionStateMachine()
    private var wantsSessionRunning = false
    private var isConfigured = false
    private var notificationTokens: [NSObjectProtocol] = []

    init(
        permission: CameraPermissionProviding = CameraPermissionService(),
        session: AVCaptureSession = AVCaptureSession(),
        sessionQueue: DispatchQueue = DispatchQueue(
            label: "com.wotagei.cam.capture.session",
            qos: .userInitiated
        )
    ) {
        self.permission = permission
        self.session = session
        self.sessionQueue = sessionQueue
        observeSessionNotifications()
    }

    deinit {
        notificationTokens.forEach(NotificationCenter.default.removeObserver)
    }

    // MARK: - 生命周期

    func start() {
        sessionQueue.async { [weak self] in
            self?.startOnQueue()
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.wantsSessionRunning = false
            if self.session.isRunning {
                self.session.stopRunning()
            }
            self.transition(.sessionStopped)
        }
    }

    /// 失败后由 UI 触发的重试：必要时先拆掉旧输入重新配置，再从权限状态走一遍启动流程。
    func retry() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.wantsSessionRunning = true
            if case .failed = self.machine.phase {
                self.rebuildConfiguration()
            }
            self.startOnQueue()
        }
    }

    // MARK: - 后续 B1 录制/渲染接入点（接口先行，本任务不实现录制）

    /// 在会话串行队列上执行自定义配置（例如添加 output），与启停/权限流程互斥。
    func configureSession(_ block: @escaping (AVCaptureSession) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.session.beginConfiguration()
            block(self.session)
            self.session.commitConfiguration()
        }
    }

    /// 添加 output。不支持时回调 `false`，调用方据此降级，而不是让预览崩溃。
    func addOutput(_ output: AVCaptureOutput, completion: ((Bool) -> Void)? = nil) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            var added = false
            self.session.beginConfiguration()
            if self.session.canAddOutput(output) {
                self.session.addOutput(output)
                added = true
            }
            self.session.commitConfiguration()
            if let completion = completion {
                DispatchQueue.main.async { completion(added) }
            }
        }
    }

    func removeOutput(_ output: AVCaptureOutput) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.session.beginConfiguration()
            if let index = self.session.outputs.index(where: { $0 === output }) {
                self.session.removeOutput(self.session.outputs[index])
            }
            self.session.commitConfiguration()
        }
    }

    /// 当前后置视频设备；在 [sessionQueue] 上调用。录制/渲染任务读取设备参数时使用。
    func currentVideoDevice() -> AVCaptureDevice? {
        return videoInput?.device
    }

    // MARK: - 串行队列内部实现

    /// 每次启动都把「最新意图」置为想要运行；[stop] 会立刻把它清零。
    /// 权限回调与会话通知都可能晚到，必须用该标志判定此刻是否仍然想运行，
    /// 否则会出现「已经 stop 又被重新拉起」的后台占用相机问题。
    private func startOnQueue() {
        wantsSessionRunning = true

        if machine.phase.isRunning && session.isRunning {
            return
        }

        switch permission.authorizationStatus {
        case .authorized:
            if !machine.phase.isRunning {
                transition(.requestPermission)
                transition(.permissionGranted)
            }
            configureAndStart()

        case .notDetermined:
            // 连续触发启动（首帧与回到前台）时只请求一次。
            if case .requestingPermission = machine.phase {
                return
            }
            transition(.requestPermission)
            permission.requestAccess { [weak self] granted in
                guard let self = self else { return }
                self.sessionQueue.async {
                    guard granted else {
                        self.transition(.permissionDenied(restricted: false))
                        return
                    }
                    // 等待授权期间用户可能已退到后台（stop() 已把最新意图清零）。
                    // 此时只更新状态机，绝不 startRunning：否则会重新抢占用相机。
                    guard self.wantsSessionRunning else {
                        self.transition(.sessionStopped)
                        return
                    }
                    self.transition(.permissionGranted)
                    self.configureAndStart()
                }
            }

        case .denied:
            transition(.requestPermission)
            transition(.permissionDenied(restricted: false))

        case .restricted:
            transition(.requestPermission)
            transition(.permissionDenied(restricted: true))

        @unknown default:
            transition(.requestPermission)
            transition(.permissionDenied(restricted: false))
        }
    }

    private func configureAndStart() {
        guard wantsSessionRunning else { return }

        if isConfigured {
            if !session.isRunning {
                session.startRunning()
            }
            transition(.sessionStarted)
            return
        }

        var failure: CameraFailure?

        session.beginConfiguration()
        session.sessionPreset = .high

        if let device = CameraSessionController.pickCamera() {
            do {
                let input = try AVCaptureDeviceInput(device: device)
                if session.canAddInput(input) {
                    session.addInput(input)
                    videoInput = input
                } else {
                    failure = .configurationFailed
                }
            } catch {
                failure = .configurationFailed
            }
        } else {
            failure = .cameraUnavailable
        }

        session.commitConfiguration()

        if let failure = failure {
            switch failure {
            case .cameraUnavailable:
                transition(.cameraUnavailable)
            default:
                transition(.failed(failure))
            }
            return
        }

        isConfigured = true
        if !session.isRunning {
            session.startRunning()
        }
        transition(.sessionStarted)
    }

    /// 重试失败状态时移除旧输入，避免重复添加同一设备 input。
    private func rebuildConfiguration() {
        guard !session.inputs.isEmpty || isConfigured else {
            isConfigured = false
            return
        }
        session.beginConfiguration()
        for input in session.inputs {
            session.removeInput(input)
        }
        session.commitConfiguration()
        videoInput = nil
        isConfigured = false
    }

    private static func pickCamera() -> AVCaptureDevice? {
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera],
            mediaType: .video,
            position: .back
        )
        if let device = discovery.devices.first {
            return device
        }
        // 没有后置广角时向系统要默认视频设备；两者都没有即视为无可用相机。
        return AVCaptureDevice.default(for: .video)
    }

    private func transition(_ event: CameraSessionEvent) {
        let phase = machine.apply(event)
        DispatchQueue.main.async { [weak self] in
            self?.onPhaseChange?(phase)
        }
    }

    // MARK: - 会话通知

    private func observeSessionNotifications() {
        let center = NotificationCenter.default

        notificationTokens.append(
            center.addObserver(
                forName: AVCaptureSession.wasInterruptedNotification,
                object: session,
                queue: nil
            ) { [weak self] _ in
                guard let self = self else { return }
                self.sessionQueue.async {
                    self.transition(.interrupted)
                }
            }
        )

        notificationTokens.append(
            center.addObserver(
                forName: AVCaptureSession.interruptionEndedNotification,
                object: session,
                queue: nil
            ) { [weak self] _ in
                guard let self = self else { return }
                self.sessionQueue.async {
                    guard self.wantsSessionRunning else { return }
                    if !self.session.isRunning {
                        self.session.startRunning()
                    }
                    self.transition(.resumed)
                }
            }
        )

        notificationTokens.append(
            center.addObserver(
                forName: AVCaptureSession.didStartRunningNotification,
                object: session,
                queue: nil
            ) { [weak self] _ in
                guard let self = self else { return }
                self.sessionQueue.async {
                    // 通知可能晚于 stop() 到达；已不打算运行时不得把状态带回 running。
                    guard self.wantsSessionRunning else { return }
                    self.transition(.sessionStarted)
                }
            }
        )

        notificationTokens.append(
            center.addObserver(
                forName: AVCaptureSession.runtimeErrorNotification,
                object: session,
                queue: nil
            ) { [weak self] _ in
                guard let self = self else { return }
                self.sessionQueue.async {
                    self.transition(.failed(.runtimeError))
                }
            }
        )
    }
}
