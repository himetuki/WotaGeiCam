import Foundation
import AVFoundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 对比页两台播放器的**插入协议**（E1 → E2 的分屏接入契约）。
///
/// E1 **不裁决分屏布局**：Android 是 App 内左右栏，iPhone 无系统级 Split View，做不做、怎么排
/// 属产品决策（见 `docs/mappings/pages.md` P-017）。本协议只固定「两个独立播放器怎么被编排」：
/// 谁负责装载 / 启停 / 定位 / 音量 / 黑层，编排层只通过本协议发幂等命令。
///
/// 任何实现（`PlayerEngine` 适配器、外来来源播放器、后续单引擎分屏）都可直接插入，
/// 不要求继承、不要求同一实现。
protocol CompareSlotDriving: AnyObject {

    /// 是否可播（`readyToPlay` 且未失败）。
    var isReady: Bool { get }
    /// 是否正在播放。
    var isPlaying: Bool { get }
    /// 当前素材位置（秒）。
    var positionSeconds: Double { get }
    /// 素材时长（秒）；未知为 0。
    var durationSeconds: Double { get }

    /// AB 段循环是否生效（决定 compare 时间线是否让位给 AB 回绕）。
    var abLoopActive: Bool { get }

    /// 装载素材（同一路径可重入；实现方自行幂等）。
    func prepare(url: URL)
    /// 播放（实现方在未就绪时可以先记下意图）。
    func play()
    /// 暂停（非破坏性，不可 release）。
    func pause()
    /// 精确定位（秒）。
    func seek(to seconds: Double)
    /// 音量（0...1）；对比页用它实现「只播一侧音轨」。
    func setVolume(_ volume: Float)
    /// 黑层（dormant 一侧由 UI 盖黑；实现方只记录状态，不改播放位置）。
    func setBlackout(_ blackout: Bool)
    /// 选音轨（环境 / 内录）；实现方不支持时保持容器默认即可。
    func selectAudioTrack(_ kind: PlayerAudioTrackKind)
    /// 软追赶：右轨相对主钟的漂移（秒，正 = 右超前）。实现方可用微调速率或忽略。
    func nudgeSpeed(forDriftSeconds drift: Double)
}

extension CompareSlotDriving {
    func setBlackout(_ blackout: Bool) {}
    func selectAudioTrack(_ kind: PlayerAudioTrackKind) {}
    func nudgeSpeed(forDriftSeconds drift: Double) {}
}

/// `PlayerEngine` 的对比槽适配器（E2 可直接 new 两个）。
///
/// 复用 `PlayerEngine` 的 AB / 进度 / 逐帧能力，不重复实现播放内核；
/// 只补对比页需要的「槽位状态」与黑层 / 音量语义。
final class PlayerEngineCompareSlot: CompareSlotDriving {

    let engine: PlayerEngine
    private(set) var isBlackout: Bool = false
    private var volume: Float = 1

    init(engine: PlayerEngine = PlayerEngine()) {
        self.engine = engine
    }

    var isReady: Bool {
        return engine.statusSnapshot.isReady
    }

    var isPlaying: Bool {
        return engine.statusSnapshot.isPlaying
    }

    var positionSeconds: Double {
        return engine.livePositionSeconds()
    }

    var durationSeconds: Double {
        return engine.durationSeconds
    }

    var abLoopActive: Bool {
        let range = engine.abRangeSnapshot
        return range.loopEnabled && range.isValid
    }

    func prepare(url: URL) {
        engine.attach(url: url)
    }

    func play() {
        engine.setPlaying(true)
    }

    func pause() {
        engine.setPlaying(false)
    }

    func seek(to seconds: Double) {
        engine.seek(to: seconds, commit: true)
    }

    func setVolume(_ volume: Float) {
        self.volume = max(0, min(1, volume))
        engine.setVolume(self.volume)
    }

    func setBlackout(_ blackout: Bool) {
        isBlackout = blackout
    }

    /// iOS 侧没有 Media3 的 `TrackSelectionOverride`；容器默认即第 0 条音轨（环境）。
    /// 内录轨选择留待 E2 按实际容器能力决定（这里显式不伪造「选了有效果」）。
    func selectAudioTrack(_ kind: PlayerAudioTrackKind) {
        // 默认环境轨 = 容器首条音轨，无需动作；内录轨需要 AVMutableAudioMix，
        // 超出 E1 的「基础播放」边界，E2 需要时在平台层补。
    }
}

/// 对比页的编排状态（纯运行态，不含任何布局尺寸 / 左右栏宽 / 取向）。
///
/// **布局归 E2**：本类只暴露两个槽位的播放状态、时间线统一 seek、黑层与音频路由。
final class ComparePlayerCoordinator {

