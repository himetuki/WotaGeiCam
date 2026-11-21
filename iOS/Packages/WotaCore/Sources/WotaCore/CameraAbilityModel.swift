import Foundation

/// 相机能力与参数状态的**纯逻辑模型**（不依赖 AVFoundation / UIKit）。
///
/// 平台层（`WotaGeiCam/Camera/CameraCapabilityProbe.swift`）把 `AVCaptureDevice` /
/// `AVCaptureDevice.Format` 的运行时能力翻译成这里的纯数据结构，UI 再据此显示可用档位与降级说明。
///
/// 设计口径（与 Android `core/CameraAbility.kt`、`core/WotaParams.kt` 对称但**不逐行翻译**）：
/// - 所有范围/档位来自设备运行时能力；本文件只表达结构，不写任何机型数值；
/// - 能力缺失用 `isAvailable == false` 的区间表达，**不伪造**范围；
/// - 每个参数可单独表达"支持 / 不支持 / 需降级"，UI 按状态灰显而不是静默失败。

/// 摄像头朝向（对应 `AVCaptureDevice.Position`，平台层负责映射）。
public enum CameraDevicePosition: String, Equatable, Sendable {
    case unspecified
    case back
    case front
}

/// 分辨率（宽 × 高，像素）。
public struct CameraResolution: Hashable, Sendable {
    public let width: Int
    public let height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }

    public var pixelCount: Int {
        return width * height
    }

    /// 宽高比的最简标签，例如 `16:9`；无法化简时给出原始比值。
    public var aspectLabel: String {
        let divisor = CameraResolution.greatestCommonDivisor(width, height)
        guard divisor > 0 else { return "\(width):\(height)" }
        return "\(width / divisor):\(height / divisor)"
    }

    /// 形如 `1920×1080`。
    public var displayName: String {
        return "\(width)×\(height)"
    }

    private static func greatestCommonDivisor(_ a: Int, _ b: Int) -> Int {
        var x = a < 0 ? -a : a
        var y = b < 0 ? -b : b
        while y != 0 {
            let remainder = x % y
            x = y
            y = remainder
        }
        return x
    }
}

/// 帧率区间（对应 `AVCaptureFormat.videoSupportedFrameRateRanges` 的一项）。
public struct CameraFrameRateRange: Equatable, Sendable {
    public let minimum: Double
    public let maximum: Double

    public init(minimum: Double, maximum: Double) {
        self.minimum = minimum
        self.maximum = maximum
    }

    /// 固定档（`[f, f]`）——固定档避免自动曝光在区间内漂移导致帧率抖动。
    public var isFixed: Bool {
        return maximum - minimum <= 0.01
    }

    public func contains(_ frameRate: Double) -> Bool {
        return frameRate >= minimum - 0.01 && frameRate <= maximum + 0.01
    }

    public var displayName: String {
        if isFixed {
            return String(format: "%.0f fps", maximum)
        }
        return String(format: "%.0f–%.0f fps", minimum, maximum)
    }
}

/// 数值区间：ISO、曝光时长、镜头位置、变焦、白平衡增益都用它。
///
/// `maximum > minimum` 才视为设备声明了可用区间；`minimum == maximum` 表示无该能力
/// （例如定焦前摄的镜头位置域、无变焦机型的变焦域）。
public struct CameraNumericRange: Equatable, Sendable {
    public let minimum: Double
    public let maximum: Double

    public init(minimum: Double, maximum: Double) {
        self.minimum = minimum
        self.maximum = maximum
    }

    public var isAvailable: Bool {
        return maximum > minimum
    }

    /// 把任意值夹到合法区间；区间不可用时返回下界。
    public func clamp(_ value: Double) -> Double {
        guard isAvailable else { return minimum }
        return Swift.min(Swift.max(value, minimum), maximum)
    }

    /// 归一化位置（0...1）→ 实际值。
    public func value(forNormalizedPosition position: Double) -> Double {
        guard isAvailable else { return minimum }
        let clamped = Swift.min(Swift.max(position, 0), 1)
        return minimum + (maximum - minimum) * clamped
    }

