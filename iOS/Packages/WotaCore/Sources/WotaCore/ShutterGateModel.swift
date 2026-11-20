import Foundation

/// **低速快门（慢门）** 的纯逻辑门控。
///
/// WOTA 艺的一个核心诉求是 1/24、1/25 这种「帧周期即曝光时间」的慢门：夜拍光弧靠长曝光拉出余晖。
/// Android 侧对应 `WotaTiers.SHUTTER_*` + `RequestApplier.shutterCeilingNs`，本文件只做同一套判据的
/// 纯函数表达，**不碰设备**；真正下发 `activeVideoMinFrameDuration` / `activeVideoMaxFrameDuration` /
/// `setExposureModeCustom` 由平台层在 `sessionQueue` 上完成。
///
/// 两条必须分开的状态（产品明确要求）：
/// - `frameDurationSettable`：该帧率被设备 format 承载 ⇒ 可以把 min/max 帧周期都锁成目标值；
/// - `sensorExposureSettable`：设备允许手动设置 sensor 曝光时间（`exposureDurationRange` 可用）。
///
/// 「快门档可用」不等于「sensor 曝光时间可设」——前者只要帧周期能锁，后者还要曝光时间域。

/// 一个快门档（分母即秒数：`1/24`、`1/25` …）。
public struct CameraShutterSpeed: Hashable, Sendable {
    public let denominator: Int

    public init(denominator: Int) {
        self.denominator = denominator
    }

    public var seconds: Double {
        return denominator > 0 ? 1.0 / Double(denominator) : 0
    }

    /// 纳秒值（整除，与产品档位表同源：1/24 → 41_666_666）。
    public var nanoseconds: Int64 {
        return RecordingTiers.shutterNanoseconds(denominator: denominator)
    }

    public var displayName: String {
        return "1/\(denominator)s"
    }

    /// 慢门档（分母 ≤ 25）：夜拍光弧的主战场。
    public var isLowSpeed: Bool {
        return denominator > 0 && denominator <= 25
    }

    /// 产品强制快门档（1/24、1/25）。
    public var isRequiredTier: Bool {
        return RecordingTiers.requiredShutterDenominators.contains(denominator)
    }

    public static let one24 = CameraShutterSpeed(denominator: 24)
    public static let one25 = CameraShutterSpeed(denominator: 25)

    /// 低速快门候选（按分母升序：1/24、1/25）。
    public static let lowSpeedTiers: [CameraShutterSpeed] = [.one24, .one25]

    /// 全部产品快门档。
    public static func standardTiers() -> [CameraShutterSpeed] {
        return RecordingTiers.shutterDenominators.map { CameraShutterSpeed(denominator: $0) }
    }
}

/// 慢门评估的结论类别（状态词与 UI 文案一一对应）。
public enum CameraSlowShutterStatus: String, Sendable {
    /// 帧周期锁定 + sensor 曝光时间都设成功。
    case applied
    /// 帧周期锁定成功，但设备不支持手动 sensor 曝光时间 ⇒ 慢门只能靠帧周期达成。
    case frameLockedExposureUnavailable
    /// 目标快门超出帧周期/1-10s 上限，实际曝光被夹短（请求与实际不一致，必须明说）。
    case exposureClamped
    /// 目标帧率设备 format 表不支持。
    case frameRateUnsupported
    /// 相机未就绪。
    case cameraUnavailable

    public var displayName: String {
        switch self {
        case .applied:
            return "已生效"
        case .frameLockedExposureUnavailable:
            return "帧周期已锁 / 曝光时间不可手动设置"
        case .exposureClamped:
            return "曝光被夹短"
        case .frameRateUnsupported:
            return "设备不支持该帧率"
        case .cameraUnavailable:
            return "相机未就绪"
        }
    }
}

/// 帧周期落档计划（平台层据此设置 `activeVideoMinFrameDuration` 与 `activeVideoMaxFrameDuration`）。
public struct CameraFrameDurationPlan: Equatable, Sendable {
    public let frameRate: Double
    public let frameDurationNanoseconds: Int64
    public let resolution: CameraResolution?
    public let range: CameraFrameRateRange?
    public let formatIndex: Int?
    public let notes: [String]

    public init(
        frameRate: Double,
        frameDurationNanoseconds: Int64,
        resolution: CameraResolution?,
        range: CameraFrameRateRange?,
        formatIndex: Int?,
        notes: [String]
    ) {
        self.frameRate = frameRate
        self.frameDurationNanoseconds = frameDurationNanoseconds
        self.resolution = resolution
        self.range = range
        self.formatIndex = formatIndex
        self.notes = notes
    }

