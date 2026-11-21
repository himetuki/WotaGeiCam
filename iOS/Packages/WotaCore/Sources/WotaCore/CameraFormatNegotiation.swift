import Foundation

/// 视频格式协商的**纯逻辑**：把"用户想要的分辨率 + 帧率"在设备运行时 format 表里落档。
///
/// 与 Android `core/CameraAbility.kt` 的 `decideFps` / `pickFpsRange` 对称，但 iOS 侧没有
/// "普通表 / 高速表"双表（`AVCaptureDevice.Format.videoSupportedFrameRateRanges` 已经把两者
/// 统一表达），因此这里只做"固定档优先、可变档取上界最小"的单表选档，绝不自己造 `[f, f]`。
///
/// 平台层（`CameraSessionController`）负责把选出的 format 在 `beginConfiguration/commitConfiguration`
/// 内落到 `AVCaptureDevice.activeFormat`；本文件只给决议，不碰设备。

/// 一次格式切换请求（分辨率 + 目标帧率）。
public struct CameraVideoConfigurationRequest: Equatable, Sendable {
    public let resolution: CameraResolution
    public let frameRate: Double

    public init(resolution: CameraResolution, frameRate: Double) {
        self.resolution = resolution
        self.frameRate = frameRate
    }
}

/// 格式选档结果：请求 / 选中 / 实际帧率 / 不支持原因 全部写明，供 UI 与日志复用。
public struct CameraFormatSelection: Equatable, Sendable {
    public let requested: CameraVideoConfigurationRequest
    public let selectedResolution: CameraResolution?
    /// 选中 format 在 `CameraAbilitySnapshot.formats` 中的下标（平台层据此取真实 `AVCaptureDevice.Format`）。
    public let selectedFormatIndex: Int?
    public let appliedFrameRate: Double?
    public let appliedFrameRateRange: CameraFrameRateRange?
    /// `true` = 命中固定档；`false` = 只能给可变区间（UI 应标注"近似"）。
    public let frameRateExact: Bool
    public let unsupportedReason: String?
    public let notes: [String]

    public init(
        requested: CameraVideoConfigurationRequest,
        selectedResolution: CameraResolution?,
        selectedFormatIndex: Int?,
        appliedFrameRate: Double?,
        appliedFrameRateRange: CameraFrameRateRange?,
        frameRateExact: Bool,
        unsupportedReason: String?,
        notes: [String]
    ) {
        self.requested = requested
        self.selectedResolution = selectedResolution
        self.selectedFormatIndex = selectedFormatIndex
        self.appliedFrameRate = appliedFrameRate
        self.appliedFrameRateRange = appliedFrameRateRange
        self.frameRateExact = frameRateExact
        self.unsupportedReason = unsupportedReason
        self.notes = notes
    }

    public var isApplied: Bool {
        return selectedFormatIndex != nil && unsupportedReason == nil
    }

    public func selectedFormat(in ability: CameraAbilitySnapshot) -> CameraFormatDescriptor? {
        guard let index = selectedFormatIndex, index >= 0, index < ability.formats.count else {
            return nil
        }
        return ability.formats[index]
    }
}

/// 平台层在 `sessionQueue` 上执行格式切换后的**结果回执**（含回滚信息）。
public struct CameraVideoConfigurationOutcome: Equatable, Sendable {
    public let selection: CameraFormatSelection
    public let appliedResolution: CameraResolution?
    public let appliedFrameRateRange: CameraFrameRateRange?
    public let didApplyFrameDurations: Bool
    public let rolledBack: Bool
    public let errorMessage: String?

    public init(
        selection: CameraFormatSelection,
        appliedResolution: CameraResolution?,
        appliedFrameRateRange: CameraFrameRateRange?,
        didApplyFrameDurations: Bool,
        rolledBack: Bool,
        errorMessage: String?
    ) {
        self.selection = selection
        self.appliedResolution = appliedResolution
        self.appliedFrameRateRange = appliedFrameRateRange
        self.didApplyFrameDurations = didApplyFrameDurations
        self.rolledBack = rolledBack
        self.errorMessage = errorMessage
    }

    public var isSuccess: Bool {
        return selection.isApplied && errorMessage == nil && !rolledBack
    }
}

/// 分辨率 / 帧率协商的纯函数集。
public enum CameraFormatNegotiation {

