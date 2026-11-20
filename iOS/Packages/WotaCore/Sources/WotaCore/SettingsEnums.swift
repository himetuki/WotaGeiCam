import Foundation

/// 设置相关枚举的 iOS 移植。
///
/// **持久化口径与 Android 完全一致**：绝大多数枚举用**枚举常量名**（Android `.name`，大写）存进键；
/// 少数用自定义字面量（降噪用 `key`，方向用 `persistValue`，滑杆用 `id`）。
/// 解析失败一律回默认、**绝不抛**（对齐 Android `ParamsPersist.enumOrDefault` 的「坏串回默认」）。

/// 枚举名解析的统一入口：`raw` 为 `nil` / 空 / 不认识时返回默认值。
public protocol WotaNameParsable: RawRepresentable where RawValue == String {
    static var fallback: Self { get }
}

public extension WotaNameParsable {
    static func parse(name raw: String?) -> Self {
        guard let raw = raw, let value = Self(rawValue: raw) else { return Self.fallback }
        return value
    }
}

// MARK: - 动画风格 / 渲染 / 抽帧

/// 交互动画风格（Android `ui/anim/Motion.kt` `MotionMode`，键 `motion_mode`，默认 FLUENT）。
public enum MotionMode: String, WotaNameParsable {
    case plain = "PLAIN"
    case fluent = "FLUENT"
    case liquid = "LIQUID"
    public static let fallback: MotionMode = .fluent
}

/// 核心渲染模式（Android `core/Constants.kt` `RenderMode`，键 `default_render_mode`，默认 GPU）。
public enum RenderMode: String, WotaNameParsable {
    case gpu = "GPU"
    case direct = "DIRECT"
    public static let fallback: RenderMode = .gpu
}

/// 强制 24/25fps 无原生精确档时的录制期转换模式（键 `arc_convert_mode`，默认 MEND）。
public enum ArcConvertMode: String, WotaNameParsable {
    case mend = "MEND"
    case drop = "DROP"
    public static let fallback: ArcConvertMode = .mend
}

// MARK: - 相机参数枚举

/// AE 模式。**LOCK 是瞬时态**：写/恢复 `default_ae_mode` 时一律归一为 AUTO（对齐 `ParamsPersist.persistedAe`）。
public enum AeMode: String, WotaNameParsable {
    case auto = "AUTO"
    case manual = "MANUAL"
    case lock = "LOCK"
    public static let fallback: AeMode = .auto

    /// LOCK 归一：落盘 / 恢复前调用。
    public var persisted: AeMode { self == .lock ? .auto : self }
}

/// AF 模式（键 `default_af_mode`，默认 CONTINUOUS_VIDEO）。`cam2Mode` 保留 Android 的 Camera2 映射值，
/// 仅作跨端语义标注，iOS 侧用 `AVCaptureDevice.FocusMode` 另行适配。
public enum AfMode: String, WotaNameParsable {
    case continuousVideo = "CONTINUOUS_VIDEO"
    case continuousPicture = "CONTINUOUS_PICTURE"
    case macro = "MACRO"
    case manual = "MANUAL"
    public static let fallback: AfMode = .continuousVideo

    public var cam2Mode: Int {
        switch self {
        case .continuousVideo: return 3
        case .continuousPicture: return 4
        case .macro: return 2
        case .manual: return 0
        }
    }

    /// 换挡回填优先级：录像连续优先，能力缺失时依次降级。
    public static let preference: [AfMode] = [.continuousVideo, .continuousPicture, .macro, .manual]
}

/// AWB 预设（键 `default_wb_preset`，默认 AUTO）。`awbMode` 保留 Android 的 AWB_MODE 取值语义。
public enum WbPreset: String, WotaNameParsable {
    case auto = "AUTO"
    case incandescent = "INCANDESCENT"
    case fluorescent = "FLUORESCENT"
    case warmFluorescent = "WARM_FLUORESCENT"
    case daylight = "DAYLIGHT"
    case cloudyDaylight = "CLOUDY_DAYLIGHT"
    case twilight = "TWILIGHT"
    case shade = "SHADE"
    case manual = "MANUAL"
    public static let fallback: WbPreset = .auto

    public var awbMode: Int {
        switch self {
        case .manual: return 0
        case .auto: return 1
        case .incandescent: return 2
        case .fluorescent: return 3
        case .warmFluorescent: return 4
        case .daylight: return 5
        case .cloudyDaylight: return 6
        case .twilight: return 7
        case .shade: return 8
        }
    }
}

