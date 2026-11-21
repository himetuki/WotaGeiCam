import UIKit
import AVFoundation
import CoreMedia
#if canImport(WotaCore)
import WotaCore
#endif

/// 双视频对比页（E2）。
///
/// **布局是临时竖排（上=左片、下=右片），不是产品定版。** Android 是 App 内左右栏
/// （`SPLIT_SQUEEZE=0.5`），iPhone 无系统级 Split View，最终取向待用户裁决
/// （`docs/mappings/pages.md` P-017、`docs/mappings/platform-differences.md` PD-006）。
///
/// 入口契约（唯一，主代理据此接入媒体库双选）：
/// ```swift
/// let compare = CompareViewController(left: leftItem, right: rightItem)
/// present(compare, animated: true, completion: nil)
/// ```
/// 两个 `MediaItem` 可以是库内条目，也可以是外来来源（`mediaId == 0`）；播放路径优先取
/// `absolutePath`，为空时按 `relativeDirectory/fileName` 在 Documents 容器下解析。
///
/// 本页已实现：
/// - 两个独立 `PlayerEngine` 同步启停、统一时间线拖动（两轨联动 seek）；
/// - 右片相对左片的手动偏移（±0.1s 步进）；
/// - 单侧发声（另一侧音量压 0，不做混音）；
/// - 对齐偏差 / 素材域 / 两侧位置反馈；
/// - 画面按 `preferredTransform` 呈现（`PlayerSurfaceView` / `AVPlayerLayer`，不叠加旋转）；
/// - 前后台与关闭页面的暂停/解绑生命周期。
///
/// 明确**未实现**（不冒充）：自动音频对齐（Android `AudioSync` 的等价物）、环境/内录音轨
/// 切换、右轨软追赶（`nudgeSpeed` 仍为协议 no-op，仅 250ms 硬校正）、AB 打点/逐帧/倍速/
/// 镜像（属单播放页），以及任何「对比内部录屏」——iOS 没有跨 App/系统录屏的等价物，本页
/// 不把 AVAssetWriter 等自绘录制冒充系统录屏。
///
/// 不使用 SwiftUI / Combine / @MainActor / async-await，保持 iOS 10 源码基线。
final class CompareViewController: UIViewController {

    // MARK: - 入口契约

    /// 两个媒体库条目；右片允许外来来源（`mediaId == 0`）。
    init(left: MediaItem, right: MediaItem, library: MediaLibrary = MediaLibrary.shared) {
        let leftSeconds = left.durationMs > 0 ? Double(left.durationMs) / 1000.0 : 0
        let rightSeconds = right.durationMs > 0 ? Double(right.durationMs) / 1000.0 : 0
        self.leftItem = left
        self.rightItem = right
        self.historyBridge = CompareHistoryBridge(library: library)
        let leftSlot = PlayerEngineCompareSlot()
        let rightSlot = PlayerEngineCompareSlot()
        self.leftSlot = leftSlot
        self.rightSlot = rightSlot
        self.lastLeftDuration = leftSeconds
        self.lastRightDuration = rightSeconds
        self.coordinator = ComparePlayerCoordinator(
            left: leftSlot,
            right: rightSlot,
            leftDuration: CMTime(seconds: leftSeconds, preferredTimescale: 600),
            rightDuration: CMTime(seconds: rightSeconds, preferredTimescale: 600),
            offset: .zero
        )
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("CompareViewController 仅支持代码初始化（两个 MediaItem）")
    }

    // MARK: - 依赖

    private let leftItem: MediaItem
    private let rightItem: MediaItem
    private let historyBridge: CompareHistoryBridge

    private let leftSlot: PlayerEngineCompareSlot
    private let rightSlot: PlayerEngineCompareSlot
    private let coordinator: ComparePlayerCoordinator

    // MARK: - 运行态

    private var rightURL: URL?
    private var lastLeftDuration: Double
    private var lastRightDuration: Double

    private var hasAppeared = false
    private var didAutoplay = false
    private var didRecordHistory = false
    private var userStopped = false
    private var wasPlayingBeforeScrub = false
    private var leftFailed = false
    private var rightFailed = false

