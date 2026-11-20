import UIKit
import AVFoundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 播放页主控制器（iOS 10 UIKit，对齐 Android `PlayerScreen` 的 UIKit 重写）。
///
/// 设计口径：
/// - **单击/双击仲裁**：单击（延迟）显隐控件、双击启停；两个手势靠 `PlayerTapArbiter` 共存；
/// - **双指缩放/平移**：`PlayerSurfaceView` 持有 `UIPinchGestureRecognizer` + `UIPanGestureRecognizer`，
///   增量上报由本控制器复合，`PlayerZoomPanMath.clampPan` 钳制；
/// - **逐帧步进**：只接受暂停态；失败原因直接显示（「该设备不支持精确逐帧」）；
/// - **AB 循环**：A/B 打点 + 循环开关 + 清除；到 B 回 A 由 `PlayerEngine` 自动处理；
/// - **倍速**：五档（0.1/0.3/0.5/0.75/1.0），`AVPlayer.rate` 直写，音高不变；
/// - **进度条**：唯一可拖动 seek 的入口，轨道上画 A/B 标记。
///
/// 不做：分屏布局裁决（见 `ComparePlayerCoordinator`）、AI 站位、隐式媒体库行为。
/// A/B 剪辑导出入口在本页（「导出」钮取当前 A/B 区间），实际执行与 staging/资源回收
/// 在 `PlayerClipExporter`（进度/取消/结果/失败均显式回调，不在中途静默丢任务）。
final class PlayerViewController: UIViewController {

    // MARK: - 依赖

    private let engine: PlayerEngine
    private let clipExporter = PlayerClipExporter()
    private let arbiter = PlayerTapArbiter(windowMs: 240)
    private var mediaURL: URL?

    private var mediaFileName = ""
    var allowsArcRepair = true {
        didSet { updateArcRepairVisibility() }
    }

    // MARK: - UI 状态

    private var controlsVisible: Bool = true {
        didSet { updateChromeVisibility() }
    }
    private var topBarCollapsed: Bool = false {
        didSet { updateChromeVisibility() }
    }
    private var speedMenuVisible = false
    private var exportedClipURL: URL?

    // A/B 打点提示
    private var bannerTimer: Timer?

    // MARK: - 视图

    private let surfaceView = PlayerSurfaceView(frame: .zero)
    private let topBarView = UIView()
    private let dockView = UIView()
    private let floatingBackButton = UIButton(type: .system)
    private let pullOutButton = UIButton(type: .system)
    private let arcRepairButton = UIButton(type: .system)
    private let playPauseButton = UIButton(type: .system)
    private let stepBackButton = UIButton(type: .system)
    private let stepForwardButton = UIButton(type: .system)
    private let aButton = UIButton(type: .system)
    private let bButton = UIButton(type: .system)
    private let loopButton = UIButton(type: .system)
    private let clearButton = UIButton(type: .system)
    private let exportButton = UIButton(type: .system)
    private let speedButton = UIButton(type: .system)
    private let fileNameLabel = UILabel()
    private let timeLabel = UILabel()
    private let bannerLabel = UILabel()
    private let exportStatusLabel = UILabel()
    private let exportActionButton = UIButton(type: .system)
    private let exportStatusView = UIStackView()
    private let seekBar = UISlider()
    private let trackAView = UIView()
    private let trackBView = UIView()
    // `init(style:)` 是 iOS 13+；iOS 10 基线用 `init(activityIndicatorStyle:)`（仅弃用警告）。
    private let loadingIndicator = UIActivityIndicatorView(activityIndicatorStyle: .white)
    private let speedMenuView = UIView()

    private var chrome: PlayerChrome = playerChromeOf(controlsVisible: true, topBarCollapsed: false)

    // MARK: - 初始化

    init(engine: PlayerEngine = PlayerEngine()) {
        self.engine = engine
        super.init(nibName: nil, bundle: nil)
    }

    /// 便捷入口：装载后可直接 present（`load` 内部会先建视图再 attach）。
    convenience init(url: URL, fileName: String) {
        self.init()
        load(url: url, fileName: fileName)
    }

    required init?(coder: NSCoder) {
        engine = PlayerEngine()
        super.init(coder: coder)
    }

