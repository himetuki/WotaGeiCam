import Foundation

/// 设置相关**纯数据模型与产品域常量**（对齐 Android `core/` 的 `Glow.kt` / `Echo.kt` /
/// `ToneParams.kt` / `CurveModel.kt` / `MarkerLines.kt` / `Constants.kt`）。
///
/// 这些值不依赖 UIKit / AVFoundation，可直接被设置页与（后续）相机/渲染层共用；
/// 编解码在 `SettingsCodecs.swift`，两个文件合起来构成复合串键的往返契约。

// MARK: - 产品档位表（设置用到的子集）

/// 产品档位表（对齐 Android `core/Constants.kt` 的 `object WotaTiers`，只保留设置层需要的常量）。
public enum WotaTiers {
    /// 帧率档位（24/25 为必须）。
    public static let fps: [Int] = [24, 25, 30, 50, 60, 120, 240]
    /// 码率 5 档（bps）。
    public static let bitrates: [Int] = [5_000_000, 10_000_000, 20_000_000, 35_000_000, 50_000_000]
    /// 音频采样率 3 档；默认 48k。
    public static let sampleRates: [Int] = [32_000, 44_100, 48_000]
    public static let defaultSampleRate = 48_000
    /// 色温/色调是「产品滑杆域」。
    public static let kelvinMin = 2000
    public static let kelvinMax = 10_000
    public static let tintMin = -50
    public static let tintMax = 50
    public static let nsPerSecond: Int64 = 1_000_000_000
    /// 3.5GiB FAT32 安全上限。
    public static let maxFileBytes: Int64 = 3_758_096_384
    /// 录像前余量门槛。
    public static let minFreeMB: Int64 = 200
}

// MARK: - 尺寸 `WxH`

/// 画幅尺寸（键 `default_size` 的 `"WxH"` 串格式）。
public struct WotaVideoSize: Equatable {
    public var width: Int
    public var height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }

    /// 出厂默认 1920x1080（对齐 Android `WotaParams.size` 初值）。
    public static let `default` = WotaVideoSize(width: 1920, height: 1080)

    /// `"1920x1080"`；与 `WotaVideoSize.parse` 一一对应。
    public var encoded: String { return "\(width)x\(height)" }

    /// 解析 `"WxH"`：两段都 >0 才认，坏串返回 nil（调用方跳过、保持出厂默认）。
    public static func parse(_ raw: String?) -> WotaVideoSize? {
        guard let raw = raw else { return nil }
        let parts = raw.split(separator: "x", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        guard let w = Int(parts[0]), let h = Int(parts[1]) else { return nil }
        guard w > 0 && h > 0 else { return nil }
        return WotaVideoSize(width: w, height: h)
    }
}

// MARK: - 补弧差分死区 / 站位线

/// 补弧合并的差分死区（8bit luma 级，`default_arc_merge_deadband`）。
public enum ArcMergeDeadband {
    public static let `default` = 12
    public static let min = 0
    public static let max = 32
    public static func clamp(_ value: Int) -> Int { return Swift.min(Swift.max(value, min), max) }
}

/// 站位线几何/色板常量（对齐 Android `core/MarkerLines.kt`）。**线数刻意不入档**。
public enum MarkerLines {
    public static let minCount = 0
    public static let maxCount = 8
    public static let minSpread: Float = 0.15
    public static let maxSpread: Float = 1.00
    public static let defaultCount = 0
    public static let defaultSpread: Float = 0.60

    public static func clampCount(_ value: Int) -> Int {
        return Swift.min(Swift.max(value, minCount), maxCount)
    }

    public static func clampSpread(_ value: Float) -> Float {
        if !value.isFinite { return defaultSpread }
        return Swift.min(Swift.max(value, minSpread), maxSpread)
    }

    /// 等距竖线的 x 比例（升序、居中对称、全落在 0..1）；count=0 返回空表 = 关闭。
    public static func lineXsFraction(count: Int, spread: Float) -> [Float] {
        let n = clampCount(count)
        let w = clampSpread(spread)
        if n <= 1 { return n == 1 ? [0.5] : [] }
        var out: [Float] = []
        out.reserveCapacity(n)
        for i in 0..<n {
            out.append(0.5 - w / 2 + w * Float(i) / Float(n - 1))
        }
        return out
    }
}

// MARK: - 等号档曝光纠偏

/// 等号档曝光纠偏的手动滑杆域（键 `correction_manual_ns`，0..8ms）。
public enum ExposureCorrection {
    public static let manualMinNs: Int64 = 0
    public static let manualMaxNs: Int64 = 8_000_000
    public static let manualStepNs: Int64 = 500_000

    public static func clampManualNs(_ value: Int64) -> Int64 {
        return Swift.min(Swift.max(value, manualMinNs), manualMaxNs)
    }
}

