import Foundation

/// 可随备份迁移的一个设置值（对应 Android `PortableUserData` 里的 `Boolean/Int/Long/Float/String` 五态）。
public enum WotaBackupValue: Equatable {
    case bool(Bool)
    case int(Int)
    case int64(Int64)
    case float(Float)
    case string(String)
}

/// 用户数据备份：**显式导出/导入的数据接口**，无自动联网、无自动上传。
///
/// 对齐 Android `backup/PortableUserData.kt`：
/// - 文件名 `wota_user_backup.bin`、`VERSION = 1`、上限 4MiB、字符串上限 64KiB；
/// - 键白名单（`WotaSettingsKeys.backupKeys`，**剔除 AI 站位键**），新增设置须显式评估能否迁移；
/// - **加法恢复**：只补目标设备当前不存在的键，绝不覆盖已存在的 UserDefaults（含 `false`/`0`）；
/// - 二进制与 Android 互通：大端 `Int32`/`Int64`/`Float32`，字符串 = 长度 + UTF-8 字节。
///
/// 大小/时长/时间戳等元数据用 `Int64`（iOS 10 目标下不引 `Codable`，也不依赖 32 位整型的位宽假设）。
public enum WotaUserBackup {

    public static let fileName = "wota_user_backup.bin"
    public static let version = 1
    public static let maxBytes = 4 * 1024 * 1024
    public static let maxTags = 10_000
    public static let maxStringBytes = 65_536
    public static let maxTagValueChars = 128

    /// 读取时允许的设置条目上限。用**跨端**白名单口径（Android `SETTING_KEYS` 含 6 个 AI 键 = 63），
    /// 这样 Android 生成的备份也能被 iOS 读入（AI 键在 `settingsOf` 阶段被剔除）。
    public static let maxSettingsCount = 64

    public enum BackupError: Error, Equatable {
        case tooLarge
        case unsupportedVersion
        case invalidFormat
        case trailingData
    }

    // MARK: - 类型表（与 Android PortableUserData 逐条对齐，剔除 AI 键）

    public static let booleanKeys: Set<String> = [
        WotaSettingsKeys.defaultAudioMuted, WotaSettingsKeys.correctionAuto,
        WotaSettingsKeys.levelEnabled, WotaSettingsKeys.levelBuzzEnabled,
        WotaSettingsKeys.playerTopBarCollapsed, WotaSettingsKeys.hudFrostBlur,
    ]

    public static let intKeys: Set<String> = [
        WotaSettingsKeys.defaultFps, WotaSettingsKeys.defaultShutterNs, WotaSettingsKeys.defaultBitrate,
        WotaSettingsKeys.defaultSampleRate, WotaSettingsKeys.defaultIso, WotaSettingsKeys.defaultEv,
        WotaSettingsKeys.defaultWbKelvin, WotaSettingsKeys.defaultWbTint, WotaSettingsKeys.defaultZebraDensity,
        WotaSettingsKeys.defaultArcMergeDeadband, WotaSettingsKeys.defaultRefLines, WotaSettingsKeys.hudItems,
        WotaSettingsKeys.hudPills, WotaSettingsKeys.hudPillsSchema, WotaSettingsKeys.textScaleCamera,
        WotaSettingsKeys.textScaleSettings, WotaSettingsKeys.textScaleDialog, WotaSettingsKeys.galleryColumns,
        WotaSettingsKeys.cameraBrightenDelaySec, WotaSettingsKeys.playerBrightenRampTenths,
        WotaSettingsKeys.compareTickMs,
    ]

    public static let longKeys: Set<String> = [
        WotaSettingsKeys.correctionManualNs,
    ]

    public static let floatKeys: Set<String> = [
        WotaSettingsKeys.defaultZoom, WotaSettingsKeys.defaultMfDiopter, WotaSettingsKeys.defaultZebraThreshold,
        WotaSettingsKeys.defaultPeakingStrength, WotaSettingsKeys.defaultMarkerSpread,
        WotaSettingsKeys.curvePanelSideDp,
    ]

    // MARK: - 快照模型

