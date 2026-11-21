import AVFoundation
import CoreMedia
import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 运行时能力探测：把 `AVCaptureDevice` / `AVCaptureDevice.Format` 的真实能力翻译成
/// WotaCore 的纯数据结构（`CameraAbilitySnapshot` / `CameraParameterState`）。
///
/// 约束：
/// - **所有数值都从设备读**，本文件不出现任何机型常量；
/// - 不使用 iOS 10 不具备的符号；`minAvailableVideoZoomFactor` / `maxAvailableVideoZoomFactor`
///   是 iOS 11 API，用 `#available` 门控，iOS 10 退回 `videoZoomFactor` + `videoMaxZoomFactor`；
/// - 本类不做线程切换：调用方（`CameraParameterController`）必须已经在
///   `CameraSessionController.sessionQueue` 上，与启停/配置互斥；
/// - 设备没有输出/未就绪时返回 `.unavailable` 形态，接口仍有确定返回值，UI 据此显示禁用与原因。

final class CameraCapabilityProbe {

    /// format 列表按设备缓存：`device.formats` 动辄上百项，每次刷新都重建会拖慢参数面板。
    private var cachedDeviceID: String?
    private var cachedFormats: [CameraFormatDescriptor] = []

    // MARK: - 能力快照

    /// 设备 → 能力快照。`device == nil` 时返回 `.unavailable`。
    func snapshot(for device: AVCaptureDevice?) -> CameraAbilitySnapshot {
        guard let device = device else {
            return .unavailable
        }

        let formats = formatDescriptors(for: device)
        var notes: [String] = []

        let activeResolution = CameraCapabilityProbe.resolution(
            of: device.activeFormat
        )
        let activeRanges = CameraCapabilityProbe.frameRateRanges(
            of: device.activeFormat
        )

        let supportsManualExposure = device.isExposureModeSupported(.custom)
        let isoRange = supportsManualExposure ? CameraNumericRange(
            minimum: Double(device.activeFormat.minISO),
            maximum: Double(device.activeFormat.maxISO)
        ) : .unavailable
        // 统一口径：设备 CMTime 转成纳秒，供曝光下发与慢门能力门控共用。
        let exposureDurationRange = supportsManualExposure ? CameraNumericRange(
            minimum: Double(CameraCapabilityProbe.nanoseconds(device.activeFormat.minExposureDuration)),
            maximum: Double(CameraCapabilityProbe.nanoseconds(device.activeFormat.maxExposureDuration))
        ) : .unavailable
        let exposureTargetBiasRange = ExposureBiasRange(
            minimum: device.minExposureTargetBias,
            maximum: device.maxExposureTargetBias
        )

        var focusModes: [CameraFocusMode] = []
        for mode in CameraFocusMode.allCases where device.isFocusModeSupported(mode.deviceMode) {
            focusModes.append(mode)
        }
        // 定焦相机也可能支持锁定，只有支持指定位置时才开放 0…1 滑杆。
        let lensPositionRange = device.isFocusModeSupported(.locked)
            && device.isLockingFocusWithCustomLensPositionSupported
            ? CameraNumericRange(minimum: 0, maximum: 1)
            : .unavailable

        var whiteBalanceModes: [CameraWhiteBalanceMode] = []
        for mode in CameraWhiteBalanceMode.allCases where device.isWhiteBalanceModeSupported(mode.deviceMode) {
            whiteBalanceModes.append(mode)
        }
        let whiteBalanceGainRange = device.isWhiteBalanceModeSupported(.locked)
            && device.isLockingWhiteBalanceWithCustomDeviceGainsSupported
            ? CameraNumericRange(minimum: 1, maximum: Double(device.maxWhiteBalanceGain))
            : .unavailable

        let zoomRange = CameraCapabilityProbe.zoomRange(of: device, notes: &notes)

        let torchAvailable = device.hasTorch && device.isTorchAvailable
            && device.isTorchModeSupported(.on)
        if !device.hasTorch {
            notes.append("该设备没有闪光灯，手电筒关闭。")
        } else if !torchAvailable {
            notes.append("手电筒当前不可用（被占用、过热或设备不支持常亮）。")
        }
        if !exposureTargetBiasRange.isAdjustable {
            notes.append("该设备不支持曝光补偿，滑杆已禁用。")
        }
        if !exposureDurationRange.isAvailable {
            notes.append("该设备不支持手动 sensor 曝光时间，慢门只能靠帧周期锁定。")
        }
        if !isoRange.isAvailable {
            notes.append("该设备不支持手动 ISO。")
        }
        if formats.isEmpty {
            notes.append("设备未上报可用 format，分辨率/帧率档位为空。")
        } else {
            var usableCount = 0
            for format in formats where format.isUsable {
                usableCount += 1
            }
            if usableCount == 0 {
                notes.append("设备上报的 format 均为抽象档（无分辨率或无帧率区间），档位表为空。")
            }
        }
        notes.append("以上参数域取自当前活动 format；切换分辨率/帧率后需重新探测。")

        return CameraAbilitySnapshot(
            isAvailable: true,
            position: CameraDevicePosition(devicePosition: device.position),
            deviceName: device.localizedName,
            deviceModel: device.modelID,
            activeResolution: activeResolution,
            formats: formats,
            activeFrameRateRanges: activeRanges,
            isoRange: isoRange,
            exposureDurationRange: exposureDurationRange,
            exposureTargetBiasRange: exposureTargetBiasRange,
            focusModes: focusModes,
            lensPositionRange: lensPositionRange,
            whiteBalanceModes: whiteBalanceModes,
            whiteBalanceGainRange: whiteBalanceGainRange,
            zoomRange: zoomRange,
            hasTorch: device.hasTorch,
            torchAvailable: torchAvailable,
            stabilizationModes: CameraCapabilityProbe.stabilizationModes(of: device.activeFormat),
            notes: notes
        )
    }

