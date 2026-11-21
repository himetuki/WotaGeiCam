import AVFoundation
import CoreGraphics
import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 相机参数快照：平台层把 `AVCaptureDevice` 的能力与当前落点翻译成 UI 可展示的纯数据。
///
/// UI 不直接持有 AVFoundation 类型。`ability` / `state` 是 B1 之后新增的完整能力与状态：
/// 能力是「设备能做哪些档」，状态是「现在落在哪一档」；相机无输出时两者都是 `.unavailable`
/// 形态，接口仍有确定返回值，UI 据此显示禁用而不是空白。
struct CameraParameterSnapshot {
    var deviceName: String
    var deviceModel: String
    var resolution: String
    var frameRate: String
    var exposure: ExposureBiasRange
    var exposureValue: Float
    var hasTorch: Bool
    var torchAvailable: Bool
    var torchEnabled: Bool
    /// 降级/不支持说明；UI 直接展示，不静默忽略。
    var notes: [String]
    /// 设备能力：分辨率/帧率档位、ISO/曝光/对焦/白平衡/变焦域、防抖与手电筒支持。
    var ability: CameraAbilitySnapshot
    /// 当前参数落点（值 + 能力域 + 降级说明）。
    var state: CameraParameterState
    /// 最近一次操作的记账文案（切换 / 慢门 / 写参数），供面板与后续录制读取。
    var lastActionSummary: String?
    var cameraPositions: [CameraDevicePosition] = []
    var isManualExposure = false
    var supportsAutoExposure = false
    var whiteBalanceGains: (red: Double, green: Double, blue: Double)? = nil

    static let unavailable = CameraParameterSnapshot(
        deviceName: "—",
        deviceModel: "—",
        resolution: "—",
        frameRate: "—",
        exposure: ExposureBiasRange(minimum: 0, maximum: 0),
        exposureValue: 0,
        hasTorch: false,
        torchAvailable: false,
        torchEnabled: false,
        notes: ["尚未建立相机输入，参数在预览启动后可用。"],
        ability: .unavailable,
        state: .unavailable,
        lastActionSummary: nil
    )
}

/// 单参数写入回执：参数名 + 设备是否支持 + 是否真的生效 + 降级/失败原因。
///
/// 「不支持」与「下发失败」分开：前者是能力缺失（UI 该禁用），后者是执行出错（UI 该重试/提示），
/// 两者都不允许静默。
struct CameraParameterAdjustOutcome {
    let parameter: String
    let supported: Bool
    let applied: Bool
    /// 生效值的可读描述（读回设备后的值，读不到为 nil）。
    let appliedDescription: String?
    let message: String?

    var isSuccess: Bool {
        return supported && applied && message == nil
    }

    var summary: String {
        if let message = message {
            return "\(parameter)：\(message)"
        }
        if let appliedDescription = appliedDescription {
            return "\(parameter)：\(appliedDescription)"
        }
        return "\(parameter)：已生效"
    }

    static func unsupported(_ parameter: String, _ message: String) -> CameraParameterAdjustOutcome {
        return CameraParameterAdjustOutcome(
            parameter: parameter,
            supported: false,
            applied: false,
            appliedDescription: nil,
            message: message
        )
    }

    static func applied(_ parameter: String, description: String?) -> CameraParameterAdjustOutcome {
        return CameraParameterAdjustOutcome(
            parameter: parameter,
            supported: true,
            applied: true,
            appliedDescription: description,
            message: nil
        )
    }

    static func failed(_ parameter: String, _ message: String) -> CameraParameterAdjustOutcome {
        return CameraParameterAdjustOutcome(
            parameter: parameter,
            supported: true,
            applied: false,
            appliedDescription: nil,
            message: message
        )
    }
}

