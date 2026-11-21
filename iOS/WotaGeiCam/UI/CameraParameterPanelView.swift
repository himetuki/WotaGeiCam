import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

/// 可滚动的取景参数抽屉。小屏只占用部分取景区，启停按钮始终留在抽屉外。
final class CameraParameterPanelView: UIView, UITableViewDataSource, UITableViewDelegate {
    struct Row {
        let title: String
        let value: String
        let action: ((UIView) -> Void)?

        init(_ title: String, _ value: String, action: ((UIView) -> Void)? = nil) {
            self.title = title
            self.value = value
            self.action = action
        }
    }

    struct Section {
        let title: String
        let rows: [Row]
    }

    private let table = UITableView(frame: .zero, style: .plain)
    private var sections: [Section] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    func update(_ sections: [Section]) {
        self.sections = sections
        table.reloadData()
    }

    private func configure() {
        backgroundColor = UIColor(white: 0.08, alpha: 0.94)
        layer.cornerRadius = 18
        clipsToBounds = true
        table.backgroundColor = .clear
        table.separatorColor = UIColor(white: 1, alpha: 0.16)
        table.indicatorStyle = .white
        table.rowHeight = UITableView.automaticDimension
        table.estimatedRowHeight = 46
        table.dataSource = self
        table.delegate = self
        table.alwaysBounceVertical = false
        table.tableFooterView = UIView(frame: .zero)
        table.translatesAutoresizingMaskIntoConstraints = false
        addSubview(table)
        NSLayoutConstraint.activate([
            table.topAnchor.constraint(equalTo: topAnchor),
            table.bottomAnchor.constraint(equalTo: bottomAnchor),
            table.leadingAnchor.constraint(equalTo: leadingAnchor),
            table.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    func numberOfSections(in tableView: UITableView) -> Int { sections.count }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        sections[section].rows.count
    }

    func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? {
        let header = UITableViewHeaderFooterView(reuseIdentifier: nil)
        header.contentView.backgroundColor = UIColor(white: 0.16, alpha: 1)
        let label = UILabel()
        label.font = UIFont.preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.text = sections[section].title
        label.textColor = UIColor(white: 0.83, alpha: 1)
        label.accessibilityTraits = .header
        label.translatesAutoresizingMaskIntoConstraints = false
        header.contentView.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: header.contentView.leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(equalTo: header.contentView.trailingAnchor, constant: -16),
            label.centerYAnchor.constraint(equalTo: header.contentView.centerYAnchor),
        ])
        return header
    }

    func tableView(_ tableView: UITableView, heightForHeaderInSection section: Int) -> CGFloat {
        max(30, UIFont.preferredFont(forTextStyle: .caption1).lineHeight + 12)
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let row = sections[indexPath.section].rows[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: "Parameter")
            ?? UITableViewCell(style: .value1, reuseIdentifier: "Parameter")
        cell.backgroundColor = .clear
        cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .subheadline)
        cell.detailTextLabel?.font = UIFont.preferredFont(forTextStyle: .footnote)
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        cell.textLabel?.text = row.title
        cell.detailTextLabel?.text = row.value
        cell.textLabel?.textColor = .white
        cell.detailTextLabel?.textColor = UIColor(white: 0.78, alpha: 1)
        cell.accessoryType = row.action == nil ? .none : .disclosureIndicator
        cell.selectionStyle = row.action == nil ? .none : .default
        let selection = UIView()
        selection.backgroundColor = UIColor(white: 1, alpha: 0.13)
        cell.selectedBackgroundView = selection
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard let cell = tableView.cellForRow(at: indexPath) else { return }
        sections[indexPath.section].rows[indexPath.row].action?(cell)
    }
}
