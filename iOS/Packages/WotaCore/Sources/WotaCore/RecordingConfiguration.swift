import Foundation

/// 录制参数档位与编码策略的**纯逻辑**（不依赖 AVFoundation）。
///
/// 与 Android `core/Constants.kt` 的 `WotaTiers`、`record/BitratePolicy.kt`、`record/RecordProfile.kt`
/// 对称：档位数值来自**产品需求**（不是机型能力），设备实际能落哪一档由 format 协商决定。
///
/// 命名约定与 Android 对齐：码率档 5/10/20/35/50 Mbps、采样率 32k/44.1k/48k、帧率 24/25/30/50/60/120/240、
/// 快门分母 24/25/30/50/60/120/240、单段上限 3.5GiB、录像前余量 200MB。

// MARK: - 编码器

/// 视频编码器（对应 Android `RecordProfile.CODEC_H264` / `CODEC_HEVC`）。
public enum RecordingVideoCodec: Int, Equatable, Sendable {
    case h264 = 0
    case hevc = 1

    public var displayName: String {
        switch self {
        case .h264:
            return "H.264"
        case .hevc:
            return "HEVC"
        }
    }

    public var isHEVC: Bool {
        return self == .hevc
    }
}

// MARK: - 档位表

/// 产品档位常量（与 Android `WotaTiers` 同源，**不是**机型能力表）。
public enum RecordingTiers {

    /// 视频码率档（bps）：5 / 10 / 20 / 35 / 50 Mbps。
    public static let videoBitRates: [Int] = [5_000_000, 10_000_000, 20_000_000, 35_000_000, 50_000_000]

    /// 音频采样率档（Hz）；默认 48k。
    public static let audioSampleRates: [Int] = [32_000, 44_100, 48_000]
    public static let defaultAudioSampleRate: Int = 48_000

    /// 帧率档（fps）。
    public static let frameRates: [Double] = [24, 25, 30, 50, 60, 120, 240]

    /// 必须支持的帧率：1/24、1/25 慢门场景。
    public static let requiredFrameRates: [Double] = [24, 25]

    /// 快门分母档（分母即秒数）。
    public static let shutterDenominators: [Int] = [24, 25, 30, 50, 60, 120, 240]

    /// 强制快门档分母（产品必须的 1/24、1/25）。
    public static let requiredShutterDenominators: Set<Int> = [24, 25]

    /// 长曝光防手抖钳制：1/10 s。
    public static let shutterCapNanoseconds: Int64 = 100_000_000

    /// 纳秒/秒。
    public static let nanosecondsPerSecond: Int64 = 1_000_000_000

    /// >60fps 的分水岭：会话/防抖/分析流行为在此换档。
    public static let highSpeedFrameRateThreshold: Double = 60

    /// 单段文件上限：3.5GiB（FAT32 安全上限）。
    public static let maximumFileBytes: Int64 = 3_758_096_384

    /// 录像前可用空间门槛（MB）。
    public static let minimumFreeMegabytes: Int64 = 200

    /// 单段低于该时长按废片处理。
    public static let minimumKeepMilliseconds: Int = 1_000

    /// 色温滑杆域（产品域，非设备能力）。
    public static let kelvinRange = CameraNumericRange(minimum: 2_000, maximum: 10_000)

    /// 色调滑杆域（产品域，非设备能力）。
    public static let tintRange = CameraNumericRange(minimum: -50, maximum: 50)

    /// 档位值 → 纳秒（整除，与 Android `NS_PER_SECOND / denom` 完全一致：1/24 = 41_666_666）。
    public static func shutterNanoseconds(denominator: Int) -> Int64 {
        guard denominator > 0 else { return 0 }
        return nanosecondsPerSecond / Int64(denominator)
    }

    /// 反查：纳秒值是否命中产品快门档。
    public static func shutterDenominator(nanoseconds: Int64) -> Int? {
        for denominator in shutterDenominators where shutterNanoseconds(denominator: denominator) == nanoseconds {
            return denominator
        }
        return nil
    }
}

// MARK: - 三段式码率策略

/// 三段式码率：**用户自设 > 自动表 > 公式兜底**，HEVC 再整体 ×0.9。
///
/// 与 Android `BitratePolicy` 逐条对齐（含自动表分界、fps≥60 翻倍、兜底钳制）。
public enum RecordingBitratePolicy {

