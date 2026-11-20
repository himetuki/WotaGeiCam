import Foundation
import CoreGraphics
import CoreMedia

// WotaCore 纯逻辑：播放器对外状态、AB 段、倍速档、时间线几何、帧步进算术。
//
// 本文件**不 import AVFoundation / UIKit**，只依赖 CoreMedia 的 `CMTime` 值语义
// （`CoreMedia` 是 iOS 4 起的系统库，`CMTime` / `CMTimeRange` 结构与比较器全程可用），
// 因此平台层（`AVPlayer` 封装）与 UI 层（对比编排）共用同一套口径与同一份钳制。
//
// 对齐 Android：
// - `player/PlayerEngine.kt`（`LoopMode` / `AbRange` / `PlayerSpeedTiers` / 状态字段）
// - `player/FrameStep.kt`（帧栅格推进算术）
// - `player/CompareTimeline.kt`（`TimelineGeometry` / `DecideInput` / `DecideCommands`）
//
// iOS 侧新增口径（Android 无对应物）：
// - 逐帧步长**必须来自 asset 的 `nominalFrameRate`**；读不到时返回**显式降级**而不是
//   固定 25fps 硬编码。旧设备 `AVAssetImageGenerator` 逐帧失败时，失败原因走状态回给 UI，
//   不静默把「seek 到近似帧」冒充精确逐帧。

// MARK: - 播放状态

/// 播放器对外状态。与 Android `PlayerEngine` 的 `playbackState` + `isPlaying` + `error` 三读数对齐，
/// 并补上 iOS 侧必须显式暴露的「未加载 / 加载失败」。
public enum PlayerPlaybackState: Equatable, Sendable {
    /// 尚未 attach，或已释放。
    case idle
    /// 已 attach，等待 `AVPlayerItem.status == .readyToPlay`。
    case loading
    /// 可播（`readyToPlay`）。
    case ready
    /// 正在播放（`rate != 0`）。
    case playing
    /// 已暂停（`readyToPlay` 且 `rate == 0`）。
    case paused
    /// 已到结尾（`AVPlayerItem.didPlayToEndTime`，停在末帧）。
    case ended
    /// 加载/解码失败（`AVPlayerItem.status == .failed` 或 `AVPlayer.error`）。
    case failed(PlayerPlaybackFailure)

    public var isReady: Bool {
        switch self {
        case .ready, .playing, .paused, .ended:
            return true
        default:
            return false
        }
    }

    public var isPlaying: Bool {
        if case .playing = self { return true }
        return false
    }

    public var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}

/// 播放失败的显式分类。`underlying` 只带字符串描述，避免把 `NSError` 泄漏进纯逻辑层。
public struct PlayerPlaybackFailure: Equatable, Sendable {
    public var kind: PlayerPlaybackFailureKind
    public var reason: String

    public init(kind: PlayerPlaybackFailureKind, reason: String) {
        self.kind = kind
        self.reason = reason
    }
}

public enum PlayerPlaybackFailureKind: String, Equatable, Sendable {
    /// 系统给出的错误域/码（`NSError` 映射而来）。
    case avFoundationError
    /// 无法读出视频轨（容器无视频轨 / 只有音频）。
    case noVideoTrack
    /// 资源不可读（文件缺失 / 权限不足 / 容器损坏）。
    case unreadable
    /// 未知兜底。
    case unknown
}

/// 循环模式：不循环 / 单视频循环。与 Android `LoopMode` 对齐；
/// AB 段循环走 `PlayerABLoop`，不占用本枚举。
public enum PlayerLoopMode: Equatable, Sendable {
    case off
    case one
}

// MARK: - AB 段

/// AB 段（A/B 均为 `CMTime`）。`isValid` 才允许开启循环：A 有限且非负、B 有限且严格晚于 A。
public struct PlayerABRange: Equatable, Sendable {
    public var start: CMTime
    public var end: CMTime
    public var loopEnabled: Bool

