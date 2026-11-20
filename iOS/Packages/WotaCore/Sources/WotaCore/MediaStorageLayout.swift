import Foundation

/// 媒体目录 / 命名口径，与 Android 对齐（`VideoStore.RELATIVE_PATH`、`ClipExport`、`TrackExport`、`ArcDropLog`）。
///
/// iOS 无 Android MediaStore / FileProvider。本类型只提供纯路径与命名计算，**不创建目录、不写相册**；
/// 实际落盘由 `Library` 数据层负责，避免在纯逻辑层引入隐式媒体库行为。
///
/// 目录与命名（与 Android 对齐，不引入隐式媒体库行为）：
/// - 成片：`Movies/WotaGeiCam/Camera`，`VID_yyyyMMdd_HHmmss_%03d.mp4`（处理前缀可为 `NNftoNNf_`）；
/// - 剪辑成片：`Movies/WotaGeiCam/Clip`，`edit_<源名去扩展名>.mp4`；
/// - 选轨成片：`Movies/WotaGeiCam/Inau`，`inau_<源名去扩展名>.mp4`；
/// - 抽帧 sidecar：与成片同目录同名，扩展名 `.drops.json`。
public enum MediaStorageLayout {

    public static let cameraRelativeDirectory = "Movies/WotaGeiCam/Camera"
    public static let clipRelativeDirectory = "Movies/WotaGeiCam/Clip"
    public static let inauRelativeDirectory = "Movies/WotaGeiCam/Inau"

    /// 所有成片目录（扫描/维护都只在这些目录内，**不做全盘遍历**）。
    public static let allMediaRelativeDirectories: [String] = [
        cameraRelativeDirectory,
        clipRelativeDirectory,
        inauRelativeDirectory
    ]

    /// 成片容器 MIME（Android `VideoStore.MIME_MP4`）。
    public static let videoMimeType = "video/mp4"

    /// sidecar 扩展名（Android `ArcDropLog.EXTENSION`）。
    public static let sidecarExtension = ".drops.json"

    /// Android `VideoStore.RELATIVE_PATH` 的相机成片目录；与既有 `StorageLayout` 同值。
    public static let cameraMediaRelativeDirectory = StorageLayout.relativeDirectory

    public static func directoryComponents(_ relativeDirectory: String) -> [String] {
        relativeDirectory.split(separator: "/").map(String.init)
    }

    /// 相对目录 → App 容器（Documents）下的绝对目录。
    public static func directoryURL(relativeDirectory: String, under containerURL: URL) -> URL {
        directoryComponents(relativeDirectory).reduce(containerURL) { partialURL, component in
            partialURL.appendingPathComponent(component, isDirectory: true)
        }
    }

    // MARK: - 命名

    /// 抽取不含扩展名的基名：`a/b/VID_x.mp4` → `VID_x`。
    public static func stem(of fileName: String) -> String {
        guard let dot = fileName.lastIndex(of: "."), dot != fileName.startIndex else { return fileName }
        return String(fileName[fileName.startIndex..<dot])
    }

    public static func extensionName(of fileName: String) -> String {
        guard let dot = fileName.lastIndex(of: "."), dot != fileName.startIndex else { return "" }
        return String(fileName[fileName.index(after: dot)...])
    }

    /// 剪辑成片名：`edit_<源名去扩展名>.mp4`（Android `ClipExport`）。
    public static func clipExportFileName(sourceFileName: String) -> String {
        "edit_" + stem(of: sourceFileName) + ".mp4"
    }

    /// 选轨成片名：`inau_<源名去扩展名>.mp4`（Android `TrackExport`）。
    public static func inauExportFileName(sourceFileName: String) -> String {
        "inau_" + stem(of: sourceFileName) + ".mp4"
    }

    /// 成片文件名 → sidecar 文件名：同目录同名，扩展名换成 `.drops.json`（Android `ArcDropLog.sidecarFor`）。
    public static func sidecarFileName(forVideoFileName fileName: String) -> String {
        stem(of: fileName) + sidecarExtension
    }

