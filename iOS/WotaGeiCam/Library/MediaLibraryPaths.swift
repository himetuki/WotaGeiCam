import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// App 自有媒体层与索引的路径口径（iOS 无 MediaStore，改为 App 沙盒目录）。
///
/// - 媒体真源：`<container>/Movies/WotaGeiCam/{Camera,Clip,Inau}`（与 Android 公共存储相对路径对齐）；
/// - 索引：`<container>/WotaGeiCam/wota_media_index.json`（Android Room `wota_media.db` 的等价索引，
///   放在媒体目录**之外**，避免与成片/sidecar 混目录；也不进入相册）。
///
/// `container` 在应用运行期即 `Documents`。所有文件操作都必须落在 `mediaRoot` 内
/// （见 `MediaStorageLayout.isPath(_:inside:)`），不做全盘外部媒体删改。
struct MediaLibraryPaths {

    static let mediaDirectoriesRelative = "Movies/WotaGeiCam"
    static let privateDirectoryRelative = "WotaGeiCam"
    static let indexFileName = "wota_media_index.json"

    let containerURL: URL

    init(containerURL: URL) {
        self.containerURL = containerURL
    }

    /// App 自有媒体根：`<container>/Movies/WotaGeiCam`。
    var mediaRootURL: URL {
        MediaStorageLayout.directoryURL(relativeDirectory: MediaLibraryPaths.mediaDirectoriesRelative, under: containerURL)
    }

    var cameraDirectoryURL: URL {
        MediaStorageLayout.directoryURL(relativeDirectory: MediaStorageLayout.cameraRelativeDirectory, under: containerURL)
    }

    var clipDirectoryURL: URL {
        MediaStorageLayout.directoryURL(relativeDirectory: MediaStorageLayout.clipRelativeDirectory, under: containerURL)
    }

    var inauDirectoryURL: URL {
        MediaStorageLayout.directoryURL(relativeDirectory: MediaStorageLayout.inauRelativeDirectory, under: containerURL)
    }

    /// 全部自有成片目录（Camera / Clip / Inau）。
    var mediaDirectoryURLs: [URL] {
        [cameraDirectoryURL, clipDirectoryURL, inauDirectoryURL]
    }

    /// 私有索引目录：`<container>/WotaGeiCam`。
    var privateDirectoryURL: URL {
        containerURL.appendingPathComponent(MediaLibraryPaths.privateDirectoryRelative, isDirectory: true)
    }

    var indexFileURL: URL {
        privateDirectoryURL.appendingPathComponent(MediaLibraryPaths.indexFileName, isDirectory: false)
    }

    /// 损坏索引的备份名（恢复坏索引时改名保留，**绝不删除媒体**）。
    func corruptIndexBackupURL(timestampMs: Int64) -> URL {
        privateDirectoryURL.appendingPathComponent("wota_media_index.corrupt-\(timestampMs).json", isDirectory: false)
    }

    /// 某相对目录的绝对目录 URL。
    func directoryURL(relativeDirectory: String) -> URL {
        MediaStorageLayout.directoryURL(relativeDirectory: relativeDirectory, under: containerURL)
    }
}