    public init(start: CMTime, end: CMTime, loopEnabled: Bool) {
        self.start = start
        self.end = end
        self.loopEnabled = loopEnabled
    }

    /// 未打点的空段（A/B 均为无效 `CMTime`）。
    public static var empty: PlayerABRange {
        PlayerABRange(start: .invalid, end: .invalid, loopEnabled: false)
    }

    /// 是否具备循环条件。**刻意不含**「时长至少一帧」之外的额外门槛：
    /// 帧数容差由调用方（平台层）在设置时给出，本结构只保证时间轴合法性。
    public var isValid: Bool {
        guard start.isValid, end.isValid, start.isNumeric, end.isNumeric else { return false }
        guard start.seconds >= 0 else { return false }
        return end.seconds > start.seconds
    }

    /// 循环是否真的生效（开关 + 范围合法）。
    public var isLoopActive: Bool {
        loopEnabled && isValid
    }

    /// 时长（无效段为 0）。
    public var duration: CMTime {
        guard isValid else { return .zero }
        return end - start
    }
}

/// AB 段的纯状态迁移（Android `PlayerEngine.markA/markB/toggleAbLoop/clearAb` 的等价口径）。
public enum PlayerABControl {

    /// 未打点：返回 `.empty`。
    public static func cleared() -> PlayerABRange { .empty }

    /// 打 A：若已有 B 且 `B <= A`，清 B 并关循环（Android `markA` 的修复口径）。
    public static func markedA(_ range: PlayerABRange, position: CMTime) -> PlayerABRange {
        var end = range.end
        var loop = range.loopEnabled
        if end.isValid, end.seconds <= position.seconds {
            end = .invalid
            loop = false
        }
        let next = PlayerABRange(start: position, end: end, loopEnabled: loop)
        // 范围不再合法时循环必须落下，避免「开关亮着但永不回绕」。
        return PlayerABRange(start: next.start, end: next.end, loopEnabled: loop && next.isValid)
    }

    /// 打 B：需已有 A 且 `B > A`，成功即自动开循环（Android `markB` 口径）。
    /// 失败返回 nil，UI 给「AB 无效」提示。
    public static func markedB(_ range: PlayerABRange, position: CMTime) -> PlayerABRange? {
        guard range.start.isValid, range.start.isNumeric else { return nil }
        guard position.isValid, position.isNumeric else { return nil }
        guard position.seconds > range.start.seconds else { return nil }
        return PlayerABRange(start: range.start, end: position, loopEnabled: true)
    }

    /// 循环开关：仅在范围合法时切换；不合法返回 nil（UI 给提示）。
    public static func toggled(_ range: PlayerABRange) -> PlayerABRange? {
        guard range.isValid else { return nil }
        return PlayerABRange(start: range.start, end: range.end, loopEnabled: !range.loopEnabled)
    }

    /// 播放位置越过 B 时是否该回绕（含「未落地 seek」由调用方负责，本函数只判时间）。
    public static func shouldWrap(position: CMTime, range: PlayerABRange) -> Bool {
        guard range.isLoopActive else { return false }
        guard position.isValid, position.isNumeric else { return false }
        return position.seconds >= range.end.seconds
    }

    /// 回绕目标（始终为 A）。
    public static func wrapTarget(_ range: PlayerABRange) -> CMTime {
        range.start
    }

    /// 持久化/恢复时的收整：`B <= A` 即清 B 与循环（Android `restoreAb` 口径，
    /// 旧数据里可能残留交叉刻度）。
    public static func normalized(_ range: PlayerABRange) -> PlayerABRange {
        guard range.isValid else {
            return PlayerABRange(start: range.start, end: range.end, loopEnabled: false)
        }
        return range
    }
}

// MARK: - 倍速