    deinit {
        bannerTimer?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSurface()
        configureControls()
        configureEngineCallbacks()
        updateArcRepairVisibility()
        updateChromeVisibility()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // 离开页面前暂停；引擎不 release（下次进页复用同一实例）。
        engine.softPause(true)
    }

    /// 装载一条视频（App 容器 URL 或外来来源绝对路径）。
    func load(url: URL, fileName: String) {
        // 允许调用方在 present 之前装载：先建视图，确保引擎回调已挂上再 attach。
        loadViewIfNeeded()
        mediaURL = url
        mediaFileName = fileName
        fileNameLabel.text = fileName
        engine.attach(url: url)
        surfaceView.player = engine.playerForDisplay
        surfaceView.resetZoomPan()
        updateArcRepairVisibility()
    }

    // MARK: - 引擎回调

    private func configureEngineCallbacks() {
        engine.onStateChanged = { [weak self] state in
            self?.onStateChanged(state)
        }
        engine.onProgress = { [weak self] position, duration in
            self?.updateProgress(position: position, duration: duration)
        }
        engine.onSeekCompleted = { [weak self] success in
            if !success { self?.showBanner("定位失败") }
        }
        engine.onFrameStep = { [weak self] outcome in
            if let failure = outcome.failure {
                self?.showBanner("逐帧降级：\(String(describing: failure))")
            }
        }
        engine.onABChanged = { [weak self] _ in
            self?.updateABMarks()
        }
    }