/// 基础 WOTA 录制参数控制器（分辨率 / 帧率 / 曝光补偿 / ISO / 快门(慢门) / 对焦 / 白平衡 / 变焦 / 手电筒）。
///
/// 设计约束：
/// - **不假设 iOS 与 Android Camera2 语义等价**：每一项都先判断设备是否支持（能力来自
///   `CameraCapabilityProbe` 对 `AVCaptureDevice`/`Format` 的运行时读取），不支持时给明确降级说明，
///   而不是静默失败或伪造数值；
/// - 所有设备操作都在 [CameraSessionController.sessionQueue] 上执行，与启停/配置/切换互斥；
/// - 分辨率/帧率/慢门的真正落档在 [CameraSessionController] 的 `applyVideoConfiguration` /
///   `applyShutterLock` 里完成，本控制器只做能力判定、参数夹取与回执；
/// - **没有相机输出时仍返回能力快照**（`.unavailable` 形态 + 原因），参数接口不会返回 nil。
final class CameraParameterController {

    /// 快照回调（主线程）。
    var onSnapshot: ((CameraParameterSnapshot) -> Void)?

    /// 最近一次发布的快照，供面板与后续录制随时读取。
    private(set) var latest: CameraParameterSnapshot = .unavailable

    private let queue: DispatchQueue
    private let deviceProvider: () -> AVCaptureDevice?
    private let sessionController: CameraSessionController

    init(sessionController: CameraSessionController) {
        self.queue = sessionController.sessionQueue
        self.deviceProvider = { sessionController.currentVideoDevice() }
        self.sessionController = sessionController
    }

    // MARK: - 刷新

