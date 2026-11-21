import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

/// 取景参数的主队列入口。设备落档后的快照决定下一次操作的选项。
final class CameraCaptureControls {
    typealias Row = CameraParameterPanelView.Row
    typealias Section = CameraParameterPanelView.Section
    private typealias Adjustment = (CameraParameterAdjustOutcome) -> Void
    private typealias RestoreStep = (@escaping () -> Void) -> Void

    /// iOS 绝对 EV、归一镜头位置及防抖级别与 Android 的同名参数域不同。
    private enum LocalKey {
        static let bias = "ios_capture_exposure_bias"
        static let lensPosition = "ios_capture_lens_position"
        static let focus = "ios_capture_focus_mode"
        static let whiteBalance = "ios_capture_white_balance_mode"
        static let gains = "ios_capture_white_balance_gains"
        static let stabilization = "ios_capture_stabilization"
        static let codec = "ios_capture_codec"
    }

    private weak var presenter: UIViewController?
    private let camera: CameraParameterController
    private let store = WotaSettingsStore.standard()
    private var needsRestore = true
    private var restoreID: UUID?
    private var restoreNotes: [String] = []
    private var isAdjusting = false
    private var selectionState = CameraRecordingCoordinator.Selection()
    private var echo = EchoParams.default

    var onChange: (() -> Void)?
    var isBusy: Bool { isAdjusting || restoreID != nil }
    private(set) var restoreNotice: String?

    init(presenter: UIViewController, camera: CameraParameterController) {
        self.presenter = presenter
        self.camera = camera
    }

    func reloadSettingsForNextAppearance() {
        selectionState.profile.userBitRate = store.defaultBitrate()
        selectionState.profile.sampleRate = store.defaultSampleRate()
        selectionState.profile.audioEnabled = !store.defaultAudioMuted()
        selectionState.profile.codec = RecordingVideoCodec(rawValue: store.defaults.integer(forKey: LocalKey.codec)) ?? .h264
        selectionState.stabilization = store.defaults.string(forKey: LocalKey.stabilization)
            .flatMap(CameraStabilizationMode.init(rawValue:)) ?? .off
        echo = store.echoParams()
        needsRestore = true
    }

    func suspendRestore() {
        if restoreID != nil {
            restoreID = nil
            needsRestore = true
        }
    }

    func applyStoredDefaultsIfNeeded(_ snapshot: CameraParameterSnapshot) {
        guard needsRestore, !isBusy, snapshot.ability.isAvailable else { return }
        needsRestore = false
        restoreNotes.removeAll()
        restoreNotice = nil
        let id = UUID()
        restoreID = id
        onChange?()

        let defaults = WotaParamsRestore.load(from: store)
        let steps: [RestoreStep] = [
            { done in self.restoreLens(defaults.lens, done: done) },
            { done in self.restoreFormat(size: self.store.defaultSize(), rate: Double(defaults.fps), done: done) },
            { done in self.restoreExposure(defaults, done: done) },
            { done in self.restoreFocus(defaults.afMode, done: done) },
            { done in self.restoreWhiteBalance(defaults, done: done) },
            { done in self.restoreZoomAndTorch(defaults, done: done) },
        ]
        runRestore(steps, start: 0, id: id)
    }

    private func runRestore(_ steps: [RestoreStep], start: Int, id: UUID) {
        guard restoreID == id else { return }
        if start < steps.count && camera.latest.ability.isAvailable {
            steps[start] { self.runRestore(steps, start: start + 1, id: id) }
        } else {
            if camera.latest.ability.isAvailable {
                camera.setStabilization(mode: selectionState.stabilization)
            }
            restoreNotice = restoreNotes.isEmpty ? nil : restoreNotes.joined(separator: "\n")
            restoreID = nil
            onChange?()
        }
    }