    private func onStateChanged(_ state: PlayerPlaybackState) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.loadingIndicator.stopAnimating()
            switch state {
            case .loading:
                self.loadingIndicator.startAnimating()
            case .playing:
                self.playPauseButton.setTitle("❚❚", for: .normal)
            case .paused, .ready:
                self.playPauseButton.setTitle("▶", for: .normal)
                // 视频方向取自当前轨的 preferredTransform（不读设备方向）。
                self.surfaceView.updateRotation(from: self.engine.videoTrackForDisplay)
                self.surfaceView.player = self.engine.playerForDisplay
            case .ended:
                self.playPauseButton.setTitle("▶", for: .normal)
            case .failed(let failure):
                self.playPauseButton.setTitle("!", for: .normal)
                self.showBanner("播放失败：\(failure.reason)")
            default:
                break
            }
        }
    }

    private func updateProgress(position: Double, duration: Double) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let isDragging = self.seekBar.isTracking
            if duration > 0, !isDragging {
                self.seekBar.maximumValue = Float(duration)
                self.seekBar.value = Float(min(position, duration))
            }
            self.timeLabel.text = formatSeconds(position) + " / " + formatSeconds(duration)
            self.updateABMarks()
        }
    }

    // MARK: - 视图布局

    private func configureSurface() {
        surfaceView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(surfaceView)
        NSLayoutConstraint.activate([
            surfaceView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            surfaceView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            surfaceView.topAnchor.constraint(equalTo: topLayoutGuide.bottomAnchor),
            surfaceView.bottomAnchor.constraint(equalTo: bottomLayoutGuide.topAnchor)
        ])
        surfaceView.onPinchChanged = { [weak self] scale, translation in
            self?.applyPinchDelta(scale: scale, translation: translation)
        }
        // 只从画面接收单击，底栏、进度和取消按钮保留自己的触控。
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.numberOfTapsRequired = 1
        surfaceView.addGestureRecognizer(tap)
    }

    private func applyPinchDelta(scale: CGFloat, translation: CGSize) {
        let currentScale = surfaceView.zoomScale
        let newScale = PlayerZoomPanMath.clampScale(currentScale * scale)
        let rawPan = CGSize(
            width: surfaceView.panOffset.width + translation.width,
            height: surfaceView.panOffset.height + translation.height
        )
        let clamped = PlayerZoomPanMath.clampPan(rawPan, scale: newScale, viewport: surfaceView.bounds.size)
        surfaceView.zoomScale = newScale
        surfaceView.panOffset = clamped
    }

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
        if surfaceView.isTwoFingerGestureActive { return }
        let nowMs = currentTimeMs()
        switch arbiter.onPointerUp(nowMs: nowMs) {
        case .deferredToggle:
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.24) { [weak self] in
                guard let self = self else { return }
                if self.arbiter.onToggleDue(nowMs: self.currentTimeMs()) {
                    self.controlsVisible = !self.controlsVisible
                }
            }
        case .playPause:
            arbiter.cancelPending()
            engine.softPause(engineStateIsPlaying())
        }
    }

    private func engineStateIsPlaying() -> Bool {
        if engine.currentStatus.isPlaying { return true }
        return false
    }

    private func currentTimeMs() -> Double {
        return CACurrentMediaTime() * 1000
    }

    private func configureControls() {
        // 顶栏
        topBarView.translatesAutoresizingMaskIntoConstraints = false
        topBarView.backgroundColor = UIColor(white: 0, alpha: 0.5)
        view.addSubview(topBarView)
        NSLayoutConstraint.activate([
            topBarView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topBarView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            topBarView.topAnchor.constraint(equalTo: topLayoutGuide.bottomAnchor),
            topBarView.heightAnchor.constraint(equalToConstant: 44)
        ])
        fileNameLabel.textColor = .white
        fileNameLabel.font = .systemFont(ofSize: 14, weight: .medium)
        fileNameLabel.translatesAutoresizingMaskIntoConstraints = false
        fileNameLabel.lineBreakMode = .byTruncatingMiddle
        let back = UIButton(type: .system)
        back.setTitle("‹ 返回", for: .normal)
        back.tintColor = .white
        back.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
        let collapse = UIButton(type: .system)
        collapse.setTitle("收起", for: .normal)
        collapse.tintColor = .white
        collapse.addTarget(self, action: #selector(collapseTapped), for: .touchUpInside)
        let header = UIStackView(arrangedSubviews: [back, fileNameLabel, collapse])
        header.spacing = 8
        header.alignment = .fill
        header.translatesAutoresizingMaskIntoConstraints = false
        fileNameLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        topBarView.addSubview(header)
        NSLayoutConstraint.activate([
            header.leadingAnchor.constraint(equalTo: topBarView.leadingAnchor, constant: 12),
            header.trailingAnchor.constraint(equalTo: topBarView.trailingAnchor, constant: -12),
            header.topAnchor.constraint(equalTo: topBarView.topAnchor),
            header.bottomAnchor.constraint(equalTo: topBarView.bottomAnchor),
            back.widthAnchor.constraint(greaterThanOrEqualToConstant: 44),
            collapse.widthAnchor.constraint(greaterThanOrEqualToConstant: 44)
        ])

        // 浮动返回（顶栏收起时恒在）
        floatingBackButton.setTitle("‹ 返回", for: .normal)
        floatingBackButton.tintColor = .white
        floatingBackButton.translatesAutoresizingMaskIntoConstraints = false
        floatingBackButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
        view.addSubview(floatingBackButton)
        NSLayoutConstraint.activate([
            floatingBackButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            floatingBackButton.topAnchor.constraint(equalTo: topLayoutGuide.bottomAnchor, constant: 8)
        ])

        // 底栏 dock
        dockView.translatesAutoresizingMaskIntoConstraints = false
        dockView.backgroundColor = UIColor(white: 0, alpha: 0.6)
        view.addSubview(dockView)
        NSLayoutConstraint.activate([
            dockView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            dockView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            dockView.bottomAnchor.constraint(equalTo: bottomLayoutGuide.topAnchor),
            dockView.heightAnchor.constraint(equalToConstant: 136)
        ])

        // 进度条
        seekBar.tintColor = .white
        seekBar.translatesAutoresizingMaskIntoConstraints = false
        seekBar.addTarget(self, action: #selector(seekChanged), for: .valueChanged)
        seekBar.addTarget(self, action: #selector(seekEnded), for: [.touchUpInside, .touchUpOutside])
        dockView.addSubview(seekBar)
        NSLayoutConstraint.activate([
            seekBar.leadingAnchor.constraint(equalTo: dockView.leadingAnchor, constant: 12),
            seekBar.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            seekBar.topAnchor.constraint(equalTo: dockView.topAnchor, constant: 8)
        ])
        timeLabel.textColor = .white
        timeLabel.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        timeLabel.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(timeLabel)
        NSLayoutConstraint.activate([
            timeLabel.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            timeLabel.topAnchor.constraint(equalTo: seekBar.bottomAnchor, constant: 4)
        ])

        // 控制行：后退 / 播放 / 前进
        stepBackButton.setTitle("◂◂", for: .normal)
        stepBackButton.tintColor = .white
        stepBackButton.addTarget(self, action: #selector(stepBackTapped), for: .touchUpInside)
        playPauseButton.setTitle("▶", for: .normal)
        playPauseButton.tintColor = .white
        playPauseButton.addTarget(self, action: #selector(playPauseTapped), for: .touchUpInside)
        stepForwardButton.setTitle("▸▸", for: .normal)
        stepForwardButton.tintColor = .white
        stepForwardButton.addTarget(self, action: #selector(stepForwardTapped), for: .touchUpInside)

        let transportRow = UIStackView(arrangedSubviews: [stepBackButton, playPauseButton, stepForwardButton])
        transportRow.axis = .horizontal
        transportRow.distribution = .fillEqually
        transportRow.spacing = 12
        transportRow.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(transportRow)
        NSLayoutConstraint.activate([
            transportRow.leadingAnchor.constraint(equalTo: dockView.leadingAnchor, constant: 12),
            transportRow.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            transportRow.topAnchor.constraint(equalTo: timeLabel.bottomAnchor, constant: 8),
            transportRow.heightAnchor.constraint(equalToConstant: 32)
        ])

        // AB 行
        aButton.setTitle("A", for: .normal)
        aButton.tintColor = .white
        aButton.addTarget(self, action: #selector(markATapped), for: .touchUpInside)
        bButton.setTitle("B", for: .normal)
        bButton.tintColor = .white
        bButton.addTarget(self, action: #selector(markBTapped), for: .touchUpInside)
        loopButton.setTitle("⟳", for: .normal)
        loopButton.tintColor = .white
        loopButton.addTarget(self, action: #selector(loopTapped), for: .touchUpInside)
        clearButton.setTitle("✕", for: .normal)
        clearButton.tintColor = .white
        clearButton.addTarget(self, action: #selector(clearTapped), for: .touchUpInside)
        exportButton.setTitle("导出", for: .normal)
        exportButton.tintColor = .white
        exportButton.addTarget(self, action: #selector(exportTapped), for: .touchUpInside)
        arcRepairButton.setTitle("补弧", for: .normal)
        arcRepairButton.tintColor = .white
        arcRepairButton.addTarget(self, action: #selector(arcRepairTapped), for: .touchUpInside)
        speedButton.setTitle("1.00×", for: .normal)
        speedButton.tintColor = .white
        speedButton.addTarget(self, action: #selector(speedTapped), for: .touchUpInside)

        let abRow = UIStackView(arrangedSubviews: [aButton, bButton, loopButton, clearButton, exportButton, arcRepairButton, speedButton])
        abRow.axis = .horizontal
        abRow.distribution = .fillEqually
        abRow.spacing = 6
        abRow.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(abRow)
        NSLayoutConstraint.activate([
            abRow.leadingAnchor.constraint(equalTo: dockView.leadingAnchor, constant: 12),
            abRow.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            abRow.topAnchor.constraint(equalTo: transportRow.bottomAnchor, constant: 8),
            abRow.heightAnchor.constraint(equalToConstant: 30)
        ])

        // 倍速菜单（浮层）
        speedMenuView.backgroundColor = UIColor(white: 0.1, alpha: 0.95)
        speedMenuView.layer.cornerRadius = 8
        speedMenuView.isHidden = true
        speedMenuView.translatesAutoresizingMaskIntoConstraints = false
        dockView.addSubview(speedMenuView)
        NSLayoutConstraint.activate([
            speedMenuView.trailingAnchor.constraint(equalTo: dockView.trailingAnchor, constant: -12),
            speedMenuView.bottomAnchor.constraint(equalTo: abRow.topAnchor, constant: -6),
            speedMenuView.widthAnchor.constraint(equalToConstant: 96)
        ])
        let speedStack = UIStackView()
        speedStack.axis = .vertical
        speedStack.spacing = 4
        speedStack.translatesAutoresizingMaskIntoConstraints = false
        speedMenuView.addSubview(speedStack)
        NSLayoutConstraint.activate([
            speedStack.topAnchor.constraint(equalTo: speedMenuView.topAnchor, constant: 8),
            speedStack.leadingAnchor.constraint(equalTo: speedMenuView.leadingAnchor, constant: 8),
            speedStack.trailingAnchor.constraint(equalTo: speedMenuView.trailingAnchor, constant: -8),
            speedStack.bottomAnchor.constraint(equalTo: speedMenuView.bottomAnchor, constant: -8)
        ])
        for tier in PlayerSpeedTiers.tiers {
            let button = UIButton(type: .system)
            button.setTitle(String(format: "%.2f×", tier), for: .normal)
            button.tintColor = .white
            button.tag = Int(tier * 100)
            button.addTarget(self, action: #selector(speedPicked(_:)), for: .touchUpInside)
            speedStack.addArrangedSubview(button)
        }

        // AB 标记视图（进度条轨道上的 A/B 刻度；位置按进度条宽度实时算，故用手动 frame）
        trackAView.backgroundColor = .white
        trackAView.translatesAutoresizingMaskIntoConstraints = true
        trackAView.isHidden = true
        trackBView.backgroundColor = .white
        trackBView.translatesAutoresizingMaskIntoConstraints = true
        trackBView.isHidden = true
        dockView.addSubview(trackAView)
        dockView.addSubview(trackBView)

        // 加载指示
        loadingIndicator.hidesWhenStopped = true
        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(loadingIndicator)
        NSLayoutConstraint.activate([
            loadingIndicator.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])

        // 导出状态常驻在底栏上方，准备、写入、清理和发布都不靠短时提示条表达。
        exportStatusLabel.textColor = .white
        exportStatusLabel.font = .systemFont(ofSize: 12, weight: .medium)
        exportStatusLabel.backgroundColor = UIColor(white: 0, alpha: 0.72)
        exportStatusLabel.layer.cornerRadius = 6
        exportStatusLabel.clipsToBounds = true
        exportStatusLabel.textAlignment = .center
        exportStatusLabel.numberOfLines = 0
        exportStatusLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        exportActionButton.setTitle("播放成片", for: .normal)
        exportActionButton.tintColor = .white
        exportActionButton.backgroundColor = UIColor(white: 0.18, alpha: 0.95)
        exportActionButton.layer.cornerRadius = 6
        exportActionButton.isHidden = true
        exportActionButton.addTarget(self, action: #selector(exportActionTapped), for: .touchUpInside)
        let clipWidth = exportActionButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 84)
        clipWidth.priority = .defaultHigh
        exportStatusView.alignment = .center
        exportStatusView.spacing = 8
        exportStatusView.addArrangedSubview(exportStatusLabel)
        exportStatusView.addArrangedSubview(exportActionButton)
        exportStatusView.translatesAutoresizingMaskIntoConstraints = false
        exportStatusView.isHidden = true
        view.addSubview(exportStatusView)
        NSLayoutConstraint.activate([
            exportStatusView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            exportStatusView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            exportStatusView.bottomAnchor.constraint(equalTo: dockView.topAnchor, constant: -8),
            exportStatusLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 32),
            exportActionButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            clipWidth
        ])

        // 提示条
        bannerLabel.textColor = .white
        bannerLabel.font = .systemFont(ofSize: 12)
        bannerLabel.backgroundColor = UIColor(white: 0, alpha: 0.7)
        bannerLabel.numberOfLines = 2
        bannerLabel.translatesAutoresizingMaskIntoConstraints = false
        bannerLabel.isHidden = true
        view.addSubview(bannerLabel)
        NSLayoutConstraint.activate([
            bannerLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            bannerLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            bannerLabel.bottomAnchor.constraint(equalTo: exportStatusLabel.topAnchor, constant: -4)
        ])

        // 「拉出」钮（顶栏已收起且 dock 可见时渲染）
        pullOutButton.setTitle("⌃ 展开", for: .normal)
        pullOutButton.tintColor = .white
        pullOutButton.backgroundColor = UIColor(white: 0, alpha: 0.5)
        pullOutButton.layer.cornerRadius = 6
        pullOutButton.addTarget(self, action: #selector(pullOutTapped), for: .touchUpInside)
        pullOutButton.translatesAutoresizingMaskIntoConstraints = false
        pullOutButton.isHidden = true
        view.addSubview(pullOutButton)
        NSLayoutConstraint.activate([
            pullOutButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            pullOutButton.topAnchor.constraint(equalTo: topLayoutGuide.bottomAnchor, constant: 8)
        ])
    }

    // MARK: - Chrome 可见性

    private func updateChromeVisibility() {
        chrome = playerChromeOf(controlsVisible: controlsVisible, topBarCollapsed: topBarCollapsed)
        topBarView.isHidden = !chrome.topBar
        floatingBackButton.isHidden = !chrome.floatingBack
        dockView.isHidden = !chrome.dock
        pullOutButton.isHidden = !chrome.pullOut
        if !chrome.dock {
            speedMenuVisible = false
            speedMenuView.isHidden = true
        }
    }

    // MARK: - AB 标记

    private func updateABMarks() {
        let range = engine.currentABRange
        let duration = engine.durationSeconds
        dockView.layoutIfNeeded()
        if range.start.isValid, range.start.isNumeric, duration > 0 {
            let fraction = CGFloat(min(range.start.seconds / duration, 1))
            trackAView.isHidden = false
            let x = dockView.bounds.width * fraction
            trackAView.frame = CGRect(x: x, y: 4, width: 2, height: 8)
        } else {
            trackAView.isHidden = true
        }
        if range.end.isValid, range.end.isNumeric, duration > 0 {
            let fraction = CGFloat(min(range.end.seconds / duration, 1))
            trackBView.isHidden = false
            let x = dockView.bounds.width * fraction
            trackBView.frame = CGRect(x: x, y: 4, width: 2, height: 8)
        } else {
            trackBView.isHidden = true
        }
        aButton.tintColor = range.start.isValid ? .systemBlue : .white
        bButton.tintColor = range.end.isValid ? .systemBlue : .white
        loopButton.tintColor = range.loopEnabled ? .systemBlue : .white
    }

    // MARK: - 动作

    @objc private func backTapped() {
        guard !clipExporter.isExporting else {
            if clipExporter.canCancel {
                showBanner("导出正在进行，返回前请先取消导出")
            } else {
                showBanner("导出正在收尾，完成后才能返回")
            }
            return
        }
        dismiss(animated: true, completion: nil)
    }

    @objc private func collapseTapped() {
        topBarCollapsed = true
    }

    @objc private func pullOutTapped() {
        topBarCollapsed = false
    }

    private func updateArcRepairVisibility() {
        arcRepairButton.isHidden = !allowsArcRepair || mediaURL == nil
    }

    @objc private func arcRepairTapped() {
        guard allowsArcRepair, let url = mediaURL, presentedViewController == nil,
              !clipExporter.isExporting else { return }
        present(ArcRepairViewController(url: url, fileName: mediaFileName), animated: true)
    }

    @objc private func playPauseTapped() {
        engine.softPause(engineStateIsPlaying())
    }

    @objc private func stepBackTapped() {
        engine.stepFrame(forward: false)
    }

    @objc private func stepForwardTapped() {
        engine.stepFrame(forward: true)
    }

    @objc private func markATapped() {
        engine.markA()
        updateABMarks()
    }

    @objc private func markBTapped() {
        if !engine.markB() {
            showBanner("AB 无效：B 需晚于 A")
        }
        updateABMarks()
    }

    @objc private func loopTapped() {
        if !engine.toggleAbLoop() {
            showBanner("AB 循环无效：A/B 未合法打点")
        }
        updateABMarks()
    }

    @objc private func clearTapped() {
        engine.clearAb()
        updateABMarks()
    }

    // MARK: - 剪辑导出（A/B 区间 → 成片）

    @objc private func exportTapped() {
        guard !clipExporter.isExporting else { return }
        exportCurrentAB()
    }

    /// 单片剪辑取现场声音轨，导出器负责暂存和发布，两类回调都在主线程交付。
    private func exportCurrentAB() {
        guard let url = mediaURL else {
            showBanner("无可导出的媒体")
            return
        }
        let range = engine.currentABRange
        guard range.isValid, range.start.isNumeric, range.end.isNumeric else {
            showBanner("请先打 A/B 点")
            return
        }
        let clipRange = ClipTimeRange(start: range.start, end: range.end)
        clipExporter.onPhase = { [weak self] phase in
            self?.onExportPhase(phase)
        }
        clipExporter.export(
            sourceURL: url,
            sourceDisplayName: url.lastPathComponent,
            range: clipRange,
            audioTrack: .environment,
            offsetSeconds: 0
        ) { [weak self] outcome in
            self?.onExportOutcome(outcome)
        }
    }

    private func onExportPhase(_ phase: PlayerClipExportPhase) {
        exportedClipURL = nil
        exportButton.isEnabled = false
        arcRepairButton.isEnabled = false
        exportStatusView.isHidden = false
        exportActionButton.setTitle("取消导出", for: .normal)
        exportActionButton.isHidden = !clipExporter.canCancel
        switch phase {
        case .preparing:
            exportStatusLabel.text = "正在准备剪辑…"
        case .writing(let progress):
            let percent = progress.isFinite ? Int(min(max(progress, 0), 1) * 100) : 0
            exportStatusLabel.text = "正在写入视频 \(percent)%"
        case .cancelling:
            exportStatusLabel.text = "正在取消导出…"
        case .cleaning:
            exportStatusLabel.text = "正在清理暂存文件…"
        case .publishing:
            exportStatusLabel.text = "正在保存到片库…"
        }
    }

    private func onExportOutcome(_ outcome: PlayerClipExportOutcome) {
        clipExporter.onPhase = nil
        exportButton.isEnabled = true
        arcRepairButton.isEnabled = true
        switch outcome {
        case .completed(let url, let notes):
            exportedClipURL = url
            exportStatusLabel.text = (["剪辑已保存"] + notes).joined(separator: "\n")
            exportActionButton.setTitle("播放成片", for: .normal)
            exportActionButton.isHidden = false
        case .cancelled:
            exportStatusLabel.text = "已取消导出"
        case .failed(let failure):
            exportStatusLabel.text = "导出失败：\(failure.message)"
        }
        if exportedClipURL == nil { exportActionButton.isHidden = true }
    }

    @objc private func exportActionTapped() {
        if clipExporter.isExporting {
            clipExporter.cancel()
            return
        }
        guard let url = exportedClipURL, presentedViewController == nil else { return }
        let player = PlayerViewController(url: url, fileName: url.lastPathComponent)
        player.modalPresentationStyle = .fullScreen
        present(player, animated: true)
    }

    @objc private func speedTapped() {
        speedMenuVisible = !speedMenuVisible
        speedMenuView.isHidden = !speedMenuVisible
    }

    @objc private func speedPicked(_ sender: UIButton) {
        let tier = Float(sender.tag) / 100
        engine.setSpeed(tier)
        speedButton.setTitle(String(format: "%.2f×", tier), for: .normal)
        speedMenuVisible = false
        speedMenuView.isHidden = true
    }

    @objc private func seekChanged() {
        let seconds = Double(seekBar.value)
        engine.seek(to: seconds, commit: false)
    }

    @objc private func seekEnded() {
        let seconds = Double(seekBar.value)
        engine.seek(to: seconds, commit: true)
    }

    // MARK: - 提示

    private func showBanner(_ text: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.bannerLabel.text = text
            self.bannerLabel.isHidden = false
            self.bannerTimer?.invalidate()
            self.bannerTimer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: false) { [weak self] _ in
                self?.bannerLabel.isHidden = true
            }
        }
    }
}

// MARK: - 引擎状态只读访问（UI 层专用探针）

extension PlayerEngine {
    /// 当前状态（UI 层只读探针）。
    var currentStatus: PlayerPlaybackState {
        if isReleased { return .idle }
        return statusSnapshot
    }

    /// 当前 AB 段（UI 层只读探针）。
    var currentABRange: PlayerABRange {
        return abRangeSnapshot
    }
}

private func formatSeconds(_ value: Double) -> String {
    let total = max(0, Int(value))
    let minutes = total / 60
    let seconds = total % 60
    return String(format: "%02d:%02d", minutes, seconds)
}