    /// 只有拿到具体 format 与区间才允许去改设备；否则平台层必须原样回滚不改。
    public var isFeasible: Bool {
        return formatIndex != nil && range != nil && range?.contains(frameRate) == true
    }
}

/// 慢门评估结果：请求 / 目标帧周期 / 实际曝光 / 两条独立状态 / 原因。
public struct CameraSlowShutterAssessment: Equatable, Sendable {
    public let target: CameraShutterSpeed
    public let frameRate: Double
    /// 目标快门时长（= 用户请求的曝光时间），纳秒。
    public let requestedNanoseconds: Int64
    /// 锁帧率后写入设备的帧周期，纳秒。
    public let frameDurationNanoseconds: Int64
    /// 设备实际会接受的 sensor 曝光时间；不可手动设置时为 nil。
    public let effectiveExposureNanoseconds: Int64?
    public let frameRateFeasible: Bool
    public let frameDurationSettable: Bool
    public let sensorExposureSettable: Bool
    public let exposureClampedByFrameDuration: Bool
    public let exposureClampedByCap: Bool
    public let formatResolution: CameraResolution?
    public let frameRateRange: CameraFrameRateRange?
    public let status: CameraSlowShutterStatus
    public let notes: [String]

    public init(
        target: CameraShutterSpeed,
        frameRate: Double,
        requestedNanoseconds: Int64,
        frameDurationNanoseconds: Int64,
        effectiveExposureNanoseconds: Int64?,
        frameRateFeasible: Bool,
        frameDurationSettable: Bool,
        sensorExposureSettable: Bool,
        exposureClampedByFrameDuration: Bool,
        exposureClampedByCap: Bool,
        formatResolution: CameraResolution?,
        frameRateRange: CameraFrameRateRange?,
        status: CameraSlowShutterStatus,
        notes: [String]
    ) {
        self.target = target
        self.frameRate = frameRate
        self.requestedNanoseconds = requestedNanoseconds
        self.frameDurationNanoseconds = frameDurationNanoseconds
        self.effectiveExposureNanoseconds = effectiveExposureNanoseconds
        self.frameRateFeasible = frameRateFeasible
        self.frameDurationSettable = frameDurationSettable
        self.sensorExposureSettable = sensorExposureSettable
        self.exposureClampedByFrameDuration = exposureClampedByFrameDuration
        self.exposureClampedByCap = exposureClampedByCap
        self.formatResolution = formatResolution
        self.frameRateRange = frameRateRange
        self.status = status
        self.notes = notes
    }

    /// 帧周期与 sensor 曝光两条路都成功。
    public var isFullyApplied: Bool {
        return status == .applied
    }

    /// 源规格 / 目标帧周期 / 实际曝光 / 结论——日志与 UI 共用的一段话。
    public var summary: String {
        let actual = effectiveExposureNanoseconds.map { String($0) } ?? "不可设"
        return String(
            format: "%@ @%.0ffps 源 %lld ns / 帧周期 %lld ns / 实际曝光 %@ ns / %@",
            target.displayName,
            frameRate,
            requestedNanoseconds,
            frameDurationNanoseconds,
            actual,
            status.displayName
        )
    }
}

/// 慢门不可用时的**显式替代建议**：只给候选，不静默改写用户意图。
public struct CameraShutterAlternative: Equatable, Sendable {
    public let target: CameraShutterSpeed
    public let frameRate: Double
    public let reason: String

    public init(target: CameraShutterSpeed, frameRate: Double, reason: String) {
        self.target = target
        self.frameRate = frameRate
        self.reason = reason
    }
}

/// 慢门门控的纯函数集。
public enum CameraShutterGate {

    /// 1/10s 防手抖钳制。
    public static let shutterCapNanoseconds: Int64 = RecordingTiers.shutterCapNanoseconds

    /// 帧率 → 帧周期（整除，与 `AVFrameRateRange` 的纳秒口径一致）。
    public static func frameDurationNanoseconds(frameRate: Double) -> Int64 {
        guard frameRate > 0 else { return 0 }
        return RecordingTiers.nanosecondsPerSecond / Int64(frameRate)
    }