    /// 一条媒体指纹（相对路径 + 名 + 字节数 + 时长毫秒）。不含 URI/MediaStore ID/权限。
    public struct MediaKey: Hashable {
        public var relativePath: String
        public var name: String
        public var size: Int64
        public var durationMs: Int64

        public init(relativePath: String, name: String, size: Int64, durationMs: Int64) {
            self.relativePath = relativePath
            self.name = name
            self.size = size
            self.durationMs = durationMs
        }

        public var isValid: Bool {
            // Android 用 `isNotBlank()`：空白串同样不是有效指纹。
            if MediaKey.isBlank(relativePath) || relativePath.hasPrefix("/") { return false }
            if relativePath.contains("\\") { return false }
            if MediaKey.containsControlCharacter(relativePath) { return false }
            for component in relativePath.components(separatedBy: "/") {
                if component == ".." || component == "." { return false }
            }
            if MediaKey.isBlank(name) || name.contains("/") || name.contains("\\") { return false }
            if MediaKey.containsControlCharacter(name) { return false }
            if size <= 0 || durationMs <= 0 { return false }
            return true
        }

        private static func isBlank(_ text: String) -> Bool {
            return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }

        /// 与 Android `Character.isISOControl(char)` 同义：Unicode 类别 `Cc`
        /// （U+0000–U+001F 与 U+007F–U+009F，均为 BMP，不含代理项）。
        /// `Unicode.Scalar` 没有 `isISOControl`，`CharacterSet.controlCharacters` 恰好是 Cc。
        private static func containsControlCharacter(_ text: String) -> Bool {
            return text.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) }
        }
    }

    public struct Tag {
        public var media: MediaKey
        public var value: String
        public var createdAt: Int64

        public init(media: MediaKey, value: String, createdAt: Int64) {
            self.media = media
            self.value = value
            self.createdAt = createdAt
        }
    }

    public struct Snapshot {
        public var settings: [String: WotaBackupValue]
        public var tags: [Tag]

        public init(settings: [String: WotaBackupValue], tags: [Tag]) {
            self.settings = settings
            self.tags = tags
        }
    }

    /// 导入结果：设置已写入的条数 + 需要由媒体库按指纹重绑的标签。
    /// 标签不在这里落地，因为 `WotaCore` 不依赖平台媒体库。
    public struct ImportResult {
        public var settingsApplied: Int
        public var tags: [Tag]

        public init(settingsApplied: Int, tags: [Tag]) {
            self.settingsApplied = settingsApplied
            self.tags = tags
        }
    }

    // MARK: - 白名单过滤 / 加法恢复

    /// 只保留白名单内、类型正确、字符串长度合规的设置。
    public static func settingsOf(_ all: [String: WotaBackupValue]) -> [String: WotaBackupValue] {
        var out: [String: WotaBackupValue] = [:]
        for (key, value) in all {
            guard WotaSettingsKeys.backupKeys.contains(key) else { continue }
            if booleanKeys.contains(key) {
                if case .bool = value { out[key] = value }
            } else if intKeys.contains(key) {
                if case .int = value { out[key] = value }
            } else if longKeys.contains(key) {
                if case .int64 = value { out[key] = value }
            } else if floatKeys.contains(key) {
                if case .float(let f) = value, f.isFinite { out[key] = value }
            } else if case .string(let s) = value {
                if s.utf8.count <= maxStringBytes { out[key] = value }
            }
        }
        return out
    }

    /// 恢复是**补充**，不覆盖目标设备已存在的用户设置（含 `false`/`0`）。
    public static func settingsToRestore(
        restored: [String: WotaBackupValue],
        current: [String: WotaBackupValue]
    ) -> [String: WotaBackupValue] {
        return settingsOf(restored).filter { current[$0.key] == nil }
    }

    /// 同一媒体的同一标签只保留一份（已有待匹配项优先，重复 restore 不累积数据）。
    public static func mergeTags(existing: [Tag], incoming: [Tag]) -> [Tag] {
        var seen: Set<String> = []
        var out: [Tag] = []
        for tag in existing + incoming {
            let fingerprint = tag.media.relativePath + "\u{0}" + tag.media.name + "\u{0}"
                + String(tag.media.size) + "\u{0}" + String(tag.media.durationMs) + "\u{0}" + tag.value
            if seen.contains(fingerprint) { continue }
            seen.insert(fingerprint)
            out.append(tag)
            if out.count >= maxTags { break }
        }
        return out
    }

    // MARK: - 导出

    /// 从存储导出快照（settings 已按白名单与类型表过滤；tags 由调用方提供，当前无媒体库时为空）。
    public static func snapshot(from store: WotaSettingsStore, tags: [Tag] = []) -> Snapshot {
        var raw: [String: WotaBackupValue] = [:]
        for key in WotaSettingsKeys.backupKeys {
            if let value = typedValue(store: store, key: key) { raw[key] = value }
        }
        let validTags = tags.filter { isTagValid($0) }
        return Snapshot(settings: settingsOf(raw), tags: mergeTags(existing: [], incoming: validTags))
    }

    /// 编码成二进制（超上限抛 `.tooLarge`；`Int` 超出 32 位线上域抛 `.invalidFormat`）。
    public static func encode(_ snapshot: Snapshot) throws -> Data {
        let settings = settingsOf(snapshot.settings)
        let tags = mergeTags(existing: [], incoming: snapshot.tags.filter { isTagValid($0) })

        var writer = WotaBinaryWriter(limit: maxBytes)
        try writer.writeInt32(Int32(version))
        try writer.writeInt32(Int32(settings.count))
        // 保证编码确定性：排序键，避免同一份数据两次导出字节不同。
        for key in settings.keys.sorted() {
            guard let value = settings[key] else { continue }
            try writer.writeText(key)
            switch value {
            case .bool(let v):
                try writer.writeByte(1)
                try writer.writeByte(v ? 1 : 0)
            case .int(let v):
                try writer.writeByte(2)
                // 线上类型是 32 位 `Int`（Java `int`）；超域时不能静默截断成另一组位型。
                guard let narrowed = Int32(exactly: v) else { throw BackupError.invalidFormat }
                try writer.writeInt32(narrowed)
            case .int64(let v):
                try writer.writeByte(3)
                try writer.writeInt64(v)
            case .float(let v):
                try writer.writeByte(4)
                try writer.writeFloat32(v)
            case .string(let v):
                try writer.writeByte(5)
                try writer.writeText(v)
            }
        }
        try writer.writeInt32(Int32(tags.count))
        for tag in tags {
            try writer.writeText(tag.media.relativePath)
            try writer.writeText(tag.media.name)
            try writer.writeInt64(tag.media.size)
            try writer.writeInt64(tag.media.durationMs)
            try writer.writeText(tag.value)
            try writer.writeInt64(tag.createdAt)
        }
        return writer.data
    }

    // MARK: - 导入（加法）

    /// 解析二进制快照；坏版本 / 越界长度 / 截断 / 尾随数据一律拒绝。
    public static func decode(_ data: Data) throws -> Snapshot {
        if data.count > maxBytes { throw BackupError.tooLarge }
        var reader = WotaBinaryReader(data: data)
        guard let versionValue = try reader.readInt32(), versionValue == Int32(version) else {
            throw BackupError.unsupportedVersion
        }
        guard let settingCount = try reader.readCount(max: maxSettingsCount) else {
            throw BackupError.invalidFormat
        }
        var settings: [String: WotaBackupValue] = [:]
        for _ in 0..<settingCount {
            guard let key = try reader.readText(), let tag = try reader.readByte() else {
                throw BackupError.invalidFormat
            }
            let value: WotaBackupValue
            switch tag {
            case 1:
                guard let raw = try reader.readByte() else { throw BackupError.invalidFormat }
                value = .bool(raw != 0)
            case 2:
                guard let raw = try reader.readInt32() else { throw BackupError.invalidFormat }
                value = .int(Int(raw))
            case 3:
                guard let raw = try reader.readInt64() else { throw BackupError.invalidFormat }
                value = .int64(raw)
            case 4:
                guard let raw = try reader.readFloat32() else { throw BackupError.invalidFormat }
                value = .float(raw)
            case 5:
                guard let raw = try reader.readText() else { throw BackupError.invalidFormat }
                value = .string(raw)
            default:
                throw BackupError.invalidFormat
            }
            if settings[key] != nil { throw BackupError.invalidFormat }
            settings[key] = value
        }
        guard let tagCount = try reader.readCount(max: maxTags) else { throw BackupError.invalidFormat }
        var tags: [Tag] = []
        for _ in 0..<tagCount {
            guard let path = try reader.readText(), let name = try reader.readText(),
                  let size = try reader.readInt64(), let duration = try reader.readInt64(),
                  let value = try reader.readText(), let createdAt = try reader.readInt64() else {
                throw BackupError.invalidFormat
            }
            let media = MediaKey(relativePath: path, name: name, size: size, durationMs: duration)
            tags.append(Tag(media: media, value: value, createdAt: createdAt))
        }
        guard reader.atEnd else { throw BackupError.trailingData }
        let validTags = tags.filter { isTagValid($0) }
        return Snapshot(settings: settingsOf(settings), tags: mergeTags(existing: [], incoming: validTags))
    }

    /// 导入：设置加法恢复（不覆盖已存在键），并交回标签供媒体库按指纹重绑。
    @discardableResult
    public static func importBackup(_ data: Data, into store: WotaSettingsStore) throws -> ImportResult {
        let snapshot = try decode(data)
        var current: [String: WotaBackupValue] = [:]
        for key in WotaSettingsKeys.backupKeys {
            if let value = typedValue(store: store, key: key) { current[key] = value }
        }
        let toRestore = settingsToRestore(restored: snapshot.settings, current: current)
        for (key, value) in toRestore {
            store.setRawValue(value, forKey: key)
        }
        if !toRestore.isEmpty { store.flush() }
        return ImportResult(settingsApplied: toRestore.count, tags: snapshot.tags)
    }

    // MARK: - 内部

    private static func isTagValid(_ tag: Tag) -> Bool {
        // Android 校验用 `String.length`（UTF-16 码元数）与 `isNotBlank()`。
        // 长度必须按 UTF-16 码元算：128 个 emoji 在 `String.count`（字素簇）里是 128，
        // 但 Java `String.length` 是 256，Android 会整份拒收 iOS 导出的备份。
        // 空白判定用 Foundation 空白集合（比 Java isWhitespace 略宽）：只会更早丢弃标签，
        // 不会写出 Android 不接受的标签。
        return tag.media.isValid
            && !tag.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && tag.value.utf16.count <= maxTagValueChars
    }

    /// 按键的类型表读出值（不依赖 `UserDefaults` 内部的 `NSNumber` 子类型）。
    private static func typedValue(store: WotaSettingsStore, key: String) -> WotaBackupValue? {
        guard store.defaults.object(forKey: key) != nil else { return nil }
        if booleanKeys.contains(key) { return .bool(store.defaults.bool(forKey: key)) }
        if intKeys.contains(key) { return .int(store.defaults.integer(forKey: key)) }
        if longKeys.contains(key) {
            guard let number = store.defaults.object(forKey: key) as? NSNumber else { return nil }
            return .int64(number.int64Value)
        }
        if floatKeys.contains(key) { return .float(store.defaults.float(forKey: key)) }
        if let string = store.defaults.string(forKey: key) { return .string(string) }
        return nil
    }
}

