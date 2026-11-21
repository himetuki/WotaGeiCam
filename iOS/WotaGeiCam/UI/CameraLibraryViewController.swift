import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

/// 媒体库页（UIKit，iOS 10 基线，对齐 Android 相册库的 UIKit 重写）。
///
/// 数据源唯一入口为 [MediaLibrary.shared]（App 自有目录 + 自有 JSON 索引，不触碰系统相册）：
/// - 列表：`list(filter:)`，按 [MediaClipFilter] 呈现全部 / 收藏 / 回收站 / 自定义标签页签；
/// - 收藏：`setLike(mediaId:liked:)`，行内 ♥ 切换；
/// - 回收站：`moveToTrash(mediaIds:)`，常规页签隐藏、文件仍原地；
/// - 还原：`restoreFromTrash(mediaIds:)`，删回收站行、不覆盖任何文件；
/// - 永久删除：`deleteForever(mediaIds:)`，**先确认**再删自有字节 + sidecar，逐条计成功/失败；
/// - 播放：`PlayerViewController.load(url:fileName:)`，模态打开。
///
/// 取舍（见 `docs/modules/G1-library.md`）：本版**不生成缩略图**，行内只以文件名 / 时长 / 大小 /
/// 日期 / 标签文字呈现，避免全盘 Photos 与后台并发探测；系列折叠暂不启用，行内仅以角标标注段数，
/// 组级动作暂按所选条目直发（系列扇出 `MediaSeriesGrouper.expandSeriesTargets` 留作后续）。
///
/// 不使用 SwiftUI / Combine / @MainActor / async-await；所有 MediaLibrary 回调已回主队列。
final class CameraLibraryViewController: UIViewController {

    // MARK: - 依赖与状态

    private var currentFilter: MediaClipFilter = .all
    private var libraryItems: [MediaItem] = []
    private var items: [MediaItem] = []
    /// 编辑态按媒体 ID 记点选顺序，列表重排时不会改变对比片的位置。
    private var selectedIDs: [Int64] = []
    private var isLoading = false
    /// 自定义标签全集（来自全部页签的非回收条目），用于动态生成标签页签。
    private var tagUniverse: [String] = []
    /// 页签按钮 ↔ 过滤条件（按钮 `tag` 即数组下标）。
    private var tabFilters: [MediaClipFilter] = []

    // MARK: - 视图

    private let tabBarScrollView = UIScrollView()
    private let tabStack = UIStackView()

    private let toolbarView = UIView()
    private let closeButton = UIButton(type: .system)
    private let editButton = UIButton(type: .system)
    private let toolbarTitleLabel = UILabel()

    private let tableView = UITableView(frame: .zero, style: .plain)
    private var requestGeneration = 0

    private let stateView = UIView()
    private let stateLabel = UILabel()
    private let loadingIndicator = UIActivityIndicatorView(activityIndicatorStyle: .gray)

    private let actionBar = UIView()
    private var actionBarHeightConstraint: NSLayoutConstraint?
    private let actionStack = UIStackView()

    // MARK: - 初始化

    /// 无参构造契约：主代理从取景页入口 `present` 本控制器。
    init() {
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white
        buildHierarchy()
        buildTabs()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reloadCurrentTab()
    }

    @objc private func closeLibrary() {
        dismiss(animated: true)
    }

    // MARK: - 视图层级

