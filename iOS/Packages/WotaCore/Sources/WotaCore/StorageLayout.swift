import Foundation

/// 媒体目录口径，与 Android `VideoStore.RELATIVE_PATH` 对齐：
/// `Movies/WotaGeiCam/Camera`。
///
/// iOS 没有 Android MediaStore。这里只提供纯路径计算，不创建目录、不写入照片图库；
/// 实际落盘策略由后续 Recording / Library 任务决定，避免在本骨架引入隐式媒体库行为。
public enum StorageLayout {
    public static let relativeDirectory = "Movies/WotaGeiCam/Camera"

    public static let directoryComponents: [String] =
        relativeDirectory.split(separator: "/").map(String.init)

    /// 把 Android 相对目录接到 iOS 应用容器目录（通常是 Documents）下。
    public static func mediaDirectoryURL(applicationDocumentsURL: URL) -> URL {
        directoryComponents.reduce(applicationDocumentsURL) { partialURL, component in
            partialURL.appendingPathComponent(component, isDirectory: true)
        }
    }
}
