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

    /// 预览宿主注册的预览层视频连接（预览连接不属于 `session.outputs`）。
    /// 仅在 [sessionQueue] 上读写；由 [setPreviewVideoConnection] 从主线程转入。
    private var previewVideoConnection: AVCaptureConnection?

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
            if let index = self.session.outputs.firstIndex(where: { $0 === output }) {
                self.session.removeOutput(self.session.outputs[index])
            }
            self.session.commitConfiguration()
        }
    }

    /// 当前后置视频设备；在 [sessionQueue] 上调用。录制/渲染任务读取设备参数时使用。
    func currentVideoDevice() -> AVCaptureDevice? {
        return videoInput?.device
    }

    /// 当前所有视频连接：output 连接在前（录制优先），预览连接在后。**只在 [sessionQueue] 上调用**。
    ///
    /// 预览连接不属于 `session.outputs`，由预览宿主通过 [setPreviewVideoConnection] 注册；
    /// 未注册时这里只返回 output 连接，调用方必须如实说明「预览连接未覆盖」而不是假定两者都在。
    func videoConnections() -> [AVCaptureConnection] {
        var result: [AVCaptureConnection] = []
        for output in session.outputs {
            if let connection = output.connection(with: .video) {
                result.append(connection)
            }
        }
        if let preview = previewVideoConnection,
           !result.contains(where: { $0 === preview }) {
            result.append(preview)
        }
        return result
    }

    /// 预览宿主注册预览层连接（防抖等连接级参数需要同时对预览与输出下发）。
    ///
    /// 主线程调用；内部转到 [sessionQueue] 存储，读取也只在 [sessionQueue] 上。
    /// 预览层连接可能晚于会话建立才可用（例如 `session` 刚设置时仍为 nil），
    /// 宿主应在会话启动/布局后重新调用本方法。
    func setPreviewVideoConnection(_ connection: AVCaptureConnection?) {
        sessionQueue.async { [weak self] in
            if let connection = connection, connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
            self?.previewVideoConnection = connection
        }
    }

    // MARK: - B1 摄像头 / 分辨率 / 帧率 / 慢门接口

    /// 能力探测器：与参数控制器共用同一份 format 缓存，避免反复遍历 `device.formats`。
    let capabilityProbe = CameraCapabilityProbe()

    /// 输入被替换或恢复后在主线程通知取景页重取图层连接。
    var onVideoInputReplaced: ((CameraDevicePosition) -> Void)?

    /// 当前视频设备的能力快照。**只在 [sessionQueue] 上调用**。
    func currentAbilitySnapshot() -> CameraAbilitySnapshot {
        return capabilityProbe.snapshot(for: videoInput?.device)
    }

    /// 当前参数落点（分辨率/帧率/ISO/曝光/对焦/白平衡/变焦/手电筒）。**只在 [sessionQueue] 上调用**。
    func currentParameterState() -> CameraParameterState {
        let device = videoInput?.device
        let ability = capabilityProbe.snapshot(for: device)
        // output 连接优先（录制连接在前）；没有 output 时退回预览连接。
        let connections = videoConnections()
        var state = capabilityProbe.parameterState(
            for: device,
            ability: ability,
            videoConnection: connections.first
        )
        if connections.count > 1 {
            let modes = Set(connections.map { $0.activeVideoStabilizationMode.rawValue })
            if modes.count > 1 {
                state.notes.append("预览连接与输出连接的防抖状态不一致，面板显示输出连接的实际状态。")
            }
        }
        return state
    }

    /// 当前设备朝向。**只在 [sessionQueue] 上调用**。
    func currentPosition() -> CameraDevicePosition {
        guard let device = videoInput?.device else { return .unspecified }
        return CameraDevicePosition(devicePosition: device.position)
    }

    /// 当前 format 表里的真实 `AVCaptureDevice.Format`（协商下标回查）。**只在 [sessionQueue] 上调用**。
    func currentFormat(at index: Int) -> AVCaptureDevice.Format? {
        guard let device = videoInput?.device else { return nil }
        return capabilityProbe.format(at: index, of: device)
    }

    /// 前后置切换：发现设备 → 同一配置事务内先移除旧输入 → `canAddInput` 新输入 → 原子替换。
    ///
    /// 新输入加不进去时在**同一事务内恢复旧输入**；连旧输入也恢复不了则把 `isConfigured` 复位，
    /// 回执 `requiresSessionRebuild == true`，由 `retry()` 重建输入。绝不留下一个"切一半"的会话。
    /// 本方法不碰任何连接的 `videoOrientation` / 镜像 / 防抖，录制方向由 B2 的 hook 维护。
    func switchCamera(
        to position: CameraDevicePosition,
        completion: ((CameraSwitchOutcome) -> Void)? = nil
    ) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let outcome = self.switchCameraOnQueue(to: position)
            if let completion = completion {
                DispatchQueue.main.async { completion(outcome) }
            }
        }
    }

    /// 应用「分辨率 + 帧率」：协商候选 format，在 `beginConfiguration/commitConfiguration` 内落档。
    ///
    /// 帧周期（`activeVideoMin/MaxFrameDuration`）只在目标帧率真被该 format 承载时才一起设置；
    /// 首选 format 落地失败会依次换候选，全部失败则**回滚到切换前的格式**并返回原因。
    func applyVideoConfiguration(
        _ request: CameraVideoConfigurationRequest,
        completion: ((CameraVideoConfigurationOutcome) -> Void)? = nil
    ) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let outcome = self.applyVideoConfigurationOnQueue(request)
            if let completion = completion {
                DispatchQueue.main.async { completion(outcome) }
            }
        }
    }

    /// 合并设置分辨率与帧率：**读最新状态与落档在同一个 [sessionQueue] 块内完成**。
    ///
    /// 参数面板只改其中一项时，另一项必须用「执行时刻」的设备实际状态补全；
    /// 否则「读旧镜头 state → 切镜头 → 用旧分辨率协商」会让用户选 4K 却静默落到 1080p。
    /// `resolution` / `frameRate` 为 nil 表示保持当前值。
    func updateVideoConfiguration(
        resolution: CameraResolution?,
        frameRate: Double?,
        completion: ((CameraVideoConfigurationOutcome) -> Void)? = nil
    ) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let outcome = self.updateVideoConfigurationOnQueue(
                resolution: resolution,
                frameRate: frameRate
            )
            if let completion = completion {
                DispatchQueue.main.async { completion(outcome) }
            }
        }
    }

    /// 锁低速快门（1/24、1/25）：**不改分辨率**，把帧周期锁到快门分母。
    ///
    /// 两条路分别记账：帧周期锁定（`frameDurationApplied`）与 sensor 曝光时间
    /// （`setExposureModeCustom`，`sensorExposureApplied`）——「快门可用 ≠ 曝光时间可设」。
    func applyShutterLock(
        target: CameraShutterSpeed,
        completion: ((CameraSlowShutterOutcome) -> Void)? = nil
    ) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let outcome = self.applyShutterLockOnQueue(target: target)
            if let completion = completion {
                DispatchQueue.main.async { completion(outcome) }
            }
        }
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

    // MARK: - 切换 / 格式落档（只在 sessionQueue 上调用）

    private func switchCameraOnQueue(to position: CameraDevicePosition) -> CameraSwitchOutcome {
        guard let device = CameraSessionController.discoverCamera(position: position) else {
            return CameraSwitchOutcome(
                requestedPosition: position,
                appliedPosition: nil,
                appliedDeviceName: nil,
                didChangeInput: false,
                keptPreviousInput: true,
                errorMessage: "未找到该朝向的可用摄像头。"
            )
        }

        let appliedPosition = CameraDevicePosition(devicePosition: device.position)

        if let current = videoInput?.device, current.uniqueID == device.uniqueID {
            // 已是目标设备：不动会话，只把当前朝向回执给调用方（不算失败）。
            return CameraSwitchOutcome(
                requestedPosition: position,
                appliedPosition: appliedPosition,
                appliedDeviceName: device.localizedName,
                didChangeInput: false,
                keptPreviousInput: true,
                errorMessage: nil
            )
        }

        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: device)
        } catch {
            return CameraSwitchOutcome(
                requestedPosition: position,
                appliedPosition: nil,
                appliedDeviceName: nil,
                didChangeInput: false,
                keptPreviousInput: true,
                errorMessage: "构造摄像头输入失败：\(error.localizedDescription)"
            )
        }

        let previousInput = videoInput
        var replaced = false
        var restoredPrevious = false
        var recoveryRequired = false

        // 同一配置事务内「先移除旧输入 → 再 canAdd 新输入」：设备在已占用 input 时
        // canAddInput 可能直接为假，先判后移会误判为「无法切换」。
        // 这里只替换 input，不硬编码任何 connection.videoOrientation / 镜像 / 防抖；
        // 预览方向由预览宿主维护，录制方向的恢复属于 B2 的后续 hook（见 onDeviceChanged）。
        session.beginConfiguration()
        if let previousInput = previousInput {
            session.removeInput(previousInput)
        }
        if session.canAddInput(input) {
            session.addInput(input)
            videoInput = input
            replaced = true
        } else if let previousInput = previousInput, session.canAddInput(previousInput) {
            // 新输入加不进去：在同一个事务里恢复旧输入，绝不把会话留在“无输入”状态。
            session.addInput(previousInput)
            videoInput = previousInput
            restoredPrevious = true
        } else {
            // 旧输入也恢复不了（或本就没有旧输入）：明确进入可重建状态，
            // 下次 retry() 会走 rebuildConfiguration + configureAndStart 重新建立输入。
            videoInput = nil
            recoveryRequired = true
        }
        isConfigured = videoInput != nil
        session.commitConfiguration()

        guard replaced else {
            capabilityProbe.invalidateCache()
            if recoveryRequired {
                // 状态机也进入失败态：`retry()` 只有在 `.failed` 时才走 rebuildConfiguration，
                // 否则会话虽然“可重建”，UI 重试却不会真的重建输入。
                transition(.failed(.configurationFailed))
                return CameraSwitchOutcome(
                    requestedPosition: position,
                    appliedPosition: nil,
                    appliedDeviceName: nil,
                    didChangeInput: false,
                    keptPreviousInput: false,
                    errorMessage: "会话无法添加该摄像头输入，且没有可恢复的视频输入；相机会话已进入可重建状态，请重试。",
                    requiresSessionRebuild: true
                )
            }
            return CameraSwitchOutcome(
                requestedPosition: position,
                appliedPosition: nil,
                appliedDeviceName: nil,
                didChangeInput: false,
                keptPreviousInput: restoredPrevious,
                errorMessage: restoredPrevious
                    ? "会话无法添加该摄像头输入，已恢复原摄像头。"
                    : "会话无法添加该摄像头输入，已保留原摄像头。"
            )
        }

        capabilityProbe.invalidateCache()
        DispatchQueue.main.async { [weak self] in
            self?.onVideoInputReplaced?(appliedPosition)
        }
        return CameraSwitchOutcome(
            requestedPosition: position,
            appliedPosition: appliedPosition,
            appliedDeviceName: device.localizedName,
            didChangeInput: true,
            keptPreviousInput: false,
            errorMessage: nil
        )
    }

    private func updateVideoConfigurationOnQueue(
        resolution: CameraResolution?,
        frameRate: Double?
    ) -> CameraVideoConfigurationOutcome {
        // 只在当前队列块内读一次；读到的是此刻真实设备/format，不会被稍后的 switchCamera 穿插。
        let state = currentParameterState()
        let resolvedFrameRate = frameRate
            ?? state.frameRate
            ?? CameraFormatNegotiation.frameRateTiers[0]

        guard let resolvedResolution = resolution ?? state.resolution else {
            let fallbackResolution = CameraResolution(width: 1_920, height: 1_080)
            let selection = CameraFormatNegotiation.selectFormat(
                for: CameraVideoConfigurationRequest(
                    resolution: fallbackResolution,
                    frameRate: resolvedFrameRate
                ),
                in: .unavailable
            )
            return CameraVideoConfigurationOutcome(
                selection: selection,
                appliedResolution: nil,
                appliedFrameRateRange: nil,
                didApplyFrameDurations: false,
                rolledBack: false,
                errorMessage: "尚未建立相机输入，无法切换分辨率/帧率。"
            )
        }

        return applyVideoConfigurationOnQueue(CameraVideoConfigurationRequest(
            resolution: resolvedResolution,
            frameRate: resolvedFrameRate
        ))
    }

    private func applyVideoConfigurationOnQueue(
        _ request: CameraVideoConfigurationRequest
    ) -> CameraVideoConfigurationOutcome {
        guard let device = videoInput?.device else {
            let selection = CameraFormatNegotiation.selectFormat(for: request, in: .unavailable)
            return CameraVideoConfigurationOutcome(
                selection: selection,
                appliedResolution: nil,
                appliedFrameRateRange: nil,
                didApplyFrameDurations: false,
                rolledBack: false,
                errorMessage: "尚未建立相机输入，无法切换格式。"
            )
        }

        let ability = capabilityProbe.snapshot(for: device)
        let selection = CameraFormatNegotiation.selectFormat(for: request, in: ability)

        guard selection.isApplied else {
            return CameraVideoConfigurationOutcome(
                selection: selection,
                appliedResolution: nil,
                appliedFrameRateRange: nil,
                didApplyFrameDurations: false,
                rolledBack: false,
                errorMessage: selection.unsupportedReason ?? "设备不支持该分辨率/帧率组合。"
            )
        }

        let result = applyFrameDurationLockOnQueue(
            device: device,
            ability: ability,
            selection: selection,
            frameRate: request.frameRate
        )

        if result.applied {
            return CameraVideoConfigurationOutcome(
                selection: selection,
                appliedResolution: CameraCapabilityProbe.resolution(of: device.activeFormat),
                appliedFrameRateRange: result.appliedRange,
                didApplyFrameDurations: true,
                rolledBack: false,
                errorMessage: nil
            )
        }

        let message = result.message ?? "设备未接受任何候选格式的帧周期锁定。"
        var notes = selection.notes + result.notes
        notes.append(String(
            format: "已回滚到切换前的格式：%@。",
            CameraCapabilityProbe.resolution(of: device.activeFormat).displayName
        ))
        let reported = CameraFormatSelection(
            requested: selection.requested,
            selectedResolution: selection.selectedResolution,
            selectedFormatIndex: nil,
            appliedFrameRate: nil,
            appliedFrameRateRange: nil,
            frameRateExact: false,
            unsupportedReason: message,
            notes: notes
        )
        return CameraVideoConfigurationOutcome(
            selection: reported,
            appliedResolution: nil,
            appliedFrameRateRange: nil,
            didApplyFrameDurations: false,
            rolledBack: true,
            errorMessage: message
        )
    }

    private func applyShutterLockOnQueue(target: CameraShutterSpeed) -> CameraSlowShutterOutcome {
        let frameRate = CameraShutterGate.preferredFrameRate(for: target)

        guard let device = videoInput?.device else {
            return CameraSlowShutterOutcome(
                assessment: CameraShutterGate.evaluate(target: target, frameRate: frameRate, in: .unavailable),
                appliedResolution: nil,
                appliedFrameRate: nil,
                frameDurationApplied: false,
                sensorExposureApplied: false,
                observedExposureNanoseconds: nil,
                rolledBack: false,
                explanation: "尚未建立相机输入，无法锁定快门。"
            )
        }

        let ability = capabilityProbe.snapshot(for: device)
        let resolution = CameraCapabilityProbe.resolution(of: device.activeFormat)
        let selection = CameraFormatNegotiation.selectFormat(
            for: CameraVideoConfigurationRequest(resolution: resolution, frameRate: frameRate),
            in: ability
        )

        var message: String?
        var frameDurationApplied = false
        var attempted = false

        if selection.isApplied {
            attempted = true
            let result = applyFrameDurationLockOnQueue(
                device: device,
                ability: ability,
                selection: selection,
                frameRate: frameRate
            )
            frameDurationApplied = result.applied
            if !result.applied {
                message = result.message ?? "设备未接受帧周期锁定，已回滚到切换前的格式。"
            }
        } else {
            message = selection.unsupportedReason ?? "设备 format 表不承载该快门所需的帧率。"
        }

        // 第二条路：sensor 曝光时间单独判定与下发；它与帧周期各记一笔，互不掩盖。
        var sensorExposureApplied = false
        if frameDurationApplied {
            // 帧周期落档可能换了 format，曝光时间域要按落档后的活动 format 重新探测。
            let exposureAbility = capabilityProbe.snapshot(for: device)
            let exposureResult = applySensorExposureOnQueue(
                device: device,
                target: target,
                frameRate: frameRate,
                ability: exposureAbility
            )
            sensorExposureApplied = exposureResult.applied
            if let failure = exposureResult.failureMessage {
                message = failure
            }
        }

        let actualFrameRate = CameraCapabilityProbe.activeFrameRate(of: device) ?? frameRate
        let abilityAfter = capabilityProbe.snapshot(for: device)
        let assessment = CameraShutterGate.evaluate(
            target: target,
            frameRate: actualFrameRate,
            in: abilityAfter
        )
        let observed = sensorExposureApplied
            ? CameraCapabilityProbe.nanoseconds(device.exposureDuration)
            : 0

        return CameraSlowShutterOutcome(
            assessment: assessment,
            appliedResolution: CameraCapabilityProbe.resolution(of: device.activeFormat),
            appliedFrameRate: actualFrameRate,
            frameDurationApplied: frameDurationApplied,
            sensorExposureApplied: sensorExposureApplied,
            observedExposureNanoseconds: observed > 0 ? observed : nil,
            rolledBack: attempted && !frameDurationApplied,
            explanation: message
        )
    }

    /// 在 `beginConfiguration/commitConfiguration` 内逐个候选 format 尝试落档。
    ///
    /// 只有目标帧率真被该 format 的区间承载时才同时设置 min/max 帧周期；全部候选失败则
    /// 回滚到切换前的 format 与帧周期，绝不把会话留在半改状态。
    private func applyFrameDurationLockOnQueue(
        device: AVCaptureDevice,
        ability: CameraAbilitySnapshot,
        selection: CameraFormatSelection,
        frameRate: Double
    ) -> FrameDurationLockResult {
        let formats = device.formats
        guard let primary = selection.selectedFormatIndex, primary >= 0, primary < formats.count else {
            return FrameDurationLockResult(
                appliedRange: nil,
                applied: false,
                notes: [],
                message: "协商出的格式下标越界，未改动设备。"
            )
        }

        let candidates = CameraFormatNegotiation.formatCandidateIndices(
            in: ability,
            resolution: selection.selectedResolution,
            frameRate: frameRate,
            primary: primary
        )

        let requiredNanoseconds = CameraShutterGate.frameDurationNanoseconds(frameRate: frameRate)
        guard requiredNanoseconds > 0 else {
            return FrameDurationLockResult(
                appliedRange: nil,
                applied: false,
                notes: [],
                message: "目标帧率非法，未改动设备。"
            )
        }

        let previousFormat = device.activeFormat
        let previousMinimum = device.activeVideoMinFrameDuration
        let previousMaximum = device.activeVideoMaxFrameDuration

        var notes: [String] = []
        var appliedRange: CameraFrameRateRange?
        var applied = false
        var message: String?

        session.beginConfiguration()
        // sessionPreset 会覆盖 activeFormat，落档前必须让位给 inputPriority，否则改了也不生效。
        if session.canSetSessionPreset(.inputPriority) {
            session.sessionPreset = .inputPriority
        } else {
            notes.append("会话 preset 无法切到 inputPriority，活动 format 可能被 preset 覆盖。")
        }

        for index in candidates {
            guard index >= 0, index < formats.count else { continue }
            let candidate = formats[index]
            let descriptor = CameraCapabilityProbe.descriptor(for: candidate)
            guard descriptor.isUsable,
                  let range = CameraFormatNegotiation.chosenRange(in: descriptor, for: frameRate),
                  range.contains(frameRate) else {
                continue
            }

            do {
                try device.lockForConfiguration()
            } catch {
                message = "无法锁定摄像头设备：\(error.localizedDescription)"
                notes.append("候选格式 \(descriptor.resolution.displayName) 锁定失败，继续尝试下一个。")
                continue
            }

            device.activeFormat = candidate
            let duration = CMTimeMake(value: requiredNanoseconds, timescale: 1_000_000_000)
            device.activeVideoMinFrameDuration = duration
            device.activeVideoMaxFrameDuration = duration

            let observedMinimum = CMTimeGetSeconds(device.activeVideoMinFrameDuration)
            let observedMaximum = CMTimeGetSeconds(device.activeVideoMaxFrameDuration)
            let expected = Double(requiredNanoseconds) / 1_000_000_000.0
            let tolerance = Swift.max(1e-6, expected * 0.01)
            let accepted = observedMinimum.isFinite && observedMaximum.isFinite
                && Swift.abs(observedMinimum - expected) <= tolerance
                && Swift.abs(observedMaximum - expected) <= tolerance

            device.unlockForConfiguration()

            if accepted {
                applied = true
                appliedRange = range
                if descriptor.resolution != selection.selectedResolution {
                    notes.append(String(
                        format: "首选格式未接受帧周期锁定，改用 %@。",
                        descriptor.resolution.displayName
                    ))
                }
                break
            }
            notes.append(String(
                format: "候选格式 %@ 未接受 %.0ffps 的帧周期锁定，继续尝试。",
                descriptor.resolution.displayName,
                frameRate
            ))
        }

        session.commitConfiguration()

        if !applied {
            // 回滚：恢复切换前的 format 与帧周期，失败也如实记账。
            do {
                try device.lockForConfiguration()
                device.activeFormat = previousFormat
                device.activeVideoMinFrameDuration = previousMinimum
                device.activeVideoMaxFrameDuration = previousMaximum
                device.unlockForConfiguration()
            } catch {
                notes.append("回滚失败：\(error.localizedDescription)")
            }
        }

        return FrameDurationLockResult(
            appliedRange: appliedRange,
            applied: applied,
            notes: notes,
            message: message
        )
    }

    /// sensor 曝光时间（慢门的第二条路）。
    ///
    /// 设备没有手动曝光能力时**不算失败**：返回 `(false, nil)`，由
    /// `CameraSlowShutterStatus.frameLockedExposureUnavailable` 表达这一档降级。
    private func applySensorExposureOnQueue(
        device: AVCaptureDevice,
        target: CameraShutterSpeed,
        frameRate: Double,
        ability: CameraAbilitySnapshot
    ) -> (applied: Bool, failureMessage: String?) {
        guard device.isExposureModeSupported(.custom), ability.hasManualExposureDuration else {
            return (false, nil)
        }

        let requested = CameraShutterGate.clampExposureNanoseconds(target.nanoseconds, frameRate: frameRate)
        // 能力域是纳秒口径；先按设备可设置域夹取，绝不把 1/24（41_666_666 ns）写成 0/1 ns。
        let inDeviceRange = ability.exposureDurationRange.clamp(Double(requested))
        let durationNanoseconds = Swift.max(1, Int64(inDeviceRange.rounded()))
        let duration = CMTimeMake(value: durationNanoseconds, timescale: 1_000_000_000)

        do {
            try device.lockForConfiguration()
        } catch {
            return (false, "锁定摄像头以设置曝光时间失败：\(error.localizedDescription)")
        }
        device.setExposureModeCustom(duration: duration, iso: AVCaptureDevice.currentISO, completionHandler: nil)
        device.unlockForConfiguration()
        return (true, nil)
    }

    /// 枚举的是切镜入口本身能打开的设备，UI 不假设机器有前后双摄。
    func availableCameraPositions() -> [CameraDevicePosition] {
        return [CameraDevicePosition.back, .front].filter { Self.discoverCamera(position: $0) != nil }
    }

    /// 指定朝向的设备发现：优先 `DiscoverySession` 的广角头，再退到系统默认视频设备。
    private static func discoverCamera(position: CameraDevicePosition) -> AVCaptureDevice? {
        if position == .unspecified {
            return pickCamera()
        }
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera],
            mediaType: .video,
            position: position.devicePosition
        )
        if let device = discovery.devices.first {
            return device
        }
        return AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: position.devicePosition
        )
    }
}

/// 帧周期落档的中间结果（仅本文件使用）。
private struct FrameDurationLockResult {
    let appliedRange: CameraFrameRateRange?
    let applied: Bool
    let notes: [String]
    let message: String?
}
