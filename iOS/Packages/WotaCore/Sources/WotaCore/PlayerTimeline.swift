import Foundation
import CoreMedia

// WotaCore 纯逻辑：播放器剪辑/导出计划、AB 持久化键、逐帧帧率探测结果、
// 对比历史与分屏接入契约的纯值口径。
//
// 本文件不 import UIKit / AVFoundation；`CMTime` / `CMTimeRange` 由 CoreMedia 提供，
// iOS 4+ 可用，故可在最低基线（iOS 10）上独立编译与复用。
//
// 对齐 Android：
// - `player/ClipExport.kt`（`ClipRule.normalize/keepSample/keyFrameIndexOf`、`ClipResult`/`ClipError`）
// - `player/TrackExport.kt`（`TrackRule.shiftUsFor/shiftedPts/nameOf`、`SelectedTrack`）
// - `player/PlayerEngine.kt`（AB 持久化键、`attach`/`restoreAb`/`persistAb`）
// - `player/CompareHistory.kt`（历史条目与上限，实际编解码已在 `MediaCompareHistory.swift`）
// - `player/CompareAudio.kt`（`Side` / `AudioTrackKind` / `audioGroupIndexOf`）
//
// iOS 侧刻意**不引入 Android 不存在的隐式媒体库行为**：计划层只算名字/区间/轨道，
// 不写索引、不写 MediaLibrary、不扫描外部媒体。

// MARK: - 剪辑计划

/// 剪辑区间的规范化结果。
public struct ClipTimeRange: Equatable, Sendable {
    public var start: CMTime
    public var end: CMTime

    public init(start: CMTime, end: CMTime) {
        self.start = start
        self.end = end
    }

    public var duration: CMTime {
        end - start
    }

    public var isValid: Bool {
        start.isValid && end.isValid && start.isNumeric && end.isNumeric && end.seconds > start.seconds
    }
}

/// 剪辑错误分类。
public enum ClipPlanError: String, Equatable, Sendable {
    /// 保留段不合法（太短 / 反向 / 越界）。
    case range
    /// 无法打开源文件。
    case open
    /// 容器/编码器不支持。
    case unsupported
    /// 用户取消。
    case cancelled
    /// 未导出完成（失败）。
    case mux
    /// 已有导出任务进行中。
    case busy
}

/// 剪辑/导出的纯判据（Android `ClipRule` + `TrackRule` 的等价重写）。
public enum PlayerClipRule {

    /// 保留段下限：比它短视为误操作（300ms 在 24fps 下连 8 帧都不到）。
    public static let minKeepSeconds: Double = 0.3

    /// 入出点规范化：起点非负、终点不超片长、终点必须晚于起点、保留段不短于下限。
    /// 不合法返回 nil（调用方给「保留段太短」提示）。
    public static func normalize(start: CMTime, end: CMTime, duration: CMTime) -> ClipTimeRange? {
        let durationSeconds = (duration.isValid && duration.isNumeric && duration.seconds > 0) ? duration.seconds : 0
        let startSeconds = min(max(start.seconds, 0), durationSeconds)
        let endSeconds = min(max(end.seconds, 0), durationSeconds)
        guard endSeconds - startSeconds >= PlayerClipRule.minKeepSeconds else { return nil }
        return ClipTimeRange(
            start: CMTime(seconds: startSeconds, preferredTimescale: 600),
            end: CMTime(seconds: endSeconds, preferredTimescale: 600)
        )
    }

    /// 平移后相对 PTS 为负的样本该不该写出：**不写**（丢弃），不 clip 到 0。
    /// 与 Android `ClipRule.keepSample` 同口径（MP4 封轨要求每轨 PTS 单调不减且 ≥0；
    /// 钳到 0 会造成同刻多样本，容器会拒绝）。
    public static func keepsSample(relativeSeconds: Double) -> Bool {
        return relativeSeconds >= 0
    }

    /// 关键帧的输出帧序号：抽帧成片 PTS = 帧序号 × 1e6/dstFps 的闭式反推（四舍五入）。
    /// iOS 侧用于 sidecar 平移重映射的位移量；`dstFps <= 0` 时返回 0（不做重映射）。
    public static func keyFrameIndex(baseSeconds: Double, destinationFps: Double) -> Int64 {
        guard destinationFps > 0 else { return 0 }
        let value = (baseSeconds * destinationFps * 1_000_000 + 500_000) / 1_000_000
        if value < 0 { return 0 }
        return Int64(value)
    }

    /// 剪辑成片命名：`edit_<源名去扩展名>.<ext>`（对齐 Android `ClipRule.clipNameOf`，
    /// 用户 2026-10-04 定版：处理过的片名上前缀可辨）。
    ///
    /// iOS 无 MediaStore 自动去重，撞名由平台层在 `beginPending` 失败时自己追加序号。
    public static func clipName(from sourceFileName: String, fileExtension: String) -> String {
        let stem = MediaStorageLayout.stem(of: sourceFileName)
        let ext = fileExtension.hasPrefix(".") ? String(fileExtension.dropFirst()) : fileExtension
        return "edit_\(stem).\(ext)"
    }

