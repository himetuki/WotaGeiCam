import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// B2 录制的**固定接入接口**：把相机能力（`CameraAbilitySnapshot`）与当前参数落点
/// （`CameraParameterState`）收敛成一份不可变的录制参数快照。
///
/// 边界：
/// - 本文件**不实现录制**：不打开 `AVAssetWriter`、不添加 `AVAssetWriterInput`、不动音轨、
///   不引入任何隐式媒体库行为（不写相册）；
/// - 不依赖 AVFoundation，只吃纯数据，便于 B2 在自己的队列上自由编排；
/// - 能力/状态不齐时返回 `.unavailable` 与原因，绝不给出半份参数（宁可明确失败也不要静默降级）。

/// 相机侧交接摘要：B2 展示与校验录制档位所需的最小信息集。
struct RecordingCameraHandoff {
    let resolutionTiers: [CameraResolution]
    let frameRateTiers: [Double]
    let frameRateIsExact: Bool
    let shutterTiers: [CameraShutterSpeed]
    /// 1/24、1/25 里在该设备上**可达成**的档位（分母帧率被 format 表承载）。
    let lowSpeedShutterAvailable: [CameraShutterSpeed]
    /// 1/24、1/25 里**不可达成**的档位（要显式列出，不做静默隐藏）。
    let lowSpeedShutterUnavailable: [CameraShutterSpeed]
    let audioSampleRateTiers: [Int]
    let videoBitRateTiers: [Int]
    let hasTorch: Bool
    let notes: [String]

    /// 从能力快照 + 当前状态构造摘要。
    static func make(ability: CameraAbilitySnapshot, state: CameraParameterState) -> RecordingCameraHandoff {
        let availability = CameraShutterGate.lowSpeedAvailability(in: ability)
        var available: [CameraShutterSpeed] = []
        var unavailable: [CameraShutterSpeed] = []
        for target in CameraShutterSpeed.lowSpeedTiers {
            if availability[target] == true {
                available.append(target)
            } else {
                unavailable.append(target)
            }
        }

        var notes = ability.notes
        if !unavailable.isEmpty {
            notes.append("慢门不可用档：\(unavailable.map { $0.displayName }.joined(separator: "、"))。")
        }
        if !state.frameRateExact {
            notes.append("当前帧率不是固定档，录制期帧率可能漂移。")
        }

        return RecordingCameraHandoff(
            resolutionTiers: CameraFormatNegotiation.availableResolutions(in: ability),
            frameRateTiers: CameraFormatNegotiation.supportedFrameRates(in: ability),
            frameRateIsExact: state.frameRateExact,
            shutterTiers: CameraShutterSpeed.standardTiers(),
            lowSpeedShutterAvailable: available,
            lowSpeedShutterUnavailable: unavailable,
            audioSampleRateTiers: RecordingTiers.audioSampleRates,
            videoBitRateTiers: RecordingTiers.videoBitRates,
            hasTorch: ability.hasTorch,
            notes: notes
        )
    }
}

/// 录制参数装配入口。
struct RecordingConfigurationBridge {

    /// B2 侧的用户意图（全是纯数据）。
    struct Request {
        /// ≤ 0 表示未自设，走三段式码率策略。
        var userBitRate: Int
        var codec: RecordingVideoCodec
        var sampleRate: Int
        var audioEnabled: Bool
        var mirrored: Bool
        var usesGPU: Bool
        /// ≤ 0 表示与容器帧率一致（非延时摄影）。
        var captureRate: Double
        /// 设备 sensor 朝向（由 B2 从 `AVCaptureConnection` 的旋转角取得）。
        var sensorOrientation: Int
        /// 当前显示旋转角（0/90/180/270）。
        var displayOrientation: Int

        init(
            userBitRate: Int = 0,
            codec: RecordingVideoCodec = .h264,
            sampleRate: Int = RecordingTiers.defaultAudioSampleRate,
            audioEnabled: Bool = true,
            mirrored: Bool = false,
            usesGPU: Bool = true,
            captureRate: Double = 0,
            sensorOrientation: Int = 0,
            displayOrientation: Int = 0
        ) {
            self.userBitRate = userBitRate
            self.codec = codec
            self.sampleRate = sampleRate
            self.audioEnabled = audioEnabled
            self.mirrored = mirrored
            self.usesGPU = usesGPU
            self.captureRate = captureRate
            self.sensorOrientation = sensorOrientation
            self.displayOrientation = displayOrientation
        }
    }

    /// 装配结果：要么给出完整参数，要么给出**明确原因**。
    enum BuildResult {
        case ready(RecordingConfiguration)
        case unavailable(String)

        var configuration: RecordingConfiguration? {
            if case let .ready(configuration) = self {
                return configuration
            }
            return nil
        }

        var reason: String? {
            if case let .unavailable(reason) = self {
                return reason
            }
            return nil
        }
    }