    /// 音频码率固定 128 kbps。
    public static let audioBitRate: Int = 128_000

    /// 自动表：720P→8M、1080P→16M、4K→32M。
    public static let automatic720p: Int = 8_000_000
    public static let automatic1080p: Int = 16_000_000
    public static let automatic4K: Int = 32_000_000

    /// fps ≥ 60 时自动表翻倍。
    public static let doubleFrameRate: Double = 60

    /// 兜底公式 `w × h × 30 × 0.2`。
    public static let fallbackFrameRate: Double = 30
    public static let fallbackBitsPerPixel: Double = 0.2

    /// HEVC 同画质省 10%。
    public static let hevcFactor: Double = 0.9

    /// 兜底下限：低于 1 Mbps 编码画质不可用。
    public static let minimumBitRate: Int = 1_000_000

    /// 自动表命中值；返回 0 表示分辨率超出表上界（改走 [fallback])。
    public static func automaticTable(width: Int, height: Int, frameRate: Double) -> Int {
        let pixels = Int64(width) * Int64(height)
        let p720 = Int64(1_280 * 720)
        let p1080 = Int64(1_920 * 1_080)
        let band1080Upper = Int64(5_000_000)
        let band4KUpper = Int64(9_000_000)

        var base: Int
        if pixels <= (p720 + p1080) / 2 {
            base = automatic720p
        } else if pixels <= band1080Upper {
            base = automatic1080p
        } else if pixels <= band4KUpper {
            base = automatic4K
        } else {
            base = 0
        }
        guard base != 0 else { return 0 }
        return frameRate >= doubleFrameRate ? base * 2 : base
    }

    /// 兜底公式，并用 [minimumBitRate] 钳住极小分辨率。
    public static func fallback(width: Int, height: Int) -> Int {
        let raw = Double(width) * Double(height) * fallbackFrameRate * fallbackBitsPerPixel
        let clamped = Swift.max(raw, Double(minimumBitRate))
        return clamped > Double(Int.max) ? Int.max : Int(clamped)
    }

    /// 产品最高档（自动/兜底两段的上界；用户自设档不钳）。
    public static var maximumTier: Int {
        return RecordingTiers.videoBitRates.max() ?? Int.max
    }

    /// 三段式取值；`userBitRate <= 0` 视为未自设。
    public static func resolve(
        userBitRate: Int,
        width: Int,
        height: Int,
        frameRate: Double,
        codec: RecordingVideoCodec
    ) -> Int {
        let fromUser = userBitRate > 0
        let automatic = automaticTable(width: width, height: height, frameRate: frameRate)
        let raw: Int
        if fromUser {
            raw = userBitRate
        } else if automatic > 0 {
            raw = automatic
        } else {
            raw = fallback(width: width, height: height)
        }
        let capped = fromUser ? raw : Swift.min(raw, maximumTier)
        let scaled = codec.isHEVC ? Int(Double(capped) * hevcFactor) : capped
        return Swift.max(scaled, minimumBitRate)
    }

    /// 把任意码率吸附到最近的产品档（按比值距离，等比档位才不会错配）。
    public static func nearestTier(bitRate: Int) -> Int {
        guard !RecordingTiers.videoBitRates.isEmpty, bitRate > 0 else { return bitRate }
        let ratio = Double(bitRate)
        var best = RecordingTiers.videoBitRates[0]
        var bestDistance = Double.greatestFiniteMagnitude
        for tier in RecordingTiers.videoBitRates {
            let value = Double(tier)
            let distance = value > ratio ? value / ratio : ratio / value
            if distance < bestDistance {
                bestDistance = distance
                best = tier
            }
        }
        return best
    }

    /// UI 默认高亮档。
    public static func recommendedTier(
        userBitRate: Int,
        width: Int,
        height: Int,
        frameRate: Double,
        codec: RecordingVideoCodec
    ) -> Int {
        return nearestTier(bitRate: resolve(
            userBitRate: userBitRate,
            width: width,
            height: height,
            frameRate: frameRate,
            codec: codec
        ))
    }
}

// MARK: - 存储估算

/// 「还能拍多久」估算（只按视频码率折算，不含音频，结果一律带上界的语义）。
public enum RecordingStorageEstimate {

    /// 可用容量（MB）+ 视频码率（bps）→ 可拍分钟数，向下取整。
    public static func minutes(freeMegabytes: Int64, bitRate: Int) -> Int64 {
        guard freeMegabytes > 0, bitRate > 0 else { return 0 }
        return freeMegabytes * 1_024 * 1_024 * 8 / Int64(bitRate) / 60
    }