    // MARK: - 参数状态快照

    /// 设备 + 能力 → 当前参数落点（分辨率/帧率/ISO/曝光时间/EV/对焦/白平衡/变焦/手电筒）。
    ///
    /// 防抖状态是**连接级**属性：`videoConnection` 为 nil 时如实显示关闭并加 note，
    /// 绝不从设备上伪造一个「已开启」。调用方应优先传录制输出连接。
    func parameterState(
        for device: AVCaptureDevice?,
        ability: CameraAbilitySnapshot,
        videoConnection: AVCaptureConnection? = nil
    ) -> CameraParameterState {
        guard let device = device, ability.isAvailable else {
            return .unavailable
        }

        var notes: [String] = []

        let activeResolution = CameraCapabilityProbe.resolution(of: device.activeFormat)
        let resolutionTiers = CameraFormatNegotiation.availableResolutions(in: ability)
        let frameRateTiers = CameraFormatNegotiation.supportedFrameRates(in: ability)

        let frameRate = CameraCapabilityProbe.activeFrameRate(of: device)
        let frameRateExact = CameraCapabilityProbe.activeFrameRateIsExact(of: device)
        if frameRate == nil {
            notes.append("无法读取当前帧周期，帧率显示未知。")
        }

        let kelvinTint = CameraCapabilityProbe.kelvinAndTint(of: device)

        let stabilizationMode: CameraStabilizationMode
        if let connection = videoConnection {
            stabilizationMode = CameraStabilizationMode(deviceMode: connection.activeVideoStabilizationMode)
            if stabilizationMode != .off
                && !device.activeFormat.isVideoStabilizationModeSupported(connection.activeVideoStabilizationMode) {
                // 换 format 后支持集合可能变化；旧连接值可能已被设备回退，必须如实提示。
                notes.append("当前 format 不支持连接上的防抖模式，防抖可能已回退为关闭。")
            }
        } else {
            notes.append("尚未建立视频连接，防抖状态显示为关闭。")
            stabilizationMode = .off
        }

        return CameraParameterState(
            resolution: activeResolution,
            resolutionTiers: resolutionTiers,
            frameRate: frameRate,
            frameRateTiers: frameRateTiers,
            frameRateExact: frameRateExact,
            iso: Double(device.iso),
            isoRange: ability.isoRange,
            exposureDurationSeconds: CameraCapabilityProbe.seconds(device.exposureDuration),
            exposureDurationRange: ability.exposureDurationRange,
            exposureTargetBias: device.exposureTargetBias,
            exposureTargetBiasRange: ability.exposureTargetBiasRange,
            focusMode: CameraFocusMode(deviceMode: device.focusMode),
            focusModes: ability.focusModes,
            lensPosition: device.isFocusModeSupported(.locked) ? Double(device.lensPosition) : nil,
            lensPositionRange: ability.lensPositionRange,
            whiteBalanceMode: CameraWhiteBalanceMode(deviceMode: device.whiteBalanceMode),
            whiteBalanceModes: ability.whiteBalanceModes,
            whiteBalanceKelvin: kelvinTint?.kelvin,
            whiteBalanceTint: kelvinTint?.tint,
            whiteBalanceGainRange: ability.whiteBalanceGainRange,
            zoomFactor: Double(device.videoZoomFactor),
            zoomRange: ability.zoomRange,
            torchEnabled: device.torchMode == .on,
            stabilizationMode: stabilizationMode,
            stabilizationModes: ability.stabilizationModes,
            notes: notes
        )
    }