    private func restoreLens(_ lens: LensType, done: @escaping () -> Void) {
        let position: CameraDevicePosition = lens == .front ? .front : .back
        if lens != .front && lens != .wide && lens != .auto {
            restoreNotes.append("预设镜头在此版本中按后置广角恢复。")
        }
        guard camera.latest.ability.position != position else { done(); return }
        guard camera.latest.cameraPositions.contains(position) else {
            restoreNotes.append("默认镜头不可用，保留当前镜头。")
            done()
            return
        }
        camera.switchCamera(to: position) { outcome in
            if !outcome.isSuccess { self.restoreNotes.append(outcome.summary) }
            done()
        }
    }

    private func restoreFormat(size: WotaVideoSize?, rate: Double, done: @escaping () -> Void) {
        let snapshot = camera.latest
        let preferred = size.map { CameraResolution(width: $0.width, height: $0.height) }
        guard let resolution = preferred.flatMap({ CameraFormatNegotiation.nearestResolution(to: $0, in: snapshot.ability) })
            ?? snapshot.state.resolution,
              let appliedRate = rates(for: resolution).min(by: { abs($0 - rate) < abs($1 - rate) }) else {
            restoreNotes.append("当前镜头无法恢复默认录制格式。")
            done()
            return
        }
        if appliedRate != rate || (preferred != nil && preferred != resolution) {
            restoreNotes.append(String(format: "预设格式已按当前镜头调整为 %@ · %.0f fps。", resolution.displayName, appliedRate))
        }
        if snapshot.state.resolution == resolution, snapshot.state.frameRateExact,
           let actual = snapshot.state.frameRate, abs(actual - appliedRate) < 0.01 {
            done()
            return
        }
        camera.setResolutionAndFrameRate(resolution: resolution, frameRate: appliedRate) { outcome in
            if !outcome.isSuccess { self.restoreNotes.append(outcome.errorMessage ?? "默认录制格式未落档。") }
            done()
        }
    }

    private func restoreExposure(_ defaults: WotaPersistedParams, done: @escaping () -> Void) {
        let snapshot = camera.latest
        let ability = snapshot.ability
        if defaults.aeMode == .manual {
            let iso = ability.hasManualISO ? Double(defaults.iso) : nil
            let seconds = manualExposureRange() == nil ? nil : Double(defaults.shutterNs) / 1_000_000_000
            guard iso != nil || seconds != nil else {
                restoreNotes.append("当前格式不支持恢复手动曝光。")
                done()
                return
            }
            camera.setManualExposure(iso: iso, exposureSeconds: seconds, completion: restoreReply(done))
        } else {
            let bias = store.defaults.object(forKey: LocalKey.bias) as? NSNumber
            let applyBias = {
                if let bias = bias, bias.floatValue.isFinite, self.camera.latest.exposure.isAdjustable,
                   !self.camera.latest.isManualExposure {
                    self.camera.setExposureBias(bias.floatValue, completion: self.restoreReply(done))
                } else {
                    done()
                }
            }
            if snapshot.isManualExposure {
                camera.setAutoExposure(completion: restoreReply(applyBias))
            } else {
                applyBias()
            }
        }
    }

    private func restoreFocus(_ stored: AfMode, done: @escaping () -> Void) {
        let snapshot = camera.latest
        let mode = store.defaults.string(forKey: LocalKey.focus).flatMap(CameraFocusMode.init(rawValue:))
            ?? (stored == .manual ? .locked : .continuousAutoFocus)
        guard snapshot.ability.focusModes.contains(mode) else {
            if mode == .locked || snapshot.ability.focusModes.count > 1 {
                restoreNotes.append("当前镜头不支持预设对焦模式。")
            }
            done()
            return
        }
        let position = mode == .locked && snapshot.ability.lensPositionRange.isAvailable
            ? (store.defaults.object(forKey: LocalKey.lensPosition) as? NSNumber)?.doubleValue : nil
        if position == nil && snapshot.state.focusMode == mode { done(); return }
        camera.setFocus(mode: mode, lensPosition: position, completion: restoreReply(done))
    }