// MARK: - 大端二进制读写（与 Android DataOutputStream / DataInputStream 逐字节一致）

/// 线上格式与 Android `backup/PortableUserData.kt` 的 `DataOutputStream` 调用对齐：
/// - `Int` = 4 字节大端二补码；`Long` = 8 字节大端二补码；`Float` = IEEE-754 32 位大端。
/// - 字符串**不是** `DataOutputStream.writeUTF` 的 Java modified UTF-8（那会是 2 字节长度、
///   U+0000 → `C0 80`、代理对分开编码）；Android 真源用的是自定义 `text()`：
///   `String.toByteArray(Charsets.UTF_8)` + `writeInt(byteCount)`，即**标准 UTF-8 + int32 字节长度**。
///   因此这里继续用 `Data(value.utf8)`，不要“为了 Java 兼容”改成 modified UTF-8。
/// - Swift `String` 保证是合法 Unicode；解码端用严格 UTF-8（坏字节 → `invalidFormat`）。
///   Java 解码器会把坏字节替换成 U+FFFD，但 Android 写出的备份永远是合法 UTF-8，互认不受影响。
private struct WotaBinaryWriter {
    private(set) var data = Data()
    private let limit: Int

    init(limit: Int) {
        self.limit = limit
    }

    mutating func writeByte(_ value: UInt8) throws {
        guard data.count + 1 <= limit else { throw WotaUserBackup.BackupError.tooLarge }
        data.append(value)
    }