    /// 产品分辨率档位（与 Android `C10` 录制档表同源；是否可选仍以设备 format 能力为准）。
    public static let resolutionTiers: [CameraResolution] = [
        CameraResolution(width: 1280, height: 720),
        CameraResolution(width: 1920, height: 1080),
        CameraResolution(width: 2560, height: 1440),
        CameraResolution(width: 3840, height: 2160),
        CameraResolution(width: 7680, height: 4320)
    ]

    /// 产品帧率档位（与 Android `WotaTiers.FPS` 同源）。
    public static let frameRateTiers: [Double] = [24, 25, 30, 50, 60, 120, 240]

    /// 必需帧率（1/24、1/25 低速快门场景）。
    public static let requiredFrameRates: [Double] = [24, 25]

    /// 设备实际可用的分辨率（去重、按像素降序）。
    public static func availableResolutions(in ability: CameraAbilitySnapshot) -> [CameraResolution] {
        return ability.supportedResolutions
    }

    /// 设备实际可用的帧率档（档位 ∩ 任 format 支持）。
    public static func supportedFrameRates(in ability: CameraAbilitySnapshot) -> [Double] {
        var result: [Double] = []
        for tier in frameRateTiers where isFrameRate(tier, supportedIn: ability) {
            result.append(tier)
        }
        return result
    }

    /// 该帧率是否被任一 format 的区间覆盖（可用性判定域）。
    public static func isFrameRate(_ frameRate: Double, supportedIn ability: CameraAbilitySnapshot) -> Bool {
        for format in ability.formats where format.isUsable && format.supports(frameRate: frameRate) {
            return true
        }
        return false
    }

    /// 在设备分辨率里找与目标最接近的一档：先同宽高比、取像素最接近；再退到全局最近像素。
    public static func nearestResolution(
        to target: CameraResolution,
        in ability: CameraAbilitySnapshot
    ) -> CameraResolution? {
        let candidates = ability.supportedResolutions
        guard !candidates.isEmpty else { return nil }

        for candidate in candidates where candidate == target {
            return candidate
        }

        var sameAspect: CameraResolution?
        var sameAspectDistance = Int.max
        var any: CameraResolution?
        var anyDistance = Int.max
        for candidate in candidates {
            let distance = Swift.abs(candidate.pixelCount - target.pixelCount)
            if distance < anyDistance {
                anyDistance = distance
                any = candidate
            }
            if candidate.aspectLabel == target.aspectLabel && distance < sameAspectDistance {
                sameAspectDistance = distance
                sameAspect = candidate
            }
        }
        return sameAspect ?? any
    }

    /// 为请求选一个可用 format：分辨率优先精确命中，帧率优先固定档、再取上界最小的可变档。
    ///
    /// 请求分辨率无 format 支持目标帧率时，会尝试**同宽高比**的其它分辨率并在 `notes` 说明；
    /// 都做不到则 `unsupportedReason` 非空、`selectedFormatIndex` 为 nil，平台层据此**不改设备**。
    public static func selectFormat(
        for request: CameraVideoConfigurationRequest,
        in ability: CameraAbilitySnapshot
    ) -> CameraFormatSelection {
        guard ability.isAvailable, !ability.formats.isEmpty else {
            return CameraFormatSelection(
                requested: request,
                selectedResolution: nil,
                selectedFormatIndex: nil,
                appliedFrameRate: nil,
                appliedFrameRateRange: nil,
                frameRateExact: false,
                unsupportedReason: "相机未就绪，无法解析格式。",
                notes: []
            )
        }

        var notes: [String] = []

        // 1) 分辨率精确命中，且支持目标帧率。
        if let index = bestFormatIndex(in: ability, resolution: request.resolution, frameRate: request.frameRate) {
            let format = ability.formats[index]
            let range = chosenRange(in: format, for: request.frameRate)
            return CameraFormatSelection(
                requested: request,
                selectedResolution: format.resolution,
                selectedFormatIndex: index,
                appliedFrameRate: request.frameRate,
                appliedFrameRateRange: range,
                frameRateExact: range?.isFixed ?? false,
                unsupportedReason: nil,
                notes: notes
            )
        }

        // 2) 回退到同宽高比的最近分辨率（分辨率改变要明说）。
        var fallbackIndex: Int?
        var fallbackResolution: CameraResolution?
        var fallbackDistance = Int.max
        for (index, format) in ability.formats.enumerated() {
            guard format.isUsable,
                  format.resolution.aspectLabel == request.resolution.aspectLabel,
                  format.supports(frameRate: request.frameRate) else { continue }
            let distance = Swift.abs(format.resolution.pixelCount - request.resolution.pixelCount)
            if distance < fallbackDistance {
                fallbackDistance = distance
                fallbackIndex = index
                fallbackResolution = format.resolution
            }
        }
        if let index = fallbackIndex, let resolution = fallbackResolution {
            let format = ability.formats[index]
            let range = chosenRange(in: format, for: request.frameRate)
            notes.append(String(
                format: "请求分辨率 %@ 不支持 %.0f fps，回退到 %@",
                request.resolution.displayName,
                request.frameRate,
                resolution.displayName
            ))
            return CameraFormatSelection(
                requested: request,
                selectedResolution: resolution,
                selectedFormatIndex: index,
                appliedFrameRate: request.frameRate,
                appliedFrameRateRange: range,
                frameRateExact: range?.isFixed ?? false,
                unsupportedReason: nil,
                notes: notes
            )
        }

        // 3) 彻底做不到：不改设备，给出原因。
        let nearest = nearestResolution(to: request.resolution, in: ability)
        let reason = String(
            format: "设备 format 表没有同时支持 %@ 与 %.0f fps 的档位。",
            request.resolution.displayName,
            request.frameRate
        )
        return CameraFormatSelection(
            requested: request,
            selectedResolution: nearest,
            selectedFormatIndex: nil,
            appliedFrameRate: nil,
            appliedFrameRateRange: nil,
            frameRateExact: false,
            unsupportedReason: reason,
            notes: notes
        )
    }