    /// sidecar 平移重映射：保留输出帧序号 `shift` 及其之后的部分，位次整体前移 `shift`。
    /// `drops` 的键 = 输出帧序号，值 = 该帧之前丢掉的源帧数（增量，平移后原样保留）。
    public static func remapDrops(_ drops: [Int64: Int64], shift: Int64) -> [Int64: Int64] {
        var out: [Int64: Int64] = [:]
        for (key, value) in drops {
            if key >= shift { out[key - shift] = value }
        }
        return out
    }
}

/// 选轨种类。iOS 侧容器音轨序沿用 Android 口径：环境 = 第 0 条、内录 = 第 1 条。
public enum PlayerAudioTrackKind: String, Equatable, Sendable {
    case environment
    case internalCapture
}

/// 选轨平移量（秒）：选环境轨 = 0；选内录轨 = `−offset`（内录晚响，导出时提前对齐）。
public enum PlayerTrackRule {
    public static func shiftSeconds(for track: PlayerAudioTrackKind, offsetSeconds: Double) -> Double {
        switch track {
        case .environment:
            return 0
        case .internalCapture:
            return -offsetSeconds
        }
    }

    /// 平移后的输出 PTS（秒）；早于 0（负 PTS）返回 nil = 丢弃该前导样本。
    public static func shiftedPts(sourcePtsSeconds: Double, baseSeconds: Double, shiftSeconds: Double) -> Double? {
        let value = sourcePtsSeconds - baseSeconds + shiftSeconds
        return PlayerClipRule.keepsSample(relativeSeconds: value) ? value : nil
    }
}

// MARK: - AB 持久化

/// AB 段的持久化键与存储（纯逻辑）。iOS 侧用 `UserDefaults` 承载，按**媒体标识串**记键
/// （App 容器 URL 字符串或外来来源绝对路径），与 Android `key(prefix, uri)` 同口径。
///
/// 红线上与 Android 一致：A/B 均落盘；恢复时 `B <= A` 即清 B 与循环；
/// **不写媒体库索引**、不把 AB 与时长之外的元数据混在一起。
public enum PlayerABStore {

    public static let defaultsKeyPrefix = "wota_player_ab"

    public static func key(prefix: String, mediaKey: String) -> String {
        "\(prefix):\(mediaKey)"
    }

    public static func mediaKey(for url: URL) -> String {
        url.absoluteString
    }

    /// 读取一段 AB（坏数据按 `.invalid` 处理，A/B 皆非有限值即整段丢弃）。
    public static func load(
        defaults: UserDefaults,
        mediaKey: String
    ) -> PlayerABRange {
        let aRaw = defaults.object(forKey: PlayerABStore.key(prefix: "ab_a", mediaKey: mediaKey)) as? Double
        let bRaw = defaults.object(forKey: PlayerABStore.key(prefix: "ab_b", mediaKey: mediaKey)) as? Double
        let loop = defaults.bool(forKey: PlayerABStore.key(prefix: "ab_loop", mediaKey: mediaKey))
        var start = PlayerABRange.empty.start
        var end = PlayerABRange.empty.end
        if let a = aRaw, a.isFinite, a >= 0 {
            start = CMTime(seconds: a, preferredTimescale: 600)
        }
        if let b = bRaw, b.isFinite, b >= 0 {
            end = CMTime(seconds: b, preferredTimescale: 600)
        }
        var range = PlayerABRange(start: start, end: end, loopEnabled: loop)
        // 旧版本残留的 A>B 数据按 markA 自身口径收整（B ≤ A 即清 B）。
        if range.start.isValid, range.end.isValid, range.end.seconds <= range.start.seconds {
            range = PlayerABRange(start: range.start, end: .invalid, loopEnabled: false)
        }
        if !range.isValid { range = PlayerABRange(start: range.start, end: range.end, loopEnabled: false) }
        return range
    }

    /// 落盘一段 AB。空段也写（清掉上一片的残留）。
    public static func save(
        defaults: UserDefaults,
        mediaKey: String,
        range: PlayerABRange
    ) {
        if range.start.isValid, range.start.isNumeric {
            defaults.set(range.start.seconds, forKey: PlayerABStore.key(prefix: "ab_a", mediaKey: mediaKey))
        } else {
            defaults.removeObject(forKey: PlayerABStore.key(prefix: "ab_a", mediaKey: mediaKey))
        }
        if range.end.isValid, range.end.isNumeric {
            defaults.set(range.end.seconds, forKey: PlayerABStore.key(prefix: "ab_b", mediaKey: mediaKey))
        } else {
            defaults.removeObject(forKey: PlayerABStore.key(prefix: "ab_b", mediaKey: mediaKey))
        }
        defaults.set(range.loopEnabled, forKey: PlayerABStore.key(prefix: "ab_loop", mediaKey: mediaKey))
    }