    mutating func writeInt32(_ value: Int32) throws {
        // 先取 32 位补码位型，再零扩展到 UInt64；`writeUIntBytes` 只取低 4 字节。
        try writeUIntBytes(UInt64(UInt32(bitPattern: value)), count: 4)
    }

    mutating func writeInt64(_ value: Int64) throws {
        try writeUIntBytes(UInt64(bitPattern: value), count: 8)
    }

    mutating func writeFloat32(_ value: Float) throws {
        try writeUIntBytes(UInt64(value.bitPattern), count: 4)
    }

    mutating func writeText(_ value: String) throws {
        let bytes = Data(value.utf8)
        guard bytes.count <= WotaUserBackup.maxStringBytes else { throw WotaUserBackup.BackupError.tooLarge }
        try writeInt32(Int32(bytes.count))
        guard data.count + bytes.count <= limit else { throw WotaUserBackup.BackupError.tooLarge }
        data.append(bytes)
    }

    private mutating func writeUIntBytes(_ value: UInt64, count: Int) throws {
        guard data.count + count <= limit else { throw WotaUserBackup.BackupError.tooLarge }
        var shift = (count - 1) * 8
        while shift >= 0 {
            data.append(UInt8((value >> UInt64(shift)) & 0xFF))
            shift -= 8
        }
    }
}