/// 倍速档位。Android 需求（0.1 / 0.3 / 0.5 / 0.75 / 1.0）逐档对齐；
/// iOS 侧由 `AVPlayer.rate` 落地，音高保持 1.0（`AVPlayer` 不随 rate 变调，
/// 与 Android `audioPitchFor` 恒 1.0 的用户口径一致）。
public enum PlayerSpeedTiers {
    public static let tiers: [Float] = [0.1, 0.3, 0.5, 0.75, 1.0]
    public static let `default`: Float = 1.0

    /// 归一到最近档。
    public static func normalize(_ value: Float) -> Float {
        var best = PlayerSpeedTiers.default
        var bestDistance = Float.greatestFiniteMagnitude
        for tier in tiers {
            let distance = abs(tier - value)
            if distance < bestDistance {
                bestDistance = distance
                best = tier
            }
        }
        return best
    }
}

// MARK: - 帧步进

/// 缩放 / 平移的纯几何钳制（Android `clampPan` 的等价重写）。
///
/// 平移域 = 仅放大态，逐轴 `±视口×(s−1)/2`；`s <= 1` 时域为 0（自然禁用）。
/// 捏回 1x 的过程中平移随夹取连续归零，无需专门复位动作。
public enum PlayerZoomPanMath {

    public static let minScale: CGFloat = 0.1
    public static let maxScale: CGFloat = 4.0

    /// 钳制平移量（pt）。
    public static func clampPan(
        _ pan: CGSize,
        scale: CGFloat,
        viewport: CGSize
    ) -> CGSize {
        return CGSize(
            width: clampAxis(pan.width, half: Double(viewport.width) * Double(scale - 1) / 2.0),
            height: clampAxis(pan.height, half: Double(viewport.height) * Double(scale - 1) / 2.0)
        )
    }

    public static func clampAxis(_ value: CGFloat, half: Double) -> CGFloat {
        if half <= 0 { return 0 }
        return CGFloat(max(-half, min(half, Double(value))))
    }

    /// 缩放钳制。
    public static func clampScale(_ scale: CGFloat) -> CGFloat {
        return max(PlayerZoomPanMath.minScale, min(PlayerZoomPanMath.maxScale, scale))
    }
}

/// 逐帧步进结果。**明确区分「已接受的目标」与「被拒绝的步进」**：
/// 拒绝时 `isExact` 恒 false，UI 必须显示降级提示，不得把近似 seek 说成精确逐帧。
public struct PlayerFrameStepOutcome: Equatable, Sendable {
    public var target: CMTime
    public var frameIndex: Int64
    public var isExact: Bool
    /// 拒绝原因（`isExact == false` 时非 nil）。
    public var failure: PlayerFrameStepFailure?

    public init(target: CMTime, frameIndex: Int64, isExact: Bool, failure: PlayerFrameStepFailure?) {
        self.target = target
        self.frameIndex = frameIndex
        self.isExact = isExact
        self.failure = failure
    }
}

public enum PlayerFrameStepFailure: Equatable, Sendable {
    /// 未在暂停态（iOS 的逐帧接口要求暂停）。
    case notPaused
    /// 时间基准非法 / 未加载。
    case invalidTimebase
    /// 帧率未知（asset 未报 `nominalFrameRate`）。
    case frameRateUnknown
    /// 已到边界（前进到末帧之后 / 后退到第 0 帧之前）。
    case atBoundary
    /// 平台逐帧接口在旧系统上不可用（`AVPlayerItem.step(byCount:)` 需要 iOS 6+，
    /// 但部分旧设备/格式上生成器失败）。
    case unsupported
}

/// 逐帧步进的纯算术（Android `FrameStep.next` 的等价重写）。
///
/// 性质：**连点单调前进，来回点不漂移**。
/// - 浮点帧长（29.97fps → 33.3667ms）下，先按容差把位置吸到最近帧首，再定位帧号；
/// - `pendingTarget` 非 nil 时以它为 anchor（seek 尚未落地期间位置仍是旧值，连点会互相吞掉）。
public enum PlayerFrameStepMath {