    // MARK: - format 映射

    /// 纯函数：单个 `AVCaptureDevice.Format` → 描述符。
    static func descriptor(for format: AVCaptureDevice.Format) -> CameraFormatDescriptor {
        return CameraFormatDescriptor(
            resolution: resolution(of: format),
            frameRateRanges: frameRateRanges(of: format),
            stabilizationModes: stabilizationModes(of: format)
        )
    }

    /// 分辨率为 0×0（systemPressure 之类的抽象 format）时视为不可用档，剔除以避免污染档位表。
    static func resolution(of format: AVCaptureDevice.Format) -> CameraResolution {
        let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        return CameraResolution(width: Int(dimensions.width), height: Int(dimensions.height))
    }

    static func frameRateRanges(of format: AVCaptureDevice.Format) -> [CameraFrameRateRange] {
        var result: [CameraFrameRateRange] = []
        for range in format.videoSupportedFrameRateRanges {
            result.append(CameraFrameRateRange(
                minimum: Double(range.minFrameRate),
                maximum: Double(range.maxFrameRate)
            ))
        }
        return result
    }

    static func stabilizationModes(of format: AVCaptureDevice.Format) -> [CameraStabilizationMode] {
        var result: [CameraStabilizationMode] = []
        for mode in CameraStabilizationMode.allCases where format.isVideoStabilizationModeSupported(mode.deviceMode) {
            result.append(mode)
        }
        return result
    }

    // MARK: - 设备读数辅助

    /// 当前活动分辨率/帧率对应的真实 format 对象（协商结果里的下标回查）。
    func format(at index: Int, of device: AVCaptureDevice) -> AVCaptureDevice.Format? {
        let formats = device.formats
        guard index >= 0, index < formats.count else { return nil }
        return formats[index]
    }

    static func activeFrameRate(of device: AVCaptureDevice) -> Double? {
        let seconds = CMTimeGetSeconds(device.activeVideoMinFrameDuration)
        guard seconds.isFinite, seconds > 0 else { return nil }
        return 1.0 / seconds
    }

    /// 当前帧率是否为固定档（min == max 帧周期）。
    static func activeFrameRateIsExact(of device: AVCaptureDevice) -> Bool {
        let minimum = CMTimeGetSeconds(device.activeVideoMinFrameDuration)
        let maximum = CMTimeGetSeconds(device.activeVideoMaxFrameDuration)
        guard minimum.isFinite, maximum.isFinite, minimum > 0, maximum > 0 else { return false }
        return Swift.abs(maximum - minimum) < 1e-9
    }

    static func seconds(_ time: CMTime) -> Double {
        let value = CMTimeGetSeconds(time)
        return value.isFinite ? value : 0
    }

    static func nanoseconds(_ time: CMTime) -> Int64 {
        let value = CMTimeGetSeconds(time)
        guard value.isFinite, value > 0 else { return 0 }
        return Int64((value * 1_000_000_000.0).rounded())
    }

