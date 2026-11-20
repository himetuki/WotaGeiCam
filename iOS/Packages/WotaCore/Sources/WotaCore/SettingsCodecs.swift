import Foundation

/// 复合串键的**编解码纯函数**（对齐 Android `encodeGlow`/`decodeGlow`、`encodeEcho`/`decodeEcho`、
/// `ToneParams.encode`/`decode`、`ColorCurve`/`CurveStack`、`encodeContinuousSliders`）。
///
/// 统一容错口径：**任何一处不认识/解析失败都整份回默认，绝不抛**。
/// 未知键忽略（前向兼容：以后加参数时老版本读新串不炸）。
public enum WotaSettingsCodecs {

    // MARK: - Glow（`glow_params`）

    /// Glow 参数 → 单串（`key=value` 用 `;` 连接，键序固定；写前先 sanitize）。
    public static func encodeGlow(_ params: GlowParams) -> String {
        let p = params.sanitized()
        var s = ""
        s += "enabled=" + (p.enabled ? "1" : "0")
        s += ";threshold=" + floatLiteral(p.threshold)
        s += ";soft=" + floatLiteral(p.soft)
        s += ";width=" + floatLiteral(p.width)
        s += ";gain=" + floatLiteral(p.gain)
        s += ";add=" + floatLiteral(p.add)
        s += ";freq=" + floatLiteral(p.freq)
        s += ";blend=" + floatLiteral(p.blend)
        s += ";color=" + hexLiteral(p.color)
        s += ";coloronly=" + (p.colorOnly ? "1" : "0")
        return s
    }

    /// 单串 → Glow 参数；任何解析失败整串回默认。
    public static func decodeGlow(_ raw: String?) -> GlowParams {
        guard let raw = raw, !isBlank(raw) else { return GlowParams.default }
        let values = kvSegments(raw)
        guard let enabledRaw = values["enabled"] else { return GlowParams.default }
        let enabled: Bool
        if enabledRaw == "1" { enabled = true } else if enabledRaw == "0" { enabled = false } else { return GlowParams.default }
        guard let threshold = floatValue(values["threshold"]),
              let soft = floatValue(values["soft"]),
              let width = floatValue(values["width"]),
              let gain = floatValue(values["gain"]),
              let add = floatValue(values["add"]),
              let freq = floatValue(values["freq"]),
              let blend = floatValue(values["blend"]) else {
            return GlowParams.default
        }
        let color: UInt32
        if let colorRaw = values["color"] {
            guard let parsed = colorValue(colorRaw) else { return GlowParams.default }
            color = parsed
        } else {
            color = GlowParams.defaultColor
        }
        let colorOnly: Bool
        if let colorOnlyRaw = values["coloronly"] {
            if colorOnlyRaw == "1" { colorOnly = true } else if colorOnlyRaw == "0" { colorOnly = false } else { return GlowParams.default }
        } else {
            colorOnly = false
        }
        return GlowParams(
            enabled: enabled, threshold: threshold, soft: soft, width: width, gain: gain,
            add: add, freq: freq, blend: blend, colorOnly: colorOnly, color: color
        ).sanitized()
    }

    // MARK: - Echo（`echo_params`）

    public static func encodeEcho(_ params: EchoParams) -> String {
        let p = params.sanitized()
        var s = ""
        s += "enabled=" + (p.enabled ? "1" : "0")
        s += ";mode=" + p.mode.rawValue
        s += ";multiplier=" + String(p.multiplier)
        s += ";threshold=" + floatLiteral(p.threshold)
        s += ";gain=" + floatLiteral(p.gain)
        s += ";tcm=" + p.trailColorMode.rawValue
        s += ";tc=" + hexLiteral(p.trailColor)
        s += ";ttol=" + floatLiteral(p.trailTol)
        return s
    }

    public static func decodeEcho(_ raw: String?) -> EchoParams {
        guard let raw = raw, !isBlank(raw) else { return EchoParams.default }
        let values = kvSegments(raw)
        guard let enabledRaw = values["enabled"] else { return EchoParams.default }
        let enabled: Bool
        if enabledRaw == "1" { enabled = true } else if enabledRaw == "0" { enabled = false } else { return EchoParams.default }

        guard let modeRaw = values["mode"] else { return EchoParams.default }
        let mode: EchoMode
        if modeRaw == EchoMode.keepBrightness.rawValue { mode = .keepBrightness }
        else if modeRaw == EchoMode.trail.rawValue { mode = .trail }
        else { return EchoParams.default }

        guard let multiplierRaw = values["multiplier"], let multiplier = Int(multiplierRaw) else { return EchoParams.default }
        guard let threshold = floatValue(values["threshold"]) else { return EchoParams.default }
        guard let gain = floatValue(values["gain"]) else { return EchoParams.default }

        let trailColorMode: EchoTrailColorMode
        if let tcmRaw = values["tcm"] {
            guard let parsed = EchoTrailColorMode(rawValue: tcmRaw) else { return EchoParams.default }
            trailColorMode = parsed
        } else {
            trailColorMode = .off
        }
        let trailColor: UInt32
        if let tcRaw = values["tc"] {
            guard let parsed = colorValue(tcRaw) else { return EchoParams.default }
            trailColor = parsed
        } else {
            trailColor = EchoParams.defaultTrailColor
        }
        let trailTol: Float
        if let ttolRaw = values["ttol"] {
            guard let parsed = floatValue(ttolRaw) else { return EchoParams.default }
            trailTol = parsed
        } else {
            trailTol = 0.3
        }
        return EchoParams(
            enabled: enabled, mode: mode, multiplier: multiplier, threshold: threshold, gain: gain,
            trailColorMode: trailColorMode, trailColor: trailColor, trailTol: trailTol
        ).sanitized()
    }