    /// 该帧率下 sensor 曝光的**上界**：不得超过帧周期（否则丢帧），也不得超过 1/10s。
    public static func exposureCeilingNanoseconds(frameRate: Double) -> Int64 {
        let frameDuration = frameDurationNanoseconds(frameRate: frameRate)
        guard frameDuration > 0 else { return shutterCapNanoseconds }
        return Swift.min(frameDuration, shutterCapNanoseconds)
    }

    /// 把请求曝光夹到 `[1ns, min(帧周期, 1/10s)]`。
    public static func clampExposureNanoseconds(_ requested: Int64, frameRate: Double) -> Int64 {
        let ceiling = exposureCeilingNanoseconds(frameRate: frameRate)
        let lower: Int64 = 1
        return Swift.min(Swift.max(requested, lower), Swift.max(ceiling, lower))
    }

    /// 目标是否慢门档（分母 ≤ 25）。
    public static func isLowSpeedTarget(_ target: CameraShutterSpeed) -> Bool {
        return target.isLowSpeed
    }

    /// 某个帧率在某颗设备上可由 format 承载时，返回首选格式的分辨率 / 区间 / 下标（分辨率优先取活动档）。
    public static func rangeSupport(
        frameRate: Double,
        in ability: CameraAbilitySnapshot
    ) -> (resolution: CameraResolution, range: CameraFrameRateRange, formatIndex: Int)? {
        guard ability.isAvailable, !ability.formats.isEmpty else { return nil }

        func bestIndex(preferringActive: Bool) -> Int? {
            var fixedIndex: Int?
            var fixedSpan = Double.greatestFiniteMagnitude
            var variableIndex: Int?
            var variableMax = Double.greatestFiniteMagnitude
            for (index, format) in ability.formats.enumerated() {
                guard format.isUsable else { continue }
                if preferringActive && format.resolution != ability.activeResolution {
                    continue
                }
                for range in format.frameRateRanges where range.contains(frameRate) {
                    if range.isFixed {
                        let span = range.maximum - range.minimum
                        if span < fixedSpan {
                            fixedSpan = span
                            fixedIndex = index
                        }
                    } else if range.maximum < variableMax {
                        variableMax = range.maximum
                        variableIndex = index
                    }
                }
            }
            return fixedIndex ?? variableIndex
        }

        if let index = bestIndex(preferringActive: true) {
            let format = ability.formats[index]
            if let range = CameraFormatNegotiation.chosenRange(in: format, for: frameRate) {
                return (format.resolution, range, index)
            }
        }
        if let index = bestIndex(preferringActive: false) {
            let format = ability.formats[index]
            if let range = CameraFormatNegotiation.chosenRange(in: format, for: frameRate) {
                return (format.resolution, range, index)
            }
        }
        return nil
    }

    /// 帧周期计划：拿不到承载该帧率的 format 时返回 `isFeasible == false` 的计划，平台层据此回滚。
    public static func frameDurationPlan(
        frameRate: Double,
        resolution: CameraResolution?,
        in ability: CameraAbilitySnapshot
    ) -> CameraFrameDurationPlan {
        let duration = frameDurationNanoseconds(frameRate: frameRate)
        guard let support = rangeSupport(frameRate: frameRate, in: ability) else {
            return CameraFrameDurationPlan(
                frameRate: frameRate,
                frameDurationNanoseconds: duration,
                resolution: nil,
                range: nil,
                formatIndex: nil,
                notes: [String(format: "设备 format 表不承载 %.0f fps，帧周期锁定不会下发。", frameRate)]
            )
        }
        var notes: [String] = []
        if let resolution = resolution, resolution != support.resolution {
            notes.append(String(
                format: "活动分辨率 %@ 不承载 %.0f fps，改用 %@。",
                resolution.displayName,
                frameRate,
                support.resolution.displayName
            ))
        }
        return CameraFrameDurationPlan(
            frameRate: frameRate,
            frameDurationNanoseconds: duration,
            resolution: support.resolution,
            range: support.range,
            formatIndex: support.formatIndex,
            notes: notes
        )
    }

