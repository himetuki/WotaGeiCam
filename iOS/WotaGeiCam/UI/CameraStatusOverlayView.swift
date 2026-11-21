import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

/// 悬浮在实时预览之上的状态条与错误卡片（原生 UIKit，无 SwiftUI / Material）。
///
/// 预览运行时只显示状态条；权限、启动、中断、失败时展示原生风格卡片与操作按钮。
/// iOS 10 没有 SF Symbols（`UIImage(systemName:)` 需 iOS 13），因此用彩色圆点代替图标。
final class CameraStatusOverlayView: UIView {

    var onRetry: (() -> Void)?
    var onOpenSettings: (() -> Void)?

    private let statusDot = UIView()
    private let statusLabel = UILabel()
    private let variantLabel = UILabel()
    private let statusPill = UIVisualEffectView(effect: UIBlurEffect(style: .dark))

    private let card = UIVisualEffectView(effect: UIBlurEffect(style: .dark))
    private let cardIndicator = UIView()
    private let spinner = CameraStatusOverlayView.makeSpinner()
    private let cardTitle = UILabel()
    private let cardMessage = UILabel()
    private let retryButton = UIButton(type: .system)
    private let settingsButton = UIButton(type: .system)

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    // MARK: - 对外接口

    func update(with phase: CameraSessionPhase, variant: AppVariant) {
        statusLabel.text = phase.wotaStatusText
        statusDot.backgroundColor = phase.wotaIndicatorColor
        variantLabel.text = variant.badgeText

        guard let content = phase.wotaNoticeContent else {
            card.isHidden = true
            spinner.stopAnimating()
            return
        }

        card.isHidden = false
        cardTitle.text = content.title
        cardMessage.text = content.message
        retryButton.isHidden = !content.showsRetry
        settingsButton.isHidden = !content.showsSettings
        cardIndicator.isHidden = content.isProgress
        cardIndicator.backgroundColor = content.indicatorColor
        if content.isProgress {
            spinner.startAnimating()
        } else {
            spinner.stopAnimating()
        }
    }

    // MARK: - 构建