    private func restoreWhiteBalance(_ defaults: WotaPersistedParams, done: @escaping () -> Void) {
        let snapshot = camera.latest
        if defaults.wbPreset == .manual, snapshot.ability.hasManualWhiteBalance {
            if let gains = store.defaults.array(forKey: LocalKey.gains) as? [Double], gains.count == 3 {
                camera.setManualWhiteBalanceGains(red: gains[0], green: gains[1], blue: gains[2], completion: restoreReply(done))
            } else {
                camera.setWhiteBalance(mode: .locked, kelvin: defaults.wbKelvin,
                                       tint: Double(defaults.wbTint), completion: restoreReply(done))
            }
            return
        }
        if defaults.wbPreset != .auto { restoreNotes.append("当前镜头无法恢复预设白平衡，保留系统模式。") }
        let modes = snapshot.ability.whiteBalanceModes
        let automatic: CameraWhiteBalanceMode? = modes.contains(.continuousAutoWhiteBalance)
            ? .continuousAutoWhiteBalance : (modes.contains(.autoWhiteBalance) ? .autoWhiteBalance : nil)
        let requested = store.defaults.string(forKey: LocalKey.whiteBalance).flatMap(CameraWhiteBalanceMode.init(rawValue:))
        let mode = requested.flatMap { modes.contains($0) ? $0 : nil } ?? automatic
        if let mode = mode, mode != snapshot.state.whiteBalanceMode {
            camera.setWhiteBalance(mode: mode, completion: restoreReply(done))
        } else {
            done()
        }
    }

    private func restoreZoomAndTorch(_ defaults: WotaPersistedParams, done: @escaping () -> Void) {
        let snapshot = camera.latest
        let torch = defaults.flash == .torch
        let applyTorch = {
            if torch != self.camera.latest.torchEnabled, self.camera.latest.torchAvailable {
                self.camera.setTorch(enabled: torch, completion: self.restoreReply(done))
            } else {
                if torch && !self.camera.latest.torchAvailable { self.restoreNotes.append("当前镜头无法恢复常亮手电。") }
                done()
            }
        }
        if snapshot.ability.hasZoom, let zoom = snapshot.state.zoomFactor, abs(zoom - Double(defaults.zoom)) > 0.01 {
            camera.setZoom(factor: Double(defaults.zoom), completion: restoreReply(applyTorch))
        } else {
            applyTorch()
        }
    }

    private func restoreReply(_ done: @escaping () -> Void) -> Adjustment {
        let id = restoreID
        return { outcome in
            guard let id = id, self.restoreID == id else { return }
            if !outcome.isSuccess { self.restoreNotes.append(outcome.summary) }
            done()
        }
    }

    func selection() -> CameraRecordingCoordinator.Selection {
        var result = selectionState
        let p = echo.sanitized()
        result.echo = RenderEffectParameters(
            isEnabled: p.enabled, mode: RenderEchoMode(androidName: p.mode.rawValue) ?? .keepBrightness,
            multiplier: p.multiplier, highlightThreshold: p.threshold, trailGain: p.gain,
            trailColorMode: RenderTrailColorMode(androidName: p.trailColorMode.rawValue) ?? .off,
            trailColor: p.trailColor, trailTolerance: p.trailTol
        )
        result.profile.usesGPU = p.enabled
        return result
    }

    func sections() -> [Section] {
        let snapshot = camera.latest
        guard snapshot.ability.isAvailable else { return [Section(title: "相机", rows: [Row("取景", "正在等待相机")])] }
        return [
            Section(title: "镜头与格式", rows: formatRows(snapshot)),
            Section(title: "曝光", rows: exposureRows(snapshot)),
            Section(title: "对焦与白平衡", rows: focusRows(snapshot) + whiteBalanceRows(snapshot)),
            Section(title: "录像档位", rows: recordingRows(snapshot)),
            Section(title: "成片回波", rows: echoRows()),
        ] + noticeSections(snapshot)
    }

