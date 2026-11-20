import Foundation

// MARK: - 回波（残影 / 光弧拖尾）参数模型
//
// 纯逻辑，无 UIKit / AVFoundation / 图形 API 依赖：实时取景、成片录制与离线导出三条链
// 共用同一份语义。算法证据来自 Android 只读参考（**不翻译代码，只对照语义**）：
//   - `Android/.../core/Echo.kt`（EchoParams / EchoMode / EchoTrailColorMode / echoDecayOf …），
//   - `Android/.../camera/Shaders.kt`（ECHO_ACCUM_FS / ECHO_DECL / SCENE_ECHO_ACCUM_FS /
//     SCENE_COMPOSITE_ECHO_FS，双模式累积与合成算式），
//   - `Android/.../camera/EchoChain.kt`（单张反馈累积面 + 乒乓，方案 B），
//   - `Android/.../player/SceneExportGl.kt`（离线同一算式、全分辨率逐帧顺序），
//   - `docs/research/R2-高光拖影可行性.md`、`docs/plan/35-拖尾色盘四模式补丁方案.md`。
//
// iOS 侧把这些量组织成协议/参数模型/累积纹理/可选后端；**不复用** Android 的 GL/GLSL 文本。

/// 回波合成模式。
///
/// 对照 Android `core/Echo.kt` 的 `EchoMode`：
/// - ``keepBrightness`` = `KEEP_BRIGHTNESS`：「保持整体亮度不变」＝逐像素取最大（星轨叠加法）。
///   静态背景完全不动、只有光源画弧，天然免疫背景提亮；出厂默认（夜间练习推荐档）。
/// - ``trail`` = `TRAIL`：「提亮拖尾」＝只让亮度过阈的高光像素进累积面，主 pass 加法叠回，
///   彗尾观感。
public enum RenderEchoMode: String, Equatable, Sendable {
    case keepBrightness
    case trail

    /// Android 持久化键里使用的名字（`EchoMode.name`），decode 时按此匹配。
    public var androidName: String {
        switch self {
        case .keepBrightness: return "KEEP_BRIGHTNESS"
        case .trail: return "TRAIL"
        }
    }

    public init?(androidName: String) {
        switch androidName {
        case "KEEP_BRIGHTNESS": self = .keepBrightness
        case "TRAIL": self = .trail
        default: return nil
        }
    }
}

/// 拖尾颜色补丁模式（仅 ``RenderEchoMode/trail`` 档参与；`KEEP_BRIGHTNESS` 档恒不生效）。
///
/// 对照 Android `core/Echo.kt` 的 `EchoTrailColorMode` 与 `docs/plan/35`：
/// - ``off``：补丁关（出厂；拖尾保持像素自身颜色）。
/// - ``add``：加色——合成趟 `e *= tint`（白＝中性元）。
/// - ``subtract``：减色——合成趟 `e *= (1 − tint)`。
/// - ``only``：择色——累积趟只让色度接近 `trailColor` 的像素进累积面。
/// - ``except``：除色——累积趟只让色度**不**接近 `trailColor` 的像素进累积面。
public enum RenderTrailColorMode: String, Equatable, Sendable {
    case off
    case add
    case subtract
    case only
    case except

    public var androidName: String {
        switch self {
        case .off: return "OFF"
        case .add: return "ADD"
        case .subtract: return "SUBTRACT"
        case .only: return "ONLY"
        case .except: return "EXCEPT"
        }
    }

    public init?(androidName: String) {
        switch androidName {
        case "OFF": self = .off
        case "ADD": self = .add
        case "SUBTRACT": self = .subtract
        case "ONLY": self = .only
        case "EXCEPT": self = .except
        default: return nil
        }
    }
}

