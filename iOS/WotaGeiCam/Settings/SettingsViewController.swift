import UIKit

#if canImport(WotaCore)
import WotaCore
#endif

/// 设置页（原生 `UITableViewController`，系统控件，无 Material / 无 SwiftUI）。
///
/// **数据层是本任务主交付**：本页只是 `WotaCore` 设置存储/备份/更新接口的一个真实消费方，
/// 证明数据层可被 UIKit 直接驱动。分组与键对齐 `iOS/docs/mappings/settings.md`。
/// 页面**不覆盖全部 34 个入档键**（映射文档逐键可查）；此处给每个分组一个可用的代表性入口。
final class SettingsViewController: UITableViewController, UIDocumentPickerDelegate {

    private let store = WotaSettingsStore.standard()
    private let updater = UpdateChecker.shared

    /// 保活随建随走的控件 target（闭包目标-动作）。
    private final class ControlTarget: NSObject {
        private let handler: (UIControl) -> Void
        init(handler: @escaping (UIControl) -> Void) { self.handler = handler }
        @objc func fired(_ sender: UIControl) { handler(sender) }
    }

    private enum RowKind {
        case value(String)
        case disclosure
        case switchRow(get: () -> Bool, set: (Bool) -> Void)
        case stepper(get: () -> Double, min: Double, max: Double, step: Double, set: (Double) -> Void, format: (Double) -> String)
        case segmented(titles: [String], selected: () -> Int, set: (Int) -> Void)
    }

    private struct SettingRow {
        let title: String
        let kind: RowKind
        let onSelect: (() -> Void)?
    }

    private struct Section {
        let title: String?
        let rows: [SettingRow]
    }

    private var sections: [Section] = []
    private var targets: [ControlTarget] = []