    /// 慢门评估：分别给出「帧周期锁定」与「sensor 曝光时间」两条路的结论，不做任何静默改写。
    public static func evaluate(
        target: CameraShutterSpeed,
        frameRate: Double,
        in ability: CameraAbilitySnapshot
    ) -> CameraSlowShutterAssessment {
        let requested = target.nanoseconds
        let frameDuration = frameDurationNanoseconds(frameRate: frameRate)

        guard ability.isAvailable, !ability.formats.isEmpty else {
            return CameraSlowShutterAssessment(
                target: target,
                frameRate: frameRate,
                requestedNanoseconds: requested,
                frameDurationNanoseconds: frameDuration,
                effectiveExposureNanoseconds: nil,
                frameRateFeasible: false,
                frameDurationSettable: false,
                sensorExposureSettable: false,
                exposureClampedByFrameDuration: false,
                exposureClampedByCap: false,
                formatResolution: nil,
                frameRateRange: nil,
                status: .cameraUnavailable,
                notes: ["尚未建立相机输入，慢门档只能在预览启动后判定。"]
            )
        }

        let support = rangeSupport(frameRate: frameRate, in: ability)
        let frameRateFeasible = support != nil
        var notes: [String] = []

        let clampedByFrameDuration = frameDuration > 0 && requested > frameDuration
        let clampedByCap = requested > shutterCapNanoseconds
        if clampedByFrameDuration {
            notes.append(String(
                format: "%.0ffps 的帧周期 %lld ns 容不下 %@（%lld ns），曝光按帧周期执行。",
                frameRate,
                frameDuration,
                target.displayName,
                requested
            ))
        }
        if clampedByCap {
            notes.append(String(
                format: "%@ 超过 1/10s 防手抖上限（%lld ns），曝光被钳到上限。",
                target.displayName,
                shutterCapNanoseconds
            ))
        }

        let sensorExposureSettable = ability.hasManualExposureDuration
        var effectiveExposure: Int64?
        var clampedByDeviceRange = false
        if sensorExposureSettable {
            let clamped = clampExposureNanoseconds(requested, frameRate: frameRate)
            let inDeviceRange = ability.exposureDurationRange.clamp(Double(clamped))
            effectiveExposure = Int64(inDeviceRange)
            if inDeviceRange < Double(clamped) {
                clampedByDeviceRange = true
                notes.append(String(
                    format: "设备曝光时间域 %lld–%lld ns，请求值被夹到 %lld ns。",
                    Int64(ability.exposureDurationRange.minimum),
                    Int64(ability.exposureDurationRange.maximum),
                    Int64(inDeviceRange)
                ))
            }
        } else {
            notes.append("该设备不支持手动 sensor 曝光时间，慢门只能通过帧周期锁定达成。")
        }

        let status: CameraSlowShutterStatus
        if !frameRateFeasible {
            status = .frameRateUnsupported
        } else if !sensorExposureSettable {
            status = .frameLockedExposureUnavailable
        } else if clampedByFrameDuration || clampedByCap || clampedByDeviceRange {
            status = .exposureClamped
        } else {
            status = .applied
        }

        return CameraSlowShutterAssessment(
            target: target,
            frameRate: frameRate,
            requestedNanoseconds: requested,
            frameDurationNanoseconds: frameDuration,
            effectiveExposureNanoseconds: effectiveExposure,
            frameRateFeasible: frameRateFeasible,
            frameDurationSettable: frameRateFeasible,
            sensorExposureSettable: sensorExposureSettable,
            exposureClampedByFrameDuration: clampedByFrameDuration,
            exposureClampedByCap: clampedByCap,
            formatResolution: support?.resolution,
            frameRateRange: support?.range,
            status: status,
            notes: notes
        )
    }

    /// 设备上 1/24、1/25 各自能否达成（分母即所需帧率；分辨率不限制，只要任一 format 承载）。
    public static func lowSpeedAvailability(in ability: CameraAbilitySnapshot) -> [CameraShutterSpeed: Bool] {
        var result: [CameraShutterSpeed: Bool] = [:]
        for target in CameraShutterSpeed.lowSpeedTiers {
            let fps = Double(target.denominator)
            result[target] = ability.isAvailable
                && CameraFormatNegotiation.isFrameRate(fps, supportedIn: ability)
        }
        return result
    }