    private func formatRows(_ snapshot: CameraParameterSnapshot) -> [Row] {
        let current = snapshot.state.frameRate ?? Double(store.defaultFps())
        let lens: (CameraDevicePosition) -> String = { $0 == .front ? "前置相机" : "后置广角" }
        let resolutions = snapshot.state.resolutionTiers.compactMap { size -> (String, CameraResolution, Double)? in
            let supported = rates(for: size)
            guard let rate = supported.min(by: { abs($0 - current) < abs($1 - current) }) else { return nil }
            let label = abs(rate - current) < 0.01 ? size.displayName : String(format: "%@ · %.0f fps", size.displayName, rate)
            return (label, size, rate)
        }
        let frameRates = snapshot.state.resolution.map(rates(for:)) ?? []
        var rows = [
            choices("镜头", snapshot.deviceName, values: snapshot.cameraPositions.map { (lens($0), $0) }) { position in
                self.run { done in
                    self.camera.switchCamera(to: position) { result in
                        if result.isSuccess {
                            self.store.setDefaultLens(position == .front ? .front : .wide)
                            self.store.flush()
                            self.camera.setStabilization(mode: self.selectionState.stabilization)
                        }
                        done()
                    }
                }
            },
            choices("分辨率", snapshot.resolution, values: resolutions.map { ($0.0, CameraVideoConfigurationRequest(resolution: $0.1, frameRate: $0.2)) }) { request in
                self.setFormat(request)
            },
            choices("帧率", snapshot.frameRate, values: frameRates.map { (String(format: "%.0f fps", $0), $0) }) { rate in
                guard let resolution = self.camera.latest.state.resolution else { return }
                self.setFormat(CameraVideoConfigurationRequest(resolution: resolution, frameRate: rate))
            },
        ]
        if let zoom = snapshot.state.zoomFactor, snapshot.ability.hasZoom {
            rows.append(number("变焦", String(format: "%.2f×", zoom), initial: zoom, range: snapshot.ability.zoomRange) { value in
                self.adjust({ self.camera.setZoom(factor: value, completion: $0) }) {
                    self.store.setDefaultZoom(Float(self.camera.latest.ability.zoomRange.clamp(value)))
                }
            })
        } else { rows.append(Row("变焦", "设备不支持")) }
        if snapshot.torchAvailable {
            rows.append(choices("手电筒", snapshot.torchEnabled ? "常亮" : "关闭", values: [("关闭", false), ("常亮", true)]) { enabled in
                self.adjust({ self.camera.setTorch(enabled: enabled, completion: $0) }) {
                    self.store.setDefaultFlash(enabled ? .torch : .off)
                }
            })
        } else { rows.append(Row("手电筒", snapshot.hasTorch ? "当前不可用" : "设备不支持")) }
        return rows
    }

    private func setFormat(_ request: CameraVideoConfigurationRequest) {
        run { done in
            self.camera.setResolutionAndFrameRate(resolution: request.resolution, frameRate: request.frameRate) { outcome in
                if outcome.isSuccess {
                    self.saveFormat(resolution: outcome.appliedResolution, rate: request.frameRate)
                    self.store.flush()
                    self.camera.setStabilization(mode: self.selectionState.stabilization)
                }
                done()
            }
        }
    }

    private func saveFormat(resolution: CameraResolution?, rate: Double) {
        if let resolution = resolution {
            store.setDefaultSize(WotaVideoSize(width: resolution.width, height: resolution.height))
        }
        if let fps = Int(exactly: rate.rounded()) { store.setDefaultFps(fps) }
    }

    private func rates(for resolution: CameraResolution) -> [Double] {
        let formats = camera.latest.ability.formats.filter { $0.isUsable && $0.resolution == resolution }
        return CameraFormatNegotiation.frameRateTiers.filter { rate in formats.contains { $0.supports(frameRate: rate) } }
    }

