import AVFoundation
import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

/// 取景、参数抽屉和一条录像的入口。录像协调器拥有正在收尾的文件。
final class CameraPreviewViewController: UIViewController {
    private let previewView = CameraPreviewView()
    private let overlayView = CameraStatusOverlayView()
    private let parameterPanel = CameraParameterPanelView()
    private let parameterButton = UIButton(type: .system)
    private let recordButton = UIButton(type: .system)
    private let libraryButton = UIButton(type: .system)
    private let settingsButton = UIButton(type: .system)
    private let playButton = UIButton(type: .system)
    private let detailLabel = UILabel()
    private let sessionController: CameraSessionController
    private let parameterController: CameraParameterController
    private let recorder: CameraRecordingCoordinator
    private lazy var controls = CameraCaptureControls(presenter: self, camera: parameterController)

    private var topInsetConstraint: NSLayoutConstraint?
    private var bottomInsetConstraint: NSLayoutConstraint?
    private var sourceVisible = false
    private var startedAt: Date?
    private var clock: Timer?
    private var phase = CameraSessionPhase.idle
    private var notice: String?

    init() {
        let camera = CameraSessionController()
        sessionController = camera
        parameterController = CameraParameterController(sessionController: camera)
        recorder = CameraRecordingCoordinator(camera: camera)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    deinit {
        clock?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        buildHierarchy()
        bindCallbacks()
        observeApplicationNotifications()
        previewView.session = sessionController.session
        render()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        sourceVisible = true
        controls.reloadSettingsForNextAppearance()
        startPreviewIfVisible()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sourceVisible = false
        controls.suspendRestore()
        parameterPanel.isHidden = true
        recorder.stop(reason: "已离开取景页，保存已录部分。")
        sessionController.setPreviewVideoConnection(nil)
        sessionController.stop()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if #available(iOS 11.0, *) {
            topInsetConstraint?.constant = view.safeAreaInsets.top
            bottomInsetConstraint?.constant = -view.safeAreaInsets.bottom - 10
        } else {
            topInsetConstraint?.constant = topLayoutGuide.length
            bottomInsetConstraint?.constant = -bottomLayoutGuide.length - 10
        }
    }