    private func configure() {
        backgroundColor = .clear

        configureStatusPill()
        configureCard()

        statusPill.translatesAutoresizingMaskIntoConstraints = false
        card.translatesAutoresizingMaskIntoConstraints = false
        addSubview(statusPill)
        addSubview(card)

        NSLayoutConstraint.activate([
            statusPill.topAnchor.constraint(equalTo: topAnchor, constant: 8),
            statusPill.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            statusPill.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -16),

            card.centerYAnchor.constraint(equalTo: centerYAnchor),
            card.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 24),
            card.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -24),
        ])
    }

    private func configureStatusPill() {
        statusDot.translatesAutoresizingMaskIntoConstraints = false
        statusDot.layer.cornerRadius = 4
        statusDot.widthAnchor.constraint(equalToConstant: 8).isActive = true
        statusDot.heightAnchor.constraint(equalToConstant: 8).isActive = true

        statusLabel.font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        statusLabel.textColor = .white

        variantLabel.font = UIFont.systemFont(ofSize: 11, weight: .bold)
        variantLabel.textColor = .white
        variantLabel.setContentHuggingPriority(.required, for: .horizontal)

        let spacer = UIView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        spacer.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let stack = UIStackView(arrangedSubviews: [statusDot, statusLabel, spacer, variantLabel])
        stack.axis = .horizontal
        stack.spacing = 8
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false

        statusPill.contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: statusPill.contentView.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: statusPill.contentView.bottomAnchor, constant: -8),
            stack.leadingAnchor.constraint(equalTo: statusPill.contentView.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: statusPill.contentView.trailingAnchor, constant: -12),
        ])

        statusPill.layer.cornerRadius = 17
        statusPill.clipsToBounds = true
    }

    private func configureCard() {
        card.layer.cornerRadius = 20
        card.clipsToBounds = true

        cardIndicator.translatesAutoresizingMaskIntoConstraints = false
        cardIndicator.layer.cornerRadius = 6
        cardIndicator.widthAnchor.constraint(equalToConstant: 12).isActive = true
        cardIndicator.heightAnchor.constraint(equalToConstant: 12).isActive = true

        spinner.translatesAutoresizingMaskIntoConstraints = false

        cardTitle.font = UIFont.systemFont(ofSize: 17, weight: .semibold)
        cardTitle.textColor = .white
        cardTitle.numberOfLines = 0

        cardMessage.font = UIFont.systemFont(ofSize: 14)
        cardMessage.textColor = UIColor(white: 1, alpha: 0.75)
        cardMessage.numberOfLines = 0

        configureButton(retryButton, title: "重试", filled: true)
        configureButton(settingsButton, title: "打开设置", filled: false)
        retryButton.addTarget(self, action: #selector(handleRetry), for: .touchUpInside)
        settingsButton.addTarget(self, action: #selector(handleOpenSettings), for: .touchUpInside)

        let titleRow = UIStackView(arrangedSubviews: [spinner, cardIndicator, cardTitle])
        titleRow.axis = .horizontal
        titleRow.spacing = 10
        titleRow.alignment = .center

        let buttonSpacer = UIView()
        buttonSpacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let buttonRow = UIStackView(arrangedSubviews: [retryButton, settingsButton, buttonSpacer])
        buttonRow.axis = .horizontal
        buttonRow.spacing = 10
        buttonRow.alignment = .center

        let content = UIStackView(arrangedSubviews: [titleRow, cardMessage, buttonRow])
        content.axis = .vertical
        content.spacing = 14
        content.alignment = .fill
        content.translatesAutoresizingMaskIntoConstraints = false

        card.contentView.addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: card.contentView.topAnchor, constant: 20),
            content.bottomAnchor.constraint(equalTo: card.contentView.bottomAnchor, constant: -20),
            content.leadingAnchor.constraint(equalTo: card.contentView.leadingAnchor, constant: 20),
            content.trailingAnchor.constraint(equalTo: card.contentView.trailingAnchor, constant: -20),
        ])

        card.isHidden = true
    }

    private func configureButton(_ button: UIButton, title: String, filled: Bool) {
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        button.layer.cornerRadius = 8
        // contentEdgeInsets 在 iOS 15 起标记废弃，但 iOS 10–14 只有它可用；这里保留以覆盖旧系统。
        button.contentEdgeInsets = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)
        if filled {
            button.backgroundColor = UIColor(white: 1, alpha: 0.92)
            button.setTitleColor(.black, for: .normal)
        } else {
            button.backgroundColor = UIColor(white: 1, alpha: 0.05)
            button.setTitleColor(.white, for: .normal)
            button.layer.borderWidth = 1
            button.layer.borderColor = UIColor(white: 1, alpha: 0.3).cgColor
        }
        button.setContentHuggingPriority(.required, for: .horizontal)
    }

    private static func makeSpinner() -> UIActivityIndicatorView {
        if #available(iOS 13.0, *) {
            let view = UIActivityIndicatorView(style: .medium)
            view.color = .white
            return view
        } else {
            return UIActivityIndicatorView(style: .white)
        }
    }

    @objc private func handleRetry() {
        onRetry?()
    }

    @objc private func handleOpenSettings() {
        onOpenSettings?()
    }
}

/// 供 UI 展示的纯文案模型，平台层不把 AVFoundation 类型带进视图。
struct CameraNoticeContent {
    let title: String
    let message: String
    let showsSettings: Bool
    let showsRetry: Bool
    let isProgress: Bool
    let indicatorColor: UIColor
}

extension CameraSessionPhase {

    var wotaStatusText: String {
        switch self {
        case .idle:
            return "相机待启动"
        case .requestingPermission:
            return "等待相机权限"
        case .configuring:
            return "正在启动相机"
        case .running:
            return "实时预览"
        case .interrupted:
            return "预览已中断"
        case .failed(.permissionDenied):
            return "需要相机权限"
        case .failed(.permissionRestricted):
            return "相机访问受限"
        case .failed(.cameraUnavailable):
            return "无可用相机"
        case .failed(.configurationFailed):
            return "相机启动失败"
        case .failed(.runtimeError):
            return "相机已停止"
        }
    }