/// 光弧倍数域：2 = 拍屏 1 次（2 倍光弧，推荐）；10 = 封顶。
///
/// 依据 `docs/research/R2 §四.3`：B 站脚本放开到 100× 是实锤翻车经验（高倍画面成糊）。
public let renderEchoMultiplierMin: Int = 2
public let renderEchoMultiplierMax: Int = 10

/// TRAIL 高光阈值域 60%..100%（`0.6...1.0`）。低于此段的背景不进累积面。
public let renderEchoThresholdMin: Float = 0.6
public let renderEchoThresholdMax: Float = 1.0

/// TRAIL 加法叠回强度上限（2 倍封顶，再大整段过曝）。
public let renderEchoGainMax: Float = 2.0

/// 32 位 ARGB 掩码（颜色是 8 位四通道，超出部分是脏数据）。
public let renderARGBMask: UInt32 = 0xFFFF_FFFF

/// 出厂拖尾补丁色：白＝中性元。
public let renderTrailColorDefault: UInt32 = 0xFFFF_FFFF

/// 择/除色度容差域：0.05（严格）..0.8（宽松）；软边固定 0.1 在着色器/核里。
public let renderTrailToleranceMin: Float = 0.05
public let renderTrailToleranceMax: Float = 0.8

/// 回波（残影 / 光弧拖尾）参数的唯一真源（纯逻辑）。
///
/// 形状参数（模式 / 倍数 / 阈值 / 强度 / 拖尾色）全部走 uniform，改它们不重建程序；
/// `isEnabled` 决定链是否运行（等价于 Android `ProgramKey` 的 `echo` 一维）。
///
/// 实时取景（DISPLAY 语义）、成片录制（MOVIE 语义）与离线导出**共享本结构体与其纯函数**，
/// 但三路的调用代码（`Camera` / `Recording` / `Player`）各自独立。
public struct RenderEffectParameters: Equatable, Sendable {

    /// 开关。关闭时后端零调用（对照 Android「零代价口径」）。
    public var isEnabled: Bool
    /// 合成模式。出厂 ``RenderEchoMode/keepBrightness``。
    public var mode: RenderEchoMode
    /// 光弧倍数 2..10（拍屏 N−1 次 = N 倍）；换算成反馈衰减见 ``feedbackDecay``。
    public var multiplier: Int
    /// TRAIL 高光阈值 0.6..1.0；择/除按 RGB 峰值判亮，其余按 BT.601 亮度判亮。
    public var highlightThreshold: Float
    /// TRAIL 拖尾强度 0..2（合成趟加法叠回的倍数）。
    public var trailGain: Float
    /// 拖尾颜色补丁模式（仅 TRAIL 档参与）。
    public var trailColorMode: RenderTrailColorMode
    /// 补丁色（32 位 ARGB）：加/减的目标色、择/除的色度距离目标。
    public var trailColor: UInt32
    /// 择/除的归一化 RGB 色距容差 0.05..0.8。
    public var trailTolerance: Float

    public init(
        isEnabled: Bool = false,
        mode: RenderEchoMode = .keepBrightness,
        multiplier: Int = 2,
        highlightThreshold: Float = 0.9,
        trailGain: Float = 1.0,
        trailColorMode: RenderTrailColorMode = .off,
        trailColor: UInt32 = renderTrailColorDefault,
        trailTolerance: Float = 0.3
    ) {
        self.isEnabled = isEnabled
        self.mode = mode
        self.multiplier = multiplier
        self.highlightThreshold = highlightThreshold
        self.trailGain = trailGain
        self.trailColorMode = trailColorMode
        self.trailColor = trailColor
        self.trailTolerance = trailTolerance
    }

    /// 出厂默认（关；等价 Android `EchoParams.DEFAULT`）。
    public static let `default` = RenderEffectParameters()

    // MARK: 派生量（反馈衰减 / 后向窗口）

    /// 后向窗口长度（帧）：累积面在第 t 帧隐含「当前帧 + 前 N−1 帧的历史回波」，
    /// 离线可据此预读；实时只用过去帧（前向等价）。等于 ``multiplier``。
    public var backwardWindowFrames: Int {
        return clampedMultiplier
    }