    private func exposureRows(_ snapshot: CameraParameterSnapshot) -> [Row] {
        let ability = snapshot.ability
        let state = snapshot.state
        let seconds = state.exposureDurationSeconds ?? 0
        let duration = CameraParameterController.exposureDescription(seconds: seconds)
        let usableExposure = manualExposureRange()
        var modes: [(String, Bool)] = []
        if snapshot.supportsAutoExposure { modes.append(("连续自动曝光", false)) }
        if ability.hasManualISO || usableExposure != nil { modes.append(("手动曝光", true)) }
        var rows = [choices("曝光模式", snapshot.isManualExposure ? "手动" : "系统", values: modes) { manual in
            if manual {
                let current = self.camera.latest
                self.setExposure(iso: current.ability.hasManualISO ? current.state.iso : nil,
                                 milliseconds: current.ability.hasManualISO ? nil : current.state.exposureDurationSeconds.map { $0 * 1_000 })
            } else {
                self.adjust({ self.camera.setAutoExposure(completion: $0) }) {
                    self.store.setDefaultAeMode(.auto)
                }
            }
        }]
        let bias = snapshot.exposure
        rows.append(number("曝光补偿", snapshot.isManualExposure ? "手动曝光时不可调" : String(format: "%+.1f EV", snapshot.exposureValue),
                           initial: Double(snapshot.exposureValue),
                           range: snapshot.isManualExposure ? nil : CameraNumericRange(minimum: Double(bias.minimum), maximum: Double(bias.maximum))) { value in
            self.adjust({ self.camera.setExposureBias(Float(value), completion: $0) }) {
                self.store.defaults.set(value, forKey: LocalKey.bias)
            }
        })
        rows.append(number("ISO", state.iso.map { String(format: "%.0f", $0) } ?? "不可用",
                           initial: state.iso ?? ability.isoRange.minimum, range: ability.isoRange) { self.setExposure(iso: $0, milliseconds: nil) })
        rows.append(number("曝光时间（ms）", usableExposure == nil ? "\(duration) · 不可调" : duration,
                           initial: seconds * 1_000, range: usableExposure) { self.setExposure(iso: nil, milliseconds: $0) })
        for target in CameraShutterSpeed.lowSpeedTiers {
            let request = CameraVideoConfigurationRequest(resolution: ability.activeResolution, frameRate: Double(target.denominator))
            let match = CameraFormatNegotiation.selectFormat(for: request, in: ability)
            let caption = match.isApplied ? (match.selectedResolution == ability.activeResolution ? "锁定至 \(target.denominator) fps" : match.selectedResolution?.displayName ?? "可选") : "当前镜头不可用"
            rows.append(Row("慢门 \(target.displayName)", caption, action: match.isApplied && !isBusy ? { [weak self] source in
                self?.presentChoices(title: "慢门 \(target.displayName)", message: match.notes.first,
                                     values: [("应用", target)], source: source) { self?.setSlowShutter($0) }
            } : nil))
        }
        return rows
    }

    private func manualExposureRange() -> CameraNumericRange? {
        let snapshot = camera.latest
        let device = snapshot.ability.exposureDurationRange
        let limit = Double(CameraShutterGate.exposureCeilingNanoseconds(frameRate: snapshot.state.frameRate ?? 30))
        guard device.isAvailable, device.minimum <= limit else { return nil }
        return CameraNumericRange(minimum: device.minimum / 1_000_000, maximum: min(device.maximum, limit) / 1_000_000)
    }

    private func setExposure(iso: Double?, milliseconds: Double?) {
        adjust({ self.camera.setManualExposure(iso: iso, exposureSeconds: milliseconds.map { $0 / 1_000 }, completion: $0) }) {
            self.store.setDefaultAeMode(.manual)
            if let iso = iso, let value = Int(exactly: self.camera.latest.ability.isoRange.clamp(iso).rounded()) {
                self.store.setDefaultIso(value)
            }
            if let milliseconds = milliseconds, let range = self.manualExposureRange(),
               let ns = Int(exactly: (range.clamp(milliseconds) * 1_000_000).rounded()) {
                self.store.setDefaultShutterNs(max(1, ns))
            }
        }
    }

    private func setSlowShutter(_ target: CameraShutterSpeed) {
        run { done in
            self.camera.setLowSpeedShutter(target) { outcome in
                if outcome.frameDurationApplied && !outcome.rolledBack {
                    self.saveFormat(resolution: outcome.appliedResolution, rate: outcome.appliedFrameRate ?? Double(target.denominator))
                    if outcome.sensorExposureApplied {
                        self.store.setDefaultAeMode(.manual)
                        let duration = outcome.assessment.effectiveExposureNanoseconds ?? target.nanoseconds
                        if let ns = Int(exactly: duration) { self.store.setDefaultShutterNs(ns) }
                    }
                    self.store.flush()
                    self.camera.setStabilization(mode: self.selectionState.stabilization)
                }
                done()
            }
        }
    }