    /// 读数文案：不足一小时给分钟，过了一小时给 `h.h` 小时，超过 100 小时报 `100+`。
    public static func humanize(minutes: Int64) -> String {
        if minutes <= 0 {
            return "0"
        }
        if minutes >= 6_000 {
            return "100+"
        }
        if minutes >= 60 {
            return String(format: "%.1f", Double(minutes) / 60.0)
        }
        return String(minutes)
    }

    /// [humanize] 的输出单位是否为小时。
    public static func usesHourUnit(minutes: Int64) -> Bool {
        return minutes >= 60
    }
}

// MARK: - 录制参数快照

/// 一次录制的全部参数（对应 Android `RecordProfile` 的字段与语义）。
///
/// 本类型只描述「应该怎么录」；真正打开 `AVAssetWriter` 属于 B2，不在本任务范围。
public struct RecordingConfiguration: Equatable, Sendable {
    public let resolution: CameraResolution
    public let frameRate: Double
    /// 采集帧率；与 [frameRate] 不等时是延时摄影语义。
    public let captureRate: Double
    public let userBitRate: Int
    public let codec: RecordingVideoCodec
    public let sampleRate: Int
    public let channels: Int
    public let audioBitRate: Int
    public let audioEnabled: Bool
    public let orientationHint: Int
    public let mirrored: Bool
    public let usesGPU: Bool
    public let frameRateIsNonIntegerStandard: Bool
    public let notes: [String]

    public init(
        resolution: CameraResolution,
        frameRate: Double,
        captureRate: Double,
        userBitRate: Int,
        codec: RecordingVideoCodec,
        sampleRate: Int,
        channels: Int = 2,
        audioBitRate: Int = RecordingBitratePolicy.audioBitRate,
        audioEnabled: Bool,
        orientationHint: Int,
        mirrored: Bool,
        usesGPU: Bool,
        frameRateIsNonIntegerStandard: Bool = false,
        notes: [String] = []
    ) {
        self.resolution = resolution
        self.frameRate = frameRate
        self.captureRate = captureRate
        self.userBitRate = userBitRate
        self.codec = codec
        self.sampleRate = sampleRate
        self.channels = channels
        self.audioBitRate = audioBitRate
        self.audioEnabled = audioEnabled
        self.orientationHint = orientationHint
        self.mirrored = mirrored
        self.usesGPU = usesGPU
        self.frameRateIsNonIntegerStandard = frameRateIsNonIntegerStandard
        self.notes = notes
    }

    public var width: Int {
        return resolution.width
    }

    public var height: Int {
        return resolution.height
    }

    /// 延时摄影（采集帧率 ≠ 容器帧率）。
    public var isTimeLapse: Bool {
        return captureRate > 0 && Int(captureRate) != Int(frameRate)
    }

    /// 是否需要音频通路（静音或无权限时上层已把 [audioEnabled] 置 false）。
    public var isAudioUsable: Bool {
        return audioEnabled && sampleRate > 0 && channels > 0
    }

    /// 三段式落地后的编码器码率。
    public var effectiveBitRate: Int {
        return RecordingBitratePolicy.resolve(
            userBitRate: userBitRate,
            width: resolution.width,
            height: resolution.height,
            frameRate: frameRate,
            codec: codec
        )
    }

    /// `1920×1080 @24p H.264 16 Mbps` 形态的摘要。
    public var summary: String {
        return String(
            format: "%@ @%.0fp %@ %.0f Mbps",
            resolution.displayName,
            frameRate,
            codec.displayName,
            Double(effectiveBitRate) / 1_000_000.0
        )
    }
}

// MARK: - 容器朝向

/// 容器旋转角（对应 Android `recordOrientationHint`）：`sensor − 显示`，前摄再加 180°。
public enum RecordingOrientation {

    /// 归一化到 `0...359`。
    public static func normalize(degrees: Int) -> Int {
        let value = degrees % 360
        return value < 0 ? value + 360 : value
    }

    /// 容器应写的显示旋转角。
    public static func hint(sensorOrientation: Int, deviceDegrees: Int, isFrontCamera: Bool) -> Int {
        return normalize(degrees: sensorOrientation - deviceDegrees + (isFrontCamera ? 180 : 0))
    }
}
