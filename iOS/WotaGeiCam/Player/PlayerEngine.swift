import Foundation
import AVFoundation
#if canImport(WotaCore)
import WotaCore
#endif

/// `AVPlayer` 封装（对齐 Android `PlayerEngine`）。
///
/// 口径要点（逐条有源码落点，见 `docs/modules/E1-player.md`）：
/// - **状态**：`idle / loading / ready / playing / paused / ended / failed`，一律主线程回调；
/// - **两种「暂停」严格区分**：`softPause(true)` 只把 `rate` 归 0，`seek` / 逐帧 / 恢复全可用；
///   `release()` 不可逆，之后所有方法空操作（对齐 Android `softPause` / `releasePlayer` 的 NPE 事故口径）；
/// - **进度只有一个数据源**：`addPeriodicTimeObserver`（0.12s，与 Android 120ms 轮询同口径）
///   同时产出 position / duration / AB 回绕；观察者句柄在 `release` 与换片时移除，不残留；
/// - **AB 循环**：A/B 均为 `CMTime`，范围合法才启用；到 B 回 seek A；循环开关切换时清掉
///   抑制态观察者与挂起步进，不破坏原声（`rate` 只影响播放速率，不改音高）；
/// - **逐帧**：优先 `AVPlayerItem.step(byCount:)`（ObjC `stepByCount:`，iOS 6+），要求暂停且时间范围合法；
///   帧率未知时退回兜底步长的帧栅格 seek，并返回 `isExact == false` + `.frameRateUnknown`，
///   **不静默把近似 seek 说成精确**；
///   步长来自 asset 的 `nominalFrameRate` / `minFrameDuration`，读不到走显式降级步长；
/// - **倍速**：`AVPlayer.rate` + `volume`；pitch 由 `AVPlayer` 保证不随 rate 变化（iOS 无音高参数）。
///
/// 线程模型：所有**主线程**状态变更与 UI 回调；`AVAsset` 的 `loadValuesAsynchronously`
/// 在系统队列执行，完成后回主线程。本类不做 `async/await`（iOS 10 基线）。
final class PlayerEngine: NSObject {

    // MARK: - 回调（全部主线程）

    /// 状态变化（含失败分类）。
    var onStateChanged: ((PlayerPlaybackState) -> Void)?
    /// 位置 / 时长变化（秒）。
    var onProgress: ((Double, Double) -> Void)?
    /// seek 完成（是否成功）。
    var onSeekCompleted: ((Bool) -> Void)?
    /// 逐帧步进结果（含降级原因）。
    var onFrameStep: ((PlayerFrameStepOutcome) -> Void)?
    /// AB 段变化（含持久化后的值）。
    var onABChanged: ((PlayerABRange) -> Void)?

    /// AB 回绕扩展点（对比页用它让两台播放器同时回到 A）：返回 true 表示外部已处理。
    var abWrapHook: ((CMTime, CMTime) -> Bool)?

    // MARK: - 状态

    private var player: AVPlayer?
    private var playerItem: AVPlayerItem?
    private var timeObserver: Any?
    /// classic KVO（iOS 2+）：`NSKeyValueObservation` 需 iOS 11，不能用在 iOS 10 基线。
    private var isObservingItemStatus = false
    private var itemFailedObserver: NSObjectProtocol?
    private var timeJumpObserver: NSObjectProtocol?
    private var endObserver: NSObjectProtocol?

    /// 已发出但尚未落地的步进目标（秒）；对齐 Android `pendingStepMs`。
    private var pendingStepSeconds: Double?
    private var pendingStepTicks: Int = 0
    /// 播放意图代际：暂停/新 seek/换片都会递增，异步 seek 完成回调据此判断意图是否仍有效。
    private var resumeToken: Int = 0

    private var state: PlayerPlaybackState = .idle {
        didSet {
            guard state != oldValue else { return }
            onStateChanged?(state)
        }
    }

    /// 当前片的时长（秒）；未知为 0。
    private(set) var durationSeconds: Double = 0
    /// 当前片的位置（秒）。
    private(set) var positionSeconds: Double = 0
    /// 当前片帧率来源。
    private(set) var frameRateSource: PlayerFrameRateSource = .unknown
    /// 当前片的媒体标识键（AB 持久化用）。
    private(set) var mediaKey: String?
    /// 当前片的来源 URL（KVO 失败映射用）。
    private var currentItemURL: URL?