    /// 清除一段 AB。
    public static func clear(defaults: UserDefaults, mediaKey: String) {
        defaults.removeObject(forKey: PlayerABStore.key(prefix: "ab_a", mediaKey: mediaKey))
        defaults.removeObject(forKey: PlayerABStore.key(prefix: "ab_b", mediaKey: mediaKey))
        defaults.removeObject(forKey: PlayerABStore.key(prefix: "ab_loop", mediaKey: mediaKey))
    }
}

// MARK: - 帧率

/// 逐帧步进所需的帧长来源。
///
/// **不允许固定 fps 硬编码**：优先取 `AVAssetTrack.nominalFrameRate`；读不到时用
/// `minFrameDuration`（iOS 4+）；
/// 两者都失败才用显式降级步长，并**必须**把 `isExact` 置 false。
public enum PlayerFrameRateSource {
    /// 已知帧率。
    case known(Float)
    /// 未知：调用方应使用降级步长并标记为非精确。
    case unknown
}

/// 由帧率来源算出单帧秒长与是否精确。
public enum PlayerFrameRateMath {

    public static func frameSeconds(_ source: PlayerFrameRateSource) -> (seconds: Double, isExact: Bool) {
        switch source {
        case .known(let rate):
            guard rate > 0, rate.isFinite else {
                return (PlayerFrameStepMath.fallbackFrameMs / 1000.0, false)
            }
            return (1.0 / Double(rate), true)
        case .unknown:
            return (PlayerFrameStepMath.fallbackFrameMs / 1000.0, false)
        }
    }
}

// MARK: - 分屏接入契约（纯值）

/// 对比页两台播放器的编号（两条视频轨）。
public enum CompareSide: String, Equatable, Sendable, CaseIterable {
    case left
    case right

    public var other: CompareSide {
        self == .left ? .right : .left
    }
}

/// 对比页音频计划（单时间线上两条视频轨，音轨只播其一）。
///
/// Android `CompareAudio` 的 `audioGroupIndexOf`（环境 = 组 0、内录 = 组 1）是唯一映射真源；
/// iOS 侧没有 Media3 的 `TrackSelectionOverride`，改用 `AVPlayerItem` 的 `mediaSelection` 或
/// 直接静音另一条音轨。本结构只给**计划**，落地在平台层。
public struct CompareAudioPlan: Equatable, Sendable {
    /// 目标音频轨种类；nil = 用容器默认选择（即第一条音轨）。
    public var track: PlayerAudioTrackKind?
    /// 期望音量（0...1）。
    public var volume: Float

    public init(track: PlayerAudioTrackKind?, volume: Float) {
        self.track = track
        self.volume = max(0, min(1, volume))
    }
}

/// 分屏音频计划器（**单引擎**）：分屏右窗只有一台播放器，没有「另一侧」可静音，
/// 因此不做「左/右分轨静音」，只决定这一台引擎选哪条音轨、给多大音量。
public enum CompareAudioPlanner {

    /// 单音轨片即便请求内录也回落环境轨（清 override 口径）。
    /// `dualAudio` = 容器是否确有 ≥2 条音轨（平台层用 `AVAsset.tracks(withMediaType:)` 判定）。
    public static func plan(dualAudio: Bool, pick: PlayerAudioTrackKind) -> CompareAudioPlan {
        let effective: PlayerAudioTrackKind? = (pick == .internalCapture && dualAudio) ? .internalCapture : nil
        return CompareAudioPlan(track: effective, volume: 1.0)
    }
}

/// 双播放器可插入协议（E2 分屏接入契约的纯值部分）。
///
/// 本任务**不裁决分屏布局**：Android 是 App 内左右栏（`SPLIT_SQUEEZE=0.5f`），
/// iPhone 无系统级 Split View，是否实现及取向属产品决策（见 `docs/mappings/pages.md` P-017、
/// `platform-differences.md` PD-006）。这里只固定「两个独立播放器 + 统一时间线 + 状态回灌」
/// 的最小契约，使 E2 无论选哪种布局都能直接插入。
public struct ComparePlaybackSlots: Equatable, Sendable {
    public var left: CompareSlotState
    public var right: CompareSlotState

    public init(left: CompareSlotState, right: CompareSlotState) {
        self.left = left
        self.right = right
    }
}

/// 单个槽位的播放状态快照（平台层每拍回灌；`isBlackout` = 已施加黑层 = dormant）。
public struct CompareSlotState: Equatable, Sendable {
    public var position: CMTime
    public var duration: CMTime
    public var isPlaying: Bool
    public var isBlackout: Bool

    public init(position: CMTime, duration: CMTime, isPlaying: Bool, isBlackout: Bool) {
        self.position = position
        self.duration = duration
        self.isPlaying = isPlaying
        self.isBlackout = isBlackout
    }

    public static var empty: CompareSlotState {
        CompareSlotState(position: .zero, duration: .zero, isPlaying: false, isBlackout: false)
    }
}