    private func buildHierarchy() {
        let dock = UIStackView(arrangedSubviews: [parameterButton, recordButton, libraryButton, settingsButton])
        dock.axis = .horizontal
        dock.distribution = .fillEqually
        dock.spacing = 6
        configure(parameterButton, title: "参数", action: #selector(toggleParameters))
        configure(recordButton, title: "录像", action: #selector(toggleRecording))
        configure(libraryButton, title: "片库", action: #selector(openLibrary))
        configure(settingsButton, title: "设置", action: #selector(openSettings))
        configure(playButton, title: "回放刚录制的视频", action: #selector(playLastTake))
        detailLabel.textColor = .white
        detailLabel.font = UIFont.preferredFont(forTextStyle: .footnote)
        detailLabel.adjustsFontForContentSizeCategory = true
        detailLabel.textAlignment = .center
        detailLabel.numberOfLines = 4
        detailLabel.lineBreakMode = .byTruncatingTail
        let bottom = UIStackView(arrangedSubviews: [detailLabel, playButton, dock])
        bottom.axis = .vertical
        bottom.spacing = 8
        [previewView, overlayView, parameterPanel, bottom].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview($0)
        }
        parameterPanel.isHidden = true
        let top = overlayView.topAnchor.constraint(equalTo: view.topAnchor)
        let lower = bottom.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -10)
        topInsetConstraint = top
        bottomInsetConstraint = lower
        NSLayoutConstraint.activate([
            previewView.topAnchor.constraint(equalTo: view.topAnchor),
            previewView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            previewView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            previewView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            top,
            overlayView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            overlayView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlayView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            lower,
            bottom.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            bottom.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            parameterPanel.leadingAnchor.constraint(equalTo: bottom.leadingAnchor),
            parameterPanel.trailingAnchor.constraint(equalTo: bottom.trailingAnchor),
            parameterPanel.bottomAnchor.constraint(equalTo: bottom.topAnchor, constant: -8),
            parameterPanel.heightAnchor.constraint(equalTo: view.heightAnchor, multiplier: 0.43),
        ])
    }

    private func configure(_ button: UIButton, title: String, action: Selector) {
        button.setTitle(title, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.setTitleColor(UIColor(white: 1, alpha: 0.36), for: .disabled)
        button.titleLabel?.font = UIFont.preferredFont(forTextStyle: .subheadline)
        button.titleLabel?.adjustsFontForContentSizeCategory = true
        button.titleLabel?.adjustsFontSizeToFitWidth = true
        button.backgroundColor = UIColor(white: 0.08, alpha: 0.87)
        button.layer.cornerRadius = 12
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    private func bindCallbacks() {
        sessionController.onPhaseChange = { [weak self] phase in
            guard let self = self else { return }
            let wasRunning = self.phase.isRunning
            self.phase = phase
            if phase.isRunning && self.sourceVisible {
                self.registerPreviewConnection()
                if !wasRunning { self.parameterController.refresh() }
            } else {
                self.parameterPanel.isHidden = true
                if wasRunning { self.recorder.stop(reason: "相机预览中断，保存已录部分。") }
            }
            self.render()
        }
        sessionController.onVideoInputReplaced = { [weak self] _ in
            self?.registerPreviewConnection()
            self?.parameterController.refresh()
        }
        overlayView.onRetry = { [weak self] in
            guard let self = self, self.sourceVisible, !self.recorder.state.isBusy else { return }
            self.sessionController.retry()
        }
        overlayView.onOpenSettings = { [weak self] in self?.openSystemSettings() }
        parameterController.onSnapshot = { [weak self] snapshot in
            guard let self = self else { return }
            if self.sourceVisible && self.phase.isRunning {
                self.controls.applyStoredDefaultsIfNeeded(snapshot)
            }
            self.refreshParameters()
        }
        controls.onChange = { [weak self] in self?.refreshParameters() }
        recorder.onStateChange = { [weak self] state in self?.recordingChanged(state) }
        recorder.privateNotice = { [weak self] note in
            self?.notice = note
            self?.render()
            UIAccessibility.post(notification: .announcement, argument: note)
        }
    }

    private func registerPreviewConnection() {
        guard sourceVisible else { return }
        previewView.registerConnection(with: sessionController)
    }

    private func recordingChanged(_ state: CameraRecordingCoordinator.State) {
        if case .recording = state {
            startedAt = Date()
            clock?.invalidate()
            clock = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.render() }
        } else {
            startedAt = nil
            clock?.invalidate()
            clock = nil
        }
        if case .saved = state { parameterController.refresh() }
        if case .failed = state { parameterController.refresh() }
        render()
    }

    private func refreshParameters() {
        if !parameterPanel.isHidden { parameterPanel.update(controls.sections()) }
        render()
    }

    private func render() {
        overlayView.update(with: phase, variant: AppVariant.current)
        let canAdjust = phase.isRunning && !recorder.state.isBusy && !controls.isBusy
        let snapshot = parameterController.latest
        parameterButton.isEnabled = canAdjust
        parameterButton.setTitle(parameterPanel.isHidden ? "参数" : "收起", for: .normal)
        libraryButton.isEnabled = !recorder.state.isBusy
        settingsButton.isEnabled = !recorder.state.isBusy
        recordButton.isEnabled = canAdjust && snapshot.ability.isAvailable
        recordButton.backgroundColor = UIColor(red: 0.74, green: 0.14, blue: 0.20, alpha: 1)
        recordButton.setTitle("录像", for: .normal)
        playButton.isHidden = true
        switch recorder.state {
        case .idle:
            let status = snapshot.lastActionSummary ?? controls.restoreNotice
            detailLabel.text = ["\(snapshot.resolution) · \(snapshot.frameRate)", status]
                .compactMap { $0 }.joined(separator: "\n")
        case .preparing:
            recordButton.isEnabled = true
            recordButton.setTitle("取消", for: .normal)
            detailLabel.text = "正在准备录像…"
        case .recording(let format):
            recordButton.isEnabled = true
            recordButton.setTitle("停止", for: .normal)
            let elapsed = max(0, Int(startedAt.map { Date().timeIntervalSince($0) } ?? 0))
            let time = String(format: "%02d:%02d", elapsed / 60, elapsed % 60)
            let parts = format.components(separatedBy: "\n")
            let display = [time + " · " + (parts.first ?? "录像中")] +
                (notice.map { [$0] } ?? []) + Array(parts.dropFirst())
            detailLabel.text = display.joined(separator: "\n")
        case .saving:
            detailLabel.text = "正在保存到应用片库…"
        case .saved(_, let messages):
            detailLabel.text = (["已保存到应用片库"] + messages).joined(separator: "\n")
            playButton.isHidden = false
        case .failed(let message):
            detailLabel.text = message
        }
        detailLabel.accessibilityLabel = detailLabel.text
        detailLabel.backgroundColor = UIColor(white: 0, alpha: 0.68)
    }

    @objc private func toggleParameters() {
        guard parameterButton.isEnabled else { return }
        parameterPanel.isHidden.toggle()
        refreshParameters()
        if !parameterPanel.isHidden { parameterController.refresh() }
    }

    @objc private func toggleRecording() {
        switch recorder.state {
        case .preparing: recorder.cancelPreparation()
        case .recording: recorder.stop()
        case .saving: break
        case .idle, .saved, .failed:
            guard recordButton.isEnabled, sourceVisible else { return }
            notice = nil
            parameterPanel.isHidden = true
            recorder.start(controls.selection())
        }
    }

    @objc private func openLibrary() { presentFullScreen(CameraLibraryViewController()) }

    @objc private func openSettings() {
        let settings = SettingsViewController(style: .grouped)
        settings.navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done, target: self, action: #selector(closePresentedPage))
        presentFullScreen(UINavigationController(rootViewController: settings))
    }

    @objc private func closePresentedPage() { dismiss(animated: true) }

    @objc private func playLastTake() {
        guard case .saved(let item, _) = recorder.state, let path = item.absolutePath else { return }
        let player = PlayerViewController(url: URL(fileURLWithPath: path), fileName: item.fileName)
        presentFullScreen(player)
    }

    private func presentFullScreen(_ controller: UIViewController) {
        guard !recorder.state.isBusy, presentedViewController == nil else { return }
        controller.modalPresentationStyle = .fullScreen
        present(controller, animated: true)
    }

    private func observeApplicationNotifications() {
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(handleDidEnterBackground),
                           name: UIApplication.didEnterBackgroundNotification, object: nil)
        center.addObserver(self, selector: #selector(handleWillEnterForeground),
                           name: UIApplication.willEnterForegroundNotification, object: nil)
        center.addObserver(self, selector: #selector(handleDidBecomeActive),
                           name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    @objc private func handleDidEnterBackground() {
        recorder.stop(reason: "应用已进入后台，保存已录部分。")
        sessionController.stop()
    }

    @objc private func handleWillEnterForeground() { startPreviewIfVisible() }
    @objc private func handleDidBecomeActive() { startPreviewIfVisible() }

    private func startPreviewIfVisible() {
        guard sourceVisible, UIApplication.shared.applicationState != .background else { return }
        sessionController.start()
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }
}