private struct WotaBinaryReader {
    private let data: Data
    private var offset = 0

    init(data: Data) {
        self.data = data
    }

    var atEnd: Bool { return offset >= data.count }
    private var remaining: Int { return data.count - offset }

    mutating func readByte() throws -> UInt8? {
        guard remaining >= 1 else { return nil }
        let value = data[data.startIndex + offset]
        offset += 1
        return value
    }

    mutating func readInt32() throws -> Int32? {
        guard let raw = try readUIntBytes(count: 4) else { return nil }
        return Int32(bitPattern: UInt32(truncatingIfNeeded: raw))
    }

    mutating func readInt64() throws -> Int64? {
        guard let raw = try readUIntBytes(count: 8) else { return nil }
        return Int64(bitPattern: raw)
    }

    mutating func readFloat32() throws -> Float? {
        guard let raw = try readUIntBytes(count: 4) else { return nil }
        return Float(bitPattern: UInt32(truncatingIfNeeded: raw))
    }

    /// 读一个「计数」：0..max 之外（含负数）判非法。
    mutating func readCount(max: Int) throws -> Int? {
        guard let raw = try readInt32() else { return nil }
        if raw < 0 || Int(raw) > max { throw WotaUserBackup.BackupError.invalidFormat }
        return Int(raw)
    }

    mutating func readText() throws -> String? {
        guard let length = try readInt32() else { return nil }
        if length < 0 || Int(length) > WotaUserBackup.maxStringBytes {
            throw WotaUserBackup.BackupError.invalidFormat
        }
        guard remaining >= Int(length) else { return nil }
        let start = data.startIndex + offset
        let slice = data[start..<(start + Int(length))]
        offset += Int(length)
        guard let text = String(data: Data(slice), encoding: .utf8) else {
            throw WotaUserBackup.BackupError.invalidFormat
        }
        return text
    }

    private mutating func readUIntBytes(count: Int) throws -> UInt64? {
        guard remaining >= count else { return nil }
        var value: UInt64 = 0
        for _ in 0..<count {
            value = (value << 8) | UInt64(data[data.startIndex + offset])
            offset += 1
        }
        return value
    }
}
