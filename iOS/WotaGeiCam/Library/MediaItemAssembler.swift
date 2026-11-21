import AVFoundation
import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 文件媒体元数据探测（AVFoundation）。
///
/// Android 从 MediaStore 列直接读 DURATION/WIDTH/HEIGHT；iOS 无该列，改为对自有文件做
/// 一次性 AVAsset 探测，结果缓存进索引身份行（见 `LibraryIdentity`）。
///
/// 探测只读、不开写；失败返回 nil（不可播/半截文件），调用方据此决定是否保留。
enum MediaItemAssembler {

    struct ProbedMetadata {
        var durationMs: Int64
        var width: Int
        var height: Int
    }

    /// 探测时长与视频宽高。打不开（半截 MP4 / 损坏 / 无视频轨）返回 nil。
    static func probe(url: URL) -> ProbedMetadata? {
        let asset = AVURLAsset(url: url, options: nil)
        let videoTracks = asset.tracks(withMediaType: .video)
        guard let track = videoTracks.first else { return nil }
        let duration = CMTimeConvertScale(track.timeRange.duration, timescale: 1000, method: .default)
        let size = track.naturalSize
        guard duration.isNumeric, duration.value > 0,
              let width = Int(exactly: abs(size.width).rounded()), width > 0,
              let height = Int(exactly: abs(size.height).rounded()), height > 0 else { return nil }
        return ProbedMetadata(durationMs: duration.value, width: width, height: height)
    }

    /// 容器是否可提取（有轨）——与 Android `MediaExtractor` 探测同一把尺，
    /// 供冷启动清扫判定「完整合法的容器」。
    static func isExtractable(url: URL) -> Bool {
        let asset = AVURLAsset(url: url, options: nil)
        return !asset.tracks(withMediaType: .video).isEmpty
    }
}