    /// 该帧率在某 format 下的推荐区间（固定档优先，否则上界最小的覆盖区间）。
    public static func chosenRange(
        in format: CameraFormatDescriptor,
        for frameRate: Double
    ) -> CameraFrameRateRange? {
        var fixed: CameraFrameRateRange?
        var fixedSpan = Double.greatestFiniteMagnitude
        var variable: CameraFrameRateRange?
        var variableMax = Double.greatestFiniteMagnitude
        for range in format.frameRateRanges where range.contains(frameRate) {
            if range.isFixed {
                let span = range.maximum - range.minimum
                if span < fixedSpan {
                    fixedSpan = span
                    fixed = range
                }
            } else if range.maximum < variableMax {
                variableMax = range.maximum
                variable = range
            }
        }
        return fixed ?? variable
    }

    /// 帧率档的展示文案（`24p（固定）` / `50p（近似）`）。
    public static func frameRateDescription(_ frameRate: Double, exact: Bool) -> String {
        return exact
            ? String(format: "%.0fp（固定）", frameRate)
            : String(format: "%.0fp（近似）", frameRate)
    }

    /// 候选 format 下标顺序：首选档在前，其后是同分辨率的固定档、再是可变档。
    ///
    /// 平台层用它实现「首选格式落地失败就换下一个候选」；返回的下标可直接索引
    /// `AVCaptureDevice.formats`（约定：`CameraAbilitySnapshot.formats` 不过滤任何一项）。
    public static func formatCandidateIndices(
        in ability: CameraAbilitySnapshot,
        resolution: CameraResolution?,
        frameRate: Double,
        primary: Int,
        limit: Int = 4
    ) -> [Int] {
        var fixed: [Int] = []
        var variable: [Int] = []
        for (index, format) in ability.formats.enumerated() {
            guard index != primary, format.isUsable else { continue }
            if let resolution = resolution, format.resolution != resolution { continue }
            guard format.supports(frameRate: frameRate) else { continue }
            if chosenRange(in: format, for: frameRate)?.isFixed == true {
                fixed.append(index)
            } else {
                variable.append(index)
            }
        }
        var result = [primary] + fixed + variable
        if limit > 0, result.count > limit {
            result = Array(result.prefix(limit))
        }
        return result
    }

    // MARK: - 内部

    private static func bestFormatIndex(
        in ability: CameraAbilitySnapshot,
        resolution: CameraResolution,
        frameRate: Double
    ) -> Int? {
        var fixedIndex: Int?
        var fixedSpan = Double.greatestFiniteMagnitude
        var variableIndex: Int?
        var variableMax = Double.greatestFiniteMagnitude
        for (index, format) in ability.formats.enumerated() {
            guard format.isUsable, format.resolution == resolution else { continue }
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
}