    private var loopMode: PlayerLoopMode = .off
    private var abRange: PlayerABRange = .empty
    private var speed: Float = PlayerSpeedTiers.default
    private var volume: Float = 1

    /// 是否已释放（不可逆）。
    var isReleased: Bool = false

    /// 只读探针（诊断用）。
    var hasPlayer: Bool { return !isReleased && player != nil }

    /// 只读状态探针（UI 层同步判定用，不触发回调）。
    var statusSnapshot: PlayerPlaybackState { return state }

    /// 只读 AB 段探针（UI 层同步渲染用，不触发回调）。
    var abRangeSnapshot: PlayerABRange { return abRange }

    /// 供画面宿主绑定的 `AVPlayer`（只读；未 attach / 已释放为 nil）。
    var playerForDisplay: AVPlayer? { return isReleased ? nil : player }

    /// 当前视频轨（方向判定用；未就绪为 nil）。
    var videoTrackForDisplay: AVAssetTrack? {
        guard !isReleased else { return nil }
        return playerItem?.asset.tracks(withMediaType: .video).first
    }

    private let defaults: UserDefaults
    private let preferredTimescale: CMTimeScale = 600

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        super.init()
    }

    deinit {
        // 释放路径由 `release()` 显式负责；这里只兜底移除通知（不访问已释放的 player）。
        removeItemObservers()
        if let timeObserver = timeObserver {
            player?.removeTimeObserver(timeObserver)
        }
    }

    // MARK: - 装载

    /// 装载本地媒体（App 容器 URL 或外来来源绝对路径）。
    ///
    /// 幂等：同一 `mediaKey` 重选时，上一轮**确实失败过**才真正重试；
    /// 没失败过则保持「不重建 player」语义（对齐 Android `attach`）。
    func attach(url: URL) {
        guard !isReleased else { return }
        resumeToken += 1
        let key = PlayerABStore.mediaKey(for: url)
        if mediaKey == key, player != nil {
            if state.isFailure {
                clearFailure()
                restartSameMedia(url: url, key: key)
                return
            }
            ensureTimeObserver()
            return
        }
        let resolved = resolvePlayer()
        removeItemObservers()
        mediaKey = key
        state = .loading
        positionSeconds = 0
        pendingStepSeconds = nil
        frameRateSource = .unknown
        durationSeconds = 0

        let asset = AVURLAsset(url: url, options: nil)
        let item = AVPlayerItem(asset: asset)
        playerItem = item
        // 用 `replaceCurrentItem(with:)` 显式换片（对齐 `restartSameMedia` 与 `release` 口径），
        // 避免 `player = item` setter 重置播放态 / 与既有 `rate`/`actionAtItemEnd` 交叠。
        resolved.replaceCurrentItem(with: item)
        resolved.rate = 0
        installItemObservers(item: item, url: url)
        abRange = PlayerABStore.load(defaults: defaults, mediaKey: key)
        loopMode = .off
        resolved.isMuted = false
        if abs(volume - 1) > 0.0001 { resolved.volume = volume }
        ensureTimeObserver()
        loadAssetMetadata(asset: asset)
        onABChanged?(abRange)
    }

    private func restartSameMedia(url: URL, key: String) {
        // 旧 item 的 KVO / 通知必须先摘干净，否则会残留对已换下 item 的观察。
        resumeToken += 1
        removeItemObservers()
        let asset = AVURLAsset(url: url, options: nil)
        let item = AVPlayerItem(asset: asset)
        playerItem = item
        player?.replaceCurrentItem(with: item)
        installItemObservers(item: item, url: url)
        state = .loading
        positionSeconds = 0
        pendingStepSeconds = nil
        loadAssetMetadata(asset: asset)
    }

    private func resolvePlayer() -> AVPlayer {
        if let player = player { return player }
        let created = AVPlayer()
        created.automaticallyWaitsToMinimizeStalling = false
        player = created
        return created
    }

    /// 读 `nominalFrameRate` / `minFrameDuration` 与时长。
    ///
    /// **不硬编码 fps**：`nominalFrameRate` 优先；为 0/非有限时退回 `minFrameDuration`；
    /// 两者都不可用则 `.unknown`，调用方必须走降级步长并提示。
    private func loadAssetMetadata(asset: AVURLAsset) {
        asset.loadValuesAsynchronously(forKeys: ["duration", "tracks"]) { [weak self] in
            guard let self = self, !self.isReleased else { return }
            DispatchQueue.main.async {
                guard let self = self, !self.isReleased else { return }
                var duration = 0.0
                let durationStatus = asset.statusOfValue(forKey: "duration", error: nil)
                if durationStatus == .loaded {
                    let seconds = asset.duration.seconds
                    if seconds.isFinite, seconds > 0 { duration = seconds }
                }
                self.durationSeconds = duration
                self.refreshFrameRate(asset: asset)
                self.onProgress?(self.positionSeconds, duration)
            }
        }
    }

    private func refreshFrameRate(asset: AVURLAsset) {
        let tracks = asset.tracks(withMediaType: .video)
        guard let track = tracks.first else {
            frameRateSource = .unknown
            return
        }
        let rate = track.nominalFrameRate
        if rate > 0, rate.isFinite {
            frameRateSource = .known(rate)
            return
        }
        // `minFrameDuration` 需要 iOS 8+；本工程最低 iOS 10，可直接用。
        let minDuration = track.minFrameDuration
        if minDuration.isValid, minDuration.seconds > 0, minDuration.seconds.isFinite {
            frameRateSource = .known(Float(1.0 / minDuration.seconds))
            return
        }
        frameRateSource = .unknown
    }

    // MARK: - 播放控制

    /// 非破坏性暂停：唯一允许的「暂停」语义（对齐 Android `softPause`）。
    /// 恢复播放时若已到结尾，先回 0 再播（否则 `rate` 置 1 不会重放）。
    func softPause(_ paused: Bool) {
        guard !isReleased, let player = player else { return }
        if !paused {
            pendingStepSeconds = nil
            // 每次起播都是一次新意图；在途的 seek 完成回调不得再覆盖之后的暂停。
            resumeToken += 1
            let token = resumeToken
            if state == .ended {
                seekInternal(to: .zero, commit: true, invalidateResume: false) { [weak self] finished in
                    guard let self = self, finished, !self.isReleased,
                          self.resumeToken == token else { return }
                    self.player?.rate = self.speed
                    self.state = .playing
                }
                return
            }
            player.rate = speed
            state = .playing
            return
        }
        resumeToken += 1
        player.rate = 0
        state = .paused
    }

    func setPlaying(_ playing: Bool) {
        softPause(!playing)
    }

    /// 定位（秒）。`commit` 走完成回调，失败必须回给调用方。
    func seek(to seconds: Double, commit: Bool = false, completion: ((Bool) -> Void)? = nil) {
        seekInternal(to: seconds, commit: commit, invalidateResume: true, completion: completion)
    }

    /// `invalidateResume == false` 只给 `softPause(false)`/循环回绕的内部零 seek 用：
    /// 它们自己已持有代际，不能再递增，否则完成回调会把自己作废。
    private func seekInternal(
        to seconds: Double,
        commit: Bool,
        invalidateResume: Bool,
        completion: ((Bool) -> Void)?
    ) {
        guard !isReleased, let player = player else {
            completion?(false)
            return
        }
        if invalidateResume { resumeToken += 1 }
        let upper = durationSeconds > 0 ? durationSeconds : seconds
        let clamped = max(0, min(seconds, upper))
        let target = CMTime(seconds: clamped, preferredTimescale: preferredTimescale)
        pendingStepSeconds = nil
        if commit {
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
                guard let self = self, !self.isReleased else { return }
                DispatchQueue.main.async {
                    if finished {
                        self.positionSeconds = clamped
                        self.onProgress?(self.positionSeconds, self.durationSeconds)
                        // 从末帧拖回中间后，状态必须离开 `.ended`，否则下一次播放会跳回片头。
                        if self.state == .ended, clamped < self.durationSeconds - 0.001 {
                            self.state = .paused
                        }
                    }
                    self.onSeekCompleted?(finished)
                    completion?(finished)
                }
            }
        } else {
            player.seek(to: target)
            positionSeconds = clamped
            if state == .ended, clamped < durationSeconds - 0.001 {
                state = .paused
            }
            self.onProgress?(positionSeconds, durationSeconds)
            completion?(true)
        }
    }

    /// 当前可读位置（秒）。
    func livePositionSeconds() -> Double {
        guard !isReleased, let player = player else { return positionSeconds }
        let seconds = player.currentTime().seconds
        if seconds.isFinite { return max(0, seconds) }
        return positionSeconds
    }

    /// 倍速（档位归一后写入）。
    func setSpeed(_ value: Float) {
        let tier = PlayerSpeedTiers.normalize(value)
        speed = tier
        guard !isReleased, let player = player else { return }
        if state == .playing {
            player.rate = tier
        }
    }

    /// 音量（0...1）。
    func setVolume(_ value: Float) {
        volume = max(0, min(1, value))
        guard !isReleased, let player = player else { return }
        player.volume = volume
    }

    /// 循环模式（AB 段循环激活时不占用本开关，避免两套回绕打架）。
    func setLoopMode(_ mode: PlayerLoopMode) {
        loopMode = mode
        guard !isReleased, let player = player else { return }
        player.actionAtItemEnd = (mode == .one && !abRange.isLoopActive) ? .none : .pause
    }

    // MARK: - 逐帧步进

    /// 逐帧前进 / 后退。
    ///
    /// 只接受**暂停态**（`AVPlayerItem.step(byCount:)` 在播放中行为未定义）；
    /// 时间范围必须合法（A/B 与时长）。失败返回 `isExact == false` 与原因。
    @discardableResult
    func stepFrame(forward: Bool) -> PlayerFrameStepOutcome {
        guard !isReleased, let player = player, let item = playerItem else {
            return PlayerFrameStepOutcome(
                target: CMTime(seconds: positionSeconds, preferredTimescale: preferredTimescale),
                frameIndex: 0,
                isExact: false,
                failure: .invalidTimebase
            )
        }
        // 与 Android 同口径：播放中按逐帧先隐式暂停，不要求用户先手动暂停。
        if state == .playing { softPause(true) }
        guard state != .playing, item.status == .readyToPlay else {
            return rejectStep(.notPaused, target: positionSeconds)
        }
        let duration = durationSeconds
        guard duration > 0 else {
            return rejectStep(.invalidTimebase, target: positionSeconds)
        }
        let frame = PlayerFrameRateMath.frameSeconds(frameRateSource)
        let pendingTarget = pendingStepSeconds.map {
            CMTime(seconds: $0, preferredTimescale: preferredTimescale)
        }
        let current = CMTime(seconds: positionSeconds, preferredTimescale: preferredTimescale)
        let computed = PlayerFrameStepMath.nextTarget(
            pendingTarget: pendingTarget,
            position: current,
            frameSeconds: frame.seconds,
            durationSeconds: duration,
            forward: forward
        )
        let targetSeconds = computed.time.seconds
        // 边界：前进到末帧之后 / 后退到第 0 帧之前。
        if forward, targetSeconds >= duration - frame.seconds / 2, positionSeconds >= duration - frame.seconds {
            return rejectStep(.atBoundary, target: targetSeconds)
        }
        if !forward, targetSeconds <= 0, positionSeconds <= frame.seconds {
            return rejectStep(.atBoundary, target: targetSeconds)
        }
        pendingStepSeconds = targetSeconds
        pendingStepTicks = 0

        // `AVPlayerItem.step(byCount:)` 是 iOS 6+ 的公开 API（ObjC `stepByCount:`）。
        // 逐帧由播放器按解码时间戳推进，**不依赖我们算出的秒数**；落点由周期观察者回读。
        // 只有帧率已知时才敢把结果标成精确；帧率未知必须显式降级，不能把近似当精确。
        if frame.isExact {
            item.step(byCount: forward ? 1 : -1)
            let outcome = PlayerFrameStepOutcome(
                target: computed.time,
                frameIndex: computed.frameIndex,
                isExact: true,
                failure: nil
            )
            onFrameStep?(outcome)
            return outcome
        }
        // 帧率未知：按兜底步长 seek 到帧栅格，并显式报「帧率未知」的降级。
        player.seek(to: computed.time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            guard let self = self, !self.isReleased else { return }
            DispatchQueue.main.async {
                self.positionSeconds = targetSeconds
                self.onProgress?(self.positionSeconds, self.durationSeconds)
            }
        }
        let degraded = PlayerFrameStepOutcome(
            target: computed.time,
            frameIndex: computed.frameIndex,
            isExact: false,
            failure: .frameRateUnknown
        )
        onFrameStep?(degraded)
        return degraded
    }

    private func rejectStep(_ failure: PlayerFrameStepFailure, target: Double) -> PlayerFrameStepOutcome {
        let outcome = PlayerFrameStepOutcome(
            target: CMTime(seconds: target, preferredTimescale: preferredTimescale),
            frameIndex: 0,
            isExact: false,
            failure: failure
        )
        onFrameStep?(outcome)
        return outcome
    }

    // MARK: - AB 段

    /// 打 A：若已有 B 且 `B <= A`，清 B 并关循环。
    @discardableResult
    func markA() -> Bool {
        guard !isReleased else { return false }
        let position = CMTime(seconds: livePositionSeconds(), preferredTimescale: preferredTimescale)
        abRange = PlayerABControl.markedA(abRange, position: position)
        persistAB()
        onABChanged?(abRange)
        return true
    }

    /// 打 B：需已有 A 且 `B > A`，成功即自动开循环。
    @discardableResult
    func markB() -> Bool {
        guard !isReleased else { return false }
        let position = CMTime(seconds: livePositionSeconds(), preferredTimescale: preferredTimescale)
        guard let next = PlayerABControl.markedB(abRange, position: position) else { return false }
        abRange = next
        persistAB()
        onABChanged?(abRange)
        return true
    }

    /// 循环开关：仅范围合法时切换。
    @discardableResult
    func toggleAbLoop() -> Bool {
        guard !isReleased else { return false }
        guard let next = PlayerABControl.toggled(abRange) else { return false }
        abRange = next
        persistAB()
        onABChanged?(abRange)
        return true
    }

    func clearAb() {
        guard !isReleased else { return }
        abRange = .empty
        persistAB()
        onABChanged?(abRange)
    }

    private func persistAB() {
        guard let key = mediaKey else { return }
        PlayerABStore.save(defaults: defaults, mediaKey: key, range: abRange)
    }

    private func clearFailure() {
        if state.isFailure { state = .loading }
    }

    // MARK: - 观察者

    /// classic KVO 回调：只关心当前 `playerItem` 的 `status`。
    override func observeValue(
        forKeyPath keyPath: String?,
        of object: Any?,
        change: [NSKeyValueChangeKey: Any]?,
        context: UnsafeMutableRawPointer?
    ) {
        guard keyPath == "status",
              let item = object as? AVPlayerItem,
              let current = playerItem,
              item === current else {
            // 不调用 super：NSObject 默认实现对未识别 keyPath 会抛
            // NSInternalInconsistencyException；本类只注册了当前 item 的 status。
            return
        }
        guard !isReleased else { return }
        DispatchQueue.main.async { [weak self, weak item] in
            guard let self = self, !self.isReleased, let item = item, self.playerItem === item else { return }
            switch item.status {
            case .readyToPlay:
                if self.state != .playing { self.state = .ready }
                self.refreshDurationAndFrameRate(item: item)
            case .failed:
                let failure = PlayerEngine.mapItemFailure(item, url: self.currentItemURL ?? URL(fileURLWithPath: ""))
                self.state = .failed(failure)
            default:
                break
            }
        }
    }

    private func installItemObservers(item: AVPlayerItem, url: URL) {
        currentItemURL = url
        // classic KVO：`observe(_:options:changeHandler:)`（NSKeyValueObservation）需 iOS 11，
        // 本工程 iOS 10 基线用 `addObserver/observeValue`。
        item.addObserver(self, forKeyPath: "status", options: [.new, .initial], context: nil)
        isObservingItemStatus = true

        let center = NotificationCenter.default
        itemFailedObserver = center.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            guard let self = self, !self.isReleased else { return }
            self.state = .failed(PlayerPlaybackFailure(kind: .avFoundationError, reason: "播放到结尾失败"))
        }
        timeJumpObserver = center.addObserver(
            forName: .AVPlayerItemTimeJumped,
            object: item,
            queue: .main
        ) { [weak self] _ in
            guard let self = self, !self.isReleased else { return }
            // seek 落地：位置已跳变，清掉挂起步进并立刻回读一次。
            self.pendingStepSeconds = nil
            self.positionSeconds = self.livePositionSeconds()
            self.onProgress?(self.positionSeconds, self.durationSeconds)
        }
        endObserver = center.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            guard let self = self, !self.isReleased else { return }
            self.state = .ended
            self.positionSeconds = self.durationSeconds
            self.onProgress?(self.positionSeconds, self.durationSeconds)
            // 单视频循环：AB 未激活时到结尾回 0 继续播（`actionAtItemEnd = .none` 只停住，不会自动重放）。
            if self.loopMode == .one, !self.abRange.isLoopActive {
                self.resumeToken += 1
                let token = self.resumeToken
                self.seekInternal(to: .zero, commit: true, invalidateResume: false) { [weak self] finished in
                    guard let self = self, finished, !self.isReleased,
                          self.resumeToken == token,
                          self.loopMode == .one, !self.abRange.isLoopActive else { return }
                    self.player?.rate = self.speed
                    self.state = .playing
                }
            }
        }
    }

    private func refreshDurationAndFrameRate(item: AVPlayerItem) {
        let seconds = item.duration.seconds
        if seconds.isFinite, seconds > 0 { durationSeconds = seconds }
        if let track = item.asset.tracks(withMediaType: .video).first {
            let rate = track.nominalFrameRate
            if rate > 0, rate.isFinite {
                frameRateSource = .known(rate)
            } else if track.minFrameDuration.isValid, track.minFrameDuration.seconds > 0 {
                frameRateSource = .known(Float(1.0 / track.minFrameDuration.seconds))
            } else {
                frameRateSource = .unknown
            }
        }
        onProgress?(positionSeconds, durationSeconds)
    }

    private func ensureTimeObserver() {
        guard let player = player, timeObserver == nil else { return }
        let interval = CMTime(seconds: 0.12, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: interval,
            queue: .main
        ) { [weak self] time in
            guard let self = self, !self.isReleased else { return }
            let seconds = time.seconds
            guard seconds.isFinite else { return }
            self.resolvePendingStep(seconds)
            self.positionSeconds = max(0, seconds)
            self.onProgress?(self.positionSeconds, self.durationSeconds)
            if PlayerABControl.shouldWrap(position: time, range: self.abRange) {
                let a = PlayerABControl.wrapTarget(self.abRange)
                let handled = self.abWrapHook?(a, time) ?? false
                if !handled {
                    player.seek(to: a, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                        guard let self = self, !self.isReleased else { return }
                        DispatchQueue.main.async {
                            self.positionSeconds = a.seconds
                            self.onProgress?(self.positionSeconds, self.durationSeconds)
                        }
                    }
                }
            }
        }
    }

    /// 步进目标已落地（或迟迟不落地）时收摊，交回常规轮询与 AB 回绕。
    private func resolvePendingStep(_ position: Double) {
        guard let pending = pendingStepSeconds else { return }
        pendingStepTicks += 1
        let frameSeconds = PlayerFrameRateMath.frameSeconds(frameRateSource).seconds
        let halfFrame = max(frameSeconds / 2, 0.001)
        if abs(position - pending) <= halfFrame || pendingStepTicks >= 3 {
            pendingStepSeconds = nil
        }
    }

    private func removeItemObservers() {
        if isObservingItemStatus, let item = playerItem {
            item.removeObserver(self, forKeyPath: "status")
        }
        isObservingItemStatus = false
        let center = NotificationCenter.default
        if let observer = itemFailedObserver { center.removeObserver(observer) }
        if let observer = timeJumpObserver { center.removeObserver(observer) }
        if let observer = endObserver { center.removeObserver(observer) }
        itemFailedObserver = nil
        timeJumpObserver = nil
        endObserver = nil
        playerItem?.cancelPendingSeeks()
        playerItem = nil
        currentItemURL = nil
    }

    // MARK: - 释放

    /// 不可逆销毁：移除全部观察者与通知，释放 `AVPlayer`，此后所有方法空操作。
    func release() {
        guard !isReleased else { return }
        isReleased = true
        resumeToken += 1
        if let timeObserver = timeObserver {
            player?.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        removeItemObservers()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        state = .idle
        positionSeconds = 0
        durationSeconds = 0
        mediaKey = nil
        abRange = .empty
        pendingStepSeconds = nil
    }

    // MARK: - 失败映射

    private static func mapItemFailure(_ item: AVPlayerItem, url: URL) -> PlayerPlaybackFailure {
        if let error = item.error {
            let nsError = error as NSError
            return PlayerPlaybackFailure(
                kind: .avFoundationError,
                reason: "domain=\(nsError.domain) code=\(nsError.code)"
            )
        }
        return PlayerPlaybackFailure(kind: .unreadable, reason: url.lastPathComponent)
    }
}