    /// 帧首定位容差（ms）。整毫秒截断本身 <1ms，再留呈现抖动余量。
    public static let snapToleranceMs: Double = 1.5

    /// 默认兜底帧长（ms）。**仅在 `nominalFrameRate` 不可读时使用**，
    /// 并且调用方必须把 `isExact` 置为 false（降级提示），不得当作精确逐帧。
    public static let fallbackFrameMs: Double = 40.0

    /// 计算下一帧目标。
    ///
    /// - Parameters:
    ///   - pendingTarget: 上一次已发出但尚未落地的步进目标；nil 表示无挂起步进。
    ///   - position: 当前播放位置（秒）。
    ///   - frameSeconds: 单帧时长（秒，>0）。
    ///   - durationSeconds: 片长（秒）；<=0 表示未知，不做上限钳制。
    ///   - forward: true = 前进，false = 后退。
    /// - Returns: 目标时间（秒）与帧号。
    public static func nextTarget(
        pendingTarget: CMTime?,
        position: CMTime,
        frameSeconds: Double,
        durationSeconds: Double,
        forward: Bool
    ) -> (time: CMTime, frameIndex: Int64) {
        let anchorSeconds: Double
        if let pending = pendingTarget, pending.isValid, pending.isNumeric {
            anchorSeconds = pending.seconds
        } else if position.isValid, position.isNumeric {
            anchorSeconds = max(position.seconds, 0)
        } else {
            anchorSeconds = 0
        }
        if frameSeconds <= 0 {
            return (CMTime(seconds: anchorSeconds, preferredTimescale: 600), 0)
        }
        let toleranceSeconds = PlayerFrameStepMath.snapToleranceMs / 1000.0
        let raw = forward
            ? (anchorSeconds + toleranceSeconds) / frameSeconds
            : (anchorSeconds - toleranceSeconds) / frameSeconds
        let frame = forward ? floor(raw) : ceil(raw)
        var targetFrame = forward ? Int64(frame) + 1 : Int64(frame) - 1
        if targetFrame < 0 { targetFrame = 0 }
        var targetSeconds = Double(targetFrame) * frameSeconds
        if durationSeconds > 0, targetSeconds > durationSeconds {
            targetSeconds = durationSeconds
            if forward {
                // 回退到最后一帧的帧首，避免停在「片长」这个非帧首值上。
                let lastFrame = Int64(floor(durationSeconds / frameSeconds))
                targetFrame = lastFrame
                targetSeconds = Double(lastFrame) * frameSeconds
            }
        }
        if targetSeconds < 0 { targetSeconds = 0 }
        return (CMTime(seconds: targetSeconds, preferredTimescale: 600), targetFrame)
    }
}

// MARK: - 时间线（分屏对照用纯逻辑）

/// 素材域。与 Android `CompareDomain` 对齐。
public enum CompareDomain: Equatable, Sendable {
    /// 左独播：主钟 = 左，右 dormant。
    case leftSolo
    /// 右独播：主钟 = 右，左 dormant。
    case rightSolo
    /// 双素材域：主钟 = 左，右软跟随。
    case dual
    /// 时间线末：两段素材都已尽。
    case timelineEnd
}

/// 时间线几何（纯函数）：由两段素材时长与偏移推出 Tmin/Tmax、覆盖判定与素材域落点。
///
/// 与 Android `TimelineGeometry` 逐条同口径（`rightPos = leftPos + offset`）。
public struct CompareTimelineGeometry: Equatable, Sendable {
    public var leftDuration: CMTime
    public var rightDuration: CMTime
    public var offset: CMTime

    public init(leftDuration: CMTime, rightDuration: CMTime, offset: CMTime) {
        self.leftDuration = leftDuration
        self.rightDuration = rightDuration
        self.offset = offset
    }

    /// 时间线下限：`min(0, −offset)`。
    public var tMin: CMTime {
        let neg = -offset.seconds
        return CMTime(seconds: min(0, neg), preferredTimescale: 600)
    }