    /// 反馈衰减系数 k = 0.5^(1/(N−1))，见 ``renderEchoDecay(multiplier:)``。
    public var feedbackDecay: Float {
        return renderEchoDecay(multiplier: multiplier)
    }

    /// 钳到域内的倍数。
    public var clampedMultiplier: Int {
        if multiplier < renderEchoMultiplierMin { return renderEchoMultiplierMin }
        if multiplier > renderEchoMultiplierMax { return renderEchoMultiplierMax }
        return multiplier
    }

    // MARK: 域钳（幂等）

    /// 域钳之后的同一份参数（幂等）。落盘/下发前必须过这一关。
    public func sanitized() -> RenderEffectParameters {
        var copy = self
        copy.multiplier = clampedMultiplier
        copy.highlightThreshold = renderClamp(highlightThreshold, renderEchoThresholdMin, renderEchoThresholdMax, 0.9)
        copy.trailGain = renderClamp(trailGain, 0, renderEchoGainMax, 1.0)
        copy.trailColor = trailColor & renderARGBMask
        copy.trailTolerance = renderClamp(trailTolerance, renderTrailToleranceMin, renderTrailToleranceMax, 0.3)
        return copy
    }
}

// MARK: - 纯函数（三链同源，后端每帧只做查表）

/// 光弧倍数 → 反馈衰减系数。
///
/// `k = 0.5^(1/(N−1))`：第 N−1 帧前的回波权重恰好降到一半，拖尾有效长度随 N 单调变长
/// （N=2 → 0.5，N=3 → ≈0.707，N=4 → ≈0.794，N=10 → ≈0.926）。
/// 域外（<2）按 2 处理：1 倍 = 没有拖影，`isEnabled` 关掉才是正解。
public func renderEchoDecay(multiplier: Int) -> Float {
    let n = multiplier < renderEchoMultiplierMin ? renderEchoMultiplierMin : multiplier
    let exponent = 1.0 / Float(n - 1)
    return powf(0.5, exponent)
}

/// 累积趟源像素门控模式 → 着色器 uniform（0 = 不门控，1 = 择，2 = 除）。
/// `KEEP_BRIGHTNESS` 档恒 0（`docs/plan/35` U1/E7：补丁只活在 TRAIL 档）。
public func renderTrailMaskUniform(_ params: RenderEffectParameters) -> Float {
    guard params.mode == .trail else { return 0 }
    switch params.trailColorMode {
    case .only: return 1
    case .except: return 2
    default: return 0
    }
}

/// 合成趟拖尾染色模式 → 着色器 uniform（0 = 不染色，1 = 加，2 = 减）。KB 档恒 0。
public func renderTrailTintModeUniform(_ params: RenderEffectParameters) -> Float {
    guard params.mode == .trail else { return 0 }
    switch params.trailColorMode {
    case .add: return 1
    case .subtract: return 2
    default: return 0
    }
}

/// 补丁色 → 0..1 三元组（uniform / 核参数用）。只取 RGB，alpha 不参与。
public func renderTrailTintRGB(_ params: RenderEffectParameters) -> (r: Float, g: Float, b: Float) {
    let v = params.trailColor & renderARGBMask
    return (
        Float((v >> 16) & 0xFF) / 255.0,
        Float((v >> 8) & 0xFF) / 255.0,
        Float(v & 0xFF) / 255.0
    )
}

/// 择/除色度容差（uniform 用；sanitize 后取值）。
public func renderTrailTolerance(_ params: RenderEffectParameters) -> Float {
    return params.sanitized().trailTolerance
}

/// 通用域钳：非有限值回退，其余钳到 `[lo, hi]`。
public func renderClamp(_ value: Float, _ lo: Float, _ hi: Float, _ fallback: Float) -> Float {
    if !value.isFinite { return fallback }
    return min(max(value, lo), hi)
}