    /// 实际值 → 归一化位置（0...1）。
    public func normalizedPosition(forValue value: Double) -> Double {
        guard isAvailable else { return 0 }
        return (clamp(value) - minimum) / (maximum - minimum)
    }

    public static let unavailable = CameraNumericRange(minimum: 0, maximum: 0)
}

/// 对焦模式（对应 `AVCaptureDevice.FocusMode`）。
public enum CameraFocusMode: String, CaseIterable, Sendable {
    case locked
    case autoFocus
    case continuousAutoFocus

    public var displayName: String {
        switch self {
        case .locked:
            return "手动 / 锁定"
        case .autoFocus:
            return "单次对焦"
        case .continuousAutoFocus:
            return "连续对焦"
        }
    }
}

/// 白平衡模式（对应 `AVCaptureDevice.WhiteBalanceMode`）。
public enum CameraWhiteBalanceMode: String, CaseIterable, Sendable {
    case locked
    case autoWhiteBalance
    case continuousAutoWhiteBalance

    public var displayName: String {
        switch self {
        case .locked:
            return "手动 / 锁定"
        case .autoWhiteBalance:
            return "自动白平衡"
        case .continuousAutoWhiteBalance:
            return "连续自动白平衡"
        }
    }
}

/// 视频防抖模式（对应 `AVCaptureVideoStabilizationMode`；`auto` 需 iOS 13，iOS 10 基线不启用）。
public enum CameraStabilizationMode: String, CaseIterable, Sendable {
    case off
    case standard
    case cinematic

    public var displayName: String {
        switch self {
        case .off:
            return "关闭"
        case .standard:
            return "电子防抖（标准）"
        case .cinematic:
            return "影院级防抖"
        }
    }
}

/// 单个 format 的描述：分辨率 + 支持帧率区间 + 支持防抖模式。
public struct CameraFormatDescriptor: Equatable, Sendable {
    public let resolution: CameraResolution
    public let frameRateRanges: [CameraFrameRateRange]
    public let stabilizationModes: [CameraStabilizationMode]

    public init(
        resolution: CameraResolution,
        frameRateRanges: [CameraFrameRateRange],
        stabilizationModes: [CameraStabilizationMode] = []
    ) {
        self.resolution = resolution
        self.frameRateRanges = frameRateRanges
        self.stabilizationModes = stabilizationModes
    }

    public var minimumFrameRate: Double {
        var value = Double.greatestFiniteMagnitude
        for range in frameRateRanges {
            value = Swift.min(value, range.minimum)
        }
        return value == Double.greatestFiniteMagnitude ? 0 : value
    }

    public var maximumFrameRate: Double {
        var value: Double = 0
        for range in frameRateRanges {
            value = Swift.max(value, range.maximum)
        }
        return value
    }

    /// >60fps 是 WOTA 场景的分水岭（高速档对会话/防抖/分析行为不同）。
    public var isHighSpeedCapable: Bool {
        return maximumFrameRate > RecordingTiers.highSpeedFrameRateThreshold
    }

    /// 档位是否可用：抽象格式（0×0、无帧率区间）不能进分辨率/帧率档位表。
    ///
    /// **下标对齐约定**：平台层构造 `formats` 时不得过滤任何一项（含不可用档），
    /// 这样 `formats[i]` 才能与 `AVCaptureDevice.formats[i]` 一一对应，回查真实 format 才不会错位。
    public var isUsable: Bool {
        return resolution.pixelCount > 0 && !frameRateRanges.isEmpty
    }

    public func supports(frameRate: Double) -> Bool {
        for range in frameRateRanges where range.contains(frameRate) {
            return true
        }
        return false
    }
}