    /// 成片绝对路径 → sidecar 绝对路径。
    public static func sidecarPath(forVideoPath videoPath: String) -> String {
        let directory = (videoPath as NSString).deletingLastPathComponent
        let name = (videoPath as NSString).lastPathComponent
        let sidecar = sidecarFileName(forVideoFileName: name)
        return directory.isEmpty ? sidecar : (directory as NSString).appendingPathComponent(sidecar)
    }

    // MARK: - 形态判定

    /// 是否是本工程产出的成片名：`VID_<时间戳>` 或带转换前缀 `NNftoNNf_VID_…`。
    ///
    /// 与 Android `StalePendingSweep.isOurClipName` 同口径；**不认** pending marker 形态
    /// （前缀里带 `.pending-<数字>-` 段的一律判 false，见 `MediaPendingName`）。
    public static func isOurClipName(_ name: String) -> Bool {
        guard name.hasSuffix(".mp4"), name.contains("VID_") else { return false }
        let prefix = name.components(separatedBy: "VID_").first ?? ""
        if prefix.isEmpty { return true }
        return prefix.range(of: #"^[0-9]+fto[0-9]+f_$"#, options: .regularExpression) != nil
    }

    /// 本工程自有目录里允许存在的媒体/sidecar 形态（成片、剪辑、选轨、sidecar）。
    public static func isOwnedMediaFileName(_ name: String) -> Bool {
        if name.hasSuffix(sidecarExtension) { return true }
        return isOwnedVideoFileName(name)
    }

    /// 自有成片名（成片、剪辑、选轨三种，不含 sidecar）。
    public static func isOwnedVideoFileName(_ name: String) -> Bool {
        if isOurClipName(name) { return true }
        if name.hasPrefix("edit_") && name.hasSuffix(".mp4") { return true }
        if name.hasPrefix("inau_") && name.hasSuffix(".mp4") { return true }
        return false
    }

    /// 是否是抽帧 sidecar 名。
    public static func isSidecarName(_ name: String) -> Bool {
        name.hasSuffix(sidecarExtension)
    }

    // MARK: - 安全（越界 / 注入防护）

    /// 用户可输入的文件名消毒：拒绝路径分隔符、`.`/`..`、空串。
    ///
    /// Android 侧改名时用 `substringAfterLast('/')` + 非空/非 `.`/`..` 过滤来防把 sidecar
    /// 挪出本应用目录；iOS 侧在拼路径前做同一道校验，返回 nil 表示不可用。
    public static func sanitizedFileName(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "." || trimmed == ".." { return nil }
        if trimmed.contains("/") || trimmed.contains("\\") { return nil }
        if trimmed.contains("\u{0}") { return nil }
        return trimmed
    }

    /// 保证新名带原扩展名（Android `MediaActions.ensureSuffix`）。
    public static func ensuringSuffix(_ name: String, like oldName: String) -> String {
        let ext = extensionName(of: oldName)
        if ext.isEmpty { return name }
        if name.lowercased().hasSuffix("." + ext.lowercased()) { return name }
        return name + "." + ext
    }

    /// 绝对路径是否位于给定根目录之内（标准化 + 逐段前缀比较，拒绝 `..` 逃逸）。
    ///
    /// 用于「永久删除仅限应用自有文件」「回收站/重命名不得越界」两道红线。
    public static func isPath(_ absolutePath: String, inside rootPath: String) -> Bool {
        let root = normalizedAbsolutePath(rootPath)
        let target = normalizedAbsolutePath(absolutePath)
        if root.isEmpty || target.isEmpty { return false }
        if target == root { return true }
        return target.hasPrefix(root + "/")
    }

    /// 用标准化的逐段路径消解 `.` / `..`（不使用 `FileManager` 符号链接解引用，避免依赖真实文件系统）。
    public static func normalizedAbsolutePath(_ path: String) -> String {
        var components: [String] = []
        let isAbsolute = path.hasPrefix("/")
        for part in path.split(separator: "/", omittingEmptySubsequences: true) {
            switch part {
            case ".":
                continue
            case "..":
                if components.isEmpty {
                    // 已到根：`..` 不再上溯（根之上仍是根）
                    continue
                }
                components.removeLast()
            default:
                components.append(String(part))
            }
        }
        let joined = components.joined(separator: "/")
        return isAbsolute ? "/" + joined : joined
    }
}
