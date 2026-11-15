import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

/// 最小 WOTA 录制参数面板（UIKit，iOS 10 基线）。
///
/// 只展示并调整**基础**相机参数：当前格式/帧率（只读）、曝光目标偏置、手电筒。
/// 分辨率/帧率切换、码率等录制档位协商属于后续录制任务，这里不做，
/// 以免在缺少设备能力判断的情况下写入未经降级的参数。
final class CameraParameterPanelView: UIView {

    var onTorchChanged: ((Bool) -> Void)?
    var onExposureChanged: ((Float) -> Void)?
    var onRefresh: (() -> Void)?

    private let headerButton = UIButton(type: .system)
    private let refreshButton = UIButton(type: .system)
    private let summaryLabel = UILabel()

    private let contentStack = UIStackView()
    private let deviceLabel = UILabel()
    private let formatLabel = UILabel()
    private let exposureTitleLabel = UILabel()
    private let exposureValueLabel = UILabel()
    private let exposureSlider = UISlider()
    private let torchTitleLabel = UILabel()
    private let torchSwitch = UISwitch()
    private let notesLabel = UILabel()

    private var isExpanded = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    // MARK: - 对外接口

    func update(with snapshot: CameraParameterSnapshot) {
        deviceLabel.text = "设备：\(snapshot.deviceName)（\(snapshot.deviceModel)）"
        formatLabel.text = "当前格式：\(snapshot.resolution)，\(snapshot.frameRate)"
        summaryLabel.text = "\(snapshot.resolution) · \(snapshot.frameRate)"

        exposureSlider.minimumValue = snapshot.exposure.minimum
        exposureSlider.maximumValue = snapshot.exposure.isAdjustable
            ? snapshot.exposure.maximum
            : snapshot.exposure.minimum + 1
        exposureSlider.value = snapshot.exposure.clamp(snapshot.exposureValue)
        exposureSlider.isEnabled = snapshot.exposure.isAdjustable
        exposureValueLabel.text = snapshot.exposure.isAdjustable
            ? String(format: "曝光补偿 %+.1f EV", snapshot.exposureValue)
            : "曝光补偿：设备不支持"

        torchSwitch.isEnabled = snapshot.torchAvailable
        torchSwitch.setOn(snapshot.torchEnabled, animated: false)
        torchTitleLabel.text = snapshot.torchAvailable ? "手电筒" : "手电筒（不可用）"

        notesLabel.text = snapshot.notes.joined(separator: "\n")
        notesLabel.isHidden = snapshot.notes.isEmpty
    }

    // MARK: - 构建

    private func configure() {
        backgroundColor = UIColor(white: 0, alpha: 0.55)
        layer.cornerRadius = 20
        clipsToBounds = true

        headerButton.setTitle("参数面板", for: .normal)
        headerButton.titleLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        headerButton.setTitleColor(.white, for: .normal)
        headerButton.contentHorizontalAlignment = .left
        headerButton.addTarget(self, action: #selector(handleToggle), for: .touchUpInside)

        refreshButton.setTitle("刷新", for: .normal)
        refreshButton.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)
        refreshButton.setTitleColor(UIColor(white: 1, alpha: 0.85), for: .normal)
        refreshButton.addTarget(self, action: #selector(handleRefresh), for: .touchUpInside)

        summaryLabel.font = UIFont.systemFont(ofSize: 12)
        summaryLabel.textColor = UIColor(white: 1, alpha: 0.65)

        configureContentStack()

        let headerRow = UIStackView(arrangedSubviews: [headerButton, refreshButton])
        headerRow.axis = .horizontal
        headerRow.alignment = .center
        headerRow.spacing = 8
        headerButton.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let rootStack = UIStackView(arrangedSubviews: [headerRow, summaryLabel, contentStack])
        rootStack.axis = .vertical
        rootStack.spacing = 10
        rootStack.alignment = .fill
        rootStack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(rootStack)

        NSLayoutConstraint.activate([
            rootStack.topAnchor.constraint(equalTo: topAnchor, constant: 14),
            rootStack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -14),
            rootStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            rootStack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
        ])

        updateToggleTitle()
    }

    private func configureContentStack() {
        deviceLabel.font = UIFont.systemFont(ofSize: 13)
        deviceLabel.textColor = .white
        deviceLabel.numberOfLines = 0

        formatLabel.font = UIFont.systemFont(ofSize: 13)
        formatLabel.textColor = .white
        formatLabel.numberOfLines = 0

        exposureTitleLabel.font = UIFont.systemFont(ofSize: 13)
        exposureTitleLabel.textColor = .white
        exposureValueLabel.font = UIFont.systemFont(ofSize: 12)
        exposureValueLabel.textColor = UIColor(white: 1, alpha: 0.65)
        exposureValueLabel.textAlignment = .right

        exposureSlider.addTarget(self, action: #selector(handleExposureChanged), for: .valueChanged)
        exposureSlider.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let exposureTitleRow = UIStackView(arrangedSubviews: [exposureTitleLabel, exposureValueLabel])
        exposureTitleRow.axis = .horizontal
        exposureTitleRow.alignment = .center
        exposureTitleRow.spacing = 8

        torchTitleLabel.font = UIFont.systemFont(ofSize: 13)
        torchTitleLabel.textColor = .white
        torchSwitch.addTarget(self, action: #selector(handleTorchChanged), for: .valueChanged)
        torchSwitch.setContentHuggingPriority(.required, for: .horizontal)

        let torchRow = UIStackView(arrangedSubviews: [torchTitleLabel, torchSwitch])
        torchRow.axis = .horizontal
        torchRow.alignment = .center
        torchRow.spacing = 8

        notesLabel.font = UIFont.systemFont(ofSize: 12)
        notesLabel.textColor = UIColor(white: 1, alpha: 0.7)
        notesLabel.numberOfLines = 0

        contentStack.axis = .vertical
        contentStack.spacing = 10
        contentStack.alignment = .fill
        [
            deviceLabel,
            formatLabel,
            exposureTitleRow,
            exposureSlider,
            torchRow,
            notesLabel,
        ].forEach { contentStack.addArrangedSubview($0) }
    }

    private func updateToggleTitle() {
        headerButton.setTitle(isExpanded ? "参数面板 ▾" : "参数面板 ▸", for: .normal)
        contentStack.isHidden = !isExpanded
    }

    // MARK: - 交互

    @objc private func handleToggle() {
        isExpanded = !isExpanded
        updateToggleTitle()
    }

    @objc private func handleRefresh() {
        onRefresh?()
    }

    @objc private func handleExposureChanged() {
        exposureValueLabel.text = String(format: "曝光补偿 %+.1f EV", exposureSlider.value)
        onExposureChanged?(exposureSlider.value)
    }

    @objc private func handleTorchChanged() {
        onTorchChanged?(torchSwitch.isOn)
    }
}