// MARK: - Glow

/// Glow 光晕的整份参数（复合串键 `glow_params`）。所有数值在构造/解码时钳进安全域。
public struct GlowParams: Equatable {
    public var enabled: Bool
    public var threshold: Float
    public var soft: Float
    public var width: Float
    public var gain: Float
    public var add: Float
    public var freq: Float
    public var blend: Float
    public var colorOnly: Bool
    public var color: UInt32

    public init(
        enabled: Bool = false,
        threshold: Float = 0.85,
        soft: Float = 0.05,
        width: Float = 0.02,
        gain: Float = 1.0,
        add: Float = 0.0,
        freq: Float = 0.0,
        blend: Float = 0.6,
        colorOnly: Bool = false,
        color: UInt32 = GlowParams.defaultColor
    ) {
        self.enabled = enabled
        self.threshold = threshold
        self.soft = soft
        self.width = width
        self.gain = gain
        self.add = add
        self.freq = freq
        self.blend = blend
        self.colorOnly = colorOnly
        self.color = color
    }

    public static let defaultColor: UInt32 = 0xFFFF_FFFF
    public static let `default` = GlowParams()

    /// 域钳之后的同一份参数（幂等）。落盘前必须过这一关。
    public func sanitized() -> GlowParams {
        var p = self
        p.threshold = GlowParams.clamp(threshold, 0.8, 1.0, 0.85)
        p.soft = GlowParams.clamp(soft, 0, 0.5, 0.05)
        p.width = GlowParams.clamp(width, 0, 0.08, 0.02)
        p.gain = GlowParams.clamp(gain, 0, 4, 1)
        p.add = GlowParams.clamp(add, 0, 1, 0)
        p.freq = GlowParams.clamp(freq, 0, 64, 0)
        p.blend = GlowParams.clamp(blend, 0, 1, 0.6)
        p.color = color & 0xFFFF_FFFF
        return p
    }

    static func clamp(_ value: Float, _ lo: Float, _ hi: Float, _ fallback: Float) -> Float {
        if !value.isFinite { return fallback }
        return Swift.min(Swift.max(value, lo), hi)
    }
}

// MARK: - Echo（残影）

/// 残影整份参数（复合串键 `echo_params`）。
public struct EchoParams: Equatable {
    public var enabled: Bool
    public var mode: EchoMode
    public var multiplier: Int
    public var threshold: Float
    public var gain: Float
    public var trailColorMode: EchoTrailColorMode
    public var trailColor: UInt32
    public var trailTol: Float

    public init(
        enabled: Bool = false,
        mode: EchoMode = .keepBrightness,
        multiplier: Int = 2,
        threshold: Float = 0.9,
        gain: Float = 1.0,
        trailColorMode: EchoTrailColorMode = .off,
        trailColor: UInt32 = EchoParams.defaultTrailColor,
        trailTol: Float = 0.3
    ) {
        self.enabled = enabled
        self.mode = mode
        self.multiplier = multiplier
        self.threshold = threshold
        self.gain = gain
        self.trailColorMode = trailColorMode
        self.trailColor = trailColor
        self.trailTol = trailTol
    }

    public static let minMultiplier = 2
    public static let maxMultiplier = 10
    public static let minThreshold: Float = 0.6
    public static let maxThreshold: Float = 1
    public static let maxGain: Float = 2
    public static let minTrailTol: Float = 0.05
    public static let maxTrailTol: Float = 0.8
    public static let defaultTrailColor: UInt32 = 0xFFFF_FFFF
    public static let `default` = EchoParams()

    public func sanitized() -> EchoParams {
        var p = self
        p.multiplier = (multiplier >= EchoParams.minMultiplier && multiplier <= EchoParams.maxMultiplier)
            ? multiplier : 2
        p.threshold = GlowParams.clamp(threshold, EchoParams.minThreshold, EchoParams.maxThreshold, 0.9)
        p.gain = GlowParams.clamp(gain, 0, EchoParams.maxGain, 1)
        p.trailTol = GlowParams.clamp(trailTol, EchoParams.minTrailTol, EchoParams.maxTrailTol, 0.3)
        p.trailColor = trailColor & 0xFFFF_FFFF
        return p
    }

    /// 光弧倍数 → 反馈衰减系数 `k = 0.5^(1/(N−1))`（域外按 2 处理）。
    public var decay: Float {
        let n = Swift.max(multiplier, EchoParams.minMultiplier)
        return pow(0.5, 1.0 / (Float(n) - 1.0))
    }
}

// MARK: - 色调四滑条

/// 色调四滑条（复合串键 `tone_params`，`black white shadows highlights` 空格分隔）。
public struct ToneParams: Equatable {
    public var black: Float
    public var white: Float
    public var shadows: Float
    public var highlights: Float