    private func buildHierarchy() {
        // 页签栏（可横向滚动，标签多时不挤压）
        tabBarScrollView.translatesAutoresizingMaskIntoConstraints = false
        tabBarScrollView.showsHorizontalScrollIndicator = false
        tabBarScrollView.showsVerticalScrollIndicator = false
        view.addSubview(tabBarScrollView)

        tabStack.translatesAutoresizingMaskIntoConstraints = false
        tabStack.axis = .horizontal
        tabStack.spacing = 8
        tabStack.alignment = .center
        tabBarScrollView.addSubview(tabStack)

        NSLayoutConstraint.activate([
            tabBarScrollView.topAnchor.constraint(equalTo: topLayoutGuide.bottomAnchor),
            tabBarScrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tabBarScrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tabBarScrollView.heightAnchor.constraint(equalToConstant: 44),
            tabStack.topAnchor.constraint(equalTo: tabBarScrollView.topAnchor, constant: 6),
            tabStack.leadingAnchor.constraint(equalTo: tabBarScrollView.leadingAnchor, constant: 8),
            tabStack.trailingAnchor.constraint(equalTo: tabBarScrollView.trailingAnchor, constant: -8),
            tabStack.bottomAnchor.constraint(equalTo: tabBarScrollView.bottomAnchor, constant: -6)
        ])

        // 工具栏：编辑 / 完成 + 当前页签计数
        toolbarView.translatesAutoresizingMaskIntoConstraints = false
        toolbarView.backgroundColor = UIColor(white: 0.94, alpha: 1)
        view.addSubview(toolbarView)

        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.setTitle("返回", for: .normal)
        closeButton.addTarget(self, action: #selector(closeLibrary), for: .touchUpInside)

        editButton.translatesAutoresizingMaskIntoConstraints = false
        editButton.setTitle("编辑", for: .normal)
        editButton.titleLabel?.font = .systemFont(ofSize: 15)
        editButton.addTarget(self, action: #selector(editTapped), for: .touchUpInside)

        toolbarTitleLabel.translatesAutoresizingMaskIntoConstraints = false
        toolbarTitleLabel.textAlignment = .center
        toolbarTitleLabel.font = .systemFont(ofSize: 15, weight: .medium)
        toolbarTitleLabel.textColor = .black

        toolbarView.addSubview(closeButton)
        toolbarView.addSubview(editButton)
        toolbarView.addSubview(toolbarTitleLabel)
        toolbarTitleLabel.lineBreakMode = .byTruncatingTail
        toolbarTitleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        NSLayoutConstraint.activate([
            toolbarView.topAnchor.constraint(equalTo: tabBarScrollView.bottomAnchor),
            toolbarView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            toolbarView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            toolbarView.heightAnchor.constraint(equalToConstant: 44),
            closeButton.leadingAnchor.constraint(equalTo: toolbarView.leadingAnchor, constant: 12),
            closeButton.topAnchor.constraint(equalTo: toolbarView.topAnchor),
            closeButton.bottomAnchor.constraint(equalTo: toolbarView.bottomAnchor),
            closeButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 44),
            editButton.trailingAnchor.constraint(equalTo: toolbarView.trailingAnchor, constant: -12),
            editButton.topAnchor.constraint(equalTo: toolbarView.topAnchor),
            editButton.bottomAnchor.constraint(equalTo: toolbarView.bottomAnchor),
            editButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 44),
            toolbarTitleLabel.leadingAnchor.constraint(equalTo: closeButton.trailingAnchor, constant: 8),
            toolbarTitleLabel.trailingAnchor.constraint(equalTo: editButton.leadingAnchor, constant: -8),
            toolbarTitleLabel.centerYAnchor.constraint(equalTo: toolbarView.centerYAnchor)
        ])

        // 列表
        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.dataSource = self
        tableView.delegate = self
        tableView.register(LibraryItemCell.self, forCellReuseIdentifier: LibraryItemCell.reuseIdentifier)
        tableView.allowsMultipleSelectionDuringEditing = true
        view.addSubview(tableView)

        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: toolbarView.bottomAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        if #available(iOS 11.0, *) {
            tableView.contentInsetAdjustmentBehavior = .automatic
        }

        // 长按行内动作（非编辑态）
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.minimumPressDuration = 0.5
        tableView.addGestureRecognizer(longPress)

        // 空态 / 加载态
        stateView.translatesAutoresizingMaskIntoConstraints = false
        stateView.isHidden = true
        view.addSubview(stateView)