    // MARK: - 槽位

    private(set) var left: CompareSlotDriving
    private(set) var right: CompareSlotDriving

    /// 时间线几何（两段时长 + 双轨偏移）。
    private(set) var geometry: CompareTimelineGeometry

    /// 硬 seek 阈值（秒），与 Android `HARD_RESYNC_MS = 250` 对齐。
    var hardResyncSeconds: Double = 0.25

    /// 时间线末是否整段循环（关 = 停末帧）。
    var timelineLoop: Bool = false

    /// 用户是否期望播放（单击启停的意图，不是当前瞬时 rate）。
    private(set) var userPlaying: Bool = false

    /// 用户是否正在拖动统一进度条。
    var isDragging: Bool = false

    /// 左/右窗黑层（dormant）会话态；由每拍 decide 回灌。
    private(set) var leftBlackout: Bool = false
    private(set) var rightBlackout: Bool = false

    /// 镜像（左右翻转）状态：**只是显示态**，不触发播放器动作，由 E2 的视图自己消费。
    private(set) var leftMirrored: Bool = false
    private(set) var rightMirrored: Bool = false

    // MARK: - 回调

    /// 每拍状态回灌：两个槽位快照 + 当前素材域。
    var onSlotsChanged: ((ComparePlaybackSlots, CompareDomain) -> Void)?
    /// 黑层变化（E2 据此渲染黑幕 / 提示）。
    var onBlackoutChanged: ((_ left: Bool, _ right: Bool) -> Void)?
    /// 镜像变化（E2 据此翻转画面）。
    var onMirrorChanged: ((_ left: Bool, _ right: Bool) -> Void)?

    // MARK: - 运行态

    private var tickTimer: Timer?
    private var tornDown = false

    init(
        left: CompareSlotDriving,
        right: CompareSlotDriving,
        leftDuration: CMTime = .zero,
        rightDuration: CMTime = .zero,
        offset: CMTime = .zero
    ) {
        self.left = left
        self.right = right
        self.geometry = CompareTimelineGeometry(
            leftDuration: leftDuration,
            rightDuration: rightDuration,
            offset: offset
        )
    }

    deinit {
        tickTimer?.invalidate()
        tickTimer = nil
    }

    // MARK: - 装载 / 几何

    /// 装载两段素材（不清几何；调用方随后用 `updateDurations` 或 `setOffset` 刷新）。
    func prepare(leftURL: URL, rightURL: URL) {
        left.prepare(url: leftURL)
        right.prepare(url: rightURL)
    }

    /// 刷新时长（素材 ready 后调用）。
    func updateDurations(leftDuration: CMTime, rightDuration: CMTime) {
        geometry = CompareTimelineGeometry(
            leftDuration: leftDuration,
            rightDuration: rightDuration,
            offset: geometry.offset
        )
    }

    /// 设置双轨偏移（秒；正 = 右轨晚响）。
    func setOffsetSeconds(_ seconds: Double) {
        let offset = CMTime(seconds: seconds, preferredTimescale: 600)
        geometry = CompareTimelineGeometry(
            leftDuration: geometry.leftDuration,
            rightDuration: geometry.rightDuration,
            offset: offset
        )
    }

    /// 左/右画面镜像（仅显示态）。
    func setMirror(side: CompareSide, mirrored: Bool) {
        switch side {
        case .left:
            if leftMirrored == mirrored { return }
            leftMirrored = mirrored
        case .right:
            if rightMirrored == mirrored { return }
            rightMirrored = mirrored
        }
        onMirrorChanged?(leftMirrored, rightMirrored)
    }

    // MARK: - 启停 / 定位

    /// 当前时间线时刻（秒，`CompareTimeline.resolveT` 同口径；UI 读数用）。
    var timelineNowSeconds: Double {
        return CompareTimeline.resolveT(
            geometry,
            leftLive: left.positionSeconds,
            rightLive: right.positionSeconds,
            blackLeft: leftBlackout,
            blackRight: rightBlackout
        )
    }

    /// 时间线总跨度（秒，`Tmax − Tmin`；UI 进度条用）。
    var timelineSpanSeconds: Double {
        return max(0, geometry.tMax.seconds - geometry.tMin.seconds)
    }