    private func focusRows(_ snapshot: CameraParameterSnapshot) -> [Row] {
        let state = snapshot.state
        return [
            choices("对焦模式", state.focusMode?.displayName ?? "固定", values: snapshot.ability.focusModes.map { ($0.displayName, $0) }) { mode in
                self.changeFocus(mode, position: nil)
            },
            number("镜头位置", state.lensPosition.map { String(format: "%.2f", $0) } ?? "设备不支持",
                   initial: state.lensPosition ?? 0, range: snapshot.ability.lensPositionRange) {
                self.changeFocus(.locked, position: $0)
            },
        ]
    }

    private func changeFocus(_ mode: CameraFocusMode, position: Double?) {
        adjust({ self.camera.setFocus(mode: mode, lensPosition: position, completion: $0) }) {
            self.store.defaults.set(mode.rawValue, forKey: LocalKey.focus)
            if mode != .autoFocus { self.store.setDefaultAfMode(mode == .locked ? .manual : .continuousVideo) }
            if let position = position { self.store.defaults.set(position, forKey: LocalKey.lensPosition) }
            else { self.store.defaults.removeObject(forKey: LocalKey.lensPosition) }
        }
    }

    private func whiteBalanceRows(_ snapshot: CameraParameterSnapshot) -> [Row] {
        let state = snapshot.state
        let manual = snapshot.ability.hasManualWhiteBalance
        let kelvin = Double(state.whiteBalanceKelvin ?? store.defaultWbKelvin())
        let tint = state.whiteBalanceTint ?? Double(store.defaultWbTint())
        var rows = [choices("白平衡模式", state.whiteBalanceMode?.displayName ?? "固定",
                            values: snapshot.ability.whiteBalanceModes.map { ($0.displayName, $0) }) { mode in
            self.adjust({ self.camera.setWhiteBalance(mode: mode, completion: $0) }) {
                self.store.defaults.set(mode.rawValue, forKey: LocalKey.whiteBalance)
                self.store.defaults.removeObject(forKey: LocalKey.gains)
                self.store.setDefaultWbPreset(.auto)
            }
        }]
        rows.append(number("色温（K）", manual ? String(format: "%.0f", kelvin) : "设备不支持", initial: kelvin,
                           range: manual ? RecordingTiers.kelvinRange : nil) { self.changeTemperature(kelvin: $0, tint: tint) })
        rows.append(number("色调", manual ? String(format: "%+.0f", tint) : "设备不支持", initial: tint,
                           range: manual ? RecordingTiers.tintRange : nil) { self.changeTemperature(kelvin: kelvin, tint: $0) })
        if manual, let rgb = snapshot.whiteBalanceGains {
            let range = snapshot.ability.whiteBalanceGainRange
            let values = [rgb.red, rgb.green, rgb.blue]
            for (channel, title) in ["红增益", "绿增益", "蓝增益"].enumerated() {
                rows.append(number(title, String(format: "%.2f×", values[channel]), initial: values[channel], range: range) { value in
                    var gains = values.map { range.clamp($0) }
                    gains[channel] = value
                    self.adjust({ self.camera.setManualWhiteBalanceGains(red: gains[0], green: gains[1], blue: gains[2], completion: $0) }) {
                        self.store.defaults.set(gains, forKey: LocalKey.gains)
                        self.store.defaults.set(CameraWhiteBalanceMode.locked.rawValue, forKey: LocalKey.whiteBalance)
                        self.store.setDefaultWbPreset(.manual)
                    }
                })
            }
        } else { rows.append(Row("RGB 增益", "设备不支持")) }
        return rows
    }