    public init(black: Float = 0, white: Float = 0, shadows: Float = 0, highlights: Float = 0) {
        self.black = black
        self.white = white
        self.shadows = shadows
        self.highlights = highlights
    }

    public static let blackMax: Float = 0.35
    public static let whiteMax: Float = 0.35
    public static let shadowGain: Float = 0.25
    public static let highlightGain: Float = 0.25
    public static let identity = ToneParams()

    public var isIdentity: Bool {
        return black == 0 && white == 0 && shadows == 0 && highlights == 0
    }

    public func sanitized() -> ToneParams {
        return ToneParams(
            black: ToneParams.clampRange(black, ToneParams.blackMax),
            white: ToneParams.clampRange(white, ToneParams.whiteMax),
            shadows: ToneParams.clampRange(shadows, 1),
            highlights: ToneParams.clampRange(highlights, 1)
        )
    }

    static func clampRange(_ value: Float, _ maxValue: Float) -> Float {
        if value.isNaN { return 0 }
        return Swift.min(Swift.max(value, -maxValue), maxValue)
    }
}

// MARK: - RGB 曲线

/// 单条 RGB 曲线（复合串键 `curve_stack` 的 `x:y` 段）。
public struct ColorCurve: Equatable {
    public struct Point: Equatable {
        public var x: Float
        public var y: Float
        public init(x: Float, y: Float) {
            self.x = x
            self.y = y
        }
    }

    public var points: [Point]

    public init(points: [Point] = []) {
        self.points = points
    }

    public static let tableSize = 256
    public static let channelMaster = 0
    public static let channelRed = 1
    public static let channelGreen = 2
    public static let channelBlue = 3
    public static let identity = ColorCurve()

    /// 是否等价于直通：无控制点，或所有控制点都落在 y=x 上。
    public var isIdentity: Bool { return points.allSatisfy { $0.x == $0.y } }

    /// 分段线性插值（控制点按 x 升序；首点之前/末点之后钳制，不外推）。
    public func evaluate(_ x: Float) -> Float {
        return ColorCurve.sample(points, x)
    }

    public static func sample(_ points: [Point], _ x: Float) -> Float {
        if points.isEmpty { return clamp01(x) }
        if points.count == 1 { return clamp01(points[0].y) }
        let v = clamp01(x)
        if v <= points[0].x { return clamp01(points[0].y) }
        if v >= points[points.count - 1].x { return clamp01(points[points.count - 1].y) }
        for i in 0..<(points.count - 1) {
            let a = points[i]
            let b = points[i + 1]
            if v >= a.x && v <= b.x {
                let span = b.x - a.x
                if span <= 0 { return clamp01(b.y) }
                return clamp01(a.y + (b.y - a.y) * ((v - a.x) / span))
            }
        }
        return v
    }

    /// 烘焙成 256 档查表（索引 i 对应输入 i/(size-1)）。
    public func table(size: Int = ColorCurve.tableSize) -> [Float] {
        var out: [Float] = []
        out.reserveCapacity(size)
        let denom = Float(size - 1)
        for i in 0..<size {
            out.append(evaluate(Float(i) / denom))
        }
        return out
    }

    static func clamp01(_ value: Float) -> Float {
        if value < 0 { return 0 }
        if value > 1 { return 1 }
        return value
    }
}

/// 四条曲线的组合（白 / 红 / 绿 / 蓝），键 `curve_stack` 用 `;` 分隔四段。
public struct CurveStack: Equatable {
    public var master: ColorCurve
    public var red: ColorCurve
    public var green: ColorCurve
    public var blue: ColorCurve

    public init(
        master: ColorCurve = .identity,
        red: ColorCurve = .identity,
        green: ColorCurve = .identity,
        blue: ColorCurve = .identity
    ) {
        self.master = master
        self.red = red
        self.green = green
        self.blue = blue
    }

    public static let identity = CurveStack()

    public var isPassthrough: Bool {
        return master.isIdentity && red.isIdentity && green.isIdentity && blue.isIdentity
    }

    public func curve(ofChannel channel: Int) -> ColorCurve {
        switch channel {
        case ColorCurve.channelRed: return red
        case ColorCurve.channelGreen: return green
        case ColorCurve.channelBlue: return blue
        default: return master
        }
    }

    public func withCurve(_ channel: Int, _ curve: ColorCurve) -> CurveStack {
        var copy = self
        switch channel {
        case ColorCurve.channelRed: copy.red = curve
        case ColorCurve.channelGreen: copy.green = curve
        case ColorCurve.channelBlue: copy.blue = curve
        default: copy.master = curve
        }
        return copy
    }
}