    /// 用户点播放：记录意图，**让当前时间线覆盖到的槽位真正起播**。
    ///
    /// `decide` 的 `resumeXxx` 只负责「黑层在册的休眠轨回到域内后唤醒」，并不负责首次起播；
    /// 进页/暂停后点播放必须由这里按几何覆盖判定直接起播，否则双素材域是「永不起播」不动点。
    /// 时间线末（两轨都已尽）先统一回绕到 `Tmin` 再起播——与 Android `setPlaying` 同口径。
    /// 回绕后的覆盖判定用 `Tmin` 本身，不读尚未落地的 seek 位置。
    func play() {
        guard !tornDown else { return }
        if geometry.tMax.seconds > geometry.tMin.seconds,
           timelineNowSeconds >= geometry.tMax.seconds - 0.001 {
            let start = geometry.tMin.seconds
            seekTimeline(to: start)
            userPlaying = true
            if geometry.leftCovers(start) { left.play() }
            if geometry.rightCovers(start) { right.play() }
            tick()
            return
        }
        userPlaying = true
        let now = timelineNowSeconds
        if geometry.leftCovers(now) { left.play() }
        if geometry.rightCovers(now) { right.play() }
        tick()
    }

    /// 用户点暂停。
    func pause() {
        guard !tornDown else { return }
        userPlaying = false
        left.pause()
        right.pause()
        tick()
    }

    /// 统一时间线 seek（T ∈ [Tmin, Tmax]）。
    ///
    /// 有素材一侧精确落位；无素材一侧 park（早于域头 → 0，越域尾 → 素材尽头）
    /// 并就地暂停，同时亮黑层——与 Android `seekTimeline` 同口径。
    func seekTimeline(to seconds: Double) {
        guard !tornDown else { return }
        let t = max(geometry.tMin.seconds, min(seconds, geometry.tMax.seconds))
        let atEnd = t >= geometry.tMax.seconds
        left.seek(to: geometry.leftTarget(t))
        right.seek(to: geometry.rightTarget(t))
        if !geometry.leftCovers(t) {
            left.pause()
        }
        if !geometry.rightCovers(t) {
            right.pause()
        }
        applyBlackout(
            left: !atEnd && !geometry.leftCovers(t),
            right: !atEnd && !geometry.rightCovers(t)
        )
        tick()
    }

    /// AB 回绕钩子：统一轴回到 A 时，两轨都经统一 seek 落位。
    /// 用法：`leftEngine.abWrapHook = { a, _ in coordinator.seekTimeline(to: a.seconds); return true }`
    func handleABWrap(targetSeconds: Double) {
        seekTimeline(to: targetSeconds)
    }

    // MARK: - 音频路由

    /// 选中哪一侧发声。**对比页只播其一**：另一侧音量压 0（不做混音，避免梳状滤波）。
    ///
    /// - Parameters:
    ///   - activeSide: 发声侧。
    ///   - track: 期望音轨（内录缺失时按 `CompareAudioPlanner` 回落环境轨）。
    ///   - dualAudio: 发声侧容器是否有 ≥2 条音轨。
    func applyAudio(activeSide: CompareSide, track: PlayerAudioTrackKind, dualAudio: Bool) {
        let plan = CompareAudioPlanner.plan(dualAudio: dualAudio, pick: track)
        switch activeSide {
        case .left:
            left.applyAudio(plan: plan)
            right.setVolume(0)
        case .right:
            right.applyAudio(plan: plan)
            left.setVolume(0)
        }
    }

    // MARK: - 每拍编排

    /// 手动驱动一拍（由 E2 的定时器 / 任一播放器的进度回调调用）。
    ///
    /// 顺序固定：先停/走 → 再 seek → 再软追赶 → 最后黑层回灌。
    func tick() {
        guard !tornDown else { return }
        let leftLive = left.positionSeconds
        let rightLive = right.positionSeconds
        let input = CompareDecideInput(
            geometry: geometry,
            leftLive: leftLive,
            rightLive: rightLive,
            userPlaying: userPlaying || left.isPlaying || right.isPlaying,
            abLoopActive: left.abLoopActive || right.abLoopActive,
            timelineLoop: timelineLoop,
            dragging: isDragging,
            blackLeft: leftBlackout,
            blackRight: rightBlackout,
            hardResync: hardResyncSeconds
        )
        let commands = CompareTimeline.decide(input)
        apply(commands)
        let domain = CompareTimeline.domainOf(
            geometry,
            leftLive: left.positionSeconds,
            rightLive: right.positionSeconds,
            blackLeft: leftBlackout,
            blackRight: rightBlackout
        )
        onSlotsChanged?(
            ComparePlaybackSlots(
                left: CompareSlotState(
                    position: CMTime(seconds: max(0, left.positionSeconds), preferredTimescale: 600),
                    duration: CMTime(seconds: max(0, left.durationSeconds), preferredTimescale: 600),
                    isPlaying: left.isPlaying,
                    isBlackout: leftBlackout
                ),
                right: CompareSlotState(
                    position: CMTime(seconds: max(0, right.positionSeconds), preferredTimescale: 600),
                    duration: CMTime(seconds: max(0, right.durationSeconds), preferredTimescale: 600),
                    isPlaying: right.isPlaying,
                    isBlackout: rightBlackout
                )
            ),
            domain
        )
    }