    private func changeTemperature(kelvin: Double, tint: Double) {
        let temperature = Int(RecordingTiers.kelvinRange.clamp(kelvin).rounded())
        let roundedTint = Int(RecordingTiers.tintRange.clamp(tint).rounded())
        adjust({ self.camera.setWhiteBalance(mode: .locked, kelvin: temperature, tint: Double(roundedTint), completion: $0) }) {
            self.store.setDefaultWbPreset(.manual)
            self.store.setDefaultWbKelvin(temperature)
            self.store.setDefaultWbTint(roundedTint)
            self.store.defaults.set(CameraWhiteBalanceMode.locked.rawValue, forKey: LocalKey.whiteBalance)
            self.store.defaults.removeObject(forKey: LocalKey.gains)
        }
    }

    private func recordingRows(_ snapshot: CameraParameterSnapshot) -> [Row] {
        let profile = selectionState.profile
        let rates = RecordingTiers.videoBitRates.map { ("\($0 / 1_000_000) Mbps", $0) }
        let audioRates = RecordingTiers.audioSampleRates.map { (String(format: "%g kHz", Double($0) / 1_000), $0) }
        var codecs: [RecordingVideoCodec] = [.h264]
        if #available(iOS 11.0, *) { codecs.append(.hevc) }
        let modes = snapshot.ability.stabilizationModes
        let preferred = selectionState.stabilization
        let stabilization = modes.contains(preferred) ? preferred.displayName : "\(preferred.displayName) · 当前不可用"
        return [
            choices("编码", codecs.contains(profile.codec) ? profile.codec.displayName : "H.264（系统限制）",
                    values: codecs.map { ($0.displayName, $0) }, message: "开录时检查相机和编码器能力。") { codec in
                self.selectionState.profile.codec = codec
                self.store.defaults.set(codec.rawValue, forKey: LocalKey.codec)
                self.onChange?()
            },
            choices("目标码率", profile.userBitRate > 0 ? "\(profile.userBitRate / 1_000_000) Mbps" : "自动", values: [("自动", 0)] + rates) { bps in
                self.selectionState.profile.userBitRate = bps
                self.store.setDefaultBitrate(bps)
                self.store.flush()
                self.onChange?()
            },
            choices("现场声", profile.audioEnabled ? "录制麦克风" : "关闭", values: [("关闭", false), ("录制麦克风", true)]) { enabled in
                self.selectionState.profile.audioEnabled = enabled
                self.store.setDefaultAudioMuted(!enabled)
                self.store.flush()
                self.onChange?()
            },
            choices("音频采样率", profile.audioEnabled ? String(format: "%g kHz", Double(profile.sampleRate) / 1_000) : "现场声已关闭",
                    values: profile.audioEnabled ? audioRates : []) { hz in
                self.selectionState.profile.sampleRate = hz
                self.store.setDefaultSampleRate(hz)
                self.store.flush()
                self.onChange?()
            },
            choices("成片防抖", stabilization, values: modes.map { ($0.displayName, $0) },
                    message: "录像连接建立后显示实际使用的防抖模式。") { mode in
                self.selectionState.stabilization = mode
                self.store.defaults.set(mode.rawValue, forKey: LocalKey.stabilization)
                self.camera.setStabilization(mode: mode)
                self.onChange?()
            },
        ]
    }

    private func echoRows() -> [Row] {
        var rows = [choices("残影", echo.enabled ? "成片启用" : "关闭", values: [("关闭", false), ("成片启用", true)],
                            message: "取景保持原画。实际后端与降级在开录时显示。") { enabled in
            self.echo.enabled = enabled
            self.saveEcho()
        }]
        if echo.enabled {
            rows.append(choices("合成", echo.mode == .trail ? "提亮拖尾" : "保持亮度", values: [("保持亮度", EchoMode.keepBrightness), ("提亮拖尾", .trail)]) {
                self.echo.mode = $0
                self.saveEcho()
            })
            rows.append(choices("光弧倍数", "\(echo.multiplier)×", values: (EchoParams.minMultiplier...EchoParams.maxMultiplier).map { ("\($0)×", $0) }) {
                self.echo.multiplier = $0
                self.saveEcho()
            })
            if echo.mode == .trail {
                let threshold = CameraNumericRange(minimum: Double(EchoParams.minThreshold), maximum: Double(EchoParams.maxThreshold))
                rows.append(number("高光阈值", String(format: "%.2f", echo.threshold), initial: Double(echo.threshold), range: threshold) {
                    self.echo.threshold = Float($0)
                    self.saveEcho()
                })
                rows.append(number("拖尾强度", String(format: "%.2f", echo.gain), initial: Double(echo.gain),
                                   range: CameraNumericRange(minimum: 0, maximum: Double(EchoParams.maxGain))) {
                    self.echo.gain = Float($0)
                    self.saveEcho()
                })
            }
        }
        return rows
    }