public enum Flash: String, WotaNameParsable {
    case off = "OFF"
    case on = "ON"
    case auto = "AUTO"
    case torch = "TORCH"
    public static let fallback: Flash = .off
}

public enum Stabilize: String, WotaNameParsable {
    case off = "OFF"
    case ois = "OIS"
    case eis = "EIS"
    case oisEis = "OIS_EIS"
    public static let fallback: Stabilize = .off
}

/// 单效果槽：监看类效果互斥。
public enum FrameEffect: String, WotaNameParsable {
    case none = "NONE"
    case zebra = "ZEBRA"
    case peaking = "PEAKING"
    public static let fallback: FrameEffect = .none
}

public enum PeakingColor: String, WotaNameParsable {
    case white = "WHITE"
    case green = "GREEN"
    case blue = "BLUE"
    case orange = "ORANGE"
    case red = "RED"
    public static let fallback: PeakingColor = .red
}

/// 镜头槽位（键 `default_lens`，默认 WIDE）。是**产品枚举**，不是 cameraId。
public enum LensType: String, WotaNameParsable {
    case superWide = "SUPER_WIDE"
    case wide = "WIDE"
    case telephoto = "TELEPHOTO"
    case superTelephoto = "SUPER_TELEPHOTO"
    case auto = "AUTO"
    case front = "FRONT"
    public static let fallback: LensType = .wide
}

/// 上屏渲染比例（键 `display_scale`，默认 ORIG）。`divisor` 即上屏离屏 RT 的除数。
public enum DisplayScale: String, WotaNameParsable {
    case orig = "ORIG"
    case half = "HALF"
    case quarter = "QUARTER"
    case eighth = "EIGHTH"
    case sixteenth = "SIXTEENTH"
    public static let fallback: DisplayScale = .orig

    public var divisor: Int {
        switch self {
        case .orig: return 1
        case .half: return 2
        case .quarter: return 4
        case .eighth: return 8
        case .sixteenth: return 16
        }
    }

    public static let all: [DisplayScale] = [.orig, .half, .quarter, .eighth, .sixteenth]

    public static func fromDivisor(_ divisor: Int) -> DisplayScale {
        return all.first(where: { $0.divisor == divisor }) ?? .orig
    }
}

/// 站位线色板（键 `default_marker_color`，默认 YELLOW）。`argb` 为 8 位四通道 ARGB。
public enum MarkerLineColor: String, WotaNameParsable {
    case yellow = "YELLOW"
    case cyan = "CYAN"
    case magenta = "MAGENTA"
    case green = "GREEN"
    case white = "WHITE"
    case orange = "ORANGE"
    public static let fallback: MarkerLineColor = .yellow

    public var argb: UInt32 {
        switch self {
        case .yellow: return 0xFFE6C700
        case .cyan: return 0xFF35D6E0
        case .magenta: return 0xFFE64AC8
        case .green: return 0xFF4CE05A
        case .white: return 0xFFF2F2F2
        case .orange: return 0xFFFF8A1E
        }
    }

    public static let all: [MarkerLineColor] = [.yellow, .cyan, .magenta, .green, .white, .orange]

    /// 越界序号回黄（对齐 Android `MarkerLineColor.fromOrdinal`）。
    public static func fromOrdinal(_ value: Int) -> MarkerLineColor {
        guard value >= 0 && value < all.count else { return .yellow }
        return all[value]
    }
}

// MARK: - 降噪（`key` 字面量持久化）

/// 音频降噪档（键 `denoise_audio`，坏串回 OFF）。
public enum AudioDenoise: String, WotaNameParsable {
    case off = "off"
    case system = "system"
    public static let fallback: AudioDenoise = .off
}

/// 画面降噪档（键 `denoise_video`，坏串回 OFF）。
public enum VideoDenoise: String, WotaNameParsable {
    case off = "off"
    case halFast = "hal_fast"
    case halHighQuality = "hal_hq"
    case gpuBilateral = "gpu_bilateral"
    public static let fallback: VideoDenoise = .off
}

// MARK: - 界面方向

/// 录制页默认方向（键 `ui_orientation`，默认 LANDSCAPE）。
/// Android 用 `persistValue`（小写）持久化，且解析**大小写不敏感**。
public enum UIOrientation: String, WotaNameParsable {
    case landscape = "landscape"
    case portrait = "portrait"
    public static let fallback: UIOrientation = .landscape

    public static let all: [UIOrientation] = [.landscape, .portrait]