    var wotaIndicatorColor: UIColor {
        switch self {
        case .idle:
            return UIColor(white: 0.65, alpha: 1)
        case .requestingPermission, .configuring:
            return UIColor(red: 0.98, green: 0.80, blue: 0.20, alpha: 1)
        case .running:
            return UIColor(red: 0.30, green: 0.80, blue: 0.40, alpha: 1)
        case .interrupted:
            return UIColor(red: 0.98, green: 0.58, blue: 0.20, alpha: 1)
        case .failed:
            return UIColor(red: 0.94, green: 0.30, blue: 0.30, alpha: 1)
        }
    }

    var wotaNoticeContent: CameraNoticeContent? {
        switch self {
        case .running:
            return nil

        case .idle:
            return CameraNoticeContent(
                title: "相机待启动",
                message: "应用进入前台后会自动启动 WOTA 艺录制预览。",
                showsSettings: false,
                showsRetry: true,
                isProgress: false,
                indicatorColor: wotaIndicatorColor
            )

        case .requestingPermission:
            return CameraNoticeContent(
                title: "正在请求相机权限",
                message: "系统弹出的说明会写明 WOTA 艺录制的使用用途。",
                showsSettings: false,
                showsRetry: false,
                isProgress: true,
                indicatorColor: wotaIndicatorColor
            )

        case .configuring:
            return CameraNoticeContent(
                title: "正在启动相机",
                message: "正在配置串行采集会话和预览连接。",
                showsSettings: false,
                showsRetry: false,
                isProgress: true,
                indicatorColor: wotaIndicatorColor
            )

        case .interrupted:
            return CameraNoticeContent(
                title: "预览被中断",
                message: "相机被来电、控制中心或其他应用占用。恢复后会自动继续预览。",
                showsSettings: false,
                showsRetry: true,
                isProgress: false,
                indicatorColor: wotaIndicatorColor
            )

        case .failed(let failure):
            switch failure {
            case .permissionDenied:
                return CameraNoticeContent(
                    title: "需要相机权限",
                    message: "WOTA 艺录制需要使用相机进行实时预览。请在“设置”中允许访问相机，然后返回重试。",
                    showsSettings: true,
                    showsRetry: true,
                    isProgress: false,
                    indicatorColor: wotaIndicatorColor
                )

            case .permissionRestricted:
                return CameraNoticeContent(
                    title: "相机访问受限",
                    message: "家长控制或管理配置限制了相机访问，WOTA 艺录制无法启用预览。",
                    showsSettings: false,
                    showsRetry: true,
                    isProgress: false,
                    indicatorColor: wotaIndicatorColor
                )

            case .cameraUnavailable:
                return CameraNoticeContent(
                    title: "未找到可用相机",
                    message: "当前运行环境没有可用的摄像头。请在真机上运行；模拟器需要提供相机输入。",
                    showsSettings: false,
                    showsRetry: true,
                    isProgress: false,
                    indicatorColor: wotaIndicatorColor
                )

            case .configurationFailed:
                return CameraNoticeContent(
                    title: "相机启动失败",
                    message: "无法建立预览会话。请确认没有其他应用正在占用相机，然后重试。",
                    showsSettings: false,
                    showsRetry: true,
                    isProgress: false,
                    indicatorColor: wotaIndicatorColor
                )

            case .runtimeError:
                return CameraNoticeContent(
                    title: "相机运行中断",
                    message: "采集会话发生错误，预览已停止。请重试；若反复失败，请检查系统相机是否可用。",
                    showsSettings: false,
                    showsRetry: true,
                    isProgress: false,
                    indicatorColor: wotaIndicatorColor
                )
            }
        }
    }
}
