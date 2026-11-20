import UIKit

/// 离线补弧页：任务结束前保留进度和取消入口，成片可从此处回放。
final class ArcRepairViewController: UIViewController {
    private let sourceURL: URL
    private let sourceFileName: String
    private let runner = ArcRepairRunner()
    private let closeButton = UIButton(type: .system)
    private let targetPicker = UISegmentedControl(items: ["24 fps", "25 fps"])
    private let backendPicker = UISegmentedControl(items: ["自动", "CPU"])
    private let deadbandSlider = UISlider()
    private let deadbandLabel = UILabel()
    private let progress = UIProgressView(progressViewStyle: .default)
    private let message = UILabel()
    private let startButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private let playButton = UIButton(type: .system)
    private var working = false
    private var cancellationRequested = false
    private var outputURL: URL?

    init(url: URL, fileName: String) {
        sourceURL = url
        sourceFileName = fileName
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        if #available(iOS 13.0, *) { view.backgroundColor = .systemBackground }
        else { view.backgroundColor = .white }
        configureContent()
        runner.onProgress = { [weak self] update in
            guard let self = self, self.working, !self.cancellationRequested else { return }
            self.progress.setProgress(update.fraction, animated: true)
            self.message.text = update.message
            self.cancelButton.isEnabled = update.canCancel
        }
        runner.onFinished = { [weak self] report in self?.finish(report) }
    }

    private func configureContent() {
        let scroll = UIScrollView()
        scroll.alwaysBounceVertical = true
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        let content = UIStackView()
        content.axis = .vertical
        content.spacing = 14
        content.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(content)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: topLayoutGuide.bottomAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.topAnchor.constraint(equalTo: scroll.topAnchor, constant: 16),
            content.bottomAnchor.constraint(equalTo: scroll.bottomAnchor, constant: -20),
            content.leadingAnchor.constraint(equalTo: scroll.leadingAnchor, constant: 20),
            content.trailingAnchor.constraint(equalTo: scroll.trailingAnchor, constant: -20),
            content.widthAnchor.constraint(equalTo: scroll.widthAnchor, constant: -40)
        ])

        let title = label("光弧修复", style: .headline)
        configure(clear: closeButton, title: "返回", action: #selector(closeTapped))
        let header = UIStackView(arrangedSubviews: [title, closeButton])
        header.alignment = .center
        closeButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        let source = label(sourceFileName, style: .footnote)
        source.lineBreakMode = .byTruncatingMiddle
        source.numberOfLines = 2
        targetPicker.selectedSegmentIndex = 0
        targetPicker.accessibilityLabel = "目标帧率"
        backendPicker.selectedSegmentIndex = 0
        backendPicker.accessibilityLabel = "图像后端"
        deadbandSlider.minimumValue = Float(ArcMergeClamp.minValue)
        deadbandSlider.maximumValue = Float(ArcMergeClamp.maxValue)
        deadbandSlider.value = Float(ArcRepairConfiguration().deadband)
        deadbandSlider.accessibilityLabel = "差分死区"
        deadbandSlider.addTarget(self, action: #selector(deadbandChanged), for: .valueChanged)
        deadbandLabel.font = .preferredFont(forTextStyle: .subheadline)
        deadbandChanged()
        configure(clear: startButton, title: "开始补弧", action: #selector(startTapped))
        configure(clear: cancelButton, title: "取消补弧", action: #selector(cancelTapped))
        configure(clear: playButton, title: "回放补弧成片", action: #selector(playTapped))
        playButton.isHidden = true
        let actions = UIStackView(arrangedSubviews: [startButton, cancelButton])
        actions.distribution = .fillEqually
        message.font = .preferredFont(forTextStyle: .footnote)
        message.adjustsFontForContentSizeCategory = true
        message.numberOfLines = 0
        message.text = "适用于帧率高于目标档的视频。抽帧时将被抽画面的光弧并入相邻帧，另存到片库。"
        let controls: [UIView] = [header, source, label("目标帧率", style: .subheadline), targetPicker,
                                  deadbandLabel, deadbandSlider, label("图像后端", style: .subheadline),
                                  backendPicker, progress, message, actions, playButton]
        controls.forEach { content.addArrangedSubview($0) }
        setWorking(false)
    }

    private func label(_ text: String, style: UIFont.TextStyle) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: style)
        label.adjustsFontForContentSizeCategory = true
        return label
    }

    private func configure(clear button: UIButton, title: String, action: Selector) {
        button.setTitle(title, for: .normal)
        button.addTarget(self, action: action, for: .touchUpInside)
        let height = button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44)
        height.priority = .defaultHigh
        height.isActive = true
    }

    private func setWorking(_ value: Bool) {
        working = value
        closeButton.isEnabled = !value
        targetPicker.isEnabled = !value
        backendPicker.isEnabled = !value
        deadbandSlider.isEnabled = !value
        startButton.isEnabled = !value
        cancelButton.isHidden = !value
        cancelButton.isEnabled = value
        progress.isHidden = !value
    }

    @objc private func deadbandChanged() {
        let value = Int(deadbandSlider.value.rounded())
        deadbandLabel.text = "差分死区 \(value)"
        deadbandSlider.accessibilityValue = String(value)
    }

    @objc private func startTapped() {
        guard !working else { return }
        let fps = targetPicker.selectedSegmentIndex == 1 ? 25 : 24
        let backend: ArcRepairBackendPreference = backendPicker.selectedSegmentIndex == 1 ? .cpu : .automatic
        let configuration = ArcRepairConfiguration(targetFps: fps,
                                                  deadband: Int(deadbandSlider.value.rounded()),
                                                  backendPreference: backend)
        outputURL = nil
        playButton.isHidden = true
        cancellationRequested = false
        progress.progress = 0
        message.text = "正在读取源视频…"
        setWorking(true)
        runner.start(sourceURL: sourceURL, sourceFileName: sourceFileName, configuration: configuration)
    }

    @objc private func cancelTapped() {
        guard working, cancelButton.isEnabled else { return }
        cancellationRequested = true
        cancelButton.isEnabled = false
        message.text = "正在取消并清理暂存文件…"
        runner.cancel()
    }

    @objc private func closeTapped() {
        guard !working else { return }
        dismiss(animated: true)
    }

    @objc private func playTapped() {
        guard let url = outputURL, presentedViewController == nil else { return }
        let player = PlayerViewController(url: url, fileName: url.lastPathComponent)
        player.allowsArcRepair = false
        player.modalPresentationStyle = .fullScreen
        present(player, animated: true)
    }

    private func finish(_ report: ArcRepairReport) {
        setWorking(false)
        message.text = ([report.message] + report.notes).joined(separator: "\n")
        outputURL = report.isPublished ? report.outputURL : nil
        playButton.isHidden = outputURL == nil
    }
}