    /// 文档导入在后台串行执行：安全作用域 + 最多 4 MiB 的读取可能触及 iCloud/第三方 provider，
    /// 不能阻塞主线程；UserDefaults 本身线程安全，结果统一回主线程更新 UI。
    private let importQueue = DispatchQueue(label: "com.wotagei.cam.settings.import", qos: .userInitiated)

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "设置"
        tableView.rowHeight = 46
        tableView.sectionFooterHeight = 12
        rebuildSections()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        targets.removeAll()
        tableView.reloadData()
    }

    // MARK: - 分组构建

    private func rebuildSections() {
        sections = [
            Section(title: "版本与更新", rows: updateRows()),
            Section(title: "开机初始值", rows: cameraDefaultRows()),
            Section(title: "界面方向", rows: orientationRows()),
            Section(title: "动画风格", rows: motionRows()),
            Section(title: "取景辅助", rows: aidRows()),
            Section(title: "取景器常驻功能", rows: hudItemRows()),
            Section(title: "滑杆吸附", rows: sliderRows()),
            Section(title: "文本高度", rows: textScaleRows()),
            Section(title: "关于", rows: aboutRows()),
            Section(title: "备份与恢复", rows: backupRows()),
        ]
    }

    private func updateRows() -> [SettingRow] {
        let rows: [SettingRow] = [
            SettingRow(title: "当前版本", kind: .value("\(updater.localVersionName) (\(updater.flavor))"), onSelect: nil),
            SettingRow(title: "检查更新", kind: .disclosure, onSelect: { [weak self] in self?.performUpdateCheck() }),
            SettingRow(title: "打开发布页", kind: .disclosure, onSelect: { [weak self] in self?.updater.openReleasePage() }),
        ]
        return rows
    }

    private func cameraDefaultRows() -> [SettingRow] {
        let store = self.store
        return [
            SettingRow(title: "帧率 (fps)", kind: .stepper(
                get: { Double(store.defaultFps()) }, min: 24, max: 240, step: 1,
                set: { store.setDefaultFps(Int($0)); store.flush() },
                format: { String(Int($0)) }
            ), onSelect: nil),
            SettingRow(title: "码率 (Mbps)", kind: .stepper(
                get: { Double(store.defaultBitrate()) / 1_000_000 }, min: 5, max: 50, step: 5,
                set: { store.setDefaultBitrate(Int($0) * 1_000_000); store.flush() },
                format: { String(Int($0)) }
            ), onSelect: nil),
            SettingRow(title: "感光度 ISO", kind: .stepper(
                get: { Double(store.defaultIso()) }, min: 50, max: 6400, step: 50,
                set: { store.setDefaultIso(Int($0)); store.flush() },
                format: { String(Int($0)) }
            ), onSelect: nil),
            SettingRow(title: "白平衡色温 (K)", kind: .stepper(
                get: { Double(store.defaultWbKelvin()) }, min: 2000, max: 10000, step: 100,
                set: { store.setDefaultWbKelvin(Int($0)); store.flush() },
                format: { String(Int($0)) }
            ), onSelect: nil),
            SettingRow(title: "白平衡色调", kind: .stepper(
                get: { Double(store.defaultWbTint()) }, min: -50, max: 50, step: 1,
                set: { store.setDefaultWbTint(Int($0)); store.flush() },
                format: { String(Int($0)) }
            ), onSelect: nil),
            SettingRow(title: "变焦", kind: .stepper(
                get: { Double(store.defaultZoom()) }, min: 1, max: 8, step: 0.1,
                set: { store.setDefaultZoom(Float($0)); store.flush() },
                format: { String(format: "%.1f", $0) }
            ), onSelect: nil),
            SettingRow(title: "AE 模式", kind: .segmented(
                titles: ["AUTO", "MANUAL"],
                selected: { store.defaultAeMode() == .manual ? 1 : 0 },
                set: { index in
                    store.setDefaultAeMode(index == 1 ? .manual : .auto)
                    store.flush()
                }
            ), onSelect: nil),
            SettingRow(title: "闪光", kind: .segmented(
                titles: ["OFF", "ON", "AUTO", "TORCH"],
                selected: { Flash.allCasesForUI.firstIndex(of: store.defaultFlash()) ?? 0 },
                set: { index in
                    let value = Flash.allCasesForUI[min(max(index, 0), Flash.allCasesForUI.count - 1)]
                    store.setDefaultFlash(value)
                    store.flush()
                }
            ), onSelect: nil),
            SettingRow(title: "降噪（画面）", kind: .segmented(
                titles: VideoDenoise.allCasesForUI.map { $0.rawValue },
                selected: { VideoDenoise.allCasesForUI.firstIndex(of: store.denoiseVideo()) ?? 0 },
                set: { index in
                    let value = VideoDenoise.allCasesForUI[min(max(index, 0), VideoDenoise.allCasesForUI.count - 1)]
                    store.setDenoiseVideo(value)
                    store.flush()
                }
            ), onSelect: nil),
        ]
    }

    private func orientationRows() -> [SettingRow] {
        let store = self.store
        return [
            SettingRow(title: "默认方向", kind: .segmented(
                titles: ["横屏", "竖屏"],
                selected: { store.uiOrientation() == .portrait ? 1 : 0 },
                set: { index in
                    store.setUIOrientation(index == 1 ? .portrait : .landscape)
                    store.flush()
                }
            ), onSelect: nil),
        ]
    }

    private func motionRows() -> [SettingRow] {
        let store = self.store
        return [
            SettingRow(title: "动画风格", kind: .segmented(
                titles: ["PLAIN", "FLUENT", "LIQUID"],
                selected: { MotionMode.allCasesForUI.firstIndex(of: store.motionMode()) ?? 1 },
                set: { index in
                    let value = MotionMode.allCasesForUI[min(max(index, 0), MotionMode.allCasesForUI.count - 1)]
                    store.setMotionMode(value)
                    store.flush()
                }
            ), onSelect: nil),
        ]
    }

    private func aidRows() -> [SettingRow] {
        let store = self.store
        return [
            SettingRow(title: "水平仪", kind: .switchRow(
                get: { store.levelEnabled() },
                set: { store.setLevelEnabled($0); store.flush() }
            ), onSelect: nil),
            SettingRow(title: "水平仪蜂鸣", kind: .switchRow(
                get: { store.levelBuzzEnabled() },
                set: { store.setLevelBuzzEnabled($0); store.flush() }
            ), onSelect: nil),
            SettingRow(title: "曝光纠偏（自动）", kind: .switchRow(
                get: { store.correctionAuto() },
                set: { store.setCorrectionAuto($0); store.flush() }
            ), onSelect: nil),
            SettingRow(title: "降噪（音频）", kind: .segmented(
                titles: AudioDenoise.allCasesForUI.map { $0.rawValue },
                selected: { AudioDenoise.allCasesForUI.firstIndex(of: store.denoiseAudio()) ?? 0 },
                set: { index in
                    let value = AudioDenoise.allCasesForUI[min(max(index, 0), AudioDenoise.allCasesForUI.count - 1)]
                    store.setDenoiseAudio(value)
                    store.flush()
                }
            ), onSelect: nil),
            SettingRow(title: "上屏比例", kind: .segmented(
                titles: DisplayScale.all.map { $0.rawValue },
                selected: { DisplayScale.all.firstIndex(of: store.displayScale()) ?? 0 },
                set: { index in
                    let value = DisplayScale.all[min(max(index, 0), DisplayScale.all.count - 1)]
                    store.setDisplayScale(value)
                    store.flush()
                }
            ), onSelect: nil),
        ]
    }

    private func hudItemRows() -> [SettingRow] {
        let store = self.store
        return HudItem.allCases.map { item in
            let label = SettingsViewController.hudItemLabel(item)
            return SettingRow(title: label, kind: .switchRow(
                get: { (store.hudItemsMask() & item.rawValue) != 0 },
                set: { on in
                    var mask = store.hudItemsMask()
                    if on { mask |= item.rawValue } else { mask &= ~item.rawValue }
                    store.setHudItemsMask(mask)
                    store.flush()
                }
            ), onSelect: nil)
        }
    }

    private func sliderRows() -> [SettingRow] {
        let store = self.store
        return SliderKey.allCases.map { key in
            return SettingRow(title: SettingsViewController.sliderLabel(key), kind: .switchRow(
                get: { store.continuousSliders().contains(key) },
                set: { on in
                    var keys = store.continuousSliders()
                    if on { keys.insert(key) } else { keys.remove(key) }
                    store.setContinuousSliders(keys)
                    store.flush()
                }
            ), onSelect: nil)
        }
    }

    private func textScaleRows() -> [SettingRow] {
        let store = self.store
        return [
            SettingRow(title: "取景页文字 (%)", kind: .stepper(
                get: { Double(store.textScaleCamera()) }, min: 80, max: 120, step: 5,
                set: { store.setTextScaleCamera(Int($0)); store.flush() },
                format: { String(Int($0)) }
            ), onSelect: nil),
            SettingRow(title: "设置页文字 (%)", kind: .stepper(
                get: { Double(store.textScaleSettings()) }, min: 80, max: 120, step: 5,
                set: { store.setTextScaleSettings(Int($0)); store.flush() },
                format: { String(Int($0)) }
            ), onSelect: nil),
            SettingRow(title: "弹窗文字 (%)", kind: .stepper(
                get: { Double(store.textScaleDialog()) }, min: 80, max: 120, step: 5,
                set: { store.setTextScaleDialog(Int($0)); store.flush() },
                format: { String(Int($0)) }
            ), onSelect: nil),
        ]
    }

    private func aboutRows() -> [SettingRow] {
        let store = self.store
        return [
            SettingRow(title: "诊断日志", kind: .switchRow(
                get: { store.diagEnabled() },
                set: { store.setDiagEnabled($0); store.flush() }
            ), onSelect: nil),
            SettingRow(title: "详细诊断", kind: .switchRow(
                get: { store.diagVerbose() },
                set: { store.setDiagVerbose($0); store.flush() }
            ), onSelect: nil),
        ]
    }

    private func backupRows() -> [SettingRow] {
        return [
            SettingRow(title: "导出备份快照", kind: .disclosure, onSelect: { [weak self] in self?.exportBackup() }),
            SettingRow(title: "导入备份快照", kind: .disclosure, onSelect: { [weak self] in self?.importBackup() }),
        ]
    }

    // MARK: - 表格数据源

    override func numberOfSections(in tableView: UITableView) -> Int { return sections.count }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return sections[section].rows.count
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return sections[section].title
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let row = sections[indexPath.section].rows[indexPath.row]
        let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
        cell.textLabel?.text = row.title
        cell.accessoryView = nil
        cell.accessoryType = .none
        cell.selectionStyle = .none

        switch row.kind {
        case .value(let text):
            cell.detailTextLabel?.text = text

        case .disclosure:
            cell.accessoryType = .disclosureIndicator
            cell.selectionStyle = .default

        case .switchRow(let get, let set):
            let toggle = UISwitch()
            toggle.isOn = get()
            let target = ControlTarget { sender in
                guard let switchView = sender as? UISwitch else { return }
                set(switchView.isOn)
            }
            toggle.addTarget(target, action: #selector(ControlTarget.fired(_:)), for: .valueChanged)
            targets.append(target)
            cell.accessoryView = toggle

        case .stepper(let get, let minimum, let maximum, let step, let set, let format):
            let stepper = UIStepper()
            stepper.minimumValue = minimum
            stepper.maximumValue = maximum
            stepper.stepValue = step
            stepper.value = min(max(get(), minimum), maximum)
            cell.detailTextLabel?.text = format(stepper.value)
            let target = ControlTarget { [weak cell] sender in
                guard let stepperView = sender as? UIStepper else { return }
                set(stepperView.value)
                cell?.detailTextLabel?.text = format(stepperView.value)
            }
            stepper.addTarget(target, action: #selector(ControlTarget.fired(_:)), for: .valueChanged)
            targets.append(target)
            cell.accessoryView = stepper

        case .segmented(let titles, let selected, let set):
            let control = UISegmentedControl(items: titles)
            control.selectedSegmentIndex = min(max(selected(), 0), titles.count - 1)
            let target = ControlTarget { sender in
                guard let seg = sender as? UISegmentedControl else { return }
                set(seg.selectedSegmentIndex)
            }
            control.addTarget(target, action: #selector(ControlTarget.fired(_:)), for: .valueChanged)
            targets.append(target)
            cell.accessoryView = control
        }

        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let row = sections[indexPath.section].rows[indexPath.row]
        tableView.deselectRow(at: indexPath, animated: true)
        row.onSelect?()
    }

    // MARK: - 更新

    private func performUpdateCheck() {
        let alert = UIAlertController(title: "检查更新", message: "正在探测最新版本…", preferredStyle: .alert)
        present(alert, animated: true, completion: nil)
        updater.check { [weak self] result in
            alert.dismiss(animated: true) {
                self?.presentUpdateResult(result)
            }
        }
    }

    private func presentUpdateResult(_ result: WotaProbeResult) {
        switch result {
        case .upToDate:
            presentMessage(title: "已是最新", message: "当前版本 \(updater.localVersionName) 已是最新。")
        case .cancelled:
            break
        case .failed:
            presentMessage(title: "检查失败", message: "无法连接更新服务。可在发布页手动查看。",
                           actionTitle: "打开\u{9875}\u{9762}") { [weak self] in self?.updater.openReleasePage() }
        case .updateAvailable(let tag, _, _, _, _, let notes):
            var message = "发现新版本 \(tag)。\n\n本 App 不会自动下载或安装：iOS 无 APK 式覆盖安装，unsigned IPA 须由外部工具重签后安装。"
            if let display = WotaReleaseNotes.forDisplay(notes), !display.isEmpty {
                message += "\n\n" + display
            }
            presentMessage(title: "有新版本", message: message,
                           actionTitle: "打开\u{9875}\u{9762}") { [weak self] in self?.updater.openTagPage(tag: tag) }
        }
    }

    private func presentMessage(title: String, message: String, actionTitle: String? = nil, action: (() -> Void)? = nil) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .cancel, handler: nil))
        if let actionTitle = actionTitle {
            alert.addAction(UIAlertAction(title: actionTitle, style: .default, handler: { _ in action?() }))
        }
        present(alert, animated: true, completion: nil)
    }

    // MARK: - 备份

    private func exportBackup() {
        guard let url = UserBackupDocumentIO.exportToFile(store: store) else {
            presentMessage(title: "导出失败", message: "生成备份快照失败。")
            return
        }
        let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let popover = activity.popoverPresentationController {
            popover.sourceView = view
            popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
        }
        present(activity, animated: true, completion: nil)
    }

    private func importBackup() {
        let picker = UIDocumentPickerViewController(documentTypes: ["public.data"], in: .open)
        picker.delegate = self
        present(picker, animated: true, completion: nil)
    }

    // MARK: - UIDocumentPickerDelegate

    @available(iOS 11.0, *)
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        applyImport(from: url)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentAt url: URL) {
        applyImport(from: url)
    }

    private func applyImport(from url: URL) {
        let store = self.store
        importQueue.async { [weak self] in
            let count = UserBackupDocumentIO.importFromFile(url: url, into: store)
            DispatchQueue.main.async {
                guard let self = self else { return }
                if let count = count {
                    self.targets.removeAll()
                    self.tableView.reloadData()
                    self.presentMessage(title: "导入完成", message: "已补充 \(count) 项设置（不覆盖已有值）。")
                } else {
                    self.presentMessage(title: "导入失败",
                                       message: "文件不是有效的备份快照、超过 4 MiB 上限，或格式/版本不受支持。")
                }
            }
        }
    }

    // MARK: - 文案

    private static func hudItemLabel(_ item: HudItem) -> String {
        switch item {
        case .shutter: return "读数：快门"
        case .fps: return "读数：帧率"
        case .bitrate: return "读数：码率"
        case .iso: return "读数：感光度"
        case .ev: return "读数：曝光补偿"
        case .wb: return "读数：白平衡"
        case .zoom: return "读数：变焦"
        }
    }

    private static func sliderLabel(_ key: SliderKey) -> String {
        switch key {
        case .shutter: return "滑杆连续：快门"
        case .iso: return "滑杆连续：感光度"
        case .kelvin: return "滑杆连续：色温"
        case .tint: return "滑杆连续：色调"
        case .zoom: return "滑杆连续：变焦"
        case .diopter: return "滑杆连续：屈光度"
        case .peaking: return "滑杆连续：峰值"
        }
    }
}

// MARK: - UI 用到的枚举全序（追加，不改 WotaCore 枚举本体）

extension Flash {
    static let allCasesForUI: [Flash] = [.off, .on, .auto, .torch]
}

extension VideoDenoise {
    static let allCasesForUI: [VideoDenoise] = [.off, .halFast, .halHighQuality, .gpuBilateral]
}

extension AudioDenoise {
    static let allCasesForUI: [AudioDenoise] = [.off, .system]
}

extension MotionMode {
    static let allCasesForUI: [MotionMode] = [.plain, .fluent, .liquid]
}
