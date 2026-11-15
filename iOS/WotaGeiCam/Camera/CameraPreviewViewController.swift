import AVFoundation
import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

/// 基础取景页（UIKit，iOS 10 基线）：全屏实时预览 + 权限/生命周期/错误反馈 + 最小参数面板。
///
/// 不使用 SwiftUI / Combine / @MainActor；所有 UIKit/AVFoundation API 均在 iOS 10 可用。
/// 录制、抽帧补弧、AI 站位不在本任务范围内。
final class CameraPreviewViewController: UIViewController {

    private let previewView = CameraPreviewView()
    private let overlayView = CameraStatusOverlayView()
    private let parameterPanel = CameraParameterPanelView()

    private let sessionController = CameraSessionController()
    private let parameterController: CameraParameterController

    private var topInsetConstraint: NSLayoutConstraint?
    private var bottomInsetConstraint: NSLayoutConstraint?

    private var phase: CameraSessionPhase = .idle {
        didSet { render() }
    }

    init() {
        self.parameterController = CameraParameterController(sessionController: sessionController)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return .lightContent
    }

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
        sessionController.start()
        parameterController.refresh()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sessionController.stop()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewView.updateVideoOrientation()

        // iOS 11 起用 safeAreaInsets；更早系统（含 iOS 10）回退到 topLayoutGuide / bottomLayoutGuide。
        var topInset: CGFloat = 12
        var bottomInset: CGFloat = 12
        if #available(iOS 11.0, *) {
            topInset = view.safeAreaInsets.top + 8
            bottomInset = view.safeAreaInsets.bottom + 12
        } else {
            topInset = topLayoutGuide.length + 8
            bottomInset = bottomLayoutGuide.length + 12
        }
        topInsetConstraint?.constant = topInset
        bottomInsetConstraint?.constant = -bottomInset
    }

    // MARK: - 视图层级

    private func buildHierarchy() {
        previewView.translatesAutoresizingMaskIntoConstraints = false
        overlayView.translatesAutoresizingMaskIntoConstraints = false
        parameterPanel.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(previewView)
        view.addSubview(overlayView)
        view.addSubview(parameterPanel)

        NSLayoutConstraint.activate([
            previewView.topAnchor.constraint(equalTo: view.topAnchor),
            previewView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            previewView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            previewView.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            overlayView.topAnchor.constraint(equalTo: view.topAnchor),
            overlayView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            overlayView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlayView.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            parameterPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            parameterPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
        ])

        let topConstraint = overlayView.topAnchor.constraint(equalTo: view.topAnchor, constant: 12)
        topConstraint.isActive = true
        topInsetConstraint = topConstraint

        let bottomConstraint = parameterPanel.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -12)
        bottomConstraint.isActive = true
        bottomInsetConstraint = bottomConstraint
    }

    private func bindCallbacks() {
        sessionController.onPhaseChange = { [weak self] phase in
            self?.phase = phase
        }

        overlayView.onRetry = { [weak self] in
            self?.sessionController.retry()
        }
        overlayView.onOpenSettings = { [weak self] in
            self?.openSystemSettings()
        }

        parameterController.onSnapshot = { [weak self] snapshot in
            self?.parameterPanel.update(with: snapshot)
        }
        parameterPanel.onTorchChanged = { [weak self] enabled in
            self?.parameterController.setTorch(enabled: enabled)
        }
        parameterPanel.onExposureChanged = { [weak self] value in
            self?.parameterController.setExposureBias(value)
        }
        parameterPanel.onRefresh = { [weak self] in
            self?.parameterController.refresh()
        }
    }

    private func render() {
        overlayView.update(with: phase, variant: AppVariant.current)
    }

    // MARK: - 生命周期（不使用 UIScene）

    private func observeApplicationNotifications() {
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(handleDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    @objc private func handleDidEnterBackground() {
        sessionController.stop()
    }

    @objc private func handleWillEnterForeground() {
        sessionController.start()
    }

    @objc private func handleDidBecomeActive() {
        sessionController.start()
        parameterController.refresh()
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }
}