    /// 启动自动节拍（0.2s，与 Android 默认 compareTickMs 对齐）。
    func startTicking(interval: TimeInterval = 0.2) {
        guard !tornDown else { return }
        tickTimer?.invalidate()
        tickTimer = Timer.scheduledTimer(withTimeInterval: max(0.1, interval), repeats: true) { [weak self] _ in
            self?.tick()
        }
    }

    func stopTicking() {
        tickTimer?.invalidate()
        tickTimer = nil
    }

    /// 拆解：停拍、停两轨。**不 release 引擎**（由持有方决定）。
    func teardown() {
        guard !tornDown else { return }
        tornDown = true
        stopTicking()
        left.pause()
        right.pause()
        left.setBlackout(false)
        right.setBlackout(false)
    }

    // MARK: - 指令应用

    private func apply(_ commands: CompareDecideCommands) {
        if commands.pauseLeft { left.pause() }
        if commands.pauseRight { right.pause() }
        if commands.resumeLeft { left.play() }
        if commands.resumeRight { right.play() }
        if let seek = commands.seekLeft { left.seek(to: seek) }
        if let seek = commands.seekRight { right.seek(to: seek) }
        if let drift = commands.nudgeRightDrift {
            right.nudgeSpeed(forDriftSeconds: drift)
        }
        applyBlackout(left: commands.blackLeft, right: commands.blackRight)
    }

    private func applyBlackout(left nextLeft: Bool, right nextRight: Bool) {
        let changed = (nextLeft != leftBlackout) || (nextRight != rightBlackout)
        leftBlackout = nextLeft
        rightBlackout = nextRight
        left.setBlackout(nextLeft)
        right.setBlackout(nextRight)
        if changed {
            onBlackoutChanged?(nextLeft, nextRight)
        }
    }
}

private extension CompareSlotDriving {
    /// 把音频计划落到单槽：选中轨下发，音量恒 1（单槽不做「压另一侧」）。
    func applyAudio(plan: CompareAudioPlan) {
        if let track = plan.track {
            selectAudioTrack(track)
        } else {
            selectAudioTrack(.environment)
        }
        setVolume(plan.volume)
    }
}

// MARK: - D1 桥接契约（对比历史）

/// 对比历史的 D1 桥（E2 只通过本桥读写，不直接摸 `MediaLibrary`）。
///
/// 契约：
/// - 读：`MediaLibrary.compareHistory`，按 `MediaCompareEntry.key` 去重（`media:<id>` / `uri:<uri>`）；
/// - 记：`MediaLibrary.recordCompare(rightId:rightUri:leftId:)`，右侧外来来源用 `rightUri`；
/// - 删：`MediaLibrary.removeCompare(key:)`；
/// - 上限 12、队首最新、同一右侧视频只留一条——全部由 WotaCore `MediaCompareHistory` 与
///   D1 索引层保证，本桥不做二次策略。
final class CompareHistoryBridge {

    private let library: MediaLibrary

    init(library: MediaLibrary = MediaLibrary.shared) {
        self.library = library
    }

    /// 读出全部历史（主线程回调）。
    func load(completion: @escaping ([MediaCompareEntry]) -> Void) {
        library.compareHistory(completion: completion)
    }

    /// 记一次对照：右侧库内条目用 `rightId`，外来来源用 `rightUri`（两者至少一个有效）。
    func record(leftId: Int64, rightId: Int64, rightUri: String?, completion: (() -> Void)? = nil) {
        library.recordCompare(rightId: rightId, rightUri: rightUri, leftId: leftId, completion: completion)
    }

    /// 删一条（按 `MediaCompareEntry.key`）。
    func remove(key: String, completion: (() -> Void)? = nil) {
        library.removeCompare(key: key, completion: completion)
    }
}

/// 对比页槽位编号 + 镜像 / 黑层的纯状态容器（E2 可直接当 UI 模型用）。
///
/// 刻意**不含** frame / 宽度 / 分割比例 / 取向：这些是布局，归 E2 决定。
struct CompareOverlayState {
    var leftMirrored: Bool = false
    var rightMirrored: Bool = false
    var leftBlackout: Bool = false
    var rightBlackout: Bool = false

    static var initial: CompareOverlayState {
        CompareOverlayState()
    }
}