    /// 时间线上限：`max(L, R−offset)`。
    public var tMax: CMTime {
        let rightEdge = rightDuration.seconds - offset.seconds
        return CMTime(seconds: max(leftDuration.seconds, rightEdge), preferredTimescale: 600)
    }

    /// T 是否落在左轨素材段 `[0, L)`。
    public func leftCovers(_ t: Double) -> Bool {
        return t >= 0 && t < leftDuration.seconds
    }

    /// T 是否落在右轨素材段 `[−offset, R−offset)`。
    public func rightCovers(_ t: Double) -> Bool {
        return t >= -offset.seconds && t < rightDuration.seconds - offset.seconds
    }

    /// T 处左引擎应处的素材位置：域内即 T；早于域头 park 到 0；越过域尾 park 到素材尽头。
    public func leftTarget(_ t: Double) -> Double {
        if leftCovers(t) { return t }
        if t < 0 { return 0 }
        return leftDuration.seconds
    }

    /// T 处右引擎应处的素材位置：域内即 `T + offset`；早于域头 park 到 0；越过域尾 park 到素材尽头。
    public func rightTarget(_ t: Double) -> Double {
        if rightCovers(t) { return t + offset.seconds }
        if t < -offset.seconds { return 0 }
        return rightDuration.seconds
    }
}

/// `decide` 的输入快照（一拍一份；黑层位是上一拍输出的回灌）。
public struct CompareDecideInput {
    public var geometry: CompareTimelineGeometry
    public var leftLive: Double
    public var rightLive: Double
    /// 用户期望播放（两引擎播放意图的并集）。
    public var userPlaying: Bool
    /// AB 段循环激活：回绕归 AB 钩子，`decide` 不抢。
    public var abLoopActive: Bool
    /// 全程循环开关（时间线末回绕；关 = 末帧停驻）。
    public var timelineLoop: Bool
    /// 用户拖动中：压 seek/nudge/wrap。
    public var dragging: Bool
    /// 当前已施加的左窗黑层（左 dormant）。
    public var blackLeft: Bool
    /// 当前已施加的右窗黑层（右 dormant）。
    public var blackRight: Bool
    /// 硬 seek 阈值（秒）。
    public var hardResync: Double

    public init(
        geometry: CompareTimelineGeometry,
        leftLive: Double,
        rightLive: Double,
        userPlaying: Bool,
        abLoopActive: Bool,
        timelineLoop: Bool,
        dragging: Bool,
        blackLeft: Bool,
        blackRight: Bool,
        hardResync: Double
    ) {
        self.geometry = geometry
        self.leftLive = leftLive
        self.rightLive = rightLive
        self.userPlaying = userPlaying
        self.abLoopActive = abLoopActive
        self.timelineLoop = timelineLoop
        self.dragging = dragging
        self.blackLeft = blackLeft
        self.blackRight = blackRight
        self.hardResync = hardResync
    }
}

/// `decide` 的输出指令（该拍要做什么；null / false = 不动）。
public struct CompareDecideCommands: Equatable, Sendable {
    public var seekLeft: Double?
    public var seekRight: Double?
    public var pauseLeft: Bool
    public var pauseRight: Bool
    public var resumeLeft: Bool
    public var resumeRight: Bool
    public var blackLeft: Bool
    public var blackRight: Bool
    /// 右片软追赶漂移量（秒，正 = 右超前）。
    public var nudgeRightDrift: Double?

    public init(
        seekLeft: Double? = nil,
        seekRight: Double? = nil,
        pauseLeft: Bool = false,
        pauseRight: Bool = false,
        resumeLeft: Bool = false,
        resumeRight: Bool = false,
        blackLeft: Bool = false,
        blackRight: Bool = false,
        nudgeRightDrift: Double? = nil
    ) {
        self.seekLeft = seekLeft
        self.seekRight = seekRight
        self.pauseLeft = pauseLeft
        self.pauseRight = pauseRight
        self.resumeLeft = resumeLeft
        self.resumeRight = resumeRight
        self.blackLeft = blackLeft
        self.blackRight = blackRight
        self.nudgeRightDrift = nudgeRightDrift
    }
}