    /// 在目标帧率下无法按 [target] 曝光时，给出**显式**替代方案（含换帧率保快门的方案）。
    public static func alternatives(
        for target: CameraShutterSpeed,
        frameRate: Double,
        in ability: CameraAbilitySnapshot
    ) -> [CameraShutterAlternative] {
        guard ability.isAvailable, !ability.formats.isEmpty else { return [] }
        var result: [CameraShutterAlternative] = []

        let assessment = evaluate(target: target, frameRate: frameRate, in: ability)
        if assessment.frameDurationSettable && assessment.isFullyApplied {
            return []
        }

        // 方案 A：保住这颗快门，把帧率降到快门分母（1/24 + 24fps 才是产品定位里的那一对）。
        let desiredFrameRate = Double(target.denominator)
        if Swift.abs(desiredFrameRate - frameRate) > 0.01
            && CameraFormatNegotiation.isFrameRate(desiredFrameRate, supportedIn: ability) {
            result.append(CameraShutterAlternative(
                target: target,
                frameRate: desiredFrameRate,
                reason: String(
                    format: "改用 %.0ffps 可按 %@ 曝光（帧周期 %lld ns）。",
                    desiredFrameRate,
                    target.displayName,
                    frameDurationNanoseconds(frameRate: desiredFrameRate)
                )
            ))
        }

        // 方案 B：保住帧率，把快门退到该帧周期容得下的最近产品档。
        var best: CameraShutterSpeed?
        for candidate in CameraShutterSpeed.standardTiers() {
            guard candidate.nanoseconds <= exposureCeilingNanoseconds(frameRate: frameRate) else { continue }
            if best == nil || candidate.nanoseconds > best!.nanoseconds {
                best = candidate
            }
        }
        if let best = best, best != target {
            result.append(CameraShutterAlternative(
                target: best,
                frameRate: frameRate,
                reason: String(
                    format: "保持 %.0ffps 时最长只能到 %@（%lld ns ≤ 帧周期 %lld ns）。",
                    frameRate,
                    best.displayName,
                    best.nanoseconds,
                    frameDurationNanoseconds(frameRate: frameRate)
                )
            ))
        }

        return result
    }

    /// 目标快门在设备上的**首选帧率**（= 快门分母），用于「换 format 后再试一次」的候选顺序。
    public static func preferredFrameRate(for target: CameraShutterSpeed) -> Double {
        return Double(target.denominator)
    }
}

/// 一次慢门下发的**结果回执**：源规格 / 目标时长 / 实际观测 / 是否回滚。
///
/// 两条执行路径分别记账（产品要求「快门可用 ≠ sensor 曝光时间可设」）：
/// - [frameDurationApplied]：`activeVideoMin/MaxFrameDuration` 是否真的锁定成功；
/// - [sensorExposureApplied]：是否成功下发 `setExposureModeCustom` 的曝光时间。
public struct CameraSlowShutterOutcome: Equatable, Sendable {
    public let assessment: CameraSlowShutterAssessment
    public let appliedResolution: CameraResolution?
    public let appliedFrameRate: Double?
    public let frameDurationApplied: Bool
    public let sensorExposureApplied: Bool
    /// 下发后从设备读回的曝光时间（best-effort；读不到为 nil）。
    public let observedExposureNanoseconds: Int64?
    public let rolledBack: Bool
    public let explanation: String?

    public init(
        assessment: CameraSlowShutterAssessment,
        appliedResolution: CameraResolution?,
        appliedFrameRate: Double?,
        frameDurationApplied: Bool,
        sensorExposureApplied: Bool,
        observedExposureNanoseconds: Int64?,
        rolledBack: Bool,
        explanation: String?
    ) {
        self.assessment = assessment
        self.appliedResolution = appliedResolution
        self.appliedFrameRate = appliedFrameRate
        self.frameDurationApplied = frameDurationApplied
        self.sensorExposureApplied = sensorExposureApplied
        self.observedExposureNanoseconds = observedExposureNanoseconds
        self.rolledBack = rolledBack
        self.explanation = explanation
    }

    /// 帧周期锁定成功且没有回滚即算成功；sensor 曝光时间不可设时按降级成功（状态里已写明）。
    public var isSuccess: Bool {
        return frameDurationApplied && !rolledBack && explanation == nil
    }

    /// 「源规格 / 目标时长 / 实际结果」三段的完整记账文案。
    public var summary: String {
        let actual = observedExposureNanoseconds.map { String($0) } ?? "未读取"
        var text = String(
            format: "%@ / 目标 %lld ns / 帧周期锁定 %@ / sensor 曝光 %@ / 实际 %@ ns",
            assessment.summary,
            assessment.requestedNanoseconds,
            frameDurationApplied ? "成功" : "失败",
            sensorExposureApplied ? "成功" : "未启用",
            actual
        )
        if let explanation = explanation {
            text += " / \(explanation)"
        }
        return text
    }
}