    private func saveEcho() {
        store.setEchoParams(echo.sanitized())
        store.flush()
        onChange?()
    }

    private func noticeSections(_ snapshot: CameraParameterSnapshot) -> [Section] {
        var unique = Set<String>()
        let raw = [snapshot.lastActionSummary, restoreNotice].compactMap { $0 } + snapshot.notes
        let notices = raw.filter { unique.insert($0).inserted }
        return notices.isEmpty ? [] : [Section(title: "相机回执", rows: notices.map { Row($0, "") })]
    }

    private func run(_ action: (@escaping () -> Void) -> Void) {
        guard !isBusy else { return }
        isAdjusting = true
        restoreNotice = nil
        onChange?()
        action {
            self.isAdjusting = false
            self.onChange?()
        }
    }

    private func adjust(_ action: (@escaping Adjustment) -> Void, save: @escaping () -> Void) {
        run { done in
            action { result in
                if result.isSuccess {
                    save()
                    if !self.store.flush() { self.restoreNotice = "参数已应用，默认设置同步失败。" }
                }
                done()
            }
        }
    }

    private func choices<Value>(_ title: String, _ current: String, values: [(String, Value)],
                                message: String? = nil, apply: @escaping (Value) -> Void) -> Row {
        guard !values.isEmpty, !isBusy else { return Row(title, current) }
        return Row(title, current) { [weak self] source in
            self?.presentChoices(title: title, message: message, values: values, source: source, apply: apply)
        }
    }

    private func presentChoices<Value>(title: String, message: String?, values: [(String, Value)],
                                       source: UIView, apply: @escaping (Value) -> Void) {
        guard !isBusy, let presenter = presenter, presenter.presentedViewController == nil else { return }
        let alert = UIAlertController(title: title, message: message, preferredStyle: .actionSheet)
        for (label, value) in values {
            alert.addAction(UIAlertAction(title: label, style: .default) { _ in apply(value) })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let popover = alert.popoverPresentationController {
            popover.sourceView = source
            popover.sourceRect = source.bounds
        }
        presenter.present(alert, animated: true)
    }

    private func number(_ title: String, _ current: String, initial: Double,
                        range: CameraNumericRange?, apply: @escaping (Double) -> Void) -> Row {
        guard !isBusy, let range = range, range.isAvailable else { return Row(title, current) }
        return Row(title, current) { [weak self] _ in
            guard let self = self, !self.isBusy, let presenter = self.presenter,
                  presenter.presentedViewController == nil else { return }
            let message = String(format: "可用范围 %.4g 至 %.4g", range.minimum, range.maximum)
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addTextField { field in
                field.keyboardType = .numbersAndPunctuation
                field.clearButtonMode = .whileEditing
                field.text = String(format: "%.6g", initial.isFinite ? range.clamp(initial) : range.minimum)
                field.accessibilityLabel = title
            }
            alert.addAction(UIAlertAction(title: "取消", style: .cancel))
            alert.addAction(UIAlertAction(title: "应用", style: .default) { [weak alert, weak self] _ in
                let text = alert?.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if let value = Double(text.replacingOccurrences(of: ",", with: ".")), value.isFinite,
                   value >= range.minimum, value <= range.maximum {
                    apply(value)
                } else {
                    self?.restoreNotice = "\(title)的输入超出可用范围，设备设置保持原值。"
                    self?.onChange?()
                }
            })
            presenter.present(alert, animated: true)
        }
    }
}
