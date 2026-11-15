import Foundation

/// 曝光目标偏置（EV）区间与归一化映射。
///
/// 纯逻辑，不依赖 AVFoundation：平台层用 `AVCaptureDevice.minExposureTargetBias` /
/// `maxExposureTargetBias` 构造区间；当 `minimum == maximum` 时表示设备不支持调整，
/// UI 据此禁用滑杆并给出降级提示。
public struct ExposureBiasRange: Equatable, Sendable {
    public let minimum: Float
    public let maximum: Float

    public init(minimum: Float, maximum: Float) {
        self.minimum = minimum
        self.maximum = maximum
    }

    /// 区间有效且两端不等时才允许调整。
    public var isAdjustable: Bool {
        return maximum > minimum
    }

    /// 把任意值夹到合法区间；不支持调整时返回下界。
    public func clamp(_ value: Float) -> Float {
        guard maximum > minimum else { return minimum }
        return Swift.min(Swift.max(value, minimum), maximum)
    }

    /// 归一化位置（0...1）→ 实际 EV 值。
    public func value(forNormalizedPosition position: Float) -> Float {
        guard maximum > minimum else { return minimum }
        let clamped = Swift.min(Swift.max(position, 0), 1)
        return minimum + (maximum - minimum) * clamped
    }

    /// 实际 EV 值 → 归一化位置（0...1）。
    public func normalizedPosition(forValue value: Float) -> Float {
        guard maximum > minimum else { return 0 }
        return (clamp(value) - minimum) / (maximum - minimum)
    }
}