/// 单颗摄像头的完整能力快照。
///
/// 由平台层在 `sessionQueue` 上从 `AVCaptureDevice` 读取并构造；`isAvailable == false`
/// 时所有区间为空、`formats` 为空，UI 据此显示"相机未就绪"而不是伪造能力。
public struct CameraAbilitySnapshot: Equatable, Sendable {
    public let isAvailable: Bool
    public let position: CameraDevicePosition
    public let deviceName: String
    public let deviceModel: String
    public let activeResolution: CameraResolution
    public let formats: [CameraFormatDescriptor]
    public let activeFrameRateRanges: [CameraFrameRateRange]
    public let isoRange: CameraNumericRange
    /// 设备可设置的 sensor 曝光时间域，单位：**纳秒**（`AVCaptureDevice.Format.min/maxExposureDuration` 秒域在平台层一次转换）。
    public let exposureDurationRange: CameraNumericRange
    public let exposureTargetBiasRange: ExposureBiasRange
    public let focusModes: [CameraFocusMode]
    public let lensPositionRange: CameraNumericRange
    public let whiteBalanceModes: [CameraWhiteBalanceMode]
    public let whiteBalanceGainRange: CameraNumericRange
    public let zoomRange: CameraNumericRange
    public let hasTorch: Bool
    public let torchAvailable: Bool
    public let stabilizationModes: [CameraStabilizationMode]
    public let notes: [String]

    public init(
        isAvailable: Bool,
        position: CameraDevicePosition,
        deviceName: String,
        deviceModel: String,
        activeResolution: CameraResolution,
        formats: [CameraFormatDescriptor],
        activeFrameRateRanges: [CameraFrameRateRange],
        isoRange: CameraNumericRange,
        exposureDurationRange: CameraNumericRange,
        exposureTargetBiasRange: ExposureBiasRange,
        focusModes: [CameraFocusMode],
        lensPositionRange: CameraNumericRange,
        whiteBalanceModes: [CameraWhiteBalanceMode],
        whiteBalanceGainRange: CameraNumericRange,
        zoomRange: CameraNumericRange,
        hasTorch: Bool,
        torchAvailable: Bool,
        stabilizationModes: [CameraStabilizationMode],
        notes: [String]
    ) {
        self.isAvailable = isAvailable
        self.position = position
        self.deviceName = deviceName
        self.deviceModel = deviceModel
        self.activeResolution = activeResolution
        self.formats = formats
        self.activeFrameRateRanges = activeFrameRateRanges
        self.isoRange = isoRange
        self.exposureDurationRange = exposureDurationRange
        self.exposureTargetBiasRange = exposureTargetBiasRange
        self.focusModes = focusModes
        self.lensPositionRange = lensPositionRange
        self.whiteBalanceModes = whiteBalanceModes
        self.whiteBalanceGainRange = whiteBalanceGainRange
        self.zoomRange = zoomRange
        self.hasTorch = hasTorch
        self.torchAvailable = torchAvailable
        self.stabilizationModes = stabilizationModes
        self.notes = notes
    }

    /// 去重并按像素降序的分辨率清单（UI 分辨率档取自它）；不可用档被剔除。
    public var supportedResolutions: [CameraResolution] {
        var seen = Set<CameraResolution>()
        var result: [CameraResolution] = []
        for format in formats where format.isUsable && !seen.contains(format.resolution) {
            seen.insert(format.resolution)
            result.append(format.resolution)
        }
        return result.sorted { lhs, rhs in
            if lhs.pixelCount != rhs.pixelCount {
                return lhs.pixelCount > rhs.pixelCount
            }
            return lhs.width > rhs.width
        }
    }

    public var maximumFrameRate: Double {
        var value: Double = 0
        for range in activeFrameRateRanges {
            value = Swift.max(value, range.maximum)
        }
        return value
    }

    /// 有可改的对焦（含手动屈光度域）才有对焦入口。
    public var hasAdjustableFocus: Bool {
        for mode in focusModes where mode != .locked {
            return true
        }
        return lensPositionRange.isAvailable
    }

    /// 手动曝光（ISO + 曝光时间）是否可用——**快门可用 ≠ sensor 曝光时间可设**，两者分开表达。
    public var hasManualISO: Bool {
        return isoRange.isAvailable
    }

    public var hasManualExposureDuration: Bool {
        return exposureDurationRange.isAvailable
    }

    /// 手动白平衡增益（色温/色调）是否可用（需 locked 白平衡模式 + 增益域）。
    public var hasManualWhiteBalance: Bool {
        var locked = false
        for mode in whiteBalanceModes where mode == .locked {
            locked = true
        }
        return locked && whiteBalanceGainRange.isAvailable
    }