    /// 变焦域。
    ///
    /// `minAvailableVideoZoomFactor` / `maxAvailableVideoZoomFactor` 是 iOS 11 API：
    /// iOS 10 走 `videoZoomFactor`(当前) 与 `activeFormat.videoMaxZoomFactor`(上界) 的旧口径。
    /// 明确不使用 `videoZoomFactorForDepthDataDelivery`（深度数据专用，与录制变焦无关）。
    static func zoomRange(of device: AVCaptureDevice, notes: inout [String]) -> CameraNumericRange {
        if #available(iOS 11.0, *) {
            let minimum = Double(device.minAvailableVideoZoomFactor)
            let maximum = Double(device.maxAvailableVideoZoomFactor)
            if maximum > minimum {
                return CameraNumericRange(minimum: minimum, maximum: maximum)
            }
            notes.append("设备上报的可用变焦域无效，变焦禁用。")
            return .unavailable
        }
        // iOS 10 旧口径：变焦下界按 1.0（`videoZoomFactor` 不低于 1），上界取活动 format 的 videoMaxZoomFactor。
        let maximum = Double(device.activeFormat.videoMaxZoomFactor)
        guard maximum > 1 else {
            return .unavailable
        }
        return CameraNumericRange(minimum: 1, maximum: maximum)
    }

    /// 白平衡增益 → 色温/色调（`temperatureAndTintValues` 是 iOS 8 起的正向可逆映射）。
    static func kelvinAndTint(of device: AVCaptureDevice) -> (kelvin: Int, tint: Double)? {
        guard device.isWhiteBalanceModeSupported(.locked) else { return nil }
        let gains = device.deviceWhiteBalanceGains
        guard gains.redGain.isFinite, gains.greenGain.isFinite, gains.blueGain.isFinite else { return nil }
        let values = device.temperatureAndTintValues(for: gains)
        guard values.temperature.isFinite, values.tint.isFinite else { return nil }
        let kelvin = Int(values.temperature.rounded())
        return (kelvin, Double(values.tint))
    }

    // MARK: - 私有

    private func formatDescriptors(for device: AVCaptureDevice) -> [CameraFormatDescriptor] {
        if cachedDeviceID == device.uniqueID {
            return cachedFormats
        }

        // 不过滤任何一项：`formats[i]` 必须与 `device.formats[i]` 一一对应，否则协商出来的
        // 下标回查真实 format 会错位。抽象档（0×0 或无帧率区间）由 `isUsable == false` 标记，
        // 档位表与协商逻辑各自跳过它们。
        var result: [CameraFormatDescriptor] = []
        for format in device.formats {
            result.append(CameraCapabilityProbe.descriptor(for: format))
        }
        cachedDeviceID = device.uniqueID
        cachedFormats = result
        return result
    }

    /// 设备切换/重新配置后主动失效缓存。
    func invalidateCache() {
        cachedDeviceID = nil
        cachedFormats = []
    }
}

// MARK: - WotaCore ↔ AVFoundation 枚举双向映射（同一 target 内使用）

extension CameraDevicePosition {
    init(devicePosition: AVCaptureDevice.Position) {
        switch devicePosition {
        case .back:
            self = .back
        case .front:
            self = .front
        case .unspecified:
            self = .unspecified
        @unknown default:
            self = .unspecified
        }
    }

    /// 请求切换时用的 AVFoundation 朝向。
    var devicePosition: AVCaptureDevice.Position {
        switch self {
        case .back:
            return .back
        case .front:
            return .front
        case .unspecified:
            return .unspecified
        }
    }
}

extension CameraFocusMode {
    init(deviceMode: AVCaptureDevice.FocusMode) {
        switch deviceMode {
        case .locked:
            self = .locked
        case .autoFocus:
            self = .autoFocus
        case .continuousAutoFocus:
            self = .continuousAutoFocus
        @unknown default:
            self = .continuousAutoFocus
        }
    }

    var deviceMode: AVCaptureDevice.FocusMode {
        switch self {
        case .locked:
            return .locked
        case .autoFocus:
            return .autoFocus
        case .continuousAutoFocus:
            return .continuousAutoFocus
        }
    }
}

extension CameraWhiteBalanceMode {
    init(deviceMode: AVCaptureDevice.WhiteBalanceMode) {
        switch deviceMode {
        case .locked:
            self = .locked
        case .autoWhiteBalance:
            self = .autoWhiteBalance
        case .continuousAutoWhiteBalance:
            self = .continuousAutoWhiteBalance
        @unknown default:
            self = .continuousAutoWhiteBalance
        }
    }

    var deviceMode: AVCaptureDevice.WhiteBalanceMode {
        switch self {
        case .locked:
            return .locked
        case .autoWhiteBalance:
            return .autoWhiteBalance
        case .continuousAutoWhiteBalance:
            return .continuousAutoWhiteBalance
        }
    }
}

extension CameraStabilizationMode {
    init(deviceMode: AVCaptureVideoStabilizationMode) {
        // 按 rawValue 比较：`AVCaptureVideoStabilizationMode.auto` 需要 iOS 13，
        // 在 iOS 10 部署目标下不能直接引用该枚举成员（会触发可用性错误）。
        switch deviceMode.rawValue {
        case AVCaptureVideoStabilizationMode.off.rawValue:
            self = .off
        case AVCaptureVideoStabilizationMode.cinematic.rawValue:
            self = .cinematic
        default:
            // standard 以及 iOS 13 的 auto 都按标准防抖展示。
            self = .standard
        }
    }

    /// `auto` 需要 iOS 13，本工程不下发该模式（`#available(iOS 13.0, *)` 判定在调用方）。
    var deviceMode: AVCaptureVideoStabilizationMode {
        switch self {
        case .off:
            return .off
        case .standard:
            return .standard
        case .cinematic:
            return .cinematic
        }
    }
}