        stateLabel.translatesAutoresizingMaskIntoConstraints = false
        stateLabel.textAlignment = .center
        stateLabel.font = .systemFont(ofSize: 15)
        stateLabel.textColor = .gray
        stateLabel.numberOfLines = 2

        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        loadingIndicator.hidesWhenStopped = true
        loadingIndicator.color = .gray

        stateView.addSubview(stateLabel)
        stateView.addSubview(loadingIndicator)
        NSLayoutConstraint.activate([
            stateView.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stateView.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stateView.widthAnchor.constraint(lessThanOrEqualTo: 280),
            stateLabel.topAnchor.constraint(equalTo: stateView.topAnchor),
            stateLabel.leadingAnchor.constraint(equalTo: stateView.leadingAnchor),
            stateLabel.trailingAnchor.constraint(equalTo: stateView.trailingAnchor),
            loadingIndicator.topAnchor.constraint(equalTo: stateLabel.bottomAnchor, constant: 12),
            loadingIndicator.centerXAnchor.constraint(equalTo: stateView.centerXAnchor)
        ])

        // 底部批量操作条（仅编辑态有选择时出现）
        actionBar.translatesAutoresizingMaskIntoConstraints = false
        actionBar.backgroundColor = UIColor(white: 0.94, alpha: 1)
        actionBar.isHidden = true
        view.addSubview(actionBar)

        actionStack.translatesAutoresizingMaskIntoConstraints = false
        actionStack.axis = .horizontal
        actionStack.spacing = 12
        actionStack.alignment = .center
        actionBar.addSubview(actionStack)