    public var hasZoom: Bool {
        return zoomRange.isAvailable && zoomRange.maximum > 1
    }

    /// 相机未就绪时返回的统一空快照（UI 按状态显示禁用，不静默）。
    public static let unavailable = CameraAbilitySnapshot(
        isAvailable: false,
        position: .unspecified,
        deviceName: "—",
        deviceModel: "—",
        activeResolution: CameraResolution(width: 0, height: 0),
        formats: [],
        activeFrameRateRanges: [],
        isoRange: .unavailable,
        exposureDurationRange: .unavailable,
        exposureTargetBiasRange: ExposureBiasRange(minimum: 0, maximum: 0),
        focusModes: [],
        lensPositionRange: .unavailable,
        whiteBalanceModes: [],
        whiteBalanceGainRange: .unavailable,
        zoomRange: .unavailable,
        hasTorch: false,
        torchAvailable: false,
        stabilizationModes: [],
        notes: ["尚未建立相机输入，参数在预览启动后可用。"]
    )
}

/// 当前参数状态快照（值 + 能力域 + 降级说明），UI 直接消费。
///
/// 与 `CameraAbilitySnapshot` 分开：能力是"设备能做哪些档"，状态是"当前落在哪一档"。
/// 相机无输出时两者都返回 `.unavailable` 形态，接口仍有确定返回值。
public struct CameraParameterState: Equatable, Sendable {
    public var resolution: CameraResolution?
    public var resolutionTiers: [CameraResolution]
    public var frameRate: Double?
    public var frameRateTiers: [Double]
    public var frameRateExact: Bool
    public var iso: Double?
    public var isoRange: CameraNumericRange
    public var exposureDurationSeconds: Double?
    /// 设备可设置的 sensor 曝光时间域，单位：**纳秒**（与 `exposureDurationSeconds` 的秒域区分）。
    public var exposureDurationRange: CameraNumericRange
    public var exposureTargetBias: Float
    public var exposureTargetBiasRange: ExposureBiasRange
    public var focusMode: CameraFocusMode?
    public var focusModes: [CameraFocusMode]
    public var lensPosition: Double?
    public var lensPositionRange: CameraNumericRange
    public var whiteBalanceMode: CameraWhiteBalanceMode?
    public var whiteBalanceModes: [CameraWhiteBalanceMode]
    public var whiteBalanceKelvin: Int?
    public var whiteBalanceTint: Double?
    public var whiteBalanceGainRange: CameraNumericRange
    public var zoomFactor: Double?
    public var zoomRange: CameraNumericRange
    public var torchEnabled: Bool
    public var stabilizationMode: CameraStabilizationMode?
    public var stabilizationModes: [CameraStabilizationMode]
    public var notes: [String]

    public init(
        resolution: CameraResolution?,
        resolutionTiers: [CameraResolution],
        frameRate: Double?,
        frameRateTiers: [Double],
        frameRateExact: Bool,
        iso: Double?,
        isoRange: CameraNumericRange,
        exposureDurationSeconds: Double?,
        exposureDurationRange: CameraNumericRange,
        exposureTargetBias: Float,
        exposureTargetBiasRange: ExposureBiasRange,
        focusMode: CameraFocusMode?,
        focusModes: [CameraFocusMode],
        lensPosition: Double?,
        lensPositionRange: CameraNumericRange,
        whiteBalanceMode: CameraWhiteBalanceMode?,
        whiteBalanceModes: [CameraWhiteBalanceMode],
        whiteBalanceKelvin: Int?,
        whiteBalanceTint: Double?,
        whiteBalanceGainRange: CameraNumericRange,
        zoomFactor: Double?,
        zoomRange: CameraNumericRange,
        torchEnabled: Bool,
        stabilizationMode: CameraStabilizationMode?,
        stabilizationModes: [CameraStabilizationMode],
        notes: [String]
    ) {
        self.resolution = resolution
        self.resolutionTiers = resolutionTiers
        self.frameRate = frameRate
        self.frameRateTiers = frameRateTiers
        self.frameRateExact = frameRateExact
        self.iso = iso
        self.isoRange = isoRange
        self.exposureDurationSeconds = exposureDurationSeconds
        self.exposureDurationRange = exposureDurationRange
        self.exposureTargetBias = exposureTargetBias
        self.exposureTargetBiasRange = exposureTargetBiasRange
        self.focusMode = focusMode
        self.focusModes = focusModes
        self.lensPosition = lensPosition
        self.lensPositionRange = lensPositionRange
        self.whiteBalanceMode = whiteBalanceMode
        self.whiteBalanceModes = whiteBalanceModes
        self.whiteBalanceKelvin = whiteBalanceKelvin
        self.whiteBalanceTint = whiteBalanceTint
        self.whiteBalanceGainRange = whiteBalanceGainRange
        self.zoomFactor = zoomFactor
        self.zoomRange = zoomRange
        self.torchEnabled = torchEnabled
        self.stabilizationMode = stabilizationMode
        self.stabilizationModes = stabilizationModes
        self.notes = notes
    }

