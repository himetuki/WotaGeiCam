import Foundation

// MARK: - 抽帧补弧的差分死区（ArcMerge）
//
// 纯逻辑。算法证据（只读对照，不翻译代码）：
//   - `Android/.../core/Constants.kt`（`ArcMergeDeadband`，DEFAULT=12 / MIN=0 / MAX=32 / luma 归一），
//   - `Android/.../camera/Shaders.kt`（`ARC_MERGE_FS`：整像素选择 + 差分死区），
//   - `Android/.../camera/ArcMendPass.kt`、`Android/.../record/ArcRepairGl.kt`（在线/离线同一拓扑），
//   - `Android/.../record/ArcKeepRule.kt`（保留/丢弃判定，属上层时间轴逻辑，不在本文件）。

/// 抽帧补弧合并的**差分死区**（8bit luma 级）——GPU/CPU 两路共用的唯一档位真源。
///
/// 判据：`Δluma = luma(被抽帧) − luma(保留帧)`；`Δluma > D` 时整颗像素取被抽帧，否则保持保留帧。
/// 这是**差分**死区，不是「多亮才算弧」的绝对亮度阈值；D=0 退化为纯整像素选择，仍不造色。
public enum ArcMergeConstants {
    public static let `default`: Int = 12
    public static let min: Int = 0
    public static let max: Int = 32
}

/// 抽帧补弧参数（纯逻辑；离线修复与录制期转换共用）。
public struct ArcMergeParameters: Equatable, Sendable {

    /// 差分死区（8bit luma 级，0..32）。
    public var deadband: Int

    public init(deadband: Int = ArcMergeConstants.default) {
        self.deadband = deadband
    }

    /// 出厂默认（12）。
    public static let `default` = ArcMergeParameters()

    /// 钳到 `[min, max]`（幂等）。
    public func sanitized() -> ArcMergeParameters {
        return ArcMergeParameters(deadband: arcMergeClamp(deadband))
    }

    /// 归一化到着色器/核的 0..1 亮度差（uniform `uDeadbandLuma`）。
    public var deadbandLuma: Float {
        return arcMergeLuma(deadband)
    }
}

/// 死区值钳到 `[min, max]`。
public func arcMergeClamp(_ value: Int) -> Int {
    if value < ArcMergeConstants.min { return ArcMergeConstants.min }
    if value > ArcMergeConstants.max { return ArcMergeConstants.max }
    return value
}

/// 死区值 → 归一化亮度差（0..1），除以 255。
public func arcMergeLuma(_ value: Int) -> Float {
    return Float(arcMergeClamp(value)) / 255.0
}

/// 差分死区下的整像素选择（CPU 后端与语义核对共用；GPU 后端在着色器里等价实现）。
///
/// - Parameters:
///   - kept: 保留帧/累积面像素（RGB 0..1）
///   - dropped: 被抽帧像素（RGB 0..1）
///   - deadbandLuma: 归一化死区（``arcMergeLuma(_:)``）
/// - Returns: `Δluma > deadbandLuma` 时返回 `dropped`，否则返回 `kept`。
public func arcMergeSelectPixel(
    kept: (r: Float, g: Float, b: Float),
    dropped: (r: Float, g: Float, b: Float),
    deadbandLuma: Float
) -> (r: Float, g: Float, b: Float) {
    let lumaKept = arcMergeLumaOfRGB(kept.r, kept.g, kept.b)
    let lumaDropped = arcMergeLumaOfRGB(dropped.r, dropped.g, dropped.b)
    return (lumaDropped - lumaKept) > deadbandLuma ? dropped : kept
}

/// BT.601 亮度（与 Android 双侧 `dot(c, vec3(0.299, 0.587, 0.114))` 同源）。
public func arcMergeLumaOfRGB(_ r: Float, _ g: Float, _ b: Float) -> Float {
    return 0.299 * r + 0.587 * g + 0.114 * b
}