// MARK: - 复合串持久化（与 Android `encodeEcho` / `decodeEcho` 同键序、同纪律）

/// 参数 → 单串持久化键（键序固定：`enabled;mode;multiplier;threshold;gain;tcm;tc;ttol`）。
public func renderEncodeEcho(_ params: RenderEffectParameters) -> String {
    let p = params.sanitized()
    var out = ""
    out += "enabled=" + (p.isEnabled ? "1" : "0")
    out += ";mode=" + p.mode.androidName
    out += ";multiplier=" + String(p.multiplier)
    out += ";threshold=" + renderFloatString(p.highlightThreshold)
    out += ";gain=" + renderFloatString(p.trailGain)
    out += ";tcm=" + p.trailColorMode.androidName
    out += ";tc=" + String(p.trailColor, radix: 16, uppercase: true)
    out += ";ttol=" + renderFloatString(p.trailTolerance)
    return out
}

/// 单串持久化键 → 参数。**任何一处不认识/解析失败都整串回默认**（残影是能改画面的参数，
/// 坏值宁可回出厂也不猜近似值）；缺失的 `tcm`/`tc`/`ttol` 视为出厂（旧串兼容）。
public func renderDecodeEcho(_ raw: String?) -> RenderEffectParameters {
    guard let raw = raw, !raw.isEmpty else { return RenderEffectParameters.default }
    var values: [String: String] = [:]
    for pair in raw.split(separator: ";") {
        guard let eq = pair.firstIndex(of: "=") else { continue }
        let key = String(pair[pair.startIndex..<eq])
        let value = String(pair[pair.index(after: eq)...])
        if !key.isEmpty { values[key.trimmingCharacters(in: .whitespaces)] = value.trimmingCharacters(in: .whitespaces) }
    }
    let enabled: Bool
    switch values["enabled"] {
    case "1": enabled = true
    case "0": enabled = false
    default: return RenderEffectParameters.default
    }
    guard let modeRaw = values["mode"], let mode = RenderEchoMode(androidName: modeRaw) else {
        return RenderEffectParameters.default
    }
    guard let multiplierRaw = values["multiplier"], let multiplier = Int(multiplierRaw) else {
        return RenderEffectParameters.default
    }
    guard let thresholdRaw = values["threshold"], let threshold = Float(thresholdRaw) else {
        return RenderEffectParameters.default
    }
    guard let gainRaw = values["gain"], let gain = Float(gainRaw) else {
        return RenderEffectParameters.default
    }
    var colorMode: RenderTrailColorMode = .off
    if let tcmRaw = values["tcm"] {
        guard let parsed = RenderTrailColorMode(androidName: tcmRaw) else {
            return RenderEffectParameters.default
        }
        colorMode = parsed
    }
    var color: UInt32 = renderTrailColorDefault
    if let tcRaw = values["tc"] {
        guard let parsed = UInt32(tcRaw, radix: 16) else { return RenderEffectParameters.default }
        color = parsed & renderARGBMask
    }
    var tolerance: Float = 0.3
    if let ttolRaw = values["ttol"] {
        guard let parsed = Float(ttolRaw) else { return RenderEffectParameters.default }
        tolerance = parsed
    }
    return RenderEffectParameters(
        isEnabled: enabled,
        mode: mode,
        multiplier: multiplier,
        highlightThreshold: threshold,
        trailGain: gain,
        trailColorMode: colorMode,
        trailColor: color,
        trailTolerance: tolerance
    ).sanitized()
}

/// 稳定小数串（避免 `Float` 默认格式在个别区域设置下输出科学计数/逗号）。保留 4 位有效小数。
private func renderFloatString(_ value: Float) -> String {
    let rounded = (value * 10000).rounded() / 10000
    return String(rounded)
}