    public static let unavailable = CameraParameterState(
        resolution: nil,
        resolutionTiers: [],
        frameRate: nil,
        frameRateTiers: [],
        frameRateExact: false,
        iso: nil,
        isoRange: .unavailable,
        exposureDurationSeconds: nil,
        exposureDurationRange: .unavailable,
        exposureTargetBias: 0,
        exposureTargetBiasRange: ExposureBiasRange(minimum: 0, maximum: 0),
        focusMode: nil,
        focusModes: [],
        lensPosition: nil,
        lensPositionRange: .unavailable,
        whiteBalanceMode: nil,
        whiteBalanceModes: [],
        whiteBalanceKelvin: nil,
        whiteBalanceTint: nil,
        whiteBalanceGainRange: .unavailable,
        zoomFactor: nil,
        zoomRange: .unavailable,
        torchEnabled: false,
        stabilizationMode: nil,
        stabilizationModes: [],
        notes: []
    )
}

/// 前后置切换结果。
///
/// 切换是「发现设备 → 同一配置事务内先移除旧输入 → `canAddInput` 新输入 → 原子替换」，
/// 新输入失败时在同一事务内恢复旧输入；旧输入也恢复不了则置 `requiresSessionRebuild`，
/// 由 `retry()` 重建输入，不能出现"切坏了但没人知道"。
public struct CameraSwitchOutcome: Equatable, Sendable {
    public let requestedPosition: CameraDevicePosition
    public let appliedPosition: CameraDevicePosition?
    public let appliedDeviceName: String?
    /// 是否真的替换了输入（已是目标朝向时为 false，不算失败）。
    public let didChangeInput: Bool
    /// 失败时旧输入是否仍然有效。
    public let keptPreviousInput: Bool
    /// 失败且旧输入无法恢复时为 true：相机会话已进入可重建状态，调用方应提示重试（`retry()` 会重建输入）。
    public let requiresSessionRebuild: Bool
    public let errorMessage: String?

    public init(
        requestedPosition: CameraDevicePosition,
        appliedPosition: CameraDevicePosition?,
        appliedDeviceName: String?,
        didChangeInput: Bool,
        keptPreviousInput: Bool,
        errorMessage: String?,
        requiresSessionRebuild: Bool = false
    ) {
        self.requestedPosition = requestedPosition
        self.appliedPosition = appliedPosition
        self.appliedDeviceName = appliedDeviceName
        self.didChangeInput = didChangeInput
        self.keptPreviousInput = keptPreviousInput
        self.requiresSessionRebuild = requiresSessionRebuild
        self.errorMessage = errorMessage
    }

    public var isSuccess: Bool {
        return errorMessage == nil && appliedPosition != nil
    }

    public var summary: String {
        let name = appliedDeviceName ?? "—"
        if let errorMessage = errorMessage {
            let previousText: String
            if keptPreviousInput {
                previousText = "已保留原摄像头"
            } else if requiresSessionRebuild {
                previousText = "原摄像头已失效，需重试重建会话"
            } else {
                previousText = "原摄像头已失效"
            }
            return "切换失败：\(errorMessage)（\(previousText)）"
        }
        if !didChangeInput {
            return "当前已是\(name)。"
        }
        return "已切换到\(name)。"
    }
}