        let actionBarHeight = actionBar.heightAnchor.constraint(equalToConstant: 0)
        actionBarHeightConstraint = actionBarHeight
        NSLayoutConstraint.activate([
            tableView.bottomAnchor.constraint(equalTo: actionBar.topAnchor),
            actionBar.bottomAnchor.constraint(equalTo: bottomLayoutGuide.topAnchor),
            actionBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            actionBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            actionBarHeight,
            actionStack.leadingAnchor.constraint(equalTo: actionBar.leadingAnchor, constant: 16),
            actionStack.trailingAnchor.constraint(equalTo: actionBar.trailingAnchor, constant: -16),
            actionStack.centerYAnchor.constraint(equalTo: actionBar.centerYAnchor)
        ])
    }

    // MARK: - 页签

    private func tabTitle(for filter: MediaClipFilter) -> String {
        switch filter {
        case .all: return "全部"
        case .like: return "收藏"
        case .trash: return "回收站"
        case .tagged(let tag): return tag
        }
    }

    /// 重建页签按钮：固定 全部/收藏/回收站 + 动态自定义标签。
    private func buildTabs() {
        for view in tabStack.arrangedSubviews {
            view.removeFromSuperview()
        }
        tabFilters = [.all, .like, .trash] + tagUniverse.map { .tagged($0) }
        for (index, filter) in tabFilters.enumerated() {
            let button = UIButton(type: .system)
            button.tag = index
            button.setTitle(tabTitle(for: filter), for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 14, weight: .medium)
            button.addTarget(self, action: #selector(tabTapped(_:)), for: .touchUpInside)
            tabStack.addArrangedSubview(button)
        }
        renderTabs()
    }

    private func renderTabs() {
        for case let button as UIButton in tabStack.arrangedSubviews where tabFilters.indices.contains(button.tag) {
            let active = tabFilters[button.tag] == currentFilter
            button.setTitleColor(active ? .black : .gray, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 14, weight: active ? .semibold : .medium)
        }
    }

    @objc private func tabTapped(_ sender: UIButton) {
        guard tabFilters.indices.contains(sender.tag) else { return }
        let filter = tabFilters[sender.tag]
        guard filter != currentFilter else { return }
        currentFilter = filter
        exitEditing()
        renderTabs()
        applyFilter()
    }

    // MARK: - 数据加载

    private func reloadCurrentTab() {
        requestGeneration += 1
        let generation = requestGeneration
        isLoading = true
        showLoading(true)
        MediaLibrary.shared.list(filter: nil) { [weak self] loaded in
            guard let self = self, self.requestGeneration == generation else { return }
            self.isLoading = false
            self.libraryItems = loaded
            let tags = loaded.lazy.filter { !$0.isTrashed }.flatMap { $0.tags }
            let tagUniverse = Set(tags.filter { MediaTagSpace.isCustom($0) }).sorted()
            if tagUniverse != self.tagUniverse {
                self.tagUniverse = tagUniverse
                if case .tagged(let tag) = self.currentFilter, !tagUniverse.contains(tag) {
                    self.currentFilter = .all
                    self.exitEditing()
                }
                self.buildTabs()
            }
            self.applyFilter()
        }
    }

    private func applyFilter() {
        items = MediaClipFiltering.apply(libraryItems, filter: currentFilter)
        let visibleIDs = Set(items.map { $0.mediaId })
        selectedIDs = selectedIDs.filter { visibleIDs.contains($0) }
        tableView.indexPathsForSelectedRows?.forEach { tableView.deselectRow(at: $0, animated: false) }
        tableView.reloadData()
        if isEditing {
            let selected = Set(selectedIDs)
            for (row, item) in items.enumerated() where selected.contains(item.mediaId) {
                tableView.selectRow(at: IndexPath(row: row, section: 0), animated: false, scrollPosition: .none)
            }
        }
        showLoading(isLoading)
        updateTitle()
        updateActionBar()
    }

    private func showLoading(_ loading: Bool) {
        if loading {
            loadingIndicator.startAnimating()
            stateLabel.isHidden = true
            loadingIndicator.isHidden = false
            stateView.isHidden = false
        } else {
            loadingIndicator.stopAnimating()
            if items.isEmpty {
                loadingIndicator.isHidden = true
                stateLabel.isHidden = false
                stateLabel.text = emptyStateText
                stateView.isHidden = false
            } else {
                stateView.isHidden = true
            }
        }
    }

    private var emptyStateText: String {
        switch currentFilter {
        case .all: return "还没有录制的视频\n在取景页录制后会显示在这里"
        case .like: return "还没有收藏的视频\n点击视频旁的 ♥ 即可收藏"
        case .trash: return "回收站是空的"
        case .tagged(let tag): return "没有带「\(tag)」标签的视频"
        }
    }

    private func updateTitle() {
        toolbarTitleLabel.text = "\(tabTitle(for: currentFilter))（\(items.count)）"
    }

    // MARK: - 编辑 / 选择

    @objc private func editTapped() {
        if isEditing {
            exitEditing()
        } else {
            enterEditing()
        }
    }

    private func enterEditing() {
        isEditing = true
        tableView.isEditing = true
        editButton.setTitle("完成", for: .normal)
        updateActionBar()
        tableView.reloadData()
    }

    private func exitEditing() {
        selectedIDs.removeAll()
        isEditing = false
        tableView.isEditing = false
        tableView.indexPathsForSelectedRows?.forEach { tableView.deselectRow(at: $0, animated: false) }
        editButton.setTitle("编辑", for: .normal)
        setActionBarVisible(false)
    }

    private func selectedPair() -> (MediaItem, MediaItem)? {
        guard selectedIDs.count == 2,
              let first = items.first(where: { $0.mediaId == selectedIDs[0] }),
              let second = items.first(where: { $0.mediaId == selectedIDs[1] }),
              !first.isTrashed, !second.isTrashed,
              first.durationMs > 0, second.durationMs > 0,
              let firstPath = first.absolutePath, !firstPath.isEmpty,
              let secondPath = second.absolutePath, !secondPath.isEmpty else { return nil }
        return (first, second)
    }

    // MARK: - 批量操作条

    private func setActionBarVisible(_ visible: Bool) {
        actionBarHeightConstraint?.constant = visible ? 56 : 0
        actionBar.isHidden = !visible
        if visible {
            view.layoutIfNeeded()
        }
    }

    private func updateActionBar() {
        let shouldShow = isEditing && !selectedIDs.isEmpty
        setActionBarVisible(shouldShow)
        if shouldShow {
            rebuildActionButtons()
        }
    }

    private func rebuildActionButtons() {
        for view in actionStack.arrangedSubviews {
            view.removeFromSuperview()
        }
        if currentFilter == .trash {
            let restore = UIButton(type: .system)
            restore.setTitle("还原", for: .normal)
            restore.titleLabel?.font = .systemFont(ofSize: 15)
            restore.addTarget(self, action: #selector(restoreSelectedTapped), for: .touchUpInside)

            let delete = UIButton(type: .system)
            delete.setTitle("永久删除", for: .normal)
            delete.titleLabel?.font = .systemFont(ofSize: 15)
            delete.setTitleColor(.red, for: .normal)
            delete.addTarget(self, action: #selector(deleteSelectedTapped), for: .touchUpInside)

            actionStack.addArrangedSubview(restore)
            actionStack.addArrangedSubview(delete)
        } else {
            let trash = UIButton(type: .system)
            trash.setTitle("回收站", for: .normal)
            trash.titleLabel?.font = .systemFont(ofSize: 15)
            trash.addTarget(self, action: #selector(trashSelectedTapped), for: .touchUpInside)
            let compare = UIButton(type: .system)
            compare.setTitle("双片对比", for: .normal)
            compare.titleLabel?.font = .systemFont(ofSize: 15)
            compare.isEnabled = selectedPair() != nil
            compare.accessibilityHint = "依次选择两条视频"
            compare.addTarget(self, action: #selector(compareSelectedTapped), for: .touchUpInside)
            actionStack.addArrangedSubview(compare)
            actionStack.addArrangedSubview(trash)
        }
    }

    @objc private func compareSelectedTapped() {
        guard let (first, second) = selectedPair(), presentedViewController == nil else { return }
        let compare = CompareViewController(left: first, right: second)
        compare.modalPresentationStyle = .fullScreen
        exitEditing()
        present(compare, animated: true)
    }

    @objc private func trashSelectedTapped() {
        let ids = selectedIDs
        exitEditing()
        moveToTrash(ids)
    }

    @objc private func restoreSelectedTapped() {
        let ids = selectedIDs
        exitEditing()
        restoreFromTrash(ids)
    }

    @objc private func deleteSelectedTapped() {
        confirmDeleteForever(selectedIDs)
    }

    // MARK: - 永久删除（确认后）

    private func confirmDeleteForever(_ ids: [Int64]) {
        guard !ids.isEmpty else { return }
        let alert = UIAlertController(
            title: "永久删除",
            message: "删除后无法恢复，确定永久删除选中的 \(ids.count) 个视频？",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "永久删除", style: .destructive) { [weak self] _ in
            MediaLibrary.shared.deleteForever(mediaIds: ids) { [weak self] report in
                self?.exitEditing()
                self?.reloadCurrentTab()
                let done = UIAlertController(
                    title: "删除完成",
                    message: "已删除 \(report.deleted) 个，失败 \(report.failed) 个",
                    preferredStyle: .alert
                )
                done.addAction(UIAlertAction(title: "好", style: .default))
                self?.present(done, animated: true)
            }
        })
        present(alert, animated: true)
    }

    // MARK: - 行内动作（长按）

    @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
        guard !tableView.isEditing else { return }
        let point = recognizer.location(in: tableView)
        guard let indexPath = tableView.indexPathForRow(at: point) else { return }
        guard indexPath.row < items.count else { return }
        showItemActionSheet(for: items[indexPath.row], at: recognizer.view ?? view)
    }

    private func showItemActionSheet(for item: MediaItem, at sourceView: UIView) {
        let alert = UIAlertController(title: item.fileName, message: nil, preferredStyle: .actionSheet)
        alert.addAction(UIAlertAction(title: "播放", style: .default) { [weak self] _ in
            self?.openPlayer(for: item)
        })
        if item.isTrashed {
            alert.addAction(UIAlertAction(title: "还原", style: .default) { [weak self] _ in
                self?.restoreFromTrash([item.mediaId])
            })
            alert.addAction(UIAlertAction(title: "永久删除", style: .destructive) { [weak self] _ in
                self?.confirmDeleteForever([item.mediaId])
            })
        } else {
            alert.addAction(UIAlertAction(title: item.liked ? "取消收藏" : "收藏", style: .default) { [weak self] _ in
                self?.toggleLike(for: item)
            })
            alert.addAction(UIAlertAction(title: "添加标签", style: .default) { [weak self] _ in
                self?.addTagForItem(item)
            })
            alert.addAction(UIAlertAction(title: "移至回收站", style: .destructive) { [weak self] _ in
                self?.moveToTrash([item.mediaId])
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        // iPad 上 action sheet 必须指定来源，否则会崩；iPhone 上无副作用。
        alert.popoverPresentationController?.sourceView = sourceView
        present(alert, animated: true)
    }

    // MARK: - 单条动作

    private func openPlayer(for item: MediaItem) {
        guard let path = item.absolutePath, !path.isEmpty else {
            showSimpleAlert(title: "无法播放", message: "该视频缺少文件路径。")
            return
        }
        guard presentedViewController == nil else { return }
        let player = PlayerViewController(url: URL(fileURLWithPath: path), fileName: item.fileName)
        player.allowsArcRepair = !item.isTrashed
        player.modalPresentationStyle = .fullScreen
        present(player, animated: true)
    }

    private func toggleLike(for item: MediaItem) {
        let newLiked = !item.liked
        MediaLibrary.shared.setLike(mediaId: item.mediaId, liked: newLiked) { [weak self] _ in
            self?.reloadCurrentTab()
        }
    }

    private func moveToTrash(_ ids: [Int64]) {
        MediaLibrary.shared.moveToTrash(mediaIds: ids) { [weak self] _ in
            self?.reloadCurrentTab()
        }
    }

    private func restoreFromTrash(_ ids: [Int64]) {
        MediaLibrary.shared.restoreFromTrash(mediaIds: ids) { [weak self] _ in
            self?.reloadCurrentTab()
        }
    }

    private func addTagForItem(_ item: MediaItem) {
        let alert = UIAlertController(
            title: "添加标签",
            message: "为 \(item.fileName) 添加自定义标签（≤24 字，非保留词）",
            preferredStyle: .alert
        )
        alert.addTextField { textField in
            textField.placeholder = "标签名"
            textField.autocapitalizationType = .none
            textField.autocorrectionType = .no
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "添加", style: .default) { [weak self] _ in
            guard let text = alert.textFields?.first?.text else { return }
            MediaLibrary.shared.addTag(mediaId: item.mediaId, rawTag: text) { [weak self] added in
                if added { self?.reloadCurrentTab() }
                else { self?.showSimpleAlert(title: "无法添加标签", message: "请检查标签名称，或刷新片库后重试。") }
            }
        })
        present(alert, animated: true)
    }

    private func showSimpleAlert(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .default))
        present(alert, animated: true)
    }

    // MARK: - 格式化

    private lazy var dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        return formatter
    }()

    private func formatDuration(_ ms: Int64) -> String {
        let seconds = max(0, ms / 1000)
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let secs = seconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%02d:%02d", minutes, secs)
    }

    private func formatSize(_ bytes: Int64) -> String {
        let mb = Double(bytes) / (1024.0 * 1024.0)
        if mb >= 1024 {
            return String(format: "%.2f GB", mb / 1024.0)
        }
        return String(format: "%.1f MB", mb)
    }

    private func formatDate(_ ms: Int64) -> String {
        guard ms > 0 else { return "—" }
        return dateFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(ms / 1000)))
    }

    private func buildSubtitle(for item: MediaItem) -> String {
        var parts: [String] = [
            formatDuration(item.durationMs),
            formatSize(item.sizeBytes),
            formatDate(item.createdAtMs)
        ]
        if item.seriesParts > 1 {
            parts.append("\(item.seriesParts) 段系列")
        }
        if !item.tags.isEmpty {
            parts.append(item.tags.sorted().map { "#\($0)" }.joined(separator: " "))
        }
        if item.isTrashed {
            parts.append("回收站")
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: - UITableViewDataSource / Delegate

extension CameraLibraryViewController: UITableViewDataSource, UITableViewDelegate {

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return items.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: LibraryItemCell.reuseIdentifier, for: indexPath)
            as! LibraryItemCell
        let item = items[indexPath.row]
        cell.configure(
            item: item,
            subtitle: buildSubtitle(for: item),
            onLike: { [weak self] item in
                self?.toggleLike(for: item)
            }
        )
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        guard items.indices.contains(indexPath.row) else { return }
        let item = items[indexPath.row]
        if tableView.isEditing {
            if !selectedIDs.contains(item.mediaId) { selectedIDs.append(item.mediaId) }
            updateActionBar()
        } else {
            tableView.deselectRow(at: indexPath, animated: true)
            openPlayer(for: item)
        }
    }

    func tableView(_ tableView: UITableView, didDeselectRowAt indexPath: IndexPath) {
        guard tableView.isEditing, items.indices.contains(indexPath.row) else { return }
        let id = items[indexPath.row].mediaId
        selectedIDs.removeAll { $0 == id }
        updateActionBar()
    }
}