    /// 用设备能力 + 当前状态装配录制参数。
    ///
    /// 分辨率与帧率**必须**来自设备档位表：不在表里就返回 `.unavailable`，不擅自吸附到别的档
    /// （吸附会让用户选 4K 却录出 1080p，属于静默降级）。帧率与采样率若只是「未设置」，
    /// 则取最近的产品档并在 notes 里写明。
    static func build(
        ability: CameraAbilitySnapshot,
        state: CameraParameterState,
        request: Request
    ) -> BuildResult {
        guard ability.isAvailable else {
            return .unavailable("相机未就绪（无视频输入），无法装配录制参数。")
        }

        let resolutionTiers = CameraFormatNegotiation.availableResolutions(in: ability)
        guard !resolutionTiers.isEmpty else {
            return .unavailable("设备未上报可用分辨率档位，无法装配录制参数。")
        }

        guard let resolution = state.resolution ?? resolutionTiers.first, resolution.pixelCount > 0 else {
            return .unavailable("当前分辨率不可读，无法装配录制参数。")
        }
        guard resolutionTiers.contains(resolution) else {
            return .unavailable("当前分辨率 \(resolution.displayName) 不在设备档位表里，拒绝装配。")
        }

        let frameRateTiers = CameraFormatNegotiation.supportedFrameRates(in: ability)
        guard !frameRateTiers.isEmpty else {
            return .unavailable("设备未上报可用帧率档位，无法装配录制参数。")
        }

        var notes: [String] = []
        var frameRate = state.frameRate ?? frameRateTiers[0]
        if !frameRateTiers.contains(frameRate) {
            let nearest = nearestFrameRate(to: frameRate, in: frameRateTiers)
            notes.append(String(
                format: "当前帧率 %.0f 不在档位表里，录制按最近档 %.0f 装配。",
                frameRate,
                nearest
            ))
            frameRate = nearest
        }

        var sampleRate = request.sampleRate
        if !RecordingTiers.audioSampleRates.contains(sampleRate) {
            let nearest = nearestSampleRate(to: sampleRate)
            notes.append("采样率 \(sampleRate) 不在产品档位里，改用最近档 \(nearest)。")
            sampleRate = nearest
        }
        if request.audioEnabled {
            notes.append("采样率是否被设备接受需 B2 用音频探测（`isSampleRateSupported` 等价判定）确认。")
        }

        if request.codec.isHEVC {
            notes.append("HEVC 编码可用性由 B2 用编码器能力探测决定，本接口不假设支持。")
        }

        let captureRate = request.captureRate > 0 ? request.captureRate : frameRate
        if captureRate > 0 && Int(captureRate) != Int(frameRate) {
            notes.append("采集帧率与容器帧率不一致，按延时摄影语义处理。")
        }

        let orientationHint = RecordingOrientation.hint(
            sensorOrientation: request.sensorOrientation,
            deviceDegrees: request.displayOrientation,
            isFrontCamera: ability.position == .front
        )

        let configuration = RecordingConfiguration(
            resolution: resolution,
            frameRate: frameRate,
            captureRate: captureRate,
            userBitRate: request.userBitRate,
            codec: request.codec,
            sampleRate: sampleRate,
            channels: 2,
            audioBitRate: RecordingBitratePolicy.audioBitRate,
            audioEnabled: request.audioEnabled,
            orientationHint: orientationHint,
            mirrored: request.mirrored,
            usesGPU: request.usesGPU,
            frameRateIsNonIntegerStandard: !state.frameRateExact,
            notes: notes
        )

        return .ready(configuration)
    }

    /// 装配后的自检：把「参数 vs 设备能力」对不上的地方逐条列出（不修改任何值）。
    static func validate(
        configuration: RecordingConfiguration,
        ability: CameraAbilitySnapshot
    ) -> [String] {
        var problems: [String] = []

        if !CameraFormatNegotiation.availableResolutions(in: ability).contains(configuration.resolution) {
            problems.append("分辨率 \(configuration.resolution.displayName) 不在设备档位表里。")
        }
        if !CameraFormatNegotiation.isFrameRate(configuration.frameRate, supportedIn: ability) {
            problems.append(String(format: "帧率 %.0f 不被任何 format 承载。", configuration.frameRate))
        }
        if configuration.effectiveBitRate < RecordingBitratePolicy.minimumBitRate {
            problems.append("落地码率低于编码器可用下限 1 Mbps。")
        }
        if configuration.frameRate >= RecordingTiers.highSpeedFrameRateThreshold
            && !ability.formats.contains(where: { $0.supports(frameRate: configuration.frameRate) && $0.isHighSpeedCapable }) {
            problems.append("目标帧率进入高速档，但设备没有匹配的高速 format。")
        }
        if configuration.isAudioUsable && configuration.sampleRate <= 0 {
            problems.append("音频通路开启但采样率非法。")
        }
        if configuration.orientationHint < 0 || configuration.orientationHint >= 360 {
            problems.append("容器朝向角不在 0–359 内。")
        }

        return problems
    }

    /// 「还能拍多久」（MB → 分钟），面板与录制前校验共用。
    static func estimatedMinutes(freeMegabytes: Int64, configuration: RecordingConfiguration) -> Int64 {
        return RecordingStorageEstimate.minutes(
            freeMegabytes: freeMegabytes,
            bitRate: configuration.effectiveBitRate
        )
    }

    /// 录制前空间门槛（与 Android `MIN_FREE_MB` 同源）。
    static func hasEnoughFreeSpace(megabytes: Int64) -> Bool {
        return megabytes >= RecordingTiers.minimumFreeMegabytes
    }

    // MARK: - 内部

    private static func nearestFrameRate(to frameRate: Double, in tiers: [Double]) -> Double {
        var best = tiers[0]
        var distance = Double.greatestFiniteMagnitude
        for tier in tiers {
            let current = Swift.abs(tier - frameRate)
            if current < distance {
                distance = current
                best = tier
            }
        }
        return best
    }

    private static func nearestSampleRate(to sampleRate: Int) -> Int {
        // 用共享的 Int64 距离实现，armv7 上也不会因 `Int32.min` 溢出。
        return WotaTiers.clampSampleRate(sampleRate)
    }
}
