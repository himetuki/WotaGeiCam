import Foundation

/// 成片命名口径，与 Android `VideoStore.displayName` 对齐：
/// `VID_yyyyMMdd_HHmmss_%03d.mp4`（`Locale.US` / POSIX，3 位后缀防同秒撞名）。
/// 这里只定义纯命名规则，不负责把文件写入相册或磁盘。
public enum RecordingFileNaming {
    public static let fileExtension = "mp4"
    public static let relativeDirectory = "Movies/WotaGeiCam/Camera"

    /// 生成成片文件名。
    ///
    /// - Parameters:
    ///   - createdAt: 创建时刻；格式按传入时区计算。
    ///   - timeZone: 默认当前时区，与 Android 的 `SimpleDateFormat` 行为一致。
    ///   - suffix: 0...999 的随机后缀；越界值按 1000 取模归一。
    ///   - prefix: 可选前缀，调用方自行带上下划线（如 `30fto24f_`）。
    public static func displayName(
        createdAt: Date,
        timeZone: TimeZone = .current,
        suffix: Int,
        prefix: String = ""
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let stamp = formatter.string(from: createdAt)
        return "\(prefix)VID_\(stamp)_\(String(format: "%03d", normalizedSuffix(suffix))).\(fileExtension)"
    }

    /// 把任意整数归一成 0...999；负数也落到合法三位后缀。
    public static func normalizedSuffix(_ suffix: Int) -> Int {
        let span = 1_000
        let remainder = suffix % span
        return remainder >= 0 ? remainder : remainder + span
    }

    public static func randomSuffix() -> Int {
        Int.random(in: 0...999)
    }

    public static func randomSuffix<R: RandomNumberGenerator>(using generator: inout R) -> Int {
        Int.random(in: 0...999, using: &generator)
    }

    /// 识别本工程产出的成片名；允许 Android 已知的 `NNftoNNf_` 处理前缀。
    public static func isWotaVideoFileName(_ name: String) -> Bool {
        let pattern = #"^(?:[0-9]+fto[0-9]+f_)?VID_[0-9]{8}_[0-9]{6}_[0-9]{3}\.mp4$"#
        return name.range(of: pattern, options: .regularExpression) != nil
    }
}