// MARK: - 行视图

/// 媒体库单行：文件名 + 副标题（时长/大小/日期/标签/段数）+ 行内 ♥。
///
/// 编辑态下由 `UITableView` 在左侧自动呈现勾选；行尾 ♥ 为 `UIControl` 子视图，
/// 其触摸不触发行选中，故点 ♥ 只切收藏、点行其余区域才播/选。
final class LibraryItemCell: UITableViewCell {

    static let reuseIdentifier = "LibraryItemCell"

    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let heartButton = UIButton(type: .system)

    private var currentItem: MediaItem?
    private var onLike: ((MediaItem) -> Void)?

    override init(style: UITableViewCellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    private func configure() {
        backgroundColor = .white
        selectionStyle = .default

        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.font = .systemFont(ofSize: 15, weight: .medium)
        titleLabel.textColor = .black
        titleLabel.lineBreakMode = .byTruncatingMiddle
        titleLabel.numberOfLines = 1

        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        subtitleLabel.font = .systemFont(ofSize: 12)
        subtitleLabel.textColor = .gray
        subtitleLabel.lineBreakMode = .byTruncatingTail
        subtitleLabel.numberOfLines = 2

        heartButton.translatesAutoresizingMaskIntoConstraints = false
        heartButton.titleLabel?.font = .systemFont(ofSize: 18)
        heartButton.addTarget(self, action: #selector(heartTapped), for: .touchUpInside)

        contentView.addSubview(titleLabel)
        contentView.addSubview(subtitleLabel)
        contentView.addSubview(heartButton)

        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            titleLabel.trailingAnchor.constraint(equalTo: heartButton.leadingAnchor, constant: -8),
            titleLabel.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),

            subtitleLabel.leadingAnchor.constraint(equalTo: titleLabel.leadingAnchor),
            subtitleLabel.trailingAnchor.constraint(equalTo: heartButton.leadingAnchor, constant: -8),
            subtitleLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 2),
            subtitleLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10),

            heartButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            heartButton.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            heartButton.widthAnchor.constraint(equalToConstant: 30),
            heartButton.heightAnchor.constraint(equalToConstant: 28)
        ])
    }

    func configure(item: MediaItem, subtitle: String, onLike: @escaping (MediaItem) -> Void) {
        currentItem = item
        self.onLike = onLike
        titleLabel.text = item.fileName
        subtitleLabel.text = subtitle
        let liked = item.liked
        heartButton.setTitle(liked ? "♥" : "♡", for: .normal)
        heartButton.setTitleColor(liked ? .red : .gray, for: .normal)
    }

    @objc private func heartTapped() {
        if let item = currentItem {
            onLike?(item)
        }
    }
}