    private var bannerTimer: Timer?

    // MARK: - 视图

    private let topBarView = UIView()
    private let backButton = UIButton(type: .system)
    private let titleLabel = UILabel()

    private let paneContainer = UIView()
    private let leftPane = UIView()
    private let rightPane = UIView()
    private let paneDivider = UIView()
    private let leftSurface = PlayerSurfaceView(frame: .zero)
    private let rightSurface = PlayerSurfaceView(frame: .zero)
    private let leftBlackoutView = UIView()
    private let rightBlackoutView = UIView()
    private let leftTitleLabel = UILabel()
    private let rightTitleLabel = UILabel()
    private let leftAudioLabel = UILabel()
    private let rightAudioLabel = UILabel()

    private let dockView = UIView()
    private let seekSlider = UISlider()
    private let timeLabel = UILabel()
    private let playPauseButton = UIButton(type: .system)
    private let offsetValueLabel = UILabel()
    private let offsetStepper = UIStepper()
    private let offsetResetButton = UIButton(type: .system)
    private let audioControl = UISegmentedControl(items: ["左", "右"])
    private let statusLabel = UILabel()
    private let footnoteLabel = UILabel()
    private let bannerLabel = UILabel()
    // Swift 下旧名 `init(activityIndicatorStyle:)` 是改名错误而非弃用警告，必须用 `init(style:)`。
    private let loadingIndicator = UIActivityIndicatorView(style: .white)

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        buildHierarchy()
        configureCoordinatorCallbacks()
        configureEngineCallbacks()
        registerLifecycleNotifications()
        loadMedia()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        hasAppeared = true
        coordinator.startTicking()
        maybeAutoplay()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // 离开页面（含被全屏覆盖）就暂停：两轨都不许在后台继续走钟/出声。
        coordinator.pause()
        coordinator.stopTicking()
    }

    deinit {
        bannerTimer?.invalidate()
        NotificationCenter.default.removeObserver(self)
        coordinator.teardown()
        leftSlot.engine.release()
        rightSlot.engine.release()
    }

    // MARK: - 装载

    private func loadMedia() {
        guard let left = CompareViewController.resolveURL(for: leftItem),
              let right = CompareViewController.resolveURL(for: rightItem) else {
            showUnusableState("媒体路径缺失，无法进入对比")
            return
        }
        rightURL = right
        leftTitleLabel.text = "左｜" + leftItem.fileName
        rightTitleLabel.text = "右｜" + rightItem.fileName

        coordinator.prepare(leftURL: left, rightURL: right)
        leftSurface.player = leftSlot.engine.playerForDisplay
        rightSurface.player = rightSlot.engine.playerForDisplay
        applyAudioSide(.left)
        recordHistoryIfNeeded()
        updateLoadingState()
    }

    /// `absolutePath` 优先；外来/库内条目没有绝对路径时按相对目录在 Documents 下解析。
    private static func resolveURL(for item: MediaItem) -> URL? {
        if let path = item.absolutePath, !path.isEmpty {
            return URL(fileURLWithPath: path)
        }
        guard !item.relativeDirectory.isEmpty, !item.fileName.isEmpty else { return nil }
        return MediaLibrary.documentsContainerURL()
            .appendingPathComponent(item.relativeDirectory, isDirectory: true)
            .appendingPathComponent(item.fileName)
    }

    /// 对比历史只复用 D1 的 `MediaLibrary`（经 `CompareHistoryBridge`），不另建存储。
    ///
    /// 左侧必须是库内条目（`mediaId > 0`）才有历史意义；右侧库内用 `rightId`，
    /// 外来来源用 `rightUri`。不满足条件就**不记**，也不在 UI 上声称已记录。
    private func recordHistoryIfNeeded() {
        guard !didRecordHistory, leftItem.mediaId > 0 else { return }
        let rightUri: String?
        if rightItem.mediaId > 0 {
            rightUri = nil
        } else if let url = rightURL {
            rightUri = url.absoluteString
        } else {
            return
        }
        didRecordHistory = true
        historyBridge.record(leftId: leftItem.mediaId, rightId: rightItem.mediaId, rightUri: rightUri)
    }

    // MARK: - 回调

    private func configureCoordinatorCallbacks() {
        coordinator.onSlotsChanged = { [weak self] slots, domain in
            self?.applySlots(slots, domain: domain)
        }
        coordinator.onBlackoutChanged = { [weak self] left, right in
            guard let self = self else { return }
            self.leftBlackoutView.isHidden = !left
            self.rightBlackoutView.isHidden = !right
        }
    }

    private func configureEngineCallbacks() {
        leftSlot.engine.onStateChanged = { [weak self] state in
            self?.handleEngineState(state, side: .left)
        }
        rightSlot.engine.onStateChanged = { [weak self] state in
            self?.handleEngineState(state, side: .right)
        }
        leftSlot.engine.onSeekCompleted = { [weak self] success in
            if !success { self?.showBanner("左片定位失败") }
        }
        rightSlot.engine.onSeekCompleted = { [weak self] success in
            if !success { self?.showBanner("右片定位失败") }
        }
    }

    private func handleEngineState(_ state: PlayerPlaybackState, side: CompareSide) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let engine = side == .left ? self.leftSlot.engine : self.rightSlot.engine
            let surface = side == .left ? self.leftSurface : self.rightSurface
            if state.isReady {
                // `AVPlayerLayer` 已按视频轨 `preferredTransform` 呈现画面；这里只读角度做诊断，
                // 不把同一角度再写进图层 transform（会双重旋转）。
                surface.updateRotation(from: engine.videoTrackForDisplay)
                surface.player = engine.playerForDisplay
            }
            if state.isFailure {
                if side == .left { self.leftFailed = true } else { self.rightFailed = true }
                var reason = ""
                if case .failed(let failure) = state { reason = failure.reason }
                self.showBanner((side == .left ? "左片" : "右片") + "播放失败：" + reason)
                self.statusLabel.text = (side == .left ? "左片" : "右片") + "无法播放"
            }
            self.updateLoadingState()
            self.updatePlayButton()
            self.maybeAutoplay()
        }
    }

    // MARK: - 每拍 UI

    private func applySlots(_ slots: ComparePlaybackSlots, domain: CompareDomain) {
        var durationsChanged = false
        let leftSeconds = slots.left.duration.seconds
        if leftSeconds > 0, abs(leftSeconds - lastLeftDuration) > 0.05 {
            lastLeftDuration = leftSeconds
            durationsChanged = true
        }
        let rightSeconds = slots.right.duration.seconds
        if rightSeconds > 0, abs(rightSeconds - lastRightDuration) > 0.05 {
            lastRightDuration = rightSeconds
            durationsChanged = true
        }
        if durationsChanged {
            coordinator.updateDurations(
                leftDuration: CMTime(seconds: lastLeftDuration, preferredTimescale: 600),
                rightDuration: CMTime(seconds: lastRightDuration, preferredTimescale: 600)
            )
        }

        updateProgressUI()
        updateStatus(domain: domain, slots: slots)
        updatePlayButton()
        updateLoadingState()
        maybeAutoplay()
    }

    private func updateProgressUI() {
        let geometry = coordinator.geometry
        let span = geometry.tMax.seconds - geometry.tMin.seconds
        let now = coordinator.timelineNowSeconds
        if span > 0.001, !seekSlider.isTracking {
            let fraction = (now - geometry.tMin.seconds) / span
            seekSlider.value = Float(max(0, min(1, fraction)))
        }
        timeLabel.text = formatSignedClock(now) + " / " + formatClock(coordinator.timelineSpanSeconds)
    }

    private func updateStatus(domain: CompareDomain, slots: ComparePlaybackSlots) {
        let domainText: String
        switch domain {
        case .dual:
            domainText = "双片同播"
        case .leftSolo:
            domainText = "仅左片"
        case .rightSolo:
            domainText = "仅右片"
        case .timelineEnd:
            domainText = "时间线末"
        }
        var parts: [String] = [domainText]
        if domain == .dual {
            // 对齐反馈：右片相对「左片 + 偏移」的实际偏差（正 = 右片超前）。
            let expectedRight = slots.left.position.seconds + coordinator.geometry.offset.seconds
            let driftMs = (slots.right.position.seconds - expectedRight) * 1000
            parts.append(String(format: "偏差 %+.0f ms", driftMs))
        }
        parts.append(String(format: "L %.1fs", max(0, slots.left.position.seconds)))
        parts.append(String(format: "R %.1fs", max(0, slots.right.position.seconds)))
        if !(leftSlot.isPlaying || rightSlot.isPlaying) {
            parts.append("已暂停")
        }
        statusLabel.text = parts.joined(separator: " · ")
    }

    private func updatePlayButton() {
        let playing = leftSlot.isPlaying || rightSlot.isPlaying
        playPauseButton.setTitle(playing ? "❚❚" : "▶", for: .normal)
    }

    private func updateLoadingState() {
        if leftFailed || rightFailed {
            loadingIndicator.stopAnimating()
            return
        }
        if leftSlot.isReady && rightSlot.isReady {
            loadingIndicator.stopAnimating()
        } else {
            loadingIndicator.startAnimating()
        }
    }

    /// 进页自动双播（Android `attach` 后 `softPause(false)` 同口径）。
    ///
    /// 必须等两片都 ready 且时长可读：几何为 0 时 `play()` 没有可覆盖的域，提前置
    /// `didAutoplay` 会让页面停在「双静止」。用户显式按过暂停则不自动起播。
    private func maybeAutoplay() {
        guard !didAutoplay, hasAppeared, !userStopped, view.window != nil else { return }
        guard leftSlot.isReady, rightSlot.isReady else { return }
        guard leftSlot.durationSeconds > 0, rightSlot.durationSeconds > 0 else { return }
        // 时长以引擎读数为准（`MediaItem.durationMs` 可能为 0）；先落几何再起播，
        // 否则 play() 面对 0 跨度没有可覆盖的素材域。
        lastLeftDuration = leftSlot.durationSeconds
        lastRightDuration = rightSlot.durationSeconds
        coordinator.updateDurations(
            leftDuration: CMTime(seconds: lastLeftDuration, preferredTimescale: 600),
            rightDuration: CMTime(seconds: lastRightDuration, preferredTimescale: 600)
        )
        didAutoplay = true
        resumePlayback()
    }

    /// 从当前位置恢复播放。
    ///
    /// `PlayerEngine.softPause(false)` 在 `.ended` 态会先回自己的 0——从末帧拖回中间再点播放
    /// 就会跳回片头。先把 ended 归一为 paused（非破坏性，位置保留），再让编排按统一时间线起播；
    /// 时间线末仍交给 `coordinator.play()` 的统一回绕处理。
    private func resumePlayback() {
        if leftSlot.engine.statusSnapshot == .ended {
            leftSlot.pause()
        }
        if rightSlot.engine.statusSnapshot == .ended {
            rightSlot.pause()
        }
        coordinator.play()
        updatePlayButton()
    }

    // MARK: - 动作

    @objc private func backTapped() {
        dismiss(animated: true, completion: nil)
    }

    @objc private func playPauseTapped() {
        if leftSlot.isPlaying || rightSlot.isPlaying {
            userStopped = true
            coordinator.pause()
        } else {
            userStopped = false
            resumePlayback()
        }
        updatePlayButton()
    }

    @objc private func seekTouchDown() {
        wasPlayingBeforeScrub = leftSlot.isPlaying || rightSlot.isPlaying
        coordinator.isDragging = true
    }

    @objc private func seekValueChanged() {
        let geometry = coordinator.geometry
        let span = geometry.tMax.seconds - geometry.tMin.seconds
        guard span > 0.001 else { return }
        let target = geometry.tMin.seconds + Double(seekSlider.value) * span
        coordinator.seekTimeline(to: target)
    }

    @objc private func seekTouchEnded() {
        seekValueChanged()
        coordinator.isDragging = false
        // 拖动中两轨可能被 park 暂停；原本在播的，松手后按新素材域恢复覆盖到的一侧。
        if wasPlayingBeforeScrub,
           coordinator.timelineNowSeconds < coordinator.geometry.tMax.seconds - 0.05 {
            resumePlayback()
        }
        wasPlayingBeforeScrub = false
        updatePlayButton()
    }

    @objc private func offsetStepperChanged() {
        let rounded = (offsetStepper.value * 10).rounded() / 10
        offsetStepper.value = rounded
        applyOffset(rounded)
    }

    @objc private func offsetResetTapped() {
        offsetStepper.value = 0
        applyOffset(0)
    }

    /// 改变右片偏移后按当前时间线重锚：两轨经统一 seek 落到新几何的映射位。
    private func applyOffset(_ seconds: Double) {
        let wasPlaying = leftSlot.isPlaying || rightSlot.isPlaying
        let anchor = coordinator.timelineNowSeconds
        offsetValueLabel.text = String(format: "右片 %+.1f s", seconds)
        coordinator.setOffsetSeconds(seconds)
        coordinator.seekTimeline(to: anchor)
        if wasPlaying, anchor < coordinator.geometry.tMax.seconds - 0.05 {
            resumePlayback()
        }
        updatePlayButton()
    }

    @objc private func audioSideChanged() {
        applyAudioSide(audioControl.selectedSegmentIndex == 1 ? .right : .left)
    }

    /// 单侧发声：发声侧音量 1，另一侧 0（不做混音，避免双声梳状滤波）。
    /// 内录/环境音轨选择在 iOS 侧仍是 no-op，UI 不提供该开关。
    private func applyAudioSide(_ side: CompareSide) {
        audioControl.selectedSegmentIndex = side == .left ? 0 : 1
        coordinator.applyAudio(activeSide: side, track: .environment, dualAudio: false)
        leftAudioLabel.text = side == .left ? "发声" : "静音"
        rightAudioLabel.text = side == .right ? "发声" : "静音"
    }

    @objc private func appDidEnterBackground() {
        coordinator.pause()
        coordinator.stopTicking()
    }

    @objc private func appWillEnterForeground() {
        // 回前台只恢复 UI 节拍，不自动续播（与 Android ON_STOP 软暂停口径一致）。
        if viewIfLoaded?.window != nil {
            coordinator.startTicking()
        }
    }

    private func registerLifecycleNotifications() {
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
    }

    // MARK: - 提示

    private func showUnusableState(_ message: String) {
        statusLabel.text = message
        playPauseButton.isEnabled = false
        seekSlider.isEnabled = false
        offsetStepper.isEnabled = false
        offsetResetButton.isEnabled = false
        audioControl.isEnabled = false
        loadingIndicator.stopAnimating()
    }

    private func showBanner(_ text: String) {
        bannerLabel.text = text
        bannerLabel.isHidden = false
        bannerTimer?.invalidate()
        bannerTimer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: false) { [weak self] _ in
            self?.bannerLabel.isHidden = true
        }
    }

    private func formatClock(_ value: Double) -> String {
        let total = max(0, Int(value))
        return String(format: "%02d:%02d", total / 60, total % 60)
    }

    private func formatSignedClock(_ value: Double) -> String {
        let sign = value < 0 ? "-" : ""
        let total = Int(abs(value))
        return sign + String(format: "%02d:%02d", total / 60, total % 60)
    }

    // MARK: - 视图层级

    private func buildHierarchy() {
        // 先入根层级：子视图的互相约束（banner↔dock、spinner↔paneContainer）必须有共同祖先。
        topBarView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBarView)
        paneContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(paneContainer)
        dockView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(dockView)

        configureTopBar()
        configurePanes()
        configureDock()
        // 浮层最后加：加载指示与提示条必须压在画面/底栏之上。
        configureOverlays()

        NSLayoutConstraint.activate([
            topBarView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topBarView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            topBarView.topAnchor.constraint(equalTo: topLayoutGuide.bottomAnchor),
            topBarView.heightAnchor.constraint(equalToConstant: 44),

            paneContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            paneContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            paneContainer.topAnchor.constraint(equalTo: topBarView.bottomAnchor),
            paneContainer.bottomAnchor.constraint(equalTo: dockView.topAnchor),

            dockView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            dockView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            dockView.bottomAnchor.constraint(equalTo: bottomLayoutGuide.topAnchor),
            dockView.heightAnchor.constraint(equalToConstant: 196)
        ])
    }

    private func configureTopBar() {
        topBarView.backgroundColor = UIColor(white: 0, alpha: 0.65)

        backButton.setTitle("‹ 返回", for: .normal)
        backButton.tintColor = .white
        backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
        backButton.translatesAutoresizingMaskIntoConstraints = false
        topBarView.addSubview(backButton)

        titleLabel.text = "双视频对比"
        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 14, weight: .medium)
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        topBarView.addSubview(titleLabel)

        NSLayoutConstraint.activate([
            backButton.leadingAnchor.constraint(equalTo: topBarView.leadingAnchor, constant: 12),
            backButton.centerYAnchor.constraint(equalTo: topBarView.centerYAnchor),
            titleLabel.leadingAnchor.constraint(equalTo: backButton.trailingAnchor, constant: 8),
            titleLabel.trailingAnchor.constraint(equalTo: topBarView.trailingAnchor, constant: -12),
            titleLabel.centerYAnchor.constraint(equalTo: topBarView.centerYAnchor)
        ])
    }

    private func configurePanes() {
        leftPane.translatesAutoresizingMaskIntoConstraints = false
        rightPane.translatesAutoresizingMaskIntoConstraints = false
        paneDivider.translatesAutoresizingMaskIntoConstraints = false
        paneDivider.backgroundColor = UIColor(white: 0.3, alpha: 1)
        paneContainer.addSubview(leftPane)
        paneContainer.addSubview(paneDivider)
        paneContainer.addSubview(rightPane)

        configurePane(
            leftPane,
            surface: leftSurface,
            blackout: leftBlackoutView,
            title: leftTitleLabel,
            audio: leftAudioLabel
        )
        configurePane(
            rightPane,
            surface: rightSurface,
            blackout: rightBlackoutView,
            title: rightTitleLabel,
            audio: rightAudioLabel
        )

        NSLayoutConstraint.activate([
            leftPane.leadingAnchor.constraint(equalTo: paneContainer.leadingAnchor),
            leftPane.trailingAnchor.constraint(equalTo: paneContainer.trailingAnchor),
            leftPane.topAnchor.constraint(equalTo: paneContainer.topAnchor),

            paneDivider.leadingAnchor.constraint(equalTo: paneContainer.leadingAnchor),
            paneDivider.trailingAnchor.constraint(equalTo: paneContainer.trailingAnchor),
            paneDivider.topAnchor.constraint(equalTo: leftPane.bottomAnchor),
            paneDivider.heightAnchor.constraint(equalToConstant: 2),

            rightPane.leadingAnchor.constraint(equalTo: paneContainer.leadingAnchor),
            rightPane.trailingAnchor.constraint(equalTo: paneContainer.trailingAnchor),
            rightPane.topAnchor.constraint(equalTo: paneDivider.bottomAnchor),
            rightPane.bottomAnchor.constraint(equalTo: paneContainer.bottomAnchor),
            rightPane.heightAnchor.constraint(equalTo: leftPane.heightAnchor)
        ])
    }

    private func configurePane(
        _ pane: UIView,
        surface: PlayerSurfaceView,
        blackout: UIView,
        title: UILabel,
        audio: UILabel
    ) {
        pane.backgroundColor = .black
        pane.clipsToBounds = true

        surface.translatesAutoresizingMaskIntoConstraints = false
        pane.addSubview(surface)

        // dormant 轨黑层：常驻视图切 alpha 会闪帧，这里直接切 isHidden。
        blackout.backgroundColor = .black
        blackout.isHidden = true
        blackout.isUserInteractionEnabled = false
        blackout.translatesAutoresizingMaskIntoConstraints = false
        pane.addSubview(blackout)

        title.textColor = .white
        title.font = .systemFont(ofSize: 12, weight: .medium)
        title.backgroundColor = UIColor(white: 0, alpha: 0.45)
        title.lineBreakMode = .byTruncatingMiddle
        title.translatesAutoresizingMaskIntoConstraints = false
        pane.addSubview(title)

        audio.textColor = .white
        audio.font = .systemFont(ofSize: 10, weight: .medium)
        audio.textAlignment = .center
        audio.backgroundColor = UIColor(white: 0, alpha: 0.45)
        audio.translatesAutoresizingMaskIntoConstraints = false
        pane.addSubview(audio)

        NSLayoutConstraint.activate([
            surface.leadingAnchor.constraint(equalTo: pane.leadingAnchor),
            surface.trailingAnchor.constraint(equalTo: pane.trailingAnchor),
            surface.topAnchor.constraint(equalTo: pane.topAnchor),
            surface.bottomAnchor.constraint(equalTo: pane.bottomAnchor),

            blackout.leadingAnchor.constraint(equalTo: pane.leadingAnchor),
            blackout.trailingAnchor.constraint(equalTo: pane.trailingAnchor),
            blackout.topAnchor.constraint(equalTo: pane.topAnchor),
            blackout.bottomAnchor.constraint(equalTo: pane.bottomAnchor),

            title.leadingAnchor.constraint(equalTo: pane.leadingAnchor, constant: 8),
            title.topAnchor.constraint(equalTo: pane.topAnchor, constant: 6),
            title.trailingAnchor.constraint(lessThanOrEqualTo: audio.leadingAnchor, constant: -8),

            audio.trailingAnchor.constraint(equalTo: pane.trailingAnchor, constant: -8),
            audio.topAnchor.constraint(equalTo: pane.topAnchor, constant: 6),
            audio.widthAnchor.constraint(equalToConstant: 40)
        ])
    }

    private func configureDock() {
        dockView.backgroundColor = UIColor(white: 0, alpha: 0.78)

        seekSlider.minimumValue = 0
        seekSlider.maximumValue = 1
        seekSlider.value = 0
        seekSlider.tintColor = .white
        seekSlider.addTarget(self, action: #selector(seekTouchDown), for: .touchDown)
        seekSlider.addTarget(self, action: #selector(seekValueChanged), for: .valueChanged)
        seekSlider.addTarget(
            self,
            action: #selector(seekTouchEnded),
            for: [.touchUpInside, .touchUpOutside, .touchCancel]
        )
        seekSlider.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(seekSlider)

        timeLabel.text = "00:00 / 00:00"
        timeLabel.textColor = .white
        timeLabel.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        timeLabel.textAlignment = .right
        timeLabel.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(timeLabel)

        playPauseButton.setTitle("▶", for: .normal)
        playPauseButton.tintColor = .white
        playPauseButton.titleLabel?.font = .systemFont(ofSize: 22, weight: .medium)
        playPauseButton.backgroundColor = UIColor(white: 1, alpha: 0.15)
        playPauseButton.layer.cornerRadius = 8
        playPauseButton.addTarget(self, action: #selector(playPauseTapped), for: .touchUpInside)
        playPauseButton.translatesAutoresizingMaskIntoConstraints = false

        offsetValueLabel.text = "右片 +0.0 s"
        offsetValueLabel.textColor = .white
        offsetValueLabel.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        offsetValueLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        offsetValueLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        offsetValueLabel.translatesAutoresizingMaskIntoConstraints = false

        offsetStepper.minimumValue = -10
        offsetStepper.maximumValue = 10
        offsetStepper.stepValue = 0.1
        offsetStepper.value = 0
        offsetStepper.addTarget(self, action: #selector(offsetStepperChanged), for: .valueChanged)
        offsetStepper.translatesAutoresizingMaskIntoConstraints = false

        offsetResetButton.setTitle("归零", for: .normal)
        offsetResetButton.tintColor = .white
        offsetResetButton.titleLabel?.font = .systemFont(ofSize: 12)
        offsetResetButton.addTarget(self, action: #selector(offsetResetTapped), for: .touchUpInside)
        offsetResetButton.translatesAutoresizingMaskIntoConstraints = false

        let transportRow = UIStackView(arrangedSubviews: [
            playPauseButton,
            offsetValueLabel,
            offsetStepper,
            offsetResetButton
        ])
        transportRow.axis = .horizontal
        transportRow.alignment = .center
        transportRow.spacing = 6
        transportRow.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(transportRow)

        audioControl.selectedSegmentIndex = 0
        audioControl.addTarget(self, action: #selector(audioSideChanged), for: .valueChanged)
        audioControl.translatesAutoresizingMaskIntoConstraints = false

        let audioTitleLabel = UILabel()
        audioTitleLabel.text = "发声"
        audioTitleLabel.textColor = .white
        audioTitleLabel.font = .systemFont(ofSize: 12)

        let audioRow = UIStackView(arrangedSubviews: [audioTitleLabel, audioControl])
        audioRow.axis = .horizontal
        audioRow.alignment = .center
        audioRow.spacing = 8
        audioRow.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(audioRow)

        statusLabel.text = "装载中…"
        statusLabel.textColor = UIColor(white: 0.85, alpha: 1)
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.adjustsFontSizeToFitWidth = true
        statusLabel.minimumScaleFactor = 0.75
        statusLabel.numberOfLines = 1
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(statusLabel)

        footnoteLabel.text = "临时竖排布局（未定版）· 仅单侧发声\n自动音频对齐、内录音轨切换、软追赶与对比录屏均未实现"
        footnoteLabel.textColor = UIColor(white: 0.62, alpha: 1)
        footnoteLabel.font = .systemFont(ofSize: 10)
        footnoteLabel.numberOfLines = 2
        footnoteLabel.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(footnoteLabel)

        NSLayoutConstraint.activate([
            seekSlider.leadingAnchor.constraint(equalTo: dockView.leadingAnchor, constant: 12),
            seekSlider.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            seekSlider.topAnchor.constraint(equalTo: dockView.topAnchor, constant: 8),

            timeLabel.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            timeLabel.topAnchor.constraint(equalTo: seekSlider.bottomAnchor, constant: 2),

            transportRow.leadingAnchor.constraint(equalTo: dockView.leadingAnchor, constant: 12),
            transportRow.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            transportRow.topAnchor.constraint(equalTo: timeLabel.bottomAnchor, constant: 6),
            transportRow.heightAnchor.constraint(equalToConstant: 36),

            playPauseButton.widthAnchor.constraint(equalToConstant: 64),
            offsetResetButton.widthAnchor.constraint(equalToConstant: 44),

            audioRow.leadingAnchor.constraint(equalTo: dockView.leadingAnchor, constant: 12),
            audioRow.trailingAnchor.constraint(lessThanOrEqualTo: dockView.trailingAnchor, constant: -12),
            audioRow.topAnchor.constraint(equalTo: transportRow.bottomAnchor, constant: 6),
            audioRow.heightAnchor.constraint(equalToConstant: 30),
            audioControl.widthAnchor.constraint(equalToConstant: 140),

            statusLabel.leadingAnchor.constraint(equalTo: dockView.leadingAnchor, constant: 12),
            statusLabel.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            statusLabel.topAnchor.constraint(equalTo: audioRow.bottomAnchor, constant: 4),

            footnoteLabel.leadingAnchor.constraint(equalTo: dockView.leadingAnchor, constant: 12),
            footnoteLabel.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            footnoteLabel.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 2),
            footnoteLabel.bottomAnchor.constraint(lessThanOrEqualTo: dockView.bottomAnchor, constant: -8)
        ])
    }

    private func configureOverlays() {
        loadingIndicator.hidesWhenStopped = true
        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(loadingIndicator)

        bannerLabel.textColor = .white
        bannerLabel.font = .systemFont(ofSize: 12)
        bannerLabel.backgroundColor = UIColor(white: 0, alpha: 0.7)
        bannerLabel.numberOfLines = 2
        bannerLabel.isHidden = true
        bannerLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bannerLabel)

        NSLayoutConstraint.activate([
            loadingIndicator.centerXAnchor.constraint(equalTo: paneContainer.centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: paneContainer.centerYAnchor),

            bannerLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            bannerLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            bannerLabel.bottomAnchor.constraint(equalTo: dockView.topAnchor, constant: -8)
        ])
    }
}