    /// 大小写不敏感的解析（对齐 Android `UIOrientation.fromPersistValue`）。
    public static func fromPersistValue(_ raw: String?) -> UIOrientation {
        guard let raw = raw else { return .fallback }
        let lowered = raw.lowercased()
        return all.first(where: { $0.rawValue == lowered }) ?? .fallback
    }
}

// MARK: - Glow / Echo 复合串里的枚举

/// 残影合成模式（`echo_params` 的 `mode` 段）。
public enum EchoMode: String, WotaNameParsable {
    case keepBrightness = "KEEP_BRIGHTNESS"
    case trail = "TRAIL"
    public static let fallback: EchoMode = .keepBrightness
}

/// 残影拖尾颜色补丁模式（`echo_params` 的 `tcm` 可选段，缺省 OFF）。
public enum EchoTrailColorMode: String, WotaNameParsable {
    case off = "OFF"
    case add = "ADD"
    case subtract = "SUBTRACT"
    case only = "ONLY"
    case except = "EXCEPT"
    public static let fallback: EchoTrailColorMode = .off
}

// MARK: - 滑杆吸附

/// 可切换「连续值」的滑杆参数（键 `slider_continuous`，`id` 是持久化契约，只许追加不许改名）。
public enum SliderKey: String, CaseIterable {
    case shutter = "shutter"
    case iso = "iso"
    case kelvin = "kelvin"
    case tint = "tint"
    case zoom = "zoom"
    case diopter = "diopter"
    case peaking = "peaking"
}

// MARK: - HUD 位掩码

/// 取景器常驻读数（键 `hud_items`，位掩码；与 `CamPill` 分两套）。
public enum HudItem: Int, CaseIterable {
    case shutter = 1
    case fps = 2
    case bitrate = 4
    case iso = 8
    case ev = 16
    case wb = 32
    case zoom = 64

    /// 出厂默认上屏四档：快门 / 帧率 / 码率 / 感光度。
    public static let defaultMask: Int = HudItem.shutter.rawValue | HudItem.fps.rawValue
        | HudItem.bitrate.rawValue | HudItem.iso.rawValue

    public static func items(mask: Int) -> [HudItem] {
        return allCases.filter { mask & $0.rawValue != 0 }
    }
}

/// 录制页可控胶囊（键 `hud_pills`，位掩码；默认全开 − TONE − ECHO）。
public enum CamPill: Int, CaseIterable {
    case level = 1
    case volume = 2
    case audio = 4
    case zoom = 8
    case focus = 16
    case stab = 32
    case refLine = 64
    case monitor = 128
    case curve = 256
    case flash = 512
    case lens = 1024
    case size = 2048
    case storage = 4096
    case denoise = 8192
    case glow = 16384
    case tone = 32768
    case echo = 65536

    /// 出厂显隐掩码：全开 − TONE − ECHO（这两颗默认关，其余维持「默认全开」）。
    public static let defaultMask: Int = {
        var mask = 0
        for pill in allCases { mask |= pill.rawValue }
        return mask & ~tone.rawValue & ~echo.rawValue
    }()

    /// 显隐位清单的版本号：每加一颗新胶囊就 +1。
    public static let maskSchema = 4

    /// 「这一位是在哪一版加进来的」：升级时只补这些位。
    /// TONE（schema 3）/ ECHO（schema 4）刻意不在表里（默认关，升级不自动打开）。
    public static let addedInSchema: [Int: Int] = [
        1: CamPill.denoise.rawValue,
        2: CamPill.glow.rawValue,
    ]

    /// 把 `fromSchema` 版的掩码升级到当前版：只 OR 进该版之后新加的位（纯函数，幂等）。
    public static func migrateMask(_ mask: Int, fromSchema: Int) -> Int {
        var result = mask
        if fromSchema < maskSchema {
            var version = fromSchema + 1
            while version <= maskSchema {
                result |= addedInSchema[version] ?? 0
                version += 1
            }
        }
        return result
    }

    /// 掩码里「没置位」的那些就是要隐藏的胶囊。
    public static func hidden(mask: Int) -> [CamPill] {
        return allCases.filter { mask & $0.rawValue == 0 }
    }
}

/// 参考线类型（键 `default_ref_lines` 的位掩码；仅作位语义标注，恢复侧不解析）。
public enum RefLineType: Int, CaseIterable {
    case grid3x3 = 1
    case diagonal = 2
    case crosshair = 4
    case broadcastSafe = 8
    case safeArea = 16
    case grid = 32
    case centerMark = 64
    case horizonLine = 128
    case redTop = 256
    case redMid = 512
    case redBottom = 1024
}