    /// 刷新当前参数快照；结果在主线程回调。
    func refresh() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.publish(self.snapshotOnQueue(extraNotes: [], lastAction: nil))
        }
    }

    // MARK: - 摄像头切换

    /// 前后置切换；失败保留原摄像头。
    func switchCamera(
        to position: CameraDevicePosition,
        completion: ((CameraSwitchOutcome) -> Void)? = nil
    ) {
        sessionController.switchCamera(to: position) { [weak self] outcome in
            guard let self = self else { return }
            self.queue.async {
                let notes = outcome.errorMessage.map { [$0] } ?? []
                self.publish(self.snapshotOnQueue(extraNotes: notes, lastAction: outcome.summary)) {
                    completion?(outcome)
                }
            }
        }
    }

    // MARK: - 分辨率 / 帧率

    /// 同时切换分辨率与帧率（分辨率/帧率档位来自能力协商，不是硬编码机型表）。
    func setResolutionAndFrameRate(
        resolution: CameraResolution,
        frameRate: Double,
        completion: ((CameraVideoConfigurationOutcome) -> Void)? = nil
    ) {
        applyConfiguration(
            CameraVideoConfigurationRequest(resolution: resolution, frameRate: frameRate),
            completion: completion
        )
    }

    /// 只切分辨率，帧率保持当前档（读不到当前帧率时退回产品默认首档 24fps）。
    ///
    /// 「读当前帧率 + 落档」由会话层在同一个 `sessionQueue` 块内完成，
    /// 避免读旧镜头状态后中间插入 `switchCamera` 导致用旧分辨率协商新镜头（P2-01）。
    func setResolution(
        _ resolution: CameraResolution,
        completion: ((CameraVideoConfigurationOutcome) -> Void)? = nil
    ) {
        applyMergedConfiguration(
            resolution: resolution,
            frameRate: nil,
            completion: completion
        )
    }

    /// 只切帧率，分辨率保持当前档（读最新设备实际状态在会话队列内完成）。
    func setFrameRate(
        _ frameRate: Double,
        completion: ((CameraVideoConfigurationOutcome) -> Void)? = nil
    ) {
        applyMergedConfiguration(
            resolution: nil,
            frameRate: frameRate,
            completion: completion
        )
    }

    // MARK: - 低速快门（1/24、1/25）

    /// 锁低速快门：帧周期与 sensor 曝光时间两条路分别记账（见 `CameraSlowShutterOutcome`）。
    func setLowSpeedShutter(
        _ target: CameraShutterSpeed,
        completion: ((CameraSlowShutterOutcome) -> Void)? = nil
    ) {
        sessionController.applyShutterLock(target: target) { [weak self] outcome in
            guard let self = self else { return }
            self.queue.async {
                var notes = outcome.assessment.notes
                if let explanation = outcome.explanation {
                    notes.append(explanation)
                }
                self.publish(self.snapshotOnQueue(extraNotes: notes, lastAction: outcome.summary)) {
                    completion?(outcome)
                }
            }
        }
    }

    // MARK: - 曝光补偿

    /// iOS 的 EV 是绝对偏置。手动曝光时保持偏置原值。
    func setExposureBias(_ value: Float, completion: ((CameraParameterAdjustOutcome) -> Void)? = nil) {
        adjust("曝光补偿", completion: completion) { device in
            let range = ExposureBiasRange(minimum: device.minExposureTargetBias,
                                          maximum: device.maxExposureTargetBias)
            guard range.isAdjustable, device.exposureMode != .custom, value.isFinite else {
                return .unsupported("曝光补偿", "该设备不支持曝光补偿，或当前处于手动曝光。")
            }
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            let applied = range.clamp(value)
            device.setExposureTargetBias(applied, completionHandler: nil)
            let text = String(format: "%+.1f EV", applied)
            return .applied("曝光补偿", description: applied == value ? text : "设备范围内调整为 \(text)")
        }
    }

    // MARK: - 手动 ISO / 曝光时间

    /// 手动 ISO（`iso == nil` 表示保持当前值）。
    func setISO(_ iso: Double, completion: ((CameraParameterAdjustOutcome) -> Void)? = nil) {
        setManualExposure(iso: iso, exposureSeconds: nil, completion: completion)
    }

    /// 手动曝光时间（秒）。
    func setExposureDuration(
        seconds: Double,
        completion: ((CameraParameterAdjustOutcome) -> Void)? = nil
    ) {
        setManualExposure(iso: nil, exposureSeconds: seconds, completion: completion)
    }

    /// 手动曝光：走 `setExposureModeCustom`；两个参数都可为 nil（nil = `AVCaptureDevice.currentISO` /
    /// `AVCaptureDevice.currentExposureDuration`（static 类属性；旧全局常量名已废弃）。
    ///
    /// 「快门档可用（帧周期能锁）」与「sensor 曝光时间可设」是两件事，这里只处理后者。
    func setManualExposure(
        iso: Double?,
        exposureSeconds: Double?,
        completion: ((CameraParameterAdjustOutcome) -> Void)? = nil
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let outcome: CameraParameterAdjustOutcome

            if let device = self.deviceProvider() {
                let ability = self.sessionController.currentAbilitySnapshot()
                let state = self.sessionController.currentParameterState()

                if !device.isExposureModeSupported(.custom) {
                    outcome = .unsupported("手动曝光", "该设备不支持手动曝光模式，ISO/曝光时间已禁用。")
                } else if iso == nil && exposureSeconds == nil {
                    outcome = .unsupported("手动曝光", "未给出 ISO 或曝光时间，未改动设备。")
                } else if iso != nil && !ability.hasManualISO {
                    outcome = .unsupported("ISO", "该设备未上报可用 ISO 域，ISO 不可调。")
                } else if exposureSeconds != nil && !ability.hasManualExposureDuration {
                    outcome = .unsupported("曝光时间", "该设备未上报可用曝光时间域，曝光时间不可调。")
                } else {
                    // `AVCaptureISOCurrent`/`AVCaptureExposureDurationCurrent` 已改名：
                    // 新 API 是 AVCaptureDevice 的 static 类属性 currentISO/currentExposureDuration。
                    var isoValue = AVCaptureDevice.currentISO
                    if let iso = iso {
                        isoValue = Float(ability.isoRange.clamp(iso))
                    }
                    var duration = AVCaptureDevice.currentExposureDuration
                    var durationText = "曝光时间保持"
                    if let seconds = exposureSeconds {
                        // 先按「不超过帧周期、不超过 1/10s」夹，再按设备曝光域夹（两者都是纳秒口径）。
                        let frameRate = state.frameRate ?? CameraTiersDefaultFrameRate
                        let requestedNanoseconds = Int64((seconds * 1_000_000_000.0).rounded())
                        let cappedNanoseconds = CameraShutterGate.clampExposureNanoseconds(
                            requestedNanoseconds,
                            frameRate: frameRate
                        )
                        let inRange = ability.exposureDurationRange.clamp(Double(cappedNanoseconds))
                        let appliedNanoseconds = Swift.max(1, Int64(inRange.rounded()))
                        duration = CMTimeMake(value: appliedNanoseconds, timescale: 1_000_000_000)

                        let requestedText = CameraParameterController.exposureDescription(seconds: seconds)
                        if appliedNanoseconds != requestedNanoseconds {
                            let appliedText = CameraParameterController.exposureDescription(
                                seconds: Double(appliedNanoseconds) / 1_000_000_000.0
                            )
                            durationText = "曝光 \(requestedText)（设备/帧周期夹到 \(appliedText)）"
                        } else {
                            durationText = "曝光 \(requestedText)"
                        }
                    }

                    do {
                        try device.lockForConfiguration()
                        device.setExposureModeCustom(duration: duration, iso: isoValue, completionHandler: nil)
                        device.unlockForConfiguration()
                        let isoText = iso.map { String(format: "ISO %.0f", ability.isoRange.clamp($0)) } ?? "ISO 保持"
                        outcome = .applied("手动曝光", description: "\(isoText)，\(durationText)")
                    } catch {
                        outcome = .failed("手动曝光", "无法锁定相机以设置手动曝光：\(error.localizedDescription)")
                    }
                }
            } else {
                outcome = .unsupported("手动曝光", "尚未建立相机输入，无法设置手动曝光。")
            }

            self.publish(self.snapshotOnQueue(
                extraNotes: outcome.message.map { [$0] } ?? [],
                lastAction: outcome.summary
            )) { completion?(outcome) }
        }
    }

    // MARK: - 自动曝光（手动曝光的退出路径）

    /// 恢复连续自动曝光。G1 手动曝光面板的“自动”出口走这里，避免只能进不能退。
    ///
    /// 只在设备支持 `.continuousAutoExposure` 时下发；`setExposureModeCustom` 设置的
    /// 固定 ISO / 曝光时间会被系统重新接管，不需要再单独复位。
    func setAutoExposure(
        completion: ((CameraParameterAdjustOutcome) -> Void)? = nil
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let outcome: CameraParameterAdjustOutcome

            if let device = self.deviceProvider() {
                if !device.isExposureModeSupported(.continuousAutoExposure) {
                    outcome = .unsupported("自动曝光", "该设备不支持连续自动曝光模式。")
                } else {
                    do {
                        try device.lockForConfiguration()
                        device.exposureMode = .continuousAutoExposure
                        device.unlockForConfiguration()
                        outcome = .applied("自动曝光", description: "连续自动曝光")
                    } catch {
                        outcome = .failed("自动曝光", "无法锁定相机以恢复自动曝光：\(error.localizedDescription)")
                    }
                }
            } else {
                outcome = .unsupported("自动曝光", "尚未建立相机输入，无法恢复自动曝光。")
            }

            self.publish(self.snapshotOnQueue(
                extraNotes: outcome.message.map { [$0] } ?? [],
                lastAction: outcome.summary
            )) { completion?(outcome) }
        }
    }

    // MARK: - 对焦

    /// 对焦模式 / 手动焦距（`lensPosition` 取值域固定 0…1）。
    func setFocus(
        mode: CameraFocusMode,
        lensPosition: Double? = nil,
        completion: ((CameraParameterAdjustOutcome) -> Void)? = nil
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let outcome: CameraParameterAdjustOutcome

            if let device = self.deviceProvider() {
                if !device.isFocusModeSupported(mode.deviceMode) {
                    outcome = .unsupported("对焦", "该设备不支持「\(mode.displayName)」对焦模式。")
                } else {
                    do {
                        try device.lockForConfiguration()
                        if mode == .locked, let lensPosition = lensPosition {
                            device.setFocusModeLocked(lensPosition: Float(Swift.min(Swift.max(lensPosition, 0), 1)), completionHandler: nil)
                        } else {
                            device.focusMode = mode.deviceMode
                        }
                        device.unlockForConfiguration()
                        let positionText = lensPosition.map { String(format: "，焦距 %.2f", Swift.min(Swift.max($0, 0), 1)) }
                        outcome = .applied("对焦", description: "\(mode.displayName)\(positionText ?? "")")
                    } catch {
                        outcome = .failed("对焦", "无法锁定相机以设置对焦：\(error.localizedDescription)")
                    }
                }
            } else {
                outcome = .unsupported("对焦", "尚未建立相机输入，无法设置对焦。")
            }

            self.publish(self.snapshotOnQueue(
                extraNotes: outcome.message.map { [$0] } ?? [],
                lastAction: outcome.summary
            )) { completion?(outcome) }
        }
    }

    // MARK: - 白平衡

    /// 白平衡模式 / 色温·色调（色温域 2000–10000K、色调 ±50 是产品滑杆域，不是设备能力；
    /// 最终增益还要按 `maxWhiteBalanceGain` 夹取）。
    func setWhiteBalance(
        mode: CameraWhiteBalanceMode?,
        kelvin: Int? = nil,
        tint: Double? = nil,
        completion: ((CameraParameterAdjustOutcome) -> Void)? = nil
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let outcome: CameraParameterAdjustOutcome

            if let device = self.deviceProvider() {
                let ability = self.sessionController.currentAbilitySnapshot()

                if let kelvin = kelvin {
                    if !device.isWhiteBalanceModeSupported(.locked) {
                        outcome = .unsupported("白平衡", "该设备不支持锁定白平衡，无法按色温设置。")
                    } else if !ability.hasManualWhiteBalance {
                        outcome = .unsupported("白平衡", "该设备未上报可用白平衡增益域，色温/色调不可调。")
                    } else {
                        let clampedKelvin = RecordingTiers.kelvinRange.clamp(Double(kelvin))
                        let clampedTint = RecordingTiers.tintRange.clamp(tint ?? 0)
                        let values = AVCaptureDevice.WhiteBalanceTemperatureAndTintValues(
                            temperature: Float(clampedKelvin),
                            tint: Float(clampedTint)
                        )
                        let maximumGain = device.maxWhiteBalanceGain
                        let raw = device.deviceWhiteBalanceGains(for: values)
                        let gains = AVCaptureDevice.WhiteBalanceGains(
                            redGain: Swift.min(Swift.max(raw.redGain, 1), maximumGain),
                            greenGain: Swift.min(Swift.max(raw.greenGain, 1), maximumGain),
                            blueGain: Swift.min(Swift.max(raw.blueGain, 1), maximumGain)
                        )
                        do {
                            try device.lockForConfiguration()
                            device.setWhiteBalanceModeLocked(with: gains, completionHandler: nil)
                            device.unlockForConfiguration()
                            outcome = .applied("白平衡", description: String(
                                format: "锁定 %.0fK / 色调 %+.0f",
                                clampedKelvin,
                                clampedTint
                            ))
                        } catch {
                            outcome = .failed("白平衡", "无法锁定相机以设置白平衡：\(error.localizedDescription)")
                        }
                    }
                } else if let mode = mode {
                    if device.isWhiteBalanceModeSupported(mode.deviceMode) {
                        do {
                            try device.lockForConfiguration()
                            device.whiteBalanceMode = mode.deviceMode
                            device.unlockForConfiguration()
                            outcome = .applied("白平衡", description: mode.displayName)
                        } catch {
                            outcome = .failed("白平衡", "无法锁定相机以设置白平衡：\(error.localizedDescription)")
                        }
                    } else {
                        outcome = .unsupported("白平衡", "该设备不支持「\(mode.displayName)」白平衡模式。")
                    }
                } else {
                    outcome = .unsupported("白平衡", "未给出白平衡模式或色温，未改动设备。")
                }
            } else {
                outcome = .unsupported("白平衡", "尚未建立相机输入，无法设置白平衡。")
            }

            self.publish(self.snapshotOnQueue(
                extraNotes: outcome.message.map { [$0] } ?? [],
                lastAction: outcome.summary
            )) { completion?(outcome) }
        }
    }

    /// 手动 RGB 增益（每通道钳到 `1...device.maxWhiteBalanceGain`，与现有白平衡路径同一限制）。
    ///
    /// 只在支持 locked 白平衡且上报增益域的设备上可写；无上限/无锁定能力时明确拒绝，
    /// 不把超域值直接塞给 `setWhiteBalanceModeLocked`（会抛异常或产生异常偏色）。
    func setManualWhiteBalanceGains(
        red: Double,
        green: Double,
        blue: Double,
        completion: ((CameraParameterAdjustOutcome) -> Void)? = nil
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let outcome: CameraParameterAdjustOutcome

            if let device = self.deviceProvider() {
                let ability = self.sessionController.currentAbilitySnapshot()
                if !device.isWhiteBalanceModeSupported(.locked) {
                    outcome = .unsupported("白平衡增益", "该设备不支持锁定白平衡，无法设置手动 RGB 增益。")
                } else if !ability.hasManualWhiteBalance {
                    outcome = .unsupported("白平衡增益", "该设备未上报可用白平衡增益域，手动 RGB 增益不可调。")
                } else {
                    let maximum = Swift.max(1.0, Double(device.maxWhiteBalanceGain))
                    func clampGain(_ value: Double) -> Float {
                        guard value.isFinite else { return 1 }
                        return Float(Swift.min(Swift.max(value, 1), maximum))
                    }
                    let gains = AVCaptureDevice.WhiteBalanceGains(
                        redGain: clampGain(red),
                        greenGain: clampGain(green),
                        blueGain: clampGain(blue)
                    )
                    do {
                        try device.lockForConfiguration()
                        device.setWhiteBalanceModeLocked(with: gains, completionHandler: nil)
                        let applied = device.deviceWhiteBalanceGains
                        device.unlockForConfiguration()
                        outcome = .applied("白平衡增益", description: String(
                            format: "R %.2f / G %.2f / B %.2f（上限 %.2f）",
                            Double(applied.redGain),
                            Double(applied.greenGain),
                            Double(applied.blueGain),
                            maximum
                        ))
                    } catch {
                        outcome = .failed("白平衡增益", "无法锁定相机以设置手动 RGB 增益：\(error.localizedDescription)")
                    }
                }
            } else {
                outcome = .unsupported("白平衡增益", "尚未建立相机输入，无法设置手动 RGB 增益。")
            }

            self.publish(self.snapshotOnQueue(
                extraNotes: outcome.message.map { [$0] } ?? [],
                lastAction: outcome.summary
            )) { completion?(outcome) }
        }
    }

    // MARK: - 变焦

    /// 变焦倍率。iOS 10 走 `videoZoomFactor` + `activeFormat.videoMaxZoomFactor`，
    /// 不使用 `videoZoomFactorForDepthDataDelivery`（深度数据专用）。
    func setZoom(
        factor: Double,
        completion: ((CameraParameterAdjustOutcome) -> Void)? = nil
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let outcome: CameraParameterAdjustOutcome

            if let device = self.deviceProvider() {
                let ability = self.sessionController.currentAbilitySnapshot()
                if !ability.hasZoom {
                    outcome = .unsupported("变焦", "该设备没有可用变焦域，变焦已禁用。")
                } else {
                    let clamped = ability.zoomRange.clamp(factor)
                    do {
                        try device.lockForConfiguration()
                        device.videoZoomFactor = CGFloat(clamped)
                        device.unlockForConfiguration()
                        outcome = .applied("变焦", description: String(
                            format: "%.2f×（%.2f–%.2f×）",
                            clamped,
                            ability.zoomRange.minimum,
                            ability.zoomRange.maximum
                        ))
                    } catch {
                        outcome = .failed("变焦", "无法锁定相机以设置变焦：\(error.localizedDescription)")
                    }
                }
            } else {
                outcome = .unsupported("变焦", "尚未建立相机输入，无法设置变焦。")
            }

            self.publish(self.snapshotOnQueue(
                extraNotes: outcome.message.map { [$0] } ?? [],
                lastAction: outcome.summary
            )) { completion?(outcome) }
        }
    }

    // MARK: - 防抖

    /// 取景连接能启用时同步预览意图。成片连接在开录时另行协商。
    func setStabilization(mode: CameraStabilizationMode) {
        queue.async { [weak self] in
            guard let self = self, let device = self.deviceProvider() else { return }
            let supported = mode == .off || device.activeFormat.isVideoStabilizationModeSupported(mode.deviceMode)
            let effective: CameraStabilizationMode = supported ? mode : .off
            for connection in self.sessionController.videoConnections() where connection.isVideoStabilizationSupported {
                connection.preferredVideoStabilizationMode = effective.deviceMode
            }
            self.publish(self.snapshotOnQueue(extraNotes: [], lastAction: nil))
        }
    }

    // MARK: - 手电筒

    func setTorch(enabled: Bool, completion: ((CameraParameterAdjustOutcome) -> Void)? = nil) {
        adjust("手电筒", completion: completion) { device in
            let mode: AVCaptureDevice.TorchMode = enabled ? .on : .off
            guard device.hasTorch, device.isTorchModeSupported(mode) else {
                return .unsupported("手电筒", "该设备不支持所选模式。")
            }
            guard !enabled || device.isTorchAvailable else {
                return .unsupported("手电筒", "当前不可用，可能被占用或温度过高。")
            }
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            device.torchMode = mode
            guard device.torchMode == mode else { return .failed("手电筒", "设备未接受所选模式。") }
            return .applied("手电筒", description: enabled ? "已开启" : "已关闭")
        }
    }

    private func adjust(
        _ parameter: String,
        completion: ((CameraParameterAdjustOutcome) -> Void)?,
        apply: @escaping (AVCaptureDevice) throws -> CameraParameterAdjustOutcome
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let outcome: CameraParameterAdjustOutcome
            if let device = self.deviceProvider() {
                do { outcome = try apply(device) }
                catch { outcome = .failed(parameter, "相机配置失败：\(error.localizedDescription)") }
            } else {
                outcome = .unsupported(parameter, "相机尚未就绪。")
            }
            self.publish(self.snapshotOnQueue(extraNotes: outcome.message.map { [$0] } ?? [],
                                               lastAction: outcome.summary)) { completion?(outcome) }
        }
    }

    // MARK: - 只在 sessionQueue 上调用

    private func applyConfiguration(
        _ request: CameraVideoConfigurationRequest,
        completion: ((CameraVideoConfigurationOutcome) -> Void)?
    ) {
        sessionController.applyVideoConfiguration(request) { [weak self] outcome in
            self?.handleConfigurationOutcome(outcome, completion: completion)
        }
    }

    /// 只给出分辨率或帧率，另一半由会话层在执行时刻用最新设备状态补齐。
    private func applyMergedConfiguration(
        resolution: CameraResolution?,
        frameRate: Double?,
        completion: ((CameraVideoConfigurationOutcome) -> Void)?
    ) {
        sessionController.updateVideoConfiguration(
            resolution: resolution,
            frameRate: frameRate
        ) { [weak self] outcome in
            self?.handleConfigurationOutcome(outcome, completion: completion)
        }
    }

    private func handleConfigurationOutcome(
        _ outcome: CameraVideoConfigurationOutcome,
        completion: ((CameraVideoConfigurationOutcome) -> Void)?
    ) {
        queue.async { [weak self] in
            guard let self = self else { return }
            var notes = outcome.selection.notes
            if let error = outcome.errorMessage {
                notes.append(error)
            }
            let snapshot = self.snapshotOnQueue(extraNotes: notes, lastAction: self.formatSummary(outcome))
            self.publish(snapshot) { completion?(outcome) }
        }
    }

    private func formatSummary(_ outcome: CameraVideoConfigurationOutcome) -> String {
        if outcome.isSuccess {
            let resolution = outcome.appliedResolution?.displayName
                ?? outcome.selection.selectedResolution?.displayName
                ?? "—"
            let exact = outcome.selection.frameRateExact ? "固定" : "近似"
            return String(
                format: "格式：%@ @%.0fp（%@）",
                resolution,
                outcome.selection.requested.frameRate,
                exact
            )
        }
        if let error = outcome.errorMessage {
            return "格式：\(error)"
        }
        return "格式：未变更"
    }

    private func snapshotOnQueue(extraNotes: [String], lastAction: String?) -> CameraParameterSnapshot {
        guard let device = deviceProvider() else {
            var snapshot = CameraParameterSnapshot.unavailable
            snapshot.notes = extraNotes + snapshot.notes
            snapshot.lastActionSummary = lastAction
            return snapshot
        }

        let ability = sessionController.currentAbilitySnapshot()
        let state = sessionController.currentParameterState()

        var notes = extraNotes

        let resolutionText = state.resolution?.displayName ?? "—"
        let frameRateText: String
        if let frameRate = state.frameRate {
            frameRateText = CameraFormatNegotiation.frameRateDescription(frameRate, exact: state.frameRateExact)
        } else {
            frameRateText = "未知"
        }

        let exposure = ExposureBiasRange(
            minimum: device.minExposureTargetBias,
            maximum: device.maxExposureTargetBias
        )
        if !exposure.isAdjustable {
            notes.append("该设备不支持曝光补偿，滑杆已禁用。")
        }
        if state.frameRate != nil && !state.frameRateExact {
            notes.append(String(
                format: "当前帧率是可变区间（区间内最高 %.0f fps），不保证稳定固定档。",
                ability.maximumFrameRate
            ))
        }

        // 慢门可用性：1/24 与 1/25 分别判定，「不可用」要明说而不是悄悄隐藏。
        let lowSpeedAvailability = CameraShutterGate.lowSpeedAvailability(in: ability)
        var lowSpeedAvailable: [String] = []
        var lowSpeedUnavailable: [String] = []
        for target in CameraShutterSpeed.lowSpeedTiers {
            if lowSpeedAvailability[target] == true {
                lowSpeedAvailable.append(target.displayName)
            } else {
                lowSpeedUnavailable.append(target.displayName)
            }
        }
        if !lowSpeedUnavailable.isEmpty {
            let tail = lowSpeedAvailable.isEmpty
                ? ""
                : "；可用：\(lowSpeedAvailable.joined(separator: "、"))"
            notes.append("慢门 \(lowSpeedUnavailable.joined(separator: "、")) 在该设备 format 表里不可用\(tail)。")
        }

        notes.append(contentsOf: ability.notes)
        notes.append(contentsOf: state.notes)

        let torchAvailable = device.hasTorch && device.isTorchAvailable
        if !torchAvailable {
            notes.append("该设备当前不可用手电筒（无闪光灯或被占用）。")
        }

        let gains = device.deviceWhiteBalanceGains
        return CameraParameterSnapshot(
            deviceName: device.localizedName,
            deviceModel: device.modelID,
            resolution: resolutionText,
            frameRate: frameRateText,
            exposure: exposure,
            exposureValue: device.exposureTargetBias,
            hasTorch: device.hasTorch,
            torchAvailable: torchAvailable,
            torchEnabled: device.torchMode == .on,
            notes: notes,
            ability: ability,
            state: state,
            lastActionSummary: lastAction,
            cameraPositions: sessionController.availableCameraPositions(),
            isManualExposure: device.exposureMode == .custom,
            supportsAutoExposure: device.isExposureModeSupported(.continuousAutoExposure),
            whiteBalanceGains: (Double(gains.redGain), Double(gains.greenGain), Double(gains.blueGain))
        )
    }

    /// 先交付更新后的快照，再在主队列发回执。恢复下一个参数时读到的是新格式的能力。
    private func publish(_ snapshot: CameraParameterSnapshot, then completion: (() -> Void)? = nil) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.latest = snapshot
            self.onSnapshot?(snapshot)
            completion?()
        }
    }

    /// 曝光时间的可读文案：慢门档优先用 `1/24s` 形态。
    static func exposureDescription(seconds: Double) -> String {
        guard seconds > 0 else { return "—" }
        let denominator = Int((1.0 / seconds).rounded())
        if denominator >= 1 && RecordingTiers.shutterDenominators.contains(denominator) {
            return "1/\(denominator)s"
        }
        if seconds >= 1 {
            return String(format: "%.2fs", seconds)
        }
        return String(format: "1/%.0fs", 1.0 / seconds)
    }
}

/// 读不到当前帧率时的兜底档（产品档位首档，不是机型常量）。
private let CameraTiersDefaultFrameRate: Double = 24