    // MARK: - Tone（`tone_params`）

    public static func encodeTone(_ params: ToneParams) -> String {
        let p = params.sanitized()
        return floatLiteral(p.black) + " " + floatLiteral(p.white) + " "
            + floatLiteral(p.shadows) + " " + floatLiteral(p.highlights)
    }

    public static func decodeTone(_ raw: String?) -> ToneParams {
        guard let raw = raw, !isBlank(raw) else { return ToneParams.identity }
        let segments = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: " ")
            .compactMap { Float($0) }
        guard segments.count == 4 else { return ToneParams.identity }
        let p = ToneParams(black: segments[0], white: segments[1], shadows: segments[2], highlights: segments[3]).sanitized()
        let total = abs(p.black) + abs(p.white) + abs(p.shadows) + abs(p.highlights)
        return total == 0 ? ToneParams.identity : p
    }

    // MARK: - ColorCurve / CurveStack（`curve_stack`）

    public static func encodeCurve(_ curve: ColorCurve) -> String {
        return curve.points.map { fixed3($0.x) + ":" + fixed3($0.y) }.joined(separator: ",")
    }

    public static func decodeCurve(_ raw: String?) -> ColorCurve {
        guard let raw = raw, !raw.isEmpty else { return ColorCurve.identity }
        var points: [ColorCurve.Point] = []
        for segment in raw.components(separatedBy: ",") {
            let kv = segment.components(separatedBy: ":")
            if kv.count != 2 { continue }
            guard let x = Float(kv[0]), let y = Float(kv[1]) else { continue }
            if !x.isFinite || !y.isFinite { continue }
            points.append(ColorCurve.Point(x: ColorCurve.clamp01(x), y: ColorCurve.clamp01(y)))
        }
        return ColorCurve(points: points.sorted { $0.x < $1.x })
    }

    public static func encodeCurveStack(_ stack: CurveStack) -> String {
        return [stack.master, stack.red, stack.green, stack.blue]
            .map { encodeCurve($0) }
            .joined(separator: ";")
    }

    public static func decodeCurveStack(_ raw: String?) -> CurveStack {
        guard let raw = raw, !raw.isEmpty else { return CurveStack.identity }
        let segments = raw.components(separatedBy: ";")
        guard segments.count == 4 else { return CurveStack.identity }
        return CurveStack(
            master: decodeCurve(segments[0]),
            red: decodeCurve(segments[1]),
            green: decodeCurve(segments[2]),
            blue: decodeCurve(segments[3])
        )
    }

    // MARK: - 滑杆吸附（`slider_continuous`）

    /// 连续值集合 → 串：按声明顺序、逗号分隔（同一集合恒得同一串）。
    public static func encodeContinuousSliders(_ keys: Set<SliderKey>) -> String {
        return SliderKey.allCases.filter { keys.contains($0) }.map { $0.rawValue }.joined(separator: ",")
    }

    /// 串 → 集合：空/坏串 → 空集（= 全部吸附），未知 id 忽略。
    public static func decodeContinuousSliders(_ raw: String?) -> Set<SliderKey> {
        guard let raw = raw, !isBlank(raw) else { return [] }
        let byId = Dictionary(uniqueKeysWithValues: SliderKey.allCases.map { ($0.rawValue, $0) })
        var out: Set<SliderKey> = []
        for piece in raw.components(separatedBy: ",") {
            let key = piece.trimmingCharacters(in: .whitespacesAndNewlines)
            if let slider = byId[key] { out.insert(slider) }
        }
        return out
    }

    // MARK: - 内部工具

    /// `key=value;` 串 → 字典。跳过没有键（`=` 在首位）的片段；键值各自 trim。
    private static func kvSegments(_ raw: String) -> [String: String] {
        var values: [String: String] = [:]
        for pair in raw.components(separatedBy: ";") {
            guard let eq = pair.firstIndex(of: "=") else { continue }
            if eq == pair.startIndex { continue }
            let key = pair[pair.startIndex..<eq].trimmingCharacters(in: .whitespacesAndNewlines)
            let value = pair[pair.index(after: eq)...].trimmingCharacters(in: .whitespacesAndNewlines)
            values[key] = value
        }
        return values
    }

    private static func floatValue(_ raw: String?) -> Float? {
        guard let raw = raw, let value = Float(raw) else { return nil }
        return value
    }

    private static func colorValue(_ raw: String) -> UInt32? {
        return UInt32(raw, radix: 16)
    }

    private static func isBlank(_ value: String) -> Bool {
        return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 浮点字面量：与 Kotlin `Float.toString()` 的「最短可往返」口径一致。
    private static func floatLiteral(_ value: Float) -> String {
        return String(value)
    }

    /// 8 位 ARGB 大写十六进制（无补零，与 Kotlin `Long.toString(16).uppercase()` 逐字一致）。
    private static func hexLiteral(_ value: UInt32) -> String {
        return String(value, radix: 16, uppercase: true)
    }

    /// `%.3f`，固定 `en_US_POSIX`（等效 Android `String.format(Locale.US, "%.3f", v)`）。
    private static func fixed3(_ value: Float) -> String {
        return String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), value)
    }
}