/// 对比播放的双轨时间线内核（纯逻辑，无平台依赖）。
/// 与 Android `CompareTimeline` 逐分支同口径，算法重写、不翻译 Kotlin。
public enum CompareTimeline {

    /// 素材域判定。黑层在册时以在册域为主钟，退出条件 = 主钟 T 回到双覆盖区。
    public static func domainOf(
        _ geometry: CompareTimelineGeometry,
        leftLive: Double,
        rightLive: Double,
        blackLeft: Bool,
        blackRight: Bool
    ) -> CompareDomain {
        let tL = leftLive
        let tR = rightLive - geometry.offset.seconds
        // 时间线末：两段素材都已尽。该判定必须排在黑层在册分支之前（末态本来就要撤黑层）。
        if tL >= geometry.leftDuration.seconds, tR >= geometry.rightDuration.seconds - geometry.offset.seconds {
            return .timelineEnd
        }
        if blackLeft {
            return geometry.leftCovers(tR) && geometry.rightCovers(tR) ? .dual : .rightSolo
        }
        if blackRight {
            return geometry.leftCovers(tL) && geometry.rightCovers(tL) ? .dual : .leftSolo
        }
        if tR < 0, geometry.rightCovers(tR) { return .rightSolo }
        if geometry.leftCovers(tL), geometry.rightCovers(tL) { return .dual }
        if geometry.leftCovers(tL) { return .leftSolo }
        if geometry.rightCovers(tR), tR >= 0 { return .rightSolo }
        return .timelineEnd
    }

    /// 当前时间线时刻。
    public static func resolveT(
        _ geometry: CompareTimelineGeometry,
        leftLive: Double,
        rightLive: Double,
        blackLeft: Bool,
        blackRight: Bool
    ) -> Double {
        switch domainOf(geometry, leftLive: leftLive, rightLive: rightLive, blackLeft: blackLeft, blackRight: blackRight) {
        case .dual, .leftSolo:
            return leftLive
        case .rightSolo:
            return rightLive - geometry.offset.seconds
        case .timelineEnd:
            return geometry.tMax.seconds
        }
    }

    /// 一拍决策。
    public static func decide(_ input: CompareDecideInput) -> CompareDecideCommands {
        let g = input.geometry
        switch domainOf(g, leftLive: input.leftLive, rightLive: input.rightLive, blackLeft: input.blackLeft, blackRight: input.blackRight) {
        case .dual:
            var commands = CompareDecideCommands()
            if input.blackLeft, input.userPlaying { commands.resumeLeft = true }
            if input.blackRight, input.userPlaying { commands.resumeRight = true }
            if !input.dragging {
                let desired = input.leftLive + g.offset.seconds
                let drift = input.rightLive - desired
                if abs(drift) > input.hardResync {
                    commands.seekRight = desired
                } else {
                    commands.nudgeRightDrift = drift
                }
            }
            return commands

        case .rightSolo:
            return CompareDecideCommands(blackLeft: true, pauseLeft: true)

        case .leftSolo:
            return CompareDecideCommands(blackRight: true, pauseRight: true)

        case .timelineEnd:
            if input.userPlaying, !input.dragging, input.timelineLoop, !input.abLoopActive {
                let t = g.tMin.seconds
                let leftAtT = g.leftCovers(t)
                let rightAtT = g.rightCovers(t)
                return CompareDecideCommands(
                    seekLeft: g.leftTarget(t),
                    seekRight: g.rightTarget(t),
                    resumeLeft: leftAtT,
                    resumeRight: rightAtT,
                    pauseLeft: !leftAtT,
                    pauseRight: !rightAtT,
                    blackLeft: !leftAtT,
                    blackRight: !rightAtT
                )
            }
            return CompareDecideCommands()
        }
    }
}
